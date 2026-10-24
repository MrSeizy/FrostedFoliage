package dev.frostedfoliage.test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Screenshot;

/** Captures a completed normal game frame, including the renderer's ordinary frame lifecycle. */
public final class GameFrameCapture {
    private static int counter;

    private GameFrameCapture() { }

    public static Path capture(ClientGameTestContext context, String name, int width, int height) {
        if (!name.matches("[a-z0-9-]+")) throw new IllegalArgumentException("Invalid screenshot name");
        if (width < 320 || height < 240) throw new IllegalArgumentException("Screenshot is smaller than the game window minimum");

        // Fabric's withSize screenshot path resizes the render target and immediately forces an
        // extra render. With Sodium/Iris that can omit the entire cutout layer in the saved image.
        // Resize through the normal window path, let complete frames settle, then only read back.
        context.runOnClient(c -> c.getWindow().setWindowed(width, height));
        context.waitFor(c -> c.getWindow().getWidth() == width && c.getWindow().getHeight() == height
                && c.gameRenderer.mainRenderTarget().width == width && c.gameRenderer.mainRenderTarget().height == height, 200);
        long firstFrame = context.computeOnClient(c -> FrameMetrics.completedFrames());
        context.waitFor(c -> FrameMetrics.completedFrames() - firstFrame >= 3, 200);

        Path output;
        do { output = Path.of("screenshots", "%04d_%s.png".formatted(counter++, name)); }
        while (Files.exists(output));
        Path destination = output;
        var saved = new CompletableFuture<Void>();
        context.runOnClient(c -> Screenshot.takeScreenshot(c.gameRenderer.mainRenderTarget(), image -> {
            try (image) {
                if (image.getWidth() != width || image.getHeight() != height)
                    throw new IllegalStateException("Window changed size during screenshot capture");
                Files.createDirectories(destination.getParent());
                image.writeToFile(destination);
                saved.complete(null);
            } catch (Exception e) { saved.completeExceptionally(e); }
        }));
        context.waitFor(c -> saved.isDone(), 200);
        saved.join();
        return destination;
    }
}
