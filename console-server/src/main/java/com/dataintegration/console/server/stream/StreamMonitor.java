package com.dataintegration.console.server.stream;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.dataintegration.console.common.ConsoleException;
import com.dataintegration.console.engine.EngineCheckpoints;
import com.dataintegration.console.engine.EngineClient;
import com.dataintegration.console.engine.EngineJobInfo;
import com.dataintegration.console.engine.EngineJobStatus;
import com.dataintegration.console.engine.SubmitJobRequest.RestoreMode;
import com.dataintegration.console.server.job.JobConfigRenderer;
import com.dataintegration.console.server.job.JobService;
import com.dataintegration.console.server.message.MessageCategory;
import com.dataintegration.console.server.persistence.entity.JobDefinition;
import com.dataintegration.console.server.persistence.entity.JobInstance;
import com.dataintegration.console.server.persistence.entity.JobInstanceAttempt;
import com.dataintegration.console.server.persistence.entity.JobStream;
import com.dataintegration.console.server.persistence.mapper.JobStreamMapper;
import com.dataintegration.console.server.run.AttemptLogArchiver;
import com.dataintegration.console.server.run.EngineMetrics;
import com.dataintegration.console.server.run.RunEventType;
import com.dataintegration.console.server.run.RunRecorder;
import com.dataintegration.console.server.run.RunRecorder.Level;
import com.dataintegration.console.server.run.RunStatus;
import com.dataintegration.console.server.stream.RestorePlan.Kind;
import com.dataintegration.console.server.stream.StreamRuns.RestorePoint;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Follows every running streaming job on the engine (docs/design.md §10.1, §12 P2): its status
 * every poll, its counters and checkpoints at a slower pace (so a console with many jobs does not
 * hammer the engine or its own database), and what to do when an engine job ends: record where the
 * next start can restore from, or restart from the latest checkpoint when the job asks for it. The
 * console is single-instance; after a restart this simply picks the running jobs up again.
 */
@Component
public class StreamMonitor {

    private static final Logger log = LoggerFactory.getLogger(StreamMonitor.class);

    /** How long a submitted job may be unknown to the engine before the run is given up. */
    static final Duration SUBMIT_GRACE = Duration.ofMinutes(2);
    /** A savepoint stop that takes longer is reported, so the user can force it. */
    static final Duration SLOW_STOP = Duration.ofMinutes(2);
    private static final Pattern CHECKPOINT_INTERVAL = Pattern.compile("\"?checkpoint\\.interval\"?\\s*[:=]\\s*(\\d+)");
    private static final Duration DEFAULT_CHECKPOINT_INTERVAL = Duration.ofSeconds(10);

    private final JobStreamMapper streams;
    private final StreamRuns runs;
    private final StreamSubmitter submitter;
    private final JobService jobService;
    private final EngineClient engine;
    private final AttemptLogArchiver logArchiver;
    private final ObjectMapper objectMapper;
    private final Duration sampleInterval;

    /** What the monitor last did for a running attempt, by attempt id; dropped when the attempt ends. */
    private final Map<String, Seen> seen = new ConcurrentHashMap<>();

    private static final class Seen {
        Instant sampledAt = Instant.EPOCH;
        Long readRows;
        EngineJobStatus status;
        boolean slowStopReported;
    }

    public StreamMonitor(JobStreamMapper streams, StreamRuns runs, StreamSubmitter submitter, JobService jobService, EngineClient engine,
            AttemptLogArchiver logArchiver, ObjectMapper objectMapper,
            @Value("${console.stream.sample-interval:60s}") Duration sampleInterval) {
        this.streams = streams;
        this.runs = runs;
        this.submitter = submitter;
        this.jobService = jobService;
        this.engine = engine;
        this.logArchiver = logArchiver;
        this.objectMapper = objectMapper;
        this.sampleInterval = sampleInterval;
    }

