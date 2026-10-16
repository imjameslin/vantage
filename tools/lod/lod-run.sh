#!/bin/zsh
# Stage 3 LOD runs (docs/stage3/LOD-DESIGN.md §8, results/STAGE3-R1.md) in the fixture world run/s3-lod (a copy of the bench
# world generated out to 256 chunks with `mode=lodpregen`). Static options, fullscreen, RD 12, timing lane (launch.py).
#
# usage: tools/lod/lod-run.sh <kind> <label> <client jar> <bench jar> [launch.py args...]
#   kind = abab    in-session A/B of Lod#draw (false = vanilla image at RD 12 | true = RD 12 + LOD), -Dmc.lod=true
#          perf    plain spin benchmark (pass --jvm=-Dmc.lod=true for LOD, nothing for the no-LOD baseline)
#          photo   3 FPS-line photos into run/s3-shots/<label> (pass bench props: yaw / pos / pitch / zoom / ...)
# Extra args go to launch.py (e.g. --jvm=-Dmc.lod.radius=128 --jvm=-Dmc.lod.draw=false).
ROOT="${0:A:h:h:h}"; cd "$ROOT"
KIND=$1; L=$2; J=$3; B=$4; shift 4
S=(); while IFS= read -r l; do S+=(--opt "$l"); done < tools/static-options.txt
# Image-only gates do not claim FPS. Retain the GPU/game-directory locks and
# LOD settling, but avoid a performance quiet wait for every screenshot launch.
QUIET=(--quiet-gate 4.0:900)
if [[ $KIND == photo && ${LOD_VISUAL_ONLY:-0} == 1 ]]; then QUIET=(); fi
COMMON=(--jar $J --game-dir ${LOD_GAME_DIR:-run/s3-lod} --api metal --opt fullscreen:true $S --bench results/raw/$L.json --label $L --bench-jar $B
        $QUIET --overlay-check --overlay-wait 900 --bench-prop timeout=2400)
rm -f results/raw/$L.json
case $KIND in
  abab)
    python3 tools/launch.py $COMMON --jvm=-Dmc.lod=true --bench-prop mode=abab \
      --bench-prop "abab.toggles=${LOD_TOGGLES:-net.minecraft.client.renderer.chunk.Lod#draw=false|true}" "$@" > results/raw/$L.out 2>&1
    python3 tools/abab.py results/raw/$L.json | head -40 ;;
  perf)
    python3 tools/launch.py $COMMON --bench-prop warmup=15 --bench-prop seconds=30 "$@" > results/raw/$L.out 2>&1 ;;
  zoomseq)   # round 3: zoom / spyglass frame sequence (bench ZoomSeq) into run/s3-shots/<label>; use LOD_GAME_DIR=run/s3-lod-scenes
    mkdir -p run/s3-shots/$L
    python3 tools/launch.py $COMMON --bench-prop mode=zoomseq --bench-prop shots="$PWD/run/s3-shots/$L" "$@" > results/raw/$L.out 2>&1 ;;
  photo)
    mkdir -p run/s3-shots/$L
    python3 tools/launch.py $COMMON --bench-prop photo=3 --bench-prop photo.every=4 --bench-prop f3=fps --bench-prop spin=1000000 \
      --bench-prop warmup=8 --bench-prop seconds=14 --bench-prop shots="$PWD/run/s3-shots/$L" "$@" > results/raw/$L.out 2>&1 ;;
esac
python3 - results/raw/$L.json <<'PY'
import json, sys
try:
    d = json.load(open(sys.argv[1]))
except Exception as e:
    print(sys.argv[1], "no result", e); sys.exit(0)
disp = d.get("launch", {}).get("display", {})
lod = d.get("lod") or {}
st = lod.get("state") or {}
g = st.get("gpu") or {}
print(sys.argv[1], "median %.0f 1%%low %.0f" % (d.get("medianFps", 0), d.get("p1LowFps", 0)), "display", disp.get("pixels"), disp.get("hz"),
      "default", disp.get("default"), "| settle %.0f s" % d.get("lodSettleSeconds", 0), "| lod gpu %s MB, drawn quads %s, selected %s %s" %
      (g.get("gpuMB"), g.get("drawnQuads"), st.get("selected"), st.get("selectedPerLevel")), "| memory", d.get("memory"))
PY
