package dev.frostedfoliage.core;

/** A mesh acknowledgment contains only version numbers, never the snapshot's backing arrays. */
public record MeshRevision(long snapshot, long progress, int appearance) {
    public boolean matches(long snapshot, long progress, int appearance) {
        return this.snapshot == snapshot && this.progress == progress && this.appearance == appearance;
    }
}
