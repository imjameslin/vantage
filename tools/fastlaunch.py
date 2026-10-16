#!/usr/bin/env python3
"""Faster game launches for tests, used by tools/launch.py (docs/stage2/STAGE2-PLAN.md W0c / W4 / W8a;
results/LAUNCH-SPEED.md has the measurements). Three independent pieces plus the result blocks:

quick play  (--quickplay auto|on|off)  The bench run starts with vanilla's --quickPlaySingleplayer <world> and
            -Dbench.quickPlay=true: the game opens the world when the resource reload ends (inside the loading overlay's
            onFinish), so loading the world overlaps the overlay's 2 s fade instead of following it (-0.75 s to world
            ready). The bench waits for the level instead of the title screen (Bench.WAIT_TITLE, as for bench.join);
            world-ready is detected exactly as before (level, player, no screen, no overlay). auto (default) = on only for
            runs where it was verified to change nothing (quickplay_verified: zoomseq, startup-only runs), the world exists,
            there is no bench.join and the bench jar has bench/Startup.class. Not for scenes (the water animation phase in
            water_surface differs from the title-path references) nor static/travel timing runs (see results/LAUNCH-SPEED.md).
AOT cache   (--aot, or MC_AOT=1; off by default)  JDK 25 AOT cache (JEP 483 / 514; under ZGC class data only: no archived
            heap objects, no AOT-linked classes, no JIT profiles): -0.55 s to world ready. Keyed by the java binary, the JVM
            flags except -D properties and agents, the classpath (client jar sha1, library sizes / mtimes: what the JVM
            validates), the bench jar's sha1 and the API; a missing cache is trained first (a short launch with
            -XX:AOTCacheOutput, under the same lock hold, ~20 s), so a changed input always means a new cache.
            build/aot/<key>.aot (+ <key>.json: inputs), newest 8 kept. tools/aot-train.sh trains ahead of time. The result's
            launch.aot.mapped says whether the JVM used it. Not for timing runs: static runs with it had higher max frame
            times / lower 1% lows (results/LAUNCH-SPEED.md); use it for functional and startup runs.
shader warm (--warm-shaders auto|on|off; off by default)  Metal: the OS caches compiled shaders per user and bundle id (the
            java runtime's net.java.openjdk.java); a jar whose MSL changed compiles ~170 functions (~17 s of CPU, +1.6 to
            +2.5 s to world ready) at its first reload. auto = before the first Metal bench run of a jar whose shader inputs
            (shader sources, renderpearl classes, RenderPipelines, native libraries; zip CRCs) are new, a short warm-up
            launch (~13 s) compiles them with the same java binary (stamp build/shader-warm/<inputs>-<java>); on = always.
            Only worth it before startup measurements: the warm-up costs more than the compile it saves.
            tools/warm-shaders.sh does it after a build; an AOT training run and tools/i5-pipeline-test.sh warm it as well.

Short launches (AOT training, warm-up) reuse the real run's command with another game dir (build/aot-train/<api>,
build/shader-warm/<api>: a fresh copy of run/pinned/scenes, windowed), quick play and -Dbench.exitAfterReady (the bench
agent stops the game that long after world ready). They need a bench jar with bench/Startup.class.

Every bench result gets "startup" (bench/src/bench/Startup.java, phase stamps from process start to world ready; the agent
writes <bench.out>.startup.json, merged here and deleted) and "launch" (quick play, AOT key / training / mapped, warm-up,
the CG display mode, load average and lock wait at start, wall time).
"""
import fcntl, hashlib, json, os, subprocess, sys, time, zipfile

import pin

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PINNED_SCENES = os.path.join(ROOT, "run", "pinned", "scenes")
AOT_DIR = os.path.join(ROOT, "build", "aot")
WARM_DIR = os.path.join(ROOT, "build", "shader-warm")
DEFAULT_LOCK = "/tmp/mc-gpu.lock"
AOT_KEEP = 8


