package com.dataintegration.console.server.scheduler;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * One trigger received from XXL-JOB.
 *
 * @param jobDefinitionId console job definition to run
 * @param instanceId run instance pre-created by the console for manual runs, reruns and backfills;
 *     null for cron triggers, where the handler creates the instance (docs/schema.md §4.1)
 * @param scheduledTime base time for variables (docs/design.md §8): taken from the executor param
 *     for reruns and backfills, otherwise the XXL-JOB trigger log time
 * @param startupParams highest-priority variable overrides for this run
 * @param xxlJobId XXL-JOB job id
 * @param xxlLogId XXL-JOB log id, ties a run instance to the scheduler log
 */
public record ScheduledTrigger(
        String jobDefinitionId,
        String instanceId,
        LocalDateTime scheduledTime,
        Map<String, String> startupParams,
        long xxlJobId,
        long xxlLogId) {

    public ScheduledTrigger {
        startupParams = startupParams == null ? Map.of() : Map.copyOf(startupParams);
    }

    public boolean preCreated() {
        return instanceId != null;
    }
}
