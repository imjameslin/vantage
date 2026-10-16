# Farsight architecture reassessment

2026-10-08. The user authorizes a complete rewrite and a step back to choose a
viable approach. This is a decision record, not a claim that the target is met.

## Live integration finding (supersedes the earlier missing-bridge status)

The opt-in Java/FFM/native bridge is built and tested, and its first real-game
screen exposed the predicted problem: global geometry invalidation starves the
cache during loading. Selection ran on 69 of 8,959 frames; the incomplete scene
already used 6,695.7 MiB of Metal allocations without a retained cache. The
6 GiB offline allowance therefore does not fit the existing live frame rings.
The run did not settle and is not usable FPS evidence.

The immediate prerequisite is reducing duplicate temporary-buffer memory and
bounding rebuild scope, with current-original fallback and no detail reduction.
A 250 ms geometry quiet interval now prevents doomed startup captures; runtime
query toggles no longer force an extra mesh-cache miss between same-jar A/B arms.
These fixes are tested, but they do not solve continuous streaming. See
`results/FARSIGHT-LIVE-SOLID.md` for hashes, raw observations and limits.

## Current decision after the full-scene screens

The full-scene capture and original-shader replay described later in this
document are complete. The following results supersede the older pending-work
wording below:

- The source-BVH depth traversal follow-up is now complete and rejected:
  **15.777ms versus9.616ms** original, with every candidate sample slower. It
  visits1.07M of1.14Mnodes and tests3.82Mindividualquads even at the initial
  oracle-seeded view. Turning visits almost the entire tree. Six image controls
  preserve winning sources and exact depth; sparse one-code color residuals
  remain explicit. See `results/FARSIGHT-SCENE-TREE.md`. No client/default gain.
  The next screen should aim for a complete-image intermediate improvement with
  hardware source discovery and original-source redraw, initially keeping full
  cutout/water raster. A partial real gain is useful even before700FPS; charge
  the whole path and qualify boundary/tie recovery before game integration.
- Actual-alpha movement census now measures the real missing work: one-frame
  X flight needs3,544 new source quads, but one-frame flight+turn needs27,898,
  with17,219 new foreground pixels over already filled seed terrain. Old-source
  reuse fails the turn image gate. Oracle-repaired original-shader draws take
  0.890–0.989ms, excluding discovery, and are not game-FPS evidence. An optimistic
  callback-free bounded ANY-hit screen still costs1.718ms before shading and is
  rejected as the target recovery schedule. See `results/FARSIGHT-SCENE-REUSE.md`.
  Next prototype one top-down source-BVH HiZ traversal with individual-quad leaf
  refinement and stable original-order compaction; prior flat cluster/microgroup
  queries and per-screen-tile ray packets did not test this scheduling. Charge
  the whole seed/pyramid/traversal/compaction/recovery, retain billboard fallbacks,
  and stop if it loses the paired original-draw test.
- Explicit shared SIMD32 packet traversal is now implemented over all original
  opaque quads and also **rejected**:138.07ms versus1.723ms original hardware
  query. The packet shares one stack per8×4pixels but preserves independent
  closest-hit tests. CPU coverage/tree validation passes; translated-view source/
  depth residuals remain substantial. The10ms early rejection gate shortened
  timing to three blocks. See `results/FARSIGHT-SCENE-PACKETS.md`. This rejects
  this software hierarchy/predicate, not every coherent traversal method.
  Next measure visibility change during realistic per-frame movement before
  designing reuse; newly visible foreground cannot be handled by hole fill alone.
- Full-scene exact fractional face bricks are now measured and the **scalar
  callback/DDA implementation is rejected**:106.02ms captured, or45.76ms with
  CPU-range-proven signed32 arithmetic, versus1.79ms original triangle query.
  The representation preserves4.16M face cells in56,076 bricks plus all original
  fallback triangles; its217.6MB AS and63.5ms build are smaller/faster, but do
  not buy rendering speed. Sampled full scans are rare; repeated cell traversal
  remains expensive. Sparse coverage/source/depth residuals and large-coordinate
  predicate limitations also remain. This was not explicit packet sharing and
  does not disprove every coherent architecture. Stop scalar callback tuning;
  inspect actual spatial visibility coherence before implementing shared work.
  See `results/FARSIGHT-SCENE-BRICKS.md`. No client/default/Prism gain shipped.
