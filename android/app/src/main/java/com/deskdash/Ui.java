package com.deskdash;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/**
 * The look shared by the dashboard, the settings and the wizard, after Apple's: widgets with large
 * continuous corners, bold short headings, system colours for meaning (green = join / on, red = leave /
 * alarm), iOS switches and grouped lists with coloured icon squares.
 */
final class Ui {
    /** Corner radii in dp: widgets, tiles inside them, grouped lists, buttons. */
    static final float R_WIDGET = 26, R_TILE = 18, R_GROUP = 14, R_BUTTON = 14;

    // iOS system colours (dark-mode variants read well on both)
    static final int RED = 0xFFFF453A, ORANGE = 0xFFFF9F0A, YELLOW = 0xFFFFD60A, GREEN = 0xFF30D158,
            MINT = 0xFF63E6E2, TEAL = 0xFF40C8E0, CYAN = 0xFF64D2FF, BLUE = 0xFF0A84FF, INDIGO = 0xFF5E5CE6,
            PURPLE = 0xFFBF5AF2, PINK = 0xFFFF375F, BROWN = 0xFFAC8E68, GRAY = 0xFF8E8E93;

    /** Shortcut tile colours, by position (as in the Shortcuts widget). */
    static final int[] TILES = {BLUE, ORANGE, GREEN, PINK, INDIGO, TEAL, PURPLE, RED, BROWN, MINT};

    static final Locale RU = new Locale("ru");
    /** The colours offered as an accent over the theme's. */
    static final int[] ACCENTS = {BLUE, INDIGO, PURPLE, PINK, RED, ORANGE, YELLOW, GREEN, MINT, TEAL, BROWN};

    private Ui() {}

    // ---------------------------------------------------------------- type

    /** System font by weight: 300 light … 400 regular … 600 semibold … 700 bold … 900 heavy. */
    static Typeface font(int weight) {
        if (Build.VERSION.SDK_INT >= 28) return Typeface.create(Typeface.DEFAULT, weight, false);
        if (weight >= 800) return Typeface.create("sans-serif-black", Typeface.NORMAL);
        if (weight >= 600) return Typeface.create("sans-serif", Typeface.BOLD);
        if (weight >= 500) return Typeface.create("sans-serif-medium", Typeface.NORMAL);
        if (weight <= 300) return Typeface.create("sans-serif-light", Typeface.NORMAL);
        return Typeface.DEFAULT;
    }

    static TextView text(Context c, int color, float sp, int weight) {
        TextView t = new TextView(c);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(font(weight));
        t.setIncludeFontPadding(true);
        return t;
    }

    static GradientDrawable round(Context c, int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusDp * c.getResources().getDisplayMetrics().density);
        return g;
    }

    static int px(Context c, float dp) {
        return Math.round(dp * c.getResources().getDisplayMetrics().density);
    }

    /** "Понедельник, 2 ноября" — always Russian, like the rest of the app. */
    static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /**
     * A widget's heading: a small tinted glyph and a short semibold title, like "Календарь" on an iPhone
     * widget; meta (a count, a status) after it in the secondary colour.
     */
    static LinearLayout header(Context c, DashTheme th, int icon, int tint, String title, TextView meta) {
        LinearLayout h = new LinearLayout(c);
        h.setGravity(android.view.Gravity.CENTER_VERTICAL);
        if (icon != 0) {
            ImageView ic = new ImageView(c);
            ic.setImageResource(icon);
            ic.setImageTintList(android.content.res.ColorStateList.valueOf(tint));
            h.addView(ic, new LinearLayout.LayoutParams(px(c, 17), px(c, 17)));
        }
        TextView t = text(c, tint, 15, 600);
        t.setText(title);
        t.setSingleLine();
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
        tl.leftMargin = icon != 0 ? px(c, 7) : 0;
        h.addView(t, tl);
        if (meta != null) {
            meta.setTextColor(th.dim);
            meta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            meta.setTypeface(font(400));
            meta.setSingleLine();
            meta.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(0, -2, 1);
            ml.leftMargin = px(c, 8);
            h.addView(meta, ml);
        }
        return h;
    }

    /** Pill button: filled with a colour (green "join", red "leave") or the quiet fill of the theme. */
    static TextView pill(Context c, String label, int bg, int fg, View.OnClickListener l) {
        TextView t = text(c, fg, 14, 600);
        t.setText(label);
        t.setSingleLine();
        t.setGravity(android.view.Gravity.CENTER);
        t.setPadding(px(c, 16), px(c, 9), px(c, 16), px(c, 9));
        t.setBackground(round(c, bg, 100));
        if (l != null) {
            t.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                l.onClick(v);
            });
        }
        return t;
    }

    /** The quiet fill for buttons, chips and fields on a widget or grouped list. */
    static int fill(DashTheme th) {
        return th.light ? DashTheme.blend(th.surface, 0xFF787880, 0.16f) : DashTheme.blend(th.surface, 0xFF787880, 0.32f);
    }

    // ---------------------------------------------------------------- switch

    /** iOS switch: a green pill with a white knob that slides. */
    static final class Switch extends View {
        interface Listener {
            void on(boolean on);
        }

        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final DashTheme th;
        private boolean on;
        private float pos;
        private Listener listener;

        Switch(Context c, DashTheme th, boolean on) {
            super(c);
            this.th = th;
            this.on = on;
            pos = on ? 1 : 0;
            setOnClickListener(v -> toggle());
        }

        void setListener(Listener l) {
            listener = l;
        }

        boolean isOn() {
            return on;
        }

        void toggle() {
            on = !on;
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(pos, on ? 1 : 0);
            a.setDuration(180);
            a.addUpdateListener(x -> {
                pos = (float) x.getAnimatedValue();
                invalidate();
            });
            a.start();
            if (listener != null) listener.on(on);
        }

        @Override
        protected void onMeasure(int w, int h) {
            setMeasuredDimension(px(getContext(), 51), px(getContext(), 31));
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), r = h / 2, d = getResources().getDisplayMetrics().density;
            int off = th.light ? 0xFFE9E9EA : 0xFF39393D;
            p.setColor(DashTheme.blend(off, GREEN, pos));
            c.drawRoundRect(0, 0, w, h, r, r, p);
            float k = r - 2 * d, x = r + (w - 2 * r) * pos;
            p.setColor(0x33000000);
            c.drawCircle(x, r + d * 0.8f, k, p);
            p.setColor(0xFFFFFFFF);
            c.drawCircle(x, r, k, p);
        }
    }
}
