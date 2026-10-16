#!/bin/zsh
# Stage 3 r3 measurement queue (results/STAGE3-R3.md): generated vs real look, travel with LOD (fade-in skips, generation in a
# fresh area), real worldgen speed, zoom temporal check, A/B at LOD 128, photos for share/lod3, disk-cap check at 1000 chunks.
# usage: tools/lod/s3r3-queue.sh <client jar> <bench jar> <photo out dir>
ROOT="${0:A:h:h:h}"; cd "$ROOT"
J=$1; B=$2; P=$3
S=(); while IFS= read -r l; do S+=(--opt "$l"); done < tools/static-options.txt
echo "== gen-compare R256 tau16"
zsh tools/lod/gen-compare.sh $J $B results/raw/gc3 256 16 | tail -3
python3 -c "import json; d=json.load(open('results/raw/gc-cand-vista-r256.json')); print('gen stats', json.dumps(d['lod']['state'].get('gen')), 'settle', d.get('lodSettleSeconds'))"
echo "== travel with LOD 256 (pre-generated travel world)"
zsh tools/bench-travel.sh r3q-travel-lod run/r3q --pregen --jar $J --bench-jar $B --api metal --jvm=-Dmc.lod=true --jvm=-Dmc.lod.radius=256 --bench-prop timeout=1200
python3 -c "import json; d=json.load(open('results/raw/r3q-travel-lod.json')); s=d['lod']['state']; print('travel lod', {k: s.get(k) for k in ['sectionUploads','fadeSkips','captured','spRead']}, json.dumps(s.get('gen')))"
echo "== real worldgen speed (fresh world, integrated server, radius 32)"
python3 tools/launch.py --jar $J --bench-jar $B --game-dir run/r3q-pregen --fresh-world pg --api metal $S --bench results/raw/r3q-pregen.json \
  --label r3q-pregen --bench-prop mode=lodpregen --bench-prop pregen.radius=32 --bench-prop timeout=1800 > results/raw/r3q-pregen.out 2>&1
grep "pregen" results/raw/r3q-pregen.out | tail -3; grep -h "pregen" run/r3q-pregen/logs/latest.log | tail -3
echo "== zoomseq LOD 256"
LOD_GAME_DIR=run/s3-lod-scenes zsh tools/lod/lod-run.sh zoomseq r3q-zs $J $B --jvm=-Dmc.lod=true --jvm=-Dmc.lod.radius=256
zsh tools/lod/zoomseq-pops.sh run/s3-shots/r3q-zs/screenshots/zoomseq results/raw/r3q-zspops | tail -70
echo "== abab LOD 128 default / tau 8"
zsh tools/lod/lod-run.sh abab r3q-r128-t16 $J $B --jvm=-Dmc.lod.radius=128 | sed -n 5,8p
zsh tools/lod/lod-run.sh abab r3q-r128-t8 $J $B --jvm=-Dmc.lod.radius=128 --jvm=-Dmc.lod.tau=8 | sed -n 5,8p
echo "== photos"
VISTA_Y=180 zsh tools/lod/s3r3-photos.sh $J $B $P
for f in r3p-vista-default r3p-vista-high r3p-far-512 r3p-far-1000 r3p-far-512-genonly; do
  python3 -c "import json,sys; d=json.load(open('results/raw/'+sys.argv[1]+'.json')); s=d['lod']['state']; print(sys.argv[1], 'fps', round(d.get('medianFps',0)), 'settle', d.get('lodSettleSeconds'), 'sel', s['selectedPerLevel'], 'gpuMB', s['gpu']['gpuMB'], 'mem', d.get('memory'), 'gen', json.dumps(s.get('gen')))" $f
done
echo "== disk cap: LOD 1000, generated store capped at 32 MB"
zsh tools/lod/lod-run.sh perf r3q-cap1000 $J $B --jvm=-Dmc.lod=true --jvm=-Dmc.lod.radius=1000 --jvm=-Dmc.lod.maxLevel=7 --jvm=-Dmc.lod.genDiskMB=32 --bench-prop pos=6.5,180,6.5 --bench-prop pitch=6
python3 -c "import json; d=json.load(open('results/raw/r3q-cap1000.json')); s=d['lod']['state']; print('cap run', json.dumps(s.get('gen')), s['selectedPerLevel'])"
echo "queue done"
