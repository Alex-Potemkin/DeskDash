package com.deskdash;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Ready-made dashboards offered on the first start and in Settings → «Готовые варианты»: a set of
 * desks plus a theme and clock to go with them. Everything stays editable in «Столы и блоки».
 */
final class Presets {
    static final class Preset {
        final String id, name, about, theme, clock;
        final int icon;

        Preset(String id, String name, String about, int icon, String theme, String clock) {
            this.id = id;
            this.name = name;
            this.about = about;
            this.icon = icon;
            this.theme = theme;
            this.clock = clock;
        }

        JSONArray desks() {
            try {
                return build(id);
            } catch (JSONException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    static final Preset[] ALL = {
            new Preset("work", "Рабочий стол", "Часы, созвоны, ПК и календарь. Второй стол — статистика дня",
                    R.drawable.ic_desktop, "dark", "digital"),
            new Preset("calls", "Созвоны", "Ближайшие встречи из всех сервисов, кнопки звонка и экран ПК на телефоне",
                    R.drawable.ic_camera, "midnight", "digital"),
            new Preset("home", "Умный дом", "Свет, климат, принтер и датчики крупными плитками, часы и музыка рядом",
                    R.drawable.ic_home, "light", "stacked"),
            new Preset("minimal", "Минимализм", "Большие часы и музыка, больше ничего",
                    R.drawable.ic_eye_off, "dark", "flip"),
            new Preset("bedside", "У кровати", "Часы, календарь на завтра и свет в спальне, мягкая ночная тема",
                    R.drawable.ic_night, "sand", "analog"),
            new Preset("classic", "Как раньше", "Часы, музыка и ПК слева, календарь справа",
                    R.drawable.ic_dashboard, "dark", "digital"),
    };

    private Presets() {}

    static Preset byId(String id) {
        for (Preset p : ALL) if (p.id.equals(id)) return p;
        return ALL[0];
    }

    private static JSONObject b(String t, int h) throws JSONException {
        JSONObject o = Desks.block(t);
        o.put("h", h);
        return o;
    }

    private static JSONObject home(String title, int h) throws JSONException {
        return b("home", h).put("title", title);
    }

    private static JSONObject desk(String name, JSONObject... cols) throws JSONException {
        JSONArray a = new JSONArray();
        for (JSONObject c : cols) a.put(c);
        return new JSONObject().put("name", name).put("cols", a);
    }

    private static JSONArray build(String id) throws JSONException {
        switch (id) {
            case "work":
                return new JSONArray()
                        .put(desk("Работа",
                                Desks.column(50, b("clock", 1), b("calls", 1), b("pc", 0)),
                                Desks.column(50, b("calendar", 1))))
                        .put(desk("День",
                                Desks.column(40, b("clock", 1), b("music", 0)),
                                Desks.column(60, b("stats", 1))));
            case "calls":
                return new JSONArray()
                        .put(desk("Созвоны",
                                Desks.column(46, b("clock", 1), b("pc", 0)),
                                Desks.column(54, b("calls", 2), b("music", 0))))
                        .put(desk("Календарь",
                                Desks.column(40, b("clock", 1), b("stats", 2)),
                                Desks.column(60, b("calendar", 1))));
            case "home":
                return new JSONArray()
                        .put(desk("Дом",
                                Desks.column(36, b("clock", 1), b("weather", 0), b("music", 0)),
                                Desks.column(64, home("Комнаты", 1))))
                        .put(desk("День",
                                Desks.column(50, b("clock", 1), b("calls", 1)),
                                Desks.column(50, b("calendar", 1))));
            case "minimal":
                return new JSONArray().put(desk("Часы", Desks.column(100, b("clock", 3), b("music", 0))));
            case "bedside":
                return new JSONArray().put(desk("Спальня",
                        Desks.column(55, b("clock", 2), home("Свет", 1)),
                        Desks.column(45, b("weather", 0), b("calendar", 1))));
            default:
                return Desks.defaults();
        }
    }

    /** Puts a preset into the active profile: its desks (replacing the old ones), theme and clock. */
    static void apply(Prefs prefs, Preset p, boolean style) {
        Desks.save(prefs, p.desks());
        prefs.setDeskIndex(0);
        if (style) {
            prefs.setTheme(p.theme);
            prefs.setClockStyle(p.clock);
        }
        if ("bedside".equals(p.id)) prefs.raw().edit().putInt("night_start", 22 * 60).apply();
    }

    // ---------------------------------------------------------------- first start

    private static SharedPreferences global(Context c) {
        return c.getSharedPreferences("deskdash_profiles", Context.MODE_PRIVATE);
    }

    /** After an update that brought new things to set up (looks, weather, night): a short tour, desks untouched. */
    static boolean needTour(Context c) {
        return global(c).getBoolean("welcomed", false) && !global(c).getBoolean("welcomed_v2", false);
    }

    /** First start: nothing set up yet. Settings from older versions count as "already set up". */
    static boolean needWelcome(Context c) {
        if (global(c).getBoolean("welcomed", false)) return false;
        SharedPreferences sp = new Prefs(c).raw();
        if (sp.contains("desks") || sp.contains("pc_host") || sp.contains("theme") || sp.contains("ha_url")) {
            global(c).edit().putBoolean("welcomed", true).apply();
            return false;
        }
        return true;
    }

    static void setWelcomed(Context c) {
        global(c).edit().putBoolean("welcomed", true).putBoolean("welcomed_v2", true).apply();
    }

    // ---------------------------------------------------------------- previews

    /** What a look ("bento", "standby", "dock", "ribbon") looks like, in a theme. */
    static final class LookPreview extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private String look = "bento";
        private DashTheme th = DashTheme.ALL[0];

        LookPreview(Context c) {
            super(c);
            p.setTextAlign(Paint.Align.CENTER);
        }

        void set(String look, DashTheme theme) {
            this.look = look;
            th = theme;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float d = getResources().getDisplayMetrics().density, w = getWidth(), h = getHeight(), pad = 7 * d, r = 5 * d;
            p.setColor(th.bg);
            c.drawRoundRect(0, 0, w, h, 10 * d, 10 * d, p);
            int card = th.surface, line = th.line;
            switch (look) {
                case "bento":
                    box(c, pad, pad, w * 0.42f, h * 0.55f, card, r);
                    clock(c, pad + (w * 0.42f - pad) / 2, pad + h * 0.3f, h * 0.2f);
                    box(c, pad, h * 0.55f + 4 * d, w * 0.42f, h - pad, card, r);
                    box(c, w * 0.42f + 4 * d, pad, w - pad, h * 0.5f, card, r);
                    lines(c, w * 0.42f + 10 * d, pad + 7 * d, w - pad - 6 * d, 3, d);
                    float tw = (w - pad - w * 0.42f - 12 * d) / 3;
                    for (int i = 0; i < 3; i++) {
                        float x = w * 0.42f + 4 * d + i * (tw + 4 * d);
                        box(c, x, h * 0.5f + 4 * d, x + tw, h - pad, i == 0 ? DashTheme.blend(card, th.accent, 0.4f) : card, r);
                    }
                    break;
                case "standby": {
                    box(c, pad, pad, w / 2 - 2 * d, h - pad, card, r);
                    clock(c, (pad + w / 2) / 2, h * 0.5f, h * 0.26f);
                    // a weather widget with its sky, and the stack's dots
                    android.graphics.LinearGradient sky = new android.graphics.LinearGradient(0, pad, 0, h - pad,
                            0xFF2E7BD6, 0xFF6FB1EA, android.graphics.Shader.TileMode.CLAMP);
                    p.setShader(sky);
                    c.drawRoundRect(w / 2 + 2 * d, pad, w - pad, h - pad, r, r, p);
                    p.setShader(null);
                    p.setColor(0xFFFFD60A);
                    c.drawCircle(w / 2 + 16 * d, pad + 14 * d, 6 * d, p);
                    p.setColor(0xFFFFFFFF);
                    p.setTextSize(h * 0.22f);
                    p.setTextAlign(Paint.Align.LEFT);
                    c.drawText("+14°", w / 2 + 9 * d, h * 0.68f, p);
                    p.setTextAlign(Paint.Align.CENTER);
                    for (int i = 0; i < 3; i++) {
                        p.setColor(i == 0 ? 0xFFFFFFFF : 0x80FFFFFF);
                        c.drawCircle(w - pad - 5 * d, h / 2 + (i - 1) * 6 * d, 1.6f * d, p);
                    }
                    break;
                }
                case "dock":
                    box(c, pad, pad, pad + 13 * d, h - pad, card, r);
                    for (int i = 0; i < 5; i++) {
                        p.setColor(i == 1 ? th.accent : th.dim);
                        c.drawCircle(pad + 6.5f * d, pad + 10 * d + i * 11 * d, 2.6f * d, p);
                    }
                    float x0 = pad + 18 * d, cw = (w - pad - x0 - 8 * d) / 3, ch = (h - 2 * pad - 4 * d) / 2;
                    for (int i = 0; i < 6; i++) {
                        float x = x0 + (i % 3) * (cw + 4 * d), y = pad + (i / 3) * (ch + 4 * d);
                        box(c, x, y, x + cw, y + ch, i == 0 || i == 4 ? DashTheme.blend(card, th.accent, 0.4f) : card, r);
                    }
                    break;
                case "ribbon": {
                    clock(c, pad + 18 * d, pad + 13 * d, h * 0.2f);
                    p.setColor(line);
                    float top = h * 0.36f, bot = h * 0.66f;
                    for (int i = 0; i <= 6; i++) c.drawRect(pad + i * (w - 2 * pad) / 6, top, pad + i * (w - 2 * pad) / 6 + d, bot, p);
                    p.setColor(th.events[0]);
                    c.drawRoundRect(w * 0.3f, top + 3 * d, w * 0.48f, top + 12 * d, 3 * d, 3 * d, p);
                    p.setColor(th.events[3]);
                    c.drawRoundRect(w * 0.55f, top + 14 * d, w * 0.8f, top + 23 * d, 3 * d, 3 * d, p);
                    p.setColor(th.now);
                    c.drawRect(w * 0.42f, top - 2 * d, w * 0.42f + 1.5f * d, bot, p);
                    box(c, pad, h * 0.72f, w * 0.45f, h - pad, card, r);
                    box(c, w * 0.48f, h * 0.72f, w - pad, h - pad, card, r);
                    break;
                }
                default:
                    box(c, pad, pad, w * 0.42f, h * 0.55f, card, r);
                    clock(c, pad + (w * 0.42f - pad) / 2, pad + h * 0.3f, h * 0.2f);
                    box(c, pad, h * 0.55f + 4 * d, w * 0.42f, h - pad, card, r);
                    box(c, w * 0.42f + 4 * d, pad, w - pad, h - pad, card, r);
                    lines(c, w * 0.42f + 10 * d, pad + 7 * d, w - pad - 6 * d, 4, d);
                    break;
            }
        }

        private void box(Canvas c, float l, float t, float r, float b, int color, float rad) {
            p.setColor(color);
            c.drawRoundRect(l, t, r, b, rad, rad, p);
        }

        private void clock(Canvas c, float cx, float cy, float size) {
            p.setColor(th.text);
            p.setTypeface(Ui.font(600));
            p.setTextSize(size);
            c.drawText("12:30", cx, cy + size * 0.35f, p);
        }

        /** Calendar-like events: a faint fill with a solid bar on the left. */
        private void lines(Canvas c, float l, float t, float r, int n, float d) {
            for (int i = 0; i < n; i++) {
                int col = th.events[i % th.events.length];
                float y = t + i * 10 * d, right = l + (r - l) * (i % 2 == 0 ? 0.9f : 0.6f);
                p.setColor(TimelineView.eventFill(th, col, false));
                c.drawRoundRect(l, y, right, y + 7 * d, 2 * d, 2 * d, p);
                p.setColor(col);
                c.drawRect(l, y, l + 1.5f * d, y + 7 * d, p);
            }
        }
    }

    /** Small picture of a preset's first desk in a theme: columns of named blocks. */
    static final class Preview extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private JSONObject desk;
        private DashTheme th;

        Preview(Context c) {
            super(c);
            p.setTextAlign(Paint.Align.CENTER);
        }

        void set(Preset preset, DashTheme theme) {
            set(preset.desks().optJSONObject(0), theme);
        }

        void set(JSONObject desk, DashTheme theme) {
            this.desk = desk;
            th = theme;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            if (desk == null) return;
            float d = getResources().getDisplayMetrics().density, w = getWidth(), h = getHeight(), pad = 6 * d, gap = 4 * d;
            p.setColor(th.bg);
            c.drawRoundRect(0, 0, w, h, 10 * d, 10 * d, p);
            JSONArray cols = desk.optJSONArray("cols");
            if (cols == null) return;
            int total = 0;
            for (int i = 0; i < cols.length(); i++) total += Math.max(1, cols.optJSONObject(i).optInt("w", 50));
            float x = pad, avail = w - 2 * pad - gap * (cols.length() - 1);
            p.setTextSize(9 * d);
            for (int i = 0; i < cols.length(); i++) {
                JSONObject col = cols.optJSONObject(i);
                float cw = avail * Math.max(1, col.optInt("w", 50)) / total;
                JSONArray blocks = col.optJSONArray("blocks");
                int n = blocks == null ? 0 : blocks.length();
                float weights = 0;
                for (int j = 0; j < n; j++) weights += weight(blocks.optJSONObject(j));
                float y = pad, ah = h - 2 * pad - gap * Math.max(0, n - 1);
                for (int j = 0; j < n; j++) {
                    JSONObject bl = blocks.optJSONObject(j);
                    float bh = ah * weight(bl) / weights;
                    String t = bl.optString("t");
                    boolean clock = "clock".equals(t);
                    p.setColor(clock ? th.bg : th.surface);
                    RectF r = new RectF(x, y, x + cw, y + bh);
                    c.drawRoundRect(r, 6 * d, 6 * d, p);
                    p.setColor(clock ? th.text : th.dim);
                    if (clock) {
                        p.setTextSize(Math.min(bh * 0.45f, cw * 0.28f));
                        c.drawText("12:30", r.centerX(), r.centerY() + p.getTextSize() * 0.35f, p);
                        p.setTextSize(9 * d);
                    } else {
                        String label = "home".equals(t) && !bl.optString("title").isEmpty() ? bl.optString("title") : Desks.typeName(t);
                        c.drawText(label, r.centerX(), r.centerY() + 3 * d, p);
                        if (bh > 30 * d) { // short blocks have no room for the accent mark above the label
                            p.setColor(th.accent);
                            c.drawRoundRect(x + 5 * d, y + 5 * d, x + 5 * d + Math.min(cw * 0.3f, 18 * d), y + 7.5f * d, d, d, p);
                        }
                    }
                    y += bh + gap;
                }
                x += cw + gap;
            }
        }

        private static float weight(JSONObject b) {
            int h = b.optInt("h", 0);
            return h > 0 ? h * 2f : "music".equals(b.optString("t")) || "pc".equals(b.optString("t")) ? 0.8f : 1f;
        }
    }
}
