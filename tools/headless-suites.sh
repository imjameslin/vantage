#!/bin/zsh
# Every headless suite on build/client.jar, never during a game run (machine-wide GPU lock). Release-candidate matrix
# (CONTRACT.md 16.R5-INT / 16.R5-FIN): shipped defaults first, then the round-5 off switches for the suites whose old path
# would otherwise go untested.
#
# Parallel since round 6 (tools/suite_sched.py): GPU suites one at a time in table order, CPU-only suites in a pool of 3
# next to them, timing-sensitive suites (solo) alone at the end; suites of one group (shared build/ directory, or build ->
# run) in table order. The lock is held in epochs of about a minute and released in between, so queued game runs get in;
# nothing starts while the user's own game is open (as in tools/launch.py). 284 s -> about 120 s of lock time, same
# verdicts (results/SPEEDUPS.md).
#
# usage: tools/headless-suites.sh <outdir> [--serial] [--cpu N] [--gpu-lanes N] [--window S] [--only a,b]
#   -> <outdir>/<name>.txt per suite, <outdir>/summary.tsv (table order), <outdir>/timeline.tsv, exit 1 if any failed
#   --serial: the old behaviour (one suite at a time, lock per suite). Env: SUITES_CPU, SUITES_GPU_LANES, SUITES_WINDOW.
ROOT="${0:A:h:h}"; cd "$ROOT"
OUT="${1:A}"; shift; mkdir -p "$OUT"; : > "$OUT/summary.tsv"
JH="$ROOT/mc/runtime/java-runtime-epsilon/jre.bundle/Contents/Home"
OFF=(-Dmcmetal.lateThrottle=false -Dmcmetal.renameSmallWrites=false -Dmc.fastAnimateTick=false -Dmc.fastTextureAnimation=false
     -Dmc.terrain.pregrow=false)
