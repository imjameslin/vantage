# Stage 2: culling (Minecraft 26.3 vanilla): what is culled, what the benchmark draws, and how to draw less without changing a pixel

Scope:
- section frustum culling;
- the cave-culling graph (`SectionOcclusionGraph`) and when it is active;
- entity, block-entity and particle culling;
- fog and far-plane culling;
- clouds and weather;
- ranked proposals that keep the output pixel-identical.

All source paths are relative to `src/vanilla`. "L123" means line 123 of the file just named.

Prerequisites:
- `docs/renderpearl/07-game-render-path.md` (render path; §4.5 is the starting point here);
- `results/GPU-PROFILE.md` (~1.1 ms vertex + ~1.5–1.7 ms fragment GPU time per frame on MoltenVK);
- the two sibling stage-2 docs, which this doc relies on rather than repeats:
  - `docs/stage2/meshing-and-vertex-format.md` (facing buckets in the mesher);
  - `docs/stage2/draw-submission-and-mdi.md` (bucket draw records, persistent draw list).

---

## 0. Summary

**Vanilla culling: four stages, all coarse**

1. **Cave-culling breadth-first search (BFS).**
   - Source: `SectionOcclusionGraph.runUpdates` L255-358.
   - It is active in every release build. `smartCull` defaults to true (`Minecraft` L364). It can only
     be toggled through a debug hotkey that is gated by `SharedConstants.DEBUG_HOTKEYS`
     (`KeyboardHandler` L95-99, L180).
   - It is forced off for a spectator inside a solid block (`Camera` L121-124).
2. **Frustum test of whole 16³ section boxes.**
   - The test uses a frustum whose apex is pushed **12–20 blocks behind the camera**
     (`Frustum.offsetToFullyIncludeCameraCube` L44-69).
   - The visible list is rebuilt only when the camera rotates past a 2° step, or when the graph
     changes (`LevelExtractor` L140-149).
3. **Back faces.** These are culled only by the GPU, after vertex shading (`RenderPipeline` L417).
4. **Entities.** Distance and frustum only; no occlusion.

**The benchmark draws about 4 quads for every 3 that could be seen, and about 2.3 for every 1 that is
front-facing and visible.** These figures come from a model of vanilla culling on the actual bench save
(§1). The model is validated two ways:
- it fits the measured per-second FPS with r = 0.95;
- it falls inside the log-derived draw counts.

**Section counts, mean over the 360° spin:**

| Stage | Sections | Terrain quads |
|---|---|---|
| View area at RD 12: 533 columns × 23 section layers | 12,259 | — |
| Non-empty / with geometry | 5,122 / 4,272 | 2.18 M |
| Reachable by the cave-culling graph (in the octree) | 1,449 | 1.18 M |
| **Drawn by vanilla** (graph ∩ offset frustum) | **505** (454–563; 481 with geometry), ~850 section-layer draws | **414 k** (207–578 k) = 1.65 M vertices, 46 MB vertex fetch |
| With any geometry actually visible (ray-cast truth) | 275 | 303 k in those sections |
| Front-facing buckets of the visible sections, all layers bucketed (lower bound) | 275 | 180 k |

**Avoidable work in the benchmark view, as quads per frame:**

| Waste | Quads/frame | Share of terrain drawn |
|---|---|---|
| Back-facing facing buckets, solid + cutout only | −158 k | −38 % |
| Offset-frustum margin and 2° staleness (exact per-frame frustum instead) | −39 k | −9 % |
| Sections fully hidden behind opaque terrain (ideal occlusion) | −110 k | −27 % (only −10 % more once buckets and the exact frustum are in) |
| Clouds outside the frustum (not terrain) | −26.6 k of 36.9 k cloud quads | 72 % of cloud vertices |

**Ranked proposals.** All are pixel-identical by construction (§4). GPU savings use the fitted
**0.25–0.32 ms per 100 k quads**, derived in §3.3.

| # | Proposal | Bench quads/frame | Est. GPU saving | CPU | Effort |
|---|---|---|---|---|---|
| P1 | Facing buckets for solid + cutout: section-level back-face culling. Design lives in the sibling docs. | 414 k → 256 k | **−0.40 to −0.51 ms** | ± (more draw records) | Medium |
| P2 | Exact per-frame frustum on the draw list, from the real (bobbed) projection, with tight mesh bounds | 256 k → 232 k with P1 (414 → 375 k alone) | −0.06 to −0.08 ms with P1 (−0.10 to −0.12 alone) | −10 % sections in the draw-list build | Low |
| P2b | Keep the real visibility set for sections that compile to no geometry (vanilla swaps in `EMPTY`, which sees through every face) | 414 k → 405 k; 505 → 456 sections | −0.02 ms | −10 % sections drawn | Low |
| P3 | Frustum culling of cloud faces at mesh-build time, order-preserving, with a widened frustum | clouds 36.9 k → ~10.3 k (+ margin) | −0.06 to −0.08 ms | small (rebuild ~4×/s while spinning) | Low–Medium |
| P4 | Entity occlusion through cave-graph reachability | ~0 in the bench (3 entities drawn); ground level: 7 of 15 entities hidden | ~0 GPU | CPU win in mob-heavy scenes | Low |
| P5 | Occlusion culling beyond the graph: GPU depth pyramid (HiZ) or coarse CPU occluders | 232 k → 205 k (HiZ); ground 218 k → 174 k | −0.07 ms (bench), −0.11 ms (ground) | High | High; **on a tile-based GPU (TBDR) at 8 MP a two-pass HiZ costs more bandwidth than it saves** (§4.5). Defer. |
| P6–P8 | Weather columns, block entities, particles | rain only / tiny | ≤ 0.01 ms | small | Low |

