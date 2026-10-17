/* Adapted from CustomPlayerModel BedrockModelParser / ElementParser.
 * Copyright (c) 2020 Gamepiaynmo. MIT; see licenses/custom-player-model/LICENSE.txt.
 * Source: c40b0ab28b6a223888f04156a0b827b18b5cf36d. No Minecraft renderer code is included.
 */
package cc.sirrus.ysmlib.cpm.java;

import cc.sirrus.ysmlib.cpm.*;
import cc.sirrus.ysmlib.cpm.CpmValue.*;
import cc.sirrus.ysmlib.cpm.CpmGeometry.*;
import cc.sirrus.ysmlib.scene.io.AssetFormatException;
import java.util.*;
import static cc.sirrus.ysmlib.cpm.java.CpmJson.*;

/** Source structure only: no axis conversion, pivot subtraction, UV baking or animation evaluation. */
final class CpmGeometryReader {
    CpmGeometry read(ObjectValue root) throws AssetFormatException {
        requireUnique(root,"geometry");
        String version=required(root,"format_version");
        boolean legacy;
        // CPM follows CustomPlayerModel's upstream major/minor rule: the
        // pre-1.12 files use geometry.<name> objects, while 1.12 and later
        // use minecraft:geometry arrays.  Keep accepting newer Bedrock
        // revisions (1.16.x and future patch versions) because their static
        // bone/cube schema is compatible with the CPM reader.
        var versionParts = version.split("\\.");
        if (versionParts.length != 3) throw error("Unsupported CPM Bedrock format_version: "+version);
        try {
            int major = Integer.parseInt(versionParts[0]);
            int minor = Integer.parseInt(versionParts[1]);
            legacy = major < 1 || (major == 1 && minor < 12);
        } catch (NumberFormatException failure) {
            throw error("Unsupported CPM Bedrock format_version: "+version);
        }
        var models=new ArrayList<Model>();
        if(legacy) {
            if(root.fields().containsKey("minecraft:geometry")) throw error("Mixed CPM Bedrock layouts");
            for(var entry:root.fields().entrySet()) if(entry.getKey().startsWith("geometry"))
                models.add(model(entry.getKey(),object(entry.getValue(),entry.getKey()),true));
        } else {
            for(String key:root.fields().keySet()) if(key.startsWith("geometry")) throw error("Mixed CPM Bedrock layouts");
            for(var entry:array(root,"minecraft:geometry")) {
                var value=object(entry,"minecraft:geometry");
                var description=object(value.fields().get("description"),"description");
                models.add(model(string(description,"identifier",""),value,false));
            }
        }
        if(models.isEmpty()) throw error("CPM geometry has no models");
        return new CpmGeometry(version,models,root);
    }
    private Model model(String identifier,ObjectValue source,boolean legacy) throws AssetFormatException {
        var description=legacy?source:object(source.fields().get("description"),"description");
        int width=dimension(description,legacy?"texturewidth":"texture_width");
        int height=dimension(description,legacy?"textureheight":"texture_height");
        var bones=new ArrayList<Bone>();var names=new HashSet<String>();
        for(var entry:array(source,"bones")) {
            var bone=object(entry,"bone");String name=required(bone,"name");
            if(!names.add(name)) throw error("Duplicate CPM geometry bone: "+name);
            var cubes=new ArrayList<Cube>();
            for(var item:array(bone,"cubes")) {
                var cube=object(item,"cube");
                CpmValue uv=cube.fields().getOrDefault("uv",new ArrayValue(List.of(new NumberValue("0"),new NumberValue("0"))));
                if(uv instanceof ArrayValue) numbers(cube,"uv",2,0);
                else object(uv,"uv"); // Per-face data is retained, not interpreted as supported box UV.
                cubes.add(new Cube(vector(cube,"origin"),vector(cube,"size"),vector(cube,"pivot"),vector(cube,"rotation"),uv,
                                   number(cube,"inflate",0),bool(cube,"mirror",false),cube));
            }
            bones.add(new Bone(name,string(bone,"parent",""),vector(bone,"pivot"),vector(bone,"rotation"),cubes,bone));
        }
        // External parent names are resolved against the complete model and its host skeleton by the package reader.
        var parents=new HashMap<String,String>();for(var bone:bones) parents.put(bone.name(),bone.parent());
        var complete=new HashSet<String>();
        for(var bone:bones) {
            var chain=new HashSet<String>();String current=bone.name();
            while(current!=null&&!current.isEmpty()&&!complete.contains(current)) {
                if(!chain.add(current)) throw error("Cyclic CPM geometry hierarchy: "+current);
                current=parents.get(current);
            }
            complete.addAll(chain);
        }
        return new Model(identifier,width,height,bones,source);
    }
    private int dimension(ObjectValue value,String key) throws AssetFormatException {
        double number=number(value,key,0);
        if(number<1||number>Integer.MAX_VALUE||number!=Math.rint(number)) throw error("Invalid CPM "+key);
        return (int)number;
    }
    private CpmGeometry.Vector vector(ObjectValue value,String key) throws AssetFormatException {
        var numbers=numbers(value,key,3,0);return new CpmGeometry.Vector(numbers.get(0),numbers.get(1),numbers.get(2));
    }
}
