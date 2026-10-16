# Direct-to-display vs. WindowServer composition (fullscreen CAMetalLayer, M5, macOS 27)

Status: research (task D), 2026-10-04. No code was changed. Every claim is tagged:
**[M]** measured here, **[S]** from a cited source, **[I]** inference.

## 0. TL;DR

1. **The most likely cause of our composition is environmental, not our layer setup.** Two system alert
   panels owned by `UserNotificationCenter` (window layer 8, the modal-panel level) sit on top of every
   Space, including the fullscreen one, and overlap our layer. The first one appeared at **16:14:32**. That
   was the first launch of the Metal build, and every Metal measurement (`metal-first` at 16:15 onward) came
   after it. The vanilla Vulkan Metal System Trace (`vk-mst.trace`, 13:08) came before it, and that is the
   only run that showed WindowServer at ~0 GPU. The second panel appeared at 16:59:25, during the first probe
   runs. [M]
   - Evidence: `CGWindowListCopyWindowInfo(OnScreenOnly)` taken during a fullscreen probe run lists
     `UserNotificationCenter` windows at layer 8, sized 260x318 pt at (799,243) and (770,214), above our
     layer-0 1800x1130 window. WindowServer's focus log shows `UserNotificationCenter` becoming frontmost at
     16:14:32, 16:15:47–58 and 16:59:25/29, and at no other time since 12:00. [M]
   - Apple: composition happens when "system UI elements or your own additional layers [overlap] the
     screen" [S2]. CoreAnimation's own detach-reason codes include `kCADetachReasonCodeNotClearAboveBlackBelow`
     and `kDisplayDetachReasonCodeDisplayNotClearAbove` [M, strings in QuartzCore].
   - **Action for the user (not the agent): look at the screen, read the two alerts and dismiss them. Then
     re-measure.** I could not read their text, because this session has neither screen-recording nor
     accessibility permission, and I did not touch them.
