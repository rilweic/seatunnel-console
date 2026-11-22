package com.dataintegration.console.engine;

import java.util.Objects;

/**
 * A fully rendered job ready for submission. {@code config} is HOCON with credentials already
 * injected, so it must never be logged or persisted.
 *
 * @param jobName display name on the engine side
 * @param config rendered HOCON
 * @param restoreMode how to restore state; {@link RestoreMode#NONE} for a fresh start
 * @param restoreSourceJobId engine job id to restore from (Zeta)
 * @param restorePath external savepoint or checkpoint path to restore from (Flink)
 * @param jobId the engine job id to use for a fresh start, chosen by the caller so a submission
 *     whose response was lost can be looked up and is not submitted twice; null lets the engine pick
 */
public record SubmitJobRequest(
        String jobName,
        String config,
        RestoreMode restoreMode,
        String restoreSourceJobId,
        String restorePath,
        String jobId) {

    public SubmitJobRequest {
        Objects.requireNonNull(config, "config");
        restoreMode = restoreMode == null ? RestoreMode.NONE : restoreMode;
        if (restoreMode != RestoreMode.NONE
                && (restoreSourceJobId == null || restoreSourceJobId.isBlank())
                && (restorePath == null || restorePath.isBlank())) {
            throw new IllegalArgumentException("restoreSourceJobId or restorePath is required for " + restoreMode);
        }
    }

    public static SubmitJobRequest fresh(String jobName, String config) {
        return new SubmitJobRequest(jobName, config, RestoreMode.NONE, null, null, null);
    }

    public SubmitJobRequest withJobId(String id) {
        return new SubmitJobRequest(jobName, config, restoreMode, restoreSourceJobId, restorePath, id);
    }

    @Override
    public String toString() {
        return "SubmitJobRequest[jobName=" + jobName + ", restoreMode=" + restoreMode
                + ", restoreSourceJobId=" + restoreSourceJobId + ", restorePath=" + restorePath
                + ", jobId=" + jobId + ", config=<redacted>]";
    }

    public enum RestoreMode {
        NONE,
        SAVEPOINT,
        CHECKPOINT
    }
}
