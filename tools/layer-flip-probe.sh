#!/bin/zsh
# Present-fusion layer flip probes (docs/metal/CONTRACT.md 16.3), offscreen through CARenderer: no window is shown,
# no screen capture is needed.
#   layer_flip_probe       which CALayer property flips what a CAMetalLayer displays (transform does;
#                          geometryFlipped / a negative contentsRect do not)
#   layer_flip_view_probe  the real mcmtl_layer_set_flipped on an SDL-style layer-backed NSView, across AppKit
#                          window and view resizes (AppKit resets the transform; the flip must survive)
set -e
ROOT="${0:A:h:h}"
OUT="$ROOT/build/native-tests"
mkdir -p "$OUT"
typeset -a SRC
SRC=(${(f)"$(find "$ROOT/native/src" -name '*.m' | sort)"})
FLAGS=(-arch arm64 -mmacosx-version-min=26.0 -O1 -fobjc-arc -fobjc-arc-exceptions -Wall -Wno-unused-parameter
       -Wno-gnu-folding-constant)
xcrun clang $FLAGS -o "$OUT/layer_flip_probe" "$ROOT/tools/layer_flip_probe.m" \
  -framework Foundation -framework QuartzCore -framework Metal -framework IOSurface -framework CoreGraphics
xcrun clang $FLAGS -fvisibility=hidden -I "$ROOT/native/include" -I "$ROOT/native/src" -o "$OUT/layer_flip_view_probe" \
  "$ROOT/tools/layer_flip_view_probe.m" $SRC -framework AppKit -framework QuartzCore -framework Metal -framework MetalFX
"$OUT/layer_flip_probe"
"$OUT/layer_flip_view_probe"