2. **The 120 FPS cap with 3 drawables is how Core Animation behaves on the composited path. It is not a bug
   in our code.** With the layer composited and `displaySyncEnabled = NO` [M]:
   - With `maximumDrawableCount = 3`, presentation is strict FIFO at refresh. Presented times are exactly
     8.33 ms apart, nothing is dropped, `nextDrawable` blocks for about 8.1 ms, and FPS is 120.0.
   - With `maximumDrawableCount = 2`, WindowServer composites on every present (presented intervals ~1.1–1.7
     ms, 0 drops, 390–620 FPS in the probe). WindowServer's GPU share roughly doubles, to about 40%.
   - **MoltenVK 1.4.2 (the game's own dylib) driving the same probe window behaves identically**: IMMEDIATE
     with 3 images gives 120.0 FPS and 100% composited, and with 2 images gives 393 FPS, 100% composited.
     The layer configuration is therefore not what separates vanilla Vulkan (346 FPS) from us. The
     environment is.
   - The MoltenVK-style experiment (scheduled-handler `[drawable present]` with 3 drawables) runs at a flat
     **240 = 2 × 120** in the probe, with 50% of drawables dropped. In game it runs at a flat 240
     (`results/raw/mtl-mvk.json` `perSecondFps` is 240 every second; the "268 median" is a
     frame-time-median artefact). This is also composited behaviour.
3. Once the layer is really direct-to-display, the vanilla configuration should be best: 3 drawables,
   `displaySyncEnabled = NO`, opaque, sRGB, `framebufferOnly`. The vanilla trace shows this configuration
   uncapped at ~330–345 FPS with **0 ms** of WindowServer GPU time [M]. The current workaround (2 drawables)
   makes WindowServer composite every frame. That costs ~19% of GPU time in game (`mtl-mst.trace`: 928.6 ms
   of WindowServer GPU busy in 4.8 s) [M].

## 1. Eligibility conditions for direct-to-display (macOS, Apple silicon)

### 1.1 Documented by Apple [S]

| # | Condition | Source |
|---|---|---|
| E1 | The app runs in **full-screen mode** (Apple recommends `-[NSWindow toggleFullScreen:]`, which puts the window in its own Space; SDL3 uses it for desktop fullscreen of resizable windows). | [S1] |
| E2 | It displays an **opaque `CAMetalLayer`** (`layer.opaque = YES`). | [S1], [S4] (Zed PR: `opaque = NO` alone blocked direct) |
| E3 | The content is **RGB**. "All of the RGB formats supported by Metal layers are capable of drawing direct-to-display content." | [S1] |
| E4 | **Apple silicon.** | [S1] |
| E5 | **No overlapping system UI and no other layers of your own over the screen.** Otherwise the frame is composited, with "additional buffering and display latency". | [S2] |
| E6 | The drawable size does **not** need to match the display: "the macOS compositor efficiently sends the drawable directly to the display, even if the drawable size doesn't match the monitor size", using hardware scaling. `contentsGravity = kCAGravityResizeAspect` plus a black background is fine for aspect mismatch. | [S1] |
| E7 | "There may be other edge case conditions depending on the hardware and system software." Verify with the Metal Performance HUD (it shows *Direct* or *Composited*) or with Instruments. | [S1], [S2] |

### 1.2 Undocumented conditions (CoreAnimation detach-reason codes in macOS 27 QuartzCore) [M]

These are string constants dumped from the loaded QuartzCore image (the `hudstr` tool in §5). Each names a
reason WindowServer will not "detach" a surface, i.e. will not scan it out directly. Interpretations are [I].

- **Layer tree:** `NonOpaque`, `NonAffine`, `Rotation`, `RectClipped`, `Nested`, `NonScreenSpace`,
  `UnderBackdrop` (a blur or backdrop layer over it), `HasRenderState`, `LayerFeatures`, `UsingClipPlanes`,
  `HasMixCoords`, `HasCenterGeometry`, `HasOuterEdgeSubFilter`, `NonWhite` (a tint or contents multiply
  color), `NonCanonicalLayer`, `ZeroDeviceScale`, `Offscreen`, `NullSurface`, `SpansMultipleDisplays`.
- **Color:** `MismatchEDR`, `GPUTonemappingRWTM`; display-side `ColorMatrixNotSupported`. Keep
  `wantsExtendedDynamicRangeContent = NO` and use an SDR colorspace. MoltenVK's sRGB was direct in vanilla.
- **Overlap:** `NotClearAboveBlackBelow` (everything above must be transparent and everything below black)
  and display-side `DisplayNotClearAbove`. This is the code our alert panels would trip [I].
- **Display pipe:** `DestRectTooLarge`, `CompressedSizeTooLarge`, `CompressionNotSupported`, `YUVTooSmall`,
  `PassThrough`, `TVCloneNotSupported`, and bandwidth limits (`gpBandwidth Exceeded. Detach disabled`).
- **Server:** `kServerDetachReasonCodeCursor` and `Cursor detach disabled: display frozen`. A visible cursor
  normally lives on its own hardware plane. SDL relative-mouse mode hides it in game anyway.
- **Policy:** `WindowServerRejected`, `DisplayRejected`, `NoDisplayCompositing`, debug kill-switches
  `CA_DISABLE_DETACHED` and `CADebug disabled detached`.
- A CoreAnimation log line ties detach state to Game Mode and vsync:
  `Disabled requested=%d disabled=%d wants_compositing_dependencies=%d detached=%d game_mode=%d vsync_disabled=%d`.

### 1.3 Our configuration against the list

| Item | Ours | Vanilla MoltenVK 1.4.2 (`MVKSwapchain.mm` `initCAMetalLayer`) | Verdict |
|---|---|---|---|
| Fullscreen | SDL3 `SDL_SetWindowFullscreen` → `toggleFullScreen:` (Space); window 1800x1130 pt below the notch | same | OK. Vanilla was direct in this exact window. |
| opaque | YES (SDL also sets YES for non-transparent windows) | `compositeAlpha OPAQUE` → YES | OK |
| pixelFormat | BGRA8Unorm | BGRA8Unorm | OK |
| colorspace | sRGB | sRGB (`colorspaceNameMVK = kCGColorSpaceSRGB`) | OK |
| EDR | NO | NO | OK |
| framebufferOnly | YES | NO (vanilla requests TRANSFER_DST) | Should not matter [S1]. Check after the overlays are gone (§3, step 2). |
| contentsScale | drawable.w / bounds.w = 2.0 | SDL keeps contentsScale = backing.h / size.h = 2.0 | Same value |
| contentsGravity, filters | default (resize, linear) | resize, linear (no scaling info), unless `MVK_CONFIG_SWAPCHAIN_MIN_MAG_FILTER_USE_NEAREST` | Same |
| maximumDrawableCount | 3 (2 with the workaround) | 3 (`minImageCount = max(3, min)`) | — |
| displaySyncEnabled | NO for IMMEDIATE | NO for IMMEDIATE, re-asserted per present in the scheduled handler | — |
| Present | `[cb presentDrawable:]` after a late `nextDrawable` | `[drawable present]` in `addScheduledHandler`; drawable fetched lazily at first texture use | Not the discriminator: MoltenVK in the probe window is also composited (§2) |

## 2. Measurements

### 2.1 Tooling

Everything here is in the session scratchpad,
`<temp>`.
Copy it into `tools/` if it should outlive the session.

- **`d2d.m` / `d2d`** is a standalone probe. It builds the window like SDL3: a titled, resizable `NSWindow`
  with `FullScreenPrimary`, a layer-backed content view with a black background, and a Metal subview whose
  backing layer is a `CAMetalLayer`. It enters fullscreen with `toggleFullScreen:` and hides the menu bar
  as SDL does. It renders `D2D_WORK` full-screen passes plus a present pass, and runs for about 9 s.
  - Settings come from environment variables: `D2D_DRAWABLES`, `D2D_SYNC`, `D2D_FBONLY`, `D2D_PRESENT`
    (0 `presentDrawable:`, 1 scheduled `present`, 2 `afterMinimumDuration`, 3 scheduled plus re-assert
    sync), `D2D_EARLY`, `D2D_BLIT`, `D2D_GRAVITY`, `D2D_NEAREST`, `D2D_CS` (srgb/nil/p3/screen), `D2D_W`/`D2D_H`,
    `D2D_DIRECTVIEW`, `D2D_FS` (space/borderless/none), and `D2D_KVC=key=val,...` for private layer
    properties.
  - `D2D_MVK=<libMoltenVK.dylib>` drives a MoltenVK swapchain configured like vanilla instead: 3 images,
    IMMEDIATE, `TRANSFER_DST`, OPAQUE, sRGB.
  - `D2D_WINLIST=1` dumps the on-screen windows.
  - Each run prints `RESULT composited=% fps wait_ms presented/s dropped/s int_med ... ws_gpu ...`.
  - Build:
    `clang -fobjc-arc -O2 -Iinc -o d2d d2d.m -framework Cocoa -framework Metal -framework QuartzCore -framework IOKit -sectcreate __TEXT __info_plist Info.plist`.
    `run.sh "<label>" VAR=val ...` runs one configuration.
- **Composition flag:** `CAMetalDrawable` has a private BOOL property `didComposite`, set by the time the
  presented handler runs. The probe reports the share of presented drawables with `didComposite == YES`.
  This is a debug-only private API; guard it with `respondsToSelector:`. The layer also exposes a private,
  read-only `displayCompositingInternalStatus`, which was 0 or 0xE0017 in our runs and is undecoded.
- **WindowServer GPU share:** the ioreg `IOAccelerator` user-client `AppUsage.accumulatedGPUTime` gives a
  rough per-process share. Ground truth is `xctrace record --template 'Metal System Trace' --all-processes`
  followed by exporting `metal-gpu-intervals` and running `gpusum.py`, which takes the union of busy
  intervals per process.
  - The Instruments schema `displayed-surfaces-interval` has **"Direct to Display"**, **"Direct to Display
    Failing Reason"** and **"Failing Suggestion"** columns. They were empty in CLI recordings here, probably
    because of privileges; open the trace in Instruments.app to see them.
- **Metal HUD:** `MTL_HUD_ENABLED=1` shows *Direct* or *Composited* on screen (`libMTLHud` strings
  "Direct", "Composited", "Display Layer State as Direct"). `MTL_HUD_LOG_ENABLED=1` writes per-frame
  `metal-HUD:` lines to stderr: frame, memory, and (interval, GPU ms) pairs. The log does **not** include
  the direct or composited state.

### 2.2 Results

All rows are 3600x2260 drawables in a fullscreen Space, displaySync off unless noted. All were recorded
with 1–2 alert panels on screen.

| Config (probe) | composited | FPS | presented interval | dropped/s | nextDrawable wait | WindowServer GPU |
|---|---|---|---|---|---|---|
| Ours: 3 drawables, fbOnly, `presentDrawable:`, late acquire | 100% | **120.0** | 8.33 ms (all) | 0 | 8.1 ms | 13–15% with 1 alert, 22–24% with 2 |
| Ours, 2 drawables | 100% | 570–620 | 1.1–1.25 ms | 0 | 1.5 ms | ~40% |
| Ours, vsync on | 100% | 120 | 8.33 | 0 | 8.1 | 29% |
| MoltenVK-like (3, fbOnly NO, blit, scheduled present plus re-assert sync, topLeft, nearest, early acquire) | 100% | **240.0** | 8.33 | **120** | 4.0 | 26% |
| MoltenVK 1.4.2 swapchain, 3 images, IMMEDIATE (vanilla config) | 100% | **120.0** | 8.33 | 0 | — | 7–24% |
| MoltenVK 1.4.2, 2 images, IMMEDIATE | 100% | 393 | 1.66 | 0 | — | 45% |
| MoltenVK 1.4.2, 3 images, MAILBOX | 100% | 120 | 8.33 | 4 | — | 30% |
| Ours 3 with colorspace nil, metal layer as the content view's own layer, cursor visible, `presentDrawable:afterMinimumDuration:`, scheduled present, drawable 3024x1898 or 1800x1130, game-category `.app` with Game Mode **on** (gamepolicyd: "Game mode enabled") | 100% | 120 | 8.33 | ~0 | 8.1 | 22–25% |
| Ours 3 plus private `disableFIFO=1`, `lowLatency=1` or `serverSyncEnabled=0` | 100% | 120 | 8.33 | ~0 | 8.2 | 23% |
| Ours 2 plus private `wantsIOSurfaceCompression=1` or `allowsDisplayCompositingWithCopy=1` | 100% | 544–558 vs 621 baseline | — | — | — | 41% (no gain) |
| Borderless window covering the whole screen, including the notch strip (not a Space) | 100% | 650 | 3.0 ms | 323 | 1.5 | 85% |

Game traces in `results/raw` (process-attributed GPU busy, from `gpusum.py`) [M]:

| Trace | Time | java GPU busy | WindowServer GPU busy | Alerts on screen |
|---|---|---|---|---|
| `vk-mst.trace` (vanilla Vulkan, 3 images, IMMEDIATE) | 13:08 | 97.2% | **0.0%** | none (the first alert appeared at 16:14:32) |
| `mtl-mst.trace` (Metal, 2 drawables) | 16:28 | 95.0% | **19.4%** | 1 |

WindowServer's own log, during the fullscreen transition of a probe run, shows the scaler being evaluated
for our surface: `ASEProcessing shouldEnhance… src={3600w x 2260h}, dest={3024w x 1898h}`. The display path
is a hardware downscale from 3600x2260 to the 3024x1898 panel rectangle below the notch [M].

## 3. Ranked experiments for the orchestrator

Experiments are in order. Each lists the exact change and the expected effect.

### Step 1 (required before any other experiment): remove the overlays and verify direct

1. **Have the user dismiss both `UserNotificationCenter` alerts.** Add an overlay check to the benchmark
   harness: before each run, list on-screen windows above layer 0 that overlap the main display and are not
   owned by WindowServer or Dock. `CGWindowListCopyWindowInfo(kCGWindowListOptionOnScreenOnly, …)` with
   `kCGWindowLayer`, `kCGWindowOwnerName` and `kCGWindowBounds` needs no permission. Abort or flag the run if
   any such window exists. The menu-bar strip `Menubar` (layer 24, 0,0 1800x39) does not overlap the
   fullscreen window and was present in the direct vanilla run.
   **Expected effect:** WindowServer GPU goes from ~19% to ~0. That frees ~0.5–0.7 ms of GPU per frame at
   ~300 FPS plus a full-screen read per frame of memory bandwidth. Our layer should go direct.
2. **Re-run the probe matrix with the overlays gone.** About 10 s per row:
   - `./run.sh A D2D_DRAWABLES=3`
   - `./run.sh B D2D_DRAWABLES=2`
   - `./run.sh C D2D_DRAWABLES=3 D2D_FBONLY=0`
   - `./run.sh D D2D_DRAWABLES=3 D2D_PRESENT=1`
   - `./run.sh V D2D_MVK=<run/vanilla/.natives/lwjgl/3.4.3+4/arm64/libMoltenVK.dylib>`

   **Expected:** row V shows `composited=0%`, FPS far above 120 and sub-8 ms presented intervals (vanilla
   behaviour). If A also shows `composited=0%`, our layer is eligible as is. If V is direct and A is not,
   bisect along C and D, then gravity, colorspace and contentsScale (§3, step 4, item 10).
3. **In game, with the overlays gone:** run `MCMTL_DRAWABLES=3` (vsync off) against `MCMTL_DRAWABLES=2`.
   Profile with Metal System Trace and `gpusum.py`, or check the HUD label.
   **Expected:** with 3 drawables, uncapped and direct, WindowServer at ~0%, FPS above today's ~300 (vanilla
   Vulkan reached 345 with the same frame cost plus its blit). Make 3 the default for IMMEDIATE again, and
   keep 2 only if 3 measures worse. On the direct path, 2 drawables can stall the GPU while waiting for
   scanout to release a buffer [I].
   **Done** with mailbox presentation (`docs/metal/CONTRACT.md` §16.4, `MCMTL_MAILBOX=1`, default):
   - IMMEDIATE uses 3 drawables again.
   - A helper thread waits in `nextDrawable`, so the composited 3-drawable FIFO pacing caps only the presented frames
     (about one per refresh), not rendering.
   - `MCMTL_MAILBOX=0` restores the 2-drawable blocking path.

