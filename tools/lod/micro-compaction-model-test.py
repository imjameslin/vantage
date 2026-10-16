#!/usr/bin/env python3
"""CPU model of micro_compact_count/prefix/scatter; no Metal, compiler or game.

Run: python3 -B tools/lod/micro-compaction-model-test.py
Checks stable route-1 IDs against an independent filter oracle, original ranks,
capacity bounds, empty/tail/invalid routes and actual issued-index accounting.
Mutation witnesses establish sensitivity to five specific implementation errors;
they do not prove the Metal implementation, memory visibility or image parity.
"""
from dataclasses import dataclass
import random

WIDTH = 128


@dataclass
class Result:
    dense: list
    counts: tuple
    issued: int
    ranks: list
    refused: bool


def compact(routes, capacity, simd_width, base=17, mutation=None):
    n = len(routes)
    assert n <= capacity <= 0xFFFFFFFF // 6
    assert simd_width > 0 and WIDTH % simd_width == 0
    nblocks = max(1, (n + WIDTH - 1) // WIDTH)
    assert nblocks <= max(1, (capacity + WIDTH - 1) // WIDTH)
    blocks = []
    for block in range(nblocks):
        r = routes[block * WIDTH:(block + 1) * WIDTH]
        h, c, e = r.count(1), r.count(2), r.count(3)
        if mutation == 'include_empty':
            h, e = h + e, 0
        blocks.append([h, c, e, 0])

    # One prefix group: contiguous block chunks per lane, then SIMD/group scan.
    chunk = (nblocks + WIDTH - 1) // WIDTH
    lane_totals = []
    for tid in range(WIDTH):
        local = [0, 0, 0, 0]
        for block in range(min(nblocks, tid * chunk), min(nblocks, (tid + 1) * chunk)):
            record = blocks[block]
            active = min(WIDTH, max(0, n - block * WIDTH))
            if sum(record[:3]) != active or record[3] != 0:
                record = blocks[block] = [0, 0, 0, 0]
                local[3] += 1
            for k in range(3):
                local[k] += record[k]
        lane_totals.append(local)
    group_totals = [sum(v[0] for v in lane_totals[s:s + simd_width])
                    for s in range(0, WIDTH, simd_width)]
    for tid in range(WIDTH):
        sg = tid // simd_width
        offset = sum(v[0] for v in lane_totals[sg * simd_width:tid]) + sum(group_totals[:sg])
        for block in range(min(nblocks, tid * chunk), min(nblocks, (tid + 1) * chunk)):
            blocks[block][3] = offset
            offset += blocks[block][0]
    totals = tuple(sum(v[k] for v in lane_totals) for k in range(4))
    refused = totals[3] != 0 or sum(totals[:3]) != n or totals[0] > n
    h = totals[0] if not refused else 0
    memory = [None] * (2 * capacity)
    memory[:n] = routes
    written = set()
    if h:
        for block in range(nblocks):
            flags = [int(q < n and (routes[q] == 1 or
                         (mutation == 'include_empty' and routes[q] == 3)))
                     for q in range(block * WIDTH, (block + 1) * WIDTH)]
            group_sums = [sum(flags[s:s + simd_width]) for s in range(0, WIDTH, simd_width)]
            for sg, group_sum in enumerate(group_sums):
                local = sum(group_sums[:sg])
                for lane in range(simd_width):
                    tid = sg * simd_width + lane
                    if not flags[tid]:
                        continue
                    q = block * WIDTH + tid
                    dst = blocks[block][3] + local
                    assert local < blocks[block][0] and dst < h and dst < n
                    at = (capacity if mutation == 'capacity_offset' else n) + dst
                    assert n <= at < 2 * capacity and at not in written
                    written.add(at)
                    memory[at] = q
                    local += 1
    dense = memory[n:n + h]
    if mutation == 'unordered_append':
        dense.reverse()
    # Model micro_main's original-q mapping and flat rank, with 012230 topology.
    ranks = [base + 2 * (i if mutation == 'dense_rank' else q) + tri + 1
             for i, q in enumerate(dense) if q is not None for tri in (0, 1)]
    issued = 6 * (n if mutation == 'old_issued_count' else h)
    return Result(dense, totals[:3], issued, ranks, refused)


def verify(routes, result, base=17):
    valid = all(r in (1, 2, 3) for r in routes)
    assert result.refused == (not valid), 'refusal'
    if not valid:
        assert result.issued == 0 and not result.dense, 'invalid route drew hardware'
        return
    expected = [q for q, route in enumerate(routes) if route == 1]
    assert result.dense == expected, 'stable original quad IDs'
    assert result.counts == tuple(routes.count(r) for r in (1, 2, 3)), 'H/C/E counts'
    assert sum(result.counts) == len(routes), 'N partition'
    assert result.issued == 6 * len(expected), 'issued indexed count'
    assert result.ranks == [base + 2 * q + tri + 1 for q in expected for tri in (0, 1)], 'original rank'
    for i, q in enumerate(expected):
        # Six shared indices must fetch original corners 0,1,2,2,3,0.
        indices = [4 * i + c for c in (0, 1, 2, 2, 3, 0)]
        assert [4 * result.dense[v >> 2] + (v & 3) for v in indices] == [4 * q + c for c in (0, 1, 2, 2, 3, 0)]
        assert [(2 * i + tri) & 1 for tri in (0, 1)] == [0, 1]


def main():
    rng = random.Random(0x531)
    cases = 0
    for n in (0, 1, 31, 32, 127, 128, 129, 255, 256, 257, 16383, 16384, 16385, 131073):
        for mode in range(6):
            routes = ([1] * n if mode == 0 else [2] * n if mode == 1 else [3] * n if mode == 2 else
                      [1 + i % 3 for i in range(n)] if mode == 3 else
                      [rng.randrange(1, 4) for _ in range(n)] if mode == 4 else
                      [0 if i == n // 2 else 1 for i in range(n)])
            # Alternate exact capacity and a reused larger slice with stale tail.
            capacity = n if mode % 2 == 0 else n + 137
            for sw in (1, 2, 4, 8, 16, 32, 64, 128):
                verify(routes, compact(routes, capacity, sw))
                cases += 1
    # Adjacent jobs must reserve 8*capacity bytes each, independent of GPU N.
    offset = 0
    for capacity, n in ((0, 0), (257, 129), (17, 17), (8192, 0), (8193, 8193)):
        assert 4 * (n + n) <= 8 * capacity
        end = offset + 8 * capacity
        assert offset <= offset + 4 * n <= end
        offset = end
    witness = [2, 1, 3, 1] * 40 + [3, 2, 1]
    mutations = ('include_empty', 'unordered_append', 'dense_rank', 'old_issued_count', 'capacity_offset')
    for mutation in mutations:
        try:
            verify(witness, compact(witness, len(witness) + 137, 32, mutation=mutation))
        except AssertionError:
            continue
        raise AssertionError('Mutation survived: ' + mutation)
    print(f'CPU compaction model: {cases} cases passed; {len(mutations)} targeted mutations rejected. No Metal execution.')


if __name__ == '__main__':
    main()
