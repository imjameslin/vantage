package net.minecraft.client.renderer.chunk;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.metal.MetalLodReuseInputs;
import com.mojang.renderpearl.backend.metal.MetalRenderPipeline;
import com.mojang.renderpearl.backend.metal.MetalTexture;
import com.mojang.renderpearl.backend.metal.TerrainShaderVariants;
import com.mojang.renderpearl.frontend.FrontendRenderPipeline;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import com.mojang.blaze3d.buffers.Std140Builder;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/** Prompt 1: exact, full-resolution, default-off reuse. Owned by one LodGpu lifetime.
 * Opaque layers retain their original insertion order; translucent material is never cached.
 * Capture uses frustum/facing culling only, so changing near depth cannot reveal a cached hole.
 * No CPU wait, approximate hash, frame age, camera tolerance or reduced-detail path is accepted.
 */
public final class LodReuse implements AutoCloseable {
    public static volatile boolean enabled = Lod.flag("mc.lod.reuse", true);
    /** Naked-eye approximation (user-approved class): "soft" inputs - atlas animation ticks (lava, fire, magma, ... in
     * the far opaque layers; water is translucent and drawn live), lightmap updates (torch flicker, slow sky light) and
     * fog colour/distances - may lag the retained far image by up to this many ms; camera, geometry, visibility, cull
     * inputs, pipelines, samplers, target size and atlas reloads still refresh at once ({@code mc.lod.reuseSoftMs},
     * default 1000, 0 = exact; abab-switchable). */
    public static volatile int softMs = Integer.getInteger("mc.lod.reuseSoftMs", 1000);
    /** Turn reuse ({@code mc.lod.turnReuse}, abab-switchable): LodGpu culls a guard frustum padded by an integer number of
     * pixels per side, the capture renders that guard image with the matching row-scaled projection, and while the eye stays
     * exactly where it was (pure rotation) the composite reprojects it (nearest texel, depth re-projected). Same camera and
     * rotation = exact texel offset. LodGpu admits retained frames only while the rotation stays inside the guard. */
    public static volatile boolean turn = Lod.flag("mc.lod.turnReuse", true);
    private boolean turnFrame, turnCapture, turnDrop, warp;
    private int padX, padY, usedPadX, usedPadY;
    private float[] srcView, srcProj;
    private final Matrix4f currentToSource = new Matrix4f(), sourceToCurrent = new Matrix4f();
    private ProjectionMatrixBuffer guardProjection;
    private MappableRingBuffer warpUbo;
    private long warps, turnCaptures, turnDrops;
    private long capturedNanos;
    private long softHits;
    /** Two guard image sets: [front] composites, the other is filled in strips (LodGpu turnStrips) and swapped in. */
    private final TextureTarget[][] sets = new TextureTarget[2][2];
    private int front;
    private @org.jspecify.annotations.Nullable List<TerrainClusters.Draw> stripDraws;
    private int stripIndex = -1, stripCount, backDone = -1, backCount, backPadX, backPadY;
    private boolean turnSwap;
    private Key backKey;
    private byte[] backTerrain;
    private float[] backView, backProj;
    private long backNanos, strips, swaps, stripAborts;
    private ProjectionMatrixBuffer backProjection;
    private MappableRingBuffer backTerrainUbo;
    private Key previous, observed;
    private byte[] cullInputs;
    private long geometry, membership, visibility;
    private boolean fading, ready, failed;
    private long attempts, hits, refreshes, bypasses;
    private final long[] misses = new long[4];
    /** Value-miss attribution: first differing index of Key.values (0 geometry, 1 membership, 2 visibility, 3 atlas, 4 lightmap, ...). */
    private final long[] valueMisses = new long[12];

    void source(long geometry, long membership, long visibility, boolean fading, byte[] cullInputs) {
        this.geometry = geometry;
        this.membership = membership;
        this.visibility = visibility;
        this.fading = fading;
        this.cullInputs = cullInputs;
        this.ready = false;
        this.turnFrame = false;
        this.turnSwap = false;
        this.stripDraws = null;
        this.stripIndex = -1;
    }

