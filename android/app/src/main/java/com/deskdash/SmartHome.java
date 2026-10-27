package com.deskdash;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * State of Home Assistant + Yandex devices for the dashboard, or of everything the DeskDash Hub offers
 * when one is set (then the hub talks to both services and the dashboard only to the hub). poll() and the actions run on a
 * background thread; local() runs on the UI thread so a tile reacts before the server answers.
 */
final class SmartHome {
    interface Change {
        void apply(HomeDevice d);
    }

    private static final long YANDEX_EVERY_MS = 15_000, HOLD_MS = 4000;

    private final Prefs prefs;
    private final Object lock = new Object();
    private volatile Map<String, HomeDevice> devices = Collections.emptyMap();
    private final Map<String, Long> holds = new HashMap<>();
    private List<HomeDevice> yaCache = new ArrayList<>();
    private long yaAt;
    private volatile String haError, yaError, hubError;

    SmartHome(Prefs p) {
        prefs = p;
    }

    static boolean configured(Prefs p) {
        return HubClient.configured(p) || HomeAssistant.configured(p) || YandexHome.configured(p);
    }

    Map<String, HomeDevice> devices() {
        return devices;
    }

    /** Connection problems, or null when everything answered. */
    String error() {
        if (HubClient.configured(prefs)) return hubError == null ? null : "Хаб: " + hubError;
        String h = haError, y = yaError;
        if (h == null) return y == null ? null : "Яндекс: " + y;
        return "Home Assistant: " + h + (y == null ? "" : "\nЯндекс: " + y);
    }

    /** Forget everything (after a profile switch). */
    void clear() {
        synchronized (lock) {
            devices = Collections.emptyMap();
            holds.clear();
            yaCache = new ArrayList<>();
            yaAt = 0;
            haError = null;
            yaError = null;
            hubError = null;
        }
    }

    /** Fetches fresh state. Yandex is asked at most every 15 s unless forced. */
    void poll(boolean forceYandex) {
        if (HubClient.configured(prefs)) {
            pollHub();
            return;
        }
        List<HomeDevice> ha = null, ya = null;
        if (HomeAssistant.configured(prefs)) {
            try {
                ha = new HomeAssistant(prefs).states();
                haError = null;
            } catch (Exception e) {
                haError = msg(e);
            }
        } else {
            haError = null;
        }
        long now = System.currentTimeMillis();
        boolean yaOn = YandexHome.configured(prefs);
        if (yaOn && (forceYandex || now - yaAt > YANDEX_EVERY_MS)) {
            try {
                ya = new YandexHome(prefs).devices();
                yaError = null;
            } catch (Exception e) {
                yaError = msg(e);
            }
            yaAt = now;
        } else if (!yaOn) {
            yaError = null;
        }
        synchronized (lock) {
            Map<String, HomeDevice> old = devices, next = new LinkedHashMap<>();
            if (ha != null) {
                for (HomeDevice d : ha) next.put(d.id, d);
            } else {
                for (HomeDevice d : old.values()) if ("ha".equals(d.source) && HomeAssistant.configured(prefs)) next.put(d.id, d);
            }
            if (ya != null) yaCache = ya;
            if (yaOn) for (HomeDevice d : yaCache) next.put(d.id, d);
            now = System.currentTimeMillis();
            for (Map.Entry<String, Long> h : new ArrayList<>(holds.entrySet())) {
                if (now < h.getValue() && old.containsKey(h.getKey())) next.put(h.getKey(), old.get(h.getKey()));
                else holds.remove(h.getKey());
            }
            devices = next;
        }
    }

    private void pollHub() {
        List<HomeDevice> l;
        try {
            l = new HubClient(prefs).devices();
            hubError = null;
        } catch (Exception e) {
            hubError = msg(e);
            return;
        }
        synchronized (lock) {
            Map<String, HomeDevice> old = devices, next = new LinkedHashMap<>();
            for (HomeDevice d : l) next.put(d.id, d);
            long now = System.currentTimeMillis();
            for (Map.Entry<String, Long> h : new ArrayList<>(holds.entrySet())) {
                if (now < h.getValue() && old.containsKey(h.getKey()) && next.containsKey(h.getKey())) next.put(h.getKey(), old.get(h.getKey()));
                else holds.remove(h.getKey());
            }
            devices = next;
        }
    }

    /** Changes a device locally right away and keeps that state for a few seconds against stale polls. */
    void local(String id, Change change) {
        synchronized (lock) {
            HomeDevice d = devices.get(id);
            if (d == null) return;
            Map<String, HomeDevice> next = new LinkedHashMap<>(devices);
            HomeDevice c = d.copy();
            change.apply(c);
            next.put(id, c);
            holds.put(id, System.currentTimeMillis() + HOLD_MS);
            devices = next;
        }
    }

    /** Drops the local override so the next poll shows the real state (after a failed action). */
    void release(String id) {
        synchronized (lock) {
            holds.remove(id);
        }
        if (id.startsWith("ya")) yaAt = 0;
    }

