#!/usr/bin/env python3
"""Report and re-analyse in-session A/B runs (bench mode=abab; docs/metal/CONTRACT.md 16.AB, bench/src/bench/Abab.java).

    python3 tools/abab.py results/raw/LABEL.json [more.json ...]   per run: arms, effects with intervals, looks
        --bins        per-bin table (A vs B: median2 / sector p99 / GPU median per 15-degree sector, pooled over blocks)
        --blocks      per-block table (frames, presented share per arm)
        --recompute   re-derive cells, pair tails and effects in Python from the per-frame arrays (independent of Java)
        --discard K   with --recompute: discard the first K frames after every switch point instead of the run's own setting
        --summary     one line per run (several runs: also how many decision intervals cover 0, per metric)
        --table       markdown table of the effects (from the per-frame arrays when present, so older results get the
                      pair metrics too); bold: a Holm call on a primary metric; a secondary metric whose own interval
                      excludes 0 is marked with a degree sign (descriptive only)
        --looks m1,m2 replay the sequential stop rule offline on these metrics (the run's alpha, minPairs, margin and the
                      balanced look schedule of AbabStats.lookPairs)
    python3 tools/abab.py --combine a.json b.json ...   pool launches of the same comparison per metric: each launch's
        estimate and interval, the inverse-variance pooled estimate, Cochran's Q / I^2 (do the launches agree?) and the
        t interval over the launch estimates (k - 1 df: the honest one when launches disagree)
    python3 tools/abab.py --classic "title" base1:cand1 base2:cand2 ...   classic full-launch pairs (results/raw/<label>.json):
        per pair % change of median / avg / 1% low / 0.1% low, mean, paired t interval on log ratios, sign test
    python3 tools/abab.py --selftest [results ...]   synthetic checks; with results: Python vs Java on real runs

Effects are B relative to A. For frame-time metrics the FPS view is reported: "+2%" = B has 2% more FPS (1/time). GPU
metrics are reported in time: "-2%" = B's GPU busy time is 2% shorter.

Metrics (docs/metal/CONTRACT.md 16.AB). Primary (the decision family of docs/GOAL.md's "median + 1% low", Holm over
the two; with the stop on, on p x looks): median2 (median of two-frame means per 15-degree sector) and pairP99 ("1%
low": per pair, the p99 of all frames of each arm, the classic definition). Secondary (descriptive, never a call): mean
(avg FPS), median (plain per-sector median: bimodal, jumps), p99 ("sector p99": the mean of the per-sector p99s, every
view weighted equally; NOT the classic 1% low), pairP999 (0.1% low), GPU busy median / p99 (a span, not pure work).
"""
import functools
import json
import math
import random
import sys

METRICS = ("median", "p99", "mean", "gpuMedian", "gpuP99", "median2")          # cell metrics (AbabStats 0..NC-1)
PAIR_METRICS = ("pairP99", "pairP999")                                         # one value per pair and arm
PAIR_PS = (0.99, 0.999)
ALL_METRICS = METRICS + PAIR_METRICS
PRIMARY = ("median2", "pairP99")
SECONDARY = ("mean", "median", "p99", "pairP999", "gpuMedian", "gpuP99")
# Presentation priority only. Do not reinterpret historical hypothesis tests or stopping rules.
REPORT_ORDER = ("pairP99", "pairP999", "p99", "mean", "median", "median2", "gpuP99", "gpuMedian")
TIME_METRICS = ("median", "p99", "mean", "median2", "pairP99", "pairP999")   # reported as FPS change
LABELS = {"median": "median FPS", "median2": "median FPS (2-frame)", "p99": "sector p99", "mean": "avg FPS",
          "gpuMedian": "GPU busy median", "gpuP99": "GPU busy p99", "pairP99": "1% low", "pairP999": "0.1% low"}


# ---- distributions (independent of the Java implementation: numerical integration) -----------------------------------

def t_pdf(x, df):
    return math.exp(math.lgamma((df + 1) / 2) - math.lgamma(df / 2) - 0.5 * math.log(df * math.pi)
                    - (df + 1) / 2 * math.log1p(x * x / df))


def t_cdf(x, df):
    if x == 0:
        return 0.5
    a = abs(x)
    n = 4000
    h = a / n
    s = t_pdf(0, df) + t_pdf(a, df)
    for i in range(1, n):
        s += (4 if i % 2 else 2) * t_pdf(i * h, df)
    area = s * h / 3
    return 0.5 + area if x > 0 else 0.5 - area


@functools.lru_cache(maxsize=None)
def t_quantile(p, df):
    lo, hi = 0.0, 1.0
    target = max(p, 1 - p)
    while t_cdf(hi, df) < target:
        hi *= 2
        if hi > 1e6:
            break
    for _ in range(80):
        mid = (lo + hi) / 2
        if t_cdf(mid, df) < target:
            lo = mid
        else:
            hi = mid
    q = (lo + hi) / 2
    return q if p >= 0.5 else -q


def sign_p(plus, minus):
    n = plus + minus
    if n == 0:
        return 1.0
    k = min(plus, minus)
    s = sum(math.exp(math.lgamma(n + 1) - math.lgamma(i + 1) - math.lgamma(n - i + 1) + n * math.log(0.5)) for i in range(k + 1))
    return min(1.0, 2 * s)


