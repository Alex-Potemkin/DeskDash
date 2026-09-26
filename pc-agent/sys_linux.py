"""Linux side of the agent: key presses, power actions, stats (standard library + common CLI tools).

Volume goes through wpctl (PipeWire) / pactl (PulseAudio) / amixer, media keys through playerctl,
other key combos through xdotool on X11 and wtype or ydotool on Wayland.
"""
import glob
import os
import shutil
import subprocess
import threading
import time

SPOTIFY_COMMAND = "spotify"
SCREENSHOT_KEYS = "printscreen"

MODS = {"ctrl": "ctrl", "control": "ctrl", "shift": "shift", "alt": "alt", "win": "super", "super": "super"}
NAMED = {  # X keysyms, understood by xdotool and wtype -k
    "esc": "Escape", "tab": "Tab", "enter": "Return", "space": "space", "backspace": "BackSpace",
    "delete": "Delete", "insert": "Insert", "home": "Home", "end": "End", "pgup": "Prior", "pgdn": "Next",
    "left": "Left", "up": "Up", "right": "Right", "down": "Down", "printscreen": "Print",
}
MEDIA = {"volume_up", "volume_down", "volume_mute", "media_play_pause", "media_next", "media_prev", "media_stop"}

# Linux input event codes for ydotool
_EV = {"ctrl": 29, "shift": 42, "alt": 56, "super": 125, "esc": 1, "tab": 15, "enter": 28, "space": 57,
       "backspace": 14, "delete": 111, "insert": 110, "home": 102, "end": 107, "pgup": 104, "pgdn": 109,
       "left": 105, "up": 103, "right": 106, "down": 108, "printscreen": 99}
_EV.update({c: i for c, i in zip("qwertyuiop", range(16, 26))})
_EV.update({c: i for c, i in zip("asdfghjkl", range(30, 39))})
_EV.update({c: i for c, i in zip("zxcvbnm", range(44, 51))})
_EV.update({str(d): 1 + d for d in range(1, 10)})
_EV["0"] = 11
_EV.update({f"f{n}": 58 + n for n in range(1, 11)})
_EV.update({"f11": 87, "f12": 88})


def _have(cmd):
    return shutil.which(cmd) is not None


def _wayland():
    return os.environ.get("XDG_SESSION_TYPE") == "wayland" or bool(os.environ.get("WAYLAND_DISPLAY"))


def _ok(args, timeout=5):
    try:
        return subprocess.run(args, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=timeout).returncode == 0
    except (OSError, subprocess.TimeoutExpired):
        return False


def _first(*commands):
    """Runs the first command whose program exists and succeeds; True if one did."""
    return any(_have(c[0]) and _ok(c) for c in commands)


# ---------------------------------------------------------------- keys

def check_key(name):
    """Raises ValueError for a key name that press() can't send."""
    n = name.strip().lower()
    if n in MODS or n in NAMED or n in MEDIA:
        return
    if len(n) == 1 and n.isascii() and n.isalnum():
        return
    if n.startswith("f") and n[1:].isdigit() and 1 <= int(n[1:]) <= 24:
        return
    raise ValueError(f"unknown key: {name}")


def _media(key):
    step = {"volume_up": "5%+", "volume_down": "5%-"}
    if key in step:
        up = key == "volume_up"
        done = _first(["wpctl", "set-volume", "-l", "1.0", "@DEFAULT_AUDIO_SINK@", step[key]],
                      ["pactl", "set-sink-volume", "@DEFAULT_SINK@", "+5%" if up else "-5%"],
                      ["amixer", "-q", "sset", "Master", step[key]])
    elif key == "volume_mute":
        done = _first(["wpctl", "set-mute", "@DEFAULT_AUDIO_SINK@", "toggle"],
                      ["pactl", "set-sink-mute", "@DEFAULT_SINK@", "toggle"],
                      ["amixer", "-q", "sset", "Master", "toggle"])
    else:
        action = {"media_play_pause": "play-pause", "media_next": "next", "media_prev": "previous",
                  "media_stop": "stop"}[key]
        xkey = {"media_play_pause": "XF86AudioPlay", "media_next": "XF86AudioNext", "media_prev": "XF86AudioPrev",
                "media_stop": "XF86AudioStop"}[key]
        done = _first(["playerctl", action], ["xdotool", "key", xkey])
    if not done:
        raise RuntimeError("нет wpctl/pactl/playerctl для звука и медиа-клавиш")


def _keysym(n):
    if n in NAMED:
        return NAMED[n]
    if n.startswith("f") and n[1:].isdigit():
        return n.upper()
    return n


