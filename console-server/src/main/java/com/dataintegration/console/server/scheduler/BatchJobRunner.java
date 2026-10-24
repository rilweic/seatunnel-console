package com.dataintegration.console.server.scheduler;

import com.dataintegration.console.common.ConsoleException;
import com.dataintegration.console.common.ErrorCodes;
import com.dataintegration.console.engine.EngineClient;
import com.dataintegration.console.engine.EngineJobInfo;
import com.dataintegration.console.engine.EngineJobStatus;
import com.dataintegration.console.engine.SubmitJobRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Submits a batch job and blocks until it reaches a terminal state (docs/design.md §5.2 steps 3–4).
 * Blocking keeps XXL-JOB's timeout, retry and block strategies meaningful.
 *
 * <p>When the run is cancelled (a user stop) or the calling thread is interrupted (XXL-JOB timeout
 * or kill) the engine job is stopped as well, so it does not keep running unseen on the cluster.
 *
 * <p>Only a failed submission is thrown. Once the engine has the job, every problem ends in an
 * {@link Outcome}, so the caller never mistakes a running job for a rejected one and submits it
 * again.
 */
public class BatchJobRunner {

    private static final Logger log = LoggerFactory.getLogger(BatchJobRunner.class);

    /** Receives progress; the XXL-JOB handler forwards it to the scheduler log. */
    public interface Listener {
        void onSubmitted(long engineJobId);

        void onProgress(EngineJobInfo info);

        void onWarning(String message);
    }

    /**
     * @param finalStatus terminal engine status; CANCELED when stopped, UNKNOWABLE when the
     *     console lost track of the job (it may still be running)
     * @param metrics last metrics seen, empty when the engine lost the job
     */
    public record Outcome(
            long engineJobId, EngineJobStatus finalStatus, Map<String, String> metrics, String errorMessage) {

        public boolean succeeded() {
            return finalStatus == EngineJobStatus.FINISHED;
        }
    }

    static final String INTERRUPTED = "interrupted by XXL-JOB (timeout or kill)";

    /** Consecutive failed status polls after which the job is stopped and the run given up. */
    static final int MAX_POLL_FAILURES = 30;

    /** Lookups of a job whose submission got no answer, 5 seconds apart. */
    static final int SUBMIT_LOOKUPS = 3;

    /** Status checks after a stop request before the stop counts as not taken effect. */
    static final int STOP_CHECKS = 6;

    /** Waits between polls; returns true when the run was cancelled meanwhile. */
    @FunctionalInterface
    interface Sleeper {
        boolean sleep(Duration duration, CancelSignal signal) throws InterruptedException;
    }

    /** Waits between checks after a stop request, which the cancel signal must not cut short. */
    @FunctionalInterface
    interface Pause {
        void pause(Duration duration);
    }

    private final EngineClient engineClient;
    private final Duration pollInterval;
    private final Sleeper sleeper;
    private final Pause pause;

    public BatchJobRunner(EngineClient engineClient, Duration pollInterval) {
        this(engineClient, pollInterval, (d, signal) -> signal.await(d), BatchJobRunner::sleepQuietly);
    }

    BatchJobRunner(EngineClient engineClient, Duration pollInterval, Sleeper sleeper, Pause pause) {
        this.engineClient = engineClient;
        this.pollInterval = pollInterval;
        this.sleeper = sleeper;
        this.pause = pause;
    }

    /**
     * An engine job id for a fresh submission. Zeta's own ids come from a Hazelcast flake id
     * generator and stay far below 2^62; ids from here start there, so the two never meet.
     */
    static long newJobId() {
        return ThreadLocalRandom.current().nextLong(1L << 62, Long.MAX_VALUE);
    }

    /** On interrupt the thread's interrupt flag is restored before returning. */
    public Outcome run(SubmitJobRequest request, Listener listener, CancelSignal signal) {
        SubmitJobRequest withId = request.jobId() == null ? request.withJobId(newJobId()) : request;
        long jobId;
        try {
            jobId = engineClient.submit(withId);
        } catch (ConsoleException e) {
            if (e.getCode() != ErrorCodes.ENGINE_RESPONSE_LOST) {
                throw e; // the engine certainly did not get it, so the caller may report and retry
            }
            return afterLostSubmission(withId.jobId(), e, listener, signal);
        }
        submitted(listener, jobId);
        return watch(jobId, listener, signal);
    }

