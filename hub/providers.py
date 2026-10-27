"""Home Assistant and Yandex Smart Home for the hub.

Both turn their devices into the same dicts the Android app uses for tiles (fields of HomeDevice.java),
so the phone, the dashboard and the web page all show one list.
"""
import colorsys
import json
import math
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

SWITCH, LIGHT, CLIMATE, SENSOR, SCENE, COVER, LOCK, FAN, MEDIA = range(9)

# Same IndieAuth client id as the Android app, so a refresh token imported from the phone keeps working here
HA_CLIENT = "https://deskdash.local/"


class ProviderError(Exception):
    pass


def http(method, url, auth=None, body=None, content_type="application/json", timeout=8):
    data = None
    headers = {}
    if auth:
        headers["Authorization"] = auth
    if body is not None:
        data = body if isinstance(body, bytes) else (body if isinstance(body, str) else json.dumps(body)).encode("utf-8")
        headers["Content-Type"] = content_type
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.read().decode("utf-8")
    except urllib.error.HTTPError as e:
        text = " ".join(e.read().decode("utf-8", "replace").split())
        raise HttpError(e.code, text[:120]) from None
    except urllib.error.URLError as e:
        raise ProviderError(friendly(e.reason)) from None
    except (TimeoutError, OSError) as e:
        raise ProviderError(friendly(e)) from None


class HttpError(ProviderError):
    def __init__(self, code, text):
        super().__init__(f"HTTP {code}" + (f": {text}" if text else ""))
        self.code = code


def friendly(e):
    s = str(e)
    if isinstance(e, TimeoutError) or "timed out" in s:
        return "не отвечает"
    if "refused" in s.lower():
        return "нет связи"
    if "Name or service not known" in s or "getaddrinfo" in s or "nodename" in s:
        return "адрес не найден"
    return s or e.__class__.__name__


def device(id, source, native, name, kind=SWITCH):
    return {"id": id, "source": source, "nativeId": native, "name": name, "room": "", "state": "",
            "kind": kind, "available": True, "canToggle": False, "on": False, "dimmable": False,
            "colorTemp": False, "color": False, "brightness": -1, "tempK": -1, "minK": 2700, "maxK": 6500,
            "colorModel": "", "current": None, "target": None, "tMin": 16, "tMax": 30, "tStep": 0.5}


def num(v):
    if v == int(v) and abs(v) < 1e9:
        return str(int(v))
    return f"{v:.0f}" if abs(v) >= 100 else f"{v:.1f}"


def deg(v):
    return num(v) + "°"


def _float(v):
    try:
        f = float(v)
        return None if math.isnan(f) else f
    except (TypeError, ValueError):
        return None


# ---------------------------------------------------------------- Home Assistant

