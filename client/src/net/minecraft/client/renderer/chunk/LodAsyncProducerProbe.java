package net.minecraft.client.renderer.chunk;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.metal.*;
import com.mojang.renderpearl.frontend.FrontendRenderPipeline;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.client.renderer.RenderPipelines;

/** Bounded actual-game integration probe, NOT an FPS acceptance experiment.
 * Requires mc.lod.async plus producerProbe or consumerProbe. consumerProbe connects guarded
 * pure-rotation reprojection with GPU-selected complete synchronous fallback; it still culls
 * synchronously and is NOT a Phase 1/flying pass. No game-thread GPU wait; the shaded
 * consumer commits pending material writes before its main-queue side command buffer.
 * producerProbe.refreshes (1..16, default 1) repeats with a leased geometry recipe,
 * fresh exact camera/uniforms/materials, stopping if that geometry becomes stale. The
 * shaded consumer defaults to continuing refreshes (0), still one pending job/three slots.
 * This diagnostic does not implement translation, streaming recipe replacement or cull skipping.
 */
public final class LodAsyncProducerProbe implements AutoCloseable {
    public static final boolean ENABLED=MetalFarPrograms.REQUESTED && (Boolean.getBoolean("mc.lod.async.producerProbe") || LodFarConsumerProbe.ENABLED);
    /** In-session A/B switch, effective only inside the default-off prototype.
     * Off keeps ownership/completion polling but starts no new captures or composites.
     * An already recorded/submitted job may finish; benchmark transition frames must
     * be discarded. Kept separate from ENABLED so off never skips resource cleanup.
     */
    public static volatile boolean active=Boolean.parseBoolean(System.getProperty("mc.lod.async.active","true"));
    private static final boolean HOLD_EXACT=Boolean.parseBoolean(System.getProperty("mc.lod.async.holdExact","true"));
    static final boolean GUARDED=ENABLED && (LodFarConsumerProbe.ENABLED || Boolean.getBoolean("mc.lod.async.producerProbe.guard"));
    private static final int REFRESHES=MetalFarEngine.SHADE_REQUESTED && LodFarConsumerProbe.ENABLED
        ? Math.clamp(Integer.getInteger("mc.lod.async.producerProbe.refreshes",0),0,1024)
        : Math.clamp(Integer.getInteger("mc.lod.async.producerProbe.refreshes",LodFarConsumerProbe.ENABLED?16:1),1,16);
    private static final ArrayList<LodAsyncProducerProbe> ACTIVE=new ArrayList<>(); // render thread only
    private static final class Worker {
        static final ExecutorService INSTANCE=Executors.newSingleThreadExecutor(r -> {
            Thread t=new Thread(r,"Far producer probe"); t.setDaemon(true); return t;
        });
    }
    private static final long EPOCH=1;
    private static final long TARGET_BUDGET=768L<<20, PACKET_BUDGET=768L<<20, MATERIAL_BUDGET=128L<<20;
    private final LodGpu gpu;
    private MetalDevice device;
    private CompletableFuture<Setup> setupWork;
    private CompletableFuture<Submission> submitWork;
    private MetalFarEngine engine;
    private MetalFarConsumer consumerBridge;
    private final LodFarConsumerProbe consumer=LodFarConsumerProbe.ENABLED?new LodFarConsumerProbe():null;
    private Setup setup;
    private Captured captured;
    private MetalFarGeometryPins pendingGeometry;
    private MetalFarDrawInputs pendingInputs;
    private LodFarRefresh pendingRefresh;
    private LodFarGuard pendingGuard;
    private LodFarConsumerProbe.Scene pendingScene;
    private boolean closed, submitted, done;
    private long submittedNs;
    private long nextCaptureNs;
    private long exactRefreshSkips;
    private int preparationRetries;
    private int submissionRetries;
    private int completedRefreshes;
    private long expectedFrame, workerNs;
    private String diagnosticPhase="created";
    private long nextDiagnosticNs;
    private record Setup(MetalFarEngine engine,MetalFarMaterials.Prepared material,MetalFarConsumer consumer) { }
    private record Captured(long recipe,LodFarGeometry.Source source,int[] codes) { }
    private record Submission(boolean accepted,Captured captured,long sourceFrame,long workerNs,LodFarConsumerProbe.Scene scene) { }

