package com.dataintegration.console.server.message;

/** Values of {@code message.category} (docs/design.md §10.7). */
public enum MessageCategory {
    RUN_FAILED,
    VALIDATION,
    BACKFILL,
    STREAM_FAILED,
    STREAM_UNSTABLE,
    CHECKPOINT,
    SCHEMA_CHANGE,
    CLUSTER;

    /** The dedup key of this category's message about {@code subject} (a job, backfill or nothing). */
    public String key(String subject) {
        return subject == null ? name() : name() + ":" + subject;
    }
}