**Combined.**
- P1 + P2 + P3 take the terrain + cloud vertex load from about **451 k to about 245 k quads per
  frame (−46 %)**.
- That is about **−0.55 ms (0.52–0.66 ms)** of today's ~2.9 ms GPU frame.
- It is roughly a third of the gap to the 1.0–1.4 ms target.
- Everything else in the frame (fragment bandwidth, passes) is outside this doc.

**Not worth doing for pixel-identical output:**

| Idea | Why not |
|---|---|
| Fog culling | ~36 k quads lie in sections that are fully fogged. They still draw the fog-coloured silhouette and write depth. |
| Far-plane culling | The cull far plane is 2048 blocks; the view area ends at ~208 blocks. |
| Frustum-restricted BFS | −1.2 % |
| "Skip underground sections" heuristics | Not conservative: ravines and cave mouths are visible from above. |
| `cutoutLeaves = false` | Changes the look. |

---

## 1. Method: a model of vanilla culling on the bench world

**Why a model.** No per-frame section or quad counters exist in the measured runs. The only
in-game number is the F3 "C:" line (`LevelExtractor.sectionStatistics` L630-648). So I rebuilt the
culling on the saved benchmark world. The tools are in this session's scratchpad, not in the repo:
`…/scratchpad/cull/{dump.py, classify.py, sim.c, clouds.py, ents.py}`.

**Inputs**

- **World:** `run/vanilla/saves/bench/dimensions/minecraft/overworld/region/*.mca`. All 729 chunks
  within 13 chunks of the camera are `minecraft:full`. The biome is snowy plains / taiga, with a frozen
  river and mountains to the south (`run/shots-vulkan/screenshots/day.png`, `day_north.png`).
- **Camera:**
  - position (−0.5, 104 + 1.62, 1.5), the bench anchor (`run/vanilla/saves/bench/bench-anchor.txt`),
    plus standing eye height;
  - pitch 12° (`bench/src/bench/Bench.java` L240);
  - yaw swept 0–359° (the bench spins 360° in 10 s, `Bench.aim` L232-242).
- **FOV:** 77° vertical. Flying multiplies the FOV by 1.1 (`AbstractClientPlayer.getFieldOfViewModifier`
  L94-96). The cull projection uses `max(fov, option fov)` (`Camera` L180-190).
- **Aspect:** 3600/2260.

**Faithful ports of the vanilla code**

| Mechanism | Source |
|---|---|
| `SectionOcclusionGraph.runUpdates`: smart-cull direction rule, VisibilitySet test, 60-block ray-march, BFS order | L255-358 |
| Neighbour lookup (`getRelativeFrom`) | L374-385 |
| `ChunkTrackingView.isWithinDistance` | L71-79 |
| `RotatingSectionStorage.containsSection` | L111-119 |
| `VisGraph.resolve`, including "fewer than 256 opaque blocks ⇒ all faces see each other" | L56-71 |
| Opaque = `isSolidRender` | `SectionCompiler` L78-80 |
| A section that compiles to no layers and no block entities becomes `CompiledSectionMesh.EMPTY` | `SectionRenderDispatcher` L434-436; `CompiledSectionMesh` L20-25 |
| JOML-style frustum planes, `intersectAab` semantics, `offsetToFullyIncludeCameraCube` | `Frustum` L44-100 |

**Approximations**
- Quads per block:
  - full cubes cull against opaque neighbours;
  - grass sides carry 2 quads (base + overlay);
  - a snow layer culls the face below it;
  - leaves keep leaf-to-leaf faces, because `cutoutLeaves = true` (`LeavesBlock` L45-46);
  - water has top + back-top + sides;
  - cross plants count as 4 quads;
  - a few rare models use fixed counts.
- Entity sizes come from vanilla hitboxes.

**Ground truth for "visible"**
- One ray per 5×5 pixels (720×452 grid) at every 10° of yaw.
- A ray stops at the first opaque block. It marks every non-air block it passes (leaves, water, plants,
  ice) as visible, so the truth set is conservative.
- At 1440×904 rays the results move by less than 1.5 %.

**Validation**
1. **Draw counts.** The model gives 505 sections (max 563) and 850 section-layer draws (728–1,014).
   The run logs show the instanced ring grew to 1024, which means 513–1024 sections at peak (log 4).
   The command ring grew to 1024 or 2048 depending on the run (`run/vanilla/logs/2026-10-04-{2,4,5}.log.gz`).
   The independent Java model in `draw-submission-and-mdi.md` §2 gets 487 sections with geometry /
   1.65 M vertices. Mine gets 481 / 1.65 M.
2. **FPS fit.** Model quads per 36° yaw step against the measured per-second FPS of the two native
   Vulkan runs (`results/raw/vanilla-vulkan-native{,-r1}.json`) gives
   `frame_ms ≈ 1.5–1.7 + 0.30–0.36 × (quads / 100 k)` with r = 0.95 for both runs (§3.3).

---

## 2. What vanilla culls today

### 2.1 Order of operations

