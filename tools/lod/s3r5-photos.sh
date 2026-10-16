#!/bin/zsh
# Stage 3 r5 final photos (share/lod5): the wave far field (mc.lod.wave, Voxy's detail tau 2, 512 chunks), FPS line only.
#   vista-wave / far-wave / spot-wave / zoom-wave   the usual views (as s3r3-photos.sh), wave far field everywhere (default)
#   horizon-wave                                    y 120, pitch 2, yaw 200: a low view toward the horizon like the Voxy reference
#   horizon-t16                                     the same view with the raster LOD default (tau 16) for comparison
# usage: tools/lod/s3r5-photos.sh <client jar> <bench jar> <out dir>
ROOT="${0:A:h:h:h}"; cd "$ROOT"
J=$1; B=$2; OUT=$3
mkdir -p "$OUT"
VISTA=(--bench-prop pos=6.5,180,6.5 --bench-prop pitch=18 --bench-prop yaw=200)
FAR=(--bench-prop pos=6.5,180,6.5 --bench-prop pitch=6 --bench-prop yaw=200)
SPOT=(--bench-prop pos=6.5,106,6.5 --bench-prop yaw=200)
HOR=(--bench-prop pos=6.5,120,6.5 --bench-prop pitch=2 --bench-prop yaw=200)
WAVE=(--jvm=-Dmc.lod=true --jvm=-Dmc.lod.wave=true --jvm=-Dmc.lod.radius=512 --jvm=-Dmc.lod.tau=2 --bench-prop warmup=30)
run() {
  local L=$1; shift
  zsh tools/lod/lod-run.sh photo r5z-$L $J $B "$@"
  cp run/s3-shots/r5z-$L/screenshots/photo-r5z-$L-2.png "$OUT/$L.png" 2>/dev/null || echo "no photo for $L"
}
run vista-wave $VISTA $WAVE
run far-wave $FAR $WAVE
run spot-wave $SPOT $WAVE
run zoom-wave $VISTA $WAVE --bench-prop zoom=10 --bench-prop warmup=40
run horizon-wave $HOR $WAVE
run horizon-t16 $HOR --jvm=-Dmc.lod=true --jvm=-Dmc.lod.radius=512 --bench-prop warmup=30
echo "photos done"
