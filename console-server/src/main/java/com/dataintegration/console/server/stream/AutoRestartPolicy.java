package com.dataintegration.console.server.stream;

import com.dataintegration.console.server.persistence.entity.JobInstanceAttempt;
import com.dataintegration.console.server.persistence.entity.JobStream;

import java.time.Duration;
import java.util.List;

/**
 * Whether a failed streaming run restarts on its own (docs/design.md §12 P2, Q20): only when the
 * job asks for it, not after a failure that would just happen again (a table-structure change), and
 * at most {@code max_restarts} times in a row; an attempt that ran {@link #STABLE} long resets the
 * count. Pure.
 */
public final class AutoRestartPolicy {

    static final Duration STABLE = Duration.ofMinutes(10);

    private AutoRestartPolicy() {}

    /**
     * @param attempts the run's attempts, oldest first, the failed one last
     * @param repeats the failure would happen again on restore (e.g. a DDL replayed)
     */
    public static boolean restart(JobStream stream, List<JobInstanceAttempt> attempts, boolean repeats) {
        if (!Boolean.TRUE.equals(stream.getAutoRestart()) || repeats || attempts.isEmpty()) {
            return false;
        }
        return restartsInARow(attempts) < Math.max(0, stream.getMaxRestarts() == null ? 3 : stream.getMaxRestarts());
    }

    /** Restarts since the last attempt that ran stably (the failed attempt itself is not a restart). */
    static int restartsInARow(List<JobInstanceAttempt> attempts) {
        int failedInARow = 0;
        for (int i = attempts.size() - 1; i >= 0; i--) {
            JobInstanceAttempt a = attempts.get(i);
            if (a.getSubmittedAt() != null && a.getFinishedAt() != null
                    && Duration.between(a.getSubmittedAt(), a.getFinishedAt()).compareTo(STABLE) >= 0) {
                break;
            }
            failedInARow++;
        }
        return Math.max(0, failedInARow - 1);
    }
}
