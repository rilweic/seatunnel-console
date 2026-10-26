package com.dataintegration.console.server.health;

import com.dataintegration.console.common.ConsoleException;
import com.dataintegration.console.engine.EngineClient;
import com.dataintegration.console.engine.EngineJobStatus;
import com.dataintegration.console.engine.SubmitJobRequest;
import com.dataintegration.console.engine.SubmitJobRequest.RestoreMode;
import com.dataintegration.console.server.health.ClusterHealthService.Check;
import com.dataintegration.console.server.health.ClusterHealthService.Status;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.function.BooleanSupplier;

/**
 * The "实时任务检查" of the cluster diagnostics (docs/design.md §12 P2, Q31): whether checkpoints
 * and savepoints actually work, by doing what a real-time job's stop and start do with a tiny
 * streaming job: wait for a completed checkpoint, stop with a savepoint, restore from it, stop.
 * This tests the checkpoint storage as configured rather than reading the configuration, which
 * the REST API does not expose. Every step that fails says which one and why.
 */
@Component
public class StreamProbe {

    static final String NAME = "checkpoint 与 savepoint";
    private static final Duration STEP_TIMEOUT = Duration.ofSeconds(40);
    private static final Duration POLL = Duration.ofSeconds(1);

    /** One row per split second; a checkpoint every two seconds so the wait is short. */
    static final String JOB = """
            env { job.mode = "STREAMING", parallelism = 1, "checkpoint.interval" = 2000 }
            source { FakeSource { row.num = 1, split.num = 1, split.read-interval = 1000, schema = { fields { id = "int" } }, plugin_output = "fake" } }
            sink { Console { plugin_input = ["fake"] } }
            """;

    private final EngineClient engine;

    public StreamProbe(EngineClient engine) {
        this.engine = engine;
    }

    public Check run() {
        long jobId;
        try {
            jobId = engine.submit(SubmitJobRequest.fresh("console-diagnostic: stream", JOB));
        } catch (ConsoleException e) {
            return error("提交实时诊断作业被拒绝：" + e.getMessage() + "（需要 FakeSource 和 Console 连接器）");
        }
        try {
            if (!await(() -> engine.checkpoints(jobId).map(c -> c.completed() > 0).orElse(false))) {
                return error("诊断作业运行 " + STEP_TIMEOUT.toSeconds() + " 秒还没有完成过 checkpoint："
                        + "检查 seatunnel.yaml 的 checkpoint.storage 配置，以及存储目录是否可写");
            }
            engine.stop(jobId, true, false);
            if (!await(() -> status(jobId) == EngineJobStatus.SAVEPOINT_DONE)) {
                return error("带 savepoint 停止没有完成（状态 " + status(jobId) + "）：实时任务的正常停止会失败，只能强制停止");
            }
            engine.submit(new SubmitJobRequest("console-diagnostic: stream", JOB, RestoreMode.SAVEPOINT, jobId, null));
            if (!await(() -> status(jobId) == EngineJobStatus.RUNNING)) {
                return error("从 savepoint 恢复没有成功（状态 " + status(jobId) + "）：实时任务停止后无法接着同步");
            }
            return new Check("实时任务", NAME, Status.OK, "checkpoint 能完成，带 savepoint 停止和从 savepoint 恢复都正常");
        } catch (ConsoleException e) {
            return error("诊断中断：" + e.getMessage());
        } finally {
            try {
                engine.stop(jobId, false, true);
            } catch (ConsoleException e) {
                // already ended
            }
        }
    }

    private EngineJobStatus status(long jobId) {
        return engine.jobInfo(jobId).map(i -> i.status()).orElse(EngineJobStatus.UNKNOWABLE);
    }

    private static boolean await(BooleanSupplier done) {
        long deadline = System.currentTimeMillis() + STEP_TIMEOUT.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (done.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(POLL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private static Check error(String message) {
        return new Check("实时任务", NAME, Status.ERROR, message);
    }
}
