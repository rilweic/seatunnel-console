package com.dataintegration.console.engine;

import java.util.Map;

/**
 * Snapshot of a job as reported by the engine.
 *
 * @param metrics raw engine metrics (e.g. SourceReceivedCount, SinkWriteCount); keys are engine
 *     specific
 * @param errorMessage failure reason, null unless the job failed
 */
public record EngineJobInfo(
        long jobId,
        String jobName,
        EngineJobStatus status,
        String createTime,
        Map<String, String> metrics,
        String errorMessage) {}