class HomeAssistant:
    def __init__(self, cfg, save):
        """cfg: {"url", "token"} or {"url", "refresh"}; save(fn) stores refreshed access tokens."""
        self.cfg = cfg
        self.save = save
        self.base = cfg.get("url", "").strip().rstrip("/")
        if self.base and not self.base.startswith(("http://", "https://")):
            self.base = "http://" + self.base
        self._lock = threading.Lock()
        self._areas = {}
        self._areas_at = 0

    @staticmethod
    def configured(cfg):
        return bool(cfg.get("url")) and bool(cfg.get("token") or cfg.get("refresh"))

    def _auth(self):
        if self.cfg.get("token"):
            return "Bearer " + self.cfg["token"]
        with self._lock:
            if self.cfg.get("access") and time.time() < self.cfg.get("expires", 0) - 60:
                return "Bearer " + self.cfg["access"]
            body = urllib.parse.urlencode({"grant_type": "refresh_token", "refresh_token": self.cfg["refresh"],
                                           "client_id": HA_CLIENT})
            try:
                o = json.loads(http("POST", self.base + "/auth/token", body=body,
                                    content_type="application/x-www-form-urlencoded"))
            except HttpError as e:
                raise ProviderError("вход в Home Assistant устарел, войди заново" if e.code == 400 else str(e))
            access, expires = o["access_token"], time.time() + o.get("expires_in", 1800)
            self.cfg.update(access=access, expires=expires)
            self.save(lambda c: c["ha"].update(access=access, expires=expires))
            return "Bearer " + access

    def _get(self, path):
        try:
            return http("GET", self.base + path, self._auth())
        except HttpError as e:
            raise ProviderError("Home Assistant не принял токен" if e.code == 401 else str(e))

    def _call(self, domain, service, data):
        try:
            http("POST", f"{self.base}/api/services/{domain}/{service}", self._auth(), data)
        except HttpError as e:
            raise ProviderError("Home Assistant не принял токен" if e.code == 401 else str(e))

    def areas(self):
        """entity_id → area name, refreshed every 10 minutes, best effort."""
        if time.time() - self._areas_at < 600:
            return self._areas
        self._areas_at = time.time()
        tpl = "{% for s in states %}{{ s.entity_id }}\t{{ area_name(s.entity_id) or '' }}\n{% endfor %}"
        try:
            r = http("POST", self.base + "/api/template", self._auth(), {"template": tpl})
            self._areas = {a.strip(): b.strip() for a, _, b in (l.partition("\t") for l in r.split("\n")) if b.strip()}
        except ProviderError:
            pass
        return self._areas

    def devices(self):
        states = json.loads(self._get("/api/states"))
        areas = self.areas()
        out = []
        for s in states:
            d = ha_parse(s)
            if d:
                d["room"] = areas.get(d["nativeId"], "")
                out.append(d)
        return out

    # actions
    def set_on(self, d, on):
        eid = d["nativeId"]
        dom = eid.split(".", 1)[0]
        service = {"cover": ("open_cover", "close_cover"), "lock": ("lock", "unlock"),
                   "media_player": ("media_play", "media_pause")}.get(dom, ("turn_on", "turn_off"))
        self._call(dom, service[0] if on else service[1], {"entity_id": eid})

    def run(self, d):
        dom = d["nativeId"].split(".", 1)[0]
        self._call(dom, "press" if dom.endswith("button") else "turn_on", {"entity_id": d["nativeId"]})

    def brightness(self, d, pct):
        self._call("light", "turn_on", {"entity_id": d["nativeId"], "brightness_pct": int(pct)})

    def color_temp(self, d, k):
        self._call("light", "turn_on", {"entity_id": d["nativeId"], "color_temp_kelvin": int(k)})

    def color(self, d, rgb):
        rgb = int(rgb)
        self._call("light", "turn_on", {"entity_id": d["nativeId"], "rgb_color": [(rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255]})

    def temperature(self, d, t):
        self._call("climate", "set_temperature", {"entity_id": d["nativeId"], "temperature": float(t)})


HVAC = {"heat": "обогрев", "cool": "охлаждение", "auto": "авто", "heat_cool": "авто", "dry": "осушение",
        "fan_only": "вентиляция", "off": "выкл"}
WEATHER = {"sunny": "ясно", "clear-night": "ясно", "partlycloudy": "переменная облачность", "cloudy": "облачно",
           "rainy": "дождь", "pouring": "дождь", "snowy": "снег", "snowy-rainy": "снег", "fog": "туман",
           "lightning": "гроза", "lightning-rainy": "гроза", "windy": "ветер", "windy-variant": "ветер"}


def binary(cls, on):
    if cls in ("door", "window", "garage_door", "opening"):
        return "Открыто" if on else "Закрыто"
    if cls in ("motion", "occupancy"):
        return "Движение" if on else "Тихо"
    if cls == "presence":
        return "Дома" if on else "Никого"
    if cls == "moisture":
        return "Протечка!" if on else "Сухо"
    if cls in ("smoke", "gas", "carbon_monoxide"):
        return "Тревога!" if on else "Норма"
    if cls == "battery":
        return "Батарея садится" if on else "Батарея в норме"
    if cls == "connectivity":
        return "В сети" if on else "Не в сети"
    return "Вкл" if on else "Выкл"


def ha_parse(s):
    eid = s.get("entity_id", "")
    if "." not in eid:
        return None
    dom, st = eid.split(".", 1)[0], s.get("state", "")
    a = s.get("attributes") or {}
    d = device("ha:" + eid, "ha", eid, a.get("friendly_name") or eid)
    d["available"] = st != "unavailable"
    d["on"] = st == "on"
    if dom == "light":
        d.update(kind=LIGHT, canToggle=True)
        if a.get("brightness") is not None:
            d["brightness"] = round(a["brightness"] * 100 / 255)
        for m in a.get("supported_color_modes") or []:
            if m != "onoff":
                d["dimmable"] = True
            if m == "color_temp":
                d["colorTemp"] = True
            if m.startswith(("hs", "xy", "rgb")):
                d["color"] = True
        d["minK"] = a.get("min_color_temp_kelvin") or 2700
        d["maxK"] = a.get("max_color_temp_kelvin") or 6500
        if a.get("color_temp_kelvin") is not None:
            d["tempK"] = a["color_temp_kelvin"]
    elif dom in ("switch", "input_boolean", "automation", "humidifier"):
        d.update(kind=SWITCH, canToggle=True)
    elif dom == "fan":
        d.update(kind=FAN, canToggle=True)
    elif dom == "cover":
        d.update(kind=COVER, canToggle=True, on=st in ("open", "opening"))
    elif dom == "lock":
        d.update(kind=LOCK, canToggle=True, on=st in ("locked", "locking"))
    elif dom == "climate":
        d.update(kind=CLIMATE, on=st != "off", canToggle="off" in (a.get("hvac_modes") or []),
                 current=_float(a.get("current_temperature")), target=_float(a.get("temperature")),
                 tMin=a.get("min_temp", 7), tMax=a.get("max_temp", 35), tStep=a.get("target_temp_step", 0.5),
                 state=HVAC.get(st, st))
    elif dom == "media_player":
        d.update(kind=MEDIA, canToggle=True, on=st == "playing",
                 state="Выключен" if st == "off" else a.get("media_title") or "")
    elif dom in ("scene", "script", "button", "input_button"):
        d["kind"] = SCENE
    elif dom == "sensor":
        d["kind"] = SENSOR
        unit = a.get("unit_of_measurement") or ""
        v = _float(st)
        if v is not None:
            d["state"] = num(v) + ("" if not unit else unit if unit.startswith("°") else " " + unit)
        else:
            d["state"] = "" if st == "unknown" else st
    elif dom == "binary_sensor":
        d.update(kind=SENSOR, state=binary(a.get("device_class", ""), d["on"]))
    elif dom == "person":
        d.update(kind=SENSOR, state={"home": "Дома", "not_home": "Не дома"}.get(st, st))
    elif dom == "weather":
        t = _float(a.get("temperature"))
        d.update(kind=SENSOR, state=(deg(t) + " · " if t is not None else "") + WEATHER.get(st, st))
    else:
        return None
    return d


# ---------------------------------------------------------------- Yandex

YA_API = "https://api.iot.yandex.net/v1.0/"
YA_UNITS = {"unit.temperature.celsius": "°", "unit.percent": "%", "unit.ppm": " ppm", "unit.watt": " Вт",
            "unit.volt": " В", "unit.ampere": " А", "unit.kilowatt_hour": " кВт·ч", "unit.illumination.lux": " лк",
            "unit.pressure.mmhg": " мм", "unit.pressure.pascal": " Па", "unit.density.mcg_m3": " мкг/м³"}


def ya_event(instance, v):
    return {"opened": "Открыто", "closed": "Закрыто",
            "detected": "Движение" if instance == "motion" else "Обнаружено!",
            "not_detected": "Тихо" if instance == "motion" else "Норма",
            "leak": "Протечка!", "dry": "Сухо",
            "low": "Батарея садится" if instance == "battery_level" else "Мало",
            "normal": "" if instance == "battery_level" else "Норма",
            "high": "Много", "empty": "Пусто"}.get(v, "")


class Yandex:
    def __init__(self, cfg):
        self.token = cfg.get("token", "").strip()

    @staticmethod
    def configured(cfg):
        return bool(cfg.get("token", "").strip())

    def _req(self, method, path, body=None):
        try:
            r = json.loads(http(method, YA_API + path, "Bearer " + self.token, body, timeout=10))
        except HttpError as e:
            if e.code in (401, 403):
                raise ProviderError("Яндекс не принял токен, войди заново")
            raise
        except ValueError:
            raise ProviderError("странный ответ Яндекса")
        if r.get("status", "ok") != "ok":
            raise ProviderError(r.get("message", "Яндекс вернул ошибку"))
        return r

    def devices(self):
        o = self._req("GET", "user/info")
        rooms = {r.get("id"): r.get("name") for r in o.get("rooms") or []}
        out = []
        for raw in o.get("devices") or []:
            d = ya_parse(raw)
            d["room"] = rooms.get(raw.get("room"), "") or ""
            out.append(d)
        for s in o.get("scenarios") or []:
            d = device("yas:" + s.get("id", ""), "ya", s.get("id", ""), s.get("name", ""), SCENE)
            d.update(room="Сценарии", available=s.get("is_active", True))
            out.append(d)
        return out

    def _act(self, d, type, instance, value):
        body = {"devices": [{"id": d["nativeId"], "actions": [{"type": type, "state": {"instance": instance, "value": value}}]}]}
        r = self._req("POST", "devices/actions", body)
        for rd in r.get("devices") or []:
            for c in rd.get("capabilities") or []:
                res = (c.get("state") or {}).get("action_result") or {}
                if res.get("status") == "ERROR":
                    raise ProviderError(f"{d['name']}: {res.get('error_message') or res.get('error_code') or 'ошибка'}")

    def set_on(self, d, on):
        self._act(d, "devices.capabilities.on_off", "on", bool(on))

    def run(self, d):
        self._req("POST", f"scenarios/{d['nativeId']}/actions", {})

    def brightness(self, d, pct):
        self._act(d, "devices.capabilities.range", "brightness", max(1, int(pct)))

    def color_temp(self, d, k):
        self._act(d, "devices.capabilities.color_setting", "temperature_k", int(k))

    def color(self, d, rgb):
        rgb = int(rgb) & 0xFFFFFF
        if d.get("colorModel") == "hsv":
            h, s, v = colorsys.rgb_to_hsv(((rgb >> 16) & 255) / 255, ((rgb >> 8) & 255) / 255, (rgb & 255) / 255)
            self._act(d, "devices.capabilities.color_setting", "hsv", {"h": round(h * 360), "s": round(s * 100), "v": round(v * 100)})
        else:
            self._act(d, "devices.capabilities.color_setting", "rgb", rgb)

    def temperature(self, d, t):
        self._act(d, "devices.capabilities.range", "temperature", float(t))


def ya_parse(o):
    d = device("ya:" + o.get("id", ""), "ya", o.get("id", ""), o.get("name", ""))
    if o.get("state") == "offline":
        d["available"] = False
    for c in o.get("capabilities") or []:
        ct, p, st = c.get("type"), c.get("parameters") or {}, c.get("state") or {}
        if ct == "devices.capabilities.on_off":
            d["canToggle"] = True
            d["on"] = bool(st.get("value", False))
        elif ct == "devices.capabilities.range":
            if p.get("instance") == "brightness":
                d["dimmable"] = True
                if "value" in st:
                    d["brightness"] = round(st["value"])
            elif p.get("instance") == "temperature":
                r = p.get("range") or {}
                d.update(tMin=r.get("min", 16), tMax=r.get("max", 30), tStep=r.get("precision", 1))
                d["target"] = st["value"] if "value" in st else (d["tMin"] + d["tMax"]) / 2
        elif ct == "devices.capabilities.color_setting":
            tk = p.get("temperature_k")
            if tk:
                d.update(colorTemp=True, minK=tk.get("min", 2700), maxK=tk.get("max", 6500))
            if p.get("color_model"):
                d.update(color=True, colorModel=p["color_model"])
            if st.get("instance") == "temperature_k":
                d["tempK"] = st.get("value", -1)
    readings = []
    for pr in o.get("properties") or []:
        p, st = pr.get("parameters") or {}, pr.get("state") or {}
        if "value" not in st:
            continue
        inst = p.get("instance", "")
        if pr.get("type") == "devices.properties.float":
            v = float(st["value"])
            if inst == "temperature":
                d["current"] = v
            if inst == "battery_level" and readings:
                continue
            text = num(v) + YA_UNITS.get(p.get("unit", ""), "")
        else:
            text = ya_event(inst, str(st["value"]))
        if text:
            readings.append(text)
    t = o.get("type", "")
    if t.startswith("devices.types.light"):
        d["kind"] = LIGHT
    elif t.startswith("devices.types.thermostat"):
        d["kind"] = CLIMATE
    elif t.startswith("devices.types.openable"):
        d["kind"] = COVER
    elif t.startswith("devices.types.media_device"):
        d["kind"] = MEDIA
    elif t.startswith("devices.types.sensor") or (not d["canToggle"] and readings):
        d["kind"] = SENSOR
    if d["kind"] == MEDIA:
        d["state"] = "Вкл" if d["on"] else "Выкл"
    elif d["kind"] != CLIMATE or (d["target"] is None and readings):
        d["state"] = " · ".join(readings)
    return d