| Step | Where | Frequency |
|---|---|---|
| Cull frustum. `fovForCulling = max(fov, option fov)`; near 0.05, far = `depthFar`. No view bobbing. | `Camera.update` L103; `createProjectionMatrixForCulling` L180-190; `prepareCullFrustum` L196-208 | every frame |
| `depthFar = max(RD×16×4, cloudRange×16)` = max(768, **2048**) = 2048 blocks | `Camera` L91-92; `cloudRange` default 128 (`Options` L192-201) | every frame |
| Graph invalidated when the camera crosses an 8-block cell, FOV changes, or smart-cull toggles | `SectionOcclusionGraph.invalidateIfNeeded` L96-110 | per cell |
| **Full graph rebuild**, asynchronous on `Util.backgroundExecutor()`; swapped in atomically | L160-175 | per invalidation |
| Partial BFS on the render thread, from sections whose mesh changed or whose chunk loaded | L177-209, `schedulePropagationFrom` L133-145; called from `LevelRenderer` L313 | per frame, when anything is pending |
| `applyFrustum`: clear `visibleSections` and refill it by octree traversal | `LevelExtractor` L140-149, L503-512; `SectionOcclusionGraph.addSectionsInFrustum` L112-127 | **only** when the graph changed or `floor(xRot/2)` / `floor(yRot/2)` changed. In the bench: ~18×/s (36°/s ÷ 2°). |
| Draw list: every layer of every section in `visibleSections` | `LevelRenderer.extractSectionDrawGroups` L736-802 | every frame |

### 2.2 Cave culling: `SectionOcclusionGraph`

**BFS rules**
- The BFS starts at the camera section (L211-253) and expands over the six faces.
- `getRelativeFrom` rejects neighbours that are:
  - outside the view-distance circle;
  - more than RD sections above or below the camera (L374-385).
- **Smart-cull rule 1: no doubling back.** A node never expands opposite to any direction already on
  its path (`directions` bitmask, L290, L452-454).
- **Smart-cull rule 2: VisibilitySet.** Expansion through face *d* requires the section's
  `VisibilitySet` to connect some entry face to *d* (L291-305).
  - The set comes from `VisGraph`, a flood fill over non-`isSolidRender` blocks
    (`VisGraph.resolve` L56-71).
  - A section with fewer than 256 opaque blocks "sees" through every face (L58-59).
  - A fully opaque section sees through none (L60-61).
  - Empty (all-air) sections use `CompiledSectionMesh.EMPTY`, which sees everything (L20-25).
  - Uncompiled sections see nothing (L14-19).
  - **Quirk:** a section that compiles to no rendered layers and no block entities is stored as
    `EMPTY` (`SectionRenderDispatcher` L434-436). A fully solid stone section with no exposed face
    therefore "sees" through all six faces. The same section with one exposed face would block every
    path through it. In the model this lets the BFS reach 168 more sections (1,449 vs 1,281). It adds
    49 drawn sections and 8 k quads per frame (P2b).
- **Smart-cull rule 3: ray-march toward the camera** (L307-343), for sections more than 3 sections
  (≥ 60 blocks) away on any axis.
  - The march steps 28 blocks at a time toward the camera.
  - It rejects the neighbour if a step lands in a section with no graph node yet.
  - This is a heuristic, not a conservative visibility test.
- **The octree holds only non-empty sections.** Empty sections are traversed but never added
  (L276-282). The octree is what the frustum walk visits (L118-126).
- **The graph ignores the frustum.** It is built for all directions around the camera, which is why
  it only needs rebuilding when the camera moves.

**Effectiveness in the benchmark (model)**
- 1,449 of 5,122 non-empty sections are reachable: −72 % of sections, −46 % of quads in the view area
  (1.18 M of 2.18 M).
- Inside the frustum, smart cull reduces drawn quads from 692 k to 414 k (**−40 %**).
- It never removed a truly visible section in 72 sampled views (two camera heights): 0 sections were
  visible but culled.
- What it cannot remove: cave systems reachable through any opening, plus water bodies and leaves.
  Those are non-opaque to the flood fill (`SectionCompiler` L78-80 marks only `isSolidRender`).
- Result: **110 k quads (27 %) are drawn in sections with no visible pixel.**
  - 88 k solid, 13 k cutout, 9 k translucent;
  - 65 k of them below y = 64.

**Restricting the BFS to the frustum** (Sodium-style) would remove only 1.2 % in the model
(409 k vs 414 k). The waste comes from cave connectivity *inside* the frustum, not from paths that
leave the frustum and come back.

### 2.3 Section frustum culling

**What is tested**
- Each octree leaf tests the section's **full 16³ AABB** (`SectionRenderDispatcher` L233; `Octree.Leaf`
  L246-262).
- Branches that are fully inside skip their children's tests (`Octree.Branch.visitNodes` L188-218).
- `Frustum.isVisible` accepts both INSIDE and INTERSECT (L83-86).
- The test runs in camera-relative floats (L92-100).

**The pushed-back apex**
- The frustum used is `offsetFrustum` = a copy moved back by `offsetToFullyIncludeCameraCube(8)`
  (`SectionOcclusionGraph` L360-362).
- That function steps the apex back along the view vector **4 blocks at a time** until the camera's
  8-block grid cube is fully inside (`Frustum` L44-69).
- At the bench pose the apex ends **12–20 blocks back** (mean 14.8, model).
- This widens the frustum by a constant 10–25 blocks on every side. It is the safety margin for:
  - the 2° rotation steps;
  - up to 8 blocks of camera translation between graph rebuilds;
  - view bobbing and nausea, which the cull frustum does not include (render-only bob in
    `GameRenderer.renderLevel` L642-662).

**Far plane.** It is at 2048 blocks (§2.1). The view area ends at about 13 chunks (≈ 208 blocks) because of
the "−1" buffer in `ChunkTrackingView.isWithinDistance` L74-79. **The far plane therefore never culls
terrain.**

**Cost of the margin.** It accounts for 39 k quads per frame (−9 %) relative to an exact per-frame
frustum on tight mesh bounds. The model gives 378 k for exact 16³ boxes and 375 k for tight boxes.

