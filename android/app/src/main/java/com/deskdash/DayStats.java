package com.deskdash;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.time.LocalDate;

/**
 * What the dashboard itself counts during the day: music listened to, tracks, smart home taps.
 * The PC's part (time at the computer, apps, calls) comes from DeskDash.exe; StatsPanel shows both.
 * Kept per day for two weeks, shared by all profiles (it's the same desk).
 */
final class DayStats {
    private static final String FILE = "deskdash_day";

    private DayStats() {}

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static JSONObject today(Context c) {
        return day(c, LocalDate.now());
    }

    static JSONObject day(Context c, LocalDate d) {
        try {
            return new JSONObject(sp(c).getString(d.toString(), "{}"));
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    /** Adds n to a counter of today: "music_sec", "tracks", "home". */
    static void add(Context c, String key, int n) {
        SharedPreferences s = sp(c);
        String k = LocalDate.now().toString();
        try {
            JSONObject o = new JSONObject(s.getString(k, "{}"));
            o.put(key, o.optInt(key) + n);
            SharedPreferences.Editor ed = s.edit().putString(k, o.toString());
            String old = LocalDate.now().minusDays(15).toString();
            for (String day : s.getAll().keySet()) if (day.compareTo(old) < 0) ed.remove(day);
            ed.apply();
        } catch (JSONException ignored) {
            // only numbers go in
        }
    }
}
