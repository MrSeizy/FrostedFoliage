package dev.frostedfoliage.test.mixin;

import dev.frostedfoliage.test.FrameMetrics;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Test-only timing hook, excluded from the distributable mod. */
@Mixin(Minecraft.class)
abstract class FrameMetricsMixin {
    @Inject(method = "renderFrame", at = @At("HEAD"))
    private void frostedfoliage$startFrame(boolean advance, CallbackInfo ci) { FrameMetrics.beginFrame(); }
    @Inject(method = "renderFrame", at = @At("RETURN"))
    private void frostedfoliage$endFrame(boolean advance, CallbackInfo ci) { FrameMetrics.endFrame(((Minecraft) (Object) this).getFrameTimeNs()); }
}
