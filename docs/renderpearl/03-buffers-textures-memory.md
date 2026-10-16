# RenderPearl 03 — Resources: Buffers, Textures, Samplers, Transient Memory, Formats

Scope: everything a native **Metal** backend for RenderPearl (Minecraft Java 26.3) must know about GPU resources.
All paths are relative to `src/vanilla/`. `RP` = `com/mojang/renderpearl`. Line numbers refer to the Vineflower output in this workspace.

---

## 0. TL;DR — invariants a Metal backend must honour

1. **Single-threaded resource API.** Neither existing backend has any locking around resources (e.g. `RP/backend/util/TransientBlockAllocator.java`, `RP/backend/vulkan/DestructionQueue.java`, all of GL). Every create/close/map/write call arrives on the render thread. (Only pipeline compilation is off-thread.)
2. **`close()` is not the same as destroying the resource.** Callers routinely close buffers, textures and samplers right after recording commands that use them. Example: a `try (GpuBuffer ubo = …)` wraps render passes that are recorded but not yet submitted (`net/minecraft/client/renderer/texture/TextureAtlas.java:178-196`). The scratch textures are closed right after recording (`:194-199`). The sampler is closed and recreated mid-frame (`net/minecraft/client/renderer/LevelRenderer.java:426-436`). The backend must **defer real destruction until the GPU has finished every submission that could reference the object**. Vulkan does this with `DestructionQueue` (§4.2).
3. **`map()` is unsynchronized and persistent-capable.** It never waits for the GPU (GL maps with `GL_MAP_UNSYNCHRONIZED_BIT`, `RP/backend/opengl/GlBuffer.java:59-63`; Vulkan just calls `vmaMapMemory` on HOST_COHERENT memory, `RP/backend/vulkan/VulkanGpuBuffer.java:58-66,165`). Game code does its own fencing: triple buffering plus `GpuFence` (`net/minecraft/client/renderer/MappableRingBuffer.java:11-54`). A buffer may stay mapped while the GPU uses it (`DeviceFeatures.persistentMapping`).
4. **`writeToBuffer`, `copyToBuffer`, `writeToTexture`, `copyBufferToTexture`, `copyTextureToTexture` and the clears are GPU-timeline-ordered commands.** They take effect after all earlier-recorded commands and before all later ones, like GL's `glBufferSubData`. Vulkan implements each one as staging, then a copy, then a **full ALL_COMMANDS memory barrier** (`RP/backend/vulkan/VulkanCommandEncoder.java:587-614,251-260`). A Metal backend must **not** memcpy straight into a shared `MTLBuffer` that in-flight or earlier-recorded GPU work may still read (see §11.3 for a safe fast path).
5. **Caller data is borrowed only for the duration of the call.** The `ByteBuffer`s passed to `createBuffer(…, ByteBuffer)`, `writeToBuffer` and `writeToTexture` are freed right after the call (`com/mojang/blaze3d/systems/RenderSystem.java:462` then `memFree`). Copy them synchronously.
6. **Transient memory is valid until `submit()`.** After `submit()` the transient `GpuBuffer` objects report `isClosed()==true`. The backing memory is recycled only after the GPU has completed that submission (§3).
7. **Readback callbacks run later, on the render thread, after GPU completion.** Vulkan runs them from the destroy queue in `submit()` (`VulkanCommandEncoder.java:793`). GL uses `RenderSystem.queueFencedTask` (`GlCommandEncoder.java:396`), which `RenderSystem.executePendingTasks()` polls each frame (`net/minecraft/client/Minecraft.java:1310`). Callbacks call `buffer.map()` and `buffer.close()` themselves (`net/minecraft/client/Screenshot.java:94-130`).
8. **Memory-row convention: rendered images are stored bottom-up, the GL way.** Both existing backends map NDC y = −1 to memory row 0. Vulkan uses an un-flipped viewport (`RP/backend/vulkan/VulkanRenderPass.java:80-87`) and flips during the present blit (`RP/backend/vulkan/VulkanGpuSurface.java:410-419`). Screenshot readback flips rows in Java (`Screenshot.java:103`). Uploaded images keep row 0 = first row in memory (the top of the PNG), and UV (0,0) addresses memory row 0 in every API. **Metal needs a clip-space Y flip for rasterization only. Texture uploads, sampling and readbacks need no flip, and scissor and clear-region y are memory-row indices.** (§5.9)
9. **Depth uses reverse-Z with D32_FLOAT, Z in [0,1].** Depth is cleared to 0.0 and compared with `GREATER_THAN_OR_EQUAL` (`RP/api/pipeline/DepthStencilState.java:4`, `net/minecraft/client/renderer/GameRenderer.java:507`, `com/mojang/blaze3d/resource/RenderTargetDescriptor.java:46-48`). Set `DeviceInfo.isZZeroToOne=true` (`RP/api/device/DeviceInfo.java:9`).
10. **Frames in flight = 2 in both backends** (`MAX_SUBMITS_IN_FLIGHT`, `VulkanCommandEncoder.java:49`, `GlCommandEncoder.java:40`). `submit()` blocks until the submission before the one just made has completed (§4).

---

## 1. Cross-cutting conventions

| Topic | Fact | Citation |
|---|---|---|
| Closeables | All resources implement `UncheckedAutoCloseable` (`close()` throws no checked exception). `close()` must be idempotent: every impl guards with a `closed` flag. | `RP/util/UncheckedAutoCloseable.java:5-13`; `VulkanGpuBuffer.java:113-122`; `GlBuffer.java:83-97` |
| `isClosed()` | Becomes true **immediately** on `close()`. The frontend checks it before using resources in copies and attachments. Draw-time checks run only under `STRICT_VALIDATION` (= `SharedConstants.IS_RUNNING_IN_IDE`). | `RP/frontend/FrontendGpuDevice.java:36`; `RP/frontend/FrontendRenderPass.java:509` |
| ByteBuffer byte order | Every mapped view is produced with LWJGL `MemoryUtil.memByteBuffer(addr,len)` / `memSlice`, which gives native (little-endian) order. Game writers (`Std140Builder`, `putInt`) depend on that. | `VulkanGpuBuffer.java:166`; `GlBuffer.java:144`; `VulkanTransientMemory.java:316,329,356` |
| Labels | Texture labels are resolved only when debugging is enabled (`FrontendGpuDevice.java:91`). Vulkan passes `""` otherwise (`VulkanDevice.java:212`). Buffer labels arrive as a `Supplier`. | |
| Error types | `GpuOutOfMemoryException` is caught by `MainTarget`, which retries smaller sizes (`com/mojang/blaze3d/pipeline/MainTarget.java:74-88`). GL throws it on `GL_OUT_OF_MEMORY` (`GlDevice.java:258-261,284-287`). Vulkan just throws `IllegalStateException` (`VulkanUtils.java:88-98`). **Metal: throw `GpuOutOfMemoryException` when `newBuffer…` or `newTexture…` returns nil.** | |
| Threading of callbacks | Callbacks must run on the render thread. They touch `GpuBuffer.map` and `close`. | `Screenshot.java:89-133`; `com/mojang/blaze3d/platform/TextureUtil.java:73,107` |

---

## 2. GpuBuffer

### 2.1 API surface (`RP/api/buffers/GpuBuffer.java`)

- `size()` (long), `usage()` (int bitmask), `isClosed()`, `close()`.
- `slice(offset,length)` validates `offset>=0 && length>=0 && offset+length<=size()` (`:27-35`). `slice()` returns the whole buffer (`:37-39`).
- `map(read, write)` maps the whole buffer (`:41-43`). `map(offset,length,read,write)` maps a range (`:45`). There is **no `unmap`**: you close the returned `GpuBufferSlice.MappedView`, which runs its `onClose` (`RP/api/buffers/GpuBufferSlice.java:29-34`).
- `GpuBufferSlice` is a plain record `(buffer, offset, length)` (`GpuBufferSlice.java:6`). `slice()` on it is relative and bounds-checked (`:7-23`); `map()` forwards (`:25-27`). The game sometimes constructs `new GpuBufferSlice(null, 0, 0)` for empty batches (`net/minecraft/client/renderer/DynamicGpuDataStorageMapped.java:121`, `DynamicGpuDataStorageNonMapped.java:98`). The backend must tolerate a null buffer with length 0 if one is ever bound (skip it).

### 2.2 Usage flags — exact meaning in each backend

`GpuBuffer.java:10-19`

| Flag (bit) | Meaning | Vulkan (`VulkanConst.bufferUsageToVk` `:45-76`, memory `VulkanGpuBuffer.java:52-70`) | GL immutable storage (`GlConst.bufferUsageToGlFlag` `:332-351`) / mutable hint (`:353-362`) | Frontend check |
|---|---|---|---|---|
| `USAGE_MAP_READ` (1) | CPU may `map(read=true)` | requiredFlags HOST_VISIBLE\|HOST_COHERENT, preferred HOST_CACHED, VMA `HOST_ACCESS_RANDOM`; `AUTO_PREFER_HOST` | `MAP_READ\|PERSISTENT\|COHERENT` (193); hint `STATIC_READ` / `STREAM_READ` | `map()` throws "Buffer is not readable" (`VulkanGpuBuffer.java:134`, `GlBuffer.java:109`) |
| `USAGE_MAP_WRITE` (2) | CPU may `map(write=true)` | required HOST_VISIBLE\|HOST_COHERENT, VMA `HOST_ACCESS_SEQUENTIAL_WRITE` | `MAP_WRITE\|PERSISTENT\|COHERENT` (194); hint `STATIC_DRAW` / `STREAM_DRAW` | `map()` throws "Buffer is not writable" |
| `USAGE_HINT_CLIENT_STORAGE` (4) | Prefer host memory | `AUTO_PREFER_HOST` (`:68-70`) | `GL_CLIENT_STORAGE_BIT` (512) | — |
| `USAGE_COPY_DST` (8) | Destination of `writeToBuffer` / `copyToBuffer` / `copyTextureToBuffer` | `TRANSFER_DST` | `GL_DYNAMIC_STORAGE_BIT` (256), needed for `glBufferSubData` | `FrontendCommandEncoder.java:302,348,662` |
| `USAGE_COPY_SRC` (16) | Source of `copyToBuffer` / `copyBufferToTexture` | `TRANSFER_SRC` | (no GL flag) | `FrontendCommandEncoder.java:339,592` |
| `USAGE_VERTEX` (32) | Vertex buffer | `VERTEX_BUFFER` | — | `FrontendRenderPass.java:221` (always checked in `setVertexBuffer`) |
| `USAGE_INDEX` (64) | Index buffer | `INDEX_BUFFER` | — | strict validation only (`FrontendRenderPass.java:524`) |
| `USAGE_UNIFORM` (128) | UBO | `UNIFORM_BUFFER` | — | strict only (`:559`) |
| `USAGE_UNIFORM_TEXEL_BUFFER` (256) | Read-only texel buffer (`samplerBuffer`) | `UNIFORM_TEXEL_BUFFER` | (bound with `glTexBuffer`, `GlCommandEncoder.java:576-580`) | strict only (`:566`); **must be the whole buffer** (`:565-578`) |
| `USAGE_INDIRECT_PARAMETERS` (512) | Draw-indirect args | `INDIRECT_BUFFER` | — | always (`FrontendRenderPass.java:315,476`) |

Vulkan memory placement for `Direct` buffers (`VulkanGpuBuffer.java:52-70`):
- Default: `VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE` (8).
- If any MAP bit is set: HOST_VISIBLE|HOST_COHERENT is required.
- MAP_READ or CLIENT_STORAGE switches to `AUTO_PREFER_HOST` (9).
- On integrated-Intel MoltenVK it always forces HOST_VISIBLE (`VulkanDevice.java:136-138,221`).

GL picks immutable `glBufferStorage` when `GL_ARB_buffer_storage` is available and the GPU is not Intel Gen7 or NVIDIA. Otherwise it uses mutable `glBufferData`, and then `canPersistentMap=false` (`RP/backend/opengl/BufferStorage.java:9-16`, `GlDevice.java:144`).

**Usage values the game actually uses** (from `createBuffer` call sites):

