# Stage 2: chunk meshing, facing buckets and a compact terrain vertex format (Minecraft 26.3)

Scope: how a chunk section becomes GPU geometry, from `SectionCompiler` through `ModelBlockRenderer`,
`FluidRenderer`, `BufferBuilder` and `MeshData`, then the uber heaps and the terrain draw. It is followed
by two game-side designs aimed at the measured vertex-stage cost (`results/GPU-PROFILE.md`: about
1.1 ms of the 2.9 ms GPU frame is vertex-stage work at 3600x2260, RD 12):

1. **Facing buckets.** Each section's quads are stored as 7 contiguous buckets: six axis-aligned
   facings plus "unaligned". The renderer skips buckets that face away from the camera.
2. **Compact terrain vertex.** The vertex shrinks from 28 B to 16 B, and terrain gets a fixed
   16-bit quad index buffer.

All source paths are relative to `src/vanilla`. "L123" means line 123 of the named file. The prior
render-path analysis is `docs/renderpearl/07-game-render-path.md` (cited as "07 §x").

> Naming note: in 26.3 the fluid tesselator is `net/minecraft/client/renderer/block/FluidRenderer.java`.
> There is no `LiquidBlockRenderer`.

---

## 0. Summary

**Geometry volume.** I simulated vanilla meshing on the bench world (method in §3):
- **About 360k terrain quads, or 1.44M vertices, per frame** on average over a 360° turn, at the
  bench camera and RD 12.
- Peak is 512k quads per frame, across about 405 sections (maximum 515).
- That is about 40 MB of vertex data plus 8.6 MB of 32-bit indices fetched per frame.

**Facing buckets remove most of the back-facing work.**
- **43%** of those quads face away from the camera at the bucket level.
- An ideal per-quad back-face test would remove 43.6%, so buckets get almost all of the available
  benefit.
- The terrain pipelines cull back faces (`RenderPipeline` L417: `cull.orElse(true)`; no terrain
  snippet calls `withCull(false)`, `RenderPipelines` L76-90, L346-428).
- Skipping these buckets is therefore lossless. It saves their vertex fetch and vertex shading, which
  the TBDR vertex stage pays today only to discard the triangles.

**Facing is not carried to the vertex buffer today.**
- `BakedQuad.direction()` is the closest axis to the geometric normal, not a guarantee that the quad is
  axis-aligned (`FaceBakery` L109, L184-207).
- `FluidRenderer` passes a constant normal (L426).
- The `BLOCK` fast path drops normals entirely (`BufferBuilder` L313-319).
- So bucket classification belongs **in `BufferBuilder`**: an exact integer test on the 4 quantized
  positions, done once per quad. That single place covers both emitters (§4.1).

**Draw count goes up modestly.**
- A fixed bucket order of **DOWN, NORTH, EAST, UP, UNALIGNED, SOUTH, WEST** lets adjacent visible
  buckets merge into one draw.
- Opaque terrain draws rise from about 807 to about 1,016 per frame (+26%), instead of the ~2.8× a
  naive one-draw-per-bucket scheme would give (§4.3).
- These remain multi-draw-indirect (MDI) records in the existing indirect path. The record format is
  unchanged.

**Translucent stays one sorted draw per section.** Per-bucket draws would break back-to-front order.
- Phase 1: no culling for translucent.
- Phase 2: drop back-facing quads *inside the sorted index list* using the translucency
  point-of-view (POV) octant, with a 1-block margin (§4.7).
- With improved transparency (order-independent transparency, OIT) on, translucent can be bucketed
  like opaque.

**Compact 16 B vertex.**

| Bytes | Field | Format | Contents |
|---|---|---|---|
| 0–7 | `Position` | `RGBA16_UINT` | x, y, z as `(p+8)·2048`; w = `blockLight \| skyLight<<8` |
| 8–11 | `Color` | `RGBA8_UNORM` | Unchanged |
| 12–15 | `UV0` | `RG16_UINT` | `u·65536`, `v·65536` |

- Every offset is 4-byte aligned, as Metal requires (`docs/metal/CONTRACT.md` L584).
- The decode is exact powers-of-two arithmetic. The output is **bit-identical** for every vertex on
  the 1/2048-block grid and every UV on the 2^-16 atlas grid. That covers all full blocks, slabs,
  stairs and the large majority of models, plus all light and colour values (§5.4).

**Per-frame byte savings.** Terrain bytes fetched per frame, combining both designs plus a 16-bit
index buffer:

| Configuration | Bytes per frame |
|---|---|
| Vanilla | 48.9 MB |
| Compact format only | 27.3 MB (−44%) |
| Compact format + opaque buckets | **16.6 MB (−66%)** |

- Vertex-shader invocations drop by 39% with opaque buckets, or 43% with translucent exclusion too.
- Resident terrain mesh memory in the bench world drops from 243 MB to 139 MB.

**Expected GPU time saved: about 0.2–0.45 ms per frame** out of the ~1.1 ms vertex stage. This is a
model, not a measurement (§6.3). Validate it with Metal GPU counters once the backend runs.

---

## 1. How a section is meshed today

### 1.1 Driver: `SectionCompiler.compile` (`net/minecraft/client/renderer/chunk/SectionCompiler.java`)

- It runs on a worker thread inside `CompileTask.doTask` (`SectionRenderDispatcher` L403-482). It
  uses a pooled `SectionBufferBuilderPack` with one `ByteBufferBuilder` per layer
  (`SectionBufferBuilderPack` L11-13). Initial sizes are 4 MiB for SOLID and CUTOUT and 768 KiB for
  TRANSLUCENT (`ChunkSectionLayer` L10-12).
- Per-layer `BufferBuilder`s are created lazily with `PrimitiveTopology.QUADS` and
  `layer.vertexFormat()` (L133-144). The format comes from the layer's pipeline binding 0
  (`ChunkSectionLayer` L52-54), which today is `DefaultVertexFormat.BLOCK`
  (`RenderPipelines` L69, inherited by `TERRAIN_SNIPPET` L76-82).
