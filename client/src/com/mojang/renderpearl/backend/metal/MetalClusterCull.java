package com.mojang.renderpearl.backend.metal;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;

/**
 * Round 12 (a) (CONTRACT.md 16.R12-A): Java side of {@code mcmtl_ext_cluster_cull} (native/src/mcmetal_clusters.m), the
 * GPU terrain cluster cull. Optional extension symbols: when missing, {@link #available} is false and terrain keeps the
 * per-record path. Buffers are ordinary Metal {@link GpuBuffer}s (Shared, tracked): Java writes the inputs through
 * {@link #contents}, the kernels write the outputs, the frame's draw reads them.
 */
public final class MetalClusterCull {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Bytes per {@code mcmtl_cluster_job}. */
    public static final int JOB_SIZE = 104;
    /** Bytes of the view block: 5 float4 planes, eps, flags, margin. */
    public static final int VIEW_SIZE = 92;
    /** Stage 3 (LodGpu): the view block with the persistent-section-table camera deltas (flag 4; float4 at 96 and 112). */
    public static final int VIEW_SIZE_LOD = 128;

    private MetalClusterCull() {
    }

    /** Compiles the kernels now (once per device) so the first culled frame does not; false if that failed. */
    public static boolean prepare(final GpuBuffer anyBuffer) {
        MetalBuffer metal = (MetalBuffer)anyBuffer;
        try {
            int status = (int)Ext.PREPARE.invokeExact(metal.device().handle());
            if (status != MtlNative.OK) {
                LOGGER.warn("Metal cluster cull kernels unavailable: {}: {}", MtlNative.statusName(status), MtlNative.lastError());
                return false;
            }
            return true;
        } catch (Throwable t) {
            LOGGER.warn("Metal cluster cull prepare threw", t);
            return false;
        }
    }

    /** CPU address of byte 0 of a Metal buffer (Shared storage). */
    public static long contents(final GpuBuffer buffer) {
        return ((MetalBuffer)buffer).contents();
    }

    /** Writes job {@code index} at {@code jobs} (JOB_SIZE bytes each): (buffer, byte offset) pairs, then counts. */
    public static void writeJob(
        final long jobs,
        final int index,
        final GpuBuffer items,
        final long itemsOffset,
        final GpuBuffer sections,
        final long sectionsOffset,
        final GpuBuffer outItems,
        final GpuBuffer quads,
        final GpuBuffer args,
        final long argsOffset,
        final GpuBuffer scratch,
        final int clusterCount,
        final int quadCapacity
    ) {
        long at = jobs + (long)index * JOB_SIZE;
        put(at, items, itemsOffset);
        put(at + 16, sections, sectionsOffset);
        put(at + 32, outItems, 0L);
        put(at + 48, quads, 0L);
        put(at + 64, args, argsOffset);
        put(at + 80, scratch, 0L);
        MemoryUtil.memPutInt(at + 96, clusterCount);
        MemoryUtil.memPutInt(at + 100, quadCapacity);
    }

    private static void put(final long at, final GpuBuffer buffer, final long offset) {
        MetalBuffer metal = (MetalBuffer)buffer;
        MemoryUtil.memPutLong(at, metal.handle());
        MemoryUtil.memPutLong(at + 8, metal.backingOffset() + offset);
    }

    /**
     * Encodes and commits {@code jobCount} jobs at {@code jobs} with the view at {@code view} (VIEW_SIZE bytes) on a side command
     * buffer of the device's queue. The caller guarantees that nothing recorded earlier in the current frame reads or writes the
     * output buffers. False if the call failed (nothing encoded: the caller draws per record).
     */
    public static boolean cull(final GpuBuffer anyBuffer, final long jobs, final int jobCount, final long view) {
        return cull(anyBuffer, jobs, jobCount, view, VIEW_SIZE);
    }

