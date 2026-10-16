#!/usr/bin/env python3
"""Prompt7 isolated offscreen prototype; never a game FPS measurement."""
import argparse, importlib.util, json, os
from pathlib import Path
import sys, tempfile, time
ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('raster_guard',ROOT/'tools/lod/rt64-raster.py')
guard=importlib.util.module_from_spec(spec);spec.loader.exec_module(guard)
p=argparse.ArgumentParser();p.add_argument('--run',action='store_true');p.add_argument('--locked',action='store_true');p.add_argument('--frames',type=int,default=120)
a=p.parse_args()
if not a.run:
 print('Plan: 8192px six-face cache, 3024x1964 output, all captured geometry, validation off, standing warp floor first. --run to execute.');sys.exit()
guard.guard_prism()
if not a.locked:
 os.execv(sys.executable,[sys.executable,str(ROOT/'tools/gpulock.py'),'timing','--quiet','4.0:120','--label','cached-far-prompt7','--',sys.executable,'-B',str(Path(__file__).resolve()),'--run','--locked','--frames',str(a.frames)])
receipt=guard.lock_receipt();base=ROOT/'build/lod-cached-7';base.mkdir(exist_ok=True)
run=Path(tempfile.mkdtemp(prefix='run-',dir=base))
for n in ['shaders','source','tmp']:(run/n).mkdir()
provenance=guard.verify_inputs(ROOT/'build/lod-snapshots/farsight-forest-s2',ROOT/'build/lod-snapshot-shaders',run)
env=dict(os.environ,MTL_DEBUG_LAYER='0',MTL_SHADER_VALIDATION='0',TMPDIR=str(run/'tmp')+'/')
command=[str(base/'cached_far'),str(ROOT/'build/lod-snapshots/farsight-forest-s2'),str(run/'shaders'),str(ROOT/'native/tests/lod_cached_far.metal'),str(run),str(a.frames)]
provenance.update(lock=receipt,command=command,cacheFaceSize=int(env.get("CACHED_FACE_SIZE",8192)),sourceSha256={str(f):guard.sha(ROOT/f) for f in ['native/tests/lod_cached_far.mm','native/tests/lod_cached_far.metal','native/tests/lod_scene_replay.mm','native/tests/lod_scene_data.h']})
guard.write_json(run/'input.json',provenance);print(run,flush=True)
start=time.monotonic()
try:
 guard.guarded_command(command,env,run/'run.log',3600)
 result=json.loads((run/'floor.json').read_text());result.update(runDirectory=str(run),wallSeconds=time.monotonic()-start)
 guard.write_json(base/'result.json',result);print(json.dumps({k:([{kk:vv for kk,vv in row.items() if kk != 'frames'} for row in v] if k=='standingBudgetScreens' else v) for k,v in result.items()},indent=2))
except BaseException as e:
 print((run/'run.log').read_text()[-8000:],file=sys.stderr);raise
