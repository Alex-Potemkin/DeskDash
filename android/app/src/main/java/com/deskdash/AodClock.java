package com.deskdash;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.view.View;

import java.time.LocalTime;

/**
 * The clock of the always-on screen: big digits on one line, hours over minutes (as on Samsung's AOD),
 * hairline-thin digits, or a minimal analog face, painted with a solid colour or a gradient.
 */
final class AodClock extends View {
    static final String[][] STYLES = {{"stacked", "Столбиком"}, {"digital", "Крупные"}, {"thin", "Тонкие"}, {"analog", "Стрелки"}};
    static final String[][] COLORS = {{"white", "Белый"}, {"accent", "Акцент"}, {"aurora", "Сияние"}, {"sunset", "Закат"},
            {"ocean", "Океан"}, {"neon", "Неон"}};

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect bounds = new Rect();
    private String style = "stacked";
    private int[] colors = {0xFFFFFFFF, 0xFFFFFFFF};
    private boolean h24 = true;
    private int shownMinute = -1;

    AodClock(Context c) {
        super(c);
        p.setTextAlign(Paint.Align.CENTER);
    }

    void setup(String style, int[] colors, boolean h24) {
        this.style = style;
        this.colors = colors.length == 1 ? new int[]{colors[0], colors[0]} : colors;
        this.h24 = h24;
        invalidate();
    }

    /** The colours of a palette id; the night tint or the accent come from the theme. */
    static int[] palette(String id, DashTheme th) {
        switch (id) {
            case "accent": return new int[]{DashTheme.blend(th.accent, 0xFFFFFFFF, 0.35f), th.accent};
            case "aurora": return new int[]{0xFF63E6E2, 0xFF5E5CE6, 0xFFBF5AF2};
            case "sunset": return new int[]{0xFFFFD60A, 0xFFFF9F0A, 0xFFFF375F};
            case "ocean": return new int[]{0xFF64D2FF, 0xFF0A84FF};
            case "neon": return new int[]{0xFFFF375F, 0xFFBF5AF2, 0xFF0A84FF};
            default: return new int[]{0xFFFFFFFF, 0xFFE5E5EA};
        }
    }

    void tick() {
        int m = LocalTime.now().getMinute();
        if (m != shownMinute) invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        LocalTime t = LocalTime.now();
        shownMinute = t.getMinute();
        float w = getWidth(), h = getHeight();
        int hh = h24 ? t.getHour() : (t.getHour() % 12 == 0 ? 12 : t.getHour() % 12);
        String hs = h24 ? String.format("%02d", hh) : String.valueOf(hh), ms = String.format("%02d", t.getMinute());
        p.setShader(new LinearGradient(0, 0, w * 0.4f, h, colors, null, Shader.TileMode.CLAMP));
        p.setStyle(Paint.Style.FILL);
        switch (style) {
            case "analog": {
                float cx = w / 2, cy = h / 2, r = Math.min(w, h) / 2 * 0.92f, d = getResources().getDisplayMetrics().density;
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeCap(Paint.Cap.ROUND);
                for (int i = 0; i < 12; i++) {
                    double a = Math.PI / 6 * i;
                    float in = i % 3 == 0 ? r * 0.82f : r * 0.9f;
                    p.setStrokeWidth((i % 3 == 0 ? 3.5f : 2f) * d);
                    c.drawLine(cx + (float) Math.sin(a) * in, cy - (float) Math.cos(a) * in,
                            cx + (float) Math.sin(a) * r, cy - (float) Math.cos(a) * r, p);
                }
                double ha = Math.PI * 2 * ((t.getHour() % 12) + t.getMinute() / 60.0) / 12, ma = Math.PI * 2 * t.getMinute() / 60.0;
                p.setStrokeWidth(7 * d);
                c.drawLine(cx, cy, cx + (float) Math.sin(ha) * r * 0.5f, cy - (float) Math.cos(ha) * r * 0.5f, p);
                p.setStrokeWidth(4.5f * d);
                c.drawLine(cx, cy, cx + (float) Math.sin(ma) * r * 0.76f, cy - (float) Math.cos(ma) * r * 0.76f, p);
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(cx, cy, 6 * d, p);
                break;
            }
            case "stacked": {
                p.setTypeface(Ui.font(800));
                p.setLetterSpacing(-0.03f);
                float size = fit(h * 0.44f, w * 0.95f, "88");
                p.setTextSize(size);
                p.getTextBounds("88", 0, 2, bounds);
                float lineH = bounds.height(), gap = lineH * 0.14f, top = (h - lineH * 2 - gap) / 2;
                c.drawText(hs, w / 2, top + lineH, p);
                c.drawText(ms, w / 2, top + lineH * 2 + gap, p);
                break;
            }
            default: {
                p.setTypeface(Ui.font("thin".equals(style) ? 200 : 700));
                p.setLetterSpacing("thin".equals(style) ? 0f : -0.03f);
                String s = hs + ":" + ms;
                p.setTextSize(fit(h * 0.9f, w * 0.95f, s));
                p.getTextBounds(s, 0, s.length(), bounds);
                c.drawText(s, w / 2, h / 2 + bounds.height() / 2f, p);
                break;
            }
        }
        p.setShader(null);
    }

    /** The largest text size whose digits fit both the height and the width. */
    private float fit(float maxH, float maxW, String sample) {
        p.setTextSize(100);
        p.getTextBounds(sample, 0, sample.length(), bounds);
        float byH = 100 * maxH / Math.max(1, bounds.height()), byW = 100 * maxW / Math.max(1, p.measureText(sample));
        return Math.min(byH, byW);
    }
}
