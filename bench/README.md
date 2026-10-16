# Bench agent

`build/bench.jar` (built by `tools/build-bench.sh`) is a java agent that hooks `Minecraft.renderFrame` and drives the
game (`Bench.java`). `tools/launch.py --bench <json> --bench-prop k=v` passes `-Dbench.k=v`. Modes (`mode=`): `perf`
(default, spin benchmark), `travel`, `scenes` (parity screenshots, `Scenes.java`), `zoomseq` (zoom frame sequence,
`ZoomSeq.java`), `soak` (stage-1 soak, `Soak.java`, below), `abab` (in-session A/B of runtime toggles, `Abab.java`,
below). Screenshots go to `<shots>/screenshots/`; pass `shots=` as an absolute path (the game runs with the game directory
as its working directory).

`launch.py --bench-jar <jar>` picks the agent jar (default `build/bench.jar`): freeze a copy
(`cp build/bench.jar build/bench-LABEL.jar`) and pass it to every run of an A/B, so a rebuild of `build/bench.jar` cannot
change a queued run. `bench-one.sh` / `bench-travel.sh` / `soak.sh` / `soak-lan.sh` pass it through. Always pass JVM
flags as `--jvm=-Dx=y` (with `=`): argparse rejects `--jvm -Dx=y`.

## Effective config and per-frame trace (docs/metal/CONTRACT.md 16.R5-B)

Every perf/travel result has a `"config"` block: JVM args and GC, `mc.*` / `mcmetal.*` / `bench.*` properties,
`MCMTL_*` / `MTL_*` / `SDL_*` environment, the effective static toggles of the patched client, the Metal library's
effective state (fusion mode, mailbox, ICB, occlusion, M2 flags) and its counters over the measurement (`fusedShare`,
`presentedShare`, `mailboxShare`, drawable wait per frame, fusion fallback reasons), video options, jar / dylib sha1.

`--bench-prop frameTrace=true` adds a `"frameTrace"` block: one row per measured frame (interval, view, client ticks,
tick-bundle wall / CPU time and its parts, time before submit, submit, present, Metal native submit / timeline wait /
drain / encoders / transient blocks, guard trips, and from the native ring: GPU start / end / busy, drawable wait,
present kind). It also sets `-Dmcmetal.frameTrace=true`. Analyse with:

```sh
python3 tools/frame_trace.py results/raw/LABEL.json            # report: attribution of the slowest 1% / 0.1%, cadences, histogram
python3 tools/frame_trace.py results/raw/LABEL.json --csv x.csv # per-frame rows + attribution
```

Headless tests (no game): `tools/bench-hook-test.sh` (probes + zero allocation) and `tools/frame-trace-test.sh`
(analyzer on synthetic runs, real Metal device + ring).

## Startup timeline and faster launches (STAGE2-PLAN W0c / W4 / W8a; results/LAUNCH-SPEED.md)

Every bench result (perf, travel, scenes, zoomseq, soak) gets a `"startup"` block from `Startup.java`: stamps in ms since
the java process started (`jvmStart`, `mainStart`, `window`, `reloadStart`, `firstFrame`, `reloadDone`, `overlayGone`,
`titleShown`, `titleWidgets`, `worldOpen`, `serverCreated`, `levelJoined`, `worldReady`), the `phases` between them (`jvm`,
`preWindow`, `init`, `reload`, `fade`, `title`, `open`, `levelLoad`, `openToReady`, `firstFrameToReady`, `total`), vanilla's
`bootstrapMs`, `quickPlay` and `cds` (the AOT cache is mapped: this runtime ships no default CDS archive). `Bench.onFrame`
calls it until world ready only. `firstFrameToReady` is the old `secondsToWorldReady` (kept unchanged); `total` is process
start to world ready; `openToReady` is title screen to playing (`worldOpen`, where the bench opens or creates the world, to
world ready).

Stage-2 W7 (instant UI transitions, `-Dmc.ui.instantTransitions`, CONTRACT 16.W7, results/W7-UI-TRANSITIONS.md) props:
`--bench-prop titleSettle=true` makes the title path wait until the title screen's widgets are fully shown (vanilla fades
them in over 2 s; stamp `titleWidgets`, phase `title` then includes that wait); `--bench-prop reloads=N` runs N resource
reloads as F3+T after world ready, 1 s apart, and adds `"reloads"` (`callbackMs`: request to completion callback,
`overlayMs`: request to overlay gone, `terrainMs`: request to the first frame of a 100 ms run in which the terrain the
reload discarded is back — section storage rebuilt, camera section compiled and faded in, compile queue empty; a reload is
done once both are taken); with `exitAfterReady` the exit timer starts after the last reload.
`tools/startup-bench.py` prints these stamps, the reloads and the paired per-round differences of each arm against the first.
`tools/lan-smoke.sh` takes `LAN_ARGS` (launch.py arguments for both clients) and `LAN_LANE=timing` (exclusive lane for
join-time A/Bs); host and guest JSONs have `"timing"` (ms since JVM start: host `title`, `published`; guest `level`, `joined`).
tools/launch.py also adds `"launch"`: quick play, AOT cache key / training / `mapped`, shader warm-up, the CG display mode
(`display.pixels`, `display.hz`: discard runs whose mode differs), `loadAvg1` and `lockWaitSeconds` at start, wall time.

