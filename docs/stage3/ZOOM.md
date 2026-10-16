# Zoom at full image quality: far terrain (stage 3), shaderpacks (stage 4), ray tracing (stage 5)

Requirement: `docs/GOAL.md`, amendment "Zoom (user, 2026-10-05)". An OptiFine-style zoom key (our own code, up to
about 50x) and the vanilla spyglass must keep the same image quality at every zoom level, refine without visible
popping, and stay invisible to servers.

This doc says how stages 3–5 meet that bar, how fast it can be, and what is hard or still unknown. Stage 3 has no
design doc yet. This doc therefore fixes the requirements that zoom puts on it (§2–§7) without choosing its
internals.

**Citations.**
- Paths are relative to `src/vanilla` unless they start with `docs/`, `client/`, `results/` or `run/`.
- "L123" means line 123 of the file just named.
- Every number carries one of three tags:
  - *(measured)*: comes from this repo's measurements;
  - *(model)*: computed here from stated assumptions (scripts were in the session scratchpad);
  - *(estimate)*: an engineering guess that an S0-style probe must replace before anyone relies on it.

**Notation.**
- `H` = 1964: the panel's physical rows. GOAL measures the image at 3024x1964. The game now renders 3600x2338
  (non-Spaces fullscreen, `results/ROUND3.md`), which the compositor scales by 0.84 onto all 1,964 rows. (The
  older Spaces fullscreen of `results/BASELINE.md`, 3600x2260, shows only ~1,898 panel rows below the notch.)
  Error budgets here are in panel pixels.
- `K` = `(H/2) / tan(fov_v/2)`: the focal length in panel pixels for the vertical FOV `fov_v`.
- `Z` = `tan(fov_option/2) / tan(fov/2)`: the linear magnification at the screen centre.
- `R` = 4096 blocks: the LOD radius (256 chunks).

---

## 0. Summary

**Key decisions**

| # | Decision | Where |
|---|---|---|
| D1 | The zoom key is a `Z` multiplier applied inside `Camera.calculateFov`. It is **not** a change of `options.fov`, which would rebuild the cave graph, could be persisted to options.txt and cannot go below 30°, and **not** a change of `fovModifier`, which is clamped to ≥ 0.1. Range: `Z` up to 50 at FOV 70 (1.6°), with an absolute cap `K ≤ 70,122 px` (the FOV-70, 50x value) | §1.5 |
| D2 | The finest LOD level L0 must be **vanilla-exact section meshing** (block models, AO, tints, animated atlas) from cached block data. At 50x a block 4096 blocks away is 17 px tall, like a block 82 blocks away unzoomed | §2 |
| D3 | LOD selection is by screen-space error (SSE) with this frame's `K`, recomputed every frame on the GPU. It has two thresholds, geometric (`τ_geo` ≈ 0.5–1 px) and texture (`τ_tex` ≈ 1–2 px per block), plus ×2 hysteresis | §3 |
| D4 | Drawing uses the exact narrow frustum. Everything with latency uses the wide frustum plus the zoom-target cone: graph, compile scheduling, LOD residency, meshing, BLAS builds. **Vanilla plus our S2 already split it this way for sections**, because the cull FOV is `max(fov, option fov)` | §1.2, §7 |
| D5 | Full block-level data is kept on disk for the whole LOD disc: about 1.2–1.3 GB (1.20–1.26 GiB) per 256-chunk disc at vanilla's measured 6.1–6.4 KiB per chunk. Singleplayer regenerates from the seed into the cache only, never into the save. Multiplayer shows only what the server sent | §5 |
| D6 | Prefetch starts when the spyglass is in a hand. A small 50x cone around the crosshair stays resident whenever the zoom key is bound. Coarse data stays until the fine data is resident. Late refinements use a dithered cross-fade. Vanilla's section fade is skipped where L0 LOD already showed that section | §6 |
| D7 | Spyglass scope mask: draws are culled to the overlay's clear **square** window (26.3's scope texture is a square frame, not a circle), and the fully opaque part of the overlay becomes a depth-only mask, so HSR rejects ~37% of world fragments with the scope fully open (up to ~88% at the start of the animation). Pixel-identical | §4 |
| D8 | Stage 4: the shadow projection is fitted to the visible samples of the zoomed view (SDSM), centred on the focus, so radial-distortion packs keep working. A per-pack allowlist covers this. L0 LOD is drawn through `gbuffers_terrain`, not the colour-only `dh_*` programs | §8 |
| D9 | Stage 5: RT shadows and reflections need no zoom-specific resolution. The BVH holds the residency set at the same LOD as the raster in view; BLASes are built at prefetch time. MetalFX temporal history degrades during the zoom animation | §9 |
| D10 | Entity distance scales with `Z` as a monotone OR with vanilla's test. It is capped by the server tracking range, which nothing client-side can lift. Name tags stay vanilla, because their distance is a server-controlled attribute | §10 |

**Main risks**

| Risk | Why | Mitigation / gate |
|---|---|---|
| Worst-case geometry at moderate zoom | SSE keeps primitives roughly constant, but the ground-plane model peaks at 1.3–2× unzoomed (Z ≈ 2–4). Forested mountains with depth complexity: plan for 3–4× | Facing buckets per node, zoom-gated two-phase HiZ, scope mask; scene Z5 gates |
| Refinement too slow | The spyglass needs L0 out to 4096 blocks after about 100 ms. Vanilla-exact meshing throughput is unmeasured; the estimate is 0.2–1.4 s for the steady cone | Prefetch P1/P3. Fallback: GPU L0 meshing. Measure first (§6.2) |
| Fast pans while zoomed | ~20k new chunk columns/s at the leading edge at 40°/s *(model)* | Cross-fade; GPU meshing gate |
| Shaderpack shadows | Packs own their shadow math. Fitting is safe only where the pack uses the uniforms consistently | Per-pack allowlist; scene Z9 |
| Multiplayer | No LOD where the player never went; entities pop at the tracking range (140–245 px tall through the spyglass) | Unfixable without server changes; documented, optional fade (user decision) |
| Memory | Steady spyglass cone L0 meshes: 0.17–1.6 GB *(estimate)* | Compact or vertex-pulled formats; release when the spyglass leaves the hand |

---

## 1. What vanilla 26.3 does when zooming

### 1.1 The spyglass FOV path

- **The modifier.** While scoping in first person, `AbstractClientPlayer.getFieldOfViewModifier` returns `0.1F`
  (L108-110). That happens before the `fovEffectScale` lerp (L113), so the spyglass ignores the "FOV effects"
  option. Flying multiplies the modifier by 1.1 (L94-96).
- **The animation.** `Camera.tickFov` moves `fovModifier` halfway to its target each tick (L176) and clamps it to
  [0.1, 1.5] (L177).
- **Per frame.** `Camera.calculateFov` lerps the old and new modifier with the frame's partial tick (L227), so the
  FOV changes every frame, not every tick.
- **The projection.** It is a JOML perspective with a *vertical* FOV (`Projection` L71). Near is 0.05 (`Camera` L40,
  L106). Far is `depthFar = max(RD·64, cloudRange·16)` (L91-92).

Spyglass schedule at FOV 70 *(model)*: `modifier(n) = 0.1 + 0.9·0.5ⁿ`, one tick = 50 ms. The last column is the
L0 radius needed at `τ_tex = 1`, see §3.

| t (ms) | modifier | FOV | Z | K (px) | L0 needed to |
|---|---|---|---|---|---|
| 0 | 1.0 | 70.0° | 1.00 | 1,402 | 1,402 blocks |
| 50 | 0.55 | 38.5° | 2.01 | 2,812 | 2,812 |
| 100 | 0.325 | 22.8° | 3.48 | 4,881 | all of R |
| 150 | 0.213 | 14.9° | 5.36 | 7,522 | all |
| 200 | 0.156 | 10.9° | 7.31 | 10,257 | all |
| 300 | 0.114 | 8.0° | 10.03 | 14,071 | all |
| 500 | 0.101 | 7.1° | 11.35 | 15,915 | all |
| ∞ | 0.1 | 7.0° | 11.45 | 16,056 | all |

