#!/bin/zsh
# W8a measurement (results/LAUNCH-SPEED.md): what a cold OS Metal shader cache costs at startup, and whether a warm-up
# removes it. Cold is simulated without deleting any cache: the salt agent (tools/shadersalt, built into
# build/shadersalt.jar) prefixes every MSL source with a comment naming a fresh salt, so the front-end cache misses; with
# "time" it also sums the wall time of the native shader / pipeline creations (printed at exit into launch.log).
#   cold:   first launch with a new salt          warm:  second launch with the same salt
#   after:  tools/warm-shaders.sh --force (a short game launch) with a new salt, then a launch with that salt
#   afteri5 (with --i5): tools/i5-pipeline-test.sh (headless, build/client.jar) with a new salt, then a launch with it
# Startup-only launches (tools/startup-bench.py: quick play, static settings, stop 3 s after world ready).
# usage: tools/shader-warm-bench.sh [--i5] <prefix> <pairs> <afterRuns> [launch.py args, e.g. --jar build/client-r5.jar --bench-jar ...]
R="${0:A:h:h}"; cd "$R"
I5=0; [[ $1 == --i5 ]] && { I5=1; shift; }
P=$1; N=$2; M=$3; shift 3
[[ -f build/shadersalt.jar ]] || lockf -k /tmp/mc-gpu.lock zsh tools/shadersalt/build.sh || exit 1
SJ="$R/build/shadersalt.jar"
COMMON=(--api metal --warm-shaders off --quickplay on "$@")
timing() { grep -a -h "\[shadersalt\] shaderCreate" "run/$1/launch.log" 2>/dev/null | tail -1 | sed 's/.*\[shadersalt\] /    compile: /'; }
one() {  # one <prefix> <arm> <salt>
  python3 tools/startup-bench.py $1 1 "$2='--jvm=-javaagent:$SJ=$3,time'" -- $COMMON | grep -v "^rows\|^per-arm\|^arm\|^$2 \|^ *total\|^$"
  timing "$1-$2"
}
for i in $(seq $N); do
  S="$P-$(date +%s)-$RANDOM"
  one $P-cold$i cold $S
  one $P-warm$i warm $S
done
for j in $(seq $M); do
  S="$P-wu-$(date +%s)-$RANDOM"
  T0=$(date +%s)
  # = tools/warm-shaders.sh <jar> --force, plus the salt agent
  python3 tools/launch.py --warm-only --warm-shaders on --api metal --game-dir build/shader-warm/unused "$@" \
    "--jvm=-javaagent:$SJ=$S,time" > "results/raw/$P-warmup$j.out" 2>&1
  echo "warm-up $j: $(( $(date +%s) - T0 )) s: $(tail -1 results/raw/$P-warmup$j.out)"
  grep -a -h "\[shadersalt\] shaderCreate" build/shader-warm/metal/launch.log | tail -1 | sed 's/.*\[shadersalt\] /    warm-up compile: /'
  one $P-after$j after $S
done
if (( I5 )); then
  S="$P-i5-$(date +%s)-$RANDOM"
  T0=$(date +%s)
  lockf -k /tmp/mc-gpu.lock zsh tools/i5-pipeline-test.sh "-javaagent:$SJ=$S,time" > "results/raw/$P-i5.out" 2>&1
  echo "i5-pipeline-test: exit $? in $(( $(date +%s) - T0 )) s; $(grep -a '\[shadersalt\] shaderCreate' results/raw/$P-i5.out | tail -1)"
  one $P-afteri5 afteri5 $S
fi
python3 - "$P" <<'EOF'
import csv, glob, statistics, sys
p = sys.argv[1]
rows = []
for f in sorted(glob.glob(f"results/raw/{p}-*-startup.tsv")):
    rows += list(csv.DictReader(open(f), delimiter="\t"))
def num(v):
    try: return float(v)
    except (TypeError, ValueError): return None
print(f"\n{'arm':8s} {'n':>2s} {'reload':>8s} {'total':>8s}  (medians, ms; label:reload/total)")
for arm in ("cold", "warm", "after", "afteri5"):
    rs = [r for r in rows if r["arm"] == arm and r["ok"] == "True"]
    if not rs: continue
    med = lambda k: statistics.median([num(r[k]) for r in rs if num(r[k]) is not None])
    print(f"{arm:8s} {len(rs):2d} {med('p.reload'):8.0f} {med('p.total'):8.0f}   "
          + " ".join(f"{r['label']}:{num(r['p.reload']):.0f}/{num(r['p.total']):.0f}" for r in rs))
EOF