- The occupied-only rectangle follow-up is also measured and **rejected as the
  main route**: **1.886 ms versus 1.795 ms** for the original triangle query,
  before alpha/material/water/near-world work. Exact cell partitioning reduces
  9,089,562 triangles to 5,936,978, removes all intersection callbacks, and keeps
  every source cell/hole/fallback. Six fixed-scene views have zero missing/extra
  coverage, invalid source IDs or depth differences above the diagnostic scale;
  remaining source-ID ties still need image qualification. This is useful
  representation evidence, not delivered FPS. Stop plane-merging polishing and
  reassess traversal/reuse and perceptually bounded detail before another full
  implementation. See `results/FARSIGHT-SCENE-RECTANGLES.md`.
- Both complete sparse-plane query implementations have now been measured and
  **rejected as the main route**. Procedural bounds cost **5.426 ms versus
  1.992 ms** for the original triangle query; hardware plane triangles with
  hole-rejection callbacks cost **3.612 versus 1.919 ms**. Every candidate block
  is slower than every paired control block. Exact CPU reconstruction succeeds,
  and fixed-view coverage differs by at most one missing/extra pixel, but source
  ownership/depth residuals remain and alpha/material/water work is omitted.
  Smaller AS storage and faster builds are real, but do not make these paths
  a frame-time win. Preserve their source encoding; stop callback tuning. The
  occupied-only follow-up above removes callbacks but still does not win. See
  `results/FARSIGHT-SCENE-PLANES.md`.
- The complete sparse-plane census is finished. **3,425,991 of 4,544,781 opaque
  source quads (75.38%)** can share 606,680 sparse 8×8 planar tiles, with exact
  corner reconstruction. All exceptions and same-scale coplanar overlaps remain
  original triangles: 2,237,580 of them. The combined hierarchy estimate is
  **2,844,260 primitives versus 9,089,562**, not a measured GPU speedup. No cap
  was reached; CPU preparation costs 641 ms with 142.53 MiB scratch. The actual
  procedural screens above supersede the original recommendation to try this
  representation. CUTOUT fallback, alpha, edge ownership, cross-scale ties and
  incremental updates remain explicit work. See
  `results/FARSIGHT-SCENE-PLANE-AUDIT.md`.
- The conservative current-view empty-region mask is implemented. It skips
  **72.50%** of rays at the captured pose, with every source ID/depth bit intact
  in seven fixed-scene translated/zoom/near-plane controls. Final build+query
  timing is **1.689 ms** versus **1.886 ms** for the original query; ranges
  overlap and an earlier screen did not improve its median. Cached-mask cost
  is still 1.656 ms before source shading or the rest of the frame. This does
  not create target headroom. Stop expanding this mask as the main route; study
  hit-bearing traversal and a smaller exact surface representation. See
  `results/FARSIGHT-SCENE-RAY-MASK.md`.
- Full-scene hardware triangle visibility is now measured. Consolidating 26
  original-draw BLASes into one 26-geometry BLAS reduces the optimistic query
  from **3.958 to 1.776 ms** with unchanged source geometry. Every timing block
  of the combined query is faster than every per-draw block. Coverage is close
  and sampled exhaustive CPU intersection witnesses pass, but source ownership
  differs, CUTOUT is forced opaque, and source shading/water/update work is
  absent. It is **not a finished image or game-FPS gain**. The query alone still
  exceeds the complete 1.429 ms target interval. AS construction takes 243 ms
  GPU and adds about 1.175 GB retained storage. Do not integrate this naive
  full-screen schedule. See `results/FARSIGHT-SCENE-RAYS.md`.
- Smaller planar spatial groups improve quad rejection from 3.60% to 31.52%,
  with passing image checks, but cost **11.143 ms versus 8.538 ms** for the
  original draw. There are now 1.528M visibility queries. Do not integrate this
  seed/pyramid/query/compact/recover schedule. See
  `results/FARSIGHT-SCENE-MICROGROUPS.md`.
- Instancing one quad and generating six nonindexed vertices are both slower
  than the original indexed topology: **9.618 / 11.405 ms versus 7.946 ms**.
  Preserve the original topology. See `results/FARSIGHT-SCENE-TOPOLOGY.md`.
- A new minimal full-scene opaque raster pass removes RGB/fog-color shading,
  keeps actual cutout coverage, and exactly reproduces source IDs and depth.
  It still costs **5.783 ms** before color resolve or water, versus **7.697 ms**
  for the original all-layer draw. It already exceeds the whole 700 FPS frame
  interval by four times. Do not build a deferred resolve behind this measured
  precursor as the target route. See `results/FARSIGHT-SCENE-MINVIS.md`.

