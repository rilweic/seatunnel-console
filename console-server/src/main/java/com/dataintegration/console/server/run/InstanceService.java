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
import com.dataintegration.console.server.persistence.entity.JobVersion;
import com.dataintegration.console.server.persistence.mapper.JobDefinitionMapper;
import com.dataintegration.console.server.persistence.mapper.JobInstanceAttemptMapper;
import com.dataintegration.console.server.persistence.mapper.JobInstanceEventMapper;
import com.dataintegration.console.server.persistence.mapper.JobInstanceMapper;
import com.dataintegration.console.server.persistence.mapper.JobVersionMapper;
import com.dataintegration.console.server.web.Pages;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Read side of runs: list, detail with attempts and timeline, and SeaTunnel job logs. */
@Service
public class InstanceService {

    public record InstanceView(
            String id,
            String projectId,
            String jobDefinitionId,
            String jobName,
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

    private final JobInstanceMapper instanceMapper;
    private final JobInstanceAttemptMapper attemptMapper;
    private final JobInstanceEventMapper eventMapper;
    private final JobDefinitionMapper jobMapper;
    private final JobVersionMapper versionMapper;
    private final EngineLogService engineLogService;
    private final LogStorage logStorage;

    public InstanceService(
            JobInstanceMapper instanceMapper,
            JobInstanceAttemptMapper attemptMapper,
            JobInstanceEventMapper eventMapper,
            JobDefinitionMapper jobMapper,
            JobVersionMapper versionMapper,
            EngineLogService engineLogService,
            LogStorage logStorage) {
        this.instanceMapper = instanceMapper;
        this.attemptMapper = attemptMapper;
        this.eventMapper = eventMapper;
        this.jobMapper = jobMapper;
        this.versionMapper = versionMapper;
        this.engineLogService = engineLogService;
        this.logStorage = logStorage;
    }

    public PageResult<InstanceView> list(
            String projectId, String jobId, String status, String backfillBatchId, Integer page, Integer size) {
        Page<JobInstance> result =
                instanceMapper.selectPage(
                        Pages.of(page, size),
                        new LambdaQueryWrapper<JobInstance>()
                                .select(JobInstance.class, f -> !f.getColumn().equals("config_masked"))
                                .eq(blank(projectId), JobInstance::getProjectId, projectId)
                                .eq(blank(jobId), JobInstance::getJobDefinitionId, jobId)
                                .eq(blank(status), JobInstance::getStatus, status)
                                .eq(blank(backfillBatchId), JobInstance::getBackfillBatchId, backfillBatchId)
                                .orderByDesc(JobInstance::getCreatedAt));
        Map<String, String> jobNames = jobNames(result.getRecords());
        Map<String, Integer> versionNos = versionNos(result.getRecords());
        return Pages.result(result).map(i -> view(i, jobNames, versionNos));
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
                view(instance, jobNames(one), versionNos(one)), instance.getConfigMasked(),
                instance.getVariablesJson(),
                instance.getStartupParamsJson(),
                attempts,
                events);
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

    private Map<String, String> jobNames(List<JobInstance> instances) {
        List<String> ids = instances.stream().map(JobInstance::getJobDefinitionId).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        // Deleted jobs keep their runs; the logic-delete filter would hide their names, so select directly.
        return jobMapper.selectNamesIncludingDeleted(ids).stream()
                .collect(Collectors.toMap(JobDefinition::getId, JobDefinition::getName));
    }

    private Map<String, Integer> versionNos(List<JobInstance> instances) {
        List<String> ids = instances.stream().map(JobInstance::getJobVersionId).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return versionMapper.selectByIds(ids).stream().collect(Collectors.toMap(JobVersion::getId, JobVersion::getVersionNo));
    }

    private static InstanceView view(JobInstance i, Map<String, String> jobNames, Map<String, Integer> versionNos) {
        Long duration = i.getStartedAt() == null
                ? null
                : Duration.between(i.getStartedAt(), i.getFinishedAt() == null ? LocalDateTime.now() : i.getFinishedAt()).toMillis();
        return new InstanceView(
                i.getId(),
                i.getProjectId(),
                i.getJobDefinitionId(),
                jobNames.get(i.getJobDefinitionId()),
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