- The loop walks the 4096 blocks in `BlockPos.betweenClosed` order (L74-114). For each block:
  - a solid-render block marks the `VisGraph` (L78-80);
  - a block entity is collected (L82-87);
  - a fluid goes to `FluidRenderer.tesselate` (L89-92);
  - a `RenderShape.MODEL` block goes to `ModelBlockRenderer.tesselateBlock`, with section-relative
    integer x/y/z passed as floats (L94-106).
- Quads route to a layer in one of two ways:
  - by `quad.materialInfo().layer()` (L64-67);
  - forced to SOLID for leaves when `cutoutLeaves` is false (L68-71, L96; `ModelBlockRenderer` L45-47).
- At the end, each started builder is `build()`-ed into a `MeshData`. TRANSLUCENT is additionally
  `sortQuads`-ed: centroids are sorted back to front from the camera position captured at compile
  time, and the sorted index buffer is written into the same layer `ByteBufferBuilder`
  (L116-126; `MeshData` L68-78).

### 1.2 Block models: `ModelBlockRenderer` (`net/minecraft/client/renderer/block/ModelBlockRenderer.java`)

- `tesselateBlock` (L49-75):
  - collects model parts with a seeded random (L60-61);
  - adds the block's random offset (`BlockState.getOffset`, L64);
  - chooses the ambient-occlusion (AO) path or the flat path (L65-69).
- Both paths iterate, per part:
  - **culled quads** `part.getQuads(direction)` for each of the 6 directions, emitted only if
    `Block.shouldRenderFace` passes (L111-135, L157-184; `Block` L295-325);
  - then **unculled quads** `part.getQuads(null)` (L137-140, L186-189).

  The direction key here is the model's *cullface*, not the quad's facing.
- The per-vertex AO colour and light are prepared into a reusable `QuadInstance` by `BlockModelLighter`:
  - colour = AO × directional shade × tint (`BlockModelLighter` L146-202, L222; tint
    `ModelBlockRenderer` L212-215);
  - light = smooth-blended packed light. Both block and sky components are `& 0xFF` and at most 240
    (`LightCoordsUtil` L9-11, L29-31, L66-88).
- Then `output.put(x, y, z, quad, instance)` (L217) calls `BufferBuilder.putBlockBakedQuad`
  (`SectionCompiler` L64-71). That is the default method `VertexConsumer.putBlockBakedQuad`
  (`VertexConsumer` L65-78): 4 calls of the 11-argument `addVertex(pos + xyz, color, u, v, overlay,
  light, normal)`.

### 1.3 Fluids: `FluidRenderer.tesselate` (`net/minecraft/client/renderer/block/FluidRenderer.java`)

Emits up to 6 faces, each through `addFace` → 4 or 8 `vertex()` calls → `addVertex(…, normal 0,1,0)`
(L338-374, L423-427).

| Face | Geometry | Back face | Source |
|---|---|---|---|
| **Top** | Corner heights are weighted averages (non-grid floats) minus 0.001 | **Second, reversed quad** when `shouldRenderBackwardUpFace`: any of the 3×3 blocks above is neither the same fluid nor solid-render (`FluidState` L76-88) | L124-195; back face L193 |
| **Bottom** | At `y + 0.001` | — | L197-231 |
| **Sides** | Inset 0.001 | **Reversed back face** unless the neighbour is glass or leaves (`addBackFace = !isOverlay`) | L235-334, L331 |

The top face is axis-aligned only when all 4 heights are equal; sloped or flowing tops are not even
planar.

**Consequence:** water surfaces cost 2 quads per block, and exposed water sides cost 2 quads per
face. Exactly one quad of each pair always faces away from the camera.

### 1.4 `BufferBuilder` → `MeshData` (`com/mojang/blaze3d/vertex/`)

- `BufferBuilder` has a hard-wired fast path when `format == DefaultVertexFormat.BLOCK` (L64, L313-319).
  Per vertex it does 6 stores: 3 floats of position, packed RGBA (L155-158), 2 floats of UV, and
  packed light as two shorts (L160-167). The normal and overlay are discarded.
- `build()` → `storeMesh()` (L92-105):
  - takes the `ByteBufferBuilder.Result`;
  - sets `indexCount = 6·quads` for QUADS;
  - sets `indexType = IndexType.least(vertices)`.
- `MeshData.sortQuads` (L68-78) does the following:
  - decodes quad centroids by reading the `Position` floats of vertices 0 and 2
    (`decodeQuadCentroids` L23-50);
  - sorts them with `VertexSorting.byDistance` (`VertexSorting` L20-33);
  - writes `startIndex*4 + {0,1,2,2,3,0}` (L107-116).

### 1.5 Layers, upload and draw (summary of 07 §4)

**Layers.** `SOLID`, `CUTOUT` (`ALPHA_CUTOUT 0.5`, uses `discard`) and `TRANSLUCENT` (blended,
sorted) (`ChunkSectionLayer` L10-12; `RenderPipelines` L346-402). Each layer has its own vertex
`UberGpuBuffer`:
- 128 MiB heaps with alignment = vertex size (`SectionRenderDispatcher` L65-75; `UberGpuBuffer`
  L33-39);
- plus an index uber buffer, used only by translucent.

**Per-section draw.** `SectionMesh.SectionDraw(indexCount, indexType, hasCustomIndexBuffer)`
(`SectionMesh` L45-46; created in `CompiledSectionMesh` L40-44).

**Per-frame draw lists.** `LevelRenderer.extractSectionDrawGroups` (L722-809) emits one
`IndexedDraw(indexCount, 1, firstIndex, baseVertex = offset / vertexSize, sectionInfoIndex)` per
section-layer (L779, L799; record in `DynamicGpuData` L128). It feeds either MDI
(`ChunkSectionsToRender` L139-157) or one draw per section-layer (L176-190).

**Opaque index buffer.** SOLID and CUTOUT use the shared `RenderSystem.getSequentialBuffer(QUADS)`
(`ChunkSectionsToRender` L91-93) with pattern `i, i+1, i+2, i+2, i+3, i` (`RenderSystem` L49-56).
- Its type is decided by the largest request from *any* user (`RenderSystem` L439-469, L445).
- Clouds (`CloudRenderer` L189, L234), sky, weather and feature batches also use it. At the default
  `cloudRange` 128 it is 32-bit (07 §4.2).

### 1.6 Is the facing known at emit time?

