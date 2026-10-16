# 08 — Native interop options (Java → Metal) and Metal 4 / macOS 27 features

Scope: how a RenderPearl Metal backend running in vanilla Minecraft 26.3 calls into Metal on an
Apple M5 MacBook (macOS 27, Metal 4, 24 GB unified memory, 3024x1964 display). The document covers
LWJGL 3.4.3's native call machinery, `java.lang.foreign` (FFM) on the shipped JRE, how the game loads
native libraries, whether an ad-hoc-signed dylib loads, the Xcode toolchain on this machine, the
Metal 4 and MetalFX APIs in the macOS 27 SDK, and a recommended interop architecture.

Conventions:
- Game paths are relative to `src/vanilla/`.
- `$SDK` = `/Applications/Xcode.app/Contents/Developer/Platforms/MacOSX.platform/Developer/SDKs/MacOSX27.0.sdk`.
- `$MTL` = `$SDK/System/Library/Frameworks/Metal.framework/Headers`, `$MFX` = `$SDK/System/Library/Frameworks/MetalFX.framework/Headers`,
  `$QC` = `$SDK/System/Library/Frameworks/QuartzCore.framework/Headers`.
- `$JRE` = `mc/runtime/java-runtime-epsilon/jre.bundle/Contents/Home`.
- "Verified" means I ran it on this machine, using small throwaway programs in the session scratchpad
  (not the game). Benchmark numbers are crude `System.nanoTime` / `mach_absolute_time` loops, not JMH.
  They varied by about ±30% between runs, so read them as orders of magnitude.

---

## 0. TL;DR

1. **The interop path is FFM (`java.lang.foreign`) into a thin Objective-C (ARC) shim dylib with a coarse C ABI.**
   FFM is final in the shipped JDK 25.0.1, and the vanilla launcher already passes
   `--enable-native-access=ALL-UNNAMED`. No `jni.h` is needed (the runtime ships none), and the
   per-call cost is the same as or lower than JNI.
2. **LWJGL 3.4.3 already runs on FFM on this JRE.** The multi-release jar replaces `org.lwjgl.system.JNI`'s
   1775 `static native invoke*` methods with FFM downcalls generated at class-init. They are non-critical
   and log `[LWJGL] FFM downcalls enabled` (verified). Every existing GL/Vulkan backend call already goes
   through FFM.
3. **Measured transition costs:** JNI static native ≈ 3.2–5.0 ns, FFM downcall ≈ 2.8–4.2 ns,
   FFM `Linker.Option.critical(false)` ≈ 1.4–2.7 ns, and Java→`objc_msgSend` via FFM ≈ 4–6 ns
   (2.4 ns critical), compared with 1.8 ns for the same ObjC message sent natively. Natively encoding one Metal
   draw (bind + draw) costs ≈ 38–48 ns of CPU. The transition itself is therefore small. The real costs
   are Java-side marshalling per call and per-draw driver work.
4. **Recommended granularity:** Java writes a packed command stream (plain memory stores into an off-heap
   segment). Then **one FFM call per submit** decodes it and encodes every render, compute and blit pass,
   and commits. This was verified end to end: Java → FFM → shim → Metal 4 drew a triangle correctly, and
   **~4000 draws were decoded, encoded and committed in 0.21 ms of CPU with a single downcall**.
5. **The ad-hoc-signed dylib loads.** The `java` binary is hardened-runtime, Developer-ID (Microsoft)
   signed, and carries `com.apple.security.cs.disable-library-validation = true`. clang's default
   *adhoc,linker-signed* dylibs load via `System.load`, `SymbolLookup.libraryLookup` and LWJGL
   `Library.loadNative` (all three verified). Mojang itself ships an ad-hoc-signed arm64 dylib (jtracy).
6. **Toolchain:** Xcode 27.0 (27A266a), the MacOSX27.0 SDK, Apple clang 21 and `-fobjc-arc` all work.
   **The offline Metal compiler is NOT installed:** `xcrun metal` fails with "missing Metal Toolchain",
   and `xcodebuild -showComponent MetalToolchain` reports `Status: uninstalled`. This does not block
   anything. Runtime MSL compilation works (verified: `newLibraryWithSource` and `MTL4Compiler`), and the
   game already ships SPIRV-Cross with the MSL backend (`lwjgl-spvc`).
7. **Metal 4 is available.** The M5 reports `MTLGPUFamilyMetal4` and `Apple10` (not `Apple11`), GPU
   architecture `applegpu_g17g`, MetalFX spatial, temporal, denoised and frame-interpolation support
   (including the Metal 4 FX variants), ray tracing from render stages, a 24 MHz timestamp frequency,
   `recommendedMaxWorkingSetSize` = 18186 MiB, and `maxBufferLength` = 13639 MiB.
8. **Packaging:** put the dylib in the patched client jar at
   `macos/arm64/com/mojang/renderpearl/backend/metal/libmcmetal.dylib` and load it from
   `MetalBackend.loadLibrary()` with
   `Library.loadNative(MetalBackend.class, "com.mojang.renderpearl.backend.metal", "mcmetal", true)`.
   LWJGL extracts it into the launcher-provided `SharedLibraryExtractPath` (verified with a probe jar).
   You must **strip Mojang's jar signature** (`META-INF/MOJANGCS.SF/.RSA` plus the per-entry digests in
   `MANIFEST.MF`) when patching classes.

---

## 1. Verified environment facts

| Item | Value | Evidence |
|---|---|---|
| JRE | Microsoft Build of OpenJDK **25.0.1+8-LTS** (`java-runtime-epsilon`), arm64 | `$JRE/release` (`JAVA_VERSION="25.0.1"`); `java -version` |
| JRE contents | Full JDK image: `javac`, `javap`, `jlink`, `jnativescan` are present. **No `include/` directory, so no `jni.h`.** Modules include `java.base` (FFM), `jdk.incubator.vector`, `jdk.internal.vm.ci`, `jdk.graal.compiler` | `ls $JRE/bin`, `java --list-modules` |
| Launcher JVM args (macOS) | `-XstartOnFirstThread`, `-XX:StackShadowPages=32`, `--enable-native-access=ALL-UNNAMED`, `--add-exports java.base/jdk.internal.misc=ALL-UNNAMED`, `-Djava.library.path=${natives_directory}/java`, `-Djna.tmpdir=${natives_directory}/jna`, `-Dorg.lwjgl.system.SharedLibraryExtractPath=${natives_directory}/lwjgl`, `-Dio.netty.native.workdir=${natives_directory}/netty` | `mc/versions/26.3/26.3.json` → `arguments.jvm` (the file is a single JSON line) |
| Default user JVM args | `-Xms2G -Xmx4G -XX:+UseCompactObjectHeaders -XX:+AlwaysPreTouch -XX:+UseStringDeduplication`, plus `-XX:+UseZGC` on osx | `26.3.json` → `arguments.default-user-jvm` |
| Main class / Java | `net.minecraft.client.main.Main`; `javaVersion.component = java-runtime-epsilon`, major 25 | `26.3.json` |
| LWJGL | **3.4.3+4**, multi-release jar (`Multi-Release: true`, built with "27-beta+32 (Azul)") | `unzip -p lwjgl-3.4.3.jar META-INF/MANIFEST.MF`; runtime log `[LWJGL] Version: 3.4.3+4` |
| macOS libraries in the version JSON | `ca.weblite:java-objc-bridge:1.1` (osx only), JNA 5.17.0, jtracy natives-macos(-arm64), and LWJGL `natives-macos` + `natives-macos-arm64` for lwjgl/freetype/jemalloc/openal/opengl/sdl/shaderc/spvc/stb/vma/vulkan | `26.3.json` → `libraries` |
| `java` code signature | `flags=0x10000(runtime)` (hardened), Authority = Developer ID Application: Microsoft Corporation (UBF8T346G9), Identifier `net.java.openjdk.java` | `codesign -dvvv $JRE/bin/java` |
| `java` entitlements | `cs.allow-dyld-environment-variables`, `cs.allow-jit`, `cs.allow-unsigned-executable-memory`, `cs.debugger`, **`cs.disable-library-validation`**, `device.audio-input` (all true) | `codesign -d --entitlements - $JRE/bin/java` |
| Xcode | Xcode 27.0 build 27A266a; `xcode-select -p` = `/Applications/Xcode.app/Contents/Developer` | `xcodebuild -version` |
| SDK | `MacOSX27.0.sdk` (`xcrun --sdk macosx --show-sdk-version` → 27.0) | `xcrun --sdk macosx --show-sdk-path` |
| clang | Apple clang 21.0.0 (clang-2100.3.34.2), target arm64-apple-darwin27.0.0. `-fobjc-arc` works | `clang --version`; built 4 ObjC test programs |
| Metal offline compiler | `xcrun -f metal` resolves to a stub. `xcrun metal --version` → `error: cannot execute tool 'metal' due to missing Metal Toolchain; use: xcodebuild -downloadComponent MetalToolchain`. `xcrun -f metallib` fails. `xcodebuild -showComponent MetalToolchain` → `Status: uninstalled` | commands run |
| Hardware | Apple M5, 10 CPU cores, 10-core GPU, 24 GiB (`hw.memsize` = 25769803776), Liquid Retina XDR 3024x1964, "Metal Support: Metal 4" | `sysctl`, `system_profiler SPDisplaysDataType` |
| OS | macOS 27.0 (build 26A428), Darwin 27.0.0 | `sw_vers` |

### 1.1 GPU capability probe (verified, `MTLCreateSystemDefaultDevice`)

```
name=Apple M5   architecture=applegpu_g17g   hasUnifiedMemory=1 lowPower=0 removable=0
recommendedMaxWorkingSetSize=18186 MiB   maxBufferLength=13639 MiB   maxThreadgroupMemoryLength=32768
argumentBuffersSupport=1 (=MTLArgumentBuffersTier2)   readWriteTextureSupport=2 (=Tier2)
supportsRaytracing=1 supportsRaytracingFromRender=1 supportsFunctionPointers(FromRender)=1/1
supportsBCTextureCompression=1 supports32BitFloatFiltering=1 supports32BitMSAA=1 pullModelInterpolation=1
supportsShaderBarycentricCoordinates=1 supportsPrimitiveMotionBlur=1
depth24Stencil8PixelFormatSupported=0   (deprecated in 27.0: "Never supported on Apple Silicon")
sparseTileSizeInBytes=16384   maxArgumentBufferSamplerCount=500000
families: Apple7..Apple10 = 1, Apple11 = 0, Metal3 = 1, Metal4 = 1
queryTimestampFrequency=24000000 (24 MHz → 41.667 ns/tick)   sizeOfCounterHeapEntry(timestamp)=8
MetalFX: Spatial, Temporal (minScale 1.0, maxScale 3.0), FrameInterpolator, TemporalDenoised: supportsDevice=1, supportsMetal4FX=1
Runtime MSL compile (MSL 4.0, newLibraryWithSource): OK, 108.9 ms cold (compiler service spin-up)
MTL4 queue/allocator/cmdbuf/argtable/compiler: all OK; MTL4Compiler render PSO: 20.7 ms
MTLResidencySet OK; shared buffer gpuAddress=0x10000080000 (contents is a plain CPU pointer)
CAMetalLayer defaults: maximumDrawableCount=3, pixelFormat=80 (BGRA8Unorm), framebufferOnly=1, displaySyncEnabled=1; layer.residencySet != nil
```

Consequences for the backend:
- There is **no D24S8**. Map depth formats to `Depth32Float` / `Depth32Float_Stencil8` (or `Depth16Unorm`).
- Timestamps must be converted from ticks to ns: `ns = ticks * 1e9 / 24e6`. `DeviceInfo.timestampPeriod()` should report 41.6667. See §7.8.

---

## 2. How the game loads native code today

### 2.1 Order of events

1. `Main.main` parses options. `--graphicsBackend <default|opengl|vulkan>` and `--vulkanValidation` are
   declared at `net/minecraft/client/main/Main.java:81-84`.
2. **`NativeLibrariesBootstrap.loadLibraries()`** runs before anything else touches natives
   (`Main.java:128-137`). A failure produces crash code -3.
