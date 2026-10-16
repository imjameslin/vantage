#!/usr/bin/env python3
"""Headless test of tools/gpulock.py on a private lock file (MC_GPU_LOCK in a temp dir; the machine's real GPU lock is
never touched). No game, no GPU. Each command records start / end wall times; the checks are on the resulting intervals.

  A  two functional holders overlap; a timing request waits for both; a functional request that arrives while the timing
     request waits starts only after it (writer preference: no starvation)
  B  a legacy `lockf -k <lock>` waiter (gate-unaware, like the user-play guard) is not starved: functional requests
     arriving after it wait for it; a lockf holder excludes functional requests
  C  nesting: inside `lockf -k <lock>` a timing request is covered (old behaviour: deadlock); functional inside functional
     and functional inside timing are covered; timing inside functional is an error (exit 75), not a deadlock
  D  build lane: waits for functional holders of the same checkout (root key), overlaps those of another checkout,
     never overlaps a timing run
  E  status lists holders and the legacy waiter; the lock is free at the end; fds are inherited: killing the wrapper
     (SIGKILL) keeps the lock until the command itself exits

usage: python3 tools/gpulock_test.py      (about 40 s; exit 1 on failure)
"""
import os, shutil, signal, subprocess, sys, tempfile, time

HERE = os.path.dirname(os.path.abspath(__file__))
GPULOCK = os.path.join(HERE, "gpulock.py")
TMP = tempfile.mkdtemp(prefix="gpulock-test-")
LOCK = os.path.join(TMP, "gpu.lock")
LOG = os.path.join(TMP, "events.log")
ENV = dict(os.environ, MC_GPU_LOCK=LOCK)
ENV.pop("MC_GPU_LOCK_HELD", None)
RECORD = ("import sys,time\nlog,name,d=sys.argv[1],sys.argv[2],float(sys.argv[3])\n"
          "w=lambda e: open(log,'a').write(f'{name} {e} {time.time()}\\n')\nw('start'); time.sleep(d); w('end')")
failures = 0
procs = []


def check(ok, what):
    global failures
    print(("ok   " if ok else "FAIL ") + what)
    failures += 0 if ok else 1


def rec(name, dur):
    return [sys.executable, "-c", RECORD, LOG, name, str(dur)]


def gl(lane, name, dur, gpulock=GPULOCK, inner=None):
    return [sys.executable, gpulock, lane, "--label", name, "--"] + (inner or rec(name, dur))


