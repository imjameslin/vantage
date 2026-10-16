#!/bin/zsh
# Build the injectable on-glass observer (tools/glass/glass_observer.m + native/src/mcmetal_glass.m) into
# build/glass/libglassobs.dylib. Diagnostics only; usage in glass_observer.m and results/BASELINE-DEFAULT.md.
set -e
ROOT="${0:A:h:h:h}"
mkdir -p "$ROOT/build/glass"
xcrun clang -arch arm64 -mmacosx-version-min=26.0 -O2 -g -fobjc-arc -Wall -Wextra -Wno-unused-parameter \
  -dynamiclib -I "$ROOT/native/src" -framework Foundation -framework Metal -framework QuartzCore \
  -o "$ROOT/build/glass/libglassobs.dylib" "$ROOT/tools/glass/glass_observer.m" "$ROOT/native/src/mcmetal_glass.m"
codesign --force --sign - "$ROOT/build/glass/libglassobs.dylib" 2>/dev/null || true
echo "built $ROOT/build/glass/libglassobs.dylib"
