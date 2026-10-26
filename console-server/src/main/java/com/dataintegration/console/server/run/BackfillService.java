package com.dataintegration.console.server.run;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.dataintegration.console.common.ConsoleException;
import com.dataintegration.console.common.ErrorCodes;
import com.dataintegration.console.server.auth.CurrentUser;
import com.dataintegration.console.server.job.JobService;
import com.dataintegration.console.server.job.ScheduleService;
import com.dataintegration.console.server.job.SyncWindow;
import com.dataintegration.console.server.job.WatermarkService;
import com.dataintegration.console.server.job.Variables;
import com.dataintegration.console.server.persistence.entity.BackfillBatch;
import com.dataintegration.console.server.persistence.entity.JobDefinition;
import com.dataintegration.console.server.persistence.entity.JobInstance;
import com.dataintegration.console.server.persistence.mapper.JobInstanceAttemptMapper;
import com.dataintegration.console.server.persistence.entity.JobInstanceAttempt;
import com.dataintegration.console.server.persistence.mapper.BackfillBatchMapper;
import com.dataintegration.console.server.persistence.mapper.JobInstanceMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;

import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Backfills (docs/design.md §9): one run per scheduled time in a date range, all with the version
 * that was current when the backfill was created.
 *
 * <p>XXL-JOB executes the triggers of one job one at a time, and with the DISCARD_LATER or
 * COVER_EARLY block strategies would drop or kill runs triggered together. So the batch is paced
 * here: a driver triggers the next scheduled time only when the job has no PENDING or RUNNING run,
 * whoever started it. A run that fails does not stop the batch; failed days can be rerun. If
 * XXL-JOB cannot take a run, the batch is PAUSED with the reason and resumes from that time.
 */
@Service
public class BackfillService {

    private static final Logger log = LoggerFactory.getLogger(BackfillService.class);

    public static final int MAX_RUNS = 1000;

    public enum Status {
        RUNNING,
        PAUSED,
        FINISHED,
        CANCELED
    }

    /** What a backfill would run, shown before the user confirms. */
    public record Plan(int totalRuns, String cron, LocalDateTime firstTime, LocalDateTime lastTime) {}

    public record BatchView(
            String id,
            String jobDefinitionId,
            Integer versionNo,
            LocalDate startDate,
            LocalDate endDate,
            String cron,
            Map<String, String> startupParams,
            int totalRuns,
            int triggered,
            Map<String, Long> statusCounts,
            String status,
            String errorMessage,
            LocalDateTime createdAt,
            LocalDateTime finishedAt) {}

    private final BackfillBatchMapper batchMapper;
    private final JobInstanceMapper instanceMapper;
    private final JobInstanceAttemptMapper attemptMapper;
    private final JobService jobService;
    private final ScheduleService scheduleService;
    private final RunService runService;
    private final WatermarkService watermarks;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;

    public BackfillService(
            BackfillBatchMapper batchMapper,
            JobInstanceMapper instanceMapper,
            JobInstanceAttemptMapper attemptMapper,
            JobService jobService,
            ScheduleService scheduleService,
            RunService runService,
            WatermarkService watermarks,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager) {
        this.transactions = new TransactionTemplate(transactionManager);
        this.batchMapper = batchMapper;
        this.instanceMapper = instanceMapper;
        this.attemptMapper = attemptMapper;
        this.jobService = jobService;
        this.scheduleService = scheduleService;
        this.runService = runService;
        this.watermarks = watermarks;
        this.objectMapper = objectMapper;
    }

    public Plan plan(String jobId, LocalDate start, LocalDate end) {
        jobService.require(jobId);
        requireNoOpenBatch(jobId);
        String cron = scheduleService.cronOf(jobId);
        List<LocalDateTime> times = times(cron, start, end);
        return new Plan(times.size(), cron, times.get(0), times.get(times.size() - 1));
    }

