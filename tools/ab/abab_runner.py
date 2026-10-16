#!/usr/bin/env python3
"""Runs a queue of in-session A/B runs (bench mode=abab) and classic full-launch runs (the bench-one.sh protocol), one
game at a time through tools/launch.py (GPU lock), and records for every run what decides whether it counts:

  * the CoreGraphics display mode (tools/swift/displaymode.swift; only the user's Default mode, 1512x982 pt =
    3024x1964 px, counts: other runs are kept but marked DISCARD) and the overlays (tools/swift/overlays.swift), both
    checked once the game process has started (so after the wait for the GPU lock, which can take minutes) and again
    right after it exits; the fullscreen framebuffer (3024x1964);
  * the load average sampled every 5 s while the game runs (mean / max; the pre-launch value is kept as loadBefore);
  * the presentation state (presented share of the frames; per block and per arm for abab runs): a run whose blocks
    differ by more than 0.04 is flagged PRESENT-MIXED (the overlay / compositing state changed during the run);
  * the wall time of the launch (minus the time spent waiting for the GPU lock) next to the measured seconds;
  * the client / bench jar sha1 from the result's config block, and for abab runs the toggle values read back;
  * the game's launch.log, copied to results/raw/<label>.log. launch.py truncates <game-dir>/launch.log when the next
    launch with the same game dir takes the GPU lock, so two queues sharing a game dir lost logs (0-byte copies): this
    runner holds an exclusive lock on <game-dir>/.abab-runner.lock from before the launch until the log is copied, and
    flags LOG-MISSING when the copy is empty or has no RESULT line.

The swift helpers are compiled once into build/tools-bin/ (a swift script run with `swift` compiles for seconds of CPU
time, which must not land next to a measurement).

usage: abab_runner.py <plan-file> [--tsv results/raw/abab-runs.tsv]
plan lines (whole-line # comments only):
  abab    <label> <client-jar> [launch.py args...]     e.g. --bench-prop abab.toggles=bench.Abab#aaNoop=0|1
  classic <label> <client-jar> [launch.py args...]     e.g. --jvm=-Dmc.hud.vignetteSkipNoop=false
Defaults: --game-dir run/abab-static (env ABAB_GD), --api metal, fullscreen, tools/static-options.txt, bench jar
build/bench-abab.jar (env ABAB_BENCH_JAR), classic: warmup 15 / seconds 30 (bench-one.sh), abab: warmup 15. Later
--bench-prop values override these (the JVM keeps the last -D of a name).
"""
import fcntl, json, os, re, shlex, subprocess, sys, threading, time

WT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
BIN = os.path.join(WT, "build", "tools-bin")


def helper(name):
    """Compiled tools/swift/<name>.swift (built once; falls back to the interpreter)."""
    src = os.path.join(WT, "tools", "swift", name + ".swift")
    exe = os.path.join(BIN, name)
    if not os.path.exists(exe) or os.path.getmtime(exe) < os.path.getmtime(src):
        os.makedirs(BIN, exist_ok=True)
        r = subprocess.run(["swiftc", "-O", src, "-o", exe], capture_output=True, text=True)
        if r.returncode != 0:
            return ["swift", src]
    return [exe]


def display_mode():
    try:
        out = subprocess.run(helper("displaymode"), capture_output=True, text=True, timeout=120).stdout.strip().splitlines()
        return json.loads(out[-1]) if out else {"error": "no output"}
    except Exception as e:  # noqa: BLE001
        return {"error": str(e)}


def overlays(own_pid=None):
    """'clean' or 'composited:<owners>' (tools/check-overlays.sh's rule: layers 1..999 but the Window Server and Dock;
    windows of our own game process (own_pid) are not overlays)."""
    try:
        out = subprocess.run(helper("overlays"), capture_output=True, text=True, timeout=120).stdout
    except Exception as e:  # noqa: BLE001
        return "?(" + str(e) + ")"
    owners = [o for pid, o in re.findall(r"pid=(-?\d+) owner=(.+?) name=", out)
              if o not in ("Window Server", "Dock") and (own_pid is None or int(pid) != own_pid)]
    return "clean" if not owners else "composited:" + ";".join(sorted(set(owners)))


def load1():
    try:
        return float(subprocess.run(["sysctl", "-n", "vm.loadavg"], capture_output=True, text=True).stdout.split()[1])
    except Exception:  # noqa: BLE001
        return float("nan")


def mode_text(m):
    if "error" in m:
        return "?(" + m["error"] + ")"
    return "%dx%d@%dx%d/%gHz%s" % (m["width"], m["height"], m["pixelWidth"], m["pixelHeight"], m["refreshHz"],
                                    "" if m.get("default") else "!")


