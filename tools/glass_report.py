#!/usr/bin/env python3
"""On-glass report of runs made with MCMTL_GLASS_TRACE=1 (native/src/mcmetal_glass.h; results/BASELINE-DEFAULT.md).

Reads the "[mcmtl glass]" per-second lines of results/raw/<label>.log (the game's launch.log) and keeps the seconds inside
the bench's measured window ("[bench] -> MEASURE" to the next state change, at the log's 1 s resolution; the first and last
partial second dropped). Per run: on-glass rate (DISTINCT presentedTime values per second: panel refreshes that showed a new
frame), its spread over the seconds, the interval histogram in refreshes, composited share (didComposite), the seconds that
were fully direct-to-display, presents per second and how many were never shown, present -> glass latency.

usage: glass_report.py <log> [<log> ...] [--md]   (--md: one markdown table row per run, plus a header)
"""
import math, re, statistics, sys

KV = re.compile(r"(\w[\w+-]*)=([^\s,]+)")


def parse_time(s):
    h, m, sec = s.split(":")
    return int(h) * 3600 + int(m) * 60 + float(sec)


def read(path):
    measure = []
    lines = []
    for raw in open(path, errors="replace"):
        m = re.match(r"\[(\d\d:\d\d:\d\d)\].*\[bench\] -> (\w+)", raw)
        if m:
            measure.append((parse_time(m.group(1)), m.group(2)))
            continue
        if "[mcmtl glass] wall=" in raw:
            body = raw.split("[mcmtl glass] ", 1)[1]
            d = {}
            for k, v in KV.findall(body):
                d[k] = v
            iv = re.search(r"iv:sub=(\d+),1r=(\d+),2r=(\d+),3r=(\d+),4-7r=(\d+),8r\+=(\d+),off=(\d+)", body)
            d["iv"] = [int(x) for x in iv.groups()] if iv else [0] * 7
            c = re.search(r"comp=(\d+)/(\d+)", body)
            d["comp"], d["compKnown"] = (int(c.group(1)), int(c.group(2))) if c else (0, 0)
            lat = re.search(r"lat=([\d.]+)/([\d.]+)ms", body)
            d["latMean"], d["latMax"] = (float(lat.group(1)), float(lat.group(2))) if lat else (0.0, 0.0)
            d["wallS"] = parse_time(d["wall"])
            lines.append(d)
    t0 = t1 = None
    for i, (t, st) in enumerate(measure):
        if st in ("MEASURE", "ABAB"):
            t0 = t
            t1 = measure[i + 1][0] if i + 1 < len(measure) else None
    sel = [d for d in lines if t0 is not None and d["wallS"] >= t0 + 1 and (t1 is None or d["wallS"] < t1)]
    info = None
    for raw in open(path, errors="replace"):
        if "[mcmtl glass] on (" in raw:
            info = raw.split("[mcmtl glass] ", 1)[1].strip()
            break
    return sel, info, (t0, t1)


def empty_window(ts, period, bins=96):
    """Fold the times at `period`: (width in s, phase 0..1 of its centre) of the longest circular run of empty phase bins.
    A composited layer's presentedTime values avoid a ~1.4 ms window before every refresh (WindowServer's latch): folded
    at the panel's true period that window stays empty; at any other period it smears out (BASELINE-DEFAULT.md)."""
    h = [0] * bins
    for t in ts:
        h[int((t % period) / period * bins) % bins] += 1
    best, start = 0, 0
    for s0 in range(bins):
        n = 0
        while n < bins and h[(s0 + n) % bins] == 0:
            n += 1
        if n > best:
            best, start = n, s0
    return best * period / bins, ((start + best / 2) / bins) % 1.0


