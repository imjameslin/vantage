#!/bin/zsh
# Stage 3 r2 LOD fog checks (CONTRACT 16.S3-LOD-FOG): LOD 256 photos in weather / time / medium conditions, on a clonefile copy
# of the fixture (run/s3-lod-scenes: the commands are saved into the world), LOD on, fog on.
# usage: tools/lod/fog-check.sh <client jar> <bench jar> <out dir> [extra launch args...]
ROOT="${0:A:h:h:h}"; cd "$ROOT"
J=$1; B=$2; OUT=$3; shift 3
mkdir -p "$OUT"
[[ -d run/s3-lod-scenes ]] || cp -cR run/s3-lod run/s3-lod-scenes
export LOD_GAME_DIR=run/s3-lod-scenes
VISTA=(--bench-prop pos=6.5,150,6.5 --bench-prop pitch=10 --bench-prop yaw=200)
ON=(--jvm=-Dmc.lod=true --jvm=-Dmc.lod.radius=256 "$@")
run() {   # label, args...
  local L=$1; shift
  zsh tools/lod/lod-run.sh photo $L $J $B "$@"
  cp run/s3-shots/$L/screenshots/photo-$L-2.png "$OUT/$L.png" 2>/dev/null || echo "no photo for $L"
}
run fog-day $VISTA $ON
run fog-sunset --bench-prop pos=6.5,150,6.5 --bench-prop pitch=5 --bench-prop yaw=90 --bench-prop 'cmds=time set 12600' $ON
run fog-night $VISTA --bench-prop 'cmds=time set 18000' $ON
run fog-rain $VISTA --bench-prop 'cmds=weather rain' $ON
run fog-thunder $VISTA --bench-prop 'cmds=time set 14000;weather thunder' $ON
run fog-underwater --bench-prop pos=6.5,120,6.5 --bench-prop pitch=5 --bench-prop yaw=200 \
  --bench-prop 'cmds=fill 0 114 0 13 126 13 minecraft:glass hollow;fill 1 115 1 12 125 12 minecraft:water' $ON
run fog-nether --bench-prop pos=0.5,81,0.5 --bench-prop pitch=10 --bench-prop yaw=0 \
  --bench-prop 'cmds=execute in minecraft:the_nether run fill -3 78 -3 3 84 3 minecraft:air;execute in minecraft:the_nether run tp @s 0.5 81 0.5' $ON
run fog-day-off $VISTA
echo "fog checks done"
