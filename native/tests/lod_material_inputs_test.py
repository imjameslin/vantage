#!/usr/bin/env python3
"""CPU-only isolated compilation/test; uses the existing client.jar, never builds/starts the client or GPU.
All compiler products, LWJGL extraction and logs go into a temporary directory.
"""
from pathlib import Path
import os
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
JH = ROOT / "mc/runtime/java-runtime-epsilon/jre.bundle/Contents/Home"
jars = [ROOT / "build/client.jar", *[p for p in sorted((ROOT / "mc/libraries").rglob("*.jar"))
        if not p.name.endswith(("natives-macos.jar", "osx-x86_64.jar"))]]
cp = os.pathsep.join(map(str, jars))
def run(command, cwd):
    result = subprocess.run(command, cwd=cwd, timeout=60)
    if result.returncode:
        raise SystemExit(result.returncode)

sources = [ROOT / "client/src" / p for p in (
    "com/mojang/renderpearl/backend/metal/MetalLodMaterialInputs.java",
    "net/minecraft/client/renderer/Lightmap.java",
    "net/minecraft/client/renderer/texture/TextureAtlas.java",
)]
sources.append(ROOT / "native/tests/MetalLodMaterialInputsTest.java")
with tempfile.TemporaryDirectory(prefix="lod-material-inputs-") as tmp:
    out = Path(tmp)
    print("Compile only logical snapshot component, its two hook classes and CPU test", flush=True)
    run([str(JH / "bin/javac"), "--release", "25", "-proc:none", "-implicit:none",
         "-cp", cp, "-d", str(out), *map(str, sources)], out)
    for enabled in (False, True):
        print(f"Run CPU tests, materialInputs={enabled}", flush=True)
        run([str(JH / "bin/java"), "--enable-native-access=ALL-UNNAMED", "-ea",
                        *(["-Dmc.lod.materialInputs=true"] if enabled else []),
                        f"-Dorg.lwjgl.system.SharedLibraryExtractPath={out / 'lwjgl'}",
                        "-cp", str(out) + os.pathsep + cp,
                        "com.mojang.renderpearl.backend.metal.MetalLodMaterialInputsTest", str(ROOT)],
                       out)
