package dev.frostedfoliage.core;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongPredicate;

/** Single writer, many readers. Values must be immutable after publication. */
public final class BoundedSectionStore<T> {
    private final Map<Long, T> values = new ConcurrentHashMap<>();
    public T get(long key) { return values.get(key); }
    public boolean put(long key, T value, int limit) {
        if (!values.containsKey(key) && values.size() >= limit) return false;
        values.put(key, value);
        return true;
    }
    public void remove(long key) { values.remove(key); }
    public void removeIf(LongPredicate predicate) { values.keySet().removeIf(k -> predicate.test(k)); }
    public void clear() { values.clear(); }
    public int size() { return values.size(); }
}