    /** After {@link #turnFrame}(false): also render strip {@code index} of {@code count} (draws culled for the back
     * capture's guard sub-frustum) into the back set, with the back capture's own view and guard projection. */
    void strip(List<TerrainClusters.Draw> draws, int index, int count) {
        this.stripDraws = draws;
        this.stripIndex = index;
        this.stripCount = count;
    }

    /** True when the composited capture is past half its soft-input window: refresh it in strips before it expires
     * (an expired soft key would otherwise drop to a live draw and a whole-frame guard capture). */
    boolean softAging() { int soft = softMs; return soft > 0 && System.nanoTime() - this.capturedNanos > (long)(soft * 1_000_000L * turnAge); }
    /** Fraction of the soft window after which a still turn capture refreshes in strips ({@code mc.lod.turnAge}). */
    public static volatile float turnAge = Float.parseFloat(System.getProperty("mc.lod.turnAge", "0.5"));

    /** After {@link #turnFrame}(true): the guard list is the completed back capture's; swap it in instead of capturing. */
    void turnSwap() { this.turnSwap = true; }

    /** After {@link #source}: this frame's list is a turn guard list; capture = it was culled for THIS view (may capture),
     * otherwise it is retained from the capture's view (composite or draw live, never capture). */
    void turnFrame(boolean capture, int padX, int padY) {
        this.turnFrame = true;
        this.turnCapture = capture;
        this.padX = padX;
        this.padY = padY;
    }

    /** True once after a retained turn frame could not composite: LodGpu must not retain its guard list again. */
    boolean takeTurnDrop() {
        boolean d = this.turnDrop;
        this.turnDrop = false;
        return d;
    }

    private void drop() {
        this.turnDrop = true;
        this.turnDrops++;
        this.invalidate();
        this.bypasses++;
    }

    void invalidate() {
        this.previous = null; this.observed = null; this.cullInputs = null; this.ready = false; this.backDone = -1;
    }

