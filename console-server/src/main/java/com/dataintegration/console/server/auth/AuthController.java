package com.dataintegration.console.server.auth;

import com.dataintegration.console.common.ConsoleException;
import com.dataintegration.console.common.ErrorCodes;
import com.dataintegration.console.common.Result;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}

    public record UserView(String id, String username, String displayName, String role, boolean mustChangePassword) {
        static UserView of(ConsoleUser user) {
            return new UserView(user.id(), user.username(), user.displayName(), user.role(), user.mustChangePassword());
        }
    }

    public record PasswordRequest(@NotBlank String oldPassword, @NotBlank String newPassword) {}

    private final AuthService authService;
    private final SecurityContextRepository contextRepository;

    public AuthController(AuthService authService, SecurityContextRepository contextRepository) {
        this.authService = authService;
        this.contextRepository = contextRepository;
    }

    @PostMapping("/login")
    public Result<UserView> login(
            @Valid @RequestBody LoginRequest body, HttpServletRequest request, HttpServletResponse response) {
        Authentication authentication = authService.authenticate(body.username(), body.password());
        // Prevent session fixation: a pre-login session id must not survive authentication.
        request.getSession(true);
        request.changeSessionId();
        store(authentication, request, response);
        return Result.ok(UserView.of((ConsoleUser) authentication.getPrincipal()));
    }

    /** Changes the logged-in user's own password; also how a must-change account is released. */
    @PutMapping("/password")
    public Result<UserView> changePassword(
            @Valid @RequestBody PasswordRequest body, HttpServletRequest request, HttpServletResponse response) {
        ConsoleUser current = CurrentUser.get()
                .orElseThrow(() -> new ConsoleException(ErrorCodes.UNAUTHENTICATED, "not logged in"));
        Authentication authentication = authService.changePassword(current, body.oldPassword(), body.newPassword());
        store(authentication, request, response);
        return Result.ok(UserView.of((ConsoleUser) authentication.getPrincipal()));
    }

    private void store(Authentication authentication, HttpServletRequest request, HttpServletResponse response) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);
    }

    @GetMapping("/me")
    public Result<UserView> me() {
        return CurrentUser.get()
                .map(user -> Result.ok(UserView.of(user)))
                .orElseThrow(() -> new ConsoleException(ErrorCodes.UNAUTHENTICATED, "not logged in"));
    }
}
