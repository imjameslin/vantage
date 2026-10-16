#!/usr/bin/env python3
"""GPU time per yaw bin of static spin runs with a frame trace (results/GPU-HEAVY.md).

Reads the per-frame CSV of tools/frame_trace.py (`--csv`) for one or more runs, pools them and prints per bin (default
15 degrees): frames, CPU interval p50 / p99, GPU busy p50 / mean / p99 (span of the frame's command buffers, from the
Metal trace ring), GPU cadence p50 / p99 (GPU end to GPU end of consecutive frames: the rate the GPU delivers frames,
the quantity the 1 % low follows when the run is GPU-bound), and the share of the run's slowest 1 % of frames in the bin.
Then the heaviest bins and the whole-run figures.

usage: yaw_bins.py <csv> [<csv> ...] [--width 15] [--md]
"""
import csv, math, sys


def pct(xs, q):
    if not xs:
        return float("nan")
    xs = sorted(xs)
    i = min(len(xs) - 1, max(0, int(math.ceil(q * len(xs))) - 1))
    return xs[i]


def load(path):
    rows = list(csv.DictReader(open(path)))
    out = []
    prev_end = None
    for r in rows:
        dt = float(r["dtNs"]) / 1e6
        busy = float(r["gpuBusyNs"]) / 1e6 if float(r["gpuBusyNs"]) >= 0 else None
        end = float(r["gpuEndNs"]) if float(r["gpuEndNs"]) > 0 else None
        cad = (end - prev_end) / 1e6 if end is not None and prev_end is not None and end > prev_end else None
        if end is not None:
            prev_end = end
        yaw = (float(r["view"]) / 1000.0) % 360.0
        out.append((yaw, dt, busy, cad))
    return out


def main():
    args = sys.argv[1:]
    width = 15.0
    md = "--md" in args
    if "--width" in args:
        width = float(args[args.index("--width") + 1])
        del args[args.index("--width"):args.index("--width") + 2]
    paths = [a for a in args if not a.startswith("--")]
    frames = []
    slow = set()
    for p in paths:
        fs = load(p)
        cut = pct([f[1] for f in fs], 0.99)
        base = len(frames)
        slow |= {base + i for i, f in enumerate(fs) if f[1] >= cut}
        frames += fs
    nb = int(round(360 / width))
    rows = []
    for b in range(nb):
        ks = [k for k, f in enumerate(frames) if int(f[0] // width) == b]
        dts = [frames[k][1] for k in ks]
        bs = [frames[k][2] for k in ks if frames[k][2] is not None]
        cs = [frames[k][3] for k in ks if frames[k][3] is not None]
        rows.append((b * width, len(ks), pct(dts, .5), pct(dts, .99), pct(bs, .5), sum(bs) / max(1, len(bs)), pct(bs, .99),
                     pct(cs, .5), pct(cs, .99), sum(1 for k in ks if k in slow)))
    hdr = ["yaw", "frames", "CPU p50", "CPU p99", "GPU busy p50", "GPU busy mean", "GPU busy p99", "GPU cad p50",
           "GPU cad p99", "slowest 1 %"]
    if md:
        print("| " + " | ".join(hdr) + " |")
        print("|" + "---|" * len(hdr))
    else:
        print("  ".join(f"{h:>13s}" for h in hdr))
    for r in rows:
        cells = [f"{r[0]:.1f}-{r[0] + width:.1f}", str(r[1])] + [f"{x:.3f}" for x in r[2:9]] + [str(r[9])]
        print(("| " + " | ".join(cells) + " |") if md else "  ".join(f"{c:>13s}" for c in cells))
    allb = [f[2] for f in frames if f[2] is not None]
    allc = [f[3] for f in frames if f[3] is not None]
    print()
    print(f"runs {len(paths)}, frames {len(frames)}: CPU p50 {pct([f[1] for f in frames], .5):.3f} p99 {pct([f[1] for f in frames], .99):.3f} ms; "
          f"GPU busy p50 {pct(allb, .5):.3f} mean {sum(allb) / len(allb):.3f} p99 {pct(allb, .99):.3f} ms; "
          f"GPU cadence p50 {pct(allc, .5):.3f} p99 {pct(allc, .99):.3f} ms")
    top = sorted(rows, key=lambda r: -r[5])[:4]
    print("heaviest bins by mean GPU busy: " + ", ".join(f"{r[0]:.1f}-{r[0] + width:.1f} ({r[5]:.3f} ms, slow1% {r[9]})" for r in top))
    med = sorted(rows, key=lambda r: r[5])[len(rows) // 2]
    print(f"median bin: {med[0]:.1f}-{med[0] + width:.1f} ({med[5]:.3f} ms)")


if __name__ == "__main__":
    main()
