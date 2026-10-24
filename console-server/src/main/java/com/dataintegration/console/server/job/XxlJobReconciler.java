package com.dataintegration.console.server.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataintegration.console.common.ConsoleException;
import com.dataintegration.console.common.ErrorCodes;
import com.dataintegration.console.server.job.ScheduleService.Reconciled;
import com.dataintegration.console.server.persistence.entity.JobDefinition;
import com.dataintegration.console.server.persistence.entity.JobSchedule;
import com.dataintegration.console.server.persistence.mapper.JobDefinitionMapper;
import com.dataintegration.console.server.persistence.mapper.JobScheduleMapper;
import com.dataintegration.console.server.scheduler.XxlJobAdminClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Periodically makes XXL-JOB match the console, the source of truth for schedules (docs/design.md
 * §5.2): re-pushes every schedule (which also retries failed syncs and recreates XXL-JOB jobs
 * deleted in the admin UI), forgets missing XXL-JOB jobs of unscheduled jobs, and removes the
 * XXL-JOB jobs of deleted console jobs that could not be removed at the time.
 */
@Component
public class XxlJobReconciler {

    private static final Logger log = LoggerFactory.getLogger(XxlJobReconciler.class);

    /**
     * @param counts jobs per outcome, plus the number of deleted jobs cleaned up
     * @param error why the round stopped early, e.g. xxl-job-admin unreachable; null when it completed
     */
    public record Round(LocalDateTime finishedAt, Map<Reconciled, Integer> counts, int removedOrphans, List<String> failures, String error) {}

    private final JobDefinitionMapper jobMapper;
    private final JobScheduleMapper scheduleMapper;
    private final ScheduleService scheduleService;
    private final ObjectProvider<XxlJobAdminClient> xxlJobAdminClient;
    private volatile Round lastRound;

    public XxlJobReconciler(
            JobDefinitionMapper jobMapper,
            JobScheduleMapper scheduleMapper,
            ScheduleService scheduleService,
            ObjectProvider<XxlJobAdminClient> xxlJobAdminClient) {
        this.jobMapper = jobMapper;
        this.scheduleMapper = scheduleMapper;
        this.scheduleService = scheduleService;
        this.xxlJobAdminClient = xxlJobAdminClient;
    }

    /** The last completed or aborted round, for the health page; null before the first one. */
    public Round lastRound() {
        return lastRound;
    }

    @Scheduled(initialDelayString = "${console.xxl-job.reconcile-initial-delay:2m}", fixedDelayString = "${console.xxl-job.reconcile-interval:10m}")
    public void reconcile() {
        XxlJobAdminClient client = xxlJobAdminClient.getIfAvailable();
        if (client != null) {
            lastRound = run(client);
        }
    }

    Round run(XxlJobAdminClient client) {
        Map<Reconciled, Integer> counts = new EnumMap<>(Reconciled.class);
        List<String> failures = new ArrayList<>();
        Set<String> scheduled = scheduleMapper.selectList(new LambdaQueryWrapper<JobSchedule>().select(JobSchedule::getJobDefinitionId))
                .stream()
                .map(JobSchedule::getJobDefinitionId)
                .collect(Collectors.toSet());
        List<JobDefinition> jobs = jobMapper.selectList(new LambdaQueryWrapper<JobDefinition>()
                .isNotNull(JobDefinition::getXxlJobId)
                .or()
                .in(!scheduled.isEmpty(), JobDefinition::getId, scheduled));
        int removed = 0;
        try {
            for (JobDefinition job : jobs) {
                Reconciled result = scheduleService.reconcile(job, client);
                counts.merge(result, 1, Integer::sum);
                if (result == Reconciled.FAILED) {
                    failures.add(job.getName());
                }
            }
            for (JobDefinition deleted : jobMapper.selectDeletedWithXxlJob()) {
                try {
                    client.removeJob(deleted.getXxlJobId());
                } catch (ConsoleException e) {
                    if (e.getCode() != ErrorCodes.SCHEDULER_JOB_MISSING) {
                        throw e;
                    }
                }
                jobMapper.clearXxlJobId(deleted.getId(), deleted.getXxlJobId());
                removed++;
            }
        } catch (ConsoleException e) {
            // Typically xxl-job-admin is down: stop here and try again next round.
            log.warn("XXL-JOB reconciliation stopped: {}", e.getMessage());
            return new Round(LocalDateTime.now(), counts, removed, failures, e.getMessage());
        }
        int changed = counts.getOrDefault(Reconciled.RECREATED, 0) + counts.getOrDefault(Reconciled.FORGOTTEN, 0) + removed;
        if (changed > 0 || !failures.isEmpty()) {
            log.info("XXL-JOB reconciliation: {} recreated, {} forgotten, {} orphans removed, failed: {}",
                    counts.getOrDefault(Reconciled.RECREATED, 0), counts.getOrDefault(Reconciled.FORGOTTEN, 0), removed, failures);
        }
        return new Round(LocalDateTime.now(), counts, removed, failures, null);
    }
}
