package com.deskdash;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.wifi.WifiManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Bambu Lab 3D printers straight over the LAN, without the cloud, the PC or Home Assistant: the
 * printer publishes its state over MQTT (port 8883, TLS with a self-signed certificate, user "bblp",
 * password = the access code from its screen). P1 and A1 printers also send camera JPEGs on port 6000.
 * Each printer is a tile "bambu:<serial>" in the smart home blocks and shows up in the live strip.
 */
final class Bambu {
    static final class Config {
        String host = "", serial = "", code = "", name = "";
    }

    /** One printer: a network thread keeps state up to date; the dashboard reads it. */
    static final class Printer {
        final Config cfg;
        final JSONObject state = new JSONObject();
        volatile boolean connected, stopped;
        volatile String error = "";
        volatile long updatedAt;
        volatile Bitmap frame;
        volatile long wantCameraUntil;
        volatile String event = "";
        volatile long eventAt;
        private volatile SSLSocket sock;
        private volatile boolean cameraRunning, cameraFailed;
        private String prevState = "";

        Printer(Config c) {
            cfg = c;
        }

        private synchronized String str(String k) {
            return state.optString(k, "");
        }

        private synchronized double num(String k) {
            try {
                return Double.parseDouble(state.optString(k, ""));
            } catch (NumberFormatException e) {
                return Double.NaN;
            }
        }

        String gcodeState() {
            return str("gcode_state").toUpperCase(Locale.ROOT);
        }

        HomeDevice device() {
            HomeDevice d = new HomeDevice();
            d.id = "bambu:" + cfg.serial;
            d.source = "bambu";
            d.nativeId = cfg.serial;
            d.kind = HomeDevice.PRINTER;
            d.name = cfg.name.isEmpty() ? "3D-принтер" : cfg.name;
            d.room = "Bambu Lab";
            d.available = connected;
            String st = gcodeState();
            boolean printing = st.equals("RUNNING") || st.equals("PREPARE") || st.equals("PAUSE") || st.equals("SLICING");
            d.on = printing;
            d.state = !connected ? (error.isEmpty() ? "Подключение…" : error) : stateName(st);
            d.alert = st.equals("FAILED");
            double pct = num("mc_percent");
            if (printing) d.progress = Double.isNaN(pct) ? 0 : (int) Math.max(0, Math.min(100, pct));
            double left = num("mc_remaining_time");
            if (!Double.isNaN(left)) {
                // the printer reports once a minute: count down in between
                if (st.equals("RUNNING") && updatedAt > 0) left -= (System.currentTimeMillis() - updatedAt) / 60_000.0;
                d.remainingMin = (int) Math.round(Math.max(0, left));
            }
            String job = str("subtask_name");
            if (job.isEmpty()) job = str("gcode_file");
            d.job = job.replaceAll("^.*/", "").replaceAll("(?i)(\\.gcode)?\\.3mf$|\\.gcode$", "");
            StringBuilder det = new StringBuilder();
            if (!Double.isNaN(num("nozzle_temper"))) {
                det.append("Сопло ").append(HomeDevice.deg(Math.round(num("nozzle_temper"))));
                if (num("nozzle_target_temper") > 0) det.append(" / ").append(HomeDevice.deg(Math.round(num("nozzle_target_temper"))));
            }
            if (!Double.isNaN(num("bed_temper"))) {
                det.append(det.length() > 0 ? "   " : "").append("Стол ").append(HomeDevice.deg(Math.round(num("bed_temper"))));
                if (num("bed_target_temper") > 0) det.append(" / ").append(HomeDevice.deg(Math.round(num("bed_target_temper"))));
            }
            double layer = num("layer_num"), total = num("total_layer_num");
            if (printing && total > 0) det.append(det.length() > 0 ? "\n" : "").append("Слой ").append((int) layer).append(" из ").append((int) total);
            d.detail = det.toString();
            if (connected) {
                if (st.equals("PAUSE")) d.cmds.put("resume", "resume");
                else if (printing) d.cmds.put("pause", "pause");
                if (printing) d.cmds.put("stop", "stop");
                d.cmds.put("light", "light");
            }
            if (!cameraFailed) d.image = "bambu:" + cfg.serial;
            return d;
        }

