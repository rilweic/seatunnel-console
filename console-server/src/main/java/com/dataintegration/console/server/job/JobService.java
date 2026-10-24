package com.dataintegration.console.server.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataintegration.console.common.ConsoleException;
import com.dataintegration.console.common.ErrorCodes;
import com.dataintegration.console.common.PageResult;
import com.dataintegration.console.server.auth.CurrentUser;
import com.dataintegration.console.server.datasource.DatasourceService;
import com.dataintegration.console.server.job.HoconTemplate.DatasourceRef;
import com.dataintegration.console.server.job.wizard.WizardForm;
import com.dataintegration.console.server.job.wizard.WizardService;
import com.dataintegration.console.server.persistence.entity.JobDefinition;
import com.dataintegration.console.server.persistence.entity.JobVersion;
import com.dataintegration.console.server.persistence.entity.JobVersionDatasource;
import com.dataintegration.console.server.persistence.mapper.JobDefinitionMapper;
import com.dataintegration.console.server.persistence.mapper.JobScheduleMapper;
import com.dataintegration.console.server.persistence.mapper.JobVersionDatasourceMapper;
import com.dataintegration.console.server.persistence.mapper.JobVersionMapper;
import com.dataintegration.console.server.persistence.mapper.ProjectMapper;
import com.dataintegration.console.server.project.ProjectService;
import com.dataintegration.console.server.scheduler.XxlJobAdminClient;
import com.dataintegration.console.server.web.Pages;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Job definitions and their immutable versions (docs/schema.md §3). P1 supports batch jobs in script mode. */
@Service
public class JobService {

    private static final Logger log = LoggerFactory.getLogger(JobService.class);

    public static final String TYPE_BATCH = "BATCH";
    public static final String MODE_SCRIPT = "SCRIPT";
    public static final String MODE_FORM = "FORM";

    /** Stands in for ${system.task.instance.id} in previews, where no instance exists yet. */
    static final String PREVIEW_INSTANCE_ID = "<instance-id>";

    private static final TypeReference<Map<String, String>> MAP_TYPE = new TypeReference<>() {};

    public record JobView(
            String id,
            String projectId,
            String projectName,
            String name,
            String jobType,
            String editMode,
            Integer currentVersionNo,
            String description,
            String updatedAt) {}

    public record VersionSummary(String id, int versionNo, String remark, String createdBy, String createdAt) {}

    public record VersionDetail(
            String id,
            String jobDefinitionId,
            int versionNo,
            String hocon,
            Map<String, String> params,
            List<String> datasourceIds,
            ValidationSpec validation,
            WizardForm form,
            String remark,
            String createdAt) {}

    public record JobDetail(JobView job, VersionDetail currentVersion) {}

    /** What a run at {@code scheduledTime} would submit; secrets masked. */
    public record PreviewResult(Map<String, String> variables, String config, List<String> unresolved) {}

    /** Validation counts taken now, for the editor's test button. */
    public record ValidationTest(RowCountValidator.Result result, Map<String, String> variables) {}

    private final JobDefinitionMapper jobMapper;
    private final JobVersionMapper versionMapper;
    private final JobVersionDatasourceMapper versionDatasourceMapper;
    private final JobScheduleMapper scheduleMapper;
    private final ProjectService projectService;
    private final ProjectMapper projectMapper;
    private final DatasourceService datasourceService;
    private final ObjectProvider<XxlJobAdminClient> xxlJobAdminClient;
    private final ObjectMapper objectMapper;
    private final JobConfigRenderer renderer;
    private final RowCountValidator validator;
    private final WizardService wizardService;

