"""DeskDash for Windows: the phone agent + a window to connect the phone and edit macros.

Built into DeskDash.exe with build_exe.py. Runs the agent in the background and lives in the tray.
"""
import copy
import ctypes
import json
import os
import queue
import re
import secrets
import subprocess
import sys
import threading
import time
import tkinter as tk
import urllib.request
import winreg
from tkinter import filedialog, messagebox, ttk

import pystray
from PIL import Image, ImageDraw

import deskdash_agent as agent
import spotify

BG, SURFACE, FIELD = "#17120F", "#231B17", "#2E2420"
TEXT, DIM, ACCENT, DANGER = "#F1E6DA", "#9C8B7E", "#C8A27C", "#E5534B"
FONT = "Segoe UI"
APP_ID = "DeskDash"
RUN_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"

ICON_GLYPH = {
    "bolt": "⚡", "web": "🌐", "settings": "⚙", "open": "↗", "lock": "🔒", "mute": "🔇",
    "vol_up": "🔊", "vol_down": "🔉", "play": "▶", "pause": "⏸", "next": "⏭", "prev": "⏮",
    "desktop": "🖥", "sleep": "🌙", "screenshot": "✂", "power": "⭘", "music": "♪", "eye_off": "◌",
}
KINDS = [("keys", "Клавиши"), ("site", "Сайт"), ("settings", "Настройки Windows"),
         ("run", "Программа"), ("file", "Файл / папка"), ("system", "Система"), ("wait", "Пауза, мс")]
KIND_LABEL = dict(KINDS)
LABEL_KIND = {v: k for k, v in KINDS}
SYSTEM = [("lock", "Заблокировать"), ("monitor_off", "Погасить экран"), ("sleep", "Сон"),
          ("hibernate", "Гибернация"), ("shutdown", "Выключить"), ("restart", "Перезагрузить")]
SYSTEM_LABEL = dict(SYSTEM)
LABEL_SYSTEM = {v: k for k, v in SYSTEM}
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
LABEL_SETTINGS = dict(WIN_SETTINGS)
TEMPLATES = [
    ("Пустой макрос", "Новый", "bolt", False, [("keys", "")]),
    ("Открыть сайт", "Сайт", "web", False, [("site", "https://")]),
    ("Настройки Windows", "Звук", "settings", False, [("settings", "ms-settings:sound")]),
    ("Запустить программу", "Программа", "open", False, [("run", "")]),
    ("Открыть файл или папку", "Папка", "open", False, [("file", "")]),
    ("Сочетание клавиш", "Клавиши", "bolt", False, [("keys", "")]),
    ("Выключить компьютер", "Выкл", "power", True, [("system", "shutdown")]),
]
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
    pyw = os.path.join(os.path.dirname(sys.executable), "pythonw.exe")
    return f'"{pyw}" "{os.path.abspath(__file__)}" --minimized'


def get_autostart():
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY) as k:
            winreg.QueryValueEx(k, APP_ID)
            return True
    except OSError:
        return False


def set_autostart(on):
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


def label(parent, text="", style="TLabel", **kw):
    return ttk.Label(parent, text=text, style=style, **kw)


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


# ================================================================ app

