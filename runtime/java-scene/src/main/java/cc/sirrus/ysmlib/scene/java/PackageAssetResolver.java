package cc.sirrus.ysmlib.scene.java;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.io.*;
import java.net.*;
import java.util.*;

/** Resolves within an immutable package. URI escaping and literal DCC paths are distinct policies. */
public final class PackageAssetResolver implements AssetResolver {
  private final ScenePackage source;
  private final String owner;
  private final ScenePackage.ReferenceSyntax syntax;
  private final Map<String,String> mappings=new HashMap<>();
  public PackageAssetResolver(ScenePackage source,String owner,ScenePackage.ReferenceSyntax syntax) {
    this.source=Objects.requireNonNull(source);this.owner=ScenePackage.checkedPath(owner);this.syntax=Objects.requireNonNull(syntax);
    if(!source.files().containsKey(owner)) throw new IllegalArgumentException("Unknown package owner");
    for(var relocation:source.relocations()) if(relocation.owner().equals(owner)) mappings.put(relocation.reference(),relocation.target());
  }
  public String path(String reference) throws AssetFormatException {
    if(reference==null || reference.isEmpty()) throw new AssetFormatException("Empty package dependency");
    String mapped=mappings.get(reference);if(mapped!=null) return mapped;
    String relative=reference;
    if(syntax==ScenePackage.ReferenceSyntax.URI) {
      try {
        var uri=new URI(reference);
        if(uri.isAbsolute() || uri.getRawAuthority()!=null || uri.getRawQuery()!=null || uri.getRawFragment()!=null)
          throw new AssetFormatException("Dependency requires explicit relocation: "+reference);
        // URI#getPath decodes exactly once. Percent and hash in FILE_PATH references are literal.
        relative=decodePath(uri.getRawPath());
      } catch(URISyntaxException e) { throw new AssetFormatException("Invalid dependency URI",e); }
    } else relative=relative.replace('\\','/');
    if(relative==null || relative.isEmpty() || relative.startsWith("/") || relative.indexOf(':')>=0
        || relative.indexOf('\\')>=0 || relative.indexOf('\0')>=0)
      throw new AssetFormatException("Dependency requires explicit relocation: "+reference);
    var parts=new ArrayDeque<String>();int slash=owner.lastIndexOf('/');
    if(slash>=0) Collections.addAll(parts,owner.substring(0,slash).split("/"));
    for(String part:relative.split("/",-1)) {
      if(part.equals("..")) {
        if(parts.isEmpty()) throw new AssetFormatException("Dependency escapes package: "+reference);
        parts.removeLast();
      } else if(!part.equals(".") && !part.isEmpty()) parts.add(part);
    }
    String path=String.join("/",parts);
    if(path.isEmpty()) throw new AssetFormatException("Dependency refers to package directory");
    return path;
  }
  @Override public ByteData resolve(String reference) throws AssetFormatException {
    String path=path(reference);var data=source.files().get(path);
    if(data==null) throw new AssetFormatException("Package dependency not found: "+reference+" ("+path+")");
    return data;
  }
  private static String decodePath(String raw) throws AssetFormatException {
    if(raw==null) return null;
    var bytes=new java.io.ByteArrayOutputStream();
    for(int i=0;i<raw.length();) {
      if(raw.charAt(i)=='%') {
        if(i+2>=raw.length()) throw new AssetFormatException("Truncated percent escape");
        int high=Character.digit(raw.charAt(i+1),16),low=Character.digit(raw.charAt(i+2),16);
        if(high<0 || low<0) throw new AssetFormatException("Invalid percent escape");
        bytes.write(high*16+low);i+=3;
      } else {
        int cp=raw.codePointAt(i);bytes.writeBytes(new String(Character.toChars(cp)).getBytes(java.nio.charset.StandardCharsets.UTF_8));i+=Character.charCount(cp);
      }
    }
    try {
      return java.nio.charset.StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
          .decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString();
    } catch(java.nio.charset.CharacterCodingException invalid) { throw new AssetFormatException("Malformed UTF-8 dependency URI",invalid); }
  }
}
