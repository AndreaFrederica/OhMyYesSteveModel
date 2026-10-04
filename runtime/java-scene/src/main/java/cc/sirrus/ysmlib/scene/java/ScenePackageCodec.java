package cc.sirrus.ysmlib.scene.java;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.io.*;
import com.google.gson.*;
import com.google.gson.stream.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;
import java.util.zip.*;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;

/** Portable closed scene packages, ZIP32 plus a versioned UTF-8 manifest. No temporary files. */
public final class ScenePackageCodec {
  public ScenePackage read(ByteData encoded,ReadLimits limits) throws IOException {
    if(encoded.size()>limits.maxBytes()) throw new AssetFormatException("Scene package byte limit");
    try {
      validateDirectory(encoded,limits);
      var files=new TreeMap<String,ByteData>();long total=0;
      try(var channel=new SeekableInMemoryByteChannel(encoded.copy());
          var zip=org.apache.commons.compress.archivers.zip.ZipFile.builder().setSeekableByteChannel(channel).setUseUnicodeExtraFields(false).get()) {
        var entries=zip.getEntries();
        while(entries.hasMoreElements()) {
          var entry=entries.nextElement();
          if(!zip.canReadEntryData(entry)) throw new AssetFormatException("Unsupported package ZIP entry");
          var output=new ByteArrayOutputStream();var crc=new CRC32();
          try(var input=zip.getInputStream(entry)) {
            byte[] block=new byte[8192];int count;
            while((count=input.read(block))!=-1) {
              if(count==0) throw new AssetFormatException("Package decoder made no progress");
              total+=count;if(total>limits.maxBytes() || output.size()+(long)count>entry.getSize())
                throw new AssetFormatException("Package expanded byte limit");
              output.write(block,0,count);crc.update(block,0,count);
            }
          }
          if(output.size()!=entry.getSize() || crc.getValue()!=entry.getCrc()) throw new AssetFormatException("Package size/CRC mismatch");
          if(!entry.isDirectory()) files.put(entry.getName(),new ByteData(output.toByteArray()));
        }
      }
      var manifest=files.remove(ScenePackage.MANIFEST);
      if(manifest==null) throw new AssetFormatException("Missing scene.json");
      JsonObject root=parse(manifest,limits);fields(root,"schema","version","model","settings","animations","relocations");
      if(!string(root,"schema").equals(ScenePackage.SCHEMA) || !string(root,"version").equals(ScenePackage.VERSION))
        throw new AssetFormatException("Unsupported scene package schema/version");
      var animations=new ArrayList<ScenePackage.Source>();
      for(var item:array(root,"animations")) animations.add(source(item.getAsJsonObject()));
      var relocations=new ArrayList<ScenePackage.Relocation>();
      for(var item:array(root,"relocations")) {
        var mapping=item.getAsJsonObject();fields(mapping,"owner","reference","target");
        relocations.add(new ScenePackage.Relocation(string(mapping,"owner"),string(mapping,"reference"),string(mapping,"target")));
      }
      var settings=root.getAsJsonObject("settings");fields(settings,"metersPerUnit","scene","fbxSkinSpace");
      if(!settings.get("metersPerUnit").isJsonPrimitive() || !settings.get("metersPerUnit").getAsJsonPrimitive().isNumber()
          || !settings.get("scene").isJsonPrimitive() || !settings.get("scene").getAsJsonPrimitive().isNumber())
        throw new AssetFormatException("Scene scale and index must be numbers");
      var config=new ScenePackage.Settings(settings.get("metersPerUnit").getAsDouble(),settings.get("scene").getAsBigDecimal().intValueExact(),
          cc.sirrus.ysmlib.scene.fbx.FbxEvaluation.SkinSpace.valueOf(string(settings,"fbxSkinSpace")));
      return new ScenePackage(source(root.getAsJsonObject("model")),config,animations,relocations,files);
    } catch(RuntimeException failure) {
      throw new AssetFormatException("Invalid scene package: "+failure.getMessage(),failure);
    }
  }

