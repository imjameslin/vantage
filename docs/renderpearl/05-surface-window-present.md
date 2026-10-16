# 05 · Window, surface, presentation and HiDPI (RenderPearl, with a Metal mapping)

Scope: how Minecraft Java 26.3 creates its SDL3 window, sizes the framebuffer (Retina), and drives
`GpuSurface` (configure, acquire, blit, present). It also covers how the OpenGL and Vulkan backends
implement the surface, and how a native Metal backend should map all of this onto
`CAMetalLayer`/`MTLCommandQueue` (and Metal 4) on an M5 MacBook Pro (3024x1964, ProMotion 120 Hz, macOS 27).

All paths are relative to `src/vanilla/` unless stated otherwise. Line numbers refer to the
Vineflower output in this workspace.

---

## 0. TL;DR for the Metal backend implementer

1. **Window flags.** `Window` always asks for `SDL_WINDOW_RESIZABLE | SDL_WINDOW_HIGH_PIXEL_DENSITY` (`0x2020`, `com/mojang/blaze3d/platform/Window.java:164-165`). Each backend ORs in its own API flag inside `GpuBackend.createWindow` (GL `0x2`, Vulkan `0x10000000`). A Metal backend should return `SDL_CreateWindow(title, w, h, SDL_WINDOW_METAL | flags)` with `SDL_WINDOW_METAL = 0x20000000L`.
2. **Retina.** The game renders at the full backing-pixel resolution. On the 14" panel at the default "looks like 1512x982" mode, a fullscreen window renders 3024x1964 pixels. The default 854x480-point window renders 1708x960 pixels. `Window.getWidth()/getHeight()` are **pixels**, and `getScreenWidth()/getScreenHeight()` are **points** (§4).
3. **The surface contract is small.** It has 7 backend methods (`backend/api/GpuSurfaceBackend.java:9-21`). The frontend (`frontend/FrontendGpuSurface.java`) already enforces the call order (configure → acquire → blit exactly once → present), state, usage and format checks. The backend only has to do the platform work and throw `SurfaceException` for recoverable failures.
4. **Call order per frame** (`net/minecraft/client/Minecraft.java:1245-1380`): `configure` (only when needed) → `acquireNextTexture` → *all game rendering* → `blitFromTexture(encoder, mainColorView)` → `encoder.submit()` → `present()`. **`present()` is called after `submit()`.** With the classic Metal API, `presentDrawable:` therefore has to be attached to the command buffer during `blitFromTexture`, before the commit in `submit()`. With Metal 4, `present()` maps naturally to `signalDrawable:` + `[drawable present]`.
5. **Copy semantics.** Copy `min(surface, texture)` pixels with no scaling, nearest filtering, anchored top-left on screen, with a **vertical flip**. Render targets are stored with row 0 at the bottom of the image (GL convention, which the Vulkan backend reproduces), and the Vulkan blit flips (`backend/vulkan/VulkanGpuSurface.java:408-435`). Use the source view's `baseMipLevel`. The source is `RGBA8_UNORM` with sRGB-encoded values; nothing converts between linear and sRGB anywhere.
6. **A Metal blit is impossible here.** `CAMetalLayer` cannot be `RGBA8Unorm` (allowed formats are BGRA8Unorm, BGRA8Unorm_sRGB, RGBA16Float, RGB10A2Unorm, BGR10A2Unorm and the XR formats). `MTLBlitCommandEncoder` cannot swizzle, flip or scale. **Present with one full-screen-triangle render pass** into a `framebufferOnly = YES`, `BGRA8Unorm` drawable. That single pass does the swizzle, the Y flip, sets alpha to 1, and can optionally upscale.
7. **Present modes.** Report `{FIFO, IMMEDIATE}`. FIFO maps to `displaySyncEnabled = YES` and IMMEDIATE to `displaySyncEnabled = NO`, which is exactly what MoltenVK does. `getSupportedVsyncMode` picks FIFO for vsync on, and IMMEDIATE > MAILBOX > FIFO for vsync off (`api/device/GpuSurface.java:35-48`).
8. **Wrap everything in autorelease pools.** The game thread *is* the macOS main thread (`-XstartOnFirstThread`), and the java launcher has one outer `NSAutoreleasePool` that is never drained. Autoreleased drawables and command buffers would never be freed. After three frames `nextDrawable` would block for 1 s and then return `nil`. This is the number-one Metal-on-JVM pitfall.
9. **Minimized windows.** Throw `SurfaceException` from `acquireNextTexture()` when `isIconified` is true, like `GlSurface` does (`backend/opengl/GlSurface.java:42-46`). The game then stops acquiring until the window is restored. Never block in `nextDrawable` while minimized or occluded.
10. **`configure()` is cheap in Metal.** It sets `drawableSize`, `displaySyncEnabled` and `maximumDrawableCount`, with no GPU idle. The Vulkan backend does `queue.waitIdle()` plus a swapchain rebuild (`VulkanGpuSurface.java:178-190`).

---

## 1. Source map

| Concern | File:lines |
|---|---|
| Public surface API, `Configuration`, `PresentMode`, `getSupportedVsyncMode` | `com/mojang/renderpearl/api/device/GpuSurface.java:9-50` |
| `SurfaceException` (checked; message or cause ctor) | `com/mojang/renderpearl/api/device/SurfaceException.java:3-11` |
| Frontend validation and state machine | `com/mojang/renderpearl/frontend/FrontendGpuSurface.java:11-126` |
| Backend SPI | `com/mojang/renderpearl/backend/api/GpuSurfaceBackend.java:9-21`; factory `backend/api/GpuDeviceBackend.java:20` |
| Device-level factory | `api/device/GpuDevice.java:25`, `frontend/FrontendGpuDevice.java:55-57` |
| Backend window and library hooks | `api/device/GpuBackend.java:5-15` |
| GL surface | `backend/opengl/GlSurface.java:14-66`; blit `backend/opengl/GlCommandEncoder.java:436-447` |
| GL backend window/library | `backend/opengl/GlBackend.java:27-67`; hidden context windows `backend/opengl/GlDevice.java:71-114`, `makeCurrent` `:394-399` |
| Vulkan surface (swapchain) | `backend/vulkan/VulkanGpuSurface.java:46-543`; factory `backend/vulkan/VulkanDevice.java:182-184` |
| Vulkan backend window/library | `backend/vulkan/VulkanBackend.java:58-130` |
| Vulkan frames-in-flight throttle | `backend/vulkan/VulkanCommandEncoder.java:263-280` (waits submit N-2 at `:270`) |
| SDL window wrapper | `com/mojang/blaze3d/platform/Window.java:36-860` |
| SDL event pump/dispatch | `com/mojang/blaze3d/platform/SDLEventHandler.java:34-101`, `com/mojang/blaze3d/systems/RenderSystem.java:147-163` |
| Monitors / video modes | `com/mojang/blaze3d/platform/MonitorManager.java`, `Monitor.java`, `VideoMode.java` |
| macOS helpers | `com/mojang/blaze3d/platform/MacosUtil.java:16-42` |
| SDL init + hints | `com/mojang/blaze3d/systems/RenderSystem.java:198-218` |
| Backend selection, window + surface creation | `net/minecraft/client/Minecraft.java:454-545`; `net/minecraft/client/PreferredGraphicsApi.java:33-37` |
| Per-frame lifecycle | `net/minecraft/client/Minecraft.java:1245-1380` |
| Resize → reconfigure | `Minecraft.java:1428-1431`, `:2974-2976` |
| Main render target sizing | `net/minecraft/client/renderer/GameRenderer.java:178, 332-337, 470-475, 742-750`; `com/mojang/blaze3d/pipeline/MainTarget.java:17-80` |
| FPS limiting | `com/mojang/blaze3d/platform/FramerateLimitTracker.java:26-57`, `net/minecraft/client/FramerateLimiter.java:15-43`, `net/minecraft/client/Options.java:119-130, 515-517` |

---

## 2. Startup, ownership and shutdown order

Sequence in `Minecraft.<init>`:

1. `RenderSystem.initBackendSystem()` (`RenderSystem.java:198-218`) sets SDL app metadata and hints, then calls `SDL_Init(SDL_INIT_VIDEO = 0x20)`. The time source becomes `SDL_GetTicksNS`. Hints set here include `SDL_VIDEO_MINIMIZE_ON_FOCUS_LOSS=0` and `SDL_QUIT_ON_LAST_WINDOW_CLOSE=0`.
2. `new MonitorManager()` (`Minecraft.java:455`; `initBackendSystem` at `:454`).
3. Backend loop (`Minecraft.java:484-509`). For each `GpuBackend` from `PreferredGraphicsApi.getBackendsToTry()` it calls `loadLibrary()` then `createDevice(debugOptions)`. On failure it calls `unloadLibrary()` and tries the next one. The order is `{GL, Vulkan}` for DEFAULT/OPENGL and `{Vulkan, GL}` for VULKAN (`PreferredGraphicsApi.java:33-37`). **A Metal backend has to be added to this enum and array (game-side change).**
4. **The device is created before the window.** The window is created afterwards with the chosen backend (`Minecraft.java:527-536`): `new Window(..., backend, maxSize)`, where `maxSize = deviceInfo.limits().maxTextureSizeForFormat(RGBA8_UNORM)` becomes the SDL maximum window size in *points* (`Window.java:115`, `:744-746`).
   - GL works around this with a hidden 320x480 utility window. It owns the GL context, which is later made current on the real window (`GlDevice.java:71-89`, `:394-399`).
   - Vulkan and Metal do not need a window to create a device.
