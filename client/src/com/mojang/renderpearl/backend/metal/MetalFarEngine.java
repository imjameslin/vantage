package com.mojang.renderpearl.backend.metal;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import com.mojang.blaze3d.systems.RenderSystem;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.minecraft.client.renderer.chunk.LodFarGeometry;
import org.lwjgl.system.MemoryUtil;

/** Optional native far queue. Creation/submission run on a worker; acquiring a completed
 * image never waits. This bridge does not redirect game draws or enable itself.
 * All +1 owners have one thread owner at a time. Stop on the render thread before close.
 */
public final class MetalFarEngine implements AutoCloseable {
    public static final boolean SHADE_REQUESTED=MetalFarPrograms.REQUESTED && Boolean.getBoolean("mc.lod.async.shade");
    private long handle;
    private final MetalDevice device;
    private final int width, height;
    private MetalFarEngine(MetalDevice device, long handle, int width, int height) {
        this.device=device; this.handle=handle; this.width=width; this.height=height;
    }
    private static RuntimeException failure(Throwable error) {
        if (error instanceof VirtualMachineError fatal) throw fatal;
        return new IllegalStateException("Far queue bridge failed",error);
    }
    private static void check(int status,long error) {
        if (status < 0) throw new IllegalStateException("Far queue status "+status+": "+MemoryUtil.memUTF8(error));
    }
    private static void worker() {
        if (RenderSystem.isOnRenderThread()) throw new IllegalStateException("Far producer belongs to worker");
    }

    /** Caller protects the native device with beginCompile/endCompile during creation. */
    public static MetalFarEngine create(MetalDevice device,int width,int height,long epoch,
                                        long targetBudget,long packetBudget) {
        worker();
        try (Arena arena=Arena.ofConfined()) {
            long config=arena.allocate(40,8).address(),out=arena.allocate(8,8).address(),error=arena.allocate(512).address();
            MemoryUtil.memPutInt(config,40); MemoryUtil.memPutInt(config+4,width); MemoryUtil.memPutInt(config+8,height);
            MemoryUtil.memPutLong(config+16,epoch); MemoryUtil.memPutLong(config+24,targetBudget);
            MemoryUtil.memPutLong(config+32,packetBudget);
            int status=(int)Native.CREATE.invokeExact(device.handle(),config,out,error,512);
            check(status,error); long owned=MemoryUtil.memGetLong(out);
            if (status != 0 || owned == 0) throw new IllegalStateException("Far queue not created");
            if(SHADE_REQUESTED) {
                try {
                    // Peak includes temporary cluster sort storage as well as
                    // persistent lookup pages; the 512-chunk scene exceeds 64 MiB.
                    status=(int)Native.ENABLE_SHADING.invokeExact(owned,128L<<20,error,512);
                    check(status,error);
                    if(status!=0) throw new IllegalStateException("Far shade preparation not enabled");
                } catch(Throwable failure) { drop(owned); throw failure; }
            }
            return new MetalFarEngine(device,owned,width,height);
        } catch (Throwable error) { throw failure(error); }
    }

    /** Worker. All arguments refer to the SAME captured frame. The published material's
     * passive main ticket must cover geometry uploads and texture copies. No wait/flush.
     * Exact native layout is asserted in mcmetal_farengine.m and its native test.
     */
    public boolean submit(long epoch,long geometryRecipe,LodFarGeometry geometry,MetalFarMaterials.Prepared material,
                          byte[][] uniformBytes,int[] uniformMasks,long[] programs) {
        return submit(epoch,geometryRecipe,geometry.source(),geometry.inputs().size(),null,
            material,uniformBytes,uniformMasks,programs);
    }

