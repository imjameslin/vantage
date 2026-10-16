# Stage 2 terrain plan: less vertex work at pixel parity, ranked

This plan merges the three stage-2 analyses:
- `docs/stage2/meshing-and-vertex-format.md` ("meshing");
- `docs/stage2/draw-submission-and-mdi.md` ("submission");
- `docs/stage2/culling.md` ("culling").

It turns them into one ordered set of game patches for vanilla 26.3. Where the analysts disagreed, the
source code decided.

**Citations.** Paths are relative to `src/vanilla` unless they start with a repo directory (`bench/`,
`tools/`, `docs/`, `results/`, `run/`). "L123" means line 123 of the file just named.

**Where patches go.** Patched copies of vanilla classes go in `client/src/<same package path>`. Shader
overrides go in `client/resources/assets/minecraft/shaders/...` (`tools/build-client.sh` overlays both onto
the vanilla jar).

**Budget it is sized against.** On Vulkan/MoltenVK at 3600x2260, RD 12, the GPU spends about 1.1 ms per
frame in the vertex stage and about 1.5 ms in the fragment stage, against a target of 1.0–1.4 ms in total
(`results/GPU-PROFILE.md`).

---

## 0. Summary

**Where the time goes.** Terrain is about 85% of the vertex stage. About 40% of the terrain vertices the
GPU shades every frame belong to quads that face away from the camera, so the rasterizer throws them away
after shading.

**What this plan saves.**
- About **0.4 ms of vertex-stage time** (range 0.25–0.65 ms), with no pixel changes. This is a model, to be
  confirmed by the measurements in S0.
- Most of it comes from S1 (facing buckets). The rest comes from:
  - an exact per-frame frustum;
  - culling clouds to the frustum;
  - a "head" index list for translucent terrain;
  - smaller indices and vertices.

**What it does not save.** Terrain's **fragment** cost hardly moves at pixel parity:
- back faces and off-screen triangles never reach the fragment stage;
- opaque overdraw is already removed by the GPU's hidden-surface removal;
- the only terrain lever left is the overdraw order of alpha-tested (CUTOUT) fragments (S6, measure-gated).

The ~1.5 ms fragment budget has to come from the Metal backend's M2 work (clear folding, pass merging,
DontCare stores), not from terrain.

### 0.1 Ranked steps

| Rank | Step | Change | Est. GPU saving, bench (central) | Pixel parity | Effort | Needs | Gate before enabling by default |
|---|---|---|---|---|---|---|---|
| — | **S0** | Measure first: counters, dry-run bucket/bounds metadata, record-cost probe, GPU traces (§3) | 0 (enables the rest) | No render change | S | — | — |
| 1 | **S1** | Facing buckets for SOLID+CUTOUT (fixed layout A, gaps of ≤ 32 quads bridged), plus a fixed 16-bit terrain quad index buffer | **0.15–0.37 ms (0.27)** vertex | Identical. Exception: exact depth ties on T-junction lines (A/B checked) | M | S0b | S0b: ≥ 25% of opaque quads skippable in-game; S0c: GPU cost per record known |
| 2 | **S2** | Exact per-frame frustum, from the bobbed projection, applied to the draw list with tight per-layer bounds | 0.03–0.06 ms on top of S1 (0.05–0.09 alone) | Identical | S | S0b bounds | — |
| 3 | **S7** | Frustum-filter cloud faces (not terrain; same vertex budget) | 0.03–0.06 ms | Identical | S–M | — | — |
| 4 | **S3** | Translucent "head" index list: vanilla sorted list plus a back-face-free copy in the same allocation | 0.016–0.03 ms at the bench; more over oceans | Identical, including when the sort is stale | M | S0b | — |
| 5 | **S4** | CPU companion: descriptors on the mesh, persistent instance stream, cached draw list | 0 GPU; CPU ~36 µs → ~10 µs per frame, ~46 KB/frame of garbage → 0 | Identical | M | S1–S3 | Needed before the 1000-FPS work, not for the GPU win |
| 6 | **S5** | Compact 16-byte terrain vertex, used only when exact for the whole section-layer, else 28 B | 0–0.08 ms (0.03); resident mesh memory about −40% | Identical (inexact layers keep 28 B) | M–L | S1, S4 | S0d: vertex stage fetch-limited; S0b: ≥ 70% of opaque section-layers exactly representable. **Done, default off (§4.5 status)** |
| 7 | **S6** | Front-to-back order inside CUTOUT buckets (fragment overdraw of `discard` geometry) | 0–0.1 ms fragment (unknown) | Identical | S | S1 | S0d frame capture shows CUTOUT overdraw > 1.5× |
| 8 | **S8** | Stop the `EMPTY` swap for compiled sections with no geometry | ~0.02 ms | Not provably identical; toggle only | S | — | Scene sweep (§5) |

### 0.2 Budget after this plan (bench, model)

| GPU stage per frame | Today (MoltenVK) | After S1+S2+S3+S7 (+S5) | Range |
|---|---|---|---|
| Vertex | ~1.1 ms | **~0.7 ms** | 0.45–0.85 ms |
| Fragment | ~1.5 ms | ~1.5 ms (S6: −0 to −0.1 ms) | 1.4–1.5 ms |
| Whole GPU frame | ~2.9 ms | **~2.5 ms** | 2.25–2.65 ms |

Reaching 1.0–1.4 ms therefore still needs **1.1–1.5 ms from fragment and bandwidth work**:
- backend levers 1–2 in `results/GPU-PROFILE.md`;
- plus whatever native Metal saves over MoltenVK's translation.

This plan delivers roughly the vertex half of the gap.

---

## 1. Baseline and how savings are sized

### 1.1 What the bench frame draws

All figures are model numbers, the mean over the 360° spin. S0 replaces them with measured counts.

| Quantity | Value | Source |
|---|---|---|
| Terrain quads drawn | **414k** = 1.65M vertices. SOLID 235k, CUTOUT 147k, TRANSLUCENT 31k | culling §3.1; submission §2 gets 1.65M independently |
| Sections / section-layer records | 481–505 / 850–908 | culling §1; submission §2 |
| Back-facing at bucket granularity | SOLID 38–39%, CUTOUT 50–51%, TRANSLUCENT 43–44% | meshing §3, submission §2 |
| Cloud quads | 36.9k (147.5k vertices); only 28% inside the frustum | culling §2.9 |
| Largest section | 4,458 quads (17.8k vertices) | meshing §3 |

**Field of view.** The bench flies (`bench/src/bench/Bench.java` L128), and flying multiplies FOV by 1.1
(`net/minecraft/client/player/AbstractClientPlayer.java` L94-96). The scene is therefore drawn at 77°, not
70°. Meshing's 1.44M-vertex figure was simulated at 70°, so the 1.65M baseline is used here. Meshing's
*ratios* still hold.

### 1.2 Converting removed quads into milliseconds

**Upper bound: 0.24 ms per 100k quads.**
- The vertex stage takes ~1.1 ms for about 1.85M submitted vertices: 1.65M terrain, 0.15M cloud, plus a
  small rest.
- If all of that time were per-vertex work, it would be **0.59 ns per vertex**, or 0.24 ms per 100k quads.

**Lower bound: 0.12 ms per 100k quads.**
- Quads removed by S1, S2 and S7 would have been culled after shading anyway: back-facing or off-screen.
- On a TBDR GPU, culled triangles are discarded before binning, so these quads never cost tiler or
  parameter-buffer work.
- If binning of the surviving triangles is about half the stage, the saving halves.

**Planning value:** **0.12–0.24 ms per 100k removed quads**, central 0.18.

**Why culling's figure is not used.** Culling's 0.25–0.32 ms per 100k is a fit of *whole-frame* time
against quad count (culling §3.3). It also absorbs fragment and binning work that grows with on-screen
terrain, so it is an upper bound.

