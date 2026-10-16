#!/bin/zsh
# Run only after main signals FPS timing is idle: zsh tools/reuse-sequence-opaque-test.sh --timing-idle
set -euo pipefail
[[ "${1:-}" == --timing-idle ]] || { print -u2 'Wait for main to signal FPS timing idle; then pass --timing-idle.'; exit 2; }
ulimit -t 60
ROOT="${0:A:h:h}"
JH="${REUSE_SEQUENCE_JAVA_HOME:-$ROOT/mc/runtime/java-runtime-epsilon/jre.bundle/Contents/Home}"
CLIENT="$ROOT/build/client-async-opaque-prototype.jar"
[[ -r "$CLIENT" ]] || { print -u2 "Frozen client missing: $CLIENT"; exit 1; }
OUT="$(mktemp -d "${TMPDIR:-/tmp}/reuse-sequence-opaque.XXXXXX")"
trap 'rm -rf "$OUT"' EXIT
DEPS="$ROOT/build/bench-classes"
for LIB in "$ROOT"/mc/libraries/**/*.jar; do DEPS="$DEPS:$LIB"; done
mkdir -p "$OUT/classes" "$OUT/empty"
cd "$OUT"
shasum -a 256 "$CLIENT"
"$JH/bin/javac" -J-Xmx256m -J-XX:ActiveProcessorCount=2 -J-XX:+UseSerialGC \
    -J-XX:MaxMetaspaceSize=128m -J-XX:ReservedCodeCacheSize=64m --release 25 -proc:none -implicit:none \
    -sourcepath "$OUT/empty" -cp "$CLIENT:$DEPS" -d "$OUT/classes" \
    "$ROOT/bench/src/bench/CaptureWriter.java" "$ROOT/bench/src/bench/LodBoundaryProbe.java" \
    "$ROOT/bench/src/bench/ReuseSequence.java" "$ROOT/bench/src/bench/Photo.java" \
    "$ROOT/tools/ReuseSequenceOpaqueTest.java"
ARGS=(-Xmx128m -XX:ActiveProcessorCount=2 -XX:+UseSerialGC -XX:MaxMetaspaceSize=128m \
    -XX:ReservedCodeCacheSize=64m -XX:MaxDirectMemorySize=32m -ea -Djava.awt.headless=true \
    -Djava.library.path="$OUT/empty" -Dopaque.testClientJar="$CLIENT" -cp "$OUT/classes:$CLIENT:$DEPS" \
    -Dbench.reuseFeature=opaque -Dbench.f3=off -Dbench.photo=0 -Dbench.zoom=0 \
    -Dmc.lod.asyncOpaque=true -Dmc.lod.asyncItems=true -Dmc.lod.cacheItems=true -Dmc.lod.cacheGroups=true \
    -Dmc.lod.atomicMeshHandoff=true -Dmc.lod.nearHandoff=true -Dmc.lod.cacheNodeItems=false \
    -Dmc.lod.cacheGroups.diagnostics=false -Dmc.lod.itemBuildProfile=false -Dmc.lod.prepareProfile=false \
    -Dmc.lod.diag= -Dmc.lod.async=false -Dmc.lod.reuse=false -Dbench.reuseBoundaryProbe=false)
"$JH/bin/java" "${ARGS[@]}" bench.ReuseSequenceOpaqueTest normal "$OUT/motion"
"$JH/bin/java" "${ARGS[@]}" -Dmc.lod.prepareProfile=true bench.ReuseSequenceOpaqueTest bad-profile
"$JH/bin/java" "${ARGS[@]}" bench.ReuseSequenceOpaqueTest photo
for VALUE in true false invalid; do
    "$JH/bin/java" "${ARGS[@]}" "-Dbench.photo.lodOpaque=$VALUE" bench.ReuseSequenceOpaqueTest photo
done
"$JH/bin/java" "${ARGS[@]}" -cp "$OUT/classes:$ROOT/mc/versions/26.3/26.3.jar:$DEPS" \
    bench.ReuseSequenceOpaqueTest vanilla
