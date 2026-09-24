package com.deskdash;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;

/** Month grid: 6 weeks × 7 days, each day shows coloured event bars; tap a day to open it. */
public class MonthView extends View {
    public interface Listener {
        void onDay(LocalDate day);

        void onSwipe(int dir);
    }

    private static final String[] WEEKDAYS = {"пн", "вт", "ср", "чт", "пт", "сб", "вс"};

    private final float dp;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint();
    private final TextPaint head = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint num = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint bar = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final GestureDetector gestures;

    private DashTheme th = DashTheme.ALL[0];
    private boolean googleColors = true;
    private YearMonth month = YearMonth.now();
    private LocalDate selected = LocalDate.now();
    private final List<List<CalEvent>> perDay = new ArrayList<>();
    private Listener listener;

    public MonthView(Context c) {
        super(c);
        dp = getResources().getDisplayMetrics().density;
        line.setStrokeWidth(Math.max(1, dp * 0.6f));
        head.setTextSize(11 * dp);
        head.setTextAlign(Paint.Align.CENTER);
        head.setLetterSpacing(0.1f);
        num.setTextSize(13 * dp);
        num.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        bar.setTextSize(9.5f * dp);
        gestures = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                LocalDate d = dayAt(e.getX(), e.getY());
                if (d != null && listener != null) listener.onDay(d);
                return true;
            }

            @Override
            public boolean onFling(MotionEvent a, MotionEvent b, float vx, float vy) {
                if (a == null || listener == null) return false;
                float dx = b.getX() - a.getX(), dy = b.getY() - a.getY();
                if (Math.abs(dx) > Math.abs(dy) && Math.abs(dx) > 60 * dp) listener.onSwipe(dx < 0 ? 1 : -1);
                else if (Math.abs(dy) > 60 * dp) listener.onSwipe(dy < 0 ? 1 : -1);
                return true;
            }
        });
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public void setTheme(DashTheme t, boolean useGoogleColors) {
        th = t;
        googleColors = useGoogleColors;
        line.setColor(t.line);
        head.setColor(t.dim);
        invalidate();
    }

    /** First day shown in the grid (Monday on or before the 1st). */
    public static LocalDate gridStart(YearMonth m) {
        return m.atDay(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    public void setMonth(YearMonth m, LocalDate selectedDay, List<CalEvent> events) {
        month = m;
        selected = selectedDay;
        perDay.clear();
        LocalDate d = gridStart(m);
        for (int i = 0; i < 42; i++, d = d.plusDays(1)) perDay.add(CalendarRepo.forDay(events, d));
        invalidate();
    }

    private float headH() {
        return 22 * dp;
    }

    private LocalDate dayAt(float x, float y) {
        if (y < headH()) return null;
        int col = (int) (x / (getWidth() / 7f));
        int row = (int) ((y - headH()) / ((getHeight() - headH()) / 6f));
        if (col < 0 || col > 6 || row < 0 || row > 5) return null;
        return gridStart(month).plusDays(row * 7L + col);
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth() / 7f, h = (getHeight() - headH()) / 6f;
        for (int i = 0; i < 7; i++) {
            head.setColor(i >= 5 ? th.accent : th.dim);
            c.drawText(WEEKDAYS[i], w * i + w / 2, headH() * 0.65f, head);
        }
        LocalDate today = LocalDate.now();
        LocalDate d = gridStart(month);
        float r = 5 * dp;
        for (int i = 0; i < 42; i++, d = d.plusDays(1)) {
            float x = (i % 7) * w, y = headH() + (i / 7) * h;
            c.drawLine(x, y, x + w, y, line);
            boolean inMonth = YearMonth.from(d).equals(month);

            if (d.equals(selected) && !d.equals(today)) {
                fill.setColor(th.surface);
                c.drawRoundRect(new RectF(x + 1 * dp, y + 1 * dp, x + w - 1 * dp, y + h - 1 * dp), r, r, fill);
            }
            String n = String.valueOf(d.getDayOfMonth());
            float nx = x + 5 * dp, ny = y + 4 * dp + num.getTextSize();
            if (d.equals(today)) {
                fill.setColor(th.accent);
                float cx = nx + num.measureText(n) / 2, cy = ny - num.getTextSize() * 0.36f;
                c.drawCircle(cx, cy, 10.5f * dp, fill);
                num.setColor(DashTheme.onColor(th.accent));
            } else {
                num.setColor(inMonth ? th.text : th.dim);
                num.setAlpha(inMonth ? 255 : 110);
            }
            c.drawText(n, nx, ny, num);

            List<CalEvent> evs = perDay.size() > i ? perDay.get(i) : new ArrayList<>();
            float by = ny + 4 * dp, bh = 12.5f * dp;
            int fits = Math.max(0, (int) ((y + h - by - 1 * dp) / (bh + 1.5f * dp)));
            int shown = Math.min(evs.size(), fits);
            if (evs.size() > shown) {
                bar.setColor(th.dim);
                bar.setTextAlign(Paint.Align.RIGHT);
                c.drawText("+" + (evs.size() - shown), x + w - 4 * dp, ny - 1 * dp, bar);
                bar.setTextAlign(Paint.Align.LEFT);
            }
            for (int k = 0; k < shown; k++) {
                CalEvent e = evs.get(k);
                int color = th.eventColor(e.color, e.title, googleColors);
                fill.setColor(color);
                fill.setAlpha(inMonth ? 255 : 120);
                RectF rc = new RectF(x + 2 * dp, by, x + w - 2 * dp, by + bh);
                c.drawRoundRect(rc, 3 * dp, 3 * dp, fill);
                bar.setColor(DashTheme.onColor(color));
                bar.setAlpha(inMonth ? 255 : 140);
                c.save();
                c.clipRect(rc);
                c.drawText(TextUtils.ellipsize(e.title, bar, rc.width() - 6 * dp, TextUtils.TruncateAt.END).toString(),
                        rc.left + 3 * dp, rc.bottom - 3.5f * dp, bar);
                c.restore();
                by += bh + 1.5f * dp;
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        boolean r = gestures.onTouchEvent(e);
        if (e.getActionMasked() == MotionEvent.ACTION_UP) performClick();
        return r;
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }
}
