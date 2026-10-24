package dev.frostedfoliage.core;

import java.util.List;

/** Keep source resolution and spatial patterns; reduce weather steps before dropping textures. */
public final class TextureBudget {
    public static final long MAX_TEXELS = 8L * 1024 * 1024;
    private TextureBudget() { }

    public record Source(long pixels, boolean leaf, int plantShapes) {
        public long texels(Profile profile) {
            return pixels * profile.stages() * ((leaf ? SnowMath.LEAF_VARIANTS * profile.edges() : 0) + 2L * plantShapes);
        }
    }

    public record Profile(int stages, int edges) {
        public int step() { return 16 / stages; }
        public int stage(int requested) { return Math.min(16, (requested + step() / 2) / step() * step()); }
    }

    public static Profile choose(List<Source> sources, boolean tapered) {
        Profile[] profiles = tapered
                ? new Profile[]{new Profile(16, 5), new Profile(8, 5), new Profile(4, 5), new Profile(8, 1),
                    new Profile(4, 1), new Profile(2, 1), new Profile(1, 1)}
                : new Profile[]{new Profile(16, 1), new Profile(8, 1), new Profile(4, 1), new Profile(2, 1), new Profile(1, 1)};
        for (Profile profile : profiles) {
            long total = 0;
            for (Source source : sources) total += source.texels(profile);
            if (total <= MAX_TEXELS) return profile;
        }
        return profiles[profiles.length - 1];
    }
}
