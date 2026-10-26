package com.dataintegration.console.server.run;

import com.dataintegration.console.server.persistence.entity.JobInstanceAttempt;
import com.dataintegration.console.server.run.RunRecorder.Level;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Pulls an attempt's SeaTunnel log from all engine nodes before Zeta deletes it (after one day by
 * default) and keeps it with the attempt, for batch runs and streaming runs alike.
 */
@Component
public class AttemptLogArchiver {

    private static final Logger log = LoggerFactory.getLogger(AttemptLogArchiver.class);

    private final RunRecorder recorder;
    private final EngineLogService engineLogService;
    private final LogStorage logStorage;

    public AttemptLogArchiver(RunRecorder recorder, EngineLogService engineLogService, LogStorage logStorage) {
        this.recorder = recorder;
        this.engineLogService = engineLogService;
        this.logStorage = logStorage;
    }

    /** Returns the archived text, or null when there was none or it could not be read. */
    public String archive(String instanceId, JobInstanceAttempt attempt, long engineJobId) {
        try {
            EngineLogService.CollectedLog collected = engineLogService.collect(engineJobId);
            if (collected.fileCount() == 0) {
                recorder.event(instanceId, attempt.getAttemptNo(), RunEventType.LOG_ARCHIVED, Level.WARN,
                        "SeaTunnel returned no job log; enable per-job logs on every node "
                                + "(rootLogger.appenderRef.file.ref = routingAppender in config/log4j2.properties)");
                return null;
            }
            attempt.setLogStorageKey(logStorage.write(attempt.getId(), LocalDate.now(), collected.text()));
            attempt.setLogSizeBytes((long) collected.text().getBytes(StandardCharsets.UTF_8).length);
            attempt.setLogTruncated(collected.truncated());
            attempt.setLogArchivedAt(LocalDateTime.now());
            recorder.update(attempt);
            recorder.event(instanceId, attempt.getAttemptNo(), RunEventType.LOG_ARCHIVED, Level.INFO,
                    "archived SeaTunnel log from " + collected.fileCount() + " node(s)");
            return collected.text();
        } catch (RuntimeException e) {
            log.warn("Archiving log of engine job {} failed: {}", engineJobId, e.toString());
            recorder.event(instanceId, attempt.getAttemptNo(), RunEventType.LOG_ARCHIVED, Level.WARN,
                    "could not archive SeaTunnel log: " + e.getMessage());
            return null;
        }
    }
}
