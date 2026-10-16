# 07 — Game-side render path and its CPU cost (Minecraft 26.3, vanilla)

Scope: everything *above* the RenderPearl API that runs every frame: the main loop, the
extract/render split, terrain (chunk sections), entities/block entities/particles ("features"),
sky/clouds/weather, OIT, post chains, GUI, `DynamicGpuData` (`RenderSystem.getDynamicUniforms()`),
and the frame graph. It lists which backend semantics this code depends on, what it costs, and
ranked FPS opportunities for a native Metal backend on Apple M5.

All paths are relative to `src/vanilla`. "L123" means line 123 of the named file.

---

## 0. Key findings

1. **Single-threaded frame.** Input, client tick, extract, command recording, submit and present all
   run on the one "Render thread" (`Minecraft.run` L910-919 → `runTick` L1166 → `renderFrame` L1245).
   Extract and render are split into two phases (state objects in between), but they run back to
   back on the same thread. Nothing in the frame is pipelined.
2. **The measured cost is backend/driver time, not Java.** In the existing JFR profiles
   (`results/raw/prof-*.jfr`, RD 12, M5) the render thread spends almost all of its sampled wall time
   in native code:
   - Vulkan/MoltenVK: 1034 of 1184 render-thread samples are inside `vkQueueSubmit2KHR`, called from
     `FrontendCommandEncoder.submit`.
   - OpenGL: 990 samples are inside `SDL_GL_SwapWindow`.
   - Java-side render-thread work is a small fraction of the frame: 121 Java samples at a 10 ms period,
     against 1063 native samples at 20 ms.

   The JVM process uses only about 0.3–0.5 cores in total (`jdk.CPULoad` jvmUser 3–5% of 10 cores).
   The native Vulkan run at 405 FPS is therefore mostly **waiting**: it is GPU-bound or
   drawable-bound at native resolution. GL at 231 FPS is driver-bound.

   **What this means for 1000 FPS:**
   - GPU work per frame must drop well below 1 ms: fewer passes and less attachment traffic.
   - Backend encoding must be cheap and must not block.
   - Java-side work, estimated at ~0.1–0.3 ms/frame today, then becomes the next wall.
3. **Terrain takes about 1–2k draws per frame at RD 12.**
   - The run logs record how far the per-frame ring buffers grew. These are peak values over the
     benchmark run, not averages:
     - `Chunk Sections Instanced` grew to a capacity of 1024, so 513–1024 sections had geometry.
     - `Chunk Sections Command Buffer` grew to 2048, so 1025–2048 section-layer draws per frame
       (`run/vanilla/logs/2026-10-04-2.log.gz`).
   - On the multi-draw-indirect (MDI) path these are issued as a handful of `drawIndexedIndirect`
     calls: one per layer × vertex heap × index heap/type.
   - On the GL path (no MDI on macOS GL 4.1) every section-layer is its own `drawIndexed`.
   - **Metal has no native multi-draw-indirect.** The Metal backend must turn each
     `drawIndexedIndirect(slice, count)` into an indirect command buffer (ICB) execution, or into a
     tight loop. Otherwise it pays ~1–2k encoder calls per frame.
4. **The terrain vertex format is 28 bytes** (`DefaultVertexFormat.BLOCK` L27-32: `RGB32F pos`,
   `RGBA8 color`, `RG32F uv`, `RG16_SINT light`).
   - Positions are section-relative floats (`SectionCompiler` L95-100).
   - Solid and cutout use a shared, generated quad index buffer (`RenderSystem` L50-57).
   - Translucent uses per-section sorted index buffers.
   - All meshes of a layer live in a few 128 MiB "uber" vertex heaps and 32 MiB index heaps
     (`SectionRenderDispatcher` L65-75), addressed with `baseVertex`/`firstIndex`.
5. **Section offsets are supplied in one of two ways.**
   - MDI path: as an instanced vertex attribute (binding 1, step rate 1, 16 B,
     `CHUNK_DATA_INSTANCED`), selected through `baseInstance` in the 20-byte indirect command.
   - Separate-draw path: as a per-draw 16-byte `ChunkSection` uniform buffer.
   - In both cases the vertex shader computes `Position + (ChunkPosition - CameraBlockPos) + CameraOffset`
     (`core/terrain.vsh` L33), which is camera-relative and integer-exact.
6. **Default settings produce about 8 render passes, 4 standalone clears and 1 present blit per frame**
   (§3.2). Several passes share the same attachments and could be merged:
   - clear + clear + sky + main;
   - hand + screen effects;
   - depth clear + GUI.

   On a tile-based deferred renderer (TBDR) GPU at 3024×1964 each extra load/store of colour (RGBA8)
   plus depth (D32) is about 47 MB. **Folding clears into load actions and merging compatible passes is
   the largest GPU-side win available to the backend alone.**
7. **The backend must honour ordered-write semantics.** The game rewrites the *same* `GpuBuffer`
   every frame with `writeToBuffer`, while earlier frames may still be reading it:
   - projection UBOs and the Globals UBO (`ProjectionMatrixBuffer` L50, `GlobalSettingsUniform` L41);
   - the instanced chunk-section data (`DynamicGpuDataStorageNonMapped` L120);
   - the weather vertex buffer (`WeatherEffectRenderer` L119).

   `writeToBuffer` and `copyToBuffer` therefore **must be queue-ordered GPU operations** (Vulkan does
   staging + `vkCmdCopyBuffer` + barrier, `VulkanCommandEncoder` L587-614). They must not be immediate
   CPU memcpys into shared memory.
8. **Entities are never occlusion-culled.**
   - Entities get a frustum test plus a "section compiled & faded in" test only
     (`LevelExtractor.isEntityVisible` L291-309).
   - Block entities are culled at section granularity: only sections in `visibleSections`, which have
     passed the cave-culling breadth-first search (BFS) and the frustum (L315-351).
   - Entity models are CPU-skinned into a per-frame staging vertex buffer every frame.

---

## 1. Threads and the frame loop

### 1.1 Threads

| Thread | What runs there | Source |
|---|---|---|
| **Render thread** (= Minecraft main thread, the only thread allowed to call `RenderSystem`) | SDL events, packets, client ticks (20 Hz), extract, all RenderPearl calls, submit, present, terrain uploads, occlusion-graph partial updates, region copying for dirty sections | `Minecraft.run` L910-919; `RenderSystem.assertOnRenderThread` L138 |
| `Worker-Main-N` | Section meshing (`CompileTask`), translucent re-sort (`ResortTransparencyTask`), full occlusion-graph rebuild (`CompletableFuture.runAsync`), plus all other background work | `Util.makeExecutor` L199-232 |
| Server thread | Integrated server in singleplayer | — |

`Worker-Main-N` is a `ForkJoinPool` with `clamp(availableProcessors-1, 1, max.bg.threads)` threads
(`Util` L234-236). That is 9 threads on a 10-core M5 (4P+6E). The pool is shared with every other
`Util.backgroundExecutor()` user.

### 1.2 Per-frame order (`Minecraft.runTick` L1166 → `renderFrame` L1245-1358)

```
runTick
 ├─ packets, scheduled tasks, N×client tick (0 ticks on most frames at >20 FPS)
 │    textureManager.tick()  (animated atlas: 1 render pass per mip, 20 Hz; TextureAtlas L246-262)
 └─ renderFrame
     ├─ surface.configure (if needed), surface.acquireNextTexture()                L1273   <-- see §11 (acquire late!)
     ├─ gui.update, level.update, gameRenderer.update (camera, post-effect list)   L1295-1300
     ├─ pick() (crosshair raycast)                                                L1302
     ├─ gameRenderer.extract()  ── EXTRACT PHASE (world → *RenderState)          L1305
     ├─ RenderSystem.executePendingTasks() (fence-polled callbacks)               L1310
     ├─ gameRenderer.render()   ── RENDER PHASE (RenderState → RenderPearl)       L1312
     ├─ surface.blitFromTexture(main color)                                       L1322
     ├─ commandEncoder.submit()                                                   L1337
     ├─ surface.present()                                                         L1340
     ├─ DynamicGpuData.reset()  (rotate rings, create fences)                     L1348
     ├─ levelRenderer.endFrame() (clouds)                                         L1349
     └─ FramerateLimiter if limit < 260 ("260" = unlimited)                       L1351-1353
```

Implications for the backend:
- **One `submit()` per frame**, after the blit to the surface.
- Many fences are created *before* that submit: `MappableRingBuffer.rotate` L52,
  `StagedVertexBuffer.GpuBufferPool.endFrame` L352, and `RenderSystem.queueFencedTask` L324.
- A fence created mid-frame must signal when all work recorded up to that point completes.
  Typically this means the frame's command buffer completion.
- `awaitCompletion(-1)` on a fence whose work has not yet been submitted must flush or commit first,
  or it deadlocks. `MappableRingBuffer.currentBuffer` L36-45 can wait on a fence from the same frame
  if a ring is rotated three or more times in one frame. `StagingBuffer.PersistentlyMapped` can do
  that while uploads are failing over.

### 1.3 Extract phase (`GameRenderer.extract` L442-467, `LevelExtractor.extract` L108-241)

Extract writes into a single, *not double-buffered* `GameRenderState`. That state holds
`LevelRenderState`, `GuiRenderState`, `OptionsRenderState` and `WindowRenderState`. Steps:

