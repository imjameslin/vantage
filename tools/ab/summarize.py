#!/usr/bin/env python3
"""Markdown tables for a set of runs: summarize.py <prefix> <arm,arm,...> [baseline-arm]
Reads results/raw/<prefix>-<arm>-<n>.json in run order (from r5ab-runs.tsv, or $RUNS_TSV)."""
import json, os, sys, statistics as st

WT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
prefix, arms = sys.argv[1], sys.argv[2].split(";" if ";" in sys.argv[2] else ",")
base = sys.argv[3] if len(sys.argv) > 3 else arms[0]
os.chdir(WT)
order = []
for line in open(os.environ.get("RUNS_TSV", "results/raw/r5ab-runs.tsv")):
    f = line.rstrip("\n").split("\t")
    if len(f) > 3 and f[2].startswith(prefix + "-") and f[3] in arms:
        order.append((f[2], f[3], f[-1]))
KEYS = [("medianFps", "median"), ("avgFps", "avg"), ("p1LowFps", "1% low"), ("p01LowFps", "0.1% low"),
        ("p99FrameMs", "p99 ms"), ("maxFrameMs", "max ms")]
per = {a: [] for a in arms}
print("| # | run | arm | RD | " + " | ".join(k[1] for k in KEYS) + " | presented fps | notes |")
print("|---|---|---|---|" + "---|" * len(KEYS) + "---|---|")
seen = set()
for i, (label, arm, notes) in enumerate(order, 1):
    if label in seen:
        continue
    seen.add(label)
    try:
        d = json.load(open(f"results/raw/{label}.json"))
    except Exception:
        print(f"| {i} | {label} | {arm} | - | no JSON |"); continue
    per[arm].append(d)
    lt = d.get("lateThrottle") or {}
    extra = ""
    if lt.get("enabled"):
        extra = f"LT trips {lt.get('tripFrames')}/{lt.get('frames')} frames, blocked {lt.get('blockedWaits')}"
    print(f"| {i} | {label} | {arm} | {d.get('renderDistance'):.0f} | " +
          " | ".join((f"{d.get(k,0):.1f}" if 'Ms' in k else f"{d.get(k,0):.0f}") for k, _ in KEYS) +
          f" | {(d.get('present') or {}).get('presentedFps',0):.1f} | {extra} |")
print()
print("| arm | n | " + " | ".join(k[1] for k in KEYS) + " | median vs " + base + " | avg vs " + base + " | 1% low vs " + base + " | 0.1% low vs " + base + " |")
print("|---|---|" + "---|" * len(KEYS) + "---|---|---|---|")
means = {}
for a in arms:
    ds = per[a]
    if not ds:
        continue
    means[a] = {k: st.mean(d.get(k, 0) for d in ds) for k, _ in KEYS}
for a in arms:
    if a not in means:
        continue
    m = means[a]; b = means.get(base)
    rel = lambda k: f"{(m[k]/b[k]-1)*100:+.1f}%" if b else ""
    print(f"| {a} | {len(per[a])} | " + " | ".join((f"{m[k]:.2f}" if 'Ms' in k else f"{m[k]:.1f}") for k, _ in KEYS) +
          f" | {rel('medianFps')} | {rel('avgFps')} | {rel('p1LowFps')} | {rel('p01LowFps')} |")
