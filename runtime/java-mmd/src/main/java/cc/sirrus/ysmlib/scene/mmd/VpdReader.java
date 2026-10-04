package cc.sirrus.ysmlib.scene.mmd;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;

/** Shift-JIS VPD and the MMM morph-pose extension. UTF-8 is accepted only with its explicit BOM. */
public final class VpdReader {
  public VpdDocument read(ByteData source,ReadLimits limits) throws AssetFormatException {
    if(source.size()>limits.maxBytes()) throw new AssetFormatException("VPD exceeds byte limit");
    byte[] bytes=source.copy();int offset=0;Charset encoding=BinaryInput.MS932;
    if(bytes.length>=3 && bytes[0]==(byte)0xef && bytes[1]==(byte)0xbb && bytes[2]==(byte)0xbf) { offset=3;encoding=StandardCharsets.UTF_8; }
    String text;
    try { text=encoding.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes,offset,bytes.length-offset)).toString(); }
    catch(CharacterCodingException e) { throw new AssetFormatException("Malformed VPD text",e); }
    var parser=new Parser(text.replaceAll("(?m)//[^\\r\\n]*", ""),limits);
    if(!parser.line().strip().equals("Vocaloid Pose Data file")) throw parser.error("Invalid VPD signature");
    String parent=parser.until(';').strip();int count=parser.integer(parser.until(';').strip());
    if(count>limits.maxElements()) throw parser.error("VPD bone count exceeds limit");
    var bones=new ArrayList<VpdDocument.BonePose>();var morphs=new ArrayList<VpdDocument.MorphPose>();
    var boneIndices=new HashSet<Integer>();var morphIndices=new HashSet<Integer>();
    while(!parser.end()) {
      String tag=parser.until('{').strip();String name=parser.line().strip();
      if(name.isEmpty() || name.length()>limits.maxStringBytes()) throw parser.error("Invalid VPD binding name");
      if(tag.startsWith("Bone")) {
        if(!morphs.isEmpty()) throw parser.error("VPD bones must precede morph extension");
        int index=parser.integer(tag.substring(4));if(!boneIndices.add(index)) throw parser.error("Duplicate VPD bone index");
        var t=parser.vector(3);var r=parser.vector(4);parser.close();
        bones.add(new VpdDocument.BonePose(index,name,new Vec3(t.get(0),t.get(1),t.get(2)),r));
      } else if(tag.startsWith("Morph")) {
        int index=parser.integer(tag.substring(5));if(!morphIndices.add(index)) throw parser.error("Duplicate VPD morph index");
        float value=parser.vector(1).get(0);parser.close();morphs.add(new VpdDocument.MorphPose(index,name,value));
      } else throw parser.error("Unknown VPD block");
      if(bones.size()+morphs.size()>limits.maxElements()) throw parser.error("VPD entries exceed limit");
    }
    if(bones.size()!=count) throw parser.error("VPD bone count does not match body");
    return new VpdDocument(parent,bones,morphs,source);
  }
  public AnimationClip animation(VpdDocument document) {
    var tracks=new ArrayList<AnimationClip.Track>();
    for(var b:document.bones()) {
      tracks.add(track(AnimationClip.Property.TRANSLATION,b.name(),new FloatData(b.translation().x(),b.translation().y(),b.translation().z()),false));
      tracks.add(track(AnimationClip.Property.ROTATION,b.name(),b.rotation(),true));
    }
    for(var m:document.morphs()) tracks.add(track(AnimationClip.Property.MORPH_WEIGHTS,m.name(),new FloatData(m.weight()),false));
    return new AnimationClip(document.parentFile(),tracks,30);
  }
  private AnimationClip.Track track(AnimationClip.Property property,String name,FloatData value,boolean quaternion) {
    return new AnimationClip.Track(property,-1,name,"",new AnimationCurve(new double[]{0},value,value.size(),quaternion,AnimationCurve.Interpolation.STEP,FloatData.EMPTY,FloatData.EMPTY,FloatData.EMPTY));
  }
  private static final class Parser {
    final String text;final ReadLimits limits;int position;
    Parser(String text,ReadLimits limits) { this.text=text;this.limits=limits; }
    AssetFormatException error(String message) { return new AssetFormatException(message+" at character "+position); }
    void whitespace() { while(position<text.length() && Character.isWhitespace(text.charAt(position))) position++; }
    boolean end() { whitespace();return position==text.length(); }
    String line() throws AssetFormatException {
      int start=position;while(position<text.length() && text.charAt(position)!='\n' && text.charAt(position)!='\r') position++;
      if(position==text.length()) throw error("Missing VPD line terminator");
      String result=text.substring(start,position);if(text.charAt(position++)=='\r' && position<text.length() && text.charAt(position)=='\n') position++;
      return result;
    }
    String until(char delimiter) throws AssetFormatException {
      int start=position;while(position<text.length() && text.charAt(position)!=delimiter) position++;
      if(position==text.length()) throw error("Missing VPD delimiter "+delimiter);
      if(position-start>limits.maxStringBytes()) throw error("VPD token exceeds limit");
      return text.substring(start,position++);
    }
    int integer(String value) throws AssetFormatException {
      try { int result=Integer.parseInt(value.strip());if(result<0) throw new NumberFormatException();return result; }
      catch(NumberFormatException e) { throw error("Invalid nonnegative VPD integer"); }
    }
    FloatData vector(int size) throws AssetFormatException {
      String[] parts=until(';').strip().split(",",-1);if(parts.length!=size) throw error("Invalid VPD vector length");
      float[] result=new float[size];
      try { for(int i=0;i<size;i++) { result[i]=Float.parseFloat(parts[i].strip());if(!Float.isFinite(result[i])) throw new NumberFormatException(); } }
      catch(NumberFormatException e) { throw error("Invalid VPD number"); }
      return new FloatData(result);
    }
    void close() throws AssetFormatException { whitespace();if(position>=text.length() || text.charAt(position++)!='}') throw error("Missing VPD block end"); }
  }
}
