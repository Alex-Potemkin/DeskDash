"""DeskDash Hub: one place for the whole house, meant to run on a Raspberry Pi next to the NAS.

It keeps the Home Assistant and Yandex logins, watches the computers with the DeskDash agent and the Pi
itself, runs scenes that mix all of them and automations by time, sun, devices and calls (automations.py),
keeps the phones' saved settings and a nightly backup on the NAS, hands out photos for the dashboard's
photo frame, and serves:
  * the web app for phones (open http://<pi>:8780 and "Add to home screen"),
  * the same JSON API for the DeskDash dashboard on the desk.
Standard library only.

    python3 deskdash_hub.py                  run
    python3 deskdash_hub.py token add NAME   new access token (--admin can change settings)
    python3 deskdash_hub.py token list
"""
import json
import mimetypes
import shutil
import os
import random
import re
import secrets
import socket
import sys
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, unquote, urlparse

import automations
import machines
import providers
from providers import SCENE, SWITCH, ProviderError

VERSION = "1.1"
HERE = os.path.dirname(os.path.abspath(__file__))
WEB_DIR = os.path.join(HERE, "web")
if os.environ.get("DESKDASH_HUB_DATA"):
    DATA_DIR = os.environ["DESKDASH_HUB_DATA"]
elif os.name == "nt":
    DATA_DIR = os.path.join(os.environ.get("APPDATA") or os.path.expanduser("~"), "DeskDash Hub")
else:
    DATA_DIR = os.path.join(os.environ.get("XDG_CONFIG_HOME") or os.path.expanduser("~/.config"), "deskdash-hub")
CONFIG_PATH = os.path.join(DATA_DIR, "config.json")
HUB_DISCOVERY_PORT = 8767

HA_EVERY, YA_EVERY, PC_EVERY, SYS_EVERY = 3, 15, 10, 30
HOLD_S = 4


def log(msg):
    print(time.strftime("%H:%M:%S ") + msg, flush=True)


# ---------------------------------------------------------------- config

_cfg_lock = threading.Lock()
_cfg = {}


def config():
    if not _cfg:
        os.makedirs(DATA_DIR, exist_ok=True)
        if os.path.exists(CONFIG_PATH):
            with open(CONFIG_PATH, encoding="utf-8") as f:
                _cfg.update(json.load(f))
        defaults = {"name": "Дом", "port": 8780, "tokens": [], "ha": {}, "yandex": {}, "pcs": [],
                    "nas": {"paths": ["/"]}, "scenes": [], "rules": [], "location": {}, "photos": {}}
        for k, v in defaults.items():
            _cfg.setdefault(k, v)
        if not _cfg["tokens"]:
            _cfg["tokens"].append({"name": "Администратор", "token": secrets.token_urlsafe(12), "admin": True})
        _write()
    return _cfg


def _write():
    tmp = CONFIG_PATH + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(_cfg, f, ensure_ascii=False, indent=2)
    os.replace(tmp, CONFIG_PATH)
    try:
        os.chmod(CONFIG_PATH, 0o600)  # tokens for HA and Yandex live here
    except OSError:
        pass


def update_config(fn):
    with _cfg_lock:
        config()
        fn(_cfg)
        _write()
    HUB.reconfigure()


# ---------------------------------------------------------------- state

