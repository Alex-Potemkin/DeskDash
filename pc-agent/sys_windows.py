"""Windows side of the agent: key presses, power actions, stats (ctypes, standard library only)."""
import ctypes
import ctypes.wintypes as wt
import os
import subprocess
import threading
import time
import winreg

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


# ---------- windows, input, devices (calls and day stats) ----------

class LASTINPUTINFO(ctypes.Structure):
    _fields_ = [("cbSize", wt.UINT), ("dwTime", wt.DWORD)]


user32.GetForegroundWindow.restype = wt.HWND
user32.GetWindowTextLengthW.argtypes = [wt.HWND]
user32.GetWindowTextW.argtypes = [wt.HWND, wt.LPWSTR, ctypes.c_int]
user32.GetWindowThreadProcessId.argtypes = [wt.HWND, ctypes.POINTER(wt.DWORD)]
user32.IsWindowVisible.argtypes = [wt.HWND]
user32.IsIconic.argtypes = [wt.HWND]
user32.ShowWindow.argtypes = [wt.HWND, ctypes.c_int]
user32.SetForegroundWindow.argtypes = [wt.HWND]
user32.GetCursorPos.argtypes = [ctypes.POINTER(wt.POINT)]
kernel32.OpenProcess.restype = wt.HANDLE
kernel32.QueryFullProcessImageNameW.argtypes = [wt.HANDLE, wt.DWORD, wt.LPWSTR, ctypes.POINTER(wt.DWORD)]
kernel32.CloseHandle.argtypes = [wt.HANDLE]
_WNDENUMPROC = ctypes.WINFUNCTYPE(wt.BOOL, wt.HWND, wt.LPARAM)


def idle_seconds():
    """Seconds since the last keyboard or mouse input."""
    li = LASTINPUTINFO()
    li.cbSize = ctypes.sizeof(li)
    if not user32.GetLastInputInfo(ctypes.byref(li)):
        return 0
    return ((kernel32.GetTickCount() - li.dwTime) & 0xFFFFFFFF) / 1000


def cursor():
    """Mouse position in screen pixels."""
    p = wt.POINT()
    user32.GetCursorPos(ctypes.byref(p))
    return p.x, p.y


def _title(hwnd):
    n = user32.GetWindowTextLengthW(hwnd)
    if n <= 0:
        return ""
    buf = ctypes.create_unicode_buffer(n + 1)
    user32.GetWindowTextW(hwnd, buf, n + 1)
    return buf.value


def _exe_of_pid(pid):
    h = kernel32.OpenProcess(0x1000, False, pid)  # PROCESS_QUERY_LIMITED_INFORMATION
    if not h:
        return ""
    try:
        buf = ctypes.create_unicode_buffer(1024)
        size = wt.DWORD(1024)
        if kernel32.QueryFullProcessImageNameW(h, 0, buf, ctypes.byref(size)):
            return os.path.basename(buf.value)
        return ""
    finally:
        kernel32.CloseHandle(h)


def _pid(hwnd):
    pid = wt.DWORD()
    user32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
    return pid.value


def foreground():
    """(exe name, window title) of the active window, ("", "") on the lock screen."""
    hwnd = user32.GetForegroundWindow()
    if not hwnd:
        return "", ""
    return _exe_of_pid(_pid(hwnd)), _title(hwnd)


def windows():
    """Visible top-level windows with a title: [(handle, exe name, title)]."""
    out, exes = [], {}

    def cb(hwnd, _):
        if user32.IsWindowVisible(hwnd):
            t = _title(hwnd)
            if t:
                pid = _pid(hwnd)
                if pid not in exes:
                    exes[pid] = _exe_of_pid(pid)
                out.append((hwnd, exes[pid], t))
        return True
    user32.EnumWindows(_WNDENUMPROC(cb), 0)
    return out


def focus(hwnd):
    """Brings a window to the front. Windows lets only the app with the last input do that,
    so an Alt tap makes this process that app first."""
    if user32.IsIconic(hwnd):
        user32.ShowWindow(hwnd, 9)  # SW_RESTORE
    user32.keybd_event(0x12, 0, 0, 0)
    user32.keybd_event(0x12, 0, KEYUP, 0)
    user32.SetForegroundWindow(hwnd)
    time.sleep(0.25)


_CONSENT = r"Software\Microsoft\Windows\CurrentVersion\CapabilityAccessManager\ConsentStore"


def _in_use(key):
    """Unix start time when the app behind this consent key uses the device now, else None."""
    try:
        start = winreg.QueryValueEx(key, "LastUsedTimeStart")[0]
        stop = winreg.QueryValueEx(key, "LastUsedTimeStop")[0]
    except OSError:
        return None
    return start / 10_000_000 - 11_644_473_600 if start and not stop else None


def _subkeys(key):
    i = 0
    while True:
        try:
            yield winreg.EnumKey(key, i)
        except OSError:
            return
        i += 1


