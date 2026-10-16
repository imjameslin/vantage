#!/bin/zsh
# Benchmark run with a Metal System Trace captured during the measurement phase.
# Timing lane of the GPU lock (exclusive; launch.py checks the quiet / overlay gates once it has the lock). The launch may
# queue behind other runs first: this script waits for its own run's measurement phase however long that takes, and
# attaches to its own game (the java process with -Dbench.label=<label>), never to another game.
# usage: mst-run.sh <label> [launch.py args...]   -> results/raw/<label>.json, results/raw/<label>.trace + GPU summary
ROOT="${0:A:h:h}"; cd "$ROOT"
LABEL=$1; shift
GD=${MST_GD:-run/mst-$LABEL}; mkdir -p $GD; rm -f $GD/launch.log; rm -rf results/raw/$LABEL.trace
python3 tools/launch.py --game-dir $GD --opt fullscreen:true --quiet-gate 4.0:900 --overlay-check --bench results/raw/$LABEL.json \
  --label $LABEL --bench-prop warmup=20 --bench-prop seconds=20 "$@" >/dev/null &
LP=$!
until grep -q "\-> MEASURE" $GD/launch.log 2>/dev/null; do
  if ! kill -0 $LP 2>/dev/null; then echo "launch ended before the measurement phase (see $GD/launch.log)"; exit 1; fi
  sleep 1
done
PID=$(pgrep -f -- "-Dbench.label=$LABEL( |$)" | head -1)
if [[ -z $PID ]]; then
  echo "could not locate this run's game process; no trace captured" >&2
  wait $LP
  exit 1
fi
sleep 3
# MST_XCTRACE_ARGS: extra xctrace record arguments, e.g. "--instrument 'Metal GPU Counters'" (results/GPU-HEAVY.md);
# MST_SECONDS: capture length (default 4).
XCT_EXTRA=(${(z)MST_XCTRACE_ARGS}); XCT_EXTRA=(${(Q)XCT_EXTRA})
TRACE_LOG="results/raw/$LABEL-xctrace.log"
if ! xcrun xctrace record --template 'Metal System Trace' $XCT_EXTRA --attach $PID --time-limit ${MST_SECONDS:-4}s --output "${MST_OUT:-$ROOT/results/raw}/$LABEL.trace" > "$TRACE_LOG" 2>&1; then
  echo "Metal trace capture failed; see $TRACE_LOG (no GPU evidence)" >&2
  wait $LP
  exit 1
fi
if ! xcrun xctrace export --input "${MST_OUT:-$ROOT/results/raw}/$LABEL.trace" --xpath '/trace-toc/run[@number="1"]/data/table[@schema="metal-gpu-intervals"]' > /tmp/$LABEL-gpu.xml 2>> "$TRACE_LOG"; then
  echo "Metal trace export failed; see $TRACE_LOG (no GPU evidence)" >&2
  wait $LP
  exit 1
fi
wait $LP || exit $?
python3 -c "import json; d=json.load(open('results/raw/$LABEL.json')); print('$LABEL', 'median', round(d.get('medianFps',0)), 'p1', round(d.get('p1LowFps',0)))"
python3 tools/mst_gpu.py /tmp/$LABEL-gpu.xml
