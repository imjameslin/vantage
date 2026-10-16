#!/usr/bin/env python3
"""Markdown tables for a Default-mode scorecard (tools/ab/scorecard_default.py; results/BASELINE-DEFAULT.md): per-run rows,
per-arm means, and the stage-1 bars (docs/GOAL.md: median >= 3x vanilla OpenGL and >= 2x vanilla Vulkan, 1% low >= 2x
vanilla Vulkan's) for every patched arm. Only rows the runner marked "ok" (Default mode before and after, 3024x1964
framebuffer, all checks) enter the means; DISCARD rows are listed with their reason.

usage: baseline_table.py <kind: static|travel> [--tsv results/raw/bd-runs.tsv] [--prefix bd-st-]
"""
import csv, statistics, sys

ORDER = ["gl", "vk", "r4", "r5", "main", "glass"]
NAMES = {"gl": "vanilla OpenGL", "vk": "vanilla Vulkan", "r4": "client-r4", "r5": "client-r5", "main": "main (r5 + W1)",
         "glass": "main + glass trace"}


def main():
    args = sys.argv[1:]
    kind = args[0]
    tsv = "results/raw/bd-runs.tsv"
    prefix = None
    if "--tsv" in args:
        tsv = args[args.index("--tsv") + 1]
    if "--prefix" in args:
        prefix = args[args.index("--prefix") + 1]
    rows = [r for r in csv.DictReader(open(tsv), delimiter="\t") if r["kind"] == kind and (prefix is None or r["label"].startswith(prefix))]
    print(f"| # | run | arm | RD | median | avg | 1% low | 0.1% low | p99 ms | max ms | presented/s | load during | overlays | counts |")
    print("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|")
    for i, r in enumerate(rows, 1):
        pf = r["presentedFps"] if r["backend"] == "Metal" else "n/a"
        why = r["counts"] if r["counts"] == "ok" else "DISCARD " + " ".join(n for n in r["notes"].split() if not n.startswith("eff="))
        print(f"| {i} | {r['label']} | {r['arm']} | {r['rd']} | {float(r['median']):.0f} | {float(r['avg']):.0f} | {float(r['p1']):.0f} | "
              f"{float(r['p01']):.0f} | {float(r['p99ms']):.2f} | {float(r['maxms']):.1f} | {pf} | {r['loadDuring']} | "
              f"{r['overlaysAtStart']} / {r['overlaysAfter']} | {why} |")
    ok = [r for r in rows if r["counts"] == "ok"]
    arms = {}
    for r in ok:
        arms.setdefault(r["arm"], []).append(r)
    print()
    print("| config | n | median | avg | 1% low | 0.1% low | max frame ms (mean / worst) | median range | 1% low range |")
    print("|---|---|---|---|---|---|---|---|---|")
    mean = {}
    for a in ORDER:
        if a not in arms:
            continue
        rs = arms[a]
        m = {k: statistics.mean(float(r[k]) for r in rs) for k in ("median", "avg", "p1", "p01", "maxms")}
        mean[a] = m
        meds = [float(r["median"]) for r in rs]
        p1s = [float(r["p1"]) for r in rs]
        print(f"| {NAMES[a]} | {len(rs)} | {m['median']:.1f} | {m['avg']:.1f} | {m['p1']:.1f} | {m['p01']:.1f} | "
              f"{m['maxms']:.1f} / {max(float(r['maxms']) for r in rs):.1f} | {min(meds):.0f}-{max(meds):.0f} | {min(p1s):.0f}-{max(p1s):.0f} |")
    pairs = [(b, a) for a, b in (("r4", "r5"), ("r5", "main"), ("r4", "main")) if a in arms and b in arms]
    if pairs:
        print()
        print("Same-round pairs (run i of one config with run i of the other; plans interleave every config once per round):")
        print()
        print("| pair | n | median | avg | 1% low | 0.1% low |")
        print("|---|---|---|---|---|---|")
        for b, a in pairs:
            ra = {r["label"].rsplit("-", 1)[1]: r for r in arms[a]}   # round number = the label's last field
            rb = {r["label"].rsplit("-", 1)[1]: r for r in arms[b]}
            rounds = sorted(set(ra) & set(rb))
            n = len(rounds)
            cells = []
            for k in ("median", "avg", "p1", "p01"):
                d = [float(rb[i][k]) / float(ra[i][k]) - 1 for i in rounds]
                cells.append(f"{100 * statistics.mean(d):+.1f}% ({sum(x > 0 for x in d)}/{n} better)")
            print(f"| {NAMES[b]} vs {NAMES[a]} | {n} | " + " | ".join(cells) + " |")
    if "gl" in mean and "vk" in mean:
        gl, vk = mean["gl"], mean["vk"]
        print()
        print(f"Bars from these means: median >= 3x OpenGL = {3 * gl['median']:.0f}; median >= 2x Vulkan = {2 * vk['median']:.0f}; "
              f"1% low >= 2x Vulkan's = {2 * vk['p1']:.0f}.")
        print()
        print("| config | median / OpenGL (bar 3x) | median / Vulkan (bar 2x) | 1% low / Vulkan 1% low (bar 2x) | avg / Vulkan | 0.1% low / Vulkan |")
        print("|---|---|---|---|---|---|")
        for a in ORDER:
            if a in mean and a not in ("gl", "vk"):
                m = mean[a]
                f = lambda x, bar: f"**{x:.2f}x**" + (" met" if x >= bar else " missed")
                print(f"| {NAMES[a]} | {f(m['median'] / gl['median'], 3)} | {f(m['median'] / vk['median'], 2)} | "
                      f"{f(m['p1'] / vk['p1'], 2)} | {m['avg'] / vk['avg']:.2f}x | {m['p01'] / vk['p01']:.2f}x |")


if __name__ == "__main__":
    main()
