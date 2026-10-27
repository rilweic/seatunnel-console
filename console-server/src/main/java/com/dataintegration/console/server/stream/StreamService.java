package com.dataintegration.console.server.stream;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.dataintegration.console.common.ConsoleException;
import com.dataintegration.console.common.ErrorCodes;
import com.dataintegration.console.datasource.cdc.CdcSupport;
import com.dataintegration.console.engine.EngineClient;
import com.dataintegration.console.server.auth.CurrentUser;
import com.dataintegration.console.server.datasource.DatasourceService;
import com.dataintegration.console.server.job.JobConfigRenderer;
import com.dataintegration.console.server.job.JobService;
import com.dataintegration.console.server.job.wizard.WizardForm;
import com.dataintegration.console.server.persistence.entity.DatasourceEntity;
import com.dataintegration.console.server.persistence.entity.JobDefinition;
import com.dataintegration.console.server.persistence.entity.JobInstance;
import com.dataintegration.console.server.persistence.entity.JobInstanceAttempt;
import com.dataintegration.console.server.persistence.entity.JobStream;
import com.dataintegration.console.server.persistence.entity.JobVersion;
import com.dataintegration.console.server.run.RunEventType;
import com.dataintegration.console.server.run.RunRecorder;
import com.dataintegration.console.server.run.RunRecorder.Level;
import com.dataintegration.console.server.run.RunStatus;
import com.dataintegration.console.server.stream.RestorePlan.Asked;
import com.dataintegration.console.server.stream.RestorePlan.Decision;
import com.dataintegration.console.server.stream.SchemaDriftWatcher.TableChanges;

import org.springframework.stereotype.Service;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Starting, stopping and configuring streaming jobs from the console (docs/design.md §12 P2). The
 * engine side of a run after its start is followed by {@link StreamMonitor}. Rights follow the job:
 * only its creator or an admin may start, stop or change it.
 */
@Service
public class StreamService {

    /** @param maxRestarts restarts in a row before the run is left failed */
    public record Settings(boolean autoRestart, int maxRestarts, int restartIntervalSeconds) {}

    /**
     * What the job's page shows about it now.
     *
     * @param state STOPPED, RUNNING, RECOVERING (the engine restores pipelines), RESTARTING (waits
     *     to restart after a failure) or STOPPING
     * @param newVersionPending the running version is not the job's current one
     * @param sourceChanged the current version reads differently from the restore point's version,
     *     so a start without choosing is fresh
     * @param replication the PostgreSQL slot the job keeps, with the WAL it holds; null otherwise
     */
    public record StreamView(
            String jobId,
            String state,
            String currentInstanceId,
            String runningVersionId,
            boolean newVersionPending,
            Integer attemptNo,
            String engineJobId,
            String stopRequested,
            LocalDateTime stopRequestedAt,
            LocalDateTime restartAt,
            LocalDateTime startedAt,
            LocalDateTime lastReadAt,
            LocalDateTime checkpointAt,
            String checkpointProblem,
            int pipelineRestores,
            String restoreKind,
            String restoreEngineJobId,
            String restoreBlocked,
            boolean sourceChanged,
            Settings settings,
            CdcSupport.Replication replication,
            String lastRunId,
            String lastRunStatus,
            String lastRunMessage,
            List<SchemaChange> schemaChanges,
            List<TableChanges> tableChanges) {}

    /** A table-structure change the watcher noticed (§12 P2, Q16); level WARN when the target did not follow. */
    public record SchemaChange(LocalDateTime time, String level, String message) {}

    /**
     * A job's state for the real-time list: what {@link StreamView} says, without engine or
     * datasource calls.
     *
     * @param lastRunStatus the latest start's status; FAILED while stopped means the job needs attention
     */
    public record StreamSummary(
            String state, LocalDateTime startedAt, LocalDateTime lastReadAt, String checkpointProblem, String lastRunStatus,
            String lastRunMessage) {}

