package cc.sirrus.ysmlib;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.fbx.FbxEvaluation;
import cc.sirrus.ysmlib.scene.io.ReadLimits;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/** Filesystem authoring service shared by standalone hosts. No Minecraft, UI or GPU dependencies. */
public final class SceneAuthoring {
    private SceneAuthoring() {}
    public static final ReadLimits LIMITS=new ReadLimits(256*1024*1024,100_000_000,1024*1024);
    private static final Set<String> RESOURCES=Set.of("png","jpg","jpeg","bmp","tga","webp","avif","ztx","sph","spa","bin","vmd","vpd","vrma","txt","md","license");
    public static Path sidecar(Path source) {return source.resolveSibling(source.getFileName()+SceneModelProfile.SUFFIX);}
    public static String extension(Path source) {
        String n=source.getFileName().toString().toLowerCase(Locale.ROOT);int i=n.lastIndexOf('.');return i<0?"":n.substring(i+1);
    }
    public static ScenePackage capture(Path input,ReadLimits limits,Consumer<String> progress) throws IOException {
        Path source=input.toRealPath();progress.accept("Reading source: "+source.getFileName());
        if(extension(source).equals("yscene"))return YsmRuntime.scenes().readPackage(read(source,limits.maxBytes()),limits);
        var format=switch(extension(source)) {
            case "pmx"->ScenePackage.Format.PMX;case "pmd"->ScenePackage.Format.PMD;
            case "vrm"->ScenePackage.Format.VRM;case "glb","gltf"->ScenePackage.Format.GLTF;case "fbx"->ScenePackage.Format.FBX;
            case "blend","unitypackage"->throw new IOException("Export this authoring project to GLB/VRM/FBX first; Blender and Unity projects are not portable scene files.");
            default->throw new IOException("Unsupported model extension: "+extension(source));
        };
        Path root=source.getParent();String owner=source.getFileName().toString();
        var files=new LinkedHashMap<String,ByteData>();files.put(owner,read(source,limits.maxBytes()));long total=files.get(owner).size();
        int visited=0;
        progress.accept("Collecting sibling dependencies (bounded to model directory)");
        try(var walk=Files.walk(root)) {
            var it=walk.iterator();while(it.hasNext()) {
                Path path=it.next();if(++visited>100_000)throw new IOException("Directory exceeds 100000 entries; move the model into a dedicated directory");
                if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||path.equals(source)||!RESOURCES.contains(extension(path)))continue;
                String ext=extension(path);
                if((ext.equals("vmd")||ext.equals("vpd")) && format!=ScenePackage.Format.PMX && format!=ScenePackage.Format.PMD)continue;
                if(ext.equals("vrma")&&format!=ScenePackage.Format.VRM)continue;
                String relative=ScenePackage.checkedPath(root.relativize(path).toString().replace('\\','/'));
                long size=Files.size(path);if(size>limits.maxBytes()-total)throw new IOException("Dependency budget exceeded at "+relative+" (limit "+limits.maxBytes()+" bytes)");
                progress.accept("Reading dependency: "+relative+" ("+size+" bytes)");
                var bytes=read(path,limits.maxBytes()-total);total+=bytes.size();files.put(relative,bytes);
            }
        }
        var relocations=new ArrayList<ScenePackage.Relocation>();var refs=new ArrayList<String>();
        progress.accept("Resolving texture references");
        if(format==ScenePackage.Format.PMX)refs.addAll(YsmRuntime.scenes().readPmx(files.get(owner),limits).textures());
        else if(format==ScenePackage.Format.PMD) {
            var doc=YsmRuntime.scenes().readPmd(files.get(owner),limits);
            for(var m:doc.materials())refs.addAll(Arrays.asList(m.textureNames().split("\\*",-1)));refs.addAll(doc.toonTextures());
        } else if(format==ScenePackage.Format.FBX) {
            for(var tex:YsmRuntime.scenes().readFbx(files.get(owner),limits).textures())if(tex.type()==0)refs.add(tex.relativePath().isEmpty()?tex.absolutePath():tex.relativePath());
        }
        for(String ref:new LinkedHashSet<>(refs)) {
            if(ref.isBlank())continue;String normalized=ref.replace('\\','/');if(files.containsKey(normalized))continue;
            String leaf=normalized.substring(normalized.lastIndexOf('/')+1);
            var matches=files.keySet().stream().filter(p->p.substring(p.lastIndexOf('/')+1).equalsIgnoreCase(leaf)).toList();
            if(matches.size()>1)throw new IOException("Ambiguous texture reference "+ref+": "+matches);
            if(matches.size()==1){relocations.add(new ScenePackage.Relocation(owner,ref,matches.get(0)));progress.accept("Relocated texture: "+ref+" -> "+matches.get(0));}
        }
        var motions=new ArrayList<ScenePackage.Source>();
        files.keySet().stream().sorted().forEach(path->{String ext=extension(Path.of(path));
            if(Set.of("vmd","vpd","vrma").contains(ext))motions.add(new ScenePackage.Source("motion-"+motions.size(),path,ScenePackage.Format.valueOf(ext.toUpperCase(Locale.ROOT))));});
        return new ScenePackage(new ScenePackage.Source("model",owner,format),new ScenePackage.Settings(
            format==ScenePackage.Format.PMX||format==ScenePackage.Format.PMD?.08:1,-1,FbxEvaluation.SkinSpace.BIND_WORLD),motions,relocations,files);
    }
    private static ByteData read(Path path,long budget) throws IOException {
        if(Files.isSymbolicLink(path)||!Files.isRegularFile(path)||Files.size(path)>budget)throw new IOException("Invalid file or byte budget exceeded: "+path);
        try(var in=Files.newInputStream(path)){byte[] data=in.readNBytes((int)Math.min(Integer.MAX_VALUE,budget+1));if(data.length>budget)throw new IOException("File grew beyond budget: "+path);return new ByteData(data);}
    }
    public static Session open(Path source,Consumer<String> progress) throws IOException {return open(source,LIMITS,progress);}
    public static Session open(Path input,ReadLimits limits,Consumer<String> progress) throws IOException {
        Path source=input.toRealPath();long stamp=Files.getLastModifiedTime(source).toMillis(),length=Files.size(source);
        ScenePackage pack=capture(source,limits,progress);progress.accept("Parsing model, skeleton and animations");
        byte[] expected=readProfileBytes(sidecar(source));
        if(expected!=null) {
            var files=new LinkedHashMap<>(pack.files());files.put(SceneModelProfile.PACKAGE_PATH,new ByteData(expected));
            pack=new ScenePackage(pack.model(),pack.settings(),pack.animations(),pack.relocations(),files);
        }
        var assets=YsmRuntime.scenes().loadPackage(pack,limits);var bones=SceneSkeleton.of(assets);
        var embedded=pack.files().get(SceneModelProfile.PACKAGE_PATH);
        SceneModelProfile profile=expected!=null?YsmRuntime.scenes().readModelProfile(new ByteData(expected)):embedded!=null?YsmRuntime.scenes().readModelProfile(embedded):null;
        progress.accept("Evaluating rest pose (physics OFF)");
        double[] referenceBounds;
        try(var player=YsmRuntime.scenes().playback(assets,ScenePackagePlayback.Selection.REST,ScenePackagePlayback.Settings.previewUi().withPhysics(false),limits)) {
            var frame=player.seek(0);double[] bounds=bounds(frame.thirdPerson(),null);referenceBounds=bounds;
            if(profile==null)profile=SceneModelProfile.defaults(pack.settings().metersPerUnit(),Math.max(1e-6,bounds[4]-bounds[1]),bounds[1]).withBones(SceneSkeleton.suggest(bones));
        }
        var session=new Session(source,assets,profile,expected,limits,stamp,length,referenceBounds);session.validate(profile);progress.accept("Ready: "+bones.size()+" bones, physics OFF");return session;
    }
    /** Transform to common up-axis coordinates, optionally applying authored placement in metres. */
    public static Vec3 position(Vec3 v,SceneAsset.Coordinates coordinates,SceneModelProfile profile) {
        float z=coordinates.rightHanded()?v.z():-v.z();
        Vec3 up=switch(coordinates.upAxis()){case "Y"->new Vec3(v.x(),v.y(),z);case "Z"->new Vec3(v.x(),z,-v.y());case "X"->new Vec3(-v.y(),v.x(),z);default->throw new IllegalArgumentException("Unknown up axis");};
        if(profile==null)return up;
        var p=profile.placement();double s=p.effectiveScale(),a=Math.toRadians(p.yaw());
        return new Vec3((float)((up.x()*Math.cos(a)+up.z()*Math.sin(a))*s+p.x()),(float)((up.y()-p.footY())*s+p.y()),(float)((-up.x()*Math.sin(a)+up.z()*Math.cos(a))*s+p.z()));
    }
    /** Bounds over referenced, visible vertices only. */
    public static double[] bounds(GeometryFrame frame,SceneModelProfile profile) {
        double[] b={Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY};
        for(var draw:frame.draws())if(draw.visible())for(var prim:draw.geometry().primitives()) {
            var pos=prim.attributes().get("POSITION").values();int n=prim.indices().size()==0?prim.vertexCount():prim.indices().size();
            for(int i=0;i<n;i++){int j=(prim.indices().size()==0?i:prim.indices().get(i))*3;var v=position(draw.world().transformPoint(new Vec3(pos.get(j),pos.get(j+1),pos.get(j+2))),frame.coordinates(),profile);
                b[0]=Math.min(b[0],v.x());b[1]=Math.min(b[1],v.y());b[2]=Math.min(b[2],v.z());b[3]=Math.max(b[3],v.x());b[4]=Math.max(b[4],v.y());b[5]=Math.max(b[5],v.z());}
        }
        if(!Double.isFinite(b[0]))throw new IllegalArgumentException("Model has no visible geometry");return b;
    }
    public static byte[] readProfileBytes(Path path) throws IOException {return Files.exists(path,LinkOption.NOFOLLOW_LINKS)?read(path,1024*1024).copy():null;}
    public static void atomicWrite(Path output,byte[] data,byte[] expected,boolean replace) throws IOException {
        Path target=output.toAbsolutePath().normalize();if(Files.isSymbolicLink(target))throw new IOException("Refusing symbolic-link output: "+target);
        if(replace && !Arrays.equals(readProfileBytes(target),expected))throw new IOException("Configuration changed externally; reopen before saving");
        if(!replace && Files.exists(target))throw new FileAlreadyExistsException(target.toString());
        Files.createDirectories(target.getParent());Path tmp=Files.createTempFile(target.getParent(),".omysm-",".tmp");
        try{Files.write(tmp,data);if(replace)Files.move(tmp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            else Files.move(tmp,target); // No REPLACE_EXISTING: a concurrently created output is preserved.
        }finally{Files.deleteIfExists(tmp);}
    }
    public static final class Session {
        private final Path source;private final ScenePackageAssets assets;private final ReadLimits limits;private final long stamp,length;
        private final double[] restBounds;private final List<SceneSkeleton.Bone> bones;private final List<SceneAnimation> animations;
        private byte[] expected;private SceneModelProfile profile;
        private Session(Path source,ScenePackageAssets assets,SceneModelProfile profile,byte[] expected,ReadLimits limits,long stamp,long length,double[] restBounds){this.source=source;this.assets=assets;this.profile=profile;this.expected=expected;this.limits=limits;this.stamp=stamp;this.length=length;this.restBounds=restBounds.clone();this.bones=SceneSkeleton.of(assets);this.animations=YsmRuntime.scenes().animations(assets,30);}
        public Path source(){return source;} public ScenePackageAssets assets(){return assets;} public SceneModelProfile profile(){return profile;}
        public List<SceneSkeleton.Bone> bones(){return bones;} public List<SceneAnimation> animations(){return animations;}
        /** Stable camera bounds from rest geometry; animation root motion must remain visible. */
        public double[] referenceBounds(SceneModelProfile profile){
            double[] b={Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY};
            for(int i=0;i<8;i++){var p=position(new Vec3((float)restBounds[(i&1)==0?0:3],(float)restBounds[(i&2)==0?1:4],(float)restBounds[(i&4)==0?2:5]),new SceneAsset.Coordinates(true,1,"Y"),profile);
                b[0]=Math.min(b[0],p.x());b[1]=Math.min(b[1],p.y());b[2]=Math.min(b[2],p.z());b[3]=Math.max(b[3],p.x());b[4]=Math.max(b[4],p.y());b[5]=Math.max(b[5],p.z());}return b;
        }
        public void validate(SceneModelProfile value){
            SceneSkeleton.validate(bones(),value.bones());var clips=animations();
            for(var author:value.metadata().authors())if(!author.avatar().isEmpty()&&!assets.source().files().containsKey(author.avatar()))throw new IllegalArgumentException("Missing author avatar: "+author.avatar());
            value.validateAnimations(assets.source(),clips);
        }
        public void profile(SceneModelProfile value){validate(value);profile=value;}
        public SceneModelProfile suggest(){var map=new LinkedHashMap<>(SceneSkeleton.suggest(bones()));map.putAll(profile.bones());var used=new HashSet<Integer>();
            profile.bones().values().forEach(b->{if(b.index()>=0)used.add(b.index());});map.entrySet().removeIf(e->!profile.bones().containsKey(e.getKey())&&used.contains(e.getValue().index()));return profile.withBones(map);}
        public SceneModelProfile bind(String role,int index,double pitch,double yaw,double roll,double weight){var map=new LinkedHashMap<>(profile.bones());var b=index<0?new SceneModelProfile.BoneBinding(-1,"","",0,0,0,1):SceneSkeleton.bind(bones(),index);var old=profile.bones().get(role);if(old!=null&&old.index()==index)b=old;map.put(role,b.withAxes(pitch,yaw,roll,weight));return profile.withBones(map);}
        public ScenePackagePlayback playback(ScenePackagePlayback.Selection selection) throws IOException {return YsmRuntime.scenes().playback(assets,selection,ScenePackagePlayback.Settings.previewUi().withPhysics(false).withModelProfile(profile),limits);}
        public ScenePackagePlayback.Selection action(String name){var action=profile.actions().get(name);if(action==null)throw new IllegalArgumentException("No explicit mapping for action: "+name);return action.resolve(assets.source());}
        public void save(Path output) throws IOException {
            validate(profile);if(Files.size(source)!=length||Files.getLastModifiedTime(source).toMillis()!=stamp)throw new IOException("Source model changed; reopen before saving");
            byte[] bytes=YsmRuntime.scenes().writeModelProfile(profile).copy();boolean same=output.toAbsolutePath().normalize().equals(sidecar(source));
            atomicWrite(output,bytes,same?expected:null,same);if(same)expected=bytes;
        }
        public void pack(Path output) throws IOException {
            validate(profile);var original=assets.source();var files=new LinkedHashMap<>(original.files());files.put(SceneModelProfile.PACKAGE_PATH,YsmRuntime.scenes().writeModelProfile(profile));
            var pack=new ScenePackage(original.model(),original.settings(),original.animations(),original.relocations(),files);
            atomicWrite(output,YsmRuntime.scenes().writePackage(pack,limits).copy(),null,false);
        }
    }
}
