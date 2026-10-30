package dev.frostedfoliage;

import dev.frostedfoliage.core.RetryBackoff;
import dev.frostedfoliage.core.SectionRebuildQueue;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import static dev.frostedfoliage.core.SectionRebuildQueue.*;
import static org.junit.jupiter.api.Assertions.*;

class SectionRebuildQueueTest {
    @Test void foliageAndGroundShareOneRequestAndOneBudgetSlot() {
        var queue = new SectionRebuildQueue(4);
        assertTrue(queue.request(1, FOLIAGE, false));
        assertTrue(queue.request(1, GROUND, false));
        assertTrue(queue.request(1, FOLIAGE, true));
        assertTrue(queue.request(2, GROUND, false));
        assertEquals(2, queue.size());
        assertEquals(1, queue.drain(1, key -> true, (key, sources) -> {
            assertEquals(1, key);
            assertEquals(FOLIAGE | GROUND, sources);
        }));
        assertEquals(1, queue.pending(GROUND));
        assertEquals(0, queue.pending(FOLIAGE));
    }

    @Test void cleanupCannotFillTheSlotsReservedForUpdatesAndFullQueueAppliesBackpressure() {
        var queue = new SectionRebuildQueue(4);
        assertTrue(queue.request(1, FOLIAGE, true));
        assertTrue(queue.request(2, GROUND, true));
        assertFalse(queue.request(3, GROUND, true));
        assertTrue(queue.request(3, GROUND, false));
        assertTrue(queue.request(4, FOLIAGE, false));
        assertFalse(queue.request(5, FOLIAGE, false));
        assertTrue(queue.request(1, GROUND, false), "An existing cleanup can be promoted even at capacity");
        var requests = new ArrayList<Long>();
        queue.drain(4, key -> true, (key, sources) -> requests.add(key));
        assertEquals(List.of(3L, 4L, 1L, 2L), requests);
        assertTrue(queue.request(5, FOLIAGE, false), "A refused producer can retry after dispatch frees capacity");
    }

    @Test void cleanupMakesProgressUnderContinuousUpdatesEvenWithOneSlotPerTick() {
        var queue = new SectionRebuildQueue(8);
        queue.request(100, FOLIAGE, true);
        var requests = new ArrayList<Long>();
        for (int tick = 1; tick <= 4; tick++) {
            queue.request(tick, GROUND, false);
            assertEquals(1, queue.drain(1, key -> true, (key, sources) -> requests.add(key)));
        }
        assertEquals(List.of(1L, 2L, 3L, 100L), requests);
        assertEquals(1, queue.pending(GROUND));
    }

    @Test void thousandsOfSimultaneousRetriesStayBoundedAndEventuallyAllDispatch() {
        for (int budget : new int[]{2, 4, 8}) {
            var queue = new SectionRebuildQueue(64);
            var backoff = new RetryBackoff(20, 5);
            var sent = new HashSet<Long>();
            for (long key = 0; key < 4096; key++) assertFalse(backoff.due(key, 0));
            int tick;
            for (tick = 20; tick < 5000 && sent.size() < 4096; tick++) {
                if (tick % 20 == 0) for (long key = 0; key < 4096; key++) {
                    if (!sent.contains(key) && queue.canRequest(key, true) && backoff.due(key, tick)) {
                        assertTrue(queue.request(key, FOLIAGE, true));
                        assertTrue(queue.request(key, GROUND, true));
                    }
                }
                assertTrue(queue.size() <= 64);
                int count = queue.drain(budget, key -> true, (key, sources) -> {
                    assertTrue(sent.add(key), "Coalesced foliage/ground cleanup must dispatch once");
                    assertEquals(FOLIAGE | GROUND, sources);
                });
                assertTrue(count <= budget);
            }
            assertEquals(4096, sent.size(), "A full queue must defer work, not lose it");
            assertEquals(0, queue.size());
        }
    }

    @Test void unloadingAndResetDiscardRequestsWithoutSpendingTheRendererBudget() {
        var queue = new SectionRebuildQueue(8);
        queue.request(1, FOLIAGE, false);
        queue.request(2, GROUND, false);
        queue.request(3, FOLIAGE, true);
        queue.remove(1);
        assertEquals(1, queue.drain(1, key -> key != 2, (key, sources) -> assertEquals(3, key)));
        assertEquals(0, queue.size());
        queue.request(4, GROUND, false);
        queue.clear();
        assertEquals(0, queue.drain(8, key -> true, (key, sources) -> fail("Old-world request survived reset")));
    }
}