3. Later the render thread is named and registered: `Thread.currentThread().setName("Render thread")`
   and `RenderSystem.initRenderThread()` at `Main.java:269-270`. With `-XstartOnFirstThread`, **the
   render thread is the process's first (AppKit main) thread**. That matters for CAMetalLayer, NSView
   and SDL window work.
4. Backend choice: `PreferredGraphicsApi.getBackendsToTry()` returns `{gl, vulkan}` or `{vulkan, gl}`
   (`net/minecraft/client/PreferredGraphicsApi.java:33-37`). The enum has only DEFAULT/OPENGL/VULKAN
   (`:10-13`), so a `METAL` entry must be added there, together with the option codec and translations.

### 2.2 `com/mojang/blaze3d/platform/NativeLibrariesBootstrap.java`

- `loadLibraries()` (`:58-109`):
  - `configureLWJGLLibraryPath()` (`:60`, body `:162-170`) rewrites `org.lwjgl.system.SharedLibraryExtractPath`
    to `<path>/<Version.getVersion() with ' '→'-'>/<arch lower-case>`. With the launcher value
    `${natives_directory}/lwjgl`, LWJGL extracts into `${natives_directory}/lwjgl/3.4.3+4/arm64/`.
  - `createAndCheckDirectory()` runs for the LWJGL extract path, `jna.tmpdir` and `io.netty.native.workdir`
    (`:61-63`, body `:111-143`). It creates the directory and writes a probe file named
    `System.mapLibraryName("VeryImportant"+rand)`, which becomes `libVeryImportantNNNN.dylib`. It then
    takes a shared `FileLock` (5 tries, 10 ms apart, `:145-160`), reads the file back and deletes it. A
    non-writable or non-lockable directory is a hard failure.
  - It forces `Configuration.DEBUG_LOADER=true` and captures LWJGL's debug stream into the crash report
    (`:64-66`, `:172-182`, `:184-212`).
  - Loads `Library.initialize()` ("LWJGL system", `:75`, `:262-264`). Then `tryLoadingVulkan()`
    (`:76`, `:285-297`) sets `Configuration.VULKAN_EXPLICIT_INIT=true` if unset and calls `VK.create()`.
    That loads `libMoltenVK.dylib` from `lwjgl-vulkan-natives-macos-arm64`.
  - It then loads SDL, OpenGL, OpenAL, STB and FreeType, and **only if the Vulkan loader loaded**
    shaderc, spvc and vma (`:77-86`). The order is **shuffled** (`Collections.shuffle(entries)`, `:88`)
    to shake out hidden load-order dependencies.
  - On failure the crash report lists the contents of `java.library.path` and `org.lwjgl.librarypath`
    (`:189-199`).
- Implication for Metal: the frontend uses SPIRV-Cross for reflection regardless of backend
  (`com/mojang/renderpearl/frontend/shaders/SPIRVModule.java:71,97`). Spvc is therefore loaded lazily on
  first use even when MoltenVK fails. The Metal backend can rely on `org.lwjgl.util.spvc.Spvc` being available.
- **Where to load the shim:** inside the new `MetalBackend.loadLibrary()`, which implements
  `GpuBackend.loadLibrary() throws BackendCreationException` (`com/mojang/renderpearl/api/device/GpuBackend.java:5-15`).
  Follow `VulkanBackend.loadLibrary()` (`com/mojang/renderpearl/backend/vulkan/VulkanBackend.java:58-87`):
  cache the failure and throw `BackendCreationException`. The frontend then falls back to the next
  backend. It is not necessary to touch `NativeLibrariesBootstrap`. Optionally add a "Metal shim"
  `LibraryLoadEntry` there so load failures show up in its crash report.

### 2.3 How the vanilla launcher passes library paths

- `-Djava.library.path=${natives_directory}/java` points at a `java` **subdirectory** of the
  launcher's natives dir. The modern natives jars are classifier artifacts on the classpath
  (`org.lwjgl:lwjgl:3.4.3:natives-macos-arm64`, with OS rules), so nothing in this version JSON requires
  legacy extraction into it.
- `-Dorg.lwjgl.system.SharedLibraryExtractPath=${natives_directory}/lwjgl`: LWJGL finds each
  `macos/arm64/org/lwjgl/<module>/lib*.dylib` resource on the classpath and extracts it there.
  Observed log: `Using SharedLibraryLoader... Extracting: jar:...!/macos/arm64/org/lwjgl/liblwjgl.dylib to: <extract>/liblwjgl.dylib ... Loaded from org.lwjgl.librarypath`.
- Workspace trap (not a game issue): the helper `tools/mcdl.py:120-128` extracted **every** `natives-*`
  jar into `mc/versions/26.3/natives/` with first-file-wins semantics. Because `natives-macos` (x86_64)
  is listed before `natives-macos-arm64`, that directory now holds **x86_64** `liblwjgl.dylib`,
  `libSDL3.dylib`, `libMoltenVK.dylib` and so on (verified with `file`). Only
  `libjtracy-jni-macos-arm64.dylib` is arm64. Do not point `java.library.path` or `org.lwjgl.librarypath`
  at that directory when launching a test build. Let LWJGL extract from the classpath instead.

### 2.4 Other native-loading precedents in the game

- **jtracy** (`com.mojang:jtracy:1.14.38`): `com.mojang.jtracy.Loader` picks `libjtracy-jni-macos-arm64.dylib`
  from the jar root, extracts it under `java.io.tmpdir` and `System.load`s it. Its natives are plain JNI
  (`TracyBindings` static natives). **That dylib is ad-hoc, linker-signed**
  (`codesign`: `flags=0x20002(adhoc,linker-signed)`). This proves an ad-hoc signed library is acceptable
  to this runtime in production.
- **java-objc-bridge + LWJGL ObjCRuntime**: `com/mojang/blaze3d/platform/MacosUtil.java:3-8,16-30,45` uses
  `ca.weblite.objc.Client` (JNA-based) to message `NSApplication`, and
  `org.lwjgl.system.macosx.ObjCRuntime.sel_getUid`. This is precedent for direct Objective-C messaging from Java.
- **SDL window creation per backend**: GL ORs `SDL_WINDOW_OPENGL` (2) at
  `com/mojang/renderpearl/backend/opengl/GlBackend.java:60-67`, and Vulkan ORs `SDL_WINDOW_VULKAN`
  (0x10000000) at `VulkanBackend.java:128-130`. The base flags come from
  `com/mojang/blaze3d/platform/Window.java:163-171`: `8224L = SDL_WINDOW_RESIZABLE (0x20) | SDL_WINDOW_HIGH_PIXEL_DENSITY (0x2000)`.
  A Metal backend should OR in **`SDL_WINDOW_METAL = 0x20000000`** (`org.lwjgl.sdl.SDLVideo.SDL_WINDOW_METAL = 536870912`).

---

## 3. LWJGL 3.4.3 native call machinery

### 3.1 `org.lwjgl.system.JNI`: generic "invoke a function pointer" helpers

- **Base class** (`org/lwjgl/system/JNI.class` in `lwjgl-3.4.3.jar`): 1775 `public static native`
  methods. The name encodes the signature and **the last `long` argument is the C function address**.
  Letters: `P` = pointer (long), `J` = int64, `I` = int32, `V` = void, `C` = short, `U` = byte,
  `F`/`D` = float/double, `Z` = boolean, and the final letter is the return type.
  Examples present: `invokeV(long)`, `invokeI(long)`, `invokePV(long,long)`, `invokePI(long,long)`,
  `invokePP(long,long)`, `invokePJI(long,long,long)`, `invokePPI(long,long,long)`,
  `invokePPPI(long,long,long,long)`, `invokePPP(long,long,long)`.
- **Java 25 override** (`META-INF/versions/25/org/lwjgl/system/JNI.class`, plus `JNI$JNIBindings`): the same
  1775 methods are **non-native**. Each one forwards to an interface method
  `JNIBindings.invokeXXX(MemorySegment fn, args...)`. The static initializer is:
  ```
  jni = FFM.ffmGenerate(JNIBindings.class, FFM.ffmConfigBuilder(MethodHandles.lookup()).withChecks(false).build());
  APIUtil.apiLog("FFM downcalls enabled");
  ```
  The body is `jni.invokePV(MemorySegment.ofAddress(fn), arg)` (from `javap -c`). The `JNIBindings`
  methods are annotated only with `@FFMPointer`, `@FFMNullable`, `@FFMCLong` and `@FFMFunctionAddress`.
  There is **no `@FFMCritical`**, so `BCCallDown.addCritical` adds no `Linker.Option.critical`, and every
  LWJGL call on this JRE is a regular (thread-state-transitioning) FFM downcall.
  - Verified at runtime: the LWJGL debug log prints `[LWJGL] FFM downcalls enabled`.
  - The class is picked automatically because the jar is multi-release and the JVM is 25.
- **Usable for our shim:** `long fn = sharedLibrary.getFunctionAddress("mcmtl_submit"); JNI.invokePJI(streamAddr, len, fn);`
  works without writing any FFM code. The call costs the same as a hand-written FFM handle (§4.3), but
  you are limited to LWJGL's pre-generated signatures and cannot opt into `critical`.

### 3.2 `org.lwjgl.system.ffm`: LWJGL's FFM binding generator (Java 25 only)

`FFM.ffmGenerate(Class<T> iface[, FFMConfig])` builds an implementation of an annotated interface at
runtime. It uses hidden-class bytecode generation (`BCCallDown`, `BCCallUp`) and `FFM.bootstrapDowncall`
constant bootstraps. Config builder (`FFM$FFMConfigBuilder`): `withSymbolLookup(SymbolLookup)`,
`withFunctionProvider(FunctionProvider)`, `withCriticalOverride(Function<Method,Boolean>)`,
`withTracing(...)`, `withChecks(boolean)`, `withNullableAnnotation(...)`, `withDebugGenerator(boolean)`.
Annotations: `FFMCritical(boolean)`, `FFMName`, `FFMPrefix`/`FFMNoPrefix`, `FFMPointer`, `FFMNullable`,
`FFMFunctionAddress`, `FFMCaptureCallState`, `FFMFirstVariadicArg`, `FFMByValue`, `FFMCLong`, `FFMSize`,
`FFMReturn`, `FFMCharset`. It also has struct and union binders (`ffmStruct`, `ffmUnion`) and upcalls
(`ffmUpcall`).

Option: declare the shim ABI as an interface such as
`interface MetalShim { @FFMCritical(true) long mcmtl_completed_value(); int mcmtl_submit(@FFMPointer long p, long n); ... }`
and call `ffmGenerate(MetalShim.class, ffmConfigBuilder(lookup).withFunctionProvider(sharedLib).build())`.
This saves boilerplate, but it ties you to an LWJGL-internal API that is new in 3.4. Hand-written
`static final MethodHandle` fields (§4) are equally fast and need no extra dependency, so prefer those
for a small coarse ABI.

### 3.3 Library loading: `Library`, `SharedLibrary`, `SharedLibraryLoader`

- `Library.initialize()`, `loadSystem(...)` (JNI libraries, i.e. `System.load` semantics) and
  `loadNative(...)` (`dlopen`; returns a `SharedLibrary`). The `loadNative` overloads are
  `(String module, String name)`, `(Class ctx, String module, String name)`,
  `(Class ctx, String module, String name, boolean bundledWithLWJGL)`, and
  `(Class ctx, String module, Configuration<String> override, [Supplier<SharedLibrary> fallback,] String... names)`.
