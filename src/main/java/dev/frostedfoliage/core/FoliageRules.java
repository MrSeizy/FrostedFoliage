package dev.frostedfoliage.core;

import dev.frostedfoliage.config.SnowConfig;

public final class FoliageRules {
    private FoliageRules() { }
    public static boolean includes(String id, boolean leafClassOrTag, SnowConfig config) {
        return includes(id, leafClassOrTag, false, config);
    }
    public static boolean includes(String id, boolean leafClassOrTag, boolean plant, SnowConfig config) {
        if (config.excludeBlocks().contains(id)) return false;
        if (plant && !config.plantsEnabled()) return false;
        if (config.includeBlocks().contains(id)) return true;
        return (leafClassOrTag || plant) && (config.moddedFoliage() || id.startsWith("minecraft:"));
    }
}
