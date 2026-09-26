"""DeskDash Agent: lets the DeskDash phone dashboard show PC stats and run macros.

Standard library only; the OS-specific parts live in sys_windows.py / sys_linux.py.
The GUI (deskdash_app.py / DeskDash.exe) embeds it via start(); it can also run headless:
    python deskdash_agent.py
Config: %APPDATA%/DeskDash/config.json on Windows, ~/.config/deskdash/config.json on Linux.
"""
import collections
import json
import os
import secrets
import socket
import sys
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, unquote, urlparse

import spotify

if os.name == "nt":
    import sys_windows as sysapi
    DATA_DIR = os.path.join(os.environ.get("APPDATA") or os.path.expanduser("~"), "DeskDash")
else:
    import sys_linux as sysapi
    DATA_DIR = os.path.join(os.environ.get("XDG_CONFIG_HOME") or os.path.expanduser("~/.config"), "deskdash")
os.makedirs(DATA_DIR, exist_ok=True)
CONFIG_PATH = os.path.join(DATA_DIR, "config.json")
LOG_PATH = os.path.join(DATA_DIR, "agent.log")
DISCOVERY_PORT = 8766

LOG = collections.deque(maxlen=200)  # recent log lines, shown in the GUI
CLIENTS = {}  # phone ip -> (last seen, token ok)
ON_SHOW = None  # set by the GUI: called when another instance asks to show the window

DEFAULT_MACROS = [
    {"id": "lock", "label": "Блок", "icon": "lock", "system": "lock"},
    {"id": "mute", "label": "Звук", "icon": "mute", "keys": "volume_mute"},
    {"id": "vol_down", "label": "Тише", "icon": "vol_down", "keys": "volume_down", "repeat": 3},
    {"id": "vol_up", "label": "Громче", "icon": "vol_up", "keys": "volume_up", "repeat": 3},
    {"id": "media", "label": "Пауза", "icon": "play", "keys": "media_play_pause"},
    {"id": "desktop", "label": "Стол", "icon": "desktop", "keys": "win+d"},
    {"id": "shot", "label": "Скрин", "icon": "screenshot", "keys": sysapi.SCREENSHOT_KEYS},
    {"id": "spotify", "label": "Spotify", "icon": "music", "run": sysapi.SPOTIFY_COMMAND},
    {"id": "screen_off", "label": "Экран", "icon": "eye_off", "system": "monitor_off"},
    {"id": "sleep", "label": "Сон", "icon": "sleep", "system": "sleep", "confirm": True},
]

vk_of = sysapi.check_key  # the GUI validates key names through this


def log(msg):
    line = time.strftime("%H:%M:%S ") + msg
    LOG.append(line)
    if sys.stdout is not None:
        try:
            print(line, flush=True)
            return
        except Exception:
            pass
    with open(LOG_PATH, "a", encoding="utf-8") as f:
        f.write(line + "\n")


# ---------- config ----------

_cfg_cache = {"mtime": None, "cfg": None}


def default_mac():
    n = uuid.getnode()
    return ":".join(f"{(n >> s) & 0xFF:02X}" for s in range(40, -1, -8))


def config():
    """Loads config.json, creating it with defaults on first run; reloads when the file changes."""
    mtime = os.path.getmtime(CONFIG_PATH) if os.path.exists(CONFIG_PATH) else None
    if mtime is not None and mtime == _cfg_cache["mtime"]:
        return _cfg_cache["cfg"]
    cfg = {}
    if mtime is not None:
        with open(CONFIG_PATH, encoding="utf-8") as f:
            cfg = json.load(f)
    defaults = {
        "port": 8765,
        "token": secrets.token_hex(4),
        "name": socket.gethostname(),
        "mac": "",
        "macros": DEFAULT_MACROS,
    }
    missing = {k: v for k, v in defaults.items() if k not in cfg}
    if missing:
        cfg.update(missing)
        with open(CONFIG_PATH, "w", encoding="utf-8") as f:
            json.dump(cfg, f, ensure_ascii=False, indent=2)
        mtime = os.path.getmtime(CONFIG_PATH)
    _cfg_cache.update(mtime=mtime, cfg=cfg)
    return cfg


_cfg_lock = threading.Lock()


def update_config(fn):
    """Read-modify-write of config.json, so the GUI tabs and Spotify login never clobber each other."""
    with _cfg_lock:
        cfg = json.loads(json.dumps(config()))
        fn(cfg)
        tmp = CONFIG_PATH + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(cfg, f, ensure_ascii=False, indent=2)
        os.replace(tmp, CONFIG_PATH)
        return config()


spotify.init(config, update_config)


# ---------- actions ----------

def run_step(step):
    if "wait" in step:
        time.sleep(float(step["wait"]) / 1000)
    if "keys" in step:
        sysapi.press(step["keys"], int(step.get("repeat", 1)))
    if "run" in step:
        sysapi.run_command(step["run"])
    if "open" in step:
        sysapi.open_target(step["open"])
    if "system" in step:
        sysapi.system_action(step["system"])


def run_macro(macro):
    for step in macro.get("steps") or [macro]:
        run_step(step)


# ---------- http ----------

def public_macro(m):
    return {"id": m.get("id", ""), "label": m.get("label", m.get("id", "")),
            "icon": m.get("icon", "bolt"), "confirm": bool(m.get("confirm", False))}