- Window/options snapshot (L742-776). Lightmap state, which re-renders only when `needsUpdate`
  (`Lightmap.render` L60-91). Camera and fog (L778-790).
- `LevelExtractor.extract`:
  - **`applyFrustum`** (L140-149, L503-512). Only runs when the occlusion graph has a new result
    (`consumeFrustumUpdate`) or the camera rotated across a 2° bucket (`floor(rot/2)`). It clears and
    refills `visibleSections` by octree traversal (§4.5).
  - **Dirty-section scan** (L151-187): over *every* entry of `visibleSections`
    (`RotatingSectionStorage` array lookup). For each dirty section it builds a `RenderSectionRegion`
    on the render thread: `RenderRegionCache.createRegion` copies the 27 neighbouring
    `PalettedContainer`s plus an `ImmutableMap.copyOf` of each chunk's block-entity map
    (`SectionCopy` L26-41). This is a CPU spike during chunk loading or block edits and drives the 1% lows.
    - Dirty sections outside `visibleSections` are not rebuilt until they become visible.
  - Entities (L261-289): iterate *all* `entitiesForRendering`. Visibility is frustum test (AABB
    inflated 0.5) plus `isSectionCompiledAndVisible` (fade ≥ 0.3). Each visible entity gets a **new
    `EntityRenderState` object every frame** (`EntityRenderer.createRenderState` L154-159).
  - Block entities (L315-368): only from sections in `visibleSections` (no per-BE frustum test),
    plus globally rendered BEs. Each gets a new render state per frame.
  - Block outline/breaking, transient blocks, weather columns, sky, border, particles
    (`ParticleEngine.extract` with a frustum offset −3, L218), clouds, debug gizmos.
- `gui.extractRenderState` (L456): the GUI is rebuilt every frame (§9).

### 1.4 Render phase (`GameRenderer.render` L469-518)

```
render()
 ├─ resize targets if window changed                                     L473-475
 ├─ CLEAR main color+depth (depth=0.0)                                   L477-481   (clear #1)
 ├─ GlobalSettingsUniform.update  → writeToBuffer(Globals UBO)           L482-492, GlobalSettingsUniform L41
 ├─ preparePostEffects (allocates a HashSet every frame)                 L494, L520-549
 ├─ lightmap.render (only if needsUpdate; 16×16 target, 1 pass)          L495
 ├─ renderLevel                                                          L635-676
 │    ├─ projection (with bob/nausea) → ProjectionMatrixBuffer.writeToBuffer every frame   L642-665
 │    ├─ fog UBO (mapped ring)                                            L667-668
 │    ├─ LevelRenderer.render (§2)                                       L672-674
 │    └─ render3dHud: hud projection, CLEAR depth (#3), "Item in hand" pass,
 │                    "Screen effects" pass (if any), 3D crosshair        L678-727
 ├─ blitEntityOutline (only if glowing entities)                         L499
 ├─ applyPostEffects (vanilla default: none; see §8)                     L500
 ├─ fogRenderer.endFrame (ring rotate)                                   L506
 ├─ CLEAR main depth (#4)                                                L507
 ├─ GuiRenderer.render + endFrame                                        L511-512
 ├─ renderBuffers.endFrame (StagedVertexBuffer pools: fences, recycle)   L515
 └─ resourcePool.endFrame (frame-graph transient targets, kept 3 frames) L516
```

---

## 2. LevelRenderer.render: the world frame (`LevelRenderer` L184-324)

### 2.1 Order of operations

1. `repositionCamera`: `ViewArea.repositionCamera` (rotating storage; invalidates the occlusion graph
   when the camera section changes), `SectionRenderDispatcher.setCameraPosition` (L196-197, L355-363).
2. Model-view stack ← `viewRotationMatrix`. This is rotation only; everything is camera-relative (L198-200).
3. **`submitFeatures`** (L202, L326-353): entities, block entities, block-break overlay, transient
   blocks, particles, block outline and gizmos are pushed into `SubmitNodeStorage` (§5).
4. **`featureRenderDispatcher.prepareFrame`** (L204): sorts and groups the submits, generates *all
   feature vertices on the CPU*, and uploads them (`StagedVertexBuffer.upload` → staging map + 1–2
   `copyToBuffer`).
5. Build a new `FrameGraphBuilder` every frame (L207-278):
   - `importExternal("main")` and `importExternal("entity_outline")`;
   - OIT transient targets, only when improved transparency is on (L217-240);
   - passes `clear` (L247-258, **clear #2**: main colour = fog colour, depth = 0), `sky` (L259-261,
     L365-383), `main` (L272, L385-473), and the optional `entity_outline` post chain (L273-278).
6. Prepare terrain draw lists (L263-270): `prepareChunkRendersIndirect` or `prepareChunkRenders` (§4.6).
7. `frame.execute(...)` (L281-291): passes run immediately, in dependency order (§10).
8. `featureFrame.close()` (L295): the staged vertex buffer is released for this frame.
9. **`compileSections`** (L297, L966-995): schedule async compiles for the
   `sectionUpdateRenderStates` produced in extract. A section compiles synchronously only with
   `prioritizeChunkUpdates` = NEARBY or PLAYER_AFFECTED (default NONE, `Options` L249-260). Then
   `scheduleTranslucentSectionResort` (L1217-1257).
10. **`uploadTerrainBuffersToGpu`** under `copyLock` (L299-310): staged meshes go into the uber heaps
    (§4.3). Uploads recorded here are used **next** frame, giving one frame of latency.
11. **`sectionOcclusionGraph.update`** (L313): BFS partial update on the render thread, or launch of
    an async full rebuild (§4.5).

### 2.2 Inside the `main` frame-graph pass (`addMainPass` L385-473)

Classic transparency, the default path. One render pass, "Main", over main colour + main depth:

| Order | What | Source |
|---|---|---|
| 0 | `prepareTranslucents`: cloud mesh/UBO (mapped), world-border rebuild (`writeToBuffer` when changed), weather VB (`writeToBuffer` when raining), `resizeAllAutoStorageIndexBuffers` (may create index buffers). All of this runs **before** the pass is opened. | L439, L532-554 |
| 1 | Terrain SOLID then CUTOUT (`ChunkSectionLayerGroup.OPAQUE`) | L453, L518-526 |
| 2 | Solid features (entities, block entities, items, particles, …) | L528 |
| 3 | Translucent features: shadows, translucent models, name tags, texts, translucent custom geometry, then shape outlines and gizmos, then translucent blocks/items, breaking overlay, water mask | L691; `FeatureRenderDispatcher` L212-242 |
| 4 | Terrain TRANSLUCENT (back-to-front) | L695-697 |
| 5 | "After terrain" features | L699 |
| 6 | Clouds (1 indexed draw, vertex data from a uniform texel buffer) | L700-702; `CloudRenderer` L231-243 |
| 7 | Weather (1–2 draws), world border | L705-706 |

After "Main", still inside the frame-graph task:
- "Outline" pass to `entityOutlineTarget` (cleared to 0) when glowing entities are present (L463, L709-720);
- "See through features" (colour only) (L464-466);
- "Always on top features" with depth cleared to 0.0 (L468-470, L486-512).

**Improved transparency (OIT) path** (`executeOit` L556-643). A "Solid" pass, then for each of
`DEPTH_BOUNDS`, `TRANSMITTANCE` and `ACCUMULATE`:
- a separate terrain pass (`ChunkSectionsToRender.renderOit` L62-80);
- a features + border + weather pass;
- a clouds pass;
- after `DEPTH_BOUNDS` only: a depth-bounds-cull full-screen pass and the water mask.

An `OIT Composite` full-screen pass follows. The render targets are:
- `RGBA32F` depth bounds ×2;
- `RGBA16F` transmittance ×2 (MRT);
- `RGBA16F` accumulate;
- two extra `D32` targets.

That is about 10–12 extra passes and roughly 0.9 GB of attachment traffic per frame at 5.9 MP.
**Leave it off (the default: `Options` L224-225) for FPS.**

---

## 3. Pass inventory and draw counts (RD 12, default options, daytime, HUD on, first person)

### 3.1 Draws per frame

| Source | GPU draws | Frontend draw API calls | Notes |
|---|---|---|---|
| Terrain SOLID+CUTOUT+TRANSLUCENT | **~1,025–2,048** section-layer draws across 513–1,024 sections | MDI: ~3–8 `drawIndexedIndirect`. Separate: 1 `drawIndexed` per section-layer. | Counts from ring-buffer growth in `logs/2026-10-04-2.log.gz` (Vulkan) and `latest.log` (GL) |
| Sky | 3–6 (disc, sunrise, sun, moon, stars, dark disc) | same | `SkyRenderer` L129-322; one `writeTransform` each |
| Clouds | 1 | 1 | |
| Weather / border | 0–2 / 0–4 | | |
| Entities / BE / particles | ~1 per (feature group × `RenderType`/texture) → typically 10–100 | same | §5 |
| Hand + screen effects | 1–6 | | separate passes |
| GUI (HUD) | ~10–40 | | §9 |
| Lightmap / animated atlas | 1 at 20 Hz / 5 passes at 20 Hz | | |
| Present | 1 blit/copy | | `blitFromTexture` |