        boolean lightOn() {
            synchronized (this) {
                JSONArray a = state.optJSONArray("lights_report");
                for (int i = 0; a != null && i < a.length(); i++) {
                    JSONObject o = a.optJSONObject(i);
                    if (o != null && "chamber_light".equals(o.optString("node"))) return "on".equals(o.optString("mode"));
                }
            }
            return false;
        }

        /** pause / resume / stop / light. Blocking; throws when the printer isn't connected. */
        void command(String action) throws IOException {
            SSLSocket s = sock;
            if (s == null || !connected) throw new IOException("принтер не подключён");
            JSONObject msg = new JSONObject();
            try {
                if ("light".equals(action)) {
                    msg.put("system", new JSONObject().put("sequence_id", "0").put("command", "ledctrl")
                            .put("led_node", "chamber_light").put("led_mode", lightOn() ? "off" : "on")
                            .put("led_on_time", 500).put("led_off_time", 500).put("loop_times", 0).put("interval_time", 0));
                } else {
                    msg.put("print", new JSONObject().put("sequence_id", "0").put("command", action));
                }
            } catch (JSONException e) {
                throw new IOException(e);
            }
            byte[] pkt = Mqtt.publish("device/" + cfg.serial + "/request", msg.toString().getBytes(StandardCharsets.UTF_8));
            synchronized (this) {
                OutputStream o = s.getOutputStream();
                o.write(pkt);
                o.flush();
            }
        }

        // ---------------------------------------------------------------- network threads

        void start() {
            Thread t = new Thread(this::run, "bambu-" + cfg.serial);
            t.setDaemon(true);
            t.start();
        }

        void stop() {
            stopped = true;
            close(sock);
        }

        private void run() {
            long delay = 3000;
            while (!stopped) {
                try {
                    session();
                    delay = 3000;
                } catch (SecurityException e) {
                    fail(e.getMessage());
                    delay = 60_000; // wrong access code: don't hammer the printer
                } catch (Exception e) {
                    fail(e instanceof SocketTimeoutException ? "не отвечает" : "недоступен в сети");
                }
                sleep(delay);
                delay = Math.min(delay * 2, 60_000);
            }
        }

        private void fail(String msg) {
            connected = false;
            error = msg;
        }

        private void session() throws Exception {
            SSLSocket s = (SSLSocket) trustAll().getSocketFactory().createSocket();
            sock = s;
            try {
                s.connect(new InetSocketAddress(cfg.host, 8883), 8000);
                s.setSoTimeout(30_000);
                s.startHandshake();
                InputStream in = s.getInputStream();
                OutputStream out = s.getOutputStream();
                out.write(Mqtt.connect("deskdash-" + (System.currentTimeMillis() % 100000), "bblp", cfg.code));
                int[] head = new int[1];
                byte[] body = Mqtt.read(in, head);
                if (head[0] >> 4 != 2 || body.length < 2) throw new IOException("неожиданный ответ");
                if (body[1] != 0) throw new SecurityException(body[1] == 4 || body[1] == 5 ? "неверный код доступа" : "отказ " + body[1]);
                out.write(Mqtt.subscribe("device/" + cfg.serial + "/report"));
                out.write(Mqtt.publish("device/" + cfg.serial + "/request",
                        "{\"pushing\":{\"sequence_id\":\"1\",\"command\":\"pushall\"}}".getBytes(StandardCharsets.UTF_8)));
                out.flush();
                connected = true;
                error = "";
                long lastPing = System.currentTimeMillis();
                while (!stopped) {
                    try {
                        body = Mqtt.read(in, head);
                        if (head[0] >> 4 == 3) merge(Mqtt.payload(head[0], body));
                    } catch (SocketTimeoutException ignored) {
                        // quiet printer: just ping
                    }
                    if (System.currentTimeMillis() - lastPing > 25_000) {
                        synchronized (this) {
                            out.write(Mqtt.PING);
                            out.flush();
                        }
                        lastPing = System.currentTimeMillis();
                    }
                    if (wantCameraUntil > System.currentTimeMillis() && !cameraRunning && !cameraFailed) startCamera();
                }
            } finally {
                connected = false;
                sock = null;
                close(s);
            }
        }