  public ByteData write(ScenePackage source,ReadLimits limits) throws IOException {
    var root=new JsonObject();root.addProperty("schema",ScenePackage.SCHEMA);root.addProperty("version",ScenePackage.VERSION);
    root.add("model",json(source.model()));var animations=new JsonArray();
    var settings=new JsonObject();settings.addProperty("metersPerUnit",source.settings().metersPerUnit());settings.addProperty("scene",source.settings().scene());
    settings.addProperty("fbxSkinSpace",source.settings().fbxSkinSpace().name());root.add("settings",settings);
    source.animations().forEach(s->animations.add(json(s)));root.add("animations",animations);
    var relocations=new JsonArray();
    source.relocations().stream().sorted(Comparator.comparing(ScenePackage.Relocation::owner).thenComparing(ScenePackage.Relocation::reference)).forEach(r->{
      var value=new JsonObject();value.addProperty("owner",r.owner());value.addProperty("reference",r.reference());value.addProperty("target",r.target());relocations.add(value);
    });root.add("relocations",relocations);
    var files=new TreeMap<>(source.files());files.put(ScenePackage.MANIFEST,new ByteData(root.toString().getBytes(StandardCharsets.UTF_8)));
    if(files.size()>=65535 || files.size()>limits.maxElements()) throw new AssetFormatException("Package entry count limit");
    long bytes=0;for(var file:files.entrySet()) {
      checkString(file.getKey(),limits);bytes+=file.getValue().size();if(bytes>limits.maxBytes()) throw new AssetFormatException("Package expanded byte limit");
    }
    parse(files.get(ScenePackage.MANIFEST),limits);
    var output=new LimitedOutput(limits.maxBytes());
    try(var zip=new ZipOutputStream(output,StandardCharsets.UTF_8)) {
      for(var file:files.entrySet()) {
        var entry=new ZipEntry(file.getKey());entry.setTimeLocal(java.time.LocalDateTime.of(1980,1,1,0,0));
        zip.putNextEntry(entry);zip.write(file.getValue().copy());zip.closeEntry();
      }
    } catch(IllegalArgumentException failure) { throw new AssetFormatException("Cannot encode scene package",failure); }
    return new ByteData(output.toByteArray());
  }

