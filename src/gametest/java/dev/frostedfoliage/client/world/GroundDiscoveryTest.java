package dev.frostedfoliage.client.world;

import dev.frostedfoliage.client.FrostedFoliageClient;
import dev.frostedfoliage.config.SnowConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.LevelChunk;

/** Drives the production cache with loaded chunks and a deterministic stream of load/unload callbacks. */
public final class GroundDiscoveryTest {
    private static final BlockPos TARGET = new BlockPos(24, 63, 24);

    public static void run(Minecraft client) {
        var original = client.player.position();
        var config = SnowConfig.builder().gradualAccumulation(false).gradualMelting(false).variation(0)
                .accumulationSeconds(1).meltingSeconds(1).persistenceSeconds(0).quality(SnowConfig.Quality.LOW)
                .maxVisualUpdatesPerSecond(1).maxCachedSections(64).build();
        var foliage = new Foliage(); foliage.refresh(config, true);
        try {
            for (boolean unloading : new boolean[]{false, true}) {
                var ground = new GroundSnowSystem(config, foliage);
                ground.reset(client.level);
                client.player.setPos(-56, 70, -56);
                // Exhaust the 16-chunk cache around an old focus, without discovering the target.
                for (int x = -4; x < 0; x++) for (int z = -4; z < 0; z++) ground.load(chunk(client, x, z));
                check(ground.layers(TARGET) == 0, "Discovery target was already cached");
                client.player.setPos(8, 70, 8);
                LevelChunk distant = chunk(client, -4, -4);
                for (int tick = 0; tick < 40; tick++) {
                    if (unloading) ground.unload(distant); else ground.load(distant);
                    ground.tick(client);
                }
                check(ground.layers(TARGET) == 1, "Chunk " + (unloading ? "unloads" : "loads") + " starved nearby discovery: " + ground.diagnostics());
                check(ground.diagnostics().contains("groundChunks=16 "), "Ground cache did not obey its capacity: " + ground.diagnostics());

                // A retained unload must replenish capacity even after a completed discovery pass.
                ground.unload(chunk(client, 1, 1));
                check(ground.layers(TARGET) == 0, "Unloaded chunk retained its snapshot");
                for (int tick = 0; tick < 40; tick++) ground.tick(client);
                check(ground.layers(TARGET) == 1, "Completed discovery did not restart after capacity was freed");
                // No renderer draws this standalone instance, so it keeps retrying unconfirmed rebuilds by design.
                check(ground.diagnostics().contains("groundPending=0/"), "Discovery did not drain its pending captures: " + ground.diagnostics());
                ground.reset(null);
                check(ground.diagnostics().contains("groundChunks=0 "), "Discovery cache survived reset");
            }
        } finally {
            client.player.setPos(original);
        }
        FrostedFoliageClient.LOGGER.info("GROUND DISCOVERY PASS: load/unload streams, 16-chunk capacity, refill after unload, reset");
    }

    private static LevelChunk chunk(Minecraft client, int x, int z) {
        LevelChunk chunk = client.level.getChunkSource().getChunkNow(x, z);
        check(chunk != null, "Missing discovery fixture chunk " + x + "," + z);
        return chunk;
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
