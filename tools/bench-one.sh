#!/bin/zsh
# One benchmark run in a persistent (pre-generated) game dir, with the quiet/overlay gates. Timing lane of the GPU lock
# (tools/gpulock.py, exclusive): launch.py checks the gates after it has the lock, so time spent queueing behind other
# runs cannot invalidate them ("(load high)" / "(overlays present: composited)" on stderr).
# usage: bench-one.sh <label> <gamedir> [launch.py args...]
ROOT="${0:A:h:h}"; cd "$ROOT"
LABEL=$1; GD=$2; shift 2
# Pin vanilla's default video settings (the travel benchmark writes the Fancy preset into its own dirs).
rm -f "results/raw/$LABEL.json"   # a failed launch must not be summarised from a stale JSON
STATIC=(); while IFS= read -r line; do STATIC+=(--opt "$line"); done < "$ROOT/tools/static-options.txt"
python3 tools/launch.py --game-dir "$GD" --opt fullscreen:true $STATIC --quiet-gate 4.0:900 --overlay-check --bench results/raw/$LABEL.json --label $LABEL \
  --bench-prop warmup=15 --bench-prop seconds=30 "$@" >/dev/null
python3 -c "import json,sys; d=json.load(open(sys.argv[1])); print(f\"{sys.argv[2]:22s} {d['backend']:7s} median {d['medianFps']:6.0f}  avg {d['avgFps']:6.0f}  1%low {d['p1LowFps']:5.0f}  0.1%low {d['p01LowFps']:5.0f}\")" results/raw/$LABEL.json $LABEL