**What "10x" means.** The spyglass magnifies **11.45x** linearly at FOV 70. At FOV 30 it is 10.2x (K = 37,500, the
largest spyglass K). At FOV 110 it is 14.8x. The FOV option range is 30–110 (`Options` L821).

**Zooming out** follows the same per-tick halving of the FOV modifier: Z goes 11.45 → 2.01 after 50 ms → 1.37
after 100 ms → 1.08 after 200 ms.

### 1.2 Culling and compile scheduling stay wide; our draw list follows the zoom

**The cull frustum never narrows.** `fovForCulling = max(fov, option fov)` (`Camera.createProjectionMatrixForCulling`
L180-190, built every frame at L103). Zooming in therefore leaves it unchanged. It feeds:
- the graph's visible set: `SectionOcclusionGraph.addSectionsInFrustum` L112-127, through the 8-block offset
  frustum L360-362, applied on 2° steps or after a graph change (`LevelExtractor` L140-149);
- partial graph updates (`SectionOcclusionGraph` L201);
- the entity frustum (`LevelExtractor` L190 → `EntityRenderer.shouldRender` L61-88);
- particles (`LevelExtractor` L218);
- block entities, which come from `visibleSections`.

**The graph never rebuilds because of zoom.** `SectionOcclusionGraph.invalidateIfNeeded` (L96-110) receives `optionsRenderState.fov`
(`LevelRenderer` L313), which is the integer option (`GameRenderer` L775), not the dynamic FOV. The BFS itself
ignores the frustum (`docs/stage2/culling.md` §2.2).

**Compile scheduling stays wide.**
- Dirty sections are taken only from `visibleSections` (`LevelExtractor` L166-186).
- They compile nearest first: `LevelRenderer.compileSections` L966-994, `SectionTaskDynamicQueue.poll` L18-53.
  The queue orders by distance only, not by view direction.

**Our S2 frustum is exact and narrow.** It is built from the frame's real projection
(`docs/stage2/TERRAIN-PLAN.md` §4.2, §9.3; `client/src/net/minecraft/client/renderer/chunk/TerrainFrustum.java`;
`client/src/net/minecraft/client/renderer/GameRenderer.java` L671 `setFrameProjection`), so under zoom it drops
every section-layer outside the zoomed view from the draw list.

**Consequence.** For vanilla sections, "draw with the narrow frustum, keep graph and compile scheduling wide"
**already holds**. Nothing pops on zoom-out because `visibleSections`, meshes and compile state never shrank.
Stages 3–5 must keep the same split (§7).

### 1.3 Vanilla code that already treats the spyglass specially

| Where | What it does while scoping |
|---|---|
| `MouseHandler.turnPlayer` L333-338 | Drops the ×8 sensitivity factor: 1/8 turn speed |
| `BeaconRenderer` L51 | Beam radius scale 1 instead of `max(1, dist/96)` |
| `AbstractSignRenderer` L87-95 | Glow-text outline at any distance |
| `Hud` L277-282, L1051-1065 | Scope overlay. Its scale starts at 0.5 and moves toward 1.125 **per frame** by `lerp(0.5·Δticks)` (L277). That halves the gap per tick only at 20 FPS; at ≥ 120 FPS it closes ~39% of the gap per tick (`e^-0.5`, time constant 100 ms). Black fills outside a `scale·H` square (1.125·H at full scale) |
| `SpellParticle` L35-37, L49-50, L62-68 | Hides potion-effect particles within 3 blocks of the eye while scoping in first person, so your own effect swirls do not cover the view |

**The overlay's shape** (decoded from 26.3's `textures/misc/spyglass_scope.png`, 256×256, palette PNG; it is a
**square frame, not a circle**):
- Texels 0–15 on every side are fully opaque (a brown frame). Texels 16–19 are a 4-texel bevel with alpha
  238 → 204 → 120 → 39. Texels 20–235 are the glass.
- So the opaque edge sits at 112/128 of the half-side, and the clear window is a **square** of half-width
  `0.4375·scale·H`: 430 px at scale 0.5 and **967 px (0.492·H)** at 1.125, i.e. a 1,933 px square.
- At full scale that window is 63% of the 3024x1964 screen; the fully opaque part (frame plus fills) is 37%. At
  scale 0.5 the window is an 860 px square and 88% of the screen is opaque.
- The side bands (2 × 407 px of 3024) are black at every scale; at full scale the frame also shows 15 px rows at
  the top and bottom (the 1.125·H square overhangs the screen by 123 px each way, the frame is 138 px).
- The glass has alpha 13/255: a 5% tint toward pale blue-grey (mean RGB ≈ 135, 161, 187), not a darkening. Two
  white glints add alpha 27 (32×32 texels) and alpha 81 (16×16 texels).

### 1.4 Entities, block entities, name tags

- **Entity distance.** An entity is culled beyond `size · 64 · viewScale` (`Entity` L2118-2126), where
  `viewScale = clamp(RD/8, 1, 2.5) · entityDistanceScaling` (`LevelExtractor` L267-269), i.e. 1.5 at RD 12. It
  does not depend on zoom. Example: under the spyglass a zombie is culled at 100.8 blocks while it is
  **311 px tall** *(model)*.
- **Remote players** use `size·10` (`RemotePlayer` L21-29), i.e. 960 blocks, so only server tracking limits them.
- **Server tracking.** An entity is sent only within
  `min(clientTrackingRange·16 · broadcast%, viewDistance·16)` and inside tracked chunks:
  - `ChunkMap` L1408-1417, L1433-1448, L824-826;
  - the broadcast percentage applies on dedicated servers only (`DedicatedServer` L733-735);
  - ranges in chunks (`EntityTypes`): zombie 8 (L1160), cow 10 (L318), item 6 (L599), arrow 4 (L193), player 32
    (L1208).
- **Name tags.** For living entities the distance is the server-synced attribute `NAME_TAG_DISTANCE`
  (`LivingEntityRenderer` L300-301). For other entities it is 64 blocks (`EntityRenderer` L245-246).
- **Block entities.** 64 blocks by default (`BlockEntityRenderer` L31-37); beacons use RD·16 (`BeaconRenderer`
  L201-203).

### 1.5 Where the zoom key goes (client only)

**The FOV change.**
- Apply `fov' = 2·atan(tan(fov/2) / Z(t))` at the end of `Camera.calculateFov` (L222-229), in a new full copy of
  `Camera` under `client/src`.
- `Z(t)` is smoothed per frame (§6.5). `hudFov` (L231-233) is unchanged, so the hand is unaffected.
- **Not via `options.fov`.** Every change would invalidate the graph (L96-110) and could be written to
  options.txt by any later `options.save()`. Its range is also 30–110 (`Options` L821), so it cannot go below 30°.
- **Not via `fovModifier`.** It is clamped to ≥ 0.1 (L177), which would cap zoom at the spyglass's 10x.

**Other patched copies.**

| Class | Change |
|---|---|
| `Options` | `KeyMapping`, plus `zoom.maxZ` and `zoom.animMs` |
| `MouseHandler` | Sensitivity ÷ Z (option); the wheel changes Z while the key is held |
| `BeaconRenderer`, `AbstractSignRenderer`, `SpellParticle` | Treat `Z_eff > 1` like scoping |

The lang key goes in `client/lang/en_us.json`. `Minecraft.java` and `GameRenderer` are not needed.

**Server-observable effects: none.**
- The key sends no packet and no use-item.
- `ClientInformation` is unchanged; it carries the view distance.
- Rotations stay user input; the spyglass already reduces sensitivity the same way.
- Wheel events consumed while the key is held send no hotbar packet. That is the same as the user not scrolling.

