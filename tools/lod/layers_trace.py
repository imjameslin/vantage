#!/usr/bin/env python3
r"""Attribute saved Metal GPU intervals using execution-time unions, not frame costs.

Usage:
  python3 tools/lod/layers_trace.py --trace-directory results/example \
      --output build/example-attribution

Reads gpu.xml; optionally reads toc.xml and labels-and-encoders.xml beside it
(or at --toc/--encoders paths). No xctrace invocation, recording, or build.
Without a TOC, percentages use the exported GPU activity window, not an assumed
recording duration. Outputs summary.json and six CSVs, including a disjoint
timeline. Category unions overlap and must not be added or divided by frame IDs.
"""

import argparse
import collections
import csv
from decimal import Decimal
import json
from pathlib import Path
import re
import statistics
import xml.etree.ElementTree as ET


def load_xml(path):
    root = ET.parse(path).getroot()
    ids = {x.get('id'): x for x in root.iter() if x.get('id')}

    def value(element):
        seen = set()
        while element.get('ref'):
            ref = element.get('ref')
            if ref in seen or ref not in ids:
                raise ValueError(f'{path}: invalid XML reference {ref}')
            seen.add(ref)
            element = ids[ref]
        return (element.text or '').strip() or element.get('fmt', '')

    return root, value


def table_rows(root, value, name):
    nodes = [n for n in root.findall('node')
             if n.find('schema') is not None and n.find('schema').get('name') == name]
    if len(nodes) != 1:
        raise ValueError(f'Expected one {name} table with its schema; found {len(nodes)}')
    node = nodes[0]
    columns = [c.findtext('mnemonic') for c in node.find('schema').findall('col')]
    if None in columns or len(set(columns)) != len(columns):
        raise ValueError(f'{name}: missing or duplicate schema column names')
    result = []
    for row in node.findall('row'):
        if len(row) != len(columns):
            raise ValueError(f'{name}: row length differs from schema')
        # Repeated XML tags are distinct columns: duration/start-latency and
        # event-label/iosurface-accesses. Never key this dictionary by tag.
        result.append(dict(zip(columns, map(value, row))))
    return result


def merge(intervals):
    result = []
    for start, end in sorted(set(intervals)):
        if end <= start:
            continue
        if result and start <= result[-1][1]:
            result[-1] = (result[-1][0], max(end, result[-1][1]))
        else:
            result.append((start, end))
    return result


def union_ns(rows):
    return sum(e - s for s, e in merge((r['start'], r['end']) for r in rows))


def category(row):
    label, channel = row['label'], row['channel-name']
    if channel in ('Vertex', 'Fragment'):
        return 'raster_' + channel.lower()
    if label.startswith('Farsight distance-layer warp + conditional draws:'):
        if ':Blit' in label:
            return 'farsight_reset_blit'
        if ':Compute' in label:
            return 'farsight_compute'
    if ':Blit' in label:
        return 'other_blit'
    for needle, name in [('terrain cluster cull', 'cluster_cull'),
                         ('atlas animation', 'atlas_animation'), ('lightmap', 'lightmap')]:
        if channel == 'Compute' and needle in label:
            return name
    return 'unclassified_' + channel.lower()


def select_process(rows, requested):
    processes = sorted({r['process'] for r in rows if r['process']})
    matches = [p for p in processes
               if p == requested or re.sub(r' \(\d+\)$', '', p) == requested]
    if len(matches) != 1:
        raise ValueError(f'--process {requested!r} must select one process; available: {processes}')
    return matches[0]