### Step 2: make it robust

4. **Ship a built-in direct/composited diagnostic,** behind `MCMTL_PROFILE_PASSES` or a new
   `MCMTL_D2D_DIAG=1`. On every N-th drawable, `addPresentedHandler`, read `presentedTime` and (only if
   `respondsToSelector:@selector(didComposite)`) the private `didComposite`. Log the composited % and the
   median presented interval once a second, and log the overlay scan from item 1 when the composited share
   rises above 0. Composited plus 3 drawables plus sync off means a 120 FPS cap, so the log explains any
   cap immediately.
   **Expected:** no FPS change; the next regression is caught without Instruments.
5. **Fix the spec text (§6.4)** that claims composited IMMEDIATE behaves mailbox-like. It is FIFO at refresh
   with 3 drawables and per-present composition with 2 [M].

### Step 3: FPS gains enabled by the direct path

6. **Render at panel-native resolution and let the display pipe scale (largest gain).** In the "More
   Space" scaled mode, the backing store is 3600x2338 but the panel is 3024x1964. A direct-to-display
   surface is scaled by display hardware at ~no cost [S1]: WindowServer logged 3600x2260 → **3024x1898**.
   - Rendering the game framebuffer at 3024x1898 (or simply 3024x1898 drawables if the frame resolution can
     be decoupled) cuts 8.14 M px to 5.74 M px, **−29.5%**. Displayed pixels stay the same and one resample
     is removed.
   - **Expected:** up to ~25–30% less GPU time on the bandwidth-bound passes (Main, clears, present). The
     per-pass profile shows over half the frame is full-screen bandwidth.
   - Parity caveat: vanilla renders 3600x2260 in this display mode, so parity scenes would need to be
     compared at equal resolution.
   - Options:
     - (a) Patch `Window` framebuffer-size reporting on the Metal path, plus `drawableSize` =
       `NSScreen.backingScaleFactor`-independent panel pixels from
       `CGDisplayModeGetPixelWidth/Height(CGDisplayCopyDisplayMode)` scaled by the window's share of the
       screen.
     - (b) Tell the user that the "Default" display scaling (1512x982 pt → 3024x1964 backing) gives the same
       result with no code change.
     - (c) Exclusive fullscreen with a native mode, which Minecraft already offers (`exclusiveFullscreen`).
       Only use it if direct is confirmed there too.
