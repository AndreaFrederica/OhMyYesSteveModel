package com.elfmcys.ysm.client.entity;

import cc.sirrus.ysmlib.YsmSkeletonBinding;
import cc.sirrus.ysmlib.scene.*;
import com.elfmcys.ysm.client.renderer.GeneralMeshInstance;
import com.elfmcys.ysm.geckolib3.geo.RenderContext;
import com.elfmcys.ysm.model.resource.client.*;
import com.elfmcys.ysm.model.service.ClientModelService;
import net.minecraft.world.entity.player.Player;
import java.util.*;

/** An independent, non-drawing source rig running the original YSM player controllers. */
final class YsmAnimationDriver extends CustomPlayerEntity implements AutoCloseable {
    private final CustomPlayerEntity owner;
    private final ResourceRequest request;
    private final ResourceLease sourceLease;
    private final String sourceModel;
    private YsmSkeletonBinding binding;
    private SceneModelProfile boundProfile;
    private Object boundModel;
    private boolean installed,closed;
    private long sequence=Long.MIN_VALUE;
    private Map<Integer,Pose> lastPose;

    YsmAnimationDriver(CustomPlayerEntity owner,String sourceModel) {
        // Local-player authority remains with owner; the driver must not emit stop/sync requests.
        super(owner.getEntity(),false,false);this.owner=owner;
        this.sourceModel=sourceModel;
        var service=ClientModelService.instance();request=sourceModel.equals("@ysm/default")?service.defaultResourceRequest("player"):
            service.resourceRequest(com.elfmcys.ysm.model.domain.Hash256.parse(sourceModel),"player","");
        sourceLease=service.getOrStart(request);
    }
    String sourceModel(){return sourceModel;}
    boolean drive(GeneralMeshInstance target,float partialTicks) {
        if(closed)throw new IllegalStateException("YSM animation driver is closed");
        if(!ready())return false;
        var model=getLoadedGeoModel();
        if(binding==null || !target.profile().equals(boundProfile) || model.getModel()!=boundModel) {
            var rig=sourceRig();
            binding=new YsmSkeletonBinding(rig,target.assets(),target.profile());boundProfile=target.profile();boundModel=model.getModel();sequence=Long.MIN_VALUE;
        }
        long next=com.elfmcys.ysm.client.event.ClientTickEvent.renderSequence();
        if(sequence!=next) {
            if(owner.getStateTracker() instanceof com.elfmcys.ysm.capability.PlayerStateTracker synced)
                ((com.elfmcys.ysm.capability.PlayerStateTracker)getStateTracker()).copyProtocolStateFrom(synced);
            tickAnimation(createAnimationEvent(partialTicks,RenderContext.levelImmutable()));
            lastPose=binding.evaluate(model.getBoneAttributes());sequence=next;
        }
        target.skeletonPose(lastPose,binding.ikOverrides());return true;
    }
    private boolean ready() {
        if(!installed) {
            var state=sourceLease.poll();
            if(state instanceof AcquireResult.Failed failed)throw new IllegalStateException("Cannot load YSM source skeleton",failed.failure().cause());
            if(!(state instanceof AcquireResult.Ready ready))return false;
            if(ready.target().generalMeshResources()!=null||ready.target().playerResources()==null)
                throw new IllegalArgumentException("YSM animation source must provide an MC player skeleton: "+sourceModel);
            installReadyForPreview(request,sourceLease);installed=true;
        }
        return true;
    }
    private java.util.List<YsmSkeletonBinding.Bone> sourceRig() {
        var model=getLoadedGeoModel();var metadata=model.getModel().sortedBones();var baked=model.getModel().bakedModel().runtimeModel().bones();
        var rig=new ArrayList<YsmSkeletonBinding.Bone>();
        for(int i=0;i<metadata.size();i++) {
            var b=metadata.get(i);var p=b.pivot();var r=b.rotation();
            rig.add(new YsmSkeletonBinding.Bone(b.name(),baked.get(i).parent(),new Vec3(p.x,p.y,p.z),new Vec3(r.x,r.y,r.z)));
        }
        return rig;
    }
    SceneModelProfile calibrate(GeneralMeshInstance target) {
        if(!ready())throw new IllegalStateException("YSM source skeleton is still loading; retry when ready");
        return YsmSkeletonBinding.calibrateArms(sourceRig(),target.assets(),target.profile());
    }
    boolean reference(GeneralMeshInstance target) {
        if(!ready())return false;
        if(binding==null||!target.profile().equals(boundProfile)||getLoadedGeoModel().getModel()!=boundModel) {
            binding=new YsmSkeletonBinding(sourceRig(),target.assets(),target.profile());boundProfile=target.profile();boundModel=getLoadedGeoModel().getModel();sequence=Long.MIN_VALUE;
        }
        target.skeletonPose(binding.referencePose(),binding.ikOverrides());return true;
    }
    @Override protected HumanoidStateTracker<Player> createStateTracker(Player player){return new com.elfmcys.ysm.capability.PlayerStateTracker(player,player instanceof net.minecraft.client.player.LocalPlayer);}
    @Override public com.elfmcys.ysm.molang.runtime.Struct getRoamingStruct(){return owner==null?null:owner.getRoamingStruct();}
    @Override public boolean isLocalPlayer(){return owner!=null&&owner.isLocalPlayer();}
    @Override public void checkModelUpdate() {} // Source lease is stable for this binding generation.
    @Override protected boolean allowEmitting(){return false;}
    @Override protected ResourceHolder createResourceHolder(ResourceLease lease,boolean fallback){return new HumanoidResourceHolder(lease,fallback);}
    @Override public void close(){if(!closed){closed=true;try{reset();}finally{sourceLease.close();}}}
}
