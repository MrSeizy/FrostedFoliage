package dev.frostedfoliage.client.mixin;

import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(SingleQuadParticle.class)
public interface SingleQuadParticleAccessor {
    @Accessor("sprite") TextureAtlasSprite frostedfoliage$sprite();
    @Invoker("setSprite") void frostedfoliage$setSprite(TextureAtlasSprite sprite);
}
