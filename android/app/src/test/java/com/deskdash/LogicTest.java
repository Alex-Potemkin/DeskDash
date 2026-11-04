package com.deskdash;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Plain-Java logic of the dashboard: runs on the JVM, no phone needed (./gradlew test). */
public class LogicTest {
    // ---------------------------------------------------------------- updates

    @Test
    public void versionsCompareByNumbers() {
        assertTrue(Updates.newer("1.10", "1.9"));
        assertTrue(Updates.newer("1.4", "1.3"));
        assertTrue(Updates.newer("2.0", "1.99"));
        assertFalse(Updates.newer("1.4", "1.4"));
        assertFalse(Updates.newer("1.3.9", "1.4"));
    }

    // ---------------------------------------------------------------- meetings

    @Test
    public void meetingLinksAreFoundInAnyText() {
        Meetings.Meeting z = Meetings.find("Созвон: https://us02web.zoom.us/j/123456789?pwd=abc. До встречи", null);
        assertNotNull(z);
        assertEquals("zoom", z.platform.id);
        assertEquals("https://us02web.zoom.us/j/123456789?pwd=abc", z.url);
        assertEquals("telemost", Meetings.find("https://telemost.yandex.ru/j/1234567", null).platform.id);
        assertEquals("meet", Meetings.find("meet.google.com/abc-defg-hij нет, вот: https://meet.google.com/abc-defg-hij", null).platform.id);
        assertNull(Meetings.find("просто текст без ссылок", null));
    }

    // ---------------------------------------------------------------- weather

    @Test
    public void weatherCodesGroupAndRead() {
        assertEquals(Weather.CLEAR, Weather.group(0));
        assertEquals(Weather.RAIN, Weather.group(63));
        assertEquals(Weather.SNOW, Weather.group(73));
        assertEquals(Weather.STORM, Weather.group(95));
        assertTrue(Weather.wet(80));
        assertFalse(Weather.wet(2));
        assertEquals("+3°", Weather.deg(3.2));
        assertEquals("−5°", Weather.deg(-4.6));
        assertEquals("0°", Weather.deg(0.2));
    }

    @Test
    public void metNoStandsInForOpenMeteo() throws Exception {
        assertEquals(80, Weather.metCode("lightrainshowers_day"));
        assertEquals(95, Weather.metCode("heavyrainandthunder"));
        assertEquals(68, Weather.metCode("sleet"));
        assertEquals(Weather.SNOW, Weather.group(Weather.metCode("snowshowers_night")));
        assertEquals(1, Weather.metCode("fair_polartwilight"));

        // Moscow, 3 November: sunrise about 7:38, sunset about 16:48 (UTC+3)
        java.time.ZoneId msk = java.time.ZoneId.of("Europe/Moscow");
        long[] sun = Weather.sun(55.75, 37.62, java.time.LocalDate.of(2026, 11, 3));
        java.time.LocalTime rise = java.time.Instant.ofEpochMilli(sun[0]).atZone(msk).toLocalTime();
        java.time.LocalTime set = java.time.Instant.ofEpochMilli(sun[1]).atZone(msk).toLocalTime();
        assertTrue(rise.toString(), rise.isAfter(java.time.LocalTime.of(7, 30)) && rise.isBefore(java.time.LocalTime.of(7, 45)));
        assertTrue(set.toString(), set.isAfter(java.time.LocalTime.of(16, 40)) && set.isBefore(java.time.LocalTime.of(16, 55)));

        String m = "{\"properties\":{\"timeseries\":["
                + "{\"time\":\"2026-11-03T12:00:00Z\",\"data\":{\"instant\":{\"details\":{\"air_temperature\":3.0,\"wind_speed\":5.0,\"relative_humidity\":80}},"
                + "\"next_1_hours\":{\"summary\":{\"symbol_code\":\"cloudy\"},\"details\":{\"precipitation_amount\":0}}}},"
                + "{\"time\":\"2026-11-03T13:00:00Z\",\"data\":{\"instant\":{\"details\":{\"air_temperature\":4.0}},"
                + "\"next_1_hours\":{\"summary\":{\"symbol_code\":\"lightrain\"},\"details\":{\"precipitation_amount\":0.4}}}},"
                + "{\"time\":\"2026-11-04T12:00:00Z\",\"data\":{\"instant\":{\"details\":{\"air_temperature\":-1.0}},"
                + "\"next_6_hours\":{\"summary\":{\"symbol_code\":\"snow\"},\"details\":{\"precipitation_amount\":2}}}}]}}";
        long now = java.time.Instant.parse("2026-11-03T12:20:00Z").toEpochMilli();
        org.json.JSONObject o = Weather.fromMetNo(new org.json.JSONObject(m), 55.75, 37.62, msk, now);
        org.json.JSONObject cur = o.getJSONObject("current");
        assertEquals(3.0, cur.getDouble("temperature_2m"), 1e-9);
        assertTrue(cur.getDouble("apparent_temperature") < 3.0);
        assertEquals(1, cur.getInt("is_day"));
        assertEquals(10800, o.getInt("utc_offset_seconds"));
        assertEquals(2, o.getJSONObject("hourly").getJSONArray("time").length());
        org.json.JSONObject daily = o.getJSONObject("daily");
        assertEquals(2, daily.getJSONArray("time").length());
        assertEquals(61, daily.getJSONArray("weather_code").getInt(0));
        assertEquals(4.0, daily.getJSONArray("temperature_2m_max").getDouble(0), 1e-9);
        assertEquals(70, daily.getJSONArray("precipitation_probability_max").getInt(0));
        assertEquals(73, daily.getJSONArray("weather_code").getInt(1));
    }