### 3.2 Render passes and standalone clears

Main target attachments: `RGBA8_UNORM` colour and `D32_FLOAT` depth. Both are created with all usages
(`GpuTexture` usage 15 = COPY_DST|COPY_SRC|TEXTURE_BINDING|RENDER_ATTACHMENT, `RenderTarget` L96-101,
`MainTarget` L18).

| # | Op | Attachments | Mergeable? |
|---|---|---|---|
| C1 | `clearColorAndDepthTextures(main)` | main C+D | Redundant with C2 when the level renders |
| (P) | "Update light" (20 Hz) | lightmap 16×16 | — |
| C2 | frame-graph `clear` | main C+D | Fold into the next pass's `loadAction = clear` |
| P1 | "Sky" | main C+D (load) | Same attachments as P2 → merge |
| P2 | "Main" | main C+D (load) | — |
| P3–P5 | "Outline" / "See through" / "Always on top" (conditional) | various | — |
| C3 | `clearDepthTexture(main depth)` | main D | Fold into P6 depth load |
| P6 | "Item in hand" | main C (load) + D (cleared) | Merge with P7 |
| P7 | "Screen effects" (if any) | main C + D | — |
| C4 | `clearDepthTexture(main depth)` | main D | Fold into P8 |
| P8 | "GUI before blur" | main C + D | — |
| B | `blitFromTexture` → drawable | main C → drawable | Unavoidable unless the final pass targets the drawable |

**The API carries no "don't care" information.** `createRenderPass(..., Optional.empty(), depth,
OptionalDouble.empty())` means *load*, and clears only arrive as clear values or standalone `clear*`
calls. Some consequences:
- Main depth after P2 is dead, because C3 clears it next.
- Depth after P8 is dead.
- Colour after B is dead.

Only a backend that defers `endEncoding` until it sees the next command on that texture can pick
`MTLStoreActionDontCare` (§12).

---

## 4. Terrain (chunk sections)

### 4.1 Data model

- `ViewArea` → `RotatingSectionStorage<RenderSection>`. Its radius is the effective render distance;
  for RD 12 that is about 25² columns × world height in sections. `RenderSection.index` is a stable
  slot index (`ViewArea` L32-34).
- `RenderSection` (`SectionRenderDispatcher` L201-571) holds:
  - `AtomicReference<SectionMesh> sectionMesh` (`UNCOMPILED`, `EMPTY`, or `CompiledSectionMesh`);
  - `bb` (an AABB);
  - `renderOrigin`;
  - `uploadedTime`, which drives the fade-in through `getVisibility(now, fade)` (L216-219).
- `CompiledSectionMesh` (`CompiledSectionMesh` L13-118) holds:
  - per-layer `SectionDraw(indexCount, indexType, hasCustomIndexBuffer)`;
  - a `VisibilitySet` (face-to-face visibility from `VisGraph`);
  - renderable block entities;
  - translucency sort state;
  - per-layer upload flags.
- Layers (`ChunkSectionLayer` L10-12):
  - `SOLID` and `CUTOUT` use a 4 MiB builder each;
  - `TRANSLUCENT` uses a 768 KiB builder.

  Groups: `OPAQUE = {SOLID, CUTOUT}`, `TRANSLUCENT = {TRANSLUCENT}` (`ChunkSectionLayerGroup` L6-7).

  Pipelines (`RenderPipelines` L346-402):
  - `SOLID_TERRAIN`;
  - `CUTOUT_TERRAIN` with `ALPHA_CUTOUT 0.5` (`discard`);
  - `TRANSLUCENT_TERRAIN` with the TRANSLUCENT blend and `ALPHA_CUTOUT 0.1`.

  Each has a `_MULTIDRAW` twin.

### 4.2 Vertex and index formats

`DefaultVertexFormat.BLOCK` (L27-32), written by `BufferBuilder.addVertex` fast path (L313-319):

| Offset | Attribute | Format | Bytes |
|---|---|---|---|
| 0 | Position (section-relative, 0..16 plus small offsets) | RGB32_FLOAT | 12 |
| 12 | Color (ABGR packed, tint × AO × shade) | RGBA8_UNORM | 4 |
| 16 | UV0 (atlas UV) | RG32_FLOAT | 8 |
| 24 | UV2 (block/sky light, 0..240) | RG16_SINT | 4 |
| | **stride** | | **28 B** |

There is no normal attribute; directional shading is baked into the colour.

**MDI per-instance stream** `CHUNK_DATA_INSTANCED` (binding 1, `stepRate` = 1, L33-36):
`ChunkPosition RGB32_SINT` + `ChunkVisibility R32F`, 16 B per section. It is written as std140
`ivec3 + float` (`DynamicGpuData.ChunkSectionInfo` L121-126).

**Indices**
- SOLID/CUTOUT meshes have **no** index buffer. They draw with the shared
  `RenderSystem.getSequentialBuffer(QUADS)`, pattern `i, i+1, i+2, i+2, i+3, i` per quad
  (`RenderSystem` L50-57).
  - That buffer only grows: `ensureStorage` doubles the request (L439-469).
  - Its type is `IndexType.least(vertexCount of 2× the largest request)` (L445; `IndexType.least`
    returns INT when the vertex count is ≥ 65,536).
  - One buffer serves *every* quad draw. Clouds at the default `cloudRange` = 128 chunks already
    request tens of thousands of quads, so in practice it is **32-bit**.
  - `firstIndex` is 0 and `baseVertex` selects the section.
- TRANSLUCENT has a per-section sorted index buffer: `MeshData.sortQuads` → `SortState`
  (`MeshData` L68-127). It is `SHORT` when the section has fewer than 65,536 vertices
  (`BufferBuilder` L102-104). It is rebuilt by `ResortTransparencyTask` as the camera moves
  (§4.4); with OIT on there is no re-sorting (`LevelRenderer` L1254).

### 4.3 GPU memory: uber heaps, TLSF, staging (`SectionRenderDispatcher` L54-76, `UberGpuBuffer`, `StagingBuffer`)

- Per layer:
  - vertex `UberGpuBuffer`: 128 MiB heaps, alignment = vertex size (28, a non-power-of-two
    alignment that TLSF supports, `TlsfAllocator` L137-168);
  - index `UberGpuBuffer`: 32 MiB heaps, align 8.
- Heaps are created lazily as needed and destroyed when completely free (`UberGpuBuffer` L86-125).
  Heap buffer usage is `VERTEX|COPY_DST|COPY_SRC` or `INDEX|COPY_DST|COPY_SRC` (L195). They are never
  mapped, so they belong in **GPU-private storage**.
- Mesh bytes take this route:
  1. Worker `BufferBuilder` writes into a per-worker `SectionBufferBuilderPack` (native memory,
     8.75 MiB per pack).
  2. **Under `copyLock`**, `StagingBuffer.tryAppend` (memcpy) moves them into the staging area
     (`UberGpuBuffer.addAllocation` L41-55, `SectionRenderDispatcher` L368-401).
  3. Next frame, on the render thread, under `copyLock`: TLSF allocate, then
     `Uploader.copyTo` → **one `writeToBuffer` (CPU staging) or `copyToBuffer` (persistently mapped
     staging) per allocation** (`UberGpuBuffer` L57-128).
  4. The upload callback runs. When every layer of the mesh is uploaded, the mesh is swapped in and
     the old one released (`checkSectionMesh` L339-354).
- `StagingBuffer.create` (L16-20) is the choice the Metal backend controls:
  - It uses **persistently mapped** GPU staging, a 3-deep `MappableRingBuffer` of 49 MiB with usage
    `MAP_WRITE|COPY_SRC`, only when `hints.writeToBufferIsSlow() && features.persistentMapping()`.
  - Otherwise it uses a 98 MiB CPU `memAlloc` and pays a second copy inside `writeToBuffer`.
  - **The Metal backend should report both as true**, so mesh bytes are written once into shared
    memory and blitted (or later written directly).
- While the staging buffer is full, worker compile tasks **spin** (`Thread.onSpinWait`) until the
  render thread uploads (L451-471, L517-529). On the render thread, a full staging buffer triggers an
  immediate upload (L393-395).
- A heap creation or destruction stops further layer uploads in that frame (L144-150).

### 4.4 Meshing and threading

- Scheduling (`SectionRenderDispatcher` L82-105, L154-159):
  - `schedule()` enqueues a task and posts one `runTask` to the `ForkJoinPool`.
  - `runTask` polls the best task from `SectionTaskDynamicQueue`. The queue is a synchronized list
    with an O(n) scan per poll. It picks the nearest initial compile; recompiles are interleaved with
    a quota of 2 (`SectionTaskDynamicQueue` L18-53).
  - Each running task needs a `SectionBufferBuilderPack` from the pool. Pool size is
    `min(availableProcessors, 0.3×maxHeap/8.75 MiB)` (`SectionBufferBuilderPool` L20-39;
    `GameRenderer` L153-154). That is 10 on M5.
  - If no pack is free, the task is re-queued (NPE catch at L98-99).
- Compile (`SectionCompiler.compile` L53-131):
  - iterate the 4096 blocks;
  - tessellate models (AO lighter cache) and fluids into per-layer `BufferBuilder`s;
  - `VisGraph` flood fill gives face visibility (L129);
  - translucent quads are sorted by the camera position captured at compile time (L120-122).