class Hub:
    def __init__(self):
        self.lock = threading.Lock()
        self.changed = threading.Condition(self.lock)
        self.version = 0
        self.devices = {}        # id -> device dict from HA / Yandex
        self.holds = {}          # id -> time until which a local change wins over polls
        self.pcs = {}            # pc id -> poll result
        self.system = {}
        self.errors = {}
        self.ha = self.ya = None
        self.kick = threading.Event()
        self.ya_at = 0
        self.ha_failed = False

    def reconfigure(self):
        cfg = config()
        self.ha = providers.HomeAssistant(cfg["ha"], update_config_quiet) if providers.HomeAssistant.configured(cfg["ha"]) else None
        self.ya = providers.Yandex(cfg["yandex"]) if providers.Yandex.configured(cfg["yandex"]) else None
        with self.lock:
            self.devices = {k: v for k, v in self.devices.items()
                            if (v["source"] == "ha" and self.ha) or (v["source"] == "ya" and self.ya)}
            ids = {p["id"] for p in cfg["pcs"]}
            self.pcs = {k: v for k, v in self.pcs.items() if k in ids}
            for k in ("ha", "ya"):
                if not (self.ha if k == "ha" else self.ya):
                    self.errors.pop(k, None)
        self.ya_at = 0
        self.bump()
        self.kick.set()

    def bump(self):
        with self.changed:
            self.version += 1
            self.changed.notify_all()

    # polling

    def loop(self):
        last = {"pc": 0, "sys": 0}
        while True:
            now = time.time()
            self._poll_home(now)
            if now - last["pc"] >= PC_EVERY:
                last["pc"] = now
                self._poll_pcs()
            if now - last["sys"] >= SYS_EVERY:
                last["sys"] = now
                s = machines.system(config()["nas"].get("paths") or [])
                with self.lock:
                    self.system = s
                self.bump()
            self.kick.wait(HA_EVERY)
            self.kick.clear()

    def _poll_home(self, now):
        fresh, errors = {}, {}
        ha, ya = self.ha, self.ya
        if ha:
            try:
                fresh["ha"] = ha.devices()
            except (ProviderError, ValueError, KeyError) as e:
                errors["ha"] = str(e)
        if ya and now - self.ya_at >= YA_EVERY:
            self.ya_at = now
            try:
                fresh["ya"] = ya.devices()
            except (ProviderError, ValueError, KeyError) as e:
                errors["ya"] = str(e)
        with self.lock:
            changed = False
            for src, lst in fresh.items():
                new = {d["id"]: d for d in lst}
                for k, until in list(self.holds.items()):
                    if k in new and now < until and k in self.devices:
                        new[k] = self.devices[k]
                    elif now >= until:
                        self.holds.pop(k)
                old = {k: v for k, v in self.devices.items() if v["source"] == src}
                if old != new:
                    changed = True
                    self.devices = {**{k: v for k, v in self.devices.items() if v["source"] != src}, **new}
            for src in ("ha", "ya"):
                if src in errors or src in fresh:
                    err = errors.get(src)
                    if self.errors.get(src) != err:
                        changed = True
                        if err:
                            log(f"{'Home Assistant' if src == 'ha' else 'Яндекс'}: {err}")
                        self.errors[src] = err
        if changed:
            self.bump()

    def _poll_pcs(self):
        results = {}
        threads = []
        for p in config()["pcs"]:
            def one(p=p):
                results[p["id"]] = machines.Pc(p).poll()
            t = threading.Thread(target=one, daemon=True)
            t.start()
            threads.append(t)
        for t in threads:
            t.join(6)
        with self.lock:
            if results == self.pcs:
                return
            for pid, r in results.items():
                was = self.pcs.get(pid, {}).get("online")
                if was is not None and was != r["online"]:
                    log(f"ПК {pid}: {'в сети' if r['online'] else 'не в сети'}")
            self.pcs.update(results)
        self.bump()

    # snapshot for clients

    def snapshot(self):
        cfg = config()
        with self.lock:
            devices = list(self.devices.values())
            pcs = []
            for p in cfg["pcs"]:
                st = self.pcs.get(p["id"], {})
                mac = p.get("mac") or st.get("mac") or ""
                pcs.append({"id": p["id"], "name": p.get("name") or st.get("name") or p["host"],
                            "online": bool(st.get("online")), "error": st.get("error"),
                            "stats": st.get("stats") or {}, "macros": st.get("macros") or [], "canWake": bool(mac)})
            system, errors, version = dict(self.system), dict(self.errors), self.version
        devices += virtual_devices(cfg, pcs)
        return {"version": version, "name": cfg["name"], "hub": VERSION, "devices": devices, "pcs": pcs,
                "system": system, "scenes": [public_scene(s) for s in cfg["scenes"]], "rules": cfg.get("rules") or [],
                "errors": {k: v for k, v in errors.items() if v},
                "configured": {"ha": self.ha is not None, "ya": self.ya is not None}}

    def calls_on(self):
        """A call is on at one of the computers."""
        with self.lock:
            return any(p.get("online") and p.get("calls") for p in self.pcs.values())

    def find(self, did):
        with self.lock:
            d = self.devices.get(did)
        if d:
            return d
        return next((v for v in virtual_devices(config(), self.snapshot()["pcs"]) if v["id"] == did), None)

    # actions

    def act(self, did, action, value=None):
        """Runs one device action; raises ProviderError with a message for the user."""
        if did.startswith("scene:"):
            return run_scene(did[len("scene:"):])
        if did.startswith("pc:"):
            return pc_action(did, action)
        d = self.find(did)
        if d is None:
            raise ProviderError("нет такого устройства")
        api = self.ha if d["source"] == "ha" else self.ya
        if api is None:
            raise ProviderError("сервис не подключён")
        local = {}
        if action in ("on", "off"):
            api.set_on(d, action == "on")
            local = {"on": action == "on"}
        elif action == "toggle":
            api.set_on(d, not d["on"])
            local = {"on": not d["on"]}
        elif action == "run":
            api.run(d)
        elif action == "brightness":
            api.brightness(d, value)
            local = {"brightness": int(value), "on": True}
        elif action == "color_temp":
            api.color_temp(d, value)
            local = {"tempK": int(value), "on": True}
        elif action == "color":
            api.color(d, value)
            local = {"on": True}
        elif action == "temperature":
            api.temperature(d, value)
            local = {"target": float(value)}
        elif action in ("pause", "resume", "stop", "light"):
            if not hasattr(api, "command"):
                raise ProviderError("у устройства нет такой кнопки")
            api.command(d, action)
        else:
            raise ProviderError(f"неизвестное действие {action}")
        if local:
            with self.lock:
                if did in self.devices:
                    self.devices[did] = {**self.devices[did], **local}
                    self.holds[did] = time.time() + HOLD_S
            self.bump()
        if d["source"] == "ya":
            self.ya_at = 0
        threading.Timer(1.5, self.kick.set).start()


