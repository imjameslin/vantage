#!/bin/zsh
# Stage 3 r6: Metal System Trace of the LOD in the fixture world (tools/mst-run.sh: vertex / fragment GPU time per encoder label),
# spin benchmark, static options, raster LOD at Voxy's detail unless the extra args say otherwise.
# usage: tools/lod/s3r6-mst.sh <label> <client jar> <bench jar> [launch.py args...]
ROOT="${0:A:h:h:h}"; cd "$ROOT"
L=$1; J=$2; B=$3; shift 3
S=(); while IFS= read -r l; do S+=(--opt "$l"); done < tools/static-options.txt
MST_GD=run/s3-lod zsh tools/mst-run.sh $L --jar $J --bench-jar $B --api metal $S --bench-prop timeout=2400 \
  --bench-prop warmup=15 --bench-prop seconds=20 "$@"
