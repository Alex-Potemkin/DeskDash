package com.deskdash;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;

/**
 * The "Погода" block: now (icon, temperature, feels like, wind), the next hours and the next days.
 * When the block is short only the first row fits and the rest is hidden by the layout.
 */
final class WeatherPanel extends LinearLayout {
    interface Listener {
        void onTap();

        void onSetup();
    }

    private final DashTheme th;
    private final float dp;
    private final boolean night;
    private boolean compact;
    private final Icon icon;
    private final TextView temp, cond, more, empty;
    private final LinearLayout hours, days, now;
    private final HorizontalScrollView hourScroll;

    WeatherPanel(Context c, DashTheme th, boolean night, Listener l) {
        super(c);
        this.th = th;
        this.night = night;
        dp = c.getResources().getDisplayMetrics().density;
        setOrientation(VERTICAL);
        setOnClickListener(v -> l.onTap());

        now = new LinearLayout(c);
        now.setGravity(Gravity.CENTER_VERTICAL);
        icon = new Icon(c, th, night);
        now.addView(icon, new LayoutParams(px(58), px(58)));
        temp = text(th.text, 44);
        temp.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        temp.setIncludeFontPadding(false);
        LayoutParams tl = new LayoutParams(-2, -2);
        tl.leftMargin = px(10);
        now.addView(temp, tl);
        LinearLayout words = new LinearLayout(c);
        words.setOrientation(VERTICAL);
        cond = text(th.text, 15);
        cond.setSingleLine();
        cond.setEllipsize(TextUtils.TruncateAt.END);
        words.addView(cond);
        more = text(th.dim, 12);
        more.setMaxLines(2);
        more.setEllipsize(TextUtils.TruncateAt.END);
        words.addView(more);
        LayoutParams wl = new LayoutParams(0, -2, 1);
        wl.leftMargin = px(14);
        now.addView(words, wl);
        addView(now, new LayoutParams(-1, -2));

        hourScroll = new HorizontalScrollView(c);
        hourScroll.setHorizontalScrollBarEnabled(false);
        hourScroll.setOverScrollMode(OVER_SCROLL_NEVER);
        hours = new LinearLayout(c);
        hourScroll.addView(hours);
        LayoutParams hl = new LayoutParams(-1, -2);
        hl.topMargin = px(12);
        addView(hourScroll, hl);

        days = new LinearLayout(c);
        days.setOrientation(VERTICAL);
        LayoutParams dl = new LayoutParams(-1, -2);
        dl.topMargin = px(10);
        addView(days, dl);

        empty = text(th.dim, 14);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(px(16), px(12), px(16), px(12));
        empty.setBackground(round(th.surface, 16));
        empty.setText("Погода: выбери город\nНастройки → «Погода»");
        empty.setOnClickListener(v -> l.onSetup());
        empty.setVisibility(GONE);
        addView(empty, new LayoutParams(-1, -2));
    }

    View hourStrip() {
        return hourScroll;
    }

    /** Only the "now" row: for the top of the day ribbon and other tight places. */
    void compact() {
        compact = true;
        temp.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        icon.getLayoutParams().width = icon.getLayoutParams().height = px(40);
    }

    /** The icon as a drawable, for the weather line under the clock. */
    static android.graphics.drawable.Drawable drawable(Context c, DashTheme th, boolean night, int code, boolean day, int size) {
        Icon ic = new Icon(c, th, night);
        ic.set(code, day);
        android.graphics.drawable.Drawable d = new android.graphics.drawable.Drawable() {
            @Override
            public void draw(Canvas canvas) {
                canvas.save();
                canvas.translate(getBounds().left, getBounds().top);
                ic.paint(canvas, getBounds().width());
                canvas.restore();
            }

            @Override
            public void setAlpha(int alpha) {}

            @Override
            public void setColorFilter(android.graphics.ColorFilter cf) {}

            @Override
            public int getOpacity() {
                return android.graphics.PixelFormat.TRANSLUCENT;
            }
        };
        d.setBounds(0, 0, size, size);
        return d;
    }