def update_config_quiet(fn):
    """For refreshed HA access tokens: saves without rebuilding the providers."""
    with _cfg_lock:
        fn(_cfg)
        _write()


HUB = Hub()


def virtual_devices(cfg, pcs):
    """Hub scenes and computers as tiles, so the dashboard shows them next to the lights."""
    out = []
    for s in cfg["scenes"]:
        d = providers.device("scene:" + s["id"], "hub", s["id"], s.get("label") or s["id"], SCENE)
        d["room"] = "Сценарии хаба"
        out.append(d)
    for p in pcs:
        d = providers.device("pc:" + p["id"], "pc", p["id"], p["name"], SWITCH)
        st = p["stats"]
        d.update(room="Компьютеры", on=p["online"], canToggle=p["canWake"] or p["online"],
                 state=f"ЦП {round(st.get('cpu', 0))}% · ОЗУ {st.get('ram', 0)}%" if p["online"] and st else "")
        out.append(d)
        for m in p["macros"]:
            d = providers.device(f"pc:{p['id']}:{m['id']}", "pc", m["id"], m.get("label") or m["id"], SCENE)
            d.update(room=p["name"], available=p["online"])
            out.append(d)
    return out


def pc_cfg(pid):
    p = next((p for p in config()["pcs"] if p["id"] == pid), None)
    if p is None:
        raise ProviderError("нет такого компьютера")
    return p


def pc_action(did, action):
    _, pid, *rest = did.split(":", 2)
    p = pc_cfg(pid)
    name = p.get("name") or HUB.pcs.get(pid, {}).get("name") or pid
    if rest:
        machines.Pc(p).macro(rest[0])
        log(f"макрос {rest[0]} на {name}")
    elif action in ("on", "toggle") and not HUB.pcs.get(pid, {}).get("online"):
        mac = p.get("mac") or HUB.pcs.get(pid, {}).get("mac")
        if not mac:
            raise ProviderError("для пробуждения нужен MAC-адрес")
        machines.wake(mac)
        log(f"пробуждаю {name}")
    elif action in ("off", "toggle"):
        macros = HUB.pcs.get(pid, {}).get("macros") or []
        m = next((m for m in macros if m["id"] in ("sleep", "shutdown", "off")), None)
        if m is None:
            raise ProviderError("на ПК нет макроса «Сон»")
        machines.Pc(p).macro(m["id"])
    HUB.kick.set()


# ---------------------------------------------------------------- scenes

def public_scene(s):
    return {"id": s["id"], "label": s.get("label") or s["id"], "icon": s.get("icon") or "star",
            "steps": s.get("steps") or []}