### 2.4 What is drawn per section

**Every non-empty layer, all quads, every frame** (`LevelRenderer` L744-800). One `IndexedDraw` is
emitted per section-layer (L799).

**Back faces are rejected only by the GPU.**
- Every terrain pipeline uses the default `cull = true`: `RenderPipeline` L417; the terrain snippets
  `RenderPipelines` L76-90 and the pipelines L346-402 never call `withCull(false)`.
- The sibling docs and this model agree that **~42–43 % of drawn terrain quads face away** from the
  camera at bucket granularity:
  - this model: 241 k of 414 k remain with all layers bucketed;
  - 256 k remain with solid + cutout only.
- **On a TBDR GPU this is pure vertex-stage waste.** The vertices are fetched (28 B each) and shaded,
  then the primitives are culled before binning.

### 2.5 Entities

**Order of tests** (`LevelExtractor.extractVisibleEntities` L261-289 → `isEntityVisible` L291-309):

1. **Distance**, `Entity.shouldRenderAtSqrDistance` L2118-2126:
   - limit = mean hitbox edge × 64 × `viewScale`;
   - `viewScale = clamp(RD/8, 1, 2.5) × entityDistanceScaling` = **1.5 at RD 12** (`LevelExtractor`
     L267-269);
   - examples: zombie 96 blocks, enderman 131, item 24.
2. **Frustum**: the exact cull frustum (not the offset one), AABB inflated by 0.5. A leashed entity
   also passes if its holder or the leash span is visible (`EntityRenderer.shouldRender` L61-88).
   - Renderers that return `affectedByCulling() == false` skip this test:
     - `EnderDragonRenderer` L219-221;
     - `DisplayRenderer` L45-47 (per entity);
     - `LightningBoltRenderer`;
     - `FishingHookRenderer`.
3. **Section loaded**: the section must be compiled and faded in to ≥ 0.3
   (`LevelRenderer.isSectionCompiledAndVisible` L1283-1292).
   - Exception: entities outside the build height pass anyway (`LevelExtractor` L305).

**There is no occlusion test.**
- A mob in a cave inside the frustum is extracted, animated and CPU-skinned every frame, then drawn
  (`docs/renderpearl/07` §5).

**Bench numbers.** Using the saved entity set (288 entities, 135 within 128 blocks, many underground
zombies, creepers and endermen):
- ~3.1 entities are rendered per frame;
- 1.4 of them stand in sections that no ray reaches.

**At ground level** (y 65.6): 15.4 rendered, 7.3 in hidden sections, 6.1 in sections the cave graph
never reaches.

### 2.6 Block entities

- Block entities are taken only from sections in `visibleSections`, so they inherit the graph test,
  the offset frustum and the 2° staleness (`LevelExtractor.extractVisibleBlockEntities` L322-351).
- Each renderer then applies its own distance check: 64 blocks by default
  (`BlockEntityRenderer.shouldRender` L35-37; `BlockEntityRenderDispatcher.tryExtractRenderState`
  L81-87).
- There is no per-block-entity frustum test, and renderers expose no render bounds.
- "Render off-screen" block entities (beacon beams and similar) bypass culling entirely
  (`LevelExtractor` L353-367; `BlockEntityRenderDispatcher` L81-83).

### 2.7 Particles

- `ParticleEngine.extract` gets `new Frustum(cullFrustum).offset(-3.0F)`, which moves the apex
  3 blocks back (`LevelExtractor` L218; `Frustum.offset` L37-42).
- `QuadParticleGroup` keeps a particle when its centre passes `pointInFrustum` (L21-37;
  `Frustum` L102-104).
- There is no occlusion test, and no distance test beyond each particle's own lifetime and spawn
  rules.

### 2.8 Fog

**Render-distance fog**
- The fog is cylindrical: `max(length(xz), |y|)` (`fog.glsl` L37-41).
- It runs linearly from `RD×16 − clamp(RD×16/10, 4, 64)` to `RD×16`, i.e. from **172.8 to
  192 blocks** at RD 12 (`FogRenderer.setupFog` L193-195; `fog.glsl` L14-26).
- Terrain applies it per fragment from per-vertex distances (`terrain.vsh` L36-37; `terrain.fsh` L32).

**Fully fogged geometry is still drawn.**
- Geometry beyond 192 blocks writes the exact `FogColor` and its depth.
- The sky behind it is a gradient, not `FogColor`. Clouds behind it are depth-tested against it.
- **Culling it would change pixels.** It amounts to ~36 k quads per frame in the bench (8.9 %).

**Environmental fog** (`AtmosphericFogEnvironment`) does not bound anything in clear weather.

### 2.9 Clouds and weather

**Clouds**
- The cloud mesh is a disc of radius `ceil(cloudRange×16/12)` = **171 cells** (2,052 blocks) around
  the camera (`CloudRenderer.prepare` L136-137; `buildMesh` L245-277).
- Cells are emitted in ring order, nearest first.
- It is rebuilt only when the cell, the camera's relation to the cloud layer, or the cloud status
  changes (L170-186).
- **It is drawn whole every frame.** One `drawIndexed(6 × quadCount)` (L242), with no frustum
  culling at all.
- Cloud size, computed from `clouds.png` (27.6 % coverage) for the bench camera below the clouds:
  - **36,881 quads (147.5 k vertices) per frame**;
  - only **28 % (10.3 k)** intersect the view frustum on average.
- The pipeline blends (TRANSLUCENT) **and writes depth** (`RenderPipelines` L214-221).
  - Because of the depth writes, nearest-first order matters wherever cloud faces overlap.
  - Any culling must therefore **remove** faces without reordering the rest.