| Value | Flags | Who |
|---|---|---|
| 9 | MAP_READ\|COPY_DST | Readback buffers: `Screenshot.java:86`, `TextureUtil.java:70`, `com/mojang/blaze3d/TracyFrameCapture.java:39,65` |
| 18 | MAP_WRITE\|COPY_SRC | Chunk staging ring (`com/mojang/blaze3d/vertex/StagingBuffer.java:126`) |
| 22 | MAP_WRITE\|CLIENT_STORAGE\|COPY_SRC | `StagedVertexBuffer` staging pool (`net/minecraft/client/renderer/StagedVertexBuffer.java:40`); font glyph staging (`net/minecraft/client/gui/font/providers/BitmapProvider.java:267`) |
| 32 | VERTEX | Immutable meshes created with data (sky, stars, cubemap, crosshair) |
| 40 | VERTEX\|COPY_DST | `StagedVertexBuffer` vertex pool (`:41`), weather, world border, chunk-section instance data (`DynamicGpuData.java:107`, then `\|8`) |
| 64 | INDEX | Auto sequential index buffer (`RenderSystem.java:462`) |
| 72 | INDEX\|COPY_DST | `StagedVertexBuffer` index pool (`:42`) |
| 128 | UNIFORM | Immutable UBOs created with data (`PostPass.java:67`, `FogRenderer.java:58`, `TextureAtlas.java:114,178`) |
| 130 | UNIFORM\|MAP_WRITE | `MappableRingBuffer` UBOs: Lightmap, Cloud, Fog, PostPass, `DynamicGpuDataStorageMapped` (`usage\|2`) |
| 136 | UNIFORM\|COPY_DST | `writeToBuffer`-updated UBOs: `GlobalSettingsUniform.java:14`, `ProjectionMatrixBuffer.java:24`, `Lighting.java:30` |
| 258 | UNIFORM_TEXEL\|MAP_WRITE | Cloud face texel buffer (`CloudRenderer.java:144`), format `R8_SINT` (`BindGroupLayouts.java:33,89`) |
| 514 | INDIRECT\|MAP_WRITE | Chunk MDI command ring (`DynamicGpuData.java:115`) |
| usage\|8\|16 | +COPY_DST\|COPY_SRC | `UberGpuBuffer` chunk geometry heaps (`com/mojang/blaze3d/vertex/UberGpuBuffer.java:195`) |

### 2.3 Creation

- `createBuffer(label, usage, size)`: the frontend rejects `size<=0` (`FrontendGpuDevice.java:178-184`). Contents are undefined.
- `createBuffer(label, usage, ByteBuffer data)`: the frontend rejects an empty `data` (`:187-193`). Size is `data.remaining()`, and the contents must equal `data`.
  - **Vulkan:** silently ORs in `COPY_DST` (so `usage()` reports `usage|8`). It copies `data` into transient staging and records `vkCmdCopyBuffer` into the **object-init command buffer**, which executes before the frame's main command buffer (`VulkanDevice.java:225-252`, `VulkanCommandEncoder.java:171-218`).
  - **GL:** `glBufferStorage` / `glBufferData` with data (`BufferStorage.java:31-36,48-53`).
- No maximum-size check in the frontend. `DeviceLimits.maxMemoryAllocationSize` is advisory (`RP/api/device/DeviceLimits.java:9`).

### 2.4 Mapping semantics

**Vulkan** (`VulkanGpuBuffer.Direct.map`, `VulkanGpuBuffer.java:125-199`):
- Checks, in order: closed, read‖write, the usage bit for each requested access, `offset+length<=size`, `length<=Integer.MAX` (2 GB), non-negative.
- Increments `mappingRefCount` and calls `vmaMapMemory` (VMA ref-counts and maps persistently internally).
- Returns `memByteBuffer(ptr+offset, length)`.
- `onClose` is idempotent (an inner `closed` flag); it decrements the count and calls `vmaUnmapMemory`.
- No flush or invalidate is ever done, because coherent memory is required.
- **Closing a buffer while mapped throws** "Attempt to close a mapped buffer". It sets `closed=true` first (`:113-122`).

**GL** (`GlBuffer.Direct`, `GlBuffer.java:43-167`):
- Mapping flags: read gives `MAP_READ`. Write gives `MAP_WRITE|MAP_UNSYNCHRONIZED` (unsynchronized is dropped on GL-on-DX12). With persistent mapping it adds `PERSISTENT|COHERENT` (`:54-68`).
- With persistent capability and a MAP usage, the buffer is **mapped once at construction for its whole lifetime** (`:72-74`). Later `map()` calls just slice the cached pointer (`:135-144`).
- Close unmaps the persistent mapping, then deletes the buffer (`:83-97`).
- Without persistent capability (mutable storage) a mapped buffer cannot be used by the GPU: `checkCanBeUsed()` throws (`GlBuffer.java:29-35`). The frontend calls `checkCanBeUsed` only in strict validation (`FrontendRenderPass.java:363,519,536,554`).

**Contract to replicate:** map is cheap, unsynchronized, coherent, and may stay open while the GPU uses the buffer. `MappedView.close()` must be idempotent. Mapping is refused for transient buffers (§3.5).

### 2.5 Who maps, and when (complete survey of game code)

| Site | Buffer | Pattern / synchronization |
|---|---|---|
| `MappableRingBuffer` (`net/minecraft/client/renderer/MappableRingBuffer.java:11-54`) | 3 buffers, rotated per frame | `currentBuffer()` first `awaitCompletion(-1)` on the fence created when this slot was last rotated out (`:36-45`). `rotate()` creates a fence for the *current* (unsubmitted) submit index (`:47-54`). |
| `Lightmap.java:66`, `CloudRenderer.java:182,192`, `PostPass.java:119`, `FogRenderer.java:200` | `MappableRingBuffer` (130 / 258) | `map(false,true)` on the current buffer, write std140, close immediately. |
| `DynamicGpuDataStorageMapped.java:75,102,136,176` | ring (usage\|2) | Maps a sub-slice per write, closes immediately. Resizes create a new ring; the old one is closed at `endFrame` (`:37-56`). |
| `StagingBuffer.PersistentlyMapped` (`StagingBuffer.java:125-157`) | ring of 3 × (size/2), usage 18 | **Kept mapped across many frames**. Re-mapped only when rotated. Data is copied to its destination with `copyToBuffer` while the buffer is mapped, which requires the persistent-mapping feature. Chosen only if `hintsAndWorkarounds.writeToBufferIsSlow && features.persistentMapping` (`:16-19`). Otherwise `StagingBuffer.Cpu` uses `writeToBuffer` (`:107-111`). Chunk staging size is 102,760,448 bytes (`SectionRenderDispatcher.java:69`). |
| `StagedVertexBuffer.uploadDrawsToBuffers` (`StagedVertexBuffer.java:136-167`) | pooled 22-buffers | Map, write vertices and sorted indices, close, then `copyToBuffer` into pooled 40/72 buffers. Pools recycle after `fence.awaitCompletion(0)` succeeds (`:308-318,350-363,376-383`). |
| `BitmapProvider.ImageDataHolder.gpuData` (`BitmapProvider.java:265-273`) | 22, created once per glyph sheet | Map once, memcpy the image, close. It is then the copy source for `copyBufferToTexture` for each glyph (`:227-240`). |
| `Screenshot.java:94`, `TextureUtil.java:73`, `TracyFrameCapture.java:99` | 9 (readback) | `map(true,false)` **inside the readback callback**, or after the callback set a status flag. |

No game code maps an index, vertex or UBO buffer that lacks a MAP flag. `GpuBuffer.map` is never called on transient buffers.

### 2.6 Command-stream buffer writes

`writeToBuffer(slice, data)`, frontend checks (`FrontendCommandEncoder.java:292-326`): not inside a render pass, buffer not closed, `COPY_DST`, `data.remaining() <= slice.length`, slice inside the buffer.
- **Vulkan** (`VulkanCommandEncoder.java:587-614`): `transientMemory.uploadStaging(data, align 1, COPY_SRC)`, then `vkCmdCopyBuffer(size = data.remaining())`, then a full barrier.
- **GL** (`GlCommandEncoder.java:274-278`): `glNamedBufferSubData` (driver-ordered).

`copyToBuffer(src, dst)`, frontend checks (`FrontendCommandEncoder.java:329-383`): src `COPY_SRC`, dst `COPY_DST`, **equal lengths**, both in range, neither closed. Vulkan: `vkCmdCopyBuffer` then a barrier (`VulkanCommandEncoder.java:617-642`). GL: `glCopyNamedBufferSubData` (`GlCommandEncoder.java:281-287`).

**Why GPU ordering matters (concrete game patterns):**
- `ProjectionMatrixBuffer.writeBuffer` rewrites the same 64-byte UBO whenever the projection changes, possibly several times per frame, between passes (`ProjectionMatrixBuffer.java:28-50`).
- `DynamicGpuDataStorageNonMapped` rewrites the *same regions* of one buffer every frame (`DynamicGpuDataStorageNonMapped.java:73-84,110-122`).
- `UberGpuBuffer` frees TLSF ranges and immediately reuses them for new chunk uploads in the same frame (`UberGpuBuffer.java:59-109`). The previous frame's draws may still be reading the old contents when the copy lands.

---

## 3. Transient memory (`RP/api/buffers/TransientMemory.java`)

### 3.1 The four kinds of memory, plus helpers

| Method | Returns | Semantics |
|---|---|---|
| `allocateCpu(size, align[, minAlloc, elemSize])` | `ByteBuffer` | Plain CPU scratch (`nmemAlloc` blocks). Max alignment **16** (`VulkanTransientMemory.java:34,40-42`, `GlTransientMemory.java:22,29-31`). Recycled **immediately at submit**. |
| `allocateStaging(size, align, usage[, …])` | `MappedView` | CPU-written, GPU-readable "upload heap" memory. Mainly a copy source. Valid until submit. GPU-safe until that submission completes. |
| `allocateGpu(size, align, usage[, …])` | `GpuBufferSlice` | GPU-only memory (no CPU pointer). Contents undefined. |
| `allocateGpuMapped(size, align, usage[, …])` | `MappedView` | CPU-written view of **device-local** memory. Writes made any time **before `submit()`** are visible to every command of the current submission (Vulkan copies the whole block at the *start* of the submission, §3.4). |
| `uploadStaging` / `uploadGpu(List<ByteBuffer>, align, usage[, minAlloc, elemSize])` | `GpuBufferSlice` | Concatenates the buffers, each padded up to `align` (`VulkanTransientMemory.java:374-406`), into one staging or gpuMapped allocation. |
| `multiUploadStaging` / `multiUploadGpu(List<ByteBuffer>, align, usage)` | `List<GpuBufferSlice>` (input order) | Packs many small arrays into as few blocks as possible. Sorts indices by size and repeatedly fits the largest that fits in the current block (`:418-458`). **Quirk:** when nothing fits, it falls back to `allocateGpuMapped` even when `staging==true` (`:450`, same in GL `:428`). |

- `alignment` may be any positive integer: `Mth.roundToward` is ceil-to-multiple, not power-of-two (`net/minecraft/util/Mth.java:694-700`). GPU allocators accept up to 2^62 (`MAX_GPU_ALIGNMENT`).
- `usage` is only recorded as the returned buffer's `usage()`. It is used for frontend usage checks such as COPY_SRC in `copyBufferToTexture`. Vulkan blocks are created with **all** buffer usages (`BUFFER_USAGE_BITS=471` = TRANSFER_SRC|DST|UNIFORM_TEXEL|UNIFORM|INDEX|VERTEX|INDIRECT, `VulkanTransientMemory.java:36,235`).
- `minimumAllocation` / `elementSize` give **partial allocation**. If `size` does not fit in the current block but `minimumAllocation` does, the call returns the rest of the block rounded down to a multiple of `elementSize`, so the **slice is shorter than requested** (`TransientBlockAllocator.java:132-139`). `upload*` then copies only what fits (`VulkanTransientMemory.java:396-401`). Vanilla only uses the default overloads (`minAlloc=size`), so partial allocations never occur in practice, but the backend must implement the contract.

### 3.2 Transient GpuBuffer objects

