#!/bin/zsh
# Static FPS of our client alone vs under Fabric (no mods) vs Fabric + the main test mod set (results/FABRIC.md), classic
# protocol (tools/bench-one.sh, timing lane), one game dir for all arms (run/fab-metal: a copy of run/metal's bench world
# and options, so the Fabric arms never write mods / configs into run/metal). Order ABC CBA ABC.
# usage: tools/ab/fabric-fps.sh <prefix> <client-jar> [mods-dir]   (bench jar: $BENCH_JAR or build/bench-r6b.jar)
ROOT="${0:A:h:h:h}"; cd "$ROOT"
P=$1; J=$2; MODS=${3:-mc/fabric/sets/main}
B=${BENCH_JAR:-build/bench-r6b.jar}
mkdir -p mc/fabric/sets/none
mode() { python3 -c "import json,sys; d=json.load(open(sys.argv[1])); l=d.get('launch',{}); print('  ', sys.argv[2], 'display', l.get('display',{}).get('pixels'), l.get('display',{}).get('hz'), 'default', l.get('display',{}).get('default'), 'fabric', (l.get('fabric') or {}).get('loader'), len((l.get('fabric') or {}).get('mods') or []), 'mods')" results/raw/$1.json $1 2>/dev/null }
for arm in plain none main main none plain plain none main; do
  c=(results/raw/$P-$arm-*.json(N)); lab=$P-$arm-$(( ${#c} + 1 ))
  case $arm in
    plain) zsh tools/bench-one.sh $lab run/fab-metal --jar $J --api metal --bench-jar $B ;;
    none)  zsh tools/bench-one.sh $lab run/fab-metal --jar $J --api metal --bench-jar $B --fabric --mods mc/fabric/sets/none ;;
    main)  zsh tools/bench-one.sh $lab run/fab-metal --jar $J --api metal --bench-jar $B --fabric --mods $MODS ;;
  esac
  mode $lab
done
