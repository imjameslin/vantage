#!/usr/bin/env python3
"""Work counts of fixed-view runs (results/GPU-HEAVY.md): terrain quads per layer and facing / frustum counters from the
bench JSON's "terrain" block (-Dmc.terrain.stats=true), and the occlusion dry run's would-cull share from the game log
(MCMTL_OCCLUSION=2 with MCMTL_DECODER_STATS=1: last "mcmtl occlusion" line of the measured window).

usage: work_counts.py <label> [<label> ...]   (reads results/raw/<label>.json and .log)
"""
import json, re, sys


def main():
    print("| run | sections | records | solid quads | cutout quads | translucent quads | cloud quads | facing-skippable (all / solid) | occlusion dry run: would cull sections / of | planned frames |")
    print("|---|---|---|---|---|---|---|---|---|---|")
    for label in sys.argv[1:]:
        d = json.load(open(f"results/raw/{label}.json"))
        t = d.get("terrain", {})
        occ = None
        for line in open(f"results/raw/{label}.log", errors="replace"):
            if "mcmtl occlusion" in line and "planned 600 of 600" in line:
                occ = line
        oc = "-"
        if occ:
            m = re.search(r"sections ([\d.]+), culled [\d.]+ \(dry run would cull ([\d.]+)\)", occ)
            if m:
                oc = f"{float(m.group(2)):.0f} / {float(m.group(1)):.0f} ({100 * float(m.group(2)) / float(m.group(1)):.0f} %)"
        g = lambda k: t.get(k, 0) or 0
        print(f"| {label} | {g('sectionsPerFrame'):.0f} | {g('recordsPerFrame'):.0f} | {g('solidQuads'):.0f} | {g('cutoutQuads'):.0f} | "
              f"{g('translucentQuads'):.0f} | {g('cloudQuads'):.0f} | {g('skippableFacing'):.0f} / {g('skippableFacingSolid'):.0f} | {oc} | "
              f"{'600/600' if occ else '-'} |")


if __name__ == "__main__":
    main()
