package cc.sirrus.ysmlib.scene.mmd;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static cc.sirrus.ysmlib.scene.mmd.VmdDocument.*;

/** VMD 0001/0002 reader. Optional sections may end cleanly; partial records never pass. */
public final class VmdReader {
  public VmdDocument read(ByteData source,ReadLimits limits) throws AssetFormatException {
    var in=new BinaryInput(source,limits);
    String signature=in.fixed(30,StandardCharsets.US_ASCII);
    int nameSize=switch(signature) { case "Vocaloid Motion Data 0002" -> 20;case "Vocaloid Motion Data file" -> 10;
      default -> throw in.error("Unsupported VMD signature"); };
    String modelName=in.fixed(nameSize,BinaryInput.MS932);
    var bones=new ArrayList<BoneKey>();var morphs=new ArrayList<MorphKey>();var cameras=new ArrayList<CameraKey>();
    var lights=new ArrayList<LightKey>();var shadows=new ArrayList<ShadowKey>();var properties=new ArrayList<PropertyKey>();
    int sections=0;
    for(int section=0;section<6 && in.remaining()>0;section++) {
      int n=in.count(new int[]{111,23,61,28,9,9}[section]);sections++;
      for(int i=0;i<n;i++) switch(section) {
        case 0 -> bones.add(new BoneKey(in.fixed(15,BinaryInput.MS932),in.u32(),in.vec3(),in.floats(4),in.bytes(64)));
        case 1 -> morphs.add(new MorphKey(in.fixed(15,BinaryInput.MS932),in.u32(),in.f32()));
        case 2 -> { long frame=in.u32();float distance=in.f32();var target=in.vec3();var euler=in.vec3();var interpolation=in.bytes(24);
          long fov=in.u32();int orthographic=in.u8();if(orthographic>1) throw in.error("Invalid camera projection");
          cameras.add(new CameraKey(frame,distance,target,euler,interpolation,fov,orthographic==0)); }
        case 3 -> lights.add(new LightKey(in.u32(),in.vec3(),in.vec3()));
        case 4 -> { long frame=in.u32();int mode=in.u8();if(mode>2) throw in.error("Invalid shadow mode");shadows.add(new ShadowKey(frame,mode,in.f32())); }
        case 5 -> { long frame=in.u32();boolean visible=flag(in);int count=in.count(21);var ik=new ArrayList<IkSwitch>();
          for(int k=0;k<count;k++) ik.add(new IkSwitch(in.fixed(20,BinaryInput.MS932),flag(in)));
          properties.add(new PropertyKey(frame,visible,ik)); }
        default -> throw new AssertionError();
      }
    }
    if(sections==0) throw in.error("VMD is missing the bone count");
    if(in.remaining()!=0) throw in.error("Unknown VMD trailing data");
    return new VmdDocument(signature,modelName,bones,morphs,cameras,lights,shadows,properties,sections,source);
  }
  private static boolean flag(BinaryInput in) throws AssetFormatException { int v=in.u8();if(v>1) throw in.error("Invalid boolean flag");return v!=0; }
}
