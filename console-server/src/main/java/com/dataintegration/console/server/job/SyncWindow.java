package com.dataintegration.console.server.job;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * The time range a continuous incremental run reads (docs/design.md §7.5): from where the job's
 * last successful window ended (its watermark) up to the scheduled time minus the lag, end
 * exclusive. Fixed on the run instance when it starts, so retries and reruns read the same range.
 *
 * @param start null = from the beginning (the job has never synced a window)
 * @param end exclusive
 */
public record SyncWindow(LocalDateTime start, LocalDateTime end) {

    /** What {@code ${system.window.start}} becomes when there is no watermark yet. */
    static final LocalDateTime BEGINNING = LocalDateTime.of(1970, 1, 1, 0, 0);

    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** The window of a run scheduled at {@code scheduledTime} that carries on from {@code watermark}. */
    public static SyncWindow continuing(LocalDateTime watermark, LocalDateTime scheduledTime, int lagSeconds) {
        return new SyncWindow(watermark, endFor(scheduledTime, lagSeconds));
    }

    /**
     * Where the window of a run scheduled at {@code scheduledTime} ends. Whole seconds, because that is
     * what the variables show and the watermark keeps.
     */
    public static LocalDateTime endFor(LocalDateTime scheduledTime, int lagSeconds) {
        return scheduledTime.truncatedTo(ChronoUnit.SECONDS).minusSeconds(Math.max(0, lagSeconds));
    }

    /** Nothing to read: the watermark has already reached the end (e.g. two runs in quick succession). */
    public boolean empty() {
        return start != null && !end.isAfter(start);
    }

    public String startText() {
        return FORMAT.format(start == null ? BEGINNING : start);
    }

    public String endText() {
        return FORMAT.format(end);
    }

    @Override
    public String toString() {
        return "[" + (start == null ? "最早" : startText()) + ", " + endText() + ")";
    }
}
