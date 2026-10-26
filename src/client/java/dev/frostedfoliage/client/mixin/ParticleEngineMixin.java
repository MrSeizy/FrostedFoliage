package dev.frostedfoliage.client.mixin;

import dev.frostedfoliage.client.render.SnowParticles;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.util.RandomSource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ParticleEngine.class)
abstract class ParticleEngineMixin {
    @Shadow @Final private RandomSource random;

    // After the provider has tinted it, so the frost replaces biome-colored leaves too.
    @Inject(method = "createParticle", at = @At("RETURN"))
    private void frostedfoliage$frostLeaves(ParticleOptions options, double x, double y, double z, double xa, double ya, double za,
            CallbackInfoReturnable<Particle> cir) {
        if (cir.getReturnValue() != null) SnowParticles.frost(cir.getReturnValue(), x, y, z, random);
    }
}
