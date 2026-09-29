package com.elfmcys.ysm.mock.supervisor;

import java.nio.file.Files;
import java.nio.file.Path;

public final class DummyProcess {
    private DummyProcess() {
    }

    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "exit" -> System.exit(Integer.parseInt(args[1]));
            case "ready" -> {
                Files.writeString(Path.of(args[1]), "ready\n");
                Thread.sleep(Long.parseLong(args[2]));
            }
            case "tree" -> {
                var child = new ProcessBuilder(javaCommand("ready", args[2], "600000"))
                        .inheritIO().start();
                Files.writeString(Path.of(args[1]), Long.toString(child.pid()));
                Thread.sleep(600000);
            }
            default -> throw new IllegalArgumentException("Unknown dummy mode: " + args[0]);
        }
    }

    static String[] javaCommand(String... arguments) {
        var result = new String[arguments.length + 4];
        result[0] = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        result[1] = "-cp";
        // This child uses only JDK APIs. Passing the game's entire classpath can
        // exceed CreateProcess's command-line limit on Windows.
        try {
            result[2] = Path.of(DummyProcess.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).toString();
        } catch (java.net.URISyntaxException error) {
            throw new IllegalStateException(error);
        }
        result[3] = DummyProcess.class.getName();
        System.arraycopy(arguments, 0, result, 4, arguments.length);
        return result;
    }
}
