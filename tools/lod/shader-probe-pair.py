#!/usr/bin/env python3
"""Fixed-view baseline / coverage-preserving gray probe / baseline. Not a quality benchmark."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('client')
p.add_argument('bench')
p.add_argument('--label', default='farsight-rgb-probe')
p.add_argument('--resume', action='store_true', help='Reuse completed results only after checking their jars and probe mode')
a = p.parse_args()
root = Path(__file__).resolve().parents[2]
os.chdir(root)
client_hash = hashlib.sha256(Path(a.client).read_bytes()).hexdigest()
common = ['--jvm=-Dmc.lod=true', '--jvm=-Dmc.lod.wave=false', '--jvm=-Dmc.lod.radius=512',
          '--jvm=-Dmc.lod.tau=2', '--jvm=-Dmc.lod.gpuMB=6144', '--jvm=-Dmc.lod.secOccl=true',
          '--jvm=-Dmc.lod.area=false', '--jvm=-Dmc.lod.hysteresis=0', '--jvm=-Dmc.lod.meshCache=true',
          '--jvm=-Dmc.lod.hiz=false', '--bench-prop', 'pos=6.5,130,6.5', '--bench-prop', 'pitch=8',
          '--bench-prop', 'yaw=200', '--bench-prop', 'warmup=20', '--bench-prop', 'lodWait=900',
          '--bench-prop', 'cmds=gamerule advance_time false;gamerule advance_weather false;gamerule random_tick_speed 0;gamerule max_snow_accumulation_height 0',
          '--bench-prop', 'measureCmds=time set 6000', '--opt', 'renderClouds:"false"']
rows = []
for suffix, mode in [('baseline1', 'off'), ('rgb', 'rgb'), ('baseline2', 'off')]:
    label = a.label + '-' + suffix
    result = Path('results/raw') / (label + '.json')
    if result.exists() and not a.resume:
        raise SystemExit(f'Will not overwrite {result}; choose a new --label')
    if hashlib.sha256(Path(a.client).read_bytes()).hexdigest() != client_hash:
        raise SystemExit('Frozen client jar changed')
    if not result.exists():
        subprocess.run(['zsh', 'tools/lod/lod-run.sh', 'photo', label, a.client, a.bench,
                        *common, '--jvm=-Dmcmetal.lodProbe=' + mode],
                       env=dict(os.environ, LOD_GAME_DIR='run/s3-cache-check'), check=True)
        shutil.copyfile('run/s3-cache-check/launch.log', 'results/raw/' + label + '.log')
    d = json.loads(result.read_text())
    jars = d['config']['jars']
    if (jars['client']['sha1'] != hashlib.sha1(Path(a.client).read_bytes()).hexdigest()
            or jars['bench']['sha1'] != hashlib.sha1(Path(a.bench).read_bytes()).hexdigest()
            or d['config']['properties'].get('mcmetal.lodProbe') != mode):
        raise SystemExit('Result does not match the frozen jars/probe mode: ' + label)
    st = d['lod']['state']
    gpu = st['gpu']
    display = d['launch']['display']
    if not st['idle'] or st['tau'] != 2 or gpu['budgetRefusals'] or d['lodSettleSeconds'] <= 0:
        raise SystemExit('Invalid settled geometry: ' + label)
    if not display.get('default') or display.get('pixels') != '3024x1964':
        raise SystemExit('Unexpected display: ' + repr(display))
    log = Path('results/raw/' + label + '.log').read_text(errors='replace')
    if 'Couldn\'t compile Metal pipeline' in log or 'shader variant vfog,hvary failed' in log:
        raise SystemExit('Shader failure: ' + label)
    row = dict(label=label, mode=mode, sha256=client_hash, median=d['medianFps'], average=d['avgFps'],
               low=d['p1LowFps'], selected=st['selectedPerLevel'], gpu=gpu, display=display)
    rows.append(row)
    print(json.dumps(row), flush=True)
Path('results/raw/' + a.label + '-summary.json').write_text(json.dumps(rows, indent=2) + '\n')
