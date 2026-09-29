package com.elfmcys.ysm.command.sub;

import static org.junit.jupiter.api.Assertions.*;
import com.elfmcys.ysm.command.RootCommand;
import com.mojang.brigadier.CommandDispatcher;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class V3dCommandTest {
    @TempDir Path temp;

    private CommandSourceStack source(int permission) {
        return new CommandSourceStack(CommandSource.NULL, Vec3.ZERO, Vec2.ZERO, null,
                permission, "test", Component.literal("test"), null, null);
    }

    @Test void registeredCommandRequiresOperatorAndParsesQuotedModelNames() {
        var dispatcher = new CommandDispatcher<CommandSourceStack>();
        RootCommand.register(dispatcher);
        var node = dispatcher.getRoot().getChild("ysm").getChild("v3d");
        assertNotNull(node);
        assertTrue(node.canUse(source(2)));
        assertFalse(node.canUse(source(0)));
        for (String operation : new String[]{"export", "validate", "restore"}) {
            var parsed = dispatcher.parse("ysm v3d " + operation + " \"migration-test/模型 test.ysm\"", source(2));
            assertTrue(parsed.getExceptions().isEmpty());
            assertFalse(parsed.getReader().canRead());
            assertNotNull(parsed.getContext().getCommand());
        }
    }

    @Test void restrictsInputsToOwnedRoots() throws Exception {
        Path custom = Files.createDirectories(temp.resolve("custom"));
        Files.writeString(custom.resolve("model.ysm"), "source");
        assertEquals(custom.resolve("model.ysm"), V3dCommandPaths.existing(custom, "model.ysm", false));
        for (String invalid : new String[]{"../model.ysm", "..\\model.ysm", "/model.ysm", "C:/model.ysm",
                "model.ysm:stream", "a//b", "./model.ysm", ".. /model.ysm", "model.ysm.", ""}) {
            assertThrows(IOException.class, () -> V3dCommandPaths.existing(custom, invalid, false), invalid);
        }
        assertThrows(IOException.class, () -> V3dCommandPaths.existing(custom, "missing.ysm", false));
        assertThrows(IOException.class, () -> V3dCommandPaths.existing(custom, "model.ysm", true));
    }

    @Test void commandOperationsRoundTripConfiguredRealModelsAndRejectOverwrite() throws Exception {
        String configured = System.getenv("YSM_MIGRATION_MODELS");
        org.junit.jupiter.api.Assumptions.assumeTrue(configured != null);
        Path models = Path.of(configured), exports = temp.resolve("export");
        try (var files = Files.list(models)) {
            for (Path input : files.filter(p -> p.toString().endsWith(".ysm")).toList()) {
                Path workspace = V3dCommand.execute("export", input.getFileName().toString(), models, exports);
                String name = workspace.getFileName().toString();
                assertEquals(workspace, V3dCommand.execute("validate", name, models, exports));
                Files.writeString(workspace.resolve("legacy/model.json"), "edited view");
                assertThrows(IOException.class, () -> V3dCommand.execute("validate", name, models, exports));
                Path restored = V3dCommand.execute("restore", name, models, exports);
                assertEquals(-1, Files.mismatch(input, restored));
                assertThrows(IOException.class, () -> V3dCommand.execute("restore", name, models, exports));
            }
        }
    }
}
