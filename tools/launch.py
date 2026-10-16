#!/usr/bin/env python3
"""Launch a Minecraft client exactly the way Mojang's launcher would (same JVM
defaults, same argument template from the version JSON), in offline mode for
local benchmarking.

Example:
  launch.py --game-dir run/vanilla-gl --api opengl --bench results/raw/x.json --label vanilla-gl

Faster launches for tests (quick play, --aot, --warm-shaders) and the "startup" / "launch" result blocks: tools/fastlaunch.py.
"""
import argparse, json, os, shutil, subprocess, sys, time, uuid, zipfile

import fastlaunch
import gpulock
import pin

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MC = os.path.join(ROOT, "mc")
# Pinned, read-only scenes world + options (docs/metal/CONTRACT.md 11.6): every scenes / zoomseq run starts from it.
PINNED_SCENES = os.path.join(ROOT, "run", "pinned", "scenes")
JAVA_HOME = os.path.join(MC, "runtime", "java-runtime-epsilon", "jre.bundle", "Contents", "Home")


def check_overlays(wait_seconds=0):
    """Read-only: wait for overlays to clear, never dismiss or move a window."""
    start = time.monotonic()
    attempts = 0
    while True:
        attempts += 1
        try:
            check = subprocess.run([os.path.join(ROOT, "tools", "check-overlays.sh")],
                                   capture_output=True, text=True, timeout=15)
            code, out, error = check.returncode, check.stdout, check.stderr
        except (OSError, subprocess.TimeoutExpired) as e:
            code, out, error = -1, "", str(e)
        elapsed = time.monotonic() - start
        if code == 0 or elapsed >= wait_seconds:
            return {"exit": code, "output": out[-4096:], "error": error[-4096:],
                    "waitedSeconds": round(elapsed, 3), "attempts": attempts}
        if attempts == 1:
            print("[launch] waiting for overlay windows to clear; no windows will be changed", file=sys.stderr)
        time.sleep(min(5.0, max(0.0, wait_seconds - elapsed)))


def rules_allow(rules):
    if not rules:
        return True
    allowed = False
    for rule in rules:
        if rule.get("features"):
            continue
        os_rule = rule.get("os", {})
        if os_rule.get("name") in (None, "osx") and os_rule.get("arch") in (None, "arm64"):
            allowed = rule["action"] == "allow"
    return allowed


def flatten(args):
    out = []
    for a in args:
        if isinstance(a, str):
            out.append(a)
        elif rules_allow(a.get("rules")):
            v = a["value"]
            out.extend(v if isinstance(v, list) else [v])
    return out


def maven_path(name):
    parts = name.split(":")
    classifier = f"-{parts[3]}" if len(parts) > 3 else ""
    return f"{parts[0].replace('.', '/')}/{parts[1]}/{parts[2]}/{parts[1]}-{parts[2]}{classifier}.jar"


def zip_entry(jar, name):
    """Bytes of one jar entry, b"" if the jar or the entry is missing."""
    try:
        with zipfile.ZipFile(jar) as z:
            return z.read(name)
    except (OSError, KeyError, zipfile.BadZipFile):
        return b""


def classpath(vjson, client_jar, extra=()):
    cp = []
    for lib in vjson["libraries"]:
        if not rules_allow(lib.get("rules")):
            continue
        art = lib.get("downloads", {}).get("artifact")
        path = art["path"] if art else maven_path(lib["name"])
        cp.append(os.path.join(MC, "libraries", path))
    cp += list(extra)
    cp.append(client_jar)
    return cp


# Fabric (docs/GOAL.md "Mod compatibility", results/FABRIC.md). The loader profile is Fabric meta's launcher profile
# (https://meta.fabricmc.net/v2/versions/loader/<game>/<loader>/profile/json), its libraries downloaded from
# maven.fabricmc.net into mc/fabric/libraries with their sha1 checked; mods live in mc/fabric/mods (Modrinth, sha1/sha512
# checked, mc/fabric/mods/manifest.json). Knot loads the game from the jar given by -Dfabric.gameJarPath (our client jar).
FABRIC = os.path.join(MC, "fabric")
FABRIC_DEFAULT_PROFILE = os.path.join(FABRIC, "fabric-loader-0.19.5-26.3.json")


