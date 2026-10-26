package dev.frostedfoliage.client.world;

import dev.frostedfoliage.config.ConfigDiff;
import dev.frostedfoliage.config.SnowConfig;
import dev.frostedfoliage.core.BoundedSectionStore;
import dev.frostedfoliage.core.ChunkRing;
import dev.frostedfoliage.core.GroundSnowMath;
import dev.frostedfoliage.core.RetryBackoff;
import dev.frostedfoliage.core.SectionScheduler;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

/** Main-thread terrain capture; chunk workers read immutable column results only. */
public final class GroundSnowSystem {
    private static final int RADIUS = GroundSnowMath.RADIUS, MAX_CHUNKS = 4096, RETRY_TICKS = 20;
    private final Foliage foliage;
    private volatile SnowConfig config;
    private ClientLevel world;
    private final BoundedSectionStore<Cover> snapshots = new BoundedSectionStore<>();
    // Chunk coordinates use section keys with y=0 so the existing bounded scheduler can be shared.
    private final SectionScheduler scheduler = new SectionScheduler(2 * MAX_CHUNKS);
    private final ChunkRing ring = new ChunkRing();
    private final Map<Long, Set<Integer>> dirtySections = new HashMap<>();
    // Written by chunk meshing workers: the capture each section's ground blocks were last meshed from.
    private final Map<Long, MeshState> meshed = new ConcurrentHashMap<>();
    private final RetryBackoff retries = new RetryBackoff(RETRY_TICKS, 5);
    private volatile int look;
    // Block updates arrive on the client thread only, so one scratch position serves them all.
    private final BlockPos.MutableBlockPos scratch = new BlockPos.MutableBlockPos();
    private int focusX, focusZ;
    private boolean focused, rescan;
    private Long rebuilding;
    private long captures, captureNanos, rebuilds;
    private int ticks, stale;
    private record MeshState(Cover cover, int look) { }
    private record Cover(int[] heights, byte[] layers, byte[] kinds, boolean candidates, int[] snowSections) {
        Cover(int[] heights, byte[] layers, byte[] kinds, boolean candidates) {
            this(heights, layers, kinds, candidates, IntStream.range(0, 256).filter(i -> layers[i] != 0).map(i -> heights[i] >> 4).distinct().toArray());
        }
        boolean any() { return snowSections.length > 0; }
        boolean snowIn(int sectionY) { for (int y : snowSections) if (y == sectionY) return true; return false; }
        boolean same(Cover other) {
            return other != null && Arrays.equals(heights, other.heights) && Arrays.equals(layers, other.layers)
                    && Arrays.equals(kinds, other.kinds);
        }
    }

    GroundSnowSystem(SnowConfig config, Foliage foliage) { this.config = config; this.foliage = foliage; }
    private static long key(int x, int z) { return SectionPos.asLong(x, 0, z); }
    private int limit() { return Math.max(16, Math.min(MAX_CHUNKS, config.maxCachedSections() / 4)); }
    private boolean enabled() { return enabled(config); }
    private static boolean enabled(SnowConfig config) { return config.enabled() && (config.treeGroundSnow() || config.plantGroundSnow()); }

    public int layers(BlockPos ground) {
        if (!enabled()) return 0;
        Cover cover = snapshots.get(key(ground.getX() >> 4, ground.getZ() >> 4));
        if (cover == null) return 0;
        int i = (ground.getZ() & 15) * 16 + (ground.getX() & 15);
        return cover.heights[i] == ground.getY() ? layers(cover, config, i) : 0;
    }

    /** Layers for chunk meshing, which also records which capture the ground block's section is built from. */
    public int meshLayers(BlockPos ground) {
        // Read the generation before the config it describes, so a racing change looks stale rather than current.
        int look = this.look;
        Cover cover = snapshots.get(key(ground.getX() >> 4, ground.getZ() >> 4));
        SnowConfig c = config;
        if (cover == null) return 0;
        int i = (ground.getZ() & 15) * 16 + (ground.getX() & 15);
        if (cover.heights[i] != ground.getY()) return 0;
        long section = SectionPos.asLong(ground);
        MeshState seen = meshed.get(section);
        if (seen == null || seen.cover() != cover || seen.look() != look) meshed.put(section, new MeshState(cover, look));
        return enabled(c) ? layers(cover, c, i) : 0;
    }

