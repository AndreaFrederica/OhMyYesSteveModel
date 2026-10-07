package com.elfmcys.ysm.util;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.client.match.EntityMatchKind;
import com.elfmcys.ysm.client.match.EntityMatchRegistry;
import com.elfmcys.ysm.client.match.MatchConstants;
import com.google.common.collect.Sets;
import it.unimi.dsi.fastutil.Pair;
import net.minecraft.util.ResourceLocation;

import java.util.Collection;
import java.util.Set;

public final class ModelIdUtil {
    private static final Set<String> KNOWN_EXT = Sets.newHashSet(
            ".zip",
            ".7z",
            ".ysm",
            ".mxc"
    );

    /**
     * "dir1/dir2/id" -> {"id", "dir1/dir2/"}
     * <p/>
     * "id" -> {"id", ""}
     */
    public static Pair<String, String> splitModelPath(String modelPath) {
        var lastSlash = modelPath.lastIndexOf('/');
        if (lastSlash == -1) {
            return Pair.of(modelPath, "");
        } else {
            return Pair.of(modelPath.substring(lastSlash + 1), modelPath.substring(0, lastSlash + 1));
        }
    }

    public static String getFileNameFromPath(String modelPath) {
        var lastSlash = modelPath.lastIndexOf('/');
        String modelName;
        if (lastSlash == -1) {
            modelName = modelPath;
        } else {
            modelName = modelPath.substring(lastSlash + 1);
        }
        var lastDot = modelName.lastIndexOf('.');
        if (lastDot < 1 || !KNOWN_EXT.contains(modelName.substring(lastDot).toLowerCase())) {
            return modelName;
        }

        return modelName.substring(0, lastDot);
    }

    public static String getLastFolderName(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        int idx = trimmed.lastIndexOf('/');
        return idx >= 0 ? trimmed.substring(idx + 1) : trimmed;
    }

    public static ResourceLocation getModelPackIconId(String hierarchy) {
        return new ResourceLocation(YesSteveModel.MOD_ID, "model_pack_icon/" + hierarchy.hashCode());
    }

    public static boolean entityIdMatchesAny(ResourceLocation entityType, Collection<String> matches) {
        String typeId = entityType.toString();
        var rules = EntityMatchRegistry.RULES.get(EntityMatchKind.TAG);
        for (String match : matches) {
            if (match.startsWith(MatchConstants.TAG_PRE)) {
                if (rules == null) {
                    continue;
                }
                for (var rule : rules) {
                    if (rule.name().equalsIgnoreCase(EntityMatchKind.TAG.normalize(match))) {
                        return true;
                    }
                }
            } else {
                if (typeId.equals(IdUtil.normalize(match))) {
                    return true;
                }
            }
        }
        return false;
    }
}
