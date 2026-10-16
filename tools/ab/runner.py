#!/usr/bin/env python3
"""Round-5 A/B runner: runs a queue of bench runs sequentially (bench-one.sh / bench-travel.sh, which go through
tools/launch.py and its GPU lock), saves each launch.log next to the JSON, validates the effective config and appends a
summary row to results/raw/r5ab-runs.tsv.

usage: runner.py <plan-file>
plan-file lines:  <static|travel> <label> <arm> [extra launch.py args...]     (# comments allowed)
"""
import json, os, shlex, subprocess, sys, threading, time

WT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
JAR = "build/client-r5i.jar"
BJAR = "build/bench-r5h.jar"
CLIENT_SHA = "0484a2be85652c4111c6a8dd0f12a69a1985aead"
BENCH_SHA = "efcc28e88b19e055d69d17fb2c7ba350060946c5"

ARMS = {
    "off": [],
    "A": ["mcmetal.lateThrottle=true"],
    "C": ["mcmetal.renameSmallWrites=true", "mcmetal.transientHysteresis=true"],
    "AC": ["mcmetal.lateThrottle=true", "mcmetal.renameSmallWrites=true", "mcmetal.transientHysteresis=true"],
    "ACD": ["mcmetal.lateThrottle=true", "mcmetal.renameSmallWrites=true", "mcmetal.transientHysteresis=true",
            "mc.fastAnimateTick=true", "mc.fastTextureAnimation=true"],
    "AD": ["mcmetal.lateThrottle=true", "mc.fastAnimateTick=true", "mc.fastTextureAnimation=true"],
    "AR": ["mcmetal.lateThrottle=true", "mcmetal.renameSmallWrites=true"],
    "AH": ["mcmetal.lateThrottle=true", "mcmetal.transientHysteresis=true"],
    "R": ["mcmetal.renameSmallWrites=true"],
    "H": ["mcmetal.transientHysteresis=true"],
    "WIN": ["mcmetal.lateThrottle=true", "mcmetal.renameSmallWrites=true", "mc.fastAnimateTick=true",
            "mc.fastTextureAnimation=true", "mc.terrain.pregrow=true"],
    "ACDE": ["mcmetal.lateThrottle=true", "mcmetal.renameSmallWrites=true", "mcmetal.transientHysteresis=true",
             "mc.fastAnimateTick=true", "mc.fastTextureAnimation=true", "mc.terrain.pregrow=true",
             "mc.asyncEntityTextures=true"],
    # finalize (round 5): WIN without pregrow, WIN without the fast tick pair
    "ARD": ["mcmetal.lateThrottle=true", "mcmetal.renameSmallWrites=true", "mc.fastAnimateTick=true",
            "mc.fastTextureAnimation=true"],
    "ARP": ["mcmetal.lateThrottle=true", "mcmetal.renameSmallWrites=true", "mc.terrain.pregrow=true"],
}


class MemSampler(threading.Thread):
    """Samples system memory once a second while a run is in flight (MEMLOG=1): kern.memorystatus_level (the
    'System-wide memory free percentage' of memory_pressure), free pages and swap used. Written to
    results/raw/<label>.mem.tsv; the run row gets min/mean free%."""

    def __init__(self, label):
        super().__init__(daemon=True)
        self.path = f"results/raw/{label}.mem.tsv"
        self.stop = threading.Event()
        self.levels = []

    def run(self):
        with open(self.path, "w") as f:
            f.write("t\tfreePct\tfreePages\tswapUsedMB\n")
            while not self.stop.is_set():
                try:
                    o = subprocess.run(["sysctl", "-n", "kern.memorystatus_level", "vm.page_free_count", "vm.swapusage"],
                                       capture_output=True, text=True).stdout.split("\n")
                    lvl = int(o[0]); pages = int(o[1])
                    used = o[2].split("used = ")[1].split("M")[0]
                    f.write(f"{time.time():.1f}\t{lvl}\t{pages}\t{used}\n"); f.flush()
                    self.levels.append(lvl)
                except Exception:
                    pass
                self.stop.wait(1.0)


def arm_props(arm):
    if arm in ARMS:
        return list(ARMS[arm])
    # composite: base+prop=value,prop=value
    if "+" in arm:
        base, extra = arm.split("+", 1)
        return list(ARMS[base]) + extra.split(",")
    raise SystemExit(f"unknown arm {arm}")


