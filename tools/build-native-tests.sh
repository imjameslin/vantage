#!/bin/zsh
# Build the core headless native tests, compiled together with all native/src/*.m
# (same flags as the shipped dylib, plus the internal header path) into build/native-tests/<name>.
#
# Usage: tools/build-native-tests.sh [--run] [--no-build] [name ...]
#   --run       run each built test (MTL_DEBUG_LAYER=1) and fail if any test fails
#   --no-build  with --run: run the binaries an earlier build left in build/native-tests, compile nothing
#               (tools/headless-suites.sh builds in its CPU pool and runs the tests alone)
#   name    build only these core tests (e.g. core_test decoder_test); default: all core tests
set -e
ROOT="${0:A:h:h}"
OUT="$ROOT/build/native-tests"
RUN=0
BUILD=1
typeset -a WANTED
for arg in "$@"; do
  if [[ "$arg" == "--run" ]]; then RUN=1; elif [[ "$arg" == "--no-build" ]]; then BUILD=0; else WANTED+=("${arg%.m}"); fi
done

typeset -a SRC TESTS CORE_TESTS
CORE_TESTS=(abi_test core_test decoder_test frametrace_test occlusion_test farimage_cull_test farimage_lifecycle_test farimage_gpu_test farimage_snapshot_test farimage_source_test farimage_shader_test farimage_program_test farimage_producer_test farimage_geometry_test farimage_material_test farimage_engine_test farimage_warp_test farimage_resolve_test farimage_consumer_test farimage_lookup_test farimage_shade_source_test farimage_shade_test farimage_alpha_test farimage_shaded_consumer_test)
for name in $WANTED; do
  if (( ! ${CORE_TESTS[(Ie)$name]} )); then
    echo "unknown core native test: $name (available: ${(j: :)CORE_TESTS})" >&2
    exit 2
  fi
done
SRC=(${(f)"$(find "$ROOT/native/src" -name '*.m' | sort)"})
# Keep stand-alone capture/research probes out of the core suite. A directory
# glob also admitted probes needing fixtures, optional sources and custom flags.
for name in $CORE_TESTS; do
  if (( ${#WANTED} )) && (( ! ${WANTED[(Ie)$name]} )); then continue; fi
  t="$ROOT/native/tests/$name.m"
  if [[ ! -f "$t" ]]; then
    echo "missing core native test: $t" >&2
    exit 1
  fi
  TESTS+=("$t")
done

mkdir -p "$OUT"
# Same language flags, visibility and frameworks as the shipped dylib (tools/build-client.sh), so a test
# links whenever the dylib does (MetalFX included).
FLAGS=(-arch arm64 -mmacosx-version-min=26.0 -O1 -g -fobjc-arc -fobjc-arc-exceptions -fvisibility=hidden
       -Wall -Wextra -Wno-unused-parameter -Werror=unguarded-availability-new -Werror=return-type
       -I "$ROOT/native/include" -I "$ROOT/native/src"
       -framework Foundation -framework Metal -framework QuartzCore -framework MetalFX)
typeset -a BUILT
for t in $TESTS; do
  name="${t:t:r}"
  if (( ${#WANTED} )) && (( ! ${WANTED[(Ie)$name]} )); then continue; fi
  if (( BUILD )); then
    echo "clang: $name"
    xcrun clang $FLAGS -o "$OUT/$name" "$t" $SRC
  else
    for f in "$t" $SRC; do
      if [[ ! -x "$OUT/$name" || "$OUT/$name" -ot "$f" ]]; then
        echo "--no-build: $OUT/$name is missing or older than $f" >&2
        exit 1
      fi
    done
  fi
  BUILT+=("$name")
done
if (( BUILD )); then
  echo "built ${#BUILT} test(s) into $OUT"
fi

if (( RUN )); then
  failed=0
  for name in $BUILT; do
    echo "== $name"
    if ! MTL_DEBUG_LAYER=1 "$OUT/$name"; then
      echo "== $name FAILED"
      failed=1
    fi
  done
  exit $failed
fi
