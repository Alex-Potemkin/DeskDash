"""DeskDash for Windows: the phone agent + a small, quiet window to connect the phone and edit macros.

Built into DeskDash.exe / DeskDash-linux-x86_64 with build_exe.py. Runs the agent in the background
and lives in the tray (or the taskbar where the desktop has no tray).
"""
import copy
import ctypes
import json
import os
import queue
import re
import secrets
import sys
import threading
import time
import tkinter as tk
import tkinter.font as tkfont
import urllib.request
import webbrowser
from tkinter import filedialog, messagebox

IS_WIN = os.name == "nt"
if IS_WIN:
    import winreg

from PIL import Image, ImageDraw

try:
    import pystray
except Exception:  # noqa: BLE001 — e.g. PyGObject present but no GTK/AppIndicator typelibs
    os.environ["PYSTRAY_BACKEND"] = "xorg"
    try:
        import pystray
    except Exception:  # noqa: BLE001 — no tray at all; the window minimises instead
        pystray = None

import deskdash_agent as agent
import spotify

BG, HOVER, LINE = "#141110", "#1D1816", "#2A2320"
TEXT, DIM, FAINT, ACCENT, DANGER = "#EDE6DD", "#8A7F76", "#5A514B", "#C8A27C", "#E5534B"
APP_ID = "DeskDash"
RUN_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"

# Segoe Fluent Icons code points (Segoe MDL2 Assets on Windows 10 has the same ones)
ICON_GLYPH = {
    "bolt": "", "web": "", "settings": "", "open": "", "lock": "",
    "mute": "", "vol_up": "", "vol_down": "", "play": "", "pause": "",
    "next": "", "prev": "", "desktop": "", "sleep": "", "screenshot": "",
    "power": "", "music": "", "eye_off": "",
}
ICON_NAMES = {
    "bolt": "молния", "web": "сайт", "settings": "настройки", "open": "открыть", "lock": "замок",
    "mute": "без звука", "vol_up": "громче", "vol_down": "тише", "play": "плей", "pause": "пауза",
    "next": "вперёд", "prev": "назад", "desktop": "монитор", "sleep": "сон", "screenshot": "скриншот",
    "power": "питание", "music": "музыка", "eye_off": "экран",
}
NAV = [("conn", "", "Подключение"), ("macros", "", "Макросы"),
       ("spotify", "", "Spotify"), ("settings", "", "Настройки")]
DEVICE_GLYPH = {"Computer": "", "Smartphone": "", "Tablet": "", "Speaker": "",
                "TV": "", "CastVideo": "", "CastAudio": "", "AVR": "",
                "GameConsole": "", "Automobile": ""}
DEVICE_RU = {"Computer": "компьютер", "Smartphone": "телефон", "Tablet": "планшет", "Speaker": "колонка",
             "TV": "телевизор", "CastVideo": "Chromecast", "CastAudio": "Chromecast", "AVR": "ресивер",
             "GameConsole": "консоль", "Automobile": "автомобиль"}

KINDS = [("keys", "клавиши"), ("site", "сайт"), ("settings", "настройки Windows"),
         ("run", "программа"), ("file", "файл или папка"), ("system", "система"), ("wait", "пауза, мс")]
if not IS_WIN:
    KINDS = [k for k in KINDS if k[0] != "settings"]
KIND_LABEL = dict(KINDS)
SYSTEM = [("lock", "заблокировать"), ("monitor_off", "погасить экран"), ("sleep", "сон"),
          ("hibernate", "гибернация"), ("shutdown", "выключить"), ("restart", "перезагрузить")]
SYSTEM_LABEL = dict(SYSTEM)
DANGEROUS = {"sleep", "hibernate", "shutdown", "restart"}
WIN_SETTINGS = [
    ("Звук", "ms-settings:sound"), ("Bluetooth", "ms-settings:bluetooth"),
    ("Wi-Fi", "ms-settings:network-wifi"), ("Экран", "ms-settings:display"),
    ("Батарея и питание", "ms-settings:batterysaver"), ("Уведомления", "ms-settings:notifications"),
    ("Не беспокоить", "ms-settings:quiethours"), ("Персонализация", "ms-settings:personalization"),
    ("Приложения", "ms-settings:appsfeatures"), ("Автозагрузка", "ms-settings:startupapps"),
    ("Центр обновления", "ms-settings:windowsupdate"), ("Мышь и тачпад", "ms-settings:mousetouchpad"),
    ("Принтеры", "ms-settings:printers"), ("Память", "ms-settings:storagesense"),
    ("Сеть", "ms-settings:network-status"), ("Параметры", "ms-settings:"),
]
SETTINGS_LABEL = dict((u, n) for n, u in WIN_SETTINGS)
TEMPLATES = [
    ("Пустой", "Новый", "bolt", False, [("keys", "")]),
    ("Открыть сайт", "Сайт", "web", False, [("site", "https://")]),
    ("Настройки Windows", "Звук", "settings", False, [("settings", "ms-settings:sound")]),
    ("Запустить программу", "Программа", "open", False, [("run", "")]),
    ("Открыть файл или папку", "Папка", "open", False, [("file", "")]),
    ("Сочетание клавиш", "Клавиши", "bolt", False, [("keys", "")]),
    ("Выключить компьютер", "Выкл", "power", True, [("system", "shutdown")]),
]
if not IS_WIN:
    TEMPLATES = [t for t in TEMPLATES if t[4][0][0] != "settings"]
MOD_KEYSYMS = {"Shift_L", "Shift_R", "Control_L", "Control_R", "Alt_L", "Alt_R", "Win_L", "Win_R"}
KEYSYM_NAMES = {
    "Return": "enter", "Escape": "esc", "Tab": "tab", "space": "space", "BackSpace": "backspace",
    "Delete": "delete", "Insert": "insert", "Home": "home", "End": "end", "Prior": "pgup",
    "Next": "pgdn", "Left": "left", "Up": "up", "Right": "right", "Down": "down", "Print": "printscreen",
}


# ================================================================ helpers

def make_icon(size):
    s = size
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([0, 0, s - 1, s - 1], radius=int(s * 0.22), fill=BG)
    w = max(2, int(s * 0.07))
    d.ellipse([s * 0.2, s * 0.2, s * 0.8, s * 0.8], outline=TEXT, width=w)
    c = s / 2
    d.line([(c, s * 0.32), (c, c), (s * 0.63, s * 0.6)], fill=ACCENT, width=w, joint="curve")
    return img


edit_config = agent.update_config


def autostart_command():
    if getattr(sys, "frozen", False):
        return f'"{sys.executable}" --minimized'
    if not IS_WIN:
        return f'"{sys.executable}" "{os.path.abspath(__file__)}" --minimized'
    pyw = os.path.join(os.path.dirname(sys.executable), "pythonw.exe")
    return f'"{pyw}" "{os.path.abspath(__file__)}" --minimized'


AUTOSTART_DESKTOP = os.path.join(os.environ.get("XDG_CONFIG_HOME") or os.path.expanduser("~/.config"),
                                 "autostart", "deskdash.desktop")


def get_autostart():
    if not IS_WIN:
        return os.path.exists(AUTOSTART_DESKTOP)
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY) as k:
            winreg.QueryValueEx(k, APP_ID)
            return True
    except OSError:
        return False


def set_autostart(on):
    if not IS_WIN:
        if on:
            os.makedirs(os.path.dirname(AUTOSTART_DESKTOP), exist_ok=True)
            with open(AUTOSTART_DESKTOP, "w", encoding="utf-8") as f:
                f.write("[Desktop Entry]\nType=Application\nName=DeskDash\n"
                        f"Exec={autostart_command()}\nX-GNOME-Autostart-enabled=true\n")
        elif os.path.exists(AUTOSTART_DESKTOP):
            os.remove(AUTOSTART_DESKTOP)
        return
    with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY, 0, winreg.KEY_SET_VALUE) as k:
        if on:
            winreg.SetValueEx(k, APP_ID, 0, winreg.REG_SZ, autostart_command())
        else:
            try:
                winreg.DeleteValue(k, APP_ID)
            except OSError:
                pass


