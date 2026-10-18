/*
 * Copyright (c) 2025 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.terminator;

import org.jspecify.annotations.Nullable;

final class TerminationRegistration {

    private static final TerminationRegistration EMPTY = new TerminationRegistration(null, null);

    @Nullable
    private final BlockingTerminable blocking;
    @Nullable
    private final NonBlockingTerminable nonBlocking;

    private TerminationRegistration(@Nullable BlockingTerminable blocking, @Nullable NonBlockingTerminable nonBlocking) {
        this.blocking = blocking;
        this.nonBlocking = nonBlocking;
    }

    static TerminationRegistration blocking(BlockingTerminable blocking) {
        return new TerminationRegistration(blocking, null);
    }

    static TerminationRegistration nonBlocking(NonBlockingTerminable nonBlocking) {
        return new TerminationRegistration(null, nonBlocking);
    }

    static TerminationRegistration empty() {
        return EMPTY;
    }

    @Nullable
    BlockingTerminable blocking() {
        return blocking;
    }

    @Nullable
    NonBlockingTerminable nonBlocking() {
        return nonBlocking;
    }
}
