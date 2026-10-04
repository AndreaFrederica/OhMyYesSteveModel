package cc.sirrus.ysmlib.scene;

import java.util.*;

/** Source-addressed model images; sampler/UV/color usage stays with each material binding. */
public record ScenePackageImages(Map<Key,SceneImage> images) {
  /**
   * Owner is the package model path. Indexed keys use glTF image, FBX texture or PMX texture
   * indices. PMD uses its original literal file reference. PMM children keep their own owner.
   * Exactly one of index/reference is active, avoiding collisions with source filenames.
   */
  public enum Domain { MODEL,MMD_SHARED_TOON }
  public record Key(String owner,int index,String reference,Domain domain) {
    public Key(String owner,int index,String reference) { this(owner,index,reference,Domain.MODEL); }
    public Key {
      Objects.requireNonNull(owner);Objects.requireNonNull(reference);Objects.requireNonNull(domain);
      boolean valid=domain==Domain.MMD_SHARED_TOON
          ? owner.isEmpty() && index>=0 && index<10 && reference.isEmpty()
          : !owner.isEmpty() && (index<0 ? index==-1 && !reference.isEmpty() : reference.isEmpty());
      if(!valid)
        throw new IllegalArgumentException("Invalid scene image source key");
    }
    public static Key indexed(String owner,int index) { return new Key(owner,index,""); }
    public static Key named(String owner,String reference) { return new Key(owner,-1,reference); }
    public static Key sharedToon(int index) { return new Key("",index,"",Domain.MMD_SHARED_TOON); }
  }
  public ScenePackageImages { images=Map.copyOf(images); }
  public SceneImage require(Key key) {
    var image=images.get(key);if(image==null) throw new IllegalArgumentException("No prepared scene image: "+key);return image;
  }
}
