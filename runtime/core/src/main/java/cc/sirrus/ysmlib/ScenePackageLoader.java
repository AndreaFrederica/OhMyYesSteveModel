package cc.sirrus.ysmlib;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.io.*;
import cc.sirrus.ysmlib.scene.java.PackageAssetResolver;
import cc.sirrus.ysmlib.scene.mmd.*;
import cc.sirrus.ysmlib.scene.fbx.FbxMappingProfile;
import java.io.IOException;
import java.util.*;

/** Composes the format readers over package capabilities; never opens source workstation paths. */
final class ScenePackageLoader {
  private final SceneProvider services;
  private final ScenePackage source;
  private final ReadLimits limits;
  private final Map<ScenePackageAssets.Reference,String> dependencies=new LinkedHashMap<>();
  private final Map<Key,ScenePackageAssets.Document> parsed=new HashMap<>();
  private record Key(String path,ScenePackage.Format format) {}
  ScenePackageLoader(SceneProvider services,ScenePackage source,ReadLimits limits) {
    this.services=Objects.requireNonNull(services);this.source=Objects.requireNonNull(source);this.limits=Objects.requireNonNull(limits);
  }
  ScenePackageAssets load() throws IOException {
    long bytes=0;
    if(source.files().size()>limits.maxElements()) throw new AssetFormatException("Package entry budget exceeded");
    for(var data:source.files().values()) {
      bytes+=data.size();if(bytes>limits.maxBytes()) throw new AssetFormatException("Package source byte budget exceeded");
    }
    var documents=new LinkedHashMap<String,ScenePackageAssets.Document>();
    documents.put(source.model().id(),read(source.model().path(),source.model().format()));
    for(var motion:source.animations()) documents.put(motion.id(),read(motion.path(),motion.format()));
    return new ScenePackageAssets(source,documents,dependencies);
  }
  private AssetResolver resolver(String owner,ScenePackage.ReferenceSyntax syntax) {
    var resolver=new PackageAssetResolver(source,owner,syntax);
    return reference->{
      var data=resolver.resolve(reference);dependencies.put(new ScenePackageAssets.Reference(owner,reference),resolver.path(reference));return data;
    };
  }
  private ScenePackageAssets.Document read(String path,ScenePackage.Format format) throws IOException {
    var key=new Key(path,format);var cached=parsed.get(key);if(cached!=null) return cached;
    var bytes=source.files().get(path);if(bytes==null) throw new AssetFormatException("Missing scene source: "+path);
    var uri=resolver(path,ScenePackage.ReferenceSyntax.URI);var file=resolver(path,ScenePackage.ReferenceSyntax.FILE_PATH);
    ScenePackageAssets.Document result=switch(format) {
      case GLTF -> new ScenePackageAssets.Gltf(services.readGltf(bytes,uri,limits));
      case VRM -> new ScenePackageAssets.Vrm(services.readVrm(bytes,uri,limits));
      case FBX -> {
        var fbx=services.readFbx(bytes,limits);
        var mapping=source.files().get(FbxMappingProfile.PACKAGE_PATH);
        var profile=mapping==null?FbxMappingProfile.empty():services.readFbxMapping(mapping);
        fbx=profile.apply(fbx);
        var resolved=services.dependencies(fbx,file,limits);
        if(profile.materialByElement().isEmpty()) yield new ScenePackageAssets.Fbx(fbx,resolved);
        var images=new LinkedHashMap<Integer,cc.sirrus.ysmlib.scene.fbx.FbxAssets.Image>(resolved.images());
        int next=fbx.textures().size();
        for(var override:profile.materialByElement().values()) for(var reference:List.of(override.baseColor(),override.normal(),override.emissive())) {
          if(reference.isBlank()) continue;
          var target=file.resolve(reference);
          int index=-1;for(var entry:images.entrySet()) if(entry.getValue().reference().equals(reference)){index=entry.getKey();break;}
          if(index<0) images.put(next++,new cc.sirrus.ysmlib.scene.fbx.FbxAssets.Image(reference,false,target));
        }
        yield new ScenePackageAssets.Fbx(fbx,new cc.sirrus.ysmlib.scene.fbx.FbxAssets(images,profile.materialByElement()));
      }
      case PMX -> {
        var pmx=services.readPmx(bytes,limits);
        for(String texture:pmx.textures()) if(!texture.isEmpty()) file.resolve(texture);
        yield new ScenePackageAssets.Pmx(pmx,services.mesh(pmx));
      }
      case PMD -> {
        var pmd=services.readPmd(bytes,limits);var textures=new LinkedHashSet<String>();
        for(var material:pmd.materials()) {
          for(String texture:material.textureNames().split("\\*",-1)) if(!texture.isEmpty()) textures.add(texture);
        }
        for(String texture:textures) file.resolve(texture);
        var paths=new PackageAssetResolver(source,path,ScenePackage.ReferenceSyntax.FILE_PATH);
        for(var material:services.materials(pmd).definitions()) {
          var toon=material.toon();if(toon==null) continue;
          String target=paths.path(toon.reference());
          // Check presence, not a caught parse/load exception. Broken custom/default overrides must fail.
          if(source.files().containsKey(target) || toon.fallbackToon()<0) file.resolve(toon.reference());
        }
        yield new ScenePackageAssets.Pmd(pmd,services.mesh(pmd));
      }
      case PMM -> {
        var project=services.readPmm(bytes,reference->schema(readProjectModel(path,reference)),limits);
        var models=new LinkedHashMap<Integer,ScenePackageAssets.Document>();
        for(var model:project.models()) models.put(model.index(),readProjectModel(path,model.path()));
        // Disabled media is still author data and may become active at another project time.
        for(String reference:project.assetReferences()) file.resolve(reference);
        yield new ScenePackageAssets.Pmm(project,models);
      }
      case VMD -> { var vmd=services.readVmd(bytes,limits);yield new ScenePackageAssets.Vmd(vmd,services.vmdAnimation(vmd)); }
      case VPD -> { var vpd=services.readVpd(bytes,limits);yield new ScenePackageAssets.Vpd(vpd,services.vpdAnimation(vpd)); }
      case VRMA -> new ScenePackageAssets.Vrma(services.readVrma(bytes,uri,limits));
      case BVH -> new ScenePackageAssets.Bvh(services.readBvh(bytes,limits));
    };
    parsed.put(key,result);return result;
  }
  private ScenePackageAssets.Document readProjectModel(String owner,String reference) throws IOException {
    var resolver=new PackageAssetResolver(source,owner,ScenePackage.ReferenceSyntax.FILE_PATH);
    var bytes=resolver.resolve(reference);String target=resolver.path(reference);
    dependencies.put(new ScenePackageAssets.Reference(owner,reference),target);
    final ScenePackage.Format format;
    if(bytes.size()>=4 && bytes.get(0)=='P' && bytes.get(1)=='M' && bytes.get(2)=='X' && bytes.get(3)==' ') format=ScenePackage.Format.PMX;
    else if(bytes.size()>=3 && bytes.get(0)=='P' && bytes.get(1)=='m' && bytes.get(2)=='d') format=ScenePackage.Format.PMD;
    else throw new AssetFormatException("PMM model is not a PMX/PMD source: "+reference);
    return read(target,format);
  }
  private static PmmModelResolver.Schema schema(ScenePackageAssets.Document model) throws AssetFormatException {
    if(model instanceof ScenePackageAssets.Pmd pmd) return PmmModelResolver.Schema.from(pmd.value());
    if(model instanceof ScenePackageAssets.Pmx pmx) {
      var indices=new ArrayList<Integer>();for(int i=0;i<pmx.value().bones().size();i++) if(pmx.value().bones().get(i).ik()!=null) indices.add(i);
      return new PmmModelResolver.Schema(pmx.value().bones().stream().map(b->b.names().local()).toList(),
          pmx.value().morphs().stream().map(m->m.names().local()).toList(),new IntData(indices.stream().mapToInt(i->i).toArray()));
    }
    throw new AssetFormatException("Unsupported PMM model schema");
  }
}
