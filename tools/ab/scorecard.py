#!/usr/bin/env python3
"""Release scorecard runner (round 5 finalize): interleaved runs of frozen client jars at their *shipped defaults*
(no toggle properties), through bench-one.sh / bench-travel.sh (tools/launch.py holds the GPU lock). Validates every
result JSON and appends a row to results/raw/r5f-runs.tsv (summarize.py reads it with RUNS_TSV=results/raw/r5f-runs.tsv).

usage: scorecard.py <plan-file>
plan lines:  <static|travel|soak> <label> <arm> [extra launch.py args...]     (# comments allowed)
arms: see JARS below (r5 = build/client-r5.jar, r4 = build/client-r4.jar, both --api metal in run/metal; vk / gl = the
vanilla 26.3 jar on Vulkan / OpenGL with SDL_VIDEO_MAC_FULLSCREEN_SPACES=0 (the patched client sets that hint itself), in
their own game dir run/vanilla-static holding a copy of run/metal's "bench" world, so vanilla never rewrites the Metal
arms' options.txt).

Checks per run: the JSON exists with no error; renderDistance 12 static / 16 travel; config.jars client/bench sha1 equal
to the frozen files; no round-5 toggle property set (shipped defaults only); the effective round-5 state matches the arm:
late throttle enabled+active, RENAME_SMALL_WRITES, TRANSIENT_HYSTERESIS, and through -Dbench.configClasses the static
values of ClientLevel.FAST_ANIMATE_TICK, TextureAtlas.FAST_TEXTURE_ANIMATION, TravelHitchToggles.PREGROW /
UPLOAD_BUDGET_KB and EntityTexturePrefetch.ENABLED. MEMLOG=1 samples system memory once a second (runner.MemSampler).
"""
import hashlib, json, os, shlex, subprocess, sys, time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from runner import MemSampler  # noqa: E402

WT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
BJAR = os.environ.get("SCORECARD_BJAR", "build/bench-r5.jar")
JARS = {"r5": "build/client-r5.jar", "r4": "build/client-r4.jar", "r5i": "build/client-r5i.jar",
        "r5z": "build/client-r5z.jar", "r5off": "build/client-r5.jar",
        "vk": "mc/versions/26.3/26.3.jar", "gl": "mc/versions/26.3/26.3.jar"}
# r5off: client-r5.jar with every round-5 default switched off (localizes a difference between r4 / r5z and r5)
R5_OFF_FLAGS = ["--jvm=-Dmcmetal.lateThrottle=false", "--jvm=-Dmcmetal.renameSmallWrites=false", "--jvm=-Dmc.fastAnimateTick=false",
                "--jvm=-Dmc.fastTextureAnimation=false", "--jvm=-Dmc.terrain.pregrow=false"]
ARM_ARGS = {"r5": ["--api", "metal"], "r4": ["--api", "metal"], "r5i": ["--api", "metal"], "r5z": ["--api", "metal"],
            "r5off": ["--api", "metal"] + R5_OFF_FLAGS,
            "vk": ["--api", "vulkan", "--env", "SDL_VIDEO_MAC_FULLSCREEN_SPACES=0"],
            "gl": ["--api", "opengl", "--env", "SDL_VIDEO_MAC_FULLSCREEN_SPACES=0"]}
ARM_BACKEND = {"r5": "Metal", "r4": "Metal", "r5i": "Metal", "r5z": "Metal", "r5off": "Metal", "vk": "Vulkan", "gl": "OpenGL"}
VANILLA_GD = "run/vanilla-static"
# Expected effective round-5 state per arm (None = class/field absent in that jar).
SHIPPED_R5 = {"lateThrottle": True, "rename": True, "hysteresis": False, "fastAnimateTick": True,
              "fastTextureAnimation": True, "pregrow": json.loads(os.environ.get("R5_PREGROW", "true")),
              "uploadBudgetKB": 0, "asyncEntityTextures": False}
OFF = "off-or-absent"  # r4 has none of the round-5 toggles: each must be absent (None) or off (False / 0)
EXPECT = {"r5": SHIPPED_R5, "r4": {k: OFF for k in SHIPPED_R5}, "vk": {k: OFF for k in SHIPPED_R5},
          "gl": {k: OFF for k in SHIPPED_R5}, "r5z": {k: OFF for k in SHIPPED_R5}, "r5off": {k: OFF for k in SHIPPED_R5}}
