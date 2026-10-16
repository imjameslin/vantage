#!/usr/bin/env python3
"""Per-command-buffer vertex / fragment split from an exported Metal System Trace 'metal-gpu-intervals' table
(results/GPU-HEAVY.md). tools/mst_gpu.py gives the per-process union; tools/mst_passes.py groups by encoder label, which
this macOS reports as a generic "GPU Execution", so this script groups the java process's intervals by command buffer
(our decoder commits one command buffer per frame) instead:

- per channel (Vertex, Fragment, Compute) the time per command buffer: mean / p50 / p99, and the share of the window;
- the vertex/fragment overlap inside the process (both channels busy at once) and the union per command buffer;
- command buffers with more than the usual number of encoders (the frames carrying texture-animation passes) separately;
- the other processes' GPU time per second (WindowServer = composition).

usage: mst_frames.py <exported.xml>
"""
import collections, math, statistics, sys, xml.etree.ElementTree as ET

ids = {}


def val(c):
    if 'ref' in c.attrib:
        return ids.get(c.attrib['ref'])
    return c.attrib.get('fmt') if c.text is None else c.text


def pct(xs, q):
    xs = sorted(xs)
    return xs[min(len(xs) - 1, max(0, int(math.ceil(q * len(xs))) - 1))] if xs else float('nan')


def union(iv):
    iv = sorted(iv)
    tot, cs, ce = 0, None, None
    for s, e in iv:
        if ce is None or s > ce:
            if ce is not None:
                tot += ce - cs
            cs, ce = s, e
        else:
            ce = max(ce, e)
    if ce is not None:
        tot += ce - cs
    return tot


def main():
    rows = []
    for ev, el in ET.iterparse(sys.argv[1], events=('end',)):
        if el.tag != 'row':
            continue
        for c in el.iter():
            if 'id' in c.attrib and 'ref' not in c.attrib:
                ids[c.attrib['id']] = c.attrib.get('fmt') if c.tag in ('process',) or c.text is None else c.text
        d = {}
        for c in el:
            if c.tag in d:
                continue
            d[c.tag] = val(c)
        proc = (d.get('process') or '?').split(' (')[0]
        cbs = [c for c in el if c.tag == 'metal-command-buffer-id']
        cb = val(cbs[0]) if cbs else None
        enc = val(cbs[1]) if len(cbs) > 1 else None
        rows.append((int(d['start-time']), int(d['duration']), d.get('gpu-channel-name'), proc, cb, enc))
        el.clear()
    t0 = min(r[0] for r in rows)
    t1 = max(r[0] + r[1] for r in rows)
    win = (t1 - t0) / 1e9
    print(f"window {win:.3f} s, {len(rows)} intervals")
    by_proc = collections.defaultdict(list)
    for r in rows:
        by_proc[r[3]].append(r)
    for p, rs in sorted(by_proc.items(), key=lambda kv: -sum(r[1] for r in kv[1])):
        ch = collections.Counter()
        for r in rs:
            ch[r[2]] += r[1]
        u = union([(r[0], r[0] + r[1]) for r in rs])
        print(f"  {p:24s} union {u / 1e6 / win:7.1f} ms/s ({100 * u / 1e9 / win:5.1f} %)  " +
              "  ".join(f"{k} {v / 1e6 / win:.1f} ms/s" for k, v in ch.most_common()))
    java = [r for r in rows if r[3].startswith('java')]
    if not java:
        return
    vt = [(r[0], r[0] + r[1]) for r in java if r[2] == 'Vertex']
    ft = [(r[0], r[0] + r[1]) for r in java if r[2] == 'Fragment']
    uv, uf = union(vt), union(ft)
    ua = union(vt + ft)
    print(f"java: vertex busy {uv / 1e6 / win:.1f} ms/s, fragment busy {uf / 1e6 / win:.1f} ms/s, either {ua / 1e6 / win:.1f} ms/s, "
          f"both at once {(uv + uf - ua) / 1e6 / win:.1f} ms/s")
    per = collections.defaultdict(lambda: {'Vertex': [], 'Fragment': [], 'Compute': [], 'enc': set()})
    for r in java:
        per[r[4]][r[2] if r[2] in ('Vertex', 'Fragment', 'Compute') else 'Fragment'].append((r[0], r[0] + r[1]))
        per[r[4]]['enc'].add(r[5])
    cbs = [v for v in per.values() if v['Vertex'] or v['Fragment']]
    ne = collections.Counter(len(v['enc']) for v in cbs)
    usual = ne.most_common(1)[0][0]
    print(f"java command buffers {len(cbs)} ({len(cbs) / win:.0f}/s); encoders per command buffer {sorted(ne.items())}")
    for name, sel in (("usual", [v for v in cbs if len(v['enc']) <= usual]), ("extra encoders", [v for v in cbs if len(v['enc']) > usual])):
        if not sel:
            continue
        vv = [union(v['Vertex']) / 1e6 for v in sel]
        ff = [union(v['Fragment']) / 1e6 for v in sel]
        aa = [union(v['Vertex'] + v['Fragment']) / 1e6 for v in sel]
        span = [(max(e for _, e in v['Vertex'] + v['Fragment']) - min(s for s, _ in v['Vertex'] + v['Fragment'])) / 1e6 for v in sel]
        print(f"  {name:15s} n={len(sel):5d}  vertex mean {statistics.mean(vv):.3f} p50 {pct(vv, .5):.3f} p99 {pct(vv, .99):.3f} | "
              f"fragment mean {statistics.mean(ff):.3f} p50 {pct(ff, .5):.3f} p99 {pct(ff, .99):.3f} | union mean {statistics.mean(aa):.3f} | "
              f"span mean {statistics.mean(span):.3f} p99 {pct(span, .99):.3f} ms")


if __name__ == '__main__':
    main()