    /** Called after uploads, lightmap, fog and sampler updates, outside every render pass. */
    boolean prepare(ChunkSectionsToRender.DrawIndirect sections, GpuBufferSlice terrain,
                    GpuSampler sampler, GpuTextureView atlas, GpuTextureView lightmap, RenderTarget main) {
        this.ready = false;
        if (!enabled || this.failed || this.cullInputs == null || !Lod.draw || Lod.layerMask != 7
            || !System.getProperty("mc.lod.diag", "").isEmpty()
            || !System.getProperty("mcmetal.diag.terrainShader", "").isEmpty()
            || !TerrainShaderVariants.hasBuiltinTerrainSources() || !TerrainShaderVariants.hasBuiltinReuseSources()
            || main.getColorTexture() == null || main.getColorTexture().getFormat() != GpuFormat.RGBA8_UNORM
            || main.getDepthTexture() == null || main.getDepthTexture().getFormat() != GpuFormat.D32_FLOAT
            || !(atlas.texture() instanceof MetalTexture atlasTexture)
            || !(lightmap.texture() instanceof MetalTexture lightTexture)) {
            this.bypasses++;
            this.invalidate();
            return false;
        }
        this.attempts++;
        GpuBufferSlice exactProjection = net.minecraft.client.renderer.TesseraBloom.INSTANCE.exactProjection;   // TAA: unjittered
        byte[] projection = MetalLodReuseInputs.snapshot(exactProjection != null ? exactProjection : RenderSystem.getProjectionMatrixBuffer(), 64);
        byte[] transform = MetalLodReuseInputs.snapshot(terrain, 72); // mat4 + ivec2, excluding std140 padding
        float[] viewNow = null, projNow = null;
        if (this.turnFrame && transform != null && projection != null) {
            viewNow = floats(transform, 16);
            projNow = floats(projection, 16);
        }
        byte[] fog = MetalLodReuseInputs.snapshot(RenderSystem.getShaderFog(), 48);
        byte[] globals = RenderSystem.getGlobalSettingsUniform() == null ? null
            : MetalLodReuseInputs.snapshot(RenderSystem.getGlobalSettingsUniform().slice(), 48);
        if (projection == null || transform == null || fog == null || globals == null
            || !finite(projection, 0, 64) || !finite(transform, 0, 64) || !finite(fog, 0, 48)
            || !finite(globals, 16, 28)) {
            this.bypasses++;
            this.invalidate();
            return false;
        }
        if (viewNow != null) transform = Arrays.copyOfRange(transform, 64, 72);   // the rotation is admitted below, not keyed
        // Audited built-in terrain does not consume GlintAlpha, GameTime or MenuBlurRadius.
        // Custom terrain sources fail closed above. Camera, ScreenSize and UseRgss remain exact.
        Arrays.fill(globals, 12, 16, (byte)0);
        Arrays.fill(globals, 28, 32, (byte)0);
        Arrays.fill(globals, 40, 44, (byte)0);
        ArrayList<Object> identities = new ArrayList<>();
        ArrayList<Long> pipelineHandles = new ArrayList<>();
        identities.add(atlas);
        identities.add(lightmap);
        identities.add(sampler);
        identities.add(RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
        for (TerrainClusters.Draw draw : sections.lodDrawsForReuse()) {
            if ((draw.layer() & 7) == ChunkSectionLayer.TRANSLUCENT.ordinal()) continue;
            RenderPipeline pipeline = RenderPipelines.lodPipeline(draw.layer());
            if (pipeline == null) { this.invalidate(); this.bypasses++; return false; }
            CompiledRenderPipeline compiled = RenderSystem.getCompiledPipeline(pipeline);
            if (!(compiled instanceof FrontendRenderPipeline front) || compiled.isClosed()
                || !(front.backendRenderPipeline() instanceof MetalRenderPipeline metal)) {
                this.invalidate(); this.bypasses++; return false;
            }
            identities.add(compiled);
            pipelineHandles.add((long)draw.layer());
            pipelineHandles.add(metal.handle());
        }
        CompiledRenderPipeline composite = RenderSystem.getCompiledPipeline(RenderPipelines.LOD_REUSE_COMPOSITE);
        identities.add(composite);
        long[] values = new long[11 + pipelineHandles.size()];
        values[0] = this.geometry; values[1] = this.membership; values[2] = this.visibility;
        values[3] = atlasTexture.contentRevision();
        values[4] = lightTexture.contentRevision();
        if (net.minecraft.client.renderer.Tessera.ENABLED) values[4] = values[4] * 31L + net.minecraft.client.renderer.Tessera.INSTANCE.captureKey();   // soft
        values[5] = main.width; values[6] = main.height; values[7] = Lod.layerMask;
        values[8] = atlasTexture.handle();
        values[9] = lightTexture.handle();
        values[10] = net.minecraft.client.renderer.texture.TextureAtlas.contentGeneration; // atlas reloads: hard
        for (int i = 0; i < pipelineHandles.size(); i++) values[11+i] = pipelineHandles.get(i);
        Key current = new Key(new byte[][]{projection, transform, fog, globals, this.cullInputs}, values, identities.toArray());
        if (this.turnFrame && this.turnCapture && this.turnSwap) {
            if (this.fading || this.backDone < 0 || this.backDone != this.backCount || this.backKey == null
                || !current.sameHard(this.backKey, false) || !Arrays.equals(projNow, this.backProj)
                || this.padX != this.backPadX || this.padY != this.backPadY) {
                this.stripAborts++;
                this.drop();   // the guard list belongs to the back view: draw it live, never capture from it
                return false;
            }
            this.front ^= 1;
            this.backDone = -1;
            this.srcView = this.backView;
            this.srcProj = this.backProj;
            this.usedPadX = this.backPadX;
            this.usedPadY = this.backPadY;
            this.capturedNanos = this.backNanos;
            this.previous = current;
            this.swaps++;
            if (!this.admitWarp(viewNow, projNow, main.width, main.height)) { this.drop(); return false; }
            this.observed = current;
            this.hits++;
            this.ready = true;
            this.writeWarp();
            return true;
        }
        boolean hit = !this.fading && !(this.turnFrame && this.turnCapture) && current.same(this.previous);
        int soft = softMs;
        if (!hit && !(this.turnFrame && this.turnCapture) && soft > 0 && !this.fading && current.sameHard(this.previous) && System.nanoTime() - this.capturedNanos < soft * 1_000_000L) {
            hit = true;
            this.softHits++;
        }
        if (hit && this.turnFrame && !this.admitWarp(viewNow, projNow, main.width, main.height)) {
            this.drop();
            return false;
        }
        if (hit) {
            if (this.turnFrame && this.stripIndex >= 0) this.renderStrip(sections, current, terrain, viewNow, projNow, sampler, atlas, lightmap, main);
            this.observed = current;
            this.hits++;
            this.ready = true;
            this.writeWarp();
            return true;
        }
        if (this.turnFrame && !this.turnCapture) {
            this.drop();   // retained guard list: never capture from it (its cull belongs to the capture's view)
            return false;
        }
        // Admit only a stationary projection. A changed lightmap/atlas/fog must refresh NOW,
        // not draw uncached and then capture the same changed image on the following frame.
        // The cull-source admission independently guarantees this is a complete, unoccluded list.
        boolean stable = this.turnFrame ? this.turnCapture && projNow != null : current.sameView(this.observed);
        this.observed = current;
        if (!stable || this.fading) { this.previous = null; this.bypasses++; return false; }
        if (this.previous == null) this.misses[0]++;
        else if (!Arrays.deepEquals(current.bytes, this.previous.bytes)) this.misses[1]++;
        else if (!Arrays.equals(current.values, this.previous.values)) {
            this.misses[2]++;
            int n = Math.min(current.values.length, this.previous.values.length), at = n;
            for (int i = 0; i < n; i++) if (current.values[i] != this.previous.values[i]) { at = i; break; }
            this.valueMisses[Math.min(at, this.valueMisses.length - 1)]++;
        }
        else this.misses[3]++;
        this.previous = null; // Never retain a partial capture after an exception.
        try {
            int px = this.turnFrame ? this.padX : 0, py = this.turnFrame ? this.padY : 0;
            int tw = main.width + 2 * px, th = main.height + 2 * py;
            GpuBufferSlice guard = null;
            if (this.turnFrame) {
                float[] gp = projNow.clone();
                for (int col = 0; col < 4; col++) {
                    gp[col * 4] = (float)(gp[col * 4] * ((double)main.width / tw));
                    gp[col * 4 + 1] = (float)(gp[col * 4 + 1] * ((double)main.height / th));
                }
                if (this.guardProjection == null) this.guardProjection = new ProjectionMatrixBuffer("Farsight turn guard");
                guard = this.guardProjection.getBuffer(new Matrix4f().set(gp));
            }
            if (net.minecraft.client.renderer.Tessera.ENABLED && projNow != null && viewNow != null) {
                float[] cp = projNow.clone();
                for (int col = 0; col < 4; col++) {
                    cp[col * 4] = (float)(cp[col * 4] * ((double)main.width / tw));
                    cp[col * 4 + 1] = (float)(cp[col * 4 + 1] * ((double)main.height / th));
                }
                net.minecraft.client.renderer.Tessera.INSTANCE.prepareCapture(0, tw, th, cp, viewNow);
            }
            for (int i = 0; i < 2; i++) {
                TextureTarget target = this.target(this.front, i, tw, th);
                String label = "Farsight exact refresh " + ChunkSectionLayer.values()[i];
                try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                        () -> label, target.getColorTextureView(), Optional.of(new Vector4f()),
                        target.getDepthTextureView(), OptionalDouble.of(0.0))) {
                    RenderSystem.bindDefaultUniforms(pass);
                    if (guard != null) pass.setUniform("Projection", guard);
                    else if (exactProjection != null) pass.setUniform("Projection", exactProjection);
                    pass.setUniform("TerrainUniform", terrain);
                    pass.setUniform("Sampler0", atlas, sampler);
                    pass.setUniform("Sampler2", lightmap, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
                    if (net.minecraft.client.renderer.Tessera.ENABLED) net.minecraft.client.renderer.Tessera.INSTANCE.bindCapture(pass, 0);
                    sections.renderLodUncached(ChunkSectionLayer.values()[i], pass);
                }
            }
            this.refreshes++;
            this.backDone = -1;
            this.usedPadX = px;
            this.usedPadY = py;
            this.srcView = viewNow;
            this.srcProj = projNow;
            this.warp = false;
            if (this.turnFrame) this.turnCaptures++;
            this.capturedNanos = System.nanoTime();
            this.previous = this.fading ? null : current;
            this.ready = true;
            this.writeWarp();
            return true;
        } catch (RuntimeException | LinkageError e) {
            this.failed = true;
            this.invalidate();
            Lod.LOGGER.warn("[lod] exact reuse disabled after failed capture; original draws retained", e);
            return false;
        }
    }

