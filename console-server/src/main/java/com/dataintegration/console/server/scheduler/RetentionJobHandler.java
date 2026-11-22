package com.dataintegration.console.server.scheduler;

import com.dataintegration.console.server.retention.RetentionService;
import com.dataintegration.console.server.retention.RetentionService.CleanupLogView;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * XXL-JOB handler for scheduled historical data retention cleanup. Registered alongside
 * {@link SeatunnelJobHandler} when XXL-JOB is enabled; falls back to local {@code @Scheduled}
 * when {@code console.xxl-job.enabled=false}.
 */
@Component
@ConditionalOnProperty(prefix = "console.xxl-job", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RetentionJobHandler {

    private final RetentionService retentionService;

    public RetentionJobHandler(RetentionService retentionService) {
        this.retentionService = retentionService;
    }

    @XxlJob(XxlJobAdminClient.RETENTION_HANDLER_NAME)
    public void execute() {
        XxlJobHelper.log("Starting scheduled historical data retention cleanup...");
        CleanupLogView result = retentionService.runFromXxlJob();
        if (result == null) {
            XxlJobHelper.log("Skipped: another retention cleanup is already in progress.");
            XxlJobHelper.handleSuccess("skipped (concurrent run in progress)");
            return;
        }
        String summary = String.format(
                "status=%s, duration=%dms, metrics=%d, instances=%d, attempts=%d, events=%d, backfills=%d, logFiles=%d (%dB), messages=%d",
                result.status(), result.durationMs(), result.deletedMetricSamples(), result.deletedInstances(),
                result.deletedAttempts(), result.deletedEvents(), result.deletedBackfills(),
                result.deletedLogFiles(), result.deletedLogBytes(), result.deletedMessages());
        XxlJobHelper.log("Retention cleanup finished: {}", summary);
        if ("SUCCESS".equals(result.status())) {
            XxlJobHelper.handleSuccess(summary);
        } else {
            XxlJobHelper.handleFail(result.errorMessage() != null ? result.errorMessage() : summary);
        }
    }
}
