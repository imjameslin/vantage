#!/bin/zsh
# CPU-only focused harness tests. Compile sequence/tests and capture helpers in temp;
# consume existing bench/client classes without modifying shared build outputs.
set -euo pipefail
ulimit -t 60 # Per-process CPU seconds; one compiler and one test JVM at a time.
ROOT="${0:A:h:h}"
JH="${REUSE_SEQUENCE_JAVA_HOME:-$ROOT/mc/runtime/java-runtime-epsilon/jre.bundle/Contents/Home}"
CLIENT="${REUSE_SEQUENCE_CLIENT_JAR:-$ROOT/build/client-async-items-a.jar}"
CLIENT="${CLIENT:A}"
[[ -r "$CLIENT" ]] || { print -u2 "Frozen production client jar missing: $CLIENT"; exit 1; }
OUT="$(mktemp -d "${TMPDIR:-/tmp}/reuse-sequence-items.XXXXXX")"
trap 'rm -rf "$OUT"' EXIT
CP="$CLIENT:$ROOT/build/bench-classes"
for LIB in "$ROOT"/mc/libraries/**/*.jar; do CP="$CP:$LIB"; done
mkdir -p "$OUT/classes" "$OUT/empty"
cd "$OUT" # Any JVM incidental output stays in the disposable test directory.
shasum -a 256 "$CLIENT"
"$JH/bin/javac" -J-Xmx256m -J-XX:ActiveProcessorCount=2 -J-XX:+UseSerialGC \
    -J-XX:MaxMetaspaceSize=128m -J-XX:ReservedCodeCacheSize=64m --release 25 -proc:none -implicit:none \
    -sourcepath "$OUT/empty" -cp "$CP" -d "$OUT/classes" \
    "$ROOT/bench/src/bench/Lockstep.java" "$ROOT/bench/src/bench/LodReferenceReadiness.java" \
    "$ROOT/bench/src/bench/ReferenceImageInputs.java" "$ROOT/bench/src/bench/CaptureWriter.java" "$ROOT/bench/src/bench/LodBoundaryProbe.java" \
    "$ROOT/bench/src/bench/ReuseSequence.java" "$ROOT/tools/ReuseSequenceItemsTest.java" \
    "$ROOT/tools/ReuseSequenceHandoffTest.java" "$ROOT/tools/ReadyReferenceClockTest.java"
JAVA_ARGS=(-Xmx128m -XX:ActiveProcessorCount=2 -XX:+UseSerialGC -XX:MaxMetaspaceSize=128m \
    -XX:ReservedCodeCacheSize=64m -XX:MaxDirectMemorySize=32m -ea -Djava.awt.headless=true \
    -Djava.library.path="$OUT/empty" -DreuseSequence.testClientJar="$CLIENT" -cp "$OUT/classes:$CP")
"$JH/bin/java" "${JAVA_ARGS[@]}" bench.ReadyReferenceClockTest
"$JH/bin/java" "${JAVA_ARGS[@]}" -Dbench.reuseFeature=items bench.ReuseSequenceItemsTest items "$OUT/items-off"
"$JH/bin/java" "${JAVA_ARGS[@]}" -Dbench.reuseFeature=items -Dmc.lod.cacheItems=true bench.ReuseSequenceItemsTest items "$OUT/items-on"
"$JH/bin/java" "${JAVA_ARGS[@]}" bench.ReuseSequenceItemsTest reuse "$OUT/reuse-default"
"$JH/bin/java" "${JAVA_ARGS[@]}" -Dbench.reuseFeature=reuse -Dbench.reuseTrajectory=phase0-motion -Dmc.lod.reuse=true \
    bench.ReuseSequenceItemsTest reuse "$OUT/reuse-motion"
"$JH/bin/java" "${JAVA_ARGS[@]}" -Dbench.reuseFeature=groups -Dmc.lod.cacheItems=true -Dmc.lod.cacheGroups.diagnostics=false \
    -Dmc.lod.async=false -Dmc.lod.reuse=false bench.ReuseSequenceItemsTest groups "$OUT/groups-off"
"$JH/bin/java" "${JAVA_ARGS[@]}" -Dbench.reuseFeature=groups -Dmc.lod.cacheItems=true -Dmc.lod.cacheGroups=true \
    -Dmc.lod.cacheGroups.diagnostics=false -Dmc.lod.async=false -Dmc.lod.reuse=false \
    bench.ReuseSequenceItemsTest groups "$OUT/groups-on"

# All controls are loaded from the frozen production client, with no stub LodGpu.
# Counter fixtures below test capture policy, not actual rendering or worker execution.
echo 'Real frozen-client reflection; synthetic evidence-policy fixtures; no game, GPU or worker execution.'
for SAVED in false true; do
    "$JH/bin/java" "${JAVA_ARGS[@]}" \
        -Dbench.reuseFeature=nodes -Dmc.lod.cacheNodeItems="$SAVED" -Dmc.lod.cacheItems=true -Dmc.lod.cacheGroups=true \
        -Dmc.lod.cacheGroups.diagnostics=false -Dmc.lod.itemBuildProfile=false -Dmc.lod.async=false -Dmc.lod.reuse=false \
        bench.ReuseSequenceItemsTest nodes "$OUT/nodes-$SAVED"
done

