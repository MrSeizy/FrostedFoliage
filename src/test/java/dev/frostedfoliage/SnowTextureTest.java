package dev.frostedfoliage;

import dev.frostedfoliage.core.SnowTexture;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.server.packs.resources.Resource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SnowTextureTest {
    // Only the PNG signature and the IHDR fields read by PngInfo. No image data is needed for admission.
    private static byte[] header(int width, int height) {
        return ByteBuffer.allocate(26).putLong(0x89504e470d0a1a0aL).putInt(13).putInt(0x49484452)
                .putInt(width).putInt(height).put((byte) 8).put((byte) 6).array();
    }

    @Test void rejectsOversizedSheetsFromTheHeaderAlone() {
        var resource = new Resource(null, () -> new ByteArrayInputStream(header(257, 256)));
        var error = assertThrows(IOException.class, () -> SnowTexture.inspect(resource));
        assertTrue(error.getMessage().contains("257x256"));
    }

    @Test void checksTheReopenedStreamAndRewindsItForTheDecoder() throws IOException {
        var bytes = new AtomicReference<>(header(128, 512));
        var resource = new Resource(null, () -> new ByteArrayInputStream(bytes.get()));
        var texture = SnowTexture.inspect(resource);
        assertEquals(65536, texture.pixels());
        try (var stream = texture.resource().open()) {
            assertArrayEquals(bytes.get(), stream.readAllBytes());
        }
        bytes.set(header(128, 513));
        assertThrows(IOException.class, () -> texture.resource().open());
    }
}
