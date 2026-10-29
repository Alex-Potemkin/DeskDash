package com.deskdash;

import org.json.JSONObject;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Whole-device tiles for Home Assistant integrations that spread one machine over many entities:
 * 3D printers (Bambu Lab, OctoPrint, Moonraker/Klipper) and washing machines, dryers and dishwashers
 * with a "remaining time" sensor. Ids look like "ha:printer.x1c"; the card's buttons press the
 * integration's own button entities.
 */
final class HaGadgets {
    private HaGadgets() {}

    private static final String[] PRINT_STATUS = {"_print_status", "_current_stage", "_current_state", "_printer_state",
            "_current_print_state"};
    private static final String[] WASHERS = {"washer", "washing", "dryer", "dishwasher", "стирал", "суш", "посуд"};

    static List<HomeDevice> build(Map<String, JSONObject> s, String base) {
        List<HomeDevice> out = new ArrayList<>();
        for (String id : s.keySet()) {
            if (!id.startsWith("sensor.")) continue;
            String p = null;
            if (id.endsWith("_print_progress")) p = cut(id, "_print_progress");
            else if (id.endsWith("_job_percentage")) p = cut(id, "_job_percentage");
            else if (id.endsWith("_progress") && (s.containsKey(id.replace("_progress", "_print_eta"))
                    || s.containsKey(id.replace("_progress", "_current_print_state")))) p = cut(id, "_progress");
            if (p != null) out.add(printer(s, p, id, base));
        }
        for (String id : s.keySet()) {
            if (!id.startsWith("sensor.") || !(id.endsWith("_remaining_time") || id.endsWith("_time_remaining"))) continue;
            String p = id.endsWith("_remaining_time") ? cut(id, "_remaining_time") : cut(id, "_time_remaining");
            if (s.containsKey("sensor." + p + "_print_progress")) continue; // a Bambu printer
            String name = name(s.get(id)).toLowerCase(Locale.ROOT) + " " + p;
            boolean washer = false;
            for (String w : WASHERS) washer |= name.contains(w);
            if (washer) out.add(appliance(s, p, id));
        }
        return out;
    }

    private static HomeDevice printer(Map<String, JSONObject> s, String p, String progressId, String base) {
        HomeDevice d = new HomeDevice();
        d.id = "ha:printer." + p;
        d.source = "ha";
        d.nativeId = p;
        d.kind = HomeDevice.PRINTER;
        d.name = name(s.get(progressId)).replaceAll("(?i)\\s*(print progress|прогресс печати|job percentage|progress)$", "");
        if (d.name.isEmpty()) d.name = "3D-принтер";
        JSONObject prog = s.get(progressId);
        d.available = !"unavailable".equals(prog.optString("state"));
        String st = "";
        for (String k : PRINT_STATUS) {
            JSONObject o = s.get("sensor." + p + k);
            if (o != null) {
                st = o.optString("state").toLowerCase(Locale.ROOT);
                break;
            }
        }
        boolean printing = st.contains("print") || st.contains("running") || st.equals("prepare") || st.contains("pause")
                || st.contains("печат");
        boolean paused = st.contains("pause");
        d.on = printing;
        d.state = st.contains("fail") || st.contains("error") ? "Ошибка" : paused ? "Пауза" : printing ? "Печать"
                : st.contains("finish") || st.contains("complete") ? "Готово" : st.isEmpty() ? "" : "Ожидание";
        d.alert = "Ошибка".equals(d.state);
        if (printing) d.progress = (int) Math.round(num(prog, 0));
        d.remainingMin = minutes(s.get("sensor." + p + "_remaining_time"));
        if (d.remainingMin < 0) d.remainingMin = untilMin(s.get("sensor." + p + "_estimated_finish_time"));
        if (d.remainingMin < 0) d.remainingMin = untilMin(s.get("sensor." + p + "_print_eta"));
        for (String k : new String[]{"_task_name", "_gcode_filename", "_current_file", "_filename", "_print_name"}) {
            JSONObject o = s.get("sensor." + p + k);
            if (o != null && !bad(o.optString("state"))) {
                d.job = o.optString("state").replaceAll("(?i)(\\.gcode)?\\.3mf$|\\.gcode$", "");
                break;
            }
        }
        StringBuilder det = new StringBuilder();
        String noz = temp(s, p, "_nozzle_temperature", "_nozzle_target_temperature");
        if (noz.isEmpty()) noz = temp(s, p, "_actual_tool0_temp", "_target_tool0_temp");
        if (noz.isEmpty()) noz = temp(s, p, "_extruder_temperature", "_extruder_target");
        String bed = temp(s, p, "_bed_temperature", "_target_bed_temperature");
        if (bed.isEmpty()) bed = temp(s, p, "_actual_bed_temp", "_target_bed_temp");
        if (bed.isEmpty()) bed = temp(s, p, "_bed_temperature", "_bed_target");
        if (!noz.isEmpty()) det.append("Сопло ").append(noz);
        if (!bed.isEmpty()) det.append(det.length() > 0 ? "   " : "").append("Стол ").append(bed);
        JSONObject layer = s.get("sensor." + p + "_current_layer"), total = s.get("sensor." + p + "_total_layer_count");
        if (layer != null && total != null && num(total, 0) > 0) {
            det.append(det.length() > 0 ? "\n" : "").append("Слой ").append((int) num(layer, 0)).append(" из ").append((int) num(total, 0));
        }
        d.detail = det.toString();
        button(s, d, "pause", "button." + p + "_pause_printing", "button." + p + "_pause_job", "button." + p + "_pause_print");
        button(s, d, "resume", "button." + p + "_resume_printing", "button." + p + "_resume_job", "button." + p + "_resume_print");
        button(s, d, "stop", "button." + p + "_stop_printing", "button." + p + "_stop_job", "button." + p + "_cancel_print");
        button(s, d, "light", "light." + p + "_chamber_light");
        for (String k : new String[]{"camera." + p + "_camera", "camera." + p, "image." + p + "_camera", "image." + p + "_cover_image"}) {
            JSONObject o = s.get(k);
            String pic = o == null ? "" : attrs(o).optString("entity_picture", "");
            if (!pic.isEmpty()) {
                d.image = pic.startsWith("http") ? pic : base + pic;
                break;
            }
        }
        return d;
    }

