package com.deskdash;

import java.util.Locale;

/** One smart home thing shown as a tile: a Home Assistant entity, a Yandex device or a Yandex scenario. */
final class HomeDevice {
    static final int SWITCH = 0, LIGHT = 1, CLIMATE = 2, SENSOR = 3, SCENE = 4, COVER = 5, LOCK = 6, FAN = 7, MEDIA = 8;

    /** "ha:light.kitchen", "ya:<device id>" or "yas:<scenario id>". */
    String id = "";
    /** "ha" or "ya". */
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
        return d;
    }

    /** Whether the tile should look "lit". */
    boolean active() {
        return available && kind != SENSOR && kind != SCENE && on;
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
            default: return new String[]{"Включить", "Выключить"};
        }
    }

    static String deg(double v) {
        return (v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.1f", v)) + "°";
    }

    static String num(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e9) return String.valueOf((long) v);
        return String.format(Locale.ROOT, Math.abs(v) >= 100 ? "%.0f" : "%.1f", v);
    }
}
