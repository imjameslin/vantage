#!/bin/zsh
# Release-candidate parity runs (bench/README.md "Candidate"): scenes + zoomseq in the functional lane, own game / shots dirs.
# usage: tools/ab/rc-parity.sh <label> <client-jar> [launch.py args...]   (bench jar: $BENCH_JAR or build/bench-r6b.jar)
ROOT="${0:A:h:h:h}"; cd "$ROOT"
L=$1; J=$2; shift 2
B=${BENCH_JAR:-build/bench-r6b.jar}
rm -rf "run/shots-$L" "run/zoomseq-$L"
python3 tools/launch.py --lane functional --jar $J --game-dir run/scenes-$L --api metal --bench-jar $B \
  --bench results/raw/scenes-$L.json --label $L --bench-prop mode=scenes --bench-prop shots="$PWD/run/shots-$L" "$@" \
  > results/raw/scenes-$L.out 2>&1 &
python3 tools/launch.py --lane functional --jar $J --game-dir run/zoomseq-$L --api metal --bench-jar $B \
  --bench results/raw/zoomseq-$L.json --label zoomseq-$L --bench-prop mode=zoomseq --bench-prop shots="$PWD/run/zoomseq-$L" "$@" \
  > results/raw/zoomseq-$L.out 2>&1
wait
cp run/scenes-$L/launch.log results/raw/scenes-$L.log 2>/dev/null
J8=mc/runtime/java-runtime-epsilon/jre.bundle/Contents/Home/bin/java
R=run/pinned/shots-vkns-1512x982-vs/screenshots
F=(); for n in 2 4 5 6 7; do F+=($R run/pinned/shots-vkns-1512x982-vs-$n/screenshots); done
F+=(run/pinned/shots-vkns-1512x982-vs-2/screenshots run/pinned/shots-vkns-1512x982-vs-3/screenshots)
$J8 tools/ImgDiff.java $R run/shots-$L/screenshots run/diff-pin-$L > results/raw/imgdiff-$L.txt 2>&1
$J8 tools/PerceptualDiff.java $R run/shots-$L/screenshots --floor $F > results/raw/perceptual-$L.txt 2>&1
$J8 tools/ImgDiff.java run/pinned/zoomseq-vkns-1512x982/screenshots/zoomseq run/zoomseq-$L/screenshots/zoomseq run/diff-pinzq-$L > results/raw/imgdiff-zq-$L.txt 2>&1
echo "parity $L done"
