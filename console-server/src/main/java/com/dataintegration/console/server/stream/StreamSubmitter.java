package com.dataintegration.console.server.stream;

import com.dataintegration.console.common.ConsoleException;
import com.dataintegration.console.common.ErrorCodes;
import com.dataintegration.console.engine.EngineClient;
import com.dataintegration.console.engine.SubmitJobRequest;
import com.dataintegration.console.engine.SubmitJobRequest.RestoreMode;
import com.dataintegration.console.server.job.JobConfigRenderer;
import com.dataintegration.console.server.job.JobService;
import com.dataintegration.console.server.persistence.entity.JobDefinition;
import com.dataintegration.console.server.persistence.entity.JobInstance;
import com.dataintegration.console.server.persistence.entity.JobInstanceAttempt;
import com.dataintegration.console.server.persistence.entity.JobVersion;
import com.dataintegration.console.server.run.RunEventType;
import com.dataintegration.console.server.run.RunRecorder;
import com.dataintegration.console.server.run.RunRecorder.Level;
import com.dataintegration.console.server.scheduler.BatchJobRunner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Renders a streaming run's config in memory (credentials and the job's CDC identity put in) and
 * submits it as one attempt. The rendered config is never stored or logged; the masked copy goes on
 * the run. A submission whose answer was lost counts as submitted: the monitor looks the job up by
 * the console-chosen id and fails the run if the engine never had it.
 */
@Component
public class StreamSubmitter {

    private static final Logger log = LoggerFactory.getLogger(StreamSubmitter.class);
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {};

    private final JobConfigRenderer renderer;
    private final JobService jobService;
    private final StreamIdentityService identity;
    private final EngineClient engine;
    private final RunRecorder recorder;
    private final ObjectMapper objectMapper;

    public StreamSubmitter(JobConfigRenderer renderer, JobService jobService, StreamIdentityService identity, EngineClient engine,
            RunRecorder recorder, ObjectMapper objectMapper) {
        this.renderer = renderer;
        this.jobService = jobService;
        this.identity = identity;
        this.engine = engine;
        this.recorder = recorder;
        this.objectMapper = objectMapper;
    }

    /** The run's config with secrets, for submission and the start checks; records the masked copy on the run. */
    public JobConfigRenderer.Rendered render(JobDefinition job, JobVersion version, JobInstance instance) {
        Map<String, String> startup = instance.getStartupParamsJson() == null
                ? Map.of() : objectMapper.readValue(instance.getStartupParamsJson(), STRING_MAP);
        JobConfigRenderer.Rendered rendered = renderer.render(job, version.getHoconTemplate(), jobService.params(version), startup,
                instance.getScheduledTime(), instance.getId(), null, true, identity.extras(job.getId()));
        instance.setConfigMasked(rendered.masked());
        instance.setVariablesJson(objectMapper.writeValueAsString(rendered.variables()));
        recorder.update(instance);
        return rendered;
    }

    /**
     * Submits the attempt and records its engine job id.
     *
     * @throws ConsoleException when the engine certainly did not get the job
     */
    public long submit(JobDefinition job, JobInstance instance, JobInstanceAttempt attempt, String config, RestoreMode mode, Long from) {
        // a savepoint restore runs under the old id; anything else under one chosen here, so a lost
        // answer can be looked up
        long id = mode == RestoreMode.SAVEPOINT ? from : BatchJobRunner.newJobId();
        SubmitJobRequest request = new SubmitJobRequest(job.getName() + "#" + instance.getId(), config, mode, from,
                mode == RestoreMode.SAVEPOINT ? null : id);
        long engineJobId;
        try {
            engineJobId = engine.submit(request);
        } catch (ConsoleException e) {
            if (e.getCode() != ErrorCodes.ENGINE_RESPONSE_LOST) {
                attempt.setEngineStatus("FAILED");
                attempt.setErrorMessage(RunRecorder.clip(e.getMessage()));
                attempt.setFinishedAt(LocalDateTime.now());
                recorder.update(attempt);
                throw e;
            }
            log.warn("No answer to the submission of streaming run {} (engine job {}): {}", instance.getId(), id, e.getMessage());
            recorder.event(instance.getId(), attempt.getAttemptNo(), RunEventType.SUBMITTED, Level.WARN,
                    "提交没有收到回应，按引擎作业 " + id + " 继续观察：" + e.getMessage());
            engineJobId = id;
        }
        attempt.setEngineJobId(String.valueOf(engineJobId));
        attempt.setSubmittedAt(LocalDateTime.now());
        recorder.update(attempt);
        recorder.event(instance.getId(), attempt.getAttemptNo(), RunEventType.SUBMITTED, Level.INFO,
                "已提交到 SeaTunnel，引擎作业 " + engineJobId);
        return engineJobId;
    }
}
