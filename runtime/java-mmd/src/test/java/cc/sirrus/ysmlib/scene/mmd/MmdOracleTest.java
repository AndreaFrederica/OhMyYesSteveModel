package cc.sirrus.ysmlib.scene.mmd;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.io.*;
import cc.sirrus.ysmlib.scene.java.*;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MmdOracleTest {
  private ByteData resource(String name) throws IOException {
    try(var input=getClass().getResourceAsStream("/mmd-oracle/"+name)) { assertNotNull(input);return new ByteData(input.readAllBytes()); }
  }
  @Test void cpuSkinningMatchesIndependentSabaAcross61AnimatedSamples() throws Exception { compare("skin"); }
  @Test void nestedSkeletonBoneGroupAndMaterialMorphsMatchIndependentSaba() throws Exception { compare("morph"); }
  @Test void pmdBaseMorphHierarchyAndAnimationMatchIndependentSaba() throws Exception { compare("pmd"); }
  private void compare(String stem) throws Exception {
    PmxDocument model;MeshAsset.Primitive mesh;MmdEvaluator evaluator;
    if(stem.equals("pmd")) {
      var pmd=new PmdReader().read(resource("pmd.pmd"),ReadLimits.DEFAULT);model=new PmdRuntimeProfile(pmd).asset;mesh=new MmdMeshCompiler().compile(pmd).primitives().get(0);evaluator=new MmdEvaluator(pmd);
    } else { model=new PmxReader().read(resource(stem+".pmx"),ReadLimits.DEFAULT);mesh=new MmdMeshCompiler().compile(model).primitives().get(0);evaluator=new MmdEvaluator(model); }
    var motion=new VmdAnimation().compile(new VmdReader().read(resource(stem+".vmd"),ReadLimits.DEFAULT)).clip();
    var sampler=new ClipSampler();var deformer=new Deformer();
    var lines=new String(resource(stem+".csv").copy(),StandardCharsets.UTF_8).lines().skip(1).toList();int cursor=0;
    for(int frame=0;frame<=60;frame++) {
      var sample=sampler.sample(motion,frame/60.0);var pose=evaluator.evaluate(sample);assertTrue(pose.diagnostics().isEmpty());
      var result=deformer.deform(mesh,pose.palette(),pose.morphs().meshWeights(),Deformer.NormalMode.MMD_WEIGHTED_ROTATION);
      for(int i=0;i<model.bones().size()+model.vertices().size()+model.materials().size();i++) {
        String[] values=lines.get(cursor++).split(",");assertEquals(frame,Integer.parseInt(values[0]));int index=Integer.parseInt(values[2]);
        float[] actual;
        if(values[1].equals("bone")) actual=pose.bones().get(index).matrix().copy();
        else if(values[1].equals("material")) {
          var m=pose.morphs().materials().get(index);actual=new float[]{m.diffuse().get(0),m.diffuse().get(1),m.diffuse().get(2),m.diffuse().get(3),m.specular().x(),m.specular().y(),m.specular().z(),m.shininess(),m.ambient().x(),m.ambient().y(),m.ambient().z()};
        }
        else { actual=new float[6];for(int k=0;k<3;k++) { actual[k]=result.get("POSITION").values().get(index*3+k);actual[k+3]=result.get("NORMAL").values().get(index*3+k); } }
        for(int k=0;k<actual.length;k++) assertEquals(Float.parseFloat(values[k+3]),actual[k],.00003f,
            "Saba frame="+frame+" kind="+values[1]+" index="+index+" component="+k);
      }
    }
    assertEquals(lines.size(),cursor);
  }
}