5. `this.windowSurface = device.createSurface(window.handle(), window::isIconified)` (`Minecraft.java:544`). It is created exactly once for the lifetime of the game, and the window handle never changes.
6. `sdlEventHandler.pumpEvents()`, then `framebufferSizeChanged()` (which sets `windowSurfaceNeedsReconfiguring`), then the first `renderFrame(false)` (`Minecraft.java:740-742`). The first `configure` happens here.

Shutdown (`Minecraft.close()`, `Minecraft.java:1127-1164`): many subsystems close, then `windowSurface.close()` (`:1153`), then `RenderSystem.shutdownRenderer()`. In `finally`, `window.close()` (`SDL_DestroyWindow`, render thread only, `Window.java:342-346`) runs, then `RenderSystem.unloadTrackedBackendLibrary()` and `SDL_Quit()`.

- The **surface is closed before the device**, and both are closed before the window is destroyed. A Metal surface can therefore safely call `SDL_Metal_DestroyView` in `close()`.
- `FrontendGpuSurface.close()` throws `IllegalStateException` if a texture is still acquired (`FrontendGpuSurface.java:22-28`).
- Note that the Vulkan surface calls `vkDestroySurfaceKHR` directly and never `SDL_Vulkan_DestroySurface` (`VulkanGpuSurface.java:175`), so on macOS the SDL-created Metal view for MoltenVK is never explicitly destroyed (harmless at exit).

**Threading.** Every window, surface and SDL call happens on the render thread, which equals the game thread (`RenderSystem.assertOnRenderThread()` in `Window.setMode/close/...`). On macOS the launcher passes `-XstartOnFirstThread` (`mc/versions/26.3/26.3.json`, jvm args), so the **game thread is the Cocoa main thread**. This satisfies SDL's rule that `SDL_Metal_CreateView` and window functions must run on the main thread. There are no cross-thread surface calls.

---

## 3. Window creation flags per backend

`Window.createWindow` (`Window.java:163-173`):

```java
long flags = 8224L;                                       // 0x2020
long windowHandle = backend.createWindow(title, width, height, 8224L);
SDLVideo.SDL_SetWindowMinimumSize(windowHandle, 320, 240);
```

`0x2020 = SDL_WINDOW_RESIZABLE (0x20) | SDL_WINDOW_HIGH_PIXEL_DENSITY (0x2000)`. `width`/`height` are **points** (default `--width 854 --height 480`, `net/minecraft/client/main/Main.java:108-109`), clamped to at least 320x240 (`Window.java:112-113`). The window is created visible, not hidden. It is centered on the primary display (`Window.java:117-119`), and `setMode()` and `refreshFramebufferSize()` run at the end of the constructor (`:149-150`).

| Backend | `createWindow` (file:line) | Extra flags | Resulting main-window flags | Other setup |
|---|---|---|---|---|
| OpenGL | `GlBackend.java:60-67` | `SDL_WINDOW_OPENGL` (`0x2`) | `0x2022` | Before creating, sets `SDL_GL_CONTEXT_MAJOR_VERSION(17)=3`, `MINOR(18)=3`, `PROFILE_MASK(20)=CORE(1)`, `CONTEXT_FLAGS(19)=FORWARD_COMPATIBLE(2)`, `FRAMEBUFFER_SRGB_CAPABLE(22)=1`. `GlDevice` also creates two hidden windows with `2147614728L = 0x80020008 = NOT_FOCUSABLE\|UTILITY\|HIDDEN` (plus OPENGL): one keeps the context (`GlDevice.java:71`), one is a test window that is destroyed immediately (`:106-114`). |
| Vulkan | `VulkanBackend.java:128-130` | `SDL_WINDOW_VULKAN` (`268435456L = 0x10000000`) | `0x10002020` | `loadLibrary` = `SDL_Vulkan_LoadLibrary(VK lib path)` + `vkGetInstanceProcAddr` identity check (`:58-87`). On macOS the loader is the bundled **MoltenVK** (`lwjgl-vulkan-3.4.3-natives-macos-arm64.jar: libMoltenVK.dylib`) with `VK_KHR_portability_enumeration` (`VulkanInstance.java:74-91`). |
| **Metal (proposed)** | new `MetalBackend.createWindow` | `SDL_WINDOW_METAL` (`536870912L = 0x20000000`) | `0x20002020` | `loadLibrary`: nothing to load (Metal.framework is always present). Just verify `MTLCreateSystemDefaultDevice() != nil`. `unloadLibrary`: no-op. |

`SDL_Metal_CreateView` will also convert a non-METAL window (it strips OPENGL/VULKAN and unloads those libraries). Creating the window with `SDL_WINDOW_METAL` from the start is cleaner and avoids that conversion.

### 3.1 Exact LWJGL 3.4.3 SDL signatures (checked with `javap` on `lwjgl-sdl-3.4.3.jar`; native is SDL **3.4.14**)

```java
// org.lwjgl.sdl.SDLMetal
public static long SDL_Metal_CreateView(long window);   // returns SDL_MetalView (retained NSView*), 0 on failure (SDL_GetError)
public static void SDL_Metal_DestroyView(long view);
public static long SDL_Metal_GetLayer(long view);       // returns CAMetalLayer* (owned by the view; not retained for you)

// org.lwjgl.sdl.SDLVideo (selected)
public static long    SDL_CreateWindow(CharSequence title, int w, int h, long flags);
public static long    SDL_CreateWindowWithProperties(int props);
public static boolean SDL_GetWindowSizeInPixels(long window, IntBuffer w, IntBuffer h);
public static boolean SDL_GetWindowSize(long window, IntBuffer w, IntBuffer h);
public static float   SDL_GetWindowPixelDensity(long window);
public static float   SDL_GetWindowDisplayScale(long window);
public static long    SDL_GetWindowFlags(long window);
public static int     SDL_GetWindowProperties(long window);
public static int     SDL_GetWindowID(long window);
public static boolean SDL_SyncWindow(long window);
public static boolean SDL_SetWindowFullscreen(long window, boolean fullscreen);
public static boolean SDL_SetWindowFullscreenMode(long window, SDL_DisplayMode mode);   // null = desktop/borderless fullscreen
public static SDL_DisplayMode SDL_GetWindowFullscreenMode(long window);
public static int     SDL_GetDisplayForWindow(long window);
public static SDL_DisplayMode SDL_GetCurrentDisplayMode(int displayID);
public static float   SDL_GetDisplayContentScale(int displayID);
public static void    SDL_DestroyWindow(long window);
// org.lwjgl.sdl.SDLVulkan (reference)
public static boolean SDL_Vulkan_CreateSurface(long window, VkInstance, VkAllocationCallbacks, LongBuffer surface);
// org.lwjgl.sdl.SDLProperties
public static long    SDL_GetPointerProperty(int props, CharSequence name, long default);
public static boolean SDL_GetBooleanProperty(int props, CharSequence name, boolean default);
// org.lwjgl.sdl.SDLHints
public static boolean SDL_SetHint(CharSequence name, CharSequence value);
```

Constants (`SDLVideo`, values verified):

| Constant | Value |
|---|---|
| `SDL_WINDOW_FULLSCREEN` | `1L` |
| `SDL_WINDOW_OPENGL` | `2L` |
| `SDL_WINDOW_OCCLUDED` | `4L` |
| `SDL_WINDOW_HIDDEN` | `8L` |
| `SDL_WINDOW_BORDERLESS` | `16L` |
| `SDL_WINDOW_RESIZABLE` | `32L` |
| `SDL_WINDOW_MINIMIZED` | `64L` |
| `SDL_WINDOW_MAXIMIZED` | `128L` |
| `SDL_WINDOW_HIGH_PIXEL_DENSITY` | `8192L` (0x2000) |
| `SDL_WINDOW_UTILITY` | `131072L` |
| `SDL_WINDOW_VULKAN` | `268435456L` (0x10000000) |
| **`SDL_WINDOW_METAL`** | **`536870912L` (0x20000000)** |
| `SDL_WINDOW_TRANSPARENT` | `1073741824L` |
| `SDL_WINDOW_NOT_FOCUSABLE` | `2147483648L` |

Properties: `SDL_PROP_WINDOW_CREATE_METAL_BOOLEAN = "SDL.window.create.metal"`, `SDL_PROP_WINDOW_COCOA_WINDOW_POINTER = "SDL.window.cocoa.window"` (NSWindow*), `SDL_PROP_WINDOW_COCOA_METAL_VIEW_TAG_NUMBER = "SDL.window.cocoa.metal_view_tag"`.

Hints (`SDLHints`): `SDL_HINT_VIDEO_METAL_AUTO_RESIZE_DRAWABLE = "SDL_VIDEO_METAL_AUTO_RESIZE_DRAWABLE"` (SDL 3.4+). `"1"` (the default) makes SDL update `drawableSize` on `SDL_EVENT_WINDOW_PIXEL_SIZE_CHANGED`; `"0"` leaves drawable size to the app. It must be set before `SDL_Metal_CreateView`. Also `SDL_HINT_VIDEO_MAC_FULLSCREEN_SPACES`, `SDL_HINT_VIDEO_MAC_FULLSCREEN_MENU_VISIBILITY`, `SDL_HINT_VIDEO_SYNC_WINDOW_OPERATIONS`.

Events (`SDLEvents`): `SDL_EVENT_WINDOW_PIXEL_SIZE_CHANGED = 519`, `SDL_EVENT_WINDOW_METAL_VIEW_RESIZED = 520` (not handled by the game), `SDL_EVENT_WINDOW_OCCLUDED = 534` (not handled), `SDL_EVENT_WINDOW_DISPLAY_SCALE_CHANGED = 532` (not handled), `SDL_EVENT_WINDOW_HDR_STATE_CHANGED = 538` (not handled).