- **Region copies are made on the render thread in extract** (§1.3). Workers only read the copies.
- Resorts (`scheduleTranslucentSectionResort` L1217-1257): every frame, all `nearbyVisibleSections`
  (within 32 blocks, octree "isClose"), plus `max(visible/8, 15)` round-robin sections, are checked.
  A resort is scheduled when the sorting point of view changed.

### 4.5 Visibility: occlusion graph + octree frustum

**Cave culling** (`SectionOcclusionGraph`):
- BFS over sections from the camera section (`initializeQueueForFullUpdate` L211-253;
  `runUpdates` L255-358).
- It traverses face *d* of a node only when:
  - *d* is not opposite one of the node's travel directions (`smartCull`);
  - the section's `VisibilitySet` says an entry face can see *d* (L290-305).
- Beyond 60 blocks (`MINIMUM_ADVANCED_CULLING_DISTANCE`, L49), an extra ray-march toward the camera
  rejects sections whose line of sight passes through sections not yet in the graph (L307-343).
- Empty sections are not added to the octree, but are traversed through (L276-282).
- Sections of unloaded chunks wait in `sectionsWaitingForChunkLoads` (L273-275).

**When it runs:**
- *Full rebuild*, async on the background executor, when the camera crosses an 8-block cell, FOV
  changes, `smartCull` toggles, or the view area is repositioned (L96-110, L160-175). The old graph
  stays in use until the new one is swapped in atomically (L171-173).
- *Partial update*, synchronously on the render thread, from sections whose mesh just changed or
  whose chunk just loaded (L177-209, `schedulePropagationFrom` L133-145).

**Frustum** (`Octree` + `Frustum`):
- The graph inserts reachable non-empty sections into an `Octree` sized to the view distance (L16-29).
- `addSectionsInFrustum` (L112-127) walks it with JOML `FrustumIntersection.intersectAab`. Fully-inside
  branches skip child tests (`Octree.Branch.visitNodes` L188-218).
- The frustum is first pushed back (`offsetToFullyIncludeCameraCube(8)`, L360-362) so the camera's own
  8-block cube is never culled.
- Child order puts the camera-side octant first (`getNodeIndex` L131-148). **`visibleSections` is
  therefore approximately near-to-far**: opaque is drawn front-to-back, and translucent is reversed to
  back-to-front (`LevelRenderer` L824-833, L921-929).
- Re-run only on a ≥2° rotation bucket change or a graph update (`LevelExtractor` L140-149).

### 4.6 Draw submission (`LevelRenderer` L722-964, `ChunkSectionsToRender`)

**Path selection** (`LevelRenderer` L178-181, L264): MDI is used when all of these hold:
- `limits.maxDrawIndirectDrawCount() > 0`;
- `features.nonZeroFirstInstance()`;
- `!hints.multiDrawIndirectHasKnownIssues()`;
- debug toggle `Minecraft.multiDrawIndirect` (default true, `Minecraft` L362; F3 toggle `KeyboardHandler` L101).

The frontend additionally requires `features.drawIndirect()`, plus `features.multiDrawIndirect()` when
count > 1, and a command buffer with `USAGE_INDIRECT_PARAMETERS` (`FrontendRenderPass` L302-330).

**Common step: `extractSectionDrawGroups`** (L722-809), under `copyLock`. For every visible section ×
3 layers:
- look up the draw and the buffer slice: 2 `HashMap.get` on `UberGpuBuffer.allocationMap`
  (`SectionRenderDispatcher.getRenderSectionSlice` L111-130, which allocates a record);
- append a `ChunkSectionInfo(x, y, z, fade)`;
- compute a group key: hash of the vertex `GpuBuffer`, and for translucent also the index buffer and
  index type;
- append `IndexedDraw(indexCount, 1, firstIndex, baseVertex = offset/28, baseInstance = sectionInfoIndex)`.

Grouping rules:
- Translucent with classic ordering only merges *consecutive* sections that share a key (L781-786).
- Everything else merges through an `Int2ObjectOpenHashMap` built fresh every frame (L788).

**MDI path** (`prepareChunkRendersIndirect` L899-964 → `ChunkSectionsToRender.DrawIndirect` L105-159):
- All groups' `IndexedDraw`s go into one mapped ring buffer: "Chunk Sections Command Buffer", 20 B
  stride, usage `INDIRECT|MAP_WRITE` (`DynamicGpuData` L113-119; `writeDataBatchedMultiple`).
- Section infos go into "Chunk Sections Instanced": usage `VERTEX|COPY_DST`, a *single non-ring
  buffer updated with `writeToBuffer` every frame* (`DynamicGpuData` L100-111;
  `DynamicGpuDataStorageNonMapped` L96-123).
- Per layer: `setPipeline(*_MULTIDRAW)`, `setVertexBuffer(1, sectionInfos)`. Per group:
  `setVertexBuffer(0, heap)`, `setIndexBuffer(group index or shared quad index)`, then
  `drawIndexedIndirect(slice, n)`, chunked by `maxDrawIndirectDrawCount` (L129-157).
- **The indirect record is `{indexCount, instanceCount = 1, firstIndex, baseVertex, baseInstance}`.**
  It is byte-identical to `MTLDrawIndexedPrimitivesIndirectArguments`.

**Separate path** (`prepareChunkRenders` L811-862 → `DrawSeparate` L161-191):
- Section infos are written as **UBO blocks**: "Chunk Sections UBO", mapped ring, each block padded
  to `minUniformOffsetAlignment` (`DynamicGpuData` L87-98; `DynamicGpuDataStorageMapped` L29).
- One `RenderPass.Draw` record plus a lambda per section-layer, issued through
  `drawMultipleIndexed(draws, defaultIndexBuffer, …, ["ChunkSection"], sectionUbos)`.
- Per draw, the frontend calls `setUniform("ChunkSection", ubo)` (a `HashMap.put`), then
  `setVertexBuffer(slot, buffer.slice())` (allocates a slice), optionally `setIndexBuffer`, then
  `backend.drawIndexed(n, 1, firstIndex, baseVertex, 0)` (`FrontendRenderPass` L332-399).

**Per-pass terrain bindings** (`renderLayers` L82-103):
- `TerrainUniform` = `{mat4 ModelViewMat (rotation only); ivec2 TextureSize}` from
  `writeTerrainTransform` (`LevelRenderer` L854/956);
- `Sampler0` = block atlas, with `chunkLayerSampler` (clamp, linear min/mag, anisotropy per option,
  L425-437);
- `Sampler2` = lightmap.
- The default uniforms `Projection`, `Fog`, `Globals` and `Lighting` come from `bindDefaultUniforms`
  (`RenderSystem` L367-387).

**Shader** (`assets/minecraft/shaders/core/terrain.vsh`/`.fsh`):
- position = `Position + (ChunkPosition - CameraBlockPos) + CameraOffset` (L33). `CameraBlockPos`
  and `CameraOffset` come from `Globals` (`GlobalSettingsUniform` L29-36).
- The lightmap is sampled in the vertex stage (L39).
- The fade (`ChunkVisibility`) mixes toward the fog colour beyond 16 blocks (L45-47).
- The fragment shader does manual "nearest with screen-space AA" sampling: `dFdx`/`dFdy` +
  `textureGrad`. In RGSS mode this becomes 8 × `textureLod` + 1 (`texture_sampling.glsl`).

### 4.7 Section-related per-frame CPU work (render thread)

| Step | Complexity | Notes |
|---|---|---|
| Dirty scan | O(visibleSections) | Array lookups; region copies for dirty sections |
| `extractSectionDrawGroups` | O(visible × 3) | Up to 6 HashMap gets + 3–5 small allocations per section, `ChunkSectionLayer.values()` clone per section (L744), all under `copyLock`, which **contends with workers memcpy'ing into staging under the same lock** |
| Ring writes | O(draws) | Per-element `ByteBuffer.position` + puts |
| Resort scheduling | O(nearby + visible/8) | |
| Uploads | O(new meshes) | TLSF + 1 copy command each |
| Occlusion partial BFS | O(changed) | |

---

## 5. Entities, block entities, particles: the "feature" pipeline

1. **Extract** (§1.3) produces `EntityRenderState` and `BlockEntityRenderState` objects, newly
   allocated each frame.
2. **Submit** (`LevelRenderer.submitEntities` L1003-1012, `submitBlockEntities` L1014-1027): renderers
   call `SubmitNodeCollector.submit*`.
   - `SubmitNodeStorage` keys collections by an `order` int in an `Int2ObjectAVLTreeMap`
     (`SubmitNodeStorage` L32, L55-58).
   - Each `SubmitNodeCollection` routes submits into phases: solid, shadows, name tags, texts, shape
     outlines, translucent blocks/items, translucent models, custom geometry, translucent gizmos,
     breaking overlay, water mask, after-terrain, always-on-top, outline (`SubmitNodeCollection`
     L51-118).
   - With OIT on, all translucent phases alias a single `oitTranslucent` phase (L70-88).
   - Submits copy their pose matrices (`new Matrix4f(...)`).
