#!/usr/bin/env python3
"""Headless test of tools/frame_trace.py on synthetic bench results (CONTRACT.md 16.R5-B). No game, no GPU.

1. The column list matches bench/src/bench/FrameTrace.java COLUMNS (so the analyzer reads what the bench writes).
2. A Metal-shaped run built from the TAIL-ANALYSIS pipeline model: GPU cadence G = 1.5 ms, a 20 Hz tick bundle of
   T = 1.1 ms exposed after the throttle (tick frame G + T, next frame G - T), plus injected events with known causes:
   5 GPU-bound frames (throttle wait), 5 drawable waits, 3 native-submit hitches, 1 Java render hitch, 1 GC hitch
   (unattributed "other", with the GPU starving after it). Checks the attribution of the slowest 1% / 0.1%, the tick
   share, CPU p99 vs GPU-cadence p99, present cadence, starvation flags, the config summary and the CSV / JSON outputs.
3. A vanilla-shaped run (no Metal columns): the whole submit() call is the "submit" component; and a late-throttle-shaped
   run (the throttle wait inside the tick bundle): the wait counts as "gpu", the bundle's CPU work as "tick", nothing twice.
4. A result without a frameTrace block is rejected with a message (exit 1).
5. (r5-int review fixes) Metal time nested in wall-time components is counted once: a late-throttle guard trip, a
   flush commit and a drain during rendering, a drain and a commit inside the tick bundle; the C2 wall-vs-CPU summary
   excludes the throttle wait inside the bundle; GPU starvation is flagged from the idle gap even when an early commit
   started the GPU before submit returned.
"""
import io
import json
import os
import random
import re
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import frame_trace  # noqa: E402

FAIL = []


def check(what, ok):
    if not ok:
        FAIL.append(what)
        print("FAIL", what)


def java_columns():
    src = open(os.path.join(HERE, "..", "bench", "src", "bench", "FrameTrace.java"), encoding="utf-8").read()
    block = re.search(r"static final String\[\] COLUMNS = \{(.*?)\};", src, re.S).group(1)
    return re.findall(r'"([A-Za-z]+)"', block)


COLUMNS = java_columns()
IDX = {c: i for i, c in enumerate(COLUMNS)}
MS = 1_000_000


def row(**values):
    r = [-1] * len(COLUMNS)
    for k, v in values.items():
        r[IDX[k]] = int(v)
    return r


