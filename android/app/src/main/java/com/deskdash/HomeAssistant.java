package com.deskdash;

import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Home Assistant REST API on the local network. Signs in with the regular HA login page
 * (LoginActivity catches the redirect) or with a long-lived token typed in the settings.
 */
final class HomeAssistant {
    /** Any URL works as an IndieAuth client id as long as the redirect URI is on the same host. */
    static final String CLIENT = "https://deskdash.local/";
    static final String REDIRECT = CLIENT + "auth";
    private static final int TIMEOUT = 5000;

    private final Prefs prefs;
    private final String base;

    HomeAssistant(Prefs p) {
        prefs = p;
        base = p.haUrl();
    }

    static boolean configured(Prefs p) {
        return !p.haUrl().isEmpty() && (!p.haToken().isEmpty() || !p.haRefresh().isEmpty());
    }

    static String authorizeUrl(String base) {
        return base + "/auth/authorize?response_type=code&client_id=" + enc(CLIENT) + "&redirect_uri=" + enc(REDIRECT);
    }

    /** Second half of the login: trades the code from the redirect for tokens and stores them. */
    static void exchangeCode(Prefs p, String code) throws IOException, JSONException {
        String body = "grant_type=authorization_code&code=" + enc(code) + "&client_id=" + enc(CLIENT);
        JSONObject o = new JSONObject(Net.http("POST", p.haUrl() + "/auth/token", null,
                "application/x-www-form-urlencoded", body, TIMEOUT));
        p.setHaAuth(o.getString("access_token"), o.getString("refresh_token"), expiry(o));
    }

    private static long expiry(JSONObject o) {
        return System.currentTimeMillis() + o.optLong("expires_in", 1800) * 1000L;
    }

    private String auth() throws IOException {
        if (!prefs.haToken().isEmpty()) return "Bearer " + prefs.haToken();
        synchronized (HomeAssistant.class) {
            if (!prefs.haAccess().isEmpty() && System.currentTimeMillis() < prefs.haExpires() - 60_000) {
                return "Bearer " + prefs.haAccess();
            }
            String refresh = prefs.haRefresh();
            if (refresh.isEmpty()) throw new IOException("войди в Home Assistant в настройках");
            try {
                String body = "grant_type=refresh_token&refresh_token=" + enc(refresh) + "&client_id=" + enc(CLIENT);
                JSONObject o = new JSONObject(Net.http("POST", base + "/auth/token", null,
                        "application/x-www-form-urlencoded", body, TIMEOUT));
                prefs.setHaAuth(o.getString("access_token"), null, expiry(o));
                return "Bearer " + o.getString("access_token");
            } catch (Net.HttpError e) {
                throw new IOException(e.code == 400 ? "вход в Home Assistant устарел, войди заново" : e.getMessage());
            } catch (JSONException e) {
                throw new IOException("странный ответ Home Assistant");
            }
        }
    }

