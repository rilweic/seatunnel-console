package com.dataintegration.console.server.scheduler;

import com.dataintegration.console.common.ConsoleException;
import com.dataintegration.console.common.ErrorCodes;
import com.dataintegration.console.engine.EngineClient;
import com.dataintegration.console.engine.EngineJobInfo;
import com.dataintegration.console.engine.EngineJobStatus;
import com.dataintegration.console.engine.SubmitJobRequest;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Submits a batch job and blocks until it reaches a terminal state (docs/design.md §5.2 steps 3–4).
 * Blocking keeps XXL-JOB's timeout, retry and block strategies meaningful.
 *
 * <p>When the run is cancelled (a user stop) or the calling thread is interrupted (XXL-JOB timeout
 * or kill) the engine job is stopped as well, so it does not keep running unseen on the cluster.
 */
public class BatchJobRunner {

    /** Receives progress; the XXL-JOB handler forwards it to the scheduler log. */
    public interface Listener {
        void onSubmitted(long engineJobId);

        void onProgress(EngineJobInfo info);

        void onWarning(String message);
    }

    /**
     * @param finalStatus terminal engine status; CANCELED when interrupted
     * @param metrics last metrics seen, empty when the engine lost the job
     */
    public record Outcome(
            long engineJobId, EngineJobStatus finalStatus, Map<String, String> metrics, String errorMessage) {

        public boolean succeeded() {
            return finalStatus == EngineJobStatus.FINISHED;
        }
    }

    static final String INTERRUPTED = "interrupted by XXL-JOB (timeout or kill)";

    /** Waits between polls; returns true when the run was cancelled meanwhile. */
    @FunctionalInterface
    interface Sleeper {
        boolean sleep(Duration duration, CancelSignal signal) throws InterruptedException;
    }

    private final EngineClient engineClient;
    private final Duration pollInterval;
    private final Sleeper sleeper;

    public BatchJobRunner(EngineClient engineClient, Duration pollInterval) {
        this(engineClient, pollInterval, (d, signal) -> signal.await(d));
    }

    BatchJobRunner(EngineClient engineClient, Duration pollInterval, Sleeper sleeper) {
        this.engineClient = engineClient;
        this.pollInterval = pollInterval;
        this.sleeper = sleeper;
    }

    /** On interrupt the thread's interrupt flag is restored before returning. */
    public Outcome run(SubmitJobRequest request, Listener listener, CancelSignal signal) {
        long jobId = engineClient.submit(request);
        listener.onSubmitted(jobId);
        Map<String, String> lastMetrics = Map.of();
        while (true) {
            try {
                if (sleeper.sleep(pollInterval, signal)) {
                    return cancel(jobId, lastMetrics, listener, signal.reason());
                }
            } catch (InterruptedException e) {
                // The flag is restored only afterwards: with it set, the HTTP calls that stop the
                // engine job would fail at once and leave the job running on the cluster.
                Outcome outcome = cancel(jobId, lastMetrics, listener, INTERRUPTED);
                Thread.currentThread().interrupt();
                return outcome;
            }
            Optional<EngineJobInfo> info;
            try {
                info = engineClient.jobInfo(jobId);
            } catch (ConsoleException e) {
                if (e.getCode() != ErrorCodes.ENGINE_UNAVAILABLE) {
                    throw e;
                }
                // Keep waiting through a cluster blip; the XXL-JOB timeout bounds the total wait.
                listener.onWarning("engine unavailable, will retry: " + e.getMessage());
                continue;
            }
            if (info.isEmpty()) {
                return new Outcome(
                        jobId, EngineJobStatus.UNKNOWABLE, lastMetrics, "engine no longer knows job " + jobId);
            }
            EngineJobInfo current = info.get();
            lastMetrics = current.metrics();
            listener.onProgress(current);
            if (current.status().isTerminal()) {
                return new Outcome(jobId, current.status(), current.metrics(), current.errorMessage());
            }
        }
    }

    /**
     * Stops the engine job, unless it already ended between two polls: then its real outcome is
     * returned, so a job that finished just before a stop request is not reported as canceled.
     */
    private Outcome cancel(long jobId, Map<String, String> lastMetrics, Listener listener, String reason) {
        try {
            Optional<EngineJobInfo> info = engineClient.jobInfo(jobId);
            if (info.isPresent() && info.get().status().isTerminal()) {
                EngineJobInfo current = info.get();
                listener.onProgress(current);
                listener.onWarning(reason + ", but engine job " + jobId + " had already ended as " + current.status());
                return new Outcome(jobId, current.status(), current.metrics(), current.errorMessage());
            }
        } catch (ConsoleException e) {
            // Unknown state; stop it anyway.
        }
        try {
            engineClient.stop(jobId, false, false);
            listener.onWarning(reason + ", stop requested for engine job " + jobId);
        } catch (ConsoleException e) {
            listener.onWarning(reason + ", failed to stop engine job " + jobId + ": " + e.getMessage());
        }
        return new Outcome(jobId, EngineJobStatus.CANCELED, lastMetrics, reason);
    }
}