3. **Prepare** (`FeatureRenderDispatcher.prepareFrameWithContext` L80-113):
   - Phases are sorted. `SimpleFeatureRenderPhase` batches by `BatchableSubmit.batchKey()` (the
     `RenderType`); `TranslucentFeatureRenderPhase` sorts back-to-front by distance.
   - Then `prepareGroup` per group. For models (`ModelFeatureRenderer.prepareModel` L25-37):
     `model.setupAnim(state)`, then `model.renderToBuffer(...)`, which **transforms every cube vertex on
     the CPU** into a `BufferBuilder` over the shared `StagedVertexBuffer`.
   - `RenderTypeFeatureRenderer.Group.getOrAddDraw` (L98-111):
     - `RenderType.prepare()` writes a `DynamicTransforms` UBO through `writeTransform`, de-duplicated
       against the previous value (`DynamicGpuDataStorageMapped` L59-82), and resolves textures and
       samplers (`RenderType` L60-77);
     - `drawRenderTypes.indexOf` is a linear search.
   - `prepareGroup` re-walks *all* previous groups' draws on every call (L38-42). That is quadratic
     in the number of groups.
4. **Upload** (`StagedVertexBuffer.upload` L90-124, `uploadDrawsToBuffers` L126-168):
   - acquire a pooled staging buffer (`MAP_WRITE|CLIENT_STORAGE|COPY_SRC`), map it, memcpy every
     draw's slices in, and write sorted indices for quad-sorted translucent types;
   - `copyToBuffer` into a pooled vertex buffer (`VERTEX|COPY_DST`) and, if needed, an index buffer;
   - this happens **once per `prepareFrame`**: level (L204), item-in-hand (`GameRenderer` L404),
     screen effects (L702), and the GUI's own instance (`GuiRenderer` L128).
5. **Pool churn** (`GpuBufferPool.endFrame` L350-363):
   - buffers used this frame are fenced;
   - **buffers that sat in `available` unused this frame are closed (destroyed)**;
   - completed ones are recycled.

   This runs before `submit()`, so frame N's fence is never complete at its own `endFrame`. Whenever
   the GPU is more than about one frame behind, new buffers are created every frame, sized in 256 KiB
   steps. The backend should pool `MTLBuffer`s internally (or sub-allocate from a heap) so that
   `createBuffer`/`close` is cheap, and must defer real destruction until GPU completion.
6. **Execute** (`PreparedFrame.execute*` L201-303 → `PreparedRenderType.draw` L37-56). Per draw:
   `pushDebugGroup(lambda)`, `setPipeline`, optional scissor, `bindDefaultUniforms` (4 named
   uniforms), `setUniform("DynamicTransforms")`, `setVertexBuffer(0, vb.slice())`, textures by name,
   `setIndexBuffer`, `drawIndexed(count, 1, firstIndex, baseVertex, 0)`.

**Culling summary**
- Entities: frustum plus section-compiled checks only. **No occlusion test.** Mobs in caves behind
  terrain are fully extracted, animated, CPU-skinned and drawn (`LevelExtractor` L291-309;
  `EntityRenderer.shouldRender` L61-88).
- Block entities: limited to sections in `visibleSections` (cave-culled + frustum-culled), with no
  per-BE frustum test (`LevelExtractor` L322-351).
- Particles: frustum offset −3 (`LevelExtractor` L218). One draw per particle layer per group
  (`QuadParticleFeatureRenderer` L80-97). Vertices are rebuilt on the CPU every frame.

---

## 6. Sky, clouds, weather, world border, lightmap, animated textures

- **Sky** (`SkyRenderer.render` L129-170+):
  - A *separate* "Sky" render pass on main C+D.
  - Static vertex buffers.
  - Each element calls `writeTransform` and binds 4 default uniforms plus its own.
  - Allocates a new `PoseStack` per frame.
- **Clouds** (`CloudRenderer` L126-243):
  - Cell faces live in a uniform texel buffer `MappableRingBuffer` with usage `UNIFORM_TEXEL_BUFFER|MAP_WRITE`
    (L144). It is rebuilt only when the cell, relative camera position or status changes (L170-187).
  - There is a per-frame UBO (mapped, L192-197).
  - Drawn as `drawIndexed(6*quads)` with **no vertex buffer** (`setVertexBuffer(0, null)`). The
    vertex shader fetches from the texel buffer.
  - The default `cloudRange` is **128 chunks** (`Options` L192-197), i.e. a radius of about 171
    cells of 12 blocks (L136-137).
    - Each occupied cell emits a top or bottom face plus the camera-facing sides (`buildMesh` L245+,
      faces L307-345), giving
      **tens of thousands of quads** per frame. With OIT on they are drawn three times.
    - It also pushes `depthFar` to 2048 blocks (`Camera` L92).
    - A lower cloud range, or `CloudStatus.FAST`, is a cheap GPU saving.
- **Weather**: CPU-built quad columns are written with `writeToBuffer` into one persistent vertex
  buffer every frame while it rains (`WeatherEffectRenderer` L109-142). This needs ordered-write
  semantics.
- **World border**: `writeToBuffer` only when rebuilt (`WorldBorderRenderer` L102).
- **Lightmap**: a 16×16 render pass, only when `needsUpdate` (about 20 Hz) (`Lightmap` L60-91).
- **Animated textures**: one render pass per atlas mip level at 20 Hz, load/store on the atlas
  (`TextureAtlas` L246-262).

---

## 7. `DynamicGpuData` and other per-frame GPU data

| Buffer | Type | Usage | Written | Read | Source |
|---|---|---|---|---|---|
| Dynamic Transforms (`{mat4 ModelView; mat4 TextureMat; vec4 ColorModulator; vec3 ModelOffset}`, ~160 B → padded to align) | `DynamicGpuDataStorageMapped` → 3× `MappableRingBuffer`, rotated per frame, grows ×2 | `UNIFORM\|MAP_WRITE` | `map()` **per element** inside or outside passes | any pass | `DynamicGpuData` L20-32, L59-85 |
| Terrain UBO (mat4 + ivec2) | mapped ring | `UNIFORM\|MAP_WRITE` | once per frame | terrain | L83-85 |
| Chunk Sections UBO | mapped ring | `UNIFORM\|MAP_WRITE` | once per frame (separate path) | terrain | L87-98 |
| Chunk Sections Instanced | **single** buffer + CPU shadow | `VERTEX\|COPY_DST` | `writeToBuffer` once per frame | terrain (MDI) | L100-111 |
| Chunk Sections Command Buffer | mapped ring | `INDIRECT\|MAP_WRITE` | once per frame | terrain (MDI) | L113-119 |
| Projection (level, 3D HUD, GUI, post) | **single** 64 B buffer each | `UNIFORM\|COPY_DST` | `writeToBuffer` when changed (level: every frame) | subsequent passes | `ProjectionMatrixBuffer` L22-50 |
| Globals | **single** buffer | `UNIFORM\|COPY_DST` | `writeToBuffer` every frame | all passes | `GlobalSettingsUniform` L14-41 |
| Fog, Lighting, Lightmap, Cloud UBO, PostPass `SamplerInfo` | mapped rings (3-deep) | `UNIFORM\|MAP_WRITE` | per frame or per use | — | `FogRenderer` L48-85, `PostPass` |

Rules the backend must honour:
- `map()` may be called while a render pass is open (e.g. the sky writes transforms inside its pass).
  On Metal it must be a pointer computation into a shared-storage buffer, not a GPU synchronisation.
  `MappedView.close()` must be cheap and must not flush.
- `MappableRingBuffer` gives 3-frame-deep reuse; `currentBuffer()` blocks on the fence from three
  rotations ago (L36-45). **Keep at most 2 frames in flight**, or those waits become the frame pacer.
- `DynamicGpuDataStorageMapped.endFrame` closes the *old* ring buffers after a resize while the
  current frame still references them (L37-48). **`GpuBuffer.close()` must defer destruction until
  the GPU has finished with every use**, including work that is recorded but not yet submitted.
- `writeToBuffer` and `copyToBuffer` are illegal inside a render pass (`FrontendCommandEncoder`
  L292-337). They must take effect in queue order, after previously recorded GPU reads.

---

## 8. Post-processing

- `GameRenderer.update` always requests `minecraft:end_of_frame` (L432). It is a resource-pack hook
  with no vanilla JSON (`ShaderManager` L352).
  - The first frame logs a warning (`isPostEffectValid` L250-254) and marks it failed.
  - After that, extract filters it out (L459-463).
  - Vanilla therefore applies **no** post chain by default. `consistentDepthRequired` = false, so the
    hand renders into main depth.
- Optional chains:
  - spectator `creeper`/`spider`/`invert` and player effects;
  - `blur` (menus) with 6 passes (`assets/minecraft/post_effect/blur.json`);
  - `entity_outline` with 4 passes, plus the outline pass and `blitEntityOutline`, when glowing
    entities exist.
- Each `PostPass` (`PostPass.addToFrame`):
  - `map()`s its `SamplerInfo` ring;
  - creates a render pass on its output target;
  - draws one full-screen triangle.

  Transient targets come from `CrossFrameResourcePool(3)` and are cleared on acquire
  (`RenderTargetDescriptor.prepare` L19-29).
- With post chains active, `consistentDepthRequired` = true. The hand uses `hud3DTarget` depth, and
  extra full-screen `INTEGRATE_DEPTH` passes appear (`GameRenderer` L724-740;
  `LevelRenderer` L500-511).

