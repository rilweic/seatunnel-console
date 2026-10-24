package com.dataintegration.console.server.scheduler;

import java.util.Objects;

/**
 * Schedule settings owned by the console and pushed to xxl-job-admin.
 *
 * @param cron Quartz-style cron expression as accepted by XXL-JOB, e.g. {@code 0 0 2 * * ?}
 * @param failRetryCount retries of a failed run, done by the console inside one run instance; XXL-JOB's own retry is always 0
 * @param timeoutSeconds run timeout; 0 means no timeout. On timeout the handler stops the engine job
 */
public record ScheduleSpec(
        String cron,
        int failRetryCount,
        int timeoutSeconds,
        BlockStrategy blockStrategy,
        MisfireStrategy misfireStrategy) {

    public ScheduleSpec {
        Objects.requireNonNull(cron, "cron");
        blockStrategy = blockStrategy == null ? BlockStrategy.SERIAL_EXECUTION : blockStrategy;
        misfireStrategy = misfireStrategy == null ? MisfireStrategy.DO_NOTHING : misfireStrategy;
    }

    /** What XXL-JOB does when a trigger fires while the previous run is still going. */
    public enum BlockStrategy {
        SERIAL_EXECUTION,
        DISCARD_LATER,
        COVER_EARLY
    }

    /** What XXL-JOB does with triggers missed while the admin was down. */
    public enum MisfireStrategy {
        DO_NOTHING,
        FIRE_ONCE_NOW
    }
}
