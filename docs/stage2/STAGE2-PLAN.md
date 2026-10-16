## Corrections to the stage-2 plan (critic pass, 2026-10-05)

Sources checked: `r5-int` (645116f, based on main bdba226) and its CONTRACT §16.R5-A to §16.R5-E, vanilla source, `Bench.java`, the r3 JFRs (re-read under the lock: CompilerStatistics, ThreadCPULoad, JavaExceptionThrow), and the bench JSONs. Nothing was launched or built.

### A. The plan predates round 5
1. **"Nothing in it is implemented yet" is false.**
   - `r5-int` has 63 files and +15.4k lines. Everything below is default off, headless-tested, and waiting for its in-game A/B:
     - W2 (`-Dmcmetal.lateThrottle`, R5-A);
     - W0a's core plus TAIL §6.3's config block (`-Dbench.frameTrace`, probes inserted by the bench agent, a native GPU ring, `tools/frame_trace.py`, R5-B);
     - TAIL S3 and S5 (`-Dmcmetal.renameSmallWrites`, `-Dmcmetal.transientHysteresis`, R5-C);
     - W3, including section-direct lookups and batched RNG (`-Dmc.fastAnimateTick`), and TAIL S4c (`-Dmc.fastTextureAnimation`, R5-D);
     - W5 with ring presizing (`-Dmc.terrain.pregrow`), W9a (`-Dmc.asyncEntityTextures`), and an upload budget (`-Dmc.terrain.uploadBudgetKB`, R5-E). The plan does not list the upload budget at all.
   - Rebase the plan on r5-int and use r5's toggle names.
2. **File ownership has changed.**
   - r5-int already patches: `Minecraft`, `ClientLevel`, `TextureAtlas`, `SpriteContents`, `TextureManager`, `UberGpuBuffer`, `StagingBuffer`, `SectionRenderDispatcher`, `DynamicGpuData*`, `LevelRenderer`, `Bench`, `BenchAgent`, and the Metal backend.
   - The zoom work on main patches `LevelExtractor`, `Options`, `Camera` and `MouseHandler`.
   - W0b, W10, W11a, W12 and W13 must rebase on these. W0a's extensions are bench-agent probes and need no `Minecraft.java` edit, so the "W0a + W2 one owner" rule goes away.

### B. Estimates that do not hold up
3. **W1 "+20–30% [H]" is contradicted by earlier measurements.**
   - The GUI has been merged into the main encoder since M2 (M2-ROUND1: 4.4 passes in → 1.4 encoders, "merged Sky+Main+Hand+GUI"). Present fusion is on.
   - The hotbar and crosshair keep the GUI draws alive anyway, so skipping the vignette cannot remove a load/store.
   - Keep +3–12% [Mod]. Drop the +20–30% unless X0.1 measures more than 12%.
4. **W1 travel gain is overstated.**
   - Travel measurement starts at least 18 s after world-ready: SETTLING ≥ 8 s (`Bench.java:183`) plus warmup 10 s.
   - The vignette's 8-bit colour reaches 0 at 27.6 s (552 ticks; `ARGB.as8BitChannel` = floor(x·255)). Only about two thirds of the measured travel frames benefit.
   - Travel median gain: +1–5%, not +2–7%. Static is fine: the gate is at 23 s or later, so about 85% of frames benefit.
5. **W3's T −0.13 to −0.20 ms is about 2–3 times too high.**
   - R5-D measured headless: animateTick 112 → 48 µs per tick (−0.064 ms). Adding the texture-animation change gives about −0.08 ms in total.
   - Tick costs taken from JFR sample shares are inflated. In r3-static the tick bundle is 32/158 samples × 0.32–0.39 ms/frame × 31 frames/tick = 2.0–2.4 ms per tick, while the MST measured +0.80 ms.
   - Corrected: static 1% low +2–3% before W2 (not +7%); travel +1–2% before W2 (not +4%); about 0 after W2.
6. **"Positive again once G ≲ 1.05 ms" uses the median G.**
   - After W2, the slowest 1% of frames are heavy-direction frames with G = 1.62–1.74 ms. There, T + C_tick (1.7 ms) stays below 2G − C (2.8–3.1 ms), with 1.1–1.4 ms of margin.
   - W3, W16 and W23 only matter for the 1% low again once heavy-direction G falls below about 1.05 ms (−35 to −40% GPU). Nothing in this plan gets there.
7. **W5's in-frame heap cost is 3.3–4.4 ms, not 2–10 ms.** R5-E probes measured it.
   - The cost is a stall on the GPU's first use of the new heap.
   - CPU-side creation takes only 0.02–0.2 ms, and CPU pre-touch still leaves 1.1–1.4 ms.
   - So the "v1 fallback" (create the heap on the render thread after submit) does not remove the stall. Cut it.
8. **W10(1), the all-air shortcut, is already vanilla behaviour.**
   - `ClientChunkCache` tracks all-air sections (`:251`, `:292-305`).
   - `SectionOcclusionGraph.runUpdates` (`:276-282`) never adds them to the section tree and sets UNCOMPILED → EMPTY synchronously. They never reach `visibleSections`, so they never get a region or a compile task.
   - "[H] air may be half of all compiles" is refuted. Remove W10(1) and its "earlier visibility" gain.
9. **W12's join gain at RD12 (−0.2 to −0.5 s) is larger than the plan's own bound.**
   - Meshing is about 0.5 s of wall time. A 2.5x speedup saves at most 0.5 × (1 − 1/2.5) = 0.3 s.
   - Less in practice: in singleplayer, worldgen competes for the same workers; in multiplayer, the network sets the pace.
   - Use −0.1 to −0.3 s.
10. **W17a (−20 to −60 ms) cannot be detected end to end.** With σ = 0.18 s, 20+20 runs resolve about 0.11 s. Judge it on the profiled reload's own timer, or cut it.
11. **"JIT is done" is true for static only.**
    - r3-travel.jfr, CompilerStatistics: compile time 43.6 → 48.4 s and compile count 35,360 → 37,604 within 24 s. That is about 0.2 cores and about 94 compiles/s.
    - Static: 0.05 cores and 27 compiles/s.
    - The travel figure is partly inflated by JFR's own RedefineClasses at recording start (22 events). It is a candidate cause of X7's burst contention, and is relevant to W9b and W18.
12. **"Max 9.8–37.7 ms within one configuration" spans two configurations.** tr3-off measured 9.78 / 21.23 ms; tr3-exact measured 37.66 / 11.72 ms. The 2–3x spread within one configuration still stands.

### C. Areas and measurements that are missing
13. **The stage-1 gate is still open, and the plan ignores it.**
    - GOAL: each stage must meet its bar before the next starts.
    - The 1% low is 397 against a bar of 488–510.
    - There is no soak tool and no soak result in the repo.
    - Fix: say this explicitly, and build `tools/soak.sh`. The soak also validates memory: the R5-E spare heaps (≤ 320 MB), the prefetch cache (64 MB), AOT and heap sizing.
14. **No item covers `tickEntities`.** Its share of the tick is about the same as `animateTick` (E4: 11/28 samples). R5-D(d) lists four exact proposals, three of them in classes shared with the integrated server. Add W23.
15. **The R5-E upload budget is not in the plan.**
    - By design it delays terrain by up to 2 frames, and it can lower chunk-load throughput.
    - It needs W0b's join/storm metrics and GOAL (b)'s temporal check. Add W24.
16. **There is no multiplayer performance bench.**
    - Every scorecard is singleplayer, where the integrated server shares the CPU with the client. In r3-travel ThreadCPULoad: Server thread 0.16 cores, 9 workers 1.44 cores.
    - Add MP travel against a headless dedicated server started from the vanilla client jar. `net.minecraft.server.Main` is in that jar, so no download is needed. Setting `eula=true` accepts Mojang's EULA, so the user must decide. The alternative is the existing lan-smoke host.
17. **Scenario coverage is missing, and these runs need no code:**
    - RD32 (open question 8);
    - fast travel with `--bench-prop speed=33` (default 10.9, `Bench.java:63`);
    - a mob scene (moved up from W21);
    - a base with many block entities.

    They decide whether W12, W13 and W16 matter at all.
18. **The fallback backends are never tested.** Patched vanilla classes also run under Vulkan and OpenGL. Add a Vulkan + OpenGL smoke and parity run each round.
19. **Some item types need extra checks:**
    - items that change timing (W11, W13, W16, W24) need GOAL (b)'s temporal check;
    - HUD and entity items need the zoom sequences;
    - scheduling and JVM items (W6, W9b, W14, W15) need a guard on the LAN-host server tick (the `minecraft.ServerTickTime` JFR event).
