package com.dataintegration.console.server.retention;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.dataintegration.console.common.ConsoleException;
import com.dataintegration.console.common.ErrorCodes;
import com.dataintegration.console.server.message.Messages;
import com.dataintegration.console.server.persistence.entity.JobInstance;
import com.dataintegration.console.server.persistence.entity.JobInstanceAttempt;
import com.dataintegration.console.server.persistence.entity.JobInstanceEvent;
import com.dataintegration.console.server.persistence.entity.JobMetricSample;
import com.dataintegration.console.server.persistence.entity.RetentionCleanupLog;
import com.dataintegration.console.server.persistence.entity.RetentionPolicy;
import com.dataintegration.console.server.persistence.mapper.BackfillBatchMapper;
import com.dataintegration.console.server.persistence.mapper.JobInstanceAttemptMapper;
import com.dataintegration.console.server.persistence.mapper.JobInstanceEventMapper;
import com.dataintegration.console.server.persistence.mapper.JobInstanceMapper;
import com.dataintegration.console.server.persistence.mapper.JobMetricSampleMapper;
import com.dataintegration.console.server.persistence.mapper.MessageMapper;
import com.dataintegration.console.server.persistence.mapper.RetentionCleanupLogMapper;
import com.dataintegration.console.server.persistence.mapper.RetentionPolicyMapper;
import com.dataintegration.console.server.persistence.mapper.SysUserMapper;
import com.dataintegration.console.server.retention.RetentionService.CleanupLogView;
import com.dataintegration.console.server.retention.RetentionService.PolicyView;
import com.dataintegration.console.server.retention.RetentionService.UpdatePolicyRequest;
import com.dataintegration.console.server.run.LocalLogStorage;
import com.dataintegration.console.server.run.LogStorage;
import com.dataintegration.console.server.run.LogStorage.PurgeStats;
import com.dataintegration.console.server.run.LogStorage.StorageStats;

