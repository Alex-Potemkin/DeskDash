package com.deskdash;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * What Spotify plays on this phone without any permission: Spotify sends "device broadcast status"
 * intents (its own switch: Spotify → Настройки → «Трансляция статуса устройства»), and the cover comes from
 * Spotify's public oEmbed page. Used when notification access isn't given.
 */
final class SpotifyBroadcast {
    static final class Track {
        String id = "", title = "", artist = "", album = "";
        long length, position, at;
        boolean playing;
        Bitmap art;
    }

    interface Listener {
        void onTrack(Track t);
    }

    /** The last state Spotify announced; survives dashboard rebuilds. */
    static volatile Track last;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Context ctx;
    private Listener listener;
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            Track t = last == null ? new Track() : copy(last);
            String a = i.getAction() == null ? "" : i.getAction();
            if (a.endsWith("metadatachanged")) {
                String id = str(i, "id");
                if (!id.equals(t.id)) t.art = null;
                t.id = id;
                t.title = str(i, "track");
                t.artist = str(i, "artist");
                t.album = str(i, "album");
                t.length = i.getIntExtra("length", 0);
            }
            if (i.hasExtra("playing")) t.playing = i.getBooleanExtra("playing", t.playing);
            if (i.hasExtra("playbackPosition")) t.position = i.getIntExtra("playbackPosition", 0);
            t.at = System.currentTimeMillis();
            last = t;
            if (t.art == null && !t.id.isEmpty()) loadArt(t);
            if (listener != null) listener.onTrack(t);
        }
    };

    void start(Context c, Listener l) {
        ctx = c.getApplicationContext() != null ? c.getApplicationContext() : c;
        listener = l;
        IntentFilter f = new IntentFilter();
        f.addAction("com.spotify.music.metadatachanged");
        f.addAction("com.spotify.music.playbackstatechanged");
        try {
            if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(receiver, f, Context.RECEIVER_EXPORTED);
            else ctx.registerReceiver(receiver, f);
        } catch (RuntimeException ignored) {
            // no receivers allowed here (a screensaver being torn down)
        }
    }

    void stop() {
        listener = null;
        try {
            if (ctx != null) ctx.unregisterReceiver(receiver);
        } catch (RuntimeException ignored) {
            // not registered
        }
    }

    /** Position now, counting the time since Spotify last said where it was. */
    static long position(Track t) {
        return t.playing ? t.position + System.currentTimeMillis() - t.at : t.position;
    }

    private void loadArt(Track t) {
        String id = t.id;
        new Thread(() -> {
            try {
                String url = "https://open.spotify.com/oembed?url=" + URLEncoder.encode(id, "UTF-8");
                String thumb = new JSONObject(Net.http("GET", url, null, null, null, 6000)).optString("thumbnail_url");
                if (thumb.isEmpty()) return;
                HttpURLConnection c = (HttpURLConnection) new URL(thumb).openConnection();
                c.setConnectTimeout(6000);
                c.setReadTimeout(8000);
                Bitmap b;
                try (InputStream in = c.getInputStream()) {
                    b = BitmapFactory.decodeStream(in);
                } finally {
                    c.disconnect();
                }
                if (b == null) return;
                ui.post(() -> {
                    Track cur = last;
                    if (cur == null || !cur.id.equals(id)) return;
                    cur.art = b;
                    if (listener != null) listener.onTrack(cur);
                });
            } catch (Exception ignored) {
                // offline: no cover
            }
        }).start();
    }

    private static String str(Intent i, String key) {
        String s = i.getStringExtra(key);
        return s == null ? "" : s;
    }

    private static Track copy(Track o) {
        Track t = new Track();
        t.id = o.id;
        t.title = o.title;
        t.artist = o.artist;
        t.album = o.album;
        t.length = o.length;
        t.position = o.position;
        t.at = o.at;
        t.playing = o.playing;
        t.art = o.art;
        return t;
    }
}
