package dev.frostedfoliage.test;

import dev.frostedfoliage.client.FrostedFoliageClient;
import dev.frostedfoliage.config.SnowConfig;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.features.TreeFeatures;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;

/** A reproducible natural spruce grove viewed at branch height, including foliage beneath foliage. */
public final class CanopyAppearanceTest {
    private static final BlockPos PROBE = new BlockPos(35, 74, 35);
    public void run(ClientGameTestContext context) {
        boolean timelapse = Boolean.getBoolean("frostedfoliage.test.timelapse");
        context.runOnClient(c -> {
            c.options.renderDistance().set(12);
            c.options.pauseOnLostFocus = false;
            c.options.inactivityFpsLimit().set(net.minecraft.client.InactivityFpsLimit.MINIMIZED);
            var d = SnowConfig.defaults();
            FrostedFoliageClient.system().configure(SnowConfig.builder().snowDepth(1)
                    .accumulationSeconds(timelapse ? d.accumulationSeconds() : 16).meltingSeconds(6)
                    .persistenceSeconds(Boolean.getBoolean("frostedfoliage.test.video") ? 600 : 30)
                    .quality(SnowConfig.Quality.HIGH).maxVisualUpdatesPerSecond(2).build());
        });
        try (var world = context.worldBuilder().adjustSettings(settings -> settings.setName("Frosted Foliage - Natural Spruce")).create()) {
            var server = world.getServer();
            server.runCommand("gamemode spectator @a");
            server.runCommand("tp @a 2 65 18 180 -8");
            server.runCommand("time set noon");
            if (Boolean.getBoolean("frostedfoliage.test.video") || timelapse) server.runCommand("gamerule minecraft:advance_time false");
            server.runCommand("gamerule minecraft:random_tick_speed 0");
            server.runCommand("gamerule minecraft:max_snow_accumulation_height 0");
            server.runCommand("weather clear");
            server.runCommand("fill -40 62 -40 40 62 40 minecraft:dirt");
            server.runCommand("fill -40 63 -40 40 63 40 minecraft:grass_block");
            server.runOnServer(s -> {
                var level = s.overworld();
                var feature = level.registryAccess().lookupOrThrow(Registries.FEATURE).getOrThrow(TreeFeatures.SPRUCE).value();
                int placed = 0;
                for (int x = -32; x <= 32; x += 8) for (int z = -32; z <= 24; z += 8) {
                    var random = RandomSource.create(28472L + x * 171L + z * 13L);
                    BlockPos pos = new BlockPos(x + random.nextInt(3) - 1, 64, z + random.nextInt(3) - 1);
                    if (feature.place(level, level.getChunkSource().getGenerator(), random, pos)) placed++;
                }
                if (placed < 50) throw new AssertionError("Not enough natural trees in the comparison scene: " + placed);
                FrostedFoliageClient.LOGGER.info("NATURAL FOREST {} native spruce features placed", placed);
                // Ground snow follows open sky, matching normal snowfall around a tree canopy.
                var pos = new BlockPos.MutableBlockPos();
                for (int x = -40; x <= 40; x++) for (int z = -40; z <= 40; z++) {
                    if (level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) == 64 && level.getBlockState(pos.set(x, 64, z)).isAir()) {
                        level.setBlock(pos, Blocks.SNOW.defaultBlockState(), 3);
                    }
                }
            });
            for (int x = -48; x <= 32; x += 16) for (int z = -48; z <= 32; z += 16) {
                server.runCommand("fillbiome " + x + " 48 " + z + " " + (x + 15) + " 95 " + (z + 15) + " minecraft:snowy_taiga");
            }
            server.runCommand("fill 35 70 35 35 74 35 minecraft:spruce_leaves[persistent=true]");
            server.runCommand("fill 35 74 35 42 74 42 minecraft:spruce_leaves[persistent=true]");
            context.waitTicks(100);
            world.getConnection().waitForChunksRender(false, 1200);
            if (Boolean.getBoolean("frostedfoliage.test.shaderExpected")) {
                if (!context.computeOnClient(c -> net.irisshaders.iris.api.v0.IrisApi.getInstance().isShaderPackInUse())) {
                    throw new AssertionError("Requested shader pack is not active");
                }
            }
            context.runOnClient(c -> { if (!c.gui.hud.isHidden()) c.gui.hud.toggle(); });
            if (timelapse) {
                server.runCommand("tp @a 4.5 66 12.5 180 -8");
                context.runOnClient(c -> c.options.cutoutLeaves().set(true));
                context.waitTicks(20);
                world.getConnection().waitForChunksRender(false, 1200);
                VideoCapture.recordTimelapse(context, () -> server.runCommand("weather rain 100000"), PROBE);
                return;
            }
            capture(context, "canopy-00-dry");
            server.runCommand("weather rain 100000");
            for (int stage = 1; stage <= 4; stage++) {
                double target = stage / 4.0;
                context.waitFor(c -> FrostedFoliageClient.system().coverage(PROBE) >= target - 0.04, 1200);
                world.getConnection().waitForChunksRender(false, 1200);
                capture(context, "canopy-0" + stage + "-snow");
            }
            context.runOnClient(c -> FrostedFoliageClient.LOGGER.info("CANOPY APPEARANCE TEST full={} lower={} {}",
                    FrostedFoliageClient.system().coverage(PROBE), FrostedFoliageClient.system().coverage(PROBE.below(4)), FrostedFoliageClient.system().diagnostics()));
            if (context.computeOnClient(c -> FrostedFoliageClient.system().coverage(PROBE.below(4))) < 0.99) {
                throw new AssertionError("Lower canopy failed to reach full coverage after sustained snowfall");
            }
            server.runCommand("tp @a 4.5 66 12.5 180 -8");
            context.waitTicks(20);
            world.getConnection().waitForChunksRender(false, 1200);
            capture(context, "canopy-05-natural-grove");
            context.runOnClient(c -> c.options.cutoutLeaves().set(false));
            context.runOnClient(c -> c.levelExtractor.allChanged());
            context.waitTicks(20);
            world.getConnection().waitForChunksRender(false, 1200);
            capture(context, "canopy-06-fast");
            context.runOnClient(c -> c.options.cutoutLeaves().set(true));
            context.runOnClient(c -> c.levelExtractor.allChanged());
            server.runCommand("weather clear");
            context.waitTicks(100);
            world.getConnection().waitForChunksRender(false, 1200);
            capture(context, "canopy-07-clear-view");
            if (Boolean.getBoolean("frostedfoliage.test.depth")) {
                for (double depth : new double[]{0, 0.25, 0.5, 0.75, 1}) {
                    var reload = context.computeOnClient(c -> FrostedFoliageClient.applyConfig(FrostedFoliageClient.system().config().withSnowDepth(depth)));
                    context.waitFor(c -> reload.isDone() && c.gui.overlay() == null, 1200);
                    reload.join();
                    context.waitFor(c -> FrostedFoliageClient.system().coverage(PROBE) >= 0.99
                            && FrostedFoliageClient.system().diagnostics().contains("pending=0/0 "), 1200);
                    context.runOnClient(c -> {
                        checkDepthGeometry(c, depth);
                        if (depth == 0.5) checkVariantGeometry(c);
                    });
                    world.getConnection().waitForChunksRender(false, 1200);
                    capture(context, "depth-" + (int) (depth * 100));
                    if (Boolean.getBoolean("frostedfoliage.test.video")) VideoCapture.record(context, (int) (depth * 100), 3);
                }
                var reload = context.computeOnClient(c -> FrostedFoliageClient.applyConfig(FrostedFoliageClient.system().config().withSnowDepth(0.5)));
                context.waitFor(c -> reload.isDone() && c.gui.overlay() == null, 1200);
                reload.join();
                context.runOnClient(c -> {
                    c.options.cutoutLeaves().set(false);
                    c.levelExtractor.allChanged();
                });
                context.waitTicks(20);
                world.getConnection().waitForChunksRender(false, 1200);
                capture(context, "depth-50-fast");
                context.runOnClient(c -> c.options.cutoutLeaves().set(true));
            }
            // Leave the disposable preview save ready to accumulate again when opened normally.
            server.runCommand("weather rain 100000");
        }
    }

    private static void checkDepthGeometry(net.minecraft.client.Minecraft client, double depth) {
        var state = client.level.getBlockState(PROBE);
        var model = client.getModelManager().getBlockStateModelSet().get(state);
        int[] snowFaces = {0};
        var emitter = net.fabricmc.fabric.api.client.renderer.v1.Renderer.get().quadEmitter(q -> {
            if (q.tintIndex() != -1) return; // This probe is spruce: its base faces use foliage tint.
            snowFaces[0]++;
            var face = q.lightFace();
            if (depth < 1 && face == net.minecraft.core.Direction.DOWN) throw new AssertionError("Snow covered an underside at depth " + depth);
            if (face != net.minecraft.core.Direction.UP && face != net.minecraft.core.Direction.DOWN) {
                for (int i = 0; i < 4; i++) if (q.y(i) < 1 - depth - 0.0001) {
                    throw new AssertionError("Snow geometry extends below configured depth: " + q.y(i));
                }
                if (depth > 0 && depth < 1) {
                    var atlas = (net.minecraft.client.renderer.texture.TextureAtlas) client.getTextureManager()
                            .getTexture(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS);
                    String path = atlas.spriteFinder().find(q).contents().name().getPath();
                    if (path.substring(path.lastIndexOf('/') + 1).startsWith("0_")) {
                        throw new AssertionError("Side snow is using an untapered mask: " + path);
                    }
                }
            }
        });
        model.emitQuads(emitter, client.level, PROBE, state, RandomSource.create(1), direction -> false);
        int expected = depth == 0 ? 1 : depth == 1 ? 6 : 5;
        if (snowFaces[0] != expected) throw new AssertionError("Wrong overlay count at depth " + depth + ": " + snowFaces[0]);
        FrostedFoliageClient.LOGGER.info("SNOW DEPTH PASS depth={} overlayFaces={}", depth, snowFaces[0]);
    }

    private static void checkVariantGeometry(net.minecraft.client.Minecraft client) {
        var atlas = (net.minecraft.client.renderer.texture.TextureAtlas) client.getTextureManager()
                .getTexture(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS);
        int[][] variants = new int[8][8];
        var seen = new java.util.HashSet<Integer>();
        for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) {
            var pos = new BlockPos(35 + x, 74, 35 + z);
            var state = client.level.getBlockState(pos);
            var model = client.getModelManager().getBlockStateModelSet().get(state);
            int[] variant = {-1};
            var emitter = net.fabricmc.fabric.api.client.renderer.v1.Renderer.get().quadEmitter(q -> {
                if (q.tintIndex() != -1 || q.lightFace() != net.minecraft.core.Direction.NORTH) return;
                String path = atlas.spriteFinder().find(q).contents().name().getPath();
                if (!path.startsWith("generated/minecraft/block/spruce_leaves/")) {
                    throw new AssertionError("Leaf overlay lacks generated snow mask: " + path);
                }
                variant[0] = Integer.parseInt(path.substring(path.lastIndexOf('/') + 1).split("_")[1]);
            });
            model.emitQuads(emitter, client.level, pos, state, RandomSource.create(1), direction -> false);
            if (variant[0] < 0) throw new AssertionError("Missing north-facing snow overlay at " + pos);
            variants[x][z] = variant[0]; seen.add(variant[0]);
            if ((x > 0 && variants[x - 1][z] == variant[0]) || (z > 0 && variants[x][z - 1] == variant[0])) {
                throw new AssertionError("Adjacent leaf masks repeat at " + pos);
            }
        }
        if (seen.size() != dev.frostedfoliage.core.SnowMath.LEAF_VARIANTS) {
            throw new AssertionError("Not all leaf patterns reached the renderer: " + seen);
        }
        FrostedFoliageClient.LOGGER.info("LEAF VARIATION GEOMETRY PASSED: {} distinct patterns, no adjacent repeats", seen.size());
    }

    private void capture(ClientGameTestContext context, String name) {
        if (context.computeOnClient(c -> FrostedFoliageClient.system().active())) {
            context.waitFor(c -> FrostedFoliageClient.system().diagnostics().contains("pending=0/0 "), 1200);
            context.waitTicks(10);
        }
        context.takeScreenshot(TestScreenshotOptions.of(name).withSize(1440, 900));
    }
}
