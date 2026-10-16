# Metal backend: integration contract (frozen)

Owner: architect. Status: **frozen** for the M1 implementation phase (ABI version 2: amended after the
contract review, see §15; clarified at the M1 integration merge without any ABI change, see §16). Normative
words: MUST, SHOULD, MAY.

This document, the frozen headers and the skeletons are everything an implementer needs besides the
authoritative spec `docs/renderpearl/00-metal-backend-spec.md` ("spec §x" below). Where the spec leaves a
choice open, this contract decides; §12 lists every such decision. If this contract and the spec disagree,
this contract wins for interfaces (names, layouts, ownership), the spec wins for semantics. If you find a
contradiction or a hole, do not work around it in your module: raise it with the architect.

Contents: 1 Architecture · 2 Ownership · 3 C ABI · 4 Command stream · 5 Synchronization and lifetime ·
6 Bindings and dummies · 7 Pipelines · 8 Surface and present · 9 Java classes and cross-module APIs ·
10 Packaging, loading, build · 11 Testing · 12 Decisions beyond the spec · 13 Spec §2 checklist · 14 Open items ·
15 Review amendments (ABI 2) · 16 Integration amendments (M1 merge).

---

## 1. Architecture (M1 = classic Metal)

```
RenderPearl frontend (unchanged)
  │ GpuBackend / GpuDeviceBackend / CommandEncoderBackend / RenderPassBackend / GpuSurfaceBackend
  ▼
com.mojang.renderpearl.backend.metal (Java)
  MetalBackend ─ MetalDevice ─┬─ MetalTimeline (pendingSeq, completedSeq, deferred FIFO)
                              ├─ MetalCommandEncoder ─ MetalRenderPass ─ StreamWriter (init + main stream)
                              ├─ MetalTransientMemory, MetalBuffer, MetalTexture(View), MetalSampler,
                              │  MetalFence, MetalQueryPool
                              ├─ MetalRenderPipeline (+ ShaderCache) ─ MslTranslator (spvc)
                              └─ MetalSurface (SDL Metal view + CAMetalLayer)
  MtlNative (FFM, 27 static final MethodHandles) · StreamOps (record layout) · MetalFormats (enum maps)
  │ immediate downcalls: create/destroy/release, compile, surface config, waits
  │ one mcmtl_submit per frame (+ flush-on-wait commits): decode + encode + signal + commit
  ▼
libmcmetal.dylib (Objective-C, ARC + -fobjc-arc-exceptions, C ABI, @autoreleasepool + @try in every export)
  mcmetal_common.m (architect: infrastructure + the mcmtl_submit guard) · device/resources/pipeline/surface .m (I1)
  · decoder .m (I2: mcmtl_decoder_submit, called by the guard)
  ▼
Metal.framework (MTLCommandQueue, automatic hazard tracking, retained references) · QuartzCore
```

Java records every `CommandEncoderBackend`/`RenderPassBackend` call into an off-heap stream (§4). Native
code decodes and encodes it in one downcall per commit. Resource creation and destruction are immediate
downcalls; destruction always goes through the Java deferred-release FIFO (§5.5). Two frames in flight;
one `MTLSharedEvent` timeline; fences are a captured timeline value (§5).

The ABI and stream are designed so that M2 (clear folding, pass merging, late store actions, upload
hoisting, untracked hazards, buffer renaming), M3 (Metal 4 decoder) and M4 (ICB terrain, MetalFX render
scale) need no ABI change: the decoder sees whole calls (look-ahead within one `mcmtl_submit`), records
carry only object handles, every flag word has reserved bits, the device flags select decoder variants
(§4.7), and the M4 `UPSCALE` record is already reserved with a frozen layout (§4.4).

---

## 2. Module ownership

Rule: an implementer edits **only** the files in their row. Everything else compiles as a skeleton/stub in
their worktree. Cross-module signatures (§9.6) are part of this contract: implement them, do not change
them. Private helpers go into your own files (nested/private classes, `static` C functions). If you truly
need a new file, name it with your prefix (I1: `native/src/mcmetal_core_*.m`, I2:
`native/src/mcmetal_decoder_*.m`, Java: a nested class instead of a new top-level class); every
`native/src/*.m` and every `client/src/**/*.java` is picked up by the build automatically. I1 also owns the
private header `native/src/mcmetal_core.h` (per-device `MCMTLCoreState` kept as an associated object of
`MCMTLDevice`, format helpers, present helpers; §16 #1, #9); nothing outside I1's files includes it except
`native/tests/core_test.m`.

| Module | Files (exact) | Scope |
|---|---|---|
| **Architect (frozen)** | `docs/metal/CONTRACT.md`, `native/include/mcmetal.h`, `native/include/mcmetal_stream.h`, `native/src/mcmetal_internal.h`, `native/src/mcmetal_common.m`, `native/tests/abi_test.m`, `client/src/com/mojang/renderpearl/backend/metal/MtlNative.java`, `.../metal/StreamOps.java`, `.../metal/MetalFormats.java`, `.../metal/package-info.java`, `tools/MetalSmoke.java`, `tools/metal-smoke.sh`, `tools/build-native-tests.sh`, `tools/build-client.sh` | ABI, stream format, shared native infrastructure (error TLS, probe, ABI sizes, device-context basics, status page, completion tracking, commit helpers, waits, the `mcmtl_submit` export = guard around the decoder, debug-message ring, depth-stencil cache, wrapper-class `@implementation`s), Java FFM bindings, enum mappings, proof tooling, build scripts |
| **I1 native-core** | `native/src/mcmetal_device.m`, `native/src/mcmetal_resources.m`, `native/src/mcmetal_pipeline.m`, `native/src/mcmetal_surface.m`, `native/src/mcmetal_core.h` (private), `native/tests/core_test.m` (new) | `mcmtl_device_*`, `mcmtl_timeline_*`, buffers, textures, views, texel views, samplers, query pools, release, shader functions, pipelines + `mcmtl_pipeline_build_variant`, surface create/configure/present/destroy |
| **I2 native-decoder** | `native/src/mcmetal_decoder.m`, `native/tests/decoder_test.m` (new) | `mcmtl_decoder_submit` (behind the architect's `mcmtl_submit` guard), `mcmtl_decoder_device_init/shutdown`, stream decode, render/blit encoders, binding shadow state, dummies, draws incl. fan emulation, clears, copies, texture init, timestamps, PRESENT + early commit + present pass, signal + commit (through `mcmtl_commit_signaled`/`mcmtl_commit_untimed`) |
| **I3 java-device** | `MetalBackend.java`, `MetalDevice.java`, `MetalTimeline.java`, `MetalBuffer.java`, `MetalTexture.java`, `MetalTextureView.java`, `MetalSampler.java`, `MetalFence.java`, `MetalQueryPool.java`, `MetalTransientMemory.java` (all in `client/src/com/mojang/renderpearl/backend/metal/`) | Backend entry, device + DeviceInfo, resources, fences, query pools, timeline + deferred FIFO, transient memory. Already complete (architect, keep semantics): `MetalBackend.loadLibrary()`/`createDevice`, `MetalDevice.buildDeviceInfo`, its fields and accessors, `forTesting`, `beginCompile/endCompile/markClosedAndAwaitCompiles`, `compilePipeline`, `getDeviceInfo`; `MetalBuffer` backing accessors and `rebind`. |
| **I4 java-encoder** | `MetalCommandEncoder.java`, `MetalRenderPass.java`, `StreamWriter.java`, `MetalSurface.java` | Recording every encoder/pass call, submit/flush, writeToBuffer fast path, validation the frontend skips, surface (SDL view/layer, configure, acquire, blit, present, close) |
| **I5 java-pipeline + integration** | `MslTranslator.java`, `MetalRenderPipeline.java`, `client/src/net/minecraft/client/PreferredGraphicsApi.java`, `client/lang/en_us.json`, any further game wiring (patched vanilla copies) | SPIR-V→MSL, binding tables, pipeline creation, shader cache; game integration. `PreferredGraphicsApi` and the lang key are already complete. |

Shared build scripts: `tools/build-client.sh` and `tools/build-native-tests.sh` belong to the architect and
use identical language flags, visibility and frameworks; implementers do not need to touch them (all
sources are discovered). Request changes instead.

---

## 3. C ABI conventions (`native/include/mcmetal.h`)

### 3.1 Types and calling convention

- Pure C linkage, `MCMTL_API` (= `visibility("default")`); everything else in the dylib is hidden.
- Fixed-size types only: `uint16_t/uint32_t/int32_t/uint64_t/float`, pointers. `mcmtl_handle` = `uint64_t`.
- **Pointers from Java are `long` addresses** (FFM `JAVA_LONG`; AAPCS64 passes pointers and 64-bit
  integers identically). Pointer fields inside structs are 8-byte addresses written with `memPutLong`.
- Every top-level struct passed by pointer starts with `uint32_t structSize`, which the **caller sets to
  sizeof(struct), for result structs too**. Natives return `MCMTL_E_ABI_MISMATCH` on mismatch.
- No implicit padding: explicit `reserved` fields, natural alignment, offsets pinned by
  `MCMTL_ASSERT_OFFSET` / `MCMTL_ASSERT_SIZE`. Callers zero-fill structs (MemoryStack `ncalloc`).
- Enum-valued fields carry **raw Metal enum values** (`MTLPixelFormat`, `MTLVertexFormat`,
  `MTLBlendFactor`, `MTLCompareFunction`, `MTLTextureUsage`, `MTLResourceOptions`, ...). Java maps
  RenderPearl enums with `MetalFormats` (frozen, complete); native code passes values through.
- Strings: UTF-8, NUL-terminated unless a length is given (`mcmtl_shader_desc.source`).

### 3.2 Status codes, last error, Java exception mapping

Functions return `int32_t` (0 = `MCMTL_OK`, errors < 0) except the documented trivial getters
(`mcmtl_abi_version`, `mcmtl_abi_struct_size`, `mcmtl_timeline_completed`, `mcmtl_last_error`).
On failure the export stores a message in a **thread-local** buffer (8 KiB) returned by
`mcmtl_last_error()` (never NULL, "" if none); Java reads it on the same thread right after the call
(`MtlNative.lastError()`, `MtlNative.check(status, what)`).

| Status | Meaning | `MtlNative.exceptionFor` |
|---|---|---|
| `MCMTL_E_INVALID_ARGUMENT` (-1) | bad handle/value/range | `IllegalArgumentException` |
| `MCMTL_E_OUT_OF_MEMORY` (-2) | `newBuffer`/`newTexture` returned nil | `GpuOutOfMemoryException` |
| `MCMTL_E_UNSUPPORTED` (-3) | format/feature/flag not supported | `IllegalArgumentException` |
| `MCMTL_E_COMPILE_FAILED` (-4) | MSL compile / PSO failure (log in last error) | `IllegalStateException` (pipelines: caller returns `Pending.NULL`) |
| `MCMTL_E_DEVICE_LOST` (-5) | device-loss class command-buffer error | `GpuDeviceLossException` |
| `MCMTL_E_TIMEOUT` (-6) | wait timed out (normal outcome) | `IllegalStateException` (callers usually handle it) |
| `MCMTL_E_BAD_STREAM` (-7) | malformed/misplaced record | `IllegalStateException` |
| `MCMTL_E_NO_DEVICE` (-8) | no Metal device / family missing | `IllegalStateException` (→ `BackendCreationException` in device creation) |
| `MCMTL_E_OBJC_EXCEPTION` (-9) | caught `NSException` (name + reason in last error) | `IllegalStateException` |
| `MCMTL_E_UNIMPLEMENTED` (-10) | skeleton stub | `UnsupportedOperationException` |
| `MCMTL_E_ABI_MISMATCH` (-11) | `structSize` mismatch | `IllegalStateException` |
| `MCMTL_E_SURFACE` (-12) | layer/drawable problem | `IllegalStateException` |
| `MCMTL_E_INTERNAL` (-99) | bug | `IllegalStateException` |

Every export body is `MCMTL_TRY_BEGIN … MCMTL_TRY_END(err)` (`mcmetal_internal.h`):
`@autoreleasepool { @try { … } @catch { record; return err; } }`. No Objective-C exception may unwind into
Java (the JVM aborts). No C++ in the shim. No upcalls into Java, ever. The `@catch` returns straight out of
the export without any cleanup: code that must restore invariants on an exception needs its own inner
`@try/@finally` (the submit path, §4.2). The shim is compiled with `-fobjc-arc-exceptions`, so ARC releases
strong locals (drawables, encoders, command buffers) during unwinding instead of leaking them.

### 3.3 Handles and ownership

A handle is a **+1 retained Objective-C object pointer** (`__bridge_retained`), 0 = null. Creation returns
+1 to Java; Java owns it until it passes it to `mcmtl_release`/`mcmtl_release_batch` (devices/surfaces:
their destroy function). Natives never release a handle they did not create a reference for; the decoder
borrows handles (`MCMTL_BUFFER(h)` etc.) and relies on the command buffer's retained references (M1) to
keep them alive until completion. Java releases only through the deferred FIFO (§5.5).

| Kind | Object behind the handle | Created by | Released by |
|---|---|---|---|
| device | `MCMTLDevice` (internal.h) | `mcmtl_device_create` | `mcmtl_device_destroy` |
| buffer | `id<MTLBuffer>` | `mcmtl_buffer_create` | `mcmtl_release[_batch]` |
| texture | `id<MTLTexture>` | `mcmtl_texture_create` | release |
| texture view | `id<MTLTexture>` (view, retains parent) | `mcmtl_texture_view_create` | release |
| texel view | `id<MTLTexture>` (texture buffer, retains buffer) | `mcmtl_texel_view_create` | release |
| sampler | `id<MTLSamplerState>` | `mcmtl_sampler_create` | release |
| shader function | `id<MTLFunction>` (keeps what it needs alive; handles of byte-identical MSL may alias one cached Metal object, refcounting stays exact, §16 #5) | `mcmtl_shader_create` | release |
| pipeline | `MCMTLPipeline` | `mcmtl_pipeline_create` | release |
| query pool | `MCMTLQueryPool` | `mcmtl_query_pool_create` | release |
| surface | `MCMTLSurface` | `mcmtl_surface_create` | `mcmtl_surface_destroy` |

`mcmtl_release(device, h)` takes the device so M3 can remove the object from the residency set; M1 just
`CFRelease`s. Wrapper classes are plain data holders with `@public` ivars (internal.h); their
`@implementation`s live in `mcmetal_common.m`, so tests can construct them directly.

### 3.4 Exports: owner, thread, blocking, critical

All exports may be called from any thread unless noted; "render" = the render/AppKit main thread.
Only the two marked **critical** are bound with `Linker.Option.critical(false)`; everything else is a
normal downcall (may block, lets GC safepoints proceed).

| Export | Owner | Thread | Blocks? | Critical |
|---|---|---|---|---|
| `mcmtl_abi_version` | arch | any | no | **yes** |
| `mcmtl_abi_struct_size`, `mcmtl_last_error` | arch | any | no | no |
| `mcmtl_probe` | arch | any (loadLibrary) | ms (device creation) | no |
| `mcmtl_device_copy_messages` | arch | any | no | no |
| `mcmtl_device_create` / `_destroy` | I1 | render | yes (ms / waits idle) | no |
| `mcmtl_device_get_info` | I1 | any | no | no |
| `mcmtl_timeline_completed` | I1 (trivial, done) | any | no | **yes** |
| `mcmtl_timeline_wait` | I1 (done: wraps `mcmtl_wait_completed`) | render | **yes** | no |
| `mcmtl_device_sample_timestamps` | I1 | render | no | no |
| `mcmtl_buffer_create`, `mcmtl_texture_create`, `mcmtl_texture_view_create`, `mcmtl_texel_view_create`, `mcmtl_sampler_create`, `mcmtl_query_pool_create` | I1 | render (thread-safe anyway) | short | no |
| `mcmtl_query_pool_resolve` | I1 | render | no (only completed samples) | no |
| `mcmtl_release`, `mcmtl_release_batch` | I1 (M1 versions done) | render | no | no |
| `mcmtl_shader_create`, `mcmtl_pipeline_create` | I1 | **compile workers, concurrent** | yes (ms) | no |
| `mcmtl_surface_create/configure/present/destroy` | I1 | render (AppKit main) | short | no |
| `mcmtl_submit` | arch (guard, common.m) → I2 (`mcmtl_decoder_submit`) | render | **yes** (`nextDrawable` ≤ ~1 s) | no |

### 3.5 Struct ids and verification

`mcmtl_abi_struct_size(id)` returns `sizeof` for every `MCMTL_STRUCT_*` (mcmetal.h) and
`MCMTL_STRUCT_CMD_*` (mcmetal_stream.h) id. `MetalBackend.loadLibrary()` calls `MtlNative.verifyAbi()`:
library ABI version must equal `MtlNative.ABI_VERSION` (now 2) and all 45 sizes must match, else
`BackendCreationException`. **Any change to either header bumps `MCMTL_ABI_VERSION`.**

### 3.6 Java mirror naming rule (checked by `tools/MetalSmoke.java`)

- `#define MCMTL_X v` → `MtlNative.X` (mcmetal.h) or `StreamOps.X` (mcmetal_stream.h);
  `MCMTL_CMD_SIZE_X` → `StreamOps.SIZE_X`. `UINT64_MAX` → `-1L`.
- `mcmtl_<name>.<field>` offset → `MtlNative.<NAME>_<FIELD>`; `mcmtl_cmd_<name>.<field>` →
  `StreamOps.<NAME>_<FIELD>`; field camelCase → UPPER_SNAKE (`([a-z])([A-Z])→$1_$2`, then
  `([A-Z]+)([A-Z][a-z])→$1_$2`), e.g. `sourceRGBBlendFactor` → `SOURCE_RGB_BLEND_FACTOR`.
- `sizeof` → `<NAME>_SIZEOF`; `StructLayout`s in `MtlNative.Layouts.<NAME>` / `StreamOps.Layouts.<NAME>`.
- Array fields: the constant is the offset of element 0 (`+4*i` for `uint32_t[]`, `+i*<ELEM>_SIZEOF` for
  struct arrays), e.g. `PIPELINE_DESC_BUFFER_MASK + 4 * stage`, `PIPELINE_DESC_COLOR_TARGETS + i * COLOR_TARGET_SIZEOF`.

The check runs in both directions: every header offset/size/define must exist in Java with the same value,
and every `public static final int/long` of `MtlNative`/`StreamOps` must have a header counterpart (no
stray or misspelled constants; helpers are methods, not constants). Current state: 368 offsets, 45 sizes,
180 defines verified forward; 593 Java constants verified backward (see `tools/metal-smoke.sh` output).

---

## 4. Command stream (`native/include/mcmetal_stream.h`)

### 4.1 Format

- A stream is a byte range of records. Every record starts at a 16-byte aligned offset with
  `{u16 op; u16 size; u32 <named operand>}`; Java writes these 8 bytes as one
  `memPutLong(addr, StreamOps.header(op, size, arg))`.
- `size` = total record bytes incl. header, multiple of 16, 16..65520, ≥ the op's size in the table.
  Padding bytes are 0 (StreamWriter zero-fills). The decoder advances by `size` and ignores padding.
- Little-endian, natural alignment. Variable payloads (labels, push-constant bytes) follow the fixed fields
  inline and are copied at record time (callers free their buffers immediately). Bulk data
  (`writeToBuffer`/`writeToTexture` sources) is copied by Java into transient staging memory and referenced
  by a `COPY_*` record.
- Resources are referenced by **handle only** (no gpuAddress / resourceID in records; an M3 decoder reads
  `gpuAddress`/`gpuResourceID` from the objects).
- Op classes: 1..15 encoder-level (only outside a pass) **except `END_PASS` (2), which is only valid inside a
  pass** (§16 #10; `StreamOps.isEncoderOp` excludes it), 16..31 anywhere, 32..63 only between `BEGIN_PASS` and
  `END_PASS`. `MCMTL_OP_NOP` (0) is padding of any size.

### 4.2 Streams, submits, flushes

- `mcmtl_submit_info` carries two streams: **init** (decoded first) and **main**. Java keeps one
  `StreamWriter` for each. The init stream may contain **only `INIT_TEXTURE` (and `NOP`)**: it runs before
  the whole main stream, so any other op there would execute ahead of earlier main-stream work on the same
  resources. The decoder rejects anything else in the init stream with `MCMTL_E_BAD_STREAM`
  (`StreamOps.isInitStreamOp`). `INIT_TEXTURE` is also accepted in the main stream, where it executes in
  stream order (Java never records it there in M1; §16 #14).
- The exported `mcmtl_submit` is the architect's **guard** (`mcmetal_common.m`); it validates, then calls
  the I2 decoder `mcmtl_decoder_submit` (internal.h) inside `@try`. One call = decode init then main into the
  device's current command buffer (`dev->currentCommandBuffer`, created on demand by the decoder), end every
  encoder it opened, then — with `MCMTL_SUBMIT_FLAG_COMMIT` — `mcmtl_commit_signaled(dev, cb, signalValue)`
  (= `encodeSignalEvent`, `mcmtl_track_command_buffer`, `commit`, then `status->submittedValue =
  signalValue`), `COMMITTED` flag, `currentCommandBuffer = nil`.
- Without COMMIT the work stays in the open command buffer for the next call (M1 Java always commits; the
  mode exists for tests and future streaming). The open command buffer never holds an open encoder between
  calls.
- A stream passed to one call MUST NOT end inside a pass (Java only flushes outside passes). The native
  side treats an unterminated pass as `MCMTL_E_BAD_STREAM` (ends the encoder, still commits).
- Callers of COMMIT: `MetalCommandEncoder.submit()` (flags `COMMIT | FRAME_END`, once per frame) and
  `MetalCommandEncoder.flushForWait(seq)` (flags `COMMIT`, §5.4). `signalValue` is always
  `timeline.pendingSeq()`, i.e. exactly the previous COMMIT's value + 1 (first = 1).
- The decoder reads the stream synchronously; after `mcmtl_submit` returns Java may reset and reuse both
  writers immediately. Natives never keep pointers into a stream.
- **Signal-value consumption rule (normative, mcmetal.h).** When `device` and `info` are non-NULL and the
  flags have COMMIT, **every** return path of `mcmtl_submit` — success, decode errors, validation errors
  (bad `structSize`, NULL or bad `outResult`, unknown/ASYNC flags, stream length not a multiple of 16,
  `signalValue != submittedValue + 1`) and exceptions — consumes `signalValue` exactly once: a committed
  command buffer signals it, or it is completed on the CPU (`mcmtl_submit_abort` →
  `mcmtl_complete_value_on_cpu`). On return `submittedValue ≥ signalValue` and no command buffer is open.
  `MCMTL_SUBMIT_RESULT_FLAG_COMMITTED` is set only for a real GPU commit. How it is enforced:
  - the guard validates first and CPU-completes the value on any validation error;
  - a `signalValue` that is not `submittedValue + 1` (Java/native divergence) is rejected before decoding
    with `MCMTL_E_INVALID_ARGUMENT` (CPU-completed if ahead; a reused value changes nothing), so a
    divergence surfaces at once instead of freezing the completed prefix;
  - after the decoder returns or throws, the guard checks `submittedValue ≥ signalValue`; if not, it
    calls `mcmtl_submit_abort` (drops the command buffer uncommitted, CPU-completes the value), clears
    `COMMITTED`, and turns `MCMTL_OK` into `MCMTL_E_INTERNAL`; on every error it sets
    `currentCommandBuffer = nil`;
  - I2 MUST structure the decoder as `@try { decode } @catch { record error } @finally { end open encoders,
    each inside its own @try; an encoder that cannot be ended poisons its command buffer: abandon it
    uncommitted and, for COMMIT, commit a fresh command buffer that only signals (or call
    mcmtl_submit_abort); COMMIT: mcmtl_commit_signaled }` (internal.h). The guard is the backstop, not
    the plan: the decoder still commits the work it encoded whenever it can.
- **Decode errors still commit**: the bad record and the rest of that stream are skipped, encoders are
  ended, the command buffer is committed with its signal, and the error is returned with `errorOffset`
  (+ `MCMTL_SUBMIT_RESULT_FLAG_ERROR_IN_INIT_STREAM` if it was the init stream).
- **Early commit at PRESENT (§8.2):** the decoder MAY (and in M1 SHOULD, when COMMIT is requested and PRESENT
  is not the first record that encodes GPU work) commit the work encoded so far as a separate command
  buffer **without** a timeline signal (`mcmtl_commit_untimed`, errors still reported through the status
  page) just before `nextDrawable`, so the GPU runs the frame while the CPU waits for a drawable. The
  value-signalling command buffer is always the last one committed by the call.
- The guard also fills `result->completedValue`, `cpuNanos` and the `DEVICE_LOST` flag; the decoder fills
  everything else.

### 4.3 Results reported back

`mcmtl_submit_result` (Java zero-fills it and sets `structSize`):

| Field | Meaning | Java reaction (I4) |
|---|---|---|
| `flags & COMMITTED` | a real GPU commit signalled the value | `timeline.onCommitted(seq)` (call it whenever COMMIT was requested, whatever the status: the value was consumed either way, §4.2, §5.2) |
| `flags & PRESENT_ENCODED` | drawable acquired, present pass encoded, `presentDrawable:` registered | nothing (surface `present()` later releases it) |
| `flags & DRAWABLE_NIL` | `nextDrawable` returned nil; present skipped | `surface.onPresentResult`: suboptimal + eaten `SurfaceException("nextDrawable returned nil")` |
| `flags & DRAWABLE_RESIZED` | drawable size ≠ configured size | surface suboptimal (edge-triggered) |
| `flags & DEVICE_LOST` | status page reports device loss | `timeline.checkErrors()` throws `GpuDeviceLossException` |
| `flags & DRAWS_SKIPPED`, `drawsSkipped` | draws skipped (missing vertex/index buffer, variant failure) | log once per frame type (debug) |
| `completedValue` | status-page completed value at return | informational |
| `recordsDecoded/drawsEncoded/encodersCreated/cpuNanos/drawableWidth/Height` | diagnostics | optional F3/debug logging |

GPU-side errors arrive asynchronously in the status page (§5.3), never through `mcmtl_submit`.

### 4.4 Record reference

Sizes in bytes; "S" = Java source call; "D" = decoder (I2) behaviour in M1. All offsets are in the header
and `StreamOps`; field names below are the C names.

**Encoder-level records (outside a pass)**

| Op | Size | Fields | S → D |
|---|---|---|---|
| `BEGIN_PASS` (1) | 48 + 32·n | `colorCount` n (0..8, null slots included), `flags` (`PASS_FLAG_DEPTH_CLEAR`), `colorClearMask` (bit i = Clear), render area `renderX/Y/Width/Height`, `depthClearValue` (float), `depthLevel`, `depthTexture` (0 = none), then n × `{texture (0 = unused slot), level, reserved, clearColor[4]}` | S: `createRenderPass` (views → `texture().handle()` + `baseMipLevel()`; clears from the descriptor; render area as given). D: `MTLRenderPassDescriptor` with parent texture + `.level`, slice 0; Clear/Load; store = Store (M2: Unknown + late decision); when the depth format has a stencil aspect (`Depth32Float_Stencil8`, …) the same texture/level is also the `stencilAttachment` (loadAction Clear with clearStencil 0 when depth is cleared, else Load; store Store), matching the PSO variant's stencil format (§7.3); if a clear is requested but the render area does not cover the attachment: Load + scissored clear-quad (spec §2.3); viewport = full attachment (Metal default); scissor = render area when partial; new render encoder; label = innermost open command-buffer debug group when labels are on. |
| `END_PASS` (2) | 16 | `flags` (0, reserved for store hints) | S: `submitRenderPass`. D: `endEncoding` (M2 may defer for merging). |
| `CLEAR_TEXTURE` (3) | 48 | `aspects` (`ASPECT_COLOR/DEPTH/STENCIL`), `texture`, `baseLevel`, `levelCount`, `slice`, `depth`, `color[4]` | S: `clearColorTexture` (all mips, color), `clearDepthTexture` (all mips, depth), `clearColorAndDepthTextures` (two records). D (M1): one empty render pass per level with loadAction Clear / store Store. M2: fold into the next pass's load action (spec §9.1 B3). |
| `CLEAR_REGION` (4) | 64 | `level`, `colorTexture`, `depthTexture`, `x,y,width,height` (memory rows, no flip), `color[4]`, `depth` | S: region `clearColorAndDepthTextures`. D: full-level region → like CLEAR_TEXTURE; else Load pass + `setScissorRect` + full-viewport clear-quad (color + `[[depth(any)]]`, depth compare Always, write on). |
| `COPY_BUFFER` (5) | 48 | `srcBuffer`, `srcOffset`, `dstBuffer`, `dstOffset`, `length` | S: `copyToBuffer`; `writeToBuffer` slow path (staging → dst). Offsets include `buffer.backingOffset()`. Same-buffer overlapping ranges: Java stages through transient memory. D: blit `copyFromBuffer:…` in the shared blit encoder (split on hazards, §4.5). |
| `COPY_BUFFER_TO_TEXTURE` (6) | 64 | `level`, `buffer`, `bufferOffset`, `texture`, `bytesPerRow`, `bytesPerImage`, `slice`, `x,y,width,height`, `blitOptions` | S: `writeToTexture` (staging, rows `w·bs`), `copyBufferToTexture` (`bufferOffset = src.offset + (sy·sW + sx)·bs`, `bytesPerRow = sW·bs`, `bytesPerImage = sW·ch·bs`; `bs` = bytes per texel of the copied plane: `blockSize`, except 4 for `D32_FLOAT_S8_UINT`, whose `DepthFromDepthStencil` copies the depth plane only, §16 #22). D: `copyFromBuffer:…toTexture:… destinationSlice:slice destinationLevel:level destinationOrigin:{x,y,0}`, size `{width,height,1}`, `sourceBytesPerImage = bytesPerRow·height` (never past the copied rows, §16 #24). |
| `COPY_TEXTURE_TO_BUFFER` (7) | 64 | same layout as 6 | S: both `copyTextureToBuffer` overloads (`bytesPerRow = w·bs`, `bytesPerImage = w·h·bs`, `blitOptions = MetalFormats.blitOptions(format)`) + `timeline.defer(callback)`. D: `copyFromTexture:…toBuffer:…`. |
| `COPY_TEXTURE` (8) | 64 | `flags` (`COPY_FLAG_DRAW`), `srcTexture`, `dstTexture`, `srcLevel`, `dstLevel`, `srcSlice`, `dstSlice`, `srcX,srcY,dstX,dstY,width,height` | S: `copyTextureToTexture` (same mip on both, slice 0). Formats equal → blit; formats differ → `COPY_FLAG_DRAW` if dst has RENDER_ATTACHMENT, else Java throws IAE. D: blit copy, or nearest-sampling draw into dst (Load pass, scissor to the dst rect). |
| `PRESENT` (9) | 32 | `flags` (0; M4 scaling modes reserved), `surface`, `texture` (parent of the view), `level` (`view.baseMipLevel()`) | S: `MetalSurface.blitFromTexture` unless the frame is dropped. D: §8.2. |
| `INIT_TEXTURE` (10) | 16 | `mode` (`INIT_TEXTURE_ZERO`), `texture` | S: `MetalTexture.create` via `encoder.recordTextureInit` (init stream; the only op allowed there). D: zero every level/slice with blit copies from a zero buffer in row bands (or a clear pass if RenderTarget). |
| `UPSCALE` (11) | 64 | `mode` (`UPSCALE_MODE_LINEAR/NEAREST/METALFX_SPATIAL`), `srcTexture`, `srcLevel`, `dstLevel`, `dstTexture`, `srcX/Y/W/H`, `dstX/Y/W/H` (memory rows) | **M4, reserved now so render scale needs no ABI change.** S: never recorded in M1 (later: a game-side hook for spec G1, world target → native-resolution target before the GUI pass). D (M1): rejected like a bad record (`MCMTL_E_UNSUPPORTED`, rest of the stream skipped, COMMIT honored). D (M4): ends the blit encoder; LINEAR/NEAREST = scaled draw (Load pass on dst, scissor to the dst rect); METALFX_SPATIAL = `MTLFXSpatialScaler` when `FEATURE_METALFX_SPATIAL`, else LINEAR. The decoder owns every MetalFX object and intermediate (scaler per (formats, sizes), an output texture with the usage MetalFX needs, then a copy/draw into `dstTexture`); Java textures keep `MetalFormats.textureUsage`. |

**Records valid anywhere**

| Op | Size | Fields | S → D |
|---|---|---|---|
| `TIMESTAMP` (16) | 16 | `index`, `queryPool` | S: encoder- or pass-level `writeTimestamp` after Java bounds-checks `index < size` (frontend is off by one) + `pool.markWritten(index, pendingSeq)`. D: classic sampling is stage-boundary only on M5 (spec §2.13): attach to the next pass's `startOfVertexSampleIndex` if the next op is `BEGIN_PASS`, to the previous pass's `endOfFragmentSampleIndex` if it directly follows `END_PASS` and the encoder end is still pending (both adjacency tests skip `NOP`, `PUSH_DEBUG_GROUP` and `POP_DEBUG_GROUP` records, so labels do not change where a timestamp lands), inside a pass to that pass's `endOfFragmentSampleIndex`, otherwise a standalone blit pass with `sampleBufferAttachments`. A TIMESTAMP between two passes forbids merging them (M2). Measured on M5 (§16 #11, #12): an *empty* blit/compute pass resolves to 0, so the standalone pass contains one 4-byte `fillBuffer` on a decoder scratch buffer; only one query pool can attach to a render pass (the highest-indexed attachment slot is the only one sampled), so the first pool attaches with up to 4 points (start: startOfVertex then startOfFragment; end: endOfFragment then endOfVertex) and other pools are sampled in standalone blit passes right before/after the pass. Runs of consecutive timestamps (labels skipped) are handled as a unit: a run ending at `BEGIN_PASS` samples that pass's start; a run directly after `END_PASS` samples that pass's end, except its last timestamp when the run also ends at `BEGIN_PASS` (Tracy's end-A/begin-B pattern). |
| `PUSH_DEBUG_GROUP` (17) | align16(8 + len) | `labelLength`, UTF-8 bytes at +8 (≤ 1024, no NUL) | S: only when `device.debugLabels()`: pass label before `BEGIN_PASS`, `RenderPass.pushDebugGroup` inside. D: outside a pass first end the open blit encoder (Metal does not allow command-buffer debug groups while an encoder is active; only happens with labels on), then `[commandBuffer pushDebugGroup:]`; inside `[encoder pushDebugGroup:]`. |
| `POP_DEBUG_GROUP` (18) | 16 | — | S: pass end (after `END_PASS`), `popDebugGroup`. D: matching pop (outside a pass: end the open blit encoder first, as for push). |

**Pass-level records (between BEGIN_PASS and END_PASS)**

| Op | Size | Fields | S → D |
|---|---|---|---|
| `SET_PIPELINE` (32) | 16 | `pipeline` | S: `setPipeline` (+ Java clears its per-index `last`/set state; setting the pipeline that is already bound in the same pass records nothing and keeps that state, §16 #20). D: variant for the pass signature (§7.3); apply PSO, depth-stencil state, cull, winding, fill mode, depth bias **each only on change**; in a pass without a depth attachment the depth-stencil state is always the Always/no-write state (`mcmtl_device_depth_stencil_state(dev, Always, NO)`), never the pipeline's (Metal validation rejects a depth test without a depth attachment; Vulkan has no depth test there either; §16 #4); clear `bufferSetMask/textureSetMask`. Variant failure → skip draws until the next SET_PIPELINE, message once. |
| `SET_BUFFER` (33) | 32 | `index` (Metal buffer 0..13), `stages`, `buffer`, `offset` | S: UNIFORM_BUFFER `setUniform`. D: per stage: same buffer → `set{Vertex,Fragment}BufferOffset:` only if the offset changed; else `set…Buffer:offset:atIndex:`; mark set. |
| `SET_TEXTURE` (34) | 32 | `index` (texture/sampler 0..15), `stages`, `texture`, `sampler` (0 = none) | S: COMBINED_IMAGE_SAMPLER (`view.samplingHandle()`, `sampler.handle()`), TEXEL_BUFFER (`buffer.texelView(...)`, sampler 0). D: `set…Texture:`/`set…SamplerState:` only on change; mark set. |
| `CLEAR_BINDING` (35) | 16 | `index`, `stages`, `kind` (`BINDING_KIND_BUFFER/TEXTURE`) | S: `setUniform(i, null)`. D: clear the set bit (dummy before the next draw if used). |
| `SET_VERTEX_BUFFER` (36) | 32 | `slot` (0..15), `buffer`, `offset` | S: non-null `setVertexBuffer` (null records nothing: keep previous). D: vertex stage `[[buffer(30 - slot)]]`, offset-only change → `setVertexBufferOffset:`. |
| `SET_INDEX_BUFFER` (37) | 16 | `indexType` (`MTLIndexType`), `buffer` | S: `setIndexBuffer` (whole buffer, offset 0). D: remember for draws. |
| `SET_SCISSOR` (38) | 32 | `x, y, width, height` | S: `enableScissor` (frontend validated). D: `setScissorRect:` unchanged (no flip), on change. |
| `RESET_SCISSOR` (39) | 16 | — | S: `disableScissor`. D: scissor = render area. |
| `PUSH_CONSTANTS` (40) | 16 + align16(len) | `byteLength`, `stages`, bytes at +16 | S: `pushConstants` copies `pipeline.pushConstantSize` bytes, `stages = pipeline.pushConstantStages`. D: `set{Vertex,Fragment}Bytes:length:atIndex:14`. |
| `DRAW` (48) | 32 | `vertexCount`, `instanceCount`, `firstVertex`, `firstInstance` | D: `drawPrimitives:…baseInstance:`; fan pipelines: indexed draw through the device fan index buffer `[0,1,2, 0,2,3, …]`, `indexCount 3(n−2)`, `baseVertex firstVertex`; skip n < 3. |
| `DRAW_INDEXED` (49) | 32 | `indexCount`, `instanceCount`, `firstIndex`, `baseVertex` (signed), `firstInstance` | D: `drawIndexedPrimitives:…indexBufferOffset:firstIndex·indexSize…baseVertex:baseInstance:`; no index buffer → skip (message once); fan pipelines: read indices (Shared) at decode, write `(i0, ik, ik+1)` triples into the decoder upload ring, draw as triangles. |
| `DRAW_INDIRECT` (50) | 32 | `drawCount`, `buffer`, `offset` | D: loop `drawPrimitives:indirectBuffer:indirectBufferOffset:offset + 16i`. |
| `DRAW_INDEXED_INDIRECT` (51) | 32 | same | D: loop `drawIndexedPrimitives:…indirectBuffer:…offset + 20i` (M1); MAY CPU-decode the Shared records; ICB ranges (cached CPU-encoded commands) for groups of ≥ 64 records since M2 round 3 (§16.5). |

Before **every draw** the decoder applies §6.4 (dummies) and skips draws whose pipeline requires a vertex
buffer index that was never bound in this pass (`requiredVertexBufferMask`).

### 4.5 Decoder state across records (I2)

- One render encoder per pass (M1). Transfer records between passes share **one blit encoder**, opened
  lazily and ended before the next render pass / clear pass / PRESENT / UPSCALE, before a command-buffer
  debug group (labels on) and at stream end.
- **Intra-encoder hazards (normative):** Metal tracks hazards between encoders; ordering of dependent blits
  inside one blit encoder is not something to rely on. The decoder keeps a small per-blit-encoder read set
  and write set (reset when the encoder ends) — at handle granularity or finer: I2 tracks byte ranges of
  buffers and (level, slice, rect) of textures, coalescing adjacent ranges (§16 #13) — and ends/reopens the
  blit encoder before a record that reads or writes a region written earlier in it, or writes a region read
  earlier in it (e.g.
  `writeToBuffer` slow path staging→X followed by `copyToBuffer` X→Y; texture zero-fill followed by an
  upload). Vanilla rarely triggers a split; `decoder_test` covers both cases (§11.2).
- Shadow state (spec §3.4, `mcmtl_decode_ctx` in internal.h) is per render encoder; bindings never persist
  across passes; `SET_PIPELINE` resets only the set masks, not the Metal bindings (that is the point of the
  fixed global slots).
- Validation: unknown op, `size` not a multiple of 16 / too small / past the end, pass op outside a pass,
  encoder op inside a pass → `MCMTL_E_BAD_STREAM` + skip the rest of that stream (§4.2).

### 4.6 What may and may not change in later phases

Frozen for M1–M4: every record layout above (including the reserved `UPSCALE`), opcode numbers, header
packing, the two-stream model, the COMMIT/signal and consumption rules. Reserved for later without
breaking the ABI: `END_PASS.flags` (store hints, G11), `PRESENT.flags` (whole-frame scaling modes),
`UPSCALE` (mid-frame render scale / MetalFX, G1: world at 0.67–0.75, GUI native), `mcmtl_pipeline_desc.flags`
bit `SUPPORT_ICB`, `MCMTL_DEVICE_FLAG_MTL4/UNTRACKED`, `MCMTL_SUBMIT_FLAG_ASYNC`, all `reserved` fields.
New ops get new numbers (< 64) and bump the ABI version.

### 4.7 Look-ahead (M2+) is a decoder concern

Clear folding, pass merging, late store actions, upload hoisting and ICB building are pure decoder
optimizations over the same records (the decoder sees the whole call before encoding). Java never
changes for them. Hazards to respect are listed in spec §9.1 B3–B6.

**Look-ahead state never outlives one `mcmtl_submit` call** (pending clears, deferred store actions,
merged/open encoders, hoisted uploads): everything is materialized before the call returns, whatever the
`FRAME_END` flag says. A frame may be split across several calls (flush-on-wait), and work committed by a
later call must never be needed to complete an earlier one.

---

## 5. Synchronization and lifetime

### 5.1 Timeline

- One `MTLSharedEvent` per device, `signaledValue` 0 at creation. Java `pendingSeq` starts at 1
  (`MetalTimeline.FIRST_SEQ`). **Every COMMIT signals exactly `pendingSeq`**, then
  `timeline.onCommitted(seq)` makes `pendingSeq = seq + 1`. Values are dense: 1, 2, 3, ….
- `status->completedValue` is the **contiguous completed prefix** (largest v with every value ≤ v
  completed), maintained by `mcmtl_track_command_buffer`'s completion handler (common.m) and therefore
  correct even if command buffers complete out of order. There is no window limit (out-of-order values
  wait in an index set): the prefix never skips a value whose work may still run, however many commits
  are outstanding (the queue's 64-command-buffer limit stalls `commandBuffer` creation long before memory
  matters). `mcmtl_timeline_completed` returns the same number (critical downcall).
- The event is raised by GPU signals and, from the CPU, **only to a contiguous completed prefix** (failed
  command buffer, CPU completion): it never runs ahead of a value whose work may still execute.
- `mcmtl_timeline_wait` / `mcmtl_wait_completed` return `MCMTL_OK` when the prefix reaches the value **or as
  soon as `event.signaledValue ≥ value`** (the GPU executed that signal; command buffers of one queue
  execute in commit order, spec §1.5 (a)) — without waiting for the completion handler, whose latency would
  otherwise be paid on every frame throttle and fence wait. They wait on the event in 10 ms slices and also
  return on timeout, on device loss, or immediately with `E_INVALID_ARGUMENT` for a value never committed.
- Java's `completedSeq()` = `max(acquire load of completedValue, largest seq a successful await() returned
  for)` (no downcall; `Long.MAX_VALUE` once the timeline is closed, §5.3).

### 5.2 Frame submit sequence (I4, `MetalCommandEncoder.submit()`)

1. `isInRenderPass()` → `IllegalStateException` (frontend does not check).
2. `seq = timeline.pendingSeq()`; `mcmtl_submit(init + main, COMMIT | FRAME_END, signalValue = seq)`.
3. `timeline.onCommitted(seq)` **even if the status is an error**: the native side consumed `seq` on every
   path (§4.2 consumption rule). Only `mcmtl_submit` with a NULL device/info could skip it, which Java never
   does.
4. Result flags → `surface.onPresentResult(...)` if a PRESENT was recorded since the last commit.
5. Reset both streams; `transientMemory.endFrame(seq)`.
6. Frame throttle (2 in flight): `timeline.await(previousFrameSeq, FRAME_WAIT_TIMEOUT_NS)`; false →
   `IllegalStateException("5s timeout reached when waiting for the GPU")`. `previousFrameSeq` = the seq of the
   previous `submit()` (0 for the first frame).
7. `timeline.checkErrors()` (throws `GpuDeviceLossException` on device loss); `timeline.drain()`.
8. Then rethrow a deferred `mcmtl_submit` error (`MtlNative.exceptionFor`).

With `-Dmcmetal.lateThrottle=true` step 6 is only armed and taken later, with the same guarantee (§16.R5-A).

### 5.3 Status page

`mcmtl_status_page` (64 bytes) lives in native memory from `mcmtl_device_create` until
`mcmtl_device_destroy` (freed with the device); address in `mcmtl_device_info.statusPage`. Java:
`MtlNative.statusPage(addr)`, then `MtlNative.statusLong/statusInt(page, STATUS_PAGE_*)` (acquire loads; pure
helpers, no library needed). Natives write it only with `__atomic` builtins (release).
`errorCount`/`messageCount` are monotonic: Java compares with its last seen value and, when they changed,
logs `mcmtl_device_copy_messages` output (I3 `checkErrors`). `deviceLost` is sticky (set for Timeout,
PageFault, DeviceRemoved, AccessRevoked, Internal). A failed command buffer raises the event to the new
contiguous prefix so no wait hangs (§5.1).

**Error policy (spec 2.3 submit step 6).** `MetalTimeline.checkErrors()` (submit, fence waits): when
`errorCount` grew, it records the new count first (each failure surfaces once), logs the message text, then
throws `GpuDeviceLossException` if `deviceLost`, `GpuOutOfMemoryException` if `lastErrorCode` is
`MTLCommandBufferErrorOutOfMemory` (8), else `IllegalStateException` with the message text (InvalidResource,
NotPermitted, … — frames do not silently continue with corrupted results). A sticky `deviceLost` throws on
every call. A grown `messageCount` alone (decoder warnings) is only logged.

**After `mcmtl_device_destroy`** Java never reads the status page and never passes the device handle again.
`MetalTimeline.close()` (called by `MetalDevice.close()` right before `mcmtl_device_destroy`) enters a closed
state: `completedSeq()` returns `Long.MAX_VALUE`, waits return true immediately, `deferRelease` drops the
handle (a deliberate leak: the device is gone; M3's `mcmtl_release_batch` would dereference it),
`defer`/`deferUntil` run the action immediately, `checkErrors()` does nothing.

### 5.4 Fences (I3, spec §1.5)

`createFence()` → `new MetalFence(device, timeline.pendingSeq())`. `awaitCompletion(t)` (ns, -1 forever):
closed or `seq ≤ completedSeq()` → true (and `timeline.drain()`); `seq == pendingSeq()`: t == 0 → false,
else **flush-on-wait**: `encoder.flushForWait(seq)` (commits everything recorded so far with COMMIT only;
throws `IllegalStateException` if a render pass is open), then wait; else t == 0 → false, t > 0 →
`timeline.await(seq, t)`, t < 0 → wait forever. `close()` marks it complete. `timeline.checkErrors()` runs
after blocking waits only (t ≠ 0); polls and already-complete fences stay a field compare, so a GPU error
then surfaces at the next submit (§16 #18).

### 5.5 Deferred-release FIFO (I3)

Entries `(seq, action)` with seq = `pendingSeq()` at insertion (or an explicit later value via
`deferUntil`). Drained in `submit()` (after the throttle wait) and opportunistically in fence polls; every
entry with `seq ≤ completedSeq()` runs **in FIFO order on the render thread**; native handles collected by
one drain are released with a single `mcmtl_release_batch` after that drain's callbacks.

| What | Who defers | When it runs |
|---|---|---|
| buffer/texture/view/texel-view/sampler/query-pool/pipeline native release | `close()` of the Java object (texture: when texture and all views closed; sampler: when the shared state's refcount hits 0) | GPU passed the tag |
| `copyTextureToBuffer` callback | I4 encoder | after the copy's commit completed |
| staging/GPU transient blocks | `transientMemory.endFrame(seq)` → `deferUntil(seq, rotate)` | frame `seq` completed |
| pooled `MTLBuffer` return (optional) | `MetalBuffer.close()` | GPU passed the tag |
| shader functions | never (owned by the cache until device close) | device close |

### 5.6 Device loss

Raised on the render thread only: `timeline.checkErrors()` (submit, fence waits) and `MCMTL_E_DEVICE_LOST`
from waits → `GpuDeviceLossException`; non-loss command-buffer errors → `GpuOutOfMemoryException` /
`IllegalStateException` (§5.3). Never from a completion handler.

### 5.7 Shutdown order (spec §1.3, §1.6)

`MetalSurface.close()` (wait `timeline.lastCommittedSeq()`, `mcmtl_surface_destroy`,
`SDL_Metal_DestroyView`) → … → `MetalDevice.close()`:
1. `markClosedAndAwaitCompiles(COMPILE_DRAIN_TIMEOUT_NS = 10 s)`: `closed = true` (later `compilePipeline`
   → `Pending.NULL`), then wait until no compile worker is between `beginCompile()` and `endCompile()`.
   Compile workers can still run here (spec §1.3, §10.4 #8: `Util.shutdownExecutors()` comes after
   `DEVICE.close()`, e.g. quitting during a resource reload); without this wait a worker that passed the
   closed check would call `mcmtl_shader_create`/`mcmtl_pipeline_create` on a destroyed device. On timeout:
   log, finish the Java-side shutdown, but skip the shader cache's native releases and
   `mcmtl_device_destroy` (leak rather than crash).
2. `encoder.shutdown()` (discard unsubmitted streams, even with a pass left open), wait for
   `lastCommittedSeq()`, `timeline.drainAll()`, close transient memory, shader cache, sampler cache,
   `timeline.close()` (§5.3), `mcmtl_device_destroy` (natives: wait idle, `mcmtl_decoder_device_shutdown`,
   release). There is no final submit. `close()` is idempotent.

---

## 6. Bindings and dummies

### 6.1 Metal index layout (fixed decision, spec §3.2)

| Resource | Metal index |
|---|---|
| UBO | `[[buffer(n)]]`, n 0..13: `Projection`→0, `Globals`→1, `Fog`→2, `Lighting`→3, `DynamicTransforms`→4, others dense from 5 in uniform-index order |
| push constants | `[[buffer(14)]]` |
| vertex buffer slot s (0..15) | `[[buffer(30 − s)]]` |
| combined image sampler | `[[texture(t)]]` + `[[sampler(t)]]`: `Sampler0/1/2`→0/1/2, others dense from 3 |
| texel buffer | `[[texture(t)]]` in the same space, no sampler |

Fallback, decided per kind on the **assigned indices** (not on counts — 10 non-fixed UBOs already reach
slot 14, the push-constant buffer; Sampler0/1/2 plus 14 others reach texture 16): if any hybrid UBO index
is > 13, assign all UBOs fully dense 0..13 in uniform-index order, and fail the compile if that still
exceeds 13; if any hybrid texture/texel-buffer index is > 15, assign them fully dense 0..15, and fail if that
still exceeds 15. Vanilla never needs the fallback.

Both stages use the same index for a binding; a binding absent from a stage's MSL is never bound there.

### 6.2 Java side (I4 using I5's tables)

`MetalRenderPipeline` exposes per uniform index i: `uniformKind[i]`, `uniformSlot[i]`, `uniformStages[i]`
(MCMTL_STAGE bits of stages whose MSL uses it; 0 → drop), `texelPixelFormat[i]`, `texelBytesPerTexel[i]`,
`usedUniformMask`. `MetalRenderPass.setUniform(i, v)`: index ≥ `uniformCount` or `uniformStages[i] == 0` →
ignore; `v == null` → `CLEAR_BINDING` (once); else drop exact repeats (slice: same buffer **identity** and
offset; `TextureViewAndSampler`: same view and sampler identity) of the value last recorded at i since
`setPipeline`; else record (buffer records: `buffer.handle()` and `buffer.backingOffset() + offset`, read at
record time, §9.3). Dispatch on `uniformKind[i]`, never `instanceof`. Mark buffers used
(`markUsed(pendingSeq)`), including texel buffers. No allocation, no hash maps, and no native calls except
the first `MetalBuffer.texelView()` per (buffer, format), which creates the view (I3 MAY create the R8Sint
view eagerly at buffer creation for `USAGE_UNIFORM_TEXEL_BUFFER` buffers; vanilla has three such buffers).

### 6.3 Native side (I2)

Shadow per stage and index (`mcmtl_decode_ctx`): bound buffer + offset, texture, sampler; `set` masks
since the last `SET_PIPELINE`. Redundant Metal calls are filtered; offset-only buffer changes use
`set…BufferOffset:`.

### 6.4 Dummy policy (spec §3.8)

Before each draw, for each stage s: every index in `pipeline->bufferMask[s]` (UBOs only: indices 0..14)
not set since `SET_PIPELINE` gets `dev->dummyBuffer` (`MCMTL_DUMMY_UNIFORM_BYTES` = 64 KiB of zeros: the usual
GL/Vulkan uniform-buffer range limit for the same RenderPearl shaders, far above any vanilla UBO, so a
missing UBO of any portable size passes the debug layer's buffer-length checks; spec §3.8 said 256 bytes); every index in `textureMask[s]`
not set gets `dummyTextureCube` if in `textureCubeMask[s]`, `dummyTexelBuffer` if in `texelBufferMask[s]`,
else `dummyTexture2D`, plus `dummySampler` if in `samplerMask[s]`; push-constant index 14 without data →
zero bytes. Vertex buffer indices (15..30) are never dummied: a missing required vertex buffer skips the
draw (message once). Dummies are created by `mcmtl_decoder_device_init` (I2) and stored on `MCMTLDevice`.

### 6.5 Strict mode

`-Dmcmetal.strictBindings=true` (`MetalDevice.STRICT_BINDINGS`): I4 checks before each draw that
`(pipeline.usedUniformMask & ~setMask) == 0`, else `IllegalStateException("Missing uniform <name>")`.

---

## 7. Pipelines

### 7.1 Compile flow (I5, worker threads, never throws, never needs the render thread)

`MetalDevice.compilePipeline(info)` (implemented): `if (!beginCompile()) return Pending.NULL; try { return
MetalRenderPipeline.compile(this, info); } finally { endCompile(); }` — `beginCompile` increments an
`AtomicInteger` and backs out if the volatile `closed` flag is set, and `close()` waits for the count to drain
(§5.7), so no compile can touch a destroyed device (no check-then-act race). Test harnesses that call
`compile` directly bracket it the same way (or use `MetalDevice.forTesting`). Inside `compile`:

1. `MslTranslator.assignBindings(info.uniforms())` (§6.1).
2. Per stage: `MslTranslator.translate(module, uniforms, table, pushConstantsSize)` with the spec §3.3
   options; only `module.spv()`/`type()`; collect masks (`spvc_compiler_msl_is_resource_used`), cube/texel
   kinds from the SPIR-V types, push-constant use.
3. `ShaderCache`: key = SHA-256 of the generated MSL + stage + shader flags/language version — exact, so
   pipelines that share a module but differ in uniforms that module does not declare share one library
   (spec dedupe target: ~125 programs / ~120+121 libraries for 195 pipelines). Translation runs before the
   lookup and MAY be memoized by (SHA-256(spv), stage, `OPTIONS_VERSION`, the (uniform index, kind, slot)
   tuples of the uniforms the module declares, push constants used). Value = `CompletableFuture<Long>` of a
   `mcmtl_shader_create` function handle (MSL 3.2 = `LANGUAGE_VERSION_3_2`, entry `main0`); concurrent
   compiles of one key are deduplicated. `ShaderCache.close()` is only called after every compile left
   (`markClosedAndAwaitCompiles` returned true, §5.7, §9.6); on a timeout the cache is not closed and its
   native handles leak. A handle created after `close()` (unreachable in M1) is released immediately instead
   of inserted (§16 #17).
4. Fill `mcmtl_pipeline_desc` (§7.2) in a `MemoryStack` frame; `mcmtl_pipeline_create`.
5. Return `() -> pipeline`; any failure → log (with `MtlNative.lastError()`) and `Pending.NULL`.

### 7.2 `mcmtl_pipeline_desc` from `CreateInfo`

| Field | Value |
|---|---|
| `flags` | `PIPELINE_FLAG_HAS_DEPTH_STATE` iff `depthStencilState != null` |
| `vertexFunction/fragmentFunction` | cache handles |
| `label` | `name` when `device.debugLabels()`, else 0 |
| `primitiveType/topologyClass/emulation` | `MetalFormats.primitiveType/topologyClass/emulation(topology)` |
| `cullMode` / `fillMode` / `frontFacing` | `MetalFormats.cullMode(cull)` / `fillMode(polygonMode)` / `WINDING_CLOCKWISE` |
| `depthCompare/depthWriteEnabled` | `compareFunction(dss.depthTest())`, `writeDepth`; null state → `COMPARE_ALWAYS`, 0 |
| `depthBiasConstant/SlopeScale/Clamp` | `dss.depthBiasConstant()`, `dss.depthBiasScaleFactor()`, 0 (null → 0) |
| `pushConstantSize/Stages` | `pushConstantsSize`, stages whose MSL reads buffer 14 |
| `colorTargetCount/colorTargets[i]` | per entry: null → all 0; else `pixelFormat(format)`, `colorWriteMask(writeMask)`, blending from `blendFunction` (`blendFactor`/`blendOperation`; color and alpha equations) |
| `attributes[]` | per `AttribBinding`: `{location, vertexFormat(format), offset (must be % 4 == 0, else fail), 30 − bufferSlot}` |
| `layouts[]` | per `VertexBuffer` with ≥ 1 attribute: `{30 − slot, stride, stepRate > 0 ? PER_INSTANCE : PER_VERTEX, max(1, stepRate)}` |
| `bufferMask[s]` | used UBO indices + 14 if push constants used + (vertex stage) every layout's buffer index |
| `textureMask/samplerMask/textureCubeMask/texelBufferMask[s]` | from the translation results |
| `requiredVertexBufferMask` | bits `30 − slot` of every layout |
| `precompileDepthFormats` | `{PIXEL_FORMAT_DEPTH32_FLOAT}` plus `PIXEL_FORMAT_INVALID` iff `depthStencilState == null` |

### 7.3 Variants (I1 builds, I2 selects)

Key `mcmtl_variant_key(colorPresentMask, depthPixelFormat)` (internal.h): bit i = the pass has an
attachment in color slot i; depth format of the pass's depth texture or 0. Variant descriptor: a **copy** of
the template (`[descriptorTemplate copy]`; the template is never mutated after `mcmtl_pipeline_create`, which
is what makes `mcmtl_pipeline_build_variant` thread-safe) + `colorAttachments[i].pixelFormat = bit i ?
colorTargetFormats[i] : Invalid`, depth format, stencil format = depth format if it has a stencil aspect
(BEGIN_PASS attaches the stencil aspect accordingly, §4.4). `mcmtl_pipeline_create` precompiles keys
`(all non-null targets, f)` for each `precompileDepthFormats[i]`, **depth formats before the depth-less
(`INVALID`) entry whatever their order in the list**: Metal decides "fragment function writes depth" for a
`[[depth(any)]]` function on the first PSO built from it and caches the verdict, so a depth-less PSO built
first would make every later PSO of that function fail (measured on M5, §16 #3). A failing depth-less
precompile is non-fatal (device message, pipeline kept) when a depth variant was built. The decoder calls
`mcmtl_pipeline_variant(p, key)` which builds and caches misses lazily (render thread).

### 7.4 Close

`MetalRenderPipeline.close()` (render thread): idempotent, marks closed, `timeline.deferRelease(handle)`.
Shader functions stay in the cache until device close.

---

## 8. Surface and present

### 8.1 Java (I4) / native (I1)

`MetalSurface` constructor: `SDL_SetHint("SDL_VIDEO_METAL_AUTO_RESIZE_DRAWABLE", "0")`,
`SDL_Metal_CreateView(window)` (0 → `IllegalStateException(SDL_GetError())`), `SDL_Metal_GetLayer(view)`,
`mcmtl_surface_create(device, layer)` (I1 sets the layer properties of spec §6.2). `configure`: validate
1..16384 (`SurfaceException`), `mcmtl_surface_configure` with `SURFACE_FLAG_DISPLAY_SYNC` iff FIFO,
`maximumDrawableCount = 3`, clear suboptimal + eaten exception. `acquireNextTexture`: eaten exception →
throw it; iconified → `SurfaceException("Cannot acquire minimized window")`; `SDL_GetWindowFlags &
SDL_WINDOW_OCCLUDED` → drop this frame silently; never `nextDrawable`. `blitFromTexture`: unless dropped,
`encoder.recordPresent(this, view)`. `present()`: `mcmtl_surface_present` (never throws; clears drop flag).
`supportedPresentModes`: `{FIFO, IMMEDIATE}`.

Drawable counts and vsync off (§16.4):
- Java passes `maximumDrawableCount = 3` for FIFO and `IMMEDIATE_DRAWABLE_COUNT` (2) for IMMEDIATE.
- With the default `MCMTL_MAILBOX=1`, a vsync-off configure instead sets `MCMTL_MAILBOX_DRAWABLES` (3) and starts the
  surface's drawable acquirer thread. FIFO turns the acquirer off.
- A submit result with neither `PRESENT_ENCODED` nor `DRAWABLE_NIL` means the frame was rendered and not presented. The
  surface stays optimal, and `MetalSurface.presentStats()` counts the frame.

### 8.2 PRESENT decode (I2)

1. End the blit encoder. **Early commit** (spec §6.6 SHOULD, B11; §4.2): if COMMIT was requested and the
   current command buffer already holds encoded work, commit it now without a signal
   (`mcmtl_commit_untimed(dev, cb, signalValue)`) and continue in a fresh command buffer, so the GPU runs the
   frame's passes while `nextDrawable` may block. Then `drawable = [surface->layer nextDrawable]` (blocking,
   up to ~1 s); nil → `DRAWABLE_NIL`, skip.
2. `surface->drawable = drawable` (I1's `mcmtl_surface_present`/`destroy` releases it);
   `drawableWidth/Height` = texture size; differs from `configuredWidth/Height` → `DRAWABLE_RESIZED`.
3. Present pass on `drawable.texture`: load `DontCare` if the copy covers the drawable, else
   `Clear(0,0,0,1)`; store Store; internal `present_bgra8` PSO (spec §6.6 shader: full-screen triangle,
   `read` at `(x, copyH − 1 − y)` from the source at `level`, alpha forced to 1); viewport/scissor
   `(0, 0, copyW, copyH)` with `copyW = min(drawableW, max(1, texW >> level))`, same for H.
4. `[commandBuffer presentDrawable:drawable]` (before commit), `PRESENT_ENCODED`, `presentedFrames++`. The
   rest of the stream (TimerQuery end, Tracy copies) goes into the same command buffer, which is the one
   that signals the timeline value.

**Present fusion (§16.3, `MCMTL_PRESENT_FUSION=1`, default):**
- The surface's layer is mirrored vertically.
- When the look-ahead proves it safe, the frame's last passes render into the drawable, which is acquired before
  them. PRESENT then only does step 4.
- Otherwise steps 1–4 run, and step 3 copies without flipping: drawable rows `[dh − copyH, dh)` read source rows
  `[0, copyH)`, and the rest is cleared.

**Mailbox presentation (§16.4, `MCMTL_MAILBOX=1`, default, vsync off only):**
- Step 1 takes a drawable parked by the surface's acquirer thread, without an early commit and without waiting. The
  fusion pass takes one the same way.
- With no drawable, PRESENT registers nothing and reports no flag. The frame was rendered into its target as usual.
- FIFO surfaces run steps 1–4 as written.

---

## 9. Java classes and cross-module APIs

All in `com.mojang.renderpearl.backend.metal`, render thread unless noted. Skeleton bodies say
`TODO(I3|I4|I5)`; trivial getters/constructors are already implemented and must keep their semantics.

### 9.1 `MetalBackend`, `MetalDevice` (I3)

- `MetalBackend`: complete (`loadLibrary`, `probeInfo`, `createWindow` with `SDL_WINDOW_METAL`,
  `createDevice` → `new FrontendGpuDevice(new MetalDevice(this, options))`, runtime failures →
  `BackendCreationException` so the game falls back to Vulkan/GL; if the `FrontendGpuDevice` constructor
  fails after the `MetalDevice` exists, `createDevice` closes the device first — exceptions from `close()`
  are added as suppressed — so a fallback backend never shares the process with a live Metal device; I3's
  `MetalDevice.close()` MUST therefore work on a device that never submitted). `unloadLibrary` is a no-op.
- `MetalDevice(backend, options) throws BackendCreationException`: device flags (`useLabels` →
  `DEVICE_FLAG_DEBUG_LABELS`, `useValidationLayers` → `DEVICE_FLAG_VALIDATION`), `mcmtl_device_create`,
  `mcmtl_device_get_info`, `buildDeviceInfo(name, driverInfo, maxBufferLength, familyMask, featureMask)`
  (complete, spec §7), status page, `MetalTimeline`, `MetalTransientMemory`, `MetalCommandEncoder`,
  `MetalRenderPipeline.ShaderCache`, stored in the skeleton's final fields (accessors are implemented).
  Works with no window (spec §1.6).
- `MetalDevice.forTesting(handle, statusPage, timeline, transientMemory|null, debugLabels)` (complete): a
  headless device without natives for I4/I5 tests (§11.3). Pass `null` for `transientMemory` (its only
  constructor needs the device). A handle-0 device's encoder must never reach `mcmtl_submit`: tests replace
  `MetalCommandEncoder.submitFunction` (package-private hook) when they commit (§16 #18, #19).
- `beginCompile()/endCompile()` (any thread) and `markClosedAndAwaitCompiles(timeoutNs)` (complete): the
  compile/close handshake of §5.7/§7.1.
- Factories delegate to `MetalBuffer.create`, `MetalTexture.create`, `MetalTextureView.create`,
  `MetalSampler.create`, `MetalQueryPool.create`; `createSurface` → `new MetalSurface(...)` (I4);
  `compilePipeline` → `MetalRenderPipeline.compile` (I5; complete, §7.1); `getLastDebugMessages` →
  `mcmtl_device_copy_messages`; `getTimestampCalibrationOffset` → `System.nanoTime() − gpuNs` bracketed by two
  `nanoTime` reads.

### 9.2 `MetalTimeline` (I3)

See §5. API: `pendingSeq()`, `lastCommittedSeq()`, `completedSeq()` (any thread), `isComplete(seq)`,
`onCommitted(seq)` (I4), `await(seq, timeoutNs)`, `checkErrors()`, `deferRelease(handle)`, `defer(action)`,
`deferUntil(seq, action)`, `drain()`, `drainAll()`, `close()`, `isClosed()`. Constants `FRAMES_IN_FLIGHT = 2`,
`FRAME_WAIT_TIMEOUT_NS = 5 s`, `FIRST_SEQ = 1`.

- **Test mode**: `new MetalTimeline(0, page)` makes no downcalls: `page` is test-owned memory (e.g.
  `MemorySegment.ofArray(new long[8])`) that the test advances by writing `STATUS_PAGE_*` fields; `await`
  polls it; native releases collected by a drain are dropped (kept inspectable). Status-page reads go through
  `MtlNative.statusLong/statusInt`, which need no library.
- **Closed state** after `close()`: §5.3.

### 9.3 Resources (I3)

- `MetalBuffer extends BaseGpuBuffer` (frontend casts): `handle()`, `backingOffset()`, `contents()`,
  `gpuAddress()` (the latter two already include the backing offset), `markUsed(seq)` / `lastUseSeq()`,
  `texelView(pixelFormat, bytesPerTexel)` (lazy `mcmtl_texel_view_create`, cached per (backing, format),
  released with the buffer; MAY be created eagerly for R8Sint), `isTransient()`. The backing is mutable
  render-thread state (`protected final rebind(handle, contents, gpuAddress, backingOffset)`, I3 only) so M2
  buffer renaming (spec B8) and transient sub-allocations need no cross-module change; M1 never rebinds and
  `backingOffset()` is 0 for ordinary buffers. **Every record reads `handle()` and adds `backingOffset()` at
  record time; nothing caches them.** `map()` = `memByteBuffer(contents + offset)`;
  `close()` idempotent, throws while mapped (Vulkan parity), defers release. Usage reported exactly as given.
  `createBuffer(data)`: create + synchronous `memCopy` (legal mid-pass; a fresh/recycled buffer is idle).
- `MetalTexture extends BaseGpuTexture`: `handle()`, `pixelFormat()`, `isCube()`, view refcount
  (`retainView`/`releaseView`). IAE for formats without a pixel format and sizes outside 1..16384;
  label `null → ""`. SHOULD zero-fill textures without RENDER_ATTACHMENT via
  `encoder.recordTextureInit(this)`.
- `MetalTextureView extends BaseGpuTextureView`: full range → no native object; partial →
  `mcmtl_texture_view_create`; `samplingHandle()`; IAE for `mipLevels < 1`.
- `MetalSampler`: deduplicated native states (refcounted by key), distinct Java objects, `handle()`.
- `MetalQueryPool`: `handle()`, `markWritten(index, seq)`, non-blocking `getValues` (§4.4 TIMESTAMP).
- `MetalFence`: §5.4.
- `MetalTransientMemory`: spec §2.9 with `TransientBlockAllocator`; staging/GPU blocks are Shared
  `MTLBuffer`s of `BLOCK_SIZE` (4 MiB) wrapped by `TransientBuffer extends MetalBuffer`; effective alignment
  `lcm(requested, 16)` (= `max(requested, 16)` for powers of two; §16 #16); `endFrame(frameSeq)` (I4 calls
  it), `close()`. M1 `TransientBuffer`s wrap a whole block (`backingOffset() == 0`); the allocation offset is
  the slice offset.

### 9.4 Encoder, pass, stream writer, surface (I4)

- `StreamWriter`: growable 16-byte-aligned off-heap buffer, `reserve(size)` returns a zeroed record address.
- `MetalCommandEncoder`: two writers (init, main); all `CommandEncoderBackend` methods per spec §2.3 and
  §4.4; cross-module `recordTextureInit`, `flushForWait`, `isInRenderPass`, `shutdown`; I4-internal
  `recordPresent`. Validation the frontend skips (spec §1.2, §2.3): 5-arg `copyTextureToBuffer` bounds,
  `copyBufferToTexture` byte range incl. sx/sy, timestamp `index < size`, submit with a pass open,
  different-format texture copies.
- **writeToBuffer** (spec §2.3): destination `MetalBuffer b` (transient blocks included — one
  `TransientBuffer` object per allocation, so its `lastUseSeq` covers exactly that allocation): if
  `b.lastUseSeq() <= timeline.completedSeq()` → `memCopy` into `b.contents() + offset` (no record);
  else `transientMemory.uploadStaging(data, 16, USAGE_COPY_SRC)` + `COPY_BUFFER` (offsets include
  `backingOffset()` of both buffers) + `b.markUsed(pendingSeq)`.
  Never modify `data`. Every record that references a buffer (bindings, vertex/index/indirect buffers,
  copy source/destination, texel views) calls `markUsed(pendingSeq)` on it.
- `MetalRenderPass`: §6.2; draws record `DRAW*`; `multiDraw*` throw `UnsupportedOperationException`
  (features false); `pushDebugGroup` evaluates the supplier only when labels are on; closed passes throw.
- `MetalSurface`: §8.1; `onPresentResult(flags, w, h)` called by the encoder after any submit containing its
  PRESENT.

### 9.5 Pipelines (I5)

`MetalRenderPipeline`: public final binding tables (§6.2), `handle()`, `compile(device, info)` (always
inside `beginCompile/endCompile`), `ShaderCache` (device-owned; MSL-hash key, refuses inserts after
`close()`, §7.1). `MslTranslator`: I5-internal API (may be reshaped by I5; the §6.1 fallback rule is not).

### 9.6 Cross-module call map (frozen signatures)

| Caller | Callee | Method |
|---|---|---|
| I3 MetalDevice | I4 | `new MetalCommandEncoder(MetalDevice)` (constructor must not call back into the device), `MetalCommandEncoder.shutdown()`, `new MetalSurface(MetalDevice, long, BooleanSupplier)` |
| I3 MetalDevice | I5 | `MetalRenderPipeline.compile(MetalDevice, CreateInfo)` (from the implemented `compilePipeline`), `new MetalRenderPipeline.ShaderCache(MetalDevice)`, `ShaderCache.close()` (after `markClosedAndAwaitCompiles` returned true) |
| I3 MetalTexture | I4 | `MetalCommandEncoder.recordTextureInit(MetalTexture)` |
| I3 MetalFence | I4 | `MetalCommandEncoder.flushForWait(long)` |
| I4 | I3 | `MetalDevice.handle/timeline/encoder/transientMemory/statusPage/debugLabels/strictBindings`, `MetalDevice.forTesting` (tests), `MetalTimeline.*`, `MetalBuffer.handle/backingOffset/contents/markUsed/lastUseSeq/texelView/isTransient`, `MetalTexture.handle/pixelFormat/isCube`, `MetalTextureView.samplingHandle/texture/baseMipLevel`, `MetalSampler.handle`, `MetalQueryPool.handle/size/markWritten`, `new MetalFence(MetalDevice, long)`, `MetalTransientMemory.uploadStaging/endFrame` |
| I4 | I5 | `MetalRenderPipeline` public fields + `handle()` |
| I5 | I3 | `MetalDevice.handle/isClosed/debugLabels/shaderCache/timeline/beginCompile/endCompile`, `MetalDevice.forTesting` (tests), `MetalTimeline.deferRelease` |
| any | arch | `MtlNative.*`, `StreamOps.*`, `MetalFormats.*` |

---

## 10. Packaging, loading, build

- Jar entries (added by `tools/build-client.sh`): classes; `macos/arm64/com/mojang/renderpearl/backend/metal/libmcmetal.dylib`;
  `META-INF/macos/arm64/com/mojang/renderpearl/backend/metal/libmcmetal.dylib.sha1`; merged
  `assets/minecraft/lang/en_us.json`. Mojang's signature is stripped (unsigned manifest).
- Loading (`MtlNative.Loader.load()`): `Library.loadNative(MtlNative.class, "com.mojang.renderpearl.backend.metal",
  "mcmetal", true)`: LWJGL finds the resource, extracts it into `org.lwjgl.system.SharedLibraryExtractPath`
  (the launcher's `<natives>/lwjgl`), re-extracting whenever the CRC32 differs (spec U18 resolved: in-place
  upgrades work), dlopens it. Symbols are resolved with `SharedLibrary.getFunctionAddress` and bound with
  `Linker.downcallHandle`. Dev override: `-Dmcmetal.library=/abs/path/libmcmetal.dylib`. The launcher's
  `--enable-native-access=ALL-UNNAMED` covers FFM; nothing else is needed.
- `tools/build-client.sh`: javac (`--release 25`, all `client/src`), resources, lang merge
  (`client/lang/<lang>.json` keys merged into the vanilla file — never put a partial lang file in
  `client/resources`), native build of `native/src/*.m` with
  `-arch arm64 -mmacosx-version-min=26.0 -O2 -g -fobjc-arc -fobjc-arc-exceptions -fvisibility=hidden -Wall
  -Wextra -Wno-unused-parameter -Werror=unguarded-availability-new -Werror=return-type` linking Foundation,
  Metal, QuartzCore, MetalFX into `build/native/libmcmetal.dylib`, ad-hoc signature, jar overlay.
- `tools/build-native-tests.sh [--run] [names]`: every `native/tests/*.m` + all `native/src/*.m` →
  `build/native-tests/<name>` with the same language flags, `-fvisibility=hidden` and the same frameworks
  (MetalFX included, so a test links whenever the dylib does; `-O1`); `--run` runs them with
  `MTL_DEBUG_LAYER=1`.
- `tools/metal-smoke.sh`: runs `tools/MetalSmoke.java` against `build/client.jar` (§11.5).
- Game wiring (I5, done): `PreferredGraphicsApi.METAL` ("metal", key `options.graphicsApi.metal` = "Metal");
  on macOS DEFAULT and METAL try `[Metal, Vulkan, OpenGL]`; other OSes unchanged (METAL acts like DEFAULT).
  Launch: `python3 tools/launch.py --jar build/client.jar --game-dir run/x --api metal`
  (debug: `--jvm -Dmcmetal.strictBindings=true`, environment `MTL_DEBUG_LAYER=1`, `MTL_HUD_ENABLED=1`).

---

## 11. Testing guidance

### 11.1 I1 — `native/tests/core_test.m`

Use the exported API (include `mcmetal_internal.h` only to inspect objects). Verify: device create/info
(every field of spec §7 plausible, status page address non-zero) / destroy; MCMTL_E_UNSUPPORTED for the MTL4
flag; buffers (contents writable, gpuAddress non-zero, texel rounding to 16), OUT_OF_MEMORY for an absurd
size; textures 2D/cube/depth with the usage/storage given; partial views (cube keeps cube type); texel views;
samplers (all address/filter combos, anisotropy 16); query pool create/resolve on a fresh pool → all
UNAVAILABLE; shader_create success and failure (garbage MSL → COMPILE_FAILED with a log); pipeline_create
with a vertex descriptor, two color targets with blending and Depth32Float/Invalid precompiles, then
`mcmtl_pipeline_variant` for a missing-attachment key (lazy build); surface create/configure/present/destroy on
`[CAMetalLayer layer]` (pass the pointer as `void *`); release/release_batch; run under `MTL_DEBUG_LAYER=1`
with zero validation errors. Never rely on I2 (its `mcmtl_decoder_device_init` stub returns YES).

### 11.2 I2 — `native/tests/decoder_test.m`

Be independent of I1: build the context with `mcmtl_device_context_create(MTLCreateSystemDefaultDevice(), @"t")`,
set `dev->flags`, call `mcmtl_decoder_device_init(dev)`, pass `(mcmtl_handle)(uintptr_t)(__bridge void *)dev`.
Create buffers/textures/samplers directly with Metal and pass `(__bridge void *)` handles. Build pipelines
by hand: `MCMTLPipeline *p = [MCMTLPipeline new]; p->owner = dev; …` + a PSO compiled from test MSL
(`newLibraryWithSource:`) inserted with `mcmtl_pipeline_add_variant(p, mcmtl_variant_key(mask, depth), pso)`.
Hand-build streams with the `mcmtl_cmd_*` structs (a tiny writer helper in the test), call `mcmtl_submit`
(the real export: guard + decoder) with COMMIT and dense signal values, `mcmtl_wait_completed`, read back
with a `COPY_TEXTURE_TO_BUFFER` record into a Shared buffer. `mcmtl_wait_completed` may return before the
completion handler advanced `completedValue`; poll the prefix when a check needs it.
Cover: clears (full, region), copies (all four), INIT_TEXTURE, draws (direct, indexed, indirect,
indexed-indirect), fan emulation (draw and drawIndexed), dummies (pipeline using an unset UBO/texture must
not fault and must sample zeros), CLEAR_BINDING, shadow filtering (same image with redundant and
non-redundant binding records; optional call counters kept in `ctx->ext`), scissor, a missing vertex buffer
(draw skipped, `drawsSkipped` = 1), PRESENT on an offscreen `CAMetalLayer` (`framebufferOnly = NO` so the test can
read back the flipped image) and a nil-drawable path, bad streams (unknown op, misplaced op, truncated, any
non-`INIT_TEXTURE` op in the init stream, `UPSCALE` in M1) still committing, timestamps resolving non-zero
(also with debug groups between the timestamp and the pass). Required additions:
- **Exception mid-stream**: an `NSException` raised while a pass and a blit batch are open. Use a
  deterministic trigger — a test hook in `ctx->ext` that calls `[NSException raise:…]` on a chosen record
  index — not invalid Metal arguments: with `MTL_DEBUG_LAYER=1` validation failures abort the process instead
  of throwing. Expect: `mcmtl_submit` returns
  `MCMTL_E_OBJC_EXCEPTION`, the value is consumed (`submittedValue ≥ value`, wait succeeds),
  `dev->currentCommandBuffer == nil`, and the **next** submit with value + 1 works and renders correctly.
- **Intra-blit-encoder ordering** (§4.5): write a buffer via staging (COPY_BUFFER staging→X), then
  COPY_BUFFER X→Y between the same two passes, read Y back; and INIT_TEXTURE-style zero fill followed by
  COPY_BUFFER_TO_TEXTURE into the same texture in one main stream, read back.
- **Early commit**: a frame with passes, then PRESENT on an offscreen layer: two command buffers committed
  (count via `encodersCreated`/a test hook in `ctx->ext`), the image is correct, the value completes.
Run with `MTL_DEBUG_LAYER=1`.

### 11.3 I3 / I4 / I5 — Java

- Everything must compile with `tools/build-client.sh` against the other modules' skeletons.
- **No natives needed for pure-Java tests.** `MtlNative` binds its downcalls in a nested holder that
  initializes at the first downcall wrapper, so constants, `Layouts`, `statusPage/statusLong/statusInt`,
  `statusName`, `exceptionFor`, `check` and `lastError()` (returns "" without the library) work without
  `Loader.load()` (`tools/MetalSmoke.java` step 0 proves it). Tests that do call natives run with
  `-Dmcmetal.library=build/native/libmcmetal.dylib` and call `MtlNative.Loader.load()` first.
- **Headless devices**: `MetalTimeline` in test mode (`new MetalTimeline(0, MemorySegment.ofArray(new long[8]))`,
  §9.2) and `MetalDevice.forTesting(0, page, timeline, null, labels)` give I4 and I5 a working device (accessors,
  encoder, shader cache, timeline) before I3 is merged. I3 implements the test-mode timeline first.
- I3: unit-test pure logic without natives (FIFO ordering, test-mode timeline, closed state, refcounts,
  `buildDeviceInfo`); after merge, a headless harness (`tools/`, owner I3) that creates `new MetalDevice(new
  MetalBackend(), options)` directly (no window, no FrontendGpuDevice) and exercises buffers/map/close,
  textures/views, samplers, fences around empty submits, query pools, close with a compile in flight.
- I4: unit-test `StreamWriter` and record encoding on a `forTesting` device by decoding the bytes back with
  `StreamOps` offsets; test `MetalRenderPass` dedupe with `MetalRenderPipeline` objects built with the
  public constructor (handle 0).
- I5: a headless harness that compiles every vanilla pipeline through the frontend's `GlslCompiler`/
  `PipelineBuilder` path (or direct shaderc) and runs `MslTranslator` on it (a `forTesting` device carries the
  shader cache); MSL validity needs a Metal compiler: use `mcmtl_shader_create` once I1 lands (the offline
  `metal` tool is not installed). Also test the §6.1 fallback with synthetic uniform lists (10 non-fixed
  UBOs; Sampler0..2 + 14 textures).

### 11.4 Integration

Headless suite (all must pass; every native/GPU step runs with `MTL_DEBUG_LAYER=1`, where any API validation
error aborts the process):

| Command | Owner | What it proves |
|---|---|---|
| `tools/build-client.sh` | arch | javac (0 errors), dylib links warning-free, jar overlay |
| `tools/build-native-tests.sh --run` | arch/I1/I2 | `abi_test` (guard, completion tracking), `core_test` (I1 exports incl. a real terrain PSO render, samplers, present helpers, CAMetalLayer surface), `decoder_test` (I2: every op, hazards, dummies, fans, PRESENT + early commit, bad streams, exceptions), `occlusion_test` (§16.6: OFF / ON / dry-run decoders byte-identical over 678 frames of a synthetic terrain path) |
| `tools/metal-smoke.sh` | arch | ABI 2, 45 sizes, header ↔ Java cross-check both ways, `createDevice` returns a device |
| `tools/metal-device-test.sh` | I3 | timeline/FIFO/fences/resources/transient memory (pure Java) + a real `MetalDevice` (GPU copy round trips, buffer recycling, flush-on-wait) |
| `tools/test-i4-encoder.sh` | I4 | every record layout decoded back in Java, dedupe, writeToBuffer paths, submit/throttle/errors, surface state machine, 0 bytes allocated per draw |
| `tools/i5-pipeline-test.sh` | I5 | all 216 vanilla + post-effect pipelines through the real frontend `PipelineBuilder` → MSL → real I1 `mcmtl_pipeline_create`; masks vs Metal reflection; smoke draws of 270 variants; pixel test (`--reference-pipeline` = pre-merge harness) |
| `tools/core-msl-test.sh` | I1 | all 192 vanilla pipelines through an independent GLSL → MSL path into the real natives on a compile pool |
| `tools/occlusion-e2e-test.sh` | integration | §16.6 with the real backend and vanilla `*_TERRAIN_MULTIDRAW`: 180 frames hashed with `MCMTL_OCCLUSION=0` and `=1` must match, and the ON run must cull |
| `tools/metal-e2e-test.sh` | integration | **end to end**: `MetalBackend.createDevice` (real `FrontendGpuDevice`), real vanilla pipelines compiled by the frontend, offscreen rendering read back with `copyTextureToBuffer` + callback + fence and checked pixel by pixel (GUI blend/scissor, render-to-texture blit, textures/mips, reverse-Z depth + depth readback, fans in a depth-less pass, SOLID_TERRAIN with 5 UBOs and int attributes, FLAT_CLOUDS texel buffer, PANORAMA cube faces, depth-only BLIT_DEPTH, mip render targets, UBO rewrite ordering, timestamps, flush-on-wait), the present path (real `MetalSurface` over the real surface exports on an offscreen `CAMetalLayer`: acquire/blit/submit/present, mip-1 source, `DRAWABLE_RESIZED` → suboptimal → reconfigure), 300 frames of churn while all 192 vanilla pipelines compile on worker threads; twice (debug labels off/on) |

Then (user's screen) `python3 tools/launch.py --jar build/client.jar --game-dir run/x --api metal` with
`MTL_DEBUG_LAYER=1`; compare screenshots with the Vulkan baseline (`tools/ImgDiff.java`, the strict regression
detector) and check the visual bar of docs/GOAL.md with `tools/PerceptualDiff.java <ref> <cand> --floor <runA> <runB>`
(CIEDE2000 mean / p99.9, SSIM, largest dE > 10 region; scenes that miss the bar are judged against the run-to-run
noise of two reference runs). Scene runs start from the pinned world and compare against the pinned vanilla Vulkan
reference of the current display mode (§11.6).

### 11.5 Architect proof (current output)

`tools/metal-smoke.sh` first uses the pure `MtlNative` helpers without the library, then loads the packaged
dylib through `MetalBackend.loadLibrary()`, prints the probe (Apple M5, families
Apple7..Apple10/Mac2/Metal3/Metal4, macOS 27.0.0), verifies ABI 2 and 45 struct sizes, cross-checks all
header offsets/sizes/defines against `MtlNative`/`StreamOps` in both directions, exercises error mapping, and
(since the M1 merge) shows `mcmtl_device_create` → `MCMTL_OK` and `createDevice` returning a device (before
the merge: a clean `BackendCreationException` from the skeleton).
`tools/build-native-tests.sh --run` runs `abi_test` (common infrastructure: completion tracking incl.
out-of-order completion over 300 values, the event never passing an outstanding value, waits, timeouts, the
`mcmtl_submit` guard consuming the signal value on every error path with the I2 stub, message ring,
depth-stencil cache).

### 11.6 Pinned scenes world and vanilla references (scenes / zoomseq parity)

**Why.** The scene runner used to reuse the world of its game dir (`run/scenes`). The rain and thunder scenes put snow on
exposed blocks of this snowy taiga, and the game saves the world on exit, so every run changed the world of the next one.
- The reference drifted. `run/shots-vkns` (2026-10-04 21:17) against `run/shots-vkns-r4` (6 h later, same game dir):
  `day_north` region 268 px at (1626, 807) and `sunrise` 305 px at (159, 1094), with dE p99.9 up to 24 (snow strips on
  dark leaves). Over successive runs the region grew 94 → 100 → 183 → 268 px (results/ROUND4-FUSION.md), identical with
  `MCMTL_PRESENT_FUSION=0` and `=1`: world state, not rendering.
- Within one run, snow from the rain/thunder scenes (it kept falling while the rain faded out after `weather clear`) landed at random on
  `water_surface`'s sand and in the zoom scenes' view (results/ROUND5.md §3: a 6084 px FAIL).
- The round-4 fix (copy `run/scenes-pinned-r4` per run, `tools/ab/scenes.sh`) was a convention only. The copy was a
  writable game dir, and the documented `launch.py --game-dir run/scenes` commands still drifted.

**Pinned content.** Read-only (`chmod a-w`), with a `SHA256SUMS` manifest of every file. `python3 tools/pin.py --verify <dir>`
checks the content and prints its id (the first 16 hex digits of the manifest's SHA-256).

| Path | Content | id |
|---|---|---|
| `run/pinned/scenes` | `saves/bench` + `options.txt`: byte-identical to `run/scenes-pinned-r4` (= `run/scenes` as saved 2026-10-05 02:52; seed 4242, anchor -4.5 103 -2.5; video options as for `shots-vkns-r4`, RD 12), without `session.lock`, and with two game rules set in `data/minecraft/game_rules.dat` (2 bytes differ): `max_snow_accumulation_height` 0 (was 1), `random_tick_speed` 0 (was 3) | `1b3c83ae44053b75` |
| `run/pinned/shots-vkns-1512x982` | vanilla Vulkan reference, 22 scenes (the 18, `spyglass_far`, `spyglass_scope`, `spyglass_entities`, `spyglass_far_entities`; `zoom_*` need the patched client) | `5dc8214c85cfecd5` |
| `run/pinned/shots-vkns-1512x982-2`, `-3` | two more vanilla runs with the same flags, for `PerceptualDiff --floor` | `d63c369a50de5cba`, `92ff01edd8259cb9` |
| `run/pinned/shots-vkns-1800x1169` | the same 22 scenes in the More Space mode (display switched back 2026-10-05 14:42); one run only, by the user's choice, so no floor runs: without `--floor`, end and underwater can FAIL on random particles and need a look by eye (add `-2`/`-3` with the same command when a clean automatic verdict is needed) | `746ca0cec1585c35` |
| `run/pinned/zoomseq-vkns-1512x982` | vanilla Vulkan zoomseq reference: 64 lockstep frames, spy_in and spy_out, with `index.csv` (`zoom_*` segments need the patched client) | `db197393e0f92601` |

**How runs use it.**
- `tools/launch.py` with `--bench-prop mode=scenes` or `mode=zoomseq` (not `join=`) verifies `run/pinned/scenes`. After it
  holds the game lock, it replaces `<game dir>/saves/<world>/` and `<game dir>/options.txt` with fresh, writable copies, then
  applies `--api` and `--opt` as before. Every run starts from the same bytes, also in a reused game dir. The game still saves
  on exit; only the game dir's copy changes. The restore runs under the lock, so a queued launch cannot replace the world of a
  game that is still running in the same dir.
- `--world-snapshot <dir>` picks another pinned snapshot. `--world-snapshot none` keeps the game dir's world: debugging only,
  because that world drifts.
- A snapshot that fails verification (a file changed, added or removed) stops the launch with the list of differing files.
- The scenes / zoomseq JSON records `"pinnedWorld": "run/pinned/scenes@1b3c83ae44053b75"` (from `-Dbench.pinnedWorld`,
  which launch.py sets whenever it restores a snapshot). A scenes JSON without that member comes from a bench jar older than
  `bench-pin1.jar` or from launch.py before this change. One with `"none"` is an unpinned run. Neither is comparable with the
  pinned reference.

**Game rules.** `Bench` (scenes / zoomseq) also sends `gamerule max_snow_accumulation_height 0` and `gamerule
random_tick_speed 0`, after the existing four. The pinned world already has both at 0, so older bench jars get them too.
- In 26.3, `ServerLevel.tickChunk` calls `tickPrecipitation` from its random-tick loop: `randomTickSpeed` tries per chunk and
  tick, 1/48 each.
- `tickPrecipitation` freezes exposed water at edges to ice in cold biomes. That needs no rain, so the snow rule alone does
  not stop it: `water_surface`'s sand border, lake edges. It places or grows snow only while raining, with
  `max_snow_accumulation_height` > 0.
- Random block ticks also grow plants (sweet berry bushes in this taiga) and melt snow near light.
- These are server-side world mutations only. The client's `animateTick` (particles), weather rendering, lightning and
  everything drawn stay vanilla. With both rules the world changes only through the scene commands. The scenes still show
  the world's existing snow layers, so snow rendering stays covered.
- This changes what `rain` and `thunder` capture (no fresh snow during their settle), so references made before it
  (`shots-vkns`, `shots-vkns-r4`, `ref-r5ab`, `shots-r5h-vk`) are not comparable.

**Display mode.** Screenshots are the fullscreen framebuffer at vanilla's downscale of 2, so a reference only matches runs
in the same macOS display mode. A size mismatch fails loudly: PerceptualDiff throws, and ImgDiff reports
`size mismatch` and exits 1.
- Rounds 1-5 ran at 3600x2338 ("More Space", 1800x1169 screenshots).
- On 2026-10-05 the built-in display was at its default 3024x1964 (1512x982 screenshots; changed between 11:30 and 14:00, not
  by these runs).
- The display went back to More Space at 14:42 the same day; `run/pinned/shots-vkns-1800x1169` is its reference. Use the
  reference whose name matches the run's screenshot size.
- The user then chose to play in Default (3024x1964, panel-native, the mode that allows direct scan-out at 120 Hz), so
  `run/pinned/shots-vkns-1512x982` (+ `-2`, `-3`, `zoomseq-vkns-1512x982`) is the primary reference; 1800x1169 is kept
  only for runs made in More Space.
  Never change the display mode for a run: it is a system setting.

**Making a reference** (vanilla 26.3 jar, i.e. no `--jar`; Vulkan; non-Spaces fullscreen; the pinned options: the
`shots-vkns` flags). Use a frozen bench jar no older than `bench-pin1.jar` (sha1 `132f7ee9…`).
```sh
python3 tools/launch.py --game-dir run/scenes-pin-ref2 --api vulkan --env SDL_VIDEO_MAC_FULLSCREEN_SPACES=0 \
  --bench results/raw/scenes-pin-ref.json --label pin-ref --bench-jar build/bench-pin1.jar \
  --bench-prop mode=scenes --bench-prop shots="$PWD/run/shots-pin-ref"
python3 tools/pin.py shots run/shots-pin-ref run/pinned/shots-vkns-1512x982
```
Then run the same command twice more, with labels `pin-v1` and `pin-v2` in one reused game dir (`run/scenes-pin-verify`), and
pin them as `-2` and `-3`. Check that each JSON says `scenes complete`, records the pinned id, and has both entity checks
`pass`. The verification below must hold before the reference is used.

**Changing the world** (for example a scene that needs other terrain): pin a new name and never edit `run/pinned/*`.
```sh
python3 tools/pin.py world <src game dir> run/pinned/scenes-2 --gamerule max_snow_accumulation_height=0 --gamerule random_tick_speed=0
```
Then pass `--world-snapshot run/pinned/scenes-2`, or change `PINNED_SCENES` in `launch.py`. Make the three vanilla runs
again and record the ids here.

**Comparing a candidate:**
```sh
J=mc/runtime/java-runtime-epsilon/jre.bundle/Contents/Home/bin/java; R=run/pinned/shots-vkns-1512x982   # or -1800x1169 (no -2/-3: drop --floor)
$J tools/ImgDiff.java $R/screenshots run/shots-LABEL/screenshots run/diff-pin-LABEL
$J tools/PerceptualDiff.java $R/screenshots run/shots-LABEL/screenshots \
  --floor $R/screenshots $R-2/screenshots $R-2/screenshots $R-3/screenshots
$J tools/ImgDiff.java run/pinned/zoomseq-vkns-1512x982/screenshots/zoomseq run/zoomseq-LABEL/screenshots/zoomseq run/diff-pinzq-LABEL
```

**Verification** (2026-10-05, `results/SCENES-PINNED.md`). There were four vanilla runs from the pinned world: the
reference, two consecutive runs in one reused game dir (`pin-v1`, `pin-v2`), and one from the first snapshot.
- `day_north` and `sunrise` have **no dE > 10 region in any compared pair**: `PerceptualDiff <ref> <v1|v2> --floor v1 v2`
  and v1 against v2. Their only differences are faint sky rings (dE mean ≤ 0.24, p99.9 ≤ 1.02). Against the run from the
  first snapshot, only 1 and 16 pixels differ, by one level.
- Strict ImgDiff: 22/22 ok.
- With the recommended floor, an independent vanilla run passes all 22 scenes. The remaining noise is client-side
  randomness: end, entities, particles, and underwater, where one random `UNDERWATER` ambient particle in the reference
  makes a 225 px region.
- `client-r5.jar` on Metal against the new reference keeps round 5's verdict: only `spyglass_far_entities` differs (the
  zoom-aware far mobs, by design). `water_surface` passes now that no snow lands on its sand.

---

## 12. Decisions made by this contract (beyond the spec)

1. Handles are +1 retained Objective-C objects as `uint64_t`: raw Metal objects for buffers, textures,
   views, texel views, samplers, functions; wrapper classes for device, pipeline, surface, query pool; one
   generic `mcmtl_release(_batch)` (device-aware for M3 residency).
2. The ABI carries raw Metal enum values; all RenderPearl→Metal mapping lives in the frozen
   `MetalFormats.java` (shared by I3/I4/I5) instead of being duplicated natively.
3. Stream records reference resources by handle only (no gpuAddress/resourceID); the M3 decoder reads them
   from the objects. Records keep the spec's 16-byte alignment with a named 32-bit operand at offset 4.
4. Two streams per submit: an **init stream** (texture zero-fill, decoded first) so textures created
   mid-pass can be initialized before any use.
5. Every COMMIT (frame end and flush-on-wait) signals its own dense timeline value; completion is tracked
   as a contiguous prefix by completion handlers (correct with out-of-order completion, no window limit);
   the CPU only ever raises the event to the contiguous prefix; waits also return as soon as the event
   passed the value; every COMMIT call consumes its value on every path (submit guard).
6. A shared-memory status page gives Java completion/error state without downcalls; errors and decoder
   warnings also go to a native message ring (`getLastDebugMessages`).
7. Clears are recorded as `CLEAR_TEXTURE`/`CLEAR_REGION`; folding is a decoder (M2) optimization.
8. Pass labels are command-buffer debug groups around the pass (`PUSH/POP_DEBUG_GROUP`), not an inline
   BEGIN_PASS field.
9. Texel-buffer views are created lazily by Java (`MetalBuffer.texelView`) and referenced by handle.
10. Copies between different formats use `COPY_FLAG_DRAW` (nearest draw) when the destination is a render
    target, IAE otherwise.
11. PSO variant key = (color-attachment presence mask, depth format); cache inline in internal.h;
    builder in I1; lazy misses on the render thread.
12. Shader-cache key = SHA-256 of the generated MSL (+ stage, flags): exact, and maximal dedupe although the
    hybrid slot scheme makes MSL depend on bindings (spec keyed on the SPIR-V hash only).
13. `mcmetal_common.m` (architect, implemented) owns the cross-cutting native infrastructure, so I1 and I2
    share one implementation of completion tracking, waits, messages and errors, and I2 can test without I1.
14. Dummies are created by the decoder (`mcmtl_decoder_device_init`) and stored on `MCMTLDevice`.
15. ABI verification at load (`mcmtl_abi_struct_size` for every struct) plus a full offset/define
    cross-check in `tools/MetalSmoke.java`.
16. Packaging at the LWJGL bundled path with a `.sha1`; `-Dmcmetal.library` override; deployment target
    macOS 26.0; MetalFX linked now (M4) to keep the build stable.
17. Lang: `client/lang/en_us.json` holds only added keys, merged into the vanilla file at build time.
18. Write-combined buffers off by default (`-Dmcmetal.writeCombined=true` to benchmark, spec U14); strict
    bindings checked in Java (`-Dmcmetal.strictBindings=true`).
19. `PreferredGraphicsApi.METAL` on non-macOS behaves like DEFAULT; crash-recovery and MoltenVK-probe
    changes (spec G15) need a `Minecraft.java` patch and are left to I5 (§14).
20. Status/result flag families are named `MCMTL_*_FLAG_*` to keep the Java mirror collision-free.
21. The `mcmtl_submit` export is an architect-owned guard in `mcmetal_common.m` around the I2 decoder
    (`mcmtl_decoder_submit`): validation, divergence check and signal-value consumption live in one
    tested place; commits go through `mcmtl_commit_signaled` / `mcmtl_commit_untimed`.
22. Early untimed commit before `nextDrawable` (decoder-internal; the frame's value-signalling command
    buffer is the last one of the call).
23. Init stream restricted to `INIT_TEXTURE`; look-ahead state is per call; blit batches split on
    intra-encoder hazards; command-buffer debug groups end the blit encoder.
24. `UPSCALE` (op 11, 64 bytes) reserved now for M4 render scale / MetalFX; decoder-owned intermediates.
25. Dummy UBO = 64 KiB of zeros (spec said 256 B).
26. Compile/close handshake (`beginCompile`/`endCompile`, bounded 10 s drain, leak on timeout) instead of a
    bare `isClosed()` check; `ShaderCache` refuses inserts after close.
27. Java tests need no natives: `MtlNative.Downcalls` holder, test-mode `MetalTimeline`,
    `MetalDevice.forTesting`.
28. `MetalBuffer` backing is mutable render-thread state with `backingOffset()` (M2 renaming without
    touching I4).
29. Non-loss command-buffer errors throw (`GpuOutOfMemoryException` / `IllegalStateException`) from
    `checkErrors`, once per error; the timeline has a closed state after device destruction.
30. Both native builds use `-fobjc-arc-exceptions`; the test build mirrors the dylib's visibility and
    frameworks.

---

## 13. Spec §2 checklist: method → owner

Java owner first; native pieces in brackets.

| Spec | Method | Owner |
|---|---|---|
| 2.1 | `GpuBackend.getName/loadLibrary/unloadLibrary/createWindow/createDevice` | I3 (done) [arch: probe/ABI] |
| 2.2 | `createSurface` | I3 → I4 `MetalSurface` [I1 surface] |
| 2.2 | `createCommandEncoder` | I3 |
| 2.2 | `createSampler` | I3 [I1 `mcmtl_sampler_create`] |
| 2.2 | `createTexture` | I3 [I1 `mcmtl_texture_create`; I2 `INIT_TEXTURE`] |
| 2.2 | `createTextureView` | I3 [I1 `mcmtl_texture_view_create`] |
| 2.2 | `createBuffer(size)`, `createBuffer(data)` | I3 [I1 `mcmtl_buffer_create`] |
| 2.2 | `getLastDebugMessages`, `isDebuggingEnabled` | I3 [arch messages] |
| 2.2 | `compilePipeline` | I3 → I5 [I1 shaders/pipelines] |
| 2.2 | `close` | I3 [I1 destroy, I2 shutdown] |
| 2.2 | `createTimestampQueryPool` | I3 [I1 query pools] |
| 2.2 | `getTimestampCalibrationOffset` | I3 [I1 `mcmtl_device_sample_timestamps`] |
| 2.2 | `getDeviceInfo` | I3 (builder done) [I1 device info] |
| 2.3 | `submit` | I4 [I2 `mcmtl_submit`] |
| 2.3 | `transientMemory` | I4 → I3 `MetalTransientMemory` |
| 2.3 | `createRenderPass`, `submitRenderPass` | I4 [I2 BEGIN/END_PASS] |
| 2.3 | `clearColorTexture`, `clearColorAndDepthTextures` ×2, `clearDepthTexture` | I4 [I2 CLEAR_TEXTURE/CLEAR_REGION] |
| 2.3 | `writeToBuffer`, `copyToBuffer` | I4 [I2 COPY_BUFFER] |
| 2.3 | `writeToTexture`, `copyBufferToTexture` | I4 [I2 COPY_BUFFER_TO_TEXTURE] |
| 2.3 | `copyTextureToBuffer` ×2 | I4 (+ I3 FIFO callback) [I2 COPY_TEXTURE_TO_BUFFER] |
| 2.3 | `copyTextureToTexture` | I4 [I2 COPY_TEXTURE] |
| 2.3 | `createFence` | I4 → I3 `MetalFence` |
| 2.3 | `writeTimestamp` | I4 [I2 TIMESTAMP, I1 resolve] |
| 2.3.11 | hazards (classic tracked) | I2 (default tracking) / I1 (resource options) |
| 2.4 | `pushDebugGroup`, `popDebugGroup` | I4 [I2] |
| 2.4 | `setPipeline`, `setUniform`, `pushConstants` | I4 (tables: I5) [I2] |
| 2.4 | `enableScissor`, `disableScissor` | I4 [I2] |
| 2.4 | `setVertexBuffer`, `setIndexBuffer` | I4 [I2] |
| 2.4 | `drawIndexed`, `draw`, `drawIndexedIndirect`, `drawIndirect` | I4 [I2 incl. fans] |
| 2.4 | `multiDrawIndexed` ×2, `multiDraw` ×2 | I4 (throw, done) |
| 2.4 | `writeTimestamp` (pass) | I4 [I2] |
| 2.4 | draw-time obligations (dummies, missing VB/IB) | I2 (I4 strict mode) |
| 2.5 | `BackendRenderPipeline.isClosed/close`, `Pending.finishCompile`, `CreateInfo` handling, variants, depth-stencil states | I5 [I1 pipeline + variants, arch depth-stencil cache] |
| 2.6 | `configure`, `isSuboptimal`, `acquireNextTexture`, `blitFromTexture`, `present`, `supportedPresentModes`, `close` | I4 [I1 surface, I2 PRESENT] |
| 2.7 | `SpvModule` usage | I5 |
| 2.8 | `GpuBuffer.size/usage/isClosed/close/slice/map` | I3 |
| 2.9 | `TransientMemory` (all methods, partial allocations, transient buffers) | I3 |
| 2.10 | `GpuTexture`/`GpuTextureView` (getWidth not clamped, views, refcounts) | I3 |
| 2.11 | `GpuSampler` | I3 [I1] |
| 2.12 | `GpuFence` | I3 (flush via I4) |
| 2.13 | `GpuQueryPool.size/getValue/getValues/close`, timestamp convention (ns, period 1.0) | I3 [I1 resolve, I2 sampling] |
| 2.14 | exceptions | all (mapping: `MtlNative.exceptionFor`) |

---

## 14. Open items and risks

- **Timestamps (I2): resolved.** Empty blit passes sample 0 on M5; the decoder samples standalone timestamps in
  a blit pass with one 4-byte fill, and one query pool per render pass (§4.4, §16 #11).
- **Game wiring (I5):** crash recovery for METAL (`Minecraft.java:466-472`), skipping the MoltenVK probe when
  Metal is chosen (`:478`) and hiding METAL in the options cycle on non-mac (`Options.java`) need patched
  copies of large vanilla classes (`Minecraft.java` has a known decompile artifact at 2578). Optional for M1.
- `mcmtl_complete_value_on_cpu` is a last resort for values whose command buffer was never committed. The
  guard only uses it when `submittedValue < signalValue`, and `mcmtl_commit_signaled` publishes
  `submittedValue` after `commit`, so a committed value is never CPU-completed; and the event is only raised
  to the contiguous prefix, so CPU completion cannot make an earlier in-flight value look done.
- Java must never skip or reuse a timeline value; the guard now rejects both immediately
  (`MCMTL_E_INVALID_ARGUMENT`, §4.2). There is no fixed cap on commits in flight (2 frames + flushes +
  early commits in practice; the queue's 64-command-buffer limit would stall, not corrupt).
- **Intra-blit-encoder ordering** is handled conservatively (split on hazards, §4.5); if `decoder_test`
  shows Metal orders dependent blits within one encoder on M5, the split stays (cheap) unless profiling
  says otherwise.
- **Waits returning on `event.signaledValue`** rely on spec §1.5 (a) (one queue executes command buffers in
  commit order). If the M2 untracked/unretained mode ever weakens that, waits fall back to the prefix only.
- The `INIT_TEXTURE` zero-fill policy (spec U15) is a SHOULD; measure its cost on atlas creation.
- Runtime-only risks after the M1 merge (not reachable headlessly): the SDL Metal view / window path of
  `MetalSurface` (the E2E test replaces only `SDL_Metal_CreateView/GetLayer`), real vsync pacing and drawable
  starvation, Retina scale vs `drawableSize`, the `Minecraft.java` crash-recovery and Vulkan-probe patches,
  `-XstartOnFirstThread` main-thread behaviour, and full-scene parity with the Vulkan baseline.

---

## 15. Review amendments (ABI 2)

A critic reviewed the v1 contract; every finding was checked against the code and the spec. All 22 were
valid and are applied; where the fix differs from the suggestion, the reason is given.

| # | Finding | Resolution |
|---|---|---|
| 1 | Error paths of `mcmtl_submit` could leave a timeline hole; exceptions skipped cleanup and left an open encoder in `currentCommandBuffer` | §4.2 consumption rule in mcmetal.h; **stronger than suggested**: the export became an architect guard (common.m) that validates, checks `signalValue == submittedValue + 1`, calls `mcmtl_decoder_submit` in `@try`, and CPU-completes via `mcmtl_submit_abort` whenever `submittedValue < signalValue`; decoder `@try/@catch/@finally` structure required; `mcmtl_commit_signaled` publishes `submittedValue` after commit. Tested in `abi_test`. The suggested "slot 31" exception trigger is replaced by a test hook (the debug layer aborts instead of throwing). |
| 2 | `compilePipeline` check-then-act race with `close()` | `beginCompile/endCompile` + bounded drain (10 s, leak on timeout), implemented in the skeleton; the bracket lives in `MetalDevice.compilePipeline` (one entry point) instead of inside `MetalRenderPipeline.compile`. `ShaderCache` refuses inserts after close. |
| 3 | Java modules could not test without each other or without the dylib | `MtlNative.Downcalls` holder (pure helpers need no library, proven by MetalSmoke step 0); test-mode `MetalTimeline`; `MetalDevice.forTesting(...)` — a **public static factory** rather than a package-private constructor, so single-file test programs outside the package can use it. |
| 4 | No early commit before `nextDrawable` | Decoder-internal untimed commit (§4.2, §8.2) with `mcmtl_track_untimed_command_buffer` / `mcmtl_commit_untimed`. |
| 5 | M4 render scale needs a mid-frame op | `MCMTL_OP_UPSCALE` (11, 64 bytes) reserved with a frozen layout; modes LINEAR/NEAREST/METALFX_SPATIAL; M1 rejects it; decoder owns MetalFX intermediates. ABI and stream version bumped to 2 (the contract's own rule). |
| 6 | Waits paid completion-handler latency | `mcmtl_wait_completed` returns once `event.signaledValue ≥ value`; Java's `completedSeq()` includes successful waits. **Required an extra fix the finding missed:** CPU paths used to raise the event to an arbitrary value, which would have made this fast path report in-flight values complete; the event is now only raised to the contiguous prefix. |
| 7 | 64-value window fell back to claiming in-flight work complete | Unbounded `NSMutableIndexSet` of out-of-order values: the prefix never skips. The suggested Java-side "block above 60 in flight" is **not adopted**: with unbounded tracking there is no invariant to protect, and the queue's 64-command-buffer limit already stalls. |
| 8 | No `-fobjc-arc-exceptions` | Added to both builds. |
| 9 | Test build lacked MetalFX / hidden visibility | Test flags now mirror the dylib. |
| 10 | Count-based binding fallback could collide with push constants / exceed 15 | Index-based rule (§6.1), MslTranslator javadoc updated. |
| 11 | 256-byte dummy UBO | 64 KiB (`MCMTL_DUMMY_UNIFORM_BYTES`); per-pipeline UBO sizes not needed. |
| 12 | Status page used after destroy | Timeline closed state (§5.3), `MetalTimeline.close()/isClosed()`. |
| 13 | `createDevice` leaked the MetalDevice when `FrontendGpuDevice` failed | Fixed in `MetalBackend` (architect code), requirement noted in §9.1. |
| 14 | Command-buffer debug groups vs the open blit encoder; timestamp adjacency broken by labels | §4.4/§4.5: groups end the blit encoder; adjacency skips NOP/PUSH/POP. |
| 15 | Dependent blits inside one encoder | Conservative rule made normative (split on hazards), plus decoder_test cases. |
| 16 | Init stream allowed transfers | `INIT_TEXTURE`/`NOP` only, else BAD_STREAM; `StreamOps.isInitStreamOp`. |
| 17 | Look-ahead across calls | Never outlives one call (§4.7). |
| 18 | Non-loss command-buffer errors only logged | `checkErrors` throws OOM/ISE once per error (§5.3). |
| 19 | Shader-cache key too broad | Key on the SHA-256 of the generated MSL (exact, maximal dedupe) instead of reflection-filtered binding tuples; translation memoization by those tuples allowed. |
| 20 | Final `MetalBuffer` backing blocked B8 renaming | Mutable backing + `backingOffset()` + `rebind`; records read them at record time. |
| 21 | "No native calls" vs lazy texel views | Reworded; eager R8Sint creation allowed. |
| 22 | Cross-check only header → Java | Reverse pass added to MetalSmoke (verified to flag a stray constant). |

---

## 16. Integration amendments (M1 merge)

The five M1 modules were merged on `main` (I1 `683225c`, I2 `efd22f1`, I3 `b94cd71`, I4 `e59e03e`, I5
`cb79c50`). Every `contract_issue` an implementer raised is listed here with the decision. **No header, record
layout, opcode or ABI version changed** (still ABI 2); where behaviour or wording changed, the section above
was updated in place. Integration code changes are listed under "Merge fixes".

### 16.1 Decisions

| # | From | Issue | Decision |
|---|---|---|---|
| 1 | I1 | Present pass ownership: I1 also implemented present helpers (`mcmtl_core_present_pipeline`, `mcmtl_core_encode_present`, `mcmtl_core_surface_encode_present` in `mcmetal_surface.m`, declared in the I1-private `mcmetal_core.h`); §2/§8.2 give the present pass to I2, which implemented its own | **Kept as in the contract: I2's PRESENT decode (with its own present PSO) is the production path** and is covered by `decoder_test` and the E2E present scene. I1's helpers stay as tested, unused utilities (no internal.h change); M2 MAY consolidate onto one implementation. |
| 2 | I1 | Build scripts do not link CoreGraphics (sRGB colorspace) | **Accepted I1's workaround**: `dlsym` from the CoreGraphics image QuartzCore loads. Build scripts unchanged. |
| 3 | I1 | Precompile order for `[[depth(any)]]` functions | **Accepted**; §7.3 updated (depth formats first; non-fatal depth-less failure when a depth variant exists). |
| 4 | I1 | Depth-stencil state in a pass without depth attachment aborts under the debug layer | **Accepted and applied in the decoder** (merge fix A); §4.4 SET_PIPELINE updated. Reproduced by the E2E fan scene (vanilla `DEBUG_TRIANGLE_FAN`, GEQUAL depth state, color-only pass): without the fix the process aborts with `MTLDepthStencilDescriptor sets depth test but MTLRenderPassDescriptor has a nil depthAttachment texture`. |
| 5 | I1 | "shader function retains its library": Metal returns one shared `MTLFunction` for byte-identical MSL; an associated library reference created a retain cycle | **Accepted**; §3.3 reworded. |
| 6 | I1 | `MTLCounterSampleBuffer` capped at 4096 samples (32 KiB) on M5 | **Accepted as implemented**: larger pools are created without a sample buffer (all samples UNAVAILABLE, device message); unwritten samples (0) map to `MCMTL_TIMESTAMP_UNAVAILABLE`. Vanilla pools: Tracy 1024, TimerQuery 6. No header change (a header comment would bump the ABI). |
| 7 | I1 | No `MCMTL_BUFFER_USAGE_UNIFORM_TEXEL_BUFFER` constant | **Declined for M1** (would bump the ABI); I1's private `MCMTL_CORE_USAGE_UNIFORM_TEXEL_BUFFER` (256 = `GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER`) stands. Add with the next ABI bump. |
| 8 | I1 | `mcmtl_device_create` returns `MCMTL_E_INTERNAL` when `mcmtl_decoder_device_init` fails; `mcmtl_device_destroy` returns OK (with a device message) after an idle-wait timeout or device loss | **Accepted** (Java maps every creation failure to `BackendCreationException`; `close()` never throws). |
| 9 | I1 | Per-device I1 state has no ivar in frozen `MCMTLDevice` | **Accepted**: `MCMTLCoreState` associated object, header `native/src/mcmetal_core.h` added to I1's row in §2. M3 MAY add a `coreState` ivar at the next internal.h change. |
| 10 | I2 | `END_PASS` (2) sits in the 1..15 "outside a pass" class but is only valid inside a pass; `StreamOps.isEncoderOp(2)` was true | **Accepted**: §4.1 updated; `StreamOps.isEncoderOp` now excludes `OP_END_PASS` (Java helper only, no constant/ABI change; merge fix B). The `mcmetal_stream.h` comment is left as is (this contract wins). |
| 11 | I2 | Timestamp sampling measurements (empty blit passes sample 0; one sample-buffer attachment per render pass) | **Accepted**; §4.4 TIMESTAMP and §14 updated. |
| 12 | I2 | Runs of adjacent timestamps (Tracy end-A/begin-B) | **Accepted**; §4.4 TIMESTAMP updated. |
| 13 | I2 | Region-precise blit hazard tracking | **Accepted** (same intent, fewer splits); §4.5 updated. The E2E copy scene covers write-after-read through a staged rewrite. |
| 14 | I2 | `INIT_TEXTURE` in the main stream | **Allowed** (executes in stream order); §4.2 updated. Java does not record it there in M1. |
| 15 | I2 | Decoder-private diagnostics (`mcmtl_decoder_get_stats`, `mcmtl_decoder_test_raise_at_record`) | **Accepted** as hidden (non-exported) symbols for native tests only. |
| 16 | I3 | Transient alignment `max(requested, 16)` is not a multiple of a non-power-of-two request | **Accepted**: `lcm(requested, 16)`; §9.3 updated. |
| 17 | I3 | §7.1 said `ShaderCache.close()` is reachable after the compile-drain timeout; §5.7/§9.6 say it is not | **§5.7/§9.6 win** (I3 implemented them); §7.1 reworded. |
| 18 | I3 | `forTesting(..., transientMemory, ...)` can only take null; `checkErrors()` after blocking fence waits only | **Accepted**; §9.1 and §5.4 updated. |
| 19 | I3 | `mcmtl_device_destroy` header says the caller "must" have released every other handle; Java cannot guarantee it (Tracy's pool, objects open at shutdown) | **Accepted as "should"**: natives never assert on it (verified: destroy releases its +1; leftover owner references keep the context alive, a leak at exit, not a crash). Header comment unchanged (ABI rule); this contract governs. |
| 20 | I4 | `setPipeline` of the pipeline already bound in the same pass records nothing and keeps the binding/dedupe state | **Accepted**: equivalent, because the frontend re-sends every uniform it holds right after `setPipeline` (those repeats are deduplicated) and the native set masks are unchanged; §4.4 updated. |
| 21 | I4 | `SET_INDEX_BUFFER` has no offset; `MetalBuffer.backingOffset()` is mutable (M2 renaming) | **Accepted workaround for M1** (non-zero index-buffer backing offset folded into `firstIndex` of direct draws; `drawIndexedIndirect` throws in that case; unreachable in M1, where `backingOffset()` is always 0). Next ABI bump: add `uint64_t offset` to `SET_INDEX_BUFFER` (16 → 32 bytes). |
| 22 | I4 | Copy formulas use `bs = blockSize` (8 for `D32_FLOAT_S8_UINT`) but `DepthFromDepthStencil` copies 4 bytes/texel | **Accepted**: `bs = 4` for `D32_FLOAT_S8_UINT` (depth plane) in §4.4's copy formulas. Vanilla never uses the format. |
| 23 | I4 | Spec/skeleton asked to pool `MetalRenderPass` objects; the frontend keeps calling a closed pass and §9.4 requires closed passes to throw | **Accepted**: one ~24-byte `MetalRenderPass` per pass, all binding state in an encoder-owned `MetalRenderPass.State`; nothing per draw. |
| 24 | I4 | 2D `COPY_BUFFER_TO_TEXTURE` with `sourceY > 0` near the end of a buffer: `bufferOffset + bytesPerImage` can pass the end | **No change needed**: the decoder passes `bytesPerRow * height` to Metal (not the record's `bytesPerImage`) and I4 realigns into staging when needed; the E2E copy scene copies the last 2×2 texels of a tightly sized buffer under validation. |
| 25 | I5 | Binding masks from `spvc_compiler_msl_is_resource_used` are larger than what Metal reads (145 sampler / 9 UBO slots over 270 variants) | **Accepted for M1** (dummies make it safe, cost is small). M2 MAY AND the masks with `MTLPipelineOptionBindingInfo` reflection inside `mcmtl_pipeline_create` (no ABI change). |
| 26 | I5 | §6.1 reading: fixed names `Sampler0/1/2` apply to the whole texture index space (texel buffers included); a fixed name only counts for its own kind | **Confirmed** as the normative reading of §6.1. |

### 16.2 Merge fixes (code changed at integration)

- **A. Decoder, depth-less passes** (`native/src/mcmetal_decoder.m`, `op_set_pipeline`): bind the Always/no-write
  depth-stencil state when the pass has no depth attachment (#4).
- **B. `StreamOps.isEncoderOp`** excludes `OP_END_PASS` (#10).
- **C. `tools/MetalDeviceTest.java`** (I3 test): the headless rig gives its handle-0 device's encoder a fake
  `submitFunction` (it reached the real `mcmtl_submit` through I4's `flushForWait` and poisoned
  `MtlNative$Downcalls` for the rest of the run), and the native part's empty submits go through
  `encoder.submit()`: a raw `mcmtl_submit` left the encoder's pending `INIT_TEXTURE` records behind while those
  textures' deferred releases ran, and the next flush-on-wait decoded a released handle (SIGSEGV in the test,
  impossible in production where every commit goes through the encoder).
- **D. `tools/i5-pipeline-test.sh` / `tools/i5_pipeline_harness.m`**: the real I1 `mcmetal_pipeline.m` is linked by
  default; the harness's reference pipeline exports are behind `I5_HARNESS_REFERENCE_PIPELINE`
  (`--reference-pipeline`).
- **E. New tests**: `tools/MetalE2ETest.java` + `tools/metal-e2e-test.sh` (§11.4), `tools/core-msl-test.sh`
  (runs I1's `CoreMslTest`).

### 16.3 Present fusion (M2 round 2, `MCMTL_PRESENT_FUSION`)

The frame's last passes render straight into the `CAMetalDrawable`; PRESENT then only registers
`presentDrawable:`. There is no present pass, and the main target is neither stored nor read for that frame.
No header, record layout or ABI changed. Java is unchanged. Code: `mcmetal_decoder.m` (planner, retargeting,
restore), `mcmetal_surface.m` (layer flip), `mcmetal_pipeline.m` / `mcmetal_internal.h`
(`MCMTL_VARIANT_KEY_BGRA8_COLOR0`).

| Toggle | Effect |
|---|---|
| `MCMTL_PRESENT_FUSION=1` (default) | Flipped layer, and fusion whenever the rules below hold. Other frames use the present pass as a straight copy. |
| `MCMTL_PRESENT_FUSION=0` | The previous path, exactly: layer untouched, flipping present pass, no planner, no pipeline indexing. Any value other than 0 or 1 (for example 2, the unimplemented strategy S2) logs a message and behaves as 0. |
| `MCMTL_PRESENT_FUSION_FLIP_ONLY=1` (with 1) | Diagnostics: flipped layer, never fused. Comparing it with `=0` isolates the cost of the flip on the display path (direct versus composited). |
| `MCMTL_PRESENT_FUSION_MAX_WAIT_US` (default 400, 0 = off) | Threshold of the adaptive guard (below). |
| `MCMTL_FUSION_TRACE=1` | Diagnostics (§16.7): logs each restore (the drawable's `presentedTime`) and follows the command buffers of the next 6 frames (scheduled, completed, status, GPU time, error). |

**Orientation (strategy S1).** The main target's memory row 0 is the bottom of the picture (GL/Vulkan memory order,
§5 of the spec). The drawable receives the same bytes in the same order. The layer is mirrored vertically:
`transform = (1, 0, 0, -1, 0, h·(1 − 2·anchorPoint.y))` in the layer's own space, i.e. about its centre.
`tools/layer-flip-probe.sh` checks this offscreen with `CARenderer`, without a window or a screen capture:
- `geometryFlipped` (on the layer or its superlayer) and a negative `contentsRect` do **not** flip the contents.
- The transform does, in place, both for the SDL view's `anchorPoint (0, 0)` and for `(0.5, 0.5)`.
- AppKit resets the transform of a view's backing layer to identity on every resize. It calls `setAnchorPoint:`,
  `setBounds:`, `setAffineTransform:(identity)`, `setPosition:`, in that order.

`mcmtl_layer_set_flipped` therefore isa-swizzles the layer, as KVO does, to a subclass whose `setTransform:`,
`setAffineTransform:`, `setBounds:`, `setFrame:` and `setAnchorPoint:` substitute the flip for the current bounds.
The flip then changes in the same transaction as AppKit's geometry, and never shows a frame the wrong way up. The
probe verifies this on a real layer-backed `NSView` across window and view resizes. `mcmtl_surface_destroy` removes
the flip. The decoder reads the layer's actual transform each frame (`mcmtl_layer_is_flipped`):
- Fusion requires the flip.
- The present pass copies straight on a flipped layer: the copy fills drawable rows `[dh − copyH, dh)`, which are
  top-anchored on screen, and the rest is cleared.
- The present pass flips as before on an upright layer.

Inside the fused passes, rasterization, viewports, scissors, render areas, winding, derivatives and `[[position]]`
are untouched: the drawable has the main target's size and memory layout. Partial-area passes, MRT passes (only
color 0 is retargeted) and fragment shaders that read `gl_FragCoord.xy` (OIT, `integrate_depth`) therefore behave
exactly as on the main target. Only the window shows the attachment mirrored. The BGRA8 drawable takes the same
values as the RGBA8 target, with R and B swapped by the format: blending is per channel in the same precision, and
unorm8 → float → unorm8 is exact. The drawable keeps the target's alpha. The present pass forced alpha to 1, but an
opaque `CAMetalLayer` ignores alpha (probe: an opaque layer composites a red drawable as red whatever its alpha).
Fusion requires `layer.opaque`.

**Rules (planner `dec_fuse_plan`, run per call after the look-ahead index, which now also lists the pipelines each
pass sets).** Let PRESENT present source S at level L:
1. Exactly one PRESENT, in a COMMIT call (§16.7: the fused frame's timeline value must be committed before a later
   call can restore from its drawable; otherwise fallback `no-index`). Its layer is flipped, opaque and BGRA8Unorm,
   and `drawableSize` equals the size of S@L. S is a single-sample 2D texture, RGBA8Unorm or BGRA8Unorm.
2. **Candidate:** the last pass before PRESENT that attaches S@L as color 0 over its full render area and clears it.
   The clear is either the pass's own, or a `CLEAR_TEXTURE` of S@L since the previous use of S that FOLD turns
   into its load action (vanilla C1/C2 → Sky).
   - Whatever was in S@L, or in the drawable, before that pass is never visible.
   - This is the conservative rule: the frame provably overwrites the whole level before anything of it shows.
     Nothing depends on what the next frame does.
   - The decision is re-checked when the pass begins: the load action must really be Clear over the full area.
3. From the candidate to PRESENT, S is only ever attached as color 0 at L, by any number of passes: merged into
   one encoder, or separate encoders that load the drawable.
   - Any other reference to S invalidates the candidate: sampling (the index's per-pass sampled set), copies,
     region clears, `INIT_TEXTURE`, other slots or levels, depth, or a new clear.
   - Any reference to S **after** PRESENT (readbacks, Tracy capture copies) means no fusion.
4. Every pipeline set in those passes has its `MCMTL_VARIANT_KEY_BGRA8_COLOR0` PSO variant.
   - Missing variants compile **asynchronously** (`newRenderPipelineStateWithDescriptor:completionHandler:`) from
     `mcmtl_pipeline_variant_descriptor`. They are handed to the render thread at the start of the next call;
     the frame meanwhile falls back. No hitch.
   - A variant that fails to build is remembered, and frames using that pipeline are never fused.
   - In-encoder depth-clear PSOs are keyed by attachment formats and built on demand (internal, once).

When the candidate pass begins, the decoder:
1. Commits the work encoded so far untimed (debug groups re-pushed), so the GPU runs it while the CPU may wait.
2. Calls `nextDrawable` (`stats.fusionWaitNs`, also counted in `drawableWaitNs`).
3. Binds `drawable.texture` (level 0) as color 0 of every planned pass. Late store actions always Store it.

PRESENT then registers the present (`MCMTL_PRESENT_MODE` honoured) on the call's last command buffer. If the
drawable is nil, `DRAWABLE_NIL` is reported without a second `nextDrawable`, and the frame renders into S as usual.
If its size or format does not match, or its texture has no IOSurface (a restore could not read it, §16.7), the
drawable is kept for the present pass (fallback `size` / `format`).

**Stale source (cross-call rule).** After a fused frame, S@L does not hold the frame. The decoder keeps (S, L,
drawable) and handles it at the start of the next call's main stream, which it scans raw, without the index:
- If S@L is fully overwritten before any read, the state is dropped. Overwrites are a color `CLEAR_TEXTURE`
  covering L, `INIT_TEXTURE`, or a full-area pass that clears it and does not sample S. Vanilla does this every
  frame: `GameRenderer.render` starts with `clearColorAndDepthTextures(main)`.
- If S@L is read or partly written first, it is **restored** before anything else in that call. Reads include
  `COPY_TEXTURE_TO_BUFFER` (screenshots: F2 and the bench `Scenes` harness grab the main target at the start of
  the next `renderFrame`), sampling, copies, PRESENT and region clears.
  - The restore is one render pass: color 0 is S@L (DontCare, Store). Its fragment shader reads the same pixel of a
    read-only texture that aliases the previous drawable's IOSurface (`newTextureWithDescriptor:iosurface:plane:`,
    usage ShaderRead, Shared). The drawable's own texture is `framebufferOnly` (usage RenderTarget only,
    lossless-compressed IOSurface `'&BGA'`), and it is **never attached to a render pass again once its present is
    registered** (§16.7: on screen, that blocks the GPU until a later present and hangs the timeline).
  - The alias is not hazard-tracked against the drawable's texture, so the restore's command buffer first waits on
    the GPU for the fused frame's timeline value (`encodeWaitForEvent`, skipped when the value is already
    signalled). The fused call committed that value (rule 1), so the wait cannot be on itself.
  - The copy is byte-exact. The drawable object and the alias stay alive until that command buffer completes.
  - A drawable without a readable IOSurface cannot be restored: a message, and the readback sees S@L as it was
    (never happens: rule above).
- If the call never references S, a FRAME_END call drops the state, so a drawable is never held past one frame.
  A non-FRAME_END call (flush-on-wait) keeps it.

A command buffer dropped by an exception, or one whose commit throws, also drops the state. The layer stays
`framebufferOnly = YES`.
`framebufferOnly = NO` would make drawables uncompressed `'BGRA'` (measured), costing write bandwidth on every frame.

**Adaptive guard.** Acquiring the drawable before the main encoder serializes the frame with drawable recycling.
With 2 drawables on a composited layer, a drawable comes back only after WindowServer has composited the previous
frame; with FIFO, only at the refresh. The present-pass path waits after committing the frame instead.
- The guard averages `nextDrawable` waits with α = 1/4.
- A wait only counts if the previous frame's timeline value was already signalled when the drawable arrived, i.e.
  the GPU had gone idle. Waits while the previous frame still runs are free.
- When the average exceeds the threshold, fusion is off for 300 frames (`fusionBackoffs`, fallback `backoff`).
  It is then retried.
- The period doubles while retries keep failing, up to 9600 frames, so a persistently drawable-bound setup pays
  two or three slow frames per period. 600 fused frames in a row reset it to 300.

**Statistics** (`mcmtl_decoder_stats`, logged with `MCMTL_DECODER_STATS=1` every 600 frames): `fusedFrames`,
`fusedPasses`, `fusionWaitNs`, `fusionRestores`, `fusionStaleDropped`, `fusionVariantBuilds`, `fusionBackoffs`,
and `fusionFallback[reason]` per PRESENT that was not fused. The reasons are: not-flipped, flip-only, no-index,
multi-present, format, size, no-clear, source-use, variants, backoff, drawable-nil, not-reached.

**Direct to display (unverified).** Nothing headless can show whether the mirrored layer stays eligible for
direct-to-display (`docs/metal/DIRECT-TO-DISPLAY.md`).
- Evidence that it should:
  - The transform is a rectilinear mirror of an opaque, unclipped, unnested layer.
  - QuartzCore's image-queue samples carry rotation and "flipped" attributes next to their detach state (strings
    `(0x%llx %s 0x%x, [%d %d; rot %d] ...) (detach 0x%016llx)` and ` flipped`), and bottom-up GL surfaces
    (`oglFlip`) have always been presented this way.
- Detach reasons that could still apply: `Rotation`, `NonCanonicalLayer`.
- To check on screen: with the overlays gone, use the Metal HUD (`MTL_HUD_ENABLED=1`: *Direct* / *Composited*) or
  the `didComposite` probe, and compare `MCMTL_PRESENT_FUSION=0` with `MCMTL_PRESENT_FUSION=1
  MCMTL_PRESENT_FUSION_FLIP_ONLY=1`.
- If the flip forces composition, set `MCMTL_PRESENT_FUSION=0`. The fallback is strategy S2: no layer flip; a
  negative-height viewport, the opposite winding and mirrored scissors and render areas. S2 needs every pipeline
  that reads `gl_FragCoord.xy` excluded and is not implemented.

**Tests.**
- `decoder_test`:
  - Fused frames byte-equal the unfused main target (alpha included) under six M2 configurations, merged and
    unmerged. Configurations without FOLD fall back with `no-clear` (M1: `no-index`).
  - The restore of the stale target before a next-call readback is byte-exact, also with a framebufferOnly
    drawable.
  - Regression (§16.7): after its present, a drawable is never attached to a render pass (every pass of the readback
    call is recorded through a test hook); the restore is exact while the fused frame is still held on the GPU (the
    queue is blocked by a test event, so the restore's event wait is encoded); a frame without COMMIT is not fused.
    Against the round-3 decoder this test fails 3 checks.
  - Fallbacks:
    - size mismatch: exact straight-copy layout with a cleared remainder;
    - readback after PRESENT;
    - partial first pass;
    - source sampled after the clear;
    - nil drawable: the main target is still rendered;
    - upright layer: classic flip;
    - flip-only mode.
  - Stale state: dropped when overwritten, kept by a non-FRAME_END call, dropped by an empty frame.
  - The adaptive guard, asynchronous variants, and the layer-flip setter overrides.
- `MetalE2ETest`: the present scene renders the vanilla GUI pipeline into the real `MetalSurface` for 8 frames,
  with a readback of the main target at the start of each next frame. In each label pass, 7 of the 8 frames fused
  (the first fell back while the BGRA8 variant compiled), and every readback equalled the GUI scene.
- `FUSION_BENCH=1 MCMTL_PROFILE_PASSES=2 FUSION_BENCH_FRAMES=600 build/native-tests/decoder_test` (opt-in)
  serializes per-encoder GPU time of a vanilla-shaped frame at 3600x2260:

  | Encoder | Present pass | Fused |
  |---|---|---|
  | Present pass | 0.60–0.65 ms | gone |
  | Merged Sky..GUI encoder | 0.78–0.83 ms | 0.84 ms (same) |
  | Total GPU per frame | 2.09 ms | 1.51 ms |

### 16.4 Mailbox presentation with vsync off (`MCMTL_MAILBOX`)

**Problem.** With vsync off, the render thread spent 1.0–1.8 ms per frame blocked in `-[CAMetalLayer nextDrawable]`
(decoder stats, fullscreen 3600x2260), and the GPU was only ~51% busy. On today's composited fullscreen path a drawable
comes back only after WindowServer has composited the previous one (2 drawables) or at the refresh (3 drawables, strict
FIFO at 120 Hz; `docs/metal/DIRECT-TO-DISPLAY.md` §2.2).

**Decision.** For a surface configured without `MCMTL_SURFACE_FLAG_DISPLAY_SYNC`, the render thread never calls
`nextDrawable`. A helper thread per surface (the *acquirer*) blocks in it instead and parks the drawable it gets. The
decoder only takes a parked drawable, without waiting. A frame that finds none is rendered completely into the game's
target and not presented. This is what Vulkan's MAILBOX does: frames that cannot be shown in time are dropped, and the
display gets the newest finished frame. FIFO keeps today's blocking path unchanged. No header, record layout or ABI
changed; Java only gained counters.

Alternatives that were rejected:
- **A presenter thread fed through a ring of present-source textures.** It costs a full-screen copy per presented frame
  (0.5–0.6 ms at 3600x2260, the present pass) and gives up present fusion.
- **Predicting a free drawable by counting presents in flight.** This still blocks whenever the prediction is wrong.
  No public API reports when Core Animation recycles a drawable.

The acquirer is exact: either a drawable is parked or it is not.

| Toggle | Effect |
|---|---|
| `MCMTL_MAILBOX=1` (default) | Vsync off: mailbox presentation as described here. FIFO: unchanged. |
| `MCMTL_MAILBOX=0` | The previous path, exactly: Java's `IMMEDIATE_DRAWABLE_COUNT` (2) drawables, blocking `nextDrawable` on the render thread, early commits, the fusion guard. |
| `MCMTL_MAILBOX_DRAWABLES` (default 3, or 2) | `maximumDrawableCount` in mailbox mode. It overrides what Java passes. |
| `MCMTL_MAILBOX_MAX_QUEUED` (default 1, 0 = no limit) | Queue gate: a drawable is taken only while fewer presented frames than this still wait for the display. |
| `MCMTL_MAILBOX_QUEUE_TIMEOUT_MS` (default 100) | After this long, the gate stops waiting for a presented handler that never came. |
| `MCMTL_MAILBOX_LATE_PRESENT` (default 0) | 1: present a drawable that arrived after the fusion pass missed, through the present pass. 0: leave it for the next frame, which renders straight into it. |
| `MCMTL_DRAWABLES` | Still overrides the drawable count in every mode (experiments). |

**Visual effect: none, so the default is on.**
- Every frame is rendered exactly as before. The parity harness reads the main target, which holds the complete frame
  whether it was presented or not: skipped frames render into it, and fused frames restore it.
- Only the choice of frames that reach the display changes. On a composited layer that is at most one frame per
  refresh, and it is a newly finished frame each time. Today WindowServer composites up to ~300 presents per second,
  but the 120 Hz panel shows 120 of them.
- There is no tearing on the composited path. Nothing can pop: no content depends on which frames are presented.

**Latency.** With the gate at 1, each refresh shows a frame that finished during the previous refresh. A deferral adds up
to one frame time. The 2-drawable blocking path showed a frame composited at most ~1.7 ms before the refresh, so the
mailbox can show frames up to one refresh (8.3 ms) older on a composited layer. With `MCMTL_MAILBOX_MAX_QUEUED=0`,
presents queue FIFO two deep on a composited layer, which adds another refresh. Going lower than gate 1 would need presents
timed to the vblank (not implemented).

**Layer properties per mode.** These match the best configuration in `DIRECT-TO-DISPLAY.md`.
- Every mode: BGRA8Unorm, `framebufferOnly = YES`, opaque, sRGB, EDR off, `presentsWithTransaction = NO`,
  `allowsNextDrawableTimeout = YES`, the present-fusion flip, and `presentDrawable:` (`MCMTL_PRESENT_MODE=0`).
- The scheduled-handler present modes remain experiments. On the composited path they produced the flat 240 FPS pattern.

| Mode | `maximumDrawableCount` | `displaySyncEnabled` | Who calls `nextDrawable` | Why |
|---|---|---|---|---|
| FIFO | 3 | YES | Render thread, blocking (at the fusion pass or PRESENT, after an early commit) | Unchanged; the refresh is the throttle. |
| IMMEDIATE, mailbox (default) | 3 | NO | Acquirer thread | Vanilla's count and the best on the direct path (`DIRECT-TO-DISPLAY.md` §3, step 3). The composited 3-drawable pacing no longer holds back rendering, and WindowServer composites at most one present per refresh instead of every frame. |
| IMMEDIATE, `MCMTL_MAILBOX=0` | 2 | NO | Render thread, blocking | The 4c123c2 workaround for the 120 FPS cap. |

**Mechanism** (`mcmetal_surface.m`, `MCMTLMailbox`; internal API in `mcmetal_internal.h`):
- **Acquirer.** One detached pthread per surface, QoS user-interactive. It starts at the first vsync-off configure. It
  waits until acquisition is enabled and nothing is parked. Then it calls `nextDrawable` in its own autorelease pool,
  which blocks for up to 1 s.
  - It parks the drawable unless a configure, disable or shutdown happened in the meantime (generation counter). In that
    case it releases the drawable unpresented.
  - After a nil drawable it backs off for 2, 4, … up to 128 ms. A configure wakes it at once.
- **Take** (`mcmtl_mailbox_take`, render thread).
  - The queue gate is checked first. Then the parked drawable is popped under a mutex (no waiting), and the acquirer is
    woken for the next one.
  - A parked drawable whose size no longer matches `layer.drawableSize` (live resize) is dropped. The skip reason is
    *stale*, and a drawable of the new size follows.
- **Queue gate.**
  - `mcmtl_mailbox_will_present` runs before every present registration, in every mode. It counts the present as queued
    and adds the drawable's presented handler. The handler ends the count and records display statistics.
  - The count includes presents whose handler has not run yet. A present stays queued until its handler runs.
  - Handlers lost for longer than the timeout are forgiven (`gateTimeouts`).
- **Configure and destroy.** `configure` drops any parked drawable, because the size or mode may change, then enables or
  disables acquisition. `mcmtl_surface_destroy` shuts the mailbox down. The acquirer exits by itself after its current
  `nextDrawable` and releases whatever that returns.
- **Decoder.**
  - The fusion pass takes a drawable instead of calling `nextDrawable`.
    - If none is available, the state becomes SKIP with fusion fallback reason `no-drawable`, and the frame renders into
      S.
    - There is no early commit, because nothing waits. The adaptive guard is idle: a mailbox surface neither applies nor
      runs down a backoff left over from blocking mode.
  - At PRESENT:
    - A fused frame registers its present.
    - A SKIP frame is not presented, unless `LATE_PRESENT` is set. The reason is *deferred* if a drawable was parked
      since the fusion pass, otherwise the fusion pass's reason.
    - Any other frame takes a drawable. It then uses the present pass, or is not presented.
  - A frame that is not presented never reports `DRAWABLE_NIL`. Java would turn that into a suboptimal surface and a
    reconfigure every frame.

**Guarantees.**
- **Never an incomplete frame.** `presentDrawable:` is registered on the command buffer that renders the frame (unchanged),
  and Core Animation shows the drawable after that GPU work.
- **Never out of order.** Presents are registered in submit order on one queue. A frame is presented in its own submit or
  never; nothing is held back to present later.
- **Timeline unchanged.** There is one signalling command buffer per submit, the signal-consumption rule (§4.2) holds, and
  the Java throttle still allows 2 frames in flight.
- **No leaks.**
  - At most one drawable is parked. Take, configure, disable and shutdown release it.
  - Every acquisition runs in an autorelease pool.
  - A drawable taken at the fusion pass whose PRESENT is never decoded is released at the end of the call (as before).
  - `decoder_test` G shows that after shutdown all three of the layer's drawables can be acquired at once.

**Finding** (measured on an offscreen layer, M5, macOS 27; probes in this round):
- A command buffer that presents a drawable runs its completion handler only when Core Animation processes the present,
  about 8.2 ms (one refresh) after commit.
  - Its GPU work started 0.4 ms after commit and ran for 0.01 ms.
  - Its shared-event signal arrived 0.31 ms after commit.
  - A command buffer committed after it is not delayed.
  - Rendering into a parked drawable without presenting completes at once, so a parked drawable carries no GPU-side
    wait.
- The game's throttle (`mcmtl_timeline_wait` returns on the event signal, §5.1) is therefore unaffected.
- Only completion-driven recycling lags by up to a refresh for presented frames: decoder ring chunks and the
  `completedValue` prefix. That was already the case, and fewer frames are presented now.

**Statistics.** Logged with `MCMTL_DECODER_STATS=1` every 600 frames.
- The line reads: `mcmtl present (mailbox, 3 drawables, queue 1): N frames rendered (x/s), presented P (y/s; fused F),
  not presented S (no drawable a, queue full b, deferred c, stale d), nil n; render-thread nextDrawable t ms/frame;
  acquirer: k drawables, w ms avg wait, nil, discarded; display: shown, dropped by Core Animation, present->on screen
  L ms avg; gate timeouts`.
- New `mcmtl_decoder_stats` fields: `presentRecords`, `presentsEncoded`, `presentsNil`, `mailboxRecords`,
  `presentsSkipped`, `presentSkip[4]`, and fallback reason 12 (`no-drawable`).
- With `MCMTL_PROFILE_PASSES`, the profile's frame unit is now each PRESENT record, presented or not. Per-frame GPU
  averages therefore stay per rendered frame.
- Java: `MetalSurface.presentStats()` returns frames, presented, not presented, nil and occluded (indices `STAT_*`).
  `resetPresentStats()` resets them. The bench (`bench/src/bench/PresentStatsHook.java`) reads both by reflection and
  writes a `"present"` block with `presentedFps` and `presentedShare`.

**Tests.**
- `decoder_test` `test_mailbox` uses a fake drawable source: the acquirer takes a real drawable from an offscreen layer
  only when the test hands out a permit.
  - A: Core Animation has no free drawable (the test holds all three) and none is parked. Frames return at once, are
    complete, are not presented, and are counted. There is no `nextDrawable` on the render thread and no early commit.
  - B: once a drawable is parked, the next frame fuses into it and shows the newest frame.
  - C: the queue gate, and its timeout.
  - D: deferral to the next frame, and `LATE_PRESENT` through the present pass (straight copy).
  - E: a stale-size parked drawable is dropped.
  - F: with the mailbox off (FIFO), the blocking path is exact (early commit, fused). It shows the same bytes as the
    skipped frame of A.
  - G: shutdown while the acquirer is inside `nextDrawable`; no leaked drawable.
  - H: the real offscreen source (drawables come back at the refresh), submitted like the game. The mailbox ran at
    1500–2270 FPS and presented about one frame per refresh; the blocking path on the same surface ran at 120 FPS.
- `core_test` checks the drawable count and mailbox state per mode, following the environment.
- `MetalE2ETest`:
  - The exact per-frame flag checks run in FIFO.
  - Fused IMMEDIATE frames are checked without requiring every one to present.
  - `mailboxFrames` drives the real `MetalSurface` over real Core Animation drawables: 3500–6100 FPS with the mailbox
    against 124 FPS in FIFO. `presentStats` matches the flags, and the newest frame is complete.
  - The section honours `MCMTL_MAILBOX=0`.
- `I4EncoderTest.testSurfacePresentStats` covers the Java counters.
- `FUSION_BENCH=1 build/native-tests/decoder_test` (opt-in) has a fourth row: the vanilla-shaped 3600x2260 frame,
  fused, with the mailbox on, on an offscreen layer whose drawables come back at the refresh.

  | Row | ms/frame | CPU submit/frame | Presented |
  |---|---|---|---|
  | Present pass, fused or flip-only (blocking) | 8.34 (120 FPS) | 8.33 ms (in `nextDrawable`) | 460/460 |
  | Fused + mailbox | 0.15 (≈ 6500 FPS, GPU/CPU bound) | 0.02–0.03 ms | 10–11/460 (≈ 1 per refresh) |

**Expected in game** (not measured here; the orchestrator benchmarks):
- At an average of ~310 FPS the frame takes 3.2 ms, of which 1.0–1.8 ms is the `nextDrawable` wait. Without it the
  render thread needs ~1.8 ms per frame, so the CPU-bound ceiling is ~550 FPS.
- Our GPU time is ~1.65 ms per frame (51% of 3.2 ms). WindowServer composites ≤ 120 instead of ~300 presents per second,
  which should cut its share from ~12% to ~5%. That leaves a GPU-bound ceiling of roughly 575 FPS.
- With 2 frames in flight, expect an average of roughly 420–520 FPS (+35–65%), the median about the same, and 1% lows
  up too (no frame waits for a drawable).
- Presented frames are about 120 per second on the composited path, or more when direct. The bench's `"present"` block
  shows it, and the log's `present->on screen` gives the display latency.
- If the GPU becomes the limit, the stats show `render-thread nextDrawable 0.000` and the Java throttle wait grows
  instead.



### 16.5 Terrain ICB (M2 round 3, `MCMTL_TERRAIN_ICB`)

A `DRAW_INDEXED_INDIRECT` record of N ≥ 64 argument records (vanilla terrain: one per layer and uber heap, 3 per frame at
RD 12 with ~860 records) becomes **one** `executeCommandsInBuffer:withRange:` of an `MTLIndirectCommandBuffer` instead of
N `drawIndexedPrimitives:…indirectBuffer:` calls. No header, record layout, opcode or ABI changed; Java is unchanged.
Code: `mcmetal_decoder.m` (ICB path, cache, stats), `mcmetal_pipeline.m` (kept MSL, MSL rewrite, ICB variant
descriptor), `mcmetal_resources.m` (argument-buffer samplers), `mcmetal_internal.h` (`MCMTL_VARIANT_KEY_ICB`,
`MCMTL_ICB_ARG_BUFFER_INDEX`, `mcmtl_terrain_icb_env`, `mcmtl_shader_keep_source`, `mcmtl_msl_icb_rewrite`).

| Toggle | Effect |
|---|---|
| `MCMTL_TERRAIN_ICB=1` (default) | ICB ranges for indexed indirect draws of ≥ `MIN_RECORDS` records whose pipeline has an ICB variant. `mcmtl_shader_create` keeps every MSL source (a few MB), `mcmtl_sampler_create` makes every sampler argument-buffer capable. |
| `MCMTL_TERRAIN_ICB=0` | The previous per-record path exactly: no kept sources, samplers as requested, no ICB variants. |
| `MCMTL_TERRAIN_ICB_MIN_RECORDS` (default 64) | Smaller groups draw per record (one execute costs ≈ 0.6 µs CPU + 0.3 µs GPU, one record saves ≈ 38 ns CPU and costs ≈ 12 ns more GPU). 1 = every group. |
| `MCMTL_TERRAIN_ICB_SYNC=1` | Diagnostics / headless tests: ICB variants compile on the render thread at first use (a hitch), so the first frames already use them. |

**Options measured** (U4 probe `tools/terrain-icb-probe.sh`, M5; then the decoder itself, below). GPU cost per draw is
small on M5: ≈ 11 ns per direct or indirect draw (843 one-pixel draws: 0.1437 ms vs 0.1345 ms for one draw).
- Per-record indirect draws (M1/M2): ≈ 30–45 ns CPU per record, ≈ 11 ns GPU.
- CPU-decoded direct draws: the same CPU (the Metal call dominates) and GPU. No gain; not kept.
- Merging contiguous records: nothing to merge. Each section has its own `baseInstance` and `baseVertex`, and
  `TerrainRuns` already merges a section's adjacent runs. Not kept.
- Single draw with vertex pulling: needs a rewritten vertex stage (fetch, per-vertex record lookup) for every terrain
  pipeline, costs vertex-shader time, and the per-draw overhead it removes is ≈ 10 µs of GPU per frame. Not pursued.
- ICB, CPU-encoded every frame: 22 µs vs 26 µs CPU (843 records); GPU per command ≈ 12 ns more than a draw.
- ICB, cached (re-encoded only when the records change): **5 µs CPU**; same GPU as above.
- ICB, GPU-encoded by a compute kernel before the pass: 7 µs CPU, +3 µs GPU for the dispatch, and it needs the kernel
  hoisted before the merged main encoder. The cached CPU encode is as cheap without that. Not kept.
- `executeCommandsInBuffer` itself: ≈ 0.6 µs CPU and 0.25–0.3 µs GPU per call (`PROBE_SPLIT`), hence the threshold.
- An ICB-capable PSO costs nothing on ordinary draws (`supportIndirectCommandBuffers` with direct draws: same GPU time).
  Enabling it on all pipelines is therefore not the cost; the shader restriction below is.

**Metal refuses ICB-capable PSOs whose shaders bind textures or samplers directly** ("Fragment shader cannot be used
with indirect command buffers"; buffers are fine). Hence the ICB variant (`MCMTL_VARIANT_KEY_ICB`, combinable with
`MCMTL_VARIANT_KEY_BGRA8_COLOR0`):
- `mcmtl_msl_icb_rewrite` edits the SPIRV-Cross entry point. Its `[[texture(n)]]` / `[[sampler(n)]]` parameters move into
  `struct mcmtl_icb_args` at `[[buffer(15)]]` (texture n's `MTLResourceID` at byte 16n, sampler n's at 16n + 8). The body
  starts by copying them into locals of the same names. Helpers and math are untouched.
- Entry points without textures stay unchanged. Texture arrays, slots ≥ 16, and an existing `[[buffer(15)]]` are
  refused.
- Index 15 is vertex-buffer slot 15's index. Vanilla never binds that slot, and pipelines that read it fall back.
- Functions are compiled with the original compile options, once per function (cached on the kept source), off the
  render thread. The variant also checks that the shader's texture slots lie inside the pipeline's binding masks.
- The variant is requested at the first eligible draw. It compiles on a global queue and is handed over through the
  present-fusion mailbox at the start of the next call. Meanwhile the draws go per record (`variant-pending`). Failures
  are remembered (`variant-failed`).

**Rules (`dec_icb_draw`).**
1. Eligible: an indexed indirect draw with ≥ `MIN_RECORDS` records, not fan-emulated, `[[buffer(15)]]` neither bound as
   vertex slot 15 in the pass nor used by the pipeline, and argument records that are CPU-visible.
2. The records are not the destination of a decoder copy (`COPY_BUFFER`, `COPY_TEXTURE_TO_BUFFER`) that has not
   completed. The decoder keeps every copy destination with the timeline value that completes it: the open work commits
   with `submittedValue + 1`, because values are dense. Vanilla's records come from the CPU-written `MAP_WRITE` ring;
   a staged `writeToBuffer` would be caught here (`gpu-written-args`).
3. Cache: the n-th ICB draw of a call uses slot n. It reuses the slot's commands when the 20-byte records, the index
   buffer (object, retained by the slot, so its pointer cannot be reused), its length, the index type and the primitive
   type are unchanged (`memcmp`). Otherwise the records are encoded on the CPU:
   - Records with a zero count, or an index range outside the index buffer, become `reset` (no-op) commands.
   - An ICB the GPU may still execute is never rewritten (`lastUse` > completed value). It is retired, and a completed
     retired ICB or a new one is used. Creating an ICB costs 0.05–0.4 ms, so up to 48 are kept.
4. The ICB variant is bound in place of the pipeline's PSO. Depth-stencil, cull, winding, fill, depth bias, viewport and
   scissor are inherited from the encoder.
5. Per stage that samples, an argument buffer is written to the upload ring with the resource IDs of the textures and
   samplers in the shadow state (dummies included). It is rewritten only when they change within the encoder.
   `useResource:Read` covers those textures and the index buffer, once per encoder.
6. A per-record or direct draw after an ICB range restores the pipeline's own PSO. Exception: a small group, a
   GPU-written group or a cache-overflow group of the same `SET_PIPELINE` keeps the ICB variant bound with a current
   argument buffer. That avoids PSO ping-pong between small and large groups.
7. Present fusion: the fused main encoder uses the BGRA8 + ICB variant. Pass merging, folding, late stores and hoisting
   are unaffected: the ICB path only replaces draw calls inside a pass.

**Statistics** (`MCMTL_DECODER_STATS=1`, line `mcmtl terrain ICB`, also printed at device shutdown):
- `icbGroups`: ICB ranges executed per frame, with their `icbRecords`.
- `icbHits` and `icbEncodedRecords`: cache reuse and CPU-encoded commands.
- `indirectRecordDraws`: records still drawn one by one.
- `icbCreated`, `icbArgBuffers`, `icbVariantBuilds`.
- `icbFallback[reason]`: variant-pending, variant-failed, fan, gpu-written-args, slot15, small, cache-slots, alloc,
  occlusion (added at the §16.6 merge: a command the occlusion planner covers is drawn per record).
- `draws` now counts an ICB execute as one Metal draw command. `drawsEncoded` in the submit result still counts records.

**Tests.**
- `decoder_test` uses vanilla's real SPIRV-Cross terrain MSL from `core_test_msl.h`, with SOLID / CUTOUT / blended
  TRANSLUCENT pipelines, 16- and 32-bit index buffers, `baseVertex` and `firstIndex` runs, a zero-count record and an
  `instanceCount` 2 record. Color and depth must equal the per-record path byte for byte for:
  - first frame (encode) and second frame (all cached);
  - changed records (re-encoded in place);
  - a slot still in use by an uncommitted call (a new ICB);
  - an atlas swap between groups (argument buffer rewritten) and a direct draw after the ranges (PSO restored);
  - mixed group sizes (ICB variant kept);
  - records written by a GPU copy in flight, slot 15 bound, small ranges, and a pipeline without kept MSL (all fall back);
  - the asynchronous variant (first frame per record, later frames ICB);
  - a fused frame (drawable bytes).
- `decoder_test` also checks the MSL rewrite on the terrain VS/FS and on the clouds VS (texel buffer), plus its refusals.
- `tools/terrain-e2e-test.sh --icb-parity` runs `TerrainE2ETest` twice through the real Java backend and MslTranslator
  (8 camera poses, 5 record layouts each): per record (`MCMTL_TERRAIN_ICB=0`), then ICB from the first frame
  (`MCMTL_TERRAIN_ICB=1 MCMTL_TERRAIN_ICB_SYNC=1`). Every pose's color + depth digest must match.
- `tools/terrain-icb-probe.sh` covers the U4 edge cases under `MTL_DEBUG_LAYER=1`.

**Measurements.** `TERRAIN_BENCH=1 build/native-tests/decoder_test`: vanilla terrain shaders, 400 sections, 1037 records,
208k quads, 3600x2260. Frames are serialized (GPU time from the status page). CPU is the `mcmtl_submit` wall time of the
terrain frame. Medians; per-record and ICB frames are interleaved.

| Groups | Path | GPU / frame | CPU / frame |
|---|---|---|---|
| 3 (1 heap per layer, like RD 12) | per record | 1.081 ms | 53–57 µs |
| 3 | ICB, records unchanged (cache hits) | 1.083 ms | **15 µs** |
| 3 | ICB, records change every frame | 1.086 ms | 45 µs |
| 129 (43 small heaps) | per record | 1.080 ms | 63–65 µs |
| 129 | ICB, threshold 64 (all groups per record) | 1.080 ms | 63–65 µs |
| 129 | ICB forced on every group (`MIN_RECORDS=1`) | 1.181 ms | 128 µs |

In game, expect the decoder's ~0.16 ms/frame to drop by ~0.04 ms at RD 12. GPU time is unchanged within noise
(+1–5 µs). Both the CPU and GPU wins of collapsing terrain draws are small on M5: per-record draws were already cheap.

### 16.6 Terrain occlusion culling (round C, `MCMTL_OCCLUSION`, default off)

Decoder only (`mcmetal_decoder.m`, section "Terrain occlusion culling"). No header, record, ABI or Java change.
Design, measurements and risks: `docs/metal/OCCLUSION.md`.

**What it does.** Sections of multidraw terrain whose box is hidden behind the opaque terrain are not encoded.

- **Evidence.** The evidence comes from Metal visibility results, Boolean. They are counted on chip and written at
  the end of the encoder.
  - Opaque terrain records count into one slot per run of consecutive records of a section. Metal allows each
    offset only once per encoder.
  - Box queries are drawn right after the last opaque terrain record, before the next pipeline or END_PASS. That
    is before entities, translucent terrain, clouds and the in-encoder depth clear. A box query is origin +
    `[-2, 18]³`, vertex-only, colour writes off, GEQUAL without depth writes, front faces only.
  - Results are read once the frame's timeline value has completed, from a ring of 8 Shared query buffers.
- **Cull rule.** A section is culled when all of the following hold:
  - its last `MCMTL_OCCLUSION_CONFIRM` box queries (default 2) had no sample, each entirely inside the frustum;
  - the newest of them is at most 8 frames old;
  - its box is entirely inside the frustum now;
  - it is farther than `MCMTL_OCCLUSION_NEAR` blocks (default 8).
- **What resets evidence.**
  - Any change of the Projection matrix, CameraBlockPos, CameraOffset, or the translation / projective row of
    ModelView drops all evidence. There is no culling while the camera moves, bobs or zooms; rotation is fine.
  - Records that change, or a section that vanishes while its box still meets the frustum, drop the evidence of
    every section overlapping it on screen.
  - A frame without a plan drops everything.
- **Classification and identity.**
  - Terrain pipelines are recognised from their vertex layout (Position float3 in slot 0, per-instance int3
    ChunkPosition in slot 1, stride 16), the UBO slots 0 / 1 / 5, and the depth / blend state.
  - Section identity is the origin read from the instance stream at `baseInstance`. It is resolved on the CPU
    through the call's COPY_BUFFER records: the section infos are a staged upload. Anything unresolvable means no
    plan for that frame.

**Status.** Not exact by construction: sub-pixel occluder gaps hit by a rotated camera's pixel centres, animated
cutout textures, and 2–3 frames of latency before a gap is seen. It therefore stays off.

**Tests.**
- `occlusion_test`: 678 frames, 0 differing bytes of colour or depth.
- `tools/occlusion-e2e-test.sh`: 180 identical frames with vanilla pipelines, about 21 % of the quads culled.

**Measured** (terrain-only, 3600x2260):
- the bench-like view, 40 above, spinning: between +0.009 and −0.003 ms GPU per frame;
- ground level: −0.015 to −0.06 ms pipelined, −0.07 to −0.12 ms serialized.

A depth-pyramid build alone costs 0.15 ms at this size, which is why the HiZ design was not built.

| Toggle | Effect |
|---|---|
| `MCMTL_OCCLUSION=0` (default; unset) | Off: no index additions, no queries, the decode path unchanged. |
| `MCMTL_OCCLUSION=1` | Cull. |
| `MCMTL_OCCLUSION=2` | Dry run: every query and statistic, nothing culled (`would cull` in the log). |
| `MCMTL_OCCLUSION_CONFIRM` (2), `MCMTL_OCCLUSION_NEAR` (8), `MCMTL_OCCLUSION_MIN_AGE` (0) | Box results required; minimum box distance in blocks; minimum result age in frames (test knob). |
| `MCMTL_DECODER_STATS=N` | `N > 1` logs every N frames instead of 600. Adds the `mcmtl occlusion` line: planned frames, sections, culled / would-cull, records and quads skipped, queries, planner time, resets, invalidations, results used / dropped, and no-plan reasons. |

Statistics struct: the `occl*` counters were appended to `mcmtl_decoder_stats`. `decoder_test` and `occlusion_test`
mirror it.

**Integration (merge of §16.4, §16.5 and §16.6).**
- With `MCMTL_OCCLUSION=1` or `=2` and `MCMTL_TERRAIN_ICB=1`, every DRAW_INDEXED_INDIRECT the occlusion planner has
  a plan for takes the per-record path. Culled sections must stay out of the encoder, and opaque records must switch
  their visibility slot between records, which one ICB range cannot do. It is logged as the ICB fallback reason
  `occlusion` and counted in `indirectRecordDraws`. Commands outside a plan (moving camera, unresolved frames, other
  pipelines) still use ICB ranges. With occlusion off (default) nothing changes for the ICB path.
- Statistics layout order: fusion fallbacks (13 reasons, incl. `no-drawable`), presentation counters (§16.4), ICB
  counters (§16.5, 9 fallback reasons), occlusion counters (§16.6). `decoder_test` and `occlusion_test` mirror it.
- Section numbers: mailbox presentation kept §16.4; terrain ICB is §16.5; occlusion culling is §16.6 (code comments
  were updated to match).

### 16.7 Present fusion: GPU hang in the scene runner (round 4 fix)

**Symptom.** With `MCMTL_PRESENT_FUSION=1` the in-game 18-scene runner crashed after 0–2 scenes with
`IllegalStateException: 5s timeout reached when waiting for the GPU` (`MetalCommandEncoder.submit`, frame throttle).
It reproduced with `MCMTL_MAILBOX=0` and with `MCMTL_TERRAIN_ICB=0`, never with fusion off, and never in the static
benchmark, which takes no screenshots.

**Root cause.** The scene runner takes each screenshot at the start of the next `renderFrame` (`Screenshot.grab` ->
`copyTextureToBuffer` of the main target), like F2. When the frame before was fused, the main target is stale and
the decoder restored it. The round-3 restore attached the previous drawable as color 0 of a render pass (Load and
Store, read through programmable blending). That drawable's present was already registered. Full-screen
(direct-to-display) it is on screen, and a render pass on it does not complete: the GPU waits until the display
releases the surface. That needs a later present, and every later present is queued behind the blocked command
buffer. After 5 s the GPU watchdog kills the command buffer, and the frame's timeline value never reaches the game.
- `MCMTL_FUSION_TRACE=1` showed it in game. The restore's command buffer had `GPUEndTime - GPUStartTime` = 5134.6 ms
  and status Error, `Caused GPU Timeout Error (00000002:kIOGPUCommandBufferCallbackErrorTimeout)`. The next frame's
  command buffer failed the same way (0 ms GPU time). The drawable's `presentedTime` was 0.5 ms before the restore,
  so it was on screen.
- With a DontCare store action on the drawable (experiment), the restore hung the same way. Any render-pass use of
  the presented drawable blocks, not only writing it.
- The scenes that passed were the ones whose previous frame was not fused. With the mailbox, about two of three
  frames are not presented, so not fused. The stats lines of a crashed run showed `restores 0`: the restore that hung
  was never logged.
- Headless tests could not see it. Their drawables come from offscreen layers, which are never on a display.

**Fix** (`mcmetal_decoder.m`, no ABI or Java change):
- The restore reads the drawable's IOSurface through a read-only alias texture and writes S@L in a pass of its own.
  The drawable is never attached again (§16.3, stale source).
- The restore first waits on the GPU for the fused frame's timeline value, because the alias is not hazard-tracked.
- Fusion requires a COMMIT call, so that value is committed, and a drawable texture with an IOSurface.
- When the commit of a call's command buffer throws, the stale state is dropped (its fused frame never ran).
- The old programmable-blending restore PSO and shader are removed.

**Verification.**
- `decoder_test`: the regression test in §16.3, Tests. All headless suites pass.
- In game with the fix: `MCMTL_FUSION_TRACE=1` shows restores from drawables that were on screen (`presentedTime`
  set). Their command buffers complete in about 2–5 ms, and parity is ok for all 18 scenes.
- Default build (fusion on, no env), 18-scene runner: 3 consecutive runs, 18/18 scenes each.
  - Strict ImgDiff ok in every run.
  - 6, 7 and 6 restores; 0 GPU timeouts.
- Same world, fusion on against off (2 runs each), checked with `tools/PerceptualDiff.java`.
  - Deterministic scenes are within the bar in every pair.
  - Animated and random scenes stay at the run-to-run noise: water texture phase, mob animation, particles.
  - Against vanilla Vulkan, the on and off runs get identical verdicts.
- Run log: `results/ROUND4-FUSION.md`.

**Decision (round 4): `MCMTL_PRESENT_FUSION` defaults to 1 again** (`mcmetal_surface.m`).
- Static scorecard, interleaved in-game A/B at RD 12, same frozen jar, 3 runs each, fusion on against off:
  - median 643.8 against 602.7 FPS (+6.8%);
  - average 616.2 against 590.6 (+4.3%);
  - 1% low 376.8 against 348.4 (+8.2%).
- Every on run beat every off run on median and average.
- Travel scorecard (Fancy, RD 16, 2 runs each): neutral within noise (median 294.5 against 289.5, average 271.1
  against 270.6).
- `MCMTL_PRESENT_FUSION=0` remains the exact previous path.

### 16.8 Terrain shader specialization (TASK S, `-Dmcmetal.terrainSpecialize`, default on)

Java only: `TerrainShaderVariants` (new), `MetalRenderPipeline` (one or two PSOs per pipeline, `handle()` picks per
frame), `GameRenderer` (reports the frame's `UseRgss`; installs the resource-pack gate), `MslTranslator.StageResult.withMsl`
(diagnostics). No native, header, record, opcode or ABI change; Vulkan and OpenGL only see two static setters.

**Why.** `core/terrain.fsh` samples with `UseRgss == 1 ? sampleRGSS(...) : sampleNearest(...)`, a branch on the Globals
UBO. SPIRV-Cross keeps it, so every terrain fragment shader holds both paths and Metal allocates registers for the
heavier one (8 unrolled `textureLod` plus the nearest sample). Round 3 attributed 0.64 ms of the 1.59 ms static frame to
`solid_terrain` and 0.21 ms to `cutout_terrain`.

**What it does.**
1. *Specialization (exact).* Every pipeline whose fragment shader is `minecraft:core/terrain` (14: solid, cutout,
   translucent, OIT accumulate / depth bounds / transmittance, wireframe, each also `_multidraw`) compiles two PSOs: the
   one branch `if (_N.UseRgss == 1)` becomes `if (false)` (nearest) or `if (true)` (RGSS), and Metal drops the dead path.
   The live path is the same MSL text. The rewrite requires exactly one such branch whose `_N` is the entry point's
   `constant Globals&` parameter; otherwise the shader is used unchanged. `MetalRenderPipeline.handle()` (read by
   `MetalRenderPass.setPipeline` into the SET_PIPELINE record) returns the variant matching
   `TerrainShaderVariants.frameUsesRgss()`. `GameRenderer.render` sets that flag next to `GlobalSettingsUniform.update`,
   with the same value. That update is the only writer of the Globals UBO, once per frame, before any pass. So the
   chosen PSO computes what the uniform branch would compute in that frame. ICB and BGRA8 variants are per native
   pipeline, and no PSO is cached across frames.
2. *RGSS early-out (exact, `-Dmcmetal.terrainRgssEarlyOut`, default on).*
   - In `sampleRGSS`, `blendFactor = smoothstep(px, 2px, maxTexelSize)` is exactly 0 wherever a texel covers at least a
     pixel. There `mix(nearest, rgss, 0) == nearest` for any finite `rgss`, so the function returns the nearest sample
     right away (same arguments; derivatives are taken before, in uniform control flow).
   - `mipBlend = fract(mipLevelExact)` is exactly 0 where the level clamps to 0, so the 4 next-level samples are
     skipped there.
3. *Experiments, off.*
   - `-Dmcmetal.terrainRgssNoUnroll`: rolled RGSS loop.
   - `-Dmcmetal.terrainRgssLevelSampler`: an in-shader mip-nearest sampler for the 8 integral-LOD samples. It is exact
     for the vanilla RGSS-mode sampler (linear/linear, anisotropy 1, LOD 0..max), byte-equal in the headless test.
   - Neither beat the early-out by more than run-to-run noise in game (table below), so neither is on.

**Resource packs.** The specialization and rewrites apply only while `GameRenderer.metalTerrainShadersVanilla` holds.
That check requires `core/terrain.fsh`, `core/terrain.vsh` and every include they pull in (13 includes, followed
transitively) to be served by the built-in `vanilla` pack. It runs on the compile worker inside the reload that compiles the pipeline, so it
sees that reload's packs. A pack that overrides any of these files, even with identical text, gets the generic path:
its MSL unchanged and one PSO, logged once as "overridden by a resource pack". Anything unexpected (a missing file, a
quoted or unparsable include, a read error) also counts as overridden. Packs that override other shaders do not affect
terrain. Headless tests without a game default to "vanilla" (the jar's sources).

**Visual bar.** Exact by construction (constant folding of a uniform plus two `mix(a, b, 0)` eliminations), so no
perceptual-only difference is introduced.
- `tools/terrain-shader-test.sh` (Metal API validation on) renders a 1600x1000 perspective terrain scene. The scene has
  magnified, transition and grazing minified texels, a random 6-mip atlas with cutout alpha, random vertex colours and
  lightmap, fog and chunk fade.
  - It renders SOLID, CUTOUT and TRANSLUCENT with both `UseRgss` values in 8 configurations. All are byte-equal to the
    unchanged pipelines in colour and depth, and RGSS changes 12-14 % of the pixels, so both paths are reached.
  - It also checks the resource-pack gate on in-memory packs, the generic path under an overriding pack, and that all
    14 terrain pipelines compile to 2 specialized PSOs with the early-out applied.
- In game: the 18-scene runner, with candidate (defaults) and base (both switches off) on the same jar.
  - Option sets: vanilla defaults (NONE), ANISOTROPIC, RGSS, and RGSS with improved transparency (OIT terrain
    pipelines) and `cutoutLeaves:false`.
  - Every candidate set passes `ImgDiff` against the base. NONE also passes against the vanilla Vulkan reference
    (`run/shots-vkns`). RGSS shows the same single `day_north` CHECK against that NONE reference as the generic path
    (0.118 % vs 0.127 % > 48), which is the filtering mode, not this change.
  - Byte equality between launches is impossible: the scene runner is not deterministic (sky/time drift, snow and ice
    random ticks, mobs, particles, water). Two generic-path runs differ in 79 % of `day` pixels (max delta 105). Every
    base-vs-candidate difference is at or below that floor:
    - `day` max delta 2 (sky);
    - small isolated regions (snow layers, mobs) in `day_north`, `sunrise`, `sunset`, `night_horizon`;
    - `nether`, `night_moon` and `clouds` byte-identical.
  - The logs show "Terrain shaders: nearest/RGSS variants in use" for candidates and nothing for bases.

**Measured** (in game, M5, fullscreen 3600x2338, same jar `s-5.jar` with the switches on (defaults) or off; interleaved,
other agents' games queued in between; every JSON's `renderDistance` checked):

| Scene | Off (generic) | On (defaults) | Gain |
|---|---|---|---|
| Static scorecard (vanilla defaults, RD 12, filtering NONE), 3 + 3 runs | median 584 / 575 / 620, 1 % low 358 / 337 / 364 | median **807 / 819 / 803**, 1 % low 380 / 391 / 407 | +38 % median |
| Travel (Fancy: RD 16, RGSS, fresh world), 2 + 2 runs | median 274 / 283, 1 % low 164 / 167 | median **500 / 510**, 1 % low 275 / 275 | +80 % median, +66 % 1 % low |
| Static scene, RD 16 + RGSS (variant selection), 2 runs each | 331 / 352 | spec only 469 / 484; + early-out **495 / 519 / 517 / 524** | early-out +5 % over spec only |
| Same, experiments on top of spec + early-out | | rolled loop 499 / 492; level sampler 525 / 522 / 523 / 506 | within noise of early-out: off |

Cost: 12 more unique fragment functions (119 -> 131 in `i5-pipeline-test`), compiled during the resource reload;
seconds to world ready 4.48-4.72 off vs 4.54-4.80 on (no measurable change). Vulkan (360 median) and OpenGL (228) on
the same jar match their round-3 numbers (351 / 226). Labels:
`results/raw/s5s-*`, `s5t-*`, `s4r-*`, `s5-vk-*`, `s5-gl-*`; scenes `s5-{def,aniso,rgss,oit}-{base,cand}`.

| Toggle | Effect |
|---|---|
| `-Dmcmetal.terrainSpecialize=false` | No specialization: one PSO per terrain pipeline with the runtime UseRgss branch (the previous path). |
| `-Dmcmetal.terrainRgssEarlyOut=false` | RGSS variant without the early-outs. With both switches false, terrain MSL is exactly the translator's output. |
| `-Dmcmetal.terrainRgssNoUnroll=true`, `-Dmcmetal.terrainRgssLevelSampler=true` | Experiments (exact; see above). |
| `-Dmcmetal.diag.terrainShader=flat` | Diagnostics only (changes the image): solid terrain fragments skip sampling. |

### 16.9 Compact terrain vertices (stage-2 S5, `-Dmc.terrain.compactVertices`, default off)

Game side only (`client/src/net/minecraft/client/renderer/chunk/TerrainCompact.java`, `SectionCompiler`,
`SectionRenderDispatcher`, `CompiledSectionMesh`/`SectionMesh.SectionDraw.compact`, `TerrainDrawList`, `LevelRenderer`,
`ChunkSectionsToRender`, `RenderPipelines.*_COMPACT*`, `client/resources/.../terrain.vsh` `TERRAIN_COMPACT`). No native,
record or ABI change; the Metal backend sees ordinary pipelines with `UShort4 / UChar4Normalized / UShort2` attributes
at stride 16. Full numbers: `results/S5-COMPACT.md`.

**What it does.** A SOLID or CUTOUT section-layer is transcoded in place after the S1 bucket reorder into 16-byte
vertices: position `(p + 8) * 2048` in `RGBA16_UINT` with `block | sky << 8` light in `w`, colour unchanged, UV
`* 32768` in `RG16_UINT`. The shader decode is exact in binary32. SOLID/CUTOUT vertex heaps are aligned to 112 bytes
(lcm(28, 16)) so 28- and 16-byte meshes share them; draw groups are keyed by heap and format (`ChunkDrawGroup.compact`,
`TerrainDrawList.Group.compact`), and a layer's compact groups draw with the `*_COMPACT` pipeline variant (pipeline
switch where the format changes). TRANSLUCENT always stays 28 bytes (its sort reads float positions).

| Toggle | Effect |
|---|---|
| `-Dmc.terrain.compactVertices=true` | Exact mode: only layers whose every vertex round-trips bit-exactly; others keep 28 B. Bit-identical image. Startup only (heap alignment). |
| `+ -Dmc.terrain.compactSnap=true` (`compactSnapPosition` 2^-18, `compactSnapUv` 2^-22) | Also layers within the tolerance of the grid. Accepted 0 extra layers in game (26.3 has no float rotation noise): equivalent to exact. |
| `+ -Dmc.terrain.compactLossy=true` | Every SOLID/CUTOUT layer compact, values rounded: position error <= 2.41e-4 block (2^-12), UV <= 1.52e-5 (2^-16), measured. Alters the image by design (see below). |
| `-Dmc.terrain.meshStats=N`, `-Dmc.terrain.meshStatsFile=<path>` | Every N s: resident vertex/index bytes per layer and format, meshing outcomes (exact / snapped / fallback reason), bytes staged for upload, max snap error. |
| `-Dmc.terrain.compactDebug=true` | Diagnostics: the most frequent inexact position floats. |

**Why exact mode covers so little.** 26-29% of SOLID+CUTOUT layers and only 10-12% of their bytes in the snowy bench
world, 56% of layers / 26% of bytes in the travel world. The big surface layers fail on `snow_height2`'s inner element
(0.002/16 = 1/8000 block, not dyadic), plant random offsets, rotated elements and fluid heights. The plan's
`compactLayerShare >= 70%` gate is not met.

**Measured** (interleaved in-game A/B, frozen jar, static RD 12 checked; travel = Fancy RD 16 fresh world):
- Exact: no measurable FPS change (static 617/604/368 vs off 608/596/359, n = 3 / 7; travel 284/273/164 vs
  281/268/160, n = 4 / 6; median / avg / 1% low means). Resident vertex bytes -7% (static) to -17% (travel), upload
  -7% to -16%.
- Lossy: static within noise (620/606/373, n = 4); travel 297/278/159 (n = 4): +5.7% median, +3.7% avg, 1% lows flat
  (won all 4 interleaved pairs, but off spans 254-304 across sessions). Resident vertex bytes -40% (129 -> 78 MB
  static, 254 -> 148 MB travel), chunk upload -43% (43 -> 24.5 MB/s while flying).
- Serialized per-pass profile (MCMTL_PROFILE_PASSES=2): Main pass within the per-window spread (1.1-1.9 ms over the spin)
  for off/exact/lossy; an earlier single lossy window set was disturbed by another GPU user (all passes +60%).

**Visual bar.** Exact: bit-identical by construction and by `tools/terrain-e2e-test.sh` (144 renders, colour and depth).
Lossy: passes the perceptual metric (`tools/PerceptualDiffUpsample.java`, task V's variant) on every terrain-static scene; its pixel changes
(snow-layer edges, plant edges, <= 1 px) are of the order of the scene runner's run-to-run noise. Not yet done for
lossy: the temporal check (b) and showing the side-by-side images (c) to the user (`results/s5-lossy/*.png`).

**Decision.** Default OFF. Exact has no measurable benefit. Lossy is the only variant with a measurable gain (travel)
but alters the image by design, so enabling it waits for GOAL (b)/(c).

**Interactions.** Occlusion culling (16.6) classifies terrain pipelines by a float3 Position: compact draws are not
recognised as terrain, so they are neither culled nor counted as occluders (safe: fewer culls). Terrain ICB (16.5) is
pipeline-agnostic. Vulkan and OpenGL render the compact pipelines too (18-scene runs with exact: no difference beyond
the backends' own baseline).

### 16.10 Front-to-back CUTOUT terrain (Terrain S6, `mc.terrain.cutoutFrontToBack` / `sortCutoutSections`, default on)

Java only (`TerrainMeshInfo`, `SectionCompiler`, `TerrainDrawList`, `LevelRenderer`, `TerrainToggles`). No native,
record, ABI or shader change; every backend gets the same draw order. Plan: `docs/stage2/TERRAIN-PLAN.md` §4.6.

**What it does.**
- **Mesh time** (`cutoutFrontToBack`, needs `faceBuckets`): inside each CUTOUT facing bucket the quads are stably sorted by
  their plane, descending for +axis buckets (EAST, UP, SOUTH) and ascending for −axis buckets. An eye sees a +axis quad
  only from the + side of its plane, so the larger plane is the nearer one at every pixel both quads cover. The order is
  therefore front-to-back for every camera, with no per-frame work. UNASSIGNED quads keep emission order.
  - The sort key is the float plane mapped to an order-preserving int (−0.0 equals 0.0), with the quad's position in
    the low half of the key, so the sort is stable.
  - Bucket metadata (counts, ranges, planes, bounds) is identical to the plain S1 bucket sort.
- **Per frame** (`sortCutoutSections`, multi-draw-indirect path, both list builders):
  - The CUTOUT records of each draw group are stably sorted by the squared distance from the camera to their section's
    centre (float bits as an int key).
  - A section's records stay together and keep their order.
  - Vanilla's order is the occlusion-graph walk, which is only roughly near-to-far.
- **SOLID** (`solidFrontToBack`, `sortSolidSections`): the same steps for SOLID terrain. These are diagnostics only and
  default off, because opaque overdraw is already removed by the GPU's hidden-surface removal.

**Why the image is identical.**
- CUTOUT does not blend; its fragments are `discard`ed or written.
- Quads of one bucket at different planes are parallel, so they never share a depth at a pixel.
- Coplanar quads (overlays) keep their emission order.
- The section sort moves only whole sections. TRANSLUCENT is never reordered.
- What changes is which fragments the depth test rejects before shading. Drawing nearer alpha-tested geometry first lets
  the depth test reject more of the `discard` fragments behind it; a tile GPU's HSR cannot drop them before shading.

**Tests.**
- `tools/terrain-test.sh` (S6 + section order):
  - permutation properties: whole quads moved, bucket ranges kept, metadata equal to the S1 sort, plane order;
  - stability, including −0.0 / 0.0 and duplicate quads;
  - nearest-first for random eyes;
  - a monotonic plane key and distance key, and a stable list sort.
- `tools/terrain-e2e-test.sh` "cutout canopy" (real Metal, vanilla `CUTOUT_TERRAIN_MULTIDRAW`, a leaf atlas with ~45%
  transparent texels): five poses, nearest and RGSS. S6 buckets, the section sort and both together each give
  0 differing pixels against vanilla order.
- `tools/terrain-drawlist-bench.sh check 300 visible=octree|far`, with the defaults and with `sortSolidSections` too:
  the fast and vanilla-shaped lists are byte-identical every frame (`CHECK PASSED`).

**In-game parity** (18 scenes, frozen jar):
- Default-off build, CUTOUT pair on, all four on, and a second default run: all pass `ImgDiff` against vanilla Vulkan.
- The scene harness is not deterministic run to run (animated water, particles, mobs, rain). Two default runs differ
  in up to 1.46M px of a scene.
- So the check counts the pixels where both default runs agree but the candidate (CUTOUT pair) differs:
  - 0 px in clouds, nether and night_moon;
  - ≤ 1148 px in day, rain, sunrise, sunset, thunder, underwater and the GUI scenes;
  - larger counts only on animated content: night_horizon 4847 px (the lava pool), end 19k (End flash lighting,
    dragon, crystals), entities 15k, particles 31k, day_north 94k (the water plane and the hotbar over it), and
    water_surface 707k.
- The night_horizon, end and day_north masks were inspected: none of their differing pixels is on leaves.
- With all four toggles, day_north drops to 4 px. The 94k of the CUTOUT-pair run is run-to-run water animation.
- **Final default-on build** (`c6-final2.jar`), three back-to-back runs with the video options pinned to vanilla
  defaults: kill switch, default, kill switch.
  - Both settings give the same `ImgDiff` figures against vanilla Vulkan.
  - Every scene passes except day_north, which reads 0.119 / 0.122 / 0.123% of pixels > 48 (bar 0.1%) whether the pair
    is off or on.
  - Cause of the day_north failure: snow layers that accumulated in the shared `run/scenes` world. Every scene sweep's
    rain and thunder scenes snow on this snowy taiga.
    - A crop of the strongest blob shows a snow layer on spruce leaves that the reference image does not have.
    - The figure grows over time: r3b was at 0.011% at 21:45, these runs at 0.06–0.09% at 00:37 and 0.12% at 02:50.
    - The reference (`shots-vkns`) is stale for day_north. This is not an S6 effect.
  - The default run's pixels outside the kill-switch noise are again only on animated content and on radial rings in
    day, the first scene. The two kill-switch runs also differ from each other on 35% of day.

**Measured** in game, on `c6-final.jar` with `-D` flags. All static and fixed-view runs record renderDistance 12. Travel
runs at 16 by design (Fancy preset). Values are FPS: median / avg / 1% low.

Static scorecard (`tools/bench-one.sh`, shared `run/metal`, 6 + 6 runs in two interleaved sets: B C A ×3, then ABBA):

| | median per run | mean median | mean avg | mean 1% low |
|---|---|---|---|---|
| base | 657 598 619 603 604 612 | 615.5 | 601.9 | 358.8 |
| CUTOUT pair | 631 594 616 592 621 594 | 608.0 | 595.7 | 361.4 |
| all four | 616 620 | 617.7 | 605.2 | 358.4 |

The static scorecard cannot resolve a difference of this size:
- Run-to-run drift is ±5%; base r1 ran 10% above every other base run, across its whole 360° curve.
- Without base r1, the base mean is 607.2 against 608.0 for the CUTOUT pair.

Fixed views (worktree bench jar: `bench.yaw`, `bench.spin=1e6`; private game dir `run/metal-c6f`; static options):
- CUTOUT quads per frame by yaw: 0° 30k, 60° 61k, 120° 94k, 180° 102k, 240° 62k, 300° 21k.
- Within a run the FPS is steady: per-second values sit within +2.2% / −3% of the run median, with one second at −6%.
  Between runs it drifts less than in the spinning scorecard, apart from the two yaw 0 base runs.

| View | base | CUTOUT pair | Δ median |
|---|---|---|---|
| yaw 180 (forest, 4 + 4 runs, 2 sets) | 480.6 481.0 477.7 476.7 | 486.2 484.1 489.0 488.3 | **+1.6%** (every on-run above every off-run) |
| yaw 180, set 2 per toggle (2 runs each) | 477.7 476.7 | f2b only 491.8 487.3; sort only 491.5 483.4; both 489.0 488.3 | +2.6% / +2.1% / +2.4% (either alone ≈ both) |
| yaw 120 (forest edge, 2 + 2) | 465.6 462.8 | 466.5 470.9 | +1.0% |
| yaw 0 (little CUTOUT, 2 + 2) | 769.3 700.3 | 753.2 753.2 | drift ±5%, no regression signal |
| yaw 180, final build: kill switch vs default (ABBA) | 479.5 478.6 | 484.7 483.2 | +1.0% (the JSON confirms the toggle values) |

- **Travel** (`tools/bench-travel.sh` with the private game dir `run/c6f`, Fancy, RD 16, fresh world, 2 + 2 runs):
  - base 304 / 278 / 153 and 285 / 269 / 156;
  - CUTOUT pair 306 / 284 / 158 and 268 / 261 / 158.
  - The median is noise. The 1% low is +2.3% and the 0.1% low +8%, so the mesh-time sort adds no chunk-build cost
    that the benchmark can see.
- **Serialized pass profile** (`MCMTL_PROFILE_PASSES=2 MCMTL_M2=0`, yaw 180, 2 + 2 runs): the Main pass is 1.74 / 1.83 ms
  base and 1.81 / 1.95 ms with the pair, but windows within one run spread from 1.68 to 2.5 ms. The profiler cannot resolve
  a 0.03 ms change, and its FPS is not comparable.

**Decision.** Both CUTOUT toggles are on by default:
- Forest views gain 1.0–1.6% (yaw 180: no overlap between the 4 + 4 runs).
- The static scorecard and travel show no measurable change, and the 1% lows do not drop.
- The output is pixel-identical by construction and in every test.
- Either toggle alone gives about the same gain, and both are cheap: a stable sort per CUTOUT layer at mesh time, and a
  sort of the CUTOUT records per frame. Both stay on so that each covers the other's case:
  - meshes compiled before the toggle flips, for the per-frame sort;
  - views where the section order already happens to be near-to-far, for the mesh-time sort.
- The SOLID pair stays off: SOLID is opaque, and HSR already removes its overdraw.

**Runs not used for the decision.** The previous instance's `c6-static-*` (22:10–22:40) and `c6-prof-*` runs all record
renderDistance 12, so none was discarded for the RD-16 leak. They are still excluded:
- Their jar (`c6-1.jar`, frozen at 22:10) is not traceable to the committed sources: `LevelRenderer` and
  `TerrainDrawList` were edited a few seconds later.
- They predate the options-under-lock fix in `launch.py`.
- Their bench JSON could not record the S6 toggles.
- `c6-prof-*` FPS was measured under the serialized profiler.

They agree anyway (median FPS by their labels): base 608 / 594, s6 578 / 610, s6sort 594 / 607, all 607 / 615.

| Toggle (system property) | Default | Effect |
|---|---|---|
| `mc.terrain.cutoutFrontToBack` | **true** | Mesh time: CUTOUT facing buckets front-to-back by plane (needs `faceBuckets`; applies to meshes compiled after the switch) |
| `mc.terrain.sortCutoutSections` | **true** | Per frame (MDI path): CUTOUT records front-to-back by section distance; runtime-switchable |
| `mc.terrain.solidFrontToBack` | false | Diagnostic: the same for SOLID buckets |
| `mc.terrain.sortSolidSections` | false | Diagnostic: the same for SOLID records |
| `mc.terrain=false` | — | Master switch: every terrain step off (vanilla) |

### 16.11 Round-4 integration (F, S, V, C merged; `build/client-r4.jar`)

Merges on `main`: `76b027a` (F, §16.7), `2802513` (S, §16.8), `a12ef56` (V, §16.9), `38f2089` (C, §16.10). Full numbers:
`results/ROUND4.md`.

**Merge decisions.**
- Section numbers: each branch had added its own "§16.7". They were renumbered in merge order: fusion 16.7, shader
  specialization 16.8, compact vertices 16.9, S6 16.10. The references in `TerrainCompact`, `TerrainToggles`,
  TERRAIN-PLAN.md and S5-COMPACT.md were updated.
- `tools/PerceptualDiff.java`: F's version is canonical (area resample to at most 3024x1964, 256 px region bar,
  `--floor` pairs, `--selftest`). V's variant (bilinear upsample, 1000 px region bar) is `tools/PerceptualDiffUpsample.java`.
- S5 + S6 order in `SectionCompiler`: S1 bucket reorder, then S6 front-to-back within buckets, then the S5 transcode.
  The transcode keeps quad order, so compact layers keep the S6 order. The S6 per-frame record sort runs per
  `TerrainDrawList.Group` / `ChunkDrawGroup`, and compact groups are separate groups, so it covers them too.
- S + S5: the compact pipelines use fragment `core/terrain`, so they are specialized as well. `terrain-shader-test`
  now counts 20 terrain pipelines, all specialized. The jar's `terrain.vsh` override is in the vanilla pack, so the
  resource-pack gate still passes.
- Merge fix: `tools/CoreMslTest.java` now lets `build/client.jar`'s shader files shadow the vanilla jar's, as the game
  does. Before this, the six `*_COMPACT` pipelines failed with "UV2(3) missing from the vertex descriptor".

**Shipped defaults.** `MCMTL_PRESENT_FUSION=1`, `mcmetal.terrainSpecialize=true`, `mcmetal.terrainRgssEarlyOut=true`,
`mc.terrain.cutoutFrontToBack=true`, `mc.terrain.sortCutoutSections=true`. `mc.terrain.compactVertices` is false.
The round-3 settings are unchanged: mailbox on, terrain ICB off, occlusion off. Every default-on change is exact, so
there are **no perceptual-only differences** to document.

**Verification.**
- Every headless suite passes (§11 table, plus `terrain-shader-test`, `--icb-parity`, the drawlist checks and the
  `PerceptualDiff` selftest).
- In-game 18-scene runs on a pinned world copy against a vanilla Vulkan reference taken from the same copy: strict
  18/18 ok and perceptual bar met (exit 0) for two r4 runs.
- LAN multiplayer: r4 to r4, vanilla host to r4 guest, and r4 host to vanilla guest all join and render.
- Scorecards, r4 against r3b (2 + 2, interleaved): static median 870 vs 623 (+40%, RD 12 checked), travel 527 vs 295 (+79%).

**Harness changes for later rounds.**
- Pinned scenes world: `run/scenes-pinned-r4`. Its vanilla Vulkan non-Spaces reference is `run/shots-vkns-r4`.
- Copy the world to a fresh game dir before every scenes run, and never run scenes in `run/scenes` itself (the rain
  and thunder scenes save snow). `run/shots-vkns` is stale for `day_north` (0.129% CHECK even for vanilla).
- Superseded by §11.6: `launch.py` itself restores the read-only `run/pinned/scenes` before every scenes / zoomseq run,
  and the reference is `run/pinned/shots-vkns-<screenshot size>`.
- `tools/lan-smoke.sh <out> <hostJar|vanilla> <api> <guestJar|vanilla> <api>` is the multiplayer check required for
  each release candidate (agent sources in `tools/lansmoke/`).

### 16.R5-A Late frame throttle (round 5, `-Dmcmetal.lateThrottle`, default **on** since 16.R5-FIN; `=false` is the off switch)

Plan: `results/TAIL-ANALYSIS.md` S1 / V1 ("Fix design"), implemented as designed. Java only: `MetalFrameThrottle` (new),
`MetalCommandEncoder` (late `submit`, guards, strict check), `MetalDevice` (owns the throttle, registers the game's device),
`MetalBuffer.map`, `MetalTransientMemory` (guards), `Minecraft` (`runTickBundleLateThrottle`, `renderFrame` entry hook),
bench `ThrottleStatsHook` (+2 lines in `Bench`). No native, header, record, opcode or ABI change. Vulkan and OpenGL
untouched: `Minecraft` takes the new order only while `MetalFrameThrottle.active()` (toggle on and a real Metal device).

**Why.** The 1% lows are client-tick frames: the 20 Hz bundle (packets, tasks, texture tick, `tick()`) ran after
`submit()`'s frames-in-flight wait and before the next frame, so it was never overlapped with GPU work (tick frame G+T,
the next G−T).

**Mechanism.**
- `submit()` (toggle on): commit as before, `previousFrameSeq` update, `frameThrottle.arm(N-1)` (nothing for the first
  frame), `transientMemory.endFrame(seq)`, non-blocking `checkErrors()` + `drain()`, rethrow a deferred submit error.
- Wait = the tail of the old `submit()`: `timeline.await(N-1, frameWaitTimeoutNs)` (false → the same
  `IllegalStateException("5s timeout reached when waiting for the GPU")`), `checkErrors()`, `drain()`. Taken by the first of:
  - `MetalFrameThrottle.frameThrottle()` (the throttle point). `runTick` (toggle on): packets → tasks → [texture condition
    `ticksToDo > 0 && isLevelRunningNormally()` evaluated here, where vanilla evaluates it] → `tick()` ×n → gizmo drain →
    **throttle** → `textureManager.tick()` → sound → mouse → `renderFrame`. Without `advanceGameTime` (OOM recovery): throttle
    → sound → mouse. `renderFrame` also calls it on entry (no-op after `runTick`; the waits of the startup / world-load loops,
    which call `renderFrame` without `runTick`). With nothing armed it only drains.
  - `ensureThrottled()` (the lazy guard, a "trip"), first statement of: `createRenderPass`; `clearColorTexture`;
    `clearColorAndDepthTextures` (both); `clearDepthTexture`; `writeToBuffer` (both paths); `copyToBuffer`; `writeToTexture`;
    `copyBufferToTexture`; `copyTextureToBuffer` (both); `copyTextureToTexture`; encoder `writeTimestamp`; `recordTextureInit`
    (INIT_TEXTURE of `MetalTexture.create`); `recordPresent` (`MetalSurface.blitFromTexture`); `submit()` and
    `flushForWait()` (before they read the value they commit; r5-int fix); `commit()` (every `mcmtl_submit`, as a backstop:
    wait + `checkErrors()` only, **no drain**, so no deferred callback can run inside a commit after its value was fixed);
    `MetalBuffer.map` (read or write); `MetalTransientMemory.allocateGpuBlock` (`allocateStaging/Gpu/GpuMapped`,
    `uploadStaging/Gpu`) and `multiUpload` (`multiUploadStaging/Gpu`).
- **Completeness.** (1) Every stream byte is appended by `record()` / `recordEmpty()` (main) or `recordTextureInit` (init).
  Their callers are the guarded methods above or `MetalRenderPass` / `submitRenderPass`, which need an open pass; a pass is
  opened only by `createRenderPass` (guarded) and `submit()` refuses an open pass, so nothing is armed while a pass is open.
  `cancelPresent` only rewrites a PRESENT already recorded this frame. `-Dmcmetal.lateThrottle.strict=true` turns this into a
  runtime check (a record while armed throws); every GPU suite below ran with it. (2) `mcmtl_submit` is only called by
  `commit()`. (3) The game reaches GPU-visible memory only through `map()`, `writeToBuffer`'s CPU fast path and transient
  allocations (all guarded). `createBuffer(data)` writes a fresh or pooled backing that no recorded or in-flight work
  references (pool entries enter after the GPU passed their close tag), so it is not guarded; persistently mapped staging
  (`StagingBuffer.PersistentlyMapped`) was mapped through a guarded `map()`, is fenced by vanilla's `MappableRingBuffer`, is
  only appended past what recorded copies read, and is written by compile workers with no frame synchronization today.
  (4) Not guarded (no command, no commit, no visible write): `createFence` (captures `pendingSeq`, same value), fence
  polls/waits, resource creation (buffers, render-target textures, views, samplers, query pools, pipelines), `close` /
  deferred releases, query reads, `allocateCpu`, surface configure / acquire / `present()` (drops the drawable reference).
- **Invariant (as before).** Nothing for frame N+1 is recorded, committed or written into GPU-visible memory before frame
  N-1 completed; at most two frames' commits are in flight. Every timeline value, fence tag, `markUsed` tag and deferred tag
  is the same as before (commits happen at the same points); deferred entries still run in FIFO order and only after their
  tag completed. So the committed command streams are byte-identical (tested), and the image is unchanged.
- **Threading.** Render thread only: `arm` records the thread; a guard call from another thread never waits and never touches
  the timeline (compile workers were never synchronized with the frame wait). `pendingSeq` is written only by the render
  thread. Waits clear `pendingSeq` before draining, so callbacks that call guarded methods do not recurse (the timeline drain
  is not re-entrant). Allocation: none per frame (primitive counters; the stat line every 30 s and logged trip sites allocate).
- **What moves in front of the wait** (in the game): `renderFrame`'s tail after submit (Tracy, `windowSurface.present()`,
  `DynamicGpuData.reset()` / `CloudRenderer` ring rotations = `createFence`, frame limiter, FPS counters), `runTick`'s head,
  packets, tasks and `tick()`. They touch the backend only through guarded calls (counted). The texture tick moves from before
  to after `tick()`: `TextureAtlas.tick` only cycles sprite animations (own counters, writes into the atlas); the client tick
  neither reads nor writes them, and both still run between the same two frames.
- **Not changed, by decision: `advanceGameTime` after the wait.** A second call per frame splits the frame's `deltaTicks`
  (readers in `renderFrame` would see only the second slice), and a 50 ms boundary crossed during the wait yields a tick that
  must either run behind the throttle (exposing tick work again on exactly those frames) or be deferred (partialTick wraps to
  ~0 before the tick ran: a one-tick backward jump on screen, and client tick timing shifts, which servers observe through
  per-tick movement packets); it would also break the bench's zoomseq lockstep hook on `advanceGameTime`. Without it,
  partialTick is sampled a constant ≈ G−C (0.5–0.8 ms at 500+ FPS) earlier relative to display: no judder.
- **Mouse input (r5-int fix).** `RenderSystem.pollEvents` stays at the top of the run loop, which is now before the wait, so
  mouse motion arriving during the wait would be applied one frame later than in vanilla (where the poll follows `submit()`'s
  wait). When the wait (at the throttle point or at a guard trip since the last submit) actually blocked, `lateFrameThrottle`
  pumps events again right after it, before `handleAccumulatedMovement`: mouse look is never older than in vanilla.
  `SDLEventHandler` runs handlers immediately on the render thread outside tasks, so the deltas land in this frame. Keys and
  clicks keep their place relative to the client ticks (the next tick consumes them, as after vanilla's next poll); packets
  sent by screen click handlers keep their order relative to the tick's packets.
- **Server-observable:** nothing. Same packets, order and tick count (`DeltaTracker` untouched); the client tick runs up to
  one frame wait earlier in wall time.
- **Remaining differences** (not image, not server): deferred entries of frame N-1 (e.g. readback callbacks such as
  screenshots) may run at the throttle point instead of before the tick; a `createBuffer` during the tick may miss a pooled
  backing that the old early drain would have returned (fresh `MTLBuffer`); a fence poll during the tick may see frame N-1
  running (vanilla polls only inside `renderFrame`); a GPU hang or device loss surfaces at the throttle point or the first
  guarded call (a loss found by a guard is thrown before that frame is committed).
- **GPU failures stay fatal (r5-int fix).** A guard can trip inside a task or a packet handler, which catch `Exception`
  (`BlockableEventLoop.doRunTask` logs it; `PacketProcessor` turns it into a packet-error disconnect). A wait that times out,
  a `checkErrors()` failure (command-buffer error, OOM, device loss) or a throwing drain callback is therefore recorded in the
  throttle and the wait stays armed: every later guard, `submit()` and the throttle point rethrow the same exception and
  nothing more is committed. The throttle point is wrapped in the same `CrashReport.forThrowable(e, "Render Frame")` +
  "Render Frame Details" report that `runTick` builds for exceptions from `renderFrame`, so the crash looks as before.

**Diagnostics.** Log line when the game's device registers. Every 30 s: frames, frames with guard trips, trips, max per frame,
blocking waits and their mean. The first `logTrips` trip sites are logged with a stack trace, after start and again after
each `resetStats()` (the bench's MEASURE start), so gameplay trips are not hidden by loading-screen ones. Bench JSON `"lateThrottle"`:
`enabled`, `active`, `frames`, `trips`, `tripFrames`, `maxTripsPerFrame`, `blockedWaits`, `meanBlockedWaitMs` (MEASURE phase;
null only on the vanilla jar; on the patched jar always an object, `active: false` on Vulkan/OpenGL or with the toggle off).
F3 profiler: `tick/frameThrottle` (and `tick/pollEvents` when the wait blocked).

**Tests** (headless, all pass):
- `tools/late-throttle-test.sh` (new, `tools/LateThrottleTest.java`): runs off, then on + strict. Both run 9 scripted frames
  over every encoder entry point (passes, clears, buffer/texture writes and copies, timestamps, INIT_TEXTURE, PRESENT,
  transient uploads, mappings, readback callbacks that record, a flush-on-wait), the "tick" work after the wait (off) or before
  the throttle point (on, 4 trips). The fake GPU (r5-int fix) completes a value only when the CPU blocks on it, from its own
  thread after a delay, never as a side effect of a commit; so completion state, fast paths, recycling and drains follow the
  waits the code really takes. The digest of all committed streams (synthetic headless handles numbered by first
  appearance, because the late order may create a transient block in a deferred callback after, not before, a texture
  created by the tick) + CPU-written memory + callback order is identical (`8b012e8124094a53`), and every frame commit has
  frame N-2 complete. Sabotage canary (on): the same script with the armed wait cleared after every submit (the reviewer's
  mutation) must give frame commits without N-2 complete and a different stream and decision digest (2 violations). New
  sections: commit() never runs callbacks (a deferred callback that commits, through flushForWait and through an empty
  frame's submit: without the fix "Metal timeline diverged"); GPU failures stay fatal (timeout swallowed by a task, a
  command-buffer error, device loss, a throwing drain callback: rethrown as the same object by the next guard, the throttle
  point and submit, nothing more committed; without the fix 7 failures); the throttle point reports a blocked wait. On: `submit()` does not wait; a write before the
  throttle point blocks until N-1 completes, then takes the same CPU fast path and the FIFO is drained; the throttle point waits
  once; 27 guarded calls take an armed wait, 12 unguarded ones leave it armed; other threads never wait; timeout and
  device loss; hooks and counters. Off: `submit()` waits itself, timeout from `submit()`, hooks inert. A removed guard
  (`copyTextureToTexture`) fails the strict run.
- Toggle off (default): native tests (`MTL_DEBUG_LAYER=1`: abi, core 845, decoder 5674, occlusion 9114), `metal-device`
  (316), `metal-e2e` (1524), `terrain` (157195), `terrain-e2e` (1581), `i5-pipeline` (16561), `core-msl` (198/198),
  `terrain-shader` (280), `occlusion-e2e` (180 identical frames), `test-i4-encoder` (939), `bench-hook-test` (90).
- Toggle on + strict: `metal-device`, `metal-e2e`, `terrain-e2e`, `terrain-shader`, `i5-pipeline`, `occlusion-e2e` (frame
  hashes identical to the off run), `test-i4-encoder` (its `submitThrottleAndErrors` asserts the old `submit()` and skips itself
  when the toggle is on).

**In game (round-5 A/B, `results/ROUND5-AB.md`): decided default on** (the code default is still off; see 16.R5-AB).
- Static: 1% low +6.0% (7 of 8 pairs), 0.1% low +8.0%, median −0.4%.
- Travel: 1% low +2.8%, 0.1% low +7.3%, median +0.7%.
- 0 guard trips in every measured run, and input sample age unchanged.
- The predicted 519–579 static 1% low was refuted: after the throttle the tail is GPU cadence in the heaviest view
  direction (p99 2.35–2.5 ms), not ticks.

| Toggle (system property) | Default | Effect |
|---|---|---|
| `mcmetal.lateThrottle` | **true** (16.R5-FIN; was false) | The late frame throttle (Metal only). `=false` (or `0`): the old `submit()` and vanilla `runTick` |
| `mcmetal.lateThrottle.strict` | false | Tests: a record while the frame wait is armed throws (missing guard) |
| `mcmetal.lateThrottle.logTrips` | 1 | Guard-trip sites logged with a stack trace |
| `mcmetal.lateThrottle.statSeconds` | 30 | Stat line interval (0 = none) |

### 16.R5-C Cross-frame UBO-copy serialization and transient-block churn (round 5 task C, TAIL-ANALYSIS S3 / S5; renaming default **on** since 16.R5-FIN, hysteresis off)

Java (`MetalBuffer`, `MetalCommandEncoder`, `MetalTransientMemory`) plus a diagnostic counter in `mcmetal_decoder.m`. No
header, record, opcode or ABI change. Vulkan / OpenGL untouched. Nothing a server can observe changes.

**Problem.** A `writeToBuffer` into a buffer that recorded or in-flight work still reads (`lastUseSeq > completedSeq`)
records staging + `COPY_BUFFER`. Most such writes come before the frame's first pass, so the decoder encodes them in
the frame's first blit encoder (the MST's Blit0). Under tracked hazards, that write into a UBO that frame N-1's
fragment work still reads makes Blit0(N) wait for Fragment(N-1). Vertex(N) then waits for Blit0(N), so consecutive
frames never overlap (E9: 3059 of 3059 pairs).

| Toggle | Default | Effect |
|---|---|---|
| `-Dmcmetal.renameSmallWrites` | **true** (16.R5-FIN; was false; `=false` is the off switch) | **Fix (S3):** buffer renaming for in-use writeToBuffer targets of at most 64 KiB (below). Read on every call. |
| `-Dmcmetal.transientHysteresis` | false | **Fix (S5):** keep idle 4 MiB transient blocks up to the high-water mark of the last 120 rotations. Read at device creation. |
| `-Dmcmetal.diag.forceFastWrite` | false | **Diagnostic, UNSAFE:** writeToBuffer always memcpys into the current backing, so no COPY_BUFFER is recorded for it. Races with in-flight frames and with earlier passes of the same frame, so it may change the image. Only for the MST confirmation of S3. |
| env `MCMTL_COPY_STATS=1` (or `=N` frames) | unset | **Diagnostic:** the decoder logs (NSLog) every 600 (N) frames the share of frames with a copy before their first render encoder, then one line per copy destination: label, buffer length or texture size, copies per frame (lead = before the first render encoder, hoisted = M2 B6), bytes per frame, and the % of frames that wrote it. Also makes Java label every buffer natively, which bypasses `MetalBuffer.Pool`, and log writeToBuffer outcomes (fast / renamed / staged + refusal reason / forced, "read by an earlier frame") and transient-block churn every 600 frames. |

**Renaming (`MetalBuffer.renameForWrite`, called from `MetalCommandEncoder.writeToBuffer`).**
- Applies when renaming is on and the target is in use.
- The buffer moves to an idle backing: one of its own retired backings whose last use completed, or a new
  `mcmtl_buffer_create` with the same size, usage and options.
- The bytes outside `[offset, offset + length)` are copied on the CPU from the current backing. The new bytes are then
  memcpy'd as on the fast path.
- The old backing goes into the buffer's spare list as `{handle, contents, gpuAddress, freeAt = lastUseSeq}`. It is
  reused only once `freeAt <= completedSeq`. At most 16 spares; with none idle at the cap, the write is staged.
- `lastUseSeq` becomes 0, because nothing references the new backing yet. A second write before the next bind is a
  plain memcpy.
- `close()` releases the spares through the deferred FIFO. The tag is `pendingSeq`, which is at least every `freeAt`.
- Steady state: 2-3 backings per renamed UBO. No allocation per frame: the spare array is allocated at the first
  rename, and `MemoryStack` is used only when a spare is created.

**Why it cannot retarget recorded work (verified).** Every record copies the native handle into the off-heap stream
when it is recorded:
- `MetalRenderPass.java:158` (SET_BUFFER), `:278` (SET_VERTEX_BUFFER), `:302` (SET_INDEX_BUFFER), `:421`
  (DRAW_*INDIRECT);
- `MetalCommandEncoder.java:857/859` (COPY_BUFFER src/dst), `:877` (COPY_BUFFER_TO_TEXTURE / TEXTURE_TO_BUFFER);
- texel views capture the handle at creation (`MetalBuffer.java:509`); renaming refuses texel-usage buffers and
  buffers with a view.

The decoder resolves handles from the record (`MCMTL_BUFFER(h)`, `mcmetal_internal.h:79`, e.g.
`mcmetal_decoder.m:5460`) and never keeps stream pointers (§4.1, §4.2). Command buffers retain what they reference
(`retainedReferences = YES`). The only Java-side caches of buffer identity are the per-pass dedupe arrays of
`MetalRenderPass.State`. They hold the Java object, not the handle, and are reset at every pass begin/end. Renaming
happens only outside passes (`writeToBuffer` → `checkOutsidePass`).

**No hazard (argument).** Write W to buffer X at stream position t, from backing A to backing B:
1. Work recorded before t names A. A is never written again by the CPU (X no longer points to it) or by the GPU (see
   2), and it is not reused before its last reader's seq completed. So that work reads A's bytes as of t, which are
   the stream-order contents before W.
2. The CPU copy of A equals the stream-order contents only if no GPU write into X is pending, apart from the staged
   writeToBuffer copies that are replayed (2b). Every GPU write into a MetalBuffer is a COPY_BUFFER or
   COPY_TEXTURE_TO_BUFFER destination. Copies the game records (`copyToBuffer`, `copyTextureToBuffer`) mark
   `gpuWriteSeq`, unconditionally so a runtime toggle stays exact. With `gpuWriteSeq > completedSeq`, the write is
   staged as before.
   2b. (r5-int review fix) A writeToBuffer that was staged while renaming is on (refused: open mapping, no spare, a
   pending game copy) is not marked: it is kept in the buffer's replay list (`MetalBuffer.recordStagedWrite`, at most
   8 pending; {seq, offset, length, staging address}). Its COPY_BUFFER still runs into A. A later rename copies A's CPU
   bytes and then replays every listed write whose seq has not completed, in stream order, from its staging memory
   (transient blocks are recycled only after the frame that holds them completed, so the bytes are intact), before the
   caller's bytes. Completed entries are pruned (their bytes are in A). A full list, a buffer that can never be renamed
   (> 64 KiB, sub-allocated, wrapped), or renaming off marks `gpuWriteSeq` as before. Before the fix one staged write
   marked the buffer, every later in-flight write failed the gate and was staged again, and renaming silently switched
   itself off under GPU-bound play.
3. An open mapping still points into A, so a buffer with an open mapping is staged.
4. Work recorded after t names B, which holds old ⊕ new bytes.
5. B is idle: it is either fresh or its `freeAt` completed. CPU writes to Shared memory before the commit are visible
   to the GPU (as on the fast path).

CPU-side readers at decode time get the right per-record bytes. These are the ICB argument records, fan index
conversion and the occlusion planner. With staged writes they saw pre-copy bytes for records after the copy. The ICB
slot cache keys the index buffer by object identity, but its commands do not bake index contents, so reusing a backing
under the same identity is exact.

Refused, and staged exactly as before: size over 64 KiB, transient / wrapped / sub-allocated / closed / texel
buffers, an open mapping, a pending GPU write, or no spare.

**Hysteresis (`MetalTransientMemory`).**
- With the toggle on, the GPU allocator is built with an `onBlockUse` counter.
- `freeBlock`, which vanilla `TransientBlockAllocator.rotate` (:47-51) calls only for blocks whose frame completed (or
  at close), puts 4 MiB blocks into a reserve while the reserve holds fewer than `highWater` blocks. `highWater` is the
  most blocks any of the last 120 rotations used.
- `allocateBlock(BLOCK_SIZE)` takes from the reserve before creating a block.
- Each `endFrame` trims the reserve to `highWater`, and `close()` releases it.
- Contents of transient memory are undefined or overwritten before use, and blocks were already recycled across
  frames, so the image cannot change.
- With the toggle off: the 3-argument allocator constructor as before, and `reserveCount` stays 0.

**Tests.**
- `tools/metal-device-test.sh`, 3 new sections (574 checks total, all pass):
  - Hysteresis: reserve capped at the high-water mark, reuse instead of a third block, decay after 120 idle rotations,
    release at close.
  - Renaming over 54 renames, within a frame and across frames with the previous frame in flight. Checks: no
    COPY_BUFFER into the UBO; every pending reader's backing still holds its record-time bytes; retired backings are
    reused only after completion; 4 backings in steady state; spares released at close.
  - Refusals, each staged with an unchanged handle: pending GPU write, mapping, more than 64 KiB (exactly 64 KiB
    renames), texel usage, transient. Also: forceFastWrite, and toggle off = the old staged record.
- `tools/metal-e2e-test.sh`, new scene "UBO rewritten while earlier frames read it in flight". On real Metal with the
  debug layer:
  - 24 frames with a heavy pass first, each rewriting only the ColorModulator twice (cross-frame and between passes).
  - Renaming off and on run in one process. All 48 targets must be byte-identical, and each must show its own
    modulator and full coverage (Projection kept).
  - 47 renames, 23 of them cross-frame.
  - The suite now prints a SHA-256 over every readback. It is `68765d48…` for: the default, the default again,
    `renameSmallWrites`, `transientHysteresis`, and both with `MCMTL_COPY_STATS=100`.
- Mutation check: a retired backing reused immediately (`freeAt = 0`) fails both suites. The e2e scene shows in-flight
  frames drawing the next frame's color.
- Also pass with both toggles on: `test-i4-encoder.sh` (939), `terrain-e2e-test.sh`, `occlusion-e2e-test.sh`, and
  `MTL_DEBUG_LAYER=1 build-native-tests.sh --run`, also with `MCMTL_COPY_STATS=2`. (Correction, r5-int: I4's
  `hotPathAllocationFree` records draws in one pass with a transient-memory test double, so it exercises neither toggle;
  it is not evidence for them. The allocation evidence is the MetalDeviceTest section below.)
- r5-int review fixes, `tools/metal-device-test.sh` (601 checks): "renaming recovers after a staged write" (a mapped,
  hence staged, in-flight write is kept for replay, not marked; the next in-flight write renames, the new backing holds
  old bytes + replayed write + new write, pending readers keep their bytes; a completed staged write is pruned, not
  replayed; the 9th pending staged write marks a GPU write and refuses renaming); "COPY_TEXTURE_TO_BUFFER destinations
  are GPU writes" (marked; an in-use write is staged into the same backing; after completion the rename keeps the
  GPU-written bytes); "no per-frame allocation" (real `MetalTransientMemory`, submit / endFrame / drain, 2 frames in
  flight, 2000 measured frames: a renamed write every frame allocates no more than the same loop without it, and a
  block-churn loop allocates no more with hysteresis than without; tolerance 4 KiB). Mutations: marking staged writes
  again, deleting the COPY_TEXTURE_TO_BUFFER mark, and one array allocation in `renameForWrite` each fail these sections.

**Interactions.** The occlusion planner (16.6, off) sees a new UBO handle each frame for renamed buffers. It reads
contents, so this is exact, but evidence keyed by handle may reset more often. Watch this if both are turned on.
`debugLabels` / `MCMTL_COPY_STATS` give renamed backings the buffer's label plus " (renamed)".

**In game (round-5 A/B, `results/ROUND5-AB.md`).**
- **Renaming: decided default on, together with the late throttle.**
  - On top of the late throttle: static median +2.7%, avg +3.0%.
  - The 3 lead UBO copies per frame disappear (Global Settings, Camera projection, Chunk Sections Instanced), and static
    transient-block creation goes from about 300 per 1000 frames to 0.
  - The 256 KiB Shared Buffer ring copies and the GUI vertex buffer copies (game copyToBuffer) still lead 98% of frames,
    so Blit0 remains. No MST was run.
  - Without the late throttle, renaming + hysteresis had a worse 0.1% low (−8.4%, 3 of 3 pairs, not explained).
- **Hysteresis: stays off.** It showed no gain on top of the late throttle, and on top of the decided set it was static
  −3.7% 1% low and neutral in travel.

### 16.R5-B Bench frame trace and effective-config block (round 5, Task B; `-Dbench.frameTrace`, default off)

Instrumentation only (results/TAIL-ANALYSIS.md §6): one bench run attributes every slow frame. No rendering, record,
opcode, `mcmetal.h` or ABI change (still ABI 2). Differs from the §6 sketch: the trace is a `"frameTrace"` block of the
result JSON (not a CSV), and the switch is `bench.frameTrace` (it sets `mcmetal.frameTrace`).

| Toggle | Default | Effect |
|---|---|---|
| `-Dbench.frameTrace=true` | off | BenchAgent inserts the `FrameProbe` probes into `Minecraft.runTick` / `renderFrame`, sets `mcmetal.frameTrace=true` (unless given) and the result gets `"frameTrace"`. |
| `-Dmcmetal.frameTrace=true` | off | Metal backend keeps `MetalFrameTrace.C` counters and installs the native ring at the first commit. |
| `-Dmcmetal.frameTrace.ringLog2` | 17 | Native ring size (2^n timeline values, 64 B each; clamped 8..24). |
| `-Dbench.frameTrace.max` | seconds × 2500 + 4096 | Preallocated rows (overflow counted in `dropped`). |
| `-Dbench.frameTrace.cpu=false` | on | No render-thread CPU columns (`ThreadMXBean`, ~1 µs per sample, µs resolution). |
| `-Dbench.frameTrace.jfrSlowMs` | 0 = off (r5-int; was 4) | `bench.SlowFrame` JFR event per interval ≥ threshold (fields = the trace row), for JFR-from-launch runs; set it (e.g. 4) to opt in. Even then an event object is created only while a recording has the event enabled (`EventType.isEnabled()`), so slow frames allocate nothing otherwise. |
| `-Dbench.guardTrips=fqcn#member` | — | Late-throttle guard-trip counter (static or backend-instance field / no-arg method: long, int, long[0], AtomicLong, LongAdder). Defaults tried in order (`MetalTraceHook.GUARD_CANDIDATES`); on the integrated client `MetalFrameThrottle#guardTrips` resolves (a never-reset total of 16.R5-A's guard trips, r5-int). `MetalFrameTrace#guardTrips` only when named, since unwired it reads 0. |
| `-Dbench.config=false` | on | Omit the `"config"` block. |

**Mechanism.**
- **Probes** (`bench/FrameProbe`, `BenchAgent.traceHooks`): per call site, matched by owner/name/descriptor so vanilla and
  the patched client both work in any order: `enter(id)`/`exit(id)` around `processQueuedPackets`, `runAllTasks`,
  `TextureManager.tick`, `Minecraft.tick` (runTick) and `ClientLevel.update`, `GameRenderer.extract/render`,
  `CommandEncoder.submit`, `GpuSurface.present`, `FramerateLimiter.limitDisplayFPS` (renderFrame);
  `ticks(int)` wraps the `advanceGameTime` result (bundle start); `bundleEnd()` before `setErrorSection("Render")`. The
  bundle also samples thread CPU time and the Metal wait, drain and native-submit counters, so a late throttle (and a
  flush commit or drain) inside it is separated (`bundleWaitNs`, `bundleDrainNs`, `bundleNativeNs`). With 16.R5-A on, the
  tick bundle runs in `Minecraft.runTickBundleLateThrottle`; its four runTick call sites are probed too (r5-int: the
  agent reports packets/tasks/texTick/tick = 2 on the patched jar, one site per order, one of which runs per runTick).
  `enter(SUBMIT)` snapshots the Metal wait / native / drain counters, so the time they took before `submit()` (a guard
  trip during rendering, a fence wait, a drain) is split out of the render time (`preSubmit*Ns`).
- **Rows** (`bench/FrameTrace`, row k = `frameTimes[k]`, the interval ending at onFrame k): 46 columns — `dtNs tNs view
  ticksToDo ticks bundleNs bundleCpuNs bundleWaitNs packetsNs tasksNs texTickNs tickNs preSubmitNs levelUpdateNs extractNs
  renderNs submitNs presentNs limiterNs frameCpuNs gcEvents seq commits nativeSubmitNs waitNs drainNs encoders draws
  blocksCreated blocksFreed guardTrips resultFlags gpuStartNs gpuEndNs gpuBusyNs cbs cbsDone drawableWaitNs presentKind
  fuseFallback commitNs bundleDrainNs bundleNativeNs preSubmitWaitNs preSubmitNativeNs preSubmitDrainNs` (the last five
  r5-int); -1 = unknown. All sources are cumulative counters differentiated per row. `drainNs` is a drain's own time
  (waits / commits made by its callbacks are subtracted: they are counted as wait / native submit).
- **Metal** (`MetalFrameTrace`): `C[]` slots updated by 3 hooks — `MetalCommandEncoder.commit` (cpuNanos, encoders,
  draws, records, FRAME_END seq/flags), `MetalTimeline.await` (native wait time), `MetalTimeline.drain`. `sample()`
  copies the transient-block counters. Each hook is `if (MetalFrameTrace.ENABLED)` on a static final.
- **Native ring** (`native/src/mcmetal_frametrace.{h,m}`; exports `mcmtl_frametrace_enable / _clock / _counters / _config`,
  outside the frozen header, bound lazily and optionally by name): `mcmtl_submit` resets the value's 64-byte entry before
  decoding and stores commit time + decoder-counter deltas (drawable wait, fused / presented / skipped / nil, fallback
  and skip reasons, command buffers, encoders) after; the shared completion-handler body folds `GPUStartTime` /
  `GPUEndTime` of every command buffer attributed to the value (signalling + untimed early commits) with atomic
  min / max / add, then `cbCompleted++`. The bench copies the ring once after MEASURE (`MetalFrameTrace.gpuRecords`) and
  merges it per row over the values the row committed (`(seq[k-1], seq[k]]`).
- **Clock**: GPU times are host time (`CACurrentMediaTime` base) in ns; `System.nanoTime` on macOS is
  `mach_absolute_time`, the same clock as `CLOCK_UPTIME_RAW`. Measured offset −230 ns (bracket 0.6 µs); the
  conversion uses the measured offsets anyway (`frameTrace.metal.clock`).
- **Config block** (`bench/ConfigBlock`, every perf/travel result): JVM (args, GC, heap), all `mc.* mcmetal.* bench.*
  terrain.* org.lwjgl.*` properties, all `MCMTL_* MTL_* METAL_* SDL_*` env, the static toggle fields of
  `TerrainToggles`, `TerrainCompact`, `TerrainStats`, `Zoom` and (Metal) `MetalDevice`, `MetalCommandEncoder`,
  `MetalTimeline`, `MetalTransientMemory`, `MetalRenderPass`, `TerrainShaderVariants`, `MetalLateThrottle`/`LateThrottle`
  if present (+ `bench.configClasses`), the native effective config (`fuseMode`, `presentFusionEnv`, M2 flags, ICB,
  occlusion, `mailboxActive` / `drawableCount` of the surface, `profilePasses`, …), decoder counters over MEASURE with
  `fusedShare`, `presentedShare`, `skippedShare`, `mailboxShare`, `drawableWaitMsPerFrame`, fusion fallbacks, present
  skips; video options; sha1 of client jar, bench jar and dylib. Prepared in WARMUP (handles bound), one native counters
  read at the WARMUP→MEASURE transition, the rest after the run.
- **Analyzer** `tools/frame_trace.py result.json [--top N] [--bin-ms W] [--csv F] [--json]`: config summary, CPU-interval
  p50…max vs GPU cadence (completion to completion) and present cadence p99, GPU busy, starved-GPU count, tick vs plain
  p99, tick share of the slowest 1%, tick-bundle wall vs CPU, slowest 1% / 0.1% tables attributed to the component
  with the largest excess over its median. The components partition the interval, every nanosecond once (r5-int): tick =
  bundle − the waits / drains / native submits inside it; gpu = every timeline wait (in submit, at a late throttle point
  inside the bundle, at a guard trip during rendering, fence waits); drawable; submit = all native submits − drawable;
  drain = all drains (own time); render = pre-submit time − the waits / native submits / drains in it; other = the rest.
  Flags: T tick, G GPU starved (r5-int: the frame's first command buffer started > 20 µs after the previous frame's GPU
  work ended, whether or not an early commit started it before `commitNs`), C GC, B blocks created, P guard trip;
  histogram, per-30°-yaw table (frames, p50/p99, mean GPU busy, slow frames). The C2 summary (tick-bundle wall vs
  render-thread CPU) uses the bundle wall without its Metal waits / drains / commits (the late-throttle wait is inside
  the bundle) and reports the raw wall time next to it.
- **Bench timing (r5-int).** The WARMUP→MEASURE transition takes the config / late-throttle / trace snapshots first and
  then samples the measurement origin (`t0`, used for `lastFrameNs`, `secondStartNs` and the trace's time origin), so none
  of their cost (≈ 0.1–1.1 ms for the first config read) lands in `frameTimes[0]`; from there on the transition is
  r5-base's code.

**Invariants.**
- Off (default): BenchAgent inserts only `Bench.onFrame` (unchanged); the three Java hooks are dead code behind a static
  final false; native: one relaxed pointer load + branch per submit and per completed command buffer. Rendering,
  packets, ticks and timing semantics unchanged; nothing is server-observable. On: the probes add ~25 ns `nanoTime` each
  (≈ 0.5 µs/frame) plus ~1–3 µs/frame of thread-CPU samples and bench bookkeeping; the native begin/end hooks copy 40
  counters twice per submit. Image unchanged (no command recorded; `MetalE2ETest` 1524 checks pass with the trace on).
- Zero allocation per frame on the render thread: rows preallocated in WARMUP, cumulative-counter reads, exact-typed
  `MethodHandle.invokeExact` for `sample()` / guard trips; measured 0 bytes over 2000 traced frames (BenchHookTest),
  including 20 frames slower than the SlowFrame threshold (the test runs with `jfrSlowMs=0.5`): no event object without a
  recording (mutation: the old unconditional `new SlowFrameEvent()` allocates 1760 B there).
- Threads: `C[]`, `FrameProbe` statics and the bench's reads are all render-thread (encoder, timeline waits, runTick,
  renderFrame, onFrame). Ring: render thread writes its fields only between the entry's reset (release store of the tag,
  before any command buffer of the value is committed) and the SUBMITTED flag; completion handlers (Metal threads,
  concurrent) only do atomic RMWs on the GPU fields after an acquire tag check; the reader fences and treats
  `cbsDone < cbs` as in flight. A handler delayed by > ring-size frames is the only race (impossible at 2 frames in
  flight; at worst one diagnostic entry). The ring is never freed. No GPU resource is touched (CPU memory only), so no
  GPU hazard; no completion handler is added (the existing one gets one branch).

**Tests.** `native/tests/frametrace_test.m` (off path, enable/idempotence/clamp, 24 real frames: tags, flags, commit
window, GPU interval inside the host-clock submit window, flush commit, wrap-around, 4000 concurrent completions of one
value exact, 3 real CBs (untimed + signalled) on one value, config JSON + counter names; 1350 checks);
`native/tests/decoder_test.m` `test_frametrace_present` (r5-int): the ring entry of a fused frame, a present pass with
its fallback reason (unflipped layer), a nil drawable and a mailbox skip with its reason equals the decoder's own
counter deltas of that submit (flags, reasons + 1, drawable wait, command buffers, encoders); a swapped flag / off-by-one
reason mutation fails 7 checks; `tools/bench-hook-test.sh` (probe insertion per site on vanilla (1) and patched
`Minecraft` (1, the four tick sites 2 with the late bundle), `ClassFile.verify`, other methods byte-identical, synthetic
loop rows consistent, 0 bytes allocated / 2000 frames incl. slow ones, analyzer on the JSON; 611 checks);
`tools/frame-trace-test.sh` (`frame_trace_test.py`: synthetic Metal + vanilla + late-throttle results with known causes,
exact attribution, and (r5-int) nested Metal time counted once, C2 wall without the bundle's Metal time, starvation from
the idle gap with an early commit; `FrameTraceTest.java`: real Metal device + bench FrameTrace, ring merge for 300/300
rows in the nanoTime base, JFR events = slow rows, config block checks). Regression: all native suites, `metal-smoke`,
I4 (also with the trace on), `metal-e2e` (trace off and on).

### 16.R5-D Cheaper client tick (TAIL-ANALYSIS S4; `-Dmc.fastAnimateTick`, `-Dmc.fastTextureAnimation`, default **on** since 16.R5-FIN)

Java only, client-only classes (`ClientLevel`, `TextureAtlas`, `SpriteContents`; none runs in the integrated server). No
native, record, ABI or shader change; nothing a server can observe changes (no packet, no gameplay state: animateTick
only spawns client particles/sounds, the texture tick only writes the atlas). With both toggles off the vanilla code
runs unchanged (one `static final` false branch each).

**(a)+(b) `mc.fastAnimateTick`** (`ClientLevel.animateTick` → `fastAnimateTick`, after vanilla's own three setup lines,
so `animateRandom` and the marker target are created exactly as before; the debug world keeps the vanilla loop).
Per position, same RNG calls in the same order with the same results, same particles and sounds:
- *Ambient-particle skip.* `AMBIENT_PARTICLES` is positional, not interpolated, not full-resolution, range `any`.
  `computeFastAmbientPlan(dimensionType)` (run in the constructor right after the `EnvironmentAttributeSystem` is baked,
  from the same `HolderSet`, so a later tag update cannot split them) is 1 iff no timeline of the dimension and no weather
  map carries the attribute and the dimension constant folded onto the default is empty after `sanitizeValue`. Then the
  sampler is `[biome positional]` over an empty base, and for a biome whose attribute map has no entry
  `applyModifier` returns the base: the list is empty, the vanilla loop calls neither `canSpawn` nor `nextDouble`, so
  the lookup is skipped. Biomes with an entry (vanilla: basalt deltas, soul sand valley, crimson/warped forest) take the
  vanilla `getValue`. The per-biome answer is cached by `Holder` identity (2-entry MRU; a holder's value is immutable).
- *Section-direct lookups.* One `ClientChunkCache.getChunk(…, false)` per chunk per tick (5x5 grid around the player:
  positions are within ±31; no packet runs inside animateTick), then `Level.getBlockState`'s rules: outside
  `isInValidBounds` or chunk absent → `VOID_AIR`, `hasOnlyAir` → `AIR`, else the palette. Fluid = `state.getFluidState()`
  (= `LevelChunkSection.getFluidState`; AIR/VOID_AIR → EMPTY; no block's animateTick changes blocks, checked over every
  vanilla `animateTick`). Biome = the same section's quart cell (no clamp inside the build height), else
  `getNoiseBiome` (vanilla's own path).
- *Batched position RNG.* The six `nextInt(16|32)` on the level's `LegacyRandomSource` become one seed read, six LCG
  steps (`fastLcg`, `fastNextIntPow2`) and one `compareAndSet` (12 atomics → 2). Same values and final seed; a lost CAS
  throws the same ThreadingDetector exception. `-Dmc.fastAnimateTick.batchRng=false` keeps the six calls.
- `-Dmc.fastAnimateTick.verify=true`: per position re-runs vanilla's block/fluid/biome lookups, the RNG and (on a skip)
  `getValue(AMBIENT_PARTICLES)`; logs mismatches (`mc.fastAnimateTick.verify:`). Diagnostic, slower than vanilla.
- Zero allocation per position; per tick only vanilla's two (animateRandom, MutableBlockPos). Render thread only.

**(c) `mc.fastTextureAnimation`** (`TextureAtlas.uploadAnimationFramesFast`, `SpriteContents.AnimationState.fastDrawToAtlas`).
- **One encoder per atlas is not possible exactly.** A Metal render encoder has one mip level per attachment, and all
  attachments share one render area anchored at their level 0, so mip levels cannot share a pass. A compute or
  fragment-write path would be provably exact only for BLIT sprites (texel = frame(clamp(i − p >> L)), texel-centre
  sampling); INTERPOLATE sprites (23 in the block atlas, drawn every tick at every level) depend on the rasterizer's
  varying interpolation and the compiler's `mix`. So the passes stay: 5 per tick for the block atlas (mip 4), plus one
  for each other atlas whose sprites changed (gui, particles).
- **What changes.** Same passes, draws and uniforms, grouped by pipeline: INTERPOLATE sprites, then dirty BLIT sprites,
  with 2 `setPipeline` per pass instead of 1 per draw. In vanilla each `setPipeline` re-sends every uniform the frontend
  holds, and on Metal every pipeline switch re-records all bindings. This is exact: sprite slots (including padding) are
  disjoint, `ColorTargetState.DEFAULT` does not blend and there is no depth, so every texel is written by one draw
  whatever the order.
- **No per-tick allocation:** no `stream().anyMatch`, no capturing label lambda per pass, and the sampler and pipelines
  are looked up once per pass. The frontend still allocates its `TextureViewAndSampler` per texture `setUniform`, as in
  vanilla.
- GPU hazards: none new (same resources, same pass order, frame textures and UBOs immutable after upload).

**Tests** (all under the GPU lock, all pass):
- `tools/animate-tick-test.sh`: a real patched `ClientLevel` (Unsafe-built: real `LevelChunk`s in a real
  `ClientChunkCache`, vanilla biome registry, overworld and nether heights, vanilla static attribute layers) with
  30816 block states (block entities excluded), fluids, all-air sections, missing chunks, build limits, nether ambient
  biomes and marker targets.
  - Vanilla's loop vs `fastAnimateTick` from the same seeds: 800 ticks, 41943 particle/sound calls, 0 mismatches in
    the hash, the level-RNG seed afterwards and the animateRandom state afterwards.
  - Run 3 times: default, `batchRng=false`, and `verify` (clean).
- `tools/client-tick-test.sh`: the RNG helpers vs `LegacyRandomSource` (400k seed/bound pairs and edge seeds); the plan
  for every vanilla dimension type, plus a negative case; skip exactness against the real `EnvironmentAttributeSystem`
  for 4 dimension types × 67 biomes × 3 positions (756 skips exact, 48 consulted); AIR fluid identities; quart-Y
  no-clamp. (r5-int) Timeline branch: a timeline with an AMBIENT_PARTICLES track (built from a vanilla timeline with the
  extra track key), inserted first, middle and last in the dimension's timeline set, and as its only timeline, makes
  the plan 2 (mutation: an always-passing timeline check fails 4 checks); vanilla rain / thunder layers pinned to carry
  no AMBIENT_PARTICLES (a positive weather case would need a different vanilla constant). 828 checks.
- `tools/atlas-anim-test.sh` (real Metal, debug layer on): a block atlas built from the jar's textures (54 animated
  including 23 interpolated, 400 static, mip 4, 2048x1024). The SHA-256 of every mip level after the upload and after
  each of 320 ticks is identical with the toggle off and on (1605 digests, 321 distinct states), with no device
  messages. (r5-int) Two more configurations, toggle off vs on identical: no interpolated sprites (31 animated: the
  BLIT-only pass every tick; 1205 digests) and 4 slow animations (frame time ≥ 3: the BLIT-only pass on change ticks
  and the "nothing to draw" early return on the 70 ticks without a change; 605 digests); the script fails if no tick
  without a change occurred.

**In game (round-5 A/B, `results/ROUND5-AB.md`): both decided default on** (the code default is still off).
- Frame trace with the late throttle on: client tick() −0.21 ms (−30%) and texture tick −42 µs per tick frame (static),
  −0.11 ms in travel.
- FPS: static 1% low +4.6%, travel within noise.
- `mc.fastAnimateTick.verify` over the full scene sweep: 0 mismatches.

**Measured** (headless, not in game; superseded by the in-game numbers above):
- animateTick on a terrain-like world: 112 → 48 µs per tick. Without the batched RNG: 105 → 68 µs.
- Block-atlas animation tick, debug layer off: Java recording 26.1 → 15.3 µs; stream 32.1 → 24.1 KB and 1044 → 753
  records; `mcmtl_submit` cpuNanos 72 → 61 µs (that submit carries only the 5 Animate passes and 204 draws).
- Together about 0.08 ms of the 0.7–1.4 ms exposed tick (E7).

**(d) tickEntities, by reading code and the 11 r3-static JFR samples under `tickEntities`**
- Samples: 4 in `EntityFluidInteraction.update` (2 in submerged `AgeableWaterCreature`s through
  `FlowingFluid.getHeight → hasSameAbove → Level.getFluidState(pos.above())`, 1 in `getHeightForCamera`, 1 in an
  `ItemEntity`'s `getCurrentAccumulatorFor`); 2 in the local player (the stream in `BubbleColumnAmbientSoundHandler.tick`,
  `FirstPersonHandsAndItems`); 5 spread over Mob/Creeper/Bat tick, `checkBelowWorld` and `tickNonPassenger`.
- The bench world holds 288 entities, many of them underground hostiles.
- Ranked exact proposals (not implemented here: every one of them lives in classes the integrated server shares, so each
  would need a `level.isClientSide()` branch that keeps the server on the vanilla path):

  1. **Fluid scan.** On the client branch of `EntityFluidInteraction.update`, prefetch the box's fluid states plus one
     extra row above, section-direct, into a small array, then run vanilla's x→y→z loop over it. `hasSameAbove`
     becomes an array read, and the order of `accumulate` calls stays the same, so the current vector is bit-identical.
     The `getAmount()/9` formula applies only when the type is a `FlowingFluid`, else `getHeight`. Expected about
     halving the cost for entities in water (2–3 lookups → 1 per block, no `pos.above()` allocations) for the box scan
     only. (r5-int correction) For current-affected entities `FlowingFluid.getFlow` also reads the 4 horizontal
     neighbours' fluid states, the neighbours' block states, the block below and, when FALLING, `isSolidFace` above:
     reads outside the box + 1 row above. Either widen the prefetch to box ±1 in x/z and −1 in y, or leave `getFlow`'s
     neighbour reads on the vanilla path (and count only the box scan in the estimate).
  2. **Fluid result reuse.** For interpolated client entities, keep the last `update` inputs (box, `eyeY`,
     `ignoreCurrent`) and a client "block-change epoch". When all are unchanged, replay the cached trackers and
     accumulated current. Exact only if the epoch covers every input of `update`: bumped by `ClientLevel.setBlock`,
     chunk/section loads, biome replacement, (r5-int correction) **chunk unloads** (`ClientChunkCache.drop` and forget:
     `hasFluidAndLoaded` returns false for a missing neighbour chunk over x0−1..x1+1 / z0−1..z1+1, so an unload flips
     the result of entities in the adjacent loaded chunk) and **fluid tag updates** (`ClientboundUpdateTagsPacket`:
     `getCurrentAccumulatorFor` matches `fluid.is(TagKey)`). Removes the scan for standing mobs.
  3. **Allocation churn.** Per entity per tick there are about 6 `Vec3`/`AABB` objects, the `EnumMap` entry iterator in
     `EntityEquipment.tick`, the passengers iterator in `tickNonPassenger` (`isVehicle()` guard), and the
     profiler-supplier method ref (skip `push`/`pop` when the profiler is inactive). Value-identical rewrites on client
     branches.
  4. **Local player.** Replace the `BubbleColumnAmbientSoundHandler` stream with a loop that stops at the first
     bubble-column state, in the same order. This one is client-only and could ship alone.

**Toggles**

| Property | Default | Effect |
|---|---|---|
| `mc.fastAnimateTick` | **true** (16.R5-FIN; was false; `=false` is the off switch) | animateTick fast path (a)+(b) |
| `mc.fastAnimateTick.batchRng` | true (with the above) | batched position RNG; `=false` keeps six `nextInt` |
| `mc.fastAnimateTick.verify` | false | diagnostic: compare with the vanilla lookups per position, log mismatches |
| `mc.fastTextureAnimation` | **true** (16.R5-FIN; was false; `=false` is the off switch) | pipeline-grouped Animate draws, no per-tick allocation |

### 16.R5-E Travel max-frame hitches (TASK E, `mc.terrain.pregrow`, `mc.terrain.uploadBudgetKB`, `mc.asyncEntityTextures`; pregrow default **on** since 16.R5-FIN, the other two off)

Source: results/TAIL-ANALYSIS.md §5 V2 (travel max frames 6.5-21 ms). No change to mcmetal.h, the stream, records or ABI
(still 2). One optional extension export in a new file (below). Every toggle off = the previous code path.

**Measured cause of the heap hitch** (real M5 / macOS 27, `tools/travel-hitch-test.sh` native part + standalone probes
`tools/heap_residency_probe.m` / `heap_residency_probe2.m`, the latter with a 1 ms frame loop on the main queue):
- `newBufferWithLength:` 128 MiB Shared: 0.02-0.2 ms. Release: ~0 ms.
- The first command buffer that references the fresh buffer: +3.3-4.4 ms between commit and completion (pages
  zero-filled, wired, GPU-mapped). That is the in-frame cost of a new uber-heap (E14).
- CPU page touch first (one byte per 16 KiB): first use still +1.1-1.4 ms.
- 4-byte blit fill on a **side** command queue on another thread: 3.2-4.6 ms there, main queue not delayed (worst
  0.19-0.25 ms vs 0.17 mean); the main queue's first use then costs nothing extra.

**1. Pregrow (`-Dmc.terrain.pregrow=true`).**
- `UberGpuBuffer`: one spare heap buffer per uber buffer. Requested from `MetalHeapReserve` when the heaps' free bytes
  (heap count × size − Σ live block sizes) fall below `pregrowFree` (0.25) of a heap, collected (`poll`, non-blocking)
  at the end of a later upload pass, or kept from a heap that became completely free while free ≤ 3 × that. Released
  (plain `close`) above 3 ×. Σ spares ≤ `pregrowSpareMB` (320). (r5-int review fix) An uber buffer with no heap at all
  (a layer with nothing in view) gives its spare back after `pregrowIdlePasses` (600) upload passes in a row without a
  heap, so idle layers (up to 128 + 32 MiB each) do not hold spares against the cap while another layer grows; a heap
  needed before then takes the spare and restarts the count.
- When vanilla creates a heap (`allocation == null` in every node) the heap takes the spare instead of `createBuffer`.
  The spare is never in `nodes` before that moment, so placement (TLSF state), upload order, callbacks, listener events
  and the return value (`newHeapCreatedOrDestroyed`, which makes `uploadTerrainBuffersToGpu` stop the layer loop) are
  exactly vanilla's; only the buffer object differs (contents undefined by the API; only uploaded ranges are read).
- A reused kept heap is the same `MetalBuffer` (its `lastUseSeq` intact), so writes into it take the same
  hazard-tracked path as writes into any live heap region freed this frame.
- `MetalHeapReserve` (backend): one daemon worker; per (size, usage) class at most one buffer pending/ready (≤ 4
  classes). The worker calls `mcmtl_buffer_create` (thread-safe per mcmetal.h) with `MetalBuffer.create`'s options, then
  `mcmtl_ext_buffer_make_resident`. `poll` wraps the ready backing with the normal `MetalBuffer` constructor
  (`wrapReserved`).
- Lifetime: every native call is inside `beginCompile`/`endCompile`, so `MetalDevice.close()` (which waits for those)
  can never destroy the device under it. `close()` calls `uninstall` first, which stops the worker and releases an
  uncollected buffer.
- Memory order: slot fields are written before the volatile `state = READY` and read after it. Requests are idempotent
  and allocation-free once a class exists.
- `mcmtl_ext_buffer_make_resident` (`native/src/mcmetal_reserve.m`): not in the frozen header. It is resolved by name
  lazily; if it is missing, the worker touches the pages on the CPU instead. It does a zero fill of bytes 0-3 on a side
  queue the `MCMTLDevice` owns (an associated object, created under `@synchronized`), then commits and waits. It never
  touches the main queue, the shared event, the status page or the decoder.
- `DynamicGpuData.presizeChunkSections(rd)`: LevelRenderer calls it at world join and on a render-distance change. It
  gives the indirect command ring and the instanced section-info storage `pregrowDrawsPerColumn` (4) × (2 rd + 1)²
  records, now and at lazy creation. That replaces the in-frame "Resizing Chunk Sections Command Buffer 2048->4096"
  (3 ring buffers + a log line). Only capacity changes; each frame still writes from offset 0, and a larger frame still
  grows as in vanilla.

**2. Upload budget (`-Dmc.terrain.uploadBudgetKB=N`, 0 = vanilla; `uploadMaxDelayFrames` 2, `uploadFlushFill` 0.5).**
- `SectionRenderDispatcher.uploadTerrainBuffersForFrame` (LevelRenderer's per-frame call) uploads vertex entries while
  the budget lasts; the first entry always goes. The rest stay staged (carried over).
- Forced, whatever the budget:
  - entries older than `uploadMaxDelayFrames`. Delay vs vanilla ≤ that many frames, i.e. 4 ms at 500 FPS and 33 ms at
    60 FPS with the default 2;
  - entries staged on the render thread (`compileSync`);
  - whole frames while the client waits for the player's section (`playerCompiledSectionCallback`, so the
    level-ready / loading-screen timing is vanilla's);
  - whole frames once the staging buffer is ≥ half full (it then drains and rotates as in vanilla);
  - the staging-full path `uploadTerrainBuffersToGpu()`, which always flushes.
- The index pass follows the vertex pass per key, so a section-layer's vertex and index data land in the same frame.
  Index-only entries (translucency re-sorts) are never delayed.
- Entries of released meshes are dropped, including meshes released by callbacks during the pass. In budget mode a
  mesh compiled for a node its section has left is released instead of shown. Vanilla has a 1-frame window where it
  could show it.
- **Per-section ordering (r5-int review fix, major).** Carrying entries reorders uploads: the budget decides per entry in
  identity-hash order, so an older mesh M1 of a section could be carried while a newer mesh M2 of the same section
  (a `compileSync`, a later async compile, or an EMPTY result the worker sets directly) was shown first; when M1's carried
  upload completed, `checkSectionMesh` showed it and released M2: stale geometry until the section was dirtied again
  (the review probe: 144 / 200 trials with an urgent M2, 60 / 200 with an async one). Now every `CompileTask` gets a
  mesh sequence number when it is created on the render thread (`SectionRenderDispatcher.meshSeqCounter`, budget mode
  only; 0 otherwise), carried in the upload callbacks like `compiledForNode`. A `RenderSection` records the number of
  the mesh it shows (`appliedMeshSeq`, under `copyLock`) and only ever replaces it with a newer one: an older mesh that
  completes later is released instead of shown. The EMPTY / block-entities-only path (worker, no upload) takes `copyLock`
  and the same check in budget mode. Newest-wins also removes vanilla's own same-pass race in budget mode; with the
  budget off nothing changes (no counter, no check).
- With flush the budget path equals vanilla (test). Not server-observable: uploads are client-only, and the
  loading-time flush keeps the level-ready signal's timing.

**3. Entity textures (`-Dmc.asyncEntityTextures=true`; `.dirs` = `textures/entity`, `.maxMB` = 64).**
- `TextureManager` (patched copy): at the start of `reload()` (synchronous inside `createReload`, right after the pack
  swap) the prefetch is invalidated; after the reload's apply step a daemon thread runs `TextureContents.load` for every
  PNG under the directories, using the reload's resource manager. Vanilla jar: 743 PNGs, 14.3 MB decoded.
- `getTexture` of an unregistered id takes the prefetched contents and applies them exactly as `registerAndLoad` would
  (same sampler from the same metadata, same pixels, same upload). Otherwise it loads synchronously as vanilla, never
  with a placeholder.
- Generations + putIfAbsent + re-check + drain: each image is closed exactly once and no stale entry is ever returned.
- Not fixed: the deopt storm at the same moment (r3-travel.jfr, t = 10.02 s). It is 40 `class_check` /
  `bimorphic` deopts in `extract*RenderState` / `submit` / `setupAnim` for the first skeleton (a new receiver class),
  i.e. JIT re-profiling of a new entity type, not texture loading. Only pre-warming entity renderers would avoid it,
  which is not exact-safe.

**4. `LayerLightSectionStorage.swapSectionMap` (analysis only, not changed).**
- The clone is required on the client too: section-compile workers read the client light engine through
  `RenderSectionRegion.getLightEngine()`, and the volatile immutable snapshot is what makes those reads safe.
- `LevelLightEngine` and the storages are shared with the integrated server's `ThreadedLevelLightEngine`. A client-only
  variant needs a constructor flag through `ClientChunkCache` → `LevelLightEngine` → `Block/SkyLightEngine` → storages
  (5-6 patched classes), plus a persistent (structurally shared) section map, including Sky's `topSections` map.
- Cost: ~96 KB/frame of allocation; a ~0.8 MB clone every ~8-16 frames is ~40-80 µs; ZGC pauses ≤ 27 µs (E15). It is an
  average-cost item, not a max-frame cause, so it is not worth the risk now.

**Tests.**
- `tools/travel-hitch-test.sh` runs `TravelHitchTest` (41k checks, also clean with `MTL_DEBUG_LAYER=1`):
  - the headless reserve: idempotent requests, poll, class limit, close;
  - the real-device reserve: 128 MiB created and GPU-resident on the worker, poll 0.005 ms;
  - pregrow vs vanilla on 6 seeds × 1500 frames of grow/shrink: identical copies (offset, length, bytes), callbacks,
    return values, heap count and per-key heap index/offset/size; in-frame heap creations 49-67 → 5-11;
  - spare lifecycle;
  - budget = vanilla with flush; delay ≤ bound and vertex/index pairing on 4 seeds × 800 frames with bursts;
    maxDelay 0 never delays; released / urgent / re-sort / released-by-callback cases;
  - prefetch = synchronous load (pixels, format, blur/clamp), generations, racing invalidate (20 rounds, most of them
    interrupted), close.
  - (r5-int) per-section ordering on the real `SectionRenderDispatcher` / `RenderSection` / `CompileTask` (stub
    `SectionCompiler`, fake GPU, budget 24 KiB, 6–9 filler sections per trial): M1 async, then M2 as urgent
    `compileSync`, as a later async compile, and as EMPTY, 150 trials each. The newest mesh stays shown in every trial;
    M1's upload completed after M2 was shown (the overtaking case) in 35–111 trials per variant; nothing stays staged
    or allocated after the sections are reset. Without the fix: 48–111 wrong meshes per variant.
  - (r5-int) idle-spare release: the last freed heap is kept as the spare, held for `PREGROW_IDLE_PASSES − 1` passes,
    taken by a regrowing layer (no in-frame `createBuffer`), and given back (closed, cap freed) after the idle count.
  - (r5-int) the toggle-off path against a verbatim copy of the r5-base `UberGpuBuffer` (`tools/ref/RefUberGpuBuffer.java`,
    only renamed + 2 read-only accessors): identical return values, copies (offset, length, bytes), callbacks, heap
    counts and per-key heap index / offset / size on 6 + 3 seeds × 1500 frames; the flush-mode budget path equals the
    toggle-off path, hence r5-base.
  - (r5-int) the pregrow, budget and released/urgent rigs rerun on persistently mapped staging (what `MetalDevice`
    reports: `writeToBufferIsSlow` + persistent mapping → `StagingBuffer.PersistentlyMapped`, a `MappableRingBuffer`,
    `copyToBuffer`; 14k ring rotations), and every upload callback checks that the copy just made carried the bytes
    staged for its key (so no carried entry ever reads staging memory a rotation reused). 132k checks in total.
- Mutation check: 6 of 7 injected bugs fail it. The 7th is the defense-in-depth generation check in `take`, which no
  test can reach.
- Unchanged suites pass: metal-device (316, also with pregrow on), terrain (157195), terrain-e2e (1581), metal-e2e
  (1524), metal-smoke, occlusion-e2e, zoom (143), native abi/core/decoder/occlusion under `MTL_DEBUG_LAYER=1`, and the
  terrain-drawlist check (defaults, pregrow on with real-Metal spares, and budget). Across runs that check is not
  deterministic (identity-hash order), even on the r5-base jar.

| Toggle | Default | Effect |
|---|---|---|
| `-Dmc.terrain.pregrow` | **true** (16.R5-FIN; was false; `=false` is the off switch) | Spare heaps created and made resident off-thread / kept; indirect storages pre-sized at world join |
| `-Dmc.terrain.pregrowFree`, `pregrowSpareMB`, `pregrowDrawsPerColumn` | 0.25, 320, 4 | Low-water fraction (high = 3×), spare bytes bound, pre-size factor |
| `-Dmc.terrain.pregrowIdlePasses` (r5-int) | 600 | Upload passes without a heap after which an uber buffer gives its spare back |
| `-Dmc.terrain.uploadBudgetKB` | 0 | Per-frame terrain upload budget (0 = vanilla) |
| `-Dmc.terrain.uploadMaxDelayFrames`, `uploadFlushFill` | 2, 0.5 | Delay bound in frames; staging fill that forces a flush |
| `-Dmc.asyncEntityTextures` (`.dirs`, `.maxMB`) | false (`textures/entity`, 64) | Background decode of entity textures after each reload |
| `-Dmc.terrain.meshStats=N` (existing) | 0 | Now also per layer: `heaps vertex/index: heaps n, created in-frame n, spares used n, spare none/kept/reserved` |

Log lines for the A/B:
- `Metal heap reserve: on`, `... MiB buffer ... created and made resident ahead of need in X ms (GPU)`, and at exit
  `N buffers created ahead (M made resident on the GPU), K used`.
- `Entity texture prefetch: N textures (X MB) decoded in Y ms`, and at exit `N loads served from the prefetch, M loaded
  synchronously`.
- With pregrow, "Resizing Chunk Sections Command Buffer" should no longer appear after world join at RD ≤ 16.

**In game (round-5 A/B, `results/ROUND5-AB.md`).**
- **pregrow: decided default on, weak.**
  - The travel max frame (15–42 ms) is a GPU stall on the first use of a new 128 MiB heap, at t ≈ 15–19 s.
  - Across 12 interleaved pairs the max frame is lower with pregrow in 8: mean 16.0 vs 20.1 ms. In-frame "Resizing
    Chunk Sections" goes from 9–11 per run to 0.
  - The side-queue fill (5–53 ms) did not reliably prevent the first-use stall. Early in the session the stall was gone
    in 8 of 10 runs; later it was gone in 5 of 24, still at the first use of the spare heap.
  - The stall needs a real fix: fill at world load, smaller heap steps, or per-frame touching just before use.
- **asyncEntityTextures: stays off.** 24–31 loads per run come from the prefetch, but there was no measurable effect
  (render spikes and the max frame unchanged).
- **uploadBudgetKB: stays 0.** At 512 KB the gain was within noise, and it delays terrain by design.

### 16.R5-INT Round-5 integration (A, C, B, D, E merged on `r5-int`; `build/client-r5i.jar`)

Branch `r5-int` from `main` (bdba226). Merge order A → C → B → D → E (`e6b5561`, `f0d2fe9`, `927a2ee`, `7f9bb9f`,
`a3c91a5`), then the review fixes (`51ead01` E, `81f4063` A, `645116f` B, `999348a` C, `0df2f4d` D, docs). Every
round-5 toggle stays default **off**; the frozen jar is `build/client-r5i.jar` (sha1 `0484a2be85652c4111c6a8dd0f12a69a1985aead`,
dylib sha1 `566203ee…`), bench `build/bench-r5i.jar` (sha1 `e02c071a…`; `tools/launch.py` always loads `build/bench.jar`,
so do not rebuild it while runs are queued).

**Merge resolutions.**
- CONTRACT §16: every branch appended its section after §16.10 / §16.11; kept all, in merge order (16.R5-A, C, B, D, E).
- A + C, `MetalCommandEncoder`: git merged A's guards and late `submit` with C's renaming / write stats cleanly; C's
  `writeStatsFrame()` call (after `commit()` in the old `submit`) was added to `submitLateThrottle` too. Renaming reads
  `completedSeq` after A's guard (the guard is the first statement of `writeToBuffer`), so a rename decision always sees
  frame N−1's completion as before. `MetalBuffer` / `MetalTransientMemory`: A's guards and C's renaming / hysteresis
  touch disjoint lines.
- A + B, `Bench.java`: both result blocks kept (`lateThrottle`, `config`, `frameTrace`). B's guard-trip source now
  resolves to A's `MetalFrameThrottle#guardTrips` (a never-reset total). B's probes were matched by call site inside
  `runTick` only; with A on, the tick bundle runs in `runTickBundleLateThrottle`, so the agent now probes its four sites
  too (bench-hook-test: 2 per tick site on the patched jar).
- C + E, `MetalBuffer`: C's `diagEnv` and E's `wrapReserved` kept side by side.

**Review findings (r5impl reviews) and what was done** (details in each section's "r5-int" notes):
- E major (upload budget shows an older mesh after a newer one): fixed (§16.R5-E, per-section ordering), proved on the
  real `SectionRenderDispatcher` (0 wrong in 450 trials, overtaking exercised 35–111× per variant; 48–111 wrong without
  the fix). E minors: idle-layer spares released after 600 passes; rig now also on persistently mapped staging, against
  a verbatim r5-base `UberGpuBuffer`, with the supersession case.
- A: input pumped again after a wait that blocked; GPU failures sticky and crashed as "Render Frame"; no callbacks inside
  `commit()` (guard before reading the value); lazy asynchronous fake GPU + sabotage canary; JSON null doc fixed.
- B: nothing new inside `frameTimes[0]`; SlowFrame JFR event off by default and allocation-free without a recording;
  analyzer partitions the interval (nested Metal time once, drain component, late-throttle wait), C2 summary without the
  bundle's Metal time, starvation from the GPU idle gap; native present attribution tested against decoder deltas.
- C: staged writes replayed instead of disabling renaming; COPY_TEXTURE_TO_BUFFER gate, recovery and allocation tests;
  the `hotPathAllocationFree` citation corrected.
- D: timeline-branch test, BLIT-only / early-return atlas configurations, tickEntities write-up corrected.
- Integration test fix: `MetalDeviceTest.transientRecycling` (vanilla free-after-unused-rotation rules) pins the
  hysteresis toggle off for its rig, so the suite also runs with `-Dmcmetal.transientHysteresis=true`.

**Headless matrix** (all under the GPU lock, on `client-r5i.jar`; toggles off, then A strict, C, B trace, D, E, and all
together): every suite passes — native abi / core 845 / decoder 5788 / frametrace 1350 / occlusion 9114 (`MTL_DEBUG_LAYER=1`,
also with `MCMTL_COPY_STATS=2`), metal-device 601, metal-e2e 2298, metal-smoke, terrain 157195, terrain-e2e 1581
(+ `--icb-parity` 8 poses), drawlist check 600 (+ 300 octree / far), terrain-shader 280, occlusion-e2e 180 frames,
i5-pipeline 16561, core-msl 198/198, test-i4-encoder 939 (932 with A on: the old-submit section skips), zoom 143,
bench-hook 611, late-throttle (off 40 / on 263 checks, digest `8b012e8124094a53` both), frame-trace, travel-hitch 132k
(also `MTL_DEBUG_LAYER=1`), animate-tick (+ verify), client-tick 828, atlas-anim (3 configurations), layer-flip and
terrain-ICB probes. Image evidence across configurations: the metal-e2e readback SHA-256 is `68765d48…` for off, A, C,
B, D, E and all on; the occlusion-e2e frame hashes are identical for off, A, C, B, E and all on.

**Not done here:** no game launch, bench, scene, MST or LAN run (the in-game A/Bs of 16.R5-A…E remain the lead's).

### 16.R5-AB Round-5 in-game A/B decisions (`results/ROUND5-AB.md`)

Measured on the frozen `client-r5i.jar` with the frozen bench `bench-r5h.jar`.
- **Runs:** 127 interleaved benchmark runs (static RD 12, travel RD 16; some frame-traced), plus parity (25 scenes ×3,
  the far-mob scenes with the zoom entity distance on and off, a full `fastAnimateTick.verify` sweep, and zoomseq ×6).
- **Overlays:** composited throughout (the notification and alerts were left on screen).

**Decided defaults** (applied in code in 16.R5-FIN / `client-r5.jar`; `client-r5i.jar` still defaults all to off):

| Toggle | Default | Decision |
|---|---|---|
| `mcmetal.lateThrottle` | on | 16.R5-A |
| `mcmetal.renameSmallWrites` | on | 16.R5-C |
| `mcmetal.transientHysteresis` | off | 16.R5-C |
| `mc.fastAnimateTick`, `mc.fastTextureAnimation` | on | 16.R5-D |
| `mc.terrain.pregrow` | on | 16.R5-E (weak, see there) |
| `mc.asyncEntityTextures` | off | 16.R5-E |
| `mc.terrain.uploadBudgetKB` | 0 | 16.R5-E |
| `bench.frameTrace` | off | measurement only |

The decided set (WIN) against everything off, interleaved:

| | median | avg | 1% low | pairs better on 1% low | 0.1% low |
|---|---|---|---|---|---|
| Static | +2.7% | +3.9% | +12.4% (433 vs 385) | 3 of 3 | +21.7% |
| Travel | +1.8% | +1.7% | +5.5% | 3 of 4 | +4.0% |

Parity with every candidate toggle on (a superset of WIN):
- Strict and perceptual parity are identical to the toggles-off run against vanilla Vulkan, with no perceptual-only
  differences.
- The far-mob entity checks pass with the zoom entity distance on and off; with it off the scene is 0.000% vs vanilla.
- Zoomseq: 128 lockstep frames are within the run-to-run floor (the lava animation phase).

When flipping the defaults, rerun the suites that pin default-off behaviour: I4's `submitThrottleAndErrors`, the off half
of `late-throttle-test`, `metal-device-test`, `travel-hitch-test` and `client-tick-test`.

### 16.R5-FIN Round-5 finalize: shipped defaults (`build/client-r5.jar`, `results/ROUND5.md`)

Frozen jar `build/client-r5.jar` (sha1 `573efa06f6a8891615dbea7f0a78b4f6c19e1e82`; packaged dylib `7f380cc4…`, native source
unchanged since `client-r5i.jar`, only its `-g` debug info differs per build); bench `build/bench-r5.jar` = `bench-r5h.jar`
(`efcc28e8…`, current `bench/src`).

**Before flipping, the follow-ups the check of 16.R5-AB asked for** (`client-r5i.jar`, plans `tools/ab/plans-r5f/p1-p3`):
- Pregrow, 8 new interleaved traced travel pairs (WIN vs WIN without pregrow): max frame 24.2 → 12.4 ms mean, lower in 7 of
  8; median +1.4%, 1% low +1.7%, 0.1% low −1.0% (noise). Free memory 42–57% in every run, so memory pressure does not explain
  the stall's return; the old full set (with hysteresis + async textures) was not cleaner than WIN. **Confirmed on.**
- Pregrow's own hitch: the heap-reserve thread's side-queue residency fill (6–45 ms in game, 3–5 ms in the headless probe)
  can delay the main queue: `r5f-p1-tr-WIN-1` has a 20.2 ms commit-to-GPU-start delay at 8.2 s with a normal 7 ms command
  buffer, at the moment of a 45 ms fill. In 16 traced pregrow runs: 10 have no frame over 11.5 ms, 5 still show the first-use
  stall (main-queue command buffer 20–37 ms), 1 the fill delay. Without pregrow: 8 of 8 show the stall (17–36 ms).
- Fast-tick pair inside the shipped set (WIN vs WIN without it, travel, 4 pairs): median +2.6% (3 of 4), 1% low +4.4% (4 of
  4). The travel median cost the check flagged does not appear in the shipped configuration. **Confirmed on.**
- Hysteresis: the old full set beat WIN on travel 0.1% low in 4 of 4 pairs (−6.8% for WIN), matching 16.R5-AB's WINH travel
  +5.2% (4 of 4); static it lost (−3.7% 1% low, 0 of 3). A trade, not a win: **stays off**, open item.
- Clean static rerun of WIN vs everything off (4+4, nothing else on the machine): 1% low +19.0% (4 of 4), 0.1% low +25.6%,
  avg +4.3%, median +1.2%. The contaminated +12.4% headline was not inflated.

**Defaults now in code** (unset = on; `false` or `0` = off; same parse as `TravelHitchToggles.flag`):

| Toggle | Field | Default |
|---|---|---|
| `mcmetal.lateThrottle` | `MetalFrameThrottle.ENABLED` (via `MetalFrameThrottle.defaultOn`) | **on** |
| `mcmetal.renameSmallWrites` | `MetalCommandEncoder.RENAME_SMALL_WRITES` | **on** |
| `mc.fastAnimateTick` | `ClientLevel.FAST_ANIMATE_TICK` | **on** |
| `mc.fastTextureAnimation` | `TextureAtlas.FAST_TEXTURE_ANIMATION` | **on** |
| `mc.terrain.pregrow` | `TravelHitchToggles.PREGROW`, `MetalHeapReserve.ENABLED` | **on** |
| `mcmetal.transientHysteresis`, `mc.asyncEntityTextures`, `mc.terrain.uploadBudgetKB` | | off / off / 0 |

Renaming was only ever measured together with the late throttle; `-Dmcmetal.lateThrottle=false` alone (renaming still on) is
an unmeasured combination (16.R5-AB 2.5). To return to the round-4 paths switch all five off.

**Tests.** `tools/late-throttle-test.sh` now passes `-Dmcmetal.lateThrottle=false` to its off run. New
`tools/headless-suites.sh <outdir>`: every headless suite, each under the GPU lock, plus the off switches where the flip
would leave an old path untested (metal-device, metal-e2e, test-i4-encoder with all round-5 off; client-tick with
`-Dmc.fastAnimateTick=false`). 32 of 32 pass; metal-e2e readback SHA-256 `68765d48…` with defaults and with everything off.
I4's `submitThrottleAndErrors` asserts the old path only with `-Dmcmetal.lateThrottle=false` (932 checks by default, 939
off); its late-throttle counterpart is `tools/late-throttle-test.sh`.

**Release verification on client-r5.jar** (details and tables in `results/ROUND5.md`):
- Parity: strict 21/22 vs vanilla Vulkan (the exception is the zoom-aware far mobs, by design), 25/25 vs the same jar with
  every round-5 toggle off; perceptual ok with a floor built only from toggles-off runs, except random snow layers on the
  water_surface sand (world state; also present in toggles-off and round-3 runs); zoomseq 0 frames differ from vanilla;
  `fastAnimateTick.verify` 0 mismatches (scene sweep + travel run with `FAST_ANIMATE_TICK_VERIFY: true` in the config block).
- LAN smoke: pass, r5↔r5, vanilla host → r5 guest, r5 host → vanilla guest.
- Scorecards vs client-r4 (4+4 ABBA each). Static, composited: median +1.4%, 1% low +13.3% (4 of 4), 0.1% low +20.1%; static,
  clean desktop (the alerts disappeared on their own mid-afternoon): 856 / 832 / 432 / 369 vs 844 / 800 / 378 / 315
  (median / avg / 1% low / 0.1% low), 1% low +14.3% (4 of 4). Travel: −3.1% median (0 of 4) in the composited 60 Hz session,
  +2.0% (r4 / r5z / r5off / r5 localization, alerts gone) and +2.6% (clean desktop) in the two later sessions (3 of 4 each);
  12 pairs: median +0.5%, 1% low +2.2%, 0.1% low +7.8% (10 of 12). Neither the zoom merge (r5z vs r4 +1.3%) nor the rest of
  r5-int with the round-5 paths off (r5off vs r5z −0.8%) moves the travel median beyond noise.
- Stage-1 bars (clean desktop, vs vanilla: established and same-session references): median 3.75–3.80x OpenGL and
  2.38–2.43x Vulkan, met; 1% low 432 vs 2x Vulkan 488–510 (same session 494 against the better of two vanilla runs), missed.
- 30-minute LAN soak: PASS, conclusive; no crash; live heap, Metal and native memory flat or falling.
- Tooling: `tools/ab/scorecard.py` (shipped-default arms validated through `-Dbench.configClasses`, vanilla arms in their own
  game dir), `tools/ab/stall_table.py`, `tools/ab/pairs.py`, `runner.py` `MEMLOG=1`, `scenes.sh` `SCENES_JAR`.

### 16.W1 HUD vignette no-op skip (stage 2 W1, `-Dmc.hud.vignetteSkipNoop`, default on)

Java only, HUD only (`client/src/net/minecraft/client/gui/Hud.java` = verbatim vanilla plus one block,
`HudVignetteSkip.java`). No native, ABI, shader, packet or gameplay change; nothing a server can observe. Status:
headless-verified; the in-game checks listed at the end are still open.

**What it does.** `Hud.extractVignette` (called first in `extractCameraOverlays` when `options.vignette()` is on) blits a
full-screen quad with `RenderPipelines.VIGNETTE` (GUI_TEXTURED_SNIPPET: `core/position_tex_color`, POSITION_TEX_COLOR
quads, no depth/stencil state; one RGBA8_UNORM target, write mask RGBA, blend `ZERO, ONE_MINUS_SRC_COLOR, ADD; ZERO, ONE,
ADD`). The fragment colour is `texture * vertexColor * ColorModulator` (alpha-0 texels discarded) and the vertex colour is
the packed ARGB `color` as UNORM8. When `color & 0xFFFFFF == 0` the source RGB is exactly 0, so every channel is written
back as `dst * 1 + 0` (alpha `dst * 1`): the draw changes no byte, on Metal, Vulkan and OpenGL alike (fixed-function
blend, no depth/stencil test or write; GL: depth test off and `depthMask(false)`; Vulkan: zero-filled depth state).
Outside the world-border warning `color = ARGB.colorFromFloat(1, b, b, b)`, `b = clamp(vignetteBrightness, 0, 1)`, so
RGB is 0 exactly when `floor(b * 255) == 0` (b < 1/255; largest skipped b is `0x3b808080`). `vignetteBrightness` starts
at 1 and decays 1 %/tick toward `1 - levelBrightness`, so in a lit place the skip starts 552 ticks (27.6 s) after joining.
Night, caves and the world-border warning keep the draw.

**Only the draw is skipped.** `extractVignette` mutates no `Hud` state (`vignetteBrightness` is updated in `Hud.tick`,
unchanged; the border reads are getters). Its one effect is `graphics.blit`: the texture lookup and a `BlitRenderState`
added to the frame's `GuiRenderState`. That element must stay: its full-screen bounds steer the GUI layering of later
elements (`lastElementBounds` and the node intersection search, also across strata and frames), the per-node sort and
draw batching. Dropping it changes the other elements' sorted draw list in about 28 % of HUD frames (test, contrast case).
`HudVignetteSkip.blitWithoutDraw` therefore repeats the blit chain (`blit` 11 args -> 13 args -> `innerBlit`) with the same
inputs into the same `GuiRenderState`, but wraps the record in `NoDrawElement` (same pipeline, texture setup, scissor and
bounds; `buildVertices` empty). `GuiRenderer` still opens a draw for it, which stays empty: `StagedVertexBuffer
.getExecuteInfo` returns null and nothing reaches the GPU. Spyglass/scope and the other camera overlays come after it and
are untouched; zoom does not touch the vignette.

**Guards** (each falls back to the vanilla blit):
- toggle off (`-Dmc.hud.vignetteSkipNoop=false` or `0`; read into the volatile `HudVignetteSkip.enabled`);
- border warning: `!(borderWarningStrength > 0)` is vanilla's own branch test (NaN takes the plain path, as in vanilla).
  The warning path never skips, even where its colour quantizes to RGB 0 (conservative);
- shader sources: every stage of VIGNETTE in the current `PipelineCache` (set by the last resource reload) must equal the
  jar's built-in source byte for byte (UTF-8 string compare, once per cache). A resource pack that overrides
  `core/position_tex_color` turns the skip off ("W1 vignette skip inactive: ... (resource pack)" logged once per
  reload); before the first reload there is no current cache and no skip;
- the private fields read through `MethodHandles` (`GuiGraphicsExtractor.guiRenderState`, `scissorStack.peek()`,
  `RenderSystem.currentPipelineCache`, `PipelineCache.shaderSource`) must resolve, else "W1 vignette skip disabled" is
  logged once and the skip never happens.

**Off path.** With the toggle off the method runs vanilla's instructions plus one `getstatic` / `ifeq`.

**Tests** (`tools/hud-vignette-test.sh`, under the GPU lock; `--no-gpu` skips the Metal part):
- quantization boundary ulp by ulp, specials, 4M random floats, the 552-tick decay; the border path never skips;
- VIGNETTE pipeline facts (blend, mask, format, no depth/stencil, shaders, no defines) and the shader guard (fragment or
  vertex override, missing shader, no cache);
- GUI differential through the real `GuiGraphicsExtractor` / `GuiRenderState` / `GuiRenderer` comparator: stand-in ==
  vanilla element minus vertices; structure, `lastElementBounds`, sorted order and batching identical;
- bytecode: `build/client.jar`'s Hud classes vs the verbatim vanilla `Hud.java` compiled by the same javac: every method
  identical except `extractVignette` = vanilla + the block, whose instructions are checked exactly: `isNoop` gets the
  slot of vanilla's own border test and the slot the vanilla blit takes its colour from (named `borderWarningStrength`
  and `color` in the LocalVariableTable), `blitWithoutDraw` gets the vanilla blit's argument instructions verbatim plus
  `this.minecraft`;
- `extractVignette` end to end: the patched `Hud` from the jar (Unsafe-built, real `WorldBorder` static and moving, real
  camera entity, NaN positions) with the toggle off / on / on with pack shaders, 3504 calls, against an independent
  oracle for the colour and the skip decision (641 skipped, 2624 drawn of which 54 border warnings with RGB 0, 239
  dropped by vanilla's own empty-bounds rule in every mode); same `GuiRenderState` structure, no `Hud` field changes;
- the guard against the game's own shader loading: `ClientPackSource`'s vanilla pack (its jar and asset-index layers, as
  `PackRepository.openAllSelected` hands them to the reload) through `ShaderManager.loadConfigs` is accepted, so the skip
  is active in game; folder packs overriding either stage or re-saving it with CRLF are rejected, byte-identical copies
  and unrelated shaders are accepted;
- Metal with API validation: VIGNETTE with colour RGB 0 over random RGBA8 content and D32 depth == no draw (60 draws);
  through the present-fusion BGRA8 drawable as well; RGB 1/255 and white change pixels (positive controls).
- Mutants of the block (compiled into a copy of the jar, `--no-gpu`) all fail the suite: `isNoop(.., color & 0xFF000000)`
  (the review's mutant, which skips every visible vignette and passed the earlier suite), `isNoop(0.0F, color)`,
  swapped texture width/height and x = 1 fail both the bytecode and the end-to-end sections; a constant `0xFF000000`
  colour in `blitWithoutDraw` (equivalent in game, vanilla's alpha is always 0xFF) fails the bytecode section.

**Still to do in game** (not run yet: no game launch while other agents benchmark): the X0.1 A/B (2+2 static with
`-Dmcmetal.skipPipelines=vignette` and `-Dbench.frameTrace=true`, GPU busy per 30° yaw bin after vb reaches 0), the
18-scene parity and zoom sequences with the toggle on vs off, and a Vulkan and an OpenGL smoke run. During the A/B, check
`latest.log` for "W1 vignette skip inactive" / "disabled" (either means the skip never ran). Results go to
`results/` and this section.

### 16.AB In-session A/B (`bench mode=abab`, runtime toggles; `Abab.java`, `AbabStats.java`, `tools/abab.py`)

**Why.** Round 5 needed 127 full launches for its toggle decisions (2.4 h even at today's 67 s per launch). In a
classic A/B each arm is a separate launch (JVM start, world load, 8 s settle, 15 s warmup, 30 s measured). Launches also
differ from each other: an interleaved pair moves by 3.1 % on the median, 2.5 % on avg FPS and 6.4 % on the 1 % low (SD,
round-5 static pairs). So a decision needs many pairs. `mode=abab` measures both arms in one launch, switching runtime
toggles at frame boundaries. Both arms then share one JIT state, one memory layout and one world, and the launch
overhead is paid once.
Measured results are in `results/ABAB.md`, usage in `bench/README.md`.

**Protocol** (details in the `Abab` class comment):
- SETTLING as in perf mode. WARMUP (`bench.warmup`) alternates the arms every `abab.warmupSwitch` s (2), so the JIT has
  profiled and compiled both code paths before the measurement starts.
- MEASURE has `abab.blocks` (12) blocks of `abab.blockSeconds` (= `bench.spin`, 10 s). Each block is one full camera turn
  from `bench.yaw`, so every block covers all view directions. Blocks 2i and 2i+1 form pair i. Block arms run in ABBA
  order (`abab.order=abab` gives strict alternation).
- The switch happens in `Bench.onFrame` (top of `Minecraft.renderFrame`, render thread). It sets every toggle field by
  reflection, so the next frame is the first of the new arm. The first `abab.discard` (30) frames after every switch
  point are recorded but not analysed: frames in flight, the switch itself, and the analysis that runs at block ends
  (at most 11 ms, in a discarded frame).
- `abab.segments=S` (default 24) alternates the arm S times inside each block, every 10/S s. The pair's second block runs
  the opposite arm in every segment, so each 15-degree sector still has one A and one B cell per pair. With an even S half
  of every pair's sectors run A first, so a linear drift cancels inside each pair; with S = 1 (one arm per spin) ABBA
  cancels it over two pairs only.
- Why 24 (results/ABAB.md 3): the machine wanders on a 10-30 s scale, which one arm per spin turns into pair-to-pair
  noise. Pair SD of avg FPS pooled over all spin runs: 1.97 % with S = 1 (6 launches), 1.04 % with S = 8 (8), 0.74 %
  with S = 24 (11); the two-frame median 1.80 / 0.89 / 0.78 %, the 1 % low 5.6 / 4.0 / 3.5 %. Interleaved arms read W1's
  per-sector tail lower than one arm per spin (S = 1: +6.3 / +6.8 %; S = 8: +4.1 to +5.8 %; S = 24: +1.9 to +4.7 %)
  while the means agree. S = 8 was tried as a compromise (4 W1 and 3 A/A launches): 15-40 % noisier than 24, also within
  the same A/A runs cut into both designs, and only a little closer to S = 1 on the tail. So the default is the least
  noisy design, and S = 1 stays the steady-state check for a toggle that only moves tails.
- Recorded per frame: interval, bin (1/24 of the block by start time = the 15-degree yaw sector), block, keep flag. On
  Metal it also records the GPU busy time and GPU end of the timeline values the frame committed. These are copied from
  the `MetalFrameTrace` native ring at every block end. The agent sets `-Dmcmetal.frameTrace=true` in this mode;
  `abab.gpu=false` turns it off.
- Statistics (`AbabStats`):
  - Per cell (block x bin): median, p99 ("sector p99"), mean and two-frame median ("median2") of the interval, plus
    median and p99 GPU busy time. Paired per-bin log ratios ln(B / A); the effect is their mean over cells (every view
    weighted equally).
  - Per pair: the p99 and p99.9 of all kept frames of each arm ("pairP99" = the 1 % low, "pairP999" = the 0.1 % low,
    the classic whole-run definition, dominated by the heavy sectors and by sporadic bursts of 20-30 slow frames), one
    log ratio per pair. The sector p99 is NOT the 1 % low: its mean over 24 views hides the bursts, which makes it 3-4
    times less noisy but a different quantity (W1: sector p99 +3.9 to +6.8 %, 1 % low +0.6 to +9.8 % per launch).
  - Decision interval: estimate -+ t(pairs - 1) x the cluster-bootstrap SE (the pair is the resampling unit: the cells
    of one pair share 20 s of machine state); equivalent to a paired t test on the pair means. t, p reported. The
    percentile and cell-level intervals are reference only (simulated coverage 85 % and 29-66 % with a pair effect; the
    decision interval 94-95 %).
  - Calls: the primary family is median2 and pairP99, the two metrics of docs/GOAL.md's stage bars (median, 1 % low).
    A call is a Holm-adjusted p < 0.05 over the two (`holmP`, `call`). avg FPS, the plain median, the sector p99, the
    0.1 % low and GPU busy are descriptive: six metrics at 95 % each gave at least one excluding 0 in 3 of 7 A/A runs
    (simulated: 22 %), Holm over the two primaries 4.6 %.
  - The intervals are within one launch. Tail effects varied between launches by more than that (W1 1 % low), so a tail
    claim needs two or more launches pooled with `tools/abab.py --combine` (per-launch estimates, inverse-variance
    pool, Cochran's Q, the DerSimonian-Laird random-effects interval).
- Sequential stop (`abab.stop=true`, `abab.stopOn` = median2, `abab.margin` 0.5 %, `abab.minPairs` 3):
  - It looks only after pair counts that are balanced against a linear drift (`AbabStats.lookPairs`): every pair from
    minPairs with an even S (3, 4, 5, 6), even counts with S = 1 (4, 6). A look after 3 or 5 pairs with one arm per spin
    carries a drift bias of d / 3 or d / 5 (d reached 0.86 % per block in abab-aa-1).
  - Each look works at alpha / looks. A primary stop metric is decided when its Holm-adjusted p x looks is below alpha
    (exactly the call the final analysis of those pairs reports), a secondary one when p x looks x (secondary stop
    metrics) is, or when its interval at alpha / looks is at most the margin wide (half-width). The run stops when every
    stop metric is decided.
  - With the stop on, every reported interval is a repeated confidence interval at alpha / looks, valid at whichever look
    ended the run (`level`; simulated coverage 96-99 %); `ci95` is the fixed-sample interval, which after a stop covers
    only 91-95 %.
  - Simulated with the measured noise (drift 0.86 % per block): false stops 1.0-1.6 % (the first rule, looks after every
    pair at alpha / 4 per metric: 2.7-3.3 %). Expected pairs of 6 with S = 24 for effects of 0 / 1 / 2 / 3 / 5 %: 5.9
    / 5.7 / 5.0 / 4.3 / 3.6; with S = 1: 6.0 / 5.9 / 5.8 / 5.5 / 4.8. A null or small effect always takes about the full
    2 minutes: the margin of 0.5 % cannot be reached in 6 pairs, so an A/A run never stops early by design.
  - The looks are computed and recorded with the stop off too (`wouldStopAfterPairs`).
- Heavy-view preset (`abab.preset=heavy`): the camera holds `abab.heavyYaw`, with 5 s blocks and time slices as bins.
  The default yaw, 142.5 degrees, is the middle of the heaviest sector in Default mode (GPU busy median 2.24 ms there,
  against 1.18 ms at 315-330 degrees).

**Runtime toggle contract** (what makes a field switchable):
1. The field is `public static` and not `final`. A static final is a JIT constant: the dead branch is compiled out, so
   the field cannot be switched, and such a toggle needs a full-launch A/B. Make it `volatile` when a non-render thread
   reads it.
2. The render thread reads it at a safe point: at least once per frame after `Bench.onFrame` (so one frame sees one
   value), or once per client tick or atlas tick. One field load per frame or per tick is the whole cost, which cannot be
   measured against a 1 ms frame.
3. Nothing derived from it outlives the frame, or what does converges within the discard window (30 frames). A choice
   baked into objects when they are created (pipelines, meshes, devices, allocators) is not switchable.
4. Both paths stay correct after any sequence of switches. A switch changes the image only if the toggle itself does.

**Switchable toggles.** The fields were checked in the code. Fields marked * were switched in game (results/ABAB.md).
| Field (`abab.toggles=fqcn#field=valueA|valueB`) | Read | Notes |
|---|---|---|
| `net.minecraft.client.gui.HudVignetteSkip#enabled` (W1) * | once per frame (`Hud.extractVignette`) | public static volatile since W1: no change needed |
| `net.minecraft.client.multiplayer.ClientLevel#FAST_ANIMATE_TICK` * | once per client tick | converted here from static final. The fast path's state (5x5 grid, ambient plan, batched-RNG field) is now built whatever the start value. `FAST_ANIMATE_TICK_BATCH_RNG` / `_VERIFY` no longer depend on it |
| `net.minecraft.client.renderer.texture.TextureAtlas#FAST_TEXTURE_ANIMATION` * | once per atlas tick | converted here from static final. Stateless between ticks |
| `com.mojang.renderpearl.backend.metal.MetalCommandEncoder#RENAME_SMALL_WRITES` * | per writeToBuffer call | already non-final ("tests flip it"). Render thread only, so one frame sees one value |
| `net.minecraft.client.renderer.chunk.TerrainToggles#fastDrawList`, `#sortCutoutSections`, `#sortSolidSections`, `#exactFrustum`, `#shortIndices` | once per frame (frame snapshots) | volatile since stage-2 S1-S6 |
| `TerrainToggles#faceBuckets` | per frame (draw side) | only the draw-side bucket selection switches. Meshes keep the buckets of the start value, so start with it on |
| `TerrainToggles#cloudCull`, `#cloudRunCopy` | per frame / per cloud filter | a switch triggers a cloud rebuild (CloudRenderer) |
| `net.minecraft.client.renderer.state.gui.GuiLayerIndex#enabled` (16.G1) * | per layering query / per GUI sort | volatile; both paths give the same answer, an index is built from the node's lists on first use, so it switches even mid-frame |
| `net.minecraft.client.renderer.texture.TexturePingPong#enabled` (16.R6-1) * | once per atlas tick, once per frame (lightmap) | volatile; the spare copy tracks what it misses while off, the lightmap returns to its first texture at the next update |
| `net.minecraft.client.renderer.LightmapCompute#enabled` (16.R7-1) * | once per lightmap update | volatile; either path writes the same bytes into the same copy (the kernel only for swapped updates whose copy has no uncommitted stream write) |
| `net.minecraft.client.renderer.texture.AtlasCompute#enabled` (16.R7-2) * | once per ping-pong atlas tick | volatile; kernel and render passes write the same texels into the spare; a render-pass tick defers the kernel to the next committed frame |
| `com.mojang.renderpearl.backend.metal.MetalTimeline#spinNs` (long, ns), `#spinMode` (16.R8-1) * | once per wait | volatile; only how the render thread waits changes |
| `com.mojang.renderpearl.backend.metal.TerrainShaderVariants#useVariant` (16.R8-2) * | per SET_PIPELINE | volatile; only with `-Dmcmetal.terrainVariant` (variant PSOs built at pipeline compile) |
| `net.minecraft.client.renderer.chunk.TerrainToggles#translucentFacingDraw` (16.R9-1) * | per TRANSLUCENT section-layer, draw-list build | volatile; both lists live in the same index allocation, so either is drawable in any frame |
| `com.mojang.renderpearl.backend.metal.MetalRenderPass#skipOn` (16.R9-B, diagnostics) | per SET_PIPELINE | volatile; only with `-Dmcmetal.skipPipelines` |
| `com.mojang.renderpearl.backend.metal.MetalDiag#flags` (int, 16.R9-B, diagnostics) | per submit (pushed to the decoder when changed) | volatile; 0 = shipped behaviour |
| `net.minecraft.client.renderer.chunk.TerrainClusters#enabled` (16.R12-A) * | once per frame (draw-list build) | volatile; both paths draw the same meshes; needs `mc.terrain.clusters` (default on) |
| `net.minecraft.client.renderer.chunk.TerrainToggles#quadIndexOrder` (int, 16.R12-B) * | once per frame (index buffer pick) | one cached index buffer per order |
| `com.mojang.renderpearl.backend.metal.TerrainVertexMsl#useHand` (16.R12-C) * | per SET_PIPELINE | only with `-Dmcmetal.terrainHandVertex=true` (PSOs built at compile) |
| `net.minecraft.client.renderer.chunk.TerrainGreedy#draw` (16.R12-G, prototype) * | once per frame (draw-list build) | only with `-Dmc.terrain.greedy=exact|aggressive` (greedy copies built at mesh time) |
| `bench.Abab#aaNoop` | never | A/A dummy |

**Toggles that still need a full-launch A/B:**
- JIT-folded static finals whose state is built at startup, device creation or world join:
  - `mcmetal.lateThrottle` (`MetalFrameThrottle.ENABLED`). The throttle registers with the device when the device is
    created, and the flag guards `MetalBuffer.map` and transient-memory paths that run many times per frame.
  - `mc.terrain.pregrow` (`TravelHitchToggles.PREGROW`, `MetalHeapReserve.ENABLED`): reserve thread and storages pre-sized
    at world join.
  - `mc.terrain.uploadBudgetKB`, and `mc.asyncEntityTextures` (prefetch at resource load).
  - `mc.zoom` / `mc.zoomEntityDistance`: read per entity, so the cost is not provably negligible.
  - `mcmetal.strictBindings`, `mcmetal.writeCombined` (buffer creation).
  - `mcmetal.skipPipelines`: diagnostic, read per setPipeline.
  - `mc.terrain.stats`, `mcmetal.frameTrace`: measurement.
- Captured at creation: `mcmetal.transientHysteresis` (`MetalTransientMemory` wires its allocator in the constructor).
- Fixed when meshes are built: `mc.terrain.compactVertices` (vertex heap alignment at startup),
  `mc.terrain.cutoutFrontToBack` / `solidFrontToBack` (affect only new meshes), and the `TerrainCompact` snap / lossy
  options.
- Fixed when pipelines are built: `mcmetal.terrainSpecialize` and `terrainRgss*` (`TerrainShaderVariants` rewrites the
  shader source when a pipeline is compiled).
- Native, read at device or surface creation: `MCMTL_PRESENT_FUSION`, `MCMTL_MAILBOX`, `MCMTL_TERRAIN_ICB`,
  `MCMTL_OCCLUSION`, `MTL_*`.
- Travel-only effects (heap growth, uploads, entity textures) do not occur in a static spin anyway.

**Results** (Default mode 3024x1964 for every run, frozen jars; 39 launches; tables in `results/ABAB.md`, which also
lists what the review of the first version found):
- **A/A, 12 launches** (1, 8 and 24 segments, heavy view; dummy toggle): 1 Holm call (+0.43 % two-frame median, Holm p
  0.042), 1 of 24 primary intervals excludes 0, 6 of all 96. Mean t² over the 6 launches with 24 segments: 3.0
  (two-frame median) and 4.0 (avg FPS) against 1.7, so those intervals may be up to about 1.4x too narrow: replicate a
  call on an effect under about 1 %.
- **Design**: pair SD of avg FPS 1.97 / 1.04 / 0.74 % with 1 / 8 / 24 segments (pooled over 6 / 8 / 11 launches; the
  same order within A/A launches cut into each design). W1's sector p99 reads +6.3 / +6.8 % with one arm per spin and
  +1.9 to +5.8 % interleaved; the means do not move. Default 24.
- **Sensitivity: W1** (`HudVignetteSkip#enabled=false|true`, merged unchanged), 10 full launches: two-frame median
  +4.9 % [+4.7, +5.1], avg FPS +5.0 %, GPU busy median -5.3 %, all launches agreeing (Cochran's Q p 0.54 / 0.18); the
  1 % low +0.3 to +9.8 % per launch (Q p 0.001), random-effects +2.9 % [+1.4, +4.5]. Classic interleaved 3+3: median
  +5.1 % [-0.9, +11.4], avg +5.4 % [-0.9, +12.1], 1 % low -2.5 % [-15, +12] (6.7 min, nothing decided; one pair in
  another presentation state).
- **Time**: classic 67 s per launch, abab 151-158 s for 12 blocks. One launch with 24 segments matches 57 / 42 / 15
  classic pairs on the two-frame median / avg FPS / 1 % low: 49x / 36x / 13x (about 20x on avg FPS if the A/A spread is
  taken at face value). One arm per spin: 11x / 8x / 7x (the first version's "14-58x" mixed designs).
- **Sequential stop**: live W1 run stopped after 3 pairs (97 s wall), two-frame median +4.6 % [+2.5, +6.6] at alpha / 4;
  offline replays stop every W1 launch after 3-4 pairs and 1 of 12 A/A launches (the one with the false call).
- **Converted toggles in game**: the fast tick pair (`FAST_ANIMATE_TICK` + `FAST_TEXTURE_ANIMATION`, off -> on), 3
  launches: 1 % low -0.7 % [-2.1, +0.8], central metrics 0 ± 0.5 %; the sector p99 +2.25 % [+1.3, +3.2] replicates (the
  first version reported that as "1 % low +2.3 %"). A 4-block run with `-Dmc.fastAnimateTick.verify=true` switched the
  pair 29 times and logged no mismatch. Renaming (`RENAME_SMALL_WRITES`): no call; 1 % low +2.1 % [-0.8, +5.1].
- **GPU busy is a span, not work.** It runs from the first command buffer's start to the last one's end, summed over the
  frame's timeline values, so overlap and queueing count. It fell 5.3 % with W1, which only removes work, but rose 3.2 %
  with renaming, which lets frames overlap more. Read it with the FPS metrics whenever a toggle changes scheduling.

**Tests.** `tools/abab-stats-test.sh` (no game): `tools/AbabStatsTest.java` (105 checks) covers distributions, cell
summaries, pair tails, the look schedule, Holm, p-values, segment pairing, simulated calibration, the sequential rule
under a 0.86 % per block drift (false stops 1.0-1.6 %; the first rule 2.7-3.3 %), the repeated intervals' coverage after
a stop (96-99 %), expected pairs per effect size, the family-wise error (six metrics at 95 %: 22 %; Holm over the two
primaries: 4.6 %) and toggle parsing / reflection. `tools/abab.py --selftest` re-implements the statistics in Python and
checks them against the Java ones on real result JSONs (cells, pair tails, effects, p). The suites that cover the
converted toggles pass on the new jar: client-tick (on and off), animate-tick, atlas-anim, plus bench-hook, frame-trace
and hud-vignette.

### 16.W7 Instant UI transitions (stage 2 W7, `-Dmc.ui.instantTransitions`, default on; user-approved 2026-10-05)

Java only, client UI only: patched copies of `LoadingOverlay`, `TitleScreen` and `DownloadedPackSource` (verbatim vanilla
26.3 first, commit 02e23af, then the patch), one line in `Minecraft.doWorldLoad`, and the toggle holder
`net.minecraft.client.gui.screens.UiTransitions` (volatile `instant`, read once per overlay, per title screen and per world
load; recorded by the bench config block), plus a read-only accessor `LevelExtractor.isGeometryInvalidationPending()`. No
native or shader change; no packet changes for a remote server. One local gameplay effect: in a newly created singleplayer
world the player can act (and take damage) 500 ms sooner, see `doWorldLoad` below. Not exact by design: it removes
animations (the user approved this, docs/GOAL.md "User decisions", afternoon); it is time saved waiting, never added to the
compute wins. `-Dmc.ui.instantTransitions=false` (or `0`) restores every vanilla wait.

**What it removes.**
- `LoadingOverlay.tick`: once the completion callback has run (same tick as vanilla) and the screen below was re-initialized
  (same call as vanilla), the overlay removes itself instead of fading out for 2.0 s (1 s logo / bar fade, 1 s background
  fade; input blocked and singleplayer paused meanwhile). Only if it is still the current overlay: the callback may have set
  a new one (a deferred reload) or removed it (the re-entrant tick in `doWorldLoad`'s wait loop).
  **In a world** (F3+T, pack / language change with a level loaded) the callback's `LevelExtractor.allChanged()` makes the
  next extract discard every compiled section (`LevelRenderer.invalidateCompiledGeometry`), which vanilla hides under the
  opaque first second of its fade-out (and keeps singleplayer paused). There the overlay stays, opaque (the render pins the
  fade-out at its opaque start) and pausing as vanilla's, until `UiTransitions.terrainShown` has held for
  `IN_WORLD_STABLE_TICKS` = 3 consecutive client ticks — section rebuild done (`isGeometryInvalidationPending()` false), camera
  section compiled and faded in (vanilla's `LevelLoadTracker` test; skipped outside the build height), compile queue empty —
  or until `IN_WORLD_HOLD_CAP_MS` = 2000 ms after the callback (vanilla's whole fade-out, which shows the world from 1 s on),
  or until the level is gone; then it goes at once. No half-loaded world is shown that vanilla would hide. (Review finding
  F1, 2026-10-05: the first version removed the overlay in the callback's tick in a world too.)
- `LoadingOverlay.isReadyToFadeOut`: no 1.0 s minimum hold on later reloads (F3+T, resource pack / language changes),
  **except while a server or world resource-pack reload is pending** (`DownloadedPackSource.serverPackReloadPending()`, a
  read-only accessor of `pendingReload`): that callback sends `ServerboundResourcePackPacket` (SUCCESSFULLY_LOADED) and its
  timing stays vanilla's. The fade-out after it is still skipped: it starts after the callback, no packet depends on it.
- `LoadingOverlay.extractRenderState`: the 0.5 s fade-in animation of later reloads (opaque at once, as vanilla after 0.5 s).
- `TitleScreen(fading=true)` (the first title screen after startup) ignores `fading`: widgets shown at once instead of
  invisible for 1 s and faded in over the next (they were clickable throughout).
- `Minecraft.doWorldLoad`: the `LevelLoadTracker` close delay comes from `UiTransitions.levelLoadCloseDelayMs(newWorld)`:
  vanilla 500 ms for a newly created singleplayer world and 0 otherwise; 0 for both with the toggle on. Not purely cosmetic
  (review finding F2): `LevelLoadTracker.isLevelReady` gates `ClientPacketListener.notifyPlayerLoaded()`, which sends
  `ServerboundPlayerLoadedPacket` and sets the client's `clientLoaded`; until then the integrated server keeps the player
  invulnerable (`ServerPlayer.isInvulnerableTo`: `!connection.hasClientLoaded()`) and ignores its movement and actions, and
  `LocalPlayer` holds them back. So in a new singleplayer world the player can move, act and take damage 500 ms sooner —
  the same timing vanilla already uses when that world is opened again. Remote / LAN servers never see a change.

**Multiplayer.** Nothing a remote server sees changes: remote and LAN joins, respawns and dimension changes already use a
0 ms `LevelLoadTracker` delay in vanilla (so `ServerboundPlayerLoadedPacket` timing is untouched; it is sent from
`ClientPacketListener.tick`, which an overlay pauses only for an unpublished singleplayer world, `Minecraft.pause`), server
resource-pack reloads keep the hold, and the overlay
and title fades gate no packet. On a new singleplayer world the integrated server gets `ServerboundPlayerLoadedPacket`
500 ms sooner (local only). A LAN guest joining with `--quickPlayMultiplayer` (Prism's "join server on launch") shows the
world as soon as it is loaded instead of after the startup overlay's fade.

**Tests.** `tools/ui-transitions-test.sh` (headless, no GPU; in `tools/headless-suites.sh` as a CPU suite): runs
`tools/UiTransitionsTest.java` with the toggle unset (on) and with `=false`, each flipping it at runtime: completion
callback timing identical in both modes (startup reload: first tick after completion; later reloads: the 1 s hold skipped
only without a pending server pack, kept to the millisecond with one, pack report after it), overlay gone right after the
callback (on) / kept for the fade (off), screen re-initialized once while the overlay was present, a replacing overlay kept,
the re-entrant tick, a failed reload, the in-world terrain hold (held while the terrain is missing, released after 3
consecutive shown ticks with a break restarting the count, released at the 2 s cap and when the level goes, vanilla never
asks; the real predicate is false while the section rebuild is pending), `TitleScreen` fading, `serverPackReloadPending`,
`levelLoadCloseDelayMs`, and the bytecode wiring (`doWorldLoad` calls the helper and has no 500 ms constant; `LoadingOverlay`
and `TitleScreen` read the toggle; `LoadingOverlay.tick` reads `Minecraft.level`; the predicate asks
`isGeometryInvalidationPending`). 58 checks per run, 0 failures (after the F1 fix; 44 before).

**Measured in game** (results/W7-UI-TRANSITIONS.md; Metal, `build/client-w7.jar` sha1 c9ec2c15, Default display mode in every
run, interleaved ABBA, `tools/startup-bench.py` and `tools/lan-smoke.sh`; medians of the paired per-round differences):

| path | saved | rounds |
|---|---|---|
| launch -> title screen (overlay gone, widgets clickable) | 1.99 s | 6/6 existing world, 6/6 new world (1.89 s) |
| launch -> title widgets fully shown | 3.00 s | 6/6 (new-world runs: 2.90 s) |
| title -> world ready, existing world | none measurable (+0.06 s, t -1.3) | 6 |
| title -> world ready, new world (create) | 0.52 s (level load -0.51 s, 6/6) | 5/6 |
| launch -> world ready, existing world (title path) | 2.92 s | 6/6 |
| launch -> world ready, new world | 3.56 s | 6/6 |
| resource reload in game (F3+T): request -> overlay gone, first version (no terrain hold, superseded) | 2.32 / 2.48 s (completion callback 0.30 / 0.47 s sooner) | 6/6 |
| resource reload in game (F3+T), with the terrain hold: request -> overlay gone (overlay goes 106–141 ms after the terrain is back) | 1.95 / 2.12 s (results/W7-UI-TRANSITIONS.md 6) | 2/2 |
| LAN host: launch -> world published (new world) | 2.37 s | 4/4 |
| LAN guest joining at launch (`--quickPlayMultiplayer`): reload done -> in the world | 0.56 s (connect -> level unchanged) | 4/4 |

Parity with the toggle on and off (scenes vs the pinned `-vs` references with the floor, zoomseq): identical verdicts, the
only FAIL the by-design `spyglass_far_entities`; 16 of 16 LAN smoke runs passed.

**Bench support.** `bench.titleSettle=true` (wait for the title widgets before opening the world; stamp `titleWidgets`),
`bench.reloads=N` (timed F3+T reloads after world ready), stamps `worldOpen` / phase `openToReady` (Startup.java);
`tools/lan-smoke.sh` `LAN_ARGS` / `LAN_LANE=timing` and the LAN agent's `"timing"` block. The W7 branch's own startup driver
(`StartupTimeline.java`, `tools/startup-ab.sh`, `tools/startup_ab.py`) was folded into these and removed.

### 16.G1 F3 overlay cost: exact GUI layering index and GUI sort comparator (round 6, `-Dmc.gui.layerIndex`, default on)

Java only, GUI only: patched copies of `GuiRenderState` and `GuiRenderer` (verbatim vanilla 26.3 plus the blocks below) and
the new `GuiLayerIndex` / `GuiElementOrder`. No native, shader, packet or gameplay change; nothing a server can observe.
Exact by construction (same layer tree, same sorted element lists, so the same vertices and draws).

**Why our client paid ~3x vanilla Vulkan's F3 cost.** Fixed view yaw 200, static options, Default mode, F3 overlay + FPS chart
(`--bench-prop f3=full`, `bench/src/bench/Photo.java`): r6a 885 -> 666 FPS (+0.37 ms/frame), vanilla Vulkan 417 -> 398
(+0.11 ms). The frame trace (`f3d-*`) shows the overlay is CPU work on the render thread: extract 0.03 -> 0.58 ms, GUI render
0.06 -> 0.17 ms, render-thread CPU per frame 0.19 -> 0.88 ms; the GPU draws are not the cost (skipping the `gui,text,...`
pipelines with F3 on changes nothing: 733 vs 717 FPS). The CPU time lands after the late throttle point (16.R5-A: wait for
frame N-1, then record frame N+1 while frame N runs), so while the render thread records, the GPU holds only one frame: the
inter-frame overlap that is worth about a third of a frame (results/GPU-HEAVY.md) is lost and the frame interval grows by
about the CPU time (bimodal 0.9 / 2.1 ms intervals; commit lead before the previous frame's GPU end p10 -178 us vs +516 us
without F3). Vanilla Vulkan is GPU-bound at 2.4 ms per frame, so the same Java work hides under its GPU time. JFR (render
thread, F3 on): 62 % of the Java samples in `GuiRenderState.hasIntersection` / `navigateToAboveHighestElementWithIntersectingBounds`:
every chart bar (FPS chart 240 one-pixel `fill`s, TPS chart 240 x 4 stacked) is not inside the previous element's bounds, so
it walks the last stratum's nodes and scans each node's element list linearly; the bars' node holds all earlier bars, so the
chart is quadratic (~0.3 ms per frame); plus 15 % in the sort comparator (`Comparator.comparing/thenComparing/nullsFirst`,
boxed keys).

**What.** `GuiRenderState.nodeIntersects` answers vanilla's per-node question ("does any element/item/text/PiP of this node
intersect these bounds?") with the node's `GuiLayerIndex` once the node holds >= 8 intersect-tested states: a column hull
(per GUI x column the smallest top / largest bottom of rectangles with width and height >= 0; a zero-width one in its column)
as an exact negative filter, then a lazily built 16-px grid, then vanilla's `ScreenRectangle.intersects` on the candidates;
rectangles the hull cannot hold (negative size, out of range, > 512 columns) are tested one by one. Proofs in the class
comment (a true answer always reaches a candidate; every hit is vanilla's own test). Indexes are pooled and released in
`reset()`. `GuiRenderer.prepare` sorts with `GuiElementOrder` (same keys, precedence and null handling; `Integer.compare` has
`compareTo`'s sign and TimSort only reads the sign, so the sorted lists are identical) and `addElementToMesh` skips the record
`equals` for an identical `TextureSetup` reference. Toggle `GuiLayerIndex.enabled` (volatile, read per query and per sort):
runtime-switchable (abab), even mid-frame.

**Tests.** `tools/gui-layer-index-test.sh` (`tools/GuiLayerIndexTest.java`, CPU, in `tools/headless-suites.sh` on and off):
index vs linear scan on random sets (bars, text lines, icons, huge, negative / zero width and height, outside the grid and the
column range) 19,013 queries; `GuiRenderState` differential over 1,500 random frames (940k ops: adds, strata, `up`, chart runs
with 0-pixel bars, F3-like lines, no-bounds elements) with the index off / on / toggled mid-frame and after `reset()` (pool
reuse): identical layer trees; F3 frame layering 157 -> 29 us; `GuiElementOrder` vs the jar's `ELEMENT_SORT_COMPARATOR` on
80,000 random pairs (null / shared / equal scissors, real GUI pipelines, null and proxy texture setups): same signs, 400 lists
sorted identically. 102,416 checks, 0 failures.

**Measured** (Default mode, fixed view yaw 200, static options, frozen jars):
- abab, F3 on, heavy preset held at yaw 200 (`abab-g1-f3`, first version of the index): two-frame median +13.8 % [+13.1,
  +14.5], 1 % low +13.7 % [+12.0, +15.5], avg +13.1 % (Holm calls on both primaries).
- Classic interleaved, 3 rounds x {r6a, RC code} x {F3 off, on} (`f3c-*`), means: r6a 872 -> 632 median (F3 cost
  +0.435 ms/frame; avg +0.364 ms; 1 % low 452 -> 341), RC 896 -> 731 (cost +0.253 ms; avg +0.190 ms; 1 % low 465 -> 389).
  F3-on FPS, RC vs r6a: median +15.6 %, avg +15.0 %, 1 % low +14.2 %. The F3 cost fell by 42 % but stays above vanilla
  Vulkan's +0.11 ms: **target missed**. Vanilla Vulkan could not be re-measured in this session: from ~02:30 the panel showed
  no frames (glass trace `shown=0`) and MoltenVK's presents were paced to the refresh (`f3c-vk-*`: 119-121 FPS), so the
  same-day reference (417 -> 398) stands.
- What remains (JFR on the RC, F3 on): vanilla's own GUI work for ~1,000 F3 elements (fill render states, text preparation and
  glyph emission, vertex building, sorting), ~0.25 ms of render-thread CPU per frame, exposed by the same mechanism. Going
  much lower needs cheaper vanilla text/fill paths or a third frame in flight (adds latency; not done).

### 16.R6-1 Hazard-free per-tick texture updates (round 6 item 1, `-Dmc.texture.pingPong`, default on)

Java only: patched `TextureAtlas` (ping-pong path), patched `Lightmap` (second texture), `GameRenderer.extract` /
`levelLightmap()` (one call each), toggle holder `TexturePingPong`. Exact: every frame samples a complete copy holding the
state vanilla's single texture holds at that frame.

**Atlas.** A tick draws D(T) (the sprites `needsToDraw()` now) plus `spareLag` (sprites drawn into the current copy since the
spare was last brought up to date) into the spare copy with each sprite's current state (`fastDrawToAtlas`, grouped as R5-D),
then swaps `texture` / `textureView` / `mipViews`; `spareLag = D(T)`. Ticks with nothing to draw swap nothing; in-place ticks
(toggle off) add D(T) to `spareLag`, so the toggle switches at any tick. The spare is created and filled by a full
copyTextureToTexture of every mip level on the first ping-pong tick after `createTexture`, and released with the atlas
(+22 MB for the 2048x2048x5 blocks atlas). **Lightmap.** Two 16x16 textures; `notePendingUpdate` (extract, before the GUI)
arms the swap, the update (a full-screen opaque triangle: every texel rewritten) goes to the other copy, `getTextureView()`
names the latest; `getTextureViewForThisFrame()` (the F3 lightmap chart, extracted before the update) names the copy this
frame's update writes, as vanilla's texture holds the new texels when that blit executes.

**Tests.** `tools/atlas-anim-test.sh` (GPU, Metal validation): in place vs ping-pong per-tick SHA-256 of every mip level of the
copy frames sample, for the block atlas, slow BLIT-only animations, the toggle flipped every 3 ticks and at random ticks: 1765
/ 765 / 1165 / 1165 / 1165 level digests identical, two copies alternate; lightmap: 160 frames with random updates and
parameters, identical digests, the predicted view equals the view after the update in every frame. The R5-D checks run with
ping-pong off.

**Measured** (abab, `TexturePingPong#enabled=false|true`): heavy preset x2 pooled: two-frame median +0.40 % [+0.21, +0.59],
avg +0.39 %, 1 % low +0.89 % [-0.16, +1.96]; spin x2 pooled: median +0.19 % [-0.23, +0.60], 1 % low +0.76 % [-0.55, +2.10].
Small. **The plan's premise did not hold:** diagnostics (`diag-*`, frame trace, yaw 200) show the frames after a tick keep
their +0.8-0.9 ms GPU own time with the animation passes aimed at a 256x256 dummy target, with their draws skipped, with no
atlas animation at all, and with neither animation nor lightmap update (cadence p99 2.08-2.16 ms in every variant vs 2.09
without changes). So the tick-frame tail is not the atlas / lightmap write-after-read; its cause is still open (next suspect:
GPU work the client tick itself causes, e.g. section or entity uploads). Shipped on (exact, +0.4 % on the heavy view).
**Correction (round 7, 16.R7-1):** the per-frame rows of these same runs show the tail gone without both updates
(`diag-none2`: +0.045 ms) and +0.81 ms with the lightmap update alone; cadence p99 hid it. The cause is the lightmap's
vertex-stage read behind its render pass plus the atlas passes' own load/store cost, fixed by 16.R7-1 / 16.R7-2.

### 16.R6-2 Present pacing to the panel (round 6 item 2): not shipped

Diagnostic only (`MCMTL_MAILBOX_PACE_US`, a minimum spacing between registered presents, phase-free; reverted, not in the RC).
Static spin, frame trace + `MCMTL_GLASS_TRACE` on every arm, 2 interleaved rounds (`pace-*`): median base 970 / 987, 6.5 ms
spacing 981 / 1003, 8.0 ms spacing 979 / 963; avg 965 / 994, 970 / 979, 957 / 940; 1 % low 450 / 467, 451 / 451, 427 / 426.
No measurable gain; the 8 ms arm lost 1 % low. In this session the client registered only ~120 presents/s already
(`presentedFps` 120.0, presented share 12-14 % in every run), not the 215-260/s of results/BASELINE-DEFAULT.md, and the glass
trace showed `shown=0` (the panel displayed nothing from ~02:30), so the on-glass check (~120 new frames/s, no added latency)
could not be made. A phase-free spacing also adds latency by construction (the newest finished frame is held back); a
latency-neutral version needs the panel's vsync phase (CVDisplayLink) and a prediction of GPU completion. Not shipped;
revisit with the display on.

### 16.R7-1 Tick-frame tail, root cause; lightmap update as a compute kernel (round 7, `-Dmc.lightmap.compute`, default on)

**Root cause of the tick-frame tail** (results/ROUND7.md 1). The frame after every client tick cost +0.9-1.0 ms GPU own
time at the heavy view (yaw 142.5) and +0.7-0.9 ms in the spin. 16.R6-1 concluded "not the texture-animation or lightmap
hazard" from cadence p99 alone; the per-frame rows of the same diagnostics say otherwise: with neither the atlas animation
nor the lightmap update (`diag-none2`) the frame after a tick costs +0.045 ms (gone); with only the lightmap update
(`diag-anim-none`) +0.81 ms. Round-7 bisection (frame trace, yaw 142.5, own GPU time of the frame whose command buffer holds
the tick's passes, minus plain frames): base +0.91 / +1.13 ms; lightmap update aimed at a scratch texture nobody samples
+0.47 / +0.54 ms; atlas animation passes aimed at a never-sampled full-size dummy atlas: unchanged (+0.43-0.63 ms) - so the
two halves have different causes:
1. **Lightmap (~0.45 ms): a vertex-stage dependency.** Vanilla samples the lightmap in the vertex stage (`terrain.vsh`,
   `block`, `entity`, `item`, `particle`, `text`, `leash`: `vertexColor = Color * sample_lightmap(Sampler2, UV2)`). The update
   is a render pass; the Apple GPU runs render passes' fragment phases in submission order, so its fragment work queues
   behind the previous frame's fragment work, and the world pass's vertex work, which reads the lightmap, waits for it: the
   frame loses its vertex/fragment overlap with the previous frame. Ping-pong (16.R6-1) cannot help: it removes a
   write-after-read that was never the cost.
2. **Atlas animation (~0.5 ms): the passes' own cost.** One render pass per mip level of every atlas with a changed sprite
   (blocks 2048x1024x5, particles, gui; 6.5 passes per tick frame), each loading and storing the whole level on the in-order
   fragment phase. Aimed at a dummy target it costs the same; blit copies of the same rectangles instead of passes cost
   more (+1.27 ms: ~250 small copies serialize).
Not a cause: UBO / buffer uploads, chunk uploads, transient blocks, fences or command-buffer count (tick frames had the same
1 command buffer, 0 blocks created, the same draws apart from the animation's 250).

**Fix (lightmap).** `Lightmap.render`: an update that goes to the other copy (the ping-pong swap armed: the target is the
copy no frame in flight samples, nothing earlier in this frame reads it) runs `mcmtl_ext_lightmap_compute`
(native/src/mcmetal_tickfx.m via `MetalTickFx`): lightmap.fsh's expressions in the same order as a compute kernel (16x16,
one dispatch, fast math, MSL 3.2 like every game shader) on a side command buffer of the device's main queue, committed at
once from the render thread. That is after the previous frame's command buffer and before this frame's, so Metal's hazard
tracking (tracked textures, same queue) orders every later read after it; a compute dispatch is not ordered behind the
previous frame's fragment phase, so it finishes at once and the world pass's vertex work overlaps again. Guards: both copies
are created ShaderWrite (`MetalFormats.USAGE_EXT_SHADER_WRITE`, a backend-private usage bit; `MetalTickFx.createWritable`),
the kernel compiled at construction, and a copy is written by the kernel only once the stream seq that last wrote it
(creation clear, a render-pass update) has been committed (`streamWriteSeq`), so the side command buffer can never overtake
an uncommitted stream write to its target. Otherwise (toggle off, in-place update, not Metal, extension symbol missing) the
vanilla render pass, unchanged. Not in the frozen ABI: optional `mcmtl_ext_*` exports resolved by name.

**Exactness.** `tools/lightmap-compute-test.sh` (`tools/LightmapComputeTest.java`, real Metal device, Metal debug layer): for
4664 parameter sets (64 edge sets, 600 game-like: block factor 1.4 +- flicker, overworld / nether / end ambient colours,
night vision, darkness, boss darkening; 4000 uniform random over [0, 2] incl. values the game never produces) the kernel's
1024 bytes equal the render pass's (4543 distinct lightmaps); a 300-frame sequence mixing kernel, render pass and in-place
updates (runtime toggles flipped at random) digests like the render-pass-only sequence; no device messages. Mutation: a
changed constant in the kernel (0.9 -> 0.8999) fails it. `tools/atlas-anim-test.sh` ping-pong configs also run it (lightmap
digests in place vs kernel). In headless-suites.

**Measured:** see 16.R7-2 (the two kernels were screened together).

### 16.R7-2 Atlas animation as one compute dispatch (round 7, `-Dmc.texture.animCompute`, default on)

Patched `TextureAtlas` (`animateCompute`, `buildComputeFrames`), `SpriteContents.AnimationState` (accessors), toggle holder
`AtlasCompute`, native `mcmtl_ext_atlas_animate` / `mcmtl_ext_atlas_prepare`.

**What.** A ping-pong atlas tick (16.R6-1) writes its changed sprites (D(T) plus `spareLag`, as before) into the spare with ONE
compute dispatch on a side command buffer instead of one render pass per mip level. Per sprite and level the kernel writes
the padded slot (x >> L, y >> L, (w + 2p) >> L, (h + 2p) >> L), texel j = clamp(j - (p >> L), 0, (w >> L) - 1) of the frame
(blit) or `mix(current, next, float(progress) / 1000)` (interpolated; progress = the draw's first vertex >> 3, as
animate_sprite.vsh), the texel the passes' NEAREST clamp-to-edge sample hits: exact when w, h and p are multiples of 2^L
for every level drawn (checked per atlas at upload; else the render passes). The frame textures of an atlas are packed once
at upload (stream copies of every level) into one "animation frames" texture whose frame origins are multiples of
2^maxMipLevel, so one texture serves every op (a dispatch per op cost ~0.4 ms of serialized dispatches: measured). Both atlas
copies are ShaderWrite (measured free for terrain sampling: plain-frame GPU time 1252 / 1296 vs 1312 / 1276 us). Same commit
guard as 16.R7-1 (`streamWriteSeq`: upload, frames texture, the spare's full copy, render-pass ticks).

**Exactness.** `tools/atlas-anim-test.sh`: every ping-pong config now runs the kernel (it fails if no tick did) and must match
the in-place render passes digest for digest on every mip level: block atlas with interpolated sprites 320 ticks (1765 level
digests), slow / BLIT-only, ping-pong flipped every 3 ticks and at random, the kernel toggle flipped at random (`pp-kflip`),
and the r6b path with both kernels off (`pp-render`). Mutation: progress / 1001 fails it.

**Measured** (yaw 142.5, frame trace, own GPU time of the tick's frame minus plain frames; `e8-*`): kernels off +0.93 / +1.00 ms,
on +0.24 / +0.24 ms (sum over the tick frame and its two neighbours +0.64 -> +0.15 ms). abab, both toggles together,
`LightmapCompute#enabled=false|true;AtlasCompute#enabled=false|true`, 2 launches each pooled (`tools/abab.py --combine`):
heavy preset 1 % low **+6.11 % [+4.77, +7.47]**, avg +1.44 %, two-frame median +0.07 % (ns), 0.1 % low +15.7 %, GPU busy p99
-7.4 %; spin 1 % low **+3.34 % [+0.93, +5.82]** (random effects; launches +4.8 / +2.3), avg +0.67 %, median -0.04 % (ns).
Holm calls on the 1 % low in all four launches.

### 16.R7-M Round-7 measurements that did not ship

- **Present cost (ROUND6-PLAN #3).** Presented (fused) frames are 12-16 % of frames (~120/s) and cost +0-90 us more GPU own
  time than unpresented ones at yaw 142.5 (`e8-*`, `e9-*`): at most ~0.6 % of the average, nothing in the tail. Not built.
- **Cutout depth pre-pass (#4).** Cutout terrain drawn depth-only (colour write mask 0, `MCMTL_DIAG_NOCOLOR`, diagnostic,
  reverted) costs as much as the full cutout pass (unpresented own 1268 / 1282 vs 1218 / 1264 us; skipping cutout: 959 us):
  the cost is vertex work and the alpha test's texture fetch, which a pre-pass repeats. A pre-pass would add a second pass
  of the same cost. Rejected.
- **Opaque cutout quads as SOLID (#5).** Vanilla 26.3 already does it: `BakedQuad.MaterialInfo.of` picks the layer per quad
  from the sprite region's transparency (`ChunkSectionLayer.byTransparency`), so opaque quads of cutout blocks are meshed
  SOLID. Nothing to build.
- **64-quad clusters (#6) / translucent back faces (#7).** Not built this round: the heavy views' slowest frames are
  fragment-bound (fragment 89-91 % busy) and the vertex work these remove is ~0.02-0.07 ms of vertex-channel time; both are
  large changes (vertex pulling; a conservative re-sort rule per point-of-view bucket). Deferred with the stage-4 D3 path.
- **Render-thread QoS.** The render thread already runs at USER_INTERACTIVE (0x21, logged); forcing DEFAULT vs INTERACTIVE
  in abab: 1 % low +1.1 % (ns). Reverted.
- **Three frames in flight (late throttle armed on N-2).** abab spin: two-frame median +2.31 % (call), avg +2.5 %, but 1 % low
  -1.8 % (ns) and 0.1 % low -12.7 %, plus a frame of latency. Reverted (diagnostic only).
- **What the static tail is now** (spin, `r7e-ft-spin-1`): of the slowest 1 % of frame intervals, 14 of 311 are at or after
  a tick; the rest are heavy-view frames (yaw 75-150) in an alternating long/short interval pattern (2.2 / 0.9 ms) whose
  preceding frame ran 2.1 ms of GPU own time, 42 % of them without overlap (GPU idle before it: the CPU submitted late,
  frame CPU 0.6 ms vs 0.29 typical).

### 16.R8-1 Busy-poll frame waits (round 8, `-Dmcmetal.spinWaitUs`, default 2000; `-Dmcmetal.spinWaitMode`, default 1 = wfe)

**Root cause of the alternating 2.2 / 0.9 ms heavy-view interval** (results/ROUND8.md 1). With the late throttle (16.R5-A),
frame N+1 is recorded only after frame N-1 completed on the GPU, so the GPU can overlap N+1 with N only if N+1 is committed
before N finishes: the commit lag L = commit(N+1) - completion(N-1) must stay below N's remaining GPU time (~0.86 ms at yaw
130-145). In GPU-bound heavy views the render thread blocks on the `MTLSharedEvent` for ~80 % of every frame; the scheduler
then runs it slower (same draw count, CPU time per frame 0.16 ms in one lap of the spin and 0.57 ms in another at the same
yaw: `r7e-ft-spin-1`), L grows to 0.7-0.8 ms, N+1 starts on an idle GPU and runs its whole serialized 2.0-2.1 ms, and the
frame after it overlaps again and completes 0.86 ms later: the 2.2 / 0.9 ms pairs. No GPU pass, present, drawable or decoder
flush serializes anything (fused frames 12 %, drawable wait 0, encoders 2, native submit 0.07-0.2 ms); the late throttle is
only where the wait sits.

**Fix.** `MetalTimeline.await` first busy-polls (`mcmtl_ext_timeline_spin`, native/src/mcmetal_common.m: optional extension
export, frozen ABI unchanged): status-page completed value or the event's `signaledValue`, device loss, `wfe` (mode 1) or
`yield` (mode 0) between polls, at most `spinNs` (clock checked every 4 polls); on success it records the value as awaited
exactly like the blocking wait, on budget exhaustion it falls through to the unchanged blocking `mcmtl_timeline_wait` (same
timeout and error semantics). Every await (frame throttle, guard trips, fences) takes this path. The thread stays runnable,
so its frames are recorded at full speed and committed in time. No command, stream byte, timeline value or image changes;
nothing a server can observe. Cost: one core stays busy (in `wfe`, low power) for the part of each frame the GPU is ahead;
GPU-bound frames longer than 2 ms block after the budget as before.

**Measured.** Frame trace yaw 142.5 (poll mode yield, 3 ms): render-thread pre-submit 0.092 -> 0.054 ms, native submit
0.089 -> 0.070 ms, L 0.26 -> 0.16 ms (p50). Spin with r8 (`r8-ft-spin-1`): GPU idle before 6 of 33087 frames (r7: 559 of
31014), intervals >= 1.5 ms 0.1 % (r7 3.5 %), CPU p99 1.33 ms (r7 2.01). abab `MetalTimeline#spinNs=0|2000000` / `|3000000`:

| launch | design | two-frame median | 1 % low | 0.1 % low |
|---|---|---|---|---|
| abab-spin-heavy-1 (yield, 3 ms) | heavy, 24 seg | +2.73 % | +2.87 % | +1.6 % (ns) |
| abab-wfe-heavy-1 (wfe, 3 ms) | heavy, 24 seg | +3.21 % | +10.04 % | +16.9 % |
| abab-spin-spin-1 (yield, 3 ms) | spin, 24 seg | +4.24 % | +14.28 % | +16.3 % |
| abab-wfe-spin-1 (wfe, 3 ms) | spin, 24 seg | +5.87 % | +16.53 % | +16.5 % |
| abab-wfe2-spin-2 (wfe, 2 ms) | spin, 24 seg | +5.87 % | +16.72 % | +14.8 % |
| abab-wfe2-spin-seg1 (wfe, 2 ms) | spin, one arm per spin (steady state) | +6.92 % | **+38.81 %** | +47.8 % |

All calls (Holm). The interleaved design under-reads it (the scheduler's placement lags a 0.4 s switch); the steady-state
launch and the classic RC (results/RC-R8.md: static 1 % low 509 -> 768, +51 %) agree. Tests: `tools/metal-device-test.sh`
(both poll modes, a budget that runs out, spin 0; default values), every suite of headless-suites.

| Toggle | Default | Effect |
|---|---|---|
| `mcmetal.spinWaitUs` / `MetalTimeline#spinNs` (abab, ns) | 2000 us | busy-poll budget per wait; 0 = r7's blocking wait |
| `mcmetal.spinWaitMode` / `MetalTimeline#spinMode` (abab) | 1 | 1 = `wfe`, 0 = `yield` |

### 16.R8-2 Terrain fragment cost: per-variant A/B and what is exact (round 8, diagnostics; nothing changes the image)

**Infrastructure (kept, off by default).** `-Dmcmetal.terrainVariant=<modes>` builds a third PSO per specialized terrain
pipeline (optionally only those whose name contains `-Dmcmetal.terrainVariant.pipelines`); `TerrainShaderVariants#useVariant`
(abab-switchable, read in `MetalRenderPipeline.handle()`) selects it instead of the nearest PSO, never with RGSS and never in
a frame whose terrain sampler is anisotropic (`GameRenderer` reports the option next to UseRgss). Modes: diagnostics `level0`,
`nofog`, `nodiscard`, `flat`; exact candidates `maglevel0` (sample level 0 instead of the gradients where both screen-space
gradients are below half a texel: lambda <= -1, so the hardware's LOD estimate is at or below 0 by a full level of margin and
both forms give the level-0 bilinear sample with LINEAR/LINEAR, anisotropy 1) and `earlydiscard` (alpha test right after the
texel x vertex-colour product: the chunk-fade mix leaves alpha unchanged). `-Dmcmetal.dumpTerrainMsl=<dir>` writes the
translator's MSL and every variant. `tools/TerrainShaderTest.java` checks both candidates (and both together) byte-identical
with the unchanged pipelines in colour and depth, both UseRgss values, and a canary (`level0` everywhere must differ).

**Measured** (abab heavy preset, `useVariant=false|true`, all terrain pipelines, results/ROUND8.md 2): `level0` +0.55 % median
(ns): the gradient sample is not a cost; `earlydiscard` +0.14 % (ns); `flat` (no atlas sample) +16.5 % median / -15.6 % GPU
busy; `nofog` (no fog, varyings dropped) +12.1 % / -11.5 %. So the fragment cost is the atlas fetch and the fog stage, not the
alpha test or the LOD path. Neither of those two has an exact cheaper form:
- **Fog.** The overworld's environmental fog runs from 0 to 1024 blocks (`visual/fog_start_distance` 0, end 1024), so every
  terrain fragment has a non-zero fog value; a per-draw fog-free shader (built: every vertex closer than both fog starts ->
  fog value 0) never engages in the overworld (abab `TerrainFogFree#enabled`: +-0.1 %, i.e. no draw qualified) and was removed.
  Dropping the two distance varyings is itself not exact: it changes how the compiler interpolates the remaining varyings
  (1-9 of 1.6 M pixels one level off in the headless test, including alpha).
- **Atlas fetch.** `ShaderWrite` usage on the atlases (R7-2) costs nothing measurable (GPU cadence p50 1.13-1.19 ms with and
  without, 3 + 3 runs); depth stored at the end of the frame vs DontCare: no difference (3 + 3 runs, diagnostic only).
Not built: half precision (no provably identical 8-bit result), alpha-to-coverage (not exact), memoryless depth (no
measurable store cost).

### 16.R9-1 Translucent facing: back-facing TRANSLUCENT buckets left out of the sorted list (round 9, `-Dmc.terrain.translucentFacing`, default on)

Java only (`TranslucentFacing` (new), `SectionRenderDispatcher.addSectionBuffersToUberBuffer`, `TerrainDrawList`
(`H_TF_KEPT` / `H_TF_MASK` in the free hot slots 30 / 31, `setTranslucentFacing`), `TerrainMeshInfo.quadSlots`,
`TerrainToggles`). No native, record, shader or ABI change.

**Why it is exact.** Every translucent terrain pipeline (`translucent_terrain[_multidraw]`, the OIT terrain sets, wireframe)
culls back faces, so a back-facing quad produces no fragment and no depth write. S1's facing buckets cannot be used for
TRANSLUCENT because its per-quad sorted order must stay vanilla's. At upload (compile or resort) a TRANSLUCENT sorted index
buffer becomes `[vanilla's sorted list, N quads][the same list without the quads of facing buckets E, K quads]`: the first
part is vanilla's bytes, the second keeps the relative order of every quad it keeps. E = the non-empty facing buckets that
`TerrainMeshInfo.visibleMask` excludes for the dispatcher's camera position at margin 1.0 block. K and E are published in
the allocation's upload callback, in the same upload pass as its offset (`UberGpuBuffer.uploadEntry`: copy, map, listener,
callback), so a draw never pairs one upload's offset with another's K. Per frame, `TerrainDrawList` draws the K list
(firstIndex + N*6) only if `visibleMask(eye of this frame, TerrainRuns.EPS) & E == 0` - S1's own test, eye of the bobbed
exact frustum - i.e. this frame's eye is behind the extreme plane of every dropped bucket by more than S1's margin, so every
dropped quad (classified exactly by `classifyQuad`) is back-facing. Otherwise it draws the full list. Both choices draw
vanilla's front-facing quads in vanilla's order. Index memory: + K quads per translucent section (at most 2x).

**Tests.** `tools/terrain-test.sh` (`TerrainTest.translucentFacing`): 400 random translucent meshes (axis faces, reversed
faces, sloped and cross quads), random sorted permutations, SHORT and INT indices, random eyes: first part byte-identical,
second part = order-preserving filter, mask names facing buckets only, and for 20 eyes per mesh near the upload eye every
dropped quad is strictly back-facing whenever the draw-time test accepts; refusals (count mismatch, reordered metadata);
engagement counts. In game: scenes parity with the toggle on and off (results/ROUND9.md).

**Measured** (abab `TerrainToggles#translucentFacingDraw=false|true`, two-frame median, Holm calls): heavy preset (yaw 142.5,
25k back-facing translucent quads per frame) +0.59 % [+0.27, +0.91]; median view (232.5) +0.22 % [+0.13, +0.32]; spin
+0.29 % [+0.17, +0.41]. Small: a back-facing primitive is rejected before binning, so it costs only its vertex shading.

| Toggle | Default | Effect |
|---|---|---|
| `mc.terrain.translucentFacing` | true | build: extended index buffers for uploads after start (static) |
| `TerrainToggles#translucentFacingDraw` (abab) | = the above | draw the short list when valid; false = always the full (vanilla) list |

### 16.R9-B Round-9 cost budget instruments (diagnostics, off; nothing changes the shipped image)

- `MetalRenderPass#skipOn` (abab) gates `-Dmcmetal.skipPipelines`; `*` matches every pipeline (the empty frame).
- `MetalDiag#flags` -> `mcmtl_ext_set_diag` (optional export, frozen ABI unchanged), decoder `g_mcmtl_diag`: bit 1 colour
  store of a frame that will not be presented (mailbox `DEC_FUSE_SKIP`, end of call, no later access) is DontCare; bit 2
  end-of-call depth stores DontCare; bit 4 in-encoder depth-clear draws skipped (changes the image).
- `-Dmcmetal.terrainVariant` modes added: `const` (fragment returns a constant: no varying read, no sample, no discard;
  refused for shaders without `fragColor`, e.g. OIT transmittance), vertex-stage modes `vsnolight` (no lightmap sample),
  `vsconstfog` (fog distances 0); vertex modes build the same switchable variant PSO with the variant vertex function.
- Tools: `tools/ab/abab_ms.py` (arms as frame time), `tools/ab/mst-static.sh`, `tools/ab/own_gpu.py` (own GPU time by
  present kind), plans in `tools/ab/plans-r9/`. Results and the budget: results/ROUND9.md.


### 16.FAB Fabric mod compatibility (stage-2 prerequisite; docs/GOAL.md "Mod compatibility", results/FABRIC.md)

**Method merge (build, `tools/MethodMerge.java`, `tools/build-client.sh`; `MC_METHOD_MERGE=0` = old full copies).** Our
patched vanilla classes are full source copies made from decompiled vanilla source (`src/vanilla`). Compiled as is, every
method of such a class was the decompiler's reconstruction (inverted branches, merged returns, extra locals such as
`var10001`, renumbered lambdas), so Fabric mixins (RETURN counts, ordinals, locals, MixinExtras expressions, lambda names)
could resolve differently even in methods we never changed. The build now compiles the decompiled vanilla source of the
same classes (D, with the compile fixes our copies carry: `MethodMerge.D_FIXES`) and compares D with ours method by method
(normalized instructions, lambda bodies compared recursively, lambda numbering ignored): equal = untouched by our source,
so the shipped class gets **Mojang's original method bytes**; different or new = ours. Lambdas follow the methods using
them (ours renamed `$mc` on a name clash). Removed vanilla methods are restored from vanilla as dead code where that cannot
change dispatch (static, private, constructors, or instance methods no supertype declares; never when the superclass
changed, never Object methods). Fields and class attributes are ours. Build-time checks: every kept vanilla method's
references to patched classes resolve (else that method is ours), every merged class passes the ClassFile API verifier,
anonymous classes keep their enclosing method. Report: `build/method-merge.txt`. Result on r10: 109 changed class files,
~1390 vanilla methods byte-identical to Mojang's, ~220 ours (only the methods a patch changed, plus new ones).

**Minimal hook points.** Where a patch had changed a vanilla signature that mods name, the signature is vanilla again:
`LevelExtractor.extractVisibleEntities(Camera, Frustum, DeltaTracker, LevelRenderState)` reads the zoom frustum from
`Zoom.extraRangeFrustum()` (set just before the call, as before) instead of taking it as a fifth parameter.

**Superseded rendering mods (`net.minecraft.client.compat.ModCompat`).** Sodium, Iris, Distant Horizons and Voxy rewrite
what our renderer replaces. `tools/launch.py --fabric` passes `-Dfabric.debug.disableModIds=sodium,iris,distanthorizons,voxy`
(Fabric then never loads them: no mixins, no entrypoints); `Minecraft.onGameLoadFinished` wraps the first-screen step with
`ModCompat.wrapInitialScreens`: if any of them is in the mods folder, an `AlertScreen` names each (version, what replaces
it) before the title screen (a toast when quick play goes straight into a world) and the log has one `[mod-compat]` line
per mod; if one was loaded anyway (launch without the property) the notice says so and how to disable it. Without Fabric:
one failed class lookup at load end, nothing else.

**Launching (`tools/launch.py --fabric [profile] --mods JAR|DIR ...`).** Knot (`KnotClient`) with our jar as the game jar
(`-Dfabric.gameJarPath`), Fabric meta's launcher profile and libraries (`mc/fabric/`), `<game dir>/mods` synced to exactly
the `--mods` jars under the lock; java agents (bench, LAN) are also put on the class path so Knot loads their hook classes
next to the game's. The bench JSON's `launch.fabric` records loader and mods (file + sha1).

**Tests.** `tools/fabric-compat-test.sh` (headless suite `fabric-compat`): ModCompat on fake mods folders; every method that
differs from vanilla in the jar is listed as ours by the merge; Hud / MouseHandler / TitleScreen / LevelExtractor methods
the test mods hit are vanilla bytecode; `extractVisibleEntities` has one, vanilla, descriptor; static mixin audit
(`tools/MixinAudit.java`) of the test mods: no injection that resolves in vanilla fails in ours. In game: results/FABRIC.md.

### 16.R11 Perceptually identical terrain fog / colour math (round 11, `-Dmcmetal.terrainVariant=vfog,hvary` + `.use=true`, default on)

First change shipped under docs/GOAL.md's perceptual bar instead of bit-exactness (user-approved, docs/QUEUE.md item 2).
Java only (`TerrainShaderVariants.pairedVariant` / `rgssVariant`, `MetalRenderPipeline.create` / `prepare` / `handle`,
`MslTranslator.StageResult.withUniform`); no native, record, GLSL or ABI change, no vanilla method signature touched.

**What.** Every non-OIT terrain pipeline (solid, cutout, translucent, wireframe; plain / multidraw / compact) gets two more
PSOs (nearest and RGSS) whose vertex and fragment MSL are rewritten together (the stage interface changes):
- `vfog`: the vertex stage evaluates vanilla's fog value F = max(linear(sph, envStart, envEnd), linear(cyl, rdStart, rdEnd))
  (same branches as `linear_fog_value`) times FogColor.a from its own two distances, and passes one `float2`
  `(g, h) = (v (1 - F Fa), mix(Fa, 1, v))` (v: vanilla's per-vertex chunk visibility) instead of the three varyings
  sphericalVertexDistance / cylindricalVertexDistance / chunkVisibility. The fragment computes `c = texel * vertexColor;
  c.a *= h; [alpha test]; out = (mix(FogColor.rgb, c.rgb, g), c.a)`, which is vanilla's chunk-fade mix followed by its fog
  mix rearranged (rgb = Fc + (1 - F Fa) v (c - Fc), a = c.a mix(Fa, 1, v)). The vertex function binds the Fog UBO
  (`prepare` adds Fog's uniform bit / buffer 2 to the vertex stage of these pipelines; unused by the exact PSOs).
- `hvary`: vertexColor and (g, h) as `half4` / `half2` varyings (texCoord0 stays float). Varyings per vertex: 9 floats
  -> 2 floats + 6 halves (52 -> 36 bytes with the position).
- Differences from vanilla, by construction: the fog ramp's kinks (start, end, the crossing of the two ramps) are
  interpolated across a triangle instead of per pixel (all terrain triangles are <= 1 block); v (1 - F) is interpolated as a
  product (v is 1 except in fading sections); half precision of colour / fog factor (relative 2^-11). Headless worst case
  (`tools/TerrainShaderTest.java`, fog ramps 30-90 / 50-80 crossing 1-block quads, fade 0.6): at most 1 level on any
  channel, 0.08 % (vfog) / 2 % (vfog+hvary) of pixels.

**Selection.** `MetalRenderPipeline.handle()`: RGSS frames -> RGSS variant while `TerrainShaderVariants#useVariant`; other
frames -> the nearest variant while `useVariant` (also with anisotropic filtering, since paired modes do not touch sampling;
the diagnostic fragment modes of 16.R8-2 keep the old rule). A variant that fails to compile is released and logged
(`VARIANT_FAILURES`), the pipeline keeps its exact PSOs; OIT pipelines and resource-pack shaders never get one.

| Toggle | Default | Effect |
|---|---|---|
| `mcmetal.terrainVariant` | `vfog,hvary` (`DEFAULT_MODES`) | modes built at pipeline compile; empty = no variant; a diagnostic list (16.R8-2 / R9-B) replaces it |
| `mcmetal.terrainVariant.use` / abab `TerrainShaderVariants#useVariant` | true | false = the exact PSOs (r10 image) |

**Tests.** `tools/terrain-shader-test.sh`: exact configurations still byte-equal with the variant off; `vfog` and
`vfog,hvary` built for 3 pipelines nearest + RGSS, every image within 1 level (bound: <= 0.01 % pixels > 4 levels), the
same PSOs switched off byte-identical; coverage: the default variant on all 14 non-OIT terrain pipelines, 0 failures.
In game: results/ROUND11.md (abab, PerceptualDiff vs vanilla and vs r10 on all scenes + cave scenes, zoomseq temporal check
`PerceptualDiff --temporal`), RC results/RC-R11.md.

### 16.R12-A GPU-driven terrain cluster culling (round 12 (a), `-Dmc.terrain.clusters`, default **on**; abab `TerrainClusters#enabled`)

Java `chunk/TerrainClusters` (mesh-time clusters, per-frame lists, GPU ring), `TerrainMeshInfo.clusters`, `TerrainDrawList`
(cluster groups), `LevelRenderer.prepareChunkRendersIndirectFast` (dispatch), `ChunkSectionsToRender.DrawIndirect` (draws),
`RenderPipelines.{SOLID,CUTOUT}_TERRAIN_CLUSTER`, `backend/metal/MetalClusterCull` (FFM), `TerrainVertexMsl#clusterVertex`,
`MetalRenderPipeline.prepare` (vertex stage substitution, required vertex buffers 27/28); native `mcmetal_clusters.m`
(optional extension symbols `mcmtl_ext_cluster_prepare` / `mcmtl_ext_cluster_cull`; mcmetal.h, stream format and ABI 2
unchanged). No vanilla method signature changed (`GpuMultiDrawIndexedIndirect` keeps its 5- and 6-argument constructors).

**What.** At mesh time every S1-bucketed SOLID / CUTOUT layer (28-byte vertices) is cut into clusters of <= 64 quads, one
facing bucket at a time (3 ints each: quad start, count, slot; section-relative AABB in 1/16 block rounded outward). Per
frame, a layer that would take S1's run path appends the clusters of its CPU-visible buckets to the cluster list of its
(layer, vertex heap) instead of emitting records (CUTOUT lists are re-sorted front-to-back by section like the records).
`TerrainClusters.Frame.dispatch` writes the section table ((origin - camera), (eye - origin), from doubles) and 16-byte items
into a CPU-mapped ring slot (4 slots) and commits one side command buffer (like R7's tick kernels): per cluster the exact
S2 frustum test on the widened box (margin 1/16 block) and S1's facing test on the cluster's own extreme plane (margin
`TerrainRuns.EPS`); a single-threadgroup prefix sum (order-preserving, no atomics); the compacted quad list (`cluster << 6 |
quad`) and the draw's arguments. Each (layer, heap) is then drawn with ONE `drawIndexedIndirect` (GPU-written count, no ICB)
through the cluster pipeline: hand-written vertex-pulling MSL (vertex buffer slots 0 heap, 1 section infos, 2 quad list,
3 cluster items) and a 32-bit quad index buffer (4o + {0 1 2 2 3 0}), after the layer's remaining record groups. On
Vulkan / OpenGL, without the extension, or when a resource pack replaces the terrain vertex shader
(`TerrainVertexMsl.clusterPipelinesReady()`), the frame keeps the records.

**Exactness.** Culling removes only quads that produce no fragment (whole box outside one frustum half-space; every triangle
back-facing), and the surviving quads keep the per-record path's order. Headless (`tools/TerrainShaderTest.java`, `r12
clusters`): depth byte-identical, CUTOUT colour byte-identical; SOLID colour differs at 6-34 of 1.6 M pixels by <= 3
levels. Measured: per-section cluster draws with only the frustum test are byte-identical; one draw instead of per-section
draws, or back-facing quads left out of the stream, each move the Apple GPU's varying interpolation at a few opaque (HSR)
pixels. The same GPU behaviour applies to S1's shipped bucket culling. Perceptual checks in game: results/ROUND12.md.

**Measured** (abab, `TerrainClusters#enabled=false|true`, two-frame median / pair-p99 1 % low): spin +5.24 % / +12.6 %, again
+5.49 % / +10.5 %; heavy +5.28 % / +11.5 %; median view (232.5) +6.79 % / +13.6 %; GPU busy -4.6 to -6.0 %. GPU tests off
(`-Dmc.terrain.clusters.cullMask=0`: one draw per heap only) +4.42 % / +8.7 %: most of the gain is the draw consolidation and
pulled vertices, the culling (~4 % of submitted quads) adds ~1 %.

| Toggle | Default | Effect |
|---|---|---|
| `mc.terrain.clusters` | true | startup: clusters at mesh time, cluster pipelines; false = r11 path, nothing built |
| `mc.terrain.clusters.use` / abab `TerrainClusters#enabled` | true | per frame: cluster draw or per-record draw of the same meshes |
| `mc.terrain.clusters.cullMask` | 3 | diagnostics: bit 0 frustum, bit 1 facing test on the GPU |
| `mc.terrain.clusters.stats=N` | 0 | log clusters / quads submitted / kept every N s |

### 16.R12-B Quad triangle order (round 12 (b), `TerrainToggles#quadIndexOrder`, default 0 = vanilla; not shipped)

Pattern 1 = `0 1 2 0 2 3` (second triangle rotated to start at vertex 0; byte-identical in the headless test) for the S1b
16-bit quad index buffer and the cluster index buffer. Pattern `2 3 0 0 1 2` (triangles swapped) changed 2-4 blended pixels
and was dropped. abab records path: spin +0.17 %, +0.23 % (calls), heavy +0.10 %, median view 0.00 % (ns); on the cluster draw
+0.13 % / +0.04 % (ns). Apple's vertex reuse is already complete for 4-vertex quads; kept as a switch, default vanilla.

### 16.R12-C Hand-written terrain vertex function (round 12 (c), `-Dmcmetal.terrainHandVertex`, default off; used by 16.R12-A)

`TerrainVertexMsl#pullVertex` replaces the translated multidraw terrain vertex function (vertex pulling from buffers 30 / 29,
`vertex_id` / `instance_id` include base vertex / instance) with byte-identical output, also under the round-11 paired
variant (the rewrite applies to its text). Fast math lets the compiler reassociate the position math differently once the
inputs are loads instead of attribute fetches (measured: ~50 % of depth pixels differed); the file therefore sets
`#pragma clang fp reassociate(off)` and spells out the association the translated function compiles to (`pos` in source
order, `m = P * MV`, `clip = fma(m[2], z, fma(m[1], y, fma(m[0], x, m[3])))`), found by testing candidate forms against the
translated function. abab `TerrainVertexMsl#useHand`: spin 0.00 %, heavy +0.03 % (ns): the vertex ALU is not the cost
(round 9). Not shipped by itself; the cluster vertex function (16.R12-A) is the same body behind a cluster fetch.

### 16.R12-G Greedy meshing prototype (round 12 Part B, `-Dmc.terrain.greedy=exact|aggressive`, default off, NOT exact, not shipped)

`chunk/TerrainGreedy`, `SectionCompiler` (greedy copy after the vanilla quads, `Results.greedyVertices`),
`TerrainMeshInfo.greedy`, `TerrainDrawList` (greedy groups, abab `TerrainGreedy#draw`), `ChunkSectionsToRender.formatVariant`,
`RenderPipelines.{SOLID,CUTOUT}_TERRAIN_GREEDY_MULTIDRAW`, shaders `terrain.vsh` / `terrain.fsh` under `TERRAIN_GREEDY`
(the non-greedy preprocessed GLSL is vanilla's; terrain.fsh is now served from our jar's vanilla pack, the resource-pack gate
is unchanged), `TextureAtlas.createTexture` (atlas size). Full-block faces (one cell, one aligned 16x16 atlas tile, no other
quad of the same facing / plane on the cell) with the same tile, orientation and vertex order are merged into rectangles;
`exact` only where every corner carries the same colour and light, `aggressive` across them (corner values of the corner
faces). The UV runs on unwrapped; the tile (+1) rides in UV2's high bytes; the fragment stage wraps it with `mod` and samples
with the unwrapped derivatives. Not exact even in exact mode (T-junctions, fog interpolated over larger triangles, wrap
seams). Merge rate (bench world): exact 5.0 % fewer quads, aggressive 37.8 %. abab `TerrainGreedy#draw` (records path):
exact spin +0.28 %, heavy -1.11 %; aggressive spin +18.4 % / +22.4 % 1 % low, heavy +18.5 % / +24.2 %. Screenshot pairs and
metrics: share/greedy (MAIN), results/GREEDY-PAIRS.md. The greedy copies go through the per-record path: with the cluster
path on (default since r12) they are drawn only with `-Dmc.terrain.clusters=false` (the cluster branch takes those layers
first). Waits for the user's decision on the look.

### 16.S2-W0B Chunk-pipeline counters and the join metric (stage 2 round 1, `-Dmc.chunkStats`, default off; diagnostics)

Counting only; nothing changes what the game does. `client/.../chunk/ChunkStats.java` (patched client) holds atomic
counters read by the bench (`bench/src/bench/ChunkStatsHook.java`, by reflection: absent on vanilla or older jars, the
rest still works); the hooks are `if (ChunkStats.ENABLED)` blocks (a static final the JIT folds away when off) in
`ClientLevel.setSectionDirtyWithNeighbors` / `setSectionRangeDirty` / `sendBlockUpdated`, `LevelExtractor.setBlockDirty` /
`setBlocksDirty` / `setSectionDirty` / the region creation in the section update loop, `LevelRenderer`'s compile scheduling,
and `SectionRenderDispatcher` (compile time, empty results, upload bytes, cancellations, resorts, identical recompiles).
- **Dirty sources.** Every `setSectionDirty` on a tracked section is attributed to the source active at the time: light
  packet, chunk load (a chunk's light enabled), block change, render-equivalent block update (16.S2-W11 verify mode), other
  (the client light engine's own propagation, biome packets, ...). A section accumulates source bits until its compile is
  scheduled; a compile that cancels an unfinished one inherits its bits (otherwise the cancelled task's sources are lost and
  the replacing compile is misattributed).
- **Identical recompiles.** Each finished compile hashes its mesh per part (per layer: uploaded vertex bytes + index bytes,
  TRANSLUCENT index size only because its order follows the camera; the VisibilitySet as 36 bits (it has no hashCode);
  block entities by identity; per layer also light-only / non-light vertex hashes for diagnosis) and compares with the newest
  earlier compile of that section (by task creation time: tasks finish out of order). Counted per source bit.
- **Render-thread timers** (`bench/.../ChunkProbe.java`, inserted by `BenchAgent` when `bench.chunkStats=true` or
  `mode=join`; render thread only, the integrated server's light engine is the same class): chunk / light / forget / block
  packets, `pollLightUpdates`, `runLightUpdates`, `replaceWithPacketData`, `LayerLightSectionStorage.swapSectionMap`.
  `tools/BenchHookTest.java` section 9 checks each insertion (once, verifies, other methods byte-identical) on vanilla and patched.
- **`mode=join`** (`tools/bench-join.sh`, pinned pre-generated world `run/pregen/join-s4242-26.3-rd32`, RD 32 default or
  `RD=12`): from the first frame with a level, hold the view; done when the loaded chunk count, the compile queue and the
  visible sections waiting for a compile have been quiet for 1 s. `allBuiltS` = join to all built; series every 250 ms.
  The server loads the chunks from disk, but the pace is still vanilla's chunk-batch protocol: the client's
  `ChunkBatchSizeCalculator` starts at 2 ms/chunk and may only fall to a third per batch with growing weight, so the send rate
  ramps from ~9 to the 64 chunks/tick cap over ~5 s whatever the client's speed (its reply is server-visible: not changed).
  The compile queue stayed empty during the joins; join time is protocol/server bound (results/STAGE2-R1.md). Pass
  `--jvm=-Dmc.chunkStats=false` for join A/Bs (the counters hash every mesh).
- Report: `tools/chunk_report.py FILE.json [--detail] [--series] [--group N]`; off-CPU time of slow frames from a trace CSV:
  `tools/trace_offcpu.py`.

### 16.S2-W11 Render-equivalent block updates skip their rebuild (stage 2 round 1, `-Dmc.chunk.sameRenderSkip`, default **on**)

Java only, client only (`client/.../chunk/BlockUpdateFilter.java`, one block in `ClientLevel.sendBlockUpdated`). No packet,
gameplay or server-visible change.
- **Why.** The travel bench's recompiles were 88 % byte-identical (93 % of the block-caused ones). The cause: leaves
  `distance` / `persistent` updates the server sends by the thousand while new terrain settles (78 % of 87k block updates in
  30 s of travel; spruce / oak / birch leaves). Vanilla marks the 3x3x3 neighbourhood dirty twice per block update: in
  `Level.setBlocksDirty` (filtered by `ModelManager.requiresRender`) and in `ClientLevel.sendBlockUpdated` (unfiltered);
  the second rebuilt sections whose mesh cannot change.
- **What.** `sendBlockUpdated` returns without marking when `BlockUpdateFilter.renderEquivalent(models, level, pos, old,
  new)`: old != new (an update to the same state is an explicit re-render request: sign / campfire / shelf block entities and
  mods use it), same block, `!requiresRender` (same visual model group incl. colouring properties, same fluid state), and
  equal render shape, solid render, canOcclude, block entity presence, emissive rendering, light emission, light dampening,
  sky-light propagation, shape-for-light-occlusion, occlusion shape and all six face occlusion shapes (by identity),
  shade brightness and full-collision-shape at pos (AO), model offset, and `skipRendering` both ways against the six
  neighbours actually present. That covers every per-state input of `SectionCompiler`, `ModelBlockRenderer`,
  `BlockModelLighter` / `LightCoordsUtil`, `FluidRenderer` and `VisGraph`; light values change through the light engine,
  which dirties sections on its own path.
- **Evidence.** `tools/block-update-filter-test.sh` (every state pair of every block with < 400 states, real model groups from
  the jar, two neighbourhoods: 74,300 checks; same-state updates kept; leaves skipped; waterlogged, redstone ore lit,
  furnace lit, snowy grass, air -> cave air kept; prints the blocks with skippable pairs: leaves, doors / trapdoors /
  fence gates `powered`, block-entity-rendered blocks (banners, beds, chests, heads, shulker boxes: drawn by their BE
  renderer every frame), kelp / vines `age`, falling water / lava levels, scaffolding `distance`, sculk sensor power,
  tripwire `powered` / `disarmed`, ...). In game, verify mode (`=verify` + `-Dmc.chunkStats=true`: marks are kept but tagged)
  counts recompiles whose only source was a skippable update and whether their mesh changed: 44,389 such recompiles over
  six fresh-world travel runs (30-150 s measured each), 6 of 26,788 differed before the cancel-inheritance fix of
  16.S2-W0B (counting artifacts and the case below), 1 of 17,601 after. The differing ones were light-only (the CUTOUT / TRANSLUCENT light hash differs): vanilla's
  client applies queued light-packet data in a later `runLightUpdates` without dirtying the section
  (`LayerLightSectionStorage.markNewInconsistencies` adds it to `changedSections`, not `sectionsAffectedByLightUpdates`), so a
  mesh compiled in between keeps stale light until something else rebuilds it. A leaves update next to it happened to
  repair that in vanilla; with the skip the section looks as vanilla does whenever no such update arrives. Rare (~1 per
  3 min of travel), one section, the light level of a few vertices.
- **Effect** (travel, 2+2, counters on): compiles 6852 -> 2464 per 30 s (-64 %), compile CPU 14.6 -> 4.9 s (-67 %), uploads
  1263 -> 261 MB (-79 %), render-thread block-dirty marks 2.56 M -> 0.53 M; FPS within noise (median -0.5 %, 1 % low +1.3 %,
  0.1 % low +5 %). Classic A/B in results/STAGE2-R1.md. The freed worker CPU matters most in single player (the integrated
  server generates terrain on the same cores) and on servers with many leaves / redstone updates.
- **Fabric.** `sendBlockUpdated` keeps its signature; a HEAD injection still runs. A mod whose model depends on a state
  property vanilla's model groups consider invisible (FRAPI dynamic models) would need `-Dmc.chunk.sameRenderSkip=false`.
- Toggle: `-Dmc.chunk.sameRenderSkip=false` (vanilla), `verify` (diagnostic), `BlockUpdateFilter.enabled` (runtime).

### 16.S2-W4 AOT cache for the user's Prism instance (stage 2 round 1; prepared, not installed)

`tools/prism/aot-wrap.sh` is a Prism "Wrapper command": copies NewLaunch.jar out of macOS AppTranslocation (a new random path
every Prism session, which would invalidate the cache; seen in Prism's own launch log) to a stable file and rewrites the
classpath entry; keys the cache by the java binary, the JVM flags except -D / -Xdock, and every classpath entry (path, size,
mtime); maps it with `-XX:AOTCache` (AOTMode=auto: an unusable cache is ignored by the JVM) or, when missing, trains it
during that play session (`-XX:AOTCacheOutput`, written at a normal quit, a few seconds after the window closes; the wrapper
forwards stdin and kill signals). Newest 3 caches kept; `<dir>/disabled` turns it off. Test: `tools/prism/aot-wrap-test.sh`
(real JDK 25.0.1: train, map from another translocation path, new key on a changed jar, disabled; in the headless suites).
Saving on the r13 jar, title path, W7 on (startup-bench, 4+4 ABBA): JVM start -> title -0.53 s, -> world ready -0.68 s
(medians, 4/4 rounds). Installing it changes the instance's settings: the lead / user decides (steps in results/STAGE2-R1.md).

### 16.S2-M Stage-2 round-1 measurements that did not lead to code

- **W16 chunk packets off the render thread.** `handleLevelChunkWithLight` costs 37-70 us per chunk on the render thread
  (max 0.3-1.9 ms), 1-6 ms per second of travel / join, below 1 % of frame time; light polling + `runLightUpdates` 2-17 us per
  frame. No slow-frame class traced to it. Not built.
- **W12 faster mesher.** The compile queue stayed empty in every join run and the joins are bound by the chunk-batch protocol;
  after W11 travel compiles fell by two thirds. No harness metric left that compile speed moves. Deferred to stage 3 (LOD
  meshing throughput), where it feeds the refinement budget.
- **W11a compile-and-compare upload skip.** After W11, 21-43 % of the remaining recompiles are identical (neighbour arrival,
  light); skipping their upload would save bandwidth only, not compile CPU. Not built this round.
- **Light map copy at RD 32** (`swapSectionMap` clones the whole section map): 1.4-5.7 us mean, max 1.3 ms, 0.3 % of frame time.
- **RD 32.** Static RD 32 (still loading chunks during the measurement): 297 FPS median / 149 1 % low, GPU bound; game-side
  chunk work there is ~0.5 % of the frame. The render-distance cost belongs to stage 3 (LOD) and the renderer.
- **Travel tail after W11** (frame trace, speed 33): slowest 1 % = 120 GPU / 51 render / 21 tick frames; the 0.1 % has
  off-CPU stalls (render thread descheduled 1+ ms in 21 %), render-thread QoS is already USER_INTERACTIVE (16.R7-M).

### 16.S3-LOD Far terrain, stage 3 round 1 (M1) (`-Dmc.lod=true`, default **off**; abab `net.minecraft.client.renderer.chunk.Lod#draw`)

Design: docs/stage3/LOD-DESIGN.md. Results: results/STAGE3-R1.md. New classes (`client/.../renderer/chunk/`): `Lod` (switches,
hook entry points), `LodData` (node format, level-0 build with sealing, downsample, codec), `LodStore` (region files under
`<game dir>/lod/{sp,mp}/<world or server>/<dimension>/L<k>/`), `LodRegion` (`RenderSectionRegion` subclass over cached cells),
`LodMesher` (vanilla `SectionCompiler` per 16-cell slab, positions scaled by 2^level), `LodManager` (sources, workers,
selection), `LodGpu` (arenas, persistent tables, cull, draws), `LodSelfTest` (headless, `tools/lod-test.sh`).

Hooks (one static call each, no vanilla signature changed, nothing allocated with the switch off):
- `ClientLevel.onChunkLoaded` -> `Lod.onChunkLoaded` (capture 1 s later once `lightOnInColumn`); `ClientLevel.unload` (first
  line) -> `Lod.onChunkUnload` (capture of the final state).
- `Camera.update`: `depthFar = Lod.depthFar(vanilla)` (LOD radius x 1.75 + 512 while drawing; reversed-Z float depth).
- `GameRenderer.extractCamera`: `FogRenderer.setupFog(..., Lod.fogRenderDistance(RD), ...)` (render-distance and sky fog at the
  LOD radius while drawing).
- `LevelRenderer.prepareChunkRendersIndirectFast`: `Lod.prepareFrame(DrawIndirect, planes, frustum, camera, eye, P[1][1])`
  after the round-12 cluster dispatch; `ChunkSectionsToRender.DrawIndirect.withLodDraws` + `renderLod`: SOLID / CUTOUT LOD
  after the layer's real draws, TRANSLUCENT LOD before them; not with the wireframe override.
- `RenderPipelines.TRANSLUCENT_TERRAIN_CLUSTER` (registered only with `-Dmc.lod=true`; vertex stage = round 12's cluster
  pulling function, `TerrainVertexMsl.clusterPipelineReady`).

Native (`mcmetal_clusters.m`, extension symbols unchanged, ABI unchanged): the view block may be 128 bytes; flag 4 =
persistent section table: `o -= camDelta`, `e += eyeDelta` (float4 at bytes 96 / 112) and the table's first float4 `.w` is the
section's cluster-bounds unit in blocks (2^level / 16) instead of 1/16. Flag 4 unset (every round-12 call, 92-byte view): the
same arithmetic as before (unit 0.0625).

Server-invisible: sources are chunks the client has and, in singleplayer, the integrated server's saved chunks read through
`ServerChunkCache.chunkMap.read` (its IO worker, read-only). No request, no packet, nothing written to the save.

| Toggle | Default | Effect |
|---|---|---|
| `mc.lod` | false | build the LOD system (startup) |
| `mc.lod.radius` | 64 | LOD radius in chunks |
| `mc.lod.draw` / abab `Lod#draw` | true | per frame: draw LOD, extend fog and far plane; false = vanilla image (data stays) |
| `mc.lod.tau` | 2.0 | panel px per LOD cell (level = floor(log2(tau d / K))) |
| `mc.lod.threads` | 3 | worker threads (minimum priority) |
| `mc.lod.sp` | true | singleplayer: read the server's saved chunks in the radius |
| `mc.lod.disk` | true | persist the store |
| `mc.lod.gpuMB` / `mc.lod.ramMB` | 1536 / 384 | GPU mesh budget / in-memory store budget |
| `mc.lod.maxLevel` | 4 | coarsest level |
| `mc.lod.stats` | 0 | log the LOD state every N s |

Bench (`build/bench-s3r1*.jar`, `bench/src/bench/LodHook.java`): settle waits for `Lod.idle()` (`bench.lodWait`, 900 s); results
carry `lod`, `lodSettleSeconds`, `memory`; `bench.pos=x,y,z`, `bench.zoom=F` (photo / perf), `bench.timeout=S`;
`mode=lodpregen` (`bench.pregen.radius`): test-fixture generation of a world COPY by the integrated server (flushes every
8192 chunks: the IO worker's background-priority writes otherwise starve behind the requests' reads and exhaust the heap).

#### 16.S3-LOD-R1B Stage 3 round 1b: LOD cost (still `-Dmc.lod=true`, default **off**)

Results: results/STAGE3-R1.md §R1b. Changes on top of 16.S3-LOD:
- `LodGreedy` (new, `LodMesher` calls it per slab-layer before the 2^level scale; `mc.lod.merge`, default true): removes
  back-to-back full-cell opaque pairs in SOLID (`mc.lod.pairs`; exact: both faces' front sides lie inside opaque cubes) and
  merges full-cell faces of one facing / plane / atlas tile into greedy rectangles (loose keys: across random texture rotation
  and AO diagonal flips, `mc.lod.mergeLoose`; colour tolerance `mc.lod.mergeTol0` 4 at level 0, `mc.lod.mergeTol` 8 above;
  light equal). Merged faces carry the tile + 1 in UV2's high bytes (round 12's TerrainGreedy contract).
- `RenderPipelines.{SOLID,CUTOUT,TRANSLUCENT}_LOD_GREEDY_CLUSTER` (`pipeline/*_lod_greedy_terrain_cluster`, defines
  `TERRAIN_CLUSTER` + `TERRAIN_GREEDY`; registered only with `-Dmc.lod=true`): fragment = core/terrain.fsh greedy wrap; vertex =
  `TerrainVertexMsl.clusterVertexGreedy` (cluster pull fetch + light from UV2 low bytes + flat `greedyTile` at locn5), chosen in
  `MetalRenderPipeline` by the `_lod_greedy_` name. `RenderPipelines.lodClusterPipeline` falls back to the plain cluster
  pipelines (merged faces then stretch their texture; warned once).
- LOD meshing: leaves opaque (`mc.lod.cutoutLeaves`, default false), levels >= `mc.lod.aoLevel` (1) flat-lit (second
  `SectionCompiler` with AO off).
- Selection: level-0 split only for nodes holding a chunk inside vanilla's round view distance (was the RD+1 square);
  `mc.lod.tau` default 16, `mc.lod.maxLevel` 5, `mc.lod.gpuMB` 1024; GPU-budget control (`LodManager.adaptTau`: selection bytes
  > 75 % of the budget raise tau x1.25, < 45 % lower it slowly; never refused uploads); missing coarse nodes are rebuilt bottom-up
  from stored descendants (`ensureData`); coarse downsample keeps leaves under snow covers.
- Diagnostics: `Lod#layerMask` (abab, bit per layer), `mc.lod.dumpDir` (raw slab dumps) + `tools/lod/greedy-tool.sh`.
- Native and ABI unchanged.

#### 16.S3-LOD-R2 Stage 3 round 2: LOD fog, look, transitions, compact quads (still `-Dmc.lod=true`, default **off**)

Results: results/STAGE3-R2.md. Changes on top of 16.S3-LOD-R1B:
- **Fog UBO** (`FogRenderer`, patched; `FOG_UBO_SIZE` 40 -> 48 bytes): two floats after `FogCloudsEnd`: `FogLodStart`,
  `FogLodCompress` (0 / 0 = vanilla; the empty fog buffer writes 0). `assets/minecraft/shaders/include/fog.glsl` (ours) declares
  them and `fog_lod_distance(d) = d - max(d - FogLodStart, 0) * FogLodCompress`; `core/terrain.fsh` feeds it to the
  environmental fog; the round-11 `vfog` rewrite (`TerrainShaderVariants.pairedVariant`) adds the same line when the Fog block
  carries the fields; `TerrainVertexMsl.HEAD`'s Fog struct has them. Non-terrain shaders keep vanilla's fog.
- `GameRenderer.extractCamera`: after `setupFog`, `Lod.adjustFog(fogData, RD)`: FogLodStart = RD blocks, compress so the LOD
  radius maps to `mc.lod.fogDist` (320), render-distance ramp over the last `mc.lod.fogEdge` (0.2) of the radius; LOD draws
  skipped while the environmental fog ends before RD - 32 (`mc.lod.fogSkip`). Toggle `Lod#fog` / `mc.lod.fog` (true).
- **Compact LOD quads** (`LodCompact`, `mc.lod.compact` true): 48-byte records (layout in the class comment) in separate arenas;
  pipelines `*_lod_compact_terrain_cluster` (vertex `TerrainVertexMsl.clusterVertexCompact`, record = item.x / 4 + local quad,
  positions in cells x 2^level, uv via `TerrainUniform.TextureSize`). Draw codes in `TerrainClusters.Draw.layer`: layer | 8
  compact | 16 fading (`RenderPipelines.lodPipeline`).
- **Fades**: `*_lod_{greedy,compact}_fade_terrain_cluster` (define `LOD_FADE`, discard) for sections that are dissolving;
  section info w: 1 drawn, [0, 1) fading in to t, [2, 3] fading out from t - 2 (`mc.lod.fadeMs` 400). Selection hysteresis
  `mc.lod.hysteresis` (0.2).
- **Coarse downsample**: snowy-leaves marker (leaves, persistent, distance 7; `mc.lod.snowTops`), snow tops at mesh time
  (`mc.lod.snowMix` 0.35), ice / glass are surface blocks, leaves over logs (`mc.lod.leavesOverLogs`); coarse levels stored
  as `L<k>c4`.
- Diagnostics: `mc.lod.diag` = novs (primitives clipped after the vertex stage), nofetch, flatfs (vertex colour only), minvary,
  direct; LOD JSON adds `visibleQuads(Avg)` (post-cull, read from the indirect args 4 frames later), compact layer counts /
  fallback reasons, fade counts. `LodManager.postCamera` ignores non-finite snaps. Bench `bench.cmds` (';'-separated
  commands after the default time / weather; scratch worlds only).
- Native and ABI unchanged.

#### 16.S3-LOD-R3 Stage 3 round 3: LOD occlusion, seed-based generation, look (still `-Dmc.lod=true`, default **off**)

Results: results/STAGE3-R3.md. Changes on top of 16.S3-LOD-R2:
- **Occlusion of LOD clusters by box queries** (`LodGpu`, `Lod.OCCLUSION` = `mc.lod.occlusion` true; per frame `Lod#occlude` =
  `mc.lod.occlude` true, abab-switchable). Instead of a depth pyramid (the opaque depth is never in memory: the merged
  encoder clears it in-pass for the hand, and a split would store / reload the 3024x1964 drawable), the depth test itself is the
  query: every LOD cluster that passes frustum + facing and lies entirely inside the side planes, at least
  `OCCL_EYE_MIN` (4) blocks in front of the eye, appends its bounds box (widened by `mc.lod.occlMargin` 0.25 block) to a box list;
  `pipeline/lod_occlusion_terrain_cluster` draws the boxes right after the opaque LOD (`ChunkSectionsToRender.renderLod`, CUTOUT
  layer, before entities / translucent / clouds / hand), no colour or depth writes, depth test GEQUAL, `[[early_fragment_tests]]`,
  and each fragment that passes writes `stamp << 1 | 1` into the cluster's visibility entry. A cluster is skipped when its entry
  says "tested `mc.lod.occlLag` (2) frames ago, no box fragment visible"; every other cluster writes "visible" for its entry,
  so an entry never claims a test that did not happen (entries = arena * 2^20 + first quad / 8; arena ranges are 8-quad
  aligned, so entries are shared only within one section-layer, and sharing only adds visibility). Stamps are trusted only if
  the frame `lag` occlusion frames back had its box draw recorded (wireframe / layer mask / skipped frames reset them).
  Lag 2 = a ring of two entry buffers: with lag 1 the cull of frame N waited for frame N-1's fragment work (no overlap of
  consecutive frames on the GPU; measured -49 % FPS).
- Native (`mcmetal_clusters.m`): new extension symbol `mcmtl_ext_lod_cull(device, jobs, jobCount, view, viewBytes, occl, jobExtra)`
  (`mcmtl_ext_cluster_cull` = the same with occl NULL; ABI unchanged): kernel `mcmtl_lod_test_occl` (the flag-4 test + the
  entries + atomic box append, counters in the params block: box count word 5, occluded quads / clusters words 6 / 7) and
  `mcmtl_lod_box_args` (indirect args: 18 indices per box). `mcmtl_lod_occl` = 80 bytes (`MetalClusterCull.OCCL_SIZE`).
- Box pipeline: vertex and fragment both hand-written (`TerrainVertexMsl.lodOcclusion`); the GLSL fragment placeholder
  `core/lod_occlusion.fsh` only declares the `LodOcclusion` uniform block so the pipeline keeps it (the fragment MSL binds the
  entry buffer there as `device uint*`). No terrain shader variants (`TerrainShaderVariants.pipelineMatches`).
- **Seed-based generation** (`LodGen`, `mc.lod.gen` true; `net.minecraft.world.level.levelgen.material.LodMaterial` = new class
  in vanilla's package for the package-private `MaterialRuleContext`): any level is generated directly at its cell size from
  the seed: `overworld/sloped_cheese` (+ final density's top / bottom gradients) on vanilla's 4 x 8 x 4 lattice (step max(4,
  cell) horizontally), vanilla's interpolation, biome source + climate sampler, surface rules per column (gradients from the
  neighbour cells), sea-level water, ice / snow where `!warmEnoughToRain`, per-biome trees (density and shapes after
  vanilla's tree features) voxelized into cells (`mc.lod.genFill` 0.3 at levels >= 2). Generated nodes live in their own store
  `<store>/gen-<seed hex>-<LodGen.versionTag()>/` with a disk cap (`mc.lod.genDiskMB` 2048; farthest region files deleted,
  `LodStore.setDiskCap`). `LodManager.load` reads the real store first; a downsample with missing children takes those
  quadrants from the generated node of its level (`LodData.overlay`); nodes without any data are generated at their level
  (`LodManager.scheduleGen`, worker priority = distance). Singleplayer: the integrated server's generator and seed;
  multiplayer: only with `-Dmc.lod.seed=<seed>` or `<game dir>/lod/seeds.properties` (`<server address>=<seed>`), vanilla's
  overworld generator from the built-in registries (`VanillaRegistries.createWorldLookup`); nothing is requested from the
  server. Diagnostics `mc.lod.genOnly` (generated data everywhere except loaded chunks; generated vs real comparisons).
- **Look**: coarse faces repeat their texture per block (`LodGreedy.repeatTiles`, `mc.lod.tileRepeat` true: UV extent x 2^level
  from corner 0, tile in UV2's high bytes, the greedy / compact pipelines wrap); side faces of snowy-leaves cells mix
  `mc.lod.snowSide` (0.3) white; per-level tau `mc.lod.tauLevels` (px for levels 1, 2, ...); `mc.lod.leafMin` (4 = round 2)
  for leaves-only coarse cells (coarse store directory `L<k>c4m<n>` when changed).
- **Transitions**: `SectionRenderDispatcher.RenderSection.updateUploadTime` takes its start time from `Lod.sectionUploadTime`:
  a real section whose chunk the LOD drew within the last 3 s (`Node.lastDrawnMillis`, any level) starts fully visible (no
  fade-in from the fog colour; `mc.lod.fadeSkip` true). While scoping, the selection uses the spyglass's final FOV (0.1x)
  from the first frame (`mc.lod.spyglassAhead` true).
- Stats: LOD JSON `gpu` adds `cpuFrameUs`, `itemBuilds`, `itemBuildUs`, `occludedQuads(Avg)`, `occlusionBoxes`; `gen` = per
  level count / ms, chunk equivalents, chunks per thread-second, jobs, overlays, store MB.

#### 16.S3-LOD-R4 Stage 3 round 4: tree stand-ins, fused LOD cull (still `-Dmc.lod=true`, default **off**)

Results: results/STAGE3-R4.md. Changes on top of 16.S3-LOD-R3:
- **Tree stand-ins** (`LodTrees`, `mc.lod.trees` true, levels `mc.lod.treeMin` 1 .. `mc.lod.treeMax` 2): trees are records
  (3 ints: axis x / z in half blocks, then base, top - base, top - crown low, radius, kind (12 kinds by leaves block), snowy).
  Level-1 lists are detected on the level-0 children during the downsample (`LodTrees.detect`: vertical log runs standing on
  ground with leaves around their top; crown top / low, radius = 3/4 quantile of the leaves runs from the trunk, capped per
  kind; spruce with radius <= 1 and a short crown = pine; 2x2 trunks = one tree; logs by registry name, not tags), coarser
  lists concatenate the children's (`LodTrees.merge`, only up to treeMax), `LodData.overlay` adds generated trees in the
  generated quadrants, `LodGen` records its own trees (`LodGen.record`). `LodData` VERSION 2 appends the list (varint count + 1,
  then 3 ints per tree; version-1 blobs still decode); coarse store directories gain `t<treeMax>` (`L<k>c4t2`, rebuilt from
  level 0) and the generated store's tag `t2`.
  Meshing (`LodMesher`, `LodRegion.stripTrees`): at stand-in levels leaves / snow cover cells inside any 3x3 tree's crown
  cylinder (radius + 1 blocks, crown low .. top + 1) and log cells of its trunk column mesh as air; one billboard quad per tree
  whose base lies in the slab is appended to the CUTOUT layer (after LodGreedy / repeatTiles): CPU corners
  (ax - hw, y0, az - hw) .. (ax + hw, y1, az + hw) (diagonal: facing bucket UNASSIGNED, never facing-culled, box contains the
  rotating quad), UV = the sprite, colour white (conifers) or the leaves' BlockColors tint at the tree (broadleaf), light =
  brightest of the six cells above the crown. `LodCompact` stores such a quad as a **billboard record**: corner 0 = anchor
  (ax, y0, az), edge 1 axis code **3** with the half width as length, edge 2 = y with the height; `TerrainVertexMsl`
  (compact pipelines only, fields `bbHalf` / `bbSide` in the compact `main0_in`) offsets corners 1 / 2 (right) and 0 / 3 (left)
  by `hw * perp(normalize(camera-relative anchor.xz))`: a cylindrical camera-facing billboard. Non-compact layers draw the
  CPU's diagonal quad (fallback). Sprite sizes: billboard width = sprite width x (radius + 0.5) / (canonical radius + 0.5) x
  `mc.lod.treeScale` (1.4), height = sprite height x (top + 1 - base) / canonical height.
- **Impostor sprites** (`tools/LodImpostorGen.java`, run by tools/build-client.sh, never committed): orthographic views of
  vanilla-shaped trees (Spruce / Pine / Blob foliage placers, snow layers on sky-exposed leaves for the snowy variants) at
  azimuth 45 deg, elevation 20 deg, from the vanilla jar's textures with vanilla face shades, AO (0.8 x occupied share of the 8
  in-plane neighbours), leaf shade 0.65, faces behind leaf holes x 0.4; conifers bake the constant spruce tint, broadleaf
  sprites are untinted leaves only. Written as `assets/minecraft/textures/block/lod_tree_*.png` (vanilla's block atlas
  "directory" source stitches them) cropped to whole blocks (multiples of 16 texels: the atlas keeps its mip levels), metrics in
  `assets/minecraft/lod/tree_sprites.txt` (name, width, height, bottom offset in blocks; `LodTrees.Sprite.measured`). Resource
  packs replacing leaf textures do not change the stand-ins (documented gap).
- **Fused LOD cull** (`Lod.fusedCull` / `mc.lod.fusedCull` true, abab `Lod#fusedCull`; view flag 8): with occlusion, each job is
  ONE dispatch of `mcmtl_lod_cull_fused` (the r3 test + occlusion bookkeeping, then a per-SIMD-group atomic reservation on the
  job's index count and the cluster's own quad writes) in a **concurrent** compute encoder (memory barrier before
  `mcmtl_lod_box_args`); the CPU zeroes the jobs' args (Shared buffers; falls back to the r3 path otherwise). LOD quad order
  is no longer the input order (opaque LOD only). Real-terrain cluster culls (round 12) are unchanged.
- Options measured and left off: `mc.lod.flat0Px` (flat-lit far level 0 as a mesh variant in the stamp), `mc.lod.treesFlat0`
  (stand-ins on flat level 0), `mc.lod.treeMax=3`.
- Diagnostics: `mc.lod.diag` `notrees` (leaves / logs mesh as air); LOD JSON `gpu` adds `treeQuads`, `treeBillboards`.
- Native: new kernel in `mcmetal_clusters.m`, extension symbols and ABI unchanged.

#### 16.S3-LOD-R5 Stage 3 round 5: the wave far field (`-Dmc.lod=true -Dmc.lod.wave=true`, default **off**)

Results: results/STAGE3-R5.md; design: docs/stage3/LOD-DESIGN.md L18 / §3.3. Changes on top of 16.S3-LOD-R4, all inactive unless
`mc.lod.wave` is set (then the LOD meshes, their culls and occlusion are not built or drawn):
- **Encoding** (`LodWave.encode`, LOD worker, instead of `LodMesher.mesh`; `LodManager.mesh`): per node 256 columns, each a
  top-down list of runs `lo | hi << 9 | sky light / 2 << 18 | material << 21` (block y + 64, exclusive hi; sky light of the
  cell above the run's top; consecutive cells of one state id;
  categories as `LodData.downsample`: air / plants / torches -> nothing, fluids -> water runs, solid-ish incl. leaves, snow,
  ice, glass -> runs). Level-0 snow layers are never runs; on top of a column the next run's top uses the snow layer's sprite
  (material key `stateId * 2 + 1`; side from the block with `snowy=true`). Runs entirely below every in-node neighbour's top
  (minus `max(32, 4 * cell)` at the node border, `cell` inside) merge into one run to the bottom; at most 63 runs per column,
  16383 per node. Per column: `tops` (ushort: top block y + 64, 0 = empty) and a column word
  `run offset (14) | count << 14 (6) | biome << 20 (8)`. Mesh stamps are the node's own data version (no neighbours).
- **Materials** (`LodWave.material`, synchronized, at most 2048; `MAT_DATA` uploaded on change): 2 x uint4 per material: top
  sprite uv0 / size, side sprite uv0 / size (16-bit unorm of the block atlas; the UP / NORTH quad of the state's model,
  else unculled quads, else the particle; fluids: the particle and tint mode water), then tint modes (top | side << 4: 0 none,
  1 grass, 2 foliage, 3 water, 4 constant) and the constant colours. Mode = the quad's tint index' `BlockTintSource.color(state)`
  compared with GrassColor.getDefaultColor() / FoliageColor.FOLIAGE_DEFAULT. Biome table at uint4 index 4096 (at most 256
  biomes: grass at (0, 0), foliage, water colour).
- **GPU state** (`LodWave`, render thread; uploads through `LodGpu.uploadPending`, frees through `LodGpu.free`): Shared pools
  `tops` (512 B x `mc.lod.waveSlots` 131072), `cols` (1 KiB per slot), `nodes` (int4: node block x, z, level, run base),
  `runs` (`mc.lod.waveRunsM` 64 M uints, first-fit), slot / run frees deferred RING + 3 frames; pools full -> a quarter of the
  unselected resident nodes are freed (`evictUnselected`, ranges reused at once: a frame in flight may show a reused slot for a
  frame, documented). A node is "resident" with `n.gpu = List.of()`; `adaptTau` is off in wave mode; the selection uses the
  option FOV's K outside the view (not 1.1 x FOV) so turning never changes it.
- **Map** (rebuilt on selection / residency / loaded-chunk change into a 3-ring): uint per chunk of a `dim`^2 square whose origin
  is aligned to 128 chunks (every node of levels <= 7 aligned): `EMPTY` or `level << 28 | slot`; level-0 nodes inside vanilla's
  render distance whose chunk is loaded stay EMPTY (the real chunk is drawn).
- **Wave pass** (`mcmtl_ext_lod_wave`, native/src/mcmetal_lodwave.m, side command buffer before the frame, like the culls;
  job 80 bytes: map, tops, ids, line tops, line count, rows, params bytes; params 288 bytes `WaveParams`). Screen mode: lines
  through the vertical vanishing point, 1 px apart on the screen edge farthest from it (`2 ceil(uHalf)` lines, at most 8192;
  skipped when the vanishing point is on screen: |pitch| beyond ~50 deg, the far field is then not drawn); panorama mode
  (`grid.z & 2`): `N = ceil(2 pi K 1.15)` world directions x elevation-angle bins (`1.15 K` per radian between the eye's
  lowest and highest possible far-field angles). Per SIMD group one line, per lane one of 32 distance segments (equal
  estimated cell steps for the selection's tau, up to RADIUS x 16 + 24 blocks). Pass 1: 2D DDA of LOD cells, max elevation
  slope; exclusive SIMD prefix max = coverage of the nearer segments; pass 2: visible column tops write span markers
  (`id | stamp << 28`, stamp 1..14) at the first row of each newly covered span (side part, then top part); lane 0's first column
  after the empty real disc only raises the coverage (its side is behind the real terrain). Fill kernel (same encoder): one SIMD
  group per line, rows bottom-up, nearest marker at or below (0xFFFFFFFF before the first), stamps stripped. Ids: node slot << 8
  | column | face << 26 (0 top, 1 x side, 2 z side).
- **Hybrid ring** (`LodWave.RING_PX`, `mc.lod.wave.ringPx`, default 0 = wave everywhere; 4 measured as the quality option):
  level-0 nodes whose blocks are larger than
  that many px (selection K, 15 % hysteresis, `Node.waveRing`, part of the stamp) are meshed by the vanilla mesher and drawn by
  the raster LOD path (`LodGpu.frame`, cull, occlusion, fades) before the composite; they stay EMPTY in the wave map, so the
  wave starts behind them (the composite's depth test keeps the ring in front). `Node.meshKind` 1 = meshes, 2 = wave columns.
- **Panorama** (`LodWave.panorama` / `mc.lod.wave.panorama` true, abab `LodWave#panorama`; used whenever K < 3000, i.e. not
  zoomed): built whole when its shape (N, K, elevation range: kept while it still covers the eye's range, widened by 0.05 rad
  when rebuilt) changes or the eye moved over 16 blocks from any slice's eye; otherwise one of `mc.lod.wave.slices` (6) slices
  of the directions whose eye position or map build differs from now is rebuilt per frame, round robin (the wave job's
  `dispatchLines` = the slice's line count, the params' `vp.w` = its first line). A standing eye costs nothing after 6 frames; a
  moving one 1/6 of a panorama per frame with at most 6 frames of parallax lag. Slot / run frees are deferred RING + 3 + SLICES
  frames so a stale slice never shows a reused slot. Zoomed (K >= 3000): screen lines every frame.
- **Composite** (`RenderPipelines.LOD_WAVE` `pipeline/lod_wave_terrain_cluster`, `TerrainVertexMsl.lodWave`, drawn by
  `ChunkSectionsToRender.renderLod` after the CUTOUT layer's real terrain): full-screen triangle at the depth of view distance
  `max(16, RD x 8)` (reversed Z, GEQUAL, depth write); fragment: framebuffer y is flipped (row 0 = image bottom); pixel -> line
  (screen: through the vanishing point; panorama: azimuth) and row (screen row / elevation bin), discard above the line's top or
  without id; the pixel's own ray against the id's face plane (top: y = column top; sides: the cell face toward the eye); side
  hits take the run at the hit height, in an air gap the run above x 0.55 (under-canopy shade; `mc.lod.wave.march=true` instead
  marches on through the gap, up to 16 cells, where a block is over 2 px: off by default, its registers cost ~0.5 ms over the
  whole far field); atlas texel at
  `log2(16 dist / (K max(cos, 0.2)))`, tint, vanilla face shade (top 1, x 0.6, z 0.8), lightmap texel (block 0, the run's sky light), the
  terrain fog of fog.glsl with the LOD compression, `[[depth(less)]]` = min(hit depth, triangle depth). Uniform blocks:
  LodWaveP, LodWaveIds, LodWaveLineTop, LodWaveCols, LodWaveRuns, LodWaveNodes, LodWaveMats, LodWaveMap (+ Projection, TerrainUniform, Fog,
  Sampler0, Sampler2), bound to the fragment stage by `MetalRenderPipeline` (dummy GLSL `core/lod_wave.fsh` declares them).
- Diagnostics / attribution: abab `LodWave#use`, `LodWave#pass`, `LodWave#composite`, `LodWave#panorama`; `mc.lod.wave.debug` 1
  (colour by face: top red, x green, z blue, half = gap shade, yellow = marched) / 2 (by material); LOD JSON `gpu.wave` (resident nodes / MB,
  materials, biomes, lines, frames, skipped frames, map builds and reasons `mapWhy` [selection, residency, chunks, origin],
  `passGpuMsAvg` from `mcmtl_ext_lod_wave_stats`, panorama builds / frames).
- Known approximations (beyond the ring): columns are solid from the top down in the wave pass (gaps under canopies shaded,
  nothing seen through them unless `march`); water is an opaque surface; no AO / smooth light (flat sky light per run); no
  plants; no camera roll; the far field is skipped while the vertical vanishing point is on screen.


## 16.FARSIGHT-HIZ: two-phase depth-culling prototype (2026-10-07)

`-Dmc.lod.hiz=true` opts into an experimental raster-only path. Default is false. It changes neither LOD selection nor
mesh/shader quality. The old section/cluster box-query path remains the default and the comparison arm. Frozen ABI2 and
vanilla method signatures are unchanged; the new FFM entry points are optional `mcmtl_ext_lod_hiz_*` symbols.

- `MetalLodHiZ` owns native state; `LodGpu` owns one instance per LOD manager. Shader-source compatibility is checked once
  per manager. The existing resource/geometry invalidation replaces that manager, so a reload rechecks the sources. A
  pack terrain-shader override, wireframe view, absent actual projection, or wave path leaves the old renderer in use.
- Phase 0 dispatches the existing cluster bounds/frustum/facing decisions plus a previous-frame depth test. It writes a
  per-cluster prepass-survivor mask into the otherwise unused scratch buffer. This historical test is only a prediction.
- The main pass draws real opaque terrain and predicted opaque LOD, then closes. `flushForWait(pendingSeq)` commits the
  stream without a CPU wait. Compute builds a minimum-depth pyramid of that frame's D32F attachment, before entities or
  translucency. Reversed Z uses the farthest (minimum) depth; sky, padding and nonfinite values cannot occlude.
- Phase 1 tests against current depth and emits previously skipped opaque clusters. Prepass survivors are not drawn
  twice. Translucent jobs ignore the opaque mask and receive a full current-depth test for their later draw. Recovered
  opaque LOD, features and transparency finish the ordinary target. Current-frame recovery prevents historical depth
  from hiding disoccluded terrain when the eye/projection/meshes change.
- Both stages use tracked resources on the same Metal queue. Indirect arguments are reset on the GPU, never by CPU while
  the first draw can still read them. `args[5]` saves prepass indexCount; statistics sum both stages instead of reporting
  only recovery. Job buffers keep the existing frame ring. The side command buffers use normal native error tracking.
- Projection is the uploaded `frameProjection * modelViewMatrix`, including view bob/hurt/zoom, with the same Metal Y
  conversion as terrain. Captured camera and section-table origin are differenced as doubles before conversion to float.
  Unusable bounds, eye/near-plane intersections and viewport-edge uncertainty remain visible. Projected rectangles include
  an extra pixel boundary; the mip query covers every touched cell. A failed depth capture invalidates the historical
  test before recovery. Cull/ordering failures abort the experimental frame instead of silently dropping geometry.
- The first implementation intentionally splits the render pass and performs a second cull. It can lose performance to
  attachment traffic and conservative depth queries. Adoption requires measured in-game gain and image/motion/zoom gates.
  Native `cullGpuMsAvg` currently measures the older cluster extension, not these new side commands; do not compare it as
  total culling work. Use end-to-end frame measurements or a full GPU trace.

Validation/results: `native/tests/lodhiz_test.m` (production kernels, independent per-pixel oracle),
`results/FARSIGHT-HIZ-H1.md`. The opt-in experiment is not a release or evidence that the 700 FPS requirement is met.

H3 adds `MCMTL_HIZ_QUERY=1` (default0): full-resolution padded depth pyramid and bounded boundary-node descent. Coarse
depth minima outside the projected rectangle no longer force visibility; stack/work exhaustion still returns visible.
The geometric bounds, depth bias, pixel padding and two-phase recovery rules are unchanged. `MCMTL_HIZ_STATS=1` reports
cumulative prediction/pyramid/recovery command-buffer GPU spans every600 recovery completions, without waiting. These
spans can include overlap and must not be treated as pure stage work. All production-kernel oracle checks pass in both
modes; the explicit outside-sky regression proves the refinement takes effect. Matched image/performance screen in
`results/FARSIGHT-HIZ-H3.md`: refined is faster than coarse, but still below the existing box renderer. Defaults unchanged.

H4 adds optional `mcmtl_ext_lod_hiz_sections` and `MCMTL_HIZ_SECTIONS=1` (default0). The existing section-union input covers
all cluster jobs, layers and fading meshes. A section test runs before BOTH phases using that phase's depth/projection;
its private byte mask is indexed by the20-bit section slot. The mask is never reused across phases, and all jobs must
share one section table. Input buffer ownership is retained; current GPU commands retain resources normally. A rejected
section skips cluster projection/depth work. Four query/section configurations pass native per-pixel/grouped-recovery
tests, and the matched in-game image gate passes. End-to-end FPS is neutral/slightly worse despite lower compute spans;
do not enable the stage. See `results/FARSIGHT-HIZ-H4.md` for measurements and the overlap caveat.

H5 adds `MCMTL_HIZ_REUSE=1` (default0). Prediction reads the previous recovery's current-visibility mask through a
separate simple kernel, then applies current frustum/facing tests. Missing history or a changed cluster count uses the
existing depth predictor. A reordered job/cluster list is safe: arbitrary historical bits can only change which valid
current geometry enters the prepass; current-depth recovery still restores every omitted visible cluster.

Recovery with reuse enabled tests prepass survivors too, records their current visibility, then suppresses their second
draw. Saving the prepass/recovery union as history would keep newly occluded clusters alive indefinitely. Translucency
always receives the current test. Empty job lists release history; capture errors retain the existing conservative
recovery behavior. No CPU wait or vanilla signature change is introduced.

History lives in native-owned, tracked private buffers per job, bounded by current job sizes (power-of-two capacity).
Java's retired scratch buffers can be pooled and overwritten, so retaining those objects alone is insufficient. The
current mask is also mirrored in scratch's second half for verification. Reuse mode requires8 bytes per cluster of
scratch; the existing Java allocation already provides this. Stage0 reads precede stage1 writes on the same tracked
queue. Old queued commands retain buffers when an allocation grows or a job disappears.

The native suite exercises32 changing frames, geometry/order/count changes, zoom/projection changes, disappearing and
new occluders, transparency, scratch recycling, empty lists, and fast-kernel frustum/facing equivalence. An independent
hardware raster oracle draws AABB triangles with depth testing and fragment atomics; every hardware-visible cluster
must occur exactly once in the prepass/recovery union. The ordinary per-pixel oracle remains active with reuse off.
GPU-overlap evidence is recorded in `results/FARSIGHT-HIZ-GPU.md`; H5 remains experimental, not a release optimization.

### 16.FARSIGHT-P1 — shader cost attribution (default off)

`-Dmcmetal.lodProbe=rgb` transforms only compact/BLOCK LOD pipelines at final MSL compilation, after the normal paired
terrain rewrites. It replaces RGB with gray while preserving positions, sampling, alpha conversions and every discard.
An unrecognized source fails compilation. The switch is diagnostic only: gray output is never a quality mode or a
shipping performance result. The 96-pair hardware check preserves depth and output alpha byte-for-byte. It does not
remove geometry fetch/projection or primitive work, and changed RGB can affect attachment compression.

The fixed forest normal/probe/normal screen is 105.3/107.3/99.8 FPS. Control drift prevents a small-effect claim; no
optimization is adopted. Reproduction, frozen jar hashes and remaining limitations: `results/FARSIGHT-SHADER-PROBE-P1.md`.


## 16.FARSIGHT-PALETTE-P1 — exact, opt-in palette storage

`mc.lod.palette` is fixed for the game process, default false. Workers turn canonical
48-byte compact LOD records into SPLIT16 only after mesh-cache reads/verification/put;
the disk cache format and BLOCK fallback remain unchanged. A compact arena then has
16-byte record units. Each allocation contains N original geometry/index records,
24-byte color/light palette entries, and 16-byte UV entries; reserve the whole tail
and round to eight units. Cluster firstQuad addresses the record prefix; draw counts
remain N. Record z/w are absolute uint-word offsets relative to the bound arena,
relocated from immutable CPU payload on every upload. Palette loads must support
8-byte alignment. Vertex math after restoring the three original uint4 values is
unchanged. No new render pass or resource binding is introduced.

Visibility entries cover every eight physical units: 2^21 per arena with palette
storage, 2^20 otherwise. Arena creation is capped at64, matching table provisioning
even if the configured memory budget is larger. Entire allocation/slot reuse follows
the existing delayed-free window. The rendering test may reflectively select the
private fetch-generation flag while compiling isolated oracle/candidate pipelines;
that is not a supported in-game switch or ABAB toggle.

96 exact production color/depth pairs,108 real allocation/upload/free checks, and
2,152,120 GPU decode comparisons pass. The normal vfog/hvary fallback on fading
pipelines is explicitly checked. Same-jar raw/palette/raw game medians are
110.7/96.4/100.4FPS. Baseline drift prevents a precise regression estimate, but
there is no demonstrated speed gain. Keep the switch off; no Prism update.
Payload, allocation and residency caveats are in `results/FARSIGHT-PALETTE-P1.md`.

## 16.FARSIGHT-DIRECT-Q1 — direct visible-quad metadata (default off)

`mc.lod.directQuads` is fixed at startup and affects only the merged Farsight path.
View flag32 requests `uint2(firstVertex + 4*localQuad, section)` output instead of
the old uint cluster/local ID. The native capability export is version1; job/view
sizes remain104/128bytes. Native validation requires LOD flag4, the128-byte view,
8-byte output alignment and sufficient capacity. Normal terrain retains its ABI.
Both fused reservation and scan paths preserve source/local addressing. Existing
fused global reservation order remains unspecified; the native proof mirrors old
IDs at each identical candidate reservation to verify every output position.

BLOCK112 and compact48 vertex shaders consume direct addresses while retaining
all geometry, shading and section data. Output allocation/write stride doubles;
old outItems storage remains in this bounded experiment. HiZ, diagnostic shader
paths and merge=false are rejected. Proper BLOCK LOD pipelines must be ready to
avoid the incompatible ordinary-terrain shader fallback. The CPU mesh cache is
canonical and unchanged; this runtime flag does not change its fingerprint.

Native correctness and192 production RGBA8/D32 rendered pairs pass. Additional
ring capacity is reported independently as visibleQuadBufferBytes. The serialized
off/on/off game medians were111.3/101.2/105.9FPS, with higher output allocation in
the candidate. Controls drifted and the first control had a different overlay
state; the candidate also lost to the matching-overlay repeat control. No gain
is demonstrated. Keep it off. Frozen inputs, memory costs and limitations:
`results/FARSIGHT-DIRECT-QUADS-PROTOTYPE.md`.


## 16.FARSIGHT-MICRO-1 — default-off current-frame visibility prototype

`mc.lod.microRaster` is a startup capability switch, default false. With capability
present, `LodMicroRaster.enabled` is a mutable same-session A/B draw toggle.
Only a contiguous compact non-fading SOLID run with the exact admitted nearest
vfog/hvary PSO sources enters the experiment. Resource-pack overrides, alternate
filters, unknown source/layout, fades and other unsupported input retain original
draws. Original SOLID prefix/suffix keep their positions around the candidate
composite; equal-depth order is preserved. CUTOUT/translucent are unchanged.
There is no temporal image/ID reuse.

The private extension uses 104-byte bounded jobs and a 152-byte frame descriptor
(`native/src/mcmetal_lodmicro.h`). Actual draw counts/dispatches stay on the GPU.
A compute pass classifies sub-2px quads, conservative sliver/snap guards retain
hardware rasterization, and both write atomic-max D32-bits/original-order-rank.
Material resolve uses exact-source helpers with primitive-local UV gradients.
The captured color metric passes, but depth remains non-bit-exact (up to 70 ULP);
this is not an exact-depth or full in-game-quality certification.

The final GPU decision writes a 16-byte composite indirect record followed by
one 20-byte original indexed record per job. Valid means composite on/originals
zero; invalid means composite zero/exact original args. Java records both paths
at the original SOLID insertion point. CPU refusal records originals only. Native
work uses the same queue after producer flushes; resources survive completion,
three scratch slots share a hard 1 GiB private budget and respect Metal's
recommended working-set ceiling. The unchanged LOD geometry budget is not a
total-device cap. No rendering decision uses a CPU readback
or GPU wait. Completion receipts report accepted/refused frames separately.

`tools/lod/micro-raster-live-test.sh` checks the actual native extension and
both fallback cases under Metal API validation. Benchmark warmup and boundary
counters require actual GPU-accepted recorded frames; fallback-only execution
cannot count as an optimization. Game FPS and full still/motion gates remain
required. All current evidence and frozen input hashes are recorded in
`results/FARSIGHT-MICRORASTER.md`; no default-on or Prism deployment is authorized
by diagnostic color checks alone.
