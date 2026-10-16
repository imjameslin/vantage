#!/usr/bin/env python3
"""Bounded CPU tests: real-format LodStore fixtures -> sparse64 -> dense/ray oracle.

No GPU/game launch. All fixture and compiler output stays in build/lod-rt64-tests.
Tests registry air/opacity, all layers, uniform collapse, holes, rank bit63,
odd LOD cell sizes, negative coordinates, stored representatives, missing data,
source overlap rejection, corrupt input and explicit node caps.
"""
from array import array
import json
import math
from pathlib import Path
import random
import struct
import subprocess
import tempfile
import zlib

ROOT = Path(__file__).resolve().parents[2]


def var(n):
    b = bytearray()
    while n >= 128:
        b.append((n & 127) | 128)
        n >>= 7
    b.append(n)
    return bytes(b)


def blob(level, nx, nz, min_y, height, fn):
    raw = bytearray(struct.pack('>BBiihhH', 2, level, nx, nz, min_y, height, 1))
    biome = b'minecraft:plains'
    raw += struct.pack('>H', len(biome)) + biome
    count = 16 * (height // 4) if level == 0 else 256
    raw += struct.pack('>I', count) + bytes(count)
    for z in range(16):
        for x in range(16):
            runs = []
            for y in range(height):
                value = fn(x, y + min_y, z)
                if runs and runs[-1][0] == value:
                    runs[-1][1] += 1
                else:
                    runs.append([value, 1])
            raw += var(len(runs))
            for state, length in runs:
                raw += var(state) + b'\x00' + var(length)
    raw += var(0)  # no stored tree records
    return struct.pack('>I', len(raw)) + zlib.compress(raw)


def regions(root, entries):
    groups = {}
    for key, value in entries.items():
        level, nx, nz = key
        group = groups.setdefault((level, nx // 32, nz // 32), {})
        group[(nz % 32)*32 + nx % 32] = value
    for (level, rx, rz), slots in groups.items():
        path = root / ('L'+str(level)) / f'r.{rx}.{rz}.lod'
        path.parent.mkdir(parents=True, exist_ok=True)
        sizes = [len(slots.get(i, b'')) for i in range(1024)]
        path.write_bytes(struct.pack('>4i', 0x4c4f4431, level, rx, rz) +
                         struct.pack('>1024i', *sizes) + b''.join(slots.get(i, b'') for i in range(1024)))


def read_tree(prefix):
    meta = json.loads(prefix.with_suffix('.json').read_text())
    nodes = list(struct.iter_unpack('<QII', prefix.with_suffix('.bin').read_bytes()))
    leaves = array('I')
    leaves.frombytes(prefix.with_name(prefix.name+'-materials.bin').read_bytes())
    assert len(nodes) == meta['nodeCount']
    assert len(leaves) == meta['leafMaterialCount']
    assert 16*len(nodes)+4*len(leaves) == meta['bytes']
    return meta, nodes, leaves


def sample(tree, p):
    meta, nodes, leaves = tree
    o, size, i = list(meta['origin']), meta['size'], 0
    if any(not o[a] <= p[a] < o[a]+size for a in range(3)):
        return 0
    while True:
        mask, base, material = nodes[i]
        if mask == 0:
            return material
        size //= 4
        c = [(p[a]-o[a])//size for a in range(3)]
        bit = c[0]+4*c[1]+16*c[2]
        if not mask & (1 << bit):
            return 0
        rank = bin(mask & ((1 << bit)-1)).count('1')
        if base & 0x80000000:
            return leaves[(base & 0x7fffffff)+rank]
        i = base+rank
        o = [o[a]+c[a]*size for a in range(3)]


def boxes(tree):
    meta, nodes, leaves = tree
    result = []
    def walk(n, o, size):
        mask, base, material = n
        if not mask:
            if material:
                result.append((o, size, material))
            return
        size //= 4
        rank = 0
        for bit in range(64):
            if mask & (1 << bit):
                p = (o[0]+(bit%4)*size, o[1]+((bit//4)%4)*size, o[2]+(bit//16)*size)
                child = (0, 0, leaves[(base & 0x7fffffff)+rank]) if base & 0x80000000 else nodes[base+rank]
                walk(child, p, size)
                rank += 1
    walk(nodes[0], meta['origin'], meta['size'])
    return result


def hit(boxes, origin, direction):
    best = math.inf
    for p, size, _ in boxes:
        lo, hi = 0., math.inf
        for a in range(3):
            if direction[a] == 0:
                if not p[a] <= origin[a] < p[a]+size:
                    hi = -1
            else:
                t = sorted(((p[a]-origin[a])/direction[a], (p[a]+size-origin[a])/direction[a]))
                lo, hi = max(lo, t[0]), min(hi, t[1])
        if lo < hi:
            best = min(best, lo)
    return best


def main():
    base = ROOT / 'build/lod-rt64-tests'
    base.mkdir(exist_ok=True)
    out = Path(tempfile.mkdtemp(prefix='run-', dir=base))
    binary = out/'builder'
    subprocess.run(['clang++', '-O2', '-std=c++17', '-Wall', '-Wextra',
                    str(ROOT/'native/tests/lod_rt64_data.cpp'), '-lz', '-o', str(binary)], check=True, timeout=60)
    (out/'config.tsv').write_text('generator\tfixture\n'+''.join(f'{i}\tL{i}\n' for i in range(8)))
    (out/'registry.tsv').write_text(''.join(f'{i}\t{int(i in (0,7,8))}\t{int(i not in (0,2,7,8))}\tfixture-state-{i}\n' for i in range(9)))
    real, gen = out/'real', out/'generated'
    def small(x,y,z):
        if (x,y,z) == (0,0,0): return 7
        if (x,y,z) == (1,1,1): return 8
        if 2<=x<6 and 2<=y<6 and 2<=z<6: return 0 if (x,y,z)==(3,3,3) else 1
        if 8<=x<12 and 0<=y<4 and 0<=z<4: return 4
        if x==y==z and x in (7,11,15): return 2
        return 0
    # L1 and L3 exercise 2- and 8-block cells in the pure power4 spatial tree.
    funcs = {(0,0,0):(-16,32,small),
             (1,1,0):(-8,16,lambda x,y,z: 2 if (x,y,z)==(15,0,15) else 3 if x<4 and 0<=y<4 and z<4 else 0),
             (2,-1,0):(0,4,lambda x,y,z: 5 if x==15 and y==0 and z==0 else 0),
             (3,0,1):(0,2,lambda x,y,z: 6 if (x,y,z)==(0,0,0) else 0)}
    entries = {k:blob(*k,*v) for k,v in funcs.items()}
    # Stored coarser representatives for the finest column: includes true air
    # above occupied children, which must not make occupancy disappear.
    rep = blob(2,0,0,-4,8,lambda x,y,z: 7 if y==0 and x==0 and z==0 else 5)
    regions(real, {k:v for k,v in entries.items() if k[0] in (0,2)} | {(2,0,0):rep})
    regions(gen, {k:v for k,v in entries.items() if k[0] in (1,3)})
    def run(name, keys, extra=(), cap=1000000, reject=None):
        selection = out/(name+'.tsv')
        selection.write_text(''.join(f'{l}\t{x}\t{z}\n' for l,x,z in keys))
        prefix = out/name
        command = [str(binary),str(real),str(gen),str(out/'config.tsv'),str(out/'registry.tsv'),str(selection),str(prefix),str(cap),*extra]
        result = subprocess.run(command, capture_output=True, text=True, timeout=30)
        if reject:
            assert result.returncode and reject in result.stderr, (reject,result.stderr)
            assert not prefix.with_suffix('.bin').exists()
            return
        assert result.returncode == 0, result.stderr
        return read_tree(prefix)
    full = run('full', list(funcs))
    meta = full[0]
    assert meta['coverage']['real'] == 2 and meta['coverage']['generated'] == 2
    assert meta['representatives']['realStore'] > 0 and meta['representatives']['storedAir'] > 0
    assert any(base & 0x80000000 and mask & (1<<63) for mask,base,_ in full[1])
    checked = 0
    for (level,nx,nz),(min_y,height,fn) in funcs.items():
        cell=1<<level
        for y in range(min_y,min_y+height):
            for z in range(16):
                for x in range(16):
                    id=fn(x,y,z); expected=0 if id in (0,7,8) else id+1
                    for offset in (0,cell-1):
                        p=((nx*16+x)*cell+offset,y*cell+offset,(nz*16+z)*cell+offset)
                        assert sample(full,p)==expected,(p,expected,sample(full,p))
                        checked+=1
    assert sample(full,(3,3,3)) == 0 and sample(full,(2,2,2)) == 2
    # Independent input-cube ray oracle, including reverse/diagonal/axis rays.
    tiny = run('tiny', [(0,0,0)])
    dense = [((x,y,z),1,small(x,y,z)+1) for y in range(16) for z in range(16) for x in range(16)
             if small(x,y,z) not in (0,7,8)]
    actual_boxes=boxes(tiny)
    assert ((8,0,0),4,5) in actual_boxes, 'uniform 4 cube was not collapsed'
    rays=[((-10,-10,-10),(1,1,1)),((3.5,3.5,3.5),(1,1,1)),((30,30,30),(-1,-1,-1)),
          ((3.5,30,3.5),(0,-1,0)),((-3,3.5,3.5),(1,0,0)),((0,20,0),(0,1,0))]
    rng=random.Random(613)
    rays += [(tuple(rng.uniform(-10,25) for _ in range(3)),tuple(rng.uniform(-1,1) for _ in range(3))) for _ in range(64)]
    for origin,direction in rays:
        a,b=hit(actual_boxes,origin,direction),hit(dense,origin,direction)
        assert a==b or abs(a-b)<1e-9,(origin,direction,a,b)
    opaque=run('opaque',[(0,0,0)],extra=['--opaque-only'])
    assert sample(tiny,(7,7,7))==3 and sample(opaque,(7,7,7))==0
    assert sample(opaque,(2,2,2))==2
    missing=run('missing',[(0,0,0),(0,5,5)])
    assert missing[0]['coverage']['missing']==1
    run('overlap',[(0,0,0),(1,0,0)],reject='overlapping column')
    run('duplicate',[(0,0,0),(0,0,0)],reject='duplicate column')
    run('cap',[(0,0,0)],cap=1,reject='node cap')
    # Bad zlib and non-registry states fail rather than becoming false air.
    bad = bytearray(entries[(0,0,0)]); bad[-1] ^= 1
    regions(real,{(0,5,5):bytes(bad)})
    run('corrupt',[(0,5,5)],reject='bad zlib')
    regions(real,{(0,6,6):blob(0,6,6,0,16,lambda x,y,z: 99)})
    run('bad-state',[(0,6,6)],reject='invalid state/run')
    result=dict(passed=True,densePointChecks=checked,rayOracleChecks=len(rays),
                controls=['uniform cube','hole','diagonal/reverse/axis rays','rank bit63','negative coordinates',
                          'odd LOD replication','all registry air IDs','opaque near filtering','real/seedgen separation',
                          'stored-air interior requires descent','missing audit','overlap','duplicate','cap','corrupt zlib','bad registry ID'])
    (out/'result.json').write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps(result),str(out))


if __name__ == '__main__':
    main()