REG="$OUT/.registry.tsv"; : > "$REG"
t() {  # t <name> <gpu|cpu|solo> <group> <estimated seconds> <command...>: register a suite (kinds: tools/suite_sched.py)
  local name=$1 kind=$2 group=$3 est=$4; shift 4
  print -r -- "$name"$'\t'"$kind"$'\t'"$group"$'\t'"$est"$'\t'"${(j: :)${(q)@}}" >> "$REG"
}
# CPU-only, longest first (they start at once next to the GPU chain). travel-hitch touches the GPU once (one 128 MiB heap
# made resident by a blit, 10 s timeout); the rest of it is CPU-side. Each variant has its own build directory.
t travel-hitch            cpu  travel-hitch     75 zsh tools/travel-hitch-test.sh
t travel-hitch-debug      cpu  travel-hitch-dbg 75 env MTL_DEBUG_LAYER=1 TRAVEL_HITCH_OUT=build/travel-hitch-test-debug zsh tools/travel-hitch-test.sh
t native-tests-build      cpu  native-tests     25 zsh tools/build-native-tests.sh
t animate-tick            cpu  animate-tick     20 zsh tools/animate-tick-test.sh
t late-throttle           cpu  late-throttle     3 zsh tools/late-throttle-test.sh
t i4-encoder              cpu  i4                3 zsh tools/test-i4-encoder.sh
t i4-encoder-off          cpu  i4                3 zsh tools/test-i4-encoder.sh $OFF
t terrain                 cpu  terrain           4 zsh tools/terrain-test.sh
t zoom                    cpu  zoom              2 zsh tools/zoom-test.sh
t client-tick             cpu  client-tick       4 zsh tools/client-tick-test.sh
t client-tick-off         cpu  client-tick       4 zsh tools/client-tick-test.sh -Dmc.fastAnimateTick=false
t soak-verdict            cpu  soak-verdict      2 zsh tools/soak-verdict-test.sh
t ui-transitions          cpu  ui-transitions    6 zsh tools/ui-transitions-test.sh
t gui-layer-index         cpu  gui-layer-index   4 zsh tools/gui-layer-index-test.sh
t gui-layer-index-off     cpu  gui-layer-index   4 zsh tools/gui-layer-index-test.sh -Dmc.gui.layerIndex=false
t perceptual-selftest     cpu  perceptual        1 "$JH/bin/java" tools/PerceptualDiff.java --selftest
t fabric-compat           cpu  fabric-compat     8 zsh tools/fabric-compat-test.sh
# Stage 2 round 1: render-equivalent block-update filter (W11) and the Prism AOT wrapper (W4).
t block-update-filter     cpu  block-update-filter 8 zsh tools/block-update-filter-test.sh
t aot-wrap                cpu  aot-wrap          4 zsh tools/prism/aot-wrap-test.sh
# Stage 3 round 1: LOD data layer (codec, sealing, downsample, store files, level formula).
t lod                     cpu  lod               2 zsh tools/lod-test.sh
t lod-mesh-cache          cpu  lod-mesh-cache    6 zsh tools/lod-mesh-cache-test.sh
# GPU suites, one at a time.
t metal-device            gpu  i3                2 zsh tools/metal-device-test.sh
t metal-device-off        gpu  i3                2 zsh tools/metal-device-test.sh $OFF
t metal-e2e               gpu  e2e               9 zsh tools/metal-e2e-test.sh
t metal-e2e-off           gpu  e2e               4 zsh tools/metal-e2e-test.sh $OFF
t metal-smoke             gpu  smoke             1 zsh tools/metal-smoke.sh
t terrain-e2e             gpu  terrain-e2e       3 zsh tools/terrain-e2e-test.sh
t terrain-e2e-icb         gpu  terrain-e2e       6 zsh tools/terrain-e2e-test.sh --icb-parity
t drawlist-600            gpu  drawlist          2 zsh tools/terrain-drawlist-bench.sh check 600
t drawlist-300-octree     gpu  drawlist          2 zsh tools/terrain-drawlist-bench.sh check 300 visible=octree
t drawlist-300-far        gpu  drawlist          2 zsh tools/terrain-drawlist-bench.sh check 300 visible=far
t terrain-shader          gpu  terrain-shader    2 zsh tools/terrain-shader-test.sh
# Round 12: the cluster path's off switch, and the greedy-meshing prototype (off by default) with its pipelines.
t terrain-shader-clusters-off gpu terrain-shader 2 zsh tools/terrain-shader-test.sh -Dmc.terrain.clusters=false
t terrain-shader-greedy   gpu  terrain-shader    3 zsh tools/terrain-shader-test.sh -Dmc.terrain.greedy=exact -DterrainShaderTest.only=r12
t occlusion-e2e           gpu  occlusion-e2e     3 zsh tools/occlusion-e2e-test.sh
t i5-pipeline             gpu  i5                5 zsh tools/i5-pipeline-test.sh
t core-msl                gpu  core-msl          2 zsh tools/core-msl-test.sh
t atlas-anim              gpu  atlas-anim       20 zsh tools/atlas-anim-test.sh
t lightmap-compute        gpu  lightmap-compute 12 zsh tools/lightmap-compute-test.sh
t layer-flip-probe        gpu  layer-flip        6 zsh tools/layer-flip-probe.sh
# Solo (nothing else of ours running, as before): native-tests' frametrace_test reads two clocks 100 us apart and checks
# GPU intervals against the submit call; bench-hook checks thread CPU time >= wall time / 4 for spinning frames; frame-trace
# checks GPU / commit timestamps at 200 us slack; terrain-icb-probe prints GPU and CPU timings (a benchmark).
t native-tests            solo native-tests      8 env MTL_DEBUG_LAYER=1 zsh tools/build-native-tests.sh --run --no-build
t bench-hook              solo bench-hook        2 zsh tools/bench-hook-test.sh
t frame-trace             solo frame-trace       3 zsh tools/frame-trace-test.sh
t terrain-icb-probe       solo terrain-icb      20 env MTL_DEBUG_LAYER=1 zsh tools/terrain-icb-probe.sh
python3 tools/suite_sched.py "$REG" --out "$OUT" --root "$ROOT" "$@"
rc=$?
echo "headless suites: $([[ $rc == 0 ]] && echo ALL PASSED || echo FAILURES) ($OUT/summary.tsv)"
exit $rc
