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


def main():
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
        print("hub smoke test passed")
    finally:
        hub.terminate()


if __name__ == "__main__":
    main()