| Emitter | What is known | Can it be bucketed directly? |
|---|---|---|
| Model quads | `BakedQuad.direction()` = closest axis to `(p1−p0)×(p2−p0)` (`FaceBakery` L109, L184-207). Positions in the quad (`BakedQuad` L18-29) | The direction alone is **not** enough: 93 of 2,883 vanilla block models contain rotated elements (cross plants at 45°, dripleaf, coral fans, levers, …), which still get a "closest" direction. Axis alignment must be checked from positions. |
| Model quads, cull lists | `getQuads(direction)` is keyed by *cullface* | It is a culling key, not a facing. Do not use it. |
| Fluid quads | Facing is implied by the code path, but the normal passed is always `(0,1,0)` (L426) | Not passed to the builder. Sloped tops are non-planar. |
| `BufferBuilder` BLOCK path | Normals are dropped (L313-319) | — |

**Design decision:** classify in the builder from the **quantized positions** that the GPU will
actually rasterize. This is exact, it covers both emitters, and it costs about 20 integer operations
per quad (§4.1).

---

## 2. The terrain vertex and index formats today

`DefaultVertexFormat.BLOCK` (L27-32) is 28 B:

| Offset | Attribute | `GpuFormat` | Bytes | Value range in terrain |
|---|---|---|---|---|
| 0 | `Position` | RGB32_FLOAT | 12 | Section-relative. Vanilla model extents are −8…24 model units (−0.5…1.5 blocks; `spore_blossom`). Random XZ offset is ≤ ±0.25 and Y offset is −0.2…0 (`BlockBehaviour` L234-240, L1276-1294). Overall about [−0.75, 17.5]. The JSON limit is ±1 block (`CuboidModelElement` L29-30, L158). Rescaled rotations stretch by up to 1/maxComponent (`CuboidRotation` L16-43). |
| 12 | `Color` | RGBA8_UNORM | 4 | Tint × AO × shade |
| 16 | `UV0` | RG32_FLOAT | 8 | Atlas UV in [padding/W, 1 − padding/W]. Atlas is 2048×2048 (log line "Created: 2048x2048x4 minecraft:textures/atlas/blocks.png-atlas"), always a power of two (`Stitcher` L80-83). Padding is ≥ 1 texel, 16 at mip 4 (`Stitcher` L28). |
| 24 | `UV2` | RG16_SINT | 4 | Block and sky light, 0…240 each |

How the shader consumes it (`assets/minecraft/shaders/core/terrain.vsh`):
- inputs `vec3 Position`, `vec4 Color`, `vec2 UV0`, `ivec2 UV2` (L13-16);
- `pos = Position + (ChunkPosition - CameraBlockPos) + CameraOffset` (L33);
- `Color * sample_lightmap(Sampler2, UV2)` (L39; `include/sample_lightmap.glsl` L4-6);
- `texCoord0 = UV0` (L43).

Shader inputs are matched **by name** to vertex-format elements. The location comes from the shader,
and the base type must match: UNORM/FLOAT → float, UINT → uint, SINT → int
(`com/mojang/renderpearl/frontend/shaders/PipelineBuilder.java` L104-116, L146-176).

---

## 3. How much geometry: simulation of the bench scene

**Why simulate.** No per-section vertex statistics exist in the run logs (07 §18 Q7). I therefore
re-implemented the relevant meshing rules over the actual bench world.

**Inputs.** `run/vanilla/saves/bench/.../region/*.mca`, data version 5023, spruce taiga with snow,
ice and water.

**Camera.** The anchor (−0.5, 104, 1.5), with pitch 12°, vertical FOV 70° and aspect 3600/2260, as in
`results/BASELINE.md`. Yaw is swept in 15° steps.

**Rules modelled:**
- Opaque cubes with neighbour culling. Snow layers occlude the face below them.
- Grass-block side overlays as extra CUTOUT quads.
- Leaves, which with `cutoutLeaves:true` (`run/vanilla/options.txt` L5) also render faces between
  leaves (`LeavesBlock` L45-47).
- Ice (translucent, culls only against ice).
- Water and lava top, back, side and bottom faces per `FluidRenderer`.
- Cross plants as 4 unaligned quads.
- Other models approximated as 6 quads.

**Visibility.**
- Cave culling replicates `VisGraph` (L60-80): fewer than 256 opaque cells means all faces see each
  other.
- The BFS replicates `SectionOcclusionGraph.runUpdates`: smart-cull directions, face visibility and
  the 60-block ray-march (L255-358).
- The frustum is offset 8 blocks back (07 §4.5).
- The ±0.25-block bucket epsilon from §4.4 is applied.

The scripts lived in the session scratchpad. Nothing was added to the repo.

| Quantity (RD 12, bench camera) | Value |
|---|---|
| Non-empty meshed sections in render distance | 4,272 (2.17M quads total, 508 per section) |
| Sections with geometry after cave culling | 1,270 |
| **Per frame (frustum, mean over yaw)** | **405 sections (max 515), 359,571 quads = 1.44M vertices (max 512k quads)** |
| Per-frame split | SOLID 198k (55%), CUTOUT 130k (36%), TRANSLUCENT 31k (9%) |
| Quads per visible section | median 595, mean 894, p90 2,382, **max 4,458 (17.8k vertices)** |
| Section-layer draws per frame (vanilla) | ~807 |

**Cross-check.** The max of 515 sections per frame matches the "Chunk Sections Instanced" ring having
grown to a capacity of 1,024, i.e. 513–1,024 sections (07 §0.3).

**Back-facing share** (fraction of quads whose facing points away from the camera):

| Layer | Per-bucket test | Ideal per-quad test |
|---|---|---|
| SOLID | 38.2% | ≈39% |
| CUTOUT | 50.4% | ≈51% |
| TRANSLUCENT | 44.1% | 44% |
| **All** | **43.1%** | 43.6% |
| Opaque only (translucent unbucketed, phase 1) | **39.4% of all quads** | — |

What the numbers show:
- SOLID is below 50% because surface terrain mostly faces up toward a camera that is above it.
  Leaves (CUTOUT) and water (TRANSLUCENT) are close to two-sided.
- Bucket granularity loses only 0.5 percentage points against a per-quad oracle. Sections that
  straddle a camera plane are few.
