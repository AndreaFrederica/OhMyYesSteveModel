package com.elfmcys.ysm.model.session.server;

import com.elfmcys.ysm.capability.ModelInfoCapability;
import com.elfmcys.ysm.format.schema.file.ChunkDataSource;
import com.elfmcys.ysm.format.schema.model.ModelFileView;
import com.elfmcys.ysm.model.catalog.content.AssetRef;
import com.elfmcys.ysm.model.catalog.content.CatalogContentBinding;
import com.elfmcys.ysm.model.catalog.content.ModelContent;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogAccess;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogEntry;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogPresentation;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogRecord;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogSnapshot;
import com.elfmcys.ysm.model.catalog.source.CatalogModelLocation;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.HierarchyPath;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.elfmcys.ysm.model.domain.ModelPath;
import com.elfmcys.ysm.model.domain.ModelRepresentation;
import com.elfmcys.ysm.model.domain.ModelScanReport;
import com.elfmcys.ysm.model.session.client.ClientModelSession;
import com.elfmcys.ysm.model.session.client.state.ActivationFailure;
import com.elfmcys.ysm.model.session.client.state.ActivationSnapshot;
import com.elfmcys.ysm.model.session.client.state.PublicationEntry;
import com.elfmcys.ysm.model.session.client.state.RemotePublicationSnapshot;
import com.elfmcys.ysm.model.session.server.state.Selection;
import com.elfmcys.ysm.network.protocol.ProtocolVersion;
import com.elfmcys.ysm.network.session.SessionMode;
import com.elfmcys.ysm.proto.network.AnimationState;
import com.elfmcys.ysm.proto.network.EntityRef;
import com.elfmcys.ysm.proto.network.GameplayState;
import com.elfmcys.ysm.proto.network.PlayerStateReport;
import com.elfmcys.ysm.proto.network.RoamingState;
import com.elfmcys.ysm.proto.network.StateWriteMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ModelSessionContractTest {
    @Test
    void commitsLeanAuthorityBeforeTerminalActivation() {
        var intrinsic = record(1, "default", CatalogAccess.PUBLIC,
                CatalogRootKind.BUILTIN);
        var local = snapshot(intrinsic, record(3, "local", CatalogAccess.PUBLIC));
        var explicitLocal = new ClientModelSession(SessionMode.LOCAL, true, local);
        assertEquals(ClientModelSession.State.LOCAL, explicitLocal.state());
        assertFalse(explicitLocal.onServerHello("bad", false).orElseThrow().accepted());

        var remote = new ClientModelSession(SessionMode.AUTO, true, local);
        assertTrue(remote.onServerHello(
                ProtocolVersion.TRANSPORT_VERSION, true)
                .orElseThrow().accepted());
        var record = record(2, "remote", CatalogAccess.PUBLIC);
        var publication = publication(record);
        remote.publishFull(publication);

        assertEquals(ClientModelSession.State.ACTIVE, remote.state());
        assertSame(publication, remote.remoteSnapshot().orElseThrow());
        assertEquals(Set.of(intrinsic.entry().modelId()),
                remote.catalog().byModelId().keySet());
        assertFalse(remote.activationSnapshot().orElseThrow().terminal());

        var states = Map.<Hash256, ActivationSnapshot.State>of(
                record.entry().modelId(),
                new ActivationSnapshot.Ready(publication.entries()
                        .get(record.entry().modelId()), record));
        remote.publishActivation(new ActivationSnapshot(publication, states));
        assertSame(record, remote.catalog().byModelId().get(record.entry().modelId()));
        assertSame(intrinsic, remote.catalog().byModelId().get(intrinsic.entry().modelId()));

        remote.failCompleteTransfer();
        assertEquals(Set.of(intrinsic.entry().modelId()),
                remote.catalog().byModelId().keySet());
    }

    @Test
    void collectionOnlyDeltaRebasesTerminalActivationWithoutReplacingContent() {
        var record = record(1, "one", CatalogAccess.PUBLIC);
        var client = activeClient(record);
        var before = client.catalog().byModelId().get(hash(1));

        var authority = client.remoteSnapshot().orElseThrow();
        client.publishPublication(authority.withCollections(
                Set.of(hash(1)), authority.packs(), authority.defaultAnimations()));

        assertSame(before, client.catalog().byModelId().get(hash(1)));
        assertEquals(Set.of(hash(1)), client.remoteSnapshot().orElseThrow().grants());
    }

    @Test
    void catalogDeltaRetainsReadyTupleAndMakesNewContentPending() {
        var first = record(1, "first", CatalogAccess.PUBLIC);
        var second = record(2, "second", CatalogAccess.PUBLIC);
        var client = activeClient(first);
        var firstEntry = publication(first).entries().get(first.entry().modelId());
        var secondEntry = new PublicationEntry(
                new ModelFileIdentity(second.entry().modelId(), container(2)),
                second.entry().path(), second.entry().access());
        var next = new RemotePublicationSnapshot(List.of(firstEntry, secondEntry), Set.of(),
                List.of(), Map.of());
        client.publishPublication(next);

        assertInstanceOf(ActivationSnapshot.Ready.class,
                client.activationSnapshot().orElseThrow().entries().get(first.entry().modelId()));
        assertInstanceOf(ActivationSnapshot.Pending.class,
                client.activationSnapshot().orElseThrow().entries().get(second.entry().modelId()));
        assertEquals(Set.of(first.entry().modelId()), client.catalog().byModelId().keySet());

        client.publishActivation(new ActivationSnapshot(next, Map.of(
                first.entry().modelId(), new ActivationSnapshot.Ready(firstEntry, first),
                second.entry().modelId(), new ActivationSnapshot.Failed(secondEntry,
                        new ActivationFailure(ActivationFailure.Kind.TRANSIENT_ACCESS,
                                "offline")))));
        assertEquals(Set.of(second.entry().modelId()),
                client.retryTransient(second.entry().modelId()));

        client.publishActivation(new ActivationSnapshot(next, Map.of(
                first.entry().modelId(), new ActivationSnapshot.Ready(firstEntry, first),
                second.entry().modelId(), new ActivationSnapshot.Ready(secondEntry, second))));
        assertEquals(Set.of(first.entry().modelId(), second.entry().modelId()),
                client.catalog().byModelId().keySet());
    }

    @Test
    void publicationRemovalDropsOnlyTheRemovedActivation() {
        var first = record(1, "first", CatalogAccess.PUBLIC);
        var second = record(2, "second", CatalogAccess.PUBLIC);
        var client = activeClient(first);
        var secondEntry = new PublicationEntry(
                new ModelFileIdentity(second.entry().modelId(), container(2)),
                second.entry().path(), second.entry().access());
        var next = new RemotePublicationSnapshot(List.of(secondEntry), Set.of(),
                List.of(), Map.of());
        client.publishPublication(next);

        assertEquals(Set.of(second.entry().modelId()),
                client.activationSnapshot().orElseThrow().entries().keySet());
        assertTrue(client.catalog().byModelId().isEmpty());
    }

    @Test
    void pendingHelloIsReusableButOneServerSessionActivatesOnlyOnce() {
        var client = new ClientModelSession(SessionMode.AUTO, true, CatalogSnapshot.empty());
        var version = ProtocolVersion.TRANSPORT_VERSION;
        assertTrue(client.onServerHello(version, true).orElseThrow().accepted());
        assertTrue(client.onServerHello(version, true).orElseThrow().accepted());
        assertEquals(ClientModelSession.State.NEGOTIATING, client.state());

        var server = new ServerModelSession(CatalogSnapshot::empty, true);
        assertTrue(server.pending());
        assertTrue(server.syncRoaming());
        assertTrue(server.activate());
        assertFalse(server.activate());
        assertEquals(1, server.allocatePublicationId());
        assertEquals(2, server.allocatePublicationId());
    }

    @Test
    void changedPendingHelloFailsTheRemoteSession() {
        var client = new ClientModelSession(SessionMode.AUTO, true, CatalogSnapshot.empty());
        var version = ProtocolVersion.TRANSPORT_VERSION;
        assertTrue(client.onServerHello(version, true).orElseThrow().accepted());

        assertTrue(client.onServerHello(version, false).isEmpty());
        assertEquals(ClientModelSession.State.INTRINSIC_DEFAULT_ONLY, client.state());
    }

    @Test
    void differentDevelopmentProtocolVersionFailsOnlyTheModelSession() {
        var client = new ClientModelSession(SessionMode.AUTO, true, CatalogSnapshot.empty());

        assertTrue(client.onServerHello("0.3.0-snapshot", true).isEmpty());
        assertEquals(ClientModelSession.State.INTRINSIC_DEFAULT_ONLY, client.state());
        assertTrue(client.catalog().byModelId().isEmpty());
    }

    @Test
    void publicationIdExhaustionRequiresAReplacementConnection() {
        var server = new ServerModelSession(CatalogSnapshot::empty, false, -1L);
        assertTrue(server.activate());

        assertEquals(-1L, server.allocatePublicationId());
        assertThrows(IllegalStateException.class, server::allocatePublicationId);
    }

    @Test
    void declinedServerOfferCannotBeReactivated() {
        var server = new ServerModelSession(CatalogSnapshot::empty, false);

        assertTrue(server.decline());
        assertFalse(server.pending());
        assertFalse(server.activate());
        assertFalse(server.active());
    }

    @Test
    void serverReportBaselineBelongsToTheActiveModelSession() {
        var catalog = snapshot(record(1, "first", CatalogAccess.PUBLIC));
        var server = new ServerModelSession(() -> catalog, false);
        var full = report(StateWriteMode.STATE_WRITE_MODE_FULL);
        var delta = report(StateWriteMode.STATE_WRITE_MODE_DELTA);

        assertFalse(server.acceptsPlayerStateReport(full));
        assertTrue(server.activate());
        assertFalse(server.acceptsPlayerStateReport(delta));
        assertTrue(server.acceptsPlayerStateReport(full));
        server.commitPlayerStateReport(true);
        assertTrue(server.acceptsPlayerStateReport(delta));
        server.commitPlayerStateReport(false);

        server.commitCatalog(catalog);
        assertEquals(ServerModelSession.SelectionResult.ACCEPTED,
                server.select(new Selection.Model(hash(1))));
        assertFalse(server.acceptsPlayerStateReport(delta));
        assertTrue(server.acceptsPlayerStateReport(full));
    }

    @Test
    void intrinsicDefaultReplacementRequiresANewReportBaseline() {
        var first = snapshot(record(1, "default", CatalogAccess.PUBLIC,
                CatalogRootKind.BUILTIN));
        var second = snapshot(record(2, "default", CatalogAccess.PUBLIC,
                CatalogRootKind.BUILTIN));
        var server = new ServerModelSession(() -> first, false);
        var full = report(StateWriteMode.STATE_WRITE_MODE_FULL);
        var delta = report(StateWriteMode.STATE_WRITE_MODE_DELTA);

        assertTrue(server.activate());
        server.commitCatalog(first);
        assertTrue(server.acceptsPlayerStateReport(full));
        server.commitPlayerStateReport(true);
        assertTrue(server.acceptsPlayerStateReport(delta));

        server.commitCatalog(second);
        assertFalse(server.acceptsPlayerStateReport(delta));
        assertTrue(server.acceptsPlayerStateReport(full));
    }

    @Test
    void gameServerReportProfileRejectsGameplayAndUnauthorizedRoaming() {
        var server = new ServerModelSession(CatalogSnapshot::empty, false);
        assertTrue(server.activate());

        assertFalse(server.acceptsPlayerStateReport(report(
                StateWriteMode.STATE_WRITE_MODE_FULL)
                .withGameplay(GameplayState.newBuilder()
                        .setHealth(1).setMaxHealth(1).build())));
        assertFalse(server.acceptsPlayerStateReport(report(
                StateWriteMode.STATE_WRITE_MODE_FULL)
                .withRoaming(RoamingState.newBuilder()
                        .setModelKey(1).build())));
    }

    @Test
    void serverUsesOnlyTheLastPublishedSnapshotForAssetLookup() {
        var first = snapshot(record(1, "first", CatalogAccess.PUBLIC));
        var live = new AtomicReference<>(first);
        var session = new ServerModelSession(live::get, false);
        assertTrue(session.activate());
        assertFalse(session.canRequest(hash(1), AssetRef.Kind.CHUNK, false));

        session.commitCatalog(first);
        live.set(snapshot(record(2, "second", CatalogAccess.PUBLIC)));

        assertTrue(session.canRequest(hash(1), AssetRef.Kind.CHUNK, false));
        assertFalse(session.containsModel(hash(2)));
        assertEquals(hash(1), session.findModel(hash(1)).orElseThrow()
                .entry().modelId());
    }

    @Test
    void serverPublicationCutoverReplacesLookupAndAuthorityTogether() {
        var first = snapshot(record(1, "first", CatalogAccess.PUBLIC),
                record(2, "second", CatalogAccess.AUTHORIZED));
        var second = snapshot(record(2, "second", CatalogAccess.AUTHORIZED));
        var session = new ServerModelSession(() -> first, false);
        assertTrue(session.activate());
        session.commitCatalog(first, Set.of(hash(2)));
        assertEquals(ServerModelSession.SelectionResult.ACCEPTED,
                session.select(new Selection.Model(hash(2))));
        assertTrue(session.findModel(hash(1)).isPresent());

        var transition = session.commitCatalog(second, Set.of(hash(2)));

        assertTrue(session.findModel(hash(1)).isEmpty());
        assertTrue(session.findModel(hash(2)).isPresent());
        assertEquals(Set.of(hash(2)), session.grants());
        assertEquals(new Selection.Model(hash(2)), session.selection());
        assertSame(transition.current(), session.authority());
    }

    @Test
    void serverChecksCurrentGrantForSelectionAndEachAssetRequest() {
        var publicId = hash(1);
        var authId = hash(2);
        var catalog = snapshot(record(1, "public", CatalogAccess.PUBLIC),
                record(2, "auth", CatalogAccess.AUTHORIZED));
        var session = new ServerModelSession(() -> catalog, false);
        assertTrue(session.activate());
        session.commitCatalog(catalog);

        assertEquals(ServerModelSession.SelectionResult.UNAUTHORIZED,
                session.select(new Selection.Model(authId)));
        assertTrue(session.canRequest(authId, AssetRef.Kind.CHUNK, false));
        assertFalse(session.canRequest(authId, AssetRef.Kind.CHUNK, true));
        assertTrue(session.canRequest(authId, AssetRef.Kind.PREVIEW, true));
        assertTrue(session.canRequest(authId, AssetRef.Kind.ICON, true));
        session.setGrants(Set.of(authId));
        assertEquals(ServerModelSession.SelectionResult.ACCEPTED,
                session.select(new Selection.Model(authId)));
        session.setGrants(Set.of());
        assertInstanceOf(Selection.IntrinsicDefault.class, session.selection());
        assertEquals(ServerModelSession.SelectionResult.ACCEPTED,
                session.select(new Selection.Model(publicId)));
    }

    @Test
    void forcedSelectionBypassesOnlyItsOwnChunksWithoutMutatingGrants() {
        var forcedId = hash(2);
        var otherId = hash(3);
        var catalog = snapshot(record(1, "public", CatalogAccess.PUBLIC),
                record(2, "forced", CatalogAccess.AUTHORIZED),
                record(3, "other", CatalogAccess.AUTHORIZED));
        var session = new ServerModelSession(() -> catalog, false);
        assertTrue(session.activate());
        session.commitCatalog(catalog);

        assertEquals(ServerModelSession.SelectionResult.ACCEPTED,
                session.selectForced(new Selection.Model(forcedId), true));
        assertEquals(Set.of(), session.grants());
        assertTrue(session.canRequest(forcedId, AssetRef.Kind.CHUNK, true));
        assertFalse(session.canRequest(otherId, AssetRef.Kind.CHUNK, true));
        assertTrue(session.canRequest(otherId, AssetRef.Kind.PREVIEW, true));
        assertTrue(session.canRequest(otherId, AssetRef.Kind.ICON, true));

        session.setGrants(Set.of(otherId));
        session.setGrants(Set.of());
        assertEquals(new Selection.Model(forcedId), session.selection());
        assertTrue(session.canRequest(forcedId, AssetRef.Kind.CHUNK, true));
        assertEquals(ServerModelSession.SelectionResult.ACCEPTED,
                session.select(new Selection.Model(forcedId, "updated")));
        assertTrue(session.canRequest(forcedId, AssetRef.Kind.CHUNK, true));

        assertEquals(ServerModelSession.SelectionResult.UNAUTHORIZED,
                session.select(new Selection.Model(otherId)));
        // A rejected manual request leaves the previously active selection intact.
        assertEquals(new Selection.Model(forcedId, "updated"), session.selection());
        assertTrue(session.canRequest(forcedId, AssetRef.Kind.CHUNK, true));
    }

    @Test
    void publicationRemovalClearsForcedSelectionAndPrivateMode() {
        var forcedId = hash(2);
        var catalog = snapshot(record(2, "forced", CatalogAccess.AUTHORIZED));
        var session = new ServerModelSession(() -> catalog, false);
        assertTrue(session.activate());
        session.commitCatalog(catalog);
        assertEquals(ServerModelSession.SelectionResult.ACCEPTED,
                session.selectForced(new Selection.Model(forcedId), true));

        var transition = session.commitCatalog(CatalogSnapshot.empty(), Set.of());

        assertInstanceOf(Selection.IntrinsicDefault.class,
                transition.current().selection());
        assertFalse(transition.current().ignoreGrants());
        assertFalse(session.canRequest(forcedId, AssetRef.Kind.CHUNK, true));
    }

    @Test
    void persistedForcedSelectionCanBeRestoredIntoANewSession() {
        var forcedId = hash(2);
        var persistent = new ModelInfoCapability();
        persistent.setCommandSelection(forcedId, "skin", true);
        var restored = new ModelInfoCapability();
        restored.deserializeNBT(persistent.serializeNBT());

        var catalog = snapshot(record(2, "forced", CatalogAccess.AUTHORIZED));
        var session = new ServerModelSession(() -> catalog, false);
        assertTrue(session.activate());
        session.commitCatalog(catalog);

        assertEquals(ServerModelSession.SelectionResult.ACCEPTED,
                session.selectForced(new Selection.Model(
                        restored.getModelId(), restored.getSelectTexture()),
                        restored.ignoresGrantsFor(restored.getModelId())));
        assertTrue(session.canRequest(forcedId, AssetRef.Kind.CHUNK, true));
    }

    private static ClientModelSession activeClient(CatalogRecord record) {
        var client = new ClientModelSession(SessionMode.AUTO, true, CatalogSnapshot.empty());
        client.onServerHello(ProtocolVersion.TRANSPORT_VERSION,
                false);
        var publication = publication(record);
        client.publishFull(publication);
        client.publishActivation(new ActivationSnapshot(publication,
                Map.of(record.entry().modelId(), new ActivationSnapshot.Ready(
                        publication.entries().get(record.entry().modelId()), record))));
        return client;
    }

    private static RemotePublicationSnapshot publication(CatalogRecord record) {
        return new RemotePublicationSnapshot(List.of(new PublicationEntry(
                new ModelFileIdentity(record.entry().modelId(), container(1)),
                record.entry().path(),
                record.entry().access())), Set.of(), List.of(), Map.of());
    }

    private static PlayerStateReport report(
            StateWriteMode mode) {
        return PlayerStateReport.newBuilder()
                .setSubject(EntityRef.newBuilder()
                        .setEntityId(1).build())
                .setMode(mode)
                .setAnimation(AnimationState.newBuilder()
                        .setStopped(true).build())
                .build();
    }

    private static CatalogSnapshot snapshot(CatalogRecord... records) {
        var map = new LinkedHashMap<Hash256, CatalogRecord>();
        for (var record : records) {
            map.put(record.entry().modelId(), record);
        }
        return new CatalogSnapshot(map, List.of(), ModelScanReport.empty());
    }

    private static CatalogRecord record(int seed, String path, CatalogAccess access) {
        return record(seed, path, access, access == CatalogAccess.AUTHORIZED
                ? CatalogRootKind.AUTH : CatalogRootKind.CUSTOM);
    }

    private static CatalogRecord record(int seed, String path, CatalogAccess access,
                                        CatalogRootKind root) {
        var id = hash(seed);
        var content = new StubContent(id);
        return new CatalogRecord(new CatalogEntry(id, new HierarchyPath(path), access,
                CatalogPresentation.empty(path)),
                new CatalogModelLocation(root, new ModelPath(path)),
                new CatalogContentBinding(id, content));
    }

    private static Hash256 hash(int seed) {
        var bytes = new byte[Hash256.SIZE];
        bytes[0] = (byte) seed;
        return new Hash256(bytes);
    }

    private static Hash256 container(int seed) {
        return hash(seed + 20);
    }

    private record StubContent(Hash256 modelId) implements ModelContent {
        @Override public ModelRepresentation representation() { return null; }
        @Override public ModelFileView modelFile() { return null; }
        @Override public ChunkDataSource chunks() { return null; }
    }
}
