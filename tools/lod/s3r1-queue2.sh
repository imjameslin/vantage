#!/bin/zsh
# Stage 3 round 1, second queue (results/STAGE3-R1.md §2-§4): no-LOD baseline (FPS + memory), LOD perf runs for memory,
# and the cost attribution of the LOD frame (Lod#layerMask: 7 = all layers, 0 = cull kernel + fog / far plane only,
# 5 = without CUTOUT). usage: tools/lod/s3r1-queue2.sh <client jar> <bench jar>
ROOT="${0:A:h:h:h}"; cd "$ROOT"
J=$1; B=$2
zsh tools/lod/lod-run.sh perf s3perf-nolod $J $B
zsh tools/lod/lod-run.sh perf s3perf-r256-t8 $J $B --jvm=-Dmc.lod=true --jvm=-Dmc.lod.radius=256 --jvm=-Dmc.lod.tau=8
zsh tools/lod/lod-run.sh abab s3ab-r64-t8-mask0 $J $B --jvm=-Dmc.lod=true --jvm=-Dmc.lod.radius=64 --jvm=-Dmc.lod.tau=8 \
  --bench-prop 'abab.toggles=net.minecraft.client.renderer.chunk.Lod#layerMask=7|0'
zsh tools/lod/lod-run.sh abab s3ab-r64-t8-mask5 $J $B --jvm=-Dmc.lod=true --jvm=-Dmc.lod.radius=64 --jvm=-Dmc.lod.tau=8 \
  --bench-prop 'abab.toggles=net.minecraft.client.renderer.chunk.Lod#layerMask=7|5'
echo "queue2 done"
