package com.dataintegration.console.server.datasource;

import com.dataintegration.console.common.PageResult;
import com.dataintegration.console.common.Result;
import com.dataintegration.console.datasource.ConnectionTestResult;
import com.dataintegration.console.datasource.meta.ColumnMeta;
import com.dataintegration.console.datasource.meta.TableMeta;
import com.dataintegration.console.datasource.DataSourcePlugins;
import com.dataintegration.console.server.datasource.DatasourceService.DatasourceView;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
import java.util.Map;

@RestController
@RequestMapping("/api/datasources")
public class DatasourceController {

    public record CreateRequest(
            @NotBlank @Size(max = 64) String name,
            @NotBlank String type,
            @Size(max = 512) String description,
            Map<String, String> props) {}

    public record UpdateRequest(
            @NotBlank @Size(max = 64) String name, @Size(max = 512) String description, Map<String, String> props) {}

    /** {@code datasourceId} is set when testing edits of a saved datasource. */
    public record TestRequest(String datasourceId, String type, Map<String, String> props) {}

    private final DatasourceService datasourceService;

    public DatasourceController(DatasourceService datasourceService) {
        this.datasourceService = datasourceService;
    }

    @GetMapping
    public Result<PageResult<DatasourceView>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return Result.ok(datasourceService.list(keyword, type, page, size));
    }

    @GetMapping("/{id}")
    public Result<DatasourceView> get(@PathVariable String id) {
        return Result.ok(datasourceService.get(id));
    }

    @PostMapping
    public Result<DatasourceView> create(@Valid @RequestBody CreateRequest body) {
        return Result.ok(datasourceService.create(body.name(), body.type(), body.description(), body.props()));
    }

    @PutMapping("/{id}")
    public Result<DatasourceView> update(@PathVariable String id, @Valid @RequestBody UpdateRequest body) {
        return Result.ok(datasourceService.update(id, body.name(), body.description(), body.props()));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable String id) {
        datasourceService.delete(id);
        return Result.ok();
    }

    /** Databases (MySQL) or schemas (PostgreSQL) of a saved datasource; empty for Elasticsearch. */
    @GetMapping("/{id}/namespaces")
    public Result<List<String>> namespaces(@PathVariable String id) {
        return Result.ok(datasourceService.namespaces(id));
    }

    @GetMapping("/{id}/tables")
    public Result<List<TableMeta>> tables(@PathVariable String id, @RequestParam(required = false) String namespace) {
        return Result.ok(datasourceService.tables(id, namespace));
    }

    @GetMapping("/{id}/columns")
    public Result<List<ColumnMeta>> columns(
            @PathVariable String id, @RequestParam(required = false) String namespace, @RequestParam String table) {
        return Result.ok(datasourceService.columns(id, namespace, table));
    }

    @PostMapping("/test")
    public Result<ConnectionTestResult> test(@RequestBody TestRequest body) {
        return Result.ok(datasourceService.test(body.datasourceId(), body.type(), body.props()));
    }

    @PostMapping("/{id}/test")
    public Result<ConnectionTestResult> testSaved(@PathVariable String id) {
        return Result.ok(datasourceService.testSaved(id));
    }

    @Configuration(proxyBeanMethods = false)
    static class PluginConfig {
        @Bean
        DataSourcePlugins dataSourcePlugins() {
            return new DataSourcePlugins();
        }
    }
}
