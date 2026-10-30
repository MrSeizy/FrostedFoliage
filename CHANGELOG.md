# Changelog

## Unreleased

- Mesh acknowledgments store revision numbers, allowing evicted or replaced foliage and ground snapshots to be reclaimed before renderer cleanup completes.
- Foliage and ground snow share a bounded rebuild queue. Duplicate pending requests are combined, cleanup retries obey the normal combined per-tick budget, and a full queue defers work without dropping it.
- The flight fixture reports frame-time percentiles, retained snapshot payload and peak rebuild requests per tick; the torn-build fixture verifies that an evicted snapshot can be reclaimed while its stale mesh still needs cleanup.

## 0.2.1 — 2026-10-26

Fixes from the adversarial audit (`ADVERSARIAL_AUDIT.md`):

- Rebuild retries now notice a mesh that was built while its snow changed: chunk workers acknowledge a section once per meshing run, so a torn build is requested again instead of being accepted. Snow that arrives or leaves while Sodium is still building a section no longer sticks.
- An unchanged recapture keeps its snapshot, so re-sent chunks, biome packets and settings refreshes no longer trigger an extra rebuild of every section they touched; exploring a snowy forest no longer costs a stream of needless ground rebuilds.
- With Sodium and **Fast** leaves, snow is drawn only on faces whose mask has no gaps (tops at any depth, all faces at 100% depth). Sodium draws those overlays opaque, which turned partial snow and the depth taper into a solid blanket.
- Frosted falling-leaf particle sprites share the 65,536-pixel source cap; oversized particle textures keep their vanilla sprite.
- The scheduler reorders its work at most every two seconds while moving one section at a time (immediately for larger jumps), removing a client-thread hitch at large cache sizes.
- Foliage geometry keys are always position-dependent; the duplicate chunk-load hook was removed.
- `/frostedfoliage status` reports `stale` and `unmeshed` separately; oversized block lists log once when truncated.

New client test scenarios: `-PtornTest=true` (a worker paused mid-section while snow changes) and `-PflightTest=true` (chunk streaming cost); `LostRebuildTest` and `GroundSnowTest` also check that re-sent chunks request nothing. A nightly GitHub Actions workflow runs the client fixtures under Xvfb.

## 0.2.0 and earlier

See the git history.
