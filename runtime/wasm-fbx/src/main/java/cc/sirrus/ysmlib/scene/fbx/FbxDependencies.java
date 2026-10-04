package cc.sirrus.ysmlib.scene.fbx;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.io.*;
import java.io.IOException;
import java.util.*;

/** Caller-owned resolution only; a stored absolute path never grants filesystem access. */
public final class FbxDependencies {
  public FbxAssets resolve(FbxDocument source,AssetResolver resolver,ReadLimits limits) throws IOException {
    Objects.requireNonNull(source);Objects.requireNonNull(resolver);Objects.requireNonNull(limits);
    long bytes=source.source().size();if(bytes>limits.maxBytes()) throw new AssetFormatException("FBX dependency byte budget exceeded");
    var images=new LinkedHashMap<Integer,FbxAssets.Image>();var resolved=new HashMap<String,ByteData>();
    for(int i=0;i<source.textures().size();i++) {
      var texture=source.textures().get(i);if(texture.type()!=0) continue;
      String reference=texture.relativePath().isEmpty()?texture.absolutePath():texture.relativePath();var data=texture.content();boolean embedded=data.size()!=0;
      if(!embedded) {
        if(reference.isEmpty()) throw new AssetFormatException("FBX file texture has no content or source reference: "+i);
        data=resolved.get(reference);
        if(data==null) { data=Objects.requireNonNull(resolver.resolve(reference),"Resolver returned no data");bytes+=data.size();resolved.put(reference,data); }
      }
      // Embedded bytes are already charged in source input, external bytes only once per exact reference.
      if(bytes>limits.maxBytes()) throw new AssetFormatException("FBX dependency byte budget exceeded");
      if(images.size()>=limits.maxElements()) throw new AssetFormatException("FBX dependency element budget exceeded");
      images.put(i,new FbxAssets.Image(reference,embedded,data));
    }
    return new FbxAssets(images);
  }
}
