/*
 * Copyright (c) 2025 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.terminator;

import java.util.Objects;

import org.jspecify.annotations.Nullable;

/** Registers services that implement a termination interface or {@link AutoCloseable}. */
public final class TerminatorAutoRegistration {

    private final Terminator terminator;

    /** Creates a registrar for the supplied coordinator. */
    public TerminatorAutoRegistration(Terminator terminator) {
        this.terminator = Objects.requireNonNull(terminator, "terminator");
    }

    /**
     * Registers a supported service. Non-blocking services take precedence over blocking services,
     * followed by AutoCloseable. Objects with none of these contracts are ignored.
     *
     * @param candidate service to inspect
     */
    public void register(Object candidate) {
        Objects.requireNonNull(candidate, "candidate");
        if (candidate instanceof NonBlockingTerminable nonBlocking) {
            terminator.register(nonBlocking);
            return;
        }
        if (candidate instanceof BlockingTerminable blocking) {
            terminator.register(blocking);
            return;
        }
        if (candidate instanceof AutoCloseable closeable) {
            terminator.register(new AutoCloseableTerminable(closeable));
        }
    }

    /** Registers supported elements, skipping null entries. */
    public void registerAll(Iterable<? extends @Nullable Object> candidates) {
        Objects.requireNonNull(candidates, "candidates");
        for (Object candidate : candidates) {
            if (candidate != null) {
                register(candidate);
            }
        }
    }

    private record AutoCloseableTerminable(AutoCloseable closeable) implements BlockingTerminable {

        @Override
        public void terminate() throws InterruptedException {
            try {
                closeable.close();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw interrupted;
            } catch (RuntimeException runtime) {
                throw runtime;
            } catch (Exception failure) {
                throw new IllegalStateException("Could not close registered service", failure);
            }
        }
    }
}
