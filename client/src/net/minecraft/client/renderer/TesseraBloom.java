package net.minecraft.client.renderer;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.util.Optional;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

/**
 * Stage 4 (Tessera): bloom: the whole world image, in linear light, blurred through a small mip chain
 * (1/4 to 1/32 of the frame: dual-filter down, tent up, the levels summed) and mixed back over the frame at a small
 * strength before the GUI, which softens everything slightly and lets bright light spill.
 */
public final class TesseraBloom {
    /** Probe: bloom on (true) or off. */
    public static volatile boolean enabled = !"false".equals(System.getProperty("mc.shaders.bloom"));
    /** Probe: ambient occlusion on (true) or off. */
    /** Probes (cost split): the down/up chain after the first step, and the final add onto the frame. */
    public static volatile boolean probeChain = true;
    public static volatile boolean probeComposite = true;
    public static volatile boolean probeTiles = true;     // probes: the tiles pass, the combine pass
    public static volatile boolean probeCombine = true;
    /** Probe: the AO reads the reflection source's half-resolution distances (true) or the full-resolution depth. */
    public static volatile boolean aoFromDistance = !"false".equals(System.getProperty("mc.shaders.aoDist"));
    /** The AO is written by the reflection source's pass as a second target (true) or by its own pass. */
    public static volatile boolean aoInPass = !"false".equals(System.getProperty("mc.shaders.aoInPass"));
    public static volatile boolean ambientOcclusion = !"false".equals(System.getProperty("mc.shaders.ao"));
    public static final TesseraBloom INSTANCE = new TesseraBloom();
    private static final int LEVELS = 4;
    /** The coarser bloom levels as tiles of one texture, two passes (true), or the down / up chain of six passes. */
    /** The screen-effects pass clears instead of loading the frame when its first draw overwrites it (probe). */
    public static volatile boolean skipLoad = !"false".equals(System.getProperty("mc.shaders.skipLoad"));
    public static volatile boolean bloomTiles = !"false".equals(System.getProperty("mc.shaders.bloomTiles"));
    private @Nullable TextureTarget tiles;
    private final @Nullable TextureTarget[] levels = new TextureTarget[LEVELS];
    private int width;
    private int height;
    private @Nullable TextureTarget ao;
    private @Nullable TextureTarget reflect;
    /** The sun's visibility for the glare (1x1, r). */
    private @Nullable TextureTarget sunVis;
    private @Nullable TextureTarget shafts;
    /** Probe: the analytic sun glare (needs the reflection source for the sun's visibility). */
    public static volatile boolean glare = !"false".equals(System.getProperty("mc.shaders.glare"));
    /** Probe: water reflections of the world (screen-space, previous frame) on (true) or off. */
    public static volatile boolean reflections = !"false".equals(System.getProperty("mc.shaders.reflections"));

    private TesseraBloom() {
    }

