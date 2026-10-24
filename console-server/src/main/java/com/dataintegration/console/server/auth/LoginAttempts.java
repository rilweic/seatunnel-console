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

    /** Failures older than this are forgotten, so an account is locked only by a burst of them. */
    static final Duration WINDOW = Duration.ofMinutes(10);

    /**
     * Tries with made-up user names are recorded too (refusing them differently would tell which
     * names exist), so the map is bounded: past this size, stale entries are dropped.
     */
    static final int MAX_ENTRIES = 10_000;

    private record State(int failures, Instant lockedUntil, Instant lastFailure) {}

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
        Instant now = Instant.now();
        if (states.size() >= MAX_ENTRIES) {
            prune(now);
        }
        states.compute(key(username), (k, s) -> {
            boolean recent = s != null && s.lastFailure() != null && s.lastFailure().isAfter(now.minus(WINDOW));
            int failures = (recent ? s.failures() : 0) + 1;
            return failures >= MAX_FAILURES ? new State(0, now.plus(LOCK), now) : new State(failures, null, now);
        });
    }

    /** Drops entries that no longer lock anything; if that is not enough, all unlocked ones. */
    private void prune(Instant now) {
        states.entrySet().removeIf(e -> {
            State s = e.getValue();
            boolean locked = s.lockedUntil() != null && s.lockedUntil().isAfter(now);
            return !locked && (s.lastFailure() == null || !s.lastFailure().isAfter(now.minus(WINDOW)));
        });
        if (states.size() >= MAX_ENTRIES) {
            states.entrySet().removeIf(e -> e.getValue().lockedUntil() == null);
        }
    }

    int size() {
        return states.size();
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
