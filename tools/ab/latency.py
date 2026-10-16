#!/usr/bin/env python3
"""Input-sample age from a frame-trace result (round 5, late throttle).

For each frame k (row k = interval ending at renderFrame(k) entry):
  poll time:
    late throttle on and the throttle wait in the bundle blocked (bundleWaitNs > 5 us): the re-pump right after the wait,
      approximated as t[k] - texTickNs[k]  (texture tick + sound/mouse follow the wait; sound/mouse ignored, tens of us)
    otherwise: the loop-top RenderSystem.pollEvents before the bundle: t[k] - bundleNs[k]
  commit time of frame k: commitNs of row k+1 (frame k is committed inside renderFrame(k), i.e. in interval k+1)
  age_commit = commit - poll           (mouse look sample -> CPU hands the frame to the GPU)
  age_gpu    = gpuEnd(row k+1) - poll  (-> GPU finished the frame; present/scanout come after, same in both arms)
Prints mean / p50 / p99 / max per file, for all frames and for tick frames.
"""
import json, sys


def pct(v, p):
    s = sorted(v)
    return s[min(len(s) - 1, int(round(p * (len(s) - 1))))] if s else float("nan")


def rows(d):
    ft = d["frameTrace"]
    cols = ft["columns"]
    data = ft["rows"] if "rows" in ft else ft["data"]
    if isinstance(data, dict):
        return {c: data[c] for c in cols}
    # list of rows or columnar list
    if data and isinstance(data[0], list) and len(data) == len(cols):
        return {c: data[i] for i, c in enumerate(cols)}
    return {c: [r[i] for r in data] for i, c in enumerate(cols)}


def main():
    for path in sys.argv[1:]:
        d = json.load(open(path))
        r = rows(d)
        n = len(r["tNs"])
        lt = bool((d.get("lateThrottle") or {}).get("active"))
        ac, ag, act, agt = [], [], [], []
        npump = 0
        for k in range(n - 1):
            t = r["tNs"][k]
            bw = r["bundleWaitNs"][k]
            if lt and bw is not None and bw > 5000:
                poll = t - max(0, r["texTickNs"][k])
                npump += 1
            else:
                poll = t - max(0, r["bundleNs"][k])
            c = r["commitNs"][k + 1]
            g = r["gpuEndNs"][k + 1]
            if c is None or c <= 0:
                continue
            a = (c - poll) / 1e6
            ac.append(a)
            tick = r["ticks"][k] > 0
            if tick:
                act.append(a)
            if g is not None and g > 0:
                ag.append((g - poll) / 1e6)
                if tick:
                    agt.append((g - poll) / 1e6)
        def s(v):
            return f"mean {sum(v)/len(v):.3f} p50 {pct(v,.5):.3f} p99 {pct(v,.99):.3f} max {max(v):.3f} (n={len(v)})" if v else "n/a"
        print(f"{path.split('/')[-1]}: lt={lt} pumpedAfterWait={npump}/{n}")
        print(f"  poll->commit all : {s(ac)}")
        print(f"  poll->commit tick: {s(act)}")
        print(f"  poll->GPU end all: {s(ag)}")
        print(f"  poll->GPU end tick: {s(agt)}")


if __name__ == "__main__":
    main()