        private void merge(byte[] payload) {
            JSONObject p;
            try {
                p = new JSONObject(new String(payload, StandardCharsets.UTF_8)).optJSONObject("print");
            } catch (JSONException e) {
                return;
            }
            if (p == null) return;
            synchronized (this) {
                for (java.util.Iterator<String> it = p.keys(); it.hasNext(); ) {
                    String k = it.next();
                    try {
                        state.put(k, p.get(k));
                    } catch (JSONException ignored) {
                        // keys come from the same object
                    }
                }
            }
            if (p.has("mc_remaining_time") || p.has("mc_percent")) updatedAt = System.currentTimeMillis();
            String st = gcodeState();
            if (!prevState.isEmpty() && !prevState.equals(st)) {
                String n = cfg.name.isEmpty() ? "Принтер" : cfg.name;
                String e = st.equals("FINISH") ? n + ": печать готова" : st.equals("FAILED") ? n + ": печать прервана"
                        : st.equals("PAUSE") ? n + ": пауза" : st.equals("RUNNING") && !prevState.equals("PAUSE") ? n + ": печать началась" : "";
                if (!e.isEmpty()) {
                    event = e;
                    eventAt = System.currentTimeMillis();
                }
            }
            prevState = st;
        }

        /** P1 / A1 camera: an 80-byte login, then frames of a 16-byte header (size at 0, little endian) + JPEG. */
        private void startCamera() {
            cameraRunning = true;
            Thread t = new Thread(() -> {
                SSLSocket s = null;
                try {
                    s = (SSLSocket) trustAll().getSocketFactory().createSocket();
                    s.connect(new InetSocketAddress(cfg.host, 6000), 6000);
                    s.setSoTimeout(12_000);
                    s.startHandshake();
                    byte[] auth = new byte[80];
                    le(auth, 0, 0x40);
                    le(auth, 4, 0x3000);
                    byte[] u = "bblp".getBytes(StandardCharsets.US_ASCII), c = cfg.code.getBytes(StandardCharsets.US_ASCII);
                    System.arraycopy(u, 0, auth, 16, Math.min(32, u.length));
                    System.arraycopy(c, 0, auth, 48, Math.min(32, c.length));
                    s.getOutputStream().write(auth);
                    InputStream in = s.getInputStream();
                    byte[] hdr = new byte[16];
                    while (!stopped && wantCameraUntil > System.currentTimeMillis()) {
                        Mqtt.exact(in, hdr);
                        int n = (hdr[0] & 255) | (hdr[1] & 255) << 8 | (hdr[2] & 255) << 16 | (hdr[3] & 255) << 24;
                        if (n <= 0 || n > 4_000_000) throw new IOException("bad frame");
                        byte[] jpg = new byte[n];
                        Mqtt.exact(in, jpg);
                        Bitmap b = BitmapFactory.decodeByteArray(jpg, 0, n);
                        if (b != null) frame = b;
                    }
                } catch (Exception e) {
                    if (frame == null) cameraFailed = true; // X1 (RTSP) or the camera is off in the printer settings
                } finally {
                    close(s);
                    cameraRunning = false;
                }
            }, "bambu-cam-" + cfg.serial);
            t.setDaemon(true);
            t.start();
        }
    }

    // ---------------------------------------------------------------- registry

    private static final Map<String, Printer> printers = new LinkedHashMap<>();
    private static String configured = "";

    private Bambu() {}

