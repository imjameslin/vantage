#!/usr/bin/env python3
"""Same-session in-game A/B screens (stage 3 resumption, 2026-10-09).

One launch = 8x10 s ABBA blocks of a runtime toggle (bench mode=abab, segments=1), through tools/launch.py's
timing lane (Prism guard, overlay + quiet gates, 3024x1964 Default check). Settings = the handoff-checkpoint baseline:
512/tau2/gpuMB6144, validation off, item/group caches + atomic publication on, async/handoff/reuse off.

  python3 -B tools/lod/ab-run.py LABEL SCENARIO TOGGLES [--rd N] [--jar J] [--jvm X ...] [--prop k=v ...]
  python3 -B tools/lod/ab-run.py --summary results/raw/LABEL.json [...]

SCENARIO: standing (heavy fixed view, y180), spin (10 s turn at spawn), flying (warm 10.9 b/s circular route).
Summary leads with lows: 1% low, 0.1% low, worst ms, frame SD ms, then avg and median FPS (B relative to A).
"""
import json, math, os, signal, statistics, subprocess, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OPTS = ["ao:true", "biomeBlendRadius:2", "chunkSectionFadeInTime:0.75", "cutoutLeaves:true", "entityDistanceScaling:1.0",
        "entityShadows:true", 'graphicsPreset:"custom"', "prioritizeChunkUpdates:0", "maxAnisotropyBit:2",
        "textureFiltering:0", "improvedTransparency:false", "mipmapLevels:4", "particles:0", 'renderClouds:"true"',
        "cloudRange:128", "simulationDistance:12", "weatherRadius:10", "menuBackgroundBlurriness:5",
        "fullscreen:true", "enableVsync:false", "maxFps:260", "farsightQuality:10", "renderScale:17"]
JVM = {"mc.lod": "true", "mc.lod.reuse": "false", "mc.lod.draw": "true", "mc.lod.radius": "256", "mc.lod.tau": "5", "mc.lod.treeMin": "2",
       "mc.lod.gpuMB": "6144", "mc.lod.wave": "false", "mc.lod.hiz": "false", "mc.lod.solidQuery": "false",
       "mc.lod.area": "false", "mc.lod.secOccl": "true", "mc.lod.layerMask": "7", "mc.lod.tauLevels": "",
       "mc.lod.flat0Px": "0", "mc.lod.hysteresis": "0", "mc.lod.meshCache": "true", "mc.lod.palette": "false",
       "mc.lod.directQuads": "false", "mc.lod.async": "false", "mc.lod.asyncItems": "false",
       "mc.lod.cacheItems": "true", "mc.lod.cacheGroups": "true", "mc.lod.cacheNodeItems": "true",
       "mc.lod.atomicMeshHandoff": "true", "mc.lod.cacheGroups.diagnostics": "false", "mc.lod.nearHandoff": "false"}
COMMON = ["mode=abab", "world=bench", "abab.order=abba", "abab.blocks=8", "abab.blockSeconds=10", "abab.segments=1",
          "abab.minCell=20", "abab.frames=true", "abab.stop=false", "abab.stopOn=median2,pairP99", "warmup=30",
          "lodWait=420", "timeout=585", "config=true", "photo=0", "f3=off", "frameTrace=false",
          "cmds=gamerule advance_time false;gamerule advance_weather false;gamerule random_tick_speed 0;"
          "gamerule max_snow_accumulation_height 0;time set 6000;weather clear",
          "measureCmds=time set 6000", "configClasses=net.minecraft.client.renderer.chunk.Lod,"
          "net.minecraft.client.renderer.chunk.LodGpu", "abab.warmupSwitch=5", "abab.lodCounters=true",
          "abab.loopDiscardSeconds=0.5"]
SCEN = {"standing": ["abab.preset=heavy", "abab.bins=1", "abab.discard=30", "spin=Infinity", "pos=6.5,180,6.5",
                     "pitch=18", "yaw=200", "abab.heavyYaw=200"],
        "spin": ["abab.preset=spin", "abab.bins=4", "abab.discard=30", "spin=10", "pos=6.5,106,6.5", "pitch=12",
                 "yaw=0"],
        "flying": ["abab.preset=travelLoop", "abab.bins=10", "abab.discard=0", "speed=10.9"],
        # Straight flight into land never loaded in this world (server generation + cold Farsight data).
        "travel": ["abab.preset=travel", "abab.bins=10", "abab.discard=0", "speed=10.9"]}


