package com.deskdash;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * DeskDash Hub on the Raspberry Pi (hub/deskdash_hub.py). When it is set, the dashboard takes the whole
 * smart home from it: Home Assistant and Yandex devices, hub scenes and the computers' macros.
 */
final class HubClient {
    static final int DISCOVERY_PORT = 8767;
    private static final int TIMEOUT = 5000;

    private final String base, auth, token;

    HubClient(Prefs p) {
        base = p.hubUrl();
        token = p.hubToken();
        auth = "Bearer " + token;
    }

    static boolean configured(Prefs p) {
        return !p.hubUrl().isEmpty() && !p.hubToken().isEmpty();
    }

    private String request(String method, String path, JSONObject body) throws IOException {
        try {
            return Net.http(method, base + path, auth, "application/json", body == null ? null : body.toString(), TIMEOUT);
        } catch (Net.HttpError e) {
            if (e.code == 401) throw new IOException("хаб не принял токен");
            if (e.code == 403) throw new IOException("нужен токен администратора хаба");
            throw e;
        }
    }

    /** Every tile the hub offers, in its order. */
    List<HomeDevice> devices() throws IOException, JSONException {
        JSONArray a = new JSONObject(request("GET", "/api/state", null)).optJSONArray("devices");
        List<HomeDevice> out = new ArrayList<>();
        for (int i = 0; a != null && i < a.length(); i++) out.add(parse(a.getJSONObject(i)));
        return out;
    }

    /** action: on, off, toggle, run, brightness, color_temp, color, temperature; pause, resume, stop, light. */
    void act(HomeDevice d, String action, Object value) throws IOException, JSONException {
        JSONObject body = new JSONObject().put("action", action);
        if (value != null) body.put("value", value);
        JSONObject r = new JSONObject(request("POST", "/api/device/" + HomeAssistant.enc(d.id).replace("+", "%20"), body));
        if (!r.optBoolean("ok")) throw new IOException(r.optString("error", "хаб вернул ошибку"));
    }

    // ---------------------------------------------------------------- saved settings, photos, automations

    /** Keeps a settings file (Backup) on the hub under a name, e.g. the phone's. */
    void saveSettings(String name, String backupJson) throws IOException, JSONException {
        JSONObject r = new JSONObject(request("POST", "/api/profiles/" + HomeAssistant.enc(name).replace("+", "%20"),
                new JSONObject(backupJson)));
        if (!r.optBoolean("ok")) throw new IOException(r.optString("error", "хаб вернул ошибку"));
    }

