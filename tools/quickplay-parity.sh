#!/bin/zsh
# Quick play must not change any parity verdict (results/LAUNCH-SPEED.md). Runs, through tools/ab/scenes.sh (fresh game
# dir, pinned world restored by launch.py):
#   <P>-vkqp      vanilla Vulkan scenes with quick play      -> vs run/pinned/shots-vkns-1512x982[-vs] (made on the title path),
#   <P>-vkqp-zq   vanilla Vulkan zoomseq with quick play     -> vs run/pinned/zoomseq-vkns-1512x982,
#   <P>-mtl-title / <P>-mtl-qp   <client jar> Metal scenes on the title path / with quick play -> both vs the reference;
# then ImgDiff (strict) and PerceptualDiff (with the two pinned vanilla floor runs) for each, and a scene-by-scene
# verdict comparison of the two Metal runs.
# usage: tools/quickplay-parity.sh <prefix> <client jar> <bench jar>
R="${0:A:h:h}"; cd "$R"
P=$1; CJ=$2; BJ=$3
J="$R/mc/runtime/java-runtime-epsilon/jre.bundle/Contents/Home/bin/java"
# References made by the same scene runner: a bench jar that settles the vignette (bench/README.md) compares against the
# -vs set, older jars against the original one.
SUF=""; unzip -p "$BJ" bench/Scenes.class 2>/dev/null | LC_ALL=C grep -qa settleVignette && SUF="-vs"
REF=run/pinned/shots-vkns-1512x982$SUF/screenshots
F2=run/pinned/shots-vkns-1512x982$SUF-2/screenshots; F3=run/pinned/shots-vkns-1512x982$SUF-3/screenshots
FX=(); [[ -n $SUF ]] && for n in 4 5 6 7; do FX+=($REF run/pinned/shots-vkns-1512x982-vs-$n/screenshots); done   # bench/README.md
VK=(--api vulkan --env SDL_VIDEO_MAC_FULLSCREEN_SPACES=0)
SCENES_JAR=mc/versions/26.3/26.3.jar SCENES_BJAR=$BJ zsh tools/ab/scenes.sh $P-vkqp scenes $VK
SCENES_JAR=mc/versions/26.3/26.3.jar SCENES_BJAR=$BJ zsh tools/ab/scenes.sh $P-vkqp-zq zoomseq $VK
SCENES_JAR=$CJ SCENES_BJAR=$BJ zsh tools/ab/scenes.sh $P-mtl-title scenes --quickplay off --warm-shaders off
SCENES_JAR=$CJ SCENES_BJAR=$BJ zsh tools/ab/scenes.sh $P-mtl-qp scenes --warm-shaders off
for L in $P-vkqp $P-mtl-title $P-mtl-qp; do
  echo "=== $L: quickPlay $(python3 -c "import json,sys; d=json.load(open(sys.argv[1])); print(d.get('launch',{}).get('quickPlay'), d.get('error'), d.get('pinnedWorld'), {k: v.get('pass') for k, v in (d.get('entityChecks') or {}).items()}, 'ready', d.get('startup',{}).get('phases',{}).get('total'))" results/raw/scenes-$L.json)"
  "$J" tools/ImgDiff.java $REF run/shots-$L/screenshots run/diff-$L > results/raw/$L-imgdiff.txt 2>&1; echo "ImgDiff exit $?"
  "$J" tools/PerceptualDiff.java $REF run/shots-$L/screenshots --floor $REF $F2 $F2 $F3 $FX > results/raw/$L-perceptual.txt 2>&1; echo "PerceptualDiff exit $?"
done
echo "=== $P-vkqp-zq: $(head -c 300 results/raw/scenes-$P-vkqp-zq.json)"
"$J" tools/ImgDiff.java run/pinned/zoomseq-vkns-1512x982/screenshots/zoomseq run/shots-$P-vkqp-zq/screenshots/zoomseq run/diff-$P-vkqp-zq \
  > results/raw/$P-vkqp-zq-imgdiff.txt 2>&1; echo "zoomseq ImgDiff exit $? ($(grep -c png run/shots-$P-vkqp-zq/screenshots/zoomseq/index.csv 2>/dev/null || ls run/shots-$P-vkqp-zq/screenshots/zoomseq | wc -l) frames)"
python3 - $P <<'EOF'
import sys, re
p = sys.argv[1]
def verdicts(path):
    out = {}
    for line in open(path):
        f = line.split()
        if len(f) >= 5 and f[0] != "scene" and re.match(r"^-?[0-9.]+$", f[1]):
            if "perceptual" in path:   # "region at" is empty or "x,y WxH": take the first verdict word after the numbers
                out[f[0]] = next((t for t in f[5:] if t in ("ok", "noise", "noisy", "FAIL")), "?")
            else:
                out[f[0]] = f[-1]
    return out
for kind in ("imgdiff", "perceptual"):
    a = verdicts(f"results/raw/{p}-mtl-title-{kind}.txt"); b = verdicts(f"results/raw/{p}-mtl-qp-{kind}.txt")
    v = verdicts(f"results/raw/{p}-vkqp-{kind}.txt")
    diff = {k: (a.get(k), b.get(k)) for k in sorted(set(a) | set(b)) if a.get(k) != b.get(k)}
    print(f"{kind}: metal title vs quick play: {len(a)} / {len(b)} scenes, verdicts differ in {diff or 'none'}; "
          f"vanilla quick play vs reference: {sum(1 for x in v.values() if x in ('ok', 'noise', 'pass', 'PASS'))} of {len(v)} ok/noise/pass, "
          f"others {dict((k, x) for k, x in v.items() if x not in ('ok', 'noise', 'pass', 'PASS'))}")
EOF
