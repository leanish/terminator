/*
 * Copyright (c) 2025 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.terminator;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class TerminatorTest {

    @Test
    void terminatesRegisteredServicesInReverseOrder()
            throws InterruptedException {
        Terminator terminator = new Terminator();
        List<Integer> order = Collections.synchronizedList(new ArrayList<>());

        terminator.register(new RecordingService(order, 1));
        terminator.register(new RecordingService(order, 2));
        terminator.register(new RecordingService(order, 3));

        terminator.terminate();

        assertThat(order).containsExactly(3, 2, 1);
    }

    @Test
    void blockingAndNonBlockingServicesCoordinate()
            throws InterruptedException {
        Terminator terminator = new Terminator();
        TrackingBlockingService blocking = new TrackingBlockingService(Duration.ofMillis(80));
        NonBlockingService nonBlocking = new NonBlockingService(Duration.ofMillis(150));

        terminator.register(blocking);
        terminator.register(nonBlocking);

        long start = System.nanoTime();
        terminator.terminate();
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(blocking.wasTerminated())
                .as("Blocking service should be terminated after terminate() returns")
                .isTrue();
        assertThat(terminator.awaitTermination(Duration.ZERO))
                .as("Immediate await should fail while non-blocking service shuts down")
                .isFalse();
        assertThat(elapsed)
                .as("terminate() should return before the asynchronous service completes")
                .isLessThan(Duration.ofMillis(150));
        assertThat(terminator.awaitTermination(Duration.ofMillis(50)))
                .as("Short await should time out while non-blocking service shuts down")
                .isFalse();
        assertThat(terminator.awaitTermination(Duration.ofMillis(500)))
                .as("Longer await should observe completion")
                .isTrue();
    }

    @Test
    void negativeTimeoutTreatedAsZero()
            throws InterruptedException {
        Terminator terminator = new Terminator();
        NonBlockingService nonBlocking = new NonBlockingService(Duration.ofMillis(150));
        terminator.register(nonBlocking);

        terminator.terminate();

        assertThat(terminator.isTerminating()).isTrue();
        assertThat(terminator.awaitTermination(Duration.ofMillis(-10)))
                .as("Negative timeout should behave like zero and return immediately")
                .isFalse();
        assertThat(terminator.awaitTermination(Duration.ofMillis(500)))
                .as("Subsequent positive timeout should detect completion")
                .isTrue();
        assertThat(terminator.awaitTermination(Duration.ZERO)).isTrue();
    }

    @Test
    void awaitTerminationReturnsTrueWhenOnlyBlockingServices()
            throws InterruptedException {
        Terminator terminator = new Terminator();
        terminator.register(new TrackingBlockingService(Duration.ZERO));

        assertThat(terminator.awaitTermination(Duration.ofMillis(10))).isTrue();
    }

    @Test
    void terminateAggregatesRuntimeFailures() {
        Terminator terminator = new Terminator();
        ThrowingBlockingService first = new ThrowingBlockingService(new IllegalStateException("first"));
        ThrowingBlockingService second = new ThrowingBlockingService(new IllegalArgumentException("second"));
        terminator.register(first);
        terminator.register(second);

        TerminationException exception = catchThrowableOfType(TerminationException.class, terminator::terminate);

        assertThat(exception).isNotNull();
        assertThat(exception.getCause()).hasMessage("second");
        assertThat(exception.getSuppressed())
                .singleElement()
                .extracting(Throwable::getMessage)
                .isEqualTo("first");
    }

    @Test
    void terminatePropagatesInterruptions()
            throws InterruptedException {
        Terminator terminator = new Terminator();
        terminator.register(new SleepyBlockingService(Duration.ofSeconds(1)));

        Thread mainThread = Thread.currentThread();
        Thread interrupter = new Thread(() -> {
            try {
                Thread.sleep(50);
                mainThread.interrupt();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        interrupter.start();

        assertThatThrownBy(terminator::terminate).isInstanceOf(InterruptedException.class);
        assertThat(mainThread.isInterrupted()).isTrue();
        interrupter.join();
    }

    @Test
    void awaitTerminationPropagatesInterruptions()
            throws InterruptedException {
        Terminator terminator = new Terminator();
        NonBlockingService nonBlocking = new NonBlockingService(Duration.ofSeconds(1));
        terminator.register(nonBlocking);
        terminator.terminate();

        InterruptedException captured = null;
        for (int attempt = 0; attempt < 5 && captured == null; attempt++) {
            Thread mainThread = Thread.currentThread();
            Thread interrupter = new Thread(() -> {
                try {
                    Thread.sleep(50);
                    mainThread.interrupt();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
            interrupter.start();
            try {
                captured = catchThrowableOfType(InterruptedException.class,
                        () -> terminator.awaitTermination(Duration.ofSeconds(5)));
            } finally {
                interrupter.join();
            }
        }

        assertThat(captured).as("Expected interruption to be observed").isNotNull();
        Thread.currentThread().interrupt();
    }

    @Test
    void terminateInvokedWhileAlreadyTerminatingReturnsImmediately()
            throws InterruptedException {
        Terminator terminator = new Terminator();
        terminator.register(new SleepyBlockingService(Duration.ofMillis(100)));

        Thread shutdownThread = new Thread(() -> {
            try {
                terminator.terminate();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        shutdownThread.start();

        //noinspection LoopConditionNotUpdatedInsideLoop // updated by shutdownThread
        while (!terminator.isTerminating()) {
            Thread.onSpinWait();
        }

        terminator.terminate();
        shutdownThread.join();
    }

    @Test
    void nonBlockingServicesSignalledBeforeBlockingOnTerminate()
            throws InterruptedException {
        Terminator terminator = new Terminator();
        List<String> order = Collections.synchronizedList(new ArrayList<>());

        terminator.register(() -> order.add("blocking"));

        terminator.register(new NonBlockingTerminable() {
            @Override
            public void terminate() {
                order.add("non-blocking");
            }

            @Override
            public boolean awaitTermination(Duration timeout) {
                return true;
            }
        });

        terminator.terminate();

        assertThat(order).containsExactly("non-blocking", "blocking");
    }

    @Test
    void registerAfterTerminationInitiatedFails()
            throws InterruptedException {
        Terminator terminator = new Terminator();
        TrackingBlockingService blocking = new TrackingBlockingService(Duration.ZERO);
        terminator.register(blocking);

        terminator.terminate();
        assertThatThrownBy(() -> terminator.register(blocking)).isInstanceOf(IllegalStateException.class);
    }

    private static void sleep(Duration duration)
            throws InterruptedException {
        if (duration.isZero()) {
            return;
        }
        long totalNanos = duration.toNanos();
        long millis = TimeUnit.NANOSECONDS.toMillis(totalNanos);
        int nanos = (int) (totalNanos - TimeUnit.MILLISECONDS.toNanos(millis));
        Thread.sleep(millis, nanos);
    }

    private record RecordingService(List<Integer> order, int id) implements BlockingTerminable {

        @Override
        public void terminate() {
            order.add(id);
        }
    }

    private static final class TrackingBlockingService implements BlockingTerminable {
        private final Duration delay;
        private final AtomicBoolean terminated = new AtomicBoolean();

        private TrackingBlockingService(Duration delay) {
            this.delay = delay;
        }

        @Override
        public void terminate()
                throws InterruptedException {
            if (terminated.compareAndSet(false, true)) {
                sleep(delay);
            }
        }

        boolean wasTerminated() {
            return terminated.get();
        }
    }

    private record SleepyBlockingService(Duration delay) implements BlockingTerminable {

        @Override
        public void terminate()
                throws InterruptedException {
            sleep(delay);
        }
    }

    private record ThrowingBlockingService(RuntimeException toThrow) implements BlockingTerminable {

        @Override
        public void terminate() {
            throw toThrow;
        }
    }

    private static final class NonBlockingService implements NonBlockingTerminable {
        private final Duration shutdownDelay;
        private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "non-blocking-service");
            thread.setDaemon(true);
            return thread;
        });
        private final CountDownLatch latch = new CountDownLatch(1);
        private final AtomicInteger terminateCalls = new AtomicInteger();

        private NonBlockingService(Duration shutdownDelay) {
            this.shutdownDelay = shutdownDelay;
        }

        @Override
        public void terminate() {
            if (terminateCalls.incrementAndGet() == 1) {
                executor.execute(() -> {
                    try {
                        sleep(shutdownDelay);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        latch.countDown();
                        executor.shutdown();
                    }
                });
            }
        }

        @Override
        public boolean awaitTermination(Duration timeout)
                throws InterruptedException {
            Duration effective = timeout.isNegative() ? Duration.ZERO : timeout;
            long timeoutNanos = effective.toNanos();
            return latch.await(timeoutNanos, TimeUnit.NANOSECONDS);
        }
    }
}
