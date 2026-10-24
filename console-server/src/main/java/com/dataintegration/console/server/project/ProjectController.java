package com.dataintegration.console.server.project;

import com.dataintegration.console.common.PageResult;
import com.dataintegration.console.common.Result;
import com.dataintegration.console.server.project.ProjectService.ParamItem;
import com.dataintegration.console.server.project.ProjectService.ProjectView;

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

import java.util.List;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    public record ProjectRequest(@NotBlank @Size(max = 64) String name, @Size(max = 512) String description) {}

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @GetMapping
    public Result<PageResult<ProjectView>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "false") boolean mine,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return Result.ok(projectService.list(keyword, mine, page, size));
    }

    @GetMapping("/{id}")
    public Result<ProjectView> get(@PathVariable String id) {
        return Result.ok(projectService.get(id));
    }

    @PostMapping
    public Result<ProjectView> create(@Valid @RequestBody ProjectRequest body) {
        return Result.ok(projectService.create(body.name(), body.description()));
    }

    @PutMapping("/{id}")
    public Result<ProjectView> update(@PathVariable String id, @Valid @RequestBody ProjectRequest body) {
        return Result.ok(projectService.update(id, body.name(), body.description()));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable String id) {
        projectService.delete(id);
        return Result.ok();
    }

    @GetMapping("/{id}/params")
    public Result<List<ParamItem>> params(@PathVariable String id) {
        return Result.ok(projectService.params(id));
    }

    @PutMapping("/{id}/params")
    public Result<List<ParamItem>> replaceParams(@PathVariable String id, @RequestBody List<ParamItem> body) {
        return Result.ok(projectService.replaceParams(id, body));
    }
}