    /** Worker refresh of leased geometry. The caller captures source/cullView/uniforms
     * together, with camera deltas relative to the recipe's unchanged render origin.
     * A null view selects the original recipe view; otherwise exactly 128 owned bytes
     * are required. New selection, origin or table contents require a new recipe.
     */
    public boolean submit(long epoch,long geometryRecipe,LodFarGeometry.Source source,int inputCount,byte[] cullView,
                          MetalFarMaterials.Prepared material,byte[][] uniformBytes,int[] uniformMasks,long[] programs) {
        worker();
        if (this.handle == 0) return false;
        MetalSubmissionTicket ticket=material.ticket();
        if (ticket == null || ticket.device() != this.device || ticket.state() != MetalSubmissionTicket.State.COMMITTED) return false;
        if (uniformBytes.length != 4 || uniformBytes[0] == null || uniformBytes[0].length != 64
            || (uniformBytes[1] != null && uniformBytes[1].length != 48)
            || (uniformBytes[2] != null && uniformBytes[2].length != 48)
            || uniformBytes[3] == null || uniformBytes[3].length != 80 || programs.length != 10
            || uniformMasks.length != inputCount || source == null
            || (cullView != null && cullView.length != 128)) throw new IllegalArgumentException("Invalid far input layout");
        try (Arena arena=Arena.ofConfined()) {
            long submission=arena.allocate(432,8).address(),error=arena.allocate(512).address();
            MemoryUtil.memPutInt(submission,432); MemoryUtil.memPutInt(submission+4,uniformMasks.length);
            MemoryUtil.memPutLong(submission+8,epoch); MemoryUtil.memPutLong(submission+16,source.viewNanos());
            MemoryUtil.memPutLong(submission+24,source.frame()); MemoryUtil.memPutLong(submission+32,source.geometryEpoch());
            // This is a version identity, not a reusable hash of material content.
            MemoryUtil.memPutLong(submission+40,ticket.sequence());
            long camera=submission+48;
            MemoryUtil.memPutDouble(camera,source.cameraX()); MemoryUtil.memPutDouble(camera+8,source.cameraY());
            MemoryUtil.memPutDouble(camera+16,source.cameraZ()); MemoryUtil.memPutDouble(camera+24,source.originX());
            MemoryUtil.memPutDouble(camera+32,source.originY()); MemoryUtil.memPutDouble(camera+40,source.originZ());
            MemorySegment.ofAddress(camera+48).reinterpret(64).copyFrom(MemorySegment.ofArray(uniformBytes[3]).asSlice(0,64));
            MemorySegment.ofAddress(camera+112).reinterpret(64).copyFrom(MemorySegment.ofArray(uniformBytes[0]));
            MemoryUtil.memPutLong(submission+224,geometryRecipe); MemoryUtil.memPutLong(submission+232,material.handle());
            for (int i=0;i<10;i++) MemoryUtil.memPutLong(submission+240+i*8L,programs[i]);
            long descriptors=arena.allocate(uniformMasks.length*64L,8).address();
            long[] bytes=new long[4];
            for (int j=0;j<4;j++) { if (uniformBytes[j] == null) continue;
                MemorySegment data=arena.allocate(uniformBytes[j].length,8);
                data.copyFrom(MemorySegment.ofArray(uniformBytes[j])); bytes[j]=data.address(); }
            int[] slots={0,1,2,5};
            for (int i=0;i<uniformMasks.length;i++) for (int j=0;j<4;j++) if ((uniformMasks[i] & (1<<slots[j])) != 0) {
                if (uniformBytes[j] == null) throw new IllegalArgumentException("Required far uniform missing");
                long descriptor=descriptors+i*64L+j*16L;
                MemoryUtil.memPutLong(descriptor,bytes[j]); MemoryUtil.memPutLong(descriptor+8,uniformBytes[j].length);
            }
            MemoryUtil.memPutLong(submission+320,descriptors); MemoryUtil.memPutLong(submission+328,ticket.sequence());
            // Capture is before the ticket, so this same committed value also covers geometry.
            MemoryUtil.memPutLong(submission+336,ticket.sequence()); MemoryUtil.memPutInt(submission+344,1);
            // Original far-image clears: transparent black, reverse-Z zero, both layers.
            int status;
            if (cullView == null) status=(int)Native.SUBMIT.invokeExact(this.handle,submission,error,512);
            else {
                MemorySegment bytesView=arena.allocate(128,8), descriptor=arena.allocate(16,8);
                bytesView.copyFrom(MemorySegment.ofArray(cullView));
                descriptor.set(JAVA_LONG,0,bytesView.address()); descriptor.set(JAVA_LONG,8,128L);
                status=(int)Native.SUBMIT_VIEW.invokeExact(this.handle,submission,descriptor.address(),error,512);
            }
            check(status,error); return status == 0;
        } catch (Throwable error) { throw failure(error); }
    }