Both backends wrap blocks in a private `TransientGpuBuffer` (`VulkanTransientMemory.java:468-510`, `GlTransientMemory.java:455-492`):
- `map()` throws "Cannot map transient buffer". `slice()` and `slice(o,l)` on the *buffer* throw. `GpuBufferSlice.slice` on the returned *slice* still works, because it is record-level.
- `size()` = the whole block size. Slices carry the offset inside the block.
- `isClosed()` becomes true once `submitIndex` has advanced past the index at allocation time (`:487-494` / `:464-471`). Frontend copy and attachment checks therefore reject stale transient slices.
- `destroy()` / `close()` only set the flag. The block's lifetime is owned by the allocator.

### 3.3 `TransientBlockAllocator` algorithm (`RP/backend/util/TransientBlockAllocator.java`)

State: `blockSize` (vanilla: 512 KiB everywhere), `maxAlignment`, `freeBlocks`, `usedBlocks`, `specialBlocks`, `currentBlock`, `currentOffset`.

`allocate(size, align, minAlloc, elem)` (`:103-148`):
1. `align > maxAlignment` throws IAE (`:104-106`).
2. `size == blockSize`: take a whole block from the free list (or allocate one) and return offset 0. The current block is unchanged (`:108-110`, `allocateBlock :92-101`).
3. `size > blockSize`: allocate a **special** block of exactly `size`. It is freed, never recycled, at rotation (`:112-117,73`).
4. No current block: take a block and set offset 0 (`:119-122`). `canAllocateInCurrentBlock` returns true when `currentBlock==null` and the request fits a block (`:82-90`).
5. Fits at `roundToward(currentOffset, align)`: bump-allocate (`:124-130`).
6. Else, if `minAlloc` fits: partial allocation (`:132-139`).
7. Else take a new block for this request. **The new block becomes current only if it would have more free space left** (`currentOffset > size`), so the emptier block is kept (`:141-147`).

`onBlockUse` callback: fired whenever a block is put into use (`:98,114`). Vulkan uses it to record the staging→device copy for gpuMapped blocks (§3.4).

`rotate()` (`:47-75`):
- Resets the current block.
- **Destroys** every block that sat in `freeBlocks` unused for a whole rotation. This trims the pool to actual per-frame usage.
- Returns a `Runnable` that, when run (later, after GPU completion), moves this rotation's used blocks back to `freeBlocks` (destroying `suboptimal()` ones) and frees the special blocks.
- `close()` runs `rotate().run()` twice (`:37-41`).

### 3.4 Vulkan transient implementation (`RP/backend/vulkan/VulkanTransientMemory.java`)

- **Heap selection** (`:56-128`):
  - Finds the largest DEVICE_LOCAL heap.
  - If that heap has a HOST_VISIBLE|HOST_COHERENT memory type (UMA / ReBAR, which includes **Apple GPUs via MoltenVK**), `useDeviceMemoryForMappedGpuStaging = true`, and CPU staging also lives in that heap.
  - Blocks that land in an unexpected heap are flagged `suboptimal` and freed instead of recycled (`:264-265`).
- **Block allocation** (`:228-283`):
  - Staging blocks on a discrete GPU use `AUTO_PREFER_HOST`; everything else uses `AUTO_PREFER_DEVICE`.
  - When UMA or staging, the block requires HOST_VISIBLE|COHERENT with SEQUENTIAL_WRITE and is **persistently mapped** (`vmaMapMemory`, never unmapped).
- **Four allocators** (`:40-45,130-141`):
  - CPU uses `nmemAlloc`, block 512 KiB, max alignment 16.
  - Staging: Vulkan blocks, freed directly.
  - GPU: Vulkan blocks, freed through the destroy queue (`queueFreeVulkanBlock :285-287`).
  - GpuMapped: `TransferPair(cpu, gpu)` blocks.
    - On UMA, `cpu == gpu` (one block taken from the GPU allocator) and **no copy** is made.
    - On a discrete GPU it takes one staging block and one GPU block. `onBlockUse = recordGpuMappedCopy` records a whole-block `vkCmdCopyBuffer(cpu→gpu)` into the transient command buffer (`:197-226,293-306`).
- **Transient command buffer ordering:**
  - `beginSubmit()` allocates a command buffer and immediately adds it to the submission (`encoder.execute`), so it executes **first** in the frame (`:152-157`).
  - It is still recorded during the frame and ended in `endSubmit()`. If anything was recorded, it ends with a full barrier (`:159-195`).
- **Rotation timing at `endSubmit()`** (`:159-168`):
  - CPU blocks: `rotate().run()` immediately, so they are reusable next frame.
  - Staging blocks: the rotation runnable is queued on the destroy queue, so they are recycled after this submission completes.
  - GPU blocks: deferred on UMA. On a discrete GPU they recycle immediately; the reuse is safe because GPU-only blocks are written only by queue-ordered copies behind full barriers.
  - GpuMapped: `rotate()` is called but its runnable is **discarded**. TransferPairs are rebuilt from the underlying pools every frame.
- `submitIndex++` at the end of `endSubmit()` (`:194`) is what makes `TransientGpuBuffer.isClosed()` flip.

### 3.5 GL transient implementations (`RP/backend/opengl/GlTransientMemory.java`)

- Chosen by `features.persistentMapping` (`GlCommandEncoder.java:54-56`).
- **PersistentMapping** (`:223-453`):
  - Three allocators: staging uses CLIENT_STORAGE plus a persistent coherent write map; GPU blocks are unmapped; gpuMapped blocks use a persistent coherent write map, flags `0xE2` = WRITE|UNSYNC|PERSISTENT|COHERENT (`:272-297`).
  - `rotate()` stores the three rotation runnables in a **2-slot ring indexed by the submit slot** and runs the runnables stored two submits earlier (`:259-270`). Runnables are therefore executed only after `submit()` waited for that frame.
- **Fallback** (`:57-221`): one allocator of usage-40 GL buffers plus a malloc shadow.
  - `allocateStaging` returns a view whose **`close()` performs `glBufferSubData`**, so the view *must* be closed for the data to reach the GPU (`:89-110`).
  - `upload*` map with `WRITE|INVALIDATE_RANGE|UNSYNCHRONIZED` (flags 38) (`:152`).
  - Blocks are recycled immediately at rotation (`:71-74`).
- GL uses transient memory for **push constants**: `uploadGpu(value, minUniformOffsetAlignment, UNIFORM)` (`GlRenderPass.java:88-91`).

### 3.6 Users of transient memory

- Backend internals:
  - Vulkan `writeToBuffer` / `writeToTexture` staging (`VulkanCommandEncoder.java:589,655`).
  - Vulkan `createBuffer(data)` staging (`VulkanDevice.java:227`).
  - GL push constants.
- Game:
  - `CubeMapTexture.doLoad` uses `uploadStaging(image, 1, COPY_SRC)` and then 6 × `copyBufferToTexture` (`net/minecraft/client/renderer/texture/CubeMapTexture.java:65-70`).
  - `SpriteContents.createAnimationState` uses `multiUploadStaging(mips, 1, COPY_SRC)` and then `copyBufferToTexture` per frame and mip (`SpriteContents.java:286-311`).

---

## 4. Submission, fences, frames in flight (resource-relevant parts)

### 4.1 Vulkan (`VulkanCommandEncoder.java`)

- A timeline semaphore starts at `currentSubmitIndex-1` (= 1) (`:63-80`).
- `submit()` (`:263-279`) runs:
  1. End the command buffers (init command buffer first, then main, `:180-218`).
  2. `transientMemory.endSubmit()`.
  3. Signal the semaphore with `currentSubmitIndex`.
  4. `vkQueueSubmit`.
  5. `currentSubmitIndex++`.
  6. **Wait for `currentSubmitIndex-2`**, with a 5 s timeout, then throw with checkpoints.
  7. Reset the command pool for this parity.
  8. `destroyQueue.rotate()`.
  9. `transientMemory.beginSubmit()`.
- `createFence()` (`:892-911`) captures `currentSubmitIndex`, the frame *being recorded*. `awaitCompletion(t)` waits on the timeline value. Waiting on the current, unsubmitted index returns false when `t==0` and **throws otherwise** (`:843-853`).
- Command buffers per submission: the transient memory command buffer (copies for gpuMapped), then any object-init command buffers (image layout transitions UNDEFINED→GENERAL and `createBuffer(data)` copies), then main command buffers.

### 4.2 Deferred destruction (`RP/backend/vulkan/DestructionQueue.java`)

- Two internal lists (`new DestructionQueue<>(2, …)`, `VulkanCommandEncoder.java:57`). `add()` appends to the current list (`:44-47`).
- `rotate()` moves to the next list index, swaps in a fresh list, and destroys the swapped-out contents (`:30-42`).
- Effective lifetime: anything queued while frame S is recorded is destroyed in the `submit()` of frame S+1, **after waiting for S to complete**. Destroy callbacks may queue more objects safely, because they go into the fresh list.
- `close()` rotates until a full cycle destroys nothing (`:21-28`).
- Things queued:
  - buffers (`VulkanGpuBuffer.java:120`)
  - textures, once the texture and all its views are closed (`VulkanGpuTexture.java:119-128`)
  - views (`VulkanGpuTextureView.java:61-67`)
  - samplers (`VulkanGpuSampler.java:83-88`)
  - per-draw texel buffer views (`VulkanRenderPass.java:341`)
  - staging rotations and GPU-block frees (`VulkanTransientMemory.java:161-163,286`)
  - **`copyTextureToBuffer` callbacks** (`VulkanCommandEncoder.java:793`)

### 4.3 GL

- `submit()` inserts `glFenceSync` into the slot `currentSubmitIndex%2`, increments, waits (infinite) for `current-2`, and rotates transient memory (`GlCommandEncoder.java:75-83`).
- `awaitSubmit` treats indices older than `current-2` as complete (`:85-116`). `GlFence` captures the current index (`GlFence.java:10-28`).
- GL deletes buffers, textures and samplers immediately (the driver keeps them alive). The texture-view refcount mirrors Vulkan's (`GlTexture.java:33-49,76-85`).

### 4.4 Frame loop

There is one `submit()` per frame (`Minecraft.java:1337`), after the swapchain blit (`:1322`) and before present. `RenderSystem.executePendingTasks()` runs before rendering each frame (`:1310`; implementation `RenderSystem.java:327-339`: FIFO, stops at the first unfinished fence, uses `awaitCompletion(0)`).

---

## 5. Textures

### 5.1 API

- `GpuTexture` (`RP/api/textures/GpuTexture.java`):
  - `getWidth(mip)`, `getHeight(mip)`
  - `getDepthOrLayers()`, `getMipLevels()`, `getFormat()`, `usage()`, `getLabel()`, `isClosed()`, `close()`
- Usage bits (`:11-15`):

| Flag | Meaning | Vulkan (`VulkanConst.textureUsageToVk :18-43`) |
|---|---|---|
| `USAGE_COPY_DST` (1) | Upload, copy destination, **and required for every clear** (`FrontendCommandEncoder.java:815,837`) | TRANSFER_DST |
| `USAGE_COPY_SRC` (2) | Readback or texture-to-texture source | TRANSFER_SRC |
| `USAGE_TEXTURE_BINDING` (4) | Sampled (combined image sampler) | SAMPLED |
| `USAGE_RENDER_ATTACHMENT` (8) | Color or depth attachment; also required for clears | COLOR_ATTACHMENT if color aspect, DEPTH_STENCIL_ATTACHMENT if depth aspect |
| `USAGE_CUBEMAP_COMPATIBLE` (16) | Cube map (6 layers) | `VK_IMAGE_CREATE_CUBE_COMPATIBLE_BIT` (`VulkanGpuTexture.java:50`) |

- `BaseGpuTexture.getWidth(mip) = width >> mip` (`RP/backend/common/BaseGpuTexture.java:34-41`). This is **not clamped to 1**: non-square textures with full mip chains report 0 for the smallest levels. Metal clamps to 1, so be careful when computing copy sizes and validation for such mips.
- `GpuTextureView`: `texture()`, `baseMipLevel()`, `mipLevels()`, `getWidth(m)` = `texture.getWidth(m + base)` (`RP/backend/common/BaseGpuTextureView.java:33-40`). A view selects **only a mip range**. Layers are implicitly all of them: a cube view covers 6 faces, a 2D view covers 1 layer.