launch.py options (implementation and rules: `tools/fastlaunch.py`; measurements: `results/LAUNCH-SPEED.md`):
- **Quick play** (`--quickplay auto|on|off`): the run starts with `--quickPlaySingleplayer <world>` +
  `-Dbench.quickPlay=true`; the world opens at the end of the resource reload, under the loading overlay's fade, instead of
  from the title screen after it (−0.74 s). auto (default) = only where verified to change nothing: zoomseq and startup-only
  runs in an existing world. Not scenes (the `water_surface` water animation phase differs from the title-path references),
  not static / travel timing runs, not travel / soak (fresh world); LAN guests already use `--quickPlayMultiplayer`.
- **AOT cache** (`--aot` or `MC_AOT=1`, off by default; `tools/aot-train.sh <launch.py args>` trains ahead): JDK 25 AOT cache
  keyed by java binary, JVM flags (not `-D`), classpath, bench jar and API; trained automatically (~20 s) when missing;
  −0.55 s. For functional and startup runs only, never timing runs (tail-latency difference in the static A/B).
- **Shader warm-up** (`--warm-shaders auto|on|off`, default off; `tools/warm-shaders.sh <jar>`): one short launch (~13 s)
  compiles every pipeline into the OS Metal cache, saving 1.6–2.5 s of cold compile in the next launch. Use it only before
  startup measurements, and check `startup.phases.reload` anyway (the OS cache also evicts entries on its own).
- `--bench-prop exitAfterReady=<s>`: stop the game that long after world ready (startup measurements:
  `tools/startup-bench.py`; AOT training and warm-up use it). Such runs write only `label`, `mode: "startup"`, `startup`
  and `launch`.

## Chunk pipeline (stage 2 W0b, docs/metal/CONTRACT.md 16.S2-W0B)

`--bench-prop chunkStats=true` (any mode) adds a `"chunks"` block: the patched client's chunk counters (dirty marks by source,
compiles scheduled / finished, compile time, identical recompiles per source, upload bytes, block updates and the
render-equivalent ones, the 25 most frequent block update kinds) and render-thread timers of the chunk / light packet
handlers and light updates, as `total` (from level join) and `measure` (perf / travel measured phase), plus a 250 ms series
(loaded chunks, compile queue, compiles). It sets `-Dmc.chunkStats=true`, which hashes every compiled mesh: never use it in
timing A/Bs of other items. `mode=join` (`tools/bench-join.sh <label> <gamedir> [launch.py args]`, RD 32 or `RD=12`; pinned
pre-generated world) measures join -> all built (`chunks.join.allBuiltS`); pass `--jvm=-Dmc.chunkStats=false` there for A/Bs.
Scenarios: `--opt renderDistance:32` (static or travel), `--bench-prop speed=33` (elytra-like travel).

```sh
RD=32 zsh tools/bench-join.sh j32-LABEL run/s2-join --jar build/client-LABEL.jar --api metal --bench-jar build/bench-LABEL.jar --jvm=-Dmc.chunkStats=false
zsh tools/bench-travel.sh tr-LABEL run/s2 --jar build/client-LABEL.jar --api metal --bench-jar build/bench-LABEL.jar --bench-prop chunkStats=true
python3 tools/chunk_report.py results/raw/tr-LABEL.json --detail      # counters, timers, identical-recompile share
python3 tools/chunk_report.py results/raw/j32-*.json --group 2           # join times per arm (mean / sd)
```

## In-session A/B (`mode=abab`, docs/metal/CONTRACT.md 16.AB, results/ABAB.md)

One launch measures two configurations: after the warmup the agent switches registered runtime toggles at frame
boundaries (`Bench.onFrame`, render thread), interleaving the arms every 15-degree sector of the spin (0.42 s,
`segments=24`), and compares them view direction by view direction (`Abab.java`, statistics in `AbabStats.java`). It
only works for toggles on the switchable list (CONTRACT 16.AB); the others still need full-launch A/Bs.

