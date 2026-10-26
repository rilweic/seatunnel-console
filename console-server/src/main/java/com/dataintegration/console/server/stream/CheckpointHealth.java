package com.dataintegration.console.server.stream;

import com.dataintegration.console.engine.EngineCheckpoints;

import java.time.Duration;
import java.time.Instant;

/**
 * Whether a running streaming job's checkpoints work (docs/design.md §12 P2, Q31). Without them a
 * savepoint stop may fail and a restore replays much more. Pure.
 */
public final class CheckpointHealth {

    /** Slack on top of three intervals: a checkpoint during the snapshot or under load takes a while. */
    static final Duration GRACE = Duration.ofSeconds(30);

    private CheckpointHealth() {}

    /**
     * @param runningSince when the engine job started
     * @return what is wrong, or null when checkpoints complete as they should
     */
    public static String problem(EngineCheckpoints checkpoints, Duration interval, Instant runningSince, Instant now) {
        if (checkpoints == null) {
            return null;
        }
        Duration late = interval.multipliedBy(3).plus(GRACE);
        if (checkpoints.lastFailedAt() != null && checkpoints.failed() >= 3
                && (checkpoints.lastCompletedAt() == null || checkpoints.lastFailedAt() > checkpoints.lastCompletedAt())) {
            return "checkpoint 失败了 " + checkpoints.failed() + " 次"
                    + (checkpoints.lastFailureReason() == null ? "" : "（" + checkpoints.lastFailureReason() + "）")
                    + "，最近一次没有成功";
        }
        Instant since = checkpoints.lastCompletedAt() == null ? runningSince : Instant.ofEpochMilli(checkpoints.lastCompletedAt());
        if (since != null && Duration.between(since, now).compareTo(late) > 0) {
            long seconds = Duration.between(since, now).toSeconds();
            return checkpoints.lastCompletedAt() == null
                    ? "启动 " + seconds + " 秒了还没有完成过 checkpoint"
                    : "已经 " + seconds + " 秒没有完成 checkpoint";
        }
        return null;
    }
}