def rank_ips(ips):
    """Home-LAN address first: VPN/TUN adapters (198.18.x, ZeroTier, …) are useless for the phone.
    An address in the same /24 as an already connected phone wins outright."""
    phones = {ip.rsplit(".", 1)[0] for ip, (_, ok) in agent.CLIENTS.items() if not ip.startswith("127.")}

    def score(ip):
        if ip.rsplit(".", 1)[0] in phones:
            return 0
        if ip.startswith("192.168."):
            return 1
        if ip.startswith("10."):
            return 2
        if re.match(r"^172\.(1[6-9]|2\d|3[01])\.", ip):
            return 3
        return 9  # 198.18.x, 169.254.x, public addresses

    return sorted(ips, key=score)


def ago(t):
    s = int(time.time() - t)
    if s < 60:
        return f"{s} с назад"
    if s < 3600:
        return f"{s // 60} мин назад"
    return time.strftime("%H:%M", time.localtime(t))


def dark_title_bar(win):
    """Dark, background-coloured native title bar (Windows 10 20H1+ / 11)."""
    if not IS_WIN:
        return
    try:
        hwnd = ctypes.windll.user32.GetParent(win.winfo_id())
        dwm = ctypes.windll.dwmapi
        on = ctypes.c_int(1)
        dwm.DwmSetWindowAttribute(hwnd, 20, ctypes.byref(on), 4)  # DWMWA_USE_IMMERSIVE_DARK_MODE

        def colorref(hex_color):
            r, g, b = int(hex_color[1:3], 16), int(hex_color[3:5], 16), int(hex_color[5:7], 16)
            return ctypes.c_int(r | g << 8 | b << 16)

        dwm.DwmSetWindowAttribute(hwnd, 35, ctypes.byref(colorref(BG)), 4)  # caption colour (Win 11)
        dwm.DwmSetWindowAttribute(hwnd, 36, ctypes.byref(colorref(DIM)), 4)  # caption text
        dwm.DwmSetWindowAttribute(hwnd, 34, ctypes.byref(colorref(BG)), 4)  # border
    except Exception:  # noqa: BLE001 — cosmetic, older Windows
        pass


# ================================================================ macro model

def kind_of(step):
    for k in ("keys", "run", "system", "wait"):
        if k in step:
            return k
    v = str(step.get("open", ""))
    if v.startswith("ms-settings:"):
        return "settings"
    if re.match(r"^(https?://|www\.)", v, re.I):
        return "site"
    return "file"


def from_cfg_step(s):
    k = kind_of(s)
    key = "open" if k in ("site", "settings", "file") else k
    return {"kind": k, "value": str(s.get(key, "")), "repeat": int(s.get("repeat", 1) or 1)}


def to_cfg_step(st):
    k, v = st["kind"], st["value"].strip()
    if k == "site":
        if v and not re.match(r"^[a-z][a-z0-9+.-]*:", v, re.I):
            v = "https://" + v
        return {"open": v}
    if k in ("settings", "file"):
        return {"open": v}
    if k == "wait":
        try:
            return {"wait": max(0, int(float(v)))}
        except ValueError:
            return {"wait": 500}
    out = {k: v}
    if k == "keys" and st.get("repeat", 1) > 1:
        out["repeat"] = st["repeat"]
    return out


def normalize(m):
    steps = m.get("steps") or [{k: m[k] for k in ("keys", "run", "open", "system", "wait", "repeat") if k in m}]
    steps = [from_cfg_step(s) for s in steps if any(k in s for k in ("keys", "run", "open", "system", "wait"))]
    return {"id": str(m.get("id", "")), "label": str(m.get("label", m.get("id", ""))),
            "icon": m.get("icon", "bolt"), "confirm": bool(m.get("confirm", False)),
            "steps": steps or [{"kind": "keys", "value": "", "repeat": 1}]}


def denormalize(m):
    out = {"id": m["id"], "label": m["label"], "icon": m["icon"]}
    if m["confirm"]:
        out["confirm"] = True
    steps = [to_cfg_step(s) for s in m["steps"]]
    if len(steps) == 1:
        out.update(steps[0])
    else:
        out["steps"] = steps
    return out


def validate(m):
    name = m["label"] or m["id"] or "без названия"
    if not m["id"]:
        return f"«{name}»: пустой ID"
    if "/" in m["id"]:
        return f"«{name}»: в ID нельзя «/»"
    for s in m["steps"]:
        v = s["value"].strip()
        if s["kind"] in ("keys", "run", "file", "settings") and not v:
            return f"«{name}»: заполни шаг «{KIND_LABEL[s['kind']]}»"
        if s["kind"] == "site" and v.lower() in ("", "https://", "http://"):
            return f"«{name}»: укажи адрес сайта"
        if s["kind"] == "keys":
            try:
                for k in v.split("+"):
                    agent.vk_of(k)
            except ValueError:
                return f"«{name}»: неизвестная клавиша в «{v}»"
    return None


# ================================================================ tiny UI kit

class F:
    """Fonts, resolved once the Tk root exists (Segoe UI Variable on Windows 11, Segoe UI otherwise)."""
    display = text = semi = icon = mono = None

    @classmethod
    def init(cls):
        fams = set(tkfont.families())

        def pick(*names):
            return next((n for n in names if n in fams), "Segoe UI" if IS_WIN else "TkDefaultFont")

        cls.display = pick("Segoe UI Variable Display Light", "Segoe UI Light", "Inter Light", "Inter",
                           "Cantarell", "Ubuntu Light", "Noto Sans", "DejaVu Sans")
        cls.text = pick("Segoe UI Variable Text", "Segoe UI", "Inter", "Cantarell", "Ubuntu", "Noto Sans",
                        "DejaVu Sans")
        cls.semi = pick("Segoe UI Variable Text Semibold", "Segoe UI Semibold", "Inter SemiBold", "Inter",
                        "Cantarell", "Ubuntu", "Noto Sans", "DejaVu Sans")
        cls.icon = next((n for n in ("Segoe Fluent Icons", "Segoe MDL2 Assets") if n in fams), None)
        cls.mono = pick("Cascadia Mono Light", "Cascadia Mono", "Consolas", "JetBrains Mono", "Ubuntu Mono",
                        "DejaVu Sans Mono", "Liberation Mono")


def glyph(ch):
    """Icon-font glyph where Segoe Fluent Icons exists, a quiet dot elsewhere (Linux)."""
    return ch if F.icon else "\u2022"


def bg_of(w):
    try:
        return w.cget("bg")
    except tk.TclError:
        return BG


def txt(parent, s="", size=10, color=TEXT, font=None, **kw):
    return tk.Label(parent, text=s, bg=bg_of(parent), fg=color, font=(font or F.text, size),
                    bd=0, padx=0, pady=0, **kw)


def caption(parent, s):
    """Small, quiet section heading."""
    return txt(parent, s.upper(), 8, FAINT, F.semi)


def link(parent, s, command, color=DIM, hover=TEXT, size=10, font=None):
    lbl = txt(parent, s, size, color, font, cursor="hand2")
    lbl.bind("<Enter>", lambda e: lbl.config(fg=hover))
    lbl.bind("<Leave>", lambda e: lbl.config(fg=lbl.base_color))
    lbl.bind("<Button-1>", lambda e: command())
    lbl.base_color = color
    return lbl


def set_link_color(lbl, color):
    lbl.base_color = color
    lbl.config(fg=color)


