package net.minecraft.client.renderer.chunk;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.metal.*;
import com.mojang.renderpearl.frontend.FrontendRenderPipeline;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import net.minecraft.client.renderer.RenderPipelines;

/** First default-off main-frame connection. Pure rotation/standing only; any unproved
 * scene, material, age or camera state keeps synchronous terrain. Missing pixels select
 * the complete current fallback on the GPU in the SAME frame. This does not claim the
 * translation/motion gate or eliminate the existing synchronous cull dispatch yet.
 */
final class LodFarConsumerProbe {
    static final boolean ENABLED=MetalFarPrograms.REQUESTED && Boolean.getBoolean("mc.lod.async.consumerProbe");
    /** Default-off within the async prototype. Requires mc.lod.materialInputs=true
     * BEFORE atlas upload/publication; missing source or current tokens fail closed.
     * A family proves stable sprite-to-UV mapping across audited backing swaps,
     * not unchanged pixels. Native current-material validation and CUTOUT alpha
     * guards remain mandatory. Lightmap, samplers and pipeline identities stay strict.
     */
    private static final boolean SHADE_ATLAS_FAMILY=Boolean.getBoolean("mc.lod.async.shadeAtlasFamily");
    private static final long MAX_AGE_NS=100_000_000L;
    private final LinkedHashMap<Long,Scene> scenes=new LinkedHashMap<>();
    private MetalFarConsumer consumer;
    private List<GpuBufferSlice> fallback;
    private boolean exactShadedPrepared;
    private long attempts,encodes,shadedEncodes,missing,stale,changed,motion,completeReuses,completeFallbacks,alphaRejects;
    private final long[] layerReuses=new long[2],layerFallbacks=new long[2],layerHoles=new long[2];
    private final long[] sceneDifferences=new long[10];
    private long lastReceipt, nextLog;

    void ready(MetalFarConsumer consumer) {
        if(consumer!=null) { consumer.adoptOutputs();this.consumer=consumer; }
    }
    void remember(Scene scene) {
        if(scene==null) return;
        scenes.put(scene.source.frame(),scene);
        while(scenes.size()>4) scenes.remove(scenes.keySet().iterator().next());
    }
    void beginFrame() { fallback=null;exactShadedPrepared=false; }
    boolean prepared() { return fallback!=null; }
    /** Current-frame CPU admission/encoding only, not a completed GPU reuse verdict.
     * The GPU still chooses full per-layer fallback for any holes or alpha changes.
     */
    boolean exactShadedPrepared() { return exactShadedPrepared; }
    String statsJson() {
        return "{\"enabled\":"+ENABLED+",\"phase1Ready\":false,\"pureRotationOnly\":true,\"synchronousCull\":true"
            +",\"attempts\":"+attempts+",\"encoded\":"+encodes+",\"missing\":"+missing+",\"ageDeclines\":"+stale
            +",\"sceneDeclines\":"+changed+",\"motionDeclines\":"+motion+",\"observedReuse\":"+completeReuses
            +",\"observedFallback\":"+completeFallbacks+",\"shadedEncoded\":"+shadedEncodes+",\"alphaRejected\":"+alphaRejects
            +",\"receiptCountsAreSampled\":true"
            +",\"independentLayers\":"+MetalFarConsumer.LAYERS_REQUESTED+",\"layerReuse\":"+Arrays.toString(layerReuses)
            +",\"layerFallback\":"+Arrays.toString(layerFallbacks)+",\"layerHolePixels\":"+Arrays.toString(layerHoles)
            +",\"atlasFamilyRequested\":"+SHADE_ATLAS_FAMILY
            +",\"sceneDifferenceCounts\":"+Arrays.toString(sceneDifferences)+"}";
    }

