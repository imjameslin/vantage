#!/usr/bin/env python3
"""Which frames make the GPU tail (results/GPU-HEAVY.md 2). Per bench JSON with a frame trace (Metal):

- tick frames = frames with more encoders than the usual 2 (the frame after a client tick carries the texture-animation
  and lightmap passes): share, extra GPU cadence over the other frames, how many of the slowest 1 % they are;
- presented frames (present kind fused / present pass): extra GPU cadence over not-presented frames (tick frames excluded);
- what-if: the GPU-cadence p99 (which the 1 % low follows in a GPU-bound run) with the tick frames' extra removed, then
  also half / all of the presented frames' extra, as 1 % low equivalents.

usage: tail_split.py <json> [<json> ...]
"""
import json, math, statistics, sys


def pct(xs, q):
    xs = sorted(xs)
    return xs[min(len(xs) - 1, max(0, int(math.ceil(q * len(xs))) - 1))]


def main():
    for path in sys.argv[1:]:
        d = json.load(open(path))
        ft = d['frameTrace']
        ix = {c: i for i, c in enumerate(ft['columns'])}
        R = ft['rows']
        g = lambda k, c: R[k][ix[c]]
        ks, cad, tick, pres = [], {}, {}, {}
        for k in range(1, len(R)):
            e, e0 = g(k, 'gpuEndNs'), g(k - 1, 'gpuEndNs')
            if e > 0 and e0 > 0 and e > e0:
                ks.append(k)
                cad[k] = (e - e0) / 1e6
                tick[k] = g(k, 'encoders') > 2
                pres[k] = g(k, 'presentKind') in (1, 2)
        m = lambda f: statistics.mean(cad[k] for k in ks if f(k))
        tx = m(lambda k: tick[k]) - m(lambda k: not tick[k])
        px = m(lambda k: pres[k] and not tick[k]) - m(lambda k: not pres[k] and not tick[k])
        cut = pct([cad[k] for k in ks], .99)
        slow = [k for k in ks if cad[k] >= cut]
        variants = [
            ("measured", lambda k: cad[k]),
            ("tick work free", lambda k: cad[k] - (tx if tick[k] else 0)),
            ("+ half presents", lambda k: cad[k] - (tx if tick[k] else 0) - (px if pres[k] and not tick[k] and k % 2 else 0)),
            ("+ presents free", lambda k: cad[k] - (tx if tick[k] else 0) - (px if pres[k] and not tick[k] else 0)),
        ]
        print(f"{d.get('label')}: tick frames {100 * sum(tick.values()) / len(ks):.1f} % (+{tx:.3f} ms, {sum(tick[k] for k in slow)} of "
              f"{len(slow)} slowest 1 %), presented {100 * sum(pres.values()) / len(ks):.0f} % (+{px:.3f} ms)")
        for name, f in variants:
            xs = [f(k) for k in ks]
            print(f"  {name:16s} GPU cadence p99 {pct(xs, .99):.3f} ms (1 % low ~{1000 / pct(xs, .99):.0f}), mean {statistics.mean(xs):.3f} ms")


if __name__ == '__main__':
    main()
