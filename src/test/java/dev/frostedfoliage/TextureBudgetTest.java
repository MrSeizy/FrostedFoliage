package dev.frostedfoliage;

import dev.frostedfoliage.core.TextureBudget;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TextureBudgetTest {
    private List<TextureBudget.Source> foliage(int size) {
        var sources = new ArrayList<TextureBudget.Source>();
        for (int i = 0; i < 14; i++) sources.add(new TextureBudget.Source((long) size * size, true, 0));
        for (int i = 0; i < 65; i++) sources.add(new TextureBudget.Source((long) size * size, false, 2));
        return sources;
    }

    @Test void vanillaRetainsFullDetailAndDoubleResolutionKeepsEverySource() {
        assertEquals(new TextureBudget.Profile(16, 5), TextureBudget.choose(foliage(16), true));
        var sources = foliage(32);
        var profile = TextureBudget.choose(sources, true);
        assertEquals(new TextureBudget.Profile(8, 5), profile);
        assertTrue(sources.stream().mapToLong(s -> s.texels(profile)).sum() <= TextureBudget.MAX_TEXELS);
        assertEquals(16, profile.stage(16));
    }
    @Test void largerPackFallsBackToClippedEdgesBeforeDroppingSpecies() {
        var sources = foliage(64);
        var profile = TextureBudget.choose(sources, true);
        assertEquals(new TextureBudget.Profile(4, 1), profile);
        assertTrue(sources.stream().mapToLong(s -> s.texels(profile)).sum() <= TextureBudget.MAX_TEXELS);
    }
    @Test void leafPlantAndParticleSourcesShareOnePixelCap() {
        assertEquals(65536, TextureBudget.MAX_SOURCE_PIXELS);
        // One capped leaf source still needs the budget's profile fallback: the cap bounds decoding, not masks.
        assertTrue(new TextureBudget.Source(TextureBudget.MAX_SOURCE_PIXELS, true, 0).texels(new TextureBudget.Profile(16, 5)) > TextureBudget.MAX_TEXELS);
    }
    @Test void stageMappingIsMonotonicAndOnlyReferencesGeneratedLevels() {
        for (int stages : new int[]{1, 2, 4, 8, 16}) {
            var profile = new TextureBudget.Profile(stages, 1);
            int previous = 0;
            for (int stage = 0; stage <= 16; stage++) {
                int mapped = profile.stage(stage);
                assertTrue(mapped >= previous && mapped <= 16);
                assertEquals(0, mapped % profile.step());
                previous = mapped;
            }
            assertEquals(0, profile.stage(0)); assertEquals(16, profile.stage(16));
        }
    }
    @Test void animationFramesAndSharedLeafPlantSpritesCountAgainstTheSameBudget() {
        var source = new TextureBudget.Source(32 * 32 * 4, true, 3);
        assertEquals(4096L * 8 * (8 * 5 + 2 * 3), source.texels(new TextureBudget.Profile(8, 5)));
        var profile = TextureBudget.choose(java.util.Collections.nCopies(256, source), true);
        assertEquals(new TextureBudget.Profile(1, 1), profile);
    }
}
