#!/bin/zsh
# Isolated CPU regression: compile only the dispatcher and this test, using a frozen jar for unrelated dependencies.
# Never rebuilds build/client.jar. Tests the startup property absent/false/true in separate JVMs.
# Optionally compare OFF behavior/signatures and prove ON regressions against a pre-fix ref: EMPTY_PUBLICATION_BASELINE=HEAD.
set -euo pipefail
ROOT="${0:A:h:h}"
JH="$ROOT/mc/runtime/java-runtime-epsilon/jre.bundle/Contents/Home"
CLIENT="${EMPTY_PUBLICATION_CLIENT_JAR:-$ROOT/build/client-async-items-fade-a.jar}"
CLIENT="${CLIENT:A}"
CP="$CLIENT"
for LIB in "$ROOT"/mc/libraries/**/*.jar; do
  if [[ "$LIB" == *natives-macos.jar || "$LIB" == *osx-x86_64.jar ]]; then continue; fi
  CP="$CP:$LIB"
done
OUT="$(mktemp -d "${TMPDIR:-/tmp}/section-empty-publication.XXXXXX")"
trap 'rm -rf "$OUT"' EXIT
mkdir -p "$OUT/classes" "$OUT/empty" "$OUT/baseline" "$OUT/compat"
cd "$OUT"
JAVAC=("$JH/bin/javac" -J-Xmx256m -J-XX:ActiveProcessorCount=2 --release 25 -proc:none -implicit:none -sourcepath "$OUT/empty")
SOURCE=client/src/net/minecraft/client/renderer/chunk/SectionRenderDispatcher.java
"${JAVAC[@]}" -cp "$CP" -d "$OUT/classes" "$ROOT/$SOURCE" "$ROOT/tools/SectionEmptyPublicationTest.java"
JAVA=("$JH/bin/java" -Xmx128m -XX:ActiveProcessorCount=2 -ea -Djava.awt.headless=true -Djava.library.path="$OUT/empty")
TEST=net.minecraft.client.renderer.chunk.SectionEmptyPublicationTest
for MODE in default false true; do
  OPTIONS=()
  if [[ "$MODE" != default ]]; then OPTIONS=("-Dmc.lod.nearHandoff=$MODE"); fi
  EXPECTED=false
  SUITE=legacy
  if [[ "$MODE" == true ]]; then EXPECTED=true; SUITE=all; fi
  "${JAVA[@]}" "${OPTIONS[@]}" -cp "$OUT/classes:$CP" "$TEST" startup "$EXPECTED"
  "${JAVA[@]}" "${OPTIONS[@]}" -cp "$OUT/classes:$CP" "$TEST" "$SUITE" | tee "$OUT/current-$MODE.log"
  "${JAVA[@]}" "${OPTIONS[@]}" -cp "$OUT/classes:$CP" "$TEST" provenance
done
# Exercise the statistics monitor and queue-cancellation lock order; no game or native renderer is started.
"${JAVA[@]}" -Dmc.lod.nearHandoff=true -Dmc.chunkStats=true -cp "$OUT/classes:$CP" "$TEST"
# Reuse existing fake-GPU fixtures for actual four-argument uploads and the 450-trial carried-upload ordering suite.
"${JAVAC[@]}" -cp "$OUT/classes:$CP" -d "$OUT/compat" "$ROOT/tools/TravelHitchTest.java" "$ROOT/tools/ref/RefUberGpuBuffer.java"
for MODE in false true; do
  "${JAVA[@]}" --enable-native-access=ALL-UNNAMED -Dorg.lwjgl.system.SharedLibraryExtractPath="$OUT/natives" \
    "-Dmc.lod.nearHandoff=$MODE" -cp "$OUT/classes:$OUT/compat:$CP" "$TEST" compat
done
if [[ -n "${EMPTY_PUBLICATION_BASELINE:-}" ]]; then
  git -C "$ROOT" show "$EMPTY_PUBLICATION_BASELINE:$SOURCE" > "$OUT/baseline/SectionRenderDispatcher.java"
  "${JAVAC[@]}" -cp "$CP" -d "$OUT/baseline" "$OUT/baseline/SectionRenderDispatcher.java"
  for MODE in default false; do
    OPTIONS=()
    if [[ "$MODE" != default ]]; then OPTIONS=("-Dmc.lod.nearHandoff=$MODE"); fi
    "${JAVA[@]}" "${OPTIONS[@]}" -cp "$OUT/baseline:$OUT/classes:$CP" "$TEST" legacy > "$OUT/baseline-$MODE.log"
    diff -u "$OUT/baseline-$MODE.log" "$OUT/current-$MODE.log"
    print "SectionEmptyPublicationTest: OFF ($MODE) predecessor behavior equivalent in both upload modes"
  done
  # Include private methods/nested classes; allow only the startup arm and the additive EMPTY provenance API/field.
  python3 - "$JH/bin/javap" "$OUT" "$CP" <<'PY'
from pathlib import Path
import difflib, subprocess, sys
javap, out, cp = sys.argv[1:]
out = Path(out)
for file in sorted((out / 'baseline/net/minecraft/client/renderer/chunk').glob('SectionRenderDispatcher*.class')):
    name = 'net.minecraft.client.renderer.chunk.' + file.stem
    def signatures(directory):
        result = subprocess.check_output([javap, '-p', '-s', '-classpath', str(out / directory) + ':' + cp, name], text=True)
        lines = [line.strip() for line in result.splitlines() if line.strip()]
        additions = {
            'SectionRenderDispatcher': [('private static final boolean NEAR_HANDOFF_STARTUP;', 'descriptor: Z'),
                                        ('static {};', 'descriptor: ()V')],
            'SectionRenderDispatcher$RenderSection': [('private long handoffEmptySeq;', 'descriptor: J'),
                                                       ('private long handoffResetSeq;', 'descriptor: J'),
                                                       ('public long handoffGeneration();', 'descriptor: ()J'),
                                                       ('public boolean handoffEmptyReady();', 'descriptor: ()Z')],
        }
        if directory == 'classes':
            for declaration, descriptor in additions.get(file.stem, []):
                index = lines.index(declaration)
                assert lines[index + 1] == descriptor
                del lines[index:index + 2]
        return lines
    before, after = signatures('baseline'), signatures('classes')
    if before != after:
        print('\n'.join(difflib.unified_diff(before, after, fromfile='baseline ' + name, tofile='current ' + name)))
        raise SystemExit(1)
print('SectionEmptyPublicationTest: existing descriptors unchanged; only startup arm and EMPTY provenance API/field added')
PY
  for SUITE in lock races sequence; do
    if "${JAVA[@]}" -Dmc.lod.nearHandoff=true -cp "$OUT/baseline:$OUT/classes:$CP" "$TEST" "$SUITE" > "$OUT/baseline-$SUITE.log" 2>&1; then
      print -u2 "FAIL: old dispatcher escaped $SUITE regression"
      exit 1
    fi
    case "$SUITE" in
      lock) EXPECTED='EMPTY must not be exposed before copyLock acquisition' ;;
      races) EXPECTED='stale no-upload result rejected after cancel' ;;
      sequence) EXPECTED='sequence advances for EMPTY in both modes' ;;
    esac
    if ! rg -q "$EXPECTED" "$OUT/baseline-$SUITE.log"; then
      cat "$OUT/baseline-$SUITE.log"
      print -u2 "FAIL: unexpected baseline failure in $SUITE"
      exit 1
    fi
    print "SectionEmptyPublicationTest: old dispatcher rejected by $SUITE regression ($EXPECTED)"
  done
fi
