package dev.frostedfoliage.test.mixin;

import dev.frostedfoliage.client.world.SnowSystem;
import dev.frostedfoliage.test.SnowWorkMetrics;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SnowSystem.class)
abstract class SnowTickMetricsMixin {
    @Inject(method = "tick", at = @At("HEAD"))
    private void frostedfoliage$beginTick(Minecraft client, CallbackInfo ci) { SnowWorkMetrics.beginTick(); }
    @Inject(method = "tick", at = @At("RETURN"))
    private void frostedfoliage$endTick(Minecraft client, CallbackInfo ci) { SnowWorkMetrics.endTick(); }
}
