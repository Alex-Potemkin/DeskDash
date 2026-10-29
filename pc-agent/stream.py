"""The PC screen and webcam as MJPEG streams for the phone (call view: screen with the webcam on top).

Screen: Pillow's ImageGrab (bundled with DeskDash.exe), the mouse pointer is drawn as a ring.
Webcam: OpenCV when it is installed, otherwise ffmpeg (DirectShow on Windows, V4L2 on Linux).
One capture serves every phone that watches the camera and stops a few seconds after the last one.
"""
import io
import os
import re
import shutil
import subprocess
import threading
import time

try:
    from PIL import Image, ImageDraw, ImageGrab
except ImportError:  # headless agent without Pillow: no screen
    Image = None

try:
    import cv2
except ImportError:
    cv2 = None

if os.name == "nt":
    import sys_windows as sysapi
else:
    import sys_linux as sysapi

NO_WINDOW = 0x08000000 if os.name == "nt" else 0  # CREATE_NO_WINDOW: no console flashing up


def screen_ok():
    return Image is not None


def screen_frame(width=1280, quality=60):
    """One JPEG of the main screen, scaled to the given width."""
    img = ImageGrab.grab()
    if img.mode != "RGB":
        img = img.convert("RGB")
    try:
        pos = sysapi.cursor()
    except Exception:
        pos = None
    if pos and 0 <= pos[0] < img.width and 0 <= pos[1] < img.height:
        r = max(7, img.width // 180)
        d = ImageDraw.Draw(img)
        d.ellipse((pos[0] - r, pos[1] - r, pos[0] + r, pos[1] + r), outline=(0, 0, 0), width=4)
        d.ellipse((pos[0] - r + 2, pos[1] - r + 2, pos[0] + r - 2, pos[1] + r - 2), outline=(255, 255, 255), width=2)
    if img.width > width:
        img = img.resize((width, round(img.height * width / img.width)), Image.BILINEAR)
    out = io.BytesIO()
    img.save(out, "JPEG", quality=quality)
    return out.getvalue()


# ---------------------------------------------------------------- webcam

def _ffmpeg():
    return shutil.which("ffmpeg")


def _dshow_camera(ffmpeg):
    """Name of the first DirectShow camera, as ffmpeg lists them."""
    try:
        r = subprocess.run([ffmpeg, "-hide_banner", "-list_devices", "true", "-f", "dshow", "-i", "dummy"],
                           capture_output=True, text=True, timeout=10, creationflags=NO_WINDOW,
                           encoding="utf-8", errors="replace")
    except (OSError, subprocess.TimeoutExpired):
        return None
    m = re.search(r'"([^"]+)"\s+\(video\)', r.stderr)
    return m.group(1) if m else None


def camera_backend():
    if cv2 is not None:
        return "opencv"
    if _ffmpeg():
        return "ffmpeg"
    return None


class Camera:
    """Shared webcam capture: start() for every viewer, stop() when it leaves."""

    def __init__(self):
        self.cond = threading.Condition()
        self.frame, self.seq, self.viewers, self.error = None, 0, 0, ""
        self.running = False
        self.proc = None
        self._gone = None

    def start(self):
        with self.cond:
            self.viewers += 1
            if not self.running:
                self.running, self.error, self.frame = True, "", None
                threading.Thread(target=self._run, name="webcam", daemon=True).start()

    def stop(self):
        with self.cond:
            self.viewers -= 1

    def wait(self, last_seq, timeout=5):
        """Next frame after last_seq: (seq, jpeg) or (seq, None) on timeout; raises on capture errors."""
        with self.cond:
            self.cond.wait_for(lambda: self.seq != last_seq or self.error or not self.running, timeout)
            if self.error:
                raise RuntimeError(self.error)
            return self.seq, self.frame if self.seq != last_seq else None

    def _put(self, jpeg):
        with self.cond:
            self.frame, self.seq = jpeg, self.seq + 1
            self.cond.notify_all()

    def _fail(self, msg):
        with self.cond:
            self.error, self.running = msg, False
            self.cond.notify_all()

    def _idle(self):
        """True after the last viewer has been gone for a few seconds: time to release the camera."""
        with self.cond:
            if self.viewers > 0:
                self._gone = None
                return False
            now = time.monotonic()
            self._gone = self._gone or now
            if now - self._gone > 4:
                self.running, self._gone = False, None
                self.cond.notify_all()
                return True
            return False

    def _run(self):
        try:
            if cv2 is not None:
                self._run_cv2()
            elif _ffmpeg():
                self._run_ffmpeg()
            else:
                self._fail("нет камеры: поставь ffmpeg или opencv-python")
        except Exception as e:
            self._fail(f"камера: {e}")

    def _run_cv2(self):
        cap = cv2.VideoCapture(0, cv2.CAP_DSHOW) if os.name == "nt" else cv2.VideoCapture(0)
        try:
            if not cap.isOpened():
                return self._fail("камера занята или не найдена")
            while not self._idle():
                ok, img = cap.read()
                if not ok:
                    return self._fail("камера перестала отдавать кадры")
                h, w = img.shape[:2]
                if w > 640:
                    img = cv2.resize(img, (640, round(h * 640 / w)))
                ok, buf = cv2.imencode(".jpg", img, [cv2.IMWRITE_JPEG_QUALITY, 70])
                if ok:
                    self._put(buf.tobytes())
        finally:
            cap.release()

    def _run_ffmpeg(self):
        ff = _ffmpeg()
        if os.name == "nt":
            name = _dshow_camera(ff)
            if not name:
                return self._fail("камера не найдена")
            src = ["-f", "dshow", "-i", f"video={name}"]
        else:
            src = ["-f", "v4l2", "-i", "/dev/video0"]
        self.proc = subprocess.Popen([ff, "-hide_banner", "-loglevel", "error"] + src +
                                     ["-vf", "scale='min(640,iw)':-2", "-r", "15", "-f", "mjpeg", "-q:v", "6", "pipe:1"],
                                     stdout=subprocess.PIPE, stderr=subprocess.PIPE, creationflags=NO_WINDOW)
        buf = b""
        try:
            while not self._idle():
                chunk = self.proc.stdout.read1(65536) if hasattr(self.proc.stdout, "read1") else self.proc.stdout.read(4096)
                if not chunk:
                    err = self.proc.stderr.read().decode("utf-8", "replace").strip().splitlines()
                    return self._fail("камера занята или недоступна" + (f": {err[-1]}" if err else ""))
                buf += chunk
                while True:
                    s = buf.find(b"\xff\xd8")
                    e = buf.find(b"\xff\xd9", s + 2) if s >= 0 else -1
                    if s < 0 or e < 0:
                        break
                    self._put(buf[s:e + 2])
                    buf = buf[e + 2:]
                if len(buf) > 4_000_000:
                    buf = b""
        finally:
            self.proc.kill()
            self.proc = None


CAMERA = Camera()


def info(cfg):
    allowed = cfg.get("stream", True)
    return {"allowed": bool(allowed), "screen": allowed and screen_ok(), "camera": bool(allowed and camera_backend())}
