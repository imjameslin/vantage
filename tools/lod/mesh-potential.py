#!/usr/bin/env python3
"""Read-only audit of additional rectangle merging in the existing LOD mesh cache.

This counts resident geometry, NOT visible geometry or FPS. Existing non-unit
rectangles, BLOCK fallback layers, billboards and overlapping faces are kept.
The hypothetical merged path retains each cell's color/light/UV orientation;
it must reconstruct the original two-triangle interpolation in the fragment
shader. Counts establish geometry potential, not image or performance parity.
No cache entries or game files are modified.
"""
import argparse
from collections import Counter, defaultdict
import hashlib
import json
import math
from pathlib import Path
import struct
import time
import zlib


class Reader:
    def __init__(self, data):
        self.data, self.at = data, 0

    def take(self, n):
        if n < 0 or n > len(self.data) - self.at:
            raise ValueError('truncated mesh entry')
        out = self.data[self.at:self.at+n]
        self.at += n
        return out

    def integer(self):
        return struct.unpack('>i', self.take(4))[0]

    def flag(self):
        v = self.take(1)[0]
        if v > 1:
            raise ValueError('invalid boolean')
        return bool(v)


def entry_layers(blob):
    if len(blob) < 24:
        raise ValueError('short compressed entry')
    size = struct.unpack_from('>i', blob, 20)[0]
    if not 12 <= size <= 128 << 20:
        raise ValueError('invalid inflated size')
    dec = zlib.decompressobj()
    data = dec.decompress(blob[24:], size + 1)
    if len(data) != size or not dec.eof or dec.unused_data or dec.unconsumed_tail:
        raise ValueError('invalid compressed length/trailer')
    r = Reader(data)
    lo, hi = struct.unpack('>ff', r.take(8))
    n = r.integer()
    if not math.isfinite(lo + hi) or lo > hi or not 0 <= n <= 256:
        raise ValueError('invalid section header')
    for _ in range(n):
        origin = struct.unpack('>4i', r.take(16))
        if not 0 <= origin[3] <= 7:
            raise ValueError('invalid section level')
        for layer in range(3):
            if not r.flag():
                continue
            compact, quads, length = r.flag(), r.integer(), r.integer()
            if quads < 0 or length != quads * (48 if compact else 112):
                raise ValueError('invalid geometry length')
            vertices = r.take(length)
            count = r.integer()
            if count < -1 or count > quads * 8:
                raise ValueError('invalid cluster size')
            if count >= 0:
                r.take(count * 4)
            yield origin, layer, compact, quads, vertices
    if r.at != len(data):
        raise ValueError('trailing mesh entry data')


def signed(value, bits):
    return (value & ((1 << (bits - 1)) - 1)) - (value & (1 << (bits - 1)))


def rectangle(q):
    """Return plane/facing, quantized bounds and 2-D axes, or None for billboards."""
    edge = q[1]
    a, b = edge & 3, (edge >> 14) & 3
    if a == 3 or b == 3 or a == b:
        return None
    la, lb = signed(edge >> 2, 12), signed(edge >> 16, 12)
    if not la or not lb:
        return None
    p = [(q[0] >> (10 * k)) & 1023 for k in range(3)]
    normal = 3 - a - b
    u, v = (normal + 1) % 3, (normal + 2) % 3
    facing = (1 if (a + 1) % 3 == b else -1) * (1 if la * lb > 0 else -1)
    end = p.copy()
    end[a] += la
    end[b] += lb
    return ((normal, p[normal], facing), min(p[u], end[u]), min(p[v], end[v]),
            max(p[u], end[u]), max(p[v], end[v]))


