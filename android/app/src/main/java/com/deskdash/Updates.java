package com.deskdash;

import android.content.Context;
import android.content.pm.PackageInfo;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * New versions from the project's GitHub releases: at most once a day the dashboard asks for the latest
 * release and, when it is newer than this app, offers its APK in the live strip and in the settings.
 */
final class Updates {
    static final String REPO = "Alex-Potemkin/DeskDash";
    private static final long DAY = 24 * 3_600_000L;

    static final class Release {
        String version = "", apk = "", page = "";
    }

    private Updates() {}

    static String current(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return pi.versionName == null ? "" : pi.versionName;
        } catch (Exception e) {
            return "";
        }
    }

    /** The newer release, or null (also when it was checked less than a day ago, unless forced). Background thread. */
    static Release check(Context c, boolean force) throws Exception {
        android.content.SharedPreferences sp = c.getSharedPreferences("deskdash_profiles", Context.MODE_PRIVATE);
        long at = sp.getLong("update_checked", 0);
        if (!force && System.currentTimeMillis() - at < DAY) return null;
        sp.edit().putLong("update_checked", System.currentTimeMillis()).apply();
        JSONObject o = new JSONObject(Net.http("GET", "https://api.github.com/repos/" + REPO + "/releases/latest",
                null, null, null, 8000));
        Release r = new Release();
        r.version = o.optString("tag_name").replaceFirst("^[vV]", "");
        r.page = o.optString("html_url");
        JSONArray assets = o.optJSONArray("assets");
        for (int i = 0; assets != null && i < assets.length(); i++) {
            JSONObject a = assets.getJSONObject(i);
            if (a.optString("name").endsWith(".apk")) r.apk = a.optString("browser_download_url");
        }
        if (r.apk.isEmpty()) r.apk = r.page;
        return newer(r.version, current(c)) ? r : null;
    }

    /** "1.10" > "1.9": compares the numbers part by part. */
    static boolean newer(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? num(x[i]) : 0, q = i < y.length ? num(y[i]) : 0;
            if (p != q) return p > q;
        }
        return false;
    }

    private static int num(String s) {
        try {
            return Integer.parseInt(s.replaceAll("\\D.*", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