### 5.2 Creation validation (`FrontendGpuDevice.java:95-149`)

Validated:
- `1 <= mipLevels <= floor(log2(max(w,h)))+1` (`Mth.log2` floors, `Mth.java:314-316`).
- `depthOrLayers >= 1`.
- Cubemaps must be square with layers = 6 exactly. Arrays throw `UnsupportedOperationException`.
- Non-cube textures with `depthOrLayers>1` throw ("Array or 3D textures are not yet supported").

**Not validated (the backend must handle):**
- width/height > 0 and ≤ `maxTextureSize`. `RenderTarget` checks this itself (`com/mojang/blaze3d/pipeline/RenderTarget.java:91`).
- Whether the format supports the requested usage. GL throws IAE for formats it has no internal format for: all `RGB8*`, `RGB16*`, `RGB16_FLOAT`, `RGB32_FLOAT` (`GlDevice.java:242-271`, `GlConst.toGlInternalId :238-288` default 0).
- Whether usage is non-zero.

`createTextureView(texture, base, count)` validation: texture not closed, `base>=0`, `base+count<=mips` (`FrontendGpuDevice.java:162-175`). `count>=1` is *not* checked.

### 5.3 Backend implementations

**Vulkan** (`RP/backend/vulkan/VulkanGpuTexture.java:24-95`):
- `VK_IMAGE_TYPE_2D`, extent (w,h,1), `arrayLayers = depthOrLayers`, `OPTIMAL` tiling, 1 sample.
- VMA `AUTO_PREFER_DEVICE`.
- In the object-init command buffer, a barrier transitions all mips and layers **UNDEFINED → GENERAL**. The aspect is color or depth, never stencil.
- **Every image stays in `GENERAL` for life.** Copies, attachments and sampling all use layout 1 (`VulkanCommandEncoder.java:328,358,435,671`; `VulkanRenderPass.java:316`).
- Contents are undefined until written.

**Vulkan views** (`VulkanGpuTextureView.java:15-53`):
- `VK_IMAGE_VIEW_TYPE_CUBE` if the texture is cube-compatible, otherwise `2D`.
- Same format; aspect color or depth; mip range from the view; layers 0..(6 or 1).

**Ref-counting:**
- The texture counts itself as a "view" (`addViews()` in the constructor, `:94`).
- `texture.close()` → `removeViews()`. Each view's `close()` queues the VkImageView for destruction, then calls `texture.removeViews()`.
- The image is queued for destruction only once the texture *and* all its views are closed (`:103-128`).

The game relies on this. `MainTarget` closes the texture *before* its view (`com/mojang/blaze3d/pipeline/MainTarget.java:37-52`), and `TextureAtlas` calls `view.texture().close()` once per view (repeated close, `TextureAtlas.java:194-199`).

**GL** (`GlDevice.java:210-273`):
- `glTexImage2D` for every mip (and every face for cubes).
- `MAX_LEVEL = mips-1`, `MIN_LOD = 0`, `MAX_LOD = mips-1`. Depth textures get `TEXTURE_COMPARE_MODE = NONE`.
- `GL_OUT_OF_MEMORY` throws `GpuOutOfMemoryException`.
- GL views are not real GL objects. At bind time the backend sets `TEXTURE_BASE_LEVEL` / `MAX_LEVEL` on the texture (`GlCommandEncoder.java:582-600`). When used as an attachment, the view's `baseMipLevel` is the FBO mip (`GlTextureView.java:46-48`).

### 5.4 Uploads

**`writeToTexture(tex, NativeImage)`**: the image size must equal mip 0 (`FrontendCommandEncoder.java:386-396`).

**`writeToTexture(tex, NativeImage, mip, layer, x, y)`** (`:399-453`):
- Destination component type must be `UNORM_8`, with component count equal to the NativeImage's: RGBA=4, RGB=3, LUMINANCE_ALPHA=2, LUMINANCE=1 (`com/mojang/blaze3d/platform/NativeImage.java:530-534`).
- So a LUMINANCE image goes to `R8_UNORM`, LUMINANCE_ALPHA to `RG8_UNORM`, RGBA to `RGBA8_UNORM`. An RGB image would need `RGB8_UNORM`, which no modern API can create.
- NativeImage RGBA memory is R,G,B,A bytes (offsets 0/8/16/24 in a little-endian int) = `RGBA8Unorm`.
- It forwards `source.getPixelBytes()` (`NativeImage.java:488-490`).

**`writeToTexture(tex, ByteBuffer, mip, layer, x, y, w, h)`** (`:456-518`):
- Checks `w*h*blockSize <= remaining`, bounds, closed state, `COPY_DST`, and `layer < depthOrLayers`.
- Does **not** check `x,y >= 0` or the format.
- **Source layout: tightly packed rows of `w*blockSize` bytes, starting at `source.position()`.**
- Vulkan (`VulkanCommandEncoder.java:645-689`):
  - Calls `uploadStaging(source, 1, COPY_SRC)`, then `vkCmdCopyBufferToImage` with `bufferRowLength=w` and `bufferImageHeight=h`.
  - **aspect = COLOR only** (depth uploads are unsupported). The layer is `baseArrayLayer=layer`. Then a barrier.
- GL (`GlCommandEncoder.java:290-316`):
  - Uses `UNPACK_ROW_LENGTH=w`, skip=0, `UNPACK_ALIGNMENT=format.byteAlignment()`.
  - Cube faces map through `CUBEMAP_TARGETS[layer % 6]`. The order is +X, −X, +Y, −Y, +Z, −Z (`GlConst.java:104`), the same as Metal's cube slice order.

**`copyBufferToTexture(srcSlice, srcX, srcY, srcW, srcH, dst, dstX, dstY, copyW, copyH, mip, layer)`** (`FrontendCommandEncoder.java:521-615`):
- The source is a 2D image `srcW × srcH`, tightly packed (row pitch = `srcW*blockSize`) at `srcSlice.offset`. The copied sub-rectangle starts at `(srcX, srcY)`.
- Byte offset of the first texel = `slice.offset + (srcY*srcW + srcX) * blockSize`. Both backends compute exactly this (`VulkanCommandEncoder.java:706-722`, `GlCommandEncoder.java:342-361`).
- Frontend validation: `srcX+copyW<=srcW`, `srcY+copyH<=srcH`, dst bounds, `COPY_SRC` on the buffer, `COPY_DST` on the texture, and `layer<depthOrLayers`.
- **Gap:** the byte-size check is only `srcW*copyH*blockSize <= slice.length`. It ignores the `srcY`/`srcX` offset, so out-of-range reads are possible. The Metal backend should validate `(srcY+copyH-1)*srcW + srcX + copyW` texels against the slice.
- Callers:
  - Cube maps: the source is a vertical strip of 6 faces, with `srcY = height*i` (`CubeMapTexture.java:65-70`).
  - Glyph sheets: arbitrary `srcX/srcY` into an R8 or RGBA8 sheet (`BitmapProvider.java:227-240`).
  - Animated sprite frames per mip (`SpriteContents.java:304-319`).

### 5.5 Readback and texture copies

**`copyTextureToBuffer(tex, buf, offset, callback, mip)`, the 5-argument form:**
- **The frontend does no validation at all** apart from the render-pass check (`FrontendCommandEncoder.java:618-624`). The backend copies the whole mip.
- Callers: `Screenshot.java:89`, `TextureUtil.java:107`, `TracyFrameCapture.java:90`.

**9-argument form** (`:627-699`):
- Checks `w*h*blockSize + offset <= buf.size`, texture `COPY_SRC`, buffer `COPY_DST`, rectangle in range, neither closed, and `depthOrLayers==1`.
- Output layout: **tightly packed rows `w*blockSize`**, starting at `offset`, rows in memory order (row 0 = texture row 0 = bottom of a rendered image).
- Vulkan: `vkCmdCopyImageToBuffer` with `aspectMask = formatAspectMask(format)`, then a barrier. The **callback goes into the destroy queue** and runs after the frame completes (`VulkanCommandEncoder.java:750-794`). The combined depth/stencil aspect mask would be invalid Vulkan usage; that path is unused.
- GL: `glReadPixels` into a `PIXEL_PACK_BUFFER` (depth via `GL_DEPTH_COMPONENT`), then `RenderSystem.queueFencedTask(callback)` (`GlCommandEncoder.java:372-404`).

**`copyTextureToTexture(src, dst, mip, dstX, dstY, srcX, srcY, w, h)`** (`FrontendCommandEncoder.java:702-782`):
- The **same mip level on both sides**. Layer 0 only.
- **Formats are not checked.**
- Vulkan: `vkCmdCopyImage`, which requires size-compatible formats. GL: `glBlitFramebuffer` with `NEAREST`, color or depth (it can convert formats).
- Game use: `RenderTarget.copyDepthFrom` / `copyColorFrom` between targets of identical format (`com/mojang/blaze3d/pipeline/RenderTarget.java:72,85`).

### 5.6 Clears (frontend `:230-289,802-876`)

All clears are outside render passes. Textures need `RENDER_ATTACHMENT` + `COPY_DST`, the right aspect, and a single layer.
- `clearColorTexture`: **all mip levels**, layer 0. Vulkan uses `vkCmdClearColorImage` (`VulkanCommandEncoder.java:427-436`); GL binds an FBO per mip and calls `glClear`.
- `clearDepthTexture`: all mips (`:438-447`).
- `clearColorAndDepthTextures(c, color, d, depth)`: both all-mip (mip counts must match).
- Region variant `(…, x, y, w, h, mip)`:
  - Vulkan opens a temporary render pass on single-mip views and calls `vkCmdClearAttachments` with the rect (`:499-560`). GL uses a scissor box (`GlCommandEncoder.java:231-254`).
  - **The y coordinate is a memory row.** `GuiItemAtlas` passes `textureSize - bottom` (`net/minecraft/client/gui/render/GuiItemAtlas.java:101-111`).
- Clear colors are `Vector4fc` RGBA floats. Depth is a double, but Vulkan uses float.

Game clear patterns worth optimising:
- The main target is cleared twice every frame, first by `GameRenderer.java:479` and then by the LevelRenderer clear pass (`LevelRenderer.java:254`).
- Frame-graph targets are cleared in `RenderTargetDescriptor.prepare` (`:19-29`) and then rendered with `Optional.empty()` (load) passes.

### 5.7 Game texture survey (usage, format)

| Usage | Format | Site |
|---|---|---|
| 15 (all except cube) | RGBA8_UNORM, D32_FLOAT, RGBA16_FLOAT, RGBA32_FLOAT | Render targets: `RenderTarget.java:96,101`, `MainTarget.java:76,84`, `LevelRenderer.java:212-235` (OIT and depth-bounds targets); atlases (`TextureAtlas.java:66`) |
| 13 (DST\|BIND\|ATTACH) | RGBA8_UNORM | Lightmap 16×16 (`Lightmap.java:43`), GUI item atlas, PiP |
| 9 (DST\|ATTACH) | D32_FLOAT | GUI item atlas / PiP depth: never sampled or copied (`GuiItemAtlas.java:49`, `PictureInPictureRenderer.java:105`) |
| 5 (DST\|BIND) | RGBA8_UNORM | Static textures, mipmapped textures, dynamic textures, sprite frame textures |
| 7 (DST\|SRC\|BIND) | RGBA8_UNORM / R8_UNORM | Font pages (`FontTexture.java:31`), paletted atlas |
| 21 (DST\|BIND\|CUBE) | RGBA8_UNORM, 6 layers | Panorama cube map (`CubeMapTexture.java:63`) |
| 10 (SRC\|ATTACH) | RGBA8_UNORM | Tracy capture (`TracyFrameCapture.java:37`) |

- Per-mip views (`createTextureView(tex, level, 1)`) are used as **color attachments** to render atlas mips (`TextureAtlas.java:75,179-191`; per-frame sprite animation also renders into mip views at `:251`).
- Formats seen in game code: textures use RGBA8_UNORM, R8_UNORM, D32_FLOAT, RGBA16_FLOAT, RGBA32_FLOAT. **RGBA32_FLOAT is a blended render target with `BlendFunction.MAX`** (`RenderPipelines.java:43`).
- Swapchain (Vulkan): `R8G8B8A8_UNORM` or `B8G8R8A8_UNORM`, SRGB_NONLINEAR colorspace, **non-sRGB formats** (`VulkanGpuSurface.java:151-153`). `GpuFormat` has no sRGB formats at all.

