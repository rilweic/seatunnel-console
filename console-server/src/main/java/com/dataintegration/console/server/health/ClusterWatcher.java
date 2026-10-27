package com.dataintegration.console.server.health;

import com.dataintegration.console.engine.EngineClient;
import com.dataintegration.console.engine.MasterStatus;
import com.dataintegration.console.server.message.MessageCategory;
import com.dataintegration.console.server.message.Messages;
import com.dataintegration.console.server.run.RunRecorder.Level;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Tells the admins when no SeaTunnel master answers for a few checks in a row, and when one does
 * again (docs/design.md §10.7). A single missed check is not reported: a master restarting or a
 * network blip would otherwise raise a message every time.
 */
@Component
public class ClusterWatcher {

    private static final Logger log = LoggerFactory.getLogger(ClusterWatcher.class);

    static final int FAILURES_TO_REPORT = 3;
    private static final String SUBJECT = "seatunnel";

    private final EngineClient engine;
    private final Messages messages;

    private int failures;
    /** Whether this outage's message is written; until then each failing check tries again. */
    private boolean reported;
    /**
     * Whether the recovery is written; false until then, which also resolves a message left open by
     * an earlier console process.
     */
    private boolean knownUp;

    public ClusterWatcher(EngineClient engine, Messages messages) {
        this.engine = engine;
        this.messages = messages;
    }

    @Scheduled(initialDelayString = "${console.message.cluster-initial-delay:1m}", fixedDelayString = "${console.message.cluster-interval:60s}")
    public void check() {
        List<MasterStatus> masters;
        try {
            masters = engine.probeMasters();
        } catch (RuntimeException e) {
            log.warn("Probing the SeaTunnel masters failed: {}", e.toString());
            return;
        }
        if (masters.isEmpty()) {
            return;
        }
        if (masters.stream().anyMatch(MasterStatus::reachable)) {
            failures = 0;
            reported = false;
            if (!knownUp) {
                knownUp = messages.resolve(SUBJECT, MessageCategory.CLUSTER);
            }
            return;
        }
        knownUp = false;
        if (++failures >= FAILURES_TO_REPORT && !reported) {
            reported = messages.system(MessageCategory.CLUSTER, Level.ERROR, "/cluster", SUBJECT, "SeaTunnel 集群连不上",
                    "连续 " + FAILURES_TO_REPORT + " 次检查所有 master 都连不上（" + String.join("；",
                            masters.stream().map(m -> m.url() + "：" + m.error()).toList())
                            + "）。这段时间离线运行会提交失败，实时任务的状态无法更新");
        }
    }
}
