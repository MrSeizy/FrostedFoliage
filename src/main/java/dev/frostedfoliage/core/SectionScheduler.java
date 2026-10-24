package dev.frostedfoliage.core;

import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.LongConsumer;
import java.util.function.ToLongFunction;

/** Client-thread-only bounded admission and work scheduling. Keys need not be Minecraft positions. */
public final class SectionScheduler {
    private final RankedSet retained = new RankedSet();
    private final RankedSet captures = new RankedSet();
    private final RankedSet rebuilds = new RankedSet();
    private final RankedSet retired = new RankedSet();
    // Conservative: a retained section may still have a snowy mesh until rebuilt by the renderer.
    private final Set<Long> snowy = new HashSet<>();
    private final int maxTracked;
    private Comparator<Long> order = Long::compare;
    private ToLongFunction<Long> distance = key -> key;
    private int captureTurn, rebuildTurn, cleanupTurn, retiredTurn;
    private long evictions;

    public SectionScheduler(int maxTracked) {
        if (maxTracked < 1) throw new IllegalArgumentException("maxTracked must be positive");
        this.maxTracked = maxTracked;
    }

    /** Pass an immutable distance function. Reordering only happens when the player's section changes. */
    public void prioritize(ToLongFunction<Long> distance) {
        this.distance = distance;
        order = Comparator.comparingLong(distance).thenComparingLong(Long::longValue);
        retained.reorder(order); captures.reorder(order); rebuilds.reorder(order); retired.reorder(order);
    }

    /** False means admission is deferred until old mesh cleanup frees metadata capacity. */
    public boolean discover(long key, int limit, boolean invalidate, LongConsumer removeSnapshot) {
        if (retained.contains(key)) {
            if (invalidate) captures.add(key);
            return true;
        }
        Long victim = retained.size() >= limit ? retained.last() : null;
        if (victim != null && order.compare(key, victim) >= 0) return true;
        int growth = retired.contains(key) ? 0 : 1;
        if (victim != null && !snowy.contains(victim)) growth--;
        if (retained.size() + retired.size() + growth > maxTracked) return false;
        if (victim != null) evict(victim, removeSnapshot);
        retained.add(key);
        if (retired.remove(key)) { snowy.add(key); rebuilds.add(key); }
        captures.add(key);
        return true;
    }

    public void resize(int limit, LongConsumer removeSnapshot) {
        while (retained.size() > limit) evict(retained.last(), removeSnapshot);
    }

    public void discard(long key, LongConsumer removeSnapshot) {
        if (retained.contains(key)) evict(key, removeSnapshot);
    }

    private void evict(long key, LongConsumer removeSnapshot) {
        retained.remove(key); captures.remove(key); rebuilds.remove(key);
        removeSnapshot.accept(key);
        if (snowy.remove(key)) retired.add(key);
        evictions++;
    }

    public void invalidateAll() {
        recaptureAll();
        rebuildAll();
    }

    public void recaptureAll() { for (long key : retained.insertionOrder) captures.add(key); }
    public void rebuildAll() { for (long key : retained.insertionOrder) if (snowy.contains(key)) rebuilds.add(key); }

    public void captured(long key, boolean hasSnow, boolean changed) {
        if (!retained.contains(key)) return;
        if (hasSnow) snowy.add(key);
        if (changed) rebuilds.add(key);
    }

    public void rebuild(long key) { if (retained.contains(key)) rebuilds.add(key); }
    public Iterable<Long> retainedKeys() { return retained.insertionOrder; }
    public Long nextCapture() { return captures.poll(++captureTurn % 4 == 0); }

    /** Three nearby updates per oldest update; old mesh cleanup receives at least one in four slots. */
    public Long nextRebuild() {
        if (!retired.isEmpty() && (rebuilds.isEmpty() || ++cleanupTurn % 4 == 0)) {
            return retired.poll(++retiredTurn % 4 == 0);
        }
        return rebuilds.poll(++rebuildTurn % 4 == 0);
    }

    public void unload(long key) {
        retained.remove(key); captures.remove(key); rebuilds.remove(key); retired.remove(key); snowy.remove(key);
    }

    public void clear() {
        retained.clear(); captures.clear(); rebuilds.clear(); retired.clear(); snowy.clear();
        captureTurn = rebuildTurn = cleanupTurn = retiredTurn = 0; evictions = 0;
    }

    public int size() { return retained.size(); }
    public int pendingCaptures() { return captures.size(); }
    public int pendingRebuilds() { return rebuilds.size() + retired.size(); }
    public int pendingCleanup() { return retired.size(); }
    public long evictions() { return evictions; }
    public long farthestDistance() { return retained.isEmpty() ? Long.MAX_VALUE : distance.applyAsLong(retained.last()); }

    private static final class RankedSet {
        private final LinkedHashSet<Long> insertionOrder = new LinkedHashSet<>();
        private TreeSet<Long> ranked = new TreeSet<>();
        void reorder(Comparator<Long> order) { ranked = new TreeSet<>(order); ranked.addAll(insertionOrder); }
        void add(long key) { if (insertionOrder.add(key)) ranked.add(key); }
        boolean remove(long key) { if (!insertionOrder.remove(key)) return false; ranked.remove(key); return true; }
        boolean contains(long key) { return insertionOrder.contains(key); }
        int size() { return insertionOrder.size(); }
        boolean isEmpty() { return insertionOrder.isEmpty(); }
        long last() { return ranked.last(); }
        Long poll(boolean oldest) {
            if (isEmpty()) return null;
            long key = oldest ? insertionOrder.iterator().next() : ranked.first();
            remove(key);
            return key;
        }
        void clear() { insertionOrder.clear(); ranked.clear(); }
    }
}
