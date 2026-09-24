package com.deskdash;

import android.content.ComponentName;
import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import java.util.List;

/**
 * Follows the system media sessions (needs notification access) and picks the one to show:
 * whatever is playing, preferring Spotify. Works for Spotify Connect playback on the PC too.
 */
public class MusicWatcher {
    public interface Listener {
        void onController(MediaController c);

        void onNoAccess();
    }

    private static final String SPOTIFY = "com.spotify.music";

    private final Context ctx;
    private final Listener listener;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final ComponentName cn;
    private final MediaSessionManager msm;
    private MediaController cur;
    private boolean started;

    private final MediaSessionManager.OnActiveSessionsChangedListener sessions = this::pick;

    private final MediaController.Callback cb = new MediaController.Callback() {
        @Override
        public void onMetadataChanged(MediaMetadata m) {
            listener.onController(cur);
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState s) {
            repick();
        }

        @Override
        public void onSessionDestroyed() {
            repick();
        }
    };

    public MusicWatcher(Context c, Listener l) {
        ctx = c.getApplicationContext();
        listener = l;
        cn = new ComponentName(ctx, MediaListenerService.class);
        msm = ctx.getSystemService(MediaSessionManager.class);
    }

    public static boolean hasAccess(Context c) {
        String s = Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
        return s != null && s.contains(c.getPackageName());
    }

    public void start() {
        if (started) return;
        if (!hasAccess(ctx)) {
            listener.onNoAccess();
            return;
        }
        try {
            msm.addOnActiveSessionsChangedListener(sessions, cn, h);
            started = true;
            pick(msm.getActiveSessions(cn));
        } catch (SecurityException e) {
            listener.onNoAccess();
        }
    }

    public void stop() {
        if (started) {
            msm.removeOnActiveSessionsChangedListener(sessions);
            started = false;
        }
        if (cur != null) {
            cur.unregisterCallback(cb);
            cur = null;
        }
    }

    private void repick() {
        try {
            pick(msm.getActiveSessions(cn));
        } catch (SecurityException e) {
            listener.onNoAccess();
        }
    }

    private void pick(List<MediaController> list) {
        MediaController best = null;
        int bestScore = -1;
        if (list != null) {
            for (MediaController c : list) {
                PlaybackState st = c.getPlaybackState();
                int score = 0;
                if (st != null && st.getState() == PlaybackState.STATE_PLAYING) score += 10;
                if (SPOTIFY.equals(c.getPackageName())) score += 5;
                if (c.getMetadata() != null) score += 1;
                if (score > bestScore) {
                    best = c;
                    bestScore = score;
                }
            }
        }
        boolean same = best == cur || (best != null && cur != null && best.getSessionToken().equals(cur.getSessionToken()));
        if (!same) {
            if (cur != null) cur.unregisterCallback(cb);
            cur = best;
            if (cur != null) cur.registerCallback(cb, h);
        }
        listener.onController(cur);
    }
}
