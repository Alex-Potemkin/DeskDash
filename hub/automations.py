"""Automations of the hub: they run on the Pi, so they work while every screen is off.

A rule is {"id", "label", "on", "when", "days", "do", "dawn"}:
  when  {"at": "07:00"}                      every day (or on "days": ISO weekdays 1..7) at a time
        {"sun": "sunset"|"sunrise", "offset": minutes}   by the sun at config["location"]
        {"device": id, "to": "on"|"off"}     when a device turns on or off (a door, a motion sensor)
        {"call": "start"|"end"}              when a call starts or ends on any PC with DeskDash
  do    steps, as in a hub scene (device actions, PC macros, wake a PC, pauses)
  dawn  {"device": id, "minutes": 20}         a lamp that goes from 1 % to full, ending at the rule's time

The nightly backup copies the hub's config and the phones' saved settings into a folder on the NAS.
Standard library only.
"""
import datetime
import math
import os
import shutil
import threading
import time

TICK_S = 20
LATE_S = 300  # a missed moment (the Pi was busy or restarting) still fires within five minutes
BACKUP_AT, BACKUP_KEEP = "03:30", 14


def sun_times(lat, lon, day):
    """(sunrise, sunset) as aware local datetimes for a date, or (None, None) in polar day or night (NOAA formula)."""
    g = 2 * math.pi / 365 * (day.timetuple().tm_yday - 1)
    eq = 229.18 * (0.000075 + 0.001868 * math.cos(g) - 0.032077 * math.sin(g)
                   - 0.014615 * math.cos(2 * g) - 0.040849 * math.sin(2 * g))
    decl = (0.006918 - 0.399912 * math.cos(g) + 0.070257 * math.sin(g) - 0.006758 * math.cos(2 * g)
            + 0.000907 * math.sin(2 * g) - 0.002697 * math.cos(3 * g) + 0.00148 * math.sin(3 * g))
    la = math.radians(lat)
    x = math.cos(math.radians(90.833)) / (math.cos(la) * math.cos(decl)) - math.tan(la) * math.tan(decl)
    if abs(x) > 1:
        return None, None
    ha = math.degrees(math.acos(x))
    midnight = datetime.datetime(day.year, day.month, day.day, tzinfo=datetime.timezone.utc)

    def at(minutes):
        return (midnight + datetime.timedelta(minutes=minutes)).astimezone()

    return at(720 - 4 * (lon + ha) - eq), at(720 - 4 * (lon - ha) - eq)


