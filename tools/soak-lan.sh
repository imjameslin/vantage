#!/bin/zsh
# Multiplayer soak (docs/GOAL.md stage 1 "joining a LAN world"; bench/src/bench/Soak.java): a host client creates world "lan"
# (seed 4242, creative) and publishes it to LAN with the tools/lan-smoke.sh agent (tools/lansmoke, online mode off: both
# clients are offline accounts), then the soak client joins it as a guest (--quickPlayMultiplayer + -Dbench.join) and runs
# the soak: flight over terrain the host generates, fullscreen toggles, window resizes, memory samples, verdict. The host
# runs windowed at render distance 16 (the guest's view distance) with maxFps 60 (it is not under test; less GPU
# contention) and quits once the guest has left.
# GPU lock: the whole script runs in the timing lane (exclusive, tools/gpulock.py; it re-runs itself under it). Host and
# guest (two games at once by design) share that one hold; no other run overlaps the soak (it toggles fullscreen, its
# stall detector is wall-clock based and its memory verdict reads process footprint, which another game can move). The
# LAN agent is built into <outdir>/agent (a concurrent lan-smoke.sh shares no files with it).
# usage: tools/soak-lan.sh <label> <outdir> <minutes> <hostJar|vanilla> <hostApi> <guestJar|vanilla> <guestApi> [guest launch.py args...]
#   e.g. tools/soak-lan.sh soak-lan-r5i run/soak-lan 30 build/client-r5i.jar metal build/client-r5i.jar metal --bench-jar build/bench-r5h.jar
# result: results/raw/<label>.json (guest soak), <outdir>/host.json (maxPlayers 2 = the guest was seen), report and exit code
#   from tools/soak_report.py (0 = PASS, 2 = PASS but inconclusive, 1 = FAIL). Port: $SOAK_LAN_PORT (default: a free port).
R="${0:A:h:h}"; cd "$R"
if [[ -z $SOAK_LAN_IN_LOCK ]]; then
  export SOAK_LAN_IN_LOCK=1
  exec python3 "$R/tools/gpulock.py" timing --label "soak-lan $1" -- zsh "${0:A}" "$@"
fi
JH="$R/mc/runtime/java-runtime-epsilon/jre.bundle/Contents/Home"
LABEL=$1; OUT="${2:A}"; MIN=$3
HJ=(); [[ $4 != vanilla ]] && HJ=(--jar "${4:A}"); HAPI=$5
GJ=(); [[ $6 != vanilla ]] && GJ=(--jar "${6:A}"); GAPI=$7
shift 7
PORT=${SOAK_LAN_PORT:-$(python3 -c 'import socket; s=socket.socket(); s.bind(("", 0)); print(s.getsockname()[1])')}
rm -rf "$OUT"; mkdir -p "$OUT/host" "$OUT/guest" "$OUT/agent/classes" results/raw
rm -f "results/raw/$LABEL.json"
CP="$R/mc/versions/26.3/26.3.jar"; for j in ${(f)"$(find "$R/mc/libraries" -name '*.jar' ! -name '*natives*')"}; do CP="$CP:$j"; done
"$JH/bin/javac" --release 25 -nowarn -cp "$CP" -d "$OUT/agent/classes" "$R"/tools/lansmoke/lansmoke/*.java || exit 1
printf 'Premain-Class: lansmoke.LanAgent\n' > "$OUT/agent/manifest.txt"
"$JH/bin/jar" --create --file "$OUT/agent/lan.jar" --manifest "$OUT/agent/manifest.txt" -C "$OUT/agent/classes" . || exit 1
"$R/tools/check-overlays.sh" >/dev/null || echo "(overlays present: composited)"
echo "soak-lan: port $PORT"
FANCY=(--opt 'graphicsPreset:"fancy"' --opt biomeBlendRadius:2 --opt renderDistance:16 --opt prioritizeChunkUpdates:1
       --opt simulationDistance:12 --opt ao:true --opt 'renderClouds:"true"' --opt particles:0 --opt mipmapLevels:4
       --opt entityShadows:true --opt entityDistanceScaling:1.0 --opt menuBackgroundBlurriness:5 --opt cloudRange:64
       --opt cutoutLeaves:true --opt improvedTransparency:false --opt weatherRadius:10 --opt maxAnisotropyBit:1
       --opt textureFiltering:1)
HOSTSEC=$(( MIN * 60 + 1200 ))
HSTAMP=$(date +%s)
python3 tools/launch.py $HJ --game-dir "$OUT/host" --api $HAPI --opt fullscreen:false --opt renderDistance:16 --opt simulationDistance:12 \
  --opt maxFps:60 --username Host "--jvm=-javaagent:$OUT/agent/lan.jar" --jvm=-Dlan.role=host "--jvm=-Dlan.out=$OUT/host.json" \
  --jvm=-Dlan.port=$PORT --jvm=-Dlan.hostSeconds=$HOSTSEC &
HP=$!
while [[ ! -f "$OUT/host.json.ready" ]]; do kill -0 $HP 2>/dev/null || break; sleep 2; done
if [[ ! -f "$OUT/host.json.ready" ]]; then
  wait $HP
  echo "SOAK FAILED: the host never published the LAN world (see $OUT/host/launch.log)"
  exit 1
fi
STAMP=$(date +%s)
python3 tools/launch.py $GJ --game-dir "$OUT/guest" --api $GAPI --opt fullscreen:true $FANCY --username Guest \
  --bench "$R/results/raw/$LABEL.json" --label "$LABEL" --bench-prop mode=soak --bench-prop join=127.0.0.1:$PORT \
  --bench-prop soak.minutes=$MIN --game-arg=--quickPlayMultiplayer --game-arg=127.0.0.1:$PORT "$@" >/dev/null
GRC=$?
wait $HP; HRC=$?
python3 tools/soak_report.py "results/raw/$LABEL.json" --exit-code $GRC --game-dir "$OUT/guest" --since $STAMP \
  --host "$OUT/host.json" --host-exit-code $HRC --host-game-dir "$OUT/host" --host-since $HSTAMP
