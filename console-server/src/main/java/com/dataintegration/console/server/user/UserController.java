package com.dataintegration.console.server.user;

import com.dataintegration.console.common.Result;
import com.dataintegration.console.server.user.UserService.Transferred;
import com.dataintegration.console.server.user.UserService.UserView;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/users")
public class UserController {

    public record CreateRequest(
            @NotBlank String username, @Size(max = 64) String displayName, @NotBlank String role, @NotBlank String password) {}

    public record UpdateRequest(@Size(max = 64) String displayName, @NotBlank String role, boolean enabled) {}

    public record PasswordRequest(@NotBlank String password) {}

    public record TransferRequest(@NotBlank String toUserId) {}

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    public Result<List<UserView>> list() {
        return Result.ok(userService.list());
    }

    @PostMapping
    public Result<UserView> create(@Valid @RequestBody CreateRequest body) {
        return Result.ok(userService.create(body.username(), body.displayName(), body.role(), body.password()));
    }

    @PutMapping("/{id}")
    public Result<UserView> update(@PathVariable String id, @Valid @RequestBody UpdateRequest body) {
        return Result.ok(userService.update(id, body.displayName(), body.role(), body.enabled()));
    }

    @PostMapping("/{id}/reset-password")
    public Result<UserView> resetPassword(@PathVariable String id, @Valid @RequestBody PasswordRequest body) {
        return Result.ok(userService.resetPassword(id, body.password()));
    }

    @PostMapping("/{id}/transfer")
    public Result<Transferred> transfer(@PathVariable String id, @Valid @RequestBody TransferRequest body) {
        return Result.ok(userService.transfer(id, body.toUserId()));
    }
}