# BENCHMARK-ONLY runtime handoff policy: C's actual controls, synthetic post-render stats.
# Neither the startup EMPTY-publication prerequisite nor held atomic handoff is an A/B variable.
HANDOFF_CLIENT="${REUSE_SEQUENCE_HANDOFF_CLIENT_JAR:-$ROOT/build/client-near-handoff-c.jar}"
HANDOFF_CLIENT="${HANDOFF_CLIENT:A}"
[[ -r "$HANDOFF_CLIENT" ]] || { print -u2 "Frozen handoff client jar missing: $HANDOFF_CLIENT"; exit 1; }
HANDOFF_ARGS=("${JAVA_ARGS[@]}" -DreuseSequence.testClientJar="$HANDOFF_CLIENT" \
    -cp "$OUT/classes:$HANDOFF_CLIENT:${CP#*:}" -Dbench.reuseFeature=handoff \
    -Dmc.lod.nearHandoff=true -Dmc.lod.cacheItems=true -Dmc.lod.cacheGroups=true \
    -Dmc.lod.atomicMeshHandoff=true -Dmc.lod.asyncItems=false -Dmc.lod.cacheNodeItems=false \
    -Dmc.lod.cacheGroups.diagnostics=false -Dmc.lod.itemBuildProfile=false -Dmc.lod.prepareProfile=false \
    -Dmc.lod.diag= -Dmc.lod.async=false -Dmc.lod.reuse=false)
for SAVED in false true; do
    "$JH/bin/java" "${HANDOFF_ARGS[@]}" bench.ReuseSequenceHandoffTest normal "$OUT/handoff-$SAVED" "$SAVED"
done
"$JH/bin/java" "${HANDOFF_ARGS[@]}" -Dbench.reuseBoundaryProbe=true -Dbench.reuseBoundaryPreset=handoff28 \
    bench.ReuseSequenceHandoffTest normal "$OUT/handoff-diagnostic" false
"$JH/bin/java" "${HANDOFF_ARGS[@]}" -Dbench.reuseBoundaryProbe=true -Dbench.reuseBoundaryPreset=handoff28 \
    -Dbench.reuseBoundaryReference=true bench.ReuseSequenceHandoffTest normal "$OUT/handoff-reference" false
"$JH/bin/java" "${HANDOFF_ARGS[@]}" -Dbench.reuseBoundaryReference=true \
    bench.ReuseSequenceHandoffTest unmarked-reference "$OUT/handoff-unmarked-reference"
for CASE in unarmed late-gpu late-dispatcher; do
    "$JH/bin/java" "${HANDOFF_ARGS[@]}" -Dmc.lod.nearHandoff=false \
        bench.ReuseSequenceHandoffTest "$CASE" "$OUT/handoff-$CASE"
done
"$JH/bin/java" "${HANDOFF_ARGS[@]}" -Dbench.reuseTrajectory=default \
    bench.ReuseSequenceHandoffTest bad-trajectory "$OUT/handoff-bad-trajectory"
"$JH/bin/java" "${HANDOFF_ARGS[@]}" -Dmc.lod.atomicMeshHandoff=false \
    bench.ReuseSequenceHandoffTest bad-atomic "$OUT/handoff-bad-atomic"
for PAIR in \
    mc.lod.async=com.mojang.renderpearl.backend.metal.MetalFarPrograms \
    mc.lod.prepareProfile=net.minecraft.client.renderer.chunk.LodPreparedItems \
    mc.lod.itemBuildProfile=net.minecraft.client.renderer.chunk.LodGpu \
    mc.lod.cacheGroups.diagnostics=net.minecraft.client.renderer.chunk.LodGpu \
    bench.reuseBoundaryProbe=bench.LodBoundaryProbe; do
    PROPERTY="${PAIR%%=*}"
    "$JH/bin/java" "${HANDOFF_ARGS[@]}" "-D$PROPERTY=true" \
        bench.ReuseSequenceHandoffTest latched "$OUT/handoff-$PROPERTY" "$PROPERTY" "${PAIR#*=}"
done
# Always exercise missing nearHandoff on the original pre-repair frozen client, even when
# REUSE_SEQUENCE_CLIENT_JAR selects C for the existing-mode regression suite above/below.
OLD_CLIENT="$ROOT/build/client-async-items-a.jar"
"$JH/bin/java" "${HANDOFF_ARGS[@]}" -cp "$OUT/classes:$OLD_CLIENT:${CP#*:}" \
    bench.ReuseSequenceHandoffTest old-client "$OUT/handoff-old-client"

"$JH/bin/java" "${JAVA_ARGS[@]}" -Dbench.reuseFeature=prepare -Dbench.reuseBoundaryProbe=true \
    -Dmc.lod.cacheItems=true -Dmc.lod.cacheGroups=true -Dmc.lod.atomicMeshHandoff=true \
    bench.ReuseSequenceItemsTest prepare "$OUT/prepare-diagnostic"

for SAVED in false true; do
    PREPARE_FLAG=()
    if [[ "$SAVED" == true ]]; then PREPARE_FLAG=(-Dmc.lod.asyncItems=true); fi
    "$JH/bin/java" "${JAVA_ARGS[@]}" "${PREPARE_FLAG[@]}" \
        -Dbench.reuseFeature=prepare -Dmc.lod.cacheItems=true -Dmc.lod.cacheGroups=true \
        -Dmc.lod.atomicMeshHandoff=true -Dmc.lod.cacheNodeItems=false -Dmc.lod.diag= \
        -Dmc.lod.cacheGroups.diagnostics=false -Dmc.lod.itemBuildProfile=false -Dmc.lod.async=false -Dmc.lod.reuse=false \
        bench.ReuseSequenceItemsTest prepare "$OUT/prepare-$SAVED"
done

"$JH/bin/java" "${HANDOFF_ARGS[@]}" -Dbench.reuseReadyReference=true \
    bench.ReuseSequenceHandoffTest normal "$OUT/handoff-ready-reference" false
