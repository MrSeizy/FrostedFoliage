package dev.frostedfoliage.test;

import dev.frostedfoliage.client.FrostedFoliageClient;
import dev.frostedfoliage.config.SnowConfig;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.fabricmc.fabric.api.client.renderer.v1.Renderer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.features.TreeFeatures;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LeavesBlock;

/** Every vanilla leaf species, sampled from Minecraft's own generated tree geometry. */
public final class TreeCompatibilityTest {
    public void run(ClientGameTestContext context) {
        context.runOnClient(c -> {
            c.options.renderDistance().set(12);
            c.options.pauseOnLostFocus = false;
            c.options.inactivityFpsLimit().set(net.minecraft.client.InactivityFpsLimit.MINIMIZED);
            var d = SnowConfig.defaults();
            FrostedFoliageClient.system().configure(new SnowConfig(true, true, true, true, 1, 0.9, 0.2, true, 1,
                    8, 6, 30, 0.8, SnowConfig.Quality.HIGH, 2, 4096, d.includeBlocks(), d.excludeBlocks(), d.extraTextures()));
        });
        Map<Block, BlockPos> probes = new LinkedHashMap<>();
        try (var world = context.worldBuilder().adjustSettings(s -> s.setName("Frosted Foliage - Mixed Grove")).create()) {
            var server = world.getServer();
            server.runCommand("gamemode spectator @a");
            server.runCommand("tp @a 56 101 65 140 30");
            server.runCommand("time set noon");
            server.runCommand("gamerule minecraft:random_tick_speed 0");
            server.runCommand("gamerule minecraft:max_snow_accumulation_height 0");
            server.runCommand("weather clear");
            server.runCommand("fill -44 60 -44 44 62 44 minecraft:dirt");
            server.runCommand("fill -44 63 -44 44 63 44 minecraft:grass_block");
            server.runOnServer(s -> {
                var level = s.overworld();
                var types = List.of(TreeFeatures.OAK, TreeFeatures.SPRUCE, TreeFeatures.BIRCH,
                        TreeFeatures.JUNGLE_TREE, TreeFeatures.ACACIA, TreeFeatures.CHERRY,
                        TreeFeatures.DARK_OAK, TreeFeatures.PALE_OAK, TreeFeatures.MANGROVE,
                        TreeFeatures.RED_POPLAR, TreeFeatures.ORANGE_POPLAR, TreeFeatures.YELLOW_POPLAR,
                        TreeFeatures.AZALEA_TREE);
                for (int i = 0; i < types.size(); i++) {
                    var feature = level.registryAccess().lookupOrThrow(Registries.FEATURE).getOrThrow(types.get(i)).value();
                    BlockPos pos = new BlockPos(-30 + (i % 4) * 20, 64, -30 + (i / 4) * 20);
                    boolean placed = false;
                    for (int attempt = 0; attempt < 8 && !placed; attempt++) {
                        placed = feature.place(level, level.getChunkSource().getGenerator(), RandomSource.create(36143L + i * 121L + attempt), pos);
                    }
                    if (!placed) throw new AssertionError("Native tree failed to generate: " + types.get(i));
                }
                // Prefer the highest leaf of each species to isolate model/texture compatibility from canopy delay.
                for (var pos : BlockPos.betweenClosed(-44, 64, -44, 44, 127, 44)) {
                    Block block = level.getBlockState(pos).getBlock();
                    if (block instanceof LeavesBlock && (!probes.containsKey(block) || probes.get(block).getY() < pos.getY())) {
                        probes.put(block, pos.immutable());
                    }
                }
                var expected = BuiltInRegistries.BLOCK.stream().filter(b -> b instanceof LeavesBlock
                        && BuiltInRegistries.BLOCK.getKey(b).getNamespace().equals("minecraft")).toList();
                for (Block block : expected) {
                    if (!probes.containsKey(block)) throw new AssertionError("Native grove is missing " + BuiltInRegistries.BLOCK.getKey(block));
                }
                FrostedFoliageClient.LOGGER.info("TREE TYPES generated {} native features covering {} vanilla leaf blocks", types.size(), expected.size());
            });
            for (int x = -48; x <= 32; x += 16) for (int z = -48; z <= 32; z += 16) {
                server.runCommand("fillbiome " + x + " 48 " + z + " " + (x + 15) + " 143 " + (z + 15) + " minecraft:snowy_taiga");
            }
            context.waitTicks(100);
            world.getConnection().waitForChunksRender(false, 1200);
            if (Boolean.getBoolean("frostedfoliage.test.shaderExpected")) {
                if (!context.computeOnClient(c -> net.irisshaders.iris.api.v0.IrisApi.getInstance().isShaderPackInUse())) {
                    throw new AssertionError("Requested shader pack is not active");
                }
            }
            context.runOnClient(c -> { if (!c.gui.hud.isHidden()) c.gui.hud.toggle(); });
            Map<Block, Integer> baseQuads = context.computeOnClient(c -> {
                Map<Block, Integer> counts = new LinkedHashMap<>();
                probes.forEach((block, pos) -> {
                    if (FrostedFoliageClient.system().coverage(pos) != 0) throw new AssertionError("Dry leaf had snow: " + block);
                    counts.put(block, quads(c, pos));
                });
                return counts;
            });
            context.takeScreenshot(TestScreenshotOptions.of("tree-types-dry").withSize(1440, 900));
            server.runCommand("weather rain 100000");
            context.waitFor(c -> probes.values().stream().allMatch(pos -> FrostedFoliageClient.system().coverage(pos) >= 0.99), 1200);
            context.waitFor(c -> FrostedFoliageClient.system().diagnostics().contains("pending=0/0 "), 1200);
            world.getConnection().waitForChunksRender(false, 1200);
            context.runOnClient(c -> probes.forEach((block, pos) -> {
                int snowQuads = quads(c, pos);
                if (snowQuads <= baseQuads.get(block)) throw new AssertionError("Missing snow geometry for " + BuiltInRegistries.BLOCK.getKey(block));
                FrostedFoliageClient.LOGGER.info("TREE TYPE PASS {} dryQuads={} snowQuads={} coverage={}",
                        BuiltInRegistries.BLOCK.getKey(block), baseQuads.get(block), snowQuads, FrostedFoliageClient.system().coverage(pos));
            }));
            if (Boolean.getBoolean("frostedfoliage.test.depth")) {
                for (double depth : new double[]{0, 0.5, 1}) {
                    var reload = context.computeOnClient(c -> FrostedFoliageClient.applyConfig(FrostedFoliageClient.system().config().withSnowDepth(depth)));
                    context.waitFor(c -> reload.isDone() && c.gui.overlay() == null, 1200);
                    reload.join();
                    context.waitFor(c -> probes.values().stream().allMatch(pos -> FrostedFoliageClient.system().coverage(pos) >= 0.99)
                            && FrostedFoliageClient.system().diagnostics().contains("pending=0/0 "), 1200);
                    context.runOnClient(c -> probes.forEach((block, pos) -> {
                        int count = quads(c, pos), expected = baseQuads.get(block) + (depth == 0 ? 1 : depth == 1 ? 6 : 5);
                        if (count != expected) throw new AssertionError("Depth geometry failed for " + block + " depth=" + depth + " quads=" + count);
                        FrostedFoliageClient.LOGGER.info("TREE DEPTH PASS {} depth={} quads={}", BuiltInRegistries.BLOCK.getKey(block), depth, count);
                    }));
                }
            }
            // Let storm particles subside while the persistence period preserves the full canopy.
            server.runCommand("weather clear");
            context.waitTicks(100);
            context.takeScreenshot(TestScreenshotOptions.of("tree-types-snow").withSize(1440, 900));
            server.runCommand("weather rain 100000");
        }
    }

    private static int quads(Minecraft client, BlockPos pos) {
        var state = client.level.getBlockState(pos);
        var model = client.getModelManager().getBlockStateModelSet().get(state);
        int[] count = {0};
        var emitter = Renderer.get().quadEmitter(q -> {
            count[0]++;
            double depth = FrostedFoliageClient.system().config().snowDepth();
            if (Boolean.getBoolean("frostedfoliage.test.depth") && depth > 0 && depth < 1
                    && q.lightFace() != net.minecraft.core.Direction.UP && q.lightFace() != net.minecraft.core.Direction.DOWN) {
                var atlas = (net.minecraft.client.renderer.texture.TextureAtlas) client.getTextureManager()
                        .getTexture(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS);
                var id = atlas.spriteFinder().find(q).contents().name();
                if (id.getNamespace().equals("frostedfoliage") && id.getPath().substring(id.getPath().lastIndexOf('/') + 1).startsWith("0_")) {
                    throw new AssertionError("Missing tapered side mask for " + state + ": " + id);
                }
            }
        });
        model.emitQuads(emitter, client.level, pos, state, RandomSource.create(1), direction -> false);
        return count[0];
    }
}
