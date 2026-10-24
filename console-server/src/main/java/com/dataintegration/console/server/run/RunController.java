package com.dataintegration.console.server.run;

import com.dataintegration.console.common.PageResult;
import com.dataintegration.console.common.Result;
import com.dataintegration.console.server.run.InstanceService.InstanceDetail;
import com.dataintegration.console.server.run.InstanceService.InstanceView;
import com.dataintegration.console.server.run.InstanceService.LogView;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
public class RunController {

    /**
     * @param scheduledTime base time for variables; now when absent
     * @param params startup variables for this run, highest priority (docs/design.md §8)
     */
    public record RunRequest(LocalDateTime scheduledTime, Map<String, String> params) {}

    public record RunStarted(String instanceId) {}

    /** @param useLatestVersion run the job's current version instead of the one the original run used */
    public record RerunRequest(Boolean useLatestVersion) {}

    public record BackfillRequest(@NotNull LocalDate startDate, @NotNull LocalDate endDate, Map<String, String> params) {}

    private final RunService runService;
    private final InstanceService instanceService;
    private final BackfillService backfillService;

    public RunController(RunService runService, InstanceService instanceService, BackfillService backfillService) {
        this.runService = runService;
        this.instanceService = instanceService;
        this.backfillService = backfillService;
    }

    @PostMapping("/api/jobs/{jobId}/run")
    public Result<RunStarted> run(@PathVariable String jobId, @RequestBody(required = false) RunRequest body) {
        return Result.ok(new RunStarted(runService.runNow(
                jobId, body == null ? null : body.scheduledTime(), body == null ? null : body.params())));
    }

    @PostMapping("/api/instances/{id}/rerun")
    public Result<RunStarted> rerun(@PathVariable String id, @RequestBody(required = false) RerunRequest body) {
        return Result.ok(new RunStarted(runService.rerun(id, body != null && Boolean.TRUE.equals(body.useLatestVersion()))));
    }

    @GetMapping("/api/jobs/{jobId}/backfills/plan")
    public Result<BackfillService.Plan> planBackfill(
            @PathVariable String jobId, @RequestParam LocalDate startDate, @RequestParam LocalDate endDate) {
        return Result.ok(backfillService.plan(jobId, startDate, endDate));
    }

    @PostMapping("/api/jobs/{jobId}/backfills")
    public Result<BackfillService.BatchView> createBackfill(@PathVariable String jobId, @Valid @RequestBody BackfillRequest body) {
        return Result.ok(backfillService.create(jobId, body.startDate(), body.endDate(), body.params()));
    }

    @GetMapping("/api/jobs/{jobId}/backfills")
    public Result<List<BackfillService.BatchView>> backfills(@PathVariable String jobId) {
        return Result.ok(backfillService.list(jobId));
    }

    @PostMapping("/api/backfills/{id}/cancel")
    public Result<BackfillService.BatchView> cancelBackfill(@PathVariable String id) {
        return Result.ok(backfillService.cancel(id));
    }

    @PostMapping("/api/backfills/{id}/resume")
    public Result<BackfillService.BatchView> resumeBackfill(@PathVariable String id) {
        return Result.ok(backfillService.resume(id));
    }

    @PostMapping("/api/instances/{id}/stop")
    public Result<Void> stop(@PathVariable String id) {
        runService.stop(id);
        return Result.ok();
    }

    @GetMapping("/api/instances")
    public Result<PageResult<InstanceView>> list(
            @RequestParam(required = false) String projectId,
            @RequestParam(required = false) String jobId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String backfillId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return Result.ok(instanceService.list(projectId, jobId, status, backfillId, page, size));
    }

    @GetMapping("/api/instances/{id}")
    public Result<InstanceDetail> detail(@PathVariable String id) {
        return Result.ok(instanceService.detail(id));
    }

    @GetMapping("/api/instances/{id}/attempts/{attemptNo}/log")
    public Result<LogView> log(@PathVariable String id, @PathVariable int attemptNo) {
        return Result.ok(instanceService.log(id, attemptNo));
    }
}
