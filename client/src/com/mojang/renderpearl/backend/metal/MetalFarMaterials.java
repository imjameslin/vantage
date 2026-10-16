package com.mojang.renderpearl.backend.metal;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/** Worker-allocated immutable pixel versions. Pinning only retains storage; pixels
 * are captured later by normal MAIN-stream copies and a passive submission ticket.
 * Owners transfer between render/worker threads and must never be used concurrently.
 */
public final class MetalFarMaterials {
    private MetalFarMaterials() { }
    private static void check(int status, long error) {
        if (status != MtlNative.OK) throw new IllegalStateException("Far material status " + status + ": " + MemoryUtil.memUTF8(error));
    }
    private static RuntimeException failure(Throwable cause) {
        if (cause instanceof VirtualMachineError fatal) throw fatal;
        return new IllegalStateException("Far material capture failed", cause);
    }
    private static void drop(long object) {
        if (object == 0) return;
        try { Native.DROP.invokeExact(object); } catch (Throwable error) { throw failure(error); }
    }

    /** Render-thread only. Inputs must be the exact views/samplers selected for the draw. */
    public static Recipe pin(MetalDevice device, MetalTextureView atlas, MetalSampler atlasSampler,
                             MetalTextureView light, MetalSampler lightSampler, long byteBudget) {
        RenderSystem.assertOnRenderThread();
        if (!MetalFarPrograms.REQUESTED || device.isClosed() || device.handle() == 0) return null;
        MetalTextureView[] views = {atlas, light};
        MetalSampler[] samplers = {atlasSampler, lightSampler};
        long owned = 0;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long sources = stack.ncalloc(8, 2, 48), out = stack.ncalloc(8, 1, 8), error = stack.ncalloc(1, 1, 512);
            for (int i = 0; i < 2; i++) {
                if (views[i] == null || samplers[i] == null || views[i].isClosed() || views[i].texture().isClosed()
                    || samplers[i].isClosed() || views[i].texture().device() != device) return null;
                long at = sources + i * 48L;
                MemoryUtil.memPutLong(at, views[i].texture().handle());
                MemoryUtil.memPutLong(at + 8, views[i].samplingHandle());
                MemoryUtil.memPutLong(at + 16, samplers[i].handle());
                MemoryUtil.memPutInt(at + 24, views[i].baseMipLevel());
                MemoryUtil.memPutInt(at + 28, views[i].mipLevels());
                // The original decoder uses the sampler descriptor's own LOD limits, no encoder override.
            }
            int status = (int)Native.PIN.invokeExact(device.handle(), sources, byteBudget, out, error, 512);
            check(status, error);
            owned = MemoryUtil.memGetLong(out);
            if (owned == 0) throw new IllegalStateException("Material recipe missing");
            Recipe recipe = new Recipe(device, views, samplers, owned);
            owned = 0; return recipe;
        } catch (Throwable error) { throw failure(error); }
        finally { drop(owned); }
    }

    public static final class Recipe implements AutoCloseable {
        private final MetalDevice device;
        private final MetalTextureView[] views;
        private final MetalSampler[] samplers;
        private long handle;
        private Recipe(MetalDevice d, MetalTextureView[] v, MetalSampler[] s, long h) {
            this.device=d; this.views=v; this.samplers=s; this.handle=h;
        }
        /** Worker only. Does not read Java render-thread state or issue GPU work. Caller closes recipe afterwards. */
        public Prepared prepare() {
            if (RenderSystem.isOnRenderThread()) throw new IllegalStateException("Material allocation belongs to worker");
            if (this.handle == 0) throw new IllegalStateException("Material recipe closed");
            long owned = 0;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                long out=stack.ncalloc(8,1,8), error=stack.ncalloc(1,1,512);
                int status=(int)Native.PREPARE.invokeExact(this.handle,out,error,512);
                check(status,error); owned=MemoryUtil.memGetLong(out);
                if (owned == 0) throw new IllegalStateException("Material version missing");
                Prepared prepared=new Prepared(this.device,this.views,this.samplers,owned);
                owned=0; return prepared;
            } catch (Throwable error) { throw failure(error); }
            finally { drop(owned); }
        }
        @Override public void close() { long owned=this.handle; this.handle=0; drop(owned); }
    }

    public static final class Prepared implements AutoCloseable {
        private final MetalDevice device;
        private final MetalTextureView[] views;
        private final MetalSampler[] samplers;
        private long handle;
        private boolean copyAttempted, publishAttempted;
        private int copiedJobs;
        private MetalSubmissionTicket ticket;
        private final long[] revisions = new long[2];
        private Prepared(MetalDevice d, MetalTextureView[] v, MetalSampler[] s, long h) {
            this.device=d; this.views=v; this.samplers=s; this.handle=h;
        }

        /** Render-thread preflight before copying large geometry metadata. Null means ready now.
         * A recipe can become stale while its worker allocates, so callers must retry preparation.
         * This reads identities/state only: no waits, callbacks, stream writes or GPU readback.
         */
        public String recordingDeclineReason(MetalTextureView atlas, MetalSampler atlasSampler,
                                             MetalTextureView light, MetalSampler lightSampler) {
            RenderSystem.assertOnRenderThread();
            if (this.handle == 0 || this.device.isClosed()) return "closed";
            if (this.copyAttempted) return "already-recorded";
            // A copy must not take the late-throttle wait and run state-changing callbacks
            // between the caller's exact uniform capture and these pixel copies.
            if (MetalFrameThrottle.ENABLED && this.device.frameThrottle().pendingSeq() != 0) return "throttle-armed";
            if (this.views[0] != atlas || this.views[1] != light || this.samplers[0] != atlasSampler || this.samplers[1] != lightSampler)
                return "source-identity-changed";
            for (int i=0;i<2;i++) if (this.views[i].isClosed() || this.views[i].texture().isClosed() || this.samplers[i].isClosed())
                return "source-retired";
            return null;
        }

        /** Render thread, at this frame's actual uniform/material capture point outside all passes.
         * Revalidate identities after worker allocation. Content is sampled NOW, not at recipe creation.
         * Never flushes. Normal texture wrappers keep +1 refs deferred through stream decoding, even
         * on a partial recording error/cancelled offer. False means stale input; discard this version.
         */
        public boolean recordCopies(MetalTextureView atlas, MetalSampler atlasSampler,
                                    MetalTextureView light, MetalSampler lightSampler) {
            RenderSystem.assertOnRenderThread();
            String decline=recordingDeclineReason(atlas,atlasSampler,light,lightSampler);
            if (decline != null) {
                // A stale recipe is single-use once offered to recordCopies, as before.
                // The read-only preflight itself never consumes it; an armed throttle may retry.
                if (decline.equals("source-identity-changed") || decline.equals("source-retired")) this.copyAttempted=true;
                return false;
            }
            this.copyAttempted=true;
            MetalTexture[] copies=new MetalTexture[2];
            try (MemoryStack stack = MemoryStack.stackPush()) {
                long binding=stack.ncalloc(8,1,64), out=stack.ncalloc(8,1,8), job=stack.ncalloc(8,1,32);
                for (int i=0; i<2; i++) {
                    int status=(int)Native.BINDING.invokeExact(this.handle,i,binding);
                    if (status != MtlNative.OK) throw new IllegalStateException("Invalid material copy binding");
                    int width=MemoryUtil.memGetInt(binding+24), height=MemoryUtil.memGetInt(binding+28);
                    int format=MemoryUtil.memGetInt(binding+32), mips=MemoryUtil.memGetInt(binding+36);
                    if (format != MetalFormats.PIXEL_FORMAT_RGBA8_UNORM || width != this.views[i].texture().getWidth(0)
                        || height != this.views[i].texture().getHeight(0) || mips != this.views[i].texture().getMipLevels())
                        throw new IllegalStateException("Material allocation metadata mismatch");
                    status=(int)Native.RETAIN_PARENT.invokeExact(this.handle,i,out);
                    if (status != MtlNative.OK) throw new IllegalStateException("Cannot retain material copy");
                    long parent=MemoryUtil.memGetLong(out);
                    if (parent == 0) throw new IllegalStateException("Material copy parent missing");
                    try {
                        copies[i]=new MetalTexture(this.device,parent,format,GpuTexture.USAGE_COPY_DST|GpuTexture.USAGE_TEXTURE_BINDING,
                            "Far material copy",GpuFormat.RGBA8_UNORM,width,height,1,mips);
                    } catch (Throwable error) {
                        this.device.timeline().deferRelease(parent); throw error;
                    }
                    this.revisions[i]=this.views[i].texture().contentRevision();
                }
                int count=(int)Native.COUNT.invokeExact(this.handle);
                if (count <= 0 || count > 30) throw new IllegalStateException("Invalid material copy count");
                for (int j=0; j<count; j++) {
                    int status=(int)Native.JOB.invokeExact(this.handle,j,job);
                    if (status != MtlNative.OK) throw new IllegalStateException("Invalid material copy job");
                    int i=MemoryUtil.memGetInt(job+16), level=MemoryUtil.memGetInt(job+20);
                    if (i < 0 || i >= 2 || MemoryUtil.memGetLong(job) != this.views[i].texture().handle()
                        || MemoryUtil.memGetLong(job+8) != copies[i].handle()) throw new IllegalStateException("Stale copy source/destination");
                    this.device.encoder().copyTextureToTexture(this.views[i].texture(),copies[i],level,0,0,0,0,
                        MemoryUtil.memGetInt(job+24),MemoryUtil.memGetInt(job+28));
                    this.copiedJobs++;
                }
                this.ticket=this.device.encoder().captureSubmissionTicket();
                return true;
            } catch (Throwable error) { throw failure(error); }
            finally { for (MetalTexture texture:copies) if (texture != null) texture.close(); }
        }

        /** Worker, after observing COMMITTED. geometryEvent is borrowed from the retained native geometry recipe.
         * Never use an event value guessed from the current timeline or a not-yet-committed frame.
         */
        public boolean publish(long geometryEvent) {
            if (RenderSystem.isOnRenderThread()) throw new IllegalStateException("Publication belongs to worker");
            if (this.handle == 0 || this.publishAttempted || this.ticket == null
                || this.ticket.state() != MetalSubmissionTicket.State.COMMITTED || this.ticket.device() != this.device) return false;
            this.publishAttempted=true;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                long dependency=stack.ncalloc(8,1,24), error=stack.ncalloc(1,1,512);
                MemoryUtil.memPutLong(dependency,geometryEvent);
                MemoryUtil.memPutLong(dependency+8,this.ticket.sequence());
                MemoryUtil.memPutInt(dependency+16,this.copiedJobs); MemoryUtil.memPutInt(dependency+20,1);
                int status=(int)Native.PUBLISH.invokeExact(this.handle,dependency,error,512);
                check(status,error); return true;
            } catch (Throwable error) { throw failure(error); }
        }
        public long handle() { return this.handle; }
        public MetalSubmissionTicket ticket() { return this.ticket; }
        public long atlasRevision() { return this.revisions[0]; }
        public long lightRevision() { return this.revisions[1]; }
        @Override public void close() { long owned=this.handle; this.handle=0; drop(owned); }
    }

    private static final class Native {
        static MethodHandle bind(String symbol, FunctionDescriptor type) {
            return Linker.nativeLinker().downcallHandle(MemorySegment.ofAddress(MtlNative.Loader.address(symbol)),type);
        }
        static final MethodHandle PIN=bind("mcmtl_ext_far_material_pin",FunctionDescriptor.of(JAVA_INT,JAVA_LONG,JAVA_LONG,JAVA_LONG,JAVA_LONG,JAVA_LONG,JAVA_INT));
        static final MethodHandle PREPARE=bind("mcmtl_ext_far_material_prepare",FunctionDescriptor.of(JAVA_INT,JAVA_LONG,JAVA_LONG,JAVA_LONG,JAVA_INT));
        static final MethodHandle BINDING=bind("mcmtl_ext_far_material_binding",FunctionDescriptor.of(JAVA_INT,JAVA_LONG,JAVA_INT,JAVA_LONG));
        static final MethodHandle RETAIN_PARENT=bind("mcmtl_ext_far_material_retain_parent",FunctionDescriptor.of(JAVA_INT,JAVA_LONG,JAVA_INT,JAVA_LONG));
        static final MethodHandle COUNT=bind("mcmtl_ext_far_material_copy_count",FunctionDescriptor.of(JAVA_INT,JAVA_LONG));
        static final MethodHandle JOB=bind("mcmtl_ext_far_material_copy_job",FunctionDescriptor.of(JAVA_INT,JAVA_LONG,JAVA_INT,JAVA_LONG));
        static final MethodHandle PUBLISH=bind("mcmtl_ext_far_material_publish",FunctionDescriptor.of(JAVA_INT,JAVA_LONG,JAVA_LONG,JAVA_LONG,JAVA_INT));
        static final MethodHandle DROP=bind("mcmtl_ext_far_material_drop",FunctionDescriptor.ofVoid(JAVA_LONG));
    }
}
