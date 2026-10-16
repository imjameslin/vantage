package com.mojang.renderpearl.backend.metal;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Arena;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.renderer.chunk.LodFarGeometry;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/** Pins actual buffer backings on the game thread before a refresh moves to its worker.
 * The Java metadata is already immutable; copying its large tables into native storage is worker work.
 */
public final class MetalFarGeometryPins implements AutoCloseable {
    private static final int INPUT_BYTES = 56;
    private static boolean nativeUsed, unavailable;
    private final AtomicLong handle;
    private final LodFarGeometry geometry;
    private final MetalDevice device;

    private MetalFarGeometryPins(long handle, LodFarGeometry geometry, MetalDevice device) {
        this.handle = new AtomicLong(handle); this.geometry = geometry; this.device = device;
    }

    /** Takes ownership of geometry even on failure. Null declines without flushing, waiting or GPU allocation.
     * Caller transfers the result to exactly one worker; that worker closes it after making its native recipe.
     */
    public static MetalFarGeometryPins pin(MetalDevice device, LodFarGeometry geometry) {
        RenderSystem.assertOnRenderThread();
        if (geometry == null) return null;
        long owned = 0;
        boolean adopted = false;
        try {
            if (!MetalFarPrograms.REQUESTED || unavailable || device.isClosed() || device.handle() == 0
                || geometry.inputs().isEmpty() || geometry.inputs().size() > 4096) return null;
            try (Arena arena = Arena.ofConfined()) {
                int count = geometry.inputs().size();
                long inputs = arena.allocate((long)count * INPUT_BYTES, 8).address(), out = arena.allocate(8, 8).address();
                for (int i = 0; i < count; i++) {
                    LodFarGeometry.Input input = geometry.inputs().get(i);
                    long at = inputs + (long)i * INPUT_BYTES;
                    if (!putSlice(at, input.vertices(), device) || !putSlice(at + 24, input.indices(), device)) return null;
                    MemoryUtil.memPutInt(at + 48, input.layerCode());
                    MemoryUtil.memPutInt(at + 52, input.faceBase());
                }
                int status = (int)Native.PIN.invokeExact(device.handle(), geometry.releaseToken(), inputs, count, out);
                if (status != MtlNative.OK) return null;
                owned = MemoryUtil.memGetLong(out);
                if (owned == 0) throw new IllegalStateException("Far geometry pin returned no owner");
                adopted = true; nativeUsed = true;
            }
            MetalFarGeometryPins pins = new MetalFarGeometryPins(owned, geometry, device);
            owned = 0;
            return pins;
        } catch (Throwable failure) {
            unavailable = true;
            if (failure instanceof VirtualMachineError fatal) throw fatal;
            LogUtils.getLogger().warn("Far geometry capture unavailable; synchronous terrain retained", failure);
            return null;
        } finally {
            if (owned != 0) drop(owned);
            if (!adopted) geometry.close();
        }
    }

    private static boolean putSlice(long at, GpuBufferSlice slice, MetalDevice device) {
        if (slice == null || !(slice.buffer() instanceof MetalBuffer buffer) || buffer.isClosed()
            || buffer.device() != device || buffer.handle() == 0 || slice.offset() < 0 || slice.length() <= 0
            || slice.offset() > buffer.size() || slice.length() > buffer.size() - slice.offset()
            || buffer.backingOffset() < 0 || slice.offset() > Long.MAX_VALUE - buffer.backingOffset()) return false;
        MemoryUtil.memPutLong(at, buffer.handle());
        MemoryUtil.memPutLong(at + 8, buffer.backingOffset() + slice.offset());
        MemoryUtil.memPutLong(at + 16, slice.length());
        return true;
    }

    /** Borrowed handle. The worker must retain descendants before closing this owner. */
    public long handle() { return this.handle.get(); }
    public LodFarGeometry geometry() { return this.geometry; }
    public MetalDevice device() { return this.device; }

