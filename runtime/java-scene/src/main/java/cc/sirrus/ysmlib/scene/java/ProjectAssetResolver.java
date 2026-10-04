package cc.sirrus.ysmlib.scene.java;

import cc.sirrus.ysmlib.scene.ByteData;
import cc.sirrus.ysmlib.scene.io.*;
import java.io.IOException;
import java.net.*;
import java.util.*;

/** Rebase source workstation paths into an explicitly supplied project capability; never open them directly. */
public final class ProjectAssetResolver implements AssetResolver {
  public record Root(String sourcePrefix,String projectPrefix) {}
  public record Resolved(String sourceReference,String projectReference,ByteData data) {}
  private final AssetResolver project;private final Map<String,String> files;private final List<Root> roots;
  public ProjectAssetResolver(AssetResolver project,Map<String,String> files,List<Root> roots) {
    this.project=Objects.requireNonNull(project);var mapped=new LinkedHashMap<String,String>();
    files.forEach((k,v)->{ if(mapped.put(key(k),relative(v))!=null) throw new IllegalArgumentException("Duplicate source path mapping"); });this.files=Map.copyOf(mapped);
    var normalized=new ArrayList<Root>();var seen=new HashSet<String>();
    for(var root:roots) { String prefix=key(root.sourcePrefix());while(prefix.endsWith("/")) prefix=prefix.substring(0,prefix.length()-1);
      if(prefix.isEmpty() || !seen.add(prefix)) throw new IllegalArgumentException("Empty or duplicate source root");normalized.add(new Root(prefix,relative(root.projectPrefix()))); }
    normalized.sort(Comparator.comparingInt((Root r)->r.sourcePrefix().length()).reversed());this.roots=List.copyOf(normalized);
  }
  public String rebase(String sourceReference) throws AssetFormatException {
    String source=sourceReference.replace('\\','/'),lookup=key(source);String mapped=files.get(lookup);
    if(mapped==null) for(var root:roots) if(lookup.startsWith(root.sourcePrefix()+"/")) {
      String tail=source.substring(root.sourcePrefix().length()+1);mapped=root.projectPrefix().isEmpty()?tail:root.projectPrefix()+"/"+tail;break;
    }
    if(mapped==null) { if(absolute(source)) throw new AssetFormatException("Source path requires explicit project relocation: "+sourceReference);mapped=source; }
    try { mapped=relative(mapped);return new URI(null,null,mapped,null).toASCIIString(); }
    catch(IllegalArgumentException|URISyntaxException e) { throw new AssetFormatException("Invalid relocated project path",e); }
  }
  public Resolved resolveWithOrigin(String sourceReference) throws IOException { String target=rebase(sourceReference);return new Resolved(sourceReference,target,project.resolve(target)); }
  @Override public ByteData resolve(String sourceReference) throws IOException { return resolveWithOrigin(sourceReference).data(); }
  private static boolean absolute(String value) { return value.startsWith("/") || value.matches("^[A-Za-z][A-Za-z0-9+.-]*:.*"); }
  private static String key(String value) { String s=value.replace('\\','/');return s.matches("^[A-Za-z]:.*") || s.startsWith("//")?s.toLowerCase(Locale.ROOT):s; }
  private static String relative(String value) {
    String s=Objects.requireNonNull(value).replace('\\','/');if(absolute(s) || s.indexOf('\0')>=0) throw new IllegalArgumentException("Project mapping must be relative");
    var parts=new ArrayDeque<String>();for(String p:s.split("/")) { if(p.equals("..")) { if(parts.isEmpty()) throw new IllegalArgumentException("Project mapping escapes capability");parts.removeLast(); }else if(!p.isEmpty() && !p.equals(".")) parts.add(p); }
    return String.join("/",parts);
  }
}
