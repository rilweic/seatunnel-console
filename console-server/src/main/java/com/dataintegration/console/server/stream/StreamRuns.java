package com.dataintegration.console.server.stream;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.dataintegration.console.server.persistence.entity.JobDefinition;
import com.dataintegration.console.server.persistence.entity.JobInstance;
import com.dataintegration.console.server.persistence.entity.JobInstanceAttempt;
import com.dataintegration.console.server.persistence.entity.JobInstanceEvent;
import com.dataintegration.console.server.persistence.entity.JobStream;
import com.dataintegration.console.server.persistence.entity.JobVersion;
import com.dataintegration.console.server.persistence.mapper.JobInstanceAttemptMapper;
import com.dataintegration.console.server.persistence.mapper.JobInstanceEventMapper;
import com.dataintegration.console.server.persistence.mapper.JobInstanceMapper;
import com.dataintegration.console.server.persistence.mapper.JobStreamMapper;
import com.dataintegration.console.server.run.RunEventType;
import com.dataintegration.console.server.run.RunRecorder;
import com.dataintegration.console.server.run.RunRecorder.Level;
import com.dataintegration.console.server.run.RunStatus;
import com.dataintegration.console.server.stream.RestorePlan.Kind;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The records of streaming runs (docs/design.md §12 P2): a start is a job_instance, each restart
 * inside it an attempt, and job_stream points at the run that is going. Taking and releasing that
 * pointer are conditional updates, so a start and the monitor never both own a run.
 */
@Component
public class StreamRuns {

    /**
     * Where the next start can restore from, left by a run that ended; null keeps the previous one
     * (a run that never reached the engine).
     *
     * @param blocked why restoring would fail again; the next start is then fresh
     */
    public record RestorePoint(Kind kind, long engineJobId, String versionId, String blocked) {}

    private final RunRecorder recorder;
    private final JobStreamMapper streams;
    private final JobInstanceAttemptMapper attempts;
    private final JobInstanceMapper instances;
    private final JobInstanceEventMapper events;

    public StreamRuns(RunRecorder recorder, JobStreamMapper streams, JobInstanceAttemptMapper attempts, JobInstanceMapper instances,
            JobInstanceEventMapper events) {
        this.events = events;
        this.recorder = recorder;
        this.streams = streams;
        this.attempts = attempts;
        this.instances = instances;
    }

    public RunRecorder recorder() {
        return recorder;
    }

    /** A new RUNNING run of the job's current version, not yet the job's current run. */
    public JobInstance create(JobDefinition job, JobVersion version, String startupParamsJson, String userId) {
        JobInstance instance = new JobInstance();
        instance.setProjectId(job.getProjectId());
        instance.setJobDefinitionId(job.getId());
        instance.setJobVersionId(version.getId());
        instance.setTriggerType(StreamRunType.START);
        LocalDateTime now = LocalDateTime.now();
        instance.setScheduledTime(now);
        instance.setStatus(RunStatus.RUNNING.name());
        instance.setStartedAt(now);
        instance.setAttemptCount(0);
        instance.setMaxAttempts(1);
        instance.setEngineType("ZETA");
        instance.setStartupParamsJson(startupParamsJson);
        instance.setVariablesJson(startupParamsJson);
        instance.setCreatedBy(userId);
        recorder.insert(instance);
        return instance;
    }

    /** Makes the run the job's current one; false when another run is going already. */
    public boolean claim(String jobId, String instanceId) {
        return streams.update(null, new LambdaUpdateWrapper<JobStream>()
                .set(JobStream::getCurrentInstanceId, instanceId)
                .set(JobStream::getStopRequested, null)
                .set(JobStream::getStopRequestedAt, null)
                .set(JobStream::getRestartAt, null)
                .set(JobStream::getPipelineRestores, 0)
                .set(JobStream::getCheckpointProblem, null)
                .set(JobStream::getCheckpointAt, null)
                .set(JobStream::getLastReadAt, null)
                .eq(JobStream::getJobDefinitionId, jobId)
                .isNull(JobStream::getCurrentInstanceId)) > 0;
    }

    /**
     * Forgets where an earlier run could be restored from, once a fresh start reached the engine: a
     * restart of this run before its first checkpoint must not go back to the old position.
     */
    public void forgetRestorePoint(String jobId, String instanceId) {
        streams.update(null, new LambdaUpdateWrapper<JobStream>()
                .set(JobStream::getRestoreKind, null)
                .set(JobStream::getRestoreEngineJobId, null)
                .set(JobStream::getRestoreVersionId, null)
                .set(JobStream::getRestoreBlocked, null)
                .eq(JobStream::getJobDefinitionId, jobId)
                .eq(JobStream::getCurrentInstanceId, instanceId));
    }

