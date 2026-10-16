#!/bin/zsh
# Stage 3 r3: speed / quality frontier of the LOD detail settings (results/STAGE3-R3.md): per configuration an in-session A/B
# of Lod#draw at LOD 256 (FPS with / without LOD) and the perceptual check at R 32 (real RD 32 vs RD 12 + LOD, reusing the
# reference shots in results/raw/pc3-t16/ref). Configurations as "label tau [extra launch args...]" lines on stdin.
# usage: tools/lod/s3r3-frontier.sh <client jar> <bench jar> < configs
ROOT="${0:A:h:h:h}"; cd "$ROOT"
J=$1; B=$2
while read -r label tau rest; do
  [[ -z "$label" || "$label" == \#* ]] && continue
  extra=(${=rest})
  echo "== $label (tau $tau) $extra"
  zsh tools/lod/lod-run.sh abab r3f-$label $J $B --jvm=-Dmc.lod.radius=256 --jvm=-Dmc.lod.tau=$tau $extra | sed -n 5,7p
  mkdir -p results/raw/pc3-$label/ref
  cp results/raw/pc3-t16/ref/*.png results/raw/pc3-$label/ref/
  REUSE_REF=1 zsh tools/lod/percept.sh $J $B results/raw/pc3-$label 32 $tau $extra | tail -3
done
echo "frontier done"