    @Scheduled(initialDelayString = "${console.stream.initial-delay:15s}", fixedDelayString = "${console.stream.poll-interval:10s}")
    public void poll() {
        List<JobStream> running = streams.selectList(new LambdaQueryWrapper<JobStream>().isNotNull(JobStream::getCurrentInstanceId));
        for (JobStream stream : running) {
            try {
                tick(stream);
            } catch (ConsoleException e) {
                // the engine or a datasource is unreachable right now; the next poll tries again
                log.warn("Monitoring streaming job {} failed: {}", stream.getJobDefinitionId(), e.getMessage());
            } catch (RuntimeException e) {
                log.error("Monitoring streaming job {} failed", stream.getJobDefinitionId(), e);
            }
        }
    }

    void tick(JobStream stream) {
        RunRecorder recorder = runs.recorder();
        JobInstance instance = recorder.requireInstance(stream.getCurrentInstanceId());
        JobInstanceAttempt attempt = runs.latestAttempt(instance.getId());
        if (stream.getRestartAt() != null) {
            if (!LocalDateTime.now().isBefore(stream.getRestartAt())) {
                restart(stream, instance, attempt);
            }
            return;
        }
        if (attempt == null || attempt.getSubmittedAt() == null && attempt.getFinishedAt() == null) {
            LocalDateTime began = attempt != null && attempt.getCreatedAt() != null ? attempt.getCreatedAt() : instance.getStartedAt();
            if (began != null && began.isAfter(LocalDateTime.now().minus(SUBMIT_GRACE))) {
                // a start or restart is on its way to the engine
                return;
            }
            if (attempt == null || attempt.getEngineJobId() == null) {
                // the start never reached the engine, and whoever started it is gone
                runs.finish(instance, RunStatus.FAILED, "启动没有完成：作业没有提交到引擎", null);
                return;
            }
            // The console went away during the submission: the engine may have the job under the
            // id recorded beforehand, so look it up like a submission whose answer was lost.
        }
        long engineJobId = Long.parseLong(attempt.getEngineJobId());
        if (attempt.getFinishedAt() != null) {
            // ended in an earlier poll that stopped halfway
            ended(stream, instance, attempt, engineJobId, EngineJobStatus.parse(attempt.getEngineStatus()), Map.of());
            return;
        }
        var info = engine.jobInfo(engineJobId);
        if (info.isEmpty()) {
            if (attempt.getSubmittedAt() != null && attempt.getSubmittedAt().isAfter(LocalDateTime.now().minus(SUBMIT_GRACE))) {
                // the engine may not list a job it has only just taken
                return;
            }
            recorder.event(instance.getId(), attempt.getAttemptNo(), RunEventType.ERROR, Level.ERROR,
                    "SeaTunnel 里找不到引擎作业 " + engineJobId + "（集群可能重启过，或提交没有成功）");
            ended(stream, instance, attempt, engineJobId, EngineJobStatus.UNKNOWABLE, Map.of());
            return;
        }
        EngineJobInfo job = info.get();
        if (attempt.getSubmittedAt() == null) {
            // found after the console went away during the submission
            attempt.setSubmittedAt(LocalDateTime.now());
            recorder.update(attempt);
            recorder.event(instance.getId(), attempt.getAttemptNo(), RunEventType.SUBMITTED, Level.WARN,
                    "提交时 console 中断过，SeaTunnel 里有引擎作业 " + engineJobId + "，继续观察");
        }
        if (job.status().isTerminal()) {
            ended(stream, instance, attempt, engineJobId, job.status(), job.metrics());
        } else {
            running(stream, instance, attempt, engineJobId, job);
        }
    }