    /**
     * Takes the pending automatic restart of the run, for the monitor to carry out or a stop to
     * drop; false when the other side took it first (or none is pending).
     */
    public boolean takeRestart(String jobId, String instanceId) {
        return streams.update(null, new LambdaUpdateWrapper<JobStream>()
                .set(JobStream::getRestartAt, null)
                .set(JobStream::getPipelineRestores, 0)
                .eq(JobStream::getJobDefinitionId, jobId)
                .eq(JobStream::getCurrentInstanceId, instanceId)
                .isNotNull(JobStream::getRestartAt)) > 0;
    }

    /** Table-structure changes noticed in the job's runs (SchemaDriftWatcher), newest first. */
    public List<JobInstanceEvent> schemaEvents(String jobId, int limit) {
        return events.selectList(new LambdaQueryWrapper<JobInstanceEvent>()
                .eq(JobInstanceEvent::getEventType, RunEventType.SCHEMA_CHANGE.name())
                .inSql(JobInstanceEvent::getInstanceId,
                        "select id from job_instance where job_definition_id = '" + jobId.replace("'", "''") + "'")
                .orderByDesc(JobInstanceEvent::getCreatedAt)
                .last("limit " + limit));
    }

    /** The job's latest start, or null when it never started. */
    public JobInstance lastRun(String jobId) {
        return instances.selectOne(new LambdaQueryWrapper<JobInstance>()
                .select(JobInstance.class, f -> !f.getColumn().equals("config_masked"))
                .eq(JobInstance::getJobDefinitionId, jobId)
                .eq(JobInstance::getTriggerType, StreamRunType.START)
                .orderByDesc(JobInstance::getCreatedAt)
                .last("limit 1"));
    }

    public JobInstanceAttempt newAttempt(JobInstance instance) {
        JobInstanceAttempt attempt = new JobInstanceAttempt();
        attempt.setInstanceId(instance.getId());
        attempt.setAttemptNo((instance.getAttemptCount() == null ? 0 : instance.getAttemptCount()) + 1);
        attempt.setLogTruncated(false);
        recorder.insert(attempt);
        instance.setAttemptCount(attempt.getAttemptNo());
        recorder.update(instance);
        return attempt;
    }

    public List<JobInstanceAttempt> attempts(String instanceId) {
        return attempts.selectList(new LambdaQueryWrapper<JobInstanceAttempt>()
                .eq(JobInstanceAttempt::getInstanceId, instanceId)
                .orderByAsc(JobInstanceAttempt::getAttemptNo));
    }

    public JobInstanceAttempt latestAttempt(String instanceId) {
        List<JobInstanceAttempt> all = attempts(instanceId);
        return all.isEmpty() ? null : all.get(all.size() - 1);
    }

    /** Ends the run and frees the job for the next start, leaving where that start can restore from. */
    public void finish(JobInstance instance, RunStatus status, String message, RestorePoint point) {
        instance.setStatus(status.name());
        instance.setFinishedAt(LocalDateTime.now());
        instance.setErrorMessage(status == RunStatus.FAILED || status == RunStatus.CANCELED ? RunRecorder.clip(message) : null);
        recorder.update(instance);
        recorder.event(instance.getId(), instance.getAttemptCount(), RunEventType.FINISHED,
                status == RunStatus.FAILED ? Level.ERROR : Level.INFO, message);
        LambdaUpdateWrapper<JobStream> update = new LambdaUpdateWrapper<JobStream>()
                .set(JobStream::getCurrentInstanceId, null)
                .set(JobStream::getStopRequested, null)
                .set(JobStream::getStopRequestedAt, null)
                .set(JobStream::getRestartAt, null)
                .set(JobStream::getPipelineRestores, 0)
                .set(JobStream::getCheckpointProblem, null)
                .eq(JobStream::getJobDefinitionId, instance.getJobDefinitionId())
                .eq(JobStream::getCurrentInstanceId, instance.getId());
        if (point != null) {
            update.set(JobStream::getRestoreKind, point.kind() == null ? null : point.kind().name())
                    .set(JobStream::getRestoreEngineJobId, point.kind() == null ? null : String.valueOf(point.engineJobId()))
                    .set(JobStream::getRestoreVersionId, point.versionId())
                    .set(JobStream::getRestoreBlocked, point.blocked());
        }
        streams.update(null, update);
    }

    /** Writes only the given live-state columns of the job's stream row. */
    public void updateLive(String jobId, LambdaUpdateWrapper<JobStream> set) {
        streams.update(null, set.eq(JobStream::getJobDefinitionId, jobId));
    }
}
