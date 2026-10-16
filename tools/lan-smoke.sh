#!/bin/zsh
# LAN multiplayer smoke test (GOAL.md: multiplayer is part of every release-candidate test).
# A host client creates world "lan" (seed 4242), publishes it to LAN (online mode off: both clients are offline accounts),
# a second client joins with --quickPlayMultiplayer, waits for every visible section, measures 15 s, saves a screenshot
# and quits; the host quits when the guest has left. Both run windowed.
# GPU lock: the whole script runs in the functional lane (shared, tools/gpulock.py; it re-runs itself under it), so host
# and guest (two games at once by design) share one hold, other functional runs may overlap it, timing runs may not. The
# verdict (host saw 2 players, guest joined) does not depend on speed; the guest's FPS is informational only.
# Port: $LAN_PORT, default a free port picked at start, so concurrent smoke tests never collide. The LAN agent is built
# into <outdir>/agent (concurrent runs do not share build files).
# usage: tools/lan-smoke.sh <outdir> <hostJar|vanilla> <hostApi> <guestJar|vanilla> <guestApi>
# result: <outdir>/host.json (maxPlayers 2 = pass) and <outdir>/guest.json (joined, fps, screenshot in <outdir>/shots); both
#   have "timing" (ms since JVM start: host title / published, guest level / joined; tools/lansmoke/lansmoke/Lan.java).
# env: LAN_ARGS  extra launch.py arguments for both clients (e.g. LAN_ARGS=--jvm=-Dmc.ui.instantTransitions=false);
#      LAN_LANE  timing: hold the exclusive timing lane instead (join-time A/Bs: no other game overlaps); default functional;
#      LAN_GUEST_DELAY  seconds between the host's world being published and the guest's launch (default 0; join-time A/Bs:
#               10, so the guest's startup does not overlap the host's first seconds in its new world).
set -e
R="${0:A:h:h}"
if [[ -z $LAN_SMOKE_IN_LOCK ]]; then
  export LAN_SMOKE_IN_LOCK=1
  exec python3 "$R/tools/gpulock.py" ${LAN_LANE:-functional} --label "lan-smoke ${1:t}" -- zsh "${0:A}" "$@"
fi
XA=(${=LAN_ARGS})
JH="$R/mc/runtime/java-runtime-epsilon/jre.bundle/Contents/Home"
OUT="${1:A}"; HJ=(); [[ $2 != vanilla ]] && HJ=(--jar "${2:A}"); GJ=(); [[ $4 != vanilla ]] && GJ=(--jar "${4:A}")
PORT=${LAN_PORT:-$(python3 -c 'import socket; s=socket.socket(); s.bind(("", 0)); print(s.getsockname()[1])')}
CP="$R/mc/versions/26.3/26.3.jar"; for j in ${(f)"$(find "$R/mc/libraries" -name '*.jar' ! -name '*natives*')"}; do CP="$CP:$j"; done
rm -rf "$OUT"; mkdir -p "$OUT/host" "$OUT/guest" "$OUT/shots" "$OUT/agent/classes"
"$JH/bin/javac" --release 25 -nowarn -cp "$CP" -d "$OUT/agent/classes" "$R"/tools/lansmoke/lansmoke/*.java
printf 'Premain-Class: lansmoke.LanAgent\n' > "$OUT/agent/manifest.txt"
"$JH/bin/jar" --create --file "$OUT/agent/lan.jar" --manifest "$OUT/agent/manifest.txt" -C "$OUT/agent/classes" .
AG="-javaagent:$OUT/agent/lan.jar"
echo "lan-smoke: port $PORT"
set +e
python3 "$R/tools/launch.py" $HJ --lane functional --game-dir "$OUT/host" --api $3 --opt fullscreen:false --username Host \
  --jvm=$AG --jvm=-Dlan.role=host --jvm=-Dlan.out=$OUT/host.json --jvm=-Dlan.port=$PORT --jvm=-Dlan.hostSeconds=240 $XA &
HP=$!
for i in {1..240}; do [[ -f "$OUT/host.json.ready" ]] && break; kill -0 $HP 2>/dev/null || break; sleep 1; done
(( ${LAN_GUEST_DELAY:-0} > 0 )) && sleep ${LAN_GUEST_DELAY}
python3 "$R/tools/launch.py" $GJ --lane functional --game-dir "$OUT/guest" --api $5 --opt fullscreen:false --username Guest \
  --jvm=$AG --jvm=-Dlan.role=guest --jvm=-Dlan.out=$OUT/guest.json --jvm=-Dlan.shots=$OUT/shots \
  --game-arg=--quickPlayMultiplayer --game-arg=127.0.0.1:$PORT $XA
wait $HP
cat "$OUT/host.json" "$OUT/guest.json"
python3 -c "import json,sys; h=json.load(open(sys.argv[1])); g=json.load(open(sys.argv[2])); ok=h.get('maxPlayers')==2 and g.get('joined'); print('LAN SMOKE ' + ('PASSED' if ok else 'FAILED')); sys.exit(0 if ok else 1)" "$OUT/host.json" "$OUT/guest.json"
