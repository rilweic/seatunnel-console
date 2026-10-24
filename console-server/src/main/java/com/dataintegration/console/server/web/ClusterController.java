package com.dataintegration.console.server.web;

import com.dataintegration.console.common.Result;
import com.dataintegration.console.engine.EngineClient;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Cluster information; grows into the health check page (docs/design.md §4.3). */
@RestController
@RequestMapping("/api/cluster")
public class ClusterController {

    private final EngineClient engineClient;

    public ClusterController(EngineClient engineClient) {
        this.engineClient = engineClient;
    }

    @GetMapping("/overview")
    public Result<Map<String, String>> overview() {
        return Result.ok(engineClient.overview());
    }
}
