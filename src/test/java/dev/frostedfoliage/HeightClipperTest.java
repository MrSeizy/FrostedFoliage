package dev.frostedfoliage;

import dev.frostedfoliage.core.HeightClipper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HeightClipperTest {
    @Test void halfHeightKeepsUpperGeometryAndInterpolatesRotatedUvs() {
        var c = new HeightClipper();
        float[] y = {1, 0, 0, 1}, u = {0, 1, 1, 0};
        int count = c.clip(y[0], y[1], y[2], y[3], 0.5f);
        assertEquals(4, count);
        int edgeVertices = 0;
        for (int i = 0; i < count; i++) {
            float clippedY = c.interpolate(i, y[c.from(i)], y[c.to(i)]);
            assertTrue(clippedY >= 0.5);
            if (clippedY == 0.5f) {
                edgeVertices++;
                assertEquals(0.5f, c.interpolate(i, u[c.from(i)], u[c.to(i)]));
            }
        }
        assertEquals(2, edgeVertices);
    }

    @Test void outsideAndZeroAreaFacesAreRemovedAndUncutFacesSurvive() {
        var c = new HeightClipper();
        assertEquals(0, c.clip(0, 0, 0, 0, 0.5f));
        assertEquals(0, c.clip(1, 0, 0, 1, 1));
        assertEquals(4, c.clip(1, 0, 0, 1, 0));
        for (int i = 0; i < 4; i++) assertEquals(c.from(i), c.to(i));
    }

    @Test void slopedQuadsCanProduceTrianglesOrPentagonsWithoutLosingVertices() {
        var c = new HeightClipper();
        assertEquals(3, c.clip(1, 0.5f, 0, 0.5f, 0.75f));
        assertEquals(5, c.clip(1, 0.5f, 0, 0.5f, 0.25f));
    }

    @Test void boundaryVerticesAreNotDuplicated() {
        var c = new HeightClipper();
        assertEquals(3, c.clip(1, 0.5f, 0, 0.5f, 0.5f));
    }
}
