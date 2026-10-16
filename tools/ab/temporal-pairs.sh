#!/bin/zsh
# Round 11: temporal + per-frame perceptual check (PerceptualDiff --temporal) of zoomseq runs.
# usage: tools/ab/temporal-pairs.sh <name>:<ref zoomseq label|vanilla>:<cand zoomseq label> ...
# FLOOR="ref:cand ..." (zoomseq labels / vanilla): runs of the exact configuration whose differences are run-to-run noise.
# writes results/raw/temporal-<name>.txt and prints the summary line of each.
ROOT="${0:A:h:h:h}"; cd "$ROOT"
J=mc/runtime/java-runtime-epsilon/jre.bundle/Contents/Home/bin/java
dir() { if [[ $1 == vanilla ]]; then echo run/pinned/zoomseq-vkns-1512x982/screenshots/zoomseq; else echo run/zoomseq-$1/screenshots/zoomseq; fi }
for spec in "$@"; do
  parts=("${(@s/:/)spec}")
  F=(); for fp in ${=FLOOR}; do q=("${(@s/:/)fp}"); F+=("$(dir $q[1])" "$(dir $q[2])"); done
  $J tools/PerceptualDiff.java --temporal "$(dir $parts[2])" "$(dir $parts[3])" ${F:+--floor} $F > results/raw/temporal-$parts[1].txt 2>&1
  echo "$parts[1] ($parts[2] -> $parts[3]): $(tail -1 results/raw/temporal-$parts[1].txt)"
done
