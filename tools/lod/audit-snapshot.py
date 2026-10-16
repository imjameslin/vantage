#!/usr/bin/env python3
"""Offline, read-only farsight-scene-1 integrity and reference auditor (stdlib only).

Usage: python3 tools/lod/audit-snapshot.py <directory-or-complete.json>
       python3 tools/lod/audit-snapshot.py --self-test

ABI source: bench/src/bench/LodSnapshot.java. LDS1/LDG1/LDV1 metadata is BE;
section info/table, optional indirect args/items, and opaque GPU payloads are LE.
Source payloads are hashed and skipped, never decoded/expanded. This validates
capture structure, not rendering, visibility correctness, replay completeness,
or performance. LDV1 is optional; absent LDV1 provides no post-cull draw list.

Bounds: aggregate raw input <=8 GiB, JSON <=1 MiB, 262144 section slots, 64
arenas, 4096 groups. Fixed section arrays ~17 MiB, arena intervals <=6 MiB,
coverage <=22 MiB. Interval sorting peaks below 40 MiB; optional LDV1 keeps
at most 16 bytes per cluster in ONE 256 MiB arena (~86 MiB at stride 48).
Streaming buffers are <=1 MiB, including hostile gzip expansion. No object
per source quad or retained list of clusters. These bounds leave ample room
below 512 MiB RSS with CPython; peak RSS is reported, not used as a speed test.
"""

import argparse
from array import array
import copy
import gzip
import hashlib
import json
import math
import os
from pathlib import Path
import re
import resource
import stat
import struct
import sys
import tempfile
import time
import zlib


MAX_RAW = 8 << 30
CHUNK = 1 << 20
SLOTS = 1 << 18
ARENAS = 64
ARENA_BYTES = 256 << 20
MAX_GROUPS = 4096
UNIFORMS = ('projection', 'globals', 'fog', 'terrain', 'cull-view')
TEXTURES = ('atlas', 'lightmap', 'frame-color', 'frame-depth')
BE_LAYER = struct.Struct('>4iq')
BE_GROUP = struct.Struct('>6iq')
BE_ITEM = struct.Struct('>4I')


class AuditError(Exception):
    pass


def require(ok, message):
    if not ok:
        raise AuditError(message)


def integer(value, name, low=0, high=MAX_RAW):
    require(type(value) is int and low <= value <= high,
            f'{name}: expected integer in [{low}, {high}]')
    return value


def vector(value, name, count, low=0, high=MAX_RAW):
    require(isinstance(value, list) and len(value) == count, f'{name}: expected {count} entries')
    return [integer(v, name, low, high) for v in value]


def obj(value, name):
    require(isinstance(value, dict), f'{name}: expected object')
    return value


def same(actual, expected, name):
    require(actual == expected, f'{name}: got {actual}, expected {expected}')


def unique_object(pairs):
    out = {}
    for key, value in pairs:
        require(key not in out, f'duplicate JSON key: {key}')
        out[key] = value
    return out


def json_int(s):
    require(len(s) <= 20, 'JSON integer too long')
    return int(s)


def json_float(s):
    value = float(s)
    require(math.isfinite(value), 'nonfinite JSON number')
    return value


def regular_open(path):
    # O_NOFOLLOW plus fstat also rejects symlinks/FIFOs without reading them.
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    try:
        require(stat.S_ISREG(os.fstat(fd).st_mode), f'{path.name}: not a regular file')
        return os.fdopen(fd, 'rb')
    except BaseException:
        os.close(fd)
        raise


class Blob:
    """Single gzip member with CRC, raw length/SHA, and bounded decompression."""
    def __init__(self, directory, name, meta):
        self.name, self.meta = name, meta
        self.file = regular_open(directory / name)
        try:
            same(os.fstat(self.file.fileno()).st_size, meta['gzipBytes'], name + ': gzipBytes')
        except BaseException:
            self.file.close()
            raise
        self.z = zlib.decompressobj(31)
        self.digest = hashlib.sha256()
        self.pending = b''
        self.buf = b''
        self.at = self.pos = self.produced = 0
        self.done = False

    def __enter__(self):
        return self

    def __exit__(self, *args):
        self.file.close()

    def error(self, message):
        raise AuditError(f'{self.name}@{self.pos}: {message}')

    @property
    def remaining(self):
        return self.meta['rawBytes'] - self.pos

    def fill(self):
        if self.at < len(self.buf):
            return True
        self.buf, self.at = b'', 0
        while not self.done:
            compressed = self.pending or self.file.read(65536)
            self.pending = b''
            if not compressed:
                self.error('truncated gzip member')
            try:
                raw = self.z.decompress(compressed, min(CHUNK, self.meta['rawBytes'] - self.produced + 1))
            except zlib.error as e:
                self.error(f'invalid gzip/CRC: {e}')
            self.pending = self.z.unconsumed_tail
            self.produced += len(raw)
            if self.produced > self.meta['rawBytes']:
                self.error('decompressed data exceeds declared rawBytes')
            self.digest.update(raw)
            if self.z.eof:
                if self.z.unused_data or self.pending or self.file.read(1):
                    self.error('trailing bytes or concatenated gzip member')
                self.done = True
                if self.produced != self.meta['rawBytes']:
                    self.error('rawBytes mismatch')
                if self.digest.hexdigest() != self.meta['rawSha256']:
                    self.error('rawSha256 mismatch')
            if raw:
                self.buf = raw
                return True
        return False

    def exact(self, count):
        if not 0 <= count <= min(CHUNK, self.remaining):
            self.error(f'truncated record or oversized read ({count} bytes)')
        if count == 0:
            return b''
        if not self.fill():
            self.error('truncated record')
        if count <= len(self.buf) - self.at:
            out = self.buf[self.at:self.at + count]
            self.at += count
            self.pos += count
            return out
        parts = []
        while count:
            if not self.fill():
                self.error('truncated record')
            n = min(count, len(self.buf) - self.at)
            parts.append(self.buf[self.at:self.at + n])
            self.at += n
            self.pos += n
            count -= n
        return b''.join(parts)

    def skip(self, count):
        if not 0 <= count <= self.remaining:
            self.error('payload exceeds remaining rawBytes')
        while count:
            if not self.fill():
                self.error('truncated payload')
            n = min(count, len(self.buf) - self.at)
            self.at += n
            self.pos += n
            count -= n

    def unpack(self, fmt):
        return struct.unpack(fmt, self.exact(struct.calcsize(fmt)))

    def finish(self):
        if self.fill() or self.pos != self.meta['rawBytes']:
            self.error('unexpected trailing raw data')


