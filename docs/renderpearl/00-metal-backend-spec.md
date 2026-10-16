# 00 — RenderPearl Metal Backend: Authoritative Implementation Contract

This is the single contract a native **Metal** backend for Minecraft Java 26.3's RenderPearl codes against. Target machine: Apple M5 (10-core GPU, `MTLGPUFamilyApple10` + `MTLGPUFamilyMetal4`), macOS 27, unified memory, 3024x1964 Retina panel. It synthesizes the eight subsystem reports in this directory:

| Doc | Subsystem |
|---|---|
| [01](01-frontend-and-device.md) | Frontend, device API, capabilities, fences, timestamps |
| [02](02-shader-pipeline.md) | GLSL → SPIR-V → backend pipelines, SPIRV-Cross MSL plan |
| [03](03-buffers-textures-memory.md) | Buffers, textures, samplers, transient memory, formats |
| [04](04-command-encoding.md) | Command encoder, render passes, draws, hot path |
| [05](05-surface-window-present.md) | SDL window, CAMetalLayer surface, present, HiDPI |
| [06](06-opengl-backend-mac-bottlenecks.md) | OpenGL backend as reference, why GL is slow on macOS |
| [07](07-game-render-path.md) | Game-side frame, terrain, features, CPU profile |
| [08](08-native-interop-and-metal4.md) | Java → native interop (FFM), Metal 4 / macOS 27 SDK survey |

Where the reports disagreed, this document has re-read the source (and in a few cases run small Metal API-validation probes in the session scratchpad) and states one answer. §10 lists every resolved conflict with its evidence, and everything that remains open.

**Conventions.** All source paths are relative to `src/vanilla/`. Abbreviations: `RP/` = `com/mojang/renderpearl/`, `B3D/` = `com/mojang/blaze3d/`, `MC/` = `net/minecraft/client/`. `file:N` is a line, `file:N-M` a range (Vineflower output in this workspace). Normative words: **MUST**, **SHOULD**, **MAY**.

**Evidence tags.**
- **[S]** re-verified against source during this synthesis.
- **[P]** verified by a synthesis probe on this M5 under `MTL_DEBUG_LAYER=1` (Appendix A).
- **[R0x]** verified by reader 0x with a probe (see that doc).
- **[D]** a design decision made by this spec (normative for the implementation).
- **[O]** open; see §10.3.

---

## 0. The contract on one page