**Weather**
- Rain and snow columns cover a (2r+1)² square around the camera, with `weatherRadius` default 10
  (`Options` L204-211; `WeatherEffectRenderer.extractRenderState` L68-82).
- There is no frustum test.
- In the bench, weather is clear.

---

## 3. The benchmark view in numbers

### 3.1 Funnel (model, mean over 360°)

See the table in §0.

**Per-layer split of the 414 k drawn quads:** solid 235 k, cutout 147 k, translucent 31 k.

**Cutout is large because of the spruce forests.**
- Leaves keep all their faces.
- Grass and fern crosses add 4 quads per plant.

**Draw records**
- Section-layer draws: ~850 per frame today.
- With facing buckets for solid + cutout and a good bucket order, the model gives ~1,076 records.
- `draw-submission-and-mdi.md` §0 gets 908 → 1,023 with gap bridging.

### 3.2 View direction matters: model against measurement

| Yaw (°) | Model sections drawn | Model quads drawn | Vulkan FPS (6 turns) | Vulkan frame ms | GL FPS |
|---|---|---|---|---|---|
| 0–36 | 520 | 364 k | 368 | 2.72 | 200 |
| 36–72 | 535 | 453 k | 334 | 3.00 | 188 |
| 72–108 | 553 | 551 k | 293 | 3.41 | 175 |
| 108–144 | 518 | 563 k | 281 | 3.56 | 172 |
| 144–180 | 478 | 517 k | 287 | 3.48 | 176 |
| 180–216 | 487 | 483 k | 315 | 3.17 | 194 |
| 216–252 | 494 | 387 k | 358 | 2.80 | 196 |
| 252–288 | 517 | 321 k | 385 | 2.60 | 213 |
| 288–324 | 485 | 246 k | 395 | 2.53 | 232 |
| 324–360 | 466 | 250 k | 393 | 2.54 | 226 |

Sources:
- FPS: `results/raw/vanilla-vulkan-native{,-r1}.json` and `vanilla-opengl-native.json`,
  `perSecondFps`.
- Second *k* of the measurement covers yaw 36k…36k+36, because the bench aim restarts at yaw 0 when
  measurement starts (`Bench.java` L162, L184, L232-242).

### 3.3 Converting quads into GPU milliseconds

**Fit.** A least-squares fit over the 30 seconds of each Vulkan run gives
**frame ms = 1.5–1.7 + 0.30–0.36 × (quads / 100 k)**, with r = 0.95 for both runs.
- On GL the slope is 0.41–0.44.
- Section count fits worse than quad count: 0–36° and 252–288° draw about the same number of sections
  but very different quad counts and FPS.
- The windowed runs are not GPU-bound, and their 16:9 aspect does not match the model, so they show
  no fit.

**The fitted slope is an upper bound.** View directions with more terrain also have more fragment
work.

**A second estimate from the profile.** The vertex stage takes ~1.1 ms per frame
(`results/GPU-PROFILE.md`). That covers ~414 k terrain quads plus ~37 k cloud quads, plus sky,
entities and GUI. This gives **≈ 0.25 ms per 100 k quads**.

**The doc uses 0.25–0.32 ms per 100 k quads** for quads that produce no fragments: back-facing,
off-screen or occluded.

### 3.4 Ground level: occlusion matters more there

The same model at the spawn column, eye height 65.6, pitch 0, flying FOV:

| | Bench (40 above ground) | Ground level |
|---|---|---|
| Vanilla drawn quads | 414 k | 363 k |
| Exact frustum (tight / 16³ boxes) | 375–378 k | 329–330 k |
| P1 (buckets, solid + cutout) | 256 k | 240 k |
| P1 + P2 | 232 k | 218 k |
| P1 + P2 + HiZ (8×8 tiles, same-frame depth) | 205 k | 174 k |
| P1 + P2 + coarse CPU occluders (fully opaque 16×16 layers) | 219 k | 186 k |
| P1 + P2 + ideal occlusion | 190 k | 158 k |
| Ideal occlusion alone | 303 k (−27 %) | 225 k (−38 %) |

---

## 4. Proposals (ranked by GPU + CPU savings, all pixel-identical)

**The guarantee.** Each proposal only removes primitives that produce no fragments in vanilla:
- back-facing;
- entirely outside the view volume;
- or entirely behind opaque depth.

Every remaining primitive is drawn in the same relative order. The one exception is the
bucket-internal reorder of P1; its impact is analysed there.

### P1. Section-level back-face culling with facing buckets (solid + cutout)

**Design.** The full design is in `docs/stage2/meshing-and-vertex-format.md` §4 (classification
in `BufferBuilder`, stable bucket sort, per-bucket plane extents) and
`docs/stage2/draw-submission-and-mdi.md` §3 (records, bucket order, gap bridging). This section adds
only the culling-specific points.

**Gain**
- Model: 414 k → 256 k quads (−38 %) with translucent left unbucketed.
- With translucent head/tail as well (`draw-submission-and-mdi.md` §0.4): 241 k (−42 %).
- That is **−0.40 to −0.51 ms GPU** in the bench and −0.31 to −0.39 ms at ground level.
- Vertex fetch drops from 46 MB to 29 MB per frame.

**Where**
- **`SectionCompiler.compile` L64-71, L116-126.** Classify each emitted quad's facing from its
  geometric normal, not from `BakedQuad.direction` (`BakedQuad` L18-29), which is a cull key. Then
  stable-partition each solid/cutout mesh into 7 contiguous ranges.
