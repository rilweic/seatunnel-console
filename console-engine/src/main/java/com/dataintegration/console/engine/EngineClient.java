package com.dataintegration.console.engine;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The only way the console talks to an execution engine. P1 ships {@code ZetaRestClient}; Flink and
 * Spark implementations are planned for P3 (see docs/design.md §4.2).
 */
public interface EngineClient {

    EngineType type();

    /**
     * Option definitions of a connector, read from the cluster so they always match its version.
     *
     * @param pluginType {@code source} or {@code sink}
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
    long submit(SubmitJobRequest request);

    /**
     * Stops a job.
     *
     * @param withSavepoint stop with a savepoint so the job can be restored later
     * @param force cancel immediately, ignoring withSavepoint
     */
    void stop(long jobId, boolean withSavepoint, boolean force);

    /** Empty when the engine no longer knows the job, e.g. after history expiry. */
    Optional<EngineJobInfo> jobInfo(long jobId);

    /**
     * Log files of a job across all engine nodes. Empty when the engine keeps no per-job logs, for
     * example when Zeta's log4j2 routing is not enabled or the files have expired.
     */
    List<EngineLogFile> listLogs(long jobId);

    /**
     * Reads one log file, keeping at most the last {@code maxBytes} bytes.
     *
     * @return the content; {@link LogContent#truncated()} tells whether the head was dropped
     */
    LogContent readLog(EngineLogFile file, long maxBytes);

    record LogContent(String text, boolean truncated) {}
}
