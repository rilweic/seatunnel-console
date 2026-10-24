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
        JobDefinition job = jobService.require(jobId);
        XxlJobAdminClient client = requireClient();
        CronSchedule.parse(command.cron());
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
        return CronSchedule.nextFireTimes(cron, LocalDateTime.now(), PREVIEW_COUNT).stream()
                .map(PREVIEW_FORMAT::format)
                .toList();
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
            return jobService.require(job.getId()).getXxlJobId();
        }
        return created;
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
            client.updateJob(xxlJobId, xxlJobName(job), spec, executorParam(job));
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
