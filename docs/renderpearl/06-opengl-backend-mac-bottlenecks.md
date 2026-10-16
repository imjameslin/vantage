# 06: The OpenGL backend as reference, and why it is slow on macOS

Scope: every file in `com/mojang/renderpearl/backend/opengl/` (all 29 files read), plus enough of the frontend
(`frontend/*`, `backend/api/*`, `backend/util/TransientBlockAllocator.java`) and game code to explain what the GL
backend is asked to do each frame. All source paths are relative to `src/vanilla`.

Evidence used, in order of strength:

1. Static reading of the decompiled 26.3 source (file:line cited everywhere).
2. Existing benchmark logs on this machine (`run/vanilla/logs/2026-10-04-{1,2,3}.log.gz`, `run/vanilla/logs/latest.log`,
   `run/smoke/logs/latest.log`, `results/raw/smoke.json`).
3. Offscreen CGL microbenchmarks that I compiled and ran on this machine for this document (Apple M5, macOS 27.0 build
   26A428, `GL_VERSION = "4.1 Metal - 91.7"`, single-threaded GL engine). Appendix A describes the method. No game code was
   run or modified.

---

## 0. TL;DR

* Apple's OpenGL on this M5 is `4.1 Metal - 91.7`, a GL-to-Metal translation layer. RenderPearl requests a 3.3 core,
  forward-compatible context and gets 4.1. Of the extensions RenderPearl looks for, it enables only two:
  `GL_ARB_draw_indirect, GL_EXT_texture_filter_anisotropic` (log line in `run/vanilla/logs/2026-10-04-1.log.gz`).
* So every modern GL path falls back:
  * no `ARB_buffer_storage`, so buffers are mutable and unmapped, and each CPU write is a map/unmap pair;
  * no DSA, so every buffer or FBO edit is a bind/op/unbind sequence;
  * no `ARB_vertex_attrib_binding`, so the full VAO is respecified on every vertex-buffer or pipeline change;
  * no `ARB_base_instance`, so the game turns off multi-draw-indirect terrain and issues one draw per chunk section
    layer, each with its own UBO rebind;
  * no `ARB_clip_control`, so reverse-Z runs in [-1,1] and loses most of its precision benefit.
* The GL backend is CPU-bound on macOS. It runs at 223 FPS at 1708x960 and 229 FPS at 3600x2260, so resolution has no
  effect. Vulkan via MoltenVK runs at 357 to 407 FPS on the same machine and scene (section 8.3).
* Draw calls are not the main cost. A bare GL draw costs about 75 ns and a terrain draw about 0.3 µs. The main cost is
  Apple GL's hidden encoder breaks and command-buffer flushes (section 7):
  * each FBO change costs about 12 to 27 µs of CPU;
  * each `glBufferSubData` costs about 15 to 58 µs;
  * each `glCopyBufferSubData` costs about 50 to 55 µs, and the driver logs
    `UNSUPPORTED (log once): gldCopyBufferSubData: NEEDS IMPLEMENTATION`;
  * each `glBlitFramebuffer` costs about 37 to 40 µs;
  * each `glClientWaitSync` with the flush bit costs about 27 to 43 µs when work is pending;
  * a `glTexSubImage2D` into a texture still being rendered to blocks until the GPU drains (30 ms in the test).
* The game uploads each rebuilt chunk mesh with its own `writeToBuffer`, which becomes `glBufferSubData` on Mac. This
  is the most likely cause of the 1% lows (103 FPS GL vs 264 FPS Vulkan).
* Per draw on Apple GL:
  * terrain: about 0.3 µs (`glBindBufferRange` plus `glDrawElementsInstancedBaseVertex`);
  * entity, feature or GUI draws: about 1 to 2 µs. These include a pipeline switch, a VAO respecification, and texture
    views emulated with two `glTexParameteri` calls per bound texture.
* Implications for the Metal backend:
  * Report `persistentMapping`, `nonZeroFirstInstance`, `drawIndirect`, `multiDrawIndirect`, `shaderDrawParameters` and
    `isZZeroToOne` as true.
  * Never break a render encoder or commit a command buffer for an upload.
  * Use one command buffer per frame.
  * Fold standalone clears into load actions.
  * Acquire the drawable late and present through the command buffer.
  * Keep JNI or FFM crossings per draw to a handful. Section 12 has the full list.

---

## 1. What Apple's OpenGL exposes on this machine

### 1.1 Context creation path

* `GlBackend.createWindow` (`GlBackend.java:60-67`) sets these SDL3 GL attributes before `SDL_CreateWindow`:
  * `17` (CONTEXT_MAJOR_VERSION) = 3 and `18` (MINOR) = 3;
  * `20` (PROFILE_MASK) = 1 (core);
  * `19` (CONTEXT_FLAGS) = 2 (forward-compatible);
  * `22` (FRAMEBUFFER_SRGB_CAPABLE) = 1;
  * window flag `2` = `SDL_WINDOW_OPENGL`.

  macOS returns its 4.1 core context for any 3.2+ core request.
* `GlBackend.loadLibrary` (`GlBackend.java:27-49`) loads the GL library through SDL. It then checks that LWJGL's
  `glGetError` address equals SDL's (`:41`), which guards against mismatched libGL.
* `GlDevice` constructor (`GlDevice.java:70-177`):
  * creates a hidden 320x480 utility window with flags `0x80020008` (NOT_FOCUSABLE | UTILITY | HIDDEN) to own the
    context (`:71`), then creates the context (`:79`);
  * checks that the version is at least 3.3 (`:95-104`);
  * creates and immediately destroys a second hidden "test" window (`:106-114`), which checks that window creation still
    works once a context exists;
  * calls `GL.createCapabilities()` (`:131`), then runs all extension detection (`:134-171`).
* The real game window becomes current only when the surface is configured or presented: `GlSurface.configure` leads to
  `GlDevice.makeCurrent` (`GlSurface.java:30`, `GlDevice.java:394-399`).
* Context-global state set once:
  * `glEnable(0x884F GL_TEXTURE_CUBE_MAP_SEAMLESS)` (`GlDevice.java:147`);
  * `glEnable(0x8642 GL_PROGRAM_POINT_SIZE)` (`:148`);
  * `glClipControl(GL_LOWER_LEFT, GL_ZERO_TO_ONE)` only if `ARB_clip_control` is present (`:149-152`). It is not
    present on Mac.

### 1.2 Ground-truth extension list (CGL probe, M5, macOS 27)

```
VERSION=4.1 Metal - 91.7   RENDERER=Apple M5   VENDOR=Apple   GLSL=4.10   NUM_EXT=43
GL_ARB_blend_func_extended GL_ARB_draw_buffers_blend GL_ARB_draw_indirect GL_ARB_ES2_compatibility
GL_ARB_explicit_attrib_location GL_ARB_gpu_shader_fp64 GL_ARB_gpu_shader5 GL_ARB_instanced_arrays
GL_ARB_internalformat_query GL_ARB_occlusion_query2 GL_ARB_sample_shading GL_ARB_sampler_objects
GL_ARB_separate_shader_objects GL_ARB_shader_bit_encoding GL_ARB_shader_subroutine GL_ARB_shading_language_include
GL_ARB_tessellation_shader GL_ARB_texture_buffer_object_rgb32 GL_ARB_texture_cube_map_array GL_ARB_texture_gather
GL_ARB_texture_query_lod GL_ARB_texture_rgb10_a2ui GL_ARB_texture_storage GL_ARB_texture_swizzle GL_ARB_timer_query
GL_ARB_transform_feedback2 GL_ARB_transform_feedback3 GL_ARB_vertex_attrib_64bit GL_ARB_vertex_type_2_10_10_10_rev
GL_ARB_viewport_array GL_EXT_debug_label GL_EXT_debug_marker GL_EXT_framebuffer_multisample_blit_scaled
GL_EXT_texture_compression_s3tc GL_EXT_texture_filter_anisotropic GL_EXT_texture_sRGB_decode GL_APPLE_client_storage
GL_APPLE_container_object_shareable GL_APPLE_flush_render GL_APPLE_rgb_422 GL_APPLE_row_bytes GL_APPLE_texture_range
GL_NV_texture_barrier
```

Absent, and relevant to RenderPearl:

* `ARB_buffer_storage`;
* `ARB_direct_state_access`;
* `ARB_vertex_attrib_binding`;
* `ARB_base_instance`;
* `ARB_multi_draw_indirect`;
* `ARB_shader_draw_parameters`;
* `ARB_clip_control`;
* `KHR_debug` and `ARB_debug_output`;
* `ARB_texture_view`, `ARB_texture_buffer_range` and `ARB_shading_language_420pack`;
* compute and SSBO support;
* `ARB_gl_spirv`.

`MT engine enabled = 0`: Apple's multithreaded GL engine is off by default, so all driver work, including GL-to-Metal
translation, runs on the game's render thread.

### 1.3 Limits as the backend will query them (CGL probe)

| Query | Value | Used by |
|---|---|---|
| `GL_UNIFORM_BUFFER_OFFSET_ALIGNMENT` (0x8A34) | **256** | `DeviceLimits.minUniformOffsetAlignment` (`GlHeuristics.java:96`) |
| `GL_MAX_TEXTURE_SIZE` | 16384 (proxy probe from 32768 down also yields 16384, `GlHeuristics.java:63-77`) | `maxTextureSize` |
| `GL_MAX_DRAW_BUFFERS` (0x8824) | 8 | reported as `maxColorAttachments` (`GlHeuristics.java:100`) |
| `GL_MAX_TEXTURE_MAX_ANISOTROPY` | 16 | `maxAnisotropy` (`GlDevice.java:134-139`) |
| `GL_MAX_UNIFORM_BLOCK_SIZE` | 65536 | not queried |
| `GL_MAX_UNIFORM_BUFFER_BINDINGS` | 80 | not queried |
| `GL_MAX_TEXTURE_IMAGE_UNITS` / combined | 16 / 80 | not queried |
| `GL_MAX_VERTEX_ATTRIBS` | 16 | the backend hard-codes 16 slots (`GlRenderPass.java:25`, `VertexArray.java:39-46`) |

---

## 2. Extension to fallback path map (what actually runs on macOS)

