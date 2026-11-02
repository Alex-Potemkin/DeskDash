package com.deskdash;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The "Лента дня" look: the next hours as a horizontal strip. Events are bars in lanes (calls with the
 * colour of their service), a line marks now, and the forecast for every other hour runs underneath.
 */
final class RibbonView extends View {
    interface Listener {
        void onEvent(CalEvent e);
    }

    private static final int HOURS = 14, BEFORE = 2;

    private final float dp;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private DashTheme th;
    private boolean googleColors, h24 = true, night;
    private List<CalEvent> events = new ArrayList<>();
    private Weather weather;
    private WeatherPanel.Icon icon;
    private Listener listener;
    private final List<Object[]> hits = new ArrayList<>(); // {RectF, CalEvent}

    RibbonView(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
    }

    void setTheme(DashTheme th, boolean googleColors, boolean h24, boolean night) {
        this.th = th;
        this.googleColors = googleColors;
        this.h24 = h24;
        this.night = night;
        icon = new WeatherPanel.Icon(getContext(), th, night);
        invalidate();
    }

    void setListener(Listener l) {
        listener = l;
    }

    void setData(List<CalEvent> events, Weather w) {
        this.events = events == null ? new ArrayList<>() : events;
        weather = w;
        invalidate();
    }

    private long windowStart() {
        long hour = 3_600_000L, now = System.currentTimeMillis();
        long local = now + ZoneId.systemDefault().getRules().getOffset(Instant.ofEpochMilli(now)).getTotalSeconds() * 1000L;
        return now - local % hour - BEFORE * hour;
    }

