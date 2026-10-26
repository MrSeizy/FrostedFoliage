package dev.frostedfoliage.test;

import dev.frostedfoliage.client.FrostedFoliageClient;
import dev.frostedfoliage.client.mixin.SingleQuadParticleAccessor;
import dev.frostedfoliage.client.render.SnowParticleSource;
import dev.frostedfoliage.config.SnowConfig;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;

/** Leaves falling from snowy foliage use frosted particle sprites; bare foliage and other blocks keep vanilla ones. */
public final class FallingLeavesTest {
    // Canopy centers: an untinted (poplar), a tinted (oak) and a fast-shedding (cherry) leaf particle.
    private static final BlockPos POPLAR = new BlockPos(-4, 70, 0), OAK = new BlockPos(0, 70, 0), CHERRY = new BlockPos(4, 70, 0);
    private static final BlockPos STONE = new BlockPos(0, 70, 6);

    public void run(ClientGameTestContext context) {
        context.runOnClient(c -> {
            c.options.pauseOnLostFocus = false;
            FrostedFoliageClient.system().configure(SnowConfig.builder().snowDepth(1)
                    .quality(SnowConfig.Quality.HIGH).maxVisualUpdatesPerSecond(2).build());
        });
        try (var world = context.worldBuilder().adjustSettings(s -> s.setName("Frosted Foliage - Falling Leaves")).create()) {
            var server = world.getServer();
            server.runCommand("gamemode spectator @a");
            server.runCommand("time set noon");
            server.runCommand("weather clear");
            server.runCommand("gamerule minecraft:random_tick_speed 0");
            server.runCommand("fill -12 63 -12 12 63 12 minecraft:snow_block");
            server.runCommand("fillbiome -12 48 -12 12 95 12 minecraft:snowy_plains");
            canopy(server, POPLAR, "orange_poplar");
            canopy(server, OAK, "oak");
            canopy(server, CHERRY, "cherry");
            server.runCommand("setblock " + STONE.getX() + " " + STONE.getY() + " " + STONE.getZ() + " minecraft:stone");
            server.runCommand("tp @a 0 69 -7 0 -6");
            world.getConnection().waitForChunksRender();
            context.runOnClient(c -> { if (!c.gui.hud.isHidden()) c.gui.hud.toggle(); });

            context.runOnClient(c -> {
                var atlas = c.getAtlasManager().getAtlasOrThrow(AtlasIds.PARTICLES);
                for (String name : List.of("leaf_0", "leaf_11", "cherry_0", "pale_oak_0", "red_poplar_1", "orange_poplar_1", "yellow_poplar_4")) {
                    Identifier id = SnowParticleSource.frostedId(Identifier.withDefaultNamespace(name));
                    check(!atlas.getSprite(id).contents().name().equals(MissingTextureAtlasSprite.getLocation()), "Missing frosted particle sprite " + id);
                }
            });
            check(sprite(context, ParticleTypes.ORANGE_POPLAR_LEAVES, POPLAR).getNamespace().equals("minecraft"), "Bare poplar dropped frosted leaves");
            shower(context, world);
            capture(context, "leaves-bare");

            context.runOnClient(c -> FrostedFoliageClient.system().setPreview(true));
            context.waitFor(c -> {
                var system = FrostedFoliageClient.system();
                return system.coverage(POPLAR) > 0.99 && system.coverage(OAK) > 0.99 && system.coverage(CHERRY) > 0.99;
            }, 200);
            world.getConnection().waitForChunksRender(false);
            check(sprite(context, ParticleTypes.ORANGE_POPLAR_LEAVES, POPLAR).getPath().startsWith("frosted/minecraft/orange_poplar_"),
                    "Snowy poplar dropped bare leaves");
            check(sprite(context, ColorParticleOption.create(ParticleTypes.TINTED_LEAVES, 0xff48b518), OAK).getPath().startsWith("frosted/minecraft/leaf_"),
                    "Snowy oak dropped bare leaves");
            check(sprite(context, ParticleTypes.CHERRY_LEAVES, CHERRY).getPath().startsWith("frosted/minecraft/cherry_"),
                    "Snowy cherry dropped bare petals");
            check(sprite(context, ParticleTypes.ORANGE_POPLAR_LEAVES, STONE).getNamespace().equals("minecraft"),
                    "Leaves beneath stone were frosted");
            context.runOnClient(c -> c.particleEngine.clearParticles());
            shower(context, world);
            capture(context, "leaves-frosted");
            context.runOnClient(c -> FrostedFoliageClient.LOGGER.info("FALLING LEAVES frosted sprites verified for poplar, oak and cherry"));

            // At full snow, depth and coverage set the share of falling leaves that come down white.
            SnowConfig full = context.computeOnClient(c -> FrostedFoliageClient.system().config());
            context.runOnClient(c -> FrostedFoliageClient.system().configure(full.withSnowDepth(0)));
            int topsOnly = context.computeOnClient(c -> frosted(c, POPLAR, 400));
            check(topsOnly == 0, "Tops-only snow frosted " + topsOnly + " of 400 falling leaves");
            context.runOnClient(c -> FrostedFoliageClient.system().configure(full.withSnowDepth(0.5)));
            int halfDepth = context.computeOnClient(c -> frosted(c, POPLAR, 400));
            check(halfDepth > 140 && halfDepth < 260, "Half-depth snow frosted " + halfDepth + " of 400 falling leaves");
            context.runOnClient(c -> FrostedFoliageClient.system().configure(full.toBuilder().snowDepth(1).maximumCoverage(0.5).build()));
            int halfCoverage = context.computeOnClient(c -> frosted(c, POPLAR, 400));
            check(halfCoverage > 140 && halfCoverage < 260, "Half-coverage snow frosted " + halfCoverage + " of 400 falling leaves");
            context.runOnClient(c -> FrostedFoliageClient.system().configure(full.toBuilder().snowDepth(0.5).maximumCoverage(0.5).build()));
            int halfBoth = context.computeOnClient(c -> frosted(c, POPLAR, 400));
            check(halfBoth > 50 && halfBoth < 150, "Half-depth, half-coverage snow frosted " + halfBoth + " of 400 falling leaves");

            // During real snowfall, more leaves come down white as the snow builds up.
            context.runOnClient(c -> {
                FrostedFoliageClient.system().setPreview(false);
                FrostedFoliageClient.system().configure(full.toBuilder().accumulationSeconds(20).build());
                c.particleEngine.clearParticles();
            });
            context.waitFor(c -> FrostedFoliageClient.system().coverage(POPLAR) == 0, 200);
            server.runCommand("weather rain 100000");
            context.waitFor(c -> FrostedFoliageClient.system().coverage(POPLAR) > 0.15, 1200);
            double[] early = context.computeOnClient(c -> new double[]{FrostedFoliageClient.system().coverage(POPLAR), frosted(c, POPLAR, 400) / 400.0});
            context.waitFor(c -> FrostedFoliageClient.system().coverage(POPLAR) > 0.95, 1200);
            double[] late = context.computeOnClient(c -> new double[]{FrostedFoliageClient.system().coverage(POPLAR), frosted(c, POPLAR, 400) / 400.0});
            for (double[] sample : new double[][]{early, late}) {
                check(Math.abs(sample[1] - sample[0]) < 0.1, "At coverage " + sample[0] + ", " + sample[1] + " of falling leaves were frosted");
            }
            check(late[1] > early[1] + 0.2, "Frosted leaves did not increase with buildup: " + early[1] + " then " + late[1]);
            server.runCommand("weather clear");
            context.runOnClient(c -> {
                c.particleEngine.clearParticles();
                FrostedFoliageClient.LOGGER.info("FALLING LEAVES settings verified: {}/400 at 0% depth, {}/400 at 50% depth, {}/400 at 50% coverage, {}/400 at 50% of both",
                        topsOnly, halfDepth, halfCoverage, halfBoth);
                FrostedFoliageClient.LOGGER.info("FALLING LEAVES buildup verified: {} frosted at coverage {}, {} at coverage {}",
                        early[1], early[0], late[1], late[0]);
            });
            context.runOnClient(c -> FrostedFoliageClient.system().setPreview(false));
        }
    }

