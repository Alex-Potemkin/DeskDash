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
 * The "Погода" block, like Apple's Weather widget: the sky of the moment as its background (blue by day,
 * deep blue at night, grey under clouds), white text, now, the next hours and the week. At night the
 * dashboard's dim palette is kept instead. The week is a strip of days on a widget, and rows with a temperature
 * bar in the sheet and on the dock's page; a tap on a row opens that day. A short block drops the hours, then the week.
 */
final class WeatherPanel extends LinearLayout {
    interface Listener {
        void onTap();

        void onSetup();
    }

    private DashTheme th;
    private final float dp;
    private final boolean night, sky;
    private boolean compact, weekRows, wide, hasData;
    private boolean showHours = true, showWeek = true;
    private int weekDays = 7;
    private final Icon icon;
    private final TextView temp, cond, more, empty;
    private final LinearLayout hours, days, now;
    private final HorizontalScrollView hourScroll;

    WeatherPanel(Context c, DashTheme theme, boolean night, Listener l) {
        this(c, theme, night, l, false);
    }

    /** flat: no sky behind it (the top row of the day ribbon), the dashboard's own colours. */
    WeatherPanel(Context c, DashTheme theme, boolean night, Listener l, boolean flat) {
        super(c);
        sky = !night && !flat;
        // white on the sky: the text colours of a dark palette
        th = sky ? DashTheme.byId("dark") : theme;
        this.night = night;
        dp = c.getResources().getDisplayMetrics().density;
        setOrientation(VERTICAL);
        setOnClickListener(v -> l.onTap());
        if (sky) setPadding(px(18), px(16), px(18), px(14));

        now = new LinearLayout(c);
        now.setGravity(Gravity.CENTER_VERTICAL);
        icon = new Icon(c, th, night);
        now.addView(icon, new LayoutParams(px(58), px(58)));
        temp = text(th.text, 46);
        temp.setTypeface(Ui.font(300));
        temp.setIncludeFontPadding(false);
        LayoutParams tl = new LayoutParams(-2, -2);
        tl.leftMargin = px(10);
        now.addView(temp, tl);
        LinearLayout words = new LinearLayout(c);
        words.setOrientation(VERTICAL);
        cond = text(th.text, 16);
        cond.setTypeface(Ui.font(600));
        cond.setSingleLine();
        cond.setEllipsize(TextUtils.TruncateAt.END);
        words.addView(cond);
        more = text(sky ? 0xD9FFFFFF : th.dim, 13);
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
        empty.setTextColor(sky ? 0xFFFFFFFF : th.dim);
        if (sky) setBackground(skyBg(1, true));
        else empty.setBackground(round(th.surface, 16));
        empty.setText("Погода: выбери город\nНастройки → «Погода»");
        empty.setOnClickListener(v -> l.onSetup());
        empty.setVisibility(GONE);
        addView(empty, new LayoutParams(-1, -2));
    }

    View hourStrip() {
        return hourScroll;
    }

    /** What the widget shows, from its settings. */
    void show(boolean hours, boolean week, int nDays) {
        showHours = hours;
        showWeek = week;
        weekDays = nDays;
    }

    /** The week as rows you can open, not a strip: for the sheet, which scrolls. */
    void weekRows() {
        weekRows = true;
    }

    /** A whole screen (the dock's page): now and the hours on the left, the week on the right. */
    void wide() {
        wide = weekRows = true;
        removeAllViews();
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout left = new LinearLayout(getContext());
        left.setOrientation(VERTICAL);
        left.addView(now, new LayoutParams(-1, -2));
        LayoutParams hl = new LayoutParams(-1, -2);
        hl.topMargin = px(16);
        left.addView(hourScroll, hl);
        left.addView(empty, new LayoutParams(-1, -2));
        addView(left, new LayoutParams(0, -2, 1));
        LayoutParams dl = new LayoutParams(0, -2, 1);
        dl.leftMargin = px(24);
        addView(days, dl);
    }

    @Override
    protected void onMeasure(int ws, int hs) {
        if (!hasData || compact || wide) {
            super.onMeasure(ws, hs);
            return;
        }
        hourScroll.setVisibility(showHours ? VISIBLE : GONE);
        days.setVisibility(showWeek ? VISIBLE : GONE);
        int mode = MeasureSpec.getMode(hs);
        if (mode != MeasureSpec.UNSPECIFIED) {
            // "as tall as its content" must not eat the whole column: the blocks above and below need some of it
            int room = mode == MeasureSpec.AT_MOST ? MeasureSpec.getSize(hs) * 7 / 10 : MeasureSpec.getSize(hs);
            int free = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
            super.onMeasure(ws, free);
            if (getMeasuredHeight() > room && showHours) {
                hourScroll.setVisibility(GONE);
                super.onMeasure(ws, free);
            }
            if (getMeasuredHeight() > room) days.setVisibility(GONE);
        }
        super.onMeasure(ws, hs);
    }

