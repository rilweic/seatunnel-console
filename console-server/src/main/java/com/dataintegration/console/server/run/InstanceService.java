package com.dataintegration.console.server.run;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataintegration.console.common.ConsoleException;
import com.dataintegration.console.common.ErrorCodes;
import com.dataintegration.console.common.PageResult;
import com.dataintegration.console.server.persistence.entity.JobDefinition;
import com.dataintegration.console.server.persistence.entity.JobInstance;
import com.dataintegration.console.server.persistence.entity.JobInstanceAttempt;
import com.dataintegration.console.server.persistence.entity.JobInstanceEvent;
import com.dataintegration.console.server.persistence.entity.JobMetricSample;
import com.dataintegration.console.server.persistence.entity.JobVersion;
import com.dataintegration.console.server.persistence.entity.Project;
import com.dataintegration.console.server.persistence.mapper.JobDefinitionMapper;
import com.dataintegration.console.server.persistence.mapper.JobInstanceAttemptMapper;
import com.dataintegration.console.server.persistence.mapper.JobInstanceEventMapper;
import com.dataintegration.console.server.persistence.mapper.JobInstanceMapper;
import com.dataintegration.console.server.persistence.mapper.JobMetricSampleMapper;
import com.dataintegration.console.server.persistence.mapper.JobVersionMapper;
import com.dataintegration.console.server.persistence.mapper.ProjectMapper;
import com.dataintegration.console.server.auth.Access;
import com.dataintegration.console.server.auth.CurrentUser;
import com.dataintegration.console.server.user.UserDirectory;
import com.dataintegration.console.server.web.Pages;

import org.springframework.stereotype.Service;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Read side of runs: list, detail with attempts and timeline, and SeaTunnel job logs. */
@Service
public class InstanceService {

    public record InstanceView(
            String id,
            String projectId,
            String projectName,
            String jobDefinitionId,
            String jobName,
            boolean jobDeleted,
            Integer versionNo,
            String triggerType,
            String status,
            int attemptCount,
            LocalDateTime scheduledTime,
            LocalDateTime startedAt,
            LocalDateTime finishedAt,
            Long durationMillis,
            Long readRows,
            Long writeRows,
            String errorMessage,
            String rerunOfInstanceId,
            String backfillBatchId,
            String validationStatus,
            Long sourceCount,
            Long targetCount,
            String validationMessage,
            String createdByName,
            boolean editable,
            LocalDateTime createdAt) {}

    public record AttemptView(
            int attemptNo,
            String engineJobId,
            String engineStatus,
            String errorMessage,
            boolean logArchived,
            Long logSizeBytes,
            boolean logTruncated,
            LocalDateTime submittedAt,
            LocalDateTime finishedAt) {}

    public record EventView(Integer attemptNo, String type, String level, String message, LocalDateTime time) {}

    public record InstanceDetail(
            InstanceView instance,
            String configMasked,
            String variablesJson,
            String startupParamsJson,
            List<AttemptView> attempts,
            List<EventView> events) {}

    /**
     * @param source LIVE (read from the engine now), ARCHIVED (from the console's archive) or
     *     UNAVAILABLE (with {@code message} saying why)
     */
    public record LogView(String source, String content, boolean truncated, String message) {}

    /** Engine counters at one poll; rates are derived from consecutive samples. */
    public record SampleView(LocalDateTime time, Long readRows, Long writeRows, Long readBytes, Long writeBytes) {}

    /**
     * Engine metrics of one attempt: the latest snapshot (live while running, final afterwards) and
     * the samples taken while it ran.
     *
     * @param attemptNo null when the run has no attempt yet
     */
    public record RunMetrics(Integer attemptNo, EngineMetrics metrics, List<SampleView> samples) {}

    public record InstanceFeed(
            List<InstanceView> items, boolean hasMore, LocalDateTime nextCursorTime, String nextCursorId) {}

    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {};

    private final JobInstanceMapper instanceMapper;
    private final JobInstanceAttemptMapper attemptMapper;
    private final JobInstanceEventMapper eventMapper;
    private final JobDefinitionMapper jobMapper;
    private final ProjectMapper projectMapper;
    private final JobVersionMapper versionMapper;
    private final EngineLogService engineLogService;
    private final LogStorage logStorage;
    private final UserDirectory users;
    private final JobMetricSampleMapper sampleMapper;
    private final ObjectMapper objectMapper;

