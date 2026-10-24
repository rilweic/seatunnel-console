package com.dataintegration.console.server.run;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataintegration.console.server.persistence.entity.BackfillBatch;
import com.dataintegration.console.server.persistence.entity.JobInstance;
import com.dataintegration.console.server.persistence.entity.JobInstanceAttempt;
import com.dataintegration.console.server.persistence.entity.JobInstanceEvent;
import com.dataintegration.console.server.persistence.entity.JobMetricSample;
import com.dataintegration.console.server.persistence.mapper.BackfillBatchMapper;
import com.dataintegration.console.server.persistence.mapper.JobInstanceAttemptMapper;
import com.dataintegration.console.server.persistence.mapper.JobInstanceEventMapper;
import com.dataintegration.console.server.persistence.mapper.JobInstanceMapper;
import com.dataintegration.console.server.persistence.mapper.JobMetricSampleMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

@Service
public class RunCleanupService {

    private static final Logger log = LoggerFactory.getLogger(RunCleanupService.class);

    private final JobInstanceMapper instanceMapper;
    private final JobInstanceAttemptMapper attemptMapper;
    private final JobInstanceEventMapper eventMapper;
    private final JobMetricSampleMapper sampleMapper;
    private final BackfillBatchMapper backfillMapper;
    private final LogStorage logStorage;

    public RunCleanupService(
            JobInstanceMapper instanceMapper,
            JobInstanceAttemptMapper attemptMapper,
            JobInstanceEventMapper eventMapper,
            JobMetricSampleMapper sampleMapper,
            BackfillBatchMapper backfillMapper,
            LogStorage logStorage) {
        this.instanceMapper = instanceMapper;
        this.attemptMapper = attemptMapper;
        this.eventMapper = eventMapper;
        this.sampleMapper = sampleMapper;
        this.backfillMapper = backfillMapper;
        this.logStorage = logStorage;
    }

    /** The caller locks the job and refuses deletion while any run or backfill is active. */
    public void deleteHistory(String jobId) {
        List<String> instanceIds = instanceMapper.selectList(new LambdaQueryWrapper<JobInstance>()
                        .select(JobInstance::getId)
                        .eq(JobInstance::getJobDefinitionId, jobId))
                .stream()
                .map(JobInstance::getId)
                .toList();
        List<String> logKeys = List.of();
        if (!instanceIds.isEmpty()) {
            logKeys = attemptMapper.selectList(new LambdaQueryWrapper<JobInstanceAttempt>()
                            .select(JobInstanceAttempt::getId, JobInstanceAttempt::getLogStorageKey)
                            .in(JobInstanceAttempt::getInstanceId, instanceIds))
                    .stream()
                    .map(JobInstanceAttempt::getLogStorageKey)
                    .filter(key -> key != null && !key.isBlank())
                    .distinct()
                    .toList();
            sampleMapper.delete(new LambdaQueryWrapper<JobMetricSample>().in(JobMetricSample::getInstanceId, instanceIds));
            eventMapper.delete(new LambdaQueryWrapper<JobInstanceEvent>().in(JobInstanceEvent::getInstanceId, instanceIds));
            attemptMapper.delete(new LambdaQueryWrapper<JobInstanceAttempt>().in(JobInstanceAttempt::getInstanceId, instanceIds));
            instanceMapper.deleteByIds(instanceIds);
        }
        backfillMapper.delete(new LambdaQueryWrapper<BackfillBatch>().eq(BackfillBatch::getJobDefinitionId, jobId));
        deleteLogsAfterCommit(jobId, logKeys);
    }

    private void deleteLogsAfterCommit(String jobId, List<String> keys) {
        if (keys.isEmpty()) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    keys.forEach(key -> deleteLog(jobId, key));
                }
            });
        } else {
            keys.forEach(key -> deleteLog(jobId, key));
        }
    }

    private void deleteLog(String jobId, String key) {
        try {
            logStorage.delete(key);
        } catch (RuntimeException e) {
            log.warn("Could not delete archived log {} for deleted job {}: {}", key, jobId, e.getMessage());
        }
    }
}
