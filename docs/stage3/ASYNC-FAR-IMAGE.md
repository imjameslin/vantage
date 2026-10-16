# Prompt 5 implementation constraints

Design notes from the Phase 0 cleanup review, 2026-10-08. No asynchronous renderer is implemented or measured
by this document. `docs/CODEX-PROMPTS.md`, Prompt 5, remains the acceptance contract.

## Queue and resource ownership

Use one optional native owner on the existing Metal device, with a separate command queue, event and three
image slots. Do not share `MCMTLDevice`'s ordered decoder/timeline state with an independently completing
queue. The ordinary command encoder is a singleton, not a separate GPU queue.

A refresh packet must own its uniform bytes, cull inputs, item/section tables, output lists and arguments.
The current `TerrainClusters.Draw` list is mutable across game frames. `LodGpu.releaseDeferred()`'s six-frame
range/slot retirement and eight-frame old-buffer retirement are not proof of far-queue completion. Geometry
needs allocation-range leases: retaining an MTLBuffer object does not prevent its range being overwritten.
Material revisions do not freeze atlas/lightmap pixels either; use immutable texture versions or ordered
copies. Pin pipelines and the actually selected built-in shader variant. Unsupported pack shaders fall back.

Signal upload/snapshot readiness from a committed main-queue submission; the far queue may wait for it on
the GPU. The main queue must never wait for a far job whose dependency is a later main submission. Offering
a refresh must not call `flushForWait()` or compile a shader on the render thread.

`MetalSubmissionTicket` supplies that commitment boundary without a decoder opcode or forced flush.
Capture is passive and shares the pending sequence's ticket. Only a successful committed native return
and completed Java submission bookkeeping publish COMMITTED; failure and shutdown publish FAILED.
COMMITTED is not GPU completion: the worker must still encode an event wait and retain all resources.

`MCMTLFarCullSnapshot` copies item, cull-section and shader-section tables and owns private cull outputs.
It validates BLOCK (112 bytes/face) and compact (48 bytes/face) ranges, section and index bounds, and
disjoint visibility-ID arena ranges before allocation. Caller-owned geometry leases remain necessary.
The initial capture admits complete frustum/facing culling, without the near image's occlusion history.

With `-Dmc.lod.async=true`, optional per-device source capture retains the exact selected Metal function's
source, entry point and compile options before worker compilation. It does not enable ICBs or substitute
a shader template. Missing provenance or an unsupported source must fall back to synchronous rendering.

The actual game retains the unused BLOCK/CHUNK_DATA vertex descriptor on manually pulled terrain
pipelines. Admit only that exact known layout (or empty), preserve it, and keep full-source checks.
The four selected opaque variants compile successfully in the bounded in-game admission probe; this
does not mean producer images have been connected to the game.

`LodFarGeometry` owns the refresh's CPU tables and range lease. `MetalFarGeometryPins` captures current
backing handles/offsets before handing it to a worker. `MCMTLFarGeometryPins` performs the large native
copy there, retaining actual device/event identities independently of later device-wrapper teardown.
Three atomic token slots bound native releases; `Minecraft.renderFrame` polls/drains them even in menus.
The Java capture still copies live tables on refresh, with a 64 MiB cap, and needs in-game CPU attribution.
Native copies must not be moved back onto every game frame.

`MCMTLFarMaterialVersion` owns private copies of every atlas/lightmap mip and preserves sampled view
ranges. Worker allocation is not a captured pixel version yet: the render thread must revalidate source
identities/revisions, record every main-stream copy, and capture a passive submission ticket afterwards.
Only COMMITTED permits publication and far-queue consumption behind that exact event/value. Java
wrappers own an additional +1 reference and defer release through the main timeline, protecting a copy
record if the offer is cancelled before native decoding. Destination pixels are immutable after publication.
No native boolean or generation number can replace this coordinator contract.