**Vertex reuse does not change the conversion.** Whether Apple's GPU shades 4 or 6 vertices per quad, the
1.1 ms was measured for this content, so the per-quad conversion holds either way.

**Extra draw records cost something.** S1 adds about 115–230 indirect records per frame (§4.1). Their GPU
front-end cost is unknown; S0c measures it. At an assumed 25–200 ns per record, that is 0.003–0.046 ms,
already subtracted in §0.1.

### 1.3 Why a vertex saving shortens the frame almost 1:1

**The stages barely overlap today.**
- GPU busy time is 97.2% of wall time at ~334 FPS, i.e. about 2.9 ms per frame.
- That is roughly the sum of the vertex and fragment stages (1.1 + ~1.5–1.7 ms).

**The main pass waits for its geometry.** On a tile-based GPU, the main pass's fragment work cannot start
until all of its geometry is binned. Main-pass vertex savings therefore come straight off the frame.

---

## 2. Analyst conflicts, resolved from the source

| # | Topic | Positions | What the source says | Decision |
|---|---|---|---|---|
| 1 | Baseline volume | Meshing: 360k quads / 1.44M vertices. Submission and culling: 414k / 1.65M | The bench flies (`bench/src/bench/Bench.java` L128). Flying FOV ×1.1 (`AbstractClientPlayer` L94-96) | Use 414k / 1.65M. S0 replaces both with measured counts |
| 2 | Where to classify facing | Meshing: in `BufferBuilder`. Culling: in `SectionCompiler.compile` L64-71 | `BakedQuad.direction` is only the closest axis (`FaceBakery` L184-207). Fluids bypass the compiler's quad lambda: they receive the builder (`SectionCompiler` L72) and call `addVertex` directly with a constant normal (`FluidRenderer` L426). Both paths meet in the BLOCK fast path (`BufferBuilder` L313-319) | Classify in `BufferBuilder` from the written positions. Enable it only for builders created by `SectionCompiler.getOrBeginLayer` (L133-144) |
| 3 | Record growth | Meshing +26% (no bridging). Submission +13% (gaps ≤ 32 quads bridged). Culling +25% | Bridged quads are back-facing, so the GPU culls them; still lossless. Records stay the vanilla 20 B (`DynamicGpuData` L128-134) | Layout A (`DOWN,NORTH,EAST,UP,UNASSIGNED,SOUTH,WEST`) plus bridging ≤ 32 quads. Defer the camera-relative layout C until S0c shows per-record cost matters |
| 4 | Translucent | Meshing T2: drop back-facing quads with a 1-block margin and re-check every section every frame. Submission P5: head/tail split, drawing head+tail when stale | Translucent terrain **writes depth**: `DepthStencilState.DEFAULT` is `GREATER_THAN_OR_EQUAL, true` (`com/mojang/renderpearl/api/pipeline/DepthStencilState.java` L4), inherited through `RenderPipelines` L71. Resorts lag the camera: a resort is cancelled unless the point of view changed or is axis-aligned (`SectionRenderDispatcher` L506-508); only nearby plus round-robin sections are checked (`LevelRenderer` L1217-1238); none under OIT (L1254). **T2** can drop faces that became visible before the resort lands. **P5's fallback is not vanilla:** it draws head then tail, so a now-front-facing tail quad is drawn *after* nearer head quads and fails the depth test instead of being blended under them | **Dual list:** one allocation holds `[vanilla sorted list][head list]`. Draw the head when the camera's strict point of view matches the buffer's; otherwise draw the vanilla list unchanged (§4.3) |
| 5 | Compact-format parity | Meshing accepts errors up to 2^-12 block and 1/64 texel for off-grid content | Random offsets, rotated elements and fluid heights are not on any 1/2048 grid (meshing §5.2). The task requires parity | Use 16 B only when every vertex of a section-layer round-trips exactly; else keep 28 B. Translucent always stays 28 B. Gated by S0 (§4.5) |
| 6 | GPU estimate for buckets | 0.17–0.34 / 0.35–0.45 / 0.40–0.51 ms | Assumptions differ on terrain share and on binning (§1.2) | 0.15–0.37 ms, central 0.27, settled by the S0d A/B trace |
| 7 | GL (separate-draw path) | Meshing: one `RenderPass.Draw` per run. Submission: gate on MDI or bridge more | GL on macOS has no MDI. Each record is a real draw (07 §13: per-section `glDrawElementsInstancedBaseVertex`). GL is CPU/driver-bound (`results/BASELINE.md`) | Separate path keeps one draw per section-layer (no bucket culling), so GL never gets more draws. It still gets S2 (fewer draws) and S3 |
| 8 | Exact frustum vs cached draw list | Culling P2 (per frame) vs submission P3 (reuse records on ~98% of frames) | The exact frustum changes with every rotation | Cache per-section run records keyed on camera position. Run the frustum filter every frame (~500 tests, ~5–10 µs) |
| 9 | Margin | All three use 0.25 block | View-bob translation ≤ (0.5·bob, bob) (`GameRenderer` L365-368) with bob ≤ 0.1 (`AbstractClientPlayer` L84). Hurt tilt, bob rotations and nausea are linear maps about the eye (`GameRenderer` L344-356, L370-371, L655-662). The shader's eye is `cameraRenderState.pos` (`GlobalSettingsUniform` L29-35; `GameRenderer` L490) | EPS = 0.25 block. Add a debug assert on the bob translation (§5) |

---

## 3. S0: measure first (tiny instrumentation patch + probes)

None of the analysts had in-game numbers; all three simulated the bench world. These patches produce real
numbers without changing a pixel.

### 3.1 S0a: per-frame terrain counters (≈ 40 lines)

New file `client/src/net/minecraft/client/renderer/chunk/TerrainStats.java`:

```java
package net.minecraft.client.renderer.chunk;

public final class TerrainStats {
    public static final boolean ENABLED = Boolean.getBoolean("terrain.stats");
    // 0-2 quads by ChunkSectionLayer.ordinal(), 3 records, 4 sections, 5 cloud quads,
    // 6-8 dry-run facing-skippable quads per layer, 9 dry-run frustum-skippable quads,
    // 10 translucent quads outside the head list, 11 section-layers exactly representable in 16 B,
    // 12 opaque section-layers seen (denominator for 11)
    public static final int N = 13;
    private static final long[] frame = new long[N];
    public static final long[] total = new long[N];
    public static long frames;

    public static void add(int slot, long v) { frame[slot] += v; }        // render thread only

    public static void endFrame() {
        for (int i = 0; i < N; i++) { total[i] += frame[i]; frame[i] = 0; }
        frames++;
    }

    public static void reset() { java.util.Arrays.fill(total, 0L); frames = 0; }
}
```

**Hooks** (each one guarded by `if (TerrainStats.ENABLED)`):

| Where | Line | Call |
|---|---|---|
| `LevelRenderer.extractSectionDrawGroups`, inside `if (sectionInfoDataIndex == -1)` | L748-755 | `TerrainStats.add(4, 1)` |
| Next to `drawGroup.draws.add(...)` | L799 | `TerrainStats.add(layer.ordinal(), draw.indexCount() / 6); TerrainStats.add(3, 1)` |
| `CloudRenderer.render`, before `drawIndexed` | L242 | `TerrainStats.add(5, this.quadCount)` |
| `LevelRenderer.endFrame` (called once per frame from `Minecraft` L1349) | L1151-1153 | `TerrainStats.endFrame()` |

**Bench output** (`bench/src/bench/Bench.java`; harness code, not game code):
- Read `TerrainStats` by reflection, so that vanilla runs still work.
- Call `reset()` when entering MEASURE (L162-167).
- In `writeResults` (L244-282), emit `"terrain": {"sectionsPerFrame", "recordsPerFrame", "solidQuads",
  "cutoutQuads", "translucentQuads", "cloudQuads", "skippableFacing", "skippableFrustum",
  "translucentTail", "compactLayerShare"}` as per-frame means.
