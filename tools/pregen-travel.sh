#!/bin/zsh
# Pre-generated travel world for quick screening (tools/bench-travel.sh --pregen; results/SPEEDUPS.md). Prints the pinned
# snapshot's path; makes it first if it does not exist yet: run/pregen/travel-s<seed>-<version>-rd16 (world "travel",
# default seed 4242, the travel options of tools/travel-options.txt). Final travel numbers keep the fresh-world protocol.
#
# Made with the vanilla jar in two launches under the game lock (about 2 minutes, once):
#   1. a fresh world with a 0.1 s flight: the world is created and the player stands near the spawn; its player file is kept;
#   2. the same world flown for 10 + 45 s (the benchmark flies 10 + 30 s after the same >= 8 s settle): every chunk its
#      flight and render distance reach is generated and saved;
# then launch 1's player file goes back in, so every screening run starts where the generated strip starts, and the world
# is pinned read-only (tools/pin.py world). Each --pregen run restores it (tools/launch.py --world-snapshot ... --world-only).
# usage: tools/pregen-travel.sh [--seed N] [--version V]
ROOT="${0:A:h:h}"; cd "$ROOT"
SEED=4242; VERSION=26.3
while (( $# )); do
  case $1 in
    --seed) SEED=$2; shift 2 ;;
    --version) VERSION=$2; shift 2 ;;
    *) echo "usage: tools/pregen-travel.sh [--seed N] [--version V]" >&2; exit 2 ;;
  esac
done
SNAP="$ROOT/run/pregen/travel-s$SEED-$VERSION-rd16"
if [[ -f "$SNAP/SHA256SUMS" ]]; then
  python3 tools/pin.py --verify "$SNAP" >&2 || exit 1
  print -r -- "$SNAP"
  exit 0
fi
WORK="$ROOT/run/pregen-work-s$SEED-$VERSION"
rm -rf "$WORK"; mkdir -p "$WORK" "$ROOT/run/pregen" "$ROOT/results/raw"
FANCY=(); while IFS= read -r line; do FANCY+=(--opt "$line"); done < "$ROOT/tools/travel-options.txt"
COMMON=(--version "$VERSION" --game-dir "$WORK" --api vulkan --env SDL_VIDEO_MAC_FULLSCREEN_SPACES=0 --opt fullscreen:true $FANCY
        --bench-prop mode=travel --bench-prop world=travel --bench-prop seed=$SEED)
echo "pregen-travel: launch 1 (create world travel, seed $SEED)" >&2
python3 tools/launch.py $COMMON --bench "$WORK/create.json" --label pregen-create \
  --bench-prop warmup=0 --bench-prop seconds=0.1 >/dev/null || { echo "pregen-travel: launch 1 failed ($WORK/launch.log)" >&2; exit 1; }
PLAYERS="$WORK/saves/travel/players/data"
[[ -d "$PLAYERS" ]] || { echo "pregen-travel: no player file after launch 1" >&2; exit 1; }
rm -rf "$WORK/start-player"; cp -R "$PLAYERS" "$WORK/start-player"
echo "pregen-travel: launch 2 (fly 10 + 45 s)" >&2
python3 tools/launch.py $COMMON --world-snapshot none --bench "$WORK/fly.json" --label pregen-fly \
  --bench-prop warmup=10 --bench-prop seconds=45 >/dev/null || { echo "pregen-travel: launch 2 failed ($WORK/launch.log)" >&2; exit 1; }
rm -rf "$PLAYERS"; cp -R "$WORK/start-player" "$PLAYERS"
python3 tools/pin.py world "$WORK" "$SNAP" --world travel >&2 || exit 1
cp "$WORK/create.json" "$SNAP.create.json"; cp "$WORK/fly.json" "$SNAP.fly.json"   # provenance (start, flight length)
rm -rf "$WORK"
print -r -- "$SNAP"
