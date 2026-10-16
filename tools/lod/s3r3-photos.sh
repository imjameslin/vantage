#!/bin/zsh
# Stage 3 r3 photos (results/STAGE3-R3.md, share/lod3): FPS line only, Default display mode, fixture world run/s3-lod
# (real chunks generated out to 256 chunks; beyond that the LOD comes from the seed generator).
#   vista-off / vista-default / vista-high   y 180, pitch 18, yaw 200: no LOD / LOD 256 tau 16 / LOD 256 tau 8
#   zoom-default                              the vista through the zoom key at 10x (LOD 256, tau 16)
#   far-512 / far-1000                        y 180, pitch 6, yaw 200: LOD 512 / 1000 chunks (seed-generated beyond 256)
#   far-512-genonly                           LOD 512, generated terrain everywhere except the loaded chunks
# usage: tools/lod/s3r3-photos.sh <client jar> <bench jar> <out dir> [extra launch args for every LOD run...]
ROOT="${0:A:h:h:h}"; cd "$ROOT"
J=$1; B=$2; OUT=$3; shift 3
mkdir -p "$OUT"
VISTA=(--bench-prop pos=6.5,180,6.5 --bench-prop pitch=18 --bench-prop yaw=200)
FAR=(--bench-prop pos=6.5,180,6.5 --bench-prop pitch=6 --bench-prop yaw=200)
run() {   # label, args...
  local L=$1; shift
  zsh tools/lod/lod-run.sh photo ${PHOTO_PREFIX:-r3p}-$L $J $B "$@"
  cp run/s3-shots/${PHOTO_PREFIX:-r3p}-$L/screenshots/photo-${PHOTO_PREFIX:-r3p}-$L-2.png "$OUT/$L.png" 2>/dev/null || echo "no photo for $L"
}
run vista-off $VISTA
run vista-default $VISTA --jvm=-Dmc.lod=true --jvm=-Dmc.lod.radius=256 "$@"
run vista-high $VISTA --jvm=-Dmc.lod=true --jvm=-Dmc.lod.radius=256 --jvm=-Dmc.lod.tau=8 "$@"
run zoom-default $VISTA --bench-prop zoom=10 --bench-prop warmup=40 --jvm=-Dmc.lod=true --jvm=-Dmc.lod.radius=256 "$@"
run far-512 $FAR --bench-prop warmup=30 --jvm=-Dmc.lod=true --jvm=-Dmc.lod.radius=512 "$@"
run far-1000 $FAR --bench-prop warmup=40 --jvm=-Dmc.lod=true --jvm=-Dmc.lod.radius=1000 --jvm=-Dmc.lod.maxLevel=7 "$@"
run far-512-genonly $FAR --bench-prop warmup=30 --jvm=-Dmc.lod=true --jvm=-Dmc.lod.radius=512 --jvm=-Dmc.lod.genOnly=true "$@"
echo "photos done"
