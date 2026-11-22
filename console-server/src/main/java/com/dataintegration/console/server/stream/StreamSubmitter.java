package com.dataintegration.console.server.stream;

import com.dataintegration.console.common.ConsoleException;
import com.dataintegration.console.common.ErrorCodes;
import com.dataintegration.console.engine.EngineClient;
import com.dataintegration.console.engine.EngineType;
import com.dataintegration.console.engine.SubmitJobRequest;
import com.dataintegration.console.engine.SubmitJobRequest.RestoreMode;
import com.dataintegration.console.server.cluster.EngineRegistry;
import com.dataintegration.console.server.job.JobConfigRenderer;
import com.dataintegration.console.server.job.JobService;
import com.dataintegration.console.server.persistence.entity.JobDefinition;
import com.dataintegration.console.server.persistence.entity.JobInstance;
import com.dataintegration.console.server.persistence.entity.JobInstanceAttempt;
import com.dataintegration.console.server.persistence.entity.JobVersion;
import com.dataintegration.console.server.run.RunEventType;
import com.dataintegration.console.server.run.RunRecorder;
import com.dataintegration.console.server.run.RunRecorder.Level;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Renders a streaming run's config in memory (credentials and the job's CDC identity put in) and
 * submits it as one attempt. The rendered config is never stored or logged; the masked copy goes on
 * the run. The engine job id is recorded before the engine is called, so a console that dies before
 * the answer still looks the job up after its restart. A submission whose answer was lost counts as
 * submitted: the monitor looks the job up by that id and fails the run if the engine never had it.
 */
@Component
public class StreamSubmitter {

    /** @param confirmed true when the engine explicitly acknowledged the submission; false on RESPONSE_LOST */
    public record SubmitResult(String engineJobId, boolean confirmed) {}

    private static final Logger log = LoggerFactory.getLogger(StreamSubmitter.class);
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {};

    private final JobConfigRenderer renderer;
    private final JobService jobService;
    private final StreamIdentityService identity;
    private final EngineRegistry engines;
    private final RunRecorder recorder;
    private final ObjectMapper objectMapper;
    private final Set<String> inFlightAttempts = ConcurrentHashMap.newKeySet();

    public StreamSubmitter(JobConfigRenderer renderer, JobService jobService, StreamIdentityService identity, EngineRegistry engines,
            RunRecorder recorder, ObjectMapper objectMapper) {
        this.renderer = renderer;
        this.jobService = jobService;
        this.identity = identity;
        this.engines = engines;
        this.recorder = recorder;
        this.objectMapper = objectMapper;
    }

    /** True while a thread in this console process is still queuing or running the submission for {@code attemptId}. */
    public boolean isSubmitting(String attemptId) {
        return attemptId != null && inFlightAttempts.contains(attemptId);
    }

    /** The run's config with secrets, for submission and the start checks; records the masked copy on the run. */
    public JobConfigRenderer.Rendered render(JobDefinition job, JobVersion version, JobInstance instance) {
        Map<String, String> startup = instance.getStartupParamsJson() == null
                ? Map.of() : objectMapper.readValue(instance.getStartupParamsJson(), STRING_MAP);
        JobConfigRenderer.Rendered rendered = renderer.render(job, version.getHoconTemplate(), jobService.params(version), startup,
                instance.getScheduledTime(), instance.getId(), null, true, identity.extras(job.getId()));
        instance.setConfigMasked(rendered.masked());
        instance.setVariablesJson(objectMapper.writeValueAsString(rendered.variables()));
        recorder.config(instance);
        return rendered;
    }

    /**
     * Submits the attempt. Its engine job id is set before the call and its submitted time once the
     * engine took the job (or the answer was lost); a stop waits for the latter.
     *
     * @throws ConsoleException when the engine certainly did not get the job
     */
    public SubmitResult submit(
            JobDefinition job,
            JobInstance instance,
            JobInstanceAttempt attempt,
            String config,
            RestoreMode mode,
            String from,
            String restorePath) {
        EngineClient engine = engines.clientFor(job.getClusterId());
        // On Zeta a savepoint restore runs under the old id; on Flink (and Zeta checkpoint/fresh)
        // a fresh id is chosen here so a lost answer can be looked up.
        boolean keepOldId = engine.type() == EngineType.ZETA && mode == RestoreMode.SAVEPOINT && from != null;
        String id = keepOldId ? from : engine.newJobId();
        SubmitJobRequest request = new SubmitJobRequest(
                job.getName() + "#" + instance.getId(),
                config,
                mode,
                from,
                restorePath,
                keepOldId ? null : id);
        inFlightAttempts.add(attempt.getId());
        try {
            attempt.setEngineJobId(id);
            recorder.update(attempt);
            String engineJobId;
            boolean confirmed = true;
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
                confirmed = false;
                log.warn("No answer to the submission of streaming run {} (engine job {}): {}", instance.getId(), id, e.getMessage());
                recorder.event(instance.getId(), attempt.getAttemptNo(), RunEventType.SUBMITTED, Level.WARN,
                        "提交没有收到回应，按引擎作业 " + id + " 继续观察：" + e.getMessage());
                engineJobId = id;
            }
            attempt.setEngineJobId(engineJobId);
            attempt.setSubmittedAt(LocalDateTime.now());
            recorder.update(attempt);
            recorder.event(instance.getId(), attempt.getAttemptNo(), RunEventType.SUBMITTED, Level.INFO,
                    "已提交到 " + engine.type() + "，引擎作业 " + engineJobId);
            return new SubmitResult(engineJobId, confirmed);
        } finally {
            inFlightAttempts.remove(attempt.getId());
        }
    }
}
