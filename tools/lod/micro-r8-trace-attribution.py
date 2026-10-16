#!/usr/bin/env python3
"""Offline r8 attribution from exported XML; never launches xctrace or the game.

Use --input TRACE_FOLDER. PID defaults to trace-state.json tracePid; --pid
explicitly selects a PID instead. Capture with --metadata for encoder-label joins.
ALL traced FPS/timing is NON-PERFORMANCE evidence, including this report.
"""
import argparse
from collections import Counter, defaultdict
from decimal import Decimal
import json
import math
from pathlib import Path
import re
import xml.etree.ElementTree as ET

WARNING = 'ALL FPS and timing from this traced run are NON-PERFORMANCE evidence; GPU attribution only.'


def read(path, schema, required):
    root = ET.parse(path).getroot()
    ids = {e.get('id'): e for e in root.iter() if e.get('id')}

    def resolve(e):
        seen = set()
        while e.get('ref'):
            ref = e.get('ref')
            if ref in seen or ref not in ids:
                raise ValueError(f'{path}: invalid XML reference {ref}')
            seen.add(ref)
            e = ids[ref]
        return e

    def value(e):
        e = resolve(e)
        return (e.text or '').strip() or e.get('fmt', '')

    def process_id(e):
        e = resolve(e)
        child = e.find('pid')
        if child is not None:
            return int(value(child))
        match = re.search(r'\((\d+)\)$', value(e))
        return int(match.group(1)) if match else None

    nodes = [n for n in root.findall('.//node') if n.find('schema') is not None
             and n.find('schema').get('name') == schema]
    if not nodes:
        raise ValueError(f'{path}: missing schema {schema}; capture/export with --metadata')
    rows = []
    for node in nodes:
        columns = [c.findtext('mnemonic') for c in node.find('schema').findall('col')]
        if not all(columns) or len(columns) != len(set(columns)):
            raise ValueError(f'{path}: missing or duplicate schema column mnemonics')
        missing = set(required) - set(columns)
        if missing:
            raise ValueError(f'{path}: {schema} missing columns {sorted(missing)}')
        for row in node.findall('row'):
            if len(row) != len(columns):
                raise ValueError(f'{path}: {schema} row length differs from declared schema')
            # Repeated XML tags (duration, formatted-label, object IDs) are
            # distinct columns. Always use schema order, then resolve references.
            record = dict(zip(columns, map(value, row)))
            record['processId'] = process_id(row[columns.index('process')])
            rows.append(record)
    return rows


def union_ns(rows):
    end, total = -1, 0
    for start, stop in sorted((r['start'], r['start'] + r['duration']) for r in rows):
        if stop > end:
            total += stop - max(start, end)
            end = stop
    return total


def stats(rows):
    per_encoder = defaultdict(int)
    for row in rows:
        per_encoder[(row['processId'], row['cmdbuffer-id'], row['encoder-id'])] += row['duration']
    values = sorted(per_encoder.values())
    return dict(intervals=len(rows), uniqueEncoders=len(values),
                uniqueCommandBuffers=len({r['cmdbuffer-id'] for r in rows}),
                sumMs=sum(values) / 1e6, executionUnionMs=union_ns(rows) / 1e6,
                meanMs=sum(values) / len(values) / 1e6 if values else None,
                p95Ms=values[math.ceil(.95 * len(values)) - 1] / 1e6 if values else None)


