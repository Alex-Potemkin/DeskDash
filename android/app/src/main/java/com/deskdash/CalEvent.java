package com.deskdash;

/** One calendar occurrence, already expanded for the shown day. */
public class CalEvent {
    public final String title;
    public final long start;
    public final long end;
    public final boolean allDay;
    public String location = "";
    public String calendar = "";
    /** Colour from Google Calendar, 0 when the source has none (ICS). */
    public int color;

    public CalEvent(String title, long start, long end, boolean allDay) {
        this.title = title == null || title.isEmpty() ? "(без названия)" : title;
        this.start = start;
        this.end = end;
        this.allDay = allDay;
    }
}