- Search order, from the string constants and log of `Library` (verified):
  1. `org.lwjgl.librarypath` (`Configuration.LIBRARY_PATH`).
  2. A classpath resource `macos/arm64/<module with '.'→'/'>/lib<name>.dylib`, found via
     `context.getClassLoader().getResource`. If it is a plain file it is loaded in place. If it is inside
     a jar, `SharedLibraryLoader.load` extracts it to `SharedLibraryExtractPath` (falling back to
     `java.io.tmpdir`/`lwjgl_<user>`/`user.home`), using a file lock ("File is locked by another
     process, waiting...").
  3. `java.library.path`.
  4. The system `dlopen` search ("Loaded from system paths").
- `SharedLibrary` extends `FunctionProvider` (`getFunctionAddress(CharSequence|ByteBuffer)`),
  `NativeResource` (`free()`) and `Pointer`, and exposes `getName()`/`getPath()`. On macOS it is
  `MacOSXLibraryDL` (`dlopen`/`dlsym`); `MacOSXLibraryBundle` handles CFBundle frameworks.
- **Verified with a probe jar:** a jar containing `macos/arm64/com/example/metal/libbenchshim.dylib` loaded
  via `Library.loadNative(LoadProbe.class, "com.example.metal", "benchshim", true)` was extracted to
  `SharedLibraryExtractPath/libbenchshim.dylib`, loaded, and kept its ad-hoc signature with no quarantine
  attribute. `getFunctionAddress("bs_add")` returned the same address as
  `SymbolLookup.libraryLookup(Path.of(lib.getPath()), Arena.global()).find("bs_add")`.
- LWJGL natives also ship `META-INF/macos/arm64/<module>/lib*.dylib.sha1` and `.git` files.
  `SharedLibraryLoader` uses them to decide whether an already-extracted file is current. Ship a `.sha1`
  next to the shim the same way, or set `-Dorg.lwjgl.system.SharedLibraryExtractForce=true` while
  developing. (The exact comparison logic was not decompiled. Behaviour without a `.sha1` file was
  "extract".)

### 3.4 `MemoryUtil` / `MemoryStack`

- **Memory backend selection** (`org.lwjgl.system.MemoryBackend`, Java ≥ 25): it tries, in order,
  `MemoryBackendUnsafe27`, `UnsafeLegacy27`, `Unsafe26`, `UnsafeLegacy26`, **`MemoryBackendUnsafe`
  (jdk.internal.misc.Unsafe)**, `UnsafeLegacy25` (sun.misc.Unsafe), ..., and finally the FFM backends.
  Classes for a newer Java are skipped by version.
  - With the launcher's `--add-exports java.base/jdk.internal.misc=ALL-UNNAMED`, the log shows
    `[LWJGL] MemoryUtil backend: jdk.internal.misc.Unsafe`. **That is why the launcher passes that flag.**
  - Without the flag it falls back to `sun.misc.Unsafe` and prints JDK "terminally deprecated method"
    warnings (verified).
- Allocator: `jemalloc` (the `lwjgl-jemalloc` module is on the classpath). Selection order is
  mimalloc → rpmalloc → jemalloc → stdlib (from the log). Override with `-Dorg.lwjgl.system.allocator`.
- Useful APIs: `nmemAlloc/nmemAlignedAlloc/nmemFree`, `memAlloc/memAlignedAlloc(int,int)`, `memAddress(Buffer)`,
  `memByteBuffer(long addr, int len)` (wraps a native pointer, for example `MTLBuffer.contents`, as a
  direct `ByteBuffer`), `memCopy`, `memSet`, `memPutInt/Long/Float(long addr, v)`, `memGet*`, and
  `memUTF8(CharSequence)`. These are all intrinsified Unsafe accesses and make good command-stream writers.
- `MemoryStack`: a thread-local stack, **64 KiB** by default (verified `getSize()=65536`; configurable via
  `-Dorg.lwjgl.system.stackSize=<KiB>`). Use `stackPush()` with `malloc/calloc/mallocLong/mallocPointer/UTF8`
  for short-lived out-params at resource-creation time. Do not use it for frame-sized command streams.
- The frontend and backend API already use LWJGL buffer types: `RenderPassBackend.pushConstants(ByteBuffer)`,
  `multiDrawIndexed(IntBuffer...)`, `multiDrawIndexed(PointerBuffer, IntBuffer, IntBuffer, int)`
  (`com/mojang/renderpearl/backend/api/RenderPassBackend.java:22,34-36`). These are direct buffers, and
  `MemoryUtil.memAddress(buf)` gives their address for a `memCopy` into the command stream.

### 3.5 Objective-C and SDL helpers already in the classpath

- `org.lwjgl.system.macosx.ObjCRuntime`: `objc_getClass`, `sel_getUid`, `sel_registerName`,
  `object_getClass`, class/protocol introspection, and `ObjCRuntime.getLibrary()` (libobjc).
  `objc_msgSend` is **not** a bound function. Fetch it with
  `ObjCRuntime.getLibrary().getFunctionAddress("objc_msgSend")` and call it through FFM with a
  per-signature `FunctionDescriptor`.
- `org.lwjgl.sdl.SDLMetal`: `SDL_Metal_CreateView(window) → SDL_MetalView`, `SDL_Metal_GetLayer(view) → CAMetalLayer*`,
  `SDL_Metal_DestroyView(view)`. Hint `SDL_HINT_VIDEO_METAL_AUTO_RESIZE_DRAWABLE`
  (`"SDL_VIDEO_METAL_AUTO_RESIZE_DRAWABLE"`): SDL updates `CAMetalLayer.drawableSize` on resize by
  default. Set it to `"0"` if the backend wants to control drawable size, for example for render-scale
  or MetalFX output.
- `org.lwjgl.util.spvc.Spvc` (SPIRV-Cross; `libspirv-cross.dylib` is 2.68 MB in
  `lwjgl-spvc-3.4.3-natives-macos-arm64.jar`) has the **MSL backend**: `SPVC_BACKEND_MSL = 3`,
  `SPVC_COMPILER_OPTION_MSL_VERSION`, `MSL_PLATFORM`, `MSL_ARGUMENT_BUFFERS`, `MSL_ARGUMENT_BUFFERS_TIER`,
  `MSL_ENABLE_DECORATION_BINDING`, `MSL_FORCE_NATIVE_ARRAYS`, `MSL_TEXTURE_BUFFER_NATIVE`,
  `MSL_INVARIANT_FP_MATH`, `spvc_compiler_msl_add_resource_binding(_2)`,
  `spvc_compiler_msl_add_vertex_attribute`, `spvc_compiler_msl_add_discrete_descriptor_set`, plus the
  common `SPVC_COMPILER_OPTION_FLIP_VERTEX_Y` and `FIXUP_DEPTH_CONVENTION`. The GL backend uses
  `spvc_context_create_compiler(ctx, 1 /*GLSL*/, ...)` (`com/mojang/renderpearl/backend/opengl/GlPipelineRecompiler.java:56-60`).
  The Metal backend does the same with backend 3.

---

## 4. `java.lang.foreign` (FFM) on the shipped runtime

### 4.1 Availability and flags

- FFM has been final since Java 22 (JEP 454) and is in `java.base` of this 25.0.1 runtime. API checked with `javap`:
  - `Linker.nativeLinker()`, `downcallHandle(MemorySegment, FunctionDescriptor, Option...)`,
    `downcallHandle(FunctionDescriptor, Option...)` (address supplied as the first argument at call
    time), `upcallStub(...)`, `defaultLookup()`, `canonicalLayouts()`.
  - `Linker.Option.critical(boolean allowHeapAccess)`, `captureCallState(String...)`, `firstVariadicArg(int)`.
  - `SymbolLookup.libraryLookup(Path|String, Arena)`, `loaderLookup()`, `find`/`findOrThrow`/`or`.
- **Restricted methods** (`Linker.downcallHandle`, `SymbolLookup.libraryLookup`, `MemorySegment.reinterpret`,
  `System.load`, and others): on JDK 24+ (JEP 472) they print a one-time warning per module unless native
  access is enabled. Without the flag I observed: `WARNING: A restricted method in java.lang.System has been called ... Use --enable-native-access=ALL-UNNAMED to avoid a warning`.
  **The vanilla launcher already passes `--enable-native-access=ALL-UNNAMED`**, so a classpath
  (unnamed-module) backend gets no warnings and works today. A future JDK may turn the warning into an
  error, which the existing flag also covers.
- `-XX:StackShadowPages=32` (launcher) gives native code more stack headroom past Java frames. That is
  relevant for deep driver and compiler stacks. Keep it.

### 4.2 Downcall semantics that matter

- **Non-critical downcall:** the thread transitions Java → native → Java (`_thread_in_native`). GC and
  safepoints can proceed while native code runs. **Use this for anything that can block or take long:**
  `nextDrawable`, `waitUntilSignaledValue`, pipeline compilation, and the frame `submit`.
- **`Linker.Option.critical(false)`:** no thread-state transition. The JVM cannot reach a safepoint
  until the call returns, which stalls GC (ZGC included) and other threads waiting for one. Use it only
  for functions with a tiny, bounded runtime that **never call back into Java**, for example reading a
  timeline counter or a cached pointer.
- **`critical(true)`** additionally lets you pass **heap** segments (`MemorySegment.ofArray(byte[])`)
  without copying. Verified: the "heap byte[] → native consumer" variant works and costs the same as
  off-heap. Do **not** use it for a whole-frame encode of hundreds of µs: it would hold every safepoint
  for that long. Prefer an off-heap command stream.
- Upcalls (native → Java via `upcallStub`) cost far more than downcalls and are dangerous from Metal's
  internal dispatch queues (completion and feedback handlers), because they attach those threads to the
  JVM. **Design rule: no upcalls.** Native code reports through shared memory that Java polls (§8.6).
- ObjC exceptions or C++ exceptions must never unwind through an FFM frame (the JVM aborts). Every
  exported shim function wraps its body in `@try/@catch` and returns a status code.

### 4.3 Measured costs (verified; shipped JRE, `-XX:+UseZGC -XX:+UseCompactObjectHeaders`)

Trivial `int64 add(int64,int64)` in an ad-hoc dylib, 5M calls, best of 8 rounds, two separate runs:

| Path | ns/call (run A) | ns/call (run B) |
|---|---|---|
| JNI `static native` (`Java_Bench_jniAdd`) | 3.19 / 3.20 | 4.96 / 3.78 |
| FFM downcall | 2.87 / 2.82 | 4.19 / 3.34 |
| FFM downcall `critical(false)` | 1.44 / 1.73 | 2.73 / 2.19 |
| LWJGL `JNI.invokePPP` (FFM-backed on 25) | 2.81 / 2.88 | 3.98 / 3.44 |
| Java-only inlined add (baseline) | 0.48 | 0.57–0.63 |

Command-stream granularity, with 4096 records of 16 bytes consumed natively:

| Variant | per frame |
|---|---|
| 1 FFM call / frame (off-heap) | 1.09–1.30 µs |
| 1 FFM critical call / frame (heap `byte[]`) | 1.13–1.34 µs |
| 1 JNI call / frame | 1.09–1.41 µs |
| 4096 FFM calls / frame (1 record each) | 18.3–20.5 µs |

Objective-C messaging, `[MTL4ArgumentTable setAddress:atIndex:]`, 10M calls:

| Path | ns/call |
|---|---|
| Native ObjC (clang -O2, ARC) | 1.76–1.87 |
| Java → FFM → `objc_msgSend` | 3.95–5.85 |
| Java → FFM `critical` → `objc_msgSend` | 2.42–4.49 |

Native Metal encode cost per draw (CPU only, offscreen 256², trivial pipeline):

| Encoder style | ns/draw (20k draws) | ns/draw (2k draws) |
|---|---|---|
| Classic `setVertexBuffer:offset:atIndex:` + `drawPrimitives` | 47.0 | 48.2 |
| Classic `setVertexBufferOffset:atIndex:` + draw | 38.1 | 41.5 |
| Classic, `commandBufferWithUnretainedReferences` | 38.2 | 41.5 |
| **Metal 4** `MTL4ArgumentTable setAddress` + draw | 43.4 | 45.2 |
| Metal 4 `commit` + `signalEvent` | 0.002–0.004 ms total | |

End-to-end skeleton, Java → FFM → ObjC shim → Metal 4 (verified, §8.9): **~4000 draws, each with its own
buffer binding, decoded, encoded and committed in 0.213 ms CPU from one FFM call**, or about 53 ns per draw
including decoding.

How to read these numbers:
- A raw Java→native transition (≈3 ns) is only 5–10% of a single Metal draw's native CPU cost (≈40 ns).
  The transition itself is not the bottleneck.
- What scales badly is **per-call Java overhead**: method-handle dispatch, argument boxing and adaptation
  if `invokeExact` is not used, `MemoryStack` push/pop, LWJGL `Checks`, struct marshalling,
  `MemorySegment` allocation, plus one transition per Metal call (a single draw is usually 3–6 Metal
  calls). Recording into a packed stream costs ~1–2 ns per record in Java (plain stores) and moves
  everything else to native code.
- 1000 FPS means **1 ms per frame for everything**, including the game's own Java work. Encoding 5000
  draws natively takes ≈ 0.2–0.25 ms, so draw-count reduction (multi-draw, indirect command buffers,
  GPU culling; §10) matters more than interop micro-costs.

### 4.4 FFM coding rules for the backend

1. Keep each `MethodHandle` in a `static final` field and call it with `invokeExact` and exact casts
   (`(long) H.invokeExact(...)`). Then C2 inlines the downcall stub.
2. Pass pointers as `JAVA_LONG` where you control the C signature (`uint64_t`/`uintptr_t`). That avoids
   `MemorySegment` wrapping. Use `ADDRESS` plus `reinterpret(len)` only where you need a bounded view.
3. Never allocate an `Arena` per call. Use one long-lived `Arena.ofShared()`/`ofAuto()` per resource
   class, or a per-thread scratch segment, or LWJGL `MemoryStack` for creation-time out-params.
4. Do not pass structs by value across the ABI, even though FFM supports it on AArch64. Put descriptors
   in memory and pass a pointer.
5. Mark only trivial getters as `critical(false)`. `submit`, `acquire`, `present`, `compile` and `wait`
   must stay non-critical.
6. Map `MTLBuffer.contents` once, at creation:
   `MemorySegment.ofAddress(ptr).reinterpret(size)` for FFM, or `MemoryUtil.memByteBuffer(ptr, size)`.
   Java then writes vertex, uniform and staging data straight into GPU-visible unified memory with zero copies.
7. `jnativescan` (in `$JRE/bin`) can audit the patched jar for restricted-method use.

### 4.5 JNI as an alternative (not recommended)

- It needs `jni.h`, which this runtime does not ship (`$JRE/include` is absent). Use another JDK 25's
  headers or hand-declare the few types.
- Symbol naming is `Java_<pkg>_<Class>_<method>` or `RegisterNatives`, and native methods must be
  declared in Java.
- `JavaCritical_*` "critical natives" were removed in JDK 18. Heap arrays need
  `Get/ReleasePrimitiveArrayCritical`.
- Measured per-call cost is about the same as non-critical FFM (§4.3), with no cheaper "critical" tier.
- The only advantage is running on Java < 22, which is irrelevant here (the runtime is fixed at 25).

---

## 5. Can this JRE load an ad-hoc-signed dylib built with clang? Yes

- `java` runs under the **hardened runtime** (`flags=0x10000(runtime)`). Normally that enforces library
  validation: only Apple-signed or same-Team-ID code may load. **`com.apple.security.cs.disable-library-validation = true`**
  turns that off, so any validly signed dylib loads, ad-hoc included.
- On arm64, every Mach-O must carry a valid code signature. `ld` signs automatically
  (`flags=0x20002(adhoc,linker-signed)`, verified on `libbenchshim.dylib` and `libmcmtl.dylib`). No
  `codesign` step is needed **unless the binary is modified after linking** (`install_name_tool`,
  `strip`, `lipo` merges). In that case re-sign with `codesign -s - -f libmcmetal.dylib`, or the
  kernel kills the process on first page-in of an invalid page (SIGKILL "Code Signature Invalid").
- Verified loads of an ad-hoc dylib in the shipped `java`: `System.load` (JNI), `SymbolLookup.libraryLookup`
  (FFM), and LWJGL `Library.loadNative` (extracted from a jar).
- **Quarantine / Gatekeeper:** files the JVM writes, such as LWJGL extraction or a custom extractor, carry
  only `com.apple.provenance`, not `com.apple.quarantine` (verified with `xattr -l`), so Gatekeeper is not
  consulted. If a user copies a downloaded dylib by hand from a quarantined zip, `dlopen` can be refused
  ("not verified"). Ship the dylib **inside the jar** and extract at runtime, or tell users to run
  `xattr -d com.apple.quarantine`.
- `allow-dyld-environment-variables` means `DYLD_*` variables work for this binary (handy for
  diagnostics). Metal's own `MTL_*` environment variables are read by Metal, not dyld, and work regardless.
- `allow-jit` / `allow-unsigned-executable-memory` are for HotSpot's JIT and the FFM stub generator.
  The shim needs neither.
- Other runtimes such as a user-chosen Temurin, Zulu or Prism-bundled JDK may have different
  entitlements. Check them with `codesign -d --entitlements - <java>` and fail gracefully
  (`BackendCreationException`) if `dlopen` fails.

---

## 6. Toolchain and the shader compilation path

### 6.1 What works now

- Building the shim (verified):
  ```
  clang -fobjc-arc -O2 -dynamiclib -fvisibility=hidden -mmacosx-version-min=26.0 \
        -Werror=unguarded-availability-new \
        mcmetal.m -framework Metal -framework QuartzCore -framework Foundation [-framework MetalFX] \
        -install_name @rpath/libmcmetal.dylib -o libmcmetal.dylib
  ```
  - `-mmacosx-version-min=26.0` is the first OS with Metal 4 (`API_AVAILABLE(macos(26.0))` on every
    `MTL4*` symbol). With `-Werror=unguarded-availability-new`, use of a 27.0-only API (§7.11) without
    an `@available` check fails the build.
  - Export the C ABI with `__attribute__((visibility("default")))` and hide everything else. Check with
    `nm -gU` (verified: only `_mcmtl_*` were exported).
  - arm64 only is fine (Apple Silicon). The game's natives also come in `natives-macos` (x86_64) jars,
    but this target is an M5.
- **Runtime shader compilation works without the Metal Toolchain:**
  - `-[MTLDevice newLibraryWithSource:options:error:]` with MSL 4.0 took 108.9 ms cold.
  - `-[MTL4Compiler newLibraryWithDescriptor:error:]` with `MTL4LibraryDescriptor.source` (`$MTL/MTL4LibraryDescriptor.h:22-31`, `$MTL/MTL4Compiler.h:108`) took 0.02 ms on the same source, a cache hit.
  - `newRenderPipelineStateWithDescriptor:compilerTaskOptions:error:` took 20.7 ms (`$MTL/MTL4Compiler.h:170`).
  - The front-end compiler is part of the OS Metal framework. Only the *offline* `metal`/`metallib` CLI
    needs the downloadable component.
- System shader cache: Metal caches compiled libraries and functions per process identity under
  `$(getconf DARWIN_USER_CACHE_DIR)/net.java.openjdk.java/com.apple.metal/<gpu-id>/{libraries,functions}*.{list,data}`
  (verified). The key is the `java` binary's bundle identifier `net.java.openjdk.java`, so the cache is
  shared by every Java program run with this runtime. Second launches compile much faster automatically.

### 6.2 What needs a user action

- An offline `.metallib` build (precompiling MSL at build time) needs
  `xcodebuild -downloadComponent MetalToolchain`. This is a large download; **the user must run it**. It
  is not required for the design below.
- For deterministic warm starts without relying on the OS cache, use **`MTL4PipelineDataSetSerializer`**
  plus **`MTL4Archive`**:
  - `-[MTLDevice newPipelineDataSetSerializerWithDescriptor:]` (`$MTL/MTLDevice.h:1486`).
  - `MTL4CompilerDescriptor.pipelineDataSetSerializer` (`$MTL/MTL4Compiler.h:51`).
  - `serializeAsArchiveAndFlushToURL:error:` (`$MTL/MTL4PipelineDataSetSerializer.h:76`).
  - On the next run: `newArchiveWithURL:error:` (`$MTL/MTLDevice.h:1475`), then
    `MTL4CompilerTaskOptions.lookupArchives` (`$MTL/MTL4Compiler.h:75`) or
    `-[MTL4Archive newRenderPipelineStateWithDescriptor:...]` (`$MTL/MTL4Archive.h:90-107`).
  - Store the archive under the game directory and invalidate it on driver/OS build change (the archive
    must be "compatible with the Metal device", `$MTL/MTL4Compiler.h:72-74`).

### 6.3 Recommended shader path (no offline tools)

GLSL (the game's shaders) → SPIR-V (shaderc, already done by the frontend: `frontend/shaders/GlslCompiler.java:62-77`)
→ **SPIRV-Cross MSL** (`spvc_context_create_compiler(..., SPVC_BACKEND_MSL=3, ...)`, set `MSL_VERSION`,
explicit `[[buffer(n)]]/[[texture(n)]]/[[sampler(n)]]` remaps via `spvc_compiler_msl_add_resource_binding_2`)
→ `MTL4Compiler` **asynchronous** pipeline creation:
`newRenderPipelineStateWithDescriptor:compilerTaskOptions:completionHandler:` returns `id<MTL4CompilerTask>`
(`$MTL/MTL4Compiler.h:303-322`). Poll `task.status == MTL4CompilerTaskStatusFinished`
(`$MTL/MTL4CompilerTask.h:25-45`) from Java. That maps naturally onto
`GpuDeviceBackend.compilePipeline(...) → BackendRenderPipeline.Pending`
(`com/mojang/renderpearl/backend/api/GpuDeviceBackend.java:40`).

Optional fast variants: compile one pipeline with `MTL4BlendStateUnspecialized` (`$MTL/MTL4PipelineState.h:55-67`)
and derive blend variants with `newRenderPipelineStateBySpecializationWithDescriptor:pipeline:error:`
(`$MTL/MTL4Compiler.h:196-216`). Metal 4 binds by index. SPIRV-Cross output with explicit indices works
directly with argument tables (§7.3). Clip-space and Y-flip conventions are covered in the shader and
pipeline docs. SPIRV-Cross has `FIXUP_DEPTH_CONVENTION` and `FLIP_VERTEX_Y` if the frontend's
SPIR-V assumes GL conventions.

---

## 7. Metal 4 / macOS 27 SDK feature survey (header references)

Every `MTL4*` API is `API_AVAILABLE(macos(26.0))`. macOS 27-only additions are listed in §7.11.

### 7.1 Command model: queue, allocator, command buffer

| API | Header | Notes for the backend |
|---|---|---|
| `-[MTLDevice newMTL4CommandQueue]` / `newMTL4CommandQueueWithDescriptor:error:` | `$MTL/MTLDevice.h:1401,1413` | `MTL4CommandQueueDescriptor.feedbackQueue` must be a **serial** dispatch queue, or nil for an internal one (`$MTL/MTL4CommandQueue.h:80-86`) |
| `-[MTLDevice newCommandAllocator]` / `...WithDescriptor:error:` | `$MTL/MTLDevice.h:1382,1394` | Owns encoding memory. **One command buffer at a time per allocator** (`$MTL/MTL4CommandBuffer.h:59-61`). `reset` only after every command buffer that used it has **completed on the GPU** (`$MTL/MTL4CommandAllocator.h:44-50`). Use one allocator per frame-in-flight per encoding thread. `allocatedSize` (`:41`) reached 7.6 MiB for 20k draws in the benchmark |
| `-[MTLDevice newCommandBuffer]` | `$MTL/MTLDevice.h:1420` | **Reusable** long-lived object, not autoreleased per frame |
| `beginCommandBufferWithAllocator:` / `...:options:` | `$MTL/MTL4CommandBuffer.h:71,95` | Resets earlier `useResidencySet:` calls (`:66-68`). `MTL4CommandBufferOptions.logState` (`:40`) enables shader `os_log` |
| `endCommandBuffer` | `$MTL/MTL4CommandBuffer.h:103` | Required before `commit` |
| `renderCommandEncoderWithDescriptor:[options:]` | `$MTL/MTL4CommandBuffer.h:110,124` | `MTL4RenderEncoderOptionSuspending/Resuming` (`$MTL/MTL4RenderCommandEncoder.h:38-53`) splits one render pass across several command buffers or threads. Commit order rules are at `$MTL/MTL4CommandQueue.h:220-234` |
| `computeCommandEncoder` | `$MTL/MTL4CommandBuffer.h:130` | **Metal 4 has no separate blit encoder.** Copies, fills, mipmaps and ICB ops are on `MTL4ComputeCommandEncoder` (`copyFromTexture...` `:172-336`, `copyFromBuffer...` `:355-442`, `generateMipmapsForTexture:` `:543`, `fillBuffer:` `:552`, `optimizeContentsForGPUAccess:` `:568`) |
| `useResidencySet(s):` | `$MTL/MTL4CommandBuffer.h:142,151` | Per-command-buffer residency |
| `writeTimestampIntoHeap:atIndex:` / `resolveCounterHeap:...` | `$MTL/MTL4CommandBuffer.h:172,206` | §7.8 |
| `-[MTL4CommandQueue commit:count:]` / `commit:count:options:` | `$MTL/MTL4CommandQueue.h:234,257` | Batch-commit an array. `MTL4CommitOptions addFeedbackHandler:` (`:63-69`) |
| `MTL4CommitFeedback` `.error`, `.GPUStartTime`, `.GPUEndTime` | `$MTL/MTL4CommitFeedback.h:31-40` | Replaces `MTLCommandBuffer` completion handlers and status. **Metal 4 command buffers have no `addCompletedHandler`.** Use feedback handlers or shared events |
| `signalEvent:value:` / `waitForEvent:value:` | `$MTL/MTL4CommandQueue.h:266,274` | GPU timeline. With `MTLSharedEvent` (`$MTL/MTLEvent.h:55-64`: `notifyListener:atValue:block:`, `waitUntilSignaledValue:timeoutMS:`, `signaledValue`), this gives CPU↔GPU sync |
| `waitForDrawable:` / `signalDrawable:` | `$MTL/MTL4CommandQueue.h:308,294` | **Mandatory for presenting with Metal 4.** Call `waitForDrawable` before committing work that targets the drawable, then `signalDrawable` after committing it and **before** `[drawable present]`. `signalDrawable` fails if called after present or twice (`:280-294`) |
| `addResidencySet(s):` / `removeResidencySet(s):` | `$MTL/MTL4CommandQueue.h:318-349` | **Max 32 residency sets per queue** (`:315,325`) |
| `updateTextureMappings:` / `updateBufferMappings:` / `copy...Mappings` | `$MTL/MTL4CommandQueue.h:368-427` | Sparse and placement-sparse mapping updates on the queue timeline. Needs a barrier on `MTLStageResourceState` (`:91-93`) |

### 7.2 Encoders, barriers and hazards

- `MTL4CommandEncoder` (`$MTL/MTL4CommandEncoder.h:38-167`):
  - `barrierAfterQueueStages:beforeStages:visibilityOptions:` (`:70`): consumer barrier against earlier encoders.
  - `barrierAfterStages:beforeQueueStages:visibilityOptions:` (`:91`): producer barrier.
  - `barrierAfterEncoderStages:beforeEncoderStages:visibilityOptions:` (`:113`): intra-pass.
  - `updateFence:afterEncoderStages:` / `waitForFence:beforeEncoderStages:` (`:130,148`).
  - Debug groups and signposts.
  - `MTL4VisibilityOptions`: `None`, `Device` (cache flush), `ResourceAlias` (`:19-32`).
- `MTLStages` bits (`$MTL/MTLCommandEncoder.h:45-75`): Vertex, Fragment, Tile, Object, Mesh, ResourceState, Dispatch, Blit, AccelerationStructure, MachineLearning, All.
- **Invariant: Metal 4 does no automatic hazard tracking and does not retain resources.** The backend
  must insert barriers for every RAW, WAR and WAW dependency between encoders: copy → sample,
  render-to-texture → sample, and compute-write → vertex-fetch. It must also keep every object alive
  until the GPU timeline passes its last use.
- Starter policy: one `barrierAfterQueueStages:MTLStageAll beforeStages:<this encoder's stages>` at the start of each encoder
  (as in the verified skeleton, §8.9). Minecraft's passes are mostly sequential, so this costs little.
  Refine later using RenderPearl usage flags.

### 7.3 Render encoder (`$MTL/MTL4RenderCommandEncoder.h`)

- State:
  - `setRenderPipelineState:` (`:80`), `setViewport(s)` (`:88,101`), `setCullMode:` (`:122`),
    `setDepthClipMode:` (`:127`), `setDepthBias...` (`:138`).
  - `setDepthTestMinBound:maxBound:` (`:152`), `setScissorRect(s)` (`:160,171`), `setTriangleFillMode:` (`:178`),
    `setBlendColor...` (`:187`), `setDepthStencilState:` (`:195`), stencil reference (`:202,214`).
  - `setVisibilityResultMode:offset:` (`:228`), `setFrontFacingWinding:` (`:628`).
  - Late store actions `setColorStoreAction:atIndex:` / `setDepthStoreAction:` / `setStencilStoreAction:` (`:237-250`).
- **Bindings: `setArgumentTable:atStages:` (`:621`).** There is **no `setVertexBuffer`, `setVertexBytes` or `setFragmentTexture`**.
  Metal snapshots the argument table **at each draw** (`:615-616`). Mutating the table between draws
  is therefore the binding model. `setVertexBytes`-style inline uniforms must go through a transient
  ring buffer plus `setAddress`.
- Draws:
  - `drawPrimitives:vertexStart:vertexCount:[instanceCount:[baseInstance:]]` (`:264,286,312`).
  - `drawIndexedPrimitives:indexCount:indexType:indexBuffer:(MTLGPUAddress)indexBufferLength:[instanceCount:[baseVertex:baseInstance:]]` (`:341,376,418`).
    The index buffer is a **GPU address** that must be 2- or 4-byte aligned, and indices read past
    `indexBufferLength` yield `vertex_id 0` (`:330-340`). There is no `indexBufferOffset`; add the
    offset to the address.
  - Indirect: `drawPrimitives:indirectBuffer:` (`:446`) and `drawIndexedPrimitives:...indirectBuffer:` (`:484`).
    These take one indirect record and no count buffer.
  - **Multi-draw:** loop natively in the shim, or use ICBs `executeCommandsInBuffer:withRange:` / `...indirectBuffer:` (`:504,532`).
  - Mesh: `drawMeshThreadgroups:...` / `drawMeshThreads:` / `drawMeshThreadgroupsWithIndirectBuffer:` (`:552-587`).
  - Tile: `dispatchThreadsPerTile:` (`:598`).
  - Timestamps: `writeTimestampWithGranularity:afterStage:intoHeap:atIndex:` (`:645`).
- Render pass: `MTL4RenderPassDescriptor` (`$MTL/MTL4RenderPass.h:30-126`). It reuses classic
  `MTLRenderPassColorAttachmentDescriptorArray` and the depth and stencil attachment descriptors,
  `renderTargetWidth/Height` (`:77,85`), `visibilityResultBuffer` (`:97`) and `supportColorAttachmentMapping` (`:126`).

### 7.4 Argument tables, GPU addresses and resource IDs

- `MTL4ArgumentTableDescriptor` (`$MTL/MTL4ArgumentTable.h:22-53`): **`maxBufferBindCount` ≤ 31,
  `maxTextureBindCount` ≤ 128, `maxSamplerStateBindCount` ≤ 16**, plus `initializeBindings` and
  `supportAttributeStrides` (needed for `setAddress:attributeStride:atIndex:`).
- `MTL4ArgumentTable`: `setAddress:atIndex:` (`:69`), `setAddress:attributeStride:atIndex:` (`:84`),
  `setResource:atBufferIndex:` (`:96`), `setTexture:(MTLResourceID)atIndex:` (`:107`), `setSamplerState:atIndex:` (`:118`).
- Handles come from `MTLBuffer.gpuAddress` (`$MTL/MTLBuffer.h:124`), `MTLTexture.gpuResourceID`
  (`$MTL/MTLTexture.h:499`) and `MTLSamplerState.gpuResourceID` (`$MTL/MTLSampler.h:249`). These are
  plain 64-bit integers. **Java can hold them and write them straight into the command stream**, so the
  native decoder needs no handle-table lookup for bindings.
- Lightweight texture views: `-[MTLDevice newTextureViewPoolWithDescriptor:error:]` (`$MTL/MTLDevice.h:1447`).
  `MTLTextureViewPool setTextureView:[descriptor:]atIndex:` returns an `MTLResourceID` without allocating
  an `MTLTexture` object (`$MTL/MTLTextureViewPool.h:29-60`). This is a good fit for
  `GpuDeviceBackend.createTextureView(texture, baseMip, mipLevels)` (`GpuDeviceBackend.java:30`).

### 7.5 Residency sets (`$MTL/MTLResidencySet.h`)

- `-[MTLDevice newResidencySetWithDescriptor:error:]` (`$MTL/MTLDevice.h:1328`). The protocol provides
  `requestResidency`, `endResidency`, `addAllocation(s)`, `removeAllocation(s)`, `removeAllAllocations`,
  `containsAllocation`, `allocationCount`, and **`commit`** (`:70-136`). Adds and removes have no effect
  until `commit`.
- Metal 4 requires every resource a command buffer touches to be resident through a residency set.
  That covers argument-table entries, index buffers, indirect buffers and render targets. Policy:
  1. One global set holds every long-lived allocation and is attached to the queue once (`addResidencySet:`).
  2. Batch adds and removes, and `commit` once per frame before submit.
  3. Add **`CAMetalLayer.residencySet`** (`$QC/CAMetalLayer.h:171`, read-only, must not be modified) to the queue so drawables are resident.
- Allocating from a few large `MTLHeap`s and adding the heaps (not each resource) keeps the set small.

### 7.6 Memory: storage modes, heaps, placement sparse

- Unified memory: `MTLStorageModeShared` buffers are CPU- and GPU-visible with a stable `contents`
  pointer. Verified: `gpuAddress=0x10000080000`, and `contents` is a normal pointer.
  - Use `MTLResourceCPUCacheModeWriteCombined` for **write-only** upload rings. Never read WC memory from Java.
  - Use `MTLResourceHazardTrackingModeUntracked` (moot under Metal 4).
- `MTLStorageModePrivate` for render targets and atlases (enables lossless compression and better bandwidth).
  `MTLStorageModeMemoryless` for attachments that are never stored (MSAA, transient depth).
- Heaps: `MTLHeapTypePlacement` (`$MTL/MTLHeap.h:26-35`) with `newBufferWithLength:options:offset:` (`:225-228`).
  Use `heapBufferSizeAndAlignWithLength:options:` / `heapTextureSizeAndAlignWithDescriptor:` (`$MTL/MTLDevice.h:664,671`) to sub-allocate.
- **Placement sparse buffers:** `newBufferWithLength:options:placementSparsePageSize:` (`$MTL/MTLDevice.h:1494-1504`)
  creates a virtual buffer whose pages map to a placement heap via
  `-[MTL4CommandQueue updateBufferMappings:heap:operations:count:]` (`$MTL/MTL4CommandQueue.h:406`).
  For chunk geometry this gives one huge GPU address range (one base address for all sections) without
  fragmentation. Sparse tile size is 16 KiB here (`sparseTileSizeInBytes=16384`).

### 7.7 Compilation (`$MTL/MTL4Compiler.h`, `$MTL/MTL4CompilerTask.h`)

- `-[MTLDevice newCompilerWithDescriptor:error:]` (`$MTL/MTLDevice.h:1463`).
- Synchronous: `newLibraryWithDescriptor:error:` (`:108`), `newDynamicLibrary...` (`:117,126`),
  `newComputePipelineStateWithDescriptor...` (`:137,151`), `newRenderPipelineStateWithDescriptor...` (`:170,190`),
  `...BySpecialization...` (`:213`), `newBinaryFunctionWithDescriptor...` (`:227`).
- Asynchronous variants with completion handlers return `id<MTL4CompilerTask>` (`:238-359`). Task API:
  `status` (None/Scheduled/Compiling/Finished), `waitUntilCompleted` (`$MTL/MTL4CompilerTask.h:25-48`).
- Pipeline descriptors:
  - `MTL4RenderPipelineDescriptor` (`$MTL/MTL4RenderPipeline.h:156-229`): `vertex/fragmentFunctionDescriptor`
    (an `MTL4LibraryFunctionDescriptor{library,name}`), classic `MTLVertexDescriptor vertexDescriptor`
    (`:171`), `rasterSampleCount`, `colorAttachments` with `blendingState` (`:54`),
    `inputPrimitiveTopology` (`:199`), `supportIndirectCommandBuffers` (`:226`).
  - `MTL4PipelineOptions.shaderValidation` / `shaderReflection` (`$MTL/MTL4PipelineState.h:84-90`).
- Pipeline objects are ordinary `id<MTLRenderPipelineState>`. They also work with classic encoders.

### 7.8 Counters and timestamps

- `MTL4CounterHeapDescriptor{type=MTL4CounterHeapTypeTimestamp,count}` → `-[MTLDevice newCounterHeapWithDescriptor:error:]`
  (`$MTL/MTLDevice.h:1516`). `MTL4TimestampHeapEntry{uint64_t timestamp}` (`$MTL/MTL4Counters.h:18-21`).
- Granularity is `Relaxed` (may sample at encoder boundaries) or `Precise` (may split encoders) (`$MTL/MTL4Counters.h:36-50`).
- `-[MTL4CounterHeap resolveCounterRange:]` returns `NSData` (`:92`); there is also `invalidateCounterRange:` (`:102`).
- `-[MTLDevice queryTimestampFrequency]` (`$MTL/MTLDevice.h:1540`) returned **24,000,000**.
  `sizeOfCounterHeapEntry:` (`:1534`) returned 8.
- CPU/GPU correlation: `sampleTimestamps:gpuTimestamp:` (`$MTL/MTLDevice.h:1179-1185`). The CPU value
  is in `mach_absolute_time` units, the same base HotSpot uses for `System.nanoTime()` on macOS.
- Mapping to RenderPearl:
  - `GpuQueryPool` (`com/mojang/renderpearl/api/commands/GpuQueryPool.java:6-12`) and
    `writeTimestamp` (`RenderPassBackend.java:48`, `CommandEncoderBackend.java:69`) map to counter-heap writes.
  - `getTimestampCalibrationOffset()` (`GpuDeviceBackend.java:46`) returns `hostNanos - gpuTicks*period`,
    as `VulkanDevice.getTimestampCalibrationOffset()` does (`com/mojang/renderpearl/backend/vulkan/VulkanDevice.java:276-300`).
  - `TracyGpuProfiler` uses the period from `DeviceInfo.timestampPeriod()`
    (`com/mojang/renderpearl/frontend/TracyGpuProfiler.java:24-28`), so report 41.6667.

### 7.9 Indirect command buffers, mesh shaders, ray tracing

- ICB:
  - `MTLIndirectCommandBufferDescriptor` (`$MTL/MTLIndirectCommandBuffer.h:53-166`) gains in macOS 26
    `inheritDepthStencilState`, `inheritDepthBias`, `inheritDepthClipMode`, `inheritCullMode`,
    `inheritFrontFacingWinding` and `inheritTriangleFillMode` (`:78-103`).
  - Other descriptor properties: `supportDynamicAttributeStride` (`:163`), `supportColorAttachmentMapping` (`:166`).
  - Create with `-[MTLDevice newIndirectCommandBufferWithDescriptor:maxCommandCount:options:]` (`$MTL/MTLDevice.h:996`).
  - Pipelines must set `supportIndirectCommandBuffers = Enabled`.
  - Execute with `executeCommandsInBuffer:withRange:` / `...indirectBuffer:` (`$MTL/MTL4RenderCommandEncoder.h:504,532`).
    Reset, copy and optimize on the compute encoder (`$MTL/MTL4ComputeCommandEncoder.h:627-650`).
  - **This is the main tool for "1000 FPS": a compute pass culls chunk sections and writes draws into
    an ICB, then one execute call renders them. CPU cost becomes independent of section count.**
- Mesh shaders: `MTL4MeshRenderPipelineDescriptor` (`$MTL/MTL4MeshRenderPipeline.h:29-228`) with object, mesh and
  fragment function descriptors, `payloadMemoryLength`, and `maxTotalThreadgroupsPerMeshGrid`. Draw with
  `drawMeshThreadgroups...` (§7.3). Supported on all Apple7+ GPUs, M5 included.
- Ray tracing:
  - `MTL4PrimitiveAccelerationStructureDescriptor` and triangle, bounding-box and motion geometry
    descriptors take `MTL4BufferRange` GPU addresses (`$MTL/MTL4AccelerationStructure.h:17-294`).
  - Build, refit, copy and compact on the compute encoder (`$MTL/MTL4ComputeCommandEncoder.h:686-814`).
  - `supportsRaytracingFromRender=1`, so intersection queries work in fragment shaders (for example RT shadows).

### 7.10 MetalFX (`$MFX`)

| Feature | Classic API | Metal 4 API | Device support (M5) |
|---|---|---|---|
| Spatial upscaler | `MTLFXSpatialScalerDescriptor newSpatialScalerWithDevice:` (`MTLFXSpatialScaler.h:68`) | `newSpatialScalerWithDevice:compiler:` (`:77`), `MTL4FXSpatialScaler encodeToCommandBuffer:(id<MTL4CommandBuffer>)` (`MTL4FXSpatialScaler.h:24-31`) | supportsDevice=1, supportsMetal4FX=1 (`:86,95`) |
| Temporal upscaler (TAAU) | `newTemporalScalerWithDevice:` (`MTLFXTemporalScaler.h:128`) | `newTemporalScalerWithDevice:compiler:` (`:137`), `MTL4FXTemporalScaler` (`MTL4FXTemporalScaler.h:26-33`) | 1/1, input scale 1.0–3.0 (`:145-171`) |
| Temporal denoised scaler (RT denoise + upscale) | `MTLFXTemporalDenoisedScaler.h` | `MTL4FXTemporalDenoisedScaler.h:27-34` | 1/1 |
| **Frame interpolation** | `MTLFXFrameInterpolatorDescriptor newFrameInterpolatorWithDevice:` (`MTLFXFrameInterpolator.h:90`) | `...WithDevice:compiler:` (`:99`), `MTL4FXFrameInterpolator` (`MTL4FXFrameInterpolator.h:27-34`) | 1/1 (`:108,117`) |

- Temporal scaler inputs (`MTLFXTemporalScaler.h:216-371`): color, depth, motion, exposure and
  reactive-mask textures, `jitterOffsetX/Y`, `motionVectorScaleX/Y`, `reset`, `depthReversed`,
  `inputContentWidth/Height`, `preExposure`, and `fence`.
- Frame interpolator inputs (`MTLFXFrameInterpolator.h:155-363`): color, `prevColorTexture`, depth,
  motion and `uiTexture` (UI drawn separately and composited), `deltaTime`, `nearPlane/farPlane`,
  `fieldOfView`, `aspectRatio`, jitter, and `shouldResetHistory`.
  It can be chained with a temporal scaler (`scaler` property, `:53`).
- Integration cost for Minecraft:
  - Spatial upscale needs only the color target, applied before the GUI. It is cheap to adopt.
  - Temporal upscale and frame interpolation need **per-pixel motion vectors and projection jitter**
    from the game's world shaders, plus a separate UI layer. That is a substantial game-side change.

### 7.11 New in the macOS 27 SDK (`API_AVAILABLE(macos(27.0))`)

- Compute pipeline: `MTL4ComputePipelineDescriptor.forwardProgressUsage`, `contentionRelief` and
  `optimizeForPersistentKernel` (`$MTL/MTL4ComputePipeline.h:63-65`; `$MTL/MTLComputePipeline.h:153-155,298-299`).
  Enums are in `$MTL/MTLPipeline.h:33-37` and `$MTL/MTLTypes.h:105-109`. These help persistent-thread
  (GPU-driven) culling kernels.
- `MTLLanguageVersion4_1` (`$MTL/MTLLibrary.h:224`).
- `MTLCompileOptions.floatingPointConversionRoundingMode` (`$MTL/MTLLibrary.h:429`).
- `MTLTextureViewDescriptor.minLOD` / `MTLTexture.minLOD` (`$MTL/MTLTexture.h:320,599`): a texture-view LOD
  clamp. Useful for streaming or partially resident mip chains.
- 24-bit and 48-bit RGB pixel formats (`MTLPixelFormatRGB8Unorm=45` ... `RGB32Float=122`, `$MTL/MTLPixelFormat.h:56-208`).
  They are **only for `MTLTextureTypeTextureBuffer`** and cannot be written by shaders (`:48-55`), so
  they cannot be render targets.
- Tensors: auxiliary planes and new FP8/FP4 types (`$MTL/MTLTensor.h`). Not relevant.
- MetalFX:
  - Temporal scaler: `outputResolutionMotionVectorsEnabled`, `jitteredMotionVectorsEnabled`, and content
    and output offsets (`$MFX/MTLFXTemporalScaler.h:102,113,240-262`).
  - Frame interpolator: `distortionTexture(Enabled)`, `requiresPrevColorTexture`, `contentWidth/Height`
    and offsets, and `worldToViewMatrix/viewToClipMatrix` (`$MFX/MTLFXFrameInterpolator.h:73,82,192-228,304-306,363`).
- Deprecations: `depth24Stencil8PixelFormatSupported` (macOS 27, "Never supported on Apple Silicon"),
  `MTLGPUFamilyMac2` and `MTLGPUFamilyCommon*` (`$MTL/MTLDevice.h:246-250,553`).

### 7.12 Presentation (`$QC/CAMetalLayer.h`, `$QC/CAMetalDisplayLink.h`)

- `CAMetalLayer`:
  - Configuration: `device` (`:66`), `pixelFormat` (`:77`), `framebufferOnly` (`:87`; YES is required for
    best performance but forbids blitting into the drawable), and `drawableSize` (`:93`).
  - Acquisition: `nextDrawable` (`:102`) **blocks** (up to 1 s when `allowsNextDrawableTimeout`, `:152`)
    and returns an autoreleased object.
  - Pacing: `maximumDrawableCount` 2..3 (`:108`), `presentsWithTransaction` (`:116`), and
    `displaySyncEnabled` (`:143`). **Set it to NO for uncapped, tearing presentation (IMMEDIATE),
    which "1000 FPS" measurements need.**
  - Colour: `colorspace`, `wantsExtendedDynamicRangeContent` and `EDRMetadata` (`:122-137`).
  - Residency: `residencySet` (`:171`).
- `CAMetalDisplayLink` (`$QC/CAMetalDisplayLink.h:21-49`): a vsync-driven callback with a drawable and a
  target timestamp, plus `preferredFrameLatency` and `preferredFrameRateRange`. It needs a run loop and a
  callback into the app, which does not fit Minecraft's own loop. Prefer polling `nextDrawable`.
- RenderPearl's present modes are `IMMEDIATE, MAILBOX, FIFO, FIFO_RELAXED`, with vsync preference
  `{FIFO}` and no-vsync preference `{IMMEDIATE, MAILBOX, FIFO}` (`com/mojang/renderpearl/api/device/GpuSurface.java:29-36`). Map them as:
  - FIFO → `displaySyncEnabled=YES`.
  - IMMEDIATE → `displaySyncEnabled=NO`.
  - MAILBOX: not native. Emulate it with `displaySyncEnabled=YES`, 3 drawables, and never waiting on the
    CPU. Or omit it from `supportedPresentModes()` (`backend/api/GpuSurfaceBackend.java:20`).

---

## 8. Recommended interop architecture

### 8.1 Layering

```
 RenderPearl frontend (unchanged)
   │  GpuDeviceBackend / CommandEncoderBackend / RenderPassBackend / GpuSurfaceBackend  (backend/api/*.java)
   ▼
 com.mojang.renderpearl.backend.metal (Java, new)
   ├─ MetalBackend implements GpuBackend          → loads shim, createWindow(| SDL_WINDOW_METAL)
   ├─ MtlNative (static final MethodHandles, ~25 entry points, FFM)
   ├─ MtlCommandStream (off-heap MemorySegment / MemoryUtil address; plain stores)
   ├─ MtlBuffer/MtlTexture/MtlSampler/MtlPipeline (hold gpuAddress / MTLResourceID / retained ptr)
   └─ MtlTimeline (deferred destruction keyed by shared-event value; polls a shared-memory word)
   │  ~1 FFM downcall per submit (+ creation/destroy batches, acquire, present)
   ▼
 libmcmetal.dylib (Objective-C, ARC, C ABI)
   ├─ device/queue/compiler/residency/argument tables/allocators (Metal 4; optional classic fallback)
   ├─ command-stream decoder → MTL4 render/compute encoders, barriers, commit, signalEvent
   ├─ surface: CAMetalLayer from SDL_Metal_GetLayer, nextDrawable, waitForDrawable/signalDrawable/present
   └─ status page: shared memory block (completed timeline value, feedback errors, GPU times)
   ▼
 Metal.framework / QuartzCore / MetalFX
```

### 8.2 C ABI rules for the shim

1. **Pure C ABI** (`extern "C"`-style, `visibility("default")`) with scalars and pointers only. Version
   it with `uint32_t mcmtl_abi_version(void)` and check it in `loadLibrary()`.
2. **Every entry point is wrapped in `@autoreleasepool { @try { ... } @catch (NSException *e) { ... } }`.**
   Java threads (including the `-XstartOnFirstThread` main thread, which runs no NSRunLoop) have **no
   autorelease pool**. Without the wrapper, objects Metal and QuartzCore return autoreleased
   (`nextDrawable`, descriptors, `renderCommandEncoderWithDescriptor:` encoders, `NSError`) accumulate
   forever on the main thread's implicit pool.
3. **Errors** come back as a negative `int32_t` status plus a thread-local message
   (`const char *mcmtl_last_error(void)`), and GPU errors through the status page (§8.6). Map them to
   `BackendCreationException`, `GpuOutOfMemoryException` and `GpuDeviceLossException`
   (`com/mojang/renderpearl/api/device/*`).
4. **Object ownership:** objects created by the shim are returned as **+1 retained raw pointers**
   (`(__bridge_retained void*)obj`, read back with `(__bridge id)(void*)h`, freed via
   `mcmtl_release(h)` → `(__bridge_transfer id)`). Java stores them as `long`. Destruction is deferred
   until the GPU timeline passes the object's last use, because Metal 4 command buffers do not retain
   resources. For debugging, a generation-tagged handle table (32-bit index + generation) catches
   use-after-free cheaply.
5. **Bindings carry raw GPU identities:** Java stores `gpuAddress` for buffers and `gpuResourceID` for
   textures, views and samplers, and writes them into the stream. The decoder calls `setAddress` and
   `setTexture` with no lookups. Only pipeline and depth-stencil state objects need a pointer cast.
6. **Threading:**
   - The render thread owns the queue, the command buffer(s) and one allocator per frame in flight.
   - Resource creation is thread-safe in Metal. The shim's residency-set mutations are not, so guard them
     or queue them to the submit thread.
   - Optional: a native encoder thread that consumes the stream asynchronously (§10).
7. **No upcalls.** Metal's feedback handlers and `MTLSharedEventListener` blocks write into the status
   page (atomics). Java reads it with plain loads.

### 8.3 Suggested entry points (coarse)

| Function | Called | Notes |
|---|---|---|
| `mcmtl_init(const InitInfo*, DeviceInfo* out)` | once | device, MTL4 queue, compiler, global residency set, shared event, argument tables, status page pointer |
| `mcmtl_surface_create(void *caMetalLayer, const SurfaceCfg*)` / `_configure` | on window create or resize or vsync change | layer from `SDL_Metal_GetLayer(SDL_Metal_CreateView(window))`; sets device, pixelFormat, framebufferOnly, maximumDrawableCount, displaySyncEnabled, drawableSize; `addResidencySet:layer.residencySet` |
| `mcmtl_buffer_create(size, usage, storage, out cpuPtr, out gpuAddr) → h` | resource creation | Java maps `cpuPtr` as a `MemorySegment` |
| `mcmtl_texture_create(const TexDesc*, out resourceId) → h`, `mcmtl_texture_view(h, const ViewDesc*) → resourceId` | creation | views via `MTLTextureViewPool` |
| `mcmtl_sampler_create(const SamplerDesc*, out resourceId) → h` | creation | set `MTLSamplerDescriptor.supportArgumentBuffers=YES` (`$MTL/MTLSampler.h:213-216`) so its `gpuResourceID` (`:246-249`) is usable from argument tables (assumed required for MTL4 tables too; verify with the debug layer) |
| `mcmtl_pipeline_compile_async(const PipelineDesc*, msl strings) → taskHandle`; `mcmtl_task_poll(task, out pso)` | pipeline creation | `MTL4Compiler` async plus polling |
| `mcmtl_release_batch(const uint64_t *handles, n)` | once per frame | deferred frees whose timeline has passed |
| **`mcmtl_submit(const void *stream, uint64_t len, uint64_t *outTimeline)`** | **once per `CommandEncoderBackend.submit()`** | decodes all passes, ends, commits, `signalEvent` |
| `mcmtl_surface_acquire(surface)` | per frame | `nextDrawable` (blocking, **non-critical**), `waitForDrawable:` |
| `mcmtl_surface_present(surface)` | per frame | `signalDrawable:` after the last commit, then `[drawable present]` |
| `mcmtl_wait(value, timeoutMs)` | rarely | `waitUntilSignaledValue` (non-critical) |
| `mcmtl_completed_value()` | optional | `critical(false)` getter. Better: read the status page |

### 8.4 Command stream format

- A 16-byte-aligned off-heap byte stream of records `{u16 opcode; u16 sizeBytes; u32 a; u64 b; [u64 c; u64 d; ...]}`,
  little-endian (arm64), written with `MemorySegment.set(JAVA_LONG_UNALIGNED/...)` or `MemoryUtil.memPut*`.
  Variable-length payloads (multi-draw arrays, push constants, debug labels) follow the header inline.
- It is double- or triple-buffered per frame in flight. Each buffer is reused only after the timeline
  value of the submit that consumed it completes, though the decoder reads it synchronously anyway.
- Opcodes map 1:1 onto RenderPearl calls:
  - `RenderPassBackend` (`backend/api/RenderPassBackend.java:13-49`): `PUSH_DEBUG_GROUP` / `POP`,
    `SET_PIPELINE(pso ptr, depthStencil ptr, raster state bits)`, `SET_UNIFORM(slot, gpuAddr)`,
    `PUSH_CONSTANTS(inline bytes → transient ring → setAddress)`, `SCISSOR` / `SCISSOR_OFF`,
    `SET_VERTEX_BUFFER(slot, gpuAddr)`, `SET_INDEX_BUFFER(gpuAddr, len, type)`,
    `DRAW_INDEXED(count, inst, first, baseVertex, baseInst)`,
    `MULTI_DRAW_INDEXED(n, inline {first,count,baseVertex} array)`, `DRAW_INDEXED_INDIRECT(gpuAddr, n)`
    (looped natively, or an ICB), `DRAW` / `MULTI_DRAW` / `DRAW_INDIRECT`, and `TIMESTAMP(heap, idx)`.
  - `CommandEncoderBackend` (`backend/api/CommandEncoderBackend.java:13-70`): `BEGIN_RENDER_PASS(desc inline)` /
    `END_RENDER_PASS` (from `createRenderPass` / `submitRenderPass`), `CLEAR_COLOR` / `CLEAR_DEPTH`
    (load action, or a clear pass), `WRITE_BUFFER(dst gpu/ptr, src offset in an upload ring)`,
    `COPY_BUFFER`, `COPY_BUFFER_TO_TEXTURE`, `COPY_TEXTURE_TO_BUFFER` (plus a callback id completed
    through the status page), `COPY_TEXTURE_TO_TEXTURE`, and `FENCE(id)` → `signalEvent` value.
- `writeToBuffer` / `writeToTexture` do not need a native call. If the destination is a Shared buffer not
  in use by the GPU (timeline check), Java does a direct `memcpy` into the mapped contents. Otherwise it
  copies into a transient upload ring and records a copy op.

### 8.5 Memory model (unified memory)

- Shared buffers expose `contents`, a stable CPU pointer. The Java side keeps a `MemorySegment` view for
  the buffer's lifetime. Writes become GPU-visible at the next command buffer **submission**. Metal on
  Apple Silicon is coherent at command-buffer boundaries, so no `didModifyRange` is needed.
- Transient memory (`CommandEncoderBackend.transientMemory()`, `:16`) is a per-frame ring of one large
  Shared+WriteCombined `MTLBuffer` (for example 32–64 MiB × frames in flight). Allocate it at 256-byte
  alignment (uniforms) or 16 (vertices) and bind via `gpuAddress + offset`.
- Keep an eye on `recommendedMaxWorkingSetSize` (18 GiB here) and log `currentAllocatedSize`.

### 8.6 Synchronization and lifetime

- One `MTLSharedEvent` timeline:
  1. Each submit does `signalEvent:++T`.
  2. The status page's `completed` field is updated by `notifyListener:atValue:block:` (`$MTL/MTLEvent.h:55`) or read via `signaledValue` (`:64`).
  3. Before reusing frame slot *k*'s allocator, transient ring segment or command-stream buffer, wait
     for `completed ≥ T(k)` (`waitUntilSignaledValue`, non-critical) only if not already reached.
- `GpuFence` (`CommandEncoderBackend.createFence()`, `:67`) = `{event, value}`.
- Deferred destruction: `destroy()` from Java enqueues `(handle, T_current)`. The per-frame
  `mcmtl_release_batch` frees entries whose value has completed and removes them from the residency set.
- The status page also carries `MTL4CommitFeedback.error` codes and the GPU start/end times written by
  the feedback handler. Java turns `MTLCommandBufferErrorDeviceRemoved` and similar errors into
  `GpuDeviceLossException`.

### 8.7 Surface and present sequence (Metal 4)

```
acquire:  @autoreleasepool { drawable = [layer nextDrawable]; (retain it) ; [queue waitForDrawable:drawable]; }
encode:   ... frame passes ...; final pass renders/blits into drawable.texture (blitFromTexture)
submit:   [queue commit:cbs count:n]; [queue signalDrawable:drawable]; [queue signalEvent:ev value:++T];
present:  [drawable present]; release drawable
```

- `GpuSurfaceBackend.blitFromTexture` (`backend/api/GpuSurfaceBackend.java:16`): with
  `framebufferOnly=YES`, use a fullscreen-triangle render pass, not `copyFromTexture`. Run a MetalFX
  spatial or temporal upscale here when render scale is below 1.
- High-DPI: Minecraft creates the window with `SDL_WINDOW_HIGH_PIXEL_DENSITY` (`Window.java:164-165`),
  so SDL's Metal view sets `drawableSize` in **pixels** (up to 3024x1964). The backend must use pixel
  sizes (`SDL_GetWindowSizeInPixels`) for its render targets.
- Main-thread requirement: `SDL_Metal_CreateView` and any NSView or CALayer property changes must run on
  the AppKit main thread. That is Minecraft's render thread (§2.1), so call them directly.

### 8.8 Choice of Java binding technique

| Technique | Pros | Cons | Verdict |
|---|---|---|---|
| **Raw FFM `static final MethodHandle`s into the shim** | Standard and final API; `critical` available; no extra build-time deps; ~3 ns per call | ~25 hand-written descriptors | **Recommended** |
| LWJGL `JNI.invoke*` + `SharedLibrary.getFunctionAddress` | Zero FFM boilerplate; same cost on 25 | Fixed signature set; no critical | Fine for a prototype |
| LWJGL `FFM.ffmGenerate(interface)` | Annotation-driven; supports critical overrides | New LWJGL-internal API surface (3.4+) | Optional |
| JNI | — | No `jni.h` shipped; no cost advantage | No |
| Pure Java → `objc_msgSend` (no shim; verified working) | No native build at all | 4–6 ns per message; manual autorelease pools (`objc_autoreleasePoolPush/Pop`); no blocks (feedback handlers, completion handlers) without hand-building block ABI; error-prone signatures | Only as a fallback if clang were unavailable (it is available) |

### 8.9 Verified skeleton (evidence)

The scratchpad files `mcmtl_skel.m` (≈150 lines) and `SkelTest.java` implement §8.2–8.4 in miniature:
Metal 4 queue, 3 allocators, one reusable `MTL4CommandBuffer`, argument table, global residency set,
shared event, `MTL4Compiler`, a 32-byte-record stream decoder (BEGIN_PASS / SET_PIPELINE / SET_BUFFER /
DRAW / END_PASS), `@autoreleasepool` + `@try` in every entry point, and retained-pointer handles. Results
on the shipped JRE (`--enable-native-access=ALL-UNNAMED -XstartOnFirstThread -XX:+UseZGC`):

```
timeline=1 wait=0 completed=1 centerPixel(BGRA LE)=0xff00ff00 -> GREEN (triangle drawn) OK
submit of ~4000 draws via one FFM call: best 0.213 ms CPU (incl. decode+encode+commit)
```

Vertices were written by Java directly into the `MTLBuffer` contents through
`MemorySegment.reinterpret` (zero-copy), and the buffer was bound by its `gpuAddress` taken from Java.

---

## 9. Packaging and loading the dylib with a patched jar

1. **Location in the jar:** `macos/arm64/com/mojang/renderpearl/backend/metal/libmcmetal.dylib`.
   Optionally add `META-INF/macos/arm64/com/mojang/renderpearl/backend/metal/libmcmetal.dylib.sha1`, as
   LWJGL's natives jars do.
2. **Load in `MetalBackend.loadLibrary()`:**
   ```java
   SharedLibrary lib = Library.loadNative(MetalBackend.class, "com.mojang.renderpearl.backend.metal", "mcmetal", true);
   SymbolLookup look = SymbolLookup.libraryLookup(Path.of(lib.getPath()), Arena.global()); // or wrap lib::getFunctionAddress
   MethodHandle submit = Linker.nativeLinker().downcallHandle(look.findOrThrow("mcmtl_submit"),
           FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG));
   ```
   - LWJGL extracts the file into `${natives_directory}/lwjgl/3.4.3+4/arm64/` (the directory
     `NativeLibrariesBootstrap` already verified as writable and lockable, §2.2).
   - Load failures appear in the LWJGL debug log. Catch `UnsatisfiedLinkError` and rethrow as
     `BackendCreationException` so the frontend falls back to GL or Vulkan.
   - A second `dlopen` of the same path (`libraryLookup`) only bumps the refcount and returns the same handle.
   - An alternative, jtracy-style self-extractor (`getResourceAsStream` → content-hashed file name →
     `System.load` / `libraryLookup`) avoids depending on LWJGL internals. LWJGL's version gives locking
     and crash-report integration for free.
3. **Strip Mojang's jar signature.** The client jar is signed: `META-INF/MOJANGCS.SF`, `MOJANGCS.RSA`, and
   `MANIFEST.MF` with a `SHA-384-Digest` per entry (visible in `src/vanilla/META-INF/`). With modified
   classes, the JVM throws `SecurityException` (digest mismatch, or "signer information does not match"
   for mixed packages). Delete `META-INF/*.SF`, `*.RSA` and the per-entry manifest sections, keeping
   `Main-Class`.
4. **Signing of the dylib:** linker ad-hoc signing is enough (§5). Re-sign with `codesign -s - -f` after
   any post-link edit. Check with `codesign -v libmcmetal.dylib`.
5. **Launcher arguments:** none are needed beyond vanilla's (`--enable-native-access=ALL-UNNAMED` is
   already present). To select the backend, add a `METAL` value to `PreferredGraphicsApi`
   (`net/minecraft/client/PreferredGraphicsApi.java:10-37`) so `--graphicsBackend metal` and the options
   screen work (`Main.java:82-84,203`).
6. **Fallback and robustness:** check `[device supportsFamily:MTLGPUFamilyMetal4]` and macOS ≥ 26 at
   init (`mcmtl_init` returns an error string). Keep an optional classic-Metal path (MTL3 encoders with
   automatic hazard tracking) to debug suspected barrier bugs.

---

## 10. Debugging and profiling hooks

- Environment variables (Metal reads them; all work under this `java`):
  - `MTL_DEBUG_LAYER=1` (API validation).
  - `MTL_SHADER_VALIDATION=1` (GPU-side out-of-bounds checks; slow).
  - `MTL_HUD_ENABLED=1` (Metal Performance HUD: FPS, GPU time and memory, the quickest way to measure).
  - `METAL_DEVICE_WRAPPER_TYPE=1`.
  - `MTL_CAPTURE_ENABLED=1` for programmatic GPU capture.
- GPU capture: `MTLCaptureManager newCaptureScopeWithMTL4CommandQueue:` (`$MTL/MTLCaptureManager.h:85`),
  `supportsDestination:` (`:88`), `MTLCaptureDestinationGPUTraceDocument` (`:38`). Expose a debug key
  that captures one frame to a `.gputrace` file.
- Shader logging: `MTL4CommandBufferOptions.logState` (`$MTL/MTL4CommandBuffer.h:40`) with
  `newLogStateWithDescriptor:error:` (`$MTL/MTLDevice.h:637`).
- Pipeline validation: `MTL4PipelineOptions.shaderValidation` (`$MTL/MTL4PipelineState.h:87`).
- Labels: set `label` on queue, command buffer, encoders and resources from RenderPearl debug labels
  (`--renderDebugLabels`, `Main.java:80`). Push and pop debug groups go through the stream.
- Tracy: the frontend creates the GPU context as `GpuApi.OPENGL` with period 1.0 and converts with
  `DeviceInfo.timestampPeriod()` (`frontend/TracyGpuProfiler.java:24-28`). jtracy's `GpuApi` has no
  Metal value (`com.mojang.jtracy.GpuApi`: INVALID, OPENGL, VULKAN, OPENCL, D3D12, D3D11). This is
  harmless as long as the backend's timestamps are converted correctly.
- Instruments "Metal System Trace" works on the `java` process (the `cs.debugger` entitlement is present).

---

## 11. Pitfalls checklist (Metal backend implementer)

1. Wrap every shim entry point in `@autoreleasepool` and `@try/@catch`. Never let an exception cross
   into Java.
2. Never mark blocking or long calls `critical`. That covers `nextDrawable`, `waitUntilSignaledValue`,
   `submit` and compile; a critical call stalls ZGC safepoints for its whole duration.
3. Metal 4 does **not retain** resources and does **not track hazards**. Keep resources alive until the
   timeline passes, and insert barriers between dependent encoders.
4. Everything the GPU touches must be in a **committed residency set**, including the drawable
   (`layer.residencySet`), index and indirect buffers, and render targets.
5. Reset an `MTL4CommandAllocator` only after the GPU finished every command buffer encoded from it.
   Use one allocator per thread per frame in flight.
6. Present order: `waitForDrawable` before committing work that writes the drawable, then
   `signalDrawable` after the last such commit, then `present`. Calling `signalDrawable` twice or after
   `present` fails.
7. There is no `setVertexBytes` in Metal 4. Push constants and small uniforms go through a transient ring
   plus `setAddress`.
8. The argument-table snapshot happens at **draw** time. Mutate the table between draws, not after.
9. Index buffer addresses must be 2- or 4-byte aligned. Out-of-range reads give `vertex_id 0` rather than
   an error.
10. D24S8 is unavailable. Use `Depth32Float(_Stencil8)`.
11. Timestamps are 24 MHz ticks. Convert them before handing them to the frontend or Tracy.
12. `CAMetalLayer` and SDL view calls belong on the main (render) thread. With
    `displaySyncEnabled=YES`, FPS is capped at the display refresh rate (ProMotion up to 120 Hz).
13. Patched jar: strip `MOJANGCS.*`. Dylib modified after linking: re-sign ad-hoc. Downloaded loose
    dylib: remove quarantine.
14. In this workspace, `mc/versions/26.3/natives/` contains x86_64 LWJGL dylibs (§2.3). Do not use it as a
    library path.
15. Write-combined Shared buffers are write-only from Java. Reading them back is extremely slow.
16. Use `-mmacosx-version-min=26.0` for Metal 4. Guard 27.0-only APIs with `@available(macOS 27, *)`.

---

## 12. Where the FPS comes from (interop-related)

- **One downcall per submit with a packed stream** (verified at 0.21 ms for 4000 draws including encode)
  instead of 3–6 LWJGL/FFM calls per draw. That cuts both transitions and per-call Java overhead.
- **Zero-copy uploads:** Java writes chunk meshes, the immediate-mode `BufferBuilder` output and uniforms
  directly into Shared `MTLBuffer` memory. No `glBufferSubData`-style copies and no staging round trip
  for buffers.
- **Asynchronous native encoding:** hand the frame's stream to a native encoder thread (or use Metal 4
  suspend/resume render passes across threads, `$MTL/MTL4RenderCommandEncoder.h:38-53`) so the Java
  render thread starts the next frame immediately. At 1 ms frames this overlaps 0.2+ ms of encode.
