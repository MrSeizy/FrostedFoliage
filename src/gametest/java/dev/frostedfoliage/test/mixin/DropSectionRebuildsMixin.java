package dev.frostedfoliage.test.mixin;

import dev.frostedfoliage.test.LostRebuildTest;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Test-only: loses rebuild requests on demand. Applied after Sodium so it also cancels Sodium's replacement method. */
@Mixin(value = LevelExtractor.class, priority = 1500)
abstract class DropSectionRebuildsMixin {
    @Inject(method = "setSectionDirty(III)V", at = @At("HEAD"), cancellable = true)
    private void frostedfoliage$drop(int x, int y, int z, CallbackInfo ci) { if (LostRebuildTest.dropRebuilds) ci.cancel(); }
}
