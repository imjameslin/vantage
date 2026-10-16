#!/usr/bin/env python3
"""Attribute every slow frame of a bench run (docs/metal/CONTRACT.md 16.R5-B, results/TAIL-ANALYSIS.md section 6).

Input: a bench result JSON written with -Dbench.frameTrace=true (its "frameTrace" block; "config" is used when present).

    python3 tools/frame_trace.py results/raw/LABEL.json [--top 40] [--bin-ms 0.25] [--csv out.csv] [--json]

Prints:
  * the effective configuration that matters for the tail (fusion and its measured share, mailbox, ICB, occlusion,
    terrain toggles, late throttle, JVM GC);
  * CPU-interval percentiles (the bench's frame times) next to the GPU cadence (completion-to-completion of the frames'
    last command buffers) and the present cadence (presented frames only), GPU busy time, tick vs non-tick p99 and the
    tick share of the slowest 1%;
  * the slowest 1% and 0.1% frames with their attribution: the component whose excess over its own median is largest.
    The components partition the interval (each nanosecond counted once): Metal waits, native submits and drains are
    taken out of the wall-time components they happen inside (tick bundle, pre-submit render work) and counted once in
    their own component.
      tick      runTick's tick bundle (packets, tasks, texture tick, client ticks) minus the timeline waits, native
                submits (flush-on-wait commits) and drains inside it (with the late throttle the frame wait is inside the
                bundle: it counts as gpu, never as tick)
      gpu       the render thread waited for the GPU: every timeline wait (the frame throttle in submit, at the late
                throttle point, at a late-throttle guard trip during rendering, fence waits)
      drawable  nextDrawable wait inside the native submit
      submit    native submit (decode + encode + commit) minus the drawable wait, every commit of the interval
      drain     timeline drains (deferred releases, readback callbacks), minus waits / commits inside callbacks
      render    Java render work before submit (level update, extract, render, blit) minus the waits, native submits
                and drains that happened in it
      other     the rest of the interval (present, sound / mouse, bench bookkeeping, GC, preemption)
    plus flags: T = tick frame, G = the GPU went idle before this frame's work (starved: its first command buffer started
    more than 20 us after the previous frame's GPU work ended), C = GC in the interval, B = transient blocks created,
    P = guard trip;
  * a histogram of the frame interval with the tick-frame count per bin, and (spin runs) a table per 30-degree yaw
    sector: CPU p50 / p99, mean GPU busy time and the slowest-1% frames (the heaviest view directions).
Without the Metal columns (vanilla / Vulkan) the gpu, drawable and submit components merge into "submit" (the whole
CommandEncoder.submit() call, throttle included).

--json prints a machine-readable summary instead; --csv writes the rows (one per frame, all columns + attribution).
"""
import argparse
import csv
import json
import math
import sys

COMPONENTS = ("tick", "gpu", "drawable", "submit", "drain", "render", "other")
PRESENT_KINDS = ("none", "fused", "present-pass", "skipped", "nil", "not-presented")
STARVED_SLACK_NS = 20_000


def pct(values, p):
    """Nearest-rank percentile on a sorted copy (same rule as Bench.pct: round(p * (n - 1)))."""
    if not values:
        return float("nan")
    s = sorted(values)
    return s[min(len(s) - 1, int(round(p * (len(s) - 1))))]


def median(values):
    return pct(values, 0.5)


def ms(ns):
    return float("nan") if ns is None or (isinstance(ns, float) and math.isnan(ns)) else ns / 1e6


def load(path):
    with open(path, encoding="utf-8") as fh:
        return json.load(fh)


class Trace:
    def __init__(self, result):
        ft = result.get("frameTrace")
        if not ft or not ft.get("rows"):
            raise ValueError("no frameTrace rows in this result (run with --bench-prop frameTrace=true)")
        self.result = result
        self.meta = ft
        self.columns = ft["columns"]
        self.index = {name: i for i, name in enumerate(self.columns)}
        self.rows = ft["rows"]
        self.n = len(self.rows)
        self.metal = ft.get("metal") is not None and self.has("waitNs")

    def has(self, name):
        i = self.index.get(name)
        return i is not None and any(r[i] >= 0 for r in self.rows)

    def col(self, name):
        i = self.index.get(name)
        if i is None:
            return [-1] * self.n
        return [r[i] for r in self.rows]

    def get(self, row, name):
        i = self.index.get(name)
        return -1 if i is None else self.rows[row][i]


