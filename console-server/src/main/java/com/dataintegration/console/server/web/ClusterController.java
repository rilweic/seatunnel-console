package com.dataintegration.console.server.web;

import com.dataintegration.console.common.Result;
import com.dataintegration.console.engine.EngineClient;

import com.dataintegration.console.server.auth.Access;
import com.dataintegration.console.server.health.ClusterHealthService;
import com.dataintegration.console.server.health.DiagnosticService;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Cluster overview and the health check page (docs/design.md §4.3). */
@RestController
@RequestMapping("/api/cluster")
public class ClusterController {

    private final EngineClient engineClient;
    private final ClusterHealthService healthService;
    private final DiagnosticService diagnosticService;

    public ClusterController(EngineClient engineClient, ClusterHealthService healthService, DiagnosticService diagnosticService) {
        this.engineClient = engineClient;
        this.healthService = healthService;
        this.diagnosticService = diagnosticService;
    }

    /** Quick checks from the REST API and the console itself. */
    @GetMapping("/health")
    public Result<ClusterHealthService.Health> health() {
        return Result.ok(healthService.check());
    }

    /** Runs tiny real jobs on the cluster; takes up to a minute and a half. */
    @PostMapping("/diagnose")
    public Result<List<ClusterHealthService.Check>> diagnose() {
        Access.requireAdmin();
        return Result.ok(diagnosticService.run());
    }

    @GetMapping("/overview")
    public Result<Map<String, String>> overview() {
        return Result.ok(engineClient.overview());
    }
}
