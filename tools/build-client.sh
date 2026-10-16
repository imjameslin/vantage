#!/bin/zsh
# Build the optimized client: compile client/src (new classes + patched copies of
# vanilla classes) against the vanilla 26.3 jar, build the native Metal library
# from native/src, merge lang overrides, and overlay everything onto a copy of the
# vanilla jar.
#
# Output: build/client.jar  (drop-in replacement for versions/26.3/26.3.jar)
#
# Jar layout added on top of vanilla (docs/metal/CONTRACT.md section 10):
#   com/mojang/renderpearl/backend/metal/*.class, net/minecraft/client/PreferredGraphicsApi.class, ...
#   macos/arm64/com/mojang/renderpearl/backend/metal/libmcmetal.dylib            (LWJGL Library.loadNative path)
#   META-INF/macos/arm64/com/mojang/renderpearl/backend/metal/libmcmetal.dylib.sha1
#   assets/minecraft/lang/en_us.json  (vanilla file + keys from client/lang/en_us.json)
set -e
ROOT="${0:A:h:h}"
JH="$ROOT/mc/runtime/java-runtime-epsilon/jre.bundle/Contents/Home"
VANILLA="$ROOT/mc/versions/26.3/26.3.jar"
OUT="$ROOT/build"
CLASSES="$OUT/client-classes"
NATIVE_OUT="$OUT/native"
DYLIB_PATH="macos/arm64/com/mojang/renderpearl/backend/metal/libmcmetal.dylib"

CP="$VANILLA"
for j in ${(f)"$(find "$ROOT/mc/libraries" -name '*.jar' ! -name '*natives*')"}; do CP="$CP:$j"; done

rm -rf "$CLASSES"; mkdir -p "$CLASSES"
SOURCES=(${(f)"$(find "$ROOT/client/src" -name "*.java" 2>/dev/null || true)"})
if (( ${#SOURCES} )); then
  echo "javac: ${#SOURCES} sources"
  "$JH/bin/javac" --release 25 -g -nowarn -proc:none -Xlint:none -encoding UTF-8 \
    -cp "$CP" -d "$CLASSES" $SOURCES
fi

# Method merge (docs/metal/CONTRACT.md 16.FAB, tools/MethodMerge.java): in every patched vanilla class, ship Mojang's
# original bytecode for each method our source did not change (decided by compiling the decompiled vanilla source of the
# same classes, src/vanilla, and comparing it method by method with ours), so Fabric mods' mixins see vanilla code
# wherever we did not patch. MC_METHOD_MERGE=0 builds the old full-copy classes (A/B only).
if [[ "${MC_METHOD_MERGE:-1}" != 0 ]]; then
  "$JH/bin/java" "$ROOT/tools/MethodMerge.java" "$VANILLA" "$CLASSES" "$ROOT/client/src" "$ROOT/src/vanilla" "$OUT/merge-work" \
    "$OUT/method-merge.txt" "$CP"
fi

# Resources that ship inside the jar verbatim (shaders, textures, ...). Never put a partial
# assets/minecraft/lang/*.json here: it would replace the whole vanilla file. Use client/lang instead.
if [[ -d "$ROOT/client/resources" ]]; then
  cp -R "$ROOT/client/resources/." "$CLASSES/"
fi

# Stage 3 r4: LOD tree impostor sprites, rendered from the vanilla jar's block textures (never committed; LodTrees, CONTRACT
# 16.S3-LOD-R4). The block atlas stitches every PNG under textures/block/.
mkdir -p "$CLASSES/assets/minecraft/lod"
"$JH/bin/java" "$ROOT/tools/LodImpostorGen.java" "$VANILLA" "$CLASSES/assets/minecraft/textures/block" "$CLASSES/assets/minecraft/lod/tree_sprites.txt"

# Lang overrides: client/lang/<lang>.json holds only added/changed keys; merge them into the vanilla
# file of the same name (en_us is the fallback for every other language).
LANG_FILES=(${(f)"$(find "$ROOT/client/lang" -name '*.json' 2>/dev/null || true)"})
if (( ${#LANG_FILES} )); then
  mkdir -p "$CLASSES/assets/minecraft/lang"
  for f in $LANG_FILES; do
    name="${f:t}"
    base="$OUT/lang-base-$name"
    if ! unzip -p "$VANILLA" "assets/minecraft/lang/$name" > "$base" 2>/dev/null; then echo '{}' > "$base"; fi
    python3 - "$base" "$f" "$CLASSES/assets/minecraft/lang/$name" <<'PY'
import json, sys
base_path, extra_path, out_path = sys.argv[1:4]
with open(base_path, encoding="utf-8") as fh:
    merged = json.load(fh)
with open(extra_path, encoding="utf-8") as fh:
    extra = json.load(fh)
merged.update(extra)
with open(out_path, "w", encoding="utf-8") as fh:
    json.dump(merged, fh, ensure_ascii=False, indent=2)
    fh.write("\n")
print(f"lang: {out_path.rsplit('/', 1)[-1]} +{len(extra)} key(s)")
PY
    rm -f "$base"
  done
fi

# Native Metal bridge: native/src only (native/tests is built by tools/build-native-tests.sh).
NATIVE_SRC=(${(f)"$(find "$ROOT/native/src" -name '*.m' 2>/dev/null | sort || true)"})
if (( ${#NATIVE_SRC} )); then
  mkdir -p "$NATIVE_OUT"
  echo "clang: ${#NATIVE_SRC} native sources"
  # -fobjc-arc-exceptions: every export catches NSExceptions and carries on, so ARC must release strong
  # locals (drawables, encoders, command buffers) while an exception unwinds.
  xcrun clang -arch arm64 -mmacosx-version-min=26.0 -O2 -g -fobjc-arc -fobjc-arc-exceptions -fvisibility=hidden \
    -Wall -Wextra -Wno-unused-parameter -Werror=unguarded-availability-new -Werror=return-type \
    -dynamiclib -install_name @rpath/libmcmetal.dylib \
    -I "$ROOT/native/include" -I "$ROOT/native/src" \
    -framework Foundation -framework Metal -framework QuartzCore -framework MetalFX \
    -o "$NATIVE_OUT/libmcmetal.dylib" $NATIVE_SRC
  mkdir -p "$CLASSES/${DYLIB_PATH:h}" "$CLASSES/META-INF/${DYLIB_PATH:h}"
  cp "$NATIVE_OUT/libmcmetal.dylib" "$CLASSES/$DYLIB_PATH"
  codesign --force --sign - "$CLASSES/$DYLIB_PATH" 2>/dev/null || true
  shasum -a 1 "$CLASSES/$DYLIB_PATH" | cut -d' ' -f1 | tr -d '\n' > "$CLASSES/META-INF/$DYLIB_PATH.sha1"
fi

# The vanilla jar is signed; overlaying classes breaks the per-entry digests, so
# drop the signature and replace the manifest with an unsigned one.
cp "$VANILLA" "$OUT/client.jar"
zip -q -d "$OUT/client.jar" 'META-INF/MOJANGCS.SF' 'META-INF/MOJANGCS.RSA' 'META-INF/MANIFEST.MF'
printf 'Manifest-Version: 1.0\nMain-Class: net.minecraft.client.Main\n' > "$OUT/client-manifest.txt"
"$JH/bin/jar" --update --file "$OUT/client.jar" --manifest "$OUT/client-manifest.txt" -C "$CLASSES" .
echo "built $OUT/client.jar ($(du -h "$OUT/client.jar" | cut -f1))"
