package com.dataintegration.console.server.retention;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataintegration.console.common.ConsoleException;
import com.dataintegration.console.common.ErrorCodes;
import com.dataintegration.console.common.UuidV7;
import com.dataintegration.console.server.message.Messages;
import com.dataintegration.console.server.persistence.entity.BackfillBatch;
import com.dataintegration.console.server.persistence.entity.JobInstance;
import com.dataintegration.console.server.persistence.entity.JobInstanceAttempt;
import com.dataintegration.console.server.persistence.entity.JobInstanceEvent;
import com.dataintegration.console.server.persistence.entity.JobMetricSample;
import com.dataintegration.console.server.persistence.entity.Message;
import com.dataintegration.console.server.persistence.entity.RetentionCleanupLog;
import com.dataintegration.console.server.persistence.entity.RetentionPolicy;
import com.dataintegration.console.server.persistence.entity.SysUser;
import com.dataintegration.console.server.persistence.mapper.BackfillBatchMapper;
import com.dataintegration.console.server.persistence.mapper.JobInstanceAttemptMapper;
import com.dataintegration.console.server.persistence.mapper.JobInstanceEventMapper;
import com.dataintegration.console.server.persistence.mapper.JobInstanceMapper;
import com.dataintegration.console.server.persistence.mapper.JobMetricSampleMapper;
import com.dataintegration.console.server.persistence.mapper.MessageMapper;
import com.dataintegration.console.server.persistence.mapper.RetentionCleanupLogMapper;
import com.dataintegration.console.server.persistence.mapper.RetentionPolicyMapper;
import com.dataintegration.console.server.persistence.mapper.SysUserMapper;
import com.dataintegration.console.server.run.LogStorage;
import com.dataintegration.console.server.run.LogStorage.PurgeStats;
import com.dataintegration.console.server.run.LogStorage.StorageStats;
import com.dataintegration.console.server.scheduler.ScheduleSpec;
import com.dataintegration.console.server.scheduler.ScheduleSpec.BlockStrategy;
import com.dataintegration.console.server.scheduler.ScheduleSpec.MisfireStrategy;
import com.dataintegration.console.server.scheduler.XxlJobAdminClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Unified historical data retention policy and cleanup engine.
 *
 * <p>Manages retention for metric samples, terminal job instances (with attempts, events and
 * backfills), archived engine log files, and in-console messages (preserving unresolved alerts).
 */
@Service
public class RetentionService {

    private static final Logger log = LoggerFactory.getLogger(RetentionService.class);

    private static final String XXL_JOB_NAME = "[系统] 历史数据保留清理";
    private static final int MAX_RETENTION_DAYS = 3650;
    private static final int METRIC_BATCH_SIZE = 2000;
    private static final int INSTANCE_BATCH_SIZE = 200;
    private static final int BACKFILL_BATCH_SIZE = 500;
    private static final int HISTORY_KEEP_COUNT = 100;

    private final RetentionPolicyMapper policyMapper;
    private final RetentionCleanupLogMapper cleanupLogMapper;
    private final JobMetricSampleMapper metricSampleMapper;
    private final JobInstanceMapper instanceMapper;
    private final JobInstanceAttemptMapper attemptMapper;
    private final JobInstanceEventMapper eventMapper;
    private final BackfillBatchMapper backfillMapper;
    private final MessageMapper messageMapper;
    private final SysUserMapper userMapper;
    private final LogStorage logStorage;
    private final Messages messages;
    private final ObjectProvider<XxlJobAdminClient> xxlJobAdminClient;

    private final AtomicBoolean running = new AtomicBoolean(false);