    void render(Weather w, String place) {
        boolean has = w != null && !Double.isNaN(w.temp);
        empty.setVisibility(has ? GONE : VISIBLE);
        now.setVisibility(has ? VISIBLE : GONE);
        hourScroll.setVisibility(has && !compact ? VISIBLE : GONE);
        days.setVisibility(has && !compact ? VISIBLE : GONE);
        if (!has) {
            if (w != null || !place.isEmpty()) empty.setText("Погода: нет связи\n" + place);
            return;
        }
        icon.set(w.code, w.isDay);
        temp.setText(Weather.deg(w.temp));
        cond.setText(Weather.text(w.code));
        StringBuilder sb = new StringBuilder();
        if (!Double.isNaN(w.feels) && Math.round(w.feels) != Math.round(w.temp)) sb.append("ощущается ").append(Weather.deg(w.feels)).append(" · ");
        Weather.Day td = w.today();
        if (compact && td != null) sb.append(Weather.deg(td.max)).append(" / ").append(Weather.deg(td.min)).append(" · ");
        sb.append("ветер ").append(Math.round(w.wind)).append(" м/с");
        if (!place.isEmpty() && !compact) sb.append('\n').append(place);
        more.setText(sb);

        hours.removeAllViews();
        long nowMs = System.currentTimeMillis();
        List<Weather.Hour> next = w.next(13, nowMs);
        for (int i = 1; i < next.size(); i++) {
            Weather.Hour h = next.get(i);
            LinearLayout cell = new LinearLayout(getContext());
            cell.setOrientation(VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            TextView t = text(th.dim, 11);
            t.setText(Weather.hour(h.time));
            cell.addView(t);
            Icon ic = new Icon(getContext(), th, night);
            ic.set(h.code, w.dayAt(h.time + 1_800_000L));
            LayoutParams il = new LayoutParams(px(26), px(26));
            il.topMargin = px(2);
            cell.addView(ic, il);
            TextView v = text(th.text, 13);
            v.setText(Weather.deg(h.temp));
            cell.addView(v);
            if (h.rain >= 30 && Weather.wet(h.code)) {
                TextView r = text(night ? th.dim : 0xFF5AA7E0, 10);
                r.setText(h.rain + "%");
                cell.addView(r);
            }
            LayoutParams cl = new LayoutParams(px(46), -2);
            hours.addView(cell, cl);
        }

        days.removeAllViews();
        LocalDate today = w.placeDate();
        for (Weather.Day d : w.days) {
            if (!d.date.isAfter(today) || days.getChildCount() >= 3) continue;
            LinearLayout r = new LinearLayout(getContext());
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(px(10), px(5), px(10), px(5));
            r.setBackground(round(th.surface, 12));
            TextView n = text(th.text, 13);
            String name = d.date.equals(today.plusDays(1)) ? "Завтра"
                    : d.date.getDayOfWeek().getDisplayName(TextStyle.FULL_STANDALONE, Locale.getDefault());
            n.setText(Character.toUpperCase(name.charAt(0)) + name.substring(1));
            r.addView(n, new LayoutParams(0, -2, 1));
            Icon ic = new Icon(getContext(), th, night);
            ic.set(d.code, true);
            r.addView(ic, new LayoutParams(px(22), px(22)));
            TextView rain = text(th.dim, 11);
            rain.setText(d.rain >= 30 ? "  " + d.rain + "%" : "");
            rain.setMinWidth(px(38));
            r.addView(rain);
            TextView t = text(th.text, 13);
            t.setText(Weather.deg(d.max) + "  ");
            r.addView(t);
            TextView lo = text(th.dim, 13);
            lo.setText(Weather.deg(d.min));
            lo.setMinWidth(px(34));
            r.addView(lo);
            LayoutParams rl = new LayoutParams(-1, -2);
            rl.bottomMargin = px(4);
            days.addView(r, rl);
        }
    }

    // ---------------------------------------------------------------- icon

    /** Weather icon drawn from circles and lines, in the colours of the theme (sun is yellow by day). */
    static final class Icon extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final DashTheme th;
        private final boolean night;
        private int group = Weather.CLOUDY;
        private boolean day = true;

        Icon(Context c, DashTheme th, boolean night) {
            super(c);
            this.th = th;
            this.night = night;
        }

        void set(int code, boolean isDay) {
            group = Weather.group(code);
            day = isDay;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), s = Math.min(w, h);
            c.save();
            c.translate((w - s) / 2, (h - s) / 2);
            paint(c, s);
            c.restore();
        }

