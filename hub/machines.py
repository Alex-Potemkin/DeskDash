"""Computers with the DeskDash agent, and the Raspberry Pi / NAS the hub runs on."""
import json
import os
import shutil
import socket
import time
import urllib.parse

from providers import ProviderError, http

AGENT_DISCOVERY_PORT = 8766


# ---------------------------------------------------------------- PCs

class Pc:
    """One deskdash_agent.py; cfg is {"id", "name", "host", "port", "token", "mac"}."""

    def __init__(self, cfg):
        self.cfg = cfg
        self.base = f"http://{cfg['host']}:{int(cfg.get('port') or 8765)}"

    def _req(self, method, path, timeout=3):
        sep = "&" if "?" in path else "?"
        url = self.base + path + sep + "token=" + urllib.parse.quote(str(self.cfg.get("token", "")))
        try:
            return json.loads(http(method, url, body=b"" if method == "POST" else None, timeout=timeout))
        except ValueError:
            raise ProviderError("странный ответ агента")

    def poll(self):
        """{"online", "name", "macros", "stats", "mac", "calls"}; never raises."""
        try:
            info = self._req("GET", "/api/info")
            stats = self._req("GET", "/api/stats")
            calls = 0
            if "calls" in str(info.get("features", "")):
                try:
                    calls = len(self._req("GET", "/api/calls").get("calls") or [])
                except ProviderError:
                    pass
            return {"online": True, "name": info.get("name") or self.cfg.get("name", ""),
                    "macros": info.get("macros") or [], "stats": stats, "mac": info.get("mac") or "", "calls": calls}
        except ProviderError as e:
            return {"online": False, "error": str(e)}

    def macro(self, mid):
        r = self._req("POST", "/api/macro/" + urllib.parse.quote(mid, safe=""), timeout=8)
        if not r.get("ok"):
            raise ProviderError(r.get("error") or "макрос не выполнился")


def wake(mac):
    """Wake-on-LAN from the Pi: it is always on and usually wired, so this works even from outside the house."""
    h = "".join(c for c in mac if c in "0123456789abcdefABCDEF")
    if len(h) != 12:
        raise ProviderError("неверный MAC-адрес")
    pkt = b"\xff" * 6 + bytes.fromhex(h) * 16
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        for port in (9, 7):
            s.sendto(pkt, ("255.255.255.255", port))


def discover_agents(timeout=1.5):
    """[{host, port, name}] of every DeskDash agent in the local network."""
    out = []
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        s.settimeout(0.3)
        try:
            s.sendto(b"DESKDASH_DISCOVER", ("255.255.255.255", AGENT_DISCOVERY_PORT))
        except OSError:
            return out
        end = time.time() + timeout
        while time.time() < end:
            try:
                data, addr = s.recvfrom(1024)
                o = json.loads(data.decode("utf-8"))
                if all(r["host"] != addr[0] for r in out):
                    out.append({"host": addr[0], "port": o.get("port", 8765), "name": o.get("name") or addr[0]})
            except (socket.timeout, ValueError):
                pass
            except OSError:
                break
    return out


# ---------------------------------------------------------------- the Pi itself

def _read(path):
    try:
        with open(path) as f:
            return f.read().strip()
    except OSError:
        return ""


def _cpu_times():
    parts = _read("/proc/stat").split("\n", 1)[0].split()[1:]
    if not parts:
        return None
    v = [int(x) for x in parts]
    return v[3] + (v[4] if len(v) > 4 else 0), sum(v)


_prev_cpu = [None]


def nas_paths(paths):
    """The configured disk folders; "/mnt/nas/*" stands for every disk mounted there right now (they come and go)."""
    out = []
    for p in paths:
        p = p.get("path") if isinstance(p, dict) else p
        if p and p.endswith("/*"):
            base = p[:-2]
            try:
                out += sorted(os.path.join(base, d) for d in os.listdir(base) if os.path.ismount(os.path.join(base, d)))
            except OSError:
                pass
        elif p:
            out.append(p)
    return out


def system(paths):
    """CPU, temperature, memory, uptime and free space of the NAS disks. Works on Linux; elsewhere what it can."""
    out = {"host": socket.gethostname(), "disks": []}
    cur = _cpu_times()
    if cur and _prev_cpu[0]:
        idle, total = cur[0] - _prev_cpu[0][0], cur[1] - _prev_cpu[0][1]
        if total > 0:
            out["cpu"] = round(100 * (1 - idle / total))
    _prev_cpu[0] = cur
    t = _read("/sys/class/thermal/thermal_zone0/temp")
    if t.isdigit():
        out["temp"] = round(int(t) / 1000, 1)
    mem = {}
    for line in _read("/proc/meminfo").splitlines():
        k, _, v = line.partition(":")
        mem[k] = int(v.split()[0]) * 1024 if v.split() else 0
    if mem.get("MemTotal"):
        out["mem"] = round(100 * (1 - mem.get("MemAvailable", 0) / mem["MemTotal"]))
    up = _read("/proc/uptime").split()
    if up:
        out["uptime"] = int(float(up[0]))
    for p in nas_paths(paths):
        try:
            u = shutil.disk_usage(p)
            out["disks"].append({"path": p, "name": os.path.basename(p.rstrip("/")) or p,
                                 "total": u.total, "used": u.used, "free": u.free})
        except OSError:
            out["disks"].append({"path": p, "name": os.path.basename(p.rstrip("/")) or p, "missing": True})
    return out