What one launch (12 blocks of 10 s, about 2.6 min wall) resolves, from the pair noise pooled over all spin runs of the
default design, against the classic interleaved full-launch protocol (67 s per launch, per-pair SD 2.5 % avg, 3.1 % median,
6.4 % 1 % low; results/ABAB.md 5):

| metric | 95 % interval per launch | classic pairs for the same | classic wall | ratio |
|---|---|---|---|---|
| two-frame median (primary; vs the classic median) | ±0.8 % | 57 | 2.1 h | 49x |
| avg FPS (like for like) | ±0.8 % | 42 | 1.6 h | 36x |
| 1 % low (primary; whole-pair p99, the classic definition) | ±3.7 % | 15 | 34 min | 13x |

These are within-launch intervals. In the 6 A/A launches with this design the estimates spread more than the intervals
imply (about 1.4x; few launches, so a warning rather than a verdict; at face value avg FPS is about 20x), and the 1 %
low effect of W1 varied between launches beyond them (+0.3 to +9.8 % over 10 launches). So confirm a call on an effect
under about 1 % with a second launch, and pool two or more launches for any 1 % low claim (`tools/abab.py --combine`).
One arm per spin (`segments=1`) is 2-2.7x noisier (7-11x the classic protocol) but is the steady state: interleaved arms
read W1's per-sector tail lower (CONTRACT 16.AB).

```sh
cp build/bench.jar build/bench-LABEL.jar   # frozen agent, as for every A/B
S=(); while IFS= read -r l; do S+=(--opt "$l"); done < tools/static-options.txt
python3 tools/launch.py --jar build/client-LABEL.jar --game-dir run/abab-static --api metal --opt fullscreen:true $S \
  --bench results/raw/LABEL.json --label LABEL --bench-jar build/bench-LABEL.jar --bench-prop mode=abab \
  --bench-prop 'abab.toggles=net.minecraft.client.gui.HudVignetteSkip#enabled=false|true'
python3 tools/abab.py results/raw/LABEL.json            # arms, effects with intervals, p, Holm calls, looks
python3 tools/abab.py results/raw/LABEL.json --bins     # per 15-degree sector, A vs B
python3 tools/abab.py results/raw/LABEL.json --looks median2   # replay the sequential stop offline
python3 tools/abab.py --combine results/raw/LABEL-1.json results/raw/LABEL-2.json   # pool launches (tails)
python3 tools/abab.py --table results/raw/*.json        # markdown table (bold = Holm call on a primary metric)
# or a queue (CG display mode and overlays checked after the GPU-lock wait, load during the run, presentation share per
# block and arm, wall time; non-Default runs marked DISCARD; launch.log copied under a game-dir lock):
ABAB_BENCH_JAR=build/bench-LABEL.jar python3 tools/ab/abab_runner.py tools/ab/plans-abab/validate3.txt
```

Add `--bench-prop abab.stop=true` to end the run early once the two-frame median is decided; it pays off only for
clear effects (expected pairs of 6 with the default design: 5.9 / 5.7 / 5.0 / 4.3 / 3.6 for effects of 0 / 1 / 2 / 3 /
5 %), and with it every reported interval is a repeated interval at alpha / looks (wider; valid after the stop).

**Reading the result.** Arm A is the first value of every toggle, B the second; effects are B relative to A (`abab.py`
reports FPS changes for frame-time metrics, time changes for GPU metrics). Primary metrics, the decision family of
docs/GOAL.md's "median + 1 % low": `median2` (median of consecutive two-frame means per 15-degree sector) and `pairP99`
("1 % low": per pair, the p99 of all frames of each arm). A call is a Holm-adjusted p below 0.05 over these two (`call`,
`holmP`; bold in `--table`). Everything else is descriptive and never a call, even when its own interval excludes 0
(degree sign in `--table`): `mean` (avg FPS), `median` (plain median: the intervals alternate short / long with two frames
in flight, so it falls into the gap between the two modes and jumps), `p99` ("sector p99": the mean of the per-sector
p99s, every view weighted equally, precise but NOT the classic 1 % low), `pairP999` (0.1 % low), `gpuMedian` / `gpuP99`
(GPU busy is a span, not pure work). Six metrics at 95 % each gave at least one "significant" one in 3 of 7 A/A runs;
Holm over the two primaries gave 1 call in 12 A/A launches.

Props (`--bench-prop abab.X=...`):

