# Terrain occlusion culling on Metal (`MCMTL_OCCLUSION`)

Task: stop drawing terrain sections that are hidden behind terrain already drawn, without any visible popping,
on the M5 at 3600x2260. `docs/stage2/culling.md` (P5) had concluded that classic occlusion culling is not worth it
at this resolution. This document re-checks that with measurements on the current, much cheaper frame, describes
what was built, and says what it buys.

Code: `native/src/mcmetal_decoder.m`, section "Terrain occlusion culling". Contract: `docs/metal/CONTRACT.md` §16.6.
Java and the game are unchanged.

---

## 0. Summary

**Recommendation: keep it off (the default). It costs nothing when off and is safe to try, but the expected
gain at the benchmark pose is near zero.**

- Built at the backend level, as GPU visibility queries in the decoder. On a tile-based GPU these are counted on
  chip, so nothing is stored, split or reloaded.
  - The opaque terrain records count into per-section slots.
  - After the last opaque terrain record, a box query is drawn for every culled or candidate section. It has no
    colour or depth writes.
  - Results are read once their frame completes (2–3 frames later). A section is skipped while its
    `[-2, 18]³` box had no sample in its last 2 queries, from the same eye, entirely on screen.
- Correctness, measured with byte comparisons of every frame, colour and depth:
  - headless native test: 678 frames, 0 differing pixels (static, turns up to 15°/frame, zoom, walking, a pit dug
    into culled caves and refilled, the benchmark view spinning);
  - Java end-to-end test with the vanilla `*_TERRAIN_MULTIDRAW` pipelines: 180 frames identical, about 20 % of the
    terrain quads culled.
- It is **not provably exact**, which is why it stays off (§3.3):
  - a box tested at earlier pixel centres can miss a sub-pixel gap that a rotated camera's pixel centres hit;
  - animated cutout textures can open gaps;
  - a gap that opens is seen 2–3 frames late.

  None of these showed up in any test. The camera must be still (rotation is allowed) for anything to be culled;
  any translation, bob or zoom drops all evidence.
- **Measured gain, terrain-only frame at 3600x2260 through the real decoder** (§4.2):

  | View | GPU time saved per frame |
  |---|---|
  | Benchmark view (40 above the terrain, spinning) | −0.003 to +0.009 ms pipelined; −0.03 to +0.01 ms serialized |
  | Ground level in a valley | −0.015 to −0.06 ms pipelined; −0.07 to −0.12 ms serialized |

  The game's GPU frame is ~1.6 ms, and the GPU is only ~51 % busy: the frame rate is bound by drawable pacing. **No
  measurable FPS change is expected at the benchmark pose.** Ground-level and cave-heavy views may gain a few
  percent of GPU time.
- **Why the bound is low on this GPU.** Even zero-cost ideal culling of every hidden section saves only
  ~0.065 ms per 100 k front-facing quads when frames overlap (0.2–0.25 ms serialized). After facing buckets and
  the exact frustum, the benchmark view has little hidden geometry left.
- **The depth-pyramid design is dead on this hardware, though not for the reason P5 gave.**
  - Storing the 3600x2260 depth is nearly free: +0.005–0.02 ms, thanks to lossless compression.
  - Building the base level of a min-depth pyramid from it costs **0.15 ms**. That is more than the whole
    upper bound at the benchmark pose.
  - The depth is also unavailable in the merged encoder until it ends, after the hand's depth clear.

Toggles: `MCMTL_OCCLUSION=1` culls; `=2` is a dry run (all queries and statistics, nothing culled: shows in-game
what would be culled at the query cost); `MCMTL_OCCLUSION_CONFIRM` (default 2), `MCMTL_OCCLUSION_NEAR` (default 8
blocks), `MCMTL_OCCLUSION_MIN_AGE` (test knob). Statistics: `MCMTL_DECODER_STATS=1` (every 600 frames, or `=N`
every N frames) adds an `mcmtl occlusion` line (§5).

---

## 1. Re-evaluation: what could occlusion culling save at all?

### 1.1 Method

`tools/occlusion-bench.sh` (`tools/occlusion_bench.m`) is a standalone, offscreen Metal program. It builds a
synthetic Minecraft-like world with these properties:
- a 26x26-chunk area with value-noise hills, cut to the RD 12 circle;
- optional cave voids; trees with cutout leaves (35 % transparent texels);
- vanilla meshing (one quad per exposed face) into 28-byte BLOCK vertices.