    /** Render thread. Returned lease pins up to two complete images and all referenced geometry/materials. */
    public Images acquire(long epoch) {
        RenderSystem.assertOnRenderThread();
        if (this.handle == 0) return null;
        try (Arena arena=Arena.ofConfined()) {
            long out=arena.allocate(8,8).address(),count=arena.allocate(4,4).address();
            int status=(int)Native.ACQUIRE.invokeExact(this.handle,epoch,out,count);
            long owned=MemoryUtil.memGetLong(out); int n=MemoryUtil.memGetInt(count);
            if (status == 1) return null;
            if (status != 0 || owned == 0 || n < 1 || n > 2) throw new IllegalStateException("Invalid completed far lease");
            return new Images(this.device,owned,n);
        } catch (Throwable error) { throw failure(error); }
    }
    /** Off the frame path; diagnostics only. */
    public String error() {
        if (this.handle == 0) return "closed";
        try (Arena arena=Arena.ofConfined()) {
            long out=arena.allocate(1024).address();
            int status=(int)Native.ERROR.invokeExact(this.handle,out,1024);
            if (status < 0) return "far error query failed: "+status;
            return MemoryUtil.memUTF8(out);
        } catch (Throwable error) { throw failure(error); }
    }
    public void stop() {
        RenderSystem.assertOnRenderThread(); if (this.handle == 0) return;
        try { int status=(int)Native.STOP.invokeExact(this.handle);
            if (status < 0) throw new IllegalStateException("Cannot stop far queue: "+status);
        } catch (Throwable error) { throw failure(error); }
    }
    @Override public void close() { long owned=this.handle; this.handle=0; drop(owned); }
    private static void drop(long owned) {
        if (owned == 0) return;
        try { Native.DROP.invokeExact(owned); } catch (Throwable error) { throw failure(error); }
    }
    public int width() { return this.width; }
    public int height() { return this.height; }

    public static final class Images implements AutoCloseable {
        private long handle; private final int count;
        private final MetalDevice device;
        private Images(MetalDevice device,long handle,int count) { this.device=device; this.handle=handle; this.count=count; }
        public int count() { return this.count; }
        /** Package-only borrowed owner for the native main-queue consumer. That consumer must
         * attach this lease BEFORE reading and hold it through its actual GPU completion. */
        long consumerLease(MetalDevice expected) {
            RenderSystem.assertOnRenderThread();
            if (expected != this.device || this.handle == 0) throw new IllegalStateException("Closed/wrong-device far lease");
            return this.handle;
        }
        /** Completed producer receipt, copied from CPU-owned state while this lease pins its
         * slot. GPU span is driver start-to-end, not exclusive GPU work or a frame-time metric.
         * Unsupported GPU timestamps remain unavailable; no GPU query or wait occurs here. */
        public Timing timing(int index) {
            if(this.handle==0 || index<0 || index>=this.count) throw new IllegalArgumentException("Invalid far timing");
            try(Arena arena=Arena.ofConfined()) {
                long out=arena.allocate(80,8).address();MemoryUtil.memPutInt(out,80);
                int status=(int)Native.TIMING.invokeExact(this.handle,index,out);
                if(status==1) return null;
                if(status!=0 || MemoryUtil.memGetInt(out)!=80) throw new IllegalStateException("Invalid far timing receipt: "+status);
                return new Timing(MemoryUtil.memGetInt(out+4),MemoryUtil.memGetLong(out+8),MemoryUtil.memGetLong(out+16),
                    MemoryUtil.memGetLong(out+24),MemoryUtil.memGetLong(out+32),MemoryUtil.memGetLong(out+40),
                    MemoryUtil.memGetLong(out+48),MemoryUtil.memGetLong(out+56),MemoryUtil.memGetLong(out+64),MemoryUtil.memGetLong(out+72));
            } catch(Throwable error) { throw failure(error); }
        }
        /** Owned CPU metadata, handles inside are borrowed from THIS lease and cannot outlive it. */
        public ByteBuffer describe(int index) {
            if (this.handle == 0 || index < 0 || index >= this.count) throw new IllegalArgumentException("Invalid far image");
            try (Arena arena=Arena.ofConfined()) {
                MemorySegment image=arena.allocate(328,8); image.set(JAVA_INT,0,328);
                int status=(int)Native.IMAGE.invokeExact(this.handle,index,image.address());
                if (status != 0) throw new IllegalStateException("Cannot inspect completed image: "+status);
                return ByteBuffer.wrap(image.toArray(java.lang.foreign.ValueLayout.JAVA_BYTE)).asReadOnlyBuffer().order(ByteOrder.nativeOrder());
            } catch (Throwable error) { throw failure(error); }
        }
        /** Transfer the +1 lease to actual MAIN completion after recording any texture/ID reads.
         * Closing after this is a no-op. Dropping only at producer completion would be unsafe.
         */
        public void retireAfterMainReads(MetalDevice device) {
            RenderSystem.assertOnRenderThread();
            if (device != this.device || device.isClosed()) throw new IllegalArgumentException("Wrong/closed far consumer device");
            long owned=this.handle;
            if (owned != 0) { device.timeline().deferRelease(owned); this.handle=0; }
        }
        /** Only for an abandoned/no-GPU-read acquisition. */
        @Override public void close() { long owned=this.handle; this.handle=0; drop(owned); }
    }

