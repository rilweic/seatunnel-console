package com.dataintegration.console.server.job;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.dataintegration.console.common.ConsoleException;
import com.dataintegration.console.common.ErrorCodes;
import com.dataintegration.console.server.auth.CurrentUser;
import com.dataintegration.console.server.persistence.entity.JobDefinition;
import com.dataintegration.console.server.persistence.entity.JobSchedule;
import com.dataintegration.console.server.persistence.mapper.JobDefinitionMapper;
import com.dataintegration.console.server.persistence.mapper.JobScheduleMapper;
import com.dataintegration.console.server.scheduler.ScheduleSpec;
import com.dataintegration.console.server.scheduler.TriggerParam;
import com.dataintegration.console.server.scheduler.XxlJobAdminClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Batch job schedules (docs/design.md §5.2). The console row is the source of truth; every save is
 * mirrored onto the job's single XXL-JOB job, which is switched to CRON and started or stopped.
 * A failed sync is kept on the row (sync_status FAILED) and fixed by saving again.
 */
@Service
public class ScheduleService {

    private static final Logger log = LoggerFactory.getLogger(ScheduleService.class);

    static final int PREVIEW_COUNT = 5;

    private static final DateTimeFormatter PREVIEW_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    public record ScheduleView(
            String jobDefinitionId,
            String cron,
            boolean enabled,
            int failRetryCount,
            int retryIntervalSeconds,
            int timeoutSeconds,
            String blockStrategy,
            String misfireStrategy,
            Integer xxlJobId,
            String syncStatus,
            String syncError,
            String syncedAt,
            List<String> nextFireTimes) {}

    public record ScheduleCommand(
            String cron,
            boolean enabled,
            int failRetryCount,
            int retryIntervalSeconds,
            int timeoutSeconds,
            ScheduleSpec.BlockStrategy blockStrategy,
            ScheduleSpec.MisfireStrategy misfireStrategy) {}

    private final JobService jobService;
    private final JobDefinitionMapper jobMapper;
    private final JobScheduleMapper scheduleMapper;
    private final ObjectProvider<XxlJobAdminClient> xxlJobAdminClient;
    private final ObjectMapper objectMapper;

    public ScheduleService(
            JobService jobService,
            JobDefinitionMapper jobMapper,
            JobScheduleMapper scheduleMapper,
            ObjectProvider<XxlJobAdminClient> xxlJobAdminClient,
            ObjectMapper objectMapper) {
        this.jobService = jobService;
        this.jobMapper = jobMapper;
        this.scheduleMapper = scheduleMapper;
        this.xxlJobAdminClient = xxlJobAdminClient;
        this.objectMapper = objectMapper;
    }

    /** Returns null when the job has no schedule yet. */
    public ScheduleView get(String jobId) {
        JobDefinition job = jobService.require(jobId);
        JobSchedule schedule = scheduleMapper.selectById(jobId);
        return schedule == null ? null : view(schedule, job);
    }

    public ScheduleView save(String jobId, ScheduleCommand command) {
        JobDefinition job = jobService.requireEditable(jobId);
        XxlJobAdminClient client = requireClient();
        CronSchedule.parseSchedule(command.cron(), LocalDateTime.now());
        if (command.enabled() && job.getCurrentVersionId() == null) {
            throw new ConsoleException(ErrorCodes.INVALID_ARGUMENT, "save a version of the job before enabling its schedule");
        }
        if (command.failRetryCount() < 0 || command.retryIntervalSeconds() < 0 || command.timeoutSeconds() < 0) {
            throw new ConsoleException(ErrorCodes.INVALID_ARGUMENT, "retry count, retry interval and timeout must not be negative");
        }

        JobSchedule schedule = scheduleMapper.selectById(jobId);
        boolean created = schedule == null;
        if (created) {
            schedule = new JobSchedule();
            schedule.setJobDefinitionId(jobId);
            schedule.setCreatedBy(CurrentUser.idOrNull());
        }
        schedule.setCron(command.cron().trim());
        schedule.setEnabled(command.enabled());
        schedule.setFailRetryCount(command.failRetryCount());
        schedule.setRetryIntervalSeconds(command.retryIntervalSeconds());
        schedule.setTimeoutSeconds(command.timeoutSeconds());
        schedule.setBlockStrategy(blockStrategy(command).name());
        schedule.setMisfireStrategy(misfireStrategy(command).name());
        schedule.setSyncStatus("PENDING");
        schedule.setSyncError(null);
        if (created) {
            scheduleMapper.insert(schedule);
        } else {
            scheduleMapper.updateById(schedule);
        }

        sync(job, schedule, client);
        scheduleMapper.updateById(schedule);
        return view(schedule, jobService.require(jobId));
    }

