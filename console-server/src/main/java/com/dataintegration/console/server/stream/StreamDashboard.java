package com.dataintegration.console.server.stream;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataintegration.console.server.persistence.entity.JobInstance;
import com.dataintegration.console.server.persistence.entity.JobStream;
import com.dataintegration.console.server.persistence.mapper.JobStreamMapper;
import com.dataintegration.console.server.run.RunStatus;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Real-time jobs on the overview page (docs/design.md §12 P2, 导航): how many run, how many need
 * attention, and the running job that has gone longest without reading data. There is no engine
 * delay metric (§12 P2, 实测结论), so time since the last read stands in for it.
 */
@Component
public class StreamDashboard {

    /**
     * @param abnormal running but recovering, waiting to restart or with failing checkpoints, or
     *     stopped after a failure
     * @param quietestReadAt the oldest "last read" among running jobs that have read before; null when none
     */
    public record Summary(long jobs, long running, long abnormal, LocalDateTime quietestReadAt, String quietestJobId) {}

    private final JobStreamMapper streams;
    private final StreamRuns runs;

    public StreamDashboard(JobStreamMapper streams, StreamRuns runs) {
        this.streams = streams;
        this.runs = runs;
    }

    /** @param jobIds the real-time jobs in scope (all, or the user's) */
    public Summary summary(Collection<String> jobIds) {
        if (jobIds.isEmpty()) {
            return new Summary(0, 0, 0, null, null);
        }
        List<JobStream> rows = streams.selectList(new LambdaQueryWrapper<JobStream>().in(JobStream::getJobDefinitionId, jobIds));
        long running = 0;
        long abnormal = 0;
        JobStream quietest = null;
        for (JobStream s : rows) {
            if (s.getCurrentInstanceId() != null) {
                running++;
                boolean troubled = s.getRestartAt() != null || s.getCheckpointProblem() != null
                        || (s.getPipelineRestores() != null && s.getPipelineRestores() > 0);
                abnormal += troubled ? 1 : 0;
                if (s.getLastReadAt() != null && (quietest == null || s.getLastReadAt().isBefore(quietest.getLastReadAt()))) {
                    quietest = s;
                }
            } else {
                JobInstance last = runs.lastRun(s.getJobDefinitionId());
                abnormal += last != null && Objects.equals(last.getStatus(), RunStatus.FAILED.name()) ? 1 : 0;
            }
        }
        return new Summary(rows.size(), running, abnormal, quietest == null ? null : quietest.getLastReadAt(),
                quietest == null ? null : quietest.getJobDefinitionId());
    }
}
