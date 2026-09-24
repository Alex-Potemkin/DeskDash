package com.deskdash;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Minimal iCalendar reader for Google's "secret iCal address": VEVENTs with
 * RRULE / EXDATE / RECURRENCE-ID, expanded for a single day. Pure Java so it can be tested off-device.
 */
public final class IcsParser {
    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");
    private static final DateTimeFormatter D = DateTimeFormatter.BASIC_ISO_DATE;
    private static final int MAX_PERIODS = 4000;
    private static final long DAY_MS = 86_400_000L;

    private IcsParser() {}

    private static final class Raw {
        String uid = "", summary = "", location = "", rrule, duration;
        boolean allDay, cancelled;
        ZonedDateTime start, end;
        final Set<Long> exdates = new HashSet<>();
        Long recurrenceId;
    }

    public static List<CalEvent> eventsForDay(String ics, LocalDate day, ZoneId local) {
        return events(ics, day, day.plusDays(1), local);
    }

    /** All occurrences overlapping [from, toExclusive). */
    public static List<CalEvent> events(String ics, LocalDate from, LocalDate toExclusive, ZoneId local) {
        List<Raw> raws = new ArrayList<>();
        String calName = "";
        Raw cur = null;
        int depth = 0; // nested components inside a VEVENT (VALARM)
        for (String line : unfold(ics)) {
            int colon = findColon(line);
            if (colon < 0) continue;
            String[] parts = line.substring(0, colon).split(";");
            String value = line.substring(colon + 1);
            String name = parts[0].trim().toUpperCase(Locale.ROOT);
            Map<String, String> params = new HashMap<>();
            for (int i = 1; i < parts.length; i++) {
                int eq = parts[i].indexOf('=');
                if (eq > 0) params.put(parts[i].substring(0, eq).toUpperCase(Locale.ROOT), unquote(parts[i].substring(eq + 1)));
            }

            if (name.equals("BEGIN")) {
                if (cur != null) depth++;
                else if (value.trim().equalsIgnoreCase("VEVENT")) cur = new Raw();
                continue;
            }
            if (name.equals("END")) {
                if (cur != null) {
                    if (depth > 0) depth--;
                    else {
                        if (cur.start != null) raws.add(cur);
                        cur = null;
                    }
                }
                continue;
            }
            if (cur == null) {
                if (name.equals("X-WR-CALNAME")) calName = unescape(value);
                continue;
            }
            if (depth > 0) continue;
            try {
                switch (name) {
                    case "UID": cur.uid = value.trim(); break;
                    case "SUMMARY": cur.summary = unescape(value); break;
                    case "LOCATION": cur.location = unescape(value); break;
                    case "STATUS": cur.cancelled = value.trim().equalsIgnoreCase("CANCELLED"); break;
                    case "DTSTART":
                        cur.allDay = isDate(value, params);
                        cur.start = parseDt(value, params, local);
                        break;
                    case "DTEND": cur.end = parseDt(value, params, local); break;
                    case "DURATION": cur.duration = value.trim(); break;
                    case "RRULE": cur.rrule = value.trim(); break;
                    case "EXDATE":
                        for (String v : value.split(",")) {
                            if (!v.trim().isEmpty()) cur.exdates.add(millis(parseDt(v, params, local)));
                        }
                        break;
                    case "RECURRENCE-ID": cur.recurrenceId = millis(parseDt(value, params, local)); break;
                    default: break;
                }
            } catch (RuntimeException ignored) {
                // skip a malformed property, keep the rest of the event
            }
        }

        Map<String, Set<Long>> overridden = new HashMap<>();
        for (Raw r : raws) {
            if (r.recurrenceId != null) overridden.computeIfAbsent(r.uid, k -> new HashSet<>()).add(r.recurrenceId);
        }

        long ws = millis(from.atStartOfDay(local));
        long we = millis(toExclusive.atStartOfDay(local));
        List<CalEvent> out = new ArrayList<>();
        for (Raw r : raws) {
            if (r.cancelled) continue;
            long dur = durationMs(r);
            if (r.rrule == null || r.recurrenceId != null) {
                long s = millis(r.start);
                if (overlaps(s, dur, ws, we)) out.add(make(r, s, dur, calName));
            } else {
                try {
                    expand(r, dur, ws, we, overridden.getOrDefault(r.uid, Collections.emptySet()), out, calName);
                } catch (RuntimeException ignored) {
                    // unsupported rule
                }
            }
        }
        return out;
    }

