#!/bin/zsh
# Stage 3 r6 (results/STAGE3-R6.md): one FPS-line photo of the wave far field (tau 2, 512 chunks) at a named view.
# usage: tools/lod/s3r6-photo.sh <label> <client jar> <bench jar> <view> <out png> [launch.py args...]
#   views: vista (y 180, pitch 18), far (y 180, pitch 6), spot (y 106), horizon (y 120, pitch 2), taiga (y 130, pitch 8: snowy
#   taiga middle distance), all yaw 200 at x/z 6.5 in the fixture world run/s3-lod
ROOT="${0:A:h:h:h}"; cd "$ROOT"
L=$1; J=$2; B=$3; V=$4; OUT=$5; shift 5
case $V in
  vista) A=(--bench-prop pos=6.5,180,6.5 --bench-prop pitch=18 --bench-prop yaw=200);;
  far) A=(--bench-prop pos=6.5,180,6.5 --bench-prop pitch=6 --bench-prop yaw=200);;
  spot) A=(--bench-prop pos=6.5,106,6.5 --bench-prop yaw=200);;
  horizon) A=(--bench-prop pos=6.5,120,6.5 --bench-prop pitch=2 --bench-prop yaw=200);;
  taiga) A=(--bench-prop pos=6.5,130,6.5 --bench-prop pitch=8 --bench-prop yaw=200);;
  *) echo "unknown view $V"; exit 2;;
esac
WAVE=(--jvm=-Dmc.lod=true --jvm=-Dmc.lod.wave=true --jvm=-Dmc.lod.radius=512 --jvm=-Dmc.lod.tau=2 --bench-prop warmup=30)
zsh tools/lod/lod-run.sh photo $L $J $B $A $WAVE "$@"
mkdir -p "${OUT:h}"
cp run/s3-shots/$L/screenshots/photo-$L-2.png "$OUT" 2>/dev/null || echo "no photo for $L"