def run_scene(sid):
    s = next((s for s in config()["scenes"] if s["id"] == sid), None)
    if s is None:
        raise ProviderError("нет такого сценария")
    log(f"сценарий «{s.get('label', sid)}»")
    threading.Thread(target=run_steps, args=(s.get("steps") or [], s.get("label", sid)), daemon=True).start()


def run_steps(steps, label=""):
    """Steps of a scene or an automation, one after another; a failed step is logged and skipped."""
    for st in steps:
        try:
            if "wait" in st:
                time.sleep(min(float(st["wait"]), 600_000) / 1000)
            elif st.get("device"):
                HUB.act(st["device"], st.get("action", "toggle"), st.get("value"))
            elif st.get("pc") and st.get("macro"):
                machines.Pc(pc_cfg(st["pc"])).macro(st["macro"])
            elif st.get("wake"):
                pc_action("pc:" + st["wake"], "on")
        except (ProviderError, ValueError, TypeError) as e:
            log(f"«{label}»: шаг {st}: {e}")


ENGINE = automations.Engine(lambda: config(), lambda did, a, v=None: HUB.act(did, a, v), run_steps,
                            lambda: HUB.snapshot()["devices"], lambda: HUB.calls_on(), log, DATA_DIR)


# ---------------------------------------------------------------- saved phone settings and photos

PROFILES_DIR = os.path.join(DATA_DIR, "profiles")
PHOTO_EXT = (".jpg", ".jpeg", ".png", ".webp")


def profile_path(name):
    safe = re.sub(r"[^\w\-]", "_", str(name), flags=re.UNICODE)[:40] or "phone"
    return os.path.join(PROFILES_DIR, safe + ".json")


def profiles():
    if not os.path.isdir(PROFILES_DIR):
        return []
    out = []
    for f in sorted(os.listdir(PROFILES_DIR)):
        if f.endswith(".json"):
            st = os.stat(os.path.join(PROFILES_DIR, f))
            out.append({"name": f[:-5], "at": int(st.st_mtime), "size": st.st_size})
    return out


def photos_dir():
    d = (config().get("photos") or {}).get("path") or ""
    return d if d and os.path.isdir(d) else None


def photo_list():
    base = photos_dir()
    if not base:
        return []
    out = []
    for root, dirs, files in os.walk(base):
        dirs[:] = [d for d in dirs if not d.startswith(".")]
        for f in files:
            if f.lower().endswith(PHOTO_EXT) and not f.startswith("."):
                out.append(os.path.relpath(os.path.join(root, f), base).replace(os.sep, "/"))
                if len(out) >= 3000:
                    return out
    return out


# ---------------------------------------------------------------- http

def token_of(t):
    for x in config()["tokens"]:
        if t and secrets.compare_digest(t.encode(), str(x["token"]).encode()):
            return x
    return None


