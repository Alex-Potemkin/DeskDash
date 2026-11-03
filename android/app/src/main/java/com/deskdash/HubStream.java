package com.deskdash;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The hub's live state (/api/events, server-sent events): the hub sends the whole device list the moment
 * anything changes, so a lamp switched from the wall or another screen shows up at once instead of at the
 * next poll. Runs on its own thread and reconnects by itself; polling stays as the fallback while it is down.
 */
final class HubStream {
    interface Listener {
        /** Background thread. */
        void onDevices(List<HomeDevice> devices);
    }

    private final Prefs prefs;
    private final Listener listener;
    private volatile int run; // bumped by start() and stop(): an old thread sees it changed and quits
    private volatile boolean live;
    private volatile HttpURLConnection conn;

    HubStream(Prefs p, Listener l) {
        prefs = p;
        listener = l;
    }

    /** Connected and getting the hub's pushes. */
    boolean live() {
        return live;
    }

    void start() {
        int my = ++run;
        Thread t = new Thread(() -> loop(my), "hub-events");
        t.setDaemon(true);
        t.start();
    }

    void stop() {
        run++;
        live = false;
        HttpURLConnection c = conn;
        // closing the socket can touch the network: not on the UI thread
        if (c != null) new Thread(c::disconnect, "hub-events-close").start();
    }

    private void loop(int my) {
        long wait = 1000;
        while (run == my && HubClient.configured(prefs)) {
            try {
                listen(my);
                wait = 1000;
            } catch (Exception ignored) {
                // hub restarting or out of reach: try again, less and less often
            }
            live = false;
            if (run != my) return;
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                return;
            }
            wait = Math.min(wait * 2, 30_000);
        }
    }

    private void listen(int my) throws IOException, org.json.JSONException {
        HttpURLConnection c = (HttpURLConnection) new URL(prefs.hubUrl() + "/api/events").openConnection();
        conn = c;
        try {
            c.setConnectTimeout(5000);
            c.setReadTimeout(45_000); // the hub sends a ping every 20 s
            c.setRequestProperty("Authorization", "Bearer " + prefs.hubToken());
            c.setRequestProperty("Accept", "text/event-stream");
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            BufferedReader in = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder data = new StringBuilder();
            String line;
            while (run == my && (line = in.readLine()) != null) {
                if (line.startsWith("data:")) {
                    data.append(line, 5, line.length());
                } else if (line.isEmpty() && data.length() > 0) {
                    JSONArray a = new JSONObject(data.toString()).optJSONArray("devices");
                    data.setLength(0);
                    List<HomeDevice> out = new ArrayList<>();
                    for (int i = 0; a != null && i < a.length(); i++) out.add(HubClient.parse(a.getJSONObject(i)));
                    live = true;
                    if (run == my) listener.onDevices(out);
                }
            }
        } finally {
            conn = null;
            c.disconnect();
        }
    }
}