    private static HomeDevice appliance(Map<String, JSONObject> s, String p, String remainingId) {
        HomeDevice d = new HomeDevice();
        d.id = "ha:appliance." + p;
        d.source = "ha";
        d.nativeId = p;
        d.kind = HomeDevice.APPLIANCE;
        d.name = name(s.get(remainingId)).replaceAll("(?i)\\s*(remaining time|time remaining|оставшееся время|осталось)$", "");
        if (d.name.isEmpty()) d.name = "Техника";
        d.remainingMin = minutes(s.get(remainingId));
        if (d.remainingMin < 0) d.remainingMin = untilMin(s.get(remainingId));
        String st = "";
        for (String k : new String[]{"_machine_state", "_job_state", "_operation_state", "_run_state", "_status", "_state"}) {
            JSONObject o = s.get("sensor." + p + k);
            if (o != null) {
                st = o.optString("state");
                break;
            }
        }
        String low = st.toLowerCase(Locale.ROOT);
        d.on = d.remainingMin > 0 && !low.contains("stop") && !low.contains("finish") && !low.equals("off") && !low.contains("end");
        d.state = d.on ? (low.contains("pause") ? "Пауза" : "Работает") : low.contains("finish") || low.contains("end") ? "Готово"
                : bad(st) ? "Выкл" : st;
        JSONObject prog = s.get("sensor." + p + "_progress");
        if (d.on) d.progress = prog != null ? (int) Math.round(num(prog, 0)) : 0;
        return d;
    }

    // ---------------------------------------------------------------- helpers

    private static String cut(String id, String suffix) {
        return id.substring("sensor.".length(), id.length() - suffix.length());
    }

    private static JSONObject attrs(JSONObject o) {
        JSONObject a = o.optJSONObject("attributes");
        return a == null ? new JSONObject() : a;
    }

    private static String name(JSONObject o) {
        return o == null ? "" : attrs(o).optString("friendly_name", "").trim();
    }

    private static boolean bad(String st) {
        return st.isEmpty() || "unknown".equals(st) || "unavailable".equals(st) || "none".equalsIgnoreCase(st);
    }

    private static double num(JSONObject o, double def) {
        if (o == null) return def;
        try {
            return Double.parseDouble(o.optString("state"));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** A duration sensor in minutes; HA may show it in hours or seconds. */
    private static int minutes(JSONObject o) {
        if (o == null) return -1;
        double v = num(o, Double.NaN);
        if (Double.isNaN(v)) return -1;
        String u = attrs(o).optString("unit_of_measurement", "min");
        if ("h".equals(u)) v *= 60;
        else if ("s".equals(u)) v /= 60;
        else if ("d".equals(u)) v *= 1440;
        return (int) Math.round(Math.max(0, v));
    }

    /** A timestamp sensor (finish time): minutes from now. */
    private static int untilMin(JSONObject o) {
        if (o == null || bad(o.optString("state"))) return -1;
        try {
            long t = OffsetDateTime.parse(o.optString("state")).toInstant().toEpochMilli();
            return (int) Math.max(0, (t - System.currentTimeMillis()) / 60_000L);
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private static String temp(Map<String, JSONObject> s, String p, String cur, String target) {
        JSONObject c = s.get("sensor." + p + cur);
        if (c == null || bad(c.optString("state"))) return "";
        String out = HomeDevice.deg(Math.round(num(c, 0)));
        JSONObject t = s.get("sensor." + p + target);
        if (t != null && num(t, 0) > 0) out += " / " + HomeDevice.deg(Math.round(num(t, 0)));
        return out;
    }

    private static void button(Map<String, JSONObject> s, HomeDevice d, String action, String... ids) {
        for (String id : ids) {
            JSONObject o = s.get(id);
            if (o != null && !"unavailable".equals(o.optString("state"))) {
                d.cmds.put(action, id);
                return;
            }
        }
    }
}