| prop | default | meaning |
|---|---|---|
| `toggles` | (required) | `fqcn#field=valueA|valueB`, several separated by `,` or `;`; A/A: `bench.Abab#aaNoop=0|1` |
| `blocks` | 12 | blocks (even); each is one full camera turn; blocks 2i, 2i+1 are pair i |
| `blockSeconds` | `bench.spin` (10) | block length (heavy preset: 5) |
| `segments` | 24 | arm switches per block (must divide `bins`); the pair's second block runs the opposite arm in every segment. 24: every 15-degree sector (0.42 s), the least noisy design. 1 = one arm per spin: the steady state, 2-2.5x noisier; W1's sector tail read higher there. 8 (1.25 s) was noisier than 24 and read the tails like it |
| `order` | `abba` | block arms ABBA...; `abab` strict alternation (with an odd `segments` it never cancels a drift: diagnostics only) |
| `discard` | 30 | frames not analysed after every switch point |
| `bins` | 24 | cells per block (15-degree sectors of the spin; time slices for the heavy preset) |
| `warmupSwitch` | 2 | seconds per arm while warming up (both paths JIT-compiled before measuring) |
| `stop`, `stopOn`, `margin`, `minPairs`, `alpha` | false, `median2`, 0.5, 3, 0.05 | sequential stop: looks after the balanced pair counts from minPairs (every pair with an even `segments`, even pair counts with an odd one) at alpha / looks; a stop metric (any of median2, pairP99, mean, median, p99, pairP999, gpuMedian, gpuP99) is decided when its Holm-adjusted p x looks is below alpha or its repeated interval's half-width is at most `margin` %; the run stops when all are |
| `preset` | `spin` | `heavy`: fixed view; `travel`: continuous eastward exploration; `travelLoop`: same constant-speed circular flight each block, segments=1 |
| `gpu`, `frames`, `resamples`, `minCell` | true, true, 4000, 20 | GPU busy from the Metal trace ring; per-frame arrays in the JSON; bootstrap resamples; frames per cell (and per arm for the pair tails) |

`travelLoop` freezes altitude above the loaded route before warmup and repeats a circle of radius
`bench.speed * abab.blockSeconds / (2*pi)`. Every block covers the same positions and tangent-facing camera;
`abab.loopDiscardSeconds` (default0.5) excludes an equal route prefix instead of `abab.discard` frames.
The result records the route. Use at least one complete lap of warmup, preserve the mesh cache, and run A/A first.
This is a warm-route CPU/renderer comparison, **not fresh exploration**: check continuous travel separately for
streaming throughput. Continuous `travel` alone does not view-match the arms and can confound central FPS.

The result has `"mode": "abab"`; its top-level FPS figures pool both arms and the discarded frames, so read the
`"abab"` block: `arms` (per arm, pooled: median, two-frame median, avg, 1 % and 0.1 % low, GPU busy median / mean / p99,
GPU cadence, presented share), `effects` (per metric: log ratio, interval at `level`, `ci95`, t and p, `pSeq`, `holmP`,
`call`, `primary`, percentile and cell-level intervals, sign tests over cells and over pairs, pair means, variance
components), `blocks` (per block and sector: frames, median, p99, mean, GPU median / p99, two-frame median; `cellArms`;
presented / counted frames per arm; `pairTailsNs` on the pair's second block), `stop` (`lookPairs`, `lookAlpha`, looks,
decisions, seconds saved), `toggles` (values read back), `frames` (per-frame interval, GPU busy and end, bin, block,
keep).

Tests (no game): `tools/abab-stats-test.sh [results/raw/LABEL.json ...]` (distributions, pair tails, look schedule, Holm,
calibration by simulation, the sequential rule under a drift with its repeated intervals and expected pairs, toggle
parsing, and the Python re-implementation against the Java statistics of real results).

## Zoom scenes and zoom sequence (docs/GOAL.md, "Zoom")

Scenes appended after the 18 original ones (details in the `Scenes` class comment):

| scene | what | vanilla |
|---|---|---|
| `spyglass_far` | spyglass raised, fogged tree line at the render-distance edge, HUD hidden | yes |
| `spyglass_scope` | same, HUD shown (vanilla scope overlay) | yes |
| `spyglass_entities` | spyglass on NoAI mobs at 40/55/70/90 blocks, HUD hidden | yes |
| `spyglass_far_entities` | spyglass on 13 mobs 105-165 blocks out, 10 beyond vanilla's entity distance (round 5) | yes |
| `zoom_far`, `zoom_entities`, `zoom_far_entities` | same views through the zoom key at 10x (equal FOV: 7 degrees) | skipped |

Entity distance is bounding-box size (2w+h)/3 x 64 x viewScale, viewScale = clamp(rd / 8, 1, 2.5) x entityDistanceScaling
= 1.5 at render distance 12. So at the scenes' render distance 12 every `spyglass_entities` mob (40-90 blocks) is inside
vanilla's distance (the old note "vanilla hides the creeper and pig at 70" assumed viewScale 1). `spyglass_far_entities`
(rows at 105 / 125 / 145 / 165 blocks, one height band each; table in `Scenes.farMobs`) has 10 mobs that vanilla culls
(creeper, pig, villager; sheep, spider, cow; polar bear, goat; 2 mannequins) and 3 it draws (polar bears at 105 and 125,
iron golem at 145). All are inside the server's tracking range (8 or 10 chunks for mobs; only entities with a range of
11+ chunks, such as mannequins, reach the client at 165). Vanilla and the patched client with
`-Dmc.zoomEntityDistance=false` draw the 3; the zoom-aware entity distance (default on) must draw all 13.