class App(tk.Tk):
    def __init__(self, server, error, minimized):
        super().__init__()
        self.server = server
        self.events = queue.Queue()
        self.title("DeskDash")
        self.geometry("980x640")
        self.minsize(860, 560)
        self.configure(bg=BG)
        ico = os.path.join(agent.DATA_DIR, "icon.ico")
        try:
            make_icon(256).save(ico, sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (256, 256)])
            self.iconbitmap(ico)
        except Exception:  # noqa: BLE001 — cosmetic
            pass
        self._style()

        top = ttk.Frame(self, padding=(22, 16, 22, 0))
        top.pack(fill="x")
        label(top, "DeskDash", "Title.TLabel").pack(side="left")
        self.state_lbl = label(top, "", "Dim.TLabel")
        self.state_lbl.pack(side="left", padx=(16, 0), pady=(6, 0))
        if error:
            self.state_lbl.config(text=f"●  агент не запущен: {error}", foreground=DANGER)
        else:
            self.state_lbl.config(text=f"●  агент работает · порт {agent.config()['port']}", foreground=ACCENT)

        self.nb = nb = ttk.Notebook(self)
        nb.pack(fill="both", expand=True, padx=16, pady=(10, 0))
        self.sp_devices = []
        self.sp_error = ""
        self.conn = ConnectionTab(nb, self)
        self.editor = MacroEditor(nb, self)
        self.spotify_tab = SpotifyTab(nb, self)
        self.settings = SettingsTab(nb, self)
        nb.add(self.conn, text="  Подключение  ")
        nb.add(self.editor, text="  Макросы  ")
        nb.add(self.spotify_tab, text="  Spotify  ")
        nb.add(self.settings, text="  Настройки  ")

        self.protocol("WM_DELETE_WINDOW", self.on_close)
        agent.ON_SHOW = lambda: self.events.put("show")
        self.tray = pystray.Icon(APP_ID, make_icon(64), "DeskDash", menu=pystray.Menu(self._tray_items))
        self.tray.run_detached()
        self.tray_hint_shown = False
        self.visible = not minimized
        threading.Thread(target=self._device_loop, daemon=True).start()
        if minimized:
            self.withdraw()
        self._pump()

    def _style(self):
        s = ttk.Style(self)
        s.theme_use("clam")
        s.configure(".", background=BG, foreground=TEXT, font=(FONT, 10), bordercolor=FIELD,
                    lightcolor=FIELD, darkcolor=FIELD, troughcolor=SURFACE, focuscolor=ACCENT)
        s.configure("TFrame", background=BG)
        s.configure("Card.TFrame", background=SURFACE)
        s.configure("TLabel", background=BG, foreground=TEXT)
        s.configure("Dim.TLabel", background=BG, foreground=DIM)
        s.configure("CardDim.TLabel", background=SURFACE, foreground=DIM)
        s.configure("Card.TLabel", background=SURFACE, foreground=TEXT)
        s.configure("Big.TLabel", background=SURFACE, foreground=TEXT, font=("Consolas", 26))
        s.configure("Title.TLabel", background=BG, foreground=TEXT, font=(FONT, 20))
        s.configure("Section.TLabel", background=BG, foreground=ACCENT, font=(FONT, 9, "bold"))
        s.configure("CardSection.TLabel", background=SURFACE, foreground=ACCENT, font=(FONT, 9, "bold"))
        s.configure("TNotebook", background=BG, borderwidth=0, bordercolor=BG, lightcolor=BG,
                    darkcolor=BG, tabmargins=(6, 4, 6, 0))
        s.configure("TNotebook.Tab", background=BG, foreground=DIM, padding=(14, 8), borderwidth=0,
                    bordercolor=BG, lightcolor=BG, darkcolor=BG, font=(FONT, 11))
        s.map("TNotebook.Tab", background=[("selected", FIELD), ("active", SURFACE)],
              foreground=[("selected", TEXT)], lightcolor=[("selected", FIELD)],
              bordercolor=[("selected", FIELD)], expand=[("selected", (0, 0, 0, 0))])
        s.configure("TEntry", fieldbackground=FIELD, foreground=TEXT, insertcolor=TEXT, padding=6)
        s.configure("TSpinbox", fieldbackground=FIELD, foreground=TEXT, arrowcolor=DIM, padding=4)
        s.configure("TCombobox", fieldbackground=FIELD, background=FIELD, foreground=TEXT,
                    arrowcolor=DIM, padding=5, insertcolor=TEXT)
        s.map("TCombobox", fieldbackground=[("readonly", FIELD)], foreground=[("readonly", TEXT)],
              selectbackground=[("readonly", FIELD)], selectforeground=[("readonly", TEXT)])
        s.configure("TButton", background=FIELD, foreground=TEXT, padding=(12, 6), borderwidth=0)
        s.map("TButton", background=[("active", "#3A2E28"), ("pressed", "#3A2E28")])
        s.configure("Accent.TButton", background=ACCENT, foreground=BG, font=(FONT, 10, "bold"))
        s.map("Accent.TButton", background=[("active", "#D8B690"), ("pressed", "#B08A66")])
        s.configure("Ghost.TButton", background=BG, foreground=DIM, padding=(6, 4))
        s.map("Ghost.TButton", background=[("active", SURFACE)], foreground=[("active", DANGER)])
        s.configure("TCheckbutton", background=BG, foreground=TEXT, indicatorbackground=FIELD,
                    indicatorforeground=ACCENT)
        s.map("TCheckbutton", background=[("active", BG)], indicatorbackground=[("selected", FIELD)])
        self.option_add("*TCombobox*Listbox.background", SURFACE)
        self.option_add("*TCombobox*Listbox.foreground", TEXT)
        self.option_add("*TCombobox*Listbox.selectBackground", ACCENT)
        self.option_add("*TCombobox*Listbox.selectForeground", BG)
        self.option_add("*TCombobox*Listbox.font", (FONT, 10))
        self.option_add("*Menu.background", SURFACE)
        self.option_add("*Menu.foreground", TEXT)
        self.option_add("*Menu.activeBackground", ACCENT)
        self.option_add("*Menu.activeForeground", BG)
        self.option_add("*Menu.font", (FONT, 10))

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
            self.tray.update_menu()
        except Exception:  # noqa: BLE001 — tray not ready yet
            pass

    # ---------------------------------------------------------- spotify devices

    def _device_loop(self):
        """Keeps the Spotify device list fresh for the tab and the tray menu."""
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

    def on_close(self):
        if agent.config().get("tray_on_close", True):
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
            self.tray.stop()
        except Exception:  # noqa: BLE001
            pass
        if self.server:
            threading.Thread(target=self.server.shutdown, daemon=True).start()
        self.destroy()


# ================================================================ connection tab

