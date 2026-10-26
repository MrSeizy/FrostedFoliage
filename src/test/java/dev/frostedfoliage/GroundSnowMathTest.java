package dev.frostedfoliage;

import dev.frostedfoliage.core.GroundSnowMath;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GroundSnowMathTest {
    private static final int WIDTH = 32, CENTER = 16;
    private final int[] heights = new int[WIDTH * WIDTH];
    private final byte[] snow = new byte[WIDTH * WIDTH];
    { java.util.Arrays.fill(heights, 63); }

    private void put(int dx, int dz, int layers) { snow[(CENTER + dz) * WIDTH + CENTER + dx] = (byte) layers; }
    private int layers() { return GroundSnowMath.nearestLayers(heights, snow, WIDTH, CENTER, CENTER, 63); }

    @Test void nearestSnowWinsOverThickerDistantSnow() {
        put(2, 0, 8); put(0, 1, 2);
        assertEquals(2, layers());
    }

    @Test void equallyNearColumnsTakeTheThinnestLayer() {
        put(1, 0, 5); put(-1, 0, 3); put(0, 1, 7);
        assertEquals(3, layers());
    }

    @Test void ignoresTheColumnItselfDistantSnowAndOtherHeights() {
        put(0, 0, 8);
        assertEquals(0, layers());
        put(GroundSnowMath.RADIUS, 1, 4);
        assertEquals(0, layers(), "Outside the circular radius");
        put(0, 2, 6); heights[(CENTER + 2) * WIDTH + CENTER] = 65;
        assertEquals(0, layers(), "Two blocks higher is a different surface");
        heights[(CENTER + 2) * WIDTH + CENTER] = 64;
        assertEquals(6, layers(), "One block of unevenness still counts");
    }

    @Test void sidesHideOnlyBehindSomethingAtLeastAsTall() {
        assertTrue(GroundSnowMath.sideHidden(3, true, 0, 0), "Solid block");
        assertTrue(GroundSnowMath.sideHidden(3, false, 3, 0), "Equal real snow");
        assertTrue(GroundSnowMath.sideHidden(3, false, 0, 8), "Taller virtual snow");
        assertFalse(GroundSnowMath.sideHidden(3, false, 2, 0), "Lower real snow leaves the top of the side exposed");
        assertFalse(GroundSnowMath.sideHidden(3, false, 0, 2), "Lower virtual snow leaves the top of the side exposed");
        assertFalse(GroundSnowMath.sideHidden(1, false, 0, 0), "Nothing beside it");
    }

    @Test void gridEdgesAreSafe() {
        int[] small = new int[4]; byte[] smallSnow = {0, 2, 0, 0};
        assertEquals(2, GroundSnowMath.nearestLayers(small, smallSnow, 2, 0, 0, 0));
        assertEquals(2, GroundSnowMath.nearestLayers(small, smallSnow, 2, 1, 1, 0));
    }
}