def pct(sorted_vals, p):
    if not sorted_vals:
        return float("nan")
    return sorted_vals[min(len(sorted_vals) - 1, int(math.floor(p * (len(sorted_vals) - 1) + 0.5)))]


def to_pct(log_ratio, metric):
    """Percent change in the reported direction (FPS for frame-time metrics)."""
    if log_ratio is None or (isinstance(log_ratio, float) and math.isnan(log_ratio)):
        return float("nan")
    return (math.exp(-log_ratio if metric in TIME_METRICS else log_ratio) - 1) * 100


# ---- recomputation from the per-frame arrays --------------------------------------------------------------------------

def cells_from_frames(ab, discard=None, min_cell=None):
    """Per block: arm and per bin {metric: value (ns) or nan}, from the frames arrays (same rules as AbabStats)."""
    fr = ab["frames"]
    dt, gpu, binv, blk, keep = fr["dtNs"], fr["gpuBusyNs"], fr["bin"], fr["block"], fr["keep"]
    bins = ab["bins"]
    segs = ab.get("segments", 1)
    min_cell = ab["minCell"] if min_cell is None else min_cell
    blocks = []
    for b in ab["blocks"]:
        s, n = b["startFrame"], b["frames"]
        ks = range(s, s + n)
        if discard is None:
            kept = [k for k in ks if keep[k]]
        else:
            # Discard the first `discard` frames after every segment start (block start included).
            kept, seg, since = [], -1, 0
            for k in ks:
                sg = binv[k] * segs // bins if k > s else 0
                if sg != seg:
                    seg, since = sg, 0
                if since >= discard:
                    kept.append(k)
                since += 1
        kset = set(kept)
        per = [{"d": [], "g": [], "d2": []} for _ in range(bins)]
        for k in kept:
            c = per[binv[k]]
            c["d"].append(dt[k])
            if gpu[k] >= 0:
                c["g"].append(gpu[k])
            if k > s and (k - 1) in kset and binv[k - 1] == binv[k]:
                c["d2"].append(dt[k - 1] + dt[k])
        cells = []
        for c in per:
            d, g, d2 = sorted(c["d"]), sorted(c["g"]), sorted(c["d2"])
            v = {m: float("nan") for m in METRICS}
            v["n"] = len(d)
            if len(d) >= min_cell:
                v["median"], v["p99"], v["mean"] = pct(d, 0.5), pct(d, 0.99), sum(d) / len(d)
            if len(g) >= min_cell:
                v["gpuMedian"], v["gpuP99"] = pct(g, 0.5), pct(g, 0.99)
            if len(d2) >= min_cell:
                v["median2"] = pct(d2, 0.5) / 2
            cells.append(v)
        blocks.append({"arm": b["arm"], "arms": cell_arms(b, bins), "cells": cells})
    return blocks


