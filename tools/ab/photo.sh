#!/bin/zsh
# FPS-line-only photos (photo mode, static options, fixed yaw 200), timing lane.
# usage: tools/ab/photo.sh <label> <client-jar> <shots dir under run/> [launch.py args...]
ROOT="${0:A:h:h:h}"; cd "$ROOT"
L=$1; J=$2; D=$3; shift 3
S=(); while IFS= read -r l; do S+=(--opt "$l"); done < tools/static-options.txt
python3 tools/launch.py --jar $J --game-dir run/metal --api metal --opt fullscreen:true $S \
  --bench results/raw/$L.json --label $L --bench-jar ${BENCH_JAR:-build/bench-r6b.jar} \
  --bench-prop photo=3 --bench-prop f3=fps --bench-prop spin=1000000 --bench-prop yaw=200 --bench-prop shots="$PWD/run/$D" "$@" \
  > results/raw/$L.out 2>&1
python3 -c "import json,sys; d=json.load(open(sys.argv[1])); l=d.get('launch',{}).get('display',{}); print(sys.argv[1], 'median', round(d.get('medianFps',0)), '1%low', round(d.get('p1LowFps',0)), 'display', l.get('pixels'), l.get('hz'), 'default', l.get('default'))" results/raw/$L.json
ls run/$D/screenshots/ | grep $L