def device_users(device):
    """Apps using "microphone" or "webcam" right now: {app: unix start}. Windows records every use
    for the privacy settings, and a running one has LastUsedTimeStop = 0. Desktop apps are named by
    their exe ("zoom.exe"), Store apps by package family ("msteams_8wekyb3d8bbwe")."""
    out = {}
    try:
        root = winreg.OpenKey(winreg.HKEY_CURRENT_USER, _CONSENT + "\\" + device)
    except OSError:
        return out
    with root:
        for name in list(_subkeys(root)):
            with winreg.OpenKey(root, name) as k:
                if name != "NonPackaged":
                    t = _in_use(k)
                    if t:
                        out[name.lower()] = t
                    continue
                for sub in list(_subkeys(k)):
                    with winreg.OpenKey(k, sub) as kk:
                        t = _in_use(kk)
                    if t:
                        out[sub.rsplit("#", 1)[-1].lower()] = t
    return out


# Core Audio: mute switch of the default microphone (IMMDeviceEnumerator -> IMMDevice -> IAudioEndpointVolume)

class _GUID(ctypes.Structure):
    _fields_ = [("a", wt.DWORD), ("b", wt.WORD), ("c", wt.WORD), ("d", ctypes.c_ubyte * 8)]

    def __init__(self, s):
        super().__init__()
        h = s.replace("-", "")
        self.a, self.b, self.c = int(h[:8], 16), int(h[8:12], 16), int(h[12:16], 16)
        self.d[:] = bytes.fromhex(h[16:])


_ole32 = ctypes.WinDLL("ole32")
_ole32.CoCreateInstance.argtypes = [ctypes.c_void_p, ctypes.c_void_p, wt.DWORD, ctypes.c_void_p, ctypes.c_void_p]
_ole32.CoTaskMemFree.argtypes = [ctypes.c_void_p]
_CLSID_ENUM = _GUID("BCDE0395-E52F-467C-8E3D-C4579291692E")
_IID_ENUM = _GUID("A95664D2-9614-4F35-A746-DE8DB63617E6")
_IID_VOLUME = _GUID("5CDF2C82-841E-4546-9722-0CF74078229A")


def _vtable(obj):
    return ctypes.cast(obj, ctypes.POINTER(ctypes.POINTER(ctypes.c_void_p)))[0]


def _method(obj, index, *argtypes):
    fn = ctypes.WINFUNCTYPE(ctypes.HRESULT, ctypes.c_void_p, *argtypes)(_vtable(obj)[index])
    return lambda *a: fn(obj, *a)


def _release(obj):
    if obj:
        ctypes.WINFUNCTYPE(ctypes.c_ulong, ctypes.c_void_p)(_vtable(obj)[2])(obj)


def _mic_volumes():
    """IAudioEndpointVolume of the default microphones for calls and for everything else
    (usually one and the same device, then it is returned once)."""
    enum = ctypes.c_void_p()
    _ole32.CoCreateInstance(ctypes.byref(_CLSID_ENUM), None, 23, ctypes.byref(_IID_ENUM), ctypes.byref(enum))
    vols, ids = [], set()
    try:
        for role in (2, 0):  # eCommunications, eConsole
            dev = ctypes.c_void_p()
            try:
                _method(enum, 4, ctypes.c_int, ctypes.c_int, ctypes.POINTER(ctypes.c_void_p))(1, role, ctypes.byref(dev))
            except OSError:
                continue  # no microphone
            try:
                pid = ctypes.c_wchar_p()
                _method(dev, 5, ctypes.POINTER(ctypes.c_wchar_p))(ctypes.byref(pid))
                did = pid.value
                _ole32.CoTaskMemFree(pid)
                if did in ids:
                    continue
                ids.add(did)
                vol = ctypes.c_void_p()
                _method(dev, 3, ctypes.POINTER(_GUID), wt.DWORD, ctypes.c_void_p, ctypes.POINTER(ctypes.c_void_p))(
                    ctypes.byref(_IID_VOLUME), 23, None, ctypes.byref(vol))
                vols.append(vol)
            finally:
                _release(dev)
    finally:
        _release(enum)
    return vols


def _with_mic(fn):
    _ole32.CoInitializeEx(None, 0)
    try:
        vols = _mic_volumes()
        try:
            return fn(vols)
        finally:
            for v in vols:
                _release(v)
    finally:
        _ole32.CoUninitialize()


def mic_muted():
    """True / False for the default microphone, None when there is none."""
    def get(vols):
        if not vols:
            return None
        m = wt.BOOL()
        _method(vols[0], 15, ctypes.POINTER(wt.BOOL))(ctypes.byref(m))
        return bool(m.value)
    try:
        return _with_mic(get)
    except OSError:
        return None


def set_mic_muted(on):
    def put(vols):
        if not vols:
            raise RuntimeError("микрофон не найден")
        for v in vols:
            _method(v, 14, wt.BOOL, ctypes.c_void_p)(bool(on), None)
    _with_mic(put)


def start_background():
    threading.Thread(target=cpu_sampler, daemon=True).start()
