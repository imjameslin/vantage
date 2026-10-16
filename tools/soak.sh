#!/bin/zsh
# Singleplayer soak (docs/GOAL.md stage 1: 30-minute soak without crashes or memory growth; bench/src/bench/Soak.java).
# Fresh world "soak" every run (deleted first), the travel benchmark's Fancy preset (render distance 16), starts fullscreen;
# flies over fresh terrain, toggles fullscreen and resizes the window, samples memory every 10 s, judges memory growth.
# launch.py takes the machine-wide game lock in the timing lane (exclusive, tools/gpulock.py): the soak toggles fullscreen and
# resizes the window (a second game on the display would interfere), its stall detector and flight load are wall-clock
# based, and its memory verdict reads process footprint, which another game's memory pressure can move. Not an FPS
# benchmark (FPS is recorded for information only).
# usage: tools/soak.sh <label> <gamedir> <minutes> [launch.py args...]
#   e.g. tools/soak.sh soak-r5i run/soak 30 --jar build/client-r5i.jar --api metal --bench-jar build/bench-r5h.jar
#   smoke: tools/soak.sh soak-smoke run/soak 3 --jar ... --api metal --bench-prop soak.warmup=60 --bench-prop soak.gcEvery=30 \
#          --bench-prop soak.windowCycle=60 --bench-prop soak.sample=5 --bench-prop soak.bucket=15
# result: results/raw/<label>.json (series + verdict); report and exit code from tools/soak_report.py
#   (0 = PASS, 2 = PASS but inconclusive (trend span < 15 min), 1 = FAIL).
ROOT="${0:A:h:h}"; cd "$ROOT"
LABEL=$1; GD=${2:A}; MIN=$3; shift 3
mkdir -p "$GD" results/raw
"$ROOT/tools/check-overlays.sh" >/dev/null || echo "(overlays present: composited)"
rm -f "results/raw/$LABEL.json"
FANCY=(--opt 'graphicsPreset:"fancy"' --opt biomeBlendRadius:2 --opt renderDistance:16 --opt prioritizeChunkUpdates:1
       --opt simulationDistance:12 --opt ao:true --opt 'renderClouds:"true"' --opt particles:0 --opt mipmapLevels:4
       --opt entityShadows:true --opt entityDistanceScaling:1.0 --opt menuBackgroundBlurriness:5 --opt cloudRange:64
       --opt cutoutLeaves:true --opt improvedTransparency:false --opt weatherRadius:10 --opt maxAnisotropyBit:1
       --opt textureFiltering:1)
STAMP=$(date +%s)
python3 tools/launch.py --game-dir "$GD" --opt fullscreen:true $FANCY --fresh-world soak --bench "results/raw/$LABEL.json" --label "$LABEL" \
  --bench-prop mode=soak --bench-prop world=soak --bench-prop soak.minutes=$MIN "$@" >/dev/null
RC=$?
python3 tools/soak_report.py "results/raw/$LABEL.json" --exit-code $RC --game-dir "$GD" --since $STAMP