| Capability | Detected at | On Apple GL | Path taken on macOS | Consequence |
|---|---|---|---|---|
| `ARB_buffer_storage` | `BufferStorage.java:9-16`; forced off for Intel Gen7/NVIDIA (`GlDevice.java:144`) | absent | `BufferStorage.Mutable`: `glBufferData` (`BufferStorage.java:39-54`). `GlBuffer.canPersistentMap=false`. `GlCommandEncoder` picks `GlTransientMemory.Fallback` (`GlCommandEncoder.java:54-56`) | Every `GpuBuffer.map` does `clearGlErrors`, then `glMapBufferRange` of the whole buffer, then unmap on close (`GlBuffer.java:133-141,161-167`). Fallback staging writes become `glBufferSubData` on close (`GlTransientMemory.java:102-108`). `DeviceFeatures.persistentMapping=false` (`GlHeuristics.java:111`) |
| `ARB_direct_state_access` (and not GL-on-DX12) | `DirectStateAccess.java:13-20` | absent | `DirectStateAccess.Emulated`: bind, op, unbind (`DirectStateAccess.java:144-270`) | Two extra `glBindBuffer` per buffer op. FBO edits are bind plus `glFramebufferTexture2D`. Blits save and restore read/draw FBOs |
| `ARB_vertex_attrib_binding` | `VertexArray.java:17-26` | absent | `VertexArray.Emulated` (`VertexArray.java:38-105`) | Each `bind()` re-issues, per attribute, `glBindBuffer(ARRAY)`, `glVertexAttrib(I)Pointer` and `glVertexAttribDivisor` (`:74-104`) |
| `ARB_clip_control` | `GlDevice.java:149-152` | absent | depth range stays [-1,1]; `DeviceInfo.isZZeroToOne=false` (`GlHeuristics.java:91`) | Game projections are built for [-1,1] (`Projection.java:69`, `Camera.java:188`). Reverse-Z (`Projection.java:67-68`, clear 0.0 at `RenderSystem.java:43`, `GREATER_THAN_OR_EQUAL` at `RenderPipelines.java:44,57`) loses most of its precision benefit |
| `ARB_base_instance` | `GlDevice.java:165-167` | absent | `nonZeroFirstInstance=false` (`GlHeuristics.java:110`). The base-instance entry points in `executeDraw` (`GlCommandEncoder.java:465-475,480-481`) are never reached | `LevelRenderer` disables MDI terrain (`LevelRenderer.java:178-181`) and uses `DrawSeparate`, which issues one draw per section layer (section 6.3) |
| `ARB_draw_indirect` | `GlDevice.java:158-163` | present (core 4.0) | `drawIndirect=true`, `maxDrawIndirectDrawCount=1` (`GlHeuristics.java:84-86`) | Only the MDI terrain path calls `drawIndexedIndirect`, and that path is disabled, so this is dormant |
| `ARB_multi_draw_indirect` | `GlDevice.java:160-162` | absent | `multiDrawIndirect=false` | dormant |
| `ARB_shader_draw_parameters` | `GlDevice.java:154-156` | absent | `shaderDrawParameters=false`. SPIRV-Cross `GLSL_SUPPORT_NONZERO_BASE_INSTANCE=false` (`GlPipelineRecompiler.java:67`). No `RENDERPEARL_INSTANCE_INDEX_INCLUDES_BASE_INSTANCE` define (`GlslCompiler.java:89-91`) | the macro is unused by vanilla shaders anyway |
| `KHR_debug` / `ARB_debug_output` | `GlDebug.java:97-135` | absent | no debug callback; `getLastDebugMessages()` is empty | only `glGetError` checks remain |
| `KHR_debug` / `EXT_debug_label` | `GlDebugLabel.java:35-51` | EXT present | only when `renderDebugLabels` is set: `GlDebugLabel.Ext`, which labels objects but **has no push/pop debug groups** | — |
| `EXT_texture_filter_anisotropic` | `GlDevice.java:134-139` | present | `maxAnisotropy=16` | — |
| `isGlOnDx12` heuristic | `GlHeuristics.java:50-53` | false | write maps include `GL_MAP_UNSYNCHRONIZED_BIT` (0x20) (`GlBuffer.java:59-64`) | Driver-side sync on write maps is skipped. Correctness relies on the game's own fences |
| Mesa 25.0.0-2 VAB workaround | `VertexArray.java:112-117` | n/a | — | — |
| AMD "shader sacrifice" | `GlDevice.java:325-341` | n/a (renderer is "Apple M5") | — | — |

---

## 3. DeviceInfo as reported on Apple GL, and what each field switches in the game

`GlHeuristics.createDeviceInfo` (`GlHeuristics.java:79-119`), record definitions in `api/device/*.java`:

| Field | Apple GL value | Game/frontend effect | Recommended Metal value |
|---|---|---|---|
| `name`, `vendorName`, `driverInfo` | "Apple M5", "Apple", "4.1 Metal - 91.7" | logs and F3 | device name, "Apple", "Metal x.y" |
| `isZZeroToOne` | **false** | projection matrices (`Projection.java:69`, `Camera.java:188`); the `RENDERPEARL_DEPTH_IS_ZERO_TO_ONE` shader define (`GlslCompiler.java:81-83`, used by `oit_common.glsl:17-19`) | **true**, giving native reverse-Z with full float precision, the same as Vulkan |
| `backendName` | "OpenGL" | logs | "Metal" |
| `timestampPeriod` | 1.0 (ns) (`GlHeuristics.java:93`) | `TimerQuery.java:64` | depends on the counter domain (section 11.9) |
| `limits.maxAnisotropy` | 16 | sampler validation (`FrontendGpuDevice.java:73-78`) | 16 |
| `limits.minUniformOffsetAlignment` | **256** | UBO block strides (`DynamicGpuDataStorageMapped.java:29`, `DynamicGpuDataStorageNonMapped.java:30`, `TextureAtlas.java:102,149`, `Lighting.java:29`); frontend check (`FrontendRenderPass.java:150-157`); push-constant alignment (`GlRenderPass.java:90`) | as small as Metal allows (16 or 32; verify, see section 15). A 16 B ChunkSection UBO currently occupies 256 B |
| `limits.maxTextureSize` | 16384 | atlas sizing | 16384 (Apple7+) |
| `limits.maxMemoryAllocationSize` | `Long.MAX_VALUE` | `maxTextureSizeForFormat` | `MTLDevice.maxBufferLength` |
| `limits.maxMultiDrawDirectInterleavedDrawCount` | 0 | `multiDraw*` interleaved is forbidden | large (implement as a CPU loop) |
| `limits.maxColorAttachments` | 8 (actually `GL_MAX_DRAW_BUFFERS`) | render-pass validation | 8 |
| `limits.maxDrawIndirectDrawCount` | **1** (`GlHeuristics.java:84-86`) | gates MDI terrain (`LevelRenderer.java:178`) and the split loop (`ChunkSectionsToRender.java:133-155`) | `Integer.MAX_VALUE` (loop or ICB) |
| `features.wireframeFillMode` | true | wireframe pipelines (`PipelineBuilder.java:373`) | true (`MTLTriangleFillModeLines`) |
| `features.shaderDrawParameters` | false | shaderc define | true |
| `features.multiDrawDirectInterleaved` | false | `FrontendRenderPass.java:255,420` | true if implemented |
| `features.multiDrawDirectSeparate` | true | `glMultiDraw*` (`GlCommandEncoder.java:487-511`); **unused by vanilla game code** | true |
| `features.multiDrawIndirect` | false | `FrontendRenderPass.java:311,472` | true |
| `features.drawIndirect` | true | — | true |
| `features.nonZeroFirstInstance` | **false** | gates MDI terrain (`LevelRenderer.java:179`) | **true** |
| `features.persistentMapping` | **false** | transient memory variant; `StagingBuffer.create` (`StagingBuffer.java:16-20`) | **true** (Apple Silicon shared storage) |
| `hints.writeToBufferIsSlow` | false (`isGlOnDx12`) | with `persistentMapping`, selects `StagingBuffer.PersistentlyMapped` (`StagingBuffer.java:17`) | **true**, which removes one memcpy per chunk upload (section 13) |
| `hints.anisotropyHasKnownIssues` | false (`isAmd`) | default texture filter in the graphics preset (`GraphicsPreset.java:99`) | false |
| `hints.isExplicitDepthRequired` | **true** (`Util.isAppleSiliconMac(renderer)`, which is `renderer.startsWith("Apple")`, `Util.java:604-606`) | forces `gl_FragDepth = gl_FragCoord.z` in OIT shaders (`oit_sample.glsl:101-103`, `oit_add_transmittance.glsl:46-48`). That disables early-Z/HSR for OIT passes. Vulkan on Apple also sets it (`VulkanDevice.java:116`) | keep true until invariance is proven; candidate fix in section 11.6 |
| `hints.multiDrawIndirectHasKnownIssues` | false (Intel non-Arc only) | MDI terrain gate | false |
| `type` | `OTHER` (`GlHeuristics.java:133-151`) | — | `INTEGRATED` |

---

## 4. Backend reference: semantics, invariants, threading, ownership

### 4.1 Threading and lifecycle (whole backend)

* Single-threaded. Almost every `GlStateManager` entry point asserts the render thread (`RenderSystem.assertOnRenderThread()`,
  e.g. `GlStateManager.java:42`). The context is current only on the render thread.
* One command stream per device:
  * `GlDevice.createCommandEncoder()` returns the same `GlCommandEncoder` every time (`GlDevice.java:193-195`);
  * `FrontendGpuDevice` caches one `FrontendCommandEncoder` (`FrontendGpuDevice.java:50,60-62`);
  * so `RenderSystem.getDevice().createCommandEncoder()`, which the game calls everywhere, is free and always returns the
    same encoder.
* Pipeline compile is split:
  * `GlDevice.compilePipeline` runs on a worker (called from `PipelineBuilder.java:377-411` inside `supplyAsync`). It only
    runs SPIRV-Cross (`GlPipelineRecompiler.decompileShaders`, `GlDevice.java:356`).
  * The returned `Pending.finishCompile` runs `glCreateShader`, compile, link, uniform-block setup and VAO creation
    (`GlDevice.java:357-366`). It executes on `reloadExecutor` (`ShaderManager.java:105-106`), which must be the GL
    thread.
* Shutdown: `GlDevice.close` closes the encoder (Fallback transient memory frees its blocks) and destroys the context
  and hidden window (`GlDevice.java:343-348`).

### 4.2 GlHeuristics (`GlHeuristics.java`)

Pure string sniffing of `GL_RENDERER` and `GL_VENDOR` (`:27-32`). On Apple:

* `isGlOnDx12 = false`: true only on Windows ARM64 or renderer "D3D12*" (`:50-53`);
* `isAmd = false` and `isNvidia = false`;
* `couldBeIntelGen7 = false` (`:121-131`).

`getMaxSupportedTextureSize` probes `GL_PROXY_TEXTURE_2D` from 32768 down (`:63-77`). It is a startup-only cost.

### 4.3 Buffers: BufferStorage, GlBuffer, DirectStateAccess, GlUtil

* **Creation.** `GlDevice.createBuffer` (`GlDevice.java:280-313`):
  * calls `clearGlErrors()` (a `glGetError` loop), then the buffer storage strategy, then `_getError()`;
  * `GL_OUT_OF_MEMORY` (1285) becomes `GpuOutOfMemoryException`; any other error becomes `IllegalStateException`.

  On Mac (Mutable plus Emulated DSA) this is `glGenBuffers`, `glBindBuffer(target)`, `glBufferData(size or data, usage
  enum)`, `glBindBuffer(target, 0)`.
* **Bind target** is picked from usage (`GlUtil.java:6-16`):
  * VERTEX (32) gives `ARRAY_BUFFER`;
  * INDEX (64) gives `ELEMENT_ARRAY_BUFFER`;
  * UNIFORM (128) gives `UNIFORM_BUFFER`;
  * INDIRECT (512) gives `DRAW_INDIRECT_BUFFER`;
  * anything else gives `COPY_WRITE_BUFFER`.
* **Usage enum** (`GlConst.java:353-362`):
  * MAP_WRITE gives `STREAM_DRAW` if CLIENT_STORAGE, otherwise `STATIC_DRAW`;
  * MAP_READ gives `STREAM_READ` or `STATIC_READ`;
  * everything else gives `STATIC_DRAW`.
