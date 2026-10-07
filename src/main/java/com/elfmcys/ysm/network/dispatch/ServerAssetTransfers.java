package com.elfmcys.ysm.network.dispatch;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.buffer.UniBuffer;
import com.elfmcys.ysm.format.container.AssetContainerConstant;
import com.elfmcys.ysm.format.container.AssetContainerView;
import com.elfmcys.ysm.format.schema.model.ModelFileConstant;
import com.elfmcys.ysm.model.catalog.content.AssetRef;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogRecord;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.model.catalog.source.ModelCatalogSources;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelPath;
import com.elfmcys.ysm.model.domain.ModelRepresentation;
import com.elfmcys.ysm.model.resource.server.ServerChunkRuntime;
import com.elfmcys.ysm.model.session.server.ServerModelSession;
import com.elfmcys.ysm.model.storage.ManagedContainer;
import com.elfmcys.ysm.model.storage.ModelHashing;
import com.elfmcys.ysm.model.storage.PreviewStore;
import com.elfmcys.ysm.network.ProtocolBuffer;
import com.elfmcys.ysm.network.frame.FrameCodec;
import com.elfmcys.ysm.proto.network.ChunkFragment;
import com.elfmcys.ysm.proto.network.IconFragment;
import com.elfmcys.ysm.proto.network.IconMember;
import com.elfmcys.ysm.proto.network.MetadataPrefixRequest;
import com.elfmcys.ysm.proto.network.ModelChunkRequest;
import com.elfmcys.ysm.proto.network.PackCoverFragment;
import com.elfmcys.ysm.proto.network.PresentationDelivery;
import com.elfmcys.ysm.proto.network.PresentationPageRequest;
import com.elfmcys.ysm.proto.network.PreviewFragment;
import com.elfmcys.ysm.proto.network.PreviewMember;
import com.elfmcys.ysm.proto.network.ResourceFailureReason;
import com.elfmcys.ysm.proto.network.ResourceTransferFailure;
import com.elfmcys.ysm.util.ProtoBytes;
import com.elfmcys.ysm.util.ThreadTools;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import us.hebi.quickbuf.ProtoMessage;

/** Owns typed server admission and accepted resource transfers for one exact connection. */
public final class ServerAssetTransfers implements AutoCloseable {
    private static final int MAX_METADATA_MEMBERS = 16_384;
    private static final int MAX_ACTION_MEMBERS = 256;
    private static final long MAX_METADATA_BYTES = 128L * 1024 * 1024;
    private static final long MAX_CHUNK_STORED_BYTES = 128L * 1024 * 1024;
    private static final long MAX_CHUNK_DECODE_BYTES = 512L * 1024 * 1024;
    private static final long MAX_PRESENTATION_STORED_BYTES = 128L * 1024 * 1024;
    private static final long MAX_PRESENTATION_DECODE_BYTES = 256L * 1024 * 1024;
    private static final int MAX_ACTIVE_TRANSFERS = 256;
    private static final int OWNER_EVENTS_PER_TICK = 1_024;

    private final ServerModelSession owner;
    private final ResourceDispatchWorker dispatch;
    private final TransportPort transport;
    private final BooleanSupplier restrictedAuth;
    private final Consumer<ResourceTransferFailure> failureSink;
    private final ServerChunkRuntime serverChunks;
    private final RootResolver roots;
    private final PreviewStore previews;
    private final Executor previewWorkers;
    private final Map<Long, TransferLease> active = new LinkedHashMap<>();
    private final ConcurrentLinkedQueue<OwnerEvent> ownerEvents =
            new ConcurrentLinkedQueue<>();
    private long highWater;
    private boolean closed;

    public ServerAssetTransfers(ServerModelSession owner, ResourceDispatchWorker dispatch,
                                TransportPort transport, BooleanSupplier restrictedAuth,
                                Consumer<ResourceTransferFailure> failureSink,
                                ServerChunkRuntime serverChunks) {
        this(owner, dispatch, transport, restrictedAuth, failureSink, serverChunks,
                ServerAssetTransfers::catalogRoot, null, Runnable::run);
    }

    public ServerAssetTransfers(ServerModelSession owner, ResourceDispatchWorker dispatch,
                                TransportPort transport, BooleanSupplier restrictedAuth,
                                Consumer<ResourceTransferFailure> failureSink,
                                ServerChunkRuntime serverChunks, PreviewStore previews) {
        this(owner, dispatch, transport, restrictedAuth, failureSink, serverChunks,
                ServerAssetTransfers::catalogRoot, Objects.requireNonNull(previews, "previews"),
                ThreadTools::submit);
    }

    ServerAssetTransfers(ServerModelSession owner, ResourceDispatchWorker dispatch,
                         TransportPort transport, BooleanSupplier restrictedAuth,
                         Consumer<ResourceTransferFailure> failureSink,
                         ServerChunkRuntime serverChunks, PreviewStore previews,
                         Executor previewWorkers) {
        this(owner, dispatch, transport, restrictedAuth, failureSink, serverChunks,
                ServerAssetTransfers::catalogRoot, Objects.requireNonNull(previews, "previews"),
                previewWorkers);
    }

    ServerAssetTransfers(ServerModelSession owner, ResourceDispatchWorker dispatch,
                         TransportPort transport, BooleanSupplier restrictedAuth,
                         Consumer<ResourceTransferFailure> failureSink,
                         ServerChunkRuntime serverChunks, RootResolver roots) {
        this(owner, dispatch, transport, restrictedAuth, failureSink, serverChunks, roots,
                null, Runnable::run);
    }

