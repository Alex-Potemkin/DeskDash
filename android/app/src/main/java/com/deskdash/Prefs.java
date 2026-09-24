package com.deskdash;

import android.content.Context;
import android.content.SharedPreferences;

/** All user settings; SettingsActivity writes the same keys directly. */
public class Prefs {
    private final SharedPreferences sp;

    public Prefs(Context c) {
        sp = c.getSharedPreferences("deskdash", Context.MODE_PRIVATE);
    }

    public SharedPreferences raw() {
        return sp;
    }

    public String theme() { return sp.getString("theme", "mocha"); }
    public void setTheme(String id) { sp.edit().putString("theme", id).apply(); }
    public String clockFont() { return sp.getString("clock_font", "thin"); }
    public String clockStyle() { return sp.getString("clock_style", "digital"); }
    public void setClockStyle(String id) { sp.edit().putString("clock_style", id).apply(); }
    /** Window brightness 0..1 chosen by the edge swipe, -1 = follow the system. */
    public float brightness() { return sp.getFloat("brightness", -1f); }
    public void setBrightness(float v) { sp.edit().putFloat("brightness", v).apply(); }
    public boolean h24() { return sp.getBoolean("h24", true); }
    public boolean seconds() { return sp.getBoolean("seconds", false); }
    public boolean googleColors() { return sp.getBoolean("google_colors", true); }
    public boolean artAccent() { return sp.getBoolean("art_accent", true); }
    public boolean burnIn() { return sp.getBoolean("burn_in", true); }

    /** "system", "ics" or "both". */
    public String calSource() { return sp.getString("cal_source", "system"); }
    public String icsUrls() { return sp.getString("ics_urls", ""); }

    public String pcHost() { return sp.getString("pc_host", "").trim(); }
    public int pcPort() { return sp.getInt("pc_port", 8765); }
    public String pcToken() { return sp.getString("pc_token", "").trim(); }
    public String pcMac() { return sp.getString("pc_mac", ""); }
    public void setPcMac(String mac) { sp.edit().putString("pc_mac", mac).apply(); }
    public String pcMacros() { return sp.getString("pc_macros", "[]"); }
    public void setPcMacros(String json) { sp.edit().putString("pc_macros", json).apply(); }

    public boolean nightMode() { return sp.getBoolean("night_mode", true); }
    public int nightFrom() { return sp.getInt("night_from", 23); }
    public int nightTo() { return sp.getInt("night_to", 7); }
}
