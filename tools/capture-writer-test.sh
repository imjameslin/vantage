#!/bin/zsh
set -euo pipefail
ROOT="${0:A:h:h}"
JH="$ROOT/mc/runtime/java-runtime-epsilon/jre.bundle/Contents/Home"
OUT="$(mktemp -d "${TMPDIR:-/tmp}/capture-writer.XXXXXX")"
trap 'rm -rf "$OUT"' EXIT
"$JH/bin/javac" -J-Xmx128m -J-XX:ActiveProcessorCount=2 --release 25 -Xlint:all -Werror -d "$OUT" \
  "$ROOT/bench/src/bench/CaptureWriter.java" "$ROOT/tools/CaptureWriterTest.java"
"$JH/bin/java" -Xmx128m -XX:ActiveProcessorCount=2 -ea -cp "$OUT" bench.CaptureWriterTest
