package com.deskdash;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Talks to deskdash_agent.py on the PC over the local network. */
public class PcClient {
    public static final int DISCOVERY_PORT = 8766;

    public static class Macro {
        public String id, label, icon;
        public boolean confirm;
    }

    public static class Info {
        public String name = "", mac = "", macrosJson = "[]";
        public List<Macro> macros = new ArrayList<>();
        public boolean spotify;
    }

    public static class SpDevice {
        public String id, name, type;
        public boolean active;
        public int volume = -1;
    }

    /** Spotify Connect state via the PC agent. error != null when Spotify refused or isn't linked. */
    public static class SpState {
        public List<SpDevice> devices = new ArrayList<>();
        public String title = "", artist = "", image = "", device = "", deviceType = "";
        public boolean playing, hasTrack;
        public String error;
    }

    private final String base, token;

    public PcClient(String host, int port, String token) {
        this.base = "http://" + host.trim() + ":" + port;
        this.token = token.trim();
    }

    public Info info() throws IOException, JSONException {
        JSONObject o = new JSONObject(Net.request("GET", base + "/api/info", token, 2500));
        Info i = new Info();
        i.name = o.optString("name", "");
        i.mac = o.optString("mac", "");
        i.spotify = o.optBoolean("spotify", false);
        JSONArray a = o.optJSONArray("macros");
        if (a != null) {
            i.macrosJson = a.toString();
            i.macros = parseMacros(a);
        }
        return i;
    }

    public JSONObject stats() throws IOException, JSONException {
        return new JSONObject(Net.request("GET", base + "/api/stats", token, 2500));
    }

    public boolean run(String id) {
        try {
            String url = base + "/api/macro/" + URLEncoder.encode(id, "UTF-8").replace("+", "%20");
            return new JSONObject(Net.request("POST", url, token, 4000)).optBoolean("ok");
        } catch (IOException | JSONException e) {
            return false;
        }
    }

    public SpState spotifyState() throws IOException, JSONException {
        JSONObject o = new JSONObject(Net.request("GET", base + "/api/spotify/state", token, 8000));
        SpState s = new SpState();
        if (!o.optBoolean("ok")) {
            s.error = o.optString("error", "Ошибка Spotify");
            return s;
        }
        JSONArray a = o.optJSONArray("devices");
        for (int k = 0; a != null && k < a.length(); k++) {
            JSONObject d = a.getJSONObject(k);
            SpDevice x = new SpDevice();
            x.id = d.optString("id");
            x.name = d.optString("name", "?");
            x.type = d.optString("type", "");
            x.active = d.optBoolean("active");
            x.volume = d.isNull("volume") ? -1 : d.optInt("volume", -1);
            s.devices.add(x);
        }
        JSONObject now = o.optJSONObject("now");
        if (now != null && now.length() > 0) {
            s.hasTrack = true;
            s.title = now.optString("title");
            s.artist = now.optString("artist");
            s.image = now.optString("image");
            s.playing = now.optBoolean("playing");
            s.device = now.optString("device");
            s.deviceType = now.optString("device_type");
        }
        return s;
    }

    /** Returns null on success, otherwise the message to show. */
    public String spotifyTransfer(String deviceId) {
        return spotifyPost("/api/spotify/transfer/" + enc(deviceId));
    }

    /** play / pause / next / prev on whatever device Spotify currently uses. */
    public String spotifyCommand(String cmd) {
        return spotifyPost("/api/spotify/cmd/" + cmd);
    }

    private String spotifyPost(String path) {
        try {
            JSONObject o = new JSONObject(Net.request("POST", base + path, token, 10000));
            return o.optBoolean("ok") ? null : o.optString("error", "Ошибка Spotify");
        } catch (IOException | JSONException e) {
            return "Нет связи с ПК";
        }
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (IOException e) {
            return s;
        }
    }

    public static List<Macro> parseMacros(JSONArray a) {
        List<Macro> out = new ArrayList<>();
        for (int k = 0; k < a.length(); k++) {
            JSONObject m = a.optJSONObject(k);
            if (m == null) continue;
            Macro x = new Macro();
            x.id = m.optString("id", "");
            x.label = m.optString("label", x.id);
            x.icon = m.optString("icon", "bolt");
            x.confirm = m.optBoolean("confirm", false);
            out.add(x);
        }
        return out;
    }

    /** Broadcasts a discovery probe; returns {host, port, name} for every agent that answers. */
    public static List<String[]> discover(int timeoutMs) {
        List<String[]> out = new ArrayList<>();
        try (DatagramSocket s = new DatagramSocket()) {
            s.setBroadcast(true);
            s.setSoTimeout(300);
            byte[] msg = "DESKDASH_DISCOVER".getBytes(StandardCharsets.UTF_8);
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
                    if (!dup) out.add(new String[]{host, String.valueOf(o.optInt("port", 8765)), o.optString("name", host)});
                } catch (SocketTimeoutException | JSONException ignored) {
                    // keep listening until the deadline
                }
            }
        } catch (IOException ignored) {
            // no network
        }
        return out;
    }

    /** Wake-on-LAN magic packet. */
    public static void wake(String mac) throws IOException {
        String hex = mac.replaceAll("[^0-9A-Fa-f]", "");
        if (hex.length() != 12) throw new IOException("bad MAC");
        byte[] m = new byte[6];
        for (int i = 0; i < 6; i++) m[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        byte[] pkt = new byte[6 + 16 * 6];
        for (int i = 0; i < 6; i++) pkt[i] = (byte) 0xFF;
        for (int i = 0; i < 16; i++) System.arraycopy(m, 0, pkt, 6 + i * 6, 6);
        try (DatagramSocket s = new DatagramSocket()) {
            s.setBroadcast(true);
            s.send(new DatagramPacket(pkt, pkt.length, InetAddress.getByName("255.255.255.255"), 9));
        }
    }
}