The Java material bridge declines before recording if a late-frame throttle remains armed. Otherwise
the first normal copy could wait/drain a callback after uniforms/source revisions were captured. Retrying
after the game's ordinary throttle boundary is allowed; the far capture itself never takes that wait.

`MTLSharedEvent.device` is nullable by the installed SDK contract (MTLEvent.h). The driver with validation
off returns nil where the validation wrapper can return a device. Accept nil for a shared event, reject
an explicitly different nonnil device, and in the engine require the **exact retained event identity**
from the geometry capture to equal the published material dependency. The committed Java ticket must
also belong to the captured device. Do not infer provenance from a nonnull `event.device` requirement.

`MetalFarEngine` bridges an independently owned native queue, immutable producer packets and newest/older
completed image leases. Its creation/submission execute on the worker. Acquiring a lease is bounded and
does not wait; a consumer that records reads transfers its +1 lease to the actual main timeline's deferred
release, never to producer completion. Source matrices and full target dimensions are copied explicitly.

The additional `-Dmc.lod.async.producerProbe=true` switch is a one-shot functional integration probe,
requiring `mc.lod.async` as well. It waits for a settled nonempty world, copies current materials through
the main stream, waits for COMMITTED by polling rather than blocking, then renders one original-camera
image on the worker queue and inspects completed metadata. It leaves every synchronous game draw active.
It has no guard band, reprojection, cadence scheduler, hole repair or performance claim. Menu polling and
device/world shutdown stop abandoned work and retire owners after worker use. This probe is not the
Phase 1 FPS/quality checkpoint.

Image slots move through FREE, PRODUCING, READY and RETIRING. Acquire only successful completed images.
Keep the newest and an older usable image; recycle a slot only after its producer and all main-queue
consumers complete. Retain geometry/material generations as long as their visibility IDs can be read.
Skip an offer when all slots are busy. Invalidation revokes eligibility immediately and retires storage later.
World changes, resource reload, device shutdown and failed jobs follow the same ownership rules.

Bound producer admission as well as image storage: coalesce offers into the latest pending snapshot,
allow at most one refresh on the GPU initially, and compile/allocate command buffers on the producer
worker rather than during the game-frame offer. Do not assume an own queue reserves GPU capacity or
gives near work priority. The installed macOS 27 SDK's classic `MTLCommandQueueDescriptor` exposes
`maxCommandBufferCount` and `logState`, not a GPU-priority setting. Refresh pacing and measured total
GPU occupancy must enforce the budget; CPU thread QoS is not evidence of GPU scheduling priority.

The main-thread `LodGpu.frame()` currently builds the draw list and calls the native LOD cull before
`prepareLodReuse()`. Merely replacing the later draws leaves that cull running every game frame. The async
path must move refresh culling into the producer work; current-view repair/fallback culling must be GPU
conditional on the warp's uncertainty decision. It must not eagerly raster or cull the entire far world
just to make a later fallback possible. Retaining the synchronous route for disabled/unsupported cases
does not mean running it alongside every successful composite.

Current CPU writes include arena payloads, section metadata and ring item tables. An allocation epoch
lease may conservatively delay range reuse across an entire snapshot rather than refcounting millions
of faces each frame. This is a proposed implementation, not an existing guarantee: leases must survive
both producer completion and every consumer of the stored IDs. Rebase/section metadata still needs an
owned snapshot, since protecting arena ranges does not freeze those mutable tables.

## Capture and composite

The existing `LodReuse.prepare()` pre-pass hook is after material/uniform updates. Snapshot the camera/origin,
projection and inverse, viewport, geometry/membership generations, near-chunk exclusions, material revisions,
uniforms, samplers and pipeline identities there. Recheck eligibility when consuming completed work.

Preserve central pixel density when adding a guard band: enlarge the target and scale projection X/Y by
the original/guarded dimensions. Widen capture culling and LOD selection too; selection currently reduces
detail outside the view. Correct screen-space fade-dither coordinates for the guard origin.