7. **Present from the scheduled handler** (`MCMTL_PRESENT_MODE=1`, as MoltenVK does). MoltenVK's source
   says this is "more performant" according to Apple [S5].
   **Expected:** small CPU-latency gain, and only once direct. On the composited path it caused the
   240-flat pattern, so gate it on direct.
8. **Remove the 0.47 ms present pass when direct.** Have the decoder retarget the frame's final pass (GUI)
   at the drawable texture, folding the Y-flip into the viewport and projection, so the frame never does a
   full-screen copy into the drawable. This needs the drawable acquired before the GUI pass, i.e. acquire
   timing moves earlier, which is what MoltenVK does.
   **Expected:** −0.3 to −0.47 ms GPU per frame, worth +10–15% FPS at 300+ FPS. This is a larger change;
   track it as a spec §9 B-item.
   **Implemented** as present fusion (`docs/metal/CONTRACT.md` §16.3, `MCMTL_PRESENT_FUSION=1`, default):
   - The Y flip is not folded into the viewport. The layer is mirrored instead, and the decoder writes the main
     target's bytes into the drawable unchanged.
   - Whether the mirrored layer stays direct is unverified. Check it with the HUD: `MCMTL_PRESENT_FUSION=0`
     against `MCMTL_PRESENT_FUSION_FLIP_ONLY=1`.
   - With 2 drawables on a composited layer, an adaptive guard falls back to the present pass when early
     drawable waits idle the GPU.
