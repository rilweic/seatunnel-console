package com.dataintegration.console.server.project;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataintegration.console.common.ConsoleException;
import com.dataintegration.console.common.ErrorCodes;
import com.dataintegration.console.common.PageResult;
import com.dataintegration.console.server.auth.Access;
import com.dataintegration.console.server.auth.CurrentUser;
import com.dataintegration.console.server.job.Variables;
import com.dataintegration.console.server.persistence.entity.JobDefinition;
import com.dataintegration.console.server.persistence.entity.Project;
import com.dataintegration.console.server.persistence.entity.ProjectParam;
import com.dataintegration.console.server.persistence.mapper.JobDefinitionMapper;
import com.dataintegration.console.server.persistence.mapper.ProjectMapper;
import com.dataintegration.console.server.persistence.mapper.ProjectParamMapper;
import com.dataintegration.console.server.user.UserDirectory;
import com.dataintegration.console.server.web.Pages;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class ProjectService {

    /** @param editable whether the current user may change it (creator or administrator) */
    public record ProjectView(
            String id, String name, String description, long jobCount, String ownerId, String ownerName, boolean editable, String createdAt) {}

    public record ParamItem(String key, String value, String description) {}

    private final ProjectMapper projectMapper;
    private final ProjectParamMapper paramMapper;
    private final JobDefinitionMapper jobMapper;
    private final UserDirectory users;

    public ProjectService(
            ProjectMapper projectMapper, ProjectParamMapper paramMapper, JobDefinitionMapper jobMapper, UserDirectory users) {
        this.projectMapper = projectMapper;
        this.paramMapper = paramMapper;
        this.jobMapper = jobMapper;
        this.users = users;
    }

    /** @param mine only the current user's projects */
    public PageResult<ProjectView> list(String keyword, boolean mine, Integer page, Integer size) {
        Page<Project> result =
                projectMapper.selectPage(
                        Pages.of(page, size),
                        new LambdaQueryWrapper<Project>()
                                .like(keyword != null && !keyword.isBlank(), Project::getName, Pages.literal(keyword))
                                .eq(mine, Project::getCreatedBy, CurrentUser.idOrNull())
                                .orderByDesc(Project::getCreatedAt));
        Map<String, Long> jobCounts = jobCounts(result.getRecords().stream().map(Project::getId).toList());
        Map<String, String> owners = users.names(result.getRecords().stream().map(Project::getCreatedBy).toList());
        return Pages.result(result).map(p -> view(p, jobCounts.getOrDefault(p.getId(), 0L), owners));
    }

    public ProjectView get(String id) {
        Project project = require(id);
        return view(project, jobCounts(List.of(id)).getOrDefault(id, 0L), users.names(Collections.singletonList(project.getCreatedBy())));
    }

    public ProjectView create(String name, String description) {
        Project project = new Project();
        project.setName(name.trim());
        project.setDescription(description);
        project.setCreatedBy(CurrentUser.idOrNull());
        projectMapper.insert(project);
        return get(project.getId());
    }

    public ProjectView update(String id, String name, String description) {
        requireEditable(id);
        // Only the edited columns, so a concurrent ownership transfer is not undone.
        Project update = new Project();
        update.setId(id);
        update.setName(name.trim());
        projectMapper.update(update, new LambdaUpdateWrapper<Project>()
                .set(Project::getDescription, description)
                .eq(Project::getId, id));
        return get(id);
    }

    /** Soft delete; refused while the project still has live jobs. */
    @Transactional
    public void delete(String id) {
        requireEditable(id);
        if (jobCounts(List.of(id)).getOrDefault(id, 0L) > 0) {
            throw new ConsoleException(ErrorCodes.CONFLICT, "project still has jobs, delete them first");
        }
        projectMapper.deleteById(id);
    }

    public List<ParamItem> params(String projectId) {
        require(projectId);
        return paramMapper
                .selectList(
                        new LambdaQueryWrapper<ProjectParam>()
                                .eq(ProjectParam::getProjectId, projectId)
                                .orderByAsc(ProjectParam::getParamKey))
                .stream()
                .map(p -> new ParamItem(p.getParamKey(), p.getParamValue(), p.getDescription()))
                .toList();
    }

    /** Replaces the whole parameter set, which is how the UI edits it. */
    @Transactional
    public List<ParamItem> replaceParams(String projectId, List<ParamItem> items) {
        requireEditable(projectId);
        Set<String> seen = new HashSet<>();
        for (ParamItem item : items) {
            if (item.key() == null || item.key().isBlank()) {
                throw new ConsoleException(ErrorCodes.INVALID_ARGUMENT, "parameter key must not be empty");
            }
            Variables.validateName(item.key().trim());
            if (!seen.add(item.key().trim())) {
                throw new ConsoleException(ErrorCodes.INVALID_ARGUMENT, "duplicate parameter " + item.key());
            }
        }
        paramMapper.delete(new LambdaQueryWrapper<ProjectParam>().eq(ProjectParam::getProjectId, projectId));
        for (ParamItem item : items) {
            ProjectParam param = new ProjectParam();
            param.setProjectId(projectId);
            param.setParamKey(item.key().trim());
            param.setParamValue(item.value() == null ? "" : item.value());
            param.setDescription(item.description());
            param.setCreatedBy(CurrentUser.idOrNull());
            paramMapper.insert(param);
        }
        return params(projectId);
    }

    /** The project, if the current user may change it or add jobs to it. */
    public Project requireEditable(String id) {
        Project project = require(id);
        Access.requireOwner(project.getCreatedBy(), "项目「" + project.getName() + "」");
        return project;
    }

    public Project require(String id) {
        Project project = projectMapper.selectById(id);
        if (project == null) {
            throw new ConsoleException(ErrorCodes.NOT_FOUND, "project not found: " + id);
        }
        return project;
    }

    private Map<String, Long> jobCounts(List<String> projectIds) {
        if (projectIds.isEmpty()) {
            return new HashMap<>();
        }
        return jobMapper
                .selectList(
                        new LambdaQueryWrapper<JobDefinition>()
                                .select(JobDefinition::getProjectId)
                                .in(JobDefinition::getProjectId, projectIds))
                .stream()
                .collect(Collectors.groupingBy(JobDefinition::getProjectId, Collectors.counting()));
    }

    private static ProjectView view(Project p, long jobCount, Map<String, String> owners) {
        return new ProjectView(
                p.getId(),
                p.getName(),
                p.getDescription(),
                jobCount,
                p.getCreatedBy(),
                owners.get(p.getCreatedBy()),
                Access.canEdit(p.getCreatedBy()),
                p.getCreatedAt() == null ? null : p.getCreatedAt().toString());
    }
}
