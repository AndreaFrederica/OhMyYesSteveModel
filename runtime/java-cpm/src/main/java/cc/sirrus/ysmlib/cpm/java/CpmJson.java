package cc.sirrus.ysmlib.cpm.java;

import cc.sirrus.ysmlib.cpm.CpmValue;
import cc.sirrus.ysmlib.cpm.CpmValue.*;
import cc.sirrus.ysmlib.scene.ByteData;
import cc.sirrus.ysmlib.scene.io.*;
import com.google.gson.stream.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;

/** Streaming, bounded source reader. Gson trees never escape into the public API. */
final class CpmJson {
    private final ReadLimits limits;
    private long elements;
    CpmJson(ReadLimits limits) { this.limits=Objects.requireNonNull(limits); }
    int maxStringBytes() { return limits.maxStringBytes(); }
    void element() throws AssetFormatException {
        if(++elements>limits.maxElements()) throw error("CPM element budget exceeded");
    }
    String text(ByteData source) throws AssetFormatException {
        if(source.size()>limits.maxBytes()) throw error("CPM source byte budget exceeded");
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(source.view()).toString();
        } catch(CharacterCodingException failure) { throw new AssetFormatException("Invalid CPM UTF-8",failure); }
    }
    ObjectValue read(ByteData source,String path) throws IOException {
        try(var reader=new JsonReader(new StringReader(text(source)))) {
            // Upstream models contain comments. Leniency is syntax only, not permission to skip validation.
            reader.setLenient(true);
            var root=value(reader,0);
            if(reader.peek()!=JsonToken.END_DOCUMENT) throw error(path+": trailing JSON content");
            return object(root,path);
        } catch(IllegalStateException|NumberFormatException failure) {
            throw new AssetFormatException(path+": invalid CPM JSON",failure);
        }
    }
    private CpmValue value(JsonReader reader,int depth) throws IOException {
        element();if(depth>128) throw error("CPM JSON nesting exceeds 128");
        return switch(reader.peek()) {
            case BEGIN_OBJECT -> {
                reader.beginObject();var fields=new LinkedHashMap<String,CpmValue>();var members=new ArrayList<Member>();
                while(reader.hasNext()) {
                    element();String name=string(reader.nextName());
                    var value=value(reader,depth+1);members.add(new Member(name,value));fields.put(name,value);
                }
                reader.endObject();yield new ObjectValue(fields,members);
            }
            case BEGIN_ARRAY -> {
                reader.beginArray();var values=new ArrayList<CpmValue>();
                while(reader.hasNext()) values.add(value(reader,depth+1));
                reader.endArray();yield new ArrayValue(values);
            }
            case STRING -> new StringValue(string(reader.nextString()));
            case NUMBER -> {
                String number=string(reader.nextString());
                if(!Double.isFinite(Double.parseDouble(number))) throw error("Nonfinite CPM number");
                yield new NumberValue(number);
            }
            case BOOLEAN -> new BooleanValue(reader.nextBoolean());
            case NULL -> {reader.nextNull();yield NullValue.INSTANCE;}
            default -> throw error("Expected CPM JSON value at "+reader.getPath());
        };
    }
    private String string(String value) throws AssetFormatException {
        if(value.getBytes(StandardCharsets.UTF_8).length>limits.maxStringBytes()) throw error("CPM string budget exceeded");
        return value;
    }
    static AssetFormatException error(String message) { return new AssetFormatException(message); }
    static List<String> duplicates(CpmValue value,String location) {
        var result=new ArrayList<String>();duplicates(value,location,result);return result;
    }
    private static void duplicates(CpmValue value,String location,List<String> result) {
        if(value instanceof ObjectValue object) {
            var seen=new HashSet<String>();for(var member:object.members()) {
                String at=location+"."+member.name();if(!seen.add(member.name())) result.add(at);
                duplicates(member.value(),at,result);
            }
        } else if(value instanceof ArrayValue array) {
            for(int i=0;i<array.values().size();i++) duplicates(array.values().get(i),location+"["+i+"]",result);
        }
    }
    static void requireUnique(CpmValue value,String location) throws AssetFormatException {
        var duplicates=duplicates(value,location);if(!duplicates.isEmpty()) throw error("Duplicate CPM JSON field: "+duplicates.get(0));
    }
    static ObjectValue object(CpmValue value,String at) throws AssetFormatException {
        if(value instanceof ObjectValue object) return object;throw error(at+" must be an object");
    }
    static List<CpmValue> array(CpmValue value,String at) throws AssetFormatException {
        if(value instanceof ArrayValue array) return array.values();throw error(at+" must be an array");
    }
    static List<CpmValue> array(ObjectValue object,String key) throws AssetFormatException {
        var value=object.fields().get(key);return value==null?List.of():array(value,key);
    }
    static String string(CpmValue value,String at) throws AssetFormatException {
        if(value instanceof StringValue text) return text.value();throw error(at+" must be a string");
    }
    static String string(ObjectValue object,String key,String fallback) throws AssetFormatException {
        var value=object.fields().get(key);return value==null?fallback:string(value,key);
    }
    static String required(ObjectValue object,String key) throws AssetFormatException {
        String value=string(object,key,"");if(value.isEmpty()) throw error("Missing CPM "+key);return value;
    }
    static double number(CpmValue value,String at) throws AssetFormatException {
        if(value instanceof NumberValue number) {
            try { double result=Double.parseDouble(number.value());if(Double.isFinite(result)) return result; }
            catch(NumberFormatException ignored) { }
        }
        throw error(at+" must be a finite number");
    }
    static double number(ObjectValue object,String key,double fallback) throws AssetFormatException {
        var value=object.fields().get(key);return value==null?fallback:number(value,key);
    }
    static boolean bool(ObjectValue object,String key,boolean fallback) throws AssetFormatException {
        var value=object.fields().get(key);if(value==null) return fallback;
        if(value instanceof BooleanValue flag) return flag.value();throw error(key+" must be boolean");
    }
    static List<Double> numbers(ObjectValue object,String key,int length,double fallback) throws AssetFormatException {
        var value=object.fields().get(key);if(value==null) return Collections.nCopies(length,fallback);
        var items=array(value,key);if(items.size()!=length) throw error(key+" must have "+length+" numbers");
        var result=new ArrayList<Double>();for(var item:items) result.add(number(item,key));return List.copyOf(result);
    }
    static List<String> strings(CpmValue value,String at) throws AssetFormatException {
        var result=new ArrayList<String>();for(var item:array(value,at)) result.add(string(item,at));return List.copyOf(result);
    }
}
