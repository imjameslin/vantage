#!/bin/zsh
# Stage 3 r2 temporal (popping) check on a zoomseq capture: perceptual difference between consecutive saved frames of each
# segment (frames 2 apart = 1 tick at fpt 2). After the FOV settles (tick >= 12) the camera is still, so any difference
# is LOD refinement (pop or dissolve); during the animation the FOV change dominates.
# usage: tools/lod/zoomseq-pops.sh <zoomseq dir> <out dir>
ROOT="${0:A:h:h:h}"; cd "$ROOT"
Z=$1; T=$2
rm -rf "$T"; mkdir -p "$T/a" "$T/b"
for seg in spy_in spy_out zoom_in zoom_out; do
  for i in 0 2 4 6 8 10 12 14 16 18 20 22 24 26 28; do
    a=$(printf '%03d' $i); b=$(printf '%03d' $((i + 2)))
    [[ -f $Z/${seg}_$a.png && -f $Z/${seg}_$b.png ]] || continue
    cp $Z/${seg}_$a.png "$T/a/${seg}_$a-$b.png"
    cp $Z/${seg}_$b.png "$T/b/${seg}_$a-$b.png"
  done
done
mc/runtime/java-runtime-epsilon/jre.bundle/Contents/Home/bin/java tools/PerceptualDiff.java "$T/a" "$T/b" | tee "$T/pops.txt"
