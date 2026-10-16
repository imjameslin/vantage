#!/usr/bin/env python3
"""Default-mode scorecard runner (results/BASELINE-DEFAULT.md): classic interleaved full launches of frozen jars through
tools/bench-one.sh (static: pinned tools/static-options.txt, RD 12) and tools/bench-travel.sh (Fancy, RD 16, fresh world),
vanilla included in both, with the checks tools/ab/scorecard.py makes plus the ones tools/ab/abab_runner.py records:

  * the CoreGraphics display mode once the game process runs (after the GPU-lock wait) and again after it exits; only
    the user's Default mode (1512x982 pt = 3024x1964 px) and a 3024x1964 framebuffer count, other runs are kept but
    marked DISCARD (docs/GOAL.md, "Display mode");
  * overlays at game start (our own window excluded) and after; the load average every 5 s during the run;
  * render distance, backend, client / bench jar sha1, no toggle property set, and the effective shipped state of the
    patched jars (round-5 toggles as scorecard.py; HudVignetteSkip.enabled for the main build = W1);
  * the game's launch.log copied to results/raw/<label>.log under <game dir>/.abab-runner.lock (the lock abab_runner.py
    takes too), so a following launch into the same dir cannot truncate it first.

usage: scorecard_default.py <plan-file> [--tsv results/raw/bd-runs.tsv]
plan lines (whole-line # comments only):  <static|travel> <label> <arm> [extra launch.py args...]
arms: gl / vk = vanilla 26.3 on OpenGL / Vulkan with SDL_VIDEO_MAC_FULLSCREEN_SPACES=0 (the patched client sets that hint
itself), game dir run/bd-vanilla; r4 / r5 = build/client-r4.jar / client-r5.jar, main = build/client-bd-main.jar
(SCORECARD_MAIN_JAR), glass = build/client-bd-glass.jar (SCORECARD_GLASS_JAR: main + the MCMTL_GLASS_TRACE diagnostic),
all --api metal in run/bd-metal. Travel adds "-travel" to the game dir (bench-travel.sh). Bench jar build/bench-bd.jar
(SCORECARD_BJAR). An extra "--opt renderDistance:N" changes the expected render distance; "{ROOT}" in an argument is replaced
by the checkout root (absolute paths with spaces, e.g. DYLD_INSERT_LIBRARIES={ROOT}/build/glass/libglassobs.dylib).
"""
import fcntl, json, os, re, shlex, subprocess, sys, threading, time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import scorecard  # noqa: E402  (effective(), sha1(), SHIPPED_R5, R5_PROPS, CONFIG_CLASSES)
import abab_runner  # noqa: E402  (display_mode(), overlays(), load1(), mode_text())

WT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
BJAR = os.environ.get("SCORECARD_BJAR", "build/bench-bd.jar")
VANILLA_JAR = "mc/versions/26.3/26.3.jar"
JARS = {"r4": "build/client-r4.jar", "r5": "build/client-r5.jar",
        "main": os.environ.get("SCORECARD_MAIN_JAR", "build/client-bd-main.jar"),
        "glass": os.environ.get("SCORECARD_GLASS_JAR", "build/client-bd-glass.jar"),
        "gl": VANILLA_JAR, "vk": VANILLA_JAR}
NONSPACES = ["--env", "SDL_VIDEO_MAC_FULLSCREEN_SPACES=0"]
ARM_ARGS = {"gl": ["--api", "opengl"] + NONSPACES, "vk": ["--api", "vulkan"] + NONSPACES,
            "r4": ["--api", "metal"], "r5": ["--api", "metal"], "main": ["--api", "metal"], "glass": ["--api", "metal"]}
BACKEND = {"gl": "OpenGL", "vk": "Vulkan", "r4": "Metal", "r5": "Metal", "main": "Metal", "glass": "Metal"}
GD = {"gl": "run/bd-vanilla", "vk": "run/bd-vanilla"}   # others: run/bd-metal
OFF = scorecard.OFF
W1 = "net.minecraft.client.gui.HudVignetteSkip"
EXPECT = {"r4": dict({k: OFF for k in scorecard.SHIPPED_R5}, vignetteSkip=None),
          "r5": dict(scorecard.SHIPPED_R5, vignetteSkip=None),
          "main": dict(scorecard.SHIPPED_R5, vignetteSkip=True),
          "glass": dict(scorecard.SHIPPED_R5, vignetteSkip=True)}
PROPS = scorecard.R5_PROPS + ["mc.hud.vignetteSkipNoop"]
FB = "3024x1964"


