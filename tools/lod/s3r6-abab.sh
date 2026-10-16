#!/bin/zsh
# Stage 3 r6: in-session A/B of a wave toggle in the fixture world (tools/lod/lod-run.sh abab), wave far field tau 2 at 512 chunks.
# usage: tools/lod/s3r6-abab.sh <label> <client jar> <bench jar> <view> <toggles> [launch.py args...]
#   view: spin (the usual spin at the bench spot) | vista (camera held at the vista view: y 180, pitch 18, yaw 200) |
#         taiga (held: y 130, pitch 8, yaw 200)
ROOT="${0:A:h:h:h}"; cd "$ROOT"
L=$1; J=$2; B=$3; V=$4; T=$5; shift 5
case $V in
  spin) A=();;
  vista) A=(--bench-prop abab.preset=heavy --bench-prop abab.heavyYaw=200 --bench-prop pitch=18 --bench-prop pos=6.5,180,6.5);;
  taiga) A=(--bench-prop abab.preset=heavy --bench-prop abab.heavyYaw=200 --bench-prop pitch=8 --bench-prop pos=6.5,130,6.5);;
  *) echo "unknown view $V"; exit 2;;
esac
LOD_TOGGLES="$T" zsh tools/lod/lod-run.sh abab $L $J $B --jvm=-Dmc.lod.wave=true --jvm=-Dmc.lod.radius=512 --jvm=-Dmc.lod.tau=2 $A "$@"
