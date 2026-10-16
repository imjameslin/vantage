#!/bin/zsh
# One-time setup: download vanilla Minecraft 26.3 and Mojang's Java runtime from Mojang's servers (tools/mcdl.py), decompile
# the client with Vineflower, and apply patches/ to rebuild the patched vanilla sources and assets. Then build with
#   zsh tools/build-client.sh        (output: build/client.jar)
#
# Nothing of Mojang's is in this repository: the vanilla files are made here, on your machine, from your own download.
set -e
ROOT="${0:A:h}"
cd "$ROOT"
VF_VERSION=1.12.0

if [[ ! -f mc/versions/26.3/26.3.jar ]]; then
  echo "== downloading vanilla 26.3 (client, libraries, Java runtime) from Mojang"
  python3 tools/mcdl.py vanilla 26.3 mc
fi
JAVA="mc/runtime/java-runtime-epsilon/jre.bundle/Contents/Home/bin/java"

if [[ ! -f tools/lib/vineflower.jar ]]; then
  echo "== downloading Vineflower $VF_VERSION (Maven Central)"
  mkdir -p tools/lib
  curl -fsSL -o tools/lib/vineflower.jar "https://repo1.maven.org/maven2/org/vineflower/vineflower/$VF_VERSION/vineflower-$VF_VERSION.jar"
fi

if [[ ! -d src/vanilla/net ]]; then
  echo "== decompiling the vanilla client into src/vanilla (a few minutes)"
  rm -rf src/vanilla
  mkdir -p src/vanilla
  "$JAVA" -jar tools/lib/vineflower.jar "-ind=    " -lit=1 mc/versions/26.3/26.3.jar src/vanilla > /dev/null
fi

echo "== applying patches"
failed=0
for p in ${(f)"$(cd patches && find src resources -name '*.patch')"}; do
  r=${p%.patch}
  kind=${r%%/*}
  rel=${r#*/}
  if [[ $kind == src ]]; then
    dst="client/src/$rel"; mkdir -p "${dst:h}"; cp "src/vanilla/$rel" "$dst"
  else
    dst="client/resources/$rel"; mkdir -p "${dst:h}"; unzip -p mc/versions/26.3/26.3.jar "$rel" > "$dst"
  fi
  if ! patch -s --no-backup-if-mismatch "$dst" < "patches/$p"; then
    echo "  FAILED: $p"; failed=$((failed + 1))
  fi
done
(( failed == 0 )) || { echo "$failed patch(es) did not apply"; exit 1; }
echo "== done. Build: zsh tools/build-client.sh"
