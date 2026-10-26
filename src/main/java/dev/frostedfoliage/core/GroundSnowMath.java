package dev.frostedfoliage.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Fills bare ground beneath foliage from the nearest real snow at a similar height. */
public final class GroundSnowMath {
    public static final int RADIUS = 8;
    private static final int[] SEARCH_X, SEARCH_Z, SEARCH_DISTANCE;
    static {
        List<int[]> offsets = new ArrayList<>();
        for (int z = -RADIUS; z <= RADIUS; z++) for (int x = -RADIUS; x <= RADIUS; x++) {
            if (x * x + z * z > 0 && x * x + z * z <= RADIUS * RADIUS) offsets.add(new int[]{x, z, x * x + z * z});
        }
        offsets.sort(Comparator.comparingInt(o -> o[2]));
        SEARCH_X = new int[offsets.size()]; SEARCH_Z = new int[offsets.size()]; SEARCH_DISTANCE = new int[offsets.size()];
        for (int i = 0; i < offsets.size(); i++) {
            SEARCH_X[i] = offsets.get(i)[0]; SEARCH_Z[i] = offsets.get(i)[1]; SEARCH_DISTANCE[i] = offsets.get(i)[2];
        }
    }
    private GroundSnowMath() { }

    /**
     * Whether a side of a {@code layers}-tall virtual snow column is covered by what stands beside it: a solid
     * block, or real or virtual snow at least as tall. Lower snow leaves the upper part of the side visible.
     */
    public static boolean sideHidden(int layers, boolean solidBeside, int realLayersBeside, int virtualLayersBeside) {
        return solidBeside || realLayersBeside >= layers || virtualLayersBeside >= layers;
    }

    /**
     * Snow layers for the column at ({@code x}, {@code z}) in a {@code width}-wide grid of ground heights and real
     * snow layers. Only columns within one block of {@code height} count; equally near columns take the thinnest.
     */
    public static int nearestLayers(int[] heights, byte[] snow, int width, int x, int z, int height) {
        int depth = heights.length / width;
        int nearest = RADIUS * RADIUS + 1, amount = 0;
        for (int i = 0; i < SEARCH_DISTANCE.length; i++) {
            int d = SEARCH_DISTANCE[i];
            if (d > nearest) break;
            int nx = x + SEARCH_X[i], nz = z + SEARCH_Z[i];
            if (nx < 0 || nz < 0 || nx >= width || nz >= depth) continue;
            int n = nz * width + nx;
            if (snow[n] == 0 || Math.abs(heights[n] - height) > 1) continue;
            amount = d < nearest ? snow[n] : Math.min(amount, snow[n]);
            nearest = d;
        }
        return amount;
    }
}
