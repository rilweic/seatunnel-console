package com.dataintegration.console.engine;

import java.util.Map;

/**
 * Snapshot of a job as reported by the engine.
 *
 * @param metrics raw engine metrics (e.g. SourceReceivedCount, SinkWriteCount); keys are engine
 *     specific
 * @param errorMessage failure reason, null unless the job failed
 * @param pipelineRestores how often the engine has restored the job's pipelines after a task
 *     failure; the job still reports RUNNING meanwhile (Zeta gives up after a few and fails it)
 */
public record EngineJobInfo(
        long jobId,
        String jobName,
        EngineJobStatus status,
        String createTime,
        Map<String, String> metrics,
        String errorMessage,
        int pipelineRestores) {

    public EngineJobInfo(
            long jobId, String jobName, EngineJobStatus status, String createTime, Map<String, String> metrics, String errorMessage) {
        this(jobId, jobName, status, createTime, metrics, errorMessage, 0);
    }
}
