#!/usr/bin/env python3
"""CPU-only targeted age/order and atlas-family checks against an existing client jar.

No client/native build, game entry point, Metal device or GPU. Atlas tests use
LWJGL CPU memory only for the actual shader include fingerprint guard. All class output
and optional mutation copies live in a temporary directory, never shared build/.
Use --mutations to verify focused age/order/eligibility regressions are detected.
"""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "client/src/net/minecraft/client/renderer/chunk/LodFarConsumerProbe.java"
TEST = ROOT / "tools/LodFarConsumerAgeTest.java"
ATLAS_TEST = ROOT / "tools/LodFarConsumerAtlasTest.java"
MATERIAL_SOURCE = ROOT / "client/src/com/mojang/renderpearl/backend/metal/MetalLodMaterialInputs.java"
PACKAGE = "net.minecraft.client.renderer.chunk.LodFarConsumerAgeTest"
ATLAS_PACKAGE = "net.minecraft.client.renderer.chunk.LodFarConsumerAtlasTest"


def wiring(source):
    # Reflection tests exercise the real predicates; these checks tie them to
    # GPU-bearing prepare() without initializing a renderer or faking a GPU.
    start = source.index("    void prepare(")
    end = source.index("    private static boolean canShade(", start)
    prepare = source[start:end]
    eligibility = "boolean shade=canShade(source,current,differences);"
    age = "if(!fresh(newest,now,current.source.frame(),shade)) {stale++;return;}"
    older = "fresh(older,now,current.source.frame(),false)"
    assert prepare.count(eligibility) == prepare.count(age) == prepare.count(older) == 1, "prepare age/eligibility wiring"
    assert prepare.index("int differences=source.differences(current);") < prepare.index(eligibility) < prepare.index(age), "age exemption must follow proof"
    assert prepare.count("fresh(") == 2, "unexpected extra/unconditional age gate"
    family_gate = "if(SHADE_ATLAS_FAMILY && !source.sameAtlasFamily(current))"
    assert prepare.count(family_gate) == 1 and prepare.index(family_gate) < prepare.index(eligibility), "missing token must decline before encoding/copy"
    capture = source[source.index("        static Scene capture("):source.index("        private boolean sameAtlasFamily(")]
    assert "SHADE_ATLAS_FAMILY ? MetalLodMaterialInputs.captureStaticAtlas(atlas) : null" in capture, "capture must validate the actual current atlas view"
    assert "objects.toArray(),values,atlasToken)" in capture, "source/current Scene must retain their own captured proof"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mutations", action="store_true")
    args = parser.parse_args()
    jdk = Path(os.environ.get("LOD_AGE_JAVA_HOME", ROOT / "mc/runtime/java-runtime-epsilon/jre.bundle/Contents/Home")).resolve()
    jar = Path(os.environ.get("LOD_AGE_CLIENT_JAR", ROOT / "build/client.jar")).resolve()
    if not jar.is_file() or not (jdk / "bin/javac").is_file():
        parser.error("existing client jar and JDK required; set LOD_AGE_CLIENT_JAR / LOD_AGE_JAVA_HOME")
    libraries = sorted((ROOT / "mc/libraries").rglob("*.jar"))
    cp = os.pathsep.join(map(str, [jar, *libraries]))
    original = SOURCE.read_text()
    wiring(original)
    with tempfile.TemporaryDirectory(prefix="lod-far-consumer-age-") as temp:
        out = Path(temp)
        classes = out / "classes"
        classes.mkdir()
        compile_base = [str(jdk / "bin/javac"), "-J-Xmx128m", "-J-XX:ActiveProcessorCount=2", "--release", "25",
                        "-proc:none", "-implicit:none", "-sourcepath", str(out / "empty"), "-cp", cp, "-d", str(classes)]
        subprocess.run([*compile_base, str(SOURCE), str(SOURCE.with_name("LodFarCamera.java")),
                        str(SOURCE.with_name("LodFarGuard.java")), str(MATERIAL_SOURCE), str(TEST), str(ATLAS_TEST)], check=True, cwd=out, timeout=45)

        def run(async_enabled, shade_enabled, classpath):
            return subprocess.run([str(jdk / "bin/java"), "-Xmx96m", "-XX:ActiveProcessorCount=2", "-ea",
                "-Djava.awt.headless=true", "-Djoml.nounsafe=true", "-Djava.library.path=" + str(out),
                "-Dmc.lod.async=" + str(async_enabled).lower(), "-Dmc.lod.async.shade=" + str(shade_enabled).lower(),
                "-cp", classpath, PACKAGE, str(async_enabled and shade_enabled).lower()],
                cwd=out, capture_output=True, text=True, timeout=15)

        runtime_cp = os.pathsep.join([str(classes), cp])
        for flags in [(False, False), (False, True), (True, False), (True, True)]:
            result = run(*flags, runtime_cp)
            if result.returncode:
                raise RuntimeError(result.stdout + result.stderr)
            print(result.stdout.strip(), flush=True)
        def atlas_run(classpath, async_enabled=True, shade_enabled=True, family=True, materials=True):
            command = [str(jdk / "bin/java"), "-Xmx96m", "-XX:ActiveProcessorCount=2", "-ea", "--enable-native-access=ALL-UNNAMED",
                       "-Djava.awt.headless=true", "-Djoml.nounsafe=true", "-Dorg.lwjgl.system.SharedLibraryExtractPath=" + str(out / "lwjgl")]
            for key, value in [("mc.lod.async", async_enabled), ("mc.lod.async.shade", shade_enabled),
                               ("mc.lod.async.shadeAtlasFamily", family), ("mc.lod.materialInputs", materials)]:
                if value is not None:
                    command.append("-D" + key + "=" + str(value).lower())
            return subprocess.run([*command, "-cp", classpath, ATLAS_PACKAGE], cwd=out, capture_output=True, text=True, timeout=20)

        # Unset verifies the new feature remains default-off. Disabled material
        # publication must fail closed, without weakening the original shade path.
        for flags in [(True, True, None, True), (True, True, False, True), (True, True, True, False),
                      (False, True, True, True), (True, False, True, True), (True, True, True, True)]:
            result = atlas_run(runtime_cp, *flags)
            if result.returncode:
                raise RuntimeError(result.stdout + result.stderr)
            print(result.stdout.strip(), flush=True)
        if not args.mutations:
            return
        mutations = [
            ("zero-time", "time>0 &&", "time>=0 &&"),
            ("future-time", "time<=now &&", "true &&"),
            ("zero-frame", "sourceFrame>0 &&", "sourceFrame>=0 &&"),
            ("future-frame", "sourceFrame<=frame", "true"),
            ("exclusive-100ms", "now-time<=MAX_AGE_NS", "now-time<MAX_AGE_NS"),
            ("no-exemption", "exactShading || now-time", "false || now-time"),
            ("unbounded-copy", "exactShading || now-time", "true || now-time"),
            ("no-feature-gate", "return MetalFarEngine.SHADE_REQUESTED &&", "return true &&"),
            ("resource-exemption", "(differences&~35)==0", "(differences&~39)==0"),
            ("deny-fog", "(differences&~35)==0", "(differences&~3)==0"),
            ("no-camera-proof", "LodFarGuard.exactShadingCrop(source.guardedCamera,source.centralCamera,current.centralCamera)", "true"),
            ("newest-copy-policy", "fresh(newest,now,current.source.frame(),shade)", "fresh(newest,now,current.source.frame(),false)"),
            ("older-exemption", "fresh(older,now,current.source.frame(),false)", "fresh(older,now,current.source.frame(),true)"),
            ("atlas-family-equality", "atlasToken.family()==other.atlasToken.family()", "true"),
            ("atlas-sampler-identity", "sameAtlas && i==0", "sameAtlas && (i==0 || i==2)"),
            ("atlas-current-token", "MetalLodMaterialInputs.captureStaticAtlas(atlas)", "null"),
        ]
        killed = 0
        for name, before, after in mutations:
            assert original.count(before) == 1, "mutation anchor changed: " + name
            mutant = original.replace(before, after)
            try:
                wiring(mutant)
            except AssertionError:
                killed += 1
                print("mutation rejected by integration wiring: " + name, flush=True)
                continue
            directory = out / name
            directory.mkdir()
            mutated_source = directory / SOURCE.name
            mutated_source.write_text(mutant)
            # Compile the actual modified production class; predicates are not
            # duplicated in a test surrogate. Baseline camera/test classes follow.
            command = compile_base.copy()
            command[command.index("-d") + 1] = str(directory)
            command[command.index("-cp") + 1] = runtime_cp
            subprocess.run([*command, str(mutated_source)], check=True, cwd=out, timeout=45)
            mutant_cp = os.pathsep.join([str(directory), runtime_cp])
            flags = (False, False) if name == "no-feature-gate" else (True, True)
            result = atlas_run(mutant_cp) if name.startswith("atlas-") else run(*flags, mutant_cp)
            if result.returncode == 0 or "AssertionError" not in result.stderr:
                raise AssertionError("mutation survived or failed outside an assertion: " + name + "\n" + result.stdout + result.stderr)
            killed += 1
            print("mutation rejected by CPU assertions: " + name, flush=True)
        print(f"Mutation checks: {killed}/{len(mutations)} rejected", flush=True)


if __name__ == "__main__":
    main()
