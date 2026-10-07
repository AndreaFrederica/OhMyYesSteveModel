package com.elfmcys.ysm.network.protocol;

import com.elfmcys.ysm.network.NetworkPayload;
import com.elfmcys.ysm.network.forge.ControlHandler;
import com.elfmcys.ysm.network.forge.MinecraftStateHandler;
import com.elfmcys.ysm.network.forge.PlayerStateHandler;
import com.elfmcys.ysm.network.forge.SessionProtocolHandler;
import com.elfmcys.ysm.network.frame.FrameCodec;
import com.elfmcys.ysm.proto.network.ChunkFragment;
import com.elfmcys.ysm.proto.network.EmitMolangSync;
import com.elfmcys.ysm.proto.network.EntityAnimationActionRequest;
import com.elfmcys.ysm.proto.network.ExecuteMolangEvent;
import com.elfmcys.ysm.proto.network.IconFragment;
import com.elfmcys.ysm.proto.network.MetadataPrefixFragment;
import com.elfmcys.ysm.proto.network.MetadataPrefixRequest;
import com.elfmcys.ysm.proto.network.ModelChunkRequest;
import com.elfmcys.ysm.proto.network.MolangSyncEvent;
import com.elfmcys.ysm.proto.network.PackCoverFragment;
import com.elfmcys.ysm.proto.network.PlayerStateReport;
import com.elfmcys.ysm.proto.network.PlayerStateUpdate;
import com.elfmcys.ysm.proto.network.PresentationPageRequest;
import com.elfmcys.ysm.proto.network.PreviewFragment;
import com.elfmcys.ysm.proto.network.ProjectileModelState;
import com.elfmcys.ysm.proto.network.ResourceTransferCancel;
import com.elfmcys.ysm.proto.network.ResourceTransferFailure;
import com.elfmcys.ysm.proto.network.SelectModelRequest;
import com.elfmcys.ysm.proto.network.SelectModelResult;
import com.elfmcys.ysm.proto.network.ServerHello;
import com.elfmcys.ysm.proto.network.SessionDeltaFragment;
import com.elfmcys.ysm.proto.network.SessionFullFragment;
import com.elfmcys.ysm.proto.network.SessionResponse;
import com.elfmcys.ysm.proto.network.StarredModelsSnapshot;
import com.elfmcys.ysm.proto.network.SubmitRouletteExpressionRequest;
import com.elfmcys.ysm.proto.network.SwingHandRequest;
import com.elfmcys.ysm.proto.network.UpdateStarredModelRequest;
import com.elfmcys.ysm.proto.network.VehicleModelState;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import com.elfmcys.ysm.network.forge.NetworkEvent;
import us.hebi.quickbuf.ProtoMessage;

/** Stable ids and the only parser/handler registry for the current wire. */
public final class ProtocolMessages {
    public static final int SERVER_HELLO_ID = 0;
    public static final int SESSION_RESPONSE_ID = 1;
    public static final int SESSION_FULL_FRAGMENT_ID = 2;
    public static final int SESSION_DELTA_FRAGMENT_ID = 3;
    public static final int METADATA_PREFIX_REQUEST_ID = 4;
    public static final int MODEL_CHUNK_REQUEST_ID = 5;
    public static final int PRESENTATION_PAGE_REQUEST_ID = 6;
    public static final int RESOURCE_TRANSFER_CANCEL_ID = 7;
    public static final int RESOURCE_TRANSFER_FAILURE_ID = 8;
    public static final int SELECT_MODEL_REQUEST_ID = 9;
    public static final int SELECT_MODEL_RESULT_ID = 10;
    public static final int METADATA_PREFIX_FRAGMENT_ID = 11;
    public static final int CHUNK_FRAGMENT_ID = 12;
    public static final int PREVIEW_FRAGMENT_ID = 13;
    public static final int ICON_FRAGMENT_ID = 14;
    public static final int PACK_COVER_FRAGMENT_ID = 15;

