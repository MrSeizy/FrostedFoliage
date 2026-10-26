package dev.frostedfoliage;

import dev.frostedfoliage.core.ChunkRing;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChunkRingTest {
    private static List<String> drain(ChunkRing ring, int budget, long farthest) {
        var visited = new ArrayList<String>();
        ring.scan(budget, () -> farthest, (x, z) -> visited.add(x + "," + z));
        return visited;
    }

    @Test void visitsEveryOffsetNearestFirstWithStableTies() {
        var ring = new ChunkRing(); assertTrue(ring.resize(2));
        var visited = drain(ring, 100, Long.MAX_VALUE);
        assertEquals(25, visited.size());
        assertEquals(List.of("0,0", "-1,0", "0,-1", "0,1", "1,0"), visited.subList(0, 5));
        assertTrue(ring.finished());
    }

    @Test void passResumesAcrossTicksAndRestartsOnDemand() {
        var ring = new ChunkRing(); ring.resize(1);
        assertEquals(List.of("0,0", "-1,0", "0,-1"), drain(ring, 3, Long.MAX_VALUE));
        assertEquals(List.of("0,1", "1,0"), drain(ring, 2, Long.MAX_VALUE));
        ring.restart(); assertEquals(List.of("0,0"), drain(ring, 1, Long.MAX_VALUE));
    }

    @Test void backpressureRetriesTheSameOffset() {
        var ring = new ChunkRing(); ring.resize(1);
        var seen = new ArrayList<String>();
        ring.scan(5, () -> Long.MAX_VALUE, (x, z) -> { seen.add(x + "," + z); return seen.size() < 2; });
        assertEquals(List.of("0,0", "-1,0"), seen);
        assertEquals(List.of("-1,0", "0,-1"), drain(ring, 2, Long.MAX_VALUE));
    }

    @Test void offsetsBeyondTheFarthestAdmissibleChunkEndThePass() {
        var ring = new ChunkRing(); ring.resize(3);
        assertEquals(5, drain(ring, 100, 1).size());
        assertTrue(ring.finished());
    }

    @Test void unchangedRadiusKeepsProgress() {
        var ring = new ChunkRing(); ring.resize(1); drain(ring, 2, Long.MAX_VALUE);
        assertFalse(ring.resize(1));
        assertEquals(List.of("0,-1"), drain(ring, 1, Long.MAX_VALUE));
        assertTrue(ring.resize(2)); assertEquals(List.of("0,0"), drain(ring, 1, Long.MAX_VALUE));
    }
}
