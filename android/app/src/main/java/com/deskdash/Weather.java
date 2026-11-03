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
 * Weather from Open-Meteo, or from met.no where Open-Meteo is out of reach: both free, no key and no sign-up. Now, the next hours and a few days for the place
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

    /**
     * Asks Open-Meteo and saves the answer. Some networks don't reach its servers: then met.no answers, and
     * for six hours it is asked first, so the dashboard doesn't wait out a timeout every time. Background thread only.
     */
    static Weather fetch(Prefs p) throws Exception {
        float lat = p.raw().getFloat("weather_lat", 0), lon = p.raw().getFloat("weather_lon", 0);
        long now = System.currentTimeMillis();
        boolean metFirst = now < p.raw().getLong("weather_metno_until", 0);
        JSONObject o;
        try {
            o = metFirst ? metNo(lat, lon) : openMeteo(lat, lon);
        } catch (Exception first) {
            try {
                o = metFirst ? openMeteo(lat, lon) : metNo(lat, lon);
            } catch (Exception second) {
                throw first;
            }
            p.raw().edit().putLong("weather_metno_until", metFirst ? 0 : now + 6 * 3_600_000L).apply();
        }
        o.put("fetched", now);
        Weather w = parse(o);
        p.raw().edit().putString("weather_cache", o.toString()).apply();
        return w;
    }

    private static JSONObject openMeteo(float lat, float lon) throws Exception {
        String url = String.format(Locale.ROOT, "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                + "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m,relative_humidity_2m,is_day"
                + "&hourly=temperature_2m,weather_code,precipitation_probability"
                + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,sunrise,sunset"
                + "&wind_speed_unit=ms&timezone=auto&forecast_days=4&timeformat=unixtime", lat, lon);
        return new JSONObject(Net.http("GET", url, null, null, null, 6000));
    }

    private static JSONObject metNo(float lat, float lon) throws Exception {
        String url = String.format(Locale.ROOT, "https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=%.3f&lon=%.3f", lat, lon);
        // slow networks take ten seconds and more for its 40 KB
        return fromMetNo(new JSONObject(Net.http("GET", url, null, null, null, 25_000)), lat, lon, ZoneId.systemDefault(),
                System.currentTimeMillis());
    }

    /**
     * met.no's forecast in Open-Meteo's shape, so the cache and parse() stay the same. It has no chance of rain
     * (the amount stands in for it), no feels-like temperature (the wind chill does) and no sun times (counted here).
     */
    static JSONObject fromMetNo(JSONObject m, double lat, double lon, ZoneId zone, long now) throws Exception {
        JSONArray ts = m.getJSONObject("properties").getJSONArray("timeseries");
        JSONArray ht = new JSONArray(), hte = new JSONArray(), hc = new JSONArray(), hr = new JSONArray();
        java.util.TreeMap<LocalDate, double[]> byDay = new java.util.TreeMap<>(); // {max, min, code, rain}
        JSONObject cur = null;
        for (int i = 0; i < ts.length(); i++) {
            JSONObject e = ts.getJSONObject(i);
            long t = Instant.parse(e.getString("time")).toEpochMilli();
            JSONObject data = e.getJSONObject("data");
            JSONObject det = data.getJSONObject("instant").getJSONObject("details");
            double temp = det.optDouble("air_temperature", Double.NaN);
            JSONObject next = data.optJSONObject("next_1_hours");
            boolean hourly = next != null;
            if (next == null) next = data.optJSONObject("next_6_hours");
            int code = next == null ? -1 : metCode(next.getJSONObject("summary").optString("symbol_code"));
            double mm = next == null || next.optJSONObject("details") == null ? 0 : next.getJSONObject("details").optDouble("precipitation_amount", 0);
            int rain = mm >= 1 ? 90 : mm >= 0.3 ? 70 : mm > 0 ? 40 : 0;
            if (cur == null && t + 3_600_000L > now && !Double.isNaN(temp)) {
                double wind = det.optDouble("wind_speed", 0);
                cur = new JSONObject().put("temperature_2m", temp).put("apparent_temperature", windChill(temp, wind))
                        .put("weather_code", code).put("wind_speed_10m", wind)
                        .put("relative_humidity_2m", det.optDouble("relative_humidity", 0));
            }
            if (hourly) {
                ht.put(t / 1000);
                hte.put(Double.isNaN(temp) ? JSONObject.NULL : temp);
                hc.put(code);
                hr.put(rain);
            }
            LocalDate d = Instant.ofEpochMilli(t).atZone(zone).toLocalDate();
            double[] a = byDay.get(d);
            if (a == null) byDay.put(d, a = new double[]{Double.NaN, Double.NaN, -1, 0});
            if (!Double.isNaN(temp)) {
                a[0] = Double.isNaN(a[0]) ? temp : Math.max(a[0], temp);
                a[1] = Double.isNaN(a[1]) ? temp : Math.min(a[1], temp);
            }
            // the day's icon is its worst weather, as Open-Meteo does it
            if (code >= 0 && (a[2] < 0 || group(code) > group((int) a[2]) || group(code) == group((int) a[2]) && code > a[2])) a[2] = code;
            a[3] = Math.max(a[3], rain);
        }
        JSONArray dt = new JSONArray(), dmx = new JSONArray(), dmn = new JSONArray(), dc = new JSONArray(), dr = new JSONArray(),
                sr = new JSONArray(), ss = new JSONArray();
        for (java.util.Map.Entry<LocalDate, double[]> e : byDay.entrySet()) {
            if (dt.length() == 4) break;
            double[] a = e.getValue();
            long[] sun = sun(lat, lon, e.getKey());
            dt.put(e.getKey().atStartOfDay(zone).toEpochSecond());
            dmx.put(Double.isNaN(a[0]) ? JSONObject.NULL : a[0]);
            dmn.put(Double.isNaN(a[1]) ? JSONObject.NULL : a[1]);
            dc.put((int) a[2]);
            dr.put((int) a[3]);
            sr.put(sun[0] / 1000);
            ss.put(sun[1] / 1000);
        }
        if (cur != null) {
            long[] sun = sun(lat, lon, Instant.ofEpochMilli(now).atZone(zone).toLocalDate());
            cur.put("is_day", sun[0] == 0 || now >= sun[0] && now < sun[1] ? 1 : 0);
        }
        JSONObject o = new JSONObject().put("source", "met.no")
                .put("utc_offset_seconds", zone.getRules().getOffset(Instant.ofEpochMilli(now)).getTotalSeconds())
                .put("hourly", new JSONObject().put("time", ht).put("temperature_2m", hte).put("weather_code", hc)
                        .put("precipitation_probability", hr))
                .put("daily", new JSONObject().put("time", dt).put("temperature_2m_max", dmx).put("temperature_2m_min", dmn)
                        .put("weather_code", dc).put("precipitation_probability_max", dr).put("sunrise", sr).put("sunset", ss));
        if (cur != null) o.put("current", cur);
        return o;
    }

    /** met.no's symbol ("lightrainshowers_day") as the WMO code Open-Meteo uses; 68 is sleet. */
    static int metCode(String symbol) {
        int u = symbol.indexOf('_');
        String s = u > 0 ? symbol.substring(0, u) : symbol;
        if (s.contains("thunder")) return 95;
        switch (s) {
            case "clearsky": return 0;
            case "fair": return 1;
            case "partlycloudy": return 2;
            case "cloudy": return 3;
            case "fog": return 45;
            case "lightrain": return 61;
            case "rain": return 63;
            case "heavyrain": return 65;
            case "lightrainshowers": return 80;
            case "rainshowers": return 81;
            case "heavyrainshowers": return 82;
            case "lightsnow": return 71;
            case "snow": return 73;
            case "heavysnow": return 75;
            case "lightsnowshowers":
            case "snowshowers": return 85;
            case "heavysnowshowers": return 86;
            default: return s.contains("sleet") ? 68 : -1;
        }
    }

    /** How cold it feels in the wind (m/s); the temperature itself when it is warm or calm. */
    static double windChill(double t, double wind) {
        double v = wind * 3.6;
        if (t > 10 || v < 4.8) return t;
        double k = Math.pow(v, 0.16);
        return 13.12 + 0.6215 * t - 11.37 * k + 0.3965 * t * k;
    }

    /** {sunrise, sunset} in ms for a date, or {0, 0} in polar day or night (the NOAA formula, as on the hub). */
    static long[] sun(double lat, double lon, LocalDate day) {
        double g = 2 * Math.PI / 365 * (day.getDayOfYear() - 1);
        double eq = 229.18 * (0.000075 + 0.001868 * Math.cos(g) - 0.032077 * Math.sin(g)
                - 0.014615 * Math.cos(2 * g) - 0.040849 * Math.sin(2 * g));
        double decl = 0.006918 - 0.399912 * Math.cos(g) + 0.070257 * Math.sin(g) - 0.006758 * Math.cos(2 * g)
                + 0.000907 * Math.sin(2 * g) - 0.002697 * Math.cos(3 * g) + 0.00148 * Math.sin(3 * g);
        double la = Math.toRadians(lat);
        double x = Math.cos(Math.toRadians(90.833)) / (Math.cos(la) * Math.cos(decl)) - Math.tan(la) * Math.tan(decl);
        if (Math.abs(x) > 1) return new long[]{0, 0};
        double ha = Math.toDegrees(Math.acos(x));
        long midnight = day.atStartOfDay(java.time.ZoneOffset.UTC).toEpochSecond() * 1000;
        return new long[]{midnight + Math.round((720 - 4 * (lon + ha) - eq) * 60_000),
                midnight + Math.round((720 - 4 * (lon - ha) - eq) * 60_000)};
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
            case 68: return "Мокрый снег";
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
