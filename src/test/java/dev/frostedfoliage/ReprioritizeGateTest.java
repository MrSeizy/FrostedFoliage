package dev.frostedfoliage;

import dev.frostedfoliage.core.ReprioritizeGate;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReprioritizeGateTest {
    @Test void theFirstFocusAlwaysReprioritizes() {
        var gate = new ReprioritizeGate(2, 40);
        assertTrue(gate.due(0, 4, 0, 0));
    }
    @Test void aOneSectionStepSoonAfterwardsWaits() {
        var gate = new ReprioritizeGate(2, 40);
        gate.due(0, 4, 0, 0);
        assertFalse(gate.due(1, 4, 0, 5));
        assertFalse(gate.due(1, 5, 1, 10));
    }
    @Test void twoSectionsOfMovementReprioritizeAtOnce() {
        var gate = new ReprioritizeGate(2, 40);
        gate.due(0, 4, 0, 0);
        assertTrue(gate.due(0, 4, 2, 1));
        assertTrue(gate.due(0, 2, 2, 2), "Vertical movement counts too");
    }
    @Test void aSmallMoveReprioritizesOnceTheIntervalHasPassed() {
        var gate = new ReprioritizeGate(2, 40);
        gate.due(0, 4, 0, 0);
        assertFalse(gate.due(1, 4, 0, 39));
        assertTrue(gate.due(1, 4, 0, 40));
    }
    @Test void anUnchangedFocusNeverReprioritizes() {
        var gate = new ReprioritizeGate(2, 40);
        gate.due(0, 4, 0, 0);
        assertFalse(gate.due(0, 4, 0, 400));
    }
    @Test void resetForgetsTheFocus() {
        var gate = new ReprioritizeGate(2, 40);
        gate.due(0, 4, 0, 0); gate.reset();
        assertTrue(gate.due(0, 4, 0, 1));
    }
}