    private static void expand(Raw r, long dur, long ws, long we, Set<Long> skip, List<CalEvent> out, String cal) {
        Map<String, String> rule = new HashMap<>();
        for (String p : r.rrule.split(";")) {
            int eq = p.indexOf('=');
            if (eq > 0) rule.put(p.substring(0, eq).trim().toUpperCase(Locale.ROOT), p.substring(eq + 1).trim().toUpperCase(Locale.ROOT));
        }
        String freq = rule.getOrDefault("FREQ", "");
        int interval = Math.max(1, intOr(rule.get("INTERVAL"), 1));
        int count = intOr(rule.get("COUNT"), -1);
        ZoneId zone = r.start.getZone();
        long until = Long.MAX_VALUE;
        String u = rule.get("UNTIL");
        if (u != null) {
            until = u.length() == 8
                    ? millis(LocalDate.parse(u, D).plusDays(1).atStartOfDay(zone)) - 1
                    : millis(parseDt(u, Collections.emptyMap(), zone));
        }

        List<int[]> byDay = new ArrayList<>(); // {ordinal, ISO day-of-week}
        String bd = rule.get("BYDAY");
        if (bd != null) {
            for (String d : bd.split(",")) {
                d = d.trim();
                if (d.length() < 2) continue;
                int dow = dow(d.substring(d.length() - 2));
                if (dow == 0) continue;
                String ord = d.substring(0, d.length() - 2).replace("+", "");
                byDay.add(new int[]{ord.isEmpty() ? 0 : Integer.parseInt(ord), dow});
            }
        }
        List<Integer> byMonthDay = ints(rule.get("BYMONTHDAY"));
        List<Integer> byMonth = ints(rule.get("BYMONTH"));

        ZonedDateTime s0 = r.start;
        LocalTime time = s0.toLocalTime();
        LocalDate d0 = s0.toLocalDate();

        long kStart = 0;
        if (count < 0) {
            // Jump close to the window instead of walking from the first occurrence.
            LocalDate ref = Instant.ofEpochMilli(ws - dur - DAY_MS).atZone(zone).toLocalDate();
            long units;
            switch (freq) {
                case "DAILY": units = ChronoUnit.DAYS.between(d0, ref); break;
                case "WEEKLY": units = ChronoUnit.WEEKS.between(d0, ref); break;
                case "MONTHLY": units = ChronoUnit.MONTHS.between(YearMonth.from(d0), YearMonth.from(ref)); break;
                case "YEARLY": units = ref.getYear() - d0.getYear(); break;
                default: return;
            }
            kStart = Math.max(0, units / interval - 1);
        }

        int produced = 0;
        for (long k = kStart; k < kStart + MAX_PERIODS; k++) {
            List<LocalDate> dates = new ArrayList<>();
            switch (freq) {
                case "DAILY": {
                    LocalDate d = d0.plusDays(k * interval);
                    if (byDay.isEmpty() || hasDow(byDay, d)) dates.add(d);
                    break;
                }
                case "WEEKLY": {
                    LocalDate wk = d0.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(k * interval);
                    if (byDay.isEmpty()) dates.add(wk.plusDays(d0.getDayOfWeek().getValue() - 1));
                    else for (int[] b : byDay) dates.add(wk.plusDays(b[1] - 1));
                    break;
                }
                case "MONTHLY":
                    monthDates(YearMonth.from(d0).plusMonths(k * interval), byDay, byMonthDay, d0.getDayOfMonth(), dates);
                    break;
                case "YEARLY": {
                    int year = d0.getYear() + (int) (k * interval);
                    List<Integer> months = byMonth.isEmpty() ? Collections.singletonList(d0.getMonthValue()) : byMonth;
                    for (int m : months) monthDates(YearMonth.of(year, m), byDay, byMonthDay, d0.getDayOfMonth(), dates);
                    break;
                }
                default:
                    return;
            }
            Collections.sort(dates);
            for (LocalDate d : dates) {
                ZonedDateTime occ = ZonedDateTime.of(d, time, zone);
                if (occ.isBefore(s0)) continue;
                long cs = millis(occ);
                if (cs > until) return;
                if (count >= 0 && produced >= count) return;
                produced++;
                if (cs >= we) return;
                if (overlaps(cs, dur, ws, we) && !r.exdates.contains(cs) && !skip.contains(cs)) out.add(make(r, cs, dur, cal));
            }
        }
    }

