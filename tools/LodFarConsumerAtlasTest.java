package net.minecraft.client.renderer.chunk;

import com.mojang.blaze3d.pipeline.PipelineCache;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.backend.metal.MetalLodMaterialInputs;
import com.mojang.renderpearl.backend.metal.MetalLodMaterialInputs.AtlasToken;
import com.mojang.renderpearl.backend.metal.MetalTexture;
import com.mojang.renderpearl.backend.metal.MetalTextureView;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.resources.Identifier;
import net.minecraft.client.renderer.chunk.LodFarCamera.Camera;
import net.minecraft.client.renderer.chunk.LodFarCamera.Position;
import net.minecraft.client.renderer.chunk.LodFarCamera.Viewport;

/** CPU-only production publication/admission checks. Texture wrappers have synthetic
 * handles and NO device; never passed to native code, closed or uploaded. Writes below
 * advance the real Java content revision but perform no GPU work. The installed source
 * guard hashes the real packaged GLSL/includes; LWJGL only allocates include text on CPU.
 */
public final class LodFarConsumerAtlasTest {
    private static int checks;
    private static long nextHandle=100;
    private static Constructor<LodFarConsumerProbe.Scene> sceneConstructor;
    private static Method canShade, markWrite;
    private static final Object LIGHT_VIEW=new Object(), ATLAS_SAMPLER=new Object(), LIGHT_SAMPLER=new Object(), PIPELINE=new Object();
    private static final float[] IDENTITY={1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1};
    private static final float[] PROJECTION={1.5f,0,0,0,0,1.5f,0,0,0,0,0,-1,0,0,0.5f,0};
    private static final boolean MATERIAL=Boolean.getBoolean("mc.lod.materialInputs");
    private static final boolean FAMILY=Boolean.getBoolean("mc.lod.async.shadeAtlasFamily");
    private static final boolean REQUESTED=Boolean.getBoolean("mc.lod.async") && Boolean.getBoolean("mc.lod.async.shade");

