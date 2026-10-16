#!/usr/bin/env python3
"""Targeted compile + isolated CPU contract tests; no game/native build or GPU access.

--compile-only compiles against the real existing client.jar/current program helpers,
then compiles the contract fixture without running Java. The fixture replaces only
capture dependencies in a temporary classpath to exercise ownership failures safely.
It does not test native shader validation or MetalLodReuseInputs' buffer replay logic.
"""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
JDK = ROOT / "mc/runtime/java-runtime-epsilon/jre.bundle/Contents/Home"
BACKEND = "com/mojang/renderpearl/backend/metal"
SOURCE = ROOT / "client/src" / BACKEND / "MetalFarDrawInputs.java"

STUBS = {
    "org/jspecify/annotations/Nullable.java": """
package org.jspecify.annotations;
@java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE_USE)
public @interface Nullable {}
""",
    "com/mojang/renderpearl/api/buffers/GpuBuffer.java": """
package com.mojang.renderpearl.api.buffers;
public class GpuBuffer {
    public byte[] bytes;
    public boolean closed, available = true;
    public boolean isClosed() { return closed; }
    public GpuBufferSlice slice() { return new GpuBufferSlice(this, 0, bytes.length); }
}
""",
    "com/mojang/renderpearl/api/buffers/GpuBufferSlice.java": """
package com.mojang.renderpearl.api.buffers;
public record GpuBufferSlice(GpuBuffer buffer, long offset, long length) {}
""",
    "com/mojang/blaze3d/systems/RenderSystem.java": """
package com.mojang.blaze3d.systems;
import com.mojang.renderpearl.api.buffers.*;
public final class RenderSystem {
    public static boolean renderThread = true;
    public static GpuBufferSlice projection, fog;
    public static GpuBuffer globals;
    public static void assertOnRenderThread() {
        if (!renderThread) throw new IllegalStateException("render state read on worker");
    }
    public static GpuBufferSlice getProjectionMatrixBuffer() { assertOnRenderThread(); return projection; }
    public static GpuBufferSlice getShaderFog() { assertOnRenderThread(); return fog; }
    public static GpuBuffer getGlobalSettingsUniform() { assertOnRenderThread(); return globals; }
}
""",
    f"{BACKEND}/MetalFarDrawInputsTest.java": r"""
package com.mojang.renderpearl.backend.metal;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.*;
import java.util.*;

public final class MetalFarDrawInputsTest {
    static final MetalDevice DEVICE = new MetalDevice();
    static MetalBuffer terrain;
    static int checks;
    static void check(boolean ok, String why) {
        checks++;
        if (!ok) throw new AssertionError(why);
    }
    static void reset() {
        RenderSystem.renderThread = true;
        DEVICE.closed = false; DEVICE.handle = 1;
        TerrainShaderVariants.builtin = true;
        MetalFarPrograms.REQUESTED = true;
        MetalFarPrograms.requests.clear(); MetalFarPrograms.retains.clear(); MetalFarPrograms.releases.clear();
        MetalFarPrograms.failRequest = MetalFarPrograms.failRetain = MetalFarPrograms.zeroRetain = MetalFarPrograms.failRelease = 0;
        RenderSystem.projection = new GpuBufferSlice(new MetalBuffer(DEVICE, 80), 8, 64);
        RenderSystem.globals = new MetalBuffer(DEVICE, 48);
        RenderSystem.fog = new MetalBuffer(DEVICE, 48).slice();
        terrain = new MetalBuffer(DEVICE, 96);
    }
    static MetalFarDrawInputs capture(MetalRenderPipeline... pipelines) {
        int[] codes = new int[pipelines.length];
        for (int i=0; i<codes.length; i++) codes[i] = i == 0 ? 0 : 1;
        return MetalFarDrawInputs.capture(Arrays.asList(pipelines), codes, new GpuBufferSlice(terrain, 8, 80));
    }
    static void declined(MetalRenderPipeline p, String why) {
        check(capture(p) == null, why);
        check(MetalFarPrograms.requests.isEmpty(), "reject before program capture: " + why);
    }
    static void expectFailure(Runnable action, String why) {
        try { action.run(); } catch (IllegalStateException expected) { checks++; return; }
        throw new AssertionError(why);
    }
    static void exactAndImmutable() {
        reset();
        var p = new MetalRenderPipeline(DEVICE, 101);
        var compact = new MetalRenderPipeline(DEVICE, 808);
        compact.uniformStages[1] = compact.uniformStages[2] = 0;
        int[] codes = {0, 8, 0};
        var input = MetalFarDrawInputs.capture(List.of(p, compact, p), codes, new GpuBufferSlice(terrain, 8, 80));
        check(input != null && input.device() == DEVICE && input.inputCount() == 3, "capture identity/count");
        byte[][] bytes = input.uniformBytes();
        check(Arrays.equals(bytes[0], Arrays.copyOfRange(RenderSystem.projection.buffer().bytes, 8, 72)), "projection offset exact");
        check(Arrays.equals(bytes[1], RenderSystem.globals.bytes), "all Globals bytes, including reuse-normalized fields");
        check(Arrays.equals(bytes[2], RenderSystem.fog.buffer().bytes), "all fog bytes");
        check(bytes[3].length == 80 && Arrays.equals(Arrays.copyOf(bytes[3], 72), Arrays.copyOfRange(terrain.bytes, 8, 80)), "terrain offset and 72 exact bytes");
        check(Arrays.equals(Arrays.copyOfRange(bytes[3], 72, 80), new byte[8]), "terrain tail padded, not copied");
        check(Arrays.equals(input.uniformMasks(), new int[]{39, 33, 39}), "per-input native slot masks");
        long[] programs = input.programs();
        check(programs.length == 10 && programs[0] == 101 && programs[8] == 808, "layer-indexed programs");
        for (int i=0; i<10; i++) if (i!=0 && i!=8) check(programs[i] == 0, "unused layer zero");
        check(MetalFarPrograms.requests.equals(List.of(101L, 808L, 101L)), "every actual selected pipeline requested");
        check(MetalFarPrograms.retains.equals(List.of(101L, 808L)), "one retain per layer table entry");
        byte saved = bytes[1][28];
        bytes[1][28]++; bytes[0] = null; programs[0] = 0; codes[0] = 9;
        input.uniformMasks()[0] = 0; input.layerCodes()[0] = 9;
        Arrays.fill(RenderSystem.globals.bytes, (byte)0); p.uniformName[0] = "changed"; p.handle = 909;
        RenderSystem.projection = RenderSystem.fog = null; RenderSystem.globals = null;
        RenderSystem.renderThread = false; // worker access must not read any render-thread dependency
        check(input.uniformBytes()[1][28] == saved && input.uniformBytes()[0] != null, "owned deep snapshots");
        check(input.uniformMasks()[0] == 39 && input.layerCodes()[0] == 0 && input.programs()[0] == 101, "owned metadata");
        input.close(); input.close();
        check(MetalFarPrograms.releases.equals(List.of(101L, 808L)), "worker close exactly once");
        expectFailure(input::programs, "closed handles inaccessible");
        expectFailure(() -> capture(p), "capture requires render thread");
    }
    static void optionalAndValidation() {
        reset();
        var p = new MetalRenderPipeline(DEVICE, 101);
        Arrays.fill(p.uniformStages, 0); p.uniformStages[0] = 1;
        RenderSystem.globals = null; RenderSystem.fog = null;
        p.uniformName[1] = "unused unsupported layout";
        try (var input = MetalFarDrawInputs.capture(List.of(p), new int[]{9}, null)) {
            check(input != null && input.programs()[9] == 101, "optional uniforms absent; compact cutout code");
            check(input.uniformMasks()[0] == 1 && input.uniformBytes()[1] == null
                && input.uniformBytes()[2] == null && input.uniformBytes()[3] == null, "absent rows and masks");
        }
        for (int mode=0; mode<11; mode++) {
            reset(); p = new MetalRenderPipeline(DEVICE, 101);
            switch (mode) {
                case 0 -> p.uniformName[0] = "Other";
                case 1 -> p.uniformSlot[0] = 6;
                case 2 -> p.uniformKind[0] = 2;
                case 3 -> p.uniformKind[0] = 99;
                case 4 -> p.uniformStages[0] = 4;
                case 5 -> p.uniformStages[4] = 1;
                case 6 -> p.uniformSlot[5] = 0;
                case 7 -> p.uniformName[4] = "Sampler1";
                case 8 -> p.pushConstantSize = 4;
                case 9 -> p.pushConstantStages = 1;
                case 10 -> { p.uniformName[1] = "Projection"; p.uniformSlot[1] = 0; }
            }
            declined(p, "unsupported binding metadata " + mode);
        }
        for (int mode=0; mode<8; mode++) {
            reset(); p = new MetalRenderPipeline(DEVICE, 101);
            switch (mode) {
                case 0 -> RenderSystem.globals = null;
                case 1 -> RenderSystem.projection = null;
                case 2 -> RenderSystem.fog.buffer().available = false;
                case 3 -> terrain.available = false;
                case 4 -> RenderSystem.globals = new MetalBuffer(new MetalDevice(), 48);
                case 5 -> p.closed = true;
                case 6 -> DEVICE.closed = true;
                case 7 -> TerrainShaderVariants.builtin = false;
            }
            declined(p, "unavailable capture " + mode);
        }
        reset(); p = new MetalRenderPipeline(DEVICE, 101);
        for (int code : new int[]{-1, 2, 3, 10, 16, 17, 24})
            check(MetalFarDrawInputs.capture(List.of(p), new int[]{code}, terrain.slice()) == null, "unsupported layer " + code);
        check(MetalFarDrawInputs.capture(List.of(p), new int[0], terrain.slice()) == null, "alignment required");
        check(MetalFarDrawInputs.capture(List.of(), new int[0], terrain.slice()) == null, "empty inputs declined");
        check(capture(p, new MetalRenderPipeline(new MetalDevice(), 202)) == null, "mixed devices declined");
    }
    static void ownershipFailures() {
        for (int mode=0; mode<5; mode++) {
            reset();
            var first = new MetalRenderPipeline(DEVICE, 101);
            var second = new MetalRenderPipeline(DEVICE, mode == 0 ? 0 : 202);
            // Zero means a pending far program, while the original selected pipeline is still live.
            if (mode == 0) { second.handle = 202; second.pending = true; }
            if (mode == 1) MetalFarPrograms.zeroRetain = 202;
            if (mode == 2) MetalFarPrograms.failRetain = 202;
            if (mode == 3) MetalFarPrograms.failRequest = 202;
            if (mode == 2 || mode == 3) expectFailure(() -> capture(first, second), "partial failure propagates");
            else if (mode == 4) check(MetalFarDrawInputs.capture(List.of(first, second), new int[]{0, 0}, terrain.slice()) == null, "different programs in same layer declined");
            else check(capture(first, second) == null, "pending/failed retain declines");
            check(MetalFarPrograms.releases.equals(List.of(101L)), "partial capture releases acquired handle " + mode);
        }
        reset();
        var input = capture(new MetalRenderPipeline(DEVICE, 101), new MetalRenderPipeline(DEVICE, 202));
        MetalFarPrograms.failRelease = 101;
        expectFailure(input::close, "release error propagates after attempting every handle");
        input.close();
        check(MetalFarPrograms.releases.equals(List.of(101L, 202L)), "cleanup attempts all and stays idempotent");
    }
    static void pairedPrograms() {
        reset();
        var input=capture(new MetalRenderPipeline(DEVICE,101));
        var compact=new MetalRenderPipeline(DEVICE,808);
        check(input.includePairedProgram(compact,8),"capture unused companion");
        check(input.inputCount()==1 && Arrays.equals(input.layerCodes(),new int[]{0}),"companion is not another draw");
        check(input.programs()[8]==808,"companion is supplied to worker");
        int retained=MetalFarPrograms.retains.size();
        check(input.includePairedProgram(compact,8) && retained==MetalFarPrograms.retains.size(),"duplicate companion retained once");
        check(!input.includePairedProgram(new MetalRenderPipeline(DEVICE,809),8),"conflicting companion rejected");
        check(!input.includePairedProgram(compact,2),"unsupported layer rejected");
        input.close();
        check(MetalFarPrograms.releases.equals(List.of(101L,808L)),"all companion references released");
        check(!input.includePairedProgram(compact,8),"closed owner cannot recapture companion");
    }
    public static void main(String[] args) {
        exactAndImmutable(); optionalAndValidation(); ownershipFailures(); pairedPrograms();
        System.out.println("MetalFarDrawInputs: " + checks + " CPU contract checks");
    }
}
final class MetalDevice {
    boolean closed; long handle = 1;
    boolean isClosed() { return closed; } long handle() { return handle; }
}
final class MetalBuffer extends GpuBuffer {
    final MetalDevice device;
    MetalBuffer(MetalDevice device, int size) {
        this.device = device; bytes = new byte[size];
        for (int i=0; i<size; i++) bytes[i] = (byte)(i*17+3);
    }
    MetalDevice device() { return device; }
}
final class MetalLodReuseInputs {
    static byte[] snapshot(GpuBufferSlice slice, int count) {
        RenderSystem.assertOnRenderThread();
        var b = slice.buffer();
        if (!b.available || b.closed || slice.offset()<0 || slice.length()<count
            || slice.offset()>b.bytes.length || slice.length()>b.bytes.length-slice.offset()) return null;
        return Arrays.copyOfRange(b.bytes, (int)slice.offset(), (int)slice.offset()+count);
    }
}
final class MetalRenderPipeline {
    static final int KIND_UNIFORM_BUFFER = 0, KIND_SAMPLED_TEXTURE = 1;
    final MetalDevice device;
    long handle; boolean closed, pending;
    int uniformCount = 6, pushConstantSize, pushConstantStages;
    String[] uniformName = {"Projection", "Globals", "Fog", "TerrainUniform", "Sampler0", "Sampler2"};
    int[] uniformKind = {0,0,0,0,1,1}, uniformSlot = {0,1,2,5,0,2}, uniformStages = {1,3,3,3,2,1};
    MetalRenderPipeline(MetalDevice device, long handle) { this.device = device; this.handle = handle; }
    MetalDevice device() { return device; }
    long handle() { RenderSystem.assertOnRenderThread(); return handle; }
    boolean isClosed() { RenderSystem.assertOnRenderThread(); return closed; }
}
final class TerrainShaderVariants {
    static boolean builtin = true;
    static boolean hasBuiltinTerrainSources() { RenderSystem.assertOnRenderThread(); return builtin; }
}
final class MtlNative { static final int STAGE_VERTEX = 1, STAGE_FRAGMENT = 2; }
final class MetalFarPrograms {
    static boolean REQUESTED = true;
    static long failRequest, failRetain, zeroRetain, failRelease;
    static final List<Long> requests = new ArrayList<>(), retains = new ArrayList<>(), releases = new ArrayList<>();
    static long request(MetalRenderPipeline p, int code) {
        long selected = p.handle(); requests.add(selected);
        if (selected == failRequest) throw new IllegalStateException("request failure");
        return p.pending ? 0 : selected;
    }
    static long retain(long borrowed) {
        RenderSystem.assertOnRenderThread();
        if (borrowed == failRetain) throw new IllegalStateException("retain failure");
        if (borrowed == zeroRetain) return 0;
        retains.add(borrowed); return borrowed;
    }
    static void release(long owned) {
        releases.add(owned);
        if (owned == failRelease) throw new IllegalStateException("release failure");
    }
}
""",
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--compile-only", action="store_true")
    args = parser.parse_args()
    jar = ROOT / "build/client.jar"
    if not jar.is_file():
        parser.error("existing build/client.jar required; this tool never builds it")
    cp = os.pathsep.join(map(str, [jar, *sorted((ROOT / "mc/libraries").rglob("*.jar"))]))
    javac = [str(JDK / "bin/javac"), "--release", "25", "-proc:none", "-implicit:none", "-sourcepath", ""]
    with tempfile.TemporaryDirectory(prefix="metal-far-draw-inputs-") as temp:
        out = Path(temp)
        real = out / "real"
        real.mkdir()
        subprocess.run([*javac, "-cp", cp, "-d", str(real), str(SOURCE),
                        str(SOURCE.with_name("MetalFarPrograms.java")),
                        str(SOURCE.with_name("MetalLodReuseInputs.java"))], check=True, cwd=out, timeout=60)
        sources = []
        for relative, content in STUBS.items():
            path = out / "fixtures" / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content)
            sources.append(str(path))
        fixture = out / "classes"
        fixture.mkdir()
        subprocess.run([*javac, "-d", str(fixture), str(SOURCE), *sources], check=True, cwd=out, timeout=60)
        print("Targeted production and CPU fixture compilation passed", flush=True)
        if not args.compile_only:
            subprocess.run([str(JDK / "bin/java"), "-ea", "-cp", str(fixture),
                            "com.mojang.renderpearl.backend.metal.MetalFarDrawInputsTest"],
                           check=True, cwd=out, timeout=60)


if __name__ == "__main__":
    main()