20. **Items that shrink T need an acceptance rule.**
    - Their 1% low effect (≤ 3%) is below scorecard noise (4–7%).
    - Proposal, for the lead or user to decide: the r5 frame trace counts as "the harness" for T. Accept when tick CPU drops beyond noise and no scorecard metric regresses.
21. **The AOT cache can switch off silently** (`AOTMode=auto`), for example when a flag changes.
    - The config block must record whether the cache was mapped.
    - Flag A/Bs either run without AOT, or train one cache per arm.
22. **ZGC headroom at the user's settings is unchecked.**
    - Heap use reaches 3.9 of 4 GB before each GC (E15); travel allocates 340–420 MB/s.
    - At RD32, check `jdk.ZAllocationStall`.
23. **There is no profile of the current build.**
    - The fact base uses r3 JFRs taken at about 620 fps; no r4 or r5 JFR or MST exists.
    - Add X0.0: JFR from launch plus an MST on the r5 jar, before re-ranking.
24. **W9a's decode overlaps world join.** It decodes 743 PNGs (14.3 MB) after the startup reload, so guard world-join time. Its daemon threads and the R5-E reserve worker should get UTILITY QoS under W6.
25. **W0b should report compile time per surface column.** Stage 3's meshing gate needs it (ZOOM §6.2).
26. **Upload/resident "amplification" mixes rebuilds with churn.**
    - At 10.9 blocks/s over 40 s, the camera crosses about 27 chunks against a 33-chunk RD16 disc. Churn alone gives about 1.8x distinct sections over resident ones.
    - TRANSLUCENT's 1.9–2.2x is about that baseline. So the real excess is roughly 2.4x for SOLID and 4.5x for CUTOUT.
    - Measure rebuilds per distinct section instead.

### D. Conflicts with the backend and with stages 3–5
27. **W12 and W20 sit on stage 3's critical path.**
    - ZOOM D2: L0 LOD is `SectionCompiler` output from cached block data, with a 300 ms budget for 3.5k–5.4k columns and about 20k columns/s on fast pans.
    - W12 must therefore read from an input abstraction, not from `RenderSectionRegion` / `ClientLevel`.
    - W20 competes with ZOOM's GPU L0 meshing fallback, so move W20 into stage 3's meshing decision.
28. **Stage 4 needs extended vertex attributes:** `mc_Entity`, `mc_midTexCoord`, `at_tangent`. W12's emission and W11a's hash must not hard-wire the 28-byte vanilla vertex. Stage 5's per-section acceleration-structure (BLAS) rebuilds gain from W11a.
29. **Cut W22.** ZOOM D3 selects LOD on the GPU. For vanilla sections W22 saves 0.02–0.03 ms, and it prints an incubator warning at every launch.
30. **W13 and W15 must leave room for stage 3.** ZOOM §6.3 says LOD prefetch gets its own pool, or a priority below the vanilla queue. The bucketed queue needs a priority class.
31. **W16 and stage 3's multiplayer ingest** (ZOOM §5) use the same chunk-packet path, so they need one owner.
32. **W8b's native changes** must follow R5's pattern: extension exports outside the frozen ABI-2 header. Its shader cache should be shared with stage 4's shaderpack compiler.
33. **W13(b) must keep two existing guarantees:**
    - R5-A: nothing frame N−1 still reads is written;
    - R5-E: the upload budget.

    W13(a) rebases on R5-E's `StagingBuffer`.
34. **W9c's exactness is disputed.** R5-E concluded that warming up entity renderers "is not exact-safe"; for example, it bumps the global entity-id counter. Demote W9c to "only with a written list of side effects".
35. **Cut W19.**
    - R5-E: the light-map clone is required on the client, because compile workers read light through `RenderSectionRegion.getLightEngine()`.
    - It costs about 40–80 µs every 8–16 frames.
    - A client-only variant needs 5–6 patched classes shared with the server.

### E. Commands and protocol
36. **`--jvm -D…` and `--jvm -XX:…` do not work.** argparse rejects them, and `bench-one.sh` hides the error (commit 17f40ad). Use `--jvm=-…`, and check each JSON's config block.
37. **`build/r4f.jar` does not exist.** `build/` holds `client-r4.jar` and `client-r5z.jar` (= main). Use a frozen copy of the r5-int build: the same jar as r5's A/B, so the results stack.
38. **Measure X0.1 on GPU time, not FPS.** Use R5-B's per-30°-yaw mean `gpuBusyNs`, not the FPS median. "≥ 2% in 4+4" contradicts §6, where 4+4 resolves 3–6%.
39. **FPS per million drawn quads needs `-Dmc.terrain.stats=true`.** It fills `perSecondTerrainQuads` (`Bench.java:227`), which is missing from the r4 travel JSONs. Check its overhead first.
40. **Checked, and not an item:**
    - r3-travel threw 3,016 `UnixException`s on IO-Worker threads. They come from `RegionFile.write`, i.e. integrated-server chunk saving, about 120/s, on the server side, and the cost is negligible.
    - The 30 render-thread `NoSuchFileException`s are W9a's first-use texture lookups.

---

# Stage 2 plan: game-side speedups, ranked (rev. 2: rebased on r5-int)

**Status.** This is a plan.
- Branch `r5-int` (645116f) has implemented W0a's core, W2, W3, W5, W9a, TAIL S3, S4c and S5, and an upload budget (W24).
- They are default off and headless-tested. The in-game A/B is in progress in another workflow (CONTRACT §16.R5-A to §16.R5-E on r5-int).
- Nothing else is implemented.
- For this revision, the r3 JFRs and the bench JSONs were re-read. The game, benchmarks and builds were not run.

**Sources.**
- The four stage-2 analyses: [CB] chunk building/upload; [FC] frame CPU; [JVM]; [SU] startup/world join.
- [R5-A] to [R5-E]: the round-5 CONTRACT notes.
- `results/TAIL-ANALYSIS.md` [TAIL], `results/ROUND4.md`.
- `docs/stage2/TERRAIN-PLAN.md` (S0–S2 and S4–S7 shipped; S5 compact vertices merged default off), `docs/stage2/culling.md` (P1–P3 shipped).
- `docs/stage3/ZOOM.md` [ZOOM], CONTRACT §16.

**Tags.** [M] measured, with the file named. [Mod] model or estimate. [H] hypothesis.

**Metrics.**
- Scorecard metrics: static median and 1% low; travel median, 1% low, 0.1% low and maximum frame.
- New metrics: chunk-load throughput, join-to-all-built, `storm` drain time, startup (JVM start to title, to level ready), world-join s, and multiplayer travel (W0d).

**Rules for every item.**
- Client-only and exact, or opt-in when not exact.
- An A/B toggle, tests, and a CONTRACT §16.x note.
- Branched from r5-int, or from main once r5 has merged.
- Ships default-on only if the harness shows a gain and no scorecard metric regresses.
- Before shipping, items that patch vanilla classes also pass:
  - a Vulkan and OpenGL smoke run;
  - 18-scene parity;
  - the zoom sequences, for HUD and entity items;
  - GOAL (b)'s temporal check, for items that change timing;
  - a LAN-host server-tick guard, for scheduling and JVM items.

---

## 0. Stage-1 gate (open)

