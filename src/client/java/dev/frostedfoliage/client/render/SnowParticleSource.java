package dev.frostedfoliage.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.serialization.MapCodec;
import dev.frostedfoliage.client.FrostedFoliageClient;
import dev.frostedfoliage.core.SnowMath;
import dev.frostedfoliage.core.TextureBudget;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.client.particle.ParticleDescription;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.atlas.SpriteSource;
import net.minecraft.client.resources.metadata.animation.AnimationMetadataSection;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.client.resources.metadata.texture.TextureMetadataSection;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.GsonHelper;

/** Fully frosted copies of falling leaf particle sprites, generated during resource reload. */
public final class SnowParticleSource implements SpriteSource {
    public static final MapCodec<SnowParticleSource> CODEC = MapCodec.unit(SnowParticleSource::new);
    private static final FileToIdConverter PARTICLES = FileToIdConverter.json("particles");

    public static Identifier frostedId(Identifier original) {
        return Identifier.fromNamespaceAndPath("frostedfoliage", "frosted/" + original.getNamespace() + "/" + original.getPath());
    }

    @Override public void run(ResourceManager manager, Output output) {
        // Reserve in identifier order before loaders run in parallel, so reloads keep the same sprites.
        Set<Identifier> textures = new TreeSet<>();
        // Vanilla names every falling leaf particle *_leaves; any other particle keeps its own sprite.
        PARTICLES.listMatchingResources(manager).forEach((file, resource) -> {
            if (!PARTICLES.fileToId(file).getPath().endsWith("leaves")) return;
            try (var reader = resource.openAsReader()) {
                textures.addAll(ParticleDescription.fromJson(GsonHelper.parse(reader)).getTextures());
            } catch (Exception e) {
                FrostedFoliageClient.LOGGER.warn("Cannot read leaf particle definition {}: {}", file, e.toString());
            }
        });
        int added = 0, skipped = 0;
        long remaining = TextureBudget.MAX_PARTICLE_TEXELS;
        for (Identifier texture : textures) {
            var resource = manager.getResource(TEXTURE_ID_CONVERTER.idToFile(texture.withPrefix("particle/")));
            if (resource.isEmpty()) continue;
            Identifier id = frostedId(texture);
            long pixels;
            // Probes are closed immediately; only admitted images reach the parallel decoding phase.
            try (var stream = resource.get().open(); NativeImage probe = NativeImage.read(stream)) {
                pixels = (long) probe.getWidth() * probe.getHeight();
                if (pixels > TextureBudget.MAX_SOURCE_PIXELS) {
                    FrostedFoliageClient.LOGGER.warn("Skipping frosted particle sprite {}: larger than {} source pixels", id, TextureBudget.MAX_SOURCE_PIXELS);
                    continue;
                }
            } catch (Exception e) {
                FrostedFoliageClient.LOGGER.warn("Cannot read leaf particle texture {}: {}", texture, e.toString());
                continue;
            }
            if (pixels > remaining) {
                skipped++;
                continue;
            }
            remaining -= pixels;
            output.add(id, loader -> frost(id, resource.get(), pixels));
            added++;
        }
        if (skipped > 0) FrostedFoliageClient.LOGGER.warn("Skipped {} frosted particle sprites to fit the 16 MiB image budget; original particles retained", skipped);
        FrostedFoliageClient.LOGGER.info("Snow textures: {} falling leaf particle sprites, {} texels", added, TextureBudget.MAX_PARTICLE_TEXELS - remaining);
    }

    private static SpriteContents frost(Identifier id, Resource resource, long reservedPixels) {
        NativeImage image = null;
        try (var stream = resource.open()) {
            image = NativeImage.read(stream);
            int width = image.getWidth(), height = image.getHeight();
            if ((long) width * height != reservedPixels) {
                FrostedFoliageClient.LOGGER.warn("Skipping frosted particle sprite {}: image size changed after budget reservation", id);
                return null;
            }
            var animation = resource.metadata().getSection(AnimationMetadataSection.TYPE);
            var texture = resource.metadata().getSection(TextureMetadataSection.TYPE);
            FrameSize frame = animation.map(a -> a.calculateFrameSize(width, height)).orElse(new FrameSize(width, height));
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) image.setPixel(x, y, SnowMath.frostPixel(image.getPixel(x, y)));
            SpriteContents contents = new SpriteContents(id, frame, image, animation, List.of(), texture);
            image = null;
            return contents;
        } catch (Exception e) {
            FrostedFoliageClient.LOGGER.warn("Cannot generate frosted particle sprite {}: {}", id, e.toString());
            return null;
        } finally {
            if (image != null) image.close();
        }
    }

    @Override public MapCodec<? extends SpriteSource> codec() { return CODEC; }
}
