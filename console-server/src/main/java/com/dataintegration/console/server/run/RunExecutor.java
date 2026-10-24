package com.dataintegration.console.server.run;

import com.dataintegration.console.common.ConsoleException;
import com.dataintegration.console.common.ErrorCodes;
import com.dataintegration.console.engine.EngineClient;
import com.dataintegration.console.engine.EngineJobInfo;
import com.dataintegration.console.engine.EngineJobStatus;
import com.dataintegration.console.engine.SubmitJobRequest;
import com.dataintegration.console.server.job.JobConfigRenderer;
import com.dataintegration.console.server.job.RowCountValidator;
import com.dataintegration.console.server.job.ScriptChecker;
import com.dataintegration.console.server.job.wizard.WizardService;
import com.dataintegration.console.server.job.ValidationSpec;
import com.dataintegration.console.server.job.JobService;
import com.dataintegration.console.server.persistence.entity.JobDefinition;
import com.dataintegration.console.server.persistence.entity.JobInstance;
import com.dataintegration.console.server.persistence.entity.JobInstanceAttempt;
import com.dataintegration.console.server.persistence.entity.JobSchedule;
import com.dataintegration.console.server.persistence.entity.JobVersion;
import com.dataintegration.console.server.persistence.mapper.JobScheduleMapper;
import com.dataintegration.console.server.run.RunRecorder.Level;
import com.dataintegration.console.server.scheduler.BatchJobRunner;
import com.dataintegration.console.server.scheduler.CancelSignal;
import com.dataintegration.console.server.scheduler.ScheduledTrigger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

/**
 * Executes one run instance end to end (docs/design.md §5.2, schema.md §4): render the config in
 * memory, submit to the engine, wait, retry within the same instance, archive each attempt's
 * SeaTunnel log and record the outcome. Called by the XXL-JOB handler, or directly when XXL-JOB is
 * disabled.
 */
@Service
public class RunExecutor {

    private static final Logger log = LoggerFactory.getLogger(RunExecutor.class);

    /**
     * SeaTunnel validates sources while accepting a job (e.g. a JDBC query against a missing table)
     * and then answers HTTP 500 without the root cause; that cause is only written to the master's
     * logs/seatunnel-engine-server.log, and no per-job log exists.
     */
    static final String REJECTED =
            "SeaTunnel rejected the job at submission (see logs/seatunnel-engine-server.log on the master for the root cause): ";

    private static final long NOT_SUBMITTED = -1;

    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {};

    /** Outcome reported back to the scheduler. */
    public record RunResult(String instanceId, RunStatus status, String message) {}

    private final RunRecorder recorder;
    private final JobService jobService;
    private final JobConfigRenderer renderer;
    private final RowCountValidator validator;
    private final ScriptChecker scriptChecker;
    private final JobScheduleMapper scheduleMapper;
    private final EngineClient engineClient;
    private final EngineLogService engineLogService;
    private final LogStorage logStorage;
    private final ObjectMapper objectMapper;
    private final BatchJobRunner runner;

    /** Runs driven by this process, by instance id; the console is single-instance. */
    private final ConcurrentMap<String, CancelSignal> activeRuns = new ConcurrentHashMap<>();

    public RunExecutor(
            RunRecorder recorder,
            JobService jobService,
            JobConfigRenderer renderer,
            RowCountValidator validator,
            ScriptChecker scriptChecker,
            JobScheduleMapper scheduleMapper,
            EngineClient engineClient,
            EngineLogService engineLogService,
            LogStorage logStorage,
            ObjectMapper objectMapper,
            @Value("${console.run.poll-interval:10s}") Duration pollInterval) {
        this.recorder = recorder;
        this.jobService = jobService;
        this.renderer = renderer;
        this.validator = validator;
        this.scriptChecker = scriptChecker;
        this.scheduleMapper = scheduleMapper;
        this.engineClient = engineClient;
        this.engineLogService = engineLogService;
        this.logStorage = logStorage;
        this.objectMapper = objectMapper;
        this.runner = new BatchJobRunner(engineClient, pollInterval);
    }