import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RetentionServiceTest {

    private RetentionPolicyMapper policyMapper;
    private RetentionCleanupLogMapper cleanupLogMapper;
    private JobMetricSampleMapper metricSampleMapper;
    private JobInstanceMapper instanceMapper;
    private JobInstanceAttemptMapper attemptMapper;
    private JobInstanceEventMapper eventMapper;
    private BackfillBatchMapper backfillMapper;
    private MessageMapper messageMapper;
    private SysUserMapper userMapper;
    private LogStorage logStorage;
    private Messages messages;

    private RetentionService service;
    private RetentionPolicy defaultPolicy;

    @BeforeAll
    static void initMybatisMeta() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, JobInstanceAttempt.class);
        TableInfoHelper.initTableInfo(assistant, JobInstance.class);
        TableInfoHelper.initTableInfo(assistant, JobInstanceEvent.class);
        TableInfoHelper.initTableInfo(assistant, JobMetricSample.class);
        TableInfoHelper.initTableInfo(assistant, RetentionCleanupLog.class);
    }

    @BeforeEach
    void setUp() {
        policyMapper = mock(RetentionPolicyMapper.class);
        cleanupLogMapper = mock(RetentionCleanupLogMapper.class);
        metricSampleMapper = mock(JobMetricSampleMapper.class);
        instanceMapper = mock(JobInstanceMapper.class);
        attemptMapper = mock(JobInstanceAttemptMapper.class);
        eventMapper = mock(JobInstanceEventMapper.class);
        backfillMapper = mock(BackfillBatchMapper.class);
        messageMapper = mock(MessageMapper.class);
        userMapper = mock(SysUserMapper.class);
        logStorage = mock(LogStorage.class);
        messages = mock(Messages.class);

        defaultPolicy = new RetentionPolicy();
        defaultPolicy.setId(RetentionPolicy.SINGLETON_ID);
        defaultPolicy.setEnabled(true);
        defaultPolicy.setRunHour(3);
        defaultPolicy.setMetricRetentionDays(14);
        defaultPolicy.setInstanceRetentionDays(90);
        defaultPolicy.setLogRetentionDays(30);
        defaultPolicy.setMessageRetentionDays(90);
        defaultPolicy.setUpdatedAt(LocalDateTime.now());

        when(policyMapper.selectById(RetentionPolicy.SINGLETON_ID)).thenReturn(defaultPolicy);

        service = new RetentionService(
                policyMapper, cleanupLogMapper, metricSampleMapper, instanceMapper,
                attemptMapper, eventMapper, backfillMapper, messageMapper,
                userMapper, logStorage, messages);
    }

    @Test
    void updatePolicyValidatesRanges() {
        ConsoleException badHour = assertThrows(ConsoleException.class, () ->
                service.updatePolicy(new UpdatePolicyRequest(true, 25, 14, 90, 30, 90), "u1"));
        assertEquals(ErrorCodes.INVALID_ARGUMENT, badHour.getCode());

        ConsoleException badDays = assertThrows(ConsoleException.class, () ->
                service.updatePolicy(new UpdatePolicyRequest(true, 3, -1, 90, 30, 90), "u1"));
        assertEquals(ErrorCodes.INVALID_ARGUMENT, badDays.getCode());

        ConsoleException tooManyDays = assertThrows(ConsoleException.class, () ->
                service.updatePolicy(new UpdatePolicyRequest(true, 3, 14, 9999, 30, 90), "u1"));
        assertEquals(ErrorCodes.INVALID_ARGUMENT, tooManyDays.getCode());

        PolicyView updated = service.updatePolicy(new UpdatePolicyRequest(false, 4, 0, 60, 15, 30), "u1");
        assertEquals(false, updated.enabled());
        assertEquals(4, updated.runHour());
        assertEquals(0, updated.metricRetentionDays());
        assertEquals(60, updated.instanceRetentionDays());
        verify(policyMapper).updateById(any(RetentionPolicy.class));
    }

    @Test
    void runNowCleansAllCategoriesAndRecordsAuditLog() {
        when(cleanupLogMapper.deleteMetricSamplesBatch(any(), anyInt())).thenReturn(500);
        when(cleanupLogMapper.findExpiredInstanceIds(any(), anyInt()))
                .thenReturn(List.of("inst-1", "inst-2"))
                .thenReturn(List.of());
        JobInstanceAttempt att = new JobInstanceAttempt();
        att.setId("att-1");
        att.setLogStorageKey("instance-logs/2026-01-01/00000000-0000-0000-0000-000000000001.log");
        when(attemptMapper.selectList(any())).thenReturn(List.of(att));
        when(metricSampleMapper.delete(any())).thenReturn(10);
        when(eventMapper.delete(any())).thenReturn(6);
        when(attemptMapper.delete(any())).thenReturn(2);
        when(instanceMapper.delete(any())).thenReturn(2);
        when(cleanupLogMapper.deleteExpiredBackfillsBatch(any(), anyInt())).thenReturn(1);
        when(logStorage.purgeOlderThan(any())).thenReturn(new PurgeStats(3, 4096));
        when(messages.purge(Duration.ofDays(90))).thenReturn(12);

        CleanupLogView result = service.runNow("admin-1");

        assertEquals("MANUAL", result.triggerType());
        assertEquals("SUCCESS", result.status());
        assertEquals(510, result.deletedMetricSamples());
        assertEquals(2, result.deletedInstances());
        assertEquals(2, result.deletedAttempts());
        assertEquals(6, result.deletedEvents());
        assertEquals(1, result.deletedBackfills());
        assertEquals(3, result.deletedLogFiles());
        assertEquals(4096, result.deletedLogBytes());
        assertEquals(12, result.deletedMessages());

        verify(logStorage).delete(att.getLogStorageKey());
        ArgumentCaptor<RetentionCleanupLog> captor = ArgumentCaptor.forClass(RetentionCleanupLog.class);
        verify(cleanupLogMapper).insert(captor.capture());
        assertEquals("SUCCESS", captor.getValue().getStatus());
        verify(cleanupLogMapper).pruneOldLogs(100);
    }

    @Test
    void runNowSkipsCategoriesConfiguredAsKeepForever() {
        defaultPolicy.setMetricRetentionDays(0);
        defaultPolicy.setInstanceRetentionDays(0);
        defaultPolicy.setLogRetentionDays(0);
        defaultPolicy.setMessageRetentionDays(0);

        CleanupLogView result = service.runNow("admin-1");

        assertEquals("SUCCESS", result.status());
        assertEquals(0, result.deletedMetricSamples());
        assertEquals(0, result.deletedInstances());
        verify(cleanupLogMapper, never()).deleteMetricSamplesBatch(any(), anyInt());
        verify(cleanupLogMapper, never()).findExpiredInstanceIds(any(), anyInt());
        verify(logStorage, never()).purgeOlderThan(any());
        verify(messages, never()).purge(any());
    }

    @Test
    void scheduledCheckSkipsWhenDisabledOrAlreadyRanToday() {
        defaultPolicy.setEnabled(false);
        service.scheduledCheck();
        verify(cleanupLogMapper, never()).insert(any(RetentionCleanupLog.class));

        defaultPolicy.setEnabled(true);
        defaultPolicy.setRunHour(LocalDateTime.now().getHour());
        when(cleanupLogMapper.selectCount(any())).thenReturn(1L);
        service.scheduledCheck();
        verify(cleanupLogMapper, never()).insert(any(RetentionCleanupLog.class));
    }

    @Test
    void localLogStorageStatsAndPurgeWork(@TempDir Path tempDir) {
        LocalLogStorage storage = new LocalLogStorage(tempDir.toString(), 30);
        String oldId = "00000000-0000-0000-0000-000000000001";
        String newId = "00000000-0000-0000-0000-000000000002";
        storage.write(oldId, LocalDate.now().minusDays(40), "old-log-content");
        storage.write(newId, LocalDate.now().minusDays(5), "fresh-log");

        StorageStats before = storage.stats();
        assertEquals(2, before.fileCount());
        assertTrue(before.totalBytes() > 0);

        PurgeStats purged = storage.purgeOlderThan(LocalDate.now().minusDays(30));
        assertEquals(1, purged.deletedFiles());
        assertEquals("old-log-content".length(), purged.deletedBytes());

        StorageStats after = storage.stats();
        assertEquals(1, after.fileCount());
        assertEquals("fresh-log".length(), after.totalBytes());
    }
}
