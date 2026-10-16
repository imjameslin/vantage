#!/bin/zsh
# Stage 3 photos (VISTA_Y env: vista height, default 200) (results/STAGE3-R1.md): LOD off (vanilla RD 12) vs on (RD 12 + LOD), FPS line only:
#   spot  = the usual photo spot (bench anchor, yaw 200)
#   vista = 200 blocks up over the spot, pitch 18, yaw 200
#   zoom  = the vista through the zoom key at 10x (= the spyglass's FOV)
# usage: tools/lod/s3b-photos.sh <client jar> <bench jar> <out dir> [extra launch args for the LOD-on runs...]
ROOT="${0:A:h:h:h}"; cd "$ROOT"
J=$1; B=$2; OUT=$3; shift 3
mkdir -p "$OUT"
VISTA=(--bench-prop pos=6.5,${VISTA_Y:-200},6.5 --bench-prop pitch=18 --bench-prop yaw=200)
ON=(--jvm=-Dmc.lod=true "$@")
run() {   # label, args...
  local L=$1; shift
  zsh tools/lod/lod-run.sh photo $L $J $B "$@"
  cp run/s3-shots/$L/screenshots/photo-$L-2.png "$OUT/$L.png" 2>/dev/null || echo "no photo for $L"
}
run spot-off --bench-prop yaw=200
run spot-on --bench-prop yaw=200 $ON
run vista-off $VISTA
run vista-on $VISTA $ON
run zoom-off $VISTA --bench-prop zoom=10 --bench-prop warmup=40
run zoom-on $VISTA --bench-prop zoom=10 --bench-prop warmup=40 $ON
echo "photos done"