    boolean composite(ChunkSectionsToRender.DrawIndirect sections, ChunkSectionLayer layer, RenderPass pass) {
        int i = layer.ordinal();
        if (!this.ready || i < 0 || i >= 2) return false;
        TextureTarget target = this.sets[this.front][i];
        pass.setPipeline(RenderSystem.getCompiledPipeline(RenderPipelines.LOD_REUSE_COMPOSITE));
        GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        pass.setUniform("ReuseColor", target.getColorTextureView(), nearest);
        pass.setUniform("ReuseDepth", target.getDepthTextureView(), nearest);
        pass.setUniform("ReuseWarp", this.warpUbo.currentBuffer());
        pass.draw(3, 1, 0, 0);
        return true;
    }

    String statsJson() {
        return "{\"enabled\":"+enabled+",\"attempts\":"+this.attempts+",\"hits\":"+this.hits
            +",\"turn\":"+turn+",\"turnCaptures\":"+this.turnCaptures+",\"warps\":"+this.warps+",\"turnDrops\":"+this.turnDrops+",\"strips\":"+this.strips+",\"swaps\":"+this.swaps+",\"stripAborts\":"+this.stripAborts
            +",\"refreshes\":"+this.refreshes+",\"softHits\":"+this.softHits+",\"softMs\":"+softMs+",\"bypasses\":"+this.bypasses+",\"misses\":"+Arrays.toString(this.misses)+",\"valueMisses\":"+Arrays.toString(this.valueMisses)+"}";
    }

