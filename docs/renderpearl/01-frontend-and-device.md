# 01 — RenderPearl frontend layer and device/capability API

Reference for implementing a native Metal backend (`GpuDeviceBackend` / `CommandEncoderBackend` /
`RenderPassBackend` / `GpuSurfaceBackend`) for Minecraft Java 26.3 RenderPearl.

All paths are relative to `src/vanilla/`. `F*` = `com/mojang/renderpearl/frontend/`,
`A*` = `com/mojang/renderpearl/api/`, `B*` = `com/mojang/renderpearl/backend/`.
Line numbers refer to the Vineflower-decompiled 26.3 sources in this workspace.

---

## 0. TL;DR for the Metal implementer

1. **The frontend is a thin validating shim.** `FrontendGpuDevice`, `FrontendCommandEncoder`, `FrontendRenderPass`,
   and `FrontendGpuSurface` wrap your backend objects 1:1, do argument checks, keep a little state
   (an "in render pass" flag, bound pipeline, uniform map, vertex buffer table, debug-group depth), and forward.
   Most deep checks only run when `FrontendGpuDevice.STRICT_VALIDATION` is true (F`FrontendGpuDevice.java:36`).
   That flag is `SharedConstants.IS_RUNNING_IN_IDE`, which nothing assigns in the shipped game
   (`net/minecraft/SharedConstants.java:128`). In production it is **false**, so your backend gets no
   missing-uniform, missing-vertex-buffer, missing-index-buffer, or closed-buffer checks.
2. **There is exactly ONE command encoder per device, for the whole process lifetime.**
   `GpuDeviceBackend.createCommandEncoder()` is called once, in the `FrontendGpuDevice` constructor
   (F`FrontendGpuDevice.java:50`). After that, `GpuDevice.createCommandEncoder()` is a getter that returns the
   same `FrontendCommandEncoder` (F`FrontendGpuDevice.java:60-62`). The game calls it 71 times in
   `net/` and `com/mojang/blaze3d/`, and every call gets the same object. Think of it as an immediate context.
   It is not a per-frame object.
3. **`submit()` is called once per frame**, at `net/minecraft/client/Minecraft.java:1337`, between
   `blitFromTexture` (`:1322`) and `present()` (`:1340`). Both existing backends treat it as the end of a frame:
   they close the recording, signal a per-submit counter, **block until submit N-1 has finished on the GPU**
   (at most 2 submits in flight), and rotate transient memory and deferred-destroy queues
   (B`vulkan/VulkanCommandEncoder.java:263-279`, B`opengl/GlCommandEncoder.java:75-83`).
4. **Fences are per-submit counters, not mid-stream markers.** A `GpuFence` created while recording submit S
   completes when the whole of submit S completes. If you wait with a non-zero timeout on a fence for the
   submit you are still recording, both backends throw `IllegalStateException`
   (B`vulkan/VulkanCommandEncoder.java:848-853`, B`opengl/GlCommandEncoder.java:90-95`).
5. **The render-pass model is begin/end with no subpasses and no viewport API.** The viewport is always the full
   attachment. The render area is always the full attachment in vanilla, because no game code builds a custom
   `RenderArea`. Each attachment is either cleared (if a clear value is present) or loaded, and is always stored.
   The frontend allows only one open render pass, and no copy, clear, or fence calls while it is open.
6. **Capabilities to claim so the fast paths unlock:**
   * `drawIndirect=true`, `multiDrawIndirect=true`, `nonZeroFirstInstance=true`,
     `maxDrawIndirectDrawCount=Integer.MAX_VALUE`, and `multiDrawIndirectHasKnownIssues=false`.
     Together these switch terrain to the multi-draw-indirect (MDI) path (`net/minecraft/client/renderer/LevelRenderer.java:178-181`).
   * `persistentMapping=true` and `writeToBufferIsSlow=true`. Together these switch chunk-mesh uploads to
     persistently mapped staging buffers plus GPU copies (`com/mojang/blaze3d/vertex/StagingBuffer.java:16-20`).
   * `isZZeroToOne=true`. This is mandatory for Metal: the projection matrices and shaders depend on it.
7. **Coordinate conventions** (section 13): reverse-Z with a [0,1] depth range and depth cleared to 0.0. Memory row 0
   is NDC y = -1, matching GL and Vulkan memory layouts. The present blit must flip Y. Scissor and render area
   coordinates are in attachment texel space counted from row 0.
8. **The main thread is the render thread** (`net/minecraft/client/main/Main.java:270`). Every frontend call is
   made from it, with two exceptions. `GpuDeviceBackend.compilePipeline` runs on worker threads, and
   `BackendRenderPipeline.Pending.finishCompile` runs back on the render thread. Separately, chunk-meshing
   worker threads `memcpy` into mapped staging memory.

---

## 1. Object graph and ownership

```
GpuBackend (GlBackend | VulkanBackend | <MetalBackend>)            api/device/GpuBackend.java
  └─ createDevice(GpuDebugOptions) ─► new FrontendGpuDevice(new <X>Device(...))
        FrontendGpuDevice  implements GpuDevice                    frontend/FrontendGpuDevice.java
          ├─ backend : GpuDeviceBackend   (your MetalDevice)
          ├─ profiler: TracyGpuProfiler?  (only if TracyClient.isAvailable())
          ├─ encoder : FrontendCommandEncoder (singleton)
          │     └─ backend : CommandEncoderBackend  (= backend.createCommandEncoder(), called once)
          │     └─ createRenderPass(desc) ─► FrontendRenderPass(backendEncoder.createRenderPass(desc), ...)
          │                                     └─ backend : RenderPassBackend
          └─ pipelineBuilder : PipelineBuilder (GLSL→SPIR-V via shaderc, reflection) → backend.compilePipeline
  createSurface(window, isIconified) ─► new FrontendGpuSurface(backend.createSurface(...))
  compilePipeline(...) ─► FrontendRenderPipeline(record) wrapping BackendRenderPipeline
```

* The backend gets `GpuTexture`, `GpuTextureView`, `GpuBuffer`, and `GpuSampler` objects that **it created
  itself**. The frontend never wraps them, so downcasting them in the backend is safe. Vulkan does this, for
  example `(VulkanGpuTextureView) attachment.textureView()` at B`vulkan/VulkanCommandEncoder.java:293`.
* **Your buffer class must extend `backend/common/BaseGpuBuffer`.** `FrontendRenderPass` casts buffers to it
  for `checkCanBeUsed()` under STRICT_VALIDATION (F`FrontendRenderPass.java:363,373,519,536,554`). The method
  is a no-op hook (B`common/BaseGpuBuffer.java:24-25`). Textures and views should likewise extend
  `BaseGpuTexture`/`BaseGpuTextureView`. The view's `getWidth(mip)` adds `baseMipLevel`
  (B`common/BaseGpuTextureView.java:33-40`), and the frontend depends on that.
* Pipelines: the frontend checks `pipeline instanceof FrontendRenderPipeline` (F`FrontendRenderPass.java:105`)
  and passes `frontendPipeline.backendRenderPipeline()` to `RenderPassBackend.setPipeline` (F`:124`).
  `FrontendRenderPipeline` is a record holding the name, the backend pipeline, per-slot vertex formats
  (nullable), a `uniformIndices` map (name→index), the uniform descriptions, the color target states,
  `wantsDepthTexture`, and `pushConstantSize` (F`FrontendRenderPipeline.java:12-21`). `isClosed()` and `close()`
  forward to the backend pipeline (`:22-30`).
* The surface: `FrontendGpuSurface.blitFromTexture` requires a `FrontendCommandEncoder` and passes
  `frontendCommandEncoder.backend()` to your surface (F`FrontendGpuSurface.java:81,106`). Your surface therefore
  receives **your** `CommandEncoderBackend` and can downcast it, as Vulkan does at B`vulkan/VulkanGpuSurface.java:363`.

---

## 2. Backend selection and bring-up sequence

### 2.1 Where the choice is made

| Step | Code | Notes |
|---|---|---|
| CLI flag | `net/minecraft/client/main/Main.java:82-84` (`--graphicsBackend <opengl\|vulkan\|default>`), parsed at `:203`, stored in `GameConfig.game.forcedGraphicsApi` (`net/minecraft/client/main/GameConfig.java:61,84`) | jopt `EnumConverter` over `PreferredGraphicsApi` |
| Option | `Options.preferredGraphicsBackend` (`net/minecraft/client/Options.java:134-156`), saved under key `preferredGraphicsBackend` (`:1560`), restart required (`:131,138,1060,1799`); shown in the Video Settings screen (`net/minecraft/client/gui/screens/options/VideoSettingsScreen.java:83`) | Datafix `OptionsForceDefaultGraphicsApiFix` resets it to `"default"` (`net/minecraft/util/datafix/fixes/OptionsForceDefaultGraphicsApiFix.java:17`) |
| Try order | `PreferredGraphicsApi.getBackendsToTry()` (`net/minecraft/client/PreferredGraphicsApi.java:33-37`): `VULKAN` → `[vulkan, gl]`; otherwise (`DEFAULT`, `OPENGL`) → `[gl, vulkan]` | **DEFAULT means GL first.** |
| Crash recovery | `Minecraft.java:465-474`: if the last startup was not clean, `VULKAN` is reset to `DEFAULT` and `DEFAULT` is forced to `OPENGL` | Treat a new `METAL` value like `VULKAN` here, otherwise one bad start leaves the user stuck on GL |
| Probe | `Minecraft.java:478-480`: when DEFAULT, runs `VulkanBackend.checkBackendAvailable()` (creates and destroys a Vulkan instance through MoltenVK) only to fill `backendCreationException` for crash reports | Wasted startup time for a Metal-default build. Skip it when Metal is chosen. |
| Loop | `Minecraft.java:484-511`: for each backend, `loadLibrary()` then `createDevice(new GpuDebugOptions(options.glDebugVerbosity, SharedConstants.DEBUG_SYNCHRONOUS_GL_LOGS, gameConfig.game.renderDebugLabels, gameConfig.game.vulkanValidation))`. If it throws `BackendCreationException`, the loop logs, appends to the error message, keeps the exception (unless the reason is `OPENGL_MISSING` and one is already recorded, `:503-505`), calls `unloadLibrary()` if the library was loaded, and continues. | Throw `BackendCreationException`, not a RuntimeException, so the game falls back to GL. |
| Failure | `Minecraft.java:513-518`: message box, then `Window.WindowInitFailed` | |

### 2.2 Bring-up order (this matters for Metal)

1. `RenderSystem.initBackendSystem()` (`com/mojang/blaze3d/systems/RenderSystem.java:198-218`) runs `SDL_Init(SDL_INIT_VIDEO=32)` and SDL hints.
2. `GpuBackend.loadLibrary()`, then `createDevice(...)`. **No game window exists yet.** GL makes its own hidden
   320x480 utility window for the context (B`opengl/GlDevice.java:71`, flags `0x80020008` =
   NOT_FOCUSABLE|UTILITY|HIDDEN). Vulkan needs no window. Metal needs none either: call
   `MTLCreateSystemDefaultDevice()` and create the command queue here.
3. `RenderSystem.initRenderer(device)` (`RenderSystem.java:220-228`). This stores DEVICE, builds `DynamicGpuData`
   (`MappableRingBuffer`s, which means `createBuffer` with MAP_WRITE and `createFence` calls), and
   `samplerCache.initialize()`, which creates **32 samplers** with anisotropy 1
   (`com/mojang/blaze3d/systems/SamplerCache.java:13-31`). **Buffer, sampler, and fence creation must all work
   before any surface exists.**
4. The log prints `backendName`, `driverInfo`, `name`, `vendorName`, and `underlyingExtensions` (`Minecraft.java:522-525`).
5. `maxSize = limits.maxTextureSizeForFormat(RGBA8_UNORM)` (`:526`) is passed to `Window`. `Window` calls
   `backend.createWindow(title, w, h, 8224L)` (`com/mojang/blaze3d/platform/Window.java:165`), where
   8224 = `SDL_WINDOW_HIGH_PIXEL_DENSITY(0x2000) | SDL_WINDOW_RESIZABLE(0x20)`, then
   `SDL_SetWindowMaximumSize(maxSize, maxSize)` (`Window.java:115,745`).
   * GL adds `SDL_WINDOW_OPENGL(2)` (B`opengl/GlBackend.java:66`). Vulkan adds `SDL_WINDOW_VULKAN(0x10000000)`
     (B`vulkan/VulkanBackend.java:129`). **Metal should add `SDL_WINDOW_METAL = 0x20000000 (536870912L)`**,
     a constant present in LWJGL 3.4.3 `SDLVideo`.
