#!/bin/zsh
# Train (if missing) the JDK 25 AOT cache that `launch.py --aot` uses for one configuration (docs/stage2/STAGE2-PLAN.md W4,
# results/LAUNCH-SPEED.md). Pass the launch.py arguments of the runs that will use it: the cache is keyed by the java
# binary, the JVM flags except -D properties (Mojang's defaults plus every --jvm), the classpath (client jar sha1,
# library sizes / mtimes), the bench agent jar's sha1 and --api; any change selects (and trains) a different cache, so a
# rebuilt client jar or a new JVM flag can never run with a stale one. launch.py --aot trains on first use by itself;
# this script only does it ahead of time (e.g. right after a build, so the first timed run does not wait for training).
#
# Training = one short game launch under the machine-wide game lock: quick play into a fresh copy of the pinned scenes
# world (run/pinned/scenes), windowed, 5 s in the world, then quit; the JVM writes the cache at exit
# (-XX:AOTCacheOutput, one-step JEP 514 workflow). Under ZGC the cache holds class data only (no archived heap objects, no
# AOT-linked classes, no JIT profiles). A Metal training run also warms the OS Metal shader cache (as warm-shaders.sh).
# Caches live in build/aot/<key>.aot with <key>.json listing their inputs; the newest 8 are kept.
# Saves ~0.55 s per launch (results/LAUNCH-SPEED.md). For functional and startup runs only: static timing runs with the cache
# showed higher max frame times / lower 1% lows, so FPS benchmarks run without --aot.
#
# usage: tools/aot-train.sh [--jar build/client-X.jar] [--api metal] [--bench-jar build/bench-X.jar] [--jvm=-XX:...]...
#   prints the cache path; exit 1 if training failed.
R="${0:A:h:h}"
exec python3 "$R/tools/launch.py" --aot-train-only --game-dir "$R/build/aot-train/unused" "$@"
