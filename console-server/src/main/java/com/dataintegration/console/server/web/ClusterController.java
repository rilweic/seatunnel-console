package com.dataintegration.console.server.web;

import com.dataintegration.console.common.Result;
import com.dataintegration.console.server.auth.Access;
import com.dataintegration.console.server.cluster.ClusterService;
import com.dataintegration.console.server.cluster.ClusterService.ClusterRequest;
import com.dataintegration.console.server.cluster.ClusterService.ClusterView;
import com.dataintegration.console.server.cluster.ClusterService.TestResult;
import com.dataintegration.console.server.cluster.EngineRegistry;
import com.dataintegration.console.server.health.ClusterHealthService;
import com.dataintegration.console.server.health.DiagnosticService;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Cluster registry, overview and the health check page (docs/design.md §4.3, docs/flink-spark.md §5.3). */
@RestController
public class ClusterController {

    private final EngineRegistry engines;
    private final ClusterService clusterService;
    private final ClusterHealthService healthService;
    private final DiagnosticService diagnosticService;

    public ClusterController(
            EngineRegistry engines,
            ClusterService clusterService,
            ClusterHealthService healthService,
            DiagnosticService diagnosticService) {
        this.engines = engines;
        this.clusterService = clusterService;
        this.healthService = healthService;
        this.diagnosticService = diagnosticService;
    }

    @GetMapping("/api/clusters")
    public Result<List<ClusterView>> list() {
        return Result.ok(clusterService.list());
    }

    @PostMapping("/api/clusters")
    public Result<ClusterView> create(@RequestBody ClusterRequest request) {
        return Result.ok(clusterService.create(request));
    }

    @PutMapping("/api/clusters/{id}")
    public Result<ClusterView> update(@PathVariable String id, @RequestBody ClusterRequest request) {
        return Result.ok(clusterService.update(id, request));
    }

    @DeleteMapping("/api/clusters/{id}")
    public Result<Void> delete(@PathVariable String id) {
        clusterService.delete(id);
        return Result.ok(null);
    }

    @PostMapping("/api/clusters/{id}/default")
    public Result<Void> setDefault(@PathVariable String id) {
        clusterService.setDefault(id);
        return Result.ok(null);
    }

    @PostMapping("/api/clusters/test")
    public Result<TestResult> test(
            @RequestBody ClusterRequest request, @RequestParam(required = false) String existingId) {
        return Result.ok(clusterService.testConnection(request, existingId));
    }

    /** Quick checks from the REST API and the console itself. */
    @GetMapping("/api/cluster/health")
    public Result<ClusterHealthService.Health> health(@RequestParam(required = false) String clusterId) {
        return Result.ok(healthService.check(clusterId));
    }

    /** Runs tiny real jobs on the cluster; takes up to a minute and a half. */
    @PostMapping("/api/cluster/diagnose")
    public Result<List<ClusterHealthService.Check>> diagnose(@RequestParam(required = false) String clusterId) {
        Access.requireAdmin();
        return Result.ok(diagnosticService.run(clusterId));
    }

    @GetMapping("/api/cluster/overview")
    public Result<Map<String, String>> overview(@RequestParam(required = false) String clusterId) {
        return Result.ok(engines.clientFor(clusterId).overview());
    }
}
