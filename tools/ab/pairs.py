#!/usr/bin/env python3
"""Paired comparisons: pairs.py "<title>" base1:cand1 base2:cand2 ...   (labels of results/raw/<label>.json)
Per pair: % change of median / avg / 1% low / 0.1% low and the max frame (ms); then the mean change, the number of pairs
where the candidate is better, and the paired t statistic of the % changes (max frame: lower is better)."""
import json, math, os, statistics as st, sys

WT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
os.chdir(WT)
title, pairs = sys.argv[1], [p.split(":") for p in sys.argv[2:]]
K = [("medianFps", "median"), ("avgFps", "avg"), ("p1LowFps", "p1Low"), ("p01LowFps", "p01Low")]
print(title)
ch = {k: [] for k, _ in K}
mx = []
for a, b in pairs:
    da, db = json.load(open(f"results/raw/{a}.json")), json.load(open(f"results/raw/{b}.json"))
    parts = []
    for k, n in K:
        c = (db[k] / da[k] - 1) * 100
        ch[k].append(c)
        parts.append(f"{n} {c:+.1f}%")
    mx.append((da["maxFrameMs"], db["maxFrameMs"]))
    print(f"  {a:22s} -> {b:22s} " + " ".join(parts) + f"  max {da['maxFrameMs']:.1f} -> {db['maxFrameMs']:.1f} ms")


def t(v):
    return st.mean(v) / (st.stdev(v) / math.sqrt(len(v))) if len(v) > 1 and st.stdev(v) > 0 else float("nan")


print("  mean: " + " ".join(f"{n} {st.mean(ch[k]):+.1f}% (better {sum(1 for c in ch[k] if c > 0)}/{len(ch[k])}, t={t(ch[k]):.2f})"
                            for k, n in K))
print(f"  max frame: mean {st.mean(a for a, _ in mx):.1f} -> {st.mean(b for _, b in mx):.1f} ms, median "
      f"{st.median(a for a, _ in mx):.1f} -> {st.median(b for _, b in mx):.1f} ms, lower in {sum(1 for a, b in mx if b < a)}/{len(mx)}")
