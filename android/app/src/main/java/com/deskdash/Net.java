package com.deskdash;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

final class Net {
    private Net() {}

    /** Non-2xx answer; message is a short piece of the response body. */
    static final class HttpError extends IOException {
        final int code;

        HttpError(int code, String msg) {
            super(msg.isEmpty() ? "HTTP " + code : "HTTP " + code + ": " + msg);
            this.code = code;
        }
    }

    /** Generic request for the smart home APIs; auth is a full Authorization header value or null. */
    static String http(String method, String url, String auth, String contentType, String body, int timeoutMs) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            if (auth != null) c.setRequestProperty("Authorization", auth);
            if (body != null) {
                byte[] data = body.getBytes(StandardCharsets.UTF_8);
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", contentType);
                c.setFixedLengthStreamingMode(data.length);
                try (OutputStream o = c.getOutputStream()) {
                    o.write(data);
                }
            }
            int code = c.getResponseCode();
            InputStream in = code / 100 == 2 ? c.getInputStream() : c.getErrorStream();
            String text = "";
            if (in != null) {
                try (InputStream s = in) {
                    ByteArrayOutputStream b = new ByteArrayOutputStream();
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = s.read(buf)) > 0) b.write(buf, 0, n);
                    text = b.toString(StandardCharsets.UTF_8.name());
                }
            }
            if (code / 100 != 2) {
                String t = text.replaceAll("\\s+", " ").trim();
                throw new HttpError(code, t.length() > 120 ? t.substring(0, 120) + "…" : t);
            }
            return text;
        } finally {
            c.disconnect();
        }
    }

    static String request(String method, String url, String token, int timeoutMs) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setInstanceFollowRedirects(true);
            if (token != null) c.setRequestProperty("X-Token", token);
            if ("POST".equals(method)) {
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(0);
                try (OutputStream o = c.getOutputStream()) {
                    o.flush();
                }
            }
            int code = c.getResponseCode();
            if (code == 401) throw new IOException("неверный токен");
            if (code / 100 != 2) throw new IOException("HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
                return b.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            c.disconnect();
        }
    }
}
