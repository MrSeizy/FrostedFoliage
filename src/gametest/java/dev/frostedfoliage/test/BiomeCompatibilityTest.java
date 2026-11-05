package dev.frostedfoliage.test;

import dev.frostedfoliage.client.FrostedFoliageClient;
import dev.frostedfoliage.client.mixin.SingleQuadParticleAccessor;
import dev.frostedfoliage.client.world.PlantTypes;
import dev.frostedfoliage.config.SnowConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.fabricmc.fabric.api.client.renderer.v1.Renderer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Actual third-party registrations, resources and tree features, loaded only by the opt-in test profile. */
public final class BiomeCompatibilityTest {
    private record Probe(BlockPos pos, BlockState state) {
        boolean plant() { return PlantTypes.supported(state.getBlock()); }
        boolean modded() { return !BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace().equals("minecraft"); }
    }

    public void run(ClientGameTestContext context) {
        for (String id : List.of("biomesoplenty", "glitchcore", "terrablender", "ohthetreesyoullgrow")) {
            var mod = FabricLoader.getInstance().getModContainer(id).orElseThrow(
                    () -> new AssertionError("Missing compatibility test dependency: " + id));
            FrostedFoliageClient.LOGGER.info("BIOME COMPAT MOD {} {}", id, mod.getMetadata().getVersion());
        }
        var config = SnowConfig.builder().snowDepth(1).plantSnowDepth(1).plantMaximumCoverage(1)
                .preferTopFaces(false).variation(0).accumulationSeconds(8).meltingSeconds(6).persistenceSeconds(0)
                .treeGroundSnow(false).plantGroundSnow(false)
                .quality(SnowConfig.Quality.HIGH).maxVisualUpdatesPerSecond(2).build();
        context.runOnClient(c -> {
            c.options.renderDistance().set(10);
            c.options.cutoutLeaves().set(true);
            c.options.pauseOnLostFocus = false;
            c.options.inactivityFpsLimit().set(net.minecraft.client.InactivityFpsLimit.MINIMIZED);
        });
        apply(context, config);
        List<Probe> catalog = new ArrayList<>(), trees = new ArrayList<>();
        try (var world = context.worldBuilder().adjustSettings(s -> s.setName("Frosted Foliage - Biome Compatibility")).create()) {
            var server = world.getServer();
            server.runCommand("gamemode spectator @a");
            server.runCommand("tp @a 65 110 80 140 32");
            server.runCommand("time set noon");
            server.runCommand("gamerule minecraft:advance_time false");
            server.runCommand("gamerule minecraft:random_tick_speed 0");
            server.runCommand("gamerule minecraft:max_snow_accumulation_height 0");
            server.runCommand("weather clear");
            server.runCommand("fill -48 62 -48 48 62 60 minecraft:dirt");
            server.runCommand("fill -48 63 -48 48 63 60 minecraft:grass_block");
            server.runOnServer(s -> {
                var level = s.overworld();
                // Include all loaded mod namespaces so additional compatible jars extend this catalog automatically.
                List<Block> blocks = BuiltInRegistries.BLOCK.stream().filter(b ->
                        !BuiltInRegistries.BLOCK.getKey(b).getNamespace().equals("minecraft")
                                && (leaf(b.defaultBlockState()) || PlantTypes.supported(b))).toList();
                check(blocks.stream().anyMatch(b -> leaf(b.defaultBlockState())), "No modded leaves registered");
                check(blocks.stream().anyMatch(PlantTypes::supported), "No modded plants registered");
                check(blocks.size() <= 180, "Expand the fixture garden before testing more than 180 foliage blocks");
                for (int i = 0; i < blocks.size(); i++) {
                    BlockState state = blocks.get(i).defaultBlockState();
                    BlockPos pos = new BlockPos(-42 + i % 22 * 4, 64, -42 + i / 22 * 4);
                    if (state.hasProperty(BlockStateProperties.PERSISTENT)) state = state.setValue(BlockStateProperties.PERSISTENT, true);
                    if (blocks.get(i) instanceof DoublePlantBlock) {
                        DoublePlantBlock.placeAt(level, state, pos, 2);
                        catalog.add(new Probe(pos.above(), level.getBlockState(pos.above())));
                    } else level.setBlock(pos, state, 2);
                    catalog.add(new Probe(pos, state));
                }
                FrostedFoliageClient.LOGGER.info("BIOME COMPAT CATALOG leaves={} plants={} probes={}",
                        blocks.stream().filter(b -> leaf(b.defaultBlockState())).count(),
                        blocks.stream().filter(PlantTypes::supported).count(), catalog.size());
                String[] features = {"biomesoplenty:fir_tree", "biomesoplenty:snowblossom_tree",
                        "ohthetreesyoullgrow:v2_test_tree_1", "ohthetreesyoullgrow:v2_test_tree_2",
                        "ohthetreesyoullgrow:v2_test_tree_4"};
                for (int i = 0; i < features.length; i++) {
                    BlockPos origin = new BlockPos(-32 + i % 3 * 32, 64, 12 + i / 3 * 32);
                    var key = ResourceKey.create(Registries.FEATURE, Identifier.parse(features[i]));
                    var feature = level.registryAccess().lookupOrThrow(Registries.FEATURE).getOrThrow(key).value();
                    boolean placed = false;
                    for (int attempt = 0; attempt < 8 && !placed; attempt++) {
                        placed = feature.place(level, level.getChunkSource().getGenerator(), RandomSource.create(831L + i * 97 + attempt), origin);
                    }
                    check(placed, "Native feature failed to generate: " + features[i]);
                    Probe top = highestLeaf(level, origin);
                    check(top != null, "Native feature generated no leaves: " + features[i]);
                    trees.add(top);
                    FrostedFoliageClient.LOGGER.info("BIOME COMPAT TREE {} probe={} state={}", features[i], top.pos(), top.state());
                }
            });
            // Exercise a real modded cold biome. A tree's native warm biome is not expected to get snow.
            for (int x = -48; x <= 48; x += 16) for (int z = -48; z <= 48; z += 16) {
                server.runCommand("fillbiome " + x + " 48 " + z + " " + (x + 15) + " 127 " + (z + 15)
                        + " biomesoplenty:snowy_coniferous_forest");
            }
            List<Probe> all = new ArrayList<>(catalog);
            all.addAll(trees);
            context.waitTicks(100);
            world.getConnection().waitForChunksRender(false, 1200);
            context.runOnClient(c -> {
                if (!c.gui.hud.isHidden()) c.gui.hud.toggle();
                for (Probe p : all) {
                    check(c.level.getBlockState(p.pos()).equals(p.state()), "Foliage did not survive placement: " + p);
                    check(FrostedFoliageClient.system().foliage().test(p.state()), "Unrecognized foliage: " + p.state());
                    check(FrostedFoliageClient.system().coverage(p.pos(), p.plant()) == 0, "Dry foliage had snow: " + p);
                    model(c, p, p.state(), false);
                }
                if (Boolean.getBoolean("frostedfoliage.test.shaderExpected")) {
                    check(net.irisshaders.iris.api.v0.IrisApi.getInstance().isShaderPackInUse(), "Requested shader pack is inactive");
                }
                particles(c, catalog, false);
            });
            context.takeScreenshot(TestScreenshotOptions.of("biome-compat-dry").withSize(1440, 900));
            server.runCommand("weather rain 100000");
            waitSnow(context, all);
            int states = context.computeOnClient(c -> verifyCatalog(c, catalog));
            int particles = context.computeOnClient(c -> particles(c, catalog, true));
            context.runOnClient(c -> trees.forEach(p -> model(c, p, p.state(), true)));
            world.getConnection().waitForChunksRender(false, 1200);
            context.takeScreenshot(TestScreenshotOptions.of("biome-compat-snow").withSize(1440, 900));

            // Rebuild the real modded atlas, then check every model variant again.
            await(context, context.computeOnClient(Minecraft::reloadResourcePacks));
            waitSnow(context, all);
            context.runOnClient(c -> { verifyCatalog(c, catalog); particles(c, catalog, true); trees.forEach(p -> model(c, p, p.state(), true)); });
            for (double depth : new double[]{0, 0.5}) {
                apply(context, config.toBuilder().snowDepth(depth).build());
                waitSnow(context, all);
                context.runOnClient(c -> all.stream().filter(p -> !p.plant()).forEach(p -> model(c, p, p.state(), true)));
                if (depth == 0) context.runOnClient(c -> particles(c, catalog, false));
            }
            apply(context, config);
            // The Advanced toggle must suppress modded snow without suppressing vanilla leaves from OTTG trees.
            apply(context, config.toBuilder().moddedFoliage(false).build());
            context.waitTicks(60);
            context.runOnClient(c -> { all.forEach(p -> model(c, p, p.state(), !p.modded())); particles(c, catalog, false); });
            apply(context, config);
            waitSnow(context, all);

            // Warm third-party biomes clear the coating, even while it is raining.
            Probe warm = catalog.getFirst();
            int wx = warm.pos().getX() & ~3, wz = warm.pos().getZ() & ~3;
            server.runCommand("fillbiome " + wx + " 48 " + wz + " " + (wx + 3) + " 127 " + (wz + 3) + " biomesoplenty:bayou");
            context.waitFor(c -> FrostedFoliageClient.system().coverage(warm.pos(), warm.plant()) == 0, 400);
            context.runOnClient(c -> model(c, warm, warm.state(), false));
            server.runCommand("fillbiome " + wx + " 48 " + wz + " " + (wx + 3) + " 127 " + (wz + 3) + " biomesoplenty:snowy_coniferous_forest");
            waitSnow(context, all);

            server.runCommand("weather clear");
            context.waitFor(c -> !FrostedFoliageClient.system().active(), 1200);
            context.runOnClient(c -> all.forEach(p -> {
                model(c, p, p.state(), false);
                check(c.level.getBlockState(p.pos()).equals(p.state()), "Visual snow changed a foliage block: " + p);
            }));
            context.runOnClient(c -> particles(c, catalog, false));
            FrostedFoliageClient.LOGGER.info("BIOME COMPAT PASS catalogProbes={} modelStates={} nativeTrees={} particleTypes={} renderer={}",
                    catalog.size(), states, trees.size(), particles, System.getProperty("frostedfoliage.test.renderer", "launcher"));
        }
        check(context.computeOnClient(c -> FrostedFoliageClient.system().diagnostics().contains("snapshots=0")),
                "Biome compatibility world leaked snapshots after disconnect");
    }

