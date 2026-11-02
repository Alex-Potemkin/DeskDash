package com.deskdash;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.media.AudioManager;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Calls on the phone itself, no DeskDash.exe needed. Every call app keeps an ongoing "call" notification
 * (Telegram, WhatsApp, Zoom, Meet, Teams, VK, the phone app…): it says who, since when, and carries the
 * app's own buttons (mute, hang up, answer), which the dashboard presses for you. When an app shows no
 * such notification, the audio mode still tells that some call is on, and the system microphone and
 * speaker switches work for any app.
 */
final class PhoneCalls {
    static final class Call {
        /** Meetings.Platform id for the colour and badge, "phone" for ordinary calls. */
        String id = "web", app = "", title = "", key = "";
        long since;
        int color = 0xFF8A8A8A;
        boolean ringing;
        PendingIntent open;
        Notification.Action mute, hangup, answer;
    }

    /** package → {platform id, name}. */
    private static final Map<String, String[]> APPS = new HashMap<>();
    private static final Map<String, Long> firstSeen = new HashMap<>();
    private static final Pattern HANGUP = Pattern.compile("(?i)(hang|end call|leave|decline|reject|заверш|сброс|отклон|положить|выйти|покинуть)");
    private static final Pattern ANSWER = Pattern.compile("(?i)(answer|accept|ответ|принять)");
    private static final Pattern MUTE = Pattern.compile("(?i)(mute|микрофон|звук)");

    static {
        String[][] a = {
                {"org.telegram.messenger", "telegram", "Telegram"}, {"org.telegram.messenger.web", "telegram", "Telegram"},
                {"org.thunderdog.challegram", "telegram", "Telegram X"}, {"com.whatsapp", "whatsapp", "WhatsApp"},
                {"com.whatsapp.w4b", "whatsapp", "WhatsApp"}, {"us.zoom.videomeetings", "zoom", "Zoom"},
                {"com.google.android.apps.tachyon", "meet", "Google Meet"}, {"com.google.android.apps.meetings", "meet", "Google Meet"},
                {"com.microsoft.teams", "teams", "Teams"}, {"com.discord", "discord", "Discord"},
                {"com.skype.raider", "skype", "Skype"}, {"com.Slack", "slack", "Slack"}, {"com.vkontakte.android", "vk", "VK"},
                {"com.vk.calls", "vk", "VK Звонки"}, {"ru.yandex.telemost", "telemost", "Телемост"},
                {"ru.yandex.mail", "telemost", "Телемост"}, {"ru.sberbank.sberjazz", "sber", "SberJazz"},
                {"ru.kontur.talk", "ktalk", "Контур.Толк"}, {"com.cisco.webex.meetings", "webex", "Webex"},
                {"com.viber.voip", "web", "Viber"}, {"org.thoughtcrime.securesms", "web", "Signal"},
                {"com.google.android.dialer", "phone", "Телефон"}, {"com.android.dialer", "phone", "Телефон"},
                {"com.samsung.android.incallui", "phone", "Телефон"}, {"com.android.server.telecom", "phone", "Телефон"},
        };
        for (String[] r : a) APPS.put(r[0], new String[]{r[1], r[2]});
    }

    private PhoneCalls() {}