These are experimental paths, not delivered FPS improvements. They
demonstrate that reducing shading or rearranging the existing per-primitive work
is insufficient. The hierarchy screen now establishes that layout matters on
the **complete captured scene**, and the empty-region screen shows that skipping
most empty rays is not the missing large gain. The sparse-plane census now
establishes a substantial primitive-count reduction without changing source
corners, but the actual query screens show that sparse per-hit callbacks erase
that advantage. Occupied-only rectangle partitioning removes those callbacks,
but the complete measured query with final-hit source lookup remains slower
than the original triangle query. There is no current measured route inside
the whole-frame budget. The next decision must address spatial traversal/reuse
or demonstrated perceptually redundant detail, not assume a primitive-count
reduction automatically buys enough time.
No source ID, hole, tree gap or snow surface may be discarded to shrink counts.

For that next screen, retain source IDs, actual 3D face positions and openings;
partition immutable geometry into bounded acceleration-structure builds, and
measure traversal plus required coverage handling. Record temporary/final memory
and build/update time. Billboards, alpha-tested foliage and fade coverage must
be explicit: an opaque-only optimistic timing may reject an expensive approach,
but cannot qualify appearance or predict FPS. Use the same capture and original
raster controls. Do not start a broad engine rewrite until a combined complete
visibility/shading/water result beats the retained reference. No result here
proves that the user's minimum is achievable or impossible.

## Outcome required

700 FPS at native 3024×1964 means **1.429 ms per complete frame**, including the
near game, Farsight, composition and CPU submission. It must hold while flying
as well as standing, with the approved 512-chunk detail, canopy gaps, trunks,
snow, water, shading and zoom. A stationary color panorama is not an acceptable
substitute. Preserve the working raster renderer as the reference and fallback.

The current dense-forest controls are about 100–111 FPS: roughly 9–10 ms per
frame. Closing that gap requires about 6.3–7 times the complete-frame throughput.
The lighter rotating-view results and earlier no-LOD results are different
workloads; they cannot be substituted for this scene or added as independent
pass budgets. No current evidence establishes that 700 is achievable everywhere.

## What the measurements rule out as the main route

| Evidence | Consequence |
|---|---|
| Dense forest has 58.3M resident source quads / 2.08M candidate clusters, with 4.61M quads surviving selection and actually drawn at the final pose. GPU vertex work occupies about 99% of the traced control. | Reduce work proportional to geometry, not just shader arithmetic. Submitted and surviving counts are different quantities. |
| Gray-RGB diagnostic remains near 100–107 FPS with control drift. | Fog/RGB arithmetic is not the large missing factor. |
| Palette records and direct-quad metadata pass exact rendering checks but do not improve the game. Direct metadata also adds roughly 1.7 GiB of visible-list capacity. | Stop tuning these forms for now. Bytes saved or loads removed are not automatically frame-time savings. |
| HiZ reduces drawing but destroys vertex/fragment and vertex/compute overlap; none of H1–H5 beats the retained renderer reliably. | Do not repeat a depth/compute/recovery pass split without a different scheduling argument. |
| Cross-material patches reduce the audited SOLID primitive count by 32.5% ideally. Expensive per-fragment source replay is 39–44% slower in the dense synthetic case. | That implementation cannot close a roughly 6–7× full-frame gap. Preserve its source interpolation work, not its costly pixel search. |
| A native full-screen triangle query over only 1.56M SOLID quads costs 1.309 ms in a fragment pass before shading and omitted geometry. | A naive full-screen triangle RT replacement has no demonstrated complete-frame headroom. |

Sources: `results/FARSIGHT-HIZ-GPU.md`, `FARSIGHT-SHADER-PROBE-P1.md`,
`FARSIGHT-PALETTE-P1.md`, `FARSIGHT-DIRECT-QUADS-PROTOTYPE.md`,
`FARSIGHT-PATCH-GPU-PROTOTYPE.md`, and `FARSIGHT-RT-PROBE.md`.
GPU channel intervals overlap: never sum their reported durations to predict FPS.

## Candidate direction: separate spatial visibility from surface shading

The next candidate represents the same three-dimensional surfaces in spatial
blocks, finds the surface visible at each needed pixel, then evaluates the
original source attributes. This is a testable direction, not yet the selected
shipping implementation. There are three bounded parts:

1. **Exact spatial representation.** Sparse face bricks retain each eligible
   original face and its source ID. Fractional/model/overlapping cases remain
   original triangles. The current 8³ codec reduces the bounded fixture from
   3.12M triangle primitives to 13,746 bricks plus 878,214 fallback triangles.
   CPU reconstruction is exhaustive; GPU traversal speed and robustness remain
   the decision. Do not fill empty cells, join canopies to the ground or extend
   top snow downward. Fallback triangles dominate the remaining primitive count.
2. **Invoke visibility only where needed.** Use current spatial bounds to limit
   fragment queries, eventually with proper near-world occlusion. The first
   added control draws only within a conservative projection of the actual
   fixture AABB, retaining full-screen clear/store and full bounds whenever the
   box reaches the camera plane. It changes no rays inside that rectangle and
   checks every output against the full-screen reference. Its subset coverage
   is not a forecast for a complete 512-chunk scene. Multiple overlapping bounds
   must not create duplicated queries or miss the nearest surface.
3. **Independent shading.** A visibility record identifies the original source
   face and triangle. Reconstruct its original corner attributes, half rounding,
   perspective interpolation, atlas mapping, filtering gradients, lightmap and
   fog. The raster visibility/resolve proof now retains exact IDs/depth in 32
   bounded pairs, with sparse color residuals. Its worst saved full image passes
   the existing perceptual metric, but movement is unqualified. Cached resolve
   costs 0.218–0.330 ms on small layouts, 2.22–2.95× their original draw, so this
   separation is not itself a performance win. Test query and shading in one
   fragment pass to avoid an intermediate full-resolution ID store/read; do not
   assume combining their separately measured costs predicts the result.
   Shared-edge equal-distance hits alone are not a
   color-parity test; different source IDs may have different materials.

Keep near vanilla terrain/entities on their current path initially. CUTOUT,
animated alpha, billboards and translucent water must have explicit correct
paths; leaving them out of a probe cannot count as the final renderer. Reuse the
existing immutable world/cache inputs and multiplayer behavior. Replace the
GPU representation and scheduling behind an opt-in switch, not the user's
installed release during experimentation.

## Whole-frame constraints, not invented component budgets

The complete selected-scene raster replay now exists
(`results/FARSIGHT-SCENE-REPLAY.md`). It preserves all58.3M source records and
the actual4.61M visible-quad list across SOLID/CUTOUT/TRANSLUCENT. The original
LOD-only draw costs7.913ms at native resolution and6.248ms at half width/height
in the short isolated probe. This is not game FPS or a hardware lower bound;
near terrain, culling, queries, streaming and presentation are excluded. It
confirms that geometry/visibility work, especially SOLID, must be reduced.
Use this workload for architecture decisions, not the earlier tiny SOLID probe.

The complete-scene final-face audit (`results/FARSIGHT-SCENE-VISIBILITY.md`)
now quantifies that opportunity: only253,468 of4,544,781 processed opaque quads
contribute final pixels. Their original-shader draw, retaining all water, costs
0.88–0.91ms with exact depth and sparse one-code color residuals that pass the
existing perceptual metric. This uses an oracle's source-face selection; its
selection cost is excluded, so it is not a delivered optimization or FPS claim.

An optimistic current-source occluder prepass plus conservative original-cluster
queries and stable GPU compaction was **rejected**:11.285ms vs7.708ms original,
only3.6% of quads removed. Empty-depth and translated-camera recovery controls
pass within the fixed captured candidate set. Even ideal original-cluster
selection retains1.731M quads versus319k per-face including water. The next
implementation must improve spatial granularity/representation or use a
different visibility method. Do not repeat the same broad-cluster HiZ pass as
though the oracle selected faces were free or this failure had not been measured.

- **Both CPU and GPU must sustain the frame interval.** Current `cpuFrameUs`
  includes load/selection work across a run; it is not a steady per-pass floor.
  Record steady submission cost and selection/upload spikes separately. Existing
  persistent tables and versioned draw lists should survive the rewrite where
  useful. Do not rebuild all selected geometry or an entire AS every frame.
- **Build and streaming cost count.** The triangle fixture AS takes about 68 ms
  to build. A fast static query with that work on the render thread is not a
  viable flying renderer. Use bounded immutable per-region generations and
  deferred GPU retirement; replace only dirty regions and report peak live
  memory while old/new generations coexist.