    /**
     * @param schedulerLog extra sink for progress lines, e.g. the XXL-JOB log; never receives secrets
     */
    public RunResult execute(ScheduledTrigger trigger, Consumer<String> schedulerLog) {
        JobInstance instance = trigger.preCreated() ? startPreCreated(trigger) : createScheduled(trigger);
        // Registered before the instance turns RUNNING, so a stop request never finds it RUNNING
        // without a signal to deliver.
        CancelSignal signal = new CancelSignal();
        activeRuns.put(instance.getId(), signal);
        try {
            if (instance.getXxlLogId() == null && trigger.xxlLogId() > 0) {
                instance.setXxlJobId((int) trigger.xxlJobId());
                instance.setXxlLogId(trigger.xxlLogId());
            }
            instance.setStatus(RunStatus.RUNNING.name());
            instance.setStartedAt(LocalDateTime.now());
            if (!recorder.claim(instance)) {
                throw new ConsoleException(
                        ErrorCodes.CONFLICT,
                        "run instance " + instance.getId() + " is already "
                                + recorder.requireInstance(instance.getId()).getStatus());
            }
            try {
                return run(instance, signal, schedulerLog);
            } catch (RuntimeException e) {
                log.error("Run {} failed unexpectedly", instance.getId(), e);
                return finish(instance, RunStatus.FAILED, "internal error: " + e, schedulerLog);
            }
        } finally {
            activeRuns.remove(instance.getId());
            if (signal.interrupted()) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Asks a run driven by this process to stop. Returns false when it is not running here (not
     * started yet, already finished, or started by a console process that has since restarted).
     */
    public boolean cancel(String instanceId, String reason) {
        CancelSignal signal = activeRuns.get(instanceId);
        if (signal == null) {
            return false;
        }
        signal.cancel(reason);
        return true;
    }

    private RunResult run(JobInstance instance, CancelSignal signal, Consumer<String> schedulerLog) {
        Prepared prepared;
        try {
            prepared = prepare(instance);
        } catch (ConsoleException e) {
            return finish(instance, RunStatus.FAILED, "cannot prepare job config: " + e.getMessage(), schedulerLog);
        }

        RetryPolicy retry = retryPolicy(instance);
        int maxAttempts = Math.max(1, instance.getMaxAttempts() == null ? 1 : instance.getMaxAttempts());
        BatchJobRunner.Outcome outcome = null;
        for (int attemptNo = 1; attemptNo <= maxAttempts && !signal.isCancelled(); attemptNo++) {
            outcome = runAttempt(instance, attemptNo, prepared, signal, schedulerLog);
            if (outcome.succeeded() || outcome.finalStatus() == EngineJobStatus.CANCELED) {
                break;
            }
            if (attemptNo < maxAttempts) {
                recorder.event(instance.getId(), attemptNo, RunEventType.RETRY, Level.WARN,
                        "attempt " + attemptNo + " failed, retrying in " + retry.interval().toSeconds() + "s");
                schedulerLog.accept("attempt " + attemptNo + " failed, retrying");
                awaitRetry(retry.interval(), signal);
            }
        }

        if (outcome == null) {
            return finish(instance, RunStatus.CANCELED, signal.reason(), schedulerLog);
        }
        applyMetrics(instance, outcome.metrics());
        RunStatus status =
                outcome.succeeded()
                        ? RunStatus.SUCCESS
                        : outcome.finalStatus() == EngineJobStatus.CANCELED || signal.isCancelled()
                                ? RunStatus.CANCELED
                                : RunStatus.FAILED;
        String message;
        if (status == RunStatus.SUCCESS) {
            message = "engine job " + outcome.engineJobId() + " finished";
            RowCountValidator.Result validation = validate(instance, prepared, schedulerLog);
            if (validation != null && validation.status() == RowCountValidator.Status.MISMATCH && prepared.validation().failOnMismatch()) {
                status = RunStatus.FAILED;
                message = "row count validation failed: " + validation.message();
            }
        } else if (status == RunStatus.CANCELED) {
            message = signal.isCancelled() ? signal.reason() : outcome.errorMessage();
        } else if (outcome.engineJobId() == NOT_SUBMITTED) {
            message = outcome.errorMessage();
        } else {
            message = "engine job " + outcome.engineJobId() + " " + outcome.finalStatus()
                    + (outcome.errorMessage() == null ? "" : ": " + outcome.errorMessage());
        }
        return finish(instance, status, message, schedulerLog);
    }

    private BatchJobRunner.Outcome runAttempt(
            JobInstance instance,
            int attemptNo,
            Prepared prepared,
            CancelSignal signal,
            Consumer<String> schedulerLog) {
        JobInstanceAttempt attempt = new JobInstanceAttempt();
        attempt.setInstanceId(instance.getId());
        attempt.setAttemptNo(attemptNo);
        attempt.setLogTruncated(false);
        recorder.insert(attempt);
        instance.setAttemptCount(attemptNo);
        recorder.update(instance);

        BatchJobRunner.Outcome outcome;
        try {
            outcome = runner.run(prepared.request(), new RecordingListener(instance.getId(), attempt, schedulerLog), signal);
        } catch (ConsoleException e) {
            attempt.setEngineStatus(EngineJobStatus.FAILED.name());
            attempt.setErrorMessage(RunRecorder.clip(e.getMessage()));
            attempt.setFinishedAt(LocalDateTime.now());
            recorder.update(attempt);
            String message = REJECTED + e.getMessage() + rejectionCause(prepared);
            recorder.event(instance.getId(), attemptNo, RunEventType.ERROR, Level.ERROR, message);
            return new BatchJobRunner.Outcome(NOT_SUBMITTED, EngineJobStatus.FAILED, Map.of(), message);
        }

        if (Thread.interrupted()) {
            // Cleared so the outcome and the log can still be recorded; execute() restores it.
            signal.cancelByInterrupt(outcome.errorMessage());
        }
        attempt.setEngineStatus(outcome.finalStatus().name());
        attempt.setMetricsJson(objectMapper.writeValueAsString(outcome.metrics()));
        attempt.setErrorMessage(RunRecorder.clip(outcome.errorMessage()));
        attempt.setFinishedAt(LocalDateTime.now());
        recorder.update(attempt);
        archiveLog(instance, attempt, outcome.engineJobId());
        return outcome;
    }

    /** Pulls the attempt's SeaTunnel log from all engine nodes before Zeta deletes it (after one day by default). */
    private void archiveLog(JobInstance instance, JobInstanceAttempt attempt, long engineJobId) {
        try {
            EngineLogService.CollectedLog collected = engineLogService.collect(engineJobId);
            if (collected.fileCount() == 0) {
                recorder.event(instance.getId(), attempt.getAttemptNo(), RunEventType.LOG_ARCHIVED, Level.WARN,
                        "SeaTunnel returned no job log; enable per-job logs on every node "
                                + "(rootLogger.appenderRef.file.ref = routingAppender in config/log4j2.properties)");
                return;
            }
            attempt.setLogStorageKey(logStorage.write(attempt.getId(), java.time.LocalDate.now(), collected.text()));
            attempt.setLogSizeBytes((long) collected.text().getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            attempt.setLogTruncated(collected.truncated());
            attempt.setLogArchivedAt(LocalDateTime.now());
            recorder.update(attempt);
            recorder.event(instance.getId(), attempt.getAttemptNo(), RunEventType.LOG_ARCHIVED, Level.INFO,
                    "archived SeaTunnel log from " + collected.fileCount() + " node(s)");
        } catch (RuntimeException e) {
            log.warn("Archiving log of engine job {} failed: {}", engineJobId, e.toString());
            recorder.event(instance.getId(), attempt.getAttemptNo(), RunEventType.LOG_ARCHIVED, Level.WARN,
                    "could not archive SeaTunnel log: " + e.getMessage());
        }
    }

    /**
     * Renders the job version for the instance's scheduled time, with real credentials for
     * submission and a masked copy for the record. From here on variables_json holds the final
     * value of every variable; the startup params stay in startup_params_json.
     */
    /**
     * SeaTunnel does not say why it rejected a job; most often a source query fails. Running the
     * same queries here usually names the actual database error. Empty when nothing is found.
     */
    private String rejectionCause(Prepared prepared) {
        try {
            List<String> errors = scriptChecker.check(prepared.rendered().template()).stream()
                    .filter(c -> c.level() == WizardService.Level.ERROR)
                    .map(WizardService.Check::message)
                    .toList();
            return errors.isEmpty() ? "" : "\nconsole 检查发现：" + String.join("；", errors);
        } catch (RuntimeException e) {
            log.warn("Could not look for the rejection cause: {}", e.toString());
            return "";
        }
    }

    /** What prepare() worked out for the run: the submission, plus what validation needs afterwards. */
    private record Prepared(SubmitJobRequest request, JobConfigRenderer.Rendered rendered, ValidationSpec validation) {}

    /**
     * Counts source and target rows if the version asks for it (docs/design.md §10.3). The result is
     * recorded on the instance; a failed count is reported but never fails the run.
     */
    private RowCountValidator.Result validate(JobInstance instance, Prepared prepared, Consumer<String> schedulerLog) {
        ValidationSpec spec = prepared.validation();
        if (spec == null || !spec.enabled()) {
            instance.setValidationStatus("SKIPPED");
            return null;
        }
        RowCountValidator.Result result;
        try {
            result = validator.validate(spec, prepared.rendered().template(), prepared.rendered().variables(), instance.getScheduledTime());
        } catch (RuntimeException e) {
            log.warn("Validation of run {} failed unexpectedly", instance.getId(), e);
            result = new RowCountValidator.Result(RowCountValidator.Status.ERROR, null, null, e.toString(), null, null);
        }
        instance.setValidationStatus(result.status().name());
        instance.setSourceCount(result.sourceCount());
        instance.setTargetCount(result.targetCount());
        instance.setValidationMessage(result.message() == null ? null : result.message().substring(0, Math.min(1024, result.message().length())));
        recorder.update(instance);
        recorder.event(instance.getId(), instance.getAttemptCount(), RunEventType.VALIDATED,
                result.status() == RowCountValidator.Status.PASSED ? Level.INFO : Level.WARN,
                "row count " + result.status() + ": " + result.message()
                        + (result.sourceQuery() == null ? "" : "\nsource: " + result.sourceQuery())
                        + (result.targetQuery() == null ? "" : "\ntarget: " + result.targetQuery()));
        schedulerLog.accept("row count " + result.status() + ": " + result.message());
        return result;
    }

    private Prepared prepare(JobInstance instance) {
        JobVersion version = jobService.requireVersion(instance.getJobVersionId());
        JobDefinition job = jobService.require(instance.getJobDefinitionId());
        Map<String, String> startupParams = instance.getStartupParamsJson() == null
                ? Map.of()
                : objectMapper.readValue(instance.getStartupParamsJson(), STRING_MAP);
        JobConfigRenderer.Rendered rendered = renderer.render(
                job, version.getHoconTemplate(), jobService.params(version), startupParams,
                instance.getScheduledTime(), instance.getId(), true);
        instance.setConfigMasked(rendered.masked());
        instance.setVariablesJson(objectMapper.writeValueAsString(rendered.variables()));
        recorder.update(instance);
        if (!rendered.unresolved().isEmpty()) {
            recorder.event(instance.getId(), null, RunEventType.CREATED, Level.WARN,
                    "not console variables, left for HOCON to resolve (check for typos): "
                            + String.join(", ", rendered.unresolved()));
        }
        return new Prepared(
                SubmitJobRequest.fresh(job.getName() + "#" + instance.getId(), rendered.config()),
                rendered,
                jobService.validation(version));
    }

    private JobInstance startPreCreated(ScheduledTrigger trigger) {
        JobInstance instance = recorder.requireInstance(trigger.instanceId());
        if (!RunStatus.PENDING.name().equals(instance.getStatus())) {
            throw new ConsoleException(
                    ErrorCodes.CONFLICT, "run instance " + instance.getId() + " is already " + instance.getStatus());
        }
        return instance;
    }

    private JobInstance createScheduled(ScheduledTrigger trigger) {
        JobDefinition job = jobService.require(trigger.jobDefinitionId());
        if (job.getCurrentVersionId() == null) {
            throw new ConsoleException(ErrorCodes.INVALID_ARGUMENT, "job " + job.getName() + " has no saved version");
        }
        JobInstance instance = new JobInstance();
        instance.setProjectId(job.getProjectId());
        instance.setJobDefinitionId(job.getId());
        instance.setJobVersionId(job.getCurrentVersionId());
        instance.setTriggerType("SCHEDULE");
        instance.setScheduledTime(trigger.scheduledTime());
        instance.setStatus(RunStatus.PENDING.name());
        instance.setAttemptCount(0);
        instance.setMaxAttempts(scheduleRetries(job.getId()) + 1);
        instance.setEngineType(engineClient.type().name());
        String startup = trigger.startupParams().isEmpty() ? null : objectMapper.writeValueAsString(trigger.startupParams());
        instance.setStartupParamsJson(startup);
        instance.setVariablesJson(startup);
        recorder.insert(instance);
        recorder.event(instance.getId(), null, RunEventType.CREATED, Level.INFO, "scheduled run triggered by XXL-JOB");
        return instance;
    }

    private int scheduleRetries(String jobId) {
        JobSchedule schedule = scheduleMapper.selectById(jobId);
        return schedule == null ? 0 : Math.max(0, schedule.getFailRetryCount());
    }

    private RetryPolicy retryPolicy(JobInstance instance) {
        JobSchedule schedule = scheduleMapper.selectById(instance.getJobDefinitionId());
        int seconds = schedule == null || schedule.getRetryIntervalSeconds() == null ? 60 : schedule.getRetryIntervalSeconds();
        return new RetryPolicy(Duration.ofSeconds(Math.max(0, seconds)));
    }

    private record RetryPolicy(Duration interval) {}

    private RunResult finish(JobInstance instance, RunStatus status, String message, Consumer<String> schedulerLog) {
        instance.setStatus(status.name());
        instance.setFinishedAt(LocalDateTime.now());
        instance.setErrorMessage(status == RunStatus.SUCCESS ? null : RunRecorder.clip(message));
        recorder.update(instance);
        recorder.event(instance.getId(), instance.getAttemptCount(), RunEventType.FINISHED,
                switch (status) {
                    case SUCCESS -> Level.INFO;
                    case CANCELED -> Level.WARN;
                    default -> Level.ERROR;
                },
                status + ": " + message);
        schedulerLog.accept(status + ": " + message);
        return new RunResult(instance.getId(), status, message);
    }

    private void applyMetrics(JobInstance instance, Map<String, String> raw) {
        EngineMetrics metrics = EngineMetrics.parse(raw, objectMapper);
        instance.setReadRows(metrics.readRows());
        instance.setWriteRows(metrics.writeRows());
        instance.setReadBytes(metrics.readBytes());
        instance.setWriteBytes(metrics.writeBytes());
    }

    private static void awaitRetry(Duration interval, CancelSignal signal) {
        try {
            signal.await(interval);
        } catch (InterruptedException e) {
            signal.cancelByInterrupt("interrupted by XXL-JOB (timeout or kill) while waiting to retry");
        }
    }

    /** Persists progress so the instance page shows the engine job id and status while running. */
    private final class RecordingListener implements BatchJobRunner.Listener {

        private final String instanceId;
        private final JobInstanceAttempt attempt;
        private final Consumer<String> schedulerLog;
        private EngineJobStatus lastStatus;

        RecordingListener(String instanceId, JobInstanceAttempt attempt, Consumer<String> schedulerLog) {
            this.instanceId = instanceId;
            this.attempt = attempt;
            this.schedulerLog = schedulerLog;
        }

        @Override
        public void onSubmitted(long engineJobId) {
            attempt.setEngineJobId(String.valueOf(engineJobId));
            attempt.setSubmittedAt(LocalDateTime.now());
            recorder.update(attempt);
            recorder.event(instanceId, attempt.getAttemptNo(), RunEventType.SUBMITTED, Level.INFO,
                    "submitted to SeaTunnel, engine job id " + engineJobId);
            schedulerLog.accept("submitted to SeaTunnel, engine job id " + engineJobId);
        }

        @Override
        public void onProgress(EngineJobInfo info) {
            if (!info.metrics().isEmpty()) {
                attempt.setMetricsJson(objectMapper.writeValueAsString(info.metrics()));
                recorder.update(attempt);
                recorder.progress(instanceId, attempt.getAttemptNo(), EngineMetrics.parse(info.metrics(), objectMapper));
            }
            if (info.status() == lastStatus) {
                return;
            }
            lastStatus = info.status();
            attempt.setEngineStatus(info.status().name());
            recorder.update(attempt);
            recorder.event(instanceId, attempt.getAttemptNo(), RunEventType.STATUS_CHANGED, Level.INFO,
                    "engine status " + info.status());
            schedulerLog.accept("engine status " + info.status());
        }

        @Override
        public void onWarning(String message) {
            recorder.event(instanceId, attempt.getAttemptNo(), RunEventType.ERROR, Level.WARN, message);
            schedulerLog.accept(message);
        }
    }
}
