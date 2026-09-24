package com.deskdash;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** Day view like Google Calendar's: hour grid, coloured blocks, overlapping events side by side, "now" line. */
public class TimelineView extends View {
    public interface OnEventTap {
        void onTap(CalEvent e);
    }

    private final float dp, hourH, gutter, topPad, minH;
    private final Paint grid = new Paint();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint nowP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint label = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint title = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint sub = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final List<CalEvent> events = new ArrayList<>();
    private final List<RectF> rects = new ArrayList<>();

    private DashTheme th = DashTheme.ALL[0];
    private boolean googleColors = true;
    private DateTimeFormatter fmt = DateTimeFormatter.ofPattern("H:mm");
    private long dayStart;
    private OnEventTap tap;
    private float downX, downY;

    public TimelineView(Context c) {
        super(c);
        dp = getResources().getDisplayMetrics().density;
        hourH = 60 * dp;
        gutter = 38 * dp;
        topPad = 10 * dp;
        minH = 18 * dp;
        grid.setStrokeWidth(Math.max(1, dp * 0.6f));
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(2 * dp);
        nowP.setStrokeWidth(2 * dp);
        label.setTextSize(11 * dp);
        label.setTextAlign(Paint.Align.RIGHT);
        label.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        title.setTextSize(12.5f * dp);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        sub.setTextSize(11 * dp);
    }

    public void setTheme(DashTheme t, boolean useGoogleColors, boolean h24) {
        th = t;
        googleColors = useGoogleColors;
        fmt = DateTimeFormatter.ofPattern(h24 ? "H:mm" : "h:mm a");
        grid.setColor(t.line);
        label.setColor(t.dim);
        ring.setColor(t.accent);
        nowP.setColor(t.now);
        invalidate();
    }

    public void setOnEventTap(OnEventTap t) {
        tap = t;
    }

    public void setEvents(List<CalEvent> all, long dayStartMs) {
        events.clear();
        for (CalEvent e : all) if (!e.allDay) events.add(e);
        dayStart = dayStartMs;
        relayout();
        invalidate();
    }

    public float yForTime(long t) {
        return topPad + (t - dayStart) / 3_600_000f * hourH;
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(MeasureSpec.getSize(w), (int) (topPad * 2 + 24 * hourH));
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        relayout();
    }

    private long visEnd(CalEvent e) {
        long minMs = (long) (minH / hourH * 3_600_000f);
        return Math.max(e.end, e.start + minMs);
    }

    private void relayout() {
        rects.clear();
        int n = events.size();
        if (n == 0 || getWidth() == 0) return;
        float left = gutter + 2 * dp, right = getWidth() - 2 * dp, w = right - left;
        int[] col = new int[n], cols = new int[n];
        int i = 0;
        while (i < n) {
            List<Long> colEnds = new ArrayList<>();
            long clusterEnd = visEnd(events.get(i));
            int j = i;
            while (j < n && (j == i || events.get(j).start < clusterEnd)) {
                CalEvent e = events.get(j);
                int c = -1;
                for (int k = 0; k < colEnds.size(); k++) {
                    if (colEnds.get(k) <= e.start) {
                        c = k;
                        break;
                    }
                }
                if (c < 0) {
                    c = colEnds.size();
                    colEnds.add(0L);
                }
                colEnds.set(c, visEnd(e));
                col[j] = c;
                clusterEnd = Math.max(clusterEnd, visEnd(e));
                j++;
            }
            for (int k = i; k < j; k++) cols[k] = colEnds.size();
            i = j;
        }
        long dayEnd = dayStart + 24 * 3_600_000L;
        for (int k = 0; k < n; k++) {
            CalEvent e = events.get(k);
            float top = yForTime(Math.max(e.start, dayStart));
            float bottom = Math.max(top + minH, yForTime(Math.min(e.end, dayEnd)));
            float cw = w / cols[k];
            float l = left + col[k] * cw;
            rects.add(new RectF(l + 1 * dp, top + 1 * dp, l + cw - 1 * dp, bottom - 1 * dp));
        }
    }

    private String t(long ms) {
        return Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(fmt);
    }

    @Override
    protected void onDraw(Canvas c) {
        long now = System.currentTimeMillis();
        for (int h = 0; h <= 24; h++) {
            float y = topPad + h * hourH;
            c.drawLine(gutter, y, getWidth(), y, grid);
            if (h < 24) c.drawText(String.format("%02d", h), gutter - 8 * dp, y + label.getTextSize() * 0.36f, label);
        }
        float r = 7 * dp, pad = 7 * dp;
        for (int k = 0; k < rects.size(); k++) {
            CalEvent e = events.get(k);
            RectF rc = rects.get(k);
            int color = th.eventColor(e.color, e.title, googleColors);
            int alpha = e.end <= now ? 120 : 255;
            fill.setColor(color);
            fill.setAlpha(alpha);
            c.drawRoundRect(rc, r, r, fill);
            if (e.start <= now && now < e.end) c.drawRoundRect(rc, r, r, ring);

            int on = DashTheme.onColor(color);
            title.setColor(on);
            title.setAlpha(alpha);
            sub.setColor(on);
            sub.setAlpha(alpha * 190 / 255);
            float avail = rc.width() - 2 * pad;
            if (avail <= 0) continue;
            c.save();
            c.clipRect(rc);
            String time = t(e.start) + " – " + t(e.end);
            if (rc.height() >= 36 * dp) {
                float y = rc.top + pad + title.getTextSize() * 0.85f;
                c.drawText(ell(e.title, title, avail), rc.left + pad, y, title);
                y += sub.getTextSize() * 1.35f;
                c.drawText(ell(time, sub, avail), rc.left + pad, y, sub);
                if (!e.location.isEmpty() && rc.height() >= 58 * dp) {
                    y += sub.getTextSize() * 1.3f;
                    c.drawText(ell(e.location, sub, avail), rc.left + pad, y, sub);
                }
            } else {
                float y = rc.centerY() + title.getTextSize() * 0.35f;
                c.drawText(ell(e.title + ",  " + t(e.start), title, avail), rc.left + pad, y, title);
            }
            c.restore();
        }
        if (now >= dayStart && now < dayStart + 24 * 3_600_000L) {
            float y = yForTime(now);
            c.drawCircle(gutter, y, 4.5f * dp, nowP);
            c.drawLine(gutter, y, getWidth(), y, nowP);
        }
    }

    private static String ell(String s, TextPaint p, float w) {
        return TextUtils.ellipsize(s, p, w, TextUtils.TruncateAt.END).toString();
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getX();
                downY = ev.getY();
                return true;
            case MotionEvent.ACTION_UP:
                if (Math.abs(ev.getX() - downX) < 12 * dp && Math.abs(ev.getY() - downY) < 12 * dp) {
                    for (int k = rects.size() - 1; k >= 0; k--) {
                        if (rects.get(k).contains(ev.getX(), ev.getY())) {
                            if (tap != null) tap.onTap(events.get(k));
                            performClick();
                            break;
                        }
                    }
                }
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }
}
