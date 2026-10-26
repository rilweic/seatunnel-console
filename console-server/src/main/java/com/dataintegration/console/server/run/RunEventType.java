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
    /** A real-time job's source or target table changed structure (docs/design.md §12 P2). */
    SCHEMA_CHANGE,
    LOG_ARCHIVED,
    FINISHED,
    ERROR
}