    // ---------------------------------------------------------------- smart home readings

    private static HomeDevice sensor(String name, String state) {
        HomeDevice d = new HomeDevice();
        d.id = "ha:" + name;
        d.name = name;
        d.kind = HomeDevice.SENSOR;
        d.state = state;
        return d;
    }

    @Test
    public void sensorsGiveTemperatureHumidityAndPower() {
        assertEquals(22.4, sensor("Комната", "22.4°C").temperature(), 1e-9);
        assertEquals(-3.5, sensor("Улица", "-3,5° · 80%").temperature(), 1e-9);
        assertEquals(80, sensor("Улица", "-3,5° · 80%").humidity());
        assertTrue(Double.isNaN(sensor("Дверь", "Закрыто").temperature()));
        assertTrue(sensor("Балкон", "1°").outdoor());
        assertTrue(sensor("Street sensor", "1°").outdoor());
        assertFalse(sensor("Спальня", "21°").outdoor());
        assertEquals(1500, EnergyPanel.watts(sensor("Чайник", "1,5 кВт")), 1e-9);
        assertEquals(42, EnergyPanel.watts(sensor("Розетка", "42 W")), 1e-9);
        assertTrue(Double.isNaN(EnergyPanel.watts(sensor("Счётчик", "123 кВт·ч"))));
        assertEquals(123, EnergyPanel.kwh(sensor("Счётчик", "123 кВт·ч")), 1e-9);
    }

    // ---------------------------------------------------------------- modes

    @Test
    public void modeSchedules() {
        Modes.Mode w = new Modes.Mode();
        w.from = 9 * 60;
        w.to = 18 * 60;
        for (int d = 1; d <= 5; d++) w.days.add(d);
        assertTrue(w.scheduled(10 * 60, 1));
        assertFalse(w.scheduled(10 * 60, 6));
        assertFalse(w.scheduled(19 * 60, 2));
        Modes.Mode night = new Modes.Mode();
        night.from = 23 * 60;
        night.to = 7 * 60;
        assertTrue(night.scheduled(23 * 60 + 30, 3));
        assertTrue(night.scheduled(6 * 60, 4));
        assertFalse(night.scheduled(12 * 60, 4));
    }

    // ---------------------------------------------------------------- voice

    private static final class FakeHost implements Voice.Host {
        final Map<String, HomeDevice> devs = new LinkedHashMap<>();
        final List<String> done = new ArrayList<>();

