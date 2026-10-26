package dev.frostedfoliage.test;

import dev.frostedfoliage.client.FrostedFoliageClient;
import dev.frostedfoliage.config.SnowConfig;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.core.BlockPos;

/** Real game and renderer exercise. The production jar does not contain this test mod. */
public final class FrostedFoliageGameTest implements FabricClientGameTest {
    private static final BlockPos TOP = new BlockPos(0, 70, 0);
    @Override public void runTest(ClientGameTestContext context) {
        if (Boolean.getBoolean("frostedfoliage.test.gallery") || Boolean.getBoolean("frostedfoliage.test.capture")) {
            new NaturalGalleryCapture().run(context); return;
        }
        if (Boolean.getBoolean("frostedfoliage.test.ground")) { new GroundSnowTest().run(context); return; }
        if (Boolean.getBoolean("frostedfoliage.test.reload")) { new FoliageReloadTest().run(context); return; }
        if (Boolean.getBoolean("frostedfoliage.test.textureBudget")) { new ResourceBudgetTest().run(context); return; }
        if (Boolean.getBoolean("frostedfoliage.test.churn")) { new BlockUpdateBenchmark().run(context); return; }
        if (Boolean.getBoolean("frostedfoliage.test.icon")) { new IconCapture().run(context); return; }
        if (Boolean.getBoolean("frostedfoliage.test.plants")) {
            new PlantSnowTest().run(context);
            if (!Boolean.getBoolean("frostedfoliage.test.vanillaPlants")) new PlantCatalogTest().run(context);
            if (Boolean.getBoolean("frostedfoliage.test.treeTypes")) new TreeCompatibilityTest().run(context);
            return;
        }
        if (Boolean.getBoolean("frostedfoliage.test.appearance") || Boolean.getBoolean("frostedfoliage.test.depth")
                || Boolean.getBoolean("frostedfoliage.test.timelapse")) {
            new CanopyAppearanceTest().run(context);
            if (Boolean.getBoolean("frostedfoliage.test.treeTypes")) new TreeCompatibilityTest().run(context);
            return;
        }
        if (Boolean.getBoolean("frostedfoliage.test.treeTypes")) { new TreeCompatibilityTest().run(context); return; }
        if (Boolean.getBoolean("frostedfoliage.test.priority")) { new NearbyCacheTest().run(context); return; }
        if (Boolean.getBoolean("frostedfoliage.test.benchmark")) { new ForestBenchmark().run(context); return; }
        if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("cloth-config")) {
            context.runOnClient(c -> c.gui.setScreen(dev.frostedfoliage.client.compat.SnowConfigScreen.create(null)));
            context.waitTicks(5);
            capture(context, "configuration-screen");
            context.runOnClient(c -> c.gui.setScreen(null));
        }
        context.runOnClient(client -> {
            FrostedFoliageClient.system().configure(SnowConfig.builder().snowDepth(1).accumulationSeconds(12).meltingSeconds(6)
                    .persistenceSeconds(0).quality(SnowConfig.Quality.HIGH).maxVisualUpdatesPerSecond(2).build());
        });
        try (var world = context.worldBuilder().create()) {
            var server = world.getServer();
            server.runCommand("fill -24 63 -24 24 63 24 minecraft:snow_block");
            server.runCommand("fillbiome -24 64 -24 0 79 24 minecraft:snowy_taiga");
            server.runCommand("fillbiome 1 64 -24 24 79 24 minecraft:snowy_taiga");
            for (int x : new int[]{-12, 0, 12}) {
                server.runCommand("fill " + x + " 64 0 " + x + " 69 0 minecraft:spruce_log");
                server.runCommand("fill " + (x-3) + " 67 -3 " + (x+3) + " 68 3 minecraft:spruce_leaves[persistent=true]");
                server.runCommand("fill " + (x-2) + " 69 -2 " + (x+2) + " 69 2 minecraft:spruce_leaves[persistent=true]");
                server.runCommand("fill " + (x-1) + " 70 -1 " + (x+1) + " 70 1 minecraft:spruce_leaves[persistent=true]");
            }
            server.runCommand("setblock 20 70 0 minecraft:oak_leaves[persistent=true]");
            server.runCommand("fill 19 73 -1 21 73 1 minecraft:stone");
            server.runCommand("gamemode spectator @a");
            server.runCommand("tp @a 13 75 22 150 20");
            server.runCommand("time set noon");
            server.runCommand("weather clear");
            world.getConnection().waitForChunksRender();
            if (Boolean.getBoolean("frostedfoliage.test.shaderExpected")) {
                check(context.computeOnClient(c -> net.irisshaders.iris.api.v0.IrisApi.getInstance().isShaderPackInUse()), "Requested shader pack is not active");
            }
            context.runOnClient(client -> { if (!client.gui.hud.isHidden()) client.gui.hud.toggle(); });
            capture(context, "00-dry");
            check(context.computeOnClient(c -> FrostedFoliageClient.system().coverage(TOP)) == 0, "Dry leaves had snow");
            server.runCommand("weather rain 100000");
            for (int stage = 1; stage <= 4; stage++) {
                double threshold = stage / 4.0 - 0.06;
                context.waitFor(c -> FrostedFoliageClient.system().coverage(TOP) >= threshold, 1200);
                world.getConnection().waitForChunksRender(false);
                capture(context, "0" + stage + "-snow");
            }
            int emittedQuads = context.computeOnClient(client -> {
                var state = client.level.getBlockState(TOP);
                var model = client.getModelManager().getBlockStateModelSet().get(state);
                int[] count = {0};
                var emitter = net.fabricmc.fabric.api.client.renderer.v1.Renderer.get().quadEmitter(q -> count[0]++);
                model.emitQuads(emitter, client.level, TOP, state, net.minecraft.util.RandomSource.create(1), d -> false);
                FrostedFoliageClient.LOGGER.info("SNOW GEOMETRY {} quads={} coverage={}", model.getClass().getName(), count[0], FrostedFoliageClient.system().coverage(TOP));
                return count[0];
            });
            check(emittedQuads > 6, "Snow model did not emit overlay geometry");
            var reload = context.computeOnClient(c -> c.reloadResourcePacks());
            context.waitFor(c -> reload.isDone(), 1200);
            reload.join();
            context.waitFor(c -> c.gui.overlay() == null, 1200);
            context.waitTicks(20);
            world.getConnection().waitForChunksRender(false);
            capture(context, "04b-resource-reloaded");
            server.runCommand("fillbiome 8 64 -8 24 79 8 minecraft:desert");
            context.waitFor(c -> FrostedFoliageClient.system().coverage(new BlockPos(12, 70, 0)) == 0, 200);
            world.getConnection().waitForChunksRender(false);
            capture(context, "05-warm-biome");
            server.runCommand("fillbiome 8 64 -8 24 79 8 minecraft:snowy_taiga");
            context.waitFor(c -> FrostedFoliageClient.system().coverage(new BlockPos(12, 70, 0)) > 0.9, 200);
            check(context.computeOnClient(c -> FrostedFoliageClient.system().coverage(new BlockPos(20, 70, 0))) == 0, "Roof failed to shelter foliage");
            context.runOnClient(client -> client.options.cutoutLeaves().set(false));
            context.runOnClient(client -> client.levelExtractor.allChanged());
            context.waitTicks(20);
            world.getConnection().waitForChunksRender(false);
            capture(context, "06-fast-leaves");
            context.runOnClient(client -> client.options.cutoutLeaves().set(true));
            context.runOnClient(client -> client.levelExtractor.allChanged());
            context.waitTicks(20);
            world.getConnection().waitForChunksRender(false);
            server.runCommand("tp @a 512 75 512");
            world.getConnection().waitForChunksDownload();
            context.waitFor(c -> FrostedFoliageClient.system().coverage(TOP) == 0, 400);
            server.runCommand("tp @a 13 75 22 150 20");
            world.getConnection().waitForChunksRender();
            context.waitFor(c -> FrostedFoliageClient.system().coverage(TOP) > 0.9, 400);
            server.runCommand("weather clear");
            context.waitFor(c -> !FrostedFoliageClient.system().active(), 1200);
            world.getConnection().waitForChunksRender(false);
            capture(context, "07-melted");
            check(context.computeOnClient(c -> FrostedFoliageClient.system().coverage(TOP)) == 0, "Snow did not melt");
            context.runOnClient(c -> FrostedFoliageClient.LOGGER.info("CLIENT TEST METRICS {}", FrostedFoliageClient.system().diagnostics()));
            server.runCommand("execute in minecraft:the_nether run tp @a 0 90 0");
            context.waitTicks(60);
            check(context.computeOnClient(c -> FrostedFoliageClient.system().coverage(TOP)) == 0, "Dimension retained snow");
        }
        check(context.computeOnClient(c -> FrostedFoliageClient.system().diagnostics().contains("snapshots=0")), "Disconnect leaked cache");
        try (var second = context.worldBuilder().create()) {
            check(context.computeOnClient(c -> !FrostedFoliageClient.system().active()), "Second world inherited weather history");
        }
    }
    private static void capture(ClientGameTestContext context, String name) {
        context.takeScreenshot(TestScreenshotOptions.of(name).withSize(1280, 800));
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