def encoder_key(row):
    return row['processId'], row['cmdbuffer-id'], row['encoder-id']


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input', type=Path, required=True, help='trace folder containing exported XML and trace-state.json')
    parser.add_argument('--pid', type=int, help='explicit PID override; otherwise use trace-state.json tracePid')
    parser.add_argument('--output', type=Path, help='default: INPUT/attribution-r8')
    args = parser.parse_args()
    state_path = args.input / 'trace-state.json'
    state = json.loads(state_path.read_text()) if state_path.exists() else {}
    pid = args.pid if args.pid is not None else state.get('tracePid')
    if type(pid) is not int or pid <= 0:
        parser.error('provide a positive --pid or trace-state.json with an integer tracePid')
    output = args.output or args.input / 'attribution-r8'
    raw = read(args.input / 'gpu.xml', 'metal-gpu-intervals',
               ('start', 'duration', 'process', 'channel-name', 'event-label', 'cmdbuffer-id', 'encoder-id', 'gpu-submission-id'))
    if not raw:
        raise ValueError('GPU interval table is empty')
    for row in raw:
        row['start'], row['duration'] = int(row['start']), int(row['duration'])
        if row['duration'] < 0:
            raise ValueError('negative GPU execution duration')
        row['gpuFullLabel'] = row['event-label'].split('  (')[0]
    window_start = min(r['start'] for r in raw)
    window_end = max(r['start'] + r['duration'] for r in raw)
    selected = [r for r in raw if r['processId'] == pid]
    if not selected:
        raise ValueError(f'no GPU intervals for PID {pid}; available PIDs: {sorted({r["processId"] for r in raw if r["processId"] is not None})}')
    unique = {}
    for row in selected:
        key = tuple(row[k] for k in ('processId', 'start', 'duration', 'channel-name', 'cmdbuffer-id', 'encoder-id', 'gpu-submission-id'))
        unique.setdefault(key, row)
    rows = list(unique.values())

    encoders = read(args.input / 'labels-and-encoders.xml', 'metal-application-encoders-list',
                    ('process', 'cmdbuffer-id', 'encoder-id', 'cmdbuffer-label', 'encoder-label'))
    enc_by_id = {}
    for row in encoders:
        if row['processId'] != pid:
            continue
        key = encoder_key(row)
        previous = enc_by_id.get(key)
        if previous and any(previous[k] != row[k] for k in ('cmdbuffer-label', 'encoder-label')):
            raise ValueError(f'ambiguous encoder metadata labels for {key}')
        enc_by_id[key] = row
    joins = Counter()
    for row in rows:
        metadata = enc_by_id.get(encoder_key(row))
        if metadata:
            row['commandBufferLabel'] = metadata['cmdbuffer-label']
            row['label'] = metadata['encoder-label']
            row['labelSource'] = 'metadata PID+command-buffer+encoder join'
        else:
            cb, separator, label = row['gpuFullLabel'].partition(':')
            row['commandBufferLabel'] = cb if separator else ''
            row['label'] = label if separator else cb
            row['labelSource'] = 'GPU event-label; metadata unmatched'
        joins[(row['gpuFullLabel'], row['commandBufferLabel'], row['label'], row['labelSource'])] += 1

    resolve = 'Micro all-winner material resolve'
    continuation = 'Farsight micro composite + main continuation'
    consumers = {r['cmdbuffer-id'] for r in rows if r['label'] == resolve}
    preparations = {r['cmdbuffer-id'] for r in rows if r['commandBufferLabel'] == 'LOD live micro-raster transaction'}
    grouped = defaultdict(list)
    for row in rows:
        label = row['label']
        if label.startswith('Micro ') or label == continuation:
            group = label
        elif row['cmdbuffer-id'] in consumers and label == 'Render Command 0':
            group = 'Main before resolve (original prefix + micro visibility; mixed)'
        elif row['cmdbuffer-id'] not in consumers and label == 'Render Command 1':
            group = 'Main Render Command 1 without observed micro resolve (not exact OFF attribution)'
        else:
            group = 'Other ' + row['channel-name']
        grouped[(group, row['channel-name'])].append(row)
    stages = [dict(stage=label, channel=channel, **stats(group)) for (label, channel), group in grouped.items()]
    stages.sort(key=lambda entry: -entry['sumMs'])
    prep_groups = defaultdict(list)
    for row in rows:
        if row['cmdbuffer-id'] in preparations:
            prep_groups[row['cmdbuffer-id']].append(row)
    windows = []
    for group in sorted(prep_groups.values(), key=lambda g: min(r['start'] for r in g)):
        start, end = min(r['start'] for r in group), max(r['start'] + r['duration'] for r in group)
        if not windows or start > windows[-1]['endNs'] + 1_000_000_000:
            windows.append(dict(startNs=start, endNs=end, preparationCalls=1))
        else:
            windows[-1]['endNs'] = max(windows[-1]['endNs'], end)
            windows[-1]['preparationCalls'] += 1
    toc = ET.parse(args.input / 'toc.xml').getroot()
    duration = toc.findtext('.//summary/duration')
    busy_ns = union_ns(rows)
    activity_ns = window_end - window_start
    caveats = [WARNING,
        'Driver-coalesced compute encoders can combine count setup/raster/block count/prefix/scatter; no separate kernel cost is inferred.',
        'All-winner resolve reconstructs material for hardware AND compute winner IDs; it is not compute-only shading.',
        'Continuation contains composite AND subsequent original/main draws; its cost is not composite-only.',
        'Pre-resolve main rendering contains original prefix AND micro visibility; it is not isolated hardware micro cost.',
        'Lane/stage sums overlap. Do not add Vertex+Fragment+Compute or interpret sums as frame time.',
        'Preparation presence windows are observed activity clusters, not synchronized CPU ABBA arm boundaries.',
        'No-resolve CB grouping is an observed structural comparison, not a clean OFF performance sample.',
        'Trace edges can truncate encoder pairs; compute channels also include blit execution.',
        'Unmatched metadata uses GPU event-label fallback and is counted explicitly.']
    if args.pid is not None and state.get('tracePid') not in (None, pid):
        caveats.append(f'Explicit PID {pid} differs from recorded tracePid {state["tracePid"]}; this report uses the explicit PID.')
    summary = dict(schema='micro-visibility-r8-trace-attribution-1', traceFolder=str(args.input.resolve()), pid=pid,
        pidSource='explicit --pid' if args.pid is not None else 'trace-state.json tracePid', recordedTracePid=state.get('tracePid'),
        purpose=WARNING, timingEvidenceValid=False, performanceEvidence=False, releaseEligible=False,
        executionDurationColumn='duration by schema mnemonic; start-latency excluded',
        labelColumn='encoder-label and cmdbuffer-label joined by PID+CB+encoder; event-label fallback for unmatched rows',
        denominator='unique (PID, cmdbuffer-id, encoder-id) per channel, summing split intervals; no GPU frame numbers',
        percentile='nearest-rank p95 of per-encoder summed execution durations',
        rawSelectedIntervals=len(selected), uniqueSelectedIntervals=len(rows), removedDuplicates=len(selected)-len(rows),
        metadataMatchedIntervals=sum(encoder_key(r) in enc_by_id for r in rows),
        metadataUnmatchedIntervals=sum(encoder_key(r) not in enc_by_id for r in rows), metadataSelectedEncoders=len(enc_by_id),
        labelJoins=[dict(gpuEventLabel=g, commandBufferLabel=cb, encoderLabel=enc, source=source, intervals=count)
                    for (g, cb, enc, source), count in sorted(joins.items())],
        gpuActivityWindowSeconds=activity_ns/1e9, gpuActivityStartSeconds=window_start/1e9, gpuActivityEndSeconds=window_end/1e9,
        tocDurationSeconds=float(Decimal(duration)) if duration is not None else None,
        selectedExecutionUnionSeconds=busy_ns/1e9,
        selectedBusyPercentOfActivityWindow=100*busy_ns/activity_ns if activity_ns else None,
        lanes={channel: stats([r for r in rows if r['channel-name'] == channel]) for channel in ('Compute', 'Vertex', 'Fragment')},
        observedMicroPreparationCalls=len(preparations), observedMicroConsumerCommandBuffers=len(consumers),
        preparationPresenceWindows=windows, presenceWindowGapThresholdSeconds=1, caveats=caveats, stages=stages)
    output.mkdir(parents=True, exist_ok=True)
    (output / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
    lines = [f'# r8 traced GPU attribution: PID {pid}', '', WARNING, '',
        f'Observed {len(preparations)} micro preparation CBs and {len(consumers)} all-winner resolve consumer CBs.',
        f"Selected execution union {summary['selectedExecutionUnionSeconds']:.3f}s / {summary['gpuActivityWindowSeconds']:.3f}s GPU activity window.", '',
        f"Metadata label joins matched {summary['metadataMatchedIntervals']}/{len(rows)} intervals by PID + CB/encoder IDs. "
        'Means/p95 use unique encoder execution sums, never GPU frame numbering.', '',
        '| Stage | Lane | Encoders | Sum ms | Mean ms | p95 ms |', '|---|---|---:|---:|---:|---:|']
    for stage in stages:
        label = stage['stage'].replace('|', '\\|').replace('\n', ' ')
        lines.append(f"| {label} | {stage['channel']} | {stage['uniqueEncoders']} | {stage['sumMs']:.3f} | {stage['meanMs']:.4f} | {stage['p95Ms']:.4f} |")
    lines += ['', 'Cautions:', ''] + ['- '+c for c in caveats]
    lines += ['', 'Observed preparation activity clusters (trace-relative; not CPU arm labels):', '']
    lines += [f"- {w['startNs']/1e9:.3f}–{w['endNs']/1e9:.3f}s: {w['preparationCalls']} calls." for w in windows]
    (output / 'report.md').write_text('\n'.join(lines) + '\n')
    print('\n'.join(lines))


if __name__ == '__main__':
    main()
