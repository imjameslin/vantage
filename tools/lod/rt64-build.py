#!/usr/bin/env python3
"""CPU-only Prompt6 sparse64 export of selected LodStore columns, with provenance.

python3 tools/lod/rt64-build.py
python3 tools/lod/rt64-data-test.py

Reads stores/cache without modifying them. Generated helpers, logs and artifacts
stay under the project build directory. Does not start Minecraft or a GPU device.
The captured client jar supplies registry IDs and LodStore naming defaults through
a headless Bootstrap helper. C++ handles all voxel loops; Python streams LDS1.
"""
import argparse
import hashlib
import json
import math
from pathlib import Path
import runpy
import struct
import subprocess
import time

ROOT = Path(__file__).resolve().parents[2]


def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as f:
        for b in iter(lambda: f.read(1 << 20), b''):
            h.update(b)
    return h.hexdigest()


def selected_columns(snapshot, out, manifest):
    """Stream/hash every source layer, retaining only column/section coordinates."""
    source = snapshot / 'sections.lds.gz'
    cache = out / 'selection.json'
    key = [digest(source), manifest['files']['sections.lds.gz']['rawSha256']]
    if cache.exists():
        data = json.loads(cache.read_text())
        if data['key'] == key:
            return data
    Blob = runpy.run_path(str(ROOT / 'tools/lod/audit-snapshot.py'))['Blob']
    columns = set()
    sections = [0] * 8
    layers = [0] * 3
    with Blob(snapshot, 'sections.lds.gz', manifest['files']['sections.lds.gz']) as b:
        magic, count = b.unpack('>II')
        if magic != 0x4c445331 or count != manifest['geometry']['selectedSections']:
            raise ValueError('invalid LDS1 header')
        last = -1
        for _ in range(count):
            slot, x, y, z, level = b.unpack('>5i')
            if not last < slot < 262144 or not 0 <= level <= 7:
                raise ValueError('invalid LDS1 section')
            last = slot
            scale = 16 << level
            if x % scale or y % scale or z % scale:
                raise ValueError('unaligned LDS1 section')
            columns.add((level, x // scale, z // scale))
            sections[level] += 1
            b.skip(48)
            for layer in range(3):
                arena, first, n, stride, size = b.unpack('>4iq')
                if arena == -1:
                    if (first, n, stride, size) != (0, 0, 0, 0):
                        raise ValueError('invalid absent layer')
                elif n <= 0 or stride not in (48, 112) or size != n * stride:
                    raise ValueError('invalid LDS1 layer payload')
                layers[layer] += n
                b.skip(size)
        b.finish()
    # A selected coarse column must not overlap a selected descendant.
    for level, nx, nz in columns:
        for parent in range(level + 1, 8):
            if (parent, nx // (1 << (parent-level)), nz // (1 << (parent-level))) in columns:
                raise ValueError('overlapping selected columns: cannot silently overwrite')
    per_level = [sum(k[0] == i for k in columns) for i in range(8)]
    expected = manifest['lod']['state']['selectedPerLevel']
    data = dict(key=key, columns=sorted(columns), sectionsByLevel=sections,
                selectedColumnsByLevel=per_level, capturedSelectedByLevel=expected,
                captureColumnsWithoutSourceSections=[expected[i]-per_level[i] for i in range(len(expected))],
                payloadQuadsByLayer=layers)
    if layers != manifest['geometry']['payloadQuadsByLayer']:
        raise ValueError('LDS1 payload totals disagree with capture')
    cache.write_text(json.dumps(data) + '\n')
    return data


REGISTRY_JAVA = r'''
package net.minecraft.client.renderer.chunk;
import java.nio.file.*;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
public class Rt64Registry {
 public static void main(String[] args) throws Exception {
  SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
  StringBuilder s = new StringBuilder();
  for (var state : Block.BLOCK_STATE_REGISTRY)
   s.append(Block.BLOCK_STATE_REGISTRY.getId(state)).append('\t')
    .append(state.isAir() ? 1 : 0).append('\t').append(state.isSolidRender() ? 1 : 0)
    .append('\t').append(state).append('\n');
  Files.writeString(Path.of(args[0]), s);
  s = new StringBuilder("generator\t" + LodGen.versionTag() + "\n");
  for(int i=0;i<8;i++) s.append(i).append('\t').append(LodStore.levelDir(i)).append('\n');
  Files.writeString(Path.of(args[1]), s);
 }
}
'''


def registry(jar, properties, out):
    java = ROOT / 'mc/runtime/java-runtime-epsilon/jre.bundle/Contents/Home/bin'
    cp = ':'.join(map(str, [jar] + sorted(p for p in (ROOT/'mc/libraries').rglob('*.jar')
                                          if 'natives' not in p.name and 'osx-x86_64' not in p.name)))
    src = out / 'Rt64Registry.java'
    src.write_text(REGISTRY_JAVA)
    classes = out / 'classes'
    classes.mkdir(exist_ok=True)
    with (out/'registry.log').open('w') as log:
        subprocess.run([str(java/'javac'), '-J-Xmx128m', '-proc:none', '-implicit:none',
                        '-cp', cp, '-d', str(classes), str(src)], check=True,
                       cwd=out, stdout=log, stderr=subprocess.STDOUT, timeout=60)
        subprocess.run([str(java/'java'), '-Xmx256m', '-XX:ActiveProcessorCount=2',
                        '-Djava.awt.headless=true', *['-D'+k+'='+v for k,v in properties.items()
                                                     if k.startswith('mc.lod.')],
                        '-cp', str(classes)+':'+cp, 'net.minecraft.client.renderer.chunk.Rt64Registry',
                        str(out/'registry.tsv'), str(out/'store-config.tsv')],
                       check=True, cwd=out, stdout=log, stderr=subprocess.STDOUT, timeout=60)
    config = dict(line.split('\t') for line in (out/'store-config.tsv').read_text().splitlines())
    air = [int(line.split('\t')[0]) for line in (out/'registry.tsv').read_text().splitlines()
           if line.split('\t')[1] == '1']
    if not air:
        raise ValueError('registry contains no air states')
    return config, air


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--snapshot', type=Path, default=ROOT/'build/lod-snapshots/farsight-forest-s2')
    p.add_argument('--store', type=Path, default=ROOT/'run/s3-palette-check/lod/sp/bench/minecraft_overworld')
    p.add_argument('--out', type=Path, default=ROOT/'build/lod-rt64')
    p.add_argument('--jar', type=Path)
    p.add_argument('--generated-store', type=Path, help='explicit matching seedgen store; never generated here')
    p.add_argument('--near-radius', type=float, default=192)
    p.add_argument('--max-nodes', type=int, default=100_000_000)
    p.add_argument('--timeout', type=int, default=300, help='bounded C++ CPU subprocess seconds per tree')
    a = p.parse_args()
    started = time.monotonic()
    a.out = a.out.resolve()
    if not a.out.is_relative_to(ROOT/'build'):
        p.error('outputs must stay under the project build directory')
    a.out.mkdir(parents=True, exist_ok=True)
    manifest = json.loads((a.snapshot/'complete.json').read_text())
    receipt = json.loads((a.snapshot/'receipt.json').read_text())
    if a.jar is None:
        a.jar = Path(receipt['command'][receipt['command'].index('--jar')+1])
    a.jar = a.jar.resolve()
    expected_jar_hash = receipt['jarSha256'].get(str(a.jar))
    actual_jar_hash = digest(a.jar)
    if expected_jar_hash and actual_jar_hash != expected_jar_hash:
        raise ValueError('capture jar hash mismatch')
    config, air = registry(a.jar, manifest['config']['properties'], a.out)
    generated = sorted(a.store.glob('gen-*-'+config['generator']))
    if a.generated_store:
        generated = [a.generated_store]
    if len(generated) != 1:
        raise ValueError('need exactly one matching seedgen store (or --generated-store): '+str(generated))
    print('Registry air IDs:', air, 'store config:', config, 'generated:', generated[0], flush=True)
    selection = selected_columns(a.snapshot, a.out, manifest)
    print('Captured columns:', selection['selectedColumnsByLevel'], flush=True)
    binary = a.out/'rt64-builder'
    subprocess.run(['clang++', '-O3', '-std=c++17', '-Wall', '-Wextra',
                    str(ROOT/'native/tests/lod_rt64_data.cpp'), '-lz', '-o', str(binary)],
                   check=True, cwd=a.out, timeout=60)
    jobs = [('tree', selection['columns'])]
    # A disk, expanded only to complete intersecting L0 columns. This is a proxy
    # for RD12 near terrain coverage, never the capture's cleared GUI depth.
    camera = [float(v) for v in manifest['config']['properties']['bench.pos'].split(',')]
    if a.near_radius > 0:
        near = []
        r = a.near_radius
        for nz in range(math.floor((camera[2]-r)/16), math.floor((camera[2]+r)/16)+1):
            for nx in range(math.floor((camera[0]-r)/16), math.floor((camera[0]+r)/16)+1):
                dx = max(nx*16-camera[0], 0, camera[0]-(nx+1)*16)
                dz = max(nz*16-camera[2], 0, camera[2]-(nz+1)*16)
                if dx*dx+dz*dz <= r*r:
                    near.append([0, nx, nz])
        jobs.append(('near-tree', near))
    # Export the small mask first so it is available even if the far cap fails.
    for name, columns in reversed(jobs):
        source = a.out/(name+'-columns.tsv')
        source.write_text(''.join('\t'.join(map(str,c))+'\n' for c in columns))
        prefix = a.out/name
        subprocess.run([str(binary), str(a.store.resolve()), str(generated[0].resolve()),
                        str(a.out/'store-config.tsv'), str(a.out/'registry.tsv'),
                        str(source), str(prefix), str(a.max_nodes),
                        *(['--opaque-only'] if name == 'near-tree' else [])],
                       check=True, cwd=a.out, timeout=a.timeout)
        meta_path = prefix.with_suffix('.json')
        meta = json.loads(meta_path.read_text())
        used_files = []
        for line in (a.out/(name+'-files.tsv')).read_text().splitlines():
            path, size, mtime = line.split('\t')
            f = Path(path)
            st = f.stat()
            if st.st_size != int(size) or st.st_mtime_ns != int(mtime):
                raise ValueError('store changed during export: '+path)
            used_files.append(dict(path=str(f.relative_to(ROOT)) if f.is_relative_to(ROOT) else str(f),
                                   bytes=st.st_size, mtimeNs=st.st_mtime_ns, sha256=digest(f)))
        audit_path = a.out/(name+'-sources.json')
        audit_path.write_text(json.dumps(used_files, indent=2)+'\n')
        meta.update(format='raw little-endian {uint64 mask; uint32 childBase; uint32 material;}[]',
                    rootIndex=0, childOrder='x+4*y+16*z; contiguous occupancy rank',
                    materialEncoding='registry state ID + 1; all registry isAir states omitted',
                    leafMaterials=prefix.name+'-materials.bin',
                    compactLeafContract='childBase bit31 set: direct children are uint32 materials in leafMaterials, indexed by (childBase & 0x7fffffff) + rank(mask, bit). Each child is terminal at parent.size/4. Otherwise direct children are 16-byte nodes in tree.bin. mask==0 terminal behavior unchanged.',
                    airStateIds=air, capture=str(a.snapshot.relative_to(ROOT)),
                    captureManifestSha256=digest(a.snapshot/'complete.json'),
                    registryJarSha256=actual_jar_hash, registryJarMatchesReceipt=bool(expected_jar_hash),
                    registryFile='registry.tsv', sourceAudit=audit_path.name,
                    storeConfiguration=config, generatedStore=str(generated[0].relative_to(ROOT)),
                    sha256=digest(prefix.with_suffix('.bin')),
                    leafMaterialsSha256=digest(a.out/(name+'-materials.bin')),
                    snapshotExact=False, footprintStoppingTrustworthy=False,
                    sourceSelection='snapshot selected columns, all vertical cells and materials'
                                    if name == 'tree' else 'added L0 near-coverage proxy, only registry isSolidRender full opaque states',
                    captureSelection={k:v for k,v in selection.items() if k not in ('key','columns')})
        meta['limitations'] = [
            'Capture stores no LodData blobs or per-node voxel hashes. Current persistent store cannot be certified as the captured state; exact mesh/source match is unproven.',
            'All cells of every recovered selected column are included, including buried interior cells, air above terrain, cutout and fluid states. No mesh surface reconstruction or face filtering.',
            'LodData already seals dark air and replaces hidden opaque variants with stone; those stored simplifications are preserved.',
            'All nonair states are voxel boxes; shape, alpha, fluid transparency, lighting, biome tint and tree billboard geometry are not reconstructed.',
            'A 4-way tree spans even power-of-two cell sizes. Odd LOD cells are represented by repeated smaller power-of-four terminals without changing occupancy or state.',
            'Interior material comes only from an aligned stored cell at the matching level and same source preference (real first, then matching seedgen). Missing/air representatives are zero and require descent. Highest nodes beyond stored levels have zero representative.',
            'Coarse representative materials have store provenance but no capture-time consistency proof. Disable footprint stopping for a conservative workload; a performance pass cannot establish the original quality target.',
            'Near tree retains only registry isSolidRender states, omitting leaves, water, plants and other nonopaque/special states. It is a current-store conservative coverage proxy, not real near depth; capture depth was cleared by GUI.',
        ]
        if name == 'near-tree':
            meta.update(nearRadius=r, nearCenterXZ=[camera[0], camera[2]],
                        nearSelection='horizontal disk intersects L0 column AABB; whole columns retained',
                        snapshotSelectedColumns=0, addedNearColumns=len(columns))
        meta['totalBuilderElapsedSeconds'] = round(time.monotonic()-started, 3)
        meta_path.write_text(json.dumps(meta, indent=2)+'\n')
        print(name, json.dumps({k:meta[k] for k in ('origin','size','nodeCount','bytes','coverage','representatives')}), flush=True)


if __name__ == '__main__':
    main()