def from_csv(path, sel, window, refresh=1 / 120):
    """The same window recomputed from the raw CSV (MCMTL_GLASS_TRACE_OUT, results/raw/<label>.glass.csv) if it exists:
    distinct presentedTime values, refresh coverage, composited share, and the panel cadence from the values themselves:
    the empty latch window when folded at the refresh period (and at +-0.1 / +-0.5 Hz, where it must vanish), then the
    refreshes that had a new frame, counted exactly on that grid (one slot per refresh, boundary in the empty window).
    A consistency check of the per-second lines. None without a CSV."""
    csv_path = path[:-4] + ".glass.csv" if path.endswith(".log") else None
    try:
        rows = [l.rstrip("\n").split(",") for l in open(csv_path) if l.startswith("F,")]
    except (OSError, TypeError):
        return None
    off = statistics.median(float(d["t"]) - d["wallS"] for d in sel)   # media time - wall clock (s)
    lo = min(d["wallS"] for d in sel) - 1 + off   # the first selected line closes the first second of the window
    hi = max(d["wallS"] for d in sel) + off
    shown = sorted((float(r[3]), int(r[4])) for r in rows if float(r[3]) > 0 and lo <= float(r[3]) < hi)
    if len(shown) < 2:
        return None
    vals, comp = [], 0
    for t, c in shown:
        comp += c == 1
        if not vals or t - vals[-1] >= 0.25 * refresh:
            vals.append(t)
    gaps = [b - a for a, b in zip(vals, vals[1:])]
    span = sum(gaps)
    held = sum(g - refresh for g in gaps if g > refresh)
    ts = [t for t, _ in shown]
    hz = 1 / refresh
    fold = {f"{d:+.1f}": empty_window(ts, 1 / (hz + d))[0] * 1e3 for d in (-0.5, -0.1, 0.1, 0.5)}
    w, phase = empty_window(ts, refresh)
    slots = None
    if w > 0:
        k = sorted({math.floor(t / refresh - phase) for t in ts})
        slots = (len(k), k[-1] - k[0] + 1)
    return {"csvDistinctPerS": (len(vals) - 1) / span, "csvGlassHz": (1 - held / span) / refresh, "csvShown": len(shown),
            "csvComposited": comp, "csvSpan": span, "emptyMs": w * 1e3, "foldOthersMs": fold, "slots": slots}


def summarize(path):
    sel, info, window = read(path)
    if not sel:
        return {"path": path, "seconds": 0, "info": info, "window": window}
    csv = from_csv(path, sel, window)
    hz = [float(d["hz"]) for d in sel]
    ghz = [float(d["glassHz"]) for d in sel if "glassHz" in d]
    tgt = sum(int(d.get("tgt", 0)) for d in sel)
    tgt_off = sum(int(d.get("tgtOff", 0)) for d in sel)
    distinct = sum(int(d["distinct"]) for d in sel)
    shown = sum(int(d["shown"]) for d in sel)
    not_shown = sum(int(d["notShown"]) for d in sel)
    lost = sum(int(d["lost"]) for d in sel)
    same = sum(int(d["same"]) for d in sel)
    reg = sum(int(d["registered"]) for d in sel)
    iv = [sum(d["iv"][i] for d in sel) for i in range(7)]
    comp = sum(d["comp"] for d in sel)
    known = sum(d["compKnown"] for d in sel)
    direct_s = sum(1 for d in sel if d["compKnown"] and d["comp"] == 0)
    comp_s = sum(1 for d in sel if d["compKnown"] and d["comp"] == d["compKnown"])
    statuses = sorted({d.get("status") for d in sel})
    lat = statistics.mean(d["latMean"] for d in sel)
    lat_max = max(d["latMax"] for d in sel)
    ivmax = max(float(d["ivMax"].rstrip("ms")) for d in sel)
    n_iv = sum(iv[:6])
    return {
        "path": path, "seconds": len(sel), "info": info, "window": window,
        "hzMean": statistics.mean(hz), "hzMedian": statistics.median(hz), "hzMin": min(hz), "hzMax": max(hz),
        "hzP10": sorted(hz)[max(0, int(0.1 * (len(hz) - 1)))],
        "glassHzMean": statistics.mean(ghz) if ghz else None, "glassHzMin": min(ghz) if ghz else None,
        "glassHzMedian": statistics.median(ghz) if ghz else None, "targets": tgt, "targetOff": tgt_off,
        "distinct": distinct, "shown": shown, "notShown": not_shown, "lost": lost, "same": same, "registered": reg,
        "presentsPerS": reg / len(sel), "iv": iv, "oneRefreshShare": iv[1] / n_iv if n_iv else 0.0,
        "composited": comp, "compKnown": known, "directSeconds": direct_s, "compositedSeconds": comp_s,
        "statuses": statuses, "latMean": lat, "latMax": lat_max, "ivMaxMs": ivmax, "csv": csv,
    }