Determinism (round 5): no daylight-burning undead (`spyglass_entities`: zombie -> husk, skeleton -> parched), no horse
(random tail flick / grass eating even with NoAI; -> polar bear), the world is frozen in the same command batch as the
summons (every mob has tickCount 0: same idle-animation phase), and mobs are removed by a teleport below the world (no
death puffs or loot in view), with the next scene waiting until the area is clear. Two vanilla Vulkan runs: 0.000 % of
pixels differ in both scenes.

**Entity check.** For zoom scenes whose name contains `entities`, the agent (scenes mode only) makes
`LevelRenderer.submitEntities` start with `EntityCapture.onSubmit(state)`, so the bench knows which entities the captured
frame really drew (LevelRenderer clears its entity render states right after submitting). Each such scene logs
`[bench] <scene>: entity check PASS|FAIL: tracked n/N, drawn d, vanilla distance would draw v, expected ...`, writes
`<shots>/entities/<scene>.tsv` (type, position, distance, vanilla max distance, vanilla draws, drawn, expected) and adds
`"entityChecks"` to the scenes JSON. Expected: vanilla's set on the vanilla jar or with `-Dmc.zoomEntityDistance=false`,
every tracked mob with the zoom-aware entity distance on. `tools/bench-hook-test.sh` verifies the hook on the vanilla and
the patched LevelRenderer.

`zoom_*` scenes and zoomseq's zoom segments need the zoom feature, found by reflection: class
`net.minecraft.client.Zoom` (override with `--bench-prop zoomClass=<fqcn>`), key named `key.zoom`
(`--bench-prop zoomKey=...`) or a public static `KeyMapping` field, and `public static void setZoomFactor(double)`
(or a static `zoomFactor` field). The zoom animation must be tick-driven, as the spyglass is. Full contract:
`ZoomHook.java`.

`mode=zoomseq` holds the `spyglass_far` view and plays spy_in / spy_out (and zoom_in / zoom_out when the zoom exists),
16 client ticks each. Every frame is saved to `<shots>/screenshots/zoomseq/<segment>_<NNN>.png` with `index.csv`
(frame, ticks, partial tick, camera FOV). The client clock is in lockstep: 2 frames per tick, independent of FPS. So
frame NNN shows the same FOV on every backend, and sequences can be diffed frame by frame. The props `zoomseq.fpt`,
`zoomseq.every`, `zoomseq.ticks` and `zoomseq.downscale` change this. With `mode=zoomseq` only, the agent also hooks
`DeltaTracker$Timer.advanceGameTime`; `tools/bench-hook-test.sh` tests that hook headlessly.

### GPU lock: two lanes (`tools/gpulock.py`)

`/tmp/mc-gpu.lock` is a reader/writer lock (flock(2)). **Timing** (exclusive): anything that measures speed or
memory — `bench-one.sh`, `bench-travel.sh`, `bench-matrix.sh`, `mst-run.sh`, A/B runs, `soak.sh`, `soak-lan.sh`, headless
suites with wall-clock checks; `launch.py`'s default lane. **Functional** (shared): runs whose verdict cannot depend on
speed — scenes / zoomseq parity (`launch.py --lane functional`, `tools/ab/scenes.sh`), `lan-smoke.sh`, deterministic headless
suites; any number overlap, never with a timing run. **Build**: shared on the GPU, exclusive on the checkout's `build/`
(waits for functional runs of the same checkout, which read its jars). A waiting timing run goes before functional requests
that arrive after it (writer preference through `<lock>.gate`). `lockf -k /tmp/mc-gpu.lock cmd` (and the
user-play guard) still means exclusive and still excludes everything; functional requests also yield to a waiting `lockf`
(they are not allowed to starve it). Inside a hold nothing is taken again (`lockf -k $L tools/bench-one.sh ...` works).
`launch.py` also holds `gamedir:<dir>`: one game per game dir in either lane, and runs a `mode=scenes` run in the timing
lane anyway when its bench jar predates the settled vignette (below), whose first scenes depend on speed. Concurrent functional runs need their own game
and shots dirs (`tools/ab/scenes.sh` makes fresh ones per label); `lan-smoke.sh` picks a free port per run.

