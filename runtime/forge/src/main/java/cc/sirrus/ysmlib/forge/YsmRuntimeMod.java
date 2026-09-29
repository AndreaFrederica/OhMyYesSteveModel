package cc.sirrus.ysmlib.forge;

import cc.sirrus.ysmlib.YsmRuntime;
import net.minecraftforge.fml.common.Mod;

/** The prerequisite owns portable services; loading it never loads an official YSM DLL. */
@Mod(YsmRuntime.MOD_ID)
public final class YsmRuntimeMod {
    public YsmRuntimeMod() {
        YsmRuntime.archives();
    }
}
