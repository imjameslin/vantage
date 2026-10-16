#!/bin/zsh
# Frame-trace tests (docs/metal/CONTRACT.md 16.R5-B), headless, no window:
#  1. tools/frame_trace_test.py: the analyzer on synthetic Metal- and vanilla-shaped results (attribution, cadences).
#  2. tools/FrameTraceTest.java: the real Metal backend from build/client.jar with -Dmcmetal.frameTrace=true, driven by
#     the bench's FrameTrace (build/bench-classes): native ring, counters, GPU merge in the System.nanoTime base.
#  3. tools/frame_trace.py on the JSON that test wrote, plus checks of its config block.
# Run tools/build-client.sh and tools/build-bench.sh first. Metal API validation on (MTL_DEBUG_LAYER=1).
set -e
ROOT="${0:A:h:h}"
JH="$ROOT/mc/runtime/java-runtime-epsilon/jre.bundle/Contents/Home"
python3 "$ROOT/tools/frame_trace_test.py"
if [[ ! -f "$ROOT/build/client.jar" || ! -d "$ROOT/build/bench-classes" ]]; then
  echo "missing build/client.jar or build/bench-classes: run tools/build-client.sh and tools/build-bench.sh first" >&2
  exit 2
fi
CP="$ROOT/build/client.jar:$ROOT/build/bench-classes"
for j in ${(f)"$(find "$ROOT/mc/libraries" -name '*.jar' | sort)"}; do
  if [[ "$j" == *natives-macos.jar || "$j" == *osx-x86_64.jar ]]; then continue; fi
  CP="$CP:$j"
done
OUT="$ROOT/build/frametrace-e2e.json"
rm -f "$OUT"
mkdir -p "$ROOT/build/frametrace-natives" "$ROOT/build/frametrace-run"
cd "$ROOT/build/frametrace-run"   # log4j writes logs/ relative to the working directory
MTL_DEBUG_LAYER=${MTL_DEBUG_LAYER:-1} "$JH/bin/java" --enable-native-access=ALL-UNNAMED -XX:+UseZGC -ea \
  -Dmcmetal.frameTrace=true -Dbench.frameTrace=true -Dbench.frameTrace.jfrSlowMs=0.25 \
  -Dorg.lwjgl.system.SharedLibraryExtractPath="$ROOT/build/frametrace-natives" \
  -cp "$CP" "$ROOT/tools/FrameTraceTest.java" "$OUT"
python3 "$ROOT/tools/frame_trace.py" "$OUT" --top 10 > "$ROOT/build/frametrace-e2e.txt"
python3 - "$OUT" "$ROOT/tools" <<'PY'
import json, sys
sys.path.insert(0, sys.argv[2])
import frame_trace
result = json.load(open(sys.argv[1]))
cfg = result["config"]
s = frame_trace.summarize(frame_trace.Trace(result))[0]
problems = []
if cfg["measured"]["counters"].get("frames") != 300: problems.append("measured frames %s" % cfg["measured"]["counters"].get("frames"))
if not isinstance(cfg["native"].get("fuseMode"), int): problems.append("native fuseMode")
if "MetalDevice" not in cfg["backendClass"]: problems.append("backendClass %s" % cfg["backendClass"])
if cfg["toggles"].get("MetalTimeline", {}).get("FRAMES_IN_FLIGHT") != 2: problems.append("toggles MetalTimeline.FRAMES_IN_FLIGHT")
if "TerrainShaderVariants" not in cfg["toggles"]: problems.append("toggles TerrainShaderVariants")
if cfg["properties"].get("mcmetal.frameTrace") != "true": problems.append("properties")
if "native ring" not in result["frameTrace"]["metal"]["status"]: problems.append("metal status")
if s["gpuCadence"]["n"] < 290 or not s["metal"]: problems.append("gpu cadence n %d" % s["gpuCadence"]["n"])
if s["tickFrames"]["n"] != 30: problems.append("tick frames %d" % s["tickFrames"]["n"])
clock = result["frameTrace"]["metal"]["clock"]
# HotSpot's System.nanoTime() on macOS is mach_absolute_time in ns, the same clock as CLOCK_UPTIME_RAW and
# CACurrentMediaTime: the measured offsets must be ~0 (the conversion stays correct either way).
if abs(clock["javaMinusUptimeNs"]) > 1_000_000 or abs(clock["javaMinusMediaNs"]) > 1_000_000:
    problems.append("clock offset %s" % clock)
print("frame_trace.py on the Metal e2e JSON:", "ok" if not problems else "FAILED %s" % problems,
      "| GPU cadence p50 %.3f ms, busy p50 %.3f ms, CPU p50 %.3f ms" % (s["gpuCadence"]["p50Ms"], s["gpuBusy"]["p50Ms"], s["cpu"]["p50Ms"]))
sys.exit(1 if problems else 0)
PY
echo "analyzer report: $ROOT/build/frametrace-e2e.txt"
