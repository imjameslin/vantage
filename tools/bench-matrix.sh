#!/bin/zsh
# Run a list of benchmark configs serially (GPU must not be shared between runs: timing lane of the GPU lock; launch.py
# checks the quiet / overlay gates once it has the lock).
# usage: bench-matrix.sh <tag> <jar-or-"vanilla"> <api>:<fullscreen true|false> ...
ROOT="${0:A:h:h}"
TAG=$1; JAR=$2; shift 2
for cfg in "$@"; do
  api=${cfg%%:*}; fs=${cfg##*:}
  label="$TAG-$api-$([[ $fs == true ]] && echo native || echo window)"
  jararg=(); [[ $JAR != vanilla ]] && jararg=(--jar "$JAR")
  echo "=== $label"
  python3 "$ROOT/tools/launch.py" $jararg --game-dir "$ROOT/run/$TAG" --api $api --opt fullscreen:$fs --quiet-gate 4.0:900 --overlay-check \
    --bench "$ROOT/results/raw/$label.json" --label "$label" --bench-prop warmup=15 --bench-prop seconds=30
  python3 -c "import json,sys; d=json.load(open(sys.argv[1])); print({k:d.get(k) for k in ['backend','framebuffer','avgFps','medianFps','p1LowFps','error']})" "$ROOT/results/raw/$label.json"
  sleep 3
done