* **Mapping** (`GlBuffer.Direct`, `GlBuffer.java:37-168`):
  * Flags:
    * READ adds `GL_MAP_READ_BIT`;
    * WRITE adds `GL_MAP_WRITE_BIT | GL_MAP_UNSYNCHRONIZED_BIT` (the UNSYNCHRONIZED bit is dropped on GL-on-DX12);
    * persistent buffers add `PERSISTENT | COHERENT` (`:54-70`).
  * The map is always of the whole buffer (`:137`) and is reference-counted (`:134`, `:161-167`). A sub-range view is
    returned with `memSlice` (`:143-155`).
  * With persistent mapping the constructor maps once and never unmaps until close (`:72-74`).
  * With mutable buffers (Mac), every `map()`/`close()` pair is a real `glMapBufferRange`/`glUnmapBuffer`. Each is
    wrapped in bind/unbind (`DirectStateAccess.java:191-205`) and preceded by `clearGlErrors()` (`:136`).
* **Mapped-while-used check.** `checkCanBeUsed()` throws if a non-persistent buffer is used while mapped
  (`GlBuffer.java:28-35`). The frontend calls it only under `STRICT_VALIDATION` (IDE runs; `FrontendGpuDevice.java:36`,
  `FrontendRenderPass.java:509`) and in `copyTextureToBuffer`/`writeToBuffer`/`copyToBuffer` (`GlCommandEncoder.java:276,
  284-285, 383`).
* **No synchronization on map.** UNSYNCHRONIZED is always set. Callers must guarantee the GPU is not reading what they
  overwrite. The game does this with `MappableRingBuffer` (3 buffers plus fences, `MappableRingBuffer.java`) and fenced
  pool recycling (`StagedVertexBuffer.java:294-391`).
* **Close.** `GlBuffer.Direct.close` (`:82-97`) unmaps if persistent, throws if still mapped, then deletes immediately.
  The GL driver defers the real free until the GPU is done.

### 4.4 GlTransientMemory (`GlTransientMemory.java`) and TransientBlockAllocator

* **Contract** (`api/buffers/TransientMemory.java`):
  * allocations live only until the next `submit()`;
  * `TransientGpuBuffer.isClosed()` flips to true once `currentSubmitIndex` advances (`GlTransientMemory.java:457-471`);
  * they cannot be mapped or re-sliced (`:479-491`).
  * Each allocation wraps the backing GL buffer handle in a fresh `TransientGpuBuffer` object, which is Java garbage per
    allocation.
* **Allocator.** `TransientBlockAllocator` hands out 512 KiB blocks (`BLOCK_SIZE`, `:21`). It uses bump allocation and
  "special" one-off blocks for oversize requests. `rotate()` returns a Runnable that recycles the blocks used in that
  rotation (`TransientBlockAllocator.java:47-75`).
* **Mac uses `Fallback`** (`:57-221`):
  * A block is one mutable GL buffer with usage 40 (COPY_DST|VERTEX). Because GL buffers are untyped, the same block is
    used as a UBO, VBO or IBO. Each block also has a `malloc`'d host shadow (`:76-81`).
  * `allocateStaging` and `allocateGpuMapped` return host memory. **On close they `glBufferSubData` the range**
    (`:89-110,125-129`).
  * `uploadGpu`/`uploadStaging` and `multiUploadGpu` bind, `glMapBufferRange(range, 38 = WRITE | INVALIDATE_RANGE |
    UNSYNCHRONIZED)`, memcpy, unmap and unbind (`:139-165,173-213`).
  * **`rotate()` recycles blocks immediately at submit** (`:71-74`). See the race in section 10.1.
* **`PersistentMapping`** (not used on Mac, `:223-453`): three allocators (staging host-visible, GPU, GPU-mapped).
  Rotation is deferred by one submit slot (`:259-270`). `close()` does `glFinish()` (`:248-257`).
* **Consumers in vanilla 26.3:**
  * `RenderPass.pushConstants`, which uploads through `uploadGpu` (`GlRenderPass.java:89-92`), is **never called by
    game code**. `grep` finds no caller under `net/`;
  * `SpriteContents` animation setup uses `multiUploadStaging` (`SpriteContents.java:286-287`, load time);
  * `CubeMapTexture` uses `uploadStaging` (`CubeMapTexture.java:65`, load time);
  * so on GL the transient allocator is nearly idle per frame.

### 4.5 GlCommandEncoder (`GlCommandEncoder.java`)

* **Submit indices and fences** (`:40-116`):
  * `MAX_SUBMITS_IN_FLIGHT=2` and `currentSubmitIndex` starts at 2 (`:40-45`).
  * `submit()` (`:75-83`):
    1. `glFenceSync(SYNC_GPU_COMMANDS_COMPLETE)` into slot `index%2`;
    2. increment `currentSubmitIndex`;
    3. **block** on the fence of the submit before the one just issued: `awaitSubmit(current-2, -1)`, which is
       `glClientWaitSync(fence, GL_SYNC_FLUSH_COMMANDS_BIT, ~0)`;
    4. `transientMemory.rotate()`.
  * So the CPU may record frame N+1 while the GPU runs frame N, but no further ahead.
  * `awaitSubmit` (`:85-116`) returns true at once if `current > index+2`, returns false or throws for the current index,
    and otherwise waits with the flush bit. The flush bit is always set, so even a timeout-0 poll forces a
    command-buffer commit (section 7, about 27 to 43 µs with pending work).
* **Render pass begin** (`createRenderPass`, `:124-194`):
  1. push a debug group;
  2. look up or create the FBO in `FrameBufferCache` (allocates a `CacheKey` per call) and `glBindFramebuffer(FRAMEBUFFER)`;
  3. **enable scissor = renderArea** (`:156-157`);
  4. per colour attachment with a clear value: `glColorMaski(i, all)` then `glClearBufferfv(COLOR, i)` (`:159-168`);
  5. for depth: `glDepthMask(true)` then `glClearBufferfv(DEPTH)` (`:170-176`);
  6. `glViewport(0,0,attachment size)` (`:178`; there is no API for a custom viewport);
  7. `glDrawBuffers` with `GL_NONE` for null attachments (`:179-189`);
  8. `lastPipeline = null` (`:190`), so the first draw always rebinds the pipeline.

  Clears in GL honour the scissor, so **clears are limited to the render area**. A Metal load-action clear is not
  (section 11.4).
* **Render pass end** (`submitRenderPass`, `:622-625`): `glBindFramebuffer(0)` and pop the debug group.
* **Clears outside passes** (each one rebinds the FBO on Mac):
  * `clearColorTexture`: loops over **all mips**, attaching each to `drawFbo` (`:197-209`);
  * `clearColorAndDepthTextures`: all mips via cached FBOs (`:212-228`);
  * the region variant: scissor plus `glClear` on one mip (`:231-254`);
  * `clearDepthTexture`: all mips with `glDrawBuffer(NONE)`, then restores `COLOR_ATTACHMENT0` (`:257-271`).
* **`writeToBuffer`** (`:274-278`): Emulated DSA bind, `glBufferSubData`, unbind. The GL contract copies the source
  memory before returning. Callers rely on this; several pass `MemoryStack` buffers (`ProjectionMatrixBuffer.java:46-50`,
  `GlobalSettingsUniform.java:27-41`, `Lighting.java:63-67`).
* **`copyToBuffer`** (`:281-287`): `COPY_READ`/`COPY_WRITE` binds and `glCopyBufferSubData`. This is the Apple
  "NEEDS IMPLEMENTATION" path.
* **`writeToTexture`** (`:290-316`):
  * binds the texture on the current unit;
  * `UNPACK_ROW_LENGTH=width`, `SKIP_*=0`, `UNPACK_ALIGNMENT=format.byteAlignment()`, then `glTexSubImage2D`;
  * a cube face is chosen as `CUBEMAP_TARGETS[depthOrLayer % 6]` (`GlConst.java:104`: +X,-X,+Y,-Y,+Z,-Z).
* **`copyBufferToTexture`** (`:319-364`):
  * PBO upload (`GL_PIXEL_UNPACK_BUFFER`);
  * `ROW_LENGTH=sourceWidth` and `IMAGE_HEIGHT=sourceHeight`;
  * the source x/y skip is applied as a byte offset (`:342-344,361`).
* **`copyTextureToBuffer`** (`:372-404`):
  * attaches the texture or depth to `readFbo`;
  * `glReadPixels` into a `GL_PIXEL_PACK_BUFFER` at `offset` with `PACK_ROW_LENGTH=width`. This is asynchronous, with no
    CPU stall;
  * `RenderSystem.queueFencedTask(callback)`: the callback runs on the render thread in a later
    `executePendingTasks()` once the fence has passed (`RenderSystem.java:323-341`, `Minecraft.java:1310`).
* **`copyTextureToTexture`** (`:407-434`): scissor off, attach both textures (same `mipLevel` for source and dest), then
  `glBlitFramebuffer(..., GL_NEAREST)` for colour or depth.
* **Present** (`presentTexture`, `:436-447`):
  * `makeCurrent(window)`;
  * scissor off, viewport, depth and colour masks;
  * attach the texture to `drawFbo`, then `glBlitFramebuffer` to FBO 0 with `dstY = swapchainH - texH`. The texture is
    anchored to the top, and there is **no Y flip** because GL's default framebuffer is bottom-up like the texture.
* **Draw entry points:**
  * `executeDraw` (`:454-485`) uses `glDrawElementsInstancedBaseVertex` / `glDrawArraysInstanced`. The base-instance
    variants run only if `firstInstance>0`, which the frontend forbids on Mac;
  * `executeDraws` uses `glMultiDrawArrays` / `glMultiDrawElementsBaseVertex` (`:487-511`, unused by the game);
  * `executeDrawIndirect` (`:513-529`, dormant on Mac).
  * Index byte offset = `firstIndex * indexType.bytes` (`:470,477`). `baseVertex` is passed as `first` for non-indexed
    draws (`:481-483`).
* `writeTimestamp` uses `glQueryCounter(GL_TIMESTAMP)` and works anywhere, including inside a pass (`:628-630`,
  `GlQueryPool.java:44-46`).

### 4.6 GlRenderPass and the per-draw state machine

`GlRenderPass` (`GlRenderPass.java`) only records state and dirty flags. All GL work happens in
`GlCommandEncoder.setupDraw` (`GlCommandEncoder.java:531-619`) right before each draw.

| Step | Trigger | GL calls on macOS |
|---|---|---|
| Vertex input | `vertexBufferDirty`: set by **every** `setPipeline` (even the same pipeline, `GlRenderPass.java:76`) and by `setVertexBuffer` with a different buffer or offset (`:127-134`) | `VertexArray.Emulated.bind`: `glBindVertexArray`, then per used attribute (typically 3 to 6) `glBindBuffer(ARRAY)`, `glVertexAttrib(I)Pointer(offset = slice.offset + attrib.offset)` and `glVertexAttribDivisor` (`VertexArray.java:74-104`) |
| Index buffer | `indexBufferDirty`: every `setIndexBuffer` (`:137-141`) and every `setPipeline` if an IBO is set | `glBindBuffer(ELEMENT_ARRAY)` (`GlCommandEncoder.java:537-540`) |
| Pipeline | `lastPipeline != pipeline` (reset per pass) | `GlRenderPipeline.bind` (`GlRenderPipeline.java:113-157`): `glUseProgram` (not cached), then depth enable/func/mask, polygon offset, cull, per-attachment `glColorMaski` and `glEnablei/Disablei(BLEND)` (cached), blend func/eq (cached), `glPolygonMode` (**not cached**, every bind) |
| Push constants | `pushConstantsDirty` | `glBindBufferRange(UNIFORM, 0, ...)` (unused by vanilla) |
| Uniforms | per-index dirty bits (`GlRenderPass.java:82-86`). A *different* pipeline marks all dirty (`:62-73`). **The frontend re-sends every named uniform on every `setPipeline`** (`FrontendRenderPass.java:125`) | UBO: `glBindBufferRange` (not cached). UTB: `glActiveTexture`, `glBindTexture(TEXTURE_BUFFER)`, `glTexBuffer(format, buffer)`. Sampler: `glActiveTexture`, `glBindTexture` (2D cached), `glBindSampler`, **`glTexParameteri(BASE_LEVEL)` and `glTexParameteri(MAX_LEVEL)`** to emulate the view's mip range (`GlCommandEncoder.java:571-603`) |
| Scissor | `scissorStateDirty` | `glEnable/Disable(SCISSOR)` (cached) and `glScissor` (not cached) |

