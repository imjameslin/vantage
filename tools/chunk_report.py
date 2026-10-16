#!/usr/bin/env python3
"""Stage-2 W0b chunk-pipeline report (bench/README.md "Chunk pipeline"): prints the "chunks" block of bench JSONs
(join runs: time to all built and the counters up to it; perf / travel runs with bench.chunkStats=true: the measured
phase), and with --series the 250 ms series. Several files: one summary line each, then mean / sd per label prefix
(--group N: the first N dash-separated fields of the label).

usage: chunk_report.py FILE.json [...] [--series] [--detail] [--group N]
"""
import json
import statistics
import sys


def load(path):
    with open(path) as f:
        return json.load(f)


def line(path, d):
    c = d.get("chunks")
    disp = d.get("launch", {}).get("display", {})
    mode = f"{disp.get('pixels')}@{disp.get('hz')} default {disp.get('default')}"
    if not c:
        return f"{d.get('label', path):28s} no chunks block {d.get('error', '')}", {}
    out = {}
    if "join" in c:
        j = c["join"]
        t = j.get("toBuilt", {})
        out = {"allBuiltS": j["allBuiltS"], "chunksDoneS": j["chunksDoneS"], "maxFrameMs": j["maxFrameMs"],
               "compileUs": t.get("compileUsMean", 0), "compiles": j.get("compilesToBuilt", 0)}
        s = (f"{d.get('label', path):28s} rd {d.get('renderDistance', 0):.0f} allBuilt {j['allBuiltS']:6.2f} s  chunksDone "
             f"{j['chunksDoneS']:6.2f} s  chunks {j['loadedChunks']}  compiles {j.get('compilesToBuilt', 0)} "
             f"({t.get('compileUsMean', 0):.0f} us)  maxFrame {j['maxFrameMs']:.1f} ms  frames {j['frames']}  {mode}")
    else:
        m = c.get("measure") or c.get("total")
        probes = sum(v["ms"] for k, v in m.items() if isinstance(v, dict) and k not in ("runLightUpdates", "replaceWithPacketData", "swapSectionMap"))
        out = {"median": d.get("medianFps", 0), "p1Low": d.get("p1LowFps", 0), "p01Low": d.get("p01LowFps", 0),
               "maxFrameMs": d.get("maxFrameMs", 0), "compiles": m.get("compiles", 0), "compileCpuS": m.get("compileNs", 0) / 1e9,
               "uploadMB": m.get("uploadBytes", 0) / 1e6, "blockMarks": m.get("marks.block", 0),
               "blocksUpdateMs": m.get("blocksUpdate", {}).get("ms", 0), "renderThreadChunkMs": probes}
        s = (f"{d.get('label', path):28s} median {out['median']:6.0f} 1%low {out['p1Low']:5.0f} 0.1%low {out['p01Low']:5.0f} max "
             f"{out['maxFrameMs']:5.1f} ms  compiles {out['compiles']} ({m.get('compileUsMean', 0):.0f} us, {out['compileCpuS']:.1f} s)"
             f"  upload {out['uploadMB']:.0f} MB  block marks {out['blockMarks']}  blocksUpdate {out['blocksUpdateMs']:.0f} ms"
             f"  chunk probes {probes:.0f} ms  {mode}")
    return s, out


def detail(c, key):
    blk = c["join"]["toBuilt"] if key == "toBuilt" else c.get(key)
    if not blk:
        return
    print(f"  [{key}]")
    plain = {k: v for k, v in blk.items() if not isinstance(v, dict) and v}
    print("   ", ", ".join(f"{k} {v}" for k, v in plain.items()))
    for k, v in blk.items():
        if isinstance(v, dict) and v.get("calls"):
            print(f"    {k:22s} calls {v['calls']:7d}  ms {v['ms']:9.2f}  mean {v['usMean']:8.1f} us  max {v['maxMs']:6.2f} ms")
    rec = sum(blk.get(f"recompileDone.{s}", 0) for s in ("other", "lightPacket", "chunkLoad", "block"))
    ide = sum(blk.get(f"identical.{s}", 0) for s in ("other", "lightPacket", "chunkLoad", "block"))
    if rec:
        print(f"    recompiles (per source bit) {rec}, identical {ide} ({100.0 * ide / rec:.1f} %)")


def main(argv):
    files = [a for a in argv if not a.startswith("--")]
    group = 0
    if "--group" in argv:
        group = int(argv[argv.index("--group") + 1])
        files = [f for f in files if f != str(group)]
    groups = {}
    for f in files:
        d = load(f)
        s, vals = line(f, d)
        print(s)
        c = d.get("chunks")
        if c and "--detail" in argv:
            for key in ("toBuilt", "measure", "total"):
                if key == "toBuilt" and "join" not in c:
                    continue
                detail(c, key)
                if key == "toBuilt":
                    break
        if c and "--series" in argv:
            for r in c["series"]["rows"]:
                print("   ", r)
        if group and vals:
            g = "-".join(d.get("label", f).split("-")[:group])
            groups.setdefault(g, []).append(vals)
    for g, rows in groups.items():
        keys = rows[0].keys()
        parts = []
        for k in keys:
            xs = [r[k] for r in rows if k in r]
            sd = statistics.stdev(xs) if len(xs) > 1 else 0.0
            parts.append(f"{k} {statistics.mean(xs):.2f} (sd {sd:.2f})")
        print(f"{g:28s} n={len(rows)}  " + "  ".join(parts))


if __name__ == "__main__":
    main(sys.argv[1:])