    /** The call going on (or ringing) on this phone, or null. */
    static Call current(Context c) {
        Call best = null;
        for (StatusBarNotification sbn : MediaListenerService.active()) {
            Notification n = sbn.getNotification();
            String[] app = APPS.get(sbn.getPackageName());
            boolean callCat = Notification.CATEGORY_CALL.equals(n.category);
            boolean ongoing = (n.flags & Notification.FLAG_ONGOING_EVENT) != 0;
            if (!callCat && (app == null || !ongoing)) continue;
            Call x = new Call();
            if (n.actions != null) {
                for (Notification.Action a : n.actions) {
                    String t = a.title == null ? "" : a.title.toString();
                    if (ANSWER.matcher(t).find()) x.answer = a;
                    else if (HANGUP.matcher(t).find()) x.hangup = a;
                    else if (MUTE.matcher(t).find()) x.mute = a;
                }
            }
            Bundle ex = n.extras;
            boolean timer = ex != null && ex.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER);
            // an ongoing notification of a call app without a call: "Telegram is running", a download…
            if (!callCat && x.hangup == null && !timer) continue;
            x.ringing = x.answer != null;
            x.key = sbn.getKey();
            x.id = app != null ? app[0] : "web";
            x.app = app != null ? app[1] : label(c, sbn.getPackageName());
            CharSequence title = ex == null ? null : ex.getCharSequence(Notification.EXTRA_TITLE);
            x.title = title == null ? "" : title.toString();
            Long seen = firstSeen.get(x.key);
            if (seen == null) firstSeen.put(x.key, seen = System.currentTimeMillis());
            x.since = timer && n.when > 0 && n.when <= System.currentTimeMillis() ? n.when : seen;
            x.color = "phone".equals(x.id) ? 0xFF34A853 : Meetings.byId(x.id).color;
            x.open = n.contentIntent;
            if (best == null || best.ringing && !x.ringing) best = x;
        }
        if (best == null) {
            AudioManager am = c.getSystemService(AudioManager.class);
            int mode = am == null ? AudioManager.MODE_NORMAL : am.getMode();
            if (mode == AudioManager.MODE_IN_COMMUNICATION || mode == AudioManager.MODE_IN_CALL) {
                best = new Call();
                best.key = "audio";
                best.app = mode == AudioManager.MODE_IN_CALL ? "Телефон" : "Звонок";
                best.id = mode == AudioManager.MODE_IN_CALL ? "phone" : "web";
                best.color = mode == AudioManager.MODE_IN_CALL ? 0xFF34A853 : 0xFF8A8A8A;
                Long seen = firstSeen.get("audio");
                if (seen == null) firstSeen.put("audio", seen = System.currentTimeMillis());
                best.since = seen;
            }
        }
        if (best == null) firstSeen.clear();
        return best;
    }

    static String badge(Call c) {
        return "phone".equals(c.id) ? "☎" : Meetings.byId(c.id).badge();
    }

    static boolean micMuted(Context c) {
        AudioManager am = c.getSystemService(AudioManager.class);
        return am != null && am.isMicrophoneMute();
    }

    @SuppressWarnings("deprecation")
    static boolean speaker(Context c) {
        AudioManager am = c.getSystemService(AudioManager.class);
        return am != null && am.isSpeakerphoneOn();
    }

    /** "mute", "speaker", "hangup", "answer", "open". Returns an error to show, or null. */
    @SuppressWarnings("deprecation")
    static String act(Context c, Call call, String action) {
        AudioManager am = c.getSystemService(AudioManager.class);
        try {
            switch (action) {
                case "mute":
                    // the app's own button keeps its UI in sync; the system switch works for any app
                    if (call.mute != null) call.mute.actionIntent.send();
                    else if (am != null) am.setMicrophoneMute(!am.isMicrophoneMute());
                    return null;
                case "speaker":
                    if (am != null) am.setSpeakerphoneOn(!am.isSpeakerphoneOn());
                    return null;
                case "hangup":
                    if (call.hangup == null) return "Положить трубку можно только в самом приложении";
                    call.hangup.actionIntent.send();
                    return null;
                case "answer":
                    if (call.answer != null) call.answer.actionIntent.send();
                    return null;
                default:
                    if (call.open != null) call.open.send();
                    return null;
            }
        } catch (PendingIntent.CanceledException | SecurityException e) {
            return "Приложение не ответило";
        }
    }

    private static String label(Context c, String pkg) {
        try {
            return c.getPackageManager().getApplicationLabel(c.getPackageManager().getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return "Звонок";
        }
    }
}
