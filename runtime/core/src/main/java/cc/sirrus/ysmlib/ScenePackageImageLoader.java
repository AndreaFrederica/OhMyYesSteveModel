package cc.sirrus.ysmlib;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.io.*;
import java.io.IOException;
import java.util.*;

/** Decode off the host render thread; creates no texture/GPU handles. */
final class ScenePackageImageLoader {
  private final SceneProvider services;
  private final ScenePackageAssets assets;
  private final ReadLimits limits;
  private final Map<ScenePackageImages.Key,SceneImage> images=new LinkedHashMap<>();
  private final Map<ByteData,SceneImage> decoded=new HashMap<>();
  private long usedBytes,usedPixels;
  @FunctionalInterface interface Decoder { SceneImage read(ByteData source,String hint,ReadLimits limits) throws IOException; }
  private final Decoder decoder;
  ScenePackageImageLoader(SceneProvider services,ScenePackageAssets assets,ReadLimits limits) {
    this(services,assets,limits,services::readImage);
  }
  ScenePackageImageLoader(SceneProvider services,ScenePackageAssets assets,ReadLimits limits,Decoder decoder) {
    this.services=Objects.requireNonNull(services);this.assets=Objects.requireNonNull(assets);this.limits=Objects.requireNonNull(limits);
    this.decoder=Objects.requireNonNull(decoder);
  }
  ScenePackageImages load() throws IOException {
    read(assets.source().model().path(),assets.model());return new ScenePackageImages(images);
  }
  private void read(String owner,ScenePackageAssets.Document document) throws IOException {
    if(document instanceof ScenePackageAssets.Gltf gltf) readScene(owner,gltf.value().scene());
    else if(document instanceof ScenePackageAssets.Vrm vrm) readScene(owner,vrm.value().scene());
    else if(document instanceof ScenePackageAssets.Fbx fbx) {
      for(var entry:fbx.assets().images().entrySet()) add(ScenePackageImages.Key.indexed(owner,entry.getKey()),entry.getValue().bytes(),entry.getValue().reference());
    } else if(document instanceof ScenePackageAssets.Pmx pmx) {
      // A PMX texture table is not a usage list. Large models often retain
      // unused 4K textures, and decoding every table entry would exhaust the
      // cumulative image budget before the renderer has requested anything.
      var usedIndexed=new LinkedHashSet<Integer>();
      var usedSharedToon=new LinkedHashSet<Integer>();
      for(var material:services.materials(pmx.value()).definitions()) for(var image:material.images()) {
        if(image.kind()==cc.sirrus.ysmlib.scene.mmd.MmdMaterials.ImageKind.INDEXED) {
          int index=image.index();
          if(usedIndexed.add(index)) {
            var reference=pmx.value().textures().get(index);
            if(!reference.isEmpty()) add(ScenePackageImages.Key.indexed(owner,index),resolve(owner,reference),reference);
          }
        } else if(image.kind()==cc.sirrus.ysmlib.scene.mmd.MmdMaterials.ImageKind.SHARED_TOON
            && usedSharedToon.add(image.index())) {
          add(image.key(owner),services.sharedMmdToon(image.index()),"bmp");
        }
      }
    } else if(document instanceof ScenePackageAssets.Pmd pmd) {
      var references=new LinkedHashSet<String>();
      for(var material:pmd.value().materials()) {
        for(var reference:material.textureNames().split("\\*",-1)) if(!reference.isEmpty()) references.add(reference);
      }
      for(var reference:references) add(ScenePackageImages.Key.named(owner,reference),resolve(owner,reference),reference);
      for(var material:services.materials(pmd.value()).definitions()) {
        var toon=material.toon();if(toon==null) continue;
        boolean packaged=assets.dependencies().containsKey(new ScenePackageAssets.Reference(owner,toon.reference()));
        var bytes=packaged || toon.fallbackToon()<0?resolve(owner,toon.reference()):services.sharedMmdToon(toon.fallbackToon());
        add(toon.key(owner),bytes,toon.reference());
      }
    } else if(document instanceof ScenePackageAssets.Pmm pmm) {
      for(var model:pmm.value().models()) {
        var path=assets.dependencies().get(new ScenePackageAssets.Reference(owner,model.path()));
        if(path==null || !pmm.models().containsKey(model.index())) throw new AssetFormatException("Missing PMM model image owner");
        read(path,pmm.models().get(model.index()));
      }
      // Accessories are not decoded as model images until their geometry/material importer exists.
    } else throw new AssetFormatException("Package root has no model image domain");
  }
  private void readScene(String owner,SceneAsset scene) throws IOException {
    for(int i=0;i<scene.images().size();i++) {
      var image=scene.images().get(i);
      String hint=image.sourceUri().isEmpty()?image.mimeType():image.sourceUri();
      add(ScenePackageImages.Key.indexed(owner,i),image.bytes(),hint);
    }
  }
  private ByteData resolve(String owner,String reference) throws IOException {
    var path=assets.dependencies().get(new ScenePackageAssets.Reference(owner,reference));
    var data=path==null?null:assets.source().files().get(path);
    if(data==null) throw new AssetFormatException("Missing parsed image dependency: "+owner+" -> "+reference);
    return data;
  }
  private void add(ScenePackageImages.Key key,ByteData source,String hint) throws IOException {
    if(images.containsKey(key)) return; // The same PMM child may be instanced more than once.
    if(images.size()>=limits.maxElements()) throw new AssetFormatException("Scene image binding budget exceeded");
    var image=decoded.get(source);
    if(image==null) {
      long remainingBytes=limits.maxBytes()-usedBytes,remainingPixels=limits.maxElements()-usedPixels;
      if(remainingBytes<1 || remainingPixels<1) throw new AssetFormatException("Cumulative scene image budget exceeded");
      var remaining=new ReadLimits((int)remainingBytes,(int)remainingPixels,(int)Math.min(limits.maxStringBytes(),remainingBytes));
      try { image=decoder.read(source,hint,remaining); }
      catch(IOException failure) { throw new AssetFormatException("Cannot decode scene image: "+key,failure); }
      usedBytes+=image.rgba().storageBytes();usedPixels+=(long)image.width()*image.height();decoded.put(source,image);
    }
    images.put(key,image);
  }
}
