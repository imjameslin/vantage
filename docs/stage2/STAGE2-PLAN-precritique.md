# Stage 2 plan: game-side speedups, ranked (merged from four analyses)

**Status:** this is a plan. Nothing in it is implemented yet. The game, benchmarks and builds were not run while it was written. Where the analyses cited line numbers, this plan checked the vignette path, the toggle names, the existing patched files and the travel `maxFrameMs` values against the repo on 2026-10-05.

**Sources.** The four stage-2 analyses, cited by their short names:
- **[CB]** chunk building / upload;
- **[FC]** frame CPU;
- **[JVM]** JVM flags and runtime;
- **[SU]** startup / world join.

They are read together with `results/TAIL-ANALYSIS.md` **[TAIL]**, `results/ROUND4.md`, `docs/stage2/TERRAIN-PLAN.md` (S0–S2, S5–S7 already shipped), `docs/stage2/culling.md` and CONTRACT §16.

**Tags.**
- **[M]** measured. The file is named.
- **[Mod]** model or estimate.
- **[H]** hypothesis.

Every gain below is attached to one metric:
- static median;
- static 1% low;
- travel 1% low;
- travel max frame / 0.1% low;
- chunk-load throughput;
- join-to-all-built;
- startup s;
- world-join s.

**Rules for every item:**
- client-only and exact (or explicitly opt-in when not exact);
- has an A/B toggle and tests;
- has a CONTRACT §16.x note;
- ships default-on only if the harness shows a gain and no scorecard metric regresses (GOAL stage 2).

---

## 0. Summary

1. **The static median is GPU-bound. Only GPU work moves it.**
   - G is about 1.15 ms at 870 fps [M ROUND4]. The render thread spends 73–77% of its time in `timelineWait` and uses about 0.38 ms of CPU per frame, all hidden behind the GPU [M r3-static.jfr].
   - The one game-side item in this plan that should raise the median is **W1: skip the HUD vignette when it draws nothing**.
     - It is bit-exact and about 10 lines.
     - Expected gain: +3–12% static median. It could reach +20–30% if the r4 GUI encoder is still separate [H].
     - A zero-code diagnostic confirms it: `-Dmcmetal.skipPipelines=vignette`.
2. **The static and travel 1% lows are set by the exposed 20 Hz client-tick bundle T** (1.1–1.2 ms static, 1.4 ms travel) [TAIL].
   - The big lever is TAIL's late throttle (**W2**, owned by the TAIL plan). It is listed here because it changes the value of W3 and W16.
   - The game-side lever is **W3: the `animateTick` AMBIENT_PARTICLES fast path**.
     - T drops by 0.13–0.20 ms, and the result is exact.
     - Its 1% low value is +7% before W2, about 0 right after W2 at G = 1.15 ms, and positive again once G ≲ 1.05 ms.
3. **Travel maximum frames are discrete events.** Attribute them with the frame trace (W0a) before building fixes. Candidates:
   - in-frame 128 MiB uber-heap creation, which happened in all 4 tr3 runs and cost at most 9.8 ms (**W5**, cheap, exact);
   - command-buffer ring doubling;
   - first-use entity textures plus deopt bursts (**W9**).
4. **Chunk meshing is not a bottleneck in either scorecard.**
   - In travel, workers are parked 77% of the time and meshing uses about 0.4 cores [M r3-travel.jfr]. At RD12, the join meshing is about 2.5 s of CPU, or about 0.5 s of wall time.
   - Chunk-side items (W10–W13) mainly buy chunk-load throughput, join-to-all-built, multiplayer bases and CPU headroom. They need W0b's new metrics, because neither scorecard measures them today.
   - **Travel FPS is confounded by terrain fill.** A faster pipeline loads more terrain sooner and *lowers* the travel median [M CB fact 7]. Normalize by drawn quads.
5. **Startup.** Today's metric (`secondsToWorldReady`, 4.63 s) starts at the first frame. It misses about 3.5 s before that, and it includes a fixed 2.0 s fade [M SU].
   - W0c fixes the metric.
   - **W4 (JDK 25 AOT cache under ZGC):** −0.6 to −1.0 s launch-to-title, exact.
   - **W7 (instant UI transitions):** −2.0 s, but it visibly removes an animation. It needs the user's approval and is reported apart from compute wins.
   - **W8 (keep shader caches warm):** −2.7 s on cold launches.
6. **The JVM is not a frame-time factor in static.** ZGC pauses are ≤ 27 µs, there are no allocation stalls, and JIT is done [M].
   - The flag items (W14) are low-confidence, cheap A/Bs that target travel tails.
   - Java thread priorities are measured no-ops on macOS. All threads run at QoS DEFAULT, which makes W6 a cheap test.

---

## 1. Where the time goes today (fact base)