    void prepare(MetalFarEngine engine,Scene current,ChunkSectionsToRender.DrawIndirect sections,RenderTarget main,
                 byte[][] uniforms,MetalTextureView atlas,MetalSampler atlasSampler,MetalTextureView light,MetalSampler lightSampler) {
        beginFrame(); attempts++;
        if(consumer==null || current==null || engine==null) {missing++;return;}
        if(Lod.layerMask!=7 || !Lod.draw || !TerrainShaderVariants.hasBuiltinTerrainSources()
            || !TerrainShaderVariants.hasBuiltinReuseSources() || main.width!=consumer.width() || main.height!=consumer.height()
            || main.getColorTexture()==null || main.getDepthTexture()==null
            || main.getColorTexture().getFormat()!=GpuFormat.RGBA8_UNORM || main.getDepthTexture().getFormat()!=GpuFormat.D32_FLOAT
            || !System.getProperty("mc.lod.diag","").isEmpty()
            || !System.getProperty("mcmetal.diag.terrainShader","").isEmpty()) {changed++;return;}
        receipt();
        try(var images=engine.acquire(1)) {
            if(images==null) {missing++;return;}
            long now=System.nanoTime();
            var newest=images.describe(0);Scene source=scenes.get(newest.getLong(32));
            if(source==null) {missing++;return;}
            int differences=source.differences(current);
            // Check even when physical handles/revision numbers happen to match:
            // an unknown write or new installed shader family must not regain trust.
            if(SHADE_ATLAS_FAMILY && !source.sameAtlasFamily(current)) {
                if(changed<3) Lod.LOGGER.info("[lod] async consumer atlas family declined: sourceFrame={} currentFrame={} sourceToken={} currentToken={} sameFamily={}; requires materialInputs before publication",
                    source.source.frame(),current.source.frame(),source.atlasToken!=null,current.atlasToken!=null,source.sameAtlasFamily(current));
                changed++;return;
            }
            // Coverage stays authoritative only at precisely the source camera. Appearance
            // may change. CUTOUT retains every-mip alpha guards; with the optional layer
            // path, SOLID needs no alpha guard (no discard, blending or alpha-to-coverage).
            // All other scene-key fields and zero-hole guards remain exact.
            boolean shade=canShade(source,current,differences);
            if(!fresh(newest,now,current.source.frame(),shade)) {stale++;return;}
            if(differences!=0) {
                for(int i=0;i<sceneDifferences.length;i++)if((differences&(1<<i))!=0)sceneDifferences[i]++;
                if(!shade) {
                    if(changed<3) Lod.LOGGER.info("[lod] async consumer scene mismatch: mask={} sourceFrame={} currentFrame={} atlasRevision={}/{} lightRevision={}/{} atlasParentChanged={} lightParentChanged={} atlasViewChanged={} lightViewChanged={} sameAtlasFamily={}; bits atlas,light,texture,settings,pipeline,fog,globals,terrain,identity,geometry",
                        differences,source.source.frame(),current.source.frame(),source.values[0],current.values[0],source.values[1],current.values[1],
                        source.values[2]!=current.values[2],source.values[3]!=current.values[3],source.identities[0]!=current.identities[0],source.identities[1]!=current.identities[1],source.sameAtlasFamily(current));
                    changed++;return;
                }
            }
            byte[] transform=LodFarGuard.warpTransform(source.guardedCamera,current.centralCamera);
            if(transform==null) {motion++;return;}
            byte[][] transforms={transform};
            if(!shade && images.count()==2) {
                var older=images.describe(1);Scene old=scenes.get(older.getLong(32));
                if(old!=null && fresh(older,now,current.source.frame(),false) && old.matches(current)) {
                    byte[] olderTransform=LodFarGuard.warpTransform(old.guardedCamera,current.centralCamera);
                    if(olderTransform!=null) transforms=new byte[][]{transform,olderTransform};
                }
            }
            List<TerrainClusters.Draw> draws=sections.lodDrawsForReuse();
            if(draws==null || draws.isEmpty() || draws.size()>MetalFarConsumer.MAX_DRAWS) return;
            ArrayList<GpuBufferSlice> originals=new ArrayList<>(draws.size());
            int[] codes=new int[draws.size()];
            for(int i=0;i<draws.size();i++) {originals.add(draws.get(i).args());codes[i]=draws.get(i).layer();}
            boolean encoded;
            if(shade && MetalFarConsumer.LAYERS_REQUESTED)
                encoded=consumer.encodeShadedLayers(images,transform,originals,codes,current.source.frame(),uniforms,atlas,atlasSampler,light,lightSampler);
            else encoded=shade?consumer.encodeShaded(images,transform,originals,current.source.frame(),uniforms,atlas,atlasSampler,light,lightSampler)
                :consumer.encode(images,transforms,originals,current.source.frame());
            if(encoded) {
                ArrayList<GpuBufferSlice> selected=new ArrayList<>(draws.size());
                for(int i=0;i<draws.size();i++) selected.add(consumer.fallbackArgs(i));
                fallback=selected;exactShadedPrepared=shade;encodes++;if(shade)shadedEncodes++;
            }
        }
    }
    private static boolean canShade(Scene source,Scene current,int differences) {
        return MetalFarEngine.SHADE_REQUESTED && (SHADE_ATLAS_FAMILY
            ? source.sameAtlasFamily(current) && (source.differences(current,true)&~35)==0
            : (differences&~35)==0)
            && LodFarGuard.exactShadingCrop(source.guardedCamera,source.centralCamera,current.centralCamera);
    }
    private static boolean fresh(ByteBuffer image,long now,long frame,boolean exactShading) {
        long time=image.getLong(24),sourceFrame=image.getLong(32);
        // Only canShade's exact camera/scene proof exempts elapsed age. It does
        // not exempt timestamp/frame order, resource/geometry admission, current
        // material queue ordering, CUTOUT alpha validation or GPU hole guards.
        // Every copied/reprojected source, including the older image, stays bounded.
        return time>0 && time<=now && sourceFrame>0 && sourceFrame<=frame
            && (exactShading || now-time<=MAX_AGE_NS);
    }
    private void receipt() {
        if(MetalFarConsumer.LAYERS_REQUESTED) {layerReceipt();return;}
        var receipt=consumer.receipt();
        if(receipt==null) return;
        if(receipt.poisoned()) throw new IllegalStateException("Main-queue far consumer GPU failure");
        if(receipt.sequence()!=lastReceipt) {
            lastReceipt=receipt.sequence();
            if(receipt.fallback()) completeFallbacks++;else completeReuses++;
            if(receipt.alphaRejected()) alphaRejects++;
            if(System.nanoTime()>=nextLog) {
                nextLog=System.nanoTime()+2_000_000_000L;
                Lod.LOGGER.info("[lod] async consumer completed frame {} source {} age {} ms holes {} syncFallback {} alphaRejected {}; observed receipts reuse={} fallback={} (may skip intermediate receipts)",
                    receipt.sequence(),receipt.sourceFrame(),receipt.sourceAgeNs()/1e6,receipt.holes(),receipt.fallback(),receipt.alphaRejected(),completeReuses,completeFallbacks);
            }
        }
    }
    private void layerReceipt() {
        var receipt=consumer.layerReceipt();if(receipt==null) return;
        if(receipt.poisoned()) throw new IllegalStateException("Main-queue far layer consumer GPU failure");
        if(receipt.sequence()==lastReceipt) return;
        lastReceipt=receipt.sequence();
        boolean[] sync={receipt.solidFallback(),receipt.cutoutFallback()};
        long[] holes={receipt.solidHoles(),receipt.cutoutHoles()};
        for(int i=0;i<2;i++) {if(sync[i])layerFallbacks[i]++;else layerReuses[i]++;layerHoles[i]+=holes[i];}
        if(sync[0] || sync[1]) completeFallbacks++;else completeReuses++;
        if(receipt.solidAlphaRejected() || receipt.cutoutAlphaRejected()) alphaRejects++;
        if(System.nanoTime()>=nextLog) {
            nextLog=System.nanoTime()+2_000_000_000L;
            Lod.LOGGER.info("[lod] async layer consumer completed frame {} source {} age {} ms: SOLID holes={} syncFallback={} alphaRejected={}; CUTOUT holes={} syncFallback={} alphaRejected={}; sampled layer reuse={} fallback={}",
                receipt.sequence(),receipt.sourceFrame(),receipt.sourceAgeNs()/1e6,
                holes[0],sync[0],receipt.solidAlphaRejected(),holes[1],sync[1],receipt.cutoutAlphaRejected(),
                Arrays.toString(layerReuses),Arrays.toString(layerFallbacks));
            var diagnostics=consumer.layerDiagnostics(receipt.sequence());
            if(diagnostics!=null) Lod.LOGGER.info("[lod] async layer shade diagnostics: SOLID={} CUTOUT={} (geometry,sky,priorMissing,priorUncertain,input,lookup,vertex,clip,flat,degenerate,triangle,perspective,gradient,interpolant,alpha,fragment)",
                Arrays.toString(Arrays.copyOfRange(diagnostics,0,16)),Arrays.toString(Arrays.copyOfRange(diagnostics,16,32)));
        }
    }
    /** Keep original layer insertion order and depth test. GPU arguments choose EXACTLY one
     * path per layer; original fallback args are never mutated or reused out of frame. */
    boolean render(ChunkSectionsToRender.DrawIndirect sections,ChunkSectionLayer layer,RenderPass pass) {
        int i=layer.ordinal(); if(fallback==null || i<0 || i>=2) return false;
        sections.renderLodUncached(layer,pass,fallback);
        pass.setPipeline(RenderSystem.getCompiledPipeline(RenderPipelines.LOD_REUSE_COMPOSITE));
        var nearest=RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        pass.setUniform("ReuseColor",consumer.color(i),nearest);
        pass.setUniform("ReuseDepth",consumer.depth(i),nearest);
        pass.drawIndirect(consumer.compositeArgs(i),1);
        return true;
    }
    void finish() {
        beginFrame();scenes.clear();
        if(consumer!=null) {
            try { receipt(); }
            catch(RuntimeException | LinkageError error) { Lod.LOGGER.warn("[lod] async consumer final receipt failed; releasing all owners",error); }
            finally { var release=consumer;consumer=null;release.close(); }
            Lod.LOGGER.info("[lod] async consumer summary: attempts={} encoded={} missing={} ageDeclines={} sceneDeclines={} motionDeclines={} observedReuse={} observedFallback={} shadedEncoded={} alphaRejected={}; pure rotation only, shading exact-camera only, cull still synchronous, not an FPS/gate result",
                attempts,encodes,missing,stale,changed,motion,completeReuses,completeFallbacks,shadedEncodes,alphaRejects);
            Lod.LOGGER.info("[lod] async consumer scene differences: {} (atlas,light,texture,settings,pipeline,fog,globals,terrain,identity,geometry; a frame may count in several categories)",Arrays.toString(sceneDifferences));
            if(MetalFarConsumer.LAYERS_REQUESTED) Lod.LOGGER.info("[lod] async layer summary: sampled reuse={} fallback={} holePixels={} (SOLID,CUTOUT)",
                Arrays.toString(layerReuses),Arrays.toString(layerFallbacks),Arrays.toString(layerHoles));
        }
    }