- Unaligned quads are 1% here (a few cross plants). Expect 5–15% in flower or grass-heavy biomes.

---

## 4. Design 1: seven facing buckets per section-layer

### 4.1 Classification (exact, in `BufferBuilder`)

After the 4th vertex of each quad, read back the 4 encoded `u16` positions. The positions are
already quantized (§5), and the GPU decodes them exactly. Then:

```
bucket = UNALIGNED
for axis a in {X, Y, Z}:
    if all four vertices have equal coordinate a:          // planar, axis-aligned
        s1 = sign(cross_a(v1 - v0, v2 - v0))                // triangle (0,1,2), as indexed (RenderSystem L49-56)
        s2 = sign(cross_a(v3 - v2, v0 - v2))                // triangle (2,3,0)
        if s1 >= 0 && s2 >= 0 && (s1 | s2) != 0: bucket = POS(a)   // EAST, UP, SOUTH
        if s1 <= 0 && s2 <= 0 && (s1 | s2) != 0: bucket = NEG(a)   // WEST, DOWN, NORTH
        break                                   // mixed signs (bow-tie) or zero area stays UNALIGNED
```

- `cross_a` is the `a` component of the integer cross product, computed in `long`. Coordinates are at
  most 65535, so products need more than 31 bits.
- The sign convention is checked against `FaceInfo.UP` (`FaceInfo` L17-21): v0 = (0,1,0),
  v1 = (0,1,1), v2 = (1,1,1) gives `(v1−v0)×(v2−v0) = (0,+1,0)`, which is UP.
  `FaceBakery.calculateFacing` uses the same product (L184-188), and vanilla renders these faces
  front-facing. A positive component therefore means the visible side is +axis.
- Per bucket, also track the **extreme plane** as a `u16`:
  - for the positive buckets: the minimum coordinate;
  - for the negative buckets: the maximum.

  A +X quad on plane `p` is front-facing only if `eye.x > p`. If `eye.x ≤ min p`, the whole bucket is
  back-facing or edge-on.
- Non-planar quads (sloped water), rotated plant quads and degenerate quads go to UNALIGNED and are
  always drawn.
- Bucket assignment is a pure function of the quantized geometry. It is therefore exactly consistent
  with GPU back-face culling: an affine view-projection transform preserves which side of the plane
  the eye is on, and clipping preserves orientation.

### 4.2 Storage: reorder each layer's quads into contiguous buckets

1. While emitting, append one byte per quad to a growable `byte[] quadBucket` in the builder. Also
   keep `int[7] count`, `short[6] plane`.
2. In `BufferBuilder.storeMesh` (L92-105), for SOLID and CUTOUT, do a **stable counting sort of whole
   64-byte quads** into bucket order:
   - copy into a per-pack scratch buffer, add a `long scratch` to `SectionBufferBuilderPack`, then
     copy back over the `ByteBufferBuilder.Result` range;
   - quads never share vertices (the index pattern is per quad), so moving whole quads is safe;
   - cost is two memcpys of the mesh (a typical section is 38 KB at 16 B per vertex), negligible next
     to tesselation.
3. Attach the bucket metadata to `MeshData` as a new nullable record in
   `MeshData.DrawState(… , @Nullable QuadBuckets buckets)` (`MeshData` L88-89):
   - `QuadBuckets(int[] firstQuad /*8 prefix sums, fixed bucket order*/, short[] plane /*6*/)`.
4. Carry it into `SectionMesh.SectionDraw` (`SectionMesh` L45-46; built in `CompiledSectionMesh`
   L40-44). Add `@Nullable QuadBuckets buckets` to the record. Memory is about 60 B per section-layer.

**Order matters only within a bucket.** The sort is stable, so coplanar same-facing quads keep their
emission order. With the `GREATER_THAN_OR_EQUAL` depth test (07 §11.4) the later of two equal-depth
fragments wins, so this order matters.
- Coplanar quads that face opposite ways are never front-facing at the same time.
- Overlays in another layer, such as the grass side overlay in CUTOUT over the SOLID base, keep the
  layer order SOLID → CUTOUT (`LevelRenderer` L525, `ChunkSectionLayerGroup` L6).

**Remaining ordering difference.** Equal-depth ties can only flip on intersection lines between quads
of *different* buckets (different planes). Those lines have measure zero. I did not find a vanilla
case where they matter.

### 4.3 Fixed bucket order and merged draws

**Order.** The vertex order inside a section-layer is `DOWN, NORTH, EAST, UP, UNALIGNED, SOUTH, WEST`.

**Merging.** At draw time, a visible bucket that is adjacent to another visible bucket merges into one
draw. Empty buckets are transparent, so a run continues across them. Any order is *correct*; the
order only changes the draw count.

I brute-forced all 7! = 5,040 orders on the simulated frames (8,430 opaque section-layer draws over a
yaw sweep):

| Strategy | Opaque draws relative to vanilla |
|---|---|
| One draw per visible bucket | 2.80× |
| Naive symmetric order `D,N,W,U,E,S,Up` | 1.79× |
| **`D,N,E,U,X,S,W`** (best; mirror images tie) | **1.30×** |
| Worst order | 2.41× |

Why the best order works:
- UP and UNALIGNED sit in the middle because they are visible for almost all terrain below the
  camera.
- The horizontal pairs are split so that three of the four diagonal quadrants give one contiguous run.

**Total draws per frame**, translucent still one draw per section: **807 → 1,016 (+26%)**.
- All runs of a section share its vertex buffer and the shared quad index buffer. They land in the
  **same MDI group** in `extractSectionDrawGroups` (L781-797): more 20-B records, no new groups.

### 4.4 Per-frame visibility test

In `extractSectionDrawGroups`, per section, use the render camera position. It is available as
`this.levelRenderState.cameraRenderState.pos` (`LevelRenderer` L263; `CameraRenderState` L13).

```java
double rx = cam.x - origin.getX(), ry = cam.y - origin.getY(), rz = cam.z - origin.getZ();
int vis = 1 << UNALIGNED;
if (rx > b.plane(EAST)  - EPS) vis |= 1 << EAST;   if (rx < b.plane(WEST)  + EPS) vis |= 1 << WEST;
if (ry > b.plane(UP)    - EPS) vis |= 1 << UP;     if (ry < b.plane(DOWN)  + EPS) vis |= 1 << DOWN;
if (rz > b.plane(SOUTH) - EPS) vis |= 1 << SOUTH;  if (rz < b.plane(NORTH) + EPS) vis |= 1 << NORTH;
// plane(k) in blocks = q * (1/2048.0) - 8
```

