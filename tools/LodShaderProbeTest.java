import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.backend.metal.*;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import java.util.*;
import java.util.concurrent.*;
import java.lang.reflect.Field;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.*;
import com.mojang.renderpearl.api.commands.*;
import com.mojang.renderpearl.api.textures.*;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/** Compile the actual LOD pipelines through production translation and Metal. With
 * -DlodProbeTest.render=true, compare baseline/probe depth and alpha for 96 renders.
 * A diagnostic that changes coverage must never yield a usable cost comparison. */
public final class LodShaderProbeTest {
    public static void main(String[] args) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        MetalBackend backend = new MetalBackend(); backend.loadLibrary();
        GpuDevice device = backend.createDevice(new GpuDebugOptions(0,false,false,true));
        Field field = RenderSystem.class.getDeclaredField("DEVICE"); field.setAccessible(true); field.set(null,device);
        TerrainShaderVariants.setVanillaSourceCheck(() -> true);
        boolean requestedProbe=LodShaderProbe.rgb;
        ExecutorService workers = Executors.newFixedThreadPool(2);
        int count=0,failed=0;
        try (ShaderSource shaders=jarShaderSource(); Rig rig=new Rig(device)) {
            List<RenderPipeline> all=new ArrayList<>(RenderPipelines.requiredPipelines());
            all.addAll(RenderPipelines.optionalPipelines());
            for(RenderPipeline p:all) {
                String name=p.getLocation().toString();
                if(!name.contains("_lod_greedy_") && !name.contains("_lod_compact_")) continue;
                LodShaderProbe.rgb=Boolean.getBoolean("lodProbeTest.render") ? false : requestedProbe;
                CompiledRenderPipeline c=device.compilePipeline(p,shaders,workers).join().finishCompile();
                count++;
                if(c==null) {failed++; System.out.println("FAILED "+name); continue;}
                if(Boolean.getBoolean("lodProbeTest.render")) {
                    LodShaderProbe.rgb=true;
                    CompiledRenderPipeline probe=device.compilePipeline(p,shaders,workers).join().finishCompile();
                    if(probe==null) throw new AssertionError("RGB probe compile failed: "+name);
                    for(boolean variant:new boolean[]{false,true}) for(boolean rgss:new boolean[]{false,true}) for(float fogAlpha:new float[]{0.25f,1f}) {
                        TerrainShaderVariants.useVariant=variant;
                        TerrainShaderVariants.setFrameUsesRgss(rgss);
                        byte[][] a=rig.draw(c,name,rgss,fogAlpha), b=rig.draw(probe,name,rgss,fogAlpha);
                        if(!Arrays.equals(a[1],b[1])) throw new AssertionError("depth changed: "+name+" variant="+variant+" rgss="+rgss);
                        int changed=0,covered=0;
                        for(int i=0;i<a[0].length;i+=4) {
                            if(a[0][i+3]!=b[0][i+3]) throw new AssertionError("alpha changed: "+name+" at "+i);
                            if(a[0][i+3]!=0) covered++;
                            if(a[0][i]!=b[0][i] || a[0][i+1]!=b[0][i+1] || a[0][i+2]!=b[0][i+2]) changed++;
                        }
                        if(covered<300 || changed<300) throw new AssertionError("empty/ineffective probe: "+name+" "+covered+"/"+changed);
                        rig.pairs++;
                    }
                    probe.close();
                }
                c.close();
            }
            if(Boolean.getBoolean("lodProbeTest.render") && (rig.pairs!=96 || LodShaderProbe.BUILT.get()!=36))
                throw new AssertionError("incomplete render matrix: pairs="+rig.pairs+" probes="+LodShaderProbe.BUILT.get());
            System.out.println("Depth/alpha byte comparisons: "+rig.pairs+" pairs; probe PSOs="+LodShaderProbe.BUILT.get());
        } finally { workers.shutdown(); device.close(); }
        if(count!=12 || TerrainShaderVariants.VARIANT_FAILURES.get()!=0) failed++;
        System.out.printf("LOD shader probe: %d pipelines, %d failures, %d variant failures%n",count,failed,TerrainShaderVariants.VARIANT_FAILURES.get());
        if(failed!=0) throw new AssertionError("LOD shader probe failed");
    }

    /** Real compact/BLOCK records, all orientations, varying light/alpha, repeating UV and section fades. */
    static final class Rig implements AutoCloseable {
        static final int W=320,H=240,N=256;
        final GpuDevice device;
        final CommandEncoder enc;
        final List<AutoCloseable> owned=new ArrayList<>();
        final GpuBuffer block,compact,sections,items,quads,indices;
        final GpuTexture atlas,lightmap,color,depth;
        final GpuTextureView av,lv,cv,dv;
        final GpuSampler sampler;
        int pairs;
        Rig(GpuDevice d) throws Exception {
            device=d;enc=d.createCommandEncoder();
            ByteBuffer vb=data(N*112),sec=data(64),it=data(32),ql=data(N*4),ib=data(N*24);
            SplittableRandom r=new SplittableRandom(842);
            for(int s=0;s<4;s++) {
                sec.putInt(-12+s*6).putInt(-6).putInt(-18-s*12).putFloat(new float[]{1,0.35f,0.8f,2.6f}[s]);
                it.putInt(s*256).putInt(s);
            }
            for(int q=0;q<N;q++) {
                int axis=q%3,ta=(axis+1)%3,tb=(axis+2)%3;
                float[] o={(float)r.nextInt(12),(float)r.nextInt(12),(float)r.nextInt(12)};
                float da=q%2==0 ? 3f : -3f,db=3f;
                if(da<0)o[ta]+=3;
                for(int k=0;k<4;k++) {
                    float[] p=o.clone();p[ta]+=(k==1||k==2)?da:0;p[tb]+=k>=2?db:0;
                    int alpha=q%4==0?r.nextInt(256):255;
                    vb.putFloat(p[0]).putFloat(p[1]).putFloat(p[2]).putInt((r.nextInt()&0xffffff)|(alpha<<24))
                        .putFloat((k==1||k==2)?0.875f:0.125f).putFloat(k>=2?0.875f:0.125f)
                        .putShort((short)(r.nextInt(241)|((q%2)*2<<8))).putShort((short)(r.nextInt(241)|((q%2)*2<<8)));
                }
                ql.putInt(q);
                for(int k:new int[]{0,1,2,2,3,0})ib.putInt(q*4+k);
            }
            vb.flip();
            var encode=net.minecraft.client.renderer.chunk.LodCompact.class.getDeclaredMethod("encode",ByteBuffer.class,int.class,int.class,int.class,int.class);
            encode.setAccessible(true);
            ByteBuffer packed=(ByteBuffer)encode.invoke(null,vb,N,0,128,128);
            if(packed==null)throw new AssertionError("fixture compact encode failed");
            // Exercise the compact camera-facing path too (two quads, retaining their material/light).
            for(int q:new int[]{10,130}) {
                int edge=packed.getInt(q*48+4);
                packed.putInt(q*48+4,(edge&0xffffc000)|3|(32<<2));
            }
            block=buffer(vb,GpuBuffer.USAGE_VERTEX);compact=buffer(packed,GpuBuffer.USAGE_VERTEX);
            sections=buffer(sec.flip(),GpuBuffer.USAGE_VERTEX);items=buffer(it.flip(),GpuBuffer.USAGE_VERTEX);
            quads=buffer(ql.flip(),GpuBuffer.USAGE_VERTEX);indices=buffer(ib.flip(),GpuBuffer.USAGE_INDEX);
            atlas=own(d.createTexture("probe atlas",GpuTexture.USAGE_COPY_DST|GpuTexture.USAGE_TEXTURE_BINDING,GpuFormat.RGBA8_UNORM,128,128,1,5));
            for(int mip=0;mip<5;mip++) {
                int dim=128>>mip;ByteBuffer tex=data(dim*dim*4);
                for(int i=0;i<dim*dim;i++)tex.putInt((r.nextInt()&0xffffff)|((i%5==0?0:i%7==0?90:255)<<24));
                enc.writeToTexture(atlas,tex.flip(),mip,0,0,0,dim,dim);
            }
            lightmap=own(d.createTexture("probe light",GpuTexture.USAGE_COPY_DST|GpuTexture.USAGE_TEXTURE_BINDING,GpuFormat.RGBA8_UNORM,16,16,1,1));
            ByteBuffer light=data(16*16*4);for(int i=0;i<256;i++)light.putInt((r.nextInt()&0xffffff)|((128+r.nextInt(128))<<24));
            enc.writeToTexture(lightmap,light.flip(),0,0,0,0,16,16);
            int usage=GpuTexture.USAGE_RENDER_ATTACHMENT|GpuTexture.USAGE_COPY_SRC;
            color=own(d.createTexture("probe color",usage,GpuFormat.RGBA8_UNORM,W,H,1,1));
            depth=own(d.createTexture("probe depth",usage,GpuFormat.D32_FLOAT,W,H,1,1));
            av=own(d.createTextureView(atlas));lv=own(d.createTextureView(lightmap));cv=own(d.createTextureView(color));dv=own(d.createTextureView(depth));
            sampler=own(d.createSampler(AddressMode.CLAMP_TO_EDGE,AddressMode.CLAMP_TO_EDGE,FilterMode.LINEAR,FilterMode.LINEAR,1,OptionalDouble.empty()));
        }
        byte[][] draw(CompiledRenderPipeline p,String name,boolean rgss,float alpha) {
            ByteBuffer g=data(48);g.putFloat(12,1).putFloat(16,-0.3f).putFloat(20,-1.7f).putFloat(24,-0.6f)
                .putFloat(32,W).putFloat(36,H).putInt(44,rgss?1:0);
            ByteBuffer f=data(48);f.putFloat(0,.6f).putFloat(4,.7f).putFloat(8,.9f).putFloat(12,alpha)
                .putFloat(16,15).putFloat(20,70).putFloat(24,30).putFloat(28,100).putFloat(32,100).putFloat(36,100).putFloat(40,30).putFloat(44,.4f);
            ByteBuffer t=data(80);new Matrix4f().rotateX(.15f).rotateY(-.12f).get(0,t);t.putInt(64,128).putInt(68,128);
            ByteBuffer pr=data(64);new Matrix4f().perspective((float)Math.toRadians(70),(float)W/H,1000,.05f,true).get(0,pr);
            try(RenderPass pass=enc.createRenderPass(()->"LOD probe",cv,Optional.of(new Vector4f()),dv,OptionalDouble.of(0))) {
                pass.setPipeline(p);pass.setUniform("Projection",ubo(pr));pass.setUniform("Globals",ubo(g));pass.setUniform("Fog",ubo(f));pass.setUniform("TerrainUniform",ubo(t));
                pass.setUniform("Sampler0",av,sampler);pass.setUniform("Sampler2",lv,sampler);
                pass.setVertexBuffer(0,(name.contains("_compact_")?compact:block).slice());pass.setVertexBuffer(1,sections.slice());
                pass.setVertexBuffer(2,quads.slice());pass.setVertexBuffer(3,items.slice());pass.setIndexBuffer(indices,IndexType.INT);
                pass.drawIndexed(N*6,1,0,0,0);
            }
            return new byte[][]{read(color),read(depth)};
        }
        GpuBufferSlice ubo(ByteBuffer b){return enc.transientMemory().uploadGpu(b,device.getDeviceInfo().limits().minUniformOffsetAlignment(),GpuBuffer.USAGE_UNIFORM);}
        byte[] read(GpuTexture tex) {
            try(GpuBuffer b=device.createBuffer(()->"probe read",GpuBuffer.USAGE_MAP_READ|GpuBuffer.USAGE_COPY_DST,W*H*4L)) {
                enc.copyTextureToBuffer(tex,b,0,()->{},0);
                try(GpuFence fence=enc.createFence()) {enc.submit();if(!fence.awaitCompletion(10_000_000_000L))throw new AssertionError("read timeout");}
                byte[] bytes=new byte[W*H*4];try(var map=b.slice().map(true,false)){map.data().get(bytes);}return bytes;
            }
        }
        GpuBuffer buffer(ByteBuffer b,int usage){return own(device.createBuffer(()->"probe",usage,b));}
        <T extends AutoCloseable>T own(T t){owned.add(t);return t;}
        public void close() throws Exception {for(int i=owned.size()-1;i>=0;i--)owned.get(i).close();}
    }
    static ByteBuffer data(int size){return ByteBuffer.allocateDirect(size).order(ByteOrder.LITTLE_ENDIAN);}
    private static ShaderSource jarShaderSource() {
        Map<Identifier, ShaderSource.CachedIncludeSource> includes = new ConcurrentHashMap<>();
        return new ShaderSource() {
            @Override
            public String getShader(final Identifier id, final ShaderType type) {
                return resource("assets/" + id.getNamespace() + "/shaders/" + id.getPath() + (type == ShaderType.VERTEX ? ".vsh" : ".fsh"));
            }

            @Override
            public CachedIncludeSource getInclude(final Identifier id) {
                return includes.computeIfAbsent(id, key -> {
                    String source = resource("assets/" + key.getNamespace() + "/shaders/include/" + key.getPath());
                    return source == null ? null : CachedIncludeSource.create(key, source);
                });
            }

            @Override
            public void close() {
                includes.values().forEach(CachedIncludeSource::close);
                includes.clear();
            }
        };
    }

    private static String resource(final String path) {
        try (InputStream in = LodShaderProbeTest.class.getClassLoader().getResourceAsStream(path)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
