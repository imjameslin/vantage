# Zoom key (OptiFine-style) + zoom-aware detail

User request 2026-10-05 (docs/GOAL.md, "Zoom"): OptiFine-style zoom key, spyglass keeps working, same image
quality while zoomed. Client-only; nothing a server can observe changes. Internal notes, terse.

## Behaviour

- Key `key.zoom` ("Zoom", category Misc), default **C** (SDL scancode 6) like OptiFine. Shares C with vanilla's
  creative "Save Hotbar Activator" (`KeyMapping.MAP` holds a list per key, both fire), exactly as with OptiFine:
  in creative, holding C and pressing 1-9 **overwrites that saved hotbar** (`hotbar.nbt`) instead of selecting the
  slot. The Controls screen does **not** flag this (KeyBindsList marks a duplicate only when one of the two
  bindings is non-default); rebinding either key removes the overlap. Rebindable in Controls; saved as
  `key_key.zoom` in options.txt.
- Hold to zoom, release to return. Default 4x (FOV / 4). While held, the wheel multiplies the zoom by 1.25 per
  notch (fractional trackpad scrolls zoom fractionally; a horizontal-only scroll, which macOS makes of Shift +
  wheel and Shift is sneak, counts as `-x` like vanilla's hotbar mapping), clamped to 1.5x..50x and so the zoom
  never takes the FOV below 1 degree (`maxZoom = vanillaFov / 1`). The wheel event is consumed: no hotbar slot change (so no
  `ServerboundSetCarriedItemPacket`), no spectator fly-speed / spectator-menu change. Level resets to 4x on every
  press and release.
- No effect while a screen is open (chat, inventory, pause) or without a player; works in first/third person and
  spectator. Panoramic screenshots ignore it.
- Animation: exponential ease of ln(FOV multiplier) with real frame time (`System.nanoTime`, once per frame from
  `Camera.update`), tau = 0.05 / ln 2 = 72 ms = vanilla's 0.5-per-tick FOV ease made continuous: 87.5 % after
  150 ms at any frame rate, moves every frame at 600-1000 FPS (no per-tick staircase). Log space = every zoom step
  (4x -> 5x -> 6.25x) animates at the same perceived speed. Snaps to the target within 1e-4 so a released zoom
  returns to a multiplier of exactly 1.
- FOV = vanilla FOV (sprint/flying/bow/spyglass/fluid/death all applied) x zoom multiplier. hudFov (hand) unchanged.
  `createProjectionMatrixForCulling` (max(fov, option fov)) unchanged, so section culling uses the unzoomed
  frustum; the round-4 exact frustum (TerrainFrustum, from the real projection) does narrow with the zoom.
- Mouse: turn deltas x tan(zoomedFov/2) / tan(vanillaFov/2) = constant on-screen motion at the screen centre per
  mouse count. Compared against vanilla's FOV (spyglass included), so with a spyglass only the zoom key's part is
  compensated; vanilla's own /8 while scoping stays as is. No forced cinematic camera (smooth camera still works,
  scaled the same way). Rotation packets are as if the user had lowered the sensitivity.

## Zoom-aware detail (`-Dmc.zoomEntityDistance`, default true)

Distance scale `s` (`ZoomMath.State.updateDistanceScale`), continuous in every case:

- **Resting** (no zoom key animation, the camera entity not scoping): exactly 1.0, bit-identical vanilla. The
  reference FOV `ref` tracks the current FOV, capped at the FOV option.
