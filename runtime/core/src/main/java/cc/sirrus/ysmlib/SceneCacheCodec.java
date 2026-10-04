package cc.sirrus.ysmlib;

import cc.sirrus.ysmlib.scene.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Private, bounded cache wire. Only the statically reachable immutable scene schema can be constructed.
 * No Java serialization, class names from disk, private-field access, or executable objects. */
final class SceneCacheCodec {
  private record Shape(Class<?> type, Constructor<?> constructor, Method[] readers) {}
  private static final List<Shape> SHAPES;
  private static final Map<Class<?>,Integer> IDS=new HashMap<>();
  static final String SCHEMA;
  static {
    var types=new TreeMap<String,Class<?>>();
    for(var root:List.of(SceneDiskCache.Parsed.class,SceneDiskCache.Rest.class,SceneImage.class,
        SceneAsset.class,ScenePackage.class,SceneImageUsage.class,AnimationCurve.Interpolation.class,
        cc.sirrus.ysmlib.scene.io.ReadLimits.class)) collect(root,types,new HashSet<>());
    var shapes=new ArrayList<Shape>();var signature=new StringBuilder("scene-cache-wire-1");
    try {
      for(var type:types.values()) {
        signature.append('|').append(type.getName());
        Constructor<?> constructor=null;Method[] readers=new Method[0];
        if(type.isRecord()) {
          var fields=type.getRecordComponents();readers=new Method[fields.length];var parameters=new Class<?>[fields.length];
          for(int i=0;i<fields.length;i++) {parameters[i]=fields[i].getType();readers[i]=fields[i].getAccessor();signature.append(':').append(fields[i].getName()).append('=').append(fields[i].getGenericType().getTypeName());}
          constructor=type.getDeclaredConstructor(parameters);
        } else for(Object constant:type.getEnumConstants()) signature.append(':').append(constant);
        IDS.put(type,shapes.size());shapes.add(new Shape(type,constructor,readers));
      }
      SHAPES=List.copyOf(shapes);SCHEMA=HexFormat.of().formatHex(digest().digest(signature.toString().getBytes(StandardCharsets.UTF_8)));
    } catch(ReflectiveOperationException e) {throw new ExceptionInInitializerError(e);}
  }
  private static void collect(Type type,Map<String,Class<?>> out,Set<Type> visited) {
    if(!visited.add(type))return;
    if(type instanceof ParameterizedType p) {for(var t:p.getActualTypeArguments())collect(t,out,visited);collect(p.getRawType(),out,visited);return;}
    if(!(type instanceof Class<?> c) || !c.getName().startsWith("cc.sirrus.ysmlib."))return;
    if(c.isRecord() || c.isEnum())out.put(c.getName(),c);
    if(c.isRecord())for(var field:c.getRecordComponents())collect(field.getGenericType(),out,visited);
    for(var child:c.getDeclaredClasses())collect(child,out,visited);
    if(c.isSealed())for(var child:c.getPermittedSubclasses())collect(child,out,visited);
  }
  static MessageDigest digest() {try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException e){throw new AssertionError(e);}}
  static void write(OutputStream output,Object value,Map<String,ByteData> external) throws IOException {
    new Writer(new DataOutputStream(output),external).value(value,0);
  }
  static Object read(InputStream input,Map<String,ByteData> external,long maxBytes,int maxElements,int maxString) throws IOException {
    return new Reader(new DataInputStream(input),external,maxBytes,maxElements,maxString).value(0);
  }
  private static final int NIL=0,REF=1,STRING=2,INT=3,LONG=4,FLOAT=5,DOUBLE=6,BOOL=7,
      LIST=8,MAP=9,RECORD=10,ENUM=11,BYTES=12,FLOATS=13,INTS=14,DOUBLES=15,MATRIX=16,CURVE=17,EXTERNAL=18,SET=19;
  private static final class Writer {
    final DataOutputStream out;final IdentityHashMap<Object,Integer> refs=new IdentityHashMap<>();
    final IdentityHashMap<ByteData,String> external=new IdentityHashMap<>();
    Writer(DataOutputStream out,Map<String,ByteData> files) {this.out=out;new TreeMap<>(files).forEach((k,v)->external.putIfAbsent(v,k));}
    void text(String text) throws IOException {var bytes=text.getBytes(StandardCharsets.UTF_8);out.writeInt(bytes.length);out.write(bytes);}
    void value(Object v,int depth) throws IOException {
      if(depth>128)throw new IOException("Cache graph depth exceeded");
      if(v==null){out.writeByte(NIL);return;}
      if(v instanceof String s){out.writeByte(STRING);text(s);return;}
      if(v instanceof Integer n){out.writeByte(INT);out.writeInt(n);return;}
      if(v instanceof Long n){out.writeByte(LONG);out.writeLong(n);return;}
      if(v instanceof Float n){out.writeByte(FLOAT);out.writeFloat(n);return;}
      if(v instanceof Double n){out.writeByte(DOUBLE);out.writeDouble(n);return;}
      if(v instanceof Boolean n){out.writeByte(BOOL);out.writeBoolean(n);return;}
      if(v instanceof Enum<?> e){out.writeByte(ENUM);out.writeShort(id(e.getDeclaringClass()));out.writeInt(e.ordinal());return;}
      var ref=refs.get(v);if(ref!=null){out.writeByte(REF);out.writeInt(ref);return;}refs.put(v,refs.size());
      if(v instanceof ByteData data) {
        var key=external.get(data);if(key!=null){out.writeByte(EXTERNAL);text(key);}
        else {out.writeByte(BYTES);out.writeInt(data.size());var view=data.view();byte[] scratch=new byte[Math.min(65536,data.size())];while(view.hasRemaining()){int n=Math.min(view.remaining(),scratch.length);view.get(scratch,0,n);out.write(scratch,0,n);}}
      } else if(v instanceof FloatData data) {
        out.writeByte(FLOATS);out.writeInt(data.size());boolean compact=data.storageBytes()==data.size();out.writeBoolean(compact);
        byte[] scratch=new byte[(int)Math.min(65536,(long)data.size()*(compact?1:4))];var buffer=java.nio.ByteBuffer.wrap(scratch);
        for(int offset=0;offset<data.size();) {
          int n=Math.min(data.size()-offset,scratch.length/(compact?1:4));buffer.clear();
          for(int i=0;i<n;i++)if(compact)buffer.put((byte)Math.round(data.get(offset+i)*255));else buffer.putFloat(data.get(offset+i));
          out.write(scratch,0,buffer.position());offset+=n;
        }
      } else if(v instanceof IntData data){out.writeByte(INTS);out.writeInt(data.size());for(int i=0;i<data.size();i++)out.writeInt(data.get(i));}
      else if(v instanceof DoubleData data){out.writeByte(DOUBLES);out.writeInt(data.size());for(int i=0;i<data.size();i++)out.writeDouble(data.get(i));}
      else if(v instanceof Matrix4 data){out.writeByte(MATRIX);for(float n:data.copy())out.writeFloat(n);}
      else if(v instanceof AnimationCurve c) {
        out.writeByte(CURVE);out.writeInt(c.keys());for(int i=0;i<c.keys();i++)out.writeDouble(c.time(i));
        out.writeInt(c.components());out.writeBoolean(c.quaternion());value(c.interpolation(),depth+1);
        for(var item:List.of(c.values(),c.inTangents(),c.outTangents(),c.bezier(),c.heldSegments()))value(item,depth+1);
      } else if(v instanceof List<?> list){out.writeByte(LIST);out.writeInt(list.size());for(var item:list)value(item,depth+1);}
      else if(v instanceof Set<?> set){out.writeByte(SET);out.writeInt(set.size());for(var item:set)value(item,depth+1);}
      else if(v instanceof Map<?,?> map){out.writeByte(MAP);out.writeInt(map.size());for(var entry:map.entrySet()){value(entry.getKey(),depth+1);value(entry.getValue(),depth+1);}}
      else {
        var shape=SHAPES.get(id(v.getClass()));out.writeByte(RECORD);out.writeShort(id(v.getClass()));
        try {for(var reader:shape.readers)value(reader.invoke(v),depth+1);}catch(ReflectiveOperationException e){throw new IOException("Cannot encode scene record",e);}
      }
    }
    int id(Class<?> type) throws IOException {var id=IDS.get(type);if(id==null)throw new IOException("Unsupported cache type: "+type.getName());return id;}
  }
  private static final class Reader {
    final DataInputStream in;final Map<String,ByteData> external;final List<Object> refs=new ArrayList<>();
    final long maxBytes;final int maxElements,maxString;long allocated;int elements;
    Reader(DataInputStream in,Map<String,ByteData> external,long maxBytes,int maxElements,int maxString){this.in=in;this.external=external;this.maxBytes=maxBytes;this.maxElements=maxElements;this.maxString=maxString;}
    void allocate(long bytes) throws IOException {if(bytes<0 || (allocated+=bytes)>maxBytes)throw new IOException("Cache decoded allocation budget exceeded");}
    int count(int bytes) throws IOException {int n=in.readInt();if(n<0 || n>maxElements)throw new IOException("Cache collection budget exceeded");allocate((long)n*bytes);return n;}
    String text() throws IOException {int n=in.readInt();if(n<0 || n>maxString)throw new IOException("Cache string budget exceeded");allocate((long)n*3);byte[] b=new byte[n];in.readFully(b);return new String(b,StandardCharsets.UTF_8);}
    Shape shape() throws IOException {int id=in.readUnsignedShort();if(id>=SHAPES.size())throw new IOException("Unknown cache schema type");return SHAPES.get(id);}
    Object value(int depth) throws IOException {
      if(depth>128 || ++elements>maxElements)throw new IOException("Cache graph budget exceeded");
      int tag=in.readUnsignedByte();
      switch(tag) {
        case NIL:return null;case STRING:return text();case INT:return in.readInt();case LONG:return in.readLong();
        case FLOAT:return in.readFloat();case DOUBLE:return in.readDouble();case BOOL:return in.readBoolean();
        case REF:{int id=in.readInt();if(id<0 || id>=refs.size() || refs.get(id)==null)throw new IOException("Invalid cache reference");return refs.get(id);}
        case ENUM:{var shape=shape();if(!shape.type.isEnum())throw new IOException("Expected cache enum");var values=shape.type.getEnumConstants();int id=in.readInt();if(id<0 || id>=values.length)throw new IOException("Invalid cache enum");return values[id];}
      }
      allocate(64);int index=refs.size();refs.add(null);Object result;
      try {
        switch(tag) {
          case EXTERNAL:{String path=text();result=external.get(path);if(result==null)throw new IOException("Missing external cache source");break;}
          case BYTES:{int n=count(2);byte[] b=new byte[n];in.readFully(b);result=new ByteData(b);break;}
          case FLOATS:{int n=count(0);boolean compact=in.readBoolean();allocate((long)n*(compact?2:4));if(compact){byte[] b=new byte[n];in.readFully(b);result=FloatData.ownedNormalized8(b);}else{
            float[] b=new float[n];byte[] scratch=new byte[(int)Math.min(65536,(long)n*4)];var buffer=java.nio.ByteBuffer.wrap(scratch);
            for(int offset=0;offset<n;){int size=Math.min(n-offset,scratch.length/4);in.readFully(scratch,0,size*4);buffer.clear();for(int i=0;i<size;i++)b[offset+i]=buffer.getFloat();offset+=size;}result=FloatData.owned(b);}break;}
          case INTS:{int[] b=new int[count(8)];for(int i=0;i<b.length;i++)b[i]=in.readInt();result=new IntData(b);break;}
          case DOUBLES:{double[] b=new double[count(16)];for(int i=0;i<b.length;i++)b[i]=in.readDouble();result=new DoubleData(b);break;}
          case MATRIX:{allocate(128);float[] b=new float[16];for(int i=0;i<16;i++)b[i]=in.readFloat();result=new Matrix4(b);break;}
          case CURVE:{double[] times=new double[count(16)];for(int i=0;i<times.length;i++)times[i]=in.readDouble();int components=in.readInt();boolean quaternion=in.readBoolean();
            var interpolation=(AnimationCurve.Interpolation)value(depth+1);
            result=new AnimationCurve(times,(FloatData)value(depth+1),components,quaternion,interpolation,
                (FloatData)value(depth+1),(FloatData)value(depth+1),(FloatData)value(depth+1),(IntData)value(depth+1));break;}
          case LIST:{int n=count(16);var list=new ArrayList<>(n);for(int i=0;i<n;i++)list.add(value(depth+1));result=List.copyOf(list);break;}
          case SET:{int n=count(48);var set=new LinkedHashSet<>();for(int i=0;i<n;i++)if(!set.add(value(depth+1)))throw new IOException("Duplicate cache set entry");result=Collections.unmodifiableSet(set);break;}
          case MAP:{int n=count(64);var map=new LinkedHashMap<>();for(int i=0;i<n;i++){var key=value(depth+1);var item=value(depth+1);if(key==null || item==null || map.putIfAbsent(key,item)!=null)throw new IOException("Invalid cache map entry");}result=Collections.unmodifiableMap(map);break;}
          case RECORD:{var shape=shape();if(shape.constructor==null)throw new IOException("Expected cache record");var args=new Object[shape.readers.length];allocate((long)args.length*8);for(int i=0;i<args.length;i++)args[i]=value(depth+1);result=shape.constructor.newInstance(args);break;}
          default:throw new IOException("Unknown cache wire tag");
        }
      } catch(ReflectiveOperationException|IllegalArgumentException|ClassCastException e){throw new IOException("Invalid scene cache payload",e);}
      refs.set(index,result);return result;
    }
  }
}