    private static void monthDates(YearMonth ym, List<int[]> byDay, List<Integer> byMonthDay, int defaultDom, List<LocalDate> out) {
        if (!byMonthDay.isEmpty()) {
            for (int md : byMonthDay) {
                int dom = md > 0 ? md : ym.lengthOfMonth() + md + 1;
                if (dom >= 1 && dom <= ym.lengthOfMonth()) out.add(ym.atDay(dom));
            }
        } else if (!byDay.isEmpty()) {
            for (int[] b : byDay) {
                DayOfWeek w = DayOfWeek.of(b[1]);
                if (b[0] == 0) {
                    for (LocalDate d = ym.atDay(1).with(TemporalAdjusters.nextOrSame(w)); YearMonth.from(d).equals(ym); d = d.plusWeeks(1)) out.add(d);
                } else if (b[0] > 0) {
                    LocalDate d = ym.atDay(1).with(TemporalAdjusters.nextOrSame(w)).plusWeeks(b[0] - 1);
                    if (YearMonth.from(d).equals(ym)) out.add(d);
                } else {
                    LocalDate d = ym.atEndOfMonth().with(TemporalAdjusters.previousOrSame(w)).minusWeeks(-b[0] - 1);
                    if (YearMonth.from(d).equals(ym)) out.add(d);
                }
            }
        } else if (defaultDom <= ym.lengthOfMonth()) {
            out.add(ym.atDay(defaultDom));
        }
    }

    private static CalEvent make(Raw r, long start, long dur, String cal) {
        CalEvent e = new CalEvent(r.summary, start, start + dur, r.allDay);
        e.location = r.location;
        e.calendar = cal;
        return e;
    }

    private static boolean overlaps(long s, long dur, long ws, long we) {
        return dur > 0 ? s < we && s + dur > ws : s >= ws && s < we;
    }

    private static long durationMs(Raw r) {
        if (r.end != null) return Math.max(0, millis(r.end) - millis(r.start));
        if (r.duration != null) {
            String d = r.duration.startsWith("+") ? r.duration.substring(1) : r.duration;
            if (d.endsWith("W")) return Long.parseLong(d.replaceAll("[^0-9]", "")) * 7 * DAY_MS;
            try {
                return Math.abs(Duration.parse(d).toMillis());
            } catch (RuntimeException ignored) {
                // fall through
            }
        }
        return r.allDay ? DAY_MS : 0;
    }

    static ZonedDateTime parseDt(String v, Map<String, String> params, ZoneId fallback) {
        v = v.trim();
        if (v.length() == 8) return LocalDate.parse(v, D).atStartOfDay(fallback);
        boolean utc = v.endsWith("Z");
        LocalDateTime ldt = LocalDateTime.parse(utc ? v.substring(0, v.length() - 1) : v, DT);
        if (utc) return ldt.atZone(ZoneOffset.UTC);
        String tz = params.get("TZID");
        return ldt.atZone(tz != null ? zone(tz, fallback) : fallback);
    }

    private static ZoneId zone(String tz, ZoneId fallback) {
        try {
            return ZoneId.of(tz.trim());
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static boolean isDate(String value, Map<String, String> params) {
        return "DATE".equalsIgnoreCase(params.get("VALUE")) || value.trim().length() == 8;
    }

    private static long millis(ZonedDateTime z) {
        return z.toInstant().toEpochMilli();
    }

    private static boolean hasDow(List<int[]> byDay, LocalDate d) {
        for (int[] b : byDay) if (b[1] == d.getDayOfWeek().getValue()) return true;
        return false;
    }

    private static int dow(String s) {
        switch (s) {
            case "MO": return 1;
            case "TU": return 2;
            case "WE": return 3;
            case "TH": return 4;
            case "FR": return 5;
            case "SA": return 6;
            case "SU": return 7;
            default: return 0;
        }
    }

    private static int intOr(String s, int def) {
        try {
            return s == null ? def : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static List<Integer> ints(String s) {
        List<Integer> out = new ArrayList<>();
        if (s == null) return out;
        for (String p : s.split(",")) {
            try {
                out.add(Integer.parseInt(p.trim()));
            } catch (NumberFormatException ignored) {
                // skip
            }
        }
        return out;
    }

    static List<String> unfold(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = null;
        for (String raw : text.split("\r?\n")) {
            if (!raw.isEmpty() && (raw.charAt(0) == ' ' || raw.charAt(0) == '\t')) {
                if (cur != null) cur.append(raw, 1, raw.length());
            } else {
                if (cur != null) out.add(cur.toString());
                cur = new StringBuilder(raw);
            }
        }
        if (cur != null) out.add(cur.toString());
        return out;
    }

    private static int findColon(String line) {
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') quoted = !quoted;
            else if (c == ':' && !quoted) return i;
        }
        return -1;
    }

    private static String unquote(String s) {
        s = s.trim();
        return s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"") ? s.substring(1, s.length() - 1) : s;
    }

    private static String unescape(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                b.append(n == 'n' || n == 'N' ? ' ' : n);
            } else {
                b.append(c);
            }
        }
        return b.toString().trim();
    }
}