Semantics to preserve:

* `disableScissor()` restores the pass's default scissor, which is the render area, not "off" (`GlRenderPass.java:101-104`).
* Uniform values: `GpuBufferSlice` for UBO/UTB and `TextureViewAndSampler` for samplers (`util/TextureViewAndSampler.java`).
  A null sampler value is ignored (`GlCommandEncoder.java:584`), so the old binding persists.
* Two `multiDraw*` interleaved variants throw (`GlRenderPass.java:149-151,169-171`); the frontend never calls them
  because the feature is false.

### 4.7 Pipelines: GlPipelineRecompiler, GlShaderModule, GlProgram, GlRenderPipeline, VertexArray, Uniform

* **SPIR-V to GLSL 330** via SPIRV-Cross (`GlPipelineRecompiler.java:45-127`). Options, resolved from the lwjgl-spvc
  constants with `javap`:
  * `GLSL_VERSION=330` (`:65`) and `ENABLE_420PACK=false` (`:66`). Without 420pack there is no `layout(binding=)`, so
    bindings are assigned at runtime.
  * `EMIT_PUSH_CONSTANT_AS_UNIFORM_BUFFER=true` (`:67`).
  * `SUPPORT_NONZERO_BASE_INSTANCE=shaderDrawParameters` (`:68`).
  * Outside RenderDoc debug mode: `FORCE_ZERO_INITIALIZED_VARIABLES` (16777270) and
    `FLATTEN_MULTIDIMENSIONAL_ARRAYS` (16777218) (`:69-71`).
* **Renames** (`:76-96,129-234`):
  * VS inputs become `_vert_input_LL` and FS outputs `_frag_output_LL`.
  * On macOS, and in all non-debug builds, VS outputs and FS inputs become `_interface_variable_LL` (`:85-89`). GLSL 330
    links varyings by name, so they are renamed by location.
  * UBO blocks become `_uniform_SS_BB` (instance `_uniform_instance_SS_BB`). Sampled and separate images and samplers
    become `_uniform_SS_BB`. Push constants become `_push_constants`.
* **Binding assignment at link time** (`GlProgram.java:59-110`):
  * `glUseProgram(program)`;
  * push-constant block gets UBO binding 0;
  * then for uniform *i* in `createInfo.uniforms()` the name `_uniform_00_ii` (descriptor set 0, binding = list index):
    * UBO: `glUniformBlockBinding(next++)`;
    * UTB or sampler: `glUniform1i(location, nextUnit++)`. Samplers and texel buffers share one unit counter.
  * If the linker removed a uniform, the slot is `null` and later binds are skipped.
  * UTBs allocate a private buffer-texture name per program (`Uniform.java:17-26`).
* **Program link** (`GlProgram.java:29-53`): a link log containing "Failed for unknown reason" is treated as failure
  even when `LINK_STATUS` is true. This is an Apple-ism.
* **Errors:**
  * a compile failure returns `GlShaderModule.INVALID_SHADER`, which gives a `null` pipeline from `finishCompile`
    (`GlPipelineRecompiler.java:236-249,288-348`);
  * SPIRV-Cross failure gives `Pending.NULL` (`GlDevice.java:357`).
* **GlRenderPipeline** (`GlRenderPipeline.java:31-88`) bakes:
  * topology;
  * cull;
  * polygon mode;
  * depth enable, func, write and bias (`glPolygonOffset(factor=depthBiasScaleFactor, units=depthBiasConstant)`,
    `:117-124`);
  * per-attachment write mask (a null target gives mask 0) and blend enable;
  * **a single "representative" blend func/equation for all attachments** (the first target with blending, `:65-87`).
    Per-attachment blend factors are not supported by this GL path. Metal and Vulkan can do them per attachment.
* **Topology mapping** (`GlConst.java:188-199`):
  * `LINES` and `QUADS` map to `GL_TRIANGLES`. Lines are CPU-expanded quads, and quads use the auto index buffer
    (`RenderSystem.java:49-65,395-492`).
  * `DEBUG_LINES` gives `GL_LINES`, `DEBUG_LINE_STRIP` gives `GL_LINE_STRIP`, `POINTS` gives `GL_POINTS`,
    `TRIANGLE_STRIP` gives the strip, and **`TRIANGLE_FAN` gives `GL_TRIANGLE_FAN`**. Fans are used by `SKY` every frame
    (`RenderPipelines.java:1034-1042`) and by debug pipelines.
* **VertexArray**: one VAO per pipeline (`VertexArray.java:15`). In `Emulated` the constructor enables attribute arrays
  and caches format, stride, divisor and offset per location (`:48-71`). Integer formats use `glVertexAttribIPointer`.
  Normalisation comes from `GlConst.isFormatNormalized` (`GlConst.java:231-236`).

### 4.8 Textures, views, samplers, FBOs

* **`createTexture`** (`GlDevice.java:209-273`):
  * `GL_TEXTURE_MAX_LEVEL=mips-1`, `MIN_LOD=0`, `MAX_LOD=mips-1`, and `TEXTURE_COMPARE_MODE=NONE` for depth;
  * then `glTexImage2D(null)` for every mip (and every cube face);
  * finally a `glGetError` check (OOM gives `GpuOutOfMemoryException`).
  * Formats without a GL internal format throw `IllegalArgumentException` (`:271`). All `RGB*` 3-channel formats are in
    that group, so only 1, 2 and 4-channel colour formats exist as textures, which is Metal-compatible.
  * Depth/stencil: `D16_UNORM`, `D32_FLOAT`, `D24_UNORM_S8_UINT`, `D32_FLOAT_S8_UINT`, `S8_UINT` (`GlConst.java:253,283-286`).
* **`GlTextureView`** is not a GL object. It holds `baseMipLevel`/`mipLevels` and a refcount on the texture
  (`GlTextureView.java:13-17`). The mip range is applied by mutating the shared texture's `BASE/MAX_LEVEL` at every bind
  (`GlCommandEncoder.java:598-599`), so two views of one texture cannot be bound with different ranges at once. As an FBO
  attachment, the view's `baseMipLevel` is the attachment level (`GlTextureView.java:45-48`).
* **Lifetime:**
  * `GlTexture.close()` defers deletion until all views are closed (`GlTexture.java:33-49,76-85`);
  * deletion destroys every cached FBO that references it (`:43-46`, `FrameBufferCache.java:63-74`).
* **`GlSampler`** is an immutable GL sampler object (`GlSampler.java:19-59`):
  * **min NEAREST maps to `GL_NEAREST_MIPMAP_LINEAR`**, and min LINEAR maps to `GL_LINEAR_MIPMAP_LINEAR`. The mip filter
    is always linear;
  * mag is NEAREST or LINEAR;
  * wrap S/T is REPEAT or CLAMP_TO_EDGE;
  * anisotropy is set only if above 1;
  * `maxLod` is optional;
  * there is no min-LOD, bias, compare or border state.
* **`FrameBufferCache`** maps a key of `(glId, mip)` per attachment to an FBO. FBOs live until an attachment is closed.

### 4.9 GlSurface (`GlSurface.java`)

* Present modes are FIFO and IMMEDIATE only (`:15`). The game picks FIFO for vsync, otherwise IMMEDIATE
  (`GpuSurface.java:35-48`).
* `configure` does `makeCurrent` and `SDL_GL_SetSwapInterval(1/0)` and records the size (`:29-34`).
* `isSuboptimal()` is always false.
* **`acquireNextTexture()` only checks `isIconified`** (`:42-46`). There is no drawable. The game calls acquire at the
  very start of the frame, before update, extract and render (`Minecraft.java:1273`), which is harmless for GL.
* `blitFromTexture` calls `GlCommandEncoder.presentTexture`.
* `present()` calls `SDL_GL_SwapWindow` (`:54-56`).

### 4.10 GlFence, GlQueryPool

* `GlFence` records `currentSubmitIndex` at creation (`GlFence.java:10-13`). `awaitCompletion(t)` calls
  `encoder.awaitSubmit(index, t)` (`:21-28`).
  * A fence created at any point during submit N completes when submit N's GL fence signals. It is guaranteed complete
    once the CPU passes submit N+1, because `submit()` waits two behind.
  * Fences are cheap Java objects. The only GL syncs are the two per-slot `GLsync`s.
* `GlQueryPool`:
  * `glGenQueries` (`:11-14`);
  * `getValue` checks `QUERY_RESULT_AVAILABLE`, then reads `QUERY_RESULT` (`:22-25`). This is non-blocking.
  * Calibration: `glGetInteger64(GL_TIMESTAMP)` vs `System.nanoTime()` (`GlDevice.java:382-387`).

### 4.11 GlStateManager shadow state (what is cached)

| Cached (skips redundant GL calls) | Not cached (called every time) |
|---|---|
| depth enable/func/mask, per-index blend enable, blend func, blend eq, cull enable, polygon-offset enable/values, logic op, scissor enable, active texture unit, `GL_TEXTURE_2D` binding per unit, per-index colour mask, read/draw FBO binding (`GlStateManager.java:25-39,276-286`) | `glUseProgram`, `glBindBuffer`, `glBindBufferRange`, `glBindSampler`, `glTexParameteri`, `glScissor` box, `glViewport`, `glPolygonMode`, `glBindVertexArray`, cube-map/buffer-texture binds, `glDrawBuffers`, `glClearColor`/`glClearDepth` |

On macOS each `glClear`/`glClearBuffer` is followed by a `glGetError()` whose result is discarded
(`GlStateManager.java:525-547`). This looks like a workaround that swallows a spurious error so later error checks are
not misattributed. Cost is about 4 ns.

### 4.12 Debug

* `GlDebug` needs `KHR_debug` or `ARB_debug_output`, both absent on Mac, so it is `null` and no callback runs.
* `GlDebugLabel.Ext` (Mac, only with `renderDebugLabels`) labels buffers, textures, shaders and programs, but has **no**
  debug groups (`GlDebugLabel.java:101-128`).

---

## 5. Frame lifecycle on GL and every sync point

### 5.1 Order of operations (`Minecraft.renderFrame`, `Minecraft.java:1245-1360`)

1. `windowSurface.acquireNextTexture()` (`:1273`). GL: iconified check only.
2. Update, pick and extract (game logic).
3. `RenderSystem.executePendingTasks()` (`:1310`): polls fenced callbacks with `awaitCompletion(0)`.
4. `gameRenderer.render()` (`:1312`). It does:
   * the frame clear (`GameRenderer.java:478-481`, `clearColorAndDepthTextures`);
   * `GlobalSettingsUniform.update` (`:482-491`, which is `writeToBuffer`);
   * `lightmap.render` (a render pass);
   * the level frame graph: a "clear" pass that clears again (`LevelRenderer.java:247-256`), sky, main
     (terrain+features), translucent, weather, clouds, see-through, always-on-top, optional depth integrate;
   * hand and HUD-3D (`GameRenderer.java:665,687,706,735`);
   * GUI (`GuiRenderer.java:195-224`, one or two passes);
   * post effects.
