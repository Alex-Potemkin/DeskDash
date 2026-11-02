package com.deskdash;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;

/**
 * The "День" block: time at the PC by hour, where it went (apps), calls, meetings from the calendar,
 * music and smart home taps on the dashboard, and the week for comparison. Tap: the day's calls.
 */
final class StatsPanel extends LinearLayout {
    interface Listener {
        void onDetails();

        void onSetup();
    }

    private final DashTheme th;
    private final float dp;
    private final LinearLayout body;
    private final Listener listener;

    StatsPanel(Context c, DashTheme th, Listener l) {
        super(c);
        this.th = th;
        listener = l;
        dp = c.getResources().getDisplayMetrics().density;
        setOrientation(VERTICAL);
        LayoutParams hl = new LayoutParams(-1, -2);
        hl.setMargins(0, 0, 0, px(10));
        addView(Ui.header(c, th, R.drawable.ic_chart, th.isNight() ? th.accent : Ui.ORANGE, "Экранное время", null), hl);
        ScrollView sv = new ScrollView(c);
        sv.setVerticalScrollBarEnabled(false);
        sv.setOverScrollMode(OVER_SCROLL_NEVER);
        sv.setVerticalFadingEdgeEnabled(true);
        sv.setFadingEdgeLength(px(20));
        body = new LinearLayout(c);
        body.setOrientation(VERTICAL);
        sv.addView(body, new ScrollView.LayoutParams(-1, -2));
        addView(sv, new LayoutParams(-1, 0, 1));
        setOnClickListener(v -> listener.onDetails());
        body.setOnClickListener(v -> listener.onDetails());
    }

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

    /** pcDay: /api/day of the PC agent or null; phone: DayStats.today(); meetings: today's calls in the calendar. */
    void render(JSONObject pcDay, boolean pcConfigured, JSONObject phone, List<Meetings.Meeting> meetings, int eventCount) {
        body.removeAllViews();
        LinearLayout nums = new LinearLayout(getContext());
        int active = pcDay == null ? -1 : pcDay.optInt("active_min");
        int callMin = pcDay == null ? 0 : pcDay.optInt("call_min");
        JSONArray pcCalls = pcDay == null ? null : pcDay.optJSONArray("calls");
        int calls = pcCalls == null ? 0 : pcCalls.length();
        long meetMin = 0;
        for (Meetings.Meeting m : meetings) meetMin += (m.event.end - m.event.start) / 60_000L;
        nums.addView(number(active < 0 ? "—" : HomeDevice.minutes(active), "за компьютером"), weight());
        nums.addView(number(calls > 0 ? HomeDevice.minutes(callMin) : meetings.isEmpty() ? "0" : HomeDevice.minutes((int) meetMin),
                calls > 0 ? plural(calls, "созвон", "созвона", "созвонов") : meetings.isEmpty() ? "созвонов"
                        : "созвоны по плану"), weight());
        int music = phone.optInt("music_sec") / 60;
        nums.addView(number(music > 0 ? HomeDevice.minutes(music) : String.valueOf(eventCount),
                music > 0 ? "музыки · " + plural(phone.optInt("tracks"), "трек", "трека", "треков")
                        : plural(eventCount, "событие", "события", "событий").replaceAll("^\\d+ ", "") + " в календаре"), weight());
        body.addView(nums, lp(0, 10));

        if (pcDay != null) {
            JSONArray hours = pcDay.optJSONArray("hours");
            Bars hb = new Bars(getContext(), th);
            int[] v = new int[24];
            for (int i = 0; i < 24 && hours != null; i++) v[i] = hours.optInt(i);
            String[] labels = new String[24];
            for (int i = 0; i < 24; i += 6) labels[i] = String.valueOf(i);
            hb.set(v, 60, labels, LocalTime.now().getHour());
            body.addView(hb, new LayoutParams(-1, px(70)));
            String first = pcDay.optString("first"), last = pcDay.optString("last");
            if (!first.isEmpty()) {
                TextView span = text(th.dim, 11);
                span.setText("За ПК с " + first + " до " + last);
                body.addView(span, lp(2, 8));
            }

            JSONArray apps = pcDay.optJSONArray("apps");
            int max = apps != null && apps.length() > 0 ? Math.max(1, apps.optJSONObject(0).optInt("min")) : 1;
            for (int i = 0; apps != null && i < Math.min(4, apps.length()); i++) {
                JSONObject a = apps.optJSONObject(i);
                body.addView(appRow(a.optString("name"), a.optInt("min"), max), lp(0, 4));
            }

            JSONArray week = pcDay.optJSONArray("week");
            if (week != null && week.length() > 1) {
                TextView cap = text(th.dim, 11);
                cap.setText("НЕДЕЛЯ ЗА ПК");
                cap.setLetterSpacing(0.12f);
                body.addView(cap, lp(10, 2));
                int[] wv = new int[week.length()];
                String[] wl = new String[week.length()];
                for (int i = 0; i < week.length(); i++) {
                    JSONObject d = week.optJSONObject(i);
                    wv[i] = d.optInt("active_min");
                    try {
                        wl[i] = LocalDate.parse(d.optString("date")).getDayOfWeek().getDisplayName(TextStyle.SHORT, Ui.RU);
                    } catch (RuntimeException e) {
                        wl[i] = "";
                    }
                }
                Bars wb = new Bars(getContext(), th);
                wb.set(wv, 0, wl, wv.length - 1);
                body.addView(wb, new LayoutParams(-1, px(60)));
            }
        } else {
            TextView e = text(th.dim, 13);
            e.setLineSpacing(0, 1.15f);
            e.setPadding(px(14), px(12), px(14), px(12));
            e.setBackground(round(th.surface, 16));
            e.setText(pcConfigured ? "Статистика ПК появится, когда он будет в сети (нужна свежая версия DeskDash.exe)"
                    : "Подключи компьютер, чтобы видеть время за ним, приложения и созвоны");
            if (!pcConfigured) e.setOnClickListener(v -> listener.onSetup());
            body.addView(e, lp(0, 4));
        }
        int taps = phone.optInt("home");
        if (taps > 0) {
            TextView h = text(th.dim, 11);
            h.setText("Умный дом с дашборда: " + plural(taps, "нажатие", "нажатия", "нажатий"));
            body.addView(h, lp(8, 0));
        }
    }