class ConnectionTab(ttk.Frame):
    def __init__(self, parent, app):
        super().__init__(parent, padding=(8, 16, 8, 8))
        self.app = app
        self.columnconfigure(0, weight=1)
        self.columnconfigure(1, weight=1)

        ipc = ttk.Frame(self, style="Card.TFrame", padding=18)
        ipc.grid(row=0, column=0, sticky="nsew", padx=(0, 8))
        label(ipc, "IP-АДРЕС НОУТБУКА", "CardSection.TLabel").pack(anchor="w")
        self.ip_lbl = label(ipc, "", "Big.TLabel")
        self.ip_lbl.pack(anchor="w", pady=(6, 0))
        self.ip_other = label(ipc, "", "CardDim.TLabel")
        self.ip_other.pack(anchor="w", pady=(2, 8))
        ttk.Button(ipc, text="Копировать", command=lambda: self._copy(self.ip_lbl.cget("text"))).pack(anchor="w")

        tkc = ttk.Frame(self, style="Card.TFrame", padding=18)
        tkc.grid(row=0, column=1, sticky="nsew", padx=(8, 0))
        label(tkc, "ТОКЕН", "CardSection.TLabel").pack(anchor="w")
        self.token_lbl = label(tkc, "", "Big.TLabel")
        self.token_lbl.pack(anchor="w", pady=(6, 0))
        self.port_lbl = label(tkc, "", "CardDim.TLabel")
        self.port_lbl.pack(anchor="w", pady=(2, 8))
        row = ttk.Frame(tkc, style="Card.TFrame")
        row.pack(anchor="w")
        ttk.Button(row, text="Копировать", command=lambda: self._copy(self.token_lbl.cget("text"))).pack(side="left")
        ttk.Button(row, text="Новый токен", command=self._new_token).pack(side="left", padx=8)

        how = ttk.Frame(self, padding=(4, 18, 4, 0))
        how.grid(row=1, column=0, columnspan=2, sticky="ew")
        label(how, "КАК ПОДКЛЮЧИТЬ ТЕЛЕФОН", "Section.TLabel").pack(anchor="w")
        label(how, "1.  Телефон и ноутбук в одной Wi-Fi сети.\n"
                   "2.  DeskDash на телефоне → ⋮ → Настройки → «Найти ПК в сети» (или впиши IP вручную).\n"
                   "3.  Введи токен и нажми «Проверить соединение». Кнопки-макросы появятся на экране.",
              justify="left").pack(anchor="w", pady=(6, 0))
        fw = ttk.Frame(how)
        fw.pack(anchor="w", pady=(10, 0))
        ttk.Button(fw, text="Разрешить в брандмауэре", command=self._firewall).pack(side="left")
        ttk.Button(fw, text="Сделать сеть частной", command=lambda: os.startfile("ms-settings:network-status")).pack(side="left", padx=8)
        label(fw, "если телефон не находит ноутбук", "Dim.TLabel").pack(side="left", padx=6)

        bottom = ttk.Frame(self, padding=(4, 18, 4, 0))
        bottom.grid(row=2, column=0, columnspan=2, sticky="nsew")
        self.rowconfigure(2, weight=1)
        bottom.columnconfigure(0, weight=1)
        bottom.columnconfigure(1, weight=2)
        label(bottom, "ТЕЛЕФОНЫ", "Section.TLabel").grid(row=0, column=0, sticky="w")
        label(bottom, "ЖУРНАЛ", "Section.TLabel").grid(row=0, column=1, sticky="w", padx=(16, 0))
        self.clients_lbl = label(bottom, "", justify="left")
        self.clients_lbl.grid(row=1, column=0, sticky="nw", pady=(6, 0))
        self.log_lbl = label(bottom, "", "Dim.TLabel", justify="left", font=("Consolas", 9))
        self.log_lbl.grid(row=1, column=1, sticky="nw", padx=(16, 0), pady=(6, 0))
        self._refresh()

    def _refresh(self):
        cfg = agent.config()
        ips = rank_ips(agent.local_ips())
        main = ips[0] if ips else "?"
        self.ip_lbl.config(text=main)
        others = [i for i in ips if i != main]
        self.ip_other.config(text=("также: " + ", ".join(others)) if others else "")
        self.token_lbl.config(text=cfg["token"])
        self.port_lbl.config(text=f"порт {cfg['port']} · имя «{cfg['name']}»")
        lines = []
        for ip, (t, ok) in sorted(agent.CLIENTS.items(), key=lambda kv: -kv[1][0]):
            fresh = time.time() - t < 15
            state = ("на связи" if fresh else "был " + ago(t)) if ok else "неверный токен"
            lines.append(f"{'●' if fresh and ok else '○'}  {ip} — {state}")
        self.clients_lbl.config(text="\n".join(lines) or "Пока никто не подключался",
                                foreground=TEXT if lines else DIM)
        self.log_lbl.config(text="\n".join(list(agent.LOG)[-9:]))
        self.after(2000, self._refresh)

    def _copy(self, text):
        self.clipboard_clear()
        self.clipboard_append(text)

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


# ================================================================ spotify tab

DEVICE_GLYPH = {"Computer": "💻", "Smartphone": "📱", "Tablet": "📱", "Speaker": "🔊", "TV": "📺",
                "CastVideo": "📺", "CastAudio": "🔊", "AVR": "🔊", "STB": "📺", "GameConsole": "🎮",
                "Automobile": "🚗"}
