package com.dataintegration.console.server.scheduler;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Asks a run in progress to stop. The waiting run thread wakes up immediately, stops the engine job
 * and ends the instance as CANCELED. The first reason given wins.
 */
public final class CancelSignal {

    private final CountDownLatch latch = new CountDownLatch(1);
    private final AtomicReference<String> reason = new AtomicReference<>();
    private volatile boolean interrupted;

    public void cancel(String reason) {
        this.reason.compareAndSet(null, reason);
        latch.countDown();
    }

    /**
     * Cancels because the run thread itself was interrupted (XXL-JOB timeout or kill). The caller
     * clears the interrupt flag so the outcome can still be recorded, and restores it at the end.
     */
    public void cancelByInterrupt(String reason) {
        interrupted = true;
        cancel(reason);
    }

    public boolean isCancelled() {
        return latch.getCount() == 0;
    }

    public boolean interrupted() {
        return interrupted;
    }

    public String reason() {
        return reason.get();
    }

    /** Waits up to {@code timeout}; returns true when cancelled meanwhile. */
    public boolean await(Duration timeout) throws InterruptedException {
        return latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }
}
