package com.deskdash;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Timers and a pomodoro, kept in the profile so they survive a restart: the "Таймеры" widget starts them
 * with one tap (or by voice), shows each with a ring and the time left; when one ends the dashboard rings.
 * The pomodoro goes 25 minutes of work, 5 of rest, and after four rounds a 15-minute break.
 */
final class Timers {
    static final class Timer {
        String id = "", label = "";
        long end, total, left = -1; // left >= 0: paused with that much to go
        int round; // pomodoro: 1, 2, 3… (0 for a plain timer)
        boolean rest;

        long remaining(long now) {
            return left >= 0 ? left : Math.max(0, end - now);
        }
    }

    interface Listener {
        void changed();
    }

    private Timers() {}

    static List<Timer> load(Prefs p) {
        List<Timer> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(p.raw().getString("timers", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Timer t = new Timer();
                t.id = o.optString("id");
                t.label = o.optString("label");
                t.end = o.optLong("end");
                t.total = o.optLong("total");
                t.left = o.optLong("left", -1);
                t.round = o.optInt("round");
                t.rest = o.optBoolean("rest");
                out.add(t);
            }
        } catch (Exception ignored) {
            // nothing saved
        }
        return out;
    }

    static void save(Prefs p, List<Timer> l) {
        JSONArray a = new JSONArray();
        try {
            for (Timer t : l) {
                a.put(new JSONObject().put("id", t.id).put("label", t.label).put("end", t.end).put("total", t.total)
                        .put("left", t.left).put("round", t.round).put("rest", t.rest));
            }
        } catch (Exception ignored) {
            // plain values
        }
        p.raw().edit().putString("timers", a.toString()).apply();
    }

    static Timer start(Prefs p, String label, long ms) {
        List<Timer> l = load(p);
        Timer t = new Timer();
        t.id = Long.toString(System.currentTimeMillis(), 36);
        t.label = label;
        t.total = ms;
        t.end = System.currentTimeMillis() + ms;
        l.add(t);
        save(p, l);
        return t;
    }

    static Timer pomodoro(Prefs p, int round, boolean rest) {
        long ms = rest ? (round % 4 == 0 ? 15 : 5) * 60_000L : 25 * 60_000L;
        Timer t = start(p, rest ? "Перерыв" : "Помодоро " + round, ms);
        List<Timer> l = load(p);
        for (Timer x : l) {
            if (x.id.equals(t.id)) {
                x.round = round;
                x.rest = rest;
            }
        }
        save(p, l);
        return t;
    }

    /** Timers that have just run out (they are removed; a pomodoro goes on with its next part). */
    static List<Timer> finished(Prefs p) {
        List<Timer> l = load(p), done = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (Timer t : new ArrayList<>(l)) {
            if (t.left < 0 && t.end <= now) {
                done.add(t);
                l.remove(t);
            }
        }
        if (done.isEmpty()) return done;
        save(p, l);
        for (Timer t : done) if (t.round > 0) pomodoro(p, t.rest ? t.round + 1 : t.round, !t.rest);
        return done;
    }