    public RetentionService(
            RetentionPolicyMapper policyMapper,
            RetentionCleanupLogMapper cleanupLogMapper,
            JobMetricSampleMapper metricSampleMapper,
            JobInstanceMapper instanceMapper,
            JobInstanceAttemptMapper attemptMapper,
            JobInstanceEventMapper eventMapper,
            BackfillBatchMapper backfillMapper,
            MessageMapper messageMapper,
            SysUserMapper userMapper,
            LogStorage logStorage,
            Messages messages,
            ObjectProvider<XxlJobAdminClient> xxlJobAdminClient) {
        this.policyMapper = policyMapper;
        this.cleanupLogMapper = cleanupLogMapper;
        this.metricSampleMapper = metricSampleMapper;
        this.instanceMapper = instanceMapper;
        this.attemptMapper = attemptMapper;
        this.eventMapper = eventMapper;
        this.backfillMapper = backfillMapper;
        this.messageMapper = messageMapper;
        this.userMapper = userMapper;
        this.logStorage = logStorage;
        this.messages = messages;
        this.xxlJobAdminClient = xxlJobAdminClient;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void syncOnStartup() {
        try {
            syncXxlJob(loadOrInitPolicy());
        } catch (RuntimeException e) {
            log.warn("Initial XXL-JOB sync for retention cleanup failed: {}", e.toString());
        }
    }

    public PolicyView getPolicy() {
        RetentionPolicy p = loadOrInitPolicy();
        return toPolicyView(p, xxlJobAdminClient.getIfAvailable() != null);
    }

    public PolicyView updatePolicy(UpdatePolicyRequest req, String userId) {
        if (req == null) {
            throw new ConsoleException(ErrorCodes.INVALID_ARGUMENT, "请求体不能为空");
        }
        int runHour = req.runHour() == null ? 3 : req.runHour();
        if (runHour < 0 || runHour > 23) {
            throw new ConsoleException(ErrorCodes.INVALID_ARGUMENT, "每日执行时间必须在 0–23 点之间");
        }
        int metricDays = validateDays(req.metricRetentionDays(), "指标采样保留天数");
        int instanceDays = validateDays(req.instanceRetentionDays(), "运行记录保留天数");
        int logDays = validateDays(req.logRetentionDays(), "归档日志保留天数");
        int messageDays = validateDays(req.messageRetentionDays(), "站内消息保留天数");

        RetentionPolicy p = loadOrInitPolicy();
        p.setEnabled(req.enabled() != null ? req.enabled() : Boolean.TRUE);
        p.setRunHour(runHour);
        p.setMetricRetentionDays(metricDays);
        p.setInstanceRetentionDays(instanceDays);
        p.setLogRetentionDays(logDays);
        p.setMessageRetentionDays(messageDays);
        p.setUpdatedBy(userId);
        p.setUpdatedAt(LocalDateTime.now());
        policyMapper.updateById(p);
        syncXxlJob(p);
        log.info("Retention policy updated by {}: enabled={}, runHour={}, metric={}d, instance={}d, log={}d, message={}d, xxlJobId={}",
                userId, p.getEnabled(), p.getRunHour(), metricDays, instanceDays, logDays, messageDays, p.getXxlJobId());
        return toPolicyView(p, xxlJobAdminClient.getIfAvailable() != null);
    }

    public StorageOverview getStats() {
        RetentionPolicy p = loadOrInitPolicy();
        LocalDateTime now = LocalDateTime.now();

        long metricTotal = safeCount(metricSampleMapper.selectCount(null));
        long metricExpired = p.getMetricRetentionDays() > 0
                ? safeCount(metricSampleMapper.selectCount(new LambdaQueryWrapper<JobMetricSample>()
                .lt(JobMetricSample::getSampledAt, now.minusDays(p.getMetricRetentionDays()))))
                : 0;

        long instanceTotal = safeCount(instanceMapper.selectCount(null));
        long instanceExpired = p.getInstanceRetentionDays() > 0
                ? cleanupLogMapper.countExpiredInstances(now.minusDays(p.getInstanceRetentionDays()))
                : 0;

        long attemptTotal = safeCount(attemptMapper.selectCount(null));
        long eventTotal = safeCount(eventMapper.selectCount(null));

        long backfillTotal = safeCount(backfillMapper.selectCount(null));
        long backfillExpired = p.getInstanceRetentionDays() > 0
                ? cleanupLogMapper.countExpiredBackfills(now.minusDays(p.getInstanceRetentionDays()))
                : 0;

        StorageStats logStats = logStorage.stats();

        long messageTotal = safeCount(messageMapper.selectCount(null));
        long messageExpired = p.getMessageRetentionDays() > 0
                ? safeCount(messageMapper.selectCount(new LambdaQueryWrapper<Message>()
                .lt(Message::getLastAt, now.minusDays(p.getMessageRetentionDays()))
                .and(w -> w.isNull(Message::getDedupKey).or().isNotNull(Message::getResolvedAt))))
                : 0;

        return new StorageOverview(
                metricTotal, metricExpired,
                instanceTotal, instanceExpired,
                attemptTotal, eventTotal,
                backfillTotal, backfillExpired,
                logStats.fileCount(), logStats.totalBytes(),
                messageTotal, messageExpired,
                running.get());
    }

    public List<CleanupLogView> listHistory(int limit) {
        int capped = Math.max(1, Math.min(limit, HISTORY_KEEP_COUNT));
        List<RetentionCleanupLog> rows = cleanupLogMapper.selectList(
                new LambdaQueryWrapper<RetentionCleanupLog>()
                        .orderByDesc(RetentionCleanupLog::getStartedAt)
                        .last("LIMIT " + capped));
        if (rows.isEmpty()) {
            return List.of();
        }
        Set<String> userIds = rows.stream()
                .map(RetentionCleanupLog::getTriggeredBy)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<String, String> userNames = new HashMap<>();
        if (!userIds.isEmpty()) {
            for (SysUser u : userMapper.selectByIds(userIds)) {
                userNames.put(u.getId(), u.getDisplayName() != null && !u.getDisplayName().isBlank()
                        ? u.getDisplayName() : u.getUsername());
            }
        }
        return rows.stream().map(r -> toLogView(r, userNames.get(r.getTriggeredBy()))).toList();
    }

    /** Manually triggers a retention cleanup immediately (admin-only). */
    public CleanupLogView runNow(String userId) {
        if (!running.compareAndSet(false, true)) {
            throw new ConsoleException(ErrorCodes.CONFLICT, "数据清理任务正在执行中，请稍后再试");
        }
        try {
            RetentionCleanupLog entry = executeCleanup("MANUAL", userId);
            String name = null;
            if (userId != null) {
                SysUser u = userMapper.selectById(userId);
                if (u != null) {
                    name = u.getDisplayName() != null && !u.getDisplayName().isBlank()
                            ? u.getDisplayName() : u.getUsername();
                }
            }
            return toLogView(entry, name);
        } finally {
            running.set(false);
        }
    }

    /** Invoked by {@code RetentionJobHandler} when XXL-JOB triggers scheduled cleanup. */
    public CleanupLogView runFromXxlJob() {
        if (!running.compareAndSet(false, true)) {
            return null;
        }
        try {
            RetentionCleanupLog entry = executeCleanup("SCHEDULED", null);
            return toLogView(entry, null);
        } finally {
            running.set(false);
        }
    }

    /**
     * Hourly check at :05.
     *
     * <p>When XXL-JOB is enabled, scheduled cleanup is driven by XXL-JOB ({@code retentionCleanupHandler});
     * this method only retries syncing the policy if the previous sync failed. When XXL-JOB is
     * disabled ({@code console.xxl-job.enabled=false}), this method drives daily cleanup locally.
     */
    @Scheduled(cron = "0 5 * * * *")
    public void scheduledCheck() {
        RetentionPolicy p = loadOrInitPolicy();
        if (xxlJobAdminClient.getIfAvailable() != null) {
            if (!"SYNCED".equals(p.getSyncStatus())) {
                syncXxlJob(p);
            }
            return;
        }
        if (!Boolean.TRUE.equals(p.getEnabled())) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        if (now.getHour() != p.getRunHour()) {
            return;
        }
        LocalDateTime startOfDay = now.toLocalDate().atStartOfDay();
        long todayRuns = safeCount(cleanupLogMapper.selectCount(new LambdaQueryWrapper<RetentionCleanupLog>()
                .eq(RetentionCleanupLog::getTriggerType, "SCHEDULED")
                .ge(RetentionCleanupLog::getStartedAt, startOfDay)));
        if (todayRuns > 0) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            executeCleanup("SCHEDULED", null);
        } finally {
            running.set(false);
        }
    }

