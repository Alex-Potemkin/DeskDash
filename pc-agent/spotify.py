"""Spotify Web API over PKCE: log in from the PC browser, list Connect devices, move playback.

The login redirect lands on the agent's own HTTP server (http://127.0.0.1:<port>/spotify/callback),
so no secret is needed. The refresh token is stored in config.json, the access token only in memory.
"""
import base64
import hashlib
import json
import secrets
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

AUTH_URL = "https://accounts.spotify.com/authorize"
TOKEN_URL = "https://accounts.spotify.com/api/token"
API = "https://api.spotify.com/v1"
SCOPES = "user-read-playback-state user-modify-playback-state"
CALLBACK_PATH = "/spotify/callback"
DASHBOARD_URL = "https://developer.spotify.com/dashboard"

_get_cfg = None
_update_cfg = None
_lock = threading.Lock()
_pending = {}  # state -> code_verifier
_access = {"token": None, "expires": 0.0}


class SpotifyError(Exception):
    pass


def init(get_cfg, update_cfg):
    global _get_cfg, _update_cfg
    _get_cfg, _update_cfg = get_cfg, update_cfg


def _sp():
    return _get_cfg().get("spotify", {})


def redirect_uri():
    return f"http://127.0.0.1:{_get_cfg()['port']}{CALLBACK_PATH}"


def status():
    s = _sp()
    return {"configured": bool(s.get("client_id")), "logged_in": bool(s.get("refresh_token")),
            "user": s.get("user", "")}


def set_client_id(cid):
    cid = cid.strip()

    def upd(c):
        s = c.setdefault("spotify", {})
        if s.get("client_id") != cid:
            s.pop("refresh_token", None)
            s.pop("user", None)
        s["client_id"] = cid

    _update_cfg(upd)
    _access.update(token=None, expires=0)


def logout():
    def upd(c):
        s = c.setdefault("spotify", {})
        s.pop("refresh_token", None)
        s.pop("user", None)

    _update_cfg(upd)
    _access.update(token=None, expires=0)


def _b64(b):
    return base64.urlsafe_b64encode(b).rstrip(b"=").decode()


def login_url():
    cid = _sp().get("client_id")
    if not cid:
        raise SpotifyError("Сначала укажи Client ID")
    verifier = _b64(secrets.token_bytes(48))
    state = secrets.token_urlsafe(12)
    _pending[state] = verifier
    return AUTH_URL + "?" + urllib.parse.urlencode({
        "client_id": cid, "response_type": "code", "redirect_uri": redirect_uri(), "scope": SCOPES,
        "state": state, "code_challenge_method": "S256",
        "code_challenge": _b64(hashlib.sha256(verifier.encode()).digest()),
    })


def _post_token(form):
    req = urllib.request.Request(TOKEN_URL, data=urllib.parse.urlencode(form).encode(),
                                 headers={"Content-Type": "application/x-www-form-urlencoded"})
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            return json.load(r)
    except urllib.error.HTTPError as e:
        try:
            msg = json.load(e).get("error_description") or e.reason
        except Exception:  # noqa: BLE001
            msg = e.reason
        raise SpotifyError(f"Spotify отклонил вход: {msg}") from e
    except OSError as e:
        raise SpotifyError("Нет связи со Spotify") from e


def _store_tokens(tok):
    _access.update(token=tok["access_token"], expires=time.time() + int(tok.get("expires_in", 3600)) - 60)
    if tok.get("refresh_token"):
        _update_cfg(lambda c: c.setdefault("spotify", {}).__setitem__("refresh_token", tok["refresh_token"]))


def handle_callback(query):
    """Returns (ok, message) for the page shown in the browser after login."""
    q = urllib.parse.parse_qs(query)
    if "error" in q:
        return False, "Вход отменён"
    verifier = _pending.pop(q.get("state", [""])[0], None)
    if not verifier or "code" not in q:
        return False, "Ссылка устарела, нажми «Войти» в DeskDash ещё раз"
    try:
        with _lock:
            _store_tokens(_post_token({
                "grant_type": "authorization_code", "code": q["code"][0], "redirect_uri": redirect_uri(),
                "client_id": _sp()["client_id"], "code_verifier": verifier,
            }))
        me = api("GET", "/me")
        name = me.get("display_name") or me.get("id", "")
        _update_cfg(lambda c: c.setdefault("spotify", {}).__setitem__("user", name))
        return True, f"Spotify подключён: {name}"
    except SpotifyError as e:
        return False, str(e)


def _token():
    with _lock:
        if _access["token"] and time.time() < _access["expires"]:
            return _access["token"]
        s = _sp()
        if not s.get("refresh_token"):
            raise SpotifyError("Spotify не подключён. Войди в DeskDash на ПК")
        _store_tokens(_post_token({"grant_type": "refresh_token", "refresh_token": s["refresh_token"],
                                   "client_id": s["client_id"]}))
        return _access["token"]


def api(method, path, body=None, retry=True):
    data = json.dumps(body).encode() if body is not None else (b"" if method in ("POST", "PUT") else None)
    req = urllib.request.Request(API + path, data=data, method=method, headers={
        "Authorization": f"Bearer {_token()}", "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            raw = r.read()
            return json.loads(raw) if raw else {}
    except urllib.error.HTTPError as e:
        if e.code == 401 and retry:
            _access.update(token=None, expires=0)
            return api(method, path, body, retry=False)
        try:
            err = json.load(e).get("error", {})
        except Exception:  # noqa: BLE001
            err = {}
        reason = err.get("reason", "") if isinstance(err, dict) else ""
        if e.code == 403 and reason == "PREMIUM_REQUIRED" or e.code == 403 and "premium" in str(err).lower():
            raise SpotifyError("Для переключения нужен Spotify Premium") from e
        if e.code == 404:
            raise SpotifyError("Устройство недоступно. Открой на нём Spotify") from e
        if e.code == 429:
            raise SpotifyError("Spotify просит подождать, попробуй через минуту") from e
        msg = err.get("message") if isinstance(err, dict) else None
        raise SpotifyError(f"Ошибка Spotify {e.code}: {msg or e.reason}") from e
    except OSError as e:
        raise SpotifyError("Нет связи со Spotify") from e


def devices():
    out = []
    for d in api("GET", "/me/player/devices").get("devices", []):
        if d.get("is_restricted") or not d.get("id"):
            continue
        out.append({"id": d["id"], "name": d.get("name", "?"), "type": d.get("type", "Unknown"),
                    "active": bool(d.get("is_active")), "volume": d.get("volume_percent")})
    return out


def now_playing():
    """Current track with cover URL, or {} when nothing plays anywhere."""
    p = api("GET", "/me/player")
    item = p.get("item") or {}
    if not item:
        return {}
    images = (item.get("album") or {}).get("images") or []
    dev = p.get("device") or {}
    return {"title": item.get("name", ""), "artist": ", ".join(a.get("name", "") for a in item.get("artists", [])),
            "image": images[0]["url"] if images else "", "playing": bool(p.get("is_playing")),
            "device": dev.get("name", ""), "device_type": dev.get("type", "")}


def transfer(device_id, play=True):
    api("PUT", "/me/player", {"device_ids": [device_id], "play": play})


COMMANDS = {"play": ("PUT", "/me/player/play"), "pause": ("PUT", "/me/player/pause"),
            "next": ("POST", "/me/player/next"), "prev": ("POST", "/me/player/previous")}


def command(name):
    if name not in COMMANDS:
        raise SpotifyError(f"Неизвестная команда {name}")
    method, path = COMMANDS[name]
    api(method, path)