def signed32(n):
    return ((n + (1 << 31)) & 0xffffffff) - (1 << 31)


def table_bytes(origin, base):
    x, y, z, level = origin
    return struct.pack('<8f', *(signed32(v - b) for v, b in zip((x, y, z), base)),
                       (1 << level) / 16.0,
                       *(signed32(b - v) for v, b in zip((x, y, z), base)), 0.0)


def texture_bpp(fmt):
    require(isinstance(fmt, str), 'texture format must be a string')
    match = re.fullmatch(r'(R|RG|RGBA)(8|16|32)_(UNORM|SNORM|UINT|SINT|FLOAT)', fmt)
    if match:
        channels, bits, kind = match.groups()
        require(not (bits == '8' and kind == 'FLOAT') and
                not (bits == '32' and kind in ('UNORM', 'SNORM')), f'unsupported texture format: {fmt}')
        return len(channels) * int(bits) // 8
    known = {'RGB10A2_UNORM': 4, 'RGB10A2_UINT': 4, 'RG11B10_FLOAT': 4,
             'D32_FLOAT': 4, 'D16_UNORM': 2, 'S8_UINT': 1}
    # Packed depth/stencil plane copies are deliberately unsupported: Metal blits
    # only the depth plane, which is not necessarily GpuFormat.blockSize().
    require(fmt in known, f'unsupported texture format/plane: {fmt}')
    return known[fmt]


