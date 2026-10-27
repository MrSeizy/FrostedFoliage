package dev.frostedfoliage.config;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Immutable configuration, safe to publish to chunk compilation workers. Defaults live in {@link Builder}. */
public record SnowConfig(boolean enabled, boolean gradualAccumulation, boolean gradualMelting,
        boolean moddedFoliage, double maximumCoverage, double whiteness, double variation,
        boolean preferTopFaces, double snowDepth, boolean plantsEnabled, double plantSnowDepth, double plantMaximumCoverage,
        double accumulationSeconds, double meltingSeconds,
        double persistenceSeconds, double skyExposureSensitivity, Quality quality,
        double maxVisualUpdatesPerSecond, int maxCachedSections, List<String> includeBlocks,
        List<String> excludeBlocks, List<String> extraTextures, boolean treeGroundSnow, boolean plantGroundSnow,
        boolean freezingBiomesStayFrosted) {
    public enum Quality { LOW, MEDIUM, HIGH }
    public static final double MIN_SECONDS = 1, MAX_SECONDS = 86400;
    public static final double MIN_UPDATES_PER_SECOND = 0.05, MAX_UPDATES_PER_SECOND = 2;
    public static final int MIN_CACHED_SECTIONS = 64, MAX_CACHED_SECTIONS = 16384;
    public static final int MAX_LIST_ENTRIES = 4096;
    private static final Logger LOGGER = LoggerFactory.getLogger("frostedfoliage");

    public SnowConfig {
        maximumCoverage = clamp(maximumCoverage, 0, 1);
        whiteness = clamp(whiteness, 0, 1);
        variation = clamp(variation, 0, 1);
        snowDepth = clamp(snowDepth, 0, 1);
        plantSnowDepth = clamp(plantSnowDepth, 0, 1);
        plantMaximumCoverage = clamp(plantMaximumCoverage, 0, 1);
        accumulationSeconds = clamp(accumulationSeconds, MIN_SECONDS, MAX_SECONDS);
        meltingSeconds = clamp(meltingSeconds, MIN_SECONDS, MAX_SECONDS);
        persistenceSeconds = clamp(persistenceSeconds, 0, MAX_SECONDS);
        skyExposureSensitivity = clamp(skyExposureSensitivity, 0, 1);
        quality = quality == null ? Quality.MEDIUM : quality;
        maxVisualUpdatesPerSecond = clamp(maxVisualUpdatesPerSecond, MIN_UPDATES_PER_SECOND, MAX_UPDATES_PER_SECOND);
        maxCachedSections = Math.max(MIN_CACHED_SECTIONS, Math.min(MAX_CACHED_SECTIONS, maxCachedSections));
        includeBlocks = clean(includeBlocks);
        excludeBlocks = clean(excludeBlocks);
        extraTextures = clean(extraTextures);
    }

    private static List<String> clean(List<String> list) {
        if (list == null) return List.of();
        List<String> ids = list.stream().filter(Objects::nonNull).map(SnowConfig::normalizeIdentifier).distinct().toList();
        if (ids.size() <= MAX_LIST_ENTRIES) return ids;
        LOGGER.warn("Keeping the first {} of {} block or texture IDs", MAX_LIST_ENTRIES, ids.size());
        return ids.subList(0, MAX_LIST_ENTRIES);
    }

    public static String normalizeIdentifier(String value) {
        String id = value.strip().toLowerCase(Locale.ROOT);
        if (!id.contains(":")) id = "minecraft:" + id;
        if (!id.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) {
            throw new IllegalArgumentException("Invalid block or texture ID: " + value + ". Use namespace:path.");
        }
        return id;
    }

    public static SnowConfig defaults() { return builder().build(); }
    public static Builder builder() { return new Builder(); }
    public Builder toBuilder() { return new Builder(this); }

    public int rebuildsPerTick() { return switch (quality) { case LOW -> 1; case MEDIUM -> 2; case HIGH -> 4; }; }
    public int captureBudget() { return quality == Quality.HIGH ? 2 : 1; }
    public int steps() { return switch (quality) { case LOW -> 4; case MEDIUM -> 8; case HIGH -> 16; }; }

    public SnowConfig withSnowDepth(double value) { return toBuilder().snowDepth(value).build(); }

    public SnowConfig withPlants(boolean enabled, double depth, double coverage) {
        return toBuilder().plantsEnabled(enabled).plantSnowDepth(depth).plantMaximumCoverage(coverage).build();
    }

    public SnowConfig withGroundSnow(boolean trees, boolean plants) {
        return toBuilder().treeGroundSnow(trees).plantGroundSnow(plants).build();
    }

    public static double clamp(double value, double min, double max) {
        return Double.isFinite(value) ? Math.max(min, Math.min(max, value)) : min;
    }

    /** The single source of default values; the config file, settings screen and tests all start here. */
    public static final class Builder {
        private boolean enabled = true, gradualAccumulation = true, gradualMelting = true, moddedFoliage = true;
        private double maximumCoverage = 1, whiteness = 0.9, variation = 0.2;
        private boolean preferTopFaces = true;
        private double snowDepth = 0.5;
        private boolean plantsEnabled = true;
        private double plantSnowDepth = 0.35, plantMaximumCoverage = 0.6;
        private double accumulationSeconds = 180, meltingSeconds = 240, persistenceSeconds = 30;
        private double skyExposureSensitivity = 0.8;
        private Quality quality = Quality.MEDIUM;
        private double maxVisualUpdatesPerSecond = 0.5;
        private int maxCachedSections = 4096;
        private List<String> includeBlocks = List.of(), excludeBlocks = List.of(), extraTextures = List.of();
        private boolean treeGroundSnow = true, plantGroundSnow = true, freezingBiomesStayFrosted = false;

        private Builder() { }
        private Builder(SnowConfig c) {
            enabled = c.enabled; gradualAccumulation = c.gradualAccumulation; gradualMelting = c.gradualMelting;
            moddedFoliage = c.moddedFoliage; maximumCoverage = c.maximumCoverage; whiteness = c.whiteness;
            variation = c.variation; preferTopFaces = c.preferTopFaces; snowDepth = c.snowDepth;
            plantsEnabled = c.plantsEnabled; plantSnowDepth = c.plantSnowDepth; plantMaximumCoverage = c.plantMaximumCoverage;
            accumulationSeconds = c.accumulationSeconds; meltingSeconds = c.meltingSeconds;
            persistenceSeconds = c.persistenceSeconds; skyExposureSensitivity = c.skyExposureSensitivity;
            quality = c.quality; maxVisualUpdatesPerSecond = c.maxVisualUpdatesPerSecond;
            maxCachedSections = c.maxCachedSections; includeBlocks = c.includeBlocks; excludeBlocks = c.excludeBlocks;
            extraTextures = c.extraTextures; treeGroundSnow = c.treeGroundSnow; plantGroundSnow = c.plantGroundSnow;
            freezingBiomesStayFrosted = c.freezingBiomesStayFrosted;
        }

        public Builder enabled(boolean value) { enabled = value; return this; }
        public Builder gradualAccumulation(boolean value) { gradualAccumulation = value; return this; }
        public Builder gradualMelting(boolean value) { gradualMelting = value; return this; }
        public Builder moddedFoliage(boolean value) { moddedFoliage = value; return this; }
        public Builder maximumCoverage(double value) { maximumCoverage = value; return this; }
        public Builder whiteness(double value) { whiteness = value; return this; }
        public Builder variation(double value) { variation = value; return this; }
        public Builder preferTopFaces(boolean value) { preferTopFaces = value; return this; }
        public Builder snowDepth(double value) { snowDepth = value; return this; }
        public Builder plantsEnabled(boolean value) { plantsEnabled = value; return this; }
        public Builder plantSnowDepth(double value) { plantSnowDepth = value; return this; }
        public Builder plantMaximumCoverage(double value) { plantMaximumCoverage = value; return this; }
        public Builder accumulationSeconds(double value) { accumulationSeconds = value; return this; }
        public Builder meltingSeconds(double value) { meltingSeconds = value; return this; }
        public Builder persistenceSeconds(double value) { persistenceSeconds = value; return this; }
        public Builder skyExposureSensitivity(double value) { skyExposureSensitivity = value; return this; }
        public Builder quality(Quality value) { quality = value; return this; }
        public Builder maxVisualUpdatesPerSecond(double value) { maxVisualUpdatesPerSecond = value; return this; }
        public Builder maxCachedSections(int value) { maxCachedSections = value; return this; }
        public Builder includeBlocks(List<String> value) { includeBlocks = value; return this; }
        public Builder excludeBlocks(List<String> value) { excludeBlocks = value; return this; }
        public Builder extraTextures(List<String> value) { extraTextures = value; return this; }
        public Builder treeGroundSnow(boolean value) { treeGroundSnow = value; return this; }
        public Builder plantGroundSnow(boolean value) { plantGroundSnow = value; return this; }
        public Builder freezingBiomesStayFrosted(boolean value) { freezingBiomesStayFrosted = value; return this; }

        public SnowConfig build() {
            return new SnowConfig(enabled, gradualAccumulation, gradualMelting, moddedFoliage, maximumCoverage,
                    whiteness, variation, preferTopFaces, snowDepth, plantsEnabled, plantSnowDepth, plantMaximumCoverage,
                    accumulationSeconds, meltingSeconds, persistenceSeconds, skyExposureSensitivity, quality,
                    maxVisualUpdatesPerSecond, maxCachedSections, includeBlocks, excludeBlocks, extraTextures,
                    treeGroundSnow, plantGroundSnow, freezingBiomesStayFrosted);
        }
    }
}