DEVICE_RU = {"Computer": "Компьютер", "Smartphone": "Телефон", "Tablet": "Планшет", "Speaker": "Колонка",
             "TV": "Телевизор", "CastVideo": "Chromecast", "CastAudio": "Chromecast", "AVR": "Ресивер",
             "GameConsole": "Консоль", "Automobile": "Автомобиль"}


class SpotifyTab(ttk.Frame):
    def __init__(self, parent, app):
        super().__init__(parent, padding=(8, 16, 8, 8))
        self.app = app
        self.logged = None
        self.cid_var = tk.StringVar(value=agent.config().get("spotify", {}).get("client_id", ""))

        head = ttk.Frame(self, style="Card.TFrame", padding=18)
        head.pack(fill="x")
        self.state_lbl = label(head, "", "Card.TLabel", font=(FONT, 14))
        self.state_lbl.pack(side="left")
        self.logout_btn = ttk.Button(head, text="Выйти", command=self._logout)
        label(head, "выбор устройства на телефоне, в трее и здесь", "CardDim.TLabel").pack(side="right", padx=12)

        # setup (shown until logged in)
        self.setup = ttk.Frame(self, padding=(4, 18, 4, 0))
        label(self.setup, "ПОДКЛЮЧЕНИЕ · ОДИН РАЗ", "Section.TLabel").grid(row=0, column=0, columnspan=3, sticky="w")
        label(self.setup, "1.  Создай приложение в панели разработчика Spotify (Create app, бесплатно).").grid(
            row=1, column=0, sticky="w", pady=(10, 0))
        ttk.Button(self.setup, text="Открыть панель Spotify",
                   command=lambda: os.startfile(spotify.DASHBOARD_URL)).grid(row=1, column=1, sticky="w", padx=10, pady=(10, 0))
        label(self.setup, "2.  В «Redirect URIs» вставь адрес ниже, в «APIs used» отметь Web API и сохрани.").grid(
            row=2, column=0, columnspan=3, sticky="w", pady=(14, 4))
        self.redirect_lbl = label(self.setup, "", font=("Consolas", 11))
        self.redirect_lbl.grid(row=3, column=0, sticky="w", padx=(22, 0))
        ttk.Button(self.setup, text="Копировать", command=self._copy_redirect).grid(row=3, column=1, sticky="w", padx=10)
        label(self.setup, "3.  Скопируй оттуда Client ID сюда и войди.").grid(
            row=4, column=0, columnspan=3, sticky="w", pady=(14, 4))
        row = ttk.Frame(self.setup)
        row.grid(row=5, column=0, columnspan=3, sticky="w", padx=(22, 0))
        ttk.Entry(row, textvariable=self.cid_var, width=40, font=("Consolas", 10)).pack(side="left")
        ttk.Button(row, text="Войти через браузер", style="Accent.TButton", command=self._login).pack(side="left", padx=10)
        label(self.setup, "Переключать устройства Spotify разрешает только с Premium.", "Dim.TLabel").grid(
            row=6, column=0, columnspan=3, sticky="w", pady=(14, 0))

        # devices (shown when logged in)
        self.devs = ttk.Frame(self, padding=(4, 18, 4, 0))
        dh = ttk.Frame(self.devs)
        dh.pack(fill="x")
        label(dh, "ГДЕ ИГРАЕТ МУЗЫКА", "Section.TLabel").pack(side="left")
        ttk.Button(dh, text="Обновить", style="Ghost.TButton", command=self._refresh_now).pack(side="right")
        self.dev_list = ttk.Frame(self.devs)
        self.dev_list.pack(fill="x", pady=(8, 0))
        self.err_lbl = label(self.devs, "", "Dim.TLabel", wraplength=800, justify="left")
        self.err_lbl.pack(anchor="w", pady=(10, 0))

        self.msg = label(self, "", "Dim.TLabel")
        self.msg.pack(side="bottom", anchor="w", padx=4)
        self._poll_status()

    def _poll_status(self):
        st = spotify.status()
        if st["logged_in"] != self.logged:
            self.logged = st["logged_in"]
            if self.logged:
                self.setup.pack_forget()
                self.devs.pack(fill="both", expand=True)
                self.logout_btn.pack(side="left", padx=16)
                self.state_lbl.config(text=f"●  Spotify: {st['user'] or 'подключён'}", foreground=ACCENT)
                self._refresh_now()
            else:
                self.devs.pack_forget()
                self.setup.pack(fill="both", expand=True)
                self.logout_btn.pack_forget()
                self.state_lbl.config(text="○  Spotify не подключён", foreground=TEXT)
            self.app.refresh_tray()
        self.redirect_lbl.config(text=spotify.redirect_uri())
        self.after(1500, self._poll_status)

    def render_devices(self):
        for w in self.dev_list.winfo_children():
            w.destroy()
        for d in self.app.sp_devices:
            r = ttk.Frame(self.dev_list, style="Card.TFrame", padding=(14, 10))
            r.pack(fill="x", pady=3)
            label(r, DEVICE_GLYPH.get(d["type"], "🎵"), "Card.TLabel", font=(FONT, 16)).pack(side="left")
            label(r, d["name"], "Card.TLabel", font=(FONT, 12)).pack(side="left", padx=12)
            sub = "играет сейчас" if d["active"] else DEVICE_RU.get(d["type"], d["type"])
            if d.get("volume") is not None:
                sub += f" · громкость {d['volume']}%"
            label(r, sub, "CardDim.TLabel").pack(side="left")
            if d["active"]:
                label(r, "●", "Card.TLabel", foreground=ACCENT).pack(side="right", padx=8)
            else:
                ttk.Button(r, text="Играть здесь", command=lambda i=d["id"]: self._transfer(i)).pack(side="right")
        if not self.app.sp_devices and self.logged:
            label(self.dev_list, "Устройств нет. Открой Spotify на телефоне, ноутбуке или колонке.",
                  "Dim.TLabel").pack(anchor="w")
        self.err_lbl.config(text=self.app.sp_error, foreground=DANGER if self.app.sp_error else DIM)

    def _transfer(self, device_id):
        self.msg.config(text="Переключаю…")
        self.app.transfer(device_id)
        self.after(2500, lambda: self.msg.config(text=""))

    def _refresh_now(self):
        threading.Thread(target=self.app.fetch_devices, daemon=True).start()

    def _copy_redirect(self):
        self.clipboard_clear()
        self.clipboard_append(spotify.redirect_uri())
        self.msg.config(text="Адрес скопирован")

    def _login(self):
        cid = self.cid_var.get().strip()
        if not re.fullmatch(r"[0-9a-fA-F]{32}", cid):
            self.msg.config(text="Client ID — это 32 символа из панели Spotify", foreground=DANGER)
            return
        spotify.set_client_id(cid)
        os.startfile(spotify.login_url())
        self.msg.config(text="Подтверди вход в открывшемся браузере…", foreground=DIM)

    def _logout(self):
        spotify.logout()
        self.app.sp_devices = []
        self.render_devices()


