#!/usr/bin/env python3
"""Summarize completed far-image timing receipts; never interpret them as FPS.

Usage: python3 tools/lod/async-timing.py GAME.log > build/timing-evidence.json
CPU intervals and the driver GPU span use separate clocks. Their absolute times
are never subtracted. A GPU span includes waits/contention, not exclusive work.
"""
import hashlib
import json
import math
from pathlib import Path
import re
import statistics
import sys


def summary(values):
    return {"count": len(values), "min": min(values),
            "median": statistics.median(values), "max": max(values)} if values else None


def read_receipts(path):
    raw = path.read_bytes()
    text = raw.decode("utf-8", errors="replace")
    timings, images = {}, {}
    for line in text.splitlines():
        if "[lod] async producer timing:" in line:
            fields = dict(re.findall(r"(\w+)=([^\s;]+)", line))
            row = {key: int(fields[key]) for key in ("sourceFrame", "sequence", "flags")}
            for key in ("captureToReserveMs", "encodeMs", "commitMs", "submittedToCallbackMs", "gpuSpanMs"):
                value = float(fields[key])
                row[key] = value if math.isfinite(value) and value >= 0 else None
            if row["sourceFrame"] in timings:
                raise ValueError("Duplicate timing source frame; use one bounded game run")
            timings[row["sourceFrame"]] = row
        elif "[lod] async producer probe COMPLETE:" in line:
            m = re.search(r"(\d+)x(\d+), (\d+) inputs, source frame (\d+), age ([0-9.]+) ms, "
                          r"main ticket (\d+), refresh (\d+)/(\d+), worker ([0-9.]+) ms", line)
            if not m:
                raise ValueError("Unrecognized completed-image receipt")
            width, height, inputs, frame, age, ticket, refresh, total, worker = m.groups()
            frame = int(frame)
            if frame in images:
                raise ValueError("Duplicate completed source frame")
            images[frame] = {"width": int(width), "height": int(height), "inputs": int(inputs),
                             "sourceFrame": frame, "ageMs": float(age), "mainTicket": int(ticket),
                             "refresh": int(refresh), "total": int(total), "workerMs": float(worker)}
    if not timings or timings.keys() != images.keys():
        raise ValueError("Missing/unpaired timing and completed-image receipts")
    rows = []
    for frame, timing in timings.items():
        if not timing["flags"] & 1 or any(timing[k] is None for k in
                ("captureToReserveMs", "encodeMs", "commitMs", "submittedToCallbackMs")):
            raise ValueError("Invalid CPU timing receipt")
        if bool(timing["flags"] & 2) != (timing["gpuSpanMs"] is not None):
            raise ValueError("GPU validity flag/span mismatch")
        rows.append({**images[frame], **timing})
    keys = ("ageMs", "workerMs", "captureToReserveMs", "encodeMs", "commitMs",
            "submittedToCallbackMs", "gpuSpanMs")
    return {"log": str(path), "logSha256": hashlib.sha256(raw).hexdigest(),
            "fpsEvidence": False, "qualityEvidence": False, "refreshes": rows,
            "allImagesMs": {k: summary([r[k] for r in rows if r[k] is not None]) for k in keys},
            "afterFirstImageMs": {k: summary([r[k] for r in rows if r["refresh"] > 1 and r[k] is not None]) for k in keys},
            "limitations": ["GPU span includes waits and contention; it is not exclusive GPU work.",
                            "No absolute CPU/GPU clock subtraction, FPS estimate or quality verdict.",
                            "Source age includes capture, submission, GPU execution and acquisition."]}


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    print(json.dumps(read_receipts(Path(sys.argv[1])), indent=2, allow_nan=False))
