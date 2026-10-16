#!/bin/zsh
# Release-candidate classic A/B: static (bench-one.sh, run/metal) 3+3 then travel (bench-travel.sh, fresh world) 3+3,
# order A B B A A B, frozen jars, bench jar $BENCH_JAR (default build/bench-r6b.jar). Display mode printed per run.
# usage: tools/ab/rc-classic.sh <prefix> <labelA> <jarA> <labelB> <jarB>
ROOT="${0:A:h:h:h}"; cd "$ROOT"
P=$1; LA=$2; JA=$3; LB=$4; JB=$5
B=${BENCH_JAR:-build/bench-r6b.jar}
mode() { python3 -c "import json,sys; d=json.load(open(sys.argv[1])); l=d.get('launch',{}).get('display',{}); print('  ', sys.argv[2], 'display', l.get('pixels'), l.get('hz'), 'default', l.get('default'), 'framebuffer', d.get('framebuffer'), 'presented', d.get('present',{}).get('presentedFps'))" results/raw/$1.json $1 2>/dev/null }
for kind in st tr; do
  i=0
  for arm in A B B A A B; do
    i=$((i + 1)); n=$(( (i + 1) / 2 ))
    if [[ $arm == A ]]; then L=$LA; J=$JA; else L=$LB; J=$JB; fi
    c=(results/raw/$P-$kind-$L-*.json(N)); lab=$P-$kind-$L-$(( ${#c} + 1 ))
    if [[ $kind == st ]]; then
      zsh tools/bench-one.sh $lab run/metal --jar $J --api metal --bench-jar $B
    else
      zsh tools/bench-travel.sh $lab run/c9 --jar $J --api metal --bench-jar $B
      python3 -c "import json,sys; d=json.load(open(sys.argv[1])); print(f\"{sys.argv[2]:22s} {d['backend']:7s} median {d['medianFps']:6.0f}  avg {d['avgFps']:6.0f}  1%low {d['p1LowFps']:5.0f}  0.1%low {d['p01LowFps']:5.0f}\")" results/raw/$lab.json $lab
    fi
    mode $lab
  done
done