### 5.8 Samplers (`RP/api/textures/GpuSampler.java`, `AddressMode`, `FilterMode`)

- Parameters: address modes U and V only (REPEAT, CLAMP_TO_EDGE); min and mag filter (NEAREST, LINEAR); `maxAnisotropy` (int); `maxLod` (`OptionalDouble`).
- No W address mode (backends default to REPEAT), no compare op, no border color, no LOD bias, no explicit mip filter.
- Frontend: requires `1 <= maxAnisotropy <= limits.maxAnisotropy` (`FrontendGpuDevice.java:65-79`).
- **Implicit mip filtering:**
  - GL: min NEAREST → `NEAREST_MIPMAP_LINEAR`; min LINEAR → `LINEAR_MIPMAP_LINEAR`. The mip filter is **always linear**. `MAX_LOD` is set only if present (`GlSampler.java:33-58`).
  - Vulkan: `mipmapMode = maxLod.orElse(1000) > 0.25 ? LINEAR : NEAREST`, `maxLod = max(0.25, maxLod.orElse(1000))`, `minLod = 0`, `lodBias = 0`. Anisotropy is enabled iff >1 (`VulkanGpuSampler.java:42-51`). This is the standard "0.25 clamp" emulation of GL non-mipmapped min/mag selection.
- Game: `SamplerCache` pre-creates all 32 combinations with anisotropy 1 and `maxLod = useMipmaps ? empty : 0.0` (`com/mojang/blaze3d/systems/SamplerCache.java:13-32`). The terrain sampler is CLAMP/LINEAR/LINEAR with user anisotropy, and is recreated on settings change (`LevelRenderer.java:426-436`).
- Sampler `close()`: Vulkan defers destruction; GL deletes immediately.

### 5.9 Coordinate conventions (critical for Metal)

- **Uploads:** the first byte of the source is texel (0,0) = memory row 0. For images this is the top row of the PNG. UV (0,0) samples memory row 0 in GL, Vulkan and Metal alike. Minecraft atlas UVs assume v=0 is the top of the image, so **no flip is needed for uploads or sampling**.
- **Rasterization:**
  - GL (`glClipControl(GL_LOWER_LEFT, GL_ZERO_TO_ONE)` when available, `GlDevice.java:149-152`): NDC y = −1 → window y = 0 → memory row 0.
  - Vulkan with a positive viewport (`VulkanRenderPass.java:80-87`) and no shader flip (`RP/frontend/shaders/GlslCompiler.java:74-93` adds no flip): NDC y = −1 → framebuffer y = 0 → memory row 0. **Identical memory layout to GL.** Rendered images are therefore "upside-down" in memory relative to the display.
  - The Vulkan present blit flips (`VulkanGpuSurface.java:410-419`). Screenshot readback flips (`Screenshot.java:103`). Debug texture dumps of uploaded atlases do not flip (`TextureUtil.java:80-89`).
  - **Metal:** NDC y = +1 maps to row 0 (top-left origin). Rasterization therefore needs a y-negation (vertex-shader epilogue `pos.y = -pos.y`, as SPIRV-Cross `flip_vert_y` does, or a negative-height viewport if validated). Front-face winding must then be verified (§11.12).
  - `gl_FragCoord.y` and Metal `[[position]].y` both equal the memory row + 0.5, so no fix is needed. Scissor rectangles, render areas and clear regions are memory-row based in all backends.
- **Depth:**
  - Z in [0,1]: `RENDERPEARL_DEPTH_IS_ZERO_TO_ONE` is defined when `isZZeroToOne` (`GlslCompiler.java:81-83`); `Projection.java:69`, `Camera.java:188`.
  - Reverse-Z: clear 0.0, GEQUAL.
- Cube faces: layer index i = face i in +X, −X, +Y, −Y, +Z, −Z order. GL, Vulkan and Metal share this ordering and the face orientation conventions.

---

## 6. GpuFormat — full list (`RP/api/GpuFormat.java`)

`blockSize() = componentType.byteSize * componentCount` (`:69-71`). `byteAlignment() = componentType.byteSize` (`:73-75`); this is used as GL `UNPACK`/`PACK_ALIGNMENT` and as the vertex attribute alignment.
Aspects: depth = {D32_FLOAT, D32_FLOAT_S8_UINT, D24_UNORM_S8_UINT, D16_UNORM}; stencil = {S8_UINT, D32_FLOAT_S8_UINT, D24_UNORM_S8_UINT}; color = everything else (`:85-95`).

| GpuFormat | Bytes | Align | VkFormat (`VulkanConst.java:109-168`) | GL internal (`GlConst.java:238-288`) | **MTLPixelFormat** | **MTLVertexFormat** |
|---|---|---|---|---|---|---|
| R8_UNORM | 1 | 1 | R8_UNORM | GL_R8 | R8Unorm | UCharNormalized |
| R8_SNORM | 1 | 1 | R8_SNORM | R8_SNORM | R8Snorm | CharNormalized |
| RG8_UNORM | 2 | 1 | R8G8_UNORM | RG8 | RG8Unorm | UChar2Normalized |
| RG8_SNORM | 2 | 1 | R8G8_SNORM | RG8_SNORM | RG8Snorm | Char2Normalized |
| RGB8_UNORM | 3 | 1 | R8G8B8_UNORM | **none (IAE)** | **none** | UChar3Normalized |
| RGB8_SNORM | 3 | 1 | R8G8B8_SNORM | **none** | **none** | Char3Normalized |
| RGBA8_UNORM | 4 | 1 | R8G8B8A8_UNORM | RGBA8 | RGBA8Unorm | UChar4Normalized |
| RGBA8_SNORM | 4 | 1 | R8G8B8A8_SNORM | RGBA8_SNORM | RGBA8Snorm | Char4Normalized |
| R16_UNORM | 2 | 2 | R16_UNORM | R16 | R16Unorm | UShortNormalized |
| R16_SNORM | 2 | 2 | R16_SNORM | R16_SNORM | R16Snorm | ShortNormalized |
| RG16_UNORM | 4 | 2 | R16G16_UNORM | RG16 | RG16Unorm | UShort2Normalized |
| RG16_SNORM | 4 | 2 | R16G16_SNORM | RG16_SNORM | RG16Snorm | Short2Normalized |
| RGB16_UNORM | 6 | 2 | R16G16B16_UNORM | **none** | **none** | UShort3Normalized |
| RGB16_SNORM | 6 | 2 | R16G16B16_SNORM | **none** | **none** | Short3Normalized |
| RGBA16_UNORM | 8 | 2 | R16G16B16A16_UNORM | RGBA16 | RGBA16Unorm | UShort4Normalized |
| RGBA16_SNORM | 8 | 2 | R16G16B16A16_SNORM | RGBA16_SNORM | RGBA16Snorm | Short4Normalized |
| R8_UINT | 1 | 1 | R8_UINT | R8UI | R8Uint | UChar |
| R8_SINT | 1 | 1 | R8_SINT | R8I | R8Sint | Char |
| RG8_UINT | 2 | 1 | R8G8_UINT | RG8UI | RG8Uint | UChar2 |
| RG8_SINT | 2 | 1 | R8G8_SINT | RG8I | RG8Sint | Char2 |
| RGB8_UINT | 3 | 1 | R8G8B8_UINT | **none** | **none** | UChar3 |
| RGB8_SINT | 3 | 1 | R8G8B8_SINT | **none** | **none** | Char3 |
| RGBA8_UINT | 4 | 1 | R8G8B8A8_UINT | RGBA8UI | RGBA8Uint | UChar4 |
| RGBA8_SINT | 4 | 1 | R8G8B8A8_SINT | RGBA8I | RGBA8Sint | Char4 |
| R16_UINT | 2 | 2 | R16_UINT | R16UI | R16Uint | UShort |
| R16_SINT | 2 | 2 | R16_SINT | R16I | R16Sint | Short |
| RG16_UINT | 4 | 2 | R16G16_UINT | RG16UI | RG16Uint | UShort2 |
| RG16_SINT | 4 | 2 | R16G16_SINT | RG16I | RG16Sint | Short2 |
| RGB16_UINT | 6 | 2 | R16G16B16_UINT | **none** | **none** | UShort3 |
| RGB16_SINT | 6 | 2 | R16G16B16_SINT | **none** | **none** | Short3 |
| RGBA16_UINT | 8 | 2 | R16G16B16A16_UINT | RGBA16UI | RGBA16Uint | UShort4 |
| RGBA16_SINT | 8 | 2 | R16G16B16A16_SINT | RGBA16I | RGBA16Sint | Short4 |
| R32_UINT | 4 | 4 | R32_UINT | R32UI | R32Uint | UInt |
| R32_SINT | 4 | 4 | R32_SINT | R32I | R32Sint | Int |
| RG32_UINT | 8 | 4 | R32G32_UINT | RG32UI | RG32Uint | UInt2 |
| RG32_SINT | 8 | 4 | R32G32_SINT | RG32I | RG32Sint | Int2 |
| RGB32_UINT | 12 | 4 | R32G32B32_UINT | RGB32UI | **none** (texture) | UInt3 |
| RGB32_SINT | 12 | 4 | R32G32B32_SINT | RGB32I | **none** (texture) | Int3 |
| RGBA32_UINT | 16 | 4 | R32G32B32A32_UINT | RGBA32UI | RGBA32Uint | UInt4 |
| RGBA32_SINT | 16 | 4 | R32G32B32A32_SINT | RGBA32I | RGBA32Sint | Int4 |
| R16_FLOAT | 2 | 2 | R16_SFLOAT | R16F | R16Float | Half |
| RG16_FLOAT | 4 | 2 | R16G16_SFLOAT | RG16F | RG16Float | Half2 |
| RGB16_FLOAT | 6 | 2 | R16G16B16_SFLOAT | **none** | **none** | Half3 |
| RGBA16_FLOAT | 8 | 2 | R16G16B16A16_SFLOAT | RGBA16F | RGBA16Float | Half4 |
| R32_FLOAT | 4 | 4 | R32_SFLOAT | R32F | R32Float | Float |
| RG32_FLOAT | 8 | 4 | R32G32_SFLOAT | RG32F | RG32Float | Float2 |
| RGB32_FLOAT | 12 | 4 | R32G32B32_SFLOAT | **none** | **none** (texture) | Float3 |
| RGBA32_FLOAT | 16 | 4 | R32G32B32A32_SFLOAT | RGBA32F | RGBA32Float | Float4 |
| RGB10A2_UNORM | 4 | 4 | A2B10G10R10_UNORM_PACK32 | RGB10_A2 | RGB10A2Unorm (R in low bits, same as Vulkan) | UInt1010102Normalized |
| RGB10A2_UINT | 4 | 4 | A2B10G10R10_UINT_PACK32 | RGB10_A2UI | RGB10A2Uint | **none** (unpack in shader from `UInt`) |
| RG11B10_FLOAT | 4 | 4 | B10G11R11_UFLOAT_PACK32 | R11F_G11F_B10F | RG11B10Float | FloatRG11B10 (macOS 14+) |
| D32_FLOAT | 4 | 4 | D32_SFLOAT | DEPTH_COMPONENT32F | **Depth32Float** | — |
| D32_FLOAT_S8_UINT | 8 | 8 | D32_SFLOAT_S8_UINT | DEPTH32F_STENCIL8 | Depth32Float_Stencil8 | — |
| D24_UNORM_S8_UINT | 4 | 4 | D24_UNORM_S8_UINT | DEPTH24_STENCIL8 | **No Apple-GPU equivalent** (`Depth24Unorm_Stencil8` unsupported; `depth24Stencil8PixelFormatSupported == NO`). Substitute Depth32Float_Stencil8, which breaks `blockSize` (4 vs 8) for readback. | — |
| D16_UNORM | 2 | 2 | D16_UNORM | DEPTH_COMPONENT16 | Depth16Unorm | — |
| S8_UINT | 1 | 1 | S8_UINT | STENCIL_INDEX8 | Stencil8 | — |

