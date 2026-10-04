package cc.sirrus.ysmlib;

import cc.sirrus.ysmlib.scene.DeformationProvider;
import cc.sirrus.ysmlib.scene.java.JavaDeformationProvider;
import cc.sirrus.ysmlib.scene.natives.NativeDeformationProvider;

/** Deformation selection is independent of the stateful physics solver. */
final class DeformationServices {
  final DeformationProvider provider;
  final String fallbackReason;
  private DeformationServices(DeformationProvider provider,String reason){this.provider=provider;fallbackReason=reason;}
  static DeformationServices configured(){
    if(Boolean.getBoolean("ysm.runtime.javaOnly"))return new DeformationServices(new JavaDeformationProvider(),"javaOnly");
    try {
      var library=NativeLibraries.find("skinning");
      return library==null?new DeformationServices(new JavaDeformationProvider(),"native library not installed")
          :new DeformationServices(new NativeDeformationProvider(library),"");
    }catch(LinkageError|SecurityException|IllegalArgumentException|IllegalStateException rejected){
      System.getLogger(DeformationServices.class.getName()).log(System.Logger.Level.WARNING,"Optional skinning accelerator unavailable; using Java",rejected);
      return new DeformationServices(new JavaDeformationProvider(),rejected.getClass().getSimpleName());
    }
  }
}
