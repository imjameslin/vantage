#!/usr/bin/env python3
"""Fixed-view run summary for GPU attribution (results/GPU-HEAVY.md).

Per bench JSON (perf mode with `bench.yaw`, `bench.spin=1e6` and `bench.frameTrace=true`): FPS (median, avg, 1 % low),
GPU cadence (GPU end to GPU end of consecutive frames; when the run is GPU-bound this is the GPU time per frame),
GPU "own" time (end - max(start, previous end): the part of the frame's GPU span not overlapping the previous frame),
the same split by presented (fused / present pass) and not presented frames, the presented share, plus the checks:
display mode, framebuffer, render distance, jar sha1, the -Dmcmetal.skipPipelines value and MCMTL_* env.

With --base A.json[,B.json] each row also gets the difference of its mean GPU cadence against the mean of the base runs
(the GPU time the skipped pipelines cost, or the toggle's effect).

usage: fixed_view.py <json> [<json> ...] [--base a.json,b.json] [--md]
"""
import json, math, statistics, sys


def pct(xs, q):
    if not xs:
        return float("nan")
    xs = sorted(xs)
    return xs[min(len(xs) - 1, max(0, int(math.ceil(q * len(xs))) - 1))]


def summarize(path):
    d = json.load(open(path))
    ft = d.get("frameTrace") or {}
    cols = ft.get("columns")
    out = {"label": d.get("label"), "median": d.get("medianFps"), "avg": d.get("avgFps"), "p1": d.get("p1LowFps"),
           "fb": d.get("framebuffer"), "rd": d.get("renderDistance")}
    launch = d.get("launch") or {}
    disp = launch.get("display") or {}
    out["default"] = bool(disp.get("default")) and disp.get("pixels") == "3024x1964" and float(disp.get("hz", 0)) == 120.0
    cfg = d.get("config") or {}
    props = cfg.get("properties") or {}
    out["skip"] = props.get("mcmetal.skipPipelines", "")
    env = cfg.get("env") or {}
    out["env"] = ",".join(f"{k}={v}" for k, v in sorted(env.items()) if k.startswith("MCMTL_"))
    jars = cfg.get("jars") or {}
    out["jar"] = (jars.get("client") or {}).get("sha1", "") if isinstance(jars.get("client"), dict) else str(jars.get("clientSha1", ""))
    if not cols:
        return out
    ix = {c: i for i, c in enumerate(cols)}
    rows = ft["rows"]
    end = [r[ix["gpuEndNs"]] for r in rows]
    st = [r[ix["gpuStartNs"]] for r in rows]
    pk = [r[ix["presentKind"]] for r in rows]
    cad, own, own_p, own_s = [], [], [], []
    for k in range(1, len(rows)):
        if end[k] > 0 and end[k - 1] > 0 and end[k] > end[k - 1]:
            cad.append((end[k] - end[k - 1]) / 1e6)
            if st[k] > 0:
                o = (end[k] - max(st[k], end[k - 1])) / 1e6
                own.append(o)
                (own_p if pk[k] in (1, 2) else own_s).append(o)
    out.update(cadP50=pct(cad, .5), cadMean=statistics.mean(cad) if cad else float("nan"), cadP99=pct(cad, .99),
               ownP50=pct(own, .5), ownMean=statistics.mean(own) if own else float("nan"),
               ownPres=statistics.mean(own_p) if own_p else float("nan"), ownSkip=statistics.mean(own_s) if own_s else float("nan"),
               presShare=len(own_p) / max(1, len(own)), frames=len(rows))
    return out


def main():
    args = sys.argv[1:]
    md = "--md" in args
    base = []
    if "--base" in args:
        base = args[args.index("--base") + 1].split(",")
        del args[args.index("--base"):args.index("--base") + 2]
    paths = [a for a in args if not a.startswith("--")]
    bmean = None
    if "--drift" in args:
        # paths in run order, the first and the last are base runs: each run is compared with the base linearly
        # interpolated at its position (removes a steady drift of the machine over the series).
        ss = [summarize(p) for p in paths]
        b0, b1, n = ss[0]["cadMean"], ss[-1]["cadMean"], len(ss) - 1
        print(f"drift over the series: {b1 - b0:+.3f} ms GPU cadence mean ({b0:.3f} -> {b1:.3f})")
        if md:
            print("| run | skipped | median FPS | GPU cadence mean ms | interpolated base ms | saved by skipping ms | share of base |")
            print("|---|---|---|---|---|---|---|")
        for i, s in enumerate(ss[1:-1], 1):
            ref = b0 + (b1 - b0) * i / n
            cells = [s["label"], s["skip"] or "-", f"{s['median']:.0f}", f"{s['cadMean']:.3f}", f"{ref:.3f}",
                     f"{ref - s['cadMean']:+.3f}", f"{100 * (ref - s['cadMean']) / ref:+.0f}%"]
            print(("| " + " | ".join(cells) + " |") if md else "  ".join(cells))
        return
    if base:
        bs = [summarize(p) for p in base]
        bmean = statistics.mean(b["cadMean"] for b in bs)
    hdr = ["run", "skip / env", "median", "avg", "1% low", "GPU cad p50", "GPU cad mean", "GPU cad p99", "own mean",
           "own presented", "own not presented", "presented", "vs base (cad mean)", "Default"]
    if md:
        print("| " + " | ".join(hdr) + " |")
        print("|" + "---|" * len(hdr))
    for p in paths:
        s = summarize(p)
        if "cadMean" not in s:
            print(f"{s['label']}: no frame trace")
            continue
        delta = f"{s['cadMean'] - bmean:+.3f}" if bmean is not None else "-"
        cells = [s["label"], (s["skip"] or "-") + (" " + s["env"] if s["env"] else ""), f"{s['median']:.0f}", f"{s['avg']:.0f}",
                 f"{s['p1']:.0f}", f"{s['cadP50']:.3f}", f"{s['cadMean']:.3f}", f"{s['cadP99']:.3f}", f"{s['ownMean']:.3f}",
                 f"{s['ownPres']:.3f}", f"{s['ownSkip']:.3f}", f"{100 * s['presShare']:.0f}%", delta,
                 "yes" if s["default"] and s["fb"] == "3024x1964" and s["rd"] == 12 else "NO"]
        print(("| " + " | ".join(cells) + " |") if md else "  ".join(cells))


if __name__ == "__main__":
    main()
