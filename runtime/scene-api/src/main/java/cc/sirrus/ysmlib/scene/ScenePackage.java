package cc.sirrus.ysmlib.scene;

import java.util.*;

/** A closed, immutable source package. Paths are package names, never host filesystem capabilities. */
public record ScenePackage(Source model,Settings settings,List<Source> animations,List<Relocation> relocations,
    Map<String,ByteData> files) {
  public static final String SCHEMA="ysmlib/scene-package";
  public static final String VERSION="0.1.0-unstable";
  public static final String MANIFEST="scene.json";
  public enum Format { GLTF,VRM,FBX,PMX,PMD,PMM,VMD,VPD,VRMA,BVH }
  public enum ReferenceSyntax { URI, FILE_PATH }
  /** Explicit presentation scale after format evaluation; -1 selects all nodes, otherwise a source scene. */
  public record Settings(double metersPerUnit,int scene,cc.sirrus.ysmlib.scene.fbx.FbxEvaluation.SkinSpace fbxSkinSpace) {
    public Settings {
      if(!Double.isFinite(metersPerUnit) || metersPerUnit<=0 || scene< -1) throw new IllegalArgumentException("Invalid scene package settings");
      Objects.requireNonNull(fbxSkinSpace);
    }
  }
  public record Source(String id,String path,Format format) {
    public Source {
      if(Objects.requireNonNull(id).isBlank()) throw new IllegalArgumentException("Empty source id");
      path=checkedPath(path);Objects.requireNonNull(format);
    }
  }
  /** Exact source reference scoped to its owning file. Original workstation paths stay inert. */
  public record Relocation(String owner,String reference,String target) {
    public Relocation {
      owner=checkedPath(owner);target=checkedPath(target);
      if(Objects.requireNonNull(reference).isEmpty() || reference.indexOf('\0')>=0)
        throw new IllegalArgumentException("Invalid source reference");
    }
  }
  public ScenePackage {
    Objects.requireNonNull(model);Objects.requireNonNull(settings);animations=List.copyOf(animations);relocations=List.copyOf(relocations);
    files=Map.copyOf(files);var paths=new HashSet<String>();
    for(var entry:files.entrySet()) {
      String path=checkedPath(entry.getKey());Objects.requireNonNull(entry.getValue());
      if(path.equals(MANIFEST)) throw new IllegalArgumentException("Reserved package manifest name");
      paths.add(path);
    }
    for(String path:paths) for(int end=path.indexOf('/');end>=0;end=path.indexOf('/',end+1))
      if(paths.contains(path.substring(0,end))) throw new IllegalArgumentException("Package file/directory conflict: "+path);
    var ids=new HashSet<String>();ids.add(model.id());requireFile(files,model.path());
    if(Set.of(Format.VMD,Format.VPD,Format.VRMA,Format.BVH).contains(model.format()))
      throw new IllegalArgumentException("Animation-only format cannot be the model source");
    if(model.format()!=Format.GLTF && model.format()!=Format.VRM && settings.scene()!=-1)
      throw new IllegalArgumentException("This source format has no scene index; use -1");
    for(var source:animations) {
      if(!ids.add(source.id())) throw new IllegalArgumentException("Duplicate source id: "+source.id());
      requireFile(files,source.path());
      if(Set.of(Format.VRM,Format.PMX,Format.PMD,Format.PMM).contains(source.format()))
        throw new IllegalArgumentException("Invalid animation source format");
    }
    var mappings=new HashMap<String,Set<String>>();
    for(var mapping:relocations) {
      requireFile(files,mapping.owner());requireFile(files,mapping.target());
      if(!mappings.computeIfAbsent(mapping.owner(),k->new HashSet<>()).add(mapping.reference()))
        throw new IllegalArgumentException("Duplicate package relocation");
    }
  }
  public static String checkedPath(String path) {
    Objects.requireNonNull(path);
    if(path.isEmpty() || path.startsWith("/") || path.indexOf('\\')>=0 || path.indexOf(':')>=0 || path.indexOf('\0')>=0)
      throw new IllegalArgumentException("Invalid package path: "+path);
    for(String part:path.split("/",-1)) if(part.isEmpty() || part.equals(".") || part.equals(".."))
      throw new IllegalArgumentException("Noncanonical package path: "+path);
    return path;
  }
  private static void requireFile(Map<String,ByteData> files,String path) {
    if(!files.containsKey(path)) throw new IllegalArgumentException("Missing package file: "+path);
  }
}