    /** @param restore where to start from; AUTO when not given */
    public record StartRequest(Asked restore, Map<String, String> startupParams) {}

    private final JobService jobService;
    private final StreamIdentityService identity;
    private final StreamRuns runs;
    private final StreamSubmitter submitter;
    private final CdcReadiness readiness;
    private final EngineClient engine;
    private final DatasourceService datasources;
    private final ObjectMapper objectMapper;
    private final SchemaDriftWatcher schemaWatcher;

    public StreamService(JobService jobService, StreamIdentityService identity, StreamRuns runs, StreamSubmitter submitter,
            CdcReadiness readiness, EngineClient engine, DatasourceService datasources, ObjectMapper objectMapper,
            SchemaDriftWatcher schemaWatcher) {
        this.schemaWatcher = schemaWatcher;
        this.jobService = jobService;
        this.identity = identity;
        this.runs = runs;
        this.submitter = submitter;
        this.readiness = readiness;
        this.engine = engine;
        this.datasources = datasources;
        this.objectMapper = objectMapper;
    }

    public StreamView view(String jobId) {
        JobDefinition job = requireStreaming(jobService.require(jobId));
        JobStream stream = identity.require(jobId);
        JobInstance current = stream.getCurrentInstanceId() == null ? null : runs.recorder().requireInstance(stream.getCurrentInstanceId());
        JobInstanceAttempt attempt = current == null ? null : runs.latestAttempt(current.getId());
        JobInstance last = current != null ? current : lastRun(jobId);
        String state = state(stream);
        return new StreamView(
                jobId,
                state,
                stream.getCurrentInstanceId(),
                current == null ? null : current.getJobVersionId(),
                current != null && !Objects.equals(current.getJobVersionId(), job.getCurrentVersionId()),
                attempt == null ? null : attempt.getAttemptNo(),
                attempt == null ? null : attempt.getEngineJobId(),
                stream.getStopRequested(),
                stream.getStopRequestedAt(),
                stream.getRestartAt(),
                current == null ? null : current.getStartedAt(),
                stream.getLastReadAt(),
                stream.getCheckpointAt(),
                stream.getCheckpointProblem(),
                stream.getPipelineRestores() == null ? 0 : stream.getPipelineRestores(),
                stream.getRestoreKind(),
                stream.getRestoreEngineJobId(),
                stream.getRestoreBlocked(),
                sourceChanged(stream, job),
                settings(stream),
                current == null ? replication(job, stream) : null,
                last == null ? null : last.getId(),
                last == null ? null : last.getStatus(),
                last == null ? null : last.getErrorMessage(),
                runs.schemaEvents(jobId, 10).stream().map(e -> new SchemaChange(e.getCreatedAt(), e.getLevel(), e.getMessage())).toList(),
                tableChanges(jobId, last));
    }

    /** Each table's changes since the latest start, by the form that start runs; none for scripts or before a start. */
    private List<TableChanges> tableChanges(String jobId, JobInstance last) {
        WizardForm form = last == null ? null : form(jobService.requireVersion(last.getJobVersionId()));
        return form == null ? List.of() : schemaWatcher.sinceStart(jobId, form);
    }

    /** States of the listed jobs, by job id; offline jobs are left out. */
    public Map<String, StreamSummary> summaries(List<String> jobIds) {
        Map<String, StreamSummary> result = new LinkedHashMap<>();
        for (String jobId : jobIds) {
            JobStream stream = identity.find(jobId);
            if (stream == null) {
                continue;
            }
            JobInstance last = runs.lastRun(jobId);
            result.put(jobId, new StreamSummary(state(stream), last == null || stream.getCurrentInstanceId() == null ? null : last.getStartedAt(),
                    stream.getLastReadAt(), stream.getCheckpointProblem(),
                    last == null ? null : last.getStatus(), last == null ? null : last.getErrorMessage()));
        }
        return result;
    }