    static String clock(long ms) {
        long s = (ms + 999) / 1000;
        return s >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                : String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    // ---------------------------------------------------------------- the widget

    static final class Panel extends LinearLayout {
        private final DashTheme th;
        private final Prefs prefs;
        private final float dp;
        private final LinearLayout list;
        private final Listener listener;
        private String sig = "";

        Panel(Context c, DashTheme th, Prefs prefs, Listener l) {
            super(c);
            this.th = th;
            this.prefs = prefs;
            listener = l;
            dp = c.getResources().getDisplayMetrics().density;
            setOrientation(VERTICAL);
            LayoutParams hl = new LayoutParams(-1, -2);
            hl.setMargins(0, 0, 0, px(10));
            addView(Ui.header(c, th, R.drawable.ic_clock, th.isNight() ? th.accent : Ui.ORANGE, "Таймеры", null), hl);
            LinearLayout quick = new LinearLayout(c);
            int[] mins = {1, 3, 5, 10, 15, 30, 60};
            for (int m : mins) {
                quick.addView(Ui.pill(c, m < 60 ? m + " мин" : "1 час", Ui.fill(th), th.text, v -> {
                    start(prefs, m < 60 ? m + " мин" : "1 час", m * 60_000L);
                    listener.changed();
                }), chip());
            }
            quick.addView(Ui.pill(c, "Помодоро", Ui.ORANGE, 0xFFFFFFFF, v -> {
                pomodoro(prefs, 1, false);
                listener.changed();
            }), chip());
            HorizontalScrollView hs = new HorizontalScrollView(c);
            hs.setHorizontalScrollBarEnabled(false);
            hs.addView(quick);
            addView(hs, new LayoutParams(-1, -2));
            list = new LinearLayout(c);
            list.setOrientation(VERTICAL);
            LayoutParams ll = new LayoutParams(-1, -2);
            ll.topMargin = px(10);
            addView(list, ll);
        }

        View strip() {
            return getChildAt(1);
        }

        /** Every second: the rings and the times; rows are rebuilt only when timers come or go. */
        void tick() {
            List<Timer> l = load(prefs);
            StringBuilder s = new StringBuilder();
            for (Timer t : l) s.append(t.id).append(t.left >= 0).append(',');
            long now = System.currentTimeMillis();
            if (!s.toString().equals(sig)) {
                sig = s.toString();
                list.removeAllViews();
                for (Timer t : l) list.addView(row(t), rowLp());
            }
            for (int i = 0; i < list.getChildCount() && i < l.size(); i++) {
                LinearLayout r = (LinearLayout) list.getChildAt(i);
                Timer t = l.get(i);
                ((Ring) r.getChildAt(0)).set(t.total <= 0 ? 0 : 1f - t.remaining(now) / (float) t.total);
                ((TextView) ((LinearLayout) r.getChildAt(1)).getChildAt(0)).setText(clock(t.remaining(now)));
            }
        }

        private View row(Timer t) {
            LinearLayout r = new LinearLayout(getContext());
            r.setGravity(Gravity.CENTER_VERTICAL);
            Ring ring = new Ring(getContext(), t.rest ? Ui.GREEN : Ui.ORANGE, Ui.fill(th));
            r.addView(ring, new LayoutParams(px(40), px(40)));
            LinearLayout words = new LinearLayout(getContext());
            words.setOrientation(VERTICAL);
            TextView left = Ui.text(getContext(), th.text, 22, 500);
            words.addView(left);
            TextView name = Ui.text(getContext(), th.dim, 13, 400);
            name.setText(t.label);
            words.addView(name);
            LayoutParams wl = new LayoutParams(0, -2, 1);
            wl.leftMargin = px(12);
            r.addView(words, wl);
            r.addView(Ui.pill(getContext(), t.left >= 0 ? "▶" : "❚❚", Ui.fill(th), th.text, v -> {
                List<Timer> l = load(prefs);
                long now = System.currentTimeMillis();
                for (Timer x : l) {
                    if (!x.id.equals(t.id)) continue;
                    if (x.left >= 0) {
                        x.end = now + x.left;
                        x.left = -1;
                    } else {
                        x.left = Math.max(0, x.end - now);
                    }
                }
                save(prefs, l);
                sig = "";
                tick();
            }));
            TextView x = Ui.pill(getContext(), "✕", 0x33FF453A, Ui.RED, v -> {
                List<Timer> l = load(prefs);
                l.removeIf(y -> y.id.equals(t.id));
                save(prefs, l);
                sig = "";
                tick();
            });
            LayoutParams xl = new LayoutParams(-2, -2);
            xl.leftMargin = px(6);
            r.addView(x, xl);
            return r;
        }

        private LayoutParams chip() {
            LayoutParams l = new LayoutParams(-2, -2);
            l.rightMargin = px(6);
            return l;
        }

        private LayoutParams rowLp() {
            LayoutParams l = new LayoutParams(-1, -2);
            l.bottomMargin = px(8);
            return l;
        }

        private int px(float v) {
            return Math.round(v * dp);
        }
    }

    /** How much of a timer has passed, as a ring. */
    static final class Ring extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int color, track;
        private float done;

        Ring(Context c, int color, int track) {
            super(c);
            this.color = color;
            this.track = track;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
        }

        void set(float f) {
            if (Math.abs(f - done) < 0.001f) return;
            done = f;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), sw = w * 0.13f;
            p.setStrokeWidth(sw);
            RectF r = new RectF(sw / 2, sw / 2, w - sw / 2, getHeight() - sw / 2);
            p.setColor(track);
            c.drawArc(r, 0, 360, false, p);
            p.setColor(color);
            c.drawArc(r, -90, 360 * (1 - done), false, p);
        }
    }
}
