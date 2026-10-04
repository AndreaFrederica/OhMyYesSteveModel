package cc.sirrus.ysmlib.scene.mmd;

import cc.sirrus.ysmlib.scene.*;
import java.util.*;
import static cc.sirrus.ysmlib.scene.mmd.PmxDocument.*;

/** Source-order morph evaluation with iterative group expansion and explicit PMX 2.1 flip/impulse semantics. */
public final class MmdMorphEvaluator {
  private record Event(int morph,float weight) {}
  private final PmxDocument source;
  private final int maxEvents;
  public MmdMorphEvaluator(PmxDocument source) { this(source,4_000_000); }
  public MmdMorphEvaluator(PmxDocument source,int maxEvents) {
    this.source=Objects.requireNonNull(source);if(maxEvents<1) throw new IllegalArgumentException("Invalid morph evaluation budget");this.maxEvents=maxEvents;
  }
  public MmdMorphState evaluate(FloatData input) {
    if(input.size()!=source.morphs().size()) throw new IllegalArgumentException("Morph input count mismatch");
    float[] weights=input.copy();int visited=0;
    // Flip channels select a child at a discrete threshold and set its weight to the authored offset.
    // Resolve these before deforming leaves, including flip channels controlled through groups.
    var pending=new ArrayDeque<Event>();
    for(int root=0;root<weights.length;root++) {
      pending.push(new Event(root,weights[root]));
      while(!pending.isEmpty()) {
        var event=pending.pop();if(++visited>maxEvents) throw new IllegalArgumentException("Morph expansion budget exceeded");
        if(!Float.isFinite(event.weight)) throw new IllegalArgumentException("Non-finite morph expansion");
        var morph=source.morphs().get(event.morph);
        if(event.weight==0) continue;
        if(morph.type()==0) {
          for(int i=morph.offsets().size()-1;i>=0;i--) { var child=(GroupOffset)morph.offsets().get(i);pending.push(new Event(child.morph(),event.weight*child.weight())); }
        } else if(morph.type()==9 && event.weight>0 && !morph.offsets().isEmpty()) {
          int n=morph.offsets().size();int selected=(int)Math.max(0,Math.min(n-1,Math.floor((n+1d)*event.weight)-1));
          var child=(GroupOffset)morph.offsets().get(selected);weights[child.morph()]=child.weight();
          int childType=source.morphs().get(child.morph()).type();
          if(childType==0 || childType==9) pending.push(new Event(child.morph(),child.weight()));
        }
      }
    }
    var translations=new Vec3[source.bones().size()];Arrays.fill(translations,Vec3.ZERO);
    var rotations=new Rotation[translations.length];Arrays.fill(rotations,Rotation.IDENTITY);
    var multiply=new float[source.materials().size()][28];var add=new float[multiply.length][28];for(var m:multiply) Arrays.fill(m,1);
    float[] effective=new float[weights.length];var impulses=new ArrayList<MmdMorphState.Impulse>();
    for(int root=0;root<weights.length;root++) {
      if(weights[root]==0) continue;pending.push(new Event(root,weights[root]));
      while(!pending.isEmpty()) {
        var event=pending.pop();if(++visited>maxEvents) throw new IllegalArgumentException("Morph expansion budget exceeded");
        if(!Float.isFinite(event.weight)) throw new IllegalArgumentException("Non-finite morph expansion");
        if(event.weight==0) continue;var morph=source.morphs().get(event.morph);float weight=event.weight;
        if(morph.type()==0) {
          for(int i=morph.offsets().size()-1;i>=0;i--) {
            var child=(GroupOffset)morph.offsets().get(i);
            if(source.morphs().get(child.morph()).type()!=9) pending.push(new Event(child.morph(),weight*child.weight()));
          }
        } else if(morph.type()!=9) {
          effective[event.morph]+=weight;
          for(var offset:morph.offsets()) {
            if(offset instanceof BoneOffset bone) {
              int i=bone.bone();translations[i]=translations[i].add(bone.translation().multiply(weight));var q=bone.rotation();
              rotations[i]=Rotation.slerp(rotations[i],Rotation.normalized(q.get(0),q.get(1),q.get(2),q.get(3)),weight);
            } else if(offset instanceof MaterialOffset material) {
              float[] values=materialValues(material);int begin=material.material()==-1?0:material.material(),end=material.material()==-1?multiply.length:begin+1;
              for(int i=begin;i<end;i++) for(int k=0;k<28;k++) {
                if(material.operation()==0) multiply[i][k]*=1+(values[k]-1)*weight;else add[i][k]+=values[k]*weight;
              }
            } else if(offset instanceof ImpulseOffset impulse) {
              boolean reset=impulse.velocity().lengthSquared()==0 && impulse.torque().lengthSquared()==0;
              impulses.add(new MmdMorphState.Impulse(impulse.rigidBody(),impulse.local(),impulse.velocity().multiply(weight),impulse.torque().multiply(weight),reset));
            }
          }
        }
      }
    }
    var materials=new ArrayList<MmdMorphState.Material>();
    for(int i=0;i<source.materials().size();i++) {
      var m=source.materials().get(i);float[] base=new float[28];Arrays.fill(base,1);put(base,0,m.diffuse());put(base,4,m.specular());base[7]=m.shininess();put(base,8,m.ambient());put(base,11,m.edgeColor());base[15]=m.edgeSize();
      for(int k=0;k<16;k++) base[k]=base[k]*multiply[i][k]+add[i][k];
      materials.add(new MmdMorphState.Material(slice(base,0,4),vec(base,4),base[7],vec(base,8),slice(base,11,4),base[15],
          slice(multiply[i],16,4),slice(add[i],16,4),slice(multiply[i],20,4),slice(add[i],20,4),slice(multiply[i],24,4),slice(add[i],24,4)));
    }
    return new MmdMorphState(new FloatData(effective),Arrays.asList(translations),Arrays.asList(rotations),materials,impulses);
  }
  private static float[] materialValues(MaterialOffset m) {
    float[] out=new float[28];put(out,0,m.diffuse());put(out,4,m.specular());out[7]=m.shininess();put(out,8,m.ambient());put(out,11,m.edgeColor());out[15]=m.edgeSize();
    put(out,16,m.textureTint());put(out,20,m.sphereTint());put(out,24,m.toonTint());return out;
  }
  private static FloatData slice(float[] source,int start,int count) { return new FloatData(Arrays.copyOfRange(source,start,start+count)); }
  private static Vec3 vec(float[] source,int start) { return new Vec3(source[start],source[start+1],source[start+2]); }
  private static void put(float[] out,int start,Vec3 value) { out[start]=value.x();out[start+1]=value.y();out[start+2]=value.z(); }
  private static void put(float[] out,int start,FloatData value) { for(int i=0;i<value.size();i++) out[start+i]=value.get(i); }
}