class Engine:
    """Fires the rules. hub: config(), act(did, action, value), steps(list), devices(), calls_on(), log(msg)."""

    def __init__(self, config, act, steps, devices, calls_on, log, data_dir):
        self.config, self.act, self.steps, self.devices, self.calls_on, self.log = config, act, steps, devices, calls_on, log
        self.data_dir = data_dir
        self.fired = {}       # rule id -> the moment it last fired for (so it fires once)
        self.states = {}      # device id -> last seen on/off
        self.call_was = None
        self.backup_day = ""

    def start(self):
        threading.Thread(target=self._loop, daemon=True).start()

    def _loop(self):
        while True:
            try:
                self.tick(datetime.datetime.now().astimezone())
            except Exception as e:  # noqa: BLE001 — one bad rule must not stop the others
                self.log(f"автоматизации: {e}")
            time.sleep(TICK_S)

    # ---------------------------------------------------------- timing

    def moment(self, rule, now):
        """Today's moment of a timed rule (the dawn starts that many minutes earlier), or None."""
        w = rule.get("when") or {}
        if "at" in w:
            h, m = (int(x) for x in str(w["at"]).split(":")[:2])
            t = now.replace(hour=h, minute=m, second=0, microsecond=0)
        elif "sun" in w:
            loc = self.config().get("location") or {}
            if "lat" not in loc:
                return None
            rise, sset = sun_times(float(loc["lat"]), float(loc["lon"]), now.date())
            t = rise if w["sun"] == "sunrise" else sset
            if t is None:
                return None
            t = t.replace(second=0, microsecond=0) + datetime.timedelta(minutes=int(w.get("offset") or 0))
        else:
            return None
        dawn = rule.get("dawn") or {}
        if dawn.get("device"):
            t -= datetime.timedelta(minutes=int(dawn.get("minutes") or 20))
        return t

    def tick(self, now):
        rules = [r for r in self.config().get("rules") or [] if r.get("on", True)]
        devs = {d["id"]: d for d in self.devices()}
        call = self.calls_on()
        for r in rules:
            days = r.get("days") or []
            if days and now.isoweekday() not in days:
                continue
            w = r.get("when") or {}
            t = self.moment(r, now)
            if t is not None:
                key = t.isoformat()
                if self.fired.get(r["id"]) != key and 0 <= (now - t).total_seconds() < LATE_S:
                    self.fired[r["id"]] = key
                    self.fire(r)
            elif "device" in w:
                d = devs.get(w["device"])
                if d is None:
                    continue
                was, cur = self.states.get(w["device"]), bool(d.get("on"))
                if was is not None and was != cur and cur == (w.get("to", "on") == "on"):
                    self.fire(r)
            elif "call" in w and self.call_was is not None and call != self.call_was:
                if call == (w["call"] == "start"):
                    self.fire(r)
        for did, d in devs.items():
            self.states[did] = bool(d.get("on"))
        self.call_was = call
        self._backup(now)

    # ---------------------------------------------------------- doing

    def fire(self, rule):
        self.log(f"автоматизация «{rule.get('label', rule['id'])}»")
        dawn = rule.get("dawn") or {}
        if dawn.get("device"):
            threading.Thread(target=self._dawn, args=(dawn["device"], int(dawn.get("minutes") or 20)), daemon=True).start()
        if rule.get("do"):
            threading.Thread(target=self.steps, args=(rule["do"], rule.get("label", "")), daemon=True).start()

    def _dawn(self, did, minutes):
        """From 1 % to full brightness in about 20 steps."""
        n = max(5, min(30, minutes))
        for i in range(n + 1):
            pct = max(1, round(100 * i / n))
            try:
                self.act(did, "brightness", pct)
            except Exception as e:  # noqa: BLE001
                self.log(f"рассвет: {e}")
                return
            time.sleep(minutes * 60 / n)

    def run_now(self, rid):
        r = next((r for r in self.config().get("rules") or [] if r.get("id") == rid), None)
        if r is None:
            raise ValueError("нет такой автоматизации")
        self.fire(r)

    # ---------------------------------------------------------- nightly backup

    def backup_dir(self):
        cfg = self.config()
        if cfg.get("backup_dir"):
            return cfg["backup_dir"]
        for p in (cfg.get("nas") or {}).get("paths") or []:
            if p and p != "/" and os.path.isdir(p):
                return os.path.join(p, "deskdash-backup")
        return None

    def _backup(self, now):
        day = now.strftime("%Y-%m-%d")
        if day == self.backup_day or now.strftime("%H:%M") < BACKUP_AT:
            return
        self.backup_day = day
        try:
            self.backup_now(day)
        except OSError as e:
            self.log(f"резервная копия: {e}")

    def backup_now(self, day=None):
        """Copies config.json and the saved phone settings into <NAS>/deskdash-backup/<date>; keeps two weeks."""
        base = self.backup_dir()
        if not base:
            raise OSError("нет папки NAS для копий (Настройки → NAS)")
        day = day or datetime.date.today().isoformat()
        dst = os.path.join(base, day)
        os.makedirs(dst, exist_ok=True)
        cfg_path = os.path.join(self.data_dir, "config.json")
        if os.path.exists(cfg_path):
            shutil.copy2(cfg_path, os.path.join(dst, "config.json"))
        prof = os.path.join(self.data_dir, "profiles")
        if os.path.isdir(prof):
            shutil.copytree(prof, os.path.join(dst, "profiles"), dirs_exist_ok=True)
        old = sorted(d for d in os.listdir(base) if len(d) == 10 and d[4] == "-")
        for d in old[:-BACKUP_KEEP]:
            shutil.rmtree(os.path.join(base, d), ignore_errors=True)
        self.log(f"резервная копия: {dst}")
        return dst