# Mods that replace rendering, superseded by our renderer / LOD / shaders: Fabric must not load them (their mixins rewrite
# what the Metal renderer replaces); the client shows which ones it found (net.minecraft.client.compat.ModCompat).
SUPERSEDED_MODS = "sodium,iris,distanthorizons,voxy"


def fabric_setup(profile_path, client_jar, allow_render_mods=False):
    """(main class, extra classpath, extra JVM args, info) for a Fabric launch from a Fabric meta launcher profile."""
    prof = json.load(open(profile_path))
    libs = [os.path.join(FABRIC, "libraries", maven_path(l["name"])) for l in prof["libraries"]]
    missing = [p for p in libs if not os.path.isfile(p)]
    if missing:
        sys.exit(f"launch.py: Fabric libraries missing (download them first, results/FABRIC.md): {missing}")
    jvm = flatten(prof.get("arguments", {}).get("jvm", []))
    jvm.append(f"-Dfabric.gameJarPath={client_jar}")
    if not allow_render_mods:
        jvm.append(f"-Dfabric.debug.disableModIds={SUPERSEDED_MODS}")
    loader = next(l["name"].split(":")[2] for l in prof["libraries"] if l["name"].startswith("net.fabricmc:fabric-loader:"))
    return prof["mainClass"], libs, jvm, {"profile": os.path.relpath(profile_path, ROOT), "loader": loader}


def mod_files(specs):
    """Mod jars named by --mods (files or directories of *.jar), in order, without duplicates."""
    out = []
    for s in specs:
        for part in s.split(","):
            part = os.path.abspath(part) if part else ""
            if os.path.isdir(part):
                out += sorted(os.path.join(part, f) for f in os.listdir(part) if f.endswith(".jar"))
            elif os.path.isfile(part):
                out.append(part)
            elif part:
                sys.exit(f"launch.py: mod not found: {part}")
    seen, uniq = set(), []
    for p in out:
        if os.path.basename(p) not in seen:
            seen.add(os.path.basename(p))
            uniq.append(p)
    return uniq


def sync_mods(game_dir, mods):
    """Make <game dir>/mods hold exactly these jars (copies; other *.jar removed). Returns [{file, sha1}]."""
    import hashlib
    d = os.path.join(game_dir, "mods")
    os.makedirs(d, exist_ok=True)
    keep = {os.path.basename(m) for m in mods}
    for f in os.listdir(d):
        if f.endswith(".jar") and f not in keep:
            os.remove(os.path.join(d, f))
    out = []
    for m in mods:
        dst = os.path.join(d, os.path.basename(m))
        shutil.copyfile(m, dst)
        out.append({"file": os.path.basename(m), "sha1": hashlib.sha1(open(dst, "rb").read()).hexdigest()})
    return out


DATA_VERSION = 5023  # 26.3 world_version (client jar version.json)


def write_options(game_dir, api, extra):
    """Benchmark-neutral options: no vsync, uncapped FPS, no AFK/focus throttling,
    no first-launch onboarding screens. Everything else stays at vanilla defaults."""
    opts = {
        "enableVsync": "false",
        "maxFps": "260",
        "inactivityFpsLimit": '"minimized"',
        "pauseOnLostFocus": "false",
        "onboardAccessibility": "false",
        "tutorialStep": "none",
        "joinedFirstServer": "true",
        "skipMultiplayerWarning": "true",
        "narrator": "0",
        "soundCategory_music": "0.0",
    }
    if api:
        opts["preferredGraphicsBackend"] = f'"{api}"'
    for kv in extra:
        k, v = kv.split(":", 1)
        opts[k] = v
    path = os.path.join(game_dir, "options.txt")
    existing = {}
    if os.path.exists(path):
        for line in open(path):
            if ":" in line:
                k, v = line.rstrip("\n").split(":", 1)
                existing[k] = v
    # A fresh options file without a data version is treated as legacy and run through the
    # options datafixer, which resets keys such as preferredGraphicsBackend to their defaults.
    existing.setdefault("version", str(DATA_VERSION))
    existing.update(opts)
    with open(path, "w") as f:
        for k, v in sorted(existing.items(), key=lambda kv: kv[0] != "version"):
            f.write(f"{k}:{v}\n")