    /** {@link #cull} with an explicit view size ({@link #VIEW_SIZE} or {@link #VIEW_SIZE_LOD}). */
    public static boolean cull(final GpuBuffer anyBuffer, final long jobs, final int jobCount, final long view, final int viewBytes) {
        MetalBuffer metal = (MetalBuffer)anyBuffer;
        try {
            int status = (int)Ext.CULL.invokeExact(metal.device().handle(), jobs, jobCount, view, viewBytes);
            if (status != MtlNative.OK) {
                LOGGER.warn("Metal cluster cull failed: {}: {}", MtlNative.statusName(status), MtlNative.lastError());
                return false;
            }
            return true;
        } catch (Throwable t) {
            LOGGER.warn("Metal cluster cull threw", t);
            return false;
        }
    }

    /** Stage 3 r3: bytes of {@code mcmtl_lod_occl} (native/src/mcmetal_clusters.m). */
    public static final int OCCL_SIZE = 80;

    /** Stage 3 r3: writes the LOD occlusion block at {@code at} (OCCL_SIZE bytes). */
    public static void writeOccl(final long at, final GpuBuffer vis, final int visEntries, final GpuBuffer boxes, final int boxCapacity, final GpuBuffer params,
        final long paramsOffset, final int stamp, final float eyeMin, final int prevKey) {
        put(at, vis, 0L);
        put(at + 16, boxes, 0L);
        put(at + 32, params, paramsOffset);
        MemoryUtil.memPutInt(at + 48, boxCapacity);
        MemoryUtil.memPutInt(at + 52, stamp);
        MemoryUtil.memPutInt(at + 56, visEntries);
        MemoryUtil.memPutFloat(at + 60, eyeMin);
        MemoryUtil.memPutInt(at + 64, prevKey);
        MemoryUtil.memPutInt(at + 68, 0);
        MemoryUtil.memPutLong(at + 72, 0L);
    }

    /** True if the stage-3 r3 LOD cull with occlusion ({@code mcmtl_ext_lod_cull}) exists in the loaded library. */
    public static boolean lodCullAvailable() {
        return Ext.LOD_CULL != null;
    }

    /**
     * Stage 3 r3: {@link #cull} with the LOD occlusion block at {@code occl} (0 = none) and per-job (visBase, itemsBase) uints at
     * {@code jobExtra}.
     */
    public static boolean cullLod(final GpuBuffer anyBuffer, final long jobs, final int jobCount, final long view, final long occl, final long jobExtra) {
        MetalBuffer metal = (MetalBuffer)anyBuffer;
        try {
            int status = (int)Ext.LOD_CULL.invokeExact(metal.device().handle(), jobs, jobCount, view, VIEW_SIZE_LOD, occl, jobExtra);
            if (status != MtlNative.OK) {
                LOGGER.warn("Metal LOD cull failed: {}: {}", MtlNative.statusName(status), MtlNative.lastError());
                return false;
            }
            return true;
        } catch (Throwable t) {
            LOGGER.warn("Metal LOD cull threw", t);
            return false;
        }
    }

    /** Stage 3 r6: bytes of {@code mcmtl_lod_sec} (section-level occlusion inputs, native/src/mcmetal_clusters.m). */
    public static final int SEC_SIZE = 48;

    /** Stage 3 r6: true if the section-level LOD occlusion ({@code mcmtl_ext_lod_cull_sec}) exists. */
    public static boolean lodCullSecAvailable() {
        return Ext.LOD_CULL_SEC != null;
    }

    /** Stage 3 r6: writes the section block at {@code at}: section items (uint4 each), their count, the per-slot result bytes. */
    public static void writeSec(final long at, final GpuBuffer sitems, final int count, final GpuBuffer secOcc, final int visBase) {
        put(at, sitems, 0L);
        put(at + 16, secOcc, 0L);
        MemoryUtil.memPutInt(at + 32, count);
        MemoryUtil.memPutInt(at + 36, visBase);
        MemoryUtil.memPutLong(at + 40, 0L);
    }

