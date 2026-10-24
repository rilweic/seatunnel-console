package com.dataintegration.console.server.job;

import com.dataintegration.console.server.datasource.DatasourceService;
import com.dataintegration.console.server.persistence.entity.JobDefinition;
import com.dataintegration.console.server.project.ProjectService;
import com.dataintegration.console.server.project.ProjectService.ParamItem;

import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
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

    public JobConfigRenderer(ProjectService projectService, DatasourceService datasourceService) {
        this.projectService = projectService;
        this.datasourceService = datasourceService;
    }

    public Rendered render(
            JobDefinition job,
            String hocon,
            Map<String, String> jobParams,
            Map<String, String> startupParams,
            LocalDateTime scheduledTime,
            String instanceId,
            boolean withSecrets) {
        Map<String, String> variables = variables(job, jobParams, startupParams, scheduledTime, instanceId);
        String text = Variables.render(hocon, variables, scheduledTime);
        HoconTemplate template = HoconTemplate.parse(text);
        String masked = template.render((connector, dsId) -> datasourceService.connectorOptions(dsId, connector, true));
        String config = withSecrets
                ? template.render((connector, dsId) -> datasourceService.connectorOptions(dsId, connector, false))
                : null;
        return new Rendered(variables, config, masked, Variables.unresolved(text), template);
    }

    public Map<String, String> variables(
            JobDefinition job,
            Map<String, String> jobParams,
            Map<String, String> startupParams,
            LocalDateTime scheduledTime,
            String instanceId) {
        String projectName = projectService.require(job.getProjectId()).getName();
        Map<String, String> projectParams = new LinkedHashMap<>();
        for (ParamItem item : projectService.params(job.getProjectId())) {
            projectParams.put(item.key(), item.value());
        }
        return Variables.resolve(
                Variables.builtins(scheduledTime, instanceId, job.getName(), projectName),
                scheduledTime,
                projectParams,
                jobParams,
                startupParams);
    }
}