---

## 9. GUI renderer (`net/minecraft/client/gui/render/GuiRenderer.java`)

- Immediate mode. Every frame:
  - prepare picture-in-picture, items (item atlas) and text;
  - **sort all elements** by (scissor, pipeline sort key, texture) (L68-79, L162-174);
  - build vertices into its own `StagedVertexBuffer` (768 KiB), upload (L128), then draw (L184-205).
- A new draw starts on any pipeline, texture or scissor change (L229-245). Each draw does
  `setPipeline`, `setVertexBuffer`, scissor, up to 3 named samplers, `setIndexBuffer`, `drawIndexed`
  (L374-402).
- **Blur effect**: draws are split into "before blur" and "after blur" passes, with
  `clearDepthTexture` and the blur post chain between them (L198-203).
- HUD cost is small in the measured profile (3 of 121 Java samples), but grows with chat, F3 or open
  screens.

---

## 10. Frame graph (`com/mojang/blaze3d/framegraph/FrameGraphBuilder.java`)

- Rebuilt every frame (`new FrameGraphBuilder()`, `LevelRenderer` L207).
- `execute` (L50-77):
  1. `identifyPassesToKeep`: passes reachable from writers of external resources, plus
     `disableCulling` passes.
  2. Topological ordering (`resolvePassOrder`, throws on cycles).
  3. Lifetimes (`assignResourceLifetimes`).
  4. Then **synchronously**: acquire transient resources from the `GraphicsResourceAllocator`
     (`CrossFrameResourcePool`), run the pass lambda, release.
- No barriers, no async compute, no aliasing beyond pool reuse.
- The frame graph sees no render passes. Each lambda creates its own `RenderPass` (or several) through
  `CommandEncoder`.
- Practical consequence for a backend: frame-graph passes map 1:1 to (1..n) RenderPearl render
  passes. **The backend sees only the linear command stream.** Merging has to happen backend-side,
  by deferring pass ends, or game-side.

---

## 11. Backend contract: semantics this game code relies on

1. **Ordering.** GPU commands execute in recording order across encoders and across frames:
   - copies and writes after earlier reads;
   - render passes after preceding copies;
   - next frame after this one.

   Examples:
   - terrain uploads reuse TLSF ranges freed in the same frame (`UberGpuBuffer` L59-64, L145-155);
   - the Globals and Projection UBOs are overwritten every frame;
   - chunk-section instance data is overwritten every frame.

   On Metal 3 this holds with tracked resources on one queue. **On MTL4, or with untracked heap
   resources, the backend must insert explicit barriers/fences** between blit and render stages
   (render→blit WAR, blit→render RAW).
2. **Deferred destruction.** `close()` on `GpuBuffer`/`GpuTexture` can happen while uses are pending
   (`DynamicGpuDataStorageMapped.endFrame`, `GpuBufferPool.endFrame`, `UberGpuBuffer` heap release
   L115-125). MTL4 command buffers do not retain resources. Retire on a completed `MTLSharedEvent`
   value.
3. **Fences** (`createFence`, `awaitCompletion(timeout)`) are created mid-frame before `submit()`.
   `awaitCompletion(0)` is a poll and happens every frame in several places. A blocking wait on an
   unsubmitted fence must flush.
4. **Clip-space and depth conventions** (game side):
   - reverse-Z: projection is built with near and far swapped (`Projection.getMatrix` L67-71);
   - depth cleared to 0.0 (`RenderSystem.DEFAULT_DEPTH_CLEAR_VALUE` L43);
   - compare `GREATER_THAN_OR_EQUAL` (`DepthStencilState.DEFAULT`);
   - `DeviceInfo.isZZeroToOne()` selects the [0,1] projection. **Report true for Metal.**
5. **Index types** are mixed per draw (SHORT or INT). The shared quad index buffer is usually INT.
   `firstIndex` × `indexType.bytes` is always aligned (index heap alignment is 8).
6. **Base vertex and base instance** are used everywhere. Terrain uses up to ~4.8M `baseVertex`
   (128 MiB / 28 B). Terrain MDI uses non-zero `baseInstance` with a per-instance vertex stream:
   the instance-rate attribute must be fetched at `baseInstance + instanceIndex`.
7. **Debug labels** are `Supplier<String>`s, created per draw, group and pass, e.g.
   `PreparedRenderType` L38 and `ChunkSectionsToRender` L99. **Evaluate them only when a capture or
   debug layer is active.** Evaluating them always means string concatenation per draw.
8. **Frontend validation is minimal in production.** `STRICT_VALIDATION = IS_RUNNING_IN_IDE`
   (`FrontendGpuDevice` L36). In release, the frontend checks only:
   - pipeline bound;
   - attachment/pipeline format match on `setPipeline`;
   - UBO offset alignment;
   - indirect usage, size and alignment;
   - feature gates.

   The backend must not crash on, and need not re-validate, closed or unbound buffers in hot paths
   (keep checks cheap).
9. **Uniform semantics.**
   - `setUniform(name, …)` stores into a per-pass `HashMap`.
   - `setPipeline` **re-applies every stored uniform** to the backend (`FrontendRenderPass` L125).
   - Pipelines differ in which names they consume (`uniformIndices`).
   - The backend therefore receives many redundant `setUniform(index, value)` calls (4 defaults per
     draw from `bindDefaultUniforms`, plus re-application on every pipeline change). **De-duplicate
     against currently bound state.**

---

## 12. Metal mapping (game-path specific)

| Game behaviour | Metal mapping | Pitfalls |
|---|---|---|
| One `submit()` per frame after `blitFromTexture`, then `present()` | One `MTLCommandBuffer` (or `MTL4CommandBuffer`) per frame. In `blitFromTexture`: obtain the drawable **here**, encode the copy (render pass or blit; format swizzle RGBA8→BGRA8 needs a render pass or `MTLTextureSwizzle` view), call `presentDrawable:` before commit. `present()` = bookkeeping. | **Do not call `nextDrawable` in `acquireNextTexture()`**: the game calls it at frame *start* (`Minecraft` L1273), which would block CPU work until a drawable frees up. `CAMetalLayer.displaySyncEnabled = NO` for vsync off; `maximumDrawableCount` 2–3. |
| Standalone `clearColorTexture`/`clearDepthTexture`/`clearColorAndDepthTextures` | Record as **pending clears**. Fold into the next render pass that uses the texture (`loadAction = MTLLoadActionClear`). Two back-to-back clears collapse into one. Only when no pass follows, emit a minimal render pass with clear load action and store. | C1+C2+Sky collapse to one encoder. C3 → hand pass depth load; C4 → GUI depth load. |
| Many consecutive passes on identical attachments (Sky→Main, Hand→Screen effects) | **Lazy encoder end**: keep the `MTLRenderCommandEncoder` open after `RenderPass.close()`. If the next `createRenderPass` has the same attachments, load actions and no intervening command, keep encoding (reset viewport and scissor). | Any `writeToBuffer`/`copyToBuffer`/clear/`createBuffer` with data in between forces an end, unless the backend hoists uploads into a pre-pass blit when they touch no resource of the open encoder. Game-side alternative: draw the sky inside the main pass. |
| Store actions unknown at pass end | Use `MTLStoreActionUnknown` when the descriptor is built, then `setColorStoreAction:atIndex:`/`setDepthStoreAction:` before `endEncoding`. Choose `DontCare` when the next recorded use of that texture is a full clear or overwrite (main depth after Main, depth after GUI). | Must look ahead by one command. That is natural with lazy encoder end. |
| Terrain `drawIndexedIndirect(slice, count)` with 20 B records | Records match `MTLDrawIndexedPrimitivesIndirectArguments`. Options: (a) loop `drawIndexedPrimitives:indexType:indexBuffer:indexBufferOffset:indirectBuffer:indirectBufferOffset:` (count calls); (b) **`MTLIndirectCommandBuffer`** with inherited pipeline and buffers, filled on the CPU from the mapped ring or by a compute kernel, then `executeCommandsInBuffer:withRange:` (1 call per group); (c) since the ring is CPU-written shared memory, read the records and emit direct draws (no GPU indirection). | Report `maxDrawIndirectDrawCount` large (e.g. 65,536+). `nonZeroFirstInstance = true`, `drawIndirect = multiDrawIndirect = true`, `multiDrawIndirectHasKnownIssues = false`. The ICB route needs `MTLIndirectCommandTypeDrawIndexed`, `inheritBuffers`/`inheritPipelineState`, and base vertex/instance support in ICB draws. |
| Separate-path per-draw `ChunkSection` UBO | `setVertexBufferOffset:`/`setFragmentBufferOffset:` on the already-bound ring, or `setVertexBytes` (16 B) | Report `minUniformOffsetAlignment` small (16). The game pads every ring block to it (`DynamicGpuDataStorageMapped` L29). |
| `CHUNK_DATA_INSTANCED` (binding 1, step 1) | `MTLVertexBufferLayoutDescriptor.stepFunction = PerInstance`, `stepRate = 1`, `stride = 16` | Confirm the per-instance fetch includes `baseInstance` (see open questions). |
| Uber heaps (`VERTEX\|INDEX\|COPY_*`, never mapped) | `MTLStorageModePrivate` buffers; or one `MTLHeap` per layer with placement. Blit from the staging ring. | Heap-allocated resources are **untracked** by default: add `MTLFence`/barrier between the upload blit and the next frame's render encoder. Include them in the residency set (MTL4). |
| Staging, rings, feature vertex pools (`MAP_WRITE`) | `MTLStorageModeShared` (write-combined: `MTLCPUCacheModeWriteCombined` for write-only rings) | `CLIENT_STORAGE` hint (4) → shared. `map()` returns `contents()+offset` without synchronisation. |
| `writeToBuffer` on single small buffers (Globals, Projection, ChunkSectionsInstanced, weather VB) | Option A: copy into a per-frame upload ring + `MTLBlitCommandEncoder copyFromBuffer`, **batched**: all writes between two passes share one blit encoder. Option B (fewer encoders): **buffer renaming**. Each `GpuBuffer` points at a backing allocation. For a whole-buffer write, allocate a fresh range from a ring and repoint; already-encoded commands keep the old binding. For partial writes, copy the remainder too. | Blit encoders between render encoders cost encoder switches on TBDR. Renaming removes them for the per-frame UBOs. |
| Fences | One `MTLSharedEvent` per queue. Each `createFence` returns `(event, value of the next commit)`. `awaitCompletion(0)` compares `signaledValue`. A blocking wait does `commit` if needed, then `waitUntilSignaledValue`. | Never block the render thread in `createFence`. |
| Render-thread QoS | Set the render thread to `QOS_CLASS_USER_INTERACTIVE` (pthread QoS) so it stays on P-cores; leave workers at a lower QoS. | Verify current QoS (open question). |
| Present at native Retina | Optional: render the world at a scale < 1 and upscale in `blitFromTexture` with **MetalFX** (`MTLFXSpatialScaler`; temporal needs motion vectors the game does not produce) | Needs game-side render-scale support (MainTarget size ≠ window size); GUI should stay native. |