**Range.** Z ≤ 50 at FOV 70 (fov ≥ 1.6°, K = 70,122), with a cap of `K ≤ K_max = 70,122` px at any FOV option. The
LOD worst case is therefore bounded; without the cap, FOV 30 and Z = 50 would give K = 183,000.

---

## 2. How big a block is on screen, and what that means for LOD detail

**Pixel size of a block.** At the screen centre a block at distance `d` covers `p(d) = K/d` panel pixels.

**Off axis.** The exact local magnification at a point with view depth `z` and distance `d` is `K·d/z²`. It is the
largest singular value of the projection's Jacobian; at angle θ from the axis that is `K/(d·cos²θ)`.
- At 70° the frame's corner is 2.65× the centre value: 3,720/d in the radial direction.
- At 7° the corner factor is only 1.013×.
- The "1608/d" figure is `H/FOV_rad`, the average over the field. It overstates the centre by 15% at 70°. At the
  spyglass both conventions agree within 0.2% (16,056/d against 16,076/d).

**Panel pixels per block at the screen centre** *(model)*:

| FOV | K | 16 | 64 | 192 | 512 | 1024 | 2048 | 4096 blocks |
|---|---|---|---|---|---|---|---|---|
| 110° (option max) | 688 | 43 | 10.7 | 3.6 | 1.34 | 0.67 | 0.34 | 0.17 |
| 77° (70 flying, bench) | 1,235 | 77 | 19.3 | 6.4 | 2.41 | 1.21 | 0.60 | 0.30 |
| 70° | 1,402 | 88 | 21.9 | 7.3 | 2.74 | 1.37 | 0.68 | 0.34 |
| 7° spyglass | 16,056 | 1,003 | 251 | 84 | 31.4 | 15.7 | 7.8 | **3.9** |
| 3° spyglass at FOV 30 | 37,501 | 2,344 | 586 | 195 | 73 | 36.6 | 18.3 | 9.2 |
| 1.6° zoom key 50x | 70,122 | 4,383 | 1,096 | 365 | 137 | 68.5 | 34.2 | **17.1** |

**What this forces on stage 3.**

1. **Real textures at L0.** A 16-texel face shown at `p` px samples mip ≈ `log2(16/p)`. Vanilla has
   `mipmapLevels` 0–4, default 4 (`Options` L410-416).
   - Under the spyglass every block of a 256-chunk LOD is ≥ 3.9 px, so mip ≈ 2 at most for faces turned toward
     the camera (grazing faces pick higher mips, as they do unzoomed).
   - At 50x every block is ≥ 17 px, so mip 0 and the texture is magnified.
   - So L0 LOD faces must sample the real block atlas with vanilla's samplers (anisotropy, RGSS option). A colour per
     face is not enough.
2. **Geometry identical to vanilla at L0.** At 50x the edge of the LOD looks like terrain 82 blocks away unzoomed.
   That means stairs, fences, cross plants with their random offsets, cutout leaves with leaf-to-leaf faces, AO and
   smooth lighting, biome tints and animated textures.
   - **L0 = the output of vanilla's `SectionCompiler`, run on cached block data.** Only faces reachable from
     outside air are kept (§5).
   - Anything simpler shows when magnified.
3. **Coarse levels only where blocks are sub-pixel**, i.e. `p ≤ τ_tex` (≈ 1–2 px per block, to be calibrated).
   - Unzoomed at 70° that is d ≥ 700–1,400 blocks. This is a stage-3 cost even without zoom: L0 out to 44–88 chunks.
   - Under the spyglass it is d ≥ 8,000–16,000, so no coarse level exists inside R.
   - At 50x there are no coarse levels at all.
4. **Block entities and entities** at magnified distances: §10.

---

## 3. FOV-aware SSE LOD selection, recomputed every frame

**Per node** (level L, cell `2^L` blocks):
- the AABB;
- the **measured** geometric error `ε_n` in blocks: a two-sided Hausdorff distance between the node's surface and
  its L0 surface, computed when the node is built. It is 0 at L0. A flat plain has ε ≈ 0 at every level, which is
  what keeps coarse levels useful;
- a flag `averaged` for nodes whose faces carry averaged colour instead of textures (L ≥ 1).

**Each frame**, with `K(t)` from this frame's `Camera.getFov()`, i.e. after the partial-tick lerp (L227) and the zoom
multiplier:
- `σ_n = K / (z_min,n · cos θ_c)`. Here `z_min,n` is the AABB's nearest view depth, clamped to ≥ near, and `θ_c` is
  the frustum's corner half-angle. The formula bounds `K·d/z²` over the box; `1/cos θ_c` = 1.63 at 70° and 1.006
  at 7°.
- Geometric SSE: `g_n = ε_n·σ_n`. Texture SSE: `t_n = σ_n` if `averaged`, else 0.
- **Refine** when `g_n > τ_geo` or `t_n > τ_tex`.
- **Coarsen** back to the parent only when the parent has `g < τ_geo/2` and `t < τ_tex/2`.
  - This hysteresis is needed because the FOV wobbles: sprint and speed effects change it by ~10–20%
    (`AbstractClientPlayer` L98-102), and flying by ×1.1 (L94-96).
- Starting values: `τ_geo` = 0.5–1 panel px, `τ_tex` = 1–2 px per block. Both are calibrated against GOAL's
  perceptual metric (§12) and need the *same* values zoomed and unzoomed.

**The draw set** is the finest *resident* level ≤ the required one, inside the exact frame frustum (or the scope
window, §4).

**Where it runs.**
- In a GPU compute pass over the resident-node list that culls, selects and writes the indirect records. At µs cost
  per frame, the selection follows the zoom animation frame by frame.
- RenderPearl has no compute stage (`docs/stage2/culling.md` P5), so this is a Metal-backend extension. The backend
  already rewrites terrain draws (`docs/metal/CONTRACT.md` §16.5).
- The CPU only does residency, at tick rate or whenever `K` moves by more than 5%.

**The residency set** is the union of:

| Part | What | Why |
|---|---|---|
| R1 | Wide frustum (option FOV + 10° margin) at the option-FOV `K` | What an unzoomed view needs; **never evicted while zoomed**, so zoom-out is instant |
| R2 | 360° ring around the player, coarse levels at the option-FOV `K` | Instant turning |
| R3 | Zoom-target cone at the target `K` (spyglass: modifier 0.1; key: target Z) | Prefetch, §6 |
| — | Ancestors of every drawn node | Coarse-until-fine (§6.4) |

The draw set is always a subset of the residency set.

---

## 4. The visible-geometry budget under zoom

**Model** *(model)*:
- a ground plane viewed toward the horizon, LOD radius R = 4096;
- L0 wherever `K/d > τ_tex`; beyond that, cells grow ∝ d (a constant projected size);
- count the surface columns (tiles) inside the horizontal wedge of half-angle `φ = atan(tan(fov/2)·aspect)`:
  - `N = 2φ·[d0²/2 + d0²·ln(R/d0)]` with `d0 = K/τ_tex` when `d0 < R`;
  - `N = φ·R²` otherwise (L0 everywhere).

**Results.** Columns in millions, with the ratio to unzoomed in brackets.

| Z | FOV | τ_tex = 1 | τ_tex = 2 |
|---|---|---|---|
| 1 | 70° | 5.09 (1.00) | 1.83 (1.00) |
| 1.5 | 50° | 6.43 (1.26) | 2.56 (1.40) |
| 2 | 38.6° | **6.84 (1.34)** | 3.06 (1.67) |
| 2.92 | 27.0° | 5.93 (1.17) | 3.54 (1.93) |
| 4 | 19.9° | 4.42 (0.87) | **3.64 (1.99)** |
| 5.36 | 14.9° | 3.33 (0.65) | 3.29 (1.79) |
| 11.45 | 7° spyglass | 1.58 (0.31) | 1.58 (0.86) |
| 50 | 1.6° key max | 0.36 (0.07) | 0.36 (0.20) |

