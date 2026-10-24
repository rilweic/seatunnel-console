package com.dataintegration.console.server.job;

import com.dataintegration.console.common.PageResult;
import com.dataintegration.console.common.Result;
import com.dataintegration.console.server.job.JobService.JobDetail;
import com.dataintegration.console.server.job.JobService.JobView;
import com.dataintegration.console.server.job.JobService.VersionDetail;
import com.dataintegration.console.server.job.JobService.VersionSummary;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.dataintegration.console.server.job.wizard.WizardForm;
import com.dataintegration.console.server.job.wizard.WizardService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/jobs")
public class JobController {

    public record CreateRequest(
            @NotBlank String projectId,
            @NotBlank @Size(max = 128) String name,
            @NotBlank String jobType,
            String editMode,
            @Size(max = 512) String description) {}

    public record UpdateRequest(@NotBlank @Size(max = 128) String name, @Size(max = 512) String description) {}

    /** {@code hocon} is ignored for wizard jobs, whose config is generated from {@code form}. */
    public record VersionRequest(
            String hocon, Map<String, String> params, ValidationSpec validation, WizardForm form, @Size(max = 512) String remark) {}

    public record WizardRequest(WizardForm form, Map<String, String> params) {}

    public record ScriptCheckRequest(@NotBlank String hocon, Map<String, String> params) {}

    public record ValidationTestRequest(
            @NotBlank String hocon, Map<String, String> params, ValidationSpec validation, LocalDateTime scheduledTime) {}

    /** Unsaved editor content plus the run inputs to preview with; scheduledTime defaults to now. */
    public record PreviewRequest(
            @NotBlank String hocon, Map<String, String> params, Map<String, String> startupParams, LocalDateTime scheduledTime) {}

    private final JobService jobService;

    public JobController(JobService jobService) {
        this.jobService = jobService;
    }

    @GetMapping
    public Result<PageResult<JobView>> list(
            @RequestParam(required = false) String projectId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "false") boolean mine,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return Result.ok(jobService.list(projectId, keyword, mine, page, size));
    }

    @GetMapping("/{id}")
    public Result<JobDetail> get(@PathVariable String id) {
        return Result.ok(jobService.get(id));
    }

    @PostMapping
    public Result<JobView> create(@Valid @RequestBody CreateRequest body) {
        return Result.ok(jobService.create(body.projectId(), body.name(), body.jobType(), body.editMode(), body.description()));
    }

    @PutMapping("/{id}")
    public Result<JobView> update(@PathVariable String id, @Valid @RequestBody UpdateRequest body) {
        return Result.ok(jobService.update(id, body.name(), body.description()));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable String id) {
        jobService.delete(id);
        return Result.ok();
    }

    @PostMapping("/{id}/versions")
    public Result<VersionDetail> saveVersion(@PathVariable String id, @Valid @RequestBody VersionRequest body) {
        return Result.ok(jobService.saveVersion(id, body.hocon(), body.params(), body.validation(), body.form(), body.remark()));
    }

    @PostMapping("/{id}/preview")
    public Result<JobService.PreviewResult> preview(@PathVariable String id, @Valid @RequestBody PreviewRequest body) {
        return Result.ok(jobService.preview(id, body.hocon(), body.params(), body.startupParams(), body.scheduledTime()));
    }

    /** Checks unsaved script content: every Jdbc source query is run once under where 1 = 0. */
    @PostMapping("/{id}/check")
    public Result<List<WizardService.Check>> checkScript(@PathVariable String id, @Valid @RequestBody ScriptCheckRequest body) {
        return Result.ok(jobService.checkScript(id, body.hocon(), body.params()));
    }

    @PostMapping("/{id}/wizard/precheck")
    public Result<WizardService.PrecheckResult> wizardPrecheck(@PathVariable String id, @RequestBody WizardRequest body) {
        return Result.ok(jobService.wizardPrecheck(id, body.form(), body.params()));
    }

    @PostMapping("/{id}/switch-to-script")
    public Result<JobView> switchToScript(@PathVariable String id) {
        return Result.ok(jobService.switchToScript(id));
    }

    @PostMapping("/{id}/validation/test")
    public Result<JobService.ValidationTest> testValidation(@PathVariable String id, @Valid @RequestBody ValidationTestRequest body) {
        return Result.ok(jobService.testValidation(id, body.hocon(), body.params(), body.validation(), body.scheduledTime()));
    }

    @GetMapping("/{id}/versions")
    public Result<List<VersionSummary>> versions(@PathVariable String id) {
        return Result.ok(jobService.versions(id));
    }

    @PostMapping("/{id}/versions/{versionId}/restore")
    public Result<VersionDetail> restoreVersion(@PathVariable String id, @PathVariable String versionId) {
        return Result.ok(jobService.restoreVersion(id, versionId));
    }

    @GetMapping("/{id}/versions/{versionId}")
    public Result<VersionDetail> version(@PathVariable String id, @PathVariable String versionId) {
        return Result.ok(jobService.version(id, versionId));
    }
}