5. `windowSurface.blitFromTexture(...)` (`:1322`). GL: `makeCurrent` and `glBlitFramebuffer` to FBO 0.
6. `createCommandEncoder().submit()` (`:1337`). GL: `glFenceSync`, then **wait for the previous submit**, then rotate.
7. `windowSurface.present()` (`:1340`). GL: `SDL_GL_SwapWindow`.
8. `getDynamicUniforms().reset()` (`:1348`, ring-buffer rotate, which creates fences) and `levelRenderer.endFrame()`
   (`:1349`). `StagedVertexBuffer.endFrame` creates a fence and polls older ones (`StagedVertexBuffer.java:350-363`).
9. Frame limiter only if `framerateLimit < 260` (`:1352-1353`). The benchmarks use 260, which means unlimited.

### 5.2 Sync-point inventory on macOS GL (steady in-world frame, `improvedTransparency=false`)

Unit costs come from section 7. Frequencies are from code reading; "est." marks estimates.

| GL call | Where | Per frame on Mac | Measured unit cost on M5 | Nature on Apple GL |
|---|---|---|---|---|
| FBO change followed by draw or clear | `createRenderPass` (`GlCommandEncoder.java:134-139`); standalone clears (`:197-271`); `submitRenderPass` binds 0 (`:623`) | one per render pass and per standalone clear: about 12 to 20 (est.) | **12 to 27 µs** | Ends the Metal render encoder and starts a new one. On a TBDR GPU it also adds a tile store/load of every attachment |
| `glBufferSubData` | `writeToBuffer` (`:274-278`) from `GlobalSettingsUniform` (1/frame), level projection (`GameRenderer.java:665`, always rewrites), lighting, HUD/GUI projections on change, weather/world border meshes, `StagingBuffer.Cpu` chunk uploads (`StagingBuffer.java:107-111`); Fallback staging close (`GlTransientMemory.java:102-108`) | 2 to 5 normally, **plus about 1 to 2 per rebuilt chunk-section layer** | **15 to 23 µs** standalone; **40 to 58 µs** interleaved with draws or when the target is in use | Encoder break plus driver-side blit or rename. It does **not** wait for unrelated GPU work (14 µs with 30 ms of work pending) |
| `glCopyBufferSubData` | `copyToBuffer` (`:281-287`), from `StagedVertexBuffer.upload` (2 calls each, `StagedVertexBuffer.java:164-166`) for level features, hand/screen features and GUI | about 3 to 6 | **50 to 55 µs** (48 to 290 µs after heavy work) | Driver prints `gldCopyBufferSubData: NEEDS IMPLEMENTATION`; this is a slow fallback path |
| `glMapBufferRange` / `glUnmapBuffer` (whole buffer, WRITE\|UNSYNC) + `glGetError` | `GlBuffer.map` (`GlBuffer.java:133-141`) from `DynamicGpuDataStorageMapped.writeData` (**one per `writeTransform`**: every prepared render type, GUI, sky, clouds, particles...), `MappableRingBuffer` UBOs (fog, lightmap, cloud, post), `StagedVertexBuffer` staging map | tens to hundreds | about **0.3 µs** per write-plus-draw cycle; 1 to 200 µs if the buffer is referenced by already-committed work | Cheap in the game's ring-buffer pattern |
| `glMapBufferRange(range, 38)` | Fallback `uploadGpu`/`multiUploadGpu` | about 0 per frame | about 0.3 µs | — |
| `glBlitFramebuffer` | present (`:445-446`); `copyTextureToTexture` (`RenderTarget.copy*From`) | 1 (+ rare) | **37 to 40 µs** CPU at 512x512; the GPU cost scales with the 3600x2260 present | Encoder break plus blit |
| `glFenceSync` + `glClientWaitSync(FLUSH, ∞)` + `glDeleteSync` | `submit()` (`:75-83`) | 1 | 9 to 16 µs when idle; otherwise **waits for frame N-1's GPU work** | Throttles the CPU to at most one frame ahead |
| `glClientWaitSync(FLUSH, 0)` polls | `GlFence.awaitCompletion(0)`: `StagedVertexBuffer` pool recycling (×3 pools), `executePendingTasks` | 0 to 3 | **27 to 43 µs** if commands are pending, cheap right after a swap | The flush bit forces a command-buffer commit |
| `glTexSubImage2D` | `writeToTexture` (glyph pages `TrueTypeGlyphProvider.java:208`, `UnihexProvider.java:340`, `SpecialGlyphs.java:66`, `DynamicTexture`, maps, paletted textures) | 0 (on demand) | 0.7 µs into an idle texture; **18 µs** into a texture sampled by in-flight work; **about 30 ms (full GPU drain)** into a texture that is a render target of queued work | — |
| `glReadPixels` into PBO | `copyTextureToBuffer` (screenshots, Tracy capture, texture dumps) | 0 | async | — |
| `glGetError` / `clearGlErrors` | every map, buffer and texture creation, every clear on Mac, copy error checks | dozens | about 4 ns | No MT engine, so cheap |
| `SDL_GL_SwapWindow` | `present()` | 1 | vsync dependent | commit plus present |
| `glFinish` | only `PersistentMapping.close()` (`GlTransientMemory.java:250`) | 0 (not on Mac) | — | — |

The terrain upload path is the dominant variable cost: `SectionRenderDispatcher.uploadTerrainBuffersToGpu`
(`SectionRenderDispatcher.java:140-154`) calls `UberGpuBuffer.uploadStagedAllocations`. That makes one `uploader.copyTo`
per staged mesh (`UberGpuBuffer.java:103`), which becomes `StagingBuffer.Cpu.copyTo`, then `writeToBuffer`, then
`glBufferSubData` into a 128 MiB (vertex) or 32 MiB (index) heap (`SectionRenderDispatcher.java:65-74`). At about 40 to
58 µs each, 20 rebuilt sections in one frame cost about 1 to 2 ms of pure driver overhead plus memcpy. That is consistent
with the GL 1%-low of 103 FPS against a median of 231.

---

## 6. Uniform and binding model, and upload strategy

### 6.1 Name to binding chain

The frontend `BindGroupLayout` uniform list index *i* maps to SPIR-V `(set 0, binding i)`, then to the GLSL name
`_uniform_00_ii`. The GL backend then assigns the binding by name at link time (`GlProgram.java:59-110`):

* UBOs get sequential uniform-block bindings, with push constants first at binding 0;
* samplers and texel buffers get sequential texture units.

`FrontendRenderPipeline.uniformIndices` maps a string name to *i* (`FrontendRenderPass.java:167`).

### 6.2 Values and upload paths used by the game

* **Per-object transforms** (`DynamicTransforms`, 160 B std140, padded to 256):
  * written by `DynamicGpuData.writeTransform`, which calls `DynamicGpuDataStorageMapped.writeData`
    (`DynamicGpuDataStorageMapped.java:58-82`);
  * one `map()`/`close()` per call into a 3-deep `MappableRingBuffer` (`MappableRingBuffer.java:36-54`);
  * the ring grows by doubling within a frame, and grown buffers are retired at `endFrame`;
  * deduplicates consecutive equal transforms (`:60-62`);
  * `RenderType.prepare()` writes one per prepared render type (`RenderType.java:60-86`).
* **Terrain:**
  * `TerrainTransform` is one per frame;
  * `ChunkSection` (16 B, padded to **256 B** on Apple GL) is written for all visible sections with one map
    (`DynamicGpuData.java:87-98`) and bound per draw (section 6.3).
* **Long-lived small UBOs written with `writeToBuffer`:**
  * `Globals` (`GlobalSettingsUniform.java:41`, every frame);
  * `Projection` (`ProjectionMatrixBuffer.java:46-50`; the level projection rewrites every frame, `GameRenderer.java:665`);
  * `Lighting` (`Lighting.java:63-67`).
* **Ring-buffered mapped UBOs:** `Fog`, `Lightmap`, `CloudInfo`, `PostPass` sampler info, and the cloud UTB
  (`MappableRingBuffer` users: `FogRenderer.java:52`, `Lightmap.java:46`, `CloudRenderer.java:56,144`, `PostPass.java:86`).
* **Texel buffer:** only `CloudFaces` (R8_SINT, `BindGroupLayouts.java:33,89`). It must be a whole buffer; the frontend
  rejects slices (`FrontendRenderPass.java:564-581`) because GL 3.3 has no `glTexBufferRange`.
* **Push constants:** API present, unused.

### 6.3 Per-draw sequences (what the GL backend actually emits on Mac)

* **Terrain** (`ChunkSectionsToRender.DrawSeparate`, `ChunkSectionsToRender.java:161-191`, `FrontendRenderPass.drawMultipleIndexed`
  at `FrontendRenderPass.java:332-398`). Per section layer:
  * `setVertexBuffer(0, heap.slice())` allocates a slice record. Usually the same heap buffer at offset 0, so it is not
    dirty;
  * `setUniform("ChunkSection", slice)` does a HashMap put and a name lookup, then marks the UBO dirty;
  * `drawIndexed` goes straight to the backend, with no frontend validation.
  * GL calls: `glBindBufferRange` plus `glDrawElementsInstancedBaseVertex`, and `glBindBuffer(ELEMENT)` when the index
    buffer changes. A VAO respecification happens only when the uber-heap changes.
* **Feature / RenderType draw** (`PreparedRenderType.draw`, `PreparedRenderType.java:37-56`). Per draw:
  * frontend calls: `setPipeline`, `enableScissor`, `bindDefaultUniforms` (Projection, Fog, Globals, Lighting:
    `RenderSystem.java:367-387`), `setUniform(DynamicTransforms)`, `setVertexBuffer`, 1 to 3 textures, `setIndexBuffer`
    and `drawIndexed`;
  * GL calls: VAO respecification (about 10 to 19 calls), IBO bind, pipeline bind when it changed (about 3 to 12 calls),
    up to 5 `glBindBufferRange`, and per texture about 5 calls (active, bind, sampler, 2 texParameteri), then scissor and
    draw;
  * about **30 to 45 GL calls per draw**.
* **GUI** (`GuiRenderer.executeDrawRange`, `GuiRenderer.java:207-224`): similar to features, batched by pipeline,
  texture and scissor.

---

## 7. Measured Apple GL costs (microbenchmarks on this M5)

CPU wall time per iteration from C (no Java/JNI overhead). "Incl. finish" includes `glFinish` amortised over the loop.
All modes were warmed up before measurement.

