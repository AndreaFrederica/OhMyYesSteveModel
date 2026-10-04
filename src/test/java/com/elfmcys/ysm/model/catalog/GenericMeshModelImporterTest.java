package com.elfmcys.ysm.model.catalog;

import com.elfmcys.ysm.format.schema.model.ModelSchema;
import com.elfmcys.ysm.model.storage.ManagedContainer;
import com.elfmcys.ysm.model.catalog.source.CatalogModelLocation;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.model.domain.ModelPath;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Assumptions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenericMeshModelImporterTest {
    @Test
    void textureAndMotionChangesInvalidateIdentityWithoutChangingThePmx(@TempDir Path temp) throws Exception {
        var source = temp.resolve("avatar.pmx");
        Files.copy(Path.of("runtime/java-mmd/src/test/resources/mmd-oracle/skin.pmx"), source);
        var importer = new GenericMeshModelImporter();
        var original = importer.capture(source).modelHash();
        var pixels = Files.writeString(temp.resolve("color.png"), "first pixels");
        var withTexture = importer.capture(source).modelHash();
        org.junit.jupiter.api.Assertions.assertNotEquals(original, withTexture);
        Files.writeString(pixels, "changed pixels");
        var changedTexture = importer.capture(source).modelHash();
        org.junit.jupiter.api.Assertions.assertNotEquals(withTexture, changedTexture);
        var motion = Files.writeString(temp.resolve("motion.vmd"), "motion");
        org.junit.jupiter.api.Assertions.assertNotEquals(changedTexture, importer.capture(source).modelHash());
        Files.delete(motion);
        assertEquals(changedTexture, importer.capture(source).modelHash());
    }

    @Test void sidecarMetadataUsesTheOrdinaryYsmManifestIncludingAvatarAndLinks(@TempDir Path temp)throws Exception {
        var source=temp.resolve("avatar.pmx");Files.copy(Path.of("runtime/java-mmd/src/test/resources/mmd-oracle/skin.pmx"),source);
        var png=java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a5WQAAAAASUVORK5CYII=");Files.write(temp.resolve("author.png"),png);
        var profile=cc.sirrus.ysmlib.scene.SceneModelProfile.defaults(.08,18,0).withMetadata(new cc.sirrus.ysmlib.scene.SceneModelProfile.Metadata(
            "模型名称","模型说明",new cc.sirrus.ysmlib.scene.SceneModelProfile.License("CC-BY","署名"),java.util.List.of(new cc.sirrus.ysmlib.scene.SceneModelProfile.Author("作者","建模",java.util.List.of(new cc.sirrus.ysmlib.scene.SceneModelProfile.Pair("主页","https://example.invalid")),"备注","author.png")),java.util.List.of(new cc.sirrus.ysmlib.scene.SceneModelProfile.Pair("发布页","https://example.invalid/model"))));
        Files.write(temp.resolve("avatar.pmx.omysm.json"),cc.sirrus.ysmlib.YsmRuntime.scenes().writeModelProfile(profile).copy());
        var importer=new GenericMeshModelImporter();var result=importer.convert(importer.capture(source),temp.resolve("out"));
        var container=ManagedContainer.openDirect(result.stagedContainer(),new CatalogModelLocation(CatalogRootKind.CUSTOM,new ModelPath("avatar.pmx")));
        try{var metadata=container.modelFile().getManifest().info().metadataUnsafe();assertEquals("模型名称",metadata.name());assertEquals("模型说明",metadata.tipsUnsafe());assertEquals("CC-BY",metadata.license().type());
            assertEquals("建模",metadata.authors().get(0).role());assertEquals("主页",metadata.authors().get(0).contacts().get(0).key());assertEquals("发布页",metadata.links().get(0).key());assertTrue(metadata.authors().get(0).avatarUnsafe().blobId()>1);
        }finally{container.representation().close();}
    }
    @Test void sidecarChangesIdentityAndTravelsInsideThePackage(@TempDir Path temp) throws Exception {
        var source=temp.resolve("avatar.pmx");Files.copy(Path.of("runtime/java-mmd/src/test/resources/mmd-oracle/skin.pmx"),source);
        var importer=new GenericMeshModelImporter();var original=importer.capture(source);
        var before=com.elfmcys.ysm.model.catalog.source.SourceStamp.captureFile(source);
        var profile=cc.sirrus.ysmlib.scene.SceneModelProfile.defaults(.1,18,0);
        Files.write(source.resolveSibling("avatar.pmx.omysm.json"),cc.sirrus.ysmlib.YsmRuntime.scenes().writeModelProfile(profile).copy());
        var captured=importer.capture(source);
        org.junit.jupiter.api.Assertions.assertNotEquals(original.modelHash(),captured.modelHash());
        org.junit.jupiter.api.Assertions.assertNotEquals(before,com.elfmcys.ysm.model.catalog.source.SourceStamp.captureFile(source));
        assertEquals(captured.modelHash(),importer.capture(source).modelHash());
        var result=importer.convert(captured,temp.resolve("out"));
        var container=ManagedContainer.openDirect(result.stagedContainer(),new CatalogModelLocation(CatalogRootKind.CUSTOM,new ModelPath("avatar.pmx")));
        try {
            var scene=container.modelFile().getPlayer().readScenePackage(()->false,container.chunks(),GenericMeshModelImporter.LIMITS);
            assertEquals(profile,cc.sirrus.ysmlib.YsmRuntime.scenes().readModelProfile(scene.files().get(cc.sirrus.ysmlib.scene.SceneModelProfile.PACKAGE_PATH)));
        } finally {container.representation().close();}
    }
    @Test
    void gltfSourceIsConvertedToCachedGeneralMeshContainer(@TempDir Path temp)
            throws Exception {
        var source = temp.resolve("avatar.gltf");
        try (var input = getClass().getResourceAsStream(
                "/three-vrm-oracle/materials-avatar.gltf")) {
            Files.copy(input, source, StandardCopyOption.REPLACE_EXISTING);
        }
        var importer = new GenericMeshModelImporter();
        var captured = importer.capture(source);
        var result = importer.convert(captured, temp.resolve("staging"));
        assertTrue(Files.isRegularFile(result.stagedContainer()));
        var location = new CatalogModelLocation(CatalogRootKind.CUSTOM,
                new ModelPath("avatar.gltf"));
        var container = ManagedContainer.openDirect(result.stagedContainer(), location);
        try {
            assertEquals(ModelSchema.GENERAL_MESH, container.modelFile().schema());
            assertEquals(captured.modelHash(), container.modelId());
            var stats = container.modelFile().getMetadata().getPlayerStats();
            assertTrue(stats.meshes() > 0);
            assertTrue(stats.materials() > 0);
            assertTrue(stats.textures() >= 0);
            assertTrue(stats.faces() > 0);
            var scene = container.modelFile().getPlayer().readScenePackage(
                    () -> false, container.chunks(), GenericMeshModelImporter.LIMITS);
            assertEquals("avatar.gltf", scene.model().path());
        } finally {
            container.representation().close();
        }
    }

    @Test
    void convertedMmdManifestPublishesExactPlayableWhitelist(@TempDir Path temp) throws Exception {
        var fixture=Path.of("runtime/java-mmd/src/test/resources/mmd-oracle");
        Files.copy(fixture.resolve("skin.pmx"),temp.resolve("avatar.pmx"));
        Files.copy(fixture.resolve("skin.vmd"),temp.resolve("dance.vmd"));
        var importer=new GenericMeshModelImporter();
        var result=importer.convert(importer.capture(temp.resolve("avatar.pmx")),temp.resolve("staging"));
        var container=ManagedContainer.openDirect(result.stagedContainer(),new CatalogModelLocation(CatalogRootKind.CUSTOM,new ModelPath("avatar.pmx")));
        try {
            var source=container.modelFile().getPlayer().readScenePackage(()->false,container.chunks(),GenericMeshModelImporter.LIMITS);
            var assets=cc.sirrus.ysmlib.YsmRuntime.scenes().loadPackage(source,GenericMeshModelImporter.LIMITS);
            var clips=cc.sirrus.ysmlib.YsmRuntime.scenes().animations(assets,30);
            var declared=container.modelFile().getManifest().info().settings().extraAnimation();
            var ids=declared.stream().map(a->a.key()).collect(java.util.stream.Collectors.toSet());
            assertEquals(clips.size()*2,ids.size());assertEquals("dance.vmd",clips.get(0).sourcePath());
            for(var clip:clips) for(boolean loop:new boolean[]{false,true})
                assertTrue(ids.contains(com.elfmcys.ysm.model.domain.SceneActionId.of(clip,loop)));
        } finally { container.representation().close(); }
    }

    @Test
    void unityPackageSelectsEmbeddedFbxWhenUserCorpusIsPresent(@TempDir Path temp)
            throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("ysm.realUnityPackageTest"));
        var source = Path.of("D:/Projects/ysm/vrc-mmd/vrc-已经解压/Kikyo_ver1.01/Kikyo.unitypackage");
        Assumptions.assumeTrue(Files.isRegularFile(source));
        var importer = new GenericMeshModelImporter();
        var captured = importer.capture(source);
        var result = importer.convert(captured, temp.resolve("staging"));
        var location = new CatalogModelLocation(CatalogRootKind.CUSTOM,
                new ModelPath("Kikyo.unitypackage"));
        var container = ManagedContainer.openDirect(result.stagedContainer(), location);
        try {
            assertEquals(ModelSchema.GENERAL_MESH, container.modelFile().schema());
            var scene = container.modelFile().getPlayer().readScenePackage(
                    () -> false, container.chunks(), GenericMeshModelImporter.LIMITS);
            assertEquals(cc.sirrus.ysmlib.scene.ScenePackage.Format.FBX,
                    scene.model().format());
            assertTrue(scene.model().path().endsWith("Kikyo.fbx"));
        } finally {
            container.representation().close();
        }
    }
}
