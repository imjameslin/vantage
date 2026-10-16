#!/usr/bin/env python3
"""Markdown table of frame-trace runs: trace_table.py label...  (results/raw/<label>.json)"""
import json, os, subprocess, sys, statistics as st

WT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
os.chdir(WT)
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import latency


def pct(v, p):
    s = sorted(v)
    return s[min(len(s) - 1, int(round(p * (len(s) - 1))))] if s else float("nan")


print("| run | 1% low | CPU p99 ms | GPU cadence p99 ms | present p99 ms | tick share of slowest 1% | slowest 1%: tick / gpu / render / other | GPU starved frames | tick-bundle CPU p50 ms (tick frames) | tick() + texTick mean us (tick frames) | blocks created / 1000 frames | poll->commit mean / p99 ms | poll->GPU end mean / p99 ms |")
print("|---|---|---|---|---|---|---|---|---|---|---|---|---|")
for label in sys.argv[1:]:
    path = f"results/raw/{label}.json"
    d = json.load(open(path))
    j = json.loads(subprocess.run(["python3", "tools/frame_trace.py", path, "--json"], capture_output=True, text=True).stdout)
    r = latency.rows(d)
    n = len(r["tNs"])
    lt = bool((d.get("lateThrottle") or {}).get("active"))
    tickus = [(r["tickNs"][k] + max(0, r["texTickNs"][k])) / 1e3 for k in range(n) if r["ticks"][k] > 0]
    blocks = sum(max(0, b) for b in r["blocksCreated"]) / n * 1000
    ac, ag = [], []
    for k in range(n - 1):
        t = r["tNs"][k]
        bw = r["bundleWaitNs"][k]
        poll = t - max(0, r["texTickNs"][k]) if (lt and bw is not None and bw > 5000) else t - max(0, r["bundleNs"][k])
        c, g = r["commitNs"][k + 1], r["gpuEndNs"][k + 1]
        if c and c > 0:
            ac.append((c - poll) / 1e6)
        if g and g > 0:
            ag.append((g - poll) / 1e6)
    a1 = j["slowest1"]["attribution"]
    print(f"| {label} | {d['p1LowFps']:.0f} | {j['cpu']['p99Ms']:.3f} | {j['gpuCadence']['p99Ms']:.3f} | {j['presentCadence']['p99Ms']:.2f} | "
          f"{j['tickShareSlowest1']*100:.1f}% | {a1['tick']} / {a1['gpu']} / {a1['render']} / {a1['other']+a1['submit']+a1['drain']+a1['drawable']} | "
          f"{j['starvedFrames']} | {j['tickBundleWallVsCpu']['cpuP50Ms']:.3f} | {st.mean(tickus):.0f} | {blocks:.1f} | "
          f"{st.mean(ac):.3f} / {pct(ac,.99):.3f} | {st.mean(ag):.3f} / {pct(ag,.99):.3f} |")