    public JobService(
            JobDefinitionMapper jobMapper,
            JobVersionMapper versionMapper,
            JobVersionDatasourceMapper versionDatasourceMapper,
            JobScheduleMapper scheduleMapper,
            ProjectService projectService,
            ProjectMapper projectMapper,
            DatasourceService datasourceService,
            ObjectProvider<XxlJobAdminClient> xxlJobAdminClient,
            ObjectMapper objectMapper,
            JobConfigRenderer renderer,
            RowCountValidator validator,
            WizardService wizardService) {
        this.jobMapper = jobMapper;
        this.versionMapper = versionMapper;
        this.versionDatasourceMapper = versionDatasourceMapper;
        this.scheduleMapper = scheduleMapper;
        this.projectService = projectService;
        this.projectMapper = projectMapper;
        this.datasourceService = datasourceService;
        this.xxlJobAdminClient = xxlJobAdminClient;
        this.objectMapper = objectMapper;
        this.renderer = renderer;
        this.validator = validator;
        this.wizardService = wizardService;
    }

    public PageResult<JobView> list(String projectId, String keyword, Integer page, Integer size) {
        Page<JobDefinition> result =
                jobMapper.selectPage(
                        Pages.of(page, size),
                        new LambdaQueryWrapper<JobDefinition>()
                                .eq(projectId != null && !projectId.isBlank(), JobDefinition::getProjectId, projectId)
                                .like(keyword != null && !keyword.isBlank(), JobDefinition::getName, keyword)
                                .orderByDesc(JobDefinition::getUpdatedAt));
        Map<String, Integer> versionNos = versionNos(result.getRecords());
        Map<String, String> projectNames =
                projectNames(result.getRecords().stream().map(JobDefinition::getProjectId).toList());
        return Pages.result(result)
                .map(job -> view(job, versionNos.get(job.getCurrentVersionId()), projectNames.get(job.getProjectId())));
    }

    public JobDetail get(String id) {
        JobDefinition job = require(id);
        VersionDetail current = job.getCurrentVersionId() == null ? null : versionDetail(requireVersion(job.getCurrentVersionId()));
        return new JobDetail(view(job, current == null ? null : current.versionNo()), current);
    }

    public JobView create(String projectId, String name, String jobType, String editMode, String description) {
        projectService.require(projectId);
        if (!TYPE_BATCH.equals(jobType)) {
            throw new ConsoleException(ErrorCodes.INVALID_ARGUMENT, "only BATCH jobs are supported in this version");
        }
        JobDefinition job = new JobDefinition();
        job.setProjectId(projectId);
        job.setName(name.trim());
        job.setJobType(jobType);
        if (editMode != null && !MODE_FORM.equals(editMode) && !MODE_SCRIPT.equals(editMode)) {
            throw new ConsoleException(ErrorCodes.INVALID_ARGUMENT, "edit mode must be FORM or SCRIPT");
        }
        job.setEditMode(editMode == null ? MODE_SCRIPT : editMode);
        job.setDescription(description);
        job.setCreatedBy(CurrentUser.idOrNull());
        jobMapper.insert(job);
        return view(job, null);
    }

    public JobView update(String id, String name, String description) {
        JobDefinition job = require(id);
        job.setName(name.trim());
        job.setDescription(description);
        jobMapper.updateById(job);
        return view(require(id), currentVersionNo(job));
    }

    /** Soft delete. The schedule goes with it; the mirrored XXL-JOB job is removed on a best-effort basis. */
    public void delete(String id) {
        JobDefinition job = require(id);
        jobMapper.deleteById(id);
        scheduleMapper.deleteById(id);
        XxlJobAdminClient client = xxlJobAdminClient.getIfAvailable();
        if (job.getXxlJobId() != null && client != null) {
            try {
                client.removeJob(job.getXxlJobId());
            } catch (ConsoleException e) {
                log.warn("Job {} deleted but XXL-JOB job {} could not be removed: {}", id, job.getXxlJobId(), e.getMessage());
            }
        }
    }