    private static int layers(Cover cover, SnowConfig config, int i) {
        return (cover.kinds[i] == 1 ? config.plantGroundSnow() : config.treeGroundSnow()) ? cover.layers[i] : 0;
    }

    void reset(ClientLevel level) {
        world = level; scheduler.clear(); snapshots.clear(); dirtySections.clear(); meshed.clear(); retries.clear();
        ring.restart(); focused = rescan = false; captures = captureNanos = rebuilds = 0; stale = 0;
        rebuilding = null;
    }

    void configure(SnowConfig next) {
        var diff = new ConfigDiff(config, next);
        config = next;
        if (diff.groundSnow()) { look++; invalidateAll(); }
        if (diff.cacheSize()) { scheduler.resize(limit(), this::remove); ring.restart(); }
    }

    void invalidateAll() {
        scheduler.recaptureAll(); ring.restart();
        for (long key : scheduler.retainedKeys()) {
            Cover cover = snapshots.get(key);
            if (cover != null && cover.any()) { mark(key, cover); scheduler.rebuild(key); }
        }
    }

    void load(LevelChunk chunk) {
        if (world == null) return;
        updateFocus(Minecraft.getInstance());
        if (!scheduler.discover(key(chunk.getPos().x(), chunk.getPos().z()), limit(), true, this::remove)) rescan = true;
        invalidateAround(chunk.getPos().x(), chunk.getPos().z(), false);
    }

    void unload(LevelChunk chunk) {
        long key = key(chunk.getPos().x(), chunk.getPos().z());
        int before = scheduler.size();
        scheduler.unload(key); snapshots.remove(key); dirtySections.remove(key);
        if (scheduler.size() < before) rescan = true;
        invalidateAround(chunk.getPos().x(), chunk.getPos().z(), true);
    }

