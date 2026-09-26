"""Smoke test for Linux CI (run under xvfb-run): agent API, a real key press, and screenshots of every page.

Uses a throw-away config dir, so it never touches a real ~/.config/deskdash.
"""
import json
import os
import sys
import tempfile
import time
import urllib.request

os.environ["XDG_CONFIG_HOME"] = tempfile.mkdtemp(prefix="deskdash-ci-")
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import deskdash_agent as agent  # noqa: E402

SHOTS = os.environ.get("SHOTS_DIR", "ci-shots")
os.makedirs(SHOTS, exist_ok=True)


def get(path, token=None, method="GET"):
    req = urllib.request.Request(f"http://127.0.0.1:{agent.config()['port']}{path}", method=method,
                                 data=b"" if method == "POST" else None)
    if token:
        req.add_header("X-Token", token)
    with urllib.request.urlopen(req, timeout=5) as r:
        return json.load(r)


server = agent.start()
time.sleep(2)
token = agent.config()["token"]
assert get("/api/ping")["app"] == "deskdash"
info = get("/api/info", token)
assert len(info["macros"]) >= 5, info
st = get("/api/stats", token)
assert 0 <= st["ram"] <= 100 and "cpu" in st, st
print("agent ok:", info["name"], st)

# a real key press through xdotool on the virtual display
agent.sysapi.press("shift")
agent.sysapi.press("ctrl+a", 2)
print("keys ok")
for bad in ("ctrll", "f99"):
    try:
        agent.vk_of(bad)
        raise SystemExit(f"{bad} should be rejected")
    except ValueError:
        pass

# the GUI: every page, screenshotted
from PIL import ImageGrab  # noqa: E402

import deskdash_app as da  # noqa: E402

app = da.App(server, None, False)
app.geometry("920x600+0+0")
pages = ["conn", "macros", "spotify", "settings"]


def shot(i):
    if i == len(pages):
        app.editor._add(da.TEMPLATES[1])
        app.after(800, lambda: (grab("macro_new"), done()))
        return
    app.show_page(pages[i])
    app.after(800, lambda: (grab(pages[i]), shot(i + 1)))


def grab(name):
    app.update()
    x, y, w, h = app.winfo_rootx(), app.winfo_rooty(), app.winfo_width(), app.winfo_height()
    ImageGrab.grab(bbox=(x, y, x + w, y + h), xdisplay=os.environ.get("DISPLAY")).save(f"{SHOTS}/{name}.png")
    print("shot", name, flush=True)


result = {}


def done():
    try:
        print("fonts:", da.F.display, "|", da.F.text, "| icons:", da.F.icon, "| tray docked:", app.tray_usable())
        app.on_close()  # no tray on Xvfb: must minimise, not hide
        app.update()
        result["closed"] = app.state()
        result["tray"] = app.tray_usable()
    finally:
        finish()


def finish():
    if app.editor.autosave_job:
        app.editor.after_cancel(app.editor.autosave_job)
    app.editor.saved = app.editor._snapshot()
    if app.tray:
        app.tray.stop()
    server.shutdown()
    app.destroy()


app.after(1500, lambda: shot(0))
app.mainloop()
# Xvfb has no window manager, so iconify may stay "normal"; what matters is that the window isn't hidden
print("after close:", result)
ok = result.get("closed") != "withdrawn" or result.get("tray")
print("gui ok" if ok else "gui FAILED", flush=True)
sys.stdout.flush()
os._exit(0 if ok else 1)  # pystray's X11 thread is not a daemon
