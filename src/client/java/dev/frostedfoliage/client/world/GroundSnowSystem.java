package dev.frostedfoliage.client.world;

import dev.frostedfoliage.config.ConfigDiff;
import dev.frostedfoliage.config.SnowConfig;
import dev.frostedfoliage.core.BoundedSectionStore;
import dev.frostedfoliage.core.SectionScheduler;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    public static final int RADIUS = 8;
    private static final int MAX_CHUNKS = 4096;
    private final Foliage foliage;
    private volatile SnowConfig config;
    private ClientLevel world;
    private final BoundedSectionStore<Cover> snapshots = new BoundedSectionStore<>();
    // Chunk coordinates use section keys with y=0 so the existing bounded scheduler can be shared.
    private final SectionScheduler scheduler = new SectionScheduler(2 * MAX_CHUNKS);
    private final Map<Long, Set<Integer>> dirtySections = new HashMap<>();
    private List<Offset> scan = List.of();
    private int cursor, radius = -1, focusX, focusZ;
    private boolean focused, rescan;
    private Long rebuilding;
    private long captures, captureNanos, rebuilds;
    private record Offset(int x, int z) { long distance() { return (long)x*x + (long)z*z; } }
    private static final List<Offset> SEARCH = searchOffsets();
    private static List<Offset> searchOffsets() {
        var offsets = new ArrayList<Offset>();
        for (int z = -RADIUS; z <= RADIUS; z++) for (int x = -RADIUS; x <= RADIUS; x++)
            if (x*x + z*z > 0 && x*x + z*z <= RADIUS*RADIUS) offsets.add(new Offset(x, z));
        offsets.sort(Comparator.comparingLong(Offset::distance));
        return List.copyOf(offsets);
    }
    private record Cover(int[] heights, byte[] layers, byte[] kinds, boolean candidates) {
        boolean any() { for (byte n : layers) if (n != 0) return true; return false; }
        boolean same(Cover other) {
            return other != null && Arrays.equals(heights, other.heights) && Arrays.equals(layers, other.layers)
                    && Arrays.equals(kinds, other.kinds);
        }
    }

    GroundSnowSystem(SnowConfig config, Foliage foliage) { this.config = config; this.foliage = foliage; }
    private static long key(int x, int z) { return SectionPos.asLong(x, 0, z); }
    private int limit() { return Math.max(16, Math.min(MAX_CHUNKS, config.maxCachedSections() / 4)); }
    private boolean enabled() { return config.enabled() && (config.treeGroundSnow() || config.plantGroundSnow()); }

    public int layers(BlockPos ground) {
        if (!enabled()) return 0;
        Cover cover = snapshots.get(key(ground.getX() >> 4, ground.getZ() >> 4));
        if (cover == null) return 0;
        int i = (ground.getZ() & 15) * 16 + (ground.getX() & 15);
        if (cover.heights[i] != ground.getY()) return 0;
        return (cover.kinds[i] == 1 ? config.plantGroundSnow() : config.treeGroundSnow()) ? cover.layers[i] : 0;
    }

    void reset(ClientLevel level) {
        world = level; scheduler.clear(); snapshots.clear(); dirtySections.clear();
        scan = List.of(); cursor = 0; radius = -1; focused = rescan = false; captures = captureNanos = rebuilds = 0;
        rebuilding = null;
    }

    void configure(SnowConfig next) {
        var diff = new ConfigDiff(config, next);
        config = next;
        if (diff.groundSnow()) invalidateAll();
        if (diff.cacheSize()) { scheduler.resize(limit(), this::remove); cursor = 0; }
    }

    void invalidateAll() {
        scheduler.recaptureAll(); cursor = 0;
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
        boolean plantChanged = !oldState.equals(newState)
                && (PlantTypes.supported(oldState.getBlock()) || PlantTypes.supported(newState.getBlock()));
        // Equal heightmap predicates can still change the ground or canopy's eligibility.
        boolean surfaceChanged = !oldState.equals(newState)
                && (pos.getY() == groundHeight(chunk, pos.getX() & 15, pos.getZ() & 15)
                || pos.getY() == chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, pos.getX() & 15, pos.getZ() & 15));
        if (heightChanged || plantChanged || surfaceChanged) { invalidateAround(chunk.getPos().x(), chunk.getPos().z(), false); return; }
        if ((oldState.is(Blocks.SNOW) || newState.is(Blocks.SNOW))
                && groundHeight(chunk, pos.getX() & 15, pos.getZ() & 15) + 1 == pos.getY()) {
            invalidateAround(chunk.getPos().x(), chunk.getPos().z(), true);
        }
        if (oldState.isAir() != newState.isAir()
                && groundHeight(chunk, pos.getX() & 15, pos.getZ() & 15) + 1 == pos.getY()) {
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
            focusX = x; focusZ = z; focused = true; cursor = 0; rescan = false;
            scheduler.prioritize(k -> { long dx = SectionPos.x(k) - x, dz = SectionPos.z(k) - z; return dx*dx + dz*dz; });
        }
    }

    void tick(Minecraft client) {
        if (world == null || client.player == null) return;
        updateFocus(client);
        int x = focusX, z = focusZ;
        int distance = client.options.getEffectiveRenderDistance();
        if (radius != distance) {
            radius = distance; cursor = 0; rescan = false; var offsets = new ArrayList<Offset>();
            for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) offsets.add(new Offset(dx, dz));
            offsets.sort(Comparator.comparingLong(Offset::distance).thenComparingInt(Offset::x).thenComparingInt(Offset::z)); scan = List.copyOf(offsets);
        }
        if (enabled()) {
            // Coalesce retries after the current pass: streaming events must never starve later offsets.
            if (rescan && cursor >= scan.size()) { cursor = 0; rescan = false; }
            for (int i = 0; i < 4 * config.captureBudget() && cursor < scan.size(); i++) {
                Offset offset = scan.get(cursor);
                if (scheduler.size() >= limit() && offset.distance() > scheduler.farthestDistance()) { cursor = scan.size(); break; }
                LevelChunk chunk = world.getChunkSource().getChunkNow(x + offset.x, z + offset.z);
                if (chunk != null && !scheduler.discover(key(x + offset.x, z + offset.z), limit(), false, this::remove)) break;
                cursor++;
            }
            for (int i = 0; i < config.captureBudget(); i++) {
                Long key = scheduler.nextCapture(); if (key == null) break;
                LevelChunk chunk = world.getChunkSource().getChunkNow(SectionPos.x(key), SectionPos.z(key));
                if (chunk == null) { scheduler.unload(key); snapshots.remove(key); dirtySections.remove(key); rescan = true; continue; }
                long start = System.nanoTime(); Cover old = snapshots.get(key), next = capture(chunk);
                boolean changed = !next.same(old) && (next.any() || old != null && old.any());
                if (changed) { if (old != null) mark(key, old); mark(key, next); }
                snapshots.put(key, next, limit()); scheduler.captured(key, next.any(), changed);
                captures++; captureNanos += System.nanoTime() - start;
            }
        }
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

    private int groundHeight(LevelChunk chunk, int x, int z) {
        return groundHeight(chunk, x, z, new BlockPos.MutableBlockPos());
    }

    private int groundHeight(LevelChunk chunk, int x, int z, BlockPos.MutableBlockPos pos) {
        int y = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        pos.set(chunk.getPos().getMinBlockX() + x, y, chunk.getPos().getMinBlockZ() + z);
        // Dense shrubs can participate in the heightmap; their supporting surface is still ground.
        for (int i = 0; i < 3; i++) {
            BlockState state = chunk.getBlockState(pos);
            if (!PlantTypes.supported(state.getBlock()) && !state.is(Blocks.SNOW)) break;
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
            int nearest = RADIUS * RADIUS + 1, amount = 0;
            for (Offset offset : SEARCH) {
                int d = (int) offset.distance(); if (d > nearest) break;
                int n = (z + RADIUS + offset.z) * width + x + RADIUS + offset.x;
                if (snow[n] == 0 || Math.abs(nearbyHeights[n] - heights[i]) > 1) continue;
                amount = d < nearest ? snow[n] : Math.min(amount, snow[n]); nearest = d;
            }
            layers[i] = (byte) amount;
        }
        return new Cover(heights, layers, kinds, true);
    }

    public boolean settled() { return (!enabled() || scheduler.pendingCaptures() == 0) && scheduler.pendingRebuilds() == 0 && rebuilding == null; }
    public String diagnostics() {
        return "groundChunks=" + snapshots.size() + " groundPending=" + scheduler.pendingCaptures() + "/" + scheduler.pendingRebuilds()
                + " groundInFlight=" + (rebuilding == null ? 0 : 1)
                + " groundCaptures=" + captures + " groundRebuilds=" + rebuilds + " groundCaptureMs=" + captureNanos / 1e6;
    }
}
