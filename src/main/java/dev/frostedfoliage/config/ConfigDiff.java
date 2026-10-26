package dev.frostedfoliage.config;

/**
 * What a settings change invalidates. Every {@link SnowConfig} component must affect at least one category below
 * or be listed as simulation-only in ConfigDiffTest, so a new setting cannot silently leave stale snow behind.
 */
public record ConfigDiff(SnowConfig before, SnowConfig after) {
    /** Which blocks count as foliage, independent of the plant toggle. */
    public boolean blockRules() {
        return before.moddedFoliage() != after.moddedFoliage()
                || !before.includeBlocks().equals(after.includeBlocks()) || !before.excludeBlocks().equals(after.excludeBlocks());
    }

    /** Which blocks receive foliage snow. */
    public boolean foliageRules() { return blockRules() || before.plantsEnabled() != after.plantsEnabled(); }

    /** Inputs to the generated snow masks; these need a resource reload. */
    public boolean textures() {
        return before.snowDepth() != after.snowDepth() || before.plantSnowDepth() != after.plantSnowDepth()
                || before.plantsEnabled() != after.plantsEnabled() || !before.extraTextures().equals(after.extraTextures());
    }

    /** Captured per-block exposure samples. */
    public boolean exposure() {
        return foliageRules() || before.skyExposureSensitivity() != after.skyExposureSensitivity();
    }

    /** Chunk meshes that may contain foliage snow. */
    public boolean appearance() {
        return foliageRules() || before.enabled() != after.enabled() || before.maximumCoverage() != after.maximumCoverage()
                || before.plantMaximumCoverage() != after.plantMaximumCoverage() || before.whiteness() != after.whiteness()
                || before.variation() != after.variation() || before.preferTopFaces() != after.preferTopFaces()
                || before.snowDepth() != after.snowDepth() || before.plantSnowDepth() != after.plantSnowDepth();
    }

    /** Captured ground snow beneath trees and plants. */
    public boolean groundSnow() {
        return blockRules() || before.enabled() != after.enabled() || before.treeGroundSnow() != after.treeGroundSnow()
                || before.plantGroundSnow() != after.plantGroundSnow();
    }

    public boolean cacheSize() { return before.maxCachedSections() != after.maxCachedSections(); }
}
