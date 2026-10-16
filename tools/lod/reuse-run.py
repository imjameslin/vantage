#!/usr/bin/env python3
"""Bounded, opt-in Farsight reuse/async measurement (CODEX-PROMPTS.md, Prompts 1/5).

Plan only by default; --dry-run is an explicit synonym. Quick cached fixture
example (repeat with --mode spot, vista, spin; keep --world bench for flying):
  python3 -B tools/lod/reuse-run.py build/client-reuse-frozen.jar \
    build/bench-reuse-frozen.jar --label reuse-flying --mode flying \
    --game-dir run/s3-palette-check --world bench --dry-run

Choose exactly one of --game-dir (an existing project fixture, retaining its
world/LOD/mesh cache in place) or --world-snapshot (restore a pin.py snapshot into
a fresh game directory). With --game-dir, optional --reset-world PATH restores
only saves/<world> from a verified pin.py snapshot under the launcher lock,
preserving the existing LOD/mesh cache. This pins the WORLD start only; the cache
remains mutable and unpinned. Without --reset-world, existing fixtures retain
their saved world too. Normal launcher/game options, logs, saves and cache writes persist.
--run requires read-only, explicitly named jars and an already built, current
build/displaymode helper. Nothing is built or pre-generated. Receipts go under
results/reuse-run/LABEL; Prism paths are forbidden. Run standalone:
the supervised worker must own its timing lock, not borrow an ancestor's lock.
The deadline includes lock waiting, scene restore, settling and measurement;
cleanup adds at most five seconds. Only the owned process group is signalled.

A = reuse off, B = reuse on, in one game, ABBA order using bench mode=abab.
All views use one segment/block: spin 10 s, flying/held 5 s, discarding 30 frames
once per block so low-FPS runs retain samples. See LIMITATIONS: this is measurement evidence,
never a release/quality verdict, nor proof that enabling the toggle reused frames.

--feature async measures the default-off independent-layer prototype instead.
Its OFF arm drains old work and retains guard selection/warmed resources. It
uses 120 discarded transition frames and material-publication tokens; inspect
per-layer completion counters and discard sensitivity before claiming a gain.

--feature items measures the synchronous renderer's default-off per-section CPU
item metadata cache. The async renderer and image reuse are explicitly disabled.
This preserves the GPU draw bytes; actual FPS and quality remain to be measured.

--feature groups holds item caching on and compares the independent default-off
CPU group lookup. Detailed lookup diagnostics stay off in FPS runs.

--feature nodes holds item/group caching on and compares default-off CPU node
packets. Its flying run must observe real packet replay during measurement.

--feature prepare compares default-off CPU packing on a worker. Item/group caches
and atomic mesh handoff stay on, node packets and the async far renderer/reuse off.
Flying requires measured worker acceptance of nonempty clusters. Boundary deltas
include discarded transition frames; timing and image approval remain manual.
"""

import argparse
import fcntl
import hashlib
import importlib.util
import json
import math
import os
from pathlib import Path
import re
import shlex
import signal
import subprocess
import sys
import time
import zipfile

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[2]
FIELD = "net.minecraft.client.renderer.chunk.LodReuse#enabled"
ASYNC_FIELD = "net.minecraft.client.renderer.chunk.LodAsyncProducerProbe#active"
ITEMS_FIELD = "net.minecraft.client.renderer.chunk.LodGpu#cacheItems"
GROUPS_FIELD = "net.minecraft.client.renderer.chunk.LodGpu#cacheGroups"
NODES_FIELD = "net.minecraft.client.renderer.chunk.LodGpu#cacheNodeItems"
PREPARE_FIELD = "net.minecraft.client.renderer.chunk.LodGpu#asyncItems"
PREPARE_COUNTERS = ("offers", "accepted", "rejected", "bootstrap", "fallbacks", "uploadDefers",
                    "captureNs", "installNs", "workerNs", "acceptedClusters")
TOGGLE = FIELD + "=false|true"
LOCK = "/tmp/mc-gpu.lock"
PRISM_PATTERN = "[P]rismLauncher/instances/.*/bin/java "
NOON = ("gamerule advance_time false;gamerule advance_weather false;"
        "gamerule random_tick_speed 0;gamerule max_snow_accumulation_height 0;"
        "time set 6000;weather clear")
JVM = {
    "mc.lod": "true", "mc.lod.reuse": "false", "mc.lod.draw": "true",
    "mc.lod.radius": "512", "mc.lod.tau": "2", "mc.lod.gpuMB": "6144",
    "mc.lod.wave": "false", "mc.lod.hiz": "false", "mc.lod.solidQuery": "false",
    "mc.lod.area": "false", "mc.lod.secOccl": "true", "mc.lod.layerMask": "7",
    "mc.lod.tauLevels": "", "mc.lod.flat0Px": "0", "mc.lod.hysteresis": "0",
    "mc.lod.meshCache": "true", "mc.lod.palette": "false", "mc.lod.directQuads": "false",
}
ENV = {"MTL_DEBUG_LAYER": "0", "MTL_SHADER_VALIDATION": "0"}
INJECT_NAMES = {"JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS", "CLASSPATH"}
INJECT_PREFIXES = ("MCMTL_", "MTL_", "METAL_", "SDL_", "DYLD_", "PYTHON")
LIMITATIONS = [
    "No image gate, transition/invalidation test, Fabric check or GPU trace is performed; releaseEligible is always false.",
    "Toggle readbacks prove switching, not cache hits, correctness or a performance win; inspect reuse counters if supplied by the client.",
    "LOD idle and actual tau are checked at initial settle and run end, not continuously or separately per arm.",
    "Flying starts at the saved player's position, flies east at 10.9 blocks/s, and alternates whole-block arms along one continuous path; frames/streaming are not identical replays.",
    "Flying may stream at run end; final idle is recorded but only held/spin runs require it. Initial settle and tau=2 remain mandatory.",
    "Fixed-noon commands are recorded requests; the current bench does not report server time/weather or a per-frame camera trajectory.",
    "Warmup exercises both arms. Pooled arm statistics exclude switch-discard frames; transition costs/visual correctness require separate tests.",
    "Global pooled p1LowFps differs from the primary per-pair pairP99 effect; sector p99 is secondary and is not global 1% low.",
    "Prism is checked before/after lock and polled while running; a user launch between polls causes an abort, but cannot be made atomic with Prism.",
    "One segment per block retains samples at low FPS; whole-block arms have more drift/streaming noise than rapid interleaving. Sparse bins can still invalidate primary intervals (minCell=20).",
]


