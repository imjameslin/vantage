#!/usr/bin/env python3
"""Prompt6 Step1 original far-raster baseline. Plan only unless --run is supplied.

Main runs this AFTER data/kernel workers are idle:
  python3 -B tools/lod/rt64-raster.py --run

Standing only, captured camera, 3024x1964, all LDV layers, original shaders.
Five warmups then 120 individual GPU spans; no near depth, motion, or FPS claim.
Compare with standing UNMASKED 64-tree raw hits, not equivalent shading/geometry.
"""
import argparse
import fcntl
import gzip
import hashlib
import json
import os
from pathlib import Path
import shutil
import signal
import struct
import subprocess
import sys
import tempfile
import time

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'build/lod-rt64/raster'
GEOMETRY = ('sections.lds.gz', 'groups.ldg.gz', 'visible.ldv.gz')
CAMERA = ('projection.bin.gz', 'globals.bin.gz', 'terrain.bin.gz')


def sha(path):
    h = hashlib.sha256()
    with path.open('rb') as f:
        for chunk in iter(lambda: f.read(1 << 20), b''):
            h.update(chunk)
    return h.hexdigest()


def write_json(path, data):
    temp = path.with_suffix('.tmp')
    temp.write_text(json.dumps(data, indent=2, allow_nan=False) + '\n')
    temp.replace(path)


def guard_prism():
    # Covers JVMs using a bundled or external Java, including --gameDir paths.
    procs = subprocess.run(['ps', '-axww', '-o', 'comm=,args='], check=True,
                           capture_output=True, text=True, timeout=5).stdout
    for line in procs.splitlines():
        low = line.lower()
        if 'prismlauncher' in low and ('/java ' in low or '/java\t' in low or ' java ' in low):
            raise RuntimeError('User Prism game is running; raster run aborted')


def lock_receipt():
    path = Path(os.environ.get('MC_GPU_LOCK', '/tmp/mc-gpu.lock')).resolve()
    if os.environ.get('MC_GPU_LOCK_HELD') != 'timing':
        raise RuntimeError('Exclusive timing lock required')
    blocked = False
    with path.open('rb') as f:
        try:
            fcntl.flock(f, fcntl.LOCK_SH | fcntl.LOCK_NB)
            fcntl.flock(f, fcntl.LOCK_UN)
        except BlockingIOError:
            blocked = True
    if not blocked:
        raise RuntimeError('Exclusive GPU lock not verified by shared probe')
    return dict(path=str(path), lane='timing', sharedProbeBlocked=True, unixTime=time.time())


def guarded_command(command, env, log, timeout):
    guard_prism()
    with log.open('w') as output:
        child = subprocess.Popen(command, cwd=ROOT, env=env, stdout=output, stderr=subprocess.STDOUT,
                                 start_new_session=True)
        deadline = time.monotonic() + timeout
        try:
            while child.poll() is None:
                guard_prism()
                if time.monotonic() >= deadline:
                    raise TimeoutError('Command exceeded bounded timeout: ' + str(log))
                try:
                    child.wait(timeout=1)
                except subprocess.TimeoutExpired:
                    pass
            guard_prism()
            if child.returncode:
                raise RuntimeError(f'Command exited {child.returncode}: {log}')
        finally:
            if child.poll() is None:
                os.killpg(child.pid, signal.SIGTERM)
                try:
                    child.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    os.killpg(child.pid, signal.SIGKILL)
                    child.wait()