    private static String state(JobStream stream) {
        if (stream.getCurrentInstanceId() == null) {
            return "STOPPED";
        }
        if (stream.getStopRequested() != null) {
            return "STOPPING";
        }
        if (stream.getRestartAt() != null) {
            return "RESTARTING";
        }
        return stream.getPipelineRestores() != null && stream.getPipelineRestores() > 0 ? "RECOVERING" : "RUNNING";
    }

    /** Starts the job's current version, restoring from where the last run ended unless asked otherwise. */
    public StreamView start(String jobId, StartRequest request) {
        JobDefinition job = requireStreaming(jobService.requireEditable(jobId));
        if (job.getCurrentVersionId() == null) {
            throw new ConsoleException(ErrorCodes.INVALID_ARGUMENT, "任务还没有保存过配置");
        }
        JobVersion version = jobService.requireVersion(job.getCurrentVersionId());
        JobStream stream = identity.require(jobId);
        if (stream.getCurrentInstanceId() != null) {
            throw new ConsoleException(ErrorCodes.CONFLICT, "任务已经在运行");
        }
        Decision decision = RestorePlan.decide(stream, request.restore() == null ? Asked.AUTO : request.restore(), sourceChanged(stream, job));
        Map<String, String> startup = request.startupParams() == null ? Map.of() : request.startupParams();
        WizardForm saved = form(version);
        if (decision.fresh() && saved != null) {
            // A fresh start reads the tables as they are now; after a schema change the saved
            // mapping may no longer fit them, which the engine would only report as a failing sink.
            List<String> errors = jobService.wizardPrecheck(jobId, saved, jobService.params(version)).errors();
            if (!errors.isEmpty()) {
                throw new ConsoleException(ErrorCodes.INVALID_ARGUMENT, "当前配置和源表、目标表对不上，请先编辑任务：" + String.join("；", errors));
            }
        }
        JobInstance instance = runs.create(job, version, startup.isEmpty() ? null : objectMapper.writeValueAsString(startup), CurrentUser.idOrNull());
        if (!runs.claim(jobId, instance.getId())) {
            runs.abandon(instance, "任务已经在运行");
            throw new ConsoleException(ErrorCodes.CONFLICT, "任务已经在运行");
        }
        RunRecorder recorder = runs.recorder();
        recorder.event(instance.getId(), null, RunEventType.CREATED, Level.INFO, "启动 v" + versionNo(version) + "：" + decision.note());
        JobInstanceAttempt attempt = runs.newAttempt(instance);
        try {
            JobConfigRenderer.Rendered rendered = submitter.render(job, version, instance);
            List<String> problems = readiness.startErrors(stream, rendered.template(),
                    saved == null ? null : saved.source().namespace(),
                    saved == null ? List.of() : saved.source().tables().stream().map(WizardForm.TableSpec::name).toList());
            if (!problems.isEmpty()) {
                throw new ConsoleException(ErrorCodes.INVALID_ARGUMENT, "源库不满足实时同步条件：" + String.join("；", problems));
            }
            submitter.submit(job, instance, attempt, rendered.config(), decision.mode(), decision.restoreFrom());
        } catch (RuntimeException e) {
            runs.finish(instance, RunStatus.FAILED, "启动失败：" + e.getMessage(), null);
            throw e;
        }
        if (decision.fresh()) {
            // kept until now, so a start that fails before the engine leaves the old point usable
            runs.forgetRestorePoint(jobId, instance.getId());
        }
        try {
            // what the tables look like now, so changes from here on are noticed
            schemaWatcher.check(jobId, true);
        } catch (RuntimeException e) {
            // the watcher tries again on its next round
        }
        return view(jobId);
    }

