package com.dataintegration.console.engine;

import java.util.Objects;

/**
 * A fully rendered job ready for submission. {@code config} is HOCON with credentials already
 * injected, so it must never be logged or persisted.
 *
 * @param jobName display name on the engine side
 * @param config rendered HOCON
 * @param restoreMode how to restore state; {@link RestoreMode#NONE} for a fresh start
 * @param restoreSourceJobId engine job id to restore from, required unless restoreMode is NONE
 */
public record SubmitJobRequest(
        String jobName, String config, RestoreMode restoreMode, Long restoreSourceJobId) {

    public SubmitJobRequest {
        Objects.requireNonNull(config, "config");
        restoreMode = restoreMode == null ? RestoreMode.NONE : restoreMode;
        if (restoreMode != RestoreMode.NONE && restoreSourceJobId == null) {
            throw new IllegalArgumentException("restoreSourceJobId is required for " + restoreMode);
        }
    }

    public static SubmitJobRequest fresh(String jobName, String config) {
        return new SubmitJobRequest(jobName, config, RestoreMode.NONE, null);
    }

    @Override
    public String toString() {
        return "SubmitJobRequest[jobName=" + jobName + ", restoreMode=" + restoreMode
                + ", restoreSourceJobId=" + restoreSourceJobId + ", config=<redacted>]";
    }

    public enum RestoreMode {
        NONE,
        SAVEPOINT,
        CHECKPOINT
    }
}
