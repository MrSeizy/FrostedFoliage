package dev.frostedfoliage;

import dev.frostedfoliage.core.SnowMath;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SnowDepthMaskTest {
    private static final int PIXEL = 0xaaccddee;

    @Test void edgeFadesFromSolidThroughScatteredPixelsToClear() {
        int upper = 0, transition = 0, below = 0;
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) {
            int pixel = SnowMath.depthPixel(PIXEL, x, y, 16, 16, 0.5, 1, 0);
            if (pixel != 0) {
                assertEquals(PIXEL, pixel, "Dithering must preserve source alpha and color");
                if (y < 4) upper++; else if (y < 8) transition++; else below++;
            }
        }
        assertEquals(64, upper); assertEquals(0, below);
        assertTrue(transition > 10 && transition < 54, "Transition must contain both snow and gaps");
    }

    @Test void sourceHolesAndFullDepthRemainUnchanged() {
        assertEquals(0, SnowMath.depthPixel(0, 0, 0, 16, 16, 0.5, 1, 0));
        assertEquals(PIXEL, SnowMath.depthPixel(PIXEL, 0, 15, 16, 16, 1, 1, 0));
        assertEquals(PIXEL, SnowMath.depthPixel(PIXEL, 0, 15, 16, 16, 0, 0, 0));
        assertEquals(0, SnowMath.depthPixel(PIXEL, 0, 0, 16, 16, 0, 1, 0));
    }

    @Test void increasingDepthNeverRemovesSnowAndWeatherMasksStayNested() {
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) {
            boolean snowy = false;
            for (int d = 0; d <= 16; d++) {
                int pixel = SnowMath.depthPixel(PIXEL, x, y, 16, 16, d / 16.0, 1, 0);
                if (snowy) assertNotEquals(0, pixel);
                snowy |= pixel != 0;
            }
            snowy = false;
            for (int stage = 1; stage <= 16; stage++) {
                int pixel = SnowMath.depthPixel(SnowMath.snowPixel(PIXEL, x, y, 16, 16, stage, 0), x, y, 16, 16, 0.5, 1, 0);
                if (snowy) assertNotEquals(0, pixel);
                snowy |= pixel != 0;
            }
        }
    }

    @Test void rotatedAndFlippedUvsChooseTheWorldUpperEdge() {
        assertEquals(1, SnowMath.depthEdge(1, 0, -1));
        assertEquals(2, SnowMath.depthEdge(1, 0, 1));
        assertEquals(3, SnowMath.depthEdge(1, -1, 0));
        assertEquals(4, SnowMath.depthEdge(1, 1, 0));
        assertEquals(1, SnowMath.depthEdge(-1, 0, 1));
        for (int edge = 1; edge <= 4; edge++) {
            for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) {
                double worldY = switch (edge) { case 1 -> 1 - (y + 0.5) / 16; case 2 -> (y + 0.5) / 16; case 3 -> 1 - (x + 0.5) / 16; default -> (x + 0.5) / 16; };
                int pixel = SnowMath.depthPixel(PIXEL, x, y, 16, 16, 0.5, edge, 0);
                if (worldY < 0.5) assertEquals(0, pixel);
                if (worldY >= 0.75) assertEquals(PIXEL, pixel);
            }
        }
    }

    @Test void croppedCustomMappingsFallBackInsteadOfErasingVisibleSnow() {
        assertTrue(SnowMath.depthMappingMatches(1, 1, 0, 0));
        assertTrue(SnowMath.depthMappingMatches(1, 0.5, 1, 0.5));
        assertTrue(SnowMath.depthMappingMatches(3, 0.75, 0.25, 0));
        assertFalse(SnowMath.depthMappingMatches(1, 0.5, 0, 1));
        assertFalse(SnowMath.depthMappingMatches(1, 1, 1, 0.5));
    }
}
