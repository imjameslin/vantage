package com.mojang.renderpearl.backend.metal;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import com.mojang.logging.LogUtils;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;

/** Opt-in source retention only. Does not start a worker, change PSOs, or enable ICBs. */
final class MetalFarSourceCapture {
    private static final boolean REQUESTED = Boolean.getBoolean("mc.lod.async");
    private MetalFarSourceCapture() { }

    /** Device construction, before its shader cache can compile any function. */
    static boolean enable(MetalDevice device) {
        if (!REQUESTED || device.handle() == 0L) return false;
        try {
            MethodHandle enable = Linker.nativeLinker().downcallHandle(
                MemorySegment.ofAddress(MtlNative.Loader.address("mcmtl_ext_far_shader_capture_enable")),
                FunctionDescriptor.of(JAVA_INT, JAVA_LONG));
            int status = (int)enable.invokeExact(device.handle());
            if (status == MtlNative.OK) return true;
            LogUtils.getLogger().warn("Far-image source capture unavailable ({}); keep synchronous LOD", status);
        } catch (Throwable error) {
            if (error instanceof VirtualMachineError fatal) throw fatal;
            LogUtils.getLogger().warn("Far-image source capture unavailable; keep synchronous LOD", error);
        }
        return false;
    }
}
