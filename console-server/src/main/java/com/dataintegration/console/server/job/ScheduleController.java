package com.dataintegration.console.server.job;

import com.dataintegration.console.common.Result;
import com.dataintegration.console.server.job.ScheduleService.ScheduleCommand;
import com.dataintegration.console.server.job.ScheduleService.ScheduleView;
import com.dataintegration.console.server.scheduler.ScheduleSpec;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class ScheduleController {

    public record ScheduleRequest(
            @NotBlank @Size(max = 64) String cron,
            boolean enabled,
            @Min(0) @Max(10) int failRetryCount,
            @Min(0) @Max(86400) int retryIntervalSeconds,
            @Min(0) int timeoutSeconds,
            ScheduleSpec.BlockStrategy blockStrategy,
            ScheduleSpec.MisfireStrategy misfireStrategy) {}

    private final ScheduleService scheduleService;

    public ScheduleController(ScheduleService scheduleService) {
        this.scheduleService = scheduleService;
    }

    /** Data is null when the job has no schedule yet. */
    @GetMapping("/api/jobs/{jobId}/schedule")
    public Result<ScheduleView> get(@PathVariable String jobId) {
        return Result.ok(scheduleService.get(jobId));
    }

    @PutMapping("/api/jobs/{jobId}/schedule")
    public Result<ScheduleView> save(@PathVariable String jobId, @Valid @RequestBody ScheduleRequest body) {
        return Result.ok(scheduleService.save(
                jobId,
                new ScheduleCommand(
                        body.cron(),
                        body.enabled(),
                        body.failRetryCount(),
                        body.retryIntervalSeconds(),
                        body.timeoutSeconds(),
                        body.blockStrategy(),
                        body.misfireStrategy())));
    }

    /** Next fire times of a cron expression, for the schedule form. */
    @GetMapping("/api/schedules/cron-preview")
    public Result<List<String>> preview(@RequestParam String cron) {
        return Result.ok(scheduleService.preview(cron));
    }
}