    /** One immutable, exact scene key per SOURCE CAMERA frame. Native material version IDs
     * alone are not scene identities. Revisions cover queued writes, so unknown/changed material
     * declines rather than assuming that a completed old image is still current. */
    static final class Scene {
        final LodFarGeometry.Source source;
        final LodFarCamera.Camera centralCamera,guardedCamera;
        private final byte[] fog,globals,terrainTail;
        private final Object[] identities;
        private final long[] values;
        private final MetalLodMaterialInputs.AtlasToken atlasToken;
        private Scene(LodFarGeometry.Source source,LodFarCamera.Camera central,LodFarCamera.Camera guarded,
                      byte[][] uniforms,Object[] identities,long[] values,MetalLodMaterialInputs.AtlasToken atlasToken) {
            this.source=source;centralCamera=central;guardedCamera=guarded;
            fog=uniforms[2].clone();globals=uniforms[1].clone();terrainTail=Arrays.copyOfRange(uniforms[3],64,72);
            // Audited built-in terrain ignores these; same exclusions as exact LodReuse.
            Arrays.fill(globals,12,16,(byte)0);Arrays.fill(globals,28,32,(byte)0);Arrays.fill(globals,40,44,(byte)0);
            this.identities=identities;this.values=values;this.atlasToken=atlasToken;
        }
        static Scene capture(LodFarGeometry.Source source,byte[][] uniforms,LodFarCamera.Camera guarded,
                             ChunkSectionsToRender.DrawIndirect sections,GpuSampler sampler,GpuTextureView atlas,GpuTextureView light,int width,int height) {
            if(!TerrainShaderVariants.hasBuiltinTerrainSources() || !(atlas instanceof MetalTextureView a)
                || !(light instanceof MetalTextureView l) || uniforms==null || uniforms.length!=4
                || uniforms[1]==null || uniforms[2]==null) return null;
            var central=LodFarGuard.currentCamera(source,uniforms,width,height);
            ArrayList<Object> objects=new ArrayList<>();objects.add(atlas);objects.add(light);objects.add(sampler);
            objects.add(RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            ArrayList<Long> handles=new ArrayList<>();
            int seen=0;
            for(var draw:sections.lodDrawsForReuse()) {
                int code=draw.layer();if((code&7)==2)continue;
                if((code&~9)!=0) return null;
                if((seen&(1<<code))!=0) continue;seen|=1<<code;
                var compiled=RenderSystem.getCompiledPipeline(RenderPipelines.lodPipeline(code));
                if(!(compiled instanceof FrontendRenderPipeline f) || compiled.isClosed()
                    || !(f.backendRenderPipeline() instanceof MetalRenderPipeline metal)) return null;
                objects.add(compiled);handles.add((long)code);handles.add(metal.handle());
            }
            long[] values=new long[handles.size()+8];
            values[0]=a.texture().contentRevision();values[1]=l.texture().contentRevision();
            values[2]=a.texture().handle();values[3]=l.texture().handle();values[4]=width;values[5]=height;
            values[6]=Lod.layerMask;values[7]=seen;
            for(int i=0;i<handles.size();i++)values[i+8]=handles.get(i);
            // Both producer Scene capture and this frame's Scene capture use the
            // actually selected view. captureStaticAtlas revalidates the current
            // content revision and installed shader family; never cache its result
            // by handle or transplant the retained source token to a current view.
            var atlasToken=SHADE_ATLAS_FAMILY ? MetalLodMaterialInputs.captureStaticAtlas(atlas) : null;
            return new Scene(source,central,guarded,uniforms,objects.toArray(),values,atlasToken);
        }
        private boolean sameAtlasFamily(Scene other) {
            return other!=null && atlasToken!=null && other.atlasToken!=null
                && atlasToken.family()==other.atlasToken.family();
        }
        boolean matches(Scene other) {
            return differences(other)==0;
        }
        /** Diagnostic categories preserve every comparison from the exact scene key. */
        int differences(Scene other) {
            return differences(other,false);
        }
        /** The shaded-only caller first proves family equality. Skip exactly the
         * atlas parent and view, never the aggregate texture/identity categories.
         * Ordinary copy matching always uses the strict overload above.
         */
        private int differences(Scene other,boolean sameAtlas) {
            if(other==null) return 1023;
            int mask=0;
            if(values[0]!=other.values[0])mask|=1;
            if(values[1]!=other.values[1])mask|=2;
            if((!sameAtlas && values[2]!=other.values[2]) || values[3]!=other.values[3])mask|=4;
            for(int i=4;i<8;i++)if(values[i]!=other.values[i])mask|=8;
            if(!Arrays.equals(values,8,values.length,other.values,8,other.values.length))mask|=16;
            if(!Arrays.equals(fog,other.fog))mask|=32;
            if(!Arrays.equals(globals,other.globals))mask|=64;
            if(!Arrays.equals(terrainTail,other.terrainTail))mask|=128;
            if(identities.length!=other.identities.length)mask|=256;
            else for(int i=0;i<identities.length;i++)if(!(sameAtlas && i==0) && identities[i]!=other.identities[i])mask|=256;
            var a=source;var b=other.source;
            if(!(a.generation()==b.generation() && a.geometryEpoch()==b.geometryEpoch()
                && a.membershipBuild()==b.membershipBuild() && a.selectionVersion()==b.selectionVersion()
                && a.visibilityRevision()==b.visibilityRevision() && a.allocationEpoch()==b.allocationEpoch()
                && a.originX()==b.originX() && a.originY()==b.originY() && a.originZ()==b.originZ()))mask|=512;
            return mask;
        }
    }
}
