package com.dataintegration.console.server.job;

import com.dataintegration.console.common.ConsoleException;
import com.dataintegration.console.common.ErrorCodes;
import com.dataintegration.console.server.job.HoconTemplate.DatasourceRef;
import com.dataintegration.console.server.job.JobService.PreviewResult;
import com.dataintegration.console.server.job.JobService.RenderedText;
import com.dataintegration.console.server.job.JobService.ValidationTest;
import com.dataintegration.console.server.job.wizard.WizardForm;
import com.dataintegration.console.server.job.wizard.WizardService;
import com.dataintegration.console.server.persistence.entity.JobDefinition;
import com.dataintegration.console.server.stream.StreamIdentityService;

import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Unsaved editor checks, variable rendering previews, HTTP source previews and row-count validation
 * tests — kept separate from {@link JobService} CRUD and version persistence.
 */
@Service
public class JobPreviewService {

    private final JobService jobService;
    private final JobConfigRenderer renderer;
    private final RowCountValidator validator;
    private final WizardService wizardService;
    private final ScriptChecker scriptChecker;
    private final StreamIdentityService streams;

    public JobPreviewService(
            JobService jobService,
            JobConfigRenderer renderer,
            RowCountValidator validator,
            WizardService wizardService,
            ScriptChecker scriptChecker,
            StreamIdentityService streams) {
        this.jobService = jobService;
        this.renderer = renderer;
        this.validator = validator;
        this.wizardService = wizardService;
        this.scriptChecker = scriptChecker;
        this.streams = streams;
    }

    /** Runs the Jdbc source queries of unsaved script content under where 1 = 0 (docs/design.md §4.3). */
    public List<WizardService.Check> checkScript(String jobId, String hocon, Map<String, String> params) {
        JobDefinition job = jobService.requireEditable(jobId);
        LocalDateTime now = LocalDateTime.now();
        HoconTemplate template = HoconTemplate.parse(
                Variables.render(hocon, renderer.variables(job, params, Map.of(), now, JobService.PREVIEW_INSTANCE_ID), now));
        jobService.requireOwnersDatasources(job, template.datasourceRefs().stream().map(DatasourceRef::datasourceId).toList());
        return scriptChecker.check(template);
    }

    /** Sends the first page of an unsaved HTTP source with the same variables as a precheck. */
    public WizardService.HttpPreviewResult httpPreview(String jobId, WizardForm form, Map<String, String> params) {
        JobDefinition job = jobService.requireEditable(jobId);
        if (!JobService.MODE_FORM.equals(job.getEditMode())) {
            throw new ConsoleException(ErrorCodes.INVALID_ARGUMENT, "this job is not edited with the wizard");
        }
        jobService.requireOwnersDatasources(job, JobService.wizardDatasources(form));
        return wizardService.previewHttp(form, renderer.variables(job, params, Map.of(), LocalDateTime.now(), JobService.PREVIEW_INSTANCE_ID));
    }

    /** Runs the validation counts now against the editor's content, as a run at scheduledTime would. */
    public ValidationTest testValidation(
            String jobId,
            String hocon,
            Map<String, String> params,
            ValidationSpec validation,
            LocalDateTime scheduledTime) {
        JobDefinition job = jobService.requireEditable(jobId);
        if (validation == null || !validation.enabled()) {
            throw new ConsoleException(ErrorCodes.INVALID_ARGUMENT, "validation is not enabled");
        }
        LocalDateTime time = scheduledTime == null ? LocalDateTime.now() : scheduledTime;
        JobConfigRenderer.Rendered rendered = renderer.render(job, hocon, params, Map.of(), time, JobService.PREVIEW_INSTANCE_ID, false);
        // The counts run with the datasources' credentials, so the same ownership rule as saving applies.
        List<String> used = new ArrayList<>(JobService.validationDatasources(validation));
        rendered.template().datasourceRefs().forEach(ref -> used.add(ref.datasourceId()));
        jobService.requireOwnersDatasources(job, used);
        validator.check(validation, rendered.template());
        return new ValidationTest(validator.validate(validation, rendered.template(), rendered.variables(), time), rendered.variables());
    }

    /** Renders unsaved HOCON with variables and masked secrets for the editor preview drawer. */
    public PreviewResult preview(
            String jobId, String hocon, Map<String, String> params, Map<String, String> startupParams, LocalDateTime scheduledTime) {
        JobDefinition job = jobService.requireEditable(jobId);
        LocalDateTime time = scheduledTime == null ? LocalDateTime.now() : scheduledTime;
        JobConfigRenderer.Rendered rendered = renderer.render(job, hocon, params, startupParams, time, JobService.PREVIEW_INSTANCE_ID,
                JobConfigRenderer.nextWindow(job, time), false, streams.extras(jobId));
        return new PreviewResult(rendered.variables(), rendered.masked(), List.copyOf(rendered.unresolved()));
    }

    /**
     * Renders a piece of config with the variables a run at scheduledTime would have (§8), for a live
     * preview while typing: a text that cannot be rendered comes back with the reason, not as an error.
     */
    public RenderedText renderText(String jobId, String text, Map<String, String> params, LocalDateTime scheduledTime) {
        JobDefinition job = jobService.require(jobId);
        LocalDateTime time = scheduledTime == null ? LocalDateTime.now() : scheduledTime;
        try {
            String rendered = Variables.render(text, renderer.variables(job, params, Map.of(), time, JobService.PREVIEW_INSTANCE_ID), time);
            return new RenderedText(rendered, List.copyOf(Variables.unresolved(rendered)), null);
        } catch (ConsoleException e) {
            return new RenderedText(null, List.of(), e.getMessage());
        }
    }
}