- Snapshot `total` at each one-second boundary (L179-183) and emit a `perSecondTerrainQuads` array next
  to `perSecondFps`. This lets the frame-time-vs-quads fit (culling §3.3) be redone on real counts.

**How to run it:** `tools/launch.py --jar build/client.jar --jvm -Dterrain.stats=true ...`
(`tools/launch.py` L94, L101).

### 3.2 S0b: dry-run bucket, bounds and compactness metadata (no render change)

This is S1's classifier without the vertex reorder or the draw change, so it is reused verbatim later.

**1. `BufferBuilder` (patched copy).**
- Add `enableTerrainInfo(ChunkSectionLayer layer)`.
- Call it only from `SectionCompiler.getOrBeginLayer` (L133-144).
- In the BLOCK fast path (L313-319), keep the 4 positions of the current quad in a `float[12]`.
- After every 4th vertex, call `classifyQuad` (code in §4.1) and update:
  - `int[7] bucketQuads`;
  - `float[6] plane`: the minimum plane for +axis buckets, the maximum for −axis buckets;
  - `float[6] bounds`: the min/max of all positions;
  - `boolean exact16`: the §4.5 round-trip test, opaque layers only.
- For TRANSLUCENT, also append one byte per quad to a growable `byte[] quadFacing`. A translucent quad whose
  plane lies outside [0, 16] is UNASSIGNED.

**2. `storeMesh` (L92-105).** Attach an immutable `TerrainMeshInfo(int[8] slotQuadStart, float[6] plane,
float[6] bounds, boolean exact16)` to the `MeshData`:
- add a new nullable field plus getter on `MeshData`;
- do **not** change the `DrawState` record (L88), which every builder uses.

**3. `SectionMesh.SectionDraw` (`SectionMesh` L45-46).** Add a nullable `TerrainMeshInfo info` component.
Its only constructor call is `CompiledSectionMesh` L43.

**4. `extractSectionDrawGroups` (L744-799).** Compute the S1 visibility mask (§4.1) and the S2 frustum test
(§4.2), and add the would-be-skipped quads to slots 6–9. Rendering is untouched.

### 3.3 S0c: record-cost probe

`-Dterrain.splitProbe=k` makes `extractSectionDrawGroups` L799 split every SOLID/CUTOUT `IndexedDraw` into
`k` records:
- the same total quads;
- `baseVertex` advanced by `4·quadsPerPart`;
- the same triangles in the same order, so it is pixel-identical.

Compare k = 1 and k = 2:
- the vertex-stage delta in the GPU trace, divided by the extra records, gives **GPU ns per record**;
- the CPU encode delta per record comes from the JFR/Instruments time profile.

Repeat on Metal once the M1 decoder implements `DRAW_INDEXED_INDIRECT` (`docs/metal/CONTRACT.md` L336).

### 3.4 S0d: GPU traces and captures

Capture on Vulkan now and repeat on Metal later. Use `xctrace` with the "Metal System Trace" template,
summarized by `tools/mst_gpu.py`.

| Run | Purpose |
|---|---|
| Bench, stats on | Baseline vertex and fragment ms per frame, alongside the S0a counters |
| Bench with `--opt renderClouds:false` | Cloud share of the vertex stage (open question in all three docs) |
| Bench with `-Dterrain.splitProbe=2` | GPU cost per record (S0c) |
| Instruments "GPU Counters" (Performance Limiters set) | Vertex-shader invocations per frame: does the GPU reuse vertices (4 vs 6 per quad)? Tiler utilisation, and whether vertex fetch is a limiter (gates S5) |
| One-frame GPU capture of the bench pose via MoltenVK's capture settings (e.g. `--env MTL_CAPTURE_ENABLED=1` plus MoltenVK's auto-capture options), later via the Metal backend | Per-draw vertex and fragment cost: terrain's share of the ~1.5 ms fragment stage, and CUTOUT fragment invocations per covered pixel (gates S6) |

### 3.5 Decision gates

| Measurement | Expected (model) | If different |
|---|---|---|
| Quads per frame (S0a) | ~414k | Rescale every saving in §0 linearly |
| Facing-skippable share, opaque (S0b) | 38–46% | Below 25%: S1 drops to rank 2 behind S2 |
| GPU ns per record (S0c) | < 50 ns | Above 200 ns: implement layout C (submission §3.3) with S1, or land the ICB (M4) first |
| Vertex-fetch limiter (S0d) | Not dominant | Dominant: S5 moves up to rank 2 |
| `compactLayerShare` (S0b) | SOLID ≳ 90%, CUTOUT 50–80% | Under 70% overall: drop S5 |
| CUTOUT overdraw (S0d capture) | Unknown | Under ~1.5 fragments per covered pixel: drop S6 |

---

## 4. The steps

Every step gets a kill switch: a `static volatile boolean` in a new `TerrainToggles` class, initialised from
`-Dterrain.<name>`, next to the existing debug fields `Minecraft.multiDrawIndirect` / `smartCull`
(`Minecraft` L362-364). These switches drive the A/B checks in §5.

### 4.1 S1: facing buckets for SOLID and CUTOUT, plus a 16-bit terrain quad index buffer

**Why it works.**
- Every terrain pipeline culls back faces:
  - `cull` defaults to true (`com/mojang/renderpearl/api/pipeline/RenderPipeline.java` L417);
  - no terrain snippet or pipeline calls `withCull(false)` (`RenderPipelines` L76-90, L346-428).
- Today the GPU fetches and shades these quads and only then discards them.

**Classification** (`BufferBuilder`, shared with S0b). The triangles are the ones the index pattern draws,
(0,1,2) and (2,3,0) (`RenderSystem` L50-57):

```java
// p = x0,y0,z0, x1,y1,z1, x2,y2,z2, x3,y3,z3 exactly as written to the vertex buffer
static int classifyQuad(float[] p) {
    for (int a = 0; a < 3; a++) {
        float c = p[a];
        if (p[3 + a] != c || p[6 + a] != c || p[9 + a] != c) continue;   // not planar on axis a
        int u = (a + 1) % 3, v = (a + 2) % 3;                             // (u, v, a) cyclic
        double n1 = cross(p, 0, 1, 2, u, v), n2 = cross(p, 2, 3, 0, u, v); // a-component, in double
        if (n1 > 0 && n2 > 0) return POS[a];   // EAST, UP, SOUTH
        if (n1 < 0 && n2 < 0) return NEG[a];   // WEST, DOWN, NORTH
        return UNASSIGNED;                      // bow-tie or zero area: always drawn
    }
    return UNASSIGNED;                          // rotated, sloped or non-planar: always drawn
}
// cross = (pj-pi)[u]*(pk-pi)[v] - (pj-pi)[v]*(pk-pi)[u]
```

- The sign convention matches `FaceBakery.calculateFacing` (L184-188), which vanilla renders as front faces.
- Unit tests:
  - every face of a stone block classifies as its own `Direction`;
  - water back faces (reversed winding, `FluidRenderer` L364-372) classify opposite to their front faces;
  - a 45° cross plant classifies as UNASSIGNED.

**Vertex reorder** (in `BufferBuilder.storeMesh` L92-105, SOLID/CUTOUT only):
- A stable counting sort of whole quads (112 B at 28 B per vertex) into layout A:
  `DOWN, NORTH, EAST, UP, UNASSIGNED, SOUTH, WEST`.
- It is applied **in place**: compute each quad's destination, then follow permutation cycles with one
  112-byte temporary and a visited bitset.
