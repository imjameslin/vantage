#!/usr/bin/env python3
"""Machine-wide GPU lock with two lanes (reader/writer), backward compatible with `lockf -k /tmp/mc-gpu.lock`.

Lanes (what to use when):
  timing      EXCLUSIVE (flock LOCK_EX): nothing else that takes the lock runs, so the run has the GPU and CPU to itself.
              Every FPS / frame-time / startup / memory-verdict measurement: bench-one, bench-travel, bench-matrix,
              mst-run, A/B (abab) runs, soak and soak-lan, wall-clock-sensitive headless suites. launch.py's default.
  functional  SHARED (LOCK_SH): any number of functional runs overlap with each other, never with a timing run or a
              legacy holder. Runs whose verdict does not depend on speed: scenes / zoomseq parity, lan-smoke,
              deterministic headless suites. Also shares this checkout's build outputs (key root:<checkout>, shared).
  build       SHARED on the GPU plus EXCLUSIVE on this checkout's build outputs (key root:<checkout>): a build never
              overlaps a timing run, nor a functional run or another build of the same checkout (they read build/).
  none        no GPU lock; only the --key locks.

Writer preference (no starvation): every acquisition first takes the gate <lock>.gate exclusively, then the lock in its
mode, then drops the gate. A waiting timing run holds the gate while it waits, so new shared holders queue behind it
and it starts as soon as the current shared holders finish. Legacy writers (`lockf -k <lock> cmd`, the user-play guard,
the old launch.py, the bench/README.md python one-liner) do not know the gate; a shared requester therefore also yields
while such a legacy writer is waiting (detected from the process table: a lockf / python flock holder of <lock> with no
child yet, or an old launch.py that is not registered here), so `lockf -k` keeps its "excludes everything" meaning and is
not starved by overlapping functional runs.

Nesting: a command that already runs inside a hold (an ancestor process is a registered holder, or a legacy `lockf -k
<lock>` / python flock holder) is covered by it and takes nothing again: `lockf -k <lock> tools/bench-one.sh ...` and a
functional script launching two games work (both deadlocked or needed a second lock file before). Asking for more than
the ancestor holds (timing inside functional, a key exclusively inside a shared hold of it) is an error, not a deadlock.

Keys are named reader/writer locks (same gate scheme), acquired in sorted order before the GPU lock, e.g.
gamedir:<abs path> (launch.py: one game per game dir at a time). Lock fds are passed to the command (pass_fds), so the
locks stay held until the command's own process exits even if this wrapper is killed.

CLI (usable from zsh):
  python3 tools/gpulock.py timing     [--key NAME[:ex|:sh]]... [--quiet LIMIT[:TIMEOUT]] [--label TEXT] -- cmd args...
  python3 tools/gpulock.py functional [--key ...] [--label TEXT] -- cmd args...
  python3 tools/gpulock.py build      [--key ...] [--label TEXT] -- cmd args...
  python3 tools/gpulock.py none --key NAME[:ex|:sh] -- cmd args...
  python3 tools/gpulock.py status [--json]
Exit code: the command's (128+N if killed by signal N); 75 lock error (e.g. timing requested inside a functional hold).
Environment: MC_GPU_LOCK (lock path, default /tmp/mc-gpu.lock); the command gets MC_GPU_LOCK_HELD=<lane>
(informational; nesting is detected from the process tree, not from it).
"""
import errno, fcntl, hashlib, json, os, re, signal, subprocess, sys, time

DEFAULT_LOCK = "/tmp/mc-gpu.lock"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LANES = {"timing": "ex", "functional": "sh", "build": "sh", "none": None}
EX, SH = "ex", "sh"
LOCK_ERROR = 75


class LockError(Exception):
    pass


def lock_path():
    return os.path.realpath(os.environ.get("MC_GPU_LOCK", DEFAULT_LOCK))


def _log(msg):
    print(f"[gpulock] {msg}", file=sys.stderr, flush=True)


def _alive(pid):
    try:
        os.kill(pid, 0)
        return True
    except ProcessLookupError:
        return False
    except PermissionError:
        return True


