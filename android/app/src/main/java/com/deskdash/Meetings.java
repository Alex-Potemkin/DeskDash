package com.deskdash;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Calls from the calendar: an event is a call when its link, place or description has a meeting link
 * of a known service. Invitations from every service land in the calendar, so this is how Zoom, Meet,
 * Teams, Telemost and the rest all end up in one list without signing in to each of them.
 * Ids and colours match pc-agent/calls.py, which reports calls running on the PC.
 */
final class Meetings {
    static final class Platform {
        final String id, name;
        final int color;
        final Pattern link;

        Platform(String id, String name, int color, String link) {
            this.id = id;
            this.name = name;
            this.color = color;
            this.link = link == null ? null : Pattern.compile(link, Pattern.CASE_INSENSITIVE);
        }

        /** Two letters for the round badge. */
        String badge() {
            switch (id) {
                case "meet": return "M";
                case "teams": return "T";
                case "zoom": return "Z";
                case "telemost": return "Т";
                case "vk": return "VK";
                case "web": return "•";
                default: return name.substring(0, 1);
            }
        }
    }

    private static final String TAIL = "[^\\s\"'<>()\\]]+";

    static final Platform[] ALL = {
            new Platform("zoom", "Zoom", 0xFF2D8CFF, "(https?://[\\w.-]*zoom\\.(?:us|com)/(?:j|my|w|s)/" + TAIL + "|zoommtg://" + TAIL + ")"),
            new Platform("meet", "Google Meet", 0xFF00897B, "https?://meet\\.google\\.com/[a-z]{3,4}-[a-z]{4}-[a-z]{3}" + "(?:\\?" + TAIL + ")?"),
            new Platform("teams", "Teams", 0xFF6264A7, "https?://teams\\.(?:microsoft|live)\\.com/(?:l/meetup-join|meet|l/meet)/" + TAIL),
            new Platform("telemost", "Телемост", 0xFFFC3F1D, "https?://telemost(?:\\.360)?\\.yandex\\.(?:ru|com)/j/" + TAIL),
            new Platform("jitsi", "Jitsi", 0xFF1D76BA, "https?://meet\\.jit\\.si/" + TAIL),
            new Platform("vk", "VK Звонки", 0xFF0077FF, "https?://(?:vk\\.com/call/join|calls\\.vk\\.com)/" + TAIL),
            new Platform("sber", "SberJazz", 0xFF21A038, "https?://(?:jazz\\.sber\\.ru|salutejazz\\.ru)/" + TAIL),
            new Platform("ktalk", "Контур.Толк", 0xFF1F87EF, "https?://[\\w-]+\\.ktalk\\.ru/" + TAIL),
            new Platform("webex", "Webex", 0xFF00BCEB, "https?://[\\w.-]*webex\\.com/(?:meet|join|[\\w.-]+/j\\.php)" + TAIL),
            new Platform("skype", "Skype", 0xFF00AFF0, "https?://join\\.skype\\.com/" + TAIL),
            new Platform("discord", "Discord", 0xFF5865F2, "https?://discord\\.(?:gg|com/invite|com/channels)/" + TAIL),
            new Platform("slack", "Slack", 0xFF611F69, "https?://app\\.slack\\.com/huddle/" + TAIL),
            new Platform("telegram", "Telegram", 0xFF229ED9, "https?://t\\.me/[\\w+]+\\?(?:videochat|voicechat|livestream)" + TAIL.replace("+", "*")),
            new Platform("whereby", "Whereby", 0xFF6D4AFF, "https?://whereby\\.com/" + TAIL),
            new Platform("whatsapp", "WhatsApp", 0xFF25D366, "https?://call\\.whatsapp\\.com/" + TAIL),
    };
    static final Platform WEB = new Platform("web", "Звонок в браузере", 0xFF8A8A8A, null);

    /** A calendar event with its meeting link. */
    static final class Meeting {
        final CalEvent event;
        final Platform platform;
        final String url;

        Meeting(CalEvent e, Platform p, String url) {
            event = e;
            platform = p;
            this.url = url;
        }

        boolean now(long t) {
            return event.start <= t && t < event.end;
        }
    }

    private Meetings() {}

    static Platform byId(String id) {
        for (Platform p : ALL) if (p.id.equals(id)) return p;
        return WEB;
    }

    /** The meeting link of an event, or null. */
    static Meeting of(CalEvent e) {
        String text = e.url + "\n" + e.location + "\n" + e.description.replaceAll("&amp;", "&");
        Platform best = null;
        String url = null;
        int at = Integer.MAX_VALUE;
        for (Platform p : ALL) {
            Matcher m = p.link.matcher(text);
            if (m.find() && m.start() < at) {
                best = p;
                url = m.group().replaceAll("[.,;]+$", "");
                at = m.start();
            }
        }
        return best == null ? null : new Meeting(e, best, url);
    }

    /** Today's calls that haven't ended yet, in time order. */
    static List<Meeting> upcoming(List<CalEvent> events, long now) {
        List<Meeting> out = new ArrayList<>();
        for (CalEvent e : events) {
            if (e.allDay || e.end <= now) continue;
            Meeting m = of(e);
            if (m != null) out.add(m);
        }
        return out;
    }

    /** All of today's calls (also the past ones), for the day stats. */
    static List<Meeting> all(List<CalEvent> events) {
        List<Meeting> out = new ArrayList<>();
        for (CalEvent e : events) {
            if (e.allDay) continue;
            Meeting m = of(e);
            if (m != null) out.add(m);
        }
        return out;
    }
}
