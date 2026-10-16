#!/usr/bin/env python3
"""Travel stall table for frame-traced runs (round 5 finalize, pregrow decision): stall_table.py label...

Per run: FPS metrics, the max frame (ms, t in the measured window, attribution), the longest GPU command buffer (gpuBusy,
t) and how many command buffers ran over 8 ms on the GPU, the heap-reserve fills from the game log (Metal heap reserve:
... made resident ahead of need in X ms), and the system free-memory percentage over the run (results/raw/<label>.mem.tsv,
written by runner.py with MEMLOG=1). Reads results/raw/<label>.json (+ .log, .mem.tsv); uses tools/frame_trace.py --csv.
"""
import csv, json, os, re, statistics as st, subprocess, sys, tempfile

WT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
os.chdir(WT)


def row(label):
    d = json.load(open(f"results/raw/{label}.json"))
    with tempfile.NamedTemporaryFile(suffix=".csv", delete=False) as tf:
        path = tf.name
    subprocess.run(["python3", "tools/frame_trace.py", f"results/raw/{label}.json", "--csv", path], capture_output=True)
    rows = list(csv.DictReader(open(path)))
    os.unlink(path)
    t0 = int(rows[0]["tNs"]) - int(rows[0]["dtNs"])
    mx = max(rows, key=lambda r: int(r["dtNs"]))
    busy = [(int(r["gpuBusyNs"]), int(r["tNs"])) for r in rows if r["gpuBusyNs"] not in ("", "-1")]
    gb, gbt = max(busy)
    over8 = sum(1 for b, _ in busy if b > 8e6)
    # queue delay: commit -> GPU start of the frame's last command buffer (a side-queue fill or residency work delays it)
    qd = [(int(r["gpuStartNs"]) - int(r["commitNs"]), int(r["tNs"])) for r in rows
          if r["gpuStartNs"] not in ("", "-1", "0") and r["commitNs"] not in ("", "-1", "0")]
    qmax, qt = max(qd) if qd else (0, t0)
    log = open(f"results/raw/{label}.log", errors="replace").read() if os.path.exists(f"results/raw/{label}.log") else ""
    hms = lambda h: int(h[0:2]) * 3600 + int(h[3:5]) * 60 + int(h[6:8])
    m0 = re.search(r"^\[(\d\d:\d\d:\d\d)\].*\[bench\] -> MEASURE", log, re.M)
    fills = []
    for m in re.finditer(r"^\[(\d\d:\d\d:\d\d)\].*made resident ahead of need in ([0-9.]+) ms", log, re.M):
        rel = hms(m.group(1)) - hms(m0.group(1)) if m0 else None
        fills.append((float(m.group(2)), rel))
    mem = []
    if os.path.exists(f"results/raw/{label}.mem.tsv"):
        for line in list(open(f"results/raw/{label}.mem.tsv"))[1:]:
            try:
                mem.append(int(line.split("\t")[1]))
            except Exception:
                pass
    return dict(label=label, median=d["medianFps"], avg=d["avgFps"], p1=d["p1LowFps"], p01=d["p01LowFps"],
                maxms=int(mx["dtNs"]) / 1e6, maxt=(int(mx["tNs"]) - t0) / 1e9, maxattr=mx["attribution"],
                gbusy=gb / 1e6, gbt=(gbt - t0) / 1e9, over8=over8, fills=fills, qmax=qmax / 1e6, qt=(qt - t0) / 1e9,
                mem=(min(mem), st.mean(mem)) if mem else None)


def main():
    print("| run | median | avg | 1% low | 0.1% low | max frame ms (t s, attribution) | longest GPU cmd buffer ms (t s) | GPU cmd buffers > 8 ms | longest commit-to-GPU-start ms (t s) | heap fills ms (t s, +-1) | free memory min / mean % |")
    print("|---|---|---|---|---|---|---|---|---|---|---|")
    for label in sys.argv[1:]:
        r = row(label)
        fills = ", ".join(f"{x:.0f} ({'warmup' if t is not None and t < 0 else t})" for x, t in r["fills"]) or "-"
        mem = f"{r['mem'][0]} / {r['mem'][1]:.0f}" if r["mem"] else "-"
        print(f"| {label} | {r['median']:.0f} | {r['avg']:.0f} | {r['p1']:.0f} | {r['p01']:.0f} | {r['maxms']:.1f} ({r['maxt']:.1f}, {r['maxattr']}) | "
              f"{r['gbusy']:.1f} ({r['gbt']:.1f}) | {r['over8']} | {r['qmax']:.1f} ({r['qt']:.1f}) | {fills} | {mem} |")


if __name__ == "__main__":
    main()