    /**
     * Saves a new immutable version and makes it current. The template is checked as a run right
     * now would see it, after variables: it must parse, and every datasource reference must exist
     * and suit its connector, checked by building masked connector options.
     */
    @Transactional
    public VersionDetail saveVersion(
            String jobId, String hocon, Map<String, String> params, ValidationSpec validation, WizardForm form, String remark) {
        JobDefinition job = require(jobId);
        if (params != null) {
            params.keySet().forEach(Variables::validateName);
        }
        if (MODE_FORM.equals(job.getEditMode())) {
            // Wizard jobs: the config and its validation always come from the form, never from the client.
            if (form == null) {
                throw new ConsoleException(ErrorCodes.INVALID_ARGUMENT, "this job is edited with the wizard; send the form");
            }
            WizardService.PrecheckResult check = wizardService.precheck(form, renderer.variables(job, params, Map.of(), LocalDateTime.now(), null));
            if (!check.passed()) {
                throw new ConsoleException(ErrorCodes.INVALID_ARGUMENT, "预检查未通过：" + String.join("；", check.errors()));
            }
            hocon = check.generated().hocon();
            validation = check.generated().validation();
        } else if (form != null) {
            throw new ConsoleException(ErrorCodes.INVALID_ARGUMENT, "this job is edited as a script; a wizard form cannot be saved");
        } else if (hocon == null || hocon.isBlank()) {
            throw new ConsoleException(ErrorCodes.INVALID_ARGUMENT, "job config is empty");
        }
        LocalDateTime now = LocalDateTime.now();
        HoconTemplate template = HoconTemplate.parse(
                Variables.render(hocon, renderer.variables(job, params, Map.of(), now, null), now));
        String mode = template.jobMode();
        if (TYPE_BATCH.equals(job.getJobType()) && mode != null && !TYPE_BATCH.equalsIgnoreCase(mode)) {
            throw new ConsoleException(ErrorCodes.INVALID_ARGUMENT, "env.job.mode must be BATCH for a batch job, got " + mode);
        }
        Set<String> datasourceIds = new LinkedHashSet<>();
        for (DatasourceRef ref : template.datasourceRefs()) {
            try {
                datasourceService.connectorOptions(ref.datasourceId(), ref.connector(), true);
            } catch (ConsoleException e) {
                throw new ConsoleException(
                        ErrorCodes.INVALID_ARGUMENT, ref.section() + "." + ref.connector() + ": " + e.getMessage());
            }
            datasourceIds.add(ref.datasourceId());
        }
        validator.check(validation, template);
        if (validation != null && validation.enabled()) {
            // Recorded with the version's datasources so they cannot be deleted while in use.
            List<ValidationSpec.Side> sides = new ArrayList<>();
            if (validation.perTable()) {
                validation.tables().forEach(t -> {
                    sides.add(t.source());
                    sides.add(t.target());
                });
            } else {
                sides.add(validation.source());
                sides.add(validation.target());
            }
            for (ValidationSpec.Side side : sides) {
                if (side.mode() != ValidationSpec.Mode.AUTO) {
                    datasourceIds.add(side.datasourceId());
                }
            }
        }

        JobVersion latest =
                versionMapper.selectOne(
                        new LambdaQueryWrapper<JobVersion>()
                                .eq(JobVersion::getJobDefinitionId, jobId)
                                .orderByDesc(JobVersion::getVersionNo)
                                .last("limit 1"));
        JobVersion version = new JobVersion();
        version.setJobDefinitionId(jobId);
        version.setVersionNo(latest == null ? 1 : latest.getVersionNo() + 1);
        version.setHoconTemplate(hocon);
        version.setParamsJson(params == null || params.isEmpty() ? null : objectMapper.writeValueAsString(params));
        version.setValidationJson(validation == null ? null : objectMapper.writeValueAsString(validation));
        version.setFormJson(form == null ? null : objectMapper.writeValueAsString(form));
        version.setRemark(remark);
        version.setCreatedBy(CurrentUser.idOrNull());
        versionMapper.insert(version);
        datasourceIds.forEach(dsId -> versionDatasourceMapper.insert(new JobVersionDatasource(version.getId(), dsId)));

        job.setCurrentVersionId(version.getId());
        jobMapper.updateById(job);
        return versionDetail(version);
    }