**`EPS = 0.25` block.** The eye that the GPU sees is not exactly `cameraState.pos`:
- **View bob** applies a *translation* of up to 0.05 horizontally and 0.1 vertically in view space
  (`GameRenderer` L360-368). It is multiplied into the projection matrix (L642-649).
- Hurt tilt, bob rotations and the nausea/portal warp (L655-663) are linear maps about the eye. They
  do not change which side of a plane the eye is on.

With this margin the cull is conservative. A bucket is skipped only when *every* quad in it is
strictly back-facing, so the frame is identical to drawing it.

### 4.5 Emission and indices

- Walk the 7 buckets in the fixed order and coalesce runs of visible, non-empty buckets. Emit
  `IndexedDraw(6·runQuads, 1, 0, baseVertex + 4·runFirstQuad, sectionInfoIndex)` for MDI, and the
  matching `RenderPass.Draw` on the separate path (`LevelRenderer` L811-862).
- **16-bit terrain quad index buffer:**
  - Add a dedicated `RenderSystem.AutoStorageIndexBuffer` for terrain, fixed at 16,384 quads
    (98,304 SHORT indices = 192 KiB).
  - Use it in `ChunkSectionsToRender.renderLayers` (L91-93) instead of the shared quad buffer.
  - Drop the terrain `requestIndexCount` calls (`LevelRenderer` L858, L960).
  - Split any run longer than 16,384 quads into several records, advancing `baseVertex` by 65,536.
    In the bench scene the largest *section* has 4,458 quads, so splits are rare.
  - This makes terrain independent of the cloud renderer pushing the shared buffer to 32-bit.
- Translucent sorted index buffers are unchanged: SHORT when there are fewer than 65,536 vertices
  (`BufferBuilder` L102-103).

### 4.6 Translucent sorting

Per-bucket draws are **not** correct for TRANSLUCENT under classic blending. Back-to-front order
across buckets inside a section would be lost (`ChunkSectionsToRender` L139-157 draws are in list
order; `LevelRenderer` L781-786 keeps consecutive translucent groups ordered).

**T1 (phase 1).** Do not reorder or bucket the translucent layer. Behaviour is identical to vanilla;
no translucent savings.

**T2 (phase 2): exclusion inside the sorted list.**
- Store `byte[] quadBucket` in `MeshData.SortState` (L91-126). For an aligned quad the centroid's
  axis coordinate *is* its plane, because vertices 0 and 2 lie on it.
- In `SortState.buildSortedIndexBuffer` (L92-97) and `ResortTransparencyTask` (`SectionRenderDispatcher`
  L486-529), skip a quad when it is back-facing for every eye position in the current
  `TranslucencyPointOfView` octant. The POV per axis is the camera section relative to this section,
  clamped to −1…1 (`TranslucencyPointOfView` L17-27). Skip when:
  - POV_a = −1 and the bucket is POS(a) with plane ≥ `M`; or
  - POV_a = +1 and the bucket is NEG(a) with plane ≤ 16 − `M`.
- `M = 1` block is a staleness margin. Once the camera enters the slab, it must move a further block
  before an excluded quad could become visible. That gives the async resort time to land.
- The index count now varies per resort:
  - Make `SectionDraw.indexCount` updatable from `indexBufferUploadCallback`
    (`SectionRenderDispatcher` L361-366). That runs under `copyLock`, the same lock as
    `extractSectionDrawGroups`.
  - Make `scheduleTranslucentSectionResort` check POV changes for **all** visible translucent sections
    every frame. Today it checks the nearby sections plus a round-robin of
    `max(visible/8, 15)` (`LevelRenderer` L1217-1238). The check is 3 integer compares per section.
- **Gain:** about 44% of translucent quads, which is about 3.8% of all terrain quads in the bench
  scene. It is much larger over oceans, where the water top plus its back face are 2 quads per block.

**OIT on.** Translucent is not re-sorted (`LevelRenderer` L1254). It can therefore use exactly the
opaque bucket scheme.

**Sort keys stay vanilla.** With compact positions, sort from **float** centroids captured at emit
time. For translucent builders only, record `(v0+v2)/2` from the float arguments of `addVertex`,
instead of decoding quantized positions in `decodeQuadCentroids` (`MeshData` L23-50). This keeps the
sort order bit-identical even for non-grid water heights.

### 4.7 Code changes (Design 1)

| Where | Change |
|---|---|
| `com/mojang/blaze3d/vertex/BufferBuilder.java` | Terrain fast path (§5.5). Per-quad classification after every 4th vertex. Growable `quadBucket` byte list, counts and planes. Stable bucket reorder in `storeMesh` (L92-105) when the layer requests it. Optional float-centroid capture for translucent. |
| `com/mojang/blaze3d/vertex/MeshData.java` | `DrawState` carries `@Nullable QuadBuckets` (L88-89). `SortState` carries `quadBucket[]` and builds POV-filtered indices (L91-126, T2). |
| `net/minecraft/client/renderer/SectionBufferBuilderPack.java` | A reusable native scratch buffer for the reorder (L11-31). |
| `net/minecraft/client/renderer/chunk/SectionCompiler.java` | Tell the builder which layers to bucket: SOLID and CUTOUT, plus TRANSLUCENT when OIT is on (L133-144). |
| `net/minecraft/client/renderer/chunk/SectionMesh.java`, `CompiledSectionMesh.java` | `SectionDraw` + `QuadBuckets` (L45-46; L40-44). A mutable translucent index count (T2). |
| `net/minecraft/client/renderer/LevelRenderer.java` | `extractSectionDrawGroups` (L722-809): visibility mask (§4.4), run emission (§4.5), `largestIndexCount` → fixed terrain buffer. Resort scheduling (T2, L1217-1257). |
| `net/minecraft/client/renderer/chunk/ChunkSectionsToRender.java` | Bind the terrain 16-bit quad index buffer (L91-93). |
| `com/mojang/blaze3d/systems/RenderSystem.java` | `getTerrainQuadIndexBuffer()`: fixed SHORT, 16,384 quads (alongside L48-56). |