    @Transactional
    public BatchView create(String jobId, LocalDate start, LocalDate end, Map<String, String> startupParams) {
        // Same lock as deleting the job takes, so a batch is never created for a job deleted meanwhile.
        jobService.lockJob(jobId);
        JobDefinition job = jobService.requireEditable(jobId);
        if (job.getCurrentVersionId() == null) {
            throw new ConsoleException(ErrorCodes.INVALID_ARGUMENT, "任务还没有保存过版本，先保存再补数");
        }
        requireNoOpenBatch(jobId);
        if (startupParams != null) {
            startupParams.keySet().forEach(Variables::validateName);
        }
        String cron = scheduleService.cronOf(jobId);
        List<LocalDateTime> times = times(cron, start, end);

        BackfillBatch batch = new BackfillBatch();
        batch.setJobDefinitionId(jobId);
        batch.setJobVersionId(job.getCurrentVersionId());
        batch.setStartTime(start.atStartOfDay());
        batch.setEndTime(end.atStartOfDay());
        batch.setCron(cron);
        batch.setStartupParamsJson(
                startupParams == null || startupParams.isEmpty() ? null : objectMapper.writeValueAsString(startupParams));
        batch.setParallelism(1);
        batch.setUseLatestVersion(false);
        batch.setTotalRuns(times.size());
        batch.setNextIndex(0);
        batch.setStatus(Status.RUNNING.name());
        batch.setCreatedBy(CurrentUser.idOrNull());
        batchMapper.insert(batch);
        return view(batch);
    }

    public List<BatchView> list(String jobId) {
        jobService.require(jobId);
        return batchMapper
                .selectList(new LambdaQueryWrapper<BackfillBatch>()
                        .eq(BackfillBatch::getJobDefinitionId, jobId)
                        .orderByDesc(BackfillBatch::getCreatedAt))
                .stream()
                .map(this::view)
                .toList();
    }

    public BatchView get(String batchId) {
        return view(require(batchId));
    }

    /** One open (running or paused) batch per job: two would take turns on the job, in an order nobody chose. */
    private void requireNoOpenBatch(String jobId) {
        BackfillBatch open = batchMapper.selectOne(new LambdaQueryWrapper<BackfillBatch>()
                .eq(BackfillBatch::getJobDefinitionId, jobId)
                .in(BackfillBatch::getStatus, Status.RUNNING.name(), Status.PAUSED.name())
                .last("limit 1"));
        if (open != null) {
            throw new ConsoleException(ErrorCodes.CONFLICT, "这个任务已有一个" + (Status.PAUSED.name().equals(open.getStatus()) ? "暂停中" : "进行中")
                    + "的补数（" + open.getStartTime().toLocalDate() + " ～ " + open.getEndTime().toLocalDate() + "），请先停止它或等它结束");
        }
    }

    /** A backfill only runs times that have come: the end date is today at the latest, and today's later times are left out. */
    private static List<LocalDateTime> times(String cron, LocalDate start, LocalDate end) {
        LocalDateTime now = LocalDateTime.now();
        if (end.isAfter(now.toLocalDate())) {
            throw new ConsoleException(ErrorCodes.INVALID_ARGUMENT, "结束日期不能晚于今天，补数只补已经到点的运行");
        }
        return ScheduleService.backfillTimes(cron, start, end, MAX_RUNS, now);
    }

    /** Stops the batch; its active run, if any, is stopped too. */
    public BatchView cancel(String batchId) {
        BackfillBatch batch = require(batchId);
        jobService.requireEditable(batch.getJobDefinitionId());
        int updated = batchMapper.update(
                null,
                new LambdaUpdateWrapper<BackfillBatch>()
                        .set(BackfillBatch::getStatus, Status.CANCELED.name())
                        .set(BackfillBatch::getFinishedAt, LocalDateTime.now())
                        .eq(BackfillBatch::getId, batchId)
                        .in(BackfillBatch::getStatus, Status.RUNNING.name(), Status.PAUSED.name()));
        if (updated == 0) {
            throw new ConsoleException(ErrorCodes.CONFLICT, "补数已经" + ("FINISHED".equals(batch.getStatus()) ? "结束" : "停止"));
        }
        for (JobInstance active : instanceMapper.selectList(new LambdaQueryWrapper<JobInstance>()
                .eq(JobInstance::getBackfillBatchId, batchId)
                .in(JobInstance::getStatus, RunStatus.PENDING.name(), RunStatus.RUNNING.name()))) {
            try {
                runService.stop(active.getId());
            } catch (ConsoleException e) {
                log.warn("Backfill {} canceled but run {} could not be stopped: {}", batchId, active.getId(), e.getMessage());
            }
        }
        return get(batchId);
    }

