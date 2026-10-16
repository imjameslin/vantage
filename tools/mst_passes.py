#!/usr/bin/env python3
"""Summed GPU interval time by encoder label, for Java in a Metal System Trace export.

usage: mst_passes.py <metal-gpu-intervals.xml> [topN]

These are interval sums, not utilization or game-frame times: channels may overlap,
and GPU frame numbers also identify side command buffers. Use mst_frames.py for
interval unions and command-buffer statistics.
"""
import re
import sys
import xml.etree.ElementTree as ET
from collections import Counter, defaultdict


def summarize(path):
    ids = {}
    labels = defaultdict(Counter)
    count = 0
    formatted = {'process', 'formatted-label'}

    def value(element):
        if 'ref' in element.attrib:
            return ids[element.attrib['ref']]
        return (element.attrib.get('fmt') if element.tag in formatted or element.text is None
                else element.text)

    for _, row in ET.iterparse(path, events=('end',)):
        if row.tag != 'row':
            continue
        for element in row.iter():
            if 'id' in element.attrib:
                ids[element.attrib['id']] = value(element)
        # Two duration columns: interval and CPU-to-GPU latency. Two labels:
        # encoder and surface accesses. Later columns must not replace the
        # first measurement. Resolve nested IDs before clearing the row.
        fields = {}
        for element in row:
            fields.setdefault(element.tag, value(element))
        if (fields.get('process') or '').startswith('java (') or fields.get('process') == 'java':
            label = re.sub(r'0x[0-9a-f]+', '', fields.get('formatted-label') or '').strip()
            labels[label][fields['gpu-channel-name']] += int(fields['duration'])
            count += 1
        row.clear()
    return count, labels


def main():
    count, labels = summarize(sys.argv[1])
    if not count:
        raise SystemExit('No Java GPU intervals; no per-label evidence.')
    print(f'{count} Java GPU intervals; summed milliseconds by encoder label (overlap included, not per game frame)')
    limit = int(sys.argv[2]) if len(sys.argv) > 2 else 30
    for label, channels in sorted(labels.items(), key=lambda item: -sum(item[1].values()))[:limit]:
        print('  '.join(f'{channel} {duration / 1e6:.3f}ms' for channel, duration in sorted(channels.items()))
              + '  ' + label[:160])


if __name__ == '__main__':
    main()