    /**
     * The screen effects on the world image, right after the level and before the hand / 3D HUD: AO (from the world
     * depth) and bloom (from the world colour) are prepared in their small targets, then one pass on the frame (colour
     * loaded, depth cleared to 0 like the hand pass that follows, or loaded when a post effect needs it) multiplies the AO
     * and adds the bloom. That pass has the hand pass's attachments, so the decoder continues it in the same encoder
     * (the frame is stored and reloaded once for both effects, not once per effect).
     */
    public void afterWorld(final RenderTarget main, final boolean keepDepth) {
        if (!Tessera.active || (!enabled && !ambientOcclusion && !reflections)) {
            return;
        }
        GpuTextureView frame = main.getColorTextureView();
        GpuTextureView depth = main.getDepthTextureView();
        if (frame == null || depth == null) {
            return;
        }
        GpuSampler linear = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        // the half-resolution world image: next frame's water reflections, and the bloom's source
        GpuTextureView bloomSource = frame;
        if (reflections) {
            int w = Math.max(1, (main.width + 1) / 2);
            int h = Math.max(1, (main.height + 1) / 2);
            if (this.reflect == null || this.reflect.width != w || this.reflect.height != h) {
                if (this.reflect != null) {
                    this.reflect.destroyBuffers();
                }
                this.reflect = new TextureTarget("Tessera reflection source", w, h, GpuFormat.RGBA16_FLOAT, null);
            }
            boolean aoInline = ambientOcclusion && aoInPass;
            if (aoInline) {
                this.ensureAo(main);
            }
            var descriptor = com.mojang.renderpearl.api.commands.RenderPassDescriptor.builder(() -> "Tessera reflection source")
                .withColorAttachment(this.reflect.getColorTextureView(), Optional.empty());
            if (aoInline) {
                descriptor.withColorAttachment(this.ao.getColorTextureView(), Optional.empty());
            }
            try (RenderPass pass = encoder.createRenderPass(descriptor.build())) {
                pass.setPipeline(RenderSystem.getCompiledPipeline(aoInline ? RenderPipelines.TESSERA_REFLECT_SOURCE_AO : RenderPipelines.TESSERA_REFLECT_SOURCE));
                RenderSystem.bindDefaultUniforms(pass);
                Tessera.INSTANCE.bindTerrain(pass, true);
                GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
                pass.setUniform("InSampler", frame, nearest);
                pass.setUniform("DepthSampler", depth, nearest);
                pass.draw(3, 1, 0, 0);
            }
            Tessera.INSTANCE.reflectWritten(this.reflect.getColorTextureView(), main.width, main.height);
            // god rays: the shadowed share of the light shafts along each pixel's ray, for the next frame
            if (Tessera.shadowShafts && Tessera.INSTANCE.shaftsVisible()) {
                int sw = Math.max(1, (w + 1) / 2);
                int sh = Math.max(1, (h + 1) / 2);
                if (this.shafts == null || this.shafts.width != sw || this.shafts.height != sh) {
                    if (this.shafts != null) {
                        this.shafts.destroyBuffers();
                    }
                    this.shafts = new TextureTarget("Tessera shafts", sw, sh, GpuFormat.RGBA8_UNORM, null);
                }
                try (RenderPass pass = encoder.createRenderPass(() -> "Tessera shafts", this.shafts.getColorTextureView(), Optional.empty())) {
                    pass.setPipeline(RenderSystem.getCompiledPipeline(RenderPipelines.TESSERA_SHAFTS));
                    RenderSystem.bindDefaultUniforms(pass);
                    Tessera.INSTANCE.bindTerrain(pass, true);
                    Tessera.INSTANCE.unbindShafts(pass);
                    pass.setUniform("DistSampler", this.reflect.getColorTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
                    pass.draw(3, 1, 0, 0);
                }
                Tessera.INSTANCE.shaftsWritten(this.shafts.getColorTextureView());
            } else {
                Tessera.INSTANCE.shaftsWritten(null);
            }
            bloomSource = this.reflect.getColorTextureView();
        }
        boolean ao = ambientOcclusion;
        if (ao && !(aoInPass && reflections)) {
            this.ensureAo(main);
            try (RenderPass pass = encoder.createRenderPass(() -> "Tessera AO", this.ao.getColorTextureView(), Optional.empty())) {
                boolean fromDist = aoFromDistance && this.reflect != null && reflections;
                pass.setPipeline(RenderSystem.getCompiledPipeline(fromDist ? RenderPipelines.TESSERA_AO_DIST : RenderPipelines.TESSERA_AO));
                RenderSystem.bindDefaultUniforms(pass);
                Tessera.INSTANCE.bindTerrain(pass, true);
                pass.setUniform("InSampler", fromDist ? this.reflect.getColorTextureView() : depth, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
                pass.draw(3, 1, 0, 0);
            }
        }
        boolean bloom = enabled;
        if (bloom) {
            if (this.levels[0] == null || main.width != this.width || main.height != this.height) {
                this.resize(main.width, main.height);
            }
            this.draw(encoder, this.levels[0].getColorTextureView(), true, RenderPipelines.TESSERA_BLOOM_BRIGHT, bloomSource, linear);
            if (probeChain && bloomTiles) {
                // the coarser levels as tiles of one texture (one pass), added back onto the first level (one pass)
                if (probeTiles) this.draw(encoder, this.tiles.getColorTextureView(), false, RenderPipelines.TESSERA_BLOOM_TILES, this.levels[0].getColorTextureView(), linear);
                if (probeCombine) this.draw(encoder, this.levels[0].getColorTextureView(), false, RenderPipelines.TESSERA_BLOOM_COMBINE, this.tiles.getColorTextureView(), linear);
            } else if (probeChain) {
                for (int i = 1; i < LEVELS; i++) {
                    this.draw(encoder, this.levels[i].getColorTextureView(), true, RenderPipelines.TESSERA_BLOOM_DOWN, this.levels[i - 1].getColorTextureView(), linear);
                }
                for (int i = LEVELS - 1; i > 0; i--) {
                    this.draw(encoder, this.levels[i - 1].getColorTextureView(), false, RenderPipelines.TESSERA_BLOOM_UP, this.levels[i].getColorTextureView(), linear);
                }
            }
            bloom = probeComposite;
        }
        // the sun glare's screen box (scissor) and, when there is one, the sun's visibility into one pixel
        int[] glareBox = reflections && glare ? Tessera.INSTANCE.sunGlareBox(main.width, main.height) : null;
        if (glareBox != null) {
            if (this.sunVis == null) {
                this.sunVis = new TextureTarget("Tessera sun visibility", 1, 1, GpuFormat.RGBA8_UNORM, null);
            }
            try (RenderPass pass = encoder.createRenderPass(() -> "Tessera sun visibility", this.sunVis.getColorTextureView(), Optional.empty())) {
                pass.setPipeline(RenderSystem.getCompiledPipeline(RenderPipelines.TESSERA_SUN_VIS));
                Tessera.INSTANCE.bindTerrain(pass, true);
                pass.setUniform("InSampler", this.reflect.getColorTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
                pass.draw(3, 1, 0, 0);
            }
        }
        // the TAA resolve (before the bloom, AO and glare: those are smooth and stay out of the history); its result is
        // copied back as the screen-effects pass's first draw (no extra pass)
        TextureTarget resolved = this.jittered && taaResolve && this.reflect != null ? this.temporal(encoder, main, frame, ao) : null;
        this.jittered = false;
        if (resolved != null && ao && aoInTaa) {
            ao = false;   // already in the TAA result
        }
        // with the TAA result composited by the bloom (every pixel written, no blend) the old frame is not loaded: cleared
        boolean overwrite = resolved != null && bloom && skipLoad;
        try (RenderPass pass = encoder.createRenderPass(() -> "Tessera screen effects", frame, overwrite ? Optional.of(new Vector4f()) : Optional.empty(), depth,
                keepDepth ? java.util.OptionalDouble.empty() : java.util.OptionalDouble.of(0.0))) {
            // with the bloom on, its composite writes the TAA result itself (below)
            if (resolved != null && !bloom) {
                pass.setPipeline(RenderSystem.getCompiledPipeline(RenderPipelines.TESSERA_COPY));
                pass.setUniform("InSampler", resolved.getColorTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
                pass.draw(3, 1, 0, 0);
            }
            if (ao && !bloom) {
                pass.setPipeline(RenderSystem.getCompiledPipeline(RenderPipelines.TESSERA_AO_APPLY));
                pass.setUniform("InSampler", this.ao.getColorTextureView(), linear);
                pass.draw(3, 1, 0, 0);
            }
            // the bloom first: its source is copied before the sun glare, so mixing it after would dim the glare
            if (bloom) {
                // with the AO multiplied in when it is on (one full-resolution blend for both)
                pass.setPipeline(RenderSystem.getCompiledPipeline(resolved != null
                    ? (ao ? RenderPipelines.TESSERA_BLOOM_COMPOSITE_AO_TAA : RenderPipelines.TESSERA_BLOOM_COMPOSITE_TAA)
                    : (ao ? RenderPipelines.TESSERA_BLOOM_COMPOSITE_AO : RenderPipelines.TESSERA_BLOOM_COMPOSITE)));
                pass.setUniform("InSampler", this.levels[0].getColorTextureView(), linear);
                if (ao) {
                    pass.setUniform("AoSampler", this.ao.getColorTextureView(), linear);
                }
                if (resolved != null) {
                    pass.setUniform("BaseSampler", resolved.getColorTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
                }
                pass.draw(3, 1, 0, 0);
            }
            if (glareBox != null) {
                GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
                pass.setPipeline(RenderSystem.getCompiledPipeline(RenderPipelines.TESSERA_SUN_GLARE));
                Tessera.INSTANCE.bindTerrain(pass, true);
                pass.setUniform("InSampler", this.reflect.getColorTextureView(), nearest);
                pass.setUniform("SunVisSampler", this.sunVis.getColorTextureView(), nearest);
                pass.enableScissor(glareBox[0], glareBox[1], glareBox[2], glareBox[3]);
                pass.draw(3, 1, 0, 0);
                pass.disableScissor();
            }
        }
        if (resolved == null && fxaa) {
            this.antiAlias(encoder, main, frame, linear);
        }
    }

    /** TAA (default on, -Dmc.shaders.taa=false: off): the projection is jittered and the frames are blended over time. */
    public static volatile boolean taa = !"false".equals(System.getProperty("mc.shaders.taa"));
    /** The AO is multiplied in by the TAA before its blend (true: filtered over time like the frame) or after it. */
    public static volatile boolean aoInTaa = !"false".equals(System.getProperty("mc.shaders.aoInTaa"));
    /** Probe: false = jitter without the resolve (separates the two costs). */
    public static volatile boolean taaResolve = true;
    /** Probe: false keeps the TAA path but stops the projection jitter. */
    public static volatile boolean jitter = true;
    private final @Nullable TextureTarget[] history = new TextureTarget[2];
    private int historyIndex;
    private boolean historyValid;
    private boolean jittered;
    /**
     * This frame's projection without the TAA jitter while it is jittered (else null): Farsight's reuse keys and captures its
     * far image with it (a jittered key changes every frame, so nothing was ever reused and the far terrain redrew each frame).
     */
    public com.mojang.renderpearl.api.buffers.@Nullable GpuBufferSlice exactProjection;
    private int jitterFrame;

    /**
     * GameRenderer: this frame's sub-pixel projection offset (NDC x, y; Halton 2, 3 over 8 frames), or null when the TAA
     * is off. The offset applies to the rasterised projection only: Tessera's matrices (reprojection, sky rays) stay
     * unjittered.
     */
    public float @Nullable [] taaJitter(final int width, final int height) {
        if (!taa || !Tessera.active || !reflections || width <= 0 || height <= 0) {
            this.historyValid = false;
            return null;
        }
        this.jittered = true;
        if (!jitter) return new float[] {0.0F, 0.0F};   // probe: the resolve without the sub-pixel offsets
        int n = (this.jitterFrame++ & 7) + 1;
        return new float[] {(halton(n, 2) - 0.5F) * 2.0F / width, (halton(n, 3) - 0.5F) * 2.0F / height};
    }

    private static float halton(int index, final int base) {
        float f = 1.0F, r = 0.0F;
        while (index > 0) {
            f /= base;
            r += f * (index % base);
            index /= base;
        }
        return r;
    }

    /** The jittered frame resolved against the reprojected history into the other history target (returned). */
    private TextureTarget temporal(final CommandEncoder encoder, final RenderTarget main, final GpuTextureView frame, final boolean ao) {
        for (int i = 0; i < 2; i++) {
            if (this.history[i] == null || this.history[i].width != main.width || this.history[i].height != main.height) {
                if (this.history[i] != null) {
                    this.history[i].destroyBuffers();
                }
                this.history[i] = new TextureTarget("Tessera TAA history " + i, main.width, main.height, GpuFormat.RGBA8_UNORM, null);
                this.historyValid = false;
            }
        }
        TextureTarget prev = this.history[this.historyIndex];
        TextureTarget next = this.history[this.historyIndex ^ 1];
        GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        try (RenderPass pass = encoder.createRenderPass(() -> "Tessera TAA", next.getColorTextureView(), Optional.empty())) {
            boolean withAo = ao && aoInTaa;
            pass.setPipeline(RenderSystem.getCompiledPipeline(withAo ? RenderPipelines.TESSERA_TAA_AO : RenderPipelines.TESSERA_TAA));
            Tessera.INSTANCE.bindTerrain(pass, true);
            if (withAo) {
                pass.setUniform("AoSampler", this.ao.getColorTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            }
            pass.setUniform("InSampler", frame, nearest);
            pass.setUniform("HistSampler", this.historyValid ? prev.getColorTextureView() : frame, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            pass.setUniform("DistSampler", this.reflect.getColorTextureView(), nearest);
            pass.draw(3, 1, 0, 0);
        }
        this.historyIndex ^= 1;
        this.historyValid = true;
        return next;
    }

    /**
     * Opt-in (-Dmc.shaders.fxaa=true): FXAA and sharpening on the world image (after the screen effects, before the hand /
     * GUI). Off by default: at a 3024x1964 frame the copy plus the full-screen pass cost 3.6 ms (187 -> 134 FPS, fxab2).
     */
    public static volatile boolean fxaa = "true".equals(System.getProperty("mc.shaders.fxaa"));
    private @Nullable TextureTarget aaCopy;

    /** FXAA: the frame copied, then its edges filtered back onto it (non-edge pixels are not written). */
    private void antiAlias(final CommandEncoder encoder, final RenderTarget main, final GpuTextureView frame, final GpuSampler linear) {
        if (this.aaCopy == null || this.aaCopy.width != main.width || this.aaCopy.height != main.height) {
            if (this.aaCopy != null) {
                this.aaCopy.destroyBuffers();
            }
            this.aaCopy = new TextureTarget("Tessera FXAA source", main.width, main.height, GpuFormat.RGBA8_UNORM, null);
        }
        encoder.copyTextureToTexture(frame.texture(), this.aaCopy.getColorTexture(), 0, 0, 0, 0, 0, main.width, main.height);
        try (RenderPass pass = encoder.createRenderPass(() -> "Tessera FXAA", frame, Optional.empty())) {
            pass.setPipeline(RenderSystem.getCompiledPipeline(RenderPipelines.TESSERA_FXAA));
            pass.setUniform("InSampler", this.aaCopy.getColorTextureView(), linear);
            pass.draw(3, 1, 0, 0);
        }
    }

    private void ensureAo(final RenderTarget main) {
        int w = Math.max(1, (main.width + 1) / 2);
        int h = Math.max(1, (main.height + 1) / 2);
        if (this.ao == null || this.ao.width != w || this.ao.height != h) {
            if (this.ao != null) {
                this.ao.destroyBuffers();
            }
            this.ao = new TextureTarget("Tessera AO", w, h, GpuFormat.RGBA8_UNORM, null);
        }
    }

    private void draw(final CommandEncoder encoder, final GpuTextureView target, final boolean clear, final RenderPipeline pipeline,
        final GpuTextureView source, final GpuSampler sampler) {
        try (RenderPass pass = encoder.createRenderPass(() -> "Tessera bloom", target, clear ? Optional.of(new Vector4f()) : Optional.empty())) {
            pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline));
            pass.setUniform("InSampler", source, sampler);
            pass.draw(3, 1, 0, 0);
        }
    }

    private void resize(final int width, final int height) {
        this.close();
        this.width = width;
        this.height = height;
        // the first level's width (tiles 1/2 + 1/4 + 1/8 of it and gaps fit), the second level's height
        this.tiles = new TextureTarget("Tessera bloom tiles", Math.max(1, width >> 2), Math.max(1, (width >> 2 > 0 ? height >> 2 : 1) >> 1), GpuFormat.RGBA16_FLOAT, null);
        for (int i = 0; i < LEVELS; i++) {
            int shift = i + 2;
            this.levels[i] = new TextureTarget("Tessera bloom " + i, Math.max(1, width >> shift), Math.max(1, height >> shift), GpuFormat.RGBA16_FLOAT, null);
        }
    }

    public void close() {
        if (this.tiles != null) {
            this.tiles.destroyBuffers();
            this.tiles = null;
        }
        for (int i = 0; i < LEVELS; i++) {
            if (this.levels[i] != null) {
                this.levels[i].destroyBuffers();
                this.levels[i] = null;
            }
        }
    }
}
