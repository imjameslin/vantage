#!/bin/zsh
# Scene / zoomseq run in a fresh game dir; tools/launch.py restores the pinned world and options (run/pinned/scenes,
# docs/metal/CONTRACT.md 11.6). Compare against run/pinned/shots-vkns-1512x982-vs (settled-vignette runner, bench/README.md).
# Functional lane of the GPU lock (shared: several scene runs overlap, never a timing run; tools/gpulock.py).
# usage: scenes.sh <label> <mode scenes|zoomseq> [launch.py args...]
# env: SCENES_JAR (default build/client-r5i.jar), SCENES_BJAR (default build/bench-pin1.jar); frozen copies only.
WT="${0:A:h:h:h}"
cd "$WT" || exit 1
LABEL=$1; MODE=$2; shift 2
GD=run/scenes-$LABEL; SH=run/shots-$LABEL
rm -rf "$GD" "$SH" && mkdir -p "$GD" "$SH/screenshots"
rm -f "results/raw/scenes-$LABEL.json"
tools/check-overlays.sh >/dev/null || echo "(overlays present: composited)"
python3 tools/launch.py --lane functional --jar "${SCENES_JAR:-build/client-r5i.jar}" --bench-jar "${SCENES_BJAR:-build/bench-pin1.jar}" --game-dir "$GD" --api metal \
  --bench "results/raw/scenes-$LABEL.json" --label "$LABEL" --bench-prop mode=$MODE --bench-prop shots="$PWD/$SH" "$@" >/dev/null
cp "$GD/launch.log" "results/raw/scenes-$LABEL.log"
echo "$LABEL: $(cat results/raw/scenes-$LABEL.json 2>/dev/null | head -c 1500)"
grep -c 'mc.fastAnimateTick.verify:' "$GD/launch.log" | sed 's/^/verify mismatch lines: /'
grep -E 'NOT SETTLED|NOT READY|entity check|Exception|5s timeout' "$GD/launch.log" | head -20
ls "$SH/screenshots" | wc -l | sed 's/^/screenshots: /'