def _open(path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    return os.open(path, os.O_RDWR | os.O_CREAT, 0o666)


def _flock(fd, mode, block=True):
    """True if taken; mode EX/SH. Non-blocking: False when busy."""
    op = fcntl.LOCK_EX if mode == EX else fcntl.LOCK_SH
    try:
        fcntl.flock(fd, op if block else op | fcntl.LOCK_NB)
        return True
    except OSError as e:
        if e.errno in (errno.EWOULDBLOCK, errno.EAGAIN):
            return False
        raise


def covers(held, wanted):
    return held == EX or (held == SH and wanted == SH)


# ---------------------------------------------------------------- process table (legacy holders / waiters, ancestry)

def ps():
    """{pid: (ppid, args)} for every process."""
    out = subprocess.run(["ps", "-axww", "-o", "pid=,ppid=,args="], capture_output=True, text=True).stdout
    procs = {}
    for line in out.splitlines():
        parts = line.strip().split(None, 2)
        if len(parts) >= 2 and parts[0].isdigit() and parts[1].isdigit():
            procs[int(parts[0])] = (int(parts[1]), parts[2] if len(parts) > 2 else "")
    return procs


def _names(lock):
    """Spellings of the lock path that can appear on a command line (/tmp vs /private/tmp)."""
    names = {lock}
    if lock.startswith("/private/tmp/"):
        names.add(lock[len("/private"):])
    return names


def legacy_kind(args, lock):
    """'lockf' / 'python-flock' / 'launch.py' when this command line is a legacy (gate-unaware, exclusive) user of lock."""
    toks = args.split()
    if not toks:
        return None
    exe = os.path.basename(toks[0])
    names = _names(lock)
    if exe == "lockf":
        i = 1
        while i < len(toks) and toks[i].startswith("-"):
            i += 2 if toks[i] == "-t" else 1
        if i < len(toks):
            f = toks[i]
            if (os.path.isabs(f) and os.path.realpath(f) == lock) or (not os.path.isabs(f) and os.path.basename(f) == os.path.basename(lock)):
                return "lockf"
        return None
    if exe.lower().startswith("python"):
        if "flock" in args and any(n in args for n in names) and " -c " in f" {args} ":
            return "python-flock"
        if re.search(r"(^|[/\s])launch\.py(\s|$)", args):
            return "launch.py"
    return None


def ancestors(procs, pid=None):
    """Parent chain of pid (default: this process), nearest first, without pid itself and without launchd."""
    chain, seen = [], set()
    if pid is None:
        p = procs.get(os.getpid(), (os.getppid(), ""))[0]
    elif pid in procs:
        p = procs[pid][0]
    else:
        return chain
    while p > 1 and p not in seen and p in procs:
        seen.add(p)
        chain.append(p)
        p = procs[p][0]
    return chain


# ---------------------------------------------------------------- registry of gate-aware clients

class Registry:
    def __init__(self, lock):
        self.dir = lock + ".d/clients"

    def entries(self):
        out = {}
        try:
            names = os.listdir(self.dir)
        except FileNotFoundError:
            return out
        for n in names:
            if not n.endswith(".json"):
                continue
            path = os.path.join(self.dir, n)
            try:
                with open(path) as f:
                    e = json.load(f)
            except (OSError, ValueError):
                continue
            if any(_alive(p) for p in [e.get("pid", 0)] + e.get("children", [])):
                out[e["pid"]] = e
            else:
                try:
                    os.unlink(path)
                except OSError:
                    pass
        return out

    def write(self, entry):
        os.makedirs(self.dir, exist_ok=True)
        tmp = os.path.join(self.dir, f".{entry['pid']}.tmp")
        with open(tmp, "w") as f:
            json.dump(entry, f)
        os.replace(tmp, os.path.join(self.dir, f"{entry['pid']}.json"))

    def remove(self, pid):
        try:
            os.unlink(os.path.join(self.dir, f"{pid}.json"))
        except OSError:
            pass


def registered_pids(entries):
    pids = set()
    for e in entries.values():
        pids.add(e["pid"])
        pids.update(e.get("children", []))
    return pids


def _env_lock(pid):
    """The lock an old launch.py process uses: its MC_GPU_LOCK environment variable or the default (ps -E shows the
    environment of our own user's processes)."""
    out = subprocess.run(["ps", "-E", "-ww", "-o", "command=", "-p", str(pid)], capture_output=True, text=True).stdout
    m = re.search(r"(?:^|\s)MC_GPU_LOCK=(\S+)", out)
    return os.path.realpath(m.group(1) if m else DEFAULT_LOCK)


def lsof_pids(path):
    out = subprocess.run(["lsof", "-t", path], capture_output=True, text=True).stdout
    return {int(x) for x in out.split() if x.isdigit()}


def legacy_procs(procs, lock, entries):
    """[(pid, kind, holding, args)] for gate-unaware users of lock.
    Known kinds (lockf, python flock one-liner, old launch.py) spawn their command only after taking the lock: holding =
    it has a child (a waiting lockf does not even have the file open yet). Any other process that has the lock file open
    (lsof) and is neither registered nor a descendant of a registered client (e.g. another tool's own flock scheduler)
    is kind "open": while registered shared holders hold the lock it cannot hold it exclusively, so it is waiting; a game
    process (inherited fd of a killed launcher) is a holder."""
    parents = {pp for pp, _ in procs.values()}
    tree = registered_pids(entries) | {os.getpid()}
    out, seen = [], set()
    for pid, (_, args) in procs.items():
        if pid in tree:
            continue
        kind = legacy_kind(args, lock)
        if kind == "launch.py" and _env_lock(pid) != lock:
            continue
        if kind:
            out.append((pid, kind, pid in parents, args))
            seen.add(pid)
    shared_held = any(e.get("state") == "holding" and e.get("gpu") == SH for e in entries.values())
    for pid in lsof_pids(lock) - seen - tree:
        if any(a in tree for a in ancestors(procs, pid)):
            continue
        args = procs.get(pid, (0, ""))[1]
        game = "net.minecraft.client.main.Main" in args
        out.append((pid, "open", game or not shared_held, args))
    return out


def coverage(procs, lock, entries):
    """What our ancestors already hold: (gpu mode or None, {key: mode}, description)."""
    gpu, keys, who = None, {}, []
    for a in ancestors(procs):
        e = entries.get(a)
        if e and e.get("state") == "holding" and e.get("lock") == lock:
            if e.get("gpu") and (gpu is None or e["gpu"] == EX):
                gpu = e["gpu"]
            for k, m in e.get("keys", {}).items():
                if k not in keys or m == EX:
                    keys[k] = m
            who.append(f"pid {a} ({e.get('lane')} {e.get('label', '')})")
        elif legacy_kind(procs[a][1], lock) in ("lockf", "python-flock"):
            gpu = EX
            who.append(f"pid {a} ({procs[a][1][:80]})")
    return gpu, keys, ", ".join(who)


# ---------------------------------------------------------------- reader/writer lock with a gate

class RWLock:
    """flock(2) reader/writer lock on path with writer preference through path.gate."""

    def __init__(self, path):
        self.path = path
        self.gate = path + ".gate"

    def acquire(self, mode, before_main=None):
        """Blocking. before_main() runs while the gate is held (shared requesters yield to legacy writers there).
        Returns the fd holding the lock."""
        gfd, mfd = _open(self.gate), _open(self.path)
        try:
            _flock(gfd, EX)
            if before_main:
                before_main()
            _flock(mfd, mode)
        except BaseException:
            os.close(mfd)
            raise
        finally:
            os.close(gfd)   # closing drops the gate
        return mfd

    def try_acquire(self, mode):
        gfd, mfd = _open(self.gate), _open(self.path)
        try:
            if _flock(gfd, EX, block=False) and _flock(mfd, mode, block=False):
                return mfd
        finally:
            os.close(gfd)
        os.close(mfd)
        return None



def key_path(lock, name):
    return os.path.join(lock + ".d", "keys", hashlib.sha1(name.encode()).hexdigest()[:20] + ".lock")


# ---------------------------------------------------------------- acquisition

class Hold:
    def __init__(self, lock, lane, label, registry):
        self.lock, self.lane, self.label, self.registry = lock, lane, label, registry
        self.fds, self.gpu, self.keys, self.children = [], None, {}, []
        self.nested, self.covered_by, self.waited = False, "", 0.0
        self.entry = {"pid": os.getpid(), "lane": lane, "label": label or "", "lock": lock, "cwd": os.getcwd(),
                      "state": "waiting", "since": time.time(), "gpu": None, "keys": {}, "children": []}

    def _publish(self, **kw):
        self.entry.update(kw)
        self.registry.write(self.entry)

    def add_child(self, pid):
        self.children.append(pid)
        self._publish(children=self.children)

    def child_env(self, env=None):
        env = dict(os.environ if env is None else env)
        env["MC_GPU_LOCK_HELD"] = self.lane
        return env

    def release(self):
        for fd in self.fds:
            try:
                os.close(fd)
            except OSError:
                pass
        self.fds = []
        self.registry.remove(os.getpid())


def describe_holders(lock, procs=None, entries=None, exclude=()):
    procs = procs if procs is not None else ps()
    entries = entries if entries is not None else Registry(lock).entries()
    held = [f"pid {e['pid']} {e['lane']}{' (nested)' if e.get('nested') else ''} '{e.get('label', '')}'"
            for e in entries.values() if e.get("state") == "holding" and e["pid"] not in exclude]
    queued = [f"pid {e['pid']} {e['lane']} '{e.get('label', '')}'" for e in entries.values()
              if e.get("state") == "waiting" and e["pid"] not in exclude]
    legacy = [f"pid {p} {k}{' holding' if h else ' waiting'}: {a[:110]}" for p, k, h, a in legacy_procs(procs, lock, entries)]
    return held, queued, legacy


def _yield_to_legacy(lock, registry):
    """Shared requester, gate held: wait while a gate-unaware exclusive user of the lock that was already queued when we
    got here is still waiting for it (first come, first served: legacy writers that arrive later queue behind us, so a
    stream of them cannot starve a functional run either). A candidate that stays childless while the lock is free (3
    probes in a row) is not waiting for it (it would have taken it) and is ignored."""
    free_streak, announced, first = {}, set(), None
    while True:
        procs = ps()
        waiters = [(p, k, a) for p, k, h, a in legacy_procs(procs, lock, registry.entries()) if not h]
        if first is None:
            first = {p for p, _, _ in waiters}
        waiters = [w for w in waiters if w[0] in first]
        if not waiters:
            return
        if _probe_free(lock):
            for p, _, _ in waiters:
                free_streak[p] = free_streak.get(p, 0) + 1
        else:
            free_streak = {}
        waiters = [w for w in waiters if free_streak.get(w[0], 0) < 3]
        if not waiters:
            return
        for p, k, a in waiters:
            if p not in announced:
                announced.add(p)
                _log(f"yielding to a waiting exclusive {k} user (pid {p}): {a[:120]}")
        time.sleep(1.0)


def _probe_free(lock):
    """True if nobody holds the lock (probe without the gate: we already hold it)."""
    fd = _open(lock)
    try:
        if _flock(fd, EX, block=False):
            return True
        return False
    finally:
        os.close(fd)


def acquire(lane, keys=(), label=None, lock=None, nested_timeout=120.0, log=_log):
    """Take the lane's locks (keys in sorted order, then the GPU lock); returns a Hold. Raises LockError."""
    if lane not in LANES:
        raise LockError(f"unknown lane {lane!r} (timing, functional, build, none)")
    lock = os.path.realpath(lock or lock_path())
    registry = Registry(lock)
    want = {}
    for k in keys:
        name, mode = (k if isinstance(k, tuple) else (k, EX))
        if mode not in (EX, SH):
            raise LockError(f"key {name}: mode must be ex or sh")
        if name not in want or mode == EX:
            want[name] = mode
    root_key = "root:" + ROOT
    if lane == "build":
        want[root_key] = EX
    elif lane == "functional" and want.get(root_key) != EX:
        want[root_key] = SH
    want_gpu = LANES[lane]
    hold = Hold(lock, lane, label, registry)
    t0 = time.time()

    procs = ps()
    entries = registry.entries()
    held_gpu, held_keys, who = coverage(procs, lock, entries)
    if held_gpu == EX:
        hold.nested, hold.covered_by = True, who
        hold._publish(state="holding", nested=True, coveredBy=who, gpu=None, keys={})
        return hold
    if held_gpu == SH and want_gpu == EX:
        raise LockError(f"the timing lane (exclusive) was requested inside a shared hold by {who}: it would wait for itself")
    for name, mode in want.items():
        if held_keys.get(name) == SH and mode == EX:
            raise LockError(f"key {name} wanted exclusive inside a shared hold of it by {who}")
    need_keys = sorted((n, m) for n, m in want.items() if not covers(held_keys.get(n), m))
    need_gpu = want_gpu if held_gpu is None else None
    nested = held_gpu is not None
    hold.nested, hold.covered_by = nested or bool(held_keys), who
    hold._publish(nested=hold.nested, coveredBy=who)

    try:
        for name, mode in need_keys:
            rw = RWLock(key_path(lock, name))
            fd = rw.try_acquire(mode)
            if fd is None:
                if nested:
                    # Inside a GPU hold: a blocking wait could wait on a holder that queues behind our own ancestor.
                    log(f"key {name} busy; polling for at most {nested_timeout:.0f} s (nested in {who})")
                    deadline = time.time() + nested_timeout
                    while fd is None and time.time() < deadline:
                        time.sleep(1.0)
                        fd = rw.try_acquire(mode)
                    if fd is None:
                        raise LockError(f"key {name} still busy after {nested_timeout:.0f} s (nested in {who})")
                else:
                    log(f"waiting for key {name} ({'exclusive' if mode == EX else 'shared'})")
                    fd = rw.acquire(mode)
            hold.fds.append(fd)
            hold.keys[name] = mode
        if need_gpu:
            gpu = RWLock(lock)
            fd = gpu.try_acquire(need_gpu)
            if fd is not None and need_gpu == SH:
                # Fast path taken without yielding: re-check legacy waiters (cheap) and back off if one is queued.
                if any(not h for _, _, h, _ in legacy_procs(ps(), lock, registry.entries())):
                    os.close(fd)
                    fd = None
            if fd is None:
                held, queued, legacy = describe_holders(lock, exclude={os.getpid()})
                log(f"{lane}: waiting for the GPU lock {lock} ({'exclusive' if need_gpu == EX else 'shared'}); "
                    f"holders: {held + [l for l in legacy if 'holding' in l] or ['?']}"
                    + (f"; queued: {queued + [l for l in legacy if 'waiting' in l]}" if queued or any('waiting' in l for l in legacy) else ""))
                fd = gpu.acquire(need_gpu, before_main=(lambda: _yield_to_legacy(lock, registry)) if need_gpu == SH else None)
            hold.fds.append(fd)
            hold.gpu = need_gpu
    except BaseException:
        hold.release()
        raise
    hold.waited = time.time() - t0
    hold._publish(state="holding", gpu=hold.gpu, keys=hold.keys, acquiredAt=time.time(), waited=round(hold.waited, 1))
    return hold


def quiet_gate(limit, timeout, log=_log):
    """Wait (holding the lock) until the 1-minute load average is below limit; returns (load, waited s, ok)."""
    t0 = time.time()
    while True:
        load = os.getloadavg()[0]
        if load < limit:
            return load, time.time() - t0, True
        if time.time() - t0 >= timeout:
            log(f"load still {load:.2f} after {timeout:.0f} s; results may be noisy")
            return load, time.time() - t0, False
        time.sleep(5.0)


# ---------------------------------------------------------------- CLI

def run_command(hold, cmd, env=None):
    """Run cmd with the hold's lock fds inherited; forward SIGTERM/SIGHUP/SIGINT; return a shell-style exit code."""
    proc = subprocess.Popen(cmd, env=hold.child_env(env), pass_fds=tuple(hold.fds))
    hold.add_child(proc.pid)

    def forward(sig, _frame):
        try:
            proc.send_signal(sig)
        except ProcessLookupError:
            pass
    for sig in (signal.SIGTERM, signal.SIGHUP, signal.SIGINT):
        signal.signal(sig, forward)
    rc = proc.wait()
    return 128 - rc if rc < 0 else rc


def status(as_json=False):
    lock = lock_path()
    procs = ps()
    entries = Registry(lock).entries()
    legacy = legacy_procs(procs, lock, entries)
    if as_json:
        print(json.dumps({"lock": lock, "clients": list(entries.values()),
                          "legacy": [{"pid": p, "kind": k, "holding": h, "args": a} for p, k, h, a in legacy]}, indent=2))
        return 0
    print(f"GPU lock {lock}")
    if not entries and not legacy:
        print("  free (no registered client, no lockf / old launch.py user)")
    for e in sorted(entries.values(), key=lambda e: e.get("since", 0)):
        age = time.time() - e.get("acquiredAt", e.get("since", time.time()))
        keys = ", ".join(f"{k}={m}" for k, m in e.get("keys", {}).items())
        print(f"  pid {e['pid']:>6} {e['lane']:<10} {e['state']:<8} {age:6.0f}s  {e.get('label', '')}"
              + (f"  [nested in {e.get('coveredBy')}]" if e.get("nested") else "") + (f"  keys: {keys}" if keys else ""))
    for p, k, h, a in legacy:
        print(f"  pid {p:>6} {'legacy ' + k:<10} {'holding' if h else 'waiting':<8}         {a[:120]}")
    return 0


def main(argv):
    if len(argv) < 2 or argv[1] in ("-h", "--help"):
        print(__doc__)
        return 0
    if argv[1] == "status":
        return status("--json" in argv[2:])
    lane = argv[1]
    if lane not in LANES:
        print(f"gpulock: unknown lane {lane!r}; use timing, functional, build, none or status", file=sys.stderr)
        return 2
    keys, quiet, label, i = [], None, None, 2
    while i < len(argv):
        a = argv[i]
        if a == "--":
            i += 1
            break
        if a == "--key" and i + 1 < len(argv):
            name, _, mode = argv[i + 1].rpartition(":") if argv[i + 1].endswith((":ex", ":sh")) else (argv[i + 1], "", EX)
            keys.append((name, mode or EX))
            i += 2
        elif a == "--quiet" and i + 1 < len(argv):
            lim, _, to = argv[i + 1].partition(":")
            quiet = (float(lim), float(to or 900))
            i += 2
        elif a == "--label" and i + 1 < len(argv):
            label = argv[i + 1]
            i += 2
        elif a.startswith("-"):
            print(f"gpulock: unknown option {a}", file=sys.stderr)
            return 2
        else:
            break
    cmd = argv[i:]
    if not cmd:
        print("gpulock: no command", file=sys.stderr)
        return 2
    if lane == "none" and not keys:
        print("gpulock: lane none needs --key", file=sys.stderr)
        return 2
    try:
        hold = acquire(lane, keys=keys, label=label or " ".join(os.path.basename(c) if j == 0 else c for j, c in enumerate(cmd[:3])))
    except LockError as e:
        _log(f"error: {e}")
        return LOCK_ERROR
    if hold.waited > 1:
        _log(f"{lane}: waited {hold.waited:.0f}s")
    if quiet and hold.gpu == EX:
        load, w, ok = quiet_gate(*quiet)
        if w > 1 or not ok:
            _log(f"quiet gate: load {load:.2f} after {w:.0f}s")
    try:
        return run_command(hold, cmd)
    finally:
        hold.release()


if __name__ == "__main__":
    sys.exit(main(sys.argv))
