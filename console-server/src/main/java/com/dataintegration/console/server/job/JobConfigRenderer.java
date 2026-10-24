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
import java.util.LinkedHashMap;
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
