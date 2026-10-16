#!/bin/zsh
# Voxy comparison runs (stage 3 round 5, results/STAGE3-R5.md): vanilla 26.2 + Fabric + Sodium 0.9.2 + Voxy 0.2.19-beta from the
# separate install mc/voxy-26.2 (tools/voxy/setup.py), game dir run/voxy262 (seed 4242 bench world), launched by tools/launch.py
# (--mc-root): GPU lock timing lane, quiet gate, overlay check, the user's game has priority, CG display mode recorded in every
# result ("launch.display"). The bench agent is build/bench-r5-262.jar (tools/voxy/build-bench262.sh: the agent compiled
# against 26.2). Static options (RD 12), OpenGL (Sodium / Voxy are OpenGL renderers), WINDOWED 1512x920 pt: 26.2's GLFW
# fullscreen picks a monitor video mode (it rendered 1920x1200 and may switch the display mode, which these runs must never do).
#
# usage: tools/voxy/voxy-run.sh <kind> <label> [launch.py args...]
#   pregen   generate + save every chunk within VOXY_PREGEN chunks (default 256) of spawn (bench mode=lodpregen)
#   import   open the world and run `/voxy import world bench` (Voxy ingests the saved chunks), hold VOXY_IMPORT_S s
#   perf     fixed view (pass pos / yaw / pitch bench props), warmup VOXY_WARMUP s (default 60), 30 s measured: median, 1 % low
#   photo    3 FPS-line photos into run/s3-shots/<label> after VOXY_WARMUP s
# Voxy's render distance: VOXY_RD chunks (default 512 = Voxy's default sectionRenderDistance 16); VOXY_ENABLED=false = Sodium only.
ROOT="${0:A:h:h:h}"; cd "$ROOT"
KIND=$1; L=$2; shift 2
V=mc/voxy-26.2
GD=${VOXY_GAME_DIR:-run/voxy262}
B=${VOXY_BENCH_JAR:-build/bench-r5-262.jar}
mkdir -p $GD/config results/raw
# Voxy config (VoxyConfig fields; sectionRenderDistance = chunks / 32). Written before every run so no run inherits another's.
cat > $GD/config/voxy-config.json <<EOF
{
  "enabled": ${VOXY_ENABLED:-true},
  "enableRendering": ${VOXY_ENABLED:-true},
  "ingestEnabled": true,
  "sectionRenderDistance": $(printf %.4f $(( ${VOXY_RD:-512} / 32.0 ))),
  "subDivisionSize": ${VOXY_SUBDIV:-64.0},
  "dontUseSodiumBuilderThreads": false
}
EOF
S=(); while IFS= read -r l; do S+=(--opt "$l"); done < tools/static-options.txt
COMMON=(--mc-root $V --version 26.2 --data-version 4903 --fabric $V/versions/fabric-loader-0.19.5-26.2/fabric-loader-0.19.5-26.2.json
        --mods $V/mods --fabric-allow-render-mods --game-dir $GD --api opengl --opt fullscreen:false --game-arg=--width --game-arg=1512 --game-arg=--height --game-arg=920 $S
        --bench results/raw/$L.json --label $L --bench-jar $B --quiet-gate 4.0:900 --overlay-check --bench-prop timeout=${VOXY_TIMEOUT:-1500})
rm -f results/raw/$L.json
case $KIND in
  pregen)
    python3 tools/launch.py $COMMON --bench-prop mode=lodpregen --bench-prop pregen.radius=${VOXY_PREGEN:-256} \
      --bench-prop timeout=${VOXY_TIMEOUT:-7000} "$@" > results/raw/$L.out 2>&1 ;;
  import)
    python3 tools/launch.py $COMMON --bench-prop "cmds=voxy import world bench" --bench-prop spin=1000000 \
      --bench-prop warmup=${VOXY_IMPORT_S:-600} --bench-prop seconds=5 --bench-prop timeout=$(( ${VOXY_IMPORT_S:-600} + 900 )) "$@" \
      > results/raw/$L.out 2>&1 ;;
  perf)
    python3 tools/launch.py $COMMON --bench-prop spin=1000000 --bench-prop warmup=${VOXY_WARMUP:-60} --bench-prop seconds=30 "$@" \
      > results/raw/$L.out 2>&1 ;;
  photo)
    mkdir -p run/s3-shots/$L
    python3 tools/launch.py $COMMON --bench-prop photo=3 --bench-prop photo.every=4 --bench-prop f3=${VOXY_F3:-fps} \
      --bench-prop spin=1000000 --bench-prop warmup=${VOXY_WARMUP:-60} --bench-prop seconds=14 --bench-prop shots="$PWD/run/s3-shots/$L" \
      "$@" > results/raw/$L.out 2>&1 ;;
esac
python3 - results/raw/$L.json <<'PY'
import json, sys
try:
    d = json.load(open(sys.argv[1]))
except Exception as e:
    print(sys.argv[1], "no result", e); sys.exit(0)
disp = d.get("launch", {}).get("display", {})
print(sys.argv[1], d.get("status", ""), "median %.0f 1%%low %.0f avg %.0f" % (d.get("medianFps", 0), d.get("p1LowFps", 0), d.get("avgFps", 0)),
      "backend", d.get("backend"), "framebuffer", d.get("framebuffer"), "display", disp.get("pixels"), disp.get("hz"), "default", disp.get("default"),
      "| pregen", d.get("pregen"), "| memory", d.get("memory"))
PY