```sh
python3 tools/gpulock.py status                                   # holders, queued requests, legacy lockf users
python3 tools/gpulock.py build      -- zsh tools/build-client.sh  # builds
python3 tools/gpulock.py functional -- zsh tools/terrain-e2e-test.sh
python3 tools/gpulock.py timing --quiet 4.0:900 -- zsh tools/late-throttle-test.sh
python3 tools/gpulock_test.py                                     # headless test of the lock on a private lock file
```

### Commands (lead; run from the repo root)

Build and test, no game:

```sh
python3 tools/gpulock.py build -- zsh tools/build-bench.sh
python3 tools/gpulock.py timing -- zsh tools/bench-hook-test.sh   # expect "0 failures" (spin-timed frames: timing lane)
```

Scenes and zoomseq runs always start from the pinned, read-only world `run/pinned/scenes` (docs/metal/CONTRACT.md
11.6): `launch.py` verifies it and copies its `saves/bench` and `options.txt` into the game dir before every
`mode=scenes` / `mode=zoomseq` run (under the game lock), so a reused game dir no longer drifts. The scenes JSON records
`"pinnedWorld": "run/pinned/scenes@<id>"`. The pinned vanilla Vulkan reference is `run/pinned/shots-vkns-1512x982-vs`
(22 scenes, plus six more vanilla runs for the noise floor, `-vs-2` to `-vs-7`; zoomseq: `run/pinned/zoomseq-vkns-1512x982`).
`-vs`, `-vs-2`, `-vs-3` were made with the old scene timing, `-vs-4` to `-vs-7` (2026-10-05, branch fast-tests) with the
fast timing below, `-vs-4` in the functional lane next to two other games. The floor needs all of them: the first three
happen to agree in the random parts of gui_inventory (a 22x15 element at 320,957), water_surface (water phase) and
underwater (bubbles), so with only their two pairs vanilla runs FAIL those scenes against vanilla (water_surface 4/4,
gui_inventory 4/4 merged-runner vanilla runs). With the six pairs below, 3 of 4 vanilla runs (each judged with the
other three as floor) and 3 of 4 Metal r5 runs have no FAIL besides the by-design spyglass_far_entities, as with the old
reference set; a random scene still FAILs now and then (old set: water_surface 1 of 3 old-timing Metal runs).

**Scene timing (round 6, results/SPEEDUPS.md).** `-Dbench.scenes.fast` (default on, `--bench-prop scenes.fast=false` for the
old waits) cuts only the waits that cannot change a frame: 152 -> 93 s per Metal run, 132 -> 87 s vanilla. Frames match the
old timing's within at most 1 level in about 100 pixels of a few HUD scenes (day, day_north, sunrise, sunset, rain,
thunder, gui_options, gui_pause); clouds, nether, night_moon, the spyglass scenes and the zoom-key scenes stay byte-identical
across runs. `RUNNER.txt` names the vignette and timing a shots dir was made with.

**Settled vignette (2026-10-05).** Vanilla eases the HUD vignette toward the camera's light level by 1 % per client tick,
so the first scenes' vignette depended on how fast the world became ready: a slower start (machine load, or a second game in
the functional lane) changed `day` by dE mean 1.6 and failed it. The runner now sets the vignette to its settled value every
frame (`Scenes.settleVignette`, `--bench-prop scenes.vignette=vanilla` for the old behaviour); every shots dir has a
`screenshots/RUNNER.txt` saying which. Compare like with like: bench jars with the settled vignette against
`shots-vkns-1512x982-vs*` (made with build/bench-gl2.jar = this change); older frozen bench jars (no RUNNER.txt) against
`shots-vkns-1512x982`, `-2`, `-3`. With the settled vignette and the old timing, two runs (sequential or concurrent) give
identical pixels in every deterministic scene (with the fast timing: see below); underwater (bubbles), water_surface (flow),
entities, particles, end and gui_inventory stay random, and their perceptual verdict flips between two sequential runs as
often as between a sequential and a concurrent one.

The original scenes reference was made with (zoomseq: the same with `mode=zoomseq`):

```sh
python3 tools/launch.py --game-dir run/scenes-pin-ref2 --api vulkan --env SDL_VIDEO_MAC_FULLSCREEN_SPACES=0 \
  --bench results/raw/scenes-pin-ref.json --label pin-ref --bench-jar build/bench-pin1.jar \
  --bench-prop mode=scenes --bench-prop shots="$PWD/run/shots-pin-ref"
python3 tools/pin.py shots run/shots-pin-ref run/pinned/shots-vkns-1512x982
```

The screenshots are half the fullscreen framebuffer, so a reference only matches runs made in the same display mode:
`run/pinned/shots-vkns-1512x982` for the 3024x1964 default scaling, `run/pinned/shots-vkns-1800x1169` (one run, no floor
runs: compare without `--floor` and look at end / underwater by eye) for 3600x2338 "More Space", the mode of rounds 1-5. Never write
into `run/pinned` (pin a new name with `tools/pin.py` instead).

