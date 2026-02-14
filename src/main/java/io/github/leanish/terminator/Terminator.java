/*
 * Copyright (c) 2025 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.terminator;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * Coordinates termination of multiple {@link Terminable} services.
 */
public final class Terminator {

    private static final Duration UNBOUNDED_WAIT = Duration.ofMillis(Long.MAX_VALUE);

    private final Object monitor = new Object();
    private final List<BlockingTerminable> blockingServices = new ArrayList<>();
    private final List<NonBlockingTerminable> nonBlockingServices = new ArrayList<>();
    private boolean terminating;

    /**
     * Creates a new terminator with no registered services.
     */
    public Terminator() {
    }

    /**
     * Registers a service to be terminated when {@link #terminate()} is invoked.
     *
     * @param service blocking service to register
     * @throws IllegalStateException if termination has already been initiated
     */
    public void register(BlockingTerminable service) {
        registerService(service, blockingServices);
    }

    /**
     * Registers a service to be terminated when {@link #terminate()} is invoked.
     *
     * @param service non-blocking service to register
     * @throws IllegalStateException if termination has already been initiated
     */
    public void register(NonBlockingTerminable service) {
        registerService(service, nonBlockingServices);
    }

    private <T extends Terminable> void registerService(T service, List<T> bucket) {
        Objects.requireNonNull(service, "service");
        synchronized (monitor) {
            if (terminating) {
                throw new IllegalStateException("Cannot register services after termination has started");
            }
            bucket.add(service);
        }
    }

    /**
     * Initiates termination of all registered services in reverse order of registration.
     *
     * @throws InterruptedException if interrupted while waiting on a blocking service termination
     * @throws TerminationException if one or more services fail to terminate cleanly
     */
    public void terminate()
            throws InterruptedException {
        checkInterrupted();

        List<NonBlockingTerminable> nonBlockingSnapshot;
        List<BlockingTerminable> blockingSnapshot;
        synchronized (monitor) {
            if (terminating) {
                return;
            }
            terminating = true;
            nonBlockingSnapshot = new ArrayList<>(nonBlockingServices);
            blockingSnapshot = new ArrayList<>(blockingServices);
        }

        TerminationException failure = terminateServices(nonBlockingSnapshot, null);
        failure = terminateServices(blockingSnapshot, failure);

        checkInterrupted();
        if (failure != null) {
            throw failure;
        }
    }

    @Nullable
    private TerminationException terminateServices(List<? extends Terminable> snapshot, @Nullable TerminationException failure)
            throws InterruptedException {
        checkInterrupted();

        ListIterator<? extends Terminable> iterator = snapshot.listIterator(snapshot.size());
        while (iterator.hasPrevious()) {
            checkInterrupted();
            Terminable service = iterator.previous();
            try {
                service.terminate();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            } catch (RuntimeException ex) {
                if (failure == null) {
                    failure = new TerminationException("Failed to terminate registered services", ex);
                } else {
                    failure.addSuppressed(ex);
                }
            }
        }

        checkInterrupted();
        return failure;
    }

    /**
     * Waits for the registered services to finish terminating, up to the supplied timeout.
     *
     * @param timeout maximum time to wait; negative values are treated as zero
     * @return {@code true} if every service completed termination in time, {@code false} otherwise
     * @throws InterruptedException if interrupted while waiting
     */
    public boolean awaitTermination(Duration timeout)
            throws InterruptedException {
        Objects.requireNonNull(timeout, "timeout");

        checkInterrupted();

        List<NonBlockingTerminable> snapshot;
        synchronized (monitor) {
            snapshot = new ArrayList<>(nonBlockingServices);
        }
        if (snapshot.isEmpty()) {
            return true;
        }

        Duration effectiveTimeout = timeout.isNegative() ? Duration.ZERO : timeout;
        boolean unlimited = effectiveTimeout.compareTo(UNBOUNDED_WAIT) >= 0;
        Duration deadline = unlimited ? UNBOUNDED_WAIT : effectiveTimeout;
        long start = System.nanoTime();

        boolean allTerminated = true;
        for (NonBlockingTerminable service : snapshot) {
            checkInterrupted();

            Duration waitDuration = UNBOUNDED_WAIT;
            if (!unlimited) {
                long elapsedNanos = System.nanoTime() - start;
                Duration remaining = deadline.minus(Duration.ofNanos(elapsedNanos));
                waitDuration = remaining.isNegative() ? Duration.ZERO : remaining;
            }

            allTerminated &= service.awaitTermination(waitDuration);
        }

        checkInterrupted();
        return allTerminated;
    }

    /**
     * Indicates if the termination has been initiated.
     *
     * @return {@code true} when termination has started, otherwise {@code false}
     */
    public boolean isTerminating() {
        synchronized (monitor) {
            return terminating;
        }
    }

    private static void checkInterrupted()
            throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Termination interrupted");
        }
    }
}
