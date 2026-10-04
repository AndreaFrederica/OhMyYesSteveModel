package cc.sirrus.ysmlib.tools.scene;

import cc.sirrus.ysmlib.YsmRuntime;
import cc.sirrus.ysmlib.scene.*;
import com.google.gson.*;
import java.nio.charset.StandardCharsets;

/** Edits the exact published sidecar schema, preserving fields not involved in an operation. */
final class ProfileEdits {
    static final Gson JSON=new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    static String json(SceneModelProfile profile){return new String(YsmRuntime.scenes().writeModelProfile(profile).copy(),StandardCharsets.UTF_8);}
    static SceneModelProfile parse(String json){return YsmRuntime.scenes().readModelProfile(new ByteData(json.getBytes(StandardCharsets.UTF_8)));}
    static SceneModelProfile set(SceneModelProfile profile,String path,String input){
        JsonObject root=JsonParser.parseString(json(profile)).getAsJsonObject(),target=root;String[] parts=path.split("\\.");
        for(int i=0;i<parts.length-1;i++){if(!target.has(parts[i])||!target.get(parts[i]).isJsonObject())throw new IllegalArgumentException("Unknown object: "+path);target=target.getAsJsonObject(parts[i]);}
        String leaf=parts[parts.length-1];if(!target.has(leaf))throw new IllegalArgumentException("Unknown field: "+path);
        JsonElement old=target.get(leaf),value;
        if(old.isJsonPrimitive()&&old.getAsJsonPrimitive().isString())value=new JsonPrimitive(input);
        else value=JsonParser.parseString(input);
        target.add(leaf,value);return parse(JSON.toJson(root));
    }
}
