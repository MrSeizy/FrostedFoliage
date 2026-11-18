package dev.frostedfoliage.core;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.PngInfo;

/** A PNG admitted to the snow atlas budget, including its entire animation sheet. */
public record SnowTexture(Resource resource, long pixels) {
    public static SnowTexture inspect(Resource source) throws IOException {
        PngInfo header;
        try (var stream = source.open()) {
            header = PngInfo.fromStream(stream);
        }
        long pixels = (long) header.width() * header.height();
        if (header.width() <= 0 || header.height() <= 0 || pixels > TextureBudget.MAX_SOURCE_PIXELS) {
            throw new IOException("Invalid snow texture dimensions " + header.width() + "x" + header.height()
                    + " (maximum " + TextureBudget.MAX_SOURCE_PIXELS + " pixels)");
        }
        // A directory pack can change between discovery and lazy decoding. Validate the actual decoder stream.
        var checked = new Resource(source.source(), () -> openChecked(source, header), source::metadata);
        return new SnowTexture(checked, pixels);
    }

    private static InputStream openChecked(Resource source, PngInfo expected) throws IOException {
        var stream = new BufferedInputStream(source.open());
        try {
            stream.mark(33); // PNG signature and IHDR chunk
            if (!PngInfo.fromStream(stream).equals(expected)) throw new IOException("Snow texture header changed after discovery");
            stream.reset();
            return stream;
        } catch (IOException | RuntimeException e) {
            stream.close();
            throw e;
        }
    }
}
