package dev.frostedfoliage;

import dev.frostedfoliage.core.SnowMath;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class QuadDepthEdgeTest {
    // A side face's vertices: top-left, bottom-left, bottom-right, top-right.
    private static final float[] SIDE_Y = {1, 0, 0, 1};

    @Test void standardSideFaceTapersTowardTheTextureBottom() {
        assertEquals(1, SnowMath.quadDepthEdge(SIDE_Y, new float[]{0, 0, 1, 1}, new float[]{0, 1, 1, 0}));
    }

    @Test void flippedAndRotatedUvsFollowWorldDown() {
        assertEquals(2, SnowMath.quadDepthEdge(SIDE_Y, new float[]{0, 0, 1, 1}, new float[]{1, 0, 0, 1}));
        assertEquals(3, SnowMath.quadDepthEdge(SIDE_Y, new float[]{0, 1, 1, 0}, new float[]{0, 0, 1, 1}));
        assertEquals(4, SnowMath.quadDepthEdge(SIDE_Y, new float[]{1, 0, 0, 1}, new float[]{1, 1, 0, 0}));
    }

    @Test void partialHeightQuadsKeepTheirMatchingTaper() {
        assertEquals(1, SnowMath.quadDepthEdge(new float[]{1, 0.5f, 0.5f, 1}, new float[]{0, 0, 1, 1}, new float[]{0, 0.5f, 0.5f, 0}));
    }

    @Test void shiftedUvsFallBackToGeometricClipping() {
        assertEquals(0, SnowMath.quadDepthEdge(SIDE_Y, new float[]{0, 0, 1, 1}, new float[]{0.25f, 1, 1, 0.25f}));
        assertEquals(0, SnowMath.quadDepthEdge(new float[]{1, 0.5f, 0.5f, 1}, new float[]{0, 0, 1, 1}, new float[]{0, 1, 1, 0}));
    }
}