class Auditor:
    def __init__(self, path, progress=False):
        self.path = Path(path)
        if self.path.is_dir():
            self.path /= 'complete.json'
        self.directory = self.path.parent
        self.progress = progress
        with regular_open(self.path) as f:
            raw = f.read(CHUNK + 1)
        require(len(raw) <= CHUNK, 'manifest exceeds 1 MiB')
        self.manifest_sha = hashlib.sha256(raw).hexdigest()
        self.m = obj(json.loads(raw, object_pairs_hook=unique_object, parse_int=json_int,
                               parse_float=json_float, parse_constant=json_float), 'manifest')
        same(self.m.get('schema'), 'farsight-scene-1', 'schema')
        require(self.m.get('complete') is True, 'capture is not complete')
        self.base = vector(self.m.get('cullBase'), 'cullBase', 3, -(1 << 31), (1 << 31) - 1)
        for key in ('frame', 'selection'):
            integer(self.m.get(key), key, 0, (1 << 63) - 1)
        self.files = obj(self.m.get('files'), 'files')
        require(1 <= len(self.files) <= 256, 'file count outside [1,256]')
        self.total_raw = self.total_gzip = 0
        for name, meta in self.files.items():
            require(re.fullmatch(r'[a-z0-9][a-z0-9._-]{0,95}\.gz', name) is not None,
                    f'unsafe/unsupported blob name: {name}')
            obj(meta, name)
            self.total_raw += integer(meta.get('rawBytes'), name + '.rawBytes')
            self.total_gzip += integer(meta.get('gzipBytes'), name + '.gzipBytes', 18, MAX_RAW + CHUNK * 64)
            require(isinstance(meta.get('rawSha256'), str) and
                    re.fullmatch('[0-9a-f]{64}', meta['rawSha256']), name + ': invalid rawSha256')
        require(self.total_raw <= MAX_RAW, 'aggregate raw input exceeds 8 GiB cap')
        require(self.total_gzip <= MAX_RAW + CHUNK * 64, 'aggregate compressed input exceeds cap')
        self.geometry = obj(self.m.get('geometry'), 'geometry')
        self.expect_sections = integer(self.geometry.get('selectedSections'), 'selectedSections', 1, SLOTS)
        self.expect_groups = integer(self.geometry.get('groups'), 'groups', 1, MAX_GROUPS)
        self.expect_clusters = integer(self.geometry.get('submittedClusters'), 'submittedClusters', 1, MAX_RAW // 16)
        self.expect_quads = integer(self.geometry.get('submittedQuads'), 'submittedQuads', 1, MAX_RAW // 48)
        self.expected_layer = vector(self.geometry.get('payloadQuadsByLayer'), 'payloadQuadsByLayer', 3)
        self.expected_level = vector(self.geometry.get('payloadQuadsByLevel'), 'payloadQuadsByLevel', 8)
        require(sum(self.expected_layer) <= self.total_raw // 48, 'payload count exceeds raw byte budget')
        same(sum(self.expected_layer), sum(self.expected_level), 'manifest payload count sums')
        self.used_files = set()
        self.textures = {}

    def note(self, message):
        if self.progress:
            print('[snapshot-audit] ' + message, file=sys.stderr, flush=True)

    def open_blob(self, name, repeat=False):
        require(name in self.files, f'missing file metadata: {name}')
        require(repeat or name not in self.used_files, f'duplicate file reference: {name}')
        self.used_files.add(name)
        return Blob(self.directory, name, self.files[name])

    def auxiliary(self):
        for label in UNIFORMS:
            with self.open_blob(label + '.bin.gz') as b:
                require(1 <= b.remaining <= 65536, label + ': uniform size out of bounds')
                if label == 'cull-view':
                    same(b.remaining, 128, 'cull-view byte size')
                b.skip(b.remaining)
                b.finish()
        for label in TEXTURES:
            meta = obj(self.m.get(label), label)
            bpp = texture_bpp(meta.get('format'))
            base = integer(meta.get('baseMip'), label + '.baseMip', 0, 30)
            mips = meta.get('mips')
            require(isinstance(mips, list) and 1 <= len(mips) <= 31 - base, label + ': invalid mip count')
            if label.startswith('frame-'):
                same((base, len(mips)), (0, 1), label + ': mip range')
            previous = None
            total = 0
            dims = []
            for index, mip in enumerate(mips):
                obj(mip, label + '.mip')
                level = integer(mip.get('level'), 'mip.level', 0, 30)
                same(level, base + index, label + ': consecutive mip levels')
                w = integer(mip.get('width'), 'mip.width', 1, 1 << 27)
                h = integer(mip.get('height'), 'mip.height', 1, 1 << 27)
                size = w * h * bpp
                require(size <= 128 << 20, label + ': texture exceeds 128 MiB cap')
                if previous:
                    same((w, h), (max(1, previous[0] // 2), max(1, previous[1] // 2)), label + ': mip dimensions')
                previous = (w, h)
                name = f'{label}-mip{level}.bin.gz'
                same(mip.get('file'), name, label + ': mip filename')
                with self.open_blob(name) as b:
                    same(b.remaining, size, label + ': texture byte count')
                    b.skip(size)
                    b.finish()
                total += size
                dims.append([level, w, h])
            self.textures[label] = {'format': meta['format'], 'mips': dims, 'rawBytes': total}
        same(self.textures['frame-color']['mips'], self.textures['frame-depth']['mips'], 'frame color/depth dimensions')

    def sections(self):
        self.arenas = array('i', [-1]) * (SLOTS * 3)
        self.first = array('I', [0]) * (SLOTS * 3)
        self.counts = array('I', [0]) * (SLOTS * 3)
        self.offsets = array('Q', [0]) * (SLOTS * 3)
        self.levels = bytearray([255]) * SLOTS
        self.arena_strides = [0] * ARENAS
        intervals = [array('Q') for _ in range(ARENAS)]
        self.payload_layer = [0] * 3
        self.payload_level = [0] * 8
        self.payload_matrix = [[0] * 8 for _ in range(3)]
        self.section_levels = [0] * 8
        self.stride_quads = {48: 0, 112: 0}
        total = 0
        with self.open_blob('sections.lds.gz') as b:
            magic, count = b.unpack('>II')
            same(magic, 0x4c445331, 'LDS1 magic')
            same(count, self.expect_sections, 'LDS1 section count')
            require(count <= b.remaining // 140, 'LDS1 count exceeds remaining headers')
            last_slot = -1
            for ordinal in range(count):
                slot, x, y, z, level = b.unpack('>5i')
                require(last_slot < slot < SLOTS, f'LDS1 section {ordinal}: slot order/range')
                require(0 <= level <= 30, f'LDS1 slot {slot}: invalid level')
                last_slot = slot
                origin = (x, y, z, level)
                same(b.exact(16), struct.pack('<3if', x, y, z, 1.0), f'LDS1 slot {slot}: LE info origin/settled weight')
                same(b.exact(32), table_bytes(origin, self.base), f'LDS1 slot {slot}: LE table/cullBase/scale')
                bucket = min(7, level)
                self.levels[slot] = level
                self.section_levels[bucket] += 1
                for layer in range(3):
                    arena, first, n, stride, size = BE_LAYER.unpack(b.exact(24))
                    where = f'LDS1 slot {slot} layer {layer}'
                    require(-1 <= arena < ARENAS and first >= 0 and n >= 0 and size >= 0, where + ': invalid arena/range')
                    if arena == -1:
                        same((first, n, stride, size), (0, 0, 0, 0), where + ': absent layer')
                        continue
                    require(stride in (48, 112) and n > 0 and first % 8 == 0, where + ': stride/count/alignment')
                    same(size, n * stride, where + ': payload size')
                    require((first + n) * stride <= ARENA_BYTES, where + ': arena range exceeds 256 MiB')
                    require(self.arena_strides[arena] in (0, stride), where + ': mixed strides in arena')
                    self.arena_strides[arena] = stride
                    key = slot * 3 + layer
                    self.arenas[key], self.first[key], self.counts[key] = arena, first, n
                    self.offsets[key] = total
                    intervals[arena].append((first << 32) | (first + n))
                    self.payload_layer[layer] += n
                    self.payload_level[bucket] += n
                    self.payload_matrix[layer][bucket] += n
                    self.stride_quads[stride] += n
                    total += n
                    b.skip(size)
            b.finish()
        for arena, spans in enumerate(intervals):
            end = 0
            for span in sorted(spans):
                require(span >> 32 >= end, f'LDS1 arena {arena}: overlapping source allocations')
                end = span & 0xffffffff
        same(self.payload_layer, self.expected_layer, 'payloadQuadsByLayer')
        same(self.payload_level, self.expected_level, 'payloadQuadsByLevel')
        self.payload_quads = total
        self.note(f'LDS1 passed: {count} sections, {total} source quads (payloads hashed without expansion)')

    def group_header(self, b):
        layer, arena, stride, fade, level, count, quads = BE_GROUP.unpack(b.exact(32))
        require(0 <= layer < 3 and 0 <= arena < ARENAS and stride in (48, 112), 'LDG1 invalid group layer/arena/stride')
        same(self.arena_strides[arena], stride, 'LDG1 arena stride')
        same(fade, 0, 'LDG1 settled group fade')
        require(-1 <= level <= 30, 'LDG1 diagnostic level out of range')
        require(1 <= count <= quads <= min(count * 64, ARENA_BYTES // stride), 'LDG1 invalid cluster/quad count')
        require(count <= b.remaining // 16, 'LDG1 cluster count exceeds remaining records')
        return layer, arena, stride, fade, level, count, quads

    def groups(self):
        referenced = bytearray(SLOTS)
        # Detect duplicate/overlapping submitted source ranges; one bit per stored
        # quad, bounded by raw-byte cap / minimum stride. No geometry expansion.
        covered = bytearray((self.payload_quads + 7) // 8)
        self.submitted_layer = [0] * 3
        self.submitted_level = [0] * 8
        self.submitted_matrix = [[0] * 8 for _ in range(3)]
        self.facing = [0] * 8
        clusters = quads = 0
        with self.open_blob('groups.ldg.gz') as b:
            magic, groups = b.unpack('>II')
            same(magic, 0x4c444731, 'LDG1 magic')
            same(groups, self.expect_groups, 'LDG1 group count')
            require(groups <= b.remaining // 48, 'LDG1 group count exceeds remaining records')
            for group in range(groups):
                layer, arena, stride, fade, diag, count, group_quads = self.group_header(b)
                counted = 0
                for _ in range(count):
                    vertex, packed, lo, hi = BE_ITEM.unpack(b.exact(16))
                    slot, n, facing = packed & 0xfffff, ((packed >> 20) & 63) + 1, (packed >> 26) & 7
                    require(slot < SLOTS and self.levels[slot] != 255, f'LDG1 group {group}: missing source section {slot}')
                    require(vertex <= 0x7fffffff and vertex % 4 == 0 and packed >> 30 == 0 and facing < 7,
                            f'LDG1 group {group}: invalid vertex/packed flags/facing')
                    if packed & (1 << 29):
                        same((lo, hi), (0, 0), 'LDG1 unusable bounds')
                    else:
                        require(lo >> 30 == hi >> 30 == 0 and
                                all(((lo >> s) & 1023) <= ((hi >> s) & 1023) for s in (0, 10, 20)), 'LDG1 inverted/invalid bounds')
                    key = slot * 3 + layer
                    start = vertex // 4
                    require(self.arenas[key] == arena and self.arena_strides[arena] == stride,
                            f'LDG1 group {group} slot {slot}: source arena/stride mismatch')
                    relative = start - self.first[key]
                    require(0 <= relative and relative + n <= self.counts[key],
                            f'LDG1 group {group} slot {slot}: cluster outside source range')
                    level = self.levels[slot]
                    require(diag == -1 or diag == level, f'LDG1 group {group}: diagnostic level mismatch')
                    bit = self.offsets[key] + relative
                    at, shift = divmod(bit, 8)
                    width = (shift + n + 7) // 8
                    mask = ((1 << n) - 1) << shift
                    old = int.from_bytes(covered[at:at + width], 'little')
                    require(old & mask == 0, f'LDG1 group {group} slot {slot}: duplicate/overlapping submitted range')
                    covered[at:at + width] = (old | mask).to_bytes(width, 'little')
                    referenced[slot] = 1
                    counted += n
                    bucket = min(7, level)
                    self.submitted_layer[layer] += n
                    self.submitted_level[bucket] += n
                    self.submitted_matrix[layer][bucket] += n
                    self.facing[facing] += n
                same(counted, group_quads, f'LDG1 group {group}: quads')
                clusters += count
                quads += counted
            b.finish()
        same(sum(referenced), self.expect_sections, 'LDG1 referenced selectedSections')
        same(clusters, self.expect_clusters, 'submittedClusters')
        same(quads, self.expect_quads, 'submittedQuads')
        self.note(f'LDG1 passed: {groups} groups, {clusters} clusters, {quads} submitted quads')

    def visible(self):
        if 'visible' not in self.m:
            return None
        meta = obj(self.m['visible'], 'visible')
        same(meta.get('file'), 'visible.ldv.gz', 'visible filename')
        same(integer(meta.get('draws'), 'visible.draws', 1, MAX_GROUPS), self.expect_groups, 'visible.draws')
        expected = integer(meta.get('quads'), 'visible.quads', 0, self.expect_quads)
        by_layer, by_level = [0] * 3, [0] * 8
        total = 0
        # Re-read small LDG1 in lockstep: retain references/masks for ONE group.
        # The second reader rechecks hash, so cross-file validation never depends
        # on an unverified replacement of groups.ldg.gz between passes.
        with self.open_blob('visible.ldv.gz') as b, self.open_blob('groups.ldg.gz', repeat=True) as g:
            same(b.unpack('>II'), (0x4c445631, self.expect_groups), 'LDV1 magic/count')
            same(g.unpack('>II'), (0x4c444731, self.expect_groups), 'LDG1 repeat magic/count')
            for ordinal in range(self.expect_groups):
                layer, arena, stride, fade, diag, count, group_quads = self.group_header(g)
                group, code, n, used = b.unpack('>4i')
                same(group, ordinal, 'LDV1 group ordinal')
                same(code, layer + (8 if stride == 48 else 0) + 16 * fade, 'LDV1 layerCode')
                require(0 <= n <= group_quads and 0 <= used <= count, 'LDV1 quad/usedCluster count out of range')
                require((n == 0) == (used == 0), 'LDV1 empty/usedCluster mismatch')
                same(b.unpack('<5I'), (n * 6, 1, 0, 0, 0), 'LDV1 LE indirect args')
                require(4 * n + 8 * used <= b.remaining, 'LDV1 counts exceed remaining payload')
                originals = array('I', [0]) * (used * 2)
                seen = array('Q', [0]) * used
                for c in range(used):
                    vertex, packed, _, _ = BE_ITEM.unpack(g.exact(16))
                    originals[c * 2], originals[c * 2 + 1] = vertex, packed
                g.skip((count - used) * 16)
                max_cluster = -1
                for _ in range(n):
                    entry, = b.unpack('<I')
                    cluster, local = entry >> 6, entry & 63
                    require(cluster < used, 'LDV1 packed cluster outside usedClusterCount')
                    vertex, packed = originals[2 * cluster:2 * cluster + 2]
                    require(local <= ((packed >> 20) & 63), 'LDV1 local quad outside input group cluster')
                    mask = 1 << local
                    require(seen[cluster] & mask == 0, 'LDV1 duplicate visible source quad')
                    seen[cluster] |= mask
                    slot = packed & 0xfffff
                    key = slot * 3 + layer
                    source_quad = vertex // 4 + local
                    require(self.arenas[key] == arena and
                            self.first[key] <= source_quad < self.first[key] + self.counts[key],
                            'LDV1 source quad outside matching section/arena')
                    by_layer[layer] += 1
                    by_level[min(7, self.levels[slot])] += 1
                    max_cluster = max(max_cluster, cluster)
                same(max_cluster + 1, used, 'LDV1 usedClusterCount maximum')
                for c in range(used):
                    item = b.unpack('<2I')
                    target = (originals[c * 2], originals[c * 2 + 1] & 0xfffff) if seen[c] else (0, 0)
                    same(item, target, f'LDV1 group {group} cluster {c}: LE uint2 source item/zeroed hole')
                total += n
                # Drop before allocating the next group, avoiding two live arrays.
                del originals, seen
            g.finish()
            b.finish()
        same(total, expected, 'visible.quads')
        self.note(f'LDV1 passed: {total} post-cull quads')
        return {'draws': self.expect_groups, 'quads': total, 'quadsByLayer': by_layer, 'quadsByLevel': by_level}

    def telemetry(self):
        # gpu.visibleQuads can be asynchronous prior-frame telemetry; LDV1 is the
        # authority for this frame. Do not compare it to visibleQuads[Avg].
        lod = obj(self.m.get('lod'), 'lod')
        state = obj(lod.get('state'), 'lod.state')
        require(state.get('idle') is True, 'lod.state: capture was not settled')
        gpu = obj(state.get('gpu'), 'lod.state.gpu')
        for name, count in (('drawnSections', self.expect_sections), ('drawnClusters', self.expect_clusters),
                            ('drawnQuads', self.expect_quads), ('fading', 0)):
            same(integer(gpu.get(name), 'gpu.' + name), count, 'gpu.' + name)
        same(vector(gpu.get('drawnQuadsByLevel'), 'gpu.drawnQuadsByLevel', 8), self.submitted_level, 'gpu.drawnQuadsByLevel')
        same(vector(gpu.get('drawnQuadsBySlot'), 'gpu.drawnQuadsBySlot', 8), self.facing, 'gpu.drawnQuadsBySlot')
        arena_count = integer(gpu.get('arenas'), 'gpu.arenas', 1, ARENAS)
        require(all(stride == 0 for stride in self.arena_strides[arena_count:]), 'source references absent GPU arena')

    def run(self):
        start = time.monotonic()
        self.auxiliary()
        self.sections()
        self.groups()
        visible = self.visible()
        self.telemetry()
        same(self.used_files, set(self.files), 'unreferenced manifest files')
        peak = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
        peak *= 1 if sys.platform == 'darwin' else 1024
        require(peak < 512 << 20, 'auditor exceeded 512 MiB peak RSS budget')
        return {'status': 'passed', 'manifest': str(self.path.absolute()), 'manifestSha256': self.manifest_sha,
                'filesVerified': len(self.used_files), 'rawBytes': self.total_raw, 'gzipBytes': self.total_gzip,
                'selectedSections': self.expect_sections, 'sectionsByLevel': self.section_levels,
                'groups': self.expect_groups, 'submittedClusters': self.expect_clusters, 'submittedQuads': self.expect_quads,
                'payloadQuadsByLayer': self.payload_layer, 'payloadQuadsByLevel': self.payload_level,
                'payloadQuadsByLayerLevel': self.payload_matrix, 'payloadQuadsByStride': self.stride_quads,
                'submittedQuadsByLayer': self.submitted_layer, 'submittedQuadsByLevel': self.submitted_level,
                'submittedQuadsByLayerLevel': self.submitted_matrix, 'submittedQuadsByFacing': self.facing,
                'unsubmittedPayloadQuads': self.payload_quads - self.expect_quads,
                'textures': self.textures, 'visible': visible, 'peakRssBytes': peak,
                'auditSeconds': round(time.monotonic() - start, 3),
                'limitations': ['GPU source bytes and uniforms are checksum-preserved, not semantically decoded.',
                                'Integrity/reference audit only; no replay, visibility, image-quality or performance proof.',
                                'No post-cull lists verified.' if visible is None else 'LDV1 validates stored references, not culling decisions.']}


def tiny_fixture():
    """Independent, explicit mixed-stride wire writer used only for self-tests."""
    base = [6, 131, 6]
    absent = (-1, 0, 0, 0)
    sections = [(3, (-16, 0, 32, 0), [(0, 8, 3, 48), (1, 16, 2, 112), absent]),
                (17, (64, -32, -64, 1), [(1, 24, 1, 112), (0, 16, 2, 48), (2, 8, 1, 112)]),
                (SLOTS - 1, (-256, 128, 0, 3), [(0, 24, 1, 48), absent, (2, 16, 2, 112)])]
    groups = [(0, 0, 48, -1, [(3, 8, 2, 0), (SLOTS - 1, 24, 1, 6)]),
              (1, 1, 112, 0, [(3, 16, 2, 1)]), (0, 1, 112, 1, [(17, 24, 1, 2)]),
              (1, 0, 48, 1, [(17, 16, 2, 3)]), (2, 2, 112, -1, [(17, 8, 1, 4), (SLOTS - 1, 16, 2, 5)])]
    s = bytearray(struct.pack('>II', 0x4c445331, len(sections)))
    offsets = {'sections': [], 'layers': [], 'groups': [], 'items': []}
    for slot, origin, layers in sections:
        offsets['sections'].append(len(s))
        s += struct.pack('>5i', slot, *origin)
        s += struct.pack('<3if', *origin[:3], 1.0)
        x, y, z, level = origin
        s += struct.pack('<8f', x - 6, y - 131, z - 6, 2 ** level / 16, 6 - x, 131 - y, 6 - z, 0)
        for arena, first, n, stride in layers:
            offsets['layers'].append(len(s))
            s += struct.pack('>4iq', arena, first, n, stride, n * stride)
            # Raw compact/BLOCK words stay opaque to the reader, including zeros.
            s += struct.pack('<I', slot) * (n * stride // 4)
    g = bytearray(struct.pack('>II', 0x4c444731, len(groups)))
    v = bytearray(struct.pack('>II', 0x4c445631, len(groups)))
    for ordinal, (layer, arena, stride, diag, items) in enumerate(groups):
        offsets['groups'].append(len(g))
        g += struct.pack('>6iq', layer, arena, stride, 0, diag, len(items), sum(t[2] for t in items))
        for slot, first, n, face in items:
            offsets['items'].append(len(g))
            g += struct.pack('>4I', first * 4, slot | ((n - 1) << 20) | (face << 26) | (1 << 29), 0, 0)
        # Only the last cluster survives, exercising unused zeroed uint2 holes.
        used = len(items)
        v += struct.pack('>4i', ordinal, layer + (8 if stride == 48 else 0), 1, used)
        v += struct.pack('<5I', 6, 1, 0, 0, 0)
        v += struct.pack('<I', ((used - 1) << 6) | (items[-1][2] - 1))
        for c, (slot, first, n, face) in enumerate(items):
            v += struct.pack('<2I', first * 4, slot) if c == used - 1 else bytes(8)
    raw = {'sections.lds.gz': bytes(s), 'groups.ldg.gz': bytes(g)}
    raw.update({label + '.bin.gz': bytes(n) for label, n in zip(UNIFORMS, (64, 48, 48, 80, 128))})
    m = {'schema': 'farsight-scene-1', 'complete': True, 'frame': 17, 'selection': 2, 'cullBase': base,
         'geometry': {'selectedSections': 3, 'groups': 5, 'submittedClusters': 7, 'submittedQuads': 11,
                      'payloadQuadsByLayer': [5, 4, 3], 'payloadQuadsByLevel': [5, 4, 0, 3, 0, 0, 0, 0]},
         'lod': {'state': {'idle': True, 'gpu': {'arenas': 3, 'fading': 0, 'drawnSections': 3,
                 'drawnClusters': 7, 'drawnQuads': 11, 'drawnQuadsByLevel': [4, 4, 0, 3, 0, 0, 0, 0],
                 'drawnQuadsBySlot': [2, 2, 1, 2, 1, 2, 1, 0]}}}}
    for label, dims, fmt in [('atlas', [(4, 4), (2, 2)], 'RGBA8_UNORM'), ('lightmap', [(2, 2)], 'RGBA8_UNORM'),
                             ('frame-color', [(3, 2)], 'RGBA8_UNORM'), ('frame-depth', [(3, 2)], 'D32_FLOAT')]:
        m[label] = {'format': fmt, 'baseMip': 0, 'mips': []}
        for level, (w, h) in enumerate(dims):
            name = f'{label}-mip{level}.bin.gz'
            m[label]['mips'].append({'level': level, 'width': w, 'height': h, 'file': name})
            raw[name] = bytes(w * h * 4)
    return m, raw, offsets, bytes(v)


def self_test():
    root = Path(__file__).resolve().parents[2] / 'build/lod-snapshot-audit-test'
    root.mkdir(parents=True, exist_ok=True)
    run = Path(tempfile.mkdtemp(prefix='run-', dir=root))
    fixture, raw_fixture, offsets, visible = tiny_fixture()
    results = []

    def case(name, mutate=None, reject=None, compressed_mutate=None, json_mutate=None):
        m, raw = copy.deepcopy(fixture), dict(raw_fixture)
        if mutate:
            mutate(m, raw)
        path = run / name
        path.mkdir()
        m['files'] = {}
        for file, data in raw.items():
            compressed = gzip.compress(data, compresslevel=1, mtime=0)
            m['files'][file] = {'rawBytes': len(data), 'gzipBytes': len(compressed), 'rawSha256': hashlib.sha256(data).hexdigest()}
            if compressed_mutate:
                compressed = compressed_mutate(file, compressed, m)
            (path / file).write_bytes(compressed)
        if json_mutate:
            json_mutate(m)
        (path / 'complete.json').write_text(json.dumps(m) + '\n')
        try:
            result = Auditor(path).run()
        except (AuditError, OSError, ValueError) as e:
            require(reject is not None and reject in str(e), f'self-test {name}: unexpected rejection {e}')
            results.append({'case': name, 'status': 'rejected-as-expected', 'reason': str(e)})
        else:
            require(reject is None, f'self-test {name}: malformed fixture accepted')
            (path / 'audit-result.json').write_text(json.dumps(result, indent=2) + '\n')
            results.append({'case': name, 'status': 'passed'})

    def patch(file, offset, fmt, *values):
        def mutate(m, raw):
            data = bytearray(raw[file])
            struct.pack_into(fmt, data, offset, *values)
            raw[file] = bytes(data)
        return mutate

    def add_visible(m, raw):
        m['visible'] = {'draws': 5, 'quads': 5, 'file': 'visible.ldv.gz'}
        raw['visible.ldv.gz'] = visible

    def vpatch(offset, fmt, *values):
        def mutate(m, raw):
            add_visible(m, raw)
            patch('visible.ldv.gz', offset, fmt, *values)(m, raw)
        return mutate

    case('valid-mixed-48-112-partial-submission')
    case('valid-optional-ldv1-zero-holes', add_visible)

    def large_texture(m, raw):
        m['atlas']['mips'] = [{'level': 0, 'width': 1024, 'height': 1024, 'file': 'atlas-mip0.bin.gz'}]
        raw['atlas-mip0.bin.gz'] = bytes(4 << 20)
        del raw['atlas-mip1.bin.gz']
    case('valid-multichunk-high-expansion', large_texture)
    sec, group = 'sections.lds.gz', 'groups.ldg.gz'
    s0, s1 = offsets['sections'][:2]
    l0 = offsets['layers'][0]
    g0 = offsets['groups'][0]
    i0, i1 = offsets['items'][:2]
    tests = [('section-count', sec, 4, '>I', (0xffffffff,), 'section count'),
             ('section-slot', sec, s0, '>i', (SLOTS,), 'slot order/range'),
             ('duplicate-slot', sec, s1, '>i', (3,), 'slot order/range'),
             ('section-level', sec, s0 + 16, '>i', (-1,), 'invalid level'),
             ('info-endian', sec, s0 + 20, '>i', (-16,), 'LE info'),
             ('info-weight', sec, s0 + 32, '<f', (float('nan'),), 'LE info'),
             ('table-scale', sec, s0 + 48, '<f', (2.0,), 'LE table'),
             ('arena-range', sec, l0, '>i', (64,), 'invalid arena/range'),
             ('negative-count', sec, l0 + 8, '>i', (-1,), 'invalid arena/range'),
             ('bad-stride', sec, l0 + 12, '>i', (64,), 'stride/count/alignment'),
             ('payload-length', sec, l0 + 16, '>q', (1 << 40,), 'payload size'),
             ('arena-offset', sec, l0 + 4, '>i', (1 << 30,), 'arena range exceeds'),
             ('allocation-overlap', sec, offsets['layers'][4] + 4, '>i', (8,), 'overlapping source allocations'),
             ('group-count', group, 4, '>I', (0xffffffff,), 'group count'),
             ('cluster-count', group, g0 + 20, '>i', (0x7fffffff,), 'cluster/quad count'),
             ('group-stride', group, g0 + 8, '>i', (112,), 'arena stride'),
             ('group-arena', group, g0 + 4, '>i', (2,), 'arena stride'),
             ('group-fade', group, g0 + 12, '>i', (1,), 'group fade'),
             ('group-level', group, g0 + 16, '>i', (2,), 'diagnostic level mismatch'),
             ('group-quads', group, g0 + 24, '>q', (4,), 'quads'),
             ('cluster-source-range', group, i0, '>I', (0,), 'outside source range'),
             ('cluster-vertex-alignment', group, i0, '>I', (33,), 'invalid vertex'),
             ('cluster-missing-slot', group, i0 + 4, '>I', (4 | (1 << 29),), 'missing source'),
             ('cluster-flags', group, i0 + 4, '>I', (3 | (1 << 31),), 'packed flags'),
             ('bounds-unusable', group, i0 + 8, '>I', (1,), 'unusable bounds'),
             ('bounds-inverted', group, i0 + 4, '>3I', (3 | (1 << 20), 1, 0), 'inverted/invalid bounds'),
             ('cluster-duplicate', group, i1, '>4I', (32, 3 | (1 << 29), 0, 0), 'duplicate/overlapping')]
    for name, file, offset, fmt, values, reject in tests:
        case(name, patch(file, offset, fmt, *values), reject=reject)
    case('trailing-raw', lambda m, r: r.__setitem__(group, r[group] + b'x'), reject='trailing raw')
    case('truncated-record', lambda m, r: r.__setitem__(group, r[group][:-3]), reject='remaining records')
    case('hash-wrong', json_mutate=lambda m: m['files'][sec].__setitem__('rawSha256', '0' * 64), reject='rawSha256')
    case('raw-length-small', json_mutate=lambda m: m['files'][sec].__setitem__('rawBytes', len(raw_fixture[sec]) - 1), reject='exceeds declared')
    case('raw-length-large', json_mutate=lambda m: m['files'][sec].__setitem__('rawBytes', len(raw_fixture[sec]) + 1), reject='rawBytes mismatch')
    case('gzip-length', json_mutate=lambda m: m['files'][sec].__setitem__('gzipBytes', 18), reject='gzipBytes')
    case('raw-cap', json_mutate=lambda m: m['files'][sec].__setitem__('rawBytes', MAX_RAW), reject='8 GiB cap')

    def compressed_bad(kind):
        def change(file, data, m):
            if file != sec:
                return data
            if kind == 'crc':
                data = data[:-8] + bytes([data[-8] ^ 1]) + data[-7:]
            elif kind == 'truncated':
                data = data[:-4]
            else:
                data += gzip.compress(b'')
            m['files'][file]['gzipBytes'] = len(data)
            return data
        return change
    for kind, message in [('crc', 'invalid gzip/CRC'), ('truncated', 'truncated gzip'), ('concatenated', 'concatenated gzip')]:
        case('gzip-' + kind, compressed_mutate=compressed_bad(kind), reject=message)
    case('manifest-count', json_mutate=lambda m: m['geometry'].__setitem__('submittedQuads', 10), reject='submittedQuads')
    case('manifest-layer', json_mutate=lambda m: m['geometry'].__setitem__('payloadQuadsByLayer', [6, 3, 3]), reject='payloadQuadsByLayer')
    case('manifest-level', json_mutate=lambda m: m['geometry'].__setitem__('payloadQuadsByLevel', [4, 5, 0, 3, 0, 0, 0, 0]), reject='payloadQuadsByLevel')
    case('telemetry-facing', json_mutate=lambda m: m['lod']['state']['gpu'].__setitem__('drawnQuadsBySlot', [0] * 8), reject='drawnQuadsBySlot')
    case('texture-format', json_mutate=lambda m: m['atlas'].__setitem__('format', 'UNKNOWN'), reject='unsupported texture')
    case('texture-size', json_mutate=lambda m: m['atlas']['mips'][0].__setitem__('width', 5), reject='texture byte count')
    case('texture-mip', json_mutate=lambda m: m['atlas']['mips'][1].__setitem__('level', 2), reject='consecutive mip')
    case('unsafe-path', json_mutate=lambda m: m['files'].__setitem__('../outside.gz', dict(m['files'][sec])), reject='unsafe/unsupported')
    case('missing-uniform', json_mutate=lambda m: m['files'].pop('fog.bin.gz'), reject='missing file metadata')
    case('ldv-group-ordinal', vpatch(8, '>i', 1), reject='group ordinal')
    case('ldv-layer-code', vpatch(12, '>i', 0), reject='layerCode')
    case('ldv-indirect', vpatch(24, '<I', 12), reject='indirect args')
    case('ldv-cluster-outside', vpatch(44, '<I', 128), reject='packed cluster')
    case('ldv-local-outside', vpatch(44, '<I', 65), reject='local quad')
    case('ldv-nonzero-hole', vpatch(48, '<I', 1), reject='zeroed hole')
    case('ldv-wrong-source-item', vpatch(56, '<I', 100), reject='source item')
    # Reader-level JSON policy test uses a separate fixture manifest.
    dup = run / 'duplicate-json-key'
    dup.mkdir()
    (dup / 'complete.json').write_text('{"complete":true,"complete":false}')
    try:
        Auditor(dup)
    except AuditError as e:
        require('duplicate JSON key' in str(e), 'duplicate-key test failed')
        results.append({'case': 'duplicate-json-key', 'status': 'rejected-as-expected', 'reason': str(e)})
    else:
        raise AuditError('duplicate JSON key accepted')
    summary = {'status': 'passed', 'cases': len(results), 'fixtures': str(run), 'results': results}
    (run / 'self-test-results.json').write_text(json.dumps(summary, indent=2) + '\n')
    return {k: v for k, v in summary.items() if k != 'results'}


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('capture', nargs='?', help='capture directory or complete.json (read-only)')
    parser.add_argument('--self-test', action='store_true', help='write tiny fixtures only under build/lod-snapshot-audit-test')
    parser.add_argument('--progress', action='store_true', help='emit bounded progress to stderr')
    args = parser.parse_args()
    if bool(args.capture) == args.self_test:
        parser.error('provide either a capture path or --self-test')
    try:
        result = self_test() if args.self_test else Auditor(args.capture, args.progress).run()
        print(json.dumps(result, indent=2, sort_keys=True))
        return 0
    except (AuditError, OSError, ValueError, TypeError, KeyError, RecursionError, MemoryError) as e:
        print(json.dumps({'status': 'failed', 'error': str(e)}), file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