class Handler(BaseHTTPRequestHandler):
    server_version = "DeskDash/1.0"

    def log_message(self, fmt, *args):
        pass

    def _send(self, code, obj):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _send_page(self, ok, message):
        color = "#C8A27C" if ok else "#E5534B"
        html = f"""<!doctype html><meta charset="utf-8"><title>DeskDash</title>
<body style="margin:0;height:100vh;display:grid;place-items:center;background:#17120F;color:#F1E6DA;
font:18px 'Segoe UI',sans-serif"><div style="text-align:center"><div style="font-size:44px;color:{color}">
{'✓' if ok else '✕'}</div><p>{message}</p><p style="color:#9C8B7E;font-size:14px">Вкладку можно закрыть
</p></div></body>""".encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(html)))
        self.end_headers()
        self.wfile.write(html)

    def _spotify(self, fn):
        try:
            self._send(200, {"ok": True, **(fn() or {})})
        except spotify.SpotifyError as e:
            self._send(200, {"ok": False, "error": str(e)})

    def _authed(self):
        cfg = config()
        token = self.headers.get("X-Token") or parse_qs(urlparse(self.path).query).get("token", [""])[0]
        ok = secrets.compare_digest(token.encode("utf-8"), str(cfg["token"]).encode("utf-8"))
        ip = self.client_address[0]
        prev = CLIENTS.get(ip)
        CLIENTS[ip] = (time.time(), ok)
        if prev is None or prev[1] != ok or time.time() - prev[0] > 60:
            log(f"телефон {ip} подключился" if ok else f"телефон {ip}: неверный токен")
        if ok:
            return cfg
        self._send(401, {"ok": False, "error": "bad token"})
        return None

    def do_GET(self):
        path = urlparse(self.path).path
        if path == "/api/ping":
            return self._send(200, {"ok": True, "app": "deskdash"})
        if path == "/api/show" and self.client_address[0] == "127.0.0.1" and ON_SHOW:
            ON_SHOW()  # a second DeskDash.exe asks the running one to open its window
            return self._send(200, {"ok": True})
        if path == spotify.CALLBACK_PATH and self.client_address[0] == "127.0.0.1":
            ok, message = spotify.handle_callback(urlparse(self.path).query)
            log(message)
            return self._send_page(ok, message)
        cfg = self._authed()
        if cfg is None:
            return
        if path == "/api/info":
            return self._send(200, {"name": cfg["name"], "mac": cfg.get("mac") or default_mac(),
                                    "macros": [public_macro(m) for m in cfg["macros"]],
                                    "spotify": spotify.status()["logged_in"]})
        if path == "/api/stats":
            return self._send(200, sysapi.stats())
        if path == "/api/spotify/devices":
            return self._spotify(lambda: {"devices": spotify.devices()})
        if path == "/api/spotify/state":
            return self._spotify(lambda: {"devices": spotify.devices(), "now": spotify.now_playing()})
        self._send(404, {"ok": False, "error": "not found"})

    def do_POST(self):
        path = urlparse(self.path).path
        cfg = self._authed()
        if cfg is None:
            return
        if path.startswith("/api/spotify/cmd/"):
            return self._spotify(lambda: spotify.command(path[len("/api/spotify/cmd/"):]))
        if path.startswith("/api/spotify/transfer/"):
            device = unquote(path[len("/api/spotify/transfer/"):])
            log(f"Spotify: переключение устройства с {self.client_address[0]}")
            return self._spotify(lambda: spotify.transfer(device))
        if not path.startswith("/api/macro/"):
            return self._send(404, {"ok": False, "error": "not found"})
        mid = unquote(path[len("/api/macro/"):])
        macro = next((m for m in cfg["macros"] if m.get("id") == mid), None)
        if macro is None:
            return self._send(404, {"ok": False, "error": f"no macro {mid}"})
        try:
            run_macro(macro)
            log(f"макрос «{macro.get('label', mid)}» с {self.client_address[0]}")
            self._send(200, {"ok": True})
        except Exception as e:
            log(f"макрос «{macro.get('label', mid)}»: ошибка {e}")
            self._send(500, {"ok": False, "error": str(e)})


def discovery():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        s.bind(("", DISCOVERY_PORT))
    except OSError as e:
        log(f"поиск по сети недоступен: {e}")
        return
    while True:
        try:
            data, addr = s.recvfrom(1024)
            if data.strip() == b"DESKDASH_DISCOVER":
                cfg = config()
                reply = json.dumps({"name": cfg["name"], "port": cfg["port"]}, ensure_ascii=False)
                s.sendto(reply.encode("utf-8"), addr)
        except OSError:
            time.sleep(1)


def local_ips():
    ips = set()
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("8.8.8.8", 80))
            ips.add(s.getsockname()[0])
    except OSError:
        pass
    try:
        ips.update(ip for ip in socket.gethostbyname_ex(socket.gethostname())[2] if not ip.startswith("127."))
    except OSError:
        pass
    return sorted(ips)


def start():
    """Starts the agent in background threads; returns the HTTP server. Raises OSError if the port is busy."""
    cfg = config()
    server = ThreadingHTTPServer(("0.0.0.0", int(cfg["port"])), Handler)
    sysapi.start_background()
    threading.Thread(target=discovery, daemon=True).start()
    threading.Thread(target=server.serve_forever, daemon=True).start()
    log(f"агент запущен, порт {cfg['port']}")
    return server


def main():
    if sys.stdout is not None:
        try:
            sys.stdout.reconfigure(encoding="utf-8")
        except Exception:
            pass
    cfg = config()
    start()
    log(f"  IP:     {', '.join(local_ips()) or '?'}")
    log(f"  Токен:  {cfg['token']}")
    log(f"  Конфиг: {CONFIG_PATH}")
    while True:
        time.sleep(3600)


if __name__ == "__main__":
    main()