class Handler(BaseHTTPRequestHandler):
    server_version = "DeskDashHub/" + VERSION
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        pass

    def _send(self, code, obj):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def _body(self):
        n = int(self.headers.get("Content-Length") or 0)
        if not n:
            return {}
        try:
            o = json.loads(self.rfile.read(n).decode("utf-8"))
            return o if isinstance(o, dict) else {}
        except ValueError:
            return {}

    def _user(self, admin=False):
        q = parse_qs(urlparse(self.path).query)
        t = self.headers.get("X-Token") or q.get("token", [""])[0]
        auth = self.headers.get("Authorization", "")
        if not t and auth.startswith("Bearer "):
            t = auth[7:]
        u = token_of(t.strip())
        if u is None:
            self._send(401, {"ok": False, "error": "неверный токен"})
            return None
        if admin and not u.get("admin"):
            self._send(403, {"ok": False, "error": "нужен токен администратора"})
            return None
        return u

    def _static(self, path):
        rel = "index.html" if path in ("", "/") else path.lstrip("/")
        full = os.path.realpath(os.path.join(WEB_DIR, rel))
        if not full.startswith(os.path.realpath(WEB_DIR) + os.sep) or not os.path.isfile(full):
            full = os.path.join(WEB_DIR, "index.html")  # app routes all land on the page
        with open(full, "rb") as f:
            data = f.read()
        ctype = mimetypes.guess_type(full)[0] or "application/octet-stream"
        if full.endswith(".webmanifest"):
            ctype = "application/manifest+json"
        if ctype.startswith("text/") or ctype.endswith(("javascript", "json")):
            ctype += "; charset=utf-8"
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-cache")
        self.end_headers()
        self.wfile.write(data)

    def _events(self):
        """Server-sent events: the whole state each time something changes, a comment every 20 s otherwise."""
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Connection", "close")
        self.send_header("X-Accel-Buffering", "no")
        self.end_headers()
        self.close_connection = True
        seen = -1
        try:
            while True:
                with HUB.changed:
                    if HUB.version == seen:
                        HUB.changed.wait(20)
                    v = HUB.version
                if v == seen:
                    self.wfile.write(b": ping\n\n")
                else:
                    seen = v
                    self.wfile.write(b"data: " + json.dumps(HUB.snapshot(), ensure_ascii=False).encode("utf-8") + b"\n\n")
                self.wfile.flush()
        except OSError:
            pass

    def do_GET(self):
        path = urlparse(self.path).path
        if not path.startswith("/api/"):
            return self._static(path)
        if path == "/api/ping":
            return self._send(200, {"ok": True, "app": "deskdash-hub", "name": config()["name"], "version": VERSION})
        u = self._user(admin=path in ("/api/config", "/api/discover"))
        if u is None:
            return
        if path == "/api/me":
            return self._send(200, {"ok": True, "name": u["name"], "admin": bool(u.get("admin"))})
        if path == "/api/state":
            return self._send(200, HUB.snapshot())
        if path == "/api/events":
            return self._events()
        if path == "/api/config":
            return self._send(200, public_config())
        if path == "/api/discover":
            return self._send(200, {"ok": True, "agents": machines.discover_agents()})
        if path == "/api/profiles":
            return self._send(200, {"ok": True, "profiles": profiles()})
        if path.startswith("/api/profiles/"):
            f = profile_path(unquote(path[len("/api/profiles/"):]))
            if not os.path.isfile(f):
                return self._send(404, {"ok": False, "error": "нет такой копии"})
            with open(f, encoding="utf-8") as fh:
                return self._send(200, {"ok": True, "backup": json.load(fh)})
        if path == "/api/photos":
            lst = photo_list()
            random.shuffle(lst)
            return self._send(200, {"ok": True, "photos": lst[:500]})
        if path == "/api/photo":
            return self._photo(parse_qs(urlparse(self.path).query).get("f", [""])[0])
        self._send(404, {"ok": False, "error": "not found"})

    def _photo(self, rel):
        base = photos_dir()
        full = os.path.realpath(os.path.join(base or "", rel)) if base else ""
        if not base or not full.startswith(os.path.realpath(base) + os.sep) or not os.path.isfile(full):
            return self._send(404, {"ok": False, "error": "нет такого фото"})
        self.send_response(200)
        self.send_header("Content-Type", mimetypes.guess_type(full)[0] or "image/jpeg")
        self.send_header("Content-Length", str(os.path.getsize(full)))
        self.send_header("Cache-Control", "max-age=86400")
        self.end_headers()
        with open(full, "rb") as fh:
            shutil.copyfileobj(fh, self.wfile, 65536)

    def do_POST(self):
        path = urlparse(self.path).path
        body = self._body()  # read first: with keep-alive an unread body would spoil the next request
        u = self._user(admin=path.startswith(("/api/config", "/api/tokens", "/api/import", "/api/backup")))
        if u is None:
            return
        try:
            if path.startswith("/api/device/"):
                did = unquote(path[len("/api/device/"):])
                HUB.act(did, body.get("action", "toggle"), body.get("value"))
                return self._send(200, {"ok": True})
            if path.startswith("/api/scene/"):
                run_scene(unquote(path[len("/api/scene/"):]))
                return self._send(200, {"ok": True})
            if path.startswith("/api/rule/"):
                ENGINE.run_now(unquote(path[len("/api/rule/"):]))
                return self._send(200, {"ok": True})
            if path.startswith("/api/profiles/"):
                name = unquote(path[len("/api/profiles/"):])
                if body.get("app") != "deskdash":
                    raise ValueError("это не резервная копия DeskDash")
                os.makedirs(PROFILES_DIR, exist_ok=True)
                tmp = profile_path(name) + ".tmp"
                with open(tmp, "w", encoding="utf-8") as fh:
                    json.dump(body, fh, ensure_ascii=False)
                os.replace(tmp, profile_path(name))
                try:
                    os.chmod(profile_path(name), 0o600)  # logins of the smart home are in it
                except OSError:
                    pass
                log(f"настройки телефона «{name}» сохранены")
                return self._send(200, {"ok": True})
            if path == "/api/backup":
                try:
                    return self._send(200, {"ok": True, "path": ENGINE.backup_now()})
                except OSError as e:
                    raise ProviderError(str(e))
            if path == "/api/config":
                apply_config(body)
                return self._send(200, {"ok": True, **public_config()})
            if path == "/api/import":
                return self._send(200, {"ok": True, "imported": import_from_phone(body)})
            if path == "/api/tokens":
                name = (body.get("name") or "").strip() or "Телефон"
                t = {"name": name, "token": secrets.token_urlsafe(12), "admin": bool(body.get("admin"))}
                update_config(lambda c: c["tokens"].append(t))
                log(f"новый доступ: {name}")
                return self._send(200, {"ok": True, **t})
            if path == "/api/tokens/delete":
                tok = body.get("token", "")
                if tok == u["token"]:
                    raise ProviderError("нельзя удалить свой же доступ")
                update_config(lambda c: c.__setitem__("tokens", [x for x in c["tokens"] if x["token"] != tok]))
                return self._send(200, {"ok": True})
        except ProviderError as e:
            return self._send(200, {"ok": False, "error": str(e)})
        except (ValueError, TypeError, KeyError) as e:
            return self._send(400, {"ok": False, "error": f"неверный запрос: {e}"})
        self._send(404, {"ok": False, "error": "not found"})


