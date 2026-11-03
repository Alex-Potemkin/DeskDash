package com.deskdash;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The "Энергия" widget: how much the house draws right now (smart plugs and meters from Home Assistant,
 * Yandex or the hub), the devices that draw most, a curve of the last 24 hours, and the kWh and roubles of today
 * (Settings → «Умный дом» → тариф).
 */
final class EnergyPanel extends LinearLayout {
    private static final Pattern WATTS = Pattern.compile("(-?\\d+(?:[.,]\\d+)?)\\s*(кВт|kW|Вт|W)(?![·h\\w])", Pattern.UNICODE_CASE);
    private static final Pattern KWH = Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s*(кВт·ч|kWh|кВтч)");

    private final DashTheme th;
    private final Prefs prefs;
    private final float dp;
    private final TextView now, today;
    private final LinearLayout top;
    private final Curve curve;

    EnergyPanel(Context c, DashTheme th, Prefs prefs) {
        super(c);
        this.th = th;
        this.prefs = prefs;
        dp = c.getResources().getDisplayMetrics().density;
        setOrientation(VERTICAL);
        LayoutParams hl = new LayoutParams(-1, -2);
        hl.setMargins(0, 0, 0, px(8));
        addView(Ui.header(c, th, R.drawable.ic_bolt, th.isNight() ? th.accent : Ui.YELLOW, "Энергия", null), hl);
        now = Ui.text(c, th.text, 32, 600);
        addView(now);
        today = Ui.text(c, th.dim, 14, 400);
        addView(today);
        top = new LinearLayout(c);
        top.setOrientation(VERTICAL);
        LayoutParams tl = new LayoutParams(-1, -2);
        tl.topMargin = px(8);
        addView(top, tl);
        curve = new Curve(c);
        addView(curve, new LayoutParams(-1, 0, 1)); // the curve takes what height is left
        curve.setMinimumHeight(px(24));
    }

    /** Power now in watts, NaN when the device doesn't report it. */
    static double watts(HomeDevice d) {
        String s = d.state + " " + d.detail;
        Matcher m = WATTS.matcher(s);
        if (!m.find()) return Double.NaN;
        double v = Double.parseDouble(m.group(1).replace(',', '.'));
        return m.group(2).startsWith("к") || m.group(2).startsWith("k") ? v * 1000 : v;
    }

    /** A meter's total in kWh, NaN when the device has none. */
    static double kwh(HomeDevice d) {
        Matcher m = KWH.matcher(d.state + " " + d.detail);
        return m.find() ? Double.parseDouble(m.group(1).replace(',', '.')) : Double.NaN;
    }

    /** Every five minutes: the total power goes into the 24-hour history; meters remember where the day started. */
    static void sample(Prefs p, Map<String, HomeDevice> devs) {
        double total = 0;
        boolean any = false;
        for (HomeDevice d : devs.values()) {
            double w = watts(d);
            if (!Double.isNaN(w) && d.available) {
                total += w;
                any = true;
            }
        }
        android.content.SharedPreferences sp = p.raw();
        try {
            long t = System.currentTimeMillis();
            if (any) {
                JSONArray h = new JSONArray(sp.getString("energy_hist", "[]")), keep = new JSONArray();
                for (int i = 0; i < h.length(); i++) if (t - h.getJSONArray(i).getLong(0) < 24 * 3_600_000L) keep.put(h.get(i));
                keep.put(new JSONArray().put(t).put(Math.round(total)));
                sp.edit().putString("energy_hist", keep.toString()).apply();
            }
            String day = LocalDate.now().toString();
            JSONObject base = new JSONObject(sp.getString("energy_base", "{}"));
            if (!day.equals(base.optString("day"))) base = new JSONObject().put("day", day);
            for (HomeDevice d : devs.values()) {
                double k = kwh(d);
                if (!Double.isNaN(k) && !base.has(d.id)) base.put(d.id, k);
            }
            sp.edit().putString("energy_base", base.toString()).apply();
        } catch (Exception ignored) {
            // a broken history starts over
        }
    }

