# Microraster correctness contract

Status: rendering design only, inspected 2026-10-08; parent reports both normal and validation mixed compute/hardware-fragment atomic probes PASS. This document's author ran no GPU work or builds. Scope is current-frame opaque LOD microrasterization with native-raster fallback. Exact depth and coverage remain the objective; final-color acceptance is perceptual as specified below. Temporal tile reuse is a separate policy.

The parent owns the capability probe `tools/lod/micro-raster-atomics.m`, its `.sh` runner, and `results/FARSIGHT-MICRORASTER.md`. This document does not change those files. Parent-reported Step 0: **M5 / macOS 27.0, MSL 3.2 and 4.0: buffer and texture 64-bit min/max PASS**, 52,248 readback checks per case, eight 64-bit cases plus 32-bit controls. Both subsequent normal and validation mixed compute/hardware-fragment runs **PASS: 16 cases, 835,968 checked words per run, including 208,992 mixed-stage checks per run**. This validates shared atomic destinations in the tested configurations, not terrain coverage/interpolation. The parent's next step is the synthetic hardware coverage/depth probe.

The [probe's actual overloads][atomicapi] are non-fetch `atomic_min_explicit` / `atomic_max_explicit` on `atomic_ulong` buffers, and `texture2d<ulong>.atomic_min` / `.atomic_max`. The generic fetch-ulong form is not the correct overload. Its [texture allocation][atomictexture] uses RG32Uint plus ShaderAtomic usage. Do not infer these capabilities from a failed generic overload or assume texture and buffer support are interchangeable.

**Main contract: preserve native depth, coverage and GEQUAL tie semantics; validate reconstructed shading against naked-eye review, SSIM >= 0.99 and mean delta-E < 1.** Atomic correctness does not establish either result. Screen-ID re-shading needs separate derivative/interpolation validation, but does not require bit-identical final color or exhaustive formal proof of every interpolant to ship. The portable 32-bit protocol remains below for other devices; M5 Step 0 does not need it.

## Current reference behavior

| Reference | Required behavior |
| --- | --- |
| [DepthStencilState.java:4][depth], [RenderPipelines.java:1454][pipelines] | Opaque terrain uses GEQUAL with depth writes, no depth bias in the default state. Compact SOLID writes ordinary color; compact CUTOUT discards below alpha 0.5. TRANSLUCENT has a different blending contract and is excluded. |
| [TerrainVertexMsl.java:228][format] | A compact quad is three uint4 records: position/edges, four RGBA8 corner colors, four packed light coordinates, UV origin/edges and greedy tile. Palette mode at line 263 reconstructs the same attributes through dictionary addresses; it is not a material-only constant-color representation. |
| [TerrainVertexMsl.java:110][transform] | Camera-relative addition order, matrix multiplication and nested FMA order are intentional. Vertex Y is negated at line 138. Lean and billboard variants require their own matching path. |
| [LodGpu.java:1868][indices] | Each output quad becomes triangles `0,1,2` and `2,3,0`. The shared diagonal, primitive order and four corner attributes matter. |
| [ChunkSectionsToRender.java:237][draws], [302][loddraws], [ChunkSectionLayerGroup.java:6][layers] | Per opaque layer, native section draws and native cluster draws precede that layer's LOD draws. SOLID precedes CUTOUT. LOD draw-list order and indirect indexed order define tie precedence. |
| [terrain.fsh:77][fragment] | Atlas sampling and multiplication by interpolated vertex color precede alpha discard. LOD fade can discard too. Fog is applied to surviving color; it does not remove depth coverage. |
| [mcmetal_pipeline.m:693][raster], [mcmetal_decoder.m:6109][state] | Current PSOs use one sample, no alpha-to-coverage. Actual depth, cull, winding, fill and bias state are bound by the decoder. The backend supplies clockwise front winding [here][winding]. |
| [lod_reuse.fsh:7][composite] | Existing reuse composite treats depth zero as empty. This is not a general occupancy rule for a GEQUAL rasterizer; see clear-zero handling below. |

All comparisons are against the current selected pipeline and emitted geometry, including existing compact-position snapping. Matching an earlier uncompressed mesh instead would change the reference.

## Main rendering gate: native coverage, depth, interpolation and derivatives

Treat the following as separate validation concerns. The table describes the native reference and exact depth/coverage objective; it does not add a bit-identical color or exhaustive interpolant shipping requirement. Shading differences are judged by the user's perceptual gate.

| Gate | Native reference / objective | Concrete risk |
| --- | --- | --- |
| Coverage | Same triangle, pixel sample and shared-edge ownership after vertex transform, clipping, culling, viewport and scissor. | A compute edge function does not automatically reproduce fixed-function subpixel setup, clipping or edge rounding. A small triangle is not necessarily simpler: an edge error can add/remove its only pixel. |
| Depth | Same binary32 value used by native D32 testing/storage, including exact equal-depth outcomes. | Matching vertex clip values does not prove identical rasterizer interpolation/rounding. Even a one-ULP difference changes GEQUAL winners. Hardware fragment position Z must itself be checked against stored D32 before being treated as its bit-exact proxy. |
| Interpolation | Same perspective-correct UV0, vertexColor, spherical/cylindrical distances and chunkVisibility, plus the same flat greedy tile. | Reconstructing from a screen ID or saved barycentrics can change arithmetic association and interpolation precision. These are the actual [terrain varyings][varyings], not just UV and material ID. |
| Derivatives / sampling | Same primitive-local helper-lane evaluation, finite differences, sampler and mip selection before wrap/discard. | Fullscreen derivatives operate on the resolve quad's reconstructed fields; adjacent winner IDs need not belong to the same primitive. Analytic gradients are not automatically the native finite differences. |
| Shader survival and color | Same sampled alpha and fade discard before claiming a winner; same tint/light/fog and RGBA8 conversion for the surviving winner. | Choosing an opaque winner first and discovering its cutout hole during re-shading loses the background candidate. |

### Hardware correctness reference, not the proposed production route

First establish a hardware-only visibility/resolve reference using the original emitted triangle stream, original vertex arithmetic, and current raster state. This separates atomic selection correctness from software rasterization correctness. Native fragment candidates can contribute the packed depth/order key described below, after the original coverage/discard work. Only real covered, surviving fragments may update the key; helper-lane side effects and fragment ordering require validation. The parent's synthetic mixed-stage atomic test does not prove any of these terrain semantics.

Keep any depth attachment used for candidate admission an immutable native-prefix baseline, with depth writes disabled; alternatively admit against that baseline explicitly. Do not let a differently ordered intermediate depth pass silently define which candidates exist. Scratch key writes must not modify main color/depth before the winner decision is complete.

For a shading oracle, **re-rasterizing the original geometry with native interpolation is a correctness reference only**. It is not proposed as the production solution: retaining that raster work defeats the targeted bottleneck. In this reference, evaluate the original primitive's gradients and derivative-dependent samples under the original rasterization context; apply winner-ID rejection after that work and retain alpha/fade checks. An early ID-based discard followed by implicit derivative sampling can change the reference. Recompiling with an ID test still needs validation of compiler motion, early tests and helper execution. Final depth must match the selected depth bits.

A second possible reference is immutable per-candidate payload capture from the actual native fragment, followed by winner selection and payload copy; that requires bounded storage for every retained candidate and validation of output conversion. Neither reference is a production prescription. A single per-pixel color/payload slot written next to an atomic key is **not** immutable candidate storage: an older candidate can overwrite the payload after a newer key wins.

### Why screen-ID re-shading needs separate validation

A fullscreen or compute resolve that reads `winnerId[p]` knows which triangle won; it does not recover that triangle's native interpolator outputs or helper lanes. At a silhouette, adjacent screen pixels may identify foreground, background and empty space. Differencing those UVs is incorrect even when all IDs and depths are perfect. Within one triangle, an analytic derivative or interpolated world point can still differ from native finite differences and varying precision. The original [greedy fragment path][fragment] takes `dFdx/dFdy` **before** wrapping UVs, and [sampleNearest][sampling] also needs derivatives. Turning RGSS off therefore does not remove this requirement.

For reconstructed resolve, derive derivatives from the winner's own primitive, including helper positions outside coverage; never difference neighboring winner attributes. Native UV/gradient/interpolant diagnostics can locate errors, but final shading is accepted through naked-eye review, SSIM >= 0.99 and mean delta-E < 1. Exercise silhouettes, diagonal seams, tiny triangles, mip transitions and motion as well as interiors. Approximate shading may satisfy that gate; do not present it as bit-exact. Any derivative error changing cutout survival is also a coverage issue and must be addressed by the targeted coverage checks or native fallback. Production fallback is the original native unit, not mandatory additional re-raster shading passes.

## M5 64-bit winner key; portable 32-bit fallback

For the admitted depth domain below, encode `key = (ulong(as_uint(d)) << 32) | orderedId` and use unsigned **max**. This retains every depth bit and puts later draw order below depth in the comparison. Clear to 0; seed a prior native prefix as `(depthBits << 32) | 0`, preserving its color. A valid zero-depth candidate with ID > 0 still beats clear. Buffer and ulong-texture paths must use the tested interfaces and retain the entire 64-bit word; do not guess channel packing through a different texture view.

After all compute and hardware contributors complete with the required resource visibility, resolve the final winner through a shading route admitted above. Non-fetch max does not tell a candidate that it is the final winner. Do not publish color on the strength of a local update or a later unsynchronized key read. Mixed-stage success proves shared key updates only; it does not establish common candidate depth/coverage or native interpolation.

### Portable fallback: depth, then equality arbitration, then resolve

Use independently allocated device-buffer arrays `atomic_uint depthBits[pixel]` and `atomic_uint winnerId[pixel]`. This needs validated 32-bit buffer atomic max, not floating-point atomics or texture atomics. If that capability is unavailable, use native rasterization. Never pack a shortened depth and ID into one 32-bit word.

Define a candidate as an actual surviving triangle fragment at a pixel. It has native-equivalent binary32 depth `d`, exact discard result, and a unique nonzero ordered triangle/instance ID. For finite nonnegative binary32 values, unsigned bit order matches numeric depth order. Domain is reversed-Z depth in [0,1]; reject NaN, infinity, out-of-range values, unsupported subnormal behavior or ambiguous signed-zero behavior. Do not clamp an invalid candidate into range. Byte precision must match the D32 value native rasterization actually compares/stores.

1. **Initialize.** For a fresh isolated batch, clear depth bits to +0 and IDs to 0. For a batch inserted after an existing native prefix, initialize depth bits from that prefix's exact D32 snapshot and IDs to 0; preserve its color. Validate the snapshot domain as well. Zero ID means “no winner from this batch,” even when its baseline depth is nonzero.
2. **Depth pass.** Enumerate every admitted candidate, reproduce coverage, culling and all shader discards, then `atomic_max(depthBits[p], as_uint(d))`. A cutout hole contributes nothing. Color is not published here.
3. **Finish the entire depth pass.** Use separate ordered GPU phases with explicit producer/consumer visibility for the actual resource hazard mode. A threadgroup barrier cannot synchronize the image. Relaxed atomic operations alone do not establish a dispatch boundary.
4. **ID pass.** Enumerate the same candidates using the same frozen inputs and identical depth arithmetic/discard. Only when `as_uint(d) == finalDepthBits[p]`, perform `atomic_max(winnerId[p], orderedId)`. No epsilon, ULP window, approximate equality or depth quantization is permitted. Depth bits remain read-only throughout this pass.
5. **Finish the entire ID pass.** Resolve the selected winner using reconstruction validated by the acceptance gate, or immutable candidate payloads. Fullscreen ID re-shading is not presumed bit-exact. ID 0 preserves the baseline/empty pixel. Otherwise emit that winner's color and exact selected depth. They must not be written by racing candidates alongside the atomic max.

Proof: pass 1 obtains the maximum eligible depth (or greater baseline depth). Pass 2 obtains the greatest submission-order ID among candidates at that exact depth. These are precisely the lexicographic `(depth, order)` winners for opaque GEQUAL. If baseline wins strictly, ID stays 0; a candidate equal to the baseline wins because the batch is submitted after that prefix. Atomic execution order is irrelevant to this proof.

The proof does **not** establish that compute generated the same candidate depths or coverage as the hardware rasterizer. A 64-bit packed atomic path has the same prerequisite.

### Clear 0 and order IDs

An eligible fragment at depth +0 passes GEQUAL against a +0 clear. Its ID must become nonzero and its color must be written. Empty pixels and zero-depth geometry therefore need an ID/occupancy distinction. Reusing the existing zero-discard composite unmodified loses this case. Either supply an ID-aware resolve/composite, or reject the entire affected unit to native rasterization whenever zero-depth coverage is possible. Never silently drop that geometry.

IDs must increase with **actual emitted draw order**, including draw, instance, output quad and triangle-within-quad. IDs are not arena addresses, section IDs, materials, input cluster indices or worker arrival order. Reserve 0; validate the complete count/prefix calculation without overflow. If a unit needs more than UINT32_MAX nonzero IDs, fall back or split only at an order-preserving boundary.

The regular [cluster scan path][scan] preserves input order. The [fused path][fused] reserves output ranges with an atomic add, so global output ordering is not implied by input section order. Derive IDs from the final compacted stream that the reference draw consumes, and retain that stream across all passes. A different cull run is not an interchangeable reference for ties.

## Near geometry and large-face integration

For the first bounded design, leave near geometry native and replace only a contiguous segment of the original LOD stream, at its original position in its layer. Keep SOLID and CUTOUT sequencing intact. Moving all far composites after both near layers changes equal-depth precedence.

Large faces, clipped faces and other unsupported primitives must not simply be drawn “all before” or “all after” microfaces from an interleaved stream. Two valid designs exist:

- **Ordered runs:** preserve original order and alternate native runs with micro runs. Each micro run uses the immediately preceding depth baseline, resolves before the next native run, and observes normal GEQUAL ties. If the resulting partition exceeds a fixed budget, render the original unit natively.
- **Common arbitration:** native large-face candidates and micro candidates participate in the same final depth and ordered-ID competition. The native contribution requires a matching coverage/discard/depth route and IDs derived from the same total order. A native color/depth prepass without IDs cannot resolve ties against arbitrarily interleaved microfaces. This is additional implementation and parity work, not an existing capability.

An alternative spatial fallback can mark a tile unsafe and render **all original contributors to that tile**, in their original order, with exact scissor coverage. It must include occluded/background contributors; replaying only the problematic foreground primitive is insufficient. Native fallback and micro resolve must have disjoint committed pixel masks.

## Precision, coverage and reconstruction obligations

**Projection and raster coverage.** Use the actual drawing projection, terrain ModelView and Globals; do not substitute a culling projection. Match [the hand-written vertex arithmetic][transform] and selected lean path, including camera integer subtraction before conversion and billboard orientation [at lines 348–352][billboard]. Compile options and reassociation affect results; [native shader compilation][math] and the hand-written reassociation pragma are part of the reference.

Clip coordinates are [0,1] in Z. Define pixel mapping from the **post-vertex-flip Metal** clip position:
`x = viewportX + (clipX/clipW * .5 + .5) * width`,
`y = viewportY + (.5 - clipY/clipW * .5) * height`.
Do not apply the Y flip twice. For a full viewport, pixel centers are (x+.5,y+.5). Verify this mapping with geometry, not only a texture-copy oracle.

Match clipping, fixed-function subpixel edge setup, edge inclusion/shared-edge ownership, winding/cull state, scissor and viewport depth mapping. The repository does not prove Metal's internal raster setup precision. Mathematical barycentrics or an arbitrary edge epsilon are not evidence of identical coverage. Initially reject clip-plane crossings, near-zero W, degenerate/uncertain edges and unsupported raster state. Routing them to native must also obey the integration order above.

**Interpolation.** Treat the two quad triangles separately. Smooth attributes use perspective-correct interpolation; conceptually
`a = sum(lambda_i * a_i / w_i) / sum(lambda_i / w_i)`.
Depth follows rasterizer interpolation of projected depth, not “interpolate world position then reproject,” and its final D32 rounding must match. These equations describe semantics, not a guarantee that software arithmetic matches the hardware interpolation units. Do not use bilinear quad interpolation or select the nearest vertex/sample.

**Light and tint.** Decode each corner's original RGBA8 color, including baked biome tint/AO/shading. Reproduce the vertex-stage linear level-0 lightmap lookup and multiplication [in TerrainVertexMsl][light], then interpolate the resulting vertex color. Sampling the lightmap once at interpolated light UV changes the operation. UV2 low bytes are light coordinates; high bytes carry flat greedy tile metadata. Preserve flat-varying provoking-vertex semantics. Use current lightmap contents; zero-block or flicker-tolerant reuse keys are not permission to omit lighting from this current-frame renderer.

**UV and derivatives.** Reconstruct exact decoded UV0, then reproduce [terrain.fsh's unwrapped derivatives and greedy wrap][fragment]. “Nearest” is a shader name: [sampleNearest][sampling] adjusts UVs and calls textureGrad. The [actual terrain sampler][sampler] has linear min/mag filtering and may enable anisotropy. RGSS uses [four offset samples at two mip levels and blending][rgss]. Atlas padding, mip contents, addressing, sampler LOD behavior and all selected shader specializations remain dependencies.

Compute cannot infer derivatives from neighboring winning pixels: those pixels can belong to different primitives or be empty. Reconstruct primitive-local gradients, including helper positions outside coverage where applicable. Analytic gradients alone do not prove equality with native finite differences; tiny triangles remain important cases. Evaluate shading differences through the perceptual gate, and alpha/discard differences through the coverage objective. Forcing mip 0, nearest sampling or disabling RGSS must not be called exact; such a change needs the same visual acceptance and coverage checks. Fall back when the admitted scope fails those checks.

**Discard and fog.** In both winner passes, reproduce alpha after atlas filtering and vertex-color multiplication; use the selected cutoff (0.5 for current opaque CUTOUT), with its strict `alpha < cutoff` test. Reproduce [LOD_FADE's screen-space discard][fragment] or exclude fading sections. Never let a subsequently discarded candidate reserve depth. Retain the same atlas/lightmap/matrices across both passes and resolve.

Fog uses interpolated vertex-computed spherical/cylindrical distances, then [LOD distance compression and fog mixing][fog]. Recomputing distance from a reconstructed fragment position is different. Preserve RGB/alpha handling, float evaluation and final RGBA8 conversion. Match the active [fragment specialization][variants]; changed/custom source, unsupported defines or unaudited pipeline state require native rendering.

## Concrete blockers and fail-closed behavior

| Blocker | Conservative response |
| --- | --- |
| 64-bit atomics absent | Use the two-pass 32-bit buffer protocol only after 32-bit atomic support is validated. No depth-bit reduction. |
| Coverage/D32 failures or shading that misses the perceptual gate | Keep the affected primitive class/unit native. Atomic success cannot waive these failures; exhaustive formal interpolant proof is not required. |
| Clipping, billboards, noncompact fallback vertices, fade or pipeline variant unsupported | Preserve original native draws in order; do not interpret a different record layout as compact. [Draw codes][codes] select compact/fade variants independently. |
| Mixed native/micro tie order cannot be represented | Render the original ordered unit natively, or use ordered runs. No blanket near/large-face reordering. |
| Candidate, pixel, bin-list or ID count overflow; unknown offsets; allocation failure | Reject before out-of-bounds accesses; never truncate a candidate list. Validate slice plus backing offsets and all byte extents. |
| Invalid depth/attributes, unsafe interpolation denominator, inconsistent repeated pass result | Set a GPU unsafe flag and reject the complete dependency unit. Do not commit a partially valid image. |
| Atlas/lightmap/input mutation, resource reload, lost source/state guard | Reject the transaction; retain input generations/resources through GPU completion. No temporal approximation in this contract. |
| Unsupported MSAA, blend/OIT, depth bias, fragment-depth modification or sample state | Native path. Current scope is one-sample, unblended SOLID/CUTOUT with audited depth behavior. |

All speculative output remains scratch until validity is known. A same-frame GPU predicate must suppress invalid micro resolve and enable the original native fallback draws; use original indexed arguments, not reconstructed counts. An unsafe unit must never composite stale scratch. Cross-unit depth baselines require dependency-aware failure handling: reject descendants or conservatively reject the whole ordered batch. Reuse resources only after the existing device timeline proves completion, with bounded in-flight ownership. No CPU readback/wait belongs in the rendering path.

## Acceptance and targeted diagnostic evidence

The user's shipping gate is **naked-eye acceptance, SSIM >= 0.99 and mean delta-E < 1**, using the agreed image-comparison protocol. Preserve exact depth/coverage as the requested implementation objective and test it with the parent's native coverage/depth probe. Bit-identical final color and exhaustive proof over all interpolants are not additional shipping bars. The following are targeted checks/diagnostics, not tests run for this document:

1. Compare final color against the original emitted native stream using the perceptual gate. Compare coverage and D32 bits in the targeted correctness fixtures, including occupied-zero pixels via IDs/coverage. Byte-identical color is useful diagnostic evidence, not required acceptance.
2. Establish coverage and D32 parity independently of final color: shared edges/diagonals, subpixel triangles, grazing edges, viewport borders, vertical displacement, flipped-Y geometry, clipping and near-W cases; both windings. The oracle must be the native rasterizer, not a second implementation of the same compute equations.
3. Equal-depth overlapping colors, reversed input order, one-D32-ULP separation, clear-zero and zero-depth geometry; mixed native/micro large faces and near SOLID/CUTOUT ties.
4. Alpha just below/equal/above cutoff, texture holes revealing background, animated alpha, greedy seams, mip transitions, RGSS and anisotropic admission/rejection. Inspect primitive-local native UV/interpolant/gradient outputs around silhouettes and the quad diagonal, then verify final production-shader output too; instrumented shaders alone do not prove unchanged compiler behavior.
5. Varied per-corner color/light, fractional smooth light coordinates, fog transitions, compact/palette identity, unsupported billboard/fade fallback.
6. Atomic contention and randomized dispatch scheduling with unchanged final stream, maximum IDs/counts, forced overflow/failure, and repeated in-flight frames. Verify original fallback arguments and zero invalid composite work.
7. Record which hardware/PSOs/settings passed. Keep capability-probe success, winner arbitration, targeted depth/coverage results and perceptual image acceptance as separate results.

Keep the prototype default-off until the agreed acceptance gates pass for its admitted scope. No frame-rate prediction follows from this contract.

[atomicapi]: <tools/lod/micro-raster-atomics.m:16>
[atomictexture]: <tools/lod/micro-raster-atomics.m:89>
[varyings]: <client/src/com/mojang/renderpearl/backend/metal/TerrainVertexMsl.java:74>

[depth]: <src/vanilla/com/mojang/renderpearl/api/pipeline/DepthStencilState.java:4>
[pipelines]: <client/src/net/minecraft/client/renderer/RenderPipelines.java:1454>
[codes]: <client/src/net/minecraft/client/renderer/RenderPipelines.java:1563>
[format]: <client/src/com/mojang/renderpearl/backend/metal/TerrainVertexMsl.java:228>
[transform]: <client/src/com/mojang/renderpearl/backend/metal/TerrainVertexMsl.java:110>
[billboard]: <client/src/com/mojang/renderpearl/backend/metal/TerrainVertexMsl.java:323>
[indices]: <client/src/net/minecraft/client/renderer/chunk/LodGpu.java:1868>
[draws]: <client/src/net/minecraft/client/renderer/chunk/ChunkSectionsToRender.java:237>
[loddraws]: <client/src/net/minecraft/client/renderer/chunk/ChunkSectionsToRender.java:302>
[layers]: <src/vanilla/net/minecraft/client/renderer/chunk/ChunkSectionLayerGroup.java:6>
[fused]: <native/src/mcmetal_clusters.m:292>
[scan]: <native/src/mcmetal_clusters.m:19>
[fragment]: <client/resources/assets/minecraft/shaders/core/terrain.fsh:77>
[rgss]: <client/resources/assets/minecraft/shaders/core/terrain.fsh:26>
[sampling]: <src/vanilla/assets/minecraft/shaders/include/texture_sampling.glsl:4>
[light]: <client/src/com/mojang/renderpearl/backend/metal/TerrainVertexMsl.java:86>
[fog]: <client/resources/assets/minecraft/shaders/include/fog.glsl:35>
[sampler]: <client/src/net/minecraft/client/renderer/LevelRenderer.java:551>
[raster]: <native/src/mcmetal_pipeline.m:693>
[math]: <native/src/mcmetal_pipeline.m:382>
[state]: <native/src/mcmetal_decoder.m:6109>
[winding]: <client/src/com/mojang/renderpearl/backend/metal/MetalRenderPipeline.java:871>
[composite]: <client/resources/assets/minecraft/shaders/core/lod_reuse.fsh:7>
[variants]: <client/src/com/mojang/renderpearl/backend/metal/TerrainShaderVariants.java:146>
