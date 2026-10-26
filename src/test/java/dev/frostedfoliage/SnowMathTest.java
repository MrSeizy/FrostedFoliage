package dev.frostedfoliage;

import dev.frostedfoliage.config.ConfigStore;
import dev.frostedfoliage.config.SnowConfig;
import dev.frostedfoliage.core.SnowMath;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SnowMathTest {
    @TempDir Path temp;
    private SnowConfig config(String json) throws Exception {
        Path file = temp.resolve("config.json"); Files.writeString(file, json); return new ConfigStore(file).load();
    }
    @Test void snowfallAccumulatesAtConfiguredRate() {
        assertEquals(0.5, SnowMath.advance(0, 1, 90, 0, false, SnowConfig.defaults()), 1e-9);
        assertEquals(0.25, SnowMath.advance(0, 0.5, 90, 0, false, SnowConfig.defaults()), 1e-9);
    }
    @Test void accumulationAndMeltingStayBounded() {
        assertEquals(1, SnowMath.advance(0.8, 1, 1000, 0, false, SnowConfig.defaults()));
        assertEquals(0, SnowMath.advance(0.2, 0, 1000, 2000, false, SnowConfig.defaults()));
    }
    @Test void freezingSnowLastsLonger() {
        double cold = SnowMath.advance(1, 0, 120, 180, false, SnowConfig.defaults());
        double frozen = SnowMath.advance(1, 0, 120, 180, true, SnowConfig.defaults());
        assertEquals(0.5, cold, 1e-9); assertEquals(0.825, frozen, 1e-9);
    }
    @Test void persistenceBoundaryDoesNotMeltForWholeTimestep() {
        assertEquals(1, SnowMath.advance(1, 0, 1, 30, false, SnowConfig.defaults()));
        assertEquals(1 - 0.5 / 240, SnowMath.advance(1, 0, 1, 30.5, false, SnowConfig.defaults()), 1e-9);
    }
    @Test void instantModesRespectPersistence() throws Exception {
        var c = config("{\"gradualAccumulation\":false,\"gradualMelting\":false}");
        assertEquals(1, SnowMath.advance(0, 1, 0.05, 0, false, c));
        assertEquals(1, SnowMath.advance(1, 0, 0.05, 20, false, c));
        assertEquals(0, SnowMath.advance(1, 0, 0.05, 31, false, c));
    }
    @Test void biomesThatSnowAtSeaLevelMeltAsFreezing() {
        // Vanilla 26.3 base temperatures: snowy_taiga, grove, frozen_peaks, then snowy_plains/ice_spikes/frozen_river and snowy_beach.
        for (float temperature : new float[]{-0.5f, -0.2f, -0.7f, 0.0f, 0.05f}) assertTrue(SnowMath.freezing(temperature), "temperature " + temperature);
        // Taiga, old growth pine taiga and windswept hills only snow at altitude.
        for (float temperature : new float[]{0.15f, 0.25f, 0.3f, 0.2f}) assertFalse(SnowMath.freezing(temperature), "temperature " + temperature);
    }
    @Test void disablingImmediatelyRemovesSnow() throws Exception {
        assertEquals(0, SnowMath.advance(1, 1, 1, 0, true, config("{\"enabled\":false}")));
    }
    @Test void warmRainAndRoofsCannotReceiveSnow() {
        assertEquals(0, SnowMath.exposure(false, false, 0, 0.8));
        assertEquals(0, SnowMath.exposure(true, true, 0, 0));
        assertEquals(1, SnowMath.exposure(true, false, 0, 0.8));
        assertTrue(SnowMath.exposure(true, false, 3, 0.8) < SnowMath.exposure(true, false, 1, 0.8));
    }
    @Test void canopyAndUndersidesAccumulateLaterButCanReachFullWhite() {
        double exposed = SnowMath.coverage(0.5, 1);
        double beneath = SnowMath.coverage(0.5, SnowMath.exposure(true, false, 4, 0.8));
        double underside = SnowMath.coverage(0.5, 0.15);
        assertEquals(0.5, exposed); assertTrue(beneath > 0 && beneath < exposed);
        assertTrue(underside > 0 && underside < exposed);
        for (double exposure : new double[]{1.0 / 127, 0.15, 0.8, 1}) {
            assertEquals(0, SnowMath.coverage(0, exposure));
            assertEquals(1, SnowMath.coverage(1, exposure));
            assertTrue(SnowMath.coverage(0.25, exposure) < SnowMath.coverage(0.75, exposure));
        }
        assertEquals(0, SnowMath.coverage(1, 0));
    }
    @Test void deepCanopyDoesNotBecomeIndistinguishableFromSolidShelter() {
        int leafSample = (int) Math.round(SnowMath.exposure(true, false, 40, 1) * 127);
        int roofSample = (int) Math.round(SnowMath.exposure(true, true, 40, 1) * 127);
        assertTrue(leafSample > 0); assertEquals(0, roofSample);
        assertEquals(1, SnowMath.coverage(1, leafSample / 127.0));
    }
    @Test void noiseIsStableAndNonconstantAtNegativeWorldCoordinates() {
        double first = SnowMath.noise(-30000000, -64, 24);
        assertEquals(first, SnowMath.noise(-30000000, -64, 24));
        assertNotEquals(first, SnowMath.noise(-29999999, -64, 24));
        for (int i = -1000; i < 1000; i++) assertTrue(SnowMath.noise(i, i * 3, i * -7) >= 0 && SnowMath.noise(i, i * 3, i * -7) < 1);
    }
    @Test void quantizationContainsAllMilestones() {
        for (int steps : new int[]{4, 8, 16}) {
            assertEquals(0, SnowMath.stage(0, steps)); assertEquals(4, SnowMath.stage(0.25, steps));
            assertEquals(8, SnowMath.stage(0.5, steps)); assertEquals(12, SnowMath.stage(0.75, steps));
            assertEquals(16, SnowMath.stage(1, steps));
        }
    }
    @Test void maskNeverFillsTransparentHolesAndPreservesSourceAlpha() {
        for (int stage = 1; stage <= 16; stage++) for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) {
            assertEquals(0, SnowMath.snowPixel(0x00ff00ff, x, y, 16, 16, stage, 0));
            int pixel = SnowMath.snowPixel(0x7f8f9f5f, x, y, 16, 16, stage, 0);
            assertTrue(pixel == 0 || pixel >>> 24 == 0x7f);
        }
    }
    @Test void masksGrowMonotonicallyToFullCoverage() {
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) {
            boolean snow = false;
            for (int stage = 1; stage <= 16; stage++) {
                boolean current = SnowMath.snowPixel(0xff7c8a4b, x, y, 16, 16, stage, 0) != 0;
                assertFalse(snow && !current); snow = current;
            }
            assertTrue(snow);
        }
    }
    @Test void textureDetailSurvivesWhitening() {
        assertNotEquals(SnowMath.snowPixel(0xff222222, 3, 4, 16, 16, 16, 0), SnowMath.snowPixel(0xffdddddd, 3, 4, 16, 16, 16, 0));
    }
    @Test void fullSnowPreservesNeedleContrastWithoutRandomBrightnessSpeckles() {
        int dark = SnowMath.snowPixel(0xff222222, 3, 4, 16, 16, 16, 0) >> 16 & 255;
        int light = SnowMath.snowPixel(0xffdddddd, 3, 4, 16, 16, 16, 0) >> 16 & 255;
        assertTrue(light - dark > 50);
        assertEquals(SnowMath.snowPixel(0xff888888, 0, 0, 16, 16, 16, 0),
                SnowMath.snowPixel(0xff888888, 9, 13, 16, 16, 16, 1));
    }
}
