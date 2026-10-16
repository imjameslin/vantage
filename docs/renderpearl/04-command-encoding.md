# 04 — Command encoding and render passes (the hot path)

Scope: `CommandEncoderBackend`, `RenderPassBackend`, the frontend wrappers that sit in front of them, and how
the Vulkan and OpenGL backends implement them. This includes barriers, descriptor handling, draws, clears,
copies, submits, fences and timestamps. The second half maps each concept onto Metal (Metal 3 API and the
Metal 4 API on macOS 27), and lists pitfalls and FPS opportunities.

Path conventions: every path is relative to `src/vanilla/`. `rp/` means `com/mojang/renderpearl/`.
`file:N` is a line number and `file:N-M` is a line range. "VK" is the Vulkan backend
(`rp/backend/vulkan`) and "GL" is the OpenGL backend (`rp/backend/opengl`).

---

## 0. The short version (invariants a Metal backend must honour)

1. **There is exactly one command encoder for the life of the device**, and it is used only from the render
   thread. `FrontendGpuDevice` creates one `FrontendCommandEncoder` around a single
   `backend.createCommandEncoder()`, and `createCommandEncoder()` always returns that same instance
   (`rp/frontend/FrontendGpuDevice.java:50,60-62`). GL asserts `RenderSystem.assertOnRenderThread()` in almost
   every `GlStateManager` call (`rp/backend/opengl/GlStateManager.java:41-616`).
2. **At most one render pass is open at a time.** While a pass is open, no encoder-level operation is allowed:
   no clears, copies, writes, fences or new passes. The frontend enforces this with `isInRenderPass`
   (`rp/frontend/FrontendCommandEncoder.java:101-103,232-234,293-295,…,786-788`). There are two exceptions.
   `writeTimestamp` (encoder level) does not check `isInRenderPass` (`:793-800`).
   `GpuSurface.blitFromTexture` checks it separately (`rp/frontend/FrontendGpuSurface.java:82-84`).
3. **Every operation is fully ordered with respect to every other operation.** VK inserts a *global*
   `ALL_COMMANDS→ALL_COMMANDS, MEMORY_READ|WRITE` barrier after every render pass, clear and copy
   (`VulkanCommandEncoder.java:251-260`, called at `:409,455,480,598,626,673,726,776,825`). All images live
   permanently in `VK_IMAGE_LAYOUT_GENERAL`; layout `1` is used everywhere (`:328,358,435,446,…`). Nothing in
   the API lets the game express finer dependencies. A Metal backend may only relax this ordering if it can
   prove a relaxation is safe.
4. **`submit()` is called once per frame** (`net/minecraft/client/Minecraft.java:1337`). After it queues the
   frame it **blocks until the previous submit has finished on the GPU**: `MAX_SUBMITS_IN_FLIGHT = 2`,
   implemented as "wait for index−2 after increment" (`VulkanCommandEncoder.java:49,262-279`;
   `GlCommandEncoder.java:40,74-83`). Deferred destruction, transient memory and readback callbacks are all
   keyed to this 2-deep rotation.
5. **Render-target memory layout follows the GL convention: row 0 is the bottom of the image.** VK renders with
   a non-flipped viewport `(0,0,W,H)` and `frontFace = CLOCKWISE`, then **flips Y in the present blit**
   (`VulkanRenderPass.java:80-87`, `VulkanRenderPipeline.java:269-270`, `VulkanGpuSurface.java:416-418`).
   Scissor and clear-region Y coordinates are in that same row space; the GUI computes
   `y = window.height - bottom` (`net/minecraft/client/gui/render/GuiRenderer.java:419`). In Metal, NDC +Y maps
   to row 0, so **the Metal backend must negate `gl_Position.y` (or flip another way) and use
   `MTLWindingClockwise`.** See §2.
6. **Depth is reversed-Z, in the range [0,1], D32_FLOAT.** The projection swaps near and far
   (`net/minecraft/client/renderer/Projection.java:67-68`). Every clear uses depth `0.0`
   (`GameRenderer.java:479-507,690`). Pipelines use `GREATER_THAN_OR_EQUAL`
   (`RenderPipelines.java:44,57,…`). VK reports `isZZeroToOne = true` (`VulkanDevice.java:91`). VK hard-codes
   `VK_FORMAT_D32_SFLOAT` as the pipeline depth format (`VulkanRenderPipeline.java:321`).
7. **The uniform `index` passed to `RenderPassBackend.setUniform(index, value)` is per pipeline.** It is
   `set=0, binding=index`, assigned in order of first appearance in the shaders' reflection
   (`rp/frontend/shaders/PipelineBuilder.java:283,298-299`). The same name can have different indices in
   different pipelines. `value` is a `GpuBufferSlice` (UBO or texel buffer), a `TextureViewAndSampler`, or
   `null`. It is never a bare `GpuTextureView` or `GpuBuffer` (§5.4).
8. **The hot path in a typical frame is:**
   - terrain through `drawIndexedIndirect`, the multi-draw-indirect (MDI) path, when the device reports
     `nonZeroFirstInstance` and `maxDrawIndirectDrawCount > 0`
     (`LevelRenderer.java:178-181,264`; `ChunkSectionsToRender.java:129-157`);
   - otherwise terrain through `drawMultipleIndexed`, which issues one `drawIndexed` plus one per-draw UBO
     rebind per chunk section (`ChunkSectionsToRender.java:185-189`, `FrontendRenderPass.java:331-398`);
   - entities, particles and other features through `PreparedRenderType.draw` (`setPipeline` + about 10
     `setUniform` + `drawIndexed` per batch) (`rendertype/PreparedRenderType.java:37-56`);
   - the GUI (`GuiRenderer.java:372-401`).

   Game code never calls `multiDraw*`, `drawIndirect` or `pushConstants` (§9).

---

## 1. Object model, lifecycle, threading, frame flow

### 1.1 Wrappers

| Layer | Class | Role |
|---|---|---|
| API | `rp/api/commands/CommandEncoder` (`:16-108`), `RenderPass` (`:18-105`), `RenderPassDescriptor` (`:14-98`), `GpuFence` (`:5-12`), `GpuQueryPool` (`:6-12`) | Game-facing interfaces |
| Frontend | `rp/frontend/FrontendCommandEncoder` (`:27-877`), `FrontendRenderPass` (`:35-635`) | Validation, name→index uniform resolution, debug-group balance, Tracy zones |
| Backend SPI | `rp/backend/api/CommandEncoderBackend` (`:13-70`), `RenderPassBackend` (`:13-49`) | What the Metal backend implements |
| VK | `VulkanCommandEncoder` (`:48-958`), `VulkanRenderPass` (`:42-396`) | Reference implementation (explicit API) |
| GL | `GlCommandEncoder` (`:38-631`), `GlRenderPass` (`:20-187`) | Reference implementation (immediate API, lazy state) |

### 1.2 Render pass lifecycle

```
CommandEncoder.createRenderPass(desc)          FrontendCommandEncoder.java:100-216
  ├─ validate (see §3.3), isInRenderPass = true
  ├─ [Tracy] profiler.pushZone → encoder.writeTimestamp(pool, id)        (:198-201; before backend pass!)
  └─ backend.createRenderPass(desc) → RenderPassBackend                   (:203-210)
RenderPass.* calls → FrontendRenderPass → RenderPassBackend
RenderPass.close()                              FrontendRenderPass.java:492-502
  └─ onFinish = FrontendCommandEncoder.submitRenderPass                   (:218-228)
        ├─ backend.submitRenderPass()
        └─ [Tracy] profiler.popZone → encoder.writeTimestamp(pool, id)    (after backend pass end)
```

- The backend pass object is used for exactly one pass. VK allocates a new `VulkanRenderPass` per pass
  (`VulkanCommandEncoder.java:374-376`). GL allocates a new `GlRenderPass` per pass
  (`GlCommandEncoder.java:193`).
- After `close()`, the frontend stops forwarding draws (`isClosed` checks). It **does not** stop
  `setPipeline`, `setUniform`, `setVertexBuffer`, `setIndexBuffer`, `pushConstants` or the scissor calls
  (`FrontendRenderPass.java:103-233`). A defensive backend should ignore or throw on use of a finished pass.
- `close()` throws if debug groups are left open, but only *after* marking the pass closed and **before** it
  calls `onFinish` (`:495-500`). If a group is left open, the pass therefore never reaches
  `backend.submitRenderPass()`.

### 1.3 Frame flow (one frame, render thread)

From `net/minecraft/client/Minecraft.java`:

1. `windowSurface.acquireNextTexture()` (`:1273`), at frame start, before update and extract.
2. `RenderSystem.executePendingTasks()` (`:1310`). This polls `GpuFence.awaitCompletion(0)` for queued readback
   callbacks (`com/mojang/blaze3d/systems/RenderSystem.java:323-341`).
3. `gameRenderer.render()` (`:1312`). This produces all clears, copies, writes and render passes (see §9.2).
4. `windowSurface.blitFromTexture(encoder, mainColorView)` (`:1322`). This is recorded into the encoder and is
   **not** a render pass.
5. `encoder.submit()` (`:1337`). This queues the frame and waits for frame N−1.
6. `windowSurface.present()` (`:1340`).
7. `RenderSystem.getDynamicUniforms().reset()` (`:1348`) rotates the mapped UBO ring buffers. They are guarded
   by `GpuFence`s created at `rotate()` (`MappableRingBuffer.java:36-53`).

`present()` comes **after** `submit()`. In VK, `blitFromTexture` adds a wait on the acquire semaphore, the
blit command buffer, and a signal of the present semaphore to the submission (`VulkanGpuSurface.java:497-499`).
The actual `vkQueuePresentKHR` happens in `present()`.

---

## 2. Coordinate conventions that affect passes (viewport, scissor, Y, Z, winding)

