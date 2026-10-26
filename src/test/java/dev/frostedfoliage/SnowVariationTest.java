package dev.frostedfoliage;

import dev.frostedfoliage.core.SnowMath;
import java.util.BitSet;
import java.util.HashSet;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SnowVariationTest {
    @Test void patternsAreStableAndNeverRepeatOnFaceAdjacentBlocks() {
        var used = new HashSet<Integer>();
        for (int x : new int[]{-30000000, -17, -1, 0, 1, 16, 29999999}) {
            for (int y = -64; y < 320; y += 17) for (int z = -24; z <= 24; z++) for (int face = 0; face < 6; face++) {
                int variant = SnowMath.leafVariant(x, y, z, face);
                assertTrue(variant >= 0 && variant < SnowMath.LEAF_VARIANTS);
                assertEquals(variant, SnowMath.leafVariant(x, y, z, face));
                assertNotEquals(variant, SnowMath.leafVariant(x + 1, y, z, face));
                assertNotEquals(variant, SnowMath.leafVariant(x, y + 1, z, face));
                assertNotEquals(variant, SnowMath.leafVariant(x, y, z + 1, face));
                used.add(variant);
            }
        }
        assertEquals(SnowMath.LEAF_VARIANTS, used.size());
    }

    @Test void plantPatternIsIndependentOfPerBlockBuildupSpeed() {
        int[][] counts = new int[2][2];
        for (int x = -64; x < 64; x++) for (int z = -64; z < 64; z++) for (int y = 60; y < 68; y++) {
            int variant = SnowMath.plantVariant(x, y, z);
            assertEquals(variant, SnowMath.plantVariant(x, y, z));
            counts[SnowMath.noise(x, y, z) < 0.5 ? 0 : 1][variant]++;
        }
        for (int[] half : counts) {
            double share = half[0] / (double) (half[0] + half[1]);
            assertEquals(0.5, share, 0.02, "Plant pattern follows buildup noise");
        }
    }

    @Test void everyVariantHasDistinctBuildupAndDepthPatternsWithNestedCoverage() {
        var buildupPatterns = new HashSet<BitSet>();
        var depthPatterns = new HashSet<BitSet>();
        for (int variant = 0; variant < SnowMath.LEAF_VARIANTS; variant++) {
            var buildup = new BitSet(256);
            var depth = new BitSet(256);
            for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) {
                if (SnowMath.snowPixel(0x7f809060, x, y, 16, 16, 8, variant) != 0) buildup.set(y * 16 + x);
                if (SnowMath.depthPixel(0x7f809060, x, y, 16, 16, 0.5, 1, variant) != 0) depth.set(y * 16 + x);
                boolean snowy = false;
                for (int stage = 1; stage <= 16; stage++) {
                    assertEquals(0, SnowMath.snowPixel(0, x, y, 16, 16, stage, variant));
                    int pixel = SnowMath.snowPixel(0x7f809060, x, y, 16, 16, stage, variant);
                    assertTrue(pixel == 0 || pixel >>> 24 == 0x7f);
                    assertFalse(snowy && pixel == 0, "Buildup removed an earlier snow pixel");
                    snowy |= pixel != 0;
                }
                assertTrue(snowy, "Full coverage left a gap");
            }
            assertTrue(buildupPatterns.add(buildup), "Duplicate buildup mask");
            assertTrue(depthPatterns.add(depth), "Duplicate depth mask");
        }
    }
}
