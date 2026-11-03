package com.deskdash;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * All settings in one JSON file: every profile (theme, desks, logins to the smart home, PC, printers)
 * and the list of profiles. Saved and opened through the system file picker, so it needs no permission;
 * moving to a new phone or reinstalling with another signature keeps everything.
 */
final class Backup {
    private static final String GLOBAL = "deskdash_profiles";
    /** Big or per-moment values a restore doesn't need. */
    private static final Set<String> SKIP = new HashSet<>();

    static {
        SKIP.add("weather_cache");
        SKIP.add("clip_seen");
    }

    private Backup() {}

    static String export(Context c) throws Exception {
        JSONObject o = new JSONObject().put("app", "deskdash").put("v", 1).put("at", System.currentTimeMillis());
        o.put("global", dump(c.getSharedPreferences(GLOBAL, Context.MODE_PRIVATE)));
        JSONObject profiles = new JSONObject();
        for (Profiles.Profile p : Profiles.list(c)) profiles.put(p.id, dump(Profiles.prefs(c, p.id)));
        o.put("profiles", profiles);
        return o.toString(1);
    }

    /** Puts a saved file back; returns how many profiles came back. */
    static int restore(Context c, String json) throws Exception {
        JSONObject o = new JSONObject(json);
        if (!"deskdash".equals(o.optString("app"))) throw new Exception("это не резервная копия DeskDash");
        JSONObject profiles = o.getJSONObject("profiles");
        load(c.getSharedPreferences(GLOBAL, Context.MODE_PRIVATE), o.getJSONObject("global"));
        int n = 0;
        for (java.util.Iterator<String> it = profiles.keys(); it.hasNext(); ) {
            String id = it.next();
            load(Profiles.prefs(c, id), profiles.getJSONObject(id));
            n++;
        }
        return n;
    }

    private static JSONObject dump(SharedPreferences sp) throws Exception {
        JSONObject out = new JSONObject();
        for (Map.Entry<String, ?> e : sp.getAll().entrySet()) {
            if (SKIP.contains(e.getKey())) continue;
            Object v = e.getValue();
            String t = v instanceof Boolean ? "b" : v instanceof Integer ? "i" : v instanceof Long ? "l"
                    : v instanceof Float ? "f" : v instanceof Set ? "set" : "s";
            Object val = v instanceof Set ? new JSONArray((Set<?>) v) : v instanceof Float ? ((Float) v).doubleValue() : v;
            out.put(e.getKey(), new JSONObject().put("t", t).put("v", val));
        }
        return out;
    }

    private static void load(SharedPreferences sp, JSONObject in) throws Exception {
        SharedPreferences.Editor ed = sp.edit().clear();
        for (java.util.Iterator<String> it = in.keys(); it.hasNext(); ) {
            String k = it.next();
            JSONObject e = in.getJSONObject(k);
            switch (e.optString("t")) {
                case "b": ed.putBoolean(k, e.getBoolean("v")); break;
                case "i": ed.putInt(k, e.getInt("v")); break;
                case "l": ed.putLong(k, e.getLong("v")); break;
                case "f": ed.putFloat(k, (float) e.getDouble("v")); break;
                case "set": {
                    Set<String> set = new HashSet<>();
                    JSONArray a = e.getJSONArray("v");
                    for (int i = 0; i < a.length(); i++) set.add(a.getString(i));
                    ed.putStringSet(k, set);
                    break;
                }
                default: ed.putString(k, e.getString("v")); break;
            }
        }
        ed.commit();
    }
}
