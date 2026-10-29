package com.deskdash;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/** Detail card for one device (long tap on a tile): on/off, brightness, colour, temperature. */
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

    private HomeSheet(Context c, DashTheme th) {
        ctx = c;
        this.th = th;
        dp = c.getResources().getDisplayMetrics().density;
    }

    static View build(Context c, DashTheme th, HomeDevice d, Actions a) {
        return new HomeSheet(c, th).card(d, a);
    }

    private View card(HomeDevice d, Actions a) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);

        TextView title = text(th.text, 18);
        title.setText(d.name);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        card.addView(title);
        TextView sub = text(th.dim, 13);
        String src = "ha".equals(d.source) ? "Home Assistant" : "bambu".equals(d.source) ? "по локальной сети" : "Яндекс";
        sub.setText(d.room.isEmpty() ? src : d.room + " · " + src);
        card.addView(sub, lp(2, 10));

        if (!d.image.isEmpty()) {
            ImageView pic = new ImageView(ctx);
            pic.setScaleType(ImageView.ScaleType.CENTER_CROP);
            pic.setBackgroundColor(th.bg);
            pic.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View v, Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), 14 * dp);
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
            ((LinearLayout.LayoutParams) pic.getLayoutParams()).bottomMargin = px(12);
        }

        TextView status = text(d.alert ? 0xFFE5534B : th.accent, 26);
        status.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        status.setText(d.kind == HomeDevice.CLIMATE && !d.state.isEmpty() ? d.status() + " · " + d.state
                : d.progress >= 0 && d.on ? d.progress + "%" + ("Пауза".equals(d.state) ? " · пауза" : "") : d.status());
        card.addView(status, lp(0, d.progress >= 0 && d.on ? 6 : 12));

        if (d.progress >= 0 && d.on) {
            android.widget.ProgressBar pb = new android.widget.ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal);
            pb.setMax(100);
            pb.setProgress(d.progress);
            pb.setProgressTintList(ColorStateList.valueOf(th.accent));
            pb.setProgressBackgroundTintList(ColorStateList.valueOf(th.line));
            card.addView(pb, lp(0, 4));
            if (d.remainingMin >= 0) {
                TextView left = text(th.text, 15);
                left.setText("Осталось " + HomeDevice.minutes(d.remainingMin) + " · готово в " + HomeDevice.finishAt(d.remainingMin));
                card.addView(left, lp(2, 6));
            }
        }
        if (!d.job.isEmpty()) {
            TextView job = text(th.text, 14);
            job.setText(d.job);
            card.addView(job, lp(0, 4));
        }
        if (!d.detail.isEmpty()) {
            TextView det = text(th.dim, 13);
            det.setLineSpacing(0, 1.2f);
            det.setText(d.detail);
            card.addView(det, lp(0, 12));
        }
        if (!d.cmds.isEmpty() && d.available) {
            LinearLayout row = row();
            String[][] names = {{"resume", "Продолжить"}, {"pause", "Пауза"}, {"stop", "Остановить"}, {"light", "Подсветка"}};
            for (String[] n : names) {
                if (!d.cmds.containsKey(n[0])) continue;
                TextView b = pill(n[1], false, null);
                b.setOnClickListener(v -> {
                    if ("stop".equals(n[0]) && v.getTag() == null) {
                        v.setTag("armed");
                        b.setText("Точно?");
                        b.setTextColor(0xFFE5534B);
                        a.touched();
                        return;
                    }
                    a.command(n[0]);
                });
                row.addView(b, weight());
            }
            card.addView(row, lp(4, 12));
        }

        if (d.kind == HomeDevice.SCENE) {
            card.addView(pill("Запустить", true, v -> a.run()), lp(0, 4));
            return card;
        }
        if (d.canToggle && d.available) {
            String[] w = d.onOffWords();
            LinearLayout row = row();
            row.addView(pill(w[0], d.on, v -> a.setOn(true)), weight());
            row.addView(pill(w[1], !d.on, v -> a.setOn(false)), weight());
            card.addView(row, lp(0, 12));
        }

        if (d.dimmable && d.available) {
            TextView label = text(th.dim, 13);
            int b0 = d.brightness >= 0 ? d.brightness : 100;
            label.setText("Яркость " + b0 + "%");
            card.addView(label);
            SeekBar sb = new SeekBar(ctx);
            sb.setMax(99);
            sb.setProgress(Math.max(0, b0 - 1));
            sb.setProgressTintList(ColorStateList.valueOf(th.accent));
            sb.setThumbTintList(ColorStateList.valueOf(th.accent));
            sb.setProgressBackgroundTintList(ColorStateList.valueOf(th.line));
            sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar s, int p, boolean user) {
                    label.setText("Яркость " + (p + 1) + "%");
                    if (user) a.touched();
                }

                @Override
                public void onStartTrackingTouch(SeekBar s) {
                    a.touched();
                }

                @Override
                public void onStopTrackingTouch(SeekBar s) {
                    a.brightness(s.getProgress() + 1);
                }
            });
            card.addView(sb, lp(4, 12));
        }

        if (d.colorTemp && d.available) {
            card.addView(caption("Белый свет"));
            LinearLayout row = row();
            int mid = (d.minK + d.maxK) / 2;
            row.addView(pill("Тёплый", false, v -> a.colorTemp(d.minK)), weight());
            row.addView(pill("Дневной", false, v -> a.colorTemp(mid)), weight());
            row.addView(pill("Холодный", false, v -> a.colorTemp(d.maxK)), weight());
            card.addView(row, lp(4, 12));
        }

        if (d.color && d.available) {
            card.addView(caption("Цвет"));
            LinearLayout row = row();
            for (int c : COLORS) {
                View dot = new View(ctx);
                GradientDrawable g = new GradientDrawable();
                g.setShape(GradientDrawable.OVAL);
                g.setColor(c);
                dot.setBackground(g);
                dot.setOnClickListener(v -> a.color(c));
                LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(px(30), px(30));
                l.rightMargin = px(8);
                row.addView(dot, l);
            }
            card.addView(row, lp(4, 12));
        }

        if (d.kind == HomeDevice.CLIMATE && !Double.isNaN(d.target) && d.available) {
            card.addView(caption("Целевая температура"));
            LinearLayout row = row();
            row.setGravity(Gravity.CENTER_VERTICAL);
            double[] t = {d.target};
            TextView val = text(th.text, 28);
            val.setGravity(Gravity.CENTER);
            val.setText(HomeDevice.deg(t[0]));
            Handler h = new Handler(Looper.getMainLooper());
            Runnable send = () -> a.temperature(t[0]);
            View.OnClickListener step = v -> {
                double s = d.tStep > 0 ? d.tStep : 0.5;
                t[0] = Math.max(d.tMin, Math.min(d.tMax, t[0] + ((int) v.getTag()) * s));
                val.setText(HomeDevice.deg(t[0]));
                a.touched();
                h.removeCallbacks(send);
                h.postDelayed(send, 900);
            };
            TextView minus = pill("−", false, step);
            minus.setTag(-1);
            TextView plus = pill("+", false, step);
            plus.setTag(1);
            minus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
            plus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
            row.addView(minus, new LinearLayout.LayoutParams(px(64), px(48)));
            row.addView(val, new LinearLayout.LayoutParams(0, -2, 1));
            row.addView(plus, new LinearLayout.LayoutParams(px(64), px(48)));
            card.addView(row, lp(4, 4));
        }
        return card;
    }

    // ---------------------------------------------------------------- helpers

    private TextView caption(String s) {
        TextView t = text(th.dim, 13);
        t.setText(s);
        return t;
    }

    private TextView pill(String label, boolean selected, View.OnClickListener l) {
        TextView t = text(selected ? DashTheme.onColor(th.accent) : th.text, 14);
        t.setText(label);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine();
        t.setPadding(px(10), px(10), px(10), px(10));
        GradientDrawable g = new GradientDrawable();
        g.setColor(selected ? th.accent : th.bg);
        g.setCornerRadius(14 * dp);
        t.setBackground(g);
        t.setOnClickListener(l);
        return t;
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