class Field(tk.Frame):
    """Borderless entry with a hairline underneath that lights up on focus."""

    def __init__(self, parent, var, size=11, font=None, width=None, on_change=None, on_commit=None,
                 placeholder=""):
        super().__init__(parent, bg=bg_of(parent))
        self.var = var
        self.placeholder = placeholder
        self.entry = tk.Entry(self, textvariable=var, bg=bg_of(parent), fg=TEXT, insertbackground=TEXT,
                              relief="flat", bd=0, highlightthickness=0, font=(font or F.text, size),
                              selectbackground=LINE, selectforeground=TEXT, disabledbackground=bg_of(parent))
        if width:
            self.entry.config(width=width)
        self.entry.pack(fill="x", pady=(0, 4))
        self.line = tk.Frame(self, bg=LINE, height=1)
        self.line.pack(fill="x")
        self.entry.bind("<FocusIn>", lambda e: self.line.config(bg=ACCENT))
        self.entry.bind("<FocusOut>", lambda e: (self.line.config(bg=LINE), on_commit and on_commit()))
        if on_commit:
            self.entry.bind("<Return>", lambda e: on_commit())
        if on_change:
            var.trace_add("write", lambda *_: on_change())


class Toggle(tk.Label):
    """"●  text" / "○  text" switch."""

    def __init__(self, parent, text, value, command):
        super().__init__(parent, bg=bg_of(parent), font=(F.text, 10), cursor="hand2", bd=0, anchor="w")
        self.text, self.value, self.command = text, bool(value), command
        self.bind("<Button-1>", lambda e: self.flip())
        self._paint()

    def flip(self):
        self.value = not self.value
        self._paint()
        self.command(self.value)

    def set(self, v):
        self.value = bool(v)
        self._paint()

    def _paint(self):
        self.config(text=("●   " if self.value else "○   ") + self.text, fg=TEXT if self.value else DIM)


def popup(widget, options, command):
    """Quiet dropdown: a native menu under the widget. options: [(value, label)]."""
    m = tk.Menu(widget, tearoff=0, bg=HOVER, fg=TEXT, activebackground=LINE, activeforeground=TEXT,
                bd=0, relief="flat", font=(F.text, 10))
    for value, lbl in options:
        m.add_command(label=lbl, command=lambda v=value: command(v))
    m.tk_popup(widget.winfo_rootx(), widget.winfo_rooty() + widget.winfo_height() + 2)


class Scroll(tk.Frame):
    """Vertically scrollable area without a visible scrollbar (mouse wheel only)."""

    def __init__(self, parent, **kw):
        super().__init__(parent, bg=bg_of(parent), **kw)
        self.canvas = tk.Canvas(self, bg=bg_of(parent), highlightthickness=0, bd=0)
        self.canvas.pack(fill="both", expand=True)
        self.inner = tk.Frame(self.canvas, bg=bg_of(parent))
        self.win = self.canvas.create_window(0, 0, window=self.inner, anchor="nw")
        self.inner.bind("<Configure>", lambda e: self.canvas.configure(scrollregion=self.canvas.bbox("all")))
        self.canvas.bind("<Configure>", lambda e: self.canvas.itemconfigure(self.win, width=e.width))
        for w in (self.canvas, self.inner):
            w.bind("<Enter>", lambda e: self.canvas.bind_all("<MouseWheel>", self._wheel))
            w.bind("<Leave>", lambda e: self.canvas.unbind_all("<MouseWheel>"))

    def _wheel(self, e):
        if self.inner.winfo_height() > self.canvas.winfo_height():
            self.canvas.yview_scroll(int(-e.delta / 120) * 2, "units")


# ================================================================ app

