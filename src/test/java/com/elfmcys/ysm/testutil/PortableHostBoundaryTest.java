package com.elfmcys.ysm.testutil;

import java.io.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Reads bytecode without loading optional game integrations or native libraries. */
class PortableHostBoundaryTest {
    @Test void productionClassesDeclareNoJniMethods() throws Exception {
        Path root = Path.of("build/classes/java/main");
        assertTrue(Files.isDirectory(root));
        try (var files = Files.walk(root)) {
            for (var file : files.filter(p -> p.toString().endsWith(".class")).toList()) {
                try (var in = new DataInputStream(Files.newInputStream(file))) {
                    assertEquals(0xcafebabe, in.readInt());
                    in.skipNBytes(4);
                    int constants = in.readUnsignedShort();
                    for (int i = 1; i < constants; i++) {
                        switch (in.readUnsignedByte()) {
                            case 1 -> in.skipNBytes(in.readUnsignedShort());
                            case 3, 4, 9, 10, 11, 12, 17, 18 -> in.skipNBytes(4);
                            case 5, 6 -> { in.skipNBytes(8); i++; }
                            case 7, 8, 16, 19, 20 -> in.skipNBytes(2);
                            case 15 -> in.skipNBytes(3);
                            default -> fail("Unknown class constant in " + file);
                        }
                    }
                    in.skipNBytes(6);
                    in.skipNBytes(in.readUnsignedShort() * 2L);
                    members(in, false, file);
                    members(in, true, file);
                }
            }
        }
    }

    private static void members(DataInputStream in, boolean methods, Path file) throws IOException {
        int count = in.readUnsignedShort();
        for (int i = 0; i < count; i++) {
            int flags = in.readUnsignedShort();
            if (methods) assertEquals(0, flags & 0x0100, "JNI declaration in " + file);
            in.skipNBytes(4);
            int attributes = in.readUnsignedShort();
            for (int a = 0; a < attributes; a++) {
                in.skipNBytes(2);
                in.skipNBytes(Integer.toUnsignedLong(in.readInt()));
            }
        }
    }
}
