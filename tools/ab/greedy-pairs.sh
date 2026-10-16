#!/bin/zsh
# Round 12 Part B: greedy-meshing BEFORE / AFTER screenshot pairs for the user (docs/QUEUE.md item 4), same views and
# settings, greedy off vs on (exact and aggressive), plus their perceptual metrics (tools/PerceptualDiff.java).
#   scenes (bench-r12.jar, pinned world, scene runner settings): day (wide landscape), ao_grass / ao_stone (close-ups with
#   corner shadows), cave (torch), night_horizon (night); photo spot: photo mode, static options, yaw 200, FPS line.
# Every run with -Dmc.terrain.clusters=false: the greedy prototype draws through the per-record path (the cluster path, default
# on since r12, would take those layers first), as in its abab measurements.
# usage: tools/ab/greedy-pairs.sh <client-jar> <out dir>     e.g. build/client-r12c.jar ../../../share/greedy
ROOT="${0:A:h:h:h}"; cd "$ROOT"
J=$1; OUT=$2
SC=day,night_horizon,cave,ao_grass,ao_stone
export BENCH_JAR=build/bench-r12.jar
zsh tools/ab/scenes-one.sh gp-before $J metal --bench-prop only=$SC --jvm=-Dmc.terrain.clusters=false
zsh tools/ab/scenes-one.sh gp-exact $J metal --bench-prop only=$SC --jvm=-Dmc.terrain.clusters=false --jvm=-Dmc.terrain.greedy=exact --jvm=-Dmc.terrain.meshStats=5
zsh tools/ab/scenes-one.sh gp-aggressive $J metal --bench-prop only=$SC --jvm=-Dmc.terrain.clusters=false --jvm=-Dmc.terrain.greedy=aggressive --jvm=-Dmc.terrain.meshStats=5
mkdir -p run/photos-greedy/screenshots
zsh tools/ab/photo.sh gp-photo-before $J photos-greedy --jvm=-Dmc.terrain.clusters=false
zsh tools/ab/photo.sh gp-photo-exact $J photos-greedy --jvm=-Dmc.terrain.clusters=false --jvm=-Dmc.terrain.greedy=exact
zsh tools/ab/photo.sh gp-photo-aggressive $J photos-greedy --jvm=-Dmc.terrain.clusters=false --jvm=-Dmc.terrain.greedy=aggressive
mkdir -p "$OUT" run/greedy-cmp/before run/greedy-cmp/exact run/greedy-cmp/aggressive
typeset -A NAME
NAME=(day wide night_horizon night cave cave ao_grass grass-ao ao_stone stone-ao)
for s in ${(k)NAME}; do
  n=${NAME[$s]}
  cp run/shots-gp-before/screenshots/$s.png "$OUT/$n-before.png"
  cp run/shots-gp-exact/screenshots/$s.png "$OUT/$n-after-exact.png"
  cp run/shots-gp-aggressive/screenshots/$s.png "$OUT/$n-after-aggressive.png"
  cp run/shots-gp-before/screenshots/$s.png run/greedy-cmp/before/$n.png
  cp run/shots-gp-exact/screenshots/$s.png run/greedy-cmp/exact/$n.png
  cp run/shots-gp-aggressive/screenshots/$s.png run/greedy-cmp/aggressive/$n.png
done
# Photo spot: the second of the three photos of each run (the camera holds yaw 200; the FPS line differs by design).
for m in before exact aggressive; do
  f=$(ls run/photos-greedy/screenshots/ | grep "gp-photo-$m-2\.png" | head -1)
  [[ $m == before ]] && dst="$OUT/photo-spot-before.png" || dst="$OUT/photo-spot-after-$m.png"
  cp "run/photos-greedy/screenshots/$f" "$dst"
  cp "run/photos-greedy/screenshots/$f" run/greedy-cmp/$m/photo-spot.png
done
J8=mc/runtime/java-runtime-epsilon/jre.bundle/Contents/Home/bin/java
$J8 tools/PerceptualDiff.java run/greedy-cmp/before run/greedy-cmp/exact > results/raw/perceptual-greedy-exact.txt 2>&1
$J8 tools/PerceptualDiff.java run/greedy-cmp/before run/greedy-cmp/aggressive > results/raw/perceptual-greedy-aggressive.txt 2>&1
cat results/raw/perceptual-greedy-exact.txt results/raw/perceptual-greedy-aggressive.txt
grep -h "greedy (" results/raw/scenes-gp-*.log | tail -4