def metal_run(seed=7):
    rnd = random.Random(seed)
    n = 20000
    G = 1.5 * MS
    C = 0.4 * MS           # Java render work before submit
    S = 0.15 * MS          # native submit
    T = 1.1 * MS           # tick bundle
    rows = []
    t = 0
    gpu_end = 0
    seq = 100
    # Injected events (none on a tick frame k % 40 == 0 or right after one).
    hitch = {5010: "gpu", 5011: "gpu", 5012: "gpu", 5013: "gpu", 5014: "gpu",
             7010: "drawable", 7011: "drawable", 7012: "drawable", 7013: "drawable", 7014: "drawable",
             9010: "submit", 9011: "submit", 9012: "submit", 11010: "render", 12010: "gc"}
    prev_tick = False
    for k in range(n):
        tick = k % 40 == 0
        jitter = rnd.uniform(-0.03, 0.03) * MS
        render = C + jitter
        native = S
        drawable = 0
        bundle = T if tick else 0.05 * MS
        wait = G - C - native - 0.05 * MS - 0.02 * MS   # steady state: the throttle absorbs the slack, dt = G
        if prev_tick:
            wait = max(0.0, wait - (T - 0.05 * MS))     # the frame after a tick frame waits less (G - T)
        gpu_busy = G
        other = 0.02 * MS
        cause = hitch.get(k)
        if cause == "gpu":
            wait += 3.0 * MS
            gpu_busy += 3.0 * MS
        elif cause == "drawable":
            drawable = 2.5 * MS
            native += drawable
        elif cause == "submit":
            native += 3.0 * MS
        elif cause == "render":
            render += 4.0 * MS
        elif cause == "gc":
            other += 6.0 * MS
        dt = render + native + wait + bundle + other
        t += dt
        seq += 1
        commit = t - dt + render + native
        # The GPU timeline runs back to back at its own cadence (G, longer for GPU-bound frames), except around the GC
        # hitch, where it went idle before the next work arrived (starved).
        start = gpu_end
        if k in (12010, 12011):
            start = max(commit, gpu_end) + 0.05 * MS
        gpu_end = start + gpu_busy
        rows.append(row(dtNs=dt, tNs=t, view=(k * 36) % 360000, ticksToDo=1 if tick else 0, ticks=1 if tick else 0,
                        bundleNs=bundle, bundleCpuNs=bundle * 0.9, bundleWaitNs=0, packetsNs=0.01 * MS, tasksNs=0.01 * MS,
                        texTickNs=0.2 * MS if tick else 0, tickNs=0.8 * MS if tick else 0, preSubmitNs=render,
                        levelUpdateNs=0.05 * MS, extractNs=0.1 * MS, renderNs=render - 0.2 * MS, submitNs=native + wait,
                        presentNs=0.005 * MS, limiterNs=0, frameCpuNs=dt - wait, gcEvents=1 if cause == "gc" else 0, seq=seq,
                        commits=1, nativeSubmitNs=native, waitNs=wait, drainNs=0.01 * MS, encoders=9 if tick else 4, draws=900,
                        blocksCreated=1 if cause == "gc" else 0, blocksFreed=0, guardTrips=0, resultFlags=1,
                        gpuStartNs=start, gpuEndNs=gpu_end, gpuBusyNs=gpu_busy, cbs=1, cbsDone=1, drawableWaitNs=drawable,
                        presentKind=1 if k % 3 == 0 else 3, fuseFallback=0, commitNs=commit))
        prev_tick = tick
    dts = sorted(r[IDX["dtNs"]] for r in rows)
    p50 = dts[len(dts) // 2]
    p99 = dts[round(0.99 * (len(dts) - 1))]
    return {
        "label": "synthetic-metal", "backend": "Metal", "frames": n, "medianFps": 1e9 / p50, "p1LowFps": 1e9 / p99, "p01LowFps": 0.0,
        "config": {
            "backendClass": "com.mojang.renderpearl.backend.metal.MetalDevice",
            "native": {"fuseMode": 1, "presentFusionEnv": 1, "mailboxActive": 1, "drawableCount": 3, "mailboxMaxQueued": 1, "icbMode": 0,
                       "occlusionMode": 0, "m2Flags": 63, "profilePasses": 0},
            "measured": {"fusedShare": 0.3333, "presentedShare": 0.3333, "skippedShare": 0.6667, "mailboxShare": 1.0,
                         "drawableWaitMsPerFrame": 0.0006, "fusionFallbacks": {"no-drawable": 13333}},
            "toggles": {"TerrainShaderVariants": {"specialize": True}, "TerrainToggles": {"COMPACT_VERTICES": False, "cutoutFrontToBack": True,
                                                                                         "sortCutoutSections": True}},
            "properties": {"mcmetal.lateThrottle": "true"}, "env": {"MCMTL_PRESENT_FUSION": "1"},
            "jvm": {"version": "25", "gc": ["ZGC Cycles", "ZGC Pauses"], "maxHeapMB": 4096, "args": ["-Xmx4G", "-XX:+UseZGC"]},
            "game": {"renderDistance": "12", "enableVsync": "false", "framerateLimit": "260"},
        },
        "frameTrace": {"version": 1, "frames": n, "metal": {"status": "enabled, native ring 131072 entries"}, "cpuSampling": True,
                       "guardTripsSource": None, "dropped": 0, "columns": COLUMNS, "rows": rows},
    }


def vanilla_run():
    rows = []
    t = 0
    for k in range(5000):
        tick = k % 40 == 0
        submit = 1.2 * MS + (3.0 * MS if k == 2500 else 0)
        dt = 0.4 * MS + submit + (1.0 * MS if tick else 0.05 * MS)
        t += dt
        rows.append(row(dtNs=dt, tNs=t, view=0, ticksToDo=1 if tick else 0, ticks=1 if tick else 0,
                        bundleNs=1.0 * MS if tick else 0.05 * MS, bundleCpuNs=-1, packetsNs=0, tasksNs=0, texTickNs=0, tickNs=0,
                        preSubmitNs=0.4 * MS, levelUpdateNs=0, extractNs=0, renderNs=0, submitNs=submit, presentNs=0, limiterNs=0,
                        frameCpuNs=-1, gcEvents=0))
    return {"label": "synthetic-vulkan", "backend": "Vulkan", "medianFps": 0, "p1LowFps": 0, "p01LowFps": 0,
            "frameTrace": {"version": 1, "frames": len(rows), "metal": None, "columns": COLUMNS, "rows": rows}}


def late_throttle_run():
    """Late throttle (TAIL-ANALYSIS S1): the frame throttle runs inside the tick bundle, so bundleNs includes it and
    bundleWaitNs / waitNs report it. Tick work is hidden in the slack except its excess."""
    rows = []
    t = 0
    for k in range(4000):
        tick = k % 40 == 0
        work = 1.1 * MS if tick else 0.05 * MS      # CPU work of the bundle
        wait = max(0.0, 0.93 * MS - work)           # throttle wait absorbs it up to the slack
        bundle = work + wait
        dt = 0.4 * MS + 0.15 * MS + bundle + 0.02 * MS
        t += dt
        rows.append(row(dtNs=dt, tNs=t, view=0, ticksToDo=1 if tick else 0, ticks=1 if tick else 0, bundleNs=bundle, bundleCpuNs=work,
                        bundleWaitNs=wait, preSubmitNs=0.4 * MS, submitNs=0.15 * MS, seq=k + 1, commits=1, nativeSubmitNs=0.15 * MS,
                        waitNs=wait, drawableWaitNs=0, gcEvents=0))
    return {"label": "synthetic-late-throttle", "backend": "Metal", "medianFps": 0, "p1LowFps": 0, "p01LowFps": 0,
            "frameTrace": {"version": 1, "frames": len(rows), "metal": {"status": "synthetic"}, "columns": COLUMNS, "rows": rows}}


def nested_metal_run():
    """Metal time nested in wall-time components: on every 50th frame (k % 50 == 7) a late-throttle guard trip waits 1.5 ms
    during rendering, a flush commit takes 0.3 ms of native submit and a drain 0.2 ms, all before submit(); every frame has a
    0.1 ms drain and a 0.05 ms commit inside the tick bundle. Frame 1000's GPU work started 60 us after the previous
    frame's ended (starved) but before its commitNs (an early commit)."""
    rows = []
    t = 0
    gpu_end = 0
    for k in range(3000):
        tick = k % 40 == 0
        trip = k % 50 == 7
        render_own = 0.4 * MS
        pre_wait = 1.5 * MS if trip else 0
        pre_native = 0.3 * MS if trip else 0
        pre_drain = 0.2 * MS if trip else 0
        pre = render_own + pre_wait + pre_native + pre_drain
        submit_native = 0.15 * MS
        submit_wait = 0.6 * MS
        submit_drain = 0.01 * MS
        bundle_own = 1.0 * MS if tick else 0.05 * MS
        bundle_drain = 0.1 * MS
        bundle_native = 0.05 * MS
        bundle = bundle_own + bundle_drain + bundle_native
        dt = pre + submit_native + submit_wait + submit_drain + bundle + 0.02 * MS
        t += dt
        commit = t - dt + pre + submit_native
        start = gpu_end + (60_000 if k == 1000 else 0)
        if k == 1000:
            commit = start + 100_000   # the early commit started the GPU before submit returned
        gpu_end = start + 1.2 * MS
        rows.append(row(dtNs=dt, tNs=t, view=0, ticksToDo=1 if tick else 0, ticks=1 if tick else 0, bundleNs=bundle,
                        bundleCpuNs=bundle_own, bundleWaitNs=0, bundleDrainNs=bundle_drain, bundleNativeNs=bundle_native,
                        preSubmitNs=pre, preSubmitWaitNs=pre_wait, preSubmitNativeNs=pre_native, preSubmitDrainNs=pre_drain,
                        submitNs=submit_native + submit_wait + submit_drain, seq=k + 1, commits=2 if trip else 1,
                        nativeSubmitNs=submit_native + pre_native + bundle_native, waitNs=submit_wait + pre_wait,
                        drainNs=submit_drain + pre_drain + bundle_drain, drawableWaitNs=0, gcEvents=0, guardTrips=1 if trip else 0,
                        gpuStartNs=start, gpuEndNs=gpu_end, gpuBusyNs=1.2 * MS, presentKind=1, commitNs=commit))
    return {"label": "synthetic-nested", "backend": "Metal", "medianFps": 0, "p1LowFps": 0, "p01LowFps": 0,
            "frameTrace": {"version": 1, "frames": len(rows), "metal": {"status": "synthetic"}, "columns": COLUMNS, "rows": rows}}


def run_cli(args):
    p = subprocess.run([sys.executable, os.path.join(HERE, "frame_trace.py")] + args, capture_output=True, text=True)
    return p.returncode, p.stdout, p.stderr


def main():
    # 1. Columns.
    check("46 columns parsed from FrameTrace.java", len(COLUMNS) == 46 and COLUMNS[0] == "dtNs" and COLUMNS[-1] == "preSubmitDrainNs")
    for needed in ("bundleNs", "bundleWaitNs", "waitNs", "nativeSubmitNs", "drawableWaitNs", "preSubmitNs", "gpuStartNs", "gpuEndNs",
                   "gpuBusyNs", "presentKind", "commitNs", "ticks", "gcEvents", "blocksCreated", "guardTrips", "view", "tNs",
                   "bundleDrainNs", "bundleNativeNs", "preSubmitWaitNs", "preSubmitNativeNs", "preSubmitDrainNs", "drainNs"):
        check("column %s exists" % needed, needed in IDX)

    with tempfile.TemporaryDirectory() as tmp:
        # 2. Metal-shaped run.
        metal = metal_run()
        path = os.path.join(tmp, "metal.json")
        with open(path, "w") as fh:
            json.dump(metal, fh)
        tr = frame_trace.Trace(metal)
        s, comp, labels, order, starved, n1, n01 = frame_trace.summarize(tr)
        a1, a01 = s["slowest1"]["attribution"], s["slowest01"]["attribution"]
        print("metal: slowest 1%", a1, "slowest 0.1%", a01)
        check("n1 = 200, n01 = 20", n1 == 200 and n01 == 20)
        check("slowest 1%: 185 tick frames", a1["tick"] == 185)
        check("slowest 1%: 5 gpu, 5 drawable, 3 submit, 1 render, 1 other", (a1["gpu"], a1["drawable"], a1["submit"], a1["render"], a1["other"]) == (5, 5, 3, 1, 1))
        check("slowest 0.1%: the 15 injected events + 5 tick frames", (a01["gpu"], a01["drawable"], a01["submit"], a01["render"], a01["other"], a01["tick"]) == (5, 5, 3, 1, 1, 5))
        injected = {5010, 5011, 5012, 5013, 5014, 7010, 7011, 7012, 7013, 7014, 9010, 9011, 9012, 11010, 12010}
        check("every injected event is in the slowest 0.1%", injected <= set(order[:n01]))
        check("injected causes attributed exactly", all(labels[k] == ("gpu" if k < 6000 else "drawable" if k < 8000 else "submit" if k < 10000
                                                                     else "render" if k < 11500 else "other") for k in injected))
        check("tick share of the slowest 1%% = 92.5%% (%.4f)" % s["tickShareSlowest1"], abs(s["tickShareSlowest1"] - 0.925) < 1e-9)
        check("tick share overall 2.5%", abs(s["tickShareAll"] - 0.025) < 1e-9)
        check("CPU p99 ~ G + T = 2.6 ms (%.3f)" % s["cpu"]["p99Ms"], 2.5 < s["cpu"]["p99Ms"] < 2.75)
        check("GPU cadence p99 ~ G = 1.5 ms (%.3f)" % s["gpuCadence"]["p99Ms"], 1.4 < s["gpuCadence"]["p99Ms"] < 1.6)
        check("GPU cadence p50 ~ 1.5 ms", 1.45 < s["gpuCadence"]["p50Ms"] < 1.55)
        check("present cadence covers fused frames only (every 3rd: ~4.5 ms)", 4.3 < s["presentCadence"]["p50Ms"] < 4.7)
        check("starved frames: at the GC hitch (12010, 12011)", starved[12010] and starved[12011] and s["starvedFrames"] == 2)
        check("tick bundle wall vs CPU reported", abs(s["tickBundleWallVsCpu"]["wallP50Ms"] - 1.1) < 1e-6 and abs(s["tickBundleWallVsCpu"]["cpuP50Ms"] - 0.99) < 1e-6)
        check("drain is its own component (0.01 ms per row)", all(abs(comp["drain"][k] - 0.01 * MS) < 1000 for k in range(tr.n)))
        check("tick-frame p99 ~ 2.6 ms, plain p99 < 2 ms", s["tickFrames"]["p99Ms"] > 2.5 and s["plainFrames"]["p99Ms"] < 2.0)
        cfg = "\n".join(frame_trace.config_summary(metal))
        check("config summary: fusion + fused share", "present fusion mode 1" in cfg and "fused share 0.3333" in cfg)
        check("config summary: mailbox, fallbacks", "mailbox active 1" in cfg and "no-drawable=13333" in cfg)
        check("config summary: late throttle property", "mcmetal.lateThrottle" in cfg)
        check("config summary: terrain toggles", "terrainSpecialize True" in cfg and "compactVertices False" in cfg)
        rc, out, err = run_cli([path, "--top", "30", "--csv", os.path.join(tmp, "metal.csv")])
        check("CLI exit 0 (%s)" % err.strip(), rc == 0)
        check("CLI report sections", "GPU cadence    p50" in out and "slowest 0.1%" in out and "histogram of the frame interval" in out
              and "tick share of the slowest 1%: 92.5%" in out)
        check("CLI flags: the GC row is 'other', starved, GC, blocks created", re.search(r"^\s+12010 .* other\s+\.GCB\.", out, re.M) is not None)
        with open(os.path.join(tmp, "metal.csv")) as fh:
            lines = fh.read().splitlines()
        check("CSV: header + 20000 rows", len(lines) == 20001 and lines[0].endswith(",attribution"))
        check("CSV: row 7010 attributed drawable", lines[7011].endswith(",drawable"))
        rc, out, err = run_cli([path, "--json"])
        summary = json.loads(out) if rc == 0 else {}
        check("CLI --json", summary.get("slowest1", {}).get("attribution", {}).get("tick") == 185)

        # 3. Vanilla-shaped run (no Metal columns).
        van = vanilla_run()
        tr = frame_trace.Trace(van)
        s, comp, labels, order, starved, n1, n01 = frame_trace.summarize(tr)
        print("vanilla: slowest 1%", s["slowest1"]["attribution"])
        check("vanilla: no metal", not s["metal"] and s["gpuCadence"]["n"] == 0)
        check("vanilla: the submit hitch is attributed to submit", labels[2500] == "submit" and order[0] == 2500)
        check("vanilla: tick frames fill the rest of the slowest 1%", s["slowest1"]["attribution"]["tick"] == n1 - 1)
        vpath = os.path.join(tmp, "vanilla.json")
        with open(vpath, "w") as fh:
            json.dump(van, fh)
        rc, out, err = run_cli([vpath])
        check("vanilla CLI exit 0", rc == 0 and "no GPU columns" in out and "result predates" in out)

        # 3b. Late-throttle-shaped run: the wait inside the bundle is "gpu", never "tick", and nothing is counted twice.
        lt = late_throttle_run()
        tr = frame_trace.Trace(lt)
        s, comp, labels, order, starved, n1, n01 = frame_trace.summarize(tr)
        print("late throttle: slowest 1%", s["slowest1"]["attribution"])
        check("late throttle: other = 0.02 ms on every row (no double counting)",
              all(abs(comp["other"][k] - 0.02 * MS) < 1000 for k in range(tr.n)))
        check("late throttle: tick component = bundle CPU work", all(abs(comp["tick"][k] - tr.get(k, "bundleCpuNs")) < 1000 for k in range(tr.n)))
        check("late throttle: slow frames are the tick frames' excess", s["slowest1"]["attribution"]["tick"] == n1)
        w = s["tickBundleWallVsCpu"]
        check("late throttle: C2 wall = CPU p50 (1.1 ms)", abs(w["wallP50Ms"] - 1.1) < 1e-6 and abs(w["cpuP50Ms"] - 1.1) < 1e-6)

        # 5. Nested Metal time counted once (r5-int review fixes).
        nested = nested_metal_run()
        tr = frame_trace.Trace(nested)
        s, comp, labels, order, starved, n1, n01 = frame_trace.summarize(tr)
        print("nested metal time: slowest 1%", s["slowest1"]["attribution"])
        check("nested: other = 0.02 ms on every row (no double counting, nothing unassigned)",
              all(abs(comp["other"][k] - 0.02 * MS) < 1000 for k in range(tr.n)))
        check("nested: render = the Java work only (0.4 ms) on every row", all(abs(comp["render"][k] - 0.4 * MS) < 1000 for k in range(tr.n)))
        check("nested: tick = the bundle's own work", all(abs(comp["tick"][k] - (1.0 * MS if k % 40 == 0 else 0.05 * MS)) < 1000 for k in range(tr.n)))
        check("nested: guard-trip frames attributed to gpu, not render", all(labels[k] == "gpu" for k in range(tr.n) if k % 50 == 7))
        check("nested: drains summed once", all(abs(comp["drain"][k] - ((0.2 * MS if k % 50 == 7 else 0) + 0.1 * MS + 0.01 * MS)) < 1000
                                               for k in range(tr.n)))
        w = s["tickBundleWallVsCpu"]
        check("nested: C2 wall excludes the Metal time inside the bundle (wall %.3f = CPU %.3f, raw %.3f)" % (w["wallP50Ms"], w["cpuP50Ms"], w["rawWallP50Ms"]),
              abs(w["wallP50Ms"] - 1.0) < 1e-6 and abs(w["cpuP50Ms"] - 1.0) < 1e-6 and abs(w["rawWallP50Ms"] - 1.15) < 1e-6)
        check("nested: starvation flagged from the GPU idle gap although an early commit started before commitNs",
              starved[1000] and not starved[999] and not starved[1001] and s["starvedFrames"] == 1)

        # 4. No frameTrace block.
        bare = os.path.join(tmp, "bare.json")
        with open(bare, "w") as fh:
            json.dump({"label": "old", "medianFps": 500}, fh)
        rc, out, err = run_cli([bare])
        check("bare result rejected", rc == 1 and "no frameTrace rows" in err)

    print("frame_trace_test: %d failures" % len(FAIL))
    return 1 if FAIL else 0


if __name__ == "__main__":
    sys.exit(main())