### 3.2 What SDL's Cocoa Metal view actually does (SDL 3.4 `src/video/cocoa/SDL_cocoametalview.m`)

- `Cocoa_Metal_CreateView` creates an `SDL3_cocoametalview` (an NSView whose backing layer is a `CAMetalLayer`, `wantsLayer = YES`, autoresizing width and height). It is added as a **subview of the window's contentView** and frame-matched to it.
- `highDPI = (flags & SDL_WINDOW_HIGH_PIXEL_DENSITY)`; `layer.opaque = !(flags & SDL_WINDOW_TRANSPARENT)`, so it is **opaque for Minecraft**.
- `updateDrawableSize`: `backing = highDPI ? convertSizeToBacking(bounds) : bounds`, then `layer.contentsScale = backing.h / bounds.h` and `layer.drawableSize = backing`. This runs once at creation and again on every `SDL_EVENT_WINDOW_PIXEL_SIZE_CHANGED` via an **EARLY event watch**, but only when the AUTO_RESIZE hint is true. The watch fires inside `SDL_PumpEvents`, so in Minecraft it fires *between frames* (in `RenderSystem.pollEvents/pumpEvents`) and before `Window.handleEvent` sees the event.
- `SDL_Metal_CreateView` **does not set `layer.device`** ("It is up to user code to do that"). It does not touch `pixelFormat`, `framebufferOnly`, `displaySyncEnabled`, `maximumDrawableCount` or `colorspace` either, so all of those keep CA defaults until the backend sets them.
- `SDL_Metal_GetLayer` returns `view.layer` (a borrowed reference). `SDL_Metal_DestroyView` calls `removeFromSuperview` and releases the view.
- The view's `hitTest:` returns nil, so input is unaffected.

---

## 4. Window vs framebuffer size, and HiDPI (Retina)

### 4.1 The fields and their units

| Getter | Field | Unit | Source of truth | Updated by |
|---|---|---|---|---|
| `getScreenWidth/Height()` (`Window.java:780-786`) | `width/height` | **points** | `SDL_EVENT_WINDOW_RESIZED (518)` data1/data2 (`:357-371`), `setWindowSizeAndPosition` (`:683-697`) | resize, setMode |
| `getWidth/getHeight()` (`:764-778`) | `framebufferWidth/Height` | **pixels** | `SDL_GetWindowSizeInPixels` (`queryFramebufferSize`, `:402-431`, clamped to ≥1) and `SDL_EVENT_WINDOW_PIXEL_SIZE_CHANGED (519)` (`:373-390`, ignores 0-size) | ctor, setMode, display-mode change, 519 |
| `getGuiScaledWidth/Height()` | `guiScaledWidth/Height` | GUI units | `ceil(framebuffer / guiScale)` (`:808-812`) | `resizeGui` |
| `getPixelDensity()` (`:814-817`) | (none) | ratio | `SDL_GetWindowPixelDensity`, falls back to 1.0 | live query |

`framebufferWidth` subtracts `framebufferWidthPadding()`. That is 1 only in Windows "borderless fullscreen window" mode (`:398-400`, `:523-525`), so **on macOS the framebuffer width is the exact pixel width.**

### 4.2 Does the game render at full backing-pixel resolution on macOS? **Yes.**

- `SDL_WINDOW_HIGH_PIXEL_DENSITY` is always requested (`Window.java:164`). The java launcher binary is linked against SDK 14.2 (`otool -l .../jre.bundle/Contents/Home/bin/java`: `sdk 14.2`), so AppKit treats it as high-resolution capable. `SDL_GetWindowSizeInPixels` then returns 2x the point size.
- The main render target is allocated at `window.getWidth() x window.getHeight()` (`GameRenderer.java:178`, resized each frame in `render()` when `WindowRenderState` changes, `:470-475`; values extracted in `extractWindow`, `:742-750`). So **every frame renders at backing-pixel resolution.**
- Example numbers on the 14" M5 MBP:

  | Case | Points | Pixels rendered |
  |---|---|---|
  | Default window 854x480 pt | 854x480 | **1708x960** (1.64 MP) |
  | Fullscreen, "Default" scaled mode | 1512x982 (or 1512x945 if fullscreen stays below the notch, unverified) | **3024x1964** (5.94 MP) |
  | Fullscreen, "More Space" scaled mode (1800x1169 pt @2x) | 1800x1169 | **3600x2338** (8.4 MP), which WindowServer then *downsamples* to the panel. This is a hidden 40% pixel tax. |

- The GUI scale is computed from **pixel** size (`calculateScale`, `Window.java:720-738`). On 3024x1964 the auto scale is 7, because `1964/8 = 245 ≥ 240` but `1964/9 < 240`.
- Mouse input is mapped through point size, not pixels (`MouseHandler.java:307,315`: `x * guiScaledWidth / screenWidth`). IME rectangles use `guiScale / pixelDensity` (`TextInputManager.java:24`). A render-scale feature (§13, item 5) must keep these consistent: the mouse mapping keeps working automatically, but `getPixelDensity()` would have to be multiplied by the render scale for the IME.
- F3 shows `Window: WxH (2.00x pixel density)` (`DebugEntrySystemSpecs.java:51`). Use it to verify on device.

### 4.3 Surface size vs render-target size can differ for a frame

- The surface is configured with a **fresh** `window.queryFramebufferSize()` (`Minecraft.java:1255`).
- The render target follows the **event-updated** `framebufferWidth/Height` (`GameRenderer.java:745-746`).
- With SDL's auto-resize hint on, the `CAMetalLayer.drawableSize` may already have changed before the game reconfigures (EARLY watch, §3.2).
- All three can disagree for one frame during resizes and fullscreen transitions. The backends cope by copying `min(w)`/`min(h)` (§9). The Metal backend must do the same and must clear any uncovered drawable area.
- `MainTarget` may also fall back to smaller dimensions on OOM (`MainTarget.java:35-63`).

---

## 5. The `GpuSurface` contract

### 5.1 API (`api/device/GpuSurface.java`)

```java
void configure(Configuration config) throws SurfaceException;   // :10
Optional<Configuration> currentConfiguration();                 // :12
Collection<PresentMode> supportedPresentModes();                // :14
boolean isSuboptimal();                                         // :16
boolean isAcquired();                                           // :18
void acquireNextTexture() throws SurfaceException;              // :20
void blitFromTexture(CommandEncoder enc, GpuTextureView view);  // :22
void present();                                                 // :24
record Configuration(int width, int height, PresentMode presentMode) {}   // :26
enum PresentMode { IMMEDIATE, MAILBOX, FIFO, FIFO_RELAXED }              // :29-34
```

`PresentMode.getSupportedVsyncMode(supported, vsync)` (`:38-48`): if vsync, only `FIFO` is accepted. Otherwise the preference is `IMMEDIATE`, then `MAILBOX`, then `FIFO`. It throws `IllegalStateException("No supported presentation mode was found")` if none match. **FIFO must always be supported.** `FIFO_RELAXED` is never selected by the game.

### 5.2 What the frontend already validates (`FrontendGpuSurface.java`)

| Call | Frontend checks before calling backend | Frontend state change |
|---|---|---|
| `close()` `:22-28` | not acquired, else `IllegalStateException` | none |
| `configure()` `:31-42` | not acquired (ISE). `config.presentMode()` is in `supportedPresentModes()`, else **`SurfaceException`** | `currentConfiguration = config` **only if the backend didn't throw** |
| `supportedPresentModes()` `:50-52` | none (delegates every call, so return a cached immutable set) | |
| `isSuboptimal()` `:55-57` | none (delegates) | |
| `acquireNextTexture()` `:65-77` | not already acquired (ISE). Configured at least once (ISE) | `hasImageAcquired = true`, `hasBlittedTexture = false`, **only if the backend didn't throw** |
| `blitFromTexture()` `:80-111` | encoder is a `FrontendCommandEncoder` (IAE). Not inside a render pass (ISE). `format.hasColorAspect()` (ISE). `usage & USAGE_COPY_SRC (2)` (ISE). `depthOrLayers == 1` (UOE). Acquired (ISE). Not already blitted this frame (ISE) | `hasBlittedTexture = true`. The backend receives `frontendCommandEncoder.backend()` |
| `present()` `:114-125` | acquired (ISE). Blitted (ISE) | `hasImageAcquired = false` (even though there is no try/finally, the backend should not throw) |

**What the backend still has to do:**
- Size validation in `configure` (Vulkan checks surface min/max extents, `VulkanGpuSurface.java:205-223`).
- Platform failures become `SurfaceException`.
- Present-mode support detection.
- Choose the copy region and do the format conversion.
- Synchronization with the frame's command stream.
- Minimized/occluded handling.
- Resource cleanup.

There is no mip/format restriction beyond color-aspect. In practice the only caller passes the main target's color view (`Minecraft.java:1317-1322`): `RGBA8_UNORM`, usage `15` = `COPY_DST|COPY_SRC|TEXTURE_BINDING|RENDER_ATTACHMENT`, 1 mip, 1 layer (`MainTarget.java:18,76`). The frontend does **not** guarantee `TEXTURE_BINDING`, so the Metal backend should give any texture with `COPY_SRC` the `MTLTextureUsageShaderRead` usage (cheap, and it keeps lossless compression), so that the render-pass present can sample it. Integer formats cannot be presented by a float sampling pass. Throw `UnsupportedOperationException` for them, mirroring the fact that `vkCmdBlitImage` cannot blit int to unorm.