    public BatchView resume(String batchId) {
        BackfillBatch batch = require(batchId);
        jobService.requireEditable(batch.getJobDefinitionId());
        int updated = batchMapper.update(
                null,
                new LambdaUpdateWrapper<BackfillBatch>()
                        .set(BackfillBatch::getStatus, Status.RUNNING.name())
                        .set(BackfillBatch::getErrorMessage, null)
                        .eq(BackfillBatch::getId, batchId)
                        .eq(BackfillBatch::getStatus, Status.PAUSED.name()));
        if (updated == 0) {
            throw new ConsoleException(ErrorCodes.CONFLICT, "只有暂停中的补数才能继续");
        }
        return get(batchId);
    }

    @Scheduled(fixedDelayString = "${console.backfill.drive-interval:5s}")
    public void drive() {
        for (BackfillBatch batch : batchMapper.selectList(
                new LambdaQueryWrapper<BackfillBatch>().eq(BackfillBatch::getStatus, Status.RUNNING.name()))) {
            try {
                step(batch);
            } catch (RuntimeException e) {
                log.error("Backfill {} step failed", batch.getId(), e);
            }
        }
    }

    /** Triggers the batch's next scheduled time if the job is idle, or finishes the batch. */
    void step(BackfillBatch batch) {
        if (runService.hasActiveRun(batch.getJobDefinitionId())) {
            return;
        }
        int index = batch.getNextIndex();
        String unknown = unknownOutcome(batch);
        if (unknown != null) {
            transition(batch, Status.PAUSED, unknown);
            return;
        }
        if (index >= batch.getTotalRuns()) {
            transition(batch, Status.FINISHED, null);
            return;
        }
        JobDefinition job;
        List<LocalDateTime> times;
        SyncWindow window;
        Map<String, String> startupParams = runService.startupParams(batch.getStartupParamsJson());
        try {
            job = jobService.require(batch.getJobDefinitionId());
            // the times as they were when the batch was created, so the indexes keep their meaning
            times = ScheduleService.backfillTimes(batch.getCron(), batch.getStartTime().toLocalDate(),
                    batch.getEndTime().toLocalDate(), MAX_RUNS, batch.getCreatedAt());
            // a run that reads the sync window covers the interval up to its scheduled time (§7.5)
            window = watermarks.backfillWindow(
                    job, jobService.requireVersion(batch.getJobVersionId()), startupParams, batch.getCron(), times.get(index));
        } catch (ConsoleException e) {
            transition(batch, Status.PAUSED, e.getMessage());
            return;
        }
        RunService.NewRun newRun = new RunService.NewRun(
                    job,
                    batch.getJobVersionId(),
                    RunService.TriggerType.BACKFILL,
                    times.get(index),
                    startupParams,
                    scheduleService.failRetryCount(job.getId()) + 1,
                    null,
                    batch.getId(),
                    "backfill run " + (index + 1) + " of " + batch.getTotalRuns(),
                    window);
        // Claiming the slot and recording its run commit together: the console stopping in between
        // can neither skip the scheduled time nor leave it claimed without a run.
        JobInstance recorded;
        try {
            recorded = transactions.execute(status -> {
                int claimed = batchMapper.update(
                        null,
                        new LambdaUpdateWrapper<BackfillBatch>()
                                .set(BackfillBatch::getNextIndex, index + 1)
                                .eq(BackfillBatch::getId, batch.getId())
                                .eq(BackfillBatch::getStatus, Status.RUNNING.name())
                                .eq(BackfillBatch::getNextIndex, index));
                return claimed == 0 ? null : runService.record(newRun);
            });
        } catch (ConsoleException e) {
            transition(batch, Status.PAUSED, "could not record run " + (index + 1) + ": " + e.getMessage());
            return;
        }
        if (recorded == null) {
            return;
        }
        try {
            runService.handOver(recorded, newRun,
                    () -> Status.RUNNING.name().equals(batchMapper.selectById(batch.getId()).getStatus()));
        } catch (ConsoleException e) {
            // Hand the slot back so resuming retries this scheduled time.
            batchMapper.update(
                    null,
                    new LambdaUpdateWrapper<BackfillBatch>()
                            .set(BackfillBatch::getNextIndex, index)
                            .eq(BackfillBatch::getId, batch.getId()));
            transition(batch, Status.PAUSED, "could not hand run " + (index + 1) + " to XXL-JOB: " + e.getMessage());
        }
    }