    /**
     * Stops the running job: with a savepoint, so the next start carries on exactly there, or by
     * force, after which it can only resume from the latest checkpoint.
     */
    public StreamView stop(String jobId, boolean force) {
        requireStreaming(jobService.requireEditable(jobId));
        JobStream stream = identity.require(jobId);
        if (stream.getCurrentInstanceId() == null) {
            throw new ConsoleException(ErrorCodes.CONFLICT, "任务没有在运行");
        }
        JobInstance instance = runs.recorder().requireInstance(stream.getCurrentInstanceId());
        JobInstanceAttempt attempt = runs.latestAttempt(instance.getId());
        String who = (force ? "强制停止" : "停止并保存 savepoint") + "，操作人 " + CurrentUser.idOrNull();
        if (stream.getRestartAt() != null) {
            // nothing runs on the engine while the run waits to restart; taking the restart keeps
            // the monitor from submitting one after the run has ended
            if (!runs.takeRestart(jobId, instance.getId())) {
                throw new ConsoleException(ErrorCodes.CONFLICT, "任务正在自动重启，请稍后再停止");
            }
            runs.recorder().event(instance.getId(), attempt == null ? null : attempt.getAttemptNo(), RunEventType.CANCEL_REQUESTED, Level.INFO, who);
            runs.finish(instance, RunStatus.CANCELED, "已停止", null);
            return view(jobId);
        }
        if (attempt == null || attempt.getFinishedAt() == null && attempt.getSubmittedAt() == null) {
            // A start or restart is still on its way to the engine. Ending the run now would leave
            // the job it submits a moment later running unwatched; if whoever started it is gone,
            // the monitor ends the run after StreamMonitor.SUBMIT_GRACE.
            throw new ConsoleException(ErrorCodes.CONFLICT, "任务正在启动，请稍后再停止");
        }
        runs.updateLive(jobId, new LambdaUpdateWrapper<JobStream>()
                .set(JobStream::getStopRequested, force ? "FORCE" : "SAVEPOINT")
                .set(JobStream::getStopRequestedAt, LocalDateTime.now()));
        runs.recorder().event(instance.getId(), attempt.getAttemptNo(), RunEventType.CANCEL_REQUESTED, Level.INFO, who);
        if (attempt.getFinishedAt() != null) {
            // the engine job has ended already; the monitor had not got round to the run yet
            runs.finish(instance, RunStatus.CANCELED, "已停止", null);
            return view(jobId);
        }
        engine.stop(Long.parseLong(attempt.getEngineJobId()), !force, force);
        return view(jobId);
    }

    public StreamView updateSettings(String jobId, Settings settings) {
        requireStreaming(jobService.requireEditable(jobId));
        if (settings.maxRestarts() < 1 || settings.maxRestarts() > 20 || settings.restartIntervalSeconds() < 10
                || settings.restartIntervalSeconds() > 3600) {
            throw new ConsoleException(ErrorCodes.INVALID_ARGUMENT, "最多重启 1–20 次，间隔 10–3600 秒");
        }
        runs.updateLive(jobId, new LambdaUpdateWrapper<JobStream>()
                .set(JobStream::getAutoRestart, settings.autoRestart())
                .set(JobStream::getMaxRestarts, settings.maxRestarts())
                .set(JobStream::getRestartIntervalSeconds, settings.restartIntervalSeconds()));
        return view(jobId);
    }