    /** Retained turn frame hit: exact offset for the capture's own camera, else a pure-rotation warp whose every screen
     * edge sample lands inside the guard image. False = cannot composite (caller draws live and drops the guard list). */
    private boolean admitWarp(float[] viewNow, float[] projNow, int width, int height) {
        if (viewNow == null || this.srcView == null || this.usedPadX == 0 && this.usedPadY == 0
            || !Arrays.equals(projNow, this.srcProj)) return false;
        if (Arrays.equals(viewNow, this.srcView)) {
            this.warp = false;
            return true;
        }
        LodFarCamera.Position o = new LodFarCamera.Position(0, 0, 0);
        LodFarCamera.Viewport vp = new LodFarCamera.Viewport(width, height);
        LodFarCamera.Result r = LodFarCamera.prepare(new LodFarCamera.Camera(this.srcProj, this.srcView, o, o, vp),
            new LodFarCamera.Camera(projNow, viewNow, o, o, vp), this.usedPadX, this.usedPadY, 32768, Long.MAX_VALUE / 2);
        if (r.camera() == null) return false;
        for (int k = 0; k < 9; k++) {
            double x = k % 3 == 0 ? 0.5 : k % 3 == 1 ? width * 0.5 : width - 0.5;
            double y = k / 3 == 0 ? 0.5 : k / 3 == 1 ? height * 0.5 : height - 0.5;
            if (r.camera().currentPixelToSource(x, y, 0.5).rejection() != LodFarCamera.PixelReject.NONE) return false;
        }
        double[] c2s = r.camera().currentToSourceClip(), s2c = r.camera().sourceToCurrentClip();
        float[] a = new float[16], b = new float[16];
        for (int k = 0; k < 16; k++) { a[k] = (float)c2s[k]; b[k] = (float)s2c[k]; }
        this.currentToSource.set(a);
        this.sourceToCurrent.set(b);
        this.warp = true;
        this.warps++;
        return true;
    }

