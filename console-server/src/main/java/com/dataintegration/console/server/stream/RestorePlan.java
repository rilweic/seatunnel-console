package com.dataintegration.console.server.stream;

import com.dataintegration.console.common.ConsoleException;
import com.dataintegration.console.common.ErrorCodes;
import com.dataintegration.console.engine.SubmitJobRequest.RestoreMode;
import com.dataintegration.console.server.persistence.entity.JobStream;

/**
 * Where a start of a streaming job reads from (docs/design.md §12 P2, Q10 and Q27): the savepoint
 * of a graceful stop, the latest checkpoint of a failed or forced-stopped run, or a fresh start.
 * Pure, so the rules are tested on their own.
 */
public final class RestorePlan {

    /** What the person starting the job asked for. */
    public enum Asked {
        /** the best available: a restore point unless the source changed or restoring would fail */
        AUTO,
        SAVEPOINT,
        CHECKPOINT,
        FRESH
    }

    /** Where the restore point comes from: a graceful stop or the latest checkpoint. */
    public enum Kind {
        SAVEPOINT,
        CHECKPOINT
    }

    /**
     * @param restoreFrom the engine job to restore from; null for a fresh start
     * @param note why it starts the way it does, for the run's timeline
     */
    public record Decision(RestoreMode mode, Long restoreFrom, String note) {

        public boolean fresh() {
            return mode == RestoreMode.NONE;
        }
    }

    private RestorePlan() {}

    /**
     * @param sourceChanged the current version reads differently from the version the restore point
     *     was taken with; restoring then fails (a new table gets no target table) or mixes positions
     */
    public static Decision decide(JobStream stream, Asked asked, boolean sourceChanged) {
        Kind available = stream.getRestoreEngineJobId() == null || stream.getRestoreKind() == null
                ? null : Kind.valueOf(stream.getRestoreKind());
        Long from = available == null ? null : Long.valueOf(stream.getRestoreEngineJobId());
        if (asked == Asked.FRESH) {
            return new Decision(RestoreMode.NONE, null, "全新启动" + (available == null ? "" : "，不使用上次保存的位置"));
        }
        if (stream.getRestoreBlocked() != null) {
            if (asked != Asked.AUTO) {
                throw new ConsoleException(ErrorCodes.INVALID_ARGUMENT, "不能恢复：" + stream.getRestoreBlocked() + "。请全新启动");
            }
            return new Decision(RestoreMode.NONE, null, "全新启动：" + stream.getRestoreBlocked());
        }
        if (asked == Asked.AUTO) {
            if (available == null) {
                return new Decision(RestoreMode.NONE, null, "全新启动：没有可以恢复的位置");
            }
            if (sourceChanged) {
                return new Decision(RestoreMode.NONE, null, "全新启动：当前版本的来源设置和保存位置时的版本不同，从保存的位置恢复会失败");
            }
            return restore(available, from);
        }
        Kind wanted = asked == Asked.SAVEPOINT ? Kind.SAVEPOINT : Kind.CHECKPOINT;
        if (available != wanted) {
            throw new ConsoleException(ErrorCodes.INVALID_ARGUMENT, available == null
                    ? "没有可以恢复的位置，只能全新启动"
                    : "上次是" + (available == Kind.SAVEPOINT ? "正常停止，只能从 savepoint 恢复" : "失败或强制停止，只能从 checkpoint 恢复"));
        }
        return restore(available, from);
    }

    private static Decision restore(Kind kind, long from) {
        return kind == Kind.SAVEPOINT
                ? new Decision(RestoreMode.SAVEPOINT, from, "从上次停止时的 savepoint 恢复（引擎作业 " + from + "）")
                : new Decision(RestoreMode.CHECKPOINT, from, "从最近的 checkpoint 恢复（引擎作业 " + from + "）");
    }
}
