#!/bin/zsh
# Compile the bench agent against vanilla 26.2 (mc/voxy-26.2) -> build/bench-262.jar, for the Voxy comparison install.
set -e
ROOT="${0:A:h:h:h}"
V="$ROOT/mc/voxy-26.2"
JH="$ROOT/mc/runtime/java-runtime-epsilon/jre.bundle/Contents/Home"
CP="$V/versions/26.2/26.2.jar"
for j in ${(f)"$(find "$V/libraries" -name '*.jar' ! -name '*natives*')"}; do CP="$CP:$j"; done
OUT="$ROOT/build/bench262-classes"
SRC="$ROOT/build/bench262-src"
rm -rf "$OUT" "$SRC"; mkdir -p "$OUT"; cp -R "$ROOT/bench/src" "$SRC"
# The three 26.2 API differences the agent touches (26.3 moved DeviceInfo to renderpearl, added a fade-time argument to
# isSectionCompiledAndVisible and dropped OptionsScreen's boolean).
sed -i '' 's/com\.mojang\.renderpearl\.api\.device\.DeviceInfo/com.mojang.blaze3d.systems.DeviceInfo/' "$SRC"/bench/*.java
sed -i '' 's/isSectionCompiledAndVisible(camera, net\.minecraft\.util\.Util\.toMillis(mc\.options\.chunkSectionFadeInTime()\.get()))/isSectionCompiledAndVisible(camera)/' "$SRC"/bench/Startup.java
sed -i '' 's/new OptionsScreen(new PauseScreen(true), mc\.options)/new OptionsScreen(new PauseScreen(true), mc.options, true)/' "$SRC"/bench/Scenes.java
"$JH/bin/javac" --release 25 -nowarn -cp "$CP" -d "$OUT" ${(f)"$(find "$SRC" -name '*.java')"}
printf 'Premain-Class: bench.BenchAgent\n' > "$ROOT/build/bench-manifest.txt"
"$JH/bin/jar" --create --file "$ROOT/build/bench-262.jar" --manifest "$ROOT/build/bench-manifest.txt" -C "$OUT" .
echo "built $ROOT/build/bench-262.jar"
