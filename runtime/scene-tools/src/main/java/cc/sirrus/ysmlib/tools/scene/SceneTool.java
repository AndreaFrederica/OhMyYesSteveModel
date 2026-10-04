package cc.sirrus.ysmlib.tools.scene;

import cc.sirrus.ysmlib.SceneAuthoring;
import cc.sirrus.ysmlib.scene.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;

/** Scriptable, JSON-output sidecar editor. Diagnostics go to stderr; failures return nonzero. */
public final class SceneTool {
    public static void main(String[] args){int result=run(args,System.out,System.err);if(result!=0)System.exit(result);}
    public static int run(String[] args,PrintStream out,PrintStream err){
        try {
            if(args.length==0||args[0].equals("help")||args[0].equals("--help")){out.println(HELP);return 0;}
            String cmd=args[0];if(cmd.equals("gui")){SceneEditor.open(args.length>1?modelPath(args[1]):null);return 0;}
            if(args.length<2)throw new IllegalArgumentException("A model path is required. Run help.");
            var options=new LinkedHashMap<String,String>();for(int i=2;i<args.length;i+=2){if(i+1>=args.length||!args[i].startsWith("--"))throw new IllegalArgumentException("Expected --option value");if(options.put(args[i].substring(2),args[i+1])!=null)throw new IllegalArgumentException("Duplicate option: "+args[i]);}
            Set<String> allowed=switch(cmd){
                case "inspect","validate","automap","init","pack"->Set.of("out","config");
                case "set"->Set.of("out","config","field","value");case "apply"->Set.of("out","config","file");
                case "bind"->Set.of("out","config","role","bone","pitch","yaw","roll","weight");
                case "calibrate-arms"->Set.of("out","config","rig");
                case "source-bind"->Set.of("out","config","role","source");
                case "action"->Set.of("out","config","name","path","clip","loop");
                case "transition"->Set.of("out","config","from","to","path","clip");
                case "render"->Set.of("out","config","clip","seconds","action","rig");default->throw new IllegalArgumentException("Unknown command: "+cmd);};
            for(String key:options.keySet())if(!allowed.contains(key))throw new IllegalArgumentException("Unknown option: --"+key);
            var session=SceneAuthoring.open(modelPath(args[1]),err::println);
            if(options.containsKey("config"))session.profile(readProfile(Path.of(options.get("config"))));
            var p=session.profile();boolean save=false;
            switch(cmd){
                case "inspect","validate"->{}
                case "init","automap"->{session.profile(session.suggest());save=true;}
                case "set"->{session.profile(ProfileEdits.set(p,required(options,"field"),required(options,"value")));save=true;}
                case "apply"->{session.profile(readProfile(Path.of(required(options,"file"))));save=true;}
                case "bind"->{session.profile(session.bind(required(options,"role"),Integer.parseInt(required(options,"bone")),number(options,"pitch",0),number(options,"yaw",0),number(options,"roll",0),number(options,"weight",1)));save=true;}
                case "calibrate-arms"->{session.profile(cc.sirrus.ysmlib.YsmSkeletonBinding.calibrateArms(readRig(Path.of(required(options,"rig"))),session.assets(),p));save=true;}
                case "source-bind"->{var map=new LinkedHashMap<>(p.retarget().sourceBones());String source=required(options,"source");if(source.isEmpty())map.remove(required(options,"role"));else map.put(required(options,"role"),source);session.profile(p.withRetarget(p.retarget().withBindings(map,p.retarget().translationScale())));save=true;}
                case "action"->{var map=new LinkedHashMap<>(p.actions());String name=required(options,"name"),path=required(options,"path");
                    if(path.equals("AUTO"))map.remove(name);else map.put(name,new SceneModelProfile.Action(path,Integer.parseInt(options.getOrDefault("clip",path.equals("REST")||path.startsWith("@ysm/generated/")?"-1":"0")),bool(options.getOrDefault("loop","true"))));session.profile(p.withActions(map));save=true;}
                case "transition"->{var edges=new ArrayList<>(p.transitions());String from=required(options,"from"),to=required(options,"to"),path=required(options,"path");
                    edges.removeIf(e->e.from().equals(from)&&e.to().equals(to));
                    if(!path.equals("AUTO"))edges.add(new SceneModelProfile.Transition(from,to,new SceneModelProfile.Action(path,Integer.parseInt(options.getOrDefault("clip","0")),false)));
                    session.profile(p.withTransitions(edges));save=true;}
                case "pack"->session.pack(Path.of(required(options,"out")));
                case "render"->{if(options.containsKey("action")&&options.containsKey("clip"))throw new IllegalArgumentException("Choose --action or --clip, not both");int clip=Integer.parseInt(options.getOrDefault("clip","-1"));var selection=options.containsKey("action")?session.action(options.get("action")):clip<0?ScenePackagePlayback.Selection.REST:session.animations().get(clip).selection();
                    try(var player=session.playback(selection)){
                        if(options.containsKey("rig")) {
                            if(!selection.equals(ScenePackagePlayback.Selection.REST))throw new IllegalArgumentException("--rig reference preview cannot override a source clip");
                            var binding=new cc.sirrus.ysmlib.YsmSkeletonBinding(readRig(Path.of(options.get("rig"))),session.assets(),session.profile());
                            player.bonePoses(binding.referencePose());player.ikOverrides(binding.ikOverrides());
                        }
                        var frame=player.seek(number(options,"seconds",0));var rendered=ScenePreview.render(frame,session.profile(),session.bones(),640,640,0,-8,1,-1);var data=new ByteArrayOutputStream();ImageIO.write(rendered.image(),"PNG",data);SceneAuthoring.atomicWrite(Path.of(required(options,"out")),data.toByteArray(),null,false);}}
            }
            if(save)session.save(Path.of(options.getOrDefault("out",SceneAuthoring.sidecar(session.source()).toString())));
            var report=new LinkedHashMap<String,Object>();report.put("ok",true);report.put("command",cmd);report.put("source",session.source().toString());report.put("sourceModified",false);report.put("physics",false);
            report.put("config",save?options.getOrDefault("out",SceneAuthoring.sidecar(session.source()).toString()):SceneAuthoring.sidecar(session.source()).toString());
            report.put("profile",session.profile());
            if(cmd.equals("inspect")||cmd.equals("automap")){report.put("bones",session.bones());report.put("animations",session.animations());report.put("unmappedRoles",SceneModelProfile.ROLES.stream().filter(r->!session.profile().bones().containsKey(r)).toList());}
            String json=ProfileEdits.JSON.toJson(report);if((cmd.equals("inspect")||cmd.equals("validate"))&&options.containsKey("out"))SceneAuthoring.atomicWrite(Path.of(options.get("out")),json.getBytes(java.nio.charset.StandardCharsets.UTF_8),null,false);
            out.println(json);return 0;
        }catch(Exception e){err.println("ERROR: "+e);e.printStackTrace(err);out.println(ProfileEdits.JSON.toJson(Map.of("ok",false,"error",e.toString())));return 2;}
    }
    static List<cc.sirrus.ysmlib.YsmSkeletonBinding.Bone> readRig(Path path)throws IOException {
        if(Files.size(path)>1024*1024)throw new IOException("Source rig exceeds 1 MiB");
        var bones=ProfileEdits.JSON.fromJson(Files.readString(path),cc.sirrus.ysmlib.YsmSkeletonBinding.Bone[].class);
        if(bones==null||bones.length==0)throw new IllegalArgumentException("Empty source rig");return List.of(bones);
    }
    private static SceneModelProfile readProfile(Path path)throws IOException {
        var bytes=SceneAuthoring.readProfileBytes(path);if(bytes==null)throw new NoSuchFileException(path.toString());
        return cc.sirrus.ysmlib.YsmRuntime.scenes().readModelProfile(new ByteData(bytes));
    }
    private static String required(Map<String,String> options,String key){String v=options.get(key);if(v==null)throw new IllegalArgumentException("Missing --"+key);return v;}
    private static Path modelPath(String value)throws IOException{return Path.of(value.startsWith("@")?Files.readString(Path.of(value.substring(1))).replace("\uFEFF","").strip():value);}
    private static double number(Map<String,String> o,String k,double d){return Double.parseDouble(o.getOrDefault(k,Double.toString(d)));}
    private static boolean bool(String v){if(!v.equals("true")&&!v.equals("false"))throw new IllegalArgumentException("Expected true/false");return Boolean.parseBoolean(v);}
    private static final String HELP="""
        Oh My YSM Lib - non-destructive model sidecar editor (Java 17, no Minecraft)
        inspect MODEL [--out report.json]             Bones, motions, mappings, metadata
        init MODEL [--out profile.json]               Create MODEL.omysm.json with suggestions
        automap MODEL [--out profile.json]            Fill missing mappings; preserve explicit bindings
        set MODEL --field placement.height --value 1.8
        set MODEL --field placement.sizeMode --value HEIGHT
        set MODEL --field metadata.name --value "My model"
        set MODEL --field metadata.authors --value '[{"name":"...","role":"...","contacts":[],"comment":"","avatar":""}]'
        bind MODEL --role head --bone 12 [--pitch 0 --yaw 0 --roll 0 --weight 1]
        bind MODEL --role head --bone -1              Explicitly unbind
        calibrate-arms MODEL --rig source-rig.json     Initial reference pose from evaluated YSM rest rig
        set MODEL --field bones.leftUpperArm.restRoll --value -60
        set MODEL --field heldItems.left.x --value 0.02
        set MODEL --field heldItems.firstPerson --value MODEL
        render MODEL --out reference.png --rig source-rig.json   Mapped rest pose, no source clip
        source-bind MODEL --role head --source Head   Bind a YSM source bone to a target role
        bind MODEL --role bone:skirtLeft --bone 42    Custom target binding (not limited to humanoid roles)
        source-bind MODEL --role bone:skirtLeft --source SkirtLeft
        set MODEL --field retarget.sourceModel --value @ysm/default
        action MODEL --name walk --path dance.vmd --clip 0 --loop true
        action MODEL --name walk --path AUTO          Remove override (REST disables movement)
        transition MODEL --from idle --to sneaking --path crouch-down.vmd --clip 0
        transition MODEL --from idle --to sneaking --path AUTO   Remove transition
        apply MODEL --file edited-profile.json        Validate and save a full profile
        validate MODEL [--config profile.json]
        render MODEL --out preview.png [--clip 0 --seconds 0]
        render MODEL --out walk.png --action walk --seconds 0.5
        pack MODEL --out model.yscene                 Optional lossless packaging, not FBX-to-PMX
        gui [MODEL]                                  Desktop sidecar editor with geometry preview

        Editing commands default to MODEL.omysm.json. --out writes a NEW file; never overwrites
        unrelated files. --config reads an alternate profile. Source models/textures/motions
        are never rewritten. Supported source models: PMX/PMD/GLB/glTF/VRM/FBX/yscene.
        Preview uses neutral geometry shading (not game material fidelity), physics always OFF.
        Blender/Unity projects require an external export first. All diagnostics go to stderr.
        On Windows Java 17 with legacy console encodings, MODEL may be @path.txt (UTF-8 full path).
        """;
}