- No scratch buffer is needed, so `SectionBufferBuilderPack`'s size, and with it the pool sizing in
  `SectionBufferBuilderPool` L21, is unchanged.
- Coplanar same-facing quads keep their emission order; the SOLID → CUTOUT layer order is untouched
  (`ChunkSectionLayerGroup` L6).
- Quads share no vertices, because the index pattern is per quad.

**Data layout** (all immutable; about 100 B per section-layer):
- `TerrainMeshInfo.slotQuadStart[8]`: prefix sums in layout-A order;
- `plane[6]`: section-relative floats, NaN when the bucket is empty;
- `bounds[6]`;
- `exact16`.

**Draw emission** (`LevelRenderer.extractSectionDrawGroups`, replacing L799 for SOLID/CUTOUT):

```java
TerrainMeshInfo info = draw.info();
boolean cull = info != null && TerrainToggles.buckets && this.usingMultiDrawIndirectForTerrain
    && layer.pipeline(true).isCull();                      // RenderPipeline L82-84
Vec3 cam = this.levelRenderState.cameraRenderState.pos;    // same eye as GlobalSettingsUniform L29-35
if (cull) {
    double rx = cam.x - ox, ry = cam.y - oy, rz = cam.z - oz;   // ox.. = section.getRenderOrigin()
    int vis = 1 << SLOT_UNASSIGNED;
    if (ry < info.plane(DOWN)  + EPS) vis |= 1 << SLOT_DOWN;    // EPS = 0.25 (section 2, row 9)
    if (rz < info.plane(NORTH) + EPS) vis |= 1 << SLOT_NORTH;
    if (rx > info.plane(EAST)  - EPS) vis |= 1 << SLOT_EAST;
    if (ry > info.plane(UP)    - EPS) vis |= 1 << SLOT_UP;
    if (rz > info.plane(SOUTH) - EPS) vis |= 1 << SLOT_SOUTH;
    if (rx < info.plane(WEST)  + EPS) vis |= 1 << SLOT_WEST;
    TerrainRuns.emit(info, vis, 32, baseVertex, sectionInfoDataIndex, drawGroup.draws);
} else {
    TerrainRuns.emitWhole(draw.indexCount() / 6, baseVertex, sectionInfoDataIndex, drawGroup.draws);
}
```

**What `TerrainRuns.emit` does:**
- walks the 7 slots in order;
- a slot is *wanted* when it is visible and non-empty;
- runs of wanted slots merge across empty slots;
- unwanted slots totalling ≤ 32 quads between two wanted slots are bridged;
- each run becomes `IndexedDraw(6·q, 1, 0, baseVertex + 4·startQuad, sectionInfoIndex)`, split every
  16,384 quads (`baseVertex += 65,536`).

`emitWhole` applies the same split. All runs of a section share its vertex heap, so they land in the same
MDI group (`LevelRenderer` L781-797): more records, no new groups.

**16-bit terrain quad index buffer (S1b).**

*Why.* The shared quad buffer is replaced and re-typed whenever any user grows it:
- `IndexType.least(vertexCount)` (`RenderSystem` L439-469);
- clouds request `6 × quadCount` (`CloudRenderer` L189-190), which makes the shared buffer INT.

*Change.*
- `RenderSystem.getTerrainQuadIndexBuffer()`: a fixed SHORT buffer of 16,384 quads (98,304 indices,
  192 KiB), filled with the same `i, i+1, i+2, i+2, i+3, i` pattern and created with the index usage that
  `AutoStorageIndexBuffer` uses (`RenderSystem` L462).
- Bind it in `ChunkSectionsToRender.renderLayers` (L91-93) for every terrain path.
- Remove the terrain `requestIndexCount` calls (`LevelRenderer` L856-859, L958-961).
- Translucent draws keep their own sorted buffers (`hasCustomIndexBuffer`, `LevelRenderer` L764-776).

**Shader changes:** none.

**Metal backend.**
- Same 20-byte records, same groups, about +115–230 records per frame. On the M1 per-record loop that is
  ~+6–12 µs of CPU encode at ~52 ns per record (submission §0); with the ICB (M4) it is noise.
- The default terrain index type becomes `MTLIndexTypeUInt16`.
- No ABI change.
- The game never emits a zero-count run, but the decoder may still skip `indexCount == 0` for free.

**Vulkan.** The same MDI records, on the `prepareChunkRendersIndirect` path (L899-964).

**GL (separate path).**
- `prepareChunkRenders` (L811-862) keeps one draw per section-layer.
- The reordered vertices are drawn whole, so the result is the same image.
- Only the 16-bit index buffer and the >16,384-quad split apply.

**Expected saving.**
- About 155k ± 10k quads per frame skipped: SOLID 235k × 0.39 plus CUTOUT 147k × 0.51. Culling's model
  says 158k.
- At 0.12–0.24 ms per 100k quads that is 0.19–0.37 ms, less the record overhead (§1.2):
  **0.15–0.37 ms, central 0.27 ms.**
- 16-bit indices halve terrain index fetch, 24 → 12 B per quad, about 3 MB less per frame after S1:
  ≤ 0.02 ms, and only if fetch is a limiter.

**Verification.**
1. In-process A/B (§5.1) in three stages:
   - reorder only, culling off: expect 0 differing pixels, apart from T-junction ties;
   - culling on vs off: expect 0 differing pixels;
   - S1b on vs off: expect 0 differing pixels.
2. A debug assert, on sampled sections, that every quad in a skipped bucket satisfies
   `eye·n < plane − EPS + 0.112`.
3. Bench JSON:
   - `recordsPerFrame` rises by at most 30%;
   - `solidQuads + cutoutQuads` falls by the S0b `skippableFacing`;
   - median FPS rises over the 345 FPS Vulkan baseline (`results/BASELINE.md`).
4. GPU trace: the vertex-stage delta with `-Dterrain.buckets=false/true`.
5. GL: `tools/bench-matrix.sh <tag> build/client.jar opengl:true` shows no regression against 200–214 FPS.

**Depends on:** S0b (classifier and metadata).

### 4.2 S2: exact per-frame frustum on the draw list, with tight bounds

**Why vanilla over-draws here.**
- `visibleSections` is built against a frustum whose apex is pushed back until the camera's 8-block cell
  is inside (`Frustum.offsetToFullyIncludeCameraCube` L44-69, called by the graph).
- It is only rebuilt on 2° rotation steps or graph changes (`LevelExtractor` L140-149, L503-512).
- Culling's model puts the margin at 39k quads per frame (−9%), or 24k once S1 is in.

**Change.**
- In `GameRenderer.renderLevel`, after the nausea block (L655-663), build
  `new Frustum(cameraState.viewRotationMatrix, projectionMatrix)` (`Frustum` L20-22) and call
  `prepare(pos.x, pos.y, pos.z)` (L71-75).
  - `projectionMatrix` already includes bob and nausea (L642-662), and it is exactly what the shader gets
    (L665). The terrain model-view is the same rotation (`LevelRenderer` L263).
- Pass the frustum into `LevelRenderer.render` (signature L184-192; caller `GameRenderer` L672-674).
- In `extractSectionDrawGroups`, before the `getRenderSectionSlice` lookups (L746), skip any section-layer
  whose box `origin + bounds ± 1/64` misses this frustum.
  - Add a public `Frustum.isVisible(double, double, double, double, double, double)` wrapping
    `cubeInFrustum` (L92-100), so no `AABB` is allocated.
- When `camera.getCapturedFrustum() != null` (a debug capture, `LevelExtractor` L140), skip S2.
- Keep `visibleSections` unchanged: it still feeds the dirty-section scan (`LevelExtractor` L166-186),
  block entities and translucent resorts.

**Why the near and far planes are safe.** JOML-style plane extraction is exact for the side planes of any
projective matrix. With the reverse-Z, zero-to-one projection, the extracted "near" plane lies beyond the
far plane and the "far" plane is the true near plane, so both are conservative.