def static_opts():
    opts = []
    for line in open(os.path.join(WT, "tools/static-options.txt")):
        line = line.strip()
        if line:
            opts += ["--opt", line]
    return opts


def sha_of(cfg, key):
    return ((cfg.get("jars") or {}).get(key) or {}).get("sha1")


class GameWatch(threading.Thread):
    """While launch.py runs: waits for its java child (= the GPU lock is held and the game started), then checks the
    display mode and overlays once and samples the load every 5 s until launch.py exits."""

    def __init__(self, proc):
        super().__init__(daemon=True)
        self.proc = proc
        self.started = None
        self.java_pid = None
        self.mode = {"error": "game never started"}
        self.overlays = "?"
        self.loads = []

    def run(self):
        while self.proc.poll() is None:
            r = subprocess.run(["pgrep", "-P", str(self.proc.pid), "-f", "^/.*/bin/java "], capture_output=True, text=True)
            if r.stdout.strip():
                self.started = time.time()
                self.java_pid = int(r.stdout.split()[0])
                break
            time.sleep(2)
        if self.started is None:
            return
        self.mode = display_mode()
        self.overlays = overlays(self.java_pid)
        while self.proc.poll() is None:
            self.loads.append(load1())
            for _ in range(10):
                if self.proc.poll() is not None:
                    break
                time.sleep(0.5)


def present_info(d):
    """(overall presented share, per-arm text, max spread of the per-block shares, flag)."""
    p = d.get("present") or {}
    share = p.get("presentedShare")
    ab = d.get("abab")
    if not ab:
        return share, "", 0.0, ""
    arms = "/".join("%.3f" % ab["arms"][a]["presentedShare"] if ab["arms"][a].get("presentedShare") is not None else "-"
                    for a in ("A", "B"))
    shares = []
    for b in ab.get("blocks", []):
        pr, pf = b.get("presented"), b.get("presentFrames")
        if pr and pf and sum(pf) > 0:
            shares.append(sum(pr) / sum(pf))
    spread = max(shares) - min(shares) if shares else 0.0
    return share, arms, spread, "PRESENT-MIXED(%.3f)" % spread if spread > 0.04 else ""