- **`CompiledSectionMesh` (L35-46) and `SectionMesh.SectionDraw` (L45-46).** Store per-range quad
  offsets and per-bucket extreme planes.
- **`LevelRenderer.extractSectionDrawGroups` L744-800.** For each solid/cutout section-layer:
  - compute the visible-bucket mask from the camera position;
  - emit one `IndexedDraw` per merged run;
  - set `baseVertex = sliceOffset/28 + 4×firstQuad` and `indexCount = 6×quads`.
- Keep `firstIndex = 0`: the shared sequential quad index buffer works at any quad-aligned base.

**Correctness**
- **The bucket test must be strict and conservative.** A bucket is skipped only if the eye is behind
  **every** plane in it, by a margin.
  - The sibling docs use 0.25 block.
  - 1e-3 block is already ≫ float error at ≤ 400-block camera-relative magnitudes (`terrain.vsh` L33).
- **Edge-on quads produce no fragments in vanilla either.**
- **Reordering inside a section-layer cannot change pixels except on exact depth ties** between
  non-coplanar quads from different buckets.
  - The stable sort keeps coplanar same-facing quads (e.g. grass base + overlay) in order.
  - Faces of different directions on unit blocks share whole edges, and the fill rule gives each
    sample to exactly one triangle.
  - Ties remain possible only on T-junction lines (snow layers, slabs, stairs).
  - Vanilla already reorders sections whenever the camera section changes, because octree order is
    camera-relative (`Octree.Branch` L93-105, L131-148). This adds nothing qualitatively new.
  - Verify with the scene diff harness (§5).
- **Translucent is excluded** from reordering: the sorted index buffer defines its order.

**CPU**
- Draw records rise by ~25 % (850 → ~1,076).
- Pair this with the persistent draw list in `draw-submission-and-mdi.md` §4, so the per-frame build
  stays cheap.

### P2. Exact per-frame frustum on the draw list, with tight mesh bounds

**What.** Keep `visibleSections` exactly as vanilla builds it: offset frustum, 2° steps, octree. It
remains the conservative candidate list for the dirty-section scan (`LevelExtractor` L166-186), block
entities and translucent resorting (`LevelRenderer` L1217-1239). In the **draw list only**, drop any
section-layer whose tight geometry box misses the exact frustum of *this* frame.

**Where**
- **Frustum.** In `GameRenderer.renderLevel`, after the bobbed and nausea-skewed `projectionMatrix` is
  final (L642-662), build `new Frustum(cameraState.viewRotationMatrix, projectionMatrix)` and call
  `prepare(cam)`. Pass it to `LevelRenderer.render` (L184-192; called at `GameRenderer` L672-674).
  - Plane extraction from P×V is exact for any linear projection, including bob translation and the
    nausea rotate/scale.
  - JOML's near plane at ≈ n/2 for a zero-to-one depth range is conservative.
- **Tight bounds.** In `SectionCompiler.compile`, keep the min/max of the emitted vertex positions per
  layer (or per bucket). Store them on `CompiledSectionMesh`.
  - This must use real vertex positions, not block positions. Models may extend up to one block
    outside their cell, and plants carry random XZ offsets.
- **Test.** At the top of the per-section loop in `extractSectionDrawGroups` (L739-746), before the
  two `HashMap` lookups in `getRenderSectionSlice`, test
  `exact.isVisible(section bounds ∪ layer bounds, inflated 1/64 block)`. On a miss, skip the
  section-layer.

**Gain**
- Alone: 414 k → 375 k quads (−9 %), −0.10 to −0.12 ms.
- With P1: 256 k → 232 k, a further −0.06 to −0.08 ms.
- About 10 % fewer sections go through the draw-list build: 505 → 453.
- The 16³-box version gets 378 k. Tight bounds add only 0.5 %, and per-bucket boxes add almost
  nothing, so test one box per section-layer.

**Correctness**
- Only geometry wholly outside the frustum that renders this frame is removed. It would have been
  clipped anyway.
- Removal never reorders the survivors.
- **The frustum must come from the bobbed render projection, not `Camera.cullFrustum`.** The cull
  frustum has no bob (`Camera` L196-208). With bob on, an exact test built from the unbobbed frustum
  would cut a sliver at the screen edge.
- Inflate the boxes slightly to cover the float conversion in `Frustum.cubeInFrustum` (L93-98).

### P2b. Keep the real visibility set for sections that compile to no geometry

**What.** In `SectionRenderDispatcher.RenderSection.CompileTask` (L434-436), a section with no rendered
layers and no block entities is stored as `CompiledSectionMesh.EMPTY`. `EMPTY.facesCanSeeEachother`
returns true for every pair (`CompiledSectionMesh` L20-25). The fix stores a geometry-less mesh object
that still carries `results.visibilitySet` (`SectionCompiler` L129). It needs:
- no draws and no buffers;
- `hasRenderableLayers() == false`;
- only `facesCanSeeEachother` delegating to the visibility set.

Air-only sections never compile. They stay in `emptySections` and keep `EMPTY` through
`SectionOcclusionGraph` L280-282.

**Gain (model)**
- The graph shrinks from 1,449 to 1,281 sections.
- Drawn sections drop from 505 to 456, and quads from 414 k to 405 k. That is −0.02 ms GPU.
- About 10 % fewer sections go through the dirty-section scan, the draw-list build and the
  block-entity loop.
- At ground level: 408 → 372 sections, 363 k → 358 k quads.

**Correctness**
- In practice a geometry-less, non-air section is fully opaque. Any air touching an opaque block
  produces a face. A fully opaque section genuinely blocks every line of sight through it.
