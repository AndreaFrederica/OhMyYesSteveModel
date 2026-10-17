package cc.sirrus.ysmlib.cpm;

import cc.sirrus.ysmlib.scene.ByteData;
import cc.sirrus.ysmlib.scene.CompatibilityReport;
import java.util.*;

/** Immutable parsed source closure. No expressions, scripts, physics or host resources have been evaluated. */
public sealed interface CpmDocument permits CpmDocument.Legacy,CpmDocument.Remake {
    enum Format { CUSTOM_MODEL, CUSTOM_PLAYER_MODEL }
    record Metadata(String sourceId,String name,String version,List<String> authors,String description,String url) {
        public Metadata { authors=List.copyOf(authors); }
    }
    Format format();
    Metadata metadata();
    CpmValue.ObjectValue source();
    Map<String,ByteData> files();
    CompatibilityReport compatibility();

    default List<CpmFeatures.Physics> physics() { return CpmFeatures.physics(this); }
    default List<CpmFeatures.Particle> particles() { return CpmFeatures.particles(this); }
    default List<CpmFeatures.Item> items() { return CpmFeatures.items(this); }
    default List<CpmAttachments.Binding> attachments() { return CpmAttachments.collect(this); }

    /** Float/bool expression strings stay in the source object. Their source order is significant. */
    /** parentIndex identifies the earlier custom bone bound by the old reader; -1 denotes a host bone. */
    record LegacyBone(String id,String parent,int parentIndex,CpmValue.ObjectValue source,List<Box> boxes,List<Quad> quads) {
        public LegacyBone { boxes=List.copyOf(boxes);quads=List.copyOf(quads); }
    }
    record Box(List<Double> coordinates,List<Double> textureOffset,double sizeAdd,boolean mirror,CpmValue.ObjectValue source) {
        public Box { coordinates=List.copyOf(coordinates);textureOffset=List.copyOf(textureOffset); }
    }
    record Quad(List<Double> coordinates,List<Double> textureOffset,double sizeAdd,CpmValue.ObjectValue source) {
        public Quad { coordinates=List.copyOf(coordinates);textureOffset=List.copyOf(textureOffset); }
    }
    record Legacy(Metadata metadata,CpmValue.ObjectValue source,List<LegacyBone> bones,
                  Map<String,ByteData> files,CompatibilityReport compatibility) implements CpmDocument {
        public Legacy { bones=List.copyOf(bones);files=Map.copyOf(files); }
        public Format format() { return Format.CUSTOM_MODEL; }
    }
    /** Optional lists distinguish original defaults from an explicitly empty hide list. */
    record Skeleton(String type,Map<String,Double> parameters,Map<String,List<String>> attachments,
                    Optional<List<String>> leftArm,Optional<List<String>> rightArm,String eyePosition) {
        public Skeleton {
            parameters=Map.copyOf(parameters);
            var copy=new LinkedHashMap<String,List<String>>();attachments.forEach((k,v)->copy.put(k,List.copyOf(v)));
            attachments=Collections.unmodifiableMap(copy);
            leftArm=leftArm.map(List::copyOf);rightArm=rightArm.map(List::copyOf);
        }
    }
    /** packageTexturePath is empty for an unresolved host ResourceLocation; texture retains the original symbol. */
    record Part(String name,String geometryPath,String texture,String packageTexturePath,
                boolean doubleFace,CpmGeometry geometry,CpmValue.ObjectValue source) {}
    record Remake(Metadata metadata,CpmValue.ObjectValue source,boolean accessory,Skeleton skeleton,
                  List<Part> parts,Map<String,String> addons,Optional<String> animationScript,
                  Map<String,ByteData> files,CompatibilityReport compatibility) implements CpmDocument {
        public Remake { parts=List.copyOf(parts);addons=Map.copyOf(addons);files=Map.copyOf(files); }
        public Format format() { return Format.CUSTOM_PLAYER_MODEL; }
    }
}
