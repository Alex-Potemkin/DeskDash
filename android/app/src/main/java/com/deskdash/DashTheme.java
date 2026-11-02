package com.deskdash;

import android.graphics.Color;

/** Colour palettes. "Мокко" is taken from the brown/beige look of the user's Google Calendar. */
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

    public static final DashTheme[] ALL = {
            new DashTheme("mocha", "Мокко", 0xFF17120F, 0xFF231B17, 0xFFF1E6DA, 0xFF9C8B7E, 0xFF2E2420,
                    0xFFC8A27C, 0xFFE5534B, false,
                    0xFF9A6B4B, 0xFF3B2A22, 0xFFD9C7AE, 0xFFB39B85, 0xFF6E4A36, 0xFFA89A8C, 0xFF4E3629, 0xFFE8DCC9),
            new DashTheme("latte", "Латте", 0xFFF3ECE3, 0xFFE9DFD2, 0xFF2B211C, 0xFF8A7768, 0xFFDCCFBF,
                    0xFF8B5E3C, 0xFFD2463C, true,
                    0xFF9A6B4B, 0xFF4A342A, 0xFFC9B397, 0xFFB08D70, 0xFF6E4A36, 0xFFA39486, 0xFFD8C6AD, 0xFF7B5A45),
            new DashTheme("amoled", "AMOLED", 0xFF000000, 0xFF0E0E0E, 0xFFFFFFFF, 0xFF7A7A7A, 0xFF1C1C1C,
                    0xFFFFFFFF, 0xFFFF453A, false,
                    0xFF3A3A3A, 0xFF5A5A5A, 0xFF2A2A2A, 0xFF8A8A8A, 0xFF474747, 0xFF6B6B6B, 0xFF242424, 0xFFBDBDBD),
            new DashTheme("sage", "Шалфей", 0xFF121613, 0xFF1B211C, 0xFFE3EADF, 0xFF8A988C, 0xFF242B25,
                    0xFF9DB89A, 0xFFE07A5F, false,
                    0xFF5F7A61, 0xFF2F3D31, 0xFFB8C9B0, 0xFF8FA58C, 0xFF44584A, 0xFFA3B1A0, 0xFF3A4A3E, 0xFFD5DECF),
            new DashTheme("ocean", "Океан", 0xFF0E1419, 0xFF16202A, 0xFFE2EAF2, 0xFF7F93A6, 0xFF1F2B36,
                    0xFF7FB3D5, 0xFFF28B82, false,
                    0xFF3E6A8A, 0xFF22384A, 0xFFA9C6DD, 0xFF6F93B0, 0xFF2E5270, 0xFF8FA7BA, 0xFF1C2E3D, 0xFFCFDDE8),
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
        DashTheme b = light ? byId("mocha") : this;
        float k = 0.5f;
        int[] ev = new int[b.events.length];
        for (int i = 0; i < ev.length; i++) ev[i] = blend(b.events[i], 0xFF000000, k);
        return new DashTheme(b.id + "_dim", b.name, 0xFF000000, blend(b.surface, 0xFF000000, 0.55f), blend(b.text, 0xFF000000, k),
                blend(b.dim, 0xFF000000, k), blend(b.line, 0xFF000000, 0.4f), blend(b.accent, 0xFF000000, k),
                blend(b.now, 0xFF000000, 0.4f), false, ev);
    }

    /** One step brighter surfaces, for things that sit on a card of the original surface colour (the "Плитки" look). */
    public DashTheme lifted() {
        return new DashTheme(id, name, bg, blend(surface, text, light ? 0.05f : 0.07f), text, dim,
                blend(line, text, 0.05f), accent, now, light, events);
    }

    public static DashTheme byId(String id) {
        for (DashTheme t : ALL) if (t.id.equals(id)) return t;
        return ALL[0];
    }

    /** Next theme for the double-tap cycle; the night themes are only used automatically. */
    public static DashTheme next(String id) {
        int i = 0;
        for (; i < ALL.length; i++) if (ALL[i].id.equals(id)) break;
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
