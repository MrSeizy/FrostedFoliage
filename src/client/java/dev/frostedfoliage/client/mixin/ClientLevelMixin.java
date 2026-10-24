package dev.frostedfoliage.client.mixin;

import dev.frostedfoliage.client.FrostedFoliageClient;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientLevel.class)
abstract class ClientLevelMixin {
    @Inject(method = "onChunkLoaded", at = @At("TAIL"))
    private void frostedfoliage$biomesChanged(ChunkPos pos, CallbackInfo ci) {
        ClientLevel level = (ClientLevel) (Object) this;
        if (FrostedFoliageClient.system() != null && level.getChunkSource().hasChunk(pos.x(), pos.z())) {
            FrostedFoliageClient.system().load(level, level.getChunk(pos.x(), pos.z()));
        }
    }
    @Inject(method = "setBlocksDirty", at = @At("TAIL"))
    private void frostedfoliage$invalidate(BlockPos pos, BlockState oldState, BlockState newState, CallbackInfo ci) {
        if (FrostedFoliageClient.system() != null) FrostedFoliageClient.system().changed((ClientLevel) (Object) this, pos, oldState, newState);
    }
}
