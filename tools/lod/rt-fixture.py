#!/usr/bin/env python3
"""Export a bounded contiguous patch of actual cached SOLID faces for an RT cost probe.

This preserves every included quad's four positions/diagonal; it is not a complete
512-chunk scene, appearance test, or game benchmark. CUTOUT/translucency are omitted.
The original cache is read-only. Output contains float4 vertices, no shading data.
"""
import argparse
from collections import Counter
import hashlib
import importlib.util
import json
import math
from pathlib import Path
import struct

spec = importlib.util.spec_from_file_location('mesh_potential', Path(__file__).with_name('mesh-potential.py'))
mesh = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mesh)


def positions(origin, compact, data, q):
    if not compact:
        return [tuple(origin[i] + p[i] for i in range(3))
                for p in (struct.unpack_from('<3f', data, q * 112 + k * 28) for k in range(4))]
    pos, edge = struct.unpack_from('<2I', data, q * 48)
    a, b = edge & 3, (edge >> 14) & 3
    if a == 3 or b == 3:
        return None  # Camera-facing billboards require their render-time expansion.
    if a == b or ((edge >> 28) & 7) != origin[3]:
        raise ValueError('invalid compact geometry/section level')
    la, lb = mesh.signed(edge >> 2, 12), mesh.signed(edge >> 16, 12)
    unit = (1 << origin[3]) / 32.0
    start = [(pos >> (10 * i)) & 1023 for i in range(3)]
    out = []
    for k in range(4):
        p = start.copy()
        if k in (1, 2): p[a] += la
        if k >= 2: p[b] += lb
        out.append(tuple(origin[i] + p[i] * unit for i in range(3)))
    return out


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('cache', type=Path)
    p.add_argument('out', type=Path)
    p.add_argument('--level', type=int, default=0)
    p.add_argument('--nodes', type=int, nargs=4, default=(-16, 16, -32, -12), metavar=('X0', 'X1', 'Z0', 'Z1'))
    p.add_argument('--max-quads', type=int, default=2_000_000)
    a = p.parse_args()
    if a.out.exists() or a.out.with_suffix('.json').exists(): p.error('output already exists')
    if not 0 <= a.level <= 7 or not 0 < a.max_quads <= 2_000_000: p.error('invalid bounds')
    x0, x1, z0, z1 = a.nodes
    if x0 >= x1 or z0 >= z1: p.error('empty bounds')
    a.out.parent.mkdir(parents=True, exist_ok=True)
    counts, blobs, count = Counter(), [], 0
    lo, hi = [math.inf] * 3, [-math.inf] * 3
    camera, fov, yaw, pitch = (6.5, 130.0, 6.5), 70.0, 200.0, 8.0
    with a.out.open('xb') as output:
        output.write(bytes(64))
        for path in sorted((a.cache / ('L' + str(a.level))).glob('m.*.bin')):
            with path.open('rb') as f:
                header = f.read(272)
                if len(header) != 272: raise ValueError('truncated region')
                magic, level, rx, rz, *lengths = struct.unpack('>68i', header)
                if magic != 0x4c4d4331 or level != a.level: raise ValueError('region identity')
                if rx * 8 >= x1 or (rx + 1) * 8 <= x0 or rz * 8 >= z1 or (rz + 1) * 8 <= z0: continue
                offset = 272
                for slot, length in enumerate(lengths):
                    nx, nz = rx * 8 + slot % 8, rz * 8 + slot // 8
                    if length < 0: raise ValueError('negative entry length')
                    if length and x0 <= nx < x1 and z0 <= nz < z1:
                        f.seek(offset); blob = f.read(length)
                        if len(blob) != length: raise ValueError('truncated entry')
                        blobs.append({'node': [level, nx, nz], 'sha256': hashlib.sha256(blob).hexdigest()})
                        counts['nodes'] += 1
                        for origin, layer, compact, n, data in mesh.entry_layers(blob):
                            counts[f'source_layer{layer}_{"compact" if compact else "block"}_quads'] += n
                            if layer != 0: continue
                            vertices = bytearray()
                            for q in range(n):
                                corners = positions(origin, compact, data, q)
                                if corners is None:
                                    counts['omitted_solid_billboards'] += 1
                                    continue
                                if any(not math.isfinite(v) for c in corners for v in c): raise ValueError('nonfinite position')
                                count += 1
                                if count > a.max_quads: raise ValueError('complete patch exceeds cap; choose smaller bounds, do not truncate silently')
                                for corner in corners:
                                    vertices += struct.pack('<4f', *corner, 1.0)
                                    for i, v in enumerate(corner): lo[i], hi[i] = min(lo[i], v), max(hi[i], v)
                            output.write(vertices)
                    offset += length
        if count == 0: raise ValueError('no solid faces')
        header = struct.pack('<4s3I12f', b'LRT1', 1, count, 0, *camera, fov, yaw, pitch, *lo, *hi)
        assert len(header) == 64
        output.seek(0); output.write(header)
    assert a.out.stat().st_size == 64 + 64 * count
    with a.out.open('rb') as f:
        sha = hashlib.sha256()
        for b in iter(lambda: f.read(1 << 20), b''): sha.update(b)
    info = {'kind': 'bounded SOLID geometry subset, not full-scene quality or gameplay FPS',
            'cache': str(a.cache), 'level': a.level, 'node_bounds': list(a.nodes), 'nodes': blobs,
            'counts': dict(counts), 'quads': count, 'triangles': 2 * count,
            'vertices': 4 * count, 'bytes': a.out.stat().st_size, 'sha256': sha.hexdigest(),
            'camera': camera, 'fov_y': fov, 'yaw': yaw, 'pitch': pitch, 'bounds': [lo, hi],
            'omitted': ['CUTOUT including leaves/billboards', 'TRANSLUCENT including water',
                        'shading, texture alpha/discards, blending', 'all other nodes and LOD levels',
                        'near vanilla terrain/entities', 'streaming/mesh/AS updates'],
            'format': 'LRT1 little endian 64-byte header; 4 float4 positions per quad, triangles 012/230'}
    a.out.with_suffix('.json').write_text(json.dumps(info, indent=2) + '\n')
    print(json.dumps({k: info[k] for k in ['quads', 'triangles', 'bytes', 'sha256', 'counts', 'bounds', 'omitted']}, indent=2))


if __name__ == '__main__': main()
