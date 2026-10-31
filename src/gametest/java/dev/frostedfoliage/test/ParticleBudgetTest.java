package dev.frostedfoliage.test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.frostedfoliage.client.FrostedFoliageClient;
import dev.frostedfoliage.client.mixin.SingleQuadParticleAccessor;
import dev.frostedfoliage.client.render.SnowParticleSource;
import dev.frostedfoliage.config.SnowConfig;
import dev.frostedfoliage.core.TextureBudget;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.SharedConstants;
import net.minecraft.client.particle.ParticleDescription;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.util.GsonHelper;

/** Reload real HD/animated/overflow packs, check atlas membership, then spawn leaves using the fallback sprite. */
public final class ParticleBudgetTest {
    private static final Identifier DEFINITION = Identifier.fromNamespaceAndPath("frostedfoliage_test", "particles/budget_leaves.json");
    private static final Identifier TOO_LARGE = Identifier.fromNamespaceAndPath("frostedfoliage_test", "00_oversized");
    private static final Identifier TAIL = extra(32);
    private static final BlockPos LEAF = new BlockPos(0, 70, 0);

    public void run(ClientGameTestContext context) {
        Set<Identifier> vanilla = context.computeOnClient(c -> {
            Set<Identifier> result = new TreeSet<>();
            c.getResourceManager().listResources("particles", id -> id.getPath().endsWith("leaves.json")).forEach((id, resource) -> {
                try (var reader = resource.openAsReader()) {
                    result.addAll(ParticleDescription.fromJson(GsonHelper.parse(reader)).getTextures());
                } catch (IOException e) { throw new AssertionError(e); }
            });
            return result;
        });
        check(vanilla.size() == 48, "Update the vanilla leaf-particle fixture: " + vanilla.size());
        check(TextureBudget.MAX_PARTICLE_TEXELS * 4 == 16L * 1024 * 1024, "Particle budget must be 16 MiB RGBA");
        var previous = context.computeOnClient(c -> List.copyOf(c.getResourcePackRepository().getSelectedIds()));
        Path pack;
        try { pack = Files.createTempDirectory(context.computeOnClient(c -> c.getResourcePackDirectory()), "frostedfoliage-particles-"); }
        catch (IOException e) { throw new AssertionError(e); }
        String packId = "file/" + pack.getFileName();
        Map<Identifier, Long> pixels = new LinkedHashMap<>();
        SnowConfig originalConfig = context.computeOnClient(c -> FrostedFoliageClient.system().config());
        try {
            var format = SharedConstants.getCurrentVersion().packVersion(PackType.CLIENT_RESOURCES);
            write(pack, "pack.mcmeta", "{\"pack\":{\"description\":\"Particle memory regression\",\"min_format\":["
                    + format.major() + "," + format.minor() + "],\"max_format\":[" + format.major() + "," + format.minor() + "]}}");
            metrics(context, "vanilla", 0, usedHeap());
            for (Identifier id : vanilla) {
                texture(pack, id, 256, 256, false);
                pixels.put(id, 65536L);
            }
            context.runOnClient(c -> {
                var repository = c.getResourcePackRepository(); repository.reload();
                var selected = new ArrayList<>(previous); selected.add(packId); repository.setSelected(selected);
            });
            reload(context, "hd-48");
            verify(context, packId, pixels, vanilla, false);

            // Four 128-square frames still cost 256*256 pixels, not just one frame's area.
            for (Identifier id : vanilla) texture(pack, id, 128, 512, true);
            reload(context, "animated-48");
            verify(context, packId, pixels, vanilla, true);

            for (int i = 0; i < 32; i++) {
                // Leave a 256-pixel gap: oversized remaining candidates must not prevent the small tail from fitting.
                int width = i == 15 ? 255 : 256;
                texture(pack, extra(i), width, 256, false);
                pixels.put(extra(i), (long) width * 256);
            }
            texture(pack, TAIL, 16, 16, false);
            pixels.put(TAIL, 256L);
            texture(pack, TOO_LARGE, 257, 256, false);
            pixels.put(TOO_LARGE, 257L * 256);
            var references = new ArrayList<>(pixels.keySet());
            references.addAll(vanilla); // Shared references must not consume the budget twice.
            references.add(Identifier.fromNamespaceAndPath("frostedfoliage_test", "00_missing"));
            definition(pack, DEFINITION, references);
            // This real particle type always selects one of the rejected originals.
            definition(pack, Identifier.withDefaultNamespace("particles/orange_poplar_leaves.json"), List.of(extra(31)));
            Set<Identifier> expected = new HashSet<>(vanilla);
            for (int i = 0; i < 16; i++) expected.add(extra(i));
            expected.add(TAIL);
            check(expected.stream().mapToLong(pixels::get).sum() == TextureBudget.MAX_PARTICLE_TEXELS, "Fixture must fill the budget exactly");
            reload(context, "overflow");
            verify(context, packId, pixels, expected, true);
            Collections.reverse(references);
            definition(pack, DEFINITION, references);
            reload(context, "overflow-reordered");
            verify(context, packId, pixels, expected, true);
            fallback(context);
            FrostedFoliageClient.LOGGER.info("PARTICLE BUDGET PASSED: HD, animation frames, exact limit, oversized source, duplicates, small tail, stable reload, original fallback");
        } catch (IOException e) { throw new AssertionError(e); }
        finally {
            context.runOnClient(c -> {
                FrostedFoliageClient.system().setPreview(false);
                FrostedFoliageClient.system().configure(originalConfig);
                c.getResourcePackRepository().setSelected(previous);
            });
            reload(context, "restored");
            context.runOnClient(c -> {
                var atlas = c.getAtlasManager().getAtlasOrThrow(AtlasIds.PARTICLES);
                for (Identifier id : vanilla) check(atlas.getSprite(SnowParticleSource.frostedId(id)).contents().name().equals(SnowParticleSource.frostedId(id)), "Reload did not reset budget for " + id);
                check(!atlas.getSprite(SnowParticleSource.frostedId(extra(0))).contents().name().equals(SnowParticleSource.frostedId(extra(0))), "Removed pack retained a generated sprite");
            });
            try (var files = Files.walk(pack)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
            } catch (IOException e) { throw new AssertionError(e); }
        }
    }

