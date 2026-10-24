package com.dataintegration.console.server.dashboard;

import com.dataintegration.console.common.Result;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping("/api/dashboard")
    public Result<DashboardService.Dashboard> dashboard(@RequestParam(defaultValue = "false") boolean mine) {
        return Result.ok(dashboardService.dashboard(mine));
    }
}