6. `device.createSurface(window.handle(), window::isIconified)` (`Minecraft.java:544`). For Metal, use
   `SDLMetal.SDL_Metal_CreateView(window)` and `SDLMetal.SDL_Metal_GetLayer(view)`, which return a `CAMetalLayer*`.
   LWJGL 3.4.3 has `org.lwjgl.sdl.SDLMetal` with `SDL_Metal_CreateView/DestroyView/GetLayer`.
7. Pipelines compile during the first resource reload (section 11).
8. **Shutdown** (`Minecraft.java:1147-1161`): game resources are closed; then `windowSurface.close()`, then
   `RenderSystem.shutdownRenderer()` (closes pipeline caches, index buffers, samplers, dynamic GPU data, then
   `DEVICE.close()`, `RenderSystem.java:230-250`), then `window.close()`, then
   `RenderSystem.unloadTrackedBackendLibrary()` (calls `GpuBackend.unloadLibrary()`), then `SDL_Quit`.
   There is **no final `submit()`**. `MetalDevice.close()` must wait for the GPU to go idle (commit any open
   command buffer and wait on the last event) before releasing objects. The surface must destroy its
   `SDL_MetalView` in `close()`, before the window is destroyed.

### 2.3 Patch points for adding Metal

* `PreferredGraphicsApi`: add `METAL("metal", "options.graphicsApi.metal")` (it also needs a lang key, or the
  button shows the raw key). Return `[metal, gl, vulkan]` for METAL. On macOS, consider making DEFAULT return the same.
* `Minecraft.java:465-474`: crash-reset logic for METAL. `:478-480`: skip the Vulkan probe.
* `BackendCreationException.Reason` (A`device/BackendCreationException.java:27-38`) has no Metal reasons.
  Use `PLATFORM_ERROR`/`OTHER`, or add `METAL_*` constants. The reason's `displayName()` only shows up in crash
  reports (`Minecraft.java:2522-2527`).
* `NativeLibrariesBootstrap.loadLibraries` preloads `shaderc`, `spvc`, and `vma` only if a Vulkan loader exists
  (`com/mojang/blaze3d/platform/NativeLibrariesBootstrap.java:76,82-86`). The frontend always uses shaderc
  (`PipelineBuilder`), and a Metal backend will use `spvc` (SPIRV-Cross → MSL). LWJGL loads both lazily, so this
  is harmless. Just do not make Metal depend on `isVulkanLoaderAvailable()`.

---

## 3. `GpuBackend`, `GpuDebugOptions`, and exceptions

`A device/GpuBackend.java:5-15`:

| Method | Contract (as used) | GL / Vulkan reference | Metal |
|---|---|---|---|
| `getName()` | Used only in logs and the startup error text | `"OpenGL"`, `"Vulkan"` | `"Metal"` |
| `loadLibrary()` throws `BackendCreationException` | Idempotent. Caches the failure and rethrows it on later calls | B`opengl/GlBackend.java:27-49`, B`vulkan/VulkanBackend.java:58-87` | Load your native bridge `.dylib`, check `MTLCreateSystemDefaultDevice() != nil` and Metal 4 support (`supportsFamily:MTLGPUFamilyMetal4`, verify the name in the SDK), throw `PLATFORM_ERROR` otherwise |
| `unloadLibrary()` | Called on failure, and after shutdown (`RenderSystem.java:256-261`) | | No-op, or close the bridge |
| `createWindow(title,w,h,flags)` | Returns an SDL window handle, or 0 | ORs in the API flag | `SDL_CreateWindow(title, w, h, flags \| SDL_WINDOW_METAL)` |
| `createDevice(GpuDebugOptions)` | Must return `new FrontendGpuDevice(new MetalDevice(...))`. Throw `BackendCreationException` for any failure so the next backend gets tried | GL `:70-72`, Vulkan `:133-205` | |

`GpuDebugOptions(int logLevel, boolean synchronousLogs, boolean useLabels, boolean useValidationLayers)`
(A`device/GpuDebugOptions.java:3`), fed from `Minecraft.java:491-496`:
* `logLevel` = `options.glDebugVerbosity` (default 1, `net/minecraft/client/Options.java:504`).
* `synchronousLogs` = `SharedConstants.DEBUG_SYNCHRONOUS_GL_LOGS` (`net/minecraft/SharedConstants.java:61`).
* `useLabels` = `--renderDebugLabels` (`Main.java:80,200`). Turn on `MTLResource.label`,
  `pushDebugGroup`, and the like only when this is set, because labels cost CPU.
* `useValidationLayers` = `--vulkanValidation` (`Main.java:81,202`). For Metal, `MTL_DEBUG_LAYER=1` and
  `MTL_SHADER_VALIDATION=1` are environment variables read at device creation, so setting them from Java at
  runtime is unreliable. Document the launcher env-var approach instead.

Exceptions (A`device/`):
* `BackendCreationException(message, reason, missingCapabilities)` is checked. Only `createDevice` and `loadLibrary` throw it.
* `SurfaceException` is checked. Only `GpuSurface.configure` and `acquireNextTexture` throw it. The game catches it
  at `Minecraft.java:1265,1274` and recovers: it marks the surface invalid and reconfigures it next frame.
* `GpuOutOfMemoryException` (runtime). `MainTarget` catches it from `createTexture` and falls back to a smaller
  size (`com/mojang/blaze3d/pipeline/MainTarget.java:74-88`). GL throws it on `GL_OUT_OF_MEMORY`
  (B`opengl/GlDevice.java:259-261,285-287`). **Metal: when `newTextureWithDescriptor:` or `newBufferWithLength:`
  returns nil, throw `GpuOutOfMemoryException`.**
* `GpuDeviceLossException` (runtime). Vulkan throws it on `VK_ERROR_DEVICE_LOST` (B`vulkan/VulkanUtils.java:93`).
  No game code catches it. Metal: throw it when a command buffer finishes with `MTLCommandBufferStatusError` and
  `MTLCommandBufferErrorDeviceRemoved`, a timeout, or a page fault. Surface it on the render thread at the next
  `submit()`, not from the completion-handler thread.

---

## 4. `GpuDevice` → `GpuDeviceBackend` (FrontendGpuDevice)

`A device/GpuDevice.java:24-58` and `B api/GpuDeviceBackend.java:19-49`. All calls come from the render
thread unless noted.

| Frontend method (F`FrontendGpuDevice.java`) | Frontend validation / behavior | Forwards to |
|---|---|---|
| ctor `:42-52` | If `TracyClient.isAvailable()`, builds `TracyGpuProfiler(this)` first. **That already calls `backend.createTimestampQueryPool(1024)`, `backend.getDeviceInfo()`, and `backend.getTimestampCalibrationOffset()`** (F`TracyGpuProfiler.java:22-29`). Then it calls `backend.createCommandEncoder()` **once** and `new PipelineBuilder(backend)`, which reads `getDeviceInfo().isZZeroToOne()` and `features().shaderDrawParameters()` (F`shaders/PipelineBuilder.java:56-57`). | The backend must be fully initialized in its constructor, including its encoder. Vulkan creates its encoder in the device constructor (B`vulkan/VulkanDevice.java:140`). |
| `createSurface(window, isIconified)` `:55-57` | none | `backend.createSurface`, wrapped in `FrontendGpuSurface` |
| `createCommandEncoder()` `:60-62` | returns the singleton | (none) |
| `createSampler(u,v,min,mag,maxAniso,maxLod)` `:65-79` | `1 <= maxAnisotropy <= limits.maxAnisotropy`, else `IllegalArgumentException` | `backend.createSampler` |
| `createTexture(Supplier label, ...)` `:82-92` | The label supplier is evaluated **only if `isDebuggingEnabled()`**, otherwise null | `createTexture(String,...)` |
| `createTexture(String label, usage, format, w, h, depthOrLayers, mips)` `:95-106` | `verifyTextureCreationArgs` `:108-149`: `mips >= 1`; `mips <= log2(max(w,h))+1`; `depthOrLayers >= 1`; if CUBEMAP_COMPATIBLE (usage & 16) then square, layers % 6 == 0 and layers <= 6; otherwise depthOrLayers must be 1. **Only 2D and single cubemaps exist.** No check of w,h > 0 or format support. | `backend.createTexture` (label may be null) |
| `createTextureView(tex)` `:152-154` | delegates to the full range `(0, tex.getMipLevels())` | |
| `createTextureView(tex, baseMip, mips)` `:157-175` | Texture not closed; `baseMip >= 0 && baseMip+mips <= tex.mips`. **`mips >= 1` is not checked.** | `backend.createTextureView` |
| `createBuffer(Supplier label, usage, long size)` `:178-184` | `size > 0`. Usage flags not validated. | `backend.createBuffer` (label is a Supplier, so evaluate it lazily) |
| `createBuffer(Supplier label, usage, ByteBuffer data)` `:187-193` | `data.hasRemaining()` | `backend.createBuffer`. The Vulkan backend ORs in `COPY_DST` and uploads through an "object init" command buffer that runs before the frame's main commands (B`vulkan/VulkanDevice.java:224-252`, B`vulkan/VulkanCommandEncoder.java:171-178,186-209`). **This can be called while a render pass is open.** Metal: for Shared storage, `memcpy` at creation, since a new buffer cannot be referenced by in-flight work. Do not encode a blit on the main stream. |
| `getLastDebugMessages()` `:196-198` | none | Shown as "GL debug messages" in crash reports (`Minecraft.java:2529-2535`) |
| `isDebuggingEnabled()` `:201-203` | none | Controls label evaluation |
| `compilePipeline(pipeline, src, executor)` `:206-210` | `PipelineBuilder` drops WIREFRAME pipelines when `!features.wireframeFillMode` (F`shaders/PipelineBuilder.java:373-375`) | `backend.compilePipeline(CreateInfo)` **on an executor thread** (F`shaders/PipelineBuilder.java:377-411,466`). The returned `Pending.finishCompile()` runs later, on whichever thread finishes the frontend `Pending` (section 11). |
| `close()` `:213-216` | `pipelineBuilder.close()` then `backend.close()`. **Does not close `TracyGpuProfiler`'s query pool.** | |
| `createTimestampQueryPool(size)` `:219-221` | none | |
| `getTimestampCalibrationOffset()` `:223-225` (package-private) | Called only by `TracyGpuProfiler`, at construction and then every 5 s | Returns `hostNanoTime - deviceTimeInNanos` (section 10) |
| `getDeviceInfo()` `:228-230` | Forwarded **on every call, with no caching**. Hot callers include `FrontendRenderPass.setUniform` (`:151`), `FrontendCommandEncoder.createRenderPass` (`:105`), and the `FrontendRenderPass` ctor (`:62-63`). | Return a pre-built immutable record (GL and Vulkan both store a final field). **Must be thread-safe**, because `GlslCompiler` reads it on worker threads (F`shaders/GlslCompiler.java:85`). |

Note that `GpuDeviceBackend.createCommandEncoder()` returns `CommandEncoderBackend`, and GL and Vulkan both
return a stored singleton (B`opengl/GlDevice.java:193-195`, B`vulkan/VulkanDevice.java:186-188`).

---

## 5. `CommandEncoder` → `CommandEncoderBackend` (FrontendCommandEncoder)

### 5.1 State machine

The only frontend state is `boolean isInRenderPass` (F`FrontendCommandEncoder.java:31`).

```
          createRenderPass(desc) [validated]                 RenderPass.close()
 IDLE ───────────────────────────────────────────► IN_PASS ─────────────────────► IDLE
   ▲   isInRenderPass=true; tracy.pushZone();          │  (FrontendRenderPass.onFinish
   │   backend.createRenderPass(desc)                  │   = encoder.submitRenderPass():
   │                                                   │   isInRenderPass=false;
   └───────────────────────────────────────────────────┘   backend.submitRenderPass(); tracy.popZone())
```

* `createRenderPass` throws if a pass is already open (`:101-103`).
* `submitRenderPass()` (`:218-228`) is `protected` and runs only through the `onFinish` Runnable passed into
  `FrontendRenderPass` (`:208`), which `FrontendRenderPass.close()` invokes (F`FrontendRenderPass.java:493-502`).
* **Tracy ordering.** `pushZone` calls `writeTimestamp` **after** `isInRenderPass = true` and **before**
  `backend.createRenderPass` (`:198-204`). `popZone` runs **after** `backend.submitRenderPass()` (`:224-227`).
  The backend therefore always receives `CommandEncoderBackend.writeTimestamp` outside its own pass, even though
  the frontend thinks a pass is open.