    /**
     * The submission may or may not have reached the engine. The job is looked up by its id a few
     * times; found, it is watched (or stopped, if the thread was interrupted meanwhile). Not
     * confirmed either way, the run ends UNKNOWABLE: retrying could run it twice, while giving up
     * leaves the decision, with the id to look for, to whoever reads the run.
     */
    private Outcome afterLostSubmission(long jobId, ConsoleException failure, Listener listener, CancelSignal signal) {
        // Cleared so the lookups can run; restored before returning.
        boolean interrupted = Thread.interrupted();
        submitted(listener, jobId);
        warn(listener, "no answer to the submission of engine job " + jobId + " (" + failure.getMessage() + "), looking it up");
        try {
            for (int i = 0; i < SUBMIT_LOOKUPS; i++) {
                if (i > 0) {
                    pause.pause(Duration.ofSeconds(5));
                }
                try {
                    if (engineClient.jobInfo(jobId).isPresent()) {
                        warn(listener, "the engine has engine job " + jobId + "; watching it");
                        return interrupted
                                ? cancel(jobId, Map.of(), listener, INTERRUPTED)
                                : watch(jobId, listener, signal);
                    }
                } catch (ConsoleException lookup) {
                    warn(listener, "cannot look up engine job " + jobId + ": " + lookup.getMessage());
                }
            }
            String message = "whether engine job " + jobId + " was started is unknown (" + failure.getMessage()
                    + "); check the cluster for it before running this job again";
            warn(listener, message);
            return new Outcome(jobId, EngineJobStatus.UNKNOWABLE, Map.of(), message);
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private Outcome watch(long jobId, Listener listener, CancelSignal signal) {
        Map<String, String> lastMetrics = Map.of();
        int failedPolls = 0;
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
                failedPolls = 0;
            } catch (ConsoleException e) {
                // The job is on the cluster whatever went wrong here, so keep watching it; the
                // XXL-JOB timeout bounds the wait, and so does this limit when there is none.
                failedPolls++;
                warn(listener, "cannot read the status of engine job " + jobId + " (" + failedPolls + "/"
                        + MAX_POLL_FAILURES + "), will retry: " + e.getMessage());
                if (failedPolls >= MAX_POLL_FAILURES) {
                    return cancel(jobId, lastMetrics, listener,
                            "lost contact with engine job " + jobId + " after " + failedPolls + " failed status checks");
                }
                continue;
            }
            if (info.isEmpty()) {
                return new Outcome(
                        jobId, EngineJobStatus.UNKNOWABLE, lastMetrics, "engine no longer knows job " + jobId);
            }
            EngineJobInfo current = info.get();
            lastMetrics = current.metrics();
            progress(listener, current);
            if (current.status().isTerminal()) {
                return new Outcome(jobId, current.status(), current.metrics(), current.errorMessage());
            }
        }
    }

    /**
     * Stops the engine job and waits until the engine reports it ended. A job that finished just
     * before the stop keeps its real outcome. A stop that fails or does not take effect is reported
     * as UNKNOWABLE, never as CANCELED, since the job may still be writing.
     */
    private Outcome cancel(long jobId, Map<String, String> lastMetrics, Listener listener, String reason) {
        Optional<Outcome> ended = endedOutcome(jobId, listener);
        if (ended.isPresent()) {
            warn(listener, reason + ", but engine job " + jobId + " had already ended as " + ended.get().finalStatus());
            return ended.get();
        }
        try {
            engineClient.stop(jobId, false, false);
            warn(listener, reason + ", stop requested for engine job " + jobId);
        } catch (ConsoleException e) {
            String message = reason + "; stopping engine job " + jobId + " failed (" + e.getMessage()
                    + "), it may still be running on the cluster";
            warn(listener, message);
            return new Outcome(jobId, EngineJobStatus.UNKNOWABLE, lastMetrics, message);
        }
        for (int i = 0; i < STOP_CHECKS; i++) {
            ended = endedOutcome(jobId, listener);
            if (ended.isPresent()) {
                Outcome outcome = ended.get();
                return outcome.finalStatus() == EngineJobStatus.CANCELED
                        ? new Outcome(jobId, EngineJobStatus.CANCELED, outcome.metrics(), reason)
                        : outcome;
            }
            pause.pause(Duration.ofSeconds(5));
        }
        String message = reason + "; engine job " + jobId + " was asked to stop but had not stopped after "
                + STOP_CHECKS * 5 + " seconds, it may still be running on the cluster";
        warn(listener, message);
        return new Outcome(jobId, EngineJobStatus.UNKNOWABLE, lastMetrics, message);
    }

    /** The job's outcome if the engine reports it as ended; empty while running or unreadable. */
    private Optional<Outcome> endedOutcome(long jobId, Listener listener) {
        try {
            Optional<EngineJobInfo> info = engineClient.jobInfo(jobId);
            if (info.isPresent() && info.get().status().isTerminal()) {
                EngineJobInfo current = info.get();
                progress(listener, current);
                return Optional.of(new Outcome(jobId, current.status(), current.metrics(), current.errorMessage()));
            }
        } catch (ConsoleException e) {
            // Unknown state; treated as still running.
        }
        return Optional.empty();
    }

    // A failure to record something (e.g. a database hiccup) must never end the watch over a job
    // that is already on the cluster: it would keep running with nobody stopping or recording it.

    private static void submitted(Listener listener, long jobId) {
        try {
            listener.onSubmitted(jobId);
        } catch (RuntimeException e) {
            log.warn("Cannot record the submission of engine job {}", jobId, e);
        }
    }

    private static void progress(Listener listener, EngineJobInfo info) {
        try {
            listener.onProgress(info);
        } catch (RuntimeException e) {
            warn(listener, "cannot record progress of engine job " + info.jobId() + ": " + e.getMessage());
        }
    }

    private static void warn(Listener listener, String message) {
        try {
            listener.onWarning(message);
        } catch (RuntimeException e) {
            log.warn("{} (and recording this warning failed: {})", message, e.getMessage());
        }
    }

    private static void sleepQuietly(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
