#!/usr/bin/env python3
"""Short Step1 checkpoint; choose one configuration by its worst path p99."""
import argparse
import json
from pathlib import Path

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('result', type=Path)
p.add_argument('--raster', type=Path)
a = p.parse_args()
r = json.loads(a.result.read_text())
rows = r['rows']
variants = sorted({x['variant'] for x in rows if x['variant'] != 'unmasked'})
by = {(x['variant'], x['path']): x for x in rows}
paths = ('standing', 'turn', 'translation')
assert all((v, k) in by for v in variants for k in paths), 'Incomplete paths'
worst = {v: max(by[v, k]['gpu']['p99Ms'] for k in paths) for v in variants}
best = min(variants, key=lambda v: (worst[v], max(by[v, k]['gpu']['maxMs'] for k in paths)))
score = worst[best]
verdict = 'KILL' if score > 1.5 else 'ELIGIBLE FOR STEP2, NOT STARTED' if score < .7 else 'REPORT TO USER'
if r['samplesPerPath'] < 100:
    verdict = 'SHORT SCREEN ONLY; NOT A PRECISE P99 CHECKPOINT'
print(f'{verdict}. Configuration: {best}. No game FPS or image-quality pass claimed.\n')
print('| Path | p99 ms | Max ms | Median ms | Steps/unmasked pixel |')
print('|---|---:|---:|---:|---:|')
for k in paths:
    x = by[best, k]
    steps = [s['work']['stepsPerUnmaskedPixel'] for s in x['workSamples']]
    print(f"| {k} | {x['gpu']['p99Ms']:.3f} | {x['gpu']['maxMs']:.3f} | {x['gpu']['medianMs']:.3f} | {min(steps):.1f}–{max(steps):.1f} |")
print(f"\nTree: {r['treeBytes']/2**20:.1f} MiB; total allocated Metal: {r['MetalAllocatedBytes']/2**20:.1f} MiB.")
print('\n| Ablation | Worst path p99 ms |')
print('|---|---:|')
for v in variants:
    print(f'| {v} | {worst[v]:.3f} |')
if a.raster:
    b = json.loads(a.raster.read_text())['gpu']
    print(f"\nOriginal shaded raster, captured standing view, unmasked: p99 {b['p99Ms']:.3f}, max {b['maxMs']:.3f}, median {b['medianMs']:.3f} ms.")
    u = by['unmasked', 'standing']['gpu']
    print(f"Sparse64 raw hits, same view unmasked: p99 {u['p99Ms']:.3f}, max {u['maxMs']:.3f}, median {u['medianMs']:.3f} ms. These are different shading workloads.")