    /** Outside every render pass (prepare): the composite's ReuseWarp block for this frame. */
    private void writeWarp() {
        TextureTarget target = this.sets[this.front][0];
        if (this.warpUbo == null) this.warpUbo = new MappableRingBuffer(() -> "Farsight reuse warp", 130, 160);
        this.warpUbo.rotate();
        try (GpuBufferSlice.MappedView view = this.warpUbo.currentBuffer().map(false, true)) {
            Std140Builder.intoBuffer(view.data())
                .putMat4f(this.currentToSource)
                .putMat4f(this.sourceToCurrent)
                .putVec4(target.width - 2 * this.usedPadX, target.height - 2 * this.usedPadY, target.width, target.height)
                .putVec4(this.usedPadX, this.usedPadY, this.warp ? 1.0F : 0.0F, 0.0F);
        }
    }

    private TextureTarget target(int set, int layer, int width, int height) {
        TextureTarget target = this.sets[set][layer];
        if (target == null || target.width != width || target.height != height) {
            if (target != null) target.destroyBuffers();
            target = new TextureTarget("Farsight exact " + set + "/" + layer, width, height, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
            this.sets[set][layer] = target;
        }
        return target;
    }

    /** One strip of the back capture: strip 0 freezes the back view (its terrain transform bytes, guard projection, pads
     * and hard key) and clears; later strips need every one of them unchanged, else the back capture is abandoned (the
     * swap then drops to a live draw). Strips overlap only where a cluster straddles two sub-frusta: opaque, same depth. */
    private void renderStrip(ChunkSectionsToRender.DrawIndirect sections, Key current, GpuBufferSlice terrain, float[] viewNow,
                             float[] projNow, GpuSampler sampler, GpuTextureView atlas, GpuTextureView lightmap, RenderTarget main) {
        int k = this.stripIndex;
        List<TerrainClusters.Draw> draws = this.stripDraws;
        if (draws == null || viewNow == null || projNow == null) { this.backDone = -1; return; }
        if (k == 0) {
            byte[] bytes = MetalLodReuseInputs.snapshot(terrain, (int)terrain.length());
            if (bytes == null) { this.backDone = -1; return; }
            this.backTerrain = bytes;
            this.backKey = current;
            this.backView = viewNow;
            this.backProj = projNow;
            this.backPadX = this.padX;
            this.backPadY = this.padY;
            this.backNanos = System.nanoTime();
            this.backCount = this.stripCount;
            this.backDone = 0;
        } else if (this.backDone != k || this.stripCount != this.backCount || !Arrays.equals(projNow, this.backProj)
                || this.padX != this.backPadX || this.padY != this.backPadY || !current.sameHard(this.backKey, false)) {
            if (this.backDone >= 0) this.stripAborts++;
            this.backDone = -1;
            return;
        }
        try {
            int tw = main.width + 2 * this.backPadX, th = main.height + 2 * this.backPadY;
            float[] gp = this.backProj.clone();
            for (int col = 0; col < 4; col++) {
                gp[col * 4] = (float)(gp[col * 4] * ((double)main.width / tw));
                gp[col * 4 + 1] = (float)(gp[col * 4 + 1] * ((double)main.height / th));
            }
            if (this.backProjection == null) this.backProjection = new ProjectionMatrixBuffer("Farsight turn back guard");
            GpuBufferSlice guard = this.backProjection.getBuffer(new Matrix4f().set(gp));
            if (this.backTerrainUbo == null) this.backTerrainUbo = new MappableRingBuffer(() -> "Farsight turn back terrain", 130, this.backTerrain.length);
            this.backTerrainUbo.rotate();
            try (GpuBufferSlice.MappedView view = this.backTerrainUbo.currentBuffer().map(false, true)) {
                view.data().put(this.backTerrain);
            }
            GpuBufferSlice back = this.backTerrainUbo.currentBuffer().slice(0L, this.backTerrain.length);
            if (net.minecraft.client.renderer.Tessera.ENABLED) net.minecraft.client.renderer.Tessera.INSTANCE.prepareCapture(1, tw, th, gp, this.backView);
            int set = this.front ^ 1;
            for (int i = 0; i < 2; i++) {
                TextureTarget target = this.target(set, i, tw, th);
                ChunkSectionLayer layer = ChunkSectionLayer.values()[i];
                try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                        () -> "Farsight turn strip " + layer, target.getColorTextureView(),
                        k == 0 ? Optional.of(new Vector4f()) : Optional.empty(),
                        target.getDepthTextureView(), k == 0 ? OptionalDouble.of(0.0) : OptionalDouble.empty())) {
                    RenderSystem.bindDefaultUniforms(pass);
                    pass.setUniform("Projection", guard);
                    pass.setUniform("TerrainUniform", back);
                    pass.setUniform("Sampler0", atlas, sampler);
                    pass.setUniform("Sampler2", lightmap, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
                    if (net.minecraft.client.renderer.Tessera.ENABLED) net.minecraft.client.renderer.Tessera.INSTANCE.bindCapture(pass, 1);
                    sections.renderLodList(draws, layer, pass);
                }
            }
            this.backDone = k + 1;
            this.strips++;
        } catch (RuntimeException | LinkageError e) {
            this.backDone = -1;
            this.failed = true;
            this.invalidate();
            Lod.LOGGER.warn("[lod] exact reuse disabled after failed turn strip; original draws retained", e);
        }
    }

