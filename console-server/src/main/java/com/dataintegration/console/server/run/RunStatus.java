package com.dataintegration.console.server.run;

/** Values of {@code job_instance.status} (docs/schema.md §4.2). */
public enum RunStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    FAILED,
    CANCELED;

    public boolean isTerminal() {
        return this == SUCCESS || this == FAILED || this == CANCELED;
    }
}
