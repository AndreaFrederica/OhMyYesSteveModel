package cc.sirrus.ysmlib.scene.mmd;

import cc.sirrus.ysmlib.scene.IntData;
import java.io.IOException;
import java.util.*;

/** PMM v1 omits track counts/names. Its exact model schema must be supplied; filenames alone cannot recover it. */
@FunctionalInterface
public interface PmmModelResolver {
  record Schema(List<String> bones,List<String> morphs,IntData ikBones) {
    public Schema { bones=List.copyOf(bones);morphs=List.copyOf(morphs);Objects.requireNonNull(ikBones); }
    public static Schema from(PmdDocument model) {
      return new Schema(model.bones().stream().map(b->b.name()).toList(),model.morphs().stream().map(m->m.name()).toList(),
          new IntData(model.ik().stream().mapToInt(i->i.controller()).toArray()));
    }
  }
  Schema resolve(String sourcePath) throws IOException;
  PmmModelResolver NONE=path->{ throw new IOException("PMM v1 requires the model schema for "+path); };
}
