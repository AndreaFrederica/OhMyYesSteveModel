package com.elfmcys.ysm.tool;

import cc.sirrus.ysmlib.YsmRuntime;
import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.fbx.FbxDocument;
import cc.sirrus.ysmlib.scene.io.ReadLimits;
import cc.sirrus.ysmlib.scene.mmd.PmxDocument;
import cc.sirrus.ysmlib.scene.mmd.PmdDocument;
import cc.sirrus.ysmlib.scene.mmd.VmdDocument;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** Read-only audit of real user supplied assets; it never publishes a host/GPU resource. */
public final class MeshFormatAudit {
  public static void main(String[] args) throws Exception {
    if (args.length < 1 || args.length > 3) throw new IllegalArgumentException("usage: MeshFormatAudit <directory> [maxBytes] [maxElements]");
    Path root=Path.of(args[0]).toAbsolutePath().normalize();
    if (!Files.isDirectory(root)) throw new IllegalArgumentException("Not a directory: "+root);
    int maxBytes=args.length>=2?Integer.parseInt(args[1]):ReadLimits.DEFAULT.maxBytes();
    int maxElements=args.length>=3?Integer.parseInt(args[2]):8_000_000;
    var scenes=YsmRuntime.scenes();var limits=new ReadLimits(maxBytes,maxElements,4*1024*1024);
    try(var paths=Files.walk(root)) {
      paths.filter(Files::isRegularFile).sorted().forEach(path -> audit(root,path,scenes,limits));
    }
  }
  private static void audit(Path root,Path path,SceneProvider scenes,ReadLimits limits) {
    String ext=extension(path);String relative=root.relativize(path).toString().replace('\\','/');
    long size=path.toFile().length();
    try {
      byte[] bytes=Files.readAllBytes(path);var source=new ByteData(bytes);
      size=bytes.length;
      switch(ext) {
        case ".pmx" -> {
          PmxDocument d=scenes.readPmx(source,limits);var mesh=scenes.mesh(d);var materials=scenes.materials(d);
          String packageResult=packageCheck(path,root,scenes,limits);
          emit("PARSED",relative,ext,bytes.length,"version="+d.version(),"vertices="+d.vertices().size(),"triangles="+d.indices().size()/3,
              "materials="+d.materials().size(),"bones="+d.bones().size(),"morphs="+d.morphs().size(),"rigidBodies="+d.rigidBodies().size(),
              "joints="+d.joints().size(),"softBodies="+d.softBodies().size(),"meshPrimitives="+mesh.primitives().size(),"materialDefinitions="+materials.definitions().size(),"package="+packageResult);
        }
        case ".pmd" -> {
          PmdDocument d=scenes.readPmd(source,limits);var mesh=scenes.mesh(d);var materials=scenes.materials(d);
          emit("PARSED",relative,ext,bytes.length,"vertices="+d.vertices().size(),"triangles="+d.indices().size()/3,
              "materials="+d.materials().size(),"bones="+d.bones().size(),"morphs="+d.morphs().size(),"rigidBodies="+d.rigidBodies().size(),
              "joints="+d.joints().size(),"meshPrimitives="+mesh.primitives().size(),"materialDefinitions="+materials.definitions().size());
        }
        case ".vmd" -> {
          VmdDocument d=scenes.readVmd(source,limits);var imported=scenes.vmdAnimation(d);
          emit("PARSED",relative,ext,bytes.length,"model="+safe(d.modelName()),"bones="+d.bones().size(),"morphs="+d.morphs().size(),
              "cameras="+d.cameras().size(),"lights="+d.lights().size(),"shadows="+d.shadows().size(),"properties="+d.properties().size(),
              "tracks="+imported.clip().tracks().size(),"durationSeconds="+imported.clip().durationSeconds());
        }
        case ".fbx" -> {
          FbxDocument d=scenes.readFbx(source,limits);
          int draws;
          try(var evaluation=scenes.evaluator(d,limits,cc.sirrus.ysmlib.scene.fbx.FbxEvaluation.Settings.DEFAULT)) {
            var frame=evaluation.evaluate(-1,0);draws=scenes.geometry(d,frame).draws().size();
          }
          var dependencies=scenes.dependencies(d,reference -> resolveByName(root,path.getParent(),reference),limits);
          var surface=com.elfmcys.ysm.model.resource.client.render.FbxSurfaceAdapter.scene(d,dependencies);
          int surfaceTextureMappings=surface.materials().stream().mapToInt(m->m.textures().size()).sum();
          String hostPrepare;
          try { hostPrepare=fbxHostPrepare(path,d,limits); }
          catch(Exception hostFailure) { hostPrepare="REJECTED_"+safe(hostFailure.getClass().getSimpleName()+": "+hostFailure.getMessage()); }
          emit("PARSED",relative,ext,bytes.length,"version="+d.info().version(),"ascii="+d.info().ascii(),"nodes="+d.nodes().size(),
              "meshes="+d.meshes().size(),"skins="+d.skins().size(),"animations="+d.animations().size(),"curves="+d.curves().size(),
              "textures="+d.textures().size(),"resolvedTextures="+dependencies.images().size(),"textureRefs="+safe(d.textures().stream().map(t->t.relativePath().isEmpty()?t.absolutePath():t.relativePath()).filter(s->!s.isEmpty()).toList().toString()),"surfaceMaterials="+surface.materials().size(),
              "surfaceTextureMappings="+surfaceTextureMappings,"hostPrepare="+hostPrepare,"blendShapes="+d.blendShapes().size(),"constraints="+d.constraintCount(),"drawsAtT0="+draws,"warnings="+d.compatibility().diagnostics().size());
        }
        case ".blend" -> {
          try(var in=new GZIPInputStream(new ByteArrayInputStream(bytes))) {
            byte[] header=in.readNBytes(16);emit("UNSUPPORTED_SOURCE",relative,ext,bytes.length,"container=gzip","decompressedHeader="+hex(header),"reason=Blender .blend is not a portable reader input; export glTF/GLB or FBX");
          }
        }
        case ".unitypackage" -> emit("ARCHIVE_ONLY",relative,ext,bytes.length,"container=gzip+tar","reason=Unity serialized assets are not a mesh source; export FBX/glTF/VRM or package extracted source files");
        case ".zip", ".rar" -> emit("ARCHIVE_ONLY",relative,ext,bytes.length,"reason=archive contents audited separately");
        default -> { }
      }
    } catch(Exception failure) {
      emit("REJECTED",relative,ext,size,"error="+safe(failure.getClass().getSimpleName()+": "+failure.getMessage()));
    }
  }
  private static String extension(Path path) { String name=path.getFileName().toString().toLowerCase(Locale.ROOT);int dot=name.lastIndexOf('.');return dot<0?"":name.substring(dot); }
  private static ByteData resolveByName(Path root,Path owner,String reference) throws IOException {
    Path candidate=owner.resolve(reference).normalize();
    if (Files.isRegularFile(candidate)) return new ByteData(Files.readAllBytes(candidate));
    String name=Path.of(reference.replace('\\','/')).getFileName().toString();
    try(var paths=Files.walk(root)) {
      var found=paths.filter(Files::isRegularFile).filter(p->p.getFileName().toString().equalsIgnoreCase(name)).findFirst();
      if(found.isPresent()) return new ByteData(Files.readAllBytes(found.get()));
    }
    throw new IOException("Unresolved FBX texture reference: "+reference);
  }
  private static String packageCheck(Path model,Path auditRoot,SceneProvider scenes,ReadLimits limits) throws Exception {
    Path parent=model.getParent();Path motion;
    try(var paths=Files.walk(auditRoot)) {
      motion=paths.filter(Files::isRegularFile).filter(p->extension(p).equals(".vmd")).findFirst().orElse(null);
    }
    if(motion==null) return "NO_VMD";
    var files=new LinkedHashMap<String,ByteData>();
    try(var paths=Files.walk(parent)) {
      for(var p:paths.filter(Files::isRegularFile).toList()) files.put(parent.relativize(p).toString().replace('\\','/'),new ByteData(Files.readAllBytes(p)));
    }
    files.put("motion.vmd",new ByteData(Files.readAllBytes(motion)));
    String modelName=model.getFileName().toString();
    var pkg=new ScenePackage(new ScenePackage.Source("model",modelName,ScenePackage.Format.PMX),
        new ScenePackage.Settings(1,-1,cc.sirrus.ysmlib.scene.fbx.FbxEvaluation.SkinSpace.BIND_WORLD),
        List.of(new ScenePackage.Source("motion","motion.vmd",ScenePackage.Format.VMD)),List.of(),files);
    var assets=scenes.loadPackage(pkg,limits);var imageCount=scenes.images(assets,limits).images().size();
    try(var playback=scenes.playback(assets,new ScenePackagePlayback.Selection("motion",0),ScenePackagePlayback.Settings.preview(),limits)) {
      var frame=playback.seek(0);var frameLater=playback.seek(1.0);
      return "LOAD_IMAGES_"+imageCount+"_PLAYBACK_DRAWS_"+frame.thirdPerson().draws().size()+"_LATER_"+frameLater.thirdPerson().draws().size();
    }
  }
  private static String fbxHostPrepare(Path model,cc.sirrus.ysmlib.scene.fbx.FbxDocument document,ReadLimits limits) throws Exception {
    Path parent=model.getParent();
    // DCC exports commonly keep Texture/ beside the FBX directory. Use the
    // containing asset folder as the package root so relocations can point to
    // sibling texture files without granting arbitrary filesystem access.
    Path packageRoot=parent.getParent()!=null?parent.getParent():parent;
    var files=new LinkedHashMap<String,ByteData>();
    try(var paths=Files.walk(packageRoot)) {
      for(var p:paths.filter(Files::isRegularFile).toList())
        files.put(packageRoot.relativize(p).toString().replace('\\','/'),new ByteData(Files.readAllBytes(p)));
    }
    String owner=packageRoot.relativize(model).toString().replace('\\','/');var relocations=new ArrayList<ScenePackage.Relocation>();
    for(var texture:document.textures()) {
      if(texture.type()!=0) continue;
      String reference=texture.relativePath().isEmpty()?texture.absolutePath():texture.relativePath();
      if(reference.isEmpty()) continue;
      String target=findPackageTarget(packageRoot,reference);
      if(target!=null && files.containsKey(target)) relocations.add(new ScenePackage.Relocation(owner,reference,target));
    }
    var source=new ScenePackage(new ScenePackage.Source("model",owner,ScenePackage.Format.FBX),
        new ScenePackage.Settings(1,-1,cc.sirrus.ysmlib.scene.fbx.FbxEvaluation.SkinSpace.BIND_WORLD),List.of(),relocations,files);
    var payload=com.elfmcys.ysm.model.resource.client.GeneralMeshModelResources.prepare(source,limits,()->false);
    return "READY_DRAWS_"+payload.assets().model().getClass().getSimpleName()+"_ANIMATIONS_"+payload.animations().size();
  }
  private static String findPackageTarget(Path parent,String reference) throws IOException {
    String name=Path.of(reference.replace('\\','/')).getFileName().toString();
    try(var paths=Files.walk(parent)) {
      return paths.filter(Files::isRegularFile).filter(p->p.getFileName().toString().equalsIgnoreCase(name))
          .map(parent::relativize).map(p->p.toString().replace('\\','/')).findFirst().orElse(null);
    }
  }
  private static String safe(String value) { return value==null?"":value.replace('\\','/').replace('\n',' ').replace('\r',' '); }
  private static String hex(byte[] bytes) { var out=new StringBuilder();for(byte b:bytes) out.append(String.format(Locale.ROOT,"%02x",b));return out.toString(); }
  private static void emit(String status,String path,String ext,long size,String... fields) {
    var out=new StringBuilder("AUDIT status=").append(status).append(" path=").append(path).append(" extension=").append(ext).append(" bytes=").append(size);
    for(String field:fields) out.append(' ').append(field.replace(' ','_'));System.out.println(out);
  }
}