### 5.3 State machine (frontend + game)

```
             configure() ok
 [unconfigured] ─────────────► [configured, idle] ◄──────────────────────────┐
                                 │  ▲      │ configure() (only when idle)      │
                acquireNextTexture()│      └──────────────┘                     │ present()
                                 ▼  │ SurfaceException → stays idle             │
                           [acquired] ── blitFromTexture() ──► [acquired+blitted]┘
```

`isSuboptimal` means "the surface still works but should be reconfigured". The game reconfigures whenever `isSuboptimal() && !surfaceIsInvalid` (`Minecraft.java:1254`). In Vulkan it is set by `VK_SUBOPTIMAL_KHR` from acquire or present (`:334-335`, `:522-523`), and set to true together with "out of date" on `VK_ERROR_OUT_OF_DATE_KHR` or a configure failure (`:289-292`, `:328-332`, `:518-521`). Configure resets it (`:270-271`). GL always returns false (`GlSurface.java:37-39`). **Never make `isSuboptimal()` return true permanently.** The game would then reconfigure every single frame.

### 5.4 Error-handling semantics expected by the game

- `SurfaceException` from `configure` → `WARN "Couldn't configure surface to {}"` and `surfaceIsInvalid = true`. `windowSurfaceNeedsReconfiguring` stays true, so it retries on the next frame (log spam every frame while it keeps failing) (`Minecraft.java:1262-1268`).
- `SurfaceException` from `acquireNextTexture` → `WARN "Couldn't acquire next surface texture..."` and sets **both** `surfaceIsInvalid = true` and `windowSurfaceNeedsReconfiguring = true`. The frame is still rendered but not presented (`:1271-1279`). The next frame reconfigures if the window is not iconified, and acquire is tried again only after a successful configure.
- Any `RuntimeException` from the surface inside `renderFrame` becomes a "Render Frame" crash report (`:1224-1229`).
- The crash report "Surface Info" section prints acquired/suboptimal/config (`Minecraft.java:2484-2500`).
- Vulkan's convention: `IllegalStateException` for GPU timeouts and out-of-date misuse; `crashIfFailure` for unexpected `VkResult`s; and **`present()` never throws on OUT_OF_DATE**. It stores an "eaten" `SurfaceException` that the next `acquireNextTexture()` rethrows (`VulkanGpuSurface.java:303-307`, `:518-521`). This is the right pattern for Metal too: `blitFromTexture`/`present` cannot throw checked exceptions, so defer the failure to the next acquire.

---

## 6. Per-frame lifecycle in the game (`Minecraft.renderFrame`, `Minecraft.java:1245-1380`)

```text
renderFrame(advance):
  if windowSurface.isAcquired(): return                     // :1246 re-entrancy guard (renderFrame is also called from
                                                            //   loading loops :2075, :2174, :2223)
  window.updateFullscreenIfChanged()                        // :1253 may call setMode() + framebufferSizeChanged()
  if (needsReconfig || (isSuboptimal && !surfaceIsInvalid)) && !window.isIconified():   // :1254
      fb = window.queryFramebufferSize()                    // fresh SDL_GetWindowSizeInPixels
      mode = getSupportedVsyncMode(surface.supportedPresentModes(), options.enableVsync)
      try configure(new Configuration(fb.w, fb.h, mode)); surfaceIsInvalid=false; needsReconfig=false
      catch SurfaceException: WARN; surfaceIsInvalid=true
  if !surfaceIsInvalid:
      try acquireNextTexture()                              // :1273  ← BEFORE any game work this frame
      catch SurfaceException: WARN; surfaceIsInvalid=true; needsReconfig=true
  ... gui.update, level.update, gameRenderer.update/extract ...             // CPU work
  RenderSystem.executePendingTasks(); gameRenderer.render()                 // :1310-1312 all GPU encoding
  if isAcquired: windowSurface.blitFromTexture(encoder, mainTarget.colorView)  // :1316-1323 ("swapchainBlit")
  [tracy frame capture may record copies here]                              // :1330-1334 (upload at :1332)
  encoder.submit()                                                          // :1337
  if isAcquired: windowSurface.present()                                    // :1339-1341
  dynamicUniforms.reset(); levelRenderer.endFrame()
  if framerateLimit < 260: FramerateLimiter.limitDisplayFPS(limit)          // :1351-1354  (260 = "Unlimited")
```

Important consequences for a backend:

- **Acquire happens at the start of the frame, and blit/present at the end.** With Vulkan FIFO, `vkAcquireNextImageKHR` is what throttles the CPU to the display. There is a 5 s timeout that crashes with checkpoints (`VulkanGpuSurface.java:321-325`).
- **Rendering continues when the surface is not acquired** (minimized or invalid). Only blit and present are skipped. While minimized the FPS cap is 10 (`FramerateLimitTracker.java:29, 39-56`). Occlusion is not throttled.
- `submit()` is called **between** blit and present. The Vulkan blit enqueues a "wait acquire semaphore / execute blit CB / signal present semaphore" into the submission builder that `submit()` flushes (`VulkanGpuSurface.java:497-499`, `VulkanCommandEncoder.java:220-245, 263-280`). `vkQueuePresentKHR` then waits on that semaphore.
- **Reconfiguration triggers** (all just set `windowSurfaceNeedsReconfiguring = true` via `invalidateSurfaceConfiguration()`, `Minecraft.java:2974-2976`):
  - `framebufferSizeChanged()` (`:1428-1431`), called from `SDL_EVENT_WINDOW_PIXEL_SIZE_CHANGED`, `SDL_EVENT_WINDOW_DISPLAY_CHANGED (531)`, `SDL_EVENT_DISPLAY_CURRENT_MODE_CHANGED (342)`, and after `setMode()` (fullscreen toggle or video-mode change);
  - the vsync option change (`Options.java:515-517`);
  - minimize, maximize and restore (`Window.onIconified`, `Window.java:437-440`).
- Changing the FPS limit never reconfigures.
- Defaults: `maxFps = 120`, `enableVsync = true` (`Options.java:120-130, 515`). **To exceed 120 FPS the user must turn vsync off and set Max Framerate to "Unlimited" (260).**

---

## 7. Present modes

| Mode | GL (`GlSurface.java:15,31`) | Vulkan (`VulkanGpuSurface.java:123-144`, `VulkanConst.java:254-261`) | MoltenVK behaviour (reference) | **Metal backend** |
|---|---|---|---|---|
| FIFO (vsync) | `SDL_GL_SetSwapInterval(1)` | `VK_PRESENT_MODE_FIFO_KHR` (2) | `displaySyncEnabled = YES` | `layer.displaySyncEnabled = YES` |
| IMMEDIATE | `SDL_GL_SetSwapInterval(0)` | `IMMEDIATE_KHR` (0) | `displaySyncEnabled = NO` | `layer.displaySyncEnabled = NO` |
| MAILBOX | unsupported | if the driver reports it | treated like FIFO (`displaySyncEnabled = (mode != IMMEDIATE)`) | **don't report** (never selected anyway, since IMMEDIATE wins) |
| FIFO_RELAXED | unsupported | if reported | n/a | don't report |

Vulkan supported modes come from `vkGetPhysicalDeviceSurfacePresentModesKHR` at surface creation and are cached (`:77-90`). The Metal backend should return a constant `EnumSet.of(FIFO, IMMEDIATE)` wrapped unmodifiable.

On macOS in **windowed (composited)** mode, `displaySyncEnabled = NO` behaves like mailbox: WindowServer still composites at the panel refresh, the newest frame wins, and there is no visible tearing. Real tearing can only happen when the layer is presented **direct-to-display** in fullscreen.

---

## 8. Reference implementations in detail

### 8.1 OpenGL (`GlSurface`)

- `configure`: `makeCurrent(window)` and swap interval (1 for FIFO, else 0). It stores width/height (`:29-34`). There is no size validation.
- `acquireNextTexture`: throws `SurfaceException("Cannot acquire minimized window")` when `isIconified` is true (`:42-46`). Otherwise it does nothing, because the default framebuffer always exists.
- `blitFromTexture` → `GlCommandEncoder.presentTexture` (`GlCommandEncoder.java:436-447`): disables scissor, sets viewport, enables depth mask and color mask, attaches the texture (mip level **0**, ignoring `baseMipLevel`) to `drawFbo`, then:
  `glBlitNamedFramebuffer(drawFbo → 0, src (0,0)-(copyW,copyH), dst (0,destY)-(copyW,copyH+destY), GL_COLOR_BUFFER_BIT, GL_NEAREST)`
  with `copyW = min(swapW, texW)`, `copyH = min(swapH, texH)`, `destY = max(0, swapH - texH)`. There is no flip, because GL's default framebuffer is bottom-up like the texture. `destY` anchors a short texture to the **top** of the window.
- `present`: `SDL_GL_SwapWindow` (`:54-56`). `close`: no-op.

### 8.2 Vulkan (`VulkanGpuSurface`)

