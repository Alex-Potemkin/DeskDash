package com.deskdash;

import android.content.Context;
import android.content.SharedPreferences;

/** All user settings of the active profile; SettingsActivity writes the same keys directly. */
public class Prefs {
    private final Context ctx;

    public Prefs(Context c) {
        ctx = c.getApplicationContext() != null ? c.getApplicationContext() : c;
    }

    /** Settings file of the profile that is active right now (switching profiles takes effect at once). */
    public SharedPreferences raw() {
        return Profiles.prefs(ctx, Profiles.active(ctx));
    }

    private SharedPreferences sp() {
        return raw();
    }

    public String theme() { return sp().getString("theme", "mocha"); }
    public void setTheme(String id) { sp().edit().putString("theme", id).apply(); }
    public String clockFont() { return sp().getString("clock_font", "thin"); }
    public String clockStyle() { return sp().getString("clock_style", "digital"); }
    public void setClockStyle(String id) { sp().edit().putString("clock_style", id).apply(); }
    /** Window brightness 0..1 chosen by the edge swipe, -1 = follow the system. */
    public float brightness() { return sp().getFloat("brightness", -1f); }
    public void setBrightness(float v) { sp().edit().putFloat("brightness", v).apply(); }
    public boolean h24() { return sp().getBoolean("h24", true); }
    public boolean seconds() { return sp().getBoolean("seconds", false); }
    public boolean googleColors() { return sp().getBoolean("google_colors", true); }
    public boolean artAccent() { return sp().getBoolean("art_accent", true); }
    public boolean burnIn() { return sp().getBoolean("burn_in", true); }

    /** "system", "ics" or "both". */
    public String calSource() { return sp().getString("cal_source", "system"); }
    public String icsUrls() { return sp().getString("ics_urls", ""); }

    public String pcHost() { return sp().getString("pc_host", "").trim(); }
    public int pcPort() { return sp().getInt("pc_port", 8765); }
    public String pcToken() { return sp().getString("pc_token", "").trim(); }
    public String pcMac() { return sp().getString("pc_mac", ""); }
    public void setPcMac(String mac) { sp().edit().putString("pc_mac", mac).apply(); }
    public String pcMacros() { return sp().getString("pc_macros", "[]"); }
    public void setPcMacros(String json) { sp().edit().putString("pc_macros", json).apply(); }

    public boolean nightMode() { return sp().getBoolean("night_mode", true); }
    public int nightFrom() { return sp().getInt("night_from", 23); }
    public int nightTo() { return sp().getInt("night_to", 7); }

    /** Desks as JSON, see Desks. */
    public String desks() { return sp().getString("desks", ""); }
    public void setDesks(String json) { sp().edit().putString("desks", json).apply(); }
    public int deskIndex() { return sp().getInt("desk", 0); }
    public void setDeskIndex(int i) { sp().edit().putInt("desk", i).apply(); }

    /** Home Assistant base URL without a trailing slash, "http://" added when missing. */
    public String haUrl() {
        String u = sp().getString("ha_url", "").trim();
        if (u.isEmpty()) return u;
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://" + u;
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }
    /** Long-lived access token typed by hand; when empty the login tokens below are used. */
    public String haToken() { return sp().getString("ha_token", "").trim(); }
    public String haAccess() { return sp().getString("ha_access", ""); }
    public String haRefresh() { return sp().getString("ha_refresh", ""); }
    public long haExpires() { return sp().getLong("ha_expires", 0); }
    public void setHaAuth(String access, String refresh, long expires) {
        SharedPreferences.Editor ed = sp().edit().putString("ha_access", access).putLong("ha_expires", expires);
        if (refresh != null) ed.putString("ha_refresh", refresh);
        ed.apply();
    }

    /** DeskDash Hub base URL; "http://" and, for plain http, the default port 8780 are added when missing. */
    public String hubUrl() {
        String u = sp().getString("hub_url", "").trim();
        if (u.isEmpty()) return u;
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://" + u;
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        int s = u.indexOf("//") + 2, e = u.indexOf('/', s);
        if (u.startsWith("http://") && u.substring(s, e < 0 ? u.length() : e).indexOf(':') < 0) {
            u = e < 0 ? u + ":8780" : u.substring(0, e) + ":8780" + u.substring(e);
        }
        return u;
    }
    public String hubToken() { return sp().getString("hub_token", "").trim(); }

    /** Bambu Lab printers as JSON, see Bambu. */
    public String bambu() { return sp().getString("bambu", "[]"); }
    public void setBambu(String json) { sp().edit().putString("bambu", json).apply(); }

    /** Strip at the top with running prints, washes, calls and alarms. */
    public boolean live() { return sp().getBoolean("live", true); }
    /** Calls: minutes before a meeting when the dashboard starts reminding. */
    public int callRemind() { return sp().getInt("call_remind", 5); }
    /** Where "Подключиться" opens a meeting: "pc" or "phone". */
    public String callJoin() { return sp().getString("call_join", "pc"); }

    public String yaClientId() { return sp().getString("ya_client_id", "").trim(); }
    public String yaToken() { return sp().getString("ya_token", "").trim(); }
    public void setYaToken(String t) { sp().edit().putString("ya_token", t).apply(); }
}
