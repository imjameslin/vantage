#!/bin/zsh
# Stage 3 r6: puts the README into MAIN/share/lod6 and marks the folder DONE.
ROOT="${0:A:h:h:h}"; cd "$ROOT"
OUT="${ROOT:h:h:h}/share/lod6"
mkdir -p "$OUT"
cp results/lod6-share-README.md "$OUT/README.md"
date > "$OUT/DONE"
ls "$OUT"
