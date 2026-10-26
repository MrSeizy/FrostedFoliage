package dev.frostedfoliage;

import dev.frostedfoliage.core.SliderSteps;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SliderStepsTest {
    @Test void customValuesBecomeStopsSoSavingKeepsThem() {
        double[] steps = SliderSteps.of(new double[]{30, 60, 180, 300}, 200, 180);
        assertArrayEquals(new double[]{30, 60, 180, 200, 300}, steps);
        assertEquals(200, steps[SliderSteps.indexOf(steps, 200)]);
        assertEquals(180, steps[SliderSteps.indexOf(steps, 180)]);
    }

    @Test void indexFindsTheNearestStop() {
        double[] steps = {0.05, 0.1, 0.5, 2};
        assertEquals(0, SliderSteps.indexOf(steps, 0));
        assertEquals(2, SliderSteps.indexOf(steps, 0.6));
        assertEquals(3, SliderSteps.indexOf(steps, 10));
    }
}
