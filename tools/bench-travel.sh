#!/bin/zsh
# Realistic exploration benchmark: fresh world every run (deleted first), Fancy preset (render distance 16,
# RGSS texture filtering, fancy clouds, ...: tools/travel-options.txt), fullscreen, flying east at ground level at 10.9 blocks/s.
# Timing lane of the GPU lock (exclusive); the quiet / overlay gates, the fresh-world delete and the pre-generated world
# restore all happen in launch.py once it has the lock (time spent queueing cannot make them stale).
# usage: bench-travel.sh <label> <gamedir> [--pregen] [launch.py args...]
#   --pregen (or env TRAVEL_PREGEN=1): QUICK SCREENING ONLY. Instead of generating a fresh world, restore the pinned
#   pre-generated one (tools/pregen-travel.sh, made on first use): the flight then loads its chunks from disk instead of
#   generating them, which is a lighter, different load. Final travel numbers keep the fresh-world protocol (results/SPEEDUPS.md
#   has how screening deltas compared with fresh-world deltas).
ROOT="${0:A:h:h}"; cd "$ROOT"
LABEL=$1; GD=$2-travel; shift 2   # own game dir: never shares options with the static benchmark
PREGEN=${TRAVEL_PREGEN:-0}; if [[ $1 == --pregen ]]; then PREGEN=1; shift; fi
if (( PREGEN )); then
  SNAP=$(zsh "$ROOT/tools/pregen-travel.sh") || { echo "bench-travel.sh: no pre-generated world"; exit 1; }
  WORLD=(--world-snapshot "$SNAP" --world-only)
else
  WORLD=(--fresh-world travel)
fi
rm -f "results/raw/$LABEL.json"   # a failed launch must not be summarised from a stale JSON
FANCY=(); while IFS= read -r line; do FANCY+=(--opt "$line"); done < "$ROOT/tools/travel-options.txt"
MODE=$(swift "$ROOT/tools/swift/display-mode.swift" 2>/dev/null)
python3 tools/launch.py --game-dir "$GD" --opt fullscreen:true $FANCY --quiet-gate 4.0:900 --overlay-check $WORLD \
  --bench results/raw/$LABEL.json --label $LABEL \
  --bench-prop mode=travel --bench-prop world=travel --bench-prop warmup=10 --bench-prop seconds=30 "$@" >/dev/null
python3 -c "import json,sys; d=json.load(open(sys.argv[1])); print(f\"{sys.argv[2]:22s} {d.get('backend','?'):7s} median {d.get('medianFps',0):6.0f}  avg {d.get('avgFps',0):6.0f}  1%low {d.get('p1LowFps',0):5.0f}  0.1%low {d.get('p01LowFps',0):5.0f} {d.get('error','')}\")" results/raw/$LABEL.json $LABEL
MODE1=$(swift "$ROOT/tools/swift/display-mode.swift" 2>/dev/null)   # (the launch may have queued on the game lock)
echo "  ${LABEL}: $( (( PREGEN )) && echo "pre-generated world ${SNAP:t}" || echo "fresh world"), $MODE1$([[ $MODE1 != $MODE ]] && echo " (DISPLAY MODE CHANGED: was $MODE)")"