- **Construction** (`:65-121`): `SDL_Vulkan_CreateSurface`. On macOS, SDL internally creates an `SDL_MetalView` and a `VK_EXT_metal_surface` from its CAMetalLayer. It enumerates present modes and picks the first surface format with `colorSpace == SRGB_NONLINEAR (0)` and format `R8G8B8A8_UNORM (37)` or `B8G8R8A8_UNORM (44)`, otherwise it throws ISE (`:151-159`). This is a **UNORM, not `_SRGB`, format, so there is no hardware sRGB encode.** On MoltenVK only BGRA8 is available. It creates 2 binary acquire semaphores (round robin).
- **`configure`** (`:193-294`):
  - `destroySwapchain()` (**`graphicsQueue().waitIdle()`**, then destroy the swapchain and per-image present semaphores);
  - query caps and validate `min ≤ (w,h) ≤ max` extents, else `SurfaceException`;
  - `minImageCount = max(3, caps.min)`; `imageUsage = TRANSFER_DST (2)` only; `compositeAlpha = OPAQUE (1)`; `preTransform = current`; `clipped = true`;
  - one present semaphore per image.
  - On any `SurfaceException` it marks suboptimal and out-of-date, then rethrows.
- **`acquireNextTexture`** (`:302-354`):
  - rethrows the eaten present error first;
  - ISE if out-of-date;
  - `vkAcquireNextImageKHR(timeout 5 s)`: `VK_TIMEOUT` → ISE with GPU checkpoints; `OUT_OF_DATE` → suboptimal + out-of-date + `SurfaceException`; `SUBOPTIMAL` → flag set but the frame proceeds.
- **`blitFromTexture`** (`:357-500`), in a transient command buffer:
  1. barrier swapchain image `UNDEFINED → TRANSFER_DST_OPTIMAL` (src stage NONE, dst TRANSFER/TRANSFER_WRITE);
  2. `vkCmdBlitImage(src = texture vkImage in TRANSFER_SRC_OPTIMAL(1), dst = swapchain image, filter NEAREST(0))` with `src (0,0)→(copyW,copyH)`, **`dst (0,copyH)→(copyW,0)`, a vertical flip**, `srcSubresource.mipLevel = textureView.baseMipLevel()`, `copyW/H = min(swapchain, view size at level 0)`;
  3. barrier `TRANSFER_DST → PRESENT_SRC_KHR` plus a global memory barrier (TRANSFER_READ → ALL_COMMANDS MEMORY_READ|WRITE).
  4. It then enqueues `waitSemaphore(acquireSem, stage ALL_COMMANDS)`, `execute(blitCB)`, `signalSemaphore(presentSem[imageIndex], stage TRANSFER)`.
  Regions outside the copy rectangle are left **undefined**.
- **`present`** (`:503-542`): `vkQueuePresentKHR` waits on `presentSem[imageIndex]`. `OUT_OF_DATE` → flags + **eaten** `SurfaceException`; `SUBOPTIMAL` → flag; other failures → crash.
- **Frames in flight**: `VulkanCommandEncoder.submit()` blocks until submit N-2 has completed (`VulkanCommandEncoder.java:270`). The CPU is at most 2 frames ahead of the GPU.

### 8.3 Side-by-side

| Aspect | GL | Vulkan | Metal (proposed) |
|---|---|---|---|
| Surface object | default FB of SDL GL window | `VkSurfaceKHR` + swapchain (3 imgs) | `SDL_MetalView` + `CAMetalLayer`, 3 drawables |
| Format | GL default FB (sRGB-capable requested, `GL_FRAMEBUFFER_SRGB` never enabled) | RGBA8/BGRA8 **UNORM**, SRGB_NONLINEAR | **`MTLPixelFormatBGRA8Unorm` (80)**, colorspace sRGB |
| configure cost | swap interval call | **waitIdle** + swapchain rebuild | set 3 layer properties, no GPU sync |
| Acquire | no-op (iconified → SurfaceException) | `vkAcquireNextImageKHR` (5 s timeout) | `nextDrawable` (≤1 s timeout → nil), preferably lazy (§12.6) |
| Copy | `glBlitFramebuffer` NEAREST, no flip | `vkCmdBlitImage` NEAREST, **Y flip**, format convert | full-screen triangle render pass, **Y flip**, swizzle, alpha=1 |
| Present | `SDL_GL_SwapWindow` | `vkQueuePresentKHR` after `submit()` | `presentDrawable:` registered in blit (classic), or `signalDrawable:` + `present` (Metal 4) |
| Iconified | throws SurfaceException | ignores the `isIconified` supplier (`VulkanDevice.java:182-184`) | throw SurfaceException like GL |

---

## 9. Orientation, copy rectangle, color

- **Memory row order.** GL renders with window origin lower-left (`glClipControl(GL_LOWER_LEFT, GL_ZERO_TO_ONE)`, `GlDevice.java:150`), so NDC y=-1 lands in texel row 0. Vulkan maps NDC y=-1 to framebuffer row 0 with a positive-height viewport (`VulkanRenderPass.java:80-87`), and compensates winding with `frontFace = CLOCKWISE` (`VulkanRenderPipeline.java:269-270`). **Both backends therefore store render targets with row 0 = bottom of the image.** Vulkan swapchain images (and Metal drawables) are scanned out with row 0 at the top, so the present copy must flip. Metal's NDC y=+1 maps to row 0. To keep the same memory layout as GL/Vulkan, which keeps post-chain sampling, scissor rectangles and `copyTextureToBuffer` consistent, the Metal pipeline must negate clip-space Y (or flip the viewport) and invert the front-face winding. That belongs to the pipeline/shader doc, but **the present pass's flip depends on it.** Flip if and only if render-target row 0 is the bottom of the image, which is the recommended choice.
- **Depth convention** (FYI): the main depth target is cleared to **0.0** (`GameRenderer.java:479-481, 507`), i.e. reverse-Z, with a [0,1] clip range (`ARB_clip_control ZERO_TO_ONE`).
- **Copy rectangle.** `copyW = min(surfaceW, srcViewW)` and `copyH = min(surfaceH, srcViewH)`. The rectangle is anchored at the screen top-left. If the source is taller, its *bottom* rows (memory rows `0..copyH`) are shown, i.e. its top is cropped. Use `NEAREST` with no scaling. Outside the rectangle the contents are undefined (Vulkan) or stale (GL). **Metal: clear to black when the drawable is larger than the copy.**
- **Color.** The source holds display-referred, gamma-encoded values in a UNORM texture. The surface format is UNORM with sRGB-nonlinear colorspace, so no transfer function is applied. For Metal: `pixelFormat = BGRA8Unorm` (**not** `_sRGB`, which would double-encode), and `layer.colorspace = sRGB`. MoltenVK sets `kCGColorSpaceSRGB` for `VK_COLOR_SPACE_SRGB_NONLINEAR_KHR`, so the Vulkan-on-Mac backend already shows sRGB-managed colors. A nil colorspace means no color matching (more saturated on a P3 panel).
- **Alpha.** Vulkan uses `compositeAlpha = OPAQUE`, and SDL's metal layer is opaque. The present shader should still write `a = 1` so that GUI-blended alpha can never leak through.

---

## 10. Resize, minimize, occlusion, display changes, fullscreen (macOS specifics)

### 10.1 Event handling (`Window.handleEvent`, `Window.java:195-246`; dispatched from `SDLEventHandler.pollEvents` default branch, `SDLEventHandler.java:72-73`; pumped once per frame in `Minecraft.run` via `RenderSystem.pollEvents`, `Minecraft.java:918`, and in loading loops via `pumpEvents`)

| SDL event | Handler | Effect on surface |
|---|---|---|
| 256 QUIT, 528 CLOSE_REQUESTED | `onQuitRequested` | n/a |
| 257 TERMINATING | `requestClose` | n/a |
| 338/339 DISPLAY_ADDED/REMOVED | MonitorManager | none |
| 342 DISPLAY_CURRENT_MODE_CHANGED | `onDisplayModeChanged` | refresh FB size + `framebufferSizeChanged()` → reconfigure |
| 517 MOVED | `onMove` | none |
| 518 RESIZED (points) | `onResize` | updates point size and mouse grab, but **no** reconfigure by itself |
| 519 PIXEL_SIZE_CHANGED (pixels) | `onFramebufferResize` | FB size + `framebufferSizeChanged()` → reconfigure + `resizeGui` |
| 521 MINIMIZED | `onIconified(true)` | invalidate (configure is skipped while iconified) |
| 522 MAXIMIZED / 523 RESTORED | `onIconified(false)` | invalidate → reconfigure next frame |
| 526/527 FOCUS | `onFocus` | none (pause-on-focus-loss, FPS throttle in exclusive FS) |
| 531 WINDOW_DISPLAY_CHANGED | `framebufferSizeChanged()` | reconfigure |
| 535/536 ENTER/LEAVE_FULLSCREEN | `updateFullscreenState` | syncs the option only |
| **unhandled:** 516 EXPOSED, 520 METAL_VIEW_RESIZED, 532 DISPLAY_SCALE_CHANGED, 534 OCCLUDED, 538 HDR_STATE_CHANGED | | |

Moving the window between the 2x panel and a 1x external display changes the pixel size, so 519 fires and the game reconfigures. With the auto-resize hint, SDL has already updated `contentsScale` and `drawableSize`.

### 10.2 Minimize

1. `onIconified(true)` sets `windowSurfaceNeedsReconfiguring = true`. Configure is skipped while iconified (`Minecraft.java:1254`).
2. The next `acquireNextTexture` must throw (GL behaviour). Then `surfaceIsInvalid = true` and nothing is acquired until restore.
3. The game keeps rendering at 10 FPS (`FramerateLimitTracker.java:29`) without presenting.
4. On restore, 522/523 trigger a reconfigure, which clears `surfaceIsInvalid`, and acquire resumes.

**Metal backend: throw `SurfaceException` from `acquireNextTexture()` when `isIconified.getAsBoolean()` is true**, before ever touching `nextDrawable`.

