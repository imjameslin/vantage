#!/bin/zsh
# Headless test of the bench agent's hooks (tools/BenchHookTest.java): the zoomseq lockstep hook (transforms vanilla
# DeltaTracker$Timer, verifies and drives it) and the frame-trace probes (CONTRACT.md 16.R5-B: transforms vanilla and,
# if built, the patched Minecraft class, verifies them; drives FrameProbe + FrameTrace like the game loop, checks zero
# allocation per frame and feeds the resulting JSON to tools/frame_trace.py). No GPU, window or game instance.
# Run tools/build-bench.sh first (and tools/build-client.sh to include the patched class).
set -e
ROOT="${0:A:h:h}"
JH="$ROOT/mc/runtime/java-runtime-epsilon/jre.bundle/Contents/Home"
VJAR="$ROOT/mc/versions/26.3/26.3.jar"
if [[ ! -d "$ROOT/build/bench-classes" ]]; then
  echo "missing build/bench-classes: run tools/build-bench.sh first" >&2
  exit 2
fi
CP="$ROOT/build/bench-classes:$VJAR"
for j in ${(f)"$(find "$ROOT/mc/libraries" -name '*.jar' ! -name '*natives*' | sort)"}; do CP="$CP:$j"; done
OUT="$ROOT/build/bench-hook-selftest.json"
rm -f "$OUT"
"$JH/bin/java" -Dbench.frameTrace=true -Dbench.frameTrace.jfrSlowMs=0.5 -cp "$CP" "$ROOT/tools/BenchHookTest.java" "$VJAR" "$ROOT/build/client.jar" "$OUT"
python3 "$ROOT/tools/frame_trace.py" "$OUT" --top 12 --bin-ms 0.1 > "$ROOT/build/bench-hook-selftest.txt"
python3 - "$OUT" "$ROOT/tools" <<'PY'
import json, sys
sys.path.insert(0, sys.argv[2])
import frame_trace
summary = json.loads(json.dumps(frame_trace.summarize(frame_trace.Trace(json.load(open(sys.argv[1]))))[0]))
ok = summary["frames"] == 5400 and summary["tickFrames"]["n"] == 675 and not summary["metal"]
ok &= summary["slowest1"]["attribution"]["render"] >= 4   # the four 1.2 ms render hitches of the first 400 frames
print("frame_trace.py on the selftest JSON:", "ok" if ok else "FAILED", summary["slowest1"])
sys.exit(0 if ok else 1)
PY
echo "analyzer report: $ROOT/build/bench-hook-selftest.txt"
