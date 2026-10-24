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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}

    public record UserView(String id, String username, String displayName, String role) {
        static UserView of(ConsoleUser user) {
            return new UserView(user.id(), user.username(), user.displayName(), user.role());
        }
    }

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
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);
        return Result.ok(UserView.of((ConsoleUser) authentication.getPrincipal()));
    }

    @GetMapping("/me")
    public Result<UserView> me() {
        return CurrentUser.get()
                .map(user -> Result.ok(UserView.of(user)))
                .orElseThrow(() -> new ConsoleException(ErrorCodes.UNAUTHENTICATED, "not logged in"));
    }
}
