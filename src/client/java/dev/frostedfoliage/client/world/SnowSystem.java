package dev.frostedfoliage.client.world;

import dev.frostedfoliage.config.SnowConfig;
import dev.frostedfoliage.core.BoundedSectionStore;
import dev.frostedfoliage.core.SectionScheduler;
import dev.frostedfoliage.core.SnowMath;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/** Tick-thread owner of simulation, discovery, and rebuild work. No per-frame scans. */
public final class SnowSystem {
    private final Foliage foliage = new Foliage();
    private final GroundSnowSystem ground;
    private final BoundedSectionStore<SectionSnow> snapshots = new BoundedSectionStore<>();
    private final SectionScheduler scheduler = new SectionScheduler(2 * SnowConfig.MAX_CACHED_SECTIONS);
    private volatile SnowConfig config;
    private volatile Progress progress = new Progress(0, 0);
    private ClientLevel world;
    private double mild, freezing, drySeconds;
    private int ticks, lastSignature = -1;
    private long captures, rebuilds, captureNanos, tickNanos;
    private int focusX, focusY, focusZ, scanRadius = -1, scanCursor;
    private boolean focused;
    private List<ChunkOffset> scanOrder = List.of();

    private record ChunkOffset(int x, int z) {
        long distance() { return (long) x * x + (long) z * z; }
    }

    public record Progress(double mild, double freezing) { }
    public SnowSystem(SnowConfig config) { this.config = config; foliage.refresh(config, false); ground = new GroundSnowSystem(config, foliage); }
    public SnowConfig config() { return config; }
    public Foliage foliage() { return foliage; }
    public GroundSnowSystem ground() { return ground; }
    public boolean active() { Progress p = progress; return config.enabled() && Math.max(p.mild, p.freezing) > 0; }

    public void configure(SnowConfig next) {
        SnowConfig previous = config;
        if (next.equals(previous)) return;
        config = next;
        boolean rules = previous.moddedFoliage() != next.moddedFoliage() || previous.plantsEnabled() != next.plantsEnabled()
                || !previous.includeBlocks().equals(next.includeBlocks()) || !previous.excludeBlocks().equals(next.excludeBlocks());
        if (rules) foliage.refresh(next, world != null);
        ground.configure(next);
        if (rules || previous.skyExposureSensitivity() != next.skyExposureSensitivity()) {
            // Keep old snapshots usable until each environment has been recaptured.
            scheduler.recaptureAll();
            scanCursor = 0;
        }
        if (rules || previous.enabled() != next.enabled() || previous.maximumCoverage() != next.maximumCoverage()
                || previous.plantMaximumCoverage() != next.plantMaximumCoverage() || previous.whiteness() != next.whiteness()
                || previous.variation() != next.variation() || previous.preferTopFaces() != next.preferTopFaces()
                || previous.snowDepth() != next.snowDepth() || previous.plantSnowDepth() != next.plantSnowDepth()) {
            scheduler.rebuildAll();
        }
        if (previous.maxCachedSections() != next.maxCachedSections()) {
            updateFocus(Minecraft.getInstance());
            scheduler.resize(next.maxCachedSections(), snapshots::remove);
            scanCursor = 0;
        }
    }

    /** Tag packets can change eligibility and heightmap predicates without changing the config. */
    public void tagsLoaded() {
        foliage.refresh(config, true);
        ground.invalidateAll();
        scheduler.invalidateAll();
        scanCursor = 0;
    }

    public void reset(ClientLevel next) {
        world = next;
        scheduler.clear(); snapshots.clear();
        focused = false; scanRadius = -1; scanCursor = 0; scanOrder = List.of();
        mild = freezing = drySeconds = 0;
        progress = new Progress(0, 0);
        ticks = 0; lastSignature = -1;
        captures = rebuilds = captureNanos = tickNanos = 0;
        foliage.refresh(config, next != null);
        ground.reset(next);
    }

    public void load(ClientLevel level, LevelChunk chunk) {
        if (chunk == null) return;
        if (world != level) reset(level);
        updateFocus(Minecraft.getInstance());
        ground.load(chunk);
        int before = scheduler.size();
        if (!discover(chunk, true) || scheduler.size() < before) scanCursor = 0;
    }

