package com.dataintegration.console.server.auth;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

/** Access to the logged-in user for audit columns such as created_by. */
public final class CurrentUser {

    private CurrentUser() {}

    public static Optional<ConsoleUser> get() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof ConsoleUser user ? Optional.of(user) : Optional.empty();
    }

    /** User id, or null outside a request (e.g. scheduled runs). */
    public static String idOrNull() {
        return get().map(ConsoleUser::id).orElse(null);
    }
}