    public static final int PLAYER_STATE_REPORT_ID = 16;
    public static final int PLAYER_STATE_UPDATE_ID = 17;
    public static final int STARRED_MODELS_SNAPSHOT_ID = 18;
    public static final int UPDATE_STARRED_MODEL_REQUEST_ID = 19;
    public static final int ENTITY_ANIMATION_ACTION_REQUEST_ID = 20;
    public static final int EXECUTE_MOLANG_EVENT_ID = 21;
    public static final int SUBMIT_ROULETTE_EXPRESSION_REQUEST_ID = 22;
    public static final int EMIT_MOLANG_SYNC_ID = 23;
    public static final int MOLANG_SYNC_EVENT_ID = 24;
    public static final int SWING_HAND_REQUEST_ID = 25;
    public static final int PROJECTILE_MODEL_STATE_ID = 26;
    public static final int VEHICLE_MODEL_STATE_ID = 27;

    // Handler lambdas defer mixed-side target-class resolution until direction validation;
    // direct method references make the JVM resolve client-only types on dedicated servers.
    public static final ProtocolMessageRegistry REGISTRY = ProtocolMessageRegistry.builder()
            .add(spec(SERVER_HELLO_ID, MessageDirection.SERVER_TO_CLIENT,
                    ProtocolMessageSpec.AttachmentPolicy.FORBIDDEN,
                    ServerHello.class, ServerHello::parseFrom,
                    SessionProtocolHandler::handleServerHello))
            .add(spec(SESSION_RESPONSE_ID, MessageDirection.CLIENT_TO_SERVER,
                    ProtocolMessageSpec.AttachmentPolicy.FORBIDDEN,
                    SessionResponse.class, SessionResponse::parseFrom,
                    SessionProtocolHandler::handleSessionResponse))
            .add(spec(SESSION_FULL_FRAGMENT_ID, MessageDirection.SERVER_TO_CLIENT,
                    ProtocolMessageSpec.AttachmentPolicy.FORBIDDEN,
                    SessionFullFragment.class, SessionFullFragment::parseFrom,
                    SessionProtocolHandler::handleSessionFullFragment))
            .add(spec(SESSION_DELTA_FRAGMENT_ID, MessageDirection.SERVER_TO_CLIENT,
                    ProtocolMessageSpec.AttachmentPolicy.FORBIDDEN,
                    SessionDeltaFragment.class, SessionDeltaFragment::parseFrom,
                    SessionProtocolHandler::handleSessionDeltaFragment))
            .add(spec(METADATA_PREFIX_REQUEST_ID, MessageDirection.CLIENT_TO_SERVER,
                    ProtocolMessageSpec.AttachmentPolicy.FORBIDDEN,
                    MetadataPrefixRequest.class, MetadataPrefixRequest::parseFrom,
                    SessionProtocolHandler::handleMetadataPrefixRequest))
            .add(spec(MODEL_CHUNK_REQUEST_ID, MessageDirection.CLIENT_TO_SERVER,
                    ProtocolMessageSpec.AttachmentPolicy.FORBIDDEN,
                    ModelChunkRequest.class, ModelChunkRequest::parseFrom,
                    SessionProtocolHandler::handleModelChunkRequest))
            .add(spec(PRESENTATION_PAGE_REQUEST_ID, MessageDirection.CLIENT_TO_SERVER,
                    ProtocolMessageSpec.AttachmentPolicy.FORBIDDEN,
                    PresentationPageRequest.class, PresentationPageRequest::parseFrom,
                    SessionProtocolHandler::handlePresentationPageRequest))
            .add(spec(RESOURCE_TRANSFER_CANCEL_ID, MessageDirection.CLIENT_TO_SERVER,
                    ProtocolMessageSpec.AttachmentPolicy.FORBIDDEN,
                    ResourceTransferCancel.class, ResourceTransferCancel::parseFrom,
                    SessionProtocolHandler::handleResourceTransferCancel))
            .add(spec(RESOURCE_TRANSFER_FAILURE_ID, MessageDirection.SERVER_TO_CLIENT,
                    ProtocolMessageSpec.AttachmentPolicy.FORBIDDEN,
                    ResourceTransferFailure.class, ResourceTransferFailure::parseFrom,
                    SessionProtocolHandler::handleResourceTransferFailure))
            .add(spec(SELECT_MODEL_REQUEST_ID, MessageDirection.CLIENT_TO_SERVER,
                    ProtocolMessageSpec.AttachmentPolicy.FORBIDDEN,
                    SelectModelRequest.class, SelectModelRequest::parseFrom,
                    SessionProtocolHandler::handleSelectModelRequest))
            .add(spec(SELECT_MODEL_RESULT_ID, MessageDirection.SERVER_TO_CLIENT,
                    ProtocolMessageSpec.AttachmentPolicy.FORBIDDEN,
                    SelectModelResult.class, SelectModelResult::parseFrom,
                    SessionProtocolHandler::handleSelectModelResult))
            .add(spec(METADATA_PREFIX_FRAGMENT_ID, MessageDirection.SERVER_TO_CLIENT,
                    ProtocolMessageSpec.AttachmentPolicy.FORBIDDEN,
                    MetadataPrefixFragment.class, MetadataPrefixFragment::parseFrom,
                    SessionProtocolHandler::handleMetadataPrefixFragment))
            .add(spec(CHUNK_FRAGMENT_ID, MessageDirection.SERVER_TO_CLIENT,
                    ProtocolMessageSpec.AttachmentPolicy.REQUIRED,
                    ChunkFragment.class, ChunkFragment::parseFrom,
                    SessionProtocolHandler::handleChunkFragment))
            .add(spec(PREVIEW_FRAGMENT_ID, MessageDirection.SERVER_TO_CLIENT,
                    ProtocolMessageSpec.AttachmentPolicy.CONDITIONAL,
                    PreviewFragment.class, PreviewFragment::parseFrom,
                    SessionProtocolHandler::handlePreviewFragment))
            .add(spec(ICON_FRAGMENT_ID, MessageDirection.SERVER_TO_CLIENT,
                    ProtocolMessageSpec.AttachmentPolicy.CONDITIONAL,
                    IconFragment.class, IconFragment::parseFrom,
                    SessionProtocolHandler::handleIconFragment))
            .add(spec(PACK_COVER_FRAGMENT_ID, MessageDirection.SERVER_TO_CLIENT,
                    ProtocolMessageSpec.AttachmentPolicy.CONDITIONAL,
                    PackCoverFragment.class, PackCoverFragment::parseFrom,
                    SessionProtocolHandler::handlePackCoverFragment))
            .add(proto(PLAYER_STATE_REPORT_ID, MessageDirection.CLIENT_TO_SERVER,
                    PlayerStateReport.class, ProtocolLimits.MAX_PLAYER_STATE_BYTES,
                    PlayerStateReport::parseFrom,
                    (message, context) -> PlayerStateHandler.handleReport(message, context)))
            .add(proto(PLAYER_STATE_UPDATE_ID, MessageDirection.SERVER_TO_CLIENT,
                    PlayerStateUpdate.class, ProtocolLimits.MAX_PLAYER_STATE_BYTES,
                    PlayerStateUpdate::parseFrom,
                    (message, context) -> PlayerStateHandler.handleUpdate(message, context)))
            .add(proto(STARRED_MODELS_SNAPSHOT_ID, MessageDirection.SERVER_TO_CLIENT,
                    StarredModelsSnapshot.class, ProtocolLimits.MAX_MODEL_SET_BYTES,
                    StarredModelsSnapshot::parseFrom,
                    (message, context) -> ControlHandler.handleStarredModels(message, context)))
            .add(proto(UPDATE_STARRED_MODEL_REQUEST_ID, MessageDirection.CLIENT_TO_SERVER,
                    UpdateStarredModelRequest.class, ProtocolLimits.MAX_STAR_UPDATE_BYTES,
                    UpdateStarredModelRequest::parseFrom,
                    (message, context) -> ControlHandler.handleUpdateStar(message, context)))
            .add(proto(ENTITY_ANIMATION_ACTION_REQUEST_ID, MessageDirection.CLIENT_TO_SERVER,
                    EntityAnimationActionRequest.class, ProtocolLimits.MAX_ENTITY_ACTION_BYTES,
                    EntityAnimationActionRequest::parseFrom,
                    (message, context) -> ControlHandler.handleEntityAnimation(message, context)))
            .add(proto(EXECUTE_MOLANG_EVENT_ID, MessageDirection.SERVER_TO_CLIENT,
                    ExecuteMolangEvent.class, ProtocolLimits.MAX_MOLANG_EVENT_BYTES,
                    ExecuteMolangEvent::parseFrom, ControlHandler::handleExecuteMolang))
            .add(proto(SUBMIT_ROULETTE_EXPRESSION_REQUEST_ID, MessageDirection.CLIENT_TO_SERVER,
                    SubmitRouletteExpressionRequest.class, ProtocolLimits.MAX_ENTITY_ACTION_BYTES,
                    SubmitRouletteExpressionRequest::parseFrom, ControlHandler::handleSubmitRoulette))
            .add(proto(EMIT_MOLANG_SYNC_ID, MessageDirection.CLIENT_TO_SERVER,
                    EmitMolangSync.class, ProtocolLimits.MAX_STAR_UPDATE_BYTES,
                    EmitMolangSync::parseFrom,
                    (message, context) -> ControlHandler.handleEmitMolangSync(message, context)))
            .add(proto(MOLANG_SYNC_EVENT_ID, MessageDirection.SERVER_TO_CLIENT,
                    MolangSyncEvent.class, ProtocolLimits.MAX_MOLANG_SYNC_BYTES,
                    MolangSyncEvent::parseFrom,
                    (message, context) -> ControlHandler.handleMolangSync(message, context)))
            .add(proto(SWING_HAND_REQUEST_ID, MessageDirection.CLIENT_TO_SERVER,
                    SwingHandRequest.class, ProtocolLimits.MAX_SWING_HAND_BYTES,
                    SwingHandRequest::parseFrom,
                    (message, context) -> ControlHandler.handleSwingHand(message, context)))
            .add(proto(PROJECTILE_MODEL_STATE_ID, MessageDirection.SERVER_TO_CLIENT,
                    ProjectileModelState.class, ProtocolLimits.MAX_MINECRAFT_STATE_BYTES,
                    ProjectileModelState::parseFrom,
                    (message, context) -> MinecraftStateHandler.handleProjectile(message, context)))
            .add(proto(VEHICLE_MODEL_STATE_ID, MessageDirection.SERVER_TO_CLIENT,
                    VehicleModelState.class, ProtocolLimits.MAX_MINECRAFT_STATE_BYTES,
                    VehicleModelState::parseFrom,
                    (message, context) -> MinecraftStateHandler.handleVehicle(message, context)))
            .build();

