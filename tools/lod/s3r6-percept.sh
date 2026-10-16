#!/bin/zsh
# Stage 3 r6 perceptual check (as tools/lod/percept.sh, results/STAGE3-R5.md R5.4): real terrain at render distance 32 (ref) vs
# render distance 12 + wave far field (tau 2) out to 32 chunks, LOD fog off, clouds off; views vista, spot (round 5's reference
# shots, reused) and taiga (y 130, pitch 8: the snowy taiga middle distance). Crops as percept.sh; tools/PerceptualDiff.java.
# usage: tools/lod/s3r6-percept.sh <client jar> <bench jar> <out dir> <ref dir> [extra launch args...]
#   <ref dir> holds vista.png / spot.png / taiga.png references (missing ones are shot with <client jar>, LOD off, RD 32)
ROOT="${0:A:h:h:h}"; cd "$ROOT"
J=$1; B=$2; OUT=$3; REF=$4; shift 4
mkdir -p "$OUT/cand" "$OUT/full" "$REF"
NOCLOUD=(--opt 'renderClouds:"false"')
view() {
  case $1 in
    vista) print -- --bench-prop pos=6.5,180,6.5 --bench-prop pitch=18 --bench-prop yaw=200;;
    spot) print -- --bench-prop yaw=200;;
    taiga) print -- --bench-prop pos=6.5,130,6.5 --bench-prop pitch=8 --bench-prop yaw=200;;
  esac
}
shoot() {   # label, dest png, args...
  local L=$1 D=$2; shift 2
  zsh tools/lod/lod-run.sh photo $L $J $B $NOCLOUD "$@"
  local src=run/s3-shots/$L/screenshots/photo-$L-2.png
  [[ -f $src ]] || { echo "no photo for $L"; return; }
  cp $src "$OUT/full/$L.png"
  sips -c 1300 2200 --cropOffset 60 0 $src --out "$D" > /dev/null
}
for v in vista spot taiga; do
  [[ -f "$REF/$v.png" ]] || shoot r6pc-ref-$v "$REF/$v.png" ${=$(view $v)} --opt renderDistance:32 --bench-prop warmup=40
  shoot r6pc-${OUT:t}-$v "$OUT/cand/$v.png" ${=$(view $v)} --jvm=-Dmc.lod=true --jvm=-Dmc.lod.wave=true --jvm=-Dmc.lod.radius=32 \
    --jvm=-Dmc.lod.tau=2 --jvm=-Dmc.lod.fog=false --bench-prop warmup=25 "$@"
done
JH=mc/runtime/java-runtime-epsilon/jre.bundle/Contents/Home
$JH/bin/java tools/PerceptualDiff.java "$REF" "$OUT/cand" | tee "$OUT/percept.txt"
