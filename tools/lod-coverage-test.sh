#!/bin/zsh
# Build only the headless coverage regression against current native sources.
# All compiler/linker outputs live in a fresh temporary directory; no shared build outputs or game.
set -euo pipefail
ROOT="${0:A:h:h}"
OUT="$(mktemp -d "${TMPDIR:-/tmp}/lod-coverage-test.XXXXXX")"
trap 'rm -rf "$OUT"' EXIT
cd "$OUT"
xcrun clang -arch arm64 -mmacosx-version-min=26.0 -O1 -g \
    -fobjc-arc -fobjc-arc-exceptions -fvisibility=hidden \
    -Wall -Wextra -Wno-unused-parameter -Werror=unguarded-availability-new -Werror=return-type \
    -I "$ROOT/native/include" -I "$ROOT/native/src" \
    -framework Foundation -framework Metal -framework QuartzCore -framework MetalFX \
    "$ROOT/native/tests/lod_coverage_test.m" "$ROOT"/native/src/*.m \
    -o "$OUT/lod_coverage_test"
MTL_DEBUG_LAYER=1 MTL_SHADER_VALIDATION=1 "$OUT/lod_coverage_test"