def main():
    plan = [l.split("#", 1)[0].strip() for l in open(sys.argv[1])]
    plan = [l for l in plan if l]
    os.chdir(WT)
    tsv = "results/raw/r5ab-runs.tsv"
    if not os.path.exists(tsv):
        with open(tsv, "w") as f:
            f.write("time\tkind\tlabel\tarm\tbackend\trd\tmedian\tavg\tp1\tp01\tp99ms\tmaxms\tpresentedFps\tlt_enabled\tlt_active\t"
                    "lt_frames\tlt_tripFrames\tlt_blockedWaits\tlt_meanWaitMs\tprops_ok\tjars_ok\tnotes\n")
    for line in plan:
        parts = shlex.split(line)
        kind, label, arm, extra = parts[0], parts[1], parts[2], parts[3:]
        props = arm_props(arm)
        args = ["--jar", JAR, "--bench-jar", BJAR] + [f"--jvm=-D{p}" for p in props] + extra
        script = "tools/bench-one.sh" if kind == "static" else "tools/bench-travel.sh"
        gd = "run/metal"
        logsrc = "run/metal/launch.log" if kind == "static" else "run/metal-travel/launch.log"
        t0 = time.time()
        print(f"=== {time.strftime('%H:%M:%S')} {kind} {label} arm={arm} {' '.join(args)}", flush=True)
        mem = MemSampler(label) if os.environ.get("MEMLOG") == "1" else None
        if mem: mem.start()
        r = subprocess.run(["zsh", script, label, gd] + args, capture_output=True, text=True)
        if mem:
            mem.stop.set(); mem.join(3)
        print(r.stdout.strip(), r.stderr.strip()[-2000:], flush=True)
        try:
            subprocess.run(["cp", logsrc, f"results/raw/{label}.log"])
        except Exception:
            pass
        notes = []
        row = dict(backend="?", rd="?", median=0, avg=0, p1=0, p01=0, p99=0, mx=0, pf=0)
        lt = {}
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
            lt = d.get("lateThrottle") or {}
            cfg = d.get("config") or {}
            p = cfg.get("properties") or {}
            want = dict(x.split("=", 1) for x in props)
            missing = [k for k, v in want.items() if p.get(k) != v]
            # nothing else from the round-5 set may be on
            r5 = ["mcmetal.lateThrottle", "mcmetal.renameSmallWrites", "mcmetal.transientHysteresis", "mc.fastAnimateTick",
                  "mc.fastTextureAnimation", "mc.terrain.pregrow", "mc.asyncEntityTextures", "mc.terrain.uploadBudgetKB"]
            leaked = [k for k in r5 if k in p and k not in want and p[k] not in ("false", "0")]
            props_ok = not missing and not leaked
            if missing: notes.append("MISSING:" + ",".join(missing))
            if leaked: notes.append("LEAKED:" + ",".join(leaked))
            lt_want = want.get("mcmetal.lateThrottle") == "true"
            if lt.get("enabled") != lt_want or lt.get("active") != lt_want:
                notes.append(f"LT_STATE(enabled={lt.get('enabled')},active={lt.get('active')})")
                props_ok = False
            tg = cfg.get("toggles") or {}
            ren = (tg.get("MetalCommandEncoder") or {}).get("RENAME_SMALL_WRITES")
            hyst = (tg.get("MetalTransientMemory") or {}).get("TRANSIENT_HYSTERESIS")
            if ren is not None and ren != (want.get("mcmetal.renameSmallWrites") == "true"):
                notes.append(f"RENAME_STATE({ren})"); props_ok = False
            if hyst is not None and hyst != (want.get("mcmetal.transientHysteresis") == "true"):
                notes.append(f"HYST_STATE({hyst})"); props_ok = False
            jars = cfg.get("jars") or {}
            jars_ok = (jars.get("client") or {}).get("sha1") == CLIENT_SHA and (jars.get("bench") or {}).get("sha1") == BENCH_SHA
            if not jars_ok: notes.append("JAR_SHA_MISMATCH")
            if "frameTrace" in extra or any("frameTrace" in a for a in extra):
                ft = cfg.get("frameTrace") or {}
                notes.append(f"ft={ft.get('enabled')}/{ft.get('metalStatus')}")
            nat = cfg.get("native") or {}
            meas = cfg.get("measured") or {}
            notes.append(f"fused={meas.get('fusedShare')} mailbox={nat.get('mailboxActive')}")
        except Exception as e:
            notes.append(f"NOJSON:{e}")
        # log checks
        try:
            log = open(f"results/raw/{label}.log", errors="replace").read()
            if "mc.fastAnimateTick.verify:" in log: notes.append("VERIFY_MISMATCH")
            if "batched RNG off" in log: notes.append("BATCHRNG_OFF")
            if "Resizing Chunk Sections Command Buffer" in log: notes.append("resizeCSCB=%d" % log.count("Resizing Chunk Sections Command Buffer"))
            if "Metal heap reserve: on" in log: notes.append("heapReserve")
            if "Entity texture prefetch" in log: notes.append("prefetch")
            for bad in ("5s timeout", "Exception in thread", "Unexpected error", "---- Minecraft Crash Report"):
                if bad in log: notes.append("LOG:" + bad.replace(" ", "_"))
        except Exception as e:
            notes.append(f"NOLOG:{e}")
        if mem and mem.levels:
            notes.append(f"memfree={min(mem.levels)}/{sum(mem.levels)/len(mem.levels):.0f}%")
        dur = time.time() - t0
        notes.append(f"{dur:.0f}s")
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