    public List<String> preview(String cron) {
        CronSchedule.parseSchedule(cron, LocalDateTime.now());
        return CronSchedule.nextFireTimes(cron, LocalDateTime.now(), PREVIEW_COUNT).stream()
                .map(PREVIEW_FORMAT::format)
                .toList();
    }

    /** The next {@code count} fire times of a cron after {@code from}. */
    public static List<LocalDateTime> nextFireTimes(String cron, LocalDateTime from, int count) {
        return CronSchedule.nextFireTimes(cron, from, count);
    }

    /** The cron of the job's schedule, enabled or not; null when it has none. */
    public String cronOf(String jobId) {
        JobSchedule schedule = scheduleMapper.selectById(jobId);
        return schedule == null ? null : schedule.getCron();
    }

    /** Retries configured on the job's schedule; 0 when it has none. */
    public int failRetryCount(String jobId) {
        JobSchedule schedule = scheduleMapper.selectById(jobId);
        return schedule == null || schedule.getFailRetryCount() == null ? 0 : Math.max(0, schedule.getFailRetryCount());
    }

    /**
     * Scheduled times of a backfill over the days {@code start..end} (both inclusive): every fire
     * time of {@code cron} in that range, like a DolphinScheduler complement; one run per day at
     * 00:00:00 when the job has no cron.
     */
    public static List<LocalDateTime> backfillTimes(String cron, LocalDate start, LocalDate end, int max) {
        if (end.isBefore(start)) {
            throw new ConsoleException(ErrorCodes.INVALID_ARGUMENT, "end date is before start date");
        }
        LocalDateTime until = end.plusDays(1).atStartOfDay();
        List<LocalDateTime> times = new ArrayList<>();
        if (cron == null) {
            for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
                times.add(day.atStartOfDay());
                if (times.size() > max) {
                    break;
                }
            }
        } else {
            var expression = CronSchedule.parse(cron);
            LocalDateTime next = start.atStartOfDay().minusNanos(1);
            while ((next = expression.next(next)) != null && next.isBefore(until) && times.size() <= max) {
                times.add(next);
            }
        }
        if (times.size() > max) {
            throw new ConsoleException(ErrorCodes.INVALID_ARGUMENT, "a backfill may have at most " + max + " runs; narrow the date range");
        }
        if (times.isEmpty()) {
            throw new ConsoleException(ErrorCodes.INVALID_ARGUMENT, "the schedule never fires in this date range");
        }
        return times;
    }

    /**
     * Every batch job owns one XXL-JOB job, created on first use with schedule type NONE. A
     * conditional update keeps concurrent first uses from attaching two XXL jobs.
     */
    public int ensureXxlJob(JobDefinition job, XxlJobAdminClient client) {
        if (job.getXxlJobId() != null) {
            return job.getXxlJobId();
        }
        int created = client.addTriggerOnlyJob(xxlJobName(job), executorParam(job));
        int updated = jobMapper.update(
                null,
                new LambdaUpdateWrapper<JobDefinition>()
                        .set(JobDefinition::getXxlJobId, created)
                        .eq(JobDefinition::getId, job.getId())
                        .isNull(JobDefinition::getXxlJobId));
        if (updated == 0) {
            client.removeJob(created);
            int existing = jobService.require(job.getId()).getXxlJobId();
            job.setXxlJobId(existing);
            return existing;
        }
        job.setXxlJobId(created);
        return created;
    }

    /** What one reconciliation of a job did. */
    public enum Reconciled {
        UNCHANGED,
        RECREATED,
        FORGOTTEN,
        FAILED
    }

    /**
     * Makes XXL-JOB match the console for one job (docs/design.md §5.2): a scheduled job gets its
     * schedule pushed again (recreating its XXL-JOB job if it was deleted there); an unscheduled
     * job is only checked for existence, and a missing one is forgotten and recreated on its next
     * run. XXL-JOB offers no read API, so pushing the console's state is the comparison.
     */
    public Reconciled reconcile(JobDefinition job, XxlJobAdminClient client) {
        JobSchedule schedule = scheduleMapper.selectById(job.getId());
        if (schedule != null) {
            Integer before = job.getXxlJobId();
            schedule = syncAndRecord(job, schedule, client);
            if (schedule == null) {
                return Reconciled.UNCHANGED;
            }
            if (!"SYNCED".equals(schedule.getSyncStatus())) {
                return Reconciled.FAILED;
            }
            return before != null && !before.equals(job.getXxlJobId()) ? Reconciled.RECREATED : Reconciled.UNCHANGED;
        }
        if (job.getXxlJobId() == null) {
            return Reconciled.UNCHANGED;
        }
        try {
            // Unscheduled jobs are stopped anyway; stopping again is a harmless existence check.
            client.stopJob(job.getXxlJobId());
            return Reconciled.UNCHANGED;
        } catch (ConsoleException e) {
            if (e.getCode() != ErrorCodes.SCHEDULER_JOB_MISSING) {
                throw e;
            }
            jobMapper.clearXxlJobId(job.getId(), job.getXxlJobId());
            return Reconciled.FORGOTTEN;
        }
    }

    /**
     * Replaces a job's XXL-JOB job that was deleted in xxl-job-admin, restoring its schedule if it
     * has one. Returns the new XXL-JOB job id.
     */
    public int recreateXxlJob(JobDefinition job, XxlJobAdminClient client) {
        if (job.getXxlJobId() != null) {
            jobMapper.clearXxlJobId(job.getId(), job.getXxlJobId());
            job.setXxlJobId(null);
        }
        JobSchedule schedule = scheduleMapper.selectById(job.getId());
        if (schedule != null) {
            syncAndRecord(job, schedule, client);
        }
        return ensureXxlJob(job, client);
    }

    /**
     * Pushes a schedule read in the background and records only the sync result, and only if the
     * schedule was not saved meanwhile: a user's save must never be overwritten with what was read
     * before it. When it was saved meanwhile, XXL-JOB may now hold the older values, so the new
     * schedule is pushed once more. Returns the schedule as recorded, null if it was deleted.
     */
    private JobSchedule syncAndRecord(JobDefinition job, JobSchedule schedule, XxlJobAdminClient client) {
        for (int round = 0; round < 2 && schedule != null; round++) {
            LocalDateTime readAt = schedule.getUpdatedAt();
            sync(job, schedule, client);
            boolean recorded = scheduleMapper.update(
                            null,
                            new LambdaUpdateWrapper<JobSchedule>()
                                    .set(JobSchedule::getSyncStatus, schedule.getSyncStatus())
                                    .set(JobSchedule::getSyncError, schedule.getSyncError())
                                    .set(JobSchedule::getSyncedAt, schedule.getSyncedAt())
                                    .eq(JobSchedule::getJobDefinitionId, schedule.getJobDefinitionId())
                                    .eq(JobSchedule::getUpdatedAt, readAt))
                    > 0;
            if (recorded) {
                return schedule;
            }
            schedule = scheduleMapper.selectById(job.getId());
        }
        return schedule;
    }

    private void sync(JobDefinition job, JobSchedule schedule, XxlJobAdminClient client) {
        try {
            int xxlJobId = ensureXxlJob(job, client);
            ScheduleSpec spec = new ScheduleSpec(
                    schedule.getCron(),
                    schedule.getFailRetryCount(),
                    schedule.getTimeoutSeconds(),
                    ScheduleSpec.BlockStrategy.valueOf(schedule.getBlockStrategy()),
                    ScheduleSpec.MisfireStrategy.valueOf(schedule.getMisfireStrategy()));
            try {
                client.updateJob(xxlJobId, xxlJobName(job), spec, executorParam(job));
            } catch (ConsoleException e) {
                if (e.getCode() != ErrorCodes.SCHEDULER_JOB_MISSING) {
                    throw e;
                }
                // Deleted in xxl-job-admin: create a new one and push the schedule onto it.
                jobMapper.clearXxlJobId(job.getId(), xxlJobId);
                job.setXxlJobId(null);
                xxlJobId = ensureXxlJob(job, client);
                client.updateJob(xxlJobId, xxlJobName(job), spec, executorParam(job));
            }
            if (Boolean.TRUE.equals(schedule.getEnabled())) {
                client.startJob(xxlJobId);
            } else {
                client.stopJob(xxlJobId);
            }
            schedule.setSyncStatus("SYNCED");
            schedule.setSyncedAt(LocalDateTime.now());
        } catch (ConsoleException e) {
            log.warn("Schedule of job {} saved but not synced to XXL-JOB: {}", job.getId(), e.getMessage());
            schedule.setSyncStatus("FAILED");
            schedule.setSyncError(e.getMessage() == null ? null : e.getMessage().substring(0, Math.min(1024, e.getMessage().length())));
        }
    }

    private String executorParam(JobDefinition job) {
        return TriggerParam.forSchedule(job.getId()).toJson(objectMapper);
    }

    private static String xxlJobName(JobDefinition job) {
        return "[console] " + job.getName() + " (" + job.getId() + ")";
    }

    private XxlJobAdminClient requireClient() {
        XxlJobAdminClient client = xxlJobAdminClient.getIfAvailable();
        if (client == null) {
            throw new ConsoleException(
                    ErrorCodes.SCHEDULER_DISABLED, "XXL-JOB is disabled (console.xxl-job.enabled = false), schedules cannot run");
        }
        return client;
    }

    private static ScheduleSpec.BlockStrategy blockStrategy(ScheduleCommand command) {
        return command.blockStrategy() == null ? ScheduleSpec.BlockStrategy.SERIAL_EXECUTION : command.blockStrategy();
    }

    private static ScheduleSpec.MisfireStrategy misfireStrategy(ScheduleCommand command) {
        return command.misfireStrategy() == null ? ScheduleSpec.MisfireStrategy.DO_NOTHING : command.misfireStrategy();
    }

    private ScheduleView view(JobSchedule schedule, JobDefinition job) {
        boolean enabled = Boolean.TRUE.equals(schedule.getEnabled());
        List<String> next = List.of();
        if (enabled) {
            try {
                next = preview(schedule.getCron());
            } catch (ConsoleException e) {
                // A cron saved under older rules; the view still loads so it can be fixed.
            }
        }
        return new ScheduleView(
                schedule.getJobDefinitionId(),
                schedule.getCron(),
                enabled,
                schedule.getFailRetryCount(),
                schedule.getRetryIntervalSeconds(),
                schedule.getTimeoutSeconds(),
                schedule.getBlockStrategy(),
                schedule.getMisfireStrategy(),
                job.getXxlJobId(),
                schedule.getSyncStatus(),
                schedule.getSyncError(),
                schedule.getSyncedAt() == null ? null : schedule.getSyncedAt().toString(),
                next);
    }
}