---

## 5. Design 2: compact 16-byte terrain vertex

### 5.1 Layout: new `DefaultVertexFormat.TERRAIN`

```java
public static final VertexFormat TERRAIN = VertexFormat.builder(0)
    .addAttribute("Position", GpuFormat.RGBA16_UINT)  // 0:  x,y,z = (p + 8) * 2048 ; w = block | sky << 8
    .addAttribute("Color",    GpuFormat.RGBA8_UNORM)  // 8:  unchanged ABGR (tint * AO * shade)
    .addAttribute("UV0",      GpuFormat.RG16_UINT)    // 12: u,v * 65536
    .build();                                          // 16 bytes, all offsets % 4 == 0
```

- **Offsets.** All are multiples of 4. Metal rejects any other offset (`docs/metal/CONTRACT.md` L584;
  `docs/renderpearl/00-metal-backend-spec.md` L466). `VertexFormat` itself only checks component
  alignment (L84-86).
- **Metal vertex formats** (00 spec L512-516): `UShort4`, `UChar4Normalized`, `UShort2`.
- **GL and Vulkan.** Integer attributes already go through `glVertexAttribIPointer` /
  `glVertexAttribIFormat` (`backend/opengl/VertexArray` L86-87, L134-135). Vulkan uses
  `VK_FORMAT_R16G16B16A16_UINT` and `R16G16_UINT`.
- **Why light lives in `Position.w`.** A separate 2×u8 light attribute would need its own 4-byte slot
  (20 B total). `UV2` is gone as an element.
- **Keeping the `Position` name.** It keeps `BufferBuilder`'s "must contain Position" check (L43-45)
  and the shader's attribute name.
- **Scope.** Only terrain pipelines switch format. `BLOCK` stays for `core/block`, beacon beam and
  crumbling (`RenderPipelines` L69, L162, L302).

### 5.2 Range and precision

| Field | Encoding | Range | Step | Exact for |
|---|---|---|---|---|
| Position xyz | `q = round((p + 8) · 2048)`, `u16` | [−8, 24) blocks, about 6.5 blocks of margin each side of [−0.75, 17.5] | 1/2048 block (0.49 mm) | Every value with ≤ 11 fractional bits, i.e. all positions from models on a 1/128-model-unit grid with 90° block-state rotations. `OctahedralGroup` matrices are exact permutations (`BlockModelRotation` L22-27). |
| Light | `block \| sky << 8` in `w` | 0…255 per component | 1 | Always. Values are `& 0xFF` and ≤ 240 (`LightCoordsUtil` L29-31, L87, L99-102). |
| Color | Unchanged | — | — | Always |
| UV | `q = round(u · 65536)`, `u16` | [0, 1). `u = 1.0` cannot occur because each sprite is inset by ≥ 1 padding texel (16 at mip 4) inside a power-of-two atlas (`Stitcher` L28, L80-83; `TextureAtlasSprite` L32-35). Clamp to 65535 defensively. | 2^-16 atlas (1/32 texel at 2048) | Every sprite edge `k/W` (W a power of two ≤ 65,536) and every in-sprite UV on the 1/16-model-unit grid: for 16-px sprites at W = 2048, `u0 + (16/W)·(m/256)` is a multiple of 2^-15. In vanilla, every model UV is on that grid except `straw_bed_head.json`. |

**Meeting the requirements.**
- *Blocks extend outside the section.* The range [−8, 24) covers the JSON limit of −1…2 blocks plus
  offsets with room to spare. 32 is the smallest power-of-two span ≥ 19.25, and a power of two keeps
  the decode exact.
- *Sub-voxel precision of at least 1/16.* We get 1/2048, which is 128 steps per model pixel.

**Out-of-range vertices.** These can only come from resource-pack models with extreme rescaled
rotations. Clamp them and log once per model.

Vanilla content that is **not** on the grid. All errors are below visibility:

| Content | Why off-grid | Max error |
|---|---|---|
| Rotated elements (93 vanilla models: `cross.json` plants at 45° rescaled, dripleaf, coral fans, levers, …) | Rotation and rescale produce arbitrary floats | ≤ 2^-12 block = 0.24 mm |
| Non-dyadic model coordinates (81 models, e.g. `cross.json` 0.8/15.2, `glow_lichen`, vines) | Coordinate is not a dyadic fraction | ≤ 2^-12 block |
| Random block offsets | Built from `k/15` (`BlockBehaviour` L1282-1292) | ≤ 2^-12 block |
| Fluid corner heights and the 0.001 insets | 0.001 becomes 2/2048 = 0.00098. Still a positive inset, so no new z-fighting. | ≤ 2^-12 block |
| Flowing-fluid UVs | `sin`/`cos` (`FluidRenderer` L152-164) | ≤ 2^-17 atlas = 1/64 texel |

**Cracks.**
- Quantization is a deterministic function of the float. Shared corners within a section therefore
  stay welded.
- Section-boundary vertices are integers, so they are exact.

**Rejected alternatives:**
- **Packed 10/11-bit positions** (`RGB10A2_UINT` exists, but has no Metal vertex format; 00 spec
  L535). At 1/32-block steps the fluid insets round to 0, giving z-fighting with glass, and water
  levels and cross plants shift visibly.
- **`RG16_UNORM` UV.** It divides by 65535, so it is not exact.
- **`half` positions.** They have too few mantissa bits at 16.
- **12 B variants.** They require packing colour, e.g. RGB565 or a palette, which breaks bit
  identity. The gain from 16 → 12 B is small compared with what buckets remove.

### 5.3 Encode (Java, `BufferBuilder` terrain fast path, next to L313-319)