def start(cmd, delay=0.0):
    time.sleep(delay)
    p = subprocess.Popen(cmd, env=ENV, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    procs.append(p)
    return p


def events():
    ev = {}
    if os.path.exists(LOG):
        for line in open(LOG):
            n, e, t = line.split()
            ev.setdefault(n, {})[e] = float(t)
    return ev


def reset():
    for p in procs:
        p.wait(timeout=60)
    procs.clear()
    if os.path.exists(LOG):
        os.unlink(LOG)


def iv(ev, n):
    return ev[n]["start"], ev[n]["end"]


EPS = 0.02


def scenario_a():
    print("A: overlap, writer preference")
    start(gl("functional", "F1", 2.0))
    start(gl("functional", "F2", 2.0), 0.3)
    start(gl("timing", "T1", 1.0), 0.4)
    start(gl("functional", "F3", 1.0), 0.5)
    reset_wait = [p for p in procs]
    for p in reset_wait:
        p.wait(timeout=60)
    ev = events()
    f1, f2, t1, f3 = iv(ev, "F1"), iv(ev, "F2"), iv(ev, "T1"), iv(ev, "F3")
    check(f2[0] < f1[1], f"F1 and F2 overlap (F2 started {f2[0] - f1[0]:.2f}s after F1, F1 ended at {f1[1] - f1[0]:.2f}s)")
    check(t1[0] >= max(f1[1], f2[1]) - EPS, f"T1 waited for both functional holders (started {t1[0] - max(f1[1], f2[1]):+.2f}s after the last ended)")
    check(f3[0] >= t1[1] - EPS, f"F3 (arrived while T1 waited) started after T1 ended ({f3[0] - t1[1]:+.2f}s): no starvation")
    reset()


def scenario_b():
    print("B: legacy lockf waiter")
    start(gl("functional", "F1", 2.0))
    start(["lockf", "-k", LOCK] + rec("L1", 1.0), 0.5)
    start(gl("functional", "F2", 1.0), 0.5)
    for p in list(procs):
        p.wait(timeout=60)
    ev = events()
    f1, l1, f2 = iv(ev, "F1"), iv(ev, "L1"), iv(ev, "F2")
    check(l1[0] >= f1[1] - EPS, f"lockf waited for the functional holder ({l1[0] - f1[1]:+.2f}s)")
    check(f2[0] >= l1[1] - EPS, f"F2 (arrived while lockf waited) started after lockf's command ended ({f2[0] - l1[1]:+.2f}s)")
    out = procs[2].stdout.read() if procs[2].stdout else ""
    check("yielding to a waiting exclusive lockf user" in out, "F2 logged that it yielded to the lockf waiter")
    reset()
    start(["lockf", "-k", LOCK] + rec("L2", 1.0))
    start(gl("functional", "F4", 0.5), 0.3)
    for p in list(procs):
        p.wait(timeout=60)
    ev = events()
    check(iv(ev, "F4")[0] >= iv(ev, "L2")[1] - EPS, "a lockf holder excludes functional requests")
    reset()
    # Another tool's own flock(LOCK_EX) scheduler (a script file: no known command-line pattern; found through lsof).
    sched = os.path.join(TMP, "own_scheduler.py")
    with open(sched, "w") as f:
        f.write("import fcntl,sys,time\nf=open(sys.argv[1],'w'); fcntl.flock(f, fcntl.LOCK_EX)\n" + RECORD.replace("sys.argv[1],sys.argv[2],float(sys.argv[3])", "sys.argv[2],sys.argv[3],float(sys.argv[4])") + "\n")
    start(gl("functional", "F5", 2.0))
    start([sys.executable, sched, LOCK, LOG, "S1", "1.0"], 0.5)
    start(gl("functional", "F6", 0.5), 0.5)
    for p in list(procs):
        p.wait(timeout=60)
    ev = events()
    f5, s1, f6 = iv(ev, "F5"), iv(ev, "S1"), iv(ev, "F6")
    check(s1[0] >= f5[1] - EPS and f6[0] >= s1[1] - EPS,
          f"an unknown flock(EX) user waiting on the lock file is not starved (it started {s1[0] - f5[1]:+.2f}s after F5, F6 {f6[0] - s1[1]:+.2f}s after it)")
    reset()


def scenario_c():
    print("C: nesting")
    r = subprocess.run(["lockf", "-k", "-t", "10", LOCK] + gl("timing", "N1", 0.1), env=ENV, capture_output=True, text=True, timeout=30)
    check(r.returncode == 0 and "N1" in events(), f"timing inside lockf -k: covered, no deadlock (rc {r.returncode})")
    r = subprocess.run(gl("functional", "outer", 0, inner=gl("functional", "N2", 0.1)), env=ENV, capture_output=True, text=True, timeout=30)
    check(r.returncode == 0 and "N2" in events(), f"functional inside functional: covered (rc {r.returncode})")
    r = subprocess.run(gl("timing", "outer", 0, inner=gl("functional", "N3", 0.1)), env=ENV, capture_output=True, text=True, timeout=30)
    check(r.returncode == 0 and "N3" in events(), f"functional inside timing: covered (rc {r.returncode})")
    r = subprocess.run(gl("functional", "outer", 0, inner=gl("timing", "N4", 0.1)), env=ENV, capture_output=True, text=True, timeout=30)
    check(r.returncode == 75 and "N4" not in events() and "would wait for itself" in r.stderr, f"timing inside functional: error 75, no deadlock (rc {r.returncode})")
    r = subprocess.run(gl("functional", "outer", 0, inner=[sys.executable, "-c", "import os; print(os.environ.get('MC_GPU_LOCK_HELD'))"]),
                       env=ENV, capture_output=True, text=True, timeout=30)
    check(r.stdout.strip().endswith("functional"), f"the command sees MC_GPU_LOCK_HELD=functional ({r.stdout.strip()!r})")
    r = subprocess.run([sys.executable, GPULOCK, "functional", "--", "sh", "-c", "exit 7"], env=ENV, capture_output=True, timeout=30)
    check(r.returncode == 7, f"exit code passed through (rc {r.returncode})")
    reset()


def scenario_d():
    print("D: build lane")
    other = os.path.join(TMP, "other-checkout", "tools")
    os.makedirs(other)
    shutil.copy(GPULOCK, other)
    start(gl("functional", "F1", 1.5))
    start(gl("build", "B1", 0.5), 0.3)
    start(gl("build", "B2", 0.5, gpulock=os.path.join(other, "gpulock.py")), 0.0)
    for p in list(procs):
        p.wait(timeout=60)
    ev = events()
    f1, b1, b2 = iv(ev, "F1"), iv(ev, "B1"), iv(ev, "B2")
    check(b1[0] >= f1[1] - EPS, f"build waits for a functional run of the same checkout ({b1[0] - f1[1]:+.2f}s)")
    check(b2[0] < f1[1], "build of another checkout overlaps it")
    reset()
    start(gl("timing", "T1", 1.0))
    start(gl("build", "B3", 0.3), 0.3)
    for p in list(procs):
        p.wait(timeout=60)
    ev = events()
    check(iv(ev, "B3")[0] >= iv(ev, "T1")[1] - EPS, "build never overlaps a timing run")
    reset()


def scenario_e():
    print("E: status, inheritance")
    start(gl("functional", "S1", 2.0))
    start(["lockf", "-k", LOCK] + rec("S2", 0.2), 0.5)
    time.sleep(0.5)
    st = subprocess.run([sys.executable, GPULOCK, "status"], env=ENV, capture_output=True, text=True).stdout
    check("functional holding" in st and "S1" in st and "legacy lockf waiting" in st, "status shows the holder and the legacy waiter")
    for p in list(procs):
        p.wait(timeout=60)
    reset()
    p = start(gl("timing", "K1", 2.0))
    time.sleep(0.6)
    p.send_signal(signal.SIGKILL)
    p.wait()
    t0 = time.time()
    r = subprocess.run([sys.executable, GPULOCK, "functional", "--", "true"], env=ENV, timeout=30)
    ev = events()
    check(r.returncode == 0 and time.time() >= ev["K1"]["end"] - EPS, f"wrapper SIGKILLed: lock held until its command exited (waited {time.time() - t0:.1f}s)")
    reset()
    st = subprocess.run([sys.executable, GPULOCK, "status"], env=ENV, capture_output=True, text=True).stdout
    check("free" in st, "lock free at the end" + ("" if "free" in st else ": " + st))


try:
    scenario_a()
    scenario_b()
    scenario_c()
    scenario_d()
    scenario_e()
finally:
    for p in procs:
        if p.poll() is None:
            p.kill()
    shutil.rmtree(TMP, ignore_errors=True)
print(f"gpulock_test: {failures} failure(s)")
sys.exit(1 if failures else 0)