    // ---------------------------------------------------------------- actions (background thread)

    /** Each returns null on success or an error message. */
    String setOn(HomeDevice d, boolean on) {
        if (HubClient.configured(prefs)) return run(d, () -> new HubClient(prefs).act(d, on ? "on" : "off", null));
        return run(d, () -> {
            if (ha(d)) new HomeAssistant(prefs).setOn(d, on);
            else new YandexHome(prefs).setOn(d, on);
        });
    }

    String runScene(HomeDevice d) {
        if (HubClient.configured(prefs)) return run(d, () -> new HubClient(prefs).act(d, "run", null));
        return run(d, () -> {
            if (ha(d)) new HomeAssistant(prefs).run(d);
            else new YandexHome(prefs).run(d);
        });
    }

    String brightness(HomeDevice d, int pct) {
        if (HubClient.configured(prefs)) return run(d, () -> new HubClient(prefs).act(d, "brightness", pct));
        return run(d, () -> {
            if (ha(d)) new HomeAssistant(prefs).brightness(d, pct);
            else new YandexHome(prefs).brightness(d, pct);
        });
    }

    String colorTemp(HomeDevice d, int k) {
        if (HubClient.configured(prefs)) return run(d, () -> new HubClient(prefs).act(d, "color_temp", k));
        return run(d, () -> {
            if (ha(d)) new HomeAssistant(prefs).colorTemp(d, k);
            else new YandexHome(prefs).colorTemp(d, k);
        });
    }

    String color(HomeDevice d, int rgb) {
        if (HubClient.configured(prefs)) return run(d, () -> new HubClient(prefs).act(d, "color", rgb & 0xFFFFFF));
        return run(d, () -> {
            if (ha(d)) new HomeAssistant(prefs).color(d, rgb);
            else new YandexHome(prefs).color(d, rgb);
        });
    }

    String temperature(HomeDevice d, double t) {
        if (HubClient.configured(prefs)) return run(d, () -> new HubClient(prefs).act(d, "temperature", t));
        return run(d, () -> {
            if (ha(d)) new HomeAssistant(prefs).temperature(d, t);
            else new YandexHome(prefs).temperature(d, t);
        });
    }

    private interface Call {
        void run() throws Exception;
    }

    private String run(HomeDevice d, Call c) {
        try {
            c.run();
            if (!ha(d)) yaAt = 0; // re-read Yandex on the next poll
            return null;
        } catch (Exception e) {
            release(d.id);
            return msg(e);
        }
    }

    private static boolean ha(HomeDevice d) {
        return "ha".equals(d.source);
    }

    // ---------------------------------------------------------------- full list for the desk editor

    /** Everything both services offer, grouped by room; problems go into errors. Blocking. */
    static List<HomeDevice> listAll(Prefs p, StringBuilder errors) {
        List<HomeDevice> out = new ArrayList<>();
        if (HubClient.configured(p)) {
            try {
                List<HomeDevice> l = new HubClient(p).devices();
                sort(l);
                out.addAll(l);
            } catch (Exception e) {
                errors.append("Хаб: ").append(msg(e)).append('\n');
            }
            return out;
        }
        if (HomeAssistant.configured(p)) {
            try {
                HomeAssistant h = new HomeAssistant(p);
                List<HomeDevice> l = h.states();
                Map<String, String> areas = h.areas();
                for (HomeDevice d : l) {
                    String a = areas.get(d.nativeId);
                    if (a != null) d.room = a;
                }
                sort(l);
                out.addAll(l);
            } catch (Exception e) {
                errors.append("Home Assistant: ").append(msg(e)).append('\n');
            }
        }
        if (YandexHome.configured(p)) {
            try {
                List<HomeDevice> l = new YandexHome(p).devices();
                sort(l);
                out.addAll(l);
            } catch (Exception e) {
                errors.append("Яндекс: ").append(msg(e)).append('\n');
            }
        }
        return out;
    }

    /** Rooms first (alphabetically), things without a room after them, scenes and sensors last. */
    private static void sort(List<HomeDevice> l) {
        Collections.sort(l, (a, b) -> {
            int ra = rank(a), rb = rank(b);
            if (ra != rb) return ra - rb;
            if (a.room.isEmpty() != b.room.isEmpty()) return a.room.isEmpty() ? 1 : -1;
            int c = a.room.compareToIgnoreCase(b.room);
            return c != 0 ? c : a.name.compareToIgnoreCase(b.name);
        });
    }

    private static int rank(HomeDevice d) {
        return d.kind == HomeDevice.SCENE ? 2 : d.kind == HomeDevice.SENSOR ? 1 : 0;
    }

    static String msg(Exception e) {
        if (e instanceof java.net.UnknownHostException) return "адрес не найден";
        if (e instanceof java.net.SocketTimeoutException) return "не отвечает";
        if (e instanceof java.net.ConnectException) return "нет связи";
        String m = e.getMessage();
        return m == null || m.isEmpty() ? e.getClass().getSimpleName() : m;
    }
}
