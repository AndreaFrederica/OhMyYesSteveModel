package com.elfmcys.ysm.model.catalog;

import com.elfmcys.ysm.format.schema.model.ModelSchema;
import com.elfmcys.ysm.model.storage.ManagedContainer;
import com.elfmcys.ysm.model.catalog.source.CatalogModelLocation;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.model.domain.ModelPath;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Assumptions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenericMeshModelImporterTest {
    @Test void vrmNativeMetadataAndLargeThumbnailSurviveImport(@TempDir Path temp)throws Exception {
        var json=com.google.gson.JsonParser.parseString(Files.readString(Path.of("runtime/java-vrm/src/test/resources/three-vrm-oracle/retarget-avatar.gltf"))).getAsJsonObject();
        var meta=new com.google.gson.JsonObject();meta.addProperty("name","Native avatar");meta.add("authors",com.google.gson.JsonParser.parseString("[\"Creator\"]"));meta.addProperty("licenseUrl","https://vrm.dev/licenses/1.0/");meta.addProperty("thumbnailImage",0);
        json.getAsJsonObject("extensions").getAsJsonObject("VRMC_vrm").add("meta",meta);
        var image=new java.awt.image.BufferedImage(2048,2048,java.awt.image.BufferedImage.TYPE_INT_ARGB);var out=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"png",out);
        var images=new com.google.gson.JsonArray();var entry=new com.google.gson.JsonObject();entry.addProperty("uri","data:image/png;base64,"+java.util.Base64.getEncoder().encodeToString(out.toByteArray()));images.add(entry);json.add("images",images);
        var source=temp.resolve("avatar.vrm");Files.writeString(source,json.toString());var importer=new GenericMeshModelImporter();var converted=importer.convert(importer.capture(source),temp.resolve("out"));
        var container=ManagedContainer.openDirect(converted.stagedContainer(),new CatalogModelLocation(CatalogRootKind.CUSTOM,new ModelPath("avatar.vrm")));
        try{
            var model=container.modelFile();var metadata=model.getManifest().info().metadataUnsafe();assertEquals("Native avatar",metadata.name());assertEquals("Creator",metadata.authors().get(0).name());assertEquals("https://vrm.dev/licenses/1.0/",metadata.license().type());
            assertEquals(com.elfmcys.ysm.proto.mixel.manifest.info.PreviewSource.PREVIEW_SOURCE_RAW,model.getThumbnailPreviewSource());
            try(var thumbnail=model.readThumbnail(()->false,container.chunks());var icon=model.readIcon(()->false,container.chunks())){assertTrue(thumbnail.width()<=156&&thumbnail.height()<=270);assertTrue(icon.width()<=192&&icon.height()<=192);}
        }finally{container.representation().close();}
    }

    @Test
    void zipWorkspaceIsReadDirectlyWithOptionalSidecars(@TempDir Path temp) throws Exception {
        var sample = Path.of("runtime/wasm-fbx/src/test/resources/ufbx/maya_cube_7500_ascii.fbx");
        var source = temp.resolve("avatar-workspace.zip");
        try (var output = new ZipOutputStream(Files.newOutputStream(source))) {
            output.putNextEntry(new ZipEntry("Workspace/Avatar.fbx"));
            output.write(Files.readAllBytes(sample));
            output.closeEntry();
        }
        Files.writeString(temp.resolve("avatar-workspace.zip.fbxmap.json"),
                "{\"schemaVersion\":1,\"sourceHash\":\"\",\"skinByMeshElement\":{}}\n");
        Files.writeString(temp.resolve("avatar-workspace.zip.resources.json"),
                "{\"schemaVersion\":1,\"resources\":{}}\n");
        var importer = new GenericMeshModelImporter();
        var captured = importer.capture(source);
        assertTrue(captured.archiveSource());
        var result = importer.convert(captured, temp.resolve("staging"));
        assertTrue(Files.isRegularFile(result.stagedContainer()));
    }

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

    @Test
    void optionalFbxResourceSidecarIsPartOfSourceIdentity(@TempDir Path temp) throws Exception {
        var source=temp.resolve("avatar.fbx");
        Files.copy(Path.of("runtime/wasm-fbx/src/test/resources/ufbx/maya_cube_7500_ascii.fbx"),source);
        var importer=new GenericMeshModelImporter();
        var without=importer.capture(source);
        Files.writeString(temp.resolve("avatar.fbx.resources.json"),
                "{\"schemaVersion\":1,\"resources\":{}}\n");
        var with=importer.capture(source);
        assertTrue(with.fbxResources()!=null);
        org.junit.jupiter.api.Assertions.assertNotEquals(without.modelHash(),with.modelHash());
        Files.writeString(temp.resolve("avatar.fbx.resources.json"),
                "{\"schemaVersion\":1,\"resources\":{\"missing.png\":\"Texture/missing.png\"}}\n");
        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,() -> importer.capture(source));
    }

    @Test
    void fbxResourceSidecarProducesExplicitRelocations(@TempDir Path temp) throws Exception {
        var source=temp.resolve("avatar.fbx");
        Files.copy(Path.of("runtime/wasm-fbx/src/test/resources/ufbx/blender_293_textures_7400_binary.fbx"),source);
        var png=java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a5WQAAAAASUVORK5CYII=");
        var textureDir=Files.createDirectories(temp.resolve("Texture"));
        for(var name:List.of("checkerboard_diffuse.png","checkerboard_weight.png","checkerboard_emissive.png","checkerboard_roughness.png","checkerboard_metallic.png"))Files.write(textureDir.resolve(name),png);
        Files.writeString(temp.resolve("avatar.fbx.resources.json"),"{\"schemaVersion\":1,\"resources\":{"+
                "\"textures\\\\checkerboard_diffuse.png\":\"Texture/checkerboard_diffuse.png\","+
                "\"textures\\\\checkerboard_weight.png\":\"Texture/checkerboard_weight.png\","+
                "\"textures\\\\checkerboard_emissive.png\":\"Texture/checkerboard_emissive.png\","+
                "\"textures\\\\checkerboard_roughness.png\":\"Texture/checkerboard_roughness.png\","+
                "\"textures\\\\checkerboard_metallic.png\":\"Texture/checkerboard_metallic.png\"}}\n");
        var importer=new GenericMeshModelImporter();
        var result=importer.convert(importer.capture(source),temp.resolve("out"));
        var container=ManagedContainer.openDirect(result.stagedContainer(),new CatalogModelLocation(CatalogRootKind.CUSTOM,new ModelPath("avatar.fbx")));
        try {
            var scene=container.modelFile().getPlayer().readScenePackage(() -> false,container.chunks(),GenericMeshModelImporter.LIMITS);
            assertEquals(5,scene.relocations().size());
            assertTrue(scene.relocations().stream().anyMatch(r -> r.target().equals("Texture/checkerboard_diffuse.png")));
            assertTrue(scene.files().containsKey("_omysm/fbx-resources.json"));
        } finally {container.representation().close();}
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
            assertTrue(scene.files().containsKey("_omysm/fbx-mapping.json"));
            var mapping=cc.sirrus.ysmlib.YsmRuntime.scenes().readFbxMapping(
                    scene.files().get("_omysm/fbx-mapping.json"));
            assertTrue(mapping.materialByElement().size() >= 4);
            assertTrue(scene.files().keySet().stream().anyMatch(path -> path.endsWith("Kikyo_Body.png")));
        } finally {
            container.representation().close();
        }
    }

    @Test
    void unityPackageCanUseResourceSidecarWithoutManualUnpack(@TempDir Path temp)
            throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("ysm.realUnityPackageTest")
                || Boolean.parseBoolean(System.getenv("YSM_REAL_UNITY_PACKAGE_TEST")));
        var original = Path.of("D:/Projects/ysm/vrc-mmd/vrc/10114-flandre-scarlet-30-sfw-2.unitypackage");
        Assumptions.assumeTrue(Files.isRegularFile(original));
        // Copying the single archive keeps this test isolated while exercising
        // the original Unity tar stream. The importer must read it directly;
        // the test never creates an extracted workspace.
        var source = temp.resolve(original.getFileName().toString());
        Files.copy(original, source);
        Files.writeString(source.resolveSibling(source.getFileName() + ".resources.json"),
                "{\"schemaVersion\":1,\"resources\":{" +
                        "\"shoes/m_cf_socks_innocent (Instance) (Instance)_(-790680).png\":\"Assets/Flandre Scarlet/Textures+Normals/Socks.png\"," +
                        "\"texs/Body.png\":\"Assets/Flandre Scarlet/Textures+Normals/BodySFW.png\"," +
                        "\"texs/Face.png\":\"Assets/Flandre Scarlet/Textures+Normals/Face.png\"," +
                        "\"texs/Wings.png\":\"Assets/Flandre Scarlet/Textures+Normals/Wings.png\"," +
                        "\"texs/Shoes.png\":\"Assets/Flandre Scarlet/Textures+Normals/Shoes.png\"," +
                        "\"Atlas_38305.png\":\"Assets/Flandre Scarlet/Textures+Normals/Hair.png\"," +
                        "\"Atlas_82828.png\":\"Assets/Flandre Scarlet/Textures+Normals/Dress.png\"," +
                        "\"Atlas_29240.png\":\"Assets/Flandre Scarlet/Textures+Normals/Underwear.png\"," +
                        "\"Atlas_52254.png\":\"Assets/Flandre Scarlet/Textures+Normals/TransparentParts.png\"}}\n");
        Files.writeString(source.resolveSibling(source.getFileName() + ".fbxmap.json"),
                "{\"schemaVersion\":1,\"sourceHash\":\"\",\"skinByMeshElement\":{}}\n");
        var importer = new GenericMeshModelImporter();
        var captured = importer.capture(source);
        assertTrue(captured.fbxResources() != null);
        assertTrue(captured.fbxMapping() != null);
        var result = importer.convert(captured, temp.resolve("staging"));
        var location = new CatalogModelLocation(CatalogRootKind.CUSTOM,
                new ModelPath(source.getFileName().toString()));
        var container = ManagedContainer.openDirect(result.stagedContainer(), location);
        try {
            var scene = container.modelFile().getPlayer().readScenePackage(
                    () -> false, container.chunks(), GenericMeshModelImporter.LIMITS);
            assertTrue(scene.files().containsKey("_omysm/fbx-resources.json"));
            assertTrue(scene.files().containsKey("_omysm/fbx-mapping.json"));
            assertTrue(scene.relocations().stream().anyMatch(relocation ->
                    relocation.target().endsWith("Textures+Normals/Socks.png")));
        } finally {
            container.representation().close();
        }
    }
}
