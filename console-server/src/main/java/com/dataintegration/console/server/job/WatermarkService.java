package com.dataintegration.console.server.job;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.dataintegration.console.common.ConsoleException;
import com.dataintegration.console.common.ErrorCodes;
import com.dataintegration.console.server.job.wizard.WizardForm;
import com.dataintegration.console.server.persistence.entity.JobDefinition;
import com.dataintegration.console.server.persistence.entity.JobVersion;
import com.dataintegration.console.server.persistence.mapper.JobDefinitionMapper;

import org.springframework.stereotype.Service;

import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/**
 * Continuous incremental sync (docs/design.md §7.5). A run whose config reads
 * {@code ${system.window.start}} / {@code ${system.window.end}} gets a window fixed on its instance;
 * when it succeeds the job's watermark moves to the window's end, but only if the window started at
 * the watermark and the watermark was not set by hand since the run read the job (its generation).
 * That one conditional update is the whole state machine: a failed window is picked up by the next
 * run, a rerun repeats its own window and moves the watermark only while nothing has moved it since,
 * and a reset is never overwritten by a run that started before it, even a reset to the same value.
 */
@Service
public class WatermarkService {

    /** Longest lag that can be set: a day. */
    static final int MAX_LAG_SECONDS = 86_400;

    /**
     * @param window the wizard's incremental window of the current version (CONTINUOUS, DAY, HOUR,
     *     CUSTOM), null for full sync or a script
     * @param usesWindow the current version reads the sync window, so it has a watermark
     * @param watermark null = the next run reads from the beginning
     * @param nextStart null = from the beginning
     */
    public record IncrementalView(
            String window,
            boolean usesWindow,
            LocalDateTime watermark,
            int lagSeconds,
            LocalDateTime nextStart,
            LocalDateTime nextEnd) {}

    private final JobService jobService;
    private final JobConfigRenderer renderer;
    private final JobDefinitionMapper jobMapper;
    private final ObjectMapper objectMapper;

    public WatermarkService(JobService jobService, JobConfigRenderer renderer, JobDefinitionMapper jobMapper, ObjectMapper objectMapper) {
        this.jobService = jobService;
        this.renderer = renderer;
        this.jobMapper = jobMapper;
        this.objectMapper = objectMapper;
    }

    public IncrementalView view(String jobId) {
        return view(jobService.require(jobId));
    }

    /**
     * Sets where the next run starts (null = from the beginning) and the lag. Runs already going keep
     * their windows; when they succeed they no longer join on, so they leave the new watermark alone.
     */
    public IncrementalView update(String jobId, LocalDateTime watermark, Integer lagSeconds) {
        JobDefinition job = jobService.requireEditable(jobId);
        int lag = lagSeconds == null ? JobConfigRenderer.lagSeconds(job) : lagSeconds;
        if (lag < 0 || lag > MAX_LAG_SECONDS) {
            throw new ConsoleException(ErrorCodes.INVALID_ARGUMENT, "延迟要在 0 到 " + MAX_LAG_SECONDS / 60 + " 分钟之间");
        }
        LocalDateTime mark = watermark == null ? null : watermark.truncatedTo(ChronoUnit.SECONDS);
        if (mark != null && mark.isAfter(LocalDateTime.now())) {
            throw new ConsoleException(ErrorCodes.INVALID_ARGUMENT, "水位不能晚于现在");
        }
        jobMapper.update(null, new LambdaUpdateWrapper<JobDefinition>()
                .set(JobDefinition::getWatermark, mark)
                .set(JobDefinition::getWindowLagSeconds, lag)
                .setSql("watermark_gen = watermark_gen + 1")
                .eq(JobDefinition::getId, jobId));
        return view(jobService.require(jobId));
    }

    /**
     * The window of a backfill run of a version that reads it: the interval between the schedule's
     * previous fire time and this one, both moved back by the lag, so the runs of a batch cover the
     * range without gaps or overlaps. Null when the version does not read the window.
     */
    public SyncWindow backfillWindow(JobDefinition job, JobVersion version, Map<String, String> startupParams, String cron, LocalDateTime time) {
        if (!renderer.usesWindow(job, version.getHoconTemplate(), jobService.params(version), startupParams)) {
            return null;
        }
        int lag = JobConfigRenderer.lagSeconds(job);
        return new SyncWindow(SyncWindow.endFor(ScheduleService.previousTime(cron, time), lag), SyncWindow.endFor(time, lag));
    }

    /**
     * Moves the watermark to the end of a window that succeeded, if it still joins on.
     *
     * @param gen the job's {@code watermark_gen} when the run read it
     */
    public boolean advance(String jobId, SyncWindow window, int gen) {
        return jobMapper.advanceWatermark(jobId, window.start(), window.end(), gen) > 0;
    }

    private IncrementalView view(JobDefinition job) {
        String window = null;
        boolean uses = false;
        if (job.getCurrentVersionId() != null) {
            JobVersion version = jobService.requireVersion(job.getCurrentVersionId());
            uses = renderer.usesWindow(job, version.getHoconTemplate(), jobService.params(version), Map.of());
            if (version.getFormJson() != null) {
                WizardForm.Sync sync = objectMapper.readValue(version.getFormJson(), WizardForm.class).sync();
                if (sync != null && sync.mode() == WizardForm.SyncMode.INCREMENTAL && sync.window() != null) {
                    window = sync.window().name();
                }
            }
        }
        SyncWindow next = JobConfigRenderer.nextWindow(job, LocalDateTime.now());
        return new IncrementalView(window, uses, job.getWatermark(), JobConfigRenderer.lagSeconds(job), next.start(), next.end());
    }
}
