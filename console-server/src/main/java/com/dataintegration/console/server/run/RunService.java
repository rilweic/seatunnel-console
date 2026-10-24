package com.dataintegration.console.server.run;

import com.dataintegration.console.common.ConsoleException;
import com.dataintegration.console.common.ErrorCodes;
import com.dataintegration.console.engine.EngineClient;
import com.dataintegration.console.server.auth.CurrentUser;
import com.dataintegration.console.server.job.JobService;
import com.dataintegration.console.server.job.ScheduleService;
import com.dataintegration.console.server.job.Variables;
import com.dataintegration.console.server.persistence.entity.JobDefinition;
import com.dataintegration.console.server.persistence.entity.JobInstance;
import com.dataintegration.console.server.run.RunRecorder.Level;
import com.dataintegration.console.server.scheduler.ScheduledTrigger;
import com.dataintegration.console.server.scheduler.TriggerParam;
import com.dataintegration.console.server.scheduler.XxlJobAdminClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Starts runs on user request (docs/schema.md §4.1): the instance is created first as PENDING, then
 * handed to XXL-JOB so its timeout and block strategy apply. Without XXL-JOB (console.xxl-job.enabled
 * = false) the run executes on a local thread pool instead. Covers manual runs, reruns and the
 * runs of a backfill, and stops runs on user request.
 */
@Service
public class RunService {

    private static final Logger log = LoggerFactory.getLogger(RunService.class);

    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {};

    private final JobService jobService;
    private final ScheduleService scheduleService;
    private final RunRecorder recorder;
    private final RunExecutor runExecutor;
    private final EngineClient engineClient;
    private final ObjectProvider<XxlJobAdminClient> xxlJobAdminClient;
    private final ObjectMapper objectMapper;
    private final TaskExecutor localExecutor;

    public RunService(
            JobService jobService,
            ScheduleService scheduleService,
            RunRecorder recorder,
            RunExecutor runExecutor,
            EngineClient engineClient,
            ObjectProvider<XxlJobAdminClient> xxlJobAdminClient,
            ObjectMapper objectMapper) {
        this.jobService = jobService;
        this.scheduleService = scheduleService;
        this.recorder = recorder;
        this.runExecutor = runExecutor;
        this.engineClient = engineClient;
        this.xxlJobAdminClient = xxlJobAdminClient;
        this.objectMapper = objectMapper;
        this.localExecutor = localPool();
    }

    /** Trigger types a user or the backfill driver starts; SCHEDULE runs are created by the handler. */
    public enum TriggerType {
        MANUAL,
        RERUN,
        BACKFILL
    }

    /**
     * Everything a new run is made of, fixed before XXL-JOB starts it.
     *
     * @param maxAttempts 1 + retries; retries only apply where nobody is watching (backfill)
     * @param createdMessage first line of the run's timeline
     */
    public record NewRun(
            JobDefinition job,
            String versionId,
            TriggerType triggerType,
            LocalDateTime scheduledTime,
            Map<String, String> startupParams,
            int maxAttempts,
            String rerunOfInstanceId,
            String backfillBatchId,
            String createdMessage) {}

    /**
     * Runs the job's current version now. {@code scheduledTime} is the base time for variables,
     * now when null. Returns the PENDING instance id.
     */
    public String runNow(String jobId, LocalDateTime scheduledTime, Map<String, String> startupParams) {
        JobDefinition job = jobService.requireEditable(jobId);
        if (job.getCurrentVersionId() == null) {
            throw new ConsoleException(ErrorCodes.INVALID_ARGUMENT, "save a version of the job before running it");
        }
        return start(new NewRun(
                job,
                job.getCurrentVersionId(),
                TriggerType.MANUAL,
                scheduledTime == null ? LocalDateTime.now() : scheduledTime,
                startupParams,
                1,
                null,
                null,
                "manual run by " + username()));
    }

    /**
     * Runs a finished instance again for the same scheduled time and startup params, with its own
     * version or, if asked, the job's current one (docs/design.md §9).
     */
    public String rerun(String instanceId, boolean useLatestVersion) {
        JobInstance original = recorder.requireInstance(instanceId);
        if (!RunStatus.valueOf(original.getStatus()).isTerminal()) {
            throw new ConsoleException(ErrorCodes.CONFLICT, "run is still " + original.getStatus() + ", stop it or wait for it to end");
        }
        JobDefinition job = jobService.requireEditable(original.getJobDefinitionId());
        String versionId = useLatestVersion ? job.getCurrentVersionId() : original.getJobVersionId();
        return start(new NewRun(
                job,
                versionId,
                TriggerType.RERUN,
                original.getScheduledTime(),
                startupParams(original.getStartupParamsJson()),
                1,
                original.getId(),
                null,
                "rerun of " + original.getId() + " by " + username()
                        + (useLatestVersion ? ", with the current version" : ", with the original version")));
    }

    /** True while the job has a run that XXL-JOB has not finished, whatever started it. */
    public boolean hasActiveRun(String jobId) {
        return recorder.countActive(jobId) > 0;
    }

    /**
     * Creates the PENDING instance and hands it to XXL-JOB, or to the local pool when XXL-JOB is
     * disabled. If XXL-JOB cannot take it, the instance is marked FAILED and the error rethrown.
     */
    public String start(NewRun run) {
        return start(run, () -> true);
    }

