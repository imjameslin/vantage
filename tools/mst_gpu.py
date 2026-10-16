#!/usr/bin/env python3
"""Summarize GPU busy time from an exported Metal System Trace 'metal-gpu-intervals' table.
usage: mst_gpu.py <exported.xml>"""
import sys, xml.etree.ElementTree as ET
from collections import defaultdict
ids = {}
def val(el):
    if el is None: return None
    if 'ref' in el.attrib: return ids.get(el.attrib['ref'])
    v = el.attrib.get('fmt') if el.text is None else el.text
    if 'id' in el.attrib: ids[el.attrib['id']] = v if el.tag != 'process' else el.attrib.get('fmt')
    if el.tag == 'process': return el.attrib.get('fmt')
    return v
def walk_ids(el):
    for c in el.iter():
        if 'id' in c.attrib and 'ref' not in c.attrib:
            ids.setdefault(c.attrib['id'], c.attrib.get('fmt') if c.tag == 'process' else (c.text if c.text is not None else c.attrib.get('fmt')))
rows = []
for ev, el in ET.iterparse(sys.argv[1], events=('end',)):
    if el.tag == 'row':
        walk_ids(el)
        ch = list(el)
        def get(tag):
            for c in ch:
                if c.tag == tag: return c
        start = int(val(get('start-time'))); dur = int(val(get('duration')))
        chan = val(get('gpu-channel-name')); proc = val(get('process'))
        rows.append((start, dur, chan, proc))
        el.clear()
by_proc = defaultdict(list)
for s, d, c, p in rows:
    by_proc[(p or '?').split(' (')[0]].append((s, s + d, c))
t0 = min(r[0] for r in rows); t1 = max(r[0] + r[1] for r in rows)
win = (t1 - t0) / 1e9
print(f"window {win:.3f}s, {len(rows)} intervals")
for p, iv in sorted(by_proc.items(), key=lambda kv: -sum(e - s for s, e, _ in kv[1])):
    iv.sort()
    busy = 0; cur_s, cur_e = None, None
    for s, e, _ in iv:
        if cur_e is None or s > cur_e:
            if cur_e is not None: busy += cur_e - cur_s
            cur_s, cur_e = s, e
        else: cur_e = max(cur_e, e)
    if cur_e is not None: busy += cur_e - cur_s
    per_chan = defaultdict(int)
    for s, e, c in iv: per_chan[c] += e - s
    print(f"{p:30s} busy {busy/1e9:.3f}s ({100*busy/1e9/win:.1f}% of window) " + ", ".join(f"{c}:{v/1e6:.0f}ms" for c, v in sorted(per_chan.items())))