    private static float[] floats(byte[] bytes, int n) {
        java.nio.FloatBuffer f = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer();
        float[] out = new float[n];
        f.get(out);
        return out;
    }

    @Override public void close() {
        this.invalidate();
        if (this.guardProjection != null) { this.guardProjection.close(); this.guardProjection = null; }
        if (this.warpUbo != null) { this.warpUbo.close(); this.warpUbo = null; }
        if (this.backProjection != null) { this.backProjection.close(); this.backProjection = null; }
        if (this.backTerrainUbo != null) { this.backTerrainUbo.close(); this.backTerrainUbo = null; }
        for (TextureTarget[] set : this.sets) for (int i=0; i<2; i++) if (set[i] != null) { set[i].destroyBuffers(); set[i]=null; }
    }

    private static boolean finite(byte[] bytes, int start, int end) {
        java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.nativeOrder());
        for (int i=start; i<end; i+=4) if (!Float.isFinite(b.getFloat(i))) return false;
        return true;
    }

    /** Explicit bytes + identity comparison: hashes can never admit reuse. */
    static final class Key {
        final byte[][] bytes;
        final long[] values;
        final Object[] identities;
        Key(byte[][] bytes, long[] values, Object[] identities) {
            this.bytes=bytes; this.values=values; this.identities=identities;
        }
        boolean same(Key other) {
            if (other == null || !Arrays.deepEquals(this.bytes, other.bytes) || !Arrays.equals(this.values, other.values)
                || this.identities.length != other.identities.length) return false;
            for (int i=0; i<this.identities.length; i++) if (this.identities[i] != other.identities[i]) return false;
            return true;
        }
        /** {@link #same} without the soft inputs: fog bytes (2), atlas/lightmap revisions and handles (values 3, 4, 8, 9),
         * atlas/lightmap view identities (0, 1). */
        boolean sameHard(Key other) { return this.sameHard(other, true); }

        /** cull = false also ignores the cull-input bytes (4): a back capture's strips are culled per sub-frustum. */
        boolean sameHard(Key other, boolean cull) {
            if (other == null || this.bytes.length != other.bytes.length || this.values.length != other.values.length
                || this.identities.length != other.identities.length) return false;
            for (int i=0; i<this.bytes.length; i++) if (i != 2 && (cull || i != 4) && !Arrays.equals(this.bytes[i], other.bytes[i])) return false;
            for (int i=0; i<this.values.length; i++) if (i != 3 && i != 4 && i != 8 && i != 9 && this.values[i] != other.values[i]) return false;
            for (int i=2; i<this.identities.length; i++) if (this.identities[i] != other.identities[i]) return false;
            return true;
        }
        boolean sameView(Key other) {
            return other != null && Arrays.equals(this.bytes[0], other.bytes[0])
                && Arrays.equals(this.bytes[1], other.bytes[1]) && Arrays.equals(this.bytes[4], other.bytes[4])
                && this.values[5] == other.values[5] && this.values[6] == other.values[6];
        }
    }
}