    private static void check(boolean value,String why) { ++checks;if(!value)throw new AssertionError(why); }
    private static final class Sources implements ShaderSource {
        private final boolean altered;
        private final Map<Identifier,CachedIncludeSource> includes=new HashMap<>();
        Sources(boolean altered) { this.altered=altered; }
        private String read(String path) {
            try(var in=LodFarConsumerAtlasTest.class.getResourceAsStream("/assets/minecraft/shaders/"+path)) {
                if(in==null)throw new AssertionError("Missing built-in shader "+path);
                return new String(in.readAllBytes(),StandardCharsets.UTF_8);
            } catch(IOException e) {throw new AssertionError(e);}
        }
        @Override public String getShader(Identifier id,ShaderType type) {
            String text=read(id.getPath()+(type==ShaderType.VERTEX?".vsh":".fsh"));
            return altered && id.getPath().equals("core/animate_sprite_blit") ? text+"\n// changed installed source\n" : text;
        }
        @Override public CachedIncludeSource getInclude(Identifier id) {
            return includes.computeIfAbsent(id,key->CachedIncludeSource.create(key,read("include/"+key.getPath())));
        }
        @Override public void close() {includes.values().forEach(CachedIncludeSource::close);includes.clear();}
    }
    private static MetalTextureView view() {
        // Constructor/retainView are Java metadata only. These handles never reach Metal.
        var texture=new MetalTexture(null,++nextHandle,70,5,"CPU atlas",GpuFormat.RGBA8_UNORM,16,16,1,3);
        return new MetalTextureView(texture,0,3,0);
    }
    private static void write(MetalTextureView view) throws Exception {markWrite.invoke(view.texture());}
    private static AtlasToken token(MetalTextureView view) {return MetalLodMaterialInputs.captureStaticAtlas(view);}
    private static AtlasToken upload(MetalTextureView view) throws Exception {
        var update=MetalLodMaterialInputs.beginAtlasUpload(view.texture(),new int[][]{{0,0,8,8}});
        write(view);
        MetalLodMaterialInputs.finishAtlas(update,view.texture(),null,false);
        return token(view);
    }
    private static void animate(MetalTextureView current,MetalTextureView spare,boolean spareValid) throws Exception {
        var update=MetalLodMaterialInputs.beginAtlasAnimation(current.texture(),spare.texture(),spareValid);
        check(token(current)==null && token(spare)==null,"unpublished animation invalidates both backings");
        // Simulates successful encoding of the first full-mip copy / later animation.
        write(spare);
        MetalLodMaterialInputs.finishAtlas(update,spare.texture(),current.texture(),true);
    }
    private static Camera central() {return new Camera(PROJECTION,IDENTITY,new Position(0,0,0),new Position(0,0,0),new Viewport(800,800));}
    private static Camera guard() {
        float[] p=PROJECTION.clone();p[0]*=0.8f;p[5]*=0.8f;
        return new Camera(p,IDENTITY,new Position(0,0,0),new Position(0,0,0),new Viewport(1000,1000));
    }
    private static LodFarGeometry.Source geometry(int change) {
        long[] key={1,2,3,4,5,6};int[] origin={0,0,0};
        if(change>=0 && change<6)++key[change];else if(change>=6)++origin[change-6];
        return new LodFarGeometry.Source(key[0],key[1],key[2],key[3],key[4],key[5],42,1,
            origin[0],origin[1],origin[2],0,0,0,0,0,0);
    }
    private static LodFarConsumerProbe.Scene scene(MetalTextureView atlas,AtlasToken token,boolean source,int changedValue,int changedIdentity,int changedGeometry,Camera camera) throws Exception {
        byte[][] u={new byte[64],new byte[48],new byte[48],new byte[80]};
        long[] values={atlas.texture().contentRevision(),20,atlas.texture().handle(),40,800,800,7,0x303,0,100};
        Object[] identities={atlas,LIGHT_VIEW,ATLAS_SAMPLER,LIGHT_SAMPLER,PIPELINE};
        if(changedValue>=0)++values[changedValue];
        if(changedIdentity>=0)identities[changedIdentity]=new Object();
        return sceneConstructor.newInstance(geometry(changedGeometry),camera,source?guard():null,u,identities,values,token);
    }
    private static LodFarConsumerProbe.Scene scene(MetalTextureView atlas,boolean source) throws Exception {
        return scene(atlas,token(atlas),source,-1,-1,-1,central());
    }
    private static boolean shade(LodFarConsumerProbe.Scene source,LodFarConsumerProbe.Scene current) throws Exception {
        return (boolean)canShade.invoke(null,source,current,source.differences(current));
    }
    private static void metadataAndAdmission() throws Exception {
        var a=view();var b=view();var family=upload(a);var source=scene(a,true);
        check((family!=null)==MATERIAL,"material publication feature gate");
        animate(a,b,false);
        if(MATERIAL)check(token(a)==family && token(b)==family,"initial copy and animation share upload family");
        var current=scene(b,false);
        check(a.texture().contentRevision()==b.texture().contentRevision(),"equal revisions on distinct parents fixture");
        check(source.differences(current)==260,"raw texture/view diagnostics remain strict with equal revisions");
        check(!source.matches(current),"ordinary copy cannot accept A to B");
        check(shade(source,current)==(REQUESTED && FAMILY && MATERIAL),"A to B needs all exact-family feature gates");
        if(!MATERIAL || !FAMILY || !REQUESTED)return;

        animate(b,a,true);
        check(token(a)==family && token(b)==family,"B to A retains family");
        current=scene(a,false);
        check(shade(source,current),"A to B to A re-shades current appearance");
        check(!source.matches(current),"same backing with newer revision is not an ordinary copy");
        animate(a,b,true);current=scene(b,false);
        check(source.differences(current)==261,"real atlas revision plus backing/view swap diagnostic mask261");
        check(shade(source,current),"mask261 exact family admitted");
        var newView=new MetalTextureView(b.texture(),0,3,0);
        check(token(newView)==family && shade(source,scene(newView,false)),"new full-range view of trusted parent");
        var partial=new MetalTextureView(b.texture(),1,2,1000);
        check(token(partial)==null,"partial view cannot supply whole-atlas family proof");
        check(!shade(source,scene(partial,false)),"partial view rejected before native descriptor checks");

        for(int index:new int[]{3,4,5,6,7,8,9}) {
            var changed=scene(b,token(b),false,index,-1,-1,central());
            check(!shade(source,changed),"atlas swap cannot hide strict value "+index);
        }
        for(int index:new int[]{1,2,3,4}) {
            var changed=scene(b,token(b),false,-1,index,-1,central());
            check(!shade(source,changed),"atlas swap cannot hide light/sampler/pipeline identity "+index);
        }
        for(int field=0;field<9;field++)
            check(!shade(source,scene(b,token(b),false,-1,-1,field,central())),"atlas swap cannot hide geometry/origin "+field);
        var missing=scene(b,null,false,-1,-1,-1,central());
        check(!shade(source,missing),"missing current token rejects");
        check(!shade(scene(a,null,true,-1,-1,-1,central()),current),"missing retained source token rejects");
        float[] rotated=IDENTITY.clone();rotated[1]=0.001f;
        float[] changedProjection=PROJECTION.clone();changedProjection[0]=Math.nextUp(changedProjection[0]);
        for(Camera moved:new Camera[]{
            new Camera(PROJECTION,rotated,new Position(0,0,0),new Position(0,0,0),new Viewport(800,800)),
            new Camera(PROJECTION,IDENTITY,new Position(0.01,0,0),new Position(0,0,0),new Viewport(800,800)),
            new Camera(changedProjection,IDENTITY,new Position(0,0,0),new Position(0,0,0),new Viewport(800,800))})
            check(!shade(source,scene(b,token(b),false,-1,-1,-1,moved)),"family cannot exempt camera/projection proof");

        write(b); // A write outside the audited animation/publication hooks.
        check(token(b)==null,"unknown real revision increment invalidates current trust");
        check(!shade(source,scene(b,false)),"unknown write does not inherit retained family");
        animate(a,b,true);
        check(token(a)==null && token(b)==null,"animation cannot repair unknown spare writes");
        var secondFamily=upload(b);
        check(secondFamily!=null && secondFamily.family()!=family.family(),"equal-size reupload creates NEW mapping generation");
        check(!shade(source,scene(b,false)),"same shape and pipelines cannot exempt atlas rebuild");
        // Reupload on the original physical backing also cannot borrow its old generation.
        upload(a);check(!shade(source,scene(a,false)),"reupload on original handle/view rejected");
        MetalLodMaterialInputs.invalidate(a.texture());
        check(token(a)==null && !shade(source,scene(a,false)),"disposal/reload invalidation rejects");
    }
    private static void installedShaderReplacement() throws Exception {
        if(!MATERIAL || !FAMILY || !REQUESTED)return;
        var a=view();upload(a);var source=scene(a,true);
        try(var sameSources=new Sources(false)) {
            RenderSystem.setCurrentPipelineCache(new PipelineCache(null,sameSources));
            check(token(a)==null,"identical installed-source cache replacement invalidates publication");
            check(!shade(source,scene(a,false)),"resource epoch replacement cannot reuse old family");
            var newToken=upload(a);check(newToken!=null,"new cache can publish only a fresh family");
            check(!shade(source,scene(a,false)),"new cache full upload still differs from source family");
        }
        try(var changedSources=new Sources(true)) {
            RenderSystem.setCurrentPipelineCache(new PipelineCache(null,changedSources));
            check(upload(a)==null,"changed actual animation shader hash prevents family publication");
            check(!shade(source,scene(a,false)),"changed resource-pack shader cannot admit atlas swap");
        }
    }
    public static void main(String[] args) throws Exception {
        RenderSystem.initRenderThread();
        sceneConstructor=LodFarConsumerProbe.Scene.class.getDeclaredConstructor(LodFarGeometry.Source.class,Camera.class,Camera.class,
            byte[][].class,Object[].class,long[].class,AtlasToken.class);sceneConstructor.setAccessible(true);
        canShade=LodFarConsumerProbe.class.getDeclaredMethod("canShade",LodFarConsumerProbe.Scene.class,LodFarConsumerProbe.Scene.class,int.class);canShade.setAccessible(true);
        markWrite=MetalTexture.class.getDeclaredMethod("markContentWritten");markWrite.setAccessible(true);
        try(var sources=new Sources(false)) {
            var previous=RenderSystem.setCurrentPipelineCache(new PipelineCache(null,sources));
            try {metadataAndAdmission();installedShaderReplacement();}
            finally {RenderSystem.setCurrentPipelineCache(previous);}
        }
        System.out.println("LodFarConsumerAtlasTest: "+checks+" checks passed; shade="+REQUESTED+" family="+FAMILY+" materialInputs="+MATERIAL+" (CPU metadata only)");
    }
}
