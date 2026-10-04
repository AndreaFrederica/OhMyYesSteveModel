package com.elfmcys.ysm.client.gui;

import cc.sirrus.ysmlib.YsmRuntime;
import cc.sirrus.ysmlib.scene.*;
import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.client.animation.GeneralModelProfileStore;
import com.elfmcys.ysm.client.event.RegisterEntityRenderersEvent;
import com.elfmcys.ysm.model.ModelRuntime;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.RenderTargetIds;
import com.elfmcys.ysm.model.resource.client.ModelRenderTarget;
import com.elfmcys.ysm.model.service.ClientModelService;
import com.elfmcys.ysm.util.RenderUtil;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.*;
import net.minecraft.network.chat.Component;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** One draft and one preview per editor. Physics is disabled before the preview acquires its first player. */
public final class GeneralModelEditorScreen extends Screen {
    private final Screen parent;
    private final Hash256 hash;
    private final ModelRenderTarget model;
    private final List<SceneSkeleton.Bone> bones;
    private final List<SceneAnimation> clips;
    private final CustomGuiPlayerEntity preview=new CustomGuiPlayerEntity();
    private final Deque<SceneModelProfile> undo=new ArrayDeque<>(),redo=new ArrayDeque<>();
    private final Map<String,EditBox> fields=new LinkedHashMap<>();
    private final Map<String,String> captions=new LinkedHashMap<>();
    private SceneModelProfile draft,saved;
    private Path source;
    private String sourceCatalogPath;
    private byte[] diskVersion;
    private boolean writable,physics,autoFit=true,loop=true,busy,applyPending,pendingFields,previewWasPreparing;
    private long applyDeadline;
    private Hash256 applyHash;
    private final List<String> actionNames;
    private String tab="placement",role="head",filter="",alias="idle",status="",lastError="";
    private String transitionFrom="idle",transitionTo="sneaking";
    private boolean leftSocket;
    private int page,selectedBone=-1,selectedClip=-1,fieldPage;
    private int panel,viewLeft=90,viewRight,viewTop=54,viewBottom,rows;
    private float yaw=165,pitch=-5,zoom=1,panX,panY;

