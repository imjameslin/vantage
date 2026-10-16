#!/usr/bin/env python3
"""Replay a frozen full-scene LOD snapshot; report GPU milliseconds, never game FPS."""
import argparse
import fcntl
import gzip
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time

ROOT = Path(__file__).resolve().parents[2]


def sha(path):
    h = hashlib.sha256()
    with path.open('rb') as f:
        for part in iter(lambda: f.read(1 << 20), b''):
            h.update(part)
    return h.hexdigest()


def verify(snapshot):
    manifest = json.loads((snapshot / 'complete.json').read_text())
    if manifest.get('schema') != 'farsight-scene-1' or not manifest.get('complete'):
        raise ValueError('Expected completed farsight-scene-1 snapshot')
    receipt = json.loads((snapshot / 'receipt.json').read_text())
    if sha(snapshot / 'complete.json') != receipt['manifestSha256']:
        raise ValueError('Snapshot manifest no longer matches capture receipt')
    total = 0
    for name, desc in manifest['files'].items():
        if Path(name).name != name:
            raise ValueError('Input must be a basename')
        h = hashlib.sha256()
        n = 0
        with gzip.open(snapshot / name, 'rb') as f:
            for part in iter(lambda: f.read(1 << 20), b''):
                n += len(part)
                if n > desc['rawBytes']:
                    raise ValueError('Oversized stream: ' + name)
                h.update(part)
        if n != desc['rawBytes'] or h.hexdigest() != desc['rawSha256']:
            raise ValueError('Snapshot stream changed: ' + name)
        total += n
    return {'files': len(manifest['files']), 'rawBytes': total,
            'manifestSha256': receipt['manifestSha256'], 'passed': True}


def lock_receipt():
    path = Path(os.path.realpath(os.environ.get('MC_GPU_LOCK', '/tmp/mc-gpu.lock')))
    blocked = False
    with path.open('a+') as f:
        try:
            fcntl.flock(f, fcntl.LOCK_SH | fcntl.LOCK_NB)
            fcntl.flock(f, fcntl.LOCK_UN)
        except BlockingIOError:
            blocked = True
    if os.environ.get('MC_GPU_LOCK_HELD') != 'timing' or not blocked:
        raise ValueError('Verified exclusive GPU lock required')
    return {'lane': 'timing', 'sharedProbeBlocked': blocked, 'unixTime': time.time()}