def public_config():
    c = config()
    ha, ya = c["ha"], c["yandex"]
    return {"name": c["name"],
            "ha": {"url": ha.get("url", ""), "hasToken": bool(ha.get("token")), "hasLogin": bool(ha.get("refresh"))},
            "yandex": {"hasToken": bool(ya.get("token")), "clientId": ya.get("clientId", "")},
            "pcs": [{k: p.get(k, "") for k in ("id", "name", "host", "port", "token", "mac")} for p in c["pcs"]],
            "nas": c["nas"], "scenes": c["scenes"], "tokens": c["tokens"], "rules": c.get("rules") or [],
            "location": c.get("location") or {}, "photos": c.get("photos") or {}, "backup_dir": c.get("backup_dir", "")}


def apply_config(b):
    """Partial update from the web settings page. Secrets are only replaced when a new value is sent."""
    def fn(c):
        if "name" in b:
            c["name"] = str(b["name"]).strip() or "Дом"
        if "ha" in b:
            h = b["ha"]
            if "url" in h:
                c["ha"]["url"] = str(h["url"]).strip()
            if h.get("token"):
                c["ha"]["token"] = str(h["token"]).strip()
            if h.get("clear"):
                c["ha"] = {"url": c["ha"].get("url", "")}
        if "yandex" in b:
            y = b["yandex"]
            if "clientId" in y:
                c["yandex"]["clientId"] = str(y["clientId"]).strip()
            if y.get("token"):
                c["yandex"]["token"] = str(y["token"]).strip()
            if y.get("clear"):
                c["yandex"].pop("token", None)
        if "pcs" in b:
            pcs = []
            for p in b["pcs"]:
                if not str(p.get("host", "")).strip():
                    continue
                pcs.append({"id": str(p.get("id") or uuid.uuid4().hex[:6]), "name": str(p.get("name", "")).strip(),
                            "host": str(p["host"]).strip(), "port": int(p.get("port") or 8765),
                            "token": str(p.get("token", "")).strip(), "mac": str(p.get("mac", "")).strip()})
            c["pcs"] = pcs
        if "nas" in b:
            c["nas"]["paths"] = [str(x).strip() for x in b["nas"].get("paths") or [] if str(x).strip()]
        if "scenes" in b:
            scenes = []
            for s in b["scenes"]:
                if not str(s.get("label", "")).strip():
                    continue
                scenes.append({"id": str(s.get("id") or uuid.uuid4().hex[:6]), "label": str(s["label"]).strip(),
                               "icon": str(s.get("icon") or "star"), "steps": list(s.get("steps") or [])})
            c["scenes"] = scenes
        if "rules" in b:
            rules = []
            for r in b["rules"]:
                if not isinstance(r, dict) or not r.get("when"):
                    continue
                rules.append({"id": str(r.get("id") or uuid.uuid4().hex[:6]), "label": str(r.get("label") or "Автоматизация"),
                              "on": bool(r.get("on", True)), "when": dict(r["when"]),
                              "days": [int(d) for d in r.get("days") or [] if 1 <= int(d) <= 7],
                              "do": list(r.get("do") or []), "dawn": dict(r.get("dawn") or {})})
            c["rules"] = rules
        if "location" in b:
            loc = b["location"] or {}
            c["location"] = {"lat": float(loc["lat"]), "lon": float(loc["lon"])} if "lat" in loc else {}
        if "photos" in b:
            c["photos"] = {"path": str((b["photos"] or {}).get("path", "")).strip()}
        if "backup_dir" in b:
            c["backup_dir"] = str(b["backup_dir"]).strip()
    update_config(fn)


