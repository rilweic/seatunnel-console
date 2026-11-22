package com.dataintegration.console.server.run;

import com.dataintegration.console.common.ConsoleException;
import com.dataintegration.console.engine.EngineClient;
import com.dataintegration.console.engine.EngineClient.LogContent;
import com.dataintegration.console.engine.EngineLogFile;
import com.dataintegration.console.server.cluster.EngineRegistry;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Collects a SeaTunnel job's log from every engine node into one text, each node's part headed by
 * its node and file name. Used both for live viewing and for archiving (docs/schema.md §5).
 */
@Component
public class EngineLogService {

    /** Same limit as Zeta's default log-response-max-size-mb. */
    public static final long MAX_BYTES = 64L * 1024 * 1024;
    /** Per-file cap for shared node logs (Flink JM/TM) so live viewing stays lightweight. */
    private static final long SHARED_NODE_LOG_CAP = 512L * 1024;

    public record CollectedLog(String text, boolean truncated, int fileCount) {}

    private final EngineRegistry engines;

    public EngineLogService(EngineRegistry engines) {
        this.engines = engines;
    }

    /**
     * @return empty text with fileCount 0 when the engine has no per-job log for this job
     */
    public CollectedLog collect(String clusterId, String engineJobId) {
        EngineClient engineClient = engines.clientFor(clusterId);
        List<EngineLogFile> files = engineClient.listLogs(engineJobId);
        if (files.isEmpty()) {
            return new CollectedLog("", false, 0);
        }
        long budget = Math.max(1, MAX_BYTES / files.size());
        if (!engineClient.archivesPerJobLogs()) {
            budget = Math.min(budget, SHARED_NODE_LOG_CAP);
        }
        StringBuilder text = new StringBuilder();
        boolean truncated = false;
        for (EngineLogFile file : files) {
            text.append("===== ").append(file.node()).append(" · ").append(file.name()).append(" =====\n");
            try {
                LogContent content = engineClient.readLog(file, budget);
                if (content.truncated()) {
                    truncated = true;
                    text.append("... (earlier lines truncated)\n");
                }
                text.append(content.text());
            } catch (ConsoleException e) {
                text.append("[unable to read this file: ").append(e.getMessage()).append("]\n");
            }
            if (text.length() > 0 && text.charAt(text.length() - 1) != '\n') {
                text.append('\n');
            }
        }
        return new CollectedLog(text.toString(), truncated, files.size());
    }
}