    private static void canopy(TestServerContext server, BlockPos center, String wood) {
        int x = center.getX(), y = center.getY(), z = center.getZ();
        server.runCommand("fill " + x + " 64 " + z + " " + x + " " + (y - 1) + " " + z + " minecraft:oak_log");
        server.runCommand("fill " + (x - 1) + " " + y + " " + (z - 1) + " " + (x + 1) + " " + (y + 1) + " " + (z + 1)
                + " minecraft:" + wood + "_leaves[persistent=true]");
    }

    private static Identifier sprite(ClientGameTestContext context, ParticleOptions options, BlockPos leaf) {
        return context.computeOnClient(c -> drop(c, options, leaf));
    }

    /** How many of {@code count} poplar leaves dropped by the block at {@code leaf} come down frosted. */
    private static int frosted(Minecraft client, BlockPos leaf, int count) {
        int frosted = 0;
        for (int i = 0; i < count; i++) {
            if (drop(client, ParticleTypes.ORANGE_POPLAR_LEAVES, leaf).getNamespace().equals("frostedfoliage")) frosted++;
        }
        return frosted;
    }

    /** The particle a leaf block at {@code leaf} would drop, as ParticleUtils.spawnParticleBelow places it. */
    private static Identifier drop(Minecraft client, ParticleOptions options, BlockPos leaf) {
        var particle = client.particleEngine.createParticle(options, leaf.getX() + 0.5, leaf.getY() - 0.05, leaf.getZ() + 0.5, 0, 0, 0);
        if (particle == null) throw new AssertionError("No particle for " + options.getType());
        return ((SingleQuadParticleAccessor) particle).frostedfoliage$sprite().contents().name();
    }

    /** Runs the blocks' own animateTick far more often than vanilla, so a screenshot shows plenty of leaves. */
    private static void shower(ClientGameTestContext context, TestSingleplayerContext world) {
        for (int tick = 0; tick < 50; tick++) {
            context.runOnClient(c -> {
                for (BlockPos center : List.of(POPLAR, OAK, CHERRY)) {
                    for (BlockPos pos : BlockPos.betweenClosed(center.offset(-1, 0, -1), center.offset(1, 0, 1))) {
                        var state = c.level.getBlockState(pos);
                        for (int i = 0; i < 12; i++) state.getBlock().animateTick(state, c.level, pos, c.level.getRandom());
                    }
                }
            });
            context.waitTick();
        }
        world.getConnection().waitForChunksRender(false);
    }

    private static void capture(ClientGameTestContext context, String name) {
        context.takeScreenshot(TestScreenshotOptions.of(name).withSize(1280, 800));
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
