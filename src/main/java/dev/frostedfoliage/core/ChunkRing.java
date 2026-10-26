package dev.frostedfoliage.core;

import java.util.Arrays;
import java.util.Comparator;
import java.util.function.LongSupplier;

/** Chunk offsets around the player, nearest first. A cursor resumes the pass across ticks. Client-thread only. */
public final class ChunkRing {
    @FunctionalInterface public interface Visitor {
        /** False applies backpressure: the pass stops and retries this offset next time. */
        boolean visit(int dx, int dz);
    }

    private int radius = -1, cursor;
    private int[] xs = new int[0], zs = new int[0];

    /** Rebuilds the ring for a new render distance and restarts the pass; returns false if unchanged. */
    public boolean resize(int radius) {
        if (radius == this.radius) return false;
        this.radius = radius;
        int side = 2 * radius + 1;
        int[][] offsets = new int[side * side][];
        for (int dx = -radius, i = 0; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) offsets[i++] = new int[]{dx, dz};
        Arrays.sort(offsets, Comparator.<int[]>comparingLong(o -> distance(o[0], o[1]))
                .thenComparingInt(o -> o[0]).thenComparingInt(o -> o[1]));
        xs = new int[offsets.length]; zs = new int[offsets.length];
        for (int i = 0; i < offsets.length; i++) { xs[i] = offsets[i][0]; zs[i] = offsets[i][1]; }
        cursor = 0;
        return true;
    }

    public void restart() { cursor = 0; }
    public boolean finished() { return cursor >= xs.length; }

    /**
     * Visits up to {@code budget} offsets. Once an offset lies beyond {@code farthest} (squared chunk distance,
     * re-read every step), no later offset can be admitted either, so the pass ends.
     */
    public void scan(int budget, LongSupplier farthest, Visitor visitor) {
        for (int i = 0; i < budget && cursor < xs.length; i++) {
            if (distance(xs[cursor], zs[cursor]) > farthest.getAsLong()) { cursor = xs.length; return; }
            if (!visitor.visit(xs[cursor], zs[cursor])) return;
            cursor++;
        }
    }

    private static long distance(int x, int z) { return (long) x * x + (long) z * z; }
}
