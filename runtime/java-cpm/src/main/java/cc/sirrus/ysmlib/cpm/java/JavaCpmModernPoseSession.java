package cc.sirrus.ysmlib.cpm.java;

import cc.sirrus.ysmlib.cpm.*;
import java.util.*;

/** Modern CustomPlayerModel runtime: host sync, animation.js and ScriptBone units. */
final class JavaCpmModernPoseSession implements CpmPoseSession {
    private final CpmDocument.Remake source;
    private final CpmJavascriptRuntime script;
    private final Map<String,CpmJavascriptRuntime.Bone> bones;
    private final Map<String,double[]> authoredPositions;
    private final Map<String,double[]> authoredRotations;
    private final Set<String> authoredBoneNames;
    private final CpmPhysicsEngine physics = new CpmPhysicsEngine();
    private final CpmParticleEmitter particles;
    private List<CpmFeatures.Spawn> pending = List.of(); private long lastTick = Long.MIN_VALUE; private boolean initialized;
    JavaCpmModernPoseSession(CpmDocument.Remake source) {
        this.source=Objects.requireNonNull(source); particles=new CpmParticleEmitter(source);
        var b=new LinkedHashMap<String,CpmJavascriptRuntime.Bone>(); var p=new LinkedHashMap<String,double[]>(); var r0=new LinkedHashMap<String,double[]>(); var names=new LinkedHashSet<String>();
        for(var part:source.parts()) for(var model:part.geometry().models()) { var by=new LinkedHashMap<String,CpmGeometry.Bone>(); for(var bone:model.bones()) { by.putIfAbsent(bone.name(),bone); names.add(bone.name()); } for(var bone:model.bones()){ var local=restPosition(source,bone,by); p.putIfAbsent(bone.name(),local); r0.putIfAbsent(bone.name(),new double[]{bone.rotation().x(),bone.rotation().y(),bone.rotation().z()}); b.putIfAbsent(bone.name(),new CpmJavascriptRuntime.Bone(local[0],local[1],local[2],bone.rotation().x(),bone.rotation().y(),bone.rotation().z())); } }
        for(var name:hostNames(source.skeleton().type())) b.computeIfAbsent(name,n->new CpmJavascriptRuntime.Bone());
        bones=b; authoredPositions=Map.copyOf(p); authoredRotations=Map.copyOf(r0); authoredBoneNames=Set.copyOf(names); script=source.animationScript().map(text->new CpmJavascriptRuntime(text,bones)).orElse(null);
    }
    @Override public void init(double age){if(!initialized){initialized=true;if(script!=null)script.init(age);}}
    @Override public void setHostPose(Map<String,BonePose> host){if(host==null)return; for(var e:host.entrySet()){String k=hostKey(e.getKey());if(k==null||!bones.containsKey(k)||authoredBoneNames.contains(k))continue;var p=e.getValue();bones.get(k).setHostPose(p.position(),new double[]{Math.toDegrees(p.rotation()[0]),Math.toDegrees(p.rotation()[1]),Math.toDegrees(p.rotation()[2])},p.scale(),p.visible(),p.color(),p.alpha(),p.emissive());}}
    @Override public Map<String,BonePose> tick(double age, Map<String,Double> inputs) {
        init(age);
        boolean advance = (long)Math.floor(age) != lastTick;
        if (advance) lastTick = (long)Math.floor(age);
        if (script != null) {
            script.update(age, inputs == null ? Map.of() : inputs);
            if (advance) script.tick(age, inputs == null ? Map.of() : inputs);
        }
        var out = new LinkedHashMap<String,BonePose>();
        double seconds = age / 20d;
        (script == null ? bones : script.snapshot()).forEach((name, bone) -> {
            var current = bone.physicsEnabled()
                    ? physics.apply(name, bone.rotation(), bone.physics(), seconds)
                    : bone.rotation();
            // ScriptBone exposes the complete authored/script rotation and
            // pixel position.  The YSM render adapter consumes this complete
            // channel once; keeping a delta here would require a second,
            // format-specific rest rotation in the baked GeoBone.
            // ModelBone.instantiate stores (source.y, source.x, source.z),
            // while ScriptBone exposes (rotation.x, rotation.y, rotation.z)
            // as the authored source channels (source.x, source.y, source.z).
            // The render adapter/JavaModelState consumes ModelRenderer order:
            // [xRot, yRot, zRot] = [source.y, source.x, source.z].
            var rotation = new double[]{current[1], current[0], current[2]};
            var position = bone.position();
            out.put(name, new BonePose(position, rotation, bone.scale(), bone.visible(),
                    bone.color(), bone.alpha(), bone.emissive()));
        });
        if (advance) pending = particles.tick(age, inputs == null ? Map.of() : inputs);
        return Map.copyOf(out);
    }    @Override public List<CpmFeatures.Spawn> drainParticles(){var out=pending;pending=List.of();return out;}
    @Override public void close(){if(script!=null)script.close();}
    private static double[] restPosition(CpmDocument.Remake model, CpmGeometry.Bone bone, Map<String,CpmGeometry.Bone> by) {
        String parent=bone.parent();
        if(parent!=null&&!parent.isEmpty()&&!"none".equals(parent)&&by.containsKey(parent)) {
            var p=by.get(parent).pivot();
            return new double[]{bone.pivot().x()-p.x(), bone.pivot().y()-p.y(), bone.pivot().z()-p.z()};
        }
        // ModelBone.instantiate stores a root's local position in blocks after
        // subtracting the 24 pixel model origin, reflecting Y, and subtracting
        // the attached vanilla ModelRenderer point. ScriptBone exposes that
        // value back in CPM pixel coordinates (x*16, -y*16, z*16).
        String host = parent;
        if (host == null || host.isEmpty() || "none".equals(host)) {
            // Upstream ModelBone performs a skeleton lookup only for an
            // unparented authored name ending in ``_c``. Ordinary custom roots
            // remain parentless; attachment metadata is a locator declaration
            // and must not rewrite this pose graph.
            host = null;
            if (bone.name().endsWith("_c")) {
                String rootBase = bone.name().substring(0, bone.name().length() - 2);
                if (hostNames(model.skeleton().type()).contains(rootBase)) host = rootBase;
            }
        }
        if(host!=null&&!host.isEmpty()&&!"none".equals(host)) {
            var hp=hostPoint(model,host);
            return new double[]{bone.pivot().x()-hp[0], bone.pivot().y()-24d+hp[1], bone.pivot().z()-hp[2]};
        }
        return new double[]{bone.pivot().x(),bone.pivot().y()-24,bone.pivot().z()};
    }
    private static double[] hostPoint(CpmDocument.Remake model, String host) {
        var p=model.skeleton().parameters();
        double armX=p.getOrDefault("arm_interval",10d)/2d;
        double legX=p.getOrDefault("leg_interval",3.8d)/2d;
        return switch(host) {
            case "left_arm" -> new double[]{armX,24d-p.getOrDefault("arm_pivot_height",22d),p.getOrDefault("arm_offset",0d)};
            case "right_arm" -> new double[]{-armX,24d-p.getOrDefault("arm_pivot_height",22d),p.getOrDefault("arm_offset",0d)};
            case "left_leg" -> new double[]{legX,24d-p.getOrDefault("leg_length",12d),p.getOrDefault("leg_offset",0d)};
            case "right_leg" -> new double[]{-legX,24d-p.getOrDefault("leg_length",12d),p.getOrDefault("leg_offset",0d)};
            case "left_front_leg" -> new double[]{legX,24d-p.getOrDefault("leg_length",6d),p.getOrDefault("leg_offset",1d)+p.getOrDefault("body_length",12d)};
            case "right_front_leg" -> new double[]{-legX,24d-p.getOrDefault("leg_length",6d),p.getOrDefault("leg_offset",1d)+p.getOrDefault("body_length",12d)};
            case "left_back_leg" -> new double[]{legX,24d-p.getOrDefault("leg_length",6d),p.getOrDefault("leg_offset",1d)};
            case "right_back_leg" -> new double[]{-legX,24d-p.getOrDefault("leg_length",6d),p.getOrDefault("leg_offset",1d)};
            case "head" -> new double[]{0,24d-p.getOrDefault("head_pivot_height",24d),p.getOrDefault("head_offset",0d)};
            case "body" -> new double[]{0,24d-p.getOrDefault("body_pivot_height",24d),p.getOrDefault("body_offset",0d)};
            default -> new double[]{0,0,0};
        };
    }
    private static List<String> hostNames(String type) {
        return switch (type) {
            case "quadruped" -> List.of("head", "body", "left_front_leg", "right_front_leg", "left_back_leg", "right_back_leg");
            case "slime" -> List.of("body");
            default -> List.of("head", "body", "left_arm", "right_arm", "left_leg", "right_leg");
        };
    }
    private static String hostKey(String name) {
        return switch (name) { case "Head" -> "head"; case "UpperBody" -> "body"; case "LeftArm" -> "left_arm";
            case "RightArm" -> "right_arm"; case "LeftLeg" -> "left_leg"; case "RightLeg" -> "right_leg"; default -> name; };
    }
}



