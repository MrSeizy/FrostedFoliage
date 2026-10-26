package dev.frostedfoliage.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.serialization.MapCodec;
import dev.frostedfoliage.client.FrostedFoliageClient;
import dev.frostedfoliage.core.SnowMath;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
        Set<Identifier> textures = new HashSet<>();
        // Vanilla names every falling leaf particle *_leaves; any other particle keeps its own sprite.
        PARTICLES.listMatchingResources(manager).forEach((file, resource) -> {
            if (!PARTICLES.fileToId(file).getPath().endsWith("leaves")) return;
            try (var reader = resource.openAsReader()) {
                textures.addAll(ParticleDescription.fromJson(GsonHelper.parse(reader)).getTextures());
            } catch (Exception e) {
                FrostedFoliageClient.LOGGER.warn("Cannot read leaf particle definition {}: {}", file, e.toString());
            }
        });
        int added = 0;
        for (Identifier texture : textures) {
            var resource = manager.getResource(TEXTURE_ID_CONVERTER.idToFile(texture.withPrefix("particle/")));
            if (resource.isEmpty()) continue;
            Identifier id = frostedId(texture);
            output.add(id, loader -> frost(id, resource.get()));
            added++;
        }
        FrostedFoliageClient.LOGGER.info("Snow textures: {} falling leaf particle sprites", added);
    }

    private static SpriteContents frost(Identifier id, Resource resource) {
        NativeImage image = null;
        try (var stream = resource.open()) {
            image = NativeImage.read(stream);
            int width = image.getWidth(), height = image.getHeight();
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
