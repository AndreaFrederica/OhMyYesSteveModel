package cc.sirrus.ysmlib.tools;

import cc.sirrus.ysmlib.legacy.java.JavaDecodedWorkspaceProvider;
import cc.sirrus.ysmlib.legacy.java.JavaV3EnvelopeProvider;
import cc.sirrus.ysmlib.v3d.V3dCache;
import java.io.PrintStream;
import java.nio.file.Path;

/** Headless entry point. Requires neither Minecraft nor the runtime's rendering services. */
public final class V3dTool {
    private V3dTool() {}

    public static void main(String[] args) {
        int status = run(args, System.out, System.err);
        if (status != 0) System.exit(status);
    }

    public static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 0 || (args.length == 1 && args[0].equals("--help"))) {
            out.println("V3D tools (Java 17)\n"
                    + "  decode <source.ysm> <output-directory>\n"
                    + "  validate <workspace.v3d>\n"
                    + "  restore <workspace.v3d> <new-output.ysm>\n"
                    + "Restore copies the original file; it does not encode edited JSON.");
            return 0;
        }
        boolean valid = args.length == 2 && args[0].equals("validate")
                || args.length == 3 && (args[0].equals("decode") || args[0].equals("restore"));
        if (!valid) {
            err.println("Invalid arguments. Use --help for usage.");
            return 2;
        }
        try {
            Path source = Path.of(args[1]);
            switch (args[0]) {
                case "decode" -> out.println(new V3dCache(new JavaV3EnvelopeProvider(),
                        new JavaDecodedWorkspaceProvider()).materialize(source, Path.of(args[2])));
                case "validate" -> {
                    V3dCache.validate(source);
                    out.println("Valid V3D: " + source.toAbsolutePath());
                }
                case "restore" -> {
                    V3dCache.restoreOriginal(source, Path.of(args[2]));
                    out.println(Path.of(args[2]).toAbsolutePath());
                }
                default -> throw new AssertionError();
            }
            return 0;
        } catch (Exception failure) {
            err.println("V3D failed: " + failure.getMessage());
            return 1;
        }
    }
}