class App(tk.Tk):
    def __init__(self, server, error, minimized):
        super().__init__()
        F.init()
        self.server = server
        self.error = error
        self.events = queue.Queue()
        self.sp_devices = []
        self.sp_error = ""
        self.title("DeskDash")
        self.geometry("920x600")
        self.minsize(780, 520)
        self.configure(bg=BG)
        self.option_add("*Menu.font", (F.text, 10))
        ico = os.path.join(agent.DATA_DIR, "icon.ico")
        try:
            make_icon(256).save(ico, sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (256, 256)])
            self.iconbitmap(ico)
        except Exception:  # noqa: BLE001 — cosmetic
            pass

        side = tk.Frame(self, bg=BG, width=200)
        side.pack(side="left", fill="y")
        side.pack_propagate(False)
        tk.Frame(self, bg=LINE, width=1).pack(side="left", fill="y", pady=28)
        self.body = tk.Frame(self, bg=BG)
        self.body.pack(side="left", fill="both", expand=True)

        head = tk.Frame(side, bg=BG)
        head.pack(fill="x", padx=28, pady=(30, 36))
        txt(head, "DeskDash", 15, TEXT, F.display).pack(anchor="w")
        self.state_lbl = txt(head, "", 8, DIM)
        self.state_lbl.pack(anchor="w", pady=(4, 0))
        if error:
            self.state_lbl.config(text=f"●  агент не запущен: {error}", fg=DANGER)
        else:
            self.state_lbl.config(text=f"●  в сети · порт {agent.config()['port']}")

        self.nav = {}
        for key, icon, name in NAV:
            row = tk.Frame(side, bg=BG, cursor="hand2")
            row.pack(fill="x", pady=1)
            bar = tk.Frame(row, bg=BG, width=2)
            bar.pack(side="left", fill="y")
            ic = txt(row, glyph(icon), 11, DIM, F.icon)
            ic.pack(side="left", padx=(26, 12), pady=9)
            lb = txt(row, name, 10, DIM)
            lb.pack(side="left")
            for w in (row, ic, lb):
                w.bind("<Button-1>", lambda e, k=key: self.show_page(k))
                w.bind("<Enter>", lambda e, k=key: self._nav_hover(k, True))
                w.bind("<Leave>", lambda e, k=key: self._nav_hover(k, False))
            self.nav[key] = (bar, ic, lb)
        txt(side, "v1.1", 8, FAINT).pack(side="bottom", anchor="w", padx=28, pady=24)

        self.pages = {
            "conn": ConnectionPage(self.body, self),
            "macros": MacroEditor(self.body, self),
            "spotify": SpotifyPage(self.body, self),
            "settings": SettingsPage(self.body, self),
        }
        self.editor = self.pages["macros"]
        self.spotify_tab = self.pages["spotify"]
        self.current = None
        self.show_page("conn")

        self.protocol("WM_DELETE_WINDOW", self.on_close)
        agent.ON_SHOW = lambda: self.events.put("show")
        try:
            self.tray = pystray.Icon(APP_ID, make_icon(64), "DeskDash", menu=pystray.Menu(self._tray_items))
            self.tray.run_detached()
        except Exception:  # noqa: BLE001 — no tray on this desktop: the window just minimises
            self.tray = None
        self.tray_hint_shown = False
        self.visible = not minimized
        threading.Thread(target=self._device_loop, daemon=True).start()
        self.update_idletasks()
        dark_title_bar(self)
        if minimized:
            self.withdraw()
        self.bind_all("<Control-s>", lambda e: self.editor.save())
        self.bind_all("<Control-q>", lambda e: self.quit_app())
        self._pump()

    # ---------------------------------------------------------- navigation

    def show_page(self, key):
        if key == self.current:
            return
        if self.current:
            self.pages[self.current].pack_forget()
        self.current = key
        self.pages[key].pack(fill="both", expand=True, padx=(44, 40), pady=(34, 24))
        for k, (bar, ic, lb) in self.nav.items():
            on = k == key
            bar.config(bg=ACCENT if on else BG)
            ic.config(fg=ACCENT if on else DIM)
            lb.config(fg=TEXT if on else DIM)

    def _nav_hover(self, key, inside):
        if key != self.current:
            _, ic, lb = self.nav[key]
            lb.config(fg=TEXT if inside else DIM)
            ic.config(fg=TEXT if inside else DIM)

    # ---------------------------------------------------------- tray

    def _tray_items(self):
        M = pystray.MenuItem
        items = [M("Открыть DeskDash", lambda: self.events.put("show"), default=True), pystray.Menu.SEPARATOR]
        macros = agent.config().get("macros", [])
        if macros:
            items.append(M("Макросы", pystray.Menu(*[
                M(m.get("label") or m.get("id", "?"), self._tray_macro(m)) for m in macros])))
        if spotify.status()["logged_in"]:
            devs = [M(("● " if d["active"] else "    ") + d["name"], self._tray_transfer(d["id"]))
                    for d in self.sp_devices]
            items.append(M("Spotify: играть на", pystray.Menu(
                *(devs or [M("Нет устройств: открой Spotify", None, enabled=False)]))))
        items += [pystray.Menu.SEPARATOR, M("Выход", lambda: self.events.put("quit"))]
        return items

    def _tray_macro(self, m):
        def run():
            def work():
                try:
                    agent.run_macro(m)
                    agent.log(f"макрос «{m.get('label', m.get('id'))}» из трея")
                except Exception as e:  # noqa: BLE001 — surface any failure in the log
                    agent.log(f"макрос «{m.get('label', m.get('id'))}»: ошибка {e}")
            threading.Thread(target=work, daemon=True).start()
        return run

    def _tray_transfer(self, device_id):
        return lambda: self.transfer(device_id)

    def refresh_tray(self):
        try:
            if self.tray:
                self.tray.update_menu()
        except Exception:  # noqa: BLE001 — tray not ready yet
            pass

    # ---------------------------------------------------------- spotify devices

    def _device_loop(self):
        """Keeps the Spotify device list fresh for the page and the tray menu."""
        while True:
            self.fetch_devices()
            time.sleep(8 if self.visible else 30)

    def fetch_devices(self):
        if not spotify.status()["logged_in"]:
            self.events.put(("devices", [], ""))
            return
        try:
            self.events.put(("devices", spotify.devices(), ""))
        except spotify.SpotifyError as e:
            self.events.put(("devices", self.sp_devices, str(e)))

    def transfer(self, device_id):
        def work():
            try:
                spotify.transfer(device_id)
                agent.log("Spotify: устройство переключено")
                time.sleep(1.2)
                self.fetch_devices()
            except spotify.SpotifyError as e:
                self.events.put(("devices", self.sp_devices, str(e)))
        threading.Thread(target=work, daemon=True).start()

    # ---------------------------------------------------------- lifecycle

    def _pump(self):
        try:
            while True:
                ev = self.events.get_nowait()
                if ev == "show":
                    self.show()
                elif ev == "quit":
                    self.quit_app()
                elif isinstance(ev, tuple) and ev[0] == "devices":
                    _, self.sp_devices, self.sp_error = ev
                    self.spotify_tab.render_devices()
                    self.refresh_tray()
        except queue.Empty:
            pass
        self.after(150, self._pump)

    def show(self):
        self.visible = True
        self.deiconify()
        self.lift()
        self.focus_force()

    def tray_usable(self):
        """False when there is nowhere to hide: no tray library, or no tray on this Linux desktop."""
        if not self.tray:
            return False
        if IS_WIN:
            return True
        if type(self.tray).__module__.endswith("_xorg"):
            return bool(getattr(self.tray, "_systray_manager", None))  # None: the icon never docked
        return self.tray.HAS_MENU

    def on_close(self):
        if not self.tray_usable():
            # Linux desktops without a usable tray (stock GNOME): keep the window reachable via the taskbar
            self.editor.save(quiet=True)
            self.iconify()
            return
        if agent.config().get("tray_on_close", True):
            self.editor.save(quiet=True)
            self.withdraw()
            self.visible = False
            if not self.tray_hint_shown:
                self.tray_hint_shown = True
                try:
                    self.tray.notify("DeskDash работает в трее. Выход через меню значка.", "DeskDash")
                except Exception:  # noqa: BLE001 — notifications may be disabled
                    pass
        else:
            self.quit_app()

    def quit_app(self):
        if not self.editor.confirm_discard():
            self.show()
            return
        try:
            if self.tray:
                self.tray.stop()
        except Exception:  # noqa: BLE001
            pass
        if self.server:
            threading.Thread(target=self.server.shutdown, daemon=True).start()
        self.destroy()


# ================================================================ connection page

class ConnectionPage(tk.Frame):
    def __init__(self, parent, app):
        super().__init__(parent, bg=BG)
        self.app = app

        top = tk.Frame(self, bg=BG)
        top.pack(fill="x")
        ipc = tk.Frame(top, bg=BG)
        ipc.pack(side="left", fill="x", expand=True)
        caption(ipc, "IP ноутбука").pack(anchor="w")
        self.ip_lbl = link(ipc, "", lambda: self._copy(self.ip_lbl.cget("text"), "IP"), TEXT, ACCENT, 34, F.display)
        self.ip_lbl.pack(anchor="w", pady=(2, 0))
        self.ip_other = txt(ipc, "", 8, FAINT)
        self.ip_other.pack(anchor="w")

        tkc = tk.Frame(top, bg=BG)
        tkc.pack(side="left", fill="x", expand=True)
        caption(tkc, "Токен").pack(anchor="w")
        self.token_lbl = link(tkc, "", lambda: self._copy(self.token_lbl.cget("text"), "токен"), TEXT, ACCENT, 34,
                              F.display)
        self.token_lbl.pack(anchor="w", pady=(2, 0))
        link(tkc, "новый токен", self._new_token, FAINT, DIM, 8).pack(anchor="w")

        self.hint = txt(self, "нажми на адрес или токен, чтобы скопировать", 9, FAINT)
        self.hint.pack(anchor="w", pady=(18, 0))

        caption(self, "Телефоны").pack(anchor="w", pady=(38, 8))
        self.clients = tk.Frame(self, bg=BG)
        self.clients.pack(fill="x")

        caption(self, "Если телефон не видит ноутбук").pack(anchor="w", pady=(34, 8))
        fix = tk.Frame(self, bg=BG)
        fix.pack(anchor="w")
        if IS_WIN:
            link(fix, "разрешить в брандмауэре", self._firewall).pack(side="left")
            txt(fix, "   ·   ", 10, FAINT).pack(side="left")
            link(fix, "сделать сеть частной", lambda: os.startfile("ms-settings:network-status")).pack(side="left")
        else:
            port = agent.config()["port"]
            ufw = f"sudo ufw allow {port}/tcp && sudo ufw allow {agent.DISCOVERY_PORT}/udp"
            txt(fix, ufw, 10, DIM, F.mono).pack(side="left")
            link(fix, "копировать", lambda: self._copy(ufw, "команда"), FAINT, DIM, 9).pack(side="left", padx=(14, 0))

        self.log_lbl = txt(self, "", 8, FAINT, F.mono, justify="left", anchor="w")
        self.log_lbl.pack(side="bottom", anchor="w", fill="x")
        self._refresh()

    def _refresh(self):
        cfg = agent.config()
        ips = rank_ips(agent.local_ips())
        main = ips[0] if ips else "?"
        if self.ip_lbl.cget("text") != main:
            self.ip_lbl.config(text=main)
        others = [i for i in ips if i != main]
        self.ip_other.config(text=("также " + ",  ".join(others)) if others else "")
        if self.token_lbl.cget("text") != cfg["token"]:
            self.token_lbl.config(text=cfg["token"])

        for w in self.clients.winfo_children():
            w.destroy()
        rows = sorted(agent.CLIENTS.items(), key=lambda kv: -kv[1][0])
        for ip, (t, ok) in rows:
            fresh = time.time() - t < 15
            r = tk.Frame(self.clients, bg=BG)
            r.pack(anchor="w", pady=2)
            txt(r, "●" if fresh and ok else "○", 9, ACCENT if fresh and ok else (DANGER if not ok else FAINT)).pack(
                side="left")
            txt(r, ip, 11, TEXT if ok else DIM).pack(side="left", padx=(12, 16))
            state = ("на связи" if fresh else "был " + ago(t)) if ok else "неверный токен"
            txt(r, state, 9, DIM).pack(side="left")
        if not rows:
            txt(self.clients, "Пока никто не подключался.  На телефоне:  ⋮  →  Настройки  →  Найти ПК в сети",
                10, DIM).pack(anchor="w")
        self.log_lbl.config(text="\n".join(list(agent.LOG)[-4:]))
        self.after(2000, self._refresh)

    def _copy(self, text, what):
        self.clipboard_clear()
        self.clipboard_append(text)
        self.hint.config(text=f"{what} скопирован", fg=ACCENT)
        self.after(1800, lambda: self.hint.config(text="нажми на адрес или токен, чтобы скопировать", fg=FAINT))

    def _new_token(self):
        if messagebox.askyesno("Новый токен", "Сгенерировать новый токен? На телефоне его нужно будет ввести заново.",
                               parent=self):
            edit_config(lambda c: c.__setitem__("token", secrets.token_hex(4)))
            agent.log("выдан новый токен")

    def _firewall(self):
        port = agent.config()["port"]
        cmd = (f'/c netsh advfirewall firewall delete rule name="DeskDash TCP" & '
               f'netsh advfirewall firewall delete rule name="DeskDash UDP" & '
               f'netsh advfirewall firewall add rule name="DeskDash TCP" dir=in action=allow protocol=TCP localport={port} profile=private,domain & '
               f'netsh advfirewall firewall add rule name="DeskDash UDP" dir=in action=allow protocol=UDP localport={agent.DISCOVERY_PORT} profile=private,domain')
        r = ctypes.windll.shell32.ShellExecuteW(None, "runas", "cmd.exe", cmd, None, 0)
        agent.log("правила брандмауэра добавлены" if r > 32 else "брандмауэр: отменено")


