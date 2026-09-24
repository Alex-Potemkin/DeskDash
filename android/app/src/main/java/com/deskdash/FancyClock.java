package com.deskdash;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import java.time.LocalTime;
import java.util.Locale;

/** The non-digital clock faces. The plain digital clock stays a TextView in DashboardView. */
public class FancyClock extends View {
    public static final String[][] STYLES = {
            {"digital", "Цифровые"}, {"analog", "Стрелочные"}, {"flip", "Перекидные"},
            {"stacked", "Столбиком"}, {"words", "Словами"}, {"ring", "Кольцо дня"},
    };

    private static final String[] HOURS = {"ноль", "один", "два", "три", "четыре", "пять", "шесть", "семь",
            "восемь", "девять", "десять", "одиннадцать", "двенадцать", "тринадцать", "четырнадцать",
            "пятнадцать", "шестнадцать", "семнадцать", "восемнадцать", "девятнадцать", "двадцать",
            "двадцать один", "двадцать два", "двадцать три"};
    private static final String[] UNITS_F = {"", "одна", "две", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять"};
    private static final String[] TEENS = {"десять", "одиннадцать", "двенадцать", "тринадцать", "четырнадцать",
            "пятнадцать", "шестнадцать", "семнадцать", "восемнадцать", "девятнадцать"};
    private static final String[] TENS = {"", "", "двадцать", "тридцать", "сорок", "пятьдесят"};

    private final float dp;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
    private String style = "analog";
    private DashTheme th = DashTheme.ALL[0];
    private Typeface face = Typeface.DEFAULT;
    private boolean h24 = true, seconds;
    private LocalTime time = LocalTime.now();
    private String oldH, oldM;
    private float flip = 1f; // 0..1 progress of the flip-card animation
    private boolean flipH, flipM;

    public FancyClock(Context c) {
        super(c);
        dp = getResources().getDisplayMetrics().density;
        p.setStrokeCap(Paint.Cap.ROUND);
    }

    public static String name(String id) {
        for (String[] s : STYLES) if (s[0].equals(id)) return s[1];
        return STYLES[0][1];
    }

    public static String next(String id) {
        for (int i = 0; i < STYLES.length; i++) if (STYLES[i][0].equals(id)) return STYLES[(i + 1) % STYLES.length][0];
        return STYLES[0][0];
    }

    public void setup(String style, DashTheme th, Typeface face, boolean h24, boolean seconds) {
        this.style = style;
        this.th = th;
        this.face = face;
        this.h24 = h24;
        this.seconds = seconds;
        invalidate();
    }

    public void setTime(LocalTime now) {
        String h = hh(now), m = mm(now);
        if ("flip".equals(style) && oldH != null && (!h.equals(hh(time)) || !m.equals(mm(time)))) {
            flipH = !h.equals(hh(time));
            flipM = !m.equals(mm(time));
            oldH = hh(time);
            oldM = mm(time);
            ValueAnimator a = ValueAnimator.ofFloat(0f, 1f).setDuration(650);
            a.setInterpolator(new DecelerateInterpolator());
            a.addUpdateListener(v -> {
                flip = (float) v.getAnimatedValue();
                invalidate();
            });
            a.start();
        }
        if (oldH == null) {
            oldH = h;
            oldM = m;
        }
        time = now;
        invalidate();
    }

    private String hh(LocalTime x) {
        int h = h24 ? x.getHour() : (x.getHour() % 12 == 0 ? 12 : x.getHour() % 12);
        return h24 ? String.format(Locale.ROOT, "%02d", h) : String.valueOf(h);
    }

    private static String mm(LocalTime x) {
        return String.format(Locale.ROOT, "%02d", x.getMinute());
    }

    @Override
    protected void onDraw(Canvas c) {
        switch (style) {
            case "flip": drawFlip(c); break;
            case "stacked": drawStacked(c); break;
            case "words": drawWords(c); break;
            case "ring": drawRing(c); break;
            default: drawAnalog(c); break;
        }
    }

    private void drawAnalog(Canvas c) {
        float size = Math.min(getWidth(), getHeight());
        float r = size / 2 - 4 * dp, cx = size / 2 + 2 * dp, cy = getHeight() / 2f;
        p.setStyle(Paint.Style.STROKE);
        for (int i = 0; i < 60; i++) {
            double a = Math.toRadians(i * 6);
            boolean hour = i % 5 == 0, major = i % 15 == 0;
            if (!hour && r < 90 * dp) continue;
            float len = major ? r * 0.12f : hour ? r * 0.07f : r * 0.02f;
            p.setStrokeWidth(major ? 3 * dp : hour ? 2 * dp : 1.2f * dp);
            p.setColor(major ? th.text : th.dim);
            c.drawLine(cx + (float) Math.sin(a) * (r - len), cy - (float) Math.cos(a) * (r - len),
                    cx + (float) Math.sin(a) * r, cy - (float) Math.cos(a) * r, p);
        }
        float sec = time.getSecond() + time.getNano() / 1e9f;
        hand(c, cx, cy, (time.getHour() % 12 + time.getMinute() / 60f) * 30, r * 0.5f, 6 * dp, th.text);
        hand(c, cx, cy, (time.getMinute() + sec / 60f) * 6, r * 0.78f, 3.5f * dp, th.text);
        if (seconds) hand(c, cx, cy, time.getSecond() * 6, r * 0.86f, 1.5f * dp, th.accent);
        p.setStyle(Paint.Style.FILL);
        p.setColor(th.text);
        c.drawCircle(cx, cy, 6 * dp, p);
        p.setColor(th.accent);
        c.drawCircle(cx, cy, 3 * dp, p);
    }

    private void hand(Canvas c, float cx, float cy, float deg, float len, float width, int color) {
        double a = Math.toRadians(deg);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(width);
        p.setColor(color);
        c.drawLine(cx - (float) Math.sin(a) * len * 0.12f, cy + (float) Math.cos(a) * len * 0.12f,
                cx + (float) Math.sin(a) * len, cy - (float) Math.cos(a) * len, p);
    }

    private void drawFlip(Canvas c) {
        float gap = 12 * dp;
        float cw = Math.min((getWidth() - gap) / 2f * 0.92f, getHeight() * 1.05f);
        float ch = Math.min(getHeight() * 0.96f, cw * 0.92f);
        float top = (getHeight() - ch) / 2;
        card(c, 0, top, cw, ch, hh(time), oldH, flipH);
        card(c, cw + gap, top, cw, ch, mm(time), oldM, flipM);
        if (!h24) {
            t.setTypeface(face);
            t.setTextSize(ch * 0.14f);
            t.setColor(th.dim);
            t.setTextAlign(Paint.Align.LEFT);
            c.drawText(time.getHour() < 12 ? "AM" : "PM", cw * 2 + gap + 8 * dp, top + ch, t);
        }
    }

    private void card(Canvas c, float x, float y, float w, float h, String now, String old, boolean animate) {
        RectF rc = new RectF(x, y, x + w, y + h);
        p.setStyle(Paint.Style.FILL);
        p.setColor(th.surface);
        c.drawRoundRect(rc, 14 * dp, 14 * dp, p);
        t.setTypeface(face);
        t.setTextAlign(Paint.Align.CENTER);
        t.setTextSize(h * 0.72f);
        float base = y + h / 2 - (t.descent() + t.ascent()) / 2;
        c.save();
        c.clipRect(rc);
        if (animate && flip < 1f) {
            t.setColor(th.text);
            t.setAlpha((int) (255 * (1 - flip)));
            c.drawText(old, x + w / 2, base + flip * h * 0.55f, t);
            t.setAlpha((int) (255 * flip));
            c.drawText(now, x + w / 2, base - (1 - flip) * h * 0.55f, t);
            t.setAlpha(255);
        } else {
            t.setColor(th.text);
            c.drawText(now, x + w / 2, base, t);
        }
        c.restore();
        p.setColor(th.bg);
        p.setStrokeWidth(2.5f * dp);
        p.setStyle(Paint.Style.STROKE);
        c.drawLine(x, y + h / 2, x + w, y + h / 2, p);
    }

    private void drawStacked(Canvas c) {
        t.setTypeface(face);
        t.setTextAlign(Paint.Align.LEFT);
        float size = Math.min(getHeight() * 0.53f, getWidth() * 0.85f / 1.25f);
        t.setTextSize(size);
        float y1 = getHeight() / 2f - size * 0.08f;
        t.setColor(th.text);
        c.drawText(hh(time), -size * 0.04f, y1, t);
        t.setColor(th.accent);
        c.drawText(mm(time), -size * 0.04f, y1 + size * 0.9f, t);
    }

    private void drawWords(Canvas c) {
        int h = time.getHour();
        String hw = h24 ? HOURS[h] : HOURS[h % 12 == 0 ? 12 : h % 12];
        String mw = minuteWords(time.getMinute());
        t.setTypeface(face);
        t.setTextAlign(Paint.Align.LEFT);
        t.setTextSize(100);
        float widest = Math.max(t.measureText(hw), t.measureText(mw));
        float size = Math.min(100 * getWidth() * 0.96f / widest, getHeight() / 2.3f);
        t.setTextSize(size);
        float y1 = getHeight() / 2f - size * 0.12f;
        t.setColor(th.text);
        c.drawText(hw, 0, y1, t);
        t.setColor(th.accent);
        c.drawText(mw, 0, y1 + size * 1.1f, t);
    }

    static String minuteWords(int m) {
        if (m == 0) return "ровно";
        if (m < 10) return "ноль " + UNITS_F[m];
        if (m < 20) return TEENS[m - 10];
        return m % 10 == 0 ? TENS[m / 10] : TENS[m / 10] + " " + UNITS_F[m % 10];
    }

    private void drawRing(Canvas c) {
        float size = Math.min(getWidth(), getHeight());
        float stroke = Math.max(5 * dp, size * 0.035f);
        float r = size / 2 - stroke, cx = size / 2, cy = getHeight() / 2f;
        RectF oval = new RectF(cx - r, cy - r, cx + r, cy + r);
        float frac = time.toSecondOfDay() / 86400f;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(stroke);
        p.setColor(th.line);
        c.drawArc(oval, 0, 360, false, p);
        p.setColor(th.accent);
        c.drawArc(oval, -90, frac * 360, false, p);
        double a = Math.toRadians(frac * 360);
        p.setStyle(Paint.Style.FILL);
        p.setColor(th.text);
        c.drawCircle(cx + (float) Math.sin(a) * r, cy - (float) Math.cos(a) * r, stroke * 0.9f, p);

        t.setTypeface(face);
        t.setTextAlign(Paint.Align.CENTER);
        t.setTextSize(size * 0.27f);
        t.setColor(th.text);
        c.drawText(hh(time) + ":" + mm(time), cx, cy + size * 0.06f, t);
        t.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        t.setTextSize(size * 0.075f);
        t.setColor(th.dim);
        c.drawText("прошло " + Math.round(frac * 100) + "% дня", cx, cy + size * 0.2f, t);
    }
}