**Why the count stays roughly constant.**
- With SSE every primitive projects to about the same size, so the count ≈ visible pixels / pixels per primitive,
  whatever the FOV. Zoom redistributes the detail into a narrower cone.
- The peak (1.3–2×) comes **before** L0 reaches R: `N ∝ φ·d0²·(½ + ln(R/d0))` with `φ ∝ 1/Z` and `d0 ∝ Z` is
  largest at `d0 ≈ R/√e ≈ 2,500` blocks. Exact maxima: Z = 2.0 (1.34×) at τ_tex = 1 and Z = 3.7 (1.99×) at
  τ_tex = 2. L0 reaches R only at `K = τ_tex·R` (Z = 2.9 or 5.8). Beyond that L0 is the floor, `N = φ·R²` falls as
  1/Z, and the narrow cone holds fewer blocks than the screen has pixels.
- The wedge counts ground that a narrow vertical FOV does not show (looking level from height h, the ground starts
  at `h/tan(fov/2)`: ~1,000 blocks at h = 64 under the spyglass). The zoomed counts are therefore upper bounds.
- The spyglass and 50x are therefore *cheaper* than unzoomed in this model.
- Absolute numbers depend on stage 3's τ and its error distribution. The **ratios** are what zoom adds.

**What makes the real worst case worse.**
- **Depth complexity.** A narrow cone toward the horizon over mountains holds stacked ridges. The wedge counts every
  column, but mountain sides add about 1.5–3× quads *(estimate)*.
- **L0 is denser per column.** It carries full models: cross plants, and leaf faces with `cutoutLeaves`. A forest L0
  column is about 5–10 quads against 1–2 for a coarse tile *(estimate)*.
- So plan for **up to ~3–4× the unzoomed LOD terrain vertex load at Z ≈ 2–4 over forested mountains**, and ≤ 1×
  under the spyglass or at 50x *(estimate)*.
- For scale: in game the terrain pipelines cost 0.85 ms of a 1.593 ms GPU frame (`results/ROUND3.md`: solid 0.644,
  cutout 0.208).

**Mitigations, all exact.**
1. **Facing buckets per LOD node** (the S1 design). They remove 38–39% of solid and 50–51% of cutout quads
   (`TERRAIN-PLAN.md` table "Back-facing at bucket granularity"; `culling.md` §0 gives −38% overall).
2. **Zoom-gated two-phase HiZ for LOD nodes.**
   - Measured on M5: occluded front-facing quads cost about 0.065 ms per 100 k (pipelined), and building the HiZ base
     costs 0.15 ms (`docs/metal/OCCLUSION.md` §0, §1.2). The pass pays off when roughly > 230 k hidden candidate
     quads are expected, which a zoomed horizon view has; the bench view does not.
   - That break-even ignores the render-pass split it needs: OCCLUSION.md §0 notes the depth is unavailable inside
     the merged Sky..GUI encoder until it ends, so phase 1 must end the pass (store colour and depth) and phase 2
     must reload them. That cost is unmeasured and raises the break-even.
   - Phase 1 draws last frame's visible nodes with **this** frame's projection; then build HiZ and test the rest.
     It therefore stays exact during the zoom animation.
   - `MCMTL_OCCLUSION`, in contrast, drops all evidence on any projection change (`CONTRACT.md` §16.6, the
     reset rules). It is off by default and not exact (`results/ROUND3.md`).
3. **Scope mask** (spyglass only).
   - Cull sections, LOD nodes and entities against the clear square window (§1.3): a sub-frustum with horizontal
     and vertical half-angles `atan(0.4375·scopeScale·H/K)`, i.e. `atan(967/K)` at full scale. It follows
     `scopeScale` every frame. The boundary is the opaque edge, so the bevel and glass stay inside.
   - Before the world pass, draw a depth-only mask mesh at the near depth over the fully opaque regions: the fills
     outside the square, plus the frame texels (alpha 255) shrunk by 1 px. No `discard` is used, so HSR rejects the
     world's fragments there: **~37% of world pixels with the scope fully open, ~88% at scale 0.5** *(model; the
     4-texel bevel and the glass are not masked)*.
   - Pixel-identical in vanilla rendering, because it only covers pixels the overlay paints fully opaque. The
     first-person hand is not drawn while scoping (`FirstPersonHandsAndItemsRenderer` L400), so the mask hides
     nothing that would show.
   - **Not with shaderpacks.** Screen-space effects (bloom, SSR, SSAO, DoF) sample beyond the window, so with a pack
     the depth mask is off and the cull window is widened by the pack's largest kernel, or off as well.
   - At the start of the animation the window is smaller (scale 0.5: an 860 px square), right when refinement load
     peaks.
4. **Narrow draw frustum** (§7).

**Proposed gate (to confirm with the user):** zoomed median FPS ≥ 0.8 × the same scene's unzoomed LOD FPS in the worst
scene Z5, and no frame-time spike > 2× median during the zoom animation.

---

## 5. Data that must be retained

**Why.** Zoom needs L0 anywhere inside R, at any moment the player raises a spyglass (§2). So the full block-level
data for the whole LOD disc must be on disk or be regenerable, not just the coarse levels.

**What L0 needs per chunk**
- block states (palette-packed) and fluid states;
- sky and block light, for smooth lighting, AO and torches at night;
- biomes, for tints;
- render-relevant block-entity data: chest, bed, banner, sign, skull, pot and shulker type, colour and facing;
  banner patterns; sign text optional (§10).

Heightmaps, ticks, structure references, POI and entities are not needed.

**Measured size of vanilla's own storage** (script over
`run/metal/saves/*/dimensions/minecraft/overworld/region/*.mca`, status `minecraft:full` only; KiB):

| World | Full chunks | zlib KiB/chunk | Raw NBT KiB | xz -6 KiB (xz -0) |
|---|---|---|---|---|
| bench | 1,369 | 6.44 | 47.7 | 5.36 (5.76) |
| travel | 2,364 | 6.13 | 44.0 | 5.18 (5.69) |

**Disc sizes.**
- The disc of radius 256 chunks holds 205,887 chunks: **1.20–1.26 GiB (1.29–1.36 GB)** at vanilla's density
  *(measured density × model area)*.
- A custom LOD format without the unneeded NBT, using zstd with shared dictionaries, should reach 3–5 KiB per chunk,
  i.e. 0.6–1.0 GiB *(estimate)*.
- Per km²: 3,906 chunks, about 12–25 MB.
- Exploring 20×20 km takes 5–10 GB, hence an LRU cap (an option; default proposed at 16 GB).

**Storage tiers**

| Tier | Holds | Notes |
|---|---|---|
| Disk | Everything, LRU | — |
| RAM (optional) | Compressed L0 for the disc around the player, 0.6–1.3 GB | Disk is not the bottleneck: the steady spyglass cone is about 6.2 k chunks, i.e. 37 MB of NVMe reads. Decompression and meshing are (§6.2) |
| GPU | Meshes for the residency set only | §3 |

**Singleplayer**
- Chunks that exist in the world's region files are read from there; they are the truth. The integrated server
  writes those files concurrently and holds newer chunks in memory, so reads go through its storage layer or
  tolerate torn/stale reads (retry on a bad zlib stream; refresh when the chunk is later sent to the client).
- Ungenerated chunks are produced by a client-side generator with the world's seed, version and datapacks. It
  writes **only into the LOD cache, never into the save**, so the save stays exactly what vanilla would have.
- **Cost.** Full generation including features is about 5–20 ms per chunk per core *(estimate)*:
  - the whole disc: 1,000–4,100 core-seconds, i.e. 2–11 min on 8–6 workers;
  - one spyglass cone (about 6.2 k chunks): 31–123 core-seconds, i.e. 4–21 s on 8–6 workers.
  - Workers yield to the integrated server's tick.
