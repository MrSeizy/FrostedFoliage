package dev.frostedfoliage.test;

import dev.frostedfoliage.client.FrostedFoliageClient;
import dev.frostedfoliage.config.SnowConfig;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Exercises cache exhaustion without relying on chunk unload/reload to discover nearer foliage. */
public final class NearbyCacheTest {
    private static final BlockPos A = new BlockPos(8, 70, 8), B = new BlockPos(184, 70, 184);

    public void run(ClientGameTestContext context) {
        context.runOnClient(c -> {
            c.options.renderDistance().set(16);
            c.options.pauseOnLostFocus = false;
            c.options.inactivityFpsLimit().set(net.minecraft.client.InactivityFpsLimit.MINIMIZED);
            FrostedFoliageClient.system().configure(config(false, 64));
        });
        try (var world = context.worldBuilder().create()) {
            var server = world.getServer();
            server.runCommand("gamemode spectator @a");
            server.runCommand("tp @a 8 75 8 -45 20");
            server.runCommand("time set noon");
            server.runCommand("gamerule minecraft:random_tick_speed 0");
            server.runCommand("gamerule minecraft:max_snow_accumulation_height 0");
            server.runCommand("weather clear");
            server.runOnServer(s -> {
                var level = s.overworld();
                var leaves = Blocks.SPRUCE_LEAVES.defaultBlockState().setValue(BlockStateProperties.PERSISTENT, true);
                var pos = new BlockPos.MutableBlockPos();
                for (int x = 8; x < 192; x += 16) for (int z = 8; z < 192; z += 16) {
                    for (int y = 64; y < 70; y++) level.setBlock(pos.set(x, y, z), Blocks.SPRUCE_LOG.defaultBlockState(), 3);
                    for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
                        level.setBlock(pos.set(x + dx, 70, z + dz), leaves, 3);
                    }
                }
            });
            for (int x = 0; x < 192; x += 16) for (int z = 0; z < 192; z += 16) {
                server.runCommand("fillbiome " + x + " 64 " + z + " " + (x + 15) + " 79 " + (z + 15) + " minecraft:snowy_taiga");
            }
            context.waitTicks(200);
            context.waitFor(c -> c.level.getChunkSource().hasChunk(0, 0) && c.level.getChunkSource().hasChunk(11, 11), 1200);
            var chunkA = context.computeOnClient(c -> c.level.getChunkSource().getChunkNow(0, 0));
            var chunkB = context.computeOnClient(c -> c.level.getChunkSource().getChunkNow(11, 11));
            if (Boolean.getBoolean("frostedfoliage.test.shaderExpected")) {
                check(context.computeOnClient(c -> net.irisshaders.iris.api.v0.IrisApi.getInstance().isShaderPackInUse()), "Requested shader pack is not active");
            }
            context.runOnClient(c -> FrostedFoliageClient.system().configure(config(true, 64)));
            server.runCommand("weather rain 100000");
            settled(context, A, B, 64);
            world.getConnection().waitForChunksRender(false, 1200);
            verifyGeometry(context, A, B);
            context.takeScreenshot("priority-a-near");

            // Both endpoint chunks are in range before and after this move.
            server.runCommand("tp @a 184 75 184 135 20");
            settled(context, B, A, 64);
            check(context.computeOnClient(c -> c.level.getChunkSource().getChunkNow(0, 0) == chunkA
                    && c.level.getChunkSource().getChunkNow(11, 11) == chunkB), "Movement reloaded an endpoint chunk");
            world.getConnection().waitForChunksRender(false, 1200);
            verifyGeometry(context, B, A);
            context.takeScreenshot("priority-b-near");

            context.runOnClient(c -> FrostedFoliageClient.system().configure(config(true, 144)));
            context.waitFor(c -> FrostedFoliageClient.system().coverage(A) > 0.9 && FrostedFoliageClient.system().diagnostics().contains("snapshots=144 "), 1200);
            verifySettingsRefresh(context);
            new MalformedPlantPackTest().run(context);
            verifyAllCoverage(context);
            check(quads(context, A) > 6 && quads(context, B) > 6, "Resource reload lost snowy meshes");
            context.runOnClient(c -> FrostedFoliageClient.system().configure(config(true, 64)));
            settled(context, B, A, 64);
            world.getConnection().waitForChunksRender(false, 1200);
            verifyGeometry(context, B, A);

            server.runCommand("tp @a 8 75 8 -45 20");
            settled(context, A, B, 64);
            check(context.computeOnClient(c -> c.level.getChunkSource().getChunkNow(0, 0) == chunkA
                    && c.level.getChunkSource().getChunkNow(11, 11) == chunkB), "Return movement reloaded an endpoint chunk");
            world.getConnection().waitForChunksRender(false, 1200);
            verifyGeometry(context, A, B);
            context.runOnClient(c -> FrostedFoliageClient.LOGGER.info("NEARBY CACHE TEST PASSED {}", FrostedFoliageClient.system().diagnostics()));
        }
        check(context.computeOnClient(c -> FrostedFoliageClient.system().diagnostics().contains("snapshots=0 ")), "Disconnect leaked priority cache");
    }

    private void settled(ClientGameTestContext context, BlockPos near, BlockPos far, int limit) {
        context.waitFor(c -> {
            var system = FrostedFoliageClient.system();
            String status = system.diagnostics();
            return system.coverage(near) > 0.9 && system.coverage(far) == 0
                    && status.contains("snapshots=" + limit + " ") && status.contains("pending=0/0 ");
        }, 1200);
        check(context.computeOnClient(c -> c.level.getChunkSource().hasChunk(0, 0) && c.level.getChunkSource().hasChunk(11, 11)), "Endpoint chunk unloaded; priority test would be invalid");
    }

    private void verifyGeometry(ClientGameTestContext context, BlockPos near, BlockPos far) {
        check(quads(context, near) > 6, "Nearby cached leaf lacks snow geometry");
        check(quads(context, far) == 6, "Evicted distant leaf still emits snow geometry");
    }

    private int quads(ClientGameTestContext context, BlockPos pos) {
        return context.computeOnClient(c -> {
            var state = c.level.getBlockState(pos);
            check(state.is(Blocks.SPRUCE_LEAVES), "Test leaf is absent");
            var model = c.getModelManager().getBlockStateModelSet().get(state);
            int[] count = {0};
            var emitter = net.fabricmc.fabric.api.client.renderer.v1.Renderer.get().quadEmitter(q -> count[0]++);
            model.emitQuads(emitter, c.level, pos, state, net.minecraft.util.RandomSource.create(1), d -> false);
            return count[0];
        });
    }

    private void verifySettingsRefresh(ClientGameTestContext context) {
        context.runOnClient(c -> {
            var system = FrostedFoliageClient.system();
            // Medium quality rebuilds twice as fast as it recaptures sections.
            system.configure(config(true, 144, SnowConfig.Quality.MEDIUM, java.util.List.of()));
            // A real tag refresh still needs invalidation even when settings are unchanged.
            system.tagsLoaded();
            check(system.diagnostics().contains("snapshots=144 "), "Settings refresh cleared existing snapshots");
            check(system.diagnostics().contains("pending=144/144 "), "Recapture backlog was not exercised");
            verifyAllCoverageNow();
        });
        for (int tick = 0; tick < 20; tick++) {
            context.waitTicks(1);
            verifyAllCoverage(context);
            check(quads(context, A) > 6 && quads(context, B) > 6, "Pending recapture lost snowy geometry");
        }
        context.waitFor(c -> FrostedFoliageClient.system().diagnostics().contains("pending=0/0 "), 1200);

        context.runOnClient(c -> FrostedFoliageClient.system().configure(config(false, 144)));
        check(context.computeOnClient(c -> FrostedFoliageClient.system().coverage(A)) == 0, "Disable retained visible snow");
        check(quads(context, A) == 6, "Disable retained snow geometry");
        context.runOnClient(c -> FrostedFoliageClient.system().configure(config(true, 144)));
        verifyAllCoverage(context);

        context.runOnClient(c -> FrostedFoliageClient.system().configure(
                config(true, 144, SnowConfig.Quality.MEDIUM, java.util.List.of("minecraft:spruce_leaves"))));
        check(quads(context, A) == 6 && quads(context, B) == 6, "Exclusion retained snow geometry");
        context.waitFor(c -> FrostedFoliageClient.system().diagnostics().contains("snapshots=0 ")
                && FrostedFoliageClient.system().diagnostics().contains("pending=0/0 "), 1200);
        context.runOnClient(c -> FrostedFoliageClient.system().configure(config(true, 144)));
        context.waitFor(c -> FrostedFoliageClient.system().diagnostics().contains("snapshots=144 ")
                && FrostedFoliageClient.system().diagnostics().contains("pending=0/0 "), 1200);
        verifyAllCoverage(context);
        FrostedFoliageClient.LOGGER.info("SETTINGS REFRESH REGRESSION PASSED: 144 sections, disable, exclusion and restoration");
    }

    private void verifyAllCoverage(ClientGameTestContext context) { context.runOnClient(c -> verifyAllCoverageNow()); }

    private static void verifyAllCoverageNow() {
        for (int x = 8; x < 192; x += 16) for (int z = 8; z < 192; z += 16) {
            check(FrostedFoliageClient.system().coverage(new BlockPos(x, 70, z)) > 0.9,
                    "Existing snow disappeared at " + x + ", " + z);
        }
    }

    private SnowConfig config(boolean enabled, int limit) {
        return config(enabled, limit, SnowConfig.Quality.HIGH, java.util.List.of());
    }

    private SnowConfig config(boolean enabled, int limit, SnowConfig.Quality quality, java.util.List<String> excluded) {
        var d = SnowConfig.defaults();
        return new SnowConfig(enabled, false, false, true, 1, 0.9, 0, true, 1,
                1, 1, 0, 0.8, quality, 2, limit, d.includeBlocks(), excluded, d.extraTextures());
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
