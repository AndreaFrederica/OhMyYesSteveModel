package cc.sirrus.ysmlib.scene.bvh;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.io.*;
import java.nio.charset.*;
import java.util.*;

/** Iterative hierarchy parser with bounded text, node, channel and motion allocations. */
public final class BvhReader {
  public BvhDocument read(ByteData source,ReadLimits limits) throws AssetFormatException { return new Reader(source,limits).read(); }
  private static final class Node {
    String name;int parent,channelOffset;Vec3 offset;List<BvhDocument.Channel> channels=List.of();boolean end,hasChannels;
  }
  private static final class Reader {
    final ByteData source;final ReadLimits limits;final String text;int pos,elements,channelCount;String pending;
    Reader(ByteData source,ReadLimits limits) throws AssetFormatException {
      this.source=source;this.limits=limits;
      if(source.size()>limits.maxBytes()) throw new AssetFormatException("BVH source exceeds byte budget");
      try { text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(source.view()).toString(); }
      catch(CharacterCodingException e) { throw new AssetFormatException("BVH text is not valid UTF-8",e); }
    }
    AssetFormatException error(String message) { return new AssetFormatException(message+" at BVH character "+pos); }
    void budget(long n) throws AssetFormatException { if(n<0 || n>limits.maxElements()-elements) throw error("BVH element budget exceeded");elements+=(int)n; }
    String token() throws AssetFormatException {
      if(pending!=null) { String t=pending;pending=null;return t; }
      while(pos<text.length() && (Character.isWhitespace(text.charAt(pos)) || pos==0 && text.charAt(pos)=='\ufeff')) pos++;
      if(pos==text.length()) return null;
      int start=pos++;char first=text.charAt(start);
      if(first!='{' && first!='}') while(pos<text.length()) {
        char c=text.charAt(pos);if(Character.isWhitespace(c) || c=='{' || c=='}') break;pos++;
      }
      if(pos-start>limits.maxStringBytes()) throw error("BVH token exceeds string budget");return text.substring(start,pos);
    }
    void expect(String expected) throws AssetFormatException { if(!expected.equals(token())) throw error("Expected "+expected); }
    void label(String expected) throws AssetFormatException {
      String t=token();
      if(expected.equals(t)) { t=token();if(t==null || !t.startsWith(":")) throw error("Expected colon after "+expected);pending=t.length()==1?null:t.substring(1); }
      else if(t!=null && t.startsWith(expected+":")) pending=t.length()==expected.length()+1?null:t.substring(expected.length()+1);
      else throw error("Expected "+expected);
    }
    int integer() throws AssetFormatException {
      try { String t=token();if(t==null || !t.matches("[0-9]+")) throw new NumberFormatException();return Integer.parseInt(t); }
      catch(NumberFormatException e) { throw error("Expected nonnegative integer"); }
    }
    double number() throws AssetFormatException {
      try { String t=token();if(t==null) throw new NumberFormatException();double n=Double.parseDouble(t);if(!Double.isFinite(n)) throw new NumberFormatException();return n; }
      catch(NumberFormatException e) { throw error("Expected finite number"); }
    }
    float value() throws AssetFormatException { float v=(float)number();if(!Float.isFinite(v)) throw error("BVH value exceeds float range");return v; }
    BvhDocument read() throws AssetFormatException {
      expect("HIERARCHY");var nodes=new ArrayList<Node>();var stack=new ArrayDeque<Integer>();boolean motion=false;
      while(!motion) {
        String t=token();if(t==null) throw error("Missing MOTION section");
        switch(t) {
          case "ROOT","JOINT","End" -> {
            if(t.equals("ROOT")?!stack.isEmpty():stack.isEmpty()) throw error("Invalid joint nesting");
            if(!stack.isEmpty()) { var parent=nodes.get(stack.peek());if(parent.offset==null || !parent.hasChannels || parent.end) throw error("Incomplete parent joint"); }
            budget(1);var node=new Node();node.parent=stack.isEmpty()?-1:stack.peek();node.end=t.equals("End");
            if(node.end) { expect("Site");node.name="End Site";node.hasChannels=true; }
            else { node.name=token();if(node.name==null || Set.of("{","}",":").contains(node.name)) throw error("Missing joint name"); }
            node.channelOffset=channelCount;expect("{");nodes.add(node);stack.push(nodes.size()-1);
          }
          case "OFFSET" -> {
            if(stack.isEmpty()) throw error("OFFSET outside joint");var node=nodes.get(stack.peek());
            if(node.offset!=null || node.hasChannels && !node.end) throw error("Duplicate or misplaced OFFSET");node.offset=new Vec3(value(),value(),value());
          }
          case "CHANNELS" -> {
            if(stack.isEmpty()) throw error("CHANNELS outside joint");var node=nodes.get(stack.peek());
            if(node.hasChannels || node.offset==null) throw error("Duplicate or misplaced CHANNELS");
            int count=integer();if(count>6) throw error("Too many BVH channels");budget(count);var channels=new ArrayList<BvhDocument.Channel>();
            for(int i=0;i<count;i++) {
              BvhDocument.Channel c;try { c=BvhDocument.Channel.valueOf(Objects.requireNonNull(token())); }
              catch(IllegalArgumentException|NullPointerException e) { throw error("Unknown BVH channel"); }
              if(channels.contains(c)) throw error("Duplicate BVH channel");channels.add(c);
            }
            node.channels=channels;node.hasChannels=true;channelCount+=count;
          }
          case "}" -> {
            if(stack.isEmpty()) throw error("Unmatched hierarchy close");var node=nodes.get(stack.pop());
            if(node.offset==null || !node.hasChannels) throw error("Incomplete BVH joint");
          }
          case "MOTION" -> { if(!stack.isEmpty() || nodes.isEmpty()) throw error("Incomplete hierarchy");motion=true; }
          default -> throw error("Unexpected hierarchy token "+t);
        }
      }
      label("Frames");int frames=integer();expect("Frame");label("Time");double dt=number();
      if(dt<=0) throw error("Nonpositive BVH frame interval");long count=(long)frames*channelCount;budget(count);
      if(count>limits.maxBytes()/Float.BYTES || frames>limits.maxElements()) throw error("BVH decoded motion budget exceeded");
      float[] samples=new float[(int)count];for(int i=0;i<samples.length;i++) samples[i]=value();
      if(token()!=null) throw error("Trailing BVH motion values");
      return new BvhDocument(nodes.stream().map(n->new BvhDocument.Joint(n.name,n.parent,n.offset,n.channels,n.channelOffset,n.end)).toList(),
          frames,dt,channelCount,new FloatData(samples),source);
    }
  }
}
