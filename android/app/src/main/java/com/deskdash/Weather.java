package com.deskdash;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Weather from Open-Meteo: free, no key and no sign-up. Now, the next hours and a few days for the place
 * set in the settings (found by the phone's IP address on the first start, or searched by name).
 * The last answer is kept in the profile, so the dashboard shows it at once after a restart or offline.
 */
final class Weather {
    static final class Hour {
        long time;
        double temp;
        int code, rain;
    }

    static final class Day {
        LocalDate date;
        /** Midnight at the place. */
        long start;
        double max, min;
        int code, rain;
        long sunrise, sunset;
    }

    /** Clear, a few clouds, cloudy, fog, rain, snow, storm: what the icon draws. */
    static final int CLEAR = 0, PARTLY = 1, CLOUDY = 2, FOG = 3, RAIN = 4, SNOW = 5, STORM = 6;

    double temp = Double.NaN, feels = Double.NaN, wind, humidity;
    int code = -1;
    boolean isDay = true;
    long fetchedAt;
    /** The place's offset from UTC: its "today" can differ from the phone's. */
    int offsetSec;
    final List<Hour> hours = new ArrayList<>();
    final List<Day> days = new ArrayList<>();

    private static final long FRESH_MS = 15 * 60_000L;

    static boolean configured(Prefs p) {
        return p.raw().contains("weather_lat");
    }

    static String place(Prefs p) {
        return p.raw().getString("weather_city", "");
    }

    static void setPlace(Prefs p, String name, double lat, double lon) {
        p.raw().edit().putString("weather_city", name).putFloat("weather_lat", (float) lat)
                .putFloat("weather_lon", (float) lon).remove("weather_cache").apply();
    }

    /** Last saved forecast, or null. */
    static Weather cached(Prefs p) {
        String s = p.raw().getString("weather_cache", "");
        if (s.isEmpty()) return null;
        try {
            return parse(new JSONObject(s));
        } catch (Exception e) {
            return null;
        }
    }

    boolean fresh() {
        return System.currentTimeMillis() - fetchedAt < FRESH_MS;
    }

    /** Asks Open-Meteo and saves the answer. Background thread only. */
    static Weather fetch(Prefs p) throws Exception {
        float lat = p.raw().getFloat("weather_lat", 0), lon = p.raw().getFloat("weather_lon", 0);
        String url = String.format(Locale.ROOT, "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                + "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m,relative_humidity_2m,is_day"
                + "&hourly=temperature_2m,weather_code,precipitation_probability"
                + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,sunrise,sunset"
                + "&wind_speed_unit=ms&timezone=auto&forecast_days=4&timeformat=unixtime", lat, lon);
        JSONObject o = new JSONObject(Net.http("GET", url, null, null, null, 10_000));
        o.put("fetched", System.currentTimeMillis());
        Weather w = parse(o);
        p.raw().edit().putString("weather_cache", o.toString()).apply();
        return w;
    }

    private static Weather parse(JSONObject o) {
        Weather w = new Weather();
        w.fetchedAt = o.optLong("fetched");
        w.offsetSec = o.optInt("utc_offset_seconds", ZoneId.systemDefault().getRules().getOffset(Instant.now()).getTotalSeconds());
        JSONObject cur = o.optJSONObject("current");
        if (cur != null) {
            w.temp = cur.optDouble("temperature_2m", Double.NaN);
            w.feels = cur.optDouble("apparent_temperature", Double.NaN);
            w.code = cur.optInt("weather_code", -1);
            w.wind = cur.optDouble("wind_speed_10m", 0);
            w.humidity = cur.optDouble("relative_humidity_2m", 0);
            w.isDay = cur.optInt("is_day", 1) == 1;
        }
        JSONObject h = o.optJSONObject("hourly");
        if (h != null) {
            JSONArray t = h.optJSONArray("time"), te = h.optJSONArray("temperature_2m"),
                    c = h.optJSONArray("weather_code"), r = h.optJSONArray("precipitation_probability");
            for (int i = 0; t != null && i < t.length(); i++) {
                Hour x = new Hour();
                x.time = t.optLong(i) * 1000;
                x.temp = te == null ? Double.NaN : te.optDouble(i, Double.NaN);
                x.code = c == null ? -1 : c.optInt(i, -1);
                x.rain = r == null ? 0 : r.optInt(i, 0);
                w.hours.add(x);
            }
        }
        JSONObject d = o.optJSONObject("daily");
        if (d != null) {
            JSONArray t = d.optJSONArray("time"), mx = d.optJSONArray("temperature_2m_max"), mn = d.optJSONArray("temperature_2m_min"),
                    c = d.optJSONArray("weather_code"), r = d.optJSONArray("precipitation_probability_max"),
                    sr = d.optJSONArray("sunrise"), ss = d.optJSONArray("sunset");
            for (int i = 0; t != null && i < t.length(); i++) {
                Day x = new Day();
                // daily "time" is midnight at the place
                x.start = t.optLong(i) * 1000;
                x.date = Instant.ofEpochSecond(t.optLong(i) + w.offsetSec).atZone(java.time.ZoneOffset.UTC).toLocalDate();
                x.max = mx == null ? Double.NaN : mx.optDouble(i, Double.NaN);
                x.min = mn == null ? Double.NaN : mn.optDouble(i, Double.NaN);
                x.code = c == null ? -1 : c.optInt(i, -1);
                x.rain = r == null ? 0 : r.optInt(i, 0);
                x.sunrise = sr == null ? 0 : sr.optLong(i) * 1000;
                x.sunset = ss == null ? 0 : ss.optLong(i) * 1000;
                w.days.add(x);
            }
        }
        return w;
    }

    Day day(LocalDate date) {
        for (Day d : days) if (d.date.equals(date)) return d;
        return null;
    }

    /** Whether the sun is up at that moment (for day or night icons); true when the forecast has no sun times. */
    boolean dayAt(long t) {
        boolean any = false;
        for (Day d : days) {
            if (d.sunrise == 0) continue;
            any = true;
            if (t >= d.sunrise && t < d.sunset) return true;
        }
        return !any;
    }

    /** The date at the place right now. */
    LocalDate placeDate() {
        return Instant.now().plusSeconds(offsetSec).atZone(java.time.ZoneOffset.UTC).toLocalDate();
    }

    /** Today at the place (sunrise, sunset, max and min). */
    Day today() {
        return day(placeDate());
    }

    /** The next n hours starting with the current one. */
    List<Hour> next(int n, long now) {
        List<Hour> out = new ArrayList<>();
        for (Hour h : hours) {
            if (h.time + 3_600_000L <= now) continue;
            out.add(h);
            if (out.size() >= n) break;
        }
        return out;
    }

    /** "Дождь около 15:00" when rain or snow is likely within two hours and it is dry now, else null. */
    String precipitationSoon(long now) {
        if (wet(code)) return null;
        for (Hour h : next(3, now)) {
            if (h.time <= now || h.time - now > 2 * 3_600_000L) continue;
            if (wet(h.code) && h.rain >= 50) {
                String at = Instant.ofEpochMilli(h.time).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("H:mm"));
                return (group(h.code) == SNOW ? "Снег" : "Дождь") + " около " + at + " · " + h.rain + "%";
            }
        }
        return null;
    }

    /** Night by the sun: from half an hour after sunset until sunrise. Null when the forecast has no sun times. */
    Boolean dark(long now) {
        Day d = today();
        if (d == null || d.sunrise == 0 || d.sunset == 0) return null;
        return now < d.sunrise || now >= d.sunset + 30 * 60_000L;
    }

    /** "Утром +2°, дождь": tomorrow's morning for the night clock. */
    String morning() {
        Day t = day(placeDate().plusDays(1));
        if (t == null) return null;
        long at = t.start + 8 * 3_600_000L;
        for (Hour h : hours) {
            if (Math.abs(h.time - at) < 1_800_000L) {
                return "Утром " + deg(h.temp) + ", " + text(h.code).toLowerCase(Locale.getDefault());
            }
        }
        return null;
    }

    static boolean wet(int code) {
        int g = group(code);
        return g == RAIN || g == SNOW || g == STORM;
    }

    static int group(int code) {
        if (code <= 0) return CLEAR;
        if (code <= 2) return PARTLY;
        if (code == 3) return CLOUDY;
        if (code == 45 || code == 48) return FOG;
        if (code >= 95) return STORM;
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return SNOW;
        return RAIN;
    }

    static String text(int code) {
        if (code < 0) return "Нет данных";
        switch (code) {
            case 0: return "Ясно";
            case 1: return "Почти ясно";
            case 2: return "Переменная облачность";
            case 3: return "Пасмурно";
            case 45: case 48: return "Туман";
            case 51: case 53: case 55: return "Морось";
            case 56: case 57: case 66: case 67: return "Ледяной дождь";
            case 61: return "Небольшой дождь";
            case 63: return "Дождь";
            case 65: return "Сильный дождь";
            case 71: return "Небольшой снег";
            case 73: return "Снег";
            case 75: return "Сильный снег";
            case 77: return "Снежная крупа";
            case 80: case 81: return "Ливень";
            case 82: return "Сильный ливень";
            case 85: case 86: return "Снегопад";
            default: return code >= 95 ? "Гроза" : "Осадки";
        }
    }

    static String deg(double v) {
        if (Double.isNaN(v)) return "—";
        long r = Math.round(v);
        return (r > 0 ? "+" : r < 0 ? "−" : "") + Math.abs(r) + "°";
    }

    static String hour(long ms) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("H:mm"));
    }

    // ---------------------------------------------------------------- where

    /** {name, lat, lon} of the phone's internet connection: good enough for a city, no location permission. */
    static String[] locate() throws Exception {
        try {
            JSONObject o = new JSONObject(Net.http("GET", "http://ip-api.com/json/?lang=ru&fields=status,city,lat,lon", null, null, null, 6000));
            if ("success".equals(o.optString("status"))) {
                return new String[]{o.optString("city"), String.valueOf(o.optDouble("lat")), String.valueOf(o.optDouble("lon"))};
            }
        } catch (Exception ignored) {
            // try the second service
        }
        JSONObject o = new JSONObject(Net.http("GET", "https://ipapi.co/json/", null, null, null, 6000));
        if (!o.has("latitude")) throw new Exception("не удалось определить город");
        return new String[]{o.optString("city"), String.valueOf(o.optDouble("latitude")), String.valueOf(o.optDouble("longitude"))};
    }

    /** Places by name: {"Москва, Москва", lat, lon}. */
    static List<String[]> search(String q) throws Exception {
        String url = "https://geocoding-api.open-meteo.com/v1/search?count=6&language=ru&name="
                + URLEncoder.encode(q.trim(), StandardCharsets.UTF_8.name());
        JSONArray a = new JSONObject(Net.http("GET", url, null, null, null, 8000)).optJSONArray("results");
        List<String[]> out = new ArrayList<>();
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject r = a.getJSONObject(i);
            String name = r.optString("name"), region = r.optString("admin1"), country = r.optString("country");
            StringBuilder full = new StringBuilder(name);
            if (!region.isEmpty() && !region.equals(name)) full.append(", ").append(region);
            if (!country.isEmpty() && !"Россия".equals(country)) full.append(", ").append(country);
            out.add(new String[]{full.toString(), String.valueOf(r.optDouble("latitude")), String.valueOf(r.optDouble("longitude"))});
        }
        return out;
    }
}