# "[P]": the pattern must not match itself, or a concurrent launch's (or tools/suite_sched.py's) own `pgrep -f` process
# reads as the user's game (seen with several functional-lane launches starting at once: a 10 s false wait each).
USER_GAME_PATTERN = "[P]rismLauncher/instances/.*/bin/java "


def user_game_running():
    return subprocess.run(["pgrep", "-f", USER_GAME_PATTERN], stdout=subprocess.DEVNULL).returncode == 0


def wait_for_user_game():
    if not user_game_running():
        return
    print("[launch] the user's game is open: waiting until it closes", file=sys.stderr)
    while user_game_running():
        time.sleep(10)
    print("[launch] the user's game closed: continuing", file=sys.stderr)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--version", default="26.3")
    ap.add_argument("--jar", help="override client jar (patched build)")
    ap.add_argument("--game-dir", required=True)
    ap.add_argument("--api", choices=["default", "opengl", "vulkan", "metal"])
    ap.add_argument("--opt", action="append", default=[], help="options.txt key:value override")
    ap.add_argument("--bench", help="write benchmark JSON here (enables bench agent)")
    ap.add_argument("--bench-jar", help="bench agent jar (default build/bench.jar); pass a frozen copy for queued A/B runs")
    ap.add_argument("--label", default="run")
    ap.add_argument("--bench-prop", action="append", default=[], help="extra -Dbench.* key=value")
    ap.add_argument("--jvm", action="append", default=[], help="extra JVM arg")
    ap.add_argument("--no-default-jvm", action="store_true", help="drop Mojang's default-user-jvm args")
    ap.add_argument("--env", action="append", default=[], help="extra environment KEY=VALUE (e.g. MTL_DEBUG_LAYER=1)")
    ap.add_argument("--game-arg", action="append", default=[], help="extra game argument (e.g. --renderDebugLabels)")
    ap.add_argument("--username", default="Bench")
    ap.add_argument("--lane", choices=["timing", "functional"], default="timing",
                    help="GPU lock lane (tools/gpulock.py): timing = exclusive, nothing else that takes the lock runs (default; "
                    "every measurement); functional = shared with other functional runs, never with a timing run (runs whose "
                    "verdict does not depend on speed: scenes / zoomseq parity, LAN smoke)")
    ap.add_argument("--quiet-gate", metavar="LIMIT[:TIMEOUT]",
                    help="once the lock is held, wait until the 1-minute load average is below LIMIT (at most TIMEOUT s, "
                    "default 900): checked when the run starts, not before it queued")
    ap.add_argument("--overlay-check", action="store_true",
                    help="once the lock is held, warn if overlay windows are on screen (tools/check-overlays.sh)")
    ap.add_argument("--overlay-wait", type=int, default=0, metavar="SECONDS",
                    help="with --overlay-check, wait up to SECONDS for clear overlays; if still blocked, do not launch")
    ap.add_argument("--fresh-world", metavar="NAME",
                    help="delete <game dir>/saves/NAME once the lock is held (fresh world every run, never under a queued run)")
    ap.add_argument("--world-snapshot", help="pinned snapshot (tools/pin.py world) whose saves/<world>/ and options.txt "
                    "replace the game dir's before the game starts; default run/pinned/scenes for --bench-prop mode=scenes or "
                    "zoomseq, none otherwise; 'none' keeps the game dir's world (debugging only: rain / thunder scenes and the "
                    "save on exit make it drift)")
    ap.add_argument("--world-only", action="store_true", help="with --world-snapshot: restore saves/<world>/ only and keep the "
                    "game dir's options.txt (tools/bench-travel.sh --pregen: the travel options stay the fresh-world run's)")
    ap.add_argument("--fabric", nargs="?", const=FABRIC_DEFAULT_PROFILE, metavar="PROFILE_JSON",
                    help="launch through the Fabric loader (Knot) with --jar (or vanilla) as the game jar; PROFILE_JSON is a "
                    "Fabric meta launcher profile (default mc/fabric/fabric-loader-0.19.5-26.3.json)")
    ap.add_argument("--mods", action="append", default=[], metavar="JAR|DIR[,...]",
                    help="with --fabric: <game dir>/mods is made to hold exactly these jars (directories: every *.jar in "
                    "them), under the lock; without --mods the game dir's mods folder is left as it is")
    ap.add_argument("--fabric-allow-render-mods", action="store_true",
                    help="with --fabric: do not disable the superseded rendering mods (Sodium, Iris, Distant Horizons, Voxy); "
                    "only to test what they do on this client")
    ap.add_argument("--mc-root", help="other install root (versions/, libraries/, assets/, runtime/; Fabric libraries in its "
                    "libraries/ as tools/mcdl.py lays them out), e.g. mc/voxy-26.2 for the Voxy comparison (tools/voxy/)")
    ap.add_argument("--data-version", type=int, help="options.txt data version for --mc-root installs (26.2: 4903)")
    ap.add_argument("--dry-run", action="store_true")
    fastlaunch.add_args(ap)
    a = ap.parse_args()
    if not 0 <= a.overlay_wait <= 1800 or (a.overlay_wait and not a.overlay_check):
        ap.error("--overlay-wait requires --overlay-check and must be between 0 and 1800 seconds")
    global MC, FABRIC, JAVA_HOME, DATA_VERSION
    if a.mc_root:
        MC = FABRIC = os.path.abspath(a.mc_root)
        JAVA_HOME = os.path.join(MC, "runtime", "java-runtime-epsilon", "jre.bundle", "Contents", "Home")
    if a.data_version:
        DATA_VERSION = a.data_version
    if a.mods and not a.fabric:
        sys.exit("launch.py: --mods needs --fabric")

    vjson = json.load(open(os.path.join(MC, "versions", a.version, f"{a.version}.json")))
    client_jar = os.path.abspath(a.jar) if a.jar else os.path.join(MC, "versions", a.version, f"{a.version}.jar")
    game_dir = os.path.abspath(a.game_dir)
    main_class, fabric_cp, fabric_jvm, fabric_info = vjson["mainClass"], [], [], None
    if a.fabric:
        main_class, fabric_cp, fabric_jvm, fabric_info = fabric_setup(os.path.abspath(a.fabric), client_jar, a.fabric_allow_render_mods)
    mods = mod_files(a.mods)
    natives = os.path.join(game_dir, ".natives")
    for sub in ("java", "jna", "lwjgl", "netty"):
        os.makedirs(os.path.join(natives, sub), exist_ok=True)

    subst = {
        "${natives_directory}": natives,
        "${launcher_name}": "claude-bench",
        "${launcher_version}": "1",
        "${classpath}": os.pathsep.join(classpath(vjson, client_jar, fabric_cp)),
        "${auth_player_name}": a.username,
        "${version_name}": a.version,
        "${game_directory}": game_dir,
        "${assets_root}": os.path.join(MC, "assets"),
        "${assets_index_name}": vjson["assetIndex"]["id"],
        "${auth_uuid}": str(uuid.uuid3(uuid.NAMESPACE_DNS, "OfflinePlayer:" + a.username)).replace("-", ""),
        "${auth_access_token}": "0",
        "${clientid}": "0",
        "${auth_xuid}": "0",
        "${version_type}": vjson.get("type", "release"),
    }

    def sub(s):
        for k, v in subst.items():
            s = s.replace(k, v)
        return s

    jvm = [] if a.no_default_jvm else flatten(vjson["arguments"].get("default-user-jvm", []))
    jvm += [sub(x) for x in flatten(vjson["arguments"]["jvm"])]
    jvm += fabric_jvm
    # Farsight ships off by default (Video Settings > Far Terrain); every bench / test run measures it on unless told
    if not any(j.startswith("-Dmc.lod=") for j in a.jvm):
        jvm.append("-Dmc.lod=true")
    jvm += a.jvm
    bench_jar = None
    if a.bench or a.aot_train_only or a.warm_only:
        bench_jar = os.path.abspath(a.bench_jar) if a.bench_jar else os.path.join(ROOT, 'build', 'bench.jar')
    if a.bench:
        os.makedirs(os.path.dirname(os.path.abspath(a.bench)), exist_ok=True)
        if not os.path.isfile(bench_jar):
            sys.exit(f"launch.py: bench jar not found: {bench_jar}")
        jvm += [f"-javaagent:{bench_jar}",
                f"-Dbench.out={os.path.abspath(a.bench)}", f"-Dbench.label={a.label}"]
        jvm += [f"-Dbench.{p}" for p in a.bench_prop]
    if a.fabric:
        # Under Knot the game classes live in Knot's class loader; a java agent's hook classes (bench.Bench & co, called
        # from Minecraft.renderFrame; the LAN agent's) must be loaded there too, or they would see a second copy of the game
        # loaded by the app class loader. On the class path, Knot takes an agent jar as a game library and loads it itself
        # (the premain transformer stays in the app loader; it only rewrites bytes).
        i = jvm.index("-cp")
        for x in list(jvm):
            if x.startswith("-javaagent:"):
                jvm[i + 1] += os.pathsep + x[len("-javaagent:"):].split("=", 1)[0]
    props = dict(p.split("=", 1) for p in a.bench_prop if "=" in p)
    snapshot = a.world_snapshot
    if snapshot is None and a.bench and props.get("mode") in ("scenes", "zoomseq") and "join" not in props:
        snapshot = PINNED_SCENES
    if snapshot == "none":
        snapshot = None
    world = props.get("world", "bench")
    if snapshot:
        snapshot = os.path.abspath(snapshot)
        try:
            sid = pin.verify(snapshot)
        except (OSError, ValueError) as e:
            sys.exit(f"launch.py: pinned world unusable: {e}")
        jvm.append(f"-Dbench.pinnedWorld={os.path.relpath(snapshot, ROOT)}@{sid}")
    game = [sub(x) for x in flatten(vjson["arguments"]["game"])] + a.game_arg
    quick_jvm, quick_game = fastlaunch.quickplay_args(a, props, snapshot, game_dir, world, bench_jar)
    jvm += quick_jvm
    game += quick_game
    cmd = [os.path.join(JAVA_HOME, "bin", "java")] + jvm + [main_class] + game
    if a.dry_run:
        print(" \\\n  ".join(cmd))
        return
    env = dict(os.environ)
    for kv in a.env:
        k, v = kv.split("=", 1)
        env[k] = v
    # Machine-wide GPU lock (tools/gpulock.py; MC_GPU_LOCK overrides the path). Timing lane: one game at a time on this
    # machine (fullscreen + GPU + CPU are shared), so concurrent launches by different agents or scripts queue instead of
    # distorting each other's measurements. Functional lane: overlaps other functional runs only; a waiting timing run
    # goes first. Key gamedir:<dir>: one game per game dir in either lane. Inside a hold (lan-smoke.sh, soak-lan.sh,
    # `lockf -k <lock> ...`) nothing is taken again.
    lane = a.lane
    if lane == "functional" and a.bench and props.get("mode") == "scenes" and (
            props.get("scenes.vignette") == "vanilla" or b"settleVignette" not in zip_entry(bench_jar, "bench/Scenes.class")):
        # Older scene runners captured a vignette that depends on how fast the world got ready (bench/README.md).
        print("[launch] this scenes runner does not settle the vignette (older bench jar or scenes.vignette=vanilla): "
              "its verdicts depend on speed, so it runs in the timing lane", file=sys.stderr)
        lane = "timing"
    t0 = time.time()
    # The user's own game (any Prism Launcher instance) has priority: never start a run while it is open, and re-check
    # after taking the lock (the user may have launched it while we waited in the queue).
    while True:
        wait_for_user_game()
        try:
            hold = gpulock.acquire(lane, keys=[("gamedir:" + os.path.realpath(game_dir), gpulock.EX)],
                                   label=f"launch.py {a.label} {os.path.relpath(game_dir, ROOT)}")
        except gpulock.LockError as e:
            sys.exit(f"launch.py: GPU lock: {e}")
        if not user_game_running():
            break
        hold.release()
    waited = time.time() - t0
    if waited > 1:
        print(f"[launch] waited {waited:.0f}s for the game lock ({lane} lane)", file=sys.stderr)
    if a.overlay_wait:
        # before_run may itself launch AOT-training or shader-warmup games.
        initial_overlay = check_overlays(a.overlay_wait)
        if initial_overlay["exit"] != 0:
            hold.release()
            sys.exit("launch.py: overlay gate still blocked; no game launched. "
                     + initial_overlay["output"] + initial_overlay["error"])
    fast = fastlaunch.Run(a, cmd, main_class, game_dir, client_jar, bench_jar, env, waited, write_options, hold=hold)
    cmd = fast.before_run()   # AOT training / shader warm-up (short launches under this hold); None: --*-only done
    if cmd is None:
        hold.release()
        sys.exit(fast.exit_code)
    # The gates follow the training / warm-up launches, so their CPU burst is part of what the quiet gate waits out.
    if a.quiet_gate:
        limit, _, timeout = a.quiet_gate.partition(":")
        load, w, quiet = gpulock.quiet_gate(float(limit), float(timeout or 900))
        fast.info["quietGate"] = {"load": round(load, 2), "waited": round(w, 1), "ok": quiet}
        if w > 1 or not quiet:
            print(f"[launch] load {load:.2f} after {w:.0f}s of quiet gate" + ("" if quiet else " (load high)"), file=sys.stderr)
        if not quiet:
            hold.release()
            sys.exit("launch.py: quiet gate failed; game not launched")
    if a.overlay_check:
        overlay = check_overlays(a.overlay_wait)
        fast.info["overlays"] = overlay["exit"] != 0
        fast.info["overlayCheck"] = overlay
        if fast.info["overlays"]:
            if a.overlay_wait:
                hold.release()
                sys.exit("launch.py: overlay gate still blocked; game not launched. " + overlay["output"] + overlay["error"])
            print("[launch] (overlays present: composited)", file=sys.stderr)
    if a.fresh_world:
        shutil.rmtree(os.path.join(game_dir, "saves", a.fresh_world), ignore_errors=True)
    # Restore the pinned world and write options only while holding the lock: another queued launch sharing this game
    # dir must not replace them between our write and the game reading them at startup (or while its game runs).
    if snapshot:
        try:
            sid = pin.restore(snapshot, game_dir, world, options=not a.world_only)
        except (OSError, ValueError) as e:
            sys.exit(f"launch.py: cannot restore the pinned world: {e}")
        print(f"[launch] restored saves/{world}{'' if a.world_only else ' and options.txt'} from {os.path.relpath(snapshot, ROOT)} "
              f"(id {sid})", file=sys.stderr)
    write_options(game_dir, a.api, a.opt)
    if fabric_info is not None:
        if a.mods:
            fabric_info["mods"] = sync_mods(game_dir, mods)
        else:
            md = os.path.join(game_dir, "mods")
            fabric_info["mods"] = sorted(f for f in os.listdir(md) if f.endswith(".jar")) if os.path.isdir(md) else []
        fast.info["fabric"] = fabric_info
    log = open(os.path.join(game_dir, "launch.log"), "w")
    fast.started()   # display mode, load and wall-time origin after the gates
    # The game inherits the lock fds: the locks stay held until the game itself exits, even if this script is killed.
    proc = subprocess.run(cmd, cwd=game_dir, stdout=log, stderr=subprocess.STDOUT, env=hold.child_env(env), pass_fds=tuple(hold.fds))
    hold.release()
    fast.after_run(proc.returncode)   # "startup" + "launch" blocks into the bench JSON
    sys.exit(proc.returncode)


if __name__ == "__main__":
    main()
