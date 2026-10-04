import cc.sirrus.ysmlib.YsmRuntime;
import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.io.ReadLimits;
import cc.sirrus.ysmlib.scene.mmd.*;
import cc.sirrus.ysmlib.scene.physics.*;
import java.nio.*;
import java.util.*;

/** A fresh JVM with only the shaded runtime, host JOML and test output. */
public final class PhysicsDistributionSmoke {
    public static void main(String[] args) throws Exception {
        boolean nativeExpected = Boolean.parseBoolean(args[0]);
        var provider = YsmRuntime.physics();
        require(provider.capabilities().nativeAcceleration() == nativeExpected, "Wrong physics selection");
        require(nativeExpected ? YsmRuntime.physicsFallbackReason().isEmpty()
                : YsmRuntime.physicsFallbackReason().contains(args[1]), "Wrong fallback reason");
        if (nativeExpected) require(provider.capabilities().abi() == 6 && provider.capabilities().featureBits() == 0x1ff,
                "Wrong native ABI/features");
        require(YsmRuntime.hostPhysics() == provider, "Live host selection changed provider");
        var status = YsmRuntime.diagnostics().stream().filter(s -> s.module().equals("Physics")).findFirst().orElseThrow();
        require(status.implementation().contains(provider.id()), "Diagnostic provider mismatch");
        require(nativeExpected || status.implementation().contains("fallback="), "Diagnostic reason missing");
        // Actual packed JNI symbols, fixed-step trajectory and cursor preservation.
        try (var world = provider.createWorld(PhysicsSpec.World.defaults().withoutReplay())) {
            world.addBody(new PhysicsSpec.Body(PhysicsSpec.Shape.SPHERE, new Vec3(.5f,0,0),
                    new Pose(new Vec3(0,10,0), Rotation.IDENTITY), PhysicsSpec.Motion.DYNAMIC,
                    1,0,0,0,.5f,.04f,1,0xffff));
            var ids = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).asIntBuffer();
            var poses = ByteBuffer.allocateDirect(28).order(ByteOrder.nativeOrder()).asFloatBuffer();
            poses.put(new float[]{0,10,0,0,0,0,1}).flip();
            world.setBodyPoses(ids, poses);
            require(ids.position() == 0 && poses.position() == 0, "Input cursor changed");
            for (int i=0; i<60; i++) world.step();
            var output = ByteBuffer.allocateDirect(52).order(ByteOrder.nativeOrder()).asFloatBuffer();
            require(world.readBodyStates(output) == 1 && output.position() == 13, "Packed readback failed");
            require(Math.abs(output.get(1)-5.018333f) < .001, "Physics trajectory mismatch");
            var input = new ScenePhysicsInput(0,30_000_000,0,0,Matrix4.IDENTITY,Vec3.ZERO,
                    List.of(),List.of(),ScenePhysicsInput.Settings.defaults());
            world.environment(new PhysicsEnvironment(input,Vec3.ZERO,new Vec3(1,0,0),Vec3.ZERO,Vec3.ZERO,false));
            world.step();
            require(world.bodyStates().get(0).linearVelocity().x()<0, "Host environment symbol/force failed");
            world.environment(null);
        }
        var scenes = YsmRuntime.scenes();
        var limits = ReadLimits.DEFAULT;
        var modelBytes = resource("/mmd-oracle/skin.pmx");
        var motionBytes = resource("/mmd-oracle/skin.vmd");
        var base = scenes.readPmx(modelBytes, limits);
        var motion = scenes.readVmd(motionBytes, limits);
        var clip = scenes.vmdAnimation(motion).clip();
        var bodies = new ArrayList<PmxDocument.RigidBody>();
        for (int mode=0; mode<3; mode++) bodies.add(new PmxDocument.RigidBody(
                new PmxDocument.Names("body"+mode, ""), Math.min(mode,base.bones().size()-1),
                0,0,0,new Vec3(.5f,0,0),base.bones().get(Math.min(mode,base.bones().size()-1)).position(),
                Vec3.ZERO,1,0,0,0,.5f,mode));
        var model = new PmxDocument(base.version(),base.globals(),base.names(),base.comment(),base.englishComment(),
                base.vertices(),base.indices(),base.textures(),base.materials(),base.bones(),base.morphs(),
                base.displayFrames(),bodies,List.of(),List.of(),base.source());
        var source = new ScenePackage(new ScenePackage.Source("model","skin.pmx",ScenePackage.Format.PMX),
                new ScenePackage.Settings(1,-1,cc.sirrus.ysmlib.scene.fbx.FbxEvaluation.SkinSpace.BIND_WORLD),
                List.of(new ScenePackage.Source("motion","skin.vmd",ScenePackage.Format.VMD)),
                List.of(),Map.of("skin.pmx",modelBytes,"skin.vmd",motionBytes));
        var assets = new ScenePackageAssets(source,Map.of("model",new ScenePackageAssets.Pmx(model,scenes.mesh(model)),
                "motion",new ScenePackageAssets.Vmd(motion,scenes.vmdAnimation(motion))),Map.of());
        var selection = new ScenePackagePlayback.Selection("motion",0);
        var settings = MmdPlayback.Settings.preview();
        var packaged = scenes.playback(assets,selection,ScenePackagePlayback.Settings.preview(),limits);
        try (packaged; var direct = scenes.playback(model,clip,settings,Map.of());
             var oracle = new MmdPlayer(model,clip,provider,settings,Map.of())) {
            for (double time : new double[]{0,.1,.508,1,.25,.75}) {
                var frame = packaged.seek(time);
                var value = ((ScenePackagePlayback.Mmd)frame.details()).value();
                require(value.equals(oracle.seek(time)) && value.equals(direct.seek(time)), "Package/scalar MMD mismatch");
                require(packaged.seek(time) == frame, "Render query advanced/rebuilt frame");
                require(frame.firstPerson() == frame.thirdPerson(), "Views recomputed the MMD frame");
            }
        }
        try { packaged.seek(0); throw new AssertionError("Closed package accepted query"); }
        catch (IllegalStateException expected) { }
        var liveSettings = new MmdPlayback.Settings(60,10,settings.gravity(),false,1000);
        try (var live = scenes.playback(model,clip,liveSettings,Map.of())) {
            var frame = live.seek(.5);
            require(frame == live.seek(.5), "Live frame not reused");
            try { live.seek(.25); throw new AssertionError("Live backward seek accepted"); }
            catch (IllegalStateException expected) { }
        }
        var pmd = scenes.readPmd(resource("/mmd-oracle/pmd.pmd"),limits);
        var pmdClip = scenes.vmdAnimation(scenes.readVmd(resource("/mmd-oracle/pmd.vmd"),limits)).clip();
        try (var player = scenes.playback(pmd,pmdClip,settings,Map.of());
             var oracle = new MmdPlayer(pmd,pmdClip,provider,settings,Map.of())) {
            for (double time : new double[]{0,.5,1,.25}) require(player.seek(time).equals(oracle.seek(time)), "PMD packed mismatch");
        }
        System.out.println("Physics distribution passed: " + status.implementation());
    }

    private static ByteData resource(String name) throws Exception {
        try (var input = PhysicsDistributionSmoke.class.getResourceAsStream(name)) {
            return new ByteData(Objects.requireNonNull(input,name).readAllBytes());
        }
    }
    private static void require(boolean success, String message) {
        if (!success) throw new AssertionError(message);
    }
}