    void render(Map<String, HomeDevice> devs) {
        List<HomeDevice> power = new ArrayList<>();
        double total = 0, kwhToday = 0;
        JSONObject base;
        try {
            base = new JSONObject(prefs.raw().getString("energy_base", "{}"));
        } catch (Exception e) {
            base = new JSONObject();
        }
        for (HomeDevice d : devs.values()) {
            double w = watts(d);
            if (!Double.isNaN(w) && d.available) {
                power.add(d);
                total += w;
            }
            double k = kwh(d);
            if (!Double.isNaN(k) && base.has(d.id)) kwhToday += Math.max(0, k - base.optDouble(d.id));
        }
        if (power.isEmpty() && kwhToday == 0) {
            now.setText("—");
            today.setText("Нет розеток и счётчиков с мощностью в умном доме");
            top.removeAllViews();
            curve.set(new long[0]);
            return;
        }
        now.setText(total >= 1000 ? String.format(Locale.ROOT, "%.2f кВт", total / 1000).replace('.', ',') : Math.round(total) + " Вт");
        float price = prefs.raw().getFloat("energy_price", 6.5f);
        today.setText(kwhToday > 0 ? String.format(Locale.ROOT, "Сегодня %.1f кВт·ч · ≈ %.0f ₽", kwhToday, kwhToday * price).replace('.', ',')
                : "Сейчас · " + power.size() + " с замером мощности");
        power.sort((a, b) -> Double.compare(watts(b), watts(a)));
        top.removeAllViews();
        double max = power.isEmpty() ? 1 : Math.max(1, watts(power.get(0)));
        for (int i = 0; i < Math.min(4, power.size()); i++) {
            HomeDevice d = power.get(i);
            LinearLayout r = new LinearLayout(getContext());
            r.setGravity(android.view.Gravity.CENTER_VERTICAL);
            TextView n = Ui.text(getContext(), th.text, 13, 500);
            n.setSingleLine();
            n.setText(d.name);
            r.addView(n, new LayoutParams(0, -2, 1));
            View bar = new View(getContext());
            bar.setBackground(Ui.round(getContext(), Ui.YELLOW, 4));
            r.addView(bar, new LayoutParams(Math.max(px(4), (int) (px(70) * watts(d) / max)), px(6)));
            TextView v = Ui.text(getContext(), th.dim, 13, 400);
            v.setText("  " + Math.round(watts(d)) + " Вт");
            v.setMinWidth(px(64));
            v.setGravity(android.view.Gravity.END);
            r.addView(v);
            LayoutParams rl = new LayoutParams(-1, -2);
            rl.bottomMargin = px(4);
            top.addView(r, rl);
        }
        try {
            JSONArray h = new JSONArray(prefs.raw().getString("energy_hist", "[]"));
            long[] vals = new long[h.length()];
            for (int i = 0; i < vals.length; i++) vals[i] = h.getJSONArray(i).getLong(1);
            curve.set(vals);
        } catch (Exception ignored) {
            curve.set(new long[0]);
        }
    }

    /** The last 24 hours of total power as a filled line. */
    private final class Curve extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private long[] vals = new long[0];

        Curve(Context c) {
            super(c);
        }

        void set(long[] v) {
            vals = v;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            p.setColor(Ui.fill(th));
            p.setStyle(Paint.Style.FILL);
            c.drawRoundRect(0, h - 2 * dp, w, h, dp, dp, p);
            if (vals.length < 2) return;
            long top = 1;
            for (long v : vals) top = Math.max(top, v);
            // headroom, so a steady load is a line across the middle rather than a filled block
            float max = top * 1.6f;
            Path line = new Path(), fill = new Path();
            for (int i = 0; i < vals.length; i++) {
                float x = w * i / (vals.length - 1), y = h - 2 * dp - (h - 4 * dp) * vals[i] / max;
                if (i == 0) {
                    line.moveTo(x, y);
                    fill.moveTo(x, h);
                }
                line.lineTo(x, y);
                fill.lineTo(x, y);
            }
            fill.lineTo(w, h);
            fill.close();
            p.setShader(new android.graphics.LinearGradient(0, 0, 0, h, (Ui.YELLOW & 0x00FFFFFF) | 0x55000000,
                    Ui.YELLOW & 0x00FFFFFF, android.graphics.Shader.TileMode.CLAMP));
            c.drawPath(fill, p);
            p.setShader(null);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2 * dp);
            p.setColor(Ui.YELLOW);
            c.drawPath(line, p);
        }
    }

    private int px(float v) {
        return Math.round(v * dp);
    }
}