- **Caveat 1, first look.** The first spyglass look into never-generated terrain shows coarse, noise-only data
  that fills in over seconds. The priority is SSE deficit × screen area. This is visible as a "focus"
  change; GOAL (c) side-by-side applies.
- **Caveat 2, old saves.** A world upgraded from older versions keeps its saved chunks. The seams between saved
  and newly generated terrain are vanilla's own.
- **Caveat 3, determinism.** Generation must equal what the server generates later. It is expected to be
  deterministic per chunk, but this must be verified (a test compares our chunks with server-generated ones).

**Multiplayer**
- Only chunks the server sent are cached, i.e. those within its view distance along the player's path.
  - The cache key is (server address, dimension, world fingerprint). The fingerprint detects map resets.
  - Without the seed nothing can be generated.
- **Honest consequences.**
  - Directions the player never visited stay empty (sky and fog) at every zoom level.
  - Cached terrain is shown as last seen and refreshed when received again.
  - Anti-xray fake ores are cached as received, which is the same as what vanilla shows.
- No request is ever sent; the protocol has no way to ask for chunks anyway.

---

## 6. Refinement latency against the zoom animation; prefetch; hiding

### 6.1 Demand over time

L0 columns needed *beyond* the unzoomed L0 radius (1,402 blocks at τ_tex = 1), in the horizontal wedge, ground-plane
model *(model)*: `φ·(min(K, R)² − 1402²)`, with `φ` the frame's horizontal half-angle or, inside the scope,
`atan(half-width/K)`. The scope half-width follows vanilla's per-frame lerp (§1.3) at ≥ 120 FPS, not a per-tick
halving:

| t (ms) | Z | Scope window half-width | Demand inside the scope (M columns) | Demand, full frame (zoom key) |
|---|---|---|---|---|
| 0 | 1.00 | 430 px | 0 | 0 |
| 50 | 2.01 | 642 px | 1.33 | 2.93 |
| 100 | 3.48 | 771 px | **2.32** | **4.45** |
| 150 | 5.36 | 848 px | 1.66 | 2.94 |
| 200 | 7.31 | 895 px | 1.29 | 2.17 |
| 300 | 10.03 | 941 px | 0.99 | 1.59 |
| steady | 11.45 | 967 px | 0.89 | 1.39 |

- L0 must reach R by about **100 ms** after the use starts (K passes 4,096 at t ≈ 87 ms).
- The steady state is 0.89 M columns inside the scope, about **3.5 k chunk columns**; the full frame is 1.39 M,
  about 5.4 k.
- **The transient peaks at t ≈ 100 ms (Z ≈ 3.5)** at 2.6–3.2× the steady demand. Middle distances need L0 over a
  wider angle than the final cone. The transient lasts about 100 ms, so the target is the steady cone, and the
  transient is covered by §6.4.
- **Zoom-out only needs coarser data.** It is instant because R1 stays resident (§3).

### 6.2 The budget, and what is unmeasured

**Without prefetch.** Showing steady quality from the end of the animation (about 300 ms, Z = 10) means
meshing 3.5–5.4 k chunk columns of vanilla-exact L0, plus upload, within about 300 ms.
- **Vanilla meshing throughput is unmeasured.** At an assumed 0.5–2 ms per surface chunk column per core and 8
  workers, this takes **0.2–1.4 s** *(estimate)*.
- Without prefetch, the far part of the cone would arrive up to about 1 s late.
- **Gate:** measure `SectionCompiler` time per surface column (S0-style probe) before choosing CPU or GPU meshing.

**GPU L0 meshing.** A compute kernel emits vanilla-identical quads from the block data and pre-baked per-blockstate
model quads, including vanilla's AO and smooth-light rules. It removes the latency, but porting bit-exactly is a
project of its own. Planned as the fallback if the gate fails.

**Memory of the steady spyglass cone's L0 meshes** *(estimate)*:
- 0.9–1.4 M columns × 3–10 quads × 64–112 B per quad ≈ **0.17–1.6 GB**. The low end assumes the S5 compact 16-byte
  vertex; vanilla is 28 B per vertex, 112 B per quad (`meshing-and-vertex-format.md` §6.2).
- A vertex-pulled format of 8–16 B per quad would make it ~20–220 MB. That is stage 3's choice.

### 6.3 Prefetch triggers

All triggers read client-side state only and send no packets.

| Trigger | Action | Lead time |
|---|---|---|
| **P1** Spyglass in the main hand or offhand | Keep R3 = the steady scope cone at L0 around the current view direction, widened by 2° + angular velocity × 0.5 s. Release it when the spyglass leaves the hand | Time from selecting to using: typically ≥ 0.2–0.5 s *(estimate)* |
| **P2** Use started (`isScoping()` turns true) | Raise R3 to top priority; pause other LOD meshing | 0, too late on its own |
| **P3** Zoom key bound (always) | Keep the 50x cone around the crosshair resident: 0.36 M columns, about 1.4 k chunk columns, ~70–400 MB *(estimate)* | Permanent |
| **P4** Zoom key down | Target Z known at press (wheel-adjustable) | Our own animation (§6.5) |
| **P5** Crosshair still for > 0.5 s | Extend R3 at low priority | Cheap; likely to precede a zoom |

Prefetch must never starve vanilla compilation inside RD. It gets its own worker pool, or a priority below
`SectionTaskDynamicQueue`'s tasks.

### 6.4 Hiding (no visible popping)

- **H1. Coarse until fine.** An unresident level is never drawn; the ancestor rule (§3) holds.
- **H2. Refine on time.** A node refined just as its error crosses `τ_geo` changes by ≤ τ, which is sub-pixel and
  needs no fade. Popping only comes from *late* refinements.
- **H3. Dithered cross-fade for late refinements**, over 100–250 ms, longer for a larger error.
  - The fine node draws the pixels where `hash(px) < α(t)` and the coarse node the complement. Both are opaque:
    no blending and no sorting.
  - With temporal AA or MetalFX temporal this resolves into a blend. Without it, the dither at render resolution is
    only partly averaged by the 0.84× compositor downscale (3600x2338 → 3024x1964), so a faint pattern is visible
    for about 0.2 s. This is a risk, and GOAL (c) side-by-side applies.
  - Fading nodes use `discard`, which defeats HSR for them, so concurrent fades are capped.
- **H4. Colour first.** A fine node starts at its coarse parent's per-face colour and blends to the textured colour
  through a per-node uniform. Less changes inside the dither.