9. **Game Mode (optional).** `java` has no `LSApplicationCategoryType`, so gamepolicyd never enables Game
   Mode for Minecraft. A probe `.app` with `LSApplicationCategoryType = public.app-category.games` did get
   it ("Game mode enabled", "Set DPS to 2"), but this changed neither the composition nor the cap [M].
   **Expected:** possibly steadier CPU and GPU priority. It needs a launcher-side `.app` wrapper, so it is
   low priority.

### Step 4: fallbacks if still composited with no overlays

10. Bisect in this order, one variable at a time, with the item 4 diagnostic:
    - `framebufferOnly = NO` (vanilla's value)
    - `colorspace = nil`
    - `contentsGravity = kCAGravityTopLeft`, so any rounding between `bounds × contentsScale` and
      `drawableSize` is never a non-affine stretch
    - set `contentsScale` from height as SDL does (`drawable.h / bounds.h`), not from width
    - menu-bar hint `SDL_VIDEO_MAC_FULLSCREEN_MENU_VISIBILITY=0` (vanilla default; if it were 1 the menu bar
      would overlay when revealed)
    - cursor hidden or relative mode on

    Then open a Metal System Trace in **Instruments.app** and read the *Direct to Display Failing Reason*
    and *Failing Suggestion* columns of the Display track.
11. **Not recommended:** private `CAMetalLayer` properties (`disableFIFO`, `lowLatency`, `serverSyncEnabled`,
    `wantsIOSurfaceCompression`, `allowsDisplayCompositingWithCopy`). None changed the cap or the
    composition, and two cost FPS [M]. A private API also risks breaking on OS updates.

## 4. Why each symptom happened (summary)

- **WindowServer ~19% GPU with our backend, ~0 with vanilla Vulkan.** The vanilla trace (13:08) predates
  the first alert panel (16:14:32). Every Metal-backend run came after it. With an overlay on screen, every
  fullscreen layer is composited [S2]: the probe shows 100% `didComposite` for both our Metal path and
  MoltenVK's. With 2 drawables and sync off, WindowServer composites on every present, so its GPU cost
  scales with our FPS [M].
- **120 cap with 3 drawables and sync off, none with 2.** This is the composited-path pacing of
  CoreAnimation/WindowServer on macOS 27. With 3 drawables, FIFO at refresh ignores `displaySyncEnabled = NO`
  for pacing. With 2, it composites immediately per present. MoltenVK shows exactly the same in the same
  window [M]. The exact internal rule is undocumented [I]. On the direct path, vanilla's 3-image IMMEDIATE
  swapchain ran uncapped [M].
- **MoltenVK-style experiment: "uncapped 268" and WindowServer down to ~5%.** It was really a flat 240 FPS
  (2 × 120) with half the drawables dropped. WindowServer composited only at 120 Hz, so its share fell.
  The GPU sat idle (68% busy) because the pacing was still vsync-bound [M + I].

## 5. Sources

- [S1] Apple, *Managing your game window for Metal in macOS*:
  https://developer.apple.com/documentation/metal/managing-your-game-window-for-metal-in-macos
- [S2] Apple Tech Talk 110339 (Metal Performance HUD; direct versus composited path, overlap causes
  composition): https://developer.apple.com/videos/play/tech-talks/110339 (transcript:
  https://nonstrict.eu/wwdcindex/tech-talks/110339/)
- [S3] `CAMetalLayer.h`, macOS 27 SDK (`maximumDrawableCount` 2–3, `displaySyncEnabled`,
  `developerHUDProperties`, drawable reuse rule).
- [S4] Zed PR #44334 / #45434, "gpui: Enable direct-to-display optimization for metal" (opaque layer
  required): https://github.com/zed-industries/zed/pull/44334
- [S5] MoltenVK v1.4.1 `MVKSwapchain.mm` (`initCAMetalLayer`) and `MVKImage.mm` (`presentCAMetalDrawable`:
  scheduled-handler present, per-present `displaySyncEnabled`): https://github.com/KhronosGroup/MoltenVK
- [S6] SDL3 `src/video/cocoa/SDL_cocoametalview.m` and `SDL_cocoawindow.m` (release-3.4.x): metal view is an
  opaque subview, `contentsScale = backing.h / size.h`; fullscreen Space through `toggleFullScreen:`; menu
  visibility hint. https://github.com/libsdl-org/SDL
- Local, macOS 27 (26A428):
  - QuartzCore detach-reason strings and private `CAMetalDrawable.didComposite` and `CAMetalLayer`
    properties (runtime introspection: `meths.m`, `hudstr.m`)
  - `libMTLHud.dylib` strings (`MTL_HUD_*` variables, "Direct" and "Composited")
  - WindowServer, gamepolicyd and UserNotificationCenter unified logs
  - `results/raw/{vk-mst,mtl-mst}.trace`