### 5.2 Which calls are legal while a render pass is open

| Call | Checks `isInRenderPass`? | Line |
|---|---|---|
| `createRenderPass` | yes, throws | `:101` |
| `clearColorTexture`, `clearColorAndDepthTextures` (both), `clearDepthTexture` | yes, throws | `:232,242,267,283` |
| `writeToBuffer`, `copyToBuffer` | yes, throws | `:293,330` |
| `writeToTexture` (NativeImage 6-arg, ByteBuffer 8-arg; the 2-arg variant delegates) | yes, throws | `:402,466` |
| `copyBufferToTexture`, `copyTextureToBuffer` (both), `copyTextureToTexture` | yes, throws | `:535,619,638,713` |
| `createFence` | yes, throws | `:786` |
| `GpuSurface.blitFromTexture` | yes, throws (F`FrontendGpuSurface.java:82`) | |
| **`submit()`** | **no** | `:46-51` |
| **`transientMemory()`** | **no** | `:54-56` |
| **`writeTimestamp(pool, idx)`** | **no** (only an index check) | `:794-800` |
| All `GpuDevice.create*`, `GpuBuffer.map`, and similar | no (not on the encoder) | |

**Backend consequences:**
* `submit()` while a pass is open is not caught by the frontend. Vulkan's `endCommandBuffer` throws
  "Cannot end command buffer while inside RenderPass" (B`vulkan/VulkanCommandEncoder.java:182-184`). Metal should
  throw too, because you cannot commit an MTLCommandBuffer while an encoder is open.
* `writeTimestamp` on the encoder can arrive while the backend has an open render encoder (game code does not do
  this, but the API allows it). Handle it: sample inside the render encoder, or defer the sample.
* `createBuffer(data)`, `map()`, `createTexture`, and the like can arrive mid-pass. They must not need to interrupt
  the open MTLRenderCommandEncoder.

### 5.3 Per-method validation and forwarding (FrontendCommandEncoder)

Texture usage bits: `COPY_DST=1, COPY_SRC=2, TEXTURE_BINDING=4, RENDER_ATTACHMENT=8, CUBEMAP_COMPATIBLE=16`
(`A textures/GpuTexture.java:11-15`).
Buffer usage bits: `MAP_READ=1, MAP_WRITE=2, HINT_CLIENT_STORAGE=4, COPY_DST=8, COPY_SRC=16, VERTEX=32, INDEX=64,
UNIFORM=128, UNIFORM_TEXEL_BUFFER=256, INDIRECT_PARAMETERS=512` (`A buffers/GpuBuffer.java:10-19`).

| Method | Frontend checks before forwarding | Backend must still handle |
|---|---|---|
| `submit()` `:46-51` | none. Calls `backend.submit()`, then `profiler.endFrame()` (reads timestamps). | End of frame (section 5.4). |
| `createRenderPass(desc)` `:100-216` | Not in a pass. `colorAttachments.size() <= limits.maxColorAttachments`. At least one attachment. **Reference size = first color attachment** (asserts it is non-null, `:121-124`, so a leading `withUnusedColorAttachment()` would NPE), else the depth attachment. Render area inside the reference size. For each non-null color attachment: view not closed, `RENDER_ATTACHMENT`, `depthOrLayers == 1`, same size. For depth: not closed, `RENDER_ATTACHMENT`, `depthOrLayers == 1`, same size. **The pixel format of attachments is not validated** (for example, depth-ness of the depth attachment). | Load action = clear if a clear value is present, else load. Store = store. A null color slot is an unused attachment (section 7). |
| `clearColorTexture(tex, rgba)` `:231-238` | `verifyColorTexture` `:802-822`: has color aspect, not closed, **RENDER_ATTACHMENT and COPY_DST**, `depthOrLayers == 1` | Clears **all mips** (Vulkan `levelCount = mips`, B`vulkan/VulkanCommandEncoder.java:430-431`). |
| `clearColorAndDepthTextures(c, rgba, d, depth)` `:241-253` | color and depth verify (`:824-844`: depth aspect, not closed, RENDER_ATTACHMENT, COPY_DST, 1 layer); `c.mips == d.mips` | Used every frame for the main target, clearing depth to **0.0** (`net/minecraft/client/renderer/LevelRenderer.java:252-256`). |
| `clearColorAndDepthTextures(..., x,y,w,h, mip)` `:256-279` | plus `verifyRegion` `:846-876` on the **color** texture's mip: `0 <= x < W`, `0 <= y < H`, `w,h > 0`, `x+w <= W`, `y+h <= H`. (The message at `:874` is a copy-paste typo.) | Partial clear. On Metal this is a render pass with a scissored quad, or load + scissored clear draw. |
| `clearDepthTexture(d, depth)` `:282-289` | verifyDepthTexture | |
| `writeToBuffer(slice, ByteBuffer)` `:292-326` | Not in a pass; buffer not closed; **COPY_DST**; `data.remaining() <= slice.length`; `slice.offset+slice.length <= size` | Writes `data.remaining()` bytes at `slice.offset`, **ordered with other commands**. It must not overwrite data that earlier commands still read. Vulkan and GL stage it through transient memory. |
| `copyToBuffer(src, dst)` `:329-383` | both not closed; src **COPY_SRC**, dst **COPY_DST**; equal lengths; in bounds | Overlap is not checked |
| `writeToTexture(tex, NativeImage)` `:386-396` | image size == mip0 size | delegates |
| `writeToTexture(tex, NativeImage, mip, layer, x, y)` `:399-453` | Not in a pass; **format component type UNORM_8**; `componentCount == image.format().components()`; mip in range; rect fits; not closed; COPY_DST; `layer < depthOrLayers` (not checked for >= 0) | Delegates to the ByteBuffer variant with `image.getPixelBytes()` |
| `writeToTexture(tex, ByteBuffer, mip, layer, x, y, w, h)` `:456-518` | Not in a pass; mip in range; `w*h*blockSize <= remaining` (int math); rect fits; not closed; COPY_DST; `layer < depthOrLayers` | **Tightly packed rows**: `bytesPerRow = w * format.blockSize()`. `layer` is the cubemap face index. 3-component formats (RGB8) have blockSize 3, and Metal has no RGB8 pixel format, so they must be expanded. |
| `copyBufferToTexture(src, sx, sy, sW, sH, tex, dx, dy, cw, ch, mip, layer)` `:521-615` | Not in a pass; mip in range; `sx+cw <= sW && sy+ch <= sH`; `sW*ch*blockSize <= src.length` (**ignores sy and sx**, so it is loose); dest rect fits; src not closed and **COPY_SRC**; tex not closed and COPY_DST; `layer < depthOrLayers` | Source layout: row pitch = `sW * blockSize`, start = `src.offset + (sy*sW + sx)*blockSize`. Bound-check it yourself. |
| `copyTextureToBuffer(tex, buf, off, cb, mip)` `:618-624` | **only the not-in-pass check** | Both backends expand this to the full-mip 9-arg variant **inside the backend** (B`opengl/GlCommandEncoder.java:367-368`, B`vulkan/VulkanCommandEncoder.java:745-746`), so the frontend's 9-arg validation is bypassed. Validate yourself. |
| `copyTextureToBuffer(tex, buf, off, cb, mip, x, y, w, h)` `:627-699` | mip in range; `w*h*blockSize + off <= buf.size`; tex **COPY_SRC**; buf **COPY_DST**; rect fits; not closed; `depthOrLayers == 1` | Tightly packed output. **`callback` must run on the render thread after GPU completion.** GL: `RenderSystem.queueFencedTask(callback)` (B`opengl/GlCommandEncoder.java:396`), polled by `RenderSystem.executePendingTasks` each frame (`RenderSystem.java:323-341`). Vulkan: queued in its destroy queue, which runs two submits later inside `submit()` (B`vulkan/VulkanCommandEncoder.java:793`). Never call it from an `addCompletedHandler` thread. |
| `copyTextureToTexture(src, dst, mip, dx, dy, sx, sy, w, h)` `:702-782` | Not in a pass; mip valid for both; rects fit; not closed; src COPY_SRC, dst COPY_DST; both `depthOrLayers == 1` | Same mip on both sides. Formats are **not** checked for compatibility, and depth copies happen (GL picks a depth or color blit, B`opengl/GlCommandEncoder.java:427`). Metal blit copies need identical or compatible pixel formats. |
| `createFence()` `:785-791` | not in a pass | Section 9 |
| `writeTimestamp(pool, idx)` `:794-800` | `0 <= idx <= pool.size()`. **Off by one: `idx == size` passes.** No render-pass check. | Bound-check yourself. Section 10. |

### 5.4 `submit()` semantics (backend)

What both existing backends do in `CommandEncoderBackend.submit()`:

* **Vulkan** (B`vulkan/VulkanCommandEncoder.java:263-279`): end the open command buffers (the init buffer goes
  before the main buffer, `:180-218`); end the transient-memory submit; signal timeline semaphore value
  `currentSubmitIndex` (stage 0x10000 = ALL_COMMANDS); submit to the graphics queue; `currentSubmitIndex++`;
  **block on `currentSubmitIndex-2`, which is the submit just before the one that was just sent, with a 5 s
  timeout** (exceeding it throws `IllegalStateException` with checkpoint info); reset the command pool for the
  reused slot; rotate the destroy queue (2 slots) and checkpoints; begin transient memory for the next submit.
  `MAX_SUBMITS_IN_FLIGHT = 2` (`:49`), and `currentSubmitIndex` starts at 2 (`:53`).
* **GL** (B`opengl/GlCommandEncoder.java:75-83`): `glFenceSync` into slot `index % 2`, `currentSubmitIndex++`,
  wait (infinite) for `index-2`, rotate transient memory.

So, after `submit()` returns, the GPU may still be running the submit just sent, and the CPU records the next
one. That gives **at most one frame of CPU/GPU overlap**. Transient memory handed out during submit S is
recycled after S completes.

**Metal mapping:** keep one "current" `MTLCommandBuffer` (or an `MTL4CommandBuffer` plus an `MTL4CommandAllocator`
per in-flight slot). On `submit()`:
1. Assert no encoder is open, then end any open blit or compute encoder.
2. If the surface has a drawable pending present, schedule the present (section 8.4).
3. `encodeSignalEvent:sharedEvent value:S` (classic) or `[queue signalEvent:value:]` (Metal 4), then commit.
4. `S++`. Wait until `sharedEvent.signaledValue >= S - (framesInFlight-1)`, preferably using a host-side cached
   completed value updated by `addCompletedHandler` so polling needs no Objective-C call.
5. Rotate the transient ring, the deferred-destroy lists, and the command allocator.

Consider **3 frames in flight** for 1000 FPS targets (section 16). `TimerQuery` and Tracy cope with longer latency.

### 5.5 `transientMemory()`

Forwarded without checks (`:54-56`). This is the backend's per-submit linear allocator
(`A buffers/TransientMemory.java:6-86`). Allocations are valid until the end of the current submit, and are
recycled once that submit completes on the GPU. Covered in the buffers/memory doc. Two notes: `VulkanDevice.createBuffer(data)` uses
`uploadStaging` (B`vulkan/VulkanDevice.java:227`), and `GlRenderPass.pushConstants` uses `uploadGpu` with
`minUniformOffsetAlignment` (B`opengl/GlRenderPass.java:90`).

---

## 6. `RenderPass` → `RenderPassBackend` (FrontendRenderPass)

### 6.1 State tracked by the frontend (F`FrontendRenderPass.java:36-50`)

* `deviceFeatures`, `deviceLimits`, captured once in the ctor (`:62-63`).
* `colorAttachments` list (with nulls), `hasDepthAttachment`, `renderArea`, and `onFinish`.
* `isClosed`, `pushedDebugGroups`.
* `boundPipeline` (FrontendRenderPipeline), `vertexBuffers[16]`, `indexBuffer` (the GpuBuffer only; **the index
  type is not tracked**), `uniforms: HashMap<String,Object>`, and `constantsPushed`.

### 6.2 Methods

