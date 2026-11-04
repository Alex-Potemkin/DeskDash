package com.deskdash;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Desks of a profile: screens you swipe between, each made of columns of blocks.
 * <pre>
 * [{"name": "Главный", "cols": [
 *     {"w": 53, "blocks": [{"t": "clock", "h": 1}, {"t": "music", "h": 0}, {"t": "pc", "h": 0}]},
 *     {"w": 47, "blocks": [{"t": "calendar", "h": 1}]}]}]
 * </pre>
 * "w" is the column's share of the width, "h" the block's share of the column height (0 = as tall as
 * its content). Any block may have "bg": "" (its usual background), "card", "tint" or "clear". A "home" block also has
 * "title" and "items": [{"id": "ha:light.kitchen", "n": "Кухня"}], "weather" has "hours", "week" and "days", "photos" "every".
 */
final class Desks {
    static final String[][] TYPES = {
            {"clock", "Часы", "Время, дата и что дальше"}, {"calendar", "Календарь", "День или месяц с событиями"},
            {"music", "Музыка", "Что играет, кнопки плеера"}, {"pc", "Компьютер", "Макросы и состояние ПК"},
            {"home", "Умный дом", "Плитки устройств, какие выберешь"}, {"calls", "Созвоны", "Ближайшие встречи и кнопки звонка"},
            {"stats", "День", "Сколько времени за компьютером"}, {"weather", "Погода", "Сейчас, по часам и на неделю"},
            {"photos", "Фото", "Снимки с NAS сменяют друг друга"}, {"messages", "Сообщения", "Уведомления из мессенджеров"},
            {"timers", "Таймеры", "Таймер и помодоро"}, {"energy", "Энергия", "Мощность и кто тратит больше"},
    };

    private Desks() {}

    static JSONArray load(Prefs p) {
        try {
            JSONArray a = new JSONArray(p.desks());
            if (a.length() > 0) return a;
        } catch (JSONException ignored) {
            // nothing saved yet
        }
        return defaults();
    }

    static void save(Prefs p, JSONArray desks) {
        p.setDesks(desks.toString());
    }

    /** The layout DeskDash always had: clock, music and PC on the left, calendar on the right. */
    static JSONArray defaults() {
        try {
            return new JSONArray().put(new JSONObject().put("name", "Главный").put("cols", new JSONArray()
                    .put(column(53, block("clock"), block("music"), block("pc")))
                    .put(column(47, block("calendar")))));
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A new desk: small clock on the left, smart home tiles on the right. */
    static JSONObject newDesk(String name) {
        try {
            return new JSONObject().put("name", name).put("cols", new JSONArray()
                    .put(column(38, block("clock")))
                    .put(column(62, block("home"))));
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    static JSONObject column(int w, JSONObject... blocks) throws JSONException {
        JSONArray a = new JSONArray();
        for (JSONObject b : blocks) a.put(b);
        return new JSONObject().put("w", w).put("blocks", a);
    }

    static JSONObject block(String type) throws JSONException {
        JSONObject b = new JSONObject().put("t", type).put("h", defaultHeight(type));
        if ("home".equals(type)) b.put("title", "").put("items", new JSONArray());
        return b;
    }

    static int defaultHeight(String type) {
        return "music".equals(type) || "pc".equals(type) ? 0 : 1;
    }

    /** Clock and calendar need a share of the height; "as tall as the content" makes no sense for them. */
    static boolean needsHeight(String type) {
        return "clock".equals(type) || "calendar".equals(type);
    }

    /** Only smart home blocks may appear several times on one desk. */
    static boolean single(String type) {
        return !"home".equals(type);
    }

    static String typeName(String type) {
        for (String[] t : TYPES) if (t[0].equals(type)) return t[1];
        return type;
    }

    /** The settings page behind a kind of block, or null when it has none. */
    static String settingsPage(String type) {
        switch (type) {
            case "clock": return "look";
            case "energy":
            case "photos": return "home";
            case "messages":
            case "timers": return null;
            default: return type;
        }
    }

    /** Moves a block; toRow counts in the target column as it is now, with the block still in its place. */
    static void move(JSONArray cols, int col, int row, int toCol, int toRow) throws JSONException {
        JSONObject from = cols.getJSONObject(col), to = cols.getJSONObject(toCol);
        List<Object> src = toList(from.getJSONArray("blocks"));
        Object b = src.remove(row);
        List<Object> dst = col == toCol ? src : toList(to.getJSONArray("blocks"));
        int at = col == toCol && toRow > row ? toRow - 1 : toRow;
        dst.add(Math.max(0, Math.min(at, dst.size())), b);
        from.put("blocks", fromList(src));
        if (col != toCol) to.put("blocks", fromList(dst));
    }

    /** Takes away columns left without blocks; a desk keeps at least one. */
    static void dropEmptyColumns(JSONObject desk) throws JSONException {
        List<Object> kept = new ArrayList<>();
        JSONArray cols = desk.getJSONArray("cols");
        for (int i = 0; i < cols.length(); i++) {
            JSONArray b = cols.getJSONObject(i).optJSONArray("blocks");
            if (b != null && b.length() > 0) kept.add(cols.get(i));
        }
        if (kept.isEmpty() && cols.length() > 0) kept.add(cols.get(0));
        desk.put("cols", fromList(kept));
    }

    /** Puts new contents into the same array. */
    static void replace(JSONArray a, List<Object> l) {
        while (a.length() > 0) a.remove(a.length() - 1);
        for (Object o : l) a.put(o);
    }

    /** [id, name] pairs of a home block. */
    static List<String[]> items(JSONObject block) {
        List<String[]> out = new ArrayList<>();
        JSONArray a = block.optJSONArray("items");
        if (a == null) return out;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && !o.optString("id").isEmpty()) out.add(new String[]{o.optString("id"), o.optString("n")});
        }
        return out;
    }

    static List<Object> toList(JSONArray a) {
        List<Object> l = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) l.add(a.opt(i));
        return l;
    }

    static JSONArray fromList(List<Object> l) {
        JSONArray a = new JSONArray();
        for (Object o : l) a.put(o);
        return a;
    }
}
