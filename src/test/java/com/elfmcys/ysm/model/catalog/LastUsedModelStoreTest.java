package com.elfmcys.ysm.model.catalog;

import com.elfmcys.ysm.model.domain.Hash256;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LastUsedModelStoreTest {
    @Test
    void selectionSurvivesAtomicRewriteAndNormalizesSeparators(@TempDir Path temp)
            throws Exception {
        var store = new LastUsedModelStore(temp.resolve("ysm/last-used-model.properties"));
        var hash = Hash256.parse("00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff");

        store.write("nested\\Kikyo.unitypackage", hash);

        var loaded = store.read();
        assertEquals("nested/Kikyo.unitypackage", loaded.path());
        assertEquals(hash, loaded.modelId());
        assertTrue(loaded.matches("nested/Kikyo.unitypackage"));
    }
}
