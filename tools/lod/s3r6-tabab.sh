#!/bin/zsh
# Stage 3 r6 (results/STAGE3-R6.md): in-session A/B while FLYING (bench abab.preset=travel: the travel flight east, 10.9 blocks/s,
# 3 blocks above the surface), in the pinned pre-generated travel world (tools/pregen-travel.sh) with the travel options (Fancy,
# RD 16), fullscreen, timing lane. Blocks of 5 s (12 blocks = 6 ABBA pairs, 24 segments: arms interleave every 0.21 s), so
# both arms see the same streaming load (the flight leaves the pre-generated strip after ~55 s; both arms meet the same generation).
# usage: tools/lod/s3r6-tabab.sh <label> <client jar> <bench jar> <toggles> [launch.py args...]
#   toggles: fqcn#field=a|b[,...], e.g. net.minecraft.client.renderer.chunk.LodWave#use=false|true
ROOT="${0:A:h:h:h}"; cd "$ROOT"
L=$1; J=$2; B=$3; T=$4; shift 4
SNAP=$(zsh "$ROOT/tools/pregen-travel.sh") || { echo "no pre-generated world"; exit 1; }
FANCY=(); while IFS= read -r line; do FANCY+=(--opt "$line"); done < "$ROOT/tools/travel-options.txt"
rm -f results/raw/$L.json
python3 tools/launch.py --jar $J --bench-jar $B --api metal --game-dir ${TABAB_DIR:-run/r3q-travel} --opt fullscreen:true $FANCY \
  --quiet-gate 4.0:900 --overlay-check --world-snapshot "$SNAP" --world-only \
  --bench results/raw/$L.json --label $L --bench-prop world=travel --bench-prop mode=abab --bench-prop abab.preset=travel \
  --bench-prop abab.blockSeconds=${TABAB_BLOCK:-5} --bench-prop abab.blocks=${TABAB_BLOCKS:-12} --bench-prop warmup=10 \
  --bench-prop timeout=2400 --bench-prop "abab.toggles=$T" "$@" > results/raw/$L.out 2>&1
python3 tools/abab.py results/raw/$L.json | head -30
python3 - results/raw/$L.json <<'PY'
import json, sys
try:
    d = json.load(open(sys.argv[1]))
except Exception as e:
    print(sys.argv[1], "no result", e); sys.exit(0)
disp = d.get("launch", {}).get("display", {})
st = (d.get("lod") or {}).get("state") or {}
w = (st.get("gpu") or {}).get("wave")
print(sys.argv[1], "display", disp.get("pixels"), disp.get("hz"), "default", disp.get("default"), "fb", d.get("framebuffer"),
      "| settle %.0f s" % d.get("lodSettleSeconds", 0), "| wave", json.dumps(w))
PY