- **Engaged** (zoom key animating/held, or the *camera entity* scoping, so spectating a scoping player counts like
  vanilla's own spyglass FOV): `s = tan(ref/2) / tan(fov/2)`, `ref` frozen at the last resting frame. A drawn bow,
  slowness or water FOV below the option therefore starts `s` at 1 (no pop-in on the first zoomed frame); zooming
  4x with a drawn bow gives s = 4.0, 4x at FOV 70 gives 4.55, the spyglass at FOV 70 gives 11.45.
- **Released**: `s` follows the shrinking magnification but never rises for 0.4 s (vanilla's spyglass FOV takes
  ~300 ms to ease back and starts a tick late when the use times out), then also fades to exactly 1 in log space
  with tau 72 ms. In the common case (vanilla FOV unchanged during the zoom) `s` reaches 1 exactly when the view
  does; only when vanilla's FOV narrowed while zoomed (bow drawn while zoomed) do the extra entities leave over
  ~0.3 s after release. `ref` is re-derived from `s`, so pressing again mid-fade continues smoothly.

The extra range only applies **inside the view actually on screen**: `LevelExtractor.zoomExtraRangeFrustum` builds
a frustum from `camera.getFov()` (the zoomed projection's FOV) and the window aspect, padded by
`ZoomMath.cullPyramid` for this frame's view bob (rotation <= 8 b degrees, eye shift <= 1.12 b, b <= 0.1: half-angles
padded, apex moved back), with vanilla's near/far planes. It falls back to vanilla's wide culling frustum while the
hurt tilt / death roll / nausea or portal warp distort the projection (short, rare), when the frustum is frozen
(debug) or when the padded view would not be narrower; panoramic screenshots get no extra range. Spyglass at 16:10:
half-angles 3.55 x 5.64 deg standing still, 4.36 x 6.45 deg with full bob, vs ~35 x 48 deg for the culling frustum.
Objects within their vanilla distance are decided exactly as in vanilla (wide frustum, vanilla distance); the zoom
frustum only gates the *additional* ones, and extraction runs every frame, so nothing pops while turning or easing.

| Hook | Vanilla | Zoomed |
|---|---|---|
| `LevelExtractor.extractVisibleEntities` | `shouldRender` with view scale clamp(rd/8,1,2.5) x entityDistanceScaling, cull frustum | vanilla test, else the same test with view scale x s against the zoom frustum (renderer's own culling box, leash checks) |
| `BlockEntityRenderer.shouldRender` (default; chests, signs, banners, ...: 64, structure/test blocks 96, gateways 256, pistons 68) | within `getViewDistance()` | vanilla, else within `getViewDistance()` x s and the block grown by 2 blocks inside the zoom frustum (gateways: the whole column for the beam; off-screen-rendered structure/test boxes: no view test) |
| `BeaconRenderer.extract` beam widening (beacons, test-instance blocks) | max(1, d/96); 1 while scoping | max(1, d/(96 s)); 1 while scoping |
| `AbstractSignRenderer.isOutlineVisible` (glow-ink outline) | < 16 blocks; always while scoping | < 16 s blocks; always while scoping |
| `SpellParticle.isCloseToScopingPlayer` (own effect swirls within 3 blocks of the eye, first person) | hidden while scoping | also hidden while the zoom key is held |
| `FishingHookRenderer.getPlayerHandPos` (first-person line start) | near plane at the option FOV | same point, sideways part x tan(zoomed/2)/tan(vanilla/2): projects to the same screen spot (the rod tip; the hand keeps the unzoomed hand FOV) |

Block-crack overlays stay at vanilla's 32 blocks: the server sends `ClientboundBlockDestructionPacket` only to
players within 32 blocks (`ServerLevel.destroyBlockProgress`, including the clearing -1), and the client keeps an
entry up to 400 ticks, so anything farther would be stale.

Only client renderer decisions read `Entity.viewScale` (`shouldRender*` in EntityRenderer/LevelExtractor; checked:
no `net/minecraft/server` user), so the integrated server sharing the JVM is unaffected. `Entity.java` is not
patched. The beacon/sign/particle rows give the zoom key what vanilla already gives the spyglass.

Cost: zero when not zoomed (`s` exactly 1, no frustum built, one extra null check per entity). While zoomed: one
`Frustum` per frame, a second `shouldRender` for entities vanilla culls by distance, one AABB test for block
entities beyond their vanilla distance; only objects inside the (padded) zoomed view are extracted and submitted.

## Toggles

- `-Dmc.zoom=false`: the key does nothing (still registered/saved). FOV, mouse, wheel = vanilla.
- `-Dmc.zoomEntityDistance=false`: all rows of the table above = vanilla (zoom key and spyglass).

## Files

- `client/src/net/minecraft/client/zoom/ZoomMath.java`: pure math + per-frame state machine (no game classes).
- `client/src/net/minecraft/client/zoom/Zoom.java`: glue (key state, frame time, toggles).
- Patched (full copies): `Camera` (calculateFov), `MouseHandler` (onScroll, turnPlayer), `Options` (keyZoom + in
  keyMappings), `renderer/extract/LevelExtractor` (extract, extractVisibleEntities, zoomExtraRangeFrustum),
  `renderer/blockentity/BlockEntityRenderer`, `BeaconRenderer`, `AbstractSignRenderer`,
  `particle/SpellParticle`, `renderer/entity/FishingHookRenderer`. `BeaconRenderer.submitBeaconBeam` also had a decompiler artifact (glow pass reusing the
  solid pass's lambda-captured locals, does not compile); fixed with separate locals, bytecode checked equal to
  vanilla modulo local slots.
- `client/lang/en_us.json`: `key.zoom`.
- Tests: `tools/zoom-test.sh` (`tools/ZoomTest.java`): compiles ZoomMath alone (purity), then easing timing at
  20..1000 FPS, frame-rate independence, per-frame smoothness bound, settle/release exactness, dt edge cases, scroll
  steps/clamps/1-degree floor/spyglass clamp, reset on press/release, sensitivity (constant on-screen motion, no
  double reduction with spyglass), magnification and the distance scale (exact through vanilla's per-tick spyglass
  ease-back, held when the use times out, per-frame continuity with a drawn bow / bow drawn while zoomed / press
  mid-fade / slowness + spyglass, exactly 1 when resting), the zoom culling pyramid (every on-screen point of the
  bobbed projection, screen border and corners, 0.3..3000 blocks, both rotation signs, lies inside it), and (with
  build/client.jar) a bytecode scan that every hook is wired in the right place (wheel consumed before
  ScrollWheelHandler/setSelectedSlot, hud FOV and culling projection untouched, entity test order, zoom frustum set
  and cleared around extraction, crack overlay vanilla, block entity/particle/fishing-line hooks, camera-entity
  spyglass, Options.keyZoom in keyMappings, lang merged).

## Not done / open

- Creative save-hotbar on C (see Behaviour): kept for OptiFine parity; avoiding it means skipping the save path in
  `Minecraft.handleKeybinds` while the zoom key is held (Minecraft.java, avoided for now) or another default key.
- An end gateway's spawn beam is tested as a whole column; other block entities as their block grown by 2 blocks
  (enough for banners, beds, double chests). A first-person leash (LocalPlayer.getRopeHoldPosition) starts off
  screen with or without zoom and is left as is.

- Name tags (64 blocks) and entity shadows (fade by 16 blocks) are distance-based in `EntityRenderer` and are not
  zoom-aware (vanilla spyglass isn't either); particles farther than 32 blocks are never spawned client-side
  (`ClientLevel`, line ~964), so distant particles stay missing when zoomed.
- View bobbing is magnified by the zoom like with the vanilla spyglass (bob lives in GameRenderer, round-4 owned).
- Stage 3 LOD, stage 4 shaders, stage 5 RT must pick detail from the current FOV (GOAL.md); this change only
  provides the FOV (`Camera.getFov()` / the projection) and `Zoom.distanceScale()`.