# ================================================================ settings tab

class SettingsTab(ttk.Frame):
    def __init__(self, parent, app):
        super().__init__(parent, padding=(12, 18, 12, 12))
        self.app = app
        cfg = agent.config()
        self.columnconfigure(2, weight=1)
        self.name_var = tk.StringVar(value=cfg.get("name", ""))
        self.port_var = tk.StringVar(value=str(cfg.get("port", 8765)))
        self.mac_var = tk.StringVar(value=cfg.get("mac", ""))
        self.auto_var = tk.BooleanVar(value=get_autostart())
        self.tray_var = tk.BooleanVar(value=cfg.get("tray_on_close", True))

        def field(r, title, var, hint):
            label(self, title, "Section.TLabel").grid(row=r, column=0, sticky="w", pady=(0, 14), padx=(0, 18))
            ttk.Entry(self, textvariable=var, width=36).grid(row=r, column=1, sticky="w", pady=(0, 14))
            label(self, hint, "Dim.TLabel").grid(row=r, column=2, sticky="w", pady=(0, 14), padx=(12, 0))

        field(0, "ИМЯ КОМПЬЮТЕРА", self.name_var, "так он называется на телефоне")
        field(1, "ПОРТ", self.port_var, "после смены перезапусти DeskDash")
        field(2, "MAC ДЛЯ ПРОБУЖДЕНИЯ", self.mac_var, f"пусто = {agent.default_mac()}")
        ttk.Checkbutton(self, text="Запускать вместе с Windows (свёрнутым в трей)",
                        variable=self.auto_var).grid(row=3, column=0, columnspan=3, sticky="w", pady=(4, 8))
        ttk.Checkbutton(self, text="Кнопка «закрыть» сворачивает в трей, агент продолжает работать",
                        variable=self.tray_var).grid(row=4, column=0, columnspan=3, sticky="w", pady=(0, 18))
        btns = ttk.Frame(self)
        btns.grid(row=5, column=0, columnspan=3, sticky="w")
        ttk.Button(btns, text="Сохранить", style="Accent.TButton", command=self.save).pack(side="left")
        ttk.Button(btns, text="Папка с настройками", command=lambda: os.startfile(agent.DATA_DIR)).pack(side="left", padx=8)
        self.status = label(self, "", "Dim.TLabel")
        self.status.grid(row=6, column=0, columnspan=3, sticky="w", pady=(14, 0))

    def save(self):
        try:
            port = int(self.port_var.get())
            assert 1024 <= port <= 65535
        except (ValueError, AssertionError):
            self.status.config(text="Порт: число от 1024 до 65535", foreground=DANGER)
            return
        old_port = agent.config()["port"]

        def upd(c):
            c["name"] = self.name_var.get().strip() or c["name"]
            c["port"] = port
            c["mac"] = self.mac_var.get().strip()
            c["tray_on_close"] = bool(self.tray_var.get())

        edit_config(upd)
        try:
            set_autostart(self.auto_var.get())
        except OSError as e:
            self.status.config(text=f"Автозапуск: {e}", foreground=DANGER)
            return
        msg = "Сохранено" + (". Порт изменится после перезапуска DeskDash" if port != old_port else "")
        self.status.config(text=msg, foreground=DIM)


# ================================================================ macro editor tab