def press(combo, repeat=1):
    parts = [p.strip().lower() for p in combo.split("+") if p.strip()]
    for p in parts:
        check_key(p)
    repeat = max(1, repeat)
    if len(parts) == 1 and parts[0] in MEDIA:
        for _ in range(repeat):
            _media(parts[0])
        return
    mods = [MODS[p] for p in parts if p in MODS]
    keys = [p for p in parts if p not in MODS]
    if not keys:  # a bare modifier such as "win" opens the launcher
        keys = [parts[-1]]
        mods = mods[:-1]

    x11 = not _wayland() and os.environ.get("DISPLAY")
    if (x11 or not (_have("wtype") or _have("ydotool"))) and _have("xdotool"):
        chord = "+".join(mods + [_keysym(k) if k not in MODS else MODS[k] for k in keys])
        if _ok(["xdotool", "key", "--clearmodifiers"] + [chord] * repeat):
            return
    if _have("wtype"):
        wmods = ["logo" if m == "super" else m for m in mods]
        args = ["wtype"]
        for _ in range(repeat):
            args += sum((["-M", m] for m in wmods), [])
            args += sum((["-k", "Super_L" if k in ("win", "super") else _keysym(k)] for k in keys), [])
            args += sum((["-m", m] for m in reversed(wmods)), [])
        if _ok(args):
            return
    if _have("ydotool"):
        codes = [_EV[m] for m in mods] + [_EV.get(MODS.get(k, k)) for k in keys]
        if None not in codes:
            seq = [f"{c}:1" for c in codes] + [f"{c}:0" for c in reversed(codes)]
            if _ok(["ydotool", "key"] + seq * repeat):
                return
    raise RuntimeError("для сочетаний клавиш установи xdotool (X11) или wtype / ydotool (Wayland)")


# ---------------------------------------------------------------- system actions

def _monitor_off():
    desktop = os.environ.get("XDG_CURRENT_DESKTOP", "").lower()
    attempts = []
    if not _wayland():
        attempts.append(["xset", "dpms", "force", "off"])
    if "kde" in desktop:
        attempts.append(["kscreen-doctor", "--dpms", "off"])
    if "gnome" in desktop:
        attempts.append(["busctl", "--user", "set-property", "org.gnome.Mutter.DisplayConfig",
                         "/org/gnome/Mutter/DisplayConfig", "org.gnome.Mutter.DisplayConfig", "PowerSaveMode", "i", "1"])
    attempts += [["wlopm", "--off", "*"], ["xset", "dpms", "force", "off"]]
    if not _first(*attempts):
        raise RuntimeError("не удалось погасить экран (нужен xset, kscreen-doctor, wlopm или GNOME)")


def system_action(name):
    if name == "lock":
        if not _first(["loginctl", "lock-session"], ["xdg-screensaver", "lock"]):
            raise RuntimeError("не удалось заблокировать сеанс")
    elif name == "monitor_off":
        _monitor_off()
    elif name in ("sleep", "hibernate"):
        # let the HTTP reply go out first
        threading.Timer(1.0, _ok, (["systemctl", "suspend" if name == "sleep" else "hibernate"],)).start()
    elif name == "shutdown":
        subprocess.Popen(["systemctl", "poweroff"])
    elif name == "restart":
        subprocess.Popen(["systemctl", "reboot"])
    else:
        raise ValueError(f"unknown system action: {name}")


def open_target(target):
    subprocess.Popen(["xdg-open", target], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                     start_new_session=True)


def run_command(cmd):
    subprocess.Popen(cmd, shell=True, cwd=os.path.expanduser("~"), stdout=subprocess.DEVNULL,
                     stderr=subprocess.DEVNULL, start_new_session=True)


# ---------------------------------------------------------------- stats

_cpu = {"percent": 0.0}


def _cpu_times(text=None):
    if text is None:
        with open("/proc/stat") as f:
            text = f.readline()
    v = [int(x) for x in text.split()[1:9]]
    idle = v[3] + v[4]  # idle + iowait
    return idle, sum(v)


def cpu_sampler():
    prev = _cpu_times()
    while True:
        time.sleep(1.5)
        cur = _cpu_times()
        total = cur[1] - prev[1]
        if total > 0:
            _cpu["percent"] = max(0.0, min(100.0, 100.0 * (1 - (cur[0] - prev[0]) / total)))
        prev = cur


def _meminfo(text=None):
    if text is None:
        with open("/proc/meminfo") as f:
            text = f.read()
    kb = {}
    for line in text.splitlines():
        k, _, rest = line.partition(":")
        if rest.strip():
            kb[k] = int(rest.split()[0])
    total = kb.get("MemTotal", 0)
    avail = kb.get("MemAvailable", kb.get("MemFree", 0))
    return total, avail


def _battery():
    for d in sorted(glob.glob("/sys/class/power_supply/BAT*")):
        try:
            with open(os.path.join(d, "capacity")) as f:
                cap = int(f.read().strip())
            with open(os.path.join(d, "status")) as f:
                status = f.read().strip()
            return cap, status in ("Charging", "Full", "Not charging")
        except (OSError, ValueError):
            continue
    return None, False


def stats():
    total, avail = _meminfo()
    bat, charging = _battery()
    try:
        with open("/proc/uptime") as f:
            uptime = int(float(f.read().split()[0]))
    except OSError:
        uptime = 0
    used = total - avail
    return {
        "cpu": round(_cpu["percent"], 1),
        "ram": round(100 * used / total) if total else 0,
        "ram_used_gb": round(used / 2 ** 20, 1),
        "ram_total_gb": round(total / 2 ** 20, 1),
        "battery": bat,
        "charging": charging,
        "uptime": uptime,
    }


def start_background():
    threading.Thread(target=cpu_sampler, daemon=True).start()