def bundle_net(tr, k, bundle, bundle_wait, bundle_drain, bundle_native):
    """Tick-bundle wall time without the Metal waits / drains / native submits inside it (late throttle: the frame wait)."""
    return max(0, bundle[k] - max(0, bundle_wait[k]) - max(0, bundle_drain[k]) - max(0, bundle_native[k]))


def components(tr):
    """Per-frame component values (ns): a partition of the interval, every nanosecond counted once."""
    dt = tr.col("dtNs")
    bundle = tr.col("bundleNs")
    bundle_wait = tr.col("bundleWaitNs")
    bundle_drain = tr.col("bundleDrainNs")
    bundle_native = tr.col("bundleNativeNs")
    wait = tr.col("waitNs")
    drain = tr.col("drainNs")
    drawable = tr.col("drawableWaitNs")
    native = tr.col("nativeSubmitNs")
    submit_call = tr.col("submitNs")
    pre = tr.col("preSubmitNs")
    pre_wait = tr.col("preSubmitWaitNs")
    pre_native = tr.col("preSubmitNativeNs")
    pre_drain = tr.col("preSubmitDrainNs")
    out = {c: [0] * tr.n for c in COMPONENTS}
    for k in range(tr.n):
        tick = bundle_net(tr, k, bundle, bundle_wait, bundle_drain, bundle_native)
        if tr.metal and wait[k] >= 0:
            gpu = max(0, wait[k])   # every timeline wait: in submit, at the late throttle point (in the bundle), at a guard trip
            dw = max(0, drawable[k])
            sub = max(0, native[k] - dw) if native[k] >= 0 else 0
            dr = max(0, drain[k])
            render = max(0, pre[k] - max(0, pre_wait[k]) - max(0, pre_native[k]) - max(0, pre_drain[k]))
        else:
            gpu = 0
            dw = 0
            dr = 0
            sub = max(0, submit_call[k])
            render = max(0, pre[k])
        known = tick + gpu + dw + sub + dr + render
        out["tick"][k] = tick
        out["gpu"][k] = gpu
        out["drawable"][k] = dw
        out["submit"][k] = sub
        out["drain"][k] = dr
        out["render"][k] = render
        out["other"][k] = max(0, dt[k] - known)
    return out


def gpu_cadence(tr):
    """Completion-to-completion intervals of the frames' GPU work, and of presented frames only."""
    end = tr.col("gpuEndNs")
    start = tr.col("gpuStartNs")
    kind = tr.col("presentKind")
    commit = tr.col("commitNs")
    cadence, present, starved = [], [], [False] * tr.n
    prev_end = prev_present = None
    for k in range(tr.n):
        if end[k] < 0:
            continue
        if prev_end is not None:
            cadence.append(end[k] - prev_end)
            # The GPU idled between the previous frame's end and this frame's first command buffer (an early commit
            # may start before submit returns, so commitNs is not a lower bound for the start).
            if start[k] >= 0 and start[k] > prev_end + STARVED_SLACK_NS:
                starved[k] = True
        prev_end = end[k]
        if kind[k] in (1, 2):
            if prev_present is not None:
                present.append(end[k] - prev_present)
            prev_present = end[k]
    return cadence, present, starved


def attribute(tr, comp):
    meds = {c: median(comp[c]) for c in COMPONENTS}
    labels = []
    for k in range(tr.n):
        best, best_excess = "other", -1
        for c in COMPONENTS:
            excess = comp[c][k] - meds[c]
            if excess > best_excess:
                best, best_excess = c, excess
        labels.append(best)
    return labels, meds


def flags(tr, k, starved):
    f = ""
    f += "T" if tr.get(k, "ticks") > 0 else "."
    f += "G" if starved[k] else "."
    f += "C" if tr.get(k, "gcEvents") > 0 else "."
    f += "B" if tr.get(k, "blocksCreated") > 0 else "."
    f += "P" if tr.get(k, "guardTrips") > 0 else "."
    return f


