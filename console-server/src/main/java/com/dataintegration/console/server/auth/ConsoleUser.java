package com.dataintegration.console.server.auth;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.io.Serializable;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

/** Authenticated principal stored in the HTTP session. Never holds the password hash after login. */
public record ConsoleUser(
        String id,
        String username,
        String displayName,
        String role,
        String passwordHash,
        boolean enabled,
        boolean mustChangePassword,
        Instant authenticatedAt)
        implements UserDetails, Serializable {

    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_USER = "USER";

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    public ConsoleUser withoutPassword() {
        return new ConsoleUser(id, username, displayName, role, null, enabled, mustChangePassword, authenticatedAt);
    }

    public boolean admin() {
        return ROLE_ADMIN.equals(role);
    }

    @Override
    public String toString() {
        return "ConsoleUser[id=" + id + ", username=" + username + ", role=" + role + "]";
    }
}
