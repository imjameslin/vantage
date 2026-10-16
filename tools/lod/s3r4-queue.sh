#!/bin/zsh
# Stage 3 r4 measurement queue (results/STAGE3-R4.md): bars (abab Lod#draw at LOD 128 / 256, tau 16 / 8 / tauLevels=10),
# perceptual check at R 32 (reusing the round-3 reference shots in results/raw/pc3-t16/ref), generated vs real look,
# zoom temporal check, photos for share/lod4.
# usage: tools/lod/s3r4-queue.sh <client jar> <bench jar> <photo out dir>
ROOT="${0:A:h:h:h}"; cd "$ROOT"
J=$1; B=$2; P=$3
echo "== bars"
zsh tools/lod/lod-run.sh abab r4q-r256-t16 $J $B --jvm=-Dmc.lod.radius=256 | sed -n 5,8p
zsh tools/lod/lod-run.sh abab r4q-r256-t8 $J $B --jvm=-Dmc.lod.radius=256 --jvm=-Dmc.lod.tau=8 | sed -n 5,8p
zsh tools/lod/lod-run.sh abab r4q-r128-t16 $J $B --jvm=-Dmc.lod.radius=128 | sed -n 5,8p
zsh tools/lod/lod-run.sh abab r4q-r128-t8 $J $B --jvm=-Dmc.lod.radius=128 --jvm=-Dmc.lod.tau=8 | sed -n 5,8p
zsh tools/lod/lod-run.sh abab r4q-r256-l10 $J $B --jvm=-Dmc.lod.radius=256 --jvm=-Dmc.lod.tauLevels=10 | sed -n 5,8p
echo "== perceptual R 32"
for cfg in "t16 16" "t8 8" "l10 16 --jvm=-Dmc.lod.tauLevels=10"; do
  set -- ${=cfg}
  name=$1; tau=$2; shift 2
  mkdir -p results/raw/pc4-$name/ref
  cp results/raw/pc3-t16/ref/*.png results/raw/pc4-$name/ref/
  echo "-- $name"
  REUSE_REF=1 zsh tools/lod/percept.sh $J $B results/raw/pc4-$name 32 $tau "$@" | tail -3
done
echo "== gen-compare R256 tau16"
zsh tools/lod/gen-compare.sh $J $B results/raw/gc5 256 16 | tail -3
echo "== zoomseq LOD 256"
LOD_GAME_DIR=run/s3-lod-scenes zsh tools/lod/lod-run.sh zoomseq r4q-zs $J $B --jvm=-Dmc.lod=true --jvm=-Dmc.lod.radius=256
zsh tools/lod/zoomseq-pops.sh run/s3-shots/r4q-zs/screenshots/zoomseq results/raw/r4q-zspops | tail -30
echo "== photos"
PHOTO_PREFIX=r4p zsh tools/lod/s3r3-photos.sh $J $B $P
echo "queue done"
