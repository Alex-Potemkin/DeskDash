"""Calls on this PC: which app is in a call right now and buttons for it.

A call is a known call app (Zoom, Teams, Telemost, Discord, Telegram...) or a browser with a call
service open (Meet, Telemost, Jitsi...) that uses the microphone right now: Windows records that for
its privacy settings, Linux has PipeWire/PulseAudio recording streams. The window title tells the
meeting and, for browsers, the service. The mic button is the system mute of the default microphone,
so it works the same for every app and shows the real state; camera, hand, screen share and leave are
the app's own shortcuts sent to its window.
"""
import re
import time

if __import__("os").name == "nt":
    import sys_windows as sysapi
else:
    import sys_linux as sysapi

# apps: parts of the exe / Store package name; titles: parts of a window title (lower case)
PLATFORMS = [
    {"id": "zoom", "name": "Zoom", "color": "#2D8CFF", "apps": ("zoom",),
     "titles": ("zoom meeting", "zoom webinar", "конференция zoom", "zoom workplace"),
     "keys": {"camera": "alt+v", "share": "alt+s", "hand": "alt+y", "leave": "alt+q"}},
    {"id": "teams", "name": "Teams", "color": "#6264A7", "apps": ("teams",), "titles": ("microsoft teams",),
     "keys": {"camera": "ctrl+shift+o", "share": "ctrl+shift+e", "hand": "ctrl+shift+k", "leave": "ctrl+shift+h"}},
    {"id": "meet", "name": "Google Meet", "color": "#00897B", "apps": (), "titles": ("meet – ", "meet - ", "google meet"),
     "keys": {"camera": "ctrl+e", "hand": "ctrl+alt+h", "leave": "ctrl+w"}},
    {"id": "telemost", "name": "Телемост", "color": "#FC3F1D", "apps": ("telemost",), "titles": ("телемост", "telemost"),
     "keys": {}},
    {"id": "jitsi", "name": "Jitsi", "color": "#1D76BA", "apps": ("jitsi",), "titles": ("jitsi meet",),
     "keys": {"camera": "v", "hand": "r"}},
    {"id": "vk", "name": "VK Звонки", "color": "#0077FF", "apps": ("vkcalls", "vk calls"), "titles": ("vk звонки", "vk calls"),
     "keys": {}},
    {"id": "sber", "name": "SberJazz", "color": "#21A038", "apps": ("sberjazz", "jazz"), "titles": ("sberjazz", "salutejazz"),
     "keys": {}},
    {"id": "ktalk", "name": "Контур.Толк", "color": "#1F87EF", "apps": ("ktalk",), "titles": ("контур.толк", "ktalk"),
     "keys": {}},
    {"id": "webex", "name": "Webex", "color": "#00BCEB", "apps": ("webex", "ciscocollabhost", "atmgr"), "titles": ("webex",),
     "keys": {"camera": "ctrl+shift+v", "share": "ctrl+shift+d"}},
    {"id": "skype", "name": "Skype", "color": "#00AFF0", "apps": ("skype",), "titles": ("skype",),
     "keys": {"camera": "ctrl+shift+k"}},
    {"id": "slack", "name": "Slack", "color": "#611F69", "apps": ("slack",), "titles": ("huddle", "созвон"),
     "keys": {}},
    {"id": "discord", "name": "Discord", "color": "#5865F2", "apps": ("discord",), "titles": (),
     "keys": {"deafen": "ctrl+shift+d"}},
    {"id": "telegram", "name": "Telegram", "color": "#229ED9", "apps": ("telegram", "ayugram", "kotatogram"), "titles": (),
     "keys": {}},
    {"id": "whatsapp", "name": "WhatsApp", "color": "#25D366", "apps": ("whatsapp",), "titles": (), "keys": {}},
]
WEB = {"id": "web", "name": "Звонок в браузере", "color": "#8A8A8A", "apps": (), "titles": (), "keys": {}}
BROWSERS = ("chrome", "msedge", "firefox", "browser.exe", "yandex", "opera", "brave", "vivaldi", "chromium", "arc.exe")
BROWSER_SUFFIX = re.compile(r"\s+[-—–]\s+(google chrome|mozilla firefox|microsoft.? edge|яндекс браузер|yandex|opera|"
                            r"brave|vivaldi|chromium)$", re.I)