def verify_inputs(snapshot, shaders, run):
    manifest = json.loads((snapshot/'complete.json').read_text())
    receipt = json.loads((snapshot/'receipt.json').read_text())
    capture_hash = sha(snapshot/'complete.json')
    if (manifest.get('schema') != 'farsight-scene-1' or manifest.get('complete') is not True or
            capture_hash != receipt['manifestSha256']):
        raise ValueError('Snapshot incomplete or manifest does not match receipt')
    sm = json.loads((shaders/'manifest.json').read_text())
    if (sm.get('schema') != 'farsight-snapshot-shaders-1' or sm.get('complete') is not True or
            sm['provenance']['captureManifestSha256'] != capture_hash or
            sm['provenance']['clientSha256'] not in receipt['jarSha256'].values()):
        raise ValueError('Shader export does not match captured scene/client')
    options = sm['options']
    if (options['mathMode'] != 'fast' or options['languageVersion'] != 196610 or
            options['sampleCount'] != 1 or options['alphaToCoverage']):
        raise ValueError('Unsupported original pipeline options')
    shutil.copy2(shaders/'manifest.json', run/'shaders/manifest.json')
    for pipeline in sm['pipelines']:
        for kind in ('vertex', 'fragment', 'descriptor'):
            name = Path(pipeline[kind+'File'])
            if name.is_absolute() or '..' in name.parts or not name.parts:
                raise ValueError('Shader path escapes export')
            dest = run/'shaders'/name
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(shaders/name, dest)
            if sha(dest) != pipeline[kind+'Sha256']:
                raise ValueError('Original shader/descriptor hash mismatch: ' + str(name))
    # Native bounded loader validates geometry hashes while loading. Verify only
    # inputs outside that loader here; do not decompress LDS twice. Frame-depth
    # and frame-color are intentionally unused, not substituted for near depth.
    names = set(CAMERA) | {'fog.bin.gz', 'cull-view.bin.gz'}
    for texture in ('atlas', 'lightmap'):
        names.update(m['file'] for m in manifest[texture]['mips'])
    for name in sorted(names):
        if Path(name).name != name:
            raise ValueError('Snapshot input must be a basename')
        desc = manifest['files'][name]
        if not 0 < desc['rawBytes'] <= 64 << 20:
            raise ValueError('Small input size cap exceeded: ' + name)
        digest, count = hashlib.sha256(), 0
        with gzip.open(snapshot/name, 'rb') as f:
            for chunk in iter(lambda: f.read(1 << 20), b''):
                count += len(chunk)
                if count > desc['rawBytes']:
                    raise ValueError('Oversized snapshot input: ' + name)
                digest.update(chunk)
        if count != desc['rawBytes'] or digest.hexdigest() != desc['rawSha256']:
            raise ValueError('Snapshot input hash mismatch: ' + name)
    raw = {name: gzip.decompress((snapshot/name).read_bytes()) for name in CAMERA}
    projection = struct.unpack('<16f', raw[CAMERA[0]])
    globals_f = struct.unpack('<12f', raw[CAMERA[1]])
    terrain = struct.unpack('<20f', raw[CAMERA[2]])
    origin = struct.unpack('<3i', raw[CAMERA[1]][:12])
    if globals_f[8:10] != (3024., 1964.):
        raise ValueError('Expected native captured 3024x1964 viewport')
    view = dict(captureManifestSha256=capture_hash, frame=manifest['frame'],
                uniformRawSha256={n: manifest['files'][n]['rawSha256'] for n in CAMERA},
                width=3024, height=1964, frozen=True,
                eye=[origin[i]-globals_f[4+i] for i in range(3)],
                forward=[-terrain[i] for i in (2, 6, 10)],
                right=[terrain[i] for i in (0, 4, 8)], up=[terrain[i] for i in (1, 5, 9)],
                projectionColumnMajor=list(projection), terrainViewColumnMajor=list(terrain[:16]),
                note='Eye/basis derived from captured uniforms, not bench player position; native raster binds those bytes unchanged.')
    for name in ('complete.json', 'receipt.json'):
        shutil.copy2(snapshot/name, run/'source'/('snapshot-'+name))
    return dict(snapshot=str(snapshot), shaders=str(shaders), capturedView=view,
                shaderManifestSha256=sha(run/'shaders/manifest.json'), shaderOptions=options,
                inputRawSha256={n: manifest['files'][n]['rawSha256'] for n in sorted(names | set(GEOMETRY))},
                geometryVerification='LDS/LDG/LDV hashes, sizes and all references checked by native loader before sampling',
                visible=manifest['visible'], smallInputsVerified=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--snapshot', type=Path, default=ROOT/'build/lod-snapshots/farsight-forest-s2')
    parser.add_argument('--shaders', type=Path, default=ROOT/'build/lod-snapshot-shaders')
    parser.add_argument('--run', action='store_true', help='build and measure only after other workers are idle')
    parser.add_argument('--locked', action='store_true', help=argparse.SUPPRESS)
    args = parser.parse_args()
    snapshot, shaders = args.snapshot.resolve(), args.shaders.resolve()
    plan = dict(snapshot=str(snapshot), shaders=str(shaders), result=str(OUT/'result.json'),
                path='standing-only', width=3024, height=1964, warmups=5, samples=120,
                passesPerSample=1, layerMask=7, nearDepth=False, validation=0,
                gpuInterval='GPUStartTime..GPUEndTime', lock='exclusive timing', prismGuard=True,
                comparison='Same captured view: shaded far raster vs standing unmasked 64-tree raw-hit traversal; not equivalent shading/geometry.')
    if not args.run:
        print(json.dumps(dict(status='plan-only; no build or GPU execution', **plan), indent=2))
        return
    guard_prism()
    if not args.locked:
        command = [sys.executable, str(ROOT/'tools/gpulock.py'), 'timing', '--quiet', '4.0:900', '--label', 'rt64-standing-raster',
                   '--', sys.executable, '-B', str(Path(__file__).resolve()), '--run', '--locked',
                   '--snapshot', str(snapshot), '--shaders', str(shaders)]
        os.execv(sys.executable, command)
    start_lock = lock_receipt()
    def interrupted(signum, _frame):
        raise RuntimeError(f'Raster wrapper interrupted by signal {signum}')
    for signum in (signal.SIGTERM, signal.SIGHUP):
        signal.signal(signum, interrupted)
    OUT.mkdir(parents=True, exist_ok=True)
    run = Path(tempfile.mkdtemp(prefix='run-', dir=OUT))
    for name in ('source', 'shaders', 'tmp', 'module-cache'):
        (run/name).mkdir()
    result = dict(schema='farsight-rt64-raster-1', status='running', runDirectory=str(run), plan=plan)
    write_json(OUT/'result.json', result)
    started = time.monotonic()
    try:
        sources = ['native/tests/lod_rt64_raster.mm', 'native/tests/lod_scene_replay.mm',
                   'native/tests/lod_scene_data.h', 'tools/lod/rt64-raster.py', 'tools/gpulock.py']
        for name in sources:
            shutil.copy2(ROOT/name, run/'source'/Path(name).name)
        provenance = verify_inputs(snapshot, shaders, run)
        env = dict(os.environ, TMPDIR=str(run/'tmp')+'/', MTL_DEBUG_LAYER='0', MTL_SHADER_VALIDATION='0')
        command = ['xcrun', '--sdk', 'macosx', 'clang++', '-std=c++17', '-fobjc-arc', '-O2',
                   '-Wall', '-Wextra', '-Werror', '-fmodules-cache-path='+str(run/'module-cache'),
                   '-framework', 'Foundation', '-framework', 'Metal', '-framework', 'CoreGraphics',
                   '-framework', 'ImageIO', '-lz', str(run/'source/lod_rt64_raster.mm'), '-o', str(run/'raster')]
        provenance.update(sourceSha256={n: sha(run/'source'/Path(n).name) for n in sources},
                          command=command, invocation=sys.argv, lockStart=start_lock,
                          validationEnvironment={k: env[k] for k in ('MTL_DEBUG_LAYER', 'MTL_SHADER_VALIDATION')})
        write_json(run/'input.json', provenance)
        print('Raster artifacts:', run, flush=True)
        guarded_command(command, env, run/'build.log', 120)
        provenance['executableSha256'] = sha(run/'raster')
        provenance['runCommand'] = [str(run/'raster'), str(snapshot), str(run/'shaders'), str(run)]
        write_json(run/'input.json', provenance)
        guarded_command(provenance['runCommand'], env, run/'run.log', 900)
        native = json.loads((run/'native-result.json').read_text())
        if native.get('status') != 'passed' or native['gpu']['sampleCount'] != 120:
            raise RuntimeError('Native result incomplete')
        gpu = native['gpu']
        native['gpu'] = {k: gpu[k] for k in ('p99Ms', 'maxMs', 'medianMs', 'sampleCount', 'percentileMethod', 'samples')}
        result.update(native, provenance=provenance, lockEnd=lock_receipt(),
                      wrapperWallSeconds=time.monotonic()-started, preview=str(run/'standing.png'))
        write_json(run/'result.json', result)
        write_json(OUT/'result.json', result)
        print((run/'run.log').read_text(), end='')
        print('Result:', OUT/'result.json')
    except BaseException as error:
        result.update(status='failed', reason=str(error), wrapperWallSeconds=time.monotonic()-started)
        write_json(run/'result.json', result)
        write_json(OUT/'result.json', result)
        raise


if __name__ == '__main__':
    main()