- **GPU-driven chunk rendering:** a compute pass does frustum and Hi-Z occlusion culling, writes an ICB
  or indirect args, and one `executeCommandsInBuffer` call follows. CPU cost becomes O(1) in visible
  sections. Combine with a placement-sparse mega-buffer for all section geometry (one base address).
- **Mesh shaders for terrain:** expand compact per-face records into quads on the GPU. That shrinks
  vertex memory, upload volume and bandwidth.
- **Upscaling on the 3024x1964 Retina panel:** MetalFX spatial (color only, cheap to integrate) at a 0.67–0.75 render scale,
  or temporal (needs motion vectors and jitter), cuts fragment cost roughly 2x at similar quality.
  Frame interpolation can double presented FPS but adds latency.
- **Uncapped presentation:** `displaySyncEnabled=NO` and `maximumDrawableCount=3` for IMMEDIATE mode.
- **Pipeline warm start:** `MTL4Archive` plus the pipeline data-set serializer, and asynchronous
  `MTL4Compiler` tasks so shader compiles never stall the render thread. The OS cache under
  `.../net.java.openjdk.java/com.apple.metal/` also helps.
- **Private and memoryless storage** for render targets and transient attachments: bandwidth savings
  from lossless compression and tile memory.

---

## 13. Open questions