# ================================================================ settings page

class SettingsPage(tk.Frame):
    def __init__(self, parent, app):
        super().__init__(parent, bg=BG)
        self.app = app
        cfg = agent.config()
        self.name_var = tk.StringVar(value=cfg.get("name", ""))
        self.port_var = tk.StringVar(value=str(cfg.get("port", 8765)))
        self.mac_var = tk.StringVar(value=cfg.get("mac", ""))

        txt(self, "Настройки", 20, TEXT, F.display).pack(anchor="w", pady=(0, 28))
        grid = tk.Frame(self, bg=BG)
        grid.pack(fill="x")
        grid.columnconfigure(2, weight=1)

        def row(r, title, widget_factory, hint=""):
            txt(grid, title, 10, DIM).grid(row=r, column=0, sticky="nw", pady=(0, 22), padx=(0, 36))
            w = widget_factory(grid)
            w.grid(row=r, column=1, sticky="w", pady=(0, 22))
            if hint:
                txt(grid, hint, 8, FAINT).grid(row=r, column=2, sticky="nw", padx=(18, 0), pady=(3, 0))

        row(0, "Имя на телефоне", lambda p: Field(p, self.name_var, 11, width=28, on_commit=self.save))
        row(1, "Порт", lambda p: Field(p, self.port_var, 11, width=8, on_commit=self.save),
            "применится после перезапуска")
        row(2, "MAC для пробуждения", lambda p: Field(p, self.mac_var, 11, F.mono, width=20, on_commit=self.save),
            f"пусто — {agent.default_mac()}")
        row(3, "Автозапуск", lambda p: Toggle(p, "вместе с Windows, свёрнутым в трей", get_autostart(),
                                              self._autostart))
        row(4, "Кнопка «закрыть»", lambda p: Toggle(p, "сворачивает в трей", cfg.get("tray_on_close", True),
                                                    lambda v: self._set("tray_on_close", v)))

        link(self, "открыть папку с настройками", lambda: agent.sysapi.open_target(agent.DATA_DIR), FAINT, DIM, 9).pack(
            anchor="w", pady=(10, 0))
        self.status = txt(self, "", 9, DIM)
        self.status.pack(side="bottom", anchor="w")

    def _say(self, msg, error=False):
        self.status.config(text=msg, fg=DANGER if error else DIM)
        if not error:
            self.after(1800, lambda: self.status.config(text=""))

    def _set(self, key, value):
        edit_config(lambda c: c.__setitem__(key, value))
        self._say("сохранено")

    def _autostart(self, on):
        try:
            set_autostart(on)
            self._say("сохранено")
        except OSError as e:
            self._say(f"автозапуск: {e}", True)

    def save(self):
        cfg = agent.config()
        try:
            port = int(self.port_var.get())
            assert 1024 <= port <= 65535
        except (ValueError, AssertionError):
            self._say("порт — число от 1024 до 65535", True)
            return
        name = self.name_var.get().strip() or cfg["name"]
        mac = self.mac_var.get().strip()
        if (name, port, mac) == (cfg["name"], cfg["port"], cfg.get("mac", "")):
            return

        def upd(c):
            c["name"], c["port"], c["mac"] = name, port, mac

        edit_config(upd)
        self._say("сохранено" + (" · порт изменится после перезапуска" if port != cfg["port"] else ""))


# ================================================================ spotify page

