import cc.sirrus.ysmlib.YsmRuntime;
import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.io.ReadLimits;
import cc.sirrus.ysmlib.scene.java.JavaDeformationProvider;
import java.util.*;

/** Fresh process with only the shaded runtime and host JOML. */
public final class SkinningDistributionSmoke {
    public static void main(String[] args)throws Exception {
        boolean nativeExpected=Boolean.parseBoolean(args[0]);var provider=YsmRuntime.deformation();
        if(provider.id().startsWith("native-")!=nativeExpected)throw new AssertionError("Wrong skinning provider: "+provider.id());
        var status=YsmRuntime.diagnostics().stream().filter(s->s.module().equals("Skinning")).findFirst().orElseThrow();
        if(!status.implementation().contains(provider.id())||!nativeExpected&&!status.implementation().contains(args[1]))throw new AssertionError("Wrong cached diagnostic: "+status);
        for(String name:List.of("skin.pmx","morph.pmx")){
            var bytes=SkinningDistributionSmoke.class.getResourceAsStream("/mmd-oracle/"+name).readAllBytes();
            var model=YsmRuntime.scenes().readPmx(new ByteData(bytes),ReadLimits.DEFAULT);var mesh=YsmRuntime.scenes().mesh(model).primitives().get(0);
            var palette=Collections.nCopies(model.bones().size(),Matrix4.IDENTITY);var weights=new FloatData(new float[mesh.morphs().size()]);
            try(var actual=provider.compile(mesh);var oracle=new JavaDeformationProvider().compile(mesh)){
                var a=actual.deform(palette,weights,SceneProvider.NormalPolicy.MMD_WEIGHTED_ROTATION);var b=oracle.deform(palette,weights,SceneProvider.NormalPolicy.MMD_WEIGHTED_ROTATION);
                for(var attribute:b.entrySet()){var data=a.get(attribute.getKey()).values();for(int i=0;i<data.size();i++)if(Math.abs(data.get(i)-attribute.getValue().values().get(i))>2e-5)throw new AssertionError("Shaded JNI output mismatch");}
            }
        }
        System.out.println("Skinning distribution passed: "+status.implementation());
    }
}
