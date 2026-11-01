package dev.frostedfoliage.test;

import dev.frostedfoliage.client.FrostedFoliageClient;
import dev.frostedfoliage.config.SnowConfig;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biomes;

/** A cached leaf can sample a biome across a chunk boundary, including a temporarily missing chunk. */
public final class BiomeBoundaryTest {
    private static final BlockPos EDGE = new BlockPos(0, 70, 0), INTERIOR = new BlockPos(1, 70, 1);
    private static final ChunkPos NORTH = new ChunkPos(0, -1);

    public void run(ClientGameTestContext context) {
        context.runOnClient(c -> {
            c.options.pauseOnLostFocus = false;
            FrostedFoliageClient.system().configure(SnowConfig.builder().snowDepth(1).quality(SnowConfig.Quality.HIGH).build());
        });
        try (var world = context.worldBuilder().adjustSettings(s -> s.setSeed("1")).create()) {
            var server = world.getServer();
            server.runCommand("gamemode spectator @a");
            server.runCommand("tp @a 13 75 22 150 20");
            server.runCommand("gamerule minecraft:random_tick_speed 0");
            server.runCommand("weather clear");
            server.runCommand("fillbiome -16 64 -16 15 79 15 minecraft:snowy_taiga");
            server.runCommand("fill 0 70 0 1 70 1 minecraft:spruce_leaves[persistent=true]");
            world.getConnection().waitForChunksRender();
            context.runOnClient(c -> FrostedFoliageClient.system().setPreview(true));
            await(context, c -> coverage(EDGE) > .99 && coverage(INTERIOR) > .99, "Initial foliage did not receive snow");

            // Seed 1 samples EDGE's biome from quart (0,17,-1), in NORTH. Keep that chunk's real packet,
            // then capture EDGE while NORTH is absent: ClientLevel supplies its warm plains fallback.
            var packet = server.computeOnServer(s -> new ClientboundLevelChunkWithLightPacket(
                    s.overworld().getChunk(NORTH.x(), NORTH.z()), s.overworld().getLightEngine(), null, null));
            context.runOnClient(c -> {
                c.level.getChunkSource().drop(NORTH);
                FrostedFoliageClient.system().load(c.level, c.level.getChunk(0, 0));
            });
            await(context, c -> !c.level.getChunkSource().hasChunk(NORTH.x(), NORTH.z())
                    && c.level.getBiome(EDGE).is(Biomes.PLAINS) && coverage(EDGE) == 0 && coverage(INTERIOR) > .99,
                    "Missing neighbor did not establish the warm boundary snapshot");
            context.runOnClient(c -> c.getConnection().handleLevelChunkWithLight(packet));
            await(context, c -> c.level.getBiome(EDGE).is(Biomes.SNOWY_TAIGA) && coverage(EDGE) > .99,
                    "Neighbor chunk arrival left a stale warm foliage snapshot");

            // Biome packets have the same cross-chunk dependency, in both directions.
            server.runCommand("fillbiome 0 64 -16 15 79 -1 minecraft:plains");
            await(context, c -> c.level.getBiome(EDGE).is(Biomes.PLAINS) && coverage(EDGE) == 0 && coverage(INTERIOR) > .99,
                    "Warm neighbor biome packet left snow at the boundary");
            server.runCommand("fillbiome 0 64 -16 15 79 -1 minecraft:snowy_taiga");
            await(context, c -> c.level.getBiome(EDGE).is(Biomes.SNOWY_TAIGA) && coverage(EDGE) > .99,
                    "Cold neighbor biome packet did not restore boundary snow");

            // Queueing unchanged neighboring captures must not force fresh meshes on every chunk resend.
            world.getConnection().waitForChunksRender(false);
            await(context, c -> settled(), "Boundary foliage did not settle before unchanged recapture");
            long before = context.computeOnClient(c -> count(" rebuilds="));
            context.runOnClient(c -> {
                for (int i = 0; i < 20; i++) FrostedFoliageClient.system().load(c.level, c.level.getChunk(NORTH.x(), NORTH.z()));
            });
            context.waitTicks(60);
            context.runOnClient(c -> {
                if (count(" rebuilds=") != before) throw new AssertionError("Unchanged neighbor recaptures requested a foliage rebuild: " + status(c));
                if (coverage(EDGE) < .99) throw new AssertionError("Unchanged neighbor recapture lost boundary snow: " + status(c));
                FrostedFoliageClient.LOGGER.info("BIOME BOUNDARY PASS chunk arrival, warm/cold biome packets, unchanged neighbor recapture: {}", status(c));
            });
        }
    }

    private static double coverage(BlockPos pos) { return FrostedFoliageClient.system().coverage(pos); }
    private static boolean settled() {
        String status = FrostedFoliageClient.system().diagnostics();
        return status.contains("pending=0/0 ") && status.contains(" stale=0 ") && status.contains(" unmeshed=0 ");
    }
    private static long count(String label) {
        var match = java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(label) + "(\\d+)").matcher(FrostedFoliageClient.system().diagnostics());
        if (!match.find()) throw new AssertionError("Missing counter " + label);
        return Long.parseLong(match.group(1));
    }
    private static String status(Minecraft client) {
        return "edge=" + coverage(EDGE) + " interior=" + coverage(INTERIOR) + " biome=" + client.level.getBiome(EDGE).unwrapKey()
                + " " + FrostedFoliageClient.system().diagnostics();
    }
    private static void await(ClientGameTestContext context, Predicate<Minecraft> predicate, String message) {
        try { context.waitFor(predicate, 200); }
        catch (AssertionError timeout) { throw new AssertionError(message + ": " + context.computeOnClient(BiomeBoundaryTest::status), timeout); }
    }
}
