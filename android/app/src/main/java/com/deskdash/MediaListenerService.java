package com.deskdash;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/**
 * Notification access unlocks reading media sessions (music) and lets PhoneCalls see the "call in
 * progress" notifications of Telegram, WhatsApp, Zoom and the rest, with their mute / hang up buttons.
 */
public class MediaListenerService extends NotificationListenerService {
    private static volatile MediaListenerService instance;

    @Override
    public void onListenerConnected() {
        instance = this;
    }

    @Override
    public void onListenerDisconnected() {
        instance = null;
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    /** What is in the notification shade now; empty while the system hasn't connected us. */
    static StatusBarNotification[] active() {
        MediaListenerService s = instance;
        if (s == null) return new StatusBarNotification[0];
        try {
            StatusBarNotification[] a = s.getActiveNotifications();
            return a == null ? new StatusBarNotification[0] : a;
        } catch (RuntimeException e) {
            return new StatusBarNotification[0];
        }
    }
}
