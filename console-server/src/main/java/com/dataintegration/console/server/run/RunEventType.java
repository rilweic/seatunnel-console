package com.dataintegration.console.server.run;

/** Values of {@code job_instance_event.event_type}. */
public enum RunEventType {
    CREATED,
    SUBMITTED,
    STATUS_CHANGED,
    RETRY,
    CANCEL_REQUESTED,
    VALIDATED,
    LOG_ARCHIVED,
    FINISHED,
    ERROR
}
