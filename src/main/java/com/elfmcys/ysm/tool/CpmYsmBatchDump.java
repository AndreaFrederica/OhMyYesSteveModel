package com.elfmcys.ysm.tool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/** One JVM batch wrapper around {@link CpmYsmActualDump}. */
public final class CpmYsmBatchDump {
  private CpmYsmBatchDump() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 3) {
      throw new IllegalArgumentException("usage: CpmYsmBatchDump <legacy-root> <modern-root> <output-root>");
    }
    Path output = Path.of(args[2]);
    run("legacy", Path.of(args[0]), output.resolve("legacy"));
    run("modern", Path.of(args[1]), output.resolve("modern"));
  }

  private static void run(String kind, Path root, Path output) throws Exception {
    Files.createDirectories(output);
    try (var paths = Files.list(root).sorted(Comparator.comparing(Path::toString))) {
      for (Path model : paths.filter(Files::isDirectory).toList()) {
        if (!Files.isRegularFile(model.resolve("model.json"))) continue;
        String name = model.getFileName().toString().replaceAll("[^A-Za-z0-9_.-]+", "_");
        Path target = output.resolve(name + ".json");
        System.out.println("[CPM-BATCH] " + kind + " " + model.getFileName());
        try {
          CpmYsmActualDump.main(new String[]{model.toString(), target.toString()});
        } catch (Throwable error) {
          Files.writeString(output.resolve(name + ".error.txt"), error.toString());
          System.err.println("[CPM-BATCH-FAIL] " + kind + " " + model.getFileName() + " " + error);
        }
      }
    }
  }
}