| Method | Frontend behavior | Forwards |
|---|---|---|
| `pushDebugGroup(label)` `:71-78` | throws if closed; increments depth | `backend.pushDebugGroup(label)` (label is a Supplier, so evaluate it only when labels are on) |
| `popDebugGroup()` `:81-92` | throws if closed or depth == 0 | `backend.popDebugGroup()` |
| `writeTimestamp(pool, idx)` `:95-101` | the same off-by-one bound check; **no closed check** | `backend.writeTimestamp`. **No game code calls this** (only `CommandEncoder.writeTimestamp` is used). |
| `setPipeline(p)` `:104-128` | Must be a `FrontendRenderPipeline`. **`colorTargetStates.size() == colorAttachments.size()`**. For each non-null attachment, a non-null target state with `format == attachment.texture().getFormat()`. Then it sets `boundPipeline`, calls `backend.setPipeline(...)`, **replays every stored uniform** with `uniforms.forEach(this::setUniform)` (which calls `backend.setUniform(index, value)` for each name the new pipeline knows), and resets `constantsPushed = false`. **No closed check. The depth attachment format is not compared with the pipeline.** | `backend.setPipeline(BackendRenderPipeline)`, then N × `backend.setUniform` |
| `setUniform(name, view, sampler)` `:131-142` | Both non-null gives `TextureViewAndSampler`. Both null removes the uniform. Exactly one null is `IllegalArgumentException`. | |
| `setUniform(name, GpuBuffer)` `:145-147` | `value.slice()` (the whole buffer) | |
| `setUniform(name, GpuBufferSlice)` `:150-157` | **`offset % limits.minUniformOffsetAlignment == 0`** is enforced even for texel buffers | |
| `setUniform(name, Object)` (private) `:159-172` | Updates the map. If a pipeline is bound and `uniformIndices` has the name, it forwards; **unknown names are stored but not forwarded**. | `backend.setUniform(int index, @Nullable Object value)`. The value is a `GpuBufferSlice` (UBO or texel buffer), a `TextureViewAndSampler`, or **null** (unbind). |
| `pushConstants(ByteBuffer)` `:175-186` | Requires a bound pipeline; `remaining() >= pipeline.pushConstantSize` | `backend.pushConstants(value)` (the ByteBuffer is caller-owned, so copy it now) |
| `enableScissor(x,y,w,h)` `:189-204` | `w,h > 0`; the rect must sit inside `renderArea`. No closed check. | `backend.enableScissor` |
| `disableScissor()` `:207-209` | none | `backend.disableScissor()`. Vulkan resets to the **render area**, or to the full output if the render area is null (B`vulkan/VulkanRenderPass.java:182-188`). |
| `setVertexBuffer(slot, slice?)` `:212-227` | `0 <= slot < 16`; non-null must be open and have **VERTEX (32)** | `backend.setVertexBuffer(slot, slice)`. **Null is forwarded** (Vulkan ignores it, B`vulkan/VulkanRenderPass.java:191-215`). |
| `setIndexBuffer(buf, type)` `:230-233` | none (the usage bit is checked only in STRICT draw validation) | `backend.setIndexBuffer(buf, type)`. **The whole buffer is used, with offset 0** (Vulkan binds at 0, B`vulkan/VulkanRenderPass.java:223`), and `firstIndex` selects the start. |
| `drawIndexed(ic, inst, firstIndex, baseVertex, firstInstance)` `:236-247` | not closed; `firstInstance != 0` requires `nonZeroFirstInstance`; `validateDraw` | `backend.drawIndexed` |
| `multiDrawIndexed(IntBuffer params, inst, firstInst, drawCount)` `:250-273` | requires `multiDrawDirectInterleaved`; `drawCount <= maxMultiDrawDirectInterleavedDrawCount`; `params.remaining() >= 3*drawCount` | `backend.multiDrawIndexed`. Layout = `VkMultiDrawIndexedInfoEXT` `{firstIndex, indexCount, vertexOffset}` (B`vulkan/VulkanRenderPass.java:235-237`). **Never called by game code.** |
| `multiDrawIndexed(PointerBuffer firstIndexOffsets, IntBuffer counts, IntBuffer vertexOffsets, n)` `:276-299` | requires `multiDrawDirectSeparate`; buffer sizes | GL `glMultiDrawElementsBaseVertex`-style (byte offsets). **Never called by game code.** |
| `drawIndexedIndirect(slice, drawCount)` `:302-329` | requires `drawIndirect`; `drawCount > 1` requires `multiDrawIndirect`; **INDIRECT_PARAMETERS (512)**; `length >= drawCount*20`; `offset % 4 == 0` | `backend.drawIndexedIndirect`. Stride = **20 bytes**: `{indexCount, instanceCount, firstIndex, int32 vertexOffset, firstInstance}`. That is the same layout as `MTLDrawIndexedPrimitivesIndirectArguments` (indexCount, instanceCount, indexStart, baseVertex, baseInstance). |
| `drawMultipleIndexed(draws, defIdx, defType, dynamicUniforms, arg)` `:332-398` | Not closed; `validateDraw(dynamicUniforms, true, false)`. **Decomposed per draw**: optional uploader callback (calls back into `this.setUniform`/`pushConstants`, so the backend sees `setUniform` per draw), `this.setVertexBuffer(slot, vb.slice())` per draw, `setIndexBuffer` only when it changes, then `backend.drawIndexed(count, 1, firstIndex, baseVertex, 0)`. "Missing push constants" is checked per draw in all builds (`:391-394`). | Many small backend calls. Make them cheap: dirty-tracking, and no Objective-C call per setter. |
| `draw(vc, inst, firstVertex, firstInstance)` `:401-412` | as for drawIndexed | `backend.draw` |
| `multiDraw(IntBuffer params, inst, firstInst, n)` `:415-438` | interleaved, `{firstVertex, vertexCount}` per draw (`VkMultiDrawInfoEXT`) | Never called by game code |
| `multiDraw(IntBuffer firstVertices, IntBuffer counts, n)` `:441-460` | separate | Never called by game code |
| `drawIndirect(slice, n)` `:463-490` | as for indexed, stride **16** `{vertexCount, instanceCount, firstVertex, firstInstance}` = `MTLDrawPrimitivesIndirectArguments` | Never called by game code |
| `close()` `:493-502` | Idempotent. Sets `isClosed = true`. **If debug groups are left open it throws `IllegalStateException` before calling `onFinish`**, which leaves the encoder stuck with `isInRenderPass == true` and the backend pass never ended. | `onFinish`, which leads to `backend.submitRenderPass()` |

`validateDraw` (`:504-634`) **always** checks that a pipeline is bound. Everything else is STRICT-only:
push constants pushed; index buffer present, open, with INDEX(64); every slot whose pipeline `vertexFormats[i]`
is non-null has a vertex buffer (skipped for `drawMultipleIndexed`); every pipeline uniform not listed in
`dynamicUniforms` is set with the right kind (UBO slice with UNIFORM(128); texel buffer = the **whole buffer**
with UNIFORM_TEXEL_BUFFER(256) and a non-null `gpuFormat`; sampler = `TextureViewAndSampler` with an open view
that has TEXTURE_BINDING(4) and an open sampler); and `wantsDepthTexture` implies a depth attachment.

**Backend obligations that follow:**
* In production a draw may arrive with unbound uniforms, textures, or vertex buffers. Unbound resources in a Metal
  shader are undefined behavior and can fault or hang the GPU. Bind dummy resources by default, or keep cheap checks.
* After `setPipeline`, the frontend re-sends every uniform the pipeline knows. Store bindings by index and flush
  only the dirty ones at draw time, the way Vulkan uses `anyDescriptorDirty` (B`vulkan/VulkanRenderPass.java:280-281`).
* `setUniform(i, null)` must unbind or mark the slot missing.
* Calls after `close()` still reach the backend for `setPipeline`/`setUniform`/`setVertexBuffer`/`setIndexBuffer`/
  `enableScissor`/`disableScissor`/`pushConstants`/`writeTimestamp`. Guard against that.
* **The pipeline does not know the depth-attachment format.** `BackendRenderPipeline.CreateInfo` has a
  `@Nullable DepthStencilState` but no depth format (B`api/BackendRenderPipeline.java:16-28`). Metal bakes
  `depthAttachmentPixelFormat` into `MTLRenderPipelineState`, so either assume `Depth32Float` (the only depth
  format vanilla uses; `GpuFormat.D32_FLOAT` appears 6 times in game code) or create variants keyed by
  (pipeline, depth format present or absent) lazily at `setPipeline`. A pipeline whose `DepthStencilState` is
  null can still be used in a pass that has a depth attachment, and the reverse is possible too.

### 6.3 What the game actually calls (for prioritizing)

`drawIndexed`, `draw`, `drawIndexedIndirect` (terrain MDI only, `net/minecraft/client/renderer/chunk/ChunkSectionsToRender.java:148-155`),
and `drawMultipleIndexed` (terrain non-MDI `:188`, world border `net/minecraft/client/renderer/WorldBorderRenderer.java:225`).
None of `multiDraw*`, `drawIndirect`, or `RenderPass.writeTimestamp` is used by vanilla.

---

## 7. `RenderPassDescriptor` semantics (A`commands/RenderPassDescriptor.java`)

* `record(Supplier<String> label, List<@Nullable Attachment<Optional<Vector4fc>>> colorAttachments,
  @Nullable Attachment<OptionalDouble> depthAttachment, RenderArea renderArea)`. The compact ctor makes an
  unmodifiable copy of the list (`:20-22`; the decompiled local `var5` is a Vineflower artifact of reassigning a
  parameter).
* `Attachment(GpuTextureView textureView, T clearValue)`. Color clear values are RGBA floats. An empty
  `Optional` means **load**. Depth clear is a double, and empty means **load**. There is no stencil.
* `withUnusedColorAttachment()` adds a `null` slot (`:51-54`). Vulkan binds it with imageView 0, loadOp
  DONT_CARE, and storeOp NONE (B`vulkan/VulkanCommandEncoder.java:340-345`). In Metal, leave
  `colorAttachments[i].texture = nil`. The pipeline's color target at that index may be null
  (`colorTargetStates` is `List<@Nullable>`). Remember the frontend NPEs if slot 0 is null (section 5.3).
* Default render area (`:71-96`): the **last** non-null color attachment's size, else the depth size. The
  `CommandEncoder` default methods use the color texture's mip-0-of-view size (A`commands/CommandEncoder.java:32-34`).
* Store op is always STORE (Vulkan `storeOp(0)` for color and depth, `:329,359`). The API has no
  "discard after pass" hint, which matters for tile-based GPUs (section 16).
* The viewport is always `(0, 0, attachmentW, attachmentH)` with depth range [0,1] (B`vulkan/VulkanRenderPass.java:80-87`,
  B`opengl/GlCommandEncoder.java:178`). Initial scissor = render area (`VulkanRenderPass.java:88`). There is no
  `setViewport` in the API.
* Attachments are views. Rendering targets the view's **base mip** (for example, atlas mip generation renders
  into per-mip views, `net/minecraft/client/renderer/texture/TextureAtlas.java:62-70`). In Metal, set
  `attachment.texture = baseTexture` with `attachment.level = view.baseMipLevel()`, or use the MTLTexture view.
* Multiple render targets: OIT transmittance uses `OIT_COEFFICIENT_COUNT/4 = 2` RGBA16F color targets plus depth
  (`net/minecraft/client/renderer/oit/OitRenderPassProvider.java:35-40`, `LevelRenderer.java:110-111`).

---

## 8. `GpuSurface` → `GpuSurfaceBackend` (FrontendGpuSurface)

### 8.1 Frontend state machine (F`FrontendGpuSurface.java`)

State: `hasImageAcquired`, `hasBlittedTexture`, `currentConfiguration`.

| Method | Frontend checks | Forwards | State change |
|---|---|---|---|
| `configure(cfg)` `:31-42` | not acquired (`IllegalStateException`); `cfg.presentMode` must be in `supportedPresentModes()`, otherwise **`SurfaceException`** | `backend.configure(cfg)` | `currentConfiguration` is set only if the backend did not throw |
| `currentConfiguration()` `:45-47` | | | |
| `supportedPresentModes()` `:50-52` | | `backend.supportedPresentModes()` | |
| `isSuboptimal()` `:55-57` | | `backend.isSuboptimal()` | |
| `isAcquired()` `:60-62` | | | |
| `acquireNextTexture()` `:65-77` | not already acquired; configured | `backend.acquireNextTexture()` (may throw `SurfaceException`) | acquired=true and blitted=false, **only on success** |
| `blitFromTexture(enc, view)` `:80-111` | `enc` is a `FrontendCommandEncoder`, not in a render pass; view's format has color; texture has **COPY_SRC (2)**; `depthOrLayers == 1`; acquired; not already blitted | `backend.blitFromTexture(enc.backend(), view)` | blitted=true |
| `present()` `:114-125` | acquired; blitted | `backend.present()` | acquired=false, **only if the backend did not throw** |
| `close()` `:22-28` | not acquired | `backend.close()` | |