    /**
     * Rollback (docs/design.md §7.7): saves an old version's content as a new current version, so
     * history stays append-only. A wizard version is re-generated and re-checked from its form.
     */
    @Transactional
    public VersionDetail restoreVersion(String jobId, String versionId) {
        JobVersion old = requireVersion(versionId);
        if (!old.getJobDefinitionId().equals(jobId)) {
            throw new ConsoleException(ErrorCodes.NOT_FOUND, "version not found: " + versionId);
        }
        JobDefinition job = require(jobId);
        WizardForm form = old.getFormJson() == null ? null : objectMapper.readValue(old.getFormJson(), WizardForm.class);
        if (MODE_FORM.equals(job.getEditMode()) && form == null) {
            throw new ConsoleException(ErrorCodes.INVALID_ARGUMENT, "v" + old.getVersionNo() + " was written as a script and cannot be restored into the wizard");
        }
        return saveVersion(
                jobId,
                old.getHoconTemplate(),
                params(old),
                validation(old),
                MODE_FORM.equals(job.getEditMode()) ? form : null,
                "回滚到 v" + old.getVersionNo());
    }

    /** Checks a wizard form against the live datasources and returns what saving it would generate. */
    public WizardService.PrecheckResult wizardPrecheck(String jobId, WizardForm form, Map<String, String> params) {
        JobDefinition job = require(jobId);
        if (!MODE_FORM.equals(job.getEditMode())) {
            throw new ConsoleException(ErrorCodes.INVALID_ARGUMENT, "this job is not edited with the wizard");
        }
        return wizardService.precheck(form, renderer.variables(job, params, Map.of(), LocalDateTime.now(), PREVIEW_INSTANCE_ID));
    }

    /**
     * One-way switch from the wizard to the script editor (docs/design.md §7.3). The current version
     * stays as it is; the next save is a plain script version.
     */
    public JobView switchToScript(String jobId) {
        JobDefinition job = require(jobId);
        if (!MODE_FORM.equals(job.getEditMode())) {
            throw new ConsoleException(ErrorCodes.CONFLICT, "job is already edited as a script");
        }
        job.setEditMode(MODE_SCRIPT);
        jobMapper.updateById(job);
        return view(require(jobId), currentVersionNo(job));
    }

    /** Runs the validation counts now against the editor's content, as a run at scheduledTime would. */
    public ValidationTest testValidation(
            String jobId,
            String hocon,
            Map<String, String> params,
            ValidationSpec validation,
            LocalDateTime scheduledTime) {
        JobDefinition job = require(jobId);
        if (validation == null || !validation.enabled()) {
            throw new ConsoleException(ErrorCodes.INVALID_ARGUMENT, "validation is not enabled");
        }
        LocalDateTime time = scheduledTime == null ? LocalDateTime.now() : scheduledTime;
        JobConfigRenderer.Rendered rendered = renderer.render(job, hocon, params, Map.of(), time, PREVIEW_INSTANCE_ID, false);
        validator.check(validation, rendered.template());
        return new ValidationTest(validator.validate(validation, rendered.template(), rendered.variables(), time), rendered.variables());
    }

    public ValidationSpec validation(JobVersion version) {
        return version.getValidationJson() == null ? null : objectMapper.readValue(version.getValidationJson(), ValidationSpec.class);
    }

    public PreviewResult preview(
            String jobId, String hocon, Map<String, String> params, Map<String, String> startupParams, LocalDateTime scheduledTime) {
        JobDefinition job = require(jobId);
        JobConfigRenderer.Rendered rendered = renderer.render(
                job, hocon, params, startupParams, scheduledTime == null ? LocalDateTime.now() : scheduledTime, PREVIEW_INSTANCE_ID, false);
        return new PreviewResult(rendered.variables(), rendered.masked(), List.copyOf(rendered.unresolved()));
    }