1. **Thin, mostly unvalidating frontend.** `FrontendGpuDevice`/`FrontendCommandEncoder`/`FrontendRenderPass`/`FrontendGpuSurface` wrap backend objects 1:1. Draw-time validation runs only when `STRICT_VALIDATION = SharedConstants.IS_RUNNING_IN_IDE` (`RP/frontend/FrontendGpuDevice.java:36`), which is **false** in the shipped game [S]. In release the backend receives draws with possibly missing uniforms, vertex buffers or index buffers, and MUST NOT fault the GPU.
2. **One command encoder per process.** `backend.createCommandEncoder()` is called exactly once, in the `FrontendGpuDevice` constructor (`RP/frontend/FrontendGpuDevice.java:50`) [S]. Treat it as an immediate context owned by the render thread.
3. **Everything is totally ordered.** RenderPearl assumes every command (pass, clear, copy, write) is ordered after everything recorded before it. Vulkan inserts a global `ALL_COMMANDS→ALL_COMMANDS` memory barrier after every pass, clear and copy (`RP/backend/vulkan/VulkanCommandEncoder.java:251-260`). `writeToBuffer`/`copyToBuffer`/texture writes are **GPU-timeline ordered**, never immediate CPU memcpys into memory earlier work still reads (the game rewrites the same buffers mid-frame and every frame).
4. **One `submit()` per frame**, after `blitFromTexture` and before `present()` (`MC/Minecraft.java:1322,1337,1340`) [S]. After committing frame S, `submit()` blocks until frame S−1 has completed: **2 frames in flight** [D] (§1.5 proves 3 would stall in game code).
5. **Fences are submit-granular.** A `GpuFence` completes when the whole submit it was created in completes. `awaitCompletion(0)` on the still-recording submit returns `false`; a non-zero timeout on it throws in GL and Vulkan (`RP/backend/vulkan/VulkanCommandEncoder.java:848-853`) [S]. Timeouts are **nanoseconds**, `-1` = forever.
6. **`close()` never destroys immediately.** The game closes buffers, textures, samplers and pipelines that recorded-but-unsubmitted and in-flight work still use. All native releases are deferred until the GPU timeline passes the last possible use.
7. **Coordinates.** Report `isZZeroToOne = true`. Depth is reverse-Z (`GREATER_THAN_OR_EQUAL`, cleared to 0.0), `D32_FLOAT` → `Depth32Float`. Render-target memory row 0 = NDC y = −1 (GL/Vulkan layout): compile every vertex shader with SPIRV-Cross `FLIP_VERTEX_Y`, use `MTLWindingClockwise`, pass scissors and clear rectangles through unchanged, never flip uploads/sampling/readback, and **flip once in the present pass** [R02].
8. **Bindings.** `setUniform(index, …)` index == SPIR-V `(set 0, binding index)` == position in `CreateInfo.uniforms()`. Map to Metal: UBOs `[[buffer(0..13)]]`, push constants `[[buffer(14)]]`, vertex buffer slot *s* → `[[buffer(30−s)]]`, textures/samplers dense `[[texture(t)]]/[[sampler(t)]]` (§3).
9. **`minUniformOffsetAlignment = 16`** [P] (Metal validation requires constant-buffer offsets to be a multiple of the bound type's alignment; 16 for any std140 block, 256 is not required on Apple silicon).
10. **Present = one full-screen render pass** into a `BGRA8Unorm`, `framebufferOnly` drawable that flips Y, swizzles and forces alpha 1. A blit cannot do it. Acquire the drawable **late** (not in `acquireNextTexture`). Wrap every Objective-C-touching call in an autorelease pool (the `-XstartOnFirstThread` main thread's outer pool is never drained).
11. **Claim the fast paths:** `nonZeroFirstInstance`, `drawIndirect`, `multiDrawIndirect` = true, `maxDrawIndirectDrawCount = Integer.MAX_VALUE`, `multiDrawIndirectHasKnownIssues = false` (MDI terrain), `persistentMapping = true`, `writeToBufferIsSlow = true` (persistently-mapped chunk staging), `isZZeroToOne = true`.
12. **Interop.** No Metal bindings exist in LWJGL 3.4.3. Use a thin Objective-C (ARC) shim with a coarse C ABI, called through FFM `static final MethodHandle`s; Java records an off-heap command stream and the shim decodes and encodes it in one downcall per submit/flush [R08: ~4000 draws decoded+encoded+committed in 0.21 ms CPU].

---

## 1. Architecture overview

### 1.1 Layering

```
 game (net/minecraft/…, com/mojang/blaze3d/…)
   │  GpuDevice / CommandEncoder / RenderPass / GpuSurface  (RP/api/…)
   ▼
 RenderPearl frontend (unchanged)                          RP/frontend/…
   │  validation (mostly IDE-only), name→index uniform resolution, debug-group balance,
   │  Tracy zones, GLSL→SPIR-V (shaderc) + reflection + binding patching (spvc)
   │  GpuDeviceBackend / CommandEncoderBackend / RenderPassBackend / GpuSurfaceBackend  (RP/backend/api/…)
   ▼
 com.mojang.renderpearl.backend.metal   (Java, NEW)
   ├─ MetalBackend (GpuBackend)            loads libmcmetal, createWindow(| SDL_WINDOW_METAL)
   ├─ MetalDevice (GpuDeviceBackend)       DeviceInfo, resource factories, pipeline compile (workers)
   ├─ MetalCommandEncoder / MetalRenderPass  → record into an off-heap command stream
   ├─ MetalBuffer extends BaseGpuBuffer, MetalTexture extends BaseGpuTexture,
   │  MetalTextureView extends BaseGpuTextureView, MetalSampler, MetalFence, MetalQueryPool
   ├─ MetalTransientMemory (reuses RP/backend/util/TransientBlockAllocator)
   ├─ MslTranslator (SPIR-V → MSL via org.lwjgl.util.spvc.Spvc, backend 3)
   ├─ MetalTimeline (MTLSharedEvent values, deferred-release FIFO, callbacks)
   └─ MtlNative (≈25 FFM downcall handles)
   │  ~1 downcall per submit/flush + creation/destruction batches
   ▼
 libmcmetal.dylib (Objective-C, ARC, C ABI; @autoreleasepool + @try in every entry point)
   ├─ device, queue(s), compiler, residency set, shared event, argument tables
   ├─ stream decoder → render/blit(compute) encoders, barriers, commit, signal
   └─ surface: CAMetalLayer from SDL_Metal_GetLayer, nextDrawable, present pass, present
   ▼
 Metal.framework / QuartzCore / (optional) MetalFX
```

### 1.2 Who does what

| Concern | Frontend (already done) | Backend MUST |
|---|---|---|
| Encoder state machine | Single `isInRenderPass` flag; rejects new pass, clears, copies, writes, `createFence`, surface blit while a pass is open (`RP/frontend/FrontendCommandEncoder.java:101,232-283,293,330,402,466,535,619,638,713,786`; `RP/frontend/FrontendGpuSurface.java:82`) | Throw if `submit()` arrives with a pass open (unchecked by the frontend, `FrontendCommandEncoder.java:46-51`) [S]. Accept `writeTimestamp` while the frontend flag is set (Tracy push happens after the flag is set, before `backend.createRenderPass`, `:198-204`) [S]. |
| Attachment checks | Count ≤ `maxColorAttachments`, ≥1 attachment, usage RENDER_ATTACHMENT, 1 layer, equal sizes, render area in bounds (`FrontendCommandEncoder.java:100-216`) | Depth attachment **format** is never checked; pipeline depth format is not in `CreateInfo` → PSO variants per pass signature. |
| Pipeline/attachment formats | `setPipeline`: color target count == attachment count, equal format for non-null attachments (`RP/frontend/FrontendRenderPass.java:104-121`) [S] | Depth variant selection; null attachment vs non-null target → `Invalid` slot. |
| Uniform offset alignment | `offset % minUniformOffsetAlignment == 0` for every `GpuBufferSlice` uniform, always (`FrontendRenderPass.java:150-157`) [S] | Nothing extra. |
| Uniform presence/kind/usage | STRICT only (`FrontendRenderPass.java:504-634`) | Survive missing bindings (bind dummies) — §3.8. |
| Vertex buffer usage | `setVertexBuffer`: slot range, open, `USAGE_VERTEX` (always, `:212-227`) | Null slice = keep previous binding (Vulkan semantic). |
| Index buffer | Nothing in release (`:230-233`) | Guard null index buffer at draw. |
| Indirect draws | Feature gates, `USAGE_INDIRECT_PARAMETERS`, length ≥ 20·n, offset % 4 (`:302-329`) | Honor `maxDrawIndirectDrawCount`. |
| Copies/writes | Usage bits, bounds, closed state, equal lengths (`FrontendCommandEncoder.java:292-782`) | 5-arg `copyTextureToBuffer` is **unvalidated** (`:618-624`); `copyBufferToTexture` byte check ignores sx/sy (`:557`). Validate both. Metal alignment rules (§2.3). |
| Timestamps | `0 ≤ index ≤ size` (off by one, `:795`; pass variant `FrontendRenderPass.java:96`) [S] | Bounds-check `index < size`. |
| Surface | Call order, acquired/blitted flags, COPY_SRC, color aspect, 1 layer (`FrontendGpuSurface.java:22-125`) | Platform work; recoverable failures as `SurfaceException`; never throw from `present()`. |
| Shaders | GLSL→SPIR-V, reflection, interface validation, binding assignment and SPIR-V patching (`RP/frontend/shaders/PipelineBuilder.java:65-368`) | SPIR-V→MSL, `MTLLibrary`, PSO, vertex descriptor, depth-stencil state. |

### 1.3 Frame lifecycle (render thread, `MC/Minecraft.java:1245-1374`) [S]

The whole frame body is guarded by `if (!this.windowSurface.isAcquired())` (`:1246`); `renderFrame` is re-entered from loading loops.

| Step | Code | Backend work |
|---|---|---|
| 1. Configure if `needsReconfig \|\| (isSuboptimal && !surfaceIsInvalid)` and not iconified | `:1254-1269` | `configure`: set layer `drawableSize`, `displaySyncEnabled`; no GPU wait. |
| 2. `acquireNextTexture()` | `:1273` | Iconified → `SurfaceException`. Otherwise **no `nextDrawable` here** (§6). |
| 3. TimerQuery begin (only with GPU-utilization overlay/metrics) | `:1287-1290` | encoder-level `writeTimestamp`. |
| 4. update / pick / extract (CPU) | `:1294-1305` | Mapped writes by game code (`GpuBuffer.map`). |
| 5. `RenderSystem.executePendingTasks()` | `:1310` (`B3D/systems/RenderSystem.java:327-341`) | Fence polls `awaitCompletion(0)` → must be a field compare. |
| 6. `gameRenderer.render()` | `:1312` | All clears, writes, copies, ~8 render passes (§9 inventory), possibly lazy pipeline compiles (`B3D/pipeline/PipelineCache.java:33` joins on the render thread). |
| 7. `blitFromTexture(encoder, mainColorView)` | `:1316-1323` | Record PRESENT; optionally flush (commit) the frame's work so far (§6.6). |
| 8. TimerQuery end, Tracy capture | `:1325-1334` | Timestamp, copies after the blit. |
| 9. `submit()` | `:1337` | Encode remaining work + present pass (drawable acquired here), signal timeline, commit, wait for frame S−1, drain completions. |
| 10. `present()` | `:1339-1341` | Classic: bookkeeping (present was registered on the command buffer). Metal 4: `[drawable present]`. |
| 11. `DynamicGpuData.reset()`, `levelRenderer.endFrame()` | `:1348-1349` | **Ring rotations after submit**: their fences capture index S+1 (§1.5). |
| 12. Frame limiter if `framerateLimit < 260` | `:1351-1354` | — |

Shutdown (`MC/Minecraft.java:1153-1162`) [S]: `windowSurface.close()` → `RenderSystem.shutdownRenderer()` (closes pipeline caches, shared index buffers, samplers, dynamic GPU data, then `DEVICE.close()`, `B3D/systems/RenderSystem.java:230-250`) → `window.close()` → `unloadTrackedBackendLibrary()` → `SDL_Quit()` → `Util.shutdownExecutors()`. There is **no final `submit()`**; compile workers may still be running when the device closes.

### 1.4 Threading model [S]

| Thread | Calls | Rules |
|---|---|---|
| Render thread = process main thread = AppKit main thread (`-XstartOnFirstThread`; `MC/main/Main.java:269-270`) | Every device/encoder/pass/surface call, `GpuBuffer.map/close`, fence waits, query reads, `Pending.finishCompile()` (reload path and lazy `PipelineCache.get`) | Single-threaded; no locks needed for encoder state. SDL Metal view and CAMetalLayer property changes must be on this thread (they are). |
| Shader compile workers (`ShaderManager` reload `taskExecutor`, `Util.backgroundExecutor()` for lazy compiles) | `GpuDeviceBackend.compilePipeline(createInfo)`, concurrent; `getDeviceInfo()` | MUST be thread-safe and MUST NOT require the render thread (the render thread may be blocked in `.join()` at `PipelineCache.java:33`, sometimes with a render pass open) [S]. |
| Chunk mesh workers | No API calls; `memcpy` into the mapped persistently-mapped staging ring under `SectionRenderDispatcher.copyLock` (`MC/renderer/chunk/SectionRenderDispatcher.java:368-401`) | Shared storage needs no flush; the backend never touches that memory concurrently. |
| Metal completion/feedback threads (backend-owned) | — | MUST NOT call game or frontend code. Only publish atomics (completed timeline value, errors) that the render thread reads. |

### 1.5 Synchronization and lifetime model (normative) [D]

**Timeline.** One `MTLSharedEvent` per device. Every command-buffer commit that ends a "sync point" signals a monotonically increasing value `seq`. `pendingSeq` is the value the next commit will signal. A Java `volatile long completedSeq` is refreshed from `event.signaledValue` (one cheap property read, or a shared-memory word written by a `notifyListener`/feedback handler — never an upcall).

**Frames.** `submit()` ends frame S by committing with `seq = pendingSeq` and records `frameSeq[S]`. It then waits until `completedSeq ≥ frameSeq[S−1]` (2 frames in flight), with a 5 s cap that throws `IllegalStateException` (Vulkan parity, `VulkanCommandEncoder.java:270-273`) or `GpuDeviceLossException` if a command buffer reported an error.

**Why 2 frames in flight, not 3 (new finding).** `MappableRingBuffer` holds 3 buffers; `rotate()` creates a fence for the *current* submit index and `currentBuffer()` waits with `-1` on the fence of the slot it reuses (`MC/renderer/MappableRingBuffer.java:36-54`) [S]. `DynamicGpuData.reset()` (`MC/Minecraft.java:1348`) and `LevelRenderer.endFrame()` → `CloudRenderer.endFrame()` (`:1349`; `MC/renderer/LevelRenderer.java:1151-1153`; `MC/renderer/CloudRenderer.java:353-354`) rotate **after** `submit()`, so those fences capture index S+1. The slot is reused while recording frame S+3, which therefore needs frame S+1 complete. With 2 in flight, frame S+1 is already complete when S+3 starts recording; with 3 in flight only S is guaranteed, so the render thread would block inside `currentBuffer()` every frame. Three frames in flight need a game patch (§9.2 G5: move those two calls before `submit()` so their fences capture S, or set `MappableRingBuffer.BUFFER_COUNT = 4`). Rings rotated before `submit()` (fog `MC/renderer/fog/FogRenderer.java:84-85`, lightmap, post passes, chunk staging) already tolerate 3.

**Fences.** `createFence()` returns `{seq = pendingSeq}` (cheap Java object, no native object). `awaitCompletion(t)`:
- `seq ≤ completedSeq` → `true` (refresh `completedSeq` first with one property read if needed).
- `seq == pendingSeq` (work not yet committed): `t == 0` → `false`. `t ≠ 0` → vanilla GL/Vulkan throw `IllegalStateException("Cannot wait on a fence for the current submit")`. **RECOMMENDED extension [D]:** if no render encoder is open, *flush* instead: commit the recorded work so far as a sync point signalling `pendingSeq`, increment `pendingSeq`, start a new command buffer for the rest of the frame, then wait. This is a strict superset of vanilla semantics (all work recorded before the fence is complete when it returns) and makes `StagingBuffer.PersistentlyMapped` robust when it rotates three times in one frame (§10.1, C7). If a render encoder is open, throw.
- otherwise `t == 0` → `false`; `t > 0` → `waitUntilSignaledValue:seq timeoutMS:ceil(t/1e6)`; `t < 0` → wait forever (`UINT64_MAX`).
- `close()` marks the fence complete (no native work).

**Deferred release.** One FIFO of `(seq, action)` entries on the render thread, where `action` is a native release, a residency-set removal, a transient-block recycle, or a `copyTextureToBuffer` callback. Anything closed or recorded while `pendingSeq = v` is tagged `v`. The FIFO drains in `submit()` after the throttle wait and opportunistically in fence polls, executing every entry with `seq ≤ completedSeq` **in FIFO order on the render thread**. This reproduces Vulkan's `DestructionQueue(2)` (`RP/backend/vulkan/DestructionQueue.java:30-47`) and is mandatory with unretained-reference command buffers, Metal 4, and residency sets.

**Transient memory and rings.** Transient allocations made while recording frame S are valid until `submit()` (then `isClosed()` flips) and are recycled only after `frameSeq[S]` completes. Reuse `RP/backend/util/TransientBlockAllocator` and run the `Runnable` returned by `rotate()` through the deferred FIFO.

**Command-buffer splitting is allowed** (early commit at `blitFromTexture`, flush-on-wait) as long as: (a) Metal executes command buffers of one queue in commit order; (b) frame-level throttling, transient rotation and callbacks key on the frame's *last* seq; (c) CPU writes into shared memory read by an already-committed command buffer must have happened before that commit (vanilla only writes after the blit for TimerQuery/Tracy, which go into the later buffer).

### 1.6 Bring-up and shutdown order [S]

1. `RenderSystem.initBackendSystem()` → `SDL_Init(VIDEO)` (`B3D/systems/RenderSystem.java:198-218`).
2. `MetalBackend.loadLibrary()`, then `createDevice(GpuDebugOptions)` (`MC/Minecraft.java:484-511`). **No window exists yet.** The `MetalDevice` constructor MUST fully initialize: `MTLCreateSystemDefaultDevice`, queue, shared event, `DeviceInfo`, the command encoder, query support. `FrontendGpuDevice`'s constructor immediately calls `createTimestampQueryPool(1024)`, `getDeviceInfo()` and `getTimestampCalibrationOffset()` if Tracy is present (`RP/frontend/TracyGpuProfiler.java:22-29`), then `createCommandEncoder()` once, then builds `PipelineBuilder`, which reads `isZZeroToOne` and `shaderDrawParameters` (`RP/frontend/FrontendGpuDevice.java:42-52`) [S].
3. `RenderSystem.initRenderer(device)` creates `DynamicGpuData` (mapped rings, fences) and 32 samplers (`B3D/systems/RenderSystem.java:220-228`; `B3D/systems/SamplerCache.java:13-31`). **Buffers, samplers and fences must work with no surface.**
4. Window: `backend.createWindow(title, w, h, 8224)` where 8224 = `SDL_WINDOW_RESIZABLE | SDL_WINDOW_HIGH_PIXEL_DENSITY` (`B3D/platform/Window.java:163-171`) [S].
5. `device.createSurface(window.handle(), window::isIconified)` (`MC/Minecraft.java:544`), once for the game's lifetime.
6. Pipelines compile during the first resource reload.
7. Shutdown as in §1.3. `MetalSurface.close()` MUST wait for the last frame's seq, release any drawable and call `SDL_Metal_DestroyView` before the window is destroyed. `MetalDevice.close()` MUST end any open encoder, commit recorded work (or discard it), wait for idle, drain the deferred FIFO (Vulkan's `DestructionQueue.close()` also runs pending callbacks), set a `closed` flag that makes late `compilePipeline` calls return `Pending.NULL`, then release device objects.

### 1.7 Interop and command-stream architecture [D]

- **Binding technology:** FFM (`java.lang.foreign`, final in the shipped Microsoft OpenJDK 25.0.1) with hand-written `static final MethodHandle` fields invoked with `invokeExact` [R08]. The launcher already passes `--enable-native-access=ALL-UNNAMED`. LWJGL's `JNI.invoke*` helpers are FFM-backed on Java 25 and acceptable for a prototype. Use `Linker.Option.critical(false)` only for trivial getters (timeline value). `submit`, flush, `nextDrawable`, waits and compiles MUST stay non-critical (critical downcalls block ZGC safepoints).
- **Shim:** `libmcmetal.dylib`, Objective-C with ARC, `-mmacosx-version-min=26.0`, pure C ABI, every export wrapped in `@autoreleasepool { @try { … } @catch { return error; } }`, `mcmtl_abi_version()` checked in `loadLibrary()`. Objects return as +1 retained pointers (`__bridge_retained`) and are released through the deferred FIFO. No upcalls. Package it at `macos/arm64/com/mojang/renderpearl/backend/metal/libmcmetal.dylib` in the patched client jar and load it with `Library.loadNative(MetalBackend.class, "com.mojang.renderpearl.backend.metal", "mcmetal", true)`; strip Mojang's jar signature (`META-INF/MOJANGCS.SF/.RSA` and per-entry digests) [R08].
- **Immediate vs recorded:**

| Immediate native call (at API call time) | Recorded into the command stream (decoded at flush/submit) | Pure Java (no native call) |
|---|---|---|
| `createBuffer/Texture/TextureView/Sampler`, `createTimestampQueryPool`, `compilePipeline` (worker), surface create/configure, release batches | Every `CommandEncoderBackend` and `RenderPassBackend` method except those on the right; the present pass (drawable acquired during decode) | `GpuBuffer.map` (pointer arithmetic on a cached `contents` address), fence polling, query availability checks, `getDeviceInfo`, `transientMemory()` allocations, `writeToBuffer` source copy into the upload ring |

- **Stream format:** 16-byte-aligned, little-endian records `{u16 op; u16 size; u32 a; u64 b; …}` in an off-heap segment written with `MemoryUtil.memPut*`/`MemorySegment.set`. Variable payloads (push constants, debug labels, multi-draw arrays) are copied **inline at call time** — callers free their buffers immediately (`B3D/systems/RenderSystem.java:462`, `MC/renderer/ProjectionMatrixBuffer.java:46-50`). Resources are referenced by native handle, `gpuAddress` (buffers) or `gpuResourceID` (textures, samplers) so the decoder needs no lookups. Keep one stream buffer per frame in flight.
- **Look-ahead is a feature.** Because a whole pass (and the commands after it) is recorded before encoding, the decoder can fold clears into load actions, merge passes, choose store actions, hoist uploads, and build indirect command buffers before the render encoder starts (§9.1).

### 1.8 Implementation phases [D]

| Phase | Scope | Exit criterion |
|---|---|---|
| M0 | `MetalBackend`, `MetalDevice` with `DeviceInfo`, Shared buffers, Private textures, samplers, fences, surface + present pass clearing to a color | Window shows a solid color at 120 Hz FIFO; shutdown is clean. |
| M1 | SPIR-V→MSL, PSOs, full stream decoder on the **classic** API (`MTLCommandQueue`, automatic hazard tracking, retained references), all `CommandEncoderBackend`/`RenderPassBackend` methods, MDI as a loop of indirect draws, fan emulation, deferred release | Bit-for-bit-plausible frames vs Vulkan screenshots; `MTL_DEBUG_LAYER=1` clean. |
| M2 | Performance core: binding dedupe, clear folding, store actions, batched blits, `writeToBuffer` fast path, transient ring, pipeline caches (binary archives) | CPU encode ≪ 0.5 ms/frame at RD 12. |
| M3 | **Metal 4 decoder** (`MTL4CommandQueue`, allocators, argument tables, residency set, explicit barriers, `MTL4CounterHeap`), unretained lifetimes; keep the classic decoder selectable as a correctness oracle | Same images as M1; lower CPU. |
| M4 | Pass merging, upload hoisting, ICB terrain, MetalFX render scale, game-side patches (§9) | FPS targets. |

---

## 2. Backend contract checklist (every method)

Each entry gives: **when/where it is called**, **semantics and units**, **what the frontend already checked**, and **the Metal implementation** (classic API first; Metal 4 differences marked "MTL4").

### 2.1 `GpuBackend` (`RP/api/device/GpuBackend.java:5-15`) [S]

| Method | Contract | Metal implementation |
|---|---|---|
| `String getName()` | Logs and the startup error text (`MC/Minecraft.java:501-502`). | `"Metal"`. |
| `void loadLibrary() throws BackendCreationException` | Called before `createDevice` in the backend loop (`MC/Minecraft.java:488`). Idempotent; cache a failure and rethrow it on later calls (GL `RP/backend/opengl/GlBackend.java:27-49`, Vulkan `RP/backend/vulkan/VulkanBackend.java:58-87`). Only `BackendCreationException` makes the game fall back to the next backend. | `Library.loadNative(...)` the shim; resolve FFM handles; check `mcmtl_abi_version()`; `MTLCreateSystemDefaultDevice() != nil`; macOS ≥ 26 and `supportsFamily:MTLGPUFamilyMetal4` (needed only for the MTL4 decoder; the classic decoder needs Apple7+). Map `UnsatisfiedLinkError` and probe failures to `BackendCreationException(msg, Reason.PLATFORM_ERROR)` (there are no Metal reasons in `RP/api/device/BackendCreationException.java:27-38`; `OTHER` is also acceptable). |
| `void unloadLibrary()` | Called after a failed `createDevice` when the library loaded (`MC/Minecraft.java:507-509`) and at shutdown (`B3D/systems/RenderSystem.java:256-261`). | No-op (the dylib stays mapped). |
| `long createWindow(@Nullable String title, int w, int h, long flags)` | `flags` is always `8224` (`B3D/platform/Window.java:164-165`); `w/h` are **points**. Return 0 on failure (the caller throws with `SDL_GetError`, `:166-168`). | `SDLVideo.SDL_CreateWindow(title, w, h, flags \| SDLVideo.SDL_WINDOW_METAL /*0x20000000L*/)`. |
| `GpuDevice createDevice(GpuDebugOptions)` | Must return `new FrontendGpuDevice(new MetalDevice(...))`; throw `BackendCreationException` for any failure. `GpuDebugOptions(logLevel, synchronousLogs, useLabels, useValidationLayers)` (`RP/api/device/GpuDebugOptions.java:3`), fed from `MC/Minecraft.java:491-496`. | `useLabels` (`--renderDebugLabels`) enables labels and debug groups. `useValidationLayers` (`--vulkanValidation`) cannot enable `MTL_DEBUG_LAYER` at runtime (it is read at device creation from the environment); document launching with `MTL_DEBUG_LAYER=1` instead, and enable `MTLCommandBufferDescriptor.errorOptions = EncoderExecutionStatus`. |

### 2.2 `GpuDeviceBackend` (`RP/backend/api/GpuDeviceBackend.java:19-49`) [S]

Constructor obligations: §1.6 step 2. All methods on the render thread except `compilePipeline` (workers) and `getDeviceInfo` (any thread).

| Method | Contract (frontend checks in parentheses) | Metal implementation |
|---|---|---|
| `GpuSurfaceBackend createSurface(long window, BooleanSupplier isIconified)` | Called once, after the window exists (`MC/Minecraft.java:544`). | §6.2. |
| `CommandEncoderBackend createCommandEncoder()` | Called **once** by the `FrontendGpuDevice` constructor (`RP/frontend/FrontendGpuDevice.java:50`). | Return the singleton created in the device constructor (as GL `GlDevice.java:193-195` and Vulkan `VulkanDevice.java:186-188` do). |
| `GpuSampler createSampler(AddressMode u, AddressMode v, FilterMode min, FilterMode mag, int maxAnisotropy, OptionalDouble maxLod)` | (`1 ≤ maxAnisotropy ≤ limits.maxAnisotropy`, `RP/frontend/FrontendGpuDevice.java:65-79`). 32 created at init plus the terrain sampler, which is recreated mid-frame on option changes (`MC/renderer/LevelRenderer.java:425-436`). | `MTLSamplerDescriptor`: `s/tAddressMode` REPEAT→`Repeat`, CLAMP_TO_EDGE→`ClampToEdge`, `rAddressMode = Repeat`; `min/magFilter` NEAREST→`Nearest`, LINEAR→`Linear`; `mipFilter = (maxLod.isPresent() && maxLod ≤ 0.25) ? NotMipmapped : Linear` (GL always uses a linear mip filter, `RP/backend/opengl/GlSampler.java:40-58`; Vulkan uses `mipmapMode LINEAR iff maxLod.orElse(1000) > 0.25`, `RP/backend/vulkan/VulkanGpuSampler.java:45-51`); `lodMinClamp = 0`, `lodMaxClamp = maxLod.orElse(FLT_MAX)` (relative to the view's base level); `maxAnisotropy`; `normalizedCoordinates = YES`; no compare; `supportArgumentBuffers = YES` (MTL4 argument tables). Deduplicate `MTLSamplerState` by key with refcounts; each call still returns a distinct `MetalSampler` whose getters return the creation parameters and whose `close()` defers the release. |
| `GpuTexture createTexture(@Nullable String label, int usage, GpuFormat fmt, int w, int h, int depthOrLayers, int mips)` | (`1 ≤ mips ≤ floor(log2(max(w,h)))+1`; `depthOrLayers ≥ 1`; cube ⇒ usage bit 16, square, exactly 6 layers; otherwise 1 layer — `RP/frontend/FrontendGpuDevice.java:108-149`.) **Not validated:** `w,h > 0`, `≤ maxTextureSize`, format support, usage ≠ 0. Label is `null` unless debugging. | `MTLTextureDescriptor`: `textureType = (usage & 16) ? Cube : Type2D`, `pixelFormat` per §4, `mipmapLevelCount = mips`, `arrayLength = 1`, `sampleCount = 1`, `storageMode = Private`, `usage = (TEXTURE_BINDING(4) or COPY_SRC(2) ? ShaderRead : 0) \| (RENDER_ATTACHMENT(8) ? RenderTarget : 0)` (COPY_SRC gets ShaderRead because the present pass samples any blit source and the frontend only guarantees COPY_SRC, [R05]). Never add `ShaderWrite`/`PixelFormatView` (they can disable lossless compression). Throw `IllegalArgumentException` for formats with no Metal pixel format (all 3-component formats, D24S8; GL does the same, `RP/backend/opengl/GlDevice.java:242-271`) and for sizes `<1` or `> 16384`. `nil` → `GpuOutOfMemoryException` (`MainTarget` retries smaller sizes, `B3D/pipeline/MainTarget.java:74-88`). Pass `label == null ? "" : label` to `BaseGpuTexture` (`getLabel()` is non-null API). SHOULD zero-fill new textures that lack RENDER_ATTACHMENT with one blit from a zero buffer (atlas gaps are never cleared, `MC/renderer/texture/TextureAtlas.java:282-293`; GL drivers zero-fill in practice). MTL4: add to the residency set. |
| `GpuTextureView createTextureView(GpuTexture t, int baseMip, int mipLevels)` | (texture open; `baseMip ≥ 0`; `baseMip + mipLevels ≤ mips`, `RP/frontend/FrontendGpuDevice.java:157-175`.) `mipLevels ≥ 1` is **not** checked. | Throw IAE if `mipLevels < 1`. Full range → wrap the parent `MTLTexture` (no new object). Partial → `newTextureViewWithPixelFormat:sameFormat textureType:sameType levels:NSMakeRange(baseMip, mipLevels) slices:NSMakeRange(0, cube ? 6 : 1)` (or `MTLTextureViewPool` under MTL4, [R08]). Refcount like Vulkan: the texture counts itself plus one per view; the native texture is released (deferred) only when the texture and all views are closed (`RP/backend/vulkan/VulkanGpuTexture.java:94,103-128`) — `MainTarget` closes the texture before its view and `TextureAtlas` closes a texture repeatedly (`B3D/pipeline/MainTarget.java:37-52`, `MC/renderer/texture/TextureAtlas.java:194-199`). Extend `BaseGpuTextureView` (its `getWidth(m)` adds `baseMipLevel`, which the frontend relies on, `RP/backend/common/BaseGpuTextureView.java:33-40`). |
| `GpuBuffer createBuffer(@Nullable Supplier<String> label, int usage, long size)` | (`size > 0`, `RP/frontend/FrontendGpuDevice.java:178-184`.) Contents undefined. | `newBufferWithLength:size options:Shared \| cacheMode` where `cacheMode = (usage & MAP_WRITE) && !(usage & MAP_READ) ? WriteCombined : DefaultCache` (WC is an optimization to benchmark; **never** WC for MAP_READ readback buffers). Classic: default (tracked) hazard mode for M1; `Untracked` only with the fence chain of §2.3.11. Cache `contents` and `gpuAddress`. `nil` → `GpuOutOfMemoryException`. Report `usage()` exactly as given (do not OR in COPY_DST as Vulkan does). Extend `BaseGpuBuffer` (the frontend casts to it, `RP/frontend/FrontendRenderPass.java:363,373,519,536,554`). SHOULD pool `MTLBuffer`s by size class after their deferred release completes: `StagedVertexBuffer` closes and recreates pooled buffers every frame when the GPU lags (`MC/renderer/StagedVertexBuffer.java:350-363`). Evaluate `label` only when debugging. MTL4: residency add. |
| `GpuBuffer createBuffer(@Nullable Supplier<String> label, int usage, ByteBuffer data)` | (`data.hasRemaining()`, `:187-193`.) Size = `data.remaining()`, contents = data. **May be called while a render pass is open** (e.g. `AutoStorageIndexBuffer` growth inside the Sky pass, `MC/renderer/SkyRenderer.java:219` → `B3D/systems/RenderSystem.java:439-469`) [S]. Caller frees `data` immediately. | Allocate Shared and `memcpy` synchronously from `data.position()` (no GPU copy, no encoder interruption; a fresh or fully retired pooled buffer cannot be referenced by in-flight work). |
| `List<String> getLastDebugMessages()` | Shown in crash reports (`MC/Minecraft.java:2529-2535`). | Bounded ring of recent command-buffer errors / validation messages; empty list otherwise. |
| `boolean isDebuggingEnabled()` | Gates label `Supplier` evaluation in `FrontendGpuDevice.createTexture` (`:82-92`). | `debugOptions.useLabels()`. |
| `BackendRenderPipeline.Pending compilePipeline(CreateInfo)` | **Worker threads, concurrent** (`RP/frontend/shaders/PipelineBuilder.java:377-411`). The returned `Pending.finishCompile()` runs on the reload executor or on the render thread (`MC/renderer/ShaderManager.java:106`, `B3D/pipeline/PipelineCache.java:33`). SPIR-V modules are freed right after `finishCompile` (`PipelineBuilder.java:451`). Failure = `Pending.NULL` or `finishCompile()` returning `null`; **never throw** (an exception fails the whole reload future and leaks modules). | Do everything here: SPIR-V→MSL (§3), `newLibraryWithSource` (MSL 3.2) or `MTL4Compiler`, `MTLRenderPipelineState` for the variants of §2.5, depth-stencil state lookup. Return `() -> pipeline`. Dedupe libraries by SHA-256 of the patched SPIR-V + stage + options version with a `ConcurrentHashMap<Key, CompletableFuture<…>>` (195 static pipelines contain only 125 unique programs, [R02]). MUST NOT need the render thread (lazy compiles block it in `.join()`, possibly mid-pass) and MUST return `Pending.NULL` after `close()`. |
| `void close()` | `FrontendGpuDevice.close()` → `pipelineBuilder.close()` → `backend.close()` (`RP/frontend/FrontendGpuDevice.java:213-216`). Called after `windowSurface.close()`, no final submit. | §1.6 step 7. Note the frontend never closes the Tracy query pool. |
| `GpuQueryPool createTimestampQueryPool(int size)` | Tracy creates 1024 at device construction; `TimerQuery` creates 6. | §2.13. |
| `long getTimestampCalibrationOffset()` | `hostNanos − deviceNanos`, host = `System.nanoTime()`; Tracy calls it at construction and every 5 s (`RP/frontend/TracyGpuProfiler.java:28,48-51`). | With the §2.13 convention (query values already in ns, `timestampPeriod = 1.0`): `[device sampleTimestamps:&cpu gpuTimestamp:&gpu]` returns **nanoseconds** for both on this M5 [P]; return `System.nanoTime() − gpu` (bracket the native call with two `nanoTime` reads and average). |
| `DeviceInfo getDeviceInfo()` | Called on hot paths with no caching by the frontend (`FrontendRenderPass.java:151` per uniform; `FrontendCommandEncoder.java:105`) and from worker threads (`RP/frontend/shaders/GlslCompiler.java:85`). | Return a `final` immutable record built in the constructor (§7). |

### 2.3 `CommandEncoderBackend` (`RP/backend/api/CommandEncoderBackend.java:13-70`) [S]

All methods are called on the render thread with **no render pass open** (frontend-enforced) except `submit()` (not checked) and `writeTimestamp()` (not checked).

| Method | Semantics and units | Frontend checked | Metal implementation |
|---|---|---|---|
| `void submit()` | End of frame. Once per frame (`MC/Minecraft.java:1337`). Frontend then runs `profiler.endFrame()` (reads query values). | Nothing. | (1) If a backend render pass is open → `IllegalStateException`. (2) Materialize leftover pending clears. (3) Flush the stream: decode/encode remaining passes, the PRESENT op (§6.6), signal `pendingSeq`, commit; `frameSeq[S] = pendingSeq++`; `S++`. (4) Wait until the frame committed *before* this one has completed (`completedSeq ≥ frameSeq[S−1]` where S is the frame just committed), 5 s cap. (5) Drain the deferred FIFO (releases, readback callbacks in FIFO order, transient-block recycles, residency removals), refresh query availability, MTL4: reset the allocator of the reused slot and commit the residency set. (6) Surface any command-buffer error as `GpuDeviceLossException` (device removed / page fault / timeout) or `IllegalStateException`. |
| `TransientMemory transientMemory()` | Per-encoder transient allocator; allocations valid until the next `submit()`. | Nothing. | Return the singleton `MetalTransientMemory` (§2.9). |
| `RenderPassBackend createRenderPass(RenderPassDescriptor d)` | Attachments are views; render to `view.texture()` at mip `view.baseMipLevel()`, layer 0. Color clear = `Optional<Vector4fc>` RGBA float, depth clear = `OptionalDouble`; empty = **Load**. Store is always Store. `null` color entry = unused slot. Render area bounds the clear and the initial and "disabled" scissor, **not** the viewport (`RP/backend/vulkan/VulkanRenderPass.java:80-88,181-188`). Viewport = full attachment, depth 0..1. Default render area = last non-null color attachment's size (`RP/api/commands/RenderPassDescriptor.java:52-71`). | No pass open; ≤ `maxColorAttachments`; ≥1 attachment; first color attachment asserted non-null (`FrontendCommandEncoder.java:121-124`; a leading unused slot NPEs); each attachment open, RENDER_ATTACHMENT, 1 layer, equal size; render area in bounds. Formats unchecked. | Record `BEGIN_PASS` with `(texture handle, level = baseMipLevel)` per attachment, clear values, render area. At decode: `MTLRenderPassDescriptor` (`texture` = parent, `.level` = base mip, `.slice` = 0), `loadAction = clear ? Clear : Load` **only if** the render area covers the attachment; otherwise Load plus a scissored clear-quad draw (cached clear pipeline per format signature writing color and `[[depth(any)]]`). Initial scissor = render area when partial. `storeAction = Store` (or `Unknown` resolved at encoder end, §9.1). Unused slot → texture `nil`. Pending clears on these attachments fold into `loadAction = Clear` (§9.1). Return a new `MetalRenderPass` (pool it). |
| `void submitRenderPass()` | Ends the pass. Called from `RenderPass.close()` → `onFinish` (`FrontendCommandEncoder.java:218-228`). If debug groups are unbalanced the frontend throws **before** calling this, leaving its flag stuck (`FrontendRenderPass.java:493-502`). | — | Record `END_PASS` (`endEncoding` at decode, possibly deferred for merging). Mark the pass object closed; later calls on it MUST throw `IllegalStateException` (the frontend still forwards `setPipeline`/`setUniform`/`setVertexBuffer`/`setIndexBuffer`/scissor/`pushConstants`/`writeTimestamp` after close). |
| `void clearColorTexture(GpuTexture, Vector4fc rgba)` | Clears **all mips**, layer 0 (Vulkan `levelCount = mips`, `VulkanCommandEncoder.java:427-436`). | Color aspect, open, RENDER_ATTACHMENT + COPY_DST, 1 layer (`:802-822`). | Record `PENDING_CLEAR(tex, allMips, color)`. Materialize as one render pass per mip with `loadAction = Clear, store = Store` and no draws, unless folded (§9.1). |
| `void clearColorAndDepthTextures(GpuTexture c, Vector4fc rgba, GpuTexture d, double depth)` | Both textures, all mips; main target cleared to depth **0.0** every frame (`MC/renderer/GameRenderer.java:479`, `MC/renderer/LevelRenderer.java:254`). | Both verified; equal mip counts (`:240-253`). | Two pending clears (fold into one pass when the next pass attaches both). Depth clear value cast to float. |
| `void clearColorAndDepthTextures(c, rgba, d, depth, x, y, w, h, mip)` | Region clear of mip `mip` of both textures; `y` is a **memory row** (`MC/gui/render/GuiItemAtlas.java:101-111`). | Region inside color mip, `w,h > 0` (`:255-279,846-876`); depth size unchecked. | If the region covers the whole mip → pending clear. Otherwise a pass on `(c, mip)`+`(d, mip)` with Load/Store, `setScissorRect(x, y, w, h)` (no flip), and a full-viewport triangle with the cached clear pipeline (color = rgba, `[[depth(any)]] = depth`, depth compare Always, write on). |
| `void clearDepthTexture(GpuTexture, double)` | All mips. Used before the hand and GUI passes (`MC/renderer/GameRenderer.java:507,690`, `MC/gui/render/GuiRenderer.java:200`). | Depth aspect, open, RENDER_ATTACHMENT + COPY_DST, 1 layer. | Pending clear (fold into the next pass's depth `loadAction = Clear`). |
| `void writeToBuffer(GpuBufferSlice dst, ByteBuffer data)` | Copies `data.remaining()` bytes from `data.position()` to `dst.offset`; must not modify `data`; caller may free it on return. **Ordered in the stream**: earlier draws see old contents, later ones the new. The game rewrites the same buffers mid-frame and every frame (`MC/renderer/ProjectionMatrixBuffer.java:28-50`, `MC/renderer/GlobalSettingsUniform.java:41`, `MC/renderer/DynamicGpuDataStorageNonMapped.java:73-122`, `MC/renderer/WeatherEffectRenderer.java:119`) and reuses freed `UberGpuBuffer` ranges in the same frame (`B3D/vertex/UberGpuBuffer.java:59-109`). | Not in pass, open, COPY_DST, `remaining ≤ dst.length`, in bounds (`:292-326`). | **Fast path:** if the destination `MetalBuffer` is Shared, not referenced by anything recorded since the last flush (`lastRecordedSeq < pendingSeq`), and `lastRecordedSeq ≤ completedSeq` → `memcpy` straight into `contents + offset` (no GPU op). **Otherwise:** `memcpy` into a 16-byte-aligned upload-ring allocation and record `COPY_BUFFER(ring, dst)` executed in a batched blit encoder. Never memcpy blindly. (Optional buffer renaming for small whole-buffer UBO writes, §9.1.) |
| `void copyToBuffer(GpuBufferSlice src, GpuBufferSlice dst)` | `src.length` bytes; equal lengths. Overlap not checked. | Both open, COPY_SRC/COPY_DST, equal length, bounds (`:329-383`). | Record `COPY_BUFFER`; decode as `copyFromBuffer:sourceOffset:toBuffer:destinationOffset:size:` in the shared blit encoder (MTL4: on `MTL4ComputeCommandEncoder`). Buffer-to-buffer copies with 1-byte offsets/sizes pass validation on M5 [P]. If `src.buffer == dst.buffer` and ranges overlap, stage through the ring. |
| `void writeToTexture(GpuTexture dst, ByteBuffer src, int mip, int layer, int x, int y, int w, int h)` | `src` holds `w*h*blockSize` **tightly packed** bytes from `position()`; `layer` = cube face (+X,−X,+Y,−Y,+Z,−Z, same order in GL/Vulkan/Metal). | Not in pass; mip in range; `w*h*bs ≤ remaining`; rect fits; open; COPY_DST; `layer < depthOrLayers` (`:456-518`). NativeImage overloads require UNORM_8 with matching component count (`:399-453`). | Copy into a ring allocation aligned to 16, record `COPY_BUFFER_TO_TEXTURE`: `copyFromBuffer:ring sourceOffset:o sourceBytesPerRow:w*bs sourceBytesPerImage:w*h*bs sourceSize:{w,h,1} toTexture:t destinationSlice:layer destinationLevel:mip destinationOrigin:{x,y,0}`. Never `replaceRegion` (not stream-ordered; GL's equivalent drained the GPU for 30 ms, [R06]). |
| `void copyBufferToTexture(GpuBufferSlice src, int sx, int sy, int sW, int sH, GpuTexture dst, int dx, int dy, int cw, int ch, int mip, int layer)` | Source is an `sW×sH` image, tightly packed (row pitch `sW*bs`) at `src.offset`; copy the `cw×ch` sub-rect at `(sx,sy)` to `(dx,dy)` of `mip`/`layer`. First texel byte = `src.offset + (sy*sW + sx)*bs` (`VulkanCommandEncoder.java:706-722`). | Bounds, usages, layer; byte check `sW*ch*bs ≤ src.length` ignores sx/sy (`:557`). | Validate `src.offset + ((sy+ch−1)*sW + sx + cw)*bs ≤ src.offset + src.length`. `sourceOffset` and `sourceBytesPerRow` **must be multiples of the pixel size** (validation asserts on RGBA8 offset 2 or row pitch 14; R8 accepts any) [P] — hence transient allocations are always ≥16-aligned (`CubeMapTexture` and `SpriteContents` request alignment 1, `MC/renderer/texture/CubeMapTexture.java:65`, `MC/renderer/texture/SpriteContents.java:286`). Decode: `copyFromBuffer:… sourceBytesPerRow:sW*bs sourceBytesPerImage:sW*ch*bs sourceSize:{cw,ch,1} toTexture:… destinationSlice:layer destinationLevel:mip destinationOrigin:{dx,dy,0}`. |
| `void copyTextureToBuffer(GpuTexture, GpuBuffer, long offset, Runnable cb, int mip)` | Whole mip; tightly packed rows (`w*bs`) in **memory-row order** (row 0 = bottom of a rendered image; `Screenshot` flips in Java, `MC/Screenshot.java:103`). **Callback runs on the render thread after the GPU finished.** | **Only the not-in-pass check** (`:618-624`). | Validate as the 9-arg form (`w*h*bs + offset ≤ size`, COPY_SRC/COPY_DST, 1 layer). Decode `copyFromTexture:sourceSlice:0 sourceLevel:mip sourceOrigin:{0,0,0} sourceSize:{w,h,1} toBuffer:… destinationOffset:offset destinationBytesPerRow:w*bs destinationBytesPerImage:w*h*bs` (Depth32Float OK with no options; combined depth/stencil needs `DepthFromDepthStencil`). Enqueue `(pendingSeq, cb)` in the deferred FIFO. Never run `cb` on a Metal thread (callbacks `map()`/`close()` buffers, `MC/Screenshot.java:94-130`). |
| `void copyTextureToBuffer(…, int mip, int x, int y, int w, int h)` | Sub-rect variant. | mip, `w*h*bs + offset ≤ size`, COPY_SRC, COPY_DST, rect, open, 1 layer (`:627-699`). | Same with origin `{x,y}`. |
| `void copyTextureToTexture(GpuTexture src, GpuTexture dst, int mip, int dx, int dy, int sx, int sy, int w, int h)` | Same mip on both, layer 0; used for D32→D32 and RGBA8→RGBA8 (`B3D/pipeline/RenderTarget.java:62-86`). | Bounds, COPY_SRC/COPY_DST, 1 layer; **formats not checked** (`:702-782`). | Blit `copyFromTexture:src sourceSlice:0 sourceLevel:mip sourceOrigin:{sx,sy,0} sourceSize:{w,h,1} toTexture:dst destinationSlice:0 destinationLevel:mip destinationOrigin:{dx,dy,0}`. Different formats → nearest-sampling draw if `dst` has RenderTarget, else IAE. |
| `GpuFence createFence()` | Captures the submit being recorded. ~5–10 per frame. | Not in pass (`:785-791`). | `new MetalFence(pendingSeq)` (§1.5). |
| `void writeTimestamp(GpuQueryPool pool, int index)` | Raw device ticks scaled by `DeviceInfo.timestampPeriod` by consumers. Users: Tracy around every pass (only with Tracy), `TimerQuery` at frame start and after the blit (only with the GPU-utilization overlay/metrics). | `0 ≤ index ≤ size` (off by one); no pass check (`:793-800`). | Bounds-check `index < size`. Record `TIMESTAMP`. MTL4: `[cb writeTimestampIntoHeap:heap atIndex:i]` at that point in the stream (a Relaxed timestamp inside an *empty* compute encoder resolved to 0 [P], so do not open an encoder just for a timestamp). Classic: attach to the adjacent pass's `startOfVertexSampleIndex` / `endOfFragmentSampleIndex`, or an empty blit pass with `sampleBufferAttachments` (Apple GPUs sample only at stage boundaries: `AtStageBoundary=1, AtDraw/Blit/DispatchBoundary=0` [P]). Set `writtenSeq[index] = pendingSeq`. A TIMESTAMP between two passes forbids merging them. |

**2.3.11 Hazards (normative).** RenderPearl's API has no storage writes; GPU writers are only attachments and copy destinations. Required orderings: transfer write → later read; later transfer write → after earlier reads (WAR: Projection/Globals rewrites, `UberGpuBuffer` range reuse); attachment write → later sampling/copy; readback copy → CPU callback. Implement by one of:
- **Classic, tracked (M1 default):** default hazard tracking on one queue gives this automatically.
- **Classic, untracked (M2 option):** `MTLResourceHazardTrackingModeUntracked` everywhere plus one `MTLFence` chain: every encoder `waitForFence:prev beforeStages:Vertex` (blit: `waitForFence:`) at start and `updateFence:next afterStages:Fragment` at end — exactly Vulkan's "everything serialized" model. Pair with `commandBufferWithUnretainedReferences` and the deferred FIFO.
- **MTL4 (M3):** `barrierAfterQueueStages:MTLStageAll beforeStages:<encoder stages> visibilityOptions:None` at the start of every encoder (`$MTL/MTL4CommandEncoder.h:70`, verified present in the SDK [S]); no barriers inside a render pass. Present pass after the GUI pass: fragment→fragment barrier.

### 2.4 `RenderPassBackend` (`RP/backend/api/RenderPassBackend.java:13-49`) [S]

| Method | Semantics | Frontend checked | Metal implementation |
|---|---|---|---|
| `pushDebugGroup(Supplier<String>)` / `popDebugGroup()` | ~300 pairs per frame (`MC/renderer/rendertype/PreparedRenderType.java:38`, `MC/renderer/chunk/ChunkSectionsToRender.java:99`). | Not closed; balanced. | Only when `useLabels`: evaluate the supplier, record inline string → `pushDebugGroup:`/`popDebugGroup`. Otherwise do nothing and **never call `get()`**. |
| `setPipeline(BackendRenderPipeline)` | Bindings are pipeline-relative; afterwards the frontend re-sends every uniform the pass holds whose name the new pipeline uses (`FrontendRenderPass.java:123-126`) and resets `constantsPushed`. Uniforms persist across pipelines within a pass, not across passes (every pass calls `RenderSystem.bindDefaultUniforms`, `B3D/systems/RenderSystem.java:367-387`). | Must be a `FrontendRenderPipeline`; color target count == attachment count; per non-null attachment equal format. Depth not checked; no closed check. | Java: remember the pipeline (for its uniform-slot table); clear the per-index "set" mask (slots not re-sent are unset in this pass). Record `SET_PIPELINE(handle)`. Decode: pick the PSO variant for this pass's signature (§2.5), then `setRenderPipelineState:`, `setDepthStencilState:`, `setCullMode:(cull ? Back : None)`, `setFrontFacingWinding:Clockwise`, `setTriangleFillMode:(WIREFRAME ? Lines : Fill)`, `setDepthBias:depthBiasConstant slopeScale:depthBiasScaleFactor clamp:0` — each only if changed. |
| `setUniform(int index, @Nullable Object value)` | `index` = binding = position in `CreateInfo.uniforms()`. Dispatch on the pipeline's declared `UniformType`, not `instanceof`: UNIFORM_BUFFER → `GpuBufferSlice`; TEXEL_BUFFER → `GpuBufferSlice` of the **whole buffer**; COMBINED_IMAGE_SAMPLER → `TextureViewAndSampler(view, sampler)`; `null` = unset. | Offset alignment for every `GpuBufferSlice` (`:150-157`); unknown names dropped (`:166-170`). Kind/usage/closed checks are STRICT-only. | O(1), no native call: Java drops exact repeats (compare `(buffer, offset)` / `(view, sampler)` against a per-index cache), then records `SET_UNIFORM(index, …)` with the pre-resolved Metal slots and stage mask from the pipeline's table (§3). The native side filters against per-stage, per-slot shadow state and uses `set{Vertex,Fragment}BufferOffset:atIndex:` when only the offset changed. MTL4: `setAddress:(gpuAddress + offset) atIndex:` / `setTexture:` / `setSamplerState:` on the per-stage argument table. Texel buffer → cached `MTLTextureTypeTextureBuffer` view per (buffer, format) (§3.7). |
| `pushConstants(ByteBuffer)` | Never called by vanilla. | Pipeline bound; `remaining() ≥ pushConstantSize` (`:175-186`). | Copy `pushConstantsSize` bytes inline. Classic: `setVertexBytes`/`setFragmentBytes` at buffer 14 for stages that use the block. MTL4: ring allocation + `setAddress` at 14. |
| `enableScissor(x, y, w, h)` | Memory-row coordinates; leaks across draws (`PreparedRenderType` never disables it, `:41-43`). | `w,h > 0`, inside render area (`:189-204`). | `setScissorRect:{x, y, w, h}` unchanged (no flip). |
| `disableScissor()` | Restores the **render area**, not "off" (`VulkanRenderPass.java:182-188`). | — | `setScissorRect:` render area. |
| `setVertexBuffer(int slot, @Nullable GpuBufferSlice)` | Slot 0..15; stride/step from the pipeline. **Null = keep the previous binding** (Vulkan no-op, `VulkanRenderPass.java:190-215`); clouds pass null (`MC/renderer/CloudRenderer.java:239`). | Slot range; open; USAGE_VERTEX (`:212-227`). | Record `(buffer, offset)` at Metal buffer index `30 − slot`; offset-only change → `setVertexBufferOffset:`. MTL4: `setAddress` at `30 − slot`. |
| `setIndexBuffer(GpuBuffer, IndexType)` | **Whole buffer at offset 0**; draws select the start with `firstIndex` (`VulkanRenderPass.java:217-224`). Game code folds slice offsets into `firstIndex` (`ChunkSectionsToRender.java:143`). | Nothing in release. | Remember `(MTLBuffer, UInt16/UInt32)`; passed with each draw. |
| `drawIndexed(indexCount, instanceCount, firstIndex, vertexOffset, firstInstance)` | `firstIndex` in indices; `vertexOffset` signed base vertex (terrain up to ~4.8M); `firstInstance` = base instance. | Not closed; pipeline bound; `firstInstance≠0` needs `nonZeroFirstInstance`. | `drawIndexedPrimitives:type indexCount:n indexType:t indexBuffer:ib indexBufferOffset:firstIndex*bytes instanceCount:i baseVertex:vo baseInstance:fi`. The offset only needs to be a multiple of the index size (`$MTL/MTLRenderCommandEncoder.h:729`; odd `firstIndex` with UInt16 passes validation) [S][P]. Per-instance attributes are fetched at `baseInstance + instance` [P]. MTL4: index buffer = `gpuAddress + firstIndex*bytes`, length = remaining bytes. Null index buffer → skip the draw (log once). Topology/fan handling §4.3. |
| `multiDrawIndexed(IntBuffer, instanceCount, firstInstance, drawCount)` (interleaved `{firstIndex, indexCount, vertexOffset}` ×n) | Never called by vanilla; requires `multiDrawDirectInterleaved`. | Feature gate, count limit, buffer size. | Report the feature `false` and throw `UnsupportedOperationException` (Vulkan's pattern for unsupported forms, `VulkanRenderPass.java:240-243`). (MAY implement as a native CPU loop over an inline copy.) |
| `multiDrawIndexed(PointerBuffer firstIndexOffsets /*bytes*/, IntBuffer counts, IntBuffer vertexOffsets, int n)` | Never called; requires `multiDrawDirectSeparate`. | Feature gate. | Same as above. |
| `drawIndexedIndirect(GpuBufferSlice cmds, int drawCount)` | `drawCount × {indexCount, instanceCount, firstIndex, int vertexOffset, firstInstance}`, stride **20**, tightly packed from `cmds.offset` — byte-identical to `MTLDrawIndexedPrimitivesIndirectArguments`. Terrain MDI only (`ChunkSectionsToRender.java:139-157`). | `drawIndirect`, `multiDrawIndirect` for n>1, INDIRECT usage, length ≥ 20n, offset % 4 (`:302-329`). | M1: loop `drawIndexedPrimitives:type indexType:t indexBuffer:ib indexBufferOffset:0 indirectBuffer:buf indirectBufferOffset:cmds.offset + 20*i` (offset multiple of 4 required, satisfied). Alternative: the records live in a CPU-written Shared ring (`MC/renderer/DynamicGpuData.java:113-134`) and are final at decode time, so the decoder MAY read them and issue direct draws. M4: indirect command buffer (§9.1, B2). Indirect draws also apply `baseInstance` to per-instance fetches [P]. |
| `draw(vertexCount, instanceCount, firstVertex, firstInstance)` | Non-indexed. Sky disc and sunrise are **triangle fans drawn with `draw(10/18,1,0,0)`** (`MC/renderer/SkyRenderer.java:167,181,304`) [S]. | As drawIndexed. | `drawPrimitives:type vertexStart:fv vertexCount:n instanceCount:i baseInstance:fi`; fans per §4.3. |
| `multiDraw(IntBuffer, …)` / `multiDraw(IntBuffer, IntBuffer, int)` | Never called. | Feature gates. | Throw `UnsupportedOperationException` (features false). |
| `drawIndirect(GpuBufferSlice, int)` | Stride 16 `{vertexCount, instanceCount, firstVertex, firstInstance}` = `MTLDrawPrimitivesIndirectArguments`. Never called. | Gates as indexed. | Loop of `drawPrimitives:indirectBuffer:indirectBufferOffset:+16*i`. |
| `writeTimestamp(GpuQueryPool, int)` | In-pass; never called by vanilla. | Off-by-one bound only (`FrontendRenderPass.java:95-101`). | Bounds-check. MTL4: `writeTimestampWithGranularity:Relaxed afterStage:MTLStageFragment intoHeap:atIndex:`. Classic: attach to this pass's `endOfFragmentSampleIndex`. |

**Draw-time obligations (release builds).** `validateDraw` checks only that a pipeline is bound (`FrontendRenderPass.java:504-509`) [S]. Before each draw the decoder MUST make every Metal slot the pipeline *uses* (per-stage used masks from SPIRV-Cross) hold something valid: unset or `null` UBOs → a 256-byte zero buffer; textures → a 1×1 dummy of the right type (2D or cube) plus a default sampler; texel buffer → a 16-byte zero `texture_buffer`; a missing vertex buffer for a slot the pipeline reads → skip the draw and log once (a dummy cannot be sized safely for arbitrary vertex counts). Vulkan throws "Missing uniform" at draw time (`VulkanRenderPass.java:296-311`); GL silently draws with stale bindings. Dummies are the robust choice; a debug flag MAY throw instead.

### 2.5 `BackendRenderPipeline` and `CreateInfo` (`RP/backend/api/BackendRenderPipeline.java:13-45`) [S]

`CreateInfo(name, shaders, vertexBuffers, attribBindings, uniforms, pushConstantsSize, @Nullable depthStencilState, polygonMode, cull, colorTargetStates, primitiveTopology)`:
- `shaders` = `[VERTEX, FRAGMENT]`, both always present, entry point `"main"`, module = `SpvModule` (§2.7).
- `vertexBuffers` = `VertexBuffer(bufferSlot, stride, stepRate)` per non-null vertex format; stride %4 == 0; `stepRate 0` = per-vertex, `>0` = per-instance divisor (vanilla only uses 1: `CHUNK_DATA_INSTANCED` at slot 1).
- `attribBindings` = `AttribBinding(bufferSlot, location, offset, GpuFormat)`, matched by element name to shader inputs; offsets are multiples of 4 in vanilla (Metal requires 4-byte attribute offsets; validate).
- `uniforms` = `UniformDescription(name, type, gpuFormat)`, **index == binding**.
- `colorTargetStates` = nullable entries, size = active count ≤ 8; `ColorTargetState(Optional<BlendFunction>, GpuFormat, writeMask R=1,G=2,B=4,A=8)`.
- No depth format and no sample count: Vulkan hard-codes D32 and builds a second "without depth" pipeline only when `depthStencilState == null` (`RP/backend/vulkan/VulkanRenderPipeline.java:321,336-354`).

| Item | Metal |
|---|---|
| `MTLRenderPipelineDescriptor` | `vertexFunction`/`fragmentFunction` = `main0` from per-stage libraries; `vertexDescriptor` (§3.6); `colorAttachments[i].pixelFormat` from the target's format (or `Invalid` for null targets); `blendingEnabled`, factors/ops (§4.2) — **per attachment** (GL's single blend function, `GlRenderPipeline.java:65-87`, is a GL limitation); `writeMask` bit-reversed (§4.2); `depthAttachmentPixelFormat` per variant; `rasterSampleCount = 1`; `inputPrimitiveTopology = Point` for POINTS (else Unspecified); label only when debugging. |
| Variants | Key = (color pixel formats per slot, depth format ∈ {Depth32Float, Invalid}). Precompile in `compilePipeline`: `Depth32Float` always, plus `Invalid` iff `depthStencilState == null` (mirrors Vulkan). Any other signature (a depth-state pipeline in a depth-less pass, which is undefined behaviour in Vulkan, or a null attachment against a non-null target) → compile lazily at decode time and cache. Keep the `MTLFunction`s and descriptor inputs alive for this; never keep the SPIR-V. |
| `MTLDepthStencilState` | From `DepthStencilState(compare, write, biasScale, biasConstant)`; `null` → `{Always, write NO}`. Only 5 distinct states in vanilla (GE+w, GE, EQ, ALWAYS+w, ALWAYS) — cache on the device [R02]. Bias is encoder state. |
| `isClosed()` / `close()` | `close()` is called on the render thread when a `PipelineCache` is cleared (reload, post-chain close, shutdown; `MC/renderer/ShaderManager.java:229-234`). Mark closed; release PSO variants through the deferred FIFO (a classic retained command buffer keeps PSOs alive, MTL4 and unretained buffers do not). |
| `Pending.finishCompile()` | Trivial: return the already built `MetalRenderPipeline` (or `null` on failure). |

### 2.6 `GpuSurfaceBackend` (`RP/backend/api/GpuSurfaceBackend.java:9-21`) [S]

`configure`, `isSuboptimal`, `acquireNextTexture`, `blitFromTexture`, `present`, `supportedPresentModes`, `close`. Full design in §6; summary:

| Method | Metal |
|---|---|
| `configure(Configuration(w, h, mode)) throws SurfaceException` | Validate `1 ≤ w,h ≤ 16384` else `SurfaceException`; in a `CATransaction` with actions disabled set `drawableSize = (w,h)`, `displaySyncEnabled = (mode == FIFO)`; store config; clear the suboptimal and eaten-exception state. No GPU wait. |
| `boolean isSuboptimal()` | Edge-triggered: true only after a nil drawable or a drawable whose size ≠ configured size; cleared by `configure`. Never permanently true (the game would reconfigure every frame, `MC/Minecraft.java:1254`). |
| `acquireNextTexture() throws SurfaceException` | Rethrow an eaten exception; iconified → `SurfaceException("Cannot acquire minimized window")` (GL parity, `RP/backend/opengl/GlSurface.java:42-46`); fully occluded (`SDL_GetWindowFlags & SDL_WINDOW_OCCLUDED`) → mark the frame "dropped" silently. Do **not** call `nextDrawable`. |
| `blitFromTexture(CommandEncoderBackend, GpuTextureView)` | Record `PRESENT(view)` (and optionally flush the frame so far). The drawable is acquired during decode (§6.6). Cannot throw `SurfaceException`: a nil drawable becomes the eaten exception and the frame is dropped. |
| `present()` | Must not throw (a throw leaves the frontend "acquired" forever and freezes the frame loop, `MC/Minecraft.java:1246`). Classic: release the retained drawable (presentation was registered with `presentDrawable:` before commit). MTL4: `[drawable present]`, then release. |
| `Collection<PresentMode> supportedPresentModes()` | Cached `Collections.unmodifiableSet(EnumSet.of(FIFO, IMMEDIATE))`. |
| `close()` | Wait for the last frame, release the drawable, `SDL_Metal_DestroyView(view)` (before `SDL_DestroyWindow`), MTL4: `removeResidencySet:layer.residencySet`. |

### 2.7 `SpvModule` usage (`RP/backend/api/SpvModule.java:9-80`) [S]

- Use **only** `spv()` (native-order `ByteBuffer`; word count = `remaining()/4`) and `type()`.
- The bytes are already **patched**: every descriptor is `DescriptorSet 0`, `Binding = uniform index` (`RP/frontend/shaders/SPIRVModule.java:247-292`). The frontend's cached reflection IR is stale for bindings, so do not use `reflect()`/`getReflectionInfoIfAvailable()` for binding info; parse `spv()` into a fresh spvc context (as GL does, `RP/backend/opengl/GlPipelineRecompiler.java:47-58`).
- Use it only inside `compilePipeline` (the frontend closes the modules asynchronously right after `finishCompile`, `PipelineBuilder.java:451`).
- Identical (vs, fs, defines) programs produce byte-identical patched SPIR-V, so a SHA-256 of `spv()` is a valid cache key for MSL, libraries and functions [R02].

### 2.8 `GpuBuffer` (`RP/api/buffers/GpuBuffer.java:9-51`, `GpuBufferSlice.java:6-35`) [S]

| Member | Contract | Metal |
|---|---|---|
| `size()`, `usage()` | From `BaseGpuBuffer`. | As created. |
| `isClosed()` | True immediately after `close()`. | Flag. |
| `close()` | Idempotent. Vulkan throws "Attempt to close a mapped buffer" if a mapping is open (`VulkanGpuBuffer.java:113-122`). | Set flag; tag the native release with `pendingSeq` in the deferred FIFO (or return the `MTLBuffer` to the pool after that seq). Keep the Vulkan throw for open mappings (refcount). |
| `slice(o,l)`, `slice()`, `map(read, write)` | Defaults in the interface. | — |
| `MappedView map(long offset, long length, boolean read, boolean write)` | **Unsynchronized, coherent, persistent-capable**: never waits for the GPU; may stay open while the GPU uses the buffer (`StagingBuffer.PersistentlyMapped` keeps a 49 MiB staging buffer mapped across frames and copies from it, `B3D/vertex/StagingBuffer.java:119-151`). Throws if not readable/writable per MAP_READ/MAP_WRITE, closed, out of range, or `length > 2 GB`. Mapped per write by `DynamicGpuDataStorageMapped` (`MC/renderer/DynamicGpuDataStorageMapped.java:75-77`), including inside render passes. | Allocation-free and O(1): `MemoryUtil.memByteBuffer(contents + offset, (int) length)` in native byte order; `MappedView.onClose` is an idempotent refcount decrement (no flush: Shared storage is coherent at command-buffer boundaries). Consider caching one `MappedView` per buffer for whole-buffer maps. |

### 2.9 `TransientMemory` (`RP/api/buffers/TransientMemory.java:6-86`) [S]

| Method | Semantics | Metal |
|---|---|---|
| `allocateCpu(size, align[, minAlloc, elemSize])` → `ByteBuffer` | Plain CPU scratch; max alignment 16; recycled at `submit()`. | `TransientBlockAllocator` over `nmemAlignedAlloc` blocks; `rotate().run()` immediately at submit (Vulkan, `VulkanTransientMemory.java:159-168`). |
| `allocateStaging(size, align, usage[, …])` → `MappedView` | CPU-written, GPU-readable upload memory; valid until submit; recycled after that submit completes. | One shared allocator: `TransientBlockAllocator` over Shared (WriteCombined) `MTLBuffer` blocks; effective alignment `max(align, 16)`; recycle via the deferred FIFO. View `close()` is a no-op. |
| `allocateGpu(size, align, usage[, …])` → `GpuBufferSlice` | GPU-only scratch, contents undefined. | Same allocator (UMA: no separate device memory). |
| `allocateGpuMapped(size, align, usage[, …])` → `MappedView` | CPU-written view of GPU memory; Vulkan semantics: writes made any time before `submit()` are visible to the whole submission. | Same memory. Weakened guarantee when the frame is split (early commit/flush): writes must precede the commit of the command buffer that reads them (vanilla complies). |
| `uploadStaging/uploadGpu(List<ByteBuffer>, align, usage[, minAlloc, elemSize])` → `GpuBufferSlice` | Concatenate, each piece padded to `align`. | Copy into one allocation. |
| `multiUploadStaging/multiUploadGpu(List<ByteBuffer>, align, usage)` → `List<GpuBufferSlice>` | Pack many arrays into few blocks; results in input order. Quirk: falls back to `allocateGpuMapped` even for staging when nothing fits (`VulkanTransientMemory.java:450`). | Mirror. |
| Partial-allocation contract | `minimumAllocation`/`elementSize`: may return a slice **shorter than requested** (rest of block rounded down to `elementSize`) (`RP/backend/util/TransientBlockAllocator.java:132-139`). Vanilla never triggers it. | Inherit from `TransientBlockAllocator`. |
| Returned transient `GpuBuffer`s | `map()` and `slice()` on the buffer throw; `isClosed()` becomes true once the submit index advances; `size()` = whole block (`VulkanTransientMemory.java:468-510`). | Copy that class. |

Block size: vanilla uses 512 KiB; Metal MAY use 4–8 MiB blocks (fewer distinct `MTLBuffer`s, more offset-only rebinds). Steady-state memory = per-frame usage × (frames in flight + 1).

### 2.10 `GpuTexture` / `GpuTextureView` (`RP/api/textures/GpuTexture.java:10-37`, `GpuTextureView.java:5-17`) [S]

- Extend `BaseGpuTexture` / `BaseGpuTextureView`. `getWidth(mip) = width >> mip` is **not clamped to 1** (`RP/backend/common/BaseGpuTexture.java:34-41`); Metal mip sizes are clamped, so compute copy extents with `max(1, …)` when talking to Metal and keep the API value for validation.
- Usage bits: `COPY_DST=1, COPY_SRC=2, TEXTURE_BINDING=4, RENDER_ATTACHMENT=8, CUBEMAP_COMPATIBLE=16`. Game usages: 15 for render targets (RGBA8, D32F, RGBA16F, RGBA32F), 13, 9 (GUI/PiP depth never sampled), 5, 7, 21 (panorama cube), 10 (Tracy) [R03].
- Views select a mip range only; cube textures always get cube views with 6 slices; `isClosed()` per object; closing order between textures and views is not enforced.
- As attachments: use the parent texture with `.level = baseMipLevel` (atlas mip generation renders into per-mip views, `MC/renderer/texture/TextureAtlas.java:62-70,179-191,251`).

### 2.11 `GpuSampler` (`RP/api/textures/GpuSampler.java:6-20`) [S]

Getters (`getAddressModeU/V`, `getMinFilter`, `getMagFilter`, `getMaxAnisotropy`, `getMaxLod`) return the creation parameters; `isClosed()`; `close()` deferred (the terrain sampler is closed and recreated mid-frame while still bound in recorded work).

### 2.12 `GpuFence` (`RP/api/commands/GpuFence.java:5-12`) [S]

`awaitCompletion(long timeoutNS)` and `close()`; semantics in §1.5. Consumers: `RenderSystem.queueFencedTask/executePendingTasks` (polls with 0, `B3D/systems/RenderSystem.java:323-341`), `MappableRingBuffer.currentBuffer` (waits with −1), `StagedVertexBuffer` pool recycling (polls with 0). Make them plain Java objects holding one `long`.

### 2.13 `GpuQueryPool` (`RP/api/commands/GpuQueryPool.java:6-12`) [S]

`size()`, `getValue(i)`, `getValues(i, n)` (**non-blocking**; `OptionalLong.empty()` until available), `close()` (deferred).

**Timestamp convention [D][P].** Three Metal clocks were measured on this M5:
- `MTL4CounterHeap` entries: **24 MHz ticks** (`queryTimestampFrequency = 24000000`), in the `mach_absolute_time` domain.
- Classic `MTLCounterSampleBuffer` timestamps: **nanoseconds** (same values as `GPUStartTime×1e9` and `sampleTimestamps`).
- `sampleTimestamps:gpuTimestamp:`: **nanoseconds** for both CPU and GPU (identical values).

Tracy computes `(long)(value * (double)timestampPeriod) + calibrationOffset` with a `float` period (`RP/frontend/TracyGpuProfiler.java:25,62`). A float 41.666668 applied to ~3.7e13 ticks gives a ~46 ms constant error, which a separately computed offset would not cancel. Therefore: **convert to nanoseconds in the backend** (`ticks × 125 / 3` using `mach_timebase_info`, integer math) and report `timestampPeriod = 1.0f`. `getTimestampCalibrationOffset()` = `System.nanoTime() − gpuNs` (§2.2).

Implementation: MTL4 → one `MTL4CounterHeap` (type Timestamp, `count = size`), resolve with `resolveCounterRange:` only for indices whose `writtenSeq ≤ completedSeq` (unwritten or invalidated entries resolve to 0). Classic → `MTLCounterSampleBuffer` (common counter set `timestamp`, Shared, `sampleCount = size`), resolved after completion. Normal gameplay writes no timestamps, so keep the path zero-cost when unused.

### 2.14 Exceptions [S]

| Exception | Thrown by the Metal backend when |
|---|---|
| `BackendCreationException` (checked) | `loadLibrary`/`createDevice` failures only. |
| `SurfaceException` (checked; `(String)` or `(Throwable)`) | `configure` (size out of range) and `acquireNextTexture` (iconified; rethrown eaten nil-drawable error). Never for occlusion. |
| `GpuOutOfMemoryException(String)` | `newBuffer…`/`newTexture…` returned nil. |
| `GpuDeviceLossException(String)` | A command buffer ended with `MTLCommandBufferErrorDeviceRemoved` / page fault / GPU timeout. Raise on the render thread at the next `submit()` or fence wait, never from a completion handler. |
| `IllegalStateException` | `submit()` with a pass open; fence wait with timeout on the current submit while a render encoder is open (or always, if the flush extension is not implemented); 5 s frame-wait timeout; use of a finished pass. |
| `IllegalArgumentException` / `UnsupportedOperationException` | Unsupported formats, invalid view ranges, multi-draw forms reported as unsupported. |

---

## 3. Resource binding model, end to end

### 3.1 The chain

```
GLSL (shaders/core|post|include): #version 330 + GL_ARB_separate_shader_objects, explicit layout(location) on all
      in/out, `layout(std140) uniform Name { … };` and `uniform sampler2D X;` with NO binding/set qualifiers
  │ shaderc (worker): target vulkan_1_2 (SPIR-V 1.5), auto_bind_uniforms, debug info, -O0
  │                  (RP/frontend/shaders/GlslCompiler.java:74-94)
  ▼
SPIR-V → spvc reflection (RP/frontend/shaders/SPIRVModule.java:65-153)
  │ PipelineBuilder.generateBackendCreateInfo (RP/frontend/shaders/PipelineBuilder.java:259-301):
  │   for shader in [VERTEX, FRAGMENT]:
  │     for descriptor in spvc order of types {1 UBO, 2 SSBO, 6 storage img, 7 sampled img, 10 sep img, 11 sep sampler}:
  │       name must exist in the pipeline's BindGroupLayouts; b = first-seen dense index; kinds must agree across stages
  │       patch SPIR-V words in place: DescriptorSet = 0, Binding = b   (SPIRVModule.java:247-292)
  ▼
CreateInfo.uniforms().get(b) = UniformDescription(name, UniformType, @Nullable GpuFormat)   // index == binding
FrontendRenderPipeline.uniformIndices: name → b                                          // PipelineBuilder.java:458
  ▼  (render thread)
RenderPass.setUniform("Name", value) → FrontendRenderPass map[name] = value
  → if bound pipeline knows name: backend.setUniform(b, value)                           // FrontendRenderPass.java:159-172
  → on setPipeline: backend.setPipeline(p); map.forEach(setUniform)                        // :123-126
  ▼
MetalRenderPass.setUniform(b, value): table[b] = {kind, mslBuffer | mslTexture+mslSampler, stageMask}
  → SET_* record → decoder → set{Vertex,Fragment}{Buffer,BufferOffset,Texture,SamplerState} (classic)
                           → per-stage MTL4ArgumentTable setAddress/setTexture/setSamplerState (MTL4)
```

Facts the backend relies on [R02][S]:
- Exactly three uniform kinds: `UNIFORM_BUFFER` (std140), `COMBINED_IMAGE_SAMPLER` (`sampler2D`, `samplerCube`), `TEXEL_BUFFER` (`isamplerBuffer`; only `CloudFaces`, `R8_SINT`, in the 8 cloud pipelines, `MC/renderer/BindGroupLayouts.java:31-34`). SSBOs, storage images, separate images/samplers, 1D and 3D textures are rejected by the frontend.
- Push constants: at most one block per stage, ≤ 128 B; **no vanilla pipeline uses them**.
- Binding numbers depend only on the two SPIR-V modules' declared resources. Declared-but-unused resources still receive bindings (e.g. `Fog` in `terrain.vsh`); SPIRV-Cross MSL emits only used resources, so per-stage *used masks* come from `spvc_compiler_msl_is_resource_used`.
- Example [R02]: `solid_terrain_multidraw` → `{Globals=0, Projection=1, TerrainUniform=2, Fog=3, Sampler2=4 (vertex stage), Sampler0=5}`.
- Max 12 referenced uniforms in one pipeline (5 UBO + 7 textures; OIT accumulate + glint entity).

### 3.2 Metal index layout [D]

Both stages use the **same** Metal index for a given binding (a binding absent from a stage's MSL is simply never bound there).

| Resource | Metal index | Notes |
|---|---|---|
| UBO | `[[buffer(0..13)]]` | Assign well-known global names fixed indices when present — `Projection`→0, `Globals`→1, `Fog`→2, `Lighting`→3, `DynamicTransforms`→4 — and every other UBO densely from 5. Pipeline switches then leave the hot globals bound (the frontend re-sends them, the native shadow filter drops them). Vanilla max is 5 UBOs, so 14 slots suffice; if a pipeline ever exceeds 14, fall back to fully dense assignment. |
| Push constants | `[[buffer(14)]]` | spvc: `desc_set = SPVC_MSL_PUSH_CONSTANT_DESC_SET (-1)`, `binding = SPVC_MSL_PUSH_CONSTANT_BINDING (0)`. |
| Vertex buffer slot *s* (0..15) | `[[buffer(30 − s)]]` | 30..15; never collides with 0..14. (Doc 04's "push constants at 29" would collide with slot 1, `CHUNK_DATA_INSTANCED`; rejected.) |
| Combined image sampler | `[[texture(t)]]` + `[[sampler(t)]]` | `Sampler0`→0, `Sampler1`→1, `Sampler2`→2 fixed; others dense from 3. Metal discrete limit: 16 samplers per stage (vanilla max 7). |
| Texel buffer | `[[texture(t)]]` as `texture_buffer<int>` | Same index space as textures; no sampler. |

MTL4 argument table per stage: `maxBufferBindCount = 31`, `maxTextureBindCount = 16`, `maxSamplerStateBindCount = 16` (limits: 31/128/16, `$MTL/MTL4ArgumentTable.h:27-37` [R08]); the snapshot is taken at each draw, so mutate between draws only.

### 3.3 SPIRV-Cross (lwjgl-spvc 3.4.3, SPIRV-Cross git 6c09849f, C API 0.68) MSL options [R02, verified to compile on M5]

Per stage, in `compilePipeline` (worker): `spvc_context_create` → `spvc_context_parse_spirv(module.spv())` → `spvc_context_create_compiler(ctx, SPVC_BACKEND_MSL = 3, ir, SPVC_CAPTURE_MODE_TAKE_OWNERSHIP = 1)` → options → resource bindings → `spvc_compiler_compile` → copy the string → `spvc_context_destroy`. One context per stage, never shared across threads.

| Option | ID | Value | Why |
|---|---|---|---|
| `MSL_VERSION` | 134217745 | `30200` (MSL 3.2; `major*10000 + minor*100`) | Verified with `MTLLanguageVersion3_2`. MSL 4.0 untested with this SPIRV-Cross build [O]. |
| `MSL_PLATFORM` | 134217759 | `SPVC_MSL_PLATFORM_MACOS = 1` | |
| `MSL_TEXTURE_BUFFER_NATIVE` | 134217762 | `true` | `isamplerBuffer` → `texture_buffer<int>`. |
| `MSL_ARGUMENT_BUFFERS` | 134217760 | `false` | Discrete indices map 1:1 onto classic `set*` calls **and** MTL4 argument-table slots. |
| `MSL_ENABLE_DECORATION_BINDING` | 134217777 | `false` | Use explicit `spvc_compiler_msl_add_resource_binding`. |
| `FLIP_VERTEX_Y` | 16777220 | `true` (vertex stage) | Appends `gl_Position.y = -gl_Position.y`; gives GL/Vulkan memory-row layout [R02]. |
| `FIXUP_DEPTH_CONVENTION` | 16777219 | `false` | The game already builds [0,1] projections when `isZZeroToOne`. |
| `MSL_PAD_FRAGMENT_OUTPUT_COMPONENTS` | 134217757 | `true` | Safety for <4-component outputs. |
| `FORCE_ZERO_INITIALIZED_VARIABLES` | 16777270 | `true` | Parity with the GL path (`RP/backend/opengl/GlPipelineRecompiler.java:68-71`). |
| `MSL_ENABLE_POINT_SIZE_BUILTIN` | 134217753 | `true` (default) | `debug_point.vsh` writes `gl_PointSize` → `[[point_size]]`. |
| `MSL_ENABLE_FRAG_DEPTH_BUILTIN` | 134217785 | `true` (default) | `gl_FragDepth` → `[[depth(any)]]`. |
| `MSL_INVARIANT_FP_MATH` | 134217775 | `false` unless the invariance experiment of §9.1 (B13) is enabled | Only affects `Invariant` outputs. |

Resource bindings, for each `i` in `createInfo.uniforms()` and each stage model (0 = vertex, 4 = fragment): `SpvcMslResourceBinding.set(stage, 0, i, mslBuffer, mslTexture, mslSampler)` (argument order verified from bytecode [R02]); UBO → `msl_buffer`; CIS → `msl_texture` + `msl_sampler`; texel buffer → `msl_texture`. Push constants (only if `pushConstantsSize > 0`): `(stage, -1, 0, 14, 0, 0)`.

After compiling: entry point is `main0` (`spvc_compiler_get_cleansed_entry_point_name`); assert `msl_needs_swizzle_buffer`, `msl_needs_buffer_size_buffer`, `msl_needs_output_buffer`, `msl_is_rasterization_disabled` are all false; record `usedMask[stage] |= msl_is_resource_used(compiler, model, 0, i) << i`. Compile **one `MTLLibrary` per stage** (both stages are named `main0` and helper code would collide in a combined source). Cache MSL text, libraries and functions by SHA-256(patched SPIR-V) + stage + option-version; expect ~120 vertex + ~121 fragment libraries instead of 390 [R02].

Generated-code properties [R02]: vertex inputs are `[[attribute(location)]]` in a `[[stage_in]]` struct; varyings `[[user(locnN)]]` (`flat` → `[[flat]]`); std140 blocks use `packed_float3`/`packed_int3`; fragment output arrays split into `[[color(0)]]`, `[[color(1)]]`; vertex-stage `texture()` becomes `.sample(s, uv, level(0))`; `samplerCube` → `texturecube<float>`; a `Depth32Float` texture bound as `texture2d<float>` (from `sampler2D` in `blit_depth.fsh`) passes validation and returns depth [R02].

### 3.4 Draw-time binding (normative) [D]

Java, per `MetalRenderPipeline`: arrays indexed by binding `i` → `kind`, `bufIdx`/`texIdx`, `stageMask` (bit 0 vertex, bit 1 fragment from the used masks), plus `usedBuffers[stage]`, `usedTextures[stage]` bitmasks.

Java, per `MetalRenderPass`: `Object[] last` per binding index and a `setMask`. `setPipeline` → swap the table, clear `last` and `setMask`. `setUniform(i, v)` → if `v` equals `last[i]` (`GpuBufferSlice` by buffer identity + offset; `TextureViewAndSampler` by view and sampler identity) return; else record `SET_BUF(stageMask, bufIdx, handle/gpuAddress, offset)` / `SET_TEX(stageMask, texIdx, texHandle, samplerHandle)` / `SET_TEXBUF(stageMask, texIdx, viewHandle)` / `UNSET(…)`. No hash maps, no native calls, no allocation (`GpuBufferSlice` records are already allocated by the game).

Native decoder, per encoder: shadow arrays `buf[stage][31] = (MTLBuffer*, offset)`, `tex[stage][16]`, `smp[stage][16]`, current PSO, depth-stencil state, cull, winding, fill mode, depth bias, scissor. Rules:
- Same buffer, different offset → `set{Vertex,Fragment}BufferOffset:atIndex:` (the per-draw `DynamicTransforms` and terrain-fallback `ChunkSection` case).
- Different buffer → `set{Vertex,Fragment}Buffer:offset:atIndex:`.
- Before each draw: any `used` slot of the bound pipeline not set since its `setPipeline` gets the dummy resource (§2.4).
- Fixed-function state from `SET_PIPELINE` is applied only on change.

### 3.5 Pipeline-switch replay

`FrontendRenderPass.setPipeline` re-sends every uniform the pass holds (`FrontendRenderPass.java:125`), and `PreparedRenderType.draw` additionally calls `bindDefaultUniforms` (4 UBOs) and `DynamicTransforms` per batch (`MC/renderer/rendertype/PreparedRenderType.java:37-56`). With the fixed indices of §3.2 and the shadow filter, a typical entity/GUI batch reduces to: PSO (if changed), `DynamicTransforms` offset, 0–2 textures, vertex buffer, draw.

### 3.6 Vertex input

No `spvc_compiler_msl_add_vertex_attribute` is needed with `[[stage_in]]` [R02]. For each `AttribBinding(slot, location, offset, fmt)`: `vd.attributes[location] = {format: §4.1 vertex column, offset, bufferIndex: 30 − slot}`. For each `VertexBuffer(slot, stride, stepRate)` that has at least one attribute: `vd.layouts[30 − slot] = {stride, stepFunction: stepRate > 0 ? PerInstance : PerVertex, stepRate: max(1, stepRate)}`. Validate `offset % 4 == 0` (Metal requirement; the `VertexFormat` builder only aligns to the component size, `RP/api/vertex/VertexFormat.java:84-86`; vanilla offsets are all multiples of 4). Per-instance attributes include `baseInstance` for direct, indexed and indirect draws [P], so `CHUNK_DATA_INSTANCED` (16 B: `ChunkPosition RGB32_SINT` + `ChunkVisibility R32F`) works with MDI's `baseInstance = section index`.

### 3.7 Texel buffers

`[mtlBuffer newTextureWithDescriptor:[MTLTextureDescriptor textureBufferDescriptorWithPixelFormat:R8Sint width:size/bpp resourceOptions:<buffer's options> usage:ShaderRead] offset:0 bytesPerRow:alignUp(size, minimumTextureBufferAlignmentForPixelFormat(R8Sint) /*16 on M5 [R02]*/)]`. Create it once per (buffer, format) and cache it on the `MetalBuffer` (Vulkan creates a `VkBufferView` per descriptor push, `RP/backend/vulkan/VulkanRenderPass.java:319-341`). The frontend requires a whole-buffer slice, so the offset is always 0. Allocate buffers with `USAGE_UNIFORM_TEXEL_BUFFER` rounded up to 16 bytes natively (keep `size()` as requested) so `bytesPerRow` never exceeds the allocation. MTL4: bind by the view's `gpuResourceID`; release the view with the buffer.

### 3.8 Missing and null bindings

Release builds do not validate presence (§0.1). Policy [D]: dummy resources (256-byte zero UBO, 1×1 2D and cube textures, a default clamp/nearest sampler, a zero texel buffer). A debug flag (`-Dmcmetal.strictBindings=true`) throws `IllegalStateException("Missing uniform <name>")` like Vulkan instead.

---

## 4. Formats and enum mappings

### 4.1 `GpuFormat` → Metal (`RP/api/GpuFormat.java:3-59`) [S]

`blockSize = componentType.byteSize × componentCount`; `byteAlignment = componentType.byteSize`. "Vanilla" marks formats game code uses (textures: RGBA8_UNORM, R8_UNORM, D32_FLOAT, RGBA16_FLOAT, RGBA32_FLOAT; vertex: see §3.6; texel buffer: R8_SINT) [R03].

| GpuFormat | Bytes | MTLPixelFormat | MTLVertexFormat | Vanilla use / notes |
|---|---|---|---|---|
| R8_UNORM | 1 | R8Unorm | UCharNormalized | font pages, paletted atlas |
| R8_SNORM | 1 | R8Snorm | CharNormalized | |
| RG8_UNORM | 2 | RG8Unorm | UChar2Normalized | |
| RG8_SNORM | 2 | RG8Snorm | Char2Normalized | |
| RGB8_UNORM | 3 | — (IAE) | UChar3Normalized | no texture use |
| RGB8_SNORM | 3 | — (IAE) | Char3Normalized | |
| RGBA8_UNORM | 4 | RGBA8Unorm | UChar4Normalized | main color target, atlases, `Color` attribute |
| RGBA8_SNORM | 4 | RGBA8Snorm | Char4Normalized | `Normal` attribute |
| R16_UNORM | 2 | R16Unorm | UShortNormalized | |
| R16_SNORM | 2 | R16Snorm | ShortNormalized | |
| RG16_UNORM | 4 | RG16Unorm | UShort2Normalized | |
| RG16_SNORM | 4 | RG16Snorm | Short2Normalized | |
| RGB16_UNORM | 6 | — (IAE) | UShort3Normalized | |
| RGB16_SNORM | 6 | — (IAE) | Short3Normalized | |
| RGBA16_UNORM | 8 | RGBA16Unorm | UShort4Normalized | |
| RGBA16_SNORM | 8 | RGBA16Snorm | Short4Normalized | |
| R8_UINT | 1 | R8Uint | UChar | |
| R8_SINT | 1 | R8Sint | Char | `CloudFaces` texel buffer |
| RG8_UINT | 2 | RG8Uint | UChar2 | |
| RG8_SINT | 2 | RG8Sint | Char2 | |
| RGB8_UINT | 3 | — (IAE) | UChar3 | |
| RGB8_SINT | 3 | — (IAE) | Char3 | |
| RGBA8_UINT | 4 | RGBA8Uint | UChar4 | |
| RGBA8_SINT | 4 | RGBA8Sint | Char4 | |
| R16_UINT | 2 | R16Uint | UShort | |
| R16_SINT | 2 | R16Sint | Short | |
| RG16_UINT | 4 | RG16Uint | UShort2 | |
| RG16_SINT | 4 | RG16Sint | Short2 | `UV1`/`UV2` (light), feeds `ivec2` |
| RGB16_UINT | 6 | — (IAE) | UShort3 | |
| RGB16_SINT | 6 | — (IAE) | Short3 | |
| RGBA16_UINT | 8 | RGBA16Uint | UShort4 | |
| RGBA16_SINT | 8 | RGBA16Sint | Short4 | |
| R32_UINT | 4 | R32Uint | UInt | |
| R32_SINT | 4 | R32Sint | Int | |
| RG32_UINT | 8 | RG32Uint | UInt2 | |
| RG32_SINT | 8 | RG32Sint | Int2 | |
| RGB32_UINT | 12 | — (IAE) | UInt3 | |
| RGB32_SINT | 12 | — (IAE) | Int3 | `ChunkPosition` (instanced) |
| RGBA32_UINT | 16 | RGBA32Uint | UInt4 | |
| RGBA32_SINT | 16 | RGBA32Sint | Int4 | |
| R16_FLOAT | 2 | R16Float | Half | |
| RG16_FLOAT | 4 | RG16Float | Half2 | |
| RGB16_FLOAT | 6 | — (IAE) | Half3 | |
| RGBA16_FLOAT | 8 | RGBA16Float | Half4 | OIT transmittance/accumulate targets (additive blend) |
| R32_FLOAT | 4 | R32Float | Float | `LineWidth`, `ChunkVisibility` |
| RG32_FLOAT | 8 | RG32Float | Float2 | `UV0`, `UV3` |
| RGB32_FLOAT | 12 | — (IAE) | Float3 | `Position` |
| RGBA32_FLOAT | 16 | RGBA32Float | Float4 | OIT depth-bounds target with **MAX blending** (builds on M5 [R02]) |
| RGB10A2_UNORM | 4 | RGB10A2Unorm | UInt1010102Normalized | |
| RGB10A2_UINT | 4 | RGB10A2Uint | — (no vertex format) | |
| RG11B10_FLOAT | 4 | RG11B10Float | FloatRG11B10 | |
| D32_FLOAT | 4 | **Depth32Float** | — | every depth target; sampled as `texture2d<float>` |
| D32_FLOAT_S8_UINT | 8 | Depth32Float_Stencil8 | — | blits need `DepthFromDepthStencil`/`StencilFromDepthStencil` |
| D24_UNORM_S8_UINT | 4 | — on Apple silicon (`depth24Stencil8PixelFormatSupported == NO` [R08]) | — | throw IAE (substituting Depth32Float_Stencil8 breaks `blockSize` 4 vs 8) |
| D16_UNORM | 2 | Depth16Unorm | — | |
| S8_UINT | 1 | Stencil8 | — | |

Never pick `_sRGB` Metal formats: `GpuFormat` has none, and nothing in RenderPearl applies an sRGB transfer function. The macOS 27 SDK's new 24/48/96-bit RGB pixel formats are texture-buffer-only and not render targets [R08]; ignore them.

### 4.2 Enum mappings [R02][S]

| RenderPearl | Metal |
|---|---|
| `BlendFactor` ZERO, ONE, SRC_COLOR, ONE_MINUS_SRC_COLOR, SRC_ALPHA, ONE_MINUS_SRC_ALPHA, DST_COLOR, ONE_MINUS_DST_COLOR, DST_ALPHA, ONE_MINUS_DST_ALPHA, SRC_ALPHA_SATURATE, CONSTANT_COLOR, ONE_MINUS_CONSTANT_COLOR, CONSTANT_ALPHA, ONE_MINUS_CONSTANT_ALPHA | Zero, One, SourceColor, OneMinusSourceColor, SourceAlpha, OneMinusSourceAlpha, DestinationColor, OneMinusDestinationColor, DestinationAlpha, OneMinusDestinationAlpha, SourceAlphaSaturated, BlendColor, OneMinusBlendColor, BlendAlpha, OneMinusBlendAlpha (no blend-color API exists; the default 0,0,0,0 applies, as in GL/Vulkan) |
| `BlendOp` ADD, SUBTRACT, REVERSE_SUBTRACT, MIN, MAX | Add, Subtract, ReverseSubtract, Min, Max |
| `CompareOp` ALWAYS_PASS, NEVER_PASS, LESS_THAN, LESS_THAN_OR_EQUAL, EQUAL, NOT_EQUAL, GREATER_THAN, GREATER_THAN_OR_EQUAL | Always, Never, Less, LessEqual, Equal, NotEqual, Greater, GreaterEqual |
| `ColorTargetState.writeMask` R=1, G=2, B=4, A=8 (`RP/api/pipeline/ColorTargetState.java:11-17`) | `MTLColorWriteMask` Red=8, Green=4, Blue=2, Alpha=1: `mtl = ((m&1)<<3) \| ((m&2)<<1) \| ((m&4)>>1) \| ((m&8)>>3)`. Vanilla uses 15, 7 and 0. |
| `DepthStencilState(compare, write, biasScale, biasConstant)`; `DEFAULT = (GREATER_THAN_OR_EQUAL, true)` (`RP/api/pipeline/DepthStencilState.java:3-4`) | `MTLDepthStencilDescriptor{depthCompareFunction, depthWriteEnabled}`; `null` → `{Always, NO}`; encoder `setDepthBias:biasConstant slopeScale:biasScale clamp:0` (Vulkan `constantFactor = depthBiasConstant, slopeFactor = depthBiasScaleFactor`, `VulkanRenderPipeline.java:266-281`). Values are positive under reverse-Z; same sign semantics as Vulkan. |
| `PolygonMode` FILL, WIREFRAME | `setTriangleFillMode:` Fill, Lines |
| `cull` true/false | `setCullMode:` Back / None, with `setFrontFacingWinding:Clockwise` |
| `IndexType` SHORT(2), INT(4) | UInt16, UInt32 |
| `AddressMode` REPEAT, CLAMP_TO_EDGE | Repeat, ClampToEdge |
| `FilterMode` NEAREST, LINEAR | Nearest, Linear |

### 4.3 Primitive topology (`RP/api/pipeline/PrimitiveTopology.java:3-29`) [S]

| Topology | How the game draws it | MTLPrimitiveType |
|---|---|---|
| `QUADS` | Indexed through the shared quad pattern `i, i+1, i+2, i+2, i+3, i` (`B3D/systems/RenderSystem.java:50-57`) | Triangle |
| `LINES` | Indexed through `i, i+1, i+2, i+3, i+2, i+1` with screen-space expansion in `rendertype_lines.vsh` | Triangle |
| `TRIANGLES` | — | Triangle |
| `TRIANGLE_STRIP` | — | TriangleStrip (Metal always restarts on 0xFFFF/0xFFFFFFFF; `IndexType.least` never produces those, `RP/api/pipeline/IndexType.java:13-15`) |
| `DEBUG_LINES` / `DEBUG_LINE_STRIP` | unused | Line / LineStrip (1 px) |
| `POINTS` | `debug_point` pipelines; vertex shader writes `gl_PointSize` | Point (`[[point_size]]` mandatory) |
| **`TRIANGLE_FAN`** | Sky disc and sunrise: **non-indexed `draw(10,1,0,0)` / `draw(18,1,0,0)`** every frame (`MC/renderer/SkyRenderer.java:166-167,180-181,303-304`). Debug fans: `RenderTypes.DEBUG_TRIANGLE_FAN` → `drawIndexed` with the identity `sharedSequential` index buffer (`B3D/systems/RenderSystem.java:300-307`). | **No Metal equivalent — emulate.** |

Fan emulation [D]: (a) `draw(n, inst, first, baseInst)` → keep a static device-wide UInt16/UInt32 index buffer `[0,1,2, 0,2,3, …, 0,k,k+1]` (grow on demand) and issue `drawIndexedPrimitives:Triangle indexCount:3(n−2) … indexBufferOffset:0 instanceCount:inst baseVertex:first baseInstance:baseInst`. (b) `drawIndexed` on a fan pipeline → at decode time read `indices[firstIndex .. firstIndex+n)` from the bound index buffer (Shared, immutable, created with data) and write `(i0, ik, ik+1)` triples into the upload ring; draw as Triangle with the same `baseVertex`. Skip `n < 3`. Fan order `(0, k, k+1)` preserves the GL winding. A game patch (§9.2) can remove fans entirely.

---

## 5. Coordinate conventions

| Topic | GL backend | Vulkan backend | Metal backend (MUST) |
|---|---|---|---|
| Clip-space Z | [0,1] only with `ARB_clip_control` (`glClipControl(LOWER_LEFT, ZERO_TO_ONE)`, `RP/backend/opengl/GlDevice.java:149-152`); **absent on macOS GL 4.1 → [-1,1]** (`GlHeuristics.java:91`) | [0,1]; `isZZeroToOne = true` (`VulkanDevice.java:91`) | Report `isZZeroToOne = true`. The game then builds 0..1 projections (`MC/renderer/Projection.java:69-74`, `MC/Camera.java:188`) and shaders get `RENDERPEARL_DEPTH_IS_ZERO_TO_ONE` (`GlslCompiler.java:81-83`; used in `include/oit_common.glsl:17-19`). No `FIXUP_DEPTH_CONVENTION`. Keep `MTLDepthClipModeClip`. |
| Depth direction | Reverse-Z: near/far swapped (`Projection.java:67-68`), `GREATER_THAN_OR_EQUAL` (`RP/api/pipeline/DepthStencilState.java:4`), clear 0.0 (`B3D/systems/RenderSystem.java:43`) | same | `Depth32Float` everywhere; clear values come from the game. |
| NDC y → memory row | NDC y=−1 → window y=0 → texture row 0 | Positive-height viewport `(0,0,W,H)` (`VulkanRenderPass.java:80-87`) → NDC y=−1 → row 0; **same bytes as GL** | Metal maps NDC y=+1 to row 0. Negate clip Y in every vertex shader (`FLIP_VERTEX_Y`) [R02]. A negative-height `MTLViewport` passes validation on M5 [P] but its orientation was not verified; the shader flip is the verified path. |
| Front face | CCW in y-up window space | `frontFace = CLOCKWISE`, cull BACK (`VulkanRenderPipeline.java:266-271`) | `setFrontFacingWinding:MTLWindingClockwise` with the shader flip [R02 verified: a GL-CCW quad survives back-face culling with Clockwise]. Without the flip it would be CounterClockwise — never mix. |
| Viewport | `glViewport(0,0,w,h)` per pass | `(0,0,outW,outH)`, depth 0..1, per pass | Metal's default viewport equals the full attachment; no API exists to change it. |
| Scissor / render area / region clears | `glScissor` lower-left = row 0 | `vkCmdSetScissor` passthrough (`VulkanRenderPass.java:174-178`) | `setScissorRect` passthrough. The GUI computes `y = height − bottom` (`MC/gui/render/GuiRenderer.java:419`) and `GuiItemAtlas` `textureSize − bottom` (`:101-111`) — already memory rows. |
| `gl_FragCoord` | row index + 0.5 | same | `[[position]]` = row + 0.5 after the flip [R02]; `texelFetch(…, ivec2(gl_FragCoord.xy))` in OIT/integrate-depth shaders works unchanged. |
| Texture uploads, UVs | Row 0 of uploaded data = texture row 0 = v 0 | same | Upload as-is; no flip. |
| Readback (`copyTextureToBuffer`) | `glReadPixels` row 0 first | memory order | Memory order; `Screenshot` flips in Java (`MC/Screenshot.java:103`). |
| Present | `glBlitFramebuffer` to FBO 0, **no flip** (default FB is bottom-up), top-anchored (`GlCommandEncoder.java:436-447`) | `vkCmdBlitImage` with **Y flip** `dst (0,copyH)→(copyW,0)` (`VulkanGpuSurface.java:408-435`) | Present pass flips: drawable pixel `(x, y)` reads source texel `(x, copyH−1−y)` (§6.6). |
| Cube faces | layer i = +X,−X,+Y,−Y,+Z,−Z (`GlConst.java:104`) | `baseArrayLayer = layer` | `destinationSlice = layer`, same order. |
| `vertex_id` / `instance_id` | GL semantics | `VertexIndex` includes base vertex, `InstanceIndex` includes base instance | MSL `[[vertex_id]]`/`[[instance_id]]` include the bases; report `shaderDrawParameters = true` (its macro is unused by vanilla). |
| Derivatives | `dFdy` along increasing rows | same | same after the flip. |

---

## 6. Surface and presentation (CAMetalLayer via SDL3)

### 6.1 Window, view, layer [R05][R08]

- Window: `SDL_CreateWindow(title, w, h, 0x2020 | SDL_WINDOW_METAL)`; `w,h` are points; the game renders at **backing-pixel** resolution (`HIGH_PIXEL_DENSITY`; `Window.getWidth/getHeight` are pixels from `SDL_GetWindowSizeInPixels`, `B3D/platform/Window.java:402-431,764-778`). Typical sizes on the 14" M5: default window 1708×960, fullscreen 3024×1964, "More Space" display mode 3600×2338 (downsampled by WindowServer — a hidden ~40% pixel tax; the existing benchmarks ran at 3600×2260).
- LWJGL 3.4.3 (native SDL 3.4.14): `SDLMetal.SDL_Metal_CreateView(long window)`, `SDL_Metal_GetLayer(long view)` (borrowed `CAMetalLayer*`), `SDL_Metal_DestroyView(long view)`; `SDLVideo.SDL_WINDOW_METAL = 0x20000000L`, `SDL_WINDOW_OCCLUDED = 4L`; hint `SDL_HINT_VIDEO_METAL_AUTO_RESIZE_DRAWABLE`; event `SDL_EVENT_WINDOW_METAL_VIEW_RESIZED = 520` (unhandled by the game).
- SDL's Cocoa Metal view is an opaque layer-backed subview of the content view; it does **not** set `layer.device`, `pixelFormat`, `framebufferOnly`, `displaySyncEnabled`, `maximumDrawableCount` or `colorspace`. With the auto-resize hint at its default `"1"`, an EARLY event watch updates `drawableSize` inside `SDL_PumpEvents`, i.e. between frames and before the game reconfigures.

### 6.2 `createSurface(window, isIconified)` [D]

```
SDL_SetHint("SDL_VIDEO_METAL_AUTO_RESIZE_DRAWABLE", "0")    // backend owns drawableSize (deterministic; needed for render scale)
view  = SDL_Metal_CreateView(window)                          // main thread; 0 → IllegalStateException(SDL_GetError())
layer = SDL_Metal_GetLayer(view)
[CATransaction begin]; [CATransaction setDisableActions:YES];
layer.device                 = mtlDevice                      // REQUIRED (nil → nextDrawable returns nil)
layer.pixelFormat            = MTLPixelFormatBGRA8Unorm (80)  // RGBA8Unorm is illegal for CAMetalLayer; non-sRGB
layer.framebufferOnly        = YES                            // the present is a render pass, never a blit
layer.opaque                 = YES
layer.colorspace             = sRGB                           // parity with MoltenVK (VK_COLOR_SPACE_SRGB_NONLINEAR)
layer.wantsExtendedDynamicRangeContent = NO
layer.maximumDrawableCount   = 3                              // only 2 or 3 are legal
layer.allowsNextDrawableTimeout = YES                         // nil after ~1 s instead of a hang
layer.presentsWithTransaction = NO
[CATransaction commit]
MTL4: [queue addResidencySet:layer.residencySet]
supportedModes = unmodifiableSet(EnumSet.of(FIFO, IMMEDIATE))
```

### 6.3 `configure(Configuration(w, h, mode))` [R05]

The frontend has already rejected unsupported modes (`SurfaceException`) and calls while acquired (`RP/frontend/FrontendGpuSurface.java:31-42`). Validate `1 ≤ w,h ≤ 16384` (else `SurfaceException`, mirroring `VulkanGpuSurface.java:205-223`); in a disabled-actions transaction set `drawableSize = (w, h)` and `displaySyncEnabled = (mode == FIFO)`; clear the suboptimal flag and the eaten exception. No GPU idle is needed (Vulkan does `queue.waitIdle()` plus a swapchain rebuild, `VulkanGpuSurface.java:178-190`). Reconfigure triggers: pixel-size change (event 519), display change (531), display mode change (342), `setMode()`, vsync option, iconify/restore (`MC/Minecraft.java:1428-1431,2974-2976`; `B3D/platform/Window.java:373-440`).

### 6.4 Present modes

| Mode | Layer | Behaviour |
|---|---|---|
| FIFO (vsync on; game default `enableVsync = true`, `MC/Options.java:515`) | `displaySyncEnabled = YES` | Capped at the panel refresh (ProMotion ≤120 Hz, 60 in Low Power Mode); `nextDrawable` blocking is the throttle. |
| IMMEDIATE (vsync off) | `displaySyncEnabled = NO` | Uncapped. Needed for ~1000 FPS together with Max Framerate = "Unlimited" (260, `MC/Options.java:119-130`). Measured on macOS 27 (`docs/metal/DIRECT-TO-DISPLAY.md`): a composited layer is *not* mailbox-like. With 3 drawables it presents strict FIFO at the refresh, and a blocking `nextDrawable` caps rendering at 120 FPS. Direct-to-display fullscreen can tear. The backend therefore implements mailbox semantics itself (`docs/metal/CONTRACT.md` §16.4, `MCMTL_MAILBOX=1`): a helper thread acquires drawables, the render thread never waits for one, and frames that find none are rendered and not presented. |
| MAILBOX, FIFO_RELAXED | — | Do not report (`getSupportedVsyncMode` prefers IMMEDIATE for vsync off, `RP/api/device/GpuSurface.java:35-48`; FIFO must always be supported). |

### 6.5 `acquireNextTexture()` — late acquisition [D]

The game calls acquire at the very start of the frame, before all CPU work (`MC/Minecraft.java:1273`). Taking a drawable here would hold one of the three drawables for the whole frame and could block for up to 1 s. Therefore:
1. If an eaten exception is pending → `throw new SurfaceException(e)`.
2. If `isIconified.getAsBoolean()` → `throw new SurfaceException("Cannot acquire minimized window")` (the game then renders without presenting and reconfigures on restore, `MC/Minecraft.java:1271-1279`; at most 10 FPS while minimized, `B3D/platform/FramerateLimitTracker.java:26-56`).
3. If `SDL_GetWindowFlags(window) & SDL_WINDOW_OCCLUDED` → set `dropFrame = true` and return normally. **Never throw for occlusion** (the game would warn and reconfigure every frame).
4. Otherwise return; the drawable is acquired during decode (§6.6).

### 6.6 `blitFromTexture` → `submit` → `present` [D]

The game order is blit (`:1322`), then `submit()` (`:1337`), then `present()` (`:1340`).

`blitFromTexture(encoder, view)` (frontend guarantees no pass open, color format, COPY_SRC, 1 layer, acquired, not yet blitted): if `dropFrame`, return. Record `PRESENT(viewHandle, baseMip)`. SHOULD flush ("early commit") the frame's recorded work now as command buffer A, so the GPU starts while the CPU may wait for a drawable.

`submit()` then decodes the rest of the stream (TimerQuery end, Tracy capture copies) and the PRESENT op:

| Step | Classic `MTLCommandQueue` | Metal 4 `MTL4CommandQueue` |
|---|---|---|
| Acquire | `@autoreleasepool { d = [layer nextDrawable]; retain d }` (non-critical downcall; may block) | same |
| nil drawable | Set suboptimal + eaten `SurfaceException("nextDrawable returned nil")`, skip the present pass; `present()` becomes a no-op | same |
| Present pass | Render pass on `d.texture`: `loadAction = (copyW == dW && copyH == dH) ? DontCare : Clear(0,0,0,1)`, `storeAction = Store`; cached `present_bgra8` PSO (BGRA8Unorm, no blend, no depth); full-screen triangle from `vertex_id`, no vertex buffer; bind the source view at its base mip | same; fragment→fragment barrier against the GUI pass (no automatic hazard tracking) |
| Register present | `[cbB presentDrawable:d]` (must be before commit) | `[queue commit:&frameCB…]`; `[queue waitForDrawable:d]`; `[queue commit:&presentCB count:1]`; `[queue signalDrawable:d]` (`$MTL/MTL4CommandQueue.h:294,308`, verified present [S]) — keeps the frame's work from waiting on the display |
| Timeline | `[cbB encodeSignalEvent:event value:frameSeq]`, commit | `[queue signalEvent:event value:frameSeq]` |
| `present()` | Release `d`; clear `dropFrame` | `[d present]` (after `signalDrawable`, never twice); release `d` |

Copy rectangle: `copyW = min(d.texture.width, view.getWidth(0))`, `copyH = min(d.texture.height, view.getHeight(0))`, unscaled, nearest, anchored top-left with a vertical flip — identical to Vulkan (`VulkanGpuSurface.java:408-435`) and to GL's top anchoring (`GlCommandEncoder.java:436-447`). Use the actual drawable size, not the configured size (sizes can disagree for a frame during resizes and fullscreen transitions, [R05]).

```metal
#include <metal_stdlib>
using namespace metal;
struct VOut { float4 pos [[position]]; };
vertex VOut present_vs(uint vid [[vertex_id]]) {
    float2 p = float2((vid << 1) & 2, vid & 2);
    VOut o; o.pos = float4(p * 2.0 - 1.0, 0.0, 1.0); return o;
}
struct PresentParams { uint copyHeight; uint mip; };
fragment half4 present_fs(VOut in [[stage_in]],
                          texture2d<half, access::read> src [[texture(0)]],
                          constant PresentParams& P [[buffer(0)]]) {
    uint2 d = uint2(in.pos.xy);                    // drawable pixel, row 0 = top of the screen
    uint2 s = uint2(d.x, P.copyHeight - 1 - d.y);   // render-target row 0 = bottom → flip
    return half4(src.read(s, P.mip).rgb, 1.0h);     // BGRA8 store swizzles; force opaque alpha
}
```

Set the viewport/scissor to `(0, 0, copyW, copyH)`. For render scale (§9.2 G1) replace `read` with `sample` (or a MetalFX spatial scaler before this pass; MetalFX does not flip, so this pass remains).

### 6.7 Autorelease pools and Objective-C lifetimes (critical) [R05][R08]

With `-XstartOnFirstThread` the launcher runs Java on thread 0 inside one outer `NSAutoreleasePool` that is drained only at exit. Every autoreleased object created from Java (`nextDrawable`, command buffers, render pass descriptors, encoders, `NSError`, `NSData` from `resolveCounterRange:`) would leak; drawables never return to CoreAnimation's pool, so after 3 frames `nextDrawable` blocks 1 s and returns nil. Every shim export MUST be wrapped in `@autoreleasepool`; objects that outlive a call (the drawable until `present()`, in-flight command buffers) MUST be explicitly retained and released.

### 6.8 Surface pitfalls

- `layer.device` unset → nil drawables. `RGBA8Unorm` layer format → exception. `maximumDrawableCount` other than 2 or 3 → exception.
- `present()` and `blitFromTexture()` MUST NOT throw for recoverable conditions; defer to the next `acquireNextTexture()` (Vulkan's eaten-exception pattern, `VulkanGpuSurface.java:302-307,517-526`). A throw from `present()` leaves the frontend "acquired" forever (`FrontendGpuSurface.java:114-125`) and freezes `renderFrame` (`MC/Minecraft.java:1246`); `close()` would then throw too.
- Honor `view.baseMipLevel()` (Vulkan does; GL ignores it).
- Clear the uncovered drawable area when the copy is smaller than the drawable.
- Layer property changes in a `CATransaction` with actions disabled, on the main (render) thread.
- `java-objc-bridge` (`ca.weblite`, used by `B3D/platform/MacosUtil.java`) is far too slow for per-frame use; `setDrawableSize:` takes a `CGSize` HFA that LWJGL's `JNI` helpers cannot express — do it in the shim.

### 6.9 Pacing and bandwidth

- FIFO caps at ≤120 FPS; IMMEDIATE + 3 drawables rarely blocks even at ~1000 FPS [R05]. Keep frames in flight at 2 (§1.5).
- The present copy moves ~47.5 MB per frame at 3024×1964 (23.8 MB read + 23.8 MB write), ~47.5 GB/s at 1000 FPS. Keep the main color target `Private` with usage `RenderTarget | ShaderRead` only, so lossless compression stays on; `framebufferOnly = YES`; `DontCare` load on the drawable.
- Options for capping without vsync semantics: `presentDrawable:afterMinimumDuration:` (classic) / `[drawable presentAfterMinimumDuration:]`; not in "Unlimited" mode.
- Verify with the Metal Performance HUD (`MTL_HUD_ENABLED=1`) whether fullscreen is direct-to-display; measure whether `colorspace = sRGB` prevents it (§10.3).

---

## 7. `DeviceInfo` the Metal backend reports

`DeviceInfo` (`RP/api/device/DeviceInfo.java:5-17`), `DeviceLimits` (`DeviceLimits.java:5-17`), `DeviceFeatures` (`DeviceFeatures.java:3-12`), `HintsAndWorkarounds` (`HintsAndWorkarounds.java:3-5`) [S]. Build once in the constructor; MUST be final before `FrontendGpuDevice` builds `PipelineBuilder`/`GlslCompiler` (`isZZeroToOne`, `shaderDrawParameters` are captured, `RP/frontend/shaders/PipelineBuilder.java:54-58`).

| Field | Value | Reason / effect |
|---|---|---|
| `name` | `device.name` (e.g. `"Apple M5"`) | Logs, crash reports, F3. Keep the "Apple" prefix. |
| `vendorName` | `"Apple"` | |
| `driverInfo` | e.g. `"Metal 4 (macOS 27.0 26A428), MSL 3.2"` | F3 shows the first line. |
| `isZZeroToOne` | **true** | Mandatory (§5). |
| `backendName` | `"Metal"` | Logs, telemetry (`MC/Minecraft.java:748`); the GPU warnlist runs only for `"OpenGL"`. |
| `timestampPeriod` | **1.0f** | Backend converts counter values to ns (§2.13) [P]. |
| `limits.maxAnisotropy` | 16 | `MTLSamplerDescriptor.maxAnisotropy` 1..16. |
| `limits.minUniformOffsetAlignment` | **16** | Validation requires constant-buffer offsets to be a multiple of the bound struct's alignment (offset 4 rejected for a `float4` block, 16/32/64/256 accepted) [P]; std140 blocks never need more than 16. Shrinks UBO ring blocks (`DynamicGpuDataStorageMapped.java:29`): `DynamicTransforms` 160 B instead of 256 B, `ChunkSection` 16 B instead of 256 B. MUST be ≥ 1 (used as divisor). |
| `limits.maxTextureSize` | 16384 | Apple-family 2D limit. |
| `limits.maxMemoryAllocationSize` | `device.maxBufferLength` (13,639 MiB measured [R08]) | Only feeds `maxTextureSizeForFormat` (stays 16384). |
| `limits.maxMultiDrawDirectInterleavedDrawCount` | 0 | Feature reported false. |
| `limits.maxColorAttachments` | 8 | |
| `limits.maxDrawIndirectDrawCount` | `Integer.MAX_VALUE` | Must be > 0 for MDI (`MC/renderer/LevelRenderer.java:178`); the split loop uses `long` math (`ChunkSectionsToRender.java:148-155`) [S]. |
| `features.wireframeFillMode` | true | `MTLTriangleFillModeLines`; otherwise WIREFRAME pipelines are dropped (`PipelineBuilder.java:373-375`). |
| `features.shaderDrawParameters` | true | `[[instance_id]]`/`[[vertex_id]]` include bases; macro unused by vanilla. |
| `features.multiDrawDirectInterleaved` | false | Unused by the game. |
| `features.multiDrawDirectSeparate` | false | Unused by the game. |
| `features.multiDrawIndirect` | **true** | Frontend gate for `drawCount > 1`. |
| `features.drawIndirect` | **true** | |
| `features.nonZeroFirstInstance` | **true** | MDI gate (`LevelRenderer.java:179`); base instance verified [P]. |
| `features.persistentMapping` | **true** | Shared storage; `contents` valid for the buffer's life. |
| `underlyingExtensions` | e.g. `{"MTLGPUFamilyApple10", "MTLGPUFamilyMetal4", "MTL4", "MetalFX"}` | Informational (log, crash report). |
| `hints.writeToBufferIsSlow` | **true** | With `persistentMapping`, selects `StagingBuffer.PersistentlyMapped` (`B3D/vertex/StagingBuffer.java:16-20`): workers memcpy mesh bytes once into a 3×49 MiB Shared ring, uploads become batched GPU copies, and the render thread no longer memcpys every mesh into transient staging. Note Vulkan/MoltenVK reports `false` (`VulkanDevice.java:116`), so Metal is the first macOS backend on this path: see §10.1 C7 for its edge case. |
| `hints.anisotropyHasKnownIssues` | false | `GraphicsPreset` default filter (`MC/GraphicsPreset.java:99-103`). |
| `hints.isExplicitDepthRequired` | **true** initially | Both reference backends set it on Apple silicon (`GlHeuristics.java:114-116`, `VulkanDevice.java:116`); it makes OIT shaders write `gl_FragDepth = gl_FragCoord.z` (`assets/minecraft/shaders/include/oit_add_transmittance.glsl:46-48`, `assets/minecraft/shaders/include/oit_sample.glsl:101-103`), costing early-Z/HSR in OIT passes only. Candidate for false after the invariance experiment (§9.1 B13). |
| `hints.multiDrawIndirectHasKnownIssues` | false | MDI gate. |
| `type` | `DeviceType.INTEGRATED` | F3 suffix "(iGPU)". |

---

## 8. Pitfalls checklist and GL anti-patterns

### 8.1 Correctness pitfalls (merged from all reports; each has bitten or would bite)

1. **Unvalidated release builds.** Missing UBOs/textures/vertex/index buffers reach the backend; bind dummies (§3.8).
2. **`submit()` with a pass open** is not caught by the frontend; throw.
3. **Unbalanced debug groups** make `RenderPass.close()` throw before `submitRenderPass`, leaving the frontend stuck in-pass (`FrontendRenderPass.java:493-502`); the backend must still be closable at shutdown.
4. **Leading null color attachment** NPEs in the frontend (`FrontendCommandEncoder.java:121-124`).
5. **Depth format mismatch**: PSO `depthAttachmentPixelFormat` must equal the pass's; compile variants per signature (§2.5).
6. **Partial render area + Clear**: Metal clears the whole level; use Load + scissored clear quad.
7. **Timestamp index off-by-one** in the frontend (`FrontendCommandEncoder.java:795`, `FrontendRenderPass.java:96`); bounds-check.
8. **Encoder-level `writeTimestamp` while the frontend says "in pass"** (Tracy push): the backend pass does not exist yet; order it before the pass begin.
9. **5-arg `copyTextureToBuffer` is unvalidated** and `copyBufferToTexture`'s range check ignores sx/sy; validate.
10. **Pixel-size alignment for buffer↔texture copies** (offset and bytes-per-row) [P]; keep transient allocations ≥16-aligned.
11. **Write mask bit order** is reversed in Metal.
12. **Write ordering**: never memcpy into a buffer that recorded or in-flight work may read (§2.3 `writeToBuffer`); `ProjectionMatrixBuffer`, `Globals`, chunk instance data and the weather VB are rewritten in place.
13. **Caller buffers are borrowed**: copy `ByteBuffer`/`IntBuffer`/`PointerBuffer` contents and label strings at call time.
14. **Deferred destruction everywhere**, including buffers closed mid-pass (`AutoStorageIndexBuffer` growth closes the old index buffer, `B3D/systems/RenderSystem.java:458-462`), rings retired at `endFrame` (`DynamicGpuDataStorageMapped.java:41-47`), the terrain sampler, pipelines on reload, transient blocks.
15. **Readback callbacks on the render thread only**, FIFO order, after completion.
16. **Fence timeouts are ns**; Metal waits take ms; `-1` = forever; never report "never completes" (`MappableRingBuffer` waits with −1 and would hang).
17. **3 frames in flight stall** inside `MappableRingBuffer` (§1.5).
18. **`getWidth(mip)` not clamped to 1**.
19. **Unsupported formats**: 3-component and D24S8 textures throw IAE.
20. **Texel buffers must be whole-buffer**; cache their texture views.
21. **Triangle fans**: sky uses non-indexed fans every frame; debug fans are indexed (§4.3).
22. **`[[point_size]]`** is mandatory for points.
23. **Index buffer bound whole at offset 0**; never add a slice offset (the game folds it into `firstIndex`).
24. **Null `setVertexBuffer`** keeps the previous binding.
25. **Scissor leakage is expected** (`PreparedRenderType` never disables); `disableScissor` = render area.
26. **Lazy pipeline compiles on the render thread, possibly mid-pass**; `compilePipeline` must never need the render thread.
27. **`GpuTexture.getLabel()` must be non-null**; map `null` labels to `""`.
28. **`createBuffer(data)` mid-pass** must not touch the open encoder.
29. **Autorelease pools** around every Objective-C call; retain drawables explicitly.
30. **No upcalls**, and no ObjC/C++ exception may unwind through an FFM frame (the JVM aborts).
31. **Never mark blocking downcalls `critical`** (`nextDrawable`, waits, submit, compile) — stalls ZGC safepoints.
32. **MTL4**: no automatic hazard tracking, no resource retention, every touched resource in a committed residency set (including `layer.residencySet`), allocators reset only after their command buffers complete, `signalDrawable` exactly once and before `present`, no `setVertexBytes`.
33. **Write-combined memory is write-only from the CPU**; never WC for `MAP_READ` (readback) buffers.
34. **Label suppliers**: evaluate only when debugging (`PreparedRenderType.java:38` concatenates strings per batch).
35. **Workspace trap**: `mc/versions/26.3/natives/` holds x86_64 LWJGL/SDL dylibs (first-file-wins extraction in `tools/mcdl.py:120-128`); never point `java.library.path` there [R08].
36. **Patched jar** must strip `META-INF/MOJANGCS.*` and per-entry digests; a dylib edited after linking must be re-signed ad hoc.

### 8.2 What the GL backend does on macOS that the Metal backend must NOT do [R06]

Apple GL is `4.1 Metal - 91.7` with only `GL_ARB_draw_indirect` and `GL_EXT_texture_filter_anisotropic` usable by RenderPearl, a single-threaded engine, and hidden encoder breaks. GL runs at 223 FPS at 1708×960 and 229 FPS at 3600×2260 (CPU/driver bound; MoltenVK 357–407 FPS; GL 1% low 103 vs 264).

| GL-on-Mac behaviour (cost measured on M5) | Metal rule |
|---|---|
| Every FBO change ends Apple's internal encoder: 12–27 µs (`GlCommandEncoder.java:124-194`) | Fewer, merged render encoders; fold clears; never split a render encoder for an upload. |
| `glBufferSubData` per `writeToBuffer` 15–58 µs; one per rebuilt chunk mesh with `StagingBuffer.Cpu` | Persistently mapped staging + batched blits; idle-buffer memcpy fast path. |
| `glCopyBufferSubData` 50–55 µs ("NEEDS IMPLEMENTATION") ×3–6 per frame (`StagedVertexBuffer.java:164-166`) | One blit encoder per gap between passes; consider the UMA direct-draw game patch. |
| `glClientWaitSync(FLUSH, 0)` polls 27–43 µs | Fence polls are a field compare; never commit to poll. |
| `glTexSubImage2D` into a texture used as a render target of queued work drains the GPU (~30 ms) | Uploads via staging + blit; never `replaceRegion` on possibly in-flight textures. |
| No `ARB_base_instance` → no MDI terrain; one draw + UBO rebind per section layer; `ChunkSection` UBO padded 16→256 B | Claim `nonZeroFirstInstance`, `maxDrawIndirectDrawCount = MAX`; `minUniformOffsetAlignment = 16`. |
| No `ARB_clip_control` → reverse-Z in [-1,1] | `isZZeroToOne = true`. |
| No `ARB_vertex_attrib_binding` → whole VAO respecified per pipeline/vertex-buffer change (0.2–0.3 µs/draw) | Vertex layout lives in the PSO; bind only changed buffers/offsets. |
| Texture views emulated with two `glTexParameteri` per bound texture per draw | Real views created once. |
| `glUseProgram`, `glPolygonMode` re-issued on every pipeline bind; frontend replays all uniforms | Shadow-filter every encoder state change; fixed indices for global UBOs. |
| No `ARB_buffer_storage` → whole-buffer map/unmap per `GpuBuffer.map` + `glGetError` | `map()` is a pointer computation. |
| Fallback transient memory recycles blocks at submit, not at GPU completion (latent race, `GlTransientMemory.java:71-74`) | Retire transient blocks on timeline completion. |
| Compile/link on the render thread in `finishCompile` (`GlDevice.java:355-367`) | Everything in `compilePipeline` on workers. |
| One blend function for all attachments (`GlRenderPipeline.java:65-87`); `glReadBuffer(GL_NONE)` never restored after depth readback (`GlCommandEncoder.java:391-393`); `CacheKey` allocation per FBO lookup | Do not copy these GL-isms. |
| Present = `glBlitFramebuffer` 37–40 µs + `SDL_GL_SwapWindow` | One tiny present pass; late drawable. |

---

## 9. Optimization opportunities (ranked)

Context [R06][R07]: GL is CPU/driver bound (~4.4 ms/frame, resolution-independent). MoltenVK reaches 357–407 FPS with the render thread mostly *waiting* in `vkQueueSubmit2KHR` (1034/1063 native samples) while the JVM uses ~0.3 cores — GPU- or drawable-bound at native resolution. Java-side frame work is roughly 0.1–0.3 ms today. ~1000 FPS = 1 ms for everything, so both GPU bandwidth per frame and CPU encode cost must fall, and Java game work becomes the next wall.

Default frame inventory (RD 12, classic transparency, no post chain; [R07] §3.2, [R04] §9.2, verified clear sites [S]):

| # | Operation | Attachments |
|---|---|---|
| C1 | `clearColorAndDepthTextures(main)` (`MC/renderer/GameRenderer.java:479`) | main C+D |
| — | `writeToBuffer(Globals)`; lightmap pass at ~20 Hz | — |
| C2 | frame-graph clear, fog color + depth 0 (`MC/renderer/LevelRenderer.java:254`) | main C+D |
| P1 | "Sky" (`MC/renderer/SkyRenderer.java:136`, Load) | main C+D |
| P2 | "Main": terrain, features, translucent, clouds, weather, border (`LevelRenderer.java:445`) | main C+D |
| P3–5 | optional Outline / See-through / Always-on-top (`:480,495`) | various |
| C3 | `clearDepthTexture` (`GameRenderer.java:690`) → P6 "Item in hand" (`:706`), P7 "Screen effects" | main C + D |
| C4 | `clearDepthTexture` (`GameRenderer.java:507`; also `MC/gui/render/GuiRenderer.java:200` before "after blur") → P8 "GUI" (`GuiRenderer.java:212`) | main C + D |
| B | present | drawable |

Each main-target load+store of RGBA8+D32 at 3024×1964 moves ~47 MB before lossless compression.

### 9.1 Backend-side (no game change needed)

| Rank | Item | Expected impact | Notes / rules |
|---|---|---|---|
| B1 | **Native command stream, one downcall per flush/submit** (§1.7) | Mandatory for ~1 ms frames: 2–10k backend calls/frame [R04] become ~1–3 downcalls | Verified 0.21 ms for ~4000 draws including encode+commit [R08]. |
| B2 | **MDI terrain**: claim the features (§7), implement `drawIndexedIndirect` | High CPU: 1–2k section-layer draws per frame at RD 12 (ring growth to 1024 sections / 2048 commands in the run logs [R07]) collapse to ~3–8 indirect calls | M1: loop of indirect draws or CPU-decoded direct draws (records are final at decode time). M4: `MTLIndirectCommandBuffer` (`commandTypes = DrawIndexed`, `inheritPipelineState = YES`, `inheritBuffers = YES`; pipelines compiled with `supportIndirectCommandBuffers`), filled by a compute kernel encoded *before* the pass (needs stream look-ahead), then one `executeCommandsInBuffer:withRange:` per draw group; index buffers via `useResource`/residency. Validate base vertex/instance in ICB draws first [O]. **Done (M2 round 3, CONTRACT.md 16.5):** CPU-encoded ICBs cached by record bytes (no look-ahead or compute pass needed: the records are final at decode time), ICB pipeline variants with argument-buffer textures; GPU-neutral, ~70 % less decoder CPU for terrain. |
| B3 | **Clear folding** | High GPU (TBDR): removes C1/C2/C3/C4 store/load round trips | Record clears as `PendingClear(texture, mip, value)`. Next use of that (texture, mip): a pass attaching it with Load over the full area → `loadAction = Clear` with the pending value; a pass with its own clear value → drop the pending clear; anything else (sampling, copy source/destination, readback, present, region clear, partial write) → materialize as an empty clear pass first. A later clear overrides an earlier one. Materialize leftovers at `submit()`. C1+C2+P1 become one clear-load; C3 → P6 depth clear; C4 → P8 depth clear. |
| B4 | **Pass merging (lazy encoder end)** | High GPU | At decode, keep the `MTLRenderCommandEncoder` open across `END_PASS`/`BEGIN_PASS` when: identical attachments (textures, levels, count, order); the new pass loads everything over the full area (or its clears were already folded); no intervening stream op touches those attachments (writes to *other* resources may be hoisted, B6); no TIMESTAMP between; the new pass does not sample or copy any attachment (the decoder sees the whole pass's bindings before deciding). Reset scissor to the render area and the "set" masks; keep PSO/binding shadow state. Candidates: Sky→Main, Hand→Screen effects, GUI before/after blur. Debug labels become nested groups. |
| B5 | **Store actions decided late** | Medium GPU | Create attachments with `MTLStoreActionUnknown` and set `setColorStoreAction:`/`setDepthStoreAction:` at encoder end: `DontCare` when the next recorded use of that attachment in the same frame is a full clear or full overwrite with no read in between (main depth before C3/C4). Without a game hint, never `DontCare` across `submit()`. |
| B6 | **Upload hoisting + batched blits** | Medium | All transfer ops between two passes share one blit encoder (Vulkan barriers after every copy). A copy/write between passes MAY be hoisted before the previous (still open) pass when its destination is not referenced by any command between the hoist point and its original position; this unblocks Sky→Main (`prepareTranslucents` writes the weather and world-border buffers between them, `LevelRenderer.java:439,532-554`). |
| B7 | **Binding dedupe and fixed global slots** (§3.2–3.5) | High CPU for entities/GUI | Java drops exact repeats; native shadow filter; `set*BufferOffset` for offset-only changes; per-stage used masks. |
| B8 | **Zero-copy memory**: Shared buffers, `map()` = pointer, `createBuffer(data)` = memcpy, `writeToBufferIsSlow = true` + `persistentMapping = true`, `writeToBuffer` idle fast path | Medium CPU, removes a render-thread memcpy of all chunk mesh bytes | §2.2, §2.3. Optional buffer renaming: for small whole-buffer `writeToBuffer` on buffers recorded this frame (Projection, Globals, Lighting), repoint the `GpuBuffer`'s backing to a fresh ring allocation (already-recorded commands keep the old backing); removes blits between passes, which also enables B4. |
| B9 | **Pipelines off-thread + caches** | Hitch removal | Everything in `compilePipeline`; dedupe by SPIR-V hash; `MTLBinaryArchive` (classic) or `MTL4Archive` + `MTL4PipelineDataSetSerializer` (MTL4); the OS cache under `$(getconf DARWIN_USER_CACHE_DIR)/net.java.openjdk.java/com.apple.metal/` helps second launches [R08]. Metal 4 flexible (unspecialized blend) pipelines: compile 125 programs once, specialize 175 states [O for gains]. |
| B10 | **Unretained command buffers + untracked hazards + fence chain** (classic), or **MTL4** with residency set and conservative barriers | Medium–High CPU at ~1000 FPS | §2.3.11. Requires the deferred FIFO (already mandatory). |
| B11 | **Late drawable + single present pass + IMMEDIATE + 3 drawables + early commit** | Removes frame-start stalls | §6. |
| B12 | `minUniformOffsetAlignment = 16` | Smaller UBO rings, better cache use | §7 [P]. |
| B13 | **`isExplicitDepthRequired = false`** after an invariance experiment | Restores early-Z/HSR in OIT passes (only with "Improved Transparency") | Decorate the position builtin `Invariant` via `spvc_compiler_set_decoration`, `MSL_INVARIANT_FP_MATH = true`, `MTLCompileOptions.preserveInvariance = YES`; compare OIT and EQUAL-depth pipelines (`glint`, `armor_decal_cutout_no_cull`) against Vulkan. |
| B14 | Cheap fences (`completedSeq` compare), cached `DeviceInfo`, allocation-free `map()`, pooled `MTLBuffer`s, cached texel views, deduped samplers/depth-stencil states, real texture views | Low each, adds up | `FrontendRenderPass.setUniform` calls `getDeviceInfo()` per uniform (`:151`); `DynamicGpuDataStorageMapped` maps per transform write (`:75-77`). |
| B15 | Private render targets with only `RenderTarget \| ShaderRead` usage | GPU bandwidth via lossless compression | Avoid `ShaderWrite`/`PixelFormatView`. |
| B16 | Async native encoding (encoder thread consuming the stream) | High when CPU-bound after B1–B7 | Overlaps 0.1–0.3 ms of encode with the next frame's Java work; needs careful lifetime/timeline handling. |

### 9.2 Game-side (patches to vanilla), ranked by expected FPS impact

| Rank | Change | Where | Impact | Effort |
|---|---|---|---|---|
| G1 | **Render scale + MetalFX spatial upscale** (world at 0.67–0.75, GUI native; or a simple "Retina off" that drops `HIGH_PIXEL_DENSITY` for 4× fewer pixels) | Framebuffer sizing `B3D/platform/Window.java:373-431,164-165`; main target `MC/renderer/GameRenderer.java:178,470-475,742-750`; IME density `B3D/platform/TextInputManager.java:24`; backend: `MTLFXSpatialScaler` before the present pass, layer `drawableSize` = window pixels | High GPU (2–4× fewer pixels at 3024×1964) | Medium |
| G2 | **Express clears as pass load ops and restructure passes**: drop C1 when the level renders (C2 clears again); pass the hand/GUI depth clears as descriptor clear values; draw the sky inside the Main pass | `GameRenderer.java:477-481,507,690,706`; `GuiRenderer.java:200,212`; `LevelRenderer.java:247-258`; `SkyRenderer.java:136` | High GPU; makes B3/B4 deterministic | Low |
| G3 | **Persistent terrain draw list** (store allocation offsets and draw records on `CompiledSectionMesh`; rebuild only when visibility, meshes, translucent order or fades change; remove per-frame allocations) | `LevelRenderer.java:722-964` (e.g. `ChunkSectionLayer.values()` clone at `:744`); `SectionRenderDispatcher.getRenderSectionSlice` `:111-130` | Medium–High CPU: ~25–30% of render-thread Java samples plus the top allocation sites [R07] | Medium |
| G4 | **Frontend: skip identical `setPipeline` and the full uniform replay; index-based uniform setters; cache `DeviceInfo`** | `RP/frontend/FrontendRenderPass.java:104-128,150-172`; callers `PreparedRenderType.java:37-56`, `GuiRenderer.java:374-402` | Medium–High CPU | Low |
| G5 | **Allow 3 frames in flight**: move `RenderSystem.getDynamicUniforms().reset()` and `levelRenderer.endFrame()` before `submit()` (so their fences capture S, not S+1), or raise `MappableRingBuffer.BUFFER_COUNT` to 4 | `MC/Minecraft.java:1337,1348-1349`; `MC/renderer/MappableRingBuffer.java:11` | Medium (absorbs GPU jitter at ~1 ms frames) | Trivial |
| G6 | **Unique locations for shadowed pipelines** (`item_cutout` ×3, `item_translucent_glint` ×2 compile synchronously on first item render) and **async precompile of post chains** (blur in menus) | `MC/renderer/RenderPipelines.java:696-717,730-744`; `MC/renderer/PostChain.java:111` | Removes hitches | Trivial–Low |
| G7 | **Remove writes between passes**: Globals, level projection, Lighting, chunk-section instance data and weather VB to mapped rings (`MAP_WRITE`) instead of `writeToBuffer` | `MC/renderer/GlobalSettingsUniform.java:14,41`; `MC/renderer/GameRenderer.java:665`; `MC/renderer/ProjectionMatrixBuffer.java:22-50`; `B3D/platform/Lighting.java:63-67`; `MC/renderer/DynamicGpuData.java:100-111`; `MC/renderer/WeatherEffectRenderer.java:109-142` | Medium; enables B4 merges | Low–Medium |
| G8 | **UMA-direct feature uploads**: draw `StagedVertexBuffer` data straight from the mapped staging buffers; stop destroying unused pooled buffers every frame | `MC/renderer/StagedVertexBuffer.java:126-168,350-363` | Medium with many entities/particles/GUI | Low |
| G9 | **`UberGpuBuffer` free-range quarantine** (no reuse for N frames) → memcpy chunk meshes straight into the Shared heap buffer, removing the 98 MiB staging path and its copies | `B3D/vertex/UberGpuBuffer.java:57-128`; `SectionRenderDispatcher.java:65-75` | Medium (chunk-loading spikes) | Medium |
| G10 | **Compact terrain vertex format** 28 B → 16–20 B (u16 fixed-point position, u16 UV, u8 light); 16-bit quad index buffer per draw (clouds currently force INT) | `B3D/vertex/DefaultVertexFormat.java:27-32`; `assets/minecraft/shaders/core/terrain.vsh:33`; `B3D/systems/RenderSystem.java:439-469` | Medium GPU bandwidth + upload volume | Medium |
| G11 | **"Discard after pass" / transient-attachment hint** in `RenderPassDescriptor` (main depth DontCare after the GUI; memoryless OIT internal targets) | `RP/api/commands/RenderPassDescriptor.java`; `LevelRenderer.java:217-240` | Medium GPU | Low–Medium |
| G12 | **Entity occlusion via section visibility** (bitset over `RenderSection.index`) | `MC/renderer/extract/LevelExtractor.java:291-309` | Medium in mob-heavy/cave scenes | Low |
| G13 | **Region copies off the render thread** (1%/0.1% lows) | `MC/renderer/extract/LevelExtractor.java:151-187`; `MC/renderer/chunk/RenderRegionCache.java:13-42`; `MC/renderer/chunk/SectionCopy.java:26-41` | 1% lows | Medium |
| G14 | **Replace triangle fans with lists** (sky disc, sunrise, debug) | `MC/renderer/SkyRenderer.java:88,95,340`; `MC/renderer/RenderPipelines.java:967-980,1040,1066` | Removes emulation path | Trivial |
| G15 | **Metal backend registration**: `PreferredGraphicsApi.METAL` (+ lang key `options.graphicsApi.metal`, try order `[metal, gl, vulkan]`, DEFAULT tries Metal first on macOS), crash recovery for METAL like VULKAN, skip the MoltenVK probe when Metal is chosen | `MC/PreferredGraphicsApi.java:10-37`; `MC/Minecraft.java:465-474,478-480`; `MC/main/Main.java:82-84` | Required wiring; probe removal saves startup time | Trivial |
| G16 | Render thread at `QOS_CLASS_USER_INTERACTIVE` (set from the shim at device init); workers lower | — | Keeps the render thread on P-cores [O] | Trivial |
| G17 | Skip `gameRenderer.render()` when the surface is not acquired; throttle on `SDL_EVENT_WINDOW_OCCLUDED` (534, unhandled) | `MC/Minecraft.java:1308-1313`; `B3D/platform/Window.java:195-246` | Power/thermals | Low |
| G18 | Small Java wins: per-draw `pushDebugGroup` lambdas, `slice()` records, `HashSet` per frame in `preparePostEffects`, quadratic `RenderTypeFeatureRenderer.prepareGroup` and its `indexOf` | `MC/renderer/rendertype/PreparedRenderType.java:38`; `MC/renderer/GameRenderer.java:521`; `MC/renderer/feature/RenderTypeFeatureRenderer.java:38-42,100` | Low each, less GC | Low |
| G19 | One `drawIndexedIndirect` per terrain layer (one uber vertex buffer per layer) | `LevelRenderer.java:722-808` | Low–Medium CPU | Medium |
| G20 | `terrain.fsh` define-based variants per texture-filtering option instead of the runtime `UseRgss` branch | `assets/minecraft/shaders/core/terrain.fsh:36`; `assets/minecraft/shaders/include/texture_sampling.glsl` | Low–Medium GPU occupancy | Low |
| G21 | Double-buffered `GameRenderState` + backend encoder thread (overlap extract N+1 with encode N) | `MC/renderer/GameRenderer.java:442-518` | High when CPU-bound | High |
| G22 | Render the final GUI pass straight into the drawable (`GpuSurface.currentTextureView()` API extension) | `MC/Minecraft.java:1316-1323`; `GuiRenderer.java` | Saves the ~47 MB/frame present copy | High risk. **Done backend-side without an API change**: decoder present fusion with a vertically mirrored layer (`docs/metal/CONTRACT.md` §16.3, `MCMTL_PRESENT_FUSION`) |
| G23 | User-facing defaults for max FPS: vsync off + Max Framerate 260; "Improved Transparency" off (it adds ~10 passes and RGBA32F/16F MRT traffic); lower `cloudRange` than 128 chunks or FAST clouds; `cutoutLeaves = false` | `MC/Options.java:119-130,192-197,224-226,515` | Situational | Trivial |

---

## 10. Open questions, conflict resolution and completeness gaps

### 10.1 Conflicts between reports, resolved

| # | Topic | Positions | Resolution | Evidence |
|---|---|---|---|---|
| C1 | `minUniformOffsetAlignment` | 01: 256 ("Apple docs require 256 on macOS"); 02/03: 16 or 256; 06: 16–32 after verification; 07: 16; 04: smallest legal | **16** | [P] Under `MTL_DEBUG_LAYER=1` a `constant` float4 block at offset 4 fails ("offset … must be a multiple of 16"), offsets 16/32/64/256 pass. std140 blocks never need more than 16. |
| C2 | Frames in flight | 01, 03: 3 is safe/better; 05, 07: 2 | **2** (3 needs game patch G5) | [S] `MappableRingBuffer.java:36-54` + `Minecraft.java:1337,1348-1349`: post-submit rotations capture fence S+1; slot reused at S+3; with 3 in flight the render thread blocks every frame. |
| C3 | `writeToBufferIsSlow` | 01, 06, 07: true; 04: false; 03: benchmark | **true** | Removes a render-thread memcpy per mesh and unbounded transient staging growth; staging is preallocated (3×49 MiB). Edge case in C7. |
| C4 | Waiting with a timeout on a fence of the current submit | 01, 03, 04: throw (GL/VK parity); 07: must flush | **Parity required; flush extension recommended** (§1.5) | [S] `VulkanCommandEncoder.java:843-853`, `GlCommandEncoder.java:85-95` throw; the flush variant is a strict superset and fixes C7. |
| C5 | How triangle fans are drawn | 02: all fan pipelines via `drawIndexed` + `sharedSequential`; 04: sky via `draw(10,1,0,0)` | **Both**: sky/sunrise are non-indexed every frame; debug fans are indexed | [S] `MC/renderer/SkyRenderer.java:166-167,180-181,303-304`; `B3D/systems/RenderSystem.java:300-307`; `MC/renderer/rendertype/RenderTypes.java:520-522`. |
| C6 | Push-constant buffer slot | 02: 14; 04: "e.g. 29" with vertex buffers at `30−slot` | **14** | 29 = vertex slot 1 = `CHUNK_DATA_INSTANCED` (`B3D/vertex/DefaultVertexFormat.java:33-36`); collision. |
| C7 | Persistently mapped staging robustness (new) | not covered | Up to 2 rotations per frame are safe (the second may block on frame S−1, the next frame's may drain the GPU); a 3rd rotation in one frame makes `currentBuffer()` wait with −1 on the current submit → throws under vanilla semantics. Implement the §1.5 flush extension. | [S] `B3D/vertex/StagingBuffer.java:44-56,144-151`; extra uploads on the render thread when staging is full, `SectionRenderDispatcher.java:393-395`. Rare (needs ~150 MiB of synchronously compiled meshes in one frame). |
| C8 | Timestamp period/domain | 01: `1e9/frequency`; 08: 41.6667; 04/06: calibrate | **Convert to ns in the backend, report 1.0** | [P] MTL4 counter heap = 24 MHz ticks; classic counter samples and `sampleTimestamps` = ns. Tracy multiplies by a float period (`TracyGpuProfiler.java:25,62`): 41.666668f × ~3.7e13 ticks ≈ 46 ms error. |
| C9 | When to call `nextDrawable` | 08: in `acquireNextTexture` (+`waitForDrawable`); 01/05/06/07: lazily | **Lazily, during decode of the present** | `acquireNextTexture` runs before all CPU work (`Minecraft.java:1273`). |
| C10 | `indexBufferOffset` alignment | 01/04: open (4 bytes on macOS?) | **Multiple of the index size** | [S] `$MTL/MTLRenderCommandEncoder.h:729,741,763,785`; [P] UInt16 offsets 2 and 6 pass validation. |
| C11 | Blit alignment | 03/04: macOS 4-byte rule for buffer copies | Buffer→buffer: **no restriction observed** on M5 (1-byte offset/size pass); buffer↔texture: offset and bytes-per-row must be **multiples of the pixel size** | [P] |
| C12 | Per-instance fetch with `baseInstance` | 04/07: open | **Applied** for `drawPrimitives`, `drawIndexedPrimitives` and indexed indirect draws | [P] instance attribute index 5 read with `baseInstance = 5` in all three, and in ICB `drawIndexedPrimitives` commands (U4). |
| C13 | Y-flip technique | 01: negative viewport or shader; 02: shader flip verified; 03/04/06: either | **Shader `FLIP_VERTEX_Y` + Clockwise**; flip at present | [R02] verified winding/rows; [P] negative viewport passes validation but orientation untested. |
| C14 | Render passes per frame | 01: "about 20–30"; 04: 6–10; 07: ~8 | **~8** with defaults (plus 4 standalone clears, 1 present) | [S] clear/pass sites listed in §9; 01 counted optional/OIT passes. |
| C15 | Loading spvc | 05: must load unconditionally; 01/08: lazy load suffices | **No change needed** | The frontend already uses `Spvc` for reflection regardless of backend (`RP/frontend/shaders/SPIRVModule.java:71,97`); LWJGL loads it on first class use. |
| C16 | Java→ObjC per-call cost | 06: ~20–40 ns; 08: 4–6 ns measured | **08's measurement** (and it is moot with the command stream) | [R08] benchmarks. |
| C17 | Classic Metal vs Metal 4 first | 01/03/04/05: classic first; 08: Metal 4 with classic oracle | **Phased**: classic decoder (M1) → MTL4 decoder (M3), same Java side and stream | §1.8. |
| C18 | Binding slot scheme | 02: dense per kind (verified); 04: name-stable globals | **Hybrid**: fixed indices for hot global names, dense for the rest | §3.2. |
| C19 | Storage for never-mapped uber heaps | 07: Private or `MTLHeap`; 01/03: Shared | **Shared** by default (UMA, no init blits, enables G9); Private optional | Buffers get no lossless compression; Shared has no GPU read penalty on Apple silicon [R03]. |
| C20 | `isSuboptimal()` | 01: true when `drawableSize` ≠ window pixels; 05: false or edge-triggered | **Edge-triggered**, cleared by `configure` | Never compare against window size (render scale); never permanently true (`Minecraft.java:1254`). |
| C21 | `maxDrawIndirectDrawCount` value | 07: ≥65,536; others: `Integer.MAX_VALUE` | **`Integer.MAX_VALUE`** | [S] `ChunkSectionsToRender.java:148-155` uses `long` lengths. |
| C22 | Layer colorspace | 01: sRGB or nil; 05: sRGB | **sRGB** (MoltenVK parity), nil as an option if it blocks direct-to-display | [O] U7. |
| C23 | Metal 4 `waitForDrawable` placement | 08: right after acquire; 05: just before committing a small present command buffer | **05** | Keeps the frame's GPU work from waiting on the display. |
| C24 | SDL auto-resize hint | 05: optional `"0"` | **`"0"`**, backend owns `drawableSize` | Deterministic sizes; required for render scale. |

### 10.2 Statements in the reader reports that this spec supersedes

- 01 §14.2 and §5.4/§16 item 5 (256-byte alignment; 3 frames in flight), 01 §16 item 3 ("20–30 passes").
- 02 §14.4 ("all 6 fan pipelines draw `drawIndexed` with `sharedSequential`" — the sky draws non-indexed).
- 03 §12 item 9 (3 frames in flight "safe"), 03 §11.3 (macOS 4-byte buffer-copy rule; not enforced on M5).
- 04 §8 (`writeToBufferIsSlow` false), 04 §10.5 (push constants at slot 29).
- 06 §12.7 (20–40 ns per `objc_msgSend`).
- 07 §1.2 ("a blocking wait on an unsubmitted fence must flush" is an extension, not current behaviour).
- 08 §7.8 (report 41.6667 — correct only for raw MTL4 ticks), 08 §8.7 (eager acquisition).

### 10.3 Still unresolved (and how to resolve)

| # | Question | Why it matters | How to resolve |
|---|---|---|---|
| U1 | Can `isExplicitDepthRequired` be false on native Metal? The Apple-silicon artifact behind the `gl_FragDepth = gl_FragCoord.z` workaround is undocumented in source. | Early-Z/HSR in OIT passes. | B13 experiment; compare OIT and EQUAL-depth pipelines against Vulkan screenshots. |
| U2 | MSL 4.0 vs 3.2 with `MTL4Compiler`/argument tables; does SPIRV-Cross 6c09849f emit valid MSL 4.0? | M3 decoder. | Compile the 13 probe shader pairs with `MSL_VERSION = 40000`; bind via argument tables. |
| U3 | MTL4 encoder-level `writeTimestampWithGranularity` domain and the 0 value from an empty encoder | Tracy GPU zones under MTL4. | Calibration test with non-empty encoders; prefer command-buffer-level writes. |
| U4 | ICB with `inheritBuffers` + per-command `baseVertex`/`baseInstance`; max commands; CPU vs GPU encode cost at ~2k draws | B2 final form. | **Answered (M5, macOS 27; `tools/terrain-icb-probe.sh`, CONTRACT.md 16.5).** Per-command `baseVertex` (negative too), `baseInstance` (per-instance fetch at `baseInstance + i`, `instanceCount` 2), odd 16-bit `indexBufferOffset`s, `reset` commands inside an executed range: byte-identical to direct draws under the debug layer, CPU- and compute-encoded. **Catch: an ICB-capable PSO (`supportIndirectCommandBuffers`) refuses shaders that bind textures/samplers directly** ("Fragment shader cannot be used with indirect command buffers"): textures must come from an argument buffer, so the decoder compiles an ICB form of the terrain shaders (MSL rewrite). Costs: `executeCommandsInBuffer` ≈ 0.6 µs CPU + 0.25–0.3 µs GPU per call; per command ≈ 12 ns more GPU than a direct draw (≈ 11 ns); CPU encode ≈ 26 ns/command (CPU), compute encode ≈ 7 µs per frame; ICB creation 0.05–0.4 ms (pool them). Shipped: CPU-encoded, cached by record bytes, for groups of ≥ 64 records. |
| U5 | Does `nextDrawable` throttle above refresh with `displaySyncEnabled = NO` in windowed mode? | Whether a presenter thread is needed for ~1000 FPS. | Measure with the Metal HUD. |
| U6 | Is the 405 FPS MoltenVK baseline GPU-bound or drawable-bound? | Weights B3/B4 vs B1/B2. | Metal System Trace of the Vulkan build. |
| U7 | Does `colorspace = sRGB` disable direct-to-display in fullscreen? Color parity of nil vs sRGB with GL output. | Latency, visual parity. | Metal HUD + screenshots. |
| U8 | Lossless compression on render targets created with all usages (COPY_*) | Size of B3/B4/B15 wins. | GPU counters in Instruments. |
| U9 | Exact per-frame backend call counts on both terrain paths | Validates the 2–10k estimate. | Counting decorator around the backend in a bench build. |
| U10 | Any pass that samples its own attachment | Merge safety (the decoder checks anyway). | Decoder assertion in debug builds. |
| U11 | Do Apple GPUs skip load/store for untouched tiles (atlas animation passes)? | Atlas pass cost. | GPU counters. |
| U12 | Runtime correctness of RGBA32Float MAX blending and Depth32Float linear filtering | OIT depth bounds; post effects. | PSO builds [R02]; visual test pending. |
| U13 | Render-thread QoS under `-XstartOnFirstThread`; E-core migration | G16. | `taskinfo`/Instruments. |
| U14 | Write-combined vs default cache mode for MAP_WRITE rings on M5 | Minor CPU. | Microbenchmark. |
| U15 | Do vanilla visuals rely on zero-initialized textures (atlas gaps)? | Zero-fill policy (§2.2). | Compare with zero-fill disabled. |
| U16 | Fullscreen size on the notched panel (3024×1964 vs 3024×1890); `2.00x` pixel density on macOS 27 | Pixel budget. | F3 on device. |
| U17 | Game Mode for a plain `java` process; ProMotion `refresh_rate` reported by SDL 3.4.14 | Pacing/thermals, exclusive fullscreen list. | Device test. |
| U18 | LWJGL `SharedLibraryLoader` up-to-date check (`.sha1`) for in-place shim upgrades | Dev workflow. | Ship a `.sha1` or use `SharedLibraryExtractForce` while developing. |

### 10.4 Completeness gaps closed by this synthesis (not in any single report)

1. Post-submit ring rotations capture fence index S+1 → the real reason 3 frames in flight stall (§1.5, C2).
2. `StagingBuffer.PersistentlyMapped` third-rotation-per-frame failure mode when `writeToBufferIsSlow = true` (C7) and the flush-on-wait fence extension.
3. `createBuffer(data)` and closing of the previous index buffer can happen **inside the Sky pass** (`SkyRenderer.java:219` → `RenderSystem.java:439-469`); lazy pipeline compiles can block the render thread mid-pass on `Util.backgroundExecutor()` (`PipelineCache.java:33`), so `compilePipeline` must never depend on the render thread.
4. Timestamp clock domains differ between MTL4 heaps (ticks) and classic samples/`sampleTimestamps` (ns), and Tracy's float period makes tick reporting inaccurate (C8); a Relaxed timestamp in an empty MTL4 encoder resolves to 0.
5. Buffer↔texture blits require pixel-size-aligned offsets and row pitches; `CubeMapTexture`/`SpriteContents` request transient alignment 1, so the Metal transient allocator must force ≥16.
6. Texel-buffer views need `bytesPerRow` rounded to 16 → allocate `UNIFORM_TEXEL_BUFFER` buffers rounded up natively.
7. `GpuTexture.getLabel()` must be non-null even when the frontend passes a null label.
8. Shutdown ordering: `Util.shutdownExecutors()` runs after `DEVICE.close()`, so late `compilePipeline` calls must be tolerated (`Pending.NULL`).
9. Present samples any COPY_SRC texture → COPY_SRC must imply `ShaderRead` usage.
10. Classic counter sampling on M5 supports only stage boundaries (draw/blit/dispatch boundaries unsupported) [P].

---

## Appendix A — Synthesis probes (session scratchpad, throwaway)

Location: `<temp>` (`probe.m`, `ts4.m`, `ts3.m`, `b2t.m`), built with `clang -fobjc-arc` and run with `MTL_DEBUG_LAYER=1` on the M5 / macOS 27. Nothing in the project or game was modified or run.

| Probe | Result |
|---|---|
| `probe cbuf N` (vertex+fragment `constant float4` block at offset N) | N = 4 → validation failure "offset … must be a multiple of 16"; N = 0, 16, 32, 64, 256 → OK. |
| `probe idx N` (`drawIndexedPrimitives`, UInt16, `indexBufferOffset` N) | N = 2, 6 → OK. |
| `probe blit N` (`copyFromBuffer:toBuffer:` offset/size N) | N = 1, 2 → OK. |
| `b2t` (buffer↔texture copies) | R8: offset 1/3, row pitch 3/5 → OK. RGBA8: offset 2 → "sourceOffset (2) must be a multiple of 4 bytes"; row pitch 14 → "must be a multiple of 4 bytes"; offset 4 / pitch 12 → OK. |
| `probe negvp` (viewport height −4) | Passes validation (orientation not checked). |
| `probe baseinst/baseinstidx/baseinstind 5` | Per-instance attribute fetched at instance 5 for direct, direct indexed and indexed-indirect draws. |
| `probe ts` | `sampleTimestamps` CPU and GPU values identical, deltas in **ns** (206 ms of sleep → 2.06e8). |
| `ts4` (MTL4) | `queryTimestampFrequency = 24000000`; `writeTimestampIntoHeap` entries in mach ticks (≈ `mach_absolute_time`); a Relaxed `writeTimestampWithGranularity` in an empty compute encoder resolved to 0. |
| `ts3` (classic) | Counter sampling: stage boundary = 1, draw/blit/dispatch boundary = 0; sample values in **ns** (match `GPUStartTime × 1e9`). |
