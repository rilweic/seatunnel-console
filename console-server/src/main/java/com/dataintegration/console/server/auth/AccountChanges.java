package com.dataintegration.console.server.auth;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * When an administrator last changed each account (role, enabled, password reset), so sessions
 * logged in before that pick up the change on their next request. In memory: the console is a
 * single instance and sessions do not survive a restart either.
 */
@Component
public class AccountChanges {

    private final Map<String, Instant> changedAt = new ConcurrentHashMap<>();

    public void changed(String userId) {
        changedAt.put(userId, Instant.now());
    }

    /** Whether the account changed after a session authenticated at {@code authenticatedAt}. */
    public boolean changedSince(String userId, Instant authenticatedAt) {
        Instant changed = changedAt.get(userId);
        return changed != null && (authenticatedAt == null || !changed.isBefore(authenticatedAt));
    }
}
