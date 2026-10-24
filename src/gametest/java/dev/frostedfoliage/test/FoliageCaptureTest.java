package dev.frostedfoliage.test;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import dev.frostedfoliage.client.FrostedFoliageClient;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;

/** Exercises the natural-world scene where forced screenshot renders dropped the cutout layer. */
public final class FoliageCaptureTest {
    private final JsonArray results = new JsonArray();
    private int failures;

    public void run(ClientGameTestContext context, TestSingleplayerContext world) throws IOException {
        var server = world.getServer();
        server.runCommand("tp @a 499 66.8 1761.2 -32.005 19.190");
        context.runOnClient(c -> c.options.fov().set(50));
        var depthReload = context.computeOnClient(c -> FrostedFoliageClient.applyConfig(
                FrostedFoliageClient.system().config().withPlants(true, 0.35, 0.5)));
        context.waitFor(c -> depthReload.isDone() && c.gui.overlay() == null, 2400); depthReload.join();
        context.waitTicks(400);
        server.runCommand("weather clear 100000");
        context.waitTicks(100);
        world.getConnection().waitForChunksRender(false, 2400);
        if (!context.computeOnClient(c -> FrostedFoliageClient.system().active()))
            throw new AssertionError("Capture regression requires accumulated snow");

        boolean legacy = Boolean.getBoolean("frostedfoliage.test.capture.legacy");
        String prefix = legacy ? "capture-legacy" : "capture-frame";
        for (int i = 0; i < 24; i++) {
            int[][] sizes = {{1440, 810}, {1920, 1080}, {854, 480}};
            int[] size = sizes[i % sizes.length];
            capture(context, prefix + "-%02d".formatted(i), size[0], size[1], legacy);
        }
        var reload = context.computeOnClient(c -> c.reloadResourcePacks());
        context.waitFor(c -> reload.isDone() && c.gui.overlay() == null, 2400); reload.join();
        context.waitTicks(100);
        world.getConnection().waitForChunksRender(false, 2400);
        capture(context, prefix + "-after-reload", 1440, 810, legacy);

        var changedDepth = context.computeOnClient(c -> FrostedFoliageClient.applyConfig(
                FrostedFoliageClient.system().config().withSnowDepth(0.75)));
        context.waitFor(c -> changedDepth.isDone() && c.gui.overlay() == null, 2400); changedDepth.join();
        context.waitTicks(100);
        world.getConnection().waitForChunksRender(false, 2400);
        capture(context, prefix + "-after-depth", 1920, 1080, legacy);

        if (Boolean.getBoolean("frostedfoliage.test.shaderExpected")) {
            context.runOnClient(c -> net.irisshaders.iris.Iris.toggleShaders(c, false));
            context.waitTicks(100); world.getConnection().waitForChunksRender(false, 2400);
            capture(context, prefix + "-shaders-off", 854, 480, legacy);
            context.runOnClient(c -> net.irisshaders.iris.Iris.toggleShaders(c, true));
            context.waitTicks(100); world.getConnection().waitForChunksRender(false, 2400);
            capture(context, prefix + "-shaders-on", 1920, 1080, legacy);
        }
        JsonObject report = new JsonObject();
        report.addProperty("renderer", System.getProperty("frostedfoliage.test.renderer"));
        report.addProperty("legacy", legacy); report.addProperty("failures", failures);
        report.add("captures", results);
        Files.writeString(Path.of("foliage-capture-results.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report));
        if (failures != 0) throw new AssertionError(failures + " screenshots lost the visible poppies; see foliage-capture-results.json");
    }

    private void capture(ClientGameTestContext context, String name, int width, int height, boolean legacy) throws IOException {
        context.waitTicks(3);
        Path path = legacy ? context.takeScreenshot(TestScreenshotOptions.of(name).withSize(width, height))
                : GameFrameCapture.capture(context, name, width, height);
        int redPixels = 0;
        try (var stream = Files.newInputStream(path); var image = NativeImage.read(stream)) {
            if (image.getWidth() != width || image.getHeight() != height) throw new AssertionError("Wrong capture dimensions: " + path);
            // Four natural poppies are visible in this fixed camera view. The original failure
            // removes all of them, along with the leaves and grass, but leaves terrain intact.
            for (int y = height / 4; y < height * 85 / 100; y++) for (int x = 0; x < width; x++) {
                int color = image.getPixel(x, y), r = color >> 16 & 255, g = color >> 8 & 255, b = color & 255;
                if (r > 140 && r > g * 1.6 && r > b * 1.6) redPixels++;
            }
        }
        double fraction = (double) redPixels / (width * height);
        boolean passed = fraction > 0.002;
        if (!passed) failures++;
        JsonObject result = new JsonObject(); result.addProperty("path", path.toString());
        result.addProperty("width", width); result.addProperty("height", height);
        result.addProperty("redPixels", redPixels); result.addProperty("fraction", fraction); result.addProperty("passed", passed);
        results.add(result);
        FrostedFoliageClient.LOGGER.info("CAPTURE CHECK {} redPixels={} fraction={} passed={}", name, redPixels, fraction, passed);
    }
}
