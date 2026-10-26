package dev.frostedfoliage;

import dev.frostedfoliage.config.ConfigStore;
import dev.frostedfoliage.config.SnowConfig;
import dev.frostedfoliage.core.BoundedSectionStore;
import dev.frostedfoliage.core.FoliageRules;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ConfigAndCacheTest {
    @TempDir Path temp;
    @Test void defaultConfigRoundTrips() throws Exception {
        ConfigStore store = new ConfigStore(temp.resolve("frostedfoliage.json"));
        assertEquals(SnowConfig.defaults(), store.load()); assertEquals(SnowConfig.defaults(), store.load());
    }
    @Test void builderIsTheSingleSourceOfDefaultsAndRoundTrips() throws Exception {
        var d = SnowConfig.defaults();
        assertTrue(d.enabled() && d.gradualAccumulation() && d.gradualMelting() && d.moddedFoliage() && d.preferTopFaces());
        assertEquals(1, d.maximumCoverage()); assertEquals(0.9, d.whiteness()); assertEquals(0.2, d.variation());
        assertEquals(0.5, d.snowDepth()); assertEquals(0.35, d.plantSnowDepth()); assertEquals(0.6, d.plantMaximumCoverage());
        assertEquals(180, d.accumulationSeconds()); assertEquals(240, d.meltingSeconds()); assertEquals(30, d.persistenceSeconds());
        assertEquals(0.8, d.skyExposureSensitivity()); assertEquals(SnowConfig.Quality.MEDIUM, d.quality());
        assertEquals(0.5, d.maxVisualUpdatesPerSecond()); assertEquals(4096, d.maxCachedSections());
        assertTrue(d.treeGroundSnow() && d.plantGroundSnow()); assertFalse(d.freezingBiomesStayFrosted());
        var custom = d.toBuilder().whiteness(0.3).freezingBiomesStayFrosted(true).excludeBlocks(java.util.List.of("Fern")).build();
        assertEquals(custom, custom.toBuilder().build());
        assertEquals(java.util.List.of("minecraft:fern"), custom.excludeBlocks());
        var store = new ConfigStore(temp.resolve("builder.json"));
        store.save(custom); assertEquals(custom, store.load());
        Files.writeString(temp.resolve("older.json"), "{\"whiteness\":0.4}");
        assertFalse(new ConfigStore(temp.resolve("older.json")).load().freezingBiomesStayFrosted(), "Older files gain the new option switched off");
    }
    @Test void partialConfigKeepsMeaningfulDefaultsAndClampsValues() throws Exception {
        Path file = temp.resolve("config.json");
        Files.writeString(file, "{\"maximumCoverage\":8,\"accumulationSeconds\":-20,\"maxCachedSections\":999999,\"enabled\":null}");
        var c = new ConfigStore(file).load();
        assertEquals(1, c.maximumCoverage()); assertEquals(1, c.accumulationSeconds());
        assertEquals(16384, c.maxCachedSections()); assertTrue(c.enabled()); assertEquals(240, c.meltingSeconds());
        assertEquals(0.5, c.snowDepth(), "Existing configs inherit the adjustable depth default");
        assertTrue(c.plantsEnabled()); assertEquals(0.35, c.plantSnowDepth()); assertEquals(0.6, c.plantMaximumCoverage());
        assertTrue(c.treeGroundSnow()); assertTrue(c.plantGroundSnow());
    }
    @Test void groundSnowTogglesPersistAndSurviveOtherSettingChanges() throws Exception {
        var store = new ConfigStore(temp.resolve("ground.json"));
        var config = SnowConfig.defaults().withGroundSnow(false, true).withPlants(false, 0.2, 0.4).withSnowDepth(0.8);
        store.save(config);
        assertEquals(config, store.load());
        assertFalse(config.treeGroundSnow()); assertTrue(config.plantGroundSnow()); assertFalse(config.plantsEnabled());
        store.save(config.withGroundSnow(true, false));
        assertTrue(store.load().treeGroundSnow()); assertFalse(store.load().plantGroundSnow());
    }
    @Test void snowDepthPersistsAndIsBounded() throws Exception {
        Path file = temp.resolve("depth.json");
        ConfigStore store = new ConfigStore(file);
        Files.writeString(file, "{\"snowDepth\":0.25}");
        var c = store.load(); assertEquals(0.25, c.snowDepth());
        store.save(c); assertEquals(0.25, store.load().snowDepth());
        Files.writeString(file, "{\"snowDepth\":-1}"); assertEquals(0, store.load().snowDepth());
        Files.writeString(file, "{\"snowDepth\":2}"); assertEquals(1, store.load().snowDepth());
    }
    @Test void malformedConfigIsNotOverwritten() throws Exception {
        Path file = temp.resolve("config.json"); Files.writeString(file, "{broken");
        assertThrows(IOException.class, () -> new ConfigStore(file).load()); assertEquals("{broken", Files.readString(file));
    }
    @Test void identifierListsNormalizeCaseWhitespaceAndDefaultNamespace() throws Exception {
        Path file = temp.resolve("ids.json");
        Files.writeString(file, "{\"excludeBlocks\":[\" Minecraft:Oak_Leaves \",\"oak_leaves\"],\"extraTextures\":[\" BLOCK/SPRUCE_LEAVES \"]}");
        var config = new ConfigStore(file).load();
        assertEquals(java.util.List.of("minecraft:oak_leaves"), config.excludeBlocks());
        assertEquals(java.util.List.of("minecraft:block/spruce_leaves"), config.extraTextures());
        assertFalse(FoliageRules.includes("minecraft:oak_leaves", true, config));
    }
    @Test void invalidIdentifierFailsLoadWithoutDiscardingTheUserEntry() throws Exception {
        Path file = temp.resolve("invalid-id.json");
        String supplied = "{\"excludeBlocks\":[\"minecraft:oak leaves\"]}";
        Files.writeString(file, supplied);
        IOException error = assertThrows(IOException.class, () -> new ConfigStore(file).load());
        assertTrue(error.toString().contains("Invalid block or texture ID"));
        assertEquals(supplied, Files.readString(file));
    }
    @Test void failedLoadBlocksSavingDefaultsUntilTheFileIsRepairedAndReloaded() throws Exception {
        Path file = temp.resolve("protected.json");
        String supplied = "{\"snowDepth\":0.25,\"excludeBlocks\":[\"#minecraft:leaves\"]}";
        Files.writeString(file, supplied);
        var store = new ConfigStore(file);
        assertThrows(IOException.class, store::load);
        assertNotNull(store.loadError());
        assertThrows(IOException.class, () -> store.save(SnowConfig.defaults()));
        assertEquals(supplied, Files.readString(file));
        Files.writeString(file, "{\"snowDepth\":0.25}");
        var repaired = store.load();
        assertNull(store.loadError());
        store.save(repaired);
        assertEquals(0.25, store.load().snowDepth());
    }
    @Test void plantSettingsPersistClampAndRemainIndependentOfLeaves() throws Exception {
        var store = new ConfigStore(temp.resolve("plants.json"));
        var config = SnowConfig.defaults().withPlants(false, 0.35, 0.72).withSnowDepth(0.9);
        store.save(config);
        assertEquals(config, store.load());
        assertEquals(0.35, config.plantSnowDepth()); assertEquals(0.72, config.plantMaximumCoverage());
        assertEquals(0.9, config.snowDepth()); assertFalse(config.plantsEnabled());
        var bounded = config.withPlants(true, 8, -3);
        assertEquals(1, bounded.plantSnowDepth()); assertEquals(0, bounded.plantMaximumCoverage());
        assertEquals(0.9, bounded.snowDepth());
    }
    @Test void plantsRespectTheirToggleModdedPolicyAndExclusions() throws Exception {
        var defaults = SnowConfig.defaults();
        assertTrue(FoliageRules.includes("minecraft:fern", false, true, defaults));
        assertFalse(FoliageRules.includes("minecraft:fern", false, true, defaults.withPlants(false, 0.5, 0.65)));
        assertTrue(FoliageRules.includes("minecraft:oak_leaves", true, false, defaults.withPlants(false, 0.5, 0.65)));
        Path file = temp.resolve("plants-rules.json");
        Files.writeString(file, "{\"moddedFoliage\":false,\"excludeBlocks\":[\"minecraft:fern\"],\"includeBlocks\":[\"minecraft:fern\",\"test:flower\"]}");
        var config = new ConfigStore(file).load();
        assertFalse(FoliageRules.includes("minecraft:fern", false, true, config));
        assertFalse(FoliageRules.includes("test:grass", false, true, config));
        assertTrue(FoliageRules.includes("test:flower", false, true, config));
    }
    @Test void inclusionRulesAreExplicitAndExclusionWins() throws Exception {
        Path file = temp.resolve("config.json");
        Files.writeString(file, "{\"moddedFoliage\":false,\"includeBlocks\":[\"test:bush\",\"test:excluded\"],\"excludeBlocks\":[\"test:excluded\"]}");
        var c = new ConfigStore(file).load();
        assertTrue(FoliageRules.includes("minecraft:oak_leaves", true, c));
        assertFalse(FoliageRules.includes("minecraft:glass", false, c));
        assertFalse(FoliageRules.includes("test:leaf", true, c));
        assertTrue(FoliageRules.includes("test:bush", false, c));
        assertFalse(FoliageRules.includes("test:excluded", true, c));
    }
    @Test void cacheAdmissionIsBoundedAndUnloadFreesCapacity() {
        var cache = new BoundedSectionStore<String>();
        assertTrue(cache.put(1, "one", 2)); assertTrue(cache.put(2, "two", 2)); assertFalse(cache.put(3, "three", 2));
        cache.remove(1); assertTrue(cache.put(3, "three", 2)); assertNull(cache.get(1)); assertEquals(2, cache.size());
    }
    @Test void replacementPublishesUpdatedSnapshotAndWorldClearDropsEverything() {
        var cache = new BoundedSectionStore<String>();
        cache.put(1, "old", 2); cache.put(1, "new", 2); assertEquals("new", cache.get(1));
        cache.clear(); assertNull(cache.get(1)); assertEquals(0, cache.size());
        cache.put(1, "other world", 2); assertEquals("other world", cache.get(1));
    }
    @Test void chunkInvalidationRemovesAllMatchingSections() {
        var cache = new BoundedSectionStore<Integer>();
        cache.put(100, 1, 8); cache.put(101, 1, 8); cache.put(200, 1, 8);
        cache.removeIf(key -> key / 100 == 1); assertNull(cache.get(100)); assertNull(cache.get(101)); assertEquals(1, cache.size());
    }
}
