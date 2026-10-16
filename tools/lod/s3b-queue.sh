#!/bin/zsh
# Stage 3 round 1b perf queue: tools/lod/s3b-queue.sh <client jar> <bench jar> <prefix> <spec...>
# spec: nolod | r<radius>-t<tau>[-<extra jvm prop>=<v>...]  (perf runs, LOD on unless nolod)
ROOT="${0:A:h:h:h}"; cd "$ROOT"
J=$1; B=$2; P=$3; shift 3
for spec in "$@"; do
  if [[ $spec == nolod ]]; then
    zsh tools/lod/lod-run.sh perf $P-nolod $J $B
    continue
  fi
  parts=(${(s:-:)spec}); R=${parts[1]#r}; T=${parts[2]#t}
  X=(); for e in ${parts[3,-1]}; do X+=(--jvm=-Dmc.lod.$e); done
  zsh tools/lod/lod-run.sh perf $P-$spec $J $B --jvm=-Dmc.lod=true --jvm=-Dmc.lod.radius=$R --jvm=-Dmc.lod.tau=$T $X
done
echo "queue done"
