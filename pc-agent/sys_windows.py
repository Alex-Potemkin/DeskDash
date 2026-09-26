"""Windows side of the agent: key presses, power actions, stats (ctypes, standard library only)."""
import ctypes
import ctypes.wintypes as wt
import os
import subprocess
import threading
import time

user32 = ctypes.WinDLL("user32", use_last_error=True)
kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
kernel32.GetTickCount64.restype = ctypes.c_ulonglong

SPOTIFY_COMMAND = "start spotify:"
SCREENSHOT_KEYS = "win+shift+s"


# ---------- keyboard ----------

KEYUP, EXTENDED = 0x2, 0x1
VK = {
    "ctrl": 0x11, "control": 0x11, "shift": 0x10, "alt": 0x12, "win": 0x5B,
    "esc": 0x1B, "tab": 0x09, "enter": 0x0D, "space": 0x20, "backspace": 0x08,
    "delete": 0x2E, "insert": 0x2D, "home": 0x24, "end": 0x23, "pgup": 0x21, "pgdn": 0x22,
    "left": 0x25, "up": 0x26, "right": 0x27, "down": 0x28, "printscreen": 0x2C,
    "volume_mute": 0xAD, "volume_down": 0xAE, "volume_up": 0xAF,
    "media_next": 0xB0, "media_prev": 0xB1, "media_stop": 0xB2, "media_play_pause": 0xB3,
}
EXTENDED_KEYS = {0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27, 0x28, 0x2D, 0x2E, 0x5B, 0x5C} | set(range(0xAD, 0xB4))


def vk_of(name):
    name = name.strip().lower()
    if name in VK:
        return VK[name]
    if len(name) == 1 and name.isalnum():
        return ord(name.upper())
    if name.startswith("f") and name[1:].isdigit() and 1 <= int(name[1:]) <= 24:
        return 0x6F + int(name[1:])
    raise ValueError(f"unknown key: {name}")


def press(combo, repeat=1):
    vks = [vk_of(k) for k in combo.split("+")]
    for _ in range(max(1, repeat)):
        for vk in vks:
            user32.keybd_event(vk, 0, EXTENDED if vk in EXTENDED_KEYS else 0, 0)
        for vk in reversed(vks):
            user32.keybd_event(vk, 0, (EXTENDED if vk in EXTENDED_KEYS else 0) | KEYUP, 0)
        time.sleep(0.03)


# ---------- actions ----------

def suspend(hibernate):
    ctypes.WinDLL("powrprof").SetSuspendState(hibernate, True, False)


def system_action(name):
    if name == "lock":
        user32.LockWorkStation()
    elif name == "monitor_off":
        user32.PostMessageW(0xFFFF, 0x0112, 0xF170, 2)  # HWND_BROADCAST, WM_SYSCOMMAND, SC_MONITORPOWER
    elif name == "sleep":
        threading.Timer(1.0, suspend, (False,)).start()  # let the HTTP reply go out first
    elif name == "hibernate":
        threading.Timer(1.0, suspend, (True,)).start()
    elif name == "shutdown":
        subprocess.Popen("shutdown /s /t 5", shell=True)
    elif name == "restart":
        subprocess.Popen("shutdown /r /t 5", shell=True)
    else:
        raise ValueError(f"unknown system action: {name}")


# ---------- stats ----------

class FILETIME(ctypes.Structure):
    _fields_ = [("lo", wt.DWORD), ("hi", wt.DWORD)]


class MEMSTAT(ctypes.Structure):
    _fields_ = [("dwLength", wt.DWORD), ("dwMemoryLoad", wt.DWORD)] + [
        (n, ctypes.c_ulonglong) for n in (
            "ullTotalPhys", "ullAvailPhys", "ullTotalPageFile", "ullAvailPageFile",
            "ullTotalVirtual", "ullAvailVirtual", "ullAvailExtendedVirtual")
    ]


class POWER(ctypes.Structure):
    _fields_ = [("ACLineStatus", ctypes.c_ubyte), ("BatteryFlag", ctypes.c_ubyte),
                ("BatteryLifePercent", ctypes.c_ubyte), ("SystemStatusFlag", ctypes.c_ubyte),
                ("BatteryLifeTime", wt.DWORD), ("BatteryFullLifeTime", wt.DWORD)]


_cpu = {"percent": 0.0}


def _sys_times():
    idle, kern, user = FILETIME(), FILETIME(), FILETIME()
    kernel32.GetSystemTimes(ctypes.byref(idle), ctypes.byref(kern), ctypes.byref(user))
    return [(t.hi << 32) | t.lo for t in (idle, kern, user)]


def cpu_sampler():
    prev = _sys_times()
    while True:
        time.sleep(1.5)
        cur = _sys_times()
        idle = cur[0] - prev[0]
        total = (cur[1] - prev[1]) + (cur[2] - prev[2])  # kernel time includes idle
        if total > 0:
            _cpu["percent"] = max(0.0, min(100.0, 100.0 * (1 - idle / total)))
        prev = cur


def stats():
    m = MEMSTAT()
    m.dwLength = ctypes.sizeof(m)
    kernel32.GlobalMemoryStatusEx(ctypes.byref(m))
    p = POWER()
    kernel32.GetSystemPowerStatus(ctypes.byref(p))
    battery = None if p.BatteryFlag == 128 or p.BatteryLifePercent == 255 else p.BatteryLifePercent
    return {
        "cpu": round(_cpu["percent"], 1),
        "ram": m.dwMemoryLoad,
        "ram_used_gb": round((m.ullTotalPhys - m.ullAvailPhys) / 2 ** 30, 1),
        "ram_total_gb": round(m.ullTotalPhys / 2 ** 30, 1),
        "battery": battery,
        "charging": p.ACLineStatus == 1,
        "uptime": kernel32.GetTickCount64() // 1000,
    }


def check_key(name):
    """Raises ValueError for a key name that press() can't send."""
    vk_of(name)


def open_target(target):
    os.startfile(target)


def run_command(cmd):
    subprocess.Popen(cmd, shell=True, cwd=os.path.expanduser("~"))


def start_background():
    threading.Thread(target=cpu_sampler, daemon=True).start()
