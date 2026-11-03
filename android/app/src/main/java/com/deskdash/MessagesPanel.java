package com.deskdash;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Parcelable;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The "Сообщения" widget: unread chats from the notification shade (Telegram, WhatsApp, VK, SMS, mail…),
 * who and what, with "Ответить" right on the dashboard (the app's own quick reply) and "Прочитано".
 */
final class MessagesPanel extends LinearLayout {
    interface Listener {
        /** Opens the reply sheet for a message; the panel sends the text. */
        void onReply(Message m);

        void onSetup();
    }

    static final class Message {
        String key = "", pkg = "", app = "", who = "", text = "";
        long when;
        Drawable icon;
        PendingIntent open;
        Notification.Action reply, read;
    }

    private static final Pattern READ = Pattern.compile("(?i)(прочит|mark as read|read)");

    private final DashTheme th;
    private final float dp;
    private final Listener listener;
    private final LinearLayout body;
    private final TextView count;
    private String sig = "";

    MessagesPanel(Context c, DashTheme th, Listener l) {
        super(c);
        this.th = th;
        listener = l;
        dp = c.getResources().getDisplayMetrics().density;
        setOrientation(VERTICAL);
        count = new TextView(c);
        LayoutParams hl = new LayoutParams(-1, -2);
        hl.setMargins(0, 0, 0, px(10));
        addView(Ui.header(c, th, R.drawable.ic_phone, th.isNight() ? th.accent : Ui.GREEN, "Сообщения", count), hl);
        ScrollView sv = new ScrollView(c);
        sv.setVerticalScrollBarEnabled(false);
        sv.setOverScrollMode(OVER_SCROLL_NEVER);
        sv.setVerticalFadingEdgeEnabled(true);
        sv.setFadingEdgeLength(px(20));
        body = new LinearLayout(c);
        body.setOrientation(VERTICAL);
        sv.addView(body, new ScrollView.LayoutParams(-1, -2));
        addView(sv, new LayoutParams(-1, 0, 1));
    }

    /** "As tall as the content" blocks: a ScrollView with weight would collapse to 0. */
    @Override
    protected void onMeasure(int w, int h) {
        if (MeasureSpec.getMode(h) != MeasureSpec.EXACTLY) {
            LayoutParams lp = (LayoutParams) getChildAt(1).getLayoutParams();
            if (lp.weight > 0) {
                lp.height = -2;
                lp.weight = 0;
            }
        }
        super.onMeasure(w, h);
    }

    /** What is unread now, newest first. */
    static List<Message> unread(Context c) {
        List<Message> out = new ArrayList<>();
        for (StatusBarNotification sbn : MediaListenerService.active()) {
            Notification n = sbn.getNotification();
            if ((n.flags & Notification.FLAG_ONGOING_EVENT) != 0 || sbn.getPackageName().equals(c.getPackageName())) continue;
            if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) continue;
            Message m = new Message();
            if (n.actions != null) {
                for (Notification.Action a : n.actions) {
                    if (a.getRemoteInputs() != null && a.getRemoteInputs().length > 0) m.reply = a;
                    else if (a.title != null && READ.matcher(a.title).find()) m.read = a;
                }
            }
            boolean chat = Notification.CATEGORY_MESSAGE.equals(n.category) || Notification.CATEGORY_EMAIL.equals(n.category) || m.reply != null;
            if (!chat) continue;
            Bundle ex = n.extras;
            CharSequence title = ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE);
            if (title == null) title = ex.getCharSequence(Notification.EXTRA_TITLE);
            CharSequence text = ex.getCharSequence(Notification.EXTRA_TEXT);
            Parcelable[] msgs = ex.getParcelableArray(Notification.EXTRA_MESSAGES);
            if (msgs != null && msgs.length > 0 && msgs[msgs.length - 1] instanceof Bundle) {
                CharSequence last = ((Bundle) msgs[msgs.length - 1]).getCharSequence("text");
                if (last != null) text = last;
            }
            m.key = sbn.getKey();
            m.pkg = sbn.getPackageName();
            m.app = appName(c, m.pkg);
            m.who = title == null ? m.app : title.toString();
            m.text = text == null ? "" : text.toString();
            m.when = n.when > 0 ? n.when : sbn.getPostTime();
            m.open = n.contentIntent;
            try {
                if (n.getSmallIcon() != null) m.icon = n.getSmallIcon().loadDrawable(c);
            } catch (RuntimeException ignored) {
                // the app's resources are out of reach
            }
            out.add(m);
        }
        out.sort((a, b) -> Long.compare(b.when, a.when));
        return out;
    }

    void render(boolean access) {
        if (!access) {
            if ("noaccess".equals(sig)) return;
            sig = "noaccess";
            body.removeAllViews();
            TextView t = Ui.text(getContext(), th.dim, 15, 400);
            t.setText("Один переключатель — и здесь будут непрочитанные из Telegram, WhatsApp, SMS и почты с ответом");
            t.setOnClickListener(v -> listener.onSetup());
            body.addView(t);
            count.setText("");
            return;
        }
        List<Message> list = unread(getContext());
        StringBuilder s = new StringBuilder();
        for (Message m : list) s.append(m.key).append(m.text).append('|');
        if (s.toString().equals(sig)) return;
        sig = s.toString();
        body.removeAllViews();
        count.setText(list.isEmpty() ? "" : String.valueOf(list.size()));
        if (list.isEmpty()) {
            TextView t = Ui.text(getContext(), th.dim, 15, 400);
            t.setText("Всё прочитано");
            body.addView(t);
            return;
        }
        for (Message m : list) body.addView(row(m), lp(0, 8));
    }

    private View row(Message m) {
        LinearLayout r = new LinearLayout(getContext());
        r.setOrientation(VERTICAL);
        r.setPadding(px(12), px(10), px(12), px(10));
        r.setBackground(Ui.round(getContext(), Ui.fill(th), 14));
        LinearLayout top = new LinearLayout(getContext());
        top.setGravity(Gravity.CENTER_VERTICAL);
        if (m.icon != null) {
            ImageView ic = new ImageView(getContext());
            m.icon.setTint(th.dim);
            ic.setImageDrawable(m.icon);
            top.addView(ic, new LayoutParams(px(16), px(16)));
        }
        TextView who = Ui.text(getContext(), th.text, 15, 600);
        who.setSingleLine();
        who.setEllipsize(TextUtils.TruncateAt.END);
        who.setText(m.who);
        LayoutParams wl = new LayoutParams(0, -2, 1);
        wl.leftMargin = px(8);
        top.addView(who, wl);
        TextView at = Ui.text(getContext(), th.dim, 12, 400);
        at.setText(m.app + " · " + Instant.ofEpochMilli(m.when).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("H:mm")));
        top.addView(at);
        r.addView(top);
        TextView text = Ui.text(getContext(), th.text, 14, 400);
        text.setMaxLines(2);
        text.setEllipsize(TextUtils.TruncateAt.END);
        text.setText(m.text);
        r.addView(text, lp(4, 0));
        LinearLayout btns = new LinearLayout(getContext());
        if (m.reply != null) btns.addView(Ui.pill(getContext(), "Ответить", th.accent, DashTheme.onColor(th.accent), v -> listener.onReply(m)), btnLp());
        if (m.read != null) btns.addView(Ui.pill(getContext(), "Прочитано", th.surface, th.text, v -> {
            send(getContext(), m.read, null);
            sig = "";
        }), btnLp());
        if (btns.getChildCount() > 0) r.addView(btns, lp(8, 0));
        r.setOnClickListener(v -> send(getContext(), null, m.open));
        return r;
    }

    /** Sends a quick reply through the app's own reply button. Returns an error to show, or null. */
    static String reply(Context c, Message m, String text) {
        if (m.reply == null) return "Это приложение не даёт отвечать из уведомления";
        RemoteInput[] inputs = m.reply.getRemoteInputs();
        Intent data = new Intent();
        Bundle results = new Bundle();
        for (RemoteInput ri : inputs) results.putCharSequence(ri.getResultKey(), text);
        RemoteInput.addResultsToIntent(inputs, data, results);
        try {
            m.reply.actionIntent.send(c, 0, data);
            return null;
        } catch (PendingIntent.CanceledException e) {
            return "Сообщение уже неактуально";
        }
    }

    private static void send(Context c, Notification.Action a, PendingIntent open) {
        try {
            if (a != null) a.actionIntent.send();
            else if (open != null) open.send();
        } catch (PendingIntent.CanceledException ignored) {
            // gone already
        }
    }

    private static String appName(Context c, String pkg) {
        try {
            return c.getPackageManager().getApplicationLabel(c.getPackageManager().getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg.contains("telegram") ? "Telegram" : pkg.contains("whatsapp") ? "WhatsApp" : "Сообщение";
        }
    }

    private LayoutParams btnLp() {
        LayoutParams l = new LayoutParams(-2, -2);
        l.rightMargin = px(6);
        return l;
    }

    private LayoutParams lp(float top, float bottom) {
        LayoutParams l = new LayoutParams(-1, -2);
        l.setMargins(0, px(top), 0, px(bottom));
        return l;
    }

    private int px(float v) {
        return Math.round(v * dp);
    }
}