### 10.3 Occlusion

The game ignores occlusion: it keeps rendering at full rate and presenting. A minimized or fully covered window can make `nextDrawable` wait up to the 1 s timeout on macOS, especially with `displaySyncEnabled = YES`.

Do **not** throw `SurfaceException` for occlusion. The game would re-run configure + acquire every frame and log a WARN each time, because configure is not gated on occlusion. Use a silent "drop frame" path instead: return normally from acquire, skip `nextDrawable`, and make blit and present no-ops, when `(SDL_GetWindowFlags(window) & SDL_WINDOW_OCCLUDED) != 0`. Optionally also add a game-side FPS throttle for occluded windows.

### 10.4 Fullscreen on macOS (`Window.setMode` and friends, `Window.java:471-711`)

- `options.fullscreen` sets `fullscreenRequested`. `renderFrame` calls `updateFullscreenIfChanged()` (`:442-448`), which calls `setMode()` and then `framebufferSizeChanged()`.
- `applyFullscreen()` (`:514-521`): the Windows-only borderless-window path is skipped on macOS (`useBorderlessFullscreenWindow()` is true only on Windows, `:523-525`). Then:
  - **Default (exclusive off):** `SDL_SetWindowFullscreenMode(window, null)` (desktop fullscreen, `:699-701`) followed by `SDL_SetWindowFullscreen(true)`. SDL3 defaults `SDL_VIDEO_MAC_FULLSCREEN_SPACES` to on, so this is a **native Spaces fullscreen** with the slide animation.
  - **Exclusive fullscreen:** `SDL_GetClosestFullscreenDisplayMode(display, w, h, refresh, include_high_density_modes = true)` then `SDL_SetWindowFullscreenMode(mode)` and `SDL_SetWindowFullscreen(true)` (`:545-593`). On macOS SDL switches the display mode with CoreGraphics. Note that `VideoMode` stores only w/h (points), RGB bits and refresh, **not pixel density** (`VideoMode.java:53-58`). 1x and 2x modes with the same point size are therefore indistinguishable in the options UI.
- After success: `SDL_SyncWindow` (which blocks until the async Cocoa transition settles), then `updateFullscreenState()`, `refreshFramebufferSize()`, and mouse grab only in exclusive mode (`:486-496`).
- Menu-bar visibility in fullscreen is controlled by the `macFullscreenMenuVisibility` option, through the `SDL_VIDEO_MAC_FULLSCREEN_MENU_VISIBILITY` hint (`MacosUtil.java:32-36`, `Options.java:599-604`).
- During the Spaces animation, sizes change several times and 518/519/535 arrive asynchronously. Expect transient size mismatches (§4.3).

---

## 11. Timing, frame pacing and the FPS limiter (game side)

- `FramerateLimitTracker.getFramerateLimit()` (`FramerateLimitTracker.java:26-34`) returns:
  - the option value normally;
  - 10 when iconified, or when unfocused in exclusive fullscreen;
  - 10 or 30 when AFK, if the inactivity limit is set to AFK;
  - 60 in out-of-level menus.
- `FramerateLimiter.limitDisplayFPS` (`FramerateLimiter.java:15-43`) uses `LockSupport.parkNanos` plus spin-wait with overshoot smoothing. It is skipped when the limit is 260 ("Unlimited").
- The Vulkan backend caps CPU run-ahead at 2 submits. **A Metal backend should do the same** (an `MTLSharedEvent` signalled at the end of each submit, and a CPU wait for value N-2 in `submit()`). Otherwise, with IMMEDIATE presentation, the CPU can queue many frames and latency grows.

---

## 12. Metal mapping

### 12.1 Backend hooks

```java
// MetalBackend implements GpuBackend
public String getName() { return "Metal"; }
public void loadLibrary() throws BackendCreationException {
    // Metal.framework/QuartzCore are system frameworks; just probe.
    if (MTLCreateSystemDefaultDevice() == 0) throw new BackendCreationException("Metal not supported", Reason.PLATFORM_ERROR);
}
public void unloadLibrary() {}
public long createWindow(@Nullable String title, int w, int h, long flags) {
    return SDLVideo.SDL_CreateWindow(title, w, h, SDLVideo.SDL_WINDOW_METAL | flags);   // 0x20000000 | 0x2020
}
```

Game-side wiring: add `METAL` to `PreferredGraphicsApi` (`net/minecraft/client/PreferredGraphicsApi.java:10-37`) and put it first on macOS. Note that `NativeLibrariesBootstrap` loads **spvc/shaderc only if the Vulkan loader is available** (`com/mojang/blaze3d/platform/NativeLibrariesBootstrap.java:76-86`). A Metal backend that cross-compiles SPIR-V to MSL with SPIRV-Cross must load spvc unconditionally. On Macs MoltenVK is bundled, so the condition is normally true, but don't rely on it.

### 12.2 Surface construction (`MetalDevice.createSurface(windowHandle, isIconified)`)

```text
SDL_SetHint("SDL_VIDEO_METAL_AUTO_RESIZE_DRAWABLE", "0")   // optional: backend owns drawableSize (needed for render-scale tricks)
view  = SDL_Metal_CreateView(windowHandle)                 // main thread; 0 → throw IllegalStateException(SDL_GetError())
layer = SDL_Metal_GetLayer(view)
[CATransaction begin]; [CATransaction setDisableActions:YES];   // no implicit animations
layer.device                    = mtlDevice                // REQUIRED: SDL does not set it
layer.pixelFormat               = MTLPixelFormatBGRA8Unorm (80)
layer.framebufferOnly           = YES                      // present via render pass only
layer.opaque                    = YES                      // SDL already sets it; set explicitly
layer.colorspace                = CGColorSpaceCreateWithName(kCGColorSpaceSRGB)   // parity with GL/MoltenVK
layer.wantsExtendedDynamicRangeContent = NO
layer.maximumDrawableCount      = 3
layer.allowsNextDrawableTimeout = YES                      // default; nil after ~1 s instead of a hang
layer.presentsWithTransaction   = NO                       // default; YES costs CPU (waitUntilScheduled)
[CATransaction commit]
// Metal 4 only (macOS 26+): [mtl4Queue addResidencySet:layer.residencySet]
supportedModes = Collections.unmodifiableSet(EnumSet.of(FIFO, IMMEDIATE))
```

### 12.3 `configure(Configuration c)`

- Validate `1 ≤ w,h ≤ maxTexture2D (16384 on Apple GPUs)`. Otherwise throw `SurfaceException` (mirrors `VulkanGpuSurface.java:207-223`).
- In one disabled-actions transaction, set:
  - `layer.drawableSize = CGSize(c.width, c.height)`;
  - `layer.displaySyncEnabled = (c.presentMode == FIFO)`;
  - `layer.maximumDrawableCount = 3` (consider 2 only for a "low latency vsync" mode).
- Store the config and clear the suboptimal/eaten state.
- **No GPU wait is needed.** Drawables already handed out keep their old size, and only new `nextDrawable` calls get the new size.
- `configure` is only called while not acquired, so no drawable is held at this point.

### 12.4 `isSuboptimal()`

The simplest correct choice is `false`, as in GL, because the game already reconfigures on every pixel-size, display and iconify event. If you want robustness, return `true` only for an edge-triggered condition that `configure()` clears, for example "the last `nextDrawable` returned a texture whose size ≠ configured size". **Never compare against the window's backing size if a render scale is active.**

### 12.5 `supportedPresentModes()`

Return the cached `{FIFO, IMMEDIATE}`.

### 12.6 `acquireNextTexture()`

```text
if eatenException != null: e = eatenException; eatenException = null; throw new SurfaceException(e)
if isIconified.getAsBoolean(): throw new SurfaceException("Cannot acquire minimized window")
dropFrame = (SDL_GetWindowFlags(window) & SDL_WINDOW_OCCLUDED) != 0      // silent, no exception (see §10.3)
// Option A (eager, Vulkan-like): drawable = retained([layer nextDrawable]) inside a local autorelease pool;
//                                if nil → throw new SurfaceException("nextDrawable timed out")
// Option B (lazy, recommended): do nothing here; get the drawable inside blitFromTexture
```

Why lazy acquisition is better on Metal:

- Apple's guidance is to "request a new drawable only when you need it, and release any references to it as quickly as possible". The game calls acquire *before* all CPU work of the frame (`Minecraft.java:1273`), so eager acquisition holds 1 of the 3 drawables for the entire frame. That makes it more likely that the next `nextDrawable` blocks.
- Blocking at the end of the frame (lazy) gives the same throughput, and the CPU work of frame N overlaps with display of N-1.
- The trade-off: a `nil` from `nextDrawable` inside `blitFromTexture` cannot be thrown as a checked exception. Record it as `eatenException`, mark the frame dropped, and let the next `acquireNextTexture` throw it. The game then reconfigures, exactly like Vulkan's OUT_OF_DATE path.

### 12.7 `blitFromTexture(CommandEncoderBackend enc, GpuTextureView src)`

The frontend guarantees there is no open render pass.

