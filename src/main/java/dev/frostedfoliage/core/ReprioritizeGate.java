package dev.frostedfoliage.core;

/**
 * Reordering every scheduler set costs milliseconds at large cache sizes, so a focus that moves one section at a time
 * only reorders every {@code minTicks}; a jump of {@code minSections} or more reorders at once. Client thread only.
 */
public final class ReprioritizeGate {
    private final int minSections, minTicks;
    private boolean set;
    private int x, y, z, tick;

    public ReprioritizeGate(int minSections, int minTicks) { this.minSections = minSections; this.minTicks = minTicks; }

    /** True when the scheduler should be reordered around ({@code x}, {@code y}, {@code z}) now. */
    public boolean due(int x, int y, int z, int tick) {
        if (set && x == this.x && y == this.y && z == this.z) return false;
        int moved = Math.max(Math.abs(x - this.x), Math.max(Math.abs(y - this.y), Math.abs(z - this.z)));
        boolean due = !set || moved >= minSections || tick - this.tick >= minTicks;
        if (due) { set = true; this.x = x; this.y = y; this.z = z; this.tick = tick; }
        return due;
    }

    public void reset() { set = false; }
}
