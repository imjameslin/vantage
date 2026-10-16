#!/bin/zsh
# Stage 3 r5 queue (results/STAGE3-R5.md): (1) the 26.2 comparison install (Sodium; Voxy disables itself on macOS OpenGL 4.1)
# at the usual views, (2) our raster LOD at 512 chunks across detail settings up to Voxy's (tau 2), photos + abab bars.
# usage: tools/lod/s3r5-queue.sh <client jar> <bench jar> <photo dir> [parts: voxy,photos,bars]
ROOT="${0:A:h:h:h}"; cd "$ROOT"
J=$1; B=$2; P=$3; PARTS=${4:-voxy,photos,bars}
mkdir -p "$P"
VISTA=(--bench-prop pos=6.5,180,6.5 --bench-prop pitch=18 --bench-prop yaw=200)
FAR=(--bench-prop pos=6.5,180,6.5 --bench-prop pitch=6 --bench-prop yaw=200)
SPOT=(--bench-prop pos=6.5,106,6.5 --bench-prop yaw=200)
if [[ $PARTS == *voxy* ]]; then
  echo "== 26.2 + Sodium + Voxy (Voxy unsupported on this GL) photos / perf"
  for v in vista far spot; do
    case $v in vista) A=($VISTA);; far) A=($FAR);; spot) A=($SPOT);; esac
    VOXY_WARMUP=20 zsh tools/voxy/voxy-run.sh photo vx5-$v $A
    cp run/s3-shots/vx5-$v/screenshots/photo-vx5-$v-2.png "$P/voxy262-$v.png" 2>/dev/null || echo "no photo vx5-$v"
  done
  VOXY_WARMUP=20 zsh tools/voxy/voxy-run.sh perf vx5-perf-vista $VISTA
  grep -h "Voxy is unsupported\|Using graphics backend" run/voxy262/launch.log | head -3
fi
if [[ $PARTS == *photos* ]]; then
  echo "== ours: photos at 512 chunks"
  run() {   # label, args...
    local L=$1; shift
    zsh tools/lod/lod-run.sh photo r5p-$L $J $B "$@"
    cp run/s3-shots/r5p-$L/screenshots/photo-r5p-$L-2.png "$P/$L.png" 2>/dev/null || echo "no photo for $L"
  }
  run spot-off $SPOT
  run vista-off $VISTA
  for tau in 16 8 4 2; do
    G=(--jvm=-Dmc.lod=true --jvm=-Dmc.lod.radius=512 --jvm=-Dmc.lod.tau=$tau --jvm=-Dmc.lod.gpuMB=4096 --bench-prop warmup=30)
    run vista-t$tau $VISTA $G
    run far-t$tau $FAR $G
  done
fi
if [[ $PARTS == *bars* ]]; then
  echo "== ours: abab Lod#draw at 512 chunks"
  for tau in 16 8 4 2; do
    zsh tools/lod/lod-run.sh abab r5q-r512-t$tau $J $B --jvm=-Dmc.lod.radius=512 --jvm=-Dmc.lod.tau=$tau --jvm=-Dmc.lod.gpuMB=4096 | sed -n 1,10p
  done
fi
echo "queue done"
