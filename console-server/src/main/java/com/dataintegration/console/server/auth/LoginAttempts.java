package com.dataintegration.console.server.auth;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Locks an account for a while after repeated wrong passwords. Kept in memory: the console runs
 * as a single instance, and a restart clearing the locks is acceptable.
 */
@Component
public class LoginAttempts {

    static final int MAX_FAILURES = 5;
    static final Duration LOCK = Duration.ofMinutes(10);

    private record State(int failures, Instant lockedUntil) {}

    private final Map<String, State> states = new ConcurrentHashMap<>();

    /** Remaining lock time, or null when the account may try to log in. */
    public Duration lockedFor(String username) {
        State state = states.get(key(username));
        if (state == null || state.lockedUntil() == null) {
            return null;
        }
        Duration left = Duration.between(Instant.now(), state.lockedUntil());
        if (left.isNegative() || left.isZero()) {
            states.remove(key(username));
            return null;
        }
        return left;
    }

    public void failed(String username) {
        states.compute(key(username), (k, s) -> {
            int failures = (s == null ? 0 : s.failures()) + 1;
            return failures >= MAX_FAILURES ? new State(0, Instant.now().plus(LOCK)) : new State(failures, null);
        });
    }

    public void succeeded(String username) {
        states.remove(key(username));
    }

    /** Manual unlock, e.g. when an admin resets the password. */
    public void clear(String username) {
        states.remove(key(username));
    }

    private static String key(String username) {
        return username == null ? "" : username.trim().toLowerCase();
    }
}
