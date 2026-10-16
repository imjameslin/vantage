#!/bin/zsh
# Stage 3 r6 scratch queue: runs the lines of a queue file (one tools/ command per line) in order; crops taiga photos.
# usage: tools/lod/s3r6-q.sh <queue file>
ROOT="${0:A:h:h:h}"; cd "$ROOT"
while IFS= read -r line; do
  [[ -z $line || $line == \#* ]] && continue
  echo ">> $line"
  eval "$line"
done < "$1"
cd results/raw/r6shots 2>/dev/null && for f in taiga-*.png; do
  [[ $f == crop-* || -f crop-$f ]] && continue
  sips -c 300 900 --cropOffset 880 700 $f --out crop-$f >/dev/null; sips -Z 1800 crop-$f >/dev/null
done
echo "queue done"
