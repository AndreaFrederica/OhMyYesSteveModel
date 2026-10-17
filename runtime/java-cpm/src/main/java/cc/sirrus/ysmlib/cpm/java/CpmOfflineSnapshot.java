package cc.sirrus.ysmlib.cpm.java;

import cc.sirrus.ysmlib.cpm.*;
import cc.sirrus.ysmlib.scene.ByteData;
import cc.sirrus.ysmlib.scene.io.ReadLimits;
import java.nio.file.*;
import java.util.*;

/**
 * Game-independent CPM diagnostic. It records the exact static IR and three
 * pose frames used by the two format-specific sessions, so a YSM bake can be
 * compared without starting Minecraft.
 *
 * Usage: CpmOfflineSnapshot <model-directory> [output.json]
 */
public final class CpmOfflineSnapshot {
    private CpmOfflineSnapshot() {}
    public static void main(String[] args) throws Exception {
        if (args.length < 1) throw new IllegalArgumentException("model directory required");
        Path root = Path.of(args[0]); var files = new LinkedHashMap<String,ByteData>();
        try (var walk = Files.walk(root)) { for (var path : walk.filter(Files::isRegularFile).sorted().toList()) files.put(root.relativize(path).toString().replace('\\','/'), new ByteData(Files.readAllBytes(path))); }
        var provider = new JavaCpmProvider(); var document = provider.read(files, ReadLimits.DEFAULT); var model = provider.compileCanonical(document);
        var out = new StringBuilder(); out.append("{\n  \"format\":\"").append(document instanceof CpmDocument.Legacy ? "legacy" : "modern").append("\",\n  \"bones\":[\n");
        for (int i=0;i<model.bones().size();i++) { var b=model.bones().get(i); if(i>0)out.append(",\n"); out.append("    {\"name\":\"").append(escape(b.name())).append("\",\"parent\":").append(b.parent()).append(",\"pivot\":").append(vec(b.pivot())).append(",\"rotation\":").append(vec(b.rotation())).append("}"); }
        out.append("\n  ],\n  \"texture_size\":[").append(model.textureWidth()).append(',').append(model.textureHeight()).append("],\n  \"textures\":{");
        int textureIndex=0; for (var texture : model.textures().values()) { if (textureIndex++>0) out.append(','); out.append("\"").append(escape(texture.symbol())).append("\":\"").append(escape(texture.packagePath())).append("\""); }
        out.append("},\n  \"primitives\":[");
        for (int i=0;i<model.primitives().size();i++) { var p=model.primitives().get(i); if(i>0)out.append(','); out.append("{\"kind\":\"").append(p.kind()).append("\",\"bone\":").append(p.bone()).append(",\"origin\":").append(vec(p.origin())).append(",\"size\":").append(vec(p.size())).append(",\"pivot\":").append(vec(p.pivot())).append(",\"rotation\":").append(vec(p.rotation())).append(",\"inflate\":").append(p.inflate()).append(",\"mirror\":").append(p.mirror()).append(",\"texture\":\"").append(escape(p.texture())).append("\",\"uv\":").append(value(p.uv())).append("}"); }
        out.append("],\n  \"poses\":{\n");
        try (var session = provider.createPoseSession(document).orElseThrow()) {
            for (int i=0;i<3;i++) { if(i>0)out.append(",\n"); double age=i*10; out.append("    \"").append((int)age).append("\":{"); var pose=session.tick(age, Map.of("age",age,"time",age)); out.append("\"bones\":").append(pose.size()).append(",\"values\":{"); int n=0; for(var e:pose.entrySet()){if(n++>0)out.append(",");out.append("\"").append(escape(e.getKey())).append("\":{\"position\":").append(vec(e.getValue().position())).append(",\"rotation\":").append(vec(e.getValue().rotation())).append(",\"scale\":").append(vec(e.getValue().scale())).append("}");} out.append("}}" ); }
        }
        out.append("\n  }\n}\n"); Path target=args.length>1?Path.of(args[1]):root.resolve("cpm-offline-snapshot.json"); Files.writeString(target,out.toString()); System.out.println(target.toAbsolutePath());
    }
    private static String vec(CpmCompiledModel.Vec3 v){return "["+v.x()+","+v.y()+","+v.z()+"]";}
    private static String vec(double[] v){return "["+v[0]+","+v[1]+","+v[2]+"]";}
    private static String value(CpmValue value){ if(value instanceof CpmValue.ArrayValue a){var s=new StringBuilder("[");for(int i=0;i<a.values().size();i++){if(i>0)s.append(',');s.append(value(a.values().get(i)));}return s.append(']').toString();} if(value instanceof CpmValue.NumberValue n)return n.value(); if(value instanceof CpmValue.BooleanValue b)return Boolean.toString(b.value()); if(value instanceof CpmValue.StringValue s)return "\""+escape(s.value())+"\""; return "null"; }
    private static String escape(String s){return s.replace("\\","\\\\").replace("\"","\\\"");}
}
