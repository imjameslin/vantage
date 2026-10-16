#!/usr/bin/env python3
"""Prompt6 offscreen feasibility only; no game integration or FPS inference."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[2]


def digest(p):
    h = hashlib.sha256()
    with p.open('rb') as f:
        for b in iter(lambda: f.read(1 << 20), b''):
            h.update(b)
    return h.hexdigest()


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--tree', type=Path, default=ROOT/'build/lod-rt64/tree')
    p.add_argument('--near', type=Path)
    p.add_argument('--snapshot', type=Path, default=ROOT/'build/lod-snapshots/farsight-forest-s2')
    p.add_argument('--mode', choices=('control', 'screen', 'full'), default='screen')
    p.add_argument('--out', type=Path, required=True)
    p.add_argument('--locked', action='store_true', help=argparse.SUPPRESS)
    a = p.parse_args()
    if not a.locked:
        cmd = [sys.executable, str(ROOT/'tools/gpulock.py'), 'timing', '--label', 'rt64-'+a.mode]
        if a.mode != 'control':
            cmd += ['--quiet', '4.0:900']
        cmd += ['--', sys.executable, str(Path(__file__).resolve()), *sys.argv[1:], '--locked']
        os.execv(sys.executable, cmd)
    if os.environ.get('MC_GPU_LOCK_HELD') != 'timing':
        raise SystemExit('Exclusive GPU lane required')
    user = subprocess.run(['pgrep', '-f', '[P]rismLauncher/instances/.*/bin/java '], stdout=subprocess.DEVNULL)
    if user.returncode != 1:
        raise SystemExit('User game running or guard unavailable; not launching')
    a.out.mkdir(parents=True, exist_ok=False)
    exe = ROOT/'build/lod-rt64/bin/rt64'
    source = ROOT/'native/tests/lod_rt64.metal'
    cmd = [str(exe), str(a.tree.resolve()), str(a.snapshot.resolve()), str(source), str(a.out.resolve()), a.mode]
    if a.near:
        cmd.append(str(a.near.resolve()))
    env = dict(os.environ, MTL_DEBUG_LAYER='1' if a.mode == 'control' else '0', MTL_SHADER_VALIDATION='0',
               CLANG_MODULE_CACHE_PATH=str(ROOT/'build/lod-rt64/module-cache'))
    inputs = [exe, source, ROOT/'native/tests/lod_rt64.mm', a.tree.with_suffix('.json'), a.tree.with_suffix('.bin'), a.snapshot/'complete.json']
    if a.near:
        inputs += [a.near.with_suffix('.json'), a.near.with_suffix('.bin')]
    for tree in [a.tree] + ([a.near] if a.near else []):
        leaf = tree.with_name(tree.name+'-materials.bin')
        if leaf.exists():
            inputs.append(leaf)
    (a.out/'input.json').write_text(json.dumps(dict(command=cmd, inputs={str(f): digest(f) for f in inputs}, validation=env['MTL_DEBUG_LAYER']), indent=2)+'\n')
    start = time.monotonic()
    with (a.out/'run.log').open('w') as log:
        result = subprocess.run(cmd, env=env, stdout=log, stderr=subprocess.STDOUT, timeout=960)
    (a.out/'exit.json').write_text(json.dumps(dict(code=result.returncode, wallSeconds=time.monotonic()-start))+'\n')
    print((a.out/'run.log').read_text(), end='')
    print('Output:', a.out)
    raise SystemExit(result.returncode)


if __name__ == '__main__':
    main()
