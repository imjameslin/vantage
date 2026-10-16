#!/usr/bin/env python3
"""Census saved exact ray-query IDs; an oracle work estimate, not safe reuse or FPS."""
import argparse
import gzip
import hashlib
import json
from pathlib import Path
import struct
import numpy as np


def census(ids, sizes=(2, 4, 8, 16)):
    height, width = ids.shape
    covered = int(np.count_nonzero(ids))
    rows = []
    for size in sizes:
        # Zero padding has no real pixels and is excluded from every denominator.
        h = (height + size - 1) // size * size
        w = (width + size - 1) // size * size
        data = np.zeros((h, w), dtype=np.uint32)
        valid = np.zeros((h, w), dtype=np.bool_)
        data[:height, :width] = ids
        valid[:height, :width] = True
        tiles = data.reshape(h//size, size, w//size, size).transpose(0, 2, 1, 3).reshape(-1, size*size)
        pixels = valid.reshape(h//size, size, w//size, size).transpose(0, 2, 1, 3).reshape(-1, size*size).sum(axis=1)
        nonzero = np.count_nonzero(tiles, axis=1)
        tiles.sort(axis=1)
        unique = np.count_nonzero((tiles[:, 1:] != tiles[:, :-1]) & (tiles[:, 1:] != 0), axis=1)
        unique += tiles[:, 0] != 0
        empty = nonzero == 0
        one = unique == 1
        full = nonzero == pixels
        full_one = full & one
        mixed_edge = (~full) & (~empty)
        total_unique = int(unique.sum())
        # This optimistic oracle already knows every final source; obtaining or
        # certifying that knowledge is exactly the unsolved visibility problem.
        assert int(nonzero.sum()) == covered
        assert int(pixels.sum()) == width * height
        assert total_unique <= covered
        rows.append({
            'tileSize': size, 'tiles': len(tiles), 'emptyTiles': int(empty.sum()),
            'tilesWithCoverage': int((~empty).sum()),
            'fullyCoveredSingleSourceTiles': int(full_one.sum()),
            'coveredPixelsInFullSingleSourceTiles': int(nonzero[full_one].sum()),
            'coveredFractionInFullSingleSourceTiles': float(nonzero[full_one].sum()/covered) if covered else 0,
            'mixedBackgroundCoverageTiles': int(mixed_edge.sum()),
            'tilesWithMultipleSources': int((unique > 1).sum()),
            'sumDistinctNonzeroSourcesPerTile': total_unique,
            'oracleSourceEvaluationsPerCoveredPixel': total_unique/covered if covered else 0,
            'coveredPixels': covered,
        })
    return rows


def self_check():
    # Independent set-based reference, including partial edge tiles and sky.
    checked = 0
    rng = np.random.default_rng(1273)
    for h, w in ((1, 1), (3, 5), (16, 16), (17, 19), (31, 33)):
        for ids in (np.zeros((h, w), np.uint32), np.full((h, w), 17, np.uint32),
                    rng.integers(0, 9, (h, w), dtype=np.uint32),
                    np.arange(h*w, dtype=np.uint32).reshape(h, w)):
            for row in census(ids):
                size = row['tileSize']; cells = []
                for y in range(0, h, size):
                    for x in range(0, w, size):
                        values = list(map(int, ids[y:y+size, x:x+size].flat))
                        nonzero = [v for v in values if v]
                        cells.append((values, nonzero, set(nonzero)))
                assert row['tiles'] == len(cells)
                assert row['emptyTiles'] == sum(not n for _, n, _ in cells)
                assert row['sumDistinctNonzeroSourcesPerTile'] == sum(len(s) for _, _, s in cells)
                assert row['fullyCoveredSingleSourceTiles'] == sum(len(v) == len(n) and len(s) == 1 for v,n,s in cells)
                assert row['coveredPixelsInFullSingleSourceTiles'] == sum(len(n) for v,n,s in cells if len(v) == len(n) and len(s) == 1)
                assert row['mixedBackgroundCoverageTiles'] == sum(bool(n) and len(v) != len(n) for v,n,s in cells)
                assert row['tilesWithMultipleSources'] == sum(len(s) > 1 for _,_,s in cells)
                checked += 1
    return checked


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('input', type=Path)
    p.add_argument('output', type=Path)
    a = p.parse_args()
    checks = self_check()
    with gzip.open(a.input, 'rb') as f:
        header = f.read(16)
        if len(header) != 16:
            raise ValueError('Truncated ray visibility header')
        magic, width, height, planes = struct.unpack('<4I', header)
        if magic != 0x5253514c or planes != 2 or min(width, height) < 1 or width*height > 16_777_216:
            raise ValueError('Unsupported/oversized ray visibility output')
        expected = width*height*8
        raw = f.read(expected+1)
        if len(raw) != expected:
            raise ValueError('Visibility payload extent differs from header')
    depth = np.frombuffer(raw, '<f4', count=width*height).reshape(height,width)
    if not np.all(np.isfinite(depth)) or np.any(depth < 0) or np.any(depth > 1):
        raise ValueError('Invalid depth')
    ids = np.frombuffer(raw, '<u4', offset=width*height*4).reshape(height,width)
    result = {'schema': 'farsight-visibility-coherence-1', 'selfCheckCases': checks,
              'input': str(a.input.resolve()), 'rawSha256': hashlib.sha256(header+raw).hexdigest(),
              'width': width, 'height': height, 'coveredPixels': int(np.count_nonzero(ids)),
              'uniqueNonzeroSources': int(np.unique(ids[ids != 0]).size), 'tiles': census(ids),
              'scope': 'Oracle ID-coherence census of one saved forced-opaque query image. No executable pixel sharing, certification, shading, actual alpha, motion or timing. Empty pixels excluded from claimed covered-pixel work ratios. Different IDs can still share hierarchy work; equal IDs at sample centers do not prove continuous footprint visibility.'}
    a.output.parent.mkdir(parents=True, exist_ok=True)
    a.output.write_text(json.dumps(result, indent=2)+'\n')
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