| Metric | State (round 4) | What dominates it | Source |
|---|---|---|---|
| Static median | 870 fps, G ≈ 1.15 ms | GPU. Render-thread CPU 0.38 ms/frame (entities 0.09, native submit 0.09, terrain-side 0.06, tick amortized 0.05) is hidden by the 2-frames-in-flight pipeline | [M] r3-static.jfr, ROUND4, FC |
| Static 1% low | 397 (p99 ≈ 2.52 ms) | Tick frames: exposed T 1.1–1.2 ms. In the tick: `animateTick` 35%, of which 58% is `getValue(AMBIENT_PARTICLES)` (ClientLevel.java:613) and 39% chunk lookups for 1334 positions; `tickEntities` 36% | [M] 5 JFRs pooled, [Mod] TAIL fit |
| Travel median | 527 fps (G ≈ 1.9 ms) | GPU. FPS falls within each run as resident terrain grows from 16 to about 200 MB (580 → 471 fps, first vs last 5 s) | [M] CB fact 7 |
| Travel 1% low | 282 | Exposed T ≈ 1.4 ms | [Mod] TAIL |
| Travel max / 0.1% low | max 9.8–37.7 ms within *one* config (v-s5c-tr3); r4 22.5 / 28.9 | Events: in-frame 128 MiB heap creation (all 4 tr3 runs, ≤ 9.8 ms); ring doubling 2048 → 4096; 15 sync entity-texture loads; 158 render-thread deopts (48 in 7 ms at first skeleton). Not GC (pauses ≤ 27 µs) | [M] logs, r3-travel.jfr, JSONs |
| Chunk meshing | about 1.5 ms Java CPU per non-empty section [Mod]; workers 77% parked in travel | Compile breakdown (inclusive): AO 54.6%, light lookups 20.8%, palette reads 14.6%, biome tint 11.2% (25 biome lookups per tinted block), `shouldRenderFace` 9.2%; 60–65% is data access, about 2% vertex writes | [M] r3-travel.jfr, 500 compile samples |
| Rebuild waste (travel) | Uploaded / resident: CUTOUT 8.6–8.9x, SOLID 4.5–4.8x, TRANSLUCENT 1.9–2.2x; static 1.0x | Light packets dirty 27 sections twice; no identical-mesh skip | [M] CB, travel logs |
| Startup (existing world) | about 8.1–8.2 s launch to world-ready; metric 4.63 s (σ 0.18) | JVM + Bootstrap about 2.0 s; ctor/window about 1.5 s; reload 0.95 s; **fixed fade 2.0 s**; world join 1.64 s. Cold Metal shader cache +2.7 s | [M] logs, offline harness |
| JVM | ZGC, COH, 2–4 GB, pretouch, StringDedup (= Mojang's 26.3 `default-user-jvm`, `mc/versions/26.3/26.3.json`) | Travel allocates 340–420 MB/s. Committed heap grows 3.41 → 4.07 GB inside the window. One code-cache-triggered Major seen. Every thread at QoS DEFAULT, Mach priority 31 | [M] JVM |

---

## 2. Contradictions between the analyses, and how they are resolved

| # | Conflict | Resolution |
|---|---|---|
| X1 | **Heap pretouch.** [JVM] P4 says pretouch *more* (`-Xms4G`) to avoid in-window first-touch faults. [SU] P12 says pretouch *less* to save 0.1 s at startup. | Run one 3-arm travel experiment (W14a). The tail outranks 0.1 s of startup (user priorities). Adopt `-Xms4G` only if the travel tail improves. Drop pretouch only if the tail is unchanged. Otherwise keep today's Mojang default. |
| X2 | **JIT profiles in the AOT cache.** [FC] P4(b) assumes JEP 515 method profiles fix the deopt storms. [JVM] measured `MethodTrainingData=0` under ZGC on JDK 25.0.1. | [JVM] wins (measured). Under ZGC, deopt storms can only be addressed by trap-limit flags or a warm-up (W9b/c). Profiles need a GC switch or JDK 26 (W18). |
| X3 | **AOT startup size.** [JVM] estimates −0.5 to −1.2 s from 62 µs/class (load + link only). [SU] measured −0.45 s on the real Bootstrap workload, where about 8.8k lambda/hidden classes are not archived under ZGC. | Use [SU]'s workload-based figure: −0.6 to −1.0 s for the full launch. [JVM]'s microbench leaves out `<clinit>` and hidden classes. |
| X4 | **Thread priorities.** [FC] said HotSpot's macOS no-op was "not checked on this build". | Settled by [JVM]'s probe: no-op with `ThreadPriorityPolicy=0`, every thread QoS 0x15, Mach 31. `-XX:ThreadPriorityPolicy=1` makes the priorities real. |
| X5 | **Who gains from QoS.** [JVM] P3 says static 1% low +0–5%. [FC] says render-thread samples were 842/842 on S-cores in the r3b MST. | Static gain from E-core avoidance is about 0 (measured). Preemption on S-cores is unmeasured. Travel is unmeasured (TAIL C2). W0a's T wall-vs-CPU split decides. |
| X6 | **Worker QoS direction.** [CB] P9 says UTILITY (E-core preference). [FC] P3 and [JVM] P3 say USER_INITIATED. | Default to USER_INITIATED for compile workers and UTILITY for IO-Worker. UTILITY for workers costs up to about 30% of mesh throughput [Mod], so test it only inside W15 against join-to-all-built. |
| X7 | **CPU contention in travel.** [CB]: JVM mean 1.7 cores, workers 77% idle, so contention is unlikely. [FC]: machine 1.3–3.9 cores, so the 4 S-cores can saturate in bursts. | Both are true: one is a mean, the other a burst. Decide per frame with W0a (T wall vs thread CPU, core number at tick start). |
| X8 | **Heap-creation stall size.** [TAIL] E14 tied it to 16.6 and 21.2 ms max frames (tr2-off-r2, tr3-off-r2). [CB] found it in all four tr3 runs, including one with a 9.78 ms max. | Consistent once the runs are named: the cost is ≤ 9.8 ms and it is not always the max-frame event. W5's confirm step times the constructor directly. |
| X9 | **Light-map clone share of render-thread allocation:** 46% / 96 KB per frame [TAIL] vs 49% / 80 KB [FC]. | Same order. JFR allocation weights are noisy, dominated by single samples [JVM]. The conclusion does not change: not a frame factor (W19 is low priority). |
| X10 | **Sample attribution:** compile 500 vs 511 samples, worldgen 459 vs 414 ([CB] vs [FC]). | Different stack filters on the same JFR. No conclusion changes. |
| X11 | **Entity-texture preload timing.** [FC] P4(a): at the end of the reload. [SU] P11: during the idle fade and title. | Merged into W9a: decode on idle workers *after* the reload completes, so it never lengthens the reload's critical path. Upload on first use from the decoded cache. |
| X12 | **Compile-pool split:** 6 threads [CB] vs about 4 [FC] vs flag-only `max.bg.threads` [JVM]. | One item, W15. Run the zero-code `-Dmax.bg.threads` sweep first. Size a dedicated pool only from data. |
| X13 | **Packet handling.** [TAIL]: never time-budget packets. [FC] P5: parse chunk packets off-thread. | Compatible. Making the client faster is fine: `ChunkBatchSizeCalculator` (7 ms / ns per chunk) then reports what a faster vanilla machine would. Deferring or budgeting handling is rejected. After W2, packets run before the wait and are hidden up to 2G−C, which shrinks W16's value. |
| X14 | **Light-storage edits.** [CB] P2(b) (light-diff dirtying), [FC] P7 (two-level COW map) and [CB] P4 (DAM snapshots of immutable published `DataLayer` maps) all touch `LayerLightSectionStorage`, a class shared with the integrated server. | One owner, in sequence: W12 relies on "published map is immutable". Any COW redesign (W19) must keep that and keep the server path bit-identical. W11b and W19 are in the same round and owned by the same agent. |
| X15 | **StringDeduplication.** [JVM] [H]: "keep the vanilla default". | It is Mojang's own 26.3 default (`default-user-jvm`), so keep it on unless W14 data says otherwise. |
| X16 | **Time before the first frame:** about 2–3 s [JVM] vs about 3.5 s [SU]. | [SU]'s log decomposition is more detailed. W0c measures it directly. |
| X17 | **Vignette cost basis.** [FC]'s upper range (0.29 ms of full-screen work) comes from an older trace with unmerged passes. | Treat +20–30% as [H]. The zero-code X0.1 run plus the decoder's passes-in vs encoders-out stats settle it, in the same way TAIL C1 settled fusion state. |

---

## 3. Ranked work items

The ranking is by expected gain per effort on the user's metrics. W0a–c are prerequisites and are not ranked by gain. "Before W2 / after W2" refers to TAIL's late throttle.

| Rank | ID | Item | Main metric gain (estimate) | Conf. | Effort | Exact? | Gate | Round |
|---|---|---|---|---|---|---|---|---|
| pre | W0a | Frame trace (TAIL §6) plus the sub-timers this plan needs | Enables W2, W3, W5, W6, W9, W15, W16, W19 | high | S–M | diag | overhead 2+2 | 1 |
| pre | W0b | Chunk-pipeline counters, `storm` and `join` bench modes, FPS per drawn quad | Enables W10–W13, W15 | high | S–M | diag | overhead 2+2 | 1 |
| pre | W0c | Startup timeline from JVM start, profiled reload, effective-config block | Enables W4, W7, W8, W17 | high | S | diag | stamps add up | 1 |
| 1 | W1 | Skip the vignette draw when it is an exact no-op | Static median +3–12% (+20–30% [H]); travel median +2–7%; static 1% low +2–5% | med | XS | bit-exact | X0.1 | 1 |
| 2 | W2 | Late throttle (TAIL S1; carried, not re-analysed) | Static 1% low 397 → 519–579 [Mod]; travel 282 → 360–386 [Mod] | high | M | exact image | TAIL D1 | 1 |
| 3 | W3 | `animateTick` AMBIENT_PARTICLES fast path (+ section-direct lookups) | T −0.13–0.20 ms; static 1% low +7% before W2, 0 after W2 until G ≲ 1.05 ms; travel +4% before W2 | med-high | S | exact (RNG identical) | W0a T timer | 1 |
| 4 | W4 | JDK 25 AOT cache (ZGC class-metadata mode) in bench and Prism | Launch→title −0.6–1.0 s; world-join −0.1–0.3 s; FPS 0 | high works / med size | S–M | exact | X0.3, W0c | 2 |
| 5 | W5 | Pre-create uber heaps off-frame, keep a spare, pre-size rings at join | Travel max frame: removes a 2–10 ms event per layer growth; 1% low 0–2% | high / med | S | exact | heap-ctor timing | 1 |
| 6 | W6 | Thread QoS / real priorities (render USER_INTERACTIVE) | Static 0 (measured); travel 1% low 0–10% [H] | low-med | XS–S | exact | X0.2, W0a | 2 |
| 7 | W7 | Instant UI transitions (opt-in) | `secondsToWorldReady` −2.0 s; every reload −2–3 s | high | S | **not exact (UI)**, user approval | user side-by-side | 3 |
| 8 | W8 | (a) Shader-cache warm-up after builds; (b) own spvc cache + `MTLBinaryArchive` | Cold launch −2.7 s; warm ≤ −0.15 s; removes cold-cache bench outliers [H] | med | (a) XS, (b) M–L | exact (content-hashed) | `shaderSalt` A/B | 1 (a) / 3 (b) |
| 9 | W9 | First-use hitches: entity-texture preload, trap-limit flags, renderer warm-up | Travel max frame / 0.1% low (share unknown); 1% low 0–3% | med / low | M | exact | X0.4 + W0a attribution | 2 |
| 10 | W10 | Region snapshot: synchronous all-air `EMPTY`, per-section BE copy, (later) `SectionCopy` cache | createRegion 2.8% → < 1% of travel render samples; multiplayer bases about 0.1–0.2 ms per region at 300 BEs [Mod]; earlier terrain visibility | high exact / med size | S–M | exact (timing only) | W0b all-air share | 2 |
| 11 | W11 | Identical-mesh skip (a), light-diff dirtying (b) | Rebuild amplification cut by [H] 30–70%; worker and upload CPU; travel 1% low 0–2% | med | M | exact; (c) opt-in | W0b ≥ 15% identical | 2 (a) / 3 (b) |
| 12 | W12 | Dense-array mesher (DAM) with dual-compile verifier | Compile CPU 1.8–2.5x [Mod]: chunk-load throughput where meshing-bound; join-to-all-built −0.2–0.5 s RD12, −1–2 s RD32; block-edit sync rebuild halves | med-high | slice 1 d; full L | byte-exact (verifier) | slice ≥ 20% compile ns, 0 mismatches | 2 slice / 3 full |
| 13 | W13 | O(1) bucketed compile queue + staging memcpy outside `copyLock` | Drain time 0.06–2 s at 5k–RD32 queues; p99.9 / max frame | med / med-low | S + S/M | exact (order only) | W0b poll / lock histograms | 3 |
| 14 | W14 | JVM flag track: heap sizing (X1), code cache, inlining, harness/instance parity | Travel 0.1% low / max frame 0–3% [H] | low | flags | exact | per-flag | X1 track |
| 15 | W15 | Worker-pool sizing / dedicated compile pool | Travel 1% low ±5% vs throughput −0–30% | low | S–M | exact | after W6, W0b | 4 |
| 16 | W16 | Parse chunk packets off the render thread | T −0.1–0.4 ms on chunk ticks (shrinks after W2) | low-med | M–L | exact | W0a packet timers after W2 | 4 |
| 17 | W17 | Startup backlog: eager reload tasks, Bootstrap overlap, data-pack stem, known-pack pre-parse, atlas cache | −0.02 to −0.7 s each (see §4) | low-med | S–L | exact (+ registry-id check) | W0c | 4 |
| 18 | W18 | Full Leyden: G1 / single-generation Shenandoah AOT, or JDK 26 (JEP 516) | Launch −1.5 to −2.5 s [Mod]; JIT profiles; **risk of G1 pauses** | low-med | flags (+ runtime) | exact | ask the user before any runtime download | 4 |
| 19 | W19 | Copy-on-write light-section map (client instance) | About 0 (allocation is not a factor) | med (that it is small) | M | exact | W0a `level.update` p99 ≥ 0.1 ms | 4 |
| 20 | W20 | Native NEON mesher | +20–35% compile on top of DAM | med | XL | exact (verifier) | after W12: 9 workers saturated > 1 s | 4+ |
| 21 | W21 | Entity-heavy scene; then instanced entity models / neighbour-check cache | 0 today; relevant when C > 0.8G | high (no gain now) | S–L | exact | new scene | 4+ |
| 22 | W22 | Vector API frustum culling | 0.02–0.03 ms CPU/frame; 0 fps now | low | 1 d | exact | stage-3 LOD counts | stage 3 |
| — | T-S3 | TERRAIN-PLAN S3 translucent "head" list (carried over) | 0.016–0.03 ms GPU at the bench; more over oceans | med | M | exact | r4+ GPU profile | backlog |
| — | Opt | Quick Play in the Prism instance (vanilla feature, no code) | About −1 s launch→in-world against menu clicks | high | none | vanilla | **offer it to the user; do not set it by default** | — |

**Expected outcome if every gate passes (not strictly additive):**
- **Static median:** +3–12% from W1, the only game-side median item. Further median gains are GPU / backend work (TAIL S3 and the backend). This plan does not deliver them.
- **Static 1% low:** dominated by W2. W1 and W3 add 0–7%.
- **Travel 1% low:** dominated by W2. W5, W9, W10 and W11 add 0–2% each; W6 adds 0–10% [H].
- **Travel max frame / 0.1% low:** W5 and W9 remove the identified event classes.
- **Chunk loading:** W12 and W13 (throughput and drain), W10 (earlier visibility).
- **Startup:** W4 −0.6–1.0 s, W8 −2.7 s cold, W17 up to −1 s more (riskier). W7 −2.0 s only if the user approves.

---

## 4. Item details

Each item lists: mechanism, gain, exactness and multiplayer argument, confirming experiment, toggle, dependencies and files touched. "Patched copy" means a new file under `client/src/<vanilla package path>`. Commands assume:
- `L=/tmp/mc-gpu.lock`;
- a frozen jar `build/<x>.jar` (copy with `cp -p`);
- the scripts' own quiet and overlay gates.

### W0a. Frame trace (TAIL §6) plus sub-timers for this plan
- **Mechanism.** TAIL §6.1–6.2 as written:
  - a per-frame record in preallocated arrays;
  - a GPU start/end ring from the completion handler (`mcmetal_common.m:347-351`);
  - a `SlowFrame` JFR event (> 4 ms);
  - Points of Interest signposts.

  This plan adds:
  - sub-timers for `animateTick` and `tickEntities`;
  - packet time by type, with `LevelChunkWithLight` separate;
  - `level.update` split into `pollLightUpdates` and `runLightUpdates`;
  - render-thread CPU-time delta and the core number at tick start (native TPIDRRO_EL0 low bits), giving T as wall vs CPU;
  - event bits for heap create/destroy (from W5), texture load, buffer resize and GC.
- **Gain.** None directly. It decides W2, W3, W5, W6, W9, W15, W16 and W19. It also answers TAIL C2 (why T rose to 1.2 ms when the GPU got faster).
- **Exact / multiplayer.** Diagnostics only, behind a static final and folded away when off.
- **Confirm.** 2+2 static, trace on vs off. Median and average must agree within 2%.
- **Toggle.** `-Dmc.frameTrace=true`, default off.
- **Depends on.** Nothing. **Same owner as W2:** both edit `Minecraft.runTick`. The trace is commit 1 and S1 is commit 2.
- **Files.**
  - `client/src/net/minecraft/client/Minecraft.java`;
  - new `client/src/net/minecraft/client/FrameTrace.java`;
  - `MetalTimeline.java` and `native/src/.../mcmetal_common.m` (GPU timestamp ring);
  - new `bench/src/bench/FrameTraceHook.java` plus a one-line registration in `Bench.java`.

### W0b. Chunk-pipeline counters, `storm` and `join` bench modes ([CB] P3)
- **Mechanism.** Per-second counters written to a `chunks` block in the bench JSON:
  - compiles started and finished, all-air compiles;
  - compile ns p50/p99 as wall time and thread CPU;
  - queue length and `poll` ns;
  - render-thread `copyLock` wait histogram;
  - region-copy ns and `SectionCopy` count;
  - uploads and bytes per frame (max);
  - heap create/destroy ns (read from W5's counter);
  - visible-but-UNCOMPILED holes;
  - resident MB;
  - identical rebuilds (counted only once W11a exists).

  Bench modes:
  - `bench.mode=storm`: static spin; every 3 s, mark all visible sections dirty. Geometry stays constant, so it measures 1% low and max frame under a full rebuild, plus drain time.
  - `bench.mode=join`: fixed camera; time from LOADING until the queue is empty and no visible section has been UNCOMPILED for 0.5 s, at RD12 and RD32.

  Travel also reports **FPS per million drawn quads** and the resident terrain MB at the end, against the fill confound. Dirty-by-source counters land with W11b, which patches those call sites anyway.
- **Gain.** None directly. It creates the chunk-loading metric the user cares about, which no harness measures today.
- **Exact / multiplayer.** Diagnostics only.
- **Confirm.** 2+2 static with stats on/off (within 2%). Then 5 baseline `join` runs to learn its noise.
- **Toggle.** `-Dmc.chunkStats=true`; `bench.mode=storm|join`.
- **Depends on.** W5 for the heap timer, optional.
- **Files.**
  - `client/src/.../chunk/SectionRenderDispatcher.java`;
  - `client/src/.../extract/LevelExtractor.java` (holes, region copies);
  - new `client/src/.../chunk/ChunkStats.java`;
  - new `bench/src/bench/ChunkStatsHook.java`;
  - `bench/src/bench/Bench.java` (modes).

### W0c. Startup timeline and effective-config block ([SU] P1 + TAIL §6.3)
- **Mechanism.**
  - Stamps relative to `RuntimeMXBean.getStartTime()`: first frame, reload done, overlay gone, `openWorld`, integrated server ready, player spawned, level ready, all sections built (from W0b).
  - Vanilla's own `GameLoadTimesEvent` values and `Bootstrap.bootstrapDuration`, read by reflection.
  - A scratch log4j2 config with ms timestamps and DEBUG on `ReloadableResourceManager`. That switches to vanilla's `ProfiledReloadInstance`, which logs per-listener prepare/apply ms and "Total blocking time".
  - The TAIL §6.3 `config` block: jar sha1, effective `MCMTL_*`, `mcmetal.*`, `mc.*` and JVM arguments, fusion and mailbox state.
- **Gain.** None directly. It fixes a metric that misses about 3.5 s and is dominated by a 2.0 s animation. It names the reload's critical path.
- **Exact / multiplayer.** Bench-only.
- **Confirm.** 3 static + 3 travel runs. The stamps must add up to `secondsToWorldReady`, and the profiled reload must list every listener.
- **Toggle.** Always on in `bench.jar`. `--jvm -Dlog4j.configurationFile=tools/startup-log4j2.xml` for the profiled reload.
- **Depends on.** Nothing. **Merge before W0b** (both edit `Bench.java` additively).
- **Files.** `bench/src/bench/Bench.java`; new `tools/startup-log4j2.xml`.

### W1. Skip the HUD vignette when it is an exact no-op ([FC] P1)
- **Mechanism.** `Hud.extractVignette` (vanilla `Hud.java:1075-1113`) draws a full-screen textured quad every frame, with blend `ZERO, ONE_MINUS_SRC_COLOR, ZERO, ONE` (`client/src/.../RenderPipelines.java:1139`). The draw is 3600x2338 = 8.4 Mpx.
  - `vignetteBrightness` decays 1% per tick toward `1 − levelBrightness`, which is 0 under eye-level light 15 (`Hud.java:1068-1073`, verified). The 8-bit colour therefore reaches 0 about 552 ticks (27.6 s) after join.
  - With vertex RGB = 0, the fragment RGB is 0. Then dst RGB × (1 − 0) = dst and dst alpha × 1 = dst alpha, so the framebuffer is bit-identical.
  - Fix: return early when the border warning strength is 0 and `as8BitChannel(clamp(vb)) == 0`.
  - The vignette applies to about 85% of measured bench frames (time 6000, clear weather, measurement starts at least 23 s after world-ready) and to daytime surface play. The gain is 0 at night, in caves and anywhere the vignette is visible, because it still draws there.
- **Gain.** Static median +3–12%, if the draw costs 0.04–0.13 ms of fragment work inside a merged GUI encoder (G = 1.15 ms) [Mod]. Up to +20–30% [H] under two conditions:
  - r4 still opens a separate GUI encoder;
  - Apple's tile pipeline skips load/store for untouched tiles, so the vignette is what forces full-screen load/store.

  The old trace's 0.29 ms of full-screen GUI-pass work fits that hypothesis. Other gains:
  - travel median +2–7%;
  - static 1% low +2–5% (model G × 0.94: 395 → 415).

  Gains compound with W2: a smaller G lowers the slack 2G − C, which brings W3 back into play.
- **Exact / multiplayer.** Bit-exact by the blend algebra above. It is HUD-only, and nothing is sent to the server. Tests:
  - a unit test on the quantization boundary (vb just below and above 1/255);
  - a forced-vb = 0 scene with the toggle on and off, expecting 0 px difference;
  - 18-scene parity.
- **Confirm (X0.1, zero code).** 2+2 interleaved static runs:
  ```sh
  lockf -k $L tools/bench-one.sh vig-on-1 run/metal --jar build/r4f.jar --jvm -Dmcmetal.skipPipelines=vignette
  ```
  - Compare against the same command without the flag.
  - The image differs only in the first about 5 s of the measurement (≤ 2/255 at the edges). This is acceptable for a diagnostic.
  - Accept the patch if the median rises ≥ 5% in 2+2, or ≥ 2% when extended to 4+4.
  - Optional: a 5-s MST with and without the flag, plus the decoder's passes-in vs encoders-out stats, to learn whether the GUI pass is merged.
- **Toggle.** `-Dmc.hud.vignetteSkipNoop`, default true after the A/B.
- **Depends on.** Nothing.
- **Files.** New patched `client/src/net/minecraft/client/gui/Hud.java`; new test `tools/HudVignetteTest.java`. **Coordinate with the zoom workstream:** the spyglass overlay also lives in `Hud`.

### W2. Late throttle (TAIL S1); carried, owned by the TAIL plan
- Listed only so the ranking and interactions are coherent. Design, gate D1 and A/B are in TAIL §4 S1 and §7.
- **Gain [Mod].** Static 1% low 397 → 519–579; travel 282 → 360–386; median unchanged.
- **Interactions.**
  - After W2, T is hidden while T + C_tick < 2G − C. With T = 1.15, C = 0.40 and C_tick = 0.55, that holds only while G > 1.05 ms. So W1 and later GPU wins bring T back into view, and W3 becomes valuable again.
  - W16 shrinks after W2, because packets move in front of the wait.
- **Toggle.** `-Dmcmetal.lateThrottle`.
- **Files.**
  - `Minecraft.java` (`runTick` order; same owner as W0a);
  - `MetalCommandEncoder.java`, `MetalDevice.java`, `MetalBuffer.java`, `MetalTransientMemory.java`.

### W3. Exact `animateTick` fast path ([FC] P2, TAIL S4a/b)
- **Mechanism.**
  1. **Per-tick proof.** Enable the fast path only when two things hold:
     - `AMBIENT_PARTICLES`' baked sampler has the constant base `List.of()` and exactly one positional layer, the biome layer (`EnvironmentAttributeSystem.java:40-58, 80-107`);
     - no candidate biome yields a non-empty list.

     The non-empty result is memoized per biome holder. Candidates come from the biome palettes (`PalettedContainerRO.maybeHas`) of sections overlapping the player ±36 blocks, plus the plains fallback for unloaded chunks. When the proof holds, skip `getValue` at `ClientLevel.java:613` for all 1334 positions. Only 4 vanilla biomes define the attribute, all of them in the Nether.
  2. **Optional.** Section-direct `getBlockState`/`getFluidState` with a last-section cache for the same positions (`ClientLevel.java:558-567`).
  3. The Animate-pass merge (TAIL S4c) is backend work. It is not part of this item.
- **Gain.** T −0.13–0.20 ms from (1), −0.25–0.30 ms with (2).
  - Static 1% low +7% before W2 (395 → 422 [Mod]). About 0 right after W2 at G = 1.15 ms, and positive again once G ≲ 1.05 ms or at 1000+ fps.
  - Travel 1% low +4% before W2, about 0 after.
  - Render-thread CPU −3–5%.
- **Exact / multiplayer.**
  - An empty list never calls `canSpawn(random)`, so the RNG call order and count are identical and the particles are identical.
  - The proof uses the server-synced registries, so it holds on any server, including data-pack biomes and dimension-level attributes. In those cases the base or layer check fails and the fast path disables itself.
  - Test: a seeded headless run of 10k ticks in Overworld and Nether chunks, comparing the particle sequence and the final RNG state with the toggle on and off.
- **Confirm.** One static run each way with W0a: `animateTick` wall time per tick −50% and T −0.12 ms. This decides the item even if W2 hides T. Before W2 lands, add a 3+3 static A/B (accept 1% low +5% with the median within noise).
- **Toggles.** `-Dmc.client.animateTickFast`, `-Dmc.client.animateTickSectionLookup`.
- **Depends on.** W0a for measurement.
- **Files.** New patched `client/src/net/minecraft/client/multiplayer/ClientLevel.java`; new `.../multiplayer/AmbientParticleGate.java`; new `tools/AnimateTickTest.java`. W12's slice later edits `ClientLevel.calculateBlockTint` and rebases on this.

### W4. JDK 25 AOT cache under ZGC ([JVM] P1 + [SU] P2)
- **Mechanism.**
  - One-step training with `-XX:AOTCacheOutput=<cache>`: title → singleplayer join → 30 s travel → quit. `Minecraft.java:1001` calls `System.exit`, so the dump runs. Later runs pass `-XX:AOTCache`.
  - Under ZGC on 25.0.1 the cache holds class metadata only. There are no AOT-linked classes, the about 8.8k lambda/hidden classes are not archived, and there are no JEP 515 profiles.
  - The JVM validates jar mtimes and sizes and the JVM flags, and falls back silently (`AOTMode=auto`).

  Engineering details:
  - **Prism.** The `-cp` head is an AppTranslocation path that changes per session (5 UUIDs seen), which would reject the cache. A wrapper (`OverrideCommands=true`, `WrapperCommand=<instance>/aot/wrap.sh`) copies `NewLaunch.jar` with `cp -p` to a stable path, picks `<sha1(client jar)+gc/coh>.aot`, and trains when the cache is missing. The alternative is the user moving Prism to /Applications. That is outside the project, so ask first.
  - **Bench.** Train with the same `-javaagent:bench.jar`. Otherwise the `java.instrument` add-mods mismatch disables optimized module handling and understates the gain.
  - Retrain whenever the client jar changes.
- **Gain.** Measured on the Bootstrap workload:
  - `bootStrap` 1256–1425 → 915–994 ms;
  - `detectVersion` 235 → 140 ms;
  - process −0.45 s.

  Projected [Mod]:
  - launch→title −0.6 to −1.0 s;
  - `secondsToWorldReady` −0.1 to −0.3 s;
  - first-use class loads in travel about 6x cheaper (121 classes in 24 s), a small max-frame help [H].

  FPS: 0.
- **Exact / multiplayer.** Same bytecode and semantics, and static initializers still run at runtime. Invisible to servers.
- **Confirm.**
  - X0.3 (now, zero code): one training run, then 3+3 static runs with and without `--jvm -XX:AOTCache=build/aot/r4.aot --jvm -Xlog:aot=warning`.
  - After W0c: 5+5 runs on JVM start → title and → levelReady.
  - Kill if title time improves by less than 0.2 s.
- **Toggle.** Presence of the flag. `launch.py --aot`; the wrapper honours `MC_AOT=0`.
- **Depends on.** W0c for the proper metric.
- **Files.** `tools/launch.py`; new `tools/aot-train.sh`; the Prism instance's `aot/wrap.sh` and `instance.cfg` (allowed scope: that one instance).

### W5. Uber heaps off the frame; pre-sized rings ([CB] P1, TAIL V2a/c)
- **Mechanism.**
  1. In `UberGpuBuffer`: after each upload pass, if a layer's free space falls below max(32 MiB, 25% of a heap), create the next 128 MiB Shared heap on a background thread. Touch it with a 1-byte blit so the wiring happens outside the frame, then hand it over at the next `uploadStagedAllocations`. A v1 fallback, if the buffer-pool lock is awkward: create it on the render thread right after submit, inside the GPU slack.
  2. Never destroy the last completely-free heap; destroy only when two are empty (`UberGpuBuffer.java:122-131`).
  3. Size the "Chunk Sections Command Buffer" (`DynamicGpuData.java:119`) and the instanced rings from render distance at world join, instead of doubling them mid-play.
  4. Export heap create/destroy ns for W0a and W0b.
- **Gain.**
  - Removes the in-frame heap creation seen in all 4 tr3 runs 19–27 s into MEASURE. Each costs at most 9.8 ms (2–10 ms [Mod]). Also removes the 2048 → 4096 ring doubling.
  - Travel 1% low 0–2%. Static and median 0, because heaps are created during join.
  - Multiplayer hits the same pattern at RD ≥ 16 in forests.
- **Exact / multiplayer.** Only allocation placement changes. An empty heap holds no allocations and produces no draw group. Client GPU memory only.
- **Confirm.**
  - First, one travel run with `-Dmc.terrain.meshStats=1` plus nanoTime around the `UberGpuBufferHeap` constructor, logged with the frame index.
  - Then 4+4 travel runs with the toggle on/off, judged by **W0a slow-frame counts tagged heap/resize**, not by raw max frame. Same-config max frames span 9.8–37.7 ms.
- **Toggles.** `-Dmc.terrain.heapPrecreate`, `-Dmc.terrain.presizeRings`.
- **Depends on.** Nothing. W0a is used for acceptance.
- **Files.**
  - `client/src/com/mojang/blaze3d/vertex/UberGpuBuffer.java`;
  - `client/src/net/minecraft/client/renderer/DynamicGpuData.java`, `DynamicGpuDataStorageMapped.java`;
  - for background creation, a buffer-pool lock in `MetalDevice.java` / `MetalBuffer.java`. **Those files are also edited by W2:** different methods, so merge W2 first.

### W6. Thread QoS and real priorities ([FC] P3, [JVM] P3, [CB] P9 QoS part)
- **Mechanism.**
  - Step A, zero code: `-XX:ThreadPriorityPolicy=1`. Vanilla's own `setPriority` calls then take effect: render thread → Mach 36, server → 34, others 31. QoS becomes unspecified, and the JVM prints a warning.
  - Step B: `pthread_set_qos_class_self_np(USER_INTERACTIVE)` through the FFM default lookup, on the render thread at device init. Measured to read back as 0x21, and Metal's GCD submission threads follow the main QoS. Also Worker-Main → USER_INITIATED via a ForkJoin `onStart` patch in `Util.makeExecutor`, and IO-Worker → UTILITY.
  - Log `pthread_get_qos_class_np` once at startup in both bench and Prism launches.
- **Gain.** Static about 0 (render thread already 842/842 on S-cores, MST r3b). Travel 1% low 0–10% [H], only if W0a shows T wall well above thread CPU, or E-core residency.
- **Exact / multiplayer.** Scheduling only. Singleplayer server timing changes only locally.
- **Confirm.**
  - X0.2: 2+2 static and 2+2 travel with `--jvm -XX:ThreadPriorityPolicy=1`.
  - Then W0a: E-core share > 1%, or T wall > CPU by > 20% → 4+4 travel A/B of step B.
  - Accept on travel 1% low +5%, with no loss in W0b join-to-all-built.
- **Toggles.** `-Dmcmetal.renderQos=interactive|off` (supersedes TAIL's `MCMTL_RENDER_QOS` name, because the call is made from Java through FFM); `-Dmc.workerQos=initiated|off`.
- **Depends on.** X0.2 and W0a.
- **Files.** New `client/src/com/mojang/renderpearl/backend/metal/ThreadQos.java`; `MetalDevice.java` (one call); new patched `client/src/net/minecraft/util/Util.java`.

### W7. Instant UI transitions, opt-in ([SU] P3)
- **Mechanism.** One toggle:
  - `LoadingOverlay` calls `setOverlay(null)` when the reload completes, removing the 2.0 s `fadeOutAnim` (`LoadingOverlay.java:73,149-150`), and drops the 1 s fade-in hold on later reloads (:173);
  - `TitleScreen` is built with `fading=false`, removing the 2 s widget fade (`TitleScreen.java:295-300`);
  - the new-world `LevelLoadTracker` close delay goes from 500 to 0 ms (`Minecraft.java:2039`).
- **Gain.**
  - `secondsToWorldReady` −2.0 s: static 4.63 → about 2.6 s, fresh world 6.44 → about 3.9 s.
  - Launch → clickable title −2 to −3 s; every later reload (F3+T, pack change) −2 to −3 s.
  - It removes waiting, not computation.
- **Exact / multiplayer.** **Not exact:** a UI animation disappears by design. GOAL (c) requires a side-by-side for the user and the user's approval. Default off. Always report it apart from compute wins (W0c keeps `reloadDone` separate). World rendering is untouched. Client-only UI timing.
- **Confirm.**
  - 5+5 static and 3+3 travel.
  - Assert `screen.init` still runs before the first frame that shows the screen (`LoadingOverlay.java:164-167`).
  - Make the side-by-side clip with the game's own frame dump, never `screencapture` (privacy prompt).
- **Toggle.** `-Dmc.ui.instantTransitions`, default false.
- **Depends on.** W0c and the user's decision.
- **Files.** New patched `client/src/net/minecraft/client/gui/screens/LoadingOverlay.java`, `.../screens/TitleScreen.java`; `Minecraft.java` (one line).

### W8. Keep shader compiles warm ([SU] P5)
- **Mechanism.**
  - **(a) Process.** After every client build that changes MSL, and on every Prism instance update, compile all pipelines once headlessly with the same `java` binary (`tools/i5-pipeline-test.sh`). This fills macOS's per-bundle cache (`net.java.openjdk.java`, shared by every Java Metal program; it has already rolled over once).
  - **(b) Code.**
    - Our own cache in the instance: SPIR-V→MSL output keyed by SHA-256(SPIR-V) + `OPTIONS_VERSION` + bindings (CONTRACT §7.1 allows this), and shaderc output keyed by preprocessed GLSL.
    - An `MTLBinaryArchive` of every PSO variant, used with `MTLPipelineOptionFailOnBinaryArchiveMiss` as a fast path and invalidated by OS build and GPU family.
- **Gain.**
  - Cold cache: 2854 ms wall / 21.2 s CPU → about 150 ms, i.e. −2.7 s on reload, startup and `secondsToWorldReady`.
  - Warm: ≤ −0.1–0.15 s.
  - (a) should also remove the first-run bench outliers (7.1–8.4 s vs 4.63 s). That cause is a hypothesis.
- **Exact / multiplayer.** Content-hash keyed; a miss compiles exactly as today. Invisible to servers.
- **Confirm.** `-Dmcmetal.shaderSalt=<random>` prepends a comment to every MSL source to force OS-cache misses without deleting system caches (deleting them is outside the project). Compare `reloadDone` and the ShaderManager listener time from W0c, cold vs warm vs archive.
- **Toggles.** `-Dmcmetal.binaryArchive`, `-Dmcmetal.shaderSalt`.
- **Depends on.** W0c for (b).
- **Files.**
  - (a): `tools/build-client.sh` or the instance-update step;
  - (b): `native/src` pipeline creation, `MetalRenderPipeline.java`, `MslTranslator.java`.

### W9. First-use hitches in travel ([FC] P4, [SU] P11, [JVM] P6, TAIL V2b)
- **Facts [M].**
  - 15 synchronous entity-texture loads on the render thread in 25 s, with 30 `NoSuchFileException` probes.
  - 158 render-thread deopts, 88 of them repeats at the same site (`PerBytecodeTrapLimit=4`). 48 fell within 7 ms when skeletons first rendered (class_check/bimorphic in `HumanoidMobRenderer.extractRenderState:57`, `ItemInHandLayer.submit:18` and others).
- **Mechanism.**
  - **(a)** Decode `textures/entity/**` and the equipment layers on idle workers after the reload completes, keyed by resource id and pack set. Upload on first use from the decoded cache. `TextureManager` registrations stay identical.
  - **(b)** `-XX:PerBytecodeTrapLimit=1 -XX:PerMethodTrapLimit=20`.
  - **(c)** At world join, run `extractRenderState` + `submit` into a discarded collector for each renderer type. The dummy entities are never added to `ClientLevel` and use their own `RandomSource`.
  - **(d)** JEP 515 profiles are not available under ZGC on JDK 25 (X2); see W18.
- **Gain.** Travel max frame and 0.1% low, by an unknown share. Travel 1% low 0–3%. A risk with (c): megamorphic dispatch can slow scenes with 1–2 entity types, so check the static scorecard.
- **Exact / multiplayer.** Same pixels and same code. (c) is checked with 18-scene parity plus an RNG-state check. Nothing is sent.
- **Confirm.**
  - X0.4: one travel run with JFR started at launch (`jdk.Deoptimization`, `jdk.Compilation` threshold 0, `jdk.FileRead` 0 ms; no `delay`, so there is no mid-run `RedefineClasses`), plus W0a `SlowFrame` events.
  - Build (a) if texture loads explain ≥ 30% of frames over 6 ms.
  - A/B (b) 4+4 travel if deopt bursts coincide with frames over 6 ms. Build (c) only if (b) is insufficient.
- **Toggles.** `-Dmc.tex.entityPreload`, `-Dmc.jit.entityWarmup`; flags for (b).
- **Depends on.** X0.4, W0a.
- **Files.** New `client/src/net/minecraft/client/renderer/texture/EntityTexturePreloader.java`; new patched `.../texture/TextureManager.java`; `Minecraft.java` (one hook after the reload); for (c), new `.../entity/EntityRendererWarmup.java`.

### W10. Region snapshot shortcuts ([CB] P6)
- **Mechanism.**
  1. **All-air shortcut.** In `LevelExtractor`'s `sectionUpdates` (:175-191), when `LevelChunkSection.hasOnlyAir()`, set `CompiledSectionMesh.EMPTY` synchronously and fire `onSectionMeshUpdate`, instead of building a 27-copy region and queuing a task.
     - UNCOMPILED blocks occlusion propagation (`CompiledSectionMesh.java:23-28`), so terrain behind air becomes visible one or more frames sooner.
     - This is distinct from TERRAIN-PLAN S8. For all-air, EMPTY's "all faces see each other" is the true visibility set.
  2. **Later, round 4.** A cross-frame `SectionCopy` cache keyed by a per-chunk modification counter. It needs a patched `LevelChunk`, a class shared with the server, so it shares an owner with W16.
  3. **Per-section BE copy.** Replace the `ImmutableMap.copyOf` of the whole chunk's BE map (`SectionCopy.java:29`) with a copy of only that section's BEs. This is exact, because `RenderSectionRegion` routes `getBlockEntity` by section.
  4. Fold in the [FC] P8 neighbour-check cache (`LevelExtractor.java:177-196`, keyed by chunk-load epoch) if it is trivial. It is exact.
- **Gain.**
  - Render-thread createRegion 2.8% → < 1% of travel render samples.
  - Multiplayer bases with about 300 BEs per chunk: about 0.1–0.2 ms per region [Mod], on block edits too.
  - Fewer queued tasks ([H] air may be half of all compiles) and earlier terrain visibility.
  - Travel 1% low 0–2%; static 0.
- **Exact / multiplayer.** EMPTY is exactly what vanilla compiles for an all-air snapshot; only the timing changes. The BE filter is exact. Client-only; largest benefit in multiplayer bases.
- **Confirm.**
  - W0b counters in travel and in a BE-heavy LAN base: all-air share, `SectionCopy` creations per frame and their ns.
  - Then 4+4 travel judged on normalized FPS (per Mquad), 1% low and holes.
- **Toggles.** `-Dmc.chunk.airShortcut`, `-Dmc.chunk.sectionBeCopy`, later `-Dmc.chunk.copyCache`.
- **Depends on.** W0b.
- **Files.** `client/src/.../extract/LevelExtractor.java`; new patched `.../chunk/SectionCopy.java`, `.../chunk/RenderRegionCache.java`.

### W11. Exact rebuild-waste cut ([CB] P2)
- **Mechanism.**
  - **(a) Identical-mesh skip.** Store per layer: length, two independent 64-bit hashes, VisibilitySet and BE list, in `CompiledSectionMesh`.
    - When every layer matches, keep the existing mesh and allocations, and skip staging, TLSF, callbacks, `setSectionMesh` and occlusion propagation.
    - For translucent layers, upload only the re-sorted indices, and only if the point of view changed.
    - A verify mode memcmps against the Shared heap.
  - **(b) Light-diff dirtying.** Compare incoming nibble arrays with the current or queued `DataLayer` using `Arrays.mismatch`, in `readSectionList` and in the queued-data path (`LayerLightSectionStorage.java:105-113,257`; `ClientPacketListener.java:2578`). Dirty only sections whose 18³ light shell contains a changed cell, which is vanilla's own ±1 rule at :102. Identical data dirties nothing.
  - **(c)** Coalescing light-only rebuilds of distant sections (≤ 100 ms) is **not exact in time**. Off; it needs GOAL (b)/(c) sign-off.
- **Gain.**
  - Travel amplification is CUTOUT 8.6–8.9x, SOLID 4.5–4.8x (static 1.0x). [H] 30–70% of CUTOUT rebuilds are byte-identical (leaf and light churn).
  - Worker CPU falls by that share of about 0.4 cores. Render-thread upload, callback and occlusion work (about 3% of travel render samples) falls proportionally, with fewer `copyLock` collisions.
  - Median about 0; travel 1% low 0–2%. Largest benefit in multiplayer with streaming light.
  - Ships only if a harness metric moves: storm drain time, travel normalized 1% low, or render-thread upload ms.
- **Exact / multiplayer.**
  - (a): identical bytes. The collision probability with two independent 64-bit hashes plus the length check is about 2⁻¹²⁸, and verify mode checks it.
  - (b): a mesh reads light at most 1 block outside its section (`BlockModelLighter.java:43-118`; `FluidRenderer` reads pos and pos.above), so unchanged shell cells cannot change it.
  - Bookkeeping of received data only. `LayerLightSectionStorage` is shared with the integrated server, so gate on the client instance and keep the server path untouched.
- **Confirm.** W0b plus (b)'s dirty-by-source counters, in one travel run and one LAN fly-over. Drop the item if fewer than 15% of rebuilds are identical and the light-dirty reduction is under 25%.
- **Toggles.** `-Dmc.chunk.skipIdentical`, `-Dmc.chunk.lightDiffDirty`, `-Dmc.chunk.lightCoalesceMs` (default 0).
- **Depends on.** W0b. (b) shares an owner with W19 (X14).
- **Files.**
  - (a): `.../chunk/SectionRenderDispatcher.java` (compile-result path), `.../chunk/CompiledSectionMesh.java`;
  - (b): new patched `.../multiplayer/ClientPacketListener.java`, `net/minecraft/world/level/lighting/LayerLightSectionStorage.java`.

### W12. Dense-array mesher (DAM) with dual-compile verifier ([CB] P4)
- **Mechanism.** Memoize the same pure vanilla functions within one compile, using dense arrays instead of hash maps and LRUs.
  - **Slice 1 (about 1 day).**
    - (5) Tint window: a 20×20 per-column colour window with sliding sums, i.e. 400 biome lookups per 16×16 layer instead of 25 per tinted block. The integer sums are identical.
    - (2) An 18³ packed-light memo read through a per-compile snapshot of the 27×2 `DataLayer` references plus the sky top sections. Visible maps are copy-on-write, so the references are immutable.
    - The verifier: `-Dmc.mesh.verify=N` recompiles every Nth section with the untouched vanilla `SectionCompiler` on the same pinned inputs. It memcmps the per-layer vertex bytes, `SortState` and sorted indices, VisibilitySet and BE list, and dumps mismatches to `results/mesh-mismatch/`. Release builds verify 1 in 256 on idle workers and revert the session to the vanilla mesher on the first mismatch.
  - **Full (3–5 days).**
    - (1) a 20³ `BlockState[]` decode;
    - (3) per-state tables (solid render, light-permeable, emission, full-face occluder for a `shouldRenderFace` fast path);
    - (4) `BakedQuad` shape precompute at bake time;
    - (6) all-air in O(1);
    - (7) facing classification during emission, replacing the `TerrainMeshInfo` post-pass (4.8%).

  AO arithmetic stays vanilla code in the same float order. Model access stays vanilla, so resource packs need nothing special.
- **Gain [Mod].** Compile CPU −45 to −60% (1.8–2.5x). The slice covers about 30% of compile samples.
  - Chunk-load throughput 1.8–2.5x where meshing is the bottleneck: full rebuilds after settings changes, F3+A or resource reload; RD ≥ 24; fast multiplayer joins.
  - The synchronous rebuild on block edits (about 1–1.5 ms per section on the render thread) halves, which helps the 1% low while building.
  - Join-to-all-built −0.2–0.5 s at RD12 and −1–2 s at RD32. Travel worker CPU −0.2 cores.
  - Static and travel FPS about 0, because workers are 77% idle.
- **Exact / multiplayer.**
  - Pure functions over one snapshot give the same output as vanilla. The only thing removed is vanilla's own race, where live light is read in the middle of a compile.
  - Proof: 0 verifier mismatches on the 18-scene pinned world, a travel run, a LAN session and two resource packs (one with rotated or non-cubic elements, one with custom colormaps), plus `tools/terrain-test.sh` and `tools/terrain-e2e-test.sh` unchanged.
  - Meshing is outside the ChunkBatch timing window.
- **Confirm.** Slice: storm mode 2+2 with `verify=1`. Require 0 mismatches and ≥ 20% lower compile-ns p50 (W0b), or stop. Full: W0b `join` at RD12 and RD32, 5+5 on time-to-all-built.
- **Toggles.** `-Dmc.mesh.dam` with sub-toggles `.tint`, `.light`, `.states`, `.quadShape`, `.emitFacing`; `-Dmc.mesh.verify=N`.
- **Depends on.** W0b. ClientLevel rebases on W3.
- **Files.**
  - Slice: new patched `client/src/net/minecraft/client/color/block/BlockTintCache.java`, `.../renderer/block/BlockModelLighter.java`; `ClientLevel.java` (`calculateBlockTint:1028`); `.../chunk/SectionCompiler.java` (snapshot + verifier); `tools/TerrainTest.java`.
  - Full: `SectionCompiler.java`, new patched `ModelBlockRenderer.java`, `FluidRenderer.java`, `BakedQuad.java`; `TerrainMeshInfo.java`.
  - The light snapshot uses existing accessors. If it needs a new getter in `LayerLightSectionStorage`, coordinate with W11b.

### W13. Dispatch and upload: O(1) queue and staging outside `copyLock` ([CB] P7 + P8)
- **Mechanism.**
  - **Queue.** Replace `SectionTaskDynamicQueue.poll`'s synchronized O(n) scan (:18-53) with buckets by Chebyshev section distance (initial and recompile FIFOs per bucket). Re-bucket lazily when the camera changes section, keep the recompile-quota rule, and use an MPSC queue for adds from the render thread.
  - **Staging (a).** Reserve the staging range with an atomic bump, memcpy outside `copyLock`, then publish under `copyLock`. A per-slot in-flight counter makes slot rotation skip while copies are still running.
  - **Staging (b), optional.** Workers allocate from TLSF and memcpy straight into the Shared heaps, with freed ranges quarantined until `completedSeq`. This drops the 3 × 49 MiB ring and the per-allocation blits.
- **Gain.**
  - Queue: about n²/2 × 5 ns of serialized scanning is removed — 0.06 s at 5k queued, 0.56 s at 15k, about 2 s for RD32 sets [Mod]. This shows up in join-to-all-built and full-rebuild drain time.
  - Staging: p99.9 and max frame (up to about 50–100 µs per large CUTOUT layer inside the lock the render thread takes every frame). (b) frees 147 MiB.
  - Median 0.
- **Exact / multiplayer.** The queue changes order only (nearest distance band first instead of exact nearest); meshes are identical, and translucent sort keys still use the camera at compile time. Staging writes the same bytes. Client-only.
- **Confirm.**
  - Queue: build only if total poll time > 100 ms or render-thread add waits > 0.5 ms per frame (W0b, join and storm).
  - Staging: (a) if the `copyLock` wait p99.9 > 50 µs; (b) if allocation recording > 0.3 ms in any frame.
- **Toggles.** `-Dmc.chunk.bucketQueue`, `-Dmc.chunk.stagingOutsideLock`, `-Dmc.chunk.directHeapWrite`.
- **Depends on.** W0b, W5. One owner for both halves (same dispatcher file).
- **Files.** New patched `.../chunk/SectionTaskDynamicQueue.java`, `com/mojang/blaze3d/vertex/StagingBuffer.java`; `SectionRenderDispatcher.java`; `UberGpuBuffer.java`.

### W14. JVM flag track ([JVM] P4, P5, P7, [SU] P12)
No code. Runs in free GPU slots. All arms are exact and invisible to servers.

| Arm | Hypothesis | Run | Adopt if |
|---|---|---|---|
| **a. Heap** (resolves X1) | In-window commits (3.41 → 4.07 GB, about 42k first-touch faults of 16 KB) hurt the tail; pretouch costs 105 ms | Travel 4+4 for each of: A = today (`-Xms2G`, pretouch); B = `-Xms4G` (whole heap pretouched, no uncommit); C = `-XX:-AlwaysPreTouch`; each with `-Xlog:gc*,gc+heap=debug` | B: tail (W0a slow frames, 0.1% low) better than A beyond noise. C: tail equals A (then −0.1 s startup). Optional `-Xmx6G -Xms6G` arm only if B wins (halves ZGC cycles) |
| **b. Code cache** | Code-cache-threshold ZGC Majors (733 ms concurrent seen) and cold-nmethod flushing; about 1 per minute of exploration [Mod] | One 10-min travel soak with `-Xlog:gc,codecache=info` | A "CodeCache GC Threshold" Major lands in a typical 30-s window → `-XX:ReservedCodeCacheSize=512m -XX:SweeperThreshold=40` 4+4 |
| **c. Trap limits** | See W9b | — | — |
| **d. Inlining** | Mid-size compiled callees not inlined into tick/extract | One static and one travel run with `jdk.CompilerInlining` (threshold 0); look for "already compiled into a big method" in the hot callers | Hits found → `-XX:InlineSmallCode=4500` 3+3 static, judged on T (W0a) |
| **e. StringDedup** | No evidence either way | — | It is Mojang's default: keep it unless a measured reason appears |
| **f. Parity** | Bench passes `-XX:StackShadowPages=32` (version JSON); Prism does not | — | Align the Prism instance with the bench args (or record the difference in the W0c config block) |

### W15. Worker-pool sizing and dedicated compile pool ([CB] P9, [FC] P6, [JVM] P8)
- **Mechanism.**
  - Zero-code sweep: `-Dmax.bg.threads=3/6/9`, a vanilla property (`Util.java:235`; today 9 = availableProcessors − 1).
  - Optional: a dedicated compile pool of 4–6 threads, split from `Util.backgroundExecutor()` (`LevelRenderer.java:1674-1676`), so singleplayer worldgen (414–459 travel samples) and meshing (500–511) stop sharing one FIFO.
- **Gain.** Travel 1% low ±5% against chunk-load throughput −0–30%. Singleplayer only for the split; static 0.
- **Exact / multiplayer.** Scheduling only.
- **Confirm.** Storm and join modes (2 runs per value) plus travel 4+4 for the best value. Adopt only if the 1% low improves and time-to-all-built does not get worse.
- **Toggle.** `-Dmc.chunk.compilePool=shared|dedicated:N`.
- **Depends on.** W6 (QoS changes who wins contention), W0a, W0b.
- **Files.** `LevelRenderer.java`; `SectionRenderDispatcher.java` (constructor).

### W16. Parse chunk packets off the render thread ([FC] P5)
- **Mechanism.** At Netty decode, build the `LevelChunkSection[]` and light-source data into a side object. In-order main-thread handling then only swaps sections in, with the vanilla path for re-sent chunks (`isValidChunk`) and as fallback (`LevelChunk.replaceWithPacketData` :523-543).
- **Gain.** T −0.1–0.4 ms on chunk-arrival ticks [H]; travel 1% low +4–12% before W2, much less after W2 (X13). Chunk-load throughput up.
- **Exact / multiplayer.** A deterministic parse of the same bytes, with order preserved. `ChunkBatchReceived` then reports a faster client, which is within normal variance between vanilla clients. No deferral or budgeting.
- **Confirm.** W0a packet-type timers **after W2**: build only if `LevelChunkWithLight` handling averages ≥ 0.2 ms per tick or exceeds 1 ms on p99 tick frames. Then 4+4 travel plus LAN smoke.
- **Toggle.** `-Dmc.net.chunkParseOffThread`.
- **Depends on.** W0a, W2. It patches `LevelChunk`, so it shares an owner with W10(2).
- **Files.** `ClientPacketListener.java` (rebases on W11b); new patched `ClientChunkCache.java`, `net/minecraft/world/level/chunk/LevelChunk.java` (shared class; client path only).

### W17. Startup backlog ([SU] P6–P10), all gated by W0c

| Sub-item | Mechanism | Gain | Risk / exactness | Gate | Toggle |
|---|---|---|---|---|---|
| a. Eager reload tasks | While a `LoadingOverlay` is up, wait with `managedBlock` until the frame deadline instead of `parkNanos`, so prepare→apply hops do not wait up to 16.7 ms (`Minecraft.java:1186`; frames capped at 60 fps) | −20 to −60 ms | exact | 20+20 runs (σ 0.18 s) | `-Dmc.reload.eagerTasks` |
| b. Bootstrap overlap | `Bootstrap.bootStrap` and `ClientBootstrap` on a background thread while the main thread creates the window and Metal device (about 0.75 s) | −0.5 to −0.7 s to title; about 0 on today's metric | class-init deadlocks; registry ids must be identical: hash `BuiltInRegistries` and `BLOCK_STATE_REGISTRY` before and after | 30-launch soak plus LAN | `-Dmc.boot.parallel` |
| c. Speculative data-pack stem | Parse the vanilla data pack on idle workers during title/fade; reuse it if the world's `WorldDataConfiguration` is vanilla-only | −0.3 s singleplayer world open | shared mutable state; counts and hashes of recipes, advancements and loot must match | W0c openWorld → serverReady 10+10 | `-Dmc.world.prepareStem` |
| d. Known-pack pre-parse | Pre-parse the vanilla registry JSON used at configuration finish (`ClientConfigurationPacketListenerImpl.java:147-151`) | −0.05 to −0.2 s multiplayer join (unmeasured) | same decode | only if ≥ 100 ms measured in LAN smoke with ms logs | `-Dmc.net.preparseKnownPacks` |
| e. Atlas/model cache | Content-hash cache of stitched atlases (all mips) and baked quads | part of the 0.95 s reload | L effort | only if one listener ≥ 0.4 s is on the critical path | `-Dmc.reload.atlasCache` |

### W18. Full Leyden: GC switch or JDK 26 ([JVM] P2, [SU] P2b)
- **Facts.**
  - With G1, AOT links about 16.6k of 16.8k classes, archives lambdas and records profiles. Bootstrap 1220–1256 → 488–512 ms, process −0.9 s, JIT CPU 2.9 → 1.3 s [M].
  - ZGC rejects G1-built caches. Generational Shenandoah rejects linked caches. Single-generation Shenandoah accepts a G1-built cache (75 ms in the microbench).
- **Gain.** Launch −1.5 to −2.5 s [Mod], plus warm-up profiles that address W9's deopt storms.
- **Cost.** G1 young pauses of about 5–20 ms [Mod] (travel relocates 78–139 MB per cycle), a likely max-frame regression. Shenandoah needs `-XX:-ShenandoahPacing`. JEP 516 (JDK 26) would keep ZGC [H: verify], but a new runtime is a download and bundling step: **ask the user first.**
- **Confirm.** Arms ZGC / Shenandoah / G1 (`MaxGCPauseMillis=10`), 3 static and 4 travel each, with gc and safepoint logs. Kill on any GC pause > 2 ms inside the measurement, or a travel 0.1% low / max frame more than 10% worse than ZGC.
- **Toggle.** JVM flags.
- **Depends on.** W4, W0c.

### W19. Copy-on-write light-section map ([FC] P7, TAIL V3)
- **Mechanism.** A two-level map (region → small immutable array) instead of cloning the whole `Long2ObjectOpenHashMap` per `runLightUpdates` (`LayerLightSectionStorage.java:276-283`). Client instance only.
- **Gain.** About 0: −0.01–0.03 ms per frame, and 40–50% less render-thread allocation. ZGC is not a factor.
- **Exact.** Only if the published-version immutability that W12 relies on is kept (X14).
- **Confirm.** W0a `level.update` split; proceed only if its p99 is ≥ 0.1 ms per frame.
- **Toggle.** `-Dmc.light.cowSectionMap`.
- Same owner as W11b.

### W20–W22 and carry-overs
- **W20, native NEON mesher ([CB] P5).**
  - Port the DAM core to C (`-ffp-contract=off -fno-fast-math`; FCVTZS matches Java casts) behind one FFM critical downcall per section, with a model DB export and RNG/multipart replication. Fall back per state for non-whitelisted `Block` overrides.
  - Gain on top of DAM: −20 to −35% compile CPU; Vector API bulk kernels −5 to −10%.
  - Build only if, after W12, storm or join shows all 9 workers busy with a non-empty queue for more than 1 s. XL.
- **W21, render-thread diet and entities ([FC] P8).**
  - First add a pinned 300–500-mob scene.
  - Pursue instanced entity models (cached part meshes plus pose buffers) only if C > 0.8G there.
  - The neighbour-check cache is folded into W10.
- **W22, Vector API frustum culling ([JVM] P9).** `TerrainDrawList.culledByFrustum`/`boxVisible` is about 0.04 ms per frame today. Stage 3, for LOD section counts. Needs `--add-modules jdk.incubator.vector`, which prints a startup warning.
- **TERRAIN-PLAN S3** (translucent head list) is unimplemented (no toggle in `TerrainToggles`). It is a small GPU median item; rank it once an r4+ GPU profile exists. **S8** is not provably exact and stays toggle-only.

---

## 5. Rounds (4–6 parallel items, with the files each touches)

### X0: zero-code experiments (the next free GPU slot, frozen r4 jar, about 1 h of bench time)

| Exp | Command sketch | Decides |
|---|---|---|
| X0.1 | Static 2+2: `--jvm -Dmcmetal.skipPipelines=vignette` vs none | W1 size |
| X0.2 | Static 2+2 + travel 2+2: `--jvm -XX:ThreadPriorityPolicy=1` vs none | W6 |
| X0.3 | 1 AOT training run (with the bench agent), then static 3+3 with `-XX:AOTCache` + `-Xlog:aot=warning` | W4 (use log timestamps for title time until W0c lands) |
| X0.4 | 1 travel run with JFR from launch (deopt, compilation, FileRead at threshold 0), plus 1 × 10-min travel soak with `-Xlog:gc,codecache=info` | W9, W14b |

### Round 1: prerequisites plus cheap exact wins

| Item | Files | Conflict notes |
|---|---|---|
| W0a + W2 (one owner, two commits) | `Minecraft.java`; new `FrameTrace.java`; `MetalTimeline.java`, `MetalCommandEncoder.java`, `MetalDevice.java`, `MetalBuffer.java`, `MetalTransientMemory.java`; `native/src/.../mcmetal_common.m`; new `bench/.../FrameTraceHook.java` (+1 line `Bench.java`) | Only owner of `Minecraft.java` this round |
| W0b | `SectionRenderDispatcher.java`, `LevelExtractor.java`, new `ChunkStats.java`, new `bench/.../ChunkStatsHook.java`, `Bench.java` (modes) | `Bench.java` shared with W0c: additive, merge W0c first |
| W0c (+ W8a process step) | `Bench.java`, new `tools/startup-log4j2.xml`; build/instance-update step | — |
| W1 | new `client/gui/Hud.java`, new test | Check with the zoom workstream (spyglass overlay is in `Hud`) |
| W3 | new `client/multiplayer/ClientLevel.java`, new `AmbientParticleGate.java`, new test | — |
| W5 | `UberGpuBuffer.java`, `DynamicGpuData.java`, `DynamicGpuDataStorageMapped.java` (+ buffer-pool lock in `MetalDevice.java`/`MetalBuffer.java` for v2) | v2 touches W2's files in different methods: merge W2 first, or ship v1 (render-thread creation after submit) this round |

### Round 2: gated by round-1 data

| Item | Files | Gate |
|---|---|---|
| W4 | `tools/launch.py`, new `tools/aot-train.sh`, Prism instance `aot/wrap.sh` + `instance.cfg` | X0.3, W0c |
| W6 | new `ThreadQos.java`, `MetalDevice.java` (1 line), new patched `util/Util.java` | X0.2, W0a |
| W9a (+ b flags) | new `EntityTexturePreloader.java`, new patched `TextureManager.java`, `Minecraft.java` (1 line) | X0.4, W0a attribution ≥ 30% |
| W10 (1)(3)(4) | `LevelExtractor.java`, new patched `SectionCopy.java`, `RenderRegionCache.java` | W0b all-air share |
| W11a | `SectionRenderDispatcher.java`, `CompiledSectionMesh.java` | W0b identical share ≥ 15% |
| W12 slice | new `BlockTintCache.java`, new `BlockModelLighter.java`, `ClientLevel.java`, `SectionCompiler.java`, `tools/TerrainTest.java` | storm ≥ 20%, 0 mismatches |

No two round-2 items share a file except `ClientLevel.java` (W3 merged in round 1, so W12 rebases) and `MetalDevice.java` (one line in W6).

### Round 3: larger, gated

| Item | Files | Gate |
|---|---|---|
| W12 full | `SectionCompiler.java`, new `ModelBlockRenderer.java`, `FluidRenderer.java`, `BakedQuad.java`, `TerrainMeshInfo.java` | slice passed |
| W13 (one owner) | new `SectionTaskDynamicQueue.java`, new `StagingBuffer.java`, `SectionRenderDispatcher.java`, `UberGpuBuffer.java` | W0b histograms |
| W11b (+ W19 if gated; one owner) | new `ClientPacketListener.java`, new `LayerLightSectionStorage.java` | dirty-source counters |
| W7 | new `LoadingOverlay.java`, new `TitleScreen.java`, `Minecraft.java` (1 line) | **user approval** |
| W8b | `native/src` pipeline cache, `MetalRenderPipeline.java`, `MslTranslator.java` | `shaderSalt` cold/warm data |
| W14 adoption | `tools/launch.py`, Prism `instance.cfg` | X1 results |

### X1: flag track (any free slot, parallel to rounds 2–3)
W14a heap 3-arm (12 travel runs), W14b code cache (if X0.4 shows it), W9b trap limits, W15 `max.bg.threads` sweep (needs W0b), W14d inlining (JFR first).

### Round 4 onward: the continuing loop backlog
- W10(2) and W16 (one owner: both patch `LevelChunk`, and W16 also edits `ClientPacketListener`);
- W15 dedicated pool;
- W17a–e;
- W18 (ask the user before any runtime download);
- W20, W21;
- TERRAIN S3.

---

## 6. Measurement protocol and acceptance rules

Noise levels from existing data:
- static median pairs differ by up to 5.6% (r4: 845.6 / 894.7);
- static 1% low by 4–7% (405.7 / 388.6; r3b 376.6 / 350.7);
- travel 1% low by about 4% (286.8 / 276.5);
- **travel max frame ranges 9.8–37.7 ms within one configuration** (v-s5c-tr3);
- startup σ = 0.18 s.

1. **Bench hygiene.**
   - Benchmark frozen jar copies (`cp -p`), interleaved ABBA, behind the quiet and overlay gates.
   - Never benchmark while agents build.
   - Close Notification Center or UserNotificationCenter windows before runs (round 4 ran composited).
   - Every JSON carries the W0c config block.
2. **Static.** 2+2 resolves about 6% or more; 4+4 for 3–6%. For 1% low effects under 8%, use 4+4, or W0a p99 by frame class (tick / plain / presented), which is more sensitive.
3. **Travel.**
   - At least 4+4.
   - Always report FPS per million drawn quads and the end-of-run resident terrain MB. Any item that changes fill speed (W6, W10–W13, W15, W16) is judged on normalized FPS. A travel median "loss" explained by more resident terrain is not a regression, and a "gain" from less terrain is not a win.
4. **Max frame / 0.1% low.** Never accept or reject on raw max frame with ≤ 8 runs. Use W0a's per-cause counts of frames over 6 ms (event bitmask plus `SlowFrame` JFR alignment). Accept an event fix when its cause count goes to about 0 and the 1% low does not regress.
5. **Startup.** 5+5 resolves about 0.25 s; 0.1 s needs about 20+20. Report JVM-start→title, →levelReady and `reloadDone` separately. Compute wins and UI-wait removals (W7) are never summed into one number.
6. **Chunk loading.** W0b `join` (RD12, RD32) and `storm` drain time. Establish their noise with 5 baseline runs before the first A/B.
7. **Shipping.**
   - Default-on only if at least one scorecard metric (including the new chunk and startup metrics) improves beyond noise and none regresses.
   - Exact items need unit tests plus 18-scene parity on `run/scenes-pinned-r4`. Mesher items also need 0 verifier mismatches.
   - Release candidates add `tools/lan-smoke.sh` (all three host/guest pairings) and the soak.
   - Each shipped item gets a CONTRACT §16.x entry (toggle, mechanism, exactness argument, numbers) and a row in `results/ROUNDn.md`.

---

## 7. The continuing optimization loop (after round 3)

For every pass:
1. Re-profile on the newest frozen jar:
   - static and travel JFR **started at launch** (avoids the 37–47 ms `RedefineClasses` artefact);
   - a round-N MST (none exists for r4 or for travel, TAIL E16);
   - the W0a trace, W0b storm/join and W0c startup.
2. Update §1's table. Re-rank by (measured cost × share of frames or launches) ÷ effort.
3. Take the top 4–6 items whose files do not overlap.
4. Implement each behind a toggle with tests and notes, then verify (parity including zoom sequences, LAN, soak, every scorecard).
5. Keep only measured wins. Update the "26.3 Metal" Prism instance with rollback, including the AOT retrain once W4 ships.
6. Start the next pass. The user ends the loop.

Expected trajectory:
- Game-side items stop moving the static median after W1, because the median is GPU-bound. From there the loop's median work goes back to the GPU and backend (TAIL S3 cross-frame serialization, TERRAIN S3, fragment/bandwidth).
- Game-side work keeps paying on T (which W1 and other GPU wins re-expose), on travel events and on chunk and startup metrics.
- Stage 3 (LOD) will turn W12, W13, W20 and W22 from throughput items into frame-rate items, because section counts rise by orders of magnitude.

---

## 8. Rejected or deferred

| Idea | Why |
|---|---|
| `/tick rate 40` arm | The client clamps to ≥ 50 ms per tick (`Minecraft.java:2970-2975`); use 5 / 10 (TAIL C6) |
| More FFM critical downcalls | 2–4 downcalls per frame, under 1 µs in total. `timelineWait` must stay non-critical, because a critical downcall blocks safepoints |
| Graal JIT, large pages, transparent huge pages | Not available in this runtime ([JVM] measured) |
| `TieredStopAtLevel=1` | The render path runs about 98% C2 code |
| Time-budgeting or deferring packet handling | Changes ChunkBatch semantics and slows chunk loading (X13) |
| Unifont parsing speedup | 40–55 ms, single-threaded, off the critical path ([SU]) |
| Client jar first on the classpath | No difference ([SU] measured) |
| Standalone base CDS archive | −26 ms; superseded by W4 |
| Trimming the vignette quad to non-zero texels | Only 10% of texels are exactly zero ([FC]) |
| Deleting system shader caches for experiments | Outside the project; use `-Dmcmetal.shaderSalt` (W8) |
| HiZ / occlusion beyond the cave graph | Costs more bandwidth than it saves on TBDR at 8 Mpx (culling P5, TERRAIN §7) |
| Non-exact light-rebuild coalescing as default | Not exact in time; opt-in only (W11c) |

## 9. Open questions (what decides the uncertain estimates)

1. Is the GUI pass a separate encoder in r4, and does Apple skip load/store on untouched tiles? This sets W1 at +3% or +25%. Answered by X0.1 plus decoder stats or MST.
2. TAIL C2: T wall vs thread CPU, and core placement in travel. This decides W6 and W15. Answered by W0a.
3. Share of byte-identical rebuilds (W11) and all-air share of compiles (W10). Answered by W0b.
4. Real cost of in-frame heap creation, 2 or 10 ms (W5). Answered by constructor timing.
5. Main-thread share of class loading (W4 size); critical reload listener (W17e). Answered by W0c and the profiled reload.
6. Do first-use texture loads or deopt bursts actually produce the travel max frames (W9)? Answered by X0.4 plus W0a.
7. Whether JDK 26 / JEP 516 gives full AOT under ZGC (W18). Needs a runtime download, so the user decides.
8. The user's real play settings (render distance, singleplayer vs which servers). Chunk items (W10–W13) are worth more at high RD and in multiplayer bases than the RD12/RD16 scorecards show.
