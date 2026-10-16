#!/usr/bin/env python3
"""Parallel runner for tools/headless-suites.sh (the suite table lives there; this file only schedules).

Registry: one suite per line, tab-separated: name, kind, group, estimated seconds, zsh command (already shell-quoted).
  kind gpu   uses the GPU in a way another GPU suite could disturb: one at a time (--gpu-lanes, default 1), table order.
       cpu   no GPU work a concurrent suite could disturb: a pool of --cpu slots (default 3) next to the GPU chain.
       solo  has timing-sensitive checks or prints timing numbers (thread CPU time vs wall time, 100 us clock reads, a GPU
             timing probe): runs with nothing else of ours running, as in the old one-at-a-time script.
  group      suites of one group run one at a time in table order (a shared build/ directory, or build -> run).
  estimate   seconds, only used to pack the lock epochs below (a wrong value costs time, never a verdict).

Machine-wide lock (/tmp/mc-gpu.lock: tools/launch.py and the builds take it): no suite ever runs without it, so
a game run never overlaps a suite. Like tools/launch.py, nothing starts while the user's own Prism game is open: an epoch
starts no further suite once it appears, and the next epoch waits until it closes. It is held in epochs: suites start while they are expected to end
before the epoch deadline (max(--window, the expected end of the longest suite already running), default window 60 s), then
the started ones finish, the lock is released and taken again after --yield-s (1 s), so a queued game run or the user-play
guard gets in between epochs (the old script released it between suites; its longest hold was one 67 s suite).

Per suite: <out>/<name>.txt (its stdout + stderr only) and one progress line when it ends. At the end: <out>/summary.tsv
in table order (name, rc, the suite's own seconds, last non-empty output line: the old format, except that the seconds no
longer include lock waits), <out>/timeline.tsv (start / end / epoch per suite) and a wall-time line. Exit 1 if any failed.

--serial: the old behaviour (one suite at a time in table order, the lock taken per suite).
"""
import argparse, fcntl, os, signal, subprocess, sys, time

KINDS = ("gpu", "cpu", "solo")
USER_GAME_PATTERN = "[P]rismLauncher/instances/.*/bin/java "   # tools/launch.py's ("[P]": never matches a concurrent pgrep)


def user_game_running():
    return subprocess.run(["pgrep", "-f", USER_GAME_PATTERN], stdout=subprocess.DEVNULL).returncode == 0


def load(path):
    suites = []
    with open(path) as f:
        for line in f:
            line = line.rstrip("\n")
            if not line:
                continue
            name, kind, group, est, cmd = line.split("\t", 4)
            if kind not in KINDS:
                sys.exit(f"suite_sched: {name}: kind must be one of {KINDS}, not {kind!r}")
            suites.append({"name": name, "kind": kind, "group": group, "est": float(est), "cmd": cmd})
    names = [s["name"] for s in suites]
    if len(set(names)) != len(names):
        sys.exit("suite_sched: duplicate suite names")
    return suites


def last_line(path):
    try:
        with open(path, errors="replace") as f:
            lines = [l.rstrip("\n") for l in f if l.strip()]
    except OSError:
        return ""
    return lines[-1][:160] if lines else ""