    ServerAssetTransfers(ServerModelSession owner, ResourceDispatchWorker dispatch,
                         TransportPort transport, BooleanSupplier restrictedAuth,
                         Consumer<ResourceTransferFailure> failureSink,
                         ServerChunkRuntime serverChunks, RootResolver roots,
                         PreviewStore previews, Executor previewWorkers) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.dispatch = Objects.requireNonNull(dispatch, "dispatch");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.restrictedAuth = Objects.requireNonNull(restrictedAuth, "restrictedAuth");
        this.failureSink = Objects.requireNonNull(failureSink, "failureSink");
        this.serverChunks = Objects.requireNonNull(serverChunks, "serverChunks");
        this.roots = Objects.requireNonNull(roots, "roots");
        this.previews = previews;
        this.previewWorkers = Objects.requireNonNull(previewWorkers, "previewWorkers");
    }

    /** Returns false only for an intrinsic request violation that must retire the session. */
    public boolean accept(MetadataPrefixRequest request) {
        var decoded = decodeMetadata(request);
        if (decoded == null) {
            return false;
        }
        var begin = begin(decoded.transferId);
        if (begin == Begin.BUSY) {
            fail(decoded.transferId, busy());
            return true;
        }
        if (begin != Begin.ACCEPTED) {
            return begin == Begin.CLOSED;
        }
        if (!readyForDistribution()) {
            fail(decoded.transferId, invalidRequest());
            return true;
        }

        var plans = new ArrayList<MetadataPlan>(decoded.containerIds.size());
        long totalBytes = 0;
        try {
            for (var containerId : decoded.containerIds) {
                var record = owner.findPublished(containerId).orElse(null);
                if (record == null) {
                    fail(decoded.transferId, notFound());
                    return true;
                }
                var representation = record.binding().content().representation();
                var size = representation.metadataPrefixSize();
                if (size <= 0) {
                    fail(decoded.transferId, unavailable());
                    return true;
                }
                totalBytes = Math.addExact(totalBytes, size);
                if (totalBytes > MAX_METADATA_BYTES) {
                    fail(decoded.transferId, itemTooLarge());
                    return true;
                }
                plans.add(new MetadataPlan(containerId, representation));
            }
        } catch (ArithmeticException tooLarge) {
            fail(decoded.transferId, itemTooLarge());
            return true;
        } catch (RuntimeException unavailable) {
            fail(decoded.transferId, unavailable());
            return true;
        }

        var lease = register(decoded.transferId);
        if (lease == null) {
            if (!isClosed()) {
                fail(decoded.transferId, busy());
            }
            return true;
        }
        var sources = new ArrayList<MetadataPrefixPacket.Source>(plans.size());
        var owned = new ArrayList<UniBuffer>(plans.size());
        try {
            for (var plan : plans) {
                var prefix = plan.representation.metadataPrefix().orElseThrow();
                owned.add(prefix);
                sources.add(new MetadataPrefixPacket.Source(plan.containerId.bytes(), prefix));
            }
            var packet = new MetadataPrefixPacket(decoded.transferId, sources,
                    lease::packetClosed, lease::productionFailed);
            if (submit(lease, List.of(packet)) == SubmitResult.REJECTED) {
                fail(decoded.transferId, busy());
            }
            return true;
        } catch (RuntimeException unavailable) {
            if (retireAcquiring(lease, LeaseState.FAILED)) {
                fail(decoded.transferId, unavailable());
            }
            return true;
        } finally {
            closeBuffers(owned);
        }
    }

    /** Returns false only for an intrinsic request violation that must retire the session. */
    public boolean accept(ModelChunkRequest request) {
        var decoded = decodeModel(request);
        if (decoded == null) {
            return false;
        }
        var begin = begin(decoded.transferId);
        if (begin == Begin.BUSY) {
            fail(decoded.transferId, busy());
            return true;
        }
        if (begin != Begin.ACCEPTED) {
            return begin == Begin.CLOSED;
        }
        if (!readyForDistribution()) {
            fail(decoded.transferId, invalidRequest());
            return true;
        }

        var restricted = restrictedAuth.getAsBoolean();
        var record = owner.findModel(decoded.modelId).orElse(null);
        if (record == null) {
            fail(decoded.transferId, restricted ? unauthorized() : notFound());
            return true;
        }
        if (!owner.canRequest(decoded.modelId, AssetRef.Kind.CHUNK, restricted)) {
            fail(decoded.transferId, unauthorized());
            return true;
        }
        var content = managed(record);
        if (content == null || !content.representation().containerId().equals(decoded.containerId)) {
            fail(decoded.transferId, invalidRequest());
            return true;
        }
        var assetView = content.modelFile().getFileView().getAssetView();
        var plans = new ArrayList<ChunkPlan>(decoded.chunks.size());
        try {
            for (var requested : decoded.chunks) {
                var chunk = assetView.getChunkInfo(requested.name);
                if (!matches(requested, chunk)) {
                    fail(decoded.transferId, invalidRequest());
                    return true;
                }
                plans.add(new ChunkPlan(content, chunk,
                        (offset, last) -> ChunkFragment.newBuilder()
                                .setDataTransferId(decoded.transferId)
                                .setName(requested.name).setOffset(offset)
                                .setFinalFragment(last).build()));
            }
            var lease = register(decoded.transferId);
            if (lease == null) {
                if (!isClosed()) {
                    fail(decoded.transferId, busy());
                }
                return true;
            }
            acquireChunks(lease, plans);
            return true;
        } catch (RuntimeException failure) {
            fail(decoded.transferId, unavailable());
            return true;
        }
    }

    /** Returns false only for an intrinsic request violation that must retire the session. */
    public boolean accept(PresentationPageRequest request) {
        var decoded = decodePresentation(request);
        if (decoded == null) {
            return false;
        }
        var begin = begin(decoded.transferId);
        if (begin == Begin.BUSY) {
            fail(decoded.transferId, busy());
            return true;
        }
        if (begin != Begin.ACCEPTED) {
            return begin == Begin.CLOSED;
        }
        if (!readyForDistribution()) {
            fail(decoded.transferId, invalidRequest());
            return true;
        }

        var plans = new ArrayList<PresentationPlan>(decoded.members.size());
        var restricted = restrictedAuth.getAsBoolean();
        try {
            for (var member : decoded.members) {
                var plan = presentationPlan(member, restricted);
                if (plan == null) {
                    fail(decoded.transferId, member.failure);
                    return true;
                }
                plans.add(plan);
            }
        } catch (RuntimeException failure) {
            fail(decoded.transferId, unavailable());
            return true;
        }

        final long knownBytes;
        try {
            knownBytes = plans.stream().mapToLong(plan -> plan.content != null
                    ? plan.chunk.size()
                    : plan.coverRootKind != null ? plan.member.storedSize : 0L).reduce(
                    0L, Math::addExact);
        } catch (ArithmeticException tooLarge) {
            fail(decoded.transferId, itemTooLarge());
            return true;
        }
        if (knownBytes > MAX_PRESENTATION_STORED_BYTES) {
            fail(decoded.transferId, itemTooLarge());
            return true;
        }

        var lease = register(decoded.transferId);
        if (lease == null) {
            if (!isClosed()) {
                fail(decoded.transferId, busy());
            }
            return true;
        }
        if (plans.stream().anyMatch(plan -> plan.content != null
                || plan.previewLoad != null)) {
            acquirePresentation(lease, plans, knownBytes);
            return true;
        }

        var packets = new ArrayList<CancellablePacket>(plans.size());
        try {
            for (var plan : plans) {
                var packet = presentationPacket(decoded.transferId, plan, lease);
                if (packet == null) {
                    closePackets(packets);
                    if (retireAcquiring(lease, LeaseState.FAILED)) {
                        fail(decoded.transferId, plan.member.failure);
                    }
                    return true;
                }
                packets.add(packet);
            }
            if (submit(lease, packets) == SubmitResult.REJECTED) {
                fail(decoded.transferId, busy());
            }
            return true;
        } catch (RuntimeException failure) {
            closePackets(packets);
            if (retireAcquiring(lease, LeaseState.FAILED)) {
                fail(decoded.transferId, unavailable());
            }
            return true;
        }
    }

    /** Returns false only when the cancel itself is structurally invalid. */
    public boolean cancel(long transferId) {
        if (transferId == 0) {
            return false;
        }
        final TransferLease lease;
        synchronized (this) {
            if (closed) {
                return true;
            }
            lease = active.remove(transferId);
            if (lease == null || lease.state != LeaseState.ACTIVE
                    && lease.state != LeaseState.ACQUIRING) {
                return true;
            }
            lease.state = LeaseState.CANCELLED;
        }
        releaseAcquisitions(lease.acquisitions);
        cancelPreviewAcquisitions(lease.previewAcquisitions);
        lease.packets.forEach(CancellablePacket::cancel);
        return true;
    }

    /** Commits runtime and dispatch completion facts on the server-session owner tick. */
    public void tick() {
        for (var disposed = 0; disposed < OWNER_EVENTS_PER_TICK; disposed++) {
            var event = ownerEvents.poll();
            if (event == null) {
                return;
            }
            if (event instanceof ChunkAcquisitionComplete completed) {
                completeChunkAcquisition(completed.lease, completed.plans,
                        completed.failure);
            } else if (event instanceof PresentationAcquisitionComplete completed) {
                completePresentationAcquisition(completed.lease, completed.plans,
                        completed.failure);
            } else if (event instanceof PacketClosed closed) {
                packetClosed(closed.lease);
            } else if (event instanceof ProductionFailed failed) {
                productionFailed(failed.lease, failed.failure);
            }
        }
    }

    @Override
    public void close() {
        final List<TransferLease> abandoned;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            abandoned = List.copyOf(active.values());
            active.clear();
            abandoned.forEach(lease -> lease.state = LeaseState.CANCELLED);
        }
        abandoned.forEach(lease -> {
            releaseAcquisitions(lease.acquisitions);
            cancelPreviewAcquisitions(lease.previewAcquisitions);
            lease.packets.forEach(CancellablePacket::cancel);
        });
        ownerEvents.clear();
    }

    synchronized int activeCount() {
        return active.size();
    }

    private PresentationPlan presentationPlan(PresentationMember member, boolean restricted) {
        if (member.kind == PresentationKind.PACK_COVER) {
            return packCoverPlan(member);
        }
        var record = owner.findModel(member.modelId).orElse(null);
        if (record == null) {
            member.failure = restricted ? unauthorized() : notFound();
            return null;
        }
        var assetKind = member.kind == PresentationKind.PREVIEW
                ? AssetRef.Kind.PREVIEW : AssetRef.Kind.ICON;
        if (!owner.canRequest(member.modelId, assetKind, restricted)) {
            member.failure = unauthorized();
            return null;
        }
        var content = managed(record);
        if (content == null || !content.representation().containerId().equals(member.containerId)) {
            member.failure = invalidRequest();
            return null;
        }
        if (member.kind == PresentationKind.PREVIEW) {
            var chunk = content.modelFile().getFileView().getAssetView().getChunkInfo(
                    ModelFileConstant.THUMB_BUTTON_CHUNK_NAME);
            var declared = content.modelFile().getThumbnailPreviewSource()
                    != com.elfmcys.ysm.proto.mixel.manifest.info.PreviewSource
                    .PREVIEW_SOURCE_UNSPECIFIED;
            if (declared && chunk != null) {
                if (chunk.size() <= 0 || chunk.size() > PreviewStore.MAX_STORED_BYTES) {
                    member.failure = itemTooLarge();
                    return null;
                }
                return PresentationPlan.model(member, content, chunk);
            }
            return PresentationPlan.preview(member, new PreviewLoad(member.containerId));
        }
        var expected = record.entry().presentation().icon().orElse(null);
        if (!matches(member, expected)) {
            member.failure = invalidRequest();
            return null;
        }
        var chunk = content.modelFile().getFileView().getAssetView().getChunkInfo(member.name);
        if (!matches(member, chunk)) {
            member.failure = invalidRequest();
            return null;
        }
        return PresentationPlan.model(member, content, chunk);
    }

    private PresentationPlan packCoverPlan(PresentationMember member) {
        var pack = owner.findPack(member.hierarchy).orElse(null);
        if (pack == null) {
            member.failure = notFound();
            return null;
        }
        if (!member.hash.equals(pack.coverHash()) || member.storedSize != pack.coverSize()
                || member.decodedSize != pack.coverSize()
                || !member.encoding.equals(pack.coverFormat()) || pack.coverSize() <= 0) {
            member.failure = invalidRequest();
            return null;
        }
        return PresentationPlan.cover(member, pack.rootKind());
    }

    private CancellablePacket presentationPacket(long transferId, PresentationPlan plan,
                                                   TransferLease lease) {
        if (plan.coverRootKind != null) {
            return packCoverPacket(transferId, plan, lease);
        }
        if (plan.previewLoad != null) {
            var bytes = plan.previewLoad.bytes;
            if (bytes == null) return unavailablePacket(transferId, plan.member, lease);
            return RangePacket.bytes(bytes,
                    (offset, last) -> presentationFragment(transferId, plan.member,
                            offset, last, com.elfmcys.ysm.proto.network.PresentationDelivery
                                    .PRESENTATION_DELIVERY_DATA),
                    lease::packetClosed, lease::productionFailed);
        }
        if (plan.member.kind == PresentationKind.PREVIEW) {
            return unavailablePacket(transferId, plan.member, lease);
        }
        throw new IllegalArgumentException(
                "Model presentation requires a verified runtime chunk lease");
    }

    private CancellablePacket presentationPacket(
            long transferId, PresentationPlan plan,
            ServerChunkRuntime.ChunkLease source, TransferLease lease) {
        return RangePacket.chunk(source,
                (offset, last) -> presentationFragment(transferId, plan.member, offset, last,
                        com.elfmcys.ysm.proto.network.PresentationDelivery
                                .PRESENTATION_DELIVERY_DATA),
                lease::packetClosed, lease::productionFailed);
    }

    private CancellablePacket packCoverPacket(long transferId, PresentationPlan plan,
                                                TransferLease lease) {
        var member = plan.member;
        final Path path;
        try {
            path = packCoverPath(plan.coverRootKind, member.hierarchy);
            if (path == null) {
                return unavailablePacket(transferId, member, lease);
            }
            if (Files.size(path) != member.storedSize
                    || !ModelHashing.blake3(path).equals(member.hash)) {
                member.failure = unavailable();
                return null;
            }
        } catch (IOException | SecurityException failure) {
            return unavailablePacket(transferId, member, lease);
        }
        return filePacket(path, path, 0, member.storedSize,
                (offset, last) -> presentationFragment(transferId, member, offset, last,
                        com.elfmcys.ysm.proto.network.PresentationDelivery
                                .PRESENTATION_DELIVERY_DATA), lease);
    }

    private CancellablePacket unavailablePacket(long transferId, PresentationMember member,
                                                  TransferLease lease) {
        return new TypedMessagePacket(presentationFragment(transferId, member, 0, true,
                PresentationDelivery.PRESENTATION_DELIVERY_UNAVAILABLE),
                lease::packetClosed, lease::productionFailed);
    }

    private static ProtoMessage<?> presentationFragment(
            long transferId, PresentationMember member, long offset, boolean last,
            PresentationDelivery delivery) {
        return switch (member.kind) {
            case PREVIEW -> PreviewFragment.newBuilder()
                    .setDataTransferId(transferId).setSlot(member.slot).setOffset(offset)
                    .setFinalFragment(last).setDelivery(delivery).build();
            case ICON -> IconFragment.newBuilder()
                    .setDataTransferId(transferId).setSlot(member.slot).setOffset(offset)
                    .setFinalFragment(last).setDelivery(delivery).build();
            case PACK_COVER -> PackCoverFragment.newBuilder()
                    .setDataTransferId(transferId).setSlot(member.slot).setOffset(offset)
                    .setFinalFragment(last).setDelivery(delivery).build();
        };
    }

    private void acquireChunks(TransferLease lease, List<ChunkPlan> plans) {
        var acquisitions = new ArrayList<CompletableFuture<ServerChunkRuntime.ChunkLease>>(
                plans.size());
        var failed = false;
        synchronized (this) {
            if (active.get(lease.transferId) != lease
                    || lease.state != LeaseState.ACQUIRING) {
                return;
            }
            try {
                for (var plan : plans) {
                    acquisitions.add(serverChunks.acquire(plan.content, plan.chunk));
                }
                lease.acquisitions = List.copyOf(acquisitions);
            } catch (RuntimeException failure) {
                active.remove(lease.transferId, lease);
                lease.state = LeaseState.FAILED;
                failed = true;
            }
        }
        if (failed) {
            releaseAcquisitions(acquisitions);
            fail(lease.transferId, unavailable());
            return;
        }
        CompletableFuture.allOf(lease.acquisitions.toArray(CompletableFuture[]::new))
                .whenComplete((ignored, failure) ->
                        enqueueOwnerEvent(new ChunkAcquisitionComplete(
                                lease, plans, failure)));
    }

    private void acquirePresentation(TransferLease lease, List<PresentationPlan> plans,
                                     long knownBytes) {
        var acquisitions = new ArrayList<CompletableFuture<ServerChunkRuntime.ChunkLease>>();
        var previewAcquisitions = new ArrayList<CompletableFuture<byte[]>>();
        var failed = false;
        synchronized (this) {
            if (active.get(lease.transferId) != lease
                    || lease.state != LeaseState.ACQUIRING) {
                return;
            }
            try {
                var previewBytes = new AtomicLong(knownBytes);
                CompletableFuture<Void> previousPreview = CompletableFuture.completedFuture(null);
                for (var plan : plans) {
                    if (plan.content != null) {
                        acquisitions.add(serverChunks.acquire(plan.content, plan.chunk));
                    }
                    if (plan.previewLoad != null) {
                        var load = plan.previewLoad;
                        var acquisition = previousPreview.thenApplyAsync(ignored -> {
                            var bytes = previews == null ? null
                                    : previews.load(load.containerId)
                                    .map(PreviewStore.EncodedPreview::bytes).orElse(null);
                            if (bytes != null && previewBytes.addAndGet(bytes.length)
                                    > MAX_PRESENTATION_STORED_BYTES) {
                                throw new IllegalStateException(
                                        "Presentation preview bytes exceed their aggregate bound");
                            }
                            load.bytes = bytes;
                            return bytes;
                        }, previewWorkers);
                        previewAcquisitions.add(acquisition);
                        previousPreview = acquisition.thenApply(ignored -> null);
                    }
                }
                lease.acquisitions = List.copyOf(acquisitions);
                lease.previewAcquisitions = List.copyOf(previewAcquisitions);
            } catch (RuntimeException failure) {
                active.remove(lease.transferId, lease);
                lease.state = LeaseState.FAILED;
                failed = true;
            }
        }
        if (failed) {
            releaseAcquisitions(acquisitions);
            cancelPreviewAcquisitions(previewAcquisitions);
            fail(lease.transferId, unavailable());
            return;
        }
        var all = new ArrayList<CompletableFuture<?>>(lease.acquisitions);
        all.addAll(lease.previewAcquisitions);
        CompletableFuture.allOf(all.toArray(CompletableFuture[]::new))
                .whenComplete((ignored, failure) ->
                        enqueueOwnerEvent(new PresentationAcquisitionComplete(
                                lease, plans, failure)));
    }

    private void completePresentationAcquisition(
            TransferLease lease, List<PresentationPlan> plans, Throwable failure) {
        List<CompletableFuture<ServerChunkRuntime.ChunkLease>> acquisitionsToRelease = List.of();
        List<CompletableFuture<byte[]>> previewsToCancel = List.of();
        List<ServerChunkRuntime.ChunkLease> sources = null;
        var notifyUnavailable = false;
        synchronized (this) {
            if (active.get(lease.transferId) != lease
                    || lease.state != LeaseState.ACQUIRING) {
                acquisitionsToRelease = lease.acquisitions;
                previewsToCancel = lease.previewAcquisitions;
                lease.acquisitions = List.of();
                lease.previewAcquisitions = List.of();
            } else if (failure != null) {
                active.remove(lease.transferId, lease);
                lease.state = LeaseState.FAILED;
                acquisitionsToRelease = lease.acquisitions;
                previewsToCancel = lease.previewAcquisitions;
                lease.acquisitions = List.of();
                lease.previewAcquisitions = List.of();
                notifyUnavailable = true;
            } else {
                var acquired = new ArrayList<ServerChunkRuntime.ChunkLease>(
                        lease.acquisitions.size());
                try {
                    for (var acquisition : lease.acquisitions) {
                        acquired.add(Objects.requireNonNull(acquisition.join(),
                                "presentation acquisition returned null"));
                    }
                    lease.acquisitions = List.of();
                    lease.previewAcquisitions = List.of();
                    sources = List.copyOf(acquired);
                } catch (RuntimeException sourceFailure) {
                    active.remove(lease.transferId, lease);
                    lease.state = LeaseState.FAILED;
                    acquisitionsToRelease = lease.acquisitions;
                    previewsToCancel = lease.previewAcquisitions;
                    lease.acquisitions = List.of();
                    lease.previewAcquisitions = List.of();
                    notifyUnavailable = true;
                }
            }
        }
        releaseAcquisitions(acquisitionsToRelease);
        cancelPreviewAcquisitions(previewsToCancel);
        if (notifyUnavailable) {
            fail(lease.transferId, unavailable());
            return;
        }
        if (sources == null) {
            return;
        }

        var packets = new ArrayList<CancellablePacket>(plans.size());
        var sourceIndex = 0;
        try {
            for (var plan : plans) {
                var packet = plan.content == null
                        ? presentationPacket(lease.transferId, plan, lease)
                        : presentationPacket(lease.transferId, plan,
                        sources.get(sourceIndex), lease);
                if (packet == null) {
                    throw new IllegalStateException("Presentation source became unavailable");
                }
                packets.add(packet);
                if (plan.content != null) {
                    sourceIndex++;
                }
            }
        } catch (RuntimeException constructionFailure) {
            closePackets(packets);
            closeChunkLeases(sources.subList(sourceIndex, sources.size()));
            if (retireAcquiring(lease, LeaseState.FAILED)) {
                fail(lease.transferId, unavailable());
            }
            return;
        }
        if (submit(lease, packets) == SubmitResult.REJECTED) {
            fail(lease.transferId, busy());
        }
    }

    private void completeChunkAcquisition(TransferLease lease, List<ChunkPlan> plans,
                                          Throwable failure) {
        List<CompletableFuture<ServerChunkRuntime.ChunkLease>> acquisitionsToRelease = List.of();
        List<ServerChunkRuntime.ChunkLease> sources = null;
        var notifyUnavailable = false;
        synchronized (this) {
            if (active.get(lease.transferId) != lease
                    || lease.state != LeaseState.ACQUIRING) {
                acquisitionsToRelease = lease.acquisitions;
                lease.acquisitions = List.of();
            } else if (failure != null) {
                active.remove(lease.transferId, lease);
                lease.state = LeaseState.FAILED;
                acquisitionsToRelease = lease.acquisitions;
                lease.acquisitions = List.of();
                notifyUnavailable = true;
            } else {
                var acquired = new ArrayList<ServerChunkRuntime.ChunkLease>(plans.size());
                try {
                    for (var acquisition : lease.acquisitions) {
                        acquired.add(Objects.requireNonNull(acquisition.join(),
                                "chunk acquisition returned null"));
                    }
                    lease.acquisitions = List.of();
                    sources = List.copyOf(acquired);
                } catch (RuntimeException sourceFailure) {
                    active.remove(lease.transferId, lease);
                    lease.state = LeaseState.FAILED;
                    acquisitionsToRelease = lease.acquisitions;
                    lease.acquisitions = List.of();
                    notifyUnavailable = true;
                }
            }
        }
        releaseAcquisitions(acquisitionsToRelease);
        if (notifyUnavailable) {
            fail(lease.transferId, unavailable());
            return;
        }
        if (sources == null) {
            return;
        }

        var packets = new ArrayList<CancellablePacket>(plans.size());
        try {
            for (var index = 0; index < plans.size(); index++) {
                var plan = plans.get(index);
                packets.add(RangePacket.chunk(sources.get(index), plan.fragments,
                        lease::packetClosed, lease::productionFailed));
            }
        } catch (RuntimeException constructionFailure) {
            closePackets(packets);
            closeChunkLeases(sources.subList(packets.size(), sources.size()));
            if (retireAcquiring(lease, LeaseState.FAILED)) {
                fail(lease.transferId, unavailable());
            }
            return;
        }
        if (submit(lease, packets) == SubmitResult.REJECTED) {
            fail(lease.transferId, busy());
        }
    }

    private static void releaseAcquisitions(
            List<CompletableFuture<ServerChunkRuntime.ChunkLease>> acquisitions) {
        for (var acquisition : acquisitions) {
            if (!acquisition.isDone() && acquisition.cancel(false)) {
                continue;
            }
            try {
                var value = acquisition.getNow(null);
                if (value != null) {
                    value.close();
                }
            } catch (RuntimeException ignored) {
                // Failed source acquisition owns no lease.
            }
        }
    }

    private static void cancelPreviewAcquisitions(
            List<CompletableFuture<byte[]>> acquisitions) {
        acquisitions.forEach(action -> action.cancel(false));
    }

    private CancellablePacket filePacket(Object sourceLease, Path path, long offset, int size,
                                         RangePacket.FragmentFactory fragments,
                                         TransferLease lease) {
        return RangePacket.file(sourceLease, path, offset, size, fragments,
                lease::packetClosed, lease::productionFailed);
    }

    private SubmitResult submit(TransferLease lease,
                                List<? extends CancellablePacket> packets) {
        List<CancellablePacket> owned = List.copyOf(packets);
        final SubmitResult result;
        synchronized (this) {
            if (active.get(lease.transferId) != lease
                    || lease.state != LeaseState.ACQUIRING) {
                result = SubmitResult.RETIRED;
            } else {
                lease.packets = owned;
                lease.remaining = owned.size();
                lease.state = LeaseState.ACTIVE;
                boolean accepted;
                try {
                    accepted = dispatch.enqueue(owner, transport, owned);
                } catch (RuntimeException failure) {
                    accepted = false;
                }
                if (accepted) {
                    result = SubmitResult.ACCEPTED;
                } else {
                    active.remove(lease.transferId, lease);
                    lease.state = LeaseState.CANCELLED;
                    result = SubmitResult.REJECTED;
                }
            }
        }
        if (result != SubmitResult.ACCEPTED) {
            closePackets(owned);
        }
        return result;
    }

    private void packetClosed(TransferLease lease) {
        synchronized (this) {
            if (lease.state != LeaseState.ACTIVE
                    || active.get(lease.transferId) != lease) {
                return;
            }
            if (--lease.remaining == 0) {
                lease.state = LeaseState.COMPLETED;
                active.remove(lease.transferId, lease);
            }
        }
    }

    private void productionFailed(TransferLease lease, RuntimeException failure) {
        synchronized (this) {
            if (closed || lease.state == LeaseState.CANCELLED
                    || lease.state == LeaseState.FAILED
                    || lease.state == LeaseState.ACQUIRING
                    || lease.state == LeaseState.ACTIVE
                    && active.get(lease.transferId) != lease) {
                return;
            }
            lease.state = LeaseState.FAILED;
            active.remove(lease.transferId, lease);
        }
        lease.packets.forEach(CancellablePacket::cancel);
        YesSteveModel.LOGGER.warn("Remote asset production failed for transfer={}",
                lease.transferId, failure);
        fail(lease.transferId, unavailable());
    }

    private synchronized Begin begin(long transferId) {
        if (closed) {
            return Begin.CLOSED;
        }
        if (transferId == 0 || Long.compareUnsigned(transferId, highWater) <= 0) {
            return Begin.INVALID;
        }
        highWater = transferId;
        if (active.size() >= MAX_ACTIVE_TRANSFERS) {
            return Begin.BUSY;
        }
        return Begin.ACCEPTED;
    }

    private synchronized TransferLease register(long transferId) {
        if (closed || active.size() >= MAX_ACTIVE_TRANSFERS) {
            return null;
        }
        var lease = new TransferLease(transferId);
        if (active.putIfAbsent(transferId, lease) != null) {
            throw new IllegalStateException("Distribution transfer id was already active");
        }
        return lease;
    }

    private synchronized boolean isClosed() {
        return closed;
    }

    private synchronized boolean retireAcquiring(TransferLease lease, LeaseState terminal) {
        if (active.get(lease.transferId) != lease
                || lease.state != LeaseState.ACQUIRING) {
            return false;
        }
        active.remove(lease.transferId, lease);
        lease.state = terminal;
        return true;
    }

    private synchronized void enqueueOwnerEvent(OwnerEvent event) {
        if (!closed) {
            ownerEvents.add(Objects.requireNonNull(event, "event"));
        }
    }

    private boolean readyForDistribution() {
        return owner.active() && owner.hasPublishedCatalog();
    }

    private void fail(long transferId, ResourceFailureReason reason) {
        var message = ResourceTransferFailure.newBuilder()
                .setDataTransferId(transferId).setReason(reason);
        try {
            failureSink.accept(message.build());
        } catch (RuntimeException failure) {
            YesSteveModel.LOGGER.warn("Failed to send resource transfer failure id={}",
                    Long.toUnsignedString(transferId), failure);
        }
    }

    private static MetadataRequest decodeMetadata(
            MetadataPrefixRequest request) {
        Objects.requireNonNull(request, "request");
        var count = request.containerIds().size();
        if (count < 1 || count > MAX_METADATA_MEMBERS || !fitsFrame(request)) {
            return null;
        }
        var ids = new ArrayList<Hash256>(count);
        Hash256 previous = null;
        try {
            for (var value : request.containerIds()) {
                var current = hash(value);
                if (previous != null && previous.compareTo(current) >= 0) {
                    return null;
                }
                ids.add(current);
                previous = current;
            }
        } catch (IllegalArgumentException invalid) {
            return null;
        }
        return request.dataTransferId() == 0
                ? null : new MetadataRequest(request.dataTransferId(), List.copyOf(ids));
    }

    private static ModelRequest decodeModel(ModelChunkRequest request) {
        Objects.requireNonNull(request, "request");
        var count = request.chunks().size();
        if (count < 1 || count > MAX_ACTION_MEMBERS || !fitsFrame(request)) {
            return null;
        }
        try {
            var modelId = hash(request.modelId());
            var containerId = hash(request.containerId());
            var chunks = new ArrayList<ChunkDescriptor>(count);
            String previous = null;
            long stored = 0;
            long decoded = 0;
            for (var chunk : request.chunks()) {
                var name = normalizedName(chunk.name());
                if (previous != null && compareUtf8(previous, name) >= 0) {
                    return null;
                }
                var storedSize = boundedSize(chunk.storedSize(),
                        AssetContainerConstant.MAX_FILE_SIZE, false);
                var decodedSize = boundedSize(chunk.decodedSize(),
                        MAX_CHUNK_DECODE_BYTES, true);
                stored = Math.addExact(stored, storedSize);
                decoded = Math.addExact(decoded, decodedSize);
                chunks.add(new ChunkDescriptor(name,
                        hash(chunk.expectedHash()),
                        storedSize, decodedSize, Objects.requireNonNull(chunk.encoding())));
                previous = name;
            }
            if (stored > MAX_CHUNK_STORED_BYTES || decoded > MAX_CHUNK_DECODE_BYTES
                    || request.dataTransferId() == 0) {
                return null;
            }
            return new ModelRequest(request.dataTransferId(), modelId, containerId,
                    List.copyOf(chunks));
        } catch (ArithmeticException | IllegalArgumentException invalid) {
            return null;
        }
    }

    private static PresentationRequest decodePresentation(
            PresentationPageRequest request) {
        Objects.requireNonNull(request, "request");
        var count = request.previews().size() + request.icons().size()
                + request.packCovers().size();
        if (count < 1 || count > MAX_ACTION_MEMBERS || !fitsFrame(request)) {
            return null;
        }
        try {
            var members = new ArrayList<PresentationMember>(count);
            decodePreviews(request.previews(), members);
            decodeIcons(request.icons(), members);
            var previous = -1;
            for (var cover : request.packCovers()) {
                if (cover.slot() < 0 || cover.slot() <= previous) {
                    return null;
                }
                var hierarchy = normalizedHierarchy(cover.hierarchyPath());
                var stored = boundedSize(cover.storedSize(),
                        AssetContainerConstant.MAX_FILE_SIZE, false);
                var decoded = boundedSize(cover.decodedSize(),
                        MAX_PRESENTATION_DECODE_BYTES, true);
                members.add(PresentationMember.cover(cover.slot(), hierarchy,
                        hash(cover.expectedHash()),
                        stored, decoded, nonBlank(cover.encoding())));
                previous = cover.slot();
            }
            long stored = 0;
            long decoded = 0;
            for (var member : members) {
                stored = Math.addExact(stored, member.storedSize);
                decoded = Math.addExact(decoded, member.decodedSize);
            }
            if (stored > MAX_PRESENTATION_STORED_BYTES
                    || decoded > MAX_PRESENTATION_DECODE_BYTES
                    || request.dataTransferId() == 0) {
                return null;
            }
            return new PresentationRequest(request.dataTransferId(), List.copyOf(members));
        } catch (ArithmeticException | IllegalArgumentException invalid) {
            return null;
        }
    }

    private static void decodePreviews(Iterable<PreviewMember> values,
                                       List<PresentationMember> output) {
        var previous = -1;
        for (var value : values) {
            if (value.slot() < 0 || value.slot() <= previous) {
                throw new IllegalArgumentException("Presentation slots are not canonical");
            }
            output.add(PresentationMember.model(PresentationKind.PREVIEW, value.slot(),
                    hash(value.modelId()),
                    hash(value.containerId()),
                    "", new Hash256(new byte[Hash256.SIZE]), 0, 0, ""));
            previous = value.slot();
        }
    }

    private static void decodeIcons(Iterable<IconMember> values,
                                    List<PresentationMember> output) {
        var previous = -1;
        for (var value : values) {
            if (value.slot() < 0 || value.slot() <= previous) {
                throw new IllegalArgumentException("Presentation slots are not canonical");
            }
            output.add(PresentationMember.model(PresentationKind.ICON, value.slot(),
                    hash(value.modelId()),
                    hash(value.containerId()),
                    normalizedName(value.name()),
                    hash(value.expectedHash()),
                    boundedSize(value.storedSize(), AssetContainerConstant.MAX_FILE_SIZE, false),
                    boundedSize(value.decodedSize(), MAX_PRESENTATION_DECODE_BYTES, true),
                    nonBlank(value.encoding())));
            previous = value.slot();
        }
    }

    private static boolean fitsFrame(ProtoMessage<?> request) {
        try (var protobuf = ProtocolBuffer.serialize(request);
             var attachment = ArrayBuffer.allocate(0);
             var frame = FrameCodec.encode(0, protobuf, attachment)) {
            return frame.size() > 0;
        } catch (IllegalArgumentException oversized) {
            return false;
        }
    }

    private static Hash256 hash(ByteBuffer bytes) {
        return new Hash256(ProtoBytes.copy(bytes));
    }

    private static int boundedSize(long value, long maximum, boolean allowZero) {
        if (value < 0 || !allowZero && value == 0 || value > maximum
                || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Resource size is outside its typed bound");
        }
        return (int) value;
    }

    private static String normalizedName(String value) {
        if (value == null || value.isBlank() || !value.equals(value.trim())) {
            throw new IllegalArgumentException("Resource name is not normalized");
        }
        var normalized = new ModelPath(value).value();
        if (!normalized.equals(value)) {
            throw new IllegalArgumentException("Resource name is not canonical");
        }
        return value;
    }

    private static String normalizedHierarchy(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        if (!value.endsWith("/")) {
            throw new IllegalArgumentException("Pack hierarchy is not normalized");
        }
        var normalized = new ModelPath(value.substring(0, value.length() - 1)).value() + "/";
        if (!normalized.equals(value)) {
            throw new IllegalArgumentException("Pack hierarchy is not canonical");
        }
        return value;
    }

    private static String nonBlank(String value) {
        if (value == null || value.isBlank() || !value.equals(value.trim())) {
            throw new IllegalArgumentException("Encoding is not normalized");
        }
        return value;
    }

    private static int compareUtf8(String left, String right) {
        return Arrays.compareUnsigned(left.getBytes(StandardCharsets.UTF_8),
                right.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean matches(ChunkDescriptor requested,
                                   AssetContainerView.ChunkInfo chunk) {
        return chunk != null && requested.name.equals(chunk.type())
                && requested.hash.matches(chunk.hash()) && requested.storedSize == chunk.size()
                && requested.decodedSize == chunk.decodeSize()
                && requested.encoding.equals(chunk.encoding());
    }

    private static boolean matches(PresentationMember requested, AssetRef expected) {
        return expected != null && requested.name.equals(expected.name())
                && requested.hash.equals(expected.hash())
                && requested.storedSize == expected.storedSize()
                && requested.decodedSize == expected.decodedSize()
                && requested.encoding.equals(expected.encoding());
    }

    private static boolean matches(PresentationMember requested,
                                   AssetContainerView.ChunkInfo chunk) {
        return chunk != null && requested.name.equals(chunk.type())
                && requested.hash.matches(chunk.hash()) && requested.storedSize == chunk.size()
                && requested.decodedSize == chunk.decodeSize()
                && requested.encoding.equals(chunk.encoding());
    }

    private static ManagedContainer managed(CatalogRecord record) {
        return record.binding().content() instanceof ManagedContainer content ? content : null;
    }

    private static boolean rangeAvailable(Path path, long offset, int size) {
        try {
            return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    && Math.addExact(offset, size) <= Files.size(path);
        } catch (IOException | ArithmeticException | SecurityException unavailable) {
            return false;
        }
    }

    private Path packCoverPath(CatalogRootKind rootKind, String hierarchy) throws IOException {
        var configuredRoot = roots.root(rootKind).toAbsolutePath().normalize();
        var candidate = configuredRoot.resolve(hierarchy).resolve("ysm-pack.png").normalize();
        if (!candidate.startsWith(configuredRoot)
                || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        var realRoot = configuredRoot.toRealPath();
        var realCandidate = candidate.toRealPath();
        if (!realCandidate.startsWith(realRoot)
                || !Files.isRegularFile(realCandidate, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Pack cover path escapes its catalog root");
        }
        return realCandidate;
    }

    private static Path catalogRoot(CatalogRootKind rootKind) {
        return ModelCatalogSources.sources().stream()
                .filter(source -> source.rootKind() == rootKind)
                .map(source -> source.path().toAbsolutePath().normalize())
                .findFirst().orElseThrow(() ->
                        new IllegalArgumentException("No catalog root for " + rootKind));
    }

    private static void closeBuffers(List<? extends UniBuffer> buffers) {
        for (var buffer : buffers) {
            try {
                buffer.close();
            } catch (RuntimeException failure) {
                YesSteveModel.LOGGER.error("Failed to close metadata source", failure);
            }
        }
    }

    private static void closeChunkLeases(
            List<? extends ServerChunkRuntime.ChunkLease> leases) {
        for (var lease : leases) {
            try {
                lease.close();
            } catch (RuntimeException failure) {
                YesSteveModel.LOGGER.error("Failed to close chunk source", failure);
            }
        }
    }

    private static void closePackets(List<? extends CancellablePacket> packets) {
        for (var packet : packets) {
            try {
                packet.close();
            } catch (RuntimeException failure) {
                YesSteveModel.LOGGER.error("Failed to close unaccepted resource packet", failure);
            }
        }
    }

    private static ResourceFailureReason invalidRequest() {
        return ResourceFailureReason.RESOURCE_FAILURE_INVALID_REQUEST;
    }

    private static ResourceFailureReason unauthorized() {
        return ResourceFailureReason.RESOURCE_FAILURE_UNAUTHORIZED;
    }

    private static ResourceFailureReason notFound() {
        return ResourceFailureReason.RESOURCE_FAILURE_NOT_FOUND;
    }

    private static ResourceFailureReason busy() {
        return ResourceFailureReason.RESOURCE_FAILURE_BUSY;
    }

    private static ResourceFailureReason itemTooLarge() {
        return ResourceFailureReason.RESOURCE_FAILURE_ITEM_TOO_LARGE;
    }

    private static ResourceFailureReason unavailable() {
        return ResourceFailureReason.RESOURCE_FAILURE_UNAVAILABLE;
    }

    @FunctionalInterface
    interface RootResolver {
        Path root(CatalogRootKind kind);
    }

    private sealed interface OwnerEvent permits ChunkAcquisitionComplete,
            PresentationAcquisitionComplete, PacketClosed, ProductionFailed {
    }

    private record ChunkAcquisitionComplete(TransferLease lease, List<ChunkPlan> plans,
                                            Throwable failure)
            implements OwnerEvent {
    }

    private record PresentationAcquisitionComplete(
            TransferLease lease, List<PresentationPlan> plans,
            Throwable failure) implements OwnerEvent {
    }

    private record PacketClosed(TransferLease lease) implements OwnerEvent {
    }

    private record ProductionFailed(TransferLease lease, RuntimeException failure)
            implements OwnerEvent {
    }

    private final class TransferLease {
        private final long transferId;
        private List<CancellablePacket> packets;
        private List<CompletableFuture<ServerChunkRuntime.ChunkLease>> acquisitions;
        private List<CompletableFuture<byte[]>> previewAcquisitions;
        private int remaining;
        private LeaseState state;

        private TransferLease(long transferId) {
            this.transferId = transferId;
            packets = List.of();
            acquisitions = List.of();
            previewAcquisitions = List.of();
            state = LeaseState.ACQUIRING;
        }

        private void packetClosed() {
            enqueueOwnerEvent(new PacketClosed(this));
        }

        private void productionFailed(RuntimeException failure) {
            // Seal sibling dispatch before the server tick commits the terminal outcome.
            packets.forEach(CancellablePacket::cancel);
            enqueueOwnerEvent(new ProductionFailed(this, failure));
        }
    }

    private record MetadataPlan(Hash256 containerId, ModelRepresentation representation) {
    }

    private record ChunkPlan(ManagedContainer content,
                             AssetContainerView.ChunkInfo chunk,
                             RangePacket.FragmentFactory fragments) {
    }

    private record PresentationPlan(PresentationMember member, ManagedContainer content,
                                    AssetContainerView.ChunkInfo chunk,
                                    PreviewLoad previewLoad,
                                    CatalogRootKind coverRootKind) {
        private static PresentationPlan model(PresentationMember member,
                                              ManagedContainer content,
                                              AssetContainerView.ChunkInfo chunk) {
            return new PresentationPlan(member, content, chunk, null, null);
        }

        private static PresentationPlan preview(PresentationMember member,
                                                PreviewLoad load) {
            return new PresentationPlan(member, null, null, load, null);
        }

        private static PresentationPlan cover(PresentationMember member,
                                              CatalogRootKind rootKind) {
            return new PresentationPlan(member, null, null, null, rootKind);
        }
    }

    private static final class PreviewLoad {
        private final Hash256 containerId;
        private volatile byte[] bytes;

        private PreviewLoad(Hash256 containerId) {
            this.containerId = containerId;
        }
    }

    private record MetadataRequest(long transferId, List<Hash256> containerIds) {
    }

    private record ModelRequest(long transferId, Hash256 modelId, Hash256 containerId,
                                List<ChunkDescriptor> chunks) {
    }

    private record ChunkDescriptor(String name, Hash256 hash, int storedSize,
                                   int decodedSize, String encoding) {
    }

    private record PresentationRequest(long transferId, List<PresentationMember> members) {
    }

    private static final class PresentationMember {
        private final PresentationKind kind;
        private final int slot;
        private final Hash256 modelId;
        private final Hash256 containerId;
        private final String name;
        private final String hierarchy;
        private final Hash256 hash;
        private final int storedSize;
        private final int decodedSize;
        private final String encoding;
        private ResourceFailureReason failure = invalidRequest();

        private PresentationMember(PresentationKind kind, int slot, Hash256 modelId,
                                   Hash256 containerId, String name, String hierarchy,
                                   Hash256 hash, int storedSize, int decodedSize,
                                   String encoding) {
            this.kind = kind;
            this.slot = slot;
            this.modelId = modelId;
            this.containerId = containerId;
            this.name = name;
            this.hierarchy = hierarchy;
            this.hash = hash;
            this.storedSize = storedSize;
            this.decodedSize = decodedSize;
            this.encoding = encoding;
        }

        private static PresentationMember model(PresentationKind kind, int slot,
                                                Hash256 modelId, Hash256 containerId,
                                                String name, Hash256 hash, int storedSize,
                                                int decodedSize, String encoding) {
            return new PresentationMember(kind, slot, modelId, containerId, name, null,
                    hash, storedSize, decodedSize, encoding);
        }

        private static PresentationMember cover(int slot, String hierarchy, Hash256 hash,
                                                int storedSize, int decodedSize,
                                                String encoding) {
            return new PresentationMember(PresentationKind.PACK_COVER, slot, null, null,
                    null, hierarchy, hash, storedSize, decodedSize, encoding);
        }
    }

    private enum PresentationKind {
        PREVIEW,
        ICON,
        PACK_COVER
    }

    private enum Begin {
        ACCEPTED,
        BUSY,
        INVALID,
        CLOSED
    }

    private enum LeaseState {
        ACQUIRING,
        ACTIVE,
        COMPLETED,
        CANCELLED,
        FAILED
    }

    private enum SubmitResult {
        ACCEPTED,
        REJECTED,
        RETIRED
    }
}
