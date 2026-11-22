package com.dataintegration.console.engine;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The only way the console talks to an execution engine. Supports Zeta REST API v2 and Flink
 * (CLI submission + Flink REST API v1).
 */
public interface EngineClient {

    EngineType type();

    /** Generates a fresh caller-chosen engine job id so a lost submission response can be looked up. */
    String newJobId();

    /**
     * Option definitions of a connector, read from the cluster or its SeaTunnel distribution so
     * they match its version.
     *
     * @param pluginType {@code source}, {@code sink} or {@code transform}
     * @param plugin connector name, e.g. {@code Jdbc}
     */
    List<ConnectorOption> connectorOptions(String pluginType, String plugin);

    /** Probes every configured master separately, for the health check page. */
    List<MasterStatus> probeMasters();

    /** Worker nodes and their resources. */
    List<EngineWorker> workers();

    /**
     * Whether the cluster has a connector installed. Only the master that answers is asked, so a
     * connector missing on other nodes is not detected.
     */
    boolean hasConnector(String pluginType, String plugin);

    /** Cluster overview, used by the cluster health check page. */
    Map<String, String> overview();

    /** Submits a job and returns the engine job id. */
    String submit(SubmitJobRequest request);

    /**
     * Stops a job.
     *
     * @param withSavepoint stop with a savepoint so the job can be restored later
     * @param force cancel immediately, ignoring withSavepoint
     * @return stop result, carrying the savepoint path on engines that require it for restore
     */
    StopResult stop(String jobId, boolean withSavepoint, boolean force);

    /** Disposes an external savepoint or retained checkpoint path no longer needed (Flink). */
    default void disposeSavepoint(String path) {}

    /** Empty when the engine no longer knows the job, e.g. after history expiry. */
    Optional<EngineJobInfo> jobInfo(String jobId);

    /** Checkpoint statistics of a job; empty when the engine has none for it. */
    default Optional<EngineCheckpoints> checkpoints(String jobId) {
        return Optional.empty();
    }

    /** Detailed exception trace from the engine (e.g. Flink {@code /jobs/{id}/exceptions}). */
    default Optional<String> jobExceptions(String jobId) {
        return Optional.empty();
    }

    /** Whether {@link #listLogs(String)} returns per-job logs suitable for archiving after an attempt. */
    default boolean archivesPerJobLogs() {
        return true;
    }

    /**
     * Whether savepoint/checkpoint restore requires an external storage path (e.g. Flink) rather
     * than restoring by previous engine job id (e.g. Zeta).
     */
    default boolean restoresByExternalPath() {
        return false;
    }

    /**
     * Whether restoring from a savepoint reuses the previous engine job id (Zeta) instead of
     * assigning a fresh job id (Flink).
     */
    default boolean reusesJobIdOnSavepointRestore() {
        return true;
    }

    /** Configuration warnings detected on the cluster (e.g. missing Flink savepoint dir). */
    default List<String> configWarnings() {
        return List.of();
    }

    /**
     * Log files of a job across engine nodes. Empty when the engine keeps no per-job logs, for
     * example when Zeta's log4j2 routing is not enabled or the files have expired.
     */
    List<EngineLogFile> listLogs(String jobId);

    /**
     * Reads one log file, keeping at most the last {@code maxBytes} bytes.
     *
     * @return the content; {@link LogContent#truncated()} tells whether the head was dropped
     */
    LogContent readLog(EngineLogFile file, long maxBytes);

    record StopResult(String savepointPath) {
        public static final StopResult NONE = new StopResult(null);
    }

    record LogContent(String text, boolean truncated) {}
}