def material(q):
    """Regular tile mapping only; preserve its per-cell orientation in metadata."""
    d = [(signed(q[k], 16), signed(q[k] >> 16, 16)) for k in (10, 11)]
    if not ((d[0][1] == d[1][0] == 0 and d[0][0] and d[1][1]) or
            (d[0][0] == d[1][1] == 0 and d[0][1] and d[1][0])):
        return None
    lengths = [max(abs(x), abs(y)) for x, y in d]
    if lengths[0] != lengths[1] or lengths[0] % 256:
        return None
    tile = ((q[8] >> 20) & 255, (q[9] >> 20) & 255)
    uv0 = (q[8] & 1048575, q[9] & 1048575)
    if tile == (0, 0):
        low = [uv0[k] + min(0, d[0][k]) + min(0, d[1][k]) for k in (0, 1)]
        if lengths[0] != 256 or any(x % 256 for x in low):
            return None
        tile = tuple(x // 256 + 1 for x in low)
    elif 0 in tile or any(x % 256 for x in uv0):
        return None
    return tile + (lengths[0],)


def pack(cells):
    """Widest-run then full-row packing. Exact coverage, no holes filled."""
    remaining = set(cells)
    out = []
    for x, y in sorted(cells, key=lambda p: (p[1], p[0])):
        if (x, y) not in remaining:
            continue
        w = 1
        while (x + w, y) in remaining:
            w += 1
        h = 1
        while all((x + dx, y + h) in remaining for dx in range(w)):
            h += 1
        for dy in range(h):
            for dx in range(w):
                remaining.remove((x + dx, y + dy))
        out.append((x, y, w, h))
    return out


def audit_layer(compact, quads, raw, layer):
    counts = Counter(quads=quads, bytes=len(raw), layers=1)
    if not compact:
        counts['block_fallback'] = quads
        return counts
    counts['compact'] = quads
    if layer == 2:
        counts['translucent_kept'] = quads
        return counts
    coverage = defaultdict(Counter)
    candidates = []
    for q in struct.iter_unpack('<12I', raw):
        shape = rectangle(q)
        if shape is None:
            counts['billboard_or_nonrect'] += 1
            continue
        plane, x0, y0, x1, y1 = shape
        # Existing rectangles / partial faces block overlapping candidate cells.
        for y in range(y0 // 32, (y1 + 31) // 32):
            for x in range(x0 // 32, (x1 + 31) // 32):
                coverage[plane][x, y] += 1
        if x1 - x0 != 32 or y1 - y0 != 32 or x0 % 32 or y0 % 32:
            counts['nonunit_kept'] += 1
            continue
        counts['unit_geometry'] += 1
        mat = material(q)
        if mat is None:
            counts['nonregular_uv'] += 1
            continue
        flat_color = len(set(q[2:6])) == 1
        light = (q[6] & 65535, q[6] >> 16, q[7] & 65535, q[7] >> 16)
        flat_light = len(set(light)) == 1
        counts['unit_flat_color'] += flat_color
        counts['unit_flat_light'] += flat_light
        counts['unit_flat_both'] += flat_color and flat_light
        candidates.append((plane, (x0 // 32, y0 // 32), mat, q, light))
    groups = [defaultdict(set), defaultdict(set), defaultdict(set)]
    for plane, cell, mat, q, light in candidates:
        if coverage[plane][cell] != 1:
            counts['overlap_kept'] += 1
            continue
        counts['eligible'] += 1
        # (1) metadata for shading only, same material; (2) per-cell material too.
        groups[0][plane, mat].add(cell)
        groups[1][plane].add(cell)
        # Reference: same flat color/light and exact UV/corner ordering signature.
        if len(set(q[2:6])) == 1 and len(set(light)) == 1:
            orient = (q[1] & 0x0fffffff, *q[8:12])
            groups[2][plane, mat, q[2], light[0], orient].add(cell)
    for name, group in zip(('same_tile', 'any_tile', 'flat_attributes'), groups):
        for cells in group.values():
            for x, y, w, h in pack(cells):
                area = w * h
                if area < 2:
                    continue
                counts[name + '_saved_quads'] += area - 1
                counts[name + '_patches'] += 1
                counts[name + '_cells'] += area
                # Model only: 48 B patch + 32 B metadata per cell; no index/alignment overhead.
                counts[name + '_byte_saving_model'] += 48 * area - (48 + 32 * area)
                counts[name + '_patch_area_' + str(min(area, 16))] += 1
                if area >= 4:
                    counts[name + '_large_saved_quads'] += area - 1
                    counts[name + '_large_cells'] += area
                    counts[name + '_large_patches'] += 1
                    counts[name + '_large_byte_saving_model'] += 48 * area - (48 + 32 * area)
    return counts


def self_test():
    import random
    rng = random.Random(7129)
    for _ in range(100):
        cells = {(x, y) for y in range(-3, 12) for x in range(-3, 12) if rng.random() < 0.6}
        actual = []
        for x, y, w, h in pack(cells):
            actual += [(x + dx, y + dy) for dy in range(h) for dx in range(w)]
        assert set(actual) == cells and len(actual) == len(cells)
    assert pack({(x, y) for x in range(5) for y in range(3)}) == [(0, 0, 5, 3)]
    # Two adjacent top faces with different shading and flipped UV orientation.
    a = [0, 0 | (32 << 2) | ((2 | (32 << 2)) << 14), 1, 2, 3, 4, 0x123400, 0x563400,
         0, 0, 256, 256 << 16]
    b = a.copy(); b[0] = 32; b[2:6] = [9] * 4; b[8] = 256; b[10] = (-256) & 65535
    raw = struct.pack('<24I', *(a + b))
    c = audit_layer(True, 2, raw, 0)
    assert c['same_tile_saved_quads'] == 1 and c['flat_attributes_saved_quads'] == 0, c
    # Duplicate coverage must block a merge; a gap must never get filled.
    c = audit_layer(True, 3, raw + struct.pack('<12I', *a), 0)
    assert c['same_tile_saved_quads'] == 0, c
    b[0] = 64
    assert audit_layer(True, 2, struct.pack('<24I', *(a+b)), 0)['any_tile_saved_quads'] == 0
    print('Mesh potential self-check: coverage, holes, overlaps, shading and UV orientation pass', flush=True)


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('cache', type=Path, help='one fingerprint directory with L0/m.*.bin etc.')
    ap.add_argument('--nodes', type=int, default=64, help='deterministic sampled nonempty nodes per level')
    ap.add_argument('--out', type=Path, required=True)
    ap.add_argument('--solid-fixture', type=Path, help='export selected compact SOLID layers: level/layer/count headers and original 48-byte records')
    args = ap.parse_args()
    if args.nodes < 1 or args.out.exists() or args.solid_fixture and args.solid_fixture.exists():
        ap.error('nodes must be positive and outputs must not already exist')
    fixture = None
    if args.solid_fixture:
        args.solid_fixture.parent.mkdir(parents=True, exist_ok=True)
        fixture = args.solid_fixture.open('wb')
    self_test()
    started = time.monotonic()
    totals, records = defaultdict(Counter), []
    for level_dir in sorted(args.cache.glob('L*')):
        entries = []
        for path in sorted(level_dir.glob('m.*.bin')):
            with path.open('rb') as f:
                header = f.read(272)
            if len(header) != 272:
                raise ValueError(f'short region {path}')
            magic, level, rx, rz, *lengths = struct.unpack('>68i', header)
            if magic != 0x4c4d4331 or level_dir.name != 'L'+str(level) or path.name != f'm.{rx}.{rz}.bin':
                raise ValueError(f'region identity mismatch {path}')
            offset = 272
            for slot, length in enumerate(lengths):
                if length < 0:
                    raise ValueError('negative entry size')
                if length:
                    ident = f'{level}/{rx*8+slot%8}/{rz*8+slot//8}'
                    rank = hashlib.sha256(ident.encode()).digest()
                    entries.append((rank, path, offset, length, ident))
                offset += length
            if offset != path.stat().st_size:
                raise ValueError(f'region length mismatch {path}')
        chosen = sorted(entries)[:args.nodes]
        for _, path, offset, length, ident in chosen:
            with path.open('rb') as f:
                f.seek(offset)
                blob = f.read(length)
            node = defaultdict(Counter)
            for origin, layer, compact, quads, data in entry_layers(blob):
                if fixture and layer == 0 and compact:
                    fixture.write(struct.pack('>3i', origin[3], layer, quads))
                    fixture.write(data)
                key = f'L{origin[3]}-layer{layer}'
                result = audit_layer(compact, quads, data, layer)
                node[key].update(result)
                totals[key].update(result)
            records.append({'node': ident, 'sha256': hashlib.sha256(blob).hexdigest(), 'counts': dict(node)})
        print(f'{level_dir.name}: sampled {len(chosen)} of {len(entries)} cached nodes; {time.monotonic()-started:.1f}s', flush=True)
    if not records:
        raise ValueError('no cache entries found')
    if fixture:
        fixture.close()
    out = {'kind': 'resident geometry audit, not visible work or FPS', 'cache': str(args.cache),
           'nodes_per_level': args.nodes, 'elapsed_s': time.monotonic()-started,
           'totals': dict(totals), 'nodes': records}
    if args.solid_fixture:
        out['solid_fixture'] = {'path': str(args.solid_fixture), 'bytes': args.solid_fixture.stat().st_size,
                                'sha256': hashlib.sha256(args.solid_fixture.read_bytes()).hexdigest()}
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(out, indent=2) + '\n')
    for key, c in sorted(totals.items()):
        print(key, json.dumps(c, sort_keys=True), flush=True)


if __name__ == '__main__':
    main()
