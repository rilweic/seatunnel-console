package com.dataintegration.console.server.project;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataintegration.console.common.ConsoleException;
import com.dataintegration.console.common.ErrorCodes;
import com.dataintegration.console.common.PageResult;
import com.dataintegration.console.server.auth.CurrentUser;
import com.dataintegration.console.server.job.Variables;
import com.dataintegration.console.server.persistence.entity.JobDefinition;
import com.dataintegration.console.server.persistence.entity.Project;
import com.dataintegration.console.server.persistence.entity.ProjectParam;
import com.dataintegration.console.server.persistence.mapper.JobDefinitionMapper;
import com.dataintegration.console.server.persistence.mapper.ProjectMapper;
import com.dataintegration.console.server.persistence.mapper.ProjectParamMapper;
import com.dataintegration.console.server.web.Pages;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class ProjectService {

    public record ProjectView(String id, String name, String description, long jobCount, String createdAt) {}

    public record ParamItem(String key, String value, String description) {}

    private final ProjectMapper projectMapper;
    private final ProjectParamMapper paramMapper;
    private final JobDefinitionMapper jobMapper;

    public ProjectService(ProjectMapper projectMapper, ProjectParamMapper paramMapper, JobDefinitionMapper jobMapper) {
        this.projectMapper = projectMapper;
        this.paramMapper = paramMapper;
        this.jobMapper = jobMapper;
    }

    public PageResult<ProjectView> list(String keyword, Integer page, Integer size) {
        Page<Project> result =
                projectMapper.selectPage(
                        Pages.of(page, size),
                        new LambdaQueryWrapper<Project>()
                                .like(keyword != null && !keyword.isBlank(), Project::getName, keyword)
                                .orderByDesc(Project::getCreatedAt));
        Map<String, Long> jobCounts = jobCounts(result.getRecords().stream().map(Project::getId).toList());
        return Pages.result(result).map(p -> view(p, jobCounts.getOrDefault(p.getId(), 0L)));
    }

    public ProjectView get(String id) {
        Project project = require(id);
        return view(project, jobCounts(List.of(id)).getOrDefault(id, 0L));
    }

    public ProjectView create(String name, String description) {
        Project project = new Project();
        project.setName(name.trim());
        project.setDescription(description);
        project.setCreatedBy(CurrentUser.idOrNull());
        projectMapper.insert(project);
        return view(project, 0);
    }

    public ProjectView update(String id, String name, String description) {
        Project project = require(id);
        project.setName(name.trim());
        project.setDescription(description);
        projectMapper.updateById(project);
        return get(id);
    }

    /** Soft delete; refused while the project still has live jobs. */
    @Transactional
    public void delete(String id) {
        require(id);
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
        require(projectId);
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

    public Project require(String id) {
        Project project = projectMapper.selectById(id);
        if (project == null) {
            throw new ConsoleException(ErrorCodes.NOT_FOUND, "project not found: " + id);
        }
        return project;
    }

    private Map<String, Long> jobCounts(List<String> projectIds) {
        if (projectIds.isEmpty()) {
            return Map.of();
        }
        return jobMapper
                .selectList(
                        new LambdaQueryWrapper<JobDefinition>()
                                .select(JobDefinition::getProjectId)
                                .in(JobDefinition::getProjectId, projectIds))
                .stream()
                .collect(Collectors.groupingBy(JobDefinition::getProjectId, Collectors.counting()));
    }

    private static ProjectView view(Project p, long jobCount) {
        return new ProjectView(
                p.getId(),
                p.getName(),
                p.getDescription(),
                jobCount,
                p.getCreatedAt() == null ? null : p.getCreatedAt().toString());
    }
}