def run(argv):
    label, scen, toggles = argv[:3]
    rest = argv[3:]
    rd, jar, bench = "8", "build/client-base.jar", "build/bench-base.jar"
    jvm, props, opts, game, raw, env, gargs = dict(JVM), [], [], "run/s3-palette-check", [], [], []
    i = 0
    while i < len(rest):
        k, v = rest[i], rest[i + 1]
        i += 2
        if k == "--bare": jvm = {} if v == "true" else jvm   # shipping defaults only (no mc.lod.* overrides)
        elif k == "--rd": rd = v
        elif k == "--jar": jar = v
        elif k == "--bench-jar": bench = v
        elif k == "--game-dir": game = v
        elif k == "--jvm": a, b = v.split("=", 1); jvm[a] = b
        elif k == "--prop": props.append(v)
        elif k == "--raw-jvm": raw.append(v)
        elif k == "--opt": opts.append(v)
        elif k == "--env": env.append(v)
        elif k == "--game-arg": gargs.append(v)
        else: sys.exit("unknown " + k)
    cmd = [sys.executable, "-B", str(ROOT / "tools/launch.py"), "--jar", str(ROOT / jar), "--bench-jar",
           str(ROOT / bench), "--game-dir", str(ROOT / game), "--api", "metal", "--lane", "timing", "--bench",
           f"results/raw/{label}.json", "--label", label, "--quiet-gate", "4.0:900", "--overlay-check",
           "--overlay-wait", "900"] + ([] if os.environ.get("AB_KEEP_WORLD") else ["--world-snapshot", str(ROOT / "run/pinned/async-phase0-world"), "--world-only"])
    for o in OPTS + [f"renderDistance:{rd}"] + opts:
        cmd += ["--opt", o]
    cmd += [f"--jvm=-D{k}={v}" for k, v in jvm.items()] + [f"--jvm={v}" for v in raw]
    for p in COMMON + SCEN[scen] + [f"abab.toggles={toggles}"] + props:
        cmd += ["--bench-prop", p]
    cmd += ["--env", "MTL_DEBUG_LAYER=0", "--env", "MTL_SHADER_VALIDATION=0"]
    for e in env: cmd += ["--env", e]
    for g in gargs: cmd += [f"--game-arg={g}"]
    (ROOT / "build/ab").mkdir(parents=True, exist_ok=True)
    (ROOT / f"build/ab/{label}-command.json").write_text(json.dumps(cmd, indent=1))
    with open(ROOT / f"build/ab/{label}.log", "w") as out:
        p = subprocess.Popen(cmd, cwd=ROOT, stdout=out, stderr=subprocess.STDOUT, start_new_session=True)
        try:
            rc = p.wait(timeout=1800)
        except subprocess.TimeoutExpired:
            os.killpg(p.pid, signal.SIGTERM)
            p.wait()
            sys.exit("deadline")
    print(label, "exit", rc)
    # A crashed startup makes the next launch fall back to OpenGL (no Metal renderer, no Farsight): refuse that run.
    log = (ROOT / game / "logs/latest.log").read_text(errors="replace") if (ROOT / game / "logs/latest.log").exists() else ""
    if "forcing preferred graphics API to OpenGL" in log:
        print(label, "INVALID: the game fell back to OpenGL (unexpected-shutdown reset); rerun")
        return 3
    if '"residentSections": 0,' in log and '"residentSections": 0,' in log.rsplit('"state"', 1)[-1]:
        print(label, "WARNING: Farsight not loaded at the end (residentSections 0)")
    if rc == 0:
        summary([f"results/raw/{label}.json"])
    return rc


def arms(path):
    d = json.loads((ROOT / path).read_text())
    L = d["launch"]
    ok = (L["exit"] == 0 and L["display"]["pixels"] == "3024x1964" and L["display"]["default"]
          and L["display"]["hz"] == 120 and not L["overlays"] and L["lane"] == "timing")
    a = d["abab"]
    f = a["frames"]
    out = {}
    for arm in ("A", "B"):
        t = [ns / 1e6 for ns, blk, keep in zip(f["dtNs"], f["block"], f["keep"]) if keep and a["blocks"][blk]["arm"] == arm]
        s = a["arms"][arm]
        out[arm] = dict(lo1=s["p1LowFps"], lo01=s["p01LowFps"], worst=s["maxFrameMs"], sd=statistics.pstdev(t),
                        avg=s["avgFps"], med=s["medianFps"], ms=statistics.mean(t), gpu=s.get("gpuMedianMs"), n=len(t))
    return d, ok, out


def summary(paths):
    print("| run | gates | 1% low | 0.1% low | worst ms | SD ms | avg FPS | median FPS | mean ms |")
    print("|---|---|---:|---:|---:|---:|---:|---:|---:|")
    for p in paths:
        d, ok, o = arms(p)
        A, B = o["A"], o["B"]
        f = lambda k, n=1: f"{A[k]:.{n}f}→{B[k]:.{n}f}"
        print(f"| {Path(p).stem} | {'ok' if ok else 'CHECK'} | {f('lo1')} | {f('lo01')} | {f('worst')} | {f('sd', 2)} |"
              f" {f('avg')} | {f('med')} | {f('ms', 2)} |")


if __name__ == "__main__":
    if sys.argv[1] == "--summary":
        summary(sys.argv[2:])
    else:
        sys.exit(run(sys.argv[1:]))