    /** Stage 3 r6: {@link #cullLod} with section-level occlusion (the view's flag 16 must be set). */
    public static boolean cullLodSec(final GpuBuffer anyBuffer, final long jobs, final int jobCount, final long view, final long occl, final long jobExtra,
        final long sec) {
        MetalBuffer metal = (MetalBuffer)anyBuffer;
        try {
            int status = (int)Ext.LOD_CULL_SEC.invokeExact(metal.device().handle(), jobs, jobCount, view, VIEW_SIZE_LOD, occl, jobExtra, sec);
            if (status != MtlNative.OK) {
                LOGGER.warn("Metal LOD cull (sections) failed: {}: {}", MtlNative.statusName(status), MtlNative.lastError());
                return false;
            }
            return true;
        } catch (Throwable t) {
            LOGGER.warn("Metal LOD cull (sections) threw", t);
            return false;
        }
    }

    /** Optional per-frame, byte-per-section near-terrain coverage. Old libraries keep the experiment off. */
    public static boolean lodCoverageAvailable() { return Ext.LOD_CULL_COVERAGE != null; }

    public static boolean cullLodCoverage(final GpuBuffer anyBuffer, final long jobs, final int jobCount,
        final long view, final long occl, final long jobExtra, final long sec,
        final GpuBuffer coverage, final int coverageCount) {
        MetalBuffer metal = (MetalBuffer)anyBuffer;
        MetalBuffer mask = (MetalBuffer)coverage;
        if (Ext.LOD_CULL_COVERAGE == null) return false;
        try {
            int status = (int)Ext.LOD_CULL_COVERAGE.invokeExact(metal.device().handle(), jobs, jobCount,
                view, VIEW_SIZE_LOD, occl, jobExtra, sec, mask.handle(), mask.backingOffset(), coverageCount);
            if (status != MtlNative.OK) {
                LOGGER.warn("Metal LOD coverage cull failed: {}: {}", MtlNative.statusName(status), MtlNative.lastError());
                return false;
            }
            return true;
        } catch (Throwable t) { LOGGER.warn("Metal LOD coverage cull threw", t); return false; }
    }

    /** Stage 3 r6: {total GPU ns, command buffers} of the cluster / LOD cull command buffers so far (zeros if unavailable). */
    public static long[] cullStats() {
        if (Ext.CULL_STATS == null) {
            return new long[2];
        }
        long mem = MemoryUtil.nmemCalloc(2L, 8L);
        try {
            int status = (int)Ext.CULL_STATS.invokeExact(mem);
            return new long[]{MemoryUtil.memGetLong(mem), MemoryUtil.memGetLong(mem + 8)};
        } catch (Throwable t) {
            return new long[2];
        } finally {
            MemoryUtil.nmemFree(mem);
        }
    }

    /** True if {@code buffer} is a Metal buffer (the cluster path only runs on Metal). */
    public static boolean isMetal(final GpuBuffer buffer) {
        return buffer instanceof MetalBuffer metal && metal.device().handle() != 0L && Ext.CULL != null && Ext.PREPARE != null;
    }

    private static final class Ext {
        static final @Nullable MethodHandle PREPARE = bind("mcmtl_ext_cluster_prepare", FunctionDescriptor.of(JAVA_INT, JAVA_LONG));
        static final @Nullable MethodHandle CULL = bind(
            "mcmtl_ext_cluster_cull", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_INT)
        );

        static final @Nullable MethodHandle LOD_CULL = bind(
            "mcmtl_ext_lod_cull", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_LONG)
        );

        static final @Nullable MethodHandle LOD_CULL_SEC = bind(
            "mcmtl_ext_lod_cull_sec", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG)
        );
        static final @Nullable MethodHandle LOD_CULL_COVERAGE = bind(
            "mcmtl_ext_lod_cull_coverage", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT,
                JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT)
        );
        static final @Nullable MethodHandle CULL_STATS = bind("mcmtl_ext_cluster_cull_stats", FunctionDescriptor.of(JAVA_INT, JAVA_LONG));

        private static @Nullable MethodHandle bind(final String symbol, final FunctionDescriptor descriptor) {
            try {
                return Linker.nativeLinker().downcallHandle(MemorySegment.ofAddress(MtlNative.Loader.address(symbol)), descriptor);
            } catch (Throwable t) {
                LOGGER.warn("Metal cluster cull: {} not available ({}); terrain draws per record", symbol, t.toString());
                return null;
            }
        }
    }
}
