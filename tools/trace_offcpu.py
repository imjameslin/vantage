#!/usr/bin/env python3
"""Off-CPU time of the render thread in the slow frames of a frame-trace CSV (tools/frame_trace.py --csv): for each
frame, offCpu = interval - render-thread CPU - intended waits (GPU / drawable components). A frame whose render thread was
descheduled (CPU contention: workers, integrated server) or blocked on a lock shows a large offCpu with no GPU wait.
Prints, for the slowest 1 % and 0.1 % and for all frames: mean interval, mean CPU, mean offCpu, and the share of frames
with offCpu > 0.5 / 1 ms; then which phase (packets, tick, levelUpdate, extract, render) holds the largest wall-CPU gap.

usage: trace_offcpu.py TRACE.csv [...]
"""
import csv
import sys


def rows(path):
    with open(path) as f:
        for r in csv.DictReader(f):
            yield {k: float(v) if v not in ("", None) and k not in ("attribution", "presentKind", "fuseFallback", "resultFlags") else v
                   for k, v in r.items()}


def summary(name, fr):
    if not fr:
        return
    n = len(fr)
    dt = sum(r["dtNs"] for r in fr) / n / 1e6
    cpu = sum(r["frameCpuNs"] for r in fr) / n / 1e6
    off = [max(0.0, r["dtNs"] - r["frameCpuNs"] - (r["c_gpu"] + r["c_drawable"]) * 1e6) / 1e6 for r in fr]
    o = sum(off) / n
    s5 = sum(1 for x in off if x > 0.5) / n * 100
    s1 = sum(1 for x in off if x > 1.0) / n * 100
    print(f"  {name:12s} n {n:6d}  interval {dt:6.3f}  cpu {cpu:6.3f}  offCpu {o:6.3f} ms  offCpu>0.5ms {s5:5.1f} %  >1ms {s1:5.1f} %")


def main(paths):
    for p in paths:
        fr = list(rows(p))
        fr.sort(key=lambda r: -r["dtNs"])
        n = len(fr)
        print(p)
        summary("all", fr)
        summary("slowest 1%", fr[:max(1, n // 100)])
        summary("slowest 0.1%", fr[:max(1, n // 1000)])
        # bundle (tick + packets) wall vs CPU in the slowest 1 %
        top = fr[:max(1, n // 100)]
        bw = sum(r["bundleNs"] for r in top) / len(top) / 1e6
        bc = sum(r["bundleCpuNs"] for r in top) / len(top) / 1e6
        print(f"  slowest 1%: bundle wall {bw:.3f} cpu {bc:.3f} ms; packets {sum(r['packetsNs'] for r in top) / len(top) / 1e6:.3f}"
              f" tick {sum(r['tickNs'] for r in top) / len(top) / 1e6:.3f} levelUpdate {sum(r['levelUpdateNs'] for r in top) / len(top) / 1e6:.3f}"
              f" extract {sum(r['extractNs'] for r in top) / len(top) / 1e6:.3f} render {sum(r['renderNs'] for r in top) / len(top) / 1e6:.3f} ms")


if __name__ == "__main__":
    main(sys.argv[1:])
