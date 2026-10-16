#!/usr/bin/env python3
"""Startup A/B (results/LAUNCH-SPEED.md): interleaved launches that stop a few seconds after world ready
(bench.exitAfterReady), each through tools/launch.py (game lock, pinned world copy, quick play / AOT / warm-up options),
with bench-one.sh's static settings (fullscreen, tools/static-options.txt). Prints per-arm medians of the startup phases
(bench/src/bench/Startup.java) and the launch.py wall time, and writes results/raw/<prefix>-startup.tsv.

usage: startup-bench.py [--full] [--from-round=N] <prefix> <rounds> <arm>=<launch.py args> [<arm>=<launch.py args> ...]
  --full: complete static benchmark runs (bench-one.sh: 15 s warmup, 30 s measured) instead of stopping 3 s after world
  ready; the table then adds median / avg / 1% low / 0.1% low FPS per arm (FPS A/B of a launch option).
  --from-round=N resumes an interrupted session (rounds before N are read back from their JSONs).
  e.g. startup-bench.py fl-qp 5 'title=--quickplay off' 'qp=--quickplay on' -- --jar build/client-r5.jar --api metal
  Arguments after `--` go to every arm. Arms run in ABBA order (A B B A A B ...), so drift cancels.
  Each run waits up to 45 s for the 1-minute load average to fall below 4.0 (game runs and builds are serialized by the
  game lock anyway; the average lags a just-finished game); launch.py records the load and the CG display mode at the
  start; runs whose display mode differs from the first run's are reported and left out of the medians.

  After the per-arm medians: the launch stamps (ms since process start; titleWidgets needs --bench-prop titleSettle=true on
  the title path), the resource-reload timings (--bench-prop reloads=N, ms from request), and the paired differences of every
  other arm against the first one, round by round (median and mean difference, rounds where it is lower, t).
  Stage-2 W7 (instant UI transitions) on vs off, existing world and new world (results/W7-UI-TRANSITIONS.md):
    startup-bench.py w7-ex 4 'on=--jvm=-Dmc.ui.instantTransitions=true' 'off=--jvm=-Dmc.ui.instantTransitions=false' -- \
      --jar build/client-LABEL.jar --bench-jar build/bench-LABEL.jar --api metal --quickplay off \
      --bench-prop titleSettle=true --bench-prop reloads=2
    (new world: add --world-snapshot none --fresh-world bench after --; the bench creates world "bench" from the title screen)
"""
import json, os, shlex, statistics, subprocess, sys, time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PHASES = ["jvm", "preWindow", "init", "reload", "fade", "title", "open", "levelLoad", "openToReady", "total"]
FPS = ["medianFps", "avgFps", "p1LowFps", "p01LowFps", "maxFrameMs"]
STAMPS = ["mainStart", "window", "firstFrame", "reloadDone", "overlayGone", "titleShown", "titleWidgets", "worldOpen",
          "levelJoined", "worldReady"]


def load():
    return float(subprocess.run(["sysctl", "-n", "vm.loadavg"], capture_output=True, text=True).stdout.split()[1])


def wait_quiet(limit=4.0, timeout=45):
    t0 = time.time()
    while load() >= limit and time.time() - t0 < timeout:
        time.sleep(5)
    return load()


def row_of(label, name, r):
    try:
        d = json.load(open(os.path.join(ROOT, "results", "raw", f"{label}.json")))
    except (OSError, ValueError):
        return None
    st = d.get("startup") or {}
    li = d.get("launch") or {}
    disp = li.get("display") or {}
    row = {"label": label, "arm": name, "round": r + 1, "display": f"{disp.get('pixels')}@{disp.get('hz')}", "ok": True,
           "launchWall": li.get("wallSeconds"), "lockWait": li.get("lockWaitSeconds"), "loadAvg1": li.get("loadAvg1"),
           "cds": st.get("cds"), "quickPlay": st.get("quickPlay"), "aotMapped": (li.get("aot") or {}).get("mapped"),
           "warm": json.dumps(li.get("shaderWarm")), "framebuffer": d.get("framebuffer"),
           "secondsToWorldReady": d.get("secondsToWorldReady"),
           "presentedFps": (d.get("present") or {}).get("presentedFps") if isinstance(d.get("present"), dict) else None}
    for k in FPS:
        row[k] = d.get(k)
    for k in PHASES:
        row["p." + k] = (st.get("phases") or {}).get(k)
    for k in STAMPS:
        row["t." + k] = (st.get("t") or {}).get(k)
    for i, rl in enumerate(st.get("reloads") or []):
        row[f"rl{i + 1}.callback"] = rl.get("callbackMs")
        row[f"rl{i + 1}.overlay"] = rl.get("overlayMs")
        row[f"rl{i + 1}.terrain"] = rl.get("terrainMs")
    return row


