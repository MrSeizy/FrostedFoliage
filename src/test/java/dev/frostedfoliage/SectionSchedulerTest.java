package dev.frostedfoliage;

import dev.frostedfoliage.core.SectionScheduler;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SectionSchedulerTest {
    private SectionScheduler scheduler(int bound) {
        var s = new SectionScheduler(bound);
        s.prioritize(key -> Math.abs(key));
        return s;
    }
    private void add(SectionScheduler s, long key, int limit) { assertTrue(s.discover(key, limit, false, k -> {})); }
    private Set<Long> keys(SectionScheduler s) {
        var keys = new HashSet<Long>(); s.retainedKeys().forEach(keys::add); return keys;
    }

    @Test void closerSectionReplacesDistantSnapshotAtCapacity() {
        var s = scheduler(8); var removed = new HashSet<Long>();
        add(s, 20, 2); add(s, 10, 2);
        assertTrue(s.discover(1, 2, false, removed::add));
        assertEquals(Set.of(1L, 10L), keys(s)); assertEquals(Set.of(20L), removed);
        assertEquals(1L, s.nextCapture()); assertEquals(10L, s.nextCapture()); assertNull(s.nextCapture());
    }
    @Test void movementAllowsPreviouslyRejectedLoadedSectionWithoutRecapturingUnchangedOnes() {
        var s = scheduler(8); add(s, 0, 1); add(s, 100, 1);
        assertEquals(0L, s.nextCapture()); s.captured(0, true, true);
        add(s, 0, 1); assertNull(s.nextCapture());
        s.prioritize(key -> Math.abs(100 - key)); add(s, 100, 1);
        assertEquals(Set.of(100L), keys(s)); assertEquals(100L, s.nextCapture()); assertEquals(1, s.pendingCleanup());
    }
    @Test void equalDistancesUseStableKeysInsteadOfArrivalOrder() {
        var s = scheduler(8); s.prioritize(key -> 1);
        add(s, 20, 2); add(s, 30, 2); add(s, 10, 2); add(s, 30, 2);
        assertEquals(Set.of(10L, 20L), keys(s)); assertEquals(1, s.evictions());
    }
    @Test void captureAndRebuildFavorNearbyButServeOldWorkEveryFourthSlot() {
        var s = scheduler(32);
        for (long key : new long[]{100, 1, 2, 3, 4, 5}) { add(s, key, 16); s.captured(key, true, true); }
        for (long expected : new long[]{1, 2, 3, 100, 4, 5}) {
            assertEquals(expected, s.nextCapture()); assertEquals(expected, s.nextRebuild());
        }
    }
    @Test void reprioritizingReordersPendingWorkWithoutCorruptingMembership() {
        var s = scheduler(16);
        for (long key : new long[]{0, 10, 20}) { add(s, key, 8); s.captured(key, true, true); }
        s.prioritize(key -> Math.abs(19 - key));
        assertEquals(20L, s.nextCapture()); assertEquals(20L, s.nextRebuild());
        s.unload(10); assertEquals(0L, s.nextCapture()); assertEquals(0L, s.nextRebuild());
        assertNull(s.nextCapture()); assertNull(s.nextRebuild());
    }
    @Test void evictedSnowGetsCleanupEvenUnderContinuousNearbyWork() {
        var s = scheduler(8); add(s, 10, 1); s.captured(10, true, true); add(s, 1, 1);
        for (int i = 0; i < 3; i++) { s.rebuild(1); assertEquals(1L, s.nextRebuild()); }
        s.rebuild(1); assertEquals(10L, s.nextRebuild()); assertEquals(0, s.pendingCleanup());
        assertEquals(1L, s.nextRebuild());
    }
    @Test void readmissionThenEvictionBeforeCapturePreservesOldMeshCleanup() {
        var s = scheduler(8); add(s, 10, 1); s.captured(10, true, true); add(s, 1, 1);
        s.prioritize(key -> Math.abs(10 - key)); add(s, 10, 1); assertEquals(0, s.pendingCleanup());
        s.prioritize(key -> Math.abs(key)); add(s, 1, 1);
        assertEquals(1, s.pendingCleanup()); assertEquals(10L, s.nextRebuild());
    }
    @Test void cleanupSlotsDoNotConsumeTheFairnessSlotsOfRegularWork() {
        var s = scheduler(32);
        for (long key : new long[]{100, 101, 102, 103}) { add(s, key, 8); s.captured(key, true, true); }
        for (long key : new long[]{101, 102, 103}) s.discard(key, k -> {});
        for (long key : new long[]{1, 2, 3, 4, 5}) { add(s, key, 8); s.captured(key, true, true); }
        assertEquals(1L, s.nextRebuild()); assertEquals(2L, s.nextRebuild()); assertEquals(3L, s.nextRebuild());
        assertEquals(101L, s.nextRebuild()); assertEquals(100L, s.nextRebuild());
    }
    @Test void shrinkingRetainsNearestAndClearsOnlyPotentialSnowMeshes() {
        var s = scheduler(16); var removed = new HashSet<Long>();
        for (long key : new long[]{1, 2, 3, 4}) add(s, key, 8);
        s.captured(3, true, true); s.captured(4, false, true);
        s.resize(2, removed::add);
        assertEquals(Set.of(1L, 2L), keys(s)); assertEquals(Set.of(3L, 4L), removed);
        assertEquals(1, s.pendingCleanup()); assertEquals(3L, s.nextRebuild());
    }
    @Test void cleanupBacklogAppliesBoundedBackpressureAndAdmissionResumes() {
        var s = scheduler(3);
        for (long key : new long[]{10, 9, 8}) { add(s, key, 1); s.captured(key, true, true); }
        assertEquals(2, s.pendingCleanup()); assertFalse(s.discover(7, 1, false, k -> fail("Evicted while blocked")));
        assertEquals(Set.of(8L), keys(s));
        assertEquals(8L, s.nextRebuild()); assertNotNull(s.nextRebuild());
        add(s, 7, 1); assertEquals(3, s.size() + s.pendingCleanup());
    }
    @Test void unloadAndWorldResetRemoveEveryKindOfPendingWork() {
        var s = scheduler(8); add(s, 10, 1); s.captured(10, true, true); add(s, 1, 1);
        s.unload(10); assertEquals(0, s.pendingCleanup());
        s.unload(1); assertEquals(0, s.size()); assertNull(s.nextCapture()); assertNull(s.nextRebuild());
        add(s, 5, 1); s.captured(5, true, true); s.clear();
        assertEquals(0, s.size()); assertEquals(0, s.evictions()); assertNull(s.nextCapture()); assertNull(s.nextRebuild());
    }
    @Test void invalidationIsCoalescedAndDoesNotLosePotentialMeshOnEmptyRecapture() {
        var s = scheduler(8); add(s, 10, 1); s.captured(10, true, true);
        s.invalidateAll(); s.invalidateAll(); s.captured(10, false, true);
        assertEquals(1, s.pendingCaptures()); assertEquals(1, s.pendingRebuilds());
        s.discard(10, k -> {}); assertEquals(1, s.pendingCleanup()); assertEquals(10L, s.nextRebuild());
    }
    @Test void identicalRecaptureDoesNotRebuildOrLoseQueuedAppearanceAndEvictionWork() {
        var s = scheduler(8); add(s, 10, 1); s.captured(10, true, true);
        assertEquals(10L, s.nextCapture()); assertEquals(10L, s.nextRebuild());
        s.captured(10, true, false); assertNull(s.nextRebuild());
        s.rebuildAll(); s.captured(10, true, false);
        assertEquals(10L, s.nextRebuild());
        s.discard(10, k -> {}); assertEquals(10L, s.nextRebuild());
    }
    @Test void captureOnlyInvalidationLeavesExistingMeshUntilEnvironmentChanges() {
        var s = scheduler(8); add(s, 10, 1); s.captured(10, true, true);
        s.nextCapture(); s.nextRebuild();
        s.recaptureAll(); assertEquals(10L, s.nextCapture()); assertNull(s.nextRebuild());
        s.captured(10, false, true); assertEquals(10L, s.nextRebuild());
        s.discard(10, k -> {}); assertEquals(10L, s.nextRebuild());
    }
}
