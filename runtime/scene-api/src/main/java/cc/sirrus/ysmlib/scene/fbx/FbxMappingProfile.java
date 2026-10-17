package cc.sirrus.ysmlib.scene.fbx;

import cc.sirrus.ysmlib.scene.*;
import java.security.MessageDigest;
import java.util.*;

/**
 * Explicit source-level FBX choices.  The ordinary model profile describes
 * YSM semantics; this profile only resolves ambiguous FBX source data.
 */
public record FbxMappingProfile(int schemaVersion, String sourceHash,
    Map<Integer,Integer> skinByMeshElement,
    Map<Integer,MaterialOverride> materialByElement) {
  public static final int SCHEMA_VERSION = 1;
  public static final String PACKAGE_PATH = "_omysm/fbx-mapping.json";
  public static final String SUFFIX = ".fbxmap.json";

  /** Core PBR material values recovered from an external package (for example Unity .mat). */
  public record MaterialOverride(String name, String baseColor, String normal, String emissive,
      String alphaMode, float alphaCutoff) {
    public MaterialOverride {
      name=Objects.requireNonNullElse(name, "");
      baseColor=Objects.requireNonNullElse(baseColor, "");
      normal=Objects.requireNonNullElse(normal, "");
      emissive=Objects.requireNonNullElse(emissive, "");
      alphaMode=Objects.requireNonNullElse(alphaMode, "OPAQUE").toUpperCase(Locale.ROOT);
      if (!Set.of("OPAQUE", "MASK", "BLEND").contains(alphaMode))
        throw new IllegalArgumentException("Unsupported FBX material alpha mode: "+alphaMode);
      if (!Float.isFinite(alphaCutoff) || alphaCutoff<0 || alphaCutoff>1)
        throw new IllegalArgumentException("Invalid FBX material alpha cutoff");
    }
  }

  public FbxMappingProfile {
    if (schemaVersion != SCHEMA_VERSION) throw new IllegalArgumentException("Unsupported FBX mapping schema: "+schemaVersion);
    Objects.requireNonNull(sourceHash);
    if (!sourceHash.isEmpty() && !sourceHash.matches("[0-9a-f]{64}"))
      throw new IllegalArgumentException("FBX mapping sourceHash must be lowercase SHA-256");
    Objects.requireNonNull(skinByMeshElement); Objects.requireNonNull(materialByElement);
    if (skinByMeshElement.size() > 100_000) throw new IllegalArgumentException("Too many FBX mapping entries");
    var copy=new LinkedHashMap<Integer,Integer>();
    for (var e:skinByMeshElement.entrySet()) {
      if (e.getKey()==null || e.getValue()==null || e.getKey()<0 || e.getValue()<0)
        throw new IllegalArgumentException("FBX mapping IDs must be non-negative");
      copy.put(e.getKey(),e.getValue());
    }
    skinByMeshElement=Map.copyOf(copy);
    var materials=new LinkedHashMap<Integer,MaterialOverride>();
    for (var e:materialByElement.entrySet()) {
      if (e.getKey()==null || e.getValue()==null || e.getKey()<0)
        throw new IllegalArgumentException("FBX material IDs must be non-negative");
      materials.put(e.getKey(),e.getValue());
    }
    materialByElement=Map.copyOf(materials);
  }

  public FbxMappingProfile(int schemaVersion,String sourceHash,Map<Integer,Integer> skinByMeshElement) {
    this(schemaVersion,sourceHash,skinByMeshElement,Map.of());
  }

  public static FbxMappingProfile empty() { return new FbxMappingProfile(SCHEMA_VERSION,"",Map.of(),Map.of()); }

  /** Applies only source-level Skin selection and leaves the original bytes untouched. */
  public FbxDocument apply(FbxDocument source) {
    Objects.requireNonNull(source);
    if (!sourceHash.isEmpty()) {
      var digest=sha256(source.source().copy());
      if (!MessageDigest.isEqual(digest,hex(sourceHash))) throw new IllegalArgumentException("FBX mapping sourceHash does not match source bytes");
    }
    if (skinByMeshElement.isEmpty()) return source;
    var meshes=new ArrayList<FbxDocument.Mesh>(source.meshes());
    var meshIds=new HashSet<Integer>();for(var mesh:meshes)meshIds.add(mesh.element());
    for (var entry:skinByMeshElement.entrySet()) {
      int meshId=entry.getKey(), requested=entry.getValue();
      if (!meshIds.contains(meshId)) throw new IllegalArgumentException("FBX mapping mesh element does not exist: "+meshId);
      int meshIndex=0;while(meshes.get(meshIndex).element()!=meshId)meshIndex++;
      var mesh=meshes.get(meshIndex);int selected=-1;int requestedIndex=resolveSkin(source,requested);
      if(requestedIndex<0) throw new IllegalArgumentException("FBX mapping Skin does not exist: "+requested);
      for(int i=0;i<mesh.skins().size();i++) {
        int token=mesh.skins().get(i);int resolved=resolveSkin(source,token);
        if (resolved>=0 && (token==requested || resolved==requestedIndex)) { selected=token;break; }
      }
      if (selected<0) throw new IllegalArgumentException("FBX mapping Skin "+requested+" is not attached to mesh "+meshId);
      meshes.set(meshIndex,new FbxDocument.Mesh(mesh.element(),mesh.positions(),mesh.normals(),mesh.controlVertices(),mesh.uvSets(),mesh.colorSets(),mesh.faces(),new IntData(selected),mesh.blendDeformers(),mesh.cacheDeformers(),mesh.subdivisionLevel(),mesh.allDeformers(),mesh.positionW(),mesh.normalW(),mesh.generatedNormals()));
    }
    return new FbxDocument(source.source(),source.info(),source.elements(),source.nodes(),meshes,source.skins(),source.animations(),source.layers(),source.curves(),source.textures(),source.blendShapes(),source.details(),source.compatibility());
  }

  private static int resolveSkin(FbxDocument source,int id) {
    for(int i=0;i<source.skins().size();i++) if(source.skins().get(i).element()==id)return i;
    for(var element:source.elements()) if(element.typedId()==id)
      for(int i=0;i<source.skins().size();i++) if(source.skins().get(i).element()==element.id())return i;
    if(id>=0&&id<source.skins().size())return id;
    return -1;
  }
  private static byte[] sha256(byte[] bytes) { try { return MessageDigest.getInstance("SHA-256").digest(bytes); } catch(Exception e) { throw new IllegalStateException(e); } }
  private static byte[] hex(String value) { byte[] out=new byte[value.length()/2];for(int i=0;i<out.length;i++)out[i]=(byte)Integer.parseInt(value.substring(i*2,i*2+2),16);return out; }
}