---

## 13. Measured profile data (existing JFR, read-only analysis)

Inputs: `results/raw/prof-vulkan.jfr` and `prof-opengl.jfr`. RD 12, framebuffer 3600×2260, spawn
fly-over scene (`results/BASELINE.md`). Aggregated with `jfr print --json`. Sampling periods are
inferred from the recording's `jdk.ActiveSetting` events (JFR "profile" settings): Java 10 ms,
native 20 ms. Sample counts are small, so treat them as rankings, not percentages.

**Render thread, Vulkan (median 390 FPS, 2.56 ms)**
- **Native**: 1034 of 1063 samples inside `FrontendCommandEncoder.submit` → `vkQueueSubmit2KHR`.
  MoltenVK encodes or waits there.
- **Java** (121 samples):

  | Method (inclusive) | Samples |
  |---|---|
  | `GameRenderer.render` | 79 |
  | `renderLevel` | 74 |
  | `LevelRenderer.render` | 50 |
  | **`prepareChunkRendersIndirect`** | **35** |
  | **`extractSectionDrawGroups`** | **29** (of which `HashMap.getNode` in `UberGpuBuffer.getAllocation`: 11) |
  | `render3dHud` (hand submit, feature prepare, staged upload) | 24 |
  | `FrameGraphBuilder.execute` | 15 |
  | `GameRenderer.extract` | 13 |
  | `FeatureRenderDispatcher.prepareFrame` | 11 |
  | `SkyRenderer.render` | 7 |
  | `FrontendRenderPass.setUniform` (HashMap put inside `setPipeline` re-application) | 6 |

- **Allocation** (`jdk.ObjectAllocationSample`): render-thread sampled weight is ~3.7 GB over 25 s,
  about 150 MB/s or about 0.38 MB/frame.
  - One PoseStack site absorbs a disproportionate share of the weight; this is a sampling artefact.
    Read the rest as a ranking.
  - Real top sites: `extractSectionDrawGroups` (`Object[]`, `IndexedDraw`, `ChunkSectionInfo`),
    `getRenderSectionSlice` (`RenderSectionBufferSlice`), `ChunkSectionLayer.values()` clones, and
    `EnvironmentAttribute` sampling `Vec3`s.
  - Then render-pass objects (`FrontendRenderPass`, `RenderPassDescriptor$Builder`, Vulkan render
    pass), `GpuBufferSlice`s from `bindDefaultUniforms`/`setUniform`, GUI nodes, and render states.
- **CPU load**: `jdk.ThreadCPULoad` for the render thread is 1.6–2.9% of the machine, i.e. about
  0.2–0.3 of one core. JVM user time is 3–5% of the machine. **The render thread is mostly idle and
  blocked**, which fits being GPU- or drawable-bound at 8.1 MP.

**OpenGL (231 FPS)**
- 990 native samples in `SDL_GL_SwapWindow`: Apple's GL-on-Metal does its work and waits at swap.
- 46 samples in `glBlitFramebuffer` (present blit).
- Per-section `glDrawElementsInstancedBaseVertex` appears; GL lacks MDI, so it uses the separate path.
- FPS is the same windowed and native (BASELINE), so it is CPU/driver-bound.

---

## 14. CPU hotspots, ranked (game side)

1. **Backend encode/submit.** It dominates today (§13). It is not game code, but the game decides the
   call volume. There are ~1–2k terrain draws per frame. Every feature or GUI draw re-binds 4–6
   named uniforms, and every pipeline change re-applies all uniforms.
2. **Terrain draw-list rebuild every frame** (`LevelRenderer` L722-964). The largest Java-side cost
   (~25–30% of Java samples) and the largest allocation source:
   - HashMap lookups keyed by mesh;
   - per-draw records;
   - `values()` clones;
   - a fresh `Int2ObjectOpenHashMap`;
   - ring writes;
   - all under `copyLock`, which contends with workers' staging memcpy.
3. **CPU vertex generation for features**: entity `ModelPart` transforms, item quads, particles,
   text. It is followed by a triple copy: `BufferBuilder` → staging map → GPU copy. There are up to
   3 `prepareFrame` uploads per frame plus the GUI (`StagedVertexBuffer` L126-168). Cost scales
   linearly with visible entities and particles.
4. **Extract**:
   - entity/BE render-state allocation per entity per frame;
   - dirty-section scan over all visible sections;
   - **region copies on the render thread** (spikes);
   - `EnvironmentAttribute` sampling (fog/sky colour).
5. **Render-pass object churn**: `createRenderPass` allocates descriptors, builders and maps per pass,
   about 10+ per frame. Plus `new FrameGraphBuilder`, lambdas, `HashSet` in `preparePostEffects`,
   and `new PoseStack`s.
6. **Occlusion partial updates and applyFrustum.** Bursty: during turning and while chunks are loading.
7. **GUI rebuild and sort.** Small with only the HUD.

---

## 15. Optimisation opportunities, ranked by expected FPS impact

Impact assumes the 1000 FPS target at native Retina on M5. "B" = Metal-backend-only, "G" = game patch.

