package com.deskdash;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** One smart home thing shown as a tile: a Home Assistant entity, a Yandex device or a Yandex scenario. */
final class HomeDevice {
    static final int SWITCH = 0, LIGHT = 1, CLIMATE = 2, SENSOR = 3, SCENE = 4, COVER = 5, LOCK = 6, FAN = 7, MEDIA = 8,
            PRINTER = 9, VACUUM = 10, CAMERA = 11, APPLIANCE = 12;

    /** "ha:light.kitchen", "ya:<device id>", "yas:<scenario id>" or "bambu:<serial>". */
    String id = "";
    /** "ha", "ya" or "bambu". */
    String source = "";
    /** entity_id for Home Assistant, device/scenario id for Yandex. */
    String nativeId = "";
    String name = "", room = "";
    /** Extra text: sensor readings, HVAC mode, what is playing. */
    String state = "";
    int kind = SWITCH;
    boolean available = true, canToggle, on;
    boolean dimmable, colorTemp, color;
    /** 0..100, -1 when unknown. */
    int brightness = -1;
    int tempK = -1, minK = 2700, maxK = 6500;
    /** Yandex colour model ("rgb" / "hsv"), empty when the lamp has no colour. */
    String colorModel = "";
    double current = Double.NaN, target = Double.NaN, tMin = 16, tMax = 30, tStep = 0.5;
    /** Long jobs (3D print, washing): 0..100, -1 when there is no job. */
    int progress = -1;
    /** Minutes left of the job, -1 when unknown. */
    int remainingMin = -1;
    /** The job: file being printed, washing programme. */
    String job = "";
    /** More lines for the detail card: temperatures, layers, battery. */
    String detail = "";
    /** Picture for the tile and card: an http(s) URL, or "bambu:<serial>" for the printer's own camera. */
    String image = "";
    /** Something to look at now: leak, smoke, a failed print. */
    boolean alert;
    /** Extra buttons of the card ("pause", "resume", "stop", "light", "start", "dock") → the thing to call for it
     *  (a Home Assistant entity, or the action itself). */
    Map<String, String> cmds = new HashMap<>();

    HomeDevice copy() {
        HomeDevice d = new HomeDevice();
        d.id = id;
        d.source = source;
        d.nativeId = nativeId;
        d.name = name;
        d.room = room;
        d.state = state;
        d.kind = kind;
        d.available = available;
        d.canToggle = canToggle;
        d.on = on;
        d.dimmable = dimmable;
        d.colorTemp = colorTemp;
        d.color = color;
        d.brightness = brightness;
        d.tempK = tempK;
        d.minK = minK;
        d.maxK = maxK;
        d.colorModel = colorModel;
        d.current = current;
        d.target = target;
        d.tMin = tMin;
        d.tMax = tMax;
        d.tStep = tStep;
        d.progress = progress;
        d.remainingMin = remainingMin;
        d.job = job;
        d.detail = detail;
        d.image = image;
        d.alert = alert;
        d.cmds = new HashMap<>(cmds);
        return d;
    }

    private static final java.util.regex.Pattern DEG = java.util.regex.Pattern.compile("(-?\\d+(?:[.,]\\d+)?)\\s*°");
    private static final java.util.regex.Pattern PCT = java.util.regex.Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s*%");

    private static final java.util.regex.Pattern OUTSIDE =
            java.util.regex.Pattern.compile("улиц|снаруж|балкон|двор|outdoor|outside|street|погод");

    /** A sensor outside by its name ("Улица", "Балкон"…): not counted in the temperature at home. */
    boolean outdoor() {
        return OUTSIDE.matcher(name.toLowerCase(new java.util.Locale("ru"))).find();
    }

    /** Temperature a sensor or a thermostat reports, NaN when it reports none. */
    double temperature() {
        if (!Double.isNaN(current)) return current;
        if (kind != SENSOR) return Double.NaN;
        java.util.regex.Matcher m = DEG.matcher(state);
        return m.find() ? Double.parseDouble(m.group(1).replace(',', '.')) : Double.NaN;
    }

    /** Humidity in %, -1 when the sensor has none. */
    int humidity() {
        if (kind != SENSOR || state.contains("батар")) return -1;
        java.util.regex.Matcher m = PCT.matcher(state);
        return m.find() ? (int) Math.round(Double.parseDouble(m.group(1).replace(',', '.'))) : -1;
    }

    /** Whether the tile should look "lit". */
    boolean active() {
        return available && kind != SENSOR && kind != SCENE && kind != CAMERA && on;
    }

    /** A long job is running (print, wash, cleaning): shown in the live strip at the top. */
    boolean busy() {
        return available && on && (kind == PRINTER || kind == APPLIANCE || kind == VACUUM);
    }

    /** Second line of the tile. */
    String status() {
        if (!available) return "Недоступно";
        switch (kind) {
            case SCENE:
                return "Запустить";
            case SENSOR:
                return state.isEmpty() ? "—" : state;
            case CLIMATE: {
                StringBuilder sb = new StringBuilder();
                if (!Double.isNaN(current)) sb.append(deg(current));
                if (!Double.isNaN(target) && (on || !canToggle)) sb.append(sb.length() > 0 ? " → " : "").append(deg(target));
                if (canToggle && !on) sb.append(sb.length() > 0 ? " · " : "").append("выкл");
                return sb.length() == 0 ? (on ? "Вкл" : "Выкл") : sb.toString();
            }
            case LIGHT:
                return on ? brightness >= 0 ? brightness + "%" : "Вкл" : "Выкл";
            case COVER:
                return on ? "Открыто" : "Закрыто";
            case LOCK:
                return on ? "Заперто" : "Открыто";
            case MEDIA:
                return state.isEmpty() ? on ? "Играет" : "Пауза" : state;
            case PRINTER:
            case APPLIANCE: {
                if (progress < 0 || !on) return state.isEmpty() ? "Ожидание" : state;
                String s = progress + "%";
                if (remainingMin >= 0) s += " · " + minutes(remainingMin);
                return "Пауза".equals(state) ? "Пауза · " + s : s;
            }
            case VACUUM:
            case CAMERA:
                return state.isEmpty() ? "—" : state;
            default: {
                String s = on ? "Вкл" : "Выкл";
                return state.isEmpty() ? s : s + " · " + state;
            }
        }
    }

    /** Words for the on / off buttons in the detail sheet. */
    String[] onOffWords() {
        switch (kind) {
            case COVER: return new String[]{"Открыть", "Закрыть"};
            case LOCK: return new String[]{"Запереть", "Отпереть"};
            case MEDIA: return new String[]{"Играть", "Пауза"};
            case VACUUM: return new String[]{"Убрать", "На базу"};
            default: return new String[]{"Включить", "Выключить"};
        }
    }

    /** "1 ч 20 мин", "45 мин". */
    static String minutes(int m) {
        int h = m / 60, mm = m % 60;
        if (h > 0 && mm > 0) return h + " ч " + mm + " мин";
        return h > 0 ? h + " ч" : mm + " мин";
    }

    /** Clock time when a job that has m minutes left ends. */
    static String finishAt(int m) {
        return java.time.LocalTime.now().plusMinutes(m).format(java.time.format.DateTimeFormatter.ofPattern("H:mm"));
    }

    static String deg(double v) {
        return (v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.1f", v)) + "°";
    }

    static String num(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e9) return String.valueOf((long) v);
        return String.format(Locale.ROOT, Math.abs(v) >= 100 ? "%.0f" : "%.1f", v);
    }
}
