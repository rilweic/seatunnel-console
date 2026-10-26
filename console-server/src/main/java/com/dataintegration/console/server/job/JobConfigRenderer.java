package com.dataintegration.console.server.job;

import com.dataintegration.console.common.ConsoleException;
import com.dataintegration.console.common.ErrorCodes;
import com.dataintegration.console.server.datasource.DatasourceService;
import com.dataintegration.console.server.encryptkey.EncryptKeyService;
import com.dataintegration.console.server.persistence.entity.JobDefinition;
import com.dataintegration.console.server.project.ProjectService;
import com.dataintegration.console.server.project.ProjectService.ParamItem;

import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Turns a job template into the config sent to the engine: variables first (textual, docs/design.md
 * §8), then datasource injection (§6). Used for real runs and for the preview in the editor, so
 * both always agree.
 */
@Service
public class JobConfigRenderer {

    /**
     * @param variables final values of all variables, recorded on the run instance
     * @param config config with real credentials; null when only the masked one was asked for
     * @param masked config with secrets replaced by ******, safe to store and show
     * @param unresolved ${name} references no variable matched, left for HOCON to resolve
     * @param template the template after variables, before datasource injection
     */
    public record Rendered(
            Map<String, String> variables, String config, String masked, Set<String> unresolved, HoconTemplate template) {}

    private final ProjectService projectService;
    private final DatasourceService datasourceService;
    private final EncryptKeyService encryptKeys;

    public JobConfigRenderer(ProjectService projectService, DatasourceService datasourceService, EncryptKeyService encryptKeys) {
        this.projectService = projectService;
        this.datasourceService = datasourceService;
        this.encryptKeys = encryptKeys;
    }

    /** For previews and checks: the window is the one the job's next run would read (§7.5). */
    public Rendered render(
            JobDefinition job,
            String hocon,
            Map<String, String> jobParams,
            Map<String, String> startupParams,
            LocalDateTime scheduledTime,
            String instanceId,
            boolean withSecrets) {
        return render(job, hocon, jobParams, startupParams, scheduledTime, instanceId, nextWindow(job, scheduledTime), withSecrets);
    }

    /** @param window the run's own sync window; null when it does not use one */
    public Rendered render(
            JobDefinition job,
            String hocon,
            Map<String, String> jobParams,
            Map<String, String> startupParams,
            LocalDateTime scheduledTime,
            String instanceId,
            SyncWindow window,
            boolean withSecrets) {
        Map<String, String> variables = variables(job, jobParams, startupParams, scheduledTime, instanceId, window);
        String text = Variables.render(hocon, variables, scheduledTime);
        HoconTemplate template = HoconTemplate.parse(text);
        requireCreatorsDatasources(job, template);
        String masked = template.renderMasked((connector, dsId) -> datasourceService.connectorOptions(dsId, connector, true));
        String config = withSecrets
                ? template.render((connector, dsId) -> datasourceService.connectorOptions(dsId, connector, false), encryptKeys::reveal)
                : null;
        return new Rendered(variables, config, masked, Variables.unresolved(text), template);
    }

    /**
     * Checked on the rendered text, not only when saving: a {@code datasource_id = "${ds}"} saved
     * with the creator's own id could otherwise be pointed at someone else's datasource by a
     * startup parameter, and the run would carry that datasource's credentials.
     */
    private void requireCreatorsDatasources(JobDefinition job, HoconTemplate template) {
        for (HoconTemplate.DatasourceRef ref : template.datasourceRefs()) {
            var ds = datasourceService.require(ref.datasourceId());
            if (!Objects.equals(ds.getCreatedBy(), job.getCreatedBy())) {
                throw new ConsoleException(ErrorCodes.INVALID_ARGUMENT,
                        ref.section() + "." + ref.connector() + "：数据源「" + ds.getName() + "」不属于任务的创建人，任务只能使用创建人自己的数据源");
            }
        }
    }

    /** For previews and checks, with the window the job's next run would read. */
    public Map<String, String> variables(
            JobDefinition job,
            Map<String, String> jobParams,
            Map<String, String> startupParams,
            LocalDateTime scheduledTime,
            String instanceId) {
        return variables(job, jobParams, startupParams, scheduledTime, instanceId, nextWindow(job, scheduledTime));
    }

    public Map<String, String> variables(
            JobDefinition job,
            Map<String, String> jobParams,
            Map<String, String> startupParams,
            LocalDateTime scheduledTime,
            String instanceId,
            SyncWindow window) {
        String projectName = projectService.require(job.getProjectId()).getName();
        return Variables.resolve(
                Variables.builtins(scheduledTime, instanceId, job.getName(), projectName, window),
                scheduledTime,
                projectParams(job),
                jobParams,
                startupParams);
    }

    /**
     * Whether a run of this template with these parameters reads the sync window, so it gets one
     * fixed on its instance and moves the job's watermark (docs/design.md §7.5).
     */
    public boolean usesWindow(JobDefinition job, String hocon, Map<String, String> jobParams, Map<String, String> startupParams) {
        List<String> texts = new ArrayList<>();
        texts.add(hocon);
        texts.addAll(projectParams(job).values());
        if (jobParams != null) {
            texts.addAll(jobParams.values());
        }
        if (startupParams != null) {
            texts.addAll(startupParams.values());
        }
        return Variables.usesWindow(texts);
    }

    /** The window a run of the job scheduled at {@code scheduledTime} would read now. */
    public static SyncWindow nextWindow(JobDefinition job, LocalDateTime scheduledTime) {
        return SyncWindow.continuing(job.getWatermark(), scheduledTime, lagSeconds(job));
    }

    public static int lagSeconds(JobDefinition job) {
        return job.getWindowLagSeconds() == null ? 0 : job.getWindowLagSeconds();
    }

    private Map<String, String> projectParams(JobDefinition job) {
        Map<String, String> projectParams = new LinkedHashMap<>();
        for (ParamItem item : projectService.params(job.getProjectId())) {
            projectParams.put(item.key(), item.value());
        }
        return projectParams;
    }
}