Notes:
- **No 3-component pixel formats exist in Metal.** GL also refuses most of them as textures. Throw `IllegalArgumentException` like GL (`GlDevice.java:271`); vanilla never creates such textures. They are vertex-only in practice (`RGB32_FLOAT` positions, `RGB32_SINT` chunk positions).
- **Texel buffers:** the only use is `R8_SINT` (CloudFaces) → Metal `MTLTextureTypeTextureBuffer` with `R8Sint`, created from the MTLBuffer (whole buffer, offset 0). Cache one per (buffer, format), whereas Vulkan creates a `VkBufferView` per draw and defers its destruction (`VulkanRenderPass.java:319-341`).
- GL vertex attributes: integer formats use `glVertexAttribIPointer`; normalized ones use `normalized=true`; floats as-is (`VertexArray.java:60-101`, `GlConst.isFormatNormalized :231-236`). Metal vertex formats encode this directly.

---

## 7. Vertex formats (`RP/api/vertex/`)

- `VertexFormat` holds up to 16 elements (`VertexFormat.java:14,80-82`); `MAX_VERTEX_BUFFERS = 16` (`RenderPass.java:19`).
- `VertexFormatElement(name, offset, GpuFormat)` (`VertexFormatElement.java:6`).
- The builder requires each attribute offset to be a multiple of `format.byteAlignment()` (`:84-86`; only 1-byte alignment for 8-bit formats) and the vertex size to be a multiple of 4 (`:138-141`).
- `stepRate`:
  - 0 = per-vertex.
  - >0 = per-instance with divisor `stepRate`. Vulkan uses `inputRate INSTANCE` + `VK_EXT_vertex_attribute_divisor` (`VulkanRenderPipeline.java:222-233`); GL uses `glVertexAttribDivisor` (`VertexArray.java:101,123`).
  - Metal: `MTLVertexStepFunctionPerInstance`, `stepRate`.
- Matrix-like attributes: `addAttribute(name, fmt, columnCount)` creates `columnCount` consecutive elements with the same name (`:114-123`).
- Vertex buffer slices: the slice offset is the buffer base offset for that slot (GL `glBindVertexBuffer(slot, buf, slice.offset, stride)`, `VertexArray.java:162`).
- Vanilla formats (`com/mojang/blaze3d/vertex/DefaultVertexFormat.java:17-115`):
  - Position RGB32_FLOAT, Color RGBA8_UNORM, UV0 RG32_FLOAT, UV1/UV2 RG16_SINT (**integer**), Normal RGBA8_SNORM, LineWidth R32_FLOAT.
  - Instanced `CHUNK_DATA_INSTANCED` (stepRate 1): RGB32_SINT + R32_FLOAT.
  - `BLOCK` = 28 bytes/vertex.
  - All offsets are multiples of 4. Metal requires 4-byte-aligned attribute offsets and strides, so the backend should validate that, because the builder doesn't.
- Indirect args: `INDIRECT_DRAW_SIZE=16`, `INDIRECT_INDEXED_DRAW_SIZE=20` (`RenderPass.java:20-21`).
  - The game writes `{indexCount, instanceCount, firstIndex, baseVertex, baseInstance}` (`DynamicGpuData.java:128-133`).
  - This is **byte-identical to `MTLDrawIndexedPrimitivesIndirectArguments`** and to `MTLDrawPrimitivesIndirectArguments` for the non-indexed form.

---

## 8. Device limits, features and hints relevant to resources

- `DeviceLimits` (`RP/api/device/DeviceLimits.java:5-17`):

| Field | Vulkan source | GL source | Game use |
|---|---|---|---|
| `maxAnisotropy` | `maxSamplerAnisotropy` | `GL_MAX_TEXTURE_MAX_ANISOTROPY` | Sampler validation; anisotropy option |
| `minUniformOffsetAlignment` | `minUniformBufferOffsetAlignment` (`VulkanDevice.java:96`) | `GL_UNIFORM_BUFFER_OFFSET_ALIGNMENT` (`GlHeuristics.java:96`) | **Every `setUniform(slice)` offset must be a multiple** (`FrontendRenderPass.java:150-154`). UBO block sizes are rounded to it (`DynamicGpuDataStorageMapped.java:29`, `TextureAtlas.java:102,149`). |
| `maxTextureSize` | `maxImageDimension2D` | Probed (`GlHeuristics.java:63-78`) | `RenderTarget` size check |
| `maxMemoryAllocationSize` | `maxMemoryAllocationSize` | Long.MAX | `maxTextureSizeForFormat = highestOneBit(min(maxTex, sqrt(maxAlloc/blockSize)))` (`:14-16`), used by `TextureAtlas.java:59` |
| `maxColorAttachments` | | | Render pass validation |

- `DeviceFeatures.persistentMapping` (`DeviceFeatures.java:11`): Vulkan always true (`VulkanDevice.java:113`); GL = `ARB_buffer_storage`.
- `HintsAndWorkarounds.writeToBufferIsSlow` (`HintsAndWorkarounds.java:4`): Vulkan false; GL = GL-on-DX12. Together with `persistentMapping`, it selects `StagingBuffer.PersistentlyMapped` over `Cpu` (`StagingBuffer.java:16-19`).
- `isExplicitDepthRequired = isAppleSiliconMac` in both backends (`VulkanDevice.java:116`, `GlHeuristics.java:115`). It adds the `RENDERPEARL_EXPLICIT_DEPTH_INVARIANCE` define (`GlslCompiler.java:85-87`). Keep it true for Metal on Apple silicon unless proven unnecessary.

---

## 9. What the frontend validates vs what the backend must do

| Area | Frontend validates | Backend must still do |
|---|---|---|
| createBuffer | size > 0; data non-empty | Allocation, OOM → `GpuOutOfMemoryException`, copy data synchronously |
| map | nothing (it is a backend method on the buffer) | All checks in §2.4, refcount, refuse close-while-mapped, refuse transient |
| writeToBuffer / copyToBuffer | not in pass, closed state, usage, ranges, equal lengths | GPU ordering, staging, alignment fallbacks |
| createTexture | mip count, layers, cube rules | Dimension limits, format/usage support (throw IAE), OOM |
| createTextureView | closed, mip range | View object (or parent reuse), refcount, deferred destruction |
| createSampler | anisotropy range | Mip filter / LOD emulation (§5.8), dedupe |
| writeToTexture / copyBufferToTexture | most bounds and usages (weak byte-range check) | Exact source range check, aspect handling, pitch math |
| copyTextureToBuffer (5-arg) | **none** | Size/usage checks, callback scheduling |
| copyTextureToBuffer (9-arg) / copyTextureToTexture | bounds, usages, single layer | Format compatibility (T2T), callbacks |
| clears | aspect, usage (ATTACH+DST), single layer, mips match, region bounds | All-mip clears, region clear via scissored draw |
| Draw-time resource state | only under `STRICT_VALIDATION` (IDE) | Be robust: closed or missing resources must not crash the GPU |
| `setUniform(slice)` | offset % `minUniformOffsetAlignment` (always) | — |
| texel buffer | must be the whole buffer (strict) | Typed texture-buffer view |

---

## 10. (reserved; see §11 for Metal)

---

## 11. Metal mapping

### 11.1 Object mapping

| RenderPearl | Metal (classic) | Metal 4 |
|---|---|---|
| `GpuBuffer` | `MTLBuffer` (`MTLResourceStorageModeShared`) + Java `ByteBuffer` over `contents()` | Same; add to an `MTLResidencySet`; bind by `gpuAddress+offset` in an `MTL4ArgumentTable` |
| `GpuBufferSlice` | (buffer, offset) passed to `set*Buffer:offset:atIndex:`; use `set*BufferOffset:` when only the offset changes | `argumentTable setAddress:` |
| `TransientMemory` | Ring of large shared `MTLBuffer` blocks (or one big ring buffer) + bump pointer | Same; placement heaps optional |
| `GpuTexture` | `MTLTexture` (`StorageModePrivate`, `MTLTextureType2D` or `Cube`) | + residency set |
| `GpuTextureView` | `newTextureViewWithPixelFormat:textureType:levels:slices:`, or the parent itself for full-range views | `gpuResourceID` |
| `GpuSampler` | `MTLSamplerState` (immutable, deduplicated) | Sampler `gpuResourceID` in argument tables (`supportArgumentBuffers=YES`) |
| texel buffer | `[buffer newTextureWithDescriptor:offset:bytesPerRow:]`, `MTLTextureTypeTextureBuffer` | Same |
| Copies | `MTLBlitCommandEncoder` | Copy commands on `MTL4ComputeCommandEncoder` (Metal 4 folds blit into compute) |
| Clears | Render pass `loadAction=Clear` | Same |
| Submit/fence | `MTLCommandBuffer` + `MTLSharedEvent` timeline (signal value = submitIndex) | `MTL4CommandQueue commit:` + `signalEvent:value:`; one `MTL4CommandAllocator` per frame in flight |
| Destroy queue | Per-submit-index release list, drained when `event.signaledValue >= idx` | Mandatory: MTL4 command buffers don't retain resources |

### 11.2 Buffers on unified memory

- **Use `MTLStorageModeShared` for every `GpuBuffer`.** On Apple silicon there is no discrete VRAM, and shared buffers have no GPU-side penalty.
  - `map()` becomes `MemoryUtil.memByteBuffer(contents()+offset, length)`, with no syscalls and no copies.
  - `createBuffer(data)` becomes `newBufferWithBytes:length:options:` (or `newBufferWithLength` + `memCopy`). This is synchronous and needs **no GPU copy** (Vulkan needs a staging copy, §2.3).
  - Keep reporting the caller's `usage` unchanged. Don't OR in `COPY_DST` like Vulkan does.
- Cache mode:
  - `MTLCPUCacheModeDefaultCache` for anything with `MAP_READ`.
  - `MTLCPUCacheModeWriteCombined` may help CPU-write-only staging (usage 18/22, also 130/514 if never read back). Measure on M5 first; never read from WC memory.
- Hazard tracking: see §11.10. The default for device-allocated resources is Tracked.
- `persistentMapping = true`. Mapping is free and may stay open while the GPU uses the buffer, matching Vulkan.
- Coherency: shared memory is coherent at command-buffer boundaries.
  - CPU writes that are complete before `commit` are visible to that command buffer.
  - GPU writes are visible to the CPU after completion; no `didModifyRange` or `synchronizeResource` is needed (those apply to Managed storage only).
- Implement `checkCanBeUsed()` as a no-op (inherited from `BaseGpuBuffer`).
- Keep a `mappingRefCount` so you can throw on close-while-mapped, like Vulkan (`VulkanGpuBuffer.java:113-118`).
- Size limits:
  - `device.maxBufferLength` sets `maxMemoryAllocationSize`.
  - `map()` lengths must be ≤ 2 GB (Java `ByteBuffer`).
  - Big buffers in game: the UberGpuBuffer heaps and the chunk staging ring (3 × 49 MiB) are fine.
- OOM: `newBuffer…` returns nil → throw `GpuOutOfMemoryException`.

### 11.3 `writeToBuffer` / `copyToBuffer` in Metal

**Default path (always correct):**
1. `memcpy` the data into the transient ring.
2. Encode `[blit copyFromBuffer:ring sourceOffset:… toBuffer:dst destinationOffset:slice.offset size:len]` in a **shared "transfer" blit encoder**.
3. Keep that encoder open across consecutive transfer commands (writeToBuffer, copyToBuffer, writeToTexture, copyBufferToTexture, copyTextureToBuffer, copyTextureToTexture) and end it when the next render pass begins or at submit.

With Tracked hazards Metal orders the blit after earlier render encoders that read `dst` and before later ones. That is the same visible behaviour as Vulkan's full barriers, without serializing everything.

**Safe fast path: CPU memcpy, no GPU work.**
- Stamp each `MetalGpuBuffer` with `lastGpuUseSubmit`. Update it on every bind or copy that references the buffer. Encoders record their bindings anyway.
- If `lastGpuUseSubmit < currentSubmit` (the buffer is not referenced by any command recorded this frame) **and** `lastGpuUseSubmit <= completedSubmit`, then a direct `memcpy` into `contents()` is semantically identical. This happens often for rarely-updated UBOs (`GlobalSettingsUniform`, `Lighting`), but not for buffers used every frame such as `ProjectionMatrixBuffer`.
- Optional: "orphaning" (renaming) for small whole-buffer UBO writes. Swap the buffer's backing (MTLBuffer, offset) to a fresh sub-allocation and memcpy into it. Already-encoded commands keep the old backing, because Metal captures buffer+offset at `set*Buffer` time. Release the old backing after the current submit completes. This is what GL drivers do. It is only valid when the buffer has no pending GPU *writes* (copy destinations).

