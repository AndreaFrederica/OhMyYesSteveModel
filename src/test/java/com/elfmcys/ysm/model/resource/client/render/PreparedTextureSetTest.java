package com.elfmcys.ysm.model.resource.client.render;

import com.elfmcys.ysm.format.schema.file.PBRImageSources;
import com.elfmcys.ysm.model.resource.client.ModelResourceFailures;
import com.elfmcys.ysm.natives.image.Image;
import com.elfmcys.ysm.natives.image.ImageSource;
import com.mojang.blaze3d.platform.NativeImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PreparedTextureSetTest {
    @Test
    void cancelledDecodeDoesNotBlockNewCandidateUsingTheSameTextureGate() throws Exception {
        var fixture = new Fixture();
        var recorded = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var gate = new com.elfmcys.ysm.model.resource.client.ModelResourceFailureGate() {
            public java.util.Optional<Throwable> failure() { return java.util.Optional.ofNullable(recorded.get()); }
            public void fail(Throwable cause) { recorded.compareAndSet(null, cause); }
        };
        var failures = new ModelResourceFailures() {
            public com.elfmcys.ysm.model.resource.client.ModelResourceFailureGate texture(String resource) { return gate; }
            public com.elfmcys.ysm.model.resource.client.ModelResourceFailureGate animation(String resource) { return gate; }
        };
        var sources = new PBRImageSources(fixture.uv, fixture.normal, fixture.specular);
        try (var cancelled = new PreparedTextureSet(sources, "test/", failures, source -> {
            throw new CancellationException("Remote chunk read was cancelled");
        })) {
            assertThrows(CancellationException.class, cancelled::get);
        }
        org.junit.jupiter.api.Assertions.assertNull(recorded.get());
        try (var retry = new PreparedTextureSet(sources, "test/", failures, fixture::decode)) {
            retry.prepare(() -> false);
            assertEquals(List.of("uv", "normal", "specular"), fixture.decodeOrder);
        }
        fixture.assertAllClosed();
        var wrapped = com.elfmcys.ysm.format.AssetLoadException.content("wrapped cancellation",
                new CancellationException("retired"));
        assertEquals(com.elfmcys.ysm.model.resource.client.ResourceFailure.Kind.TRANSIENT,
                ModelRenderTargetLoader.failure(wrapped).failure().kind());
    }

    @Test
    void cacheHitMaterializesBaseWithoutDecodingItTwice() throws Exception {
        var fixture = new Fixture();
        try (var prepared = fixture.prepared()) {
            prepared.get();
            prepared.prepare(() -> false);

            assertEquals(List.of("uv", "normal", "specular"), fixture.decodeOrder);
        }
        fixture.assertAllClosed();
    }

    @Test
    void cancellationAfterBaseDecodeDisposesThePartialSet() throws Exception {
        var fixture = new Fixture();
        var cancelled = new AtomicBoolean();
        try (var prepared = fixture.prepared(source -> {
            var image = fixture.decode(source);
            cancelled.set(true);
            return image;
        })) {
            assertThrows(CancellationException.class,
                    () -> prepared.prepare(cancelled::get));
            assertEquals(List.of("uv"), fixture.decodeOrder);
        }
        fixture.assertAllClosed();
    }

    @ParameterizedTest
    @EnumSource(TextureComponent.class)
    void eachComponentDecodeFailureStopsPreparationAndDisposesDecodedSiblings(
            TextureComponent failedComponent) throws Exception {
        var fixture = new Fixture();
        try (var prepared = fixture.prepared(source -> {
            var component = fixture.names.get(source);
            if (component.equals(failedComponent.name)) {
                fixture.decodeOrder.add(component);
                throw new IOException(component + " failed");
            }
            return fixture.decode(source);
        })) {
            assertThrows(IOException.class, () -> prepared.prepare(() -> false));
            assertEquals(failedComponent.expectedDecodeOrder, fixture.decodeOrder);
        }
        fixture.assertAllClosed();
    }

    @Test
    void transferMovesEveryImageToOneTargetBoundTextureSet() throws Exception {
        var fixture = new Fixture();
        var prepared = fixture.prepared();
        prepared.prepare(() -> false);

        var texture = prepared.transfer();
        prepared.close();
        fixture.assertAllAllocated();

        texture.closeUnregistered();
        fixture.assertAllClosed();
    }

    private static final class Fixture {
        private final ImageSource uv = unused();
        private final ImageSource normal = unused();
        private final ImageSource specular = unused();
        private final Map<ImageSource, String> names = new IdentityHashMap<>();
        private final List<String> decodeOrder = new ArrayList<>();
        private final List<NativeImage> images = new ArrayList<>();

        private Fixture() {
            names.put(uv, "uv");
            names.put(normal, "normal");
            names.put(specular, "specular");
        }

        private PreparedTextureSet prepared() {
            return prepared(this::decode);
        }

        private PreparedTextureSet prepared(PreparedTextureSet.Decoder decoder) {
            return new PreparedTextureSet(
                    new PBRImageSources(uv, normal, specular), "test/",
                    ModelResourceFailures.none(), decoder);
        }

        private NativeImage decode(ImageSource source) {
            decodeOrder.add(names.get(source));
            var image = new NativeImage(1, 1, false);
            images.add(image);
            return image;
        }

        private void assertAllAllocated() {
            images.forEach(image -> image.getPixelRGBA(0, 0));
        }

        private void assertAllClosed() {
            images.forEach(image -> assertThrows(IllegalStateException.class,
                    () -> image.getPixelRGBA(0, 0)));
        }

        private static ImageSource unused() {
            return new ImageSource() {
                @Override
                public Image open() {
                    throw new AssertionError("Injected decoder must own this test boundary");
                }
            };
        }
    }

    private enum TextureComponent {
        UV("uv", List.of("uv")),
        NORMAL("normal", List.of("uv", "normal")),
        SPECULAR("specular", List.of("uv", "normal", "specular"));

        private final String name;
        private final List<String> expectedDecodeOrder;

        TextureComponent(String name, List<String> expectedDecodeOrder) {
            this.name = name;
            this.expectedDecodeOrder = expectedDecodeOrder;
        }
    }
}
