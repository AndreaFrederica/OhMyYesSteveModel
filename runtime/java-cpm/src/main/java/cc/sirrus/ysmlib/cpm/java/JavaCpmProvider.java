package cc.sirrus.ysmlib.cpm.java;

import cc.sirrus.ysmlib.cpm.*;
import cc.sirrus.ysmlib.cpm.CpmDocument.*;
import cc.sirrus.ysmlib.cpm.CpmValue.*;
import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.io.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static cc.sirrus.ysmlib.cpm.java.CpmJson.*;

/** Stateless Java source parser. Dynamic fields are preserved, never evaluated during import. */
public final class JavaCpmProvider implements CpmProvider {
    @Override public CpmCompiledModel compile(CpmDocument document) { return CpmSceneCompiler.compile(document); }
    @Override public CpmCompiledModel compileCanonical(CpmDocument document) { return CpmSceneCompiler.compileCanonical(document); }
    @Override public Optional<CpmPoseSession> createPoseSession(CpmDocument document) {
        return Optional.of(new JavaCpmPoseSession(document));
    }
    private static final Set<String> ATTACHMENTS=Set.of("none","helmet","chestplate_body","chestplate_left","chestplate_right",
        "leggings_body","leggings_left","leggings_right","boots_left","boots_right","item_left","item_right",
        "cape","skull","elytra","parrot_left","parrot_right");
    private static final Set<String> BIPED=Set.of("none","head","body","left_arm","right_arm","left_leg","right_leg");
    private static final Set<String> LEGACY_HOST=Set.of("none","head","body","left_arm","right_arm","left_leg","right_leg",
        "head_overlay","body_overlay","left_arm_overlay","right_arm_overlay","left_leg_overlay","right_leg_overlay");
    private static final Set<String> LEGACY_FIELDS=Set.of("modelId","modelName","author","version","hide","variables","tickVars",
        "skeleton","eyeHeight","boundingBox","bones","fpLeft","fpRight","fpList");
    private static final Set<String> REMAKE_FIELDS=Set.of("name","version","author","description","url","accessory","default_scale",
        "size","eye_height","skeleton","parts","shadow_size","particles","items","addons");
    public String profile() { return "ysmlib-cpm-source-1"; }
    public Optional<Format> detect(ByteData mainJson,ReadLimits limits) throws IOException {
        return format(new CpmJson(limits).read(mainJson,"model.json"));
    }
    private Optional<Format> format(ObjectValue root) throws AssetFormatException {
        boolean legacy=root.fields().containsKey("modelId")||root.fields().containsKey("modelName")||root.fields().containsKey("bones");
        boolean remake=root.fields().containsKey("parts")||root.fields().containsKey("accessory");
        if(legacy&&remake) throw error("Ambiguous CPM model.json: both source formats are present");
        return legacy?Optional.of(Format.CUSTOM_MODEL):remake?Optional.of(Format.CUSTOM_PLAYER_MODEL):Optional.empty();
    }
    public CpmGeometry readGeometry(ByteData source,ReadLimits limits) throws IOException {
        return new CpmGeometryReader().read(new CpmJson(limits).read(source,"geometry"));
    }
    public CpmDocument read(Map<String,ByteData> input,ReadLimits limits) throws IOException {
        Objects.requireNonNull(input);Objects.requireNonNull(limits);
        if(input.size()>limits.maxElements()) throw error("CPM file count budget exceeded");
        var files=new LinkedHashMap<String,ByteData>();long total=0;
        for(var entry:input.entrySet()) {
            String path=entry.getKey();
            try { ScenePackage.checkedPath(path); }
            catch(IllegalArgumentException failure) { throw new AssetFormatException("Invalid CPM package path: "+path,failure); }
            if(path.getBytes(StandardCharsets.UTF_8).length>limits.maxStringBytes()) throw error("CPM path budget exceeded");
            var bytes=Objects.requireNonNull(entry.getValue());total+=bytes.size();
            if(total>limits.maxBytes()) throw error("CPM package byte budget exceeded");
            files.put(path,bytes);
        }
        var main=files.get("model.json");if(main==null) throw error("CPM package requires root model.json");
        if(files.keySet().stream().filter(p->p.equalsIgnoreCase("model.json")).count()!=1) throw error("Ambiguous CPM main filename");
        var json=new CpmJson(limits);var root=json.read(main,"model.json");
        var kind=format(root).orElseThrow(()->error("Unrecognized CPM model.json"));
        var diagnostics=new ArrayList<CompatibilityReport.Diagnostic>();
        // Both upstream CPM readers are Gson based and therefore retain the
        // last value when an authoring tool emitted a duplicate member. Keep
        // the source bytes and expose a diagnostic instead of rejecting an
        // otherwise usable pack (notably 200-Ori's duplicate attachment).
        for(String at:duplicates(root,"model.json")) diagnostics.add(new CompatibilityReport.Diagnostic(
            CompatibilityReport.Severity.WARNING,at,"CPM Gson uses the last duplicate JSON member; all occurrences and original bytes are retained"));
        for(String key:root.fields().keySet()) if(!(kind==Format.CUSTOM_MODEL?LEGACY_FIELDS:REMAKE_FIELDS).contains(key))
            diagnostics.add(new CompatibilityReport.Diagnostic(CompatibilityReport.Severity.WARNING,"model.json."+key,
                "Unknown source field retained without evaluation"));
        var coverage=new ArrayList<CompatibilityReport.Feature>();
        coverage.add(new CompatibilityReport.Feature("cpm.source",CompatibilityReport.Level.READ,true,
            "Source structures only; geometry baking, expressions, scripts, physics and host rendering are not implemented"));
        return kind==Format.CUSTOM_MODEL?legacy(root,files,coverage,diagnostics):remake(root,files,json,coverage,diagnostics);
    }
    private Legacy legacy(ObjectValue root,Map<String,ByteData> files,List<CompatibilityReport.Feature> coverage,
                          List<CompatibilityReport.Diagnostic> diagnostics) throws AssetFormatException {
        String id=required(root,"modelId");if(!id.matches("[a-z_][a-z0-9_]*")) throw error("Invalid CustomModel modelId");
        var metadata=new Metadata(id,required(root,"modelName"),scalarString(root.fields().get("version"),"version",""),
            root.fields().containsKey("author")?List.of(string(root,"author","")):List.of(),"","");
        for(String key:List.of("hide","fpLeft","fpRight","fpList")) stringList(root,key);
        // CustomModel's original Gson loader calls getAsString() for
        // variables. Gson accepts JSON numbers and booleans there too, and
        // several archived packs rely on that coercion.
        for(var entry:objectOrEmpty(root,"variables").fields().entrySet()) scalarString(entry.getValue(),"variables."+entry.getKey(),"");
        for(var entry:objectOrEmpty(root,"tickVars").fields().entrySet()) {
            var value=array(entry.getValue(),"tickVars."+entry.getKey());
            if(value.size()!=3) throw error("tickVars entry requires type, initial value and update expression");
            String type=string(value.get(0),"tickVars type");
            if(!Set.of("float","bool","boolean").contains(type)) throw error("Unknown tickVars type: "+type);
            // Legacy Gson getAsBoolean accepts numeric initial values (the official Tetris uses 0).
            // Preserve the scalar without applying that coercion during source reading.
            if(type.equals("float")||!(value.get(1) instanceof NumberValue)) dynamic(value.get(1),!type.equals("float"),"tickVars initial value");
            dynamic(value.get(2),!type.equals("float"),"tickVars update");
        }
        for(var entry:objectOrEmpty(root,"skeleton").fields().entrySet()) dynamicVector(entry.getValue(),3,"skeleton."+entry.getKey());
        dimensions(root,"boundingBox",2);dimensions(root,"eyeHeight",1);
        var bones=new ArrayList<LegacyBone>();var names=new HashSet<>(LEGACY_HOST);var customIndices=new HashMap<String,Integer>();
        for(var item:array(root,"bones")) {
            var bone=object(item,"bone");String authoredName=required(bone,"id");String name=authoredName;
            boolean duplicateAuthored=customIndices.containsKey(authoredName);
            // CustomModel resolves vanilla ids before its custom id map. Keep
            // that distinction in the common IR so a custom `head` cannot
            // become a self-parenting replacement for the host head.
            if (LEGACY_HOST.contains(name)) name="__cpm_"+name;
            if (customIndices.containsKey(name)) {
                int suffix=2; String candidate=name+"_"+suffix;
                while (customIndices.containsKey(candidate)) candidate=name+"_"+(++suffix);
                name=candidate;
            }
            String parent=string(bone,"parent","none");
            // A legacy custom bone may intentionally reuse a vanilla id. The
            // upstream resolver still binds parent/script lookups to the
            // vanilla bone while the custom geometry is rendered separately.
            if(duplicateAuthored) diagnostics.add(new CompatibilityReport.Diagnostic(
                CompatibilityReport.Severity.WARNING,"bones["+bones.size()+"]","Duplicate legacy bone id retained; subsequent references bind to this occurrence: "+name));
            if(!names.contains(parent)) throw error("CustomModel parent must precede child: "+parent);
            int parentIndex=LEGACY_HOST.contains(parent) ? -1 : customIndices.getOrDefault(parent,-1);
            names.add(name);
            for(String key:List.of("position","rotation","scale","color")) if(bone.fields().containsKey(key)) dynamicVector(bone.fields().get(key),3,key);
            if(bone.fields().containsKey("physics")) dynamicVector(bone.fields().get("physics"),5,"physics");
            for(String key:List.of("texture","alpha")) if(bone.fields().containsKey(key)) dynamic(bone.fields().get(key),false,key);
            for(String key:List.of("visible","emissive")) if(bone.fields().containsKey(key)) dynamic(bone.fields().get(key),true,key);
            if(bone.fields().containsKey("textureSize")) numbers(bone,"textureSize",2,0);
            stringList(bone,"attached");objects(bone,"particles");objects(bone,"items");
            var boxes=new ArrayList<Box>();for(var value:array(bone,"boxes")) {
                var box=object(value,"box");boxes.add(new Box(numbers(box,"coordinates",6,0),numbers(box,"textureOffset",2,0),
                    number(box,"sizeAdd",0),legacyBool(box,"mirror",false),box));
            }
            var quads=new ArrayList<Quad>();for(var value:array(bone,"quads")) {
                var quad=object(value,"quad");quads.add(new Quad(numbers(quad,"coordinates",5,0),numbers(quad,"textureOffset",2,0),number(quad,"sizeAdd",0),quad));
            }
            customIndices.put(name,bones.size());
            // Also resolve later references written with the authored id.
            if (!LEGACY_HOST.contains(authoredName)) customIndices.put(authoredName,bones.size());
            bones.add(new LegacyBone(name,parent,parentIndex,bone,boxes,quads));
        }
        for(String key:List.of("fpLeft","fpRight","fpList")) {
            for(String reference:stringList(root,key)) if(!names.contains(reference))
                diagnostics.add(new CompatibilityReport.Diagnostic(CompatibilityReport.Severity.WARNING,
                        key,"Missing first-person bone ignored by upstream resolver: "+reference));
        }
        return new Legacy(metadata,root,bones,files,new CompatibilityReport(coverage,diagnostics));
    }
    private Remake remake(ObjectValue root,Map<String,ByteData> files,CpmJson json,List<CompatibilityReport.Feature> coverage,
                          List<CompatibilityReport.Diagnostic> diagnostics) throws IOException {
        var authors=root.fields().containsKey("author")?strings(root.fields().get("author"),"author"):List.<String>of();
        var metadata=new Metadata("",required(root,"name"),string(root,"version",""),authors,string(root,"description",""),string(root,"url",""));
        boolean accessory=bool(root,"accessory",false);var sourceSkeleton=objectOrEmpty(root,"skeleton");
        String type=string(sourceSkeleton,"type","");
        if(!accessory&&!Set.of("biped","zombie","slime","quadruped").contains(type)) throw error("Unknown/missing CPM skeleton: "+type);
        var parameters=new LinkedHashMap<String,Double>();for(var entry:objectOrEmpty(sourceSkeleton,"param").fields().entrySet()) parameters.put(entry.getKey(),number(entry.getValue(),entry.getKey()));
        var attachments=new LinkedHashMap<String,List<String>>();for(var entry:objectOrEmpty(sourceSkeleton,"attachments").fields().entrySet()) {
            attachment(entry.getKey());attachments.put(entry.getKey(),strings(entry.getValue(),entry.getKey()));
        }
        var skeleton=new Skeleton(type,parameters,attachments,optionalStrings(sourceSkeleton,"left_arm"),optionalStrings(sourceSkeleton,"right_arm"),string(sourceSkeleton,"eye_position",""));
        dimensions(root,"size",2);dimensions(root,"eye_height",2);
        if(number(root,"default_scale",1)<=0) throw error("default_scale must be positive");
        number(root,"shadow_size",-1);
        var textures=new LinkedHashMap<String,String>();for(String path:files.keySet()) {
            String suffix=path.substring(path.lastIndexOf('.')+1);
            if(Set.of("png","jpg","bmp").contains(suffix)) {
                String stem=path.substring(0,path.lastIndexOf('.'));
                if(textures.putIfAbsent(stem,path)!=null) throw error("Ambiguous CPM texture stem: "+stem);
                int slash=stem.lastIndexOf('/');String leaf=slash<0?stem:stem.substring(slash+1);
                int symbolDot=leaf.lastIndexOf('.');if(symbolDot>0) textures.putIfAbsent(leaf.substring(symbolDot+1),path);
            }
        }
        var parts=new ArrayList<Part>();var partNames=new HashSet<String>();var boneNames=new HashSet<String>();
        for(var value:array(root,"parts")) {
            var part=object(value,"part");String name=required(part,"name");
            if(!partNames.add(name)) throw error("Duplicate CPM part: "+name);
            String path=name+".json";var bytes=files.get(path);if(bytes==null) throw error("Missing CPM part file: "+path);
            var geometry=new CpmGeometryReader().read(json.read(bytes,path));
            for(var model:geometry.models()) for(var bone:model.bones()) {
                if(!boneNames.add(bone.name())) throw error("Duplicate CPM bone across parts: "+bone.name());
                for(var cube:bone.cubes()) if(cube.uv() instanceof ObjectValue) {
                    if(coverage.stream().noneMatch(f->f.id().equals("cpm.per-face-uv"))) coverage.add(new CompatibilityReport.Feature(
                        "cpm.per-face-uv",CompatibilityReport.Level.READ,true,"Per-face UV retained; original CPM box reader only consumes array UV"));
                }
            }
            String texture=string(part,"texture","");String texturePath=textures.getOrDefault(texture,"");
            if(!texture.isEmpty()&&texturePath.isEmpty()) {
                if(!texture.matches("(?:[a-z0-9_.-]+:)?[a-z0-9/._-]+")||texture.contains("..")) throw error("Invalid host texture symbol: "+texture);
                diagnostics.add(new CompatibilityReport.Diagnostic(CompatibilityReport.Severity.WARNING,path,
                    "Texture requires host resource resolution: "+texture));
            }
            parts.add(new Part(name,path,texture,texturePath,bool(part,"double_face",false),geometry,part));
        }
        if(parts.isEmpty()) throw error("CPM model requires at least one part");
        var names=new HashSet<>(boneNames);names.addAll(hostBones(type));
        var parents=new LinkedHashMap<String,String>();
        for(var part:parts) for(var model:part.geometry().models()) for(var bone:model.bones()) {
            if(!bone.parent().isEmpty()) references(List.of(bone.parent()),names,"geometry parent");
            parents.put(bone.name(),bone.parent());
        }
        var complete=new HashSet<String>();
        for(String bone:parents.keySet()) {
            var chain=new HashSet<String>();String current=bone;
            while(current!=null&&!current.isEmpty()&&!complete.contains(current)) {
                if(!chain.add(current)) throw error("Cyclic CPM part hierarchy: "+current);
                current=parents.get(current);
            }
            complete.addAll(chain);
        }
        for(var list:attachments.values()) references(list,names,"attachments");
        if(skeleton.leftArm().isPresent()) references(skeleton.leftArm().get(),names,"left_arm");
        if(skeleton.rightArm().isPresent()) references(skeleton.rightArm().get(),names,"right_arm");
        if(!skeleton.eyePosition().isEmpty()) references(List.of(skeleton.eyePosition()),names,"eye_position");
        var addons=new LinkedHashMap<String,String>();for(var entry:objectOrEmpty(root,"addons").fields().entrySet()) {
            if(!boneNames.contains(entry.getKey())) throw error("Missing accessory addon bone: "+entry.getKey());
            String slot=string(entry.getValue(),"addons");attachment(slot);addons.put(entry.getKey(),slot);
        }
        for(String key:List.of("particles","items")) {
            var componentNames=new HashSet<String>();int index=0;
            for(var named:components(root,key)) {
                var component=named.value();String name=named.name().isEmpty()?string(component,"name",key+"_"+index++):named.name();
                if(!componentNames.add(name)) throw error("Duplicate CPM "+key+" name: "+name);
                String parent=string(component,"parent","none");references(List.of(parent),names,key+" parent");
            }
        }
        Optional<String> script=Optional.empty();if(files.containsKey("animation.js")) {
            String text=json.text(files.get("animation.js"));
            if(text.getBytes(StandardCharsets.UTF_8).length>json.maxStringBytes()) throw error("CPM animation.js string budget exceeded");
            script=Optional.of(text);
            coverage.add(new CompatibilityReport.Feature("cpm.javascript",CompatibilityReport.Level.READ,true,"JavaScript retained without compilation or execution"));
        }
        return new Remake(metadata,root,accessory,skeleton,parts,addons,script,files,new CompatibilityReport(coverage,diagnostics));
    }
    private static Set<String> hostBones(String type) {
        if(type.equals("quadruped")) return Set.of("none","head","body","left_front_leg","right_front_leg","left_back_leg","right_back_leg");
        if(type.equals("slime")) return Set.of("none","body");return BIPED;
    }
    private static void attachment(String name) throws AssetFormatException { if(!ATTACHMENTS.contains(name)) throw error("Unknown CPM attachment: "+name); }
    private static void references(List<String> list,Set<String> names,String at) throws AssetFormatException {
        for(String name:list) if(!names.contains(name)) throw error(at+": missing bone "+name);
    }
    private static ObjectValue objectOrEmpty(ObjectValue value,String key) throws AssetFormatException {
        var item=value.fields().get(key);return item==null?new ObjectValue(Map.of()):object(item,key);
    }
    private static List<String> stringList(ObjectValue value,String key) throws AssetFormatException {
        var item=value.fields().get(key);return item==null?List.of():strings(item,key);
    }
    private static Optional<List<String>> optionalStrings(ObjectValue value,String key) throws AssetFormatException {
        return value.fields().containsKey(key)?Optional.of(stringList(value,key)):Optional.empty();
    }
    private static List<ObjectValue> objects(ObjectValue value,String key) throws AssetFormatException {
        var result=new ArrayList<ObjectValue>();for(var item:array(value,key)) result.add(object(item,key));return result;
    }
    private record NamedObject(String name,ObjectValue value) {}
    private static List<NamedObject> components(ObjectValue value,String key) throws AssetFormatException {
        var raw=value.fields().get(key);if(raw==null)return List.of();var result=new ArrayList<NamedObject>();
        if(raw instanceof ArrayValue array) for(var item:array.values()) result.add(new NamedObject("",object(item,key)));
        else if(raw instanceof ObjectValue object) for(var entry:object.fields().entrySet()) result.add(new NamedObject(entry.getKey(),object(entry.getValue(),key+"."+entry.getKey())));
        else throw error(key+" must be an array or object");
        return List.copyOf(result);
    }
    private static void dimensions(ObjectValue value,String key,int length) throws AssetFormatException {
        for(var entry:objectOrEmpty(value,key).fields().entrySet()) {
            if(length==1) number(entry.getValue(),key+"."+entry.getKey());
            else {
                var numbers=array(entry.getValue(),key);if(numbers.size()!=length) throw error(key+" requires "+length+" components");
                for(var component:numbers) number(component,key);
            }
        }
    }
    private static void dynamicVector(CpmValue value,int count,String at) throws AssetFormatException {
        var array=array(value,at);if(array.size()!=count) throw error(at+" requires "+count+" expressions");
        for(var component:array) dynamic(component,false,at);
    }
    private static void dynamic(CpmValue value,boolean bool,String at) throws AssetFormatException {
        if(value instanceof StringValue||(!bool&&value instanceof NumberValue)||(bool&&value instanceof BooleanValue)) return;
        throw error(at+" must be a "+(bool?"boolean":"number")+" or expression string");
    }

    /** Gson's JsonPrimitive#getAsString coercion used by the legacy loader. */
    private static String scalarString(CpmValue value,String at,String fallback) throws AssetFormatException {
        if(value==null) return fallback;
        if(value instanceof StringValue s) return s.value();
        if(value instanceof NumberValue n) return n.value();
        if(value instanceof BooleanValue b) return Boolean.toString(b.value());
        throw error(at+" must be a scalar");
    }

    /** Legacy packs commonly encode mirror as a quoted JSON boolean. */
    private static boolean legacyBool(ObjectValue object,String key,boolean fallback) throws AssetFormatException {
        var value=object.fields().get(key);if(value==null) return fallback;
        if(value instanceof BooleanValue b) return b.value();
        if(value instanceof NumberValue n) return number(value,key)!=0;
        if(value instanceof StringValue s) {
            if(s.value().equalsIgnoreCase("true")) return true;
            if(s.value().equalsIgnoreCase("false")) return false;
        }
        throw error(key+" must be boolean");
    }
}
