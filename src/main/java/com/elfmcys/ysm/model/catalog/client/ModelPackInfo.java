package com.elfmcys.ysm.model.catalog.client;

import com.elfmcys.ysm.client.texture.CustomTexture;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

public class ModelPackInfo {
    /**
     * 示例：dir1/dir2/dir3/，
     * 开头必定没有 '/' 而末尾必定有,
     * 不论当前操作系统是什么都以 '/' 作为分隔符。
     */
    private final String hierarchy;
    private final String name;
    private final String desc;
    /**
     * 最大 260 x 450，是游戏分辨率为 2k 且界面尺寸为 5 时 ModelButton 的大小。
     */
    private volatile @Nullable CustomTexture icon;
    private final @Nullable Map<String, Map<String, String>> lang;

    public ModelPackInfo(String hierarchy, String name, String desc, @Nullable CustomTexture icon, @Nullable Map<String, Map<String, String>> lang) {
        this.hierarchy = hierarchy;
        this.name = name;
        this.desc = desc;
        this.icon = icon;
        this.lang = lang;
    }

    @NotNull
    public String hierarchy() {
        return hierarchy;
    }

    @NotNull
    public String name() {
        return name;
    }

    @Nullable
    public String desc() {
        return desc;
    }

    @Nullable
    public CustomTexture icon() {
        return icon;
    }

    public void setIcon(@Nullable CustomTexture icon) {
        this.icon = icon;
    }

    @Nullable
    public Map<String, Map<String, String>> lang() {
        return lang;
    }
}