**Pitfall:** if `backend.present()` throws, `hasImageAcquired` stays true. The next `renderFrame` does nothing
because it starts with `if (!windowSurface.isAcquired())` (`Minecraft.java:1246`), so the game freezes. Vulkan
avoids this by **swallowing** out-of-date and suboptimal errors in `present`, setting `swapchainSuboptimal`, and
rethrowing the stored exception from the next `acquireNextTexture` (B`vulkan/VulkanGpuSurface.java:302-306,517-526`).
Do the same.

### 8.2 How the game drives it each frame (`Minecraft.renderFrame`, `Minecraft.java:1245-1374`)

```
if (!surface.isAcquired()) {
  if ((needsReconfigure || surface.isSuboptimal() && !surfaceIsInvalid) && !window.isIconified()) {
      size = window.queryFramebufferSize()             // SDL_GetWindowSizeInPixels → full Retina px
      mode = PresentMode.getSupportedVsyncMode(surface.supportedPresentModes(), options.enableVsync)
      surface.configure(new Configuration(size.w, size.h, mode))   // SurfaceException → surfaceIsInvalid
  }
  if (!surfaceIsInvalid) surface.acquireNextTexture()  // SurfaceException → invalid + reconfigure next frame
  timerQuery.beginProfile()?                            // encoder.writeTimestamp (if GPU-util overlay / metrics)
  ... extract ...
  RenderSystem.executePendingTasks()                    // polls fences with awaitCompletion(0)
  gameRenderer.render()                                 // all render passes / copies on the single encoder
  if (surface.isAcquired()) surface.blitFromTexture(encoder, mainRenderTarget.colorTextureView)
  timerQuery.endProfile()?                              // encoder.writeTimestamp
  tracyFrameCapture...
  encoder.submit()                                      // ← end of frame; CPU throttles here
  if (surface.isAcquired()) surface.present()
  DynamicGpuData.reset(); levelRenderer.endFrame(); frame limiter
}
```

* `Minecraft.invalidateSurfaceConfiguration()` (`:2974-2976`) forces a reconfigure on resize, vsync change, and similar.
* If acquire fails (for example when minimized: GL throws `"Cannot acquire minimized window"`,
  B`opengl/GlSurface.java:42-46`), the frame **still renders and submits**, with no blit and no present.
* `PresentMode.getSupportedVsyncMode` (A`device/GpuSurface.java:38-48`) picks `FIFO` when vsync is on. When vsync
  is off it picks the first supported of `IMMEDIATE`, `MAILBOX`, `FIFO`. `FIFO_RELAXED` is never chosen.
  GL reports `{FIFO, IMMEDIATE}` (B`opengl/GlSurface.java:15`). Vulkan reports what the driver supports.

### 8.3 Blit semantics (what `blitFromTexture` must produce)

* **1:1 unscaled copy** of `min(swapchain, texture)` pixels, **top-left anchored**, **Y-flipped** relative to
  texture memory rows.
  * Vulkan: src `(0,0)-(w,h)` goes to dst `(0,h)-(w,0)` with `vkCmdBlitImage` (B`vulkan/VulkanGpuSurface.java:408-435`).
    The source mip is `textureView.baseMipLevel()`.
  * GL: the blit lands at `destY = max(0, swapH - texH)` in the bottom-left-origin default framebuffer
    (B`opengl/GlCommandEncoder.java:436-447`).
* The source is the main render target (`RGBA8_UNORM`, usage 15). Vulkan's swapchain uses `R8G8B8A8_UNORM` or
  `B8G8R8A8_UNORM` with SRGB_NONLINEAR, so **no sRGB encode happens**; the image is already gamma-space
  (B`vulkan/VulkanGpuSurface.java:151-158`). Composite alpha is opaque. The swapchain has at least 3 images (`:228-229`).

### 8.4 Metal mapping for the surface