- The model found no truly visible section culled with the fix (72 views).
- Strictly, smart-cull's direction rule could make such a solid section the only allowed BFS path to
  a visible section. The fix would then cull that section where vanilla, by luck, draws it.
- Treat P2b like P4: behind a toggle, verified by the scene diff.

### P3. Frustum culling of cloud faces, order-preserving

**What.** Filter cloud faces against a **widened** frustum while building the cloud mesh, and add a
rotation trigger for rebuilds. Faces that never reach the screen are dropped. The rest keep their
nearest-first ring order, so blending and depth-write results are unchanged.

**Where: `CloudRenderer`**
1. **Keep the full ordered face list on the CPU.** In `buildMesh` (L245-277), keep the full ordered
   face list (cell x, z, direction/flags) in a CPU-side array whenever the existing rebuild triggers
   fire (L170-186).
2. **Write only the surviving faces.** In a filter pass, write to the mapped `utb`
   (`prepare` L182-185) only the faces whose cell box intersects the widened frustum. The cell box is
   `[x·12 − xInCell, relBottomY, z·12 − zInCell]` + (12, 4, 12), the same math as `clouds.vsh` L98-101.
3. **Widen the frustum by:**
   - **translation:** pushing the apex back until the camera's whole 12-block cloud cell (plus a
     vertical tolerance) is inside. This mirrors `offsetToFullyIncludeCameraCube`.
   - **rotation:** adding an angular margin θ ≈ 10° to the FOV in both axes. View bobbing rotates at
     most a few degrees (`GameRenderer.bobView` L361-…).
4. **Re-filter when:**
   - yaw or pitch drifts more than θ from the filter pose;
   - FOV or aspect changes;
   - camera height crosses the vertical tolerance.
5. **Disable filtering** (write all faces) while hurt-tilt or nausea/portal distortion is active
   (`GameRenderer.bobHurt` L339; L653-662). It is rare, and it keeps the margin argument simple.

**Gain**
- 36.9 k → ~10.3 k quads, plus the θ margin: about 13 k with θ = 10°.
- About −24 k quads (−95 k vertex invocations), i.e. **−0.06 to −0.08 ms**.
- This is independent of RD, because the cloud disc is 2 km in every direction.

**CPU**
- A linear pass over ~37 k faces in Java takes on the order of 0.1–0.2 ms.
- At the bench's 36°/s it runs ~3.6×/s, i.e. well under 1 ms per second of CPU. It can run on a
  worker thread into a CPU array, followed by a memcpy.

**Correctness**
- Only faces whose whole box lies outside a conservative superset of the view volume are removed.
- Survivors keep their exact relative order.
- Pixels are therefore identical, including the blend-over-depth-write behaviour.

### P4. Entity occlusion through cave-graph reachability

**What.** In `LevelExtractor.isEntityVisible` (L291-309), after the frustum test passes, also reject
the entity if **none** of the sections overlapped by its culling box has a node in the current graph.
- The culling box is `getBoundingBoxForCulling` + 0.5, as in `EntityRenderer` L70, expanded by the
  shadow radius.
- The node lookup is `SectionOcclusionGraph.currentGraph.storage.sectionToNodeMap`, via a new
  `isReachable(section)` (L484-498).
- Use the **node map**, not `visibleSections`: empty air sections have nodes but are not in the octree
  (L276-282).

**Never cull these:**
- the camera entity and its vehicle or passengers (`LevelExtractor` L275-276, L303);
- entities whose renderer does not take part in frustum culling (`affectedByCulling() == false`);
- glowing or outlined entities. Outlines show through walls via the `entity_outline` chain
  (`LevelRenderer` L273-278; `LevelExtractor` L237).
- entities whose name tag renders see-through, i.e. `!isDiscrete`. These go to the see-through phase
  (`EntityRenderer.submitNameDisplay` L124-138; `SubmitNodeCollection` L121-143).
- leashed entities and leash holders (`EntityRenderer` L79-85).

**Gain**
- Bench: about zero, since ~3 entities are drawn.
- Ground level: 6.1 of 15.4 drawn entities are removed (40 %). The ray truth says 7.3 are hidden.
- Each removed entity saves extract, animation, CPU skinning and upload (07 §5), plus a few hundred
  vertices.
- Mostly a CPU win for farms, villages and caves.

**Correctness**
- In 72 sampled views the rule culled **0** entities that a ray could reach.
- The graph is only as conservative as vanilla's terrain culling. In the rare case where smart-cull's
  60-block heuristic hides a visible section, vanilla already shows a terrain hole there; P4 would
  also hide the entity standing in it.
- The graph swaps atomically, and extract reads whatever graph is current, consistent with terrain
  (`SectionOcclusionGraph` L171-173).
- Gate P4 behind a toggle until the scene diff confirms it.

### P5. Occlusion culling beyond the graph (HiZ or CPU occluders): defer

**Upper bound.**
- Section-level: 27 % of drawn quads in the bench, 38 % at ground level.
- Once P1 + P2 are in: 232 k → 190 k (bench) and 218 k → 158 k (ground).

**Realistic variants (model):**