R5_PROPS = ["mcmetal.lateThrottle", "mcmetal.renameSmallWrites", "mcmetal.transientHysteresis", "mc.fastAnimateTick",
            "mc.fastTextureAnimation", "mc.terrain.pregrow", "mc.asyncEntityTextures", "mc.terrain.uploadBudgetKB"]
CONFIG_CLASSES = ",".join([
    "net.minecraft.client.renderer.chunk.TravelHitchToggles",
    "net.minecraft.client.multiplayer.ClientLevel",
    "net.minecraft.client.renderer.texture.TextureAtlas",
    "net.minecraft.client.renderer.texture.EntityTexturePrefetch",
])


def sha1(path):
    h = hashlib.sha1()
    with open(path, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()


def effective(d):
    cfg = d.get("config") or {}
    tg = cfg.get("toggles") or {}
    lt = d.get("lateThrottle")
    g = lambda c, f: (tg.get(c) or {}).get(f)
    return {
        "lateThrottle": None if lt is None else bool(lt.get("enabled") and lt.get("active")),
        "rename": g("MetalCommandEncoder", "RENAME_SMALL_WRITES"),
        "hysteresis": g("MetalTransientMemory", "TRANSIENT_HYSTERESIS"),
        "fastAnimateTick": g("ClientLevel", "FAST_ANIMATE_TICK"),
        "fastTextureAnimation": g("TextureAtlas", "FAST_TEXTURE_ANIMATION"),
        "pregrow": g("TravelHitchToggles", "PREGROW"),
        "uploadBudgetKB": g("TravelHitchToggles", "UPLOAD_BUDGET_KB"),
        "asyncEntityTextures": g("EntityTexturePrefetch", "ENABLED"),
    }


def main():
    plan = [l.split("#", 1)[0].strip() for l in open(sys.argv[1])]
    plan = [l for l in plan if l]
    os.chdir(WT)
    shas = {a: sha1(j) for a, j in JARS.items() if os.path.exists(j)}
    bsha = sha1(BJAR)
    tsv = "results/raw/r5f-runs.tsv"
    if not os.path.exists(tsv):
        with open(tsv, "w") as f:
            f.write("time\tkind\tlabel\tarm\tbackend\trd\tmedian\tavg\tp1\tp01\tp99ms\tmaxms\tpresentedFps\tlt_enabled\tlt_active\t"
                    "lt_frames\tlt_tripFrames\tlt_blockedWaits\tlt_meanWaitMs\tprops_ok\tjars_ok\tnotes\n")
    for line in plan:
        parts = shlex.split(line)
        kind, label, arm, extra = parts[0], parts[1], parts[2], parts[3:]
        jar = JARS[arm]
        vanilla = arm in ("vk", "gl")
        args = ([] if vanilla else ["--jar", jar]) + ARM_ARGS[arm] + ["--bench-jar", BJAR, "--bench-prop",
                                                                      f"configClasses={CONFIG_CLASSES}"] + extra
        script = {"static": "tools/bench-one.sh", "travel": "tools/bench-travel.sh"}[kind]
        gd = VANILLA_GD if vanilla else "run/metal"
        if vanilla and kind != "static":
            raise SystemExit("vanilla arms: static only")
        logsrc = f"{gd}/launch.log" if kind == "static" else "run/metal-travel/launch.log"
        t0 = time.time()
        print(f"=== {time.strftime('%H:%M:%S')} {kind} {label} arm={arm} {' '.join(args)}", flush=True)
        mem = MemSampler(label) if os.environ.get("MEMLOG") == "1" else None
        if mem:
            mem.start()
        r = subprocess.run(["zsh", script, label, gd] + args, capture_output=True, text=True)
        if mem:
            mem.stop.set(); mem.join(3)
        print(r.stdout.strip(), r.stderr.strip()[-2000:], flush=True)
        subprocess.run(["cp", logsrc, f"results/raw/{label}.log"])
        notes, row, lt = [], dict(backend="?", rd="?", median=0, avg=0, p1=0, p01=0, p99=0, mx=0, pf=0), {}
        props_ok = jars_ok = False
        try:
            d = json.load(open(f"results/raw/{label}.json"))
            if "error" in d and "medianFps" not in d:
                notes.append("ERROR:" + str(d.get("error")))
            row.update(backend=d.get("backend"), rd=d.get("renderDistance"), median=d.get("medianFps", 0),
                       avg=d.get("avgFps", 0), p1=d.get("p1LowFps", 0), p01=d.get("p01LowFps", 0),
                       p99=d.get("p99FrameMs", 0), mx=d.get("maxFrameMs", 0),
                       pf=(d.get("present") or {}).get("presentedFps", 0))
            want_rd = 12 if kind == "static" else 16
            if row["rd"] != want_rd:
                notes.append(f"RD_MISMATCH({row['rd']}!={want_rd})")
            if row["backend"] != ARM_BACKEND[arm]:
                notes.append(f"BACKEND_MISMATCH({row['backend']})")
            if d.get("framebuffer") != "3600x2338":
                notes.append(f"FRAMEBUFFER({d.get('framebuffer')})")
            lt = d.get("lateThrottle") or {}
            cfg = d.get("config") or {}
            p = cfg.get("properties") or {}
            set_props = [k for k in R5_PROPS if k in p and not (arm == "r5off" and p[k] == "false")]
            eff = effective(d)
            want = EXPECT[arm] if arm in EXPECT else None
            ok = lambda k, v: eff[k] in (None, False, 0) if v == OFF else eff[k] == v
            bad = [] if want is None else [f"{k}={eff[k]}!={v}" for k, v in want.items() if not ok(k, v)]
            props_ok = not set_props and not bad and row["backend"] == ARM_BACKEND[arm] and row["rd"] == want_rd
            if set_props:
                notes.append("PROPS_SET:" + ",".join(set_props))
            if bad:
                notes.append("STATE:" + ",".join(bad))
            notes.append("eff=" + ",".join(f"{k}:{v}" for k, v in eff.items()))
            jars = cfg.get("jars") or {}
            jars_ok = (jars.get("client") or {}).get("sha1") == shas.get(arm) and (jars.get("bench") or {}).get("sha1") == bsha
            if not jars_ok:
                notes.append("JAR_SHA_MISMATCH")
        except Exception as e:
            notes.append(f"NOJSON:{e}")
        try:
            log = open(f"results/raw/{label}.log", errors="replace").read()
            if "Resizing Chunk Sections Command Buffer" in log:
                notes.append("resizeCSCB=%d" % log.count("Resizing Chunk Sections Command Buffer"))
            if "Metal heap reserve: on" in log:
                notes.append("heapReserve")
            for bad in ("5s timeout", "Exception in thread", "Unexpected error", "---- Minecraft Crash Report"):
                if bad in log:
                    notes.append("LOG:" + bad.replace(" ", "_"))
        except Exception as e:
            notes.append(f"NOLOG:{e}")
        if mem and mem.levels:
            notes.append(f"memfree={min(mem.levels)}/{sum(mem.levels)/len(mem.levels):.0f}%")
        notes.append(f"{time.time() - t0:.0f}s")
        with open(tsv, "a") as f:
            f.write("\t".join(str(x) for x in [time.strftime("%H:%M:%S"), kind, label, arm, row["backend"], row["rd"],
                    f"{row['median']:.1f}", f"{row['avg']:.1f}", f"{row['p1']:.1f}", f"{row['p01']:.1f}", f"{row['p99']:.3f}",
                    f"{row['mx']:.2f}", f"{row['pf']:.1f}", lt.get("enabled"), lt.get("active"), lt.get("frames"),
                    lt.get("tripFrames"), lt.get("blockedWaits"), lt.get("meanBlockedWaitMs"), props_ok, jars_ok,
                    " ".join(notes)]) + "\n")
        print(f"    -> rd={row['rd']} med={row['median']:.0f} avg={row['avg']:.0f} 1%={row['p1']:.0f} 0.1%={row['p01']:.0f} "
              f"p99={row['p99']:.2f} max={row['mx']:.1f} props_ok={props_ok} jars_ok={jars_ok} {' '.join(notes)}", flush=True)


if __name__ == "__main__":
    main()
