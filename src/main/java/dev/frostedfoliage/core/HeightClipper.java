package dev.frostedfoliage.core;

/** Reusable horizontal clipping of a convex quad, expressed as source-edge interpolation. */
public final class HeightClipper {
    private final float[] heights = new float[4];
    private final int[] from = new int[6], to = new int[6];
    private final float[] fractions = new float[6];
    private int count;

    public int clip(float y0, float y1, float y2, float y3, float minimumY) {
        heights[0] = y0; heights[1] = y1; heights[2] = y2; heights[3] = y3;
        count = 0;
        for (int current = 0; current < 4; current++) {
            int previous = (current + 3) % 4;
            float a = heights[previous], b = heights[current];
            // Strict crossings avoid duplicating vertices exactly on the boundary.
            if ((a < minimumY && b > minimumY) || (a > minimumY && b < minimumY)) {
                add(previous, current, (minimumY - a) / (b - a));
            }
            if (b >= minimumY) add(current, current, 0);
        }
        return count >= 3 ? count : 0;
    }

    private void add(int a, int b, float fraction) {
        from[count] = a; to[count] = b; fractions[count++] = fraction;
    }
    public int from(int vertex) { return from[vertex]; }
    public int to(int vertex) { return to[vertex]; }
    public float fraction(int vertex) { return fractions[vertex]; }
    public float interpolate(int vertex, float a, float b) { return a + fraction(vertex) * (b - a); }
}