        /** Draws the icon into an s×s square at the canvas origin (also used by the day ribbon). */
        void paint(Canvas c, float s) {
            int sun = night ? th.accent : 0xFFF2B33D, cloud = night ? th.dim : th.light ? 0xFF9AA3AE : 0xFFD7DCE2,
                    drop = night ? th.accent : 0xFF5AA7E0, moon = night ? th.accent : 0xFFE8DDB5;
            p.setStrokeCap(Paint.Cap.ROUND);
            switch (group) {
                case Weather.CLEAR:
                    if (day) sun(c, s * 0.5f, s * 0.5f, s * 0.22f, sun);
                    else moon(c, s * 0.5f, s * 0.5f, s * 0.3f, moon);
                    break;
                case Weather.PARTLY:
                    if (day) sun(c, s * 0.36f, s * 0.36f, s * 0.15f, sun);
                    else moon(c, s * 0.36f, s * 0.36f, s * 0.2f, moon);
                    cloud(c, s, s * 0.08f, s * 0.12f, cloud);
                    break;
                case Weather.FOG:
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(s * 0.07f);
                    p.setColor(cloud);
                    for (int i = 0; i < 3; i++) {
                        float y = s * (0.35f + i * 0.15f), in = i == 1 ? s * 0.08f : 0;
                        c.drawLine(s * 0.2f + in, y, s * 0.8f - in, y, p);
                    }
                    break;
                default:
                    cloud(c, s, 0, group == Weather.CLOUDY ? s * 0.04f : -s * 0.08f, cloud);
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(s * 0.055f);
                    if (group == Weather.RAIN) {
                        p.setColor(drop);
                        for (int i = 0; i < 3; i++) {
                            float x = s * (0.36f + i * 0.14f);
                            c.drawLine(x, s * 0.7f, x - s * 0.05f, s * 0.86f, p);
                        }
                    } else if (group == Weather.SNOW) {
                        p.setStyle(Paint.Style.FILL);
                        p.setColor(night ? th.accent : cloud);
                        for (int i = 0; i < 3; i++) c.drawCircle(s * (0.34f + i * 0.16f), s * (0.76f + (i % 2) * 0.08f), s * 0.045f, p);
                    } else if (group == Weather.STORM) {
                        p.setStyle(Paint.Style.FILL);
                        p.setColor(sun);
                        Path b = new Path();
                        b.moveTo(s * 0.52f, s * 0.6f);
                        b.lineTo(s * 0.4f, s * 0.8f);
                        b.lineTo(s * 0.5f, s * 0.8f);
                        b.lineTo(s * 0.44f, s * 0.96f);
                        b.lineTo(s * 0.62f, s * 0.74f);
                        b.lineTo(s * 0.52f, s * 0.74f);
                        b.lineTo(s * 0.58f, s * 0.6f);
                        b.close();
                        c.drawPath(b, p);
                    }
                    break;
            }
        }

        private void sun(Canvas c, float x, float y, float r, int color) {
            p.setColor(color);
            p.setStyle(Paint.Style.FILL);
            c.drawCircle(x, y, r, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(r * 0.28f);
            for (int i = 0; i < 8; i++) {
                double a = Math.PI / 4 * i;
                float cos = (float) Math.cos(a), sin = (float) Math.sin(a);
                c.drawLine(x + cos * r * 1.45f, y + sin * r * 1.45f, x + cos * r * 1.9f, y + sin * r * 1.9f, p);
            }
        }

        private void moon(Canvas c, float x, float y, float r, int color) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(color);
            Path m = new Path();
            m.addCircle(x, y, r, Path.Direction.CW);
            Path cut = new Path();
            cut.addCircle(x + r * 0.55f, y - r * 0.35f, r * 0.85f, Path.Direction.CW);
            m.op(cut, Path.Op.DIFFERENCE);
            c.drawPath(m, p);
        }

        /** A cloud filling the lower part of the icon; dx/dy shift it. */
        private void cloud(Canvas c, float s, float dx, float dy, int color) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(color);
            Path cl = new Path();
            cl.addCircle(s * 0.38f + dx, s * 0.56f + dy, s * 0.16f, Path.Direction.CW);
            cl.addCircle(s * 0.56f + dx, s * 0.48f + dy, s * 0.21f, Path.Direction.CW);
            cl.addCircle(s * 0.72f + dx, s * 0.6f + dy, s * 0.13f, Path.Direction.CW);
            cl.addRoundRect(new RectF(s * 0.24f + dx, s * 0.56f + dy, s * 0.84f + dx, s * 0.73f + dy), s * 0.08f, s * 0.08f, Path.Direction.CW);
            c.drawPath(cl, p);
        }
    }

    // ---------------------------------------------------------------- helpers

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