**Pitfall: macOS buffer-copy alignment.** Apple documents that on macOS, `copyFromBuffer:…toBuffer:` offsets and size must be multiples of 4 (and similarly for `fillBuffer`). Vanilla sizes are in practice multiples of 4:
- vertex stride % 4 == 0
- index ranges are multiples of 12 bytes per quad
- `IndexedDraw` is 20 bytes
- UBO blocks are 16-aligned

Still, guard it. On a violation, use the CPU fast path if it applies, otherwise round the copy up when the destination has room and the source is ring-padded. Verify with the Metal API validation layer on M5.

### 11.4 Transient memory ring for N frames in flight

Contract recap (§3): allocations are valid until `submit()`; the memory must not be reused until the GPU has completed that submit. On UMA all four kinds collapse into one shared allocator. Vulkan's UMA path already does this: `cpu==gpu`, no copy (`VulkanTransientMemory.java:296-299`). `allocateCpu` can come from the same blocks or from malloc blocks.

```java
// sketch: one instance per device; all on render thread
final class MetalTransientMemory implements TransientMemory {
    static final long BLOCK = 8L << 20;            // 8 MiB shared blocks (vanilla: 512 KiB) – fewer distinct MTLBuffers, more offset-only rebinds
    final ArrayDeque<Block> free = new ArrayDeque<>();
    final ArrayList<Block> used = new ArrayList<>(), special = new ArrayList<>();
    final ArrayDeque<Retired> retired = new ArrayDeque<>();   // (submitIndex, blocks) FIFO
    Block cur; long off;                            // bump pointer
    long frameIndex;                                // == encoder.currentSubmitIndex while recording

    Alloc alloc(long size, long align, long minAlloc, long elem) {
        // replicate TransientBlockAllocator semantics (§3.3) incl. partial allocation and "keep emptier block current"
        if (size > BLOCK) { Block b = Block.dedicated(size); special.add(b); return new Alloc(b, 0, size); }
        long o = cur == null ? Long.MAX_VALUE : Mth.roundToward(off, align);
        if (cur != null && o + size <= BLOCK) { off = o + size; return new Alloc(cur, o, size); }
        if (cur != null && o + minAlloc <= BLOCK) { long n = (BLOCK - o) / elem * elem; off = o + n; return new Alloc(cur, o, n); }
        Block b = free.isEmpty() ? Block.create(BLOCK) : free.pop(); used.add(b);
        if (cur == null || off > size) { cur = b; off = size; }
        return new Alloc(b, 0, size);
    }
    void endFrame(long submitIndex) {               // called inside submit(), before commit
        retired.add(new Retired(submitIndex, List.copyOf(used), List.copyOf(special)));
        used.clear(); special.clear(); cur = null;
    }
    void reclaim(long completedIndex) {             // called after waiting in submit()
        while (!retired.isEmpty() && retired.peek().index <= completedIndex) {
            Retired r = retired.pop(); free.addAll(r.blocks); r.special.forEach(Block::release);
        }
        // optional trimming: release blocks unused for K frames (vanilla trims after 1 idle rotation)
    }
}
```

- The returned "transient GpuBuffer" wrappers must throw on `map()` / `slice()` and must report `isClosed()` once `submitIndex` has advanced. Copy the Vulkan class.
- `allocateGpuMapped` / `allocateStaging` return the CPU pointer into the same shared block. `MappedView.close()` is a no-op.
- Alignment: allocate with `max(align, 16)` when the slice may be used as a blit source to a texture (Metal requires `sourceOffset` to be a multiple of the pixel size) or as a constant buffer.
- **Size the ring for N frames**: steady-state memory = per-frame usage × (N+1). With `StagingBuffer.Cpu`, chunk mesh uploads go through `writeToBuffer` staging, so peaks of tens of MiB are possible while many chunks load.
- **Ordering inside submit():**
  1. End encoders.
  2. `transient.endFrame(idx)`.
  3. Encode a signal of event value `idx`.
  4. Commit.
  5. `idx++`.
  6. Wait for `idx - FRAMES_IN_FLIGHT`, matching vanilla's wait-for-N-2 with N=2.
  7. Drain releases and callbacks up to `event.signaledValue`.
  8. `transient.reclaim(completed)`.

### 11.5 Deferred destruction, readback callbacks, fences

- Keep **one FIFO of `(submitIndex, Runnable)`**, used for releases, texel-view releases and `copyTextureToBuffer` callbacks.
  - `close()` enqueues the `release`/`removeFromResidencySet` at `currentSubmitIndex`.
  - Drain only on the render thread: in `submit()` after the wait, and optionally at the start of `executePendingTasks`. Use `MTLSharedEvent.signaledValue` (a cheap property read) or an `AtomicLong` updated from `addCompletedHandler:`.
  - **Never run callbacks on Metal's completion-handler thread.** They call `map()`, `close()` and NativeImage code.
- Vulkan's delay is "closed during frame S → destroyed after S completes". Any delay ≥ that is valid.
- With classic Metal and *retained-reference* command buffers you could release immediately. Deferral is required for:
  - `commandBufferWithUnretainedReferences` (a CPU win at high draw counts)
  - Metal 4
  - residency-set removal
- `GpuFence`: capture `currentSubmitIndex`. `awaitCompletion(0)` → `signaledValue >= idx`. `awaitCompletion(t>0 or -1)` → `[event waitUntilSignaledValue:idx timeoutMS:…]`; NS→MS conversion, −1 = forever.
  - **Throw if `idx == currentSubmitIndex` and `t != 0`**, matching Vulkan and GL (`VulkanCommandEncoder.java:848-853`, `GlCommandEncoder.java:90-95`).
  - `close()` marks it complete.
- If you split a frame into several committed `MTLCommandBuffer`s (to get the GPU started earlier), keep **one submitIndex per frame** and signal the event only after the last one. All fence, transient and destroy semantics are per frame.
  - CPU writes into mapped or transient memory that already-committed work reads would race. The game already respects this, because GL executes eagerly.
  - Vulkan's `allocateGpuMapped` semantic ("visible to the whole submission, even if written after recording") weakens to "written before the command buffer that uses it is committed".

### 11.6 Textures

**Descriptor:**
- `textureType = (usage & CUBEMAP_COMPATIBLE) ? MTLTextureTypeCube : MTLTextureType2D`
- `pixelFormat` from §6
- `width`, `height`, `mipmapLevelCount = mipLevels`, `arrayLength = 1` (cube: 6 faces implicit), `sampleCount = 1`
- `storageMode = Private`
- `usage = (TEXTURE_BINDING ? ShaderRead : 0) | (RENDER_ATTACHMENT ? RenderTarget : 0)`. COPY_SRC and COPY_DST need no Metal usage bit. **Don't add `PixelFormatView` or `ShaderWrite`**: they can disable lossless compression on Apple GPUs.
- `hazardTrackingMode` per §11.10
- Contents are undefined, as in Vulkan. Atlas gaps are never cleared by vanilla (`TextureAtlas.clearTextureData` only drops sprites, `:282-293`), so GL drivers' de-facto zero-fill may be relied on visually. Optionally zero-fill new atlases with one clear pass; it is cheap.

**Private vs Shared:** Private allows lossless framebuffer compression and twiddled layouts, which are big bandwidth wins for render targets and sampled textures. Uploads go through blits from the shared transient ring. `replaceRegion` cannot target Private textures, so writes are GPU-ordered anyway, as the API requires.

**Views:**
- Full-range views (`base=0, count=mips`): reuse the parent `MTLTexture` with no new object; `RenderTarget` creates these for every target.
- Partial mip ranges: `newTextureViewWithPixelFormat:sameFormat textureType:sameType levels:NSMakeRange(base,count) slices:NSMakeRange(0, cube?6:1)`.
- Mirror the Vulkan refcount: the texture counts itself as one; each view adds one. Real release (deferred) happens when the count reaches 0.

**Render to mip:** in render pass descriptors use `attachment.texture = parent; attachment.level = view.baseMipLevel()`, or the view object.

**`writeToTexture` (ByteBuffer):**
1. Ring-allocate `w*h*bpp` bytes with ≥16-byte alignment.
2. `memCopy`.
3. `copyFromBuffer:ring sourceOffset:o sourceBytesPerRow:w*bpp sourceBytesPerImage:w*h*bpp sourceSize:{w,h,1} toTexture:t destinationSlice:layer destinationLevel:mip destinationOrigin:{x,y,0}`.

**`copyBufferToTexture`:** `sourceOffset = slice.offset + (srcY*srcW+srcX)*bpp`, `sourceBytesPerRow = srcW*bpp`, `sourceBytesPerImage = srcW*copyH*bpp`, `sourceSize = {copyW,copyH,1}`, `destinationSlice = arrayLayer` (cube face), `destinationLevel = mip`.
- Requirements: offset and bytesPerRow are multiples of the pixel size. For R8 any value works, which matters for glyph sheets.
- Validate the exact byte range (§5.4).
- Depth formats: use `MTLBlitOptionNone` for Depth32Float/Depth16. Use `DepthFromDepthStencil` / `StencilFromDepthStencil` for combined formats.

**`copyTextureToBuffer`:**
1. `copyFromTexture:src sourceSlice:0 sourceLevel:mip sourceOrigin:{x,y,0} sourceSize:{w,h,1} toBuffer:dst destinationOffset:offset destinationBytesPerRow:w*bpp destinationBytesPerImage:w*h*bpp`.
2. Enqueue the callback at `currentSubmitIndex` (§11.5).
3. Do the frontend's checks yourself for the 5-argument overload.

Rows come out in memory order, which is the bottom-up rendered image (Screenshot flips them).

**`copyTextureToTexture`:** use a blit `copyFromTexture:…toTexture:` with the same level on both sides and slice 0. It requires identical (or bit-compatible) pixel formats. If they differ, fall back to a fullscreen-triangle draw with `nearest` sampling (GL blit semantics). Depth→depth blits are allowed.

**Clears (big TBDR opportunity):**
- `clearColorTexture`, `clearDepthTexture` and full `clearColorAndDepthTextures` clear **all mips**. Implement them as one render pass per mip, with `loadAction = Clear`, `storeAction = Store` and no draws. There is no texture fill command in Metal.
- **Better: lazy clears.** Record "pending clear (value)" on the texture/mip and *fold* it into the next render pass that uses it as an attachment (`loadAction=Clear` instead of `Load`). Materialize it as an empty clear pass only if the next use is a copy, a sample, or the end of the frame.
  - This removes a full store→load round trip per target.
  - The main target is cleared twice per frame (`GameRenderer.java:479`, `LevelRenderer.java:254`), and frame-graph targets are cleared then immediately rendered (`RenderTargetDescriptor.java:19-29`).