```java
// com.mojang.blaze3d.vertex.TerrainVertex (new): shared constants and codec
static final float POS_BIAS = 8.0f, POS_SCALE = 2048.0f, UV_SCALE = 65536.0f;
static int qPos(float p) { int q = Math.round((p + POS_BIAS) * POS_SCALE); return q < 0 ? 0 : Math.min(q, 0xFFFF); }
static int qUv (float u) { int q = Math.round(u * UV_SCALE);              return q < 0 ? 0 : Math.min(q, 0xFFFF); }

// in addVertex(x,y,z,color,u,v,overlay,light,nx,ny,nz) when format == DefaultVertexFormat.TERRAIN:
long p = this.beginVertex();
int lt = (light & 0xFF) | ((light >>> 8) & 0xFF00);             // block | sky << 8
MemoryUtil.memPutLong(p, qPos(x) | (long) qPos(y) << 16 | (long) qPos(z) << 32 | (long) lt << 48);
putRgba(p + 8L, color);
MemoryUtil.memPutInt(p + 12L, qUv(u) | qUv(v) << 16);
if ((this.vertices & 3) == 0) this.finishTerrainQuad(p - 48L);  // §4.1 classification
```

- The first 8 bytes are written as one little-endian store. macOS on ARM is little-endian; keep a
  short-store fallback behind `IS_LITTLE_ENDIAN` (L19).
- That is 3 stores per vertex instead of 6, and 16 bytes written instead of 28. This is CPU-neutral
  or slightly faster for meshing.
- For grid values, `(p + 8)` and `× 2048` are exact in float, so `round` is exact.
- The generic `VertexConsumer` setters must throw for `TERRAIN`. Only the 11-argument path is used by
  terrain emitters (`VertexConsumer` L76; `FluidRenderer` L426).

### 5.4 Decode: `assets/minecraft/shaders/core/terrain.vsh`

```glsl
layout(location = 0) in uvec4 Position;   // RGBA16_UINT
layout(location = 1) in vec4  Color;      // RGBA8_UNORM (unchanged)
layout(location = 2) in uvec2 UV0;        // RG16_UINT
#ifdef MULTIDRAW_TERRAIN
layout(location = 4) in ivec3 ChunkPosition;
layout(location = 5) in float ChunkVisibility;
#endif
...
void main() {
    // (q * 2^-11) == Position_vanilla + 8 exactly; folding the -8 into the integer term keeps the
    // single rounding of the vanilla "Position + (ChunkPosition - CameraBlockPos)" add.
    vec3 pos = vec3(Position.xyz) * (1.0 / 2048.0) + vec3(ChunkPosition - CameraBlockPos - 8) + CameraOffset;
    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);
    ...
    ivec2 light = ivec2(int(Position.w & 0xFFu), int(Position.w >> 8u));
    vertexColor = Color * sample_lightmap(Sampler2, light);       // sample_lightmap.glsl unchanged
    texCoord0   = vec2(UV0) * (1.0 / 65536.0);
    ...                                                           // fog distances, chunkVisibility unchanged
}
```

- `terrain.fsh`, `sample_lightmap.glsl`, `chunksection.glsl` and `terrainglobals.glsl` need no
  changes. The varyings are identical.
- **Bit-identity argument for positions.**
  - `vec3(Position.xyz)` is exact (< 2^24), and `* 2^-11` is exact, so the result is exactly
    `p_vanilla + 8` for grid vertices.
  - `ChunkPosition − CameraBlockPos − 8` is an exact integer.
  - So the first float add rounds the **same real number** as vanilla's
    `Position + (ChunkPosition − CameraBlockPos)` (L33).
  - Fused multiply-add (FMA) contraction by SPIRV-Cross or Metal's compiler cannot change it, since the
    product is exact.
  - Everything after `pos` is the vanilla code.
- **Bit-identity for UV and light.** `vec2(UV0) * 2^-16` reproduces the vanilla float exactly for grid
  UVs, and `light` reproduces the vanilla `ivec2 UV2`.
- **Where frames can differ.** Only at pixels covered by the off-grid content in §5.2, by sub-pixel
  amounts. Verify with the existing screenshot-diff harness (`run/diff-*`).

### 5.5 Code changes (Design 2)

| Where | Change |
|---|---|
| `com/mojang/blaze3d/vertex/DefaultVertexFormat.java` | Add `TERRAIN` (§5.1). Keep `BLOCK` (L27-32). |
| `net/minecraft/client/renderer/RenderPipelines.java` | `.withVertexBinding(0, DefaultVertexFormat.TERRAIN)` in `TERRAIN_SNIPPET` (L76-82), `MULTIDRAW_TERRAIN_SNIPPET` (L83-90), and both `OIT_TERRAIN*` builders (L404-428). The builder override wins over the snippet's BLOCK binding (`RenderPipeline` L267-269, L323-328). `WIREFRAME*` inherit it. `ChunkSectionLayer.vertexFormat()` (L52-54) and the uber-heap alignment (`SectionRenderDispatcher` L71-72) follow automatically. Alignment becomes 16, a power of two. |
| `com/mojang/blaze3d/vertex/BufferBuilder.java` | `terrainFormat` flag beside `blockFormat` (L64-65). Fast path (§5.3). Guard the generic setters. |
| `com/mojang/blaze3d/vertex/MeshData.java` | `decodeQuadCentroids` (L23-50): a `TERRAIN` branch that decodes `u16` as a fallback. Preferred: float centroids captured at emit (§4.6). |
| `net/minecraft/client/renderer/LevelRenderer.java` | `baseVertex = offset / 16` happens automatically (L758, L779). |
| `assets/minecraft/shaders/core/terrain.vsh` | §5.4 |
| `ChunkSectionLayer` buffer sizes (L10-12) | Optional: 4 MiB → 2.5 MiB. `SectionBufferBuilderPool` then fits more packs in the same heap share (07 §4.4). |

---

## 6. Savings

### 6.1 Per frame (bench scene, mean over the yaw sweep; terrain only)

| Configuration | Vertices shaded / frame | Vertex bytes | Index bytes | **Total fetched** |
|---|---|---|---|---|
| Vanilla (28 B, 32-bit shared quad indices) | 1,438k | 40.3 MB | 8.6 MB | **48.9 MB** |
| Compact 16 B + 16-bit terrain indices | 1,438k | 23.0 MB | 4.3 MB | **27.3 MB (−44%)** |
| + opaque buckets (T1) | **872k (−39%)** | 13.9 MB | 2.6 MB | **16.6 MB (−66%)** |
| + translucent exclusion (T2) | 818k (−43%) | 13.1 MB | 2.5 MB | 15.6 MB (−68%) |

