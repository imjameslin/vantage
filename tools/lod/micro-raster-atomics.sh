#!/bin/zsh
# Driver capability evidence only. Run under tools/gpulock.py timing.
set -euo pipefail
ROOT="${0:A:h:h:h}"
cd "$ROOT"
mkdir -p build/micro-raster
xcrun clang -arch arm64 -mmacosx-version-min=26.0 -O2 -fobjc-arc -Wall -Wextra -Werror \
    tools/lod/micro-raster-atomics.m -framework Foundation -framework Metal \
    -o build/micro-raster/atomics
build/micro-raster/atomics
