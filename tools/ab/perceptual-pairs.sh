#!/bin/zsh
# Round 11: PerceptualDiff of scenes shots dirs, several comparisons sharing one noise floor.
# usage: FLOOR="a:b c:d ..." tools/ab/perceptual-pairs.sh <name>:<ref>:<cand> ...
#   labels are scenes-run labels (run/shots-<label>/screenshots); "vanilla" / "vanilla-N" = run/pinned/shots-vkns-1512x982-vs[-N].
# writes results/raw/perceptual-<name>.txt and prints each table's non-ok rows plus a verdict.
ROOT="${0:A:h:h:h}"; cd "$ROOT"
J=mc/runtime/java-runtime-epsilon/jre.bundle/Contents/Home/bin/java
dir() {
  if [[ $1 == vanilla ]]; then echo run/pinned/shots-vkns-1512x982-vs/screenshots
  elif [[ $1 == vanilla-* ]]; then echo run/pinned/shots-vkns-1512x982-vs-${1#vanilla-}/screenshots
  else echo run/shots-$1/screenshots; fi
}
F=(); for fp in ${=FLOOR}; do q=("${(@s/:/)fp}"); F+=("$(dir $q[1])" "$(dir $q[2])"); done
for spec in "$@"; do
  parts=("${(@s/:/)spec}")
  $J tools/PerceptualDiff.java "$(dir $parts[2])" "$(dir $parts[3])" ${F:+--floor} $F > results/raw/perceptual-$parts[1].txt 2>&1
  rc=$?
  echo "## $parts[1] ($parts[2] -> $parts[3]): exit $rc"
  awk 'NR == 1 || $0 !~ / ok /' results/raw/perceptual-$parts[1].txt
done