**Metal, Vulkan, GL.** Only fewer records. GL gets fewer real draws.

**Expected saving:** 24k quads after S1, i.e. **0.03–0.06 ms**; 0.05–0.09 ms alone. About 10% fewer
sections also go through the draw-list loop on the CPU.

**Verification.**
- In-process A/B with 0 differing pixels.
- Extra scenes with nausea and with bob, the latter as a walking frame (§5.2).
- `skippableFrustum` (S0b) matches the quads actually removed.

**Depends on:** S0b bounds.

### 4.3 S3: translucent dual index list ("head")

**Why translucent needs its own scheme.** Translucent terrain is one back-to-front sorted draw per section.
It uses the full `MeshData.SortState` index list (L91-116), which is rebuilt per point of view by
`ResortTransparencyTask` (`SectionRenderDispatcher` L486-537).

**Change.**

1. **`MeshData.SortState`.**
   - Add a component `byte @Nullable [] quadFacing`, from the S0b classifier.
   - Keep a two-argument constructor for the feature path (`StagedVertexBuffer` L154).
   - Add `buildSortedIndexBuffer(target, sorting, povX, povY, povZ)`. It writes the **vanilla list
     unchanged** (`writeIndices` L107-116), then a **head list**: the same order with the quads that are
     definitely back-facing for that POV left out.
   - "Definitely back-facing" means: bucket `+a` with `pov_a == −1`, or bucket `−a` with `pov_a == +1`.
     Quads whose plane lies outside [0, 16] are UNASSIGNED, which keeps the rule valid.
   - It returns `(result, fullIndices = 6n, headIndices = 6h)`.

2. **Call sites.**
   - Compile time: `SectionCompiler.compile` L120-122. The POV that `CompileTask` computes at L433 moves
     above the compile call (same `cameraPos`, L425) and is passed in. `MeshData.sortQuads` (L68-78) keeps
     `h`.
   - Resort: `ResortTransparencyTask` L510 passes its POV (L505).

3. **Upload.** `addSectionBuffersToUberBuffer` (L368-401) takes `(n, h, pov)`. Its index callback (closure
   at L384-387) stores them in render-thread-only fields of `CompiledSectionMesh`. The callback runs right
   after `allocationMap.put` (`UberGpuBuffer` L104-105), so the fields always describe the allocation that
   `getRenderSectionSlice` returns.

4. **Draw** (`LevelRenderer` L776, L799).
   - Compute the camera's strict POV per axis: `+1` if `c ≥ o+16+EPS`, `−1` if `c ≤ o−EPS`, else 0.
   - If every non-zero component of the buffer's POV equals the strict POV, emit
     `IndexedDraw(6h, 1, firstIndex + 6n, ...)`.
   - Otherwise emit `IndexedDraw(6n, 1, firstIndex, ...)`, exactly vanilla's list.

**Why it is identical.**
- In head mode, every dropped quad is back-facing for any eye within 0.112 of `c`, so it produces no
  fragments. The survivors keep vanilla's relative order, and the section order (L921-929) is untouched.
- When stale, the draw is vanilla's own stale list. Nothing depends on resort timing, so meshing's "check
  every section every frame" is not needed.
- Under OIT (resorts disabled, L1254), the compile-time POV gives the same guarantee.

**Data.** Translucent index bytes grow by up to 2× (h ≤ n). That is small: there are 31k translucent quads
per frame, mostly 16-bit indices.

**Metal, Vulkan, GL.** One record per section as today; only `indexCount` and `firstIndex` change. No
backend change.

**Expected saving:**
- bench: 31k × 0.43 ≈ 13k quads, i.e. **0.016–0.03 ms**;
- over oceans, where every water surface is a front quad plus a reversed back quad (`FluidRenderer` L193,
  L364-372): roughly 0.1 ms or more (estimate).

**Verification.**
- In-process A/B with 0 differing pixels: `water_surface`, `underwater`, and a new low-altitude ocean scene.
- A fly-through over water crossing section boundaries, with a debug counter of "fallback draws per frame"
  to confirm the fallback is taken while resorts are pending.

**Depends on:** S0b (translucent `quadFacing`).

### 4.4 S4: CPU companion (descriptors, instance stream, cached draw list)

This is the submission doc's P1–P3 (§4), adjusted for S2. It is not a GPU win. It keeps the extra S1
records, and the per-frame S2 test, cheap.

**P1: mesh-side descriptors.**
- `UberGpuBuffer.UploadCallback.bufferHasBeenUploaded` (L199-201) also receives the heap buffer, a stable
  `heapId` (assigned at heap creation, L86-98) and `offsetFromHeap` (call site L100-105).
- `SectionRenderDispatcher.RenderSection.vertexBufferUploadCallback` / `indexBufferUploadCallback`
  (L356-366) store them on `CompiledSectionMesh`, together with S3's `(n, h, pov)`.
- Building the draw list then needs neither `copyLock` (L730) nor `getRenderSectionSlice` (L111-130).

**P2: persistent `CHUNK_DATA_INSTANCED` buffer.**
- Indexed by `RenderSection.index` (L202), replacing the per-frame `writeChunkSectionsInstanced`
  (`DynamicGpuData` L100-111).
- Written when a slot gets a new node (`setSectionNode` L226-234) and while sections fade in
  (`updateUploadTime` L318-322).

**P3: `TerrainDrawList`.**
- Caches **per-section run records**, keyed by a camera "safe box": the nearest bucket plane ± EPS on each
  axis.
- Each frame it then:
  1. runs the S2 frustum test per section;
  2. copies the cached records of the passing sections into the existing `MAP_WRITE` command ring
     (`DynamicGpuData` L113-119) with one bulk write.
- Groups are keyed by `(layer, format, heapId)`, not by hash code (L757-788).

**Expected effect:** ~36 µs → ~10 µs per frame, ~46 KB/frame of garbage → 0 (submission §1.7).

**Metal.** Keep the records in the `MAP_WRITE` ring, so the decoder's "CPU-decode the shared records"
option stays valid (`docs/metal/CONTRACT.md` L336).

**Verification.**
- Debug mode `-Dterrain.drawListVerify=true` rebuilds the list from scratch every frame and compares it
  record by record with the cached list.
- A/B pixels: 0 differences.

**Depends on:** S1–S3, because it absorbs their emission code.

**Status (round 2, `TerrainToggles.fastDrawList`, default on).** Implemented as `chunk/TerrainDrawList`, used by
`prepareChunkRendersIndirect` (the non-MDI path is unchanged):
- P1 as planned, with one difference: the uber buffers tell the mesh about every allocation change through an
  `UberGpuBuffer.AllocationListener` (installed by `SectionRenderDispatcher`), so the mesh mirrors exactly what
  `getRenderSectionSlice` returns, including translucent re-sorts. The mesh also packs, per layer, what the culling reads
  (`CompiledSectionMesh.hot`: flags, index count/type, bounds, bucket planes, offsets).
- P3 reduced to a per-section-layer one-entry cache of the S1 runs keyed by the visible-bucket mask; records go into
  reused int arrays and reach the existing `MAP_WRITE` ring in one bulk copy (`DynamicGpuData.writeChunkSectionCommandsRaw`).
- P2 not done: section infos are still written every frame, now with one bulk copy (`writeChunkSectionsInstancedRaw`).
- Verification: `tools/terrain-drawlist-bench.sh check` builds every frame of a churning synthetic world with both paths
  into separate rings and compares every byte; `-Dmc.terrain.drawListVerify=true` compares in game. Cost:
  `tools/terrain-drawlist-bench.sh bench`.

### 4.5 S5: compact 16-byte vertex, exact-or-fallback (gated)

