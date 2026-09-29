package cc.sirrus.ysmlib.tools;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class V3dToolTest {
    @TempDir Path temp;

    @Test void helpAndFailuresHaveStableExitCodesWithoutCreatingOutputs() throws Exception {
        var bytes = new ByteArrayOutputStream();
        var output = new PrintStream(bytes);
        assertEquals(0, V3dTool.run(new String[]{"--help"}, output, output));
        assertTrue(bytes.toString().contains("restore"));
        assertEquals(2, V3dTool.run(new String[]{"unknown"}, output, output));
        Path invalid = Files.write(temp.resolve("invalid.ysm"), new byte[]{1, 2, 3});
        assertEquals(1, V3dTool.run(new String[]{"decode", invalid.toString(), temp.resolve("out").toString()}, output, output));
        assertEquals(1, V3dTool.run(new String[]{"validate", temp.resolve("missing.v3d").toString()}, output, output));
        assertEquals(1, V3dTool.run(new String[]{"restore", temp.resolve("missing.v3d").toString(), temp.resolve("restored.ysm").toString()}, output, output));
        assertFalse(Files.exists(temp.resolve("restored.ysm")));
    }
}
