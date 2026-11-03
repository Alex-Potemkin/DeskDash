package com.deskdash;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Detail card for one device (long tap on a tile), after Apple's Home: the round icon and the name on top,
 * a big slider that fills with the device's colour for brightness, white-light and colour swatches,
 * round − / + for a thermostat, and pill buttons for printers and vacuums.
 */
final class HomeSheet {
    interface Actions {
        void setOn(boolean on);

        void run();

        void brightness(int pct);

        void colorTemp(int kelvin);

        void color(int rgb);

        void temperature(double t);

        /** Printer / vacuum buttons: pause, resume, stop, light. */
        void command(String action);

        /** Camera or printer picture, null while there is none. */
        Bitmap image(HomeDevice d);

        /** Keeps the card open while the user is fiddling with it. */
        void touched();
    }

    private static final int[] COLORS = {0xFFFF3B30, 0xFFFF9500, 0xFFFFCC00, 0xFF34C759, 0xFF00C7BE,
            0xFF007AFF, 0xFFAF52DE, 0xFFFF2D92};

    private final Context ctx;
    private final DashTheme th;
    private final float dp;
    private int kc;

    private HomeSheet(Context c, DashTheme th) {
        ctx = c;
        this.th = th;
        dp = c.getResources().getDisplayMetrics().density;
    }

    static View build(Context c, DashTheme th, HomeDevice d, Actions a) {
        return new HomeSheet(c, th).card(d, a);
    }

    private View card(HomeDevice d, Actions a) {
        kc = th.isNight() ? th.accent : HomePanel.kindColor(d);
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);

        // the round icon, the name, where it is and its state
        LinearLayout head = new LinearLayout(ctx);
        head.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout bubble = new FrameLayout(ctx);
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(d.active() ? kc : Ui.fill(th));
        bubble.setBackground(ring);
        ImageView ic = new ImageView(ctx);
        ic.setImageResource(HomePanel.iconFor(d));
        ic.setImageTintList(ColorStateList.valueOf(d.active() ? DashTheme.onColor(kc) : th.dim));
        bubble.addView(ic, new FrameLayout.LayoutParams(px(24), px(24), Gravity.CENTER));
        head.addView(bubble, new LinearLayout.LayoutParams(px(48), px(48)));
        LinearLayout names = new LinearLayout(ctx);
        names.setOrientation(LinearLayout.VERTICAL);
        TextView title = Ui.text(ctx, th.text, 20, 700);
        title.setText(d.name);
        title.setSingleLine();
        names.addView(title);
        TextView sub = text(th.dim, 14);
        String src = "ha".equals(d.source) ? "Home Assistant" : "bambu".equals(d.source) ? "по локальной сети" : "Яндекс";
        String state = d.kind == HomeDevice.CLIMATE && !d.state.isEmpty() ? d.status() + " · " + d.state
                : d.progress >= 0 && d.on ? d.progress + "%" + ("Пауза".equals(d.state) ? " · пауза" : "") : d.status();
        sub.setText(state + " · " + (d.room.isEmpty() ? src : d.room));
        sub.setTextColor(d.alert ? Ui.RED : th.dim);
        names.addView(sub);
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(0, -2, 1);
        nl.leftMargin = px(14);
        head.addView(names, nl);
        card.addView(head, lp(0, 16));

