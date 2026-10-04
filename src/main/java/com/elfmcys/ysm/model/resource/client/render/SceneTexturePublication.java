package com.elfmcys.ysm.model.resource.client.render;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Transaction over one target's host mappings; published bindings are adopted by the target owner. */
public final class SceneTexturePublication {
    private SceneTexturePublication() {}

    public interface Host<I> {
        /** Success means sampled pixels and exact mapping are ready. Failure must dispose its own partial entry. */
        I register(PreparedSceneTextures.Pixels pixels) throws Exception;
        void release(I mapping) throws Exception;
    }

    public static <I> Binding<I> publish(PreparedSceneTextures source, Host<I> host, BooleanSupplier cancelled)
            throws Exception {
        var mappings = new LinkedHashMap<PreparedSceneTextures.Key, I>();
        try {
            for (var entry : source.textures().entrySet()) {
                PreparedSceneTextures.requireActive(cancelled);
                var mapping = Objects.requireNonNull(host.register(entry.getValue()), "Host returned no scene texture mapping");
                mappings.put(entry.getKey(), mapping);
            }
            PreparedSceneTextures.requireActive(cancelled);
            return new Binding<>(host, mappings);
        } catch (Exception | Error failure) {
            try { new Binding<>(host, mappings).close(); }
            catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    public static final class Binding<I> implements AutoCloseable {
        private final Host<I> host;
        private final Map<PreparedSceneTextures.Key, I> mappings;
        private final AtomicBoolean closed = new AtomicBoolean();
        private Binding(Host<I> host, Map<PreparedSceneTextures.Key, I> mappings) {
            this.host = host;
            this.mappings = Collections.unmodifiableMap(new LinkedHashMap<>(mappings));
        }
        public Map<PreparedSceneTextures.Key, I> mappings() { return mappings; }
        @Override public void close() throws Exception {
            if (!closed.compareAndSet(false, true)) return;
            var ids = new ArrayList<>(mappings.values());
            Exception failure = null;
            for (int i = ids.size() - 1; i >= 0; i--) {
                try { host.release(ids.get(i)); }
                catch (Exception error) {
                    if (failure == null) failure = error;
                    else failure.addSuppressed(error);
                }
            }
            if (failure != null) throw failure;
        }
    }
}