def add_args(ap):
    ap.add_argument("--quickplay", choices=["auto", "on", "off"], default="auto",
                    help="bench runs in an existing world start with --quickPlaySingleplayer (tools/fastlaunch.py)")
    ap.add_argument("--aot", action="store_true", default=os.environ.get("MC_AOT") == "1",
                    help="use (train if missing) the JDK AOT cache for this configuration (tools/fastlaunch.py; MC_AOT=1)")
    ap.add_argument("--warm-shaders", choices=["auto", "on", "off"], default=os.environ.get("MC_WARM_SHADERS", "off"),
                    help="warm the OS Metal shader cache before a new jar's first Metal run (default off; tools/fastlaunch.py)")
    ap.add_argument("--aot-train-only", action="store_true", help=argparse_suppress())   # tools/aot-train.sh
    ap.add_argument("--warm-only", action="store_true", help=argparse_suppress())        # tools/warm-shaders.sh


def argparse_suppress():
    import argparse
    return argparse.SUPPRESS


def sha1_file(path):
    h = hashlib.sha1()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def jar_has(jar, entry):
    try:
        with zipfile.ZipFile(jar) as z:
            z.getinfo(entry)
            return True
    except (OSError, KeyError, zipfile.BadZipFile):
        return False


def bench_jar_new(jar):
    """Startup timeline, bench.quickPlay and bench.exitAfterReady: absent in older frozen bench jars."""
    return bool(jar) and jar_has(jar, "bench/Startup.class")


def default_bench_jar():
    return os.path.join(ROOT, "build", "bench.jar")


def shader_inputs_id(client_jar):
    """Hash of what can change the MSL the Metal backend compiles or how: shader sources, renderpearl classes,
    RenderPipelines, native libraries (zip CRCs, no decompression). None without the Metal backend (vanilla jar)."""
    h = hashlib.sha1()
    metal = False
    with zipfile.ZipFile(client_jar) as z:
        for info in sorted(z.infolist(), key=lambda i: i.filename):
            n = info.filename
            if n.startswith("com/mojang/renderpearl/backend/metal/"):
                metal = True
            if (n.startswith("assets/minecraft/shaders/") or n.startswith("com/mojang/renderpearl/")
                    or n.startswith("net/minecraft/client/renderer/RenderPipelines") or n.endswith((".dylib", ".metal"))):
                h.update(f"{n}\0{info.CRC:08x}\0{info.file_size}\n".encode())
    return h.hexdigest()[:16] if metal else None


def java_id(java):
    real = os.path.realpath(java)
    st = os.stat(real)
    return f"{real}:{st.st_size}:{int(st.st_mtime)}"


def display_mode():
    """Main display's CG mode {"points", "pixels", "hz", "builtin", "default"} (tools/swift/displaymode.swift, the helper
    tools/ab/abab_runner.py uses too, compiled once into build/displaymode); None if unavailable. Read-only."""
    exe = os.path.join(ROOT, "build", "displaymode")
    src = os.path.join(ROOT, "tools", "swift", "displaymode.swift")
    try:
        if not os.path.exists(exe) or os.path.getmtime(exe) < os.path.getmtime(src):
            os.makedirs(os.path.dirname(exe), exist_ok=True)
            tmp = f"{exe}.{os.getpid()}.tmp"   # (functional-lane launches may compile it at the same time)
            subprocess.run(["xcrun", "swiftc", "-O", "-o", tmp, src], check=True, capture_output=True, timeout=120)
            os.replace(tmp, exe)
        out = subprocess.run([exe], capture_output=True, text=True, timeout=10).stdout.strip().splitlines()
        m = json.loads(out[-1])
        return {"points": f"{m['width']}x{m['height']}", "pixels": f"{m['pixelWidth']}x{m['pixelHeight']}",
                "hz": m["refreshHz"], "builtin": m.get("builtin"), "default": m.get("default")}
    except Exception:
        return None