def import_from_phone(b):
    """The dashboard sends its Home Assistant / Yandex logins and its PC, so they are set up once."""
    done = []

    def fn(c):
        if b.get("ha_url") and (b.get("ha_token") or b.get("ha_refresh")):
            c["ha"] = {"url": b["ha_url"]}
            if b.get("ha_token"):
                c["ha"]["token"] = b["ha_token"]
            else:
                c["ha"]["refresh"] = b["ha_refresh"]
            done.append("Home Assistant")
        if b.get("ya_token"):
            c["yandex"]["token"] = b["ya_token"]
            if b.get("ya_client_id"):
                c["yandex"]["clientId"] = b["ya_client_id"]
            done.append("Яндекс")
        pc = b.get("pc") or {}
        if pc.get("host") and pc.get("token"):
            same = next((p for p in c["pcs"] if p["host"] == pc["host"]), None)
            entry = {"id": same["id"] if same else uuid.uuid4().hex[:6], "name": pc.get("name", ""), "host": pc["host"],
                     "port": int(pc.get("port") or 8765), "token": pc["token"], "mac": pc.get("mac", "")}
            c["pcs"] = [p for p in c["pcs"] if p is not same] + [entry]
            done.append("компьютер")
    update_config(fn)
    log("импорт с телефона: " + (", ".join(done) or "ничего"))
    return done


def discovery():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        s.bind(("", HUB_DISCOVERY_PORT))
    except OSError as e:
        log(f"поиск по сети недоступен: {e}")
        return
    while True:
        try:
            data, addr = s.recvfrom(1024)
            if data.strip() == b"DESKDASH_HUB_DISCOVER":
                c = config()
                s.sendto(json.dumps({"name": c["name"], "port": c["port"]}, ensure_ascii=False).encode("utf-8"), addr)
        except OSError:
            time.sleep(1)


def local_ips():
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("8.8.8.8", 80))
            return [s.getsockname()[0]]
    except OSError:
        return ["127.0.0.1"]


def cli(args):
    cfg = config()
    if args[:2] == ["token", "add"] and len(args) >= 3:
        admin = "--admin" in args
        name = " ".join(a for a in args[2:] if a != "--admin")
        t = {"name": name, "token": secrets.token_urlsafe(12), "admin": admin}
        update_config(lambda c: c["tokens"].append(t))
        print(f"{name}: {t['token']}")
        print(f"ссылка: http://{local_ips()[0]}:{cfg['port']}/#t={t['token']}")
    elif args[:2] == ["token", "list"]:
        for t in cfg["tokens"]:
            print(f"{t['name']}{' (админ)' if t.get('admin') else ''}: {t['token']}")
    else:
        print(__doc__)


def main():
    if sys.stdout is not None:
        try:
            sys.stdout.reconfigure(encoding="utf-8")
        except Exception:
            pass
    if len(sys.argv) > 1:
        return cli(sys.argv[1:])
    cfg = config()
    HUB.reconfigure()
    threading.Thread(target=HUB.loop, daemon=True).start()
    threading.Thread(target=discovery, daemon=True).start()
    ENGINE.start()
    server = ThreadingHTTPServer(("0.0.0.0", int(cfg["port"])), Handler)
    server.daemon_threads = True
    admin = next(t for t in cfg["tokens"] if t.get("admin"))
    log(f"хаб «{cfg['name']}» запущен: http://{local_ips()[0]}:{cfg['port']}")
    log(f"  вход администратора: http://{local_ips()[0]}:{cfg['port']}/#t={admin['token']}")
    log(f"  конфиг: {CONFIG_PATH}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
