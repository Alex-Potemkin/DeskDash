package com.deskdash;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Yandex Smart Home through the official IoT API (api.iot.yandex.net) with an OAuth token
 * that has the iot:view and iot:control scopes.
 */
final class YandexHome {
    private static final String API = "https://api.iot.yandex.net/v1.0/";
    private static final int TIMEOUT = 10_000;

    private final String token;

    YandexHome(Prefs p) {
        token = p.yaToken();
    }

    static boolean configured(Prefs p) {
        return !p.yaToken().isEmpty();
    }

    /** Implicit flow: Yandex sends the token to the verification_code page, LoginActivity catches it. */
    static String authorizeUrl(String clientId) {
        return "https://oauth.yandex.ru/authorize?response_type=token&client_id=" + HomeAssistant.enc(clientId);
    }

    private String request(String method, String path, String body) throws IOException {
        try {
            String r = Net.http(method, API + path, "Bearer " + token, "application/json", body, TIMEOUT);
            JSONObject o = new JSONObject(r);
            if (!"ok".equals(o.optString("status", "ok"))) throw new IOException(o.optString("message", "Яндекс вернул ошибку"));
            return r;
        } catch (Net.HttpError e) {
            if (e.code == 401 || e.code == 403) throw new IOException("Яндекс не принял токен, войди заново");
            throw e;
        } catch (JSONException e) {
            throw new IOException("странный ответ Яндекса");
        }
    }

