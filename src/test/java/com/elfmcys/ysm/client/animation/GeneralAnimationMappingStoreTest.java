package com.elfmcys.ysm.client.animation;
import com.elfmcys.ysm.model.domain.Hash256;
import cc.sirrus.ysmlib.scene.ScenePackagePlayback;
import java.nio.file.*;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
class GeneralAnimationMappingStoreTest {
    @TempDir Path temp;
    final Hash256 model=new Hash256(new byte[32]);
    @Test void aliasesRestAndLoopSurviveReloadAndRemoval() throws Exception {
        var file=temp.resolve("mapping.json");var store=new GeneralAnimationMappingStore(file);
        var dance=new GeneralAnimationMappingStore.Entry("motion-12",0,true);
        store.put(model,"dance",dance);store.put(model,"idle",new GeneralAnimationMappingStore.Entry("",-1,false));
        var reloaded=new GeneralAnimationMappingStore(file);
        assertEquals(dance,reloaded.entries(model).get("dance"));
        assertEquals(ScenePackagePlayback.Selection.REST,reloaded.entries(model).get("idle").selection());
        reloaded.put(model,"dance",null);assertFalse(new GeneralAnimationMappingStore(file).entries(model).containsKey("dance"));
        assertThrows(UnsupportedOperationException.class,()->store.entries(model).clear());
        assertThrows(IllegalArgumentException.class,()->store.put(model,"scene/evil",dance));
    }
    @Test void corruptFileIsPreservedAndFailedSaveCannotPublish() throws Exception {
        var file=temp.resolve("bad.json");Files.writeString(file,"broken");var store=new GeneralAnimationMappingStore(file);
        assertThrows(IOException.class,()->store.put(model,"idle",null));assertEquals("broken",Files.readString(file));
        var parent=temp.resolve("parent");var blocked=new GeneralAnimationMappingStore(parent.resolve("mapping.json"));
        Files.writeString(parent,"not a directory");
        assertThrows(IOException.class,()->blocked.put(model,"dance",new GeneralAnimationMappingStore.Entry("motion",0,false)));
        assertEquals(0,blocked.version());assertTrue(blocked.entries(model).isEmpty());
    }
}
