package com.elfmcys.ysm.format.schema.model.views;

import com.elfmcys.ysm.format.schema.file.AssetFileView;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.proto.mixel.common.Program;
import com.elfmcys.ysm.proto.mixel.common.StringPair;
import com.elfmcys.ysm.proto.mixel.manifest.asset.RenderTarget;
import com.elfmcys.ysm.proto.mixel.manifest.asset.RenderTargetKind;
import com.elfmcys.ysm.proto.mixel.manifest.info.Author;
import com.elfmcys.ysm.proto.mixel.manifest.info.ConfigForms;
import com.elfmcys.ysm.proto.mixel.manifest.info.ExtraAnimationButton;
import com.elfmcys.ysm.proto.mixel.manifest.info.ExtraAnimationClassify;
import com.elfmcys.ysm.proto.mixel.manifest.info.Info;
import com.elfmcys.ysm.proto.mixel.manifest.info.LanguageFile;
import com.elfmcys.ysm.proto.mixel.manifest.info.License;
import com.elfmcys.ysm.proto.mixel.manifest.info.Metadata;
import com.elfmcys.ysm.proto.mixel.manifest.info.ModelSettings;
import com.elfmcys.ysm.proto.mixel.manifest.info.ModelStats;
import com.elfmcys.ysm.proto.mixel.manifest.info.Properties;
import com.elfmcys.ysm.proto.mixel.manifest.info.Settings;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelViewPresenceTest {
    @Test
    void acceptsAbsentDisplayMetadataAndAuthorAvatar() {
        var info = infoBuilder().clearMetadata().build();
        var author = Author.newBuilder().build();

        var view = view(info, player());

        assertFalse(view.hasMetadata());
        assertNull(view.getMetadata());
        assertFalse(author.hasAvatar());
        assertTrue(author.avatar().isEmpty());
    }

    @Test
    void treatsEmptyInfoCollectionsAsEmptyWithoutMutatingMessage() {
        var info = emptyInfo();

        var view = view(info, player());

        assertEquals("fallback", view.translateOr("missing", "en_us", "fallback"));
        assertEquals(0, info.languageFiles().size());
        assertEquals(0, info.metadataUnsafe().authors().size());
    }

    @Test
    void translationWithMissingFallbackRemainsRenderable() {
        var view = view(emptyInfo(), player());

        assertEquals("", view.translateOr("missing", "zh_cn", null));
    }

    @Test
    void treatsEmptyRenderTargetCollectionsWithoutMutatingMessage() {
        var descriptor = player();

        var view = new RenderTargetView(null, descriptor);

        assertEquals("player", view.id());
        assertEquals(RenderTargetKind.RENDER_TARGET_KIND_PLAYER, view.kind());
        assertEquals(0, view.matches().size());
        assertEquals(0, view.getTextureNames().size());
        assertEquals(0, descriptor.match().size());
        assertEquals(0, descriptor.textures().size());
    }

    @Test
    void metadataIndexesReferenceThePublishedProto() {
        var animation = pair("wave", "Wave");
        var button = ExtraAnimationButton.newBuilder()
                .setId("settings")
                .setName("Settings")
                .setSound("")
                .build();
        var classification = ExtraAnimationClassify.newBuilder()
                .setId("social")
                .addExtraAnimation(animation)
                .build();
        var settings = Settings.newBuilder()
                .setDefaultTexture("default")
                .setPreviewAnimation("idle")
                .setDisablePreviewRotation(false)
                .addExtraAnimation(animation)
                .addExtraAnimationButtons(button)
                .addExtraAnimationClassify(classification)
                .build();
        var language = LanguageFile.newBuilder()
                .setLocale("en_us")
                .putEntries("metadata.name", "Published name")
                .build();
        var info = infoBuilder()
                .setSettings(settings)
                .addLanguageFiles(language)
                .build();
        var playerSettings = playerSettings();
        var playerStats = ModelStats.newBuilder()
                .setBones(1)
                .setCubes(2)
                .setFaces(3)
                .build();
        var player = RenderTarget.newBuilder()
                .setTargetId("player")
                .setKind(RenderTargetKind.RENDER_TARGET_KIND_PLAYER)
                .setBlobId(1)
                .setSettings(playerSettings)
                .setStats(playerStats)
                .build();

        var view = view(info, player);

        assertSame(settings, view.getSettings());
        assertSame(playerSettings, view.getPlayerSettings());
        assertSame(playerStats, view.getPlayerStats());
        assertSame(animation, view.getExtraAnimations().get(0));
        assertSame(button, view.getExtraAnimationButtons().get("settings"));
        assertSame(classification, view.getExtraAnimationClassifications().get("social"));
        assertEquals("Published name",
                view.translateOr("metadata.name", "en_us", "fallback"));
        assertThrows(UnsupportedOperationException.class,
                () -> view.getExtraAnimations().add(pair("other", "Other")));
        assertThrows(UnsupportedOperationException.class,
                () -> view.getExtraAnimationButtons().put("other", button));
    }

    @Test
    void rejectsUnknownRouletteFormAtTheMetadataInterface() {
        var form = ConfigForms.newBuilder()
                .setType("unknown")
                .setTitle("")
                .setDescription("")
                .setReadProgram(Program.newBuilder().build())
                .setWriteProgram(Program.newBuilder().build())
                .setStep(0)
                .setMin(0)
                .setMax(0)
                .build();
        var button = ExtraAnimationButton.newBuilder()
                .setId("settings")
                .setName("Settings")
                .setSound("")
                .addConfigForms(form)
                .build();
        var info = infoBuilder()
                .setSettings(Settings.newBuilder()
                        .setDefaultTexture("default")
                        .setPreviewAnimation("idle")
                        .setDisablePreviewRotation(false)
                        .addExtraAnimationButtons(button)
                        .build())
                .build();

        assertThrows(IllegalArgumentException.class,
                () -> view(info, player()));
    }

    private static ModelInfoView view(Info info,
                                      RenderTarget player) {
        return new ModelInfoView(info, player, new AssetFileView(null));
    }

    private static StringPair pair(String key, String value) {
        return StringPair.newBuilder()
                .setKey(key)
                .setValue(value)
                .build();
    }

    private static Info emptyInfo() {
        return infoBuilder().build();
    }

    private static Info.Builder infoBuilder() {
        return Info.newBuilder()
                .setSettings(Settings.newBuilder()
                        .setDefaultTexture("")
                        .setPreviewAnimation("")
                        .setDisablePreviewRotation(false)
                        .build())
                .setMetadata(Metadata.newBuilder()
                        .setName("")
                        .setTips("")
                        .setLicense(License.newBuilder()
                                .setType("")
                                .setDesc("")
                                .build())
                        .build())
                .setProperties(Properties.newBuilder()
                        .setModelId(ByteBuffer.wrap(
                                new byte[Hash256.SIZE]))
                        .setFree(false)
                        .setOriginVer("")
                        .build());
    }

    private static RenderTarget player() {
        return RenderTarget.newBuilder()
                .setTargetId("player")
                .setKind(RenderTargetKind.RENDER_TARGET_KIND_PLAYER)
                .setBlobId(1)
                .setSettings(playerSettings())
                .setStats(ModelStats.newBuilder()
                        .setBones(0)
                        .setCubes(0)
                        .setFaces(0)
                        .build())
                .build();
    }

    private static ModelSettings playerSettings() {
        return ModelSettings.newBuilder()
                .setHeightScale(1.1f)
                .setWidthScale(0.9f)
                .setRenderLayersFirst(true)
                .setForceCulling(false)
                .setGuiNoLighting(false)
                .setMergeMultilineExpr(false)
                .build();
    }
}
