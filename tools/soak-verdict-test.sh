#!/bin/zsh
# Headless test of the soak verdict (tools/SoakVerdictTest.java): compiles bench/src/bench/SoakVerdict.java ALONE with the
# test (so the verdict provably needs no game class) and runs synthetic 30-minute runs through it. Also checks
# tools/soak_report.py on a synthetic result. No GPU, window or game instance.
set -e
ROOT="${0:A:h:h}"
JH="$ROOT/mc/runtime/java-runtime-epsilon/jre.bundle/Contents/Home"
OUT="$ROOT/build/soak-verdict-test"
rm -rf "$OUT"; mkdir -p "$OUT"
"$JH/bin/javac" --release 25 -d "$OUT" "$ROOT/bench/src/bench/SoakVerdict.java" "$ROOT/tools/SoakVerdictTest.java"
"$JH/bin/java" -cp "$OUT" bench.SoakVerdictTest
python3 - "$ROOT" "$OUT" <<'PY'
import json, subprocess, sys
root, out = sys.argv[1], sys.argv[2]
base = {"label": "synthetic", "mode": "soak", "status": "complete", "backend": "Metal", "server": "integrated (singleplayer)",
        "renderDistance": 16, "elapsedSeconds": 1800, "plannedSeconds": 1800, "frames": 900000, "fullscreenToggles": 15,
        "resizeBursts": 45, "nativeMem": "rusage ok", "events": [], "liveHeap": {"t": [300, 1800], "mb": [900, 910], "gcMs": [100, 100]},
        "series": {k: [0, 10] for k in ["t", "fs", "trans", "fbW", "fbH", "fps", "maxMs", "heapAfterGcMB", "metalMB",
                                        "nativeFootprintMB", "footprintMB", "rssMB", "chunks", "entities"]}}
base["series"]["seg"] = ["east", "east"]
def run(verdict, extra=()):
    d = dict(base, verdict=verdict)
    p = f"{out}/r.json"
    json.dump(d, open(p, "w"))
    return subprocess.run(["python3", f"{root}/tools/soak_report.py", p, *extra], capture_output=True, text=True).returncode
ok = {"pass": True, "conclusive": True, "reasons": [], "metrics": {}, "info": {}}
short = {"pass": True, "conclusive": False, "reasons": ["inconclusive: trend span 2.0 min < 15 min"], "metrics": {}, "info": {}}
m = {"metalMB": {"pass": False, "slopeMBperMin": 9, "maxSlope": 4, "deltaMB": 300, "maxDelta": 256, "noiseMB": 1, "points": 20, "first": 1, "last": 2}}
bad = {"pass": False, "conclusive": True, "reasons": ["metalMB: slope 9.00 MB/min (max 4.0), delta 300.0 MB (max 256)"], "metrics": m, "info": {}}
shortbad = {"pass": False, "conclusive": False, "reasons": ["metalMB: slope 9.00 MB/min (max 4.0), delta 300.0 MB (max 256)",
            "inconclusive: trend span 2.0 min < 15 min"], "metrics": m, "info": {}}
crashed = {"pass": False, "conclusive": False, "reasons": ["status failed: render thread stalled for 130 s",
           "inconclusive: trend span 2.0 min < 15 min"], "metrics": m, "info": {}}
res = [run(ok) == 0, run(short) == 2, run(bad) == 1, run(shortbad) == 2, run(crashed) == 1, run(ok, ["--exit-code", "1"]) == 1,
       run(ok, ["--host", f"{out}/missing-host.json"]) == 1]
print("soak_report.py exit codes (pass 0, inconclusive 2, fail 1, short+memory 2, short+stall 1, game exit 1, missing host 1):",
      "ok" if all(res) else f"FAILED {res}")
sys.exit(0 if all(res) else 1)
PY
echo "SOAK VERDICT TEST PASSED"
