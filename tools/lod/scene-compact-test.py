#!/usr/bin/env python3
"""Compile and run the retained stable GPU-compaction fixture tests.

    python3 tools/lod/scene-compact-test.py
    python3 tools/lod/scene-compact-test.py --prepare-only

Every invocation creates a private build/lod-scene-compact-test/run-*/ directory
with frozen source copies, SHA-256 receipt, compile log and executable. A test
run also saves run.log and result.json. Tests use synthetic fixtures only: no
full snapshot, game launch, render or display change. The normal invocation
acquires the exclusive project GPU timing lane, independently verifies its
lock, checks the Prism user-game guard and enables Metal API Validation.
--prepare-only copies sources and compiles without acquiring a GPU lock or
running Metal. Its receipt explicitly says that the tests were not run.

Bounds: compile 60 s, native test alarm 60 s, GPU completion helper 10 s,
wrapper native-process timeout 70 s, each user-game guard check 5 s. The empty
validation command buffer's original wait is covered by the native alarm.
The fixture retains the original 516 checks and adds per-entry mapped-mask tests.
"""
import argparse
import fcntl
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
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + '\n')


def lock_receipt():
    if os.environ.get('MC_GPU_LOCK_HELD') != 'timing':
        raise RuntimeError('Exclusive project GPU timing lane required')
    path = Path(os.path.realpath(os.environ.get('MC_GPU_LOCK', '/tmp/mc-gpu.lock')))
    blocked = False
    with path.open('a+') as stream:
        try:
            fcntl.flock(stream, fcntl.LOCK_SH | fcntl.LOCK_NB)
            fcntl.flock(stream, fcntl.LOCK_UN)
        except BlockingIOError:
            blocked = True
    if not blocked:
        raise RuntimeError('Independent shared-lock probe succeeded; exclusive GPU lock not verified')
    return {'lane': 'timing', 'sharedProbeBlocked': True, 'unixTime': time.time()}


def prism_guard():
    result = subprocess.run(['pgrep', '-f', '[P]rismLauncher/instances/.*/bin/java '],
                            stdout=subprocess.DEVNULL, timeout=5)
    if result.returncode == 0:
        raise RuntimeError('User Prism game is running; compaction test did not launch')
    if result.returncode != 1:
        raise RuntimeError('Cannot verify user-game guard; compaction test did not launch')
    return {'pgrepExitCode': result.returncode, 'unixTime': time.time()}


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--prepare-only', action='store_true',
                        help='copy fixtures and compile without running Metal tests')
    parser.add_argument('--locked', action='store_true', help=argparse.SUPPRESS)
    args = parser.parse_args()
    if not args.prepare_only and not args.locked:
        return subprocess.call([sys.executable, str(ROOT / 'tools/gpulock.py'),
                                'timing', '--label', 'lod-scene-compact-test', '--',
                                sys.executable, str(Path(__file__).resolve()), '--locked'], cwd=ROOT)

    base = ROOT / 'build/lod-scene-compact-test'
    base.mkdir(parents=True, exist_ok=True)
    out = Path(tempfile.mkdtemp(prefix='run-', dir=base))
    print('Test artifacts:', out, flush=True)
    source = out / 'source'
    source.mkdir()
    for name in ('lod_scene_data.h', 'lod_scene_compact.h', 'lod_scene_compact_test.mm'):
        shutil.copyfile(ROOT / 'native/tests' / name, source / name)
    shutil.copyfile(Path(__file__).resolve(), source / Path(__file__).name)
    for name in ('module-cache', 'tmp'):
        (out / name).mkdir()
    receipt = {'status': 'preparing', 'gpuTestsRun': False,
               'sourceSha256': {p.name: sha(p) for p in sorted(source.iterdir())},
               'boundsSeconds': {'compile': 60, 'nativeAlarm': 60, 'gpuWaitHelper': 10,
                                 'nativeProcess': 70, 'userGameGuard': 5}}
    write_json(out / 'receipt.json', receipt)
    exe = out / 'compact-test'
    env = dict(os.environ, TMPDIR=str(out / 'tmp') + '/', MTL_DEBUG_LAYER='1')
    code = 1
    try:
        if not args.prepare_only:
            receipt['lockStart'] = lock_receipt()
            receipt['userGameBeforeCompile'] = prism_guard()
        command = ['xcrun', '--sdk', 'macosx', 'clang++', '-std=c++17', '-fobjc-arc',
                   '-O2', '-Wall', '-Wextra', '-Werror',
                   '-fmodules-cache-path=' + str(out / 'module-cache'),
                   '-framework', 'Foundation', '-framework', 'Metal', '-lz',
                   str(source / 'lod_scene_compact_test.mm'), '-o', str(exe)]
        with (out / 'compile.log').open('w') as log:
            subprocess.run(command, cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT,
                           timeout=60, check=True)
        receipt['executableSha256'] = sha(exe)
        if args.prepare_only:
            receipt['status'] = 'prepared-tests-not-run'
            code = 0
            print('Fixture compiled; GPU tests NOT run.', flush=True)
            return code

        # Recheck after compilation, immediately before creating the Metal device.
        receipt['lockBeforeRun'] = lock_receipt()
        receipt['userGameBeforeRun'] = prism_guard()
        receipt['gpuTestsRun'] = True
        write_json(out / 'receipt.json', receipt)
        started = time.monotonic()
        with (out / 'run.log').open('w') as log:
            result = subprocess.run([str(exe), str(out / 'result.json')], cwd=ROOT, env=env,
                                    stdout=log, stderr=subprocess.STDOUT, timeout=70)
        receipt['nativeWallSeconds'] = time.monotonic() - started
        code = result.returncode if result.returncode >= 0 else 128 - result.returncode
        receipt['lockEnd'] = lock_receipt()
        if code:
            raise RuntimeError('Native test failed; see run.log')
        report = json.loads((out / 'result.json').read_text())
        if report.get('status') != 'passed' or report.get('MetalAPIValidation') is not True:
            raise RuntimeError('Native report did not confirm success with Metal API Validation')
        receipt['status'] = 'passed'
        print('Report:', out / 'result.json', flush=True)
        print('Log:', out / 'run.log', flush=True)
        return code
    except (OSError, RuntimeError, ValueError, subprocess.SubprocessError) as error:
        if isinstance(error, subprocess.TimeoutExpired):
            code = 124
        elif isinstance(error, subprocess.CalledProcessError):
            code = error.returncode if error.returncode >= 0 else 128 - error.returncode
        else:
            code = code or 1
        receipt.update(status='failed', error=str(error))
        # Keep any native report intact; receipt records wrapper failures separately.
        if not (out / 'result.json').exists():
            write_json(out / 'result.json', {'status': 'failed', 'exitCode': code,
                                           'reason': str(error)})
        print('scene-compact-test:', error, file=sys.stderr)
        print('Failure artifacts:', out, file=sys.stderr)
        return code
    finally:
        receipt['exitCode'] = code
        write_json(out / 'receipt.json', receipt)


if __name__ == '__main__':
    sys.exit(main())