class SpotifyPage(tk.Frame):
    def __init__(self, parent, app):
        super().__init__(parent, bg=BG)
        self.app = app
        self.logged = None
        self.cid_var = tk.StringVar(value=agent.config().get("spotify", {}).get("client_id", ""))

        head = tk.Frame(self, bg=BG)
        head.pack(fill="x", pady=(0, 28))
        self.title_lbl = txt(head, "Spotify", 20, TEXT, F.display)
        self.title_lbl.pack(side="left")
        self.logout = link(head, "выйти", self._logout, FAINT, DIM, 9)

        # setup, shown until logged in
        self.setup = tk.Frame(self, bg=BG)
        txt(self.setup, "Выбирай, где играет музыка: здесь, в трее и на телефоне. Нужен Spotify Premium.",
            10, DIM).pack(anchor="w", pady=(0, 26))

        def step(n, text):
            r = tk.Frame(self.setup, bg=BG)
            r.pack(anchor="w", fill="x", pady=(0, 6))
            txt(r, n, 10, FAINT).pack(side="left", padx=(0, 14))
            txt(r, text, 10, TEXT).pack(side="left")
            return r

        s1 = step("1", "Создай приложение в панели разработчика Spotify")
        link(s1, "открыть панель  →", lambda: webbrowser.open(spotify.DASHBOARD_URL), ACCENT, TEXT).pack(
            side="left", padx=(14, 0))
        step("2", "В Redirect URIs добавь этот адрес, отметь Web API")
        r2 = tk.Frame(self.setup, bg=BG)
        r2.pack(anchor="w", pady=(2, 18), padx=(26, 0))
        self.redirect = txt(r2, "", 10, DIM, F.mono)
        self.redirect.pack(side="left")
        self.copy_lbl = link(r2, "копировать", self._copy_redirect, FAINT, DIM, 9)
        self.copy_lbl.pack(side="left", padx=(14, 0))
        step("3", "Вставь Client ID и войди")
        r3 = tk.Frame(self.setup, bg=BG)
        r3.pack(anchor="w", pady=(6, 0), padx=(26, 0))
        Field(r3, self.cid_var, 11, F.mono, width=34).pack(side="left")
        link(r3, "войти через браузер  →", self._login, ACCENT, TEXT).pack(side="left", padx=(22, 0))

        # devices, shown when logged in
        self.devs = tk.Frame(self, bg=BG)
        caption(self.devs, "Где играть").pack(anchor="w", pady=(0, 10))
        self.dev_list = tk.Frame(self.devs, bg=BG)
        self.dev_list.pack(fill="x")

        self.msg = txt(self, "", 9, DIM, wraplength=560, justify="left")
        self.msg.pack(side="bottom", anchor="w")
        self._poll_status()

    def _poll_status(self):
        st = spotify.status()
        if st["logged_in"] != self.logged:
            self.logged = st["logged_in"]
            if self.logged:
                self.setup.pack_forget()
                self.devs.pack(fill="both", expand=True)
                self.title_lbl.config(text=f"Spotify  ·  {st['user'] or 'подключён'}")
                self.logout.pack(side="left", padx=(18, 0), pady=(10, 0))
                threading.Thread(target=self.app.fetch_devices, daemon=True).start()
            else:
                self.devs.pack_forget()
                self.setup.pack(fill="both", expand=True)
                self.title_lbl.config(text="Spotify")
                self.logout.pack_forget()
            self.app.refresh_tray()
        self.redirect.config(text=spotify.redirect_uri())
        self.after(1500, self._poll_status)

    def render_devices(self):
        for w in self.dev_list.winfo_children():
            w.destroy()
        for d in self.app.sp_devices:
            r = tk.Frame(self.dev_list, bg=BG, cursor="hand2")
            r.pack(fill="x")
            ic = txt(r, glyph(DEVICE_GLYPH.get(d["type"], "")), 13, ACCENT if d["active"] else DIM, F.icon)
            ic.pack(side="left", padx=(10, 18), pady=12)
            name = txt(r, d["name"], 12, TEXT)
            name.pack(side="left")
            sub = "играет" if d["active"] else DEVICE_RU.get(d["type"], d["type"])
            if d.get("volume") is not None:
                sub += f"  ·  {d['volume']}%"
            info = txt(r, sub, 9, ACCENT if d["active"] else FAINT)
            info.pack(side="left", padx=(14, 0))
            tk.Frame(self.dev_list, bg=LINE, height=1).pack(fill="x")
            if not d["active"]:
                act = txt(r, "играть здесь", 9, BG)
                act.pack(side="right", padx=10)
                parts = (r, ic, name, info, act)

                def enter(e, parts=parts, act=act):
                    for p in parts:
                        p.config(bg=HOVER)
                    act.config(fg=DIM)

                def leave(e, parts=parts, act=act):
                    for p in parts:
                        p.config(bg=BG)
                    act.config(fg=BG)

                for p in parts:
                    p.bind("<Enter>", enter)
                    p.bind("<Leave>", leave)
                    p.bind("<Button-1>", lambda e, i=d["id"]: self._transfer(i))
        if not self.app.sp_devices and self.logged:
            txt(self.dev_list, "Устройств нет. Открой Spotify на телефоне, ноутбуке или колонке.", 10, DIM).pack(
                anchor="w")
        if self.app.sp_error:
            self.msg.config(text=self.app.sp_error, fg=DANGER)

    def _transfer(self, device_id):
        self.msg.config(text="переключаю…", fg=DIM)
        self.app.transfer(device_id)
        self.after(2500, lambda: self.msg.config(text=""))

    def _copy_redirect(self):
        self.clipboard_clear()
        self.clipboard_append(spotify.redirect_uri())
        self.copy_lbl.config(text="скопировано")
        self.after(1600, lambda: self.copy_lbl.config(text="копировать"))

    def _login(self):
        cid = self.cid_var.get().strip()
        if not re.fullmatch(r"[0-9a-fA-F]{32}", cid):
            self.msg.config(text="Client ID — это 32 символа из панели Spotify", fg=DANGER)
            return
        spotify.set_client_id(cid)
        webbrowser.open(spotify.login_url())
        self.msg.config(text="подтверди вход в открывшемся браузере…", fg=DIM)

    def _logout(self):
        spotify.logout()
        self.app.sp_devices = []
        self.render_devices()


# ================================================================ macro editor page

