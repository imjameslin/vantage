#!/bin/zsh
# I5 (java-pipeline) headless test: every vanilla pipeline through the real frontend (GlslCompiler ->
# SPIR-V -> binding patching) into MslTranslator / MetalRenderPipeline, then through real Metal
# (newLibraryWithSource MSL 3.2, PSO variants, reflection vs binding masks, decoder-style draws, a pixel
# test) under MTL_DEBUG_LAYER=1. See tools/I5PipelineTest.java.
#
# Native side: build/i5test/libmcmetal_i5test.dylib = every native/src/*.m (the real I1 pipeline code) +
# tools/i5_pipeline_harness.m (reflection/render helpers). With --reference-pipeline, mcmetal_pipeline.m is
# left out and the harness's test-only reference implementation of the three I1 pipeline entry points is
# linked instead (pre-merge mode). The shipped dylib is not affected.
#
# Usage: tools/build-client.sh && tools/i5-pipeline-test.sh [--no-native | --reference-pipeline] [extra JVM args...]
set -e
ROOT="${0:A:h:h}"
JH="$ROOT/mc/runtime/java-runtime-epsilon/jre.bundle/Contents/Home"
JAR="$ROOT/build/client.jar"
if [[ ! -f "$JAR" ]]; then
  echo "missing $JAR: run tools/build-client.sh first" >&2
  exit 2
fi
NATIVE=1
REFERENCE=0
if [[ "$1" == "--no-native" ]]; then NATIVE=0; shift; fi
if [[ "$1" == "--reference-pipeline" ]]; then REFERENCE=1; shift; fi

typeset -a JVM
if (( NATIVE )); then
  OUT="$ROOT/build/i5test"
  mkdir -p "$OUT"
  typeset -a HARNESS_FLAGS
  if (( REFERENCE )); then
    SRC=(${(f)"$(find "$ROOT/native/src" -name '*.m' ! -name 'mcmetal_pipeline.m' | sort)"})
    HARNESS_FLAGS=(-DI5_HARNESS_REFERENCE_PIPELINE=1)
  else
    SRC=(${(f)"$(find "$ROOT/native/src" -name '*.m' | sort)"})
  fi
  echo "clang: libmcmetal_i5test.dylib (${#SRC} native/src files + i5_pipeline_harness.m${HARNESS_FLAGS:+, reference pipeline})"
  xcrun clang $HARNESS_FLAGS -arch arm64 -mmacosx-version-min=26.0 -O1 -g -fobjc-arc -fobjc-arc-exceptions -fvisibility=hidden \
    -Wall -Wextra -Wno-unused-parameter -Werror=unguarded-availability-new -Werror=return-type \
    -dynamiclib -install_name @rpath/libmcmetal_i5test.dylib \
    -I "$ROOT/native/include" -I "$ROOT/native/src" \
    -framework Foundation -framework Metal -framework QuartzCore -framework MetalFX \
    -o "$OUT/libmcmetal_i5test.dylib" $SRC "$ROOT/tools/i5_pipeline_harness.m"
  JVM+=("-Dmcmetal.library=$OUT/libmcmetal_i5test.dylib")
  export MTL_DEBUG_LAYER=1
fi

CP="$JAR"
for j in ${(f)"$(find "$ROOT/mc/libraries" -name '*.jar' | sort)"}; do
  if [[ "$j" == *natives-macos.jar || "$j" == *osx-x86_64.jar ]]; then continue; fi
  CP="$CP:$j"
done
EXTRACT="$ROOT/build/i5test-natives"
mkdir -p "$EXTRACT" "$ROOT/build/i5test-run"
cd "$ROOT/build/i5test-run"   # log4j writes logs/ relative to the working directory
exec "$JH/bin/java" --enable-native-access=ALL-UNNAMED -XX:+UseZGC -ea \
  -Dorg.lwjgl.system.SharedLibraryExtractPath="$EXTRACT" \
  $JVM "$@" -cp "$CP" "$ROOT/tools/I5PipelineTest.java" "$ROOT"
