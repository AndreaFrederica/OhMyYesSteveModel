package cc.sirrus.ysmlib.cpm.java;

import cc.sirrus.ysmlib.cpm.CpmDocument;
import cc.sirrus.ysmlib.scene.ByteData;
import cc.sirrus.ysmlib.scene.io.ReadLimits;
import java.nio.file.*;
import java.util.*;

/** Opt-in local corpus verification. Upstream model art is not copied into this repository or distribution. */
public final class CpmUpstreamVerification {
    public static void main(String[] args) throws Exception {
        if(args.length!=1) throw new IllegalArgumentException("Provide a root containing CustomModel/examples and CustomPlayerModel/examples");
        var root=Path.of(args[0]);var reader=new JavaCpmProvider();int old=0,newer=0;var failures=new ArrayList<String>();
        // A local game instance commonly stores all legacy packs directly in
        // custom-models/ instead of preserving the two upstream repository
        // folders. Accept that layout for corpus verification as well.
        boolean direct = !Files.isDirectory(root.resolve("CustomModel"));
        var repositories = direct ? List.of(".") : List.of("CustomModel","CustomPlayerModel");
        for(String repo:repositories) {
            var examples = direct ? root : root.resolve(repo).resolve("examples");
            try(var directories=Files.list(examples)) {
                for(var directory:directories.filter(Files::isDirectory).sorted().toList()) {
                    var files=new LinkedHashMap<String,ByteData>();try(var paths=Files.walk(directory)) {
                        for(var file:paths.filter(Files::isRegularFile).toList()) files.put(directory.relativize(file).toString().replace('\\','/'),new ByteData(Files.readAllBytes(file)));
                    }
                    if (!files.containsKey("model.json")) {
                        var candidates = files.keySet().stream()
                                .filter(path -> path.equalsIgnoreCase("model.json") || path.toLowerCase(Locale.ROOT).endsWith("/model.json"))
                                .toList();
                        if (candidates.size() == 1) {
                            String model = candidates.get(0), prefix = model.substring(0, model.length() - "model.json".length());
                            var normalized = new LinkedHashMap<String,ByteData>();
                            files.forEach((path, bytes) -> normalized.put(path.startsWith(prefix) ? path.substring(prefix.length()) : path, bytes));
                            files = normalized;
                        }
                    }
                    CpmDocument doc;
                    try { doc=reader.read(files,ReadLimits.DEFAULT); }
                    catch(java.io.IOException failure) {
                        String message=repo+"/"+directory.getFileName()+": "+failure.getMessage();
                        failures.add(message);System.err.println(message);continue;
                    }
                    try (var session = reader.createPoseSession(doc).orElseThrow()) {
                        var inputs = Map.of("health", 20d, "max_health", 20d,
                                "is_first_person", 0d, "inv.helmet", 1d,
                                "inv.chestplate", 1d, "inv.leggings", 1d,
                                "inv.boots", 1d, "inv.offhand", 0d,
                                "inv.mainhand", 1d);
                        session.init(0);
                        session.tick(0, inputs); session.tick(.5, inputs); session.tick(1, inputs);
                    } catch (RuntimeException failure) {
                        failures.add((direct ? directory.getFileName() : repo+"/"+directory.getFileName())
                                + ": runtime evaluation: " + failure.getMessage());
                        System.err.println(failures.get(failures.size() - 1));
                        continue;
                    }
                    if(doc.compatibility().canEvaluateRequiredFeatures()) throw new AssertionError("Source read must not claim runtime evaluation");
                    if(doc instanceof CpmDocument.Legacy) old++; else if(doc instanceof CpmDocument.Remake) newer++;
                    System.out.println((direct ? directory.getFileName().toString() : repo+"/"+directory.getFileName())+": "+doc.metadata().name()+" [READ]");
                }
            }
        }
        if(!failures.isEmpty() || (!direct && (old!=14 || newer!=15)))
            throw new AssertionError((direct ? "Local corpus failures" : "Expected pinned corpus of 14 legacy and 15 remake examples")+"; got "+old+" / "+newer+"; failures="+failures);
        System.out.println(direct ? "Verified local corpus: "+old+" legacy and "+newer+" remake sources; pose evaluation completed without Minecraft rendering."
                : "Verified all 29 source closures; source pose evaluation was not part of the pinned count.");
    }
}
