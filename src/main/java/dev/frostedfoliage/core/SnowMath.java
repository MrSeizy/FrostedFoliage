package dev.frostedfoliage.core;

import dev.frostedfoliage.config.SnowConfig;

public final class SnowMath {
    public static final int LEAF_VARIANTS = 8;
    private SnowMath() { }

    public static long mix(long n) {
        n = (n ^ (n >>> 30)) * 0xbf58476d1ce4e5b9L;
        n = (n ^ (n >>> 27)) * 0x94d049bb133111ebL;
        return n ^ (n >>> 31);
    }

    public static double noise(int x, int y, int z) {
        return (mix(x * 0x632be59bd9b4e019L ^ y * 0x9e3779b97f4a7c15L ^ z * 0x85157af5L) >>> 11) * 0x1.0p-53;
    }

    /** Stable per-face patterns; face-adjacent blocks draw from disjoint halves of the palette. */
    public static int leafVariant(int x, int y, int z, int face) {
        int half = LEAF_VARIANTS / 2;
        long seed = mix(x * 0x632be59bd9b4e019L ^ y * 0x9e3779b97f4a7c15L
                ^ z * 0x85157af5L ^ (face + 1L) * 0xd1b54a32d192ed03L);
        return ((x ^ y ^ z ^ face) & 1) * half + ((int) seed & (half - 1));
    }

    public static double exposure(boolean snowPrecipitation, boolean roof, int leafDepth, double sensitivity) {
        if (!snowPrecipitation || roof) return 0;
        // Keep eligible foliage distinct from a solid roof when packed into a seven-bit sample.
        return Math.max(1.0 / 127, Math.exp(-Math.max(0, leafDepth) * (0.12 + sensitivity * 0.55)));
    }

    /** Exposure delays whitening; an eligible canopy can still become fully white after sustained snow. */
    public static double coverage(double progress, double exposure) {
        if (exposure <= 0) return 0;
        return Math.pow(SnowConfig.clamp(progress, 0, 1), 1 + 1.5 * (1 - SnowConfig.clamp(exposure, 0, 1)));
    }

    public static double advance(double snow, double rain, double elapsed, double drySeconds,
                                 boolean freezing, SnowConfig config) {
        if (!config.enabled()) return 0;
        if (rain > 0.01) {
            if (!config.gradualAccumulation()) return 1;
            return Math.min(1, snow + elapsed * rain / config.accumulationSeconds());
        }
        double delay = config.persistenceSeconds() * (freezing ? 2 : 1);
        if (drySeconds <= delay) return snow;
        if (!config.gradualMelting()) return 0;
        // Only integrate the part of this step after persistence expires.
        double meltingTime = Math.min(elapsed, drySeconds - delay);
        return Math.max(0, snow - meltingTime / config.meltingSeconds() * (freezing ? 0.35 : 1));
    }

    public static int stage(double amount, int steps) {
        return (int) Math.floor(SnowConfig.clamp(amount, 0, 1) * steps + 1e-6) * (16 / steps);
    }

    /** Alpha-masked, nested snow patches: each later stage contains all earlier snow pixels. */
    public static int snowPixel(int argb, int x, int y, int width, int height, int stage, int variant) {
        int alpha = argb >>> 24;
        if (alpha == 0) return 0;
        int px = x * 16 / width, py = y * 16 / height;
        int luminance = (((argb >> 16) & 255) * 3 + ((argb >> 8) & 255) * 6 + (argb & 255)) / 10;
        double patch = 0.78 * smoothNoise(x * 4.0 / width, y * 4.0 / height, variant + 37)
                + 0.12 * noise(px / 2, py / 2, variant + 17) + 0.10 * (1 - luminance / 255.0);
        if (patch >= stage / 16.0) return 0;
        // Retain the source's branch/needle shading without adding independent per-pixel glitter.
        int shade = 160 + luminance * 88 / 255;
        return (alpha << 24) | (shade << 16) | ((shade + 2) << 8) | (shade + 4);
    }

    /** Dither the last quarter-block of side snow; never whiten pixels below the chosen depth. */
    public static int depthPixel(int argb, int x, int y, int width, int height, double depth, int edge, int variant) {
        if (edge == 0 || depth >= 1 || (argb >>> 24) == 0) return argb;
        if (depth <= 0) return 0;
        double u = (x + 0.5) / width, v = (y + 0.5) / height;
        double worldY = textureHeight(u, v, edge);
        double density = SnowConfig.clamp((worldY - (1 - depth)) / Math.min(depth, 0.25), 0, 1);
        int px = x * 16 / width, py = y * 16 / height;
        double threshold = noise(px, py, variant + 127);
        return density >= threshold ? argb : 0;
    }

    /** Which texture axis points down in world space, including rotated/flipped model UVs. */
    public static int depthEdge(float dy, float du, float dv) {
        return Math.abs(dv) >= Math.abs(du) ? (dy * dv < 0 ? 1 : 2) : (dy * du < 0 ? 3 : 4);
    }

    /** A shifted/cropped custom UV projection cannot use a full-block taper without losing valid snow. */
    public static boolean depthMappingMatches(int edge, double y, double u, double v) {
        return Math.abs(y - textureHeight(u, v, edge)) <= 1e-4;
    }

    private static double textureHeight(double u, double v, int edge) {
        return switch (edge) { case 1 -> 1 - v; case 2 -> v; case 3 -> 1 - u; case 4 -> u; default -> throw new IllegalArgumentException("Unknown snow edge"); };
    }

    private static double smoothNoise(double x, double y, int seed) {
        int ix = (int) Math.floor(x), iy = (int) Math.floor(y);
        double fx = x - ix, fy = y - iy;
        fx = fx * fx * (3 - 2 * fx); fy = fy * fy * (3 - 2 * fy);
        double upper = noise(ix, iy, seed) * (1 - fx) + noise(ix + 1, iy, seed) * fx;
        double lower = noise(ix, iy + 1, seed) * (1 - fx) + noise(ix + 1, iy + 1, seed) * fx;
        return upper * (1 - fy) + lower * fy;
    }
}