def config_summary(result):
    cfg = result.get("config") or {}
    nat = cfg.get("native") or {}
    meas = cfg.get("measured") or {}
    props = cfg.get("properties") or {}
    env = cfg.get("env") or {}
    toggles = cfg.get("toggles") or {}
    lines = []
    if not cfg:
        lines.append("config: (no config block: result predates CONTRACT.md 16.R5-B)")
        return lines
    fuse = nat.get("fuseMode")
    lines.append("config: backend %s; present fusion mode %s (env %s), fused share %s, presented share %s, skipped %s, mailbox share %s"
                 % (cfg.get("backendClass", "?").rsplit(".", 1)[-1], fuse, nat.get("presentFusionEnv"), meas.get("fusedShare"),
                    meas.get("presentedShare"), meas.get("skippedShare"), meas.get("mailboxShare")))
    if meas.get("fusionFallbacks"):
        lines.append("        fusion fallbacks: " + ", ".join("%s=%s" % kv for kv in meas["fusionFallbacks"].items()))
    lines.append("        mailbox active %s (%s drawables, queue %s); ICB mode %s; occlusion %s; M2 flags %s; profilePasses %s; drawable wait %s ms/frame"
                 % (nat.get("mailboxActive"), nat.get("drawableCount"), nat.get("mailboxMaxQueued"), nat.get("icbMode"),
                    nat.get("occlusionMode"), nat.get("m2Flags"), nat.get("profilePasses"), meas.get("drawableWaitMsPerFrame")))
    tsv = toggles.get("TerrainShaderVariants") or {}
    tt = toggles.get("TerrainToggles") or {}
    lines.append("        terrainSpecialize %s; compactVertices %s; cutoutFrontToBack %s; sortCutoutSections %s"
                 % (tsv.get("specialize"), tt.get("COMPACT_VERTICES"), tt.get("cutoutFrontToBack"), tt.get("sortCutoutSections")))
    lt = {k: v for k, v in props.items() if "throttle" in k.lower()}
    for cls, fields in toggles.items():
        for name, value in fields.items():
            if "throttle" in name.lower() or "throttle" in cls.lower():
                lt["%s.%s" % (cls, name)] = value
    lines.append("        late throttle: %s" % (lt if lt else "not present"))
    other = {k: v for k, v in env.items() if k.startswith("MCMTL_")}
    lines.append("        MCMTL_* env: %s" % (other if other else "none"))
    jvm = cfg.get("jvm") or {}
    lines.append("        JVM %s, GC %s, heap %s MB; args: %s" % (jvm.get("version"), jvm.get("gc"), jvm.get("maxHeapMB"),
                                                             " ".join(a for a in jvm.get("args", []) if a.startswith("-X") or a.startswith("-XX"))))
    game = cfg.get("game") or {}
    if game:
        lines.append("        video: RD %s, vsync %s, framerateLimit %s, preset %s, window %sx%s fullscreen %s"
                     % (game.get("renderDistance"), game.get("enableVsync"), game.get("framerateLimit"), game.get("graphicsPreset"),
                        game.get("window.getWidth"), game.get("window.getHeight"), game.get("window.isFullscreen")))
    return lines