* `createSurface`: `view = SDL_Metal_CreateView(window)`, `layer = SDL_Metal_GetLayer(view)`. Set `layer.device`,
  `layer.pixelFormat = MTLPixelFormatBGRA8Unorm`, `layer.opaque = YES`, `layer.maximumDrawableCount = 3`,
  `layer.framebufferOnly = YES` (see the next bullet), and `layer.colorspace = sRGB` (or nil, to match "no
  conversion"). Keep `isIconified` for `acquireNextTexture`.
* **The blit cannot be an `MTLBlitCommandEncoder` copy.** CAMetalLayer does not support RGBA8Unorm (only BGRA8,
  BGRA8_sRGB, RGBA16F, RGB10A2, BGR10A2, and the XR formats), and blit copies need matching pixel formats. They
  also need `framebufferOnly = NO`. **Use a tiny render pass instead:** a fullscreen triangle that samples the
  main texture with a Y flip, writing the drawable with `loadAction = DontCare` and `storeAction = Store`. This
  allows `framebufferOnly = YES` and is the natural place to put **MetalFX upscaling** (section 16).
* `configure(cfg)`: `layer.drawableSize = (cfg.width, cfg.height)`. Use `layer.displaySyncEnabled = YES` for FIFO
  and `NO` for IMMEDIATE. Report `{FIFO, IMMEDIATE}`, and add MAILBOX only if you implement real mailbox
  behavior. Reset the suboptimal flag.
* `acquireNextTexture()`: throw `SurfaceException` if `isIconified` (as GL does). **Do not call `nextDrawable`
  here.** The game acquires at the very start of the frame, and `nextDrawable` can block for up to 1 s and pins a
  drawable for the whole frame. Just mark the surface as acquired.
* `blitFromTexture(enc, view)`: call `nextDrawable` now, as late as possible. If it is nil, set the suboptimal
  flag, skip the blit, and make `present()` a no-op, because this method cannot throw `SurfaceException`.
  Encode the present pass into the encoder's current command buffer. Classic Metal: `[cmdBuf presentDrawable:]`
  before commit. Metal 4: `[queue waitForDrawable:]` before committing the command buffer that writes it, then
  `[queue signalDrawable:]` after commit, then `[drawable present]`; verify these against the macOS 27 SDK.
* `present()`: runs **after** `submit()` committed the command buffer. With classic Metal, `presentDrawable:` was
  already scheduled before commit, so `present()` is bookkeeping only. Alternatively call `[drawable present]`
  here; it must be after the commit that renders the drawable.
* `isSuboptimal()`: true when `layer.drawableSize` no longer matches the window's pixel size (cheap check), or
  after a nil drawable.

---

## 9. Fences (`GpuFence`, A`commands/GpuFence.java`)

* API: `awaitCompletion(long timeoutNS)` returns true when complete. `NO_TIMEOUT = -1` is never used by name;
  callers pass `-1L` or `0L`. `close()` is idempotent and may come before completion.
* **Semantics in both backends:** a fence captures `currentSubmitIndex` at creation. It is complete once that
  whole submit has finished on the GPU (B`opengl/GlFence.java:10-28`, B`vulkan/VulkanCommandEncoder.java:891-911`).
  * timeout 0 on the current, unsubmitted index returns `false`.
  * timeout != 0 on the current index **throws `IllegalStateException("Cannot wait on a fence for the current submit")`**.
  * Vulkan's `awaitSubmitCompletion` (`:843-888`) calls `vkWaitSemaphores` with the timeout, which is in **ns**.
    (The anonymous class names the parameter `timeoutMs`, but the value is passed through as ns.)
  * `close()` just marks it completed. No GPU object exists per fence.
* **Consumers (all on the render thread):**
  * `RenderSystem.queueFencedTask` / `executePendingTasks`: a per-frame FIFO polled with `awaitCompletion(0)`
    (`RenderSystem.java:323-341`).
  * `MappableRingBuffer` (3 buffers): creates a fence on each `rotate()`, and `currentBuffer()` waits with
    **`-1L`** on the fence of the slot it is about to reuse (`net/minecraft/client/renderer/MappableRingBuffer.java:36-54`).
    This is safe only because rotation happens at most once per submit. If you ever rotate three times within one
    submit, both backends throw.
  * `StagedVertexBuffer.GpuBufferPool.endFrame` creates one fence per frame and polls it with `awaitCompletion(0)`
    (`net/minecraft/client/renderer/StagedVertexBuffer.java:350-383`).
  * `DynamicGpuDataStorageMapped.endFrame` (`net/minecraft/client/renderer/DynamicGpuDataStorageMapped.java:37-48`)
    rotates its ring buffers once per frame, which creates fences.
  * Expect roughly 5-10 fences per frame. **Make them plain Java objects** (one long), not native events.
* **Metal mapping:** a single `MTLSharedEvent` (or a Metal 4 queue-signaled `MTLSharedEvent`) whose value is the
  submit index. Signal it at the end of each committed frame command buffer. Keep `volatile long completedIndex`,
  updated from `addCompletedHandler`, or read `event.signaledValue` (an Objective-C call). For timeout 0, compare
  only. For timeout > 0, use `waitUntilSignaledValue:timeoutMS:` (macOS 12+, **milliseconds**, so convert ns to
  ms rounding up; -1 becomes `UINT64_MAX`). Keep the "throw on the current submit" rule. Flushing mid-frame is
  possible in Metal because command buffers execute in queue order, but it is only safe when no render encoder is
  open, and the frontend does not prevent waits during a pass.

---

## 10. Timestamp queries, Tracy, and GPU utilization

### 10.1 API
* `GpuQueryPool` (A`commands/GpuQueryPool.java:6-12`): `size()`, `getValue(i)`, `getValues(i, n)`. These return
  `OptionalLong.empty()` when the value is **not yet available**, and must be **non-blocking**. `close()` must
  defer destruction (Vulkan queues it, B`vulkan/VulkanQueryPool.java:102-105`).
* `GpuQuery` (A`commands/GpuQuery.java`) is unused by the frontend and game.
* Writing: `CommandEncoder.writeTimestamp(pool, idx)` and `RenderPass.writeTimestamp(pool, idx)`. Vulkan
  **host-resets the slot, then writes** at ALL_COMMANDS (`vkResetQueryPool` + `vkCmdWriteTimestamp2`,
  B`vulkan/VulkanCommandEncoder.java:913-918`). The value therefore becomes unavailable again until the new write
  completes. GL uses `glQueryCounter(GL_TIMESTAMP)` (B`opengl/GlQueryPool.java:44-46`).
* Raw values are in device ticks. `DeviceInfo.timestampPeriod` is **nanoseconds per tick**: GL 1.0, Vulkan
  `limits.timestampPeriod` (B`opengl/GlHeuristics.java:93`, B`vulkan/VulkanDevice.java:93`).
* `GpuDeviceBackend.getTimestampCalibrationOffset()` returns `hostNanos - deviceNanos` with host time =
  `System.nanoTime()`. GL uses `glGetInteger64(GL_TIMESTAMP)` (B`opengl/GlDevice.java:383-387`). Vulkan uses
  `vkGetCalibratedTimestampsEXT`, or a blocking one-off timestamp submit when that is unavailable
  (B`vulkan/VulkanDevice.java:276-316`, B`vulkan/VulkanCommandEncoder.java:920-957`).

### 10.2 Consumers
* `TracyGpuProfiler` (F`TracyGpuProfiler.java`) exists only when Tracy is available (the `--tracy` launch path). It
  uses a 1024-entry ring (`:11`), `pushZone`/`popZone` around **every render pass** (section 5.1), drains
  `getValues` in `endFrame()` after every `submit()` (stopping at the first unavailable entry, `:47-80`), and
  recalibrates every 5 s (`:48-51`). The Tracy GPU context is always created as `GpuApi.OPENGL` (`:24`).
* `TimerQuery` (`com/mojang/blaze3d/systems/TimerQuery.java`) uses a 6-entry pool (3 rotations × begin and end).
  It writes at frame start and after the blit (`Minecraft.java:1287-1290,1326-1328`) only when the GPU-utilization
  debug entry is shown or metrics are recording. It polls `getValues` and scales by `timestampPeriod` (`:58-69`).
* Normal gameplay writes **no timestamps**. Keep the path zero-cost when unused.

### 10.3 Metal mapping
* Metal 4: `MTL4CounterHeap` (type timestamp, `count = size`). Write with `-[MTL4CommandBuffer writeTimestampIntoHeap:atIndex:]`
  outside encoders and `-[MTL4RenderCommandEncoder writeTimestampWithGranularity:afterStage:intoHeap:atIndex:]`
  inside one. Read on the CPU with `resolveCounterRange:`, only for indices whose submit index has completed
  (keep a per-slot "written in submit S" array and return empty otherwise). Get ticks per second from
  `-[MTLDevice queryTimestampFrequency]`, so `timestampPeriod = 1e9 / freq`. Verify these Metal 4 selector names
  in the SDK headers.
* Classic Metal fallback: `MTLCounterSampleBuffer` (common counter set timestamp, `storageMode = Shared`). **Apple
  GPUs support sampling only at stage boundaries** (`MTLCounterSamplingPointAtStageBoundary`), not mid-encoder
  (`AtDrawBoundary` and `AtBlitBoundary` are unsupported on the Apple family). `CommandEncoder.writeTimestamp`
  therefore has to start an empty blit or compute encoder with `sampleBufferAttachments`, or attach the sample to
  the start of the next pass. Tracy's push happens immediately before `createRenderPass` and its pop immediately
  after `submitRenderPass`, so this attachment trick fits.
* Calibration: `[device sampleTimestamps:&cpu gpuTimestamp:&gpu]`, then take `System.nanoTime()` right after
  (HotSpot on macOS derives `nanoTime` from `mach_absolute_time`, so convert `cpu` through `mach_timebase_info` if
  you use it). Return `nanoTime - gpu * period`.

---

## 11. Threading model

| Thread | What it calls |
|---|---|
| **Render thread = main/game thread** (`RenderSystem.initRenderThread()` at `net/minecraft/client/main/Main.java:270`; `RenderSystem.assertOnRenderThread` at `RenderSystem.java:138-142`) | Every `GpuDevice` create/close call, everything on `CommandEncoder`/`RenderPass`/`GpuSurface`, `GpuBuffer.map/close`, `GpuFence.awaitCompletion`, `GpuQueryPool.getValues`, and `Pending.finishCompile()`. GL asserts this on every GL call (B`opengl/GlStateManager.java:42ff`), so in practice nothing else touches the device. |
| **Shader compile workers** (`Util.backgroundExecutor()` for lazy `PipelineCache.get`, `com/mojang/blaze3d/pipeline/PipelineCache.java:33`; the resource-reload `taskExecutor` in `net/minecraft/client/renderer/ShaderManager.java:72,105`) | `PipelineBuilder.compilePipeline`'s async body: shaderc GLSL→SPIR-V, reflection, then **`GpuDeviceBackend.compilePipeline(createInfo)`** (F`shaders/PipelineBuilder.java:377-411`). **Many pipelines compile concurrently.** `getDeviceInfo()` is read here too. Vulkan builds the `VkPipeline` right here (B`vulkan/VulkanDevice.java:264-268`). GL only decompiles SPIR-V here and links later (B`opengl/GlDevice.java:354-367`). |
| **Render thread again** | `Pending.finishCompile()`: `ShaderManager` runs it via `reloadExecutor` (the game thread, `ShaderManager.java:106`), and `PipelineCache.get` joins and finishes inline (`PipelineCache.java:33`, which causes a hitch on a cache miss). SPIR-V modules are closed on the executor afterwards (F`shaders/PipelineBuilder.java:451`). |
| **Chunk mesh workers** | No API calls. They `memcpy` mesh data into the staging buffer under `SectionRenderDispatcher.copyLock` (`net/minecraft/client/renderer/chunk/SectionRenderDispatcher.java:368-395`, `com/mojang/blaze3d/vertex/UberGpuBuffer.java:41-55`). With the `PersistentlyMapped` staging path that memory is a **mapped GPU buffer**, written concurrently while the GPU reads other parts of the ring. On Shared storage this is fine, with no flush needed. |
| **Metal completion-handler threads** (yours) | Must not call back into game code. Only update atomics or queues that the render thread drains. |

Metal implications: `MTLDevice` object creation (`newRenderPipelineStateWithDescriptor:`, `newLibraryWithSource:`,
`newBufferWithLength:`) is thread-safe, so `compilePipeline` can build the full `MTLRenderPipelineState` on the
worker. Command encoding is single-threaded on the render thread.

---

## 12. Lifetimes, ownership, and deferred destruction

* **The game closes resources that in-flight GPU work may still reference.** Examples: `DynamicGpuDataStorageMapped.endFrame`
  closes `oldBuffers` right after the frame that used them was submitted (`DynamicGpuDataStorageMapped.java:41-47`).
  The terrain sampler is closed and recreated mid-frame (`LevelRenderer.java:425-436`). `AutoStorageIndexBuffer`
  closes the old index buffer on growth (`RenderSystem.java:458-462`).
  Vulkan routes every destroy through a 2-slot `DestructionQueue` rotated in `submit()`
  (B`vulkan/VulkanCommandEncoder.java:57,121-123,276`). **The Metal backend needs the same.** Classic Metal
  command buffers retain referenced resources by default (unless `commandBufferWithUnretainedReferences`, which you
  want for CPU speed), and **`MTL4CommandBuffer` never retains**. Keep "release after submit S completes" lists.
* `GpuTextureView` close and `GpuTexture` close order is not enforced. Views may outlive textures briefly.
* Frontend object lifetimes: the `FrontendRenderPass` is single-use (one `close`). `FrontendCommandEncoder` and
  `FrontendGpuDevice` live as long as the process.
* Transient memory slices are valid only within the submit that produced them.

---

## 13. Coordinate conventions (what the rest of the engine assumes)

| Item | Convention | Evidence | Metal action |
|---|---|---|---|
| Clip-space Z | `[0,1]` when `isZZeroToOne`; projections are built with `zZeroToOne` (`net/minecraft/client/renderer/Projection.java:69-74`, `net/minecraft/client/Camera.java:183-189`); shaders get `RENDERPEARL_DEPTH_IS_ZERO_TO_ONE` (F`shaders/GlslCompiler.java:81-83`, used in `assets/minecraft/shaders/include/oit_common.glsl:16-21`) | GL with `ARB_clip_control` → `glClipControl(LOWER_LEFT, ZERO_TO_ONE)` (B`opengl/GlDevice.java:149-152`); Vulkan reports true | **Report `true`.** Metal clips z to [0,1]. |
| Depth direction | **Reverse-Z**: `near = zFar`, `far = zNear` (`Projection.java:67-68`); main depth cleared to **0.0** (`LevelRenderer.java:255`); format `D32_FLOAT` | | `Depth32Float`, clear 0.0, GREATER-style compares come from `DepthStencilState`. |
| Framebuffer Y / memory rows | Memory row 0 = NDC y = -1. GL: lower-left origin. Vulkan: positive viewport `(0,0,w,h)` (B`vulkan/VulkanRenderPass.java:80-87`), which puts NDC -1 at row 0, then **flips at present** (`VulkanGpuSurface.java:410-418`) | Same bytes in both backends | Metal maps NDC +1 to row 0, so **flip**: use a negative-height viewport (`originY = h, height = -h`), or flip `gl_Position.y` in MSL (SPIRV-Cross `flip_vert_y`) **and** invert the front-face winding. Flip again in the present pass. |
| Texture origin | v=0 is memory row 0 in GL, Vulkan, and Metal | | No change, as long as render-target memory layout matches. |
| `gl_FragCoord` / `[[position]]` | Row 0 based in all three (shaders index textures with `ivec2(gl_FragCoord.xy)`, for example `assets/minecraft/shaders/include/oit_sample.glsl:104`) | | No change. |
| Scissor and render-area coordinates | Texel coordinates from row 0. Vulkan passes them straight through (`VulkanRenderPass.java:174-178`), GL likewise (`glScissor`) | | `MTLScissorRect{x, y, w, h}` straight through. Must lie inside the attachment, which the frontend guarantees. |
| Vertex and instance IDs | Shaders use Vulkan GLSL `gl_VertexIndex` (includes base vertex), for example `assets/minecraft/shaders/core/screenquad.vsh:7` | | MSL `[[vertex_id]]`/`[[instance_id]]` include base vertex and base instance. |

---

## 14. `DeviceInfo` / `DeviceLimits` / `DeviceFeatures` / `HintsAndWorkarounds`: every field

`DeviceInfo` (A`device/DeviceInfo.java:5-17`). Built in GL by `GlHeuristics.createDeviceInfo` (B`opengl/GlHeuristics.java:79-119`)
and in Vulkan by the `VulkanDevice` ctor (B`vulkan/VulkanDevice.java:84-118`).

### 14.1 Top-level fields

| Field | GL value | Vulkan value | Read by (game and frontend) | Metal recommendation |
|---|---|---|---|---|
| `name` | `GL_RENDERER` | `deviceName` | Log `Minecraft.java:524`; crash report `:2511`; F3 `net/minecraft/client/gui/components/debug/DebugEntrySystemSpecs.java:52`; GPU warnlist (GL only) `net/minecraft/client/renderer/GpuWarnlistManager.java:143` | `device.name` (for example "Apple M5"). Keep the "Apple" prefix, which `Util.isAppleSiliconMac` tests (`net/minecraft/util/Util.java:604-606`). |
| `vendorName` | `GL_VENDOR` | vendor | Log, crash report, F3 `DebugEntrySystemSpecs.java:50` | `"Apple"` |
| `driverInfo` | `GL_VERSION` | driver info | Log `:523`, crash report, F3 (first line only, `DebugEntrySystemSpecs.java:53,58-60`) | e.g. `"Metal 4 / macOS 27.x (build)"` |
| `isZZeroToOne` | `GL_ARB_clip_control` present. **False on Apple's GL 4.1**, which lacks clip_control. | `true` | `Projection.java:69`, `Camera.java:188`, `PipelineBuilder.java:57` (shader define) | **`true`** (mandatory) |
| `backendName` | `"OpenGL"` | `"Vulkan"` | Log, crash report `:2512`, telemetry `Minecraft.java:748`, F3, **GPU warnlist runs only if `"OpenGL"`** (`GpuWarnlistManager.java:142`) | `"Metal"` |
| `timestampPeriod` | 1.0 | `limits.timestampPeriod` | `TracyGpuProfiler.java:25`, `TimerQuery.java:64` | `1e9 / queryTimestampFrequency` (or measured) |
| `limits` | 14.2 | 14.2 | | |
| `features` | 14.3 | 14.3 | Crash report prints `features().toString()` (`Minecraft.java:2517`) | |
| `underlyingExtensions` | enabled GL extension names | instance extensions `(I)` and device extensions `(D)` | Log `:525` and crash report `:2515` only | Informational: GPU families, "Metal4", "MetalFX", and so on |
| `hintsAndWorkarounds` | 14.4 | 14.4 | | |
| `type` | Intel gives INTEGRATED, or DISCRETE for Arc; llvmpipe/mesa offscreen give CPU; virtgl gives VIRTUAL; else OTHER (`GlHeuristics.java:133-151`) | `physicalDevice.deviceType()` | Crash report `:2516`; F3 suffix "(iGPU)" and so on (`DebugEntrySystemSpecs.java:62-70`) | `INTEGRATED` (unified memory) |

### 14.2 `DeviceLimits` (A`device/DeviceLimits.java:5-17`)

| Field | GL | Vulkan | Read by | Metal recommendation |
|---|---|---|---|---|
| `maxAnisotropy` | `GL_MAX_TEXTURE_MAX_ANISOTROPY` (floored) or 1 (B`opengl/GlDevice.java:133-139`) | `(int) maxSamplerAnisotropy` | `FrontendGpuDevice.createSampler` range check `:73-78`; `Options.maxAnisotropyValue()` clamp (`net/minecraft/client/Options.java:1210-1212`), fed to the terrain sampler (`LevelRenderer.java:430-436`) | **16** (`MTLSamplerDescriptor.maxAnisotropy` 1..16) |
| `minUniformOffsetAlignment` | `GL_UNIFORM_BUFFER_OFFSET_ALIGNMENT` (35380) | `minUniformBufferOffsetAlignment` | Frontend check `FrontendRenderPass.java:151-154`; UBO block padding in `DynamicGpuDataStorageMapped.java:29`, `DynamicGpuDataStorageNonMapped.java:30`, `com/mojang/blaze3d/platform/Lighting.java:29`, `TextureAtlas.java:102,149`; GL push-constant uploads `GlRenderPass.java:90`. **Must be >= 1** (used as a divisor and modulus). | **256** to be safe. Apple's `setVertexBuffer:offset:atIndex:` docs require 256-byte alignment for constant-address-space buffers on macOS, and SPIRV-Cross emits UBOs as `constant`. Try 16 or 64 only after validation-layer testing. Wasted space is small, for example TRANSFORM_UBO (160 B) padded to 256. |
| `maxTextureSize` | Probed with `GL_PROXY_TEXTURE_2D`, from 32768 down (`GlHeuristics.java:63-77`) | `maxImageDimension2D` | `MainTarget.listWithFallback` (`com/mojang/blaze3d/pipeline/MainTarget.java:99-104`), `RenderTarget.createBuffers` (`com/mojang/blaze3d/pipeline/RenderTarget.java:91-92`), and via `maxTextureSizeForFormat` | **16384** (Apple-family 2D limit) |
| `maxMemoryAllocationSize` | `Long.MAX_VALUE` | `maxMemoryAllocationSize` (negative → MAX) | Only `maxTextureSizeForFormat` = `highestOneBit(min(maxTextureSize, (int)sqrt(maxAlloc / blockSize)))` (`DeviceLimits.java:14-16`). Used by `TextureAtlas.java:59` (atlas stitch limit), `net/minecraft/client/gui/render/GuiItemAtlas.java:61`, and the window max size (`Minecraft.java:526`). | `device.maxBufferLength` (keeps the result at 16384) |
| `maxMultiDrawDirectInterleavedDrawCount` | 0 | `VkPhysicalDeviceMultiDrawPropertiesEXT.maxMultiDrawCount` (negative → MAX) | Frontend check only (`FrontendRenderPass.java:263,428`) | 0 if you report `multiDrawDirectInterleaved=false`. The game never uses it. |
| `maxColorAttachments` | `GL_MAX_DRAW_BUFFERS` (34852) | `maxColorAttachments` | `FrontendCommandEncoder.createRenderPass` (`:105-111`) | **8** |
| `maxDrawIndirectDrawCount` | `Integer.MAX` if `GL_ARB_multi_draw_indirect`, 1 if only `GL_ARB_draw_indirect`, else 0 (`GlHeuristics.java:84-86`) | `limits.maxDrawIndirectCount` clamped to int (`VulkanDevice.java:86`) | **`LevelRenderer.java:178` (must be > 0 for MDI)** and `ChunkSectionsToRender.java:132-155` (splits indirect batches into chunks of this size) | **`Integer.MAX_VALUE`**. The backend loops internally (section 15.4). |

### 14.3 `DeviceFeatures` (A`device/DeviceFeatures.java:3-12`)

| Field | GL | Vulkan | Read by | Metal recommendation |
|---|---|---|---|---|
| `wireframeFillMode` | `true` | `fillModeNonSolid` enabled (optional feature set, `VulkanFeatureSets.java:51-53`) | `RenderSystem.isWireframeAvailable()` (`RenderSystem.java:355-357`); `PipelineBuilder.compilePipeline` returns NULL for WIREFRAME pipelines when false (F`shaders/PipelineBuilder.java:373-375`) | **true** (`MTLTriangleFillModeLines`) |
| `shaderDrawParameters` | `GL_ARB_shader_draw_parameters` | `true` | `PipelineBuilder.java:57` adds the define `RENDERPEARL_INSTANCE_INDEX_INCLUDES_BASE_INSTANCE` (F`shaders/GlslCompiler.java:89-91`), which **no vanilla shader references**; GL recompiler (`GlDevice.java:171`) | **true** (`[[instance_id]]` includes base instance, matching Vulkan) |
| `multiDrawDirectInterleaved` | `false` | `VK_EXT_multi_draw` enabled | Frontend gate only | **false** (unused), or true with a CPU loop |
| `multiDrawDirectSeparate` | `true` | `false` | Frontend gate only | **false** (unused) |
| `multiDrawIndirect` | `GL_ARB_multi_draw_indirect` | `true` | Frontend gate for `drawCount > 1` (`FrontendRenderPass.java:311,472`) | **true** |
| `drawIndirect` | `GL_ARB_draw_indirect` | `true` | Frontend gate (`:307,468`) | **true** |
| `nonZeroFirstInstance` | `GL_ARB_base_instance` (GL 4.2, **absent on Apple's GL 4.1**) | `true` | **`LevelRenderer.java:179` (required for MDI)**; frontend gates (`:241,259,406,424`) | **true** (base instance on Apple GPUs) |
| `persistentMapping` | `GL_ARB_buffer_storage` (GL 4.4, absent on Apple's GL 4.1) | `true` | **`StagingBuffer.create` (`com/mojang/blaze3d/vertex/StagingBuffer.java:17`)**; GL transient-memory choice (`GlCommandEncoder.java:54-56`) | **true** (Shared storage, `contents` stays valid for the buffer's lifetime) |

### 14.4 `HintsAndWorkarounds` (A`device/HintsAndWorkarounds.java:3-5`)

| Field | GL | Vulkan | Read by | Effect | Metal recommendation |
|---|---|---|---|---|---|
| `writeToBufferIsSlow` | `isGlOnDx12` (Windows ARM64 or "D3D12" renderer, `GlHeuristics.java:50-53`) | `false` | `StagingBuffer.create` (`StagingBuffer.java:16-20`), but only if `persistentMapping` is also true | true and persistentMapping: the 98 MiB chunk staging (`SectionRenderDispatcher.java:67-69`) becomes a `MappableRingBuffer` of 3 × 49 MiB mapped buffers with usage `MAP_WRITE\|COPY_SRC` (18). Uploads become `copyToBuffer` (GPU blit) (`StagingBuffer.java:119-151`). false: a malloc'd CPU buffer plus `writeToBuffer` per mesh (`:94-117`), which costs **an extra render-thread memcpy** inside the backend's `writeToBuffer` staging. | **true** (saves a render-thread copy of all chunk mesh bytes) |
| `anisotropyHasKnownIssues` | `isAmd` (renderer contains "AMD") | `false` | `GraphicsPreset` picks RGSS instead of ANISOTROPIC texture filtering (`net/minecraft/client/GraphicsPreset.java:99-103`) | preset default only | **false** |
| `isExplicitDepthRequired` | `Util.isAppleSiliconMac(renderer)` | `Util.isAppleSiliconMac(deviceName)` (`VulkanDevice.java:116`) | `GlslCompiler` adds `RENDERPEARL_EXPLICIT_DEPTH_INVARIANCE` (F`shaders/GlslCompiler.java:85-87`), which makes OIT shaders write `gl_FragDepth = gl_FragCoord.z` (`assets/minecraft/shaders/include/oit_add_transmittance.glsl:46-48`, `oit_sample.glsl:101-103`) | Writing depth from the fragment shader disables early-Z and hidden-surface removal for those draws on tile-based GPUs | **true to start** (both other backends set it on Apple Silicon). Candidate for **false** if the Metal backend guarantees `gl_Position` invariance (`MTLCompileOptions.preserveInvariance = YES` plus an Invariant decoration on the position builtin through spvc before MSL generation). See open questions. |
| `multiDrawIndirectHasKnownIssues` | Intel and not Arc (`GlHeuristics.java:115`) | `false` | **`LevelRenderer.java:180` (disables MDI)** | | **false** |

### 14.5 What unlocks what (summary)

* **MDI terrain** (`LevelRenderer.java:178-181,264-270`; `ChunkSectionsToRender.DrawIndirect` `:105-159`) needs
  `maxDrawIndirectDrawCount > 0 && nonZeroFirstInstance && !multiDrawIndirectHasKnownIssues`. The frontend also
  needs `drawIndirect`, plus `multiDrawIndirect` for `drawCount > 1`. A runtime debug toggle
  (`Minecraft.multiDrawIndirect`, F3 key code 109 in `net/minecraft/client/KeyboardHandler.java:100-103`, used via
  `net/minecraft/client/renderer/extract/LevelExtractor.java:231`) can switch it off.
  * MDI per layer: one `setPipeline` + `setVertexBuffer(1, chunkSectionInfos)` (per-instance attributes
    `ChunkPosition`/`ChunkVisibility`, `assets/minecraft/shaders/core/terrain.vsh:17-20,33`), then per draw group
    (one uber vertex/index buffer pair): `setVertexBuffer(0)`, `setIndexBuffer`, and **one `drawIndexedIndirect`**
    with N 20-byte commands that the CPU writes into a mapped buffer (`net/minecraft/client/renderer/DynamicGpuData.java:113-119,128`).
    `firstInstance` indexes the per-section instance data.
  * Non-MDI: `drawMultipleIndexed` costs **per section** a `setVertexBuffer`, a `setUniform("ChunkSection", slice)`,
    and a `drawIndexed` (`ChunkSectionsToRender.java:185-189`, F`FrontendRenderPass.java:347-397`). That is several
    backend calls per chunk section per layer.
* **Persistently mapped staging**: `persistentMapping && writeToBufferIsSlow`.
* **Wireframe debug pipelines** compile only with `wireframeFillMode`.
* **Anisotropic default**: `!anisotropyHasKnownIssues`.

---

## 15. Metal mapping (consolidated)

### 15.1 Device-level objects

| RenderPearl | Metal (classic) | Metal 4 option | Notes |
|---|---|---|---|
| `GpuDeviceBackend` | `id<MTLDevice>` + one `id<MTLCommandQueue>` | `MTL4CommandQueue` + `MTL4CommandAllocator`[framesInFlight] + `MTLResidencySet` | Create everything in the ctor; build `DeviceInfo` once. |
| `CommandEncoderBackend` (singleton) | "Current" `MTLCommandBuffer` (created lazily, `commandBufferWithUnretainedReferences` or a descriptor with `retainedReferences=NO`) + current encoder (render / blit / compute) | `MTL4CommandBuffer` reused per frame slot; `beginCommandBufferWithAllocator:` | Lazily open a blit encoder for consecutive copies; end it when a render pass starts. |
| `RenderPassBackend` | `MTLRenderCommandEncoder` from `MTLRenderPassDescriptor` (load = Clear or Load, store = Store) | `MTL4RenderCommandEncoder` + `MTL4ArgumentTable` | Viewport full-size and flipped (section 13). Initial scissor = render area. |
| `submitRenderPass` | `endEncoding` (or defer it for pass merging, section 16) | same | Vulkan adds a full memory barrier after every pass (`VulkanCommandEncoder.java:409`, `:251-260`). Classic Metal tracks hazards automatically (unless buffers are `MTLHazardTrackingModeUntracked`). In Metal 4 you must add `barrierAfterQueueStages:beforeStages:` and similar barriers yourself. |
| `submit()` | `encodeSignalEvent` + `commit` + throttle | `[queue commit:&cb count:1]` + `signalEvent` | Section 5.4 |
| `GpuFence` | `MTLSharedEvent` value | same | Section 9 |
| `GpuQueryPool` | `MTLCounterSampleBuffer` (Shared) | `MTL4CounterHeap` | Section 10 |
| `GpuSurfaceBackend` | `CAMetalLayer` via SDL MetalView | + `waitForDrawable`/`signalDrawable` | Section 8.4 |
| `createBuffer` | `newBufferWithLength:options:`, Shared for MAP_* (and generally on unified memory), Private optional for GPU-only | `gpuAddress` for argument tables | `map()` returns a `MemoryUtil.memByteBuffer(contents+off, len)` with no copy |
| `createTexture` | `MTLTextureDescriptor`: `type2D` or `typeCube` (usage&16 with 6 layers), `mipmapLevelCount`, usage `ShaderRead` (TEXTURE_BINDING) / `RenderTarget` (RENDER_ATTACHMENT), Private storage | | Metal has no RGB8/RGB16 pixel formats, and `D24_UNORM_S8_UINT` is unsupported on Apple GPUs. Remap or reject. |
| `createTextureView` | `newTextureViewWithPixelFormat:textureType:levels:NSMakeRange(base,count) slices:` | | Cheap. Cache by (texture, range) if they are created often. |
| `createSampler` | `MTLSamplerDescriptor` (`maxAnisotropy`, `lodMaxClamp = maxLod`, mip filter NotMipmapped when `maxLod == 0`) | `supportArgumentBuffers = YES` | Only about 33 are ever created. Deduplicate by key. |
| `compilePipeline` | SPIR-V → MSL (spvc) → `newLibraryWithSource:` or a cached metallib → `newRenderPipelineStateWithDescriptor:` on the worker thread | `MTL4Compiler` + `MTL4PipelineDataSetSerializer` / binary archives | Return a `Pending` that just hands over the result |
| `pushConstants` | `setVertexBytes`/`setFragmentBytes` (≤ 4 KB) | Metal 4 has no setBytes, so suballocate from the transient ring and use `setAddress` | |
| `setUniform(i, slice)` | `setVertexBuffer:offset:atIndex:` / `setFragmentBuffer`, or `setBufferOffset` when only the offset changed | `argTable setAddress:(buf.gpuAddress+off) atIndex:` | Dirty bit per slot; flush at draw time |
| `setUniform(i, TextureViewAndSampler)` | `setFragmentTexture` + `setFragmentSamplerState` (and vertex stage if used) | `setTexture:(tex.gpuResourceID)` / `setSamplerState:` | |
| Texel buffer uniform | `newTextureWithDescriptor:offset:bytesPerRow:` on the MTLBuffer (texture buffer, `MTLTextureTypeTextureBuffer`), created once per (buffer, format) | | The frontend requires the **whole buffer** (`FrontendRenderPass.java:564-578`) |
| `drawIndexed` | `drawIndexedPrimitives:indexCount:indexType:indexBuffer:indexBufferOffset:(firstIndex*idxSize) instanceCount:baseVertex:baseInstance:` | same on `MTL4RenderCommandEncoder` with an index buffer GPU address and length | See the alignment open question |
| `drawIndexedIndirect(slice, n)` | Loop `n` × `drawIndexedPrimitives:indexType:indexBuffer:indexBufferOffset:0 indirectBuffer:buf indirectBufferOffset:off+20*i` | ICB + `executeCommandsInBuffer` (GPU-converted) | Section 16 |
| Clears outside passes | Defer: record "pending clear" on the texture and fold it into the next pass's `loadAction=Clear`; otherwise an empty render pass with Clear | | Section 16 |

### 15.2 Storage modes on the M5 (unified memory)
* `MTLStorageModeShared` for every buffer that is mapped (`MAP_READ`/`MAP_WRITE`), every transient or staging ring,
  every `DynamicGpuData` ring, and the chunk staging rings. The CPU writes straight into `contents`, with no
  `didModifyRange` (that is Managed mode, which Apple Silicon does not need).
* `MTLStorageModePrivate` for render targets and sampled textures (enables lossless framebuffer compression on
  Apple GPUs), and optionally for uber vertex/index buffers, which are written only by GPU copies. Shared buffers
  are equally fast for GPU reads on Apple Silicon, so this is optional.
* `MTLStorageModeMemoryless` only for attachments whose content never leaves the tile. RenderPearl gives no such
  hint today, so this is a game-side opportunity (section 16).

### 15.3 Hazards
RenderPearl assumes full ordering between all commands on the single encoder. Vulkan implements that with a
global barrier after each pass and after init commands. Classic Metal with default hazard tracking gives this
for free between encoders. If you mark buffers `Untracked`, which is faster, add `MTLFence` or barrier updates
between encoders that write and then read the same resource. Metal 4 requires explicit barriers.

### 15.4 Indirect draws on Metal
Metal has no `multiDrawIndirect(count)`. The game's `drawCount` is a CPU-known int, so a loop of
`drawIndexedPrimitives…indirectBuffer:indirectBufferOffset:` (one Objective-C call per draw, roughly 50-100 ns
natively) is simple and correct. To reach a single call per group, convert the 20-byte commands into an
`MTLIndirectCommandBuffer` with a small compute kernel (or CPU-encode into the ICB), then call
`executeCommandsInBuffer:withRange:`. The per-instance vertex buffer (slot 1) and the index buffer must then be
set inherited or bound in the ICB.

---

## 16. Performance opportunities (backend and game side)

1. **Claim the MDI feature set** (section 14.5). This turns per-chunk-section `setVertexBuffer` + `setUniform` +
   `drawIndexed` (3+ backend calls × sections × layers) into one `drawIndexedIndirect` per uber-buffer group.
   It is the single largest CPU win in terrain.
2. **Keep backend calls JNI/FFM-cheap.** Hot paths cross Java→native per call: `setUniform`, `setVertexBuffer`,
   `drawIndexed`, `map`. Record into a native-side command ring (a direct `ByteBuffer` of opcodes) and replay it in
   one native call per pass or at submit. Encoding on the native side also allows multithreaded or parallel
   render encoders later.
3. **Render-pass coalescing and deferred clears on the tile-based GPU.** At 3024×1964, each pass that loads and
   stores an RGBA8 color plus a D32 depth target moves about 3024·1964·8 ≈ 47.5 MB. The game opens about 20-30
   passes per frame (labels such as "Sky", "Main/Solid", "Terrain", "Clouds", "Features, World Border, Weather",
   "Item in hand", "Outline", and post passes), many of them on the same main target. The frame
   graph's `clear` pass (`LevelRenderer.java:247-258`) is a separate full-target clear. Backend-side fixes:
   (a) fold `clearColorAndDepthTextures` into the next pass's `loadAction = Clear`;
   (b) when `createRenderPass` targets **exactly the same attachments** as the pass that just ended, with load
   actions and no intervening copy or clear on those textures, keep the same `MTLRenderCommandEncoder` open
   (defer `endEncoding` until something conflicts);
   (c) use `storeAction = DontCare` for the depth of the final passes when you can prove it is not read later
   (game-side hint needed).
4. **`persistentMapping=true` + `writeToBufferIsSlow=true`** removes a render-thread memcpy of all chunk mesh data
   (section 14.4).
5. **Frames in flight.** Both reference backends block in `submit()` until the previous submit has finished
   (2 in flight). At about 1 ms frames, any GPU hiccup stalls the CPU. Three in flight (with transient rings and
   `MappableRingBuffer` sized for 3; the ring already has `BUFFER_COUNT = 3`, `MappableRingBuffer.java:11`)
   smooths throughput.
6. **Late drawable acquisition and non-blocking present** (section 8.4). Use `displaySyncEnabled = NO` and
   `maximumDrawableCount = 3`. If `nextDrawable` still throttles above the display refresh rate in windowed mode,
   present on a dedicated thread that always shows the latest completed frame, and let the game loop render
   unthrottled.
7. **Render scale + MetalFX in `blitFromTexture`.** The main target is sized to the full Retina framebuffer
   (`Window` uses `HIGH_PIXEL_DENSITY`, `Window.java:165`; the size comes from `SDL_GetWindowSizeInPixels`,
   `Window.java:402-409`). A game-side patch to render at a lower internal resolution, combined with MetalFX
   spatial (or temporal) upscaling in the present pass, cuts fragment cost by about 4× at 0.5× scale. The present
   pass is already a shader pass (section 8.4), so the upscale costs nothing extra structurally.
8. **Make `setPipeline` uniform replay cheap.** The frontend re-sends all stored uniforms on every pipeline
   change (`FrontendRenderPass.java:125`). Compare against the cached binding per slot and skip identical ones.
   Using Metal's `setVertexBufferOffset:atIndex:` when only the offset changed is very cheap.
9. **`GpuBuffer.map()` must be allocation-free.** `DynamicGpuDataStorageMapped.writeData` maps and closes a tiny
   slice **per transform write** (`DynamicGpuDataStorageMapped.java:75-77`). Return cached direct buffers or reuse
   a `MappedView` with a no-op close.
10. **Fences and `getDeviceInfo()` on hot paths.** Make `awaitCompletion(0)` a field compare. Return a cached
    `DeviceInfo`. `FrontendRenderPass.setUniform` calls `getDeviceInfo()` per uniform (`:151`).
11. **Shader and pipeline caching.** Startup compiles every pipeline through shaderc → SPIR-V → spvc → MSL →
    MTLLibrary → PSO. Persist MSL or metallib and binary archives keyed by a SPIR-V hash. `PipelineCache.get`
    compiles synchronously on a miss (`PipelineCache.java:33`), so prewarm it.
12. **`isExplicitDepthRequired` → false** if position invariance is guaranteed. This brings back early-Z and HSR
    in the OIT passes ("improved transparency").
13. **Present pass bandwidth:** with `loadAction = DontCare` on the drawable and `framebufferOnly = YES`, the
    present costs one read of the main color target.
14. **Game side:** add a "discard after pass" or transient-attachment hint to `RenderPassDescriptor` for
    frame-graph internal targets (OIT accumulate, transmittance, depth_bounds; `LevelRenderer.java:217-239`). Those
    could become memoryless or DontCare-stored on a tile-based GPU.

---

## 17. Frontend quirks, bugs, and pitfalls (checklist)

1. `writeTimestamp` bound check is `index <= pool.size()`, so it lets `index == size` through
   (F`FrontendCommandEncoder.java:795`, F`FrontendRenderPass.java:96`).
2. `RenderPass.close()` throws when debug groups are unbalanced **before** `onFinish`, which leaves
   `isInRenderPass` stuck at true (F`FrontendRenderPass.java:493-502`).
3. A null first color attachment NPEs in `createRenderPass` (F`FrontendCommandEncoder.java:121-124`).
4. Depth attachment format and pipeline depth format are never cross-checked (section 6.2).
5. `copyTextureToBuffer` (5-arg) and `copyBufferToTexture` (sx, sy) are validated loosely or not at all (section 5.3).
6. `createTextureView` does not reject `mipLevels == 0`.
7. `FrontendGpuDevice.close()` never closes the Tracy profiler's query pool.
8. `submit()` is not blocked during an open render pass (the backend must throw).
9. `present()` throwing freezes the frame loop (section 8.1). Defer the error to the next acquire.
10. Production builds skip STRICT validation (section 0, item 1). The backend must survive missing bindings.
11. All closes of in-flight resources must be deferred (section 12).
12. `copyTextureToBuffer` callbacks must run on the render thread after completion (section 5.3).
13. `MappableRingBuffer` waits with `-1L`. If a backend fence ever reports "never completes" (for example, it is
    tied to a command buffer that was never committed), the game hangs.
14. The index buffer is bound **whole**, at offset 0. Draw offsets come from `firstIndex`, so the byte offset is
    `firstIndex * (2|4)`.
15. GL fences and timeouts are in **ns** (`glClientWaitSync`). Metal `waitUntilSignaledValue:timeoutMS:` takes
    **ms**, so convert.

---

## 18. Open questions to resolve on the M5 / macOS 27

1. **Index buffer offset alignment.** Does Metal on macOS (Apple-family GPU) require `indexBufferOffset % 4 == 0`
   for `uint16` indices (odd `firstIndex`)? Vanilla `firstIndex` values are mostly multiples of 6 (quads), but
   `WeatherEffectRenderer` uses `startColumn*6`, `DebugCrosshairRenderer` uses 18, and GUI/particle batches use
   their own `firstIndex`. If alignment is required, fall back to the indirect-args path, where `indexStart` is in
   elements.
2. **`minUniformOffsetAlignment`.** Is 256 truly required for `constant` address-space buffers on Apple Silicon
   macOS, or are 16/64 accepted by the validation layer? This affects UBO packing only, not correctness, if you
   report 256.
3. **`isExplicitDepthRequired`.** What is the actual Apple-Silicon artifact this works around (depth invariance
   between the OIT depth pre-pass and the accumulate pass)? Does `preserveInvariance` plus an invariant position
   fix it, so the hint can be false?
4. **Drawable throttling.** With `displaySyncEnabled = NO` in windowed mode on a 120 Hz ProMotion panel, does
   `nextDrawable` block once more than about 3 frames are queued? Measure before building a presenter thread.
5. **Metal 4 adoption.** Confirm the exact selectors in the macOS 27 SDK: `MTL4CounterHeap`,
   `writeTimestampIntoHeap:atIndex:`, `queryTimestampFrequency`, `waitForDrawable:`/`signalDrawable:`,
   `MTL4ArgumentTable`. Decide whether to start with classic Metal (automatic hazard tracking and simpler code)
   and move hot paths to Metal 4 later.
6. **GPU timestamp domain.** Are Apple GPU timestamps in the `mach_absolute_time` domain (24 MHz ticks) or in ns?
   That decides the `timestampPeriod` and calibration math.
7. **Present-mode reporting.** Should Metal report `MAILBOX`? (`getSupportedVsyncMode` prefers IMMEDIATE, so
   reporting `{FIFO, IMMEDIATE}` is sufficient.)
8. **Multiple render passes on the same targets.** How much of the main-target pass sequence can be merged safely?
   It depends on whether any copy or `copyTextureToTexture` of the main target happens between passes (for example
   the "Blit render target" or "Integrate depth" passes). Audit the frame graph in the render-pass doc.
9. **`PreferredGraphicsApi.DEFAULT` on macOS.** Should DEFAULT try Metal first in this custom build? Should the
   crash-recovery path downgrade METAL to DEFAULT, which would be GL, or keep it?