| # | Opportunity | Side | Expected impact | Effort / risk |
|---|---|---|---|---|
| 1 | **Collapse clears into load actions, merge identical-attachment passes, deferred store actions** (§12). Removes about 3–4 full-screen load/store round trips of C+D per frame (~47 MB each at 3024×1964 before lossless compression). | B (+G: sky inside main pass, drop redundant clear C1 when the level renders, hand+screen effects in one pass) | **High** when GPU/bandwidth-bound, which the native-res Vulkan run appears to be | Medium. Needs one-command look-ahead in the encoder. |
| 2 | **Terrain multi-draw via ICB or tight loop**, MDI path enabled through `DeviceFeatures`/limits (§12) | B | **High** for CPU/encode: 1–2k Metal draw encodes → a few `executeCommandsInBuffer` calls | Medium. Validate base vertex/instance in ICB. |
| 3 | **Lean encoder**: de-duplicate redundant `setUniform`/`setVertexBuffer`/`setPipeline`; precomputed name→slot tables; no label evaluation; argument tables (MTL4) or bind-once ring buffers + `set*BufferOffset`; minimise Java↔native crossings (batch commands into a native stream, replay in C/Swift) | B | **High** (the encode/submit path dominates today) | Medium–High |
| 4 | **Persistent terrain draw list.** Store allocation offsets and draw records on `CompiledSectionMesh`, not in `UberGpuBuffer.allocationMap`. Keep a cached `ChunkSectionsToRender` and rebuild only when `visibleSections`, a mesh, the translucent order, or a fading section changes. Remove per-frame allocations (cache `ChunkSectionLayer.values()`, reuse lists). | G | Medium–High CPU (~25–30% of Java render time measured, plus GC pressure) | Medium. Needs persistent buffers (the rings reset every frame). |
| 5 | **Render scale + MetalFX spatial upscale** (world at ~0.67–0.75 scale, GUI native) | G+B | **High** GPU (2–2.25× fewer pixels), with a visual trade-off | Medium. Optional user setting. |
| 6 | **Compact terrain vertex format**: 28 B → 16–20 B (pos `3×u16` fixed-point with offset bias, UV `2×u16` normalised, light `2×u8`, colour `RGBA8`). Add a 16-bit shared quad index buffer by capping per-draw vertex count. | G (shader + mesher + `decodeQuadCentroids` + vertex format) | Medium GPU (vertex fetch, memory) + faster uploads | Medium. Check the precision of fluid heights and UV atlas ≥ 8k. |
| 7 | **Avoid `writeToBuffer` blit encoders** for per-frame small buffers via buffer renaming (§12); batch all copies between passes into one blit encoder | B | Medium (fewer encoder switches, no blits for the Globals/Projection/instance stream) | Low–Medium |
| 8 | **UMA-aware feature vertex upload**: write vertices straight into a `MAP_WRITE\|VERTEX` ring; stop destroying unused pool buffers every frame (`GpuBufferPool.endFrame` L357-360) | G (+B: cheap pooled `createBuffer`) | Medium with many entities, particles or GUI | Low |
| 9 | **Overlap CPU and GPU/encode**: (a) a backend encoder thread consuming a recorded command list; (b) game: double-buffer `GameRenderState` and run extract N+1 while frame N encodes or submits | B (a), G (b) | High when CPU-bound after 1–4 | High (thread safety, `RenderSystem` thread asserts) |
| 10 | **Entity occlusion culling via section visibility**: skip entities whose section is not in this frame's `visibleSections` (bitset by `RenderSection.index`) | G | Medium in mob-heavy or cave scenes, ~0 otherwise | Low |
| 11 | **Move region copying off the render thread** (copy-on-write palettes or copying on workers) | G | 1%/0.1% lows during chunk loading and edits | Medium |
| 12 | **Frame pacing**: lazy drawable acquisition, at most 2 frames in flight (stay inside the 3-deep rings), render thread at user-interactive QoS | B | Medium (removes stalls, raises sustained FPS) | Low |
| 13 | Keep **improved transparency off**. If wanted, re-implement OIT with Apple tile memory (imageblocks / raster-order groups) inside one pass | G | Very high when on (removes ~10 passes and RGBA32F/16F MRT traffic) | High for the tile-memory version |
| 13b | Clouds: lower the default `cloudRange` (128 chunks → tens of thousands of quads per frame), or use FAST clouds | User option / G | Low–Medium GPU | Trivial |
| 14 | Cutout cost on TBDR: `discard` in CUTOUT terrain hurts hidden-surface removal; vanilla already draws SOLID→CUTOUT→TRANSLUCENT. The `cutoutLeaves=false` option moves leaves to SOLID. | User option / G | Low–Medium (forest scenes) | Trivial |
| 15 | Small Java wins: no `HashSet` per frame in `preparePostEffects`; reuse `FrameGraphBuilder`/`PoseStack`/descriptor builders; fix the quadratic `prepareGroup` loop (`RenderTypeFeatureRenderer` L38-42) and the `indexOf` search (L100); one `Util.getMillis()` per frame in the BE loop | G | Low individually; it adds up and reduces GC | Low |

---

## 16. `DeviceFeatures` / limits / hints the Metal backend should report

| Field | Recommended | Game effect | Source |
|---|---|---|---|
| `limits.maxDrawIndirectDrawCount` | large (≥ 65,536) | Enables terrain MDI; the chunking loop uses it | `LevelRenderer` L178; `ChunkSectionsToRender` L133-155 |
| `features.nonZeroFirstInstance` | true | Required for MDI (`baseInstance`) | `LevelRenderer` L179 |
| `features.drawIndirect`, `multiDrawIndirect` | true | Frontend gates for `drawIndexedIndirect` | `FrontendRenderPass` L307-313 |
| `hints.multiDrawIndirectHasKnownIssues` | false | MDI gate | `LevelRenderer` L180 |
| `hints.writeToBufferIsSlow` + `features.persistentMapping` | true + true | Chunk `StagingBuffer` uses the persistently mapped GPU ring + `copyToBuffer` (no double copy) | `StagingBuffer` L16-20 |
| `limits.minUniformOffsetAlignment` | 16 | Smaller UBO ring blocks (transforms, chunk-section UBOs, lighting, sprite UBOs) | `DynamicGpuDataStorageMapped` L29, `Lighting` L29, `TextureAtlas` L102/149 |
| `isZZeroToOne` | true | Projection and GLSL define `RENDERPEARL_DEPTH_IS_ZERO_TO_ONE` | `Projection` L69, `GlslCompiler` L81-83 |
| `features.shaderDrawParameters` | true (with MSL `[[instance_id]]`/`[[base_instance]]` semantics) | `RENDERPEARL_INSTANCE_INDEX_INCLUDES_BASE_INSTANCE` | `GlslCompiler` L89-91 |
| `hints.isExplicitDepthRequired` | consider true, or set `MTLCompileOptions.preserveInvariance` | Invariant depth for OIT re-draws and `EQUAL`-depth pipelines (`RenderPipelines` L469) | `GlslCompiler` L85-87 |
| `features.wireframeFillMode` | true (`MTLTriangleFillModeLines`) | Debug wireframe pipelines | `RenderSystem` L355-357 |
| `limits.maxAnisotropy` / `maxTextureSize` / `maxColorAttachments` | 16 / 16384 / 8 | Options, atlas size, OIT MRT | `Options` L1211, `TextureAtlas` L59 |
| `multiDrawDirect*` | optional (unused by game code) | Not used by the game | grep: no game call sites |

---

## 17. Quick reference: file and line index

- Frame loop: `net/minecraft/client/Minecraft.java` L910-919, L1166-1243, L1245-1358
- GameRenderer: extract L442-467, render L469-518, renderLevel L635-676, render3dHud L678-727, post effects L520-560
- LevelRenderer: ctor (MDI gate) L174-181, render L184-324, main pass L385-473, OIT L556-667, classic transparency L683-707, draw-group extract L722-809, prepareChunkRenders L811-862, indirect L899-964, compileSections L966-995, resort L1217-1257
- LevelExtractor: extract L108-241, entities L261-309, block entities L315-368, applyFrustum L503-512
- Chunk: `SectionRenderDispatcher` L54-152 (heaps, staging, uploads), L201-571 (tasks); `SectionCompiler` L53-131; `ChunkSectionsToRender` L82-191; `ChunkSectionLayer` L10-12; `SectionTaskDynamicQueue` L18-53; `RenderRegionCache` L13-42; `SectionCopy` L26-41
- Memory: `com/mojang/blaze3d/vertex/UberGpuBuffer.java` L41-159, `StagingBuffer.java` L16-183, `TlsfAllocator.java` L137+
- Culling: `SectionOcclusionGraph` L96-209, L255-362; `Octree` L35-218; `culling/Frustum` L44-100
- Dynamic data: `DynamicGpuData` L20-134; `DynamicGpuDataStorageMapped` L26-195; `DynamicGpuDataStorageNonMapped` L27-168; `MappableRingBuffer` L10-65
- Features: `FeatureRenderDispatcher` L63-430; `RenderTypeFeatureRenderer` L33-111; `PreparedRenderType` L37-56; `StagedVertexBuffer` L90-392; `SubmitNodeStorage` L31-233; `SubmitNodeCollection` L51-118
- Shaders: `assets/minecraft/shaders/core/terrain.vsh` L33-47, `terrain.fsh` L35-50, `include/texture_sampling.glsl`
- Frame graph: `com/mojang/blaze3d/framegraph/FrameGraphBuilder.java` L50-160; `resource/CrossFrameResourcePool.java`; `resource/RenderTargetDescriptor.java` L19-29
- Frontend semantics referenced: `com/mojang/renderpearl/frontend/FrontendRenderPass.java` L104-173, L302-399, L504+; `FrontendCommandEncoder.java` L233-337; `FrontendGpuSurface.java`

---

## 18. Open questions / to verify

1. **Per-instance fetch with base instance in Metal**: confirm that `MTLVertexStepFunctionPerInstance`
   attributes are fetched at `baseInstance + instance` for both direct and indirect (and ICB) draws.
   The MDI terrain path depends on it (`CHUNK_DATA_INSTANCED`).
2. **ICB limits**: maximum commands per ICB, and whether `inheritBuffers` together with per-command
   `baseVertex`/`baseInstance` works for indexed draws. Also the cost of CPU encoding vs GPU (compute)
   encoding at ~2k draws/frame.
3. **Apple constant-buffer offset alignment**: the documented minimum, believed to be 4 B on Apple
   GPU families. Pick 16 vs 32 for `minUniformOffsetAlignment`.
4. **Bottleneck at native resolution**: is the 405 FPS Vulkan baseline GPU-bound, or blocked in
   MoltenVK drawable acquisition? `vkQueueSubmit2` dominates wall time while the render thread uses
   only ~0.25 core. A Metal System Trace of the baseline would settle it, and would size win #1 vs #2.
5. **Render-thread QoS on macOS**: the JVM is started with `-XstartOnFirstThread`. Check which QoS
   class the render thread actually has and whether it migrates to E-cores.
6. **Lossless framebuffer compression**: whether the M5 applies it to `RGBA8`/`D32F` targets created
   with usage = all (incl. `COPY_*`). This affects how much passes 1 and 5 actually save.
7. **Terrain buffer sizes**: actual bytes per visible section at RD 12 in the bench scene. It decides
   whether more than one 128 MiB heap per layer appears (more MDI groups).
8. Whether vanilla `HintsAndWorkarounds` values reported by the Vulkan backend on MoltenVK already
   enable the persistently mapped staging path. Compare against what the Metal backend should report.
