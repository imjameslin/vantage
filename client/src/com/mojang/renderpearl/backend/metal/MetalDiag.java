package com.mojang.renderpearl.backend.metal;

import com.mojang.logging.LogUtils;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Round 9 diagnostics (CONTRACT 16.R9): native experiment bits, runtime-switchable (abab: {@code MetalDiag#flags=0|N}).
 * Pushed to the decoder ({@code mcmtl_ext_set_diag}) before a submit whenever the value changed. 0 = shipped behaviour.
 * Bits: native/src/mcmetal_decoder.m {@code g_mcmtl_diag}.
 */
public final class MetalDiag {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static volatile int flags = Integer.getInteger("mcmetal.diag", 0);
    private static int pushed = 0;
    private static final @Nullable MethodHandle SET = bind();

    private MetalDiag() {
    }

    private static @Nullable MethodHandle bind() {
        try {
            return Linker.nativeLinker().downcallHandle(
                MemorySegment.ofAddress(MtlNative.Loader.address("mcmtl_ext_set_diag")), FunctionDescriptor.ofVoid(ValueLayout.JAVA_INT)
            );
        } catch (Throwable t) {
            LOGGER.warn("Metal diag: mcmtl_ext_set_diag not available ({})", t.toString());
            return null;
        }
    }

    /** Render thread, before mcmtl_submit. */
    static void sync() {
        int f = flags;
        if (f != pushed && SET != null) {
            try {
                SET.invokeExact(f);
            } catch (Throwable t) {
                throw new IllegalStateException(t);
            }
            pushed = f;
        }
    }
}
