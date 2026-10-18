/*
 * Copyright (c) 2025 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.terminator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

class TerminatorAutoRegistrationTest {

    @Test
    void registersAutoCloseableInReverseOrder() throws InterruptedException {
        Terminator terminator = new Terminator();
        TerminatorAutoRegistration registration = new TerminatorAutoRegistration(terminator);
        List<String> closed = new ArrayList<>();
        AutoCloseable first = () -> closed.add("first");
        AutoCloseable second = () -> closed.add("second");

        registration.registerAll(List.of(first, second));
        terminator.terminate();
        terminator.terminate();

        assertThat(closed).containsExactly("second", "first");
    }

    @Test
    void registersBlockingInterfacesBeforeAutoCloseable() throws InterruptedException {
        Terminator terminator = new Terminator();
        DualContract service = new DualContract();

        new TerminatorAutoRegistration(terminator).register(service);
        terminator.terminate();

        assertThat(service.terminated).isTrue();
        assertThat(service.closed).isFalse();
    }

    @Test
    void registersNonBlockingServices() throws InterruptedException {
        Terminator terminator = new Terminator();
        AtomicBoolean stopped = new AtomicBoolean();
        NonBlockingTerminable service = new NonBlockingTerminable() {
            @Override
            public void terminate() {
                stopped.set(true);
            }

            @Override
            public boolean awaitTermination(Duration timeout) {
                return stopped.get();
            }
        };

        new TerminatorAutoRegistration(terminator).register(service);
        terminator.terminate();

        assertThat(stopped).isTrue();
        assertThat(terminator.awaitTermination(Duration.ofSeconds(1))).isTrue();
    }

    @Test
    void skipsNullEntriesAndUnsupportedObjects() throws InterruptedException {
        Terminator terminator = new Terminator();
        AtomicBoolean closed = new AtomicBoolean();
        AutoCloseable service = () -> closed.set(true);

        new TerminatorAutoRegistration(terminator).registerAll(Arrays.asList(null, new Object(), service));
        terminator.terminate();

        assertThat(closed).isTrue();
    }

    @Test
    void rejectsNullArguments() {
        TerminatorAutoRegistration registration = new TerminatorAutoRegistration(new Terminator());

        assertThatThrownBy(() -> new TerminatorAutoRegistration(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> registration.register(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> registration.registerAll(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void retainsCheckedCloseFailure() {
        Terminator terminator = new Terminator();
        IOException failure = new IOException("close failed");
        AutoCloseable service = () -> {
            throw failure;
        };
        new TerminatorAutoRegistration(terminator).register(service);

        assertThatThrownBy(terminator::terminate)
                .isInstanceOf(TerminationException.class)
                .hasRootCause(failure);
    }

    @Test
    void retainsRuntimeCloseFailure() {
        Terminator terminator = new Terminator();
        IllegalArgumentException failure = new IllegalArgumentException("close failed");
        AutoCloseable service = () -> {
            throw failure;
        };
        new TerminatorAutoRegistration(terminator).register(service);

        assertThatThrownBy(terminator::terminate)
                .isInstanceOf(TerminationException.class)
                .hasCause(failure);
    }

    @Test
    void preservesInterruptedClose() {
        Terminator terminator = new Terminator();
        AutoCloseable service = () -> {
            throw new InterruptedException("interrupted");
        };
        new TerminatorAutoRegistration(terminator).register(service);
        try {
            assertThatThrownBy(terminator::terminate).isInstanceOf(InterruptedException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    private static final class DualContract implements BlockingTerminable, AutoCloseable {
        private boolean terminated;
        private boolean closed;

        @Override
        public void terminate() {
            terminated = true;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