def main():
    args = sys.argv[1:]
    tsv = "results/raw/abab-runs.tsv"
    if "--tsv" in args:
        i = args.index("--tsv")
        tsv = args[i + 1]
        del args[i:i + 2]
    # Whole-line comments only: toggle specs contain '#' (fqcn#field).
    plan = [l.strip() for l in open(args[0])]
    plan = [l for l in plan if l and not l.startswith("#")]
    os.chdir(WT)
    gd = os.environ.get("ABAB_GD", "run/abab-static")
    bjar = os.environ.get("ABAB_BENCH_JAR", "build/bench-abab.jar")
    helper("displaymode")
    helper("overlays")
    header = ("time\tkind\tlabel\tjar\twallS\tlockWaitS\tmeasuredS\tmodeAtStart\tmodeAfter\tcounts\tframebuffer\toverlaysAtStart\t"
              "overlaysAfter\tloadBefore\tloadDuring\tpresentedShare\tpresentedArms\tclientSha\tbenchSha\tsummary\tnotes\n")
    if not os.path.exists(tsv):
        with open(tsv, "w") as f:
            f.write(header)
    elif open(tsv).readline() != header:
        with open(tsv, "a") as f:
            f.write("# columns from here on:\n#" + header)
    for line in plan:
        parts = shlex.split(line)
        kind, label, jar, extra = parts[0], parts[1], parts[2], parts[3:]
        out = f"results/raw/{label}.json"
        if os.path.exists(out):
            os.remove(out)   # a failed launch must not be summarised from a stale JSON
        before = subprocess.run(["zsh", "tools/wait-quiet.sh", "4.0", "900"], capture_output=True, text=True).stdout.strip()
        cmd = ["python3", "tools/launch.py", "--jar", jar, "--game-dir", gd, "--api", "metal", "--opt", "fullscreen:true"]
        cmd += static_opts()
        cmd += ["--bench", out, "--label", label, "--bench-jar", bjar]
        if kind == "abab":
            cmd += ["--bench-prop", "mode=abab", "--bench-prop", "warmup=15"]
        elif kind == "classic":
            cmd += ["--bench-prop", "warmup=15", "--bench-prop", "seconds=30"]
        else:
            raise SystemExit(f"unknown kind {kind}")
        cmd += extra
        print(f"=== {time.strftime('%H:%M:%S')} {kind} {label} {jar} {' '.join(extra)}  [before: {before}]", flush=True)
        os.makedirs(gd, exist_ok=True)
        gdlock = open(os.path.join(gd, ".abab-runner.lock"), "w")
        fcntl.flock(gdlock, fcntl.LOCK_EX)   # no other runner may launch into this game dir until our log is copied
        try:
            t0 = time.time()
            proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            watch = GameWatch(proc)
            watch.start()
            stdout, stderr = proc.communicate()
            wall = time.time() - t0
            watch.join(timeout=10)
            waited = 0.0
            mw = re.search(r"waited (\d+)s for the game lock", stderr)
            if mw:
                waited = float(mw.group(1))
            m1 = display_mode()
            ov1 = overlays()
            log = f"results/raw/{label}.log"
            subprocess.run(["cp", os.path.join(gd, "launch.log"), log])
        finally:
            fcntl.flock(gdlock, fcntl.LOCK_UN)
            gdlock.close()
        notes = []
        try:
            logtext = open(log, errors="replace").read()
        except OSError:
            logtext = ""
        if not logtext or "[bench] RESULT" not in logtext:
            notes.append("LOG-MISSING")
        if proc.returncode != 0:
            notes.append(f"exit={proc.returncode}")
        if stderr.strip():
            notes.append("stderr:" + stderr.strip().replace("\n", " | ")[-300:])
        summary = ""
        fb = "?"
        csha = bsha = "?"
        measured = 0.0
        share, arms, flag = None, "", ""
        try:
            d = json.load(open(out))
            if "error" in d and "medianFps" not in d:
                notes.append("ERROR:" + str(d.get("error")))
            fb = d.get("framebuffer", "?")
            cfg = d.get("config") or {}
            csha, bsha = sha_of(cfg, "client"), sha_of(cfg, "bench")
            if d.get("renderDistance") != 12:
                notes.append(f"RD={d.get('renderDistance')}")
            if d.get("backend") != "Metal":
                notes.append(f"backend={d.get('backend')}")
            share, arms, _, flag = present_info(d)
            if flag:
                notes.append(flag)
            ab = d.get("abab")
            if ab:
                measured = ab.get("secondsRun", 0.0)
                eff = ab.get("effects", {})

                def ef(k):
                    e = eff.get(k) or {}
                    c = e.get("pctCi") or [None, None]
                    if e.get("pctChange") is None or c[0] is None:
                        return f"{k} n/a"
                    return f"{k} {e['pctChange']:+.2f}% [{c[0]:+.2f},{c[1]:+.2f}]{' CALL' if e.get('call') else ''}"
                summary = ("time change B vs A: " + "; ".join(ef(k) for k in ("median2", "pairP99", "mean", "p99", "gpuMedian"))
                           + f" | blocks {ab.get('blocksRun')}/{ab.get('blocksPlanned')} wouldStop {ab['stop'].get('wouldStopAfterPairs')}")
                for t in ab.get("toggles", []):
                    if str(t.get("readBackA")) != str(t.get("a")) or str(t.get("readBackB")) != str(t.get("b")):
                        notes.append(f"READBACK({t.get('spec')}: {t.get('readBackA')}/{t.get('readBackB')})")
            else:
                measured = d.get("seconds", 0.0)
                summary = (f"median {d.get('medianFps', 0):.1f} avg {d.get('avgFps', 0):.1f} 1% {d.get('p1LowFps', 0):.1f} "
                           f"0.1% {d.get('p01LowFps', 0):.1f} max {d.get('maxFrameMs', 0):.1f}ms "
                           f"presented {(d.get('present') or {}).get('presentedFps', 0):.1f}")
        except Exception as e:  # noqa: BLE001
            notes.append(f"NOJSON:{e}")
        m0 = watch.mode
        loads = [x for x in watch.loads if x == x]
        during = "mean %.2f max %.2f (%d samples)" % (sum(loads) / len(loads), max(loads), len(loads)) if loads else "-"
        counts = bool(m0.get("default")) and bool(m1.get("default")) and fb == "3024x1964" and not any(
            n.startswith(("ERROR", "NOJSON", "exit", "READBACK", "RD=", "backend")) for n in notes)
        with open(tsv, "a") as f:
            f.write("\t".join(str(x) for x in [
                time.strftime("%H:%M:%S"), kind, label, jar, f"{wall - waited:.1f}", f"{waited:.0f}", f"{measured:.1f}",
                mode_text(m0), mode_text(m1), "ok" if counts else "DISCARD", fb, watch.overlays, ov1, before, during,
                "-" if share is None else f"{share:.4f}", arms or "-", csha, bsha, summary, " ".join(notes)]) + "\n")
        print(f"    -> {'ok' if counts else 'DISCARD'} wall {wall - waited:.0f}s (lock wait {waited:.0f}s) fb {fb} "
              f"mode {mode_text(m0)} -> {mode_text(m1)} overlays {watch.overlays} -> {ov1} load during {during} "
              f"presented {share} {arms} | {summary} {' '.join(notes)}", flush=True)


if __name__ == "__main__":
    main()