    /** Only the "now" row: for the top of the day ribbon and other tight places. */
    void compact() {
        compact = true;
        temp.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        icon.getLayoutParams().width = icon.getLayoutParams().height = px(40);
        setPadding(0, 0, px(8), 0);
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
        render(w, place, Double.NaN, "");
    }

    /** outT: the outdoor sensor's temperature (NaN: the forecast's); indoor: "Дома 22,4° · 41 %" or "". */
    void render(Weather w, String place, double outT, String indoor) {
        boolean has = w != null && !Double.isNaN(w.temp);
        hasData = has;
        empty.setVisibility(has ? GONE : VISIBLE);
        now.setVisibility(has ? VISIBLE : GONE);
        hourScroll.setVisibility(has && !compact && showHours ? VISIBLE : GONE);
        days.setVisibility(has && !compact && showWeek ? VISIBLE : GONE);
        if (!has) {
            if (w != null || !place.isEmpty()) empty.setText("Погода: нет связи\n" + place);
            return;
        }
        icon.set(w.code, w.isDay);
        if (sky) setBackground(skyBg(w.code, w.isDay));
        temp.setText(Weather.deg(Double.isNaN(outT) ? w.temp : outT));
        cond.setText(Weather.text(w.code));
        StringBuilder sb = new StringBuilder();
        if (!Double.isNaN(w.feels) && Math.round(w.feels) != Math.round(w.temp)) sb.append("ощущается ").append(Weather.deg(w.feels)).append(" · ");
        Weather.Day td = w.today();
        if (compact && td != null) sb.append(Weather.deg(td.max)).append(" / ").append(Weather.deg(td.min)).append(" · ");
        sb.append("ветер ").append(Math.round(w.wind)).append(" м/с");
        if (!Double.isNaN(outT)) sb.append(" · по датчику");
        if (!indoor.isEmpty()) sb.append('\n').append(indoor);
        else if (!place.isEmpty() && !compact) sb.append('\n').append(place);
        more.setText(sb);

        hours.removeAllViews();
        long nowMs = System.currentTimeMillis();
        List<Weather.Hour> next = w.next(13, nowMs);
        for (int i = 1; i < next.size(); i++) {
            Weather.Hour h = next.get(i);
            LinearLayout cell = new LinearLayout(getContext());
            cell.setOrientation(VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            TextView t = text(sky ? 0xD9FFFFFF : th.dim, 12);
            t.setTypeface(Ui.font(500));
            t.setText(Weather.hour(h.time));
            cell.addView(centered(t));
            Icon ic = new Icon(getContext(), th, night);
            ic.set(h.code, w.dayAt(h.time + 1_800_000L));
            LayoutParams il = new LayoutParams(px(26), px(26));
            il.topMargin = px(2);
            cell.addView(ic, il);
            TextView v = text(th.text, 14);
            v.setTypeface(Ui.font(600));
            v.setText(Weather.deg(h.temp));
            cell.addView(centered(v));
            if (h.rain >= 30 && Weather.wet(h.code)) {
                TextView r = text(sky ? 0xFF9ED8FF : th.dim, 11);
                r.setText(h.rain + "%");
                cell.addView(centered(r));
            }
            // the strip scrolls and keeps taps to itself; on a cell a tap still reaches the widget
            cell.setOnClickListener(x -> performClick());
            LayoutParams cl = new LayoutParams(px(46), -2);
            hours.addView(cell, cl);
        }

        days.removeAllViews();
        List<Weather.Day> week = w.week(weekDays);
        if (weekRows) weekList(w, week, outT);
        else weekStrip(w, week);
    }

    /** The widget's week: one narrow column per day, so seven days cost the height of one row. */
    private void weekStrip(Weather w, List<Weather.Day> week) {
        days.setOrientation(HORIZONTAL);
        LocalDate today = w.placeDate();
        boolean wet = false;
        for (Weather.Day d : week) wet |= d.rain >= 30;
        for (Weather.Day d : week) {
            LinearLayout cell = new LinearLayout(getContext());
            cell.setOrientation(VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            boolean first = d.date.equals(today);
            TextView n = text(first ? th.text : muted(), 12);
            n.setTypeface(Ui.font(first ? 700 : 500));
            n.setText(first ? "Сег" : Ui.cap(d.date.getDayOfWeek().getDisplayName(TextStyle.SHORT_STANDALONE, Ui.RU)));
            cell.addView(centered(n));
            Icon ic = new Icon(getContext(), th, night);
            ic.set(d.code, true);
            LayoutParams il = new LayoutParams(px(24), px(24));
            il.topMargin = px(2);
            cell.addView(ic, il);
            TextView hi = text(th.text, 14);
            hi.setTypeface(Ui.font(600));
            hi.setSingleLine();
            hi.setText(Weather.deg(d.max));
            cell.addView(centered(hi));
            TextView lo = text(muted(), 12);
            lo.setSingleLine();
            lo.setText(Weather.deg(d.min));
            cell.addView(centered(lo));
            if (wet) {
                TextView r = text(sky ? 0xFF9ED8FF : th.dim, 11);
                r.setSingleLine();
                r.setText(d.rain >= 30 ? d.rain + "%" : " ");
                cell.addView(centered(r));
            }
            days.addView(cell, new LayoutParams(0, -2, 1));
        }
    }

    /** The week as rows: every day's range on one scale for the whole week, so warmer and colder days show at a glance. */
    private void weekList(Weather w, List<Weather.Day> week, double outT) {
        days.setOrientation(VERTICAL);
        LocalDate today = w.placeDate();
        double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
        for (Weather.Day d : week) {
            if (!Double.isNaN(d.min)) lo = Math.min(lo, d.min);
            if (!Double.isNaN(d.max)) hi = Math.max(hi, d.max);
        }
        for (Weather.Day d : week) {
            LinearLayout r = new LinearLayout(getContext());
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(px(2), px(7), px(2), px(7));
            TextView n = text(th.text, 14);
            n.setTypeface(Ui.font(d.date.equals(today) ? 700 : 400));
            n.setSingleLine();
            n.setEllipsize(TextUtils.TruncateAt.END);
            n.setText(dayName(d, today));
            r.addView(n, new LayoutParams(px(104), -2));
            Icon ic = new Icon(getContext(), th, night);
            ic.set(d.code, true);
            r.addView(ic, new LayoutParams(px(24), px(24)));
            TextView rain = text(sky ? 0xFF9ED8FF : th.dim, 11);
            rain.setGravity(Gravity.CENTER);
            rain.setText(d.rain >= 30 ? d.rain + "%" : "");
            r.addView(rain, new LayoutParams(px(38), -2));
            TextView min = text(muted(), 14);
            min.setGravity(Gravity.END);
            min.setText(Weather.deg(d.min));
            r.addView(min, new LayoutParams(px(38), -2));
            TempBar bar = new TempBar(getContext(), lo, hi, d.min, d.max,
                    d.date.equals(today) ? Double.isNaN(outT) ? w.temp : outT : Double.NaN, sky ? 0x33FFFFFF : th.line, night ? th.accent : 0);
            LayoutParams bl = new LayoutParams(0, px(6), 1);
            bl.leftMargin = bl.rightMargin = px(8);
            r.addView(bar, bl);
            TextView max = text(th.text, 14);
            max.setTypeface(Ui.font(600));
            max.setText(Weather.deg(d.max));
            r.addView(max, new LayoutParams(px(38), -2));
            if (days.getChildCount() > 0) {
                View line = new View(getContext());
                line.setBackgroundColor(sky ? 0x33FFFFFF : th.line);
                days.addView(line, new LayoutParams(-1, Math.max(1, (int) (dp * 0.6f))));
            }
            days.addView(r, new LayoutParams(-1, -2));
            LinearLayout more = dayDetails(w, d);
            more.setVisibility(GONE);
            days.addView(more, new LayoutParams(-1, -2));
            r.setOnClickListener(v -> more.setVisibility(more.getVisibility() == VISIBLE ? GONE : VISIBLE));
        }
    }

    private static String dayName(Weather.Day d, LocalDate today) {
        if (d.date.equals(today)) return "Сегодня";
        if (d.date.equals(today.plusDays(1))) return "Завтра";
        return Ui.cap(d.date.getDayOfWeek().getDisplayName(TextStyle.FULL_STANDALONE, Ui.RU));
    }

    /** An open day: its date, weather in words, sun and wind, and the day every three hours while the forecast has them. */
    private LinearLayout dayDetails(Weather w, Weather.Day d) {
        LinearLayout box = new LinearLayout(getContext());
        box.setOrientation(VERTICAL);
        box.setPadding(px(2), 0, px(2), px(10));
        StringBuilder sb = new StringBuilder();
        sb.append(d.date.format(java.time.format.DateTimeFormatter.ofPattern("d MMMM", Ui.RU))).append(" · ")
                .append(Weather.text(d.code).toLowerCase(Ui.RU));
        if (d.wind > 0) sb.append(" · ветер до ").append(Math.round(d.wind)).append(" м/с");
        if (d.sunrise > 0) sb.append("\nВосход ").append(Weather.hour(d.sunrise)).append(" · закат ").append(Weather.hour(d.sunset));
        TextView t = text(muted(), 13);
        t.setLineSpacing(0, 1.15f);
        t.setText(sb);
        box.addView(t);
        List<Weather.Hour> hs = w.hoursOf(d, 3);
        if (hs.size() >= 4) {
            LinearLayout row = new LinearLayout(getContext());
            for (Weather.Hour h : hs) {
                LinearLayout cell = new LinearLayout(getContext());
                cell.setOrientation(VERTICAL);
                cell.setGravity(Gravity.CENTER_HORIZONTAL);
                TextView at = text(muted(), 11);
                at.setSingleLine();
                at.setText(Weather.hour(h.time));
                cell.addView(centered(at));
                Icon ic = new Icon(getContext(), th, night);
                ic.set(h.code, w.dayAt(h.time + 1_800_000L));
                cell.addView(ic, new LayoutParams(px(22), px(22)));
                TextView v = text(th.text, 12);
                v.setSingleLine();
                v.setText(Weather.deg(h.temp));
                cell.addView(centered(v));
                row.addView(cell, new LayoutParams(0, -2, 1));
            }
            LayoutParams rl = new LayoutParams(-1, -2);
            rl.topMargin = px(8);
            box.addView(row, rl);
        }
        return box;
    }

    /** A line of a narrow column: as wide as the column, so its text sits over the icon. */
    private static TextView centered(TextView t) {
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        return t;
    }

    /** Secondary text: soft white on the sky, the theme's grey elsewhere. */
    private int muted() {
        return sky ? 0xD9FFFFFF : th.dim;
    }

    /** A day's range on the week's scale, blue for cold to orange for warm; a dot marks the temperature now. */
    private static final class TempBar extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final double lo, hi, min, max, now;
        private final int track, flat;

        TempBar(Context c, double lo, double hi, double min, double max, double now, int track, int flat) {
            super(c);
            this.lo = lo;
            this.hi = hi;
            this.min = min;
            this.max = max;
            this.now = now;
            this.track = track;
            this.flat = flat;
        }

        private float at(double t, float w) {
            return hi <= lo ? w / 2 : (float) ((Math.max(lo, Math.min(hi, t)) - lo) / (hi - lo)) * w;
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), r = h / 2;
            p.setShader(null);
            p.setColor(track);
            c.drawRoundRect(0, 0, w, h, r, r, p);
            if (Double.isNaN(min) || Double.isNaN(max)) return;
            float a = at(min, w), b = Math.max(at(max, w), a + h);
            if (b > w) {
                a -= b - w;
                b = w;
            }
            if (flat != 0) p.setColor(flat);
            else p.setShader(new android.graphics.LinearGradient(a, 0, b, 0, tempColor(min), tempColor(max), android.graphics.Shader.TileMode.CLAMP));
            c.drawRoundRect(a, 0, b, h, r, r, p);
            p.setShader(null);
            if (!Double.isNaN(now)) {
                p.setColor(0xFFFFFFFF);
                c.drawCircle(Math.max(r, Math.min(w - r, at(now, w))), r, r, p);
            }
        }
    }