    private void running(JobStream stream, JobInstance instance, JobInstanceAttempt attempt, long engineJobId, EngineJobInfo job) {
        RunRecorder recorder = runs.recorder();
        Seen s = seen.computeIfAbsent(attempt.getId(), k -> new Seen());
        if (job.status() != s.status) {
            s.status = job.status();
            attempt.setEngineStatus(job.status().name());
            recorder.update(attempt);
            recorder.event(instance.getId(), attempt.getAttemptNo(), RunEventType.STATUS_CHANGED, Level.INFO, "引擎状态 " + job.status());
        }
        int restores = stream.getPipelineRestores() == null ? 0 : stream.getPipelineRestores();
        if (job.pipelineRestores() != restores) {
            runs.updateLive(stream.getJobDefinitionId(), new LambdaUpdateWrapper<JobStream>()
                    .set(JobStream::getPipelineRestores, job.pipelineRestores()));
            if (job.pipelineRestores() > restores) {
                String message = "作业出错，SeaTunnel 正在自动恢复（第 " + job.pipelineRestores() + " 次；超过引擎的上限后作业会失败）";
                recorder.event(instance.getId(), attempt.getAttemptNo(), RunEventType.ERROR, Level.WARN, message);
                unstable(instance, message);
            }
        }
        Instant now = Instant.now();
        if (Duration.between(s.sampledAt, now).compareTo(sampleInterval) >= 0) {
            s.sampledAt = now;
            sample(stream, instance, attempt, engineJobId, job, s);
        }
        if (stream.getStopRequested() != null && !s.slowStopReported && stream.getStopRequestedAt() != null
                && stream.getStopRequestedAt().isBefore(LocalDateTime.now().minus(SLOW_STOP))) {
            s.slowStopReported = true;
            recorder.event(instance.getId(), attempt.getAttemptNo(), RunEventType.ERROR, Level.WARN,
                    "停止 " + SLOW_STOP.toMinutes() + " 分钟还没有完成（savepoint 可能做不出来，检查 checkpoint 存储）。"
                            + "可以强制停止；之后只能从最近的 checkpoint 恢复");
        }
    }

    /** Counters, the throughput sample, when data last came in, and checkpoint health; once per sample interval. */
    private void sample(JobStream stream, JobInstance instance, JobInstanceAttempt attempt, long engineJobId, EngineJobInfo job, Seen s) {
        RunRecorder recorder = runs.recorder();
        if (!job.metrics().isEmpty()) {
            EngineMetrics metrics = EngineMetrics.parse(job.metrics(), objectMapper);
            attempt.setMetricsJson(objectMapper.writeValueAsString(job.metrics()));
            recorder.update(attempt);
            recorder.progress(instance.getId(), attempt.getAttemptNo(), metrics);
            if (metrics.readRows() != null && (s.readRows == null || metrics.readRows() > s.readRows)) {
                if (s.readRows != null || stream.getLastReadAt() == null) {
                    runs.updateLive(stream.getJobDefinitionId(), new LambdaUpdateWrapper<JobStream>()
                            .set(JobStream::getLastReadAt, LocalDateTime.now()));
                }
                s.readRows = metrics.readRows();
            }
        }
        EngineCheckpoints checkpoints;
        try {
            checkpoints = engine.checkpoints(engineJobId).orElse(null);
        } catch (ConsoleException e) {
            return;
        }
        Instant since = attempt.getSubmittedAt() == null ? Instant.now() : attempt.getSubmittedAt().atZone(ZoneId.systemDefault()).toInstant();
        String problem = CheckpointHealth.problem(checkpoints, checkpointInterval(instance), since, Instant.now());
        LambdaUpdateWrapper<JobStream> update = new LambdaUpdateWrapper<JobStream>().set(JobStream::getCheckpointProblem, problem);
        if (checkpoints != null && checkpoints.lastCompletedAt() != null) {
            update.set(JobStream::getCheckpointAt, LocalDateTime.ofInstant(Instant.ofEpochMilli(checkpoints.lastCompletedAt()), ZoneId.systemDefault()));
            // From its first completed checkpoint on, this engine job is where a failure or a
            // forced stop resumes; until then it is still the point the run started from.
            if (!String.valueOf(engineJobId).equals(stream.getRestoreEngineJobId()) || !Kind.CHECKPOINT.name().equals(stream.getRestoreKind())) {
                update.set(JobStream::getRestoreKind, Kind.CHECKPOINT.name())
                        .set(JobStream::getRestoreEngineJobId, String.valueOf(engineJobId))
                        .set(JobStream::getRestoreVersionId, instance.getJobVersionId())
                        .set(JobStream::getRestoreBlocked, null);
                // The engine job has come far enough to checkpoint: earlier failures are behind it,
                // and so is a checkpoint problem left open by an earlier run, unless one is seen now.
                runs.messages().resolve(stream.getJobDefinitionId(), MessageCategory.STREAM_FAILED, MessageCategory.STREAM_UNSTABLE);
                if (problem == null) {
                    runs.messages().resolve(stream.getJobDefinitionId(), MessageCategory.CHECKPOINT);
                }
            }
        }
        runs.updateLive(stream.getJobDefinitionId(), update);
        if (problem != null && stream.getCheckpointProblem() == null) {
            String message = "checkpoint 异常：" + problem + "。savepoint 停止可能失败，失败后恢复要重放更多数据";
            recorder.event(instance.getId(), attempt.getAttemptNo(), RunEventType.ERROR, Level.WARN, message);
            runs.messages().job(MessageCategory.CHECKPOINT, Level.WARN, stream.getJobDefinitionId(), instance.getId(),
                    "/realtime/" + stream.getJobDefinitionId(), stream.getJobDefinitionId(), "checkpoint 异常", message);
        } else if (problem == null && stream.getCheckpointProblem() != null) {
            recorder.event(instance.getId(), attempt.getAttemptNo(), RunEventType.STATUS_CHANGED, Level.INFO, "checkpoint 恢复正常");
            runs.messages().resolve(stream.getJobDefinitionId(), MessageCategory.CHECKPOINT);
        }
    }

