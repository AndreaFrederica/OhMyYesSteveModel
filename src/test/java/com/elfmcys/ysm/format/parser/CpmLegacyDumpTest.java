package com.elfmcys.ysm.format.parser;

import cc.sirrus.ysmlib.YsmRuntime;
import cc.sirrus.ysmlib.cpm.CpmDocument;
import cc.sirrus.ysmlib.scene.ByteData;
import cc.sirrus.ysmlib.scene.io.ReadLimits;
import com.elfmcys.ysm.proto.mixel.asset.model.data.Cubes;
import java.nio.file.*;
import java.util.*;
import us.hebi.quickbuf.ProtoSource;
import org.junit.jupiter.api.Test;

class CpmLegacyDumpTest {
  @Test void dumpMarisa() throws Exception {
    Path dir=Path.of("C:/Users/qwe17/AppData/Roaming/PrismLauncher/instances/YSM-JVM-Test-1.20.1/minecraft/ysm/custom/CPM-Collection/Legacy-Models/10-Kirisame Marisa");
    var files=new LinkedHashMap<String,ByteData>();
    try(var paths=Files.walk(dir)) { for(var p:paths.filter(Files::isRegularFile).toList()) files.put(dir.relativize(p).toString().replace('\\','/'),new ByteData(Files.readAllBytes(p))); }
    CpmDocument.Legacy doc=(CpmDocument.Legacy)YsmRuntime.cpm().read(files,ReadLimits.DEFAULT);
    System.out.println("DOC bones="+doc.bones().size());
    for(int i=0;i<Math.min(30,doc.bones().size());i++){var b=doc.bones().get(i);System.out.println("D "+i+" "+b.id()+" parent="+b.parent()+" pi="+b.parentIndex()+" pos="+b.source().fields().get("position"));}
    var g=CpmYsmModelAdapter.build(doc).model(); var cubes=Cubes.parseFrom(ProtoSource.newInstance(g.cubes()));
    int off=0; for(var b:g.bones()) { if(Set.of("Head","UpperBody","head_cpm_dummy","body_cpm_dummy","hairBraid","braid_cpm_dummy").contains(b.name())) {System.out.println("G "+b.name()+" parent="+b.parent().orElse("-")+" piv="+b.pivot()+" cubes="+b.cubeCount().orElse(0)); for(int i=0;i<b.cubeCount().orElse(0);i++){var x=cubes.cubesLegacy().get(off+i);var a=x.pos().toFloatArray();float miny=Float.POSITIVE_INFINITY,maxy=Float.NEGATIVE_INFINITY;for(int j=1;j<a.length;j+=3){miny=Math.min(miny,a[j]);maxy=Math.max(maxy,a[j]);}System.out.println("  cube y="+miny+".."+maxy+" pos="+Arrays.toString(a));}} off+=b.cubeCount().orElse(0); }
  }
}
