"""Smoke test of the hub against a fake Home Assistant and a fake DeskDash agent.

    python hub/ci_smoke.py
"""
import json
import os
import subprocess
import sys
import tempfile
import threading
import time
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HA_PORT, AGENT_PORT, HUB_PORT = 18123, 18765, 18780
CALLS = []
STATES = {
    "light.kitchen": {"state": "on", "attributes": {"friendly_name": "Кухня", "brightness": 128,
                                                    "supported_color_modes": ["color_temp"]}},
    "switch.kettle": {"state": "off", "attributes": {"friendly_name": "Чайник"}},
    "sensor.temp": {"state": "23.4", "attributes": {"friendly_name": "Температура", "unit_of_measurement": "°C"}},
}


class Fake(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def _json(self, o, code=200):
        b = json.dumps(o, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def do_GET(self):
        if self.server.server_port == HA_PORT:
            if self.headers.get("Authorization") != "Bearer good":
                return self._json({}, 401)
            return self._json([{"entity_id": k, **v} for k, v in STATES.items()])
        if "token=abc" not in self.path:
            return self._json({"ok": False}, 401)
        if self.path.startswith("/api/info"):
            return self._json({"name": "Ноутбук", "mac": "AA:BB:CC:DD:EE:FF", "macros": [{"id": "lock", "label": "Блок"}]})
        return self._json({"cpu": 10, "ram": 40, "uptime": 60})

    def do_POST(self):
        body = self.rfile.read(int(self.headers.get("Content-Length") or 0))
        if self.path == "/api/template":
            return self._json("")
        CALLS.append(self.path.split("?")[0])
        eid = (json.loads(body or b"{}") or {}).get("entity_id")
        if eid in STATES:
            STATES[eid]["state"] = "on" if self.path.endswith("turn_on") else "off"
        self._json({"ok": True} if self.server.server_port == AGENT_PORT else [])


def call(path, body=None, token="user"):
    req = urllib.request.Request(f"http://127.0.0.1:{HUB_PORT}{path}", method="GET" if body is None else "POST",
                                 data=None if body is None else json.dumps(body).encode(),
                                 headers={"X-Token": token, "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=5) as r:
            return r.status, json.loads(r.read())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read())


def check(cond, what):
    print(("ok   " if cond else "FAIL ") + what, flush=True)
    if not cond:
        sys.exit(1)


def engine_check():
    """The automation engine on its own: fires once, keeps to weekdays, follows a door and the calls."""
    import datetime
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    import automations
    fired, devs, call_on = [], [{"id": "door", "on": False}], [False]
    rules = [{"id": "t", "label": "Будни", "when": {"at": "07:00"}, "days": [1, 2, 3, 4, 5], "do": [{"wait": 1}]},
             {"id": "d", "label": "Дверь", "when": {"device": "door", "to": "on"}, "do": [{"wait": 1}]},
             {"id": "c", "label": "Созвон", "when": {"call": "start"}, "do": [{"wait": 1}]},
             {"id": "w", "label": "Рассвет", "when": {"at": "07:30"}, "dawn": {"device": "lamp", "minutes": 20}}]
    e = automations.Engine(lambda: {"rules": rules}, lambda *a: None, lambda steps, label: fired.append(label),
                           lambda: devs, lambda: call_on[0], lambda m: None, tempfile.mkdtemp())
    e.fire = lambda r: fired.append(r["label"])
    tz = datetime.datetime.now().astimezone().tzinfo
    mon = datetime.datetime(2026, 11, 2, 7, 0, 30, tzinfo=tz)
    e.tick(mon - datetime.timedelta(minutes=1))
    e.tick(mon)
    e.tick(mon + datetime.timedelta(seconds=20))
    check(fired.count("Будни") == 1, "a timed rule fires once")
    e.tick(datetime.datetime(2026, 11, 7, 7, 0, 30, tzinfo=tz))
    check(fired.count("Будни") == 1, "not on a Saturday")
    e.tick(datetime.datetime(2026, 11, 2, 7, 10, 30, tzinfo=tz))
    check("Рассвет" in fired, "the sunrise lamp starts 20 minutes before its time")
    devs[0]["on"] = True
    call_on[0] = True
    e.tick(mon + datetime.timedelta(minutes=1))
    check(fired.count("Дверь") == 1 and fired.count("Созвон") == 1, "the door opened and a call started")


def main():
    engine_check()
    for port in (HA_PORT, AGENT_PORT):
        s = ThreadingHTTPServer(("127.0.0.1", port), Fake)
        threading.Thread(target=s.serve_forever, daemon=True).start()
    data = tempfile.mkdtemp()
    with open(os.path.join(data, "config.json"), "w", encoding="utf-8") as f:
        json.dump({"port": HUB_PORT, "tokens": [{"name": "admin", "token": "admin", "admin": True},
                                                {"name": "user", "token": "user"}],
                   "ha": {"url": f"127.0.0.1:{HA_PORT}", "token": "good"},
                   "pcs": [{"id": "pc", "name": "", "host": "127.0.0.1", "port": AGENT_PORT, "token": "abc"}],
                   "nas": {"paths": [data]},
                   "scenes": [{"id": "s", "label": "Тест", "steps": [{"device": "ha:switch.kettle", "action": "on"},
                                                                    {"pc": "pc", "macro": "lock"}]}]}, f)
    hub = subprocess.Popen([sys.executable, os.path.join(os.path.dirname(os.path.abspath(__file__)), "deskdash_hub.py")],
                           env={**os.environ, "DESKDASH_HUB_DATA": data})
    try:
        for _ in range(50):
            try:
                if call("/api/ping")[1].get("app") == "deskdash-hub":
                    break
            except OSError:
                time.sleep(0.2)
        time.sleep(1.5)
        check(call("/api/state", token="bad")[0] == 401, "bad token is refused")
        check(call("/api/config")[0] == 403, "settings need an admin token")
        code, st = call("/api/state")
        ids = {d["id"]: d for d in st["devices"]}
        check(code == 200 and ids["ha:light.kitchen"]["brightness"] == 50, "Home Assistant light with brightness")
        check(ids["ha:sensor.temp"]["state"] == "23.4°C", "sensor reading")
        check(ids["pc:pc"]["on"] and "pc:pc:lock" in ids, "computer and its macro as tiles")
        check(st["system"]["disks"] and not st["system"]["disks"][0].get("missing"), "NAS disk space")
        check(call("/api/device/ha%3Aswitch.kettle", {"action": "on"})[1]["ok"], "switch turns on")
        check(call("/api/device/pc%3Apc%3Alock", {"action": "run"})[1]["ok"], "PC macro runs")
        check(call("/api/scene/s", {})[1]["ok"], "hub scene starts")
        time.sleep(1)
        check(CALLS.count("/api/services/switch/turn_on") == 2 and CALLS.count("/api/macro/lock") == 2,
              f"scene reached HA and the PC ({CALLS})")
        code, t = call("/api/tokens", {"name": "Телефон"}, token="admin")
        check(code == 200 and call("/api/me", token=t["token"])[1]["name"] == "Телефон", "new access token works")
        with urllib.request.urlopen(f"http://127.0.0.1:{HUB_PORT}/", timeout=5) as r:
            check(b"DeskDash" in r.read(), "web app is served")

        # automations, saved phone settings, photos and the nightly backup
        photos = os.path.join(data, "photos")
        os.makedirs(os.path.join(photos, "trip"))
        with open(os.path.join(photos, "trip", "a.jpg"), "wb") as f:
            f.write(b"\xff\xd8 picture")
        rules = [{"id": "r", "label": "Утро", "when": {"at": "07:00"}, "days": [1, 2, 3, 4, 5],
                  "do": [{"device": "ha:switch.kettle", "action": "on"}]}]
        code, cfg = call("/api/config", {"rules": rules, "location": {"lat": 55.75, "lon": 37.62},
                                         "photos": {"path": photos}}, token="admin")
        check(code == 200 and cfg["rules"][0]["days"] == [1, 2, 3, 4, 5] and cfg["location"]["lat"] == 55.75,
              "automation and location saved")
        n = CALLS.count("/api/services/switch/turn_on")
        check(call("/api/rule/r", {})[1]["ok"], "automation runs on request")
        time.sleep(1)
        check(CALLS.count("/api/services/switch/turn_on") == n + 1, "automation reached Home Assistant")
        backup = {"app": "deskdash", "v": 1, "global": {}, "profiles": {"default": {"theme": {"t": "s", "v": "dark"}}}}
        name = urllib.parse.quote("Телефон Маши")
        check(call("/api/profiles/" + name, backup)[1]["ok"], "phone settings saved on the hub")
        code, got = call("/api/profiles/" + name)
        check(code == 200 and got["backup"]["profiles"]["default"]["theme"]["v"] == "dark", "and given back")
        check(call("/api/profiles/x", {"app": "other"})[0] == 400, "only DeskDash backups are kept")
        code, ph = call("/api/photos")
        check(ph["photos"] == ["trip/a.jpg"], "photos listed")
        with urllib.request.urlopen(f"http://127.0.0.1:{HUB_PORT}/api/photo?f=trip/a.jpg&token=user", timeout=5) as r:
            check(r.read() == b"\xff\xd8 picture", "photo served")
        try:
            urllib.request.urlopen(f"http://127.0.0.1:{HUB_PORT}/api/photo?f=../config.json&token=user", timeout=5)
            check(False, "no files outside the photo folder")
        except urllib.error.HTTPError as e:
            check(e.code == 404, "no files outside the photo folder")
        code, b = call("/api/backup", {}, token="admin")
        check(code == 200 and b["ok"] and os.path.isfile(os.path.join(b["path"], "config.json"))
              and os.path.isdir(os.path.join(b["path"], "profiles")), "backup to the NAS folder")
        print("hub smoke test passed")
    finally:
        hub.terminate()


if __name__ == "__main__":
    main()
