package dev.frostedfoliage.config;

import java.util.List;

/** Immutable configuration, safe to publish to chunk compilation workers. */
public record SnowConfig(boolean enabled, boolean gradualAccumulation, boolean gradualMelting,
        boolean moddedFoliage, double maximumCoverage, double whiteness, double variation,
        boolean preferTopFaces, double snowDepth, boolean plantsEnabled, double plantSnowDepth, double plantMaximumCoverage,
        double accumulationSeconds, double meltingSeconds,
        double persistenceSeconds, double skyExposureSensitivity, Quality quality,
        double maxVisualUpdatesPerSecond, int maxCachedSections, List<String> includeBlocks,
        List<String> excludeBlocks, List<String> extraTextures, boolean treeGroundSnow, boolean plantGroundSnow) {
    public enum Quality { LOW, MEDIUM, HIGH }
    public static final int MAX_CACHED_SECTIONS = 16384;

    public SnowConfig {
        maximumCoverage = clamp(maximumCoverage, 0, 1);
        whiteness = clamp(whiteness, 0, 1);
        variation = clamp(variation, 0, 1);
        snowDepth = clamp(snowDepth, 0, 1);
        plantSnowDepth = clamp(plantSnowDepth, 0, 1);
        plantMaximumCoverage = clamp(plantMaximumCoverage, 0, 1);
        accumulationSeconds = clamp(accumulationSeconds, 1, 86400);
        meltingSeconds = clamp(meltingSeconds, 1, 86400);
        persistenceSeconds = clamp(persistenceSeconds, 0, 86400);
        skyExposureSensitivity = clamp(skyExposureSensitivity, 0, 1);
        quality = quality == null ? Quality.MEDIUM : quality;
        maxVisualUpdatesPerSecond = clamp(maxVisualUpdatesPerSecond, 0.05, 2);
        maxCachedSections = Math.max(64, Math.min(MAX_CACHED_SECTIONS, maxCachedSections));
        includeBlocks = clean(includeBlocks);
        excludeBlocks = clean(excludeBlocks);
        extraTextures = clean(extraTextures);
    }

    public SnowConfig(boolean enabled, boolean gradualAccumulation, boolean gradualMelting, boolean moddedFoliage,
            double maximumCoverage, double whiteness, double variation, boolean preferTopFaces, double snowDepth,
            boolean plantsEnabled, double plantSnowDepth, double plantMaximumCoverage,
            double accumulationSeconds, double meltingSeconds, double persistenceSeconds,
            double skyExposureSensitivity, Quality quality, double maxVisualUpdatesPerSecond, int maxCachedSections,
            List<String> includeBlocks, List<String> excludeBlocks, List<String> extraTextures) {
        this(enabled, gradualAccumulation, gradualMelting, moddedFoliage, maximumCoverage, whiteness, variation,
                preferTopFaces, snowDepth, plantsEnabled, plantSnowDepth, plantMaximumCoverage, accumulationSeconds,
                meltingSeconds, persistenceSeconds, skyExposureSensitivity, quality, maxVisualUpdatesPerSecond,
                maxCachedSections, includeBlocks, excludeBlocks, extraTextures, true, true);
    }

    /** Existing leaf-only callers inherit the independently configurable plant defaults. */
    public SnowConfig(boolean enabled, boolean gradualAccumulation, boolean gradualMelting, boolean moddedFoliage,
            double maximumCoverage, double whiteness, double variation, boolean preferTopFaces, double snowDepth,
            double accumulationSeconds, double meltingSeconds, double persistenceSeconds,
            double skyExposureSensitivity, Quality quality, double maxVisualUpdatesPerSecond, int maxCachedSections,
            List<String> includeBlocks, List<String> excludeBlocks, List<String> extraTextures) {
        this(enabled, gradualAccumulation, gradualMelting, moddedFoliage, maximumCoverage, whiteness, variation,
                preferTopFaces, snowDepth, true, 0.35, 0.6, accumulationSeconds, meltingSeconds,
                persistenceSeconds, skyExposureSensitivity, quality, maxVisualUpdatesPerSecond, maxCachedSections,
                includeBlocks, excludeBlocks, extraTextures);
    }

    private static List<String> clean(List<String> list) {
        if (list == null) return List.of();
        return list.stream().filter(java.util.Objects::nonNull).map(SnowConfig::normalizeIdentifier)
                .distinct().limit(4096).toList();
    }

    public static String normalizeIdentifier(String value) {
        String id = value.strip().toLowerCase(java.util.Locale.ROOT);
        if (!id.contains(":")) id = "minecraft:" + id;
        if (!id.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) {
            throw new IllegalArgumentException("Invalid block or texture ID: " + value + ". Use namespace:path.");
        }
        return id;
    }

    public static SnowConfig defaults() {
        return new SnowConfig(true, true, true, true, 1, 0.9, 0.2, true, 0.5,
                180, 240, 30, 0.8, Quality.MEDIUM, 0.5, 4096, List.of(), List.of(), List.of());
    }

    public int rebuildsPerTick() { return switch (quality) { case LOW -> 1; case MEDIUM -> 2; case HIGH -> 4; }; }
    public int captureBudget() { return quality == Quality.HIGH ? 2 : 1; }
    public int steps() { return switch (quality) { case LOW -> 4; case MEDIUM -> 8; case HIGH -> 16; }; }

    public SnowConfig withSnowDepth(double value) {
        return new SnowConfig(enabled, gradualAccumulation, gradualMelting, moddedFoliage, maximumCoverage,
                whiteness, variation, preferTopFaces, value, plantsEnabled, plantSnowDepth, plantMaximumCoverage,
                accumulationSeconds, meltingSeconds,
                persistenceSeconds, skyExposureSensitivity, quality, maxVisualUpdatesPerSecond, maxCachedSections,
                includeBlocks, excludeBlocks, extraTextures, treeGroundSnow, plantGroundSnow);
    }

    public SnowConfig withPlants(boolean enabled, double depth, double coverage) {
        return new SnowConfig(this.enabled, gradualAccumulation, gradualMelting, moddedFoliage, maximumCoverage,
                whiteness, variation, preferTopFaces, snowDepth, enabled, depth, coverage,
                accumulationSeconds, meltingSeconds, persistenceSeconds, skyExposureSensitivity, quality,
                maxVisualUpdatesPerSecond, maxCachedSections, includeBlocks, excludeBlocks, extraTextures, treeGroundSnow, plantGroundSnow);
    }

    public SnowConfig withGroundSnow(boolean trees, boolean plants) {
        return new SnowConfig(enabled, gradualAccumulation, gradualMelting, moddedFoliage, maximumCoverage,
                whiteness, variation, preferTopFaces, snowDepth, plantsEnabled, plantSnowDepth, plantMaximumCoverage,
                accumulationSeconds, meltingSeconds, persistenceSeconds, skyExposureSensitivity, quality,
                maxVisualUpdatesPerSecond, maxCachedSections, includeBlocks, excludeBlocks, extraTextures, trees, plants);
    }

    public static double clamp(double value, double min, double max) {
        return Double.isFinite(value) ? Math.max(min, Math.min(max, value)) : min;
    }
}
