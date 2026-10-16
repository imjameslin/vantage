#!/bin/zsh
# Print any on-screen windows above the normal level that are not the menu bar / Dock (system alert
# panels, crash dialogs). Such overlays force macOS to composite fullscreen games, which distorts FPS.
# Exit 1 if any are present.
ROOT="${0:A:h:h}"
if ! ALL=$(swift "$ROOT/tools/swift/overlays.swift" 2>&1); then
  print -u2 -- "overlay probe failed: $ALL"
  exit 2
fi
OUT=$(print -r -- "$ALL" | grep -v -E "owner=(Window Server|Dock) " || true)
if [[ -n "$OUT" ]]; then
  echo "WARNING: overlay windows on screen (results will be composited / pessimistic):"
  echo "$OUT"
  exit 1
fi
exit 0
