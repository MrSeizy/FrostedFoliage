package dev.frostedfoliage;

import dev.frostedfoliage.core.PlantSnow;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlantSnowTest {
    private static final int SNOW = 0x9fddeeff;

    @Test void zeroAndFullDepthPreserveTransparencyAndPixelColor() {
        var span = new PlantSnow.Span(0, 1, 0);
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) {
            assertEquals(0, PlantSnow.pixel(SNOW, x, y, 16, 16, 0, span, 0));
            assertEquals(SNOW, PlantSnow.pixel(SNOW, x, y, 16, 16, 1, span, 0));
            assertEquals(0, PlantSnow.pixel(0x00ddeeff, x, y, 16, 16, 1, span, 0));
        }
    }

    @Test void shortPlantsUseVisibleHeightInsteadOfAnEmptyUpperHalf() {
        var shortPlant = new PlantSnow.Span(0, 0.5, 0);
        int coated = 0;
        for (int y = 8; y < 16; y++) for (int x = 0; x < 16; x++) {
            int pixel = PlantSnow.pixel(SNOW, x, y, 16, 16, 0.5, shortPlant, 0);
            if (y >= 12) assertEquals(0, pixel, "The bottom half must stay clear");
            if (pixel != 0) coated++;
        }
        assertTrue(coated > 0 && coated < 64, "Frost reaches visible tips and tapers before the midpoint");
    }

    @Test void aTallPlantHasOneCoatingRatherThanOnePerBlock() {
        var lower = new PlantSnow.Span(0, 2, 0);
        var upper = new PlantSnow.Span(0, 2, 1);
        int top = 0;
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) {
            assertEquals(0, PlantSnow.pixel(SNOW, x, y, 16, 16, 0.5, lower, 0));
            if (PlantSnow.pixel(SNOW, x, y, 16, 16, 0.5, upper, 0) != 0) top++;
        }
        assertTrue(top > 128 && top < 256, "The upper half has a solid top and a dithered lower edge");
    }

    @Test void increasedPlantDepthNeverRemovesExistingSnowPixels() {
        for (int offset = 0; offset <= 1; offset++) {
            var span = new PlantSnow.Span(0.125, 1.875, offset);
            for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) {
                boolean covered = false;
                for (int percent = 0; percent <= 100; percent++) {
                    int pixel = PlantSnow.pixel(SNOW, x, y, 16, 16, percent / 100.0, span, 1);
                    if (covered) assertEquals(SNOW, pixel);
                    covered |= pixel != 0;
                }
            }
        }
    }
}