def summarize(tr):
    comp = components(tr)
    labels, meds = attribute(tr, comp)
    cadence, present, starved = gpu_cadence(tr)
    dt = tr.col("dtNs")
    ticks = tr.col("ticks")
    order = sorted(range(tr.n), key=lambda k: dt[k], reverse=True)
    n1 = max(1, int(round(tr.n * 0.01)))
    n01 = max(1, int(round(tr.n * 0.001)))
    slow1 = order[:n1]
    tick_frames = [dt[k] for k in range(tr.n) if ticks[k] > 0]
    plain_frames = [dt[k] for k in range(tr.n) if ticks[k] <= 0]
    busy = [b for b in tr.col("gpuBusyNs") if b >= 0]
    counts = {c: sum(1 for k in slow1 if labels[k] == c) for c in COMPONENTS}
    counts01 = {c: sum(1 for k in order[:n01] if labels[k] == c) for c in COMPONENTS}
    s = {
        "frames": tr.n,
        "metal": tr.metal,
        "cpu": {"p50Ms": ms(pct(dt, 0.5)), "p90Ms": ms(pct(dt, 0.9)), "p99Ms": ms(pct(dt, 0.99)), "p999Ms": ms(pct(dt, 0.999)),
                "maxMs": ms(max(dt))},
        "gpuCadence": {"n": len(cadence), "p50Ms": ms(pct(cadence, 0.5)), "p99Ms": ms(pct(cadence, 0.99)), "p999Ms": ms(pct(cadence, 0.999))},
        "presentCadence": {"n": len(present), "p50Ms": ms(pct(present, 0.5)), "p99Ms": ms(pct(present, 0.99))},
        "gpuBusy": {"p50Ms": ms(pct(busy, 0.5)), "p99Ms": ms(pct(busy, 0.99))},
        "tickFrames": {"n": len(tick_frames), "p50Ms": ms(pct(tick_frames, 0.5)), "p99Ms": ms(pct(tick_frames, 0.99))},
        "plainFrames": {"n": len(plain_frames), "p50Ms": ms(pct(plain_frames, 0.5)), "p99Ms": ms(pct(plain_frames, 0.99))},
        "tickShareSlowest1": sum(1 for k in slow1 if ticks[k] > 0) / len(slow1),
        "tickShareAll": len(tick_frames) / tr.n,
        "starvedFrames": sum(starved),
        "slowest1": {"n": n1, "attribution": counts},
        "slowest01": {"n": n01, "attribution": counts01},
        "componentMediansMs": {c: ms(meds[c]) for c in COMPONENTS},
        "componentMeanExcessSlowest1Ms": {c: ms(sum(comp[c][k] - meds[c] for k in slow1) / len(slow1)) for c in COMPONENTS},
    }
    bundle = tr.col("bundleNs")
    bundle_cpu = tr.col("bundleCpuNs")
    bundle_wait = tr.col("bundleWaitNs")
    bundle_drain = tr.col("bundleDrainNs")
    bundle_native = tr.col("bundleNativeNs")
    tk = [k for k in range(tr.n) if ticks[k] > 0 and bundle[k] >= 0]
    if tk and any(bundle_cpu[k] >= 0 for k in tk):
        # TAIL-ANALYSIS C2: wall vs CPU of the tick work itself. The wall figure excludes timeline waits (with the late
        # throttle the frame wait is inside the bundle), drains and native commits; the raw wall time is reported too.
        net = [bundle_net(tr, k, bundle, bundle_wait, bundle_drain, bundle_native) for k in tk]
        cpu = [bundle_cpu[k] for k in tk if bundle_cpu[k] >= 0]
        s["tickBundleWallVsCpu"] = {"wallP50Ms": ms(median(net)), "cpuP50Ms": ms(median(cpu)),
                                    "wallP90Ms": ms(pct(net, 0.9)), "cpuP90Ms": ms(pct(cpu, 0.9)),
                                    "rawWallP50Ms": ms(median([bundle[k] for k in tk]))}
    return s, comp, labels, order, starved, n1, n01


def frame_table(tr, comp, labels, starved, rows, title):
    out = [title, "  %6s %8s %7s %5s %7s %7s %7s %7s %7s %7s %7s %7s %7s %-9s %-5s %-12s"
           % ("row", "t s", "dt ms", "ticks", "tick", "gpu", "drawbl", "submit", "drain", "render", "other", "gpuBsy", "view", "attrib", "flags",
              "present")]
    for k in rows:
        kind = tr.get(k, "presentKind")
        out.append("  %6d %8.3f %7.3f %5d %7.3f %7.3f %7.3f %7.3f %7.3f %7.3f %7.3f %7s %7.1f %-9s %-5s %-12s"
                   % (k, tr.get(k, "tNs") / 1e9, ms(tr.get(k, "dtNs")), max(0, tr.get(k, "ticks")), ms(comp["tick"][k]), ms(comp["gpu"][k]),
                      ms(comp["drawable"][k]), ms(comp["submit"][k]), ms(comp["drain"][k]), ms(comp["render"][k]), ms(comp["other"][k]),
                      "%.3f" % ms(tr.get(k, "gpuBusyNs")) if tr.get(k, "gpuBusyNs") >= 0 else "-", tr.get(k, "view") / 1000.0,
                      labels[k], flags(tr, k, starved), PRESENT_KINDS[kind] if 0 <= kind < len(PRESENT_KINDS) else "-"))
    return out


