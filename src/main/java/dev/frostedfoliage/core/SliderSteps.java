package dev.frostedfoliage.core;

import java.util.Arrays;
import java.util.stream.DoubleStream;

/** Slider stops for wide-ranged settings. Required values (current, default) are always stops, so saving never snaps them. */
public final class SliderSteps {
    private SliderSteps() { }

    public static double[] of(double[] presets, double... required) {
        return DoubleStream.concat(Arrays.stream(presets), Arrays.stream(required)).sorted().distinct().toArray();
    }

    public static int indexOf(double[] steps, double value) {
        int best = 0;
        for (int i = 1; i < steps.length; i++) if (Math.abs(steps[i] - value) < Math.abs(steps[best] - value)) best = i;
        return best;
    }
}
