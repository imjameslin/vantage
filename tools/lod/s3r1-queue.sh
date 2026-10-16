#!/bin/zsh
# Stage 3 round 1 measurement queue (results/STAGE3-R1.md). usage: tools/lod/s3r1-queue.sh <client jar> <bench jar> [runs...]
# runs: abab-<radius>-<tau> | base | photos
ROOT="${0:A:h:h:h}"; cd "$ROOT"
J=$1; B=$2; shift 2
for r in "$@"; do
  case $r in
    abab-*)
      parts=(${(s:-:)r}); R=${parts[2]}; T=${parts[3]}
      zsh tools/lod/lod-run.sh abab s3ab-r$R-t$T $J $B --jvm=-Dmc.lod.radius=$R --jvm=-Dmc.lod.tau=$T --jvm=-Dmc.lod.stats=30 ;;
    base)
      zsh tools/lod/lod-run.sh perf s3perf-nolod $J $B ;;
    perf-*)
      parts=(${(s:-:)r}); R=${parts[2]}; T=${parts[3]}
      zsh tools/lod/lod-run.sh perf s3perf-r$R-t$T $J $B --jvm=-Dmc.lod=true --jvm=-Dmc.lod.radius=$R --jvm=-Dmc.lod.tau=$T ;;
  esac
done
echo "queue done"