def user_game_guard():
    user_game = subprocess.run(['pgrep', '-f', '[P]rismLauncher/instances/.*/bin/java '],
                               stdout=subprocess.DEVNULL, timeout=5)
    if user_game.returncode == 0:
        raise SystemExit('User Prism game is running; replay did not launch')
    if user_game.returncode != 1:
        raise SystemExit('Cannot verify user-game guard; replay did not launch')


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('snapshot', type=Path)
    p.add_argument('shaders', type=Path)
    p.add_argument('--mode', choices=['quality', 'timing', 'both'], default='both')
    p.add_argument('--probe', choices=['replay', 'visibility', 'minvis', 'reuse'], default='replay')
    p.add_argument('--locked', action='store_true', help=argparse.SUPPRESS)
    args = p.parse_args()
    snapshot, shaders = args.snapshot.resolve(), args.shaders.resolve()
    if not args.locked:
        cmd = ['python3', str(ROOT/'tools/gpulock.py'), 'timing', '--label', 'farsight-full-scene-replay',
               '--', sys.executable, str(Path(__file__).resolve()), str(snapshot), str(shaders),
               '--mode', args.mode, '--probe', args.probe, '--locked']
        os.execv(sys.executable, [sys.executable, *cmd[1:]])
    parent = ROOT/('build/lod-scene-'+args.probe)
    parent.mkdir(parents=True, exist_ok=True)
    out = Path(tempfile.mkdtemp(prefix='run-', dir=parent))
    for d in ('source', 'shaders', 'tmp', 'module-cache'):
        (out/d).mkdir()
    print('Full-scene replay:', out, flush=True)
    sources = [ROOT/'native/tests/lod_scene_replay.mm', ROOT/'native/tests/lod_scene_data.h', Path(__file__).resolve()]
    if args.probe in ('visibility', 'reuse'):
        sources.extend(ROOT/'native/tests'/name for name in ('lod_scene_visibility.mm','lod_scene_occlusion.h','lod_scene_compact.h'))
    if args.probe == 'reuse':
        sources.append(ROOT/'native/tests/lod_scene_reuse.mm')
    if args.probe == 'minvis':
        sources.append(ROOT/'native/tests/lod_scene_minvis.mm')
    for f in sources:
        shutil.copy2(f, out/'source'/f.name)
    shader_manifest = json.loads((shaders/'manifest.json').read_text())
    if not shader_manifest.get('complete'):
        raise ValueError('Incomplete shader export')
    shutil.copy2(shaders/'manifest.json', out/'shaders/manifest.json')
    for pipeline in shader_manifest['pipelines']:
        for kind in ('vertex','fragment','descriptor'):
            name = Path(pipeline[kind+'File'])
            if name.is_absolute() or '..' in name.parts:
                raise ValueError('Shader export path escapes directory')
            if sha(shaders/name) != pipeline[kind+'Sha256']:
                raise ValueError('Shader export changed: '+str(name))
            dest=out/'shaders'/name
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(shaders/name, dest)
    start = lock_receipt()
    user_game_guard()
    checked = verify(snapshot)
    provenance = shader_manifest['provenance']
    receipt = json.loads((snapshot/'receipt.json').read_text())
    if (provenance['captureManifestSha256'] != checked['manifestSha256'] or
            provenance['clientSha256'] not in receipt['jarSha256'].values()):
        raise ValueError('Shader export is from a different captured client/scene')
    options = shader_manifest['options']
    if (options['mathMode'] != 'fast' or options['languageVersion'] != 196610 or
            options['sampleCount'] != 1 or options['alphaToCoverage']):
        raise ValueError('Unsupported shader/pipeline compilation options')
    (out/'input.json').write_text(json.dumps({'snapshot': str(snapshot), 'verification': checked,
        'probe': args.probe,
        'sourceSha256': {str(f.relative_to(out)): sha(f) for d in ('source','shaders') for f in (out/d).rglob('*') if f.is_file()},
        'lock': start}, indent=2)+'\n')
    env = dict(os.environ, TMPDIR=str(out/'tmp')+'/')
    cmd = ['xcrun','--sdk','macosx','clang++','-std=c++17','-fobjc-arc','-O2','-Wall','-Wextra','-Werror',
           '-fmodules-cache-path='+str(out/'module-cache'),'-framework','Foundation','-framework','Metal',
           '-framework','CoreGraphics','-framework','ImageIO','-lz',str(out/('source/lod_scene_'+args.probe+'.mm')),
           '-o',str(out/'replay')]
    subprocess.run(cmd, env=env, check=True, timeout=60)
    modes = ['quality','timing'] if args.mode == 'both' else [args.mode]
    for mode in modes:
        user_game_guard()
        dest=out/mode
        dest.mkdir()
        t=time.monotonic()
        env['MTL_DEBUG_LAYER']='1' if mode=='quality' else '0'
        code=1
        with (dest/'run.log').open('w') as log:
            try:
                run_args=[str(out/'replay'),str(snapshot),str(out/'shaders'),str(dest),mode]
                r=subprocess.run(run_args,
                                 env=env,stdout=log,stderr=subprocess.STDOUT,timeout=180)
                code=r.returncode
            except subprocess.TimeoutExpired:
                code=124
        result=dest/'result.json'
        data=json.loads(result.read_text()) if result.exists() else {}
        data.update(executableExitCode=code, wrapperWallSeconds=time.monotonic()-t,
                    MetalAPIValidation=mode=='quality',lockStart=start,lockEnd=lock_receipt())
        if code:
            data.update(status='failed', reason='See run.log; executable failed or timed out')
        result.write_text(json.dumps(data,indent=2,sort_keys=True)+'\n')
        print((dest/'run.log').read_text(),end='',flush=True)
        print('Result:',result,flush=True)
        if code:
            raise SystemExit(code if code>0 else 128-code)


if __name__ == '__main__':
    main()
