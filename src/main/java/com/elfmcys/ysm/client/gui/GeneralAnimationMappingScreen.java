package com.elfmcys.ysm.client.gui;

import cc.sirrus.ysmlib.scene.*;
import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.client.animation.GeneralAnimationMappingStore;
import com.elfmcys.ysm.client.event.RegisterEntityRenderersEvent;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.RenderTargetIds;
import com.elfmcys.ysm.model.resource.client.ModelRenderTarget;
import com.elfmcys.ysm.model.service.ClientModelService;
import com.elfmcys.ysm.util.RenderUtil;
import java.util.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Draft-based mapping editor with an independently owned model preview. */
public final class GeneralAnimationMappingScreen extends Screen {
    private final Screen parent;
    private final Hash256 hash;
    private final ModelRenderTarget model;
    private final List<SceneAnimation> clips;
    private final CustomGuiPlayerEntity preview = new CustomGuiPlayerEntity();
    private final SortedSet<String> targets = new TreeSet<>();
    private EditBox search, name;
    private String query="", target="idle", status="";
    private int targetPage, sourcePage, selected=-1, rows, x, y, column;
    private boolean loop=true, playPending;
    private String lastPreviewError="";
    public GeneralAnimationMappingScreen(Screen parent, Hash256 hash, ModelRenderTarget model) {
        super(label("title"));this.parent=parent;this.hash=hash;this.model=model;
        clips=model.generalMeshResources().animations();
        targets.addAll(List.of("idle","walk","run","sneak","sneaking","swim","swim_stand","fly","elytra_fly",
                "death","sleep","jump","attacked","riptide","climb","climbing","ladder_up","ladder_down","ladder_stillness"));
        var defaults=ClientModelService.instance().defaultRenderTarget().playerResources();
        if(defaults!=null) targets.addAll(defaults.animations().keySet());
        targets.addAll(GeneralAnimationMappingStore.instance().entries(hash).keySet());
        loadTarget(target);
    }
    private static Component label(String key,Object... args) {
        return Component.translatable("gui.yes_steve_model.mapping."+key,args);
    }
    private void loadTarget(String value) {
        target=value;selected=-1;
        var mapping=GeneralAnimationMappingStore.instance().entries(hash).get(value);
        loop=mapping==null || mapping.loop();
        if(mapping!=null) {
            if(mapping.selection().equals(ScenePackagePlayback.Selection.REST)) selected=-2;
            else {
                for(int i=0;i<clips.size();i++) if(clips.get(i).selection().equals(mapping.selection())) { selected=i;break; }
                if(selected==-1) status=label("missing").getString();
            }
        }
    }
    @Override protected void init() {
        clearWidgets();
        column=Math.max(80, Math.min(190,(width-32)/3));x=(width-column*3-16)/2;y=26;
        rows=Math.max(1,(height-140)/22);
        preview.updateModelAndTexture(hash, RenderTargetIds.GENERAL_MESH_VARIANT);
        search=addRenderableWidget(new EditBox(font,x,y,column,18,label("search")));
        search.setValue(query);search.setResponder(value->{query=value;targetPage=0; rebuildLists();});
        name=addRenderableWidget(new EditBox(font,x+column+8,y,column,18,label("alias")));
        name.setMaxLength(128);name.setValue(target);
        addRenderableWidget(Button.builder(label("use_name"),b->{loadTarget(name.getValue().trim());init();})
                .bounds(x+column*2+16,y,column,18).build());
        rebuildLists();
    }
    private final List<Button> listButtons=new ArrayList<>();
    private Button button(int bx,int by,int w,Component text,Button.OnPress action) {
        var b=Button.builder(text,action).bounds(bx,by,w,20).build();addRenderableWidget(b);listButtons.add(b);return b;
    }
    private void rebuildLists() {
        listButtons.forEach(this::removeWidget);listButtons.clear();
        var filtered=targets.stream().filter(t->t.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))).toList();
        targetPage=Math.min(targetPage,Math.max(0,(filtered.size()-1)/rows));
        for(int i=0;i<rows && targetPage*rows+i<filtered.size();i++) {
            String key=filtered.get(targetPage*rows+i);
            button(x,y+34+i*22,column,Component.literal((key.equals(target)?"> ":"")+key),b->{loadTarget(key);name.setValue(key);rebuildLists();});
        }
        int total=clips.size()+2;
        sourcePage=Math.min(sourcePage,Math.max(0,(total-1)/rows));
        for(int i=0;i<rows && sourcePage*rows+i<total;i++) {
            int index=sourcePage*rows+i-2;
            Component text=index==-2?label("rest"):index==-1?label("auto"):
                    Component.literal(clips.get(index).name().isBlank()?clips.get(index).sourcePath():clips.get(index).name());
            var b=button(x+column+8,y+34+i*22,column,text,ignored->{selected=index;rebuildLists();});
            if(index==selected) b.setMessage(Component.literal("> ").append(text));
            if(index>=0) b.setTooltip(Tooltip.create(Component.literal(clips.get(index).sourcePath()+" / "+clips.get(index).selection().clip())));
        }
        int bottom=height-74;
        button(x,bottom,column/2-2,Component.literal("<"),b->{targetPage=Math.max(0,targetPage-1);rebuildLists();});
        button(x+column/2,bottom,column/2,Component.literal(">"),b->{targetPage=Math.min(Math.max(0,(filtered.size()-1)/rows),targetPage+1);rebuildLists();});
        button(x+column+8,bottom,column/2-2,Component.literal("<"),b->{sourcePage=Math.max(0,sourcePage-1);rebuildLists();});
        button(x+column+8+column/2,bottom,column/2,Component.literal(">"),b->{sourcePage=Math.min((total-1)/rows,sourcePage+1);rebuildLists();});
        button(x+column*2+16,bottom,column,label(loop?"loop":"once"),b->{loop=!loop;rebuildLists();});
        button(x+column*2+16,height-96,column/2-2,label("preview"),b->playPending=true);
        button(x+column*2+16+column/2,height-96,column/2,label("pause"),b->{if(preview.getGeneralMeshInstance()!=null) preview.getGeneralMeshInstance().timeline().pause();});
        button(x,height-28,column,label("back"),b->onClose());
        button(x+column+8,height-28,column,label("save"),b->save());
        button(x+column*2+16,height-28,column,label("remove"),b->{selected=-1;save();});
    }
    private void save() {
        try {
            var selection=selected<0?ScenePackagePlayback.Selection.REST:clips.get(selected).selection();
            GeneralAnimationMappingStore.instance().put(hash,target,selected==-1?null:
                new GeneralAnimationMappingStore.Entry(selection.sourceId(),selection.clip(),loop));
            targets.add(target);status=label("saved").getString();rebuildLists();
        } catch(Exception failure) {
            status=label("failed",failure.getMessage()).getString();
            YesSteveModel.LOGGER.error("Cannot save scene action mapping model={} alias={}",hash,target,failure);
        }
    }
    @Override public void render(GuiGraphics g,int mx,int my,float partial) {
        renderBackground(g);g.drawCenteredString(font,title,width/2,8,0xffffff);
        g.drawString(font,label("targets"),x,y+22,0xcccccc);
        g.drawString(font,label("sources"),x+column+8,y+22,0xcccccc);
        int bx=x+column*2+16, top=y+34, bottom=height-100;
        g.enableScissor(bx,top,bx+column,bottom);
        try {
            RenderUtil.renderModelInGui(bx+column/2f,(top+bottom)/2f,Math.min(column,bottom-top)*.45f,partial,
                    preview,RegisterEntityRenderersEvent.getPlayerRenderer(),false,true);
            if(playPending && preview.getGeneralMeshInstance()!=null) {
                var clip=selected==-1?com.elfmcys.ysm.client.animation.GeneralAnimationActions.automatic(model,target):selected<0?null:clips.get(selected);
                var selection=clip==null?ScenePackagePlayback.Selection.REST:clip.selection();
                var range=clip==null?new AnimationPreview.Range(0,0,30):clip.range();
                preview.previewGeneralAnimation(selection,range,loop);playPending=false;
            }
        } catch(RuntimeException failure) {
            String error=failure.toString();
            if(!error.equals(lastPreviewError)) YesSteveModel.LOGGER.error("Scene mapping preview failed model={}",hash,failure);
            lastPreviewError=error;
            status=label("failed",failure.getMessage()).getString();playPending=false;
        } finally { g.disableScissor(); }
        g.drawString(font,font.plainSubstrByWidth(status.isBlank()?label("hint").getString():status,width-20),10,height-43,0xeeeeaa);
        super.render(g,mx,my,partial);
    }
    @Override public void tick() { search.tick();name.tick(); }
    @Override public void removed() { preview.reset(); }
    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