class Runner:
    def __init__(self, a, suites):
        self.a, self.suites = a, suites
        self.t0 = time.time()
        self.running = {}          # pid -> (suite, Popen, log file)
        self.lock_fd = None
        self.lock_waited = self.lock_held = 0.0
        self.epochs = 0
        self.failed = False

    # --- machine-wide lock -------------------------------------------------------------------------------------------
    def acquire(self):
        self.lock_fd = open(self.a.lock, "w")
        w0 = time.time()
        # The user's own game has priority (as in tools/launch.py): never start while it is open, and re-check after taking
        # the lock (it may have been launched while we waited in the queue).
        while True:
            if user_game_running():
                print("[suites] the user's game is open: waiting until it closes", flush=True)
                while user_game_running():
                    time.sleep(10)
            fcntl.flock(self.lock_fd, fcntl.LOCK_EX)
            if not user_game_running():
                break
            fcntl.flock(self.lock_fd, fcntl.LOCK_UN)
        waited = time.time() - w0
        self.lock_waited += waited
        if waited > 1:
            print(f"[suites] waited {waited:.0f}s for the game lock", flush=True)
        self.epochs += 1
        self.held_since = time.time()

    def release(self):
        if self.lock_fd is not None:
            self.lock_held += time.time() - self.held_since
            fcntl.flock(self.lock_fd, fcntl.LOCK_UN)
            self.lock_fd.close()
            self.lock_fd = None

    # --- suites ------------------------------------------------------------------------------------------------------
    def start(self, s):
        log = open(os.path.join(self.a.out, s["name"] + ".txt"), "w")
        s["start"] = time.time()
        s["epoch"] = self.epochs
        # Own process group, so an interrupted run kills the whole suite (zsh and its java / clang children). The lock fd is
        # not inherited (Python opens files close-on-exec), so it is released when this runner releases it.
        p = subprocess.Popen(["zsh", "-c", s["cmd"]], cwd=self.a.root, stdout=log, stderr=subprocess.STDOUT,
                             stdin=subprocess.DEVNULL, start_new_session=True)
        self.running[p.pid] = (s, p, log)

    def reap(self):
        """Block until at least one running suite ends; collect every suite that has ended."""
        block = True
        while self.running:
            pid, status = os.waitpid(-1, 0 if block else os.WNOHANG)
            if pid == 0:
                return
            if pid not in self.running:
                continue
            s, p, log = self.running.pop(pid)
            p.returncode = os.waitstatus_to_exitcode(status)
            log.close()
            self.finish(s, p.returncode)
            block = False

    def finish(self, s, rc):
        s["end"] = time.time()
        s["rc"] = rc if rc >= 0 else 128 - rc
        s["last"] = last_line(os.path.join(self.a.out, s["name"] + ".txt"))
        self.failed |= s["rc"] != 0
        print(f"{s['name']}\t{s['rc']}\t{s['end'] - s['start']:.0f}s\t{s['last']}", flush=True)

    def kill_all(self, *_):
        for s, p, log in list(self.running.values()):
            try:
                os.killpg(p.pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
        self.release()
        sys.exit(130)

    def ready(self, s):
        """Every earlier suite of its group has ended and a lane of its kind is free."""
        for o in self.suites:
            if o is s:
                break
            if o["group"] == s["group"] and "end" not in o:
                return False
        kinds = [r[0]["kind"] for r in self.running.values()]
        if "solo" in kinds:
            return False
        if s["kind"] == "solo":
            return not kinds
        if s["kind"] == "gpu":
            return kinds.count("gpu") < self.a.gpu_lanes
        return kinds.count("cpu") < self.a.cpu

    def run_parallel(self):
        pending = list(self.suites)
        while pending:
            self.acquire()
            e0 = time.time()
            while True:
                now = time.time()
                deadline = max([e0 + self.a.window] + [r[0]["start"] + r[0]["est"] for r in self.running.values()])
                if user_game_running():
                    deadline = now   # the user started playing: start nothing more, end the epoch (acquire waits)
                for s in list(pending):
                    # Solo suites only once nothing else is left to start beside them (they wait for an idle machine anyway).
                    if s["kind"] == "solo" and any(o["kind"] != "solo" for o in pending):
                        continue
                    fits = now + s["est"] <= deadline or not self.running   # (an idle epoch always starts something)
                    if fits and now < deadline and self.ready(s):
                        pending.remove(s)
                        self.start(s)
                        deadline = max(deadline, s["start"] + s["est"])
                if not self.running:
                    break
                self.reap()
            self.release()
            if pending:
                time.sleep(self.a.yield_s)

    def run_serial(self):
        for s in self.suites:
            self.acquire()
            self.start(s)
            self.reap()
            self.release()

    def report(self):
        wall = time.time() - self.t0
        with open(os.path.join(self.a.out, "summary.tsv"), "w") as f:
            for s in self.suites:
                f.write(f"{s['name']}\t{s['rc']}\t{s['end'] - s['start']:.0f}s\t{s['last']}\n")
        busy = sum(s["end"] - s["start"] for s in self.suites)
        mode = "serial" if self.a.serial else f"parallel (cpu {self.a.cpu}, gpu lanes {self.a.gpu_lanes}, window {self.a.window:.0f}s)"
        line = (f"{mode}: wall {wall:.0f}s, suite time {busy:.0f}s, lock held {self.lock_held:.0f}s in {self.epochs} "
                f"epoch(s), waited {self.lock_waited:.0f}s for it")
        with open(os.path.join(self.a.out, "timeline.tsv"), "w") as f:
            f.write("name\tkind\tgroup\tepoch\tstart_s\tend_s\tseconds\test_s\trc\n")
            for s in sorted(self.suites, key=lambda s: s["start"]):
                f.write(f"{s['name']}\t{s['kind']}\t{s['group']}\t{s['epoch']}\t{s['start'] - self.t0:.1f}\t{s['end'] - self.t0:.1f}"
                        f"\t{s['end'] - s['start']:.1f}\t{s['est']:.0f}\t{s['rc']}\n")
            f.write("# " + line + "\n")
        print("[suites] " + line, flush=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("registry")
    ap.add_argument("--out", required=True)
    ap.add_argument("--root", required=True)
    ap.add_argument("--lock", default="/tmp/mc-gpu.lock")
    ap.add_argument("--cpu", type=int, default=int(os.environ.get("SUITES_CPU", "3")), help="CPU-pool slots (env SUITES_CPU)")
    ap.add_argument("--gpu-lanes", type=int, default=int(os.environ.get("SUITES_GPU_LANES", "1")),
                    help="GPU suites at once (env SUITES_GPU_LANES; 1 = serialized)")
    ap.add_argument("--window", type=float, default=float(os.environ.get("SUITES_WINDOW", "60")),
                    help="minimum seconds per lock epoch (env SUITES_WINDOW)")
    ap.add_argument("--yield-s", type=float, default=1.0)
    ap.add_argument("--serial", action="store_true", default=os.environ.get("SUITES_SERIAL") == "1")
    ap.add_argument("--only", help="comma-separated suite names to run (debugging)")
    a = ap.parse_args()
    suites = load(a.registry)
    if a.only:
        keep = set(a.only.split(","))
        suites = [s for s in suites if s["name"] in keep]
    os.makedirs(a.out, exist_ok=True)
    r = Runner(a, suites)
    for sig in (signal.SIGINT, signal.SIGTERM, signal.SIGHUP):
        signal.signal(sig, r.kill_all)
    if a.serial:
        r.run_serial()
    else:
        r.run_parallel()
    r.report()
    sys.exit(1 if r.failed else 0)


if __name__ == "__main__":
    main()
