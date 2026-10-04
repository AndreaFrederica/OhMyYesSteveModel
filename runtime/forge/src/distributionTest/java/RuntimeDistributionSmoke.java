import cc.sirrus.ysmlib.YsmRuntime;
import cc.sirrus.ysmlib.archive.*;
import cc.sirrus.ysmlib.audio.SupportedAudioProbe;
import java.nio.file.*;
import java.util.Arrays;
import java.nio.ByteBuffer;
import java.util.HexFormat;
import java.util.zip.*;

/** Launched with only the actual shaded prerequisite JAR and these test classes. */
public final class RuntimeDistributionSmoke {
    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("ysm-runtime-distribution-");
        Path zip = dir.resolve("model.zip"), seven = dir.resolve("model.7z");
        try {
            byte[] expected = {1,2,3,4};
            var surface = YsmRuntime.scenes().prepareGltfSurface(new cc.sirrus.ysmlib.scene.MeshAsset.Primitive(
                    cc.sirrus.ysmlib.scene.MeshAsset.Topology.TRIANGLES, java.util.Map.of(
                    "POSITION", new cc.sirrus.ysmlib.scene.MeshAsset.Attribute(3,new cc.sirrus.ysmlib.scene.FloatData(0,0,0,1,0,0,0,1,0)),
                    "TEXCOORD_5", new cc.sirrus.ysmlib.scene.MeshAsset.Attribute(2,new cc.sirrus.ysmlib.scene.FloatData(0,0,1,0,0,1))),
                    new cc.sirrus.ysmlib.scene.IntData(0,1,2),-1,null,java.util.List.of()),5,cc.sirrus.ysmlib.scene.io.ReadLimits.DEFAULT);
            var tangent = surface.attributes().get("TANGENT").values();
            if(tangent.get(0)!=1 || tangent.get(3)!=1 || surface.attributes().get("NORMAL").values().get(2)!=1)
                throw new AssertionError("Shaded managed MikkTSpace surface generation failed");
            byte[] digest = YsmRuntime.hashes().blake3(ByteBuffer.allocate(0));
            if (!HexFormat.of().formatHex(digest).equals("af1349b9f5f9a1a6a0404dea36dcc9499bcb25c9adc112b7cc9a93cae41f3262")) {
                throw new AssertionError("Shaded BLAKE3 implementation mismatch");
            }
            byte[] compressed = YsmRuntime.compression().compress(ByteBuffer.wrap(expected), 3, 1024);
            if (!Arrays.equals(expected, YsmRuntime.compression().decompress(ByteBuffer.wrap(compressed), 4, 1024))) {
                throw new AssertionError("Shaded Zstandard implementation mismatch");
            }
            try (var out = new ZipOutputStream(Files.newOutputStream(zip))) {
                out.putNextEntry(new ZipEntry("test")); out.write(expected); out.closeEntry();
            }
            verify(zip, ArchiveFormat.ZIP, expected);
            // Reflection deliberately names the shaded implementation, verifying relocation.
            String prefix = "cc.sirrus.ysmlib.internal.apache.commons.compress.archivers.";
            Class<?> writerType = Class.forName(prefix + "sevenz.SevenZOutputFile");
            Class<?> entryType = Class.forName(prefix + "sevenz.SevenZArchiveEntry");
            Object entry = entryType.getConstructor().newInstance();
            entryType.getMethod("setName", String.class).invoke(entry, "test");
            try (var writer = (AutoCloseable) writerType.getConstructor(java.io.File.class).newInstance(seven.toFile())) {
                writerType.getMethod("putArchiveEntry", Class.forName(prefix + "ArchiveEntry")).invoke(writer, entry);
                writerType.getMethod("write", byte[].class).invoke(writer, (Object) expected);
                writerType.getMethod("closeArchiveEntry").invoke(writer);
            }
            verify(seven, ArchiveFormat.SEVEN_ZIP, expected);
            try (var input = RuntimeDistributionSmoke.class.getResourceAsStream("/alpha.avif");
                 var reference = RuntimeDistributionSmoke.class.getResourceAsStream("/alpha.rgba")) {
                var avif = ByteBuffer.wrap(input.readAllBytes());
                var info = YsmRuntime.images().probe(avif);
                byte[] expectedPixels = reference.readAllBytes(), actual = YsmRuntime.images().decode(avif, info);
                if (actual.length != expectedPixels.length) throw new AssertionError("AVIF size mismatch");
                for (int i = 0; i < actual.length; i++) {
                    if (Math.abs((actual[i] & 255) - (expectedPixels[i] & 255)) > (i % 4 == 3 ? 0 : 2))
                        throw new AssertionError("Shaded JVM AVIF decoder mismatch at component " + i);
                }
            }
            try {
                Class.forName("org.apache.commons.compress.archivers.sevenz.SevenZFile");
                throw new AssertionError("Unrelocated dependency leaked into distribution");
            } catch (ClassNotFoundException expectedAbsence) { }
            for(String name : new String[]{"gray-alpha.tga","origin-30.tga","rle8.bmp","rgba.webp"}) {
                try(var input=RuntimeDistributionSmoke.class.getResourceAsStream("/scene-image-oracle/"+name);
                    var reference=RuntimeDistributionSmoke.class.getResourceAsStream("/scene-image-oracle/"+name+".rgba")) {
                    var decoded=YsmRuntime.scenes().readImage(new cc.sirrus.ysmlib.scene.ByteData(input.readAllBytes()),
                            name,cc.sirrus.ysmlib.scene.io.ReadLimits.DEFAULT);
                    var pixels=reference.readAllBytes();
                    if(decoded.rgba().size()!=pixels.length) throw new AssertionError("Scene image size mismatch");
                    for(int i=0;i<pixels.length;i++) if(Math.abs(decoded.rgba().get(i)-(pixels[i]&255)/255f)>1e-6)
                        throw new AssertionError("Shaded scene image sample mismatch: "+name+" at "+i);
                }
            }
            for (String codec : new String[]{"opus", "vorbis"}) {
                try (var input = RuntimeDistributionSmoke.class.getResourceAsStream("/" + codec + "-mono.ogg")) {
                    var data = ByteBuffer.wrap(input.readAllBytes());
                    var media = SupportedAudioProbe.inspect(data).media();
                    long count = 0;
                    try (var decoder = YsmRuntime.audio().open(data, media)) {
                        var output = ByteBuffer.allocate(2048); int read;
                        while ((read = decoder.read(output.clear())) > 0) count += read;
                    }
                    if (count != 120002) throw new AssertionError("Shaded " + codec + " frame count mismatch");
                }
            }
            try (var input = RuntimeDistributionSmoke.class.getResourceAsStream("/legacy_v3_dynamic_vector.ysm")) {
                byte[] envelope = input.readAllBytes();
                if (YsmRuntime.v3().decode(ByteBuffer.wrap(envelope), 12004).length != 12004)
                    throw new AssertionError("Shaded V3 envelope mismatch");
                Path source = Files.write(dir.resolve("original.ysm"), envelope);
                Path workspace = new cc.sirrus.ysmlib.v3d.V3dCache(YsmRuntime.v3())
                        .materialize(source, dir.resolve("v3d"));
                cc.sirrus.ysmlib.v3d.V3dCache.validate(workspace);
                Path restored = dir.resolve("restored.ysm");
                cc.sirrus.ysmlib.v3d.V3dCache.restoreOriginal(workspace, restored);
                if (Files.mismatch(source, restored) != -1) throw new AssertionError("Shaded V3D restore mismatch");
            }
            try (var input = RuntimeDistributionSmoke.class.getResourceAsStream("/historical/v32.wire")) {
                var paths = new java.util.HashSet<String>();
                YsmRuntime.decodedWorkspace().materialize(ByteBuffer.wrap(input.readAllBytes()), (path, bytes) -> paths.add(path));
                if (!paths.contains("legacy/model.json") || paths.stream().noneMatch(p -> p.endsWith(".tga")))
                    throw new AssertionError("Shaded historical workspace is incomplete");
            }
            try (var physics = YsmRuntime.physics().createWorld(cc.sirrus.ysmlib.scene.physics.PhysicsSpec.World.defaults().withoutReplay())) {
                physics.addBody(new cc.sirrus.ysmlib.scene.physics.PhysicsSpec.Body(
                    cc.sirrus.ysmlib.scene.physics.PhysicsSpec.Shape.SPHERE,new cc.sirrus.ysmlib.scene.Vec3(.5f,0,0),
                    new cc.sirrus.ysmlib.scene.Pose(new cc.sirrus.ysmlib.scene.Vec3(0,10,0),cc.sirrus.ysmlib.scene.Rotation.IDENTITY),
                    cc.sirrus.ysmlib.scene.physics.PhysicsSpec.Motion.DYNAMIC,1,0,0,0,.5f,.04f,1,0xffff));
                for (int i=0;i<60;i++) physics.step();
                if (Math.abs(physics.bodyStates().get(0).pose().position().y()-5.018333f)>.001)
                    throw new AssertionError("Shaded JVM Bullet reactor mismatch");
            }
            var gltf = YsmRuntime.scenes().readGltf(new cc.sirrus.ysmlib.scene.ByteData("""
                {"asset":{"version":"2.0"},"nodes":[{"translation":[1,2,3]}],"scenes":[{"nodes":[0]}],"scene":0}
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                cc.sirrus.ysmlib.scene.io.AssetResolver.NONE,cc.sirrus.ysmlib.scene.io.ReadLimits.DEFAULT);
            var pose = YsmRuntime.scenes().evaluator(gltf.scene()).evaluate(null,0);
            {
                var service=YsmRuntime.scenes();var limits=cc.sirrus.ysmlib.scene.io.ReadLimits.DEFAULT;
                for(int index=0;index<10;index++) {
                    var image=service.readImage(service.sharedMmdToon(index),"bmp",limits);
                    if(image.width()!=32 || image.height()!=32) throw new AssertionError("Shaded shared MMD toon mismatch");
                }
                try(var modelInput=RuntimeDistributionSmoke.class.getResourceAsStream("/mmd-oracle/pmd.pmd")) {
                    var modelBytes=new cc.sirrus.ysmlib.scene.ByteData(modelInput.readAllBytes());
                    var pmd=service.readPmd(modelBytes,limits);
                    var profile=service.materials(pmd);
                    if(profile.definitions().get(0).toon().fallbackToon()!=0 || profile.rest().materials().size()!=pmd.materials().size())
                        throw new AssertionError("Shaded PMD material profile mismatch");
                    var pack=new cc.sirrus.ysmlib.scene.ScenePackage(
                        new cc.sirrus.ysmlib.scene.ScenePackage.Source("model","model.pmd",cc.sirrus.ysmlib.scene.ScenePackage.Format.PMD),
                        new cc.sirrus.ysmlib.scene.ScenePackage.Settings(1,-1,cc.sirrus.ysmlib.scene.fbx.FbxEvaluation.SkinSpace.BIND_WORLD),
                        java.util.List.of(),java.util.List.of(),java.util.Map.of("model.pmd",modelBytes));
                    var images=service.images(service.loadPackage(pack,limits),limits);
                    if(images.require(profile.definitions().get(0).toon().key("model.pmd")).width()!=32)
                        throw new AssertionError("Shaded package default MMD toon mismatch");
                }
            }
            {
                var service=YsmRuntime.scenes();var limits=cc.sirrus.ysmlib.scene.io.ReadLimits.DEFAULT;
                var packageSource=new cc.sirrus.ysmlib.scene.ScenePackage(
                    new cc.sirrus.ysmlib.scene.ScenePackage.Source("model","models/avatar.gltf",cc.sirrus.ysmlib.scene.ScenePackage.Format.GLTF),
                    new cc.sirrus.ysmlib.scene.ScenePackage.Settings(1,0,cc.sirrus.ysmlib.scene.fbx.FbxEvaluation.SkinSpace.BIND_WORLD),
                    java.util.List.of(),java.util.List.of(),java.util.Map.of("models/avatar.gltf",gltf.source()));
                var packageBytes=service.writePackage(packageSource,limits);var reopened=service.readPackage(packageBytes,limits);
                var packageAssets=service.loadPackage(reopened,limits);
                var scene=service.readGltf(reopened.files().get(reopened.model().path()),
                    service.resolver(reopened,reopened.model().path(),cc.sirrus.ysmlib.scene.ScenePackage.ReferenceSyntax.URI),limits);
                if(!(packageAssets.model() instanceof cc.sirrus.ysmlib.scene.ScenePackageAssets.Gltf)
                    ||!packageSource.equals(reopened)||!service.evaluator(scene.scene()).evaluate(null,0).equals(pose))
                    throw new AssertionError("Shaded source package roundtrip mismatch");
                try(var packagePlayer=service.playback(packageAssets,cc.sirrus.ysmlib.scene.ScenePackagePlayback.Selection.REST,
                    cc.sirrus.ysmlib.scene.ScenePackagePlayback.Settings.preview(),limits)) {
                    var packageFrame=packagePlayer.seek(0);
                    if(!(packageFrame.details() instanceof cc.sirrus.ysmlib.scene.ScenePackagePlayback.Scene detail) || !detail.pose().equals(pose))
                        throw new AssertionError("Shaded package playback mismatch");
                }
            }
            if (!pose.globalMatrices().get(0).transformPoint(cc.sirrus.ysmlib.scene.Vec3.ZERO).equals(new cc.sirrus.ysmlib.scene.Vec3(1,2,3)))
                throw new AssertionError("Shaded general scene evaluation mismatch");
            var vpd = YsmRuntime.scenes().readVpd(new cc.sirrus.ysmlib.scene.ByteData("""
                Vocaloid Pose Data file
                test.osm;
                1;
                Bone0{center
                1,2,3;
                0,0,0,1;
                }
                """.getBytes(java.nio.charset.StandardCharsets.US_ASCII)),cc.sirrus.ysmlib.scene.io.ReadLimits.DEFAULT);
            if (YsmRuntime.scenes().sample(YsmRuntime.scenes().vpdAnimation(vpd),10).channels().size()!=2)
                throw new AssertionError("Shaded MMD pose evaluation mismatch");
            try (var modelInput = RuntimeDistributionSmoke.class.getResourceAsStream("/mmd-oracle/skin.pmx");
                 var motionInput = RuntimeDistributionSmoke.class.getResourceAsStream("/mmd-oracle/skin.vmd")) {
                var scenes = YsmRuntime.scenes();
                var limits = cc.sirrus.ysmlib.scene.io.ReadLimits.DEFAULT;
                var model = scenes.readPmx(new cc.sirrus.ysmlib.scene.ByteData(modelInput.readAllBytes()), limits);
                var motion = scenes.readVmd(new cc.sirrus.ysmlib.scene.ByteData(motionInput.readAllBytes()), limits);
                try (var player = scenes.playback(model, scenes.vmdAnimation(motion).clip(),
                        cc.sirrus.ysmlib.scene.mmd.MmdPlayback.Settings.preview(), java.util.Map.of())) {
                    var first = player.seek(1);
                    player.seek(0);
                    var replay = player.seek(1);
                    var expectedVertices = first.primitives().get(0).get("POSITION").values().copy();
                    var actualVertices = replay.primitives().get(0).get("POSITION").values().copy();
                    if (!Arrays.equals(expectedVertices, actualVertices) || first.pose().bones().isEmpty())
                        throw new AssertionError("Shaded MMD playback/replay mismatch");
                }
            }
            try (var projectInput = RuntimeDistributionSmoke.class.getResourceAsStream("/pmm-oracle/project.pmm")) {
                var project = YsmRuntime.scenes().readPmm(new cc.sirrus.ysmlib.scene.ByteData(projectInput.readAllBytes()),
                    cc.sirrus.ysmlib.scene.mmd.PmmModelResolver.NONE,cc.sirrus.ysmlib.scene.io.ReadLimits.DEFAULT);
                var animations = YsmRuntime.scenes().pmmAnimations(project);
                if (project.assetReferences().size()!=6 || animations.models().size()!=2 || animations.scene().clip().tracks().isEmpty())
                    throw new AssertionError("Shaded PMM project import mismatch");
            }
            var bvh = YsmRuntime.scenes().readBvh(new cc.sirrus.ysmlib.scene.ByteData(
                "HIERARCHY ROOT root { OFFSET 1 2 3 CHANNELS 1 Zrotation } MOTION Frames:2 Frame Time:.1 0 360".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                cc.sirrus.ysmlib.scene.io.ReadLimits.DEFAULT);
            var bvhPose = YsmRuntime.scenes().evaluator(bvh,cc.sirrus.ysmlib.scene.SceneAsset.Coordinates.GLTF)
                .evaluate(.05,cc.sirrus.ysmlib.scene.bvh.BvhEvaluation.Sampling.LINEAR_CHANNELS);
            if (Math.abs(bvhPose.globalMatrices().get(0).transformDirection(new cc.sirrus.ysmlib.scene.Vec3(1,0,0)).x()+1)>1e-5)
                throw new AssertionError("Shaded BVH full-turn evaluation mismatch");
            try (var avatarInput=RuntimeDistributionSmoke.class.getResourceAsStream("/three-vrm-oracle/portable-avatar.gltf")) {
                var sceneService=YsmRuntime.scenes();
                var avatar=sceneService.readVrm(new cc.sirrus.ysmlib.scene.ByteData(avatarInput.readAllBytes()),
                    cc.sirrus.ysmlib.scene.io.AssetResolver.NONE,cc.sirrus.ysmlib.scene.io.ReadLimits.DEFAULT);
                var evaluation=sceneService.evaluator(avatar);
                var input=new cc.sirrus.ysmlib.scene.vrm.VrmEvaluation.Input(java.util.Map.of("happy",.7f),java.util.Map.of(),null);
                var player=sceneService.playback(avatar,t->evaluation.evaluate(null,t,input),cc.sirrus.ysmlib.scene.vrm.VrmPlayback.Settings.preview());
                var half=player.seek(.5);player.seek(1);var replay=player.seek(.5);
                if(!half.pose().localMatrices().equals(replay.pose().localMatrices()) || Math.abs(half.expressions().get("happy")-.7f)>1e-6
                    || half.localRotations().get(15).equals(cc.sirrus.ysmlib.scene.Rotation.IDENTITY))
                    throw new AssertionError("Shaded VRM expression/spring/replay mismatch");
            }
            try (var avatarInput=RuntimeDistributionSmoke.class.getResourceAsStream("/three-vrm-oracle/retarget-avatar.gltf");
                 var animationInput=RuntimeDistributionSmoke.class.getResourceAsStream("/three-vrm-oracle/retarget-source.gltf")) {
                var service=YsmRuntime.scenes();var resolver=cc.sirrus.ysmlib.scene.io.AssetResolver.NONE;var limits=cc.sirrus.ysmlib.scene.io.ReadLimits.DEFAULT;
                var avatar=service.readVrm(new cc.sirrus.ysmlib.scene.ByteData(avatarInput.readAllBytes()),resolver,limits);
                var animation=service.readVrma(new cc.sirrus.ysmlib.scene.ByteData(animationInput.readAllBytes()),resolver,limits);
                var evaluated=service.retarget(animation,avatar,cc.sirrus.ysmlib.scene.vrm.VrmaEvaluation.Settings.automatic()).evaluate(1);
                if(Math.abs(evaluated.pose().globalMatrices().get(avatar.humanBones().get("hips")).get(3,0)-.5f)>1e-5
                    || Math.abs(evaluated.expressions().get("happy")-1)>1e-6) throw new AssertionError("Shaded VRMA retarget mismatch");
            }
            try (var avatarInput=RuntimeDistributionSmoke.class.getResourceAsStream("/three-vrm-oracle/first-person-avatar.gltf")) {
                var service=YsmRuntime.scenes();var avatar=service.readVrm(new cc.sirrus.ysmlib.scene.ByteData(avatarInput.readAllBytes()),
                    cc.sirrus.ysmlib.scene.io.AssetResolver.NONE,cc.sirrus.ysmlib.scene.io.ReadLimits.DEFAULT);
                var views=service.firstPerson(avatar);
                var automatic=views.nodes().stream().filter(n->avatar.scene().nodes().get(n.node()).name().equals("auto")).findFirst().orElseThrow();
                if(!Arrays.equals(automatic.firstPerson().get(0).geometry().indices().copy(),new int[]{6,7,8})
                    || automatic.thirdPerson().get(0).geometry().indices().size()!=9) throw new AssertionError("Shaded VRM first-person geometry mismatch");
                var avatarPose=service.evaluator(avatar).evaluate(null,0,cc.sirrus.ysmlib.scene.vrm.VrmEvaluation.Input.NONE).pose();
                var first=service.geometry(avatar,0,true).compile(avatarPose);var third=service.geometry(avatar,0,false).compile(avatarPose);
                var firstDraw=first.draws().stream().filter(d->d.node()==automatic.node()).findFirst().orElseThrow();
                var thirdDraw=third.draws().stream().filter(d->d.node()==automatic.node()).findFirst().orElseThrow();
                if(firstDraw.geometry().primitives().get(0).indices().size()!=3 || thirdDraw.geometry().primitives().get(0).indices().size()!=9
                    || firstDraw.geometry().primitives().get(0).skinning()!=null) throw new AssertionError("Shaded VRM shared draw plan mismatch");
            }
            try (var avatarInput=RuntimeDistributionSmoke.class.getResourceAsStream("/three-vrm-oracle/materials-avatar.gltf")) {
                var service=YsmRuntime.scenes();var avatar=service.readVrm(new cc.sirrus.ysmlib.scene.ByteData(avatarInput.readAllBytes()),
                    cc.sirrus.ysmlib.scene.io.AssetResolver.NONE,cc.sirrus.ysmlib.scene.io.ReadLimits.DEFAULT);
                var materials=service.materials(avatar).evaluate(service.expressions(avatar).evaluate(java.util.Map.of()).materials(),1).materials();
                if(materials.size()!=4 || materials.get(2).shader()!=cc.sirrus.ysmlib.scene.vrm.VrmMaterials.Shader.MTOON_1
                    || !materials.get(2).renderState().depthWrite() || materials.get(3).renderState().depthWrite()
                    || materials.get(2).renderState().queueOffset()!=4) throw new AssertionError("Shaded VRM material evaluation mismatch");
            }
            try(var modelInput=RuntimeDistributionSmoke.class.getResourceAsStream("/ufbx/maya_blend_inbetween_7500_ascii.fbx")) {
                var service=YsmRuntime.scenes();var limits=cc.sirrus.ysmlib.scene.io.ReadLimits.DEFAULT;
                var document=service.readFbx(new cc.sirrus.ysmlib.scene.ByteData(modelInput.readAllBytes()),limits);
                try(var player=service.evaluator(document,limits,cc.sirrus.ysmlib.scene.fbx.FbxEvaluation.Settings.DEFAULT)) {
                    var first=player.evaluate(-1,.5);player.evaluate(-1,2);var replay=player.evaluate(-1,.5);
                    var geometry=service.geometry(document,replay);
                    if(!first.equals(replay) || document.blendShapes().size()!=5 || geometry.draws().isEmpty()
                        || geometry.draws().get(0).geometry().primitives().get(0).indices().size()==0)
                        throw new AssertionError("Shaded FBX source/morph/replay/shared geometry mismatch");
                }
                if(!service.dependencies(document,cc.sirrus.ysmlib.scene.io.AssetResolver.NONE,limits).images().isEmpty())
                    throw new AssertionError("Unexpected FBX texture dependency");
            }
            try(var modelInput=RuntimeDistributionSmoke.class.getResourceAsStream("/blender-oracle/weighted-morph.fbx")) {
                var service=YsmRuntime.scenes();var limits=cc.sirrus.ysmlib.scene.io.ReadLimits.DEFAULT;
                var source=service.readFbx(new cc.sirrus.ysmlib.scene.ByteData(modelInput.readAllBytes()),limits);
                try(var player=service.evaluator(source,limits,cc.sirrus.ysmlib.scene.fbx.FbxEvaluation.Settings.DEFAULT)) {
                    var a=player.evaluate(0,1.0/24);var b=player.evaluate(0,13.0/24);
                    if(a.draws().get(0).positions().equals(b.draws().get(0).positions())
                        || source.skins().get(0).vertices().stream().anyMatch(v->v.count()!=5))
                        throw new AssertionError("Shaded FBX combined morph/five-weight animation mismatch");
                    var preview=service.preview(new cc.sirrus.ysmlib.scene.AnimationPreview.Range(source.animations().get(0).begin(),source.animations().get(0).end(),24),t->player.evaluate(0,t));
                    preview.play();var frame=preview.advance(1,.5);
                    if(frame!=preview.advance(1,.5) || Math.abs(preview.state().seconds()-13.0/24)>1e-8)
                        throw new AssertionError("Shaded preview advanced once per view");
                }
            }
            System.out.println("Standalone prerequisite JAR: archives/hash/zstd/images/Opus/Vorbis/V3/workspace/physics/scenes/MMD playback/PMM/BVH/VRM playback/VRMA/first-person/materials/FBX passed without Forge or YSM native");
        } finally {
            try (var paths = Files.walk(dir)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void verify(Path source, ArchiveFormat format, byte[] expected) throws Exception {
        try (var archive = YsmRuntime.archives().open(source, format, ArchiveLimits.DEFAULT)) {
            if (!Arrays.equals(expected, archive.read("test"))) throw new AssertionError(format);
        }
    }
}
