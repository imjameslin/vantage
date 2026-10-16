#!/bin/zsh
# Far-terrain quality gate (origin: results/STAGE3-R6.md): candidate vs synchronous raster LOD at Voxy's detail (tau 2), both at
# 512 chunks, same fog (default LOD fog), clouds off; views vista (y 180, pitch 18), far (y 180, pitch 6: centre ~1000 blocks),
# spot (y 106), taiga (y 130, pitch 8), all yaw 200. Gate: SSIM >= 0.99 and mean dE < 1.0 per view (tools/PerceptualDiff.java).
# usage: tools/lod/s3r6-gate.sh <client jar> <bench jar> <out dir> <ref dir> [extra launch args for the candidate...]
#   <ref dir>: vista/far/spot/taiga.png of the raster tau 2 reference; missing ones are shot with <client jar> (mc.lod.wave off)
#   GATE_REFERENCE_ONLY=1 captures missing synchronous references only; it never reports a gate pass.
#   GATE_LABEL_PREFIX isolates raw run names from older gate receipts (default r6g).
#   GATE_WORLD_SNAPSHOT optionally restores the same pinned save before every reference and candidate.
set -euo pipefail
ROOT="${0:A:h:h:h}"; cd "$ROOT"
J=$1; B=$2; OUT=$3; REF=$4; shift 4
mkdir -p "$OUT/cand" "$OUT/full" "$REF/full"
NOCLOUD=(--opt 'renderClouds:"false"' --bench-prop 'measureCmds=time set 6000' --bench-prop spin=Infinity)
PIN=()
if [[ -n ${GATE_WORLD_SNAPSHOT:-} ]]; then PIN=(--world-snapshot "$GATE_WORLD_SNAPSHOT" --world-only); fi
view() {
  case $1 in
    vista) print -- --bench-prop pos=6.5,180,6.5 --bench-prop pitch=18 --bench-prop yaw=200;;
    far) print -- --bench-prop pos=6.5,180,6.5 --bench-prop pitch=6 --bench-prop yaw=200;;
    spot) print -- --bench-prop pos=6.5,106,6.5 --bench-prop yaw=200;;
    taiga) print -- --bench-prop pos=6.5,130,6.5 --bench-prop pitch=8 --bench-prop yaw=200;;
  esac
}
shoot() {   # label, dest png, full dir, args...
  local L=$1 D=$2 F=$3; shift 3
  LOD_VISUAL_ONLY=1 zsh tools/lod/lod-run.sh photo $L $J $B $NOCLOUD $PIN "$@"
  python3 - "results/raw/$L.json" <<'PY'
import json, sys
d = json.load(open(sys.argv[1]))
s = (d.get('lod') or {}).get('state') or {}
display = (d.get('launch') or {}).get('display') or {}
assert display.get('pixels') == '3024x1964' and display.get('default') is True, 'wrong display'
assert display.get('hz') == 120 and d.get('framebuffer') == '3024x1964', 'wrong framebuffer/refresh'
assert d.get('lodSettleSeconds', 0) > 0 and s.get('idle') is True, 'LOD not settled'
assert s.get('tau') == float(__import__('os').environ.get('GATE_TAU', '2')) and s.get('selected', 0) > 0, 'actual detail is not the expected tau'
reuse = (s.get('gpu') or {}).get('reuse') or {}
if reuse.get('enabled'):
    # Exact reuse reports attempts/hits/refreshes; older jars also report nested
    # experimental failure counters. Absence of that retired field is normal.
    assert reuse.get('failed', False) is False and reuse.get('hits', 0) > 0, 'exact reuse failed or never reused an image'

PY
  local src=run/s3-shots/$L/screenshots/photo-$L-2.png
  [[ -f $src ]] || { echo "no photo for $L" >&2; return 1; }
  cp $src "$F/$L.png"
  sips -c 1300 2200 --cropOffset 60 0 $src --out "$D" > /dev/null
}
LOD=(--jvm=-Dmc.lod=true --jvm=-Dmc.lod.radius=${GATE_RADIUS:-512} --jvm=-Dmc.lod.tau=2 --jvm=-Dmc.lod.gpuMB=6144 --jvm=-Dmc.lod.reuse=false --jvm=-Dmc.lod.layers=false --jvm=-Dmc.lod.wave=false --jvm=-Dmc.lod.hiz=false --jvm=-Dmc.lod.solidQuery=false
     --jvm=-Dmc.lod.area=false --jvm=-Dmc.lod.secOccl=true --jvm=-Dmc.lod.layerMask=7 --jvm=-Dmc.lod.tauLevels= --jvm=-Dmc.lod.flat0Px=0 --jvm=-Dmc.lod.hysteresis=0
     --jvm=-Dmc.lod.meshCache=true --jvm=-Dmc.lod.palette=false --jvm=-Dmc.lod.directQuads=false --env MTL_DEBUG_LAYER=0 --env MTL_SHADER_VALIDATION=0)
for v in ${=GATE_VIEWS:-vista far spot taiga}; do
  [[ -f "$REF/$v.png" ]] || shoot ${GATE_LABEL_PREFIX:-r6g}-ref-$v "$REF/$v.png" "$REF/full" ${=$(view $v)} $LOD ${=GATE_REF_EXTRA:-} --bench-prop warmup=60 --bench-prop lodWait=900
  if [[ ${GATE_REFERENCE_ONLY:-0} == 1 ]]; then continue; fi
  shoot ${GATE_LABEL_PREFIX:-r6g}-${OUT:t}-$v "$OUT/cand/$v.png" "$OUT/full" ${=$(view $v)} $LOD --bench-prop warmup=40 --bench-prop lodWait=900 "$@"
done
if [[ ${GATE_REFERENCE_ONLY:-0} == 1 ]]; then
  print -- "Reference capture only: no candidate compared, no quality gate evaluated."
  exit 0
fi
JH=mc/runtime/java-runtime-epsilon/jre.bundle/Contents/Home
$JH/bin/java tools/PerceptualDiff.java "$REF" "$OUT/cand" | tee "$OUT/percept.txt"
