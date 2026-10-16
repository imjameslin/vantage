package com.mojang.renderpearl.backend.metal;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.util.List;
import org.lwjgl.system.MemoryUtil;

/** Default-off far-image consumer. The side command buffer is ordered on the MAIN queue,
 * before this frame's ordinary render stream reads its outputs. No producer fence wait,
 * GPU readback or CPU-selected hole fallback. Resource allocation/PSO creation is worker-only.
 * The one reusable output set requires at most one encode per game frame and the prior main
 * frame to have been committed. Never call during a render pass or after recording an output read.
 */
public final class MetalFarConsumer implements AutoCloseable {
    public static final int MAX_DRAWS=4096;
    public static final boolean LAYERS_REQUESTED=MetalFarEngine.SHADE_REQUESTED
        && Boolean.getBoolean("mc.lod.async.shadeLayers");
    public static final boolean SHADE_DIAGNOSTICS=LAYERS_REQUESTED
        && Boolean.getBoolean("mc.lod.async.shadeDiagnostics");
    private final MetalDevice device;
    private final int width,height;
    private long handle;
    private final MetalTextureView[] color=new MetalTextureView[2],depth=new MetalTextureView[2];
    private MetalBuffer fallbackArgs,compositeArgs;
    private long lastSequence;
    private MetalFarConsumer(MetalDevice device,long handle,int width,int height) {
        this.device=device; this.handle=handle; this.width=width; this.height=height;
    }
    private static RuntimeException failure(Throwable t) {
        if (t instanceof VirtualMachineError fatal) throw fatal;
        return new IllegalStateException("Far image consumer failed",t);
    }
    private static void check(int status,long error) {
        if (status<0) throw new IllegalStateException("Far consumer status "+status+": "+MemoryUtil.memUTF8(error));
    }
    /** Worker: caller protects device lifetime with beginCompile/endCompile. */
    public static MetalFarConsumer create(MetalDevice device,int width,int height,long budget) {
        if (RenderSystem.isOnRenderThread()) throw new IllegalStateException("Far consumer preparation requires worker");
        try (Arena a=Arena.ofConfined()) {
            long out=a.allocate(8,8).address(),err=a.allocate(512).address();
            int status=(int)Native.CREATE.invokeExact(device.handle(),width,height,MAX_DRAWS,budget,out,err,512);
            check(status,err); long owned=MemoryUtil.memGetLong(out);
            if (status!=0 || owned==0) throw new IllegalStateException("Far consumer not created");
            var consumer=new MetalFarConsumer(device,owned,width,height);
            try {
                if(LAYERS_REQUESTED) {
                    int layerStatus=(int)Native.ENABLE_LAYERS.invokeExact(owned,1L<<20,err,512);
                    check(layerStatus,err);
                    if(layerStatus!=0) throw new IllegalStateException("Far consumer layers not prepared");
                }
                return consumer;
            } catch(Throwable t) { consumer.close(); throw t; }
        } catch (Throwable t) { throw failure(t); }
    }
    /** Render thread, once. Native output handles are independent +1 references; wrapping does
     * not initialize/clear them or record uploads that could race the consumer side buffer. */
    public void adoptOutputs() {
        RenderSystem.assertOnRenderThread();
        if (this.handle==0 || device.isClosed()) throw new IllegalStateException("Closed consumer/device");
        if (fallbackArgs!=null) return;
        long[] owned=new long[6];
        try (Arena a=Arena.ofConfined()) {
            long out=a.allocate(64,8).address(),err=a.allocate(512).address();
            MemoryUtil.memPutInt(out,64);
            int status=(int)(LAYERS_REQUESTED?Native.LAYER_OUTPUTS:Native.OUTPUTS).invokeExact(this.handle,out,err,512);
            check(status,err);
            for (int i=0;i<6;i++) owned[i]=MemoryUtil.memGetLong(out+16+i*8L);
            if (status!=0 || MemoryUtil.memGetInt(out+4)!=width || MemoryUtil.memGetInt(out+8)!=height
                || MemoryUtil.memGetInt(out+12)!=MAX_DRAWS) throw new IllegalStateException("Invalid consumer output layout");
            for (long h:owned) if (h==0) throw new IllegalStateException("Missing consumer output");
            for (int i=0;i<2;i++) {
                MetalTexture c=new MetalTexture(device,owned[i],MetalFormats.pixelFormat(GpuFormat.RGBA8_UNORM),
                    GpuTexture.USAGE_TEXTURE_BINDING,"Far resolved colour "+i,GpuFormat.RGBA8_UNORM,width,height,1,1);
                owned[i]=0; color[i]=new MetalTextureView(c,0,1,0); c.close();
                MetalTexture d=new MetalTexture(device,owned[2+i],MetalFormats.pixelFormat(GpuFormat.R32_FLOAT),
                    GpuTexture.USAGE_TEXTURE_BINDING,"Far resolved depth "+i,GpuFormat.R32_FLOAT,width,height,1,1);
                owned[2+i]=0; depth[i]=new MetalTextureView(d,0,1,0); d.close();
            }
            fallbackArgs=new MetalBuffer(device,owned[4],0,0,GpuBuffer.USAGE_INDIRECT_PARAMETERS,MAX_DRAWS*20L); owned[4]=0;
            compositeArgs=new MetalBuffer(device,owned[5],0,0,GpuBuffer.USAGE_INDIRECT_PARAMETERS,LAYERS_REQUESTED?32L:16L); owned[5]=0;
        } catch (Throwable t) { close(); throw failure(t); }
        finally { for(long h:owned) if(h!=0) device.timeline().deferRelease(h); }
    }
    /** Record/commit bounded reprojection then hole-selected args, preserving all original args.
     * Transforms are 144 owned bytes each, native MCMTLFarWarpTransform, newest first. Only pure
     * rotation admitted for this first integration. False leaves ordinary draws untouched. */
    public boolean encode(MetalFarEngine.Images images,byte[][] transforms,List<GpuBufferSlice> originals,long sequence) {
        return encodeInternal(images,transforms,originals,sequence,null,null);
    }
    private record CurrentShading(byte[][] uniforms,MetalTextureView atlas,MetalSampler atlasSampler,
                                  MetalTextureView light,MetalSampler lightSampler) { }
    /** Exact-camera only, after caller's scene/coverage admission. Commits pending
     * current material writes without waiting before the existing main-queue sideCB. */
    public boolean encodeShaded(MetalFarEngine.Images images,byte[] transform,List<GpuBufferSlice> originals,long sequence,
                               byte[][] uniforms,MetalTextureView atlas,MetalSampler atlasSampler,
                               MetalTextureView light,MetalSampler lightSampler) {
        if(!MetalFarEngine.SHADE_REQUESTED) return false;
        return encodeInternal(images,new byte[][]{transform},originals,sequence,
            new CurrentShading(uniforms,atlas,atlasSampler,light,lightSampler),null);
    }
    /** Full draw-list order, including translucent records, must be preserved. */
    public boolean encodeShadedLayers(MetalFarEngine.Images images,byte[] transform,List<GpuBufferSlice> originals,
                                     int[] layerCodes,long sequence,byte[][] uniforms,MetalTextureView atlas,
                                     MetalSampler atlasSampler,MetalTextureView light,MetalSampler lightSampler) {
        if(!LAYERS_REQUESTED) return false;
        return encodeInternal(images,new byte[][]{transform},originals,sequence,
            new CurrentShading(uniforms,atlas,atlasSampler,light,lightSampler),layerCodes);
    }
    private boolean encodeInternal(MetalFarEngine.Images images,byte[][] transforms,List<GpuBufferSlice> originals,
                                   long sequence,CurrentShading shading,int[] layerCodes) {
        RenderSystem.assertOnRenderThread();
        if (handle==0 || device.isClosed() || fallbackArgs==null || sequence<=lastSequence
            || transforms==null || transforms.length<1 || transforms.length>2 || transforms.length>images.count()
            || originals==null || originals.isEmpty() || originals.size()>MAX_DRAWS) return false;
        // Layer outputs are authorized only by the additive layer encode API. A rotating
        // camera or unsupported shade state retains the complete ordinary render path.
        if(LAYERS_REQUESTED && (shading==null || layerCodes==null || layerCodes.length!=originals.size())) return false;
        if(!LAYERS_REQUESTED && layerCodes!=null) return false;
        // This hook is after the normal frame-throttle point. Never introduce a new wait here.
        if (MetalFrameThrottle.ENABLED && device.frameThrottle().pendingSeq()!=0) return false;
        try (Arena a=Arena.ofConfined()) {
            MemorySegment tx=a.allocate(288,8),args=a.allocate(originals.size()*16L,8);
            MemorySegment codes=LAYERS_REQUESTED?a.allocate(layerCodes.length*4L,4):MemorySegment.NULL;
            if(LAYERS_REQUESTED) for(int i=0;i<layerCodes.length;i++) {
                int code=layerCodes[i];
                if(code!=0 && code!=1 && code!=2 && code!=8 && code!=9 && code!=10) return false;
                codes.set(JAVA_INT,i*4L,code);
            }
            for(int i=0;i<transforms.length;i++) {
                if(transforms[i]==null || transforms[i].length!=144) return false;
                tx.asSlice(i*144L,144).copyFrom(MemorySegment.ofArray(transforms[i]));
            }
            for(int i=0;i<originals.size();i++) {
                var slice=originals.get(i);
                if (slice==null || !(slice.buffer() instanceof MetalBuffer b) || b.device()!=device || b.isClosed()
                    || slice.offset()<0 || slice.length()<20 || slice.offset()>b.size()-20) return false;
                args.set(JAVA_LONG,i*16L,b.handle()); args.set(JAVA_LONG,i*16L+8,b.backingOffset()+slice.offset());
            }
            long err=a.allocate(512).address();
            int status;
            if(shading==null) status=(int)Native.ENCODE.invokeExact(handle,images.consumerLease(device),tx.address(),transforms.length,
                args.address(),originals.size(),sequence,err,512);
            else {
                if(transforms.length!=1 || shading.uniforms==null || shading.uniforms.length!=4) return false;
                MemorySegment desc=a.allocate(352,8); desc.set(JAVA_INT,0,352);desc.set(JAVA_INT,4,SHADE_DIAGNOSTICS?3:1);
                int at=16; int[] sizes={64,48,48,80};
                for(int i=0;i<4;i++) {
                    if(shading.uniforms[i]==null || shading.uniforms[i].length!=sizes[i]) return false;
                    desc.asSlice(at,sizes[i]).copyFrom(MemorySegment.ofArray(shading.uniforms[i]));at+=sizes[i];
                }
                MetalTextureView[] views={shading.atlas,shading.light};
                MetalSampler[] samplers={shading.atlasSampler,shading.lightSampler};
                for(int i=0;i<2;i++) {
                    if(views[i]==null || views[i].isClosed() || views[i].texture().isClosed()
                        || views[i].texture().device()!=device || samplers[i]==null || samplers[i].isClosed()) return false;
                    long offset=256+48L*i;
                    desc.set(JAVA_LONG,offset,views[i].texture().handle());
                    desc.set(JAVA_LONG,offset+8,views[i].samplingHandle());
                    desc.set(JAVA_LONG,offset+16,samplers[i].handle());
                    desc.set(JAVA_INT,offset+24,views[i].baseMipLevel());desc.set(JAVA_INT,offset+28,views[i].mipLevels());
                    // The real draw uses the retained sampler's own clamps, no override.
                }
                var ticket=device.encoder().captureSubmissionTicket();
                device.encoder().flushForWait(ticket.sequence()); // COMMIT only; no await.
                if(ticket.state()!=MetalSubmissionTicket.State.COMMITTED) return false;
                desc.set(JAVA_LONG,8,ticket.sequence());
                if(LAYERS_REQUESTED) status=(int)Native.ENCODE_SHADED_LAYERS.invokeExact(handle,images.consumerLease(device),tx.address(),
                    args.address(),codes.address(),originals.size(),sequence,desc.address(),err,512);
                else status=(int)Native.ENCODE_SHADED.invokeExact(handle,images.consumerLease(device),tx.address(),
                    args.address(),originals.size(),sequence,desc.address(),err,512);
            }
            check(status,err);
            if(status==0) lastSequence=sequence;
            return status==0;
        } catch(Throwable t) { throw failure(t); }
    }
    public MetalTextureView color(int layer) { return color[layer]; }
    public MetalTextureView depth(int layer) { return depth[layer]; }
    public GpuBufferSlice fallbackArgs(int draw) { return fallbackArgs.slice(draw*20L,20); }
    public GpuBufferSlice compositeArgs() { return compositeArgs.slice(); }
    public GpuBufferSlice compositeArgs(int layer) {
        if(layer<0 || layer>1) throw new IllegalArgumentException("Opaque far layer required");
        return compositeArgs.slice(LAYERS_REQUESTED?layer*16L:0,16);
    }
    public int width() { return width; }
    public int height() { return height; }
    public record Receipt(long sequence,long sourceFrame,long sourceAgeNs,long holes,boolean fallback,int images,int draws,boolean poisoned,boolean alphaRejected) { }
    public record LayerReceipt(long sequence,long sourceFrame,long sourceAgeNs,long solidHoles,long cutoutHoles,
                               boolean solidFallback,boolean cutoutFallback,boolean solidAlphaRejected,boolean cutoutAlphaRejected,
                               int solidDraws,int cutoutDraws,boolean poisoned) { }
    public LayerReceipt layerReceipt() {
        RenderSystem.assertOnRenderThread();
        if(handle==0 || !LAYERS_REQUESTED) return null;
        try(Arena a=Arena.ofConfined()) {
            long p=a.allocate(96,8).address();MemoryUtil.memPutInt(p,96);
            int status=(int)Native.LAYER_STATS.invokeExact(handle,p);
            if(status!=0) return null;
            int flags=MemoryUtil.memGetInt(p+4);if((flags&3)==0) return null;
            return new LayerReceipt(MemoryUtil.memGetLong(p+8),MemoryUtil.memGetLong(p+16),MemoryUtil.memGetLong(p+24),
                MemoryUtil.memGetLong(p+32),MemoryUtil.memGetLong(p+40),MemoryUtil.memGetInt(p+48)!=0,MemoryUtil.memGetInt(p+52)!=0,
                MemoryUtil.memGetInt(p+56)!=0,MemoryUtil.memGetInt(p+60)!=0,MemoryUtil.memGetInt(p+64),MemoryUtil.memGetInt(p+68),(flags&2)!=0);
        } catch(Throwable t) { throw failure(t); }
    }
    public long[] layerDiagnostics(long sequence) {
        RenderSystem.assertOnRenderThread();
        if(handle==0 || !SHADE_DIAGNOSTICS) return null;
        try(Arena a=Arena.ofConfined()) {
            MemorySegment p=a.allocate(128,4);
            int status=(int)Native.LAYER_DIAGNOSTICS.invokeExact(handle,sequence,p.address(),128);
            if(status!=0) return null;
            long[] result=new long[32];
            for(int i=0;i<result.length;i++) result[i]=Integer.toUnsignedLong(p.get(JAVA_INT,i*4L));
            return result;
        } catch(Throwable t) { throw failure(t); }
    }
    /** Most recently completed sideCB, copied from native CPU state. Never reads GPU memory. */
    public Receipt receipt() {
        RenderSystem.assertOnRenderThread();
        if(handle==0) return null;
        try(Arena a=Arena.ofConfined()) {
            long p=a.allocate(80,8).address();MemoryUtil.memPutInt(p,80);
            int status=(int)Native.STATS.invokeExact(handle,p);
            if(status!=0) return null;
            int flags=MemoryUtil.memGetInt(p+4);
            if((flags&3)==0) return null;
            return new Receipt(MemoryUtil.memGetLong(p+8),MemoryUtil.memGetLong(p+16),MemoryUtil.memGetLong(p+32),
                MemoryUtil.memGetLong(p+48),MemoryUtil.memGetInt(p+64)!=0,MemoryUtil.memGetInt(p+68),MemoryUtil.memGetInt(p+72),(flags&2)!=0,
                MemoryUtil.memGetLong(p+56)!=0);
        } catch(Throwable t) { throw failure(t); }
    }
    @Override public void close() {
        if (fallbackArgs!=null) {
            RenderSystem.assertOnRenderThread();
            for(int i=0;i<2;i++) { if(color[i]!=null){color[i].close();color[i]=null;} if(depth[i]!=null){depth[i].close();depth[i]=null;} }
            fallbackArgs.close();fallbackArgs=null; if(compositeArgs!=null){compositeArgs.close();compositeArgs=null;}
        } else if (color[0]!=null || depth[0]!=null) {
            RenderSystem.assertOnRenderThread();
            for(int i=0;i<2;i++) { if(color[i]!=null){color[i].close();color[i]=null;} if(depth[i]!=null){depth[i].close();depth[i]=null;} }
        }
        long owned=handle;handle=0;
        if(owned!=0) {
            if(RenderSystem.isOnRenderThread()) device.timeline().deferRelease(owned);
            else try { Native.DROP.invokeExact(owned); } catch(Throwable t){throw failure(t);}
        }
    }
    private static final class Native {
        private static MethodHandle bind(String symbol,FunctionDescriptor signature) {
            return Linker.nativeLinker().downcallHandle(MemorySegment.ofAddress(MtlNative.Loader.address(symbol)),signature);
        }
        static final MethodHandle CREATE=bind("mcmtl_ext_far_consumer_create",FunctionDescriptor.of(JAVA_INT,JAVA_LONG,JAVA_INT,JAVA_INT,JAVA_INT,JAVA_LONG,JAVA_LONG,JAVA_LONG,JAVA_INT));
        static final MethodHandle OUTPUTS=bind("mcmtl_ext_far_consumer_outputs",FunctionDescriptor.of(JAVA_INT,JAVA_LONG,JAVA_LONG,JAVA_LONG,JAVA_INT));
        static final MethodHandle ENCODE=bind("mcmtl_ext_far_consumer_encode",FunctionDescriptor.of(JAVA_INT,JAVA_LONG,JAVA_LONG,JAVA_LONG,JAVA_INT,JAVA_LONG,JAVA_INT,JAVA_LONG,JAVA_LONG,JAVA_INT));
        static final MethodHandle ENCODE_SHADED=bind("mcmtl_ext_far_consumer_encode_shaded",FunctionDescriptor.of(JAVA_INT,JAVA_LONG,JAVA_LONG,JAVA_LONG,JAVA_LONG,JAVA_INT,JAVA_LONG,JAVA_LONG,JAVA_LONG,JAVA_INT));
        static final MethodHandle ENABLE_LAYERS=bind("mcmtl_ext_far_consumer_enable_layers",FunctionDescriptor.of(JAVA_INT,JAVA_LONG,JAVA_LONG,JAVA_LONG,JAVA_INT));
        static final MethodHandle LAYER_OUTPUTS=bind("mcmtl_ext_far_consumer_layer_outputs",FunctionDescriptor.of(JAVA_INT,JAVA_LONG,JAVA_LONG,JAVA_LONG,JAVA_INT));
        static final MethodHandle ENCODE_SHADED_LAYERS=bind("mcmtl_ext_far_consumer_encode_shaded_layers",FunctionDescriptor.of(JAVA_INT,JAVA_LONG,JAVA_LONG,JAVA_LONG,JAVA_LONG,JAVA_LONG,JAVA_INT,JAVA_LONG,JAVA_LONG,JAVA_LONG,JAVA_INT));
        static final MethodHandle LAYER_STATS=bind("mcmtl_ext_far_consumer_layer_stats",FunctionDescriptor.of(JAVA_INT,JAVA_LONG,JAVA_LONG));
        static final MethodHandle LAYER_DIAGNOSTICS=bind("mcmtl_ext_far_consumer_layer_diagnostics",FunctionDescriptor.of(JAVA_INT,JAVA_LONG,JAVA_LONG,JAVA_LONG,JAVA_INT));
        static final MethodHandle STATS=bind("mcmtl_ext_far_consumer_stats",FunctionDescriptor.of(JAVA_INT,JAVA_LONG,JAVA_LONG));
        static final MethodHandle DROP=bind("mcmtl_ext_far_engine_drop",FunctionDescriptor.ofVoid(JAVA_LONG));
    }
}
