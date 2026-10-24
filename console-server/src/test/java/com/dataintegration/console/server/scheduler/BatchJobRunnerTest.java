package com.dataintegration.console.server.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dataintegration.console.common.ConsoleException;
import com.dataintegration.console.common.ErrorCodes;
import com.dataintegration.console.engine.ConnectorOption;
import com.dataintegration.console.engine.EngineClient;
import com.dataintegration.console.engine.EngineWorker;
import com.dataintegration.console.engine.MasterStatus;
import com.dataintegration.console.engine.EngineJobInfo;
import com.dataintegration.console.engine.EngineJobStatus;
import com.dataintegration.console.engine.EngineLogFile;
import com.dataintegration.console.engine.EngineType;
import com.dataintegration.console.engine.SubmitJobRequest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

class BatchJobRunnerTest {

    private final FakeEngine engine = new FakeEngine();
    private final RecordingListener listener = new RecordingListener();

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    @Test
    void pollsUntilTerminalStatus() {
        engine.respond(() -> info(EngineJobStatus.RUNNING, "5"));
        engine.respond(() -> info(EngineJobStatus.FINISHED, "10"));

        BatchJobRunner.Outcome outcome = runner((d, s) -> false).run(request(), listener, new CancelSignal());

        assertThat(outcome.succeeded()).isTrue();
        assertThat(outcome.engineJobId()).isEqualTo(42L);
        assertThat(outcome.metrics()).containsEntry("SinkWriteCount", "10");
        assertThat(listener.submitted).containsExactly(42L);
        assertThat(listener.progress).hasSize(2);
    }

    @Test
    void failedJobCarriesEngineError() {
        engine.respond(
                () ->
                        Optional.of(
                                new EngineJobInfo(
                                        42L, "j", EngineJobStatus.FAILED, null, Map.of(), "table not found")));

        BatchJobRunner.Outcome outcome = runner((d, s) -> false).run(request(), listener, new CancelSignal());

        assertThat(outcome.succeeded()).isFalse();
        assertThat(outcome.finalStatus()).isEqualTo(EngineJobStatus.FAILED);
        assertThat(outcome.errorMessage()).isEqualTo("table not found");
    }

    @Test
    void keepsWaitingWhileEngineIsTemporarilyUnavailable() {
        engine.respond(
                () -> {
                    throw new ConsoleException(ErrorCodes.ENGINE_UNAVAILABLE, "no Zeta master reachable");
                });
        engine.respond(() -> info(EngineJobStatus.FINISHED, "10"));

        BatchJobRunner.Outcome outcome = runner((d, s) -> false).run(request(), listener, new CancelSignal());

        assertThat(outcome.succeeded()).isTrue();
        assertThat(listener.warnings).anyMatch(w -> w.contains("no Zeta master reachable") && w.contains("will retry"));
    }

    @Test
    void jobUnknownToEngineEndsAsUnknowable() {
        engine.respond(Optional::empty);

        BatchJobRunner.Outcome outcome = runner((d, s) -> false).run(request(), listener, new CancelSignal());

        assertThat(outcome.finalStatus()).isEqualTo(EngineJobStatus.UNKNOWABLE);
        assertThat(outcome.succeeded()).isFalse();
    }