  private static ScenePackage.Source source(JsonObject object) throws AssetFormatException {
    fields(object,"id","path","format");
    String token=string(object,"format");var format=ScenePackage.Format.valueOf(token.toUpperCase(Locale.ROOT));
    if(!token.equals(format.name().toLowerCase(Locale.ROOT))) throw new AssetFormatException("Noncanonical source format");
    return new ScenePackage.Source(string(object,"id"),string(object,"path"),format);
  }
  private static JsonObject json(ScenePackage.Source source) {
    var value=new JsonObject();value.addProperty("id",source.id());value.addProperty("path",source.path());value.addProperty("format",source.format().name().toLowerCase(Locale.ROOT));return value;
  }
  private static void fields(JsonObject object,String... names) throws AssetFormatException {
    if(object==null || !object.keySet().equals(Set.of(names))) throw new AssetFormatException("Unknown or missing scene manifest fields");
  }
  private static String string(JsonObject object,String key) throws AssetFormatException {
    var value=object.get(key);
    if(value==null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new AssetFormatException("Expected string: "+key);
    return value.getAsString();
  }
  private static JsonArray array(JsonObject object,String key) throws AssetFormatException {
    var value=object.get(key);if(value==null || !value.isJsonArray()) throw new AssetFormatException("Expected array: "+key);return value.getAsJsonArray();
  }
  private static JsonObject parse(ByteData data,ReadLimits limits) throws IOException {
    String text=utf8(data.copy());int depth=0,count=0;var objects=new ArrayDeque<Set<String>>();
    try(var reader=new JsonReader(new StringReader(text))) {
      reader.setLenient(false);
      while(reader.peek()!=JsonToken.END_DOCUMENT) {
        if(++count>limits.maxElements()) throw new AssetFormatException("Package manifest element limit");
        switch(reader.peek()) {
          case BEGIN_OBJECT -> { reader.beginObject();objects.push(new HashSet<>());if(++depth>32) throw new AssetFormatException("Package JSON nesting limit"); }
          case END_OBJECT -> { reader.endObject();objects.pop();depth--; }
          case BEGIN_ARRAY -> { reader.beginArray();if(++depth>32) throw new AssetFormatException("Package JSON nesting limit"); }
          case END_ARRAY -> { reader.endArray();depth--; }
          case NAME -> { String name=reader.nextName();checkString(name,limits);if(!objects.peek().add(name)) throw new AssetFormatException("Duplicate package JSON key"); }
          case STRING,NUMBER -> checkString(reader.nextString(),limits);
          case BOOLEAN -> reader.nextBoolean();case NULL -> reader.nextNull();
          default -> throw new AssetFormatException("Invalid package manifest JSON");
        }
      }
    }
    return JsonParser.parseString(text).getAsJsonObject();
  }
  private static void checkString(String value,ReadLimits limits) throws AssetFormatException {
    if(value.getBytes(StandardCharsets.UTF_8).length>limits.maxStringBytes()) throw new AssetFormatException("Package string limit");
    for(int i=0;i<value.length();i++) {
      char c=value.charAt(i);
      if(Character.isHighSurrogate(c)) {
        if(++i==value.length() || !Character.isLowSurrogate(value.charAt(i))) throw new AssetFormatException("Unpaired Unicode surrogate");
      } else if(Character.isLowSurrogate(c)) throw new AssetFormatException("Unpaired Unicode surrogate");
    }
  }
  private static String utf8(byte[] data) throws CharacterCodingException {
    return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString();
  }

  /** Check counts, sizes, local/central agreement and disjoint entry extents before decoder allocation. */
  private static void validateDirectory(ByteData data,ReadLimits limits) throws IOException {
    var b=data.view().order(ByteOrder.LITTLE_ENDIAN);int end=-1;
    for(int i=b.limit()-22;i>=Math.max(0,b.limit()-65557);i--) if(b.getInt(i)==0x06054b50 && i+22+u16(b,i+20)==b.limit()) { end=i;break; }
    if(end<0 || u16(b,end+4)!=0 || u16(b,end+6)!=0 || u16(b,end+8)!=u16(b,end+10)) throw new AssetFormatException("Invalid package ZIP directory");
    int count=u16(b,end+10);long directory=u32(b,end+16),size=u32(b,end+12);
    if(count==0 || count==65535 || count>limits.maxElements() || directory+size!=end) throw new AssetFormatException("Package ZIP32 directory/count limit");
    int cursor=(int)directory;long total=0;var names=new HashSet<String>();var files=new HashSet<String>();var ranges=new TreeMap<Integer,Integer>();
    for(int n=0;n<count;n++) {
      if(cursor<0 || cursor+46L>end || b.getInt(cursor)!=0x02014b50) throw new AssetFormatException("Invalid package central entry");
      int flags=u16(b,cursor+8),method=u16(b,cursor+10),nameLength=u16(b,cursor+28),extra=u16(b,cursor+30),comment=u16(b,cursor+32);
      long packed=u32(b,cursor+20),unpacked=u32(b,cursor+24),local=u32(b,cursor+42);long next=cursor+46L+nameLength+extra+comment;
      if(next>end || nameLength==0 || nameLength>limits.maxStringBytes() || (flags&~(0x800|8|6))!=0 || method!=0&&method!=8
          || u16(b,cursor+34)!=0 || packed==0xffffffffL || unpacked==0xffffffffL || local+30>directory)
        throw new AssetFormatException("Unsupported or truncated package ZIP entry");
      byte[] nameBytes=new byte[nameLength];b.get(cursor+46,nameBytes);String name=utf8(nameBytes);
      boolean isDirectory=name.endsWith("/");String path=ScenePackage.checkedPath(isDirectory?name.substring(0,name.length()-1):name);
      if(!names.add(path) || isDirectory&&unpacked!=0) throw new AssetFormatException("Duplicate or invalid package entry");
      if(!isDirectory) files.add(path);
      total+=unpacked;if(total>limits.maxBytes()) throw new AssetFormatException("Package expanded byte limit");
      int l=(int)local;
      if(b.getInt(l)!=0x04034b50 || u16(b,l+6)!=flags || u16(b,l+8)!=method || u16(b,l+26)!=nameLength)
        throw new AssetFormatException("Package ZIP local header mismatch");
      long content=local+30+nameLength+u16(b,l+28),finish=content+packed;
      if(finish>directory) throw new AssetFormatException("Package entry crosses directory");
      for(int i=0;i<nameLength;i++) if(b.get(l+30+i)!=nameBytes[i]) throw new AssetFormatException("Package ZIP local name mismatch");
      if((flags&8)!=0) {
        if(finish+12>directory) throw new AssetFormatException("Truncated package ZIP descriptor");
        int d=(int)finish;if(b.getInt(d)==0x08074b50) d+=4;
        if(d+12L>directory || u32(b,d)!=u32(b,cursor+16) || u32(b,d+4)!=packed || u32(b,d+8)!=unpacked)
          throw new AssetFormatException("Package ZIP data descriptor mismatch");
        finish=d+12;
      } else if(u32(b,l+14)!=u32(b,cursor+16) || u32(b,l+18)!=packed || u32(b,l+22)!=unpacked)
        throw new AssetFormatException("Package ZIP local size/CRC mismatch");
      if(ranges.put(l,(int)finish)!=null) throw new AssetFormatException("Aliased package ZIP entry");
      cursor=(int)next;
    }
    if(cursor!=end) throw new AssetFormatException("Trailing package directory bytes");
    int next=0;for(var range:ranges.entrySet()) { if(range.getKey()!=next) throw new AssetFormatException("Overlapping or hidden package ZIP data");next=range.getValue(); }
    if(next!=directory) throw new AssetFormatException("Unreferenced package ZIP data");
    for(String path:names) for(int endPart=path.indexOf('/');endPart>=0;endPart=path.indexOf('/',endPart+1))
      if(files.contains(path.substring(0,endPart))) throw new AssetFormatException("Package file/directory conflict");
  }
  private static int u16(ByteBuffer b,int at) { return Short.toUnsignedInt(b.getShort(at)); }
  private static long u32(ByteBuffer b,int at) { return Integer.toUnsignedLong(b.getInt(at)); }
  private static final class LimitedOutput extends ByteArrayOutputStream {
    final int max;
    LimitedOutput(int max) { this.max=max; }
    @Override public synchronized void write(int value) { check(1);super.write(value); }
    @Override public synchronized void write(byte[] bytes,int offset,int length) { check(length);super.write(bytes,offset,length); }
    private void check(int length) { if(length>max-count) throw new IllegalArgumentException("Encoded package byte limit"); }
  }
}
