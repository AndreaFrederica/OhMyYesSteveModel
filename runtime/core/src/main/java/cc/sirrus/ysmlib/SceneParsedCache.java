package cc.sirrus.ysmlib;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.io.*;
import java.lang.ref.WeakReference;
import java.util.*;

/** Small weak reuse window: a profile edit may share immutable parsed documents with a still-live target. */
final class SceneParsedCache {
  private record Entry(ReadLimits limits,WeakReference<ScenePackageAssets> value) {}
  private final Deque<Entry> entries=new ArrayDeque<>();
  synchronized ScenePackageAssets find(ScenePackage source,ReadLimits limits) throws AssetFormatException {
    long bytes=0;for(var data:source.files().values())bytes+=data.size();
    if(bytes>limits.maxBytes() || source.files().size()>limits.maxElements())throw new AssetFormatException("Package source budget exceeded");
    entries.removeIf(e->e.value.get()==null);
    for(var entry:entries) {
      var cached=entry.value.get();if(cached==null || !entry.limits.equals(limits))continue;
      var old=cached.source();
      if(!old.model().equals(source.model()) || !old.settings().equals(source.settings()) || !old.animations().equals(source.animations()) || !old.relocations().equals(source.relocations()))continue;
      if(!sameFiles(old.files(),source.files()))continue;
      return new ScenePackageAssets(source,cached.documents(),cached.dependencies());
    }
    return null;
  }
  synchronized void add(ScenePackageAssets value,ReadLimits limits) {
    entries.addFirst(new Entry(limits,new WeakReference<>(value)));while(entries.size()>4)entries.removeLast();
  }
  private static boolean sameFiles(Map<String,ByteData> a,Map<String,ByteData> b) {
    int ac=a.size()-(a.containsKey(SceneModelProfile.PACKAGE_PATH)?1:0),bc=b.size()-(b.containsKey(SceneModelProfile.PACKAGE_PATH)?1:0);
    if(ac!=bc)return false;
    for(var entry:a.entrySet())if(!entry.getKey().equals(SceneModelProfile.PACKAGE_PATH) && !entry.getValue().equals(b.get(entry.getKey())))return false;
    return true;
  }
}