    /** −15° and below is blue, about +5° green, +20° yellow, +30° and above orange-red. */
    static int tempColor(double t) {
        double[] at = {-15, -5, 5, 20, 30};
        int[] col = {0xFF5E9BFF, 0xFF4CC9F0, 0xFF4CD08A, 0xFFFFD60A, 0xFFFF6B35};
        if (t <= at[0]) return col[0];
        for (int i = 1; i < at.length; i++) {
            if (t <= at[i]) return DashTheme.blend(col[i - 1], col[i], (float) ((t - at[i - 1]) / (at[i] - at[i - 1])));
        }
        return col[col.length - 1];
    }

    /** The widget's background: a vertical gradient of the sky right now. */
    private GradientDrawable skyBg(int code, boolean day) {
        int g = Weather.group(code);
        int[] cols;
        if (!day) cols = new int[]{0xFF0B1630, 0xFF253A66};
        else if (g == Weather.CLEAR || g == Weather.PARTLY) cols = new int[]{0xFF2E7BD6, 0xFF6FB1EA};
        else if (g == Weather.STORM) cols = new int[]{0xFF2C3440, 0xFF56606E};
        else cols = new int[]{0xFF4F6B85, 0xFF8299AE};
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, cols);
        d.setCornerRadius(Ui.R_WIDGET * dp);
        return d;
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
            int sun = night ? th.accent : 0xFFFFD60A, cloud = night ? th.dim : th.light ? 0xFF9AA3AE : 0xFFF2F4F7,
                    drop = night ? th.accent : 0xFF8FD3FF, moon = night ? th.accent : 0xFFF5EFD6;
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