Check these:
- The JSON files say `"scenes complete"` / `"zoomseq complete"` and `"pinnedWorld": "run/pinned/scenes@1b3c83ae44053b75"`;
  scenes JSONs with entity zoom scenes have every `entityChecks.*.pass` true.
- `launch.log` has `[bench] spyglass_far: camera fov 7.0000 (unzoomed 70, target 7.0000)` and no `NOT SETTLED` or
  `NOT READY` (a scene captured after the 40 s settle cap without all sections rendered).
- A zoomseq run's `screenshots/zoomseq/index.csv` lists 64 frames on vanilla: spy_in and spy_out, 32 each.

Candidate (patched client, all scenes incl. `zoom_*`) and comparisons. Functional lane: the scenes and zoomseq runs may run at
the same time (give each its own game dir; the world is restored into it), next to other agents' functional runs:

```sh
python3 tools/launch.py --lane functional --jar build/client.jar --game-dir run/scenes --api metal \
  --bench results/raw/scenes-LABEL.json --label LABEL \
  --bench-prop mode=scenes --bench-prop shots="$PWD/run/shots-LABEL" &
python3 tools/launch.py --lane functional --jar build/client.jar --game-dir run/zoomseq --api metal \
  --bench results/raw/zoomseq-LABEL.json --label zoomseq-LABEL \
  --bench-prop mode=zoomseq --bench-prop shots="$PWD/run/zoomseq-LABEL"
wait
J=mc/runtime/java-runtime-epsilon/jre.bundle/Contents/Home/bin/java   # no system java on this Mac
R=run/pinned/shots-vkns-1512x982-vs/screenshots   # settled-vignette runner (screenshots/RUNNER.txt); older jars: shots-vkns-1512x982
F=(); for n in 2 4 5 6 7; do F+=($R run/pinned/shots-vkns-1512x982-vs-$n/screenshots); done   # floor: ref vs each extra run
F+=(run/pinned/shots-vkns-1512x982-vs-2/screenshots run/pinned/shots-vkns-1512x982-vs-3/screenshots)
$J tools/ImgDiff.java $R run/shots-LABEL/screenshots run/diff-pin-LABEL
$J tools/PerceptualDiff.java $R run/shots-LABEL/screenshots --floor $F
$J tools/ImgDiff.java run/pinned/zoomseq-vkns-1512x982/screenshots/zoomseq run/zoomseq-LABEL/screenshots/zoomseq run/diff-pinzq-LABEL
# zoom key vs spyglass at equal FOV, same candidate run (ImgDiff pairs files by name):
S=run/shots-LABEL/screenshots; C=run/zoomcmp-LABEL; mkdir -p $C/spy $C/zoom
cp $S/spyglass_far.png $S/spyglass_entities.png $C/spy/
cp $S/zoom_far.png $C/zoom/spyglass_far.png; cp $S/zoom_entities.png $C/zoom/spyglass_entities.png
$J tools/ImgDiff.java $C/spy $C/zoom $C/diff
```

## Round 12 scenes (`build/bench-r12.jar`)

Appended after `cave_dark` (every earlier scene unchanged): `ao_grass` and `ao_stone`, close-ups of a grass / stone floor with
blocks standing on it (ambient-occlusion corner shadows) on a platform 19 blocks above the anchor, camera standing on it
(pitch 50 / 35). Made for the greedy-meshing before/after pairs (`tools/ab/greedy-pairs.sh`, results/ROUND12.md); there is no
pinned vanilla reference for them, compare runs of the same jar. Run them with `--bench-prop only=ao_grass,ao_stone`.

## Soak (docs/GOAL.md stage 1; `Soak.java`, `SoakVerdict.java`)

`mode=soak` flies (360 s cycle: 180 s east over fresh terrain, 60 s back west, 60 s north, 60 s hovering with a spin;
terrain following 6 blocks up), toggles fullscreen and resizes the window (120 s cycle: fullscreen until 75 s, windowed
with three 1-second resize bursts, fullscreen again), never opens F3, and samples every 10 s: FPS / p99 / max frame time,
Java heap (used, committed, after the last GC cycle, old generation), non-heap, direct buffers, GC count / time,
phys_footprint and RSS (`proc_pid_rusage`), `MTLDevice.currentAllocatedSize` (FFM, `NativeMem.java`: covers every
backend), nativeFootprint = footprint - heap committed, chunks, entities, window state. A daemon thread forces a major GC
every 60 s (concurrent under ZGC) and records the old generation after it ("liveHeap"). The result JSON (written every
60 s while running, so a crash leaves the series up to the crash) has `status` (`complete` / `failed` / `running`),
`verdict`, `events`, `liveHeap`, `series` (columnar) and `config`.

