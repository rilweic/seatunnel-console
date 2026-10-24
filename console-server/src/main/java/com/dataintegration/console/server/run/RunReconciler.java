package com.dataintegration.console.server.run;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataintegration.console.server.persistence.entity.JobInstance;
import com.dataintegration.console.server.persistence.mapper.JobInstanceMapper;
import com.dataintegration.console.server.run.RunRecorder.Level;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Closes runs nobody is driving any more, so they do not stay PENDING or RUNNING forever:
 *
 * <ul>
 *   <li>at startup, RUNNING runs belonged to the previous process (the console is single-instance)
 *       and are marked FAILED; the engine job may still be running and is named in the message
 *   <li>PENDING runs that XXL-JOB never started within the grace period are marked FAILED
 * </ul>
 */
@Component
public class RunReconciler {

    private static final Logger log = LoggerFactory.getLogger(RunReconciler.class);

    private final JobInstanceMapper instanceMapper;
    private final RunRecorder recorder;
    private final Duration pendingGrace;

    public RunReconciler(
            JobInstanceMapper instanceMapper,
            RunRecorder recorder,
            @Value("${console.run.pending-grace:10m}") Duration pendingGrace) {
        this.instanceMapper = instanceMapper;
        this.recorder = recorder;
        this.pendingGrace = pendingGrace;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void failOrphanedRuns() {
        List<JobInstance> running = instanceMapper.selectList(
                new LambdaQueryWrapper<JobInstance>().eq(JobInstance::getStatus, RunStatus.RUNNING.name()));
        for (JobInstance instance : running) {
            fail(instance, "console restarted while this run was being monitored; "
                    + "check the SeaTunnel cluster for the engine job of the last attempt");
        }
        if (!running.isEmpty()) {
            log.warn("Marked {} run(s) interrupted by a console restart as FAILED", running.size());
        }
    }

    @Scheduled(fixedDelayString = "${console.run.reconcile-interval:60s}")
    public void failStalePending() {
        LocalDateTime cutoff = LocalDateTime.now().minus(pendingGrace);
        List<JobInstance> stale = instanceMapper.selectList(
                new LambdaQueryWrapper<JobInstance>()
                        .eq(JobInstance::getStatus, RunStatus.PENDING.name())
                        .lt(JobInstance::getCreatedAt, cutoff));
        for (JobInstance instance : stale) {
            fail(instance, "XXL-JOB did not start this run within " + pendingGrace.toMinutes()
                    + " minutes; check the XXL-JOB scheduling log and that the executor is online");
        }
    }

    private void fail(JobInstance instance, String message) {
        instance.setStatus(RunStatus.FAILED.name());
        instance.setFinishedAt(LocalDateTime.now());
        instance.setErrorMessage(message);
        recorder.update(instance);
        recorder.event(instance.getId(), instance.getAttemptCount(), RunEventType.ERROR, Level.ERROR, message);
    }
}
