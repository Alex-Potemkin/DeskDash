package com.deskdash;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Modes, like Focus on an iPhone: "Работа", "Отдых", "Сон", "Кино". A mode picks a desk and a look, can
 * force the night screen, sets the volume, turns devices on and off, runs a scene or a PC macro and silences
 * the phone. It is switched by hand (⋮ → «Режим», or the label in the bar), by a schedule, or by a meeting.
 * <pre>
 * {"id", "name", "icon": "desktop", "color": int, "desk": -1, "look": "", "night": false, "volume": -1,
 *  "on": [ids], "off": [ids], "macro": "", "dnd": false, "from": -1, "to": -1, "days": [1..7], "meeting": false}
 * </pre>
 */
final class Modes {
    static final class Mode {
        String id = "", name = "", icon = "star", look = "", macro = "";
        int color = Ui.BLUE, desk = -1, volume = -1, from = -1, to = -1;
        boolean night, dnd, meeting;
        List<String> on = new ArrayList<>(), off = new ArrayList<>();
        List<Integer> days = new ArrayList<>();

        JSONObject json() throws JSONException {
            JSONObject o = new JSONObject().put("id", id).put("name", name).put("icon", icon).put("color", color)
                    .put("desk", desk).put("look", look).put("night", night).put("volume", volume).put("macro", macro)
                    .put("dnd", dnd).put("from", from).put("to", to).put("meeting", meeting);
            o.put("on", new JSONArray(on)).put("off", new JSONArray(off)).put("days", new JSONArray(days));
            return o;
        }

        static Mode parse(JSONObject o) {
            Mode m = new Mode();
            m.id = o.optString("id");
            m.name = o.optString("name");
            m.icon = o.optString("icon", "star");
            m.color = o.optInt("color", Ui.BLUE);
            m.desk = o.optInt("desk", -1);
            m.look = o.optString("look");
            m.night = o.optBoolean("night");
            m.volume = o.optInt("volume", -1);
            m.macro = o.optString("macro");
            m.dnd = o.optBoolean("dnd");
            m.from = o.optInt("from", -1);
            m.to = o.optInt("to", -1);
            m.meeting = o.optBoolean("meeting");
            JSONArray a = o.optJSONArray("on");
            for (int i = 0; a != null && i < a.length(); i++) m.on.add(a.optString(i));
            a = o.optJSONArray("off");
            for (int i = 0; a != null && i < a.length(); i++) m.off.add(a.optString(i));
            a = o.optJSONArray("days");
            for (int i = 0; a != null && i < a.length(); i++) m.days.add(a.optInt(i));
            return m;
        }

        int iconRes() {
            switch (icon) {
                case "desktop": return R.drawable.ic_desktop;
                case "music": return R.drawable.ic_music;
                case "night": return R.drawable.ic_night;
                case "tv": return R.drawable.ic_tv;
                case "home": return R.drawable.ic_home;
                case "camera": return R.drawable.ic_camera;
                default: return R.drawable.ic_star;
            }
        }

        /** Whether the schedule says this mode is on at a minute of a weekday. */
        boolean scheduled(int minute, int weekday) {
            if (from < 0 || to < 0 || from == to) return false;
            if (!days.isEmpty() && !days.contains(weekday)) return false;
            return from < to ? minute >= from && minute < to : minute >= from || minute < to;
        }
    }

    private Modes() {}

    static List<Mode> load(Prefs p) {
        List<Mode> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(p.raw().getString("modes", ""));
            for (int i = 0; i < a.length(); i++) out.add(Mode.parse(a.getJSONObject(i)));
        } catch (JSONException e) {
            out.addAll(defaults());
        }
        return out;
    }

    static void save(Prefs p, List<Mode> modes) {
        JSONArray a = new JSONArray();
        try {
            for (Mode m : modes) a.put(m.json());
        } catch (JSONException ignored) {
            // only plain values
        }
        p.raw().edit().putString("modes", a.toString()).apply();
    }

    static Mode byId(List<Mode> modes, String id) {
        for (Mode m : modes) if (m.id.equals(id)) return m;
        return null;
    }

    /** Four modes to start with; the user changes what each does. */
    static List<Mode> defaults() {
        List<Mode> l = new ArrayList<>();
        Mode w = new Mode();
        w.id = "work";
        w.name = "Работа";
        w.icon = "desktop";
        w.color = Ui.BLUE;
        w.desk = 0;
        w.meeting = true;
        w.from = 9 * 60;
        w.to = 18 * 60;
        for (int d = 1; d <= 5; d++) w.days.add(d);
        l.add(w);
        Mode r = new Mode();
        r.id = "rest";
        r.name = "Отдых";
        r.icon = "music";
        r.color = Ui.ORANGE;
        r.look = "standby";
        l.add(r);
        Mode m = new Mode();
        m.id = "movie";
        m.name = "Кино";
        m.icon = "tv";
        m.color = Ui.PURPLE;
        m.look = "standby";
        m.dnd = true;
        l.add(m);
        Mode s = new Mode();
        s.id = "sleep";
        s.name = "Сон";
        s.icon = "night";
        s.color = Ui.INDIGO;
        s.night = true;
        s.dnd = true;
        s.volume = 0;
        l.add(s);
        return l;
    }
}