It draws the terrain the way the game does today:
- facing buckets with gaps bridged;
- the exact frustum on tight bounds;
- one indirect record per run, a 16-bit quad index buffer;
- shaders equivalent to `terrain.vsh` / `terrain.fsh`;
- reverse-Z GEQUAL, a 3600x2260 BGRA8 target, depth DontCare.

Per pose it times these configurations, interleaved in rotated order:

| Configuration | What it draws |
|---|---|
| full | today's terrain |
| ideal | only the sections that put at least one sample at the final depth |
| full+vis | full, with one visibility counter per section |
| ideal+box | the visible set with counters, plus one box query per culled section after the opaque terrain |
| full+zst | full, with the depth stored |
| full+hiz | full+zst, plus a compute pass that builds a 16x16-tile min-depth map (HiZ base level) |

**The ideal set.** An oracle builds it. It draws everything, then redraws every section with GEQUAL, no writes and a
per-section counter. A counter of 0 means every fragment of the section is strictly behind the final depth.
Removing such sections renders the same bytes: verified, 0 px difference in 15 of 16 poses, 1 px in one. That
pixel is an artefact of the oracle's no-write pipeline at an alpha-test edge, not of culling.

**Metrics.**
- *Pipelined* is GPU throughput with 3 frames in flight: last end minus first start, over N frames. This is what a
  frame costs when frames overlap, as in the game.
- *Serialized* is one frame committed and waited for alone.
- Per-command-buffer spans of overlapping frames are meaningless and were not used. A visibility buffer shared
  between frames serializes them, so the queries use one buffer per frame in flight.

### 1.2 Results (M5, 3600x2260, terrain only)

**World without caves (hidden geometry = terrain behind hills):**

| Pose | Quads drawn | Ideal culls | full | ideal | full+vis | ideal+box | full+zst | full+hiz |
|---|---|---|---|---|---|---|---|---|
| Bench, yaw 0 / 90 / 180 / 270 | 74–90 k | 3–7 % | 0.48–0.54 | 0.47–0.54 | ≈ full | ≈ ideal | +0.005–0.02 | **+0.15–0.17** |
| Ground (valley), 4 yaws | 47–95 k | 96–99 % | 0.39–0.43 | 0.33–0.41 | ≈ full | +0.00–0.01 | +0.005–0.01 | +0.15 |

Pipelined ms per frame. The ideal saving is ≈ 0 (±0.008 ms, noise) at the bench pose and 0.02–0.06 ms on the
ground, i.e. **~0.065 ms per 100 k culled quads**. Serialized, the ground saving is 0.10–0.22 ms.