    public record Timing(int flags,long sourceNs,long reserveNs,long encodeStartNs,long commitStartNs,
                         long commitEndNs,long gpuStartNs,long gpuEndNs,long completionNs,long sequence) {
        public double captureToReserveMs() { return (flags&1)!=0 ? elapsed(sourceNs,reserveNs) : Double.NaN; }
        public double encodeMs() { return (flags&1)!=0 ? elapsed(encodeStartNs,commitStartNs) : Double.NaN; }
        public double commitMs() { return (flags&1)!=0 ? elapsed(commitStartNs,commitEndNs) : Double.NaN; }
        public double submittedToCallbackMs() { return (flags&1)!=0 ? elapsed(commitStartNs,completionNs) : Double.NaN; }
        public double gpuSpanMs() { return (flags&2)!=0 ? elapsed(gpuStartNs,gpuEndNs) : Double.NaN; }
        private static double elapsed(long start,long end) { return start>0 && end>=start ? (end-start)/1e6 : Double.NaN; }
    }
    private static final class Native {
        static MethodHandle bind(String symbol,FunctionDescriptor type) {
            return Linker.nativeLinker().downcallHandle(MemorySegment.ofAddress(MtlNative.Loader.address(symbol)),type);
        }
        static final MethodHandle CREATE=bind("mcmtl_ext_far_engine_create",FunctionDescriptor.of(JAVA_INT,JAVA_LONG,JAVA_LONG,JAVA_LONG,JAVA_LONG,JAVA_INT));
        static final MethodHandle ENABLE_SHADING=bind("mcmtl_ext_far_engine_enable_shading",FunctionDescriptor.of(JAVA_INT,JAVA_LONG,JAVA_LONG,JAVA_LONG,JAVA_INT));
        static final MethodHandle SUBMIT=bind("mcmtl_ext_far_engine_submit",FunctionDescriptor.of(JAVA_INT,JAVA_LONG,JAVA_LONG,JAVA_LONG,JAVA_INT));
        static final MethodHandle SUBMIT_VIEW=bind("mcmtl_ext_far_engine_submit_view",FunctionDescriptor.of(JAVA_INT,JAVA_LONG,JAVA_LONG,JAVA_LONG,JAVA_LONG,JAVA_INT));
        static final MethodHandle ACQUIRE=bind("mcmtl_ext_far_engine_acquire",FunctionDescriptor.of(JAVA_INT,JAVA_LONG,JAVA_LONG,JAVA_LONG,JAVA_LONG));
        static final MethodHandle IMAGE=bind("mcmtl_ext_far_engine_image",FunctionDescriptor.of(JAVA_INT,JAVA_LONG,JAVA_INT,JAVA_LONG));
        static final MethodHandle TIMING=bind("mcmtl_ext_far_engine_timing",FunctionDescriptor.of(JAVA_INT,JAVA_LONG,JAVA_INT,JAVA_LONG));
        static final MethodHandle ERROR=bind("mcmtl_ext_far_engine_error",FunctionDescriptor.of(JAVA_INT,JAVA_LONG,JAVA_LONG,JAVA_INT));
        static final MethodHandle STOP=bind("mcmtl_ext_far_engine_stop",FunctionDescriptor.of(JAVA_INT,JAVA_LONG));
        static final MethodHandle DROP=bind("mcmtl_ext_far_engine_drop",FunctionDescriptor.ofVoid(JAVA_LONG));
    }
}