    public InstanceService(
            JobInstanceMapper instanceMapper,
            JobInstanceAttemptMapper attemptMapper,
            JobInstanceEventMapper eventMapper,
            JobDefinitionMapper jobMapper,
            ProjectMapper projectMapper,
            JobVersionMapper versionMapper,
            EngineLogService engineLogService,
            LogStorage logStorage,
            UserDirectory users,
            JobMetricSampleMapper sampleMapper,
            ObjectMapper objectMapper) {
        this.instanceMapper = instanceMapper;
        this.attemptMapper = attemptMapper;
        this.eventMapper = eventMapper;
        this.jobMapper = jobMapper;
        this.projectMapper = projectMapper;
        this.versionMapper = versionMapper;
        this.engineLogService = engineLogService;
        this.logStorage = logStorage;
        this.users = users;
        this.sampleMapper = sampleMapper;
        this.objectMapper = objectMapper;
    }

    /** @param mine only runs of the current user's jobs, whoever or whatever triggered them */
    public PageResult<InstanceView> list(
            String projectId, String jobId, String jobName, String status, String backfillBatchId,
            LocalDateTime startedFrom, LocalDateTime startedTo, boolean mine, Integer page, Integer size) {
        Page<JobInstance> result = instanceMapper.selectPage(
                Pages.of(page, size),
                filters(projectId, jobId, jobName, status, backfillBatchId, startedFrom, startedTo, mine)
                        .select(JobInstance.class, f -> !f.getColumn().equals("config_masked"))
                        .orderByDesc(JobInstance::getCreatedAt));
        Map<String, String> projectNames = projectNames(result.getRecords());
        Map<String, JobDefinition> jobs = jobs(result.getRecords());
        Map<String, Integer> versionNos = versionNos(result.getRecords());
        Map<String, String> userNames = users.names(result.getRecords().stream().map(JobInstance::getCreatedBy).toList());
        return Pages.result(result).map(i -> view(i, projectNames, jobs, versionNos, userNames));
    }

    public InstanceFeed feed(
            String projectId, String jobId, String jobName, String status, String backfillBatchId,
            LocalDateTime startedFrom, LocalDateTime startedTo, boolean mine,
            LocalDateTime cursorTime, String cursorId, Integer size) {
        int limit = size == null || size < 1 ? 20 : Math.min(size, Pages.MAX_SIZE);
        List<JobInstance> records = instanceMapper.selectList(
                filters(projectId, jobId, jobName, status, backfillBatchId, startedFrom, startedTo, mine)
                        .select(JobInstance.class, f -> !f.getColumn().equals("config_masked"))
                        .and(cursorTime != null && blank(cursorId), w -> w
                                .lt(JobInstance::getCreatedAt, cursorTime)
                                .or(sameTime -> sameTime
                                        .eq(JobInstance::getCreatedAt, cursorTime)
                                        .lt(JobInstance::getId, cursorId)))
                        .orderByDesc(JobInstance::getCreatedAt)
                        .orderByDesc(JobInstance::getId)
                        .last("limit " + (limit + 1)));
        boolean hasMore = records.size() > limit;
        List<JobInstance> page = records.stream().limit(limit).toList();
        JobInstance last = page.isEmpty() ? null : page.get(page.size() - 1);
        return new InstanceFeed(views(page), hasMore, hasMore ? last.getCreatedAt() : null, hasMore ? last.getId() : null);
    }

    public long count(
            String projectId, String jobId, String jobName, String status, String backfillBatchId,
            LocalDateTime startedFrom, LocalDateTime startedTo, boolean mine) {
        return instanceMapper.selectCount(filters(projectId, jobId, jobName, status, backfillBatchId, startedFrom, startedTo, mine));
    }

    public InstanceDetail detail(String id) {
        JobInstance instance = require(id);
        List<AttemptView> attempts = attempts(id).stream().map(InstanceService::attemptView).toList();
        List<EventView> events =
                eventMapper
                        .selectList(new LambdaQueryWrapper<JobInstanceEvent>()
                                .eq(JobInstanceEvent::getInstanceId, id)
                                .orderByAsc(JobInstanceEvent::getCreatedAt))
                        .stream()
                        .map(e -> new EventView(e.getAttemptNo(), e.getEventType(), e.getLevel(), e.getMessage(), e.getCreatedAt()))
                        .toList();
        List<JobInstance> one = List.of(instance);
        return new InstanceDetail(
                view(instance, projectNames(one), jobs(one), versionNos(one), users.names(Collections.singletonList(instance.getCreatedBy()))),
                instance.getConfigMasked(),
                instance.getVariablesJson(),
                instance.getStartupParamsJson(),
                attempts,
                events);
    }

    /** Views of instances already loaded, with project/job names, version numbers and trigger users. */
    public List<InstanceView> views(List<JobInstance> instances) {
        Map<String, String> projectNames = projectNames(instances);
        Map<String, JobDefinition> jobs = jobs(instances);
        Map<String, Integer> versionNos = versionNos(instances);
        Map<String, String> userNames = users.names(instances.stream().map(JobInstance::getCreatedBy).toList());
        return instances.stream().map(i -> view(i, projectNames, jobs, versionNos, userNames)).toList();
    }