    void syncXxlJob(RetentionPolicy p) {
        XxlJobAdminClient client = xxlJobAdminClient.getIfAvailable();
        if (client == null) {
            if (!"LOCAL".equals(p.getSyncStatus()) || p.getSyncError() != null) {
                p.setSyncStatus("LOCAL");
                p.setSyncError(null);
                policyMapper.updateById(p);
            }
            return;
        }
        ScheduleSpec spec = new ScheduleSpec(
                "0 5 " + p.getRunHour() + " * * ?",
                0,
                0,
                BlockStrategy.DISCARD_LATER,
                MisfireStrategy.DO_NOTHING);
        try {
            Integer jobId = p.getXxlJobId();
            if (jobId == null) {
                jobId = client.addJob(XXL_JOB_NAME, spec, XxlJobAdminClient.RETENTION_HANDLER_NAME, "");
                p.setXxlJobId(jobId);
            } else {
                try {
                    client.updateJob(jobId, XXL_JOB_NAME, spec, XxlJobAdminClient.RETENTION_HANDLER_NAME, "");
                } catch (ConsoleException e) {
                    if (e.getCode() != ErrorCodes.SCHEDULER_JOB_MISSING) {
                        throw e;
                    }
                    jobId = client.addJob(XXL_JOB_NAME, spec, XxlJobAdminClient.RETENTION_HANDLER_NAME, "");
                    p.setXxlJobId(jobId);
                }
            }
            if (Boolean.TRUE.equals(p.getEnabled())) {
                client.startJob(jobId);
            } else {
                client.stopJob(jobId);
            }
            p.setSyncStatus("SYNCED");
            p.setSyncError(null);
            policyMapper.updateById(p);
            log.info("Synced retention cleanup to XXL-JOB jobId={} (enabled={}, cron='{}')",
                    jobId, p.getEnabled(), spec.cron());
        } catch (RuntimeException e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.toString();
            p.setSyncStatus("FAILED");
            p.setSyncError(msg.length() > 480 ? msg.substring(0, 480) : msg);
            policyMapper.updateById(p);
            log.warn("Failed to sync retention cleanup to XXL-JOB: {}", msg);
        }
    }