1. If `dropFrame`, return.
2. *(Classic API)* Optionally **commit** the frame's accumulated command buffer first, so the GPU starts on the frame while the CPU possibly waits in `nextDrawable`. Then start a fresh command buffer for the present pass.
3. Lazy path: `drawable = nextDrawable` (inside an autorelease pool, then `retain`). If nil: set `eatenException`, mark dropped, and return.
4. `copyW = min(drawable.texture.width, src.getWidth(0))`, `copyH = min(drawable.texture.height, src.getHeight(0))`.
5. Render pass descriptor: color[0] = `drawable.texture`, `loadAction = (copyW == dw && copyH == dh) ? DontCare : Clear(0,0,0,1)`, `storeAction = Store`.
6. Encoder: viewport `(0,0,copyW,copyH)` (or full viewport plus a scissor), pipeline `present_bgra8` (color format BGRA8Unorm, no blending, no depth). Bind the source as a texture *view* at `src.baseMipLevel()` (or pass the mip as a constant), then `drawPrimitives(triangle, 0, 3)`.
7. *(Classic API)* `[cmdBuf presentDrawable:drawable]` here, because `submit()` (the commit) comes **before** `present()` in `Minecraft.renderFrame`.
8. Hazards: with default (tracked) resources in the same command buffer, Metal orders the GUI pass's writes before this read automatically. With **untracked** resources or **Metal 4** (no automatic hazard tracking), add an explicit barrier: Metal 4 `barrierAfterQueueStages:MTLStageFragment beforeStages:MTLStageFragment` (or the encoder-level consumer barrier), or an `MTLFence` between the last write and this pass.

Minimal MSL for the present pass (exact texel fetch, Y flip, swizzle and alpha handled implicitly):

```metal
#include <metal_stdlib>
using namespace metal;
struct VOut { float4 pos [[position]]; };
vertex VOut present_vs(uint vid [[vertex_id]]) {                 // full-screen triangle, no vertex buffer
    float2 p = float2((vid << 1) & 2, vid & 2);
    VOut o; o.pos = float4(p * 2.0 - 1.0, 0.0, 1.0); return o;
}
struct PresentParams { uint copyHeight; uint mip; };
fragment half4 present_fs(VOut in [[stage_in]],
                          texture2d<half, access::read> src [[texture(0)]],
                          constant PresentParams& P [[buffer(0)]]) {
    uint2 d = uint2(in.pos.xy);                       // drawable pixel, row 0 = top of screen
    uint2 s = uint2(d.x, P.copyHeight - 1 - d.y);     // GL/Vulkan row 0 = bottom → flip (matches VulkanGpuSurface dst offsets)
    return half4(src.read(s, P.mip).rgb, 1.0h);       // BGRA8 store swizzles automatically
}
```

For a render-scale or upscale variant, replace `read` with `sample` (linear or bicubic) using `uv = float2(d.x / dw, 1 - d.y / dh)`, or chain a MetalFX spatial scaler (MetalFX does not flip, so you still need this pass).

### 12.8 `present()`

**Classic `MTLCommandQueue`:** the drawable was already registered with `presentDrawable:` and the buffer was committed in `submit()`. `present()` only has to `release` the retained drawable, pop the per-frame autorelease pool, and clear `dropFrame`. Do **not** call `[drawable present]` again. Calling `[drawable present]` here instead would require the command buffer to already be *scheduled* (`waitUntilScheduled` costs CPU), so prefer `presentDrawable:`.

**Metal 4 (`MTL4CommandQueue`)**, which maps more naturally onto RenderPearl's separate `submit()` and `present()`:

```text
blitFromTexture: encode the present pass into a *separate small* MTL4CommandBuffer P (main frame work is in buffer F)
submit():        [queue commit:&F count:1];            // frame work starts immediately
                 [queue waitForDrawable:drawable];     // GPU-side wait: display no longer using it
                 [queue commit:&P count:1];            // only the present pass waits for the drawable
                 [queue signalDrawable:drawable];      // rendering to the drawable is complete
                 [queue signalEvent:frameEvent value:++n];
present():       [drawable present]; release drawable; pop pool
```

Keeping `waitForDrawable:` just before the *small* present command buffer stops the whole frame's GPU work from waiting on the display. Add `layer.residencySet` to the queue once, at surface creation.

Optional pacing APIs:
- `presentDrawable:afterMinimumDuration:` (classic `MTLCommandBuffer`; `[drawable presentAfterMinimumDuration:]` on Metal 4) can cap at a refresh multiple without vsync semantics, e.g. `1.0/120`.
- `presentDrawable:atTime:` schedules a present for a given time.
- `[drawable addPresentedHandler:]` provides `presentedTime` for frame-time statistics. Neither pacing API should be used in "Unlimited" mode.

### 12.9 `close()`

1. Wait for the last frame's event (so no in-flight command buffer references the layer's drawables).
2. Release any held drawable.
3. `SDL_Metal_DestroyView(view)` on the main thread.
4. Drop the layer pointer (it is owned by the view; do not release it).
5. Metal 4: `removeResidencySet:`.

### 12.10 Autorelease pools and object lifetime (critical)

- With `-XstartOnFirstThread`, the JDK launcher runs `JavaMain` on thread 0 inside one outer `NSAutoreleasePool` that is only drained at exit. **Every autoreleased Objective-C object created from Java is never freed.** That includes `nextDrawable`, `commandBuffer`, `renderPassDescriptor`, `renderCommandEncoderWithDescriptor:`, `CGColorSpace` wrappers, and so on.
- For drawables this causes **pool starvation**: drawables return to CA's pool only when released, so after `maximumDrawableCount` frames `nextDrawable` blocks for 1 s and returns nil.
- SDL's own `@autoreleasepool` in `SDL_PumpEvents` does not cover backend calls.
- **Rules:**
  - (a) Wrap each backend entry point that touches ObjC in `objc_autoreleasePoolPush()`/`objc_autoreleasePoolPop()`. Both are exported by `libobjc`: get them with `ObjCRuntime.getLibrary().getFunctionAddress(...)` and call via `JNI.invokeP`/`invokePV`, or use FFM.
  - (b) `retain` anything that must outlive that scope (the drawable until `present()`, command buffers until completion) and `release` it explicitly.
  - (c) Alternatively, push a frame-scoped pool in `acquireNextTexture` and pop it in `present()`. This is LIFO-safe because everything in between runs on the same thread with balanced inner pools, **but** every acquire path that ends without a present must also pop (failed acquire, dropped frame, exception), so per-call pools are more robust.

### 12.11 `nextDrawable` blocking: causes and how to avoid stalls

`nextDrawable` blocks when all `maximumDrawableCount` drawables are in use (being rendered, queued for display, or on screen). It returns nil after ~1 s if `allowsNextDrawableTimeout` is set, if the layer has a zero size, or if the format or device is invalid.

How to avoid stalls:
- `maximumDrawableCount = 3`.
- With IMMEDIATE (`displaySyncEnabled = NO`), drawables are released as soon as a newer one replaces them, so blocking is rare even at around 1000 FPS.
- With FIFO, blocking is the intended vsync throttle (≤120 FPS on ProMotion).
- Acquire **lazily**, right before encoding the present pass (§12.6).
- Release the drawable right after `presentDrawable:`/`present`. Never cache `drawable.texture` across frames.
- Drain autorelease pools (§12.10).
- Bound frames in flight to 2 with an `MTLSharedEvent`, so the CPU does not get far ahead of the display.
- Skip `nextDrawable` entirely when minimized (throw) or occluded (silent drop).
- Classic API: commit the bulk of the frame's work **before** calling `nextDrawable`, so a wait never starves the GPU.

### 12.12 Blit vs render-to-drawable vs rendering the final pass into the drawable

- **`MTLBlitCommandEncoder copyFromTexture:` is not usable as a drop-in:**
  - it requires identical (or sRGB-sibling) pixel formats, but the source is RGBA8 and the drawable must be BGRA8;
  - it cannot flip or scale;
  - it needs `framebufferOnly = NO`, which can cost display/compression optimizations.
- **A render pass into the drawable is the right primitive.** One draw does the conversion, flip, alpha and optional scaling, and it works with `framebufferOnly = YES`. On a TBDR GPU it costs essentially only the memory traffic.
- **Memory traffic** of the present copy at 3024x1964: 5.94 MP × 4 B = 23.8 MB read + 23.8 MB write, about **47.5 MB per frame**. At 1000 FPS that is about **47.5 GB/s**, roughly a third of an M5's ~150 GB/s class bandwidth. At very high FPS the present copy is a first-order cost. Mitigations:
  1. Keep the main color target in `MTLStorageModePrivate`, with usage `RenderTarget | ShaderRead` only (no `ShaderWrite`/`PixelFormatView`), so Apple's lossless framebuffer compression stays enabled. The present pass then reads compressed data.
  2. **Render the final GUI pass straight into the drawable.** This is not expressible through the current API: the game renders into `MainTarget` and then calls `blitFromTexture`. It would need either:
     - (a) a game-side API extension, e.g. `GpuSurface.currentTextureView()` used as the GUI pass's target, plus a Y-flipped projection for that pass only; or
     - (b) a backend-only hack where the main target's color `GpuTexture` is a proxy whose `MTLTexture` is swapped each frame to the current drawable texture.

     Option (b) requires eager acquisition, `framebufferOnly = NO`, a CA-level vertical flip, BGRA swizzles in `copyTextureToBuffer` (screenshots, Tracy), and handling of size mismatches. That is high risk; prototype only after measuring.
  3. **Render scale** (§13): fewer pixels everywhere, and the present pass does the upscale for free.

### 12.13 ProMotion (120 Hz) considerations

