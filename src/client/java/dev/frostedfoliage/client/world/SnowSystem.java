package dev.frostedfoliage.client.world;

import dev.frostedfoliage.config.ConfigDiff;
import dev.frostedfoliage.config.SnowConfig;
import dev.frostedfoliage.core.BoundedSectionStore;
import dev.frostedfoliage.core.ChunkRing;
import dev.frostedfoliage.core.MeshAcks;
import dev.frostedfoliage.core.MeshRevision;
import dev.frostedfoliage.core.ReprioritizeGate;
import dev.frostedfoliage.core.RetryBackoff;
import dev.frostedfoliage.core.SectionScheduler;
import dev.frostedfoliage.core.SectionRebuildQueue;
import dev.frostedfoliage.core.SnowMath;
import java.util.Locale;
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
    private final ChunkRing ring = new ChunkRing();
    // Keep dispatch short; the existing prioritized schedulers hold normal work until there is room.
    private final SectionRebuildQueue rebuildQueue = new SectionRebuildQueue(64);
    // Written by chunk meshing workers: what each section's mesh was last built from, kept only while that had snow.
    private final MeshAcks<MeshRevision> meshed = new MeshAcks<>();
    private final ReprioritizeGate reprioritize = new ReprioritizeGate(2, 40);
    private volatile SnowConfig config;
    private volatile Progress progress = new Progress(0, 0, 0);
    private volatile int appearance;
    private ClientLevel world;
    private double mild, freezing, drySeconds;
    private int ticks, lastSignature = -1, stale, unmeshed;
    private long captures, rebuilds, captureNanos, tickNanos;
    // Never reset versions: a worker from an old world must not match a new world's snapshot.
    private long nextSnapshotRevision, nextProgressRevision, sectionRebuilds;
    private int peakRebuilds;
    private Long rebuilding;
    private int focusX, focusY, focusZ;
    private boolean focused, preview, progressStale;
    private static final int RETRY_TICKS = 20;
    private final RetryBackoff retries = new RetryBackoff(RETRY_TICKS, 5);

    public record Progress(double mild, double freezing, long revision) { }
    public SnowSystem(SnowConfig config) { this.config = config; foliage.refresh(config, false); ground = new GroundSnowSystem(config, foliage, rebuildQueue); }
    public SnowConfig config() { return config; }
    public Foliage foliage() { return foliage; }
    public GroundSnowSystem ground() { return ground; }
    public boolean active() { Progress p = progress; return config.enabled() && Math.max(p.mild, p.freezing) > 0; }
    public boolean preview() { return preview; }

    /** Shows full snow wherever it can fall, leaving the weather simulation underneath untouched. Cleared on disconnect. */
    public void setPreview(boolean enabled) {
        if (preview == enabled) return;
        preview = enabled;
        progressStale = true;
    }

    public void configure(SnowConfig next) {
        SnowConfig previous = config;
        if (next.equals(previous)) return;
        config = next;
        var diff = new ConfigDiff(previous, next);
        if (diff.foliageRules()) foliage.refresh(next, world != null);
        ground.configure(next);
        if (diff.exposure()) {
            // Keep old snapshots usable until each environment has been recaptured.
            scheduler.recaptureAll();
            ring.restart();
        }
        if (diff.appearance()) { appearance++; scheduler.rebuildAll(); }
        if (diff.cacheSize()) {
            updateFocus(Minecraft.getInstance());
            scheduler.resize(next.maxCachedSections(), snapshots::remove);
            ring.restart();
        }
    }

    /** Tag packets can change eligibility and heightmap predicates without changing the config. */
    public void tagsLoaded() {
        foliage.refresh(config, true);
        ground.invalidateAll();
        scheduler.invalidateAll();
        ring.restart();
    }

    public void reset(ClientLevel next) {
        world = next;
        scheduler.clear(); snapshots.clear(); meshed.clear(); retries.clear(); reprioritize.reset();
        rebuildQueue.clear(); rebuilding = null; sectionRebuilds = 0; peakRebuilds = 0;
        focused = false; ring.restart();
        mild = freezing = drySeconds = 0;
        progress = new Progress(0, 0, ++nextProgressRevision);
        ticks = 0; lastSignature = -1; stale = 0;
        captures = rebuilds = captureNanos = tickNanos = 0;
        if (next == null) preview = false;
        foliage.refresh(config, next != null);
        ground.reset(next);
    }

    public void load(ClientLevel level, LevelChunk chunk) {
        if (chunk == null) return;
        if (world != level) reset(level);
        updateFocus(Minecraft.getInstance());
        ground.load(chunk);
        int before = scheduler.size();
        if (!discover(chunk, true) || scheduler.size() < before) ring.restart();
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
            scheduler.unload(key); snapshots.remove(key); meshed.remove(key); retries.settled(key);
            rebuildQueue.remove(key);
            if (rebuilding != null && rebuilding == key) rebuilding = null;
        }
        ring.restart();
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
        if (!complete || scheduler.size() < before) ring.restart();
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
            ring.restart();
        }
        // Captured coordinates keep the ordered sets' comparator stable between reprioritizations.
        if (reprioritize.due(x, y, z, ticks)) scheduler.prioritize(key -> {
            long dx = (long) SectionPos.x(key) - x, dy = (long) SectionPos.y(key) - y, dz = (long) SectionPos.z(key) - z;
            return dx * dx + dy * dy + dz * dz;
        });
        ring.resize(client.options.getEffectiveRenderDistance());
    }

    /** Reconsiders already-loaded chunks after movement without retaining an unbounded rejected-key index. */
    private void discoverNearby() {
        if (!focused) return;
        ring.scan(4 * config.captureBudget(),
                () -> scheduler.size() >= config.maxCachedSections() ? scheduler.farthestDistance() : Long.MAX_VALUE,
                (dx, dz) -> {
                    LevelChunk chunk = world.getChunkSource().getChunkNow(focusX + dx, focusZ + dz);
                    return chunk == null || discover(chunk, false);
                });
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
        if (progressStale || ticks % interval == 0) {
            progressStale = false;
            int mildStage = preview ? 16 : SnowMath.stage(mild, config.steps());
            int frozenStage = preview ? 16 : SnowMath.stage(freezing, config.steps());
            int signature = mildStage * 17 + frozenStage;
            if (signature != lastSignature) {
                progress = new Progress(mildStage / 16.0, frozenStage / 16.0, ++nextProgressRevision);
                lastSignature = signature;
                retries.clear();
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
            if (chunk == null) { scheduler.unload(key); snapshots.remove(key); ring.restart(); continue; }
            if (!chunk.getSection(world.getSectionIndexFromSectionY(y)).maybeHas(foliage::test)) {
                scheduler.discard(key, snapshots::remove); ring.restart(); continue;
            }
            long captureStart = System.nanoTime();
            SectionSnow data = SectionSnow.capture(world, chunk, y, foliage, config, ++nextSnapshotRevision);
            boolean changed = !data.sameEnvironment(snapshots.get(key));
            // An unchanged environment keeps its snapshot object: meshes built from it stay acknowledged.
            if (changed) snapshots.put(key, data, config.maxCachedSections());
            captures++; captureNanos += System.nanoTime() - captureStart;
            if (changed) retries.settled(key);
            scheduler.captured(key, data.hasSnow(), changed);
        }
        // Alternate producers, including on successive retry passes, so neither monopolizes a full queue.
        boolean foliageFirst = (ticks + ticks / RETRY_TICKS) % 2 == 0;
        if (foliageFirst) {
            if (ticks % RETRY_TICKS == 0) retryLostRebuilds();
            enqueueRebuilds();
        }
        ground.tick(client);
        if (!foliageFirst) {
            if (ticks % RETRY_TICKS == 0) retryLostRebuilds();
            enqueueRebuilds();
        }
        int dispatched = rebuildQueue.drain(2 * config.rebuildsPerTick(),
                key -> world.getChunkSource().hasChunk(SectionPos.x(key), SectionPos.z(key)), (key, sources) -> {
                    client.levelExtractor.setSectionDirty(SectionPos.x(key), SectionPos.y(key), SectionPos.z(key));
                    if ((sources & SectionRebuildQueue.FOLIAGE) != 0) rebuilds++;
                    if ((sources & SectionRebuildQueue.GROUND) != 0) ground.rebuilt();
                });
        sectionRebuilds += dispatched;
        peakRebuilds = Math.max(peakRebuilds, dispatched);
        tickNanos += System.nanoTime() - start;
    }

    private void enqueueRebuilds() {
        for (int i = 0; i < config.rebuildsPerTick(); i++) {
            if (rebuilding == null) rebuilding = scheduler.nextRebuild();
            if (rebuilding == null) break;
            long key = rebuilding;
            if (world.getChunkSource().hasChunk(SectionPos.x(key), SectionPos.z(key))
                    && !rebuildQueue.request(key, SectionRebuildQueue.FOLIAGE, false)) break;
            rebuilding = null;
        }
    }

    public double coverage(BlockPos pos) {
        return coverage(pos, false);
    }

    public double coverage(BlockPos pos, boolean plant) {
        return coverage(snapshots.get(SectionPos.asLong(pos)), progress, config, pos, plant);
    }

    /**
     * Called by chunk meshing workers for every block they mesh. The first block of a run acknowledges what the
     * section's mesh is being built from, so a run that straddles a change records the older state and is retried.
     * A run that starts without snow removes the acknowledgment: whatever an older snapshot put in the mesh is gone,
     * even when the section no longer holds any eligible foliage.
     */
    public void meshing(BlockPos pos) {
        long key = SectionPos.asLong(pos);
        if (!meshed.begins(key, SectionSnow.blockIndex(pos))) return;
        // Read the generation before the state it describes, so a racing change looks stale rather than current.
        int look = appearance;
        SectionSnow snapshot = snapshots.get(key);
        Progress p = progress;
        if (snapshot != null && snapshot.hasSnow()) meshed.put(key, new MeshRevision(snapshot.revision(), p.revision(), look));
        else meshed.remove(key);
    }

    /** Coverage for chunk meshing. */
    public double meshCoverage(BlockPos pos, boolean plant) {
        return coverage(snapshots.get(SectionPos.asLong(pos)), progress, config, pos, plant);
    }

    private static double coverage(SectionSnow snapshot, Progress p, SnowConfig config, BlockPos pos, boolean plant) {
        if (snapshot == null || !config.enabled() || Math.max(p.mild, p.freezing) <= 0) return 0;
        int sample = snapshot.sample(pos);
        double weather = (sample & 128) != 0 ? p.freezing : p.mild;
        double variation = 1 - config.variation() * (1 - weather) * SnowMath.noise(pos.getX(), pos.getY(), pos.getZ());
        double maximum = plant ? config.plantMaximumCoverage() : config.maximumCoverage();
        return SnowMath.coverage(weather, (sample & 127) / 127.0) * variation * maximum;
    }

    /**
     * A renderer can drop a rebuild request: Sodium ignores one while the section's first mesh is still being built, so
     * snow captured in that window never reaches it. Request again until a mesh is built from the current snow, backing
     * off for sections the renderer is only deferring, such as those out of view. A mesh still showing snow from a
     * snapshot that was since evicted or discarded joins the bounded cleanup queue until it is rebuilt without snow.
     */
    private void retryLostRebuilds() {
        Progress p = progress;
        int look = appearance;
        stale = unmeshed = 0;
        for (long key : scheduler.retainedKeys()) {
            MeshRevision seen = meshed.get(key);
            if (current(seen, snapshots.get(key), p, look)) { retries.settled(key); continue; }
            // Never meshed since its snow arrived (out of view, or a request still pending) versus meshed from older state.
            if (seen == null) unmeshed++; else stale++;
            if (retries.due(key, ticks)) scheduler.rebuild(key);
        }
        for (long key : meshed.keys()) {
            if (snapshots.get(key) != null) continue;
            if (!world.getChunkSource().hasChunk(SectionPos.x(key), SectionPos.z(key))) { meshed.remove(key); retries.settled(key); continue; }
            stale++;
            if (rebuildQueue.canRequest(key, true) && retries.due(key, ticks)) {
                rebuildQueue.request(key, SectionRebuildQueue.FOLIAGE, true);
            }
        }
        retries.removeIf(key -> snapshots.get(key) == null && meshed.get(key) == null);
    }

    private static boolean current(MeshRevision seen, SectionSnow data, Progress p, int look) {
        // Without eligible foliage the mesh may only show snow from an older capture, which its next build removes.
        if (data == null || !data.hasSnow()) return seen == null;
        return seen != null && seen.matches(data.revision(), p.revision(), look);
    }

    public String diagnostics() {
        return String.format(Locale.ROOT,
                "coverage=%.3f/%.3f preview=%b sections=%d snapshots=%d pending=%d/%d cleanup=%d evictions=%d captures=%d rebuilds=%d stale=%d unmeshed=%d captureMs=%.3f tickUs=%.3f snapshotBytes<=%d",
                mild, freezing, preview, scheduler.size(), snapshots.size(), scheduler.pendingCaptures(), scheduler.pendingRebuilds()
                        + (rebuilding == null ? 0 : 1) + rebuildQueue.pending(SectionRebuildQueue.FOLIAGE), scheduler.pendingCleanup(), scheduler.evictions(), captures, rebuilds, stale, unmeshed,
                captures == 0 ? 0 : captureNanos / 1e6 / captures, ticks == 0 ? 0 : tickNanos / 1e3 / ticks, snapshots.size() * 4096L)
                + " sectionRebuilds=" + sectionRebuilds + " rebuildQueue=" + rebuildQueue.size() + " peakRebuilds=" + peakRebuilds
                + " " + ground.diagnostics();
    }
}