    /**
     * @param stillWanted asked after the run is recorded and right before it is handed to XXL-JOB;
     *     false cancels it there. A backfill uses it so a cancel that came in meanwhile, and so found
     *     no run to stop yet, still prevents the run.
     */
    public String start(NewRun run, BooleanSupplier stillWanted) {
        if (run.startupParams() != null) {
            run.startupParams().keySet().forEach(Variables::validateName);
        }
        JobDefinition job = run.job();
        String startup = run.startupParams() == null || run.startupParams().isEmpty()
                ? null
                : objectMapper.writeValueAsString(run.startupParams());
        JobInstance instance = new JobInstance();
        instance.setProjectId(job.getProjectId());
        instance.setJobDefinitionId(job.getId());
        instance.setJobVersionId(run.versionId());
        instance.setTriggerType(run.triggerType().name());
        instance.setScheduledTime(run.scheduledTime());
        instance.setStatus(RunStatus.PENDING.name());
        instance.setAttemptCount(0);
        instance.setMaxAttempts(Math.max(1, run.maxAttempts()));
        instance.setRerunOfInstanceId(run.rerunOfInstanceId());
        instance.setBackfillBatchId(run.backfillBatchId());
        instance.setEngineType(engineClient.type().name());
        instance.setStartupParamsJson(startup);
        // Shown as the run's variables until it starts and the final values replace them.
        instance.setVariablesJson(startup);
        instance.setCreatedBy(CurrentUser.idOrNull());
        recorder.insert(instance);
        recorder.event(instance.getId(), null, RunEventType.CREATED, Level.INFO, run.createdMessage());
        if (!stillWanted.getAsBoolean()) {
            if (recorder.cancelPending(instance.getId(), "no longer wanted before it was started")) {
                recorder.event(instance.getId(), null, RunEventType.FINISHED, Level.WARN, RunStatus.CANCELED + ": no longer wanted");
            }
            return instance.getId();
        }

        XxlJobAdminClient client = xxlJobAdminClient.getIfAvailable();
        if (client == null) {
            ScheduledTrigger trigger = new ScheduledTrigger(
                    job.getId(), instance.getId(), instance.getScheduledTime(), run.startupParams(), 0, 0);
            localExecutor.execute(() -> runExecutor.execute(trigger, line -> log.info("[run {}] {}", instance.getId(), line)));
            return instance.getId();
        }
        TriggerParam param = new TriggerParam(job.getId(), instance.getId(), instance.getScheduledTime(), run.startupParams());
        try {
            try {
                client.triggerJob(scheduleService.ensureXxlJob(job, client), param.toJson(objectMapper));
            } catch (ConsoleException e) {
                if (e.getCode() != ErrorCodes.SCHEDULER_JOB_MISSING) {
                    throw e;
                }
                // Deleted in xxl-job-admin since: recreate it (with its schedule) and trigger once more.
                recorder.event(instance.getId(), null, RunEventType.CREATED, Level.WARN,
                        "XXL-JOB job " + job.getXxlJobId() + " no longer exists in xxl-job-admin, recreated it");
                client.triggerJob(scheduleService.recreateXxlJob(job, client), param.toJson(objectMapper));
            }
        } catch (ConsoleException e) {
            instance.setStatus(RunStatus.FAILED.name());
            instance.setFinishedAt(LocalDateTime.now());
            instance.setErrorMessage("could not hand the run to XXL-JOB: " + e.getMessage());
            recorder.update(instance);
            recorder.event(instance.getId(), null, RunEventType.ERROR, Level.ERROR, instance.getErrorMessage());
            throw e;
        }
        return instance.getId();
    }

    public Map<String, String> startupParams(String json) {
        return json == null ? Map.of() : objectMapper.readValue(json, STRING_MAP);
    }

    private static String username() {
        return CurrentUser.get().map(u -> u.username()).orElse("unknown");
    }

    /**
     * Stops a run. A PENDING run is canceled before XXL-JOB starts it; a RUNNING one is signalled,
     * and its run thread stops the SeaTunnel job and records the instance as CANCELED.
     */
    public void stop(String instanceId) {
        String reason = "stopped by " + username();
        jobService.requireEditable(recorder.requireInstance(instanceId).getJobDefinitionId());
        if (recorder.cancelPending(instanceId, reason)) {
            recorder.event(instanceId, null, RunEventType.FINISHED, Level.WARN, RunStatus.CANCELED + ": " + reason);
            return;
        }
        JobInstance instance = recorder.requireInstance(instanceId);
        if (RunStatus.RUNNING.name().equals(instance.getStatus()) && runExecutor.cancel(instanceId, reason)) {
            recorder.event(instanceId, instance.getAttemptCount(), RunEventType.CANCEL_REQUESTED, Level.WARN,
                    reason + ", stopping the SeaTunnel job");
            return;
        }
        if (RunStatus.valueOf(instance.getStatus()).isTerminal()) {
            throw new ConsoleException(ErrorCodes.CONFLICT, "run already ended as " + instance.getStatus());
        }
        throw new ConsoleException(ErrorCodes.CONFLICT, "run is not being driven by this console process");
    }

    private static TaskExecutor localPool() {
        ThreadPoolTaskExecutor pool = new ThreadPoolTaskExecutor();
        pool.setThreadNamePrefix("local-run-");
        pool.setCorePoolSize(4);
        pool.setMaxPoolSize(4);
        pool.setQueueCapacity(100);
        pool.initialize();
        return pool;
    }
}