    /** {name, saved at (ms)} of the settings files kept on the hub. */
    List<String[]> savedSettings() throws IOException, JSONException {
        JSONArray a = new JSONObject(request("GET", "/api/profiles", null)).optJSONArray("profiles");
        List<String[]> out = new ArrayList<>();
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i);
            out.add(new String[]{o.optString("name"), String.valueOf(o.optLong("at") * 1000)});
        }
        return out;
    }

    String loadSettings(String name) throws IOException, JSONException {
        return new JSONObject(request("GET", "/api/profiles/" + HomeAssistant.enc(name).replace("+", "%20"), null))
                .getJSONObject("backup").toString();
    }

    /** Photos of the folder set on the hub, shuffled. */
    List<String> photos() throws IOException, JSONException {
        JSONArray a = new JSONObject(request("GET", "/api/photos", null)).optJSONArray("photos");
        List<String> out = new ArrayList<>();
        for (int i = 0; a != null && i < a.length(); i++) out.add(a.getString(i));
        return out;
    }

    String photoUrl(String rel) {
        return base + "/api/photo?f=" + HomeAssistant.enc(rel).replace("+", "%20") + "&token=" + HomeAssistant.enc(token);
    }

    /**
     * Puts the phone's automations on the hub (rules with ids "phone-…" replace the earlier ones, the hub's own
     * stay) and the place for sunset rules. Needs an admin token.
     */
    void pushRules(JSONArray rules, double lat, double lon) throws IOException, JSONException {
        JSONObject cfg = new JSONObject(request("GET", "/api/config", null));
        JSONArray all = new JSONArray();
        JSONArray old = cfg.optJSONArray("rules");
        for (int i = 0; old != null && i < old.length(); i++) {
            JSONObject r = old.getJSONObject(i);
            if (!r.optString("id").startsWith("phone-")) all.put(r);
        }
        for (int i = 0; i < rules.length(); i++) all.put(rules.get(i));
        JSONObject b = new JSONObject().put("rules", all);
        if (!Double.isNaN(lat)) b.put("location", new JSONObject().put("lat", lat).put("lon", lon));
        JSONObject r = new JSONObject(request("POST", "/api/config", b));
        if (!r.optBoolean("ok")) throw new IOException(r.optString("error", "хаб вернул ошибку"));
    }

    /** Hands the hub this profile's Home Assistant / Yandex logins and computer; returns what it took. */
    String importSettings(Prefs p) throws IOException, JSONException {
        JSONObject b = new JSONObject();
        if (!p.haUrl().isEmpty()) {
            b.put("ha_url", p.haUrl());
            if (!p.haToken().isEmpty()) b.put("ha_token", p.haToken());
            else if (!p.haRefresh().isEmpty()) b.put("ha_refresh", p.haRefresh());
        }
        if (!p.yaToken().isEmpty()) b.put("ya_token", p.yaToken()).put("ya_client_id", p.yaClientId());
        if (!p.pcHost().isEmpty() && !p.pcToken().isEmpty()) {
            b.put("pc", new JSONObject().put("host", p.pcHost()).put("port", p.pcPort()).put("token", p.pcToken())
                    .put("mac", p.pcMac()));
        }
        JSONObject r = new JSONObject(request("POST", "/api/import", b));
        if (!r.optBoolean("ok")) throw new IOException(r.optString("error", "хаб вернул ошибку"));
        JSONArray done = r.optJSONArray("imported");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; done != null && i < done.length(); i++) sb.append(i > 0 ? ", " : "").append(done.optString(i));
        return sb.toString();
    }

    static HomeDevice parse(JSONObject o) {
        HomeDevice d = new HomeDevice();
        d.id = o.optString("id");
        d.source = o.optString("source");
        d.nativeId = o.optString("nativeId");
        d.name = o.optString("name");
        d.room = o.optString("room");
        d.state = o.optString("state");
        d.kind = o.optInt("kind", HomeDevice.SWITCH);
        d.available = o.optBoolean("available", true);
        d.canToggle = o.optBoolean("canToggle");
        d.on = o.optBoolean("on");
        d.dimmable = o.optBoolean("dimmable");
        d.colorTemp = o.optBoolean("colorTemp");
        d.color = o.optBoolean("color");
        d.brightness = o.optInt("brightness", -1);
        d.tempK = o.optInt("tempK", -1);
        d.minK = o.optInt("minK", 2700);
        d.maxK = o.optInt("maxK", 6500);
        d.colorModel = o.optString("colorModel");
        d.current = o.isNull("current") ? Double.NaN : o.optDouble("current");
        d.target = o.isNull("target") ? Double.NaN : o.optDouble("target");
        d.tMin = o.optDouble("tMin", 16);
        d.tMax = o.optDouble("tMax", 30);
        d.tStep = o.optDouble("tStep", 0.5);
        d.progress = o.optInt("progress", -1);
        d.remainingMin = o.optInt("remainingMin", -1);
        d.job = o.optString("job");
        d.detail = o.optString("detail");
        d.alert = o.optBoolean("alert");
        JSONObject cmds = o.optJSONObject("cmds");
        if (cmds != null) {
            for (java.util.Iterator<String> it = cmds.keys(); it.hasNext(); ) {
                String k = it.next();
                d.cmds.put(k, cmds.optString(k));
            }
        }
        return d;
    }

    /** Broadcasts a probe; returns {host, port, name} for every hub that answers. */
    static List<String[]> discover(int timeoutMs) {
        List<String[]> out = new ArrayList<>();
        try (DatagramSocket s = new DatagramSocket()) {
            s.setBroadcast(true);
            s.setSoTimeout(300);
            byte[] msg = "DESKDASH_HUB_DISCOVER".getBytes(StandardCharsets.UTF_8);
            s.send(new DatagramPacket(msg, msg.length, InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT));
            long end = System.currentTimeMillis() + timeoutMs;
            byte[] buf = new byte[1024];
            while (System.currentTimeMillis() < end) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    s.receive(p);
                    JSONObject o = new JSONObject(new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8));
                    String host = p.getAddress().getHostAddress();
                    boolean dup = false;
                    for (String[] r : out) dup |= r[0].equals(host);
                    if (!dup) out.add(new String[]{host, String.valueOf(o.optInt("port", 8780)), o.optString("name", host)});
                } catch (SocketTimeoutException | JSONException ignored) {
                    // keep listening until the deadline
                }
            }
        } catch (IOException ignored) {
            // no network
        }
        return out;
    }
}
