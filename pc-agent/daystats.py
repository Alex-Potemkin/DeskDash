"""Day statistics of this PC for the phone: time at the computer by hour, the apps it went to, calls.

Every 15 s the sampler looks at the time since the last input, the active window and the calls
(calls.py). Two minutes without input still count (reading, watching), a call always counts.
Days are kept for a month in days.json next to config.json.
"""
import json
import os
import threading
import time

import calls

if os.name == "nt":
    import sys_windows as sysapi
else:
    import sys_linux as sysapi

SAMPLE = 15
IDLE_OK = 120
KEEP_DAYS = 31

PRETTY = {
    "chrome": "Chrome", "msedge": "Edge", "firefox": "Firefox", "browser": "Яндекс Браузер", "opera": "Opera",
    "code": "VS Code", "explorer": "Проводник", "telegram": "Telegram", "discord": "Discord", "zoom": "Zoom",
    "ms-teams": "Teams", "teams": "Teams", "spotify": "Spotify", "winword": "Word", "excel": "Excel",
    "powerpnt": "PowerPoint", "outlook": "Outlook", "olk": "Outlook", "devenv": "Visual Studio",
    "idea64": "IntelliJ IDEA", "pycharm64": "PyCharm", "studio64": "Android Studio", "obsidian": "Obsidian",
    "steam": "Steam", "bambu-studio": "Bambu Studio", "windowsterminal": "Терминал", "cmd": "Терминал",
    "powershell": "PowerShell", "claude": "Claude", "kompas": "КОМПАС-3D", "acad": "AutoCAD", "notepad": "Блокнот",
    "notepad++": "Notepad++", "obs64": "OBS", "vlc": "VLC", "figma": "Figma", "slack": "Slack", "notion": "Notion",
    "telemost": "Телемост", "whatsapp": "WhatsApp", "gnome-terminal-server": "Терминал", "konsole": "Терминал",
    "nautilus": "Файлы", "dolphin": "Файлы",
}
SKIP = {"", "lockapp", "searchhost", "shellexperiencehost", "startmenuexperiencehost", "applicationframehost",
        "deskdash", "python", "pythonw"}


def app_name(exe):
    stem = exe.lower()
    for ext in (".exe", ".bin"):
        if stem.endswith(ext):
            stem = stem[:-len(ext)]
    if stem in SKIP:
        return ""
    return PRETTY.get(stem, stem[:1].upper() + stem[1:])


def _empty(day):
    return {"date": day, "active": 0, "hours": [0] * 24, "apps": {}, "calls": [], "first": "", "last": ""}


class Days:
    def __init__(self, path):
        self.path = path
        self.lock = threading.Lock()
        self.days = {}
        self.open = {}  # call id -> its entry in today's "calls"
        try:
            with open(path, encoding="utf-8") as f:
                self.days = json.load(f)
        except (OSError, ValueError):
            self.days = {}

    def start(self):
        threading.Thread(target=self._loop, name="day-stats", daemon=True).start()

    def _loop(self):
        n = 0
        while True:
            time.sleep(SAMPLE)
            try:
                self.tick()
                n += 1
                if n % 4 == 0:
                    self.save()
            except Exception:
                pass  # one bad sample must not stop the stats

    def tick(self, now=None):
        now = time.time() if now is None else now
        lt = time.localtime(now)
        day, hhmm = time.strftime("%Y-%m-%d", lt), time.strftime("%H:%M", lt)
        try:
            live = calls.current()
        except Exception:
            live = []
        idle = sysapi.idle_seconds()
        exe, _title = sysapi.foreground()
        active = bool(live) or idle < IDLE_OK
        with self.lock:
            rec = self.days.setdefault(day, _empty(day))
            if active:
                rec["active"] += SAMPLE
                rec["hours"][lt.tm_hour] += SAMPLE
                rec["first"] = rec["first"] or hhmm
                rec["last"] = hhmm
                app = live[0]["name"] if live else app_name(exe)
                if app:
                    rec["apps"][app] = rec["apps"].get(app, 0) + SAMPLE
            seen = set()
            for c in live:
                seen.add(c["id"])
                entry = self.open.get(c["id"])
                if entry is None or entry not in rec["calls"]:
                    entry = {"id": c["id"], "name": c["name"], "color": c["color"], "title": c["title"],
                             "start": time.strftime("%H:%M", time.localtime(c["since"])), "end": hhmm, "sec": 0}
                    rec["calls"].append(entry)
                    self.open[c["id"]] = entry
                entry["sec"] += SAMPLE
                entry["end"] = hhmm
                if c["title"] and not entry["title"]:
                    entry["title"] = c["title"]
            for cid in list(self.open):
                if cid not in seen:
                    del self.open[cid]
            for old in sorted(self.days)[:-KEEP_DAYS]:
                del self.days[old]

    def save(self):
        with self.lock:
            data = json.dumps(self.days, ensure_ascii=False)
        tmp = self.path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            f.write(data)
        os.replace(tmp, self.path)

    def day(self, date=None):
        """One day for the phone: minutes by hour, top apps, calls."""
        date = date or time.strftime("%Y-%m-%d")
        with self.lock:
            rec = json.loads(json.dumps(self.days.get(date) or _empty(date)))
        apps = sorted(rec["apps"].items(), key=lambda kv: -kv[1])
        return {
            "date": date,
            "active_min": rec["active"] // 60,
            "hours": [round(s / 60) for s in rec["hours"]],
            "apps": [{"name": k, "min": v // 60} for k, v in apps[:8] if v >= 60],
            "calls": [{**c, "min": c["sec"] // 60} for c in rec["calls"]],
            "call_min": sum(c["sec"] for c in rec["calls"]) // 60,
            "first": rec["first"], "last": rec["last"],
        }

    def week(self, n=7):
        """The last n days (oldest first) for the week chart."""
        out = []
        today = time.time()
        with self.lock:
            for i in range(n - 1, -1, -1):
                d = time.strftime("%Y-%m-%d", time.localtime(today - i * 86400))
                rec = self.days.get(d) or _empty(d)
                out.append({"date": d, "active_min": rec["active"] // 60,
                            "call_min": sum(c["sec"] for c in rec["calls"]) // 60})
        return out