    /** Worker-only. Returned +1 native recipe retains the allocations independently of this pins object.
     * Call dropRecipe after the producer packet has retained its descendants, or on abandoned work.
     * A zero result means this offer is unusable; it never changes the live game's buffers.
     */
    public long captureRecipe() {
        if (RenderSystem.isOnRenderThread()) throw new IllegalStateException("Far metadata copy belongs to worker");
        long pins = this.handle.get();
        if (pins == 0) throw new IllegalStateException("Far geometry pins closed");
        try (Arena arena = Arena.ofConfined()) {
            int count = this.geometry.inputs().size();
            long items = arena.allocate((long)count * 16, 8).address();
            for (int i = 0; i < count; i++) bytes(arena, items + i * 16L, this.geometry.inputs().get(i).items());
            long tables = arena.allocate(48, 8).address();
            bytes(arena, tables, this.geometry.cullSections());
            bytes(arena, tables + 16, this.geometry.shaderSections());
            bytes(arena, tables + 32, this.geometry.view());
            long out = arena.allocate(8, 8).address(), error = arena.allocate(512, 1).address();
            int status = (int)Native.CAPTURE.invokeExact(pins, items, count, tables,
                LodFarGeometry.MAX_METADATA_BYTES, out, error, 512);
            if (status != MtlNative.OK) {
                LogUtils.getLogger().warn("Far geometry worker capture declined: status={}, {}", status, MemoryUtil.memUTF8(error));
                return 0;
            }
            return MemoryUtil.memGetLong(out);
        } catch (Throwable failure) {
            if (failure instanceof VirtualMachineError fatal) throw fatal;
            throw new IllegalStateException("Far metadata capture failed", failure);
        }
    }

    private static void bytes(Arena arena, long descriptor, ByteBuffer data) {
        MemorySegment src = MemorySegment.ofBuffer(data);
        MemorySegment dst = arena.allocate(src.byteSize(), 8);
        dst.copyFrom(src);
        MemoryUtil.memPutLong(descriptor, dst.address());
        MemoryUtil.memPutLong(descriptor + 8, dst.byteSize());
    }
    public static void dropRecipe(long recipe) { if (recipe != 0) drop(recipe); }
    public static long uploadEvent(long recipe) {
        try { return (long)Native.EVENT.invokeExact(recipe); }
        catch (Throwable failure) {
            if (failure instanceof VirtualMachineError fatal) throw fatal;
            throw new IllegalStateException("Missing captured upload event", failure);
        }
    }

    /** Poll once per game frame, even in menus. Native destruction never calls Java. */
    public static void pollReleases() {
        RenderSystem.assertOnRenderThread();
        if (nativeUsed) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                long tokens = stack.ncalloc(8, 3, 8);
                int count = (int)Native.POLL.invokeExact(tokens, 3);
                if (count < 0 || count > 3) throw new IllegalStateException("Invalid far release count");
                for (int i = 0; i < count; i++) LodFarGeometry.deferredRelease(MemoryUtil.memGetLong(tokens + i * 8L));
            } catch (Throwable failure) {
                // Do not guess that an in-flight allocation became safe: leave its lease held.
                unavailable = true;
                if (failure instanceof VirtualMachineError fatal) throw fatal;
                throw new IllegalStateException("Cannot poll native far geometry releases", failure);
            }
        }
        LodFarGeometry.drainDeferredReleases();
    }

    @Override public void close() {
        long owned = this.handle.getAndSet(0);
        if (owned != 0) drop(owned);
    }
    private static void drop(long owned) {
        try { Native.DROP.invokeExact(owned); }
        catch (Throwable failure) {
            if (failure instanceof VirtualMachineError fatal) throw fatal;
            // Retain the Java lease on failure rather than recycle a native reader's memory.
            throw new IllegalStateException("Cannot release native far geometry", failure);
        }
    }
    private static final class Native {
        static MethodHandle bind(String symbol, FunctionDescriptor type) {
            return Linker.nativeLinker().downcallHandle(MemorySegment.ofAddress(MtlNative.Loader.address(symbol)), type);
        }
        static final MethodHandle PIN = bind("mcmtl_ext_far_geometry_pin", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG));
        static final MethodHandle DROP = bind("mcmtl_ext_far_geometry_drop", FunctionDescriptor.ofVoid(JAVA_LONG));
        static final MethodHandle CAPTURE = bind("mcmtl_ext_far_geometry_capture", FunctionDescriptor.of(JAVA_INT,
            JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT));
        static final MethodHandle POLL = bind("mcmtl_ext_far_geometry_poll", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT));
        static final MethodHandle EVENT = bind("mcmtl_ext_far_geometry_event", FunctionDescriptor.of(JAVA_LONG, JAVA_LONG));
    }
}
