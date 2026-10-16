#!/usr/bin/env python3
"""Headless tests for tools/fastlaunch.py (no game, no lock): AOT key invalidation, derived short-launch commands,
quick-play decisions, result merging. usage: python3 tools/fastlaunch_test.py   -> "fastlaunch tests: N passed", exit 1 on
failure."""
import json, os, shutil, sys, tempfile, types, zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import fastlaunch

failures = []


def check(cond, what):
    if not cond:
        failures.append(what)
        print("FAIL:", what)


def ns(**kw):
    base = dict(label="t", api="metal", bench="/r/x.json", quickplay="auto", game_arg=[], aot=False, aot_train_only=False,
                warm_only=False, warm_shaders="off")
    base.update(kw)
    return types.SimpleNamespace(**base)


def jar(path, entries):
    with zipfile.ZipFile(path, "w") as z:
        for n, data in entries.items():
            z.writestr(n, data)


tmp = tempfile.mkdtemp()
try:
    java = os.path.join(tmp, "java")
    open(java, "w").write("x")
    lib = os.path.join(tmp, "lib.jar"); jar(lib, {"a.class": b"1"})
    client = os.path.join(tmp, "client.jar"); jar(client, {"com/mojang/renderpearl/backend/metal/M.class": b"m",
                                                           "assets/minecraft/shaders/core/a.vsh": b"v"})
    bench = os.path.join(tmp, "bench.jar"); jar(bench, {"bench/Startup.class": b"s"})
    old_bench = os.path.join(tmp, "old.jar"); jar(old_bench, {"bench/Bench.class": b"b"})
    gd = os.path.join(tmp, "g d")   # space in the path, as in "Application Support"

    def cmd(extra_jvm=(), cp=None):
        return [java, "-Xmx4G", "-XX:+UseZGC", f"-Djava.library.path={gd}/.natives/java", "-cp", cp or f"{lib}:{client}",
                f"-javaagent:{bench}", "-Dbench.out=/r/x.json", "-Dbench.mode=scenes", *extra_jvm, "Main", "--gameDir", gd,
                "--version", "26.3"]

    def key(c, a=None, b=bench, cj=client):
        return fastlaunch.Run(a or ns(), c, "Main", gd, cj, b, {}, 0, None).aot_key()[0]

    k0 = key(cmd())
    check(k0 == key(cmd(["-Dmcmetal.lateThrottle=false"])), "-D properties do not change the AOT key")
    check(k0 == key(cmd(["-javaagent:/x/salt.jar=1"])), "extra agents do not change the AOT key")
    check(k0 != key(cmd(["-XX:+AlwaysPreTouch"])), "a JVM flag changes the AOT key")
    check(k0 != key(cmd(), a=ns(api="vulkan")), "the API changes the AOT key")
    jar(client, {"com/mojang/renderpearl/backend/metal/M.class": b"m2", "assets/minecraft/shaders/core/a.vsh": b"v"})
    check(k0 != key(cmd()), "client jar content changes the AOT key")
    k1 = key(cmd())
    jar(bench, {"bench/Startup.class": b"s2"})
    check(k1 != key(cmd()), "bench jar content changes the AOT key")
    k2 = key(cmd())
    os.utime(lib, (1, 1))
    check(k2 != key(cmd()), "a library mtime changes the AOT key (the JVM validates it)")

    # shader inputs: Metal classes / shaders count, other classes do not
    s0 = fastlaunch.shader_inputs_id(client)
    jar(client, {"com/mojang/renderpearl/backend/metal/M.class": b"m2", "assets/minecraft/shaders/core/a.vsh": b"v",
                 "net/minecraft/Other.class": b"o"})
    check(s0 == fastlaunch.shader_inputs_id(client), "non-shader classes do not change the shader inputs id")
    jar(client, {"com/mojang/renderpearl/backend/metal/M.class": b"m2", "assets/minecraft/shaders/core/a.vsh": b"v2"})
    check(s0 != fastlaunch.shader_inputs_id(client), "a shader source changes the shader inputs id")
    vanilla = os.path.join(tmp, "vanilla.jar"); jar(vanilla, {"net/minecraft/A.class": b"a"})
    check(fastlaunch.shader_inputs_id(vanilla) is None, "no shader inputs id for a jar without the Metal backend")

    # derived short launch: game dir swapped, bench props replaced, quick play into "bench", extra JVM before Main
    r = fastlaunch.Run(ns(), cmd(["-Dbench.quickPlay=true"]) + ["--quickPlaySingleplayer", "w"], "Main", gd, client, bench, {}, 0, None)
    d = r.derived("/t/w", "/t/w/o.json", "warm", ["mode=perf"], ["-XX:AOTCacheOutput=/t/c"])
    mi = d.index("Main")
    check(f"-Djava.library.path=/t/w/.natives/java" in d and "--gameDir" in d and d[d.index("--gameDir") + 1] == "/t/w",
          "derived: game dir swapped")
    check(not any(x.startswith("-Dbench.mode=scenes") or x == "-Dbench.out=/r/x.json" for x in d), "derived: run's bench props removed")
    check(d.index("-XX:AOTCacheOutput=/t/c") < mi and f"-javaagent:{bench}" in d[:mi] and "-Dbench.mode=perf" in d[:mi],
          "derived: extra JVM, agent and new props before the main class")
    check(d[-2:] == ["--quickPlaySingleplayer", "bench"] and d.count("--quickPlaySingleplayer") == 1, "derived: quick play into bench")

    # quick-play decision
    os.makedirs(os.path.join(gd, "saves", "bench"))
    qa = lambda **kw: fastlaunch.quickplay_args(ns(**kw), kw.pop("props", {}) if False else kw.get("_props", {}), kw.get("_snap"), gd,
                                                "bench", kw.get("_bench", bench))[1]
    check(qa(_props={"mode": "zoomseq"}) == [], "auto: no quick play without level.dat")
    open(os.path.join(gd, "saves", "bench", "level.dat"), "w").write("x")
    check(qa(_props={"mode": "zoomseq"}) == ["--quickPlaySingleplayer", "bench"], "auto: quick play for zoomseq in an existing world")
    check(qa(_props={"exitAfterReady": "3"}) == ["--quickPlaySingleplayer", "bench"], "auto: quick play for a startup-only run")
    check(qa(_props={"mode": "scenes"}) == [], "auto: no quick play for scenes (title-path references)")
    check(qa(quickplay="on", _props={"mode": "scenes"}) == ["--quickPlaySingleplayer", "bench"], "on: forced")
    check(qa(_props={"mode": "travel"}) == [], "auto: no quick play for travel (fresh world)")
    check(qa(_props={"join": "h:1"}) == [], "auto: no quick play for a LAN guest")
    check(qa(_bench=old_bench, _props={"mode": "zoomseq"}) == [], "auto: no quick play with a bench jar without bench.quickPlay")
    check(qa(quickplay="off") == [], "off")
    check(qa(bench=None) == [], "no quick play without --bench")

    # merge keeps the agent's bytes and appends members; creates a JSON for startup-only runs
    out = os.path.join(tmp, "r.json")
    original = '{\n  "label": "x",\n  "medianFps": 1.000,\n  "perSecondFps": [1, 2]\n}\n'
    open(out, "w").write(original)
    open(out + ".startup.json", "w").write('{"phases": {"total": 5.0}}\n')
    fastlaunch.merge_results(out, {"label": "x", "quickPlay": True})
    text = open(out).read()
    j = json.loads(text)
    check(text.startswith(original.rstrip()[:-1].rstrip()) and j["startup"]["phases"]["total"] == 5.0 and j["launch"]["quickPlay"],
          "merge: original kept, startup + launch appended")
    check(not os.path.exists(out + ".startup.json"), "merge: side file removed")
    one = os.path.join(tmp, "s.json")
    open(one, "w").write('{"label": "x", "error": "scenes complete", "pinnedWorld": "p"}\n')
    fastlaunch.merge_results(one, {"label": "x"})
    check(json.loads(open(one).read())["error"] == "scenes complete", "merge: one-line scenes JSON")
    none = os.path.join(tmp, "n.json")
    open(none + ".startup.json", "w").write('{"phases": {}}')
    fastlaunch.merge_results(none, {"label": "n"})
    check(json.load(open(none))["mode"] == "startup", "merge: startup-only JSON created")
finally:
    shutil.rmtree(tmp)

print(f"fastlaunch tests: {'ALL PASSED' if not failures else str(len(failures)) + ' FAILED'}")
sys.exit(1 if failures else 0)