def parser():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("client", type=Path)
    p.add_argument("bench", type=Path)
    p.add_argument("--label", required=True)
    p.add_argument("--feature", choices=("reuse", "async", "items", "groups", "nodes", "prepare"), default="reuse", help="exact reuse, async prototype, CPU item/group/node caching or worker packing")
    p.add_argument("--item-build-profile", action="store_true",
                   help="items/groups/nodes flying only: instrument CPU build stages; never accepted as FPS evidence")
    p.add_argument("--node-cache-mb", type=int, choices=(64, 128), default=64,
                   help="nodes only: held CPU packet cap in MiB; GPU budget/detail unchanged")
    p.add_argument("--mode", choices=("spot", "vista", "spin", "flying"), required=True)
    p.add_argument("--spin-seconds", type=int, choices=(1, 10), default=10,
                   help="spin only: seconds per full turn; 1 = Prompt 5 fast turn (360 deg/s), 10 = legacy 36 deg/s")
    fixture = p.add_mutually_exclusive_group(required=True)
    fixture.add_argument("--world-snapshot", type=Path, help="existing pin.py snapshot; restore into a fresh game directory")
    fixture.add_argument("--game-dir", type=Path, help="existing project fixture; retain world/LOD/mesh cache in place, e.g. run/s3-palette-check")
    p.add_argument("--reset-world", type=Path, help="only with --game-dir: restore saves/<world> from this pin.py snapshot; preserve mutable LOD/mesh cache")
    p.add_argument("--world", help="save name (default bench with --game-dir; snapshot flying defaults to travel)")
    p.add_argument("--deadline", type=int, default=1500, help="total child wall limit, 180..3600 seconds")
    p.add_argument("--lod-wait", type=int, default=900, help="initial settle cap, 15..1800 seconds")
    p.add_argument("--blocks", type=int, default=12, help="4..24, multiple of four for balanced ABBA")
    action = p.add_mutually_exclusive_group()
    action.add_argument("--run", action="store_true", help="execute the plan, explicitly opt in")
    action.add_argument("--dry-run", action="store_true", help="print JSON plan only; no process, lock or output files")
    p.add_argument("--_worker", action="store_true", help=argparse.SUPPRESS)
    return p


def safe_path(path):
    # Resolve symlinked ancestors too, before any reading/restoring/writing.
    resolved = path.expanduser().resolve()
    if any("prism" in part.lower() for part in (*path.parts, *resolved.parts)):
        raise ValueError("Prism paths are forbidden")
    return resolved


