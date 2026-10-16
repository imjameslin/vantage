#!/bin/zsh
# Stage-2 W0b chunk-load benchmark (bench mode=join, bench/README.md "Chunk pipeline"): join the pinned pre-generated world
# "join" (seed 4242, generated to render distance 32 around its spawn: run/pregen/join-s4242-26.3-rd32, made by
# tools/bench-join.sh --make once) with the static options, fullscreen, render distance $RD (default 32), hold the view, and
# time level join -> all built (loaded chunk count stable, compile queue empty, no visible section waiting, for 1 s).
# The server loads the chunks from disk (no world generation), so the client's chunk pipeline sets the pace.
# Timing lane (exclusive) with the quiet / overlay gates.
# usage: bench-join.sh <label> <gamedir> [launch.py args...]      (RD=12 tools/bench-join.sh ... for render distance 12)
ROOT="${0:A:h:h}"; cd "$ROOT"
SNAP="$ROOT/run/pregen/join-s4242-26.3-rd32"
LABEL=$1; GD=$2; shift 2
python3 tools/pin.py --verify "$SNAP" >/dev/null || { echo "bench-join.sh: pinned world $SNAP missing or changed"; exit 1; }
rm -f "results/raw/$LABEL.json"
STATIC=(); while IFS= read -r line; do STATIC+=(--opt "$line"); done < "$ROOT/tools/static-options.txt"
python3 tools/launch.py --game-dir "$GD" --opt fullscreen:true $STATIC --opt renderDistance:${RD:-32} --quiet-gate 4.0:900 --overlay-check \
  --world-snapshot "$SNAP" --world-only --bench results/raw/$LABEL.json --label $LABEL \
  --bench-prop mode=join --bench-prop world=join "$@" >/dev/null
python3 - "results/raw/$LABEL.json" "$LABEL" <<'PY'
import json, sys
d = json.load(open(sys.argv[1]))
if "chunks" not in d:
    print(f"{sys.argv[2]:22s} FAILED {d.get('error', '')}"); sys.exit(1)
j = d["chunks"]["join"]; t = j.get("toBuilt", {})
disp = d.get("launch", {}).get("display", {})
print(f"{sys.argv[2]:22s} rd {d['renderDistance']:.0f} allBuilt {j['allBuiltS']:6.2f} s  chunksDone {j['chunksDoneS']:6.2f} s  chunks {j['loadedChunks']}"
      f"  compiles {j.get('compilesToBuilt', 0)} ({j.get('compilesPerS', 0):.0f}/s, {t.get('compileUsMean', 0):.0f} us)  maxFrame {j['maxFrameMs']:.1f} ms"
      f"  join {d.get('secondsToJoin', 0):.1f} s  display {disp.get('pixels')}@{disp.get('hz')} default {disp.get('default')}")
PY
