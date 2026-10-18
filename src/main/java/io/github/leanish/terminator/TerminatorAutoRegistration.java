/*
 * Copyright (c) 2025 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.terminator;

import java.util.Objects;

/**
 * Registers managed services with a {@link Terminator} by inspecting their shutdown annotations or marker interfaces.
 */
public final class TerminatorAutoRegistration {

    private final Terminator terminator;

    /**
     * Creates a new registrar that adds services to the supplied {@link Terminator} instance.
     */
    public TerminatorAutoRegistration(Terminator terminator) {
        this.terminator = Objects.requireNonNull(terminator, "terminator");
    }

    /**
     * Attempts to register the supplied service with the underlying {@link Terminator}. Services are recognised when
     * they implement {@link BlockingTerminable}, {@link NonBlockingTerminable}, implement {@link AutoCloseable}, or
     * expose appropriately annotated shutdown methods.
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
        TerminationRegistration registration = TerminationIntrospector.inspect(candidate);
        if (registration.nonBlocking() != null) {
            terminator.register(registration.nonBlocking());
        } else if (registration.blocking() != null) {
            terminator.register(registration.blocking());
        }
    }

    /**
     * Attempts to register every element of {@code candidates}.
     */
    public void registerAll(Iterable<?> candidates) {
        Objects.requireNonNull(candidates, "candidates");
        for (Object candidate : candidates) {
            if (candidate != null) {
                register(candidate);
            }
        }
    }
}
