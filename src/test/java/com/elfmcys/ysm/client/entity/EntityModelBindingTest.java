package com.elfmcys.ysm.client.entity;

import com.elfmcys.ysm.client.animation.condition.ConditionManager;
import com.elfmcys.ysm.client.animation.condition.FPArmConditionManager;
import com.elfmcys.ysm.format.schema.file.ChunkDataSource;
import com.elfmcys.ysm.format.schema.model.ModelFileView;
import com.elfmcys.ysm.format.schema.model.views.ModelInfoView;
import com.elfmcys.ysm.geckolib3.core.molang.value.IValue;
import com.elfmcys.ysm.geckolib3.geo.render.built.GeoModel;
import com.elfmcys.ysm.model.catalog.content.ModelContent;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelRepresentation;
import com.elfmcys.ysm.model.resource.client.AcquireResult;
import com.elfmcys.ysm.model.resource.client.AnimationStore;
import com.elfmcys.ysm.model.resource.client.BakeProfile;
import com.elfmcys.ysm.model.resource.client.CommonAsset;
import com.elfmcys.ysm.model.resource.client.ModelRenderTarget;
import com.elfmcys.ysm.model.resource.client.PlayerModelResources;
import com.elfmcys.ysm.model.resource.client.PlayerModelVariant;
import com.elfmcys.ysm.model.resource.client.ResourceFailure;
import com.elfmcys.ysm.model.resource.client.ResourceLease;
import com.elfmcys.ysm.model.resource.client.ResourceRequest;
import com.elfmcys.ysm.util.FifoHashMap;
import com.elfmcys.ysm.util.UnsafeUtil;
import it.unimi.dsi.fastutil.objects.Object2ReferenceMap;
import it.unimi.dsi.fastutil.objects.Object2ReferenceOpenHashMap;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityModelBindingTest {
    @org.junit.jupiter.api.BeforeAll
    static void initializeHostValueClassesBeforeCreatingCleanerOwnedFixtures() throws Exception {
        // Forge initializes these serially during startup. Plain-JVM fixtures must do the
        // same before Cleaner can race ResourceLocation -> Style against Screen -> Style.
        Class.forName("net.minecraft.resources.ResourceLocation");
    }
    private static final BakeProfile PROFILE = new BakeProfile("test");

    @Test
    void authoritativeDefaultUsesPrimaryWhileMissingSelectionUsesFallback() throws Exception {
        var defaultId = hash(101);
        var primaryModels = new FakeModelAccess();
        primaryModels.defaultId = defaultId;
        primaryModels.contents.put(defaultId, content(defaultId));
        primaryModels.offlineResults.add(CompletableFuture.completedFuture(
                Optional.of(FakeLease.ready(defaultId, target()))));
        var primary = new EntityModelBinding(primaryModels);

        primary.updateModelHash(defaultId);
        primary.synchronize("player", "default", "player", EntityModelBindingTest::holder);

        assertSame(defaultId, primary.modelHash());
        assertFalse(primary.resourceHolder().fallback);

        var fallbackModels = new FakeModelAccess();
        fallbackModels.defaultId = defaultId;
        fallbackModels.leases.add(FakeLease.ready(defaultId, target()));
        var fallback = new EntityModelBinding(fallbackModels);

        fallback.updateModelHash(null);
        fallback.synchronize("player", "", "player", EntityModelBindingTest::holder);

        assertNull(fallback.modelHash());
        assertTrue(fallback.resourceHolder().fallback);
    }

    @Test
    void replacementKeepsReadyLeasesCleanerManagedAndCancelsReplacedPending() throws Exception {
        var firstId = hash(1);
        var secondId = hash(2);
        var thirdId = hash(3);
        var models = new FakeModelAccess();
        models.contents.put(firstId, content(firstId));
        models.contents.put(secondId, content(secondId));
        models.contents.put(thirdId, content(thirdId));
        var first = FakeLease.ready(firstId, target());
        var second = FakeLease.pending(secondId);
        var third = FakeLease.pending(thirdId);
        models.leases.add(first);
        models.leases.add(second);
        models.leases.add(third);
        var binding = new EntityModelBinding(models);

        binding.updateModelHash(firstId);
        binding.synchronize("player", "default", null, EntityModelBindingTest::holder);
        var firstHolder = binding.resourceHolder();
        assertSame(first, firstHolder.lease());

        binding.updateModelHash(secondId);
        binding.synchronize("player", "default", null, EntityModelBindingTest::holder);
        assertSame(firstHolder, binding.resourceHolder());
        assertEquals(0, first.closes.get());

        binding.updateModelHash(thirdId);
        binding.synchronize("player", "default", null, EntityModelBindingTest::holder);
        assertEquals(1, second.pendingCancellations.get());
        assertSame(firstHolder, binding.resourceHolder());

        third.result = new AcquireResult.Ready(target());
        binding.synchronize("player", "default", null, EntityModelBindingTest::holder);
        assertSame(third, binding.resourceHolder().lease());
        assertEquals(0, first.closes.get());
        assertEquals(0, third.closes.get());

        binding.close();
        assertEquals(0, third.closes.get());
    }

    @Test
    void deterministicFailureMarkerPreventsPerTickReacquisition() {
        var modelId = hash(4);
        var models = new FakeModelAccess();
        models.contents.put(modelId, content(modelId));
        var failed = FakeLease.failed(modelId, ResourceFailure.Kind.DETERMINISTIC);
        models.leases.add(failed);
        var binding = new EntityModelBinding(models);

        binding.updateModelHash(modelId);
        binding.synchronize("player", "default", null, EntityModelBindingTest::holder);
        binding.synchronize("player", "default", null, EntityModelBindingTest::holder);

        assertEquals(1, models.acquisitions.get());
        assertEquals(1, models.failures.get());
        assertEquals(0, failed.closes.get());
        assertEquals(0, failed.pendingCancellations.get());
    }

    @Test
    void modelSwitchDoesNotExposeActivationRetryCapability() {
        var firstId = hash(30);
        var secondId = hash(31);
        var models = new FakeModelAccess();
        var binding = new EntityModelBinding(models);

        binding.updateModelHash(firstId);
        binding.synchronize("player", "default", null, EntityModelBindingTest::holder);
        binding.updateModelHash(secondId);
        binding.synchronize("player", "default", null, EntityModelBindingTest::holder);
        binding.updateModelHash(firstId);
        binding.synchronize("player", "default", null, EntityModelBindingTest::holder);

        assertFalse(Arrays.stream(EntityModelBinding.ModelAccess.class
                        .getDeclaredMethods())
                .anyMatch(method -> method.getName().equals("retryModelUse")));
        assertEquals(0, models.acquisitions.get());
    }

    @Test
    void unavailableNonPlayerReplacementAcquiresNoYsmTarget() {
        var modelId = hash(9);
        var models = new FakeModelAccess();
        models.contents.put(modelId, content(modelId));
        var binding = new EntityModelBinding(models);

        binding.updateModelHash(modelId);
        binding.synchronize(null, "", null, EntityModelBindingTest::holder);

        assertNull(binding.resourceHolder());
        assertEquals(0, models.acquisitions.get());
        assertEquals(0, models.failures.get());
    }

    @Test
    void rejectedReadyAndTemporaryFallbackHandlesAreClosed() throws Exception {
        var modelId = hash(5);
        var defaultId = hash(6);
        var models = new FakeModelAccess();
        models.defaultId = defaultId;
        models.contents.put(modelId, content(modelId));
        var rejected = FakeLease.ready(modelId, target());
        var fallbackPending = FakeLease.pending(defaultId);
        models.leases.add(rejected);
        models.leases.add(fallbackPending);
        var binding = new EntityModelBinding(models);

        binding.updateModelHash(modelId);
        binding.synchronize("player", "default", null, (lease, fallback) -> null);
        binding.synchronize("player", "default", null, (lease, fallback) -> null);
        assertEquals(1, models.acquisitions.get());
        assertEquals(1, rejected.closes.get());

        binding.clearModel();
        binding.synchronize(null, "", "player", EntityModelBindingTest::holder);
        assertEquals(1, fallbackPending.pendingCancellations.get());
    }

    @Test
    void pendingModelReplacementKeepsRequestedTextureWhileOldVariantRemainsVisible()
            throws Exception {
        var currentId = hash(7);
        var replacementId = hash(8);
        var models = new FakeModelAccess();
        models.contents.put(currentId, content(currentId));
        models.contents.put(replacementId, content(replacementId));
        var currentTarget = playerTarget(currentId, "current");
        models.leases.add(FakeLease.ready(currentId, currentTarget));
        models.leases.add(FakeLease.pending(replacementId));
        var entity = new TestHumanoid(new EntityModelBinding(models));

        entity.updateModelAndTexture(currentId, "current");
        entity.updateModelAndTexture(replacementId, "alternate");

        assertEquals("alternate", models.requests.get(1).requestedTexture());
        assertEquals("alternate", entity.requestedTexture());
        assertSame(currentTarget.playerResources().defaultVariant(), entity.getModelVariant());
    }

    @Test
    void remoteMissUsesStrictPerEntitySwitchDwell() throws Exception {
        var clock = new AtomicLong();
        var firstId = hash(20);
        var secondId = hash(21);
        var models = new FakeModelAccess();
        models.contents.put(firstId, content(firstId));
        models.contents.put(secondId, content(secondId));
        models.offlineResults.add(CompletableFuture.completedFuture(Optional.empty()));
        models.offlineResults.add(CompletableFuture.completedFuture(Optional.empty()));
        models.leases.add(FakeLease.ready(firstId, target()));
        models.leases.add(FakeLease.pending(secondId));
        var binding = new EntityModelBinding(models, clock::get);

        binding.updateModelHash(firstId);
        binding.synchronize("player", "default", null, EntityModelBindingTest::holder);
        assertEquals(1, models.onlineAcquisitions.get());

        clock.set(700);
        binding.updateModelHash(secondId);
        binding.synchronize("player", "default", null, EntityModelBindingTest::holder);
        clock.set(1_400);
        binding.synchronize("player", "default", null, EntityModelBindingTest::holder);
        assertEquals(1, models.onlineAcquisitions.get());

        clock.set(1_401);
        binding.synchronize("player", "default", null, EntityModelBindingTest::holder);
        assertEquals(2, models.onlineAcquisitions.get());
    }

    @Test
    void independentEntitiesDoNotResetEachOthersDemandClock() throws Exception {
        var clock = new AtomicLong();
        var firstId = hash(22);
        var secondId = hash(23);
        var firstModels = missingThenReady(firstId, secondId);
        var secondModels = missingThenReady(firstId, secondId);
        var first = new EntityModelBinding(firstModels, clock::get);
        var second = new EntityModelBinding(secondModels, clock::get);

        first.updateModelHash(firstId);
        first.synchronize("player", "default", null, EntityModelBindingTest::holder);
        second.updateModelHash(firstId);
        second.synchronize("player", "default", null, EntityModelBindingTest::holder);

        clock.set(100);
        first.updateModelHash(secondId);
        first.synchronize("player", "default", null, EntityModelBindingTest::holder);
        clock.set(500);
        second.updateModelHash(secondId);
        second.synchronize("player", "default", null, EntityModelBindingTest::holder);

        clock.set(801);
        first.synchronize("player", "default", null, EntityModelBindingTest::holder);
        second.synchronize("player", "default", null, EntityModelBindingTest::holder);
        assertEquals(2, firstModels.onlineAcquisitions.get());
        assertEquals(1, secondModels.onlineAcquisitions.get());
    }

    @Test
    void sameModelTextureChangeStartsANewStrictDemandInterval() throws Exception {
        var clock = new AtomicLong();
        var modelId = hash(24);
        var models = new FakeModelAccess();
        models.contents.put(modelId, content(modelId));
        models.offlineResults.add(CompletableFuture.completedFuture(Optional.empty()));
        models.offlineResults.add(CompletableFuture.completedFuture(Optional.empty()));
        models.leases.add(FakeLease.ready(modelId, "first", target()));
        models.leases.add(FakeLease.pending(modelId));
        var binding = new EntityModelBinding(models, clock::get);

        binding.updateModelHash(modelId, "player", "first");
        binding.synchronize("player", "first", null, EntityModelBindingTest::holder);
        assertEquals(1, models.onlineAcquisitions.get());

        clock.set(100);
        binding.updateModelHash(modelId, "player", "second");
        binding.synchronize("player", "second", null, EntityModelBindingTest::holder);
        clock.set(800);
        binding.synchronize("player", "second", null, EntityModelBindingTest::holder);
        assertEquals(1, models.onlineAcquisitions.get());

        clock.set(801);
        binding.synchronize("player", "second", null, EntityModelBindingTest::holder);
        assertEquals(2, models.onlineAcquisitions.get());
    }

    private static FakeModelAccess missingThenReady(Hash256 firstId, Hash256 secondId)
            throws Exception {
        var models = new FakeModelAccess();
        models.contents.put(firstId, content(firstId));
        models.contents.put(secondId, content(secondId));
        models.offlineResults.add(CompletableFuture.completedFuture(Optional.empty()));
        models.offlineResults.add(CompletableFuture.completedFuture(Optional.empty()));
        models.leases.add(FakeLease.ready(firstId, target()));
        models.leases.add(FakeLease.pending(secondId));
        return models;
    }

    private static CustomEntity.ResourceHolder holder(ResourceLease lease, boolean fallback) {
        return new CustomEntity.ResourceHolder(lease, fallback);
    }

    private static ModelRenderTarget target() throws InstantiationException {
        return (ModelRenderTarget) UnsafeUtil.getUnsafe().allocateInstance(ModelRenderTarget.class);
    }

    private static ModelRenderTarget playerTarget(Hash256 modelId, String textureName)
            throws InstantiationException {
        var model = (GeoModel) UnsafeUtil.getUnsafe().allocateInstance(GeoModel.class);
        var variants = new FifoHashMap<>(new String[]{textureName},
                new PlayerModelVariant[]{new PlayerModelVariant(model, model)});
        var assets = new CommonAsset(Map.of(), new Object2ReferenceOpenHashMap<>(),
                new Object2ReferenceOpenHashMap<>());
        var resources = new PlayerModelResources(variants, new AnimationStore(),
                new AnimationStore(), new ConditionManager(), new FPArmConditionManager(),
                new Object2ReferenceOpenHashMap<>(), textureName, assets);
        var info = (ModelInfoView) UnsafeUtil.getUnsafe().allocateInstance(ModelInfoView.class);
        return new ModelRenderTarget(modelId, "player", resources, assets, info);
    }

    private static Hash256 hash(int marker) {
        var bytes = new byte[Hash256.SIZE];
        bytes[0] = (byte) marker;
        return new Hash256(bytes);
    }

    private static ModelContent content(Hash256 modelId) {
        return new ModelContent() {
            @Override
            public Hash256 modelId() {
                return modelId;
            }

            @Override
            public ModelRepresentation representation() {
                return null;
            }

            @Override
            public ModelFileView modelFile() {
                return null;
            }

            @Override
            public ChunkDataSource chunks() {
                return null;
            }
        };
    }

    private static final class FakeModelAccess implements EntityModelBinding.ModelAccess {
        private final HashMap<Hash256, ModelContent> contents = new HashMap<>();
        private final ArrayDeque<ResourceLease> leases = new ArrayDeque<>();
        private final ArrayDeque<CompletableFuture<Optional<ResourceLease>>> offlineResults =
                new ArrayDeque<>();
        private final ArrayList<ResourceRequest> requests = new ArrayList<>();
        private final AtomicInteger acquisitions = new AtomicInteger();
        private final AtomicInteger onlineAcquisitions = new AtomicInteger();
        private final AtomicInteger failures = new AtomicInteger();
        private Hash256 defaultId = hash(100);

        @Override
        public ModelContent content(Hash256 modelId) {
            return contents.get(modelId);
        }

        @Override
        public ResourceRequest resourceRequest(Hash256 modelId, String targetId,
                                               String textureName) {
            var request = new ResourceRequest(modelId, targetId, textureName, PROFILE);
            requests.add(request);
            return request;
        }

        @Override
        public ResourceRequest defaultResourceRequest(String targetId) {
            return new ResourceRequest(defaultId, targetId, "", PROFILE);
        }

        @Override
        public ResourceLease getOrStart(ResourceRequest request) {
            acquisitions.incrementAndGet();
            onlineAcquisitions.incrementAndGet();
            return leases.removeFirst();
        }

        @Override
        public CompletableFuture<Optional<ResourceLease>> getOrStartOffline(
                ResourceRequest request) {
            acquisitions.incrementAndGet();
            if (!offlineResults.isEmpty()) {
                return offlineResults.removeFirst();
            }
            return CompletableFuture.completedFuture(Optional.of(leases.removeFirst()));
        }

        @Override
        public void reportActiveModelUse(ResourceRequest request) {
        }

        @Override
        public void reportActiveModelFailure(ModelContent content, boolean fallbackAvailable) {
            failures.incrementAndGet();
        }
    }

    private static final class TestHumanoid extends CustomHumanoidEntity<LivingEntity> {
        private TestHumanoid(EntityModelBinding binding) {
            super(null, false, binding);
        }

        private String requestedTexture() {
            return requestedTextureName();
        }

        @SuppressWarnings("unchecked")
        @Override
        protected HumanoidStateTracker<LivingEntity> createStateTracker(LivingEntity entity) {
            try {
                return (HumanoidStateTracker<LivingEntity>) UnsafeUtil.getUnsafe()
                        .allocateInstance(HumanoidStateTracker.class);
            } catch (InstantiationException error) {
                throw new AssertionError(error);
            }
        }

        @Override
        public int getFrameRateLimit() {
            return 60;
        }

        @Override
        protected ResourceHolder createResourceHolder(ResourceLease lease, boolean fallback) {
            return new ResourceHolder(lease, fallback);
        }

        @Override
        protected void loadGeoModel(@NotNull GeoModel model,
                                    Object2ReferenceMap<String, List<IValue>> eventHandlers) {
        }

        @Override
        protected void onSetupAnimationController() {
        }
    }

    private static final class FakeLease implements ResourceLease {
        private final Hash256 modelId;
        private final String textureName;
        private final AtomicInteger closes = new AtomicInteger();
        private final AtomicInteger pendingCancellations = new AtomicInteger();
        private AcquireResult result;
        private boolean closed;

        private FakeLease(Hash256 modelId, AcquireResult result) {
            this(modelId, null, result);
        }

        private FakeLease(Hash256 modelId, String textureName, AcquireResult result) {
            this.modelId = modelId;
            this.textureName = textureName;
            this.result = result;
        }

        private static FakeLease pending(Hash256 modelId) {
            return new FakeLease(modelId, new AcquireResult.Pending());
        }

        private static FakeLease ready(Hash256 modelId, ModelRenderTarget target) {
            return new FakeLease(modelId, new AcquireResult.Ready(target));
        }

        private static FakeLease ready(Hash256 modelId, String textureName,
                                       ModelRenderTarget target) {
            return new FakeLease(modelId, textureName, new AcquireResult.Ready(target));
        }

        private static FakeLease failed(Hash256 modelId, ResourceFailure.Kind kind) {
            return new FakeLease(modelId, new AcquireResult.Failed(
                    new ResourceFailure(kind, new IllegalStateException("failed"))));
        }

        @Override
        public AcquireResult poll() {
            requireOpen();
            return result;
        }

        @Override
        public boolean isCurrent(ResourceRequest request) {
            requireOpen();
            return modelId.equals(request.modelId())
                    && (textureName == null || textureName.equals(request.requestedTexture()));
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                closes.incrementAndGet();
            }
        }

        @Override
        public void cancelPending() {
            if (!closed && result instanceof AcquireResult.Pending) {
                closed = true;
                pendingCancellations.incrementAndGet();
            }
        }

        private void requireOpen() {
            if (closed) {
                throw new IllegalStateException("closed");
            }
        }
    }
}
