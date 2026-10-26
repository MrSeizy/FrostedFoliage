package dev.frostedfoliage.test;

import dev.frostedfoliage.client.FrostedFoliageClient;
import dev.frostedfoliage.client.render.SnowSpriteSource;
import dev.frostedfoliage.config.SnowConfig;
import dev.frostedfoliage.core.TextureBudget;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.SharedConstants;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Actual HD resource reloads must retain every leaf/plant source and preserve alpha at source resolution. */
public final class ResourceBudgetTest {
    private static final Identifier SPRUCE = Identifier.withDefaultNamespace("block/spruce_leaves");
    // This is a color sprite explicitly used by the model, despite its conventional normal-map suffix.
    private static final Identifier RENAMED = Identifier.withDefaultNamespace("block/review_needles_n");
    private record Probe(BlockPos pos, Identifier block) { }

    public void run(ClientGameTestContext context) {
        Path pack;
        try {
            Path directory = context.computeOnClient(c -> c.getResourcePackDirectory());
            Files.createDirectories(directory);
            pack = Files.createTempDirectory(directory, "frostedfoliage-hd-test-");
        } catch (IOException e) { throw new AssertionError(e); }
        List<String> previous = context.computeOnClient(c -> List.copyOf(c.getResourcePackRepository().getSelectedIds()));
        var original = SnowSpriteSource.catalog();
        Set<Identifier> leaves = new HashSet<>(original.sources());
        leaves.remove(SPRUCE); leaves.add(RENAMED);
        Map<Identifier, byte[]> textures = context.computeOnClient(c -> {
            var ids = new HashSet<>(original.sources()); ids.addAll(original.plantSources().keySet());
            Map<Identifier, byte[]> result = new HashMap<>();
            for (Identifier id : ids) {
                var resource = c.getResourceManager().getResource(id.withPath("textures/" + id.getPath() + ".png")).orElseThrow();
                try (var input = resource.open()) { result.put(id, input.readAllBytes()); }
                catch (IOException e) { throw new AssertionError(e); }
            }
            return result;
        });
        context.runOnClient(c -> {
            c.options.pauseOnLostFocus = false;
            c.options.inactivityFpsLimit().set(net.minecraft.client.InactivityFpsLimit.MINIMIZED);
            FrostedFoliageClient.system().configure(SnowConfig.builder().gradualAccumulation(false).variation(0)
                    .accumulationSeconds(8).meltingSeconds(6).persistenceSeconds(0).maxVisualUpdatesPerSecond(2).build());
        });
        var probes = new ArrayList<Probe>();
        try (var world = context.worldBuilder().create()) {
            var server = world.getServer();
            server.runCommand("gamemode spectator @a");
            server.runCommand("tp @a 19 76 26 145 20");
            server.runCommand("time set noon");
            server.runCommand("gamerule minecraft:random_tick_speed 0");
            server.runCommand("gamerule minecraft:max_snow_accumulation_height 0");
            for (int x = -16; x < 32; x += 16) for (int z = -16; z < 32; z += 16) {
                server.runCommand("fillbiome " + x + " 64 " + z + " " + (x + 15) + " 95 " + (z + 15) + " minecraft:snowy_taiga");
            }
            server.runOnServer(s -> {
                var blocks = BuiltInRegistries.BLOCK.stream().filter(b -> b instanceof LeavesBlock
                        && BuiltInRegistries.BLOCK.getKey(b).getNamespace().equals("minecraft")).toList();
                for (int i = 0; i < blocks.size(); i++) {
                    var block = blocks.get(i);
                    var pos = new BlockPos(i % 4 * 4, 70, i / 4 * 4);
                    s.overworld().setBlock(pos, block.defaultBlockState().setValue(BlockStateProperties.PERSISTENT, true), 3);
                    probes.add(new Probe(pos, BuiltInRegistries.BLOCK.getKey(block)));
                }
            });
            server.runCommand("weather rain 100000");
            context.waitFor(c -> probes.stream().allMatch(p -> FrostedFoliageClient.system().coverage(p.pos()) > 0.99), 1200);
            if (Boolean.getBoolean("frostedfoliage.test.shaderExpected")) {
                check(context.computeOnClient(c -> net.irisshaders.iris.api.v0.IrisApi.getInstance().isShaderPackInUse()),
                        "Requested shader pack is not active");
            }
            check(probes.size() == 14, "Vanilla leaf catalog changed; update the resource fixture");
            check(original.sources().size() == 14, "Unused leaf-name textures are still being generated");

            var format = SharedConstants.getCurrentVersion().packVersion(PackType.CLIENT_RESOURCES);
            Files.writeString(pack.resolve("pack.mcmeta"), "{\"pack\":{\"description\":\"HD foliage budget regression\",\"min_format\":["
                    + format.major() + "," + format.minor() + "],\"max_format\":[" + format.major() + "," + format.minor() + "]}}");
            Path model = pack.resolve("assets/minecraft/models/block/spruce_leaves.json");
            Files.createDirectories(model.getParent());
            Files.writeString(model, "{\"parent\":\"minecraft:block/leaves\",\"textures\":{\"all\":\"" + RENAMED + "\"}}");
            String packId = "file/" + pack.getFileName();
            for (int factor : new int[]{2, 4}) {
                for (var entry : textures.entrySet()) {
                    Identifier id = entry.getKey().equals(SPRUCE) ? RENAMED : entry.getKey();
                    writeScaled(pack, id, entry.getValue(), factor);
                }
                // A misleading name must not consume any of the leaf budget.
                writeScaled(pack, Identifier.withDefaultNamespace("block/review_unused_leaves"), textures.get(SPRUCE), factor);
                context.runOnClient(c -> {
                    var repository = c.getResourcePackRepository(); repository.reload();
                    var selected = new ArrayList<>(previous); selected.add(packId); repository.setSelected(selected);
                });
                reload(context);
                context.runOnClient(c -> {
                    check(c.getResourcePackRepository().getSelectedIds().contains(packId), "HD pack was silently dropped");
                    var catalog = SnowSpriteSource.catalog();
                    check(catalog.sources().equals(leaves), "HD reload dropped or added leaf sources: " + catalog.sources());
                    check(catalog.plantSources().equals(original.plantSources()), "HD reload dropped plant masks");
                    check(catalog.texels() <= TextureBudget.MAX_TEXELS, "Generated masks exceeded the texel budget");
                    check(catalog.profile().equals(factor == 2 ? new TextureBudget.Profile(8, 5) : new TextureBudget.Profile(4, 1)),
                            "Unexpected HD quality fallback: " + catalog.profile());
                    var atlas = (TextureAtlas) c.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);
                    for (Probe probe : probes) {
                        int[] snow = {0};
                        var state = c.level.getBlockState(probe.pos());
                        var modelView = c.getModelManager().getBlockStateModelSet().get(state);
                        var emitter = net.fabricmc.fabric.api.client.renderer.v1.Renderer.get().quadEmitter(q -> {
                            var sprite = atlas.spriteFinder().find(q);
                            var name = sprite.contents().name();
                            if (name.getNamespace().equals("frostedfoliage") && name.getPath().startsWith("generated/")) {
                                snow[0]++;
                                check(sprite.contents().width() == 16 * factor, "Snow mask lost source resolution");
                                if (q.lightFace() == Direction.UP) {
                                    Identifier source = probe.block().getPath().equals("spruce_leaves") ? SPRUCE
                                            : Identifier.withDefaultNamespace("block/" + probe.block().getPath());
                                    try {
                                        BufferedImage sourceImage = ImageIO.read(new ByteArrayInputStream(textures.get(source)));
                                        for (int y = 0; y < 16 * factor; y++) for (int x = 0; x < 16 * factor; x++) {
                                            boolean transparent = (sourceImage.getRGB(x / factor, y / factor) >>> 24) == 0;
                                            check(sprite.contents().isTransparent(0, x, y) == transparent, "HD mask changed source alpha");
                                        }
                                    } catch (IOException e) { throw new AssertionError(e); }
                                }
                            }
                        });
                        modelView.emitQuads(emitter, c.level, probe.pos(), state, net.minecraft.util.RandomSource.create(1), d -> false);
                        check(snow[0] > 0, "No HD snow geometry for " + probe.block());
                    }
                    FrostedFoliageClient.LOGGER.info("HD FOLIAGE PASS size={} leaves={} plants={} texels={} profile={}",
                            16 * factor, catalog.sources().size(), catalog.plantSources().size(), catalog.texels(), catalog.profile());
                });
                world.getConnection().waitForChunksRender(false, 1200);
                context.takeScreenshot("foliage-hd-" + 16 * factor);
            }
        } catch (IOException e) { throw new AssertionError(e); }
        finally {
            context.runOnClient(c -> c.getResourcePackRepository().setSelected(previous));
            reload(context);
            try (var files = Files.walk(pack)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
            } catch (IOException e) { throw new AssertionError(e); }
        }
    }

    private static void writeScaled(Path pack, Identifier id, byte[] bytes, int factor) throws IOException {
        BufferedImage original = ImageIO.read(new ByteArrayInputStream(bytes));
        var scaled = new BufferedImage(original.getWidth() * factor, original.getHeight() * factor, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < scaled.getHeight(); y++) for (int x = 0; x < scaled.getWidth(); x++) {
            scaled.setRGB(x, y, original.getRGB(x / factor, y / factor));
        }
        Path target = pack.resolve("assets/" + id.getNamespace() + "/textures/" + id.getPath() + ".png");
        Files.createDirectories(target.getParent());
        ImageIO.write(scaled, "png", target.toFile());
    }

    private static void reload(ClientGameTestContext context) {
        var reload = context.computeOnClient(c -> c.reloadResourcePacks());
        context.waitFor(c -> reload.isDone(), 1200); reload.join();
        context.waitFor(c -> c.gui.overlay() == null, 1200);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
