package com.dataintegration.console.engine;

/**
 * Checkpoint statistics of a running job, summed over its pipelines.
 *
 * @param lastCompletedAt epoch millis of the latest completed checkpoint, null when none completed
 * @param lastFailedAt epoch millis of the latest failed checkpoint, null when none failed
 * @param lastFailureReason why it failed, e.g. {@code CHECKPOINT_EXPIRED}
 * @param latestExternalPath external path of the latest completed checkpoint (Flink), null on Zeta
 */
public record EngineCheckpoints(
        int triggered,
        int completed,
        int failed,
        Long lastCompletedAt,
        Long lastFailedAt,
        String lastFailureReason,
        String latestExternalPath) {

    public EngineCheckpoints(
            int triggered, int completed, int failed, Long lastCompletedAt, Long lastFailedAt, String lastFailureReason) {
        this(triggered, completed, failed, lastCompletedAt, lastFailedAt, lastFailureReason, null);
    }
}