    private static void verify(ClientGameTestContext context, String packId, Map<Identifier, Long> pixels, Set<Identifier> expected, boolean animated) {
        context.runOnClient(c -> {
            check(c.getResourcePackRepository().getSelectedIds().contains(packId), "Reload silently dropped the test pack");
            var atlas = c.getAtlasManager().getAtlasOrThrow(AtlasIds.PARTICLES);
            long admitted = 0;
            for (var entry : pixels.entrySet()) {
                Identifier id = entry.getKey(), frosted = SnowParticleSource.frostedId(id);
                check(atlas.getSprite(id).contents().name().equals(id), "Original particle missing: " + id);
                var contents = atlas.getSprite(frosted).contents();
                boolean present = contents.name().equals(frosted);
                check(present == expected.contains(id), "Unexpected budget selection for " + id);
                if (present) {
                    admitted += entry.getValue();
                    check(contents.isTransparent(0, 0, 0) && !contents.isTransparent(0, 1, 0), "Frost changed source alpha: " + id);
                    if (id.getNamespace().equals("minecraft")) {
                        check(contents.isAnimated() == animated, "Lost animation for " + id);
                        check(contents.width() == (animated ? 128 : 256), "Changed particle resolution: " + id);
                        if (animated) check(contents.isTransparent(3, 0, 0), "Lost animation-frame alpha: " + id);
                    }
                }
            }
            long expectedPixels = expected.stream().mapToLong(pixels::get).sum();
            check(admitted == expectedPixels && admitted <= TextureBudget.MAX_PARTICLE_TEXELS, "Particle images exceeded budget: " + admitted);
            FrostedFoliageClient.LOGGER.info("PARTICLE BUDGET selection sprites={} baseRgbaBytes={}", expected.size(), admitted * 4);
        });
    }

