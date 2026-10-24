package com.dataintegration.console.server.run;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.dataintegration.console.common.ConsoleException;
import com.dataintegration.console.common.ErrorCodes;
import com.dataintegration.console.server.persistence.entity.JobInstance;
import com.dataintegration.console.server.persistence.entity.JobInstanceAttempt;
import com.dataintegration.console.server.persistence.entity.JobInstanceEvent;
import com.dataintegration.console.server.persistence.entity.JobMetricSample;
import com.dataintegration.console.server.persistence.mapper.JobInstanceAttemptMapper;
import com.dataintegration.console.server.persistence.mapper.JobInstanceEventMapper;
import com.dataintegration.console.server.persistence.mapper.JobInstanceMapper;
import com.dataintegration.console.server.persistence.mapper.JobMetricSampleMapper;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Persistence for runs: instances, attempts and the timeline. Every write is its own statement, so
 * progress is visible on the instance page while a run is still going.
 */
@Component
public class RunRecorder {

    public enum Level {
        INFO,
        WARN,
        ERROR
    }

    private static final int MAX_ERROR_CHARS = 8000;

    private final JobInstanceMapper instanceMapper;
    private final JobInstanceAttemptMapper attemptMapper;
    private final JobInstanceEventMapper eventMapper;
    private final JobMetricSampleMapper sampleMapper;

    public RunRecorder(
            JobInstanceMapper instanceMapper,
            JobInstanceAttemptMapper attemptMapper,
            JobInstanceEventMapper eventMapper,
            JobMetricSampleMapper sampleMapper) {
        this.instanceMapper = instanceMapper;
        this.attemptMapper = attemptMapper;
        this.eventMapper = eventMapper;
        this.sampleMapper = sampleMapper;
    }

    public JobInstance requireInstance(String id) {
        JobInstance instance = instanceMapper.selectById(id);
        if (instance == null) {
            throw new ConsoleException(ErrorCodes.NOT_FOUND, "run instance not found: " + id);
        }
        return instance;
    }

    public void insert(JobInstance instance) {
        instanceMapper.insert(instance);
    }

    public void update(JobInstance instance) {
        instanceMapper.updateById(instance);
    }

    /**
     * Moves a PENDING instance to the state held by {@code instance} (RUNNING). Returns false when
     * the instance is no longer PENDING, e.g. it was stopped before XXL-JOB started it.
     */
    public boolean claim(JobInstance instance) {
        return instanceMapper.update(
                        instance,
                        new LambdaUpdateWrapper<JobInstance>()
                                .eq(JobInstance::getId, instance.getId())
                                .eq(JobInstance::getStatus, RunStatus.PENDING.name()))
                > 0;
    }

    /** Runs of the job that are PENDING or RUNNING. */
    public long countActive(String jobId) {
        return instanceMapper.selectCount(
                new LambdaQueryWrapper<JobInstance>()
                        .eq(JobInstance::getJobDefinitionId, jobId)
                        .in(JobInstance::getStatus, RunStatus.PENDING.name(), RunStatus.RUNNING.name()));
    }

    /** Cancels an instance that has not started yet. Returns false when it is no longer PENDING. */
    public boolean cancelPending(String instanceId, String reason) {
        return instanceMapper.update(
                        null,
                        new LambdaUpdateWrapper<JobInstance>()
                                .set(JobInstance::getStatus, RunStatus.CANCELED.name())
                                .set(JobInstance::getFinishedAt, LocalDateTime.now())
                                .set(JobInstance::getErrorMessage, clip(reason))
                                .eq(JobInstance::getId, instanceId)
                                .eq(JobInstance::getStatus, RunStatus.PENDING.name()))
                > 0;
    }

    public void insert(JobInstanceAttempt attempt) {
        attemptMapper.insert(attempt);
    }

    public void update(JobInstanceAttempt attempt) {
        attemptMapper.updateById(attempt);
    }

    public void event(String instanceId, Integer attemptNo, RunEventType type, Level level, String message) {
        JobInstanceEvent event = new JobInstanceEvent();
        event.setInstanceId(instanceId);
        event.setAttemptNo(attemptNo);
        event.setEventType(type.name());
        event.setLevel(level.name());
        event.setMessage(message == null ? "" : message);
        eventMapper.insert(event);
    }

    /**
     * Records the counters of a running attempt: on the instance, so lists show live progress, and
     * as a sample for the throughput chart. Only the counter columns are written, so a concurrent
     * stop or status change is never overwritten.
     */
    public void progress(String instanceId, int attemptNo, EngineMetrics metrics) {
        instanceMapper.update(
                null,
                new LambdaUpdateWrapper<JobInstance>()
                        .set(JobInstance::getReadRows, metrics.readRows())
                        .set(JobInstance::getWriteRows, metrics.writeRows())
                        .set(JobInstance::getReadBytes, metrics.readBytes())
                        .set(JobInstance::getWriteBytes, metrics.writeBytes())
                        .eq(JobInstance::getId, instanceId));
        JobMetricSample sample = new JobMetricSample();
        sample.setInstanceId(instanceId);
        sample.setAttemptNo(attemptNo);
        sample.setReadRows(metrics.readRows());
        sample.setWriteRows(metrics.writeRows());
        sample.setReadBytes(metrics.readBytes());
        sample.setWriteBytes(metrics.writeBytes());
        sample.setSampledAt(LocalDateTime.now());
        sampleMapper.insert(sample);
    }

    public static String clip(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= MAX_ERROR_CHARS ? text : text.substring(0, MAX_ERROR_CHARS) + "...";
    }
}