    @Test
    void interruptionStopsTheEngineJob() {
        engine.respond(() -> info(EngineJobStatus.RUNNING, "5"));
        engine.respond(() -> info(EngineJobStatus.CANCELED, "5"));
        BatchJobRunner.Outcome outcome =
                runner(
                                (d, s) -> {
                                    throw new InterruptedException();
                                })
                        .run(request(), listener, new CancelSignal());

        assertThat(outcome.finalStatus()).isEqualTo(EngineJobStatus.CANCELED);
        assertThat(engine.stopped).containsExactly(42L);
        assertThat(engine.interruptedDuringStop).isFalse();
        assertThat(outcome.errorMessage()).isEqualTo(BatchJobRunner.INTERRUPTED);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    @Test
    void cancelSignalStopsTheEngineJobWithItsReason() {
        engine.respond(() -> info(EngineJobStatus.RUNNING, "5"));
        engine.respond(() -> info(EngineJobStatus.RUNNING, "6"));
        engine.respond(() -> info(EngineJobStatus.RUNNING, "6"));
        engine.respond(() -> info(EngineJobStatus.CANCELED, "7"));
        CancelSignal signal = new CancelSignal();

        BatchJobRunner.Outcome outcome =
                runner(
                                (d, s) -> {
                                    if (!listener.progress.isEmpty()) {
                                        s.cancel("stopped by admin");
                                    }
                                    return s.isCancelled();
                                })
                        .run(request(), listener, signal);

        assertThat(outcome.finalStatus()).isEqualTo(EngineJobStatus.CANCELED);
        assertThat(outcome.errorMessage()).isEqualTo("stopped by admin");
        assertThat(outcome.metrics()).containsEntry("SinkWriteCount", "7");
        assertThat(engine.stopped).containsExactly(42L);
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    @Test
    void jobThatEndedBeforeTheStopKeepsItsRealOutcome() {
        engine.respond(() -> info(EngineJobStatus.RUNNING, "5"));
        engine.respond(() -> info(EngineJobStatus.FINISHED, "10"));

        BatchJobRunner.Outcome outcome =
                runner(
                                (d, s) -> {
                                    if (!listener.progress.isEmpty()) {
                                        s.cancel("stopped by admin");
                                    }
                                    return s.isCancelled();
                                })
                        .run(request(), listener, new CancelSignal());

        assertThat(outcome.succeeded()).isTrue();
        assertThat(outcome.metrics()).containsEntry("SinkWriteCount", "10");
        assertThat(engine.stopped).isEmpty();
    }

    @Test
    void submitsWithItsOwnJobIdOutsideZetasRange() {
        engine.respond(() -> info(EngineJobStatus.FINISHED, "1"));

        runner((d, s) -> false).run(request(), listener, new CancelSignal());

        assertThat(engine.submittedJobId).isNotNull().isGreaterThanOrEqualTo(1L << 62);
    }

    @Test
    void lostSubmitResponseWatchesTheJobTheEngineAlreadyHas() {
        engine.submitFailure = new ConsoleException(ErrorCodes.ENGINE_UNAVAILABLE, "request timed out");
        engine.respond(() -> info(EngineJobStatus.RUNNING, "0"));
        engine.respond(() -> info(EngineJobStatus.FINISHED, "3"));

        BatchJobRunner.Outcome outcome = runner((d, s) -> false).run(request(), listener, new CancelSignal());

        assertThat(outcome.succeeded()).isTrue();
        assertThat(outcome.engineJobId()).isEqualTo(engine.submittedJobId);
        assertThat(listener.warnings).anyMatch(w -> w.contains("submission response lost"));
    }

    @Test
    void rejectedSubmissionIsThrown() {
        engine.submitFailure = new ConsoleException(ErrorCodes.ENGINE_REQUEST_FAILED, "HTTP 500");

        assertThatThrownBy(() -> runner((d, s) -> false).run(request(), listener, new CancelSignal()))
                .isInstanceOf(ConsoleException.class)
                .hasMessageContaining("HTTP 500");
    }

    @Test
    void statusErrorsAfterSubmissionKeepWatchingInsteadOfFailingTheSubmission() {
        engine.respond(() -> {
            throw new ConsoleException(ErrorCodes.ENGINE_REQUEST_FAILED, "job-info returned HTTP 500");
        });
        engine.respond(() -> info(EngineJobStatus.FINISHED, "10"));

        BatchJobRunner.Outcome outcome = runner((d, s) -> false).run(request(), listener, new CancelSignal());

        assertThat(outcome.succeeded()).isTrue();
        assertThat(listener.warnings).anyMatch(w -> w.contains("HTTP 500"));
    }

    @Test
    void failedStopIsNotReportedAsCanceled() {
        engine.respond(() -> info(EngineJobStatus.RUNNING, "5"));
        engine.stopFailure = new ConsoleException(ErrorCodes.ENGINE_UNAVAILABLE, "no Zeta master reachable");

        BatchJobRunner.Outcome outcome = runner((d, s) -> {
                    s.cancel("stopped by admin");
                    return true;
                })
                .run(request(), listener, new CancelSignal());

        assertThat(outcome.finalStatus()).isEqualTo(EngineJobStatus.UNKNOWABLE);
        assertThat(outcome.errorMessage()).contains("stopped by admin").contains("may still be running");
    }

    @Test
    void stopThatDoesNotTakeEffectIsNotReportedAsCanceled() {
        for (int i = 0; i <= BatchJobRunner.STOP_CHECKS; i++) {
            engine.respond(() -> info(EngineJobStatus.RUNNING, "5"));
        }

        BatchJobRunner.Outcome outcome = runner((d, s) -> {
                    s.cancel("stopped by admin");
                    return true;
                })
                .run(request(), listener, new CancelSignal());

        assertThat(outcome.finalStatus()).isEqualTo(EngineJobStatus.UNKNOWABLE);
        assertThat(engine.stopped).hasSize(1);
    }

    private BatchJobRunner runner(BatchJobRunner.Sleeper sleeper) {
        return new BatchJobRunner(engine, Duration.ofSeconds(10), sleeper, d -> {});
    }

    private static SubmitJobRequest request() {
        return SubmitJobRequest.fresh("j", "cfg");
    }

    private static Optional<EngineJobInfo> info(EngineJobStatus status, String written) {
        return Optional.of(
                new EngineJobInfo(42L, "j", status, null, Map.of("SinkWriteCount", written), null));
    }

    private static final class FakeEngine implements EngineClient {
        private final Deque<Supplier<Optional<EngineJobInfo>>> responses = new ArrayDeque<>();
        private final List<Long> stopped = new ArrayList<>();
        private boolean interruptedDuringStop;
        private Long submittedJobId;
        private ConsoleException submitFailure;
        private ConsoleException stopFailure;

        void respond(Supplier<Optional<EngineJobInfo>> response) {
            responses.add(response);
        }

        @Override
        public EngineType type() {
            return EngineType.ZETA;
        }

        @Override
        public Map<String, String> overview() {
            return Map.of();
        }

        @Override
        public List<ConnectorOption> connectorOptions(String pluginType, String plugin) {
            return List.of();
        }

        @Override
        public List<MasterStatus> probeMasters() {
            return List.of();
        }

        @Override
        public List<EngineWorker> workers() {
            return List.of();
        }

        @Override
        public boolean hasConnector(String pluginType, String plugin) {
            return true;
        }

        @Override
        public long submit(SubmitJobRequest request) {
            submittedJobId = request.jobId();
            if (submitFailure != null) {
                throw submitFailure;
            }
            return 42L;
        }

        @Override
        public void stop(long jobId, boolean withSavepoint, boolean force) {
            if (stopFailure != null) {
                throw stopFailure;
            }
            stopped.add(jobId);
            interruptedDuringStop = Thread.currentThread().isInterrupted();
        }

        @Override
        public Optional<EngineJobInfo> jobInfo(long jobId) {
            return responses.removeFirst().get();
        }

        @Override
        public List<EngineLogFile> listLogs(long jobId) {
            return List.of();
        }

        @Override
        public LogContent readLog(EngineLogFile file, long maxBytes) {
            return new LogContent("", false);
        }
    }

    private static final class RecordingListener implements BatchJobRunner.Listener {
        private final List<Long> submitted = new ArrayList<>();
        private final List<EngineJobInfo> progress = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();

        @Override
        public void onSubmitted(long engineJobId) {
            submitted.add(engineJobId);
        }

        @Override
        public void onProgress(EngineJobInfo info) {
            progress.add(info);
        }

        @Override
        public void onWarning(String message) {
            warnings.add(message);
        }
    }
}
