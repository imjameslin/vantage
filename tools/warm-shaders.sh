#!/bin/zsh
# Warm the OS Metal shader cache for a client jar (docs/stage2/STAGE2-PLAN.md W8a, results/LAUNCH-SPEED.md): one short
# game launch (quick play into a fresh copy of the pinned scenes world, windowed, Metal, 2 s in the world, quit; ~13 s) with
# the same java binary as every game run, so the OS caches (per user and bundle id net.java.openjdk.java, in
# $(getconf DARWIN_USER_CACHE_DIR)net.java.openjdk.java/com.apple.metal{,fe}) hold every MSL function the game compiles.
# The next launch's reload then skips ~170 cold compiles (17 s of CPU, +1.6 to +2.5 s to world ready).
#
# Worth it only right before startup measurements of a jar whose MSL changed: the warm-up costs more wall time than it saves.
# Run it outside the build's lock (it takes the machine-wide game lock itself through launch.py):
#   lockf -k /tmp/mc-gpu.lock zsh tools/build-client.sh && tools/warm-shaders.sh build/client.jar
# The cache also evicts entries on its own under many different jars, so check the run's startup.phases.reload anyway.
# Default: skip when this jar's shader inputs (shader sources, renderpearl classes, RenderPipelines, native libraries) were
# warmed before with this java (stamp build/shader-warm/<inputs>-<java>); --force warms again. `launch.py --warm-shaders auto`
# does the same before a bench run; an AOT training run (launch.py --aot) and tools/i5-pipeline-test.sh warm it too.
#
# usage: tools/warm-shaders.sh <client jar> [--force] [launch.py args, e.g. --bench-jar build/bench-X.jar]
R="${0:A:h:h}"
JAR=${1:A}; shift
MODE=auto; [[ $1 == --force ]] && { MODE=on; shift; }
exec python3 "$R/tools/launch.py" --warm-only --warm-shaders $MODE --jar "$JAR" --api metal --game-dir "$R/build/shader-warm/unused" "$@"
