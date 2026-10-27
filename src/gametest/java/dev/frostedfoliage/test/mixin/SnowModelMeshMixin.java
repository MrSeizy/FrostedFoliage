package dev.frostedfoliage.test.mixin;

import dev.frostedfoliage.client.FrostedFoliageClient;
import dev.frostedfoliage.test.LostRebuildTest;
import dev.frostedfoliage.test.TornBuildTest;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.LeavesBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Test-only: records the snow leaves and the watched ground block had each time the renderer actually meshed them. */
@Mixin(targets = "dev.frostedfoliage.client.render.SnowModels$SnowModel")
abstract class SnowModelMeshMixin {
    @Inject(method = "emitQuads", at = @At("HEAD"))
    private void frostedfoliage$meshed(QuadEmitter emitter, BlockAndTintGetter level, BlockPos pos, BlockState state,
            RandomSource random, Predicate<Direction> cullTest, CallbackInfo ci) {
        if (level == BlockAndTintGetter.EMPTY) return;
        if (state.getBlock() instanceof LeavesBlock) {
            // Pause first: the recorded coverage must be what the resumed worker goes on to mesh.
            TornBuildTest.pauseIfRequested(pos);
            double coverage = FrostedFoliageClient.system().coverage(pos);
            LostRebuildTest.meshedCoverage.put(SectionPos.asLong(pos), coverage);
            TornBuildTest.meshedBlocks.put(pos.immutable(), coverage);
        }
        if (pos.equals(LostRebuildTest.GROUND)) LostRebuildTest.groundMeshedLayers = FrostedFoliageClient.system().ground().layers(pos);
    }
}