**Layout.** A new `DefaultVertexFormat.TERRAIN_COMPACT`, laid out as meshing §5.1 proposes:

| Offset | Attribute | Format | Contents |
|---|---|---|---|
| 0 | `Position` | `RGBA16_UINT` | x, y, z = `(p+8)·2048`; w = `block \| sky<<8` |
| 8 | `Color` | `RGBA8_UNORM` | Unchanged |
| 12 | `UV0` | `RG16_UINT` | `u·65536`, `v·65536` |

**Change.**

1. **Transcode during S1's reorder** (`BufferBuilder.storeMesh`, in place: 28 → 16 B per vertex never
   overtakes the read pointer). Transcode only if **every** vertex of the section-layer satisfies all of:
   - `((double)p + 8) * 2048` is an integer in [0, 65535] for x, y and z;
   - `u * 65536` and `v * 65536` are integers in [0, 65535];
   - `(light & 0xFF00FF00) == 0`.

   Otherwise keep the 28-byte BLOCK vertices (`DefaultVertexFormat` L27-32). TRANSLUCENT always stays BLOCK,
   so `decodeQuadCentroids` (`MeshData` L23-50) and the sort keys are untouched.
2. **Heaps.** Separate compact vertex `UberGpuBuffer`s for SOLID and CUTOUT, alongside L70-75 (alignment
   16). `addSectionBuffersToUberBuffer` picks the heap by mesh format. S4's descriptors carry the format.
3. **Pipelines.** New `SOLID_TERRAIN_COMPACT(_MULTIDRAW)`, `CUTOUT_TERRAIN_COMPACT(_MULTIDRAW)` and
   `WIREFRAME_COMPACT(_MULTIDRAW)`, built like L346-389 with `.withVertexBinding(0, TERRAIN_COMPACT)` and
   `.withShaderDefine("TERRAIN_COMPACT")`. `ChunkSectionsToRender.DrawIndirect.render` (L129-131) sets the
   pipeline per group format.
4. **Shader** (`terrain.vsh`, override in `client/resources`): under `#ifdef TERRAIN_COMPACT`:

   ```glsl
   layout(location = 0) in uvec4 Position;
   layout(location = 1) in vec4 Color;
   layout(location = 2) in uvec2 UV0;
   ...
   vec3 pos = vec3(Position.xyz) * (1.0 / 2048.0) + vec3(ChunkPosition - CameraBlockPos - 8) + CameraOffset;
   ivec2 light = ivec2(int(Position.w & 0xFFu), int(Position.w >> 8u));
   vertexColor = Color * sample_lightmap(Sampler2, light);
   texCoord0 = vec2(UV0) * (1.0 / 65536.0);
   ```

   The `#else` branch is vanilla L13-16, L33, L39, L43. The non-multidraw variant reads `ChunkPosition` from
   the `ChunkSection` UBO, as today.

**Why the output is identical.** For accepted layers:
- `q·2^-11` is exactly `p+8` and the integer term is exact, so the first add rounds the same real number
  as vanilla's `Position + (ChunkPosition − CameraBlockPos)` (L33).
- The UV and light decodes reproduce vanilla's inputs bit for bit.

The compiler's fast-math reassociation could still differ between the two shader variants, which is why
§5.1 checks the result with an A/B.

**Metal.** Vertex formats `UShort4 @0`, `UChar4Normalized @8`, `UShort2 @12`, stride 16:
- all offsets satisfy the `% 4` rule (`docs/renderpearl/00-metal-backend-spec.md` L466);
- `RGBA16_UINT`/`RG16_UINT` map to `UShort4`/`UShort2` in the spec's format table (spec L512, L516);
- `baseVertex` ≤ 128 MiB / 16 = 8.4M.

**Vulkan.** `RGBA16_UINT`/`RG16_UINT` exist (`com/mojang/renderpearl/backend/vulkan/VulkanConst.java`
L137, L141).

**GL.** Integer attributes go through `glVertexAttribIPointer` / `glVertexAttribIFormat`
(`com/mojang/renderpearl/backend/opengl/VertexArray.java` L86-87, L134-135). GLSL 330 has `uvec4`.

**Expected saving.**
- About 12 B less per vertex on compact layers, about 11 MB/frame less fetch after S1+S2.
- **0–0.08 ms** (central 0.03), and only if S0d shows vertex fetch limiting.
- Certain regardless:
  - resident mesh memory about −40% (meshing §6.2: 243 → 139 MB if everything were compact);
  - chunk upload traffic −43% on compact layers.

**Verification.**
- In-process A/B with a mesh rebuild between shots (§5.1): 0 differing pixels.
- `compactLayerShare` in the bench JSON.
- A unit test over all baked vanilla block models that lists which ones fall back: expect the rotated and
  non-dyadic models meshing counts (§5.2).

**Depends on:** S1 (reorder pass) and S4 (descriptors and groups keyed by format).

**Gate:** S0d fetch-limiter result and `compactLayerShare` ≥ 70%.

**Status (round 4, `TerrainToggles.COMPACT_VERTICES`, default OFF).** Implemented as planned, with these differences:
- Encoding `chunk/TerrainCompact`: UV is `uv * 2^15` (covers the atlas edge u = 1), position decode
  `float(int(q) - 16384) * 2^-11`; acceptance is a bit-exact round trip of every component (−0.0 rejected).
- Transcode in `SectionCompiler` after the S1 reorder (not in `BufferBuilder.storeMesh`); compact and 28-byte meshes share
  the layer's vertex heaps, aligned to 112 bytes, instead of separate compact heaps. Groups and descriptors carry the
  format; `ChunkSectionsToRender` switches to the `*_COMPACT` pipeline where it changes (multidraw and per-section paths,
  wireframe too).
- Gate result: `compactLayerShare` is 26–29% of layers (10–12% of bytes) in the snowy bench world and 56% (26% of bytes)
  in the travel world, mostly because of `snow_height2`'s 1/8000-block inner element, plant offsets and rotated elements.
- Extra modes: snap (near-grid tolerance; accepts nothing more in 26.3) and lossy (every layer, error ≤ 2^-12 block /
  2^-16 UV).
- In game: exact no measurable FPS change; lossy +3–6% in travel, ~0 static; −40% resident vertex memory and −43% upload
  with lossy. Default off: lossy changes the image by design and still needs GOAL (b)/(c).

Details: `results/S5-COMPACT.md`, `docs/metal/CONTRACT.md` 16.9.

### 4.6 S6: front-to-back order inside CUTOUT buckets (fragment, gated)

**What the GPU can and cannot hide.**
- SOLID needs no ordering: hidden-surface removal shades each opaque pixel once.
- CUTOUT uses `discard` (`terrain.fsh` L40-44; `ALPHA_CUTOUT 0.5`, `RenderPipelines` L376-389), so its
  fragments are shaded whenever they pass the depth test.
- Leaves draw the faces between leaf blocks (`LeavesBlock` L45-46 with `cutoutLeaves:true` in
  `run/vanilla/options.txt` L5).
- Blocks are emitted x-fastest, then y, then z (`BlockPos` L395-398). That is back-to-front for the
  quads of all three positive buckets.

**Change.** In S1's reorder, for CUTOUT only:
- sort quads inside each **positive** bucket by plane, descending;
- sort inside each negative bucket ascending;
- stable, keyed on the plane value.

For any eye that sees a quad, a larger plane in a positive bucket is nearer, so the order becomes
camera-independent front-to-back within each bucket.

**Why it is identical.** Quads at different planes in the same bucket are parallel. They never tie in depth
at a pixel, so the result does not depend on order (no blending in CUTOUT). Coplanar quads keep emission
order.

**Backends:** none affected.

**Expected saving:** unknown, at most about 0.1 ms in forest views. Measure CUTOUT fragment invocations per
covered pixel in the S0d capture first.