def pair_tails_from_frames(ab, discard=None, min_frames=None):
    """Per pair: [[A p99, A p99.9], [B p99, B p99.9]] (ns) over all kept frames of the pair's two blocks, each frame in the
    arm of its cell (AbabStats.pairTails)."""
    fr = ab["frames"]
    dt, binv, keep = fr["dtNs"], fr["bin"], fr["keep"]
    bins = ab["bins"]
    segs = ab.get("segments", 1)
    min_frames = ab["minCell"] if min_frames is None else min_frames
    out = []
    blocks = ab["blocks"]
    for i in range(len(blocks) // 2):
        vals = {"A": [], "B": []}
        for b in (blocks[2 * i], blocks[2 * i + 1]):
            arms = cell_arms(b, bins)
            s, n = b["startFrame"], b["frames"]
            seg, since = -1, 0
            for k in range(s, s + n):
                if discard is None:
                    kept = keep[k]
                else:
                    sg = binv[k] * segs // bins if k > s else 0
                    if sg != seg:
                        seg, since = sg, 0
                    kept = since >= discard
                    since += 1
                if kept:
                    vals[arms[binv[k]]].append(dt[k])
        row = []
        for a in ("A", "B"):
            v = sorted(vals[a])
            row.append([pct(v, p) if len(v) >= min_frames else float("nan") for p in PAIR_PS])
        out.append(row)
    return out


def pair_tails_from_json(ab):
    """Pair tails recorded by the Java side (blocks[2i+1].pairTailsNs), or None for results without them."""
    out = []
    for i in range(len(ab["blocks"]) // 2):
        t = ab["blocks"][2 * i + 1].get("pairTailsNs")
        if t is None:
            return None
        out.append([[float("nan") if v is None else v for v in t["A"]], [float("nan") if v is None else v for v in t["B"]]])
    return out


def cell_arms(block, bins):
    """Arm of each bin of a block ('cellArms'; one arm for the whole block in results without segments)."""
    return list(block.get("cellArms") or block["arm"] * bins)


def cells_from_json(ab):
    cols = ab["cellColumns"]
    out = []
    for b in ab["blocks"]:
        cells = []
        for row in b["cells"]:
            v = {"n": row[0]}
            for m in METRICS:
                name = m + "Ns"
                v[m] = float("nan") if name not in cols or row[cols.index(name)] is None else float(row[cols.index(name)])
            cells.append(v)
        out.append({"arm": b["arm"], "arms": cell_arms(b, len(cells)), "cells": cells})
    return out


def ratios(blocks, tails, metric, pairs=None):
    """Log ratios of any metric: cells (pairs x bins) for cell metrics, one per pair for pair metrics."""
    if metric in PAIR_METRICS:
        pi = PAIR_METRICS.index(metric)
        pairs = len(tails) if pairs is None else pairs
        out = []
        for i in range(pairs):
            a, b = tails[i][0][pi], tails[i][1][pi]
            out.append([math.log(b / a) if a > 0 and b > 0 else float("nan")])
        return out
    return log_ratios(blocks, metric, pairs)


def log_ratios(blocks, metric, pairs=None):
    pairs = len(blocks) // 2 if pairs is None else pairs
    lr = []
    for i in range(pairs):
        x, y = blocks[2 * i], blocks[2 * i + 1]
        row = []
        for j in range(len(x["cells"])):
            if x["arms"][j] == y["arms"][j]:
                row.append(float("nan"))
                continue
            a, b = (x, y) if x["arms"][j] == "A" else (y, x)
            va, vb = a["cells"][j][metric], b["cells"][j][metric]
            row.append(math.log(vb / va) if va > 0 and vb > 0 else float("nan"))
        lr.append(row)
    return lr


def effect(lr, alpha=0.05, resamples=4000, seed=7):
    sums, cnts = [], []
    plus = minus = 0
    for row in lr:
        vals = [v for v in row if not math.isnan(v)]
        if vals:
            sums.append(sum(vals))
            cnts.append(len(vals))
        plus += sum(1 for v in vals if v > 0)
        minus += sum(1 for v in vals if v < 0)
    e = {"pairs": len(sums), "cells": sum(cnts), "signCellsP": sign_p(plus, minus), "plus": plus, "minus": minus}
    if not sums:
        return e
    est = sum(sums) / sum(cnts)
    means = [s / c for s, c in zip(sums, cnts)]
    e.update(est=est, pairMeans=means, signPairsP=sign_p(sum(1 for m in means if m > 0), sum(1 for m in means if m < 0)))
    p = len(sums)
    if p < 2:
        return e
    rng = random.Random(seed)
    boot = []
    for _ in range(resamples):
        s = c = 0
        for _ in range(p):
            j = rng.randrange(p)
            s += sums[j]
            c += cnts[j]
        boot.append(s / c)
    bm = sum(boot) / len(boot)
    sd = math.sqrt(sum((v - bm) ** 2 for v in boot) / (len(boot) - 1))
    se = sd * math.sqrt(p / (p - 1))
    t = t_quantile(1 - alpha / 2, p - 1)
    mm = sum(means) / p
    sdp = math.sqrt(sum((m - mm) ** 2 for m in means) / (p - 1))
    tt = t_quantile(1 - alpha / 2, p - 1)
    tstat = est / se if se > 0 else (0.0 if est == 0 else math.copysign(float("inf"), est))
    pv = min(1.0, 2 * t_cdf(-abs(tstat), p - 1)) if math.isfinite(tstat) else (1.0 if est == 0 else 0.0)
    e.update(se=se, lo=est - t * se, hi=est + t * se, tLo=mm - tt * sdp / math.sqrt(p), tHi=mm + tt * sdp / math.sqrt(p),
             t=tstat, p=pv, df=p - 1)
    return e


def holm(ps):
    """Holm step-down adjusted p-values (None / nan entries skipped), as AbabStats.holm."""
    idx = sorted((i for i, v in enumerate(ps) if v is not None and not math.isnan(v)), key=lambda i: ps[i])
    out = [float("nan")] * len(ps)
    run = 0.0
    for r, i in enumerate(idx):
        run = max(run, min(1.0, (len(idx) - r) * ps[i]))
        out[i] = run
    return out


def balanced(pairs, segments, abba=True):
    """AbabStats.balanced: even segments balance every pair; one arm per block (odd segments) needs ABBA and even pairs."""
    return segments % 2 == 0 or (abba and pairs % 2 == 0)


def look_pairs(min_pairs, max_pairs, segments, abba=True):
    return [p for p in range(max(2, min_pairs), max_pairs + 1) if balanced(p, segments, abba)]


def analyse(ab, discard=None, recompute=None, level=None):
    """Effects of every metric for one run: from the per-frame arrays when present (recompute None: whenever the JSON lacks
    pair metrics), else from the JSON cells. Returns {metric: effect dict with pct, ci, p, pSeq, holmP, call}."""
    has_frames = "frames" in ab
    if recompute is None:
        recompute = has_frames and (discard is not None or "pairP99" not in ab["effects"])
    if recompute and has_frames:
        blocks = cells_from_frames(ab, discard)
        tails = pair_tails_from_frames(ab, discard)
    else:
        blocks = cells_from_json(ab)
        tails = pair_tails_from_json(ab)
    st = ab.get("stop", {})
    alpha = st.get("alpha", 0.05)
    looks = st.get("lookPairs")
    if looks is None:
        looks = look_pairs(st.get("minPairs", 3), ab["blocksPlanned"] // 2, ab.get("segments", 1), ab.get("order", "abba") != "abab")
    stop_on = bool(st.get("enabled"))
    lvl = (alpha / max(1, len(looks)) if stop_on else alpha) if level is None else level
    out = {}
    for m in ALL_METRICS:
        if m in PAIR_METRICS and tails is None:
            continue
        e = effect(ratios(blocks, tails, m), alpha=lvl)
        if "lo" not in e:
            continue
        e["level"] = 1 - lvl
        e["pSeq"] = min(1.0, e["p"] * (max(1, len(looks)) if stop_on else 1))
        out[m] = e
    fam = [m for m in PRIMARY if m in out]
    adj = holm([out[m]["pSeq"] for m in fam])
    for m, h in zip(fam, adj):
        out[m]["holmP"] = h
        out[m]["call"] = h < alpha
    return out


# ---- reports ----------------------------------------------------------------------------------------------------------

def fmt_ci(metric, lo, hi):
    a, b = to_pct(lo, metric), to_pct(hi, metric)
    return "[%+.2f, %+.2f]" % (min(a, b), max(a, b))


def report(path, args):
    d = json.load(open(path))
    ab = d.get("abab")
    if not ab:
        print("%s: no abab block" % path)
        return None
    print("== %s (%s, %s, framebuffer %s)" % (d.get("label"), path, d.get("backend"), d.get("framebuffer")))
    for t in ab["toggles"]:
        print("   toggle %s: A=%s B=%s (read back %s / %s)" % (t["field"], t["a"], t["b"], t["readBackA"], t["readBackB"]))
    st = ab["stop"]
    print("   %s preset, order %s, %d segment(s)/block, %d/%d blocks x %.1f s (%.0f s measured), discard %d, bins %d (%s), "
          "switch %.1f us, analysis max %.1f ms, gpu %s" % (ab["preset"], ab["order"], ab.get("segments", 1), ab["blocksRun"],
                                                          ab["blocksPlanned"], ab["blockSeconds"],
                                            ab["secondsRun"], ab["discardFrames"], ab["bins"], ab["binKind"], ab["switchMeanUs"] or 0,
                                            ab["analysisMs"]["max"] or 0, "yes" if ab.get("gpu") else "no"))
    print("   stop %s on %s (margin %.2f%%, look alpha %.4f): %s; would stop after %s pairs (saves %.0f s)"
          % ("ON" if st["enabled"] else "off", st["on"], st["marginPct"], st["lookAlpha"], st["reason"], st["wouldStopAfterPairs"],
             st["wouldSaveSeconds"] or 0))
    arms = ab["arms"]
    keys = [("p1LowFps", "1% low"), ("p01LowFps", "0.1% low"), ("maxFrameMs", "worst ms"),
            ("avgFps", "avg"), ("medianFps", "median"), ("median2Fps", "median2"),
            ("gpuP99Ms", "GPU p99 ms"), ("gpuCadenceP99Ms", "GPU cad p99"), ("gpuMedianMs", "GPU med ms")]
    print("   %-4s %7s " % ("arm", "frames") + " ".join("%11s" % k[1] for k in keys))
    for a in ("A", "B"):
        r = arms[a]
        print("   %-4s %7d " % (a, r["frames"]) + " ".join(
            ("%11.3f" % r[k] if "Ms" in k else "%11.1f" % r[k]) if r.get(k) is not None else "%11s" % "-" for k, _ in keys))
    print("   Smoothness takes priority: a median gain cannot qualify a change with worse lows.")
    print("   CALL/primary/secondary below retain the run's historical statistics; they are not release approval.")
    print("   effects (B vs A; decision interval: cluster bootstrap SE x t[pairs-1]%s):" % (
        ", repeated CIs at alpha / looks (stop on)" if st["enabled"] else ", 95%"))
    print("     %-22s %9s %18s %8s %8s %5s %14s %6s" % ("metric", "change", "CI", "p", "Holm p", "call", "sign pairs", "icc"))
    if "pairP99" in ab["effects"]:
        effs = {m: ab["effects"][m] for m in ALL_METRICS if ab["effects"].get(m) and ab["effects"][m].get("logRatio") is not None}
        rows = [(m, e["logRatio"], e["ci"], e.get("p"), e.get("holmP"), e.get("call"), e["signPairs"], e.get("icc")) for m, e in effs.items()]
    else:
        # Older result (no pair metrics / p-values in the JSON): analysed here from the per-frame arrays or the cells.
        an = analyse(ab)
        rows = [(m, e["est"], (e["lo"], e["hi"]), e["p"], e.get("holmP"), e.get("call"), None, None) for m, e in an.items()]
        print("     (older result: effects analysed here%s)" % (" from the per-frame arrays" if "frames" in ab else " from the JSON cells"))
    order = {m: i for i, m in enumerate(REPORT_ORDER)}
    for m, est, ci, pv, hp, call, sp, icc in sorted(rows, key=lambda r: order[r[0]]):
        print("     %-22s %+8.2f%% %18s %8s %8s %5s %14s %6s" % (
            LABELS[m] + ("" if m in PRIMARY else " (2nd)"), to_pct(est, m), fmt_ci(m, *ci), "%.4f" % pv if pv is not None else "-",
            "%.4f" % hp if hp is not None and not math.isnan(hp) else "-", ("CALL" if call else "no") if m in PRIMARY else "",
            "%d/%d %.3f" % (sp["plus"], sp["minus"], sp["p"]) if sp else "-", "%.2f" % icc if icc is not None else "-"))
    if args.get("bins"):
        cells = cells_from_json(ab)
        print("   per bin (pooled over blocks): median2 ms / p99 ms / GPU median ms, A vs B")
        for b in range(ab["bins"]):
            def avg(arm, m):
                v = [blk["cells"][b][m] for blk in cells if blk["arms"][b] == arm and not math.isnan(blk["cells"][b][m])]
                return sum(v) / len(v) / 1e6 if v else float("nan")
            lo = b * 360 / ab["bins"] if ab["binKind"] == "yaw" else b * ab["blockSeconds"] / ab["bins"]
            print("     %6.1f%s  %.3f/%.3f  %.3f/%.3f  %.3f/%.3f" % (lo, "deg" if ab["binKind"] == "yaw" else "s",
                                                                 avg("A", "median2"), avg("B", "median2"), avg("A", "p99"), avg("B", "p99"),
                                                                 avg("A", "gpuMedian"), avg("B", "gpuMedian")))
    if args.get("blocks"):
        print("   blocks: index arm frames kept seconds presented-share(A/B) cellArms")
        for b in ab["blocks"]:
            pr, pf = b.get("presented"), b.get("presentFrames")
            share = "/".join("%.3f" % (p / f) if f else "-" for p, f in zip(pr, pf)) if pr else "-"
            print("     %2d %s %6d %6d %.3f %11s %s" % (b["index"], b["arm"], b["frames"], b["kept"], b["seconds"], share, b.get("cellArms", "")))
    if args.get("recompute"):
        if "frames" not in ab:
            print("   (no per-frame arrays: run with abab.frames=true)")
        else:
            an = analyse(ab, args.get("discard"), recompute=True)
            print("   recomputed in Python%s:" % ("" if args.get("discard") is None else " with discard %d" % args["discard"]))
            for m in REPORT_ORDER:
                e = an.get(m)
                if e:
                    print("     %-22s %+8.2f%% %18s  t on pair means %s  p %.4f%s" % (
                        LABELS[m], to_pct(e["est"], m), fmt_ci(m, e["lo"], e["hi"]), fmt_ci(m, e["tLo"], e["tHi"]), e["p"],
                        "  Holm %.4f %s" % (e["holmP"], "CALL" if e["call"] else "no") if m in PRIMARY else ""))
    return d


def looks(ab, metrics, alpha=0.05, min_pairs=3, margin_pct=0.5):
    """Replays the sequential rule offline (any stop metrics): looks only at balanced pair counts (AbabStats.lookPairs),
    each metric at alpha / (looks x metrics); returns (lines, first pair count where every metric is decided or None)."""
    if "frames" in ab:
        blocks, tails = cells_from_frames(ab), pair_tails_from_frames(ab)
    else:
        blocks, tails = cells_from_json(ab), pair_tails_from_json(ab)
    total = len(blocks) // 2
    planned = ab["blocksPlanned"] // 2
    schedule = look_pairs(min_pairs, planned, ab.get("segments", 1), ab.get("order", "abba") != "abab")
    k = max(1, len(schedule))
    a = alpha / k
    secondary = sum(1 for m in metrics if m not in PRIMARY)
    margin = math.log1p(margin_pct / 100)
    lines, stop = [], None
    for pairs in [p for p in schedule if p <= total]:
        parts, decided = [], True
        effs = {m: effect(ratios(blocks, tails, m, pairs), alpha=a) for m in set(metrics) | set(PRIMARY)
                if m not in PAIR_METRICS or tails is not None}
        fam = [m for m in PRIMARY if m in effs and "p" in effs[m]]
        hp = dict(zip(fam, holm([min(1.0, effs[m]["p"] * k) for m in fam])))
        for m in metrics:
            e = effs.get(m, {})
            if "lo" not in e:
                decided = False
                continue
            padj = hp[m] if m in PRIMARY else min(1.0, e["p"] * k * max(1, secondary))
            if padj < alpha:
                dec = "effect"
            elif (e["hi"] - e["lo"]) / 2 <= margin:
                dec = "within-margin"
            else:
                dec = "undecided"
                decided = False
            parts.append("%s %+.2f%% %s %s" % (m, to_pct(e["est"], m), fmt_ci(m, e["lo"], e["hi"]), dec))
        lines.append("     after %d pairs (%.0f s): %s%s" % (pairs, pairs * 2 * ab["blockSeconds"], "; ".join(parts),
                                                          "  -> STOP" if decided and stop is None else ""))
        if decided and stop is None:
            stop = pairs
    return lines, stop


TABLE_METRICS = ("pairP99", "pairP999", "worst", "p99", "mean", "median2", "gpuP99", "gpuMedian")


def table_row(d, discard=None):
    """Markdown row: label, segments, blocks, then per metric 'change [CI]' (FPS for frame-time metrics, time for GPU).
    Bold: a Holm call on a primary metric. Degree sign: a secondary metric whose own interval excludes 0."""
    ab = d["abab"]
    an = analyse(ab, discard)
    cells = []
    for m in TABLE_METRICS:
        if m == "worst":
            # Saved whole-arm maxima: descriptive, not a paired effect or significance claim.
            a, b = (ab["arms"][arm].get("maxFrameMs") for arm in ("A", "B"))
            cells.append("%.2f → %.2f" % (a, b) if a is not None and b is not None else "-")
            continue
        e = an.get(m)
        if not e:
            cells.append("-")
            continue
        txt = "%+.2f %s" % (to_pct(e["est"], m), fmt_ci(m, e["lo"], e["hi"]))
        if m in PRIMARY and e.get("call"):
            txt = "**%+.2f** %s" % (to_pct(e["est"], m), fmt_ci(m, e["lo"], e["hi"]))
        elif m not in PRIMARY and (e["lo"] > 0 or e["hi"] < 0):
            txt += " °"
        cells.append(txt)
    return "| %s | %d | %d/%d | %s |" % (d["label"], ab.get("segments", 1), ab["blocksRun"], ab["blocksPlanned"], " | ".join(cells))


def combine(paths, metrics=None):
    """Pools launches of one comparison per metric. Per launch: estimate and SE (from its own analysis). Pooled:
    inverse-variance estimate with its interval (assumes the launches measure the same effect), Cochran's Q and I^2
    (heterogeneity: Q p < 0.05 means the launches disagree beyond their own intervals), the DerSimonian-Laird random-
    effects estimate and interval (the between-launch variance tau^2 added to every launch's; equal to the fixed one when
    Q <= k - 1), and from 3 launches on the t interval over the launch estimates (k - 1 df)."""
    runs = []
    for p in paths:
        d = json.load(open(p))
        if d.get("abab"):
            runs.append((d["label"], analyse(d["abab"], level=0.05)))
    print("combined launches: " + ", ".join(r[0] for r in runs))
    for m in metrics or REPORT_ORDER:
        est = [(lab, an[m]) for lab, an in runs if m in an and an[m].get("se")]
        if len(est) < 2:
            continue
        ys = [e["est"] for _, e in est]
        ws = [1 / e["se"] ** 2 for _, e in est]
        k = len(ys)
        yw = sum(w * y for w, y in zip(ws, ys)) / sum(ws)
        sew = math.sqrt(1 / sum(ws))
        q = sum(w * (y - yw) ** 2 for w, y in zip(ws, ys))
        pq_ = 1 - chi2_cdf(q, k - 1)
        i2 = max(0.0, (q - (k - 1)) / q) if q > 0 else 0.0
        c = sum(ws) - sum(w * w for w in ws) / sum(ws)
        tau2 = max(0.0, (q - (k - 1)) / c) if c > 0 else 0.0
        wr = [1 / (e["se"] ** 2 + tau2) for _, e in est]
        yr = sum(w * y for w, y in zip(wr, ys)) / sum(wr)
        ser = math.sqrt(1 / sum(wr))
        mean = sum(ys) / k
        sd = math.sqrt(sum((y - mean) ** 2 for y in ys) / (k - 1))
        tq = t_quantile(0.975, k - 1)
        per = "  ".join("%s %+.2f %s" % (lab, to_pct(e["est"], m), fmt_ci(m, e["lo"], e["hi"])) for lab, e in est)
        over = (" | over launches %+.2f%% %s (t, %d df)" % (to_pct(mean, m), fmt_ci(m, mean - tq * sd / math.sqrt(k), mean + tq * sd / math.sqrt(k)), k - 1)
                if k >= 3 else "")
        print("  %-22s %s" % (LABELS[m], per))
        print("  %-22s fixed %+.2f%% %s | Q %.2f on %d df, p %.3f, I^2 %.0f%% | random %+.2f%% %s (tau %.2f%%)%s" % (
            "", to_pct(yw, m), fmt_ci(m, yw - 1.96 * sew, yw + 1.96 * sew), q, k - 1, pq_, i2 * 100, to_pct(yr, m),
            fmt_ci(m, yr - 1.96 * ser, yr + 1.96 * ser), math.sqrt(tau2) * 100, over))


def chi2_cdf(x, k):
    """Chi-square CDF (regularized lower incomplete gamma by its series / continued fraction)."""
    if x <= 0:
        return 0.0
    a = k / 2.0
    xx = x / 2.0
    if xx < a + 1:
        term = s = 1.0 / a
        n = a
        for _ in range(500):
            n += 1
            term *= xx / n
            s += term
            if abs(term) < abs(s) * 1e-15:
                break
        return s * math.exp(-xx + a * math.log(xx) - math.lgamma(a))
    b = xx + 1 - a
    c = 1 / 1e-300
    dd = 1 / b
    h = dd
    for i in range(1, 500):
        an = -i * (i - a)
        b += 2
        dd = an * dd + b
        dd = 1e-300 if abs(dd) < 1e-300 else dd
        c = b + an / c
        c = 1e-300 if abs(c) < 1e-300 else c
        dd = 1 / dd
        de = dd * c
        h *= de
        if abs(de - 1) < 1e-15:
            break
    return 1 - math.exp(-xx + a * math.log(xx) - math.lgamma(a)) * h


def summary_line(d):
    ab = d["abab"]
    parts = []
    an = analyse(ab)
    for m in REPORT_ORDER:
        e = an.get(m)
        if e:
            parts.append("%s %+.2f%% %s%s" % (m, to_pct(e["est"], m), fmt_ci(m, e["lo"], e["hi"]), "*" if e.get("call") else ""))
    return "%-24s %2d/%2d blocks  %s" % (d["label"], ab["blocksRun"], ab["blocksPlanned"], "  ".join(parts))


def classic(title, pairs):
    print(title)
    keys = [("p1LowFps", "1% low"), ("p01LowFps", "0.1% low"), ("maxFrameMs", "worst ms"),
            ("avgFps", "avg"), ("medianFps", "median")]
    logs = {k: [] for k, _ in keys}
    for a, b in pairs:
        da, db = json.load(open("results/raw/%s.json" % a)), json.load(open("results/raw/%s.json" % b))
        parts = []
        for k, n in keys:
            if da.get(k) is None or db.get(k) is None:
                parts.append("%s unmeasured" % n)
                continue
            r = math.log(db[k] / da[k])
            logs[k].append(r)
            parts.append("%s %+.2f%%" % (n, (math.exp(r) - 1) * 100))
        print("  %-24s -> %-24s %s" % (a, b, " ".join(parts)))
    for k, n in keys:
        v = logs[k]
        if not v:
            print("  %-9s unmeasured" % n)
            continue
        m = sum(v) / len(v)
        if len(v) > 1:
            sd = math.sqrt(sum((x - m) ** 2 for x in v) / (len(v) - 1))
            t = t_quantile(0.975, len(v) - 1)
            ci = "[%+.2f, %+.2f]" % ((math.exp(m - t * sd / math.sqrt(len(v))) - 1) * 100, (math.exp(m + t * sd / math.sqrt(len(v))) - 1) * 100)
            half = (math.exp(t * sd / math.sqrt(len(v))) - 1) * 100
        else:
            ci, half = "-", float("nan")
        better = sum(1 for x in v if (x < 0 if k == "maxFrameMs" else x > 0))
        print("  %-9s mean %+.2f%%  95%% t-CI %s (half-width %.2f%%)  better %d/%d  sign p %.3f" % (
            n, (math.exp(m) - 1) * 100, ci, half, better, len(v), sign_p(sum(1 for x in v if x > 0), sum(1 for x in v if x < 0))))


# ---- self test --------------------------------------------------------------------------------------------------------

def selftest(paths):
    fails = 0

    def check(ok, what):
        nonlocal fails
        if not ok:
            fails += 1
            print("  FAIL:", what)

    for p, df, want in ((0.975, 1, 12.7062047), (0.975, 2, 4.30265273), (0.975, 5, 2.57058184), (0.975, 30, 2.04227246),
                        (0.995, 3, 5.84090931)):
        q = t_quantile(p, df)
        check(abs(q - want) < 1e-4 * want, "t_quantile(%s, %s) = %s" % (p, df, q))
    check(abs(sign_p(15, 5) - 0.041389465) < 1e-8, "sign 15/5")
    check(abs(sign_p(6, 0) - 0.03125) < 1e-12, "sign 6/0")
    check(pct([1, 2, 3, 4], 0.5) == 3, "nearest rank: round(1.5) = 2 -> 3 (Java Math.round)")
    h = holm([0.01, 0.04, 0.03, float("nan")])
    check(abs(h[0] - 0.03) < 1e-15 and abs(h[1] - 0.06) < 1e-15 and abs(h[2] - 0.06) < 1e-15 and math.isnan(h[3]), "holm %s" % h)
    check(look_pairs(3, 6, 1) == [4, 6] and look_pairs(3, 6, 24) == [3, 4, 5, 6] and look_pairs(3, 6, 8) == [3, 4, 5, 6]
          and look_pairs(3, 6, 1, abba=False) == [], "look schedule")
    check(abs(chi2_cdf(3.841458820694124, 1) - 0.95) < 1e-9 and abs(chi2_cdf(9.487729036781154, 4) - 0.95) < 1e-9, "chi2 cdf")
    e3 = effect([[1.0], [2.0], [3.0]], resamples=20000, seed=3)
    check(abs(e3["p"] - 2 * t_cdf(-e3["est"] / e3["se"], 2)) < 1e-12 and 0.04 < e3["p"] < 0.12, "p %s" % e3["p"])
    # Calibration of the decision interval on simulated data (fewer sims than the Java test: pure Python).
    rng = random.Random(5)
    cover = 0
    sims = 300
    for i in range(sims):
        lr = []
        for _ in range(6):
            u = rng.gauss(0, 0.004)
            lr.append([u + rng.gauss(0, 0.01) for _ in range(24)])
        e = effect(lr, resamples=500, seed=i)
        cover += e["lo"] <= 0 <= e["hi"]
    check(0.90 <= cover / sims <= 0.99, "python decision CI coverage %.3f" % (cover / sims))
    print("  python calibration: covers 0 in %.3f of %d simulated A/A runs" % (cover / sims, sims))
    for path in paths:
        d = json.load(open(path))
        ab = d.get("abab")
        if not ab or "frames" not in ab:
            print("  %s: no abab frames, skipped" % path)
            continue
        py = cells_from_frames(ab)
        jv = cells_from_json(ab)
        tj = pair_tails_from_json(ab)
        tp = pair_tails_from_frames(ab)
        if tj is not None:
            # The JSON writes pair tails with 6 significant digits (AbabStats.num): a few ns on 2 ms.
            tm = sum(1 for a, b in zip(tp, tj) for x, y in zip(a, b) for u, v in zip(x, y)
                     if math.isnan(u) != math.isnan(v) or (not math.isnan(u) and abs(u - v) > 1e-5 * abs(u) + 1.0))
            check(tm == 0, "%s: %d pair tails differ between Python and Java" % (path, tm))
        mism = 0
        for bp, bj in zip(py, jv):
            for cp, cj in zip(bp["cells"], bj["cells"]):
                for m in METRICS:
                    a, b = cp[m], cj[m]
                    if math.isnan(a) != math.isnan(b) or (not math.isnan(a) and abs(a - b) > 1.0):
                        mism += 1
        check(mism == 0, "%s: %d cell values differ between Python and Java" % (path, mism))
        stop_on = bool(ab["stop"].get("enabled"))
        lvl = ab["stop"]["lookAlpha"] if stop_on and "lookPairs" in ab["stop"] else 0.05
        for m in ALL_METRICS:
            ej = ab["effects"].get(m)
            if not ej or ej.get("logRatio") is None:
                continue
            ep = effect(ratios(py, tp, m), alpha=lvl)
            if ej.get("p") is not None and "p" in ep:
                check(abs(ep["p"] - ej["p"]) <= 0.1 * ej["p"] + 1e-6, "%s %s p %.6f (py) vs %.6f (java)" % (path, m, ep["p"], ej["p"]))
            check(abs(ep["est"] - ej["logRatio"]) <= 1e-5 * abs(ej["logRatio"]) + 1e-10, "%s %s estimate %s vs %s" % (path, m, ep["est"], ej["logRatio"]))
            if "lo" in ep and ej["ci"][0] is not None:
                hw_p, hw_j = (ep["hi"] - ep["lo"]) / 2, (ej["ci"][1] - ej["ci"][0]) / 2
                check(abs(hw_p - hw_j) <= 0.08 * hw_j + 1e-12, "%s %s half-width %.5f (py) vs %.5f (java)" % (path, m, hw_p, hw_j))
        print("  %s: cells and effects agree (%d blocks)" % (path, len(py)))
    print("abab.py selftest: %d failures" % fails)
    return fails


def main(argv):
    if argv and argv[0] == "--selftest":
        sys.exit(1 if selftest(argv[1:]) else 0)
    if argv and argv[0] == "--classic":
        classic(argv[1], [p.split(":") for p in argv[2:]])
        return
    if argv and argv[0] == "--combine":
        combine([a for a in argv[1:] if not a.startswith("--")])
        return
    args = {"bins": "--bins" in argv, "blocks": "--blocks" in argv, "recompute": "--recompute" in argv, "summary": "--summary" in argv}
    if "--table" in argv:
        print("| run | segments | blocks | " + " | ".join("worst ms A → B" if m == "worst" else LABELS[m] + " %" for m in TABLE_METRICS) + " |")
        print("|---|---|---|" + "---|" * len(TABLE_METRICS))
        disc = int(argv[argv.index("--discard") + 1]) if "--discard" in argv else None
        for i, p in enumerate(argv):
            if p.startswith("--") or (i > 0 and argv[i - 1] == "--discard"):
                continue
            d = json.load(open(p))
            if d.get("abab"):
                print(table_row(d, disc))
        return
    if "--discard" in argv:
        args["discard"] = int(argv[argv.index("--discard") + 1])
    if "--looks" in argv:
        args["looks"] = argv[argv.index("--looks") + 1].split(",")
    paths = [a for i, a in enumerate(argv) if not a.startswith("--") and not (i > 0 and argv[i - 1] in ("--discard", "--looks"))]
    runs = []
    for p in paths:
        if args["summary"]:
            d = json.load(open(p))
            if d.get("abab"):
                print(summary_line(d))
                runs.append(d)
        else:
            d = report(p, args)
            if d:
                runs.append(d)
        if args.get("looks") and runs and runs[-1].get("abab"):
            ab = runs[-1]["abab"]
            lines, stop = looks(ab, args["looks"], ab["stop"]["alpha"], ab["stop"]["minPairs"], ab["stop"]["marginPct"])
            print("   sequential rule replayed on %s:" % ",".join(args["looks"]))
            print("\n".join(lines))
            print("     -> %s" % ("stop after %d pairs: %.0f of %.0f s measured, %.0f s saved" % (
                stop, stop * 2 * ab["blockSeconds"], ab["secondsPlanned"], ab["secondsPlanned"] - stop * 2 * ab["blockSeconds"])
                if stop else "no stop: all %d pairs needed" % (len(ab["blocks"]) // 2)))
    if args["summary"] and len(runs) > 1:
        ans = [analyse(r["abab"]) for r in runs]
        for m in REPORT_ORDER:
            cov = [(a[m]["lo"], a[m]["hi"]) for a in ans if m in a]
            print("  %-9s decision CI covers 0 in %d of %d runs" % (m, sum(1 for lo, hi in cov if lo <= 0 <= hi), len(cov)))
        print("  runs with a Holm call on a primary metric: %d of %d" % (sum(1 for a in ans if any(a[m].get("call") for m in PRIMARY if m in a)), len(ans)))


if __name__ == "__main__":
    main(sys.argv[1:])