def effective(d):
    eff = scorecard.effective(d)
    tg = ((d.get("config") or {}).get("toggles") or {})
    eff["vignetteSkip"] = (tg.get("HudVignetteSkip") or {}).get("enabled")
    return eff


class GameWatch(threading.Thread):
    """Waits for the java process of this label (bench-one.sh -> launch.py -> java: a grandchild, found by its
    -Dbench.label), then checks the display mode and overlays once and samples the load every 5 s until it exits."""

    def __init__(self, proc, label):
        super().__init__(daemon=True)
        self.proc, self.label = proc, label
        self.java_pid = None
        self.mode = {"error": "game never started"}
        self.overlays = "?"
        self.loads = []

    def run(self):
        pat = f"bin/java .*-Dbench[.]label={self.label} "
        while self.proc.poll() is None:
            r = subprocess.run(["pgrep", "-f", pat], capture_output=True, text=True)
            pids = [int(p) for p in r.stdout.split() if p.strip() and int(p) != os.getpid()]
            if pids:
                self.java_pid = pids[0]
                break
            time.sleep(2)
        if self.java_pid is None:
            return
        time.sleep(3)   # past the window creation
        self.mode = abab_runner.display_mode()
        self.overlays = abab_runner.overlays(self.java_pid)
        while self.proc.poll() is None:
            self.loads.append(abab_runner.load1())
            for _ in range(10):
                if self.proc.poll() is not None:
                    break
                time.sleep(0.5)


def expected_rd(kind, extra):
    rd = 12 if kind == "static" else 16
    for i, x in enumerate(extra):
        if x == "--opt" and i + 1 < len(extra) and extra[i + 1].startswith("renderDistance:"):
            rd = int(extra[i + 1].split(":", 1)[1])
    return rd


