#!/bin/zsh
# Metal System Trace of a static-options run in run/metal (round 9 budget).
# usage: tools/ab/mst-static.sh <label> <client-jar> [launch.py args...]   (bench jar: $BENCH_JAR or build/bench-r6b.jar)
ROOT="${0:A:h:h:h}"; cd "$ROOT"
L=$1; J=$2; shift 2
S=(); while IFS= read -r l; do S+=(--opt "$l"); done < tools/static-options.txt
MST_GD=run/metal zsh tools/mst-run.sh $L --jar $J --api metal --bench-jar ${BENCH_JAR:-build/bench-r6b.jar} $S "$@"
