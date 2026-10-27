package dev.frostedfoliage;

import dev.frostedfoliage.core.MeshAcks;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MeshAcksTest {
    @Test void onlyTheFirstBlockOfARunBeginsIt() {
        var acks = new MeshAcks<String>();
        assertTrue(acks.begins(7, 0));
        assertFalse(acks.begins(7, 1));
        assertFalse(acks.begins(7, 4095));
    }
    @Test void revisitingABlockBeginsANewRunOfTheSameSection() {
        var acks = new MeshAcks<String>();
        assertTrue(acks.begins(7, 5)); assertFalse(acks.begins(7, 6));
        assertTrue(acks.begins(7, 5), "A second job for the same section starts at a block the first already meshed");
        assertFalse(acks.begins(7, 6));
    }
    @Test void anotherSectionBeginsANewRunAndReturningBeginsAnother() {
        var acks = new MeshAcks<String>();
        assertTrue(acks.begins(1, 0)); assertFalse(acks.begins(1, 1));
        assertTrue(acks.begins(2, 0));
        assertTrue(acks.begins(1, 2), "Coming back to a section after meshing another one is a new job");
    }
    @Test void acknowledgmentsAreStoredRemovedAndCleared() {
        var acks = new MeshAcks<String>();
        acks.put(1, "a"); acks.put(2, "b");
        assertEquals("a", acks.get(1)); assertEquals(2, acks.size());
        acks.remove(1); assertNull(acks.get(1));
        acks.removeIf(key -> key == 2); assertEquals(0, acks.size());
        acks.put(3, "c"); acks.clear(); assertNull(acks.get(3));
    }
    @Test void clearingForANewWorldBeginsANewRunEvenMidSection() {
        var acks = new MeshAcks<String>();
        assertTrue(acks.begins(7, 0)); assertFalse(acks.begins(7, 1));
        acks.clear();
        assertTrue(acks.begins(7, 2), "A worker still meshing after a world change must acknowledge the new world's section");
    }
    @Test void runsAreTrackedPerWorkerThread() throws Exception {
        var acks = new MeshAcks<String>();
        assertTrue(acks.begins(7, 0)); assertFalse(acks.begins(7, 1));
        boolean[] other = new boolean[2];
        Thread worker = new Thread(() -> { other[0] = acks.begins(7, 1); other[1] = acks.begins(7, 2); });
        worker.start(); worker.join();
        assertTrue(other[0], "Another worker's first block of the same section begins its own run");
        assertFalse(other[1]);
        assertFalse(acks.begins(7, 3), "The other worker's run did not disturb this thread's run");
    }
}