def fmt(s):
    if not s["seconds"]:
        return f"{s['path']}: no glass lines in the measured window {s['window']} ({s['info']})"
    iv = s["iv"]
    g = (f"refreshes with a new frame {s['glassHzMean']:.1f}/s mean, min {s['glassHzMin']:.1f}; target timestamps "
         f"{s['targets']} ({s['targetOff']} gaps off-grid); " if s["glassHzMean"] is not None else "")
    return (f"{s['path']}: {s['seconds']} s measured; {g}distinct presentedTime {s['hzMean']:.1f}/s mean, median {s['hzMedian']:.0f}, "
            f"p10 {s['hzP10']:.0f}, min {s['hzMin']:.0f}, max {s['hzMax']:.0f}; intervals 1r {iv[1]} 2r {iv[2]} 3r {iv[3]} "
            f"4-7r {iv[4]} 8r+ {iv[5]} sub {iv[0]} off-grid {iv[6]} (1r share {100 * s['oneRefreshShare']:.2f}%, max "
            f"{s['ivMaxMs']:.1f} ms); presents {s['presentsPerS']:.0f}/s, shown {s['shown']} (same refresh {s['same']}), "
            f"never shown {s['notShown']}, lost {s['lost']}; composited {s['composited']}/{s['compKnown']}, direct seconds "
            f"{s['directSeconds']}/{s['seconds']}; status {','.join(map(str, s['statuses']))}; present->glass "
            f"{s['latMean']:.2f} ms mean, {s['latMax']:.2f} max\n  {s['info']}" + (
                f"\n  raw CSV, same window: distinct {s['csv']['csvDistinctPerS']:.1f}/s, refreshes with a new frame "
                f"{s['csv']['csvGlassHz']:.1f}/s, composited {s['csv']['csvComposited']}/{s['csv']['csvShown']} over "
                f"{s['csv']['csvSpan']:.1f} s; folded at the refresh: empty latch window {s['csv']['emptyMs']:.2f} ms (at "
                + ", ".join(f"{k} Hz: {v:.2f} ms" for k, v in s['csv']['foldOthersMs'].items()) + ")"
                + (f"; refreshes with a new frame on that grid: {s['csv']['slots'][0]} of {s['csv']['slots'][1]} = "
                   f"{120 * s['csv']['slots'][0] / s['csv']['slots'][1]:.2f}/s" if s['csv']['slots'] else "")
                if s.get("csv") else ""))


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    md = "--md" in sys.argv
    rows = [summarize(p) for p in args]
    if md:
        print("| run | s | refreshes with a new frame /s: grid count (CSV) | coverage /s: mean / min second | latch window folded at "
              "120 Hz / at +-0.1 Hz (ms) | distinct presentedTime /s: mean / min / max | intervals 1 r / 2 r / 3 r+ / sub-refresh | "
              "composited | direct seconds | presents /s (never shown) | present->presentedTime ms |")
        print("|---|---|---|---|---|---|---|---|---|---|---|")
        for s in rows:
            if not s["seconds"]:
                print(f"| {s['path']} | 0 |" + " |" * 9)
                continue
            iv = s["iv"]
            c = s.get("csv")
            name = s["path"].rsplit("/", 1)[-1].replace(".log", "")
            grid = f"{120 * c['slots'][0] / c['slots'][1]:.2f} ({c['slots'][0]} of {c['slots'][1]})" if c and c["slots"] else "-"
            gh = (f"{s['glassHzMean']:.1f} / {s['glassHzMin']:.1f}" if s["glassHzMean"] is not None else
                  f"{c['csvGlassHz']:.1f} (CSV)" if c else "-")
            fold = (f"{c['emptyMs']:.2f} / {max(c['foldOthersMs']['-0.1'], c['foldOthersMs']['+0.1']):.2f}" if c else "-")
            print(f"| {name} | {s['seconds']} | {grid} | {gh} | {fold} | {s['hzMean']:.0f} / {s['hzMin']:.0f} / {s['hzMax']:.0f} | "
                  f"{iv[1]} / {iv[2]} / {iv[3] + iv[4] + iv[5]} / {iv[0]} | {s['composited']}/{s['compKnown']} | "
                  f"{s['directSeconds']}/{s['seconds']} | {s['presentsPerS']:.0f} ({s['notShown']}) | {s['latMean']:.1f} |")
    else:
        for s in rows:
            print(fmt(s))


if __name__ == "__main__":
    main()