def build_plan(a, root=ROOT):
    """Construct launch.py argv without running it (its own --dry-run writes dirs)."""
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,79}", a.label):
        raise ValueError("label must be 1..80 alphanumeric/hyphen/underscore characters")
    if (a.game_dir is None) == (a.world_snapshot is None):
        raise ValueError("choose exactly one of --game-dir or --world-snapshot")
    if a.reset_world and not a.game_dir:
        raise ValueError("--reset-world requires --game-dir; it cannot be combined with --world-snapshot")
    if a.mode != "spin" and a.spin_seconds != 10:
        raise ValueError("--spin-seconds applies only to --mode spin")
    world = a.world or ("travel" if a.mode == "flying" and a.world_snapshot else "bench")
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,79}", world):
        raise ValueError("world must be a simple save name")
    feature = getattr(a, "feature", "reuse")
    if feature not in ("reuse", "async", "items", "groups", "nodes", "prepare"): raise ValueError("archived feature; use the farsight-pre-async tag")
    if a.node_cache_mb != 64 and feature != "nodes":
        raise ValueError("node cache capacity requires feature nodes")
    profile = bool(getattr(a, "item_build_profile", False))
    if profile and (feature not in ("items", "groups", "nodes") or a.mode != "flying"):
        raise ValueError("item build profiling requires a flying items/groups/nodes comparison")
    field = {"reuse": FIELD, "async": ASYNC_FIELD, "items": ITEMS_FIELD, "groups": GROUPS_FIELD,
             "nodes": NODES_FIELD, "prepare": PREPARE_FIELD}[feature]
    runtime = JVM.copy()
    if feature == "async":
        runtime.update({"mc.lod.async": "true", "mc.lod.async.producerProbe": "true",
                        "mc.lod.async.producerProbe.refreshes": "0", "mc.lod.async.consumerProbe": "true",
                        "mc.lod.async.shade": "true", "mc.lod.async.shadeLayers": "true",
                        "mc.lod.async.shadeDiagnostics": "false", "mc.lod.async.shadeAtlasFamily": "true",
                        "mc.lod.materialInputs": "true"})
    elif feature == "items":
        runtime.update({"mc.lod.async": "false", "mc.lod.cacheItems": "false"})
    elif feature == "groups":
        runtime.update({"mc.lod.async": "false", "mc.lod.cacheItems": "true",
                        "mc.lod.cacheGroups": "false", "mc.lod.cacheGroups.diagnostics": "false"})
    elif feature == "nodes":
        runtime.update({"mc.lod.async": "false", "mc.lod.cacheItems": "true", "mc.lod.cacheGroups": "true",
                        "mc.lod.cacheNodeItems": "false", "mc.lod.cacheNodeItemsMB": str(a.node_cache_mb),
                        "mc.lod.cacheGroups.diagnostics": "false"})
    elif feature == "prepare":
        runtime.update({"mc.lod.async": "false", "mc.lod.asyncItems": "false",
                        "mc.lod.cacheItems": "true", "mc.lod.cacheGroups": "true",
                        "mc.lod.cacheNodeItems": "false", "mc.lod.atomicMeshHandoff": "true",
                        "mc.lod.cacheGroups.diagnostics": "false"})
    if profile:
        runtime["mc.lod.itemBuildProfile"] = "true"
    block_seconds = 10 if a.mode == "spin" else 5
    if not 4 <= a.blocks <= 24 or a.blocks % 4:
        raise ValueError("blocks must be a multiple of four in 4..24")
    if not 180 <= a.deadline <= 3600 or not 15 <= a.lod_wait <= 1800:
        raise ValueError("deadline must be 180..3600 and lod-wait 15..1800")
    if a.deadline < a.lod_wait + 30 + a.blocks * block_seconds + 90:
        raise ValueError("deadline must leave warmup, all blocks and 90 seconds for startup/shutdown/lock waiting")
    client, bench = map(safe_path, (a.client, a.bench))
    reset_world = safe_path(a.reset_world) if a.reset_world else None
    snapshot = reset_world or (safe_path(a.world_snapshot) if a.world_snapshot else None)
    out = safe_path(root / "results/reuse-run" / a.label)
    if not out.is_relative_to(root.resolve()):
        raise ValueError("run output must stay inside this checkout")
    if snapshot and (snapshot == out or snapshot.is_relative_to(out) or out.is_relative_to(snapshot)):
        raise ValueError("snapshot and output must not overlap")
    game = safe_path(a.game_dir) if a.game_dir else out / "game"
    if a.game_dir:
        if game == root.resolve() or not game.is_relative_to(root.resolve()):
            raise ValueError("existing game directory must be a folder inside this checkout")
        if game == out or game.is_relative_to(out) or out.is_relative_to(game):
            raise ValueError("existing game directory and output must not overlap")
        if snapshot and (snapshot == game or snapshot.is_relative_to(game) or game.is_relative_to(snapshot)):
            raise ValueError("snapshot and existing game directory must not overlap")
    fixture = dict(kind="pinned-snapshot" if snapshot else "shared-mutable-project-fixture",
                   pinned=bool(snapshot), path=str(snapshot or game), world=world,
                   restoredBeforeRun=bool(snapshot),
                   comparison="in-session A/B shares the same world and LOD/mesh cache; no identical cross-run content guarantee" if not snapshot
                   else "in-session A/B after restoring the verified pinned world")
    if reset_world:
        fixture.update(kind="pinned-world-mutable-project-cache", pinnedScope="world", gameDir=str(game),
                       lodCachePinned=False, lodCacheMutable=True, lodCachePreserved=True,
                       comparison="same pinned WORLD start; existing LOD/mesh cache is preserved, mutable and unpinned; no identical cache guarantee across runs")
    held = a.mode in ("spot", "vista")
    props = {
        "mode": "abab", "world": world, "abab.toggles": field + "=false|true",
        "abab.preset": "heavy" if held else "travel" if a.mode == "flying" else "spin",
        "abab.order": "abba", "abab.blocks": str(a.blocks), "abab.blockSeconds": str(block_seconds),
        "abab.bins": "4", "abab.segments": "1",
        "abab.discard": "30", "abab.minCell": "20", "abab.frames": "true",
        "abab.stop": "false", "abab.stopOn": "median2,pairP99",
        "warmup": "30", "lodWait": str(a.lod_wait), "timeout": str(a.deadline - 15),
        "config": "true", "photo": "0", "f3": "off", "frameTrace": "false",
        "cmds": NOON, "measureCmds": "time set 6000", "spin": "Infinity" if held else str(a.spin_seconds),
        "configClasses": "net.minecraft.client.renderer.chunk.Lod," + field.split("#")[0],
    }
    if feature == "async":
        props.update({"abab.discard": "120", "abab.warmupSwitch": "5", "abab.lodCounters": "true"})
        if a.mode != "spin":
            props["abab.bins"] = "1"  # Retain enough samples after the longer drain at current low FPS.
    elif feature in ("items", "groups", "nodes", "prepare"):
        props.update({"abab.warmupSwitch": "5", "abab.lodCounters": "true"})
        if a.mode != "spin": props["abab.bins"] = "1"
    if a.mode == "flying":
        props["speed"] = "10.9"  # Bench.travel ignores bench.pos; saved player is the origin.
    else:
        props.update(pos="6.5,180,6.5" if a.mode == "vista" else "6.5,106,6.5",
                     pitch="18" if a.mode == "vista" else "12", yaw="200" if held else "0")
        if held:
            props["abab.heavyYaw"] = "200"
    option_file = root / "tools" / ("travel-options.txt" if a.mode == "flying" else "static-options.txt")
    options = [line.strip() for line in option_file.read_text().splitlines() if line.strip() and not line.startswith("#")]
    options += ["fullscreen:true", "enableVsync:false", "maxFps:260"]
    cmd = [sys.executable, "-B", str(root / "tools/launch.py"), "--jar", str(client),
           "--bench-jar", str(bench), "--game-dir", str(game), "--api", "metal", "--lane", "timing",
           "--bench", str(out / "result.json"),
           "--label", a.label, "--quiet-gate", "4.0:900", "--overlay-check", "--overlay-wait", "900"]
    if snapshot:
        cmd += ["--world-snapshot", str(snapshot), "--world-only"]
    for option in options:
        cmd += ["--opt", option]
    cmd += ["--jvm=-D" + k + "=" + v for k, v in runtime.items()]
    for key, value in props.items():
        cmd += ["--bench-prop", key + "=" + value]
    for key, value in ENV.items():
        cmd += ["--env", key + "=" + value]
    return dict(schema="farsight-reuse-run-1", feature=feature, toggleField=field, mode=a.mode, spinSeconds=a.spin_seconds, label=a.label, world=world,
                diagnosticOnly=profile, itemBuildProfile=profile, nodeCacheMiB=a.node_cache_mb,
                client=str(client), bench=str(bench), snapshot=str(snapshot) if snapshot else None,
                resetWorld=str(reset_world) if reset_world else None, output=str(out), fixture=fixture,
                gameDir=str(game), command=cmd, commandShell=shlex.join(cmd),
                analysisCommand=[sys.executable, "-B", str(root / "tools/abab.py"), str(out / "result.json")],
                deadlineSeconds=a.deadline, cleanupGraceSeconds=5, requestedProperties=runtime.copy(),
                benchProperties=props, options=options, environment=ENV.copy(),
                displayRequired="nativeDefault3024x1964@120", renderDistance=16 if a.mode == "flying" else 12,
                arms={"A": feature + " off", "B": feature + " on"}, releaseEligible=False,
                limitations=LIMITATIONS + ([
                    "CPU build-stage instrumentation is enabled. All FPS/intervals are diagnostic only, not accepted performance evidence."
                ] if profile else []) + ([
                    "Async OFF retains guarded selection, warmed program resources and completion polling; it is not a clean production-renderer baseline.",
                    "Pending async work may complete after an OFF switch. 120 frames are discarded; check a longer discard offline too. Completion counters can lag across arm boundaries.",
                    "Async independent-layer shading may reuse SOLID while CUTOUT falls back. Aggregate reuse=0 does not prove both layers fell back."
                ] if feature == "async" else []) + ([
                    "CPU item cache OFF still retains warmed cached metadata allocated during ON warmup; no async renderer or image reuse is enabled.",
                    "Item metadata caching changes rebuild cost only; a view with no rebuilds cannot demonstrate an active performance gain. Inspect per-block LOD counters."
                ] if feature == "items" else []) + ([
                    "Group lookup comparison holds item caching ON in both arms; OFF retains warmed lookup tables. No async renderer or image reuse is enabled.",
                    "A view with no item rebuilds cannot demonstrate a group-lookup gain. Check groupCache.buildsByMode and buildNsByMode; per-lookup diagnostics are disabled for timing."
                ] if feature == "groups" else []) + ([
                    "Node packet comparison holds item/group caches ON; OFF retains warmed packets. Inspect measured nodeItems counters for activity and fallback.",
                    "Standing/turning with zero rebuilds only checks the idle case. Flying must include both build modes and actual ON packet replay."
                ] if feature == "nodes" else []) + ([
                    "CPU packing holds item/group caching and atomic mesh handoff ON, node packets OFF; async far rendering and image reuse stay OFF.",
                    "Measurement-start can retain warmup active/pending state until the first rendered OFF frame. Block ends must reflect their actual arm.",
                    "Flying requires measured ON offers, acceptances, accepted clusters and worker time. Standing/spin may have no worker activity.",
                    "CPU boundary deltas include discarded transition frames. Rejections, fallbacks and upload deferrals are diagnostic; OFF deferrals can include pending work from the preceding ON arm.",
                    "Prepare timings are diagnostic only: no automatic performance or image approval. Current source and production builds are not certified or frozen by this runner."
                ] if feature == "prepare" else []) + ([
                    "Only the starting WORLD is pinned and restored. Existing LOD/mesh cache is preserved, mutable and unpinned; cross-run cache contents may differ."
                ] if reset_world else [] if snapshot else [
                    "Shared mutable project fixture: world/LOD/mesh cache content is not pinned or hashed. In-session A/B uses the same live fixture; normal launcher/game writes persist between runs."]))


