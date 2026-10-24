package com.dataintegration.console.server.auth;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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

    /**
     * Inside a transaction the change is recorded only after it commits: recorded earlier, a
     * request could refresh its session from the old row in between and then count as up to date.
     */
    public void changed(String userId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    changedAt.put(userId, Instant.now());
                }
            });
        } else {
            changedAt.put(userId, Instant.now());
        }
    }

    /** Whether the account changed after a session authenticated at {@code authenticatedAt}. */
    public boolean changedSince(String userId, Instant authenticatedAt) {
        Instant changed = changedAt.get(userId);
        return changed != null && (authenticatedAt == null || !changed.isBefore(authenticatedAt));
    }
}
