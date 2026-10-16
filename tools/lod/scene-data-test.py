#!/usr/bin/env python3
"""Generate, compile and run the retained 18-case LDS1/LDG1/LDV1 loader tests.

    python3 tools/lod/scene-data-test.py
    python3 tools/lod/scene-data-test.py --prepare-only

Each invocation creates build/lod-scene-data-test/run-*/ with fixtures, source
copies, executable and (after a test run) result.json plus run.log. The default
run acquires gpulock's exclusive timing lane, enables Metal API Validation and
uses only tiny fixtures: no full snapshot, draw, GPU command or game launch.
--prepare-only generates fixtures and compiles; it does not create a Metal device
or run tests. Fixture construction reuses audit-snapshot.py's tiny_fixture().
The native executable retains the previously tested capture-or-"-" argument;
this runner always passes "-". No full snapshot is needed for these tests.
"""
from pathlib import Path
import argparse
import copy
import gzip
import hashlib
import json
import os
import runpy
import shutil
import struct
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]


def fixtures(root):
    root.mkdir()
    ns=runpy.run_path(str(ROOT / 'tools/lod/audit-snapshot.py'))
    base,raw,off,ldv=ns['tiny_fixture']()
    base['visible']={'draws':5,'quads':5,'file':'visible.ldv.gz'}
    raw['visible.ldv.gz']=ldv
    cases=[]
    def case(name,reject='',blob=None,offset=0,fmt='',values=(),mode=None,meta=None):
     m=copy.deepcopy(base);r=dict(raw)
     if blob and fmt:
      b=bytearray(r[blob]);struct.pack_into(fmt,b,offset,*values);r[blob]=bytes(b)
     m['files']={};p=root/name;p.mkdir(exist_ok=True)
     for file,data in r.items():
      gz=gzip.compress(data,mtime=0)
      if file==blob:
       if mode=='crc':gz=gz[:-8]+bytes([gz[-8]^1])+gz[-7:]
       if mode=='truncated':gz=gz[:-4]
       if mode=='concatenated':gz+=gzip.compress(b'')
      (p/file).write_bytes(gz)
      m['files'][file]={'rawBytes':len(data),'gzipBytes':len(gz),'rawSha256':hashlib.sha256(data).hexdigest()}
     if meta:meta(m)
     (p/'complete.json').write_text(json.dumps(m)+'\n')
     cases.append({'case':name,'reject':reject})
    case('valid-mixed-original-offsets')
    case('sha-wrong','rawSha256',meta=lambda m:m['files']['sections.lds.gz'].__setitem__('rawSha256','0'*64))
    case('gzip-crc','invalid gzip/CRC',blob='sections.lds.gz',mode='crc')
    case('gzip-truncated','truncated gzip',blob='sections.lds.gz',mode='truncated')
    case('gzip-concatenated','concatenated gzip',blob='sections.lds.gz',mode='concatenated')
    case('raw-expansion-cap','exceed rawBytes',meta=lambda m:m['files']['sections.lds.gz'].__setitem__('rawBytes',16))
    case('aggregate-raw-cap','exceeds 8 GiB',meta=lambda m:m['files']['sections.lds.gz'].__setitem__('rawBytes',8<<30))
    case('section-count','magic/count',blob='sections.lds.gz',offset=4,fmt='>I',values=(0xffffffff,))
    case('section-table','LE table',blob='sections.lds.gz',offset=off['sections'][0]+48,fmt='<f',values=(2.,))
    case('arena-overlap','overlapping source',blob='sections.lds.gz',offset=off['layers'][4]+4,fmt='>i',values=(8,))
    case('group-source-range','outside matching source',blob='groups.ldg.gz',offset=off['items'][0],fmt='>I',values=(0,))
    case('group-count-cap','outside bounds',blob='groups.ldg.gz',offset=off['groups'][0]+20,fmt='>I',values=(0x7fffffff,))
    case('group-duplicate','overlapping submitted',blob='groups.ldg.gz',offset=off['items'][1],fmt='>4I',values=(32,3|(1<<29),0,0))
    case('visible-cluster','outside used count',blob='visible.ldv.gz',offset=44,fmt='<I',values=(128,))
    case('visible-local','outside original group',blob='visible.ldv.gz',offset=44,fmt='<I',values=(65,))
    case('visible-item','item source/zeroed hole',blob='visible.ldv.gz',offset=56,fmt='<I',values=(100,))
    case('visible-hole','item source/zeroed hole',blob='visible.ldv.gz',offset=48,fmt='<I',values=(1,))
    case('missing-visible','required LDV1',meta=lambda m:m.pop('visible'))
    (root/'cases.json').write_text(json.dumps(cases,indent=2)+'\n')
    print('Fixture cases:',len(cases))


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--prepare-only', action='store_true',
                        help='generate fixtures and compile, without running Metal tests')
    parser.add_argument('--locked', action='store_true', help=argparse.SUPPRESS)
    args = parser.parse_args()
    if not args.prepare_only and not args.locked:
        return subprocess.call([sys.executable, str(ROOT / 'tools/gpulock.py'),
                                'timing', '--label', 'lod-scene-data-test', '--',
                                sys.executable, str(Path(__file__).resolve()), '--locked'], cwd=ROOT)
    if args.locked and os.environ.get('MC_GPU_LOCK_HELD') != 'timing':
        parser.error('--locked requires the exclusive project GPU lock')

    base = ROOT / 'build/lod-scene-data-test'
    base.mkdir(parents=True, exist_ok=True)
    out = Path(tempfile.mkdtemp(prefix='run-', dir=base))
    print('Test artifacts:', out, flush=True)
    fixtures(out / 'fixtures')
    source = out / 'source'
    source.mkdir()
    for name in ('lod_scene_data.h', 'lod_scene_data_test.mm'):
        shutil.copyfile(ROOT / 'native/tests' / name, source / name)
    module_cache = out / 'module-cache'
    module_cache.mkdir()
    exe = out / 'loader-test'
    subprocess.run(['xcrun', '--sdk', 'macosx', 'clang++', '-std=c++17', '-fobjc-arc',
                    '-O2', '-Wall', '-Wextra', '-Werror',
                    '-fmodules-cache-path=' + str(module_cache),
                    '-framework', 'Foundation', '-framework', 'Metal', '-lz',
                    str(source / 'lod_scene_data_test.mm'), '-o', str(exe)],
                   cwd=ROOT, check=True)
    if args.prepare_only:
        print('Fixtures generated and native driver compiled; tests NOT run.', flush=True)
        return 0
    report = out / 'result.json'
    log = out / 'run.log'
    env = dict(os.environ, MTL_DEBUG_LAYER='1')
    with log.open('w') as stream:
        try:
            result = subprocess.run([str(exe), str(out / 'fixtures'), '-', str(report)],
                                    cwd=ROOT, env=env, stdout=stream,
                                    stderr=subprocess.STDOUT, timeout=130)
            code = result.returncode if result.returncode >= 0 else 128 - result.returncode
        except subprocess.TimeoutExpired:
            code = 124
    if code or not report.is_file():
        code = code or 1
        report.write_text(json.dumps({'status': 'failed', 'exitCode': code,
                                      'log': str(log)}, indent=2) + '\n')
    print('Report:', report, flush=True)
    print('Log:', log, flush=True)
    return code


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (OSError, subprocess.CalledProcessError) as error:
        print('scene-data-test:', error, file=sys.stderr)
        sys.exit(1)