def quickplay_verified(props):
    """Runs for which quick play was shown to give the same verdicts / numbers (results/LAUNCH-SPEED.md): zoomseq (64 of 64
    frames identical to the title-path reference) and startup-only runs (bench.exitAfterReady). Not scenes: the world opens
    at a different client tick, so the water texture animation phase in water_surface differs deterministically from the
    title-path references (perceptual FAIL there, vanilla and Metal alike). Not travel / soak (fresh world each run: no
    world to quick-play into). Not the perf (static) timing runs: see QUICKPLAY_PERF."""
    mode = props.get("mode", "perf")
    if "exitAfterReady" in props or mode == "zoomseq":
        return True
    return mode == "perf" and QUICKPLAY_PERF


# Static timing runs: E5/E9 in results/LAUNCH-SPEED.md decide this.
QUICKPLAY_PERF = False


def quickplay_args(a, props, snapshot, game_dir, world, bench_jar):
    """(extra JVM args, extra game args) for quick play, decided from the options and the game dir as it is now (a
    snapshot restore under the lock only replaces an existing world with another)."""
    if a.quickplay == "off" or not a.bench:
        return [], []
    if a.quickplay == "auto":
        if not bench_jar_new(bench_jar) or "join" in props or not quickplay_verified(props):
            return [], []
        if any(g.startswith("--quickPlay") for g in a.game_arg):
            return [], []
        if not snapshot and not os.path.isfile(os.path.join(game_dir, "saves", world, "level.dat")):
            return [], []
    return ["-Dbench.quickPlay=true"], ["--quickPlaySingleplayer", world]


