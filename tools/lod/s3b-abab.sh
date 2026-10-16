#!/bin/zsh
# Stage 3 round 1b final in-session A/B (results/STAGE3-R1.md): Lod#draw false (vanilla RD 12) | true (RD 12 + LOD) at LOD
# radius 64 / 128 / 256 with the round-1b defaults (tau 16), and radius 256 at tau 8 (finer setting).
# usage: tools/lod/s3b-abab.sh <client jar> <bench jar>
ROOT="${0:A:h:h:h}"; cd "$ROOT"
J=$1; B=$2
for R in 64 128 256; do
  zsh tools/lod/lod-run.sh abab s3b-ab-r$R $J $B --jvm=-Dmc.lod.radius=$R --jvm=-Dmc.lod.stats=30
done
zsh tools/lod/lod-run.sh abab s3b-ab-r256-t8 $J $B --jvm=-Dmc.lod.radius=256 --jvm=-Dmc.lod.tau=8 --jvm=-Dmc.lod.stats=30
echo "abab done"
