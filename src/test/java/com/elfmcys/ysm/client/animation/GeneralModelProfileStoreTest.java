package com.elfmcys.ysm.client.animation;

import cc.sirrus.ysmlib.scene.*;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class GeneralModelProfileStoreTest {
    @Test void junctionRootPreservesCatalogPathWhenSavingHeight(@TempDir Path temp) throws Exception {
        var physical=Files.createDirectory(temp.resolve("physical custom"));
        var alias=temp.resolve("linked custom");
        String catalogPath="mmd/风堇/model.pmx";
        var model=physical.resolve(catalogPath);
        Files.createDirectories(model.getParent());Files.writeString(model,"original model bytes");
        if(System.getProperty("os.name").startsWith("Windows")) {
            var process=new ProcessBuilder("cmd.exe","/c","mklink","/J",alias.toString(),physical.toString())
                    .redirectErrorStream(true).start();
            if(!process.waitFor(10,TimeUnit.SECONDS)) {process.destroyForcibly();fail("Junction creation timed out");}
            assertEquals(0,process.exitValue(),new String(process.getInputStream().readAllBytes()));
        } else Files.createSymbolicLink(alias,physical);
        try {
            var source=GeneralModelProfileStore.resolveSource(alias,catalogPath);
            assertEquals(catalogPath,source.catalogPath());
            assertTrue(Files.isSameFile(model,source.path()));
            assertFalse(source.path().startsWith(alias));
            // The previous UI calculation either returns the wrong path or throws across drives.
            if(alias.getRoot().equals(source.path().getRoot()))
                assertNotEquals(catalogPath,alias.relativize(source.path()).toString().replace('\\','/'));
            var initial=SceneModelProfile.defaults(.08,18,0);var p=initial.placement();
            var edited=initial.withPlacement(new SceneModelProfile.Placement(p.metersPerUnit(),
                    SceneModelProfile.SizeMode.HEIGHT,p.scale(),1.65,p.referenceHeight(),p.footY(),p.x(),p.y(),p.z(),p.yaw()));
            GeneralModelProfileStore.save(source.path(),edited,null);
            var saved=cc.sirrus.ysmlib.YsmRuntime.scenes().readModelProfile(new ByteData(GeneralModelProfileStore.read(model)));
            assertEquals(1.65,saved.placement().actualHeight(),1e-9);
            assertEquals("original model bytes",Files.readString(model));
            assertEquals(catalogPath,GeneralModelProfileStore.resolveSource(alias,catalogPath).catalogPath());
        } finally {Files.delete(alias);}
    }

    @Test void sourceCannotEscapeCustomOrEditCompiledContainer(@TempDir Path root) throws Exception {
        assertThrows(java.io.IOException.class,()->GeneralModelProfileStore.resolveSource(root,"../outside.pmx"));
        var absolute=Files.writeString(root.resolve("model.pmx"),"model");
        assertThrows(java.io.IOException.class,()->GeneralModelProfileStore.resolveSource(root,absolute.toString()));
        Files.writeString(root.resolve("model.mxc"),"container");
        assertThrows(java.io.IOException.class,()->GeneralModelProfileStore.resolveSource(root,"model.mxc"));
    }

    @Test void separateModelsAndExternalEditsAreNotOverwritten(@TempDir Path root) throws Exception {
        var first=root.resolve("8.pmx");var second=root.resolve("8_modified.pmx");
        var profile=SceneModelProfile.defaults(.08,18,0);
        var bytes=GeneralModelProfileStore.save(first,profile,null);
        assertArrayEquals(bytes,GeneralModelProfileStore.read(first));assertNull(GeneralModelProfileStore.read(second));
        var file=GeneralModelProfileStore.sidecar(first);Files.writeString(file,"broken user edit");
        assertThrows(java.io.IOException.class,()->GeneralModelProfileStore.save(first,profile,bytes));
        assertEquals("broken user edit",Files.readString(file));
    }
}
