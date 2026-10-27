package dev.frostedfoliage.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.serialization.MapCodec;
import dev.frostedfoliage.client.FrostedFoliageClient;
import dev.frostedfoliage.core.PlantSnow;
import dev.frostedfoliage.core.SnowMath;
import dev.frostedfoliage.core.TextureBudget;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.atlas.SpriteResourceLoader;
import net.minecraft.client.renderer.texture.atlas.SpriteSource;
import net.minecraft.client.renderer.texture.atlas.sources.LazyLoadedImage;
import net.minecraft.client.resources.metadata.animation.AnimationMetadataSection;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.client.resources.metadata.texture.TextureMetadataSection;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.block.Block;

/** Generate alpha-preserving masks during resource reload, never during gameplay. */
public final class SnowSpriteSource implements SpriteSource {
    public static final MapCodec<SnowSpriteSource> CODEC = MapCodec.unit(SnowSpriteSource::new);
    public static final int STAGES = 16, VARIANTS = SnowMath.LEAF_VARIANTS, PLANT_VARIANTS = 2;
    public static final int LEAF_STRIDE = STAGES * VARIANTS, PLANT_STRIDE = STAGES * PLANT_VARIANTS;
    public record Catalog(Set<Identifier> sources, Map<Identifier, Set<Integer>> plantSources,
                          Set<Block> extraLeafBlocks, TextureBudget.Profile profile, double depth, double plantDepth, long texels) {
        public int edges() { return profile.edges(); }
    }
    private static volatile Catalog catalog = new Catalog(Set.of(), Map.of(), Set.of(), new TextureBudget.Profile(16, 1), 1, 0.5, 0);
    public static Catalog catalog() { return catalog; }
    public static boolean needsFoliageReload() {
        return !catalog.extraLeafBlocks().equals(FrostedFoliageClient.system().foliage().extraLeafBlocks());
    }
    public static Identifier snowId(Identifier original, int stage, int variant, int edge) {
        return Identifier.fromNamespaceAndPath("frostedfoliage", "generated/" + original.getNamespace() + "/" + original.getPath() + "/" + edge + "_" + variant + "_" + stage);
    }
    public static Identifier plantSnowId(Identifier original, int stage, int variant, int shape) {
        return Identifier.fromNamespaceAndPath("frostedfoliage", "generated/plants/" + original.getNamespace() + "/" + original.getPath() + "/" + shape + "_" + variant + "_" + stage);
    }
    private record Source(Identifier id, Identifier file, Resource resource, boolean leaf, long pixels) { }

    @Override public void run(ResourceManager manager, Output output) {
        var config = FrostedFoliageClient.system().config();
        Set<Block> extraBlocks = FrostedFoliageClient.system().foliage().extraLeafBlocks();
        Set<Identifier> leaves = new HashSet<>(FoliageTextures.leaves(manager, extraBlocks));
        config.extraTextures().forEach(id -> leaves.add(Identifier.parse(id)));
        double depth = config.snowDepth(), plantDepth = config.plantSnowDepth();
        var plantUses = config.plantsEnabled() ? FoliageTextures.plants(manager) : Map.<Identifier, Set<FoliageTextures.Use>>of();
        Set<Identifier> generated = new HashSet<>();
        var generatedPlants = new HashMap<Identifier, Set<Integer>>();
        // Resolve textures used by eligible models, not unrelated bamboo/dripleaf filenames.
        var resources = manager.listResources("textures", id -> id.getPath().endsWith(".png")
                && (leaves.contains(TEXTURE_ID_CONVERTER.fileToId(id)) || plantUses.containsKey(TEXTURE_ID_CONVERTER.fileToId(id))));
        var candidates = resources.entrySet().stream().sorted(Comparator
                .<Map.Entry<Identifier, Resource>, Boolean>comparing(e -> !leaves.contains(TEXTURE_ID_CONVERTER.fileToId(e.getKey())))
                .thenComparing(Map.Entry::getKey)).limit(256).toList();
        if (resources.size() > candidates.size()) FrostedFoliageClient.LOGGER.warn("Snow texture discovery limited to 256 of {} sources", resources.size());
        var accepted = new ArrayList<Source>();
        var bounds = new HashMap<Identifier, FoliageTextures.Bounds>();
        for (var entry : candidates) {
            Identifier original = TEXTURE_ID_CONVERTER.fileToId(entry.getKey());
            Resource resource = entry.getValue();
            boolean leaf = leaves.contains(original);
            var uses = plantUses.getOrDefault(original, Set.of());
            long pixels;
            try (var stream = resource.open(); NativeImage probe = NativeImage.read(stream)) {
                pixels = (long) probe.getWidth() * probe.getHeight();
                if (pixels > TextureBudget.MAX_SOURCE_PIXELS) {
                    FrostedFoliageClient.LOGGER.warn("Skipping snow texture {} larger than {} source pixels", original, TextureBudget.MAX_SOURCE_PIXELS);
                    continue;
                }
                if (!uses.isEmpty()) bounds.put(original, visibleBounds(probe, resource));
            } catch (Exception e) {
                FrostedFoliageClient.LOGGER.warn("Cannot read foliage texture {}: {}", original, e.toString());
                continue;
            }
            accepted.add(new Source(original, entry.getKey(), resource, leaf, pixels));
        }
        var plantSpans = FoliageTextures.spans(plantUses, bounds);
        var costs = accepted.stream().map(source -> new TextureBudget.Source(source.pixels(), source.leaf(),
                plantSpans.containsKey(source.id()) ? 1 + plantSpans.get(source.id()).size() : 0)).toList();
        var profile = TextureBudget.choose(costs, depth > 0 && depth < 1);
        int edges = profile.edges(), step = profile.step();
        if (profile.stages() < STAGES || (edges == 1 && depth > 0 && depth < 1)) {
            FrostedFoliageClient.LOGGER.info("Snow atlas budget: using {} buildup stages and {} edge masks; source resolution and spatial patterns retained",
                    profile.stages(), edges);
        }
        long pixelBudget = TextureBudget.MAX_TEXELS;
        int totalMasks = 0;
        for (Source source : accepted) {
            Map<Integer, PlantSnow.Span> spans = plantSpans.getOrDefault(source.id(), Map.of());
            int masksPerSource = profile.stages() * ((source.leaf() ? VARIANTS * edges : 0)
                    + PLANT_VARIANTS * (spans.isEmpty() ? 0 : 1 + spans.size()));
            if (masksPerSource == 0) continue;
            long texels = source.pixels() * masksPerSource;
            if (texels > pixelBudget) {
                FrostedFoliageClient.LOGGER.warn("Skipping snow texture {} after reducing masks to fit the atlas budget", source.id());
                continue;
            }
            pixelBudget -= texels;
            LazyLoadedImage image = new LazyLoadedImage(source.file(), source.resource(), masksPerSource);
            totalMasks += masksPerSource;
            if (source.leaf()) {
                generated.add(source.id());
                for (int edge = 0; edge < edges; edge++) for (int variant = 0; variant < VARIANTS; variant++) for (int stage = step; stage <= STAGES; stage += step) {
                    Identifier id = snowId(source.id(), stage, variant, edge);
                    output.add(id, new MaskLoader(image, source.resource(), id, stage, variant, depth, edge, null));
                }
            }
            if (!spans.isEmpty()) {
                generatedPlants.put(source.id(), Set.copyOf(spans.keySet()));
                for (int variant = 0; variant < PLANT_VARIANTS; variant++) for (int stage = step; stage <= STAGES; stage += step) {
                    Identifier full = plantSnowId(source.id(), stage, variant, 0);
                    output.add(full, new MaskLoader(image, source.resource(), full, stage, variant, 1, 0, null));
                    for (var shape : spans.entrySet()) {
                        Identifier id = plantSnowId(source.id(), stage, variant, shape.getKey() + 1);
                        output.add(id, new MaskLoader(image, source.resource(), id, stage, variant, plantDepth, 0, shape.getValue()));
                    }
                }
            }
        }
        catalog = new Catalog(Set.copyOf(generated), Map.copyOf(generatedPlants), extraBlocks, profile, depth, plantDepth, TextureBudget.MAX_TEXELS - pixelBudget);
        FrostedFoliageClient.LOGGER.info("Snow textures: {} leaf sprites, {} plant sprites, {} masks, {} texels, depth={}/{}",
                generated.size(), generatedPlants.size(), totalMasks, catalog.texels(), depth, plantDepth);
    }

