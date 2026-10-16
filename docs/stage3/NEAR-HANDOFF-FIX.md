# Farsight near-terrain handoff repair

Status2026-10-09: implementation candidate; targeted independent review and headless checks passed; **not qualified**.
Progress and verification scope: `results/FARSIGHT-NEAR-HANDOFF.md`. The design was
independently reviewed before implementation. Follows the CPU-preparation boundary diagnostic, and is a shared
correctness prerequisite for that prototype and the Farsightv1 flying-flash fix.
No new renderer architecture/rejected experiment is reopened. Defaults stay off.

## Measured defect

`async-items-boundary-probe-a`, native3024x1964,512chunks/tau2/gpuMB6144:
at trajectory32 the spruce-leaf ray hits(214,79,-52). Its visible near section
at(208,64,-64) is UNCOMPILED with no draw geometry in both arms. The synchronous
LOD path has already removed level0node(13,-4) on chunk-data availability; async
still retains it. At33–35 the near section is compiled/uploaded, but async retains
the LOD. The adjacent y80section is already ready at32: whole-column readiness
cannot fix both cases. Detailed evidence is in FARSIGHT-CPU-PREPARE.md.
These post-render collision observations identify candidate surfaces, not exact
cutout-fragment ownership. The diagnostic is deliberately ineligible as a gate.

## Bounded implementation

1. Add one default-off handoff control, independent of CPUasyncItems. Admit it
   only with the optional native coverage extension present. Retention and masked
   culling must enable together; never retain near LOD with an unmasked fallback.
2. Keep selected L0geometry in both synchronous and async item snapshots through
   chunk-data/readiness changes. Preserve genuine selection departures, leases,
   eviction and LOD-to-LOD fades. Do not change scheduler/detail policy.
3. Capture exact near-section coverage in TerrainDrawList's existing mesh read,
   under dispatcher ownership, from the same mesh and allocations used for that
   frame. Do not perform a later independent scan. Missing/uncompiled/incomplete
   uploads are uncovered; a ready mesh is covered; validated EMPTY or compiled
   block-entity-only geometry is authoritative empty. Coordinates must be copied,
   not inferred from rotating section indices. Suppress all LOD layers together.
4. Fix/validate nonbudgeted empty publication under copyLock with coordinate and
   compile-sequence checks before relying on EMPTY. Current source publishes that
   result before locking; merely locking the observer does not establish safety.
5. Match current coverage to the L0slabs actually referenced by the installed draw
   list, including activeItems' leased retired geometry. Covered y80 and uncovered
   y64 in one column must remain independent. Coverage changes apply while the
   worker is pending and must not force whole-item repacking.
6. Own mask bytes per cull slot, separately from the item ring. Use a new optional
   cull entry point with explicit buffer/range and completion-checked lifetime.
   Byte-per-slot costs at most4MiB for4slots; a bitset is an optional later measured
   improvement. Clear every physical buffer's old coverage before reuse. Preserve
   backing offsets. Existing optional extension contracts remain unchanged.
7. All native cull paths must exclude covered sections before generating output,
   including bounds-invalid clusters and the section-occlusion prepass. Fused
   inactive lanes still participate in SIMD scans with zero contribution. On
   uncovering, discard stale temporal occlusion; invalidate image/cull reuse when
   coverage changes before any reuse early return.
8. A near section replacing an exposed fallback must use full near visibility in
   the same draw snapshot. Its usual fog fade and the3s lastDrawnMillis heuristic
   are not a readiness guarantee. Preserve the ordinary fade for unrelated near
   sections and keep this behavior gated with the handoff experiment.

Do not write per-frame coverage into the persistent sectionTable's unused w:
previous side culls may still read it. Main-stream writeToBuffer copies do not
automatically precede the cull extension's separately committed side buffer.
No CPU wait for the worker/GPU may be used to conceal this ownership problem.

## Required verification

- Mixed-height, ready/empty/uncompiled, every layer's upload requirements, full
  visibility at replacement, rotating coordinates and stale empty publication.
- Current installed vs resident geometry, leases, pending worker and fallback,
  acceptance/rejection, recycled slots and per-physical-buffer stale mask clearing.
- GPU-lag reuse, old-library admission, all cull modes, invalid bounds, partial
  SIMD groups, stale occlusion on uncovering and image/cull-reuse invalidation.
- Diagnostic boundary observation, then real non-diagnostic still/motion gates
  with their existing thresholds/activity checks; only then in-game FPS.

Retained hidden L0still consumes residency/item/cull work; no FPS gain is assumed.
This repair covers available L0fallbacks. Coarse nodes crossing the near boundary
and missing fallback meshes require explicit conservative treatment; a whole-slab
mask must never claim to clip part of a coarse node. Do not silently broaden this
patch into partial-coarse rendering or a new scheduler. Farsightv1's full flying
coverage checks must report any remaining cases.

## Coarse-boundary audit after the populated-reference motion pass

Confirmed by code review, not yet an in-game reproduction: `LodManager.visit`
disallows parent fallback when `overlapsRd`, even if children are incomplete.
`buildItems` skips missing child meshes and the outgoing parent's400ms fade does
not wait for replacements. This avoids coarse geometry occluding ready near
terrain, but can leave a gap. Do not remove that overlap guard without spatially
clipping the fallback against actual replacement coverage.

There is a second, narrower risk: active handoff disables the legacy ancestor
fade exemption, while exposure tracking contains only L0. A ready near mesh
replacing coarse-only coverage can therefore fade from fog. A bounded prototype
may track recently submitted coarse coverage for the existing ready-only
full-visibility latch; this would not fix a genuinely missing replacement.
It must preserve reset generations, height/footprint matching and unrelated fades.

## Occlusion-reset safety audit after the performance rejection

Do not simply stop resetting history on cover, or on uncover outside the current
frustum. A newly covered LOD occluder may be replaced by authoritative empty near
geometry: unrelated far sections can retain hidden verdicts from that old depth.
With two history buffers, hidden → covered for one frame → uncovered can also
read the untouched older hidden slot. Per-section bypass alone fixes neither
the unrelated-occluder case nor every history-generation mismatch.

A narrower reset needs evidence for every retained query view and generation:
newly exposed geometry cannot consume stale hidden results, and removed near/LOD
depth cannot leave affected queries hidden. Current-frustum exclusion is not
enough. `updateNearCoverage` precedes the current view-buffer write, so that
buffer is stale there. Include lag1/2, alternating buffers, invalid bounds,
section/fused/nonfused paths, private arguments and recycled/leased slots in the
regression cases. The existing native covered-entry test explicitly invalidates
the stale other ring; it does not prove that whole-history invalidation is safe
to remove. This audit changed no rendering or culling policy.

Suggested regression fixture: resident L1(1,0), RD2, camera chunk x−1→0,z0;
leave a data-bearing child nonresident and its near section uncompiled past400ms.
Then publish ready near geometry with visibility below1. Check both the missing
coverage interval and the later fade separately, including startup and async
installed geometry. The current120-frame pass does not cover these cases.
