package com.dataintegration.console.server.health;

import com.dataintegration.console.common.ConsoleException;
import com.dataintegration.console.engine.EngineClient;
import com.dataintegration.console.engine.EngineClient.StopResult;
import com.dataintegration.console.engine.EngineJobStatus;
import com.dataintegration.console.engine.EngineType;
import com.dataintegration.console.engine.SubmitJobRequest;
import com.dataintegration.console.engine.SubmitJobRequest.RestoreMode;
import com.dataintegration.console.server.health.ClusterHealthService.Check;
import com.dataintegration.console.server.health.ClusterHealthService.Status;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * The "实时任务检查" of the cluster diagnostics (docs/design.md §12 P2, Q31): whether checkpoints
 * and savepoints actually work, by doing what a real-time job's stop and start do with a tiny
 * streaming job: wait for a completed checkpoint, stop with a savepoint, restore from it, stop.
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

    public Check run(EngineClient engine) {
        // Pre-choose every engine job id and record it before calling submit(), so even a
        // RESPONSE_LOST during the initial or restored submission is still stopped in finally.
        Set<String> submittedIds = new LinkedHashSet<>();
        String initialId = engine.newJobId();
        String savepointPath = null;
        try {
            submittedIds.add(initialId);
            String jobId;
            try {
                jobId = engine.submit(SubmitJobRequest.fresh("console-diagnostic: stream", JOB).withJobId(initialId));
                submittedIds.add(jobId);
            } catch (ConsoleException e) {
                return error("提交实时诊断作业失败：" + e.getMessage() + "（需要 FakeSource 和 Console 连接器）");
            }
            if (!await(() -> engine.checkpoints(jobId).map(c -> c.completed() > 0).orElse(false))) {
                return error("诊断作业运行 " + STEP_TIMEOUT.toSeconds() + " 秒还没有完成过 checkpoint："
                        + "检查集群的 checkpoint 存储配置以及目录是否可写");
            }
            StopResult stopped = engine.stop(jobId, true, false);
            savepointPath = stopped.savepointPath();
            String restoredId;
            if (engine.restoresByExternalPath()) {
                if (savepointPath == null || savepointPath.isBlank()) {
                    return error("带 savepoint 停止没有返回保存路径：检查 Flink 集群的 execution.checkpointing.savepoint-dir 配置");
                }
                String chosenRestoreId = engine.newJobId();
                submittedIds.add(chosenRestoreId);
                restoredId = engine.submit(new SubmitJobRequest(
                        "console-diagnostic: stream", JOB, RestoreMode.SAVEPOINT, null, savepointPath, chosenRestoreId));
                submittedIds.add(restoredId);
            } else {
                if (!await(() -> status(engine, jobId) == EngineJobStatus.SAVEPOINT_DONE)) {
                    return error("带 savepoint 停止没有完成（状态 " + status(engine, jobId) + "）：实时任务的正常停止会失败，只能强制停止");
                }
                restoredId = engine.submit(new SubmitJobRequest(
                        "console-diagnostic: stream", JOB, RestoreMode.SAVEPOINT, jobId, null, null));
                submittedIds.add(restoredId);
            }
            String checkId = restoredId;
            if (!await(() -> status(engine, checkId) == EngineJobStatus.RUNNING)) {
                return error("从 savepoint 恢复没有成功（状态 " + status(engine, checkId) + "）：实时任务停止后无法接着同步");
            }
            return new Check("实时任务", NAME, Status.OK, "checkpoint 能完成，带 savepoint 停止和从 savepoint 恢复都正常");
        } catch (ConsoleException e) {
            return error("诊断中断：" + e.getMessage());
        } finally {
            List<String> toStop = new ArrayList<>(submittedIds);
            for (String id : toStop) {
                try {
                    engine.stop(id, false, true);
                } catch (ConsoleException e) {
                    // already ended or never reached engine
                }
            }
            if (savepointPath != null) {
                try {
                    engine.disposeSavepoint(savepointPath);
                } catch (ConsoleException e) {
                    // best effort
                }
            }
        }
    }

    private static EngineJobStatus status(EngineClient engine, String jobId) {
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
