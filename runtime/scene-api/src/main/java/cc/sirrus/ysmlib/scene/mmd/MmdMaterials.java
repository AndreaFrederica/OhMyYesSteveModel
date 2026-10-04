package cc.sirrus.ysmlib.scene.mmd;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.io.ReadLimits;
import java.util.*;

/** MMD's own material inputs. Colors and texture arithmetic remain in legacy shader space. */
public interface MmdMaterials {
  enum Format { PMD,PMX }
  enum ImageKind { INDEXED,NAMED,SHARED_TOON }
  enum SphereMode { NONE,MULTIPLY,ADD,SUB_TEXTURE }
  /** fallbackToon is used only for a missing bare default PMD toon filename, never for a broken file. */
  record Image(ImageKind kind,int index,String reference,int fallbackToon) {
    public Image {
      Objects.requireNonNull(kind);Objects.requireNonNull(reference);
      if(fallbackToon< -1 || fallbackToon>9) throw new IllegalArgumentException("Invalid toon fallback");
      boolean valid=switch(kind) {
        case INDEXED -> index>=0 && reference.isEmpty() && fallbackToon==-1;
        case NAMED -> index==-1 && !reference.isEmpty();
        case SHARED_TOON -> index>=0 && index<10 && reference.isEmpty() && fallbackToon==-1;
      };
      if(!valid) throw new IllegalArgumentException("Invalid MMD image reference");
    }
    public ScenePackageImages.Key key(String owner) {
      return switch(kind) {
        case INDEXED -> ScenePackageImages.Key.indexed(owner,index);
        case NAMED -> ScenePackageImages.Key.named(owner,reference);
        case SHARED_TOON -> ScenePackageImages.Key.sharedToon(index);
      };
    }
  }
  /** Source flags are preserved, including the PMX 2.1 vertex-color/point/line bits.
   * The host resolves rasterization independently of the original triangle index storage. */
  record Definition(int index,Format format,String name,int flags,SphereMode sphereMode,
      Image diffuse,Image sphere,Image toon) {
    public Definition { Objects.requireNonNull(format);Objects.requireNonNull(name);Objects.requireNonNull(sphereMode); }
    public boolean doubleSided() { return (flags&1)!=0; }
    public boolean edge() { return (flags&16)!=0; }
    public boolean vertexColor() { return (flags&32)!=0; }
    public boolean points() { return (flags&64)!=0; }
    public boolean lines() { return (flags&128)!=0; }
    public List<Image> images() {
      var result=new ArrayList<Image>(3);
      if(diffuse!=null) result.add(diffuse);if(sphere!=null) result.add(sphere);if(toon!=null) result.add(toon);
      return List.copyOf(result);
    }
  }
  record Material(Definition definition,MmdMorphState.Material values) {
    public Material { Objects.requireNonNull(definition);Objects.requireNonNull(values); }
  }
  record Frame(List<Material> materials) { public Frame { materials=List.copyOf(materials); } }
  List<Definition> definitions();
  /** Values come from playback's final morph state; this method never applies the morph a second time. */
  Frame evaluate(List<MmdMorphState.Material> values);
  /** Uses immutable source values without running animation or physics. */
  Frame rest();
  /** Project evaluated source triangles to PMX 2.1 points or all three wire edges. Does not deform or advance time. */
  MeshAsset.Primitive geometry(int material,MeshAsset.Primitive evaluated,ReadLimits limits);
}
