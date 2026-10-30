package dev.frostedfoliage.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongPredicate;

/** Client-thread dispatch shared by foliage and ground snow. Refused requests stay with their producer. */
public final class SectionRebuildQueue {
    public static final int FOLIAGE = 1, GROUND = 2;
    @FunctionalInterface public interface Sink { void rebuild(long section, int sources); }

    private final int capacity;
    private final LinkedHashMap<Long, Integer> updates = new LinkedHashMap<>();
    private final LinkedHashMap<Long, Integer> cleanup = new LinkedHashMap<>();
    private int cleanupTurn;

    public SectionRebuildQueue(int capacity) {
        if (capacity < 2) throw new IllegalArgumentException("capacity must be at least two");
        this.capacity = capacity;
    }

    /** Reserve half the slots for normal updates; check before advancing a cleanup retry's backoff. */
    public boolean canRequest(long section, boolean retiring) {
        return updates.containsKey(section) || cleanup.containsKey(section)
                || size() < capacity && (!retiring || cleanup.size() < capacity / 2);
    }

    /** Coalesce both sources, promoting a cleanup request if a normal update for the section arrives. */
    public boolean request(long section, int source, boolean retiring) {
        Integer existing = updates.get(section);
        if (existing != null) { updates.put(section, existing | source); return true; }
        existing = cleanup.get(section);
        if (existing != null) {
            if (retiring) cleanup.put(section, existing | source);
            else { cleanup.remove(section); updates.put(section, existing | source); }
            return true;
        }
        if (!canRequest(section, retiring)) return false;
        (retiring ? cleanup : updates).put(section, source);
        return true;
    }

    /** Three normal updates per cleanup while both wait. Unloaded sections consume no renderer budget. */
    public int drain(int budget, LongPredicate loaded, Sink sink) {
        int sent = 0, examined = 0;
        while (sent < budget && size() > 0 && examined++ < capacity) {
            var queue = !cleanup.isEmpty() && (updates.isEmpty() || ++cleanupTurn % 4 == 0) ? cleanup : updates;
            var entry = queue.pollFirstEntry();
            if (!loaded.test(entry.getKey())) continue;
            sink.rebuild(entry.getKey(), entry.getValue());
            sent++;
        }
        return sent;
    }

    public int size() { return updates.size() + cleanup.size(); }
    public int pending(int source) { return pending(updates, source) + pending(cleanup, source); }
    private static int pending(Map<Long, Integer> queue, int source) {
        int count = 0;
        for (int sources : queue.values()) if ((sources & source) != 0) count++;
        return count;
    }
    public void remove(long section) { updates.remove(section); cleanup.remove(section); }
    public void clear() { updates.clear(); cleanup.clear(); cleanupTurn = 0; }
}