    void changed(LevelChunk chunk, BlockPos pos, BlockState oldState, BlockState newState, boolean heightChanged) {
        int x = pos.getX() & 15, z = pos.getZ() & 15, ground = groundHeight(chunk, x, z, scratch);
        boolean replaced = !oldState.equals(newState);
        boolean plantChanged = replaced && (foliage.isPlant(oldState) || foliage.isPlant(newState));
        // Equal heightmap predicates can still change the ground or canopy's eligibility.
        boolean surfaceChanged = replaced
                && (pos.getY() == ground || pos.getY() == chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z));
        if (heightChanged || plantChanged || surfaceChanged) { invalidateAround(chunk.getPos().x(), chunk.getPos().z(), false); return; }
        if ((oldState.is(Blocks.SNOW) || newState.is(Blocks.SNOW)) && ground + 1 == pos.getY()) {
            invalidateAround(chunk.getPos().x(), chunk.getPos().z(), true);
        }
        if (oldState.isAir() != newState.isAir() && ground + 1 == pos.getY()) {
            // Torches, carpet and leaves below a canopy can change eligibility without moving either heightmap.
            long key = key(chunk.getPos().x(), chunk.getPos().z());
            if (snapshots.get(key) != null) scheduler.discover(key, limit(), true, this::remove);
        }
    }

    private void invalidateAround(int x, int z, boolean candidatesOnly) {
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            long key = key(x + dx, z + dz);
            Cover old = snapshots.get(key);
            // Missing snapshots already have queued initial capture; don't admit distant chunks here.
            if (old != null && (!candidatesOnly || old.candidates)) scheduler.discover(key, limit(), true, this::remove);
        }
    }

    private void remove(long key) { Cover old = snapshots.get(key); if (old != null) mark(key, old); snapshots.remove(key); }
    private void mark(long key, Cover cover) {
        for (int i = 0; i < 256; i++) if (cover.layers[i] != 0) {
            dirtySections.computeIfAbsent(key, unused -> new HashSet<>()).add(cover.heights[i] >> 4);
        }
    }

    private void updateFocus(Minecraft client) {
        if (world == null || client.player == null) return;
        int x = client.player.blockPosition().getX() >> 4, z = client.player.blockPosition().getZ() >> 4;
        if (!focused || x != focusX || z != focusZ) {
            focusX = x; focusZ = z; focused = true; ring.restart(); rescan = false;
            scheduler.prioritize(k -> { long dx = SectionPos.x(k) - x, dz = SectionPos.z(k) - z; return dx*dx + dz*dz; });
        }
    }

    void tick(Minecraft client) {
        if (world == null || client.player == null) return;
        updateFocus(client);
        int x = focusX, z = focusZ;
        if (ring.resize(client.options.getEffectiveRenderDistance())) rescan = false;
        if (enabled()) {
            // Coalesce retries after the current pass: streaming events must never starve later offsets.
            if (rescan && ring.finished()) { ring.restart(); rescan = false; }
            ring.scan(4 * config.captureBudget(), () -> scheduler.size() >= limit() ? scheduler.farthestDistance() : Long.MAX_VALUE,
                    (dx, dz) -> world.getChunkSource().getChunkNow(x + dx, z + dz) == null
                            || scheduler.discover(key(x + dx, z + dz), limit(), false, this::remove));
            for (int i = 0; i < config.captureBudget(); i++) {
                Long key = scheduler.nextCapture(); if (key == null) break;
                LevelChunk chunk = world.getChunkSource().getChunkNow(SectionPos.x(key), SectionPos.z(key));
                if (chunk == null) { scheduler.unload(key); snapshots.remove(key); dirtySections.remove(key); rescan = true; continue; }
                long start = System.nanoTime(); Cover old = snapshots.get(key), next = capture(chunk);
                boolean changed = !next.same(old) && (next.any() || old != null && old.any());
                if (changed) { if (old != null) mark(key, old); mark(key, next); retrySettled(key); }
                snapshots.put(key, next, limit()); scheduler.captured(key, next.any(), changed);
                captures++; captureNanos += System.nanoTime() - start;
            }
        }
        if (++ticks % RETRY_TICKS == 0) retryLostRebuilds();
        for (int i = 0; i < config.rebuildsPerTick(); i++) {
            if (rebuilding == null) rebuilding = scheduler.nextRebuild();
            if (rebuilding == null) break;
            long key = rebuilding;
            Set<Integer> sections = dirtySections.get(key);
            if (sections != null && !sections.isEmpty() && world.getChunkSource().hasChunk(SectionPos.x(key), SectionPos.z(key))) {
                var iterator = sections.iterator(); int y = iterator.next(); iterator.remove();
                client.levelExtractor.setSectionDirty(SectionPos.x(key), y, SectionPos.z(key)); rebuilds++;
                if (!sections.isEmpty()) continue;
            }
            dirtySections.remove(key); rebuilding = null;
        }
    }

    /**
     * A renderer can drop a rebuild request (see SnowSystem), leaving a section with outdated ground snow. Request again
     * until its ground blocks are meshed from the current capture, backing off for sections the renderer is deferring.
     */
    private void retryLostRebuilds() {
        int look = this.look;
        stale = 0;
        // Sections that may show ground snow: those with snow in the current capture or in the one they were meshed from.
        Set<Long> sections = new HashSet<>();
        for (long key : scheduler.retainedKeys()) {
            Cover cover = snapshots.get(key);
            if (cover != null) for (int y : cover.snowSections()) sections.add(SectionPos.asLong(SectionPos.x(key), y, SectionPos.z(key)));
        }
        meshed.forEach((section, seen) -> { if (seen.cover().any()) sections.add(section); });
        for (long section : sections) {
            long key = key(SectionPos.x(section), SectionPos.z(section));
            Cover cover = snapshots.get(key);
            MeshState seen = meshed.get(section);
            int y = SectionPos.y(section);
            boolean current = cover == null || (seen == null ? !cover.snowIn(y)
                    : (seen.cover() == cover && seen.look() == look) || (!cover.snowIn(y) && !seen.cover().snowIn(y)));
            if (current) { retries.settled(section); continue; }
            stale++;
            if (retries.due(section, ticks)) { dirtySections.computeIfAbsent(key, unused -> new HashSet<>()).add(y); scheduler.rebuild(key); }
        }
        // Workers can record a section just after its chunk unloads.
        meshed.keySet().removeIf(section -> snapshots.get(key(SectionPos.x(section), SectionPos.z(section))) == null);
        retries.removeIf(section -> snapshots.get(key(SectionPos.x(section), SectionPos.z(section))) == null);
    }

    /** A new capture brings its own rebuild request, so its sections start their retries over. */
    private void retrySettled(long key) {
        retries.removeIf(section -> SectionPos.x(section) == SectionPos.x(key) && SectionPos.z(section) == SectionPos.z(key));
    }

    private int groundHeight(LevelChunk chunk, int x, int z, BlockPos.MutableBlockPos pos) {
        int y = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        pos.set(chunk.getPos().getMinBlockX() + x, y, chunk.getPos().getMinBlockZ() + z);
        // Dense shrubs can participate in the heightmap; their supporting surface is still ground.
        for (int i = 0; i < 3; i++) {
            BlockState state = chunk.getBlockState(pos);
            if (!foliage.isPlant(state) && !state.is(Blocks.SNOW)) break;
            pos.setY(--y);
        }
        return y;
    }

    private Cover capture(LevelChunk chunk) {
        int[] heights = new int[256]; byte[] kinds = new byte[256], layers = new byte[256];
        boolean candidates = false;
        int baseX = chunk.getPos().getMinBlockX(), baseZ = chunk.getPos().getMinBlockZ();
        var pos = new BlockPos.MutableBlockPos();
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            int i = z * 16 + x, y = groundHeight(chunk, x, z, pos); heights[i] = y;
            pos.set(baseX + x, y, baseZ + z); BlockState support = chunk.getBlockState(pos);
            if (support.isAir() || support.is(Blocks.SNOW_BLOCK) || support.is(BlockTags.LOGS) || support.is(BlockTags.LEAVES)
                    || support.is(BlockTags.CANNOT_SUPPORT_SNOW_LAYER)
                    || !support.getFluidState().isEmpty() || !support.isFaceSturdy(world, pos, Direction.UP)) continue;
            BlockState above = chunk.getBlockState(pos.above());
            if (!above.getFluidState().isEmpty()) continue;
            if (foliage.groundPlant(above) && PlantTypes.part(above) != PlantTypes.UPPER) kinds[i] = 1;
            else if (above.isAir()) {
                int canopy = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
                if (canopy <= y) continue;
                BlockState top = chunk.getBlockState(pos.setY(canopy));
                if (foliage.test(top) && !foliage.isPlant(top)) kinds[i] = 2;
            }
            candidates |= kinds[i] != 0;
        }
        if (!candidates) return new Cover(heights, layers, kinds, false);

        int width = 16 + 2 * RADIUS;
        int[] nearbyHeights = new int[width * width]; byte[] snow = new byte[width * width];
        for (int z = 0; z < width; z++) for (int x = 0; x < width; x++) {
            int wx = baseX + x - RADIUS, wz = baseZ + z - RADIUS, i = z * width + x;
            LevelChunk neighbor = world.getChunkSource().getChunkNow(wx >> 4, wz >> 4); if (neighbor == null) continue;
            int y = groundHeight(neighbor, wx & 15, wz & 15, pos); nearbyHeights[i] = y;
            BlockState state = neighbor.getBlockState(pos.set(wx, y + 1, wz));
            if (state.is(Blocks.SNOW)) snow[i] = state.getValue(BlockStateProperties.LAYERS).byteValue();
        }
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            int i = z * 16 + x; if (kinds[i] == 0) continue;
            layers[i] = (byte) GroundSnowMath.nearestLayers(nearbyHeights, snow, width, x + RADIUS, z + RADIUS, heights[i]);
        }
        return new Cover(heights, layers, kinds, true);
    }

    public boolean settled() { return (!enabled() || scheduler.pendingCaptures() == 0) && scheduler.pendingRebuilds() == 0 && rebuilding == null; }
    public String diagnostics() {
        return "groundChunks=" + snapshots.size() + " groundPending=" + scheduler.pendingCaptures() + "/" + scheduler.pendingRebuilds()
                + " groundInFlight=" + (rebuilding == null ? 0 : 1)
                + " groundCaptures=" + captures + " groundRebuilds=" + rebuilds + " groundStale=" + stale + " groundCaptureMs=" + captureNanos / 1e6;
    }
}
