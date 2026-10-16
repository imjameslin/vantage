#!/bin/zsh
# Stage 3 r6: labelled photos for the user (MAIN/share/lod6). Reads a list of "<source png>|<dest name>|<caption>" lines.
# usage: tools/lod/s3r6-share.sh <list file>
ROOT="${0:A:h:h:h}"; cd "$ROOT"
MAIN="${ROOT:h:h:h}"
OUT="$MAIN/share/lod6"
mkdir -p "$OUT"
JH=mc/runtime/java-runtime-epsilon/jre.bundle/Contents/Home
while IFS='|' read -r src dst cap; do
  [[ -z $src || $src == \#* ]] && continue
  if [[ -f $src ]]; then
    $JH/bin/java tools/LabelImage.java "$src" "$OUT/$dst" "$cap" && echo "$OUT/$dst"
  else
    echo "missing $src"
  fi
done < "$1"