class MacroEditor(tk.Frame):
    def __init__(self, parent, app):
        super().__init__(parent, bg=BG)
        self.app = app
        self.macros = [normalize(m) for m in agent.config().get("macros", [])]
        self.saved = self._snapshot()
        self.cur = None
        self.loading = False
        self.step_rows = []
        self.recording = None
        self.win_down = False
        self.autosave_job = None
        self._build()
        self._fill_list()
        if self.macros:
            self._select(0)

    # ---------------------------------------------------------- layout

    def _build(self):
        left = tk.Frame(self, bg=BG, width=210)
        left.pack(side="left", fill="y")
        left.pack_propagate(False)
        caption(left, "Кнопки на телефоне").pack(anchor="w", pady=(6, 12))
        self.list_area = Scroll(left)
        self.list_area.pack(fill="both", expand=True)
        self.add_lbl = link(left, "+  добавить", self._templates_menu, ACCENT, TEXT)
        self.add_lbl.pack(anchor="w", pady=(12, 0))

        tk.Frame(self, bg=LINE, width=1).pack(side="left", fill="y", padx=(0, 36))

        self.form = tk.Frame(self, bg=BG)
        self.form.pack(side="left", fill="both", expand=True)
        tools = tk.Frame(self.form, bg=BG)
        tools.pack(fill="x")
        for text, cmd in (("удалить", self._remove), ("копия", self._duplicate), ("↓", lambda: self._move(1)),
                          ("↑", lambda: self._move(-1))):
            link(tools, text, cmd, FAINT, TEXT, 9).pack(side="right", padx=(14, 0))

        self.label_var = tk.StringVar()
        self.id_var = tk.StringVar()
        self.label_var.trace_add("write", lambda *_: self._changed())
        self.id_var.trace_add("write", lambda *_: self._changed())

        Field(self.form, self.label_var, 22, F.display).pack(fill="x", pady=(4, 18))

        meta = tk.Frame(self.form, bg=BG)
        meta.pack(fill="x")
        caption(meta, "Иконка").grid(row=0, column=0, sticky="w")
        caption(meta, "ID").grid(row=0, column=1, sticky="w", padx=(40, 0))
        icon_row = tk.Frame(meta, bg=BG, cursor="hand2")
        icon_row.grid(row=1, column=0, sticky="w", pady=(6, 0))
        self.icon_glyph = link(icon_row, "", self._pick_icon, ACCENT, TEXT, 12, F.icon)
        self.icon_glyph.pack(side="left")
        self.icon_lbl = link(icon_row, "", self._pick_icon, TEXT, ACCENT, 10)
        self.icon_lbl.pack(side="left", padx=(10, 0))
        Field(meta, self.id_var, 10, F.mono, width=18).grid(row=1, column=1, sticky="w", padx=(40, 0), pady=(6, 0))
        self.confirm = Toggle(self.form, "срабатывает по второму нажатию", False, lambda v: self._changed())
        self.confirm.pack(anchor="w", pady=(20, 0))

        caption(self.form, "Действия по порядку").pack(anchor="w", pady=(30, 10))
        self.steps_frame = tk.Frame(self.form, bg=BG)
        self.steps_frame.pack(fill="x")
        link(self.form, "+  шаг", self._add_step, FAINT, TEXT, 9).pack(anchor="w", pady=(10, 0))

        foot = tk.Frame(self.form, bg=BG)
        foot.pack(side="bottom", fill="x")
        self.status = txt(foot, "", 9, DIM)
        self.status.pack(side="left")
        link(foot, "▶  проверить", self._test, ACCENT, TEXT).pack(side="right")

    # ---------------------------------------------------------- list

    def _fill_list(self):
        for w in self.list_area.inner.winfo_children():
            w.destroy()
        self.rows = []
        for i, m in enumerate(self.macros):
            r = tk.Frame(self.list_area.inner, bg=BG, cursor="hand2")
            r.pack(fill="x")
            ic = txt(r, glyph(ICON_GLYPH.get(m["icon"], ICON_GLYPH["bolt"])), 10, FAINT, F.icon)
            ic.pack(side="left", padx=(0, 14), pady=7)
            lb = txt(r, m["label"] or m["id"] or "без названия", 10, DIM)
            lb.pack(side="left")
            for w in (r, ic, lb):
                w.bind("<Button-1>", lambda e, i=i: self._on_pick(i))
                w.bind("<Enter>", lambda e, i=i: self._hover(i, True))
                w.bind("<Leave>", lambda e, i=i: self._hover(i, False))
            self.rows.append((ic, lb))
        self._paint_list()

    def _paint_list(self):
        for i, (ic, lb) in enumerate(self.rows):
            on = i == self.cur
            m = self.macros[i]
            ic.config(text=glyph(ICON_GLYPH.get(m["icon"], ICON_GLYPH["bolt"])), fg=ACCENT if on else FAINT)
            lb.config(text=m["label"] or m["id"] or "без названия", fg=TEXT if on else DIM)

    def _hover(self, i, inside):
        if i != self.cur and i < len(self.rows):
            self.rows[i][1].config(fg=TEXT if inside else DIM)

    def _on_pick(self, i):
        if i != self.cur:
            self.commit()
            self._select(i)

    def _select(self, i):
        if not self.macros:
            self.cur = None
            self._load_form()
            return
        self.cur = max(0, min(i, len(self.macros) - 1))
        self._paint_list()
        self._load_form()

    def _load_form(self):
        self.loading = True
        m = self.macros[self.cur] if self.cur is not None else None
        self.label_var.set(m["label"] if m else "")
        self.id_var.set(m["id"] if m else "")
        self._paint_icon(m["icon"] if m else "bolt")
        self.confirm.set(m["confirm"] if m else False)
        self.loading = False
        self._render_steps()

    def _paint_icon(self, name):
        self.icon_glyph.config(text=glyph(ICON_GLYPH.get(name, ICON_GLYPH["bolt"])))
        self.icon_lbl.config(text=ICON_NAMES.get(name, name) + "  ▾")
        self.icon_name = name

    def _pick_icon(self):
        if self.cur is None:
            return
        m = tk.Menu(self, tearoff=0, bg=HOVER, fg=TEXT, activebackground=LINE, activeforeground=TEXT, bd=0,
                    font=(F.text, 10))
        for name in ICON_GLYPH:
            m.add_command(label=ICON_NAMES.get(name, name), command=lambda n=name: self._set_icon(n))
        m.tk_popup(self.icon_lbl.winfo_rootx(), self.icon_lbl.winfo_rooty() + self.icon_lbl.winfo_height() + 2)

    def _set_icon(self, name):
        self._paint_icon(name)
        self._changed()

    def _templates_menu(self):
        popup(self.add_lbl, [(t, t[0]) for t in TEMPLATES], self._add)

    def _add(self, tpl):
        self.commit()
        _, lbl, icon, confirm, steps = tpl
        ids = {m["id"] for m in self.macros}
        n = 1
        while f"macro_{n}" in ids:
            n += 1
        self.macros.append({"id": f"macro_{n}", "label": lbl, "icon": icon, "confirm": confirm,
                            "steps": [{"kind": k, "value": v, "repeat": 1} for k, v in steps]})
        self._fill_list()
        self._select(len(self.macros) - 1)
        self._schedule_save()

    def _duplicate(self):
        if self.cur is None:
            return
        self.commit()
        m = copy.deepcopy(self.macros[self.cur])
        ids = {x["id"] for x in self.macros}
        n = 2
        while f"{m['id']}_{n}" in ids:
            n += 1
        m["id"] = f"{m['id']}_{n}"
        m["label"] = f"{m['label']} {n}"
        self.macros.insert(self.cur + 1, m)
        self._fill_list()
        self._select(self.cur + 1)
        self._schedule_save()

    def _remove(self):
        if self.cur is None:
            return
        if not messagebox.askyesno("Удалить", f"Удалить «{self.macros[self.cur]['label']}»?", parent=self):
            return
        self.macros.pop(self.cur)
        i = self.cur
        self.cur = None
        self._fill_list()
        self._select(i)
        self._schedule_save()

    def _move(self, d):
        if self.cur is None:
            return
        self.commit()
        j = self.cur + d
        if 0 <= j < len(self.macros):
            self.macros[self.cur], self.macros[j] = self.macros[j], self.macros[self.cur]
            self._fill_list()
            self._select(j)
            self._schedule_save()

    # ---------------------------------------------------------- steps

    def _render_steps(self):
        for w in self.steps_frame.winfo_children():
            w.destroy()
        self.step_rows = []
        if self.cur is None:
            return
        for i, st in enumerate(self.macros[self.cur]["steps"]):
            k = st["kind"]
            row = tk.Frame(self.steps_frame, bg=BG)
            row.pack(fill="x", pady=5)
            txt(row, str(i + 1), 9, FAINT).pack(side="left", padx=(0, 16))
            kind_lbl = link(row, KIND_LABEL[k] + "  ▾", lambda: None, DIM, TEXT, 10)
            kind_lbl.config(width=17, anchor="w")
            kind_lbl.bind("<Button-1>", lambda e, i=i, w=kind_lbl: popup(w, KINDS, lambda v, i=i: self._set_kind(i, v)))
            kind_lbl.pack(side="left")
            var = tk.StringVar(value=st["value"])
            rep = tk.StringVar(value=str(st.get("repeat", 1)))
            # right-hand controls first, so the expanding value field can't squeeze them out
            tk.Frame(row, bg=BG, width=12).pack(side="right")
            link(row, "×", lambda i=i: self._remove_step(i), FAINT, DANGER, 12).pack(side="right", padx=(16, 0))
            if k == "keys":
                Field(row, rep, 10, F.mono, width=2, on_change=self._changed).pack(side="right")
                txt(row, "×", 9, FAINT).pack(side="right", padx=(16, 4))
                link(row, "записать", lambda v=var: self._record(v), FAINT, ACCENT, 9).pack(side="right", padx=(16, 0))
            elif k in ("run", "file"):
                link(row, "обзор", lambda v=var, x=k: self._browse(v, x), FAINT, ACCENT, 9).pack(side="right",
                                                                                                padx=(16, 0))
            elif k == "site":
                link(row, "открыть", lambda v=var: self._open_now(v), FAINT, ACCENT, 9).pack(side="right",
                                                                                            padx=(16, 0))
            if k == "settings":
                val = link(row, SETTINGS_LABEL.get(st["value"], st["value"]) + "  ▾", lambda: None, TEXT, ACCENT)
                val.bind("<Button-1>", lambda e, i=i, w=val: popup(
                    w, [(u, n) for n, u in WIN_SETTINGS], lambda v, i=i: self._set_value(i, v)))
                val.pack(side="left", padx=(8, 0))
            elif k == "system":
                val = link(row, SYSTEM_LABEL.get(st["value"], "заблокировать") + "  ▾", lambda: None, TEXT, ACCENT)
                val.bind("<Button-1>", lambda e, i=i, w=val: popup(w, SYSTEM, lambda v, i=i: self._set_value(i, v)))
                val.pack(side="left", padx=(8, 0))
            else:
                font = F.mono if k in ("keys", "run", "file") else None
                Field(row, var, 10, font, on_change=self._changed).pack(side="left", fill="x", expand=True, padx=(8, 0))
            self.step_rows.append({"value": var, "repeat": rep})

    def _set_kind(self, i, kind):
        self.commit()
        st = self.macros[self.cur]["steps"][i]
        if st["kind"] != kind:
            defaults = {"site": "https://", "settings": "ms-settings:sound", "system": "lock", "wait": "500"}
            st.update(kind=kind, value=defaults.get(kind, ""), repeat=1)
        self._render_steps()
        self._changed()

    def _set_value(self, i, value):
        self.commit()
        self.macros[self.cur]["steps"][i]["value"] = value
        self._render_steps()
        self._changed()

    def _browse(self, var, kind):
        if kind == "run":
            types = [("Программы", "*.exe *.bat *.lnk"), ("Все файлы", "*.*")] if IS_WIN else [("Все файлы", "*")]
            p = filedialog.askopenfilename(parent=self, filetypes=types)
            if p:
                var.set(f'"{os.path.normpath(p)}"')
        else:
            p = filedialog.askopenfilename(parent=self) or filedialog.askdirectory(parent=self)
            if p:
                var.set(os.path.normpath(p))

    def _open_now(self, var):
        v = to_cfg_step({"kind": "site", "value": var.get()})["open"]
        if v and v.lower() not in ("https://", "http://"):
            webbrowser.open(v)

    def _add_step(self):
        if self.cur is None:
            return
        self.commit()
        self.macros[self.cur]["steps"].append({"kind": "keys", "value": "", "repeat": 1})
        self._render_steps()
        self._changed()

    def _remove_step(self, i):
        self.commit()
        steps = self.macros[self.cur]["steps"]
        steps.pop(i)
        if not steps:
            steps.append({"kind": "keys", "value": "", "repeat": 1})
        self._render_steps()
        self._changed()

    def _record(self, var):
        self.recording = var
        self.win_down = False
        self._say("нажми сочетание клавиш…  (esc — отмена)")
        self.bind_all("<KeyPress>", self._on_key)
        self.bind_all("<KeyRelease>", self._on_key_up)

    def _on_key_up(self, e):
        if e.keysym in ("Win_L", "Win_R"):
            self.win_down = False

    def _on_key(self, e):
        if e.keysym in ("Win_L", "Win_R"):
            self.win_down = True
            return "break"
        if e.keysym in MOD_KEYSYMS:
            return "break"
        mods = []
        if e.state & 0x4:
            mods.append("ctrl")
        if e.state & 0x20000:
            mods.append("alt")
        if e.state & 0x1:
            mods.append("shift")
        if self.win_down:
            mods.append("win")
        ks = e.keysym
        if ks == "Escape" and not mods:
            self._stop_record("запись отменена")
            return "break"
        if ks in KEYSYM_NAMES:
            key = KEYSYM_NAMES[ks]
        elif len(ks) == 1 and ks.isalnum():
            key = ks.lower()
        elif ks.startswith("F") and ks[1:].isdigit():
            key = ks.lower()
        else:
            return "break"
        combo = "+".join(mods + [key])
        self.recording.set(combo)
        self._stop_record(f"записано: {combo}")
        return "break"

    def _stop_record(self, msg):
        self.unbind_all("<KeyPress>")
        self.unbind_all("<KeyRelease>")
        self.bind_all("<Control-s>", lambda e: self.save())
        self.recording = None
        self._say(msg)
        self._changed()

    # ---------------------------------------------------------- model & autosave

    def commit(self):
        if self.loading or self.cur is None:
            return
        m = self.macros[self.cur]
        m["label"] = self.label_var.get().strip()
        m["id"] = self.id_var.get().strip()
        m["icon"] = getattr(self, "icon_name", m["icon"])
        m["confirm"] = self.confirm.value
        for st, r in zip(m["steps"], self.step_rows):
            if st["kind"] not in ("settings", "system"):
                st["value"] = r["value"].get().strip()
            try:
                st["repeat"] = max(1, min(50, int(r["repeat"].get())))
            except ValueError:
                st["repeat"] = 1

    def _changed(self):
        if self.loading:
            return
        self.commit()
        self._paint_list()
        self._schedule_save()

    def _schedule_save(self):
        if self.autosave_job:
            self.after_cancel(self.autosave_job)
        self.autosave_job = self.after(700, lambda: self.save(auto=True))

    def _snapshot(self):
        return json.dumps([denormalize(m) for m in self.macros], ensure_ascii=False, sort_keys=True)

    def save(self, quiet=False, auto=False):
        """Validates and writes config.json. Autosave reports problems quietly, explicit saves in red."""
        self.autosave_job = None
        self.commit()
        ids = [m["id"] for m in self.macros]
        dup = next((i for i in ids if ids.count(i) > 1), None)
        err = next((e for e in map(validate, self.macros) if e), None) or (dup and f"ID «{dup}» повторяется")
        if err:
            if auto:
                self._say("не сохранено: " + err)
            elif not quiet:
                self._say(err, error=True)
            return False
        snap = self._snapshot()
        if snap != self.saved:
            macros = [denormalize(m) for m in self.macros]
            edit_config(lambda c: c.__setitem__("macros", macros))
            self.saved = snap
            self.app.refresh_tray()
            if not quiet:
                self._say("сохранено · телефон обновит кнопки в течение минуты")
        return True

    def confirm_discard(self):
        """True when it is fine to exit (saved, or the user chose what to do)."""
        if self.save(quiet=True) or self._snapshot() == self.saved:
            return True
        self.app.show()
        return messagebox.askyesno("Выйти?", "Последние изменения в макросах не сохранены: в них есть ошибка. "
                                             "Выйти без сохранения?", parent=self)

    def _test(self):
        if self.cur is None:
            return
        self.commit()
        m = self.macros[self.cur]
        err = validate(m)
        if err:
            self._say(err, error=True)
            return
        if any(s["kind"] == "system" and s["value"] in DANGEROUS for s in m["steps"]):
            if not messagebox.askyesno("Проверка", "Этот макрос усыпит или выключит компьютер. Выполнить?", parent=self):
                return
        macro = denormalize(m)
        self._say(f"выполняю «{m['label']}»…")

        def run():
            try:
                agent.run_macro(macro)
                self.after(0, self._say, f"«{m['label']}» выполнен")
            except Exception as e:  # noqa: BLE001 — show any failure to the user
                self.after(0, self._say, f"ошибка: {e}", True)

        threading.Timer(0.6, run).start()

    def _say(self, msg, error=False):
        self.status.config(text=msg, fg=DANGER if error else DIM)