- **Preserve useful GPU overlap.** A visibility texture and separate resolve
  may add attachment traffic or serialization. Test the combined critical path,
  including clear/store/resolve/depth composition, before predicting game FPS.
  Fragment queries are promising because they can participate in raster passes;
  that does not prove early depth tests or fusion work with the final shader.
- **Visibility reuse must be correct during movement.** Stationary source-ID
  reuse can still refresh lighting, fog and animated materials. Reprojection
  plus hole filling alone is insufficient while moving: a newly exposed front
  occluder can cover an already populated background pixel. Recompute visibility
  or prove conservative validity for every reused pixel. FOV/zoom, geometry,
  alpha, origin changes and reloads invalidate relevant state.
- **No silent quality adaptation.** Freeze radius, tau and scene content for
  comparisons and report all budget refusals/pending detail. Coarsening because
  a new representation exceeds its memory budget is a failed comparison.

## Decision gates and next implementation order

1. Finish the already-running mixed brick/triangle query and review the completed
   visibility/resolve proof. Stop a candidate if correctness fails or representation overhead
   erases the gain. Keep failed results; do not repeatedly expand their tests.
2. Combine successful visibility and shading mechanisms in one offscreen path
   over the *same source snapshot*, including source attributes. Measure the
   complete clear/query/shade/depth output together, not a sum of fastest modes
   from unrelated runs. Add foliage alpha and water or separately account for
   their exact retained raster work before any full-scene projection.
3. Capture/replay the settled game's actual selected geometry across levels,
   camera and material state, with hashes and omitted-work accounting. The
   current 640-node, level-0, SOLID-only fixture is deliberately incomplete and
   cannot qualify the architecture. Confirm a combined win before writing a
   broad native/Java renderer replacement.
4. Integrate one opt-in path and perform a short matched forest/vista/flying/
   zoom screen at native resolution. Record total FPS, tails, submission time,
   full GPU work, streaming/build spikes, resident and peak memory, source/detail
   counts and image/motion differences. A static-only gain does not pass.
5. Only a passing candidate receives broader multiplayer/resource-pack/reload/
   soak checks and release packaging. If the architecture misses 700, report the
   measured result and remaining dominant cost; do not relabel partial-query
   throughput or a lower-detail scene as success.

This changes the priority from further local record/shader tweaks to a bounded
proof of a different complete visibility-and-shading path. The original raster
quality reference and the 700 FPS requirement remain fixed.

## Follow-up screens and next decision (2026-10-08)

The two bounded screens have finished; neither is a shipping win:

- Mixed face-brick/fallback AS storage is 84.74 MB versus 296.82 MB, and build
  time in the retained run is 16.39 versus 68.25 ms. Its small 1,054-ray suite
  passes, but the forest screen stops at a fallback triangle edge whose selected
  source differs from the full triangle AS. Equal float distances do not prove
  source membership. No paired traversal timing was accepted. See
  `results/FARSIGHT-BRICK-RT.md`.
- Combined fragment query, original shading and depth costs 0.249–1.012 ms on
  the bounded small layouts, versus 0.095–0.151 ms for raster. It preserves the
  explicit hole/foreground witnesses but leaves sparse coverage/source/depth
  differences. This is not a full-scene result or a quality acceptance. See
  `results/FARSIGHT-RT-SHADE.md`.

Source identity is a diagnostic, **not a new pixel-exact user requirement**.
The user's acceptance remains perceptual and temporal. The measured edge
difference alone neither proves a visible defect nor disqualifies every brick
architecture. Before investing in a repair, quantify all differences and the
total cost on representative data; retain failed cases and keep experimental
timing separate from a validated gain. Do not spend another round polishing a
tiny proof whose complete-frame cost has not been established.

The immediate prerequisite is a benchmark-only, lossless capture of the complete
selected scene: every source layer and LOD level, material atlas, lightmap,
actual camera/uniform state and post-cull draw lists. Source candidates and
actual visible quads must remain distinct. The final game color/depth image is
context only (world depth is cleared later for the hand/GUI); it is not an
isolated LOD or near-occluder reference. Build a matched offscreen raster replay
before comparing a complete alternative. Account for culling, streaming, memory,
CUTOUT and water explicitly; no full-game FPS can be inferred from this capture.

## Complete captured-scene intermediate result

