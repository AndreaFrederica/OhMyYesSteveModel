package com.elfmcys.ysm.client.renderer;

import cc.sirrus.ysmlib.scene.*;
import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.client.entity.CustomEntity;
import com.elfmcys.ysm.geckolib3.geo.GeoReplacedEntityRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;
import java.util.*;

/** Minecraft-only sampling. One bounded neighborhood per game tick; no file scanning or chunk loading. */
final class GeneralMeshPhysicsHost {
    private record Fluid(AABB box,Vec3 flow,float density) {}
    private final List<AABB> solids=new ArrayList<>();
    private final List<Fluid> fluids=new ArrayList<>();
    private int sampledTick=Integer.MIN_VALUE;
    private double sampledX,sampledY,sampledZ;
    private boolean warned;
    private final cc.sirrus.ysmlib.scene.physics.ScenePhysicsMotion motion=new cc.sirrus.ysmlib.scene.physics.ScenePhysicsMotion();

    cc.sirrus.ysmlib.scene.physics.PhysicsEnvironment sample(CustomEntity<?> owner,GeneralMeshInstance instance,float partialTicks,double seconds) {
        var entity=owner.getEntity();var level=entity.level();
        double x=Mth.lerp(partialTicks,entity.xo,entity.getX()),y=Mth.lerp(partialTicks,entity.yo,entity.getY()),z=Mth.lerp(partialTicks,entity.zo,entity.getZ());
        var renderer=entity instanceof net.minecraft.world.entity.player.Player?
            com.elfmcys.ysm.client.event.RegisterEntityRenderersEvent.getPlayerRenderer():Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(entity);
        Matrix4f placement=renderer instanceof GeoReplacedEntityRenderer<?,?> replaced?replaced.generalPhysicsPlacement(owner,partialTicks):
            new Matrix4f().rotateY((float)Math.toRadians(180-Mth.rotLerp(partialTicks,entity.yRotO,entity.getYRot()))).translate(0,.01f,0);
        placement.scale(owner.getWidthScale(),owner.getHeightScale(),owner.getWidthScale()).rotateY((float)Math.PI)
            .mul(instance.placement()).mul(SceneEntityRenderer.sourceToMeters(instance.frame().thirdPerson().coordinates()));
        var profile=instance.profile();var options=profile.hostPhysics();
        if(sampledTick!=entity.tickCount||Math.abs(entity.getX()-sampledX)+Math.abs(entity.getY()-sampledY)+Math.abs(entity.getZ()-sampledZ)>2) {
            sampledTick=entity.tickCount;sampledX=entity.getX();sampledY=entity.getY();sampledZ=entity.getZ();solids.clear();fluids.clear();
            double radius=Math.max(1,profile.placement().actualHeight()*Math.max(owner.getWidthScale(),owner.getHeightScale())+
                Math.abs(profile.placement().x())+Math.abs(profile.placement().y())+Math.abs(profile.placement().z()));
            if(radius>8){warn("model extent exceeds the eight-metre host contact sampling radius");radius=8;}
            var region=new AABB(x-radius,y-radius,z-radius,x+radius,y+radius,z+radius);
            if(options.worldCollision())for(var shape:level.getBlockCollisions(entity,region))for(var box:shape.toAabbs()) {
                if(solids.size()==4096){warn("nearby block collision shape budget exceeded (4096)");break;}solids.add(box);
            }
            var cursor=new BlockPos.MutableBlockPos();
            for(int bx=Mth.floor(region.minX);bx<=Mth.floor(region.maxX);bx++)for(int by=Math.max(level.getMinBuildHeight(),Mth.floor(region.minY));by<=Math.min(level.getMaxBuildHeight()-1,Mth.floor(region.maxY));by++)
                for(int bz=Mth.floor(region.minZ);bz<=Mth.floor(region.maxZ);bz++) {
                    cursor.set(bx,by,bz);if(!level.hasChunkAt(cursor))continue;
                    var fluid=level.getFluidState(cursor);if(fluid.isEmpty())continue;
                    if(fluids.size()==4096){warn("nearby fluid sampling budget exceeded (4096)");continue;}
                    double h=fluid.getHeight(level,cursor);if(h<=0)continue;var flow=fluid.getFlow(level,cursor);
                    // Vanilla applies flow as tick displacement/acceleration, not a physical velocity field.
                    // One block/s is an explicit visual advection policy; gameplay flow is untouched.
                    fluids.add(new Fluid(new AABB(bx,by,bz,bx+1,by+h,bz+1),new Vec3((float)flow.x,(float)flow.y,(float)flow.z),fluid.is(FluidTags.LAVA)?1.5f:1));
                }
        }
        var boxes=solids.stream().map(box->relative(box,x,y,z)).toList();
        var water=fluids.stream().map(fluid->new ScenePhysicsInput.Fluid(relative(fluid.box,x,y,z),fluid.flow,fluid.density)).toList();
        return motion.update(new ScenePhysicsInput(seconds,x,y,z,new Matrix4(placement.get(new float[16])),new Vec3(0,-9.8f,0),boxes,water,options));
    }
    private static ScenePhysicsInput.Box relative(AABB box,double x,double y,double z) {
        return new ScenePhysicsInput.Box(new Vec3((float)(box.minX-x),(float)(box.minY-y),(float)(box.minZ-z)),
            new Vec3((float)(box.maxX-x),(float)(box.maxY-y),(float)(box.maxZ-z)));
    }
    private void warn(String message){if(!warned){warned=true;YesSteveModel.LOGGER.warn("General model host physics: {}",message);}}
}
