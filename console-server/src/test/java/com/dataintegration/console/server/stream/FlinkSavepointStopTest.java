package com.dataintegration.console.server.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.dataintegration.console.engine.EngineClient;
import com.dataintegration.console.engine.EngineClient.StopResult;
import com.dataintegration.console.engine.EngineJobInfo;
import com.dataintegration.console.engine.EngineJobStatus;
import com.dataintegration.console.engine.EngineType;
import com.dataintegration.console.server.cluster.EngineRegistry;
import com.dataintegration.console.server.datasource.DatasourceService;
import com.dataintegration.console.server.job.JobService;
import com.dataintegration.console.server.persistence.entity.JobDefinition;
import com.dataintegration.console.server.persistence.entity.JobInstance;
import com.dataintegration.console.server.persistence.entity.JobInstanceAttempt;
import com.dataintegration.console.server.persistence.entity.JobStream;
import com.dataintegration.console.server.persistence.mapper.JobStreamMapper;
import com.dataintegration.console.server.run.AttemptLogArchiver;
import com.dataintegration.console.server.run.RunRecorder;
import com.dataintegration.console.server.run.RunStatus;

import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

class FlinkSavepointStopTest {

    private static final String JOB_ID = "job-1";
    private static final String INSTANCE_ID = "inst-1";
    private static final String CLUSTER_ID = "cluster-flink";
    private static final String ENGINE_JOB_ID = "11112222333344445555666677778888";
    private static final String OLD_CHECKPOINT = "file:/opt/flink-state/checkpoints/chk-5";
    private static final String NEW_SAVEPOINT = "file:/opt/flink-state/savepoints/sp-9";

    private JobStreamMapper streamMapper;
    private StreamRuns runs;
    private RunRecorder recorder;
    private StreamSubmitter submitter;
    private JobService jobService;
    private EngineRegistry engines;
    private EngineClient flinkEngine;
    private AttemptLogArchiver logArchiver;
    private StreamMonitor monitor;

