package cc.sirrus.ysmlib.scene.java;

import cc.sirrus.ysmlib.scene.ByteData;
import cc.sirrus.ysmlib.scene.io.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;

/** Explicit directory capability with canonical path containment, including symlink resolution. */
public final class DirectoryAssetResolver implements AssetResolver {
  private final Path root;private final int maxBytes;
  public DirectoryAssetResolver(Path root,int maxBytes) throws IOException {
    this.root=root.toRealPath();if(!Files.isDirectory(this.root) || maxBytes<1) throw new IllegalArgumentException("Invalid asset root/limit");this.maxBytes=maxBytes;
  }
  @Override public ByteData resolve(String sourceReference) throws IOException {
    String reference=sourceReference.replace('\\','/');URI uri;
    try { uri=new URI(reference.replace(" ","%20")); } catch(URISyntaxException e) { throw new AssetFormatException("Invalid asset URI",e); }
    if(uri.isAbsolute() || uri.getRawAuthority()!=null || uri.getQuery()!=null || uri.getFragment()!=null)
      throw new AssetFormatException("Only relative file dependencies are allowed");
    String decoded=uri.getPath();if(decoded==null || decoded.indexOf('\\')>=0 || decoded.indexOf('\0')>=0)
      throw new AssetFormatException("Invalid asset path");
    Path relative;
    try { relative=Path.of(decoded); } catch(InvalidPathException e) { throw new AssetFormatException("Invalid dependency path",e); }
    if(relative.isAbsolute()) throw new AssetFormatException("Absolute dependency path is not allowed");
    Path target=root.resolve(relative).normalize();
    if(!target.startsWith(root)) throw new AssetFormatException("Dependency escapes asset root");
    target=target.toRealPath();if(!target.startsWith(root)) throw new AssetFormatException("Dependency symlink escapes asset root");
    if(!Files.isRegularFile(target) || Files.size(target)>maxBytes) throw new AssetFormatException("Invalid dependency size/type");
    try(var input=Files.newInputStream(target)) { byte[] data=input.readNBytes(maxBytes);if(input.read()!=-1) throw new AssetFormatException("Dependency exceeds limit");return new ByteData(data); }
  }
}