    private ProtocolMessages() {
    }

    private static <T extends ProtoMessage<T>> ProtocolMessageSpec<T> spec(
            int id, MessageDirection direction,
            ProtocolMessageSpec.AttachmentPolicy attachmentPolicy, Class<T> type,
            ProtocolMessageSpec.Parser<T> parser, ProtocolMessageSpec.Handler<T> handler) {
        return new ProtocolMessageSpec<>(id, direction, type,
                FrameCodec.MAX_DECODED_PROTO_BYTES, attachmentPolicy, parser, handler);
    }

    private static <T extends ProtoMessage<T>> ProtocolMessageSpec<T> proto(
            int id, MessageDirection direction, Class<T> type, int maxBytes,
            ProtocolMessageSpec.Parser<T> parser,
            BiConsumer<T, Supplier<NetworkEvent.Context>> handler) {
        return new ProtocolMessageSpec<>(id, direction, type, maxBytes,
                ProtocolMessageSpec.AttachmentPolicy.FORBIDDEN, parser,
                (payload, context) -> handleProto(payload, context, handler));
    }

    private static <T extends ProtoMessage<T>> void handleProto(
            NetworkPayload<T> payload, Supplier<NetworkEvent.Context> context,
            BiConsumer<T, Supplier<NetworkEvent.Context>> handler) {
        try (payload) {
            if (payload.raw().isPresent()) {
                throw new IllegalArgumentException("Raw attachment is not valid for "
                        + payload.protobuf().getClass().getName());
            }
            handler.accept(payload.protobuf(), context);
        }
    }
}
