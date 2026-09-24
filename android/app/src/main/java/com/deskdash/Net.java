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