    LodAsyncProducerProbe(LodGpu gpu) { this.gpu=gpu; ACTIVE.add(this); }

    private void diagnostic(String phase) {
        long now=System.nanoTime();
        if (!phase.equals(diagnosticPhase) && now>=nextDiagnosticNs) {
            diagnosticPhase=phase; nextDiagnosticNs=now+5_000_000_000L;
            Lod.LOGGER.info("[lod] async producer probe: {}",phase);
        }
    }

    void prepare(ChunkSectionsToRender.DrawIndirect sections,GpuBufferSlice terrain,GpuSampler sampler,GpuTextureView atlas,GpuTextureView light,RenderTarget main) {
        RenderSystem.assertOnRenderThread();
        if(consumer!=null) consumer.beginFrame();
        if (closed || done || !ENABLED) return;
        if (!(atlas instanceof MetalTextureView a) || !(light instanceof MetalTextureView l)
            || !(sampler instanceof MetalSampler s)
            || !(RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR) instanceof MetalSampler ls)) { diagnostic("unsupported material wrappers"); return; }
        if (device == null) device=a.texture().device();
        if (device.isClosed() || l.texture().device() != device || main.width<=0 || main.height<=0) { close(); return; }
        try {
            poll();
            if (!active) return;
            if(!closed && !done && consumer!=null && captured!=null && engine!=null) {
                LodFarRefresh current=gpu.captureFarRefresh(captured.source);
                if(current!=null) try(var inputs=captureInputs(captured.codes,terrain)) {
                    if(inputs!=null) consumer.prepare(engine,LodFarConsumerProbe.Scene.capture(current.source(),inputs.uniformBytes(),null,
                        sections,sampler,atlas,light,main.width,main.height),sections,main,inputs.uniformBytes(),a,s,l,ls);
                }
            }
            // An accepted far job may still be on the GPU after its worker future completes.
            // Keep its single-use materials intact until the completed image is acquired.
            if (closed || done || submitted || submitWork != null || pendingInputs != null) return;
            // Current-frame geometry/camera/scene admission already happened above.
            // Keep the leased image, re-shade with current materials, and let the GPU
            // independently choose each layer's synchronous fallback. CUTOUT alpha
            // rejection is not a reason to redraw unchanged SOLID geometry.
            // Never abandon an existing preparation/submission to take this shortcut.
            if (HOLD_EXACT && consumer!=null && consumer.exactShadedPrepared() && setupWork==null && setup==null) {
                exactRefreshSkips++;
                return;
            }
            if (!gpu.asyncProbeSettled()) { diagnostic("waiting for settled nonempty world"); return; }
            if (setupWork == null && setup == null) {
                var size=GUARDED?LodFarGuard.guardViewport(main.width,main.height):new LodFarCamera.Viewport(main.width,main.height);
                MetalFarMaterials.Recipe recipe=MetalFarMaterials.pin(device,a,s,l,ls,MATERIAL_BUDGET);
                if (recipe == null) { diagnostic("material recipe unavailable"); return; }
                diagnostic(engine==null ? "preparing targets on worker" : "preparing fresh material on worker");
                int width=size.width(),height=size.height(),centralWidth=main.width,centralHeight=main.height;
                MetalFarEngine existingEngine=engine;
                MetalFarConsumer existingConsumer=consumerBridge;
                setupWork=new CompletableFuture<>(); var completion=setupWork;
                Worker.INSTANCE.execute(() -> {
                    boolean entered=false; MetalFarEngine engine=null; MetalFarMaterials.Prepared material=null; MetalFarConsumer bridge=null;
                    try (recipe) {
                        if (!(entered=device.beginCompile())) throw new IllegalStateException("Device closed before far preparation");
                        material=recipe.prepare();
                        if (existingEngine==null) engine=MetalFarEngine.create(device,width,height,EPOCH,TARGET_BUDGET,PACKET_BUDGET);
                        if(consumer!=null && existingConsumer==null) bridge=MetalFarConsumer.create(device,centralWidth,centralHeight,512L<<20);
                        completion.complete(new Setup(existingEngine!=null ? existingEngine : engine,material,existingConsumer!=null?existingConsumer:bridge)); engine=null; material=null;bridge=null;
                    } catch (Throwable error) { completion.completeExceptionally(error); }
                    finally {
                        // Unsubmitted engine: no work can publish after abandoning creation.
                        if (engine!=null) engine.close(); if (material!=null) material.close();
                        if(bridge!=null) bridge.close();
                        if (entered) device.endCompile();
                    }
                });
                return;
            }
            if (setup == null) { diagnostic("waiting for worker targets"); return; }
            var expectedSize=GUARDED?LodFarGuard.guardViewport(main.width,main.height):new LodFarCamera.Viewport(main.width,main.height);
            if (setup.engine.width()!=expectedSize.width() || setup.engine.height()!=expectedSize.height()) {
                fail(new IllegalStateException("Viewport changed during producer preparation")); return;
            }
            if (!gpu.asyncProbeSettled()) { diagnostic("waiting for settled nonempty world"); return; }
            String decline=setup.material.recordingDeclineReason(a,s,l,ls);
            if (decline!=null) {
                diagnostic("material preparation declined: "+decline);
                if (decline.equals("throttle-armed")) return;
                if (++preparationRetries>8) { fail(new IllegalStateException("Material preparation kept changing: "+decline)); return; }
                // This prepared version has not been copied/submitted. Completed
                // images keep their own materials; only the stale preparation is dropped.
                setup.material.close(); setup=null;
                return;
            }
            if (System.nanoTime()<nextCaptureNs) return;
            // Metadata can be tens of MiB. Do not capture repeatedly while PSOs compile.
            for (TerrainClusters.Draw draw:sections.lodDrawsForReuse()) {
                int code=draw.layer(); if ((code&7)==2) continue;
                if ((code&~9)!=0 || (code&7)>1) return;
                CompiledRenderPipeline compiled=RenderSystem.getCompiledPipeline(RenderPipelines.lodPipeline(code));
                if (!(compiled instanceof FrontendRenderPipeline front) || compiled.isClosed()
                    || !(front.backendRenderPipeline() instanceof MetalRenderPipeline metal)
                    || MetalFarPrograms.request(metal,code)==0) { diagnostic("waiting for selected program "+code); return; }
            }
            nextCaptureNs=System.nanoTime()+100_000_000L;
            if (captured!=null) {
                long start=System.nanoTime();
                LodFarRefresh refresh=gpu.captureFarRefresh(captured.source);
                if (refresh==null) {
                    // This bounded probe does not implement streaming recipe replacement.
                    // Do not submit stale geometry or pretend this is the flying path.
                    diagnostic("geometry changed; bounded refresh probe stopped"); done=true; close(); return;
                }
                MetalFarDrawInputs inputs=captureInputs(captured.codes,terrain);
                if (inputs==null) { diagnostic("fresh draw inputs unavailable"); return; }
                try {
                    LodFarGuard guard=GUARDED?LodFarGuard.prepare(refresh.source(),inputs.uniformBytes(),refresh.view(),main.width,main.height,TARGET_BUDGET):null;
                    var scene=consumer!=null?LodFarConsumerProbe.Scene.capture(refresh.source(),inputs.uniformBytes(),guard.camera().guardedSource(),
                        sections,sampler,atlas,light,main.width,main.height):null;
                    if (!setup.material.recordCopies(a,s,l,ls)) {
                        fail(new IllegalStateException("Fresh material copies declined")); return;
                    }
                    pendingInputs=inputs; inputs=null; pendingRefresh=refresh;pendingGuard=guard;pendingScene=scene;
                    Lod.LOGGER.info("[lod] async producer probe refresh capture: {} inputs, 128 view bytes, {} ms; immutable geometry reused",
                        captured.codes.length,(System.nanoTime()-start)/1e6);
                } finally { if (inputs!=null) inputs.close(); }
                return;
            }
            LodFarGeometry geometry=gpu.captureFarGeometry(LodFarGeometry.MAX_METADATA_BYTES);
            if (geometry == null) { diagnostic("geometry declined: "+gpu.farCaptureDecline()); return; }
            MetalFarDrawInputs inputs=null; MetalFarGeometryPins pins=null;
            try {
                int[] codes=new int[geometry.inputs().size()];
                for (int i=0;i<codes.length;i++) codes[i]=geometry.inputs().get(i).layerCode();
                inputs=captureInputs(codes,terrain);
                if (inputs == null) { diagnostic("exact draw inputs unavailable"); return; }
                byte[] captureView=null;
                if(GUARDED) { captureView=new byte[128];geometry.view().get(captureView); }
                LodFarGuard guard=GUARDED?LodFarGuard.prepare(geometry.source(),inputs.uniformBytes(),captureView,main.width,main.height,TARGET_BUDGET):null;
                var scene=consumer!=null?LodFarConsumerProbe.Scene.capture(geometry.source(),inputs.uniformBytes(),guard.camera().guardedSource(),
                    sections,sampler,atlas,light,main.width,main.height):null;
                pins=MetalFarGeometryPins.pin(device,geometry); geometry=null; // pin always adopts/cancels the Java offer
                if (pins == null) { diagnostic("native geometry pin unavailable"); return; }
                if (!setup.material.recordCopies(a,s,l,ls)) {
                    fail(new IllegalStateException("Prepared material copies declined at capture: "+setup.material.recordingDeclineReason(a,s,l,ls))); return;
                }
                pendingGeometry=pins; pins=null; pendingInputs=inputs; inputs=null;pendingGuard=guard;pendingScene=scene;
                Lod.LOGGER.info("[lod] async producer probe captured {} inputs, {} faces, {} metadata bytes, {} ms; settled world, guarded={}, consumer={}",
                    pendingGeometry.geometry().inputs().size(),pendingGeometry.geometry().inputs().stream().mapToLong(LodFarGeometry.Input::quadCount).sum(),
                    pendingGeometry.geometry().metadataBytes(),pendingGeometry.geometry().captureNanos()/1e6,GUARDED,consumer!=null);
            } finally {
                if (geometry!=null) geometry.close(); if (pins!=null) pins.close(); if (inputs!=null) inputs.close();
            }
        } catch (RuntimeException | LinkageError error) { fail(error); }
    }

    private MetalFarDrawInputs captureInputs(int[] codes,GpuBufferSlice terrain) {
        var pipelines=new ArrayList<MetalRenderPipeline>(codes.length);
        for (int code:codes) {
            CompiledRenderPipeline compiled=RenderSystem.getCompiledPipeline(RenderPipelines.lodPipeline(code));
            if (!(compiled instanceof FrontendRenderPipeline front) || compiled.isClosed()
                || !(front.backendRenderPipeline() instanceof MetalRenderPipeline metal) || metal.device()!=device) return null;
            pipelines.add(metal);
        }
        MetalFarDrawInputs inputs=MetalFarDrawInputs.capture(pipelines,codes,terrain);
        if(inputs==null || !MetalFarEngine.SHADE_REQUESTED) return inputs;
        boolean complete=false;
        try {
            for(int code:new int[]{0,1,8,9}) {
                var compiled=RenderSystem.getCompiledPipeline(RenderPipelines.lodPipeline(code));
                if(!(compiled instanceof FrontendRenderPipeline front) || compiled.isClosed()
                    || !(front.backendRenderPipeline() instanceof MetalRenderPipeline metal)
                    || !inputs.includePairedProgram(metal,code)) return null;
            }
            complete=true;return inputs;
        } finally {if(!complete)inputs.close();}
    }

    /** Global menu/startup poll keeps abandoned-world resources from waiting for another world frame. */
    public static void pollAll() {
        if (!ENABLED) return;
        RenderSystem.assertOnRenderThread();
        for (int i=ACTIVE.size()-1;i>=0;i--) {
            LodAsyncProducerProbe probe=ACTIVE.get(i);
            try { probe.poll(); } catch (RuntimeException | LinkageError error) { probe.fail(error); }
            if (probe.closed && probe.setupWork==null && probe.submitWork==null && probe.engine==null
                && probe.consumerBridge==null && probe.setup==null && probe.captured==null
                && probe.pendingGeometry==null && probe.pendingInputs==null) ACTIVE.remove(i);
        }
    }
    public static void stopDevice(MetalDevice device) {
        if (!ENABLED) return;
        RenderSystem.assertOnRenderThread();
        for (LodAsyncProducerProbe probe:ACTIVE) if (probe.device==device) probe.close();
    }

    private void poll() {
        if (setupWork!=null && setupWork.isDone()) {
            var work=setupWork; setupWork=null; setup=work.join(); // isDone: never blocks
            engine=setup.engine;
            consumerBridge=setup.consumer;
            if(!closed && consumer!=null) consumer.ready(consumerBridge);
            if (closed) setup.engine.stop();
        }
        if (!closed && pendingInputs!=null) {
            var ticket=setup.material.ticket();
            if (ticket.state()==MetalSubmissionTicket.State.FAILED) { fail(new IllegalStateException("Source submission failed")); return; }
            if (ticket.state()==MetalSubmissionTicket.State.COMMITTED) {
                var pins=pendingGeometry; var inputs=pendingInputs; var current=setup;
                var reusable=captured; var refresh=pendingRefresh;
                var guard=pendingGuard; var scene=pendingScene;
                pendingGeometry=null; pendingInputs=null; pendingRefresh=null;pendingGuard=null;pendingScene=null;
                submitWork=new CompletableFuture<>(); var completion=submitWork;
                Worker.INSTANCE.execute(() -> {
                    boolean entered=false; long recipe=0; long start=System.nanoTime();
                    try (pins;inputs) {
                        if (!(entered=device.beginCompile())) throw new IllegalStateException("Device closed before far submission");
                        if (reusable==null) recipe=pins.captureRecipe();
                        long nativeRecipe=reusable!=null ? reusable.recipe : recipe;
                        if (nativeRecipe==0 || !current.material.publish(MetalFarGeometryPins.uploadEvent(nativeRecipe)))
                            throw new IllegalStateException("Far material/geometry capture declined");
                        var source=refresh!=null ? refresh.source() : pins.geometry().source();
                        boolean accepted=current.engine.submit(EPOCH,nativeRecipe,source,inputs.inputCount(),
                            guard!=null?guard.cullView():refresh!=null ? refresh.view() : null,current.material,
                            guard!=null?guard.uniformBytes():inputs.uniformBytes(),inputs.uniformMasks(),inputs.programs());
                        Captured retained=reusable;
                        if (accepted && reusable==null) retained=new Captured(recipe,source,inputs.layerCodes());
                        completion.complete(new Submission(accepted,retained,source.frame(),System.nanoTime()-start,scene));
                        if (accepted && reusable==null) recipe=0; // Transfer one +1 into the completed future.
                    } catch (Throwable error) { completion.completeExceptionally(error); }
                    finally { MetalFarGeometryPins.dropRecipe(recipe); if (entered) device.endCompile(); }
                });
            }
        }
        if (submitWork!=null && submitWork.isDone()) {
            var work=submitWork; submitWork=null;
            var result=work.join(); captured=result.captured;
            if(result.accepted && consumer!=null) consumer.remember(result.scene);
            submitted=result.accepted; expectedFrame=result.sourceFrame; workerNs=result.workerNs; submittedNs=System.nanoTime();
            if (!submitted && !closed) {
                if (++submissionRetries>8) { fail(new IllegalStateException("Far producer admission repeatedly declined")); return; }
                // Never publish this single-use material again. Completed images
                // retain their own versions; prepare a fresh capture on a later frame.
                setup.material.close(); setup=null; nextCaptureNs=System.nanoTime()+100_000_000L;
                diagnostic("producer busy; refreshing source for bounded retry"); return;
            }
        }
        if (!closed && submitted && setup!=null) {
            try (var images=setup.engine.acquire(EPOCH)) {
                if (images!=null) {
                    ByteBuffer image=images.describe(0);
                    // Acquire may still return the last completed refresh while the new one runs.
                    if (image.getLong(32)!=expectedFrame) {
                        if (System.nanoTime()-submittedNs>10_000_000_000L)
                            fail(new IllegalStateException("No new completed far image: "+setup.engine.error()));
                        return;
                    }
                    double age=(System.nanoTime()-image.getLong(24))/1e6;
                    var timing=images.timing(0);
                    if(timing!=null) Lod.LOGGER.info("[lod] async producer timing: sourceFrame={} sequence={} flags={} captureToReserveMs={} encodeMs={} commitMs={} submittedToCallbackMs={} gpuSpanMs={}; GPU span includes waits/contention, not exclusive work",
                        image.getLong(32),timing.sequence(),timing.flags(),timing.captureToReserveMs(),timing.encodeMs(),timing.commitMs(),timing.submittedToCallbackMs(),timing.gpuSpanMs());
                    Lod.LOGGER.info("[lod] async producer probe COMPLETE: {}x{}, {} inputs, source frame {}, age {} ms, main ticket {}, refresh {}/{}, worker {} ms; own queue, consumer={}",
                        image.getInt(4),image.getInt(8),image.getInt(12),image.getLong(32),age,image.getLong(240),++completedRefreshes,REFRESHES,workerNs/1e6,consumer!=null);
                    submitted=false;
                    submissionRetries=0;
                    if (REFRESHES!=0 && completedRefreshes>=REFRESHES) { done=true; close(); }
                    else {
                        setup.material.close(); setup=null; preparationRetries=0;
                        nextCaptureNs=System.nanoTime()+8_333_334L; // Never exceed display rate, even in tiny scenes.
                    }
                } else if (System.nanoTime()-submittedNs > 10_000_000_000L) {
                    fail(new IllegalStateException("No completed far image: "+setup.engine.error()));
                }
            }
        }
        if (closed && setupWork==null && submitWork==null) {
            if (engine!=null) { engine.stop(); engine.close(); engine=null; }
            if (setup!=null) { setup.material.close(); setup=null; }
            try { if(consumer!=null) consumer.finish(); }
            finally {
                try { if(consumerBridge!=null) { var release=consumerBridge;consumerBridge=null;release.close(); } }
                finally { if (captured!=null) { var release=captured;captured=null;MetalFarGeometryPins.dropRecipe(release.recipe); } }
            }
        }
    }
    private void fail(Throwable error) {
        Lod.LOGGER.warn("[lod] async producer probe failed; synchronous draws retained",error); done=true; close();
    }
    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        if (!closed) Lod.LOGGER.info("[lod] async producer exact refresh suppression: enabled={} skipped={} completedRefreshes={}; skips are CPU admissions, not GPU reuse receipts",
            HOLD_EXACT,exactRefreshSkips,completedRefreshes);
        closed=true;
        if (engine!=null) engine.stop();
        if (pendingGeometry!=null) { pendingGeometry.close(); pendingGeometry=null; }
        if (pendingInputs!=null) { pendingInputs.close(); pendingInputs=null; }
        pendingRefresh=null;
        pendingGuard=null;pendingScene=null;
        if(consumer!=null) consumer.beginFrame();
    }
    boolean render(ChunkSectionsToRender.DrawIndirect sections,ChunkSectionLayer layer,com.mojang.renderpearl.api.commands.RenderPass pass) {
        return !closed && consumer!=null && consumer.render(sections,layer,pass);
    }
    boolean consuming() { return !closed && consumer!=null && consumer.prepared(); }
    String consumerStatsJson() { return consumer==null?"null":consumer.statsJson(); }
}