class Run:
    """One launch.py invocation: the real command plus what the hooks learned (the "launch" result block)."""

    def __init__(self, a, cmd, main_class, game_dir, client_jar, bench_jar, env, waited, write_options, primary_lock=None,
                 hold=None):
        self.a, self.cmd, self.main_class, self.game_dir = a, list(cmd), main_class, game_dir
        self.write_options = write_options   # launch.py's benchmark-neutral options writer
        self.client_jar, self.bench_jar, self.env = client_jar, bench_jar, env
        self.hold = hold   # tools/gpulock.py hold of this launch (short launches inherit its lock fds)
        if primary_lock is None:
            # LAN hosts / guests run inside their script's hold (tools/gpulock.py: nested; before the lock lanes: on their
            # own lock file): no extra launches there.
            primary_lock = (not hold.nested) if hold is not None else os.environ.get("MC_GPU_LOCK", DEFAULT_LOCK) == DEFAULT_LOCK
        self.primary = primary_lock
        self.info = {"label": a.label, "lockWaitSeconds": round(waited, 1),
                     "quickPlay": "--quickPlaySingleplayer" in cmd}
        if hold is not None:
            self.info["lane"] = hold.lane
            self.info["lockNested"] = hold.nested
        self.aot_cache = None
        self.exit_code = 0   # of --aot-train-only / --warm-only

    # ---- derived short launches ---------------------------------------------------------------------------------------

    def derived(self, gdir, bench_out, label, props, extra_jvm=(), bench_jar=None):
        """The real command for game dir gdir: game-dir paths swapped; the run's bench agent, -Dbench.* and AOT options
        replaced by extra_jvm + the bench agent (bench_jar or the run's) with props; quick play into "bench"."""
        g = self.game_dir.rstrip("/")
        mi = self.cmd.index(self.main_class)
        jvm = []
        for x in self.cmd[1:mi]:
            if x.startswith(("-Dbench.", "-XX:AOTCache", "-Xlog:aot")) or x == "-javaagent:" + str(self.bench_jar):
                continue
            jvm.append(gdir if x == g else x.replace(g + "/", gdir + "/"))
        jvm += list(extra_jvm) + ["-javaagent:" + (bench_jar or self.bench_jar), f"-Dbench.out={bench_out}",
                                  f"-Dbench.label={label}", "-Dbench.quickPlay=true"] + [f"-Dbench.{p}" for p in props]
        game, i, rest = [], 0, self.cmd[mi + 1:]
        while i < len(rest):
            if rest[i] == "--quickPlaySingleplayer":
                i += 2
                continue
            game.append(gdir if rest[i] == g else rest[i].replace(g + "/", gdir + "/"))
            i += 1
        return [self.cmd[0]] + jvm + [self.main_class] + game + ["--quickPlaySingleplayer", "bench"]

    def short_launch(self, kind, extra_jvm, seconds, bench_jar=None):
        """Quick play into a fresh copy of the pinned scenes world (windowed), stop `seconds` after world ready."""
        api = self.a.api or "default"
        gdir = os.path.join(ROOT, "build", kind, api)
        for sub in ("java", "jna", "lwjgl", "netty"):
            os.makedirs(os.path.join(gdir, ".natives", sub), exist_ok=True)
        pin.restore(PINNED_SCENES, gdir, "bench")
        self.write_options(gdir, self.a.api, ["fullscreen:false"])
        out = os.path.join(gdir, "result.json")
        for p in (out, out + ".startup.json"):
            if os.path.exists(p):
                os.remove(p)
        cmd = self.derived(gdir, out, kind, ["mode=perf", "world=bench", f"exitAfterReady={seconds}"], extra_jvm, bench_jar)
        t0 = time.time()
        env, fds = (self.hold.child_env(self.env), tuple(self.hold.fds)) if self.hold is not None else (self.env, ())
        with open(os.path.join(gdir, "launch.log"), "w") as log:
            rc = subprocess.run(cmd, cwd=gdir, stdout=log, stderr=subprocess.STDOUT, env=env, pass_fds=fds).returncode
        return rc, time.time() - t0, gdir

    @staticmethod
    def short_lock():
        """Exclusive flock(2) on build/.fastlaunch.lock; the caller closes the returned fd. Runs in the GPU lock's
        functional lane overlap, and short launches share their game dirs (build/aot-train/<api>, build/shader-warm/<api>)
        and the AOT cache's temporary file: one training / warm-up at a time per checkout."""
        os.makedirs(os.path.join(ROOT, "build"), exist_ok=True)
        fd = os.open(os.path.join(ROOT, "build", ".fastlaunch.lock"), os.O_RDWR | os.O_CREAT, 0o666)
        fcntl.flock(fd, fcntl.LOCK_EX)
        return fd

    # ---- AOT -----------------------------------------------------------------------------------------------------------

    def aot_key(self):
        mi = self.cmd.index(self.main_class)
        flags, cp, skip = [], [], False
        for i, f in enumerate(self.cmd[1:mi], 1):
            if skip:
                skip = False
                continue
            if f in ("-cp", "-classpath"):
                cp = self.cmd[i + 1].split(os.pathsep)
                skip = True
                continue
            if f.startswith(("-D", "-javaagent:", "-XX:AOTCache", "-XX:AOTMode", "-Xlog:aot")):
                continue
            flags.append(f)
        libs = []
        for p in cp:
            if os.path.abspath(p) == self.client_jar:
                continue
            try:
                st = os.stat(p)
                libs.append(f"{os.path.basename(p)}:{st.st_size}:{int(st.st_mtime)}")
            except OSError:
                libs.append(f"{os.path.basename(p)}:missing")
        parts = {"java": java_id(self.cmd[0]), "flags": flags, "libs": libs, "clientJar": sha1_file(self.client_jar),
                 "clientJarPath": self.client_jar, "benchJar": sha1_file(self.bench_jar), "api": self.a.api or "default"}
        return hashlib.sha1(json.dumps(parts, sort_keys=True).encode()).hexdigest()[:16], parts

    def prepare_aot(self):
        if not bench_jar_new(self.bench_jar):
            print("[launch] --aot needs a bench jar with bench/Startup.class (for the training run); running without AOT",
                  file=sys.stderr)
            return
        os.makedirs(AOT_DIR, exist_ok=True)
        key, parts = self.aot_key()
        cache = os.path.join(AOT_DIR, f"{key}.aot")
        info = {"key": key, "trainedNow": False}
        lock_fd = self.short_lock() if not os.path.exists(cache) else None
        if lock_fd is not None and not os.path.exists(cache):   # (re-checked: a concurrent run may have trained it meanwhile)
            tmp = cache + ".tmp"
            if os.path.exists(tmp):
                os.remove(tmp)
            print(f"[launch] training AOT cache {key} (new inputs)", file=sys.stderr)
            rc, secs, gdir = self.short_launch("aot-train", [f"-XX:AOTCacheOutput={tmp}"], 5)
            if rc == 0 and os.path.exists(tmp):
                os.replace(tmp, cache)
                with open(cache[:-4] + ".json", "w") as f:
                    json.dump(dict(parts, trainedAt=time.strftime("%Y-%m-%dT%H:%M:%S"), trainSeconds=round(secs, 1)), f, indent=2)
                info.update(trainedNow=True, trainSeconds=round(secs, 1))
                print(f"[launch] AOT cache {key}: trained in {secs:.0f}s, {os.path.getsize(cache) >> 20} MB", file=sys.stderr)
                stamp = self.warm_stamp()
                if stamp and self.a.api == "metal":   # the training run compiled every pipeline, as a warm-up does
                    os.makedirs(WARM_DIR, exist_ok=True)
                    with open(stamp, "w") as f:
                        f.write(f"aot-train {time.strftime('%Y-%m-%dT%H:%M:%S')} {self.client_jar}\n")
            else:
                print(f"[launch] AOT training failed (exit {rc}, {gdir}/launch.log); running without AOT", file=sys.stderr)
                cache = None
            self.prune_aot()
        if lock_fd is not None:
            os.close(lock_fd)
        if cache:
            os.utime(cache)   # most recently used (prune order)
        self.aot_cache = cache
        self.info["aot"] = dict(info, cache=os.path.relpath(cache, ROOT) if cache else None)

    @staticmethod
    def prune_aot():
        caches = sorted((os.path.join(AOT_DIR, n) for n in os.listdir(AOT_DIR) if n.endswith(".aot")),
                        key=os.path.getmtime, reverse=True)
        for old in caches[AOT_KEEP:]:
            for p in (old, old[:-4] + ".json"):
                try:
                    os.remove(p)
                except OSError:
                    pass

    # ---- shader warm-up ------------------------------------------------------------------------------------------------

    def warm_stamp(self):
        sid = shader_inputs_id(self.client_jar)
        if not sid:
            return None
        return os.path.join(WARM_DIR, f"{sid}-{hashlib.sha1(java_id(self.cmd[0]).encode()).hexdigest()[:8]}")

    def prepare_warm(self):
        a = self.a
        if a.warm_shaders == "off" or (a.warm_shaders == "auto" and not (self.primary and a.api == "metal" and (a.bench or a.warm_only))):
            return
        stamp = self.warm_stamp()
        if not stamp:
            return
        jar = self.bench_jar if bench_jar_new(self.bench_jar) else default_bench_jar()
        if not bench_jar_new(jar):
            return
        if a.warm_shaders == "auto" and os.path.exists(stamp):
            self.info["shaderWarm"] = {"ran": False, "warmBefore": True, "stamp": os.path.basename(stamp)}
            return
        lock_fd = self.short_lock()
        try:
            if a.warm_shaders == "auto" and os.path.exists(stamp):   # (a concurrent functional run warmed it meanwhile)
                self.info["shaderWarm"] = {"ran": False, "warmBefore": True, "stamp": os.path.basename(stamp)}
                return
            print(f"[launch] warming the Metal shader cache ({os.path.basename(stamp)})", file=sys.stderr)
            rc, secs, gdir = self.short_launch("shader-warm", [], 2, bench_jar=jar)
            self.info["shaderWarm"] = {"ran": True, "seconds": round(secs, 1), "exit": rc, "stamp": os.path.basename(stamp)}
            if rc == 0:
                os.makedirs(WARM_DIR, exist_ok=True)
                with open(stamp, "w") as f:
                    f.write(f"warm {time.strftime('%Y-%m-%dT%H:%M:%S')} {self.client_jar}\n")
            else:
                print(f"[launch] shader warm-up failed (exit {rc}, {gdir}/launch.log)", file=sys.stderr)
        finally:
            os.close(lock_fd)

    # ---- hooks called by launch.py -------------------------------------------------------------------------------------

    def before_run(self):
        """Under the lock, before the pinned-world restore: AOT training, shader warm-up. Returns the command to run
        (with the AOT cache when there is one); exits for --aot-train-only / --warm-only."""
        a = self.a
        if a.aot or a.aot_train_only:
            self.prepare_aot()
        if a.aot_train_only:
            print(self.aot_cache or "")
            self.exit_code = 0 if self.aot_cache else 1
            return None
        self.prepare_warm()
        if a.warm_only:
            print(json.dumps(self.info.get("shaderWarm")))
            self.exit_code = 0 if (self.info.get("shaderWarm") or {}).get("exit", 0) == 0 else 1
            return None
        if self.aot_cache:
            mi = self.cmd.index(self.main_class)
            self.cmd[mi:mi] = [f"-XX:AOTCache={self.aot_cache}", "-Xlog:aot=info"]
        self.started()
        return self.cmd

    def started(self):
        """Display mode, load average and the wall-time origin, right before the game starts (launch.py calls it again
        after its quiet / overlay gates, which follow the training / warm-up launches)."""
        self.info["display"] = display_mode()
        try:
            self.info["loadAvg1"] = round(os.getloadavg()[0], 2)
        except OSError:
            pass
        self.t_start = time.time()

    def after_run(self, rc):
        """After the game exited: AOT mapped state from the log, merge "startup" and "launch" into the bench JSON."""
        self.info["wallSeconds"] = round(time.time() - self.t_start, 2)
        self.info["exit"] = rc
        if not self.a.bench:
            return
        if self.aot_cache:
            try:
                log = open(os.path.join(self.game_dir, "launch.log"), errors="replace").read()
                self.info["aot"]["mapped"] = ("Opened AOT cache" in log and "Unable to map shared spaces" not in log
                                              and "Unable to use AOT cache" not in log)
            except OSError:
                pass
        merge_results(os.path.abspath(self.a.bench), self.info)


def merge_results(bench_out, extra):
    """Appends "startup" (the agent's <bench_out>.startup.json, then deleted) and "launch" to the bench JSON, keeping the
    rest of the file byte for byte; creates the JSON for runs that wrote none (exitAfterReady startup runs)."""
    side = bench_out + ".startup.json"
    members = {}
    if os.path.exists(side):
        try:
            members["startup"] = json.load(open(side))
        except ValueError:
            pass
    members["launch"] = extra
    if os.path.exists(bench_out):
        text = open(bench_out).read().rstrip()
        if not text.endswith("}"):
            return
        body = text[:-1].rstrip()
        sep = "" if body.endswith("{") else ","
        new = body + sep + ",".join(f'\n  "{k}": {json.dumps(v)}' for k, v in members.items()) + "\n}\n"
        try:
            json.loads(new)
        except ValueError:
            return   # leave the agent's file (and the side file) alone
    else:
        new = json.dumps(dict({"label": extra.get("label"), "mode": "startup"}, **members), indent=2) + "\n"
    with open(bench_out, "w") as f:
        f.write(new)
    if os.path.exists(side):
        os.remove(side)