def main():
    args = sys.argv[1:]
    tsv = "results/raw/bd-runs.tsv"
    if "--tsv" in args:
        i = args.index("--tsv")
        tsv = args[i + 1]
        del args[i:i + 2]
    plan = [l.strip() for l in open(args[0])]
    plan = [l for l in plan if l and not l.startswith("#")]
    os.chdir(WT)
    abab_runner.helper("displaymode")
    abab_runner.helper("overlays")
    shas = {a: scorecard.sha1(j) for a, j in JARS.items() if os.path.exists(j)}
    bsha = scorecard.sha1(BJAR)
    cfg_classes = scorecard.CONFIG_CLASSES + "," + W1
    header = ("time\tkind\tlabel\tarm\tbackend\trd\tmedian\tavg\tp1\tp01\tp99ms\tmaxms\tpresentedFps\twallS\tlockWaitS\t"
              "modeAtStart\tmodeAfter\tcounts\tframebuffer\toverlaysAtStart\toverlaysAfter\tloadDuring\tchecks_ok\tnotes\n")
    if not os.path.exists(tsv):
        with open(tsv, "w") as f:
            f.write(header)
    for line in plan:
        parts = shlex.split(line)
        kind, label, arm, extra = parts[0], parts[1], parts[2], [x.replace("{ROOT}", WT) for x in parts[3:]]
        vanilla = arm in ("gl", "vk")
        gd = GD.get(arm, "run/bd-metal")
        script = {"static": "tools/bench-one.sh", "travel": "tools/bench-travel.sh"}[kind]
        rgd = gd if kind == "static" else gd + "-travel"
        cmd = ["zsh", script, label, gd] + ([] if vanilla else ["--jar", JARS[arm]]) + ARM_ARGS[arm] + [
            "--bench-jar", BJAR, "--bench-prop", f"configClasses={cfg_classes}"] + extra
        print(f"=== {time.strftime('%H:%M:%S')} {kind} {label} arm={arm} {' '.join(cmd[2:])}", flush=True)
        os.makedirs(rgd, exist_ok=True)
        gdlock = open(os.path.join(rgd, ".abab-runner.lock"), "w")
        fcntl.flock(gdlock, fcntl.LOCK_EX)
        try:
            t0 = time.time()
            proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            watch = GameWatch(proc, label)
            watch.start()
            stdout, stderr = proc.communicate()
            wall = time.time() - t0
            watch.join(timeout=10)
            m1 = abab_runner.display_mode()
            ov1 = abab_runner.overlays()
            subprocess.run(["cp", os.path.join(rgd, "launch.log"), f"results/raw/{label}.log"])
            glass_csv = os.path.join(rgd, f"{label}.glass.csv")   # MCMTL_GLASS_TRACE_OUT=<label>.glass.csv (game dir = cwd)
            if os.path.exists(glass_csv):
                os.replace(glass_csv, f"results/raw/{label}.glass.csv")
        finally:
            fcntl.flock(gdlock, fcntl.LOCK_UN)
            gdlock.close()
        mw = re.search(r"waited (\d+)s for the game lock", stderr)
        waited = float(mw.group(1)) if mw else 0.0
        print(stdout.strip(), stderr.strip()[-1500:], flush=True)
        notes = []
        row = dict(backend="?", rd="?", median=0, avg=0, p1=0, p01=0, p99=0, mx=0, pf=0)
        fb = "?"
        checks_ok = False
        try:
            d = json.load(open(f"results/raw/{label}.json"))
            if "error" in d and "medianFps" not in d:
                notes.append("ERROR:" + str(d.get("error")))
            row.update(backend=d.get("backend"), rd=d.get("renderDistance"), median=d.get("medianFps", 0),
                       avg=d.get("avgFps", 0), p1=d.get("p1LowFps", 0), p01=d.get("p01LowFps", 0),
                       p99=d.get("p99FrameMs", 0), mx=d.get("maxFrameMs", 0),
                       pf=(d.get("present") or {}).get("presentedFps", 0))
            fb = d.get("framebuffer", "?")
            want_rd = expected_rd(kind, extra)
            bad = []
            if row["rd"] != want_rd:
                bad.append(f"RD_MISMATCH({row['rd']}!={want_rd})")
            if row["backend"] != BACKEND[arm]:
                bad.append(f"BACKEND_MISMATCH({row['backend']})")
            cfg = d.get("config") or {}
            p = cfg.get("properties") or {}
            set_props = [k for k in PROPS if k in p]
            if set_props:
                bad.append("PROPS_SET:" + ",".join(set_props))
            if not vanilla:
                eff = effective(d)
                ok = lambda k, v: eff[k] in (None, False, 0) if v == OFF else eff[k] == v
                st = [f"{k}={eff[k]}!={v}" for k, v in EXPECT[arm].items() if not ok(k, v)]
                if st:
                    bad.append("STATE:" + ",".join(st))
                notes.append("eff=" + ",".join(f"{k}:{v}" for k, v in eff.items()))
            jars = cfg.get("jars") or {}
            if (jars.get("client") or {}).get("sha1") != shas.get(arm) or (jars.get("bench") or {}).get("sha1") != bsha:
                bad.append("JAR_SHA_MISMATCH")
            notes = bad + notes
            checks_ok = not bad
        except Exception as e:  # noqa: BLE001
            notes.append(f"NOJSON:{e}")
        try:
            log = open(f"results/raw/{label}.log", errors="replace").read()
            if "[bench] RESULT" not in log:
                notes.append("LOG-MISSING")
            for b in ("5s timeout", "Exception in thread", "Unexpected error", "---- Minecraft Crash Report"):
                if b in log:
                    notes.append("LOG:" + b.replace(" ", "_"))
        except OSError as e:
            notes.append(f"NOLOG:{e}")
        if proc.returncode != 0:
            notes.append(f"exit={proc.returncode}")
        m0 = watch.mode
        loads = [x for x in watch.loads if x == x]
        during = "mean %.2f max %.2f (%d)" % (sum(loads) / len(loads), max(loads), len(loads)) if loads else "-"
        counts = bool(m0.get("default")) and bool(m1.get("default")) and fb == FB and checks_ok
        with open(tsv, "a") as f:
            f.write("\t".join(str(x) for x in [
                time.strftime("%H:%M:%S"), kind, label, arm, row["backend"], row["rd"], f"{row['median']:.1f}",
                f"{row['avg']:.1f}", f"{row['p1']:.1f}", f"{row['p01']:.1f}", f"{row['p99']:.3f}", f"{row['mx']:.2f}",
                f"{row['pf']:.1f}", f"{wall - waited:.0f}", f"{waited:.0f}", abab_runner.mode_text(m0), abab_runner.mode_text(m1),
                "ok" if counts else "DISCARD", fb, watch.overlays, ov1, during, checks_ok, " ".join(notes)]) + "\n")
        print(f"    -> {'ok' if counts else 'DISCARD'} rd={row['rd']} med={row['median']:.0f} avg={row['avg']:.0f} "
              f"1%={row['p1']:.0f} 0.1%={row['p01']:.0f} max={row['mx']:.1f}ms fb={fb} mode {abab_runner.mode_text(m0)} -> "
              f"{abab_runner.mode_text(m1)} overlays {watch.overlays} -> {ov1} load {during} wall {wall - waited:.0f}s "
              f"(lock wait {waited:.0f}s) {' '.join(notes)}", flush=True)


if __name__ == "__main__":
    main()
