package dev.frostedfoliage.test;

import dev.frostedfoliage.client.FrostedFoliageClient;
import dev.frostedfoliage.client.world.SectionSnow;
import dev.frostedfoliage.core.MeshAcks;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/** Test-only measurements that also run against the pre-queue implementation for before/after comparisons. */
public final class SnowWorkMetrics {
    private static boolean recording;
    private static long before, requests;
    private static int ticks, peak;
    private static Field sharedRequests, leafRequests, groundRequests;

    public static void start() {
        var system = FrostedFoliageClient.system();
        try { sharedRequests = accessible(system, "sectionRebuilds"); }
        catch (NoSuchFieldException absentOnBaseline) {
            try { leafRequests = accessible(system, "rebuilds"); groundRequests = accessible(system.ground(), "rebuilds"); }
            catch (NoSuchFieldException e) { throw new AssertionError(e); }
        }
        recording = true; requests = 0; ticks = peak = 0;
    }
    public static void beginTick() { if (recording) before = requests(); }
    public static void endTick() {
        if (!recording) return;
        int count = Math.toIntExact(requests() - before);
        requests += count; peak = Math.max(peak, count); ticks++;
    }
    public static Map<String, Long> stop() {
        recording = false;
        return Map.of("ticks", (long) ticks, "requests", requests, "peakRequestsPerTick", (long) peak);
    }

    private static long requests() {
        var system = FrostedFoliageClient.system();
        try { return sharedRequests != null ? sharedRequests.getLong(system) : leafRequests.getLong(system) + groundRequests.getLong(system.ground()); }
        catch (IllegalAccessException e) { throw new AssertionError(e); }
    }

    /** Counts actual primitive-array payload reachable through caches and acknowledgments, without a GC/heap estimate. */
    public static Map<String, Long> snapshotMemory() {
        Set<Object> cached = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<Object> acknowledged = Collections.newSetFromMap(new IdentityHashMap<>());
        var system = FrostedFoliageClient.system();
        for (Object owner : new Object[]{system, system.ground()}) {
            cached.addAll(((Map<?, ?>) field(field(owner, "snapshots"), "values")).values());
            var acks = (MeshAcks<?>) field(owner, "meshed");
            for (long key : acks.keys()) {
                Object ack = acks.get(key);
                if (ack == null) continue;
                for (var component : ack.getClass().getDeclaredFields()) {
                    if (component.getType().isPrimitive()) continue;
                    Object value = field(ack, component.getName());
                    if (value instanceof SectionSnow || value != null && value.getClass().getSimpleName().equals("Cover")) acknowledged.add(value);
                }
            }
        }
        acknowledged.removeAll(cached);
        return Map.of("cachedPayloadBytes", payload(cached), "acknowledgmentOnlyPayloadBytes", payload(acknowledged),
                "acknowledgmentOnlySnapshots", (long) acknowledged.size());
    }

    private static long payload(Set<Object> snapshots) {
        long bytes = 0;
        for (Object snapshot : snapshots) for (var component : snapshot.getClass().getDeclaredFields()) {
            Class<?> type = component.getType();
            if (type == byte[].class || type == int[].class) {
                bytes += (long) Array.getLength(field(snapshot, component.getName())) * (type == byte[].class ? 1 : 4);
            }
        }
        return bytes;
    }

    private static Object field(Object object, String name) {
        try {
            return accessible(object, name).get(object);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private static Field accessible(Object object, String name) throws NoSuchFieldException {
        var field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field;
    }
}
