package com.deskdash;

import android.graphics.Color;

/**
 * Colour palettes, after Apple's: the dark and light system backgrounds with widget cards on them,
 * system accents and the Calendar's event colours. Ids of the earlier palettes still open the closest one.
 */
public final class DashTheme {
    public final String id, name;
    public final int bg, surface, text, dim, line, accent, now;
    public final int[] events;
    public final boolean light;

    private DashTheme(String id, String name, int bg, int surface, int text, int dim, int line,
                      int accent, int now, boolean light, int... events) {
        this.id = id;
        this.name = name;
        this.bg = bg;
        this.surface = surface;
        this.text = text;
        this.dim = dim;
        this.line = line;
        this.accent = accent;
        this.now = now;
        this.light = light;
        this.events = events;
    }

    private static final int[] EVENTS_DARK = {0xFF0A84FF, 0xFFFF9F0A, 0xFF30D158, 0xFFBF5AF2, 0xFFFF453A, 0xFF64D2FF,
            0xFFFFD60A, 0xFF5E5CE6, 0xFFFF375F, 0xFFAC8E68};
    private static final int[] EVENTS_LIGHT = {0xFF007AFF, 0xFFFF9500, 0xFF34C759, 0xFFAF52DE, 0xFFFF3B30, 0xFF32ADE6,
            0xFFFFCC00, 0xFF5856D6, 0xFFFF2D55, 0xFFA2845E};

    public static final DashTheme[] ALL = {
            new DashTheme("dark", "Тёмная", 0xFF000000, 0xFF1C1C1E, 0xFFFFFFFF, 0xFF8E8E93, 0xFF38383A,
                    0xFF0A84FF, 0xFFFF453A, false, EVENTS_DARK),
            new DashTheme("light", "Светлая", 0xFFF2F2F7, 0xFFFFFFFF, 0xFF000000, 0xFF8A8A8E, 0xFFE5E5EA,
                    0xFF007AFF, 0xFFFF3B30, true, EVENTS_LIGHT),
            new DashTheme("midnight", "Полночь", 0xFF04060E, 0xFF141A2C, 0xFFF2F4FA, 0xFF8A93AD, 0xFF262D45,
                    0xFF7D7AFF, 0xFFFF453A, false, EVENTS_DARK),
            new DashTheme("mint", "Мята", 0xFF000000, 0xFF1C1C1E, 0xFFFFFFFF, 0xFF8E8E93, 0xFF38383A,
                    0xFF30D158, 0xFFFF453A, false, EVENTS_DARK),
            new DashTheme("sand", "Песок", 0xFFF4F1EC, 0xFFFFFFFF, 0xFF1C1C1E, 0xFF8D877F, 0xFFE7E1D8,
                    0xFFFF9500, 0xFFFF3B30, true, EVENTS_LIGHT),
            new DashTheme("night", "Ночь", 0xFF000000, 0xFF0A0303, 0xFFB3261E, 0xFF5E1712, 0xFF1A0706,
                    0xFFD13A2F, 0xFFFF3B30, false,
                    0xFF3A0E0B, 0xFF4F1410, 0xFF2A0907, 0xFF611A14, 0xFF451210, 0xFF330C0A, 0xFF571713, 0xFF6B1E17),
            new DashTheme("night_amber", "Ночь · янтарь", 0xFF000000, 0xFF0B0702, 0xFFB7791F, 0xFF5C3D10, 0xFF1A1105,
                    0xFFD08A25, 0xFFE0662B, false,
                    0xFF3A2708, 0xFF4F350C, 0xFF2A1C06, 0xFF61420F, 0xFF45300B, 0xFF332307, 0xFF573B0D, 0xFF6B4912),
            new DashTheme("night_gray", "Ночь · серый", 0xFF000000, 0xFF080808, 0xFF8C8C8C, 0xFF4A4A4A, 0xFF161616,
                    0xFFA6A6A6, 0xFFB0B0B0, false,
                    0xFF262626, 0xFF333333, 0xFF1E1E1E, 0xFF404040, 0xFF2C2C2C, 0xFF222222, 0xFF383838, 0xFF4A4A4A),
    };

