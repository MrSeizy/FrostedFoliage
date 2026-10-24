package dev.frostedfoliage.core;

import dev.frostedfoliage.config.SnowConfig;

/** Plant-relative height masks; both halves of a tall plant share one vertical span. */
public final class PlantSnow {
    private PlantSnow() { }
    public record Span(double bottom, double top, int blockOffset) {
        public Span {
            if (!Double.isFinite(bottom) || !Double.isFinite(top) || top <= bottom) throw new IllegalArgumentException("Empty plant span");
        }
    }

    public static int pixel(int argb, int x, int y, int width, int height, double depth, Span span, int variant) {
        if ((argb >>> 24) == 0 || depth <= 0) return 0;
        if (depth >= 1) return argb;
        double size = span.top() - span.bottom();
        double worldY = span.blockOffset() + 1 - (y + 0.5) / height;
        double cutoff = span.top() - depth * size;
        if (worldY <= cutoff) return 0;
        double fade = Math.min(depth, 0.25) * size;
        double density = SnowConfig.clamp((worldY - cutoff) / fade, 0, 1);
        return density >= SnowMath.noise(x * 16 / width, y * 16 / height, variant + 173) ? argb : 0;
    }
}