The full-scene capture/replay prerequisite and losing traversal screens are now
complete. The next SOLID-only hardware-discovery hybrid measures a complete
LOD image at6.259ms versus8.263ms in its paired original raster control. It uses
current queries, stable original-source redraw and retains all CUTOUT/water;
there is no oracle inside its candidate frame. See
`results/FARSIGHT-SOLID-HYBRID.md` for exact scope, residual missing faces and
six-view/12-frame perceptual evidence. AS construction and live scene updates
remain excluded;700FPS is still unmet.

Keep this as an experimental intermediate win. Reduce charged source-list
construction next, then evaluate one opt-in integration. The complete-image
gain, rather than isolated query throughput, justifies that next step. Do not
present the observed24%GPU-time saving as measured gameplay FPS or as Voxy
quality certification for all content.

The packed stable list builder now lowers the complete path to5.121ms versus
7.757ms original in one interleaved run, preserving the generic filter's exact
selected entries and order. This34%GPU-time reduction is stronger evidence for
the intermediate path. Next resolve incremental AS lifetime/streaming and test
live totals; also keep pursuing cheaper visibility toward700. No full-game FPS
forecast follows from the isolated LOD result.

## First live membership protocol

Use an immutable AS source snapshot, but discover visibility against the current
frame. A pure camera turn must not require rebuilding the AS or trusting an old
compacted list. The conservative protocol is:

1. Clear per-snapshot-owner PRESENT/VISIBLE flags and a frame fallback word.
2. Scatter PRESENT from **current pre-hybrid eligible SOLID draws**, recording
   each current entry's snapshot owner or a sentinel for a new/unrepresented
   source. This is exact source identity, not a hash with collision risk.
3. Run closest-hit queries on the immutable AS. A hit whose owner is not PRESENT
   sets the fallback word. A present hit ORs VISIBLE. Invalid results also set
   fallback. Genuine misses do not.
4. After all query/mark work finishes, compact current original entries with
   `fallback || sentinel || VISIBLE`, preserving their current order and args.
   All CUTOUT, water, fades and unsupported sources remain original raster.

Proof for one ray: if its closest snapshot hit is present, removing absent
snapshot triangles cannot reveal a closer represented current triangle. If the
closest hit is absent, retaining the entire current frame recovers everything
it could have blocked. A miss is also a miss in the represented current subset.
New sources always survive. Thus absent snapshot sources outside the view need
no full-set-equality rejection. This proof requires **closest**, not traversal-
first/ANY, hits. It adds no new claim about ray/raster edge or coplanar tie parity;
those remain governed by the existing perceptual and temporal checks.

Geometry/decoding changes, allocation/section-slot reuse, world replacement and
resource reload still invalidate the geometry epoch. A PRESENT identity must
refer to the same corners/transform as the snapshot; matching arena offsets
alone is insufficient after reuse. Exclude fading/billboard sources from PRESENT.
Freeze epoch, source maps and resources for the whole GPU submission. Use atomic
loads/ORs and ordered GPU phases; a threadgroup barrier cannot order all queries
before compaction. No current-frame CPU readback belongs in this protocol.

This protocol is implemented and independently reviewed in
`native/src/mcmetal_lodsolid_select.{h,m}`, with GPU tests against changing
current lists and the full captured forest. It is **not wired into live Java
draws at the time of that check**. See `results/FARSIGHT-SOLID-HYBRID.md` for the final safe-math source
hashes/artifacts,21,737 focused checks and11 perceptually qualified image pairs. Fallback pays query overhead plus the complete original draw; measure its
frequency and all build/streaming costs. The one-build async cache can still be
starved by rapid epoch changes. First establish a correct opt-in live baseline,
then partition or otherwise incrementally rebuild from measured churn. Do not
claim that a dedicated build queue alone prevents GPU stalls, or that this
intermediate path meets700FPS.

Live Java integration and the first in-game checks now exist; the current record
is `results/FARSIGHT-LIVE-SOLID.md`. The first run exposed capture starvation and
an absolute allocation cap already exceeded by the existing renderer. S4 fixes
unnecessary membership invalidation, debounces geometry churn and reduces quad
work-buffer capacity by58% for the same required capacity. Its short off-selector
screen remains about103FPS with no measured FPS win. Non-drawn resident meshes
vary substantially across launches, confounding total-memory comparisons.
Address that retained residency and scoped capture/build work before interpreting
live selector performance. The earlier captured-LOD34% gain remains a separate
diagnostic; default/Prism status and the unmet700FPS target are unchanged.