def load_tool(name):
    spec = importlib.util.spec_from_file_location("reuse_" + name, ROOT / "tools" / (name + ".py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def worker_command(plan):
    # Canonical paths matter when this CLI was called from outside the repo.
    if plan.get("resetWorld"):
        fixture = ["--game-dir", plan["gameDir"], "--reset-world", plan["resetWorld"]]
    else:
        fixture = ["--world-snapshot", plan["snapshot"]] if plan["snapshot"] else ["--game-dir", plan["gameDir"]]
    return [sys.executable, "-B", str(Path(__file__).resolve()), plan["client"], plan["bench"],
            "--label", plan["label"], "--feature", plan.get("feature", "reuse"), "--mode", plan["mode"],
            "--spin-seconds", str(plan.get("spinSeconds", 10)), *fixture,
            "--world", plan["world"], "--deadline", str(plan["deadlineSeconds"]),
            "--lod-wait", plan["benchProperties"]["lodWait"], "--blocks", plan["benchProperties"]["abab.blocks"],
            "--node-cache-mb", str(plan.get("nodeCacheMiB", 64)),
            *(["--item-build-profile"] if plan.get("itemBuildProfile") else []),
            "--run", "--_worker"]


def digest(path, algorithm="sha256"):
    h = hashlib.new(algorithm)
    with Path(path).open("rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def verify_inputs(plan):
    """Read-only verification. Never chmod/copy/rebuild the caller's jars."""
    hashes = {}
    entries = {"client": [plan.get("toggleField",FIELD).split("#")[0].replace(".","/") + ".class"],
               "bench": ["bench/Bench.class", "bench/Abab.class", "bench/ConfigBlock.class"]}
    for role in ("client", "bench"):
        p = Path(plan[role])
        if not p.is_file() or p.suffix != ".jar" or p.stat().st_mode & 0o222:
            raise ValueError(role + " must be an existing read-only frozen .jar")
        if p == (ROOT / "build" / (role + ".jar")).resolve():
            raise ValueError("use an explicitly frozen jar, not build/" + role + ".jar")
        with zipfile.ZipFile(p) as jar:
            for entry in entries[role]:
                if entry not in jar.namelist():
                    raise ValueError(role + " jar lacks " + entry)
        hashes[role] = dict(path=str(p), sha256=digest(p), sha1=digest(p, "sha1"), size=p.stat().st_size)
    if not plan["snapshot"] or plan.get("resetWorld"):
        game = safe_path(Path(plan["gameDir"]))
        if game == ROOT or not game.is_relative_to(ROOT) or not game.is_dir():
            raise ValueError("existing game directory must exist inside this checkout")
        # Reject escaped fixture paths before launch.py can write options/logs or
        # the game can open the save/cache. No traversal or hashing of GB caches.
        for relative in ("saves", "saves/" + plan["world"], "lod", ".natives", "options.txt", "launch.log"):
            if not safe_path(game / relative).is_relative_to(game):
                raise ValueError("fixture path escapes its game directory: " + relative)
        if plan.get("resetWorld"):
            # pin.restore removes this tree. Even an in-fixture alias must not
            # redirect that removal into a different save or the retained cache.
            if any((game / relative).is_symlink() for relative in ("saves", "saves/" + plan["world"])):
                raise ValueError("world reset destination must not be a symlink")
            save, cache = game / "saves" / plan["world"], safe_path(game / "lod")
            if cache == save or cache.is_relative_to(save) or save.is_relative_to(cache):
                raise ValueError("world reset destination overlaps the retained LOD cache")
            snapshot = safe_path(Path(plan["snapshot"]))
            if str(game) != plan["gameDir"] or str(snapshot) != plan["resetWorld"]:
                raise ValueError("world reset paths changed since planning")
        if not (game / "saves" / plan["world"] / "level.dat").is_file():
            raise ValueError("existing fixture lacks saves/" + plan["world"] + "/level.dat")
    if not plan["snapshot"]:
        return dict(jars=hashes, fixture=plan["fixture"], snapshotId=None, snapshotManifestSha256=None)
    snapshot = Path(plan["snapshot"])
    if not (snapshot / "saves" / plan["world"] / "level.dat").is_file():
        raise ValueError("snapshot lacks saves/" + plan["world"] + "/level.dat")
    sid = load_tool("pin").verify(str(snapshot))
    return dict(jars=hashes, fixture=plan["fixture"], snapshotId=sid, snapshotManifestSha256=digest(snapshot / "SHA256SUMS"))


def child_environment(source):
    env = {k: v for k, v in source.items() if k not in INJECT_NAMES and not k.startswith(INJECT_PREFIXES)}
    env.update(ENV)
    env["MC_GPU_LOCK"] = LOCK
    env.pop("MC_GPU_LOCK_HELD", None)
    env["PYTHONDONTWRITEBYTECODE"] = "1"
    return env


def prism_clear(timeout=5):
    result = subprocess.run(["pgrep", "-f", PRISM_PATTERN], stdout=subprocess.DEVNULL,
                            stderr=subprocess.DEVNULL, timeout=timeout)
    if result.returncode == 0:
        raise RuntimeError("Prism game process detected; benchmark aborted (fail closed)")
    if result.returncode != 1:
        raise RuntimeError("Prism process check unavailable: pgrep exit " + str(result.returncode) + " (fail closed)")


def display_ok(d):
    return (isinstance(d, dict) and d.get("default") is True and d.get("pixels") == "3024x1964"
            and d.get("points") == "1512x982" and d.get("hz") == 120)


def read_display():
    # fastlaunch.display_mode would silently compile a missing/stale helper.
    exe, src = ROOT / "build/displaymode", ROOT / "tools/swift/displaymode.swift"
    if not exe.is_file() or exe.stat().st_mtime < src.stat().st_mtime:
        raise RuntimeError("a prebuilt, current build/displaymode is required; no compilation here")
    p = subprocess.run([str(exe)], capture_output=True, text=True, check=True, timeout=10)
    d = json.loads(p.stdout.strip().splitlines()[-1])
    mode = dict(points=f"{d['width']}x{d['height']}", pixels=f"{d['pixelWidth']}x{d['pixelHeight']}",
                hz=d["refreshHz"], default=d.get("default"), builtin=d.get("builtin"))
    if not display_ok(mode):
        raise RuntimeError("required native Default 3024x1964 @ 120 display is not active")
    return mode


def write_json(path, value):
    Path(path).write_text(json.dumps(value, indent=2, allow_nan=False) + "\n")


def read_json(path):
    def invalid(value):
        raise ValueError("nonfinite JSON constant: " + value)
    return json.loads(Path(path).read_text(), parse_constant=invalid)


def worker(plan):
    """The parent owns this whole session; launch.py and Java inherit its lock FDs."""
    gpulock = load_tool("gpulock")
    prism_clear()
    hold = gpulock.acquire("timing", keys=[("gamedir:" + plan["gameDir"], gpulock.EX)],
                           label="reuse-" + plan["label"], lock=LOCK)
    try:
        if hold.nested or hold.gpu != gpulock.EX or not hold.fds:
            raise RuntimeError("sidecar must own an exclusive timing lock; run outside ancestor lock wrappers")
        # Check the actual kernel lock, not only the informational environment flag.
        with open(LOCK, "rb") as probe:
            try:
                fcntl.flock(probe, fcntl.LOCK_SH | fcntl.LOCK_NB)
            except BlockingIOError:
                pass
            else:
                fcntl.flock(probe, fcntl.LOCK_UN)
                raise RuntimeError("exclusive GPU lock probe unexpectedly succeeded")
        prism_clear()
        evidence = dict(exclusiveLock=True, lock=str(Path(LOCK).resolve()), prismClear=True,
                        displayBefore=read_display(), inputs=verify_inputs(plan), command=plan["command"])
        out = Path(plan["output"])
        write_json(out / "guards.json", evidence)
        with (out / "launch-wrapper.log").open("x") as log:
            child = subprocess.Popen(plan["command"], cwd=ROOT, stdout=log, stderr=subprocess.STDOUT,
                                     env=hold.child_env(child_environment(os.environ)), pass_fds=tuple(hold.fds))
            rc = child.wait()  # Entire group, including lock wait, is supervised by the parent.
        # Capture settle evidence while still holding the fixture lock: another
        # run may replace a shared launch.log immediately after we release it.
        evidence["initialLodState"] = settled_state((Path(plan["gameDir"]) / "launch.log").read_text(errors="replace"))
        write_json(out / "guards.json", evidence)
        prism_clear()
        evidence["displayAfter"] = read_display()
        write_json(out / "guards.json", evidence)
        return rc
    finally:
        hold.release()


def settled_state(log):
    states = []
    for line in log.splitlines():
        marker = "[bench] lod settled after "
        if marker in line:
            start = line.find("{", line.index(marker))
            if start >= 0:
                states.append(json.loads(line[start:]).get("state"))
    return states[-1] if states else None


def positive(x):
    return type(x) in (int, float) and math.isfinite(x) and x > 0


def node_activity(data):
    """CPU boundary deltas include switch-discard frames, unlike pooled FPS."""
    ab = data.get("abab") or {}
    snapshots = (ab.get("lodCounters") or {}).get("snapshots") or []
    blocks = ab.get("blocks") or []
    errors, rows = [], []
    def integers(values, count):
        return isinstance(values, list) and len(values) == count and all(type(v) is int and v >= 0 for v in values)
    if not blocks or len(blocks) != ab.get("blocksRun") or len(snapshots) != len(blocks) + 1:
        return dict(errors=["node packet boundary counters missing"], blocks=rows)
    for index, block in enumerate(blocks):
        if type(block.get("index")) is not int or block["index"] != index or block.get("arm") != "ABBA"[index % 4]:
            errors.append("node packet blocks are not the complete ordered ABBA sequence")
    caches = []
    for index, snapshot in enumerate(snapshots):
        state = (snapshot.get("lod") or {}).get("state") or {}
        gpu = state.get("gpu") or {}
        cache = gpu.get("nodeItems") or {}
        arm = "A" if index == 0 else "ABBA"[(index - 1) % 4]
        expected = {"boundary": "measurement-start" if index == 0 else "block-end",
                    "completedBlock": index - 1, "activeArm": arm,
                    "intervalArm": None if index == 0 else arm}
        if (any(snapshot.get(k) != v for k, v in expected.items()) or
                snapshot.get("toggleReadbacks") != [{"field": NODES_FIELD, "value": "true" if arm == "B" else "false"}]
                or cache.get("enabled") is not (arm == "B")):
            errors.append("node packet boundary identity or toggle readback does not match its arm")
        if ((gpu.get("itemCache") or {}).get("enabled") is not True or
                (gpu.get("groupCache") or {}).get("enabled") is not True or
                (gpu.get("groupCache") or {}).get("diagnostics") is not False):
            errors.append("node comparison did not hold item/group caching on with diagnostics off")
        if (not integers(cache.get("buildsByMode"), 2) or not integers(cache.get("buildNsByMode"), 2) or
                not integers([cache.get("packetHits"), cache.get("replayedClusters")], 2)):
            errors.append("node packet counters malformed or missing")
        caches.append(cache)
    if errors:
        return dict(errors=sorted(set(errors)), blocks=rows)
    for block, before, after in zip(blocks, caches, caches[1:]):
        arm = block.get("arm")
        if arm not in ("A", "B"):
            errors.append("node comparison requires whole-arm blocks")
            continue
        mode = 0 if arm == "A" else 1
        counts = [b-a for a,b in zip(before["buildsByMode"], after["buildsByMode"])]
        times = [b-a for a,b in zip(before["buildNsByMode"], after["buildNsByMode"])]
        hits = after["packetHits"] - before["packetHits"]
        clusters = after["replayedClusters"] - before["replayedClusters"]
        if min(*counts, *times, hits, clusters) < 0:
            errors.append("node packet counters decreased")
        if counts[1-mode] or times[1-mode] or (mode == 0 and (hits or clusters)):
            errors.append("node packet activity occurred in the wrong arm")
        rows.append(dict(block=block.get("index"), arm=arm, builds=counts[mode],
                         buildNs=times[mode], packetHits=hits, replayedClusters=clusters))
    return dict(errors=sorted(set(errors)), blocks=rows, includesDiscardedFrames=True)


def prepare_state_errors(state, enabled, after_frames=True):
    """The reflected switch is immediate; active/pending clear on an OFF frame."""
    state = state if isinstance(state, dict) else {}
    def obj(value):
        return value if isinstance(value, dict) else {}
    gpu = obj(state.get("gpu"))
    packing = obj(gpu.get("asyncItems"))
    errors = []
    if (any(type(packing.get(k)) is not bool for k in ("enabled", "active", "pending", "failed"))
            or not isinstance(packing.get("error"), str)
            or any(type(packing.get(k)) is not int or packing[k] < 0 for k in PREPARE_COUNTERS)):
        errors.append("prepare counters malformed or missing")
    if packing.get("enabled") is not enabled:
        errors.append("prepare enabled state does not match its arm")
    if packing.get("failed") is not False or packing.get("error") != "":
        errors.append("prepare worker failed or reported an error")
    if ((after_frames and (packing.get("active") is not enabled or
                          (not enabled and packing.get("pending") is not False))) or
            (packing.get("pending") is True and packing.get("active") is not True)):
        errors.append("prepare active/pending state does not match rendered arm")
    if (obj(gpu.get("itemCache")).get("enabled") is not True or
            obj(gpu.get("groupCache")).get("enabled") is not True or
            obj(gpu.get("groupCache")).get("diagnostics") is not False or
            obj(gpu.get("nodeItems")).get("enabled") is not False or
            obj(state.get("meshHandoff")).get("enabled") is not True):
        errors.append("prepare did not hold item/group caches and atomic handoff on, node packets/diagnostics off")
    if (obj(gpu.get("reuse")).get("enabled") is not False or
            "asyncConsumer" not in gpu or gpu["asyncConsumer"] is not None):
        errors.append("prepare did not keep async far rendering and image reuse off")
    return errors


def prepare_activity(data):
    """Measured CPU deltas, never warmup totals or a performance/image verdict."""
    ab = data.get("abab") or {}
    snapshots = (ab.get("lodCounters") or {}).get("snapshots") or []
    blocks = ab.get("blocks") or []
    errors, rows, counters = [], [], []
    if (not isinstance(blocks, list) or not blocks or len(blocks) != ab.get("blocksRun") or
            not isinstance(snapshots, list) or len(snapshots) != len(blocks) + 1 or
            not all(isinstance(s, dict) for s in [*blocks, *snapshots])):
        return dict(errors=["prepare boundary counters missing"], blocks=rows)
    frame_ends = [0]
    for index, block in enumerate(blocks):
        if type(block.get("index")) is not int or block["index"] != index or block.get("arm") != "ABBA"[index % 4]:
            errors.append("prepare blocks are not the complete ordered ABBA sequence")
        if (type(block.get("startFrame")) is not int or block["startFrame"] != frame_ends[-1] or
                type(block.get("frames")) is not int or block["frames"] <= 0):
            errors.append("prepare boundaries require actual complete rendered blocks")
            break
        frame_ends.append(frame_ends[-1] + block["frames"])
    if errors or frame_ends[-1] != ab.get("framesRecorded"):
        return dict(errors=sorted(set(errors or ["prepare rendered frame count differs"])), blocks=rows)
    for index, snapshot in enumerate(snapshots):
        arm = "A" if index == 0 else "ABBA"[(index - 1) % 4]
        expected = {"boundary": "measurement-start" if index == 0 else "block-end",
                    "completedBlock": index - 1, "activeArm": arm,
                    "intervalArm": None if index == 0 else arm, "frameIndex": frame_ends[index]}
        if (any(snapshot.get(k) != v for k, v in expected.items()) or snapshot.get("error") or
                type(snapshot.get("frameIndex")) is not int or type(snapshot.get("completedBlock")) is not int or
                snapshot.get("toggleReadbacks") != [{"field": PREPARE_FIELD, "value": "true" if arm == "B" else "false"}]):
            errors.append("prepare boundary identity or toggle readback does not match its arm")
        state = (snapshot.get("lod") or {}).get("state") or {}
        errors.extend(prepare_state_errors(state, arm == "B", after_frames=index > 0))
        counters.append((state.get("gpu") or {}).get("asyncItems") or {})
    if "prepare counters malformed or missing" in errors:
        return dict(errors=sorted(set(errors)), blocks=rows)
    for block, before, after in zip(blocks, counters, counters[1:]):
        delta = {key: after[key] - before[key] for key in PREPARE_COUNTERS}
        if any(value < 0 for value in delta.values()):
            errors.append("prepare counters decreased")
        # upload() precedes frame() clearing pending ON work. Preserve these OFF
        # deferrals as diagnostics; worker accounting happens only in an ON frame.
        if block["arm"] == "A" and any(delta[k] for k in PREPARE_COUNTERS if k != "uploadDefers"):
            errors.append("prepare worker activity occurred in the OFF arm")
        rows.append(dict(block=block["index"], arm=block["arm"], **delta))
    totals = {arm: {key: sum(r[key] for r in rows if r["arm"] == arm) for key in PREPARE_COUNTERS}
              for arm in ("A", "B")}
    return dict(errors=sorted(set(errors)), blocks=rows, byArm=totals, includesDiscardedFrames=True,
                diagnosticDifferenceBMinusA={key: totals["B"][key] - totals["A"][key]
                                            for key in ("rejected", "fallbacks", "uploadDefers")},
                warmupExcluded=True, performanceApproved=False, imageApproved=False)


def summarize(data):
    """Preserve current abab.py metrics, directions and denominators verbatim."""
    ab = data.get("abab") or {}
    arms = ab.get("arms") or {}
    effects = ab.get("effects") or {}
    fps_effects = {}
    for metric in ("median2", "pairP99"):
        e = effects.get(metric) or {}
        est, ci = e.get("logRatio"), e.get("ci")
        if (type(est) in (int, float) and math.isfinite(est) and isinstance(ci, list) and len(ci) == 2
                and all(type(v) in (int, float) and math.isfinite(v) for v in ci)):
            # tools/abab.py: time ratios are inverted for the FPS view.
            fps_effects[metric] = dict(percent=math.expm1(-est) * 100,
                                      intervalPercent=sorted(math.expm1(-v) * 100 for v in ci),
                                      holmP=e.get("holmP"), call=e.get("call"))
    return dict(arms={arm: arms.get(arm) for arm in ("A", "B")},
                primaryEffectsFps=fps_effects, recordedEffects=effects,
                metricDefinitions=dict(medianFps="pooled kept frame median FPS, per arm",
                    median2Fps="pooled consecutive kept two-frame mean median FPS, per arm",
                    p1LowFps="global pooled kept-frame p99 inverse, per arm (classic 1% low)",
                    pairP99="primary effect of per-pair whole-arm p99; not the pooled global low",
                    p99="secondary sector p99; not global 1% low"),
                topLevelFpsUsed=False, releaseEligible=False)


def validate_result(data, plan, inputs, guards, initial_state, exit_code=0, unchanged=True):
    """Fail closed on missing receipt evidence; no settings inferred from a label."""
    reasons = []
    def require(ok, message):
        if not ok:
            reasons.append(message)
    require(exit_code == 0 and not data.get("error"), "process/result failed")
    require(unchanged and guards.get("inputs") == inputs, "frozen jars changed or inputs were not verified under lock")
    require(inputs.get("fixture") == plan["fixture"], "fixture provenance not verified")
    require(guards.get("command") == plan["command"], "executed launch plan differs from receipt")
    require(guards.get("exclusiveLock") is True and guards.get("prismClear") is True, "exclusive lock/Prism guard unverified")
    launch = data.get("launch") or {}
    for name, disp in (("before", guards.get("displayBefore")), ("launch", launch.get("display")),
                       ("after", guards.get("displayAfter"))):
        require(display_ok(disp), name + " display is not verified native Default 3024x1964@120")
    require((launch.get("quietGate") or {}).get("ok") is True, "quiet gate failed or missing")
    require(launch.get("overlays") is False, "overlay check failed or missing")
    require(data.get("label") == plan["label"] and data.get("mode") == "abab", "wrong result label/mode")
    require(data.get("backend") == "Metal" and data.get("framebuffer") == "3024x1964", "wrong backend/framebuffer")
    require(data.get("renderDistance") == plan["renderDistance"], "wrong actual render distance")
    config = data.get("config") or {}
    properties = config.get("properties") or {}
    required = {**plan["requestedProperties"], **{"bench." + k: v for k, v in plan["benchProperties"].items()}}
    require(all(properties.get(k) == v for k, v in required.items()), "actual JVM/bench properties differ or are missing")
    if plan["snapshot"]:
        sid = os.path.relpath(plan["snapshot"], ROOT) + "@" + inputs["snapshotId"]
        require(properties.get("bench.pinnedWorld") == sid, "pinned scene identity not verified")
    else:
        require(not properties.get("bench.pinnedWorld") and inputs.get("snapshotId") is None
                and inputs.get("snapshotManifestSha256") is None, "shared mutable fixture incorrectly claims pinned content")
    require(all(((config.get("jars") or {}).get(role) or {}).get("sha1") == inputs["jars"][role]["sha1"]
                for role in ("client", "bench")), "loaded client/bench hashes not verified")
    env = config.get("env") or {}
    require(all(env.get(k) == v for k, v in ENV.items()) and
            not any(k not in ENV and (k in INJECT_NAMES or k.startswith(INJECT_PREFIXES)) for k in env),
            "validation/instrumentation environment differs or is missing")
    native = config.get("native") or {}
    require(native.get("validation") == 0 and native.get("profilePasses") == 0, "native validation/profiling not verified off")
    game = config.get("game") or {}
    require(game.get("enableVsync") == "false" and game.get("framerateLimit") == "260", "uncapped/no-vsync options not verified")
    require(game.get("ambientOcclusion") == "true" and game.get("entityShadows") == "true"
            and game.get("cutoutLeaves") == "true" and game.get("mipmapLevels") == "4"
            and game.get("cloudStatus") == "FANCY" and game.get("fov") == "70", "full-detail video options not verified")
    lod = (config.get("toggles") or {}).get("Lod") or {}
    require(all(lod.get(k) == v for k, v in {"BUILD": True, "draw": True, "layerMask": 7,
                "RADIUS": 512, "TAU": 2, "GPU_BYTES": 6144 << 20, "secOccl": True, "area": False}.items()),
            "effective Lod fields not verified")
    state = (data.get("lod") or {}).get("state") or {}
    profile = (state.get("gpu") or {}).get("itemBuildProfile")
    if plan.get("itemBuildProfile"):
        require(isinstance(profile, dict) and profile.get("enabled") is True,
                "requested item build profile missing or disabled")
        snapshots = ((data.get("abab") or {}).get("lodCounters") or {}).get("snapshots") or []
        def profile_attempts(snapshot):
            snapshot_state = (snapshot.get("lod") or {}).get("state") or {}
            value = (snapshot_state.get("gpu") or {}).get("itemBuildProfile") or {}
            modes = value.get("modes") or []
            if value.get("enabled") is not True or len(modes) != 4:
                return None
            counts = [(m.get("completed") or {}).get("attempts") for m in modes]
            if not all(type(n) is int and n >= 0 for n in counts):
                return None
            return sum(counts)
        before = profile_attempts(snapshots[0]) if snapshots else None
        after = profile_attempts(snapshots[-1]) if snapshots else None
        require(before is not None and after is not None and after > before,
                "no completed item builds profiled during measurement")
    else:
        require(properties.get("mc.lod.itemBuildProfile", "false") == "false"
                and not (isinstance(profile, dict) and profile.get("enabled") is True),
                "unrequested CPU profiling contaminates FPS evidence")
    require(positive(data.get("lodSettleSeconds")), "initial LOD settle failed or missing")
    initial = initial_state or {}
    require(initial.get("idle") is True and initial.get("tau") == 2 and positive(initial.get("selected")),
            "settle-log idle/actual tau=2/selection not verified")
    require(state.get("tau") == 2 and positive(state.get("selected")), "final actual tau=2/selection not verified")
    require(positive((initial.get("gpu") or {}).get("gpuMB")) and positive((state.get("gpu") or {}).get("gpuMB")),
            "resident LOD meshes not verified at settle and end")
    if plan["mode"] != "flying":
        require(state.get("idle") is True, "final static LOD is not idle")
    ab = data.get("abab") or {}
    props = plan["benchProperties"]
    if plan.get("feature") == "prepare":
        activity = prepare_activity(data)
        reasons.extend(activity["errors"])
        for label, boundary_state in (("initial", initial), ("final", state)):
            reasons.extend(label + " " + error for error in prepare_state_errors(boundary_state, False))
        gpu_flags = (config.get("toggles") or {}).get("LodGpu") or {}
        require(all(gpu_flags.get(k) is v for k, v in dict(cacheItems=True, cacheGroups=True,
                    cacheNodeItems=False, asyncItems=False, GROUP_LOOKUP_DIAGNOSTICS=False).items()),
                "effective prepare LodGpu fields not verified")
        if plan["mode"] == "flying":
            rows = activity["blocks"]
            require(all(sum(r[k] for r in rows if r["arm"] == "B") > 0
                        for k in ("offers", "accepted", "acceptedClusters", "workerNs")),
                    "flying prepare comparison did not exercise measured ON worker acceptance of nonempty clusters")
    if plan.get("feature") == "nodes":
        activity = node_activity(data)
        reasons.extend(activity["errors"])
        expected_budget = plan.get("nodeCacheMiB", 64) << 20
        snapshots = ((data.get("abab") or {}).get("lodCounters") or {}).get("snapshots") or []
        require(all(((((s.get("lod") or {}).get("state") or {}).get("gpu") or {}).get("nodeItems") or {}).get("budgetBytes") == expected_budget
                    for s in snapshots), "node packet CPU budget differs from requested capacity")
        if plan["mode"] == "flying":
            rows = activity["blocks"]
            require(all(sum(r["builds"] for r in rows if r["arm"] == arm) > 0 for arm in ("A", "B"))
                    and sum(r["packetHits"] for r in rows if r["arm"] == "B") > 0
                    and sum(r["replayedClusters"] for r in rows if r["arm"] == "B") > 0,
                    "flying node comparison did not exercise both builds and nonempty ON packet replay")
    if plan["mode"] == "spin":
        require(ab.get("spinSeconds") == float(props["spin"]), "actual spin period differs or missing")
    for key, expected in {"preset": props["abab.preset"], "order": "abba", "segments": int(props["abab.segments"]),
                          "bins": int(props["abab.bins"]), "blockSeconds": int(props["abab.blockSeconds"]), "minCell": 20,
                          "discardFrames": int(props["abab.discard"]), "blocksRun": int(props["abab.blocks"]),
                          "blocksPlanned": int(props["abab.blocks"]), "dropped": 0}.items():
        require(ab.get(key) == expected, "ABAB " + key + " differs or missing")
    require((ab.get("stop") or {}).get("enabled") is False, "unexpected or missing sequential stopping config")
    toggles = ab.get("toggles") or []
    require(len(toggles) == 1 and all(toggles[0].get(k) == v for k, v in {
        "field": plan.get("toggleField",FIELD), "a": "false", "b": "true", "readBackA": "false", "readBackB": "true", "type": "boolean"}.items()),
        "selected A/B toggle/readbacks not verified")
    require(positive(ab.get("switches")), "no measured arm switches")
    arms = ab.get("arms") or {}
    for arm in ("A", "B"):
        row = arms.get(arm) or {}
        require(all(positive(row.get(k)) for k in ("frames", "medianFps", "median2Fps", "avgFps", "p1LowFps")),
                arm + " pooled central/global 1% metrics missing or invalid")
    frames = ab.get("frames") or {}
    n = ab.get("framesRecorded")
    require(positive(n) and all(isinstance(frames.get(k), list) and len(frames[k]) == n
                               for k in ("dtNs", "gpuBusyNs", "bin", "block", "keep")), "per-frame arrays missing or incomplete")
    require(len(summarize(data)["primaryEffectsFps"]) == 2, "primary median2/pairP99 intervals unavailable (possibly sparse cells)")
    for metric in ("median2", "pairP99"):
        require(((ab.get("effects") or {}).get(metric) or {}).get("pairs") == int(props["abab.blocks"]) // 2,
                metric + " does not contain all planned pairs")
    return reasons


def stop_owned_group(child):
    """Escalate even if the group leader exits first: Java may still hold the lock."""
    try:
        os.killpg(child.pid, signal.SIGTERM)
    except ProcessLookupError:
        pass
    try:
        child.wait(timeout=3)
    except subprocess.TimeoutExpired:
        pass
    try:
        os.killpg(child.pid, signal.SIGKILL)
    except ProcessLookupError:
        pass
    child.wait(timeout=2)


def run(plan):
    out = Path(plan["output"])
    if out.exists():
        raise ValueError("refusing to overwrite run " + str(out))
    # All actual work (including verification of large pinned scenes) is bounded
    # in the child. The parent only makes its new receipt/output directory.
    out.mkdir(parents=True, exist_ok=False)
    receipt = dict(plan, started=time.time(), status="starting", releaseEligible=False, performanceEvidence=False)
    receipt_path = out / "receipt.json"
    write_json(receipt_path, receipt)
    child = None
    failure = None
    rc = 1
    def interrupted(signum, frame):
        raise InterruptedError("sidecar interrupted by signal " + str(signum))
    previous = {s: signal.signal(s, interrupted) for s in (signal.SIGINT, signal.SIGTERM)}
    try:
        prism_clear()
        command = worker_command(plan)
        receipt["workerCommand"] = command
        write_json(receipt_path, receipt)
        end = time.monotonic() + plan["deadlineSeconds"]
        with (out / "worker.log").open("x") as log:
            child = subprocess.Popen(command, cwd=ROOT, env=child_environment(os.environ),
                                     stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
            while child.poll() is None:
                remaining = end - time.monotonic()
                if remaining <= 0:
                    raise TimeoutError("owned child deadline exceeded (includes lock wait)")
                prism_clear(timeout=min(5, remaining))
                remaining = end - time.monotonic()
                if remaining <= 0:
                    raise TimeoutError("owned child deadline exceeded (includes lock wait)")
                try:
                    child.wait(timeout=min(1, remaining))
                except subprocess.TimeoutExpired:
                    pass
            rc = child.returncode
    except (OSError, RuntimeError, subprocess.SubprocessError, TimeoutError) as e:
        failure = str(e)
        rc = 124 if isinstance(e, TimeoutError) else 1
    finally:
        # Ignore repeated interrupts only while reaping our own process group.
        for s in previous:
            signal.signal(s, signal.SIG_IGN)
        try:
            if child is not None:
                try:
                    stop_owned_group(child)
                except (OSError, subprocess.SubprocessError) as e:
                    failure = "owned group cleanup failed: " + str(e)
                    rc = 1
        finally:
            for s, handler in previous.items():
                signal.signal(s, handler)
    reasons = [failure] if failure else []
    try:
        guards = read_json(out / "guards.json")
        inputs = guards["inputs"]
        receipt.update(guards=guards, inputs=inputs)
        # Live fixture/cache content remains mutable in every mode. The worker
        # recorded the verified pin manifest before any requested world restore.
        unchanged = all(digest(p["path"]) == p["sha256"] for p in inputs["jars"].values())
        data = read_json(out / "result.json")
        initial = guards.get("initialLodState")
        receipt.update(inputs=inputs, guards=guards, jarsUnchanged=unchanged,
                       resultSha256=digest(out / "result.json"), actualSettings=data.get("config"),
                       display=(data.get("launch") or {}).get("display"), initialLodState=initial,
                       finalLod=data.get("lod"), lodSettleSeconds=data.get("lodSettleSeconds"))
        reasons += validate_result(data, plan, inputs, guards, initial, rc, unchanged)
        if plan.get("feature") == "nodes":
            receipt["nodeActivity"] = node_activity(data)
        if plan.get("feature") == "prepare":
            receipt["prepareActivity"] = prepare_activity(data)
        receipt["summary"] = None if reasons else summarize(data)
    except (OSError, ValueError, KeyError, TypeError, AttributeError, OverflowError) as e:
        reasons.append("missing/invalid result evidence: " + str(e))
    timing_valid = not reasons and not plan.get("diagnosticOnly", False)
    receipt.update(completed=time.time(), exitCode=rc, guardsValid=not reasons,
                   timingEvidenceValid=timing_valid, performanceEvidence=timing_valid and plan.get("feature") != "prepare",
                   rejections=reasons, status="rejected" if reasons else
                   "profile-diagnostic-only" if plan.get("diagnosticOnly") else "measured-diagnostic-only")
    write_json(receipt_path, receipt)
    print(json.dumps(dict(receipt=str(receipt_path), guardsValid=not reasons, timingEvidenceValid=timing_valid,
                          performanceEvidence=receipt["performanceEvidence"],
                          **({"prepareActivity": receipt.get("prepareActivity")} if plan.get("feature") == "prepare" else {}),
                          fixture=plan["fixture"], rejections=reasons, summary=receipt.get("summary"), releaseEligible=False), indent=2))
    return rc if rc else (1 if reasons else 0)


def main(argv=None):
    argv = list(sys.argv[1:] if argv is None else argv)
    p = parser()
    a = p.parse_args(argv)
    try:
        plan = build_plan(a)
        if a._worker:
            if not a.run or a.dry_run or os.getsid(0) != os.getpid():
                raise ValueError("internal worker requires its own supervised session and --run")
            return worker(plan)
        if not a.run:
            print(json.dumps(dict(plan, status="plan-only; inputs and runtime not checked"), indent=2))
            return 0
        return run(plan)
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError, zipfile.BadZipFile) as e:
        print("reuse-run: " + str(e), file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