    private static boolean leaf(BlockState state) {
        return state.getBlock() instanceof LeavesBlock || state.is(BlockTags.LEAVES);
    }

    private static Probe highestLeaf(ServerLevel level, BlockPos origin) {
        Probe top = null;
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-14, 0, -14), origin.offset(14, 60, 14))) {
            BlockState state = level.getBlockState(pos);
            if (leaf(state) && (top == null || pos.getY() > top.pos().getY())) top = new Probe(pos.immutable(), state);
        }
        return top;
    }

    private static int verifyCatalog(Minecraft client, List<Probe> probes) {
        int states = 0;
        for (Probe p : probes) {
            for (BlockState state : p.state().getBlock().getStateDefinition().getPossibleStates()) {
                if (PlantTypes.part(state) != PlantTypes.part(p.state())) continue;
                if (state.hasProperty(BlockStateProperties.WATERLOGGED) && state.getValue(BlockStateProperties.WATERLOGGED)) continue;
                if (state.hasProperty(BlockStateProperties.HANGING) && state.getValue(BlockStateProperties.HANGING)) continue;
                model(client, p, state, true);
                states++;
            }
            FrostedFoliageClient.LOGGER.info("BIOME COMPAT FOLIAGE PASS {}", p.state());
        }
        return states;
    }

    /** Require actual visible generated sprites, rather than treating coverage or extra quads alone as success. */
    private static void model(Minecraft client, Probe probe, BlockState state, boolean snow) {
        var model = client.getModelManager().getBlockStateModelSet().get(state);
        var atlas = (TextureAtlas) client.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);
        int[] base = {0}, frosted = {0};
        var emitter = Renderer.get().quadEmitter(q -> {
            var sprite = atlas.spriteFinder().find(q).contents();
            check(!sprite.name().equals(MissingTextureAtlasSprite.getLocation()), "Missing model texture: " + state);
            if (!sprite.name().getNamespace().equals("frostedfoliage")) { base[0]++; return; }
            check(sprite.name().getPath().startsWith(probe.plant() ? "generated/plants/" : "generated/"),
                    "Wrong snow texture kind: " + state + " " + sprite.name());
            for (int y = 0; y < sprite.height(); y++) for (int x = 0; x < sprite.width(); x++) {
                if (!sprite.isTransparent(0, x, y)) { frosted[0]++; return; }
            }
        });
        model.emitQuads(emitter, client.level, probe.pos(), state, RandomSource.create(1), direction -> false);
        check(base[0] > 0, "No base geometry: " + state);
        check(snow ? frosted[0] > 0 : frosted[0] == 0,
                "Unexpected snow geometry: " + state + " expected=" + snow + " snowQuads=" + frosted[0] + " pos=" + probe.pos());
    }

    private static void waitSnow(ClientGameTestContext context, List<Probe> probes) {
        context.waitFor(c -> probes.stream().allMatch(p -> FrostedFoliageClient.system().coverage(p.pos(), p.plant()) == 1), 1200);
        context.waitFor(c -> FrostedFoliageClient.system().diagnostics().contains("pending=0/0 "), 1200);
    }

    private static int particles(Minecraft client, List<Probe> probes, boolean snow) {
        int checked = 0;
        for (Probe p : probes) {
            if (p.plant()) continue;
            Identifier id = BuiltInRegistries.BLOCK.getKey(p.state().getBlock());
            var type = BuiltInRegistries.PARTICLE_TYPE.getValue(id);
            if (!(type instanceof SimpleParticleType simple)) continue;
            for (int i = 0; i < 32; i++) particle(client, simple, p.pos(), snow);
            checked++;
        }
        check(checked >= 11, "Expected all 11 BOP falling-leaf particle types, found " + checked);
        // Unrelated particles beneath snowy foliage keep their original sprites, including BOP's petal particles.
        particle(client, ParticleTypes.SMOKE, probes.getFirst().pos(), false);
        var petals = BuiltInRegistries.PARTICLE_TYPE.getValue(Identifier.parse("biomesoplenty:white_flower_petal"));
        check(petals instanceof SimpleParticleType, "BOP flower-petal negative control is missing");
        particle(client, (SimpleParticleType) petals, probes.getFirst().pos(), false);
        client.particleEngine.clearParticles();
        return checked;
    }

    private static void particle(Minecraft client, ParticleOptions type, BlockPos pos, boolean snow) {
        var particle = client.particleEngine.createParticle(type, pos.getX() + 0.5, pos.getY() - 0.05, pos.getZ() + 0.5, 0, 0, 0);
        check(particle instanceof SingleQuadParticle, "Expected a sprite particle: " + type);
        var accessor = (SingleQuadParticleAccessor) particle;
        for (int tick = 0; tick < 5; tick++) {
            var sprite = accessor.frostedfoliage$sprite().contents().name();
            check(sprite.getNamespace().equals("frostedfoliage") == snow,
                    "Unexpected falling leaf sprite: " + BuiltInRegistries.PARTICLE_TYPE.getKey(type.getType())
                            + " expectedSnow=" + snow + " sprite=" + sprite + " tick=" + tick);
            particle.tick();
        }
    }

    private static void apply(ClientGameTestContext context, SnowConfig config) {
        await(context, context.computeOnClient(c -> FrostedFoliageClient.applyConfig(config)));
    }

    private static void await(ClientGameTestContext context, CompletableFuture<Void> reload) {
        context.waitFor(c -> reload.isDone() && c.gui.overlay() == null, 1200);
        reload.join();
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