# ================================================================ entry point

_instance_lock = None


def already_running():
    """Single instance: a named mutex on Windows, a lock file on Linux."""
    global _instance_lock
    if IS_WIN:
        _instance_lock = ctypes.windll.kernel32.CreateMutexW(None, False, "DeskDash.PC.Singleton")
        return ctypes.windll.kernel32.GetLastError() == 183  # ERROR_ALREADY_EXISTS
    import fcntl
    _instance_lock = open(os.path.join(agent.DATA_DIR, "instance.lock"), "w")
    try:
        fcntl.flock(_instance_lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        return False
    except OSError:
        return True


def main():
    if IS_WIN:
        try:
            ctypes.windll.shcore.SetProcessDpiAwareness(1)
        except Exception:  # noqa: BLE001 — older Windows
            pass
        ctypes.windll.shell32.SetCurrentProcessExplicitAppUserModelID("DeskDash.PC")
    if already_running():  # wake the running window instead
        try:
            urllib.request.urlopen(f"http://127.0.0.1:{agent.config()['port']}/api/show", timeout=2)
        except Exception:  # noqa: BLE001
            pass
        return
    server, error = None, None
    try:
        server = agent.start()
    except OSError as e:
        busy = getattr(e, "winerror", None) == 10048 or getattr(e, "errno", None) in (98, 48)
        error = "порт занят" if busy else str(e)
    App(server, error, "--minimized" in sys.argv).mainloop()
    if not IS_WIN:
        os._exit(0)  # pystray's X11 thread is not a daemon and would keep the process alive


if __name__ == "__main__":
    main()