- **Stage 1 is not closed.**
  - The static 1% low is 397. The bar is 488–510 (2x vanilla Vulkan's 244–255).
  - The 30-minute soak has never run, and no soak tool exists.
- **What stage-2 work depends on.**
  - The items that close stage 1, W2 and TAIL S3, are in round 5 already. Stage-2 work runs in parallel, and its gains are reported separately.
  - The user should be told that stage 1 is still open.
  - `tools/soak.sh` (W0e) is a round-1 prerequisite.

## 1. Summary

1. **The static median is GPU-bound.**
   - G is about 1.15 ms at 870 fps [M ROUND4]. The render thread waits 73–77% of the time [M r3-static.jfr].
   - **W1** (skip the vignette when it is an exact no-op) is the only game-side item expected to raise the median: +3–12% [Mod], bit-exact, XS.
   - The +20–30% hypothesis is retracted: the GUI pass is merged into the main encoder (M2) and present fusion is on.
   - X0.1 measures the vignette's GPU cost directly, from the frame trace's per-direction GPU-busy time.
2. **The 1% lows are set by the exposed 20 Hz tick bundle T:** 1.1–1.2 ms static, 1.4 ms travel [TAIL].
   - W2 (R5-A) is the lever, and it decides stage 1 [Mod: 397 → 519–579].
   - What T is made of is unknown. R5-D measured `animateTick` at about 0.11 ms of T, so JFR sample shares overstate tick components by 2–3x.
   - W3 (R5-D) gives an exact −0.08 ms on T. That is +2–3% on the 1% low before W2 and about 0 after.
   - After W2, T-reduction items only matter for the 1% low once heavy-direction G is below about 1.05 ms. They are CPU-headroom items.
   - Decompose T with the frame trace (W0a) before investing further.
3. **Travel maximum frames are discrete events.** The trace (R5-B) attributes them. R5-E covers three causes:
   - heap creation: a 3.3–4.4 ms first-use stall on the GPU [M R5-E];
   - command-buffer ring resizes;
   - first-use entity textures.

   Deopt bursts remain (W9b). The upload budget (W24) trades up to 2 frames of terrain latency for fewer upload bursts.
4. **Chunk meshing is not a bottleneck in today's scorecards.**
   - Workers are about 84% idle in travel [M r3-travel ThreadCPULoad, 0.16 cores each].
   - Chunk items (W10–W13) mainly buy chunk-load throughput, multiplayer bases and CPU headroom, and they matter for stage 3: L0 LOD is vanilla meshing on cached data [ZOOM D2].
   - They need W0b's metrics and W0d's scenarios (RD32, fast travel, multiplayer).
   - Travel FPS is confounded by how much terrain has loaded. FPS falls from 563–580 to 467–471 between the first and last 5 s of a run. Normalize by drawn quads.
5. **Startup.** Today's metric (4.63 s) starts at the first frame and contains a fixed 2.0 s fade. W0c fixes the metric.
   - W4 (AOT under ZGC): −0.45 s measured on the Bootstrap workload, −1.0 s at most, exact.
   - W8a: −2.7 s on cold shader caches.
   - W7 (instant UI) is opt-in and needs the user's approval. It is never added to the compute wins.
6. **The JVM is not a frame-time factor in static:** ZGC pauses ≤ 27 µs, no stalls, JIT idle.
   - In travel, C2 still compiles about 94 methods/s, about 0.2 cores [M r3-travel CompilerStatistics; partly inflated by JFR's own redefinition].
   - The flag items (W14, W9b) target travel tails.
   - The heap must be checked for allocation stalls at RD32.

## 2. Fact base

| Metric | State | What dominates it | Source |
|---|---|---|---|
| Static median | 870 fps, G ≈ 1.15 ms (r4) | GPU. The render thread uses about 0.32–0.39 ms of CPU per frame, measured on r3 at about 620 fps; no r4 or r5 JFR exists | [M] ROUND4, r3-static.jfr |
| Static 1% low | 397 (p99 2.52 ms); the bar is 488–510 | Tick frames, T 1.1–1.2 ms (fitted). Measured component: `animateTick` about 0.11 ms per tick (headless). The rest is unknown | [Mod] TAIL fit, [M] R5-D |
| Travel median | 527 fps (G ≈ 1.9 ms) | GPU. Falls from 563–580 to 467–471 fps between the first and last 5 s as resident terrain grows | [M] r4-tr JSONs |
| Travel 1% low | 282 | T ≈ 1.4 ms | [Mod] TAIL |
| Travel max / 0.1% low | max 22.5 / 28.9 ms (r4); 9.8 / 21.2 (tr3-off), 37.7 / 11.7 (tr3-exact) | Heap first use 3.3–4.4 ms; ring resize; 15 synchronous entity-texture loads; 158 render-thread deopts (40 within 7 ms at the first skeleton) | [M] R5-E probes, logs, r3-travel.jfr |
| CPU (travel) | JVM 1.69 cores mean; machine 2.6 mean, 3.9 max | 9 workers about 0.16 cores each (1.44 in total), Server thread 0.16, render thread 0.21, C2 about 0.2 cores (94 compiles/s) | [M] r3-travel ThreadCPULoad and CompilerStatistics |
| Chunk meshing | about 1.5 ms of Java CPU per non-empty section [Mod] | AO 54.6%, light 20.8%, palette 14.6%, biome tint 11.2%, `shouldRenderFace` 9.2%. All-air sections are never compiled (vanilla) | [M] r3-travel 500 compile samples; `SectionOcclusionGraph:276-282` |
| Rebuild waste | Uploaded / resident: CUTOUT 8.6–8.9x, SOLID 4.5–4.8x, TRANSLUCENT 1.9–2.2x; static 1.0x | About 1.8x of that is churn (sections entering and leaving the disc). The excess is about 4.5x for CUTOUT and 2.4x for SOLID | [M] CB logs; [Mod] churn |
| Startup | about 8.1–8.2 s launch → world-ready; metric 4.63 s (σ 0.18) | JVM and Bootstrap about 2.0 s; window about 1.5 s; reload 0.95 s; fade 2.0 s; join 1.64 s; cold Metal cache +2.7 s | [M] SU |
| JVM | ZGC, COH, 2–4 GB, pretouch, StringDedup (Mojang's 26.3 `default-user-jvm`) | Travel allocates 340–420 MB/s; heap 3.9 / 4 GB before each GC; committed heap 3.41 → 4.07 GB inside the window; every thread at QoS DEFAULT | [M] JVM, E15 |

## 3. Contradictions and how they are resolved

These are the earlier X1–X17, cut down to the rows that still decide something, plus new rows X18–X23.

| # | Conflict | Resolution |
|---|---|---|
| X1 | Pretouch more (`-Xms4G`) or less | W14a, 3 arms on travel. The tail outranks 0.1 s of startup |
| X2 | Do JEP 515 profiles fix deopt storms? | No under ZGC on JDK 25.0.1 (`MethodTrainingData=0`, measured). Use W9b or W18 |
| X3 | AOT size | Measured floor −0.45 s (Bootstrap workload). Full launch −0.45 to −1.0 s [Mod] |
| X4/X5/X8 | Priorities, QoS, heap stall | Priorities are a no-op unless `-XX:ThreadPriorityPolicy=1`. Static QoS gain about 0. Heap stall measured at 3.3–4.4 ms (R5-E) |
| X6 | Worker QoS direction | USER_INITIATED for compile workers, UTILITY for IO-Worker and the R5 daemon threads. Test UTILITY workers only inside W15 |
| X7 | CPU contention, mean vs burst | Both are true. The trace's tick wall vs CPU split decides |
| X13 | Packet handling | Never time-budget packets. Off-thread parsing (W16) is allowed |
| X14 | Who edits `LayerLightSectionStorage` | W11b only (W19 is cut). W12 relies on the published map being immutable |
| X17 | Size of the vignette cost | GUI merged since M2 and fusion on, so the +20–30% is retracted. X0.1 measures `gpuBusyNs` |
| X18 | T share: JFR samples (animateTick 35% of tick) vs R5-D headless (0.11 of 1.1–1.2 ms) | Sample shares overstate by 2–3x (TAIL C3). Use the trace's sub-timers |
| X19 | W5 v1 (render-thread creation after submit) vs R5-E probes | The cost is the GPU's first use, so v1 does not help. A side-queue residency blit is required (as R5-E does) |
| X20 | W9c "exact" vs R5-E "not exact-safe" | Demoted. It needs a written list of side effects (entity-id counter, level and random access, texture registration) |
| X21 | W10(1) as a new idea | Vanilla 26.3 already does it (`ClientChunkCache:251, 292-305`; `SectionOcclusionGraph:276-282`). Cut |
| X22 | "JIT done" | Static only. Travel still compiles (C2 about 0.2 cores) |
| X23 | Stage order (GOAL) vs this plan | Stage 1 is open (§0). Stage-2 work runs in parallel and is reported separately |

## 4. Ranked work items

Items that are already implemented rank on A/B cost alone.

| Rank | ID | Item | Status | Main metric gain | Conf. | Effort | Exact? | Gate | Round |
|---|---|---|---|---|---|---|---|---|---|
| pre | W0a | Frame trace | Core done (R5-B). Remaining: probes for `animateTick`, `tickEntities`, packets by type, the light split, a heap-create flag and the core id | Decides W3, W6, W9, W15, W16, W23 | high | S (bench only) | diag | overhead 2+2 | 1 |
| pre | W0b | Chunk counters, `storm` / `join` modes, compile ns per surface column, rebuilds per distinct section | — | Creates the chunk metrics | high | S–M | diag | overhead 2+2 | 1 |
| pre | W0c | Startup timeline from JVM start, profiled reload, "AOT mapped" flag | Config block done (R5-B) | Enables W4, W7, W8, W17 | high | S | diag | stamps add up | 1 |
| pre | W0d | Scenarios: RD32, `speed=33`, multiplayer travel (dedicated or LAN), mob scene, BE-heavy base | — | Makes the chunk, entity and multiplayer items measurable | high | S–M | diag | — | X0 / 1 |
| pre | W0e | Soak and memory (RSS, GPU allocated, heap) | — | Stage-1 bar; guards memory for R5-E, W9a, W14 | high | S–M | diag | — | 1 |
| 1 | W2 | Late throttle | Done (R5-A) | Static 1% low 397 → 519–579 [Mod]; travel 282 → 360–386 | high | A/B | exact image | TAIL D1 | 0 |
| 2 | W5 | Pregrow heaps and presize rings | Done (R5-E) | Removes 3.3–4.4 ms heap events and ring resizes; travel 1% low 0–2% | high | A/B | exact | trace cause counts | 0 |
| 3 | W3 (+S4c) | `fastAnimateTick`, `fastTextureAnimation` | Done (R5-D) | T −0.08 ms (headless); static 1% low +2–3% before W2, about 0 after | high exact / low gain | A/B | exact (tested) | trace T | 0 |
| 4 | W1 | Skip the vignette when it is an exact no-op | — | Static median +3–12% [Mod]; travel median +1–5%; static 1% low +1.5–5%, also after W2 | med | XS | bit-exact | X0.1 | 1 |
| 5 | W9 | (a) async entity textures; (b) trap-limit flags | (a) done (R5-E); (b) flags | Travel max / 0.1% low (share unknown); 1% low 0–3% | med | A/B / flags | exact | X0.4 + trace | 0 / X1 |
| 6 | W24 | Terrain upload budget | Done (R5-E) | Travel max frame from upload bursts [H]; costs ≤ 2 frames of terrain latency | low-med | A/B | timing only | trace + W0b + temporal | 0 / 2 |
| 7 | W8a | Shader-cache warm-up after builds | — | Cold launch −2.7 s | med | XS | exact | `shaderSalt` | 1 |
| 8 | W4 | JDK 25 AOT cache under ZGC | — | Launch → title −0.45 to −1.0 s; FPS 0 | high works / med size | S–M | exact | X0.3, W0c | 2 |
| 9 | W6 | Thread QoS / real priorities | — | Static 0; travel 1% low 0–10% [H] | low-med | XS–S | exact | X0.2, W0a | 2 |
| 10 | W10 (3)(4) | Copy only the section's own BEs; neighbour-check cache | — | Multiplayer bases 0.1–0.2 ms per region [Mod]; createRegion share down | med | S | exact | W0d BE base | 2 |
| 11 | W11a | Skip identical meshes | — | Upload / callback CPU in proportion to identical rebuilds [H] | med | M | exact | W0b ≥ 15% identical | 2 |
| 12 | W12 | Dense-array mesher with verifier, built on an input type stage 3 can reuse | — | Compile CPU 1.8–2.5x [Mod]; join −0.1 to −0.3 s RD12, −1 to −2 s RD32; stage-3 L0 throughput | med-high | slice 1 d; full L | byte-exact | slice: ≥ 20% compile ns, 0 mismatches | 2 / 3 |
| 13 | W23 | `tickEntities` client fast paths | — | T [H] −0.05 to −0.15 ms; CPU in mob scenes | low-med | S (client-only part) / M | exact | W0a sub-timer | 2 / 3 |
| 14 | W13 | Bucketed queue with a priority class; staging copy outside `copyLock` | — | Drain 0.06–2 s at 5k–RD32 queues; p99.9 | med / med-low | S + S/M | exact (order only) | W0b histograms | 3 |
| 15 | W11b | Light-diff dirtying | — | Rebuilds from streaming light (multiplayer) | med | M | exact | W0b dirty sources | 3 |
| 16 | W14 | JVM flag track | — | Travel 0.1% low / max 0–3% [H] | low | flags | exact | per arm | X1 |
| 17 | W8b | Own SPIR-V→MSL cache + `MTLBinaryArchive` (shared with stage 4) | — | Warm ≤ −0.15 s; stage-4 pack loads | med | M–L | exact | `shaderSalt` | 3 |
| 18 | W7 | Instant UI transitions (opt-in) | — | Waiting time −2.0 s (not compute) | high | S | **not exact** | user approval | 3 |
| 19 | W15 | Worker-pool sizing, leaving room for the stage-3 LOD pool | — | Travel 1% low ±5% vs throughput −0 to −30% | low | S–M | exact | after W6, W0b | 4 |
| 20 | W16 | Parse chunk packets off-thread; stage-3 ingest point | — | T −0.1 to −0.4 ms on chunk ticks [H]; about 0 on the 1% low after W2 | low-med | M–L | exact | W0a packet timers + multiplayer bench | 4 |
| 21 | W17 b–e | Startup backlog | — | −0.05 to −0.7 s each | low-med | S–L | exact (+ registry ids) | W0c | 4 |
| 22 | W18 | Full Leyden (other GC or JDK 26) | — | Launch −1.5 to −2.5 s [Mod]; G1 pause risk | low-med | flags + runtime | exact | ask the user before any download | 4 |
| 23 | W21 | Instanced entity models | — | 0 today | high (no gain now) | L | exact | W0d mob scene: C > 0.8G | 4+ |
| — | T-S3 | TERRAIN S3 translucent head list | — | 0.016–0.03 ms GPU | med | M | exact | r5 GPU profile | backlog |
| — | Opt | Quick Play in the Prism instance | — | About −1 s against menu clicks | high | none | vanilla | offer it to the user | — |

**Cut or moved:**
- W10(1): vanilla already does it.
- W17a: undetectable end to end. It stays only if it is judged on the reload timer.
- W19: R5-E says the clone is required.
- W20: moved to stage 3's meshing decision.
- W22: moot under ZOOM D3.
- W5 v1: does not help.
- W9c: kept only with a list of side effects.

**Expected outcome (not additive):**
- **Static median:** +3–12% from W1. Further median gains come from GPU and backend work (TAIL S3 in R5-C, TERRAIN S3).
- **Static 1% low:** W2 dominates and decides stage 1. W1 adds +1.5–5%. W3 adds about 0 after W2.
- **Travel 1% low:** W2. W5 / W9a / W24 add 0–2% each. W6 adds 0–10% [H].
- **Travel max frame / 0.1% low:** R5-E and W9b remove the identified event classes. Deopt bursts stay open.
- **Chunk loading:** W12, W13, W11a, W10(3). The size is unknown until W0b and W0d run.
- **Startup:** W4 −0.45 to −1.0 s; W8a −2.7 s cold; W17b −0.5 to −0.7 s to title (risky). W7 only with the user's approval and reported apart.

## 5. Item details

Shell variables: `L=/tmp/mc-gpu.lock`, and `J=build/<frozen r5 jar>` (a `cp -p` copy of the r5-int build used by r5's A/B). JVM flags are always written `--jvm=-…`. Each JSON's `config` block is checked for the effective flags.

### W0a. Frame trace extensions
- **Status.** R5-B is done:
  - per-frame rows for packets / tasks / texture tick / tick / `level.update` / extract / render / submit / present;
  - bundle CPU vs wall time;
  - the GPU start/end ring;
  - fused / presented / skipped flags;
  - guard trips;
  - the `config` block;
  - `tools/frame_trace.py`, which partitions each interval into components;
  - toggle `-Dbench.frameTrace=true`.
- **Remaining work.** More `FrameProbe` sites, all inserted by the bench agent, so no `Minecraft.java` edit:
  - `ClientLevel.animateTick`, `tickEntities`;
  - `ClientPacketListener.handleLevelChunkWithLight`, plus other packets by type;
  - `pollLightUpdates` and `runLightUpdates` separately;
  - a per-frame heap-create/destroy flag (from R5-E's counters);
  - texture-load and buffer-resize flags;
  - the core id at bundle start, through a native extension export outside the frozen header (TPIDRRO_EL0 low bits).
- **What it decides.** TAIL C2 (what T is made of) is the first question. Then W6, W9, W15, W16 and W23.
- **Confirm.** Overhead 2+2 (median and average within 2%).
- **Files.**
  - `bench/src/bench/FrameProbe.java`, `BenchAgent.java`, `FrameTrace.java`;
  - `tools/frame_trace.py`;
  - optionally `native/src/mcmetal_frametrace.m`.
- **Same owner as W0c, W0b and W0d** (all of them touch `bench/`).

### W0b. Chunk-pipeline counters and the `storm` / `join` modes ([CB] P3)
- **Per-second counters:**
  - compiles started and finished;
  - compile ns p50/p99 (wall and CPU), plus **ns per surface column**, which stage 3 needs (ZOOM §6.2);
  - queue length, `poll` ns, `copyLock` wait histogram;
  - region-copy ns, `SectionCopy` count, BE copies;
  - uploads and bytes per frame (with R5-E's budget carry-over);
  - visible UNCOMPILED holes;
  - resident MB;
  - **rebuilds per distinct section**, which removes the churn from the amplification figure;
  - identical rebuilds, once W11a exists;
  - an all-air compile count, kept only as a sanity check (expected 0).
- **Modes.**
  - `bench.mode=storm`: every 3 s, mark every visible section dirty.
  - `bench.mode=join`: LOADING → queue empty and no visible UNCOMPILED section for 0.5 s, at RD12 and RD32.
  - Travel also enables `-Dmc.terrain.stats=true` to get `perSecondTerrainQuads` (FPS per million quads). Check that overhead first.
- **Confirm.** Overhead 2+2. Then 5 baseline `join` runs to learn the noise.
- **Files.**
  - `SectionRenderDispatcher.java` (rebase on R5-E);
  - `LevelExtractor.java` (rebase on the zoom patch);
  - new `ChunkStats.java`, `bench/.../ChunkStatsHook.java`, `Bench.java`.

### W0c. Startup timeline ([SU] P1)
- Stamps relative to `RuntimeMXBean.getStartTime()`: first frame, `reloadDone`, overlay gone, `openWorld`, server ready, spawn, level ready, all built.
- Vanilla's `GameLoadTimesEvent` and `Bootstrap.bootstrapDuration`.
- `tools/startup-log4j2.xml` turns on DEBUG for `ReloadableResourceManager`, which switches to `ProfiledReloadInstance` (prepare and apply ms per listener).
- **"AOT cache mapped: yes/no"** added to the R5-B config block (`-Xlog:aot` output, or the CDS status read through `ManagementFactory`).
- **Confirm.** The stamps add up to `secondsToWorldReady`, over 3 static and 3 travel runs.

### W0d. Scenario coverage
**Zero-code runs (X0.5):**
- static and travel with `--opt renderDistance:32`;
- travel with `--bench-prop speed=33` (elytra-like);
- check `jdk.ZAllocationStall` and the travel 0.1% low.

**Harness work:**
- **Multiplayer travel.** Two options:
  - A headless dedicated server started from the *vanilla* client jar (`java -cp <vanilla jar + libraries> net.minecraft.server.Main --nogui`, pregenerated world, localhost). The client travels and runs `join` against it. **Needs the user's OK for `eula=true`.**
  - The `tools/lan-smoke.sh` host. It is a second client, so the GPU is shared; use it only for correctness.
- **Mob scene:** a pinned scene with 300–500 mobs (moved here from W21).
- **BE-heavy base:** chests, signs, banners; about 300 BEs per chunk.

**Guard metric:** the LAN host's `minecraft.ServerTickTime` for scheduling and JVM items.

### W0e. Soak and memory (stage-1 bar)
- `tools/soak.sh`: 30 min of flying, chunk loading, resizing, fullscreen toggles and a LAN join.
- Log every 10 s: RSS, the Metal allocated size, heap after GC, the ZGC cycle count, and the R5-E spare count and prefetch MB.
- **Pass:** no crash, and no upward trend after 10 minutes.

### W1. Skip the HUD vignette when it is an exact no-op ([FC] P1)
- **Mechanism.**
  - `Hud.extractVignette` (vanilla `Hud.java:1075-1113`) blends a full-screen quad of 3600x2338 = 8.4 Mpx with `ZERO, ONE_MINUS_SRC_COLOR, ZERO, ONE`.
  - `vignetteBrightness` starts at 1.0 (`:154`) and decays 1% per tick toward 1 − levelBrightness (`:1068-1073`). The target is 0 at raw brightness 15.
  - `as8BitChannel` = floor(x·255) (`ARGB.java:343-345`), so the colour is 0 after 552 ticks (27.6 s).
  - With RGB 0 the output is dst·1 for RGB and dst for alpha, so the image is bit-identical.
  - Fix: return early when the border warning strength is 0 and `as8BitChannel(clamp(vb)) == 0`.
  - The `options.vignette()` check (`:271`) and the world-border path are unchanged.
- **Gain.**
  - Static median +3–12% if the quad costs 0.04–0.13 ms [Mod]. It applies to about 85% of static frames: measurement starts ≥ 23 s after world-ready (`Bench.java:183-198`).
  - Travel median +1–5%: about two thirds of travel frames, since measurement starts ≥ 18 s after world-ready.
  - Static 1% low +1.5–5%. After W2 the gain still holds, because the slowest frames are heavy-direction GPU frames.
  - 0 at night, in caves and near the world border.
- **Exact / multiplayer.** HUD only, bit-exact.
- **Tests.**
  - a unit test at the quantization boundary;
  - a forced vb = 0 frame with the toggle on and off, expecting 0 px difference;
  - 18-scene parity and the zoom sequences (the spyglass overlay is also in `Hud`);
  - a Vulkan and OpenGL smoke run.
- **Confirm (X0.1, zero code).**
  - 2+2 static runs with `--jvm=-Dmcmetal.skipPipelines=vignette --jvm=-Dbench.frameTrace=true`, against the same without the skip.
  - Compare the mean `gpuBusyNs` per 30°-yaw bin over frames after vb = 0. That resolves about 0.02 ms.
  - Ship if GPU busy drops in every bin and the median does not regress.
- **Toggle.** `-Dmc.hud.vignetteSkipNoop` (default true after the A/B).
- **Files.** New patched `client/src/net/minecraft/client/gui/Hud.java`; `tools/HudVignetteTest.java`. Coordinate with the ZOOM D7 scope-mask work, which will also patch `Hud`.

### W2. Late throttle (R5-A; owned by TAIL)
- **Status.** Implemented as designed. The waits move to the throttle point and to lazy guards.
- **Deliberately not done:** calling `advanceGameTime` again after the wait. The reasons are in R5-A: the frame's delta would be split, ticks deferred past a 50 ms boundary would jump back on screen and shift client tick timing, and the zoomseq lockstep would break.
- **Decision.** Pending the r5 A/B (gate D1, then 3+3 static and 4+4 travel). This decides stage 1.
- **Interaction.** Afterwards T is hidden on heavy-direction frames with 1.1–1.4 ms of margin, so T-reduction items only buy CPU headroom.

### W3. Exact `animateTick` and texture-animation fast paths (R5-D)
- **Status.** `-Dmc.fastAnimateTick` (ambient-particle skip proved per dimension, a per-biome-holder cache, section-direct lookups, batched LCG with one CAS) and `-Dmc.fastTextureAnimation` (Animate draws grouped by pipeline).
- **Tests.** 41,943 particle and sound calls with 0 mismatches. Every mip-level digest identical over 320 ticks.
- **Gain [M headless].** −0.064 ms (animateTick) plus −0.015 ms (texture animation) per tick.
- **Confirm.** Trace: tick-bundle CPU −0.06 to −0.08 ms beyond noise, with no scorecard regression (acceptance rule in §7.6).

### W4. JDK 25 AOT cache under ZGC ([JVM] P1, [SU] P2)
- **Mechanism.**
  - Train with `-XX:AOTCacheOutput`: title → join → 30 s travel → quit. The exit path calls `System.exit`, so the dump runs.
  - Under ZGC the cache holds class metadata only.
  - Prism: a wrapper copies `NewLaunch.jar` to a stable path (AppTranslocation changes the path per session) and keys the cache by the client jar's sha1 plus GC and COH.
  - Bench: train with the same `-javaagent:bench.jar`.
  - Retrain whenever the client jar **or any JVM flag** changes.
  - The config block records whether the cache was mapped. W14, W6 and W9b A/Bs run either without AOT or with one cache per arm.
- **Gain.** Measured on Bootstrap: `bootStrap` 1256–1425 → 915–994 ms, process −0.45 s. Full launch −0.45 to −1.0 s [Mod]. FPS 0.
- **Exact / multiplayer.** Same bytecode.
- **Confirm.**
  - X0.3: training, then 3+3 static with `--jvm=-XX:AOTCache=… --jvm=-Xlog:aot=warning`.
  - After W0c: 5+5 runs on JVM start → title.
  - Kill if the gain is under 0.2 s.
- **Files.** `tools/launch.py`, new `tools/aot-train.sh`, and the Prism instance's `aot/wrap.sh` and `instance.cfg`.

### W5. Uber heaps off the frame (R5-E)
- **Status.** `-Dmc.terrain.pregrow`:
  - spare heaps are made resident with a 4-byte blit on a side command queue, on the `MetalHeapReserve` worker;
  - a fully free heap is kept while free space is ≤ 3x the low-water mark;
  - `DynamicGpuData.presizeChunkSections(rd)` presizes the rings.
- **Headless results.** In-frame heap creations fell from 49–67 to 5–11. Placement, callbacks and return values are identical to vanilla.
- **Decision.** Pending the r5 A/B. Judge on the trace's per-frame heap flags (W0a) and slow-frame cause counts, not on the raw max frame.

### W6. Thread QoS and real priorities ([FC] P3, [JVM] P3)
- **Step A (X0.2).** `--jvm=-XX:ThreadPriorityPolicy=1`, 2+2 static and 2+2 travel.
- **Step B.** Set QoS through FFM:
  - render thread USER_INTERACTIVE at device init;
  - Worker-Main USER_INITIATED;
  - IO-Worker, `MetalHeapReserve` and the prefetch daemon UTILITY.

  Log `pthread_get_qos_class_np` once.
- **Gain.** Static about 0 (842 of 842 render-thread samples on S-cores, r3b MST). Travel 0–10% [H], only if W0a shows tick wall time ≥ 1.2x tick CPU time, or E-core placement.
- **Guards.** W0b `join`, and the LAN-host `ServerTickTime`.
- **Toggles.** `-Dmcmetal.renderQos`, `-Dmc.workerQos`. The name `MCMTL_RENDER_QOS` is retired.
- **Files.** New `ThreadQos.java`, `MetalDevice.java` (1 line), patched `Util.java`.

### W7. Instant UI transitions (opt-in; [SU] P3)
- **Changes:**
  - `LoadingOverlay` drops the 2.0 s fade (`:149-150`) and the fade-in hold (`:173`);
  - `TitleScreen` is built with `fading=false`;
  - the `LevelLoadTracker` delay goes from 500 to 0 ms.
- **Gain.** −2.0 s of waiting, not computation.
- **Exactness.** Not exact. It needs the GOAL (c) side-by-side (made from the game's own frame dump) and the user's approval. Default off. Always reported apart.
- **Status (2026-10-05).** Approved by the user (docs/GOAL.md, "User decisions", afternoon): shipped default **on**,
  `-Dmc.ui.instantTransitions=false` restores every vanilla wait. Server / world resource-pack reloads keep vanilla's hold
  (their callback reports the pack to the server). Implementation, guards and tests: CONTRACT 16.W7; in-game time saved per
  path: results/W7-UI-TRANSITIONS.md.

### W8. Shader caches ([SU] P5)
- **(a) Process.** After every build that changes MSL, and after every Prism update, run `tools/i5-pipeline-test.sh` under the lock, with the same `java` binary as Prism.
- **(b) Code.**
  - A SPIR-V→MSL cache keyed by SHA-256 + options + bindings.
  - An `MTLBinaryArchive`, keyed by OS build and GPU family.
  - Native work goes through extension exports outside the ABI-2 header (R5 pattern).
  - Design it as the shared cache for stage 4's shaderpack translator.
- **Gain.** Cold cache 2854 ms → about 150 ms. Warm ≤ −0.15 s.
- **Confirm.** `-Dmcmetal.shaderSalt` cold vs warm, read from W0c's ShaderManager listener time.

### W9. First-use hitches ([FC] P4, [SU] P11, [JVM] P6)
- **(a) Done (R5-E).** `-Dmc.asyncEntityTextures`: decodes the 743 PNGs (14.3 MB) after each reload. Guard world-join s (W0c) with it on and off, because the decode overlaps the join.
- **(b) Flags.** `--jvm=-XX:PerBytecodeTrapLimit=1 --jvm=-XX:PerMethodTrapLimit=20`, 4+4 travel, but only if X0.4 shows deopt bursts coinciding with frames over 6 ms. The median must not regress: C2 gives up speculation sooner.
- **(c) Renderer warm-up.** Not planned. R5-E judged it not exact-safe. It is reconsidered only with a written list of side effects (entity-id counter, level and random access, texture registration, `ItemModelResolver` caches) and after (b).
- **X0.4.** One travel run with JFR from launch (`jdk.Deoptimization`, `jdk.Compilation` 0 ms, `jdk.FileRead` 0 ms) plus the trace with `-Dbench.frameTrace.jfrSlowMs=4`. Plus one 10-min soak with `-Xlog:gc,codecache=info` (for W14b).

### W10. Region snapshot: per-section BE copy and neighbour cache ([CB] P6)
- **(3)** `SectionCopy.java:29` copies the whole chunk's BE map once per `SectionCopy`, i.e. up to 3x per chunk per region build. Copy only that section's BEs instead. Exact, because `RenderSectionRegion` routes by section.
- **(4)** Fold in [FC] P8's neighbour-check cache (`LevelExtractor`, keyed by the chunk-load epoch).
- **(2)** The cross-frame `SectionCopy` cache stays in round 4, with W16's owner (both patch `LevelChunk`).
- **Gain.** About 0.1–0.2 ms per region in BE-heavy multiplayer bases [Mod], including block edits. Travel 0–1%.
- **Confirm.** W0d BE base plus W0b region-copy ns, then 4+4 travel.
- **Files.** New patched `SectionCopy.java`, `RenderRegionCache.java`; `LevelExtractor.java` (rebase on the zoom patch).

### W11. Cut rebuild waste ([CB] P2)
- **(a) Identical-mesh skip.**
  - Compare per layer: length, two 64-bit hashes, VisibilitySet, BE list, **and the S1 bucket table and S5/S6 metadata (`TerrainMeshInfo`)**.
  - On a match, keep the existing mesh. Translucent layers re-sort indices only.
  - Fade-in is unaffected: `updateUploadTime` sets the time only when it is 0 (`SectionRenderDispatcher.java:318-321`).
- **(b) Light-diff dirtying.** Dirty a section only when a cell in its 18³ shell changed (`LayerLightSectionStorage`, `ClientPacketListener`; client instance only).
- **(c) Coalescing.** Opt-in; not exact in time.
- **Gate.** Rebuilds per distinct section (W0b), not upload/resident. Drop the item if fewer than 15% of rebuilds are identical and light dirties fall by less than 25%.
- **Stage 5.** Identical skips also avoid acceleration-structure rebuilds per section.
- **Checks.** Temporal check for (b).

### W12. Dense-array mesher with dual-compile verifier ([CB] P4)
- **Mechanism.**
  - Slice 1 (about 1 day): a 20×20 tint window per block layer with sliding integer sums; an 18³ packed-light memo over a pinned snapshot of the `DataLayer` references; the verifier.
  - Full (3–5 days): a 20³ state decode, per-state tables, `BakedQuad` shape precompute, all-air in O(1), facing during emission.
- **New requirements:**
  - **The input is an abstraction** (a dense snapshot from a `RenderSectionRegion` *or* from stage 3's LOD cache), because L0 LOD = vanilla meshing on cached data (ZOOM D2).
  - **Emission goes through a vertex sink**, so stage 4's extended attributes (`mc_Entity`, `mc_midTexCoord`, `at_tangent`) and S5 compact vertices can plug in.
  - **The verifier gives vanilla's compiler the same pinned light through a wrapper.** That is the only way to compare on identical inputs: vanilla reads live light, which is racy.
- **Gain [Mod].**
  - Compile CPU 1.8–2.5x.
  - Join −0.1 to −0.3 s at RD12, −1 to −2 s at RD32.
  - Block-edit synchronous rebuilds halve.
  - Stage 3's refinement budget (3.5k–5.4k columns in 300 ms) depends on it.
  - FPS about 0 today.
- **Exactness.** Byte-exact against the verifier. Release builds verify 1 in 256 sections and revert to vanilla on the first mismatch.
- **Confirm.** Slice: `storm` 2+2 with `verify=1`, 0 mismatches and ≥ 20% lower compile-ns p50. Full: `join` at RD12 and RD32, 5+5.
- **Files.** `BlockTintCache.java`, `BlockModelLighter.java`, `ClientLevel.java` (rebase on R5-D), `SectionCompiler.java`; full: `ModelBlockRenderer`, `FluidRenderer`, `BakedQuad`, `TerrainMeshInfo`.

### W13. O(1) queue and staging outside `copyLock` ([CB] P7 + P8)
- **Queue.** Today's queue is an O(n) scan plus `ArrayList.remove` (`SectionTaskDynamicQueue.java:18-53`). Replace it with buckets by Chebyshev distance, holding initial and recompile FIFOs, the quota rule, and **a lower priority class reserved for stage-3 prefetch** (ZOOM §6.3).
- **Staging (a).** Bump-reserve, copy outside the lock, publish under it. Rebase on R5-E's `StagingBuffer`.
- **Staging (b), optional.** Workers write into the Shared heaps directly, with freed ranges quarantined until `completedSeq`. It must keep R5-A's invariant and work with R5-E's budget.
- **Gates.**
  - Queue: total `poll` time > 100 ms, or render-thread add waits > 0.5 ms per frame.
  - Staging (a): `copyLock` wait p99.9 > 50 µs.

### W14. JVM flag track ([JVM] P4, P5, P7, [SU] P12)
All arms run without AOT, or with a cache trained for that arm.

| Arm | Run | Adopt if |
|---|---|---|
| a. Heap (X1) | Travel 4+4 for each of: A = today; B = `-Xms4G`; C = `-XX:-AlwaysPreTouch`. All with `-Xlog:gc*`. Also RD32 travel: count `jdk.ZAllocationStall` | B: tail better beyond noise. C: tail equal to A. Any allocation stall at RD32: raise `-Xmx` (an instance setting the user approves) |
| b. Code cache | X0.4 soak | A "CodeCache GC Threshold" Major inside a 30 s window → `-XX:ReservedCodeCacheSize=512m` 4+4 |
| c. Trap limits | W9b | — |
| d. Inlining | `jdk.CompilerInlining` on static + travel | Hits in hot callers → `-XX:InlineSmallCode=4500`, judged on trace T |
| f. Parity | Bench vs Prism args (`StackShadowPages`) | Align them, or record the difference in the config block |

Guard for every arm: the LAN-host `ServerTickTime`.

### W15. Worker pools ([CB] P9, [FC] P6, [JVM] P8)
- Sweep `-Dmax.bg.threads=3/6/9` (`Util.java:235`) in `storm` and `join`.
- Optionally a dedicated compile pool, sized with room for the future stage-3 LOD and prefetch pool.
- Adopt only if travel 1% low improves and join-to-all-built does not get worse.

### W16. Parse chunk packets off the render thread ([FC] P5)
- **Mechanism.** At Netty decode, build the `LevelChunkSection[]` and light data. In-order handling then swaps them in. Vanilla's path stays as the fallback (`LevelChunk.replaceWithPacketData` :523-543).
- **Stage 3.** This is also **stage 3's multiplayer ingest point** (ZOOM §5), so one owner covers W16, W10(2) and stage-3 ingest.
- **Server-observable effect.** `ChunkBatchReceived` then reports a faster client, so the server sends larger batches. That is within the range of a faster vanilla machine. Never defer or budget packets.
- **Gate.** W0a packet timers after W2, plus the W0d multiplayer bench: `LevelChunkWithLight` ≥ 0.2 ms per tick on average, or > 1 ms on p99 tick frames.

### W17. Startup backlog ([SU] P6–P10)
- **(a) Eager reload tasks.** Kept only if judged on the profiled reload's `Total blocking time`. End to end it is undetectable (−20 to −60 ms against σ 0.18 s).
- **(b) Bootstrap overlap.** −0.5 to −0.7 s to title.
  - Risk: class-initialization deadlocks.
  - Gate: hashes of `BuiltInRegistries` and `BLOCK_STATE_REGISTRY` must match. Registry raw ids are not synced from the server, so a mismatch breaks multiplayer.
  - Also a 30-launch soak and LAN.
- **(c) Speculative data-pack stem.** −0.3 s in singleplayer.
- **(d) Known-pack pre-parse.** Only if ≥ 100 ms is measured.
- **(e) Atlas cache.** Only if one listener of ≥ 0.4 s is on the critical path.

### W18. Full Leyden ([JVM] P2)
- **Facts.** With G1, AOT links about 16.6k classes, archives lambdas and records profiles. Bootstrap −0.7 s, JIT CPU 2.9 → 1.3 s [M].
- **Risk.** G1 pauses of 5–20 ms [Mod].
- **Options.** JDK 26 (JEP 516) may keep ZGC [H]. A new runtime is a download, so **ask the user first**.
- **Kill.** Any GC pause > 2 ms inside the measurement, or travel 0.1% low / max frame more than 10% worse.

### W21. Instanced entity models ([FC] P8)
Only if the W0d mob scene shows C > 0.8G. Zoom-aware entity distance (ZOOM §10) raises entity counts and may change this.

### W23. `tickEntities` client fast paths (R5-D (d); new)
- **Mechanism.** In order:
  1. `BubbleColumnAmbientSoundHandler`: replace the stream with a loop that stops at the first match. Client-only class; ship it alone.
  2. On the client branch of `EntityFluidInteraction.update`: prefetch the fluid states section-direct, keeping vanilla's x→y→z order and the same `accumulate` sequence.
  3. Reuse the fluid result while a client block-change epoch is unchanged.
  4. Cut allocation churn in `Vec3` / `AABB` / iterators.
- **Multiplayer.** (2)–(4) live in classes shared with the integrated server. They must be strictly behind `level().isClientSide()`: a LAN host serves guests from the same code, so any server-side leak is observable to them.
- **Tests.** A seeded client entity simulation comparing positions, velocities and fluid trackers bitwise over N ticks, plus LAN smoke.
- **Gain [H].** T −0.05 to −0.15 ms; more in mob scenes. 1% low about 0 after W2.
- **Gate.** W0a's `tickEntities` sub-timer ≥ 0.15 ms per tick in the bench, or ≥ 0.3 ms in the mob scene.

### W24. Terrain upload budget (R5-E; new in this plan)
- **Mechanism.** `-Dmc.terrain.uploadBudgetKB=N`, `uploadMaxDelayFrames=2`.
  - A delayed section can appear up to 2 frames later: 4 ms at 500 fps, 33 ms at 60 fps.
  - Uploads flush while loading and when staging is ≥ 50% full.
  - A mesh for a node the section has left is released, where vanilla could show it for one frame.
- **Exactness.** Timing only. It needs GOAL (b)'s temporal check, a travel capture with the zoom sequences, and the W0b guards (join-to-all-built, `storm` drain, holes).
- **Gate.** Trace slow frames tagged as upload bursts must go to about 0, with no chunk-metric regression.

## 6. Rounds

### Round 0 (in progress; another workflow owns the GPU)
- **What runs.** The r5 A/B as written in R5-A to R5-E: W2, TAIL S3/S5, W3 and S4c (via the trace), W5, W9a, W24.
- **What it produces:**
  - the stage-1 1% low decision;
  - the first r5 frame traces, which give TAIL C2's answer (T wall vs CPU, T's components).

### X0: zero-code runs (appended to the same GPU session, same frozen r5 jar, `--jvm=` form)

| Exp | Run | Decides |
|---|---|---|
| X0.0 | JFR from launch (static and travel) plus one MST on the r5 jar | Replaces the r3 fact base |
| X0.1 | Static 2+2, `--jvm=-Dmcmetal.skipPipelines=vignette` with the trace on, against none | W1 size (per-direction `gpuBusyNs`) |
| X0.2 | Static and travel 2+2, `--jvm=-XX:ThreadPriorityPolicy=1` | W6 |
| X0.3 | AOT training, then static 3+3 `--jvm=-XX:AOTCache=…` | W4 |
| X0.4 | JFR travel from launch, plus a 10-min `-Xlog:gc,codecache=info` soak | W9b, W14b |
| X0.5 | RD32 static and travel; travel `--bench-prop speed=33`; count `ZAllocationStall` | Whether W12, W13, W16 and W14a matter |
| X0.6 | Scene runner on Vulkan and OpenGL with the r5 jar | Fallback-backend regressions |

### Round 1: prerequisites plus the cheap exact win

| Item | Files | Notes |
|---|---|---|
| W0c → W0a-ext → W0b → W0d, in that merge order (one bench owner) | `bench/src/bench/*`, `tools/frame_trace.py`, `tools/startup-log4j2.xml`; W0b: `SectionRenderDispatcher.java`, `LevelExtractor.java`, new `ChunkStats.java` | Rebase on r5-int and on the zoom patch |
| W0e | new `tools/soak.sh` | Stage-1 bar |
| W1 | new `client/gui/Hud.java`, test | Coordinate with ZOOM D7 |
| W8a | build / instance-update step | Run under the lock |

### Round 2: gated by round-0 and round-1 data

| Item | Files | Gate |
|---|---|---|
| W4 | `launch.py`, `aot-train.sh`, Prism `wrap.sh` / `instance.cfg` | X0.3, W0c |
| W6 | `ThreadQos.java`, `MetalDevice.java` (1 line), `Util.java` | X0.2, W0a |
| W10 (3)(4) | `SectionCopy.java`, `RenderRegionCache.java`, `LevelExtractor.java` | W0d BE base |
| W11a | `SectionRenderDispatcher.java`, `CompiledSectionMesh.java` | W0b ≥ 15% identical |
| W12 slice | `BlockTintCache.java`, `BlockModelLighter.java`, `ClientLevel.java`, `SectionCompiler.java` | `storm` ≥ 20%, 0 mismatches |
| W23 (1) | `BubbleColumnAmbientSoundHandler.java` | W0a sub-timer |
| W24 decision | — | Round-0 trace plus W0b |

Only W11a and W0b share a file (`SectionRenderDispatcher`): W0b merges first.

### Round 3

| Item | Gate |
|---|---|
| W12 full | slice passed |
| W13 (one owner) | W0b histograms |
| W11b | dirty-source counters |
| W23 (2)–(4) | sub-timer and mob scene |
| W8b | `shaderSalt` data |
| W7 | user approval |
| W14 adoption | X1 results |

### X1: flag track (parallel)
W14a (12 travel runs plus RD32), W14b, W9b, the W15 sweep (needs W0b), W14d.

### Round 4 onward
W10(2) + W16 (with stage-3 ingest), W15 pool, W17 b–e, W18 (ask first), W21, T-S3.

**Handed to stage 3:** W20 (native NEON mesher) against ZOOM's GPU L0 meshing, W12's input interface, W13's priority class, and W16's ingest point.

## 7. Measurement protocol

Noise:
- static median pairs up to 5.6%;
- static 1% low 4–7%;
- travel 1% low about 4%;
- travel max frame 9.8–21.2 ms (tr3-off) and 11.7–37.7 ms (tr3-exact) between two runs of one configuration;
- startup σ 0.18 s.

1. **Hygiene.**
   - Frozen `cp -p` jars, interleaved ABBA, quiet and overlay gates.
   - No benchmarks while builds run.
   - Close notification windows.
   - `--jvm=-…` only, and each JSON's `config` block (R5-B) is checked for the effective flags and for the AOT-mapped state.
2. **Static.** 2+2 resolves about 6%; 4+4 resolves 3–6%. For GPU-cost items use the trace's per-direction `gpuBusyNs`.
3. **Travel.**
   - At least 4+4.
   - Report FPS per million quads (`mc.terrain.stats`) and end-of-run resident MB.
   - Items that change fill speed are judged on the normalized FPS.
4. **Maximum frame / 0.1% low.** Never judged on the raw maximum with ≤ 8 runs. Use `tools/frame_trace.py`'s cause attribution plus W0a's event flags.
5. **Startup.** 5+5 resolves about 0.25 s. Use W0c component timers for smaller effects. Compute wins and waiting removals (W7) are never summed.
6. **Items that shrink T** (their 1% low effect is ≤ 3% after W2): proposed acceptance is that the trace's tick-bundle CPU, or the relevant sub-timer, drops beyond noise and no scorecard metric regresses. **The lead or the user must agree** that this counts as "the harness shows a gain".
7. **Chunk loading.** W0b `join` (RD12, RD32) and `storm` drain, with 5 baseline runs first. The multiplayer variant runs on the W0d server.
8. **Shipping requirements:**
   - unit tests;
   - 18-scene parity on `run/scenes-pinned-r4`, plus the zoom sequences;
   - a Vulkan and OpenGL smoke run;
   - the temporal check for items that change timing;
   - 0 verifier mismatches for mesher items;
   - `tools/lan-smoke.sh` (all three pairings) plus `tools/soak.sh` for release candidates;
   - a LAN-host `ServerTickTime` guard for scheduling and JVM items;
   - a CONTRACT §16.x entry and a `results/ROUNDn.md` row.

## 8. The continuing loop (after round 3)

1. Re-profile on the newest frozen jar: JFR from launch, an MST, the trace, W0b, W0c, the W0d scenarios and the multiplayer bench.
2. Update §2 and re-rank by measured cost × share ÷ effort.
3. Take the top 4–6 items whose files do not overlap.
4. Implement each behind a toggle, with tests and notes.
5. Verify: parity including zoom, the fallback backends, LAN, soak, every scorecard.
6. Keep only measured wins. Update the "26.3 Metal" Prism instance with rollback, including the AOT retrain.
7. Repeat until the user stops the loop.

Expected direction:
- After W1, the median is GPU and backend work.
- Game-side work keeps paying on T only once heavy-direction G is below about 1.05 ms. It keeps paying on travel events, chunk metrics and startup.
- Stage 3 turns W12, W13 and W16 into frame-rate and latency items.

## 9. Rejected or deferred

| Idea | Why |
|---|---|
| W10(1) all-air shortcut | Vanilla 26.3 already does it (`SectionOcclusionGraph:276-282`, `ClientChunkCache:251, 292-305`) |
| W5 v1 (render-thread heap creation after submit) | The stall happens on the GPU's first use (3.3–4.4 ms), not at creation (R5-E) |
| W19 COW light map | The clone is required for worker safety; about 40–80 µs every 8–16 frames; 5–6 shared classes (R5-E §4) |
| W20 native NEON mesher in stage 2 | Moved to stage 3's choice between CPU-native and GPU meshing (ZOOM §6.2) |
| W22 Vector API frustum | 0.02–0.03 ms; LOD selection is on the GPU (ZOOM D3); a startup warning at every launch |
| W9c renderer warm-up | Not exact-safe per R5-E unless a list of side effects proves otherwise |
| W17a judged end to end | Below detection; only with the reload timer |
| `/tick rate 40` | The client clamps to ≥ 50 ms per tick |
| More FFM critical downcalls | `timelineWait` must stay non-critical |
| Graal, large pages, THP | Not available |
| `TieredStopAtLevel=1` | About 98% of the render path runs in C2 code |
| Time-budgeting packets | Changes ChunkBatch semantics |
| Unifont speedup, client jar first on the classpath, standalone CDS | ≤ 55 ms, or no difference |
| Trimming the vignette quad | Only 10% of its texels are exactly zero |
| Deleting system shader caches | Outside the project; use `shaderSalt` |
| HiZ occlusion | Costs more bandwidth than it saves on this TBDR GPU at 8 Mpx |
| Non-exact light coalescing as default | Opt-in only (W11c) |
| IO-Worker `UnixException`s (3,016 per 25 s in r3-travel) | Integrated-server chunk saving (`RegionFile.write`); negligible cost |

## 10. Open questions

1. **TAIL C2: what is T made of?** Answered by the round-0 traces plus W0a's extensions. This decides whether further T items (W23, W16) are worth building.
2. **The vignette's real GPU cost.** Answered by X0.1's `gpuBusyNs`.
3. **What decides on the round-5 items.** The r5 A/B results: W2 (stage 1), W5, W24, W9a.
4. **The share of identical rebuilds per distinct section, and BE-copy cost in bases.** Answered by W0b and W0d.
5. **Do RD32 and fast travel expose the meshing, queue and packet path?** Answered by X0.5. Allocation stalls are checked in the same runs.
6. **How much does AOT save on the main thread?** Answered by W0c. Does the cache stay mapped across flag arms? Answered by the config block.
7. **Do first-use texture loads or deopt bursts set the travel maximum frames?** Answered by X0.4 plus the trace.
8. **User decisions:**
   - the EULA for the dedicated-server bench (W0d);
   - W7;
   - the JDK 26 download (W18);
   - Quick Play;
   - the real play settings (render distance, servers);
   - whether trace-based acceptance (§7.6) counts as a harness gain;
   - acknowledging that stage 1 is still open (§0).

Files referenced: `.claude/worktrees/r5-int` (branch `r5-int`, 645116f; CONTRACT §16.R5-A to §16.R5-E); `src/vanilla/net/minecraft/client/renderer/SectionOcclusionGraph.java:276-282`; `src/vanilla/net/minecraft/client/multiplayer/ClientChunkCache.java:251,292-305`; `src/vanilla/net/minecraft/client/renderer/chunk/SectionCopy.java:29`; `src/vanilla/net/minecraft/client/gui/Hud.java:154,271,1068-1113`; `src/vanilla/net/minecraft/util/ARGB.java:343-345`; `bench/src/bench/Bench.java:63,183-198,227`; `results/M2-ROUND1.md`; `results/raw/attr-gui.json`; `results/raw/r3-travel.jfr`. My scratch notes are in `<temp>` and `.../scratchpad/crit-r5contract.md`.
