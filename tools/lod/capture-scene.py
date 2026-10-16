#!/usr/bin/env python3
"""Capture settled Farsight source geometry/materials/draw lists; never an FPS benchmark."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('client', type=Path)
p.add_argument('bench', type=Path)
p.add_argument('--label', required=True)
p.add_argument('--game-dir', default='run/s3-palette-check', type=Path)
p.add_argument('--pos', default='6.5,130,6.5')
p.add_argument('--yaw', default='200')
p.add_argument('--pitch', default='8')
a = p.parse_args()
root = Path(__file__).resolve().parents[2]
os.chdir(root)
if not a.label or any(c not in 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_' for c in a.label):
    p.error('label must be alphanumeric with hyphens/underscores')
a.client, a.bench, a.game_dir = a.client.resolve(), a.bench.resolve(), a.game_dir.resolve()
for f in (a.client, a.bench):
    if not f.is_file(): p.error('Missing frozen jar: ' + str(f))
hashes = {str(f): hashlib.sha256(f.read_bytes()).hexdigest() for f in (a.client, a.bench)}
out = root / 'build/lod-snapshots' / a.label
result = root / 'results/raw' / (a.label + '.json')
log = root / 'results/raw' / (a.label + '.out')
for f in (out, result, log):
    if f.exists(): p.error('Will not overwrite ' + str(f))
out.parent.mkdir(parents=True, exist_ok=True)
opts = []
for line in (root / 'tools/static-options.txt').read_text().splitlines():
    if line.strip(): opts += ['--opt', line]
cmd = ['python3', 'tools/launch.py', '--jar', str(a.client), '--bench-jar', str(a.bench),
       '--game-dir', str(a.game_dir), '--api', 'metal', '--lane', 'timing', '--opt', 'fullscreen:true',
       *opts, '--opt', 'renderClouds:"false"', '--bench', str(result), '--label', a.label,
       '--quiet-gate', '4.0:900', '--overlay-check']
for key, value in dict(lod='true').items():
    cmd += ['--jvm=-Dmc.'+key+'='+value]
for key, value in {'wave':'false','radius':'512','tau':'2','gpuMB':'6144','secOccl':'true','area':'false',
                   'hysteresis':'0','meshCache':'true','hiz':'false','palette':'false','directQuads':'false'}.items():
    cmd += ['--jvm=-Dmc.lod.'+key+'='+value]
for key, value in {'mode':'lodsnapshot','snapshot.dir':str(out),'pos':a.pos,'yaw':a.yaw,'pitch':a.pitch,
                   'spin':'1000000','warmup':'5','lodWait':'900','timeout':'1200','photo':'0','f3':'off',
                   'cmds':'gamerule advance_time false;gamerule advance_weather false;gamerule random_tick_speed 0;gamerule max_snow_accumulation_height 0;time set 6000'}.items():
    cmd += ['--bench-prop',key+'='+value]
print('Capture only:', out, flush=True)
with log.open('x') as f: subprocess.run(cmd, stdout=f, stderr=subprocess.STDOUT, check=True)
for f in (a.client,a.bench):
    if hashlib.sha256(f.read_bytes()).hexdigest() != hashes[str(f)]: raise SystemExit('Frozen jar changed')
d = json.loads(result.read_text())
if d.get('error') != 'lodsnapshot complete': raise SystemExit('Capture failed: '+str(d.get('error')))
display = d['launch']['display']
if not display.get('default') or display.get('pixels') != '3024x1964' or abs(display.get('hz',0)-120)>0.1:
    raise SystemExit('Capture retained but display is not the target: '+str(display))
manifest = json.loads((out / 'complete.json').read_text())
if not manifest.get('complete'): raise SystemExit('Incomplete snapshot')
receipt = {'command':cmd, 'jarSha256':hashes, 'display':display, 'result':str(result.relative_to(root)),
           'manifestSha256':hashlib.sha256((out/'complete.json').read_bytes()).hexdigest()}
(out/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n')
print(json.dumps({'geometry':manifest['geometry'],'visible':manifest.get('visible'), 'directory':str(out)},indent=2))