At about 400 FPS, terrain vertex-input traffic falls from about 19.6 GB/s to about 6.6 GB/s.

### 6.2 Per section and resident memory

| Quantity | Vanilla 28 B | Compact 16 B |
|---|---|---|
| Median visible section, 595 quads | 66.6 KB | 38.1 KB |
| p90 section, 2,382 quads | 267 KB | 152 KB |
| Largest section, 4,458 quads = 17.8k vertices | 499 KB | 285 KB |
| All meshed sections in RD 12 (2.17M quads) | **243 MB** | **139 MB** |

- The largest section is still below 65,536 vertices, so 16-bit indices need no split here.
- In resident memory, SOLID alone drops from ~178 MB (2 × 128 MiB heaps) to ~102 MB (1 heap). That
  means fewer MDI groups (07 §4.6).
- Chunk-load staging and upload traffic (`StagingBuffer`, 07 §4.3) drops by 43% per mesh.

### 6.3 GPU time (model, to be measured)

**What changes on a TBDR GPU.**
- Back-facing triangles are culled after vertex shading and before binning. Buckets therefore remove
  **vertex fetch and shading**, including the vertex-stage lightmap fetch (`terrain.vsh` L39), for
  39–43% of terrain vertices.
- They do **not** change binning (parameter-buffer writes of visible triangles) or fragment work.

**Terrain share of the vertex stage.**
- Terrain is about 1.44M vertices per frame.
- The next-largest vertex consumer is the cloud ring: a radius of about 171 cells at `cloudRange` 128
  (`CloudRenderer` L135-137), roughly 10^5 vertices.
- So terrain is about 80% or more of vertex-stage work.

**Estimate.**

| Assumption about the vertex stage | Saving per frame |
|---|---|
| Upper bound: vertex-stage time ∝ vertices processed | 1.1 × 0.8 × 0.39 ≈ **0.34 ms** |
| Lower bound: half of vertex-stage time is binning of surviving triangles | ≈ **0.17 ms** |
| Compact format (43% fewer bytes per remaining vertex, half-size indices) | **0.03–0.1 ms** more |

**Net: about 0.2–0.45 ms per frame** of the ~1.1 ms vertex stage. That is a meaningful share of the
remaining gap to a 1.0–1.4 ms GPU frame.

**How to confirm.** Metal System Trace GPU counters on identical frames, with and without each
change: vertex-shader invocations, vertex-stage time and tiler utilization.

---

## 7. Implications for the Metal backend

- **Vertex descriptor.** `UShort4 @0`, `UChar4Normalized @8`, `UShort2 @12`, stride 16 (fits the
  CONTRACT L584 rule). The instanced binding 1 (`CHUNK_DATA_INSTANCED`, 16 B) is unchanged.
- **Index type.** `MTLIndexTypeUInt16` for the terrain quad buffer. `firstIndex` stays 0, and
  `baseVertex` ≤ 128 MiB / 16 = 8.4M.
- **Draw count.** About 26% more `drawIndexedIndirect` records per frame (~1,016 vs ~807). Record
  format and grouping are unchanged.
  - With the M1 per-record loop (CONTRACT L336) that is 26% more encoder calls.
  - With the M4 indirect-command-buffer (ICB) path it is noise.
  - This favours landing M4 before, or together with, Design 1.
- **Front face.** The game's winding convention is unchanged. Classification uses the same winding
  the backend culls with (00 spec L583).

---

## 8. Implementation order and tests

1. **Compact format + terrain 16-bit index buffer** (§5, §4.5 index part). Independent and small.
   - Gate: screenshot diffs against vanilla. Expect zero diff on scenes without plants or flowing
     water, and sub-pixel diffs otherwise.
   - Gate: a unit test over all baked vanilla block models. It asserts exact round-trip for
     non-rotated, dyadic quads and lists the inexact ones (expected: the 93 rotated + 81 non-dyadic
     models).
2. **Opaque buckets** (§4.1–4.5).
   - Unit tests:
     - the faces of a stone block classify as their `FaceInfo` directions;
     - water back faces classify opposite to their fronts;
     - a 45° cross plant classifies UNALIGNED;
     - the stable reorder preserves emission order within each bucket.
   - Runtime debug assert (development builds only): every skipped bucket's quads satisfy
     `eye·n ≤ plane`.
   - Metrics: vertex invocations and draws per frame.
3. **Translucent T2 exclusion** (§4.6). Includes the all-visible POV-change check. Test by flying fast
   across section boundaries over water and checking for missing faces.
4. **With OIT on:** bucket translucent like opaque.

**Options beyond these steps:**
- **Per-octant index buffers.** Rebuilt when a section's POV octant changes, they would give exactly
  1 draw per section-layer with no back-facing indices. The cost is 12 B per quad of index memory and
  a rebuild per octant change.
- **Quad records with vertex pulling.** One 24–32 B record per quad, expanded with `vertex_id`, would
  halve fetch again. It is a bigger shader change and needs care for rotated UVs.

---

## 9. Risks and open questions

1. **Vertex-stage composition on the M5** (§6.3). The split between fetch/shading and binning is
   unknown. It sets where the 0.2–0.45 ms lands. Measure it first with the Metal backend.
2. **Simulation fidelity.**
   - Block categories are approximated: snow assumed 1 layer; misc models as 6 quads; the water
     back-face rule simplified.
   - Bucket shares in leaf-, ocean- or flower-heavy scenes will differ. Leaves and water are close to
     50%; flat, solid terrain viewed from above is lower.
   - Re-measure in-game: count per-bucket quads in `SectionCompiler` behind a debug flag.
3. **Resource packs.** Atlases up to 16,384 still give 1/4-texel UV steps. Huge or rotated models may
   clamp, and get logged. Non-power-of-two atlases cannot happen (`Stitcher` L80-83).
4. **T2 staleness.** If the camera moves more than 1 block into a section before its resort lands,
   faces could be missing for a frame. Possible with elytra at low FPS. Mitigations: raise `M` to 2,
   or prioritize those resorts in `SectionTaskDynamicQueue`.
5. **Draw-count sensitivity.** The 1.30× merge factor was chosen on one scene. The order is a
   constant; any order is correct, only the draw count changes.