    private RetentionCleanupLog executeCleanup(String triggerType, String triggeredBy) {
        RetentionPolicy p = loadOrInitPolicy();
        LocalDateTime startedAt = LocalDateTime.now();
        long startNanos = System.nanoTime();

        long deletedMetrics = 0;
        long deletedInstances = 0;
        long deletedAttempts = 0;
        long deletedEvents = 0;
        long deletedBackfills = 0;
        long deletedLogFiles = 0;
        long deletedLogBytes = 0;
        long deletedMessages = 0;
        String status = "SUCCESS";
        String errorMessage = null;

        try {
            // 1. Metric samples (includes long-running streaming jobs)
            if (p.getMetricRetentionDays() > 0) {
                LocalDateTime metricCutoff = startedAt.minusDays(p.getMetricRetentionDays());
                int batch;
                do {
                    batch = cleanupLogMapper.deleteMetricSamplesBatch(metricCutoff, METRIC_BATCH_SIZE);
                    deletedMetrics += batch;
                } while (batch == METRIC_BATCH_SIZE);
            }

            // 2. Terminal job instances + child attempts/events/samples + terminal backfills
            if (p.getInstanceRetentionDays() > 0) {
                LocalDateTime instanceCutoff = startedAt.minusDays(p.getInstanceRetentionDays());
                while (true) {
                    List<String> ids = cleanupLogMapper.findExpiredInstanceIds(instanceCutoff, INSTANCE_BATCH_SIZE);
                    if (ids.isEmpty()) {
                        break;
                    }
                    // Best-effort cleanup of archived logs referenced by these attempts
                    List<JobInstanceAttempt> attempts = attemptMapper.selectList(
                            new LambdaQueryWrapper<JobInstanceAttempt>()
                                    .select(JobInstanceAttempt::getId, JobInstanceAttempt::getLogStorageKey)
                                    .in(JobInstanceAttempt::getInstanceId, ids)
                                    .isNotNull(JobInstanceAttempt::getLogStorageKey));
                    for (JobInstanceAttempt a : attempts) {
                        try {
                            logStorage.delete(a.getLogStorageKey());
                        } catch (RuntimeException e) {
                            log.warn("Could not delete log archive {}: {}", a.getLogStorageKey(), e.toString());
                        }
                    }

                    deletedMetrics += metricSampleMapper.delete(
                            new LambdaQueryWrapper<JobMetricSample>().in(JobMetricSample::getInstanceId, ids));
                    deletedEvents += eventMapper.delete(
                            new LambdaQueryWrapper<JobInstanceEvent>().in(JobInstanceEvent::getInstanceId, ids));
                    deletedAttempts += attemptMapper.delete(
                            new LambdaQueryWrapper<JobInstanceAttempt>().in(JobInstanceAttempt::getInstanceId, ids));
                    deletedInstances += instanceMapper.delete(
                            new LambdaQueryWrapper<JobInstance>().in(JobInstance::getId, ids));
                }

                int bfBatch;
                do {
                    bfBatch = cleanupLogMapper.deleteExpiredBackfillsBatch(instanceCutoff, BACKFILL_BATCH_SIZE);
                    deletedBackfills += bfBatch;
                } while (bfBatch == BACKFILL_BATCH_SIZE);
            }

            // 3. Archived log day directories
            if (p.getLogRetentionDays() > 0) {
                LocalDate logCutoff = startedAt.toLocalDate().minusDays(p.getLogRetentionDays());
                PurgeStats purged = logStorage.purgeOlderThan(logCutoff);
                deletedLogFiles = purged.deletedFiles();
                deletedLogBytes = purged.deletedBytes();
            }

            // 4. In-console messages (unresolved open problems are preserved)
            if (p.getMessageRetentionDays() > 0) {
                deletedMessages = messages.purge(Duration.ofDays(p.getMessageRetentionDays()));
            }
        } catch (RuntimeException e) {
            status = "FAILED";
            String msg = e.getMessage() != null ? e.getMessage() : e.toString();
            errorMessage = msg.length() > 1000 ? msg.substring(0, 1000) : msg;
            log.error("Retention cleanup failed ({}): {}", triggerType, msg, e);
        }

        LocalDateTime finishedAt = LocalDateTime.now();
        long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;

        RetentionCleanupLog entry = new RetentionCleanupLog();
        entry.setId(UuidV7.next());
        entry.setTriggerType(triggerType);
        entry.setTriggeredBy(triggeredBy);
        entry.setStatus(status);
        entry.setDeletedMetricSamples(deletedMetrics);
        entry.setDeletedInstances(deletedInstances);
        entry.setDeletedAttempts(deletedAttempts);
        entry.setDeletedEvents(deletedEvents);
        entry.setDeletedBackfills(deletedBackfills);
        entry.setDeletedLogFiles(deletedLogFiles);
        entry.setDeletedLogBytes(deletedLogBytes);
        entry.setDeletedMessages(deletedMessages);
        entry.setErrorMessage(errorMessage);
        entry.setStartedAt(startedAt);
        entry.setFinishedAt(finishedAt);
        entry.setDurationMs(durationMs);
        cleanupLogMapper.insert(entry);
        cleanupLogMapper.pruneOldLogs(HISTORY_KEEP_COUNT);

        log.info("Retention cleanup {} ({}ms): metrics={}, instances={}, attempts={}, events={}, backfills={}, logFiles={} ({}B), messages={}",
                status, durationMs, deletedMetrics, deletedInstances, deletedAttempts, deletedEvents,
                deletedBackfills, deletedLogFiles, deletedLogBytes, deletedMessages);
        return entry;
    }