        FakeHost() {
            add("ha:light.kitchen", "Свет на кухне", "Кухня", HomeDevice.LIGHT);
            add("ha:light.desk", "Лампа у стола", "Кабинет", HomeDevice.LIGHT);
            add("ha:scene.movie", "Кино", "", HomeDevice.SCENE);
        }

        void add(String id, String name, String room, int kind) {
            HomeDevice d = new HomeDevice();
            d.id = id;
            d.name = name;
            d.room = room;
            d.kind = kind;
            devs.put(id, d);
        }

        @Override
        public Map<String, HomeDevice> devices() {
            return devs;
        }

        @Override
        public void device(HomeDevice d, String action, int value) {
            done.add(action + ":" + d.id + (value > 0 ? ":" + value : ""));
        }

        @Override
        public void allOff() {
            done.add("alloff");
        }

        @Override
        public boolean mode(String name) {
            done.add("mode:" + name);
            return true;
        }

        @Override
        public void timer(String label, long ms) {
            done.add("timer:" + ms);
        }

        @Override
        public void pomodoro() {
            done.add("pomodoro");
        }

        @Override
        public void media(String what) {
            done.add("media:" + what);
        }

        @Override
        public String weather() {
            return "";
        }

        @Override
        public String agenda() {
            return "";
        }

        @Override
        public void say(String text) {}
    }

    @Test
    public void voiceCommandsAreUnderstood() {
        FakeHost h = new FakeHost();
        Voice.handle("Включи свет на кухне", h);
        Voice.handle("выключи лампу у стола", h);
        Voice.handle("поставь таймер на десять минут", h);
        Voice.handle("таймер 30 секунд", h);
        Voice.handle("запусти кино", h);
        Voice.handle("режим работа", h);
        Voice.handle("яркость лампы у стола 40 процентов", h);
        Voice.handle("следующий трек", h);
        Voice.handle("выключи всё", h);
        assertEquals(List.of("on:ha:light.kitchen", "off:ha:light.desk", "timer:600000", "timer:30000", "run:ha:scene.movie",
                "mode:работа", "brightness:ha:light.desk:40", "media:next", "alloff"), h.done);
    }

    @Test
    public void tappedTileWaitsForTheServiceToCatchUp() {
        HomeDevice lamp = new HomeDevice();
        lamp.on = false;
        lamp.brightness = 40;
        SmartHome.Change on = x -> x.on = true;
        assertFalse(SmartHome.shows(lamp, on));
        lamp.on = true;
        assertTrue(SmartHome.shows(lamp, on));
        // Home Assistant keeps brightness as 0..255: 60 % comes back as 59 %
        SmartHome.Change dim = x -> {
            x.brightness = 60;
            x.on = true;
        };
        lamp.brightness = 59;
        assertTrue(SmartHome.shows(lamp, dim));
        lamp.brightness = 40;
        assertFalse(SmartHome.shows(lamp, dim));
    }

    // ---------------------------------------------------------------- the week, themes, moving widgets

    @Test
    public void metNoGivesAWeek() throws Exception {
        StringBuilder m = new StringBuilder("{\"properties\":{\"timeseries\":[");
        java.time.Instant t = java.time.Instant.parse("2026-11-03T12:00:00Z");
        for (int i = 0; i < 40; i++) { // ten days every six hours, as the far end of its forecast comes
            if (i > 0) m.append(',');
            m.append("{\"time\":\"").append(t.plusSeconds(i * 6 * 3600L)).append("\",\"data\":{\"instant\":{\"details\":{\"air_temperature\":")
                    .append(i % 4).append(",\"wind_speed\":").append(i % 7).append("}},\"next_6_hours\":{\"summary\":{\"symbol_code\":\"cloudy\"},")
                    .append("\"details\":{\"precipitation_amount\":0}}}}");
        }
        m.append("]}}");
        org.json.JSONObject daily = Weather.fromMetNo(new org.json.JSONObject(m.toString()), 55.75, 37.62,
                java.time.ZoneId.of("Europe/Moscow"), t.toEpochMilli()).getJSONObject("daily");
        assertEquals(8, daily.getJSONArray("time").length());
        assertEquals(8, daily.getJSONArray("wind_speed_10m_max").length());
        assertTrue(daily.getJSONArray("wind_speed_10m_max").getDouble(3) > 0);
    }