- **H5. Vanilla sections replacing LOD at the RD edge.**
  - Vanilla's section fade-in mixes the colour toward the fog colour (`terrain.fsh`: `mix(FogColor…,
    chunkVisibility)`; `SectionRenderDispatcher` L216-219; option `chunkSectionFadeInTime`).
  - Over L0 LOD that already shows the same blocks, this would flash fog-coloured patches. That is very visible at
    80 px per block under the spyglass.
  - Where an L0 node covered the section, skip the fade (visibility 1). Since L0 is vanilla-exact geometry, the swap
    is invisible.
- **H6. Fast pans while zoomed.**
  - Under the spyglass the turn speed is 1/8 (§1.3), but a fast swipe still reaches about 40°/s *(estimate)*, i.e.
    ~3.7 frame widths (10.8°) or ~5.8 scope-window widths (6.9°) per second.
  - At 40°/s the leading edge sweeps `ω·(R² − 1402²)/2` ≈ 5.2 M block columns, i.e. **~20 k new chunk columns per
    second** *(model)*, beyond CPU meshing (8 workers at 0.5–2 ms give 4–16 k/s).
  - The leading edge then lags, hidden by H1/H3, unless GPU meshing is built. Same gate as §6.2.

### 6.5 The zoom key's animation

- **Default: 150 ms ease-out.** OptiFine's zoom is instant, which puts the whole demand at t = 0. With P3 the
  central 50x cone is already resident; the rest falls under H3.
- **Option "wait for detail"** (our key only; the spyglass stays vanilla): stretch the animation by up to 150 ms
  while the SSE deficit in the target cone is above a threshold.

---

## 7. Narrow draws, wide everything else (the zoom optimization)

**Rule.**
- Anything with latency uses the wide frustum (the option FOV, as vanilla's `max(fov, option fov)` at `Camera` L181)
  plus the zoom-target cone: graph, compile scheduling, LOD residency, meshing, BLAS builds, shadow-map *allocation*.
- Anything recomputed per frame uses the exact narrow frustum: draw records, entity extraction, LOD draw selection,
  shadow casters.

| Item | Today | Under zoom |
|---|---|---|
| Sections | S2 exact frustum on the draw list (§1.2) | Already narrow. At 7° the view's tangent-plane area is (tan 3.5°/tan 35°)² ≈ 0.76% of the 70° frame; only sections near the axis remain |
| Entities | Wide cull frustum (`LevelExtractor` L190 → `EntityRenderer.shouldRender` L61-88): every entity in the 70° view is extracted, animated and CPU-skinned (`docs/renderpearl/07` §5) | Test the exact frame frustum when Z > 1 on the renderer's own `getBoundingBoxForCulling` box, inflated by 0.5 as vanilla does (L70) and, where they apply, by whatever an extracted entity draws outside that box under the wide frustum: the blob shadow (only within 16 blocks of the camera, `EntityRenderer` L281; radius ≤ 32 horizontally, ≤ ~2 blocks down, L277-289) and the name tag (up to ~1 block above the box with a score line, half the text width sideways; vanilla's own frustum edge drops it too, but its wide frustum keeps it while zoomed). A shaderpack's shadow pass culls entities against its own shadow frustum, not this one. Extraction is per frame, so zoom-out has no latency. Keeps vanilla's exemptions: `affectedByCulling() == false`, leash rules (L66-85), camera entity and passengers (`LevelExtractor` L275-276, L303). (Glowing entities need none: an off-screen silhouette never reaches the outline buffer.) Exact if the inflation covers everything drawn; a CPU win in mob-heavy scenes |
| Block entities | From `visibleSections` (wide) | Per-section exact-frustum test, box inflated 2 blocks (`culling.md` P7) |
| LOD | — | Draw set narrow, residency wide (§3) |
| Clouds (S7) | Reuses the upload while the view's corner rays lie in the cached cone (`TERRAIN-PLAN.md` §9.3) | Zoom-in reuses it; zoom-out re-filters once. Free |
| Terrain ICB | Off by default (`results/ROUND3.md`) | If enabled: the narrow frustum changes the records every frame during the animation, so the ICB re-encodes (45 µs instead of 15 µs on a cache hit, `CONTRACT.md` §16.5). Negligible |
| `MCMTL_OCCLUSION` | Off by default | Evidence is dropped on every projection change (`CONTRACT.md` §16.6), so no culling during the animation; it resumes after ≥ 2 query rounds once still. LOD uses the two-phase HiZ (§4) instead |

---

## 8. Stage 4: shaderpacks under zoom

### 8.1 Shadow maps (the main stage-4 zoom defect)

**How packs do shadows.** OptiFine/Iris render the shadow pass with an orthographic projection:
- half-size `shadowDistance`, centred on the player;
- resolution `shadowMapResolution`;
- snapped by `shadowIntervalSize` (all pack constants in `shaders.properties` / `const`);
- packs then apply a radial distortion in `shadow.vsh` and the same distortion in their lookup code.

**Numbers** *(model)*.
- Undistorted, a 2048² map over 2 × 128 blocks (`shadowDistance` 128, an illustrative value; packs differ) gives
  0.125 blocks per texel.
- A screen pixel at 64 blocks covers 0.046 blocks at 70°, 0.0040 under the spyglass and 0.0009 at 50x.
- So one shadow texel spans about 3, **31** and **137** px.
- Distortion helps only near the player, and the zoom focus is usually far away. Shadows turn into blocky, swimming
  steps exactly where the user is looking.

**Fix: a zoom-fitted shadow projection** (when Z > ~1.5).
1. **Reduce the visible samples.** From the previous frame's depth, reduce the min/max depth and the light-space
   bounds of the visible samples (sample distribution shadow maps, SDSM).
   - Cost: ≤ 0.15 ms at 3600x2260. That bound is the measured HiZ base build (`OCCLUSION.md` §1.2); a reduction is
     cheaper.
2. **Fit the projection.** Set `shadowProjection`/`shadowModelView` so that:
   - the map's centre is the centroid of those samples;
   - the half-size covers their light-space extent, plus a PCF/penumbra margin;
   - the light-space near plane extends toward the sun to the world's height range, so off-screen casters such as
     mountains toward the sun still cast.
   - Example: spyglass at 64 blocks. The cone slice is about 12 × 8 blocks, so 2048 texels give about 0.006 blocks
     per texel, ≈ 1.5 px per texel. That holds only when the visible samples lie near one depth (looking at a
     hillside or a building).
   - **Limit: deep views.** Looking toward the horizon, the shadowed samples run from the focus out to the pack's
     `shadowDistance` (packs do not shadow beyond it, see the caveats). Their light-space extent then grows with
     that depth range, e.g. ~64 blocks for samples at 64–128 blocks with a high sun: 0.031 blocks per texel, i.e.
     ~4–8 px per texel. Better than 31 px, but not unzoomed quality: one map cannot reach ~1 px per texel over
     that extent without cascades, which packs do not have *(model)*.
3. **Why it works with distortion packs.** The pack's distortion is a function of shadow-clip xy, applied
   identically when rendering and when looking up. Its densest point is the map centre, which is now the focus:
   exactly where zoom needs it.
4. **Stability.**
   - Quantize the half-size to 2^(1/4) steps and the centre to whole light-space texels.
   - Move at most one step per frame during the animation.
   - **Risk:** resolution steps may be visible during the ~300 ms animation; the pack's TAA hides some of it.
5. **Shadow-pass culling uses the fitted frustum.** Fewer casters, so a cheaper shadow pass when zoomed.
6. **LOD casters** are drawn in the shadow pass (DH-style `dh_shadow`). Their level follows the *receiver's* SSE:
   casters near the focus at L0.

**Caveats (per pack, must be tested).**
- Packs fade shadows by distance from the player (`shadowDistance`). Keep that semantics: shadows the pack does not
  draw unzoomed are not added when zoomed.
- Packs that tile their own cascades inside the map, or snap with `cameraPosition`, break with a moved centre.
  - For those, fitting is off.
  - Mechanism: a per-pack allowlist, starting with the most popular packs.
- Packs that cannot be fitted: raising `shadowMapResolution` through the loader costs ×4 memory and time per doubling
  and is still insufficient at 11x. **Not fixable for those packs**; this is documented.

### 8.2 Depth-based effects

| Effect | What happens under zoom | What we do |
|---|---|---|
| DoF | Packs focus on `centerDepthSmooth`, which follows the centre depth with a half-life. Zooming onto a far object refocuses with that lag: pack behaviour, the same as turning | Supply correct near/far, including the LOD far plane (DH-style `dhFarPlane`), so linearized depth is not clipped |
| SSAO/GTAO | A radius in world units without a screen clamp scales correctly. A radius clamped in pixels makes AO relatively smaller; a radius defined in pixels shrinks Z-fold in world units, a visible change | Not fixable without editing pack shaders. Measure per pack (Z9), document, and decide later on a per-pack patch table. We already parse pack programs for Metal |
| SSR | Can only reflect on-screen content. The narrow cone contains less, so zoomed water falls back to sky more often | Fundamental to SSR. Stage 5 RT reflections fix it |
| TAA (pack) | Needs the exact previous projection | The loader captures `gbufferPreviousProjection` *after* our zoom multiplier each frame. Softer for ~100 ms during the animation (pack behaviour) |
| LOD programs | DH-style `dh_terrain`/`dh_water` are colour-only. Zoomed L0 LOD drawn through them would look flat | Draw L0 nodes through the pack's `gbuffers_terrain`/`gbuffers_water` with vanilla-like attributes (`mc_Entity` from `block.properties`, `mc_midTexCoord`, `at_tangent`, `at_midBlock`): materials, normal/PBR maps, waving foliage, emissives. Only averaged nodes go to `dh_terrain`. **Risk:** packs fog `gbuffers_terrain` at `far` (vanilla RD), which would fog out L0 LOD beyond RD. Needs consistent `far`/`dhRenderDistance` uniforms per pack; uncertain |

**Cost under zoom.** The fitted shadow pass draws fewer casters. The SDSM reduction adds 0.1–0.15 ms. The main
passes stay the same, since the pixel count is constant.

---

## 9. Stage 5: hardware ray tracing under zoom

**Why RT is resolution-independent.**
- RT shadows and reflections are per-pixel rays against the BVH: no shadow map and no screen-space limit.
- A shadow edge is as sharp at 50x as the geometry is, and reflections include off-screen objects.
- The cost scales with pixels × rays per pixel, not with Z.
- Hardware support: intersection queries work from render stages on M5 (`docs/renderpearl/08` §7.9).

**BVH coverage.**
- The TLAS instances are vanilla section BLASes plus LOD-node BLASes for the **residency set**: R1, the R2 ring and
  R3 (§3), not just the drawn set. Casters and reflected objects are often off-screen.

**LOD consistency.**
- In the visible region the BVH must use the same node as the rasteriser. Otherwise raster L0 and a coarse BVH
  surface differ by up to `ε_n`, and self-shadowing and light leaks appear.
- So "drawable" is coupled to "BLAS built": a refined node switches in the raster and the TLAS in the same frame.

**Casters.** A caster's error ε moves the shadow edge on a receiver by about ε, which shows as `ε·K/d` px at the
receiver's distance d. So casters are selected by the *receiver's* SSE: L0 near the zoom focus, coarse elsewhere.

**Reflections.**
- A mirrored object is seen at ray length d1 + d2. The wide residency rule may hold it coarser than a zoomed view
  wants, so mirror-like water at the focus can reflect coarse distant terrain.
- Ray-cone LOD per ray would need several levels in the BVH (stochastic LOD through instance masks), which costs
  memory. Decide after measuring; ripples and roughness usually mask it.

**Build cost.**
- The TLAS is rebuilt every frame, since selection changes during zoom; each new node needs a BLAS.
- M5 BLAS throughput is unmeasured. At ~50–100 M triangles/s *(estimate)*, the steady spyglass cone (about
  5–28 M triangles: §6.2's quad counts × 2) costs 0.05–0.6 s of GPU build.
- Spread at ≤ 1 ms per frame, that adds ~0.5–5 s of refinement latency at 120 FPS in RT mode. Therefore **BLASes are
  built at prefetch time** (P1, P3). Even so, RT-mode refinement without a P1 lead (scene Z13) is expected to be
  slower than raster refinement. The H1/H3 rules apply, keyed on "BLAS built".

**GI (one bounce).** Low frequency, so a coarse BVH is acceptable. A world-space radiance cache, per node or voxel,
is zoom-invariant, unlike screen-space history.

**MetalFX.**
- **Spatial** (the contract's `UPSCALE` mode `METALFX_SPATIAL`, `CONTRACT.md` §4.4) keeps no history, so it behaves
  the same zoomed.
- **Temporal** needs motion vectors and jitter (`docs/renderpearl/08` §7.10). During the zoom animation the scale
  changes by ~12% per frame at 120 FPS, so history is magnified or disoccluded at the edges. Output falls toward
  spatial quality for about 100–200 ms.
  - Motion vectors must include the projection change (per-pixel reprojection with the previous view-projection).
  - The scaler's `reset` must **not** be set on zoom.
  - Option: a dynamic render scale of 1.0 during zoom transitions, if GPU headroom allows.
- **The RT denoiser** (`TemporalDenoisedScaler`) loses history the same way and is noisier for ~100–200 ms.
  Mitigations: more rays during transitions (budgeted), or the world-space cache.

---

## 10. Entities and block entities

**Zoom-aware entity distance** (client only).
- `Z_eff = max(1, tan(fov_option/2) / tan(fov/2))`, computed per frame; it covers both the spyglass and the key.
  It is also slightly above 1 underwater or in lava (FOV × 0.857, `Camera` L241-245) and while drawing a bow (up
  to −15% FOV, `AbstractClientPlayer` L105-107). That is intended (those are zooms too), but it means entity
  distance differs from vanilla in those cases even without the key.
- Patch `EntityRenderer.shouldRender` (L61-88): the first test becomes vanilla's `entity.shouldRender(...)` **OR**
  (`Z_eff > 1` and `entity.shouldRenderAtSqrDistance(distSq / Z_eff²)`).
  - **Monotone:** it never hides what vanilla shows.
  - It scales every type's threshold, including types that ignore `viewScale` (`ArmorStand` L359-367,
    `ThrowableProjectile` L24-36).
  - The near-spawn rules (`tickCount < 2` within 3.5 blocks) keep vanilla's result because of the OR.
- The remaining checks are unchanged: then the narrow frustum (§7), then vanilla's section rule (`LevelExtractor` L305,
  ≥ 0.3 faded).
- `viewScale` is read only on this client render path (callers: `EntityRenderer` L62 and the overrides), so nothing
  reaches the integrated server's logic.

**The hard limit is the server.** Nothing client-side lifts it (§1.4). *(model)*

| Entity | Vanilla client cull at RD 12 | Server sends up to | Height on screen through the spyglass at the server limit |
|---|---|---|---|
| Zombie (1.95 tall) | 100.8 blocks (311 px there) | 128 | 245 px |
| Cow (1.4) | 102.4 (220 px) | 160 | 140 px |
| Player (1.8) | 960 | min(512, viewDistance·16) = 192 at RD 12 (viewDistance = clamp(client RD, 2, server view distance), `ChunkMap` L824-826; 160 on a default dedicated server) | 150 px (180 px at 160) |

- **On servers, mobs still pop out at the tracking boundary while 140–245 px tall.** Vanilla's spyglass already
  pops them earlier and larger: about 100 blocks, 220–311 px.
- **Optional fade (default off; the user decides after a side-by-side).** An entity removed while within one chunk of
  its tracking boundary fades over 200 ms. It is a client heuristic, because the remove packet carries no reason. It
  shows a no-longer-tracked entity for 0.2 s: invisible to the server, but a deviation from vanilla.
- **Beyond RD there are no entities in LOD terrain:** no animals or players in distant zoomed views.
  - In multiplayer this cannot be fixed.
  - In singleplayer it would need a longer integrated-server tracking range, which LAN guests could observe.
    Rejected.

**Name tags stay vanilla.** For living entities the distance is the server-controlled attribute `NAME_TAG_DISTANCE`
(`LivingEntityRenderer` L300-301). Scaling it would show names the server chose to hide, which is a gameplay effect.
**Flagged for the user.**

**Block entities.**
- Inside RD: `BlockEntityRenderer.shouldRender` (L35-37) is OR-ed with `closerThan(viewDistance · Z_eff)`.
- Beyond RD: static proxies are baked into the L0 LOD meshes from the cached data (§5): closed chests, beds, banner
  patterns, skulls, pots, shulker boxes, bells, lecterns, sign boards (text as an optional decal).
- Animation and dynamic state at distance are approximations: open lids, bell swing, conduit, beacon beam beyond RD.
  **Honest gap.**

**Spyglass parity for the key.** Beam scale (`BeaconRenderer` L51), sign outline (`AbstractSignRenderer` L90) and
the hiding of potion-effect particles within 3 blocks of the eye (`SpellParticle` L62-68) treat `Z_eff > 1` like
scoping.

**Particles.** Server particles arrive only within 32 blocks (512 with the long-distance flag), and ambient particles
spawn near the player. Far smoke and flames are therefore absent in zoomed views, as in vanilla. Out of scope.

---

## 11. Multiplayer invariants (checklist)

- [ ] No packet is added, changed or re-timed by the zoom key, the LOD or prefetch.
- [ ] Client information is unchanged, including the requested view distance.
- [ ] The zoom key never sends use-item.
- [ ] LOD reads only received chunk and block-entity data and never asks for more.
- [ ] Entity, block-entity and name-tag changes affect only what is drawn; name tags are left vanilla.
- [ ] Mouse sensitivity scaling changes only how user input maps to rotation, as the spyglass already does.
- [ ] Verified by a packet-capture diff on LAN and on a dedicated server, with the same input script and with
      zoom/LOD on vs off (Z8).

---

## 12. Test plan

### 12.1 References

**The "truth" render for a zoomed scene.** Same camera, same zoomed projection, plus:
- LOD forced to L0 everywhere inside R (brute-force vanilla meshing from the cache);
- entities at the server's limits;
- stage 4: shadow maps at 4× the fitted resolution;
- stage 5: 16 spp.

**Where it is rendered.** Singleplayer test worlds with `/tick freeze`, so time, animated textures and entities stay
fixed (`Camera.getCameraEntityPartialTicks` L111-113). The FOV schedule is scripted by injecting the partial tick.

**Why this is the right reference.** A rectilinear zoomed frame is exactly the central crop of an unzoomed frame
rendered at Z× resolution. So "same quality as unzoomed" means "equal to this truth render".

**Inside vanilla's RD** the zoomed image must still pass the strict parity harness against vanilla's own spyglass.
The exceptions are the by-design differences, each with a side-by-side (GOAL (c)): entity and block-entity distance,
scope mask (exact), LOD beyond RD.

### 12.2 Scenes (to add to `bench/src/bench/Scenes.java`)

| # | Scene | Exercises |
|---|---|---|
| Z1 | Spyglass from a mountain top toward the horizon; zoom in, hold, zoom out | Wedge worst case; the §6.1 transient |
| Z2 | Spyglass at a forest edge 600 blocks away | Cutout leaves magnified at L0; plants |
| Z3 | Zoom key 50x on a village 3,000 blocks away | L0 at the LOD edge; block-entity proxies; absent entities |
| Z4 | Zoom key 4x over an ocean toward land | Translucent LOD; the moderate-zoom peak (§4) |
| Z5 | Zoom key Z = 2–4 over forested mountains | **Performance worst case**; HiZ gate |
| Z6 | Night, torches 1,500 blocks away, spyglass | Block light in the cache; emissives |
| Z7 | Herd of cows at 140–170 blocks, spyglass, LAN server | Zoom-aware entity distance; tracking-boundary pop (expected; measured) |
| Z8 | Z1 on a dedicated server at RD 10 | LOD only from received chunks; packet-capture diff |
| Z9 | Popular shaderpacks on Z1/Z2 at noon and sunset | Fitted shadows, SSAO, SSR, DoF, TAA during the animation |
| Z10 | RT mode on Z1/Z2/Z4 | Shadow and reflection sharpness; raster/BVH LOD mismatch; MetalFX transients |
| Z11 | Fast pan while zoomed (scripted 40°/s) | Leading-edge lag; fades |
| Z12 | Sprint and fly toggles at the L0 boundary | Hysteresis: no LOD thrash |
| Z13 | Spyglass raised within 50 ms of selecting it (no P1 lead) | Worst-case late refinement; H3 |

### 12.3 Metrics

**Static frames.** GOAL (a) at 3024x1964 against the truth render:
- mean CIEDE2000 < 1.0;
- 99.9th percentile < 3.0;
- SSIM ≥ 0.99;
- no connected large-error region.

**Temporal: the zoom-sequence popping metric.**
1. Capture every frame of the zoom-in and zoom-out sequences: the spyglass's vanilla schedule, the key with its
   150 ms ease, and the key in instant mode.
2. Per frame, `E_t` = the per-pixel ΔE00 of the candidate against the truth render.
3. A **pop** is a connected region of ≥ 16 panel px where `|E_t − E_{t−1}| > 3.0`.
4. Pass criteria (proposal):
   - 0 pops from 100 ms after the animation settles;
   - during the animation, a pop is allowed only inside an active H3 fade, with mean `E_t` < 2.0.
   - GOAL requires "no visible popping", so these thresholds are confirmed by side-by-side review (GOAL (c)).

**Instrumentation.**
- Per frame: the screen fraction where the drawn level violates τ (the SSE deficit).
- A histogram of the time from "needed" to "resident" per node.
- Prefetch hit rate per trigger.

**Performance.** Median FPS and 1% low for zoomed-static, during the animation (frame-time spikes) and for a fast
pan, each against the same scene unzoomed, and with every toggle on and off. Follow the bench methodology:
- non-Spaces fullscreen;
- frozen jar copies;
- settings pinned per run;
- never while other agents build.

**Multiplayer.**
- Packet-capture diff on LAN and on a dedicated server: identical packet types and counts, with timing inside
  vanilla's own run-to-run envelope.
- Z7's pop distance equals the tracking range.

### 12.4 Toggles (proposed; read once per frame like `TerrainToggles`)

| Property | Off means |
|---|---|
| `-Dmc.zoom=false` | No zoom key |
| `-Dmc.zoom.maxZ=50`, `-Dmc.zoom.animMs=150` | — |
| `-Dmc.lod.zoomAware=false` | SSE uses the option-FOV K, as distance-only LOD would (for the A/B) |
| `-Dmc.lod.prefetch=false` | No P1/P3/P5 |
| `-Dmc.lod.fade=false` | Late refinements swap immediately |
| `-Dmc.lod.tauGeo`, `-Dmc.lod.tauTex` | Thresholds |
| `-Dmc.zoom.entityScale=false` | Vanilla entity distance under zoom |
| `-Dmc.zoom.narrowEntityCull=false` | Vanilla wide entity frustum |
| `-Dmc.zoom.scopeMask=false` | No scope culling or depth mask |
| `-Dmc.shader.zoomShadowFit=false` | The pack's own shadow projection |
| `-Dmc.rt.prefetchBlas=false` | BLAS on first draw |

---

## 13. Open questions and risks (ranked)

1. **Vanilla-exact meshing throughput** (per surface chunk column) decides between CPU and GPU L0 meshing and how
   late the spyglass cone fills without P1. Measure first.
2. **Stage 3's τ values and the per-node error distribution** set every absolute number. The zoom *ratios* in §4
   hold either way. Even unzoomed, L0 must reach 700–1,400 blocks; that is a base-cost question for stage 3.
3. **Worst-case geometry at Z ≈ 2–4** (scene Z5): does two-phase HiZ plus buckets keep it within the gate?
4. **The H3 dither without temporal AA** may be visible. Alternatives: a geomorph for height-field-like nodes, or a
   longer colour-first fade.
5. **Per-pack shadow fitting** compatibility, and the uniform consistency for L0 LOD in `gbuffers_terrain` (fog at
   `far`).
6. **BLAS build throughput** on M5, and BVH memory for a full ring plus cone.
7. **Multiplayer pops at the tracking range** and the empty unvisited regions are unfixable. The user decides on the
   optional fade (§10). Also for the user: name tags stay vanilla.
8. **Determinism of client-side generation** against the server, especially features and structures. Needs a
   chunk-by-chunk comparison test.
9. **Memory.** Steady spyglass cone meshes of 0.17–1.6 GB, the permanent 50x cone of 70–400 MB, and the optional RAM
   tier of 0.6–1.3 GB. The machine's RAM size (24 GB on this M5, `results/BASELINE.md`) sets the defaults.
