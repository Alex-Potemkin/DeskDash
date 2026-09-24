package com.deskdash;

import android.Manifest;
import android.content.ContentUris;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract.Instances;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Loads events from the phone's calendar provider and/or Google's secret ICS links. */
public final class CalendarRepo {
    private static final long DAY_MS = 86_400_000L;
    private static final long ICS_TTL_MS = 10 * 60_000L;

    private CalendarRepo() {}

    public static boolean hasPermission(Context c) {
        return c.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED;
    }

    public static List<CalEvent> load(Context c, Prefs p, LocalDate day) {
        return load(c, p, day, day.plusDays(1));
    }

    /** Events overlapping [from, toExclusive), all-day first, then by start time. */
    public static List<CalEvent> load(Context c, Prefs p, LocalDate from, LocalDate toExclusive) {
        List<CalEvent> out = new ArrayList<>();
        String src = p.calSource();
        if (!"ics".equals(src)) out.addAll(system(c, from, toExclusive));
        if (!"system".equals(src)) out.addAll(ics(c, p.icsUrls(), from, toExclusive));
        Collections.sort(out, (a, b) -> a.allDay != b.allDay ? (a.allDay ? -1 : 1) : Long.compare(a.start, b.start));
        return out;
    }

    /** Events of one day picked out of a longer list. */
    public static List<CalEvent> forDay(List<CalEvent> all, LocalDate day) {
        ZoneId z = ZoneId.systemDefault();
        long ws = day.atStartOfDay(z).toInstant().toEpochMilli();
        long we = day.plusDays(1).atStartOfDay(z).toInstant().toEpochMilli();
        List<CalEvent> out = new ArrayList<>();
        for (CalEvent e : all) {
            boolean hit = e.end > e.start ? e.start < we && e.end > ws : e.start >= ws && e.start < we;
            if (hit) out.add(e);
        }
        return out;
    }

    private static List<CalEvent> system(Context c, LocalDate from, LocalDate to) {
        List<CalEvent> out = new ArrayList<>();
        if (!hasPermission(c)) return out;
        ZoneId z = ZoneId.systemDefault();
        long ws = from.atStartOfDay(z).toInstant().toEpochMilli();
        long we = to.atStartOfDay(z).toInstant().toEpochMilli();
        Uri.Builder b = Instances.CONTENT_URI.buildUpon();
        ContentUris.appendId(b, ws - DAY_MS);
        ContentUris.appendId(b, we + DAY_MS);
        String[] proj = {Instances.TITLE, Instances.BEGIN, Instances.END, Instances.ALL_DAY,
                Instances.DISPLAY_COLOR, Instances.EVENT_LOCATION, Instances.CALENDAR_DISPLAY_NAME};
        try (Cursor cur = c.getContentResolver().query(b.build(), proj, Instances.VISIBLE + "=1", null, Instances.BEGIN + " ASC")) {
            if (cur == null) return out;
            while (cur.moveToNext()) {
                boolean allDay = cur.getInt(3) != 0;
                long s = cur.getLong(1), e = cur.getLong(2);
                if (allDay) {
                    // all-day instances are stored as UTC midnights
                    LocalDate sd = Instant.ofEpochMilli(s).atZone(ZoneOffset.UTC).toLocalDate();
                    LocalDate ed = Instant.ofEpochMilli(e).atZone(ZoneOffset.UTC).toLocalDate();
                    if (!ed.isAfter(sd)) ed = sd.plusDays(1);
                    if (!sd.isBefore(to) || !ed.isAfter(from)) continue;
                    s = sd.atStartOfDay(z).toInstant().toEpochMilli();
                    e = ed.atStartOfDay(z).toInstant().toEpochMilli();
                } else {
                    boolean hit = e > s ? s < we && e > ws : s >= ws && s < we;
                    if (!hit) continue;
                }
                CalEvent ev = new CalEvent(cur.getString(0), s, e, allDay);
                ev.color = cur.getInt(4);
                ev.location = nz(cur.getString(5));
                ev.calendar = nz(cur.getString(6));
                out.add(ev);
            }
        } catch (RuntimeException ignored) {
            // provider missing or permission revoked mid-query
        }
        return out;
    }

    private static List<CalEvent> ics(Context c, String urls, LocalDate from, LocalDate to) {
        List<CalEvent> out = new ArrayList<>();
        for (String u : urls.split("\\s+")) {
            u = u.trim();
            if (u.isEmpty()) continue;
            if (u.startsWith("webcal://")) u = "https://" + u.substring("webcal://".length());
            String text = fetchCached(c, u);
            if (text != null) out.addAll(IcsParser.events(text, from, to, ZoneId.systemDefault()));
        }
        return out;
    }

    private static String fetchCached(Context c, String url) {
        File f = new File(c.getCacheDir(), "ics_" + Integer.toHexString(url.hashCode()) + ".ics");
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < ICS_TTL_MS) return read(f);
        try {
            String t = Net.request("GET", url, null, 15000);
            if (t.contains("BEGIN:VCALENDAR")) {
                Files.write(f.toPath(), t.getBytes(StandardCharsets.UTF_8));
                return t;
            }
        } catch (IOException | RuntimeException ignored) {
            // offline: fall back to the cached copy
        }
        return f.exists() ? read(f) : null;
    }

    private static String read(File f) {
        try {
            return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