    List<HomeDevice> states() throws IOException, JSONException {
        JSONArray a = new JSONArray(get("/api/states"));
        List<HomeDevice> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            HomeDevice d = parse(a.getJSONObject(i));
            if (d != null) out.add(d);
        }
        return out;
    }

    /** entity_id → area name, best effort (only used to group the device list). */
    Map<String, String> areas() {
        Map<String, String> m = new HashMap<>();
        try {
            String tpl = "{% for s in states %}{{ s.entity_id }}\t{{ area_name(s.entity_id) or '' }}\n{% endfor %}";
            String r = Net.http("POST", base + "/api/template", auth(), "application/json",
                    new JSONObject().put("template", tpl).toString(), TIMEOUT);
            for (String line : r.split("\n")) {
                int t = line.indexOf('\t');
                if (t > 0 && t < line.length() - 1) m.put(line.substring(0, t).trim(), line.substring(t + 1).trim());
            }
        } catch (Exception ignored) {
            // older HA or no rights: list without rooms
        }
        return m;
    }

    private String get(String path) throws IOException {
        try {
            return Net.http("GET", base + path, auth(), null, null, TIMEOUT);
        } catch (Net.HttpError e) {
            throw new IOException(e.code == 401 ? "Home Assistant не принял токен" : e.getMessage());
        }
    }

    private void call(String domain, String service, JSONObject data) throws IOException {
        try {
            Net.http("POST", base + "/api/services/" + domain + "/" + service, auth(), "application/json", data.toString(), TIMEOUT);
        } catch (Net.HttpError e) {
            throw new IOException(e.code == 401 ? "Home Assistant не принял токен" : e.getMessage());
        }
    }

    private static JSONObject target(HomeDevice d) throws JSONException {
        return new JSONObject().put("entity_id", d.nativeId);
    }

    private static String domain(HomeDevice d) {
        int dot = d.nativeId.indexOf('.');
        return dot < 0 ? "" : d.nativeId.substring(0, dot);
    }

    // ---------------------------------------------------------------- actions

    void setOn(HomeDevice d, boolean on) throws IOException, JSONException {
        String dom = domain(d);
        switch (dom) {
            case "cover": call(dom, on ? "open_cover" : "close_cover", target(d)); break;
            case "lock": call(dom, on ? "lock" : "unlock", target(d)); break;
            case "media_player": call(dom, on ? "media_play" : "media_pause", target(d)); break;
            default: call(dom, on ? "turn_on" : "turn_off", target(d)); break;
        }
    }

    void run(HomeDevice d) throws IOException, JSONException {
        String dom = domain(d);
        call(dom, dom.endsWith("button") ? "press" : "turn_on", target(d));
    }

    void brightness(HomeDevice d, int pct) throws IOException, JSONException {
        call("light", "turn_on", target(d).put("brightness_pct", pct));
    }

    void colorTemp(HomeDevice d, int kelvin) throws IOException, JSONException {
        call("light", "turn_on", target(d).put("color_temp_kelvin", kelvin));
    }

    void color(HomeDevice d, int rgb) throws IOException, JSONException {
        JSONArray c = new JSONArray().put((rgb >> 16) & 255).put((rgb >> 8) & 255).put(rgb & 255);
        call("light", "turn_on", target(d).put("rgb_color", c));
    }

    void temperature(HomeDevice d, double t) throws IOException, JSONException {
        call("climate", "set_temperature", target(d).put("temperature", t));
    }

    // ---------------------------------------------------------------- parsing

    static HomeDevice parse(JSONObject s) {
        String eid = s.optString("entity_id");
        int dot = eid.indexOf('.');
        if (dot < 0) return null;
        String dom = eid.substring(0, dot), st = s.optString("state");
        JSONObject a = s.optJSONObject("attributes");
        if (a == null) a = new JSONObject();
        HomeDevice d = new HomeDevice();
        d.id = "ha:" + eid;
        d.source = "ha";
        d.nativeId = eid;
        d.name = a.optString("friendly_name", eid);
        d.available = !"unavailable".equals(st);
        d.on = "on".equals(st);
        switch (dom) {
            case "light": {
                d.kind = HomeDevice.LIGHT;
                d.canToggle = true;
                if (!a.isNull("brightness")) d.brightness = (int) Math.round(a.optDouble("brightness", 0) * 100 / 255);
                JSONArray modes = a.optJSONArray("supported_color_modes");
                if (modes != null) {
                    for (int i = 0; i < modes.length(); i++) {
                        String m = modes.optString(i);
                        if (!"onoff".equals(m)) d.dimmable = true;
                        if ("color_temp".equals(m)) d.colorTemp = true;
                        if (m.startsWith("hs") || m.startsWith("xy") || m.startsWith("rgb")) d.color = true;
                    }
                }
                d.minK = a.optInt("min_color_temp_kelvin", 2700);
                d.maxK = a.optInt("max_color_temp_kelvin", 6500);
                if (!a.isNull("color_temp_kelvin")) d.tempK = a.optInt("color_temp_kelvin", -1);
                break;
            }
            case "switch":
            case "input_boolean":
            case "automation":
            case "humidifier":
                d.kind = HomeDevice.SWITCH;
                d.canToggle = true;
                break;
            case "fan":
                d.kind = HomeDevice.FAN;
                d.canToggle = true;
                break;
            case "cover":
                d.kind = HomeDevice.COVER;
                d.canToggle = true;
                d.on = "open".equals(st) || "opening".equals(st);
                break;
            case "lock":
                d.kind = HomeDevice.LOCK;
                d.canToggle = true;
                d.on = "locked".equals(st) || "locking".equals(st);
                break;
            case "climate": {
                d.kind = HomeDevice.CLIMATE;
                d.on = !"off".equals(st);
                JSONArray modes = a.optJSONArray("hvac_modes");
                d.canToggle = modes != null && modes.toString().contains("\"off\"");
                d.current = a.optDouble("current_temperature", Double.NaN);
                d.target = a.optDouble("temperature", Double.NaN);
                d.tMin = a.optDouble("min_temp", 7);
                d.tMax = a.optDouble("max_temp", 35);
                d.tStep = a.optDouble("target_temp_step", 0.5);
                d.state = hvac(st);
                break;
            }
            case "media_player":
                d.kind = HomeDevice.MEDIA;
                d.canToggle = true;
                d.on = "playing".equals(st);
                d.state = a.optString("media_title", "");
                if ("off".equals(st)) d.state = "Выключен";
                break;
            case "scene":
            case "script":
            case "button":
            case "input_button":
                d.kind = HomeDevice.SCENE;
                break;
            case "sensor": {
                d.kind = HomeDevice.SENSOR;
                String unit = a.optString("unit_of_measurement", "");
                try {
                    d.state = HomeDevice.num(Double.parseDouble(st)) + (unit.isEmpty() ? "" : unit.startsWith("°") ? unit : " " + unit);
                } catch (NumberFormatException e) {
                    d.state = "unknown".equals(st) ? "" : st;
                }
                break;
            }
            case "binary_sensor":
                d.kind = HomeDevice.SENSOR;
                d.state = binary(a.optString("device_class", ""), d.on);
                break;
            case "person":
                d.kind = HomeDevice.SENSOR;
                d.state = "home".equals(st) ? "Дома" : "not_home".equals(st) ? "Не дома" : st;
                break;
            case "weather":
                d.kind = HomeDevice.SENSOR;
                d.state = (a.has("temperature") ? HomeDevice.deg(a.optDouble("temperature")) + " · " : "") + weather(st);
                break;
            default:
                return null;
        }
        return d;
    }

    private static String hvac(String s) {
        switch (s) {
            case "heat": return "обогрев";
            case "cool": return "охлаждение";
            case "auto": case "heat_cool": return "авто";
            case "dry": return "осушение";
            case "fan_only": return "вентиляция";
            case "off": return "выкл";
            default: return s;
        }
    }

    private static String binary(String cls, boolean on) {
        switch (cls) {
            case "door": case "window": case "garage_door": case "opening": return on ? "Открыто" : "Закрыто";
            case "motion": case "occupancy": return on ? "Движение" : "Тихо";
            case "presence": return on ? "Дома" : "Никого";
            case "moisture": return on ? "Протечка!" : "Сухо";
            case "smoke": case "gas": case "carbon_monoxide": return on ? "Тревога!" : "Норма";
            case "battery": return on ? "Батарея садится" : "Батарея в норме";
            case "connectivity": return on ? "В сети" : "Не в сети";
            default: return on ? "Вкл" : "Выкл";
        }
    }

    private static String weather(String s) {
        switch (s) {
            case "sunny": case "clear-night": return "ясно";
            case "partlycloudy": return "переменная облачность";
            case "cloudy": return "облачно";
            case "rainy": case "pouring": return "дождь";
            case "snowy": case "snowy-rainy": return "снег";
            case "fog": return "туман";
            case "lightning": case "lightning-rainy": return "гроза";
            case "windy": case "windy-variant": return "ветер";
            default: return s;
        }
    }

    static String enc(String s) {
        try {
            return URLEncoder.encode(s, StandardCharsets.UTF_8.name());
        } catch (java.io.UnsupportedEncodingException e) {
            return Uri.encode(s);
        }
    }
}
