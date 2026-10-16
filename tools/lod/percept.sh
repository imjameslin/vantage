#!/bin/zsh
# Stage 3 r2 perceptual check (GOAL visual bar (a), docs/stage3/LOD-DESIGN.md §8): real terrain vs LOD at the same distance.
#   ref  = vanilla image with real chunks at render distance R (no LOD)
#   cand = render distance 12 + LOD out to R chunks, LOD fog off (= vanilla's fog at render distance R, the same as ref)
# Clouds off in both (they drift between runs). Views: vista (y 180, pitch 18, yaw 200) and spot (yaw 200); tau as given.
# Crops (sips) remove the FPS line, hotbar and hand; tools/PerceptualDiff.java compares ref vs cand per view.
# usage: tools/lod/percept.sh <client jar> <bench jar> <out dir> <R chunks> <tau> [extra LOD args...]
ROOT="${0:A:h:h:h}"; cd "$ROOT"
J=$1; B=$2; OUT=$3; R=$4; TAU=$5; shift 5
mkdir -p "$OUT/ref" "$OUT/cand" "$OUT/full"
NOCLOUD=(--opt 'renderClouds:"false"')
VISTA=(--bench-prop pos=6.5,180,6.5 --bench-prop pitch=18 --bench-prop yaw=200)
SPOT=(--bench-prop yaw=200)
LODC=(--jvm=-Dmc.lod=true --jvm=-Dmc.lod.radius=$R --jvm=-Dmc.lod.tau=$TAU --jvm=-Dmc.lod.fog=false "$@")
shot() {   # label, kind (ref|cand), view name, args...
  local L=$1 K=$2 V=$3; shift 3
  # Round 3: REUSE_REF=1 keeps reference shots already in <out dir>/ref and only shoots the candidates.
  if [[ $K == ref && -n "$REUSE_REF" && -f "$OUT/$K/$V.png" ]]; then echo "reusing $OUT/$K/$V.png"; return; fi
  zsh tools/lod/lod-run.sh photo $L $J $B $NOCLOUD "$@"
  local src=run/s3-shots/$L/screenshots/photo-$L-2.png
  [[ -f $src ]] || { echo "no photo for $L"; return; }
  cp $src "$OUT/full/$L.png"
  # 3024x1964: keep rows 60..1360 and columns 0..2200 (no FPS line, hotbar or hand).
  sips -c 1300 2200 --cropOffset 60 0 $src --out "$OUT/$K/$V.png" > /dev/null
}
shot pc-ref-vista-r$R ref vista $VISTA --opt renderDistance:$R
shot pc-cand-vista-r$R-t$TAU cand vista $VISTA $LODC
shot pc-ref-spot-r$R ref spot $SPOT --opt renderDistance:$R
shot pc-cand-spot-r$R-t$TAU cand spot $SPOT $LODC
JH=mc/runtime/java-runtime-epsilon/jre.bundle/Contents/Home
$JH/bin/java tools/PerceptualDiff.java "$OUT/ref" "$OUT/cand" | tee "$OUT/percept.txt"
echo "percept done"