    /**
     * @param attemptNo null for the latest attempt
     * @param withSamples false to skip the samples, when only the snapshot is needed
     */
    public RunMetrics metrics(String instanceId, Integer attemptNo, boolean withSamples) {
        require(instanceId);
        List<JobInstanceAttempt> attempts = attempts(instanceId);
        JobInstanceAttempt attempt = attemptNo == null
                ? (attempts.isEmpty() ? null : attempts.get(attempts.size() - 1))
                : attempts.stream().filter(a -> a.getAttemptNo() == attemptNo.intValue()).findFirst().orElse(null);
        if (attempt == null) {
            return new RunMetrics(null, EngineMetrics.EMPTY, List.of());
        }
        EngineMetrics metrics = EngineMetrics.EMPTY;
        if (attempt.getMetricsJson() != null) {
            try {
                metrics = EngineMetrics.parse(objectMapper.readValue(attempt.getMetricsJson(), STRING_MAP), objectMapper);
            } catch (JacksonException e) {
                // Unreadable snapshot; show the run without metrics.
            }
        }
        List<SampleView> samples = !withSamples
                ? List.of()
                : sampleMapper
                        .selectList(new LambdaQueryWrapper<JobMetricSample>()
                                .eq(JobMetricSample::getInstanceId, instanceId)
                                .eq(JobMetricSample::getAttemptNo, attempt.getAttemptNo())
                                .orderByAsc(JobMetricSample::getSampledAt))
                        .stream()
                        .map(m -> new SampleView(m.getSampledAt(), m.getReadRows(), m.getWriteRows(), m.getReadBytes(), m.getWriteBytes()))
                        .toList();
        return new RunMetrics(attempt.getAttemptNo(), metrics, samples);
    }

    /**
     * The SeaTunnel log of one attempt: from the archive once written, otherwise live from the
     * engine (which keeps per-job logs for history-job-expire-minutes, one day by default).
     */
    public LogView log(String instanceId, int attemptNo) {
        require(instanceId);
        JobInstanceAttempt attempt =
                attempts(instanceId).stream()
                        .filter(a -> a.getAttemptNo() == attemptNo)
                        .findFirst()
                        .orElseThrow(() -> new ConsoleException(ErrorCodes.NOT_FOUND, "attempt " + attemptNo + " not found"));
        if (attempt.getLogStorageKey() != null) {
            return logStorage.read(attempt.getLogStorageKey())
                    .map(text -> new LogView("ARCHIVED", text, Boolean.TRUE.equals(attempt.getLogTruncated()), null))
                    .orElse(new LogView("UNAVAILABLE", "", false, "archived log was removed by retention"));
        }
        if (attempt.getEngineJobId() == null) {
            String reason = attempt.getFinishedAt() != null
                    ? "SeaTunnel rejected this attempt at submission, so no job log was created. "
                            + "The root cause is in logs/seatunnel-engine-server.log on the SeaTunnel master node."
                    : "the job has not been submitted to SeaTunnel yet";
            return new LogView("UNAVAILABLE", "", false, reason);
        }
        try {
            EngineLogService.CollectedLog live = engineLogService.collect(Long.parseLong(attempt.getEngineJobId()));
            if (live.fileCount() == 0) {
                return new LogView("UNAVAILABLE", "", false,
                        "SeaTunnel has no per-job log for engine job " + attempt.getEngineJobId()
                                + "; enable routingAppender in config/log4j2.properties on every node, or the log has expired");
            }
            return new LogView("LIVE", live.text(), live.truncated(), null);
        } catch (ConsoleException e) {
            return new LogView("UNAVAILABLE", "", false, "cannot read the log from SeaTunnel: " + e.getMessage());
        }
    }

    private JobInstance require(String id) {
        JobInstance instance = instanceMapper.selectById(id);
        if (instance == null) {
            throw new ConsoleException(ErrorCodes.NOT_FOUND, "run instance not found: " + id);
        }
        return instance;
    }

    private List<JobInstanceAttempt> attempts(String instanceId) {
        return attemptMapper.selectList(new LambdaQueryWrapper<JobInstanceAttempt>()
                .eq(JobInstanceAttempt::getInstanceId, instanceId)
                .orderByAsc(JobInstanceAttempt::getAttemptNo));
    }