    /**
     * Deletes a stopped streaming job (like an offline one), then the slot and publication it keeps
     * on a PostgreSQL source, which otherwise hold WAL forever (§12 P2, Q9 and Q29). Every
     * datasource any version used is cleaned, not only the current source: a job whose source was
     * changed, or a script, may have left them elsewhere.
     *
     * @return SQL left to run by hand because dropping failed, each datasource's under a comment
     *         naming it; empty when nothing is left
     */
    public List<String> delete(String jobId, boolean deleteRuns, boolean dropReplication) {
        requireStreaming(jobService.requireEditable(jobId));
        JobStream stream = identity.require(jobId);
        List<String> used = jobService.datasourcesOfAllVersions(jobId);
        jobService.delete(jobId, deleteRuns);
        schemaWatcher.forget(jobId);
        String name = stream.getReplicationName();
        if (!dropReplication || name == null) {
            return List.of();
        }
        List<String> left = new ArrayList<>();
        for (String id : used) {
            DatasourceEntity datasource;
            try {
                datasource = datasources.require(id);
            } catch (ConsoleException e) {
                continue; // deleted since; nothing to connect with
            }
            if (datasources.plugin(datasource.getType()).cdcSupport() == null) {
                continue;
            }
            List<String> sql;
            try {
                sql = datasources.dropReplication(id, name);
            } catch (RuntimeException e) {
                sql = List.of("SELECT pg_drop_replication_slot('" + name + "');", "DROP PUBLICATION IF EXISTS " + name + ";");
            }
            if (!sql.isEmpty()) {
                left.add("-- 数据源「" + datasource.getName() + "」");
                left.addAll(sql);
            }
        }
        return left;
    }

    /** The CDC source's datasource of the current version, where the job's replication resources live. */
    private String sourceDatasource(JobDefinition job) {
        if (job.getCurrentVersionId() == null) {
            return null;
        }
        WizardForm form = form(jobService.requireVersion(job.getCurrentVersionId()));
        return form == null ? null : form.source().datasourceId();
    }

    private CdcSupport.Replication replication(JobDefinition job, JobStream stream) {
        String source = sourceDatasource(job);
        if (source == null || stream.getReplicationName() == null) {
            return null;
        }
        try {
            return datasources.plugin(datasources.typeOf(source)).cdcSupport() == null
                    ? null : datasources.replication(source, stream.getReplicationName());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Whether the job now reads or writes differently from the version its restore point was taken
     * with. A restore then fails (Zeta creates no target table for a new table) or carries on
     * from a position that means something else. Settings that change neither (parallelism,
     * checkpoint interval, skipping deletes, advanced options) do not count; for a script, any edit does.
     */
    boolean sourceChanged(JobStream stream, JobDefinition job) {
        if (stream.getRestoreVersionId() == null || job.getCurrentVersionId() == null
                || stream.getRestoreVersionId().equals(job.getCurrentVersionId())) {
            return false;
        }
        JobVersion before = jobService.requireVersion(stream.getRestoreVersionId());
        JobVersion now = jobService.requireVersion(job.getCurrentVersionId());
        if (before.getFormJson() == null || now.getFormJson() == null) {
            return !Objects.equals(before.getHoconTemplate(), now.getHoconTemplate());
        }
        return !shape(before.getFormJson()).equals(shape(now.getFormJson()));
    }

    private ObjectNode shape(String formJson) {
        ObjectNode form = (ObjectNode) objectMapper.readTree(formJson);
        form.remove(List.of("parallelism", "validation", "sourceOptions", "sinkOptions"));
        if (form.get("stream") instanceof ObjectNode stream) {
            stream.remove(List.of("checkpointSeconds", "skipDeletes", "startup", "startupTimestamp"));
        }
        return form;
    }

    private WizardForm form(JobVersion version) {
        return version.getFormJson() == null ? null : objectMapper.readValue(version.getFormJson(), WizardForm.class);
    }

    private JobInstance lastRun(String jobId) {
        return runs.lastRun(jobId);
    }

    private static int versionNo(JobVersion version) {
        return version.getVersionNo() == null ? 0 : version.getVersionNo();
    }

    private static Settings settings(JobStream stream) {
        return new Settings(Boolean.TRUE.equals(stream.getAutoRestart()),
                stream.getMaxRestarts() == null ? 3 : stream.getMaxRestarts(),
                stream.getRestartIntervalSeconds() == null ? 30 : stream.getRestartIntervalSeconds());
    }

    private static JobDefinition requireStreaming(JobDefinition job) {
        if (!JobService.TYPE_STREAMING.equals(job.getJobType())) {
            throw new ConsoleException(ErrorCodes.INVALID_ARGUMENT, "不是实时任务");
        }
        return job;
    }
}
