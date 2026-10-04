package com.elfmcys.ysm.model.resource.client.render;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.io.ReadLimits;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SceneTexturePublicationTest {
    private static final ScenePackageImages.Key IMAGE = ScenePackageImages.Key.indexed("avatar.gltf", 0);
    private static final SceneImageUsage COLOR = new SceneImageUsage(SceneImageUsage.Transfer.SRGB, SceneImageUsage.Alpha.STRAIGHT);
    private static final ScenePackageImages SOURCE = new ScenePackageImages(Map.of(IMAGE,
            new SceneImage(SceneImage.Format.PNG, 1, 1, new FloatData(.5f, .5f, .5f, 1), new IntData(8, 8, 8, 8),
                    SceneImage.Alpha.STRAIGHT, Map.of(), ByteData.EMPTY)));

    @Test void sharedPixelsKeepIndependentSamplerMappingsAndExactlyOneRelease() throws Exception {
        var prepared = prepared();
        var entries = new ArrayList<>(prepared.textures().values());
        assertSame(entries.get(0).rgba(), entries.get(1).rgba());
        assertNotEquals(entries.get(0).sampler(), entries.get(1).sampler());
        assertEquals(.21404114, entries.get(0).rgba().get(0), 1e-7);
        var host = new Host();
        var binding = SceneTexturePublication.publish(prepared, host, () -> false);
        assertEquals(2, binding.mappings().size());
        assertTrue(host.released.isEmpty());
        binding.close(); binding.close();
        assertEquals(List.of(2, 1), host.released);
    }

    @Test void registrationFailureRollsBackOnlyEarlierAdoptionsAndKeepsPrimaryFailure() throws Exception {
        var host = new Host(); host.failRegistration = 2; host.failRelease = true;
        var failure = assertThrows(IOException.class, () -> SceneTexturePublication.publish(prepared(), host, () -> false));
        assertEquals("upload failed", failure.getMessage());
        assertEquals(List.of(1), host.released);
        assertEquals(1, failure.getSuppressed().length);
        assertEquals("release failed", failure.getSuppressed()[0].getMessage());
    }

    @Test void cancellationBeforeAndAfterUploadCannotPublishPartialBindings() throws Exception {
        var host = new Host();
        assertThrows(CancellationException.class, () -> SceneTexturePublication.publish(prepared(), host, () -> true));
        assertEquals(0, host.created);
        var cancelled = new AtomicBoolean();
        host.cancelAfterRegistration = cancelled;
        assertThrows(CancellationException.class, () -> SceneTexturePublication.publish(prepared(), host, cancelled::get));
        assertEquals(List.of(1), host.released);
    }

    @Test void eachSamplerAllocationCountsAgainstTextureStorageBudget() throws Exception {
        var keys = new ArrayList<PreparedSceneTextures.Key>();
        for (int wrap : List.of(33071, 33648, 10497)) for (int mag : List.of(9728, 9729)) {
            keys.add(new PreparedSceneTextures.Key(IMAGE, new PreparedSceneTextures.Sampler(mag, 9728, wrap, 10497), COLOR));
        }
        assertThrows(IOException.class, () -> PreparedSceneTextures.prepare(SOURCE, keys,
                new ReadLimits(47, 100, 1), () -> false));
        // Six independent 1x1 RGBA16F allocations require 48 bytes; CPU samples are shared.
        assertEquals(6, PreparedSceneTextures.prepare(SOURCE, keys,
                new ReadLimits(48, 100, 1), () -> false).textures().size());
        assertThrows(IllegalArgumentException.class, () -> new PreparedSceneTextures.Sampler(9987, 9729, 10497, 10497));
    }

    private PreparedSceneTextures prepared() throws Exception {
        return PreparedSceneTextures.prepare(SOURCE, List.of(
                new PreparedSceneTextures.Key(IMAGE, new PreparedSceneTextures.Sampler(9728, 9728, 33071, 33071), COLOR),
                new PreparedSceneTextures.Key(IMAGE, new PreparedSceneTextures.Sampler(9729, 9987, 10497, 10497), COLOR)),
                ReadLimits.DEFAULT, () -> false);
    }
    private static final class Host implements SceneTexturePublication.Host<Integer> {
        int created, failRegistration = -1;
        boolean failRelease;
        AtomicBoolean cancelAfterRegistration;
        final List<Integer> released = new ArrayList<>();
        public Integer register(PreparedSceneTextures.Pixels pixels) throws IOException {
            if (++created == failRegistration) throw new IOException("upload failed");
            if (cancelAfterRegistration != null) cancelAfterRegistration.set(true);
            return created;
        }
        public void release(Integer mapping) throws IOException {
            released.add(mapping);
            if (failRelease) throw new IOException("release failed");
        }
    }
}
