#!/bin/zsh
# One zoomseq run (functional lane, own dirs) and its ImgDiff against the pinned vanilla zoomseq.
# usage: tools/ab/zoomseq-one.sh <label> <client-jar> [launch.py args...]
ROOT="${0:A:h:h:h}"; cd "$ROOT"
L=$1; J=$2; shift 2
rm -rf "run/zoomseq-$L"
python3 tools/launch.py --lane functional --jar $J --game-dir run/zoomseq-$L --api metal --bench-jar ${BENCH_JAR:-build/bench-r6b.jar} \
  --bench results/raw/zoomseq-$L.json --label zoomseq-$L --bench-prop mode=zoomseq --bench-prop shots="$PWD/run/zoomseq-$L" "$@" \
  > results/raw/zoomseq-$L.out 2>&1
mc/runtime/java-runtime-epsilon/jre.bundle/Contents/Home/bin/java tools/ImgDiff.java run/pinned/zoomseq-vkns-1512x982/screenshots/zoomseq \
  run/zoomseq-$L/screenshots/zoomseq run/diff-pinzq-$L > results/raw/imgdiff-zq-$L.txt 2>&1
echo "$L: $(grep -c ' ok$' results/raw/imgdiff-zq-$L.txt) ok, not ok: $(grep -v ' ok$' results/raw/imgdiff-zq-$L.txt | tail -n +2 | awk '{print $1}' | tr '\n' ' ')"
