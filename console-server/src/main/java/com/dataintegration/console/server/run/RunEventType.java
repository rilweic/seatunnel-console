package com.dataintegration.console.server.run;

/** Values of {@code job_instance_event.event_type}. */
public enum RunEventType {
    CREATED,
    SUBMITTED,
    STATUS_CHANGED,
    RETRY,
    CANCEL_REQUESTED,
    VALIDATED,
    /** The run's sync window was fixed, or the watermark moved (docs/design.md §7.5). */
    WINDOW,
    LOG_ARCHIVED,
    FINISHED,
    ERROR
}