| Operation | CPU per iteration |
|---|---|
| `glDrawArrays` with no state change, same FBO | **~75 ns** |
| A: `glBindBufferRange` + `glDrawElementsInstancedBaseVertex` (terrain pattern) | **285 to 340 ns** |
| B: A + Emulated VAO respec (5 attributes × bind+pointer+divisor) + IBO bind | 510 to 650 ns |
| C: B + 2 texture bindings (`glActiveTexture`, `glBindTexture`, `glBindSampler`, 2× `glTexParameteri`) | 870 to 1050 ns |
| D: C + pipeline switch every draw (`glUseProgram` + about 8 state calls) | **1.5 to 1.9 µs** |
| Per-draw map/unmap of a 16 KB UBO ring (WRITE\|UNSYNC) + draw | ~0.33 µs |
| Fallback transient map (flags 38) + unmap + draw | ~0.30 µs |
| `glBufferSubData` 256 B, no draws between (any buffer size 4 KB to 8 MB) | **15 to 23 µs** (fixed cost, independent of buffer size) |
| `glBufferSubData` 80 B UBO between draws | **~55 µs** |
| `glBufferSubData` 64 KB into an in-use 4 MB VBO + draw | **~54 to 58 µs** |
| `glBufferSubData` into an *unrelated* buffer with 30 ms of GPU work queued | 14 to 16 µs (no GPU wait) |
| `glCopyBufferSubData` 4 KB + draw | **~54 to 56 µs** (driver: "NEEDS IMPLEMENTATION") |
| FBO switch + 1 draw (with or without clears) | **~25 µs** in a loop; 12 to 17 µs after heavy work |
| Render pass emulation as the backend does it (FBO bind, scissor, 2× clearBuffer+getError, viewport, drawBuffers, draw, bind 0) | **25 to 27 µs** |
| Same FBO, `glClearBufferfv` + draw | ~0.44 µs |
| `glBlitFramebuffer` 512² RGBA8 | **37 to 40 µs** |
| `glTexSubImage2D` 16×16 into an idle texture | ~0.7 µs |
| `glTexSubImage2D` into a texture *sampled* by in-flight work | ~18 µs |
| `glTexSubImage2D` into a texture that is the *render target* of queued work | **~29 to 31 ms (full drain)** |
| `glFenceSync` + `glClientWaitSync(FLUSH, ∞)`, idle GPU | 9 to 16 µs |
| `glClientWaitSync(FLUSH, timeout 0)` right after enqueuing work | **27 to 43 µs** |
| `glGetError` | 4 ns |
| buffer create + delete (256 KB, no upload) | 0.15 to 0.3 µs |
| `clear + draw + glFinish` round trip | 0.19 to 0.43 ms |

How to read these numbers: Apple's GL is a state-tracking layer that records Metal commands into a render encoder. It
is cheap while state stays inside one encoder (about 75 to 300 ns per draw). Anything that needs a different encoder is
expensive: a new framebuffer, a buffer or texture copy, an upload with a blit, or a command-buffer flush. The FBO-change,
`BufferSubData`, `CopyBufferSubData` and flush costs all cluster around 15 to 55 µs, which is consistent with "end the
encoder, maybe commit, start a new encoder". This is inferred from timings; Apple does not document the internals.

---

## 8. Where the CPU time goes on macOS GL

### 8.1 Per draw

| Draw kind | Apple GL driver (measured equivalent) | Java (frontend + backend bookkeeping; est., unmeasured) | Total |
|---|---|---|---|
| Terrain section layer (`DrawSeparate`) | 0.29 to 0.34 µs | 0.1 to 0.2 µs (HashMap put/get by name, `BooleanList` dirty scan, slice record allocation) | **~0.4 to 0.55 µs** |
| Terrain with uber-heap change (VAO respec) | +0.2 to 0.3 µs | — | ~0.7 µs |
| Feature / entity / particle / GUI draw | 1 to 1.9 µs (B to D) | 0.3 to 0.6 µs (re-sending every named uniform on `setPipeline`, 4 to 5 `setUniform`, lambdas, records) | **~1.3 to 2.5 µs** |

### 8.2 Per frame (steady state, RD12, estimates built from the unit costs)

| Item | Count (est.) | Unit | Total |
|---|---|---|---|
| Render passes and standalone clears (FBO switches) | 12 to 20 | 12 to 27 µs | 0.2 to 0.5 ms |
| `glCopyBufferSubData` (3 `StagedVertexBuffer.upload`s) | 3 to 6 | ~55 µs | 0.15 to 0.33 ms |
| `glBufferSubData` (globals, projection, lighting...) | 2 to 5 | 15 to 55 µs | 0.05 to 0.25 ms |
| Terrain draws | ~2,000 to 5,000 | ~0.45 µs | 0.9 to 2.2 ms |
| Feature/entity/GUI draws | ~100 to 500 | ~1.5 to 2 µs | 0.15 to 1.0 ms |
| Present blit + submit fence + polls | 1 to 4 | 10 to 40 µs | ~0.05 to 0.1 ms |
| Chunk mesh uploads (only while sections rebuild) | 0 to 100+ | 40 to 58 µs | **0 to 5+ ms (spikes)** |

### 8.3 Reconciliation with the benchmarks on this machine

| Run | Backend | Framebuffer | Avg FPS | Median | 1% low |
|---|---|---|---|---|---|
| `vanilla-opengl-window` | GL 4.1 Metal-91.7 | 1708x960 | 222.9 | 228.7 | — |
| `vanilla-opengl-native` | GL | 3600x2260 | 228.7 | 230.8 | — |
| `smoke` | GL | 1708x960 | 183.2 | 193.4 | 103.3 |
| `vanilla-vulkan-window` | MoltenVK 1.4.2 | 1708x960 | 356.6 | 578.4 | — |
| `vanilla-vulkan-native` | MoltenVK 1.4.2 | 3600x2260 | 407.4 | 405.5 | 264.1 |

GL FPS does not change with a 5x pixel-count increase, so it is CPU/driver bound at about 4.4 ms per frame. Vulkan
through MoltenVK spends about 2.5 ms per frame on the same game code. The roughly 1.9 ms gap matches the GL-specific
items above:

* encoder breaks;
* emulated copies;
* about 0.3 to 0.5 µs extra per draw;
* no MDI terrain.

Both runs are still far from the 1 ms per frame needed for about 1000 FPS. The rest is Java-side game and frontend work,
which a Metal backend must not add to (section 12).

---

## 9. Coordinate conventions as implemented (and what Metal must match)

* **Clip-space Z.** On Mac GL the range is [-1,1] (no clip_control). The game uses **reverse-Z**:
  * near and far are swapped in `Projection.getMatrix` (`Projection.java:67-68`);
  * the depth clear value is 0.0 (`RenderSystem.java:43`, `GameRenderer.java:479-481`, `LevelRenderer.java:254-256`);
  * depth tests are `GREATER_THAN_OR_EQUAL` (`RenderPipelines.java:44,57`).

  With [-1,1] plus the GL window transform, much of reverse-Z's precision is lost. Metal is natively [0,1], so report
  `isZZeroToOne=true`. The game then builds 0..1 projections and shaders get `RENDERPEARL_DEPTH_IS_ZERO_TO_ONE`.
  Vulkan already does this.
* **Framebuffer memory layout.** In GL, row 0 of every texture and render target is NDC y = -1, and the default
  framebuffer shows row 0 at the bottom of the window. The Vulkan backend keeps the **same memory layout**:
  * positive viewport height, so NDC y = -1 lands on row 0 (`VulkanRenderPass.java:80-87`);
  * it **flips at present** (dst y from `copyHeight` to 0, `VulkanGpuSurface.java:410-417`);
  * it uses `frontFace = CLOCKWISE` with back-face culling (`VulkanRenderPipeline.java:269-270`).

  Metal's viewport maps NDC y = +1 to row 0, which is the opposite of GL/Vulkan memory order. To stay compatible with
  every render-to-texture and sample pass (post chains, OIT, `texelFetch(ivec2(gl_FragCoord.xy))` in
  `integrate_depth.fsh:9` and `oit_*`), the Metal backend must:
  * either negate `gl_Position.y` in the vertex stage (SPIRV-Cross `SPVC_COMPILER_OPTION_FLIP_VERTEX_Y = 16777220`) or
    use a negative-height viewport;
  * use clockwise front faces, as Vulkan does;
  * flip vertically at present.

  Verify visually: GUI, sky and post effects all need to come out upright.
* **Scissor and render area** are passed straight through by both GL (`glScissor` in lower-left window coordinates,
  which here equals memory rows) and Vulkan (top-left, also memory rows). For example, `GuiItemAtlas` computes
  `y = textureSize - bottom` (`GuiItemAtlas.java:95-107`). Pass them unchanged to Metal once the memory layout matches
  Vulkan.
* **Texture uploads:** row 0 of `NativeImage` goes to texture row 0, and `v=0` samples row 0 on every backend.
  Readback (`glReadPixels`) returns row 0 first.
* **Depth range in OIT:** `oit_common.glsl:17-19` remaps depth only when `RENDERPEARL_DEPTH_IS_ZERO_TO_ONE` is not
  defined.
* **Point size:** GL enables `GL_PROGRAM_POINT_SIZE` (`GlDevice.java:148`). `debug_point.vsh` with `POINTS` topology
  (`RenderPipelines.java:266-273`) needs `[[point_size]]` in MSL (SPIRV-Cross `MSL_ENABLE_POINT_SIZE_BUILTIN`).
* **Seamless cube maps** are enabled in GL (`GlDevice.java:147`). Metal cube sampling is always seamless.

---

## 10. Quirks and latent bugs in the GL backend (do not copy)

1. **Fallback transient memory recycles blocks at submit, not at GPU completion.** `Fallback.rotate()` runs the
   allocator's recycle immediately (`GlTransientMemory.java:71-74`). `submit()` has only waited for submit N-1, so a block
   used in submit N can be handed out in N+1 and written through `UNSYNCHRONIZED` maps (flags 38, `:152,191,205`) while
   N is still executing. The bug is latent because nothing uses transient memory per frame on Mac. A Metal transient
   ring must retire blocks on completion (MTLSharedEvent or completion handler).
2. The depth readback in `copyTextureToBuffer` sets `glReadBuffer(GL_NONE)` on the shared `readFbo` and never restores
   it (`GlCommandEncoder.java:391-393`). A later colour readback through `readFbo` would read from NONE.
3. `maxColorAttachments` is filled from `GL_MAX_DRAW_BUFFERS` (`GlHeuristics.java:100`), which happens to equal 8.
4. One blend function is used for all colour attachments (`GlRenderPipeline.java:65-87`).
5. `GlRenderPass.setPipeline` always dirties vertex and index state, even for the same pipeline (`:76-77`). The frontend
   also re-sends every uniform on every `setPipeline` (`FrontendRenderPass.java:125`). Together these force a full VAO
   respecification and a rebind of every uniform on each feature draw.
6. Emulated DSA binds and unbinds `GL_ELEMENT_ARRAY_BUFFER` for index-usage buffers (`GlUtil.java:9-10`,
   `DirectStateAccess.java:151-205`) while a VAO is still bound. That clobbers the VAO's element binding. It is survivable
   only because index buffers are rebound after every `setPipeline`/`setIndexBuffer` and buffer edits are forbidden
   inside passes.
7. `FrameBufferCache.getFbo` allocates a `CacheKey` (`int[]` + `ArrayList`) on every lookup (`FrameBufferCache.java:25`),
   which is Java garbage per pass.
8. Texture views are emulated by mutating `BASE/MAX_LEVEL` on the shared texture at bind time
   (`GlCommandEncoder.java:598-599`). This is a per-draw texture state change and cannot express two simultaneous views.

---

## 11. Metal mapping

### 11.1 Device, window, surface