    private static FoliageTextures.Bounds visibleBounds(NativeImage image, Resource resource) throws IOException {
        FrameSize frame = resource.metadata().getSection(AnimationMetadataSection.TYPE)
                .map(a -> a.calculateFrameSize(image.getWidth(), image.getHeight())).orElse(new FrameSize(image.getWidth(), image.getHeight()));
        int min = frame.height(), max = -1;
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
            if ((image.getPixel(x, y) >>> 24) != 0) { min = Math.min(min, y % frame.height()); max = Math.max(max, y % frame.height()); }
        }
        return max < min ? new FoliageTextures.Bounds(0, 1) : new FoliageTextures.Bounds(1 - (max + 1.0) / frame.height(), 1 - min / (double) frame.height());
    }

    @Override public MapCodec<? extends SpriteSource> codec() { return CODEC; }

    private record MaskLoader(LazyLoadedImage source, Resource resource, Identifier id, int stage, int variant, double depth, int edge, PlantSnow.Span plantSpan) implements DiscardableLoader {
        @Override public SpriteContents get(SpriteResourceLoader loader) {
            NativeImage result = null;
            try {
                NativeImage original = source.get();
                var animation = resource.metadata().getSection(AnimationMetadataSection.TYPE);
                var texture = resource.metadata().getSection(TextureMetadataSection.TYPE);
                FrameSize frame = animation.map(a -> a.calculateFrameSize(original.getWidth(), original.getHeight()))
                        .orElse(new FrameSize(original.getWidth(), original.getHeight()));
                result = new NativeImage(original.getWidth(), original.getHeight(), false);
                for (int y = 0; y < original.getHeight(); y++) for (int x = 0; x < original.getWidth(); x++) {
                    int px = x % frame.width(), py = y % frame.height();
                    int pixel = SnowMath.snowPixel(original.getPixel(x, y), px, py, frame.width(), frame.height(), stage, variant);
                    result.setPixel(x, y, plantSpan == null ? SnowMath.depthPixel(pixel, px, py, frame.width(), frame.height(), depth, edge, variant)
                            : PlantSnow.pixel(pixel, px, py, frame.width(), frame.height(), depth, plantSpan, variant));
                }
                SpriteContents contents = new SpriteContents(id, frame, result, animation, List.of(), texture);
                result = null;
                return contents;
            } catch (Exception e) {
                FrostedFoliageClient.LOGGER.warn("Cannot generate snow sprite {}: {}", id, e.toString());
                return null;
            } finally {
                if (result != null) result.close();
                source.release();
            }
        }
        @Override public void discard() { source.release(); }
    }
}
