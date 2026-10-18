/*
 * Copyright (c) 2025 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.terminator;

import static org.assertj.core.api.Assertions.*;

import io.github.leanish.terminator.annotation.AwaitsTermination;
import io.github.leanish.terminator.annotation.Terminates;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class TerminatorAutoRegistrationTest {

    private final Terminator terminator = new Terminator();
    private final TerminatorAutoRegistration registration = new TerminatorAutoRegistration(terminator);

    @Test
    void registersBlockingServiceAnnotatedWithTerminates()
            throws InterruptedException {
        AnnotatedBlockingService service = new AnnotatedBlockingService();

        registration.register(service);
        terminator.terminate();

        assertThat(service.terminated).isTrue();
    }

    @Test
    void registersAutoCloseableAsBlocking()
            throws InterruptedException {
        TrackingAutoCloseable closeable = new TrackingAutoCloseable();

        registration.register(closeable);
        terminator.terminate();

        assertThat(closeable.closed).isTrue();
    }

    @Test
    void registersNonBlockingServiceWithDurationAwait()
            throws InterruptedException {
        AnnotatedNonBlockingService service = new AnnotatedNonBlockingService();

        registration.register(service);
        terminator.terminate();

        assertThat(service.shutdownInitiated).isTrue();
        assertThat(terminator.awaitTermination(Duration.ofMillis(10))).isFalse();
        service.complete();
        assertThat(terminator.awaitTermination(Duration.ofSeconds(1))).isTrue();
    }

    @Test
    void registersNonBlockingServiceWithNumericAwait()
            throws InterruptedException {
        NumericAwaitNonBlockingService service = new NumericAwaitNonBlockingService();

        registration.register(service);
        terminator.terminate();

        assertThat(service.shutdownCalled).isTrue();
        assertThat(terminator.awaitTermination(Duration.ofMillis(5))).isFalse();
        service.finish();
        assertThat(terminator.awaitTermination(Duration.ofSeconds(1))).isTrue();
        assertThat(service.awaitTimeouts).hasSize(2);
        assertThat(service.awaitTimeouts.getFirst())
                .isGreaterThanOrEqualTo(0L)
                .isLessThanOrEqualTo(Duration.ofMillis(5).toNanos());
        assertThat(service.awaitTimeouts.getLast())
                .isGreaterThan(0L)
                .isLessThanOrEqualTo(Duration.ofSeconds(1).toNanos());
        assertThat(service.awaitUnits).allMatch(unit -> unit == TimeUnit.NANOSECONDS);
    }

    @Test
    void registerAllSkipsNulls()
            throws InterruptedException {
        AnnotatedBlockingService first = new AnnotatedBlockingService();
        AnnotatedBlockingService second = new AnnotatedBlockingService();

        registration.registerAll(java.util.Arrays.asList(first, null, second));
        terminator.terminate();

        assertThat(first.terminated).isTrue();
        assertThat(second.terminated).isTrue();
    }

    @Test
    void failsWhenMultipleAwaitsTerminationMethodsPresent() {
        InvalidAwaitService service = new InvalidAwaitService();

        assertThatThrownBy(() -> registration.register(service))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Multiple methods");
    }

    @SuppressWarnings("unused")
    private static final class AnnotatedBlockingService {

        private boolean terminated;

        @Terminates
        void shutdown() {
            terminated = true;
        }
    }

    private static final class TrackingAutoCloseable implements AutoCloseable {

        private boolean closed;

        @Override
        public void close() {
            closed = true;
        }
    }

    @SuppressWarnings("unused")
    private static final class AnnotatedNonBlockingService {

        private final CountDownLatch latch = new CountDownLatch(1);
        private final AtomicBoolean shutdownInitiated = new AtomicBoolean();

        @Terminates
        void shutdown() {
            shutdownInitiated.set(true);
        }

        @AwaitsTermination
        boolean waitForCompletion(Duration timeout)
                throws InterruptedException {
            return latch.await(timeout.toNanos(), TimeUnit.NANOSECONDS);
        }

        void complete() {
            latch.countDown();
        }
    }

    @SuppressWarnings("unused")
    private static final class NumericAwaitNonBlockingService {

        private final CountDownLatch latch = new CountDownLatch(1);
        private final AtomicBoolean shutdownCalled = new AtomicBoolean();
        private final java.util.List<Long> awaitTimeouts = new java.util.ArrayList<>();
        private final java.util.List<TimeUnit> awaitUnits = new java.util.ArrayList<>();

        @Terminates
        void shutdown() {
            shutdownCalled.set(true);
        }

        @AwaitsTermination
        boolean await(long timeout, TimeUnit unit)
                throws InterruptedException {
            awaitTimeouts.add(timeout);
            awaitUnits.add(unit);
            boolean done = latch.await(timeout, unit);
            return done;
        }

        void finish() {
            latch.countDown();
        }
    }

    @SuppressWarnings("unused")
    private static final class InvalidAwaitService {

        @Terminates
        void shutdown() {
        }

        @AwaitsTermination
        boolean awaitOne(Duration timeout) {
            return true;
        }

        @AwaitsTermination
        boolean awaitTwo(Duration timeout) {
            return true;
        }
    }
}