- The built-in Liquid Retina XDR panel refreshes adaptively up to 120 Hz. macOS composites `CAMetalLayer` content at up to 120 Hz without any Info.plist opt-in, so **FIFO caps the game at 120 FPS**. Low Power Mode (on battery) can cap it at 60 Hz.
- Vsync on and below 120 FPS: in fullscreen (direct-to-display), macOS can drive the panel adaptively (VRR-like), so pacing is smooth without hard 120/60 quantization. In windowed mode the compositor's cadence governs.
- Vsync off ("Unlimited"): IMMEDIATE renders as fast as possible. Only ≤120 frames per second reach the glass; the extras only reduce input-to-photon latency slightly and cost power and heat, which on a fanless or quiet MBP can lead to throttling.
- A "cap at refresh without vsync" option can be built with `presentDrawable:afterMinimumDuration:(1/120)`, or with the game's own `FramerateLimiter`.
- `CAMetalDisplayLink` (macOS 14+) provides drawables at ideal times. It is callback-driven and does not fit Minecraft's loop, so it is not recommended for max FPS.
- Refresh-rate reporting: `SDL_DisplayMode.refresh_rate` for the built-in ProMotion panel. Verify what SDL 3.4 reports (`CGDisplayModeGetRefreshRate` historically returns 0 for built-in panels). It matters only for the exclusive-fullscreen mode list.

### 12.14 Fullscreen and direct-to-display

When an **opaque** `CAMetalLayer` covers the entire display in a fullscreen Space with a compatible pixel format (BGRA8Unorm), WindowServer can scan the drawable out **directly** (bypassing composition). That gives lower latency, no compositor GPU pass, and makes IMMEDIATE able to tear.

- Check it with the Metal Performance HUD (`MTL_HUD_ENABLED=1` env var, or `layer.developerHUDProperties`).
- Things that may force composition: a non-nil colorspace that differs from the display's, a visible menu bar (`macFullscreenMenuVisibility`), overlays, and the notch safe area. **Measure** whether `colorspace = sRGB` costs direct mode. If it does, offer "color-managed (sRGB)" vs "native (nil colorspace)" as an option.

### 12.15 ObjC interop notes (for the layer configuration code)

- `CAMetalLayer` setters taking `BOOL`/`id`/`NSUInteger` can be called through `objc_msgSend` with LWJGL `ObjCRuntime` + `JNI.invokePPV(long self, long sel, boolean, long fn)` / `invokePPPV`.
- `setDrawableSize:` takes a `CGSize` (two doubles, passed as an HFA in d0/d1 on arm64). LWJGL 3.4.3's `JNI` has **no** `(ptr, ptr, double, double)` variant. Use the JDK 25 FFM `Linker` (`--enable-native-access=ALL-UNNAMED` is already in the launcher JVM args) with a struct layout of two `JAVA_DOUBLE`, or a tiny native helper dylib.
- `java-objc-bridge` (`ca.weblite.objc`) is also on the classpath (used by `MacosUtil`). It is too slow for per-frame use.

---

## 13. Performance opportunities (backend and game side)

1. **Single-pass present** (render pass, `framebufferOnly = YES`, `DontCare` load when the copy covers the drawable, `texture.read` without a sampler). This is the minimal possible cost for the mandatory flip and swizzle.
2. **Lazy drawable acquisition plus an early commit** of the frame's work before `nextDrawable` (classic API), or a split command buffer around `waitForDrawable:` (Metal 4). This avoids frame-start stalls and keeps the GPU busy.
3. **Cheap `configure()`.** No `waitIdle`, unlike Vulkan's swapchain rebuild.
4. **Frames-in-flight cap = 2** via `MTLSharedEvent` (like `VulkanCommandEncoder.java:270`). This bounds latency with IMMEDIATE.
5. **Render scale** (biggest lever at Retina). Game side, scale `framebufferWidth/Height` in `Window.refreshFramebufferSize/onFramebufferResize/queryFramebufferSize` (`Window.java:373-431`) by a factor, and scale `getPixelDensity()` for the IME. Backend side, either:
   - (a) set `drawableSize` = configured (scaled) size with `SDL_VIDEO_METAL_AUTO_RESIZE_DRAWABLE=0` and let Core Animation upscale for free (`layer.magnificationFilter` linear/nearest); or
   - (b) keep the drawable at full size and upscale in the present shader (bilinear/bicubic), or with MetalFX spatial plus the flip pass.

   At scale 0.5 on 3024x1964, all per-pixel work drops by 4x.
6. **Quick "Retina off" toggle.** Game side, drop `SDL_WINDOW_HIGH_PIXEL_DENSITY` from `Window.createWindow` flags (`Window.java:164-165`, `0x2020 → 0x20`). SDL's metal view then sizes the drawable in points and the compositor upscales. It renders 4x fewer pixels with zero backend work, but needs a window recreate to toggle.
7. **Warn about "More Space" scaled display modes**, which render more pixels than the panel has (§4.2).
8. **Lossless compression friendliness**: main color target private with no ShaderWrite, so the present read and GUI passes move less data.
9. **Fullscreen direct-to-display** (opaque, BGRA8, full-screen Space). Verify whether the sRGB colorspace prevents it.
10. **Stop work while invisible.** Game side, skip `gameRenderer.render()` when the surface isn't acquired (minimized: it currently renders at 10 FPS for nothing). Throttle on `SDL_EVENT_WINDOW_OCCLUDED` (534) like iconify (currently unhandled).
11. **Presenting faster than 120 Hz is wasted on the panel.** For power and thermals (sustained clocks on an MBP), offer a "cap at 2x refresh" via `presentDrawable:afterMinimumDuration:` while keeping vsync off.
12. **Event pumping.** `SDL_PumpEvents` (an AppKit event loop spin) runs every frame in `RenderSystem.pollEvents` (`Minecraft.java:918`). At ~1000 FPS its fixed cost (tens of µs) is a few percent. Measure it with the Tracy/F3 profiler ("Client Tick" / "frame" zones).
13. **Avoid per-frame ObjC allocation churn.** Pre-create the present pipeline state and render pass descriptor (mutate only the texture), use `commandBufferWithUnretainedReferences` / Metal 4 reusable command buffers, and keep pools tight.

---

## 14. Pitfalls checklist

- [ ] `layer.device` is not set by SDL. Forgetting it makes `nextDrawable` return nil.
- [ ] `RGBA8Unorm` is not a legal `CAMetalLayer.pixelFormat`. Setting it raises an exception.
- [ ] `maximumDrawableCount` must be exactly 2 or 3. Any other value raises an exception.
- [ ] Autorelease pools: never let `nextDrawable` objects live in the launcher's never-drained pool (§12.10).
- [ ] Classic API: `presentDrawable:` must be registered **before** `commit`, and `commit` happens in `submit()`, which runs before `present()`.
- [ ] Do not throw from `blitFromTexture`/`present` for recoverable conditions. Defer the error to the next `acquireNextTexture` (Vulkan's eatenException pattern).
- [ ] Do not throw `SurfaceException` for occlusion (it causes log spam and configure churn). Do throw for iconified.
- [ ] `isSuboptimal()` must be edge-triggered and cleared by `configure()`, never permanently true.
- [ ] The Y flip in the present pass must match the render-target row-order convention chosen by the pipeline layer.
- [ ] Clear uncovered drawable regions when the copy is smaller than the drawable (resize transitions).
- [ ] Honor `textureView.baseMipLevel()` (Vulkan does; GL ignores it).
- [ ] SDL's EARLY watch may change `drawableSize` before the game's reconfigure (hint default "1"). Either set the hint to "0" before `SDL_Metal_CreateView` and own the size, or accept that `drawableSize` may change between frames and always use the actual `drawable.texture` size for the copy rectangle.
- [ ] Wrap layer property changes in `CATransaction` with actions disabled. All calls happen on the main thread, which matches the game thread here.
- [ ] Metal 4: add `layer.residencySet` to the queue, use `waitForDrawable:`/`signalDrawable:`, and insert explicit barriers (no automatic hazard tracking).
- [ ] Load spvc unconditionally for the Metal backend (`NativeLibrariesBootstrap.java:82-86`).
- [ ] `FrontendGpuSurface.present()` clears `hasImageAcquired` only after `backend.present()` returns. An exception there leaves the surface "acquired" forever, and `close()` would then throw.

---

## 15. Open questions / to verify on device

1. Does the java process get `NSHighResolutionCapable` behaviour on macOS 27? Expect yes (SDK 14.2 linkage); confirm F3 shows `2.00x pixel density`.
2. Is fullscreen on the notched 14" panel 3024x1964 or 3024x1890? This affects pixel count and direct-to-display eligibility.
3. Does `layer.colorspace = sRGB` disable direct-to-display in fullscreen on macOS 27? If so, make it an option.
4. How do `nextDrawable` / `displaySyncEnabled = YES` behave when the window is occluded or on another Space on macOS 27? Is a 1 s stall still possible, and does it need the occlusion-drop path?
5. Which convention will the pipeline doc pick for render-target row order in Metal (negated clip Y vs negative-height viewport)? The present flip depends on it.
6. Should MAILBOX be reported? It is never chosen by `getSupportedVsyncMode`, so it doesn't matter functionally.
7. Classic `MTLCommandQueue` vs `MTL4CommandQueue` for the whole backend. The surface design differs mainly in `present()` (§12.8).
8. Interop technology for ObjC calls (FFM vs LWJGL JNI vs a native helper dylib). `CGSize` setters need FFM or native code.
9. Can macOS Game Mode be triggered for a `java` process without an `LSApplicationCategoryType = public.app-category.games` bundle?
10. What does SDL 3.4.14 report as `refresh_rate` for the ProMotion panel, and does exclusive fullscreen on macOS 27 still change modes through CoreGraphics?
11. Is it worth implementing render-into-drawable for the final GUI pass (§12.12)? Measure the present-pass cost with the Metal HUD / Instruments first.
