#!/bin/zsh
# Stage 3 r5 queue 2 (results/STAGE3-R5.md): the wave far field (mc.lod.wave) at Voxy's detail (tau 2), 512 chunks.
#   voxy     26.2 + Sodium + Voxy install at the same views (borderless fullscreen = 3024x1964; Voxy disables itself on GL 4.1)
#   photos   wave photos (vista, far, spot, zoom) into <photo dir>
#   bars     abab Lod#draw for the wave at tau 2 / 4 (spin, usual spot) + the panorama off (per-frame lines only)
#   percept  perceptual check vs real RD 32 (wave tau 2 at R 32)
#   travel   pre-generated travel world, LOD 512: wave tau 2 vs raster default (tau 16) vs no LOD
# usage: tools/lod/s3r5-queue2.sh <client jar> <bench jar> <photo dir> [parts]
ROOT="${0:A:h:h:h}"; cd "$ROOT"
J=$1; B=$2; P=$3; PARTS=${4:-voxy,photos,bars,percept,travel}
mkdir -p "$P"
VISTA=(--bench-prop pos=6.5,180,6.5 --bench-prop pitch=18 --bench-prop yaw=200)
FAR=(--bench-prop pos=6.5,180,6.5 --bench-prop pitch=6 --bench-prop yaw=200)
SPOT=(--bench-prop pos=6.5,106,6.5 --bench-prop yaw=200)
WAVE=(--jvm=-Dmc.lod=true --jvm=-Dmc.lod.wave=true --jvm=-Dmc.lod.radius=512 --jvm=-Dmc.lod.tau=2)
if [[ $PARTS == *voxy* ]]; then
  echo "== 26.2 + Sodium + Voxy (borderless fullscreen)"
  for v in vista far spot; do
    case $v in vista) A=($VISTA);; far) A=($FAR);; spot) A=($SPOT);; esac
    VOXY_WARMUP=20 zsh tools/voxy/voxy-run.sh photo vx6-$v $A
    cp run/s3-shots/vx6-$v/screenshots/photo-vx6-$v-2.png "$P/voxy262-$v.png" 2>/dev/null || echo "no photo vx6-$v"
  done
  VOXY_WARMUP=20 zsh tools/voxy/voxy-run.sh perf vx6-perf-vista $VISTA
  VOXY_WARMUP=20 zsh tools/voxy/voxy-run.sh perf vx6-perf-spot $SPOT
  grep -h "Voxy is unsupported\|Using graphics backend" run/voxy262/launch.log | head -3
fi
if [[ $PARTS == *photos* ]]; then
  echo "== wave photos"
  run() {
    local L=$1; shift
    zsh tools/lod/lod-run.sh photo r5p-$L $J $B "$@"
    cp run/s3-shots/r5p-$L/screenshots/photo-r5p-$L-2.png "$P/$L.png" 2>/dev/null || echo "no photo for $L"
  }
  run vista-wave-ring0 $VISTA $WAVE --jvm=-Dmc.lod.wave.ringPx=0 --bench-prop warmup=30
  run vista-wave $VISTA $WAVE --bench-prop warmup=30
  run far-wave $FAR $WAVE --bench-prop warmup=30
  run spot-wave $SPOT $WAVE --bench-prop warmup=30
  run zoom-wave $VISTA $WAVE --bench-prop zoom=10 --bench-prop warmup=40
fi
if [[ $PARTS == *bars* ]]; then
  echo "== bars"
  zsh tools/lod/lod-run.sh abab ${PFX:-r5q}-wave-t2 $J $B --jvm=-Dmc.lod.wave=true --jvm=-Dmc.lod.radius=512 --jvm=-Dmc.lod.tau=2 | sed -n 1,10p
  zsh tools/lod/lod-run.sh abab ${PFX:-r5q}-wave-t2-ring0 $J $B --jvm=-Dmc.lod.wave=true --jvm=-Dmc.lod.radius=512 --jvm=-Dmc.lod.tau=2 --jvm=-Dmc.lod.wave.ringPx=0 | sed -n 1,10p
  zsh tools/lod/lod-run.sh abab ${PFX:-r5q}-wave-t2-nopano $J $B --jvm=-Dmc.lod.wave=true --jvm=-Dmc.lod.radius=512 --jvm=-Dmc.lod.tau=2 --jvm=-Dmc.lod.wave.panorama=false | sed -n 1,10p
fi
if [[ $PARTS == *percept* ]]; then
  echo "== perceptual R 32 (wave tau 2)"
  for cfg in "f-hyb 4" "f-pure 0"; do
    set -- ${=cfg}
    mkdir -p results/raw/pc5$1/ref
    cp results/raw/pc5-wave/ref/*.png results/raw/pc5$1/ref/ 2>/dev/null
    echo "-- $1"
    REUSE_REF=1 zsh tools/lod/percept.sh $J $B results/raw/pc5$1 32 2 --jvm=-Dmc.lod.wave=true --jvm=-Dmc.lod.wave.ringPx=$2 | tail -3
  done
fi
if [[ $PARTS == *travel* ]]; then
  echo "== travel LOD 512"
  zsh tools/bench-travel.sh ${PFX:-r5q}-travel-wave run/r3q --pregen --jar $J --bench-jar $B --api metal $WAVE --bench-prop timeout=1500
  zsh tools/bench-travel.sh ${PFX:-r5q}-travel-wave-ring0 run/r3q --pregen --jar $J --bench-jar $B --api metal $WAVE --jvm=-Dmc.lod.wave.ringPx=0 --bench-prop timeout=1500
fi
echo "queue2 done"
