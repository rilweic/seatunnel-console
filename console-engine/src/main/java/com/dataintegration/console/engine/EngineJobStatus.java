package com.dataintegration.console.engine;

/**
 * Engine-side job status, mirroring SeaTunnel Zeta's {@code JobStatus} (3.0.0). Other engines map
 * their own states onto these values.
 */
public enum EngineJobStatus {
    INITIALIZING(false),
    CREATED(false),
    PENDING(false),
    SCHEDULED(false),
    RUNNING(false),
    FAILING(false),
    DOING_SAVEPOINT(false),
    CANCELING(false),
    FAILED(true),
    SAVEPOINT_DONE(true),
    CANCELED(true),
    FINISHED(true),
    UNKNOWABLE(true);

    private final boolean terminal;

    EngineJobStatus(boolean terminal) {
        this.terminal = terminal;
    }

    public boolean isTerminal() {
        return terminal;
    }

    /** Unrecognised values map to {@link #UNKNOWABLE} so a newer engine never breaks polling. */
    public static EngineJobStatus parse(String value) {
        if (value == null || value.isBlank()) {
            return UNKNOWABLE;
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return UNKNOWABLE;
        }
    }
}