    /**
     * Why the batch must wait for a person: its last run ended with the engine job's state unknown
     * (it may still be writing), so starting the next scheduled time could run two at once. Only
     * runs that ended after the batch last changed count, so resuming it lets it go on.
     */
    private String unknownOutcome(BackfillBatch batch) {
        JobInstance last = instanceMapper.selectOne(new LambdaQueryWrapper<JobInstance>()
                .eq(JobInstance::getBackfillBatchId, batch.getId())
                .orderByDesc(JobInstance::getCreatedAt)
                .last("limit 1"));
        if (last == null || !RunStatus.FAILED.name().equals(last.getStatus())
                || last.getFinishedAt() == null || batch.getUpdatedAt() == null || !last.getFinishedAt().isAfter(batch.getUpdatedAt())) {
            return null;
        }
        boolean unknown = attemptMapper.selectCount(new LambdaQueryWrapper<JobInstanceAttempt>()
                        .eq(JobInstanceAttempt::getInstanceId, last.getId())
                        .eq(JobInstanceAttempt::getEngineStatus, "UNKNOWABLE"))
                > 0;
        return unknown
                ? "上一次运行的引擎作业状态不明，可能还在集群上运行；确认它已经结束后点\"继续\""
                : null;
    }

    private void transition(BackfillBatch batch, Status status, String error) {
        batchMapper.update(
                null,
                new LambdaUpdateWrapper<BackfillBatch>()
                        .set(BackfillBatch::getStatus, status.name())
                        .set(BackfillBatch::getErrorMessage, error == null ? null : error.substring(0, Math.min(1024, error.length())))
                        .set(status == Status.FINISHED, BackfillBatch::getFinishedAt, LocalDateTime.now())
                        .eq(BackfillBatch::getId, batch.getId())
                        .eq(BackfillBatch::getStatus, Status.RUNNING.name()));
    }

    private BackfillBatch require(String batchId) {
        BackfillBatch batch = batchMapper.selectById(batchId);
        if (batch == null) {
            throw new ConsoleException(ErrorCodes.NOT_FOUND, "补数不存在：" + batchId);
        }
        return batch;
    }

    private BatchView view(BackfillBatch batch) {
        Map<String, Long> counts = instanceMapper
                .selectList(new LambdaQueryWrapper<JobInstance>()
                        .select(JobInstance::getStatus)
                        .eq(JobInstance::getBackfillBatchId, batch.getId()))
                .stream()
                .collect(Collectors.groupingBy(JobInstance::getStatus, Collectors.counting()));
        Integer versionNo;
        try {
            versionNo = jobService.requireVersion(batch.getJobVersionId()).getVersionNo();
        } catch (ConsoleException e) {
            versionNo = null;
        }
        return new BatchView(
                batch.getId(),
                batch.getJobDefinitionId(),
                versionNo,
                batch.getStartTime().toLocalDate(),
                batch.getEndTime().toLocalDate(),
                batch.getCron(),
                runService.startupParams(batch.getStartupParamsJson()),
                batch.getTotalRuns(),
                batch.getNextIndex(),
                counts,
                batch.getStatus(),
                batch.getErrorMessage(),
                batch.getCreatedAt(),
                batch.getFinishedAt());
    }
}
