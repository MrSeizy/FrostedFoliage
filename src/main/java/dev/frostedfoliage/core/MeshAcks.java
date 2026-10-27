package dev.frostedfoliage.core;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongPredicate;

/**
 * What each section's mesh was last built from, as observed by chunk meshing workers. A worker meshes one section per
 * job and visits each block at most once, so a block it has already seen in the current section, or a change of
 * section, marks the start of a new job. Only that first block acknowledges: a job that straddles a change of state then
 * records the older state, so the client thread sees it as stale and requests the section again.
 */
public final class MeshAcks<S> {
    private final Map<Long, S> acks = new ConcurrentHashMap<>();
    private volatile int generation;
    private final ThreadLocal<Run> runs = ThreadLocal.withInitial(Run::new);

    private static final class Run {
        int generation = -1;
        long key;
        final long[] seen = new long[64];
    }

    /** True when block {@code index} (0..4095) of section {@code key} begins a new meshing run on the calling thread. */
    public boolean begins(long key, int index) {
        Run run = runs.get();
        int word = index >>> 6; long bit = 1L << index;
        boolean begins = run.generation != generation || run.key != key || (run.seen[word] & bit) != 0;
        if (begins) { run.generation = generation; run.key = key; Arrays.fill(run.seen, 0); }
        run.seen[word] |= bit;
        return begins;
    }

    public S get(long key) { return acks.get(key); }
    public void put(long key, S state) { acks.put(key, state); }
    public void remove(long key) { acks.remove(key); }
    public void removeIf(LongPredicate predicate) { acks.keySet().removeIf(k -> predicate.test(k)); }
    public Set<Long> keys() { return acks.keySet(); }
    public int size() { return acks.size(); }

    /** Forgets every acknowledgment and ends every worker's current run, for a new world. */
    public void clear() { acks.clear(); generation++; }
}