    public List<VersionSummary> versions(String jobId) {
        require(jobId);
        return versionMapper
                .selectList(
                        new LambdaQueryWrapper<JobVersion>()
                                .select(
                                        JobVersion::getId,
                                        JobVersion::getVersionNo,
                                        JobVersion::getRemark,
                                        JobVersion::getCreatedBy,
                                        JobVersion::getCreatedAt)
                                .eq(JobVersion::getJobDefinitionId, jobId)
                                .orderByDesc(JobVersion::getVersionNo))
                .stream()
                .map(v -> new VersionSummary(
                        v.getId(), v.getVersionNo(), v.getRemark(), v.getCreatedBy(), String.valueOf(v.getCreatedAt())))
                .toList();
    }

    public VersionDetail version(String jobId, String versionId) {
        JobVersion version = requireVersion(versionId);
        if (!version.getJobDefinitionId().equals(jobId)) {
            throw new ConsoleException(ErrorCodes.NOT_FOUND, "version not found: " + versionId);
        }
        return versionDetail(version);
    }

    public JobDefinition require(String id) {
        JobDefinition job = jobMapper.selectById(id);
        if (job == null) {
            throw new ConsoleException(ErrorCodes.NOT_FOUND, "job not found: " + id);
        }
        return job;
    }

    public JobVersion requireVersion(String versionId) {
        JobVersion version = versionMapper.selectById(versionId);
        if (version == null) {
            throw new ConsoleException(ErrorCodes.NOT_FOUND, "version not found: " + versionId);
        }
        return version;
    }

    public Map<String, String> params(JobVersion version) {
        return version.getParamsJson() == null ? Map.of() : objectMapper.readValue(version.getParamsJson(), MAP_TYPE);
    }

    private VersionDetail versionDetail(JobVersion version) {
        List<String> datasourceIds =
                versionDatasourceMapper
                        .selectList(new LambdaQueryWrapper<JobVersionDatasource>()
                                .eq(JobVersionDatasource::getJobVersionId, version.getId()))
                        .stream()
                        .map(JobVersionDatasource::getDatasourceId)
                        .toList();
        return new VersionDetail(
                version.getId(),
                version.getJobDefinitionId(),
                version.getVersionNo(),
                version.getHoconTemplate(),
                params(version),
                datasourceIds,
                validation(version),
                version.getFormJson() == null ? null : objectMapper.readValue(version.getFormJson(), WizardForm.class),
                version.getRemark(),
                String.valueOf(version.getCreatedAt()));
    }

    private Integer currentVersionNo(JobDefinition job) {
        return job.getCurrentVersionId() == null ? null : requireVersion(job.getCurrentVersionId()).getVersionNo();
    }

    private Map<String, Integer> versionNos(List<JobDefinition> jobs) {
        List<String> ids = jobs.stream().map(JobDefinition::getCurrentVersionId).filter(v -> v != null).toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return versionMapper
                .selectList(new LambdaQueryWrapper<JobVersion>()
                        .select(JobVersion::getId, JobVersion::getVersionNo)
                        .in(JobVersion::getId, ids))
                .stream()
                .collect(Collectors.toMap(JobVersion::getId, JobVersion::getVersionNo));
    }

    private JobView view(JobDefinition job, Integer currentVersionNo) {
        return view(job, currentVersionNo, projectNames(List.of(job.getProjectId())).get(job.getProjectId()));
    }

    private JobView view(JobDefinition job, Integer currentVersionNo, String projectName) {
        return new JobView(
                job.getId(),
                job.getProjectId(),
                projectName,
                job.getName(),
                job.getJobType(),
                job.getEditMode(),
                currentVersionNo,
                job.getDescription(),
                job.getUpdatedAt() == null ? null : job.getUpdatedAt().toString());
    }

    private Map<String, String> projectNames(List<String> projectIds) {
        if (projectIds.isEmpty()) {
            return Map.of();
        }
        return projectMapper.selectByIds(projectIds.stream().distinct().toList()).stream()
                .collect(Collectors.toMap(p -> p.getId(), p -> p.getName()));
    }

}