    static List<Config> load(Prefs p) {
        List<Config> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(p.bambu());
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Config c = new Config();
                c.host = o.optString("host").trim();
                c.serial = o.optString("serial").trim().toUpperCase(Locale.ROOT);
                c.code = o.optString("code").trim();
                c.name = o.optString("name").trim();
                if (!c.host.isEmpty() && !c.serial.isEmpty() && !c.code.isEmpty()) out.add(c);
            }
        } catch (JSONException ignored) {
            // nothing saved yet
        }
        return out;
    }

    static void save(Prefs p, List<Config> list) {
        JSONArray a = new JSONArray();
        try {
            for (Config c : list) {
                a.put(new JSONObject().put("host", c.host).put("serial", c.serial).put("code", c.code).put("name", c.name));
            }
        } catch (JSONException ignored) {
            // only strings go in
        }
        p.setBambu(a.toString());
    }

    static boolean configured(Prefs p) {
        return !load(p).isEmpty();
    }

    /** Connects to the printers of the active profile, dropping the ones that are gone. */
    static synchronized void sync(Prefs p) {
        String now = p.bambu();
        if (now.equals(configured)) return;
        configured = now;
        stopAll();
        for (Config c : load(p)) {
            Printer pr = new Printer(c);
            printers.put(c.serial, pr);
            pr.start();
        }
    }

    static synchronized void stopAll() {
        for (Printer p : printers.values()) p.stop();
        printers.clear();
        configured = "";
    }

    static synchronized List<HomeDevice> devices() {
        List<HomeDevice> out = new ArrayList<>();
        for (Printer p : printers.values()) out.add(p.device());
        return out;
    }

    static synchronized Printer get(String serial) {
        return printers.get(serial);
    }

    /** Latest camera picture, and keeps the camera running for a while longer. */
    static Bitmap frame(String serial) {
        Printer p = get(serial);
        if (p == null) return null;
        p.wantCameraUntil = System.currentTimeMillis() + 20_000;
        return p.frame;
    }

    /** Print started / finished / failed in the last minute, for the dashboard banner. */
    static synchronized String recentEvent(long sinceMs) {
        for (Printer p : printers.values()) if (p.eventAt > sinceMs) return p.event;
        return null;
    }

    // ---------------------------------------------------------------- discovery

    /** Printers that announce themselves by SSDP: {ip, serial, name}. Blocking for the given time. */
    static List<String[]> discover(Context ctx, int ms) {
        List<String[]> out = new ArrayList<>();
        WifiManager wm = ctx.getApplicationContext().getSystemService(WifiManager.class);
        WifiManager.MulticastLock lock = wm == null ? null : wm.createMulticastLock("deskdash-bambu");
        if (lock != null) lock.acquire();
        try (DatagramSocket s = new DatagramSocket(null)) {
            s.setReuseAddress(true);
            s.setBroadcast(true);
            try {
                s.bind(new InetSocketAddress(2021));
            } catch (IOException e) {
                s.bind(null);
            }
            s.setSoTimeout(300);
            byte[] q = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1990\r\nMAN: \"ssdp:discover\"\r\nMX: 1\r\n"
                    + "ST: urn:bambulab-com:device:3dprinter:1\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
            for (String[] a : new String[][]{{"239.255.255.250", "1990"}, {"255.255.255.255", "2021"}}) {
                try {
                    s.send(new DatagramPacket(q, q.length, InetAddress.getByName(a[0]), Integer.parseInt(a[1])));
                } catch (IOException ignored) {
                    // no route for multicast: the broadcast still goes
                }
            }
            long end = System.currentTimeMillis() + ms;
            byte[] buf = new byte[4096];
            while (System.currentTimeMillis() < end) {
                DatagramPacket pk = new DatagramPacket(buf, buf.length);
                try {
                    s.receive(pk);
                } catch (SocketTimeoutException e) {
                    continue;
                }
                String t = new String(pk.getData(), 0, pk.getLength(), StandardCharsets.UTF_8);
                if (!t.toLowerCase(Locale.ROOT).contains("bambu")) continue;
                String serial = field(t, "USN").toUpperCase(Locale.ROOT);
                if (serial.isEmpty()) continue;
                String loc = field(t, "Location");
                String ip = loc.matches("\\d+\\.\\d+\\.\\d+\\.\\d+") ? loc : pk.getAddress().getHostAddress();
                String name = field(t, "DevName\\.bambu\\.com");
                boolean dup = false;
                for (String[] r : out) dup |= r[1].equals(serial);
                if (!dup) out.add(new String[]{ip, serial, name.isEmpty() ? serial : name});
            }
        } catch (IOException ignored) {
            // no Wi-Fi
        } finally {
            if (lock != null) lock.release();
        }
        return out;
    }

    private static String field(String text, String name) {
        Matcher m = Pattern.compile("^" + name + ":\\s*(.+?)\\s*$", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE).matcher(text);
        return m.find() ? m.group(1) : "";
    }

    // ---------------------------------------------------------------- helpers

    private static SSLContext trustAll() throws Exception {
        TrustManager tm = new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] c, String a) {}

            @Override
            public void checkServerTrusted(X509Certificate[] c, String a) {}

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[]{tm}, null); // the printer's certificate is self-signed
        return ctx;
    }

    private static void le(byte[] b, int at, int v) {
        b[at] = (byte) v;
        b[at + 1] = (byte) (v >> 8);
        b[at + 2] = (byte) (v >> 16);
        b[at + 3] = (byte) (v >> 24);
    }

    private static void close(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException ignored) {
            // already closed
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static String stateName(String st) {
        switch (st) {
            case "RUNNING": return "Печать";
            case "PREPARE": return "Подготовка";
            case "SLICING": return "Нарезка";
            case "PAUSE": return "Пауза";
            case "FINISH": return "Готово";
            case "FAILED": return "Ошибка";
            default: return "Ожидание";
        }
    }

    /** The few MQTT 3.1.1 packets the printer needs. */
    static final class Mqtt {
        static final byte[] PING = {(byte) 0xC0, 0};

        private Mqtt() {}

        static byte[] packet(int type, int flags, byte[] body) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            b.write((type << 4) | flags);
            int n = body.length;
            do {
                int d = n % 128;
                n /= 128;
                b.write(d | (n > 0 ? 0x80 : 0));
            } while (n > 0);
            b.write(body, 0, body.length);
            return b.toByteArray();
        }

        static byte[] str(String s) {
            byte[] v = s.getBytes(StandardCharsets.UTF_8);
            byte[] o = new byte[v.length + 2];
            o[0] = (byte) (v.length >> 8);
            o[1] = (byte) v.length;
            System.arraycopy(v, 0, o, 2, v.length);
            return o;
        }

        static byte[] cat(byte[]... parts) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            for (byte[] p : parts) b.write(p, 0, p.length);
            return b.toByteArray();
        }

        static byte[] connect(String client, String user, String pass) {
            return packet(1, 0, cat(str("MQTT"), new byte[]{4, (byte) 0xC2, 0, 60}, str(client), str(user), str(pass)));
        }

        static byte[] subscribe(String topic) {
            return packet(8, 2, cat(new byte[]{0, 1}, str(topic), new byte[]{0}));
        }

        static byte[] publish(String topic, byte[] payload) {
            return packet(3, 0, cat(str(topic), payload));
        }

        static void exact(InputStream in, byte[] buf) throws IOException {
            int got = 0;
            while (got < buf.length) {
                int n = in.read(buf, got, buf.length - got);
                if (n < 0) throw new IOException("соединение закрыто");
                got += n;
            }
        }

        /** One packet; head[0] gets the first byte (type and flags). */
        static byte[] read(InputStream in, int[] head) throws IOException {
            int h = in.read();
            if (h < 0) throw new IOException("соединение закрыто");
            head[0] = h;
            int n = 0, mult = 1, b;
            do {
                b = in.read();
                if (b < 0) throw new IOException("соединение закрыто");
                n += (b & 0x7F) * mult;
                mult *= 128;
            } while ((b & 0x80) != 0);
            byte[] body = new byte[n];
            exact(in, body);
            return body;
        }

        static byte[] payload(int head, byte[] body) {
            int tlen = (body[0] & 255) << 8 | (body[1] & 255);
            int at = 2 + tlen + (((head >> 1) & 3) != 0 ? 2 : 0);
            byte[] p = new byte[Math.max(0, body.length - at)];
            System.arraycopy(body, at, p, 0, p.length);
            return p;
        }
    }
}
