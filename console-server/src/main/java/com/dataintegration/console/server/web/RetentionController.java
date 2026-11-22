package com.dataintegration.console.server.web;

import com.dataintegration.console.common.Result;
import com.dataintegration.console.server.auth.Access;
import com.dataintegration.console.server.auth.CurrentUser;
import com.dataintegration.console.server.retention.RetentionService;
import com.dataintegration.console.server.retention.RetentionService.CleanupLogView;
import com.dataintegration.console.server.retention.RetentionService.PolicyView;
import com.dataintegration.console.server.retention.RetentionService.StorageOverview;
import com.dataintegration.console.server.retention.RetentionService.UpdatePolicyRequest;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Administrator-only endpoints for historical data retention policy and manual cleanup. */
@RestController
@RequestMapping("/api/retention")
public class RetentionController {

    private final RetentionService retentionService;

    public RetentionController(RetentionService retentionService) {
        this.retentionService = retentionService;
    }

    @GetMapping("/policy")
    public Result<PolicyView> getPolicy() {
        Access.requireAdmin();
        return Result.ok(retentionService.getPolicy());
    }

    @PutMapping("/policy")
    public Result<PolicyView> updatePolicy(@RequestBody UpdatePolicyRequest request) {
        Access.requireAdmin();
        return Result.ok(retentionService.updatePolicy(request, CurrentUser.idOrNull()));
    }

    @GetMapping("/stats")
    public Result<StorageOverview> getStats() {
        Access.requireAdmin();
        return Result.ok(retentionService.getStats());
    }

    @GetMapping("/history")
    public Result<List<CleanupLogView>> listHistory(@RequestParam(defaultValue = "20") int limit) {
        Access.requireAdmin();
        return Result.ok(retentionService.listHistory(limit));
    }

    @PostMapping("/run")
    public Result<CleanupLogView> runNow() {
        Access.requireAdmin();
        return Result.ok(retentionService.runNow(CurrentUser.idOrNull()));
    }
}
