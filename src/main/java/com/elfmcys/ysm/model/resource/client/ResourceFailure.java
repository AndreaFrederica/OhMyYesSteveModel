package com.elfmcys.ysm.model.resource.client;

import java.util.Objects;

public record ResourceFailure(Kind kind, Throwable cause) {
    public ResourceFailure {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(cause, "cause");
    }

    /** Cancellation belongs to a request, never to the immutable model content. */
    public static boolean isCancellation(Throwable error) {
        var visited = java.util.Collections.newSetFromMap(
                new java.util.IdentityHashMap<Throwable, Boolean>());
        for (var current = error; current != null && visited.add(current); current = current.getCause()) {
            if (current instanceof java.util.concurrent.CancellationException) return true;
        }
        return false;
    }

    public enum Kind {
        DETERMINISTIC,
        TRANSIENT
    }
}
