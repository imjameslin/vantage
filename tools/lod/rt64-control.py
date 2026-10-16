#!/usr/bin/env python3
"""Small nested/holey voxel fixture for independent CPU-vs-GPU hit checks."""
from pathlib import Path
import json
import random
import struct

r = random.Random(512)
out = Path('build/lod-rt64/control-nested')
out.mkdir(parents=True, exist_ok=True)
nodes = [(0, 0, 0)]


def fill(index, depth):
    bits = [i for i in range(64) if r.random() < (.32 if depth == 0 else .08)]
    if not bits or depth == 3:
        nodes[index] = (0, 0, index + 1)
        return
    base = len(nodes)
    nodes.extend([(0, 0, 0)] * len(bits))
    nodes[index] = (sum(1 << i for i in bits), base, index + 1)
    for j in range(len(bits)):
        if r.random() < .6:
            fill(base + j, depth + 1)
        else:
            nodes[base + j] = (0, 0, base + j + 1)


fill(0, 0)
(out/'tree.bin').write_bytes(b''.join(struct.pack('<QII', *n) for n in nodes))
(out/'tree.json').write_text(json.dumps(dict(origin=[-128, 0, -256], size=256,
    boundsMin=[-128, 0, -256], boundsMax=[128, 256, 0], nodeCount=len(nodes),
    representativesQualified=False)) + '\n')
print(out, len(nodes), 'nodes')
materials = []
compact = list(nodes)
for i, (mask, base, mat) in enumerate(nodes):
    children = nodes[base:base+bin(mask).count('1')] if mask else []
    if children and all(n[0] == 0 for n in children):
        compact[i] = (mask, 0x80000000 | len(materials), mat)
        materials.extend(n[2] for n in children)
(out/'compact.bin').write_bytes(b''.join(struct.pack('<QII', *n) for n in compact))
(out/'compact-materials.bin').write_bytes(struct.pack('<%dI' % len(materials), *materials))
meta = json.loads((out/'tree.json').read_text())
meta['leafMaterialCount'] = len(materials)
(out/'compact.json').write_text(json.dumps(meta)+'\n')