    List<HomeDevice> devices() throws IOException, JSONException {
        JSONObject o = new JSONObject(request("GET", "user/info", null));
        Map<String, String> rooms = new HashMap<>();
        JSONArray ra = o.optJSONArray("rooms");
        if (ra != null) {
            for (int i = 0; i < ra.length(); i++) {
                JSONObject r = ra.getJSONObject(i);
                rooms.put(r.optString("id"), r.optString("name"));
            }
        }
        List<HomeDevice> out = new ArrayList<>();
        JSONArray da = o.optJSONArray("devices");
        if (da != null) {
            for (int i = 0; i < da.length(); i++) {
                HomeDevice d = parse(da.getJSONObject(i));
                String room = rooms.get(da.getJSONObject(i).optString("room"));
                if (room != null) d.room = room;
                out.add(d);
            }
        }
        JSONArray sa = o.optJSONArray("scenarios");
        if (sa != null) {
            for (int i = 0; i < sa.length(); i++) {
                JSONObject s = sa.getJSONObject(i);
                HomeDevice d = new HomeDevice();
                d.source = "ya";
                d.nativeId = s.optString("id");
                d.id = "yas:" + d.nativeId;
                d.name = s.optString("name");
                d.room = "Сценарии";
                d.kind = HomeDevice.SCENE;
                d.available = s.optBoolean("is_active", true);
                out.add(d);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- actions

    private void act(HomeDevice d, String type, String instance, Object value) throws IOException, JSONException {
        JSONObject action = new JSONObject().put("type", type)
                .put("state", new JSONObject().put("instance", instance).put("value", value));
        JSONObject dev = new JSONObject().put("id", d.nativeId).put("actions", new JSONArray().put(action));
        String r = request("POST", "devices/actions", new JSONObject().put("devices", new JSONArray().put(dev)).toString());
        // per-capability result: {"devices":[{"capabilities":[{"state":{"action_result":{"status":"ERROR",...}}}]}]}
        JSONArray rd = new JSONObject(r).optJSONArray("devices");
        if (rd == null) return;
        for (int i = 0; i < rd.length(); i++) {
            JSONArray caps = rd.getJSONObject(i).optJSONArray("capabilities");
            if (caps == null) continue;
            for (int j = 0; j < caps.length(); j++) {
                JSONObject st = caps.getJSONObject(j).optJSONObject("state");
                JSONObject res = st == null ? null : st.optJSONObject("action_result");
                if (res != null && "ERROR".equals(res.optString("status"))) {
                    String msg = res.optString("error_message", res.optString("error_code", "ошибка"));
                    throw new IOException(d.name + ": " + msg);
                }
            }
        }
    }

    void setOn(HomeDevice d, boolean on) throws IOException, JSONException {
        act(d, "devices.capabilities.on_off", "on", on);
    }

    void run(HomeDevice d) throws IOException {
        request("POST", "scenarios/" + d.nativeId + "/actions", "{}");
    }

    void brightness(HomeDevice d, int pct) throws IOException, JSONException {
        act(d, "devices.capabilities.range", "brightness", Math.max(1, pct));
    }

    void colorTemp(HomeDevice d, int kelvin) throws IOException, JSONException {
        act(d, "devices.capabilities.color_setting", "temperature_k", kelvin);
    }

    void color(HomeDevice d, int rgb) throws IOException, JSONException {
        int c = rgb & 0xFFFFFF;
        if ("hsv".equals(d.colorModel)) {
            float[] hsv = new float[3];
            android.graphics.Color.colorToHSV(rgb, hsv);
            act(d, "devices.capabilities.color_setting", "hsv", new JSONObject()
                    .put("h", Math.round(hsv[0])).put("s", Math.round(hsv[1] * 100)).put("v", Math.round(hsv[2] * 100)));
        } else {
            act(d, "devices.capabilities.color_setting", "rgb", c);
        }
    }

    void temperature(HomeDevice d, double t) throws IOException, JSONException {
        act(d, "devices.capabilities.range", "temperature", t);
    }

    // ---------------------------------------------------------------- parsing

    static HomeDevice parse(JSONObject o) {
        HomeDevice d = new HomeDevice();
        d.source = "ya";
        d.nativeId = o.optString("id");
        d.id = "ya:" + d.nativeId;
        d.name = o.optString("name");
        String type = o.optString("type");
        if ("offline".equals(o.optString("state"))) d.available = false;

        JSONArray caps = o.optJSONArray("capabilities");
        if (caps != null) {
            for (int i = 0; i < caps.length(); i++) {
                JSONObject c = caps.optJSONObject(i);
                if (c == null) continue;
                String ct = c.optString("type");
                JSONObject p = c.optJSONObject("parameters");
                if (p == null) p = new JSONObject();
                JSONObject st = c.optJSONObject("state");
                switch (ct) {
                    case "devices.capabilities.on_off":
                        d.canToggle = true;
                        if (st != null) d.on = st.optBoolean("value", false);
                        break;
                    case "devices.capabilities.range": {
                        String inst = p.optString("instance");
                        if ("brightness".equals(inst)) {
                            d.dimmable = true;
                            if (st != null && st.has("value")) d.brightness = (int) Math.round(st.optDouble("value"));
                        } else if ("temperature".equals(inst)) {
                            JSONObject r = p.optJSONObject("range");
                            if (r != null) {
                                d.tMin = r.optDouble("min", 16);
                                d.tMax = r.optDouble("max", 30);
                                d.tStep = r.optDouble("precision", 1);
                            }
                            d.target = st != null && st.has("value") ? st.optDouble("value") : (d.tMin + d.tMax) / 2;
                        }
                        break;
                    }
                    case "devices.capabilities.color_setting": {
                        JSONObject tk = p.optJSONObject("temperature_k");
                        if (tk != null) {
                            d.colorTemp = true;
                            d.minK = tk.optInt("min", 2700);
                            d.maxK = tk.optInt("max", 6500);
                        }
                        String model = p.optString("color_model", "");
                        if (!model.isEmpty()) {
                            d.color = true;
                            d.colorModel = model;
                        }
                        if (st != null && "temperature_k".equals(st.optString("instance"))) d.tempK = st.optInt("value", -1);
                        break;
                    }
                    default:
                        break;
                }
            }
        }

        StringBuilder readings = new StringBuilder();
        JSONArray props = o.optJSONArray("properties");
        if (props != null) {
            for (int i = 0; i < props.length(); i++) {
                JSONObject pr = props.optJSONObject(i);
                if (pr == null) continue;
                JSONObject p = pr.optJSONObject("parameters");
                JSONObject st = pr.optJSONObject("state");
                if (p == null || st == null || !st.has("value")) continue;
                String inst = p.optString("instance"), text;
                if ("devices.properties.float".equals(pr.optString("type"))) {
                    double v = st.optDouble("value");
                    if ("temperature".equals(inst)) d.current = v;
                    if ("battery_level".equals(inst) && readings.length() > 0) continue;
                    text = HomeDevice.num(v) + unit(p.optString("unit"));
                } else {
                    text = event(inst, st.optString("value"));
                }
                if (text.isEmpty()) continue;
                if (readings.length() > 0) readings.append(" · ");
                readings.append(text);
            }
        }

        if (type.startsWith("devices.types.light")) d.kind = HomeDevice.LIGHT;
        else if (type.startsWith("devices.types.thermostat")) d.kind = HomeDevice.CLIMATE;
        else if (type.startsWith("devices.types.openable")) d.kind = HomeDevice.COVER;
        else if (type.startsWith("devices.types.media_device")) d.kind = HomeDevice.MEDIA;
        else if (type.startsWith("devices.types.sensor") || !d.canToggle && readings.length() > 0) d.kind = HomeDevice.SENSOR;
        else d.kind = HomeDevice.SWITCH;

        if (d.kind == HomeDevice.MEDIA) d.state = d.on ? "Вкл" : "Выкл";
        else if (d.kind != HomeDevice.CLIMATE) d.state = readings.toString();
        else if (Double.isNaN(d.target) && readings.length() > 0) d.state = readings.toString();
        return d;
    }

    private static String unit(String u) {
        switch (u) {
            case "unit.temperature.celsius": return "°";
            case "unit.percent": return "%";
            case "unit.ppm": return " ppm";
            case "unit.watt": return " Вт";
            case "unit.volt": return " В";
            case "unit.ampere": return " А";
            case "unit.kilowatt_hour": return " кВт·ч";
            case "unit.illumination.lux": return " лк";
            case "unit.pressure.mmhg": return " мм";
            case "unit.pressure.pascal": return " Па";
            case "unit.density.mcg_m3": return " мкг/м³";
            default: return "";
        }
    }

    private static String event(String instance, String v) {
        switch (v) {
            case "opened": return "Открыто";
            case "closed": return "Закрыто";
            case "detected": return "motion".equals(instance) ? "Движение" : "Обнаружено!";
            case "not_detected": return "motion".equals(instance) ? "Тихо" : "Норма";
            case "leak": return "Протечка!";
            case "dry": return "Сухо";
            case "low": return "battery_level".equals(instance) ? "Батарея садится" : "Мало";
            case "normal": return "battery_level".equals(instance) ? "" : "Норма";
            case "high": return "Много";
            case "empty": return "Пусто";
            default: return "";
        }
    }
}