    /** The interval the run was submitted with (its masked config), else SeaTunnel's default. */
    static Duration checkpointInterval(JobInstance instance) {
        if (instance.getConfigMasked() != null) {
            Matcher m = CHECKPOINT_INTERVAL.matcher(instance.getConfigMasked());
            if (m.find()) {
                return Duration.ofMillis(Long.parseLong(m.group(1)));
            }
        }
        return DEFAULT_CHECKPOINT_INTERVAL;
    }

    /** The engine job is over: record why, and either restart it or end the run. */
    private void ended(JobStream stream, JobInstance instance, JobInstanceAttempt attempt, long engineJobId, EngineJobStatus status,
            Map<String, String> metrics) {
        RunRecorder recorder = runs.recorder();
        seen.remove(attempt.getId());
        String logText = null;
        if (attempt.getFinishedAt() == null) {
            attempt.setEngineStatus(status.name());
            attempt.setFinishedAt(LocalDateTime.now());
            if (!metrics.isEmpty()) {
                attempt.setMetricsJson(objectMapper.writeValueAsString(metrics));
                EngineMetrics m = EngineMetrics.parse(metrics, objectMapper);
                instance.setReadRows(m.readRows());
                instance.setWriteRows(m.writeRows());
                instance.setReadBytes(m.readBytes());
                instance.setWriteBytes(m.writeBytes());
            }
            recorder.update(attempt);
            logText = logArchiver.archive(instance.getId(), attempt, engineJobId);
        }
        String versionId = instance.getJobVersionId();
        switch (status) {
            case SAVEPOINT_DONE -> runs.finish(instance, RunStatus.CANCELED, "已停止，savepoint 已保存；下次启动从这里继续",
                    new RestorePoint(Kind.SAVEPOINT, engineJobId, versionId, null));
            case CANCELED -> runs.finish(instance, RunStatus.CANCELED, "已强制停止；" + resumesFrom(stream.getJobDefinitionId()), null);
            case FINISHED -> runs.finish(instance, RunStatus.SUCCESS, "引擎作业已结束", new RestorePoint(null, engineJobId, versionId, null));
            default -> failed(stream, instance, attempt, engineJobId, status, StreamFailure.fromLog(logText));
        }
    }

