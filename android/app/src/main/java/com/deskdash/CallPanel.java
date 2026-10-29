package com.deskdash;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The "Созвоны" block. Live call on the PC: who, how long, mic / camera / share / hand / leave and the
 * PC screen on the phone. Otherwise the next call from the calendar with a countdown and "join" buttons,
 * then the rest of the day's calls. Links of every service come from the calendar (Meetings).
 */
final class CallPanel extends LinearLayout {
    interface Listener {
        void onJoin(Meetings.Meeting m, boolean onPc);

        void onCallAction(PcClient.Call c, String action);

        void onScreen();

        void onSetup();
    }

    private final DashTheme th;
    private final float dp;
    private final Listener listener;
    private final LinearLayout body;
    private final TextView count;
    private final List<Runnable> tickers = new ArrayList<>();
    private boolean leaveArmed;

    CallPanel(Context c, DashTheme th, Listener l) {
        super(c);
        this.th = th;
        listener = l;
        dp = c.getResources().getDisplayMetrics().density;
        setOrientation(VERTICAL);

        LinearLayout head = new LinearLayout(c);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = text(th.dim, 12);
        t.setText("СОЗВОНЫ");
        t.setLetterSpacing(0.16f);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        head.addView(t);
        count = text(th.dim, 12);
        count.setSingleLine();
        head.addView(count, new LayoutParams(0, -2, 1));
        LayoutParams hl = new LayoutParams(-1, -2);
        hl.setMargins(px(4), px(4), 0, px(8));
        addView(head, hl);

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

    /** Called every second: timers and countdowns. */
    void tick() {
        for (Runnable r : tickers) r.run();
    }

    void render(List<Meetings.Meeting> today, PcClient.Calls live, boolean pcOnline, boolean canStream, int remindMin) {
        body.removeAllViews();
        tickers.clear();
        long now = System.currentTimeMillis();
        List<Meetings.Meeting> left = new ArrayList<>();
        for (Meetings.Meeting m : today) if (m.event.end > now) left.add(m);
        count.setText(today.isEmpty() ? "" : "  · " + today.size() + " сегодня" + (left.size() < today.size() && !left.isEmpty() ? ", ещё " + left.size() : ""));

        PcClient.Call call = live == null || live.calls.isEmpty() ? null : live.calls.get(0);
        Meetings.Meeting next = null;
        for (Meetings.Meeting m : left) {
            next = m;
            break;
        }
        if (call != null) {
            body.addView(liveCard(call, live.micMuted, canStream, matching(today, call)), lp(0, 8));
        } else if (next != null) {
            body.addView(nextCard(next, now, remindMin, pcOnline), lp(0, 8));
        }
        boolean first = true;
        for (Meetings.Meeting m : today) {
            if (call == null && m == next) continue;
            if (first) {
                first = false;
                TextView cap = text(th.dim, 11);
                cap.setText(call == null && next == null ? "ПРОШЛИ" : "ЕЩЁ СЕГОДНЯ");
                cap.setLetterSpacing(0.12f);
                body.addView(cap, lp(6, 4));
            }
            body.addView(row(m, now, pcOnline), lp(0, 4));
        }
        if (call == null && today.isEmpty()) {
            TextView e = text(th.dim, 13);
            e.setGravity(Gravity.CENTER);
            e.setLineSpacing(0, 1.15f);
            e.setPadding(px(14), px(14), px(14), px(14));
            e.setBackground(round(th.surface, 16));
            e.setText(pcOnline ? "Сегодня созвонов нет.\nZoom, Meet, Teams, Телемост и другие\nпоявятся здесь из календаря"
                    : "Сегодня созвонов нет.\nЗвонки на ПК видны, когда подключён DeskDash.exe");
            if (!pcOnline) e.setOnClickListener(v -> listener.onSetup());
            body.addView(e, lp(0, 4));
            if (canStream) body.addView(chip("Экран ПК на телефоне", th.surface, th.text, v -> listener.onScreen()), lp(8, 0));
        }
    }

    // ---------------------------------------------------------------- cards

    /** The calendar event that is this call (same service, happening now), for its title. */
    private static Meetings.Meeting matching(List<Meetings.Meeting> today, PcClient.Call c) {
        long now = System.currentTimeMillis();
        for (Meetings.Meeting m : today) if (m.platform.id.equals(c.id) && m.now(now + 10 * 60_000L)) return m;
        return null;
    }

    private View liveCard(PcClient.Call c, Boolean muted, boolean canStream, Meetings.Meeting m) {
        LinearLayout card = vertical();
        card.setPadding(px(12), px(10), px(12), px(10));
        GradientDrawable bg = round(DashTheme.blend(th.surface, c.color, 0.22f), 18);
        bg.setStroke(px(1.5f), DashTheme.blend(th.surface, c.color, 0.7f));
        card.setBackground(bg);

        LinearLayout top = new LinearLayout(getContext());
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(badge(Meetings.byId(c.id).badge(), c.color, 34), new LayoutParams(px(34), px(34)));
        LinearLayout names = vertical();
        TextView title = text(th.text, 15);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setSingleLine();
        title.setEllipsize(TextUtils.TruncateAt.END);
        String t = m != null ? m.event.title : c.title;
        title.setText(t.isEmpty() ? c.name : t);
        names.addView(title);
        TextView sub = text(th.dim, 12);
        sub.setSingleLine();
        sub.setText("● Идёт · " + c.name + (c.camera ? " · камера вкл" : ""));
        sub.setTextColor(DashTheme.blend(th.dim, c.color, 0.5f));
        names.addView(sub);
        top.addView(names, new LayoutParams(0, -2, 1));
        LayoutParams nl = (LayoutParams) names.getLayoutParams();
        nl.leftMargin = px(12);
        TextView timer = text(th.text, 22);
        timer.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        Runnable upd = () -> timer.setText(clock((System.currentTimeMillis() - c.since) / 1000));
        upd.run();
        tickers.add(upd);
        top.addView(timer);
        card.addView(top);

        LinearLayout btns = new LinearLayout(getContext());
        btns.setGravity(Gravity.CENTER_VERTICAL);
        if (muted != null) {
            boolean mu = muted;
            btns.addView(round("Мик " + (mu ? "выкл" : "вкл"), mu ? 0xFFE5534B : th.bg, mu ? 0xFFFFFFFF : th.text,
                    v -> listener.onCallAction(c, mu ? "unmute" : "mute")), btnLp());
        }
        if (c.actions.contains("camera")) {
            btns.addView(round(c.camera ? "Камера вкл" : "Камера выкл", th.bg, th.text, v -> listener.onCallAction(c, "camera")), btnLp());
        }
        if (c.actions.contains("share")) btns.addView(round("Экран", th.bg, th.text, v -> listener.onCallAction(c, "share")), btnLp());
        if (c.actions.contains("hand")) btns.addView(round("✋", th.bg, th.text, v -> listener.onCallAction(c, "hand")), btnLp());
        if (canStream) btns.addView(round("На телефон", th.accent, DashTheme.onColor(th.accent), v -> listener.onScreen()), btnLp());
        if (c.actions.contains("leave")) {
            TextView leave = round("Выйти", 0x33E5534B, 0xFFE5534B, null);
            leave.setOnClickListener(v -> {
                if (!leaveArmed) {
                    leaveArmed = true;
                    leave.setText("Точно?");
                    leave.postDelayed(() -> {
                        leaveArmed = false;
                        leave.setText("Выйти");
                    }, 3000);
                    return;
                }
                leaveArmed = false;
                listener.onCallAction(c, "leave");
            });
            btns.addView(leave, btnLp());
        }
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(getContext());
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(btns);
        card.addView(hs, lp(8, 0));
        card.setOnClickListener(v -> listener.onCallAction(c, "show"));
        return card;
    }

    private View nextCard(Meetings.Meeting m, long now, int remindMin, boolean pcOnline) {
        LinearLayout card = vertical();
        card.setPadding(px(14), px(12), px(14), px(12));
        boolean soon = m.event.start - now <= remindMin * 60_000L;
        GradientDrawable bg = round(soon ? DashTheme.blend(th.surface, m.platform.color, 0.18f) : th.surface, 18);
        if (soon) bg.setStroke(px(1.5f), DashTheme.blend(th.surface, m.platform.color, 0.6f));
        card.setBackground(bg);

        LinearLayout top = new LinearLayout(getContext());
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(badge(m.platform.badge(), m.platform.color, 40), new LayoutParams(px(40), px(40)));
        LinearLayout names = vertical();
        TextView title = text(th.text, 16);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setText(m.event.title);
        names.addView(title);
        TextView sub = text(th.dim, 12);
        sub.setSingleLine();
        sub.setText(time(m.event.start) + "–" + time(m.event.end) + " · " + m.platform.name);
        names.addView(sub);
        LayoutParams nl = new LayoutParams(0, -2, 1);
        nl.leftMargin = px(12);
        top.addView(names, nl);
        card.addView(top);

        TextView when = text(soon ? th.accent : th.text, 20);
        when.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        Runnable upd = () -> when.setText(countdown(m, System.currentTimeMillis()));
        upd.run();
        tickers.add(upd);
        card.addView(when, lp(10, 0));

        LinearLayout btns = new LinearLayout(getContext());
        if (pcOnline) btns.addView(round("Подключиться на ПК", th.accent, DashTheme.onColor(th.accent), v -> listener.onJoin(m, true)), btnLp());
        btns.addView(round(pcOnline ? "С телефона" : "Подключиться", pcOnline ? th.bg : th.accent,
                pcOnline ? th.text : DashTheme.onColor(th.accent), v -> listener.onJoin(m, false)), btnLp());
        card.addView(btns, lp(10, 0));
        return card;
    }

    private View row(Meetings.Meeting m, long now, boolean pcOnline) {
        LinearLayout r = new LinearLayout(getContext());
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(px(10), px(8), px(10), px(8));
        r.setBackground(round(th.surface, 14));
        boolean past = m.event.end <= now;
        r.setAlpha(past ? 0.5f : 1f);
        TextView tm = text(th.dim, 13);
        tm.setText(time(m.event.start));
        tm.setMinWidth(px(44));
        r.addView(tm);
        r.addView(badge(m.platform.badge(), m.platform.color, 22), new LayoutParams(px(22), px(22)));
        TextView title = text(th.text, 14);
        title.setSingleLine();
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setText(m.event.title);
        LayoutParams tl = new LayoutParams(0, -2, 1);
        tl.leftMargin = px(10);
        r.addView(title, tl);
        TextView p = text(th.dim, 11);
        p.setText(m.platform.name);
        r.addView(p);
        if (!past) r.setOnClickListener(v -> listener.onJoin(m, pcOnline));
        return r;
    }

    // ---------------------------------------------------------------- text

    static String countdown(Meetings.Meeting m, long now) {
        if (m.event.start > now) {
            long min = (m.event.start - now + 59_999) / 60_000;
            return min < 60 ? "через " + min + " мин" : "через " + HomeDevice.minutes((int) min);
        }
        long min = (now - m.event.start) / 60_000;
        return min == 0 ? "начинается сейчас" : "идёт " + min + " мин";
    }

    static String clock(long s) {
        s = Math.max(0, s);
        return s >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                : String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    private static String time(long ms) {
        return Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("H:mm"));
    }

    // ---------------------------------------------------------------- helpers

    private TextView badge(String s, int color, int sizeDp) {
        TextView b = text(DashTheme.onColor(color), sizeDp * 0.38f);
        b.setText(s);
        b.setGravity(Gravity.CENTER);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color | 0xFF000000);
        b.setBackground(g);
        return b;
    }

    private TextView round(String label, int bg, int fg, View.OnClickListener l) {
        TextView t = text(fg, 13);
        t.setText(label);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine();
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setPadding(px(13), px(7), px(13), px(7));
        t.setBackground(round(bg, 18));
        if (l != null) {
            t.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                l.onClick(v);
            });
        }
        return t;
    }

    private TextView chip(String label, int bg, int fg, View.OnClickListener l) {
        TextView t = round(label, bg, fg, l);
        t.setGravity(Gravity.CENTER);
        return t;
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

    private LinearLayout vertical() {
        LinearLayout l = new LinearLayout(getContext());
        l.setOrientation(VERTICAL);
        return l;
    }

    private TextView text(int color, float sp) {
        TextView t = new TextView(getContext());
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        return t;
    }

    private GradientDrawable round(int color, float r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(r * dp);
        return g;
    }

    private int px(float v) {
        return Math.round(v * dp);
    }
}
