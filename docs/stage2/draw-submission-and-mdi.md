# Stage 2: terrain draw submission, MDI and per-direction face buckets

Scope: how vanilla 26.3 turns `visibleSections` into terrain draws, and how the per-direction face
buckets produced by the mesher become indirect draw records. Covers:

- `LevelRenderer.extractSectionDrawGroups` / `prepareChunkRendersIndirect` / `prepareChunkRenders`;
- `ChunkSectionsToRender`, the uber heaps (`UberGpuBuffer`) and `DynamicGpuData`;
- the `CHUNK_DATA_INSTANCED` stream and translucent ordering;
- the per-frame CPU cost of all of the above;
- what the Metal backend contract says about `drawIndexedIndirect`.

All source paths are relative to `src/vanilla`. `File Lx` or `File Lx-y` means lines of that file. Metal
documents are cited as `CONTRACT.md` (`docs/metal/CONTRACT.md`) and `spec` (`docs/renderpearl/00-metal-backend-spec.md`).

---

## 0. Summary

**Vertex savings**

1. **Per-direction face buckets with a camera-facing test remove about 43 % of terrain vertices in the
   benchmark view and 37–44 % at ground level.** A simulation of vanilla meshing, cave culling and
   frustum over the real benchmark world (§2) gives, per frame:
   - benchmark: 1.65 M → 0.94 M vertices;
   - spawn at ground level: 1.36 M → 0.77 M vertices.

   The skipped quads are exactly the ones the rasterizer already discards: every terrain pipeline has
   `cull = true` (`RenderPipeline` L417; `RenderPipelines` L76-90, L346-426). The output is therefore
   pixel-identical. Only vertex shading, vertex fetch and primitive assembly are saved.
2. **Expected GPU gain: about 0.35–0.45 ms per frame** of the measured ~1.1 ms vertex-stage time
   (`results/GPU-PROFILE.md`). Two assumptions:
   - terrain is about 85–90 % of vertex work (clouds are the next largest);
   - vertex time scales with vertex count.

   The same change cuts vertex fetch by about 20 MB per frame (0.71 M × 28 B).

**Draw records**

3. **Record count can stay at vanilla's level if the bucket layout is chosen per mesh.**
   - **Phase 1:** the fixed order `−Y, −Z, +X, +Y, UN, +Z, −X` (the same order as the meshing doc),
     plus bridging of gaps of ≤ 32 quads. Bridging costs 0.3–0.4 % extra vertices. Records:
     - benchmark: 908 → 1,023 (+13 %);
     - ground level: +13–51 %.
   - **Phase 2:** a camera-relative layout chosen per mesh at compile time (§3.3). It gives exactly
     vanilla's count while the camera has not moved since compile, and +1–24 % after the player moves.
   - A naive layout in `Direction` order would roughly double opaque records (+87–103 %).
   - At the measured ~52 ns of native encode per draw (spec L42, L831), even +250 records cost only
     ~13 µs of CPU per frame.

**Translucent**