| Variant | Bench | Ground | Notes |
|---|---|---|---|
| GPU HiZ: 8×8 max-depth tiles of the true same-frame depth; section-box test | −27 k (−0.07 ms) | −44 k (−0.11 ms) | Exactness needs **two-pass** occlusion (draw last frame's visible set, build HiZ, test the rest, draw the newly visible). A previous-frame-only HiZ pops on disocclusion and is not pixel-identical. |
| Coarse CPU occluders: fully opaque 16×16 block layers rasterised at 1/4 of the 720-wide test grid | −13 k | −32 k | 63 k such layers in the view area. Merging them into large occluders is a project of its own. |

**Why defer**
1. **API.** RenderPearl has no compute shaders (`ShaderType` L7-9: VERTEX and FRAGMENT only) and no
   occlusion queries (`GpuQuery` and `GpuQueryPool` expose values; `CommandEncoder` L107 only writes
   timestamps). GPU-driven culling would need a Metal-only extension. For example, a compute kernel
   could zero `instanceCount` in the MDI command ring before the main pass
   (`DynamicGpuData.IndexedDraw`; 07 §4.6).
2. **TBDR cost.** Two-pass HiZ at 3600×2260 splits the main pass.
   - It must store D32 (32.5 MB), build the pyramid (≈ 45 MB read + write) and reload colour + depth
     (65 MB).
   - That is ≈ 140 MB, or ~0.9 ms at M5 bandwidth, against 0.07–0.11 ms saved.
   - Only a separate **low-resolution occluder pre-pass** could pay off. It would rasterise a CPU
     occluder set into a ~450×280 depth target and build HiZ from that. It is complex.
3. **Order of work.** P1 removes most of what occlusion would: hidden cave geometry is largely
   cave-ceiling and back faces.

**Revisit** for ground-level scenes once P1–P3 land, using this model at other camera poses.

**Revisited (round C, `docs/metal/OCCLUSION.md`).** Measured on the M5 at 3600x2260:
- Storing depth is nearly free, thanks to lossless compression. Building a pyramid from it costs 0.15 ms.
- Occluded front-facing quads cost ~0.065 ms per 100 k when frames overlap.

Previous-frame visibility queries were built in the decoder (`MCMTL_OCCLUSION`, off by default). They save ≤ 0.01 ms
at the bench pose and 0.02–0.06 ms at ground level.

### P6–P8. Small, pixel-identical extras

- **P6, weather.** Drop rain and snow columns whose box misses the widened frustum in
  `WeatherEffectRenderer.extractRenderState` (L68-82). Keep the loop order.
  - Rain only. About 70 % of up to 441 columns lie behind the camera.
- **P7, block entities.** In the draw-list stage, add the P2 exact-frustum test per section, using the
  section box **inflated by 2 blocks**. Banners, beds, signs and bells extend outside their cell, and
  there is no render-bounds API.
  - Small gain. The offset frustum and the 2° staleness are the only slack (`LevelExtractor`
    L322-351).
- **P8, particles.** Leave as is. The test is already a cheap point test with 3 blocks of slack
  (L218). Occlusion would need per-particle size bounds, and the benefit is CPU-only.

### Rejected (not pixel-identical, or no gain)

| Idea | Reason |
|---|---|
| Fog culling of fully fogged sections (~36 k quads) | They draw `FogColor` silhouettes and depth over a sky gradient (`fog.glsl` L28-31; `FogRenderer` L193-195). |
| Pulling the cull far plane in | It is already beyond everything drawn (§2.3). |
| Frustum-restricted BFS | −1.2 % in the model. |
| "Skip underground sections when the camera is above ground" | Not conservative: ravines, cave mouths, water-filled caves. The graph already handles connectivity. |
| `cutoutLeaves = false`, lower `cloudRange`, FAST clouds | Change the image. Players can choose these as options. |

---

## 5. Validation plan

1. **Pixel diff.**
   - Run `bench` in `-Dbench.mode=scenes` before and after each proposal
     (`bench/src/bench/Scenes.java` L22, L57-80; output in `run/shots-*`).
   - Require 0 differing pixels for P2, P3 and P4.
   - For P1, count and inspect any differing pixels. They should be ties on T-junction lines only,
     if any.
2. **Counters.** Add debug counters, shown next to the F3 "C:" line (`LevelExtractor` L630-648), for:
   - sections drawn;
   - section-layer records;
   - terrain quads;
   - cloud quads.

   Compare them against the model (§3.1–3.2) at the bench pose. The model is a reference, not a
   spec.
3. **Kill switches.** Add runtime toggles for P1–P4 (and P2b), like the existing `smartCull` and
   `multiDrawIndirect` debug fields (`Minecraft` L362-364).
4. **Safety assertions** (debug builds only):
   - for P2, assert that every skipped section-layer's tight box misses the exact frustum by the
     margin;
   - for P1, on a sample of sections, assert by brute force that no quad in a skipped bucket is
     front-facing.
5. **GPU time.** Use a Metal System Trace with `tools/mst_gpu.py`, comparing per-frame vertex-stage
   time.
   - Expectation for P1 + P2 + P3: ~1.1 ms → ~0.6 ms.
   - Expectation for frame time: down about 0.5–0.6 ms, following the §3.3 slope.

---

## 6. Open questions

1. **Per-draw GPU cost on M5** for indirect-command-buffer (ICB) / MDI records. P1 adds ~230 records
   per frame. The sibling doc measures ~52 ns of CPU encode per record, but GPU front-end cost per
   draw has not been measured on Metal.
2. **Vertex-stage cost per quad.**
   - Is vertex time linear in terrain vertex count? (§3.3 assumes so.)
   - How much of the 1.1 ms is tiling/binning rather than shading? This needs a trace of a scene with
     known quad counts.
3. **The model's quad counts are approximate** (plants and rare models). A per-frame quad counter in
   a dev build (§5.2) would calibrate them.
4. **Entity counts** come from the saved world after the runs, not from a live frame. Live mob counts
   during the bench may differ.
5. **P5 at ground level.** Whether a cheap CPU occluder set (merged opaque slabs per section column)
   can approach the HiZ numbers is untested. It is worth a model run before any engine work.