Verdict: PASS = status complete, the full duration, and for each gated metric after the 5-minute warmup: step-tolerant
Theil-Sen slope of per-minute medians over the last 20 min < X and median(last 3 min) - median(first 3 min after the
warmup) < Y. liveHeapMB (old generation after the forced GC) 3 MB/min / 160 MB, metalMB 4 / 256, nativeFootprintMB 5 /
320; Metal and native only from settled fullscreen samples. Justification and the one-step tolerance: `Soak.java` /
`SoakVerdict.java` class comments; headless test `tools/soak-verdict-test.sh`. A trend span under 15 min is
"inconclusive". Props: `soak.minutes` (30), `soak.warmup` (300), `soak.sample` (10), `soak.gcEvery` (60),
`soak.windowCycle` (120), `soak.window=false`, `soak.trendWindow` (1200), `soak.edgeWindow` (180), `soak.bucket` (60),
`soak.stallSeconds` (120), `soak.limit.<metric>=X,Y`, `join=host:port` (multiplayer guest).

```sh
# singleplayer, fresh world "soak", Fancy / render distance 16, fullscreen start (report + exit code: tools/soak_report.py)
tools/soak.sh soak-LABEL run/soak 30 --jar build/client-LABEL.jar --api metal --bench-jar build/bench-LABEL.jar
# LAN: host (lansmoke agent, world "lan", windowed, RD 16, maxFps 60) + soak guest joining 127.0.0.1:25599
tools/soak-lan.sh soak-lan-LABEL run/soak-lan 30 build/client-LABEL.jar metal build/client-LABEL.jar metal --bench-jar build/bench-LABEL.jar
# 3-minute smoke (exercises every path; the memory verdict is "inconclusive" by design, exit code 2)
tools/soak.sh soak-smoke run/soak 3 --jar build/client-LABEL.jar --api metal --bench-prop soak.warmup=60 \
  --bench-prop soak.gcEvery=20 --bench-prop soak.windowCycle=60 --bench-prop soak.sample=5 --bench-prop soak.bucket=15 \
  --bench-prop soak.edgeWindow=30
python3 tools/soak_report.py results/raw/soak-LABEL.json      # re-print a report
```

Exit codes (`soak_report.py`): 0 PASS, 1 FAIL (crash, error, stall, exit code, crash report or hs_err file, LAN host
did not see the guest, or memory growth in a conclusive run), 2 INCONCLUSIVE (clean but shorter than 15 min of trend).

### Complete Farsight scene snapshots (diagnostic only)

`bench.mode=lodsnapshot` exports the settled selected LOD geometry, current-frame
GPU draw lists, actual atlas/lightmap and terrain uniforms without recording FPS.
Use `python3 tools/lod/capture-scene.py CLIENT_JAR FROZEN_BENCH_JAR --label NEW_LABEL`.
The label must be new. The runner stays in the exclusive timing lane and honors
the user-game guard. The game exits after export.

Format, scope, limits and evidence are documented in
`results/FARSIGHT-SCENE-SNAPSHOT.md`. This is a source snapshot for a future
matched renderer replay, not a complete frame capture or a quality/performance
acceptance. Final-frame depth has already been cleared for hand/GUI rendering.

### Populated handoff motion reference

For `mode=reuseseq`, `reuseFeature=handoff`, `reuseTrajectory=phase0-motion`,
`--bench-prop reuseReadyReference=true` lets the OFF reference settle between
logical frames. ON remains 120 consecutive renders at the original flight/turn
trajectory. Both captures use the same logical clock, deterministic light flicker,
and verified frozen sprite phases. The vanilla flicker formula is indexed by
logical client tick and lightmap inputs are refreshed at that tick’s camera in
both arms, so waiting cannot change the comparison lighting. Reference admission checks near visibility
updates, replacement compiles, uploads/fades, and complete, non-refused LOD work
before and after each captured render. At reference chunk boundaries, local
server position is synchronized so the held clock cannot prevent neighbor-chunk
delivery. ON receives no such commands. The harness neither forces a mesh build nor changes
production code. Boundary-diagnostic flags cannot be combined with this mode.

Run `python3 tools/lod/check-ready-reference.py <manifest.json> <result.json>`
before the unchanged `PerceptualDiff --temporal <off-dir> <on-dir>` comparison.
The first command checks inputs only; it never reports image quality or FPS.
Reference wait frames are explicit in the receipt and excluded from any claim
of uninterrupted candidate motion. Both screenshot capture and reference waits
make this unsuitable for performance measurements.