    private static void fallback(ClientGameTestContext context) {
        context.runOnClient(c -> FrostedFoliageClient.system().configure(SnowConfig.builder().snowDepth(1).variation(0).build()));
        try (var world = context.worldBuilder().create()) {
            var server = world.getServer();
            server.runCommand("gamemode spectator @a");
            server.runCommand("tp @a 0 72 3");
            server.runCommand("fillbiome -16 64 -16 15 95 15 minecraft:snowy_taiga");
            server.runCommand("setblock 0 70 0 minecraft:orange_poplar_leaves[persistent=true]");
            context.runOnClient(c -> FrostedFoliageClient.system().setPreview(true));
            context.waitFor(c -> FrostedFoliageClient.system().coverage(LEAF) > 0.99, 1200);
            context.runOnClient(c -> {
                var particle = c.particleEngine.createParticle(ParticleTypes.ORANGE_POPLAR_LEAVES, 0.5, 69.95, 0.5, 0, 0, 0);
                check(particle != null, "No fallback leaf particle");
                var sprite = ((SingleQuadParticleAccessor) particle).frostedfoliage$sprite();
                check(sprite.contents().name().equals(extra(31)), "Budget overflow did not preserve original leaf sprite");
                c.particleEngine.clearParticles();
                FrostedFoliageClient.system().setPreview(false);
            });
        }
    }

    private static Identifier extra(int index) { return Identifier.fromNamespaceAndPath("frostedfoliage_test", "zz_budget/sprite_" + String.format(java.util.Locale.ROOT, "%02d", index)); }
    private static void texture(Path pack, Identifier id, int width, int height, boolean animated) throws IOException {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) for (int x = 1; x < width; x++) image.setRGB(x, y, 0xff48b518);
        Path file = pack.resolve("assets/" + id.getNamespace() + "/textures/particle/" + id.getPath() + ".png");
        Files.createDirectories(file.getParent()); ImageIO.write(image, "png", file.toFile());
        if (animated) Files.writeString(file.resolveSibling(file.getFileName() + ".mcmeta"), "{\"animation\":{\"frametime\":2}}");
    }
    private static void definition(Path pack, Identifier id, List<Identifier> textures) throws IOException {
        var json = new JsonObject(); var array = new JsonArray();
        textures.forEach(texture -> array.add(texture.toString())); json.add("textures", array);
        write(pack, "assets/" + id.getNamespace() + "/" + id.getPath(), json.toString());
    }
    private static void write(Path pack, String name, String content) throws IOException {
        Path file = pack.resolve(name); Files.createDirectories(file.getParent()); Files.writeString(file, content);
    }
    private static long usedHeap() { return Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory(); }
    private static void reload(ClientGameTestContext context, String label) {
        long start = System.nanoTime(); long[] peakHeap = {usedHeap()};
        FrostedFoliageClient.LOGGER.info("PARTICLE BUDGET reload begin {} pid={}", label, ProcessHandle.current().pid());
        var reload = context.computeOnClient(c -> c.reloadResourcePacks());
        context.waitFor(c -> { peakHeap[0] = Math.max(peakHeap[0], usedHeap()); return reload.isDone(); }, 1200);
        reload.join();
        context.waitFor(c -> c.gui.overlay() == null, 1200);
        metrics(context, label, (System.nanoTime() - start) / 1e6, peakHeap[0]);
    }
    private static void metrics(ClientGameTestContext context, String label, double reloadMs, long peakHeap) {
        context.runOnClient(c -> {
            var texture = c.getAtlasManager().getAtlasOrThrow(AtlasIds.PARTICLES).getTexture();
            FrostedFoliageClient.LOGGER.info("PARTICLE BUDGET metrics label={} reloadMs={} sampledPeakHeapBytes={} atlas={}x{}", label, reloadMs, peakHeap, texture.getWidth(0), texture.getHeight(0));
        });
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