| Item | VK | GL | Metal requirement |
|---|---|---|---|
| Viewport | Set once at pass begin to `(0,0,outW,outH)`, depth 0..1. `outW/H` = size of the **last** non-null color attachment view at its mip 0, or of the depth view if there is no color (`VulkanCommandEncoder.java:302-315`, `VulkanRenderPass.java:80-87`). No API to change it. | `glViewport(0,0,w,h)` per pass (`GlCommandEncoder.java:178`) | The default `MTLViewport` of a render encoder is the full render target, which equals this. Depth range 0..1 is native. Do not set it per draw. |
| Y orientation | NDC −1 → row 0 (no flip). Present blit flips (`VulkanGpuSurface.java:416-418`). | NDC −1 → row 0 (GL native). Lower-left `glClipControl` (`GlDevice.java:148-150`). Present blit does not flip (`GlCommandEncoder.java:436-447`). | Metal maps NDC +1 → row 0. **Negate clip-space Y in every vertex shader** (SPIRV-Cross `SPVC_COMPILER_OPTION_FLIP_VERTEX_Y` exists in the shipped `lwjgl-spvc`). Then sampled UVs, scissors, readbacks and texture uploads all match VK and GL unchanged. **Flip in the final present blit**, as VK does. |
| Front face | `VK_FRONT_FACE_CLOCKWISE`, cull `BACK` if `cull` (`VulkanRenderPipeline.java:266-271`) | Default CCW, `glEnable(CULL_FACE)` (`GlRenderPipeline.java:131-135`) | With the Y negation above, the Metal window space equals VK framebuffer space. Use `setFrontFacingWinding:MTLWindingClockwise` and `setCullMode:MTLCullModeBack/None`. (Same as MoltenVK's CW→CW mapping with its default vertex-Y flip.) Verify once with a culled cube. |
| Scissor | `vkCmdSetScissor(x,y,w,h)` passed through unchanged (`VulkanRenderPass.java:174-179`) | `glScissor(x,y,w,h)` (bottom-left = row 0) | `setScissorRect:{x,y,w,h}` passed through unchanged, given the Y flip above. |
| Depth | [0,1], reversed-Z, clear 0.0 | `glClipControl(LOWER_LEFT, ZERO_TO_ONE)` when available, else `isZZeroToOne=false` (`GlHeuristics.java:91`) | Report `isZZeroToOne=true`. The shader macro `RENDERPEARL_DEPTH_IS_ZERO_TO_ONE` follows from it (`GlslCompiler.java:81-83`). |

---

## 3. `CommandEncoderBackend`: every method

Interface: `rp/backend/api/CommandEncoderBackend.java:13-70`. Every method below is called with **no render pass
open**; the frontend checks this, except for `writeTimestamp`.

### 3.1 `void submit()`

- **Frontend:** `backend.submit()`, then Tracy `endFrame()` reads back timestamps
  (`FrontendCommandEncoder.java:45-51`).
- **VK** (`VulkanCommandEncoder.java:262-279`):
  1. `endCommandBuffer()` flushes the "object init" command buffer first, with a trailing global barrier, then
     the main command buffer into the pending `VulkanQueue.Submission` (`:180-218`).
  2. `transientMemory.endSubmit()` ends the transient-copy command buffer. That buffer was **put at the front of
     this submission** when the previous submit finished (`VulkanTransientMemory.java:152-194`).
  3. `signalSemaphore(timeline, currentSubmitIndex, ALL_COMMANDS)`. One timeline semaphore is created with
     initial value 1; `currentSubmitIndex` starts at 2 (`:53,70-79`).
  4. `submissionBuilder.close()` makes one `vkQueueSubmit2KHR` call with one or more `VkSubmitInfo2` "stages".
     A new stage starts whenever a wait follows command buffers or signals, or a command buffer follows a
     signal (`VulkanQueue.java:57-164`).
  5. `currentSubmitIndex++`, then `vkWaitSemaphores(currentSubmitIndex-2, 5 s)`. On timeout it throws
     `IllegalStateException` with GPU checkpoints (`:270-273`).
  6. `currentCommandPool().reset()` resets one of 2 pools, indexed by `submitIndex % 2` (`:125-127`,
     `VulkanCommandPool.java:71-74`). Then `destroyQueue.rotate()`, `checkpointStorage.rotate()`, and
     `transientMemory.beginSubmit()`.
- **GL** (`GlCommandEncoder.java:74-83`): `glFenceSync` goes into `fences[index%2]`. Then index++,
  `awaitSubmit(index-2, -1)` (a `glClientWaitSync` with `FLUSH_COMMANDS`, infinite), then
  `transientMemory.rotate()`. There is no `glFlush`.
- **Command buffers per frame in VK.** One `vkQueueSubmit2` call carries 2–3 submit-info stages and typically
  4–5 primary command buffers:
  - `[transient-copy CB]`
  - `[init CB]` (only if objects were created)
  - `[main CB]`
  - then `| wait acquireSem |` `[blit CB]` `| signal presentSem |`
  - then `[trailing CB]` if anything was recorded after the blit; `TimerQuery.endProfile` and Tracy capture do
    this
  - and finally `| signal timeline=N |`.

  Command buffers come from per-slot pools in blocks of 32 (`VulkanCommandPool.java:13,76-118`) and are begun
  `ONE_TIME_SUBMIT` (`VulkanCommandEncoder.java:129-156`).

### 3.2 `TransientMemory transientMemory()`

This returns the per-encoder transient allocator, documented separately. What matters here is that GL uses it
for push constants (`GlRenderPass.java:88-92`) and VK uses it for `writeToBuffer` and `writeToTexture` staging
(`VulkanCommandEncoder.java:589,655`). In VK, GPU-mapped transient blocks are copied staging→device **at the
very beginning of the submit**, in the transient CB (`VulkanTransientMemory.java:196-225`). That is legal only
because a transient allocation is fresh in every submit. On Apple unified memory, those copies should not
exist at all: use `MTLStorageModeShared`.

### 3.3 `RenderPassBackend createRenderPass(RenderPassDescriptor)` and `void submitRenderPass()`

**What the frontend validates** (`FrontendCommandEncoder.java:100-216`):
- No pass is already open.
- `colorAttachments.size() <= limits.maxColorAttachments` and total attachments ≥ 1.
- The render area lies within the first color attachment's mip-0 size, or the depth view's if there are no
  color attachments. The first entry is asserted non-null (`:121-128`), so do not put
  `withUnusedColorAttachment()` first.
- For each non-null color attachment and for depth: not closed, `USAGE_RENDER_ATTACHMENT` (bit 8),
  `depthOrLayers == 1` (no array or cube attachments), and the same size as the reference attachment.
- **Not validated:** the depth format. It is assumed to be D32_FLOAT: VK hard-codes it and the game only uses
  D32_FLOAT (`MainTarget.java:18`, `RenderTargetDescriptor.java:47`, `GameRenderer.java:179`). Formats are also
  not validated here; color formats are checked later, in `setPipeline`.

**Descriptor semantics** (`rp/api/commands/RenderPassDescriptor.java`):

| Field | Meaning | VK | GL |
|---|---|---|---|
| `colorAttachments[i] = Attachment(view, Optional<Vector4fc> clear)` | Render to `view.texture()` at mip `view.baseMipLevel()`, layer 0. Clear is **RGBA float** (`x,y,z,w`→R,G,B,A; `VulkanUtils.putArgb` despite the name, `VulkanUtils.java:249-255`). | `loadOp = clear.present ? CLEAR : LOAD`, `storeOp = STORE` always, layout `GENERAL` (`VulkanCommandEncoder.java:323-339`) | Cached FBO plus `glClearBuffer` inside the scissor = render area (`GlCommandEncoder.java:134-168`) |
| `colorAttachments[i] = null` (`withUnusedColorAttachment`) | Slot exists but is unbound | `imageView = NULL`, `loadOp = DONT_CARE`, `storeOp = DONT_CARE` (`:340-345`) | FBO slot 0, `glDrawBuffers[i] = GL_NONE` (`:179-189`) |
| `depthAttachment = Attachment(view, OptionalDouble clear)` | Same, depth aspect. The clear value is cast to float. | `CLEAR/LOAD`, `STORE` (`:354-371`) | `glClearBufferfv(GL_DEPTH)` after `depthMask(true)` (`:170-176`) |
| `renderArea (x,y,w,h)` | Defaults to `(0,0,w,h)` of the attachments (`RenderPassDescriptor.java:71-96`). It bounds the **load-op clear** and the **initial and "disabled" scissor**. It does **not** change the viewport. | `VkRenderingInfo.renderArea`, and the initial scissor = render area (`VulkanRenderPass.java:88`) | `glScissor(renderArea)` and enables scissor (`GlCommandEncoder.java:155-157`) |
| `label` (`Supplier<String>`) | Debug label, evaluated lazily and only when labels are enabled | `beginDebugGroup(label)` plus checkpoint before `vkCmdBeginRenderingKHR` (`:297-298,373`) | `pushDebugGroup(label)` (`:125`) |

- Mip-level rendering is done by passing a single-mip **view** (`TextureAtlas.java:179-183,249-251` use
  `mipViews[level]`). VK uses the view's `VkImageView`, which has `levelCount = view.mipLevels()`
  (`VulkanGpuTextureView.java:23-28`). GL attaches at `fboMipLevel() = baseMipLevel()`
  (`GlTextureView.java:46-48`, `FrameBufferCache.java:41-46`).
- **`submitRenderPass()` in VK:** `vkCmdEndRenderingKHR`, end debug group, checkpoint, then **global memory
  barrier** (`VulkanCommandEncoder.java:396-425`).
- **`submitRenderPass()` in GL:** bind FBO 0 and pop the debug group (`GlCommandEncoder.java:621-625`).
- VK keeps **two pipelines per `RenderPipeline`**: `withDepthPipeline` (depth format D32) and
  `withoutDepthPipeline`. The second is compiled **only if `depthStencilState == null`**; otherwise its handle
  is 0 (`VulkanRenderPipeline.java:340-354`). `setPipeline` picks the variant by whether the pass has depth
  (`VulkanRenderPass.java:133`). A pipeline with depth state used in a depth-less pass therefore binds handle 0,
  which is undefined behaviour. The frontend catches this only under `STRICT_VALIDATION`
  (`FrontendRenderPass.java:628-632`), and `STRICT_VALIDATION = SharedConstants.IS_RUNNING_IN_IDE`
  (`FrontendGpuDevice.java:36`), which is off in release builds.

### 3.4 Clears outside a pass

There is **no in-pass clear API**. In-pass clears happen only through descriptor load ops.

| Method | Frontend checks | VK | GL |
|---|---|---|---|
| `clearColorTexture(tex, rgba)` | Color format, not closed, `RENDER_ATTACHMENT` and `COPY_DST`, 1 layer (`FrontendCommandEncoder.java:230-238,802-822`) | `vkCmdClearColorImage`, **all mip levels**, layer 0, then barrier (`VulkanCommandEncoder.java:427-436,449-471`) | Loop over mips: attach to the scratch draw FBO and `glClear` with scissor off and colorMask 15 (`GlCommandEncoder.java:196-209`) |
| `clearDepthTexture(tex, d)` | Depth format and similar checks (`:281-289,824-844`) | `vkCmdClearDepthStencilImage`, all mips, then barrier (`:438-447,562-584`) | Loop over mips, `glClear(DEPTH)` (`:256-271`) |
| `clearColorAndDepthTextures(c, rgba, d, depth)` | Both checks, and the **mip counts must be equal** (`:240-253`) | Both clears, one barrier (`:473-496`) | Per mip: cached FBO plus `glClear(COLOR\|DEPTH)` (`:211-228`) |
| `clearColorAndDepthTextures(c, rgba, d, depth, x, y, w, h, mip)` | Region inside **color** mip `mip`, `w,h > 0` (`:255-279,846-876`). Depth size is not checked. | Temporary views of `(mip, 1 level)`, a temporary render pass with **LOAD** ops and render area = full mip, `vkCmdClearAttachments` with one rect, then `submitRenderPass` (barrier) (`:498-560`) | `glScissor(region)` plus `glClear` on the FBO at `mip` (`:230-254`) |

Region coordinates are in the same row space as the scissor (row 0 = GL bottom).

### 3.5 Buffer writes and copies

| Method | Units and semantics | Frontend checks | VK | GL |
|---|---|---|---|---|
| `writeToBuffer(GpuBufferSlice dst, ByteBuffer data)` | Copies `data.remaining()` bytes, from `data.position()`, to `dst.offset`. `dst.length` may be larger. **Must not consume or modify `data`; the caller may free it immediately** (stack buffers: `ProjectionMatrixBuffer.java:47-50`, `Lighting.java:66-67`). Ordered in-stream: earlier draws see the old contents, later draws see the new. | dst not closed, `COPY_DST`, `remaining <= dst.length` (`FrontendCommandEncoder.java:291-326`) | `uploadStaging(data, align 1, COPY_SRC)`, `vkCmdCopyBuffer`, barrier (`VulkanCommandEncoder.java:586-614`) | DSA `bufferSubData` (`GlCommandEncoder.java:273-278`) |
| `copyToBuffer(src, dst)` | `src.length` bytes, equal lengths required | Both not closed, `COPY_SRC` and `COPY_DST`, bounds (`:328-383`) | `vkCmdCopyBuffer`, barrier (`:616-642`) | `copyBufferSubData` (`:280-287`) |

### 3.6 Texture uploads and copies

| Method | Semantics and units | VK | GL |
|---|---|---|---|
| `writeToTexture(tex, ByteBuffer src, mip, layer, x, y, w, h)` | `src` holds `w*h*blockSize` tightly packed bytes (row length = `w` texels). Writes the region at (`x`,`y`) of `mip` and `layer`; the layer is the cube face for cubemaps. The frontend checks bounds, `COPY_DST` (bit 1) and `layer < depthOrLayers` (`FrontendCommandEncoder.java:455-518`). The `NativeImage` overloads require UNORM_8 and a matching channel count (`:398-453`). | Staging (align 1), `vkCmdCopyBufferToImage` with `bufferRowLength = w`, `imageHeight = h`, color aspect, then barrier (`VulkanCommandEncoder.java:644-689`) | Bind texture (cube face target if `USAGE_CUBEMAP_COMPATIBLE`), set `UNPACK_ROW_LENGTH = w` and `UNPACK_ALIGNMENT = byteAlignment()`, then `glTexSubImage2D` (`GlCommandEncoder.java:289-316`) |
| `copyBufferToTexture(srcSlice, sx, sy, sW, sH, tex, dx, dy, cw, ch, mip, layer)` | The source is an `sW×sH`-texel image laid out tightly in the buffer starting at `src.offset`. The copy reads the `cw×ch` sub-rect at (`sx`,`sy`). Byte offset = `src.offset + (sx + sy*sW)*blockSize`. The frontend checks `sW*ch*blockSize <= src.length` (`:557`; note it uses `sW*ch`, not a sub-rect end), plus bounds, `COPY_SRC` on the buffer and `COPY_DST` on the texture (`:520-615`). | `bufferOffset = src.offset + skipBytes`, `rowLength = sW`, `imageHeight = sH` (`VulkanCommandEncoder.java:691-742`) | PBO unpack with `ROW_LENGTH = sW` and `IMAGE_HEIGHT = sH` (`GlCommandEncoder.java:318-364`) |
| `copyTextureToBuffer(tex, buf, offset, Runnable cb, mip[, x, y, w, h])` | Rows packed tightly (`w*blockSize`) at byte `offset`. **`cb` runs on the render thread after the GPU has finished.** The 5-argument form has **no frontend validation** beyond the in-pass check (`:617-624`). The 9-argument form checks bounds, `COPY_SRC` on the texture and `COPY_DST` on the buffer, and 1 layer (`:626-699`). | `vkCmdCopyImageToBuffer` with aspect from the format (color or depth), then barrier. The callback is put in the destroy queue (`:744-794`), so it runs inside the `submit()` *after next*, once that submit's wait has passed. | Readback FBO plus `glReadPixels` into a PBO. Callback via `RenderSystem.queueFencedTask`, which runs in `executePendingTasks` once the fence passes (`GlCommandEncoder.java:366-404`). |
| `copyTextureToTexture(src, dst, mip, dx, dy, sx, sy, w, h)` | Same mip in both. 2D only. The frontend checks bounds, `COPY_SRC` and `COPY_DST`, and 1 layer (`:701-782`). Used for depth copies (`RenderTarget.copyDepthFrom`, `com/mojang/blaze3d/pipeline/RenderTarget.java:62-86`). | `vkCmdCopyImage`, aspect from the source format, barrier (`:796-841`) | `glBlitFramebuffer` NEAREST between scratch FBOs; color or depth bit (`:406-434`) |

### 3.7 `GpuFence createFence()`

- Captures `currentSubmitIndex`, the submit still being **recorded** (`VulkanCommandEncoder.java:891-911`,
  `GlFence.java:10-28`). The fence signals when *that* frame's submit completes.
- `awaitCompletion(timeoutNs)`:
  - returns `true` if already completed;
  - if the fence's submit **is the current one** (not yet submitted): returns `false` when `timeout == 0`,
    otherwise **throws** `"Cannot wait on a fence for the current submit"`
    (`VulkanCommandEncoder.java:848-853`, `GlCommandEncoder.java:90-95`);
  - otherwise waits on the GPU: `vkWaitSemaphores` with the timeout in ns, or `glClientWaitSync`.

  `GpuFence.NO_TIMEOUT = -1`, which both APIs treat as "forever" (unsigned maximum). The VK anonymous class
  names its parameter `timeoutMs`, but it is passed through as **nanoseconds** (`:898-901`).
- `close()` just marks the fence completed. No native object exists.
- Game usage:
  - `MappableRingBuffer.currentBuffer()` does `awaitCompletion(-1)` on a fence from 3 rotations ago
    (`MappableRingBuffer.java:36-53`).
  - `StagedVertexBuffer` pool recycling polls `awaitCompletion(0)` (`StagedVertexBuffer.java:352,377`).
  - Fenced readback tasks also poll (`RenderSystem.java:323-341`).

### 3.8 `void writeTimestamp(GpuQueryPool pool, int index)` (encoder level)

- **VK:** host `vkResetQueryPool(index, 1)` *at record time*, then
  `vkCmdWriteTimestamp2(ALL_COMMANDS, pool, index)` (`VulkanCommandEncoder.java:913-918`).
- **GL:** `glQueryCounter(GL_TIMESTAMP)` (`GlQueryPool.java:44-46`).
- **Reading back:** `getValues(i, n)` is **non-blocking** and returns `OptionalLong.empty()` if a value is not
  yet available. VK uses `64_BIT | WITH_AVAILABILITY`, stride 16 (`VulkanQueryPool.java:55-96`); GL checks
  `GL_QUERY_RESULT_AVAILABLE` (`GlQueryPool.java:21-25`).
- **Converting to ns:** values are multiplied by `DeviceInfo.timestampPeriod()` (`TracyGpuProfiler.java:62`,
  `TimerQuery.java:64`).
- **Users:**
  - `TimerQuery` writes one timestamp before rendering and one after the blit, but only while the
    GPU-utilization debug overlay or a metrics recording is active (`Minecraft.java:1282-1290,1325-1327`,
    `TimerQuery.java:20-40`).
  - The Tracy profiler writes one before and one after every render pass, only when Tracy is attached
    (`FrontendCommandEncoder.java:198-201,225-227`).
- **Frontend bug:** the bounds check is `index <= pool.size()`, an off-by-one (`:795`, and the pass version at
  `FrontendRenderPass.java:96`). Bounds-check in the backend.

---

## 4. Draw-call data layouts (exact)

Constants are in `rp/api/commands/RenderPass.java:19-21`: `MAX_VERTEX_BUFFERS = 16`,
`INDIRECT_DRAW_SIZE = 16`, `INDIRECT_INDEXED_DRAW_SIZE = 20`.

| Call | Layout | Units |
|---|---|---|
| `drawIndexed(indexCount, instanceCount, firstIndex, vertexOffset, firstInstance)` | — | `firstIndex` counts **indices**: byte offset = `firstIndex*indexType.bytes` (`GlCommandEncoder.java:470`). `vertexOffset` is a signed base vertex added to each index value. |
| `draw(vertexCount, instanceCount, firstVertex, firstInstance)` | — | Vertices |
| `multiDrawIndexed(IntBuffer p, instanceCount, firstInstance, drawCount)` (**interleaved**) | Starting at `p.position()`: `drawCount × {uint firstIndex, uint indexCount, int vertexOffset}`, stride 12 bytes = `VkMultiDrawIndexedInfoEXT` (`VulkanRenderPass.java:232-238`). `instanceCount` and `firstInstance` apply to every draw. The frontend checks `remaining >= 3*drawCount` (`FrontendRenderPass.java:267`). | Indices and vertices |
| `multiDraw(IntBuffer p, instanceCount, firstInstance, drawCount)` (**interleaved**) | `drawCount × {uint firstVertex, uint vertexCount}`, stride 8 = `VkMultiDrawInfoEXT` (`VulkanRenderPass.java:259-265`). The frontend checks `remaining >= 2*drawCount` (`:432`). | Vertices |
| `multiDrawIndexed(PointerBuffer firstIndexOffsets, IntBuffer indexCounts, IntBuffer vertexOffsets, drawCount)` (**separate**) | Three parallel arrays. `firstIndexOffsets` are **byte offsets** into the index buffer (pointer-sized; GL passes them as the `indices[]` of `glMultiDrawElementsBaseVertex`, `GlCommandEncoder.java:502-509`). Instance count is 1, base instance 0. | Bytes, indices, vertices |
| `multiDraw(IntBuffer firstVertices, IntBuffer vertexCounts, drawCount)` (**separate**) | Two parallel arrays (`glMultiDrawArrays`, `GlCommandEncoder.java:496-499`) | Vertices |
| `drawIndexedIndirect(GpuBufferSlice cmds, drawCount)` | `drawCount × {uint indexCount, uint instanceCount, uint firstIndex, int vertexOffset, uint firstInstance}`, **stride 20 bytes, tightly packed**, starting at `cmds.offset`. The frontend requires `USAGE_INDIRECT_PARAMETERS` (512), `cmds.length >= 20*drawCount`, offset multiple of 4, `drawCount <= 1` unless `multiDrawIndirect` (`FrontendRenderPass.java:301-329`). The game writes the records on the CPU into a mapped ring (`DynamicGpuData.java:113-134`). | Same as `drawIndexed` |
| `drawIndirect(cmds, drawCount)` | `drawCount × {vertexCount, instanceCount, firstVertex, firstInstance}`, stride 16 (`FrontendRenderPass.java:462-490`) | Vertices |

Validity is checked only in the frontend:
- `firstInstance != 0` requires `features.nonZeroFirstInstance`.
- The interleaved forms require `multiDrawDirectInterleaved` and `drawCount <= limits.maxMultiDrawDirectInterleavedDrawCount`.
- The separate forms require `multiDrawDirectSeparate`.
- `drawIndirect` requires `features.drawIndirect`.

The VK backend throws for the separate forms (`VulkanRenderPass.java:240-243,267-270`; VK reports
`multiDrawDirectSeparate=false`, `VulkanDevice.java:109`). The GL backend throws for the interleaved forms
(`GlRenderPass.java:148-151,168-171`; GL reports `multiDrawDirectInterleaved=false`, `GlHeuristics.java:106`).

**Metal's indirect argument structs have exactly these layouts:**
`MTLDrawIndexedPrimitivesIndirectArguments {indexCount, instanceCount, indexStart, baseVertex, baseInstance}`
(20 bytes) and `MTLDrawPrimitivesIndirectArguments {vertexCount, instanceCount, vertexStart, baseInstance}`
(16 bytes). The game's indirect buffers can be passed to Metal unmodified.

---

## 5. `RenderPassBackend`: every method

Interface: `rp/backend/api/RenderPassBackend.java:13-49`.

### 5.1 `pushDebugGroup(Supplier<String>)` / `popDebugGroup()`

- **Frontend:** throws if the pass is closed, and keeps groups balanced (`FrontendRenderPass.java:70-92`).
- **VK:** `vkCmdBeginDebugUtilsLabelEXT` and the matching end, only when debug labels are enabled. The
  `Disabled` implementation is a no-op and **never calls `label.get()`** (`VulkanDebug.java:48-81,232-…`).
- **GL:** `glPushDebugGroup(label.get())` only if `KHR_debug` labels were requested
  (`GlDebugLabel.java:29-33,83-90`).
- **Volume:** `PreparedRenderType` pushes one group per batch and each terrain layer pushes one
  (`PreparedRenderType.java:38,55`, `ChunkSectionsToRender.java:99-101`). Groups are therefore high-volume;
  keep them free when debugging is off.

### 5.2 `setPipeline(BackendRenderPipeline)`

- **Frontend** (`FrontendRenderPass.java:103-128`):
  - The pipeline must be a `FrontendRenderPipeline`.
  - `colorTargetStates.size() == colorAttachments.size()`.
  - For each **non-null** attachment, `colorTargetState.format() == texture format`. A null attachment with a
    non-null target state is *not* rejected.
  - Then `backend.setPipeline(...)`, then **re-sends every uniform in the pass's name→value map** whose name
    exists in the new pipeline (`this.uniforms.forEach(this::setUniform)`), and resets `constantsPushed`.
- **VK** (`VulkanRenderPass.java:126-137`): always calls `vkCmdBindPipeline` (no redundancy check), clears its
  uniform array to the new size, and marks every descriptor dirty.
- **GL** (`GlRenderPass.java:57-79`): if the pipeline object changed, clears uniforms and marks them all
  dirty. **Always** sets `vertexBufferDirty` (the VAO is rebound) and `indexBufferDirty`. State is applied
  lazily at draw time in `setupDraw` (`GlCommandEncoder.java:531-619`). `GlRenderPipeline.bind()` pushes depth,
  cull, color mask, blend, polygon offset and polygon mode through the shadow-state cache in `GlStateManager`
  (`GlRenderPipeline.java:113-157`). The encoder resets `lastPipeline = null` at each pass start
  (`GlCommandEncoder.java:190`).

### 5.3 `pushConstants(ByteBuffer value)`

- **Frontend:** a pipeline must be bound, and `value.remaining() >= pipeline.pushConstantSize`
  (`FrontendRenderPass.java:174-186`).
- **VK:** `vkCmdPushConstants(layout, VK_SHADER_STAGE_ALL, offset 0, value)`, with size `value.remaining()`
  (`VulkanRenderPass.java:145-149`; range declared at `VulkanRenderPipeline.java:133-139`).
- **GL:** uploads a copy into transient GPU memory (aligned to `minUniformOffsetAlignment`, `USAGE_UNIFORM`)
  and binds it as the UBO block `_push_constants` at UBO binding 0 (`GlRenderPass.java:88-92`,
  `GlProgram.java:63-68`, `GlCommandEncoder.java:548-561`).
- **Game usage: none** in 26.3 (§9). Even so, implement it as `setVertexBytes` / `setFragmentBytes`, at most
  4 KB, copying exactly `pushConstantSize` bytes.

### 5.4 `setUniform(int index, @Nullable Object value)`

How the index is assigned: `PipelineBuilder` walks each shader's SPIR-V descriptors (vertex, then fragment) and
gives each distinct name the next binding number (`PipelineBuilder.java:276-300`). It rewrites `set=0`, binding
= that number, and passes `CreateInfo.uniforms` ordered by binding (`:287-291,404-409`). The frontend's
`uniformIndices` is that same map (`:458`). Only uniforms **actually referenced by a shader** get an index. A
uniform that is declared in the pipeline but unused is never forwarded (`FrontendRenderPass.java:166-170`).
GL names them `_uniform_00_%02d` (`GlProgram.java:70-72`).

**The value types the backend can receive:**

| `UniformDescription.type()` at `index` | Value class | How it is produced | VK binding | GL binding |
|---|---|---|---|---|
| `UNIFORM_BUFFER` | `GpuBufferSlice` | `setUniform(name, GpuBufferSlice)`: the frontend checks `offset % minUniformOffsetAlignment == 0` (`FrontendRenderPass.java:149-157`). `setUniform(name, GpuBuffer)` becomes `buffer.slice()` (whole buffer, offset 0) (`:144-147`). The per-draw `UniformUploader.setUniform` in `drawMultipleIndexed` goes through the same check. | `VkDescriptorBufferInfo{buffer, offset, range=length}`, `UNIFORM_BUFFER` (`VulkanRenderPass.java:295-306`) | `glBindBufferRange(GL_UNIFORM_BUFFER, blockBinding, buf, offset, length)` (`GlCommandEncoder.java:572-575`) |
| `TEXEL_BUFFER` | `GpuBufferSlice` | Must be the **whole buffer** with `USAGE_UNIFORM_TEXEL_BUFFER`. The format comes from the `UniformDescription.gpuFormat()` (`FrontendRenderPass.java:563-581`, strict only). In the game it is used only for `CloudFaces` as `R8_SINT` (`BindGroupLayouts.java:33,89`). | **A new `VkBufferView` is created on every descriptor push**, then queued for destruction (`VulkanRenderPass.java:319-359`) | `glTexBuffer` on a per-uniform texture (`GlCommandEncoder.java:576-581`, `Uniform.java:17-25`) |
| `COMBINED_IMAGE_SAMPLER` | `TextureViewAndSampler(view, sampler)` (`rp/util/TextureViewAndSampler.java:6`) | `setUniform(name, view, sampler)`: both null means `null`, mixed throws (`FrontendRenderPass.java:130-142`) | `VkDescriptorImageInfo{sampler, view.vkImageView, GENERAL}` (`VulkanRenderPass.java:307-318`) | Bind the texture (cube target if cubemap) and `glBindSampler`, and **mutate** `GL_TEXTURE_BASE_LEVEL` / `MAX_LEVEL` to the view's range (`GlCommandEncoder.java:582-600`). A null value is skipped. |
| any | `null` | `setUniform(name, null, null)` removes the entry and forwards null (`FrontendRenderPass.java:159-171`) | Stored. The next draw **throws "Missing uniform/sampler"** (`VulkanRenderPass.java:296-311`). | Stored; a null sampler is skipped |

**Behaviour to preserve:**
- **Dispatch on the pipeline's declared type, not on `instanceof`.** VK casts according to `uniform.type()`.
- **VK descriptor model:** one push-descriptor set layout (`VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT`;
  stage flags VERTEX|FRAGMENT = 17) (`VulkanRenderPipeline.java:85-112`). On the first draw after **any**
  `setUniform` or `setPipeline`, VK rewrites **all** descriptors of the pipeline with a single
  `vkCmdPushDescriptorSetKHR` (`VulkanRenderPass.java:280-384`). The `drawMultipleIndexed` terrain fallback
  changes one UBO per draw, so it re-pushes the whole set, textures included, for every chunk section.
- **Uniform persistence:**
  - Uniforms persist **across pipeline changes within a pass**: the frontend re-sends them on `setPipeline`.
  - They do **not** persist **across passes**: every new `FrontendRenderPass` starts with an empty map, which
    is why every pass calls `RenderSystem.bindDefaultUniforms` (`RenderSystem.java:367-387`). That call
    re-binds `Projection`, `Fog`, `Globals` and `Lighting`.

### 5.5 `enableScissor(x, y, w, h)` / `disableScissor()`

- **Frontend:** `w,h > 0`, and the rect must lie inside the render area (`FrontendRenderPass.java:188-204`).
  `disableScissor` has no checks (`:206-209`).
- **VK:** `vkCmdSetScissor`. "Disable" means the scissor is set to the **render area**, not the full target
  (`VulkanRenderPass.java:151-188`).
- **GL:** shadow `ScissorState`; disable restores the pass default, which is the render area (`enabled=true`),
  and the state is applied lazily in `setupDraw` (`GlRenderPass.java:94-104`,
  `GlCommandEncoder.java:610-618`).
- **Callers:** `PreparedRenderType.draw` enables the scissor if the render type has one and **never disables
  it**, so the scissor leaks to later draws in the same pass (`PreparedRenderType.java:41-43`). The GUI
  enables or disables per draw (`GuiRenderer.java:380-385`).

### 5.6 `setVertexBuffer(int slot, @Nullable GpuBufferSlice vb)` / `setIndexBuffer(GpuBuffer ib, IndexType t)`

- **Frontend:** `0 <= slot < 16`, not closed, `USAGE_VERTEX` (`FrontendRenderPass.java:211-227`). The index
  buffer is not validated until a strict-mode draw (`:229-233,514-527`).
- **Vertex buffer, VK:** `vkCmdBindVertexBuffers(slot, buffer, slice.offset)`. **Null is a no-op**, so the old
  binding stays (`VulkanRenderPass.java:190-215`). The stride, step rate and attribute formats come from the
  pipeline (`VulkanRenderPipeline.java:214-261`).
- **Vertex buffer, GL:** dirty tracking on (buffer, offset). At draw time the VAO is rebound with
  `glBindVertexBuffer(slot, buf, offset, stride)` (`ARB_vertex_attrib_binding`, `VertexArray.java:146-165`), or
  attribute pointers are re-specified with the offset baked in (`VertexArray.java:73-104`).
- **Index buffer:** the **whole buffer, offset 0**. There is no slice parameter, and `firstIndex` carries the
  offset. VK: `vkCmdBindIndexBuffer(buf, 0, UINT16|UINT32)` (`VulkanRenderPass.java:217-224`). GL: binds
  `ELEMENT_ARRAY_BUFFER`; the index type defaults to INT if it was never set (`GlRenderPass.java:28,136-141`).
- **Instance-rate data:** `VertexFormat.stepRate > 0` means instance rate with divisor `stepRate`
  (`VulkanRenderPipeline.java:222-234`). The game uses this only for `CHUNK_DATA_INSTANCED`, stepRate 1, slot 1
  (`DefaultVertexFormat.java:33-36`, `RenderPipelines.java:86,424`), together with
  `baseInstance = section index` in the MDI path.

### 5.7 Draws

- **VK:** every draw first calls `pushDescriptors()` if anything is dirty. Then it issues
  `vkCmdDrawIndexed`, `vkCmdDraw`, `vkCmdDrawMultiIndexedEXT` / `vkCmdDrawMultiEXT` (pointer =
  `memAddress(IntBuffer)` at its position), or `vkCmdDrawIndexedIndirect` / `vkCmdDrawIndirect` with the
  buffer, `slice.offset`, `drawCount` and stride 20 or 16 (`VulkanRenderPass.java:226-278`).
- **GL:** `setupDraw` flushes the VAO, index buffer, program/state, push-constant UBO, dirty uniforms and
  scissor (`GlCommandEncoder.java:531-619`). It then calls:
  - `glDrawElementsInstancedBaseVertex[BaseInstance]` (base-instance variant only if `firstInstance > 0`),
  - `glDrawArraysInstanced[BaseInstance]`,
  - `glMultiDrawElementsBaseVertex` / `glMultiDrawArrays`,
  - `glDraw*Indirect` / `glMultiDraw*Indirect` (`:454-529`).
- **Frontend-only `drawMultipleIndexed(draws, defaultIB, defaultType, dynamicUniforms, arg)`**
  (`FrontendRenderPass.java:331-398`). Per draw: run the uniform-uploader lambda (which calls
  `backend.setUniform`), `setVertexBuffer(slot, vb.slice())`, `setIndexBuffer` **only when it changed**, then
  `backend.drawIndexed(indexCount, 1, firstIndex, baseVertex, 0)`. The backend sees ordinary calls.
- **Topology mapping** (`VulkanConst.java:220-231`, `GlConst.java:188-199`):

| Topology | VK | GL | Metal |
|---|---|---|---|
| `QUADS`, `LINES` | `TRIANGLE_LIST` (quads and lines are drawn through a generated quad→triangle index buffer, `PrimitiveTopology.indexCount`) | `GL_TRIANGLES` | `MTLPrimitiveTypeTriangle` |
| `TRIANGLES` | `TRIANGLE_LIST` | `GL_TRIANGLES` | `Triangle` |
| `TRIANGLE_STRIP` | strip | strip | `TriangleStrip` (Metal always applies primitive restart on 0xFFFF / 0xFFFFFFFF for strips) |
| `TRIANGLE_FAN` | `TRIANGLE_FAN` | `GL_TRIANGLE_FAN` | **No Metal equivalent. Must be emulated** (§10.7). Used by the sky disc, sunrise and debug fans (`RenderPipelines.java:1040,1066,972`, drawn with `draw(10,1,0,0)` at `SkyRenderer.java:167,181`). |
| `DEBUG_LINES` / `DEBUG_LINE_STRIP` | line list / strip | `GL_LINES` / `GL_LINE_STRIP` | `Line` / `LineStrip` (1-pixel lines only) |
| `POINTS` | point list | `GL_POINTS` | `Point`. The vertex function **must** write `[[point_size]]` (`RenderPipelines.java:271`). |

### 5.8 `writeTimestamp(pool, index)` inside a pass

VK: `vkResetQueryPool` plus `vkCmdWriteTimestamp2` inside rendering (`VulkanRenderPass.java:386-391`).
GL: `glQueryCounter`. The game never calls it; only the encoder-level version is used (§3.8).

---

## 6. Synchronization and lifetime model (VK reference)

| Mechanism | VK | GL | What Metal needs |
|---|---|---|---|
| Inter-operation hazards | Global full barrier after every pass, clear and copy, plus after the init CB (`VulkanCommandEncoder.java:190`) and after the transient-copy CB | Implicit (GL driver) | Automatic hazard tracking, or an explicit fence/barrier chain (§10.4) |
| Frame completion | Timeline semaphore value = submit index | `glFenceSync`, 2 slots | `MTLSharedEvent` signaled with the submit index, or command buffer completion |
| CPU frame pacing | Wait for N−1 after submitting N (5 s timeout, then exception) | Wait for N−1 (infinite) | The same. Keep 2 in flight unless every ring is resized. |
| Deferred destruction | `DestructionQueue(2)`: an object closed during submit S is destroyed at the end of `submit()` for S+1, after waiting for S (`DestructionQueue.java:30-47`, `VulkanCommandEncoder.java:276`). Used for buffers, textures, views, samplers, pipelines, query pools, buffer views, transient blocks **and readback callbacks**. | Immediate `glDelete*` (the driver keeps them alive) | **Required** if command buffers do not retain resources (`commandBufferWithUnretainedReferences` / Metal 4). Use the same two-slot rotation. |
| Object-init commands | Separate init CB, lazily created, flushed **before** the main CB of the same segment, with a trailing barrier (`:171-210`). Used for buffer-with-data staging copies (`VulkanDevice.java:233`) and image layout init (`VulkanGpuTexture.java:76`). Recording order is reordered, which is safe because new objects cannot be referenced earlier. | N/A | With shared storage, initial data is a `memcpy` at creation. For private textures, use a blit in an "init" command buffer enqueued before the frame CB, or at the front of the frame's first blit encoder. |

---

## 7. What the frontend validates and what the backend must still do

| Concern | Frontend (release build) | Frontend (`STRICT_VALIDATION`, IDE only) | Backend must |
|---|---|---|---|
| Pass nesting and encoder ops inside a pass | ✔ | ✔ | — |
| Attachment usage, size and layer count | ✔ | ✔ | Assume the depth format is D32_FLOAT, or key pipeline variants on the format |
| Color format vs pipeline | ✔ (non-null attachments only) | ✔ | Handle a null attachment against a non-null target state (make it Invalid in the PSO variant) |
| Pipeline has depth state but the pass has no depth attachment | ✘ | ✔ | Make it never crash: compile or choose a PSO with depth format Invalid, or throw a clear error |
| Uniform set / null / type / closed / usage flags | ✘ (only the UBO offset alignment) | ✔ | Throw on a missing UBO or texture, as VK does (§5.4), or bind dummies |
| Vertex buffer present for every used slot | ✘ | ✔ | Guard (GL throws in `VertexArray.bind`) |
| Index buffer present and `USAGE_INDEX` | ✘ | ✔ | Guard |
| Push constants pushed when `pushConstantSize > 0` | ✘ | ✔ | — |
| Scissor within the render area | ✔ | ✔ | — |
| Indirect: usage, length, 4-byte offset, `drawCount` vs features | ✔ | ✔ | Honour `maxDrawIndirectDrawCount` (the game splits by it, `ChunkSectionsToRender.java:147-155`) |
| Timestamp index | ✘ (off-by-one) | ✘ | Bounds-check |
| Copy bounds, usage and mip | ✔ (except the 5-argument `copyTextureToBuffer`) | ✔ | Metal alignment rules (§10.8) |

---

## 8. Device features and limits that steer the hot path

From `rp/api/device/DeviceFeatures.java:3-12`, `DeviceLimits.java:5-13`, `HintsAndWorkarounds.java:3-5`.
VK values are at `VulkanDevice.java:84-118`; GL values at `GlHeuristics.java:79-119`.

| Field | VK value | GL (macOS GL 4.1) | Effect | Recommended Metal value |
|---|---|---|---|---|
| `nonZeroFirstInstance` | true | false (no `ARB_base_instance`) | **Gates the MDI terrain path** (`LevelRenderer.java:178-181`) | **true** |
| `drawIndirect` / `multiDrawIndirect` | true / true | `ARB_draw_indirect` yes / no | MDI path; `drawCount > 1` | true / true (loop or ICB) |
| `maxDrawIndirectDrawCount` | `maxDrawIndirectCount` | 1 (only `draw_indirect`) | The game splits indirect calls by this value | Large, for example `Integer.MAX_VALUE`, so the game never splits |
| `multiDrawDirectInterleaved` / `Separate` | `VK_EXT_multi_draw` / false | false / true | Unused by the game | true / true (CPU loop), or false |
| `shaderDrawParameters` | true | `ARB_shader_draw_parameters` | Shader macro `RENDERPEARL_INSTANCE_INDEX_INCLUDES_BASE_INSTANCE` (`GlslCompiler.java:89-91`) | true. MSL `[[instance_id]]` includes the base instance; confirm SPIRV-Cross semantics. |
| `persistentMapping` | true | `ARB_buffer_storage` | Transient memory and staging strategy | true |
| `minUniformOffsetAlignment` | device | `GL_UNIFORM_BUFFER_OFFSET_ALIGNMENT` | UBO ring block size: `DynamicGpuDataStorageMapped` rounds each block up to it (`:28`). For example a 16-byte ChunkSection block becomes 256 bytes at alignment 256. | Smallest legal value for the Apple GPU family. 256 is always safe; a smaller value saves cache and bandwidth. Verify the constant-buffer offset rule. |
| `maxColorAttachments` | device | `GL_MAX_DRAW_BUFFERS` | Pass validation | 8 |
| `isExplicitDepthRequired` hint | `isAppleSiliconMac` | `isAppleSiliconMac` | Shader macro `RENDERPEARL_EXPLICIT_DEPTH_INVARIANCE` (`GlslCompiler.java:85-87`) | true |
| `multiDrawIndirectHasKnownIssues` | false | Intel non-Arc | Disables MDI | false |
| `writeToBufferIsSlow` | false | GL-on-DX12 | Staging strategy (`StagingBuffer.java:16-20`) | false (§10.8) |

---

## 9. Game-side usage census and call-count estimate

### 9.1 Which API entry points the game actually uses (26.3)

From grepping `net/` and `com/mojang/blaze3d/`:
- `drawIndexed`: 11 sites (`PreparedRenderType.java:54`, `GuiRenderer.java:400`, sky, clouds, weather,
  particles, …).
- `draw`: about 20 sites (full-screen triangles, sky fans, the lightmap, post passes, the atlas animation blit).
- `drawIndexedIndirect`: 1 site, terrain (`ChunkSectionsToRender.java:152`).
- `drawMultipleIndexed`: 2 sites, terrain fallback and world border (`ChunkSectionsToRender.java:188`,
  `WorldBorderRenderer.java:225`).
- **Never called:** `multiDrawIndexed` (both forms), `multiDraw` (both forms), `drawIndirect`,
  `pushConstants`, pass-level `writeTimestamp`.
- Encoder level:
  - `clearColorAndDepthTextures` (6 sites), `clearDepthTexture` (4), `clearColorTexture` (2);
  - `writeToBuffer` (9), `copyToBuffer` (3), `writeToTexture` (8), `copyBufferToTexture` (3);
  - `copyTextureToBuffer` (3: screenshot, Tracy, `TextureUtil`), `copyTextureToTexture` (2);
  - `createFence` (4), `writeTimestamp` (`TimerQuery` only).

### 9.2 Anatomy of a typical in-world frame (classic transparency; `improvedTransparency` defaults to false, `Options.java:224-225`)

| # | Operation | Attachments / target | Source |
|---|---|---|---|
| 1 | `clearColorAndDepthTextures(main)` | main color and depth | `GameRenderer.java:477-481` |
| 2 | `writeToBuffer(Globals)` | — | `GameRenderer.java:482-491`, `GlobalSettingsUniform.java:41` |
| 3 | Lightmap pass (`draw(3)`) | lightmap texture | `Lightmap.java:80-84` |
| 4 | Frame-graph "clear": `clearColorAndDepthTextures(main)` **again**, with the fog color | main | `LevelRenderer.java:247-257` |
| 5 | "Sky" pass (fans, quads) | main color and depth, LOAD | `SkyRenderer.java:133-136` |
| 6 | "Main" pass: terrain solid and cutout, entities, block entities, particles, classic translucency, clouds, weather, world border | main color and depth, LOAD | `LevelRenderer.java:443-456,690-706` |
| 7 | Optional: outline, see-through, always-on-top, entity-outline blit, post-effect passes | various | `LevelRenderer.java:476-512,709-719`, `PostPass.java:128` |
| 8 | `clearDepthTexture(depth)`, then the "Item in hand" pass, then the optional "Screen effects" pass | main color and depth | `GameRenderer.java:690-710` |
| 9 | `clearDepthTexture(main)`, then the GUI pass(es) ("GUI before/after blur"; another depth clear before "after blur") | main color and depth | `GameRenderer.java:507`, `GuiRenderer.java:196-221` |
| 10 | Occasionally: atlas animation passes, one per mip level (only when an animated sprite needs to draw); `GuiItemAtlas` and picture-in-picture passes | atlas / UI textures | `TextureAtlas.java:248-260`, `GuiItemAtlas.java:128`, `PictureInPictureRenderer.java:57` |
| 11 | `blitFromTexture`, then `submit`, then `present` | drawable | `Minecraft.java:1322-1340` |

Result: about **6–10 render passes**, **4 full-target clears** (2 color+depth on main, 2 or more depth-only)
and 5–20 small `writeToBuffer` calls per frame. The main color and depth targets are loaded and stored across
roughly 4–6 pass boundaries.

### 9.3 Estimated backend calls per frame

This is an estimate, not a measurement. The JFR captures in `results/raw/prof-*.jfr` were recorded with a
stack depth of 5 and cannot attribute calls; adding a counting `CommandEncoderBackend` decorator would.

| Source | Backend calls per unit | Typical units / frame (RD 12) | Calls |
|---|---|---|---|
| `PreparedRenderType` batch (entities, particles, …) | push + setPipeline + (re-sent uniforms ≈4–8) + 4 default UBOs + DynamicTransforms + VB + 1–3 textures + IB + draw + pop + optional scissor ≈ **15–22** | 50–300 | 1–6 k |
| GUI draw | setPipeline (+re-sends) + VB + scissor + 1–3 textures + IB + draw ≈ **8–12** | 50–400 | 0.5–4 k |
| Terrain, MDI path (VK / Metal) | per layer: setPipeline + about 3 re-sends + VB(1); per draw group: VB(0) + IB + indirect ≈ **3** | 3 layers × 5–50 groups | ≈ 50–500 backend calls. These **expand to 2–6 k GPU draws**, one per visible section-layer. |
| Terrain, separate path (GL on macOS) | per section-layer: setUniform(ChunkSection) + VB + draw (+ IB on change) ≈ **3–4** | 2–6 k | 6–24 k (each with a full descriptor re-push in VK) |
| Fixed (passes, clears, writes, full-screen draws) | — | — | ≈ 100–200 |

So a Metal backend on the MDI path sees on the order of **2–10 k backend calls per frame** plus **2–6 k
expanded terrain draws**. At a 1000 FPS target (a 1 ms total CPU frame), each backend call must cost tens of
nanoseconds. That settles the Metal architecture in §10.1.

---

## 10. Metal mapping

### 10.1 Architecture: a native command stream, not one JNI/FFM call per API call

- **No bindings exist for Metal.** LWJGL 3.4.3 has no Metal bindings. It ships only
  `org.lwjgl.system.macosx.ObjCRuntime` (`objc_getClass`, `sel_registerName`) and
  `org.lwjgl.sdl.SDLMetal` (`SDL_Metal_CreateView` / `GetLayer`) (checked in
  `mc/libraries/.../lwjgl-3.4.3.jar` and `lwjgl-sdl-3.4.3.jar`). The JFR samples also show LWJGL 3.4 native
  calls going through FFM `DowncallStub`s.
- **Recommendation: a small native library** (Objective-C++, or C++ with metal-cpp) plus a **Java-side command
  stream**:
  - The Java `MetalRenderPass` and `MetalCommandEncoder` append fixed-size opcodes into an off-heap buffer.
    Examples: `SET_PIPELINE id`, `SET_UBO slot buf off`, `SET_TEX slot tex samp`, `DRAW_INDEXED …`.
    Resources are referred to by `long` native handles or by small integer ids.
  - **Flush with one downcall** at `submitRenderPass`, before any encoder-level operation, and at `submit`.
  - The native side keeps shadow state, removes redundant binds, and encodes Metal.
- **Why this design:**
  - It turns about 10 k downcalls per frame into about 10–20.
  - It gives **look-ahead**, which the optimizations below need: render-pass merging, clear folding, store
    actions, and building ICBs before the render encoder starts (§10.6).
  - Because recording is deferred, all data must be **copied** when the backend method is called. That covers
    `pushConstants`, the multi-draw `IntBuffer`s and `PointerBuffer`s, `writeToBuffer`/`writeToTexture` source
    bytes, and label strings when debugging. Callers may free their buffers right after the call (§3.5).

### 10.2 Command buffers, submit, fences

| RenderPearl | Metal 3 | Metal 4 (macOS 26+; verify selectors against the macOS 27 SDK) |
|---|---|---|
| Recording slot (VK command pool ×2) | One `MTLCommandBuffer` per submit from `[queue commandBufferWithUnretainedReferences]`, or a descriptor with `retainedReferences = NO` and `errorOptions = EncoderExecutionStatus` | `MTL4CommandAllocator` ×2, one per in-flight slot, `reset` after its submit completes. `MTL4CommandBuffer` via `beginCommandBufferWithAllocator:`. |
| `submit()` | `[cb encodeSignalEvent:frameEvent value:S]`, `[cb commit]`, `S++`, then wait for `frameEvent ≥ S−2+1`, i.e. the previous submit (§0.4). Then rotate the destroy queue, transient memory and pending callbacks. | `[queue commit:&cb count:1]`, then `[queue signalEvent:frameEvent value:S]`. Same wait. |
| Timeline semaphore | `MTLSharedEvent frameEvent` (`signaledValue`) | Same |
| `createFence()` | `{S = currentSubmitIndex}` | Same |
| `awaitCompletion(t)` | `signaledValue ≥ S` → true. Else if `S == current`: `t == 0` → false, otherwise throw (keep the VK/GL semantics). Else `t == 0` → false. Else `[frameEvent waitUntilSignaledValue:S timeoutMS:(t<0 ? UINT64_MAX : ceil(t/1e6))]` (macOS 14+). **The timeout is in ns in RenderPearl and in ms in Metal.** | Same |
| Device loss / timeout | Check `cb.status == MTLCommandBufferStatusError` and `cb.error.code` (`…DeviceRemoved`, `Timeout`, `PageFault`). Throw `GpuDeviceLossException` (VK does this at `VulkanUtils.java:91-93`). Keep a 5 s wait cap that throws `IllegalStateException`, like `VulkanCommandEncoder.java:270-273`. | Commit feedback handler / `MTL4CommitFeedback.error` |
| Readback callbacks (`copyTextureToBuffer`) | Append `(S, Runnable)`; run on the render thread in `submit()` after the wait shows S completed, like VK's destroy-queue timing. **Never run them from `addCompletedHandler`**: that is a Metal thread, and the callbacks touch game state. | Same |
| Present | In `blitFromTexture`: obtain the drawable (late acquire is preferable), encode a render pass that samples the main color texture **with V flipped** into `drawable.texture` (a blit cannot flip, and the drawable is `framebufferOnly`), then `[cb presentDrawable:drawable]`. `GpuSurface.present()` then only does bookkeeping. | `[queue waitForDrawable:d]` before encoding, `[queue signalDrawable:d]` after commit, then `[d present]` |

Command buffer count: **one per frame**, plus an optional "init" command buffer that is enqueued *before* it
for private-storage object initialization. Metal executes command buffers in **enqueue order**, so creating the
init CB later and committing it first reproduces VK's init-CB hoisting.

### 10.3 Render passes

- **`createRenderPass` maps to `MTLRenderPassDescriptor`:**
  - `colorAttachments[i].texture = view.texture`'s `MTLTexture`, `.level = view.baseMipLevel()`, `.slice = 0`.
    Use the **parent texture plus `level`**, not a texture view, for attachments.
  - `loadAction = clear.present ? Clear : Load`, `clearColor = MTLClearColorMake(x, y, z, w)`.
  - `storeAction = Store`, or better **`MTLStoreActionUnknown`, decided at encoder end** with
    `setColorStoreAction:atIndex:` / `setDepthStoreAction:` (§10.9).
  - A null attachment slot is simply left unset.
  - `depthAttachment` likewise, with `clearDepth = (float)value`.
- **Render area:** Metal has no render-area concept for load actions; `loadAction = Clear` clears the *whole*
  attachment level. That is correct only when `renderArea.fillsTexture(view)` (`RenderPass.java:94-97`). For a
  partial render area with a clear: use Load, then draw a scissored full-screen "clear" triangle with a small
  clear pipeline cached per (color formats, has depth); it writes the color and `[[depth(any)]]`. Initial
  scissor = render area (`setScissorRect`) when it is not the full target. **No game code uses a partial render
  area** (§9: no `withRenderArea` or `RenderArea(` calls outside the API defaults), so this path matters only
  for correctness.
- **Pipeline variants:** `MTLRenderPipelineState` must match the pass's color pixel formats, the depth format
  (`Depth32Float` or Invalid) and the sample count. Compile two variants per `RenderPipeline`, "with depth" and
  "without depth" (only when `depthStencilState == null`, as in VK), keyed by the pass's attachment format
  tuple. Handle a null color attachment matched against a non-null `ColorTargetState` with a variant where that
  slot is `MTLPixelFormatInvalid`.
- **`submitRenderPass`:** `[enc endEncoding]`, unless deferred for merging (§10.9). No barrier is needed with
  tracked resources; for untracked resources see §10.4.
- **Pass-level debug groups:** `[enc pushDebugGroup:]` / `popDebugGroup`, and `enc.label = label.get()`, only
  when debugging is enabled. **Never evaluate the `Supplier` otherwise.**

### 10.4 Hazards and barriers

Option A (simplest, and correct from the first frame): resources use the default **tracked** hazard mode.
Metal inserts inter-encoder dependencies automatically, which is equivalent to VK's global barrier but only
where needed. The cost is CPU time per bind and per encoder.

Option B (fastest CPU): `MTLResourceHazardTrackingModeUntracked` on all buffers, textures and heaps, plus
**one `MTLFence` chain**. Every encoder calls `waitForFence:prev beforeStages:Vertex` (or `waitForFence:` on
blit and compute encoders) at the start, and `updateFence:next afterStages:Fragment` at the end. This
reproduces VK's "everything is serialized" exactly. Relax only with proven dependency analysis: for example,
pass N+1 neither samples nor attaches anything that pass N writes.

Metal 4 has no automatic tracking. Use `barrierAfterQueueStages:beforeStages:visibilityOptions:` at the start
of each encoder (the equivalent of the VK global barrier), and add all resources to an `MTLResidencySet` that
is attached to the queue.

Within a render pass, no barriers are ever needed: the API has no storage writes, and attachment-only writes
are ordered by the tile pipeline.

### 10.5 Binding model, redundant-state elimination, slot assignment

**Per-pipeline translation table**, built at pipeline creation from `CreateInfo.uniforms` plus SPIR-V
reflection of which stage uses which binding:
`index → {kind (UBO / TEX+SAMPLER / TEXBUF), vsSlot or -1, fsSlot or -1}`. Bind only in the stages that use
the binding. VK binds to both stages (stage flags 17), which costs nothing in VK, but in Metal every bind is a
call.

**Slot assignment.** Metal 3 has 31 buffer slots, up to 128 texture slots and **16 sampler slots** per stage.
- Vertex buffers occupy buffer slots `30 - slot` (MoltenVK-style), so they never collide with UBOs. Only
  slots 0 and 1 are used in practice.
- Push constants get a fixed buffer slot, for example 29, via `setVertexBytes`/`setFragmentBytes`.
- **UBOs and textures get name-stable slots.** Give the hot globals fixed slots in every pipeline by remapping
  `set=0, binding=i` through SPIRV-Cross `msl_add_resource_binding` at compile time:
  - buffers: `Projection` 0, `Fog` 1, `Globals` 2, `Lighting` 3, `DynamicTransforms` 4, `ChunkSection` and
    `TerrainUniform` 5–6;
  - textures: `Sampler0..2` → texture and sampler 0–2.

  Assign everything else per pipeline from the remaining range. The game's name universe is small: about 11
  UBO names and about 14 sampler names, plus post-effect `*Sampler`, `SamplerInfo` and custom names
  (`BindGroupLayouts.java`, `PostPass.java:133-142`).

  Payoff: a `setPipeline` no longer forces re-binding of `Projection`, `Fog`, `Globals`, `Lighting`,
  `DynamicTransforms` or `Sampler0..2`. In entity and GUI rendering, pipelines change almost every batch while
  these bindings stay the same.

**Shadow state** (native side, per encoder, reset at encoder start):
- PSO and depth-stencil-state ids;
- cull mode, winding, depth bias and fill mode (all derived from the pipeline; set only on change);
- scissor;
- per stage and slot, the buffer `(MTLBuffer*, offset)`. If only the offset changed, use
  `setVertexBufferOffset:atIndex:` / `setFragmentBufferOffset:atIndex:`. This is the ChunkSection-per-draw
  case, and it is very cheap.
- per stage and slot, the `(MTLTexture*, MTLSamplerState*)` pair.

The Java side should also drop obviously redundant `setUniform` calls. Compare `GpuBufferSlice` by
`(buffer, offset, length)` (records are re-created per call) and `TextureViewAndSampler` by
`(view, sampler)` identity. This matters because `FrontendRenderPass.setPipeline` re-forwards the entire
uniform map every time (`FrontendRenderPass.java:125`).

**Mapping by uniform kind:**

| Kind | Metal 3 | Metal 4 (`MTL4ArgumentTable` per stage) |
|---|---|---|
| UBO slice | `setVertexBuffer:buf offset:off atIndex:s` (and fragment) | `[table setAddress:buf.gpuAddress + off atIndex:s]` |
| Texture + sampler | `setFragmentTexture:view.mtlTexture atIndex:t` + `setFragmentSamplerState:sampler atIndex:t` | `setTexture:tex.gpuResourceID` / `setSamplerState:samp.gpuResourceID` (samplers need `supportArgumentBuffers = YES`) |
| Texel buffer | Cache one `MTLTextureTypeTextureBuffer` texture per `(GpuBuffer, GpuFormat)`, created once with `[buf newTextureWithDescriptor:offset:0 bytesPerRow:]`. Do **not** re-create it per draw as VK does (`VulkanRenderPass.java:319-359`). | Same texture, by resource ID |
| Vertex buffer | `setVertexBuffer:buf offset:off atIndex:30-slot` | `setAddress:… atIndex:` in the vertex argument table (Metal 4 has no `setVertexBuffer`) |
| Push constants | `setVertexBytes`/`setFragmentBytes` (≤ 4 KB, copied at encode time) | Write into a per-frame shared ring and `setAddress` |

Texture views: VK uses a `VkImageView` with `(baseMip, mipLevels)` and a cube view type for cubemaps
(`VulkanGpuTextureView.java:19-28`). GL mutates `BASE_LEVEL`/`MAX_LEVEL` on every bind
(`GlCommandEncoder.java:598-599`). Metal should create the `newTextureViewWithPixelFormat:textureType:levels:slices:`
**once, at `createTextureView`** (only when the range is not the full texture), and bind it directly.

Samplers: one immutable `MTLSamplerState` per `GpuSampler`.

### 10.6 Draws, multi-draw and indirect

| RenderPearl | Metal call |
|---|---|
| `drawIndexed(n, inst, first, vo, fi)` | `drawIndexedPrimitives:type indexCount:n indexType:(SHORT→UInt16, INT→UInt32) indexBuffer:ib indexBufferOffset:first*bytes instanceCount:inst baseVertex:vo baseInstance:fi`. `indexBufferOffset` must be a multiple of the index size; older docs state a 4-byte requirement on macOS. Verify with API validation, because odd `firstIndex` with 16-bit indices is possible. |
| `draw(n, inst, fv, fi)` | `drawPrimitives:type vertexStart:fv vertexCount:n instanceCount:inst baseInstance:fi` (fans: §10.7) |
| `multiDrawIndexed` interleaved | CPU loop over `{firstIndex, indexCount, vertexOffset}` → `drawIndexedPrimitives` (copy the array into the command stream) |
| `multiDrawIndexed` separate | Loop: `indexBufferOffset = firstIndexOffsets[i]` (**bytes**), `indexCount[i]`, `baseVertex = vertexOffsets[i]`, instance 1 / base 0 |
| `multiDraw` interleaved / separate | Loop of `drawPrimitives` |
| `drawIndirect(slice, n)` | `n ×` `drawPrimitives:type indirectBuffer:buf indirectBufferOffset:off + 16*i` |
| `drawIndexedIndirect(slice, n)` | See the options below. Metal's `drawIndexedPrimitives:indexType:indexBuffer:indexBufferOffset:0 indirectBuffer:indirectBufferOffset:` draws exactly one command and reads `indexStart` in indices from the 20-byte record. |

**Options for `drawIndexedIndirect`, the terrain MDI path:**
1. **GPU indirect loop.** `n` calls of the indirect draw above, with offset `off + 20*i`. Simplest. CPU cost
   is O(n) encoder calls, about 50–150 ns each, which is about 0.3–0.9 ms for 5 k sections.
2. **CPU decode.** The args were written on the CPU into a mapped `MTLStorageModeShared` ring
   (`DynamicGpuDataStorageMapped`), so the native side can read the 20-byte records from `buffer.contents` and
   issue direct draws. Same O(n) cost, but no GPU indirection and no dependency on the arguments being
   GPU-visible.
3. **Indirect command buffer** (`MTLIndirectCommandBuffer`, `commandTypes = DrawIndexed`,
   `inheritPipelineState = YES`, `inheritBuffers = YES`). Fill it on the CPU in native code
   (`[icb indirectRenderCommandAtIndex:i] drawIndexedPrimitives:…`), or better **on the GPU**:
   - Encode a compute kernel that reads the 20-byte records and calls `render_command::draw_indexed_primitives`.
     Pass the index pointer as `indexBase + firstIndex`, plus `indexCount`, `instanceCount`, `baseVertex` and
     `baseInstance`.
   - Then, inside the pass, per draw group: bind VB0 and VB1 (they are inherited), then
     `[enc executeCommandsInBuffer:icb withRange:NSMakeRange(groupStart, n)]`.
   - Index buffers referenced only through the ICB need `useResource:usage:stages:` (Metal 3) or residency-set
     membership.
   - The compute pass must be encoded **before** the render encoder of that pass. Only the deferred command
     stream in §10.1 allows that: at pass flush, emit "compute ICB build" first, then the render encoder.

   With option 3, CPU cost is O(groups) instead of O(sections). This is the path toward 1000 FPS. Keep 2 ICB
   sets, one per in-flight frame, and size them by the frame's total `drawCount`.
4. Report `maxDrawIndirectDrawCount` as large, so the game issues one call per draw group
   (`ChunkSectionsToRender.java:147-155`).

**Instancing.** For `stepRate > 0` attributes, use `MTLVertexStepFunctionPerInstance` with `stepRate`; Metal
applies `baseInstance` to per-instance fetches. VK fetch index = `firstInstance + (instance − firstInstance) / divisor`.
The game only uses stepRate 1, where the two agree.

### 10.7 Triangle-fan emulation (Metal has no fans)

- **`draw(n, …)` with a fan pipeline:** keep a static, private "fan→list" `UInt16`/`UInt32` index buffer
  `[0,1,2, 0,2,3, …]`. Draw with `drawIndexedPrimitives:Triangle indexCount:3*(n-2) … baseVertex:firstVertex`.
- **`drawIndexed` with a fan pipeline:** the game feeds fans an identity "sequential" index buffer
  (`RenderSystem.java:300-307`). If the bound index buffer is the shared sequential buffer, use the same trick
  with `baseVertex += firstIndex`. Otherwise (generic), read the indices from shared storage and build a
  transient list buffer.
- Make the topology a property of the Metal pipeline wrapper, so that draws resolve it without a lookup.

### 10.8 Clears, copies and uploads (blit encoders)

Keep **one open `MTLBlitCommandEncoder`** for consecutive encoder-level copies, and close it when a render pass
starts or at submit. Consecutive copies then batch into one encoder; VK instead issues one barrier per copy.

| RenderPearl | Metal |
|---|---|
| `clearColorTexture` / `clearDepthTexture` / `clearColorAndDepthTextures` (all mips) | No blit "fill texture" exists. For each mip, an empty render pass with `loadAction = Clear`, `storeAction = Store`. **Better: defer** (§10.9). |
| Region clear `(x, y, w, h, mip)` | Render pass on `level = mip` with Load ops, `setScissorRect(region)`, and a full-screen clear-pipeline draw writing color and depth. Cache the pipeline per color format. |
| `writeToBuffer(dst, data)` | **Fast path:** if `dst.buffer` is shared storage and has not been referenced by the current command buffer or any incomplete submit, `memcpy` into `contents + offset`. Track a "last used submit index" plus an "used in current CB" flag per buffer, set whenever it is bound. **Otherwise:** copy into a staging ring (shared, 16-byte aligned), then `[blit copyFromBuffer:… toBuffer:dst destinationOffset:… size:]`. Never `memcpy` blindly: `ProjectionMatrixBuffer` and others overwrite in place mid-frame and rely on in-stream ordering (§3.5). |
| `copyToBuffer(src, dst)` | `copyFromBuffer:sourceOffset:toBuffer:destinationOffset:size:`. Older macOS docs require offsets and size to be multiples of 4; fall back to a tiny compute copy or a CPU copy when the hazard checks allow. |
| `writeToTexture` | Staging copy (alignment ≥ max(4, blockSize)), then `copyFromBuffer:… sourceBytesPerRow:w*bs sourceBytesPerImage:w*h*bs sourceSize:{w,h,1} toTexture:… destinationSlice:layer destinationLevel:mip destinationOrigin:{x,y,0}`. Do **not** use `replaceRegion:` on textures the GPU may still be reading (animated atlas, font pages); it is not ordered with the command stream. |
| `copyBufferToTexture` | Same call with `sourceOffset = src.offset + (sx + sy*sW)*bs`, `bytesPerRow = sW*bs`, `bytesPerImage = sW*sH*bs`. The offset must be a multiple of the pixel size. |
| `copyTextureToBuffer` | `copyFromTexture:sourceSlice:0 sourceLevel:mip sourceOrigin:{x,y,0} sourceSize:{w,h,1} toBuffer:dst destinationOffset:offset destinationBytesPerRow:w*bs destinationBytesPerImage:w*h*bs`. Depth32Float is supported; combined depth/stencil formats need `MTLBlitOption`. The destination should be shared (it is `USAGE_MAP_READ`). Callback per §10.2. |
| `copyTextureToTexture` | `copyFromTexture:sourceSlice:0 sourceLevel:mip sourceOrigin:… sourceSize:… toTexture:dst destinationSlice:0 destinationLevel:mip destinationOrigin:…`. The formats must be copy-compatible; the game copies D32→D32 and RGBA8→RGBA8. |
| Formats without a Metal equivalent (RGB8/RGB16 families) | Must be expanded to RGBA on upload. Coordinate with the texture subsystem, since copy byte math changes: `blockSize` is 3 in RenderPearl but 4 in Metal. |

### 10.9 Load/store-action optimizations (TBDR; the main GPU-side win)

The main target at fullscreen native (3600×2260, `results/BASELINE.md`) is 32.5 MB of RGBA8 color plus
32.5 MB of D32 depth. Every pass boundary on main costs a load and a store of both, before Apple's lossless
compression. Steps:

1. **Clear folding.** Record each out-of-pass clear as "pending clear (value)" on the texture instead of
   encoding it:
   - If the next GPU use is a render pass attaching that texture or mip with **Load**, convert it to
     `loadAction = Clear`.
   - Any other use (sampling, copy, readback, present) first flushes the clear as a minimal clear pass.
   - A second clear overrides the first. Frame steps 1 and 4 (§9.2) collapse into one clear folded into the
     Sky pass, and the depth clears in steps 8 and 9 fold into the hand and GUI passes.
2. **Pass merging.** If `submitRenderPass` is followed directly by `createRenderPass` with the **same
   attachments** (same textures and levels; all Load; render area = full target), with no intervening
   encoder-level operation, and the new pass does not sample any of those attachments, keep the same
   `MTLRenderCommandEncoder`.
   - Reset the scissor to the render area and clear the shadow bindings, so they are re-sent.
   - Candidates: Sky→Main, Item in hand→Screen effects, GUI passes.
   - The intervening small `writeToBuffer` calls (lighting, projection) block this. Use the fast `memcpy`
     path when hazard-free, or move upload-only writes into a "pre" blit encoder at the start of the frame's
     command buffer when the destination was not used earlier in the frame. VK already runs all transient
     uploads first.
3. **Store actions.** Use `MTLStoreActionUnknown` at pass creation, and decide at encoder end with
   `setDepthStoreAction:` / `setColorStoreAction:atIndex:`. With look-ahead, a depth attachment whose next use
   is a clear can use `DontCare`. For example, the main depth after the last GUI pass is cleared at the start
   of the next frame. The pending-clear tracking from item 1 provides this look-ahead across `submit()` only
   with a game-side hint (see §12 G4).

### 10.10 Timestamps (`GpuQueryPool`, `writeTimestamp`, `timestampPeriod`)

- **Metal 3 on Apple GPUs** supports counter sampling only at **stage boundaries**
  (`MTLCounterSamplingPointAtStageBoundary`), not between draws or blits.
  - Back a `GpuQueryPool` with an `MTLCounterSampleBuffer` (common counter set *timestamp*, shared storage).
  - For an encoder-level `writeTimestamp(i)`: if it comes immediately before `createRenderPass` (Tracy's
    `pushZone`), attach `i` as `startOfVertexSampleIndex` of that pass's
    `MTLRenderPassSampleBufferAttachmentDescriptor`. If it comes immediately after `submitRenderPass`
    (Tracy's `popZone`), attach `i` as `endOfFragmentSampleIndex` of the pass that just ended, which is still
    possible when the encoder end is deferred.
  - Otherwise (`TimerQuery` around the whole frame), encode an empty blit pass with the sample attached.
  - `getValues` is non-blocking: `resolveCounterRange:` after completion. Report "not ready" with
    `OptionalLong.empty()`.
  - Calibrate `timestampPeriod` and `getTimestampCalibrationOffset` with two
    `[device sampleTimestamps:&cpu gpuTimestamp:&gpu]` calls.
- **Metal 4:** `MTL4CounterHeap` (timestamp type) and encoder `writeTimestampWithGranularity:afterStage:intoHeap:atIndex:`
  allow arbitrary placement (verify in the SDK).
- **Cheap fallback** for frame GPU time: the command buffer's `GPUStartTime` / `GPUEndTime`.

### 10.11 Debug groups and labels

Map `pushDebugGroup` / `popDebugGroup` to the encoder's `pushDebugGroup:` / `popDebugGroup`. For pass labels,
use the encoder `label` and the command-buffer `label`. Gate everything on `isDebuggingEnabled()`, never
evaluate label `Supplier`s otherwise, and never format strings. In release builds the about 300 debug-group
push/pop pairs per frame must cost zero.

### 10.12 One-to-one cheat sheet

| `CommandEncoderBackend` / `RenderPassBackend` | Metal 3 |
|---|---|
| `submit` | end the open encoder, signal the event, `presentDrawable` (registered in `blitFromTexture`), `commit`, wait for N−1, rotate the destroy queue, run callbacks, reset rings |
| `createRenderPass` | resolve pending clears, then `MTLRenderPassDescriptor` (texture + level, Clear/Load, Store/Unknown), then `renderCommandEncoderWithDescriptor:` (or merge with the previous pass); scissor = render area if partial |
| `submitRenderPass` | `endEncoding` (possibly deferred) |
| `clear*` | pending-clear record, or a clear render pass |
| `writeToBuffer` / `copyToBuffer` | `memcpy` fast path, or staging + blit `copyFromBuffer` |
| `writeToTexture` / `copyBufferToTexture` | staging + blit `copyFromBuffer:…toTexture:` |
| `copyTextureToBuffer` | blit `copyFromTexture:…toBuffer:` + deferred callback |
| `copyTextureToTexture` | blit `copyFromTexture:…toTexture:` |
| `createFence` | `{submitIndex}` + `MTLSharedEvent` |
| `writeTimestamp` | counter sample-buffer attachment (stage boundary) |
| `pushDebugGroup` / `popDebugGroup` | `pushDebugGroup:` / `popDebugGroup` (debug only) |
| `setPipeline` | `setRenderPipelineState:` (variant by pass formats), `setDepthStencilState:`, `setCullMode:`, `setFrontFacingWinding:Clockwise`, `setDepthBias:slopeScale:clamp:` (constant = `depthBiasConstant`, slope = `depthBiasScaleFactor`, as in VK `VulkanRenderPipeline.java:275-277`), `setTriangleFillMode:` (wireframe); each only on change |
| `setUniform` | per-pipeline index→slot table; `set{Vertex,Fragment}Buffer[Offset]` / `Texture` / `SamplerState`, shadow-filtered |
| `pushConstants` | `set{Vertex,Fragment}Bytes` |
| `enableScissor` / `disableScissor` | `setScissorRect:` (rect or render area) |
| `setVertexBuffer` | `setVertexBuffer:offset:atIndex:30-slot` or `setVertexBufferOffset`; null → keep (VK semantics) |
| `setIndexBuffer` | remember `(MTLBuffer, type)`; it is passed with each draw |
| `drawIndexed` / `draw` | `drawIndexedPrimitives:…baseVertex:baseInstance:` / `drawPrimitives:…baseInstance:` (fan emulation) |
| `multiDraw*` | CPU loops |
| `drawIndexedIndirect` / `drawIndirect` | loop of indirect draws, CPU decode, or ICB (§10.6) |

---

## 11. Pitfalls checklist

1. **Y orientation:** forgetting the vertex-Y negation turns every offscreen target and readback upside down
   compared with the game's expectations, and then the present blit "fixes" only the screen.
2. **Winding:** after the Y negation use `Clockwise`. Without the negation it would be `CounterClockwise`.
   Mixing the two culls everything.
3. **Depth format:** PSO `depthAttachmentPixelFormat` must equal the pass's depth format, or be `Invalid` for
   depth-less passes. Two variants per pipeline.
4. **Partial render area plus Clear:** Metal clears the whole level (§10.3).
5. **Unused color attachment slots:** the PSO's `colorAttachments[i].pixelFormat` must be `Invalid` where the
   pass has no texture.
6. **`setIndexBuffer` takes a whole `GpuBuffer`.** The terrain code drops the slice offset and folds it into
   `firstIndex` (`LevelRenderer.java:776`, `ChunkSectionsToRender.java:143`). Never add a slice offset
   yourself.
7. **`firstIndexOffsets` in the separate multi-draw are bytes; elsewhere `firstIndex` is in indices.**
8. **`GpuFence` timeouts are in ns; Metal shared-event waits take ms.** `-1` means infinite. Waiting with a
   timeout on a fence for the current submit must throw.
9. **Readback callbacks must run on the render thread**, after completion, in submit order.
10. **Unretained command buffers mean a destroy queue is mandatory.** A `close()` during frame N may only free
    the native object after N has completed: two rotations, as in VK.
11. **In-place UBO rewrites mid-frame** (`writeToBuffer` on Projection, Lighting, Globals) need in-stream
    ordering. A blind `memcpy` breaks earlier draws in the same frame and the in-flight previous frame.
12. **`replaceRegion:` is CPU-immediate.** Do not use it for textures that may be in flight.
13. **Triangle fans and point size:** no fans in Metal; `[[point_size]]` is mandatory for points.
14. **Sampler limit:** 16 per stage. Name-stable slot schemes must respect it.
15. **Label suppliers:** VK evaluates them only when debugging. Doing the same keeps per-batch string
    concatenation off the hot path (`PreparedRenderType.java:38`).
16. **Scissor leakage is expected.** `PreparedRenderType` leaves the scissor enabled; do not reset the scissor
    on `setPipeline`.
17. **Uniform values are type-dispatched by the pipeline layout.** A null value at draw time is an error in
    VK; choose a deterministic policy.
18. **`writeTimestamp` index range** is off-by-one in the frontend.
19. **Encoder-level `writeTimestamp` may arrive while `isInRenderPass` is true** (Tracy `pushZone` runs after
    the flag is set but before the backend pass exists). Order it before the pass begin.
20. **`drawIndexedIndirect` offset** must be a multiple of 4 (the frontend checks this). Each record is
    20 bytes, so consecutive `off + 20*i` stays 4-aligned.

---

## 12. FPS opportunities

### Backend side (Metal)

- **B1. Native command stream with one downcall per pass** instead of one call per method (§10.1). This is
  mandatory for about 1 ms CPU frames.
- **B2. ICB-based terrain MDI** with a GPU-built ICB: O(groups) CPU work instead of O(sections) (§10.6).
  Report a large `maxDrawIndirectDrawCount`, plus `nonZeroFirstInstance` and `multiDrawIndirect` = true, so
  the game picks the MDI path instead of the 3–4-calls-per-section fallback that GL on macOS takes
  (`LevelRenderer.java:178-181`, `results/BASELINE.md`: GL exposes only `ARB_draw_indirect`).
- **B3. Name-stable binding slots plus shadow state.** Pipeline switches no longer re-bind the global UBOs and
  textures. Per-draw ChunkSection changes become `setVertexBufferOffset`. VK re-pushes *all* descriptors after
  any change, which is the VK backend's per-draw cost (`VulkanRenderPass.java:280-384`).
- **B4. `commandBufferWithUnretainedReferences` plus untracked hazards plus a fence chain**, or Metal 4 with
  residency sets: removes per-bind retain/release and tracking overhead.
- **B5. Clear folding, pass merging, deferred store actions:** large bandwidth savings at 3600×2260 (§10.9).
  At least 4 full-target clears per frame become load actions.
- **B6. Shared-storage, zero-copy transient and staging memory.** Drop VK's staging→device whole-block copies
  (`VulkanTransientMemory.java:196-225`). Add the hazard-aware `memcpy` fast path for `writeToBuffer` into
  idle buffers, such as freshly pooled `StagedVertexBuffer` targets.
- **B7. Cache texel-buffer textures and texture views** instead of creating them per descriptor push (VK
  creates a `VkBufferView` per push for clouds).
- **B8. Batch consecutive blit operations** into one blit encoder. VK uses one global barrier per copy.
- **B9. Late drawable acquire** (in `blitFromTexture`) to avoid blocking at frame start, which is where
  `Minecraft.java:1273` calls it. `CAMetalLayer.maximumDrawableCount = 3`, `displaySyncEnabled = NO` for
  uncapped FPS (surface subsystem).
- **B10. Report the smallest legal `minUniformOffsetAlignment`.** It shrinks every dynamic UBO block (16-byte
  ChunkSection blocks become 256 bytes at alignment 256).

### Game side (we may patch vanilla)

- **G1. Merge the two main-target clears** (`GameRenderer.java:477-481` and `LevelRenderer.java:247-257`) and
  express clears as pass load ops: pass `Optional.of(clear)` and `OptionalDouble.of(0.0)` to the first pass
  that uses the target.
- **G2. Fold `clearDepthTexture` before the hand and GUI** (`GameRenderer.java:507,690`, `GuiRenderer.java:200`)
  into those passes' depth `clearValue`.
- **G3. Avoid tiny `writeToBuffer` calls between passes on the same target** (Lighting / Projection
  rewrites). Use the mapped ring (`DynamicGpuData`) instead, so that passes can merge.
- **G4. Add a "discard after pass" hint** to `RenderPassDescriptor` so depth can be `DontCare` on the last
  pass of the frame (main depth after the GUI), and transient OIT targets can be memoryless.
- **G5. Unified-memory vertex path.** `StagedVertexBuffer` uploads go mapped staging → `copyToBuffer` → GPU
  buffer (`StagedVertexBuffer.java:164-166`). On Apple Silicon, draw directly from the mapped ring.
- **G6. Terrain:** make `ChunkSectionsToRender` issue one `drawIndexedIndirect` per layer by putting each
  layer's sections into a single uber vertex buffer. Draw groups currently follow the buffer pages
  (`LevelRenderer.java:722-808`).
- **G7. Stop re-sending every uniform on `setPipeline`** in `FrontendRenderPass` when the pipeline did not
  change (`FrontendRenderPass.java:123-126`), and skip `setPipeline` when it is identical (the GUI and
  `PreparedRenderType` call it per draw or batch).

---

## 13. Open questions

1. The exact Metal 4 selector names and semantics on the macOS 27 SDK: argument-table capture-at-draw,
   `MTL4CounterHeap` timestamp granularity, `waitForDrawable` / `signalDrawable`, and barrier APIs. Verify
   against the headers before committing to the Metal 4 path.
2. The `drawIndexedPrimitives` `indexBufferOffset` alignment on Apple-family GPUs under macOS: index-size
   versus 4-byte. Odd `firstIndex` with 16-bit indices occurs in `StagedVertexBuffer` sub-allocations.
3. The minimum constant-buffer offset alignment for M5 (Apple GPU family 9 or 10). This decides
   `minUniformOffsetAlignment`.
4. Whether `MTLVertexStepFunctionPerInstance` fetch indexing with `baseInstance` matches VK for
   `stepRate > 1` (the game only uses 1 today).
5. Whether Apple GPUs skip load/store work for tiles that a render pass does not touch (relevant to atlas
   animation passes, which load and store an entire atlas mip).
6. Measured backend call counts per frame: add a counting decorator around `CommandEncoderBackend` and
   `RenderPassBackend` in the bench build, to replace the §9.3 estimate.
7. Whether any game pass samples a texture that is also attached to the same pass. None were found in the OIT,
   integrate-depth or water-mask passes reviewed, but this is not proven for every pass. Metal forbids it
   without framebuffer fetch.
8. Whether a negative-height `MTLViewport` is supported and validated on macOS 27 as an alternative to shader
   Y negation. The shader flip is the known-good path.