Retain SOLID/CUTOUT insertion order and reversed-depth equality behavior. `DrawIndirect` composites far
geometry after near geometry separately for these layers. One combined opaque image changes that order.
One nearest color/depth/ID cannot represent blended translucent layers. An initial opaque prototype may
keep water live, but must report that limitation and its cost; that is not completion of all far-terrain work.

The retained replay's `identitySource()` demonstrates adding an integer MRT to the original shaded draw
after its original discard decisions, without drawing geometry twice. IDs must identify a face in an owned
generation, not a transient compacted ordinal or recycled arena address. BLOCK and compact layouts retain
their original fetch paths. Exact re-shading also needs correct triangle interpolation, UV derivatives and
per-vertex lightmap sampling; sampling light once per destination pixel is not equivalent.

## Holes and correctness limits

Two prior images may both lack newly exposed geometry. A fully occupied destination can still show a
wrong, farther surface; a coverage mask alone does not certify visibility. Conservative uncertainty needs
geometry-backed validation, repair or a current synchronous render. Small unresolved holes cannot simply
be displayed until another refresh. A GPU-side same-frame fallback must be available before composite;
reading the decision back to the CPU would block the game.

Camera motion changes fog distance even if fog uniforms are unchanged. Reprojected color is therefore
not exact current shading. Phase 1 must meet the perceptual motion gate; Phase 2 re-shading addresses these
appearance inputs without re-rasterizing. Generic D32 reprojection is also not bit-identical current raster.

## Credible motion gate

The existing 25-frame `ReuseSequence` is a synchronous regression diagnostic, not an async acceptance test.
It covers 0.156 seconds of virtual time; its discrete translations are 43.6 blocks/s at 6.25 ms/frame.
Screenshot readback and pixel conversion alter wall time available for a far refresh.

For Phase 1, record a continuous production-cadence route without image readbacks: hold, fly past foreground
occluders at 10.9 blocks/s, turn at 360 degrees/s, combine motion/reversal, then stop. Log every actual frame
in preallocated bounded storage: full camera/projection/viewport, clocks, uniform bytes, resource epochs,
selected primary/secondary image IDs, hole/fallback decisions, and refresh request/submit/complete/publish
events. Overflow invalidates the run. Age is measured from the image's source camera time/frame, not completion.

Replay the recorded history afterward, regenerating the exact source images and using exactly the recorded
image selections. Compare every full-resolution output with a synchronous render at its recorded current
state; fail when inputs cannot be reproduced. Short bounded production capture windows verify replay fidelity,
with cadence/age overhead reported. Slow PNG capture must not silently substitute fresher images.

Report source age median/p95/p99/max in ms and game frames, refresh cadence, queue delay, coalesced requests,
fallback frequency, and primary/secondary contributions. Keep CPU selection age distinct from GPU execution
and presentation age. Require SSIM >= 0.99 and mean delta-E < 1.0 on every full-resolution frame, plus temporal
residuals and inspection for seams, holes, swimming and lag. FPS comes only from separate uninstrumented
in-game A/B. Apply Prompt 5's motion and 500-FPS kill criteria rather than extending experiments indefinitely.

## Cache behavior observed during Phase 0

`LodMeshCache.open()` removes every directory for a different code/options fingerprint. Switching the tagged
and cleaned frozen jars therefore discards the other build's disk mesh cache; immutable JAR hashes do not
imply warm mesh-cache starts. Keep initial settling outside measured FPS. In the reverse-order tagged run,
100 seconds after join there were still 41,667 pending meshes. This is preparation evidence, not a frame-rate
result and not a diagnosis of the moving-frame bottleneck.

For Phase 3, distinguish cache hit latency, namespace replacement and per-frame upload/selection cost. If
multiple fingerprints are retained, preserve the current code/material/world validation and enforce a total
disk cap across namespaces; do not reuse a differently keyed mesh to hide rebuilding time.