- Region clear `(x,y,w,h,mip)`: there is no `vkCmdClearAttachments` equivalent. Open a pass on that mip with Load/Store, set the scissor to the region (y = memory row, so it maps directly to Metal's top-left scissor), and draw a full-viewport triangle. The pipeline writes the clear color and outputs depth = clearDepth (`[[depth(any)]]`, or vertex z with depth compare Always and write on). If the region covers the whole mip, use `loadAction=Clear`.

**Present:** `CAMetalLayer` cannot use `RGBA8Unorm`; use `BGRA8Unorm` (non-sRGB, to match Vulkan's UNORM choice) and set `colorspace` to sRGB. The final copy must also flip Y (§5.9), so present with a fullscreen-triangle draw that samples the main target with v flipped. The draw does both swizzle and flip, which a blit cannot.

### 11.7 Samplers

`MTLSamplerDescriptor`:
- `sAddressMode`/`tAddressMode` = Repeat or ClampToEdge; `rAddressMode = Repeat`.
- `minFilter`/`magFilter` = Nearest or Linear.
- `mipFilter`:
  - `NotMipmapped` if `maxLod.isPresent() && maxLod <= 0.25`. This equals Vulkan's NEAREST + 0.25 clamp and GL's MAX_LOD 0: only the view's base level is sampled, with the min/mag choice made by LOD sign.
  - `Linear` otherwise. GL always blends mips linearly, including min NEAREST → `NEAREST_MIPMAP_LINEAR`.
- `lodMinClamp = 0`; `lodMaxClamp = maxLod.orElse(FLT_MAX)`. Metal LODs are relative to the view's base level, like GL with `TEXTURE_BASE_LEVEL`.
- `maxAnisotropy` 1..16.
- `normalizedCoordinates = YES`; no compare function.
- `supportArgumentBuffers = YES` if you use argument buffers or Metal 4.

**Dedupe:** keep a map from params to a refcounted `MTLSamplerState`. `GpuSampler.close()` decrements, and the release is deferred. Report `limits.maxAnisotropy = 16`.

### 11.8 Pixel-format mapping summary and flags

- Direct equivalents exist for every non-3-component color format plus D32, D16, S8 and D32S8. See §6.
- **No Metal pixel format** (throw IAE on `createTexture`, as GL does):
  - `RGB8_UNORM/SNORM/UINT/SINT`
  - `RGB16_UNORM/SNORM/UINT/SINT`
  - `RGB16_FLOAT`
  - `RGB32_FLOAT/UINT/SINT` (GL can create RGB32UI/I; vanilla never does)
- **D24_UNORM_S8_UINT → Depth32Float_Stencil8** on Apple silicon. Precision is better, but the `blockSize()` of 4 bytes no longer matches the real 8-byte texel and the Depth-only copy is 4 bytes. Unused by vanilla, so throw on readback of combined depth/stencil formats.
- `RGBA32_FLOAT` render target with **MAX blending** (`RenderPipelines.java:43`) and `RGBA16_FLOAT` additive targets (`:55,63`):
  - Apple-silicon Macs support `MTLGPUFamilyMac2`, whose capability set includes blending and filtering of 32-bit float formats. `supports32BitFloatFiltering` is YES on M-series.
  - **Verify at startup.** The fallback is programmable blending (read `[[color(n)]]` in the fragment shader), which is native and cheap on Apple TBDR.
- Sampling D32_FLOAT through a non-comparison sampler is used by post effects. Bind it as `texture2d<float>` / `depth2d<float>` per SPIRV-Cross output, and verify linear filtering of Depth32Float on M5.
- No sRGB formats exist in `GpuFormat`. Never pick `_sRGB` Metal variants.

### 11.9 Vertex formats

Use the §6 `MTLVertexFormat` column, with `MTLVertexStepFunctionPerVertex` / `PerInstance` and `stepRate`. Buffer layout `stride = vertexSize` (always %4==0). Attribute offsets must be 4-aligned; validate them. The packed `RGB10A2_UINT` has no Metal vertex format.

### 11.10 Hazard tracking modes and reproducing Vulkan's synchronization

Vulkan's model:
- Every image stays in `GENERAL`.
- **A full `ALL_COMMANDS→ALL_COMMANDS, MEMORY_READ|WRITE` barrier follows every copy, clear and render pass** (`VulkanCommandEncoder.java:251-260`, used at `:409,455,480,568,598,626,673,726,776,825`).
- There is one queue.

The only GPU writers are attachments and copy destinations. There are no storage buffers or images in the API.

Options for Metal:
1. **Tracked (default for device-allocated resources).** Correct by construction across encoders and across command buffers on one queue. The cost is CPU time in the driver per binding, plus false dependencies at whole-resource granularity (e.g. one big transient block or UberGpuBuffer heap). This is the recommended first implementation.
2. **Untracked + manual fences** (resources from an `MTLHeap` with `hazardTrackingMode=Untracked`, or `MTLResourceHazardTrackingModeUntracked`). Reproduce Vulkan's semantics with **one `MTLFence` chain**: every encoder calls `waitForFence:` (before vertex stage for render) at start and `updateFence:` (after fragment for render) at end. This serializes encoders exactly like the Vulkan barriers do, and removes all per-binding tracking cost. Pair it with `commandBufferWithUnretainedReferences` and the deferred destruction of §11.5. CPU writes to mapped buffers are already fenced by the game, and transient memory is fenced by the ring.
3. **Metal 4.** No tracking and no retention. Use `MTL4CommandQueue` + per-frame `MTL4CommandAllocator`, `MTLResidencySet`s (add on create, remove on deferred destroy, `commit` changes once per frame), `MTL4ArgumentTable` with GPU addresses and resource IDs, and producer/consumer barriers (`barrierAfterQueueStages:beforeStages:visibilityOptions:` / `MTLFence`) between passes. Copies are encoded on `MTL4ComputeCommandEncoder`.

Whichever option you choose, the required orderings are:
- (a) transfer writes before later reads;
- (b) later transfer writes after earlier reads (WAR: `UberGpuBuffer` region reuse, `DynamicGpuDataStorageNonMapped` region reuse, `ProjectionMatrixBuffer`);
- (c) attachment writes before later sampling or copies;
- (d) readback copy before the CPU callback (event).

### 11.11 Residency (classic Metal)

Direct `set*Buffer` / `set*Texture` bindings make resources resident automatically. If you move to argument buffers, resources referenced only indirectly need `useResource:` / `useHeap:`, or better, an `MTLResidencySet` attached to the queue (macOS 15+). With Metal 4, residency sets are mandatory.

### 11.12 Pitfalls checklist

- [ ] Y-flip only in rasterization (§5.9). Re-check `frontFace` / culling after the flip. Present needs a flip plus an RGBA→BGRA swizzle.
- [ ] Never memcpy into a buffer the GPU might still read for `writeToBuffer` (§11.3).
- [ ] Copy caller `ByteBuffer`s synchronously (§0.5).
- [ ] `isClosed()` flips immediately; destruction is deferred; textures and views are refcounted; double `close()` is a no-op.
- [ ] Transient slices: throw on `map` / `slice`; `isClosed` after submit; memory recycled only after GPU completion.
- [ ] Callbacks run on the render thread in FIFO order. A callback may close buffers and map others.
- [ ] Fence semantics, including throwing on the unsubmitted index.
- [ ] `getWidth(mip)` can be 0 (no clamp); Metal clamps to ≥1.
- [ ] 3-component formats and D24S8: throw or substitute (§11.8).
- [ ] Buffer→buffer copy 4-byte alignment on macOS; buffer→texture offset/pitch multiple of pixel size.
- [ ] Region clears need a draw; full clears are loadActions; all-mip clears.
- [ ] Cube textures: 6 faces through `destinationSlice`, cube-typed views; seamless filtering is implicit.
- [ ] `copyTextureToBuffer` 5-arg is unvalidated by the frontend.
- [ ] `minUniformOffsetAlignment`: pick a value Metal honours for `constant` buffers. 16 is safe for std140 on Apple GPUs (whose documented constant-offset alignment is small); 256 is the conservative Mac-family value. See open questions.
- [ ] `GpuOutOfMemoryException` on nil allocations (`MainTarget` fallback).
- [ ] `GpuBufferSlice(null,0,0)` tolerance.

---

## 12. Performance opportunities

### Backend-side (Metal)
1. **Zero-copy shared buffers.** `createBuffer(data)` becomes a memcpy with no blit. `map()` is a pointer. There is no gpuMapped staging copy (Vulkan's non-UMA path issues whole-block copies).
2. **One open blit encoder per gap between render passes**, batching every transfer. Vulkan's full barrier after every copy and pass (`VulkanCommandEncoder.java:251-260`) is pure overhead to avoid.
3. **Lazy clears folded into load actions** (§11.6). This saves at least two full-screen store/load round trips per frame on a 3024×1964 target: main color RGBA8 (~24 MB) plus main depth D32 (~24 MB), and frame-graph targets on top.
4. **Private render targets and textures** for lossless compression. Use DontCare store actions for depth where the frame graph proves it unused afterwards (needs a game hint; GUI/PiP depth (usage 9) is a candidate).
5. **Untracked hazards + fence chain + unretained command buffers**, or Metal 4 argument tables plus residency sets. These cut per-bind CPU cost, which dominates at ~1000 FPS.
6. **Deduplicate samplers. Cache texel-buffer texture views per buffer** (Vulkan creates a `VkBufferView` per draw). Reuse the parent `MTLTexture` for full-range views.
7. **Large transient blocks** (MiB-scale instead of 512 KiB). Bind with `set*BufferOffset:` when only the offset changes.
8. **Direct-memcpy fast path for idle buffers** in `writeToBuffer` (§11.3), plus optional UBO orphaning.
9. **Consider 3 frames in flight** (the game's `MappableRingBuffer` has 3 slots and fences everything, so it is safe). Also consider committing mid-frame command buffers early so the GPU starts sooner, with one event signal per frame.
10. **Set `writeToBufferIsSlow=false`** to keep `StagingBuffer.Cpu`, *or* `true` to use the persistently-mapped ring, which removes per-mesh transient staging (one memcpy into a mapped ring, then a GPU copy). Benchmark chunk-loading spikes both ways. With the persistently-mapped ring, staging memory is preallocated (3 × 49 MiB) instead of growing the transient pool.

### Game-side (patches to vanilla code)
1. **`StagedVertexBuffer` double copy on UMA.** It maps a staging buffer, then `copyToBuffer`s into vertex and index pools (`StagedVertexBuffer.java:136-167`). On Metal, make the vertex and index pools `MAP_WRITE` and write directly (the pools are already fence-recycled, `:308-363`). This removes one GPU copy plus a blit encoder per upload batch, and those batches cover GUI, entities and particles.
2. **`DynamicGpuDataStorageNonMapped`** (chunk-section instance data, `DynamicGpuData.java:107`) does `writeToBuffer` into the same regions each frame. Switch to `DynamicGpuDataStorageMapped` with `VERTEX|MAP_WRITE` on UMA.
3. **`UberGpuBuffer` chunk uploads:** staging, then a GPU copy (`UberGpuBuffer.java:57-128`). Add a "quarantine" so freed TLSF ranges are not reused for N frames. Then mesh data can be memcpy'd straight into the Shared heap buffer (no staging, no blits), which removes the 98 MiB staging path entirely.
4. **Remove the redundant main-target clear** (`GameRenderer.java:479` vs `LevelRenderer.java:254`), or rely on backend lazy clears.
5. **Compact the chunk vertex format.** `BLOCK` is 28 bytes/vertex (`DefaultVertexFormat.java:27-32`). Bandwidth-bound terrain on Retina benefits from 16–20 byte formats (needs shader changes).
6. **Screenshot and Tracy readbacks** are already async; nothing to do.

---

## 13. Open questions

1. Exact Metal alignment rules on **Apple-silicon macOS** for `copyFromBuffer:toBuffer:` (4-byte multiple?). Also confirm the constant-buffer offset alignment, which decides `minUniformOffsetAlignment` (4 / 16 / 256).
2. Does Metal accept a **negative viewport height** for the Y flip, or must it be a shader epilogue? What front-face winding results in each case?
3. RGBA32Float **MAX blending** and Depth32Float **linear filtering** on M5 under macOS 27. Expected YES via the Mac2 family, but confirm.
4. Is **Metal 4** (MTL4CommandQueue, residency sets, argument tables) worth it from Java? LWJGL 3.4.3 ships no Metal bindings, only `org.lwjgl.system.macosx.ObjCRuntime` / `JNI` for `objc_msgSend`, or JDK 25 FFM. Per-call FFI overhead strongly favours batching, and possibly a small native helper dylib for hot paths.
5. Frames in flight: keep vanilla's 2, or go to 3? This interacts with drawable count and latency.
6. Does any visual path depend on GL's de-facto zero-initialized textures (atlas gaps under mipmapping)? Should the Metal backend zero-fill new textures?
7. Should 3-component and D24S8 textures throw (GL behaviour) or be emulated (RGB→RGBA expansion on upload)? Vanilla never needs it.
8. Is the frontend's weak `copyBufferToTexture` byte-range check (§5.4) relied upon by any mod-free path with out-of-range reads? Vanilla callers look in-range.
