package cc.sirrus.ysmlib.tools.scene;

import cc.sirrus.ysmlib.*;
import cc.sirrus.ysmlib.scene.*;
import java.awt.*;
import java.awt.event.*;
import java.io.StringWriter;
import java.io.PrintWriter;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/** Desktop host for editing the same sidecar used by Minecraft. All loading/evaluation runs off the EDT. */
public final class SceneEditor extends JFrame {
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"OMYSM editor worker");t.setDaemon(true);return t;});
    private final JLabel status=new JLabel("请选择模型文件；只保存附加配置，不改写模型。");
    private final JTextArea log=new JTextArea(5,90),json=new JTextArea(),metadata=new JTextArea(),heldItems=new JTextArea(),motionInfo=new JTextArea();
    private final JTabbedPane tabs=new JTabbedPane();private final JPanel placement=new JPanel(new GridLayout(0,2,5,5));
    private final Map<String,JTextField> values=new LinkedHashMap<>();private final Deque<SceneModelProfile> undo=new ArrayDeque<>(),redo=new ArrayDeque<>();
    private final JComboBox<String> role=new JComboBox<>(SceneModelProfile.ROLES.toArray(String[]::new));
    private final DefaultListModel<SceneSkeleton.Bone> boneModel=new DefaultListModel<>();private final JList<SceneSkeleton.Bone> boneList=new JList<>(boneModel);
    private final JTextField sourceBone=new JTextField(),sourceModel=new JTextField("@ysm/default"),translationScale=new JTextField("1"),search=new JTextField(),alias=new JTextField("walk"),seconds=new JTextField("0"),pitchField=new JTextField("0"),yawField=new JTextField("0"),rollField=new JTextField("0"),weight=new JTextField("1");
    private final JComboBox<String> clips=new JComboBox<>(),mapped=new JComboBox<>();private final JCheckBox loop=new JCheckBox("循环",true);
    private final Canvas canvas=new Canvas();private final javax.swing.Timer timer;
    private SceneAuthoring.Session session;private SceneModelProfile saved;private boolean busy,rendering,dirtyFields,refreshing,playing,renderPending,closed;private final Set<String> dirtySections=new HashSet<>();
    private double time,yaw,pitch=-8,zoom=1;private int selectedBone=-1,clipIndex=-1;private String playingAlias;
    private long lastTick;
    // Only the worker accesses these mutable playback fields.
    private ScenePackagePlayback player;private SceneAuthoring.Session playerOwner;private SceneModelProfile playerProfile;private ScenePackagePlayback.Selection playerSelection;
    private Map<Integer,Rotation> overlay=Map.of();
    private List<YsmSkeletonBinding.Bone> referenceRig=List.of();
    private boolean referencePreview;
    private final JTextField restPitch=new JTextField("0"),restYaw=new JTextField("0"),restRoll=new JTextField("0");

    public static void open(Path source){if(GraphicsEnvironment.isHeadless())throw new IllegalStateException("GUI requires a desktop; use CLI commands in headless environments");SwingUtilities.invokeLater(()->{var ui=new SceneEditor();ui.setVisible(true);if(source!=null)ui.load(source);});}
    private SceneEditor(){
        super("Oh My YSM Lib — 模型映射配置编辑器");setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);setMinimumSize(new Dimension(960,700));setSize(1280,880);setLocationByPlatform(true);
        JPanel tools=new JPanel(new FlowLayout(FlowLayout.LEFT));
        addButton(tools,"打开模型",()->{if(discard()){var fc=chooser();if(fc.showOpenDialog(this)==JFileChooser.APPROVE_OPTION)load(fc.getSelectedFile().toPath());}});
        addButton(tools,"保存 .omysm.json",this::save);addButton(tools,"另存配置",()->{commitFields();var fc=chooser();if(fc.showSaveDialog(this)==JFileChooser.APPROVE_OPTION)saveTo(fc.getSelectedFile().toPath());});
        addButton(tools,"撤销",()->{commitFields();if(!undo.isEmpty()){redo.push(session.profile());session.profile(undo.pop());refresh();requestRender();}});
        addButton(tools,"重做",()->{if(!redo.isEmpty()){undo.push(session.profile());session.profile(redo.pop());refresh();requestRender();}});
        addButton(tools,"自动补全映射",()->{commitFields();change(session.suggest());});
        addButton(tools,"导出场景包（可选）",()->{commitFields();var fc=chooser();if(fc.showSaveDialog(this)==JFileChooser.APPROVE_OPTION){var path=fc.getSelectedFile().toPath();job(()->{session.pack(path);return path;},p->message("已导出 "+p));}});
        tools.add(new JLabel("预览物理：关闭（不影响游戏设置）"));add(tools,BorderLayout.NORTH);
        buildPlacement();buildBones();buildReferencePose();buildActions();buildTransitions();
        tabs.addTab("持物挂点",textEditor(heldItems,"左右手 binding 引用骨骼映射键；xyz 单位为米，pitch/yaw/roll 为度。物品由游戏绘制，此预览只检查骨架。"));
        tabs.addTab("YSM 元数据",textEditor(metadata,"名称、提示、许可证、作者/联系方式/头像、链接；使用与 YSM 一致的字段。"));
        tabs.addTab("完整配置 JSON",textEditor(json,"此处编辑完整 .omysm.json。校验失败不会写入磁盘。"));
        tabs.addTab("能力与诊断",new JScrollPane(motionInfo));motionInfo.setEditable(false);motionInfo.setLineWrap(true);motionInfo.setWrapStyleWord(true);
        JPanel left=new JPanel(new BorderLayout());left.add(canvas,BorderLayout.CENTER);JPanel transport=new JPanel(new FlowLayout());
        addButton(transport,"播放映射",this::playMapping);addButton(transport,"播放来源",this::playSource);addButton(transport,"暂停",()->playing=false);
        addButton(transport,"−1 帧",()->step(-1));addButton(transport,"+1 帧",()->step(1));seconds.setColumns(7);transport.add(seconds);addButton(transport,"跳转秒",()->{playing=false;time=Double.parseDouble(seconds.getText());if(!Double.isFinite(time)||time<0)throw new IllegalArgumentException("时间必须非负且有限");overlay=Map.of();requestRender();});transport.add(loop);
        addButton(transport,"重置视角",()->{yaw=0;pitch=-8;zoom=1;requestRender();});left.add(transport,BorderLayout.SOUTH);
        var split=new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,left,tabs);split.setResizeWeight(.57);split.setDividerLocation(690);add(split,BorderLayout.CENTER);
        log.setEditable(false);JPanel bottom=new JPanel(new BorderLayout());bottom.add(status,BorderLayout.NORTH);bottom.add(new JScrollPane(log),BorderLayout.CENTER);add(bottom,BorderLayout.SOUTH);
        listen(json);listen(metadata);listen(heldItems);addWindowListener(new WindowAdapter(){@Override public void windowClosing(WindowEvent e){if(busy){message("操作进行中，请等待当前步骤完成。");return;}if(discard()){closed=true;timer.stop();worker.execute(()->{if(player!=null)player.close();});worker.shutdown();dispose();}}});
        timer=new javax.swing.Timer(33,e->tick());timer.start();
    }
    private JFileChooser chooser(){return new JFileChooser(session==null?System.getProperty("user.dir"):session.source().getParent().toString());}
    private void addButton(JPanel panel,String text,Runnable action){var b=new JButton(text);b.addActionListener(e->{if(busy){message("当前步骤处理中，请稍候。");return;}try{if(session==null&&!text.equals("打开模型"))throw new IllegalStateException("请先打开模型");action.run();}catch(Exception ex){error(ex);}});panel.add(b);}
    private void listen(javax.swing.text.JTextComponent field){field.getDocument().addDocumentListener(new DocumentListener(){public void insertUpdate(DocumentEvent e){mark();}public void removeUpdate(DocumentEvent e){mark();}public void changedUpdate(DocumentEvent e){mark();}void mark(){if(!refreshing){dirtyFields=true;dirtySections.add(field==json?"json":field==metadata?"metadata":field==heldItems?"heldItems":"placement");setTitle("* Oh My YSM Lib — 映射配置编辑器");}}});}
    private void buildPlacement(){
        for(String field:List.of("metersPerUnit","sizeMode","scale","height","referenceHeight","footY","x","y","z","yaw")){placement.add(new JLabel(field));var value=new JTextField();values.put(field,value);listen(value);placement.add(value);}
        JPanel page=new JPanel(new BorderLayout());page.add(placement,BorderLayout.NORTH);JPanel buttons=new JPanel();addButton(buttons,"应用尺寸草稿",this::commitPlacement);page.add(buttons,BorderLayout.SOUTH);tabs.addTab("单位与放置",page);
    }
    private void buildBones(){
        JPanel top=new JPanel(new GridLayout(0,1,4,4));top.add(new JLabel("人体角色（IK 控制骨与变形骨分开）"));role.setEditable(true);top.add(role);top.add(new JLabel("额外骨骼使用 bone:自定义名称；动画源为 @ysm/default 或模型内容哈希"));top.add(sourceModel);top.add(new JLabel("YSM 源骨名 → 当前人体角色 → 目标骨"));top.add(sourceBone);top.add(new JLabel("位移换算：目标单位 / YSM 像素"));top.add(translationScale);addButton(top,"保存 YSM 源骨绑定",()->{commitFields();var map=new LinkedHashMap<>(session.profile().retarget().sourceBones());String name=sourceBone.getText().trim();if(name.isEmpty())map.remove(role.getSelectedItem());else map.put((String)role.getSelectedItem(),name);change(session.profile().withRetarget(new SceneModelProfile.Retarget(map,Double.parseDouble(translationScale.getText()),sourceModel.getText().trim())));});top.add(search);
        search.getDocument().addDocumentListener(new DocumentListener(){public void insertUpdate(DocumentEvent e){filterBones();}public void removeUpdate(DocumentEvent e){filterBones();}public void changedUpdate(DocumentEvent e){filterBones();}});
        role.addActionListener(e->{if(!refreshing){readBinding();filterBones();}});
        boneList.setCellRenderer(new DefaultListCellRenderer(){@Override public Component getListCellRendererComponent(JList<?> list,Object value,int index,boolean selected,boolean focus){var c=(JLabel)super.getListCellRendererComponent(list,value,index,selected,focus);if(value instanceof SceneSkeleton.Bone b)c.setText(b.index()+" | "+b.name()+" / "+b.alternate()+" | "+(b.ik()?"IK":"FK"));return c;}});
        boneList.addListSelectionListener(e->{if(!e.getValueIsAdjusting()&&boneList.getSelectedValue()!=null){selectedBone=boneList.getSelectedValue().index();requestRender();}});
        JPanel bottom=new JPanel(new GridLayout(0,2,4,4));for(var entry:Map.of("pitch",pitchField,"yaw",yawField,"roll",rollField,"weight",weight).entrySet()){bottom.add(new JLabel(entry.getKey()));bottom.add(entry.getValue());}
        addButton(bottom,"绑定选中骨",()->{commitFields();if(selectedBone<0)throw new IllegalStateException("先选骨骼");change(session.bind((String)role.getSelectedItem(),selectedBone,Double.parseDouble(pitchField.getText()),Double.parseDouble(yawField.getText()),Double.parseDouble(rollField.getText()),Double.parseDouble(weight.getText())));});
        addButton(bottom,"取消绑定",()->{commitFields();change(session.bind((String)role.getSelectedItem(),-1,0,0,0,1));});
        addButton(bottom,"单骨测试 25°",()->{if(!(session.assets().model() instanceof ScenePackageAssets.Pmx||session.assets().model() instanceof ScenePackageAssets.Pmd))throw new IllegalStateException("单骨叠加仅 MMD 实现；其他格式尚未实现通用重定向");if(selectedBone<0)throw new IllegalStateException("先选骨骼");
            var b=session.profile().bones().values().stream().filter(v->v.index()==selectedBone).findFirst().orElseThrow(()->new IllegalStateException("请先绑定此骨骼，再测试映射校正"));
            var basis=Rotation.axisAngle(new Vec3(0,0,1),Math.toRadians(b.roll())).multiply(Rotation.axisAngle(new Vec3(0,1,0),Math.toRadians(b.yaw()))).multiply(Rotation.axisAngle(new Vec3(1,0,0),Math.toRadians(b.pitch())));
            overlay=Map.of(selectedBone,basis.multiply(Rotation.axisAngle(new Vec3(1,0,0),Math.toRadians(25)*b.weight())).multiply(basis.inverse()));playing=false;requestRender();});
        addButton(bottom,"清除测试姿态",()->{overlay=Map.of();requestRender();});
        JPanel page=new JPanel(new BorderLayout(4,4));page.add(top,BorderLayout.NORTH);page.add(new JScrollPane(boneList),BorderLayout.CENTER);page.add(bottom,BorderLayout.SOUTH);tabs.addTab("骨骼映射",page);
    }
    private void buildReferencePose(){
        JPanel page=new JPanel(new GridLayout(0,1,4,4));
        page.add(new JLabel("先在骨骼映射选择角色；参考姿态独立于动画轴校正。"));
        page.add(new JLabel("参考 X / pitch（度）"));page.add(restPitch);page.add(new JLabel("参考 Y / yaw（度）"));page.add(restYaw);page.add(new JLabel("参考 Z / roll（度）"));page.add(restRoll);
        addButton(page,"应用当前骨参考角",()->{commitFields();String key=(String)role.getSelectedItem();var b=session.profile().bones().get(key);if(b==null||b.index()<0)throw new IllegalArgumentException("先绑定当前骨");
            var map=new LinkedHashMap<>(session.profile().bones());map.put(key,b.withReference(Double.parseDouble(restPitch.getText()),Double.parseDouble(restYaw.getText()),Double.parseDouble(restRoll.getText())));change(session.profile().withBones(map));});
        addButton(page,"读取 YSM 源参考骨架 JSON",()->{var fc=chooser();if(fc.showOpenDialog(this)==JFileChooser.APPROVE_OPTION){var path=fc.getSelectedFile().toPath();job(()->SceneTool.readRig(path),rig->{referenceRig=rig;message("已读取源骨架："+rig.size()+" 骨；坐标需使用 YSM 求值器像素/弧度约定");requestRender();});}});
        addButton(page,"按源骨架校准手臂",()->{commitFields();if(referenceRig.isEmpty())throw new IllegalStateException("先读取源骨架 JSON");change(YsmSkeletonBinding.calibrateArms(referenceRig,session.assets(),session.profile()));referencePreview=true;clipIndex=-1;playing=false;requestRender();});
        addButton(page,"预览绑定后的参考姿态",()->{commitFields();if(referenceRig.isEmpty())throw new IllegalStateException("先读取源骨架 JSON");referencePreview=true;clipIndex=-1;playing=false;requestRender();});
        addButton(page,"恢复来源姿态",()->{referencePreview=false;requestRender();});
        tabs.addTab("参考姿态",new JScrollPane(page));
    }
    private void buildActions(){
        JPanel page=new JPanel(new BorderLayout()),form=new JPanel(new GridLayout(0,1,4,4));form.add(new JLabel("YSM 动作别名（walk、idle 或额外动作名）"));form.add(alias);form.add(new JLabel("已配置别名"));form.add(mapped);form.add(new JLabel("实际来源动画"));form.add(clips);
        mapped.addActionListener(e->{if(!refreshing&&mapped.getSelectedItem()!=null)alias.setText(mapped.getSelectedItem().toString());});
        addButton(form,"绑定动作",()->{commitFields();int index=clips.getSelectedIndex()-1;var map=new LinkedHashMap<>(session.profile().actions());SceneModelProfile.Action action;
            if(index<0)action=new SceneModelProfile.Action("REST",-1,loop.isSelected());else {var c=session.animations().get(index);action=new SceneModelProfile.Action(c.selection().sourceId().startsWith("@ysm/generated/")?c.selection().sourceId():c.sourcePath(),c.selection().clip(),loop.isSelected());}
            map.put(alias.getText().trim(),action);change(session.profile().withActions(map));});
        addButton(form,"恢复 AUTO（删除覆盖）",()->{commitFields();var map=new LinkedHashMap<>(session.profile().actions());map.remove(alias.getText().trim());change(session.profile().withActions(map));});
        page.add(form,BorderLayout.NORTH);var help=new JTextArea("播放映射严格解析配置中的动作来源，使用同一个 Lib 播放器、骨骼绑定和轴校正。\n\n仅能预览实际可用的来源或已有生成动作；全部原生 YSM 曲线跨骨架重定向尚未实现。缺少映射不会伪造动作。\n\n预览物理关闭；IK、Morph、骨骼动画仍按 Lib 求值。当前视窗是几何/骨骼校验着色，不是游戏材质效果验收。");help.setLineWrap(true);help.setWrapStyleWord(true);help.setEditable(false);page.add(help,BorderLayout.CENTER);tabs.addTab("动作映射与预览",page);
    }
    private void buildTransitions(){
        JPanel form=new JPanel(new GridLayout(0,2,4,4));var from=new JTextField("idle");var to=new JTextField("sneaking");var path=new JTextField();var clip=new JTextField("0");
        form.add(new JLabel("来源状态"));form.add(from);form.add(new JLabel("目标状态"));form.add(to);
        form.add(new JLabel("包内过渡动画路径（如 crouch.vmd）"));form.add(path);form.add(new JLabel("clip 索引"));form.add(clip);
        addButton(form,"保存过渡",()->{commitFields();var list=new ArrayList<>(session.profile().transitions());list.removeIf(e->e.from().equals(from.getText().trim())&&e.to().equals(to.getText().trim()));list.add(new SceneModelProfile.Transition(from.getText().trim(),to.getText().trim(),new SceneModelProfile.Action(path.getText().trim(),Integer.parseInt(clip.getText()),false)));change(session.profile().withTransitions(list));});
        addButton(form,"删除过渡",()->{commitFields();var list=new ArrayList<>(session.profile().transitions());list.removeIf(e->e.from().equals(from.getText().trim())&&e.to().equals(to.getText().trim()));change(session.profile().withTransitions(list));});
        var page=new JPanel(new BorderLayout());page.add(form,BorderLayout.NORTH);page.add(new JLabel("过渡播放一次；新状态立即中断。完整 JSON 可查看全部规则；来源预览可检查该片段。"),BorderLayout.SOUTH);tabs.addTab("状态过渡",page);
    }
    private JPanel textEditor(JTextArea text,String hint){text.setFont(new Font(Font.MONOSPACED,Font.PLAIN,13));JPanel p=new JPanel(new BorderLayout());p.add(new JLabel(hint),BorderLayout.NORTH);p.add(new JScrollPane(text),BorderLayout.CENTER);JPanel buttons=new JPanel();addButton(buttons,"校验并应用草稿",this::commitFields);p.add(buttons,BorderLayout.SOUTH);return p;}
    private void load(Path source){playing=false;job(()->SceneAuthoring.open(source,this::progress),value->{session=value;clips.removeAllItems();saved=value.profile();referencePreview=false;undo.clear();redo.clear();dirtyFields=false;selectedBone=-1;clipIndex=-1;playingAlias=null;time=0;overlay=Map.of();refresh();message("已打开 "+source+"；预览物理关闭");requestRender();});}
    private void refresh(){if(session==null)return;refreshing=true;int tab=tabs.getSelectedIndex(),choice=clips.getSelectedIndex();try{var p=session.profile();json.setText(ProfileEdits.json(p));json.setCaretPosition(0);metadata.setText(ProfileEdits.JSON.toJson(p.metadata()));metadata.setCaretPosition(0);heldItems.setText(ProfileEdits.JSON.toJson(p.heldItems()));heldItems.setCaretPosition(0);
        var placement=ProfileEdits.JSON.toJsonTree(p.placement()).getAsJsonObject();values.forEach((k,v)->v.setText(placement.get(k).getAsString()));
        clips.removeAllItems();clips.addItem("REST / 静止");for(var c:session.animations())clips.addItem(c.name()+" | "+c.sourcePath()+" | clip="+c.selection().clip());clips.setSelectedIndex(Math.max(0,Math.min(choice,clips.getItemCount()-1)));
        mapped.removeAllItems();new TreeSet<>(p.actions().keySet()).forEach(mapped::addItem);readBinding();filterBones();
        motionInfo.setText("模型: "+session.source()+"\n配置: "+SceneAuthoring.sidecar(session.source())+"\n骨骼: "+session.bones().size()+"\n实际来源/生成动作: "+session.animations().size()+"\n身高: "+p.placement().actualHeight()+" m\n\n全部操作保存附加配置；不会改写模型、纹理或动作。\n物理: OFF（不创建物理世界）\n解析/求值: 与游戏共用 Lib\n预览着色: 几何校验，尚未复用游戏 MToon/PBR/MMD 材质程序\n骨架绑定: 可配置源骨名与目标角色；Lib 可重定向已求值的刚性骨架姿态\n原生 YSM 曲线/Molang 的独立求值接线: 尚未实现\n\n修改后点击应用草稿或保存。");
        dirtyFields=false;dirtySections.clear();setTitle((p.equals(saved)?"":"* ")+"Oh My YSM Lib — "+session.source().getFileName());}finally{tabs.setSelectedIndex(tab);refreshing=false;}}
    private void filterBones(){if(session==null)return;String needle=search.getText().toLowerCase(Locale.ROOT);boolean ik=Objects.toString(role.getSelectedItem(),"").endsWith("Ik");boneModel.clear();for(var b:session.bones())if(b.ik()==ik&&(b.index()+" "+b.name()+" "+b.alternate()).toLowerCase(Locale.ROOT).contains(needle))boneModel.addElement(b);}
    private void readBinding(){if(session==null)return;sourceModel.setText(session.profile().retarget().sourceModel());translationScale.setText(Double.toString(session.profile().retarget().translationScale()));sourceBone.setText(session.profile().retarget().sourceBones().getOrDefault(role.getSelectedItem(),""));var b=session.profile().bones().get(role.getSelectedItem());selectedBone=b==null?-1:b.index();pitchField.setText(Double.toString(b==null?0:b.pitch()));yawField.setText(Double.toString(b==null?0:b.yaw()));rollField.setText(Double.toString(b==null?0:b.roll()));weight.setText(Double.toString(b==null?1:b.weight()));restPitch.setText(Double.toString(b==null?0:b.restPitch()));restYaw.setText(Double.toString(b==null?0:b.restYaw()));restRoll.setText(Double.toString(b==null?0:b.restRoll()));requestRender();}
    private void change(SceneModelProfile p){session.validate(p);if(!p.equals(session.profile())){undo.push(session.profile());if(undo.size()>64)undo.removeLast();redo.clear();session.profile(p);}refresh();requestRender();}
    private void commitPlacement(){dirtyFields=true;dirtySections.add("placement");commitFields();}
    private void commitFields(){
        if(session==null||!dirtyFields)return;
        if(dirtySections.contains("json")&&dirtySections.size()>1)throw new IllegalStateException("完整 JSON 与表单同时有草稿，请先统一内容，避免覆盖另一份输入。");
        var p=session.profile();
        if(dirtySections.contains("json"))p=ProfileEdits.parse(json.getText());
        if(dirtySections.contains("placement"))for(var e:values.entrySet())p=ProfileEdits.set(p,"placement."+e.getKey(),e.getValue().getText());
        if(dirtySections.contains("metadata"))p=ProfileEdits.set(p,"metadata",metadata.getText());
        if(dirtySections.contains("heldItems"))p=ProfileEdits.set(p,"heldItems",heldItems.getText());
        change(p);
    }
    private boolean discard(){return session==null||(!dirtyFields&&session.profile().equals(saved))||JOptionPane.showConfirmDialog(this,"放弃未保存的配置草稿？","未保存",JOptionPane.YES_NO_OPTION)==JOptionPane.YES_OPTION;}
    private void save(){commitFields();saveTo(SceneAuthoring.sidecar(session.source()));}
    private void saveTo(Path path){var writing=session.profile();job(()->{session.save(path);return path;},p->{if(p.toAbsolutePath().normalize().equals(SceneAuthoring.sidecar(session.source())))saved=writing;refresh();message("配置已保存："+p+"；源模型未改写");});}
    private void playMapping(){commitFields();String name=alias.getText().trim();var a=session.profile().actions().get(name);if(a==null)throw new IllegalArgumentException("该别名没有明确映射；请绑定实际来源，或选择来源动画预览。不会用默认摆动替代。");var selection=a.resolve(session.assets().source());clipIndex=-1;var list=session.animations();for(int i=0;i<list.size();i++)if(list.get(i).selection().equals(selection))clipIndex=i;loop.setSelected(a.loop());playingAlias=name;start();}
    private void playSource(){commitFields();clipIndex=clips.getSelectedIndex()-1;playingAlias=null;start();}
    private void start(){referencePreview=false;time=clipIndex<0?0:session.animations().get(clipIndex).range().start();overlay=Map.of();playing=clipIndex>=0;lastTick=System.nanoTime();requestRender();}
    private void step(int delta){playing=false;double rate=clipIndex<0?30:session.animations().get(clipIndex).range().framesPerSecond();time=Math.max(0,time+delta/rate);requestRender();}
    private void tick(){if(session==null||closed)return;if(playing){long now=System.nanoTime();double elapsed=(now-lastTick)/1e9;lastTick=now;time+=elapsed;var range=session.animations().get(clipIndex).range();if(time>range.end()){if(loop.isSelected()&&range.end()>range.start())time=range.start()+(time-range.start())%(range.end()-range.start());else{time=range.end();playing=false;}}renderPending=true;}
        if(renderPending&&!busy&&!rendering){renderPending=false;renderFrame();}}
    private void requestRender(){renderPending=true;}
    private void renderFrame(){if(session==null)return;var owner=session;var profile=owner.profile();double t=time,ry=yaw,rp=pitch,rz=zoom;int bone=selectedBone;var rotations=overlay;var rig=referenceRig;boolean reference=referencePreview;var selection=clipIndex<0?ScenePackagePlayback.Selection.REST:owner.animations().get(clipIndex).selection();
        job(()->{if(player==null||playerOwner!=owner||!profile.bones().equals(playerProfile.bones())||!selection.equals(playerSelection)){
                var next=YsmRuntime.scenes().playback(owner.assets(),selection,ScenePackagePlayback.Settings.previewUi().withPhysics(false).withModelProfile(profile),SceneAuthoring.LIMITS);if(player!=null)player.close();player=next;playerOwner=owner;playerProfile=profile;playerSelection=selection;}
            if(reference){var binding=new YsmSkeletonBinding(rig,owner.assets(),profile);player.bonePoses(binding.referencePose());player.ikOverrides(binding.ikOverrides());}
            else {player.bonePoses(Map.of());player.ikOverrides(Map.of());}
            player.boneRotations(rotations);return ScenePreview.render(player.seek(t),profile,owner.bones(),720,720,ry,rp,rz,bone,owner.referenceBounds(profile));
        },frame->{if(session!=owner||!profile.equals(owner.profile())){requestRender();return;}canvas.frame=frame;canvas.repaint();seconds.setText(String.format(Locale.ROOT,"%.3f",t));status.setText((playingAlias==null?"来源预览":"YSM 别名："+playingAlias)+" | t="+String.format(Locale.ROOT,"%.3f",t)+" | "+frame.triangles()+" 三角面 | 物理关闭");},true);}
    private <T> void job(Callable<T> task,java.util.function.Consumer<T> done){job(task,done,false);}
    private <T> void job(Callable<T> task,java.util.function.Consumer<T> done,boolean render){
        if(busy || render&&rendering)return;if(render)rendering=true;else busy=true;
        worker.submit(()->{try{T value=task.call();SwingUtilities.invokeLater(()->{if(render)rendering=false;else busy=false;if(!closed)try{done.accept(value);}catch(Exception e){error(e);}});}
            catch(Exception e){SwingUtilities.invokeLater(()->{if(render)rendering=false;else busy=false;playing=false;if(!closed)error(e);});}});
    }
    private void progress(String text){SwingUtilities.invokeLater(()->message(text));}
    private void message(String text){status.setText(text);log.append(text+"\n");if(log.getDocument().getLength()>64000)log.setText(log.getText().substring(log.getText().length()-32000));log.setCaretPosition(log.getDocument().getLength());}
    private void error(Exception e){message("失败："+e);var writer=new StringWriter();e.printStackTrace(new PrintWriter(writer));log.append(writer.toString());System.err.println(writer);}
    private final class Canvas extends JPanel {
        ScenePreview.Result frame;int lastX,lastY;
        Canvas(){setBackground(new Color(37,44,54));var mouse=new MouseAdapter(){@Override public void mousePressed(MouseEvent e){lastX=e.getX();lastY=e.getY();}
            @Override public void mouseDragged(MouseEvent e){yaw+=(e.getX()-lastX)*.7;pitch=Math.max(-85,Math.min(85,pitch+(e.getY()-lastY)*.7));lastX=e.getX();lastY=e.getY();requestRender();}
            @Override public void mouseWheelMoved(MouseWheelEvent e){zoom=Math.max(.1,Math.min(8,zoom*Math.pow(1.1,-e.getPreciseWheelRotation())));requestRender();}
            @Override public void mouseClicked(MouseEvent e){if(frame==null)return;double scale=Math.min(getWidth(),getHeight())/720.0,x=(e.getX()-(getWidth()-720*scale)/2)/scale,y=(e.getY()-(getHeight()-720*scale)/2)/scale;
                frame.bones().stream().filter(p->Math.hypot(p.x()-x,p.y()-y)<10).min(Comparator.comparingDouble(p->Math.hypot(p.x()-x,p.y()-y))).ifPresent(p->{selectedBone=p.bone();message("选中骨 "+p.bone()+" "+session.bones().get(p.bone()).name());requestRender();});}};
            addMouseListener(mouse);addMouseMotionListener(mouse);addMouseWheelListener(mouse);}
        @Override protected void paintComponent(Graphics graphics){super.paintComponent(graphics);if(frame==null){graphics.setColor(Color.WHITE);graphics.drawString("打开模型后在此预览真实几何、骨骼与映射动作。",30,40);return;}int side=Math.min(getWidth(),getHeight());graphics.drawImage(frame.image(),(getWidth()-side)/2,(getHeight()-side)/2,side,side,null);}
    }
}
