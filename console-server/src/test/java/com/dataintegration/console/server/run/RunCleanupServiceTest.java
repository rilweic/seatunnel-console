package com.dataintegration.console.server.run;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.dataintegration.console.server.message.Messages;
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

import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.UncheckedIOException;
import java.util.List;

@ExtendWith(MockitoExtension.class)
class RunCleanupServiceTest {

    @Mock private JobInstanceMapper instanceMapper;
    @Mock private JobInstanceAttemptMapper attemptMapper;
    @Mock private JobInstanceEventMapper eventMapper;
    @Mock private JobMetricSampleMapper sampleMapper;
    @Mock private BackfillBatchMapper backfillMapper;
    @Mock private LogStorage logStorage;
    @Mock private Messages messages;
    @InjectMocks private RunCleanupService cleanupService;

    @BeforeEach
    void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, JobInstance.class);
        TableInfoHelper.initTableInfo(assistant, JobInstanceAttempt.class);
        TableInfoHelper.initTableInfo(assistant, JobInstanceEvent.class);
        TableInfoHelper.initTableInfo(assistant, JobMetricSample.class);
        TableInfoHelper.initTableInfo(assistant, BackfillBatch.class);
    }

    @Test
    void deletesAllRunDataBackfillsAndArchivedLogs() {
        mockHistory();

        cleanupService.deleteHistory("job-1");

        verify(sampleMapper).delete(any());
        verify(eventMapper).delete(any());
        verify(attemptMapper).delete(any());
        verify(instanceMapper).deleteByIds(List.of("instance-1"));
        verify(backfillMapper).delete(any());
        verify(logStorage).delete("instance-logs/2026-10-25/00000000-0000-0000-0000-000000000001.log");
    }

    @Test
    void deletesArchivedLogsOnlyAfterTheTransactionCommits() {
        mockHistory();
        TransactionSynchronizationManager.initSynchronization();
        try {
            cleanupService.deleteHistory("job-1");

            verify(logStorage, never()).delete(anyString());
            List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
            synchronizations.forEach(TransactionSynchronization::afterCommit);
            verify(logStorage).delete("instance-logs/2026-10-25/00000000-0000-0000-0000-000000000001.log");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void keepsArchivedLogsWhenTheTransactionRollsBack() {
        mockHistory();
        TransactionSynchronizationManager.initSynchronization();
        try {
            cleanupService.deleteHistory("job-1");

            verify(logStorage, never()).delete(anyString());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void logFailureDoesNotRollbackTheDatabaseCleanup() {
        mockHistory();
        doThrow(new UncheckedIOException(new java.io.IOException("disk busy"))).when(logStorage).delete(anyString());

        assertThatCode(() -> cleanupService.deleteHistory("job-1")).doesNotThrowAnyException();

        verify(instanceMapper).deleteByIds(List.of("instance-1"));
        verify(backfillMapper).delete(any());
    }

    private void mockHistory() {
        JobInstance instance = new JobInstance();
        instance.setId("instance-1");
        when(instanceMapper.selectList(any())).thenReturn(List.of(instance));
        JobInstanceAttempt attempt = new JobInstanceAttempt();
        attempt.setId("attempt-1");
        attempt.setLogStorageKey("instance-logs/2026-10-25/00000000-0000-0000-0000-000000000001.log");
        when(attemptMapper.selectList(any())).thenReturn(List.of(attempt));
    }
}