    private boolean discover(LevelChunk chunk, boolean invalidate) {
        boolean complete = true;
        for (int i = 0; i < chunk.getSectionsCount(); i++) {
            complete &= discoverSection(chunk, i, invalidate);
        }
        return complete;
    }

    private boolean discoverSection(LevelChunk chunk, int index, boolean invalidate) {
        long key = SectionPos.asLong(chunk.getPos().x(), world.getSectionYFromSectionIndex(index), chunk.getPos().z());
        if (chunk.getSection(index).maybeHas(foliage::test)) {
            return scheduler.discover(key, config.maxCachedSections(), invalidate, snapshots::remove);
        }
        scheduler.discard(key, snapshots::remove);
        return true;
    }

    public void unload(ClientLevel level, LevelChunk chunk) {
        if (level != world) return;
        ground.unload(chunk);
        for (int i = 0; i < level.getSectionsCount(); i++) {
            long key = SectionPos.asLong(chunk.getPos().x(), level.getSectionYFromSectionIndex(i), chunk.getPos().z());
            scheduler.unload(key); snapshots.remove(key);
        }
        scanCursor = 0;
    }

    public void changed(ClientLevel level, BlockPos pos, BlockState oldState, BlockState newState) {
        if (level != world) return;
        int cx = pos.getX() >> 4, cz = pos.getZ() >> 4;
        LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
        if (chunk == null) return;
        boolean heightChanged = changesHeight(chunk, pos, oldState, newState, Heightmap.Types.MOTION_BLOCKING)
                || changesHeight(chunk, pos, oldState, newState, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES);
        ground.changed(chunk, pos, oldState, newState, heightChanged);
        if (!heightChanged && !foliage.test(oldState) && !foliage.test(newState)) return;
        int section = level.getSectionIndex(pos.getY());
        int before = scheduler.size();
        boolean complete = true;
        // A column's new top can affect foliage below it, never sections above it.
        for (int i = heightChanged ? 0 : section; i <= section; i++) complete &= discoverSection(chunk, i, true);
        if (!complete || scheduler.size() < before) scanCursor = 0;
    }

    private static boolean changesHeight(LevelChunk chunk, BlockPos pos, BlockState oldState, BlockState newState, Heightmap.Types type) {
        return type.isOpaque().test(oldState) != type.isOpaque().test(newState)
                && chunk.getHeight(type, pos.getX() & 15, pos.getZ() & 15) <= pos.getY();
    }