| GL concept | Metal | Notes and pitfalls |
|---|---|---|
| hidden utility window + `SDL_GL_CreateContext` (`GlDevice.java:71-114`) | `MTLCreateSystemDefaultDevice()` + one `MTLCommandQueue` (or `MTL4CommandQueue`) | No window is needed to create the device |
| `GlBackend.createWindow` attributes | `SDL_CreateWindow(..., SDL_WINDOW_METAL \| flags)`, then `SDLMetal.SDL_Metal_CreateView` and `SDL_Metal_GetLayer` give a `CAMetalLayer` (bindings exist in `lwjgl-sdl-3.4.3.jar`) | Set `drawableSize` to the pixel size (Retina) on `configure` |
| `GlSurface.configure` (swap interval) | FIFO sets `displaySyncEnabled = YES`; IMMEDIATE sets `NO`. Also `maximumDrawableCount` 2 or 3 and `framebufferOnly = YES` | MAILBOX is not exposed by GL. Metal can report only FIFO and IMMEDIATE |
| `acquireNextTexture` (no-op) | **Keep it a no-op** (iconified check). Call `[layer nextDrawable]` lazily inside `blitFromTexture` | The game calls acquire at the start of the frame (`Minecraft.java:1273`), before most CPU work. Taking the drawable there would hold it for the whole frame and can block (default 1 s timeout) |
| `presentTexture` (`glBlitFramebuffer` to FBO 0) | A tiny render pass into the drawable: a full-screen triangle samples the main target and **flips Y** | `CAMetalLayer` does not support `RGBA8Unorm` (use `BGRA8Unorm`, non-sRGB, to match GL's raw output). A blit encoder cannot swizzle RGBA to BGRA |
| `present()` (`SDL_GL_SwapWindow`) | `[cmdBuf presentDrawable:]` encoded in `blitFromTexture`, then committed by `submit()`. `present()` becomes bookkeeping | The game order is blit, `submit()`, `present()` (`Minecraft.java:1322,1337,1340`) |

### 11.2 Command stream, submit, fences, transient memory

| GL | Metal |
|---|---|
| One implicit command stream, one `GlCommandEncoder` | One "current" `MTLCommandBuffer` per frame (Metal 4: an `MTL4CommandBuffer` with an `MTL4CommandAllocator` per frame in flight). `createCommandEncoder()` must stay allocation-free |
| `submit()`: `glFenceSync` and wait N-1 | `commit` and signal an `MTLSharedEvent` with value = submit index. Wait (`waitUntilSignaledValue:timeoutMS:` or a completion-handler semaphore) only when more than K frames are in flight. K=2 matches GL; K=3 gives more overlap |
| `GlFence(submitIndex)` and `awaitCompletion(timeout)` | Compare `sharedEvent.signaledValue >= index`. A timeout of 0 is a pure read with no flush. Never commit just to poll (GL's flush bit costs 27 to 43 µs) |
| Transient blocks, valid until the next submit | Per-frame bump allocator over shared `MTLBuffer` pages, retired when the submit's event value is signalled. `TransientGpuBuffer.isClosed` semantics stay "closed after `submit()`" |
| `RenderSystem.queueFencedTask` callbacks | unchanged; they rely on `GpuFence` |

### 11.3 Buffers

| GL | Metal | Pitfalls |
|---|---|---|
| `glBufferData`/`glBufferStorage` | `newBufferWithLength:options:MTLResourceStorageModeShared` (unified memory) | Consider `MTLResourceHazardTrackingModeUntracked` plus explicit fences or barriers. Metal 4 has no automatic tracking |
| `GlBuffer.map` (whole buffer, UNSYNC) | `contents` + offset. Persistent, no unmap cost, so report `persistentMapping=true` | Keep the same no-sync semantics (the game fences itself). `MAP_READ` data is visible after the fence |
| `writeToBuffer` (copy-at-call semantics) | memcpy into a transient staging slice, then a `MTLBlitCommandEncoder copyFromBuffer`. Batch all blits issued between two passes into one blit encoder | **Never end a render encoder for this.** The frontend already forbids it inside a pass. If the destination range has not been used by this or an in-flight submit, a direct memcpy into `contents` needs no blit |
| `copyToBuffer` | blit `copyFromBuffer:...` | The same batching rules apply |
| Index buffers | `drawIndexedPrimitives:...indexBuffer:indexBufferOffset:` with `firstIndex*bytes` | Indices never reach 0xFFFF for 16-bit (`IndexType.least`, `IndexType.java:13-15`), so Metal's always-on restart index is safe |
| UBO slice bind (`glBindBufferRange`) | `setVertexBuffer:offset:atIndex:` / `setFragmentBuffer`, `setVertexBufferOffset:` when only the offset changes, `setVertexBytes` for under 4 KB of data. Metal 4: `MTL4ArgumentTable setAddress:(gpuAddress+offset) atIndex:` | Buffer indices are shared between vertex-attribute buffers and constants per stage (31 slots). Reserve a range for vertex streams |

### 11.4 Render passes and clears

| GL | Metal | Pitfalls |
|---|---|---|
| FBO from `FrameBufferCache` | `MTLRenderPassDescriptor` (cacheable per attachment set). For a view: `level = view.baseMipLevel` or the view texture itself | — |
| `glClearBuffer` inside `createRenderPass` (scissored to the render area) | `loadAction = Clear` when `renderArea` is the full attachment; otherwise `Load` plus a clear-quad draw with a scissor | Metal load-action clears ignore render area and write masks |
| `glViewport(0,0,w,h)` | `setViewport` (full attachment, with the Y-flip convention from section 9) | — |
| scissor always on (default = render area) | `setScissorRect` (must lie inside the attachment; the frontend validates `enableScissor`) | — |
| Standalone clears (`clearColorTexture`, `clearDepthTexture`, `clearColorAndDepthTextures`) | Defer them. If the next render pass on the same attachments would `Load`, turn it into `Clear` ("clear folding"). Otherwise emit an empty render encoder per mip with `loadAction=Clear` and `storeAction=Store` | Saves full-screen store/load bandwidth. The game clears the main target twice per frame (`GameRenderer.java:478-481`, `LevelRenderer.java:247-256`) before passes that load it |
| Region clear (`GuiItemAtlas.java:99-111`) | clear-quad with scissor, or a pass with clear only if the region is the full texture | — |
| `glBindFramebuffer(0)` at pass end | `endEncoding`. Optionally keep the encoder open if the next pass uses identical attachments with no clear and nothing in between (lazy end) | Merges consecutive passes, e.g. "See through" and "Always on top" when their depth attachments match |
| `copyTextureToTexture` (`glBlitFramebuffer`) | blit `copyFromTexture:sourceSlice:sourceLevel:...` | Formats must match; no scissor applies |
| `copyTextureToBuffer` (PBO readback) | blit `copyFromTexture:...toBuffer:destinationOffset:destinationBytesPerRow:` | `bytesPerRow = width*bpp` (GL's tight `PACK_ROW_LENGTH`). The callback stays fence-gated |
| `writeToTexture` / `copyBufferToTexture` | memcpy into staging, then blit `copyFromBuffer:...toTexture:` with `sourceBytesPerRow = sourceWidth*bpp` and the x/y skip folded into the offset | Do **not** use `replaceRegion` on textures that may be in flight. GL's equivalent stalled for 30 ms when the texture was a render target of queued work |

### 11.5 Pipelines and per-draw state

| GL | Metal | Pitfalls |
|---|---|---|
| SPIRV-Cross to GLSL 330, link at `finishCompile` on the GL thread | SPIRV-Cross to MSL (`SPVC_BACKEND_MSL = 3` in lwjgl-spvc), then `newLibraryWithSource` and `newRenderPipelineStateWithDescriptor`, **all in `compilePipeline` on the worker** | Make `finishCompile` trivial. Cache with `MTLBinaryArchive` or the Metal 4 compiler |
| Name-based binding (`_uniform_00_ii`, `GlProgram.java:59-110`) | SPIRV-Cross MSL resource bindings: `(set 0, binding i)` maps to an explicit `[[buffer(n)]]`, `[[texture(n)]]` or `[[sampler(n)]]` | Keep frontend index *i* as the key |
| `GlRenderPipeline` + VAO | `MTLRenderPipelineState`: shaders, `MTLVertexDescriptor` with layouts per slot (`stride`; `stepFunction=perInstance` when `stepRate>0`) and attributes (`format`, `offset`, `bufferIndex`), colour pixel formats, per-attachment blend and write mask, depth/stencil pixel format, rasterSampleCount. `MTLDepthStencilState` holds compare and write. Cull, winding, fill mode and depth bias are encoder state | GL tolerates a depth test without a depth attachment and depth-less pipelines in passes that have one. Metal needs the PSO's `depthAttachmentPixelFormat` to equal the pass's, so **create PSO variants lazily per attachment-format signature**. Null colour targets (GL mask 0) use `MTLPixelFormatInvalid` |
| `VertexArray.Emulated.bind` per change | `setVertexBuffer:offset:atIndex:` per used slot; `setVertexBufferOffset` for offset-only changes | Removes about 0.2 to 0.3 µs per draw on GL |
| Texture views via `BASE/MAX_LEVEL` at bind | real views created once in `createTextureView` (`newTextureViewWithPixelFormat:textureType:levels:slices:`) | — |
| `GlSampler` | `MTLSamplerState` created once: min NEAREST gives `minFilter=Nearest, mipFilter=Linear`; LINEAR gives `Linear/Linear`; `lodMaxClamp=maxLod`; `maxAnisotropy` | Matches GL's `*_MIPMAP_LINEAR` choice exactly |
| UTB (`glTexBuffer`) | `MTLTextureTypeTextureBuffer` view of the `MTLBuffer` (`newTextureWithDescriptor:offset:bytesPerRow:`), or SPIRV-Cross `MSL_TEXTURE_BUFFER_NATIVE` | Offset alignment rules apply |
| `TRIANGLE_FAN` (sky, every frame) | **No fan primitive in Metal.** Convert to an indexed list: `(0,i,i+1)`, generated from or through the bound index buffer | MoltenVK does this internally |
| `POINTS` | `MTLPrimitiveTypePoint` + `[[point_size]]` | — |
| Polygon offset (factor, units) | `setDepthBias:constant slopeScale:factor clamp:0` | GL's offset affects only filled polygons |
| `GL_LINE` polygon mode | `setTriangleFillMode:Lines` | — |
| Indirect draws | `drawIndexedPrimitives:...indirectBuffer:indirectBufferOffset:` in a loop (stride 20), or an ICB | `MTLDrawIndexedPrimitivesIndirectArguments` {indexCount, instanceCount, indexStart, baseVertex, baseInstance} has the **same layout** as the 20-byte `IndexedDraw` the game writes (`DynamicGpuData.java:128-133`) |

### 11.6 Explicit-depth workaround (Apple)

`isExplicitDepthRequired=true` makes the OIT shaders write `gl_FragDepth = gl_FragCoord.z`, which costs early depth/HSR
on those passes. A native backend could compile with `MTLCompileOptions.preserveInvariance = YES` and mark the position
invariant (SPIRV-Cross also has `MSL_INVARIANT_FP_MATH`), then test whether the depth-equality artefacts the hint guards
against still occur. Only clear the hint if they do not. It matters only with improved transparency on.

### 11.7 Error handling and lifetime

* GL OOM is detected via `glGetError` (`GlDevice.java:258-265,284-291`). In Metal, buffer or texture creation returns
  `nil`; throw `GpuOutOfMemoryException` there.
* GL deletes are deferred by the driver. Metal (and especially Metal 4, whose command buffers do not retain resources)
  needs a **deferred destruction queue keyed by submit index**. Vulkan has `backend/vulkan/DestructionQueue.java`.
* GL silently uses texture 0 or stale bindings for missing uniforms (null values are ignored, `GlCommandEncoder.java:584`).
  Outside strict validation the frontend does not check that uniforms are present (`FrontendRenderPass.java:509`).
  **Bind dummy resources for unset slots** in Metal to avoid GPU faults.

### 11.8 Debug

* Labels and debug groups: `MTLCommandBuffer` / encoder `pushDebugGroup`, `label`. Gate them on `useLabels`; the GL Ext
  path also skips groups.
* Validation: `MTL_DEBUG_LAYER=1` and `MTL_SHADER_VALIDATION=1` for development, plus
  `MTLCommandBufferDescriptor.errorOptions = EncoderExecutionStatus`.

### 11.9 Timestamps

GL's `glQueryCounter` can be placed anywhere. On Apple GPUs, `MTLCounterSampleBuffer` timestamps are only supported at
**stage boundaries** (`sampleBufferAttachments` on pass, blit or compute descriptors), not mid-encoder. Map
`writeTimestamp` to the nearest encoder boundary, or use `MTLCommandBuffer.GPUStartTime`/`GPUEndTime` for
`TimerQuery`-style frame timing. Calibrate with `[device sampleTimestamps:&cpu gpuTimestamp:&gpu]`.

---

## 12. Lessons for the Metal backend (what to avoid)

1. **Never split a render encoder or commit a command buffer for a data update.** The defining cost of Apple GL is 15 to
   55 µs per encoder break: FBO switch, `BufferSubData`, `CopyBufferSubData`, blit, flush. Uploads should be:
   * a memcpy into a mapped ring, or
   * blits batched into one blit encoder between passes, or
   * front-loaded at the start of the frame's command buffer.
2. **One command buffer per frame** (at most two: uploads plus frame). No commit per pass or per sync poll. Poll fences by
   reading `MTLSharedEvent.signaledValue` (GL's poll costs 27 to 43 µs because of the flush bit).
3. **Report the features GL lacks** (section 3) so the game takes its fast paths: MDI terrain (`LevelRenderer.java:178-181`,
   which needs `nonZeroFirstInstance` and `maxDrawIndirectDrawCount>0`), persistent mapping, and 0..1 depth.
4. **Do not respecify vertex input per draw.** Vertex layout belongs in the PSO. Per draw, only bind buffers and offsets,
   and skip unchanged ones. GL re-issues 15 or more calls on every `setPipeline`.
5. **Do not mutate texture state at bind time.** Use real `MTLTexture` views and immutable `MTLSamplerState`. GL calls
   `glTexParameteri` twice per texture per draw.
6. **Shadow-cache encoder state yourself.** Cull mode, winding, fill mode, depth bias, depth/stencil state and PSO are
   cheap in Metal but not free across JNI. Skip redundant calls, especially since the frontend re-sends every uniform on
   every `setPipeline` (`FrontendRenderPass.java:125`).
7. **Keep JNI or FFM crossings per draw minimal.** LWJGL 3.4.3 has no Metal bindings; raw `objc_msgSend` through
   `ObjCRuntime`/JNI is about 20 to 40 ns per call. At 1000 FPS with 3,000 or more draws, budget about 100 to 200 ns per
   draw in total. Options:
   * a small native shim (C/ObjC, built with the installed clang) that decodes a compact command stream from a direct
     `ByteBuffer`, one crossing per pass or batch;
   * JDK 25 FFM downcalls with `Linker.Option.critical(true)`.
8. **Acquire the drawable as late as possible and present through the command buffer** (section 11.1). Do not put a
   blocking `nextDrawable` at the start of the frame, where the game calls `acquireNextTexture`.
9. **Retire transient memory on GPU completion**, not at submit (see bug 10.1).
10. **Lazily create PSO variants per attachment signature** (colour formats, depth format, sample count). GL has no such
    constraint, so the game freely mixes depth and depth-less pipelines and passes.
11. **Compile pipelines entirely off the render thread.** GL forces `glCompileShader`/`glLinkProgram` onto the GL thread
    via `finishCompile`.
12. **Handle Metal-missing GL features explicitly:**
    * triangle fans (sky);
    * sub-rect clears;
    * RGBA8 to BGRA8 present;
    * Y flip and winding;
    * 0..1 depth;
    * point size.
13. **Copy caller data immediately** in `writeToBuffer`/`writeToTexture`. Callers free `MemoryStack` or `NativeImage`
    memory right after the call returns.
14. **Do not translate GL-isms that exist only for missing GL features:**
    * the single blend function;
    * the 256-byte uniform alignment;
    * whole-buffer texel buffers;
    * hidden context windows;
    * `glGetError` after clears;
    * `UNSYNCHRONIZED` whole-buffer map/unmap.

---

## 13. Game-side behaviours that exist only because of GL (Metal can skip or enable)

| Behaviour | Where | Why it exists | With Metal |
|---|---|---|---|
| Terrain via `DrawSeparate`: one draw per section layer plus a per-draw `ChunkSection` UBO rebind | `LevelRenderer.java:178-181,264-270,811-861`, `ChunkSectionsToRender.java:161-191` | no `ARB_base_instance` (and `maxDrawIndirectDrawCount=1`) on Mac GL | Report `nonZeroFirstInstance=true` and `maxDrawIndirectDrawCount=MAX`. The game then uses `DrawIndirect` (`LevelRenderer.java:899-963`): per-section info as an instanced vertex stream (`writeChunkSectionsInstanced`, `DynamicGpuData.java:100-111`) and one indirect multi-draw per draw group |
| ChunkSection UBO padded from 16 to 256 B | `DynamicGpuDataStorageMapped.java:29` | GL UBO offset alignment 256 | Smaller `minUniformOffsetAlignment`, or the MDI path, which does not use UBOs for section info |
| [-1,1] depth projections and the OIT depth remap | `Projection.java:69`, `Camera.java:188`, `oit_common.glsl:17-19` | no `clip_control` | `isZZeroToOne=true` |
| `StagingBuffer.Cpu`: a malloc'd 98 MiB staging area, then `writeToBuffer` per mesh | `StagingBuffer.java:16-20,94-116`, `SectionRenderDispatcher.java:69` | `persistentMapping=false` (and `writeToBufferIsSlow=false`) | `writeToBufferIsSlow=true` with `persistentMapping=true` selects `PersistentlyMapped` (`StagingBuffer.java:119-162`): the game writes straight into a mapped ring, and uploads become GPU blits (`copyToBuffer`). This saves one memcpy per mesh and all per-call driver cost |
| `StagedVertexBuffer` copies a staging buffer into a separate vertex/index buffer every frame (2 `copyToBuffer` per upload, 3 uploads per frame) | `StagedVertexBuffer.java:126-168` | discrete-GPU style VRAM placement | The backend must honour the copy. A game patch could draw directly from the shared staging buffer on unified memory (section 14) |
| `gl_FragDepth = gl_FragCoord.z` in OIT shaders | `GlslCompiler.java:85-87`, `oit_sample.glsl:101-103`, `oit_add_transmittance.glsl:46-48` | Apple depth-invariance issue (GL and MoltenVK) | Possibly removable with invariance (section 11.6) |
| Two full-target clears per frame before passes that load | `GameRenderer.java:478-481`, `LevelRenderer.java:247-256` | clears are cheap on immediate-mode GL | Clear folding in the backend |
| Default API order: GL first, then Vulkan | `PreferredGraphicsApi.java:33-37` | — | Add the Metal backend first on macOS |

---

## 14. Perf opportunities

**Backend (Metal):**

* Clear folding and lazy encoder end/merge, which cut render encoders and full-screen tile store/load traffic. At
  3600x2260, RGBA8 plus D32F is about 65 MB per split.
* A persistent ring for all dynamic data, giving zero-cost `map()`. `setVertexBytes` for under 4 KB of constants.
* Batched upload blits at the start of the command buffer. Direct memcpy into shared buffers when the range is provably
  idle.
* A native command-stream decoder: one crossing per pass, encoder calls in C.
* PSO and depth-state caches keyed by integers. Precompile variants in `compilePipeline`.
* Indirect multi-draw for terrain, or an ICB, so terrain costs O(draw groups) CPU instead of O(sections).
* Metal 4 argument tables and residency sets: bind by GPU address, no per-draw `useResource`, explicit barriers.
* Present via one tiny pass into the drawable (`framebufferOnly`).

**Game side (patchable, since we ship a custom build):**

* Draw directly from `StagedVertexBuffer` staging buffers (unified memory), removing 3 to 6 buffer copies per frame.
* Stop re-sending every uniform in `FrontendRenderPass.setPipeline` (`:125`) when the pipeline is unchanged. Use
  index-based uniform setters instead of `HashMap<String,Object>` lookups on hot paths.
* Remove per-draw `pushDebugGroup` lambda allocations and `slice()` record allocations on hot paths
  (`PreparedRenderType.java:38,47`, `FrontendRenderPass.java:357`).
* Render the final GUI composite directly into the drawable instead of the main target plus a present blit (saves a
  full-screen copy, about 47 MB per frame at 3600x2260).
* Merge consecutive passes that target the same attachments ("See through" and "Always on top", hand passes), or let the
  backend merge them.

---

## 15. Open questions

1. What is the exact minimum constant-buffer offset alignment on M5 (Apple family vs Mac2 family rules)? Check the Metal
   Feature Set Tables before reporting `minUniformOffsetAlignment` below 256 (candidates 4, 16 or 32).
2. Is Apple GL's ~25 µs per FBO change caused by a command-buffer commit or only an encoder restart? The timings cannot
   tell them apart. It does not change the Metal design, but Instruments' Metal System Trace on the GL app would show
   the encoder and command-buffer count per frame.
3. Real per-frame counts (draws, passes, uploads) at RD12 for both terrain paths. Estimates in section 8 are not measured
   in-game; a Tracy capture or a counting shim in the frontend would settle them.
4. Does native Metal with `preserveInvariance` remove the need for `isExplicitDepthRequired` (OIT)?
5. Colour space and visual parity of `CAMetalLayer` (colorspace nil vs sRGB) against the GL default framebuffer. Compare
   screenshots before and after.
6. `timestampPeriod` and the domain of Metal counter timestamps on M5 (are they ns?), and how to support mid-pass
   `writeTimestamp`.
7. Whether `waitUntilSignaledValue:timeoutMS:` or completion handlers give lower frame-pacing jitter at around 1000 FPS
   with IMMEDIATE present (`displaySyncEnabled=NO`).

---

## Appendix A: microbenchmark method

* Plain C against `OpenGL.framework`, built with `/usr/bin/clang -O2`. Context: offscreen CGL with
  `kCGLPFAOpenGLProfile = 3.2 core`, which gives the same 4.1 Metal driver the game uses, and the MT engine off. Timing
  uses `mach_absolute_time`. No window, no game, nothing installed.
* Source lived in the session scratchpad (`glprobe*.c`), which is ephemeral.
* Patterns:
  * mode A to D draw loops of 20,000 iterations replicate the GL backend's per-draw call sequences (`setupDraw`, Emulated
    VAO, sampler binding with `BASE/MAX_LEVEL`, pipeline bind);
  * upload tests replicate `writeToBuffer` (bind, `BufferSubData`, unbind), `copyToBuffer`, the Fallback transient map
    (flags 38), the Mutable whole-buffer map with WRITE\|UNSYNC preceded by `glGetError`, and the render-pass emulation
    from `createRenderPass` and `submitRenderPass`;
  * "in-flight" tests queue about 30 ms of fragment work into a 4096² RGBA16F target before the measured call.
* Caveats:
  * the tests use small GPU workloads;
  * the Java side is excluded (LWJGL adds roughly 5 to 15 ns per GL call);
  * first-iteration costs (PSO creation inside Apple's driver) are excluded by warm-up but appear in the game as hitches.