**Verification:** in-process A/B with 0 differing pixels; fragment-stage time from the trace in a forest
scene.

**Status (round 4, `TerrainToggles.cutoutFrontToBack` + `sortCutoutSections`, default on).**
- Implemented as planned, plus a per-frame front-to-back sort of the CUTOUT records by section distance (both list
  builders), and SOLID variants for diagnostics (default off).
- Pixel-identical:
  - `tools/terrain-e2e-test.sh` "cutout canopy": 0 px in every pose;
  - `terrain-drawlist-bench check`: byte-identical lists;
  - 18-scene parity passes.
- In game: +1.6% median FPS in a fixed forest view (yaw 180), +1.0% at yaw 120; no measurable change in the static
  scorecard or the travel benchmark.
- Details and every run: `docs/metal/CONTRACT.md` §16.10.

### 4.7 S7: cloud faces culled to a widened frustum (adjacent, same vertex budget)

This is culling P3, unchanged in design.
- In `CloudRenderer.buildMesh` (L245-277), keep the full ring-ordered face list in a CPU array.
- Write only the faces whose 12×4×12 cell box intersects the widened frustum, into the UTB (L182-185).
- Widen the frustum by one cell, to cover drift within the cell (offset in the UBO, L192-197), and by about
  10° of angle.
- Re-filter on yaw/pitch drift beyond 10°, on FOV/aspect changes, or when the existing rebuild triggers
  fire (L170-186).
- Write all faces while hurt tilt or nausea is active (`GameRenderer` L339-358, L653-662).
- Survivors keep their nearest-first order, which matters: clouds blend **and** write depth.

**Expected saving:** about 24k quads, i.e. **0.03–0.06 ms**. The shared quad index buffer stays INT, but
terrain no longer uses it after S1b.

**Verification:** in-process A/B on the `clouds` scene: 0 differing pixels.

### 4.8 S8: real visibility set for compiled sections with no geometry (optional)

**What vanilla does.** `CompileTask` stores sections with no layers and no block entities as `EMPTY`, which
"sees" through every face (`SectionRenderDispatcher` L435-436; `CompiledSectionMesh` L20-25).

**Change.** Store a geometry-less `CompiledSectionMesh` that keeps `results.visibilitySet` (`SectionCompiler`
L129). Air-only sections stay `EMPTY` (`SectionOcclusionGraph` L276-282).

**Expected saving:** about 0.02 ms of GPU, plus about 10% fewer sections in the CPU scans (culling P2b).

**Why it stays a toggle.** The cave graph is heuristic, so the step is off by default until the scene
sweep in §5.2 shows 0 visible-section losses.

### 4.9 Fragment-side items considered

**RGSS-only shader specialization.**
- `terrain.fsh` L36 selects `sampleRGSS` or `sampleNearest` at runtime from the `UseRgss` global
  (`GlobalSettingsUniform` L39).
- `sampleRGSS` costs 8 `textureLod` plus a `textureGrad` (`texture_sampling.glsl` L26-74).
- The bench, and vanilla's default, use `textureFiltering:0` (NONE) (`run/vanilla/options.txt` L26;
  `Options` L446).
- A define-specialized pipeline would only help through register pressure. Check the terrain fragment
  shader's register count in the S0d capture before doing anything.
- Skipping the RGSS samples when `blendFactor == 0` would be bit-exact (`mix(x, y, 0) == x`), but only
  matters for players with RGSS on.

**Back faces and off-screen geometry.** S1, S2, S3 and S7 do not reduce fragment work: none of the removed
primitives produced fragments.

**Fully fogged terrain.** It cannot be culled: it writes the fog colour and depth over a sky gradient
(culling §2.8).

---

## 5. Verification harness changes

### 5.1 In-process A/B diff (the parity proof)

**Why cross-run diffs are not enough.** Two vanilla Vulkan runs already differ from each other. Measured
with `tools/ImgDiff.java` on `run/shots-vk1` against `run/shots-vk2`:
- `day`: MAE 1.08, 0.006% of pixels off by more than 48;
- `water_surface`: 3.6% of pixels off by more than 16.

The existing bar (≤ 0.1% of pixels off by more than 48, `tools/ImgDiff.java` L16-19) can catch
regressions, but it cannot prove identity.

**New `AB` phase in `bench/src/bench/Scenes.java`** (between SHOOT L161-167 and CLEANUP L174-183):
1. Grab shot A with the scene's toggles off.
2. Flip the `TerrainToggles` field under test.
3. Wait 2 frames and confirm the client tick counter has not advanced; otherwise retry. This keeps animated
   textures identical.
4. Grab shot B.
5. Write both images, plus a strict count of pixels whose channel difference is greater than 0.

For mesh-time toggles (reorder, S5, S6):
- call `LevelExtractor.allChanged()` (L536-546) between the shots;
- wait for `hasRenderedAllSections()` (`LevelRenderer` L1279-1281);
- wait out the section fade-in (`chunkSectionFadeInTime 0.75`, `run/vanilla/options.txt` L4).

**Pass criteria.**
- 0 differing pixels for S1 culling, S1b, S2, S3, S5, S6 and S7.
- For S1's reorder: 0 differing pixels, or a handful on T-junction lines, inspected in the heatmap.

### 5.2 Terrain-specific scenes (added to `Scenes.build`, L57-116)

| Scene | Camera setup | Exercises |
|---|---|---|
| `section_edge` | Camera at x, y, z = 16k ± 0.05 near the anchor | S1 EPS, S3 strict POV |
| `canopy` | Inside the spruce canopy | CUTOUT buckets, S6 |
| `ocean_low` | 2 blocks above open water, pitch 5° | S3 |
| `plants_flow` | Flowing water, ferns, grass | S5 fallback, UNASSIGNED buckets |
| `nausea` | `effect give @s nausea` | S2 with the projection skew |
| `cave_mouth` | Looking into a ravine | S8 |

**Bob.** Bob only happens while walking on the ground. Cover it with:
- a unit test of the bound;
- a debug assert each frame that the translation column of `bobStack.last().pose()` (`GameRenderer`
  L643-649) has length < EPS.

### 5.3 Performance runs

Run `tools/bench-matrix.sh <tag> build/client.jar vulkan:true opengl:true`, plus one run per toggle via
`--jvm -Dterrain.<x>=false`. Report:
- median FPS and the 1% low;
- the `terrain` block from S0a;
- vertex and fragment ms per frame from `tools/mst_gpu.py`.

Re-baseline on the Metal backend once M1 runs.

---

## 6. Order and dependencies

```
S0a counters ─┬─> S0b dry-run metadata ─┬─> S1 buckets + S1b 16-bit IB ──┬─> S4 CPU draw list ──> S5 compact (gated)
              │                         ├─> S2 exact frustum ────────────┤
              │                         └─> S3 translucent dual list ────┘
              ├─> S0c record probe ──> (decides layout A vs C, bridging threshold)
              └─> S0d traces ─────────> (gates S5, S6; sizes everything)
S7 clouds: independent.   S6 cutout order: after S1, gated.   S8: independent, toggle.
```

**Milestones.**
1. **A:** S0a–S0d on Vulkan. Publish measured counts and update §0.
2. **B:** S1 + S1b + S2, measured A/B on Vulkan, GL regression check.
3. **C:** S3 + S4 + S7.
4. **D:** gated S5, S6 and S8.
5. **E:** repeat milestone A's measurements on Metal M1 (per-record loop) and again at M4 (ICB). Revisit
   layout C only if per-record GPU cost exceeds about 200 ns.

---

## 7. Deferred or rejected

