package com.deskdash;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * People sharing the dashboard. Each profile keeps all of its settings (theme, calendar, PC,
 * smart home, desks) in its own SharedPreferences file; the first one uses the original
 * "deskdash" file, so settings from older versions become the first profile as they are.
 */
final class Profiles {
    static final class Profile {
        final String id;
        final String name;

        Profile(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    private Profiles() {}

    private static SharedPreferences global(Context c) {
        return c.getSharedPreferences("deskdash_profiles", Context.MODE_PRIVATE);
    }

    private static String file(String id) {
        return "0".equals(id) ? "deskdash" : "deskdash_" + id;
    }

    static SharedPreferences prefs(Context c, String id) {
        return c.getSharedPreferences(file(id), Context.MODE_PRIVATE);
    }

    static List<Profile> list(Context c) {
        List<Profile> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(global(c).getString("profiles", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new Profile(o.getString("id"), o.optString("name", "Профиль")));
            }
        } catch (Exception ignored) {
            // broken list: fall back to the default profile
        }
        if (out.isEmpty()) out.add(new Profile("0", "Основной"));
        return out;
    }

    static String active(Context c) {
        String id = global(c).getString("active", "0");
        for (Profile p : list(c)) if (p.id.equals(id)) return id;
        return list(c).get(0).id;
    }

    static String name(Context c, String id) {
        for (Profile p : list(c)) if (p.id.equals(id)) return p.name;
        return "";
    }

    static void setActive(Context c, String id) {
        global(c).edit().putString("active", id).apply();
    }

    /** Creates a profile, empty or as a copy of the active one, and returns its id. */
    static String add(Context c, String name, boolean copyActive) {
        String id = Long.toString(System.currentTimeMillis(), 36);
        if (copyActive) {
            SharedPreferences.Editor ed = prefs(c, id).edit();
            for (Map.Entry<String, ?> e : prefs(c, active(c)).getAll().entrySet()) {
                Object v = e.getValue();
                if (v instanceof String) ed.putString(e.getKey(), (String) v);
                else if (v instanceof Integer) ed.putInt(e.getKey(), (Integer) v);
                else if (v instanceof Boolean) ed.putBoolean(e.getKey(), (Boolean) v);
                else if (v instanceof Long) ed.putLong(e.getKey(), (Long) v);
                else if (v instanceof Float) ed.putFloat(e.getKey(), (Float) v);
                else if (v instanceof Set) {
                    @SuppressWarnings("unchecked") Set<String> s = (Set<String>) v;
                    ed.putStringSet(e.getKey(), s);
                }
            }
            ed.commit();
        }
        List<Profile> l = list(c);
        l.add(new Profile(id, name));
        save(c, l);
        return id;
    }

    static void rename(Context c, String id, String name) {
        List<Profile> l = list(c);
        for (int i = 0; i < l.size(); i++) if (l.get(i).id.equals(id)) l.set(i, new Profile(id, name));
        save(c, l);
    }

    /** Deletes a profile and its settings; the last remaining profile can't be deleted. */
    static boolean remove(Context c, String id) {
        List<Profile> l = list(c);
        if (l.size() < 2) return false;
        boolean wasActive = active(c).equals(id);
        for (int i = 0; i < l.size(); i++) if (l.get(i).id.equals(id)) l.remove(i--);
        save(c, l);
        prefs(c, id).edit().clear().commit();
        if (!"0".equals(id)) c.deleteSharedPreferences(file(id));
        if (wasActive) setActive(c, l.get(0).id);
        return true;
    }

    private static void save(Context c, List<Profile> l) {
        JSONArray a = new JSONArray();
        try {
            for (Profile p : l) a.put(new JSONObject().put("id", p.id).put("name", p.name));
        } catch (Exception ignored) {
            // only strings go in
        }
        global(c).edit().putString("profiles", a.toString()).apply();
    }
}