def histogram(tr, bin_ms, width=50):
    dt = tr.col("dtNs")
    ticks = tr.col("ticks")
    top = pct(dt, 0.999) * 1.25
    bin_ns = bin_ms * 1e6
    nbins = max(1, int(math.ceil(top / bin_ns)))
    counts = [0] * (nbins + 1)
    tick_counts = [0] * (nbins + 1)
    for k in range(tr.n):
        b = min(nbins, int(dt[k] // bin_ns))
        counts[b] += 1
        if ticks[k] > 0:
            tick_counts[b] += 1
    peak = max(counts)
    out = ["histogram of the frame interval (%.2f ms bins; '#' all frames, the count in [] are tick frames):" % bin_ms]
    for b in range(nbins + 1):
        if counts[b] == 0:
            continue
        label = (">= %6.2f" % (b * bin_ms)) if b == nbins else ("%6.2f-%-6.2f" % (b * bin_ms, (b + 1) * bin_ms))
        bar = "#" * max(1, int(round(width * counts[b] / peak)))
        out.append("  %-15s %7d %6.2f%% [%6d] %s" % (label, counts[b], 100.0 * counts[b] / tr.n, tick_counts[b], bar))
    return out


def report(result, args):
    tr = Trace(result)
    s, comp, labels, order, starved, n1, n01 = summarize(tr)
    lines = ["%s: %s, %d frames, median %.0f FPS, 1%% low %.0f, 0.1%% low %.0f"
             % (result.get("label", "?"), result.get("backend", "?"), tr.n, result.get("medianFps", float("nan")),
                result.get("p1LowFps", float("nan")), result.get("p01LowFps", float("nan")))]
    lines += config_summary(result)
    meta = tr.meta
    lines.append("trace: metal %s; guard trips source %s; cpu sampling %s; dropped rows %s"
                 % ((meta.get("metal") or {}).get("status", "absent"), meta.get("guardTripsSource"), meta.get("cpuSampling"), meta.get("dropped")))
    c, g, p = s["cpu"], s["gpuCadence"], s["presentCadence"]
    lines.append("")
    lines.append("CPU interval   p50 %.3f  p90 %.3f  p99 %.3f  p99.9 %.3f  max %.3f ms" % (c["p50Ms"], c["p90Ms"], c["p99Ms"], c["p999Ms"], c["maxMs"]))
    if g["n"]:
        lines.append("GPU cadence    p50 %.3f  p99 %.3f  p99.9 %.3f ms   (completion to completion, %d frames)" % (g["p50Ms"], g["p99Ms"], g["p999Ms"], g["n"]))
        if p["n"]:
            lines.append("present cad.   p50 %.3f  p99 %.3f ms   (%d presented intervals)" % (p["p50Ms"], p["p99Ms"], p["n"]))
        else:
            lines.append("present cad.   (no presented frames)")
        lines.append("GPU busy       p50 %.3f  p99 %.3f ms;  GPU starved before %d frames" % (s["gpuBusy"]["p50Ms"], s["gpuBusy"]["p99Ms"], s["starvedFrames"]))
        lines.append("=> CPU p99 - GPU-cadence p99 = %+.3f ms" % (c["p99Ms"] - g["p99Ms"]))
    else:
        lines.append("GPU cadence    (no GPU columns: not Metal, or the native ring was unavailable)")
    lines.append("tick frames    %d (%.1f%%): p50 %.3f p99 %.3f ms;  other frames p50 %.3f p99 %.3f ms"
                 % (s["tickFrames"]["n"], 100 * s["tickShareAll"], s["tickFrames"]["p50Ms"], s["tickFrames"]["p99Ms"],
                    s["plainFrames"]["p50Ms"], s["plainFrames"]["p99Ms"]))
    lines.append("tick share of the slowest 1%%: %.1f%% (base rate %.1f%%)" % (100 * s["tickShareSlowest1"], 100 * s["tickShareAll"]))
    if "tickBundleWallVsCpu" in s:
        w = s["tickBundleWallVsCpu"]
        lines.append("tick bundle wall (without Metal waits / drains / commits) vs render-thread CPU (tick frames): p50 %.3f / %.3f ms, "
                     "p90 %.3f / %.3f ms (raw wall p50 %.3f ms)"
                     % (w["wallP50Ms"], w["cpuP50Ms"], w["wallP90Ms"], w["cpuP90Ms"], w["rawWallP50Ms"]))
    lines.append("component medians (ms): " + ", ".join("%s %.3f" % (k, v) for k, v in s["componentMediansMs"].items()))
    lines.append("mean excess over median in the slowest 1% (ms): " + ", ".join("%s %+.3f" % (k, v) for k, v in s["componentMeanExcessSlowest1Ms"].items()))
    lines.append("slowest 1%% (%d frames) by attribution: %s" % (n1, ", ".join("%s %d" % kv for kv in s["slowest1"]["attribution"].items() if kv[1])))
    lines.append("slowest 0.1%% (%d frames) by attribution: %s" % (n01, ", ".join("%s %d" % kv for kv in s["slowest01"]["attribution"].items() if kv[1])))
    lines.append("")
    lines += frame_table(tr, comp, labels, starved, order[:n01], "slowest 0.1% (flags: T tick, G GPU starved, C GC, B blocks created, P guard trip):")
    lines.append("")
    lines += frame_table(tr, comp, labels, starved, order[n01:min(n1, args.top)],
                         "slowest 1%%, next %d of %d:" % (max(0, min(n1, args.top) - n01), n1 - n01))
    lines.append("")
    lines += histogram(tr, args.bin_ms)
    if "yaw" in str(tr.meta.get("view", "yaw")):
        lines.append("")
        lines += sectors(tr, labels, set(order[:n1]))
    return "\n".join(lines), s, tr, comp, labels


def sectors(tr, labels, slow1, width=30):
    """Static spin: per 30-degree yaw sector, CPU p50 / p99, GPU busy mean and the slowest-1% frames (heaviest views)."""
    view = tr.col("view")
    dt = tr.col("dtNs")
    busy = tr.col("gpuBusyNs")
    ticks = tr.col("ticks")
    out = ["by view direction (yaw sector; the frame's GPU busy time shows the heaviest directions):",
           "  %-9s %7s %8s %8s %9s %7s %8s" % ("yaw", "frames", "p50 ms", "p99 ms", "GPU ms", "slow1%", "slowTick")]
    for lo in range(0, 360, width):
        ks = [k for k in range(tr.n) if lo <= (view[k] / 1000.0) % 360.0 < lo + width]
        if not ks:
            continue
        b = [busy[k] for k in ks if busy[k] >= 0]
        out.append("  %3d-%-5d %7d %8.3f %8.3f %9s %7d %8d"
                   % (lo, lo + width, len(ks), ms(pct([dt[k] for k in ks], 0.5)), ms(pct([dt[k] for k in ks], 0.99)),
                      "%.3f" % ms(sum(b) / len(b)) if b else "-", sum(1 for k in ks if k in slow1),
                      sum(1 for k in ks if k in slow1 and ticks[k] > 0)))
    return out


def write_csv(path, tr, comp, labels):
    with open(path, "w", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh)
        w.writerow(list(tr.columns) + ["c_" + c for c in COMPONENTS] + ["attribution"])
        for k in range(tr.n):
            w.writerow(list(tr.rows[k]) + [comp[c][k] for c in COMPONENTS] + [labels[k]])


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("result", nargs="+", help="bench result JSON(s) with a frameTrace block")
    ap.add_argument("--top", type=int, default=40, help="rows of the slowest 1%% table (default 40)")
    ap.add_argument("--bin-ms", type=float, default=0.25, help="histogram bin width in ms (default 0.25)")
    ap.add_argument("--csv", help="write per-frame rows + components + attribution here (single input)")
    ap.add_argument("--json", action="store_true", help="print a JSON summary instead of the report")
    args = ap.parse_args(argv)
    summaries = {}
    status = 0
    for path in args.result:
        try:
            result = load(path)
            text, s, tr, comp, labels = report(result, args)
        except (OSError, ValueError, KeyError) as e:
            print("%s: %s" % (path, e), file=sys.stderr)
            status = 1
            continue
        summaries[path] = s
        if args.csv:
            write_csv(args.csv, tr, comp, labels)
        if not args.json:
            print(text)
            if len(args.result) > 1:
                print()
    if args.json:
        print(json.dumps(summaries if len(args.result) > 1 else next(iter(summaries.values()), {}), indent=2))
    return status


if __name__ == "__main__":
    sys.exit(main())
