#!/bin/zsh
# usage: tools/shoot.sh LABEL JAR scene1,scene2  -> results/shaders/LABEL/*.jpg
X=$1; J=$2; S=$3; R="$PWD"
lockf -k /tmp/mc-gpu.lock python3 tools/launch.py --jar $J --bench-jar build/bench.jar --game-dir run/scenes --api metal --opt fullscreen:true --opt chatVisibility:2 ${SHOOT_OPT:+--opt=$SHOOT_OPT} --jvm=-Dmc.shaders=true --jvm=-Dbench.only=$S ${SHOOT_JVM:+--jvm=$SHOOT_JVM} --bench results/raw/$X.json --label $X --bench-prop mode=scenes --bench-prop "shots=$R/run/shots-$X" > /tmp/$X.log 2>&1
echo "opengl-fallback: $(grep -c 'forcing preferred' run/scenes/logs/latest.log)"
grep -i "shader" run/scenes/logs/latest.log | grep -i "error\|fail" | head -3
mkdir -p results/shaders/$X
for f in run/shots-$X/screenshots/*.png; do sips -s format jpeg -Z 1400 "$f" --out results/shaders/$X/$(basename "${f%.png}").jpg >/dev/null; done
ls results/shaders/$X
