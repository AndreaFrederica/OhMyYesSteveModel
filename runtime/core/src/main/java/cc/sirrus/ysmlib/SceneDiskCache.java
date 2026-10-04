package cc.sirrus.ysmlib;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.io.*;
import cc.sirrus.ysmlib.scene.mmd.*;
import cc.sirrus.ysmlib.scene.vrm.VrmMaterials;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.security.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.*;

/** Optional portable, content-addressed scene preparation cache. Hosts own the root and budgets.
 * Only immutable CPU values are persisted. A cache failure never makes a valid source unloadable. */
public final class SceneDiskCache {
  public static final String ABI="scene-preparation-1";
  public record Event(String stage,String state,String reason,long elapsedMillis,String item) {}
  public record Parsed(Map<String,ScenePackageAssets.Document> documents,Map<ScenePackageAssets.Reference,String> dependencies,
                       ScenePackageAssets.PreparedGeometry geometry) {
    public Parsed {documents=Map.copyOf(documents);dependencies=Map.copyOf(dependencies);}
  }
  public record Rest(GeometryFrame thirdPerson,GeometryFrame firstPerson,VrmMaterials.Frame vrmMaterials,
                     List<CompatibilityReport.Diagnostic> diagnostics,List<SceneAnimation> animations) {
    public Rest {diagnostics=List.copyOf(diagnostics);animations=List.copyOf(animations);}
  }
  @FunctionalInterface public interface Build<T> {T run() throws IOException;}
  private final Path root;
  private final long diskBudget,entryBudget;
  private final String profile;
  public SceneDiskCache(Path root,String hostProfile,long diskBudget,long entryBudget) {
    this.root=Objects.requireNonNull(root).toAbsolutePath().normalize();
    if(diskBudget<1 || entryBudget<1 || entryBudget>diskBudget)throw new IllegalArgumentException("Invalid scene cache budget");
    this.diskBudget=diskBudget;this.entryBudget=entryBudget;
    profile=ABI+":"+SceneCacheCodec.SCHEMA+":"+Objects.requireNonNull(hostProfile);
  }
  public Session session(SceneProvider services,ReadLimits limits,Consumer<Event> progress,BooleanSupplier cancelled) {
    return new Session(services,limits,progress,cancelled);
  }
  public final class Session {
    private final SceneProvider services;private final ReadLimits limits;private final Consumer<Event> progress;private final BooleanSupplier cancelled;
    private final IdentityHashMap<ByteData,String> hashes=new IdentityHashMap<>();
    private final IdentityHashMap<SceneImage,String> imageKeys=new IdentityHashMap<>();
    private final IdentityHashMap<SceneImage,String> imageLabels=new IdentityHashMap<>();
    private final IdentityHashMap<ScenePackageAssets,String> packageKeys=new IdentityHashMap<>();
    private String item="";
    Session(SceneProvider services,ReadLimits limits,Consumer<Event> progress,BooleanSupplier cancelled) {
      this.services=Objects.requireNonNull(services);this.limits=Objects.requireNonNull(limits);this.progress=Objects.requireNonNull(progress);this.cancelled=Objects.requireNonNull(cancelled);
    }
    private void active(){if(cancelled.getAsBoolean())throw new CancellationException("Scene cache preparation cancelled");}
    private void event(String stage,String state,String reason,long start){progress.accept(new Event(stage,state,reason,(System.nanoTime()-start)/1_000_000,item));}
    private String hash(ByteData data) {return hashes.computeIfAbsent(data,bytes->{active();var digest=SceneCacheCodec.digest();digest.update(bytes.view());return HexFormat.of().formatHex(digest.digest());});}
    private String key(String... parts) {
      var digest=SceneCacheCodec.digest();for(String part:parts){var bytes=part.getBytes(java.nio.charset.StandardCharsets.UTF_8);digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());digest.update(bytes);}
      return HexFormat.of().formatHex(digest.digest());
    }
    private String packageKey(ScenePackage source) throws IOException {
      long bytes=0;for(var data:source.files().values())bytes+=data.size();
      if(bytes>limits.maxBytes() || source.files().size()>limits.maxElements())throw new AssetFormatException("Package source budget exceeded");
      var parts=new ArrayList<String>(List.of(profile,services.profile(),limits.toString(),source.model().toString(),source.settings().toString(),source.animations().toString(),source.relocations().toString()));
      for(var entry:new TreeMap<>(source.files()).entrySet())if(!entry.getKey().equals(SceneModelProfile.PACKAGE_PATH)){parts.add(entry.getKey());parts.add(hash(entry.getValue()));}
      return key(parts.toArray(String[]::new));
    }
    public ScenePackageAssets loadPackage(ScenePackage source) throws IOException {
      item=source.model().path();active();long start=System.nanoTime();event("documents","checking","content-key",start);
      String key=packageKey(source);
      var parsed=derive("documents",key,Parsed.class,source.files(),()->{
        var loaded=services.loadPackage(source,limits);
        ScenePackageAssets.PreparedGeometry geometry=null;
        if(loaded.model() instanceof ScenePackageAssets.Gltf gltf)
          geometry=new ScenePackageAssets.PreparedGeometry(new cc.sirrus.ysmlib.scene.java.GltfSurfaceGeometry().prepare(gltf.value().scene(),limits),null);
        else if(loaded.model() instanceof ScenePackageAssets.Vrm vrm) {
          var prepared=new cc.sirrus.ysmlib.scene.java.GltfSurfaceGeometry().prepare(vrm.value().scene(),limits);
          geometry=new ScenePackageAssets.PreparedGeometry(prepared,new cc.sirrus.ysmlib.scene.vrm.VrmFirstPerson().compile(vrm.value(),prepared));
        }
        return new Parsed(bindAnimations(loaded),loaded.dependencies(),geometry);
      });
      var result=new ScenePackageAssets(source,parsed.documents(),parsed.dependencies(),parsed.geometry());packageKeys.put(result,key);return result;
    }
    private String assetsKey(ScenePackageAssets assets) throws IOException {
      var key=packageKeys.get(assets);if(key==null){key=packageKey(assets.source());packageKeys.put(assets,key);}return key;
    }
    /** Rest preparation is cached separately from parsing; authored profiles participate only here. */
    public Rest rest(ScenePackageAssets assets) throws IOException {
      item=assets.source().model().path();
      var sidecar=assets.source().files().get(SceneModelProfile.PACKAGE_PATH);
      return derive("geometry",key(assetsKey(assets),sidecar==null?"":hash(sidecar)),Rest.class,assets.source().files(),()->{
        try(var player=services.playback(assets,ScenePackagePlayback.Selection.REST,ScenePackagePlayback.Settings.preview().withPhysics(false),limits)) {
          var frame=player.seek(0);var diagnostics=frame.details() instanceof ScenePackagePlayback.Mmd m?m.value().pose().diagnostics():List.<CompatibilityReport.Diagnostic>of();
          return new Rest(frame.thirdPerson(),frame.firstPerson(),frame.details() instanceof ScenePackagePlayback.Vrm v?v.materials():null,diagnostics,services.animations(assets,30));
        }
      });
    }
    /** Host-specific immutable scene adaptation uses the same wire and budget; profile versions belong in the label. */
    public SceneAsset scene(ScenePackageAssets assets,String label,Build<SceneAsset> build) throws IOException {
      item=assets.source().model().path();
      return derive("surface",key(assetsKey(assets),label),SceneAsset.class,assets.source().files(),build);
    }
    public ScenePackageImages images(ScenePackageAssets assets) throws IOException {
      return new ScenePackageImageLoader(services,assets,limits,this::image).load();
    }
    private SceneImage image(ByteData source,String hint,ReadLimits remaining) throws IOException {
      item=hint;
      String key=key(profile,hash(source),hint.toLowerCase(Locale.ROOT),remaining.toString());
      var result=derive("image",key,SceneImage.class,Map.of("source",source),()->services.readImage(source,hint,remaining));
      if(result.rgba().storageBytes()>remaining.maxBytes() || (long)result.width()*result.height()>remaining.maxElements())throw new IOException("Cached image exceeds remaining scene budget");
      imageKeys.put(result,key);imageLabels.put(result,hint);return result;
    }
    public FloatData texturePixels(SceneImage image,SceneImageUsage usage,ReadLimits remaining) throws IOException {
      item=imageLabels.getOrDefault(image,image.format()+" "+image.width()+"x"+image.height());
      long start=System.nanoTime();
      var shared=new cc.sirrus.ysmlib.image.java.SceneImageSamples().passthrough(image,usage,remaining);
      if(shared!=null){active();event("material","hit","decoded-image-pixels",start);return shared;}
      var imageKey=imageKeys.get(image);
      if(imageKey==null) { // Also usable by standalone hosts supplying already decoded images.
        var digest=SceneCacheCodec.digest();SceneCacheCodec.write(new DigestOutputStream(OutputStream.nullOutputStream(),digest),image,Map.of());imageKey=HexFormat.of().formatHex(digest.digest());
      }
      String key=key(profile,imageKey,usage.toString(),remaining.toString());
      var result=derive("material",key,FloatData.class,Map.of(),()->services.texturePixels(image,usage,remaining));
      if(result.size()!=image.rgba().size() || result.storageBytes()>remaining.maxBytes())throw new IOException("Invalid cached material sample shape/budget");
      return result;
    }
    private <T> T derive(String stage,String key,Class<T> type,Map<String,ByteData> external,Build<T> build) throws IOException {
      active();long start=System.nanoTime();event(stage,"checking",key.substring(0,12),start);
      Path path=root.resolve(stage+"-"+key+".ysc");String miss="absent";Throwable cacheError=null;
      try {
        if(Files.exists(path,LinkOption.NOFOLLOW_LINKS)) {
          if(Files.isSymbolicLink(path) || !Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("Cache is not a regular file");
          long size=Files.size(path);if(size<40 || size>entryBudget)throw new IOException("Cache stored-size budget exceeded");
          try(var raw=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)) {
            var payload=new FilterInputStream(raw) {
              long remaining=size-32;
              @Override public int read()throws IOException {if(remaining==0)return -1;int b=in.read();if(b>=0)remaining--;return b;}
              @Override public int read(byte[] b,int offset,int length)throws IOException {if(remaining==0)return -1;active();int n=in.read(b,offset,(int)Math.min(length,remaining));if(n>0)remaining-=n;return n;}
            };
            var digest=SceneCacheCodec.digest();var checked=new DigestInputStream(payload,digest);var input=new DataInputStream(new BufferedInputStream(checked,65536));
            if(input.readInt()!=0x59534331 || !input.readUTF().equals(profile) || !input.readUTF().equals(key))throw new IOException("Cache profile/key mismatch");
            Object decoded=SceneCacheCodec.read(input,external,limits.maxBytes(),limits.maxElements(),limits.maxStringBytes());
            if(input.read()!=-1)throw new IOException("Trailing cache payload");
            byte[] actual=digest.digest();byte[] expected=raw.readNBytes(32);
            if(raw.read()!=-1 || !MessageDigest.isEqual(actual,expected) || !type.isInstance(decoded))throw new IOException("Cache integrity/type mismatch");
            active();event(stage,"hit","disk",start);
            try {Files.setLastModifiedTime(path,FileTime.fromMillis(System.currentTimeMillis()));}catch(IOException ignored) { /* LRU touch is optional. */ }
            return type.cast(decoded);
          }
        }
      } catch(IOException|RuntimeException failure) {if(failure instanceof CancellationException cancel)throw cancel;miss="invalid-or-inaccessible: "+failure;cacheError=failure;}
      event(stage,"building",miss,start);T value=build.run();active();
      Path temp=null;
      try {
        Files.createDirectories(root);temp=Files.createTempFile(root,".scene-",".tmp");
        try(var raw=Files.newOutputStream(temp)) {
          var bounded=new FilterOutputStream(raw) {
            long bytes;
            void reserve(int n) throws IOException {if((bytes+=n)>entryBudget-32)throw new IOException("Cache entry budget exceeded");active();}
            @Override public void write(int b)throws IOException{reserve(1);out.write(b);}
            @Override public void write(byte[] b,int o,int n)throws IOException{reserve(n);out.write(b,o,n);}
          };
          var digest=SceneCacheCodec.digest();var checked=new DigestOutputStream(bounded,digest);var output=new DataOutputStream(new BufferedOutputStream(checked,65536));
          output.writeInt(0x59534331);output.writeUTF(profile);output.writeUTF(key);SceneCacheCodec.write(output,value,external);output.flush();
          raw.write(digest.digest());raw.flush();
        }
        active();commit(temp,path);temp=null;event(stage,"stored",miss,start);
      } catch(IOException|SecurityException failure) {if(cacheError!=null)failure.addSuppressed(cacheError);event(stage,"uncached",failure.toString(),start);}
      finally {if(temp!=null)try{Files.deleteIfExists(temp);}catch(IOException ignored){}}
      return value;
    }
  }
  /** Serializes budget enforcement in this cache owner; independent processes publish complete identical keys atomically. */
  private synchronized void commit(Path temp,Path destination) throws IOException {
    try(var channel=java.nio.channels.FileChannel.open(root.resolve(".budget.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
      final java.nio.channels.FileLock lock;
      try {lock=channel.tryLock();}catch(java.nio.channels.OverlappingFileLockException busy){throw new IOException("Another cache writer is enforcing the disk budget",busy);}
      if(lock==null)throw new IOException("Another process is enforcing the scene disk budget");
      try(lock){commitLocked(temp,destination);}
    }
  }
  private void commitLocked(Path temp,Path destination) throws IOException {
    var files=new ArrayList<Path>();long total=Files.size(temp);
    try(var paths=Files.newDirectoryStream(root,"*.ysc")){for(var path:paths)if(!path.equals(destination)&&Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)){files.add(path);total+=Files.size(path);}}
    files.sort(Comparator.comparingLong(path->{try{return Files.getLastModifiedTime(path,LinkOption.NOFOLLOW_LINKS).toMillis();}catch(IOException e){return Long.MIN_VALUE;}}));
    for(var path:files) {if(total<=diskBudget)break;long size=Files.size(path);Files.deleteIfExists(path);total-=size;}
    if(total>diskBudget)throw new IOException("Scene disk cache budget exhausted");
    Files.move(temp,destination,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
  }
  private static Map<String,ScenePackageAssets.Document> bindAnimations(ScenePackageAssets assets) {
    List<String> bones,morphs;
    if(assets.model() instanceof ScenePackageAssets.Pmx p){bones=p.value().bones().stream().map(b->b.names().local()).toList();morphs=p.value().morphs().stream().map(m->m.names().local()).toList();}
    else if(assets.model() instanceof ScenePackageAssets.Pmd p){bones=p.value().bones().stream().map(PmdDocument.Bone::name).toList();morphs=p.value().morphs().stream().map(PmdDocument.Morph::name).toList();}
    else return assets.documents();
    var boneMap=uniqueNames(bones);var morphMap=uniqueNames(morphs);var documents=new LinkedHashMap<>(assets.documents());
    documents.replaceAll((id,doc)->{
      if(doc instanceof ScenePackageAssets.Vmd v)return new ScenePackageAssets.Vmd(v.value(),new AnimationImport(bound(v.animation().clip(),boneMap,morphMap),v.animation().compatibility()));
      if(doc instanceof ScenePackageAssets.Vpd v)return new ScenePackageAssets.Vpd(v.value(),bound(v.animation(),boneMap,morphMap));
      return doc;
    });return documents;
  }
  private static Map<String,Integer> uniqueNames(List<String> names) {var result=new HashMap<String,Integer>();for(int i=0;i<names.size();i++)result.merge(names.get(i),i,(a,b)->-1);return result;}
  private static AnimationClip bound(AnimationClip clip,Map<String,Integer> bones,Map<String,Integer> morphs) {
    return new AnimationClip(clip.name(),clip.tracks().stream().map(t->{var names=switch(t.property()) {
      case TRANSLATION,ROTATION,IK_ENABLED,BONE_PHYSICS_ENABLED -> bones;case MORPH_WEIGHTS -> morphs;default -> Map.<String,Integer>of();};
      return new AnimationClip.Track(t.property(),t.targetIndex()>=0?t.targetIndex():names.getOrDefault(t.binding(),-1),t.binding(),t.component(),t.curve());
    }).toList(),clip.sourceFramesPerSecond());
  }
}
