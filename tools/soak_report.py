#!/usr/bin/env python3
"""Report and final verdict of a soak run (bench/src/bench/Soak.java; tools/soak.sh, tools/soak-lan.sh).

The game computes the memory verdict itself ("verdict" in the result JSON). This adds what the game cannot see: its own
exit code, crash reports / JVM fatal error logs written during the run, and for a LAN soak the host's result.

  python3 tools/soak_report.py results/raw/LABEL.json [--exit-code N] [--game-dir DIR --since EPOCH]
                               [--host host.json --host-exit-code N --host-game-dir DIR --host-since EPOCH]

Exit code: 0 = PASS; 1 = FAIL (crash, error, exit code, host, or memory growth in a conclusive run); 2 = INCONCLUSIVE: the
run itself was clean but its trend span is under 15 min (e.g. the 3-minute smoke), so the memory verdict, pass or fail,
says nothing yet (it is printed as information).
"""
import argparse, glob, json, os, statistics, sys


def crashes(game_dir, since):
    if not game_dir:
        return []
    found = glob.glob(os.path.join(game_dir, "crash-reports", "*.txt")) + glob.glob(os.path.join(game_dir, "hs_err_pid*.log"))
    return sorted(f for f in found if since is None or os.path.getmtime(f) >= since)


def table(d):
    s = d.get("series") or {}
    t = s.get("t") or []
    if not t:
        print("  (no samples)")
        return
    live = d.get("liveHeap") or {}
    lt, lm = live.get("t") or [], live.get("mb") or []
    step = 300 if t[-1] > 900 else 60
    print(f"  {'t(s)':>6} {'seg':6} {'fs':>2} {'fb':>9} {'fps':>6} {'maxMs':>7} {'heapAfterGc':>11} {'liveOld':>8} "
          f"{'metal':>7} {'natFoot':>7} {'footpr':>7} {'rss':>7} {'chunks':>6} {'ent':>5}")
    nxt = 0.0
    for i, ti in enumerate(t):
        if ti + 1e-9 < nxt and i != len(t) - 1:
            continue
        nxt = ti + step
        lh = [m for (tt, m) in zip(lt, lm) if tt <= ti + 1e-9]
        g = lambda k: (s.get(k) or [None] * len(t))[i]
        print(f"  {ti:6.0f} {g('seg'):6} {g('fs'):>2} {str(g('fbW')) + 'x' + str(g('fbH')):>9} {g('fps'):6.0f} {g('maxMs'):7.1f} "
              f"{g('heapAfterGcMB'):11.0f} {(lh[-1] if lh else float('nan')):8.0f} {g('metalMB'):7.0f} {g('nativeFootprintMB'):7.0f} "
              f"{g('footprintMB'):7.0f} {g('rssMB'):7.0f} {g('chunks'):6} {g('entities'):5}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("result")
    ap.add_argument("--exit-code", type=int)
    ap.add_argument("--game-dir")
    ap.add_argument("--since", type=float)
    ap.add_argument("--host")
    ap.add_argument("--host-exit-code", type=int)
    ap.add_argument("--host-game-dir")
    ap.add_argument("--host-since", type=float)
    a = ap.parse_args()

    fails, notes, memory = [], [], []
    try:
        d = json.load(open(a.result))
    except Exception as e:
        d = {}
        fails.append(f"no result JSON ({e})")
    if d and d.get("mode") != "soak":
        fails.append(f"not a soak result: {d.get('error', d)}")
    v = d.get("verdict") or {}
    if d.get("mode") == "soak":
        print(f"{d.get('label')}: {d.get('backend', '?')} {d.get('device', '')}, server {d.get('server')}, "
              f"render distance {d.get('renderDistance')}, status {d.get('status')}, "
              f"{d.get('elapsedSeconds', 0):.0f}/{d.get('plannedSeconds', 0):.0f} s, {d.get('frames', 0):.0f} frames, "
              f"{d.get('fullscreenToggles', 0):.0f} fullscreen toggles, {d.get('resizeBursts', 0):.0f} resize bursts")
        print(f"  native memory readers: {d.get('nativeMem')}")
        fps = [x for x in (d.get("series") or {}).get("fps", []) if x > 0]
        if fps:
            print(f"  fps per sample: median {statistics.median(fps):.0f}, min {min(fps):.0f} (informational, not a benchmark)")
        print(f"  verdict window: {v.get('trendFromSeconds')}..{v.get('toSeconds')} s, reference framebuffer {v.get('referenceFramebuffer')}")
        for k, m in (v.get("metrics") or {}).items():
            print(f"  {k:18} {'ok  ' if m['pass'] else 'FAIL'} slope {m['slopeMBperMin']:7.2f} MB/min (max {m['maxSlope']}), "
                  f"delta {m['deltaMB']:7.1f} MB (max {m['maxDelta']}), noise {m['noiseMB']:.1f} MB, {m['points']} points, "
                  f"{m['first']:.0f} -> {m['last']:.0f} MB")
        for k, m in (v.get("info") or {}).items():
            print(f"  {k:18} info slope {m['slopeMBperMin']:7.2f} MB/min, delta {m['deltaMB']:7.1f} MB, noise {m['noiseMB']:.1f} MB")
        table(d)
        metric_names = tuple((v.get("metrics") or {}).keys())
        for r in v.get("reasons", []):
            if r.startswith("inconclusive"):
                notes.append(r)
            elif r.startswith(metric_names):
                memory.append(r)
            else:
                fails.append(r)
        if not v.get("pass") and not fails and not memory:
            fails.append("verdict false")
    if a.exit_code not in (None, 0):
        fails.append(f"game exit code {a.exit_code}")
    for f in crashes(a.game_dir, a.since):
        fails.append(f"crash report {f}")
    if a.host:
        try:
            h = json.load(open(a.host))
            print(f"  host: maxPlayers {h.get('maxPlayers')}, published {h.get('published')}, log {h.get('log', [])[-2:]}")
            if h.get("maxPlayers") != 2 or h.get("error"):
                fails.append(f"host saw maxPlayers {h.get('maxPlayers')} {h.get('error', '')}".strip())
        except Exception as e:
            fails.append(f"no host result ({e})")
        if a.host_exit_code not in (None, 0):
            fails.append(f"host exit code {a.host_exit_code}")
        for f in crashes(a.host_game_dir, a.host_since):
            fails.append(f"host crash report {f}")
    conclusive = bool(v.get("conclusive"))
    if fails or (memory and conclusive):
        print("SOAK FAILED: " + "; ".join(fails + memory))
        sys.exit(1)
    if not conclusive:
        print("SOAK INCONCLUSIVE (clean run, no crash; " + "; ".join(notes) + "; memory verdict not meaningful yet"
              + (": would fail " + "; ".join(memory) if memory else ": would pass") + ")")
        sys.exit(2)
    print("SOAK PASSED")
    sys.exit(0)


if __name__ == "__main__":
    main()
