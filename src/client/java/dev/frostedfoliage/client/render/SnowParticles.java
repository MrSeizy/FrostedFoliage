package dev.frostedfoliage.client.render;

import dev.frostedfoliage.client.FrostedFoliageClient;
import dev.frostedfoliage.client.mixin.SingleQuadParticleAccessor;
import dev.frostedfoliage.core.SnowMath;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.FallingLeavesParticle;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;

/** Falling leaves from snowy foliage come down frosted, as often as their block's coverage and the leaf depth allow. */
public final class SnowParticles {
    private static final Set<Identifier> missing = ConcurrentHashMap.newKeySet();
    private SnowParticles() { }

    public static void frost(Particle particle, double x, double y, double z, RandomSource random) {
        var system = FrostedFoliageClient.system();
        if (!(particle instanceof FallingLeavesParticle leaf) || system == null) return;
        // Leaf blocks drop these 0.05 below themselves (ParticleUtils.spawnParticleBelow).
        double coverage = system.coverage(BlockPos.containing(x, y + 0.1, z));
        if (random.nextDouble() >= SnowMath.frostedLeafChance(coverage, system.config().snowDepth())) return;
        var quad = (SingleQuadParticleAccessor) leaf;
        Identifier original = quad.frostedfoliage$sprite().contents().name();
        TextureAtlasSprite frosted = Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(AtlasIds.PARTICLES)
                .getSprite(SnowParticleSource.frostedId(original));
        if (frosted.contents().name().equals(MissingTextureAtlasSprite.getLocation())) {
            if (missing.size() < 32 && missing.add(original)) {
                FrostedFoliageClient.LOGGER.warn("No frosted sprite for falling leaf particle {}", original);
            }
            return;
        }
        // Sodium tracks animated particle sprites through setSprite.
        quad.frostedfoliage$setSprite(frosted);
        float shade = SnowMath.snowTint(system.config().whiteness()) / 255f;
        leaf.setColor(shade, shade, shade);
    }
}