**World with caves (bench pose: 33–40 % of the drawn quads hidden, close to culling.md's model):**

| Pose | Ideal saves, pipelined | Ideal saves, serialized |
|---|---|---|
| Bench, 4 yaws | 0.002–0.04 ms | 0.09–0.16 ms |
| Ground, 4 yaws | 0.04–0.17 ms | 0.21–0.43 ms |

**Box-query cost** (ideal+box against ideal, loose `[-2, 18]³` boxes, world with caves):
- Front faces only: +0.025–0.065 ms pipelined for 400–750 boxes at ground level.
- Both faces (no culling): up to +0.22 ms, because boxes near the camera are large on screen. Front-face-only boxes
  are required.

**Conclusions.**
1. Occluded front-facing quads are cheap on this tile-based GPU. Hidden-surface removal rejects their fragments
   before shading, so only vertex and tiling work remains, and it overlaps the previous frame's fragment work.
   Culling's 0.25–0.32 ms and the plan's 0.12–0.24 ms per 100 k quads were whole-frame fits. **Measured here, it
   is ~0.065 ms pipelined.**
2. At the benchmark pose, even perfect culling is worth ≤ 0.04 ms per frame.
3. Any design that reads the full-resolution depth (HiZ, reprojection) starts with a 0.15 ms build. It cannot win.
4. Visibility queries are free when pipelined and need nothing stored. They are the only mechanism worth building
   here.

---

## 2. Designs considered

| Design | Verdict |
|---|---|
| (1) Previous-frame depth → HiZ → reprojected AABB test → zero `instanceCount` | 0.15 ms to build the pyramid (§1.2) is more than the upper bound at the bench pose. The opaque terrain's depth is never in memory: the merged Sky..GUI encoder (M2) clears it in-encoder for the hand. Reprojection is not conservative at silhouettes or sub-pixel gaps. Rejected. |
| (1b) Same-frame two-phase HiZ (draw last frame's visible set, build HiZ, test the rest, draw them) | Exact, but it splits the main encoder: colour + depth stored and reloaded, ≈ 130 MB at 3600x2260. Rejected. |
| (2) Visibility queries from the previous frames + box re-tests | Counted on chip, written at encoder end, ~free (§1.2). Not exact under camera motion → only when the eye is fixed; residual risks in §3.3. **Built.** |
| (3) Depth pre-pass of large occluders / CPU software occlusion | Needs watertight occluders from the game's block data (`isSolidRender` regions, face-occlusion subtleties) and a rasteriser; culling.md's model gives −13 k (bench) / −32 k (ground) quads, i.e. ≤ 0.01–0.02 ms here. Not worth it. |

---

## 3. What was built

### 3.1 Data flow (all in the decoder, CPU side at decode time)

1. **Index.** The M2 look-ahead index over the main stream also records two things:
   - every DRAW_INDEXED_INDIRECT whose pipeline is multidraw terrain, with its bindings;
   - every buffer write: COPY_BUFFER with its CPU source, readbacks.

   A pipeline is multidraw terrain when all of these hold:
   - Position float3 is in slot 0 and an int3 ChunkPosition is per instance in slot 1, stride 16;
   - Projection / Globals / TerrainUniform sit at vertex buffers 0 / 1 / 5, the only dense UBO;
   - it draws filled triangles.

   It is *opaque* with no blending and GEQUAL with writes, and *translucent* with blending. The vanilla
   SOLID / CUTOUT / TRANSLUCENT `_MULTIDRAW` pipelines classify; the Java E2E test checks this.
2. **Plan** (FRAME_END COMMIT submits only).
   - Apply the query results of completed frames.
   - Require all opaque terrain in one pass, with one transform.
   - Read the uniforms, records and section origins on the CPU, resolved through this call's copies: the
     section infos are a staged upload. A range written by an earlier call that may not have run, or a stream
     the index did not cover, means no plan.
   - Then decide, per section:

   | Decision | Rule |
   |---|---|
   | culled | `boxRun ≥ confirm`, the last box result ≤ 8 frames old, the box fully inside the frustum now, farther than `near` blocks |
   | box-queried | culled, or a candidate (no sample in its last geometry query) whose box is fully inside and not near; a candidate whose box showed a sample waits 8 frames |
   | geometry-queried | not culled, and could be culled (fully inside, not near) |

   - Slots: Metal allows each visibility offset **once per encoder** (the debug layer aborts otherwise). A
     geometry slot therefore covers one run of consecutive encoded records of one section; box slots are unique.
3. **Encode.**
   - The encoder that will hold the opaque terrain gets the frame's visibility buffer. With pass merging, that is
     the encoder of an earlier pass.
   - Opaque records count into their run's slot. Records of culled sections are not encoded, in every terrain
     draw of that pass, translucent included.
   - At the first SET_PIPELINE of a non-opaque-terrain pipeline or END_PASS after the last opaque terrain record
     (before entities, translucent terrain, clouds and the hand's depth clear), the box queries are drawn:
     - an internal vertex-only PSO per attachment signature, so it also works on the fused BGRA8 drawable;
     - colour writes off, depth GEQUAL without writes;
     - FLIP_VERTEX_Y and clockwise front faces with back-face culling, like translated vertex stages;
     - camera-relative box origins and Projection × ModelView from the CPU.

     The decoder's binding shadow is updated, and the next SET_PIPELINE restores everything.
4. **Read back.** A ring of 8 query buffers is used, each a Shared buffer with 8 bytes per slot, pre-filled with a
   sentinel (unwritten = unknown). Its slots are read when the frame's timeline value has completed: in practice
   2–3 frames later.

### 3.2 Why skipping is safe, frame by frame

**The pixel argument.**
- Opaque terrain uses GEQUAL with depth writes. The final colour of a sample is therefore the last fragment at the
  maximum depth. Removing fragments that are strictly behind the final depth changes neither colour nor depth.
- The box is drawn after all opaque terrain, against its depth. "No sample of the box passes GEQUAL" means every
  ray through the box's pixel centres hits opaque terrain in front of the box.
- The box contains all of the section's geometry: block models reach at most one block out of their cell, plus
  random offsets. So every fragment of the section, translucent included, is strictly behind the final opaque
  depth.
- Entities, translucent terrain and the rest are drawn later. They test against the same depth, which the
  section's absence does not change.

**Culled sections and evidence.**
- A culled section contributes no depth. Removing it cannot un-occlude another section, because its fragments
  were all behind other geometry.
- Evidence is invalidated when the scene changes:
  - **Eye.** Any change to the Projection matrix, CameraBlockPos, CameraOffset, or the translation or projective
    row of ModelView drops all evidence. This covers motion, view bob, FOV and zoom. Values are compared, so
    −0 = +0.
  - **Meshes.** A section that was encoded last frame drops the evidence of every section whose box overlaps its
    box on screen, if either of these holds: its records changed (mesh rebuilt, re-uploaded, translucent resort),
    or it vanished while its box still meets the frustum. More than 48 such changes in one frame trigger a global
    reset.
  - **Missing frame.** A frame without a plan, or a FRAME_END without one, drops everything.
- Rotation keeps the evidence. Occlusion from a fixed point does not depend on the view direction, and the box must
  have been entirely on screen in each evidence frame and be entirely on screen now.

### 3.3 Why it is still off by default: residual risks

1. **Sampling.**
   - The evidence comes from earlier frames' pixel centres. When the camera turns, new pixel centres sample new
     rays.
   - A gap in the occluders narrower than a pixel could be missed by every earlier sample and hit by a new one:
     cutout leaves, thin cracks, T-junctions.
   - The hidden section would then be missing from that one pixel until the next box query sees the gap, 2–3
     frames later.
2. **Animated cutout textures.** These are occluders whose coverage changes without a mesh change, for example
   kelp or seagrass. The same 2–3 frame latency applies.
3. **Anything that changes depth before the terrain without changing the records or the eye.** Nothing in vanilla
   does this. A mod could.

None of these produced a single differing pixel in the tests below, which include cutout leaves with 35 % holes and
fast turns. They cannot be ruled out by construction, so the feature stays behind the toggle. The parity harness's
deterministic scenes have a static camera, so they would pass by construction.

---

## 4. Tests and measurements

### 4.1 Correctness

**`native/tests/occlusion_test.m`** (part of `tools/build-native-tests.sh --run`, `MTL_DEBUG_LAYER=1`).
- Two decoder devices share every resource: OFF, and ON (results read 2 frames late). A third device runs the dry
  run.
- The streams are shaped like the game's:
  - staged upload of the section infos;
  - SOLID / CUTOUT / TRANSLUCENT multidraw records with facing buckets and the exact frustum;
  - an "entity" draw after the opaque terrain;
  - water; caves; trees with holed leaves.

Every frame's colour and depth must be byte-identical:

| Phase | Frames | Differ | Culled sections / frame | Terrain quads skipped |
|---|---|---|---|---|
| Ground, static | 24 | 0 | 178 | 51 % |
| Ground, turning 3°/frame, pitch ±12° | 120 | 0 | 64 | 12.5 % |
| Ground, fast turns 15°/frame, pitch ±30° | 48 | 0 | 0 | 0 % |
| Static / zoom / zoomed | 42 | 0 | 9 | 15 % |
| Pit dug into culled caves, then refilled (mesh changes) | 60 | 0 | 158 | 38.5 % |
| Walking 0.2 blocks/frame, then stopped | 60 | 0 | 0.3 | 0 % |
| 40 above the terrain, spinning 2°/frame | 180 | 0 | 123 | 13.5 % |
| 40 above, static | 24 | 0 | 147 | 15.4 % |
| 40 above, spinning 0.12°/frame (36°/s at 300 FPS) | 120 | 0 | 136 | 14.2 % |
| **All** | **678** | **0** | 94 | 14.5 % |
| Dry run | 678 | 0 | (would cull 94) | — |

**`tools/occlusion-e2e-test.sh`** (`tools/OcclusionE2ETest.java`, needs `tools/build-client.sh`).
- The real `MetalBackend`, vanilla SOLID / CUTOUT / TRANSLUCENT `_TERRAIN_MULTIDRAW` compiled from the jar's
  shaders, `drawIndexedIndirect` records, one FRAME_END submit per frame.
- The scene is a wall of sections hiding 18 others.
- 180 frames: static, a slow turn, a faster turn back.
- Run with `MCMTL_OCCLUSION=0` and `=1`. All 180 colour + depth hashes are identical; the ON run culled 22–24
  sections per frame (~21 % of the quads).

All earlier suites also pass:
- unchanged: `decoder_test` (also with `MCMTL_OCCLUSION=1` / `=2` in the environment), `core_test`, `abi_test`,
  metal-smoke, metal-e2e, terrain-test, terrain-e2e (both e2e tests also with `MCMTL_OCCLUSION=1`);
- the drawlist bench check, metal-device-test, i5-pipeline-test, test-i4-encoder, core-msl-test.

### 4.2 GPU time through the real decoder

Command: `OCCLUSION_BENCH=1 OCCLUSION_BENCH_FRAMES=600 build/native-tests/occlusion_test`. Terrain-only frames at
3600x2260, 2 in flight, 5 rounds × 600 frames per view, OFF and ON interleaved. Medians; differences between runs
are about ±0.01 ms pipelined and ±0.03 ms serialized.

| View | Culled | Pipelined OFF → ON | Serialized OFF → ON | Planner CPU |
|---|---|---|---|---|
| No caves: bench view, spinning 0.12°/frame | 0 % of quads | 0.499 → 0.508 (+0.009) | 0.639 → 0.651 (+0.012) | 0.015 ms |
| No caves: ground, static | 63 % | 0.385 → 0.349 (−0.037) | 0.444 → 0.374 (−0.069) | 0.013 ms |
| No caves: ground, spinning | 61 % | 0.405 → 0.391 (−0.015) | 0.551 → 0.433 (−0.118) | 0.012 ms |
| Caves: bench view, spinning | 18 % | 0.617 → 0.614 (−0.003) | 0.828 → 0.798 (−0.030) | 0.035 ms |
| Caves: ground, static | 64 % | 0.460 → 0.402 (−0.059) | 0.625 → 0.511 (−0.114) | 0.022 ms |
| Caves: ground, spinning | 64 % | 0.489 → 0.446 (−0.042) | 0.685 → 0.576 (−0.109) | 0.025 ms |

**Overhead with nothing to cull.**
- About +0.01 ms GPU (queries, box draws).
- About 0.015–0.035 ms of decoder CPU per frame (the planner).
- Fewer records to encode give some of that back.

**Culling effectiveness.** The loose `[-2, 18]³` boxes confirm about half of what the oracle finds hidden at the
bench pose: 18 % culled against 33–40 % ideal, with caves. Tight per-section bounds would confirm more. Even so,
the ideal pipelined saving there is ≤ 0.04 ms (§1.2).

---

## 5. Statistics (in-game)

`MCMTL_DECODER_STATS=1` with `MCMTL_OCCLUSION=1` or `2` adds one line per log:

```
mcmtl occlusion (mode 1 = cull, confirm 2, near 8): planned P of F frames; per planned frame: sections S, culled C
(dry run would cull W), records skipped R, quads skipped Q of T (x%), geometry queries G, box queries B, planner t ms;
eye/projection resets E, sections invalidated by changes I, result frames used U / dropped D; no plan: <reason>=n ...
```

**No-plan reasons:**

| Reason | Meaning |
|---|---|
| `no-index` | M2 off |
| `no-terrain` | menus, or the terrain pipelines did not classify |
| `passes` | opaque terrain in more than one pass |
| `unresolved` | records, origins or uniforms not readable at decode time |
| `transform` | terrain draws with different matrices |
| `records` | e.g. `instanceCount != 1` |
| `ring` | all query buffers in flight |

**Suggested in-game check.**
1. Bench with `MCMTL_OCCLUSION=2`. This gives the query and planner cost, and what would be culled at the real
   pose.
2. Bench with `=1`, and compare FPS and the decoder's CPU submit time.

The benchmark spins at a fixed position, so the eye lock holds and culling is active.

---

## 6. If this is revisited

- **Tight boxes.** The decoder can read each record's vertex range on the CPU (the heaps are Shared). Bounds cached
  per record signature would:
  - confirm about twice as many sections at the bench pose;
  - make box queries cheaper;
  - stop vanished sections at the frustum edge from invalidating their screen neighbours.
- **Translation.** Inflate the evidence by the per-frame camera motion, or keep the evidence while
  `|Δeye|` × (distance to the nearest occluder) stays below a pixel. Both need care at silhouettes.
- **Same-frame exactness** would need two-phase rendering. That is ruled out on this GPU by the encoder split (§2).
