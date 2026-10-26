package dev.frostedfoliage.core;

import java.util.HashMap;
import java.util.Map;
import java.util.function.LongPredicate;

/**
 * Client-thread-only timing for repeating rebuild requests that a renderer may have dropped. A key first seen stale
 * waits one period for its original request to land; each retry after that doubles the wait, up to a cap, so keys the
 * renderer is only deferring, such as sections out of view, cost little.
 */
public final class RetryBackoff {
    private final int period, maxDoublings;
    private final Map<Long, Retry> retries = new HashMap<>();
    private record Retry(long due, int attempts) { }

    public RetryBackoff(int period, int maxDoublings) { this.period = period; this.maxDoublings = maxDoublings; }

    /** Call for a stale key on each check; true means request its rebuild again now. */
    public boolean due(long key, long tick) {
        Retry retry = retries.get(key);
        if (retry == null) { retries.put(key, new Retry(tick + period, 0)); return false; }
        if (tick < retry.due()) return false;
        int attempts = retry.attempts() + 1;
        retries.put(key, new Retry(tick + ((long) period << Math.min(attempts, maxDoublings)), attempts));
        return true;
    }

    /** The key's mesh is current, or its snow changed and a fresh request is on its way. */
    public void settled(long key) { retries.remove(key); }
    public void removeIf(LongPredicate predicate) { retries.keySet().removeIf(predicate::test); }
    public void clear() { retries.clear(); }
}