    /** Night palettes by tint: "red", "amber", "gray". */
    public static DashTheme night(String tint) {
        if ("amber".equals(tint)) return byId("night_amber");
        if ("gray".equals(tint)) return byId("night_gray");
        return byId("night");
    }

    public boolean isNight() {
        return id.startsWith("night");
    }

    /** The same theme at night: dark background, everything else much quieter. Light themes turn into their dark twin. */
    public DashTheme dimmed() {
        DashTheme b = light ? byId("dark") : this;
        float k = 0.5f;
        int[] ev = new int[b.events.length];
        for (int i = 0; i < ev.length; i++) ev[i] = blend(b.events[i], 0xFF000000, k);
        return new DashTheme(b.id + "_dim", b.name, 0xFF000000, blend(b.surface, 0xFF000000, 0.55f), blend(b.text, 0xFF000000, k),
                blend(b.dim, 0xFF000000, k), blend(b.line, 0xFF000000, 0.4f), blend(b.accent, 0xFF000000, k),
                blend(b.now, 0xFF000000, 0.4f), false, ev);
    }

    /** The same palette with another accent (Settings → «Вид» → «Акцент»). */
    public DashTheme withAccent(int color) {
        if (color == 0 || isNight()) return this;
        return new DashTheme(id, name, bg, surface, text, dim, line, color | 0xFF000000, now, light, events);
    }

    /** Wallpaper gradients behind the widgets, by id; null for none. */
    public static int[] wallpaper(String id) {
        switch (id == null ? "" : id) {
            case "aurora": return new int[]{0xFF06141F, 0xFF0F3B45, 0xFF2A1748};
            case "sunset": return new int[]{0xFF1A0F2E, 0xFF5B1E3C, 0xFFA8452E};
            case "ocean": return new int[]{0xFF04101F, 0xFF0B2E57, 0xFF125E7A};
            case "graphite": return new int[]{0xFF101012, 0xFF2A2A30, 0xFF15151A};
            case "dawn": return new int[]{0xFFFDE4D0, 0xFFF3D9F2, 0xFFDCE4FF};
            case "sky": return new int[]{0xFFD8EAFF, 0xFFEFF4FF, 0xFFE6F7F1};
            default: return null;
        }
    }

    /** One step brighter surfaces, for things that sit on a card of the original surface colour (the "Плитки" look). */
    public DashTheme lifted() {
        return new DashTheme(id, name, bg, blend(surface, text, light ? 0.05f : 0.07f), text, dim,
                blend(line, text, 0.05f), accent, now, light, events);
    }

    public static DashTheme byId(String id) {
        String real = alias(id);
        for (DashTheme t : ALL) if (t.id.equals(real)) return t;
        return ALL[0];
    }

    /** Palettes of earlier versions → the closest one now. */
    private static String alias(String id) {
        switch (id == null ? "" : id) {
            case "mocha": case "amoled": return "dark";
            case "latte": return "sand";
            case "sage": return "mint";
            case "ocean": return "midnight";
            default: return id;
        }
    }

    /** Next theme for the double-tap cycle; the night themes are only used automatically. */
    public static DashTheme next(String id) {
        int i = 0;
        String real = byId(id).id;
        for (; i < ALL.length; i++) if (ALL[i].id.equals(real)) break;
        DashTheme n = ALL[(i + 1) % ALL.length];
        return n.isNight() ? ALL[0] : n;
    }

    public int eventColor(int googleColor, String key, boolean useGoogle) {
        if (useGoogle && googleColor != 0) return googleColor | 0xFF000000;
        int h = googleColor != 0 ? googleColor : key.hashCode();
        return events[Math.floorMod(h * 31 + (h >>> 7), events.length)];
    }

    /** Text colour that reads well on the given fill. */
    public static int onColor(int c) {
        double l = (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255.0;
        return l > 0.6 ? 0xFF1E1714 : 0xFFFFFFFF;
    }

    public static int blend(int a, int b, float t) {
        float s = 1 - t;
        return Color.rgb(
                (int) (Color.red(a) * s + Color.red(b) * t),
                (int) (Color.green(a) * s + Color.green(b) * t),
                (int) (Color.blue(a) * s + Color.blue(b) * t));
    }
}