        if (!d.image.isEmpty()) {
            ImageView pic = new ImageView(ctx);
            pic.setScaleType(ImageView.ScaleType.CENTER_CROP);
            pic.setBackgroundColor(th.bg);
            pic.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View v, Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), Ui.R_TILE * dp);
                }
            });
            pic.setClipToOutline(true);
            Handler h = new Handler(Looper.getMainLooper());
            Runnable refresh = new Runnable() {
                @Override
                public void run() {
                    if (!pic.isAttachedToWindow() && pic.getTag() != null) return;
                    pic.setTag("shown");
                    Bitmap b = a.image(d);
                    if (b != null) pic.setImageBitmap(b);
                    h.postDelayed(this, 1500);
                }
            };
            refresh.run();
            card.addView(pic, new LinearLayout.LayoutParams(-1, px(200)));
            ((LinearLayout.LayoutParams) pic.getLayoutParams()).bottomMargin = px(14);
        }

        if (d.progress >= 0 && d.on) {
            Slider bar = new Slider(ctx, d.progress, false);
            card.addView(bar, new LinearLayout.LayoutParams(-1, px(14)));
            if (d.remainingMin >= 0) {
                TextView left = text(th.text, 15);
                left.setText("Осталось " + HomeDevice.minutes(d.remainingMin) + " · готово в " + HomeDevice.finishAt(d.remainingMin));
                card.addView(left, lp(8, 6));
            }
        }
        if (!d.job.isEmpty()) {
            TextView job = text(th.text, 15);
            job.setText(d.job);
            card.addView(job, lp(4, 4));
        }
        if (!d.detail.isEmpty()) {
            TextView det = text(th.dim, 14);
            det.setLineSpacing(0, 1.2f);
            det.setText(d.detail);
            card.addView(det, lp(0, 12));
        }
        if (!d.cmds.isEmpty() && d.available) {
            LinearLayout row = row();
            String[][] names2 = {{"resume", "Продолжить"}, {"pause", "Пауза"}, {"stop", "Остановить"}, {"light", "Подсветка"}};
            for (String[] n : names2) {
                if (!d.cmds.containsKey(n[0])) continue;
                boolean stop = "stop".equals(n[0]);
                TextView b = pill(n[1], stop ? 0x33FF453A : Ui.fill(th), stop ? Ui.RED : th.text, null);
                b.setOnClickListener(v -> {
                    if (stop && v.getTag() == null) {
                        v.setTag("armed");
                        b.setText("Точно?");
                        b.setBackground(Ui.round(ctx, Ui.RED, 100));
                        b.setTextColor(0xFFFFFFFF);
                        a.touched();
                        return;
                    }
                    a.command(n[0]);
                });
                row.addView(b, weight());
            }
            card.addView(row, lp(8, 12));
        }

        if (d.kind == HomeDevice.SCENE) {
            card.addView(pill("Запустить", kc, DashTheme.onColor(kc), v -> a.run()), lp(0, 4));
            return card;
        }

        if (d.dimmable && d.available) {
            // the big slider: drag to set the brightness, it fills with the lamp's colour
            int b0 = d.on && d.brightness >= 0 ? d.brightness : d.on ? 100 : 0;
            Slider sb = new Slider(ctx, b0, true);
            sb.listener = new SliderListener() {
                @Override
                public void moving() {
                    a.touched();
                }

                @Override
                public void done(int pct) {
                    if (pct <= 0) a.setOn(false);
                    else a.brightness(pct);
                }
            };
            card.addView(sb, new LinearLayout.LayoutParams(-1, px(64)));
            ((LinearLayout.LayoutParams) sb.getLayoutParams()).bottomMargin = px(14);
        }
        if (d.canToggle && d.available) {
            String[] w = d.onOffWords();
            LinearLayout row = row();
            row.addView(pill(w[0], d.on ? kc : Ui.fill(th), d.on ? DashTheme.onColor(kc) : th.text, v -> a.setOn(true)), weight());
            row.addView(pill(w[1], !d.on ? th.text : Ui.fill(th), !d.on ? th.bg : th.text, v -> a.setOn(false)), weight());
            card.addView(row, lp(0, 14));
        }

        if (d.colorTemp && d.available) {
            card.addView(caption("Белый свет"));
            LinearLayout row = row();
            int mid = (d.minK + d.maxK) / 2;
            int[][] whites = {{d.minK, 0xFFFFC27A}, {mid, 0xFFFFF1DC}, {d.maxK, 0xFFDCEBFF}};
            for (int[] wt : whites) {
                View dot = swatch(wt[1], v -> a.colorTemp(wt[0]));
                row.addView(dot, swatchLp());
            }
            card.addView(row, lp(6, 14));
        }

        if (d.color && d.available) {
            card.addView(caption("Цвет"));
            LinearLayout row = row();
            for (int c : COLORS) row.addView(swatch(c, v -> a.color(c)), swatchLp());
            card.addView(row, lp(6, 14));
        }

        if (d.kind == HomeDevice.CLIMATE && !Double.isNaN(d.target) && d.available) {
            card.addView(caption("Целевая температура"));
            LinearLayout row = row();
            row.setGravity(Gravity.CENTER_VERTICAL);
            double[] t = {d.target};
            TextView val = Ui.text(ctx, th.text, 40, 300);
            val.setGravity(Gravity.CENTER);
            val.setText(HomeDevice.deg(t[0]));
            Handler h = new Handler(Looper.getMainLooper());
            Runnable send = () -> a.temperature(t[0]);
            View.OnClickListener step = v -> {
                double s = d.tStep > 0 ? d.tStep : 0.5;
                t[0] = Math.max(d.tMin, Math.min(d.tMax, t[0] + ((int) v.getTag()) * s));
                val.setText(HomeDevice.deg(t[0]));
                v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                a.touched();
                h.removeCallbacks(send);
                h.postDelayed(send, 900);
            };
            TextView minus = round("−", step);
            minus.setTag(-1);
            TextView plus = round("+", step);
            plus.setTag(1);
            row.addView(minus, new LinearLayout.LayoutParams(px(56), px(56)));
            row.addView(val, new LinearLayout.LayoutParams(0, -2, 1));
            row.addView(plus, new LinearLayout.LayoutParams(px(56), px(56)));
            card.addView(row, lp(6, 4));
        }
        return card;
    }

    // ---------------------------------------------------------------- slider

    /** A rounded bar filled from the left, like the brightness slider of iOS; a drag sets it, release sends it. */
    private interface SliderListener {
        void moving();

        void done(int pct);
    }

    private final class Slider extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final boolean interactive;
        int value;
        SliderListener listener;

        Slider(Context c, int value, boolean interactive) {
            super(c);
            this.value = value;
            this.interactive = interactive;
            p.setTextSize(17 * dp);
            p.setTypeface(Ui.font(600));
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), r = Math.min(h / 2, 22 * dp);
            p.setColor(Ui.fill(th));
            c.drawRoundRect(new RectF(0, 0, w, h), r, r, p);
            c.save();
            android.graphics.Path clip = new android.graphics.Path();
            clip.addRoundRect(new RectF(0, 0, w, h), r, r, android.graphics.Path.Direction.CW);
            c.clipPath(clip);
            p.setColor(kc);
            c.drawRect(0, 0, w * value / 100f, h, p);
            c.restore();
            if (interactive) {
                String s = value <= 0 ? "Выкл" : value + " %";
                p.setColor(value > 12 ? DashTheme.onColor(kc) : th.text);
                c.drawText(s, 18 * dp, h / 2 + p.getTextSize() * 0.35f, p);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (!interactive) return false;
            getParent().requestDisallowInterceptTouchEvent(true);
            int v = Math.max(0, Math.min(100, Math.round(e.getX() / getWidth() * 100)));
            if (v != value) {
                if (v / 10 != value / 10) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                value = v;
                invalidate();
            }
            if (listener != null) {
                if (e.getActionMasked() == MotionEvent.ACTION_UP) listener.done(value);
                else listener.moving();
            }
            return true;
        }
    }

    // ---------------------------------------------------------------- helpers

    private TextView caption(String s) {
        TextView t = text(th.dim, 14);
        t.setText(s);
        return t;
    }

    private TextView pill(String label, int bg, int fg, View.OnClickListener l) {
        TextView t = Ui.text(ctx, fg, 15, 600);
        t.setText(label);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine();
        t.setPadding(px(12), px(12), px(12), px(12));
        t.setBackground(Ui.round(ctx, bg, 100));
        t.setOnClickListener(l);
        return t;
    }

    private TextView round(String label, View.OnClickListener l) {
        TextView t = Ui.text(ctx, th.text, 26, 400);
        t.setText(label);
        t.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(Ui.fill(th));
        t.setBackground(g);
        t.setOnClickListener(l);
        return t;
    }

    private View swatch(int color, View.OnClickListener l) {
        View dot = new View(ctx);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        g.setStroke(Math.max(1, (int) dp), th.line);
        dot.setBackground(g);
        dot.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            l.onClick(v);
        });
        return dot;
    }

    private LinearLayout.LayoutParams swatchLp() {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(px(38), px(38));
        l.rightMargin = px(10);
        return l;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(ctx);
        r.setOrientation(LinearLayout.HORIZONTAL);
        return r;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(0, -2, 1);
        l.setMargins(0, 0, px(8), 0);
        return l;
    }

    private LinearLayout.LayoutParams lp(float top, float bottom) {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-1, -2);
        l.setMargins(0, px(top), 0, px(bottom));
        return l;
    }

    private TextView text(int color, float sp) {
        TextView t = new TextView(ctx);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        return t;
    }

    private int px(float v) {
        return Math.round(v * dp);
    }
}
