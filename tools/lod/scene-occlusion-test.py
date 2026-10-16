#!/usr/bin/env python3
"""Build/run the tiny conservative-occlusion fixture; never load a full scene.

Default: exclusive timing GPU lock, fail-closed Prism guard, 58-second test cap.
--compile-only: native compile without running the executable or compiling MSL.
Every invocation keeps sources, hashes, commands and logs in a private build dir.
"""
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]
TIMEOUT_SECONDS = 58


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def gpu_guard():
    if os.environ.get("MC_GPU_LOCK_HELD") != "timing":
        raise RuntimeError("Exclusive timing GPU lock required")
    lock = Path(os.path.realpath(os.environ.get("MC_GPU_LOCK", "/tmp/mc-gpu.lock")))
    with lock.open("a+") as handle:
        try:
            fcntl.flock(handle, fcntl.LOCK_SH | fcntl.LOCK_NB)
        except BlockingIOError:
            pass
        else:
            fcntl.flock(handle, fcntl.LOCK_UN)
            raise RuntimeError("Exclusive GPU lock could not be verified")
    guard = subprocess.run(["pgrep", "-f", "[P]rismLauncher/instances/.*/bin/java "],
                           capture_output=True, text=True, timeout=5)
    if guard.returncode != 1:
        raise RuntimeError("Refusing GPU test: Prism game running or process guard failed")
    return {"lane": "timing", "sharedProbeBlocked": True, "prismPgrepExitCode": 1}


def run_logged(command, log, env):
    with log.open("w") as output:
        result = subprocess.run(command, cwd=ROOT, env=env, stdout=output,
                                stderr=subprocess.STDOUT, timeout=TIMEOUT_SECONDS)
    print(log.read_text(), end="", flush=True)
    if result.returncode:
        raise RuntimeError(f"Command exited {result.returncode}; see {log}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--compile-only", action="store_true", help="Compile native fixture; no GPU execution")
    parser.add_argument("--locked", action="store_true", help=argparse.SUPPRESS)
    args = parser.parse_args()
    if not args.compile_only and not args.locked:
        os.execv(sys.executable, [sys.executable, str(ROOT / "tools/gpulock.py"), "timing",
                                 "--label", "lod-scene-occlusion-tiny-proof", "--",
                                 sys.executable, str(Path(__file__).resolve()), "--locked"])
    parent = ROOT / "build/lod-scene-occlusion"
    parent.mkdir(parents=True, exist_ok=True)
    out = Path(tempfile.mkdtemp(prefix="run-", dir=parent))
    for name in ("source", "tmp", "module-cache"):
        (out / name).mkdir()
    print("Tiny occlusion fixture:", out, flush=True)
    files = [ROOT / "native/tests" / name for name in
             ("lod_scene_occlusion_test.mm", "lod_scene_occlusion.h", "lod_scene_data.h")]
    files.append(Path(__file__).resolve())
    for source in files:
        shutil.copy2(source, out / "source" / source.name)
    receipt = {"schema": "lod-scene-occlusion-test-1", "status": "started",
               "compileOnly": args.compile_only, "gpuRun": False,
               "timeoutSeconds": TIMEOUT_SECONDS,
               "sourceSha256": {str(f.relative_to(ROOT)): sha(out / "source" / f.name) for f in files}}
    receipt_path = out / "receipt.json"
    env = dict(os.environ, TMPDIR=str(out / "tmp") + "/")
    try:
        if not args.compile_only:
            receipt["lock"] = gpu_guard()
        command = ["xcrun", "--sdk", "macosx", "clang++", "-std=c++17", "-fobjc-arc",
                   "-O2", "-Wall", "-Wextra", "-Werror", "-Wno-deprecated-declarations",
                   "-fmodules-cache-path=" + str(out / "module-cache"),
                   "-framework", "Foundation", "-framework", "Metal",
                   "-framework", "CoreGraphics", "-framework", "ImageIO", "-lz",
                   str(out / "source/lod_scene_occlusion_test.mm"), "-o", str(out / "test")]
        receipt["compileCommand"] = command
        run_logged(command, out / "compile.log", env)
        receipt["executableSha256"] = sha(out / "test")
        if args.compile_only:
            receipt["status"] = "compiled; GPU not run"
        else:
            receipt["lockBeforeRun"] = gpu_guard()
            command = [str(out / "test"), str(out / "result.json")]
            receipt.update(runCommand=command, gpuRun=True)
            run_logged(command, out / "run.log", env)
            result = json.loads((out / "result.json").read_text())
            if result.get("passed") is not True or result.get("falseOcclusions") != 0:
                raise RuntimeError("Fixture did not report a passing result")
            receipt.update(status="passed", resultSha256=sha(out / "result.json"))
    except (OSError, RuntimeError, ValueError, subprocess.TimeoutExpired) as error:
        receipt.update(status="failed", error=str(error))
        print("FAIL:", error, file=sys.stderr)
        return 1
    finally:
        receipt_path.write_text(json.dumps(receipt, indent=2, sort_keys=True) + "\n")
        print("Receipt:", receipt_path, flush=True)
    print(receipt["status"], flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