4. **Translucent terrain needs a different mechanism.** Its sorted index buffer interleaves directions,
   so the design is:
   - partition the sorted index buffer into a *head* (quads that may face the camera) and a *tail*
     (quads that face away for the sort's `TranslucencyPointOfView`);
   - draw only the head while the camera's current point of view, with a margin, equals the one the
     buffer was built for.

   This keeps exactly one record per section, so back-to-front order is unchanged. It removes about
   43 % of translucent vertices.

**CPU side**

5. **Today's per-frame terrain list build is the render thread's largest allocator and ~29 % of its
   Java time.**
   - From `results/raw/prof-vulkan.jfr`, 25 s at 393 FPS:
     - 35 of 121 render-thread Java samples, about 36 µs per frame;
     - 53 % of render-thread allocation, about 46 KB per frame;
     - 12 samples are in `HashMap.getNode`, 11 of them through `UberGpuBuffer.getAllocation`.
   - The whole loop holds `copyLock`, which mesh workers also need.

   **Proposal (§4):**
   - store GPU draw descriptors on the mesh in the upload callbacks;
   - build records into a reused `int[]` without the lock;
   - make the instance stream persistent, indexed by `RenderSection.index`;
   - skip the rebuild entirely while nothing changed (static camera: about 98 % of benchmark frames).

   Estimated cost: about 10–20 µs per rebuild, about 2 µs on reuse, and ~0 allocation.

**Backend**

6. **The Metal backend needs no change.**
   - Records stay `{indexCount, instanceCount = 1, firstIndex, baseVertex, baseInstance}` with
     stride 20.
   - They are byte-identical to `MTLDrawIndexedPrimitivesIndirectArguments` (spec L260; `CONTRACT.md`
     L336; `native/include/mcmetal_stream.h` L73, L649-658).
   - Base-instance fetch for indexed indirect draws is already verified (spec L895).
   - The decoder's `DRAW_INDEXED_INDIRECT` is still a TODO: see
     `client/src/com/mojang/renderpearl/backend/metal/MetalRenderPass.java` L110-111 (repo root, not
     `src/vanilla`); `native/src/mcmetal_decoder.m` is a skeleton. There are four recommendations for
     it, all optional (§5).
   - The game should keep the command records in the `MAP_WRITE` ring. That keeps the decoder's
     "CPU-decode the Shared records" option valid (`CONTRACT.md` L336).

---

## 1. Terrain draw submission today

### 1.1 Path selection

| Gate | Source |
|---|---|
| `multiDrawIndirectAvailable = maxDrawIndirectDrawCount > 0 && nonZeroFirstInstance && !multiDrawIndirectHasKnownIssues` | `LevelRenderer` L178-181 |
| Per frame: `&& levelRenderState.shouldUseMultiDrawIndirectForTerrain` (F3 toggle, default true) | `LevelRenderer` L264; `LevelExtractor` L231; `Minecraft` L362; `KeyboardHandler` L101 |
| MDI → `prepareChunkRendersIndirect`; otherwise → `prepareChunkRenders` | `LevelRenderer` L265-270 |
| Frontend: `drawIndirect`, `multiDrawIndirect` when n > 1, INDIRECT usage, length ≥ 20·n, offset % 4 | `FrontendRenderPass` L302-329 |

The Metal backend claims all MDI features (spec L41, L724-737), so the MDI path is the one that matters.
The list is built in `LevelRenderer.render` **before** the frame graph executes (L263-270). That is
outside any render pass, so `writeToBuffer` is legal there.

### 1.2 `extractSectionDrawGroups` (`LevelRenderer` L722-809)

The method takes `copyLock` for the whole loop (L730, L803-805) and walks every entry of
`visibleSections`. That list is roughly near-to-far: it comes from octree traversal and is filled only
by `applyFrustum` (`LevelExtractor` L140-149).

For each section, and for each of the 3 layers (`ChunkSectionLayer.values()` clones an array **per
section**, L744):

1. `sectionMesh.getSectionDraw(layer)` (EnumMap) and
   `sectionRenderDispatcher.getRenderSectionSlice(mesh, layer)` (L745-746).
   - The slice lookup is an EnumMap lookup plus **two `HashMap.get`** on
     `UberGpuBuffer.allocationMap` (vertex and index).
   - It also allocates a `RenderSectionBufferSlice` record (`SectionRenderDispatcher` L111-130,
     L573; `UberGpuBuffer` L31, L136-138).
2. On the section's first layer with geometry, append `new ChunkSectionInfo(originX, originY, originZ,
   visibility)` (L748-755). Its list index becomes the draw's `baseInstance`, so instance data is
   *compacted per frame*.
3. Group key `h = 31·173 + vertexHeap.hashCode()`; for translucent also
   `h = 31·h + indexHeap.hashCode()` and then `h = 31·h + indexType.hashCode()` (L757-777).
   - `firstIndex = indexOffset / indexSize` for translucent; 0 for solid/cutout, which use the shared
     quad index buffer.
   - `baseVertex = vertexOffset / 28` (L776, L779).
4. Grouping (L780-797):
   - Translucent with classic transparency joins the *previous* group only if the key equals the
     previous translucent key (L781-786).
   - Everything else uses a fresh `Int2ObjectOpenHashMap` keyed by the hash (L728, L788).
   - A new group allocates `ChunkDrawGroup(vertexHeap.slice(), indexHeap?.slice(), type, new
     ArrayList)` (L792-796; record at L1366).
5. `drawGroup.draws.add(new IndexedDraw(indexCount, 1, firstIndex, baseVertex, sectionInfoIndex))`
   (L799).

**Quirk.** The group key is a 32-bit hash of identity hash codes, used as the identity itself (L788).
If two heaps collide, their draws merge under the first heap's vertex buffer and render garbage. The
chance is negligible with 3–6 heaps, but the persistent design (§4) keys groups by a heap id instead.

### 1.3 MDI path: `prepareChunkRendersIndirect` (`LevelRenderer` L899-964) → `ChunkSectionsToRender.DrawIndirect`

**Building the buffers**

- Groups are flattened per layer. For translucent with classic order, both the group list and each
  group's draw list are reversed to get back-to-front (L917-933).
- `writeChunkSectionCommands(batchedDraws)` (L935) writes every `IndexedDraw` into one mapped ring
  (`DynamicGpuData` L113-119; `DynamicGpuDataStorageMapped` L152-195):
  - label "Chunk Sections Command Buffer", 20 B per record, usage `INDIRECT | MAP_WRITE`;
  - 3-deep `MappableRingBuffer`, rotated in `DynamicGpuData.reset` (L34-44; `MappableRingBuffer`
    L36-54);
  - each record costs a `ByteBuffer.position` and five `putInt` calls (`DynamicGpuData` L128-134).
- `writeChunkSectionsInstanced(sectionInfos)` (L957 → `DynamicGpuData` L100-111) feeds the instance
  stream:
  - one **non-ring** `VERTEX | COPY_DST` buffer with a CPU shadow;
  - every frame the whole compacted array goes through `writeToBuffer`
    (`DynamicGpuDataStorageNonMapped` L96-123);
  - each element allocates a `Std140Builder` (`DynamicGpuData` L124; `Std140Builder` L23-25).
- `writeTerrainTransform` (L956) and `requestIndexCount(largestIndexCount)` on the shared quad index
  buffer (L958-961; `RenderSystem` L421-469).

**Drawing** (`ChunkSectionsToRender.renderLayers` L82-103 + `DrawIndirect.render` L120-158), per layer:

- `setPipeline(*_MULTIDRAW)` (L129-131);
- `setVertexBuffer(1, chunkSectionInfos)` (L136);
- per group: `setVertexBuffer(0, heap)`, `setIndexBuffer(group's or shared quad buffer)`, then
  `drawIndexedIndirect(slice, n)`, chunked by `maxDrawIndirectDrawCount` (L139-157).

**Group count.** It is one per (layer, vertex heap), plus translucent splits whenever consecutive
sections live in different (vertex heap, index heap) pairs.

The simulation (§2) puts the resident vertex bytes of reachable sections at 59–71 MiB solid, 37–43 MiB
cutout and 8–10 MiB translucent, against 128 MiB heaps (`SectionRenderDispatcher` L65-75). So at
RD 12 there is **one heap per layer, i.e. 3 `drawIndexedIndirect` calls per frame**. Fragmentation or
RD ≥ 16 adds a few more.

**Per-instance stream** (`DefaultVertexFormat` L33-36, `RenderPipelines` L83-90, `terrain.vsh` L17-20,
L33, L45-47):

- `ChunkPosition` (`RGB32_SINT`, the absolute section origin) and `ChunkVisibility` (`R32F`, fade-in);
  16 B per instance, binding 1, step rate 1;
- the shader computes `Position + (ChunkPosition - CameraBlockPos) + CameraOffset`;
- `CameraBlockPos` and `CameraOffset` come from `cameraRenderState.pos` (`GlobalSettingsUniform`
  L29-35, `GameRenderer` L482-492).

### 1.4 Separate path (`prepareChunkRenders` L811-862 → `DrawSeparate` L161-191)

- One `RenderPass.Draw` plus a lambda per section-layer (L835-849).
- Section infos go into a padded UBO ring: "Chunk Sections UBO" (`DynamicGpuData` L87-98).
- Drawing goes through `FrontendRenderPass.drawMultipleIndexed` (L332-398):
  `setUniform("ChunkSection")`, `setVertexBuffer(slot, buffer.slice())`, then
  `backend.drawIndexed(n, 1, firstIndex, baseVertex, 0)` per record.

In practice only GL uses this path (or any backend with MDI toggled off with F3). Every record is a backend draw, so extra records cost real CPU there (§3.7).

### 1.5 Translucent ordering

- The order is per-section back-to-front: `visibleSections` reversed (L921-929). It is approximate,
  since octree order is only near-to-far.
- Within a section, the order comes from the per-section index buffer, sorted by quad centroid
  distance:
  - at compile time (`SectionCompiler` L116-122, `MeshData` L68-78, L91-116);
  - on resort (`ResortTransparencyTask`, `SectionRenderDispatcher` L486-537).
- Resorts are scheduled when the per-section `TranslucencyPointOfView` changes, or when the camera
  block changes for nearby or axis-aligned views (`LevelRenderer` L1217-1257).
- `TranslucencyPointOfView` is the camera's section offset relative to the section, clamped to −1..1
  per axis (`TranslucencyPointOfView` L13-31).
- A resort uploads a **new index allocation for the same mesh key**:
  - `UberGpuBuffer.uploadStagedAllocations` first frees the key's current allocation, then allocates
    again (L59-64, L79-105);
  - so a translucent mesh's `firstIndex` changes over its lifetime without a mesh swap.

### 1.6 Ownership, threads and locking

- `allocationMap` (`UberGpuBuffer` L31) is mutated:
  - by the render thread (uploads, L104);
  - by mesh workers through `releaseSectionMesh`. Example: a compile that yields no geometry swaps in
    `EMPTY` and frees the old mesh on the worker (`SectionRenderDispatcher` L433-446, L324-331).

  `copyLock` therefore guards it. Workers also hold `copyLock` while they memcpy mesh bytes into
  staging (`SectionRenderDispatcher` L368-401, `UberGpuBuffer` L41-55).
- So the render thread's 30–40 µs draw-list loop and workers' staging copies serialize. During chunk
  loading this shows up as render-thread waits and as worker spinning (`SectionRenderDispatcher`
  L451-471).
- TLSF allocation and copy recording happen **only on the render thread**, after the frame's draws
  were recorded (`LevelRenderer` L299-310). Upload callbacks also run there (`UberGpuBuffer` L105 →
  `SectionRenderDispatcher` L356-366, L339-354). §4 P1 relies on this.

### 1.7 Measured cost of the current build

From `results/raw/prof-vulkan.jfr`: Vulkan, RD 12, benchmark scene, 25 s, mean 392.8 FPS from the
`minecraft.ClientFps` events, so about 9,800 frames. Java samples every 10 ms.

| Item | Value |
|---|---|
| Render-thread Java samples in `prepareChunkRendersIndirect` (inclusive) | 35 of 121 (29 %) → **~36 µs/frame** |
| … of which `extractSectionDrawGroups` | 29 |
| `HashMap.getNode` | 12, of which 11 come through `getRenderSectionSlice` |
| Render-thread allocation (`jdk.ThreadAllocationStatistics`) | 849 MB / 24.9 s = 34 MB/s ≈ **87 KB/frame** |
| Share in the terrain draw-list code (sampled weight; the first sample is excluded because it carries the thread's whole pre-recording total of 2.9 GB) | **53 % ≈ 46 KB/frame**. Top sites: `extractSectionDrawGroups` 31.6 % (`IndexedDraw`, `ChunkSectionInfo`, `Object[]` growth), `getRenderSectionSlice` 13.4 % (`RenderSectionBufferSlice`), `ChunkSectionLayer.values` 6.1 % |

At 1000 FPS this is about 36 µs/frame (3.6 % of the frame) and about 46 MB/s of garbage. That is small
next to the GPU, but it is the single largest Java item on the render thread
(`07-game-render-path.md` §13-14).

---

## 2. How many vertices the buckets remove: simulation over the benchmark world

**Method.** A throwaway Java tool (in this session's scratchpad: `…/scratchpad/sim/{World,Sim}.java`,
not part of the repo) reads the benchmark save (`run/vanilla/saves/bench`, seed 4242, snowy taiga with
spruce forest, water and ice). It re-implements vanilla closely enough to count quads per section,
layer and direction:

- **Face culling.** Full opaque cubes cull neighbours; a snow layer culls the top of the block below.
- **Leaves.** With `cutoutLeaves = true` (the bench options) leaves render faces against leaves
  (`LeavesBlock` L45-46), in CUTOUT.
- **Grass.** Grass block sides carry the overlay quad, so 2 quads per side.
- **Snow layers.**
- **Water** (`FluidRenderer` L77-83, L193, L338-373; `FluidState` L76-88):
  - top face plus the backward top face (a −Y quad at the surface);
  - side faces with back faces unless the overlay case applies;
  - sloped tops counted as unassigned.
- **Plants and other models.** Cross models count as 4 unassigned quads; other rare models are
  approximated.
- **VisGraph** (`VisGraph` L56-71), including the vanilla quirk that a compiled section with no
  geometry becomes `EMPTY`, which "sees" through all faces (`SectionRenderDispatcher` L435-436,
  `CompiledSectionMesh` L20-25).
- **Occlusion BFS.** Smart-cull, the 60-block ray-march, the view-distance circle and the ±RD vertical
  limit (`SectionOcclusionGraph` L255-358, L364-385; `ChunkTrackingView` L67-79).
- **Frustum.** Aspect 3600×2260, offset to include the camera cube (`Frustum` L44-60).

Every scene averages 36 yaw steps (the benchmark spins 360°).

**Validation against the run logs.** The simulated benchmark draws 487 sections with geometry per
frame (max 572) and 908 section-layer draws (max 1,123). The log's ring growth implies peaks of
513–1,024 sections and 1,025–2,048 commands (`07-game-render-path.md` §0.3). Both peaks fall inside
those ranges.

**Facing test used** (§3.2): per-bucket plane extents, margin 0.25 block. Translucent uses the POV
head/tail rule (§3.4).

| Scene | Vanilla vertices/frame | With buckets | Removed | Solid / cutout / translucent kept |
|---|---|---|---|---|
| **Benchmark**: (−0.5, 105.6, 1.5), 40 above terrain, pitch 12°, flying FOV 77° | 1,646,200 | 937,044 | **43.1 %** | 61 % / 49 % / 57 % |
| Benchmark, FOV 70° | 1,544,880 | 879,397 | 43.1 % | 61 % / 49 % / 57 % |
| **Ground, spawn** (y 66.6, pitch 0, FOV 70) | 1,364,057 | 774,349 | **43.2 %** | 55 % / 59 % / 57 % |
| Ground B (120, 70.6, −80) | 1,164,885 | 729,246 | 37.4 % | 62 % / 64 % / 57 % |
| Ground C (−150, 65.6, 60), pitch 5 | 1,031,727 | 576,178 | 44.2 % | 55 % / 57 % / 55 % |
| Ground D, hilltop (60, 89.6, 140) | 721,588 | 415,564 | 42.4 % | 59 % / 55 % / 56 % |
| Ground, 48 blocks east of spawn (on a hill, y 81.6) | 1,306,449 | 740,671 | 43.3 % | 60 % / 51 % / 57 % |
| Ground, 21 blocks east of spawn | 1,222,217 | 721,127 | 41.0 % | — |
| Ground, 11 blocks diagonal from spawn | 1,334,939 | 787,135 | 41.0 % | — |

**Where the savings come from.** Benchmark, share of vanilla vertices / fraction of that bucket kept:

| Bucket | Share of vanilla vertices | Fraction kept |
|---|---|---|
| −Y | 15.0 % | 0 % |
| +Y | 25.0 % | 100 % |
| −Z | 14.9 % | 45 % |
| +Z | 14.2 % | 60 % |
| −X | 14.4 % | 40 % |
| +X | 14.5 % | 62 % |
| Unassigned | 2.0 % | 100 % |

- From 40 blocks up every −Y face is culled: leaf bottoms, water back faces, overhangs.
- Horizontal faces are culled roughly half the time. They survive for sections in the camera's own X or
  Z slab, where both signs face the camera.
- At ground level, ±Y each survive about 60 %, because sections above the eye show their −Y faces.

**AABB-only test vs per-bucket planes.** Testing against the section AABB with a 1-block model slack,
instead of per-bucket plane extents, keeps 57.9 % (benchmark) and 62.6 % (ground). That is about 6
points worse at ground level, because most visible geometry then sits in the camera's own Y slab. The
mesher should therefore export the plane extents (§3.1).

**Caveats**

- Block models are simplified. Non-cube blocks are rare in this world: under 0.1 % of blocks besides
  leaves, plants, snow and water.
- Absolute vertex counts are model-level. The *fractions* are robust, because they depend only on face
  orientation relative to the camera, not on exact model shapes.
- The world is a single biome. CUTOUT, almost all of it leaves, is 34 % of terrain vertices here, and it
  benefits most (51 % removed at the benchmark). A desert or plains world has fewer leaves. The
  expectation is 35–45 % removed elsewhere, but that is unmeasured (§8).

**Vertex budget context.** About 1.1 ms of vertex-stage GPU time per frame at about 1.65 M terrain
vertices (plus clouds, entities, sky and GUI) is roughly 1.5–1.7 G vertices/s. Removing 0.71 M
vertices therefore saves **~0.35–0.45 ms** if terrain is 85–90 % of vertex work. Confirm with a Metal
System Trace (§8).

---

## 3. Design: face buckets → indirect records

### 3.1 What the mesh must provide (contract with the meshing work)

**Alignment with `docs/stage2/meshing-and-vertex-format.md`.** That document independently measures
the same 43 % back-facing share. It also specifies:

- a fixed bucket order `DOWN, NORTH, EAST, UP, UNALIGNED, SOUTH, WEST`, which is exactly this
  document's layout A in §3.3;
- `u16`-quantized plane extents;
- a `QuadBuckets` record on `MeshData.DrawState`;
- the ±0.25 epsilon.

This document's contract is a superset. With one fixed layout it is that design (§3.3 layout A).
The camera-relative per-mesh layout (§3.3 layout C) is an optional phase-2 refinement. It brings
opaque records back to vanilla's count; examples: spawn 870 → 610, benchmark 897 → 780.

The plane extents may be stored quantized as in the meshing doc; only the comparison in §3.2 matters
here.

Per `CompiledSectionMesh` and per layer:

```java
// immutable, built on the worker in SectionCompiler.compile (L53-131), stored in SectionMesh.SectionDraw (L45-46)
record FaceBuckets(
    byte layoutId,          // which bucket order the vertices were written in (§3.3)
    int[] slotQuadStart,    // length 8, prefix sums in *layout slot* order; quads of slot s = [start[s], start[s+1])
    byte[] slotBucket,      // length 7, slot -> bucket (0..5 = Direction ordinal, 6 = UNASSIGNED)
    float[] planeExtent,    // length 6, section-relative: for +X/+Y/+Z the MIN plane coordinate of quads in that
                            // bucket, for -X/-Y/-Z the MAX; NaN if empty
    int unassignedQuads) {}
```

**Rules the mesher must follow.**

- **Bucket membership.** A quad goes into a directional bucket only if all four vertices lie in one
  axis plane and its *front-facing side* points along +d or −d. Front-facing is judged under the
  pipeline's winding, which vanilla quads already follow: `BakedQuad.direction()` for axis-aligned
  block faces, explicit vertex order for fluids.
- **The water backward top face** (`FluidRenderer` L193, L368-372) is therefore a −Y quad at the
  surface height.
- Rotated elements, cross models and sloped fluid tops go to UNASSIGNED.
- **Translucent only.** A quad also goes to UNASSIGNED if its plane lies outside the section's slab on
  that axis. Block models may reach 1 block outside their cell. §3.4 relies on this.
- **Vertex layout.** Vertices are written bucket-contiguous in `layoutId` order. Order inside a bucket
  is free; keep block iteration order.
- **Translucent sorting.** `MeshData.SortState` (L91-116) keeps the slot table, so a sort can tell
  each quad's bucket by range lookup on its start vertex.

### 3.2 The facing test (exact and conservative)

Let `c` be the camera position used by the vertex shader, i.e. `levelRenderState.cameraRenderState.pos`
(§1.3), and `o` the section origin. Bucket `+X` can only contain front-facing quads if
`c.x > o.x + planeExtent[+X]`. Bucket `−X` needs `c.x < o.x + planeExtent[−X]`. Y and Z work the same
way.

Use a margin **m = 0.25 block**: *skip* `+X` only if `c.x ≤ o.x + planeExtent[+X] − m`. The margin
covers:

- **view bobbing.** It is applied inside the projection matrix (`GameRenderer` L642-648) as a view-space
  translation of at most `(0.5·bob, bob)` (`GameRenderer` L361-372). Here `bob ≤ 0.1`
  (`AbstractClientPlayer` L81-89, `ClientAvatarState` L75-77), so the real eye can be up to ~0.11
  block from `c`;
- float rounding in the camera-relative shader math.

Rotations (bobbing, hurt tilt) and the nausea/portal projection skew are linear with positive
determinant and keep the eye at the origin, so facing is unchanged.

UNASSIGNED is always drawn. An empty bucket (zero quads) is "free": it never breaks a run.

**Cost and simplification.** The test is 6 float compares per section-layer.

Block models can put a plane up to 1 block outside the section. So when the camera is beyond that
slack on an axis (`c.x ≥ o.x + 17 + m` or `c.x ≤ o.x − 1 − m`), the result for that axis follows from
the section position alone. It can be computed once per section and refined per layer with the plane
extents only for the few sections near the camera's slabs.

### 3.3 Bucket layout and run merging

The set of visible buckets for a section is {UN} ∪ one or both buckets per axis. A linear vertex layout
cannot make every combination contiguous, so the visible buckets form 1–4 **runs**, each one record.

**Run merging rules**

- Walk the slots in layout order and start a new run only when a wanted, non-empty slot follows an
  unwanted, non-empty one.
- **Gap bridging.** If the unwanted buckets between two wanted ones total ≤ 32 quads, include them and
  keep one run. Those quads are back-facing and the rasterizer culls them, so this is still
  pixel-identical. It costs 0.3–0.4 % extra vertices.

Three layouts were compared in the simulation (§2):

| Layout | How the order is chosen |
|---|---|
| **A. Fixed order** | `−Y, −Z, +X, +Y, UN, +Z, −X` (= `DOWN, NORTH, EAST, UP, UNALIGNED, SOUTH, WEST`). This is the best of all 5,040 static orders at the benchmark, and the order chosen by `meshing-and-vertex-format.md` §4.3. |
| **B. Y-adaptive ring** | Like A, but Y placement depends on the compile camera: above → A; below → `+Y, −Z, +X, −Y, UN, +Z, −X`; inside the Y slab → `−Z, +X, −Y, +Y, UN, +Z, −X`. |
| **C. Camera-relative (recommended for phase 2)** | At compile time the worker evaluates the §3.2 facing test against its own camera position. The compile task already reads it: `SectionRenderDispatcher.cameraPosition`, L48, L425. Layout = buckets facing away (fixed sub-order `−Y, −Z, −X, +Y, +Z, +X`, filtered) + facing buckets (the same sub-order reversed) + UN. For a camera that has not moved since compile, every layer is then exactly one run. |

Opaque (solid + cutout) records per frame, averaged over 36 yaws, bridging ≤ 32. "Stale" means the
meshes were compiled with the camera at ground-level spawn (−0.5, 66.6, 1.5) and the camera then moved
to the scene's position.

| Scene | Vanilla opaque draws | Naive `Direction` order | A. fixed | B. Y-adaptive | **C. camera-relative** |
|---|---|---|---|---|---|
| Benchmark (fresh) | 782 | 1,461 | 897 | 898 | **780** |
| Benchmark, stale (flew up 40 blocks) | 782 | — | 897 | 1,036 | **997** |
| Ground spawn (fresh) | 613 | 1,242 | 870 | 723 | **610** |
| Ground B (fresh) | 461 | — | 725 | 605 | **461** |
| Ground C / D (fresh) | 392 / 433 | — | 601 / 493 | 496 / 490 | **392 / 431** |
| Walked 21 blocks east, stale | 560 | — | 758 | 628 | **576** |
| Walked 11 blocks diagonally, stale | 632 | — | 869 | 724 | **642** |
| 48 blocks east on a hill (+15 Y), stale | 646 | — | 743 | 809 | 818 |

**Translucent records.** There is always one per section with translucent geometry (§3.4): 126 at the
benchmark, 44–100 at ground level.

**Total records per frame (opaque + translucent)**

| Scene | Vanilla | A. fixed | C. camera-relative, fresh | C. camera-relative, stale |
|---|---|---|---|---|
| Benchmark | 908 | 1,023 (+13 %) | ~906 (±0 %) | +24 % after flying up 40 blocks |
| Ground spawn | 713 | 970 (+36 %) | ~710 (±0 %) | +1–3 % after walking 11–21 blocks; +23 % after 48 blocks and +15 in Y |

**Recommendation**

- **Phase 1: layout A.** It is fixed, needs no per-mesh metadata beyond the prefix sums, and matches
  the meshing doc. It costs +13 % records at the benchmark and +13–51 % at ground level.
- **Phase 2: layout C.** It only adds a per-mesh `slotBucket` permutation. In the benchmark the
  meshes are compiled after the teleport and the camera only rotates, so C gives exactly vanilla's
  record count. During normal play it stays within +1–24 %.

C degrades after large moves. In the "48 blocks east" row of the opaque table, C (818) is worse than
the fixed order A (743).

A possible guard, **not yet simulated**: fall back to layout A for a mesh when the worker's camera is
within ~2 sections of the mesh's slab on some axis. Those are the sections whose facing is most likely
to change soon. Evaluate it with the same simulation before adopting it.

**Alternative rejected: GPU re-layout copies.** These would reorder bucket ranges with
`copyToBuffer` when a section's facing set changed. Not worth the complexity: the whole difference
between layouts is ≤ ~250 records per frame, i.e. ≤ ~13 µs of encode at ~52 ns per draw.

**Why the order is cheap to change.** The draw side never hard-codes an order. It reads `slotBucket` /
`slotQuadStart`, so the mesher can switch layouts without touching submission code.

### 3.4 Translucent: head/tail partition instead of buckets

The translucent index buffer is sorted back-to-front across the whole section, so direction ranges are
not contiguous in it. Changes:

- **`SortState.buildSortedIndexBuffer(target, sorting)`** (`MeshData` L92-97) gets a `povX, povY,
  povZ` argument, the point of view the sort is for.
  - It writes the sorted quads in two passes: first every quad that is not *definitely back-facing*
    for that POV (the **head**), then the rest (the **tail**).
  - A quad in bucket `+X` is definitely back-facing iff `povX == −1`, i.e. the camera's section is
    west of this one. `−X` iff `povX == +1`. Same for Y and Z. UN is never in the tail.
  - Relative order inside the head is the vanilla sorted order. Back-facing quads produce no fragments,
    so moving them to the end changes no pixel.
  - Return `headIndexCount`.
- **Call sites** pass the POV they already compute:
  - compile: `SectionCompiler` L120-122 with the compile-time `TranslucencyPointOfView`
    (`SectionRenderDispatcher` L433);
  - resort: `ResortTransparencyTask` L505-510.
- **The uploaded index allocation carries `(firstIndex, headIndexCount, fullIndexCount, pov)`.** It is
  captured in the upload callback closure (`SectionRenderDispatcher` L384-387), *not* read from
  `CompiledSectionMesh.translucencyPointOfView`. That field is set before the upload happens (L532).
- **At draw time**, compute the camera's *strict* POV for the section:
  - per axis, +1 if `c ≥ o + 16 + m`, −1 if `c ≤ o − m`, otherwise "inside";
  - if every non-zero component of the buffer's POV matches the strict POV, emit
    `indexCount = headIndexCount`;
  - otherwise (camera crossed a slab and the resort is still pending) emit the full count, which is
    exactly vanilla.

Result:

- still **one record per section**, so the back-to-front section order and `respectTranslucentOrder`
  grouping (`LevelRenderer` L781-786, L921-929) are untouched;
- 57 % of translucent vertices kept (§2).

The same works for OIT (`ChunkSectionsToRender.renderOit` L62-80). There, buffers are only sorted at
compile time (`LevelRenderer` L1254); the POV check still guarantees correctness.

### 3.5 Record format, index buffers, base instance

The record is the vanilla 20-byte record (`DynamicGpuData` L128-134), unchanged.

| Layer | `indexCount` | `instanceCount` | `firstIndex` | `baseVertex` | `baseInstance` |
|---|---|---|---|---|---|
| SOLID / CUTOUT run | `6 × runQuads` | 1 | 0 (shared quad IB) | `meshBaseVertex + 4 × runStartQuad` | `section.index` (§4 P2) |
| TRANSLUCENT | `headIndexCount` or full | 1 | `indexOffset / indexSize` | `meshBaseVertex` | `section.index` |

- The shared quad index buffer is relative (`i, i+1, i+2, i+2, i+3, i`; `RenderSystem` L50-57). A run
  addressed through `baseVertex` therefore needs no more indices than vanilla's per-section count:
  `largestIndexCount` (`LevelRenderer` L765-767, L958-961) stays valid.
- `baseVertex` stays within vanilla's range: up to about 4.8 M (spec L257).
- **Optional (P7).** A terrain-private **16-bit** sequential quad buffer of 16,384 quads (196 KB).
  - The shared one is INT because clouds request large counts (`RenderSystem` L445).
  - It halves index fetch: 12 B instead of 24 B per quad, about 2.8 MB/frame at the benchmark.
  - Split any run over 16,384 quads (≤ 65,536 vertices). That only happens in pathological all-leaves
    sections.
  - Change `ChunkSectionsToRender.renderLayers` L91-93 to bind it for SOLID/CUTOUT.

### 3.6 Expected result (benchmark)

| | Vanilla | With buckets |
|---|---|---|
| Terrain vertices/frame | 1.65 M | 0.94 M (−43 %) |
| Vertex bytes fetched/frame | 46 MB | 26 MB |
| Indirect records/frame | 908 | ~1,023 (layout A) / ~906 (layout C) |
| `drawIndexedIndirect` calls/frame | 3 (1 heap per layer) | 3 |
| Vertex-stage GPU time | ~1.1 ms | ~0.65–0.75 ms (estimate) |
| Fragment work | — | unchanged (back faces never reached it) |

### 3.7 Separate path (GL)

The same runs can feed `prepareChunkRenders` (L811-862): one `RenderPass.Draw` per run, with
`baseVertex` = run start and the same section UBO. On GL every record is a real `glDraw*` (§1.4).
Layout A's +13–51 % draws, or layout C's 0–24 %, would cost CPU on a driver-bound backend. Two
options:

- enable buckets only when `usingMultiDrawIndirectForTerrain` is true;
- or raise the bridging threshold on the separate path to about 256 quads.

---

## 4. CPU side: make submission persistent and incremental

### P1. Draw descriptors live on the mesh (removes the HashMaps and the lock from the frame)

1. **Upload callback.** Extend `UberGpuBuffer.UploadCallback.bufferHasBeenUploaded(T key)`
   (L199-201) to `(T key, GpuBuffer heap, int heapId, long offsetFromHeap)`.
   - Pass the values at the call site (L105), where the allocation is in hand (L100-104).
   - Give each `UberGpuBufferHeap` a small stable `heapId` (L190-196); assign it at heap creation
     (L86-98).
2. **Store descriptors.** In `SectionRenderDispatcher.RenderSection.vertexBufferUploadCallback` /
   `indexBufferUploadCallback` (L356-366), store into render-thread-only fields of
   `CompiledSectionMesh`:
   - per layer: `GpuBuffer vertexHeap; int vertexHeapId; int baseVertex`;
   - translucent: `GpuBuffer indexHeap; int indexHeapId; IndexType; int firstIndex;
     int headIndexCount; int fullIndexCount; byte povX, povY, povZ`.

   Resort uploads overwrite the translucent fields in place. These are plain fields: they are written
   and read only on the render thread (§1.6).
3. **Build without the lock or HashMaps.** The draw-list build reads only `section.getSectionMesh()`
   (atomic) and these fields. It needs **no `copyLock`** and no `allocationMap` lookups.
   `getRenderSectionSlice` (L111-130) is no longer called per frame.

   **Why the lock is not needed.** A worker may free a mesh's TLSF range (`releaseSectionMesh`) while
   the render thread still emits that mesh's records this frame. That is harmless:
   - ranges are re-allocated and overwritten only by the render thread, in
     `uploadTerrainBuffersToGpu`, after the frame's draws are recorded (`LevelRenderer` L299-310);
   - the copies are queue-ordered after the draws;
   - heap destruction is deferred by the backend (`07-game-render-path.md` §11.2).

   Do **not** read anything that `CompiledSectionMesh.close()` clears (L113-117). Snapshot `draws` into
   an immutable array at construction (L35-46).

### P2. Persistent instance stream indexed by `RenderSection.index`

- **Buffer.** Replace `DynamicGpuData.writeChunkSectionsInstanced` (L100-111) with a
  `TerrainInstanceData` owned by `LevelRenderer`:
  - one `VERTEX | COPY_DST` buffer of `viewArea.size() × 16` B, i.e. 25² × 24 = 15,000 slots =
    240 KB at RD 12 (`RotatingSectionStorage` L25-27);
  - plus a CPU shadow.
- **Indexing.** `baseInstance = section.index`, the stable slot (`SectionRenderDispatcher` L202).
- **Writes.**
  - `ChunkPosition` is written when a slot gets a new section node: hook
    `RenderSection.setSectionNode`, L226-234.
  - `ChunkVisibility` is written only while a section fades in. Keep a small list of fading slots,
    filled in `updateUploadTime` (L318-322) and drained when `getVisibility` reaches 1 (L216-219).
  - Coalesce dirty slots into a few ranges and `writeToBuffer` them before the main pass, or rewrite
    the whole buffer when more than ~25 % is dirty.
- **Effect.** In steady state, no instance upload and **no per-frame copy** remains.
  - Today there is a `writeToBuffer` of ~8 KB every frame (`DynamicGpuDataStorageNonMapped` L120).
  - It is recorded before the frame graph runs (`LevelRenderer` L957 runs before L281). On Metal it
    therefore shares the pre-pass blit encoder with the other per-frame uploads (spec L836, B6).
  - The saving is one copy command plus the CPU shadow write, not a whole encoder.
- **Lifetime.** Recreate the buffer in `LevelRenderer.invalidateCompiledGeometry` when the `ViewArea`
  is rebuilt (L1179-1215, `new ViewArea` at L1202). Bind the whole buffer in `DrawIndirect.render`
  (`ChunkSectionsToRender` L136).

### P3. `TerrainDrawList`: allocation-free build, cached between frames

Replace `extractSectionDrawGroups` + `prepareChunkRendersIndirect` (`LevelRenderer` L722-809,
L899-964) with a new `net.minecraft.client.renderer.chunk.TerrainDrawList` owned by `LevelRenderer`.

```text
state (all reused across frames):
  int[][] opaqueRecs[layer][heapId]        growable int arrays, 5 ints per record
  int[]   transRecs + transGroups          translucent records in back-to-front order, split into groups
                                           on (vertexHeapId, indexHeapId, indexType) change, like L781-786
  GpuMultiDrawIndexedIndirect[] reused     ChunkSectionsToRender L193-196
  long    builtVisibleVersion, builtMeshEpoch
  Box     safeBox                          camera region in which no facing mask changes

build(camera):
  if visibleVersion == builtVisibleVersion && meshEpoch == builtMeshEpoch && safeBox.contains(camera):
      reuse records                                            // benchmark: ~98 % of frames
  else:
      clear counts; safeBox = infinite
      for section in visibleSections (no lock):
          mesh = section.getSectionMesh(); if not CompiledSectionMesh: continue
          axisMask = per-axis facing from section AABB (+ per-layer plane extents if camera in its slab)
          shrink safeBox to the nearest threshold plane on each axis
          for layer in SOLID, CUTOUT:
              d = mesh.gpu[layer]; if absent: continue
              runs = RUNS[d.layoutId][visibleMask | emptyMask | bridgeableMask]
                     // static table: (1 layout for A, ≤ 27 for C) × 128 masks;
                     // bridgeableMask = buckets with ≤ 32 quads (approximates the gap rule of §3.3)
              for run: append {6·q, 1, 0, d.baseVertex + 4·start, section.index} to opaqueRecs[layer][d.vertexHeapId]
          translucent: append one record (head or full count, §3.4) in near-to-far order
      reverse translucent order for classic transparency (as L921-929)
  map the "Chunk Sections Command Buffer" ring once; bulk-copy all record arrays
  (MemoryUtil.memCopy / IntBuffer.put(int[])); one GpuBufferSlice per group
```

**Versions to add**

- `visibleVersion`: bump in `LevelRenderer.clearVisibleSections` (L1259-1262). It runs on every
  `applyFrustum` (`LevelExtractor` L503-512).
- `meshEpoch`: a global `AtomicLong` bumped in:
  - `RenderSection.setSectionMesh` (`SectionRenderDispatcher` L311-316, any thread);
  - the upload callbacks (P1);
  - heap create/destroy (`UberGpuBuffer` L86-98, L115-125);
  - and on toggles of MDI, OIT or wireframe.

**Ring write.** Keep the records in the `MAP_WRITE` ring (`DynamicGpuData` L113-119). Add
`DynamicGpuDataStorageMapped.writeRaw(int[] data, int ints)`: one `map`, one bulk copy, no record
objects. Rewriting about 20 KB per frame costs ~2 µs, and it keeps the backend's "CPU-decode the
Shared records" option valid (§5).

**Estimated cost**

| Operation | Cost |
|---|---|
| Rebuild (~490 sections) | ~10–20 µs: a few compares, one table lookup and 1–2 record appends per section-layer, no allocation |
| Reuse | ~2 µs (the ring copy) |
| Today | ~36 µs and ~46 KB of garbage per frame |

`applyFrustum` runs once per 2° rotation bucket (`LevelExtractor` L140-149). At the benchmark's
36°/s, that is 18 rebuilds per second, i.e. ~2 % of frames at 1000 FPS. When walking, the safe box
(nearest bucket plane ± m) is typically 0.1–1 block. At 4.3 blocks/s and 1000 FPS, rebuilds then
happen every ~25–250 frames.

### Smaller fixes (fold into P3)

- Key groups by `heapId`, not by hash code (§1.2).
- `ChunkSectionLayer.values()` per section (L744) disappears with P3. If any per-frame loop remains,
  cache it in a `static final` array.
- Stop calling `layer.pipeline(false).getVertexFormatBinding(0)` per section-layer (L758). Vertex size
  is a constant (28).

---

## 5. Metal backend: contract check

**What the contract says**

- `drawIndexedIndirect(slice, n)` is recorded as a 32-byte `DRAW_INDEXED_INDIRECT` stream record
  `{drawCount, buffer, offset}` (`CONTRACT.md` L335-336; `mcmetal_stream.h` L73, L649-658).
- M1 decoder: loop `drawIndexedPrimitives:indexType:indexBuffer:indexBufferOffset:0
  indirectBuffer:buf indirectBufferOffset:offset + 20·i`. It **MAY** CPU-decode the Shared records
  instead. M4: ICB (`CONTRACT.md` L336; spec L260, L832).
- Records are byte-identical to `MTLDrawIndexedPrimitivesIndirectArguments`. Per-instance attributes
  are fetched at `baseInstance + instance` for indexed indirect draws; this is verified (spec L466,
  L895). ICB is untested (spec L926).
- `maxDrawIndirectDrawCount = Integer.MAX_VALUE` (spec L724, L904), so each group is one call.

**Does anything need to change? No.** The proposal keeps:

- the 20-byte record;
- `instanceCount = 1`;
- the same index buffers (shared INT quad buffer or the translucent heaps);
- `baseVertex` within the existing range;
- `baseInstance` ≤ ~15,000;
- the same number of `drawIndexedIndirect` calls (3 at RD 12).

Only `drawCount` per call grows: by 0–51 %, depending on the layout (§3.3).

**Recommendations for the decoder implementers (optional)**

1. **Keep the per-record loop branch-free and cheap.**
   - About 900–1,200 records per frame at ~52 ns each (spec L42) is ~47–62 µs of encode. Vanilla's
     908 records take ~47 µs.
   - On the CPU-decode path, skip records with `indexCount == 0`. The game will not emit any, but it
     is a free guard.
   - Do not try to coalesce records; the game already merges contiguous runs.
2. **CPU-decode stays valid only for `MAP_WRITE` (Shared, CPU-written) indirect buffers.**
   - If a future game patch moves the records into a persistent `COPY_DST` buffer updated with
     `writeToBuffer`, the buffer's bytes at decode time may still be the previous frame's contents.
   - So the decoder must CPU-decode only buffers created with `MAP_WRITE`, and use the GPU indirect
     path otherwise.
   - This proposal keeps the ring precisely so the fast path stays available.
3. **M4 ICB.**
   - Size the ICB per group from `drawCount`.
   - Optionally reuse last frame's ICB when the record bytes for that group are unchanged. A
     `memcmp` of ~20 KB costs about 1 µs.

     With P3's caching, the benchmark's records are identical on about 98 % of frames, so
     ICB encoding would drop to near zero.
4. **Debug counter.** Expose records and groups per frame in the backend's debug statistics, to
   verify §3.6 in game.

---

## 6. Implementation plan (game patches, in order)

| # | Patch | Files / methods | Depends on | Gain |
|---|---|---|---|---|
| P1 | Mesh-side GPU descriptors; heap ids; callback signature | `UberGpuBuffer` (L86-105, L190-201), `SectionRenderDispatcher.RenderSection` (L356-366, L368-401), `CompiledSectionMesh` (new fields, immutable `draws` snapshot) | — | removes the HashMaps and `copyLock` from the frame |
| P2 | Persistent instance stream, `baseInstance = section.index` | new `TerrainInstanceData`; `RenderSection.setSectionNode`/`updateUploadTime`; `DynamicGpuData` L100-111 retired; `ChunkSectionsToRender` L136 | — | no per-frame instance `writeToBuffer`, no `ChunkSectionInfo` objects |
| P3 | `TerrainDrawList` (flat records, cache + safe box, `writeRaw` into the ring) | new class; `LevelRenderer` L263-270, L722-809, L899-964 replaced; `DynamicGpuDataStorageMapped.writeRaw` | P1, P2 | ~36 → ~2–20 µs/frame, ~46 KB/frame → 0 |
| P4 | Face buckets for SOLID/CUTOUT: facing test, run merging, bridging; layout A first, camera-relative layout C later | `TerrainDrawList` + mesher (`SectionCompiler`, `BufferBuilder`, `SectionMesh.SectionDraw`) | P3, meshing work | **−43 % terrain vertices** (opaque part) |
| P5 | Translucent head/tail | `MeshData.SortState.buildSortedIndexBuffer`, `SectionCompiler` L120-122, `ResortTransparencyTask` L505-532, upload closure L384-387 | P1, P4 | −43 % of translucent vertices, order unchanged |
| P6 | Separate path consumes the same runs (GL only) | `LevelRenderer.prepareChunkRenders` L811-862 | P4 | keeps GL correct; optional |
| P7 | Terrain 16-bit quad index buffer | `ChunkSectionsToRender.renderLayers` L91-93; run splitting in `TerrainDrawList` | P4 | −50 % index fetch for terrain |

**Validation**

- Pixel diff against vanilla. It must be identical, up to the existing frame-to-frame noise: the
  `run/diff-*` screenshot tooling with `tools/ImgDiff.java`, with scenes where the camera sits exactly
  on section boundaries and while flying fast through them.
- A debug overlay counting records, vertices and rebuilds per frame.
- A Metal System Trace comparing vertex-channel time before and after.

---

## 7. Risks and visual parity

| Risk | Mitigation |
|---|---|
| A quad is bucketed by its nominal `BakedQuad.direction` while its geometry is not axis-aligned (rotated elements) | Classify by geometry (all 4 vertices on one axis plane) and winding, never by `direction()` alone. Everything else goes to UNASSIGNED. Add a debug assertion that a bucketed quad's winding normal equals its bucket. |
| Model elements outside the section bounds (up to 1 block) | Opaque: per-bucket plane extents handle it. Translucent: quads outside the slab go to UNASSIGNED (§3.1). |
| Eye ≠ `cameraRenderState.pos` (bobbing) | Margin 0.25 ≥ 2 × the maximum bob offset (§3.2). |
| Resort lag after a POV change | The tail is drawn whenever the current strict POV differs from the buffer's (§3.4), which is vanilla behaviour. |
| More records hurt GL (separate path) | Gate on MDI or use larger bridging (§3.7). |
| Lock removal races | Only read descriptors written on the render thread. Never read `CompiledSectionMesh.draws` after `close()` (§4 P1). Freed ranges are not reused before this frame's draws execute (§1.6). |
| A camera crossing many slabs between rebuilds (teleport) | `repositionCamera` invalidates the occlusion graph (`ViewArea` L71-79) → `applyFrustum` → `visibleVersion` bump → rebuild. The safe-box test catches any other move. |
| Pipelines with `cull = false` drawing terrain meshes | None in vanilla: SOLID/CUTOUT/TRANSLUCENT, the `_MULTIDRAW` twins, WIREFRAME and OIT_TERRAIN all inherit `cull = true`. Assert `pipeline.isCull()` (`RenderPipeline` L82-84) before applying bucket culling, and draw all buckets otherwise. `OitPipelineSet` sets no cull override. |

Parity: identical pixels. Only primitives that back-face culling already rejects are removed, plus
bridged back-facing quads that are still culled.

---

## 8. Open questions

1. **GPU cost per draw on Apple GPUs.** What does an extra 100–250 small indirect draws per frame cost
   on M5, in the M1 loop and in an ICB? The expectation is under 10 µs. Measure 900 vs 1,200 records
   on the same scene.
2. **Terrain's share of vertex-stage time.** Clouds at `cloudRange = 128` and entities also run vertex
   work. A Metal System Trace with clouds off would split the 1.1 ms and firm up the 0.35–0.45 ms
   estimate.
3. **Vertex reuse.** Does Apple's vertex stage shade each quad vertex exactly once with the
   `i, i+1, i+2, i+2, i+3, i` pattern? If not, the 16-bit index buffer and vertex counts scale
   differently. Read it from the trace's vertex-invocation counter.
4. **Biome sensitivity.** The simulation covers one snowy taiga world. Re-run it on a plains/desert save
   and a dense jungle to bound the 37–44 % range.
5. **Interaction with the compact vertex format** (28 B → 16–20 B, another stage-2 doc). The savings
   multiply: fetch bytes fall by both factors. The bucket ranges must survive any vertex-format
   change; they are in quads, so they do.
6. **ICB reuse rules.** If the backend caches ICBs on identical record bytes, it must also check that
   the bound vertex and index heaps are the same objects (heap ids) between frames.
