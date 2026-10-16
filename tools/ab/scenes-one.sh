#!/bin/zsh
# One scenes run (functional lane, own game / shots dirs), no comparison. Round 11.
# usage: tools/ab/scenes-one.sh <label> <client-jar|vanilla> <api> [launch.py args...]
#   bench jar: $BENCH_JAR (default build/bench-r6b.jar); vanilla runs use SDL_VIDEO_MAC_FULLSCREEN_SPACES=0 like the pinned refs.
#   cave scenes only (bench-r11.jar): --bench-prop only=cave,cave_dark
ROOT="${0:A:h:h:h}"; cd "$ROOT"
L=$1; J=$2; A=$3; shift 3
rm -rf "run/shots-$L"
JAR=(); ENV=()
if [[ $J == vanilla ]]; then ENV=(--env SDL_VIDEO_MAC_FULLSCREEN_SPACES=0); else JAR=(--jar $J); fi
python3 tools/launch.py --lane functional $JAR --game-dir run/scenes-$L --api $A $ENV --bench-jar ${BENCH_JAR:-build/bench-r6b.jar} \
  --bench results/raw/scenes-$L.json --label $L --bench-prop mode=scenes --bench-prop shots="$PWD/run/shots-$L" "$@" \
  > results/raw/scenes-$L.out 2>&1
cp run/scenes-$L/launch.log results/raw/scenes-$L.log 2>/dev/null
echo "$L: $(ls run/shots-$L/screenshots/*.png 2>/dev/null | wc -l | tr -d ' ') shots, NOT SETTLED/READY: $(grep -c 'NOT SETTLED\|NOT READY' results/raw/scenes-$L.log), variant: $(grep -c 'Terrain shader variant .* built' results/raw/scenes-$L.log) built"
