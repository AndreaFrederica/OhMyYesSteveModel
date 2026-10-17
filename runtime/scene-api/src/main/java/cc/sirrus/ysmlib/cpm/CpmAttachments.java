package cc.sirrus.ysmlib.cpm;

import java.util.*;

/** Normalized equipment/socket declarations shared by CPM source families. */
public final class CpmAttachments {
    private CpmAttachments() {}
    public record Binding(String bone, String slot) {
        public Binding { Objects.requireNonNull(bone); Objects.requireNonNull(slot); }
    }

    public static List<Binding> collect(CpmDocument document) {
        Objects.requireNonNull(document);
        var result = new ArrayList<Binding>();
        if (document instanceof CpmDocument.Legacy legacy) {
            for (var bone : legacy.bones()) {
                var attached = bone.source().fields().get("attached");
                if (attached instanceof CpmValue.ArrayValue values) for (var value : values.values())
                    if (value instanceof CpmValue.StringValue slot) result.add(new Binding(bone.id(), slot.value()));
            }
        } else if (document instanceof CpmDocument.Remake remake) {
            remake.addons().forEach((bone, slot) -> result.add(new Binding(bone, slot)));
            remake.skeleton().attachments().forEach((slot, bones) -> bones.forEach(bone -> result.add(new Binding(bone, slot))));
            remake.skeleton().leftArm().ifPresent(bones -> bones.forEach(bone -> result.add(new Binding(bone, "left_arm"))));
            remake.skeleton().rightArm().ifPresent(bones -> bones.forEach(bone -> result.add(new Binding(bone, "right_arm"))));
        }
        var unique = new LinkedHashMap<String, Binding>();
        for (var binding : result) unique.putIfAbsent(binding.bone() + "\u0000" + binding.slot(), binding);
        return List.copyOf(unique.values());
    }

    public static Optional<String> slotFor(CpmDocument document, String bone) {
        return collect(document).stream().filter(binding -> binding.bone().equals(bone)).map(Binding::slot).findFirst();
    }
}
