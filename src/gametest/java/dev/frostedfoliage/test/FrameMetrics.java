package dev.frostedfoliage.test;

import java.util.Arrays;

/** All calls on the client thread; fixed-size buffers keep the measurement bounded. */
public final class FrameMetrics {
    private static final long[] render = new long[32768], wall = new long[32768];
    private static boolean recording;
    private static int count;
    private static long start;
    private static long completedFrames;
    public static long completedFrames() { return completedFrames; }
    public static void start() { count = 0; recording = true; }
    public static void beginFrame() { if (recording) start = System.nanoTime(); }
    public static void endFrame(long renderNanos) {
        completedFrames++;
        if (!recording || count == render.length) return;
        render[count] = renderNanos; wall[count] = System.nanoTime() - start; count++;
    }
    public static Result stop() {
        recording = false;
        long[] c = Arrays.copyOf(render, count), w = Arrays.copyOf(wall, count);
        Arrays.sort(c); Arrays.sort(w);
        if (count == 0) throw new AssertionError("Benchmark recorded no frames");
        return new Result(count, mean(c), at(c, .50), at(c, .95), at(c, .99), mean(w), at(w, .95), at(w, .99));
    }
    private static double mean(long[] values) { return Arrays.stream(values).average().orElse(0) / 1e6; }
    private static double at(long[] values, double p) { return values[Math.min(values.length-1, (int) (p*values.length))] / 1e6; }
    public record Result(int frames, double gameRenderMeanMs, double gameRenderP50Ms, double gameRenderP95Ms, double gameRenderP99Ms,
                         double renderWallMeanMs, double renderWallP95Ms, double renderWallP99Ms) { }
}
