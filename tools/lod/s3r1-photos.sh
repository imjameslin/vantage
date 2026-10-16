#!/bin/zsh
# Stage 3 round 1 photos (results/STAGE3-R1.md §5): LOD off (vanilla RD 12) vs on, at the usual photo spot (anchor, yaw 200),
# a wide vista from 200 blocks up, and the vista through the zoom key at 10x (= the spyglass's FOV). FPS line only.
# usage: tools/lod/s3r1-photos.sh <client jar> <bench jar> <out dir>
ROOT="${0:A:h:h:h}"; cd "$ROOT"
J=$1; B=$2; OUT=$3
mkdir -p "$OUT"
VISTA=(--bench-prop pos=6.5,200,6.5 --bench-prop pitch=18 --bench-prop yaw=200)
Q=(--jvm=-Dmc.lod=true --jvm=-Dmc.lod.radius=128 --jvm=-Dmc.lod.tau=2 --jvm=-Dmc.lod.gpuMB=6144)   # quality setting
P=(--jvm=-Dmc.lod=true --jvm=-Dmc.lod.radius=256 --jvm=-Dmc.lod.tau=8)                           # performance setting
run() {   # label, args...
  local L=$1; shift
  zsh tools/lod/lod-run.sh photo $L $J $B "$@"
  cp run/s3-shots/$L/screenshots/photo-$L-2.png "$OUT/$L.png" 2>/dev/null
}
run s3ph-spot-off --bench-prop yaw=200
run s3ph-spot-q --bench-prop yaw=200 $Q
run s3ph-spot-p --bench-prop yaw=200 $P
run s3ph-vista-off $VISTA
run s3ph-vista-q $VISTA $Q
run s3ph-vista-p $VISTA $P
run s3ph-zoom-off $VISTA --bench-prop zoom=10 --bench-prop warmup=40
run s3ph-zoom-q $VISTA --bench-prop zoom=10 --bench-prop warmup=40 $Q
run s3ph-zoom-p $VISTA --bench-prop zoom=10 --bench-prop warmup=40 $P
echo "photos done"