    private void failed(JobStream stream, JobInstance instance, JobInstanceAttempt attempt, long engineJobId, EngineJobStatus status,
            StreamFailure failure) {
        RunRecorder recorder = runs.recorder();
        String cause = failure.summary() == null ? "引擎作业 " + engineJobId + " " + status : failure.summary();
        if (failure.summary() != null) {
            attempt.setErrorMessage(RunRecorder.clip(failure.summary()));
            recorder.update(attempt);
        }
        if (stream.getStopRequested() == null && AutoRestartPolicy.restart(stream, runs.attempts(instance.getId()), failure.schemaChange())) {
            int interval = stream.getRestartIntervalSeconds() == null ? 30 : stream.getRestartIntervalSeconds();
            runs.updateLive(stream.getJobDefinitionId(), new LambdaUpdateWrapper<JobStream>()
                    .set(JobStream::getRestartAt, LocalDateTime.now().plusSeconds(interval))
                    .eq(JobStream::getCurrentInstanceId, instance.getId()));
            String message = "作业失败：" + cause + "。" + interval + " 秒后从最近的 checkpoint 自动重启";
            recorder.event(instance.getId(), attempt.getAttemptNo(), RunEventType.RETRY, Level.WARN, message);
            unstable(instance, message);
            return;
        }
        if (failure.restoreBlocked() != null) {
            runs.updateLive(stream.getJobDefinitionId(), new LambdaUpdateWrapper<JobStream>()
                    .set(JobStream::getRestoreBlocked, failure.restoreBlocked()));
        }
        String message = failure.schemaChange()
                ? "源表结构变更导致作业失败：" + cause + "。从 checkpoint 恢复会重放同一条变更再次失败；"
                        + "请修改任务（或先手动修改目标表）后全新启动"
                : "作业失败：" + cause + "。" + resumesFrom(stream.getJobDefinitionId());
        // the restore point stays where the last completed checkpoint (or the start) left it
        runs.finish(instance, RunStatus.FAILED, message, null);
    }

    /** Merged per job until the job checkpoints again or the run ends. */
    private void unstable(JobInstance instance, String message) {
        String jobId = instance.getJobDefinitionId();
        runs.messages().job(MessageCategory.STREAM_UNSTABLE, Level.WARN, jobId, instance.getId(), "/realtime/" + jobId, jobId,
                "实时任务出错，正在自动恢复", message);
    }

    /** Where the next start carries on, as the run's last words. */
    private String resumesFrom(String jobId) {
        JobStream now = streams.selectById(jobId);
        if (now == null || now.getRestoreEngineJobId() == null) {
            return "没有可以恢复的位置，下次只能全新启动（重新全量）";
        }
        return Kind.SAVEPOINT.name().equals(now.getRestoreKind())
                ? "还没有完成过 checkpoint，下次启动从启动时的 savepoint 恢复"
                : "下次启动可以从最近的 checkpoint 恢复";
    }

    /**
     * A new attempt of the run from the latest completed checkpoint; when the run never completed
     * one, from where it started (its savepoint), or fresh.
     */
    private void restart(JobStream stream, JobInstance instance, JobInstanceAttempt failed) {
        if (!runs.takeRestart(stream.getJobDefinitionId(), instance.getId())) {
            // stopped while it waited
            return;
        }
        JobDefinition job = jobService.require(instance.getJobDefinitionId());
        RestorePlan.Decision decision = RestorePlan.decide(streams.selectById(stream.getJobDefinitionId()), RestorePlan.Asked.AUTO, false);
        JobInstanceAttempt attempt = runs.newAttempt(instance);
        runs.recorder().event(instance.getId(), attempt.getAttemptNo(), RunEventType.RETRY, Level.INFO, "自动重启：" + decision.note());
        try {
            JobConfigRenderer.Rendered rendered = submitter.render(job, jobService.requireVersion(instance.getJobVersionId()), instance);
            submitter.submit(job, instance, attempt, rendered.config(), decision.mode(), decision.restoreFrom());
        } catch (RuntimeException e) {
            runs.finish(instance, RunStatus.FAILED, "自动重启失败：" + e.getMessage(), null);
        }
    }
}