    public GeneralModelEditorScreen(Screen parent,Hash256 hash,ModelRenderTarget model) {
        super(t("title"));this.parent=parent;this.hash=hash;this.model=model;
        draft=GeneralModelProfileStore.migrateDraft(model);saved=draft;
        bones=SceneSkeleton.of(model.generalMeshResources().assets());clips=model.generalMeshResources().animations();
        var names=new TreeSet<String>(List.of("idle","walk","run","sneak","swim","fly","death","jump","attacked"));
        var defaults=ClientModelService.instance().defaultRenderTarget().playerResources();if(defaults!=null)names.addAll(defaults.animations().keySet());
        names.addAll(draft.actions().keySet());actionNames=List.copyOf(names);
        try {
            var editable=GeneralModelProfileStore.source(hash);
            source=editable.path();sourceCatalogPath=editable.catalogPath();diskVersion=GeneralModelProfileStore.read(source);
            if(diskVersion!=null) draft=YsmRuntime.scenes().readModelProfile(new ByteData(diskVersion));
            SceneSkeleton.validate(bones,draft.bones());saved=draft;writable=true;
        } catch(Exception failure) { draft=saved;error("Cannot open model profile",failure); }
        preview.configureEditorPreview(draft,false);
    }
    private static Component t(String key,Object... args) { return Component.translatable("gui.yes_steve_model.editor."+key,args); }
    private Button button(int x,int y,int w,Component text,Runnable action) {
        return addRenderableWidget(Button.builder(text,b->{try {action.run();} catch(Exception e) {error("Editor operation failed",e);}}).bounds(x,y,w,20).build());
    }
    private EditBox field(String key,String caption,String value,int y) {
        var box=new EditBox(font,panel+94,y,Math.max(55,width-panel-104),18,Component.literal(caption));
        box.setMaxLength(256);box.setValue(value);fields.put(key,box);captions.put(key,caption);addRenderableWidget(box);return box;
    }
    private void numeric(String key,double value,int y) { field(key,t(key).getString(),Double.toString(value),y).setResponder(v->pendingFields=true); }
    @Override protected void init() {
        var uncommitted=new LinkedHashMap<String,String>();
        if(pendingFields)fields.forEach((key,box)->uncommitted.put(key,box.getValue()));
        clearWidgets();fields.clear();captions.clear();pendingFields=false;
        panel=Math.max(200,width-240);viewRight=panel-8;viewBottom=height-100;rows=Math.max(1,(height-235)/22);
        preview.updateModelAndTexture(hash,RenderTargetIds.GENERAL_MESH_VARIANT);
        int y=36;for(String name:List.of("placement","bones","actions","transitions","materials","held_items","diagnostics")) {
            String key=name;button(8,y,76,t(name),()->{commitFields();tab=key;page=fieldPage=0;init();});y+=24;
        }
        button(8,height-80,76,t("undo"),this::undo);
        button(8,height-56,76,t("redo"),this::redo);
        button(8,height-30,76,t("back"),this::onClose);
        button(viewLeft,30,Math.max(90,(viewRight-viewLeft)/2),t(autoFit?"fit":"ruler"),()->{autoFit=!autoFit;zoom=1;panX=panY=0;init();});
        button(viewLeft+Math.max(90,(viewRight-viewLeft)/2)+4,30,Math.max(75,(viewRight-viewLeft)/2-4),t("reset_camera"),()->{zoom=1;panX=panY=0;yaw=165;pitch=-5;});
        int bw=Math.max(36,(viewRight-viewLeft-12)/4);
        button(viewLeft,height-92,bw,t("play"),this::play);
        button(viewLeft+bw+4,height-92,bw,t("pause"),()->{var s=preview.getGeneralMeshInstance();if(s!=null)s.timeline().pause();});
        button(viewLeft+(bw+4)*2,height-92,bw,Component.literal("−1f"),()->{var s=preview.getGeneralMeshInstance();if(s!=null)s.timeline().step(-1);});
        button(viewLeft+(bw+4)*3,height-92,bw,Component.literal("+1f"),()->{var s=preview.getGeneralMeshInstance();if(s!=null)s.timeline().step(1);});
        button(viewLeft,height-66,viewRight-viewLeft,t(physics?"physics_on":"physics_off"),this::togglePhysics);
        button(panel,height-30,108,t("save"),()->save(false)).active=writable&&!busy;
        button(panel+112,height-30,Math.max(100,width-panel-120),t("save_apply"),()->save(true)).active=writable&&!busy;
        if(tab.equals("placement")) placement();
        else if(tab.equals("bones")) bonePage();
        else if(tab.equals("actions")) actionPage();
        else if(tab.equals("transitions")) transitionPage();
        else if(tab.equals("materials")) materialPage();
        else if(tab.equals("bone_correction")) correctionPage();
        else if(tab.equals("held_items")) socketPage();
        uncommitted.forEach((key,value)->{var box=fields.get(key);if(box!=null)box.setValue(value);});
    }
    private void placement() {
        var p=draft.placement();
        button(panel,36,width-panel-8,t(p.sizeMode()==SceneModelProfile.SizeMode.SCALE?"scale_mode":"height_mode"),()->{
            commitFields();var current=draft.placement();
            change(draft.withPlacement(new SceneModelProfile.Placement(current.metersPerUnit(),current.sizeMode()==SceneModelProfile.SizeMode.SCALE?SceneModelProfile.SizeMode.HEIGHT:SceneModelProfile.SizeMode.SCALE,
                current.scale(),current.height(),current.referenceHeight(),current.footY(),current.x(),current.y(),current.z(),current.yaw())));init();});
        String[] keys={"units","scale","height","reference_height","foot","offset_x","offset_y","offset_z","yaw"};
        double[] values={p.metersPerUnit(),p.scale(),p.height(),p.referenceHeight(),p.footY(),p.x(),p.y(),p.z(),p.yaw()};
        int count=Math.max(1,(height-160)/24),start=fieldPage*count;
        for(int i=start;i<Math.min(keys.length,start+count);i++) numeric(keys[i],values[i],64+(i-start)*24);
        int by=height-88;
        button(panel,by,48,Component.literal("<"),()->{commitPlacement();fieldPage=Math.max(0,fieldPage-1);init();});
        button(panel+52,by,48,Component.literal(">"),()->{commitPlacement();fieldPage=Math.min((keys.length-1)/count,fieldPage+1);init();});
        button(panel+104,by,width-panel-112,t("update"),this::commitPlacement);
        button(panel,height-62,width-panel-8,t("reset_placement"),()->{change(draft.withPlacement(model.generalMeshResources().profile().placement()));pendingFields=false;init();});
    }
    private double value(String key,double old) { return fields.containsKey(key)?Double.parseDouble(fields.get(key).getValue().trim()):old; }
    private void commitPlacement() {
        var p=draft.placement();change(draft.withPlacement(new SceneModelProfile.Placement(value("units",p.metersPerUnit()),p.sizeMode(),value("scale",p.scale()),
            value("height",p.height()),value("reference_height",p.referenceHeight()),value("foot",p.footY()),value("offset_x",p.x()),value("offset_y",p.y()),value("offset_z",p.z()),value("yaw",p.yaw()))));
        pendingFields=false;
    }
    private void commitFields() {
        if(!pendingFields)return;
        if(tab.equals("placement"))commitPlacement();
        else if(tab.equals("materials"))change(draft.withPresentation(new SceneModelProfile.Presentation(draft.presentation().outlines(),value("outline_scale",1))));
        else if(tab.equals("held_items"))commitSocket();
        else if(tab.equals("bone_correction")) {
            commitBoneBinding();
        }
        pendingFields=false;
    }
    private void bonePage() {
        var asset=model.generalMeshResources().assets().model();
        boolean canTest=asset instanceof ScenePackageAssets.Pmx || asset instanceof ScenePackageAssets.Pmd;
        button(panel,36,28,Component.literal("<"),()->cycleRole(-1));
        var key=new EditBox(font,panel+32,36,width-panel-100,18,t("binding_key"));key.setMaxLength(128);key.setValue(role);addRenderableWidget(key);
        button(width-66,36,28,Component.literal("✓"),()->{SceneModelProfile.checkBindingKey(key.getValue());role=key.getValue();selectedBone=-1;page=0;init();});
        button(width-36,36,28,Component.literal(">"),()->cycleRole(1));
        var search=field("search",t("search").getString(),filter,62);
        search.setResponder(v->filter=v);
        button(panel,86,76,t("search"),()->{page=0;init();});
        button(panel+80,86,68,t("suggest"),()->{var next=new LinkedHashMap<>(SceneSkeleton.suggest(bones));next.putAll(draft.bones());change(draft.withBones(next));init();});
        button(panel+152,86,Math.max(65,width-panel-160),t("unbind"),()->{
            var next=new LinkedHashMap<>(draft.bones());next.put(role,new SceneModelProfile.BoneBinding(-1,"","",0,0,0,1));change(draft.withBones(next));init();});
        var selected=draft.bones().get(role);boolean ik=role.endsWith("Ik");
        var matches=bones.stream().filter(b->(SceneModelProfile.customBinding(role)||b.ik()==ik) && (b.name()+" "+b.alternate()+" "+b.index()).toLowerCase(Locale.ROOT).contains(filter.toLowerCase(Locale.ROOT))).toList();
        int count=Math.max(1,(height-276)/22);page=Math.min(page,Math.max(0,(matches.size()-1)/count));
        for(int i=0;i<count && page*count+i<matches.size();i++) {
            var b=matches.get(page*count+i);String label=(selected!=null&&selected.index()==b.index()?"> ":"")+b.index()+" "+b.name();
            var btn=button(panel,112+i*22,width-panel-8,Component.literal(label),()->{
                selectedBone=b.index();var next=new LinkedHashMap<>(draft.bones());next.put(role,SceneSkeleton.bind(bones,b.index()));change(draft.withBones(next));init();
            });btn.setTooltip(Tooltip.create(Component.literal(b.name()+" / "+b.alternate()+" | parent="+(b.parent()<0?"ROOT":bones.get(b.parent()).name())+(b.ik()?" | IK":" | FK"))));
        }
        int by=height-154;
        button(panel,by,48,Component.literal("<"),()->{page=Math.max(0,page-1);init();});
        button(panel+52,by,48,Component.literal(">"),()->{page=Math.min((matches.size()-1)/count,page+1);init();});
        button(panel+104,by,width-panel-112,t("correction"),()->{tab="bone_correction";init();});
        var test=button(panel,height-128,width-panel-8,t("test_bone"),()->testBone(25));
        var rest=button(panel,height-104,width-panel-8,t("rest"),()->testBone(0));
        var head=button(panel,height-80,width-panel-8,t("test_head"),()->{
            var binding=draft.bones().get("head");if(binding==null || binding.index()<0) throw new IllegalStateException(t("head_missing").getString());
            selectedBone=binding.index();testBone(25);
        });
        for(var control:List.of(test,rest,head)) {control.active=canTest;if(!canTest)control.setTooltip(Tooltip.create(t("mmd_test_only")));}
        button(panel,height-56,width-panel-8,t("bind_selected"),()->{
            if(selectedBone<0)throw new IllegalStateException(t("select_bone").getString());
            var next=new LinkedHashMap<>(draft.bones());next.put(role,SceneSkeleton.bind(bones,selectedBone));change(draft.withBones(next));init();
        });
    }
    private void cycleRole(int delta) { var roles=new ArrayList<>(SceneModelProfile.ROLES);draft.bones().keySet().stream().filter(SceneModelProfile::customBinding).sorted().forEach(roles::add);role=roles.get(Math.floorMod(roles.indexOf(role)+delta,roles.size()));var b=draft.bones().get(role);selectedBone=b==null?-1:b.index();page=0;filter="";init(); }
    private void correctionPage() {
        var b=draft.bones().get(role);if(b==null || b.index()<0) { status=t("select_bone").getString();return; }
        String[] keys={"pitch","yaw","roll","weight","rest_pitch","rest_yaw","rest_roll","source_bone","translation_scale","source_model"};
        double[] values={b.pitch(),b.yaw(),b.roll(),b.weight(),b.restPitch(),b.restYaw(),b.restRoll()};
        int count=Math.max(1,(height-190)/24),start=fieldPage*count;
        for(int i=start;i<Math.min(keys.length,start+count);i++) {
            int y=60+(i-start)*24;
            if(i<7)numeric(keys[i],values[i],y);
            else if(i==8)numeric(keys[i],draft.retarget().translationScale(),y);
            else field(keys[i],t(keys[i]).getString(),i==7?draft.retarget().sourceBones().getOrDefault(role,""):draft.retarget().sourceModel(),y).setResponder(v->pendingFields=true);
        }
        button(panel,height-112,48,Component.literal("<"),()->{commitFields();fieldPage=Math.max(0,fieldPage-1);init();});
        button(panel+52,height-112,48,Component.literal(">"),()->{commitFields();fieldPage=Math.min((keys.length-1)/count,fieldPage+1);init();});
        button(panel+104,height-112,width-panel-112,t("update"),()->{commitBoneBinding();pendingFields=false;});
        button(panel,height-86,width-panel-8,t("calibrate_arms"),()->{commitFields();change(preview.calibrateGeneralArms());preview.previewReferencePose(true);init();});
        button(panel,height-60,width-panel-8,t("reference_preview"),()->{commitFields();preview.previewReferencePose(true);});
    }
    private void socketPage() {
        var held=draft.heldItems();var socket=leftSocket?held.left():held.right();
        button(panel,36,100,t(leftSocket?"left_hand":"right_hand"),()->{commitFields();leftSocket=!leftSocket;fieldPage=0;init();});
        button(panel+104,36,width-panel-112,t(held.enabled()?"items_on":"items_off"),()->{commitFields();var h=draft.heldItems();change(draft.withHeldItems(new SceneModelProfile.HeldItems(!h.enabled(),h.firstPerson(),h.left(),h.right())));init();});
        button(panel,60,width-panel-8,t("first_items_"+held.firstPerson().name().toLowerCase(Locale.ROOT)),()->{
            commitFields();var h=draft.heldItems();var modes=SceneModelProfile.FirstPersonItems.values();change(draft.withHeldItems(new SceneModelProfile.HeldItems(h.enabled(),modes[(h.firstPerson().ordinal()+1)%modes.length],h.left(),h.right())));init();});
        String[] keys={"socket_binding","offset_x","offset_y","offset_z","pitch","yaw","roll","scale"};
        double[] values={0,socket.x(),socket.y(),socket.z(),socket.pitch(),socket.yaw(),socket.roll(),socket.scale()};
        int count=Math.max(1,(height-220)/24),start=fieldPage*count;
        for(int i=start;i<Math.min(keys.length,start+count);i++) {
            int y=88+(i-start)*24;
            if(i==0)field(keys[i],t(keys[i]).getString(),socket.binding(),y).setResponder(v->pendingFields=true);else numeric(keys[i],values[i],y);
        }
        button(panel,height-108,width-panel-8,t(socket.enabled()?"socket_on":"socket_off"),()->{
            commitFields();var h=draft.heldItems();var v=leftSocket?h.left():h.right();setSocket(new SceneModelProfile.HandSocket(!v.enabled(),v.binding(),v.x(),v.y(),v.z(),v.pitch(),v.yaw(),v.roll(),v.scale()));init();});
        button(panel,height-82,48,Component.literal("<"),()->{commitFields();fieldPage=Math.max(0,fieldPage-1);init();});
        button(panel+52,height-82,48,Component.literal(">"),()->{commitFields();fieldPage=Math.min((keys.length-1)/count,fieldPage+1);init();});
        button(panel+104,height-82,width-panel-112,t("update"),()->{commitSocket();pendingFields=false;});
        button(panel,height-56,width-panel-8,t("preview_items"),()->{
            preview.getEntity().setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND_SWORD));
            preview.getEntity().setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.SHIELD));
            preview.previewReferencePose(true);
        });
    }
    private void setSocket(SceneModelProfile.HandSocket value) {
        var held=draft.heldItems();change(draft.withHeldItems(new SceneModelProfile.HeldItems(held.enabled(),held.firstPerson(),leftSocket?value:held.left(),leftSocket?held.right():value)));
    }
    private void commitSocket() {
        var h=draft.heldItems();var v=leftSocket?h.left():h.right();
        setSocket(new SceneModelProfile.HandSocket(v.enabled(),fields.containsKey("socket_binding")?fields.get("socket_binding").getValue().trim():v.binding(),
            value("offset_x",v.x()),value("offset_y",v.y()),value("offset_z",v.z()),value("pitch",v.pitch()),value("yaw",v.yaw()),value("roll",v.roll()),value("scale",v.scale())));
    }
    private void commitBoneBinding() {
        var b=draft.bones().get(role);if(b==null)return;
        var next=new LinkedHashMap<>(draft.bones());
        next.put(role,b.withAxes(value("pitch",b.pitch()),value("yaw",b.yaw()),value("roll",b.roll()),value("weight",b.weight())).withReference(value("rest_pitch",b.restPitch()),value("rest_yaw",b.restYaw()),value("rest_roll",b.restRoll())));
        var sources=new LinkedHashMap<>(draft.retarget().sourceBones());
        if(fields.containsKey("source_bone")){String name=fields.get("source_bone").getValue().trim();if(name.isEmpty())sources.remove(role);else sources.put(role,name);}
        change(draft.withBones(next).withRetarget(new SceneModelProfile.Retarget(sources,value("translation_scale",draft.retarget().translationScale()),fields.containsKey("source_model")?fields.get("source_model").getValue().trim():draft.retarget().sourceModel())));
    }
    private void testBone(double degrees) {
        preview.previewReferencePose(false);
        var s=preview.getGeneralMeshInstance();if(s==null)return;
        var b=draft.bones().get(role);int index=selectedBone>=0?selectedBone:b==null?-1:b.index();
        if(index<0) throw new IllegalStateException(t("select_bone").getString());
        var correction=draft.bones().values().stream().filter(v->v.index()==index).findFirst().orElse(null);
        var q=Rotation.axisAngle(new Vec3(1,0,0),Math.toRadians(degrees)*(correction==null?1:correction.weight()));
        if(correction!=null){var basis=Rotation.axisAngle(new Vec3(0,0,1),Math.toRadians(correction.roll())).multiply(Rotation.axisAngle(new Vec3(0,1,0),Math.toRadians(correction.yaw()))).multiply(Rotation.axisAngle(new Vec3(1,0,0),Math.toRadians(correction.pitch())));q=basis.multiply(q).multiply(basis.inverse());}
        s.timeline().pause();s.boneRotations(degrees==0?Map.of():Map.of(index,q));s.debugBone=index;
    }
    private void actionPage() {
        var name=field("alias",t("alias").getString(),alias,36);name.setResponder(v->alias=v);
        button(panel,62,28,Component.literal("<"),()->cycleAction(-1));
        button(panel+32,62,28,Component.literal(">"),()->cycleAction(1));
        button(panel+64,62,48,t("rest"),()->{selectedClip=-1;setAction(new SceneModelProfile.Action("REST",-1,loop));});
        button(panel+116,62,48,t("auto"),()->{var next=new LinkedHashMap<>(draft.actions());next.remove(alias.trim());change(draft.withActions(next));});
        button(panel+168,62,Math.max(55,width-panel-176),t(loop?"loop":"once"),()->{loop=!loop;var action=draft.actions().get(alias.trim());if(action!=null)setAction(new SceneModelProfile.Action(action.path(),action.clip(),loop));init();});
        int count=Math.max(1,(height-210)/22);page=Math.min(page,Math.max(0,(clips.size()-1)/count));
        for(int i=0;i<count && page*count+i<clips.size();i++) {
            int index=page*count+i;var clip=clips.get(index);
            var b=button(panel,90+i*22,width-panel-8,Component.literal((selectedClip==index?"> ":"")+clip.name()),()->{selectedClip=index;setAction(new SceneModelProfile.Action(
                clip.selection().sourceId().startsWith("@ysm/generated/")?clip.selection().sourceId():clip.sourcePath(),clip.selection().clip(),loop));init();});
            b.setTooltip(Tooltip.create(Component.literal(clip.sourcePath()+" / clip="+clip.selection().clip())));
        }
        button(panel,height-108,60,Component.literal("<"),()->{page=Math.max(0,page-1);init();});
        button(panel+64,height-108,60,Component.literal(">"),()->{page=Math.min((clips.size()-1)/count,page+1);init();});
        field("seconds",t("seconds").getString(),"0",height-80);
        button(panel,height-56,width-panel-8,t("seek"),()->{var s=preview.getGeneralMeshInstance();if(s!=null)s.timeline().seek(value("seconds",0));});
    }
    private void transitionPage() {
        field("from",t("transition_from").getString(),transitionFrom,36).setResponder(v->transitionFrom=v);
        field("to",t("transition_to").getString(),transitionTo,60).setResponder(v->transitionTo=v);
        button(panel,84,(width-panel-12)/2,t("update"),()->{
            if(selectedClip<0)throw new IllegalStateException(t("transition_select").getString());
            var clip=clips.get(selectedClip);var list=new ArrayList<>(draft.transitions());
            list.removeIf(e->e.from().equals(transitionFrom.trim())&&e.to().equals(transitionTo.trim()));
            list.add(new SceneModelProfile.Transition(transitionFrom.trim(),transitionTo.trim(),new SceneModelProfile.Action(clip.sourcePath(),clip.selection().clip(),false)));
            var next=draft.withTransitions(list);next.validateAnimations(model.generalMeshResources().assets().source(),clips);change(next);status=t("draft_updated").getString();
        });
        button(panel+(width-panel-12)/2+4,84,(width-panel-12)/2,t("unbind"),()->{var list=new ArrayList<>(draft.transitions());list.removeIf(e->e.from().equals(transitionFrom.trim())&&e.to().equals(transitionTo.trim()));change(draft.withTransitions(list));});
        var eligible=java.util.stream.IntStream.range(0,clips.size()).filter(i->!clips.get(i).selection().sourceId().startsWith("@ysm/")&&clips.get(i).range().end()>clips.get(i).range().start()).boxed().toList();
        int count=Math.max(1,(height-190)/22);page=Math.min(page,Math.max(0,(eligible.size()-1)/count));
        for(int i=0;i<count&&page*count+i<eligible.size();i++) {int index=eligible.get(page*count+i);var clip=clips.get(index);
            button(panel,112+i*22,width-panel-8,Component.literal((selectedClip==index?"> ":"")+clip.sourcePath()),()->{selectedClip=index;preview.previewGeneralAnimation(clip.selection(),clip.range(),false);init();});
        }
        button(panel,height-56,60,Component.literal("<"),()->{page=Math.max(0,page-1);init();});
        button(panel+64,height-56,60,Component.literal(">"),()->{page++;init();});
    }
    private void cycleAction(int delta) {
        alias=actionNames.get(Math.floorMod(actionNames.indexOf(alias)+delta,actionNames.size()));
        var action=draft.actions().get(alias);selectedClip=-1;
        if(action!=null){loop=action.loop();var selection=action.resolve(model.generalMeshResources().assets().source());for(int i=0;i<clips.size();i++)if(clips.get(i).selection().equals(selection)){selectedClip=i;page=i/Math.max(1,(height-210)/22);}}
        init();
    }
    private void setAction(SceneModelProfile.Action action) { var next=new LinkedHashMap<>(draft.actions());next.put(alias.trim(),action);change(draft.withActions(next));status=t("draft_updated").getString(); }
    private void materialPage() {
        button(panel,36,width-panel-8,t(draft.presentation().outlines()?"outline_on":"outline_off"),()->{commitFields();change(draft.withPresentation(new SceneModelProfile.Presentation(!draft.presentation().outlines(),draft.presentation().outlineScale())));init();});
        numeric("outline_scale",draft.presentation().outlineScale(),66);
        button(panel,94,width-panel-8,t("update"),()->change(draft.withPresentation(new SceneModelProfile.Presentation(draft.presentation().outlines(),value("outline_scale",1)))));
    }
    private void change(SceneModelProfile next) {
        if(next.equals(draft))return;SceneSkeleton.validate(bones,next.bones());next.validateAnimations(model.generalMeshResources().assets().source(),clips);
        preview.configureEditorPreview(next,physics);undo.push(draft);if(undo.size()>64)undo.removeLast();redo.clear();draft=next;
    }
    private void undo() { commitFields();if(undo.isEmpty())return;var next=undo.peek();preview.configureEditorPreview(next,physics);redo.push(draft);draft=undo.pop();init(); }
    private void redo() { if(pendingFields)commitFields();if(redo.isEmpty())return;var next=redo.peek();preview.configureEditorPreview(next,physics);undo.push(draft);draft=redo.pop();init(); }
    private void play() {
        preview.previewReferencePose(false);
        var s=preview.getGeneralMeshInstance();if(s==null)return;
        var mapping=draft.actions().get(alias.trim());
        var automatic=com.elfmcys.ysm.client.animation.GeneralAnimationActions.automatic(model,alias.trim());
        var selection=mapping!=null?mapping.resolve(model.generalMeshResources().assets().source()):automatic!=null?automatic.selection():ScenePackagePlayback.Selection.REST;
        var clip=clips.stream().filter(c->c.selection().equals(selection)).findFirst().orElse(null);
        preview.previewGeneralAnimation(selection,clip==null?new AnimationPreview.Range(0,0,30):clip.range(),mapping==null?loop:mapping.loop());
    }
    private void togglePhysics() {
        var instance=preview.getGeneralMeshInstance();if(instance==null)return;
        if(!physics) {instance.timeline().pause();status=t("preparing_physics").getString();}
        preview.configureEditorPreview(draft,!physics);physics=!physics;
        if(!physics)status=t("physics_off").getString();
        init();
    }
    private void save(boolean apply) {
        if(!writable||busy)return;
        commitFields();
        if(tab.equals("placement"))commitPlacement();
        if(tab.equals("materials"))change(draft.withPresentation(new SceneModelProfile.Presentation(draft.presentation().outlines(),value("outline_scale",1))));
        var next=draft;var expected=diskVersion;busy=true;status=t("saving").getString();init();
        record Saved(byte[] bytes,Hash256 id) {}
        CompletableFuture.supplyAsync(()->{try{var bytes=GeneralModelProfileStore.save(source,next,expected);return new Saved(bytes,new com.elfmcys.ysm.model.catalog.GenericMeshModelImporter().capture(source).modelHash());}catch(Exception e){throw new java.util.concurrent.CompletionException(e);}})
            .whenComplete((result,failure)->minecraft.execute(()->{
                busy=false;if(failure!=null)error("Cannot save model profile",failure.getCause()==null?failure:failure.getCause());
                else {diskVersion=result.bytes();saved=next;applyHash=result.id();ModelRuntime.system().catalog().sourceEdited(GeneralModelProfileStore.sidecar(source));
                    status=t("saved").getString();if(apply){applyPending=true;applyDeadline=System.nanoTime()+120_000_000_000L;status=t("publishing").getString();}}
                if(minecraft.screen==this)init();
            }));
    }
    @Override public void tick() {
        fields.values().forEach(EditBox::tick);
        var instance=preview.getGeneralMeshInstance();
        if(instance!=null && instance.preparing()){previewWasPreparing=true;status=t("preparing_physics").getString();}
        else if(instance!=null && !instance.preparationError().isEmpty()) {
            previewWasPreparing=false;String failure=instance.preparationError();preview.configureEditorPreview(draft,false);physics=false;status=failure;init();
        }
        else if(previewWasPreparing){previewWasPreparing=false;status=t(physics?"physics_reset":"physics_off").getString();}
        if(applyPending) {
          try {
            var next=ClientModelService.instance().resolvePath(sourceCatalogPath);
            if(next.isPresent()&&next.get().equals(applyHash)) {
                applyPending=false;
                new PlayerModelScreen().selectModel(next.get(),sourceCatalogPath,RenderTargetIds.GENERAL_MESH_VARIANT,model);
                status=t("applied").getString();
            } else if(System.nanoTime()>applyDeadline) {applyPending=false;status=t("publish_timeout").getString();}
          } catch(Exception failure) {
            applyPending=false;error("Model profile saved, but applying it failed",failure);
          }
        }
    }
    @Override public void render(GuiGraphics g,int mx,int my,float partial) {
        renderBackground(g);g.drawCenteredString(font,title.copy().append(draft.equals(saved)?"":" *"),width/2,10,0xffffff);
        g.fill(viewLeft,viewTop,viewRight,viewBottom,0xff252b33);
        g.enableScissor(viewLeft,viewTop,viewRight,viewBottom);
        try {
            var s=preview.getGeneralMeshInstance();if(s!=null){s.debugSkeleton=tab.startsWith("bone");s.debugBone=selectedBone;}
            float scale=(viewBottom-viewTop)*.43f;Vec3 center=null;
            if(autoFit && s!=null) {
                var bounds=s.previewBounds(false);
                if(bounds.valid()) {var extent=bounds.max().subtract(bounds.min());double diameter=Math.sqrt(extent.dot(extent));
                    scale=(float)(Math.min(viewRight-viewLeft,viewBottom-viewTop)*.8/Math.max(.1,diameter));center=bounds.center();}
            }
            float anchorY=autoFit?(viewTop+viewBottom)*.5f:viewBottom-8;
            RenderUtil.renderTextureScreenEntity((viewLeft+viewRight)*.5f+panX,anchorY+panY-.8f*scale*zoom,scale*zoom,pitch,yaw,partial,preview,RegisterEntityRenderersEvent.getPlayerRenderer(),!autoFit,center);
            s=preview.getGeneralMeshInstance();
            if(s!=null && tab.startsWith("bone")) {
                var points=s.projectedBones;
                g.drawManaged(()-> {for(var b:points) {
                    int bx=(int)b.x(),by=(int)b.y();boolean chosen=b.index()==selectedBone;
                    g.fill(bx-2,by-2,bx+3,by+3,chosen?0xffffff00:0x8864d8ef);
                    if(chosen)g.drawString(font,bones.get(b.index()).name(),bx+6,by-4,0xffff00);
                }});
            }
            if(!autoFit) {int bottom=viewBottom-8+(int)panY;for(int i=0;i<=4;i++){int py=bottom-(int)(i*.5*scale*zoom);g.fill(viewLeft+6,py,viewLeft+20,py+1,0xffd2dbe8);g.drawString(font,(i*.5)+" m",viewLeft+22,py-4,0xffffff);} }
        } catch(Exception failure) {error("Model editor preview failed",failure);}
        finally {g.disableScissor();}
        for(var entry:fields.entrySet())g.drawString(font,font.plainSubstrByWidth(captions.get(entry.getKey()),90),panel,entry.getValue().getY()+5,0xcccccc);
        if(tab.equals("diagnostics")) {
            int y=40;for(String line:List.of(t("physics_off_hint").getString(),"Bones: "+bones.size(),"Mapped: "+draft.bones().values().stream().filter(b->b.index()>=0).count(),
                "Actions: "+draft.actions().size(),"Height: "+String.format(Locale.ROOT,"%.3f m",draft.placement().actualHeight()),
                "Format: "+model.generalMeshResources().assets().source().model().format(),t("source_preserved").getString())) {g.drawString(font,font.plainSubstrByWidth(line,width-panel-8),panel,y,0xdddddd);y+=18;}
        }
        var scene=preview.getGeneralMeshInstance();
        if(scene!=null)g.drawString(font,String.format(Locale.ROOT,"%.2f s | %.3f m",scene.timeline().state().seconds(),draft.placement().actualHeight()),viewLeft,height-42,0xeeeeaa);
        g.drawString(font,font.plainSubstrByWidth(status,width-110),90,height-14,0xffd898);
        super.render(g,mx,my,partial);
        if(!status.isBlank()&&my>=height-22)g.renderTooltip(font,Component.literal(status),mx,my);
    }
    private boolean inView(double x,double y){return x>=viewLeft&&x<viewRight&&y>=viewTop&&y<viewBottom;}
    @Override public boolean mouseDragged(double x,double y,int button,double dx,double dy){if(inView(x,y)){if(button==0){yaw+=dx*1.5;pitch=Math.max(-80,Math.min(80,pitch-(float)dy));}else if(button==1){panX+=dx;panY+=dy;}return true;}return super.mouseDragged(x,y,button,dx,dy);}
    @Override public boolean mouseScrolled(double x,double y,double delta){if(inView(x,y)){zoom=Math.max(.05f,Math.min(20,zoom*(float)Math.pow(1.1,delta)));return true;}return super.mouseScrolled(x,y,delta);}
    @Override public boolean mouseClicked(double x,double y,int button) {
        if(button==0 && tab.startsWith("bone") && inView(x,y)) {
            var scene=preview.getGeneralMeshInstance();if(scene!=null) {
                var hit=scene.projectedBones.stream().filter(b->Math.hypot(b.x()-x,b.y()-y)<7)
                    .min(Comparator.comparingDouble(b->Math.hypot(b.x()-x,b.y()-y)));
                if(hit.isPresent()){selectedBone=hit.get().index();status=bones.get(selectedBone).name();return true;}
            }
        }
        return super.mouseClicked(x,y,button);
    }
    private void error(String message,Throwable failure){status=message+": "+failure;String key=message+failure;if(!key.equals(lastError))YesSteveModel.LOGGER.error("{} model={}",message,hash,failure);lastError=key;}
    @Override public void removed(){preview.reset();physics=false;preview.configureEditorPreview(draft,false);}
    @Override public boolean isPauseScreen(){return false;}
    @Override public void onClose(){if(!draft.equals(saved)||pendingFields)minecraft.setScreen(new ConfirmScreen(discard->minecraft.setScreen(discard?parent:this),t("discard"),t("discard_hint")));else minecraft.setScreen(parent);}
}