    private RetentionPolicy loadOrInitPolicy() {
        RetentionPolicy p = policyMapper.selectById(RetentionPolicy.SINGLETON_ID);
        if (p != null) {
            return p;
        }
        p = new RetentionPolicy();
        p.setId(RetentionPolicy.SINGLETON_ID);
        p.setEnabled(true);
        p.setRunHour(3);
        p.setMetricRetentionDays(14);
        p.setInstanceRetentionDays(90);
        p.setLogRetentionDays(30);
        p.setMessageRetentionDays(90);
        p.setUpdatedAt(LocalDateTime.now());
        policyMapper.insert(p);
        return p;
    }

    private static int validateDays(Integer value, String label) {
        if (value == null || value < 0 || value > MAX_RETENTION_DAYS) {
            throw new ConsoleException(ErrorCodes.INVALID_ARGUMENT,
                    label + " 必须在 0–" + MAX_RETENTION_DAYS + " 之间（0 表示永久保留）");
        }
        return value;
    }

    private static long safeCount(Long count) {
        return count == null ? 0L : count;
    }

    private static PolicyView toPolicyView(RetentionPolicy p, boolean xxlJobEnabled) {
        return new PolicyView(
                Boolean.TRUE.equals(p.getEnabled()),
                p.getRunHour(),
                p.getMetricRetentionDays(),
                p.getInstanceRetentionDays(),
                p.getLogRetentionDays(),
                p.getMessageRetentionDays(),
                xxlJobEnabled ? "XXL_JOB" : "LOCAL",
                p.getXxlJobId(),
                p.getSyncStatus(),
                p.getSyncError(),
                p.getUpdatedAt());
    }