class MacroEditor(ttk.Frame):
    def __init__(self, parent, app):
        super().__init__(parent, padding=(8, 14, 8, 8))
        self.app = app
        self.macros = [normalize(m) for m in agent.config().get("macros", [])]
        self.saved = self._snapshot()
        self.cur = None
        self.loading = False
        self.step_rows = []
        self.recording = None
        self.win_down = False
        self._build()
        self._fill_list()
        if self.macros:
            self._select(0)

    def _build(self):
        body = ttk.Frame(self)
        body.pack(fill="both", expand=True)
        left = ttk.Frame(body)
        left.pack(side="left", fill="y")
        self.listbox = tk.Listbox(left, width=26, bg=SURFACE, fg=TEXT, bd=0, highlightthickness=0,
                                  selectbackground=ACCENT, selectforeground=BG, activestyle="none",
                                  font=(FONT, 11), exportselection=False)
        self.listbox.pack(fill="y", expand=True)
        self.listbox.bind("<<ListboxSelect>>", self._on_list_select)
        btns = ttk.Frame(left)
        btns.pack(fill="x", pady=(8, 0))
        self.add_btn = ttk.Button(btns, text="+ Добавить ▾", command=self._templates_menu)
        self.add_btn.pack(side="left")
        ttk.Button(btns, text="↑", width=3, command=lambda: self._move(-1)).pack(side="left", padx=(6, 0))
        ttk.Button(btns, text="↓", width=3, command=lambda: self._move(1)).pack(side="left", padx=(4, 0))
        ttk.Button(btns, text="Удалить", style="Ghost.TButton", command=self._remove).pack(side="right")
        ttk.Button(btns, text="Копия", style="Ghost.TButton", command=self._duplicate).pack(side="right")

        form = ttk.Frame(body, padding=(24, 0, 0, 0))
        form.pack(side="left", fill="both", expand=True)
        form.columnconfigure(1, weight=1)
        self.label_var = tk.StringVar()
        self.id_var = tk.StringVar()
        self.icon_var = tk.StringVar()
        self.confirm_var = tk.BooleanVar()
        self.label_var.trace_add("write", lambda *_: self.commit())

        label(form, "ПОДПИСЬ НА ТЕЛЕФОНЕ", "Section.TLabel").grid(row=0, column=0, sticky="w", pady=(0, 4))
        self.label_entry = ttk.Entry(form, textvariable=self.label_var, font=(FONT, 12))
        self.label_entry.grid(row=1, column=0, columnspan=2, sticky="ew")
        label(form, "ИКОНКА", "Section.TLabel").grid(row=2, column=0, sticky="w", pady=(14, 4))
        label(form, "ID", "Section.TLabel").grid(row=2, column=1, sticky="w", pady=(14, 4), padx=(12, 0))
        icons = ttk.Combobox(form, textvariable=self.icon_var, state="readonly", width=16,
                             values=[f"{g}  {n}" for n, g in ICON_GLYPH.items()])
        icons.grid(row=3, column=0, sticky="w")
        icons.bind("<<ComboboxSelected>>", lambda e: self.commit())
        ttk.Entry(form, textvariable=self.id_var).grid(row=3, column=1, sticky="ew", padx=(12, 0))
        ttk.Checkbutton(form, text="Спрашивать подтверждение (срабатывает по второму нажатию)",
                        variable=self.confirm_var, command=self.commit).grid(row=4, column=0, columnspan=2,
                                                                             sticky="w", pady=(12, 0))
        head = ttk.Frame(form)
        head.grid(row=5, column=0, columnspan=2, sticky="ew", pady=(18, 4))
        label(head, "ДЕЙСТВИЯ ПО ПОРЯДКУ", "Section.TLabel").pack(side="left")
        ttk.Button(head, text="+ шаг", style="Ghost.TButton", command=self._add_step).pack(side="right")
        self.steps_frame = ttk.Frame(form)
        self.steps_frame.grid(row=6, column=0, columnspan=2, sticky="new")
        form.rowconfigure(6, weight=1)
        label(form, "Сайт: адрес, например youtube.com · Настройки Windows: выбери раздел из списка\n"
                    "Программа: команда или exe (кнопка «Обзор») · Клавиши: ctrl+shift+m, win+d, f5, "
                    "media_play_pause", "Dim.TLabel", justify="left").grid(row=7, column=0, columnspan=2,
                                                                         sticky="w", pady=(10, 0))

        foot = ttk.Frame(self, padding=(0, 12, 0, 4))
        foot.pack(fill="x")
        self.status = label(foot, "", "Dim.TLabel")
        self.status.pack(side="left")
        ttk.Button(foot, text="Сохранить", style="Accent.TButton", command=self.save).pack(side="right")
        ttk.Button(foot, text="▶  Проверить", command=self._test).pack(side="right", padx=8)
        self.bind_all("<Control-s>", lambda e: self.save())

    # ---------------------------------------------------------- list

    def _item_text(self, m):
        return f"  {ICON_GLYPH.get(m['icon'], '⚡')}   {m['label'] or m['id'] or '(без названия)'}"

    def _fill_list(self):
        self.listbox.delete(0, "end")
        for m in self.macros:
            self.listbox.insert("end", self._item_text(m))

    def _on_list_select(self, _e):
        sel = self.listbox.curselection()
        if sel and sel[0] != self.cur:
            self.commit()
            self._select(sel[0])

    def _select(self, i):
        if not self.macros:
            self.cur = None
            self._load_form()
            return
        i = max(0, min(i, len(self.macros) - 1))
        self.cur = i
        self.listbox.selection_clear(0, "end")
        self.listbox.selection_set(i)
        self.listbox.see(i)
        self._load_form()

    def _load_form(self):
        self.loading = True
        m = self.macros[self.cur] if self.cur is not None else None
        self.label_var.set(m["label"] if m else "")
        self.id_var.set(m["id"] if m else "")
        self.icon_var.set(f"{ICON_GLYPH.get(m['icon'], '⚡')}  {m['icon']}" if m else "")
        self.confirm_var.set(m["confirm"] if m else False)
        self.loading = False
        self._render_steps()

    def _templates_menu(self):
        menu = tk.Menu(self, tearoff=0)
        for t in TEMPLATES:
            menu.add_command(label=t[0], command=lambda t=t: self._add(t))
        x, y = self.add_btn.winfo_rootx(), self.add_btn.winfo_rooty() + self.add_btn.winfo_height()
        menu.tk_popup(x, y)

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
        self.label_entry.focus_set()
        self.label_entry.select_range(0, "end")

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
        self._say("Копия создана, не забудь сохранить")

    def _remove(self):
        if self.cur is None:
            return
        if not messagebox.askyesno("Удалить", f"Удалить макрос «{self.macros[self.cur]['label']}»?", parent=self):
            return
        self.macros.pop(self.cur)
        i = self.cur
        self.cur = None
        self._fill_list()
        self._select(i)

    def _move(self, d):
        if self.cur is None:
            return
        self.commit()
        j = self.cur + d
        if 0 <= j < len(self.macros):
            self.macros[self.cur], self.macros[j] = self.macros[j], self.macros[self.cur]
            self._fill_list()
            self._select(j)

    # ---------------------------------------------------------- steps

    def _render_steps(self):
        for w in self.steps_frame.winfo_children():
            w.destroy()
        self.step_rows = []
        if self.cur is None:
            return
        for i, st in enumerate(self.macros[self.cur]["steps"]):
            k = st["kind"]
            row = ttk.Frame(self.steps_frame)
            row.pack(fill="x", pady=3)
            kv = tk.StringVar(value=KIND_LABEL[k])
            cb = ttk.Combobox(row, textvariable=kv, state="readonly", width=17, values=[l for _, l in KINDS])
            cb.pack(side="left")
            cb.bind("<<ComboboxSelected>>", lambda e: (self.commit(), self._render_steps()))
            if k == "settings":
                vv = tk.StringVar(value=SETTINGS_LABEL.get(st["value"], st["value"]))
                val = ttk.Combobox(row, textvariable=vv, values=[n for n, _ in WIN_SETTINGS])
                val.bind("<<ComboboxSelected>>", lambda e: self.commit())
            elif k == "system":
                vv = tk.StringVar(value=SYSTEM_LABEL.get(st["value"], "Заблокировать"))
                val = ttk.Combobox(row, textvariable=vv, state="readonly", values=[n for _, n in SYSTEM])
                val.bind("<<ComboboxSelected>>", lambda e: self.commit())
            else:
                vv = tk.StringVar(value=st["value"])
                val = ttk.Entry(row, textvariable=vv)
            val.pack(side="left", fill="x", expand=True, padx=6)
            rv = tk.StringVar(value=str(st.get("repeat", 1)))
            if k == "keys":
                ttk.Button(row, text="⌨ Записать", command=lambda v=vv: self._record(v)).pack(side="left")
                label(row, "×", "Dim.TLabel").pack(side="left", padx=(8, 2))
                ttk.Spinbox(row, from_=1, to=20, textvariable=rv, width=3).pack(side="left")
            elif k == "run":
                ttk.Button(row, text="Обзор…", command=lambda v=vv: self._browse(v, exe=True)).pack(side="left")
            elif k == "file":
                ttk.Button(row, text="Файл…", command=lambda v=vv: self._browse(v)).pack(side="left")
                ttk.Button(row, text="Папка…", command=lambda v=vv: self._browse(v, folder=True)).pack(side="left", padx=(4, 0))
            elif k == "site":
                ttk.Button(row, text="Открыть", command=lambda v=vv: self._open_now(v)).pack(side="left")
            ttk.Button(row, text="✕", width=3, style="Ghost.TButton",
                       command=lambda i=i: self._remove_step(i)).pack(side="left", padx=(6, 0))
            self.step_rows.append({"kind": kv, "value": vv, "repeat": rv})

    def _browse(self, var, exe=False, folder=False):
        if folder:
            p = filedialog.askdirectory(parent=self)
        elif exe:
            p = filedialog.askopenfilename(parent=self, filetypes=[("Программы", "*.exe *.bat *.lnk"), ("Все файлы", "*.*")])
        else:
            p = filedialog.askopenfilename(parent=self)
        if p:
            p = os.path.normpath(p)
            var.set(f'"{p}"' if exe else p)
            self.commit()

    def _open_now(self, var):
        self.commit()
        v = to_cfg_step({"kind": "site", "value": var.get()})["open"]
        if v:
            os.startfile(v)

    def _add_step(self):
        if self.cur is None:
            return
        self.commit()
        self.macros[self.cur]["steps"].append({"kind": "keys", "value": "", "repeat": 1})
        self._render_steps()

    def _remove_step(self, i):
        self.commit()
        steps = self.macros[self.cur]["steps"]
        steps.pop(i)
        if not steps:
            steps.append({"kind": "keys", "value": "", "repeat": 1})
        self._render_steps()

    def _record(self, var):
        self.recording = var
        self.win_down = False
        self._say("Нажми сочетание клавиш… (Esc — отмена)")
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
            self._stop_record("Запись отменена")
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
        self._stop_record(f"Записано: {combo}")
        return "break"

    def _stop_record(self, msg):
        self.unbind_all("<KeyPress>")
        self.unbind_all("<KeyRelease>")
        self.bind_all("<Control-s>", lambda e: self.save())
        self.recording = None
        self.commit()
        self._say(msg)

    # ---------------------------------------------------------- model

    def commit(self):
        if self.loading or self.cur is None:
            return
        m = self.macros[self.cur]
        m["label"] = self.label_var.get().strip()
        m["id"] = self.id_var.get().strip()
        icon = self.icon_var.get().split()
        m["icon"] = icon[-1] if icon else "bolt"
        m["confirm"] = bool(self.confirm_var.get())
        steps = []
        for r in self.step_rows:
            k = LABEL_KIND.get(r["kind"].get(), "keys")
            v = r["value"].get().strip()
            if k == "settings":
                v = LABEL_SETTINGS.get(v, v)
                if not v.startswith("ms-settings:"):
                    v = "ms-settings:sound"
            elif k == "system":
                v = LABEL_SYSTEM.get(v, v if v in SYSTEM_LABEL else "lock")
            elif k == "site" and not v:
                v = "https://"
            try:
                rep = max(1, min(50, int(r["repeat"].get())))
            except ValueError:
                rep = 1
            steps.append({"kind": k, "value": v, "repeat": rep})
        if steps:
            m["steps"] = steps
        text = self._item_text(m)
        if self.listbox.get(self.cur) != text:
            self.listbox.delete(self.cur)
            self.listbox.insert(self.cur, text)
            self.listbox.selection_set(self.cur)

    def _snapshot(self):
        return json.dumps([denormalize(m) for m in self.macros], ensure_ascii=False, sort_keys=True)

    def save(self):
        self.commit()
        for m in self.macros:
            err = validate(m)
            if err:
                self._say(err, error=True)
                return False
        ids = [m["id"] for m in self.macros]
        dup = next((i for i in ids if ids.count(i) > 1), None)
        if dup:
            self._say(f"ID «{dup}» повторяется", error=True)
            return False
        macros = [denormalize(m) for m in self.macros]
        edit_config(lambda c: c.__setitem__("macros", macros))
        self.saved = self._snapshot()
        self.app.refresh_tray()
        self._say("Сохранено. Телефон обновит кнопки в течение минуты")
        return True

    def confirm_discard(self):
        """True when it is fine to exit (saved, or the user chose what to do)."""
        self.commit()
        if self._snapshot() == self.saved:
            return True
        self.app.show()
        ans = messagebox.askyesnocancel("Сохранить?", "Есть несохранённые изменения в макросах. Сохранить?", parent=self)
        if ans is None:
            return False
        return self.save() if ans else True

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
        self._say(f"Выполняю «{m['label']}»…")

        def run():
            try:
                agent.run_macro(macro)
                self.after(0, self._say, f"«{m['label']}» выполнен")
            except Exception as e:  # noqa: BLE001 — show any failure to the user
                self.after(0, self._say, f"Ошибка: {e}", True)

        threading.Timer(0.6, run).start()

    def _say(self, msg, error=False):
        self.status.config(text=msg, foreground=DANGER if error else DIM)


# ================================================================ entry point

def main():
    try:
        ctypes.windll.shcore.SetProcessDpiAwareness(1)
    except Exception:  # noqa: BLE001 — older Windows
        pass
    ctypes.windll.shell32.SetCurrentProcessExplicitAppUserModelID("DeskDash.PC")
    mutex = ctypes.windll.kernel32.CreateMutexW(None, False, "DeskDash.PC.Singleton")  # noqa: F841 — held for life
    if ctypes.windll.kernel32.GetLastError() == 183:  # ERROR_ALREADY_EXISTS: wake the running window
        try:
            urllib.request.urlopen(f"http://127.0.0.1:{agent.config()['port']}/api/show", timeout=2)
        except Exception:  # noqa: BLE001
            pass
        return
    server, error = None, None
    try:
        server = agent.start()
    except OSError as e:
        error = "порт занят" if getattr(e, "winerror", None) == 10048 else str(e)
    App(server, error, "--minimized" in sys.argv).mainloop()


if __name__ == "__main__":
    main()