    private JobDefinition job;
    private JobInstance instance;
    private JobInstanceAttempt attempt;
    private JobStream stream;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), JobStream.class);
        streamMapper = mock(JobStreamMapper.class);
        runs = mock(StreamRuns.class);
        recorder = mock(RunRecorder.class);
        submitter = mock(StreamSubmitter.class);
        jobService = mock(JobService.class);
        engines = mock(EngineRegistry.class);
        flinkEngine = mock(EngineClient.class);
        logArchiver = mock(AttemptLogArchiver.class);

        when(runs.recorder()).thenReturn(recorder);
        when(engines.clientFor(CLUSTER_ID)).thenReturn(flinkEngine);
        when(flinkEngine.type()).thenReturn(EngineType.FLINK);
        when(flinkEngine.restoresByExternalPath()).thenReturn(true);

        job = new JobDefinition();
        job.setId(JOB_ID);
        job.setClusterId(CLUSTER_ID);
        job.setJobType(JobService.TYPE_STREAMING);
        when(jobService.require(JOB_ID)).thenReturn(job);
        when(jobService.requireEditable(JOB_ID)).thenReturn(job);

        instance = new JobInstance();
        instance.setId(INSTANCE_ID);
        instance.setJobDefinitionId(JOB_ID);
        instance.setJobVersionId("ver-1");
        instance.setStatus(RunStatus.RUNNING.name());
        when(recorder.requireInstance(INSTANCE_ID)).thenReturn(instance);

        attempt = new JobInstanceAttempt();
        attempt.setId("att-1");
        attempt.setInstanceId(INSTANCE_ID);
        attempt.setAttemptNo(1);
        attempt.setEngineJobId(ENGINE_JOB_ID);
        attempt.setSubmittedAt(LocalDateTime.now().minusMinutes(5));
        when(runs.latestAttempt(INSTANCE_ID)).thenReturn(attempt);

        stream = new JobStream();
        stream.setJobDefinitionId(JOB_ID);
        stream.setCurrentInstanceId(INSTANCE_ID);
        stream.setRestoreKind(RestorePlan.Kind.CHECKPOINT.name());
        stream.setRestoreEngineJobId(ENGINE_JOB_ID);
        stream.setRestorePath(OLD_CHECKPOINT);
        stream.setStopRequested("SAVEPOINT");
        stream.setStopRequestedAt(LocalDateTime.now().minusSeconds(2));
        when(streamMapper.selectById(JOB_ID)).thenReturn(stream);

        monitor = new StreamMonitor(
                streamMapper, runs, submitter, jobService, engines, logArchiver, new ObjectMapper(), Duration.ofSeconds(60));
    }

    @Test
    void monitorWaitsDuringFlinkSavepointStopEvenWhenEarlierCheckpointExists() {
        when(flinkEngine.jobInfo(ENGINE_JOB_ID)).thenReturn(Optional.of(
                new EngineJobInfo(ENGINE_JOB_ID, "demo", EngineJobStatus.FINISHED, null, Map.of(), null)));

        monitor.tick(stream);

        verify(runs, never()).finish(any(), any(), any(), any());
    }

    @Test
    void monitorAfterGraceExpiryPreservesEarlierCheckpointInsteadOfFabricatingSavepoint() {
        stream.setStopRequestedAt(LocalDateTime.now().minusMinutes(5));
        when(flinkEngine.jobInfo(ENGINE_JOB_ID)).thenReturn(Optional.of(
                new EngineJobInfo(ENGINE_JOB_ID, "demo", EngineJobStatus.FINISHED, null, Map.of(), null)));

        monitor.tick(stream);

        verify(runs).finish(eq(instance), eq(RunStatus.CANCELED), any(), eq(null));
    }

    @Test
    void concurrentMonitorPollAndStopRecordsRealSavepointPath() throws Exception {
        CountDownLatch stopEntered = new CountDownLatch(1);
        CountDownLatch monitorPolled = new CountDownLatch(1);

        when(flinkEngine.jobInfo(ENGINE_JOB_ID)).thenReturn(Optional.of(
                new EngineJobInfo(ENGINE_JOB_ID, "demo", EngineJobStatus.FINISHED, null, Map.of(), null)));
        when(flinkEngine.stop(ENGINE_JOB_ID, true, false)).thenAnswer(inv -> {
            stopEntered.countDown();
            monitorPolled.await(5, TimeUnit.SECONDS);
            return new StopResult(NEW_SAVEPOINT);
        });

        StreamIdentityService identity = mock(StreamIdentityService.class);
        when(identity.require(JOB_ID)).thenReturn(stream);
        StreamService service = new StreamService(
                jobService,
                identity,
                runs,
                submitter,
                mock(CdcReadiness.class),
                engines,
                mock(DatasourceService.class),
                new ObjectMapper(),
                mock(SchemaDriftWatcher.class));

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> stopCall = pool.submit(() -> {
                try {
                    service.stop(JOB_ID, false);
                } catch (RuntimeException e) {
                    // Only ignore exceptions from view() after stop() has already finished
                    if (stopEntered.getCount() > 0) {
                        throw e;
                    }
                }
            });
            assertThat(stopEntered.await(5, TimeUnit.SECONDS)).isTrue();
            // Monitor observes FINISHED while stop() is still polling Flink for the savepoint path
            monitor.tick(stream);
            monitorPolled.countDown();
            stopCall.get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        ArgumentCaptor<StreamRuns.RestorePoint> captor = ArgumentCaptor.forClass(StreamRuns.RestorePoint.class);
        verify(runs).finish(eq(instance), eq(RunStatus.CANCELED), any(), captor.capture());
        StreamRuns.RestorePoint saved = captor.getValue();
        assertThat(saved.kind()).isEqualTo(RestorePlan.Kind.SAVEPOINT);
        assertThat(saved.restorePath()).isEqualTo(NEW_SAVEPOINT);
    }

    @Test
    void monitorWithStaleSnapshotRereadsStopRequestedBeforeEnding() {
        // Monitor loaded stream BEFORE stop() set stopRequested = SAVEPOINT
        JobStream staleSnapshot = new JobStream();
        staleSnapshot.setJobDefinitionId(JOB_ID);
        staleSnapshot.setCurrentInstanceId(INSTANCE_ID);
        staleSnapshot.setRestoreKind(RestorePlan.Kind.CHECKPOINT.name());
        staleSnapshot.setRestoreEngineJobId(ENGINE_JOB_ID);
        staleSnapshot.setRestorePath(OLD_CHECKPOINT);
        staleSnapshot.setStopRequested(null);

        // Meanwhile DB already has stopRequested = SAVEPOINT (stubbed in streamMapper.selectById)
        when(flinkEngine.jobInfo(ENGINE_JOB_ID)).thenReturn(Optional.of(
                new EngineJobInfo(ENGINE_JOB_ID, "demo", EngineJobStatus.FINISHED, null, Map.of(), null)));

        monitor.tick(staleSnapshot);

        verify(runs, never()).finish(any(), any(), any(), any());
    }

    @Test
    void monitorDoesNotTimeoutAttemptStillSubmittingInThisProcess() {
        attempt.setSubmittedAt(null);
        attempt.setCreatedAt(LocalDateTime.now().minusMinutes(10));
        when(submitter.isSubmitting("att-1")).thenReturn(true);

        monitor.tick(stream);

        verify(runs, never()).finish(any(), any(), any(), any());
        verify(flinkEngine, never()).jobInfo(any());
    }
}