    private static CleanupLogView toLogView(RetentionCleanupLog r, String triggeredByName) {
        return new CleanupLogView(
                r.getId(),
                r.getTriggerType(),
                triggeredByName,
                r.getStatus(),
                r.getDeletedMetricSamples(),
                r.getDeletedInstances(),
                r.getDeletedAttempts(),
                r.getDeletedEvents(),
                r.getDeletedBackfills(),
                r.getDeletedLogFiles(),
                r.getDeletedLogBytes(),
                r.getDeletedMessages(),
                r.getErrorMessage(),
                r.getStartedAt(),
                r.getFinishedAt(),
                r.getDurationMs());
    }

    public record UpdatePolicyRequest(
            Boolean enabled,
            Integer runHour,
            Integer metricRetentionDays,
            Integer instanceRetentionDays,
            Integer logRetentionDays,
            Integer messageRetentionDays) {}

    public record PolicyView(
            boolean enabled,
            int runHour,
            int metricRetentionDays,
            int instanceRetentionDays,
            int logRetentionDays,
            int messageRetentionDays,
            String schedulerMode,
            Integer xxlJobId,
            String syncStatus,
            String syncError,
            LocalDateTime updatedAt) {}

    public record StorageOverview(
            long metricSamplesTotal,
            long metricSamplesExpired,
            long instancesTotal,
            long instancesExpired,
            long attemptsTotal,
            long eventsTotal,
            long backfillsTotal,
            long backfillsExpired,
            long logFilesTotal,
            long logBytesTotal,
            long messagesTotal,
            long messagesExpired,
            boolean running) {}

    public record CleanupLogView(
            String id,
            String triggerType,
            String triggeredByName,
            String status,
            long deletedMetricSamples,
            long deletedInstances,
            long deletedAttempts,
            long deletedEvents,
            long deletedBackfills,
            long deletedLogFiles,
            long deletedLogBytes,
            long deletedMessages,
            String errorMessage,
            LocalDateTime startedAt,
            LocalDateTime finishedAt,
            long durationMs) {}
}