NAMES = {"camera": "Камера", "share": "Демонстрация", "hand": "Поднять руку", "leave": "Выйти", "deafen": "Звук"}


def _platform_of_app(app):
    for p in PLATFORMS:
        if any(a in app for a in p["apps"]):
            return p
    return None


def _platform_of_title(title):
    low = title.lower()
    for p in PLATFORMS:
        if any(t in low for t in p["titles"]):
            return p
    return None


def _is_browser(app):
    return any(b in app for b in BROWSERS)


def _meeting_name(p, title):
    """What the window title says about the meeting, without the app and browser names."""
    t = BROWSER_SUFFIX.sub("", title.strip())
    for cut in ("| Microsoft Teams", "- Microsoft Teams", "Microsoft Teams", "Zoom Meeting", "Zoom Webinar",
                "Zoom Workplace", "Конференция Zoom", "Google Meet", "Jitsi Meet", "Телемост", "VK Звонки"):
        t = t.replace(cut, "")
    t = re.sub(r"^\s*Meet\s*[-–—]\s*", "", t)
    t = t.strip(" -–—|·")
    return "" if t.lower() in (p["name"].lower(), "zoom", "meet") else t


def current():
    """Calls going on now: [{id, name, color, title, since, camera, actions, window}]."""
    mic = sysapi.device_users("microphone")
    if not mic:
        return []
    cam = sysapi.device_users("webcam")
    try:
        wins = sysapi.windows()
    except Exception:
        wins = []
    calls = {}
    for app, since in mic.items():
        p = _platform_of_app(app)
        win, title = None, ""
        if p is None and _is_browser(app):
            # the browser window whose active tab is a call service
            for w, exe, t in wins:
                if exe.lower() in app or app in exe.lower():
                    q = _platform_of_title(t)
                    if q is not None:
                        p, win, title = q, w, t
                        break
            if p is None:
                p = WEB
                win = next((w for w, exe, _ in wins if exe.lower() in app or app in exe.lower()), None)
        if p is None:
            continue  # games, OBS, recorders: not calls
        if win is None:
            own = [(w, t) for w, exe, t in wins if any(a in exe.lower() for a in p["apps"])]
            # the meeting window, not the app's main window, when there are several
            meeting = [x for x in own if _platform_of_title(x[1]) is p]
            win, title = (meeting or own or [(None, "")])[0]
        if p["id"] in calls:
            continue
        calls[p["id"]] = {
            "id": p["id"], "name": p["name"], "color": p["color"],
            "title": _meeting_name(p, title) if title else "",
            "since": int(min(since, time.time())),
            "camera": app in cam or any(a in c for c in cam for a in p["apps"]),
            "actions": [k for k in ("camera", "share", "hand", "deafen", "leave") if k in p["keys"]],
            "window": win,
        }
    return sorted(calls.values(), key=lambda c: c["since"])


def public(calls):
    return [{k: v for k, v in c.items() if k != "window"} for c in calls]


def state():
    try:
        muted = sysapi.mic_muted()
    except Exception:
        muted = None
    return {"calls": public(current()), "mic_muted": muted, "names": NAMES}


def act(action, call_id=""):
    """mute / unmute / mute_toggle, show, or one of the app shortcuts. Returns a note for the phone."""
    if action in ("mute", "unmute", "mute_toggle"):
        on = {"mute": True, "unmute": False}.get(action)
        if on is None:
            on = not bool(sysapi.mic_muted())
        sysapi.set_mic_muted(on)
        return "Микрофон выключен" if on else "Микрофон включён"
    calls = current()
    call = next((c for c in calls if c["id"] == call_id), calls[0] if calls else None)
    if call is None:
        raise RuntimeError("сейчас нет созвона")
    p = next((x for x in PLATFORMS if x["id"] == call["id"]), WEB)
    if action != "show" and action not in p["keys"]:
        raise RuntimeError(f"{p['name']}: такой кнопки нет")
    if call["window"] is None:
        raise RuntimeError("окно созвона не найдено")
    sysapi.focus(call["window"])
    if action == "show":
        return ""
    sysapi.press(p["keys"][action])
    if action == "leave" and p["id"] == "zoom":
        return "Zoom спросит подтверждение на ПК"
    return ""