1. How much does RenderPearl's frontend validate before calling the backend? Barrier placement depends on
   knowing every read-after-write the frontend can produce (copy→sample, RT→sample, buffer write→draw).
   This needs the frontend docs.
2. Does the frontend ever read back Shared or WC memory on the CPU (for example `copyTextureToBuffer`
   callbacks reading results)? Readback buffers must not be write-combined.
3. Should `MAILBOX` be advertised? CAMetalLayer has no true mailbox mode.
4. The exact `SharedLibraryLoader` up-to-date check (`.sha1` vs CRC vs size) was not decompiled. Confirm
   before relying on in-place upgrades of the extracted shim.
5. MetalFX temporal and frame interpolation need motion vectors and jitter from vanilla's world shaders.
   That is a scope decision for the game-side patch.
6. Do `MTL4CounterHeap` timestamps share the GPU domain of `sampleTimestamps:gpuTimestamp:`? This is
   assumed but not verified. Verify with a calibration test before trusting Tracy GPU zones.
7. Should the Metal 4 path be the only path? Metal 4 needs macOS 26+, which is fine for this target. A
   classic-Metal path with automatic hazard tracking is a cheap correctness oracle during bring-up.
8. The user will need to run `xcodebuild -downloadComponent MetalToolchain` only if offline `.metallib`
   precompilation is wanted. It is not needed for the runtime-compile design.

---

## Appendix A — Evidence programs (session scratchpad, throwaway)

All are in `<temp>`.
This location is session-scoped and may be deleted, so copy anything worth keeping.

| File | What it shows |
|---|---|
| `devquery.m` | GPU caps, MetalFX support, runtime MSL compile, MTL4 object creation, CAMetalLayer defaults (§1.1) |
| `benchshim.c` + `Bench.java` | JNI vs FFM vs FFM-critical vs LWJGL `JNI.invokePPP` cost; per-frame stream vs per-command calls (§4.3) |
| `encbench.m` | native per-draw encode cost, classic vs Metal 4 (§4.3) |
| `setaddr.m` + `ObjcBench.java` | ObjC message cost natively vs Java→FFM→`objc_msgSend`; pure-Java Metal access works (§4.3, §8.8) |
| `MemProbe.java` | LWJGL memory backend, allocator, extraction and loader logs on Java 25 (§3.4) |
| `LoadProbe.java` + `probe.jar` | `Library.loadNative` of a custom ad-hoc dylib from a jar + FFM lookup (§3.3, §5) |
| `mcmtl_skel.m` + `SkelTest.java` | end-to-end Java→FFM→ObjC shim→Metal 4 with packed command stream (§8.9) |
