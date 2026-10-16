#!/usr/bin/env python3
"""Round 9 budget helper: per abab result, the two arms' two-frame median as frame time (ms), the difference A - B (ms of
frame time removed by arm B), and GPU busy medians. usage: abab_ms.py results/raw/X.json [...] [--md]"""
import json, sys

md = '--md' in sys.argv
paths = [a for a in sys.argv[1:] if a != '--md']
if md:
    print('| run | A ms | B ms | A - B ms | A GPU cadence p50 | B GPU cadence p50 |')
    print('|---|---|---|---|---|---|')
for p in paths:
    d = json.load(open(p))
    if 'abab' not in d:
        continue
    arms = d['abab']['arms']
    a, b = (arms[0], arms[1]) if isinstance(arms, list) else (arms['A'], arms['B'])
    ma, mb = 1000.0 / a['median2Fps'], 1000.0 / b['median2Fps']
    ga, gb = a.get('gpuCadenceP50Ms'), b.get('gpuCadenceP50Ms')
    lab = d.get('label', p)
    if md:
        print(f"| {lab} | {ma:.3f} | {mb:.3f} | {ma - mb:+.3f} | {ga} | {gb} |")
    else:
        print(f"{lab:22s} A {ma:.3f} ms  B {mb:.3f} ms  A-B {ma - mb:+.3f} ms  gpu {ga} / {gb}")
