package com.deskdash;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.View;

import java.util.Random;

/**
 * The weather behind the always-on clock, as on Samsung's: rain falls in thin streaks, snow drifts
 * and sways, a storm flashes now and then, a clear night twinkles with stars, clouds drift slowly.
 * Few particles at about 30 frames a second, so a dim screen stays cheap.
 */
final class WeatherFx extends View {
    static final int NONE = 0, RAIN = 1, SNOW = 2, STARS = 3, CLOUDS = 4, STORM = 5;
    private static final long FRAME_MS = 33;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random rnd = new Random();
    private final float dp;
    private int mode = NONE, color = 0xFFFFFFFF;
    private float[] x = new float[0], y = new float[0], v = new float[0], s = new float[0], ph = new float[0];
    private long last, flashAt, nextFlash;
    private boolean live;

    WeatherFx(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        p.setStrokeCap(Paint.Cap.ROUND);
    }

    /** The effect for a weather group (Weather.RAIN …), in a colour (the night tint, or white). */
    void set(int weatherGroup, boolean night, int color) {
        this.color = color;
        switch (weatherGroup) {
            case Weather.RAIN: mode = RAIN; break;
            case Weather.STORM: mode = STORM; break;
            case Weather.SNOW: mode = SNOW; break;
            case Weather.CLEAR: mode = night ? STARS : NONE; break;
            case Weather.PARTLY: mode = night ? STARS : CLOUDS; break;
            default: mode = CLOUDS; break;
        }
        seed();
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        seed();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        live = true;
        last = SystemClock.uptimeMillis();
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        live = false;
        super.onDetachedFromWindow();
    }

    private void seed() {
        int w = Math.max(1, getWidth()), h = Math.max(1, getHeight());
        int n;
        switch (mode) {
            case RAIN: case STORM: n = 70; break;
            case SNOW: n = 55; break;
            case STARS: n = 45; break;
            case CLOUDS: n = 5; break;
            default: n = 0; break;
        }
        x = new float[n];
        y = new float[n];
        v = new float[n];
        s = new float[n];
        ph = new float[n];
        for (int i = 0; i < n; i++) {
            x[i] = rnd.nextFloat() * w;
            y[i] = rnd.nextFloat() * h;
            ph[i] = rnd.nextFloat() * 6.28f;
            switch (mode) {
                case RAIN: case STORM:
                    v[i] = (650 + rnd.nextFloat() * 500) * dp;
                    s[i] = (12 + rnd.nextFloat() * 20) * dp;
                    break;
                case SNOW:
                    v[i] = (22 + rnd.nextFloat() * 45) * dp;
                    s[i] = (1.5f + rnd.nextFloat() * 2.8f) * dp;
                    break;
                case STARS:
                    s[i] = (0.6f + rnd.nextFloat() * 1.4f) * dp;
                    v[i] = 0.4f + rnd.nextFloat() * 1.2f;
                    break;
                default:
                    s[i] = (90 + rnd.nextFloat() * 120) * dp;
                    v[i] = (4 + rnd.nextFloat() * 8) * dp;
                    y[i] = rnd.nextFloat() * h * 0.6f;
                    break;
            }
        }
        nextFlash = SystemClock.uptimeMillis() + 5000 + rnd.nextInt(9000);
    }

    @Override
    protected void onDraw(Canvas c) {
        if (mode == NONE) return;
        long now = SystemClock.uptimeMillis();
        float dt = Math.min(0.1f, (now - last) / 1000f);
        last = now;
        float w = getWidth(), h = getHeight(), t = now / 1000f;
        int rgb = color & 0x00FFFFFF;
        switch (mode) {
            case RAIN:
            case STORM:
                p.setStrokeWidth(1.3f * dp);
                for (int i = 0; i < x.length; i++) {
                    y[i] += v[i] * dt;
                    x[i] += v[i] * dt * 0.12f;
                    if (y[i] - s[i] > h) {
                        y[i] = -s[i] - rnd.nextFloat() * h * 0.3f;
                        x[i] = rnd.nextFloat() * (w + 0.2f * h) - 0.2f * h;
                    }
                    p.setColor(rgb | (55 + (int) (s[i] / dp * 3)) << 24);
                    c.drawLine(x[i], y[i], x[i] - s[i] * 0.12f, y[i] - s[i], p);
                }
                if (mode == STORM) {
                    if (now > nextFlash) {
                        flashAt = now;
                        nextFlash = now + 6000 + rnd.nextInt(10000);
                    }
                    long since = now - flashAt;
                    if (since < 450) {
                        // two quick flickers, like lightning behind clouds
                        float k = since < 120 ? 1 - since / 120f : since > 200 && since < 450 ? (1 - (since - 200) / 250f) * 0.6f : 0;
                        c.drawColor(rgb | ((int) (k * 38)) << 24);
                    }
                }
                break;
            case SNOW:
                p.setStyle(Paint.Style.FILL);
                for (int i = 0; i < x.length; i++) {
                    y[i] += v[i] * dt;
                    float sway = (float) Math.sin(t * 0.9f + ph[i]) * 14 * dp;
                    if (y[i] - s[i] > h) {
                        y[i] = -s[i];
                        x[i] = rnd.nextFloat() * w;
                    }
                    p.setColor(rgb | (110 + (int) (s[i] / dp * 25)) << 24);
                    c.drawCircle(x[i] + sway, y[i], s[i], p);
                }
                break;
            case STARS:
                p.setStyle(Paint.Style.FILL);
                for (int i = 0; i < x.length; i++) {
                    float a = 0.35f + 0.65f * (0.5f + 0.5f * (float) Math.sin(t * v[i] + ph[i]));
                    p.setColor(rgb | ((int) (a * 150)) << 24);
                    c.drawCircle(x[i], y[i], s[i], p);
                }
                break;
            default:
                p.setStyle(Paint.Style.FILL);
                for (int i = 0; i < x.length; i++) {
                    x[i] += v[i] * dt;
                    if (x[i] - s[i] > w) x[i] = -s[i];
                    p.setColor(rgb | 14 << 24);
                    c.drawCircle(x[i], y[i], s[i], p);
                    c.drawCircle(x[i] + s[i] * 0.7f, y[i] + s[i] * 0.2f, s[i] * 0.8f, p);
                }
                break;
        }
        if (live && getVisibility() == VISIBLE) postInvalidateDelayed(FRAME_MS);
    }
}