    // ---------------------------------------------------------------- pieces

    private View number(String big, String small) {
        LinearLayout b = new LinearLayout(getContext());
        b.setOrientation(VERTICAL);
        b.setPadding(px(10), px(8), px(8), px(8));
        b.setBackground(round(th.surface, 14));
        TextView v = text(th.text, 18);
        v.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        v.setSingleLine();
        v.setEllipsize(TextUtils.TruncateAt.END);
        v.setText(big);
        b.addView(v);
        TextView s = text(th.dim, 11);
        s.setMaxLines(2);
        s.setText(small);
        b.addView(s);
        return b;
    }

    private View appRow(String name, int min, int max) {
        LinearLayout r = new LinearLayout(getContext());
        r.setGravity(Gravity.CENTER_VERTICAL);
        TextView n = text(th.text, 12);
        n.setSingleLine();
        n.setEllipsize(TextUtils.TruncateAt.END);
        n.setText(name);
        r.addView(n, new LayoutParams(px(96), -2));
        Bars.Line line = new Bars.Line(getContext(), th, min / (float) max);
        r.addView(line, new LayoutParams(0, px(6), 1));
        TextView m = text(th.dim, 11);
        m.setText(HomeDevice.minutes(min));
        m.setGravity(Gravity.END);
        r.addView(m, new LayoutParams(px(70), -2));
        return r;
    }

    static String plural(int n, String one, String few, String many) {
        int m10 = n % 10, m100 = n % 100;
        String w = m10 == 1 && m100 != 11 ? one : m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14) ? few : many;
        return n + " " + w;
    }

    private LayoutParams weight() {
        LayoutParams l = new LayoutParams(0, -2, 1);
        l.rightMargin = px(6);
        return l;
    }

    private LayoutParams lp(float top, float bottom) {
        LayoutParams l = new LayoutParams(-1, -2);
        l.setMargins(0, px(top), 0, px(bottom));
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

    /** Column chart: one bar per value, labels under some of them, one bar highlighted. */
    static final class Bars extends View {
        private final DashTheme th;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int[] values = new int[0];
        private String[] labels = new String[0];
        private int max, hi = -1;

        Bars(Context c, DashTheme th) {
            super(c);
            this.th = th;
            p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(10 * c.getResources().getDisplayMetrics().density);
        }

        /** floor: smallest full-height value (60 for minutes per hour), 0 = the biggest value. */
        void set(int[] v, int floor, String[] labels, int highlight) {
            values = v;
            this.labels = labels;
            hi = highlight;
            int m = floor;
            for (int x : v) m = Math.max(m, x);
            max = Math.max(1, m);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            int n = values.length;
            if (n == 0) return;
            float d = getResources().getDisplayMetrics().density;
            float labelH = 14 * d, h = getHeight() - labelH, slot = getWidth() / (float) n, w = Math.max(2 * d, slot * 0.62f);
            for (int i = 0; i < n; i++) {
                float x = i * slot + (slot - w) / 2, bh = Math.max(values[i] > 0 ? 2 * d : 1 * d, h * values[i] / max);
                p.setColor(i == hi ? th.accent : values[i] > 0 ? DashTheme.blend(th.surface, th.accent, 0.45f) : th.line);
                float r = Math.min(w / 3, 4 * d);
                c.drawRoundRect(x, h - bh, x + w, h, r, r, p);
                if (i < labels.length && labels[i] != null) {
                    p.setColor(i == hi ? th.text : th.dim);
                    c.drawText(labels[i], x + w / 2, getHeight() - 2 * d, p);
                }
            }
        }

        /** Thin horizontal share bar. */
        static final class Line extends View {
            private final DashTheme th;
            private final float frac;
            private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

            Line(Context c, DashTheme th, float frac) {
                super(c);
                this.th = th;
                this.frac = Math.max(0.02f, Math.min(1f, frac));
            }

            @Override
            protected void onDraw(Canvas c) {
                float r = getHeight() / 2f;
                p.setColor(th.line);
                c.drawRoundRect(0, 0, getWidth(), getHeight(), r, r, p);
                p.setColor(th.accent);
                c.drawRoundRect(0, 0, getWidth() * frac, getHeight(), r, r, p);
            }
        }
    }
}