    @Override
    protected void onDraw(Canvas c) {
        if (th == null) return;
        hits.clear();
        float w = getWidth(), h = getHeight();
        long start = windowStart(), end = start + HOURS * 3_600_000L, now = System.currentTimeMillis();
        float hw = w / HOURS, top = 20 * dp, wxH = weather == null ? 0 : 50 * dp, bottom = h - wxH - 6 * dp;

        // hours
        tp.setTextSize(11 * dp);
        tp.setColor(th.dim);
        tp.setTextAlign(Paint.Align.LEFT);
        p.setStrokeWidth(Math.max(1, dp));
        for (int i = 0; i <= HOURS; i++) {
            float x = i * hw;
            p.setColor(th.line);
            c.drawLine(x, top, x, bottom, p);
            if (i < HOURS) c.drawText(time(start + i * 3_600_000L, true), x + 4 * dp, 13 * dp, tp);
        }
        // the past is quieter
        float nx = (now - start) * w / (end - start);
        p.setColor(th.bg);
        p.setAlpha(110);
        c.drawRect(0, top, nx, bottom, p);
        p.setAlpha(255);

        // events in lanes
        List<CalEvent> timed = new ArrayList<>();
        for (CalEvent e : events) if (!e.allDay && e.end > start && e.start < end) timed.add(e);
        List<Long> laneEnds = new ArrayList<>();
        int[] lane = new int[timed.size()];
        for (int i = 0; i < timed.size(); i++) {
            CalEvent e = timed.get(i);
            int l = 0;
            while (l < laneEnds.size() && laneEnds.get(l) > e.start) l++;
            if (l == laneEnds.size()) laneEnds.add(e.end);
            else laneEnds.set(l, e.end);
            lane[i] = l;
        }
        int lanes = Math.max(2, laneEnds.size());
        float laneH = Math.min(64 * dp, (bottom - top - 8 * dp) / lanes), gap = 4 * dp;
        for (int i = 0; i < timed.size(); i++) {
            CalEvent e = timed.get(i);
            float x0 = Math.max(0, (e.start - start) * w / (end - start)), x1 = Math.min(w, (e.end - start) * w / (end - start));
            float y0 = top + 6 * dp + lane[i] * laneH, y1 = y0 + laneH - gap;
            Meetings.Meeting m = Meetings.of(e);
            int color = th.eventColor(e.color, e.title, googleColors);
            boolean past = e.end <= now;
            RectF r = new RectF(x0 + 1.5f * dp, y0, x1 - 1.5f * dp, y1);
            p.setColor(TimelineView.eventFill(th, m != null ? m.platform.color | 0xFF000000 : color, e.start <= now && now < e.end));
            p.setAlpha(past ? 130 : 255);
            c.drawRoundRect(r, 8 * dp, 8 * dp, p);
            c.save();
            c.clipRect(r.left, r.top, r.left + 4 * dp, r.bottom);
            p.setColor(m != null ? m.platform.color | 0xFF000000 : color);
            p.setAlpha(past ? 130 : 255);
            c.drawRoundRect(r, 8 * dp, 8 * dp, p);
            c.restore();
            p.setAlpha(255);
            hits.add(new Object[]{r, e});
            float tw = r.width() - 16 * dp;
            if (tw > 18 * dp) {
                int fg = TimelineView.eventText(th, m != null ? m.platform.color | 0xFF000000 : color);
                tp.setColor(past ? DashTheme.blend(fg, th.bg, 0.4f) : fg);
                tp.setTypeface(Ui.font(600));
                tp.setTextSize(13 * dp);
                CharSequence title = TextUtils.ellipsize(e.title, tp, tw, TextUtils.TruncateAt.END);
                c.drawText(title, 0, title.length(), r.left + 10 * dp, r.top + 18 * dp, tp);
                if (r.height() > 36 * dp) {
                    tp.setTextSize(11 * dp);
                    tp.setTypeface(Ui.font(400));
                    tp.setAlpha(190);
                    String sub = time(e.start, false) + "–" + time(e.end, false) + (m != null ? " · " + m.platform.name : "");
                    CharSequence s2 = TextUtils.ellipsize(sub, tp, tw, TextUtils.TruncateAt.END);
                    c.drawText(s2, 0, s2.length(), r.left + 10 * dp, r.top + 33 * dp, tp);
                    tp.setAlpha(255);
                }
            }
        }
        if (timed.isEmpty()) {
            tp.setColor(th.dim);
            tp.setTextSize(14 * dp);
            tp.setTextAlign(Paint.Align.CENTER);
            c.drawText("Ближайшие часы свободны", w / 2, (top + bottom) / 2, tp);
            tp.setTextAlign(Paint.Align.LEFT);
        }

        // now
        p.setColor(th.now);
        p.setStrokeWidth(2 * dp);
        c.drawLine(nx, top - 2 * dp, nx, bottom, p);
        c.drawCircle(nx, top - 2 * dp, 4 * dp, p);

        // weather under the strip, every other hour
        if (weather != null && icon != null) {
            float wy = bottom + 6 * dp, is = 22 * dp;
            tp.setTextSize(12 * dp);
            tp.setColor(th.text);
            tp.setTextAlign(Paint.Align.CENTER);
            for (Weather.Hour hr : weather.hours) {
                if (hr.time < start || hr.time >= end) continue;
                int idx = (int) ((hr.time - start) / 3_600_000L);
                if (idx % 2 != 0) continue;
                float cx = (idx + 0.5f) * hw;
                icon.set(hr.code, weather.dayAt(hr.time + 1_800_000L));
                c.save();
                c.translate(cx - is - 2 * dp, wy + 4 * dp);
                icon.paint(c, is);
                c.restore();
                tp.setTextAlign(Paint.Align.LEFT);
                c.drawText(Weather.deg(hr.temp), cx + 2 * dp, wy + 20 * dp, tp);
            }
            tp.setTextAlign(Paint.Align.LEFT);
        }
    }

    private String time(long ms, boolean hourOnly) {
        String pat = hourOnly ? (h24 ? "H:00" : "h a") : (h24 ? "H:mm" : "h:mm");
        return Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern(pat, Locale.ENGLISH));
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) return !hits.isEmpty();
        if (e.getActionMasked() == MotionEvent.ACTION_UP && listener != null) {
            for (Object[] h : hits) {
                if (((RectF) h[0]).contains(e.getX(), e.getY())) {
                    performClick();
                    listener.onEvent((CalEvent) h[1]);
                    return true;
                }
            }
        }
        return true;
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }
}