    private static double luminance(int c) {
        double[] v = new double[3];
        for (int i = 0; i < 3; i++) {
            double x = (c >> (16 - 8 * i) & 0xFF) / 255.0;
            v[i] = x <= 0.03928 ? x / 12.92 : Math.pow((x + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * v[0] + 0.7152 * v[1] + 0.0722 * v[2];
    }

    private static double contrast(int a, int b) {
        double x = luminance(a), y = luminance(b);
        return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05);
    }

    @Test
    public void themesStayReadable() {
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (DashTheme t : DashTheme.ALL) {
            assertTrue(t.id, ids.add(t.id));
            if (t.isNight()) continue;
            assertTrue(t.id + " text", contrast(t.text, t.surface) >= 7);
            assertTrue(t.id + " dim", contrast(t.dim, t.surface) >= 3);
            assertTrue(t.id + " accent", contrast(t.accent, t.surface) >= 3);
            assertTrue(t.id + " events", t.events.length > 0);
        }
        assertTrue(DashTheme.choices(false).size() + DashTheme.choices(true).size() >= 15);
        for (DashTheme t : DashTheme.choices(true)) assertTrue(t.light);
        // the cycle reaches every theme and never a night one
        java.util.Set<String> seen = new java.util.HashSet<>();
        String id = "dark";
        for (int i = 0; i < DashTheme.ALL.length; i++) {
            id = DashTheme.next(id).id;
            assertFalse(id, DashTheme.byId(id).isNight());
            seen.add(id);
        }
        assertEquals(DashTheme.choices(false).size() + DashTheme.choices(true).size(), seen.size());
    }

    private static org.json.JSONArray columns(String... cols) throws Exception {
        org.json.JSONArray a = new org.json.JSONArray();
        for (String c : cols) {
            org.json.JSONArray b = new org.json.JSONArray();
            for (String t : c.isEmpty() ? new String[0] : c.split(",")) b.put(new org.json.JSONObject().put("t", t));
            a.put(new org.json.JSONObject().put("w", 50).put("blocks", b));
        }
        return a;
    }

    private static String layout(org.json.JSONArray cols) throws Exception {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < cols.length(); i++) {
            org.json.JSONArray b = cols.getJSONObject(i).getJSONArray("blocks");
            if (i > 0) s.append('|');
            for (int j = 0; j < b.length(); j++) s.append(j > 0 ? "," : "").append(b.getJSONObject(j).getString("t"));
        }
        return s.toString();
    }

    @Test
    public void widgetsMove() throws Exception {
        org.json.JSONArray c = columns("a,b,c", "d");
        Desks.move(c, 0, 0, 0, 2); // before c
        assertEquals("b,a,c|d", layout(c));
        Desks.move(c, 0, 2, 0, 0); // to the top
        assertEquals("c,b,a|d", layout(c));
        Desks.move(c, 0, 1, 0, 1); // dropped where it was
        assertEquals("c,b,a|d", layout(c));
        Desks.move(c, 0, 1, 0, 2); // the gap right after itself
        assertEquals("c,b,a|d", layout(c));
        Desks.move(c, 0, 0, 1, 1); // to the end of the next column
        assertEquals("b,a|d,c", layout(c));
        Desks.move(c, 1, 1, 0, 0);
        assertEquals("c,b,a|d", layout(c));

        org.json.JSONObject desk = new org.json.JSONObject().put("cols", columns("", "a", ""));
        Desks.dropEmptyColumns(desk);
        assertEquals("a", layout(desk.getJSONArray("cols")));
        desk.put("cols", columns("", ""));
        Desks.dropEmptyColumns(desk);
        assertEquals(1, desk.getJSONArray("cols").length());
    }
}