    private static LambdaQueryWrapper<JobInstance> filters(
            String projectId, String jobId, String jobName, String status, String backfillBatchId,
            LocalDateTime startedFrom, LocalDateTime startedTo, boolean mine) {
        return new LambdaQueryWrapper<JobInstance>()
                .eq(blank(projectId), JobInstance::getProjectId, projectId)
                .eq(blank(jobId), JobInstance::getJobDefinitionId, jobId)
                .apply(blank(jobName), "job_definition_id in (select id from job_definition where name like {0})",
                        "%" + Pages.literal(jobName) + "%")
                .eq(blank(status), JobInstance::getStatus, status)
                .eq(blank(backfillBatchId), JobInstance::getBackfillBatchId, backfillBatchId)
                .and(startedFrom != null, w -> w
                        .and(started -> started.isNotNull(JobInstance::getStartedAt).ge(JobInstance::getStartedAt, startedFrom))
                        .or(pending -> pending.isNull(JobInstance::getStartedAt).ge(JobInstance::getCreatedAt, startedFrom)))
                .and(startedTo != null, w -> w
                        .and(started -> started.isNotNull(JobInstance::getStartedAt).lt(JobInstance::getStartedAt, startedTo))
                        .or(pending -> pending.isNull(JobInstance::getStartedAt).lt(JobInstance::getCreatedAt, startedTo)))
                .inSql(mine, JobInstance::getJobDefinitionId,
                        "select id from job_definition where created_by = " + quote(CurrentUser.idOrNull()));
    }

    /** User ids are UUIDs, never user input, but quote them safely anyway for the inSql fragment. */
    private static String quote(String id) {
        return id == null ? "null" : "'" + id.replace("'", "''") + "'";
    }

    private Map<String, String> projectNames(List<JobInstance> instances) {
        List<String> ids = instances.stream().map(JobInstance::getProjectId).distinct().toList();
        if (ids.isEmpty()) {
            return new HashMap<>();
        }
        // Runs keep their original project even when the job moves or the project is deleted.
        return projectMapper.selectNamesIncludingDeleted(ids).stream()
                .collect(Collectors.toMap(Project::getId, Project::getName));
    }

    private Map<String, JobDefinition> jobs(List<JobInstance> instances) {
        List<String> ids = instances.stream().map(JobInstance::getJobDefinitionId).distinct().toList();
        if (ids.isEmpty()) {
            return new HashMap<>();
        }
        // Deleted jobs keep their runs; the logic-delete filter would hide their names, so select directly.
        return jobMapper.selectNamesIncludingDeleted(ids).stream()
                .collect(Collectors.toMap(JobDefinition::getId, j -> j));
    }

    private Map<String, Integer> versionNos(List<JobInstance> instances) {
        List<String> ids = instances.stream().map(JobInstance::getJobVersionId).distinct().toList();
        if (ids.isEmpty()) {
            return new HashMap<>();
        }
        return versionMapper.selectByIds(ids).stream().collect(Collectors.toMap(JobVersion::getId, JobVersion::getVersionNo));
    }

    private static InstanceView view(
            JobInstance i, Map<String, String> projectNames, Map<String, JobDefinition> jobs,
            Map<String, Integer> versionNos, Map<String, String> userNames) {
        JobDefinition job = jobs.get(i.getJobDefinitionId());
        Long duration = i.getStartedAt() == null
                ? null
                : Duration.between(i.getStartedAt(), i.getFinishedAt() == null ? LocalDateTime.now() : i.getFinishedAt()).toMillis();
        return new InstanceView(
                i.getId(),
                i.getProjectId(),
                projectNames.get(i.getProjectId()),
                i.getJobDefinitionId(),
                job == null ? null : job.getName(),
                job == null || job.getDeletedAt() != null,
                versionNos.get(i.getJobVersionId()),
                i.getTriggerType(),
                i.getStatus(),
                i.getAttemptCount() == null ? 0 : i.getAttemptCount(),
                i.getScheduledTime(),
                i.getStartedAt(),
                i.getFinishedAt(),
                duration,
                i.getReadRows(),
                i.getWriteRows(),
                i.getErrorMessage(),
                i.getRerunOfInstanceId(),
                i.getBackfillBatchId(),
                i.getValidationStatus(),
                i.getSourceCount(),
                i.getTargetCount(),
                i.getValidationMessage(),
                userNames.get(i.getCreatedBy()),
                job != null && Access.canEdit(job.getCreatedBy()),
                i.getCreatedAt());
    }

    private static AttemptView attemptView(JobInstanceAttempt a) {
        return new AttemptView(
                a.getAttemptNo(),
                a.getEngineJobId(),
                a.getEngineStatus(),
                a.getErrorMessage(),
                a.getLogStorageKey() != null,
                a.getLogSizeBytes(),
                Boolean.TRUE.equals(a.getLogTruncated()),
                a.getSubmittedAt(),
                a.getFinishedAt());
    }

    private static boolean blank(String value) {
        return value != null && !value.isBlank();
    }
}