def analyze(args):
    directory = args.trace_directory.resolve()
    gpu_path = directory / 'gpu.xml'
    root, value = load_xml(gpu_path)
    rows = table_rows(root, value, 'metal-gpu-intervals')
    if not rows:
        raise ValueError('gpu.xml has no GPU intervals')
    for row in rows:
        for name in ('start', 'duration', 'event-depth', 'frame-number'):
            row[name] = int(row[name])
        latency = row.get('start-latency')
        row['start-latency'] = int(latency) if latency else None
        row['end'] = row['start'] + row['duration']
        if row['start'] < 0 or row['duration'] < 0:
            raise ValueError('GPU interval has a negative start or duration')
        row['label'] = row['event-label'].split('  (')[0]
        row['category'] = category(row)

    unique = {}
    for row in rows:
        key = tuple(row[k] for k in ('process', 'gpu', 'channel-name', 'start', 'end',
                                     'cmdbuffer-id', 'encoder-id', 'gpu-submission-id'))
        unique.setdefault(key, row)
    process = select_process(rows, args.process)
    selected = [r for r in unique.values() if r['process'] == process]
    # Keep valid depth-1 lane events and account for their overlap by union.
    activity_start = min(r['start'] for r in rows)
    activity_end = max(r['end'] for r in rows)
    window_start, window_end = activity_start, activity_end
    window_source = 'exported GPU activity bounds (all processes in gpu.xml)'
    toc_path = args.toc if args.toc is not None else directory / 'toc.xml'
    enc_path = args.encoders if args.encoders is not None else directory / 'labels-and-encoders.xml'
    recorded_duration = requested_limit = None
    if args.toc is not None or toc_path.exists():
        toc = ET.parse(toc_path).getroot()
        summaries = toc.findall('.//summary')
        if len(summaries) != 1 or summaries[0].findtext('duration') is None:
            raise ValueError('TOC must describe exactly one run with a duration')
        recorded_duration = int(Decimal(summaries[0].findtext('duration')) * 1_000_000_000)
        requested_limit = summaries[0].findtext('time-limit')
        window_start, window_end = 0, recorded_duration
        window_source = 'TOC recorded duration'
    if window_end <= window_start or activity_end > window_end:
        raise ValueError('Invalid analysis window or GPU intervals beyond TOC duration')
    window_ns = window_end - window_start

    groups = collections.defaultdict(list)
    for row in selected:
        groups[row['category']].append(row)
    stats = []
    for name, group in groups.items():
        durations = [r['duration'] / 1e6 for r in group]
        stats.append(dict(category=name, interval_rows=len(group),
                          execution_union_ms=union_ns(group) / 1e6,
                          analysis_window_percent=100 * union_ns(group) / window_ns,
                          raw_execution_sum_ms=sum(durations),
                          median_encoder_stage_interval_ms=statistics.median(durations),
                          max_encoder_stage_interval_ms=max(durations)))
    stats.sort(key=lambda r: (-r['execution_union_ms'], r['category']))

    events = collections.defaultdict(collections.Counter)
    for name, group in groups.items():
        for start, end in merge((r['start'], r['end']) for r in group):
            events[start][name] += 1
            events[end][name] -= 1
    active = collections.Counter()
    previous = window_start
    segments, combinations = [], collections.Counter()
    for time in sorted(set(events) | {window_start, window_end}):
        names = tuple(sorted(k for k, count in active.items() if count > 0))
        if time > previous:
            segments.append(dict(start_ns=previous, end_ns=time, duration_ns=time-previous,
                                 active_categories=';'.join(names)))
            combinations[names] += time - previous
        active.update(events[time])
        previous = time
    if (sum(combinations.values()) != window_ns or
            sum(n for names, n in combinations.items() if names) != union_ns(selected)):
        raise ValueError('Disjoint timeline does not reconcile with interval union')

    encoders = []
    if args.encoders is not None or enc_path.exists():
        enc_root, enc_value = load_xml(enc_path)
        encoders = table_rows(enc_root, enc_value, 'metal-application-encoders-list')
        encoders = [r for r in encoders if r['process'] == process]
    enc_by_id = {r['encoder-id']: r for r in encoders}
    per_encoder = collections.defaultdict(list)
    for row in selected:
        per_encoder[(row['cmdbuffer-id'], row['encoder-id'])].append(row)
    encoder_stats = []
    for (cb_id, enc_id), group in per_encoder.items():
        channels = {c: union_ns([r for r in group if r['channel-name'] == c])/1e6
                    for c in ('Vertex', 'Fragment', 'Compute')}
        encoder_stats.append(dict(encoder_id=enc_id, cmdbuffer_id=cb_id,
                                  frame_assignment=group[0]['frame-number'], label=group[0]['label'],
                                  cpu_encoding_start_ns=enc_by_id.get(enc_id, {}).get('start', ''),
                                  gpu_start_ns=min(r['start'] for r in group),
                                  gpu_end_ns=max(r['end'] for r in group),
                                  execution_union_ms=union_ns(group)/1e6,
                                  vertex_union_ms=channels['Vertex'], fragment_union_ms=channels['Fragment'],
                                  compute_union_ms=channels['Compute']))
    encoder_stats.sort(key=lambda r: (r['gpu_start_ns'], r['encoder_id']))

    def overlap(predicate):
        return sum(n for names, n in combinations.items() if predicate(set(names))) / 1e6

    raster = groups['raster_vertex'] + groups['raster_fragment']
    latencies = [r['start-latency'] for r in selected if r['start-latency'] is not None]
    summary = dict(
        scope='Diagnostic interval occupancy only; no FPS or per-frame GPU cost derived.',
        inputs=dict(gpu_xml=str(gpu_path), toc_xml=str(toc_path.resolve()) if recorded_duration is not None else None,
                    encoders_xml=str(enc_path.resolve()) if args.encoders is not None or enc_path.exists() else None),
        selected_process=process, analysis_window_source=window_source,
        analysis_window_start_ns=window_start, analysis_window_end_ns=window_end,
        analysis_window_ms=window_ns/1e6,
        recorded_duration_ms=recorded_duration/1e6 if recorded_duration is not None else None,
        requested_time_limit=requested_limit,
        exported_activity_start_ns=activity_start, exported_activity_end_ns=activity_end,
        input_rows=len(rows), exact_duplicate_rows=len(rows)-len(unique), selected_rows=len(selected),
        unattributed_rows=sum(not r['process'] for r in rows),
        other_process_rows=sum(bool(r['process']) and r['process'] != process for r in rows),
        selected_gpu_union_ms=union_ns(selected)/1e6, all_exported_gpu_union_ms=union_ns(rows)/1e6,
        selected_raw_execution_sum_ms=sum(r['duration'] for r in selected)/1e6,
        selected_queue_latency_sum_ms=sum(latencies)/1e6 if latencies else None,
        queue_latency_rows=len(latencies), frame_assignment_ids=len({r['frame-number'] for r in selected}),
        raster_union_ms=union_ns(raster)/1e6,
        compute_channel_union_ms=union_ns([r for r in selected if r['channel-name']=='Compute'])/1e6,
        explicit_blit_union_ms=union_ns(groups['other_blit']+groups['farsight_reset_blit'])/1e6,
        vertex_fragment_overlap_ms=overlap(lambda x: {'raster_vertex','raster_fragment'} <= x),
        farsight_compute_overlapping_raster_ms=overlap(lambda x: 'farsight_compute' in x and bool(x & {'raster_vertex','raster_fragment'})),
        farsight_compute_outside_raster_ms=overlap(lambda x: 'farsight_compute' in x and not x & {'raster_vertex','raster_fragment'}),
        categories=stats, encoder_metadata_rows=len(encoders),
        caveats=[
            'Category and channel unions overlap; neither their sums nor raw duration sums are frame costs.',
            'Queue latency is not execution time. Frame-assignment IDs are not a complete game-frame count.',
            'Vertex/Fragment are recorded stage intervals, not isolated shader instruction costs.',
            'Compute-channel intervals include blits. Copies implemented as render passes remain in raster.',
            'Main/refresh/band/mode attribution requires identifying labels; generic Render Command N is insufficient.',
            'Farsight compute encoder time includes its kernels/argument generation; no per-kernel split is inferred.',
            'GPUStartTime..GPUEndTime command-buffer spans can contain gaps and overlap other work.',
            'No timing conclusion about source changes after this recording is implied.'])

    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)

    def write_csv(name, data, columns=None):
        with (output / name).open('w', newline='', encoding='utf-8') as stream:
            writer = csv.DictWriter(stream, fieldnames=columns or list(data[0]))
            writer.writeheader()
            writer.writerows(data)

    write_csv('process-intervals.csv', selected)
    write_csv('category-unions.csv', stats)
    write_csv('timeline-union.csv', segments)
    write_csv('overlap-states.csv', [dict(active_categories=';'.join(k), union_ms=v/1e6)
                                   for k, v in combinations.most_common()])
    write_csv('encoder-labels.csv', encoders, list(encoders[0]) if encoders else ['encoder-id', 'encoder-label'])
    write_csv('encoder-unions.csv', encoder_stats)
    (output / 'summary.json').write_text(json.dumps(summary, indent=2)+'\n', encoding='utf-8')
    print(f'{process}: GPU union {union_ns(selected)/1e6:.3f} ms; window {window_ns/1e6:.3f} ms ({window_source})')
    for row in stats:
        print(f"{row['category']:24} union={row['execution_union_ms']:9.3f} ms  window={row['analysis_window_percent']:6.2f}%  rows={row['interval_rows']}")
    print(f'Overlapping unions are not additive or per-frame costs. Outputs: {output}')


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--trace-directory', type=Path, required=True, help='Directory containing exported gpu.xml')
    parser.add_argument('--output', type=Path, required=True, help='Directory for summary JSON and timeline CSVs')
    parser.add_argument('--process', default='java', help='Process name or exact label, e.g. "java (123)"; default: java')
    parser.add_argument('--toc', type=Path, help='Optional saved TOC XML; otherwise use trace-directory/toc.xml if present')
    parser.add_argument('--encoders', type=Path, help='Optional saved encoder XML; otherwise use trace-directory/labels-and-encoders.xml if present')
    args = parser.parse_args()
    try:
        analyze(args)
    except (OSError, ValueError, KeyError, ET.ParseError) as error:
        parser.exit(1, f'error: {error}\n')


if __name__ == '__main__':
    main()
