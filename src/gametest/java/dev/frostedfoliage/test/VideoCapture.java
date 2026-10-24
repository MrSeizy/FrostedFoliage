package dev.frostedfoliage.test;

import com.google.gson.GsonBuilder;
import dev.frostedfoliage.client.FrostedFoliageClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;

/** Test-only framebuffer recording; frame timestamps allow encoding at the actual captured pace. */
public final class VideoCapture {
    private VideoCapture() { }

    public static void recordTimelapse(ClientGameTestContext context, Runnable startSnowfall, BlockPos probe) {
        Path directory = context.computeOnClient(c -> c.gameDirectory.toPath().resolve("video-buildup"));
        try { Files.createDirectories(directory); }
        catch (java.io.IOException e) { throw new RuntimeException(e); }
        int[] previousSize = context.computeOnClient(c -> {
            if (FrostedFoliageClient.system().config().snowDepth() != 1 || FrostedFoliageClient.system().coverage(probe) != 0) {
                throw new AssertionError("Timelapse must start with bare leaves at 100% snow depth");
            }
            int[] size = {c.getWindow().getWidth(), c.getWindow().getHeight()};
            c.getWindow().setWidth(1440); c.getWindow().setHeight(900);
            c.gameRenderer.resize(1440, 900);
            return size;
        });
        var frames = new ArrayList<TimelapseFrame>();
        try {
            context.waitTicks(4);
            long firstTick = context.computeOnClient(c -> c.level.getGameTime());
            long firstNanos = System.nanoTime();
            int nextReport = 30;
            boolean finishing = false;
            while (true) {
                String filename = String.format(Locale.ROOT, "%05d.png", frames.size());
                TimelapseFrame frame = context.computeOnClient(c -> new TimelapseFrame(filename,
                        (c.level.getGameTime() - firstTick) / 20.0, (System.nanoTime() - firstNanos) / 1e9,
                        FrostedFoliageClient.system().coverage(probe), FrostedFoliageClient.system().coverage(probe.below(4))));
                saveFrame(context, directory.resolve(filename));
                frames.add(frame);
                if (frame.seconds() >= nextReport) {
                    FrostedFoliageClient.LOGGER.info("TIMELAPSE {}s top={} lower={} frames={}", frame.seconds(), frame.coverage(), frame.lowerCoverage(), frames.size());
                    nextReport += 30;
                }
                if (finishing) break;
                if (frame.seconds() > 240) throw new AssertionError("Snow did not finish accumulating in the timelapse");
                if (frames.size() == 1) startSnowfall.run();
                finishing = frame.lowerCoverage() >= 0.999
                        && context.computeOnClient(c -> FrostedFoliageClient.system().diagnostics().contains("pending=0/0 "));
                context.waitTicks(finishing ? 40 : 8);
            }
            Files.writeString(directory.resolve("frames.json"), new GsonBuilder().setPrettyPrinting().create().toJson(frames));
            FrostedFoliageClient.LOGGER.info("TIMELAPSE COMPLETE frames={} gameSeconds={} wallSeconds={} depth={}",
                    frames.size(), frames.getLast().seconds(), frames.getLast().wallSeconds(),
                    context.computeOnClient(c -> FrostedFoliageClient.system().config().snowDepth()));
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        } finally {
            context.runOnClient(c -> {
                c.getWindow().setWidth(previousSize[0]); c.getWindow().setHeight(previousSize[1]);
                c.gameRenderer.resize(previousSize[0], previousSize[1]);
            });
        }
    }

    private record TimelapseFrame(String filename, double seconds, double wallSeconds, double coverage, double lowerCoverage) { }

    private static void saveFrame(ClientGameTestContext context, Path frame) {
        var captured = context.computeOnClient(c -> {
            var done = new CompletableFuture<Void>();
            Screenshot.takeScreenshot(c.gameRenderer.mainRenderTarget(), image -> {
                try (image) { image.writeToFile(frame); done.complete(null); }
                catch (Throwable error) { done.completeExceptionally(error); }
            });
            return done;
        });
        context.waitFor(c -> captured.isDone(), 200);
        captured.join();
    }

    public static void record(ClientGameTestContext context, int percent, int seconds) {
        Path directory = context.computeOnClient(c -> c.gameDirectory.toPath().resolve("video-depth").resolve(String.format(Locale.ROOT, "%03d", percent)));
        try { Files.createDirectories(directory); }
        catch (java.io.IOException e) { throw new RuntimeException(e); }
        int[] previousSize = context.computeOnClient(c -> {
            int[] size = {c.getWindow().getWidth(), c.getWindow().getHeight()};
            c.getWindow().setWidth(1440); c.getWindow().setHeight(900);
            c.gameRenderer.resize(1440, 900);
            return size;
        });
        var timestamps = new ArrayList<Long>();
        try {
            context.waitTicks(4);
            long start = System.nanoTime();
            while (System.nanoTime() - start < seconds * 1_000_000_000L || timestamps.size() < 2) {
                Path frame = directory.resolve(String.format(Locale.ROOT, "%05d.png", timestamps.size()));
                timestamps.add(System.nanoTime() - start);
                saveFrame(context, frame);
                context.waitTick();
            }
            long end = System.nanoTime() - start;
            var manifest = new StringBuilder("ffconcat version 1.0\n");
            for (int i = 0; i < timestamps.size(); i++) {
                long next = i + 1 < timestamps.size() ? timestamps.get(i + 1) : end;
                manifest.append(String.format(Locale.ROOT, "file '%05d.png'\nduration %.9f\n", i, (next - timestamps.get(i)) / 1e9));
            }
            manifest.append(String.format(Locale.ROOT, "file '%05d.png'\n", timestamps.size() - 1));
            Files.writeString(directory.resolve("frames.ffconcat"), manifest);
            FrostedFoliageClient.LOGGER.info("VIDEO CAPTURE depth={} frames={} duration={}s", percent, timestamps.size(), end / 1e9);
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        } finally {
            context.runOnClient(c -> {
                c.getWindow().setWidth(previousSize[0]); c.getWindow().setHeight(previousSize[1]);
                c.gameRenderer.resize(previousSize[0], previousSize[1]);
            });
        }
    }
}
