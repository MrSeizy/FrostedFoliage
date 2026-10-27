package dev.frostedfoliage.test.mixin;

import dev.frostedfoliage.test.LostRebuildTest;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Test-only: loses every section rebuild request on demand, whatever its source. Applied after Sodium, so it also
 * cancels the methods Sodium replaces with direct calls into its own renderer.
 */
@Mixin(value = LevelExtractor.class, priority = 1500)
abstract class DropSectionRebuildsMixin {
    @Inject(method = "setSectionDirty(III)V", at = @At("HEAD"), cancellable = true)
    private void frostedfoliage$drop(int x, int y, int z, CallbackInfo ci) { if (LostRebuildTest.dropRebuilds) ci.cancel(); }
    @Inject(method = "setSectionDirty(IIIZ)V", at = @At("HEAD"), cancellable = true)
    private void frostedfoliage$dropImportant(int x, int y, int z, boolean important, CallbackInfo ci) { if (LostRebuildTest.dropRebuilds) ci.cancel(); }
    @Inject(method = "setSectionDirtyWithNeighbors(III)V", at = @At("HEAD"), cancellable = true)
    private void frostedfoliage$dropNeighbors(int x, int y, int z, CallbackInfo ci) { if (LostRebuildTest.dropRebuilds) ci.cancel(); }
    @Inject(method = "setBlocksDirty(IIIIII)V", at = @At("HEAD"), cancellable = true)
    private void frostedfoliage$dropArea(int x0, int y0, int z0, int x1, int y1, int z1, CallbackInfo ci) { if (LostRebuildTest.dropRebuilds) ci.cancel(); }
    @Inject(method = "setBlockDirty(Lnet/minecraft/core/BlockPos;Z)V", at = @At("HEAD"), cancellable = true)
    private void frostedfoliage$dropBlock(BlockPos pos, boolean important, CallbackInfo ci) { if (LostRebuildTest.dropRebuilds) ci.cancel(); }
}