def main():
    argv = sys.argv[1:]
    common = []
    if "--" in argv:
        i = argv.index("--")
        argv, common = argv[:i], argv[i + 1:]
    full = "--full" in argv
    argv = [x for x in argv if x != "--full"]
    first_round = 1
    for x in list(argv):
        if x.startswith("--from-round="):
            first_round = int(x.split("=", 1)[1])
            argv.remove(x)
    prefix, rounds = argv[0], int(argv[1])
    arms = [(a.split("=", 1)[0], shlex.split(a.split("=", 1)[1])) for a in argv[2:]]
    order = []
    for r in range(rounds):
        seq = arms if r % 2 == 0 else list(reversed(arms))
        order += [(name, args, r) for name, args in seq if r + 1 >= first_round]
    static = []
    for line in open(os.path.join(ROOT, "tools", "static-options.txt")):
        if line.strip():
            static += ["--opt", line.strip()]
    tsv = os.path.join(ROOT, "results", "raw", f"{prefix}-startup.tsv")
    os.makedirs(os.path.dirname(tsv), exist_ok=True)
    for name, args, r in order:
        la = wait_quiet()
        label = f"{prefix}-{name}-{r + 1}"
        out = os.path.join(ROOT, "results", "raw", f"{label}.json")
        if os.path.exists(out):
            os.remove(out)
        gd = os.path.join(ROOT, "run", f"{prefix}-{name}")
        cmd = ["python3", os.path.join(ROOT, "tools", "launch.py"), "--game-dir", gd, "--opt", "fullscreen:true"] + static + [
            "--bench", out, "--label", label, "--world-snapshot", os.path.join(ROOT, "run", "pinned", "scenes")] + \
            (["--bench-prop", "warmup=15", "--bench-prop", "seconds=30"] if full else ["--bench-prop", "exitAfterReady=3"]) + \
            common + args
        p = subprocess.run(cmd, capture_output=True, text=True)
        row = row_of(label, name, r)
        if row is None:
            print(f"{label}: no result (exit {p.returncode}) {p.stderr[-400:]}", flush=True)
            continue
        print(f"{label:28s} load {la:4.2f} total {row['p.total']} ms  reload {row['p.reload']}  fade {row['p.fade']}  open "
              f"{row['p.open']}  levelLoad {row['p.levelLoad']}  jvm {row['p.jvm']}  preWindow {row['p.preWindow']}  "
              f"wall {row['launchWall']} s  aot {row['aotMapped']}  {row['display']}"
              + (f"  fps median {row['medianFps']} avg {row['avgFps']} 1% {row['p1LowFps']} 0.1% {row['p01LowFps']} "
                 f"max {row['maxFrameMs']} ms" if full else ""), flush=True)
    # Every round's result (also those of an interrupted earlier session: --from-round).
    rows = [x for x in (row_of(f"{prefix}-{name}-{r + 1}", name, r) for r in range(rounds) for name, _ in arms) if x]
    first_display = rows[0]["display"] if rows else None
    for row in rows:
        row["ok"] = row["display"] == first_display
    keys = list(dict.fromkeys(k for row in rows for k in row))
    with open(tsv, "w") as f:
        f.write("\t".join(keys) + "\n")
        for row in rows:
            f.write("\t".join(str(row.get(k)) for k in keys) + "\n")
    print(f"\nper-arm medians (ms; launchWall s), display {first_display}, excluded (other display mode): "
          f"{[r['label'] for r in rows if not r['ok']]}")
    cols = ["p." + k for k in PHASES] + ["launchWall"] + (FPS if full else [])
    print("arm".ljust(10) + "n".rjust(3) + "".join(c.replace("p.", "").rjust(11) for c in cols))
    for name, _ in arms:
        rs = [r for r in rows if r["arm"] == name and r["ok"]]
        line = name.ljust(10) + str(len(rs)).rjust(3)
        for c in cols:
            v = [r[c] for r in rs if r[c] is not None]
            line += (f"{statistics.median(v):11.1f}" if v else "          -")
        print(line)
        tot = [r["p.total"] for r in rs if r["p.total"] is not None]
        if len(tot) > 1:
            print(" " * 13 + f"total: mean {statistics.mean(tot):.0f} sd {statistics.stdev(tot):.0f} min {min(tot):.0f} max {max(tot):.0f}")
    extra = [c for c in ["t." + k for k in STAMPS] + [k for k in keys if k.startswith("rl")]
             if any(r.get(c) is not None for r in rows)]
    if extra:
        print("\nstamps (ms since process start) and reloads (ms from request), per-arm medians")
        for i in range(0, len(extra), 8):
            part = extra[i:i + 8]
            print("arm".ljust(10) + "".join(c.replace("t.", "").rjust(14) for c in part))
            for name, _ in arms:
                rs = [r for r in rows if r["arm"] == name and r["ok"]]
                line = name.ljust(10)
                for c in part:
                    v = [r[c] for r in rs if r.get(c) is not None]
                    line += (f"{statistics.median(v):14.1f}" if v else "             -")
                print(line)
    if len(arms) > 1:
        base = arms[0][0]
        print(f"\npaired by round, arm - {base} (ms; launchWall s): median diff / mean diff / rounds lower / t")
        for name, _ in arms[1:]:
            print(f"  {name} - {base}")
            for c in ["p." + k for k in PHASES] + extra + ["launchWall"] + (FPS if full else []):
                d = []
                for r in range(rounds):
                    a = [x for x in rows if x["arm"] == base and x["round"] == r + 1 and x["ok"]]
                    b = [x for x in rows if x["arm"] == name and x["round"] == r + 1 and x["ok"]]
                    if a and b and a[0].get(c) is not None and b[0].get(c) is not None:
                        d.append(b[0][c] - a[0][c])
                if not d:
                    continue
                t = (statistics.mean(d) / (statistics.stdev(d) / len(d) ** 0.5)
                     if len(d) > 1 and statistics.stdev(d) > 0 else float("nan"))
                print(f"    {c.replace('p.', '').replace('t.', 't:'):16s} {statistics.median(d):9.1f} {statistics.mean(d):9.1f}"
                      f"   {sum(1 for x in d if x < 0)}/{len(d)}   t {t:6.2f}")
    print(f"rows: {tsv}")


if __name__ == "__main__":
    main()