| Idea | Why |
|---|---|
| HiZ / occlusion beyond the cave graph | A two-pass depth pyramid at 8 MP costs ~140 MB of traffic (~0.9 ms) on a TBDR GPU, against 0.07–0.11 ms saved. RenderPearl also has no compute shaders (culling P5) |
| Camera-relative layout C | Only matters if S0c shows records are expensive; stale-layout behaviour is unproven (submission §3.3) |
| Bucketing translucent under OIT | OIT is off by default (`improvedTransparency:false`, `run/vanilla/options.txt` L28) and costs ~10 extra passes (07 §2.2) |
| Meshing's T2 (drop quads with a 1-block margin) | Can miss faces during resort lag; replaced by the S3 dual list |
| Submission's head/tail fallback | Not vanilla order while stale (§2 row 4); replaced by the S3 dual list |
| 12-byte vertex, packed 10/11-bit positions | Breaks colour exactness, or has no Metal vertex format (meshing §5.2) |
| Precomputing `ProjMat * ModelViewMat` on the CPU (`terrain.vsh` L34) | Not bit-exact against the GPU product. Apple's compiler may already hoist it per draw; check in S0d |
| Fog culling, far-plane culling, frustum-restricted cave search, `cutoutLeaves=false` | Change pixels or save nothing (culling §0) |

---

## 8. Open questions

1. How is the ~1.1 ms vertex stage split between shading and binning? S0d's A/B with
   `-Dterrain.buckets` answers it directly and pins S1 within 0.15–0.37 ms.
2. What does an extra indirect record cost on the GPU, on MoltenVK now and on the Metal M1 loop and M4 ICB
   later? Answered by S0c.
3. How sensitive are the bucket shares to biome? Only a snowy-taiga world was modelled. Run S0b's counters
   in a plains world and in an ocean world.
4. What is terrain's share of the ~1.5 ms fragment stage, and how much CUTOUT overdraw is there? Answered
   by the S0d frame capture. This decides whether S6 is worth doing at all.

---

## 9. Implementation, round 1 (S0, S1 + S1b, S2, S7)

### 9.1 Switches

All default ON except the counters; `-Dmc.terrain=false` turns every step off (vanilla behaviour).

| Property | Step | Off means |
|---|---|---|
| `-Dmc.terrain.faceBuckets=false` | S1 | No reorder for newly meshed sections; every bucket drawn |
| `-Dmc.terrain.shortIndices=false` | S1b | Vanilla shared sequential quad buffer, no 16383-quad split |
| `-Dmc.terrain.exactFrustum=false` | S2 | No per-frame frustum on the draw list |
| `-Dmc.terrain.cloudCull=false` | S7 | Vanilla cloud upload (all faces) |
| `-Dmc.terrain.bridgeQuads=N` | S1 | Bridging threshold (default 32) |
| `-Dmc.terrain.splitProbe=k` | S0c | Split every SOLID/CUTOUT record into k (default 1 = off) |
| `-Dmc.terrain.stats=true` (or `-Dterrain.stats=true`) | S0a/S0b | Counters off (default) |

The fields live in `net.minecraft.client.renderer.chunk.TerrainToggles` (volatile, read once per frame).

### 9.2 Where the code is

| File (`client/src/...`) | Change |
|---|---|
| `chunk/TerrainMeshInfo` (new) | §4.1 classifier (exact in double), stable in-place counting sort of whole quads into layout A, bucket planes, bounds |
| `chunk/TerrainRuns` (new) | Visible-bucket runs, bridging, record emission, 16383-quad split, S0c probe |
| `chunk/TerrainFrustum` (new) | Exact frame frustum (4 side planes + `w >= 0`), centre of projection, orientation sign, corner rays |
| `chunk/TerrainStats`, `chunk/TerrainToggles` (new) | S0a counters, switches |
| `CloudFaceFilter` (new) | S7 selection and reference test |
| `chunk/SectionCompiler` | `TerrainMeshInfo.analyze` on every built layer (reorder for SOLID/CUTOUT) → `Results.terrainInfo` |
| `chunk/SectionMesh`, `chunk/CompiledSectionMesh` | `SectionDraw.info` |
| `chunk/ChunkSectionsToRender` | `withTerrainQuadIndexBuffer` (default index buffer override) |
| `LevelRenderer` | Frame projection hand-over, S2 + S1 in `extractSectionDrawGroups`, S1b buffer ownership, stats, cloud projection |
| `GameRenderer` | One line: `levelRenderer.setFrameProjection(projectionMatrix)` after the upload in `renderLevel` |
| `CloudRenderer` | S7 path in `prepare` |
| `bench/src/bench/TerrainStatsHook` (new), `Bench` | Reflection hook: `"terrain"` block and `perSecondTerrainQuads` (`null` on vanilla) |

### 9.3 Decisions beyond the plan

- **Classification site.** `SectionCompiler.compile` classifies from the built vertex buffer (the positions exactly as
  written, every emission path included), instead of hooking `BufferBuilder`; `BufferBuilder`, `MeshData` and
  `DrawState` stay vanilla.
- **16383 quads per 16-bit draw, not 16384.** The largest index is then 65531: `0xFFFF` is the 16-bit
  primitive-restart value Metal always honours, and vanilla never puts it in a 16-bit buffer either
  (`IndexType.least`). Split records advance `baseVertex` by 65532.
- **S1 eye.** The visibility mask uses the exact centre of projection of the frame matrix (bob included), computed
  from the inverse of `projection * viewRotation`, with the plan's 0.25-block margin on top. S1 is disabled for a
  frame whose orientation sign differs from the unbobbed camera projection's, or without a frame projection.
- **S2 planes.** Only `-w <= x, y <= w` and `w >= 0`: valid whatever a backend does at near/far (clip or clamp).
  Bounds margin 1/64 block. Skipped while a debug frustum capture is active.
- **S7 reference.** Instead of a widened plane frustum: a cone around the reference view axis (widest corner ray
  + 10°), cells kept when their bounding sphere (+6 blocks of cloud-grid drift + 0.5 eye offset) meets it. A frame
  reuses the upload only if all four corner rays of its own view volume lie in the cone, its eye is within 0.5 of
  the camera and the grid moved ≤ 6 blocks; hurt tilt, bob and nausea are therefore covered by the same test (no
  special case). Re-filter = ring-buffer rotate + upload, as a vanilla rebuild.
- **GL / non-MDI.** The separate-draw path never culls buckets (one draw per section-layer as before, split only
  above 16383 quads); it still gets S2 and S7.
- **Translucent** is classified (S0b dry-run counters) but never reordered or culled by facing; S2 applies to it.

### 9.4 Tests

- `tools/terrain-test.sh` (CPU): stone faces of several cuboids classify as their own direction (all vertex
  rotations; reversed winding → opposite), water top/sides and their back faces, cross plants / bow-ties /
  degenerate / non-planar / NaN → UNASSIGNED; analyze + in-place sort on random meshes (counts, prefix sums,
  layout order, stability, whole quads moved, bytes outside the range untouched, planes, bounds); mask margins per
  axis and a property test (every skipped quad is behind the eye by ≥ EPS); run merging/bridging cases and a
  20 000-case property test; record split and probe; frustum conservativeness against sampled clip-space points,
  bob/nausea eye and orientation; cloud filter conservativeness against sampled cells for covered frames.
- `tools/terrain-e2e-test.sh` (GPU, Metal, `MTL_DEBUG_LAYER=1`): vanilla `SOLID_TERRAIN_MULTIDRAW` through indirect
  records, emission order + 32-bit indices vs. (B) reordered + 16-bit split, (C/D) visible buckets with bridging
  32/0, (E) + exact frustum, for 8 poses (outside, tunnel, section boundary ± 0.03, view bob, nausea, from below):
  colour and depth bit-identical. `CLOUDS` (blend + depth write) all faces vs. the filtered subset for covered
  frames below/above/inside the layer: bit-identical.
