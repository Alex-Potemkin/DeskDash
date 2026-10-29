package com.deskdash;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Reads an MJPEG stream of the PC agent (multipart/x-mixed-replace, one JPEG per part) on its own
 * thread and hands every frame to the UI thread. Reconnects by itself until stop().
 */
final class MjpegStream {
    interface Listener {
        void onFrame(Bitmap b);

        /** Shown over the picture; null once frames come again. */
        void onStatus(String msg);
    }

    private final String url, token;
    private final Listener listener;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean running;
    private volatile HttpURLConnection conn;
    private volatile boolean framePending;

    MjpegStream(String url, String token, Listener l) {
        this.url = url;
        this.token = token;
        listener = l;
    }

    void start() {
        if (running) return;
        running = true;
        Thread t = new Thread(this::loop, "mjpeg");
        t.setDaemon(true);
        t.start();
    }

    void stop() {
        running = false;
        HttpURLConnection c = conn;
        if (c != null) c.disconnect();
    }

    private void loop() {
        while (running) {
            try {
                read();
            } catch (IOException e) {
                if (!running) return;
                String m = e.getMessage();
                status(m == null || m.isEmpty() ? "Нет связи с ПК" : m);
            }
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void read() throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        conn = c;
        try {
            c.setConnectTimeout(4000);
            c.setReadTimeout(15000);
            c.setRequestProperty("X-Token", token);
            int code = c.getResponseCode();
            if (code != 200) {
                InputStream es = c.getErrorStream();
                String body = es == null ? "" : new String(readAll(es), StandardCharsets.UTF_8);
                String err = body.replaceAll(".*\"error\":\\s*\"([^\"]*)\".*", "$1");
                throw new IOException(code == 401 ? "неверный токен" : code == 404 ? "обнови DeskDash на ПК"
                        : err.equals(body) ? "HTTP " + code : err);
            }
            InputStream in = new BufferedInputStream(c.getInputStream(), 65536);
            boolean first = true;
            while (running) {
                int len = -1;
                String line;
                // part headers up to the empty line
                while ((line = readLine(in)) != null) {
                    if (line.isEmpty() && len >= 0) break;
                    String low = line.toLowerCase(Locale.ROOT);
                    if (low.startsWith("content-length:")) len = Integer.parseInt(low.substring(15).trim());
                }
                if (line == null) throw new IOException("ПК закрыл показ");
                byte[] jpg = new byte[len];
                int got = 0;
                while (got < len) {
                    int n = in.read(jpg, got, len - got);
                    if (n < 0) throw new IOException("ПК закрыл показ");
                    got += n;
                }
                if (framePending) continue; // the UI is behind: skip this one, keep the delay low
                Bitmap b = BitmapFactory.decodeByteArray(jpg, 0, len);
                if (b == null) continue;
                if (first) {
                    first = false;
                    status(null);
                }
                framePending = true;
                ui.post(() -> {
                    framePending = false;
                    if (running) listener.onFrame(b);
                });
            }
        } finally {
            c.disconnect();
            conn = null;
        }
    }

    private void status(String msg) {
        ui.post(() -> {
            if (running) listener.onStatus(msg);
        });
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 1024) break;
        }
        if (c < 0 && b.size() == 0) return null;
        return b.toString("UTF-8");
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        in.close();
        return b.toByteArray();
    }
}