    private void updateFocus(Minecraft client) {
        if (world == null || client.level != world || client.player == null) return;
        BlockPos pos = client.player.blockPosition();
        int x = pos.getX() >> 4, y = pos.getY() >> 4, z = pos.getZ() >> 4;
        if (!focused || x != focusX || y != focusY || z != focusZ) {
            focusX = x; focusY = y; focusZ = z; focused = true;
            // Captured coordinates keep the ordered sets' comparator stable between reprioritizations.
            scheduler.prioritize(key -> {
                long dx = (long) SectionPos.x(key) - x, dy = (long) SectionPos.y(key) - y, dz = (long) SectionPos.z(key) - z;
                return dx * dx + dy * dy + dz * dz;
            });
            scanCursor = 0;
        }
        int radius = client.options.getEffectiveRenderDistance();
        if (radius != scanRadius) {
            scanRadius = radius;
            var offsets = new ArrayList<ChunkOffset>();
            for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
                offsets.add(new ChunkOffset(dx, dz));
            }
            offsets.sort(Comparator.comparingLong(ChunkOffset::distance).thenComparingInt(ChunkOffset::x).thenComparingInt(ChunkOffset::z));
            scanOrder = List.copyOf(offsets);
            scanCursor = 0;
        }
    }

    /** Reconsiders already-loaded chunks after movement without retaining an unbounded rejected-key index. */
    private void discoverNearby() {
        if (!focused) return;
        for (int i = 0; i < 4 * config.captureBudget() && scanCursor < scanOrder.size(); i++) {
            ChunkOffset offset = scanOrder.get(scanCursor);
            if (scheduler.size() >= config.maxCachedSections() && offset.distance() > scheduler.farthestDistance()) {
                scanCursor = scanOrder.size();
                return;
            }
            LevelChunk chunk = world.getChunkSource().getChunkNow(focusX + offset.x(), focusZ + offset.z());
            if (chunk != null && !discover(chunk, false)) return;
            scanCursor++;
        }
    }

    public void tick(Minecraft client) {
        if (client.level != world) reset(client.level);
        if (world == null || client.isPaused()) return;
        long start = System.nanoTime();
        updateFocus(client);
        ticks++;
        double rain = world.canHaveWeather() ? world.getRainLevel(1) : 0;
        drySeconds = rain > 0.01 ? 0 : drySeconds + 0.05;
        mild = SnowMath.advance(mild, rain, 0.05, drySeconds, false, config);
        freezing = SnowMath.advance(freezing, rain, 0.05, drySeconds, true, config);
        int interval = Math.max(10, (int) Math.ceil(20 / config.maxVisualUpdatesPerSecond()));
        if (ticks % interval == 0) {
            int mildStage = SnowMath.stage(mild, config.steps()), frozenStage = SnowMath.stage(freezing, config.steps());
            int signature = mildStage * 17 + frozenStage;
            if (signature != lastSignature) {
                progress = new Progress(mildStage / 16.0, frozenStage / 16.0);
                lastSignature = signature;
                for (long key : scheduler.retainedKeys()) {
                    SectionSnow data = snapshots.get(key);
                    if (data != null && data.hasSnow()) scheduler.rebuild(key);
                }
            }
        }
        if (active()) discoverNearby();
        if (active()) for (int i = 0; i < config.captureBudget(); i++) {
            Long key = scheduler.nextCapture();
            if (key == null) break;
            int x = SectionPos.x(key), y = SectionPos.y(key), z = SectionPos.z(key);
            LevelChunk chunk = world.getChunkSource().getChunkNow(x, z);
            if (chunk == null) { scheduler.unload(key); snapshots.remove(key); scanCursor = 0; continue; }
            if (!chunk.getSection(world.getSectionIndexFromSectionY(y)).maybeHas(foliage::test)) {
                scheduler.discard(key, snapshots::remove); scanCursor = 0; continue;
            }
            long captureStart = System.nanoTime();
            SectionSnow data = SectionSnow.capture(world, chunk, y, foliage, config);
            boolean changed = !data.sameEnvironment(snapshots.get(key));
            snapshots.put(key, data, config.maxCachedSections());
            captures++; captureNanos += System.nanoTime() - captureStart;
            scheduler.captured(key, data.hasSnow(), changed);
        }
        for (int i = 0; i < config.rebuildsPerTick(); i++) {
            Long key = scheduler.nextRebuild();
            if (key == null) break;
            if (!world.getChunkSource().hasChunk(SectionPos.x(key), SectionPos.z(key))) continue;
            client.levelExtractor.setSectionDirty(SectionPos.x(key), SectionPos.y(key), SectionPos.z(key));
            rebuilds++;
        }
        ground.tick(client);
        tickNanos += System.nanoTime() - start;
    }

    public double coverage(BlockPos pos) {
        return coverage(pos, false);
    }

    public double coverage(BlockPos pos, boolean plant) {
        if (!active()) return 0;
        SectionSnow snapshot = snapshots.get(SectionPos.asLong(pos));
        if (snapshot == null) return 0;
        int sample = snapshot.sample(pos);
        Progress p = progress;
        double weather = (sample & 128) != 0 ? p.freezing : p.mild;
        double variation = 1 - config.variation() * (1 - weather) * SnowMath.noise(pos.getX(), pos.getY(), pos.getZ());
        double maximum = plant ? config.plantMaximumCoverage() : config.maximumCoverage();
        return SnowMath.coverage(weather, (sample & 127) / 127.0) * variation * maximum;
    }

    public String diagnostics() {
        return String.format(java.util.Locale.ROOT,
                "coverage=%.3f/%.3f sections=%d snapshots=%d pending=%d/%d cleanup=%d evictions=%d captures=%d rebuilds=%d captureMs=%.3f tickUs=%.3f snapshotBytes<=%d",
                mild, freezing, scheduler.size(), snapshots.size(), scheduler.pendingCaptures(), scheduler.pendingRebuilds(), scheduler.pendingCleanup(), scheduler.evictions(), captures, rebuilds,
                captures == 0 ? 0 : captureNanos / 1e6 / captures, ticks == 0 ? 0 : tickNanos / 1e3 / ticks, snapshots.size() * 4096L)
                + " " + ground.diagnostics();
    }
}
