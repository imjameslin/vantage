#!/bin/zsh
# Stage 3 r3 (seed-based LOD generation, results/STAGE3-R3.md): generated vs real far terrain at the same view.
#   ref  = RD 12 + LOD from the real store (the fixture world run/s3-lod is generated out to 256 chunks)
#   cand = RD 12 + LOD from the seed generator only (-Dmc.lod.genOnly=true: real data only for the chunks the client loaded)
# Clouds off, LOD fog on (the image the player sees), radius R, tau as given; crops without FPS line / hotbar / hand;
# tools/PerceptualDiff.java compares cand vs ref per view. REUSE_REF=1 keeps existing reference shots.
# usage: tools/lod/gen-compare.sh <client jar> <bench jar> <out dir> <R chunks> <tau> [extra args for the generated run...]
ROOT="${0:A:h:h:h}"; cd "$ROOT"
J=$1; B=$2; OUT=$3; R=$4; TAU=$5; shift 5
mkdir -p "$OUT/ref" "$OUT/cand" "$OUT/full"
NOCLOUD=(--opt 'renderClouds:"false"')
VISTA=(--bench-prop pos=6.5,180,6.5 --bench-prop pitch=18 --bench-prop yaw=200)
FAR=(--bench-prop pos=6.5,180,6.5 --bench-prop pitch=8 --bench-prop yaw=110)
LODC=(--jvm=-Dmc.lod=true --jvm=-Dmc.lod.radius=$R --jvm=-Dmc.lod.tau=$TAU)
shot() {   # label, kind (ref|cand), view name, args...
  local L=$1 K=$2 V=$3; shift 3
  if [[ $K == ref && -n "$REUSE_REF" && -f "$OUT/$K/$V.png" ]]; then echo "reusing $OUT/$K/$V.png"; return; fi
  zsh tools/lod/lod-run.sh photo $L $J $B $NOCLOUD "$@"
  local src=run/s3-shots/$L/screenshots/photo-$L-2.png
  [[ -f $src ]] || { echo "no photo for $L"; return; }
  cp $src "$OUT/full/$L.png"
  sips -c 1300 2200 --cropOffset 60 0 $src --out "$OUT/$K/$V.png" > /dev/null
}
shot gc-ref-vista-r$R ref vista $VISTA $LODC
shot gc-cand-vista-r$R cand vista $VISTA $LODC --jvm=-Dmc.lod.genOnly=true "$@"
shot gc-ref-far-r$R ref far $FAR $LODC
shot gc-cand-far-r$R cand far $FAR $LODC --jvm=-Dmc.lod.genOnly=true "$@"
JH=mc/runtime/java-runtime-epsilon/jre.bundle/Contents/Home
$JH/bin/java tools/PerceptualDiff.java "$OUT/ref" "$OUT/cand" | tee "$OUT/percept.txt"
echo "gen-compare done"
