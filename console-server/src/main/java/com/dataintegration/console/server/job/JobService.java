package com.dataintegration.console.server.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataintegration.console.common.ConsoleException;
import com.dataintegration.console.common.ErrorCodes;
import com.dataintegration.console.common.PageResult;
import com.dataintegration.console.server.auth.Access;
import com.dataintegration.console.server.auth.CurrentUser;
import com.dataintegration.console.server.datasource.DatasourceService;
import com.dataintegration.console.server.job.HoconTemplate.DatasourceRef;
import com.dataintegration.console.server.job.wizard.WizardForm;
import com.dataintegration.console.server.job.wizard.WizardService;
import com.dataintegration.console.server.persistence.entity.BackfillBatch;
import com.dataintegration.console.server.persistence.entity.JobDefinition;
import com.dataintegration.console.server.persistence.entity.JobInstance;
import com.dataintegration.console.server.persistence.entity.JobSchedule;
import com.dataintegration.console.server.persistence.entity.JobVersion;
import com.dataintegration.console.server.persistence.entity.JobVersionDatasource;
import com.dataintegration.console.server.persistence.mapper.BackfillBatchMapper;
import com.dataintegration.console.server.persistence.mapper.JobDefinitionMapper;
import com.dataintegration.console.server.persistence.mapper.JobInstanceMapper;
import com.dataintegration.console.server.persistence.mapper.JobScheduleMapper;
import com.dataintegration.console.server.persistence.mapper.JobVersionDatasourceMapper;
import com.dataintegration.console.server.persistence.mapper.JobVersionMapper;
import com.dataintegration.console.server.persistence.mapper.ProjectMapper;
import com.dataintegration.console.server.project.ProjectService;
import com.dataintegration.console.server.scheduler.XxlJobAdminClient;
import com.dataintegration.console.server.user.UserDirectory;
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
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
            String ownerId,
            String ownerName,
            boolean editable,
            String updatedAt,
            Activity activity) {}

    /**
     * How the job is running, for lists and headers.
     *
     * @param lastRunAt start time of the newest run, or its creation time while it waits
     * @param scheduleEnabled false also when the job has no schedule
     */
    public record Activity(String lastRunId, String lastRunStatus, String lastRunAt, boolean scheduleEnabled, String cron) {}

    public record VersionSummary(String id, int versionNo, String remark, String createdBy, String createdByName, String createdAt) {}

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
    private final UserDirectory users;
    private final ScriptChecker scriptChecker;
    private final JobInstanceMapper instanceMapper;
    private final BackfillBatchMapper backfillMapper;

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
            WizardService wizardService,
            UserDirectory users,
            ScriptChecker scriptChecker,
            JobInstanceMapper instanceMapper,
            BackfillBatchMapper backfillMapper) {
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
        this.users = users;
        this.scriptChecker = scriptChecker;
        this.instanceMapper = instanceMapper;
        this.backfillMapper = backfillMapper;
    }

    /** @param mine only the current user's jobs */
    public PageResult<JobView> list(String projectId, String keyword, boolean mine, Integer page, Integer size) {
        Page<JobDefinition> result =
                jobMapper.selectPage(
                        Pages.of(page, size),
                        new LambdaQueryWrapper<JobDefinition>()
                                .eq(projectId != null && !projectId.isBlank(), JobDefinition::getProjectId, projectId)
                                .like(keyword != null && !keyword.isBlank(), JobDefinition::getName, Pages.literal(keyword))
                                .eq(mine, JobDefinition::getCreatedBy, CurrentUser.idOrNull())
                                .orderByDesc(JobDefinition::getUpdatedAt));
        Map<String, Integer> versionNos = versionNos(result.getRecords());
        Map<String, String> projectNames =
                projectNames(result.getRecords().stream().map(JobDefinition::getProjectId).toList());
        Map<String, String> owners = users.names(result.getRecords().stream().map(JobDefinition::getCreatedBy).toList());
        Map<String, Activity> activities = activities(result.getRecords().stream().map(JobDefinition::getId).toList());
        return Pages.result(result)
                .map(job -> view(job, versionNos.get(job.getCurrentVersionId()), projectNames.get(job.getProjectId()), owners,
                        activities.get(job.getId())));
    }

    public JobDetail get(String id) {
        JobDefinition job = require(id);
        VersionDetail current = job.getCurrentVersionId() == null ? null : versionDetail(requireVersion(job.getCurrentVersionId()));
        return new JobDetail(view(job, current == null ? null : current.versionNo()), current);
    }

    public JobView create(String projectId, String name, String jobType, String editMode, String description) {
        projectService.requireEditable(projectId);
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

    /**
     * Name, description and project; none of them is part of a version. Moving a job needs edit
     * rights on the target project, and from then on its runs resolve that project's parameters.
     *
     * @param projectId null to keep the current project
     */
    public JobView update(String id, String name, String description, String projectId) {
        JobDefinition job = requireEditable(id);
        // Only the edited columns: writing the whole row read above could undo a concurrent
        // ownership transfer, version save or XXL-JOB id change.
        JobDefinition update = new JobDefinition();
        update.setId(id);
        if (projectId != null && !projectId.equals(job.getProjectId())) {
            projectService.requireEditable(projectId);
            update.setProjectId(projectId);
        }
        update.setName(name.trim());
        jobMapper.update(update, new LambdaUpdateWrapper<JobDefinition>()
                .set(JobDefinition::getDescription, description)
                .eq(JobDefinition::getId, id));
        JobDefinition saved = require(id);
        return view(saved, currentVersionNo(saved));
    }

    /** Soft delete. The schedule goes with it; the mirrored XXL-JOB job is removed on a best-effort basis. */
    @Transactional
    public void delete(String id) {
        // Locked before anything else is read in this transaction, so a run being created right now
        // (RunRecorder.insert takes the same lock) is either seen below or finds the job deleted.
        lockJob(id);
        JobDefinition job = requireEditable(id);
        // A deleted job's runs can no longer be stopped from the console, so none may be going.
        long active = instanceMapper.selectCount(new LambdaQueryWrapper<JobInstance>()
                .eq(JobInstance::getJobDefinitionId, id)
                .in(JobInstance::getStatus, "PENDING", "RUNNING"));
        if (active > 0) {
            throw new ConsoleException(ErrorCodes.CONFLICT, "任务还有 " + active + " 个运行中或等待中的实例，请先停止再删除");
        }
        long backfills = backfillMapper.selectCount(new LambdaQueryWrapper<BackfillBatch>()
                .eq(BackfillBatch::getJobDefinitionId, id)
                .in(BackfillBatch::getStatus, "RUNNING", "PAUSED"));
        if (backfills > 0) {
            throw new ConsoleException(ErrorCodes.CONFLICT, "任务还有进行中或暂停的补数，请先在「补数」里取消再删除");
        }
        jobMapper.deleteById(id);
        scheduleMapper.deleteById(id);
        XxlJobAdminClient client = xxlJobAdminClient.getIfAvailable();
        if (job.getXxlJobId() != null && client != null) {
            try {
                client.removeJob(job.getXxlJobId());
                jobMapper.clearXxlJobId(id, job.getXxlJobId());
            } catch (ConsoleException e) {
                if (e.getCode() == ErrorCodes.SCHEDULER_JOB_MISSING) {
                    jobMapper.clearXxlJobId(id, job.getXxlJobId());
                } else {
                    // Left for XxlJobReconciler, which retries the removal.
                    log.warn("Job {} deleted but XXL-JOB job {} could not be removed: {}", id, job.getXxlJobId(), e.getMessage());
                }
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
        JobDefinition job = requireEditable(jobId);
        if (params != null) {
            params.keySet().forEach(Variables::validateName);
        }
        if (MODE_FORM.equals(job.getEditMode())) {
            // Wizard jobs: the config and its validation always come from the form, never from the client.
            if (form == null) {
                throw new ConsoleException(ErrorCodes.INVALID_ARGUMENT, "this job is edited with the wizard; send the form");
            }
            requireOwnersDatasources(job, wizardDatasources(form));
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
        List<String> secrets = template.explicitSecrets();
        if (!secrets.isEmpty()) {
            throw new ConsoleException(ErrorCodes.INVALID_ARGUMENT,
                    "配置里不能直接写密码或密钥（" + String.join("、", secrets)
                            + "）：请建一个数据源，用 datasource_id 引用，连接信息会在运行时注入");
        }
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
        // Recorded with the version's datasources so they cannot be deleted while in use.
        datasourceIds.addAll(validationDatasources(validation));

        requireOwnersDatasources(job, datasourceIds);

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

        JobDefinition current = new JobDefinition();
        current.setId(jobId);
        current.setCurrentVersionId(version.getId());
        jobMapper.updateById(current);
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

    /** Runs the Jdbc source queries of unsaved script content under where 1 = 0 (docs/design.md §4.3). */
    public List<WizardService.Check> checkScript(String jobId, String hocon, Map<String, String> params) {
        JobDefinition job = requireEditable(jobId);
        LocalDateTime now = LocalDateTime.now();
        HoconTemplate template = HoconTemplate.parse(
                Variables.render(hocon, renderer.variables(job, params, Map.of(), now, PREVIEW_INSTANCE_ID), now));
        requireOwnersDatasources(job, template.datasourceRefs().stream().map(DatasourceRef::datasourceId).toList());
        return scriptChecker.check(template);
    }

    /** Checks a wizard form against the live datasources and returns what saving it would generate. */
    public WizardService.PrecheckResult wizardPrecheck(String jobId, WizardForm form, Map<String, String> params) {
        JobDefinition job = requireEditable(jobId);
        if (!MODE_FORM.equals(job.getEditMode())) {
            throw new ConsoleException(ErrorCodes.INVALID_ARGUMENT, "this job is not edited with the wizard");
        }
        requireOwnersDatasources(job, wizardDatasources(form));
        return wizardService.precheck(form, renderer.variables(job, params, Map.of(), LocalDateTime.now(), PREVIEW_INSTANCE_ID));
    }

    /**
     * One-way switch from the wizard to the script editor (docs/design.md §7.3). The current version
     * stays as it is; the next save is a plain script version.
     */
    public JobView switchToScript(String jobId) {
        JobDefinition job = requireEditable(jobId);
        if (!MODE_FORM.equals(job.getEditMode())) {
            throw new ConsoleException(ErrorCodes.CONFLICT, "job is already edited as a script");
        }
        JobDefinition update = new JobDefinition();
        update.setId(jobId);
        update.setEditMode(MODE_SCRIPT);
        jobMapper.updateById(update);
        JobDefinition saved = require(jobId);
        return view(saved, currentVersionNo(saved));
    }

    /** Runs the validation counts now against the editor's content, as a run at scheduledTime would. */
    public ValidationTest testValidation(
            String jobId,
            String hocon,
            Map<String, String> params,
            ValidationSpec validation,
            LocalDateTime scheduledTime) {
        JobDefinition job = requireEditable(jobId);
        if (validation == null || !validation.enabled()) {
            throw new ConsoleException(ErrorCodes.INVALID_ARGUMENT, "validation is not enabled");
        }
        LocalDateTime time = scheduledTime == null ? LocalDateTime.now() : scheduledTime;
        JobConfigRenderer.Rendered rendered = renderer.render(job, hocon, params, Map.of(), time, PREVIEW_INSTANCE_ID, false);
        // The counts run with the datasources' credentials, so the same ownership rule as saving applies.
        List<String> used = new ArrayList<>(validationDatasources(validation));
        rendered.template().datasourceRefs().forEach(ref -> used.add(ref.datasourceId()));
        requireOwnersDatasources(job, used);
        validator.check(validation, rendered.template());
        return new ValidationTest(validator.validate(validation, rendered.template(), rendered.variables(), time), rendered.variables());
    }

    public ValidationSpec validation(JobVersion version) {
        return version.getValidationJson() == null ? null : objectMapper.readValue(version.getValidationJson(), ValidationSpec.class);
    }

    public PreviewResult preview(
            String jobId, String hocon, Map<String, String> params, Map<String, String> startupParams, LocalDateTime scheduledTime) {
        JobDefinition job = requireEditable(jobId);
        JobConfigRenderer.Rendered rendered = renderer.render(
                job, hocon, params, startupParams, scheduledTime == null ? LocalDateTime.now() : scheduledTime, PREVIEW_INSTANCE_ID, false);
        return new PreviewResult(rendered.variables(), rendered.masked(), List.copyOf(rendered.unresolved()));
    }

    public List<VersionSummary> versions(String jobId) {
        require(jobId);
        List<JobVersion> versions = versionMapper.selectList(
                new LambdaQueryWrapper<JobVersion>()
                        .select(JobVersion::getId, JobVersion::getVersionNo, JobVersion::getRemark, JobVersion::getCreatedBy, JobVersion::getCreatedAt)
                        .eq(JobVersion::getJobDefinitionId, jobId)
                        .orderByDesc(JobVersion::getVersionNo));
        Map<String, String> names = users.names(versions.stream().map(JobVersion::getCreatedBy).toList());
        return versions.stream()
                .map(v -> new VersionSummary(
                        v.getId(), v.getVersionNo(), v.getRemark(), v.getCreatedBy(), names.get(v.getCreatedBy()), String.valueOf(v.getCreatedAt())))
                .toList();
    }

    public VersionDetail version(String jobId, String versionId) {
        JobVersion version = requireVersion(versionId);
        if (!version.getJobDefinitionId().equals(jobId)) {
            throw new ConsoleException(ErrorCodes.NOT_FOUND, "version not found: " + versionId);
        }
        return versionDetail(version);
    }

    private void lockJob(String id) {
        if (jobMapper.selectOne(new LambdaQueryWrapper<JobDefinition>().eq(JobDefinition::getId, id).last("for update")) == null) {
            throw new ConsoleException(ErrorCodes.NOT_FOUND, "job not found: " + id);
        }
    }

    /**
     * Edit rights on a job that may have been deleted since, e.g. to stop one of its runs: those
     * follow the job's creator too.
     */
    public void requireEditableIncludingDeleted(String id) {
        JobDefinition job = jobMapper.selectNamesIncludingDeleted(List.of(id)).stream().findFirst()
                .orElseThrow(() -> new ConsoleException(ErrorCodes.NOT_FOUND, "job not found: " + id));
        Access.requireOwner(job.getCreatedBy(), "任务「" + job.getName() + "」");
    }

    /** The job, if the current user may change or run it (its creator or an administrator). */
    public JobDefinition requireEditable(String id) {
        JobDefinition job = require(id);
        Access.requireOwner(job.getCreatedBy(), "任务「" + job.getName() + "」");
        return job;
    }

    /**
     * A job may only use datasources of its own creator (docs/design.md §2), also when an
     * administrator edits it; an administrator moves datasources by transferring ownership.
     */
    private void requireOwnersDatasources(JobDefinition job, Collection<String> datasourceIds) {
        for (String id : new LinkedHashSet<>(datasourceIds)) {
            if (id == null) {
                continue;
            }
            // Locked inside a version save, so a concurrent delete waits and then sees the new reference.
            var ds = datasourceService.requireLocked(id);
            if (!Objects.equals(ds.getCreatedBy(), job.getCreatedBy())) {
                throw new ConsoleException(ErrorCodes.INVALID_ARGUMENT,
                        "数据源「" + ds.getName() + "」不属于任务的创建人，任务只能使用创建人自己的数据源");
            }
        }
    }

    /** Datasources a validation counts on by itself (custom SQL or ES); AUTO uses the job's source. */
    private static List<String> validationDatasources(ValidationSpec validation) {
        List<String> ids = new ArrayList<>();
        if (validation == null || !validation.enabled()) {
            return ids;
        }
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
            if (side != null && side.mode() != ValidationSpec.Mode.AUTO) {
                ids.add(side.datasourceId());
            }
        }
        return ids;
    }

    private static List<String> wizardDatasources(WizardForm form) {
        List<String> ids = new ArrayList<>();
        if (form.source() != null) {
            ids.add(form.source().datasourceId());
        }
        if (form.target() != null) {
            ids.add(form.target().datasourceId());
        }
        return ids;
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
            return new HashMap<>();
        }
        return versionMapper
                .selectList(new LambdaQueryWrapper<JobVersion>()
                        .select(JobVersion::getId, JobVersion::getVersionNo)
                        .in(JobVersion::getId, ids))
                .stream()
                .collect(Collectors.toMap(JobVersion::getId, JobVersion::getVersionNo));
    }

    private JobView view(JobDefinition job, Integer currentVersionNo) {
        return view(job, currentVersionNo, projectNames(List.of(job.getProjectId())).get(job.getProjectId()),
                users.names(Collections.singletonList(job.getCreatedBy())), activities(List.of(job.getId())).get(job.getId()));
    }

    private Map<String, Activity> activities(List<String> jobIds) {
        Map<String, Activity> result = new HashMap<>();
        if (jobIds.isEmpty()) {
            return result;
        }
        Map<String, JobInstance> latest = instanceMapper.selectLatestPerJob(jobIds).stream()
                .collect(Collectors.toMap(JobInstance::getJobDefinitionId, i -> i, (a, b) -> a));
        Map<String, JobSchedule> schedules = scheduleMapper.selectByIds(jobIds).stream()
                .collect(Collectors.toMap(JobSchedule::getJobDefinitionId, s -> s));
        for (String id : jobIds) {
            JobInstance run = latest.get(id);
            JobSchedule schedule = schedules.get(id);
            LocalDateTime at = run == null ? null : run.getStartedAt() != null ? run.getStartedAt() : run.getCreatedAt();
            result.put(id, new Activity(
                    run == null ? null : run.getId(),
                    run == null ? null : run.getStatus(),
                    at == null ? null : at.toString(),
                    schedule != null && Boolean.TRUE.equals(schedule.getEnabled()),
                    schedule == null ? null : schedule.getCron()));
        }
        return result;
    }

    private JobView view(
            JobDefinition job, Integer currentVersionNo, String projectName, Map<String, String> owners, Activity activity) {
        return new JobView(
                job.getId(),
                job.getProjectId(),
                projectName,
                job.getName(),
                job.getJobType(),
                job.getEditMode(),
                currentVersionNo,
                job.getDescription(),
                job.getCreatedBy(),
                owners.get(job.getCreatedBy()),
                Access.canEdit(job.getCreatedBy()),
                job.getUpdatedAt() == null ? null : job.getUpdatedAt().toString(),
                activity);
    }

    private Map<String, String> projectNames(List<String> projectIds) {
        if (projectIds.isEmpty()) {
            return new HashMap<>();
        }
        return projectMapper.selectByIds(projectIds.stream().distinct().toList()).stream()
                .collect(Collectors.toMap(p -> p.getId(), p -> p.getName()));
    }

}
