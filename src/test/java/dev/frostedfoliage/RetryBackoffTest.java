package dev.frostedfoliage;

import dev.frostedfoliage.core.RetryBackoff;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RetryBackoffTest {
    @Test void firstRequestGetsOnePeriodToLand() {
        var backoff = new RetryBackoff(20, 5);
        assertFalse(backoff.due(7, 100));
        assertFalse(backoff.due(7, 119));
        assertTrue(backoff.due(7, 120));
    }
    @Test void retriesDoubleTheWaitUpToTheCap() {
        var backoff = new RetryBackoff(20, 2);
        backoff.due(7, 0);
        int tick = 20, retries = 0;
        int[] expected = {20, 60, 140, 220, 300};
        for (; tick <= 300; tick++) if (backoff.due(7, tick)) assertEquals(expected[retries++], tick);
        assertEquals(expected.length, retries);
    }
    @Test void settledKeysStartOver() {
        var backoff = new RetryBackoff(20, 5);
        backoff.due(7, 0);
        assertTrue(backoff.due(7, 20));
        backoff.settled(7);
        assertFalse(backoff.due(7, 21));
        assertTrue(backoff.due(7, 41));
    }
    @Test void keysAreIndependentAndCanBeDropped() {
        var backoff = new RetryBackoff(20, 5);
        backoff.due(1, 0);
        backoff.due(2, 10);
        assertTrue(backoff.due(1, 20));
        assertFalse(backoff.due(2, 20));
        backoff.removeIf(key -> key == 2);
        assertFalse(backoff.due(2, 30));
        backoff.clear();
        assertFalse(backoff.due(1, 1000));
    }
}
