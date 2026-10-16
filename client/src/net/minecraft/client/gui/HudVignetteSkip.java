package net.minecraft.client.gui;

import com.mojang.blaze3d.pipeline.PipelineCache;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.state.gui.BlitRenderState;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;
import org.joml.Matrix3x2f;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * W1 of docs/stage2/STAGE2-PLAN.md: the HUD vignette's full-screen quad is not drawn when the draw is an exact no-op.
 * Toggle {@code -Dmc.hud.vignetteSkipNoop} (default ON; {@code false} or {@code 0} = the vanilla draw every frame).
 * {@link #enabled} is volatile so a test harness or debugger can flip it; {@code Hud.extractVignette} reads it once per
 * frame. Used only by the patched {@code Hud.extractVignette}; nothing else changes.
 *
 * <p><b>Why the vanilla draw is a no-op.</b> {@code RenderPipelines.VIGNETTE} = GUI_TEXTURED_SNIPPET (shaders
 * {@code core/position_tex_color}, POSITION_TEX_COLOR quads, no depth/stencil state, cull default) with one RGBA8_UNORM
 * colour target, write mask 15 (RGBA) and blend {@code (ZERO, ONE_MINUS_SRC_COLOR, ADD; ZERO, ONE, ADD)}. Its fragment
 * colour is {@code texture(Sampler0, uv) * vertexColor * ColorModulator} (fragments with alpha 0 are discarded). The
 * vertex colour is the packed ARGB {@code color} as UNORM8, so when {@code color & 0xFFFFFF == 0} the source RGB is
 * exactly +-0 (finite texel x 0, times the GUI's ColorModulator (1,1,1,1)), and the blend writes
 * {@code rgb = src*0 + dst*(1 - 0) = dst}, {@code a = src*0 + dst*1 = dst}: every byte of the target keeps its value
 * (UNORM8 -> float/half -> UNORM8 round-trips exactly; verified on Metal for RGBA8 and the present-fusion BGRA8 drawable
 * by tools/HudVignetteTest.java). No depth or stencil is written. Vanilla computes the colour as
 * {@code ARGB.colorFromFloat(1, b, b, b)} with {@code b = clamp(vignetteBrightness, 0, 1)} outside the world-border
 * warning, so the RGB is 0 exactly when {@code ARGB.as8BitChannel(b) = floor(b * 255) == 0}, i.e. {@code b < 1/255}
 * (about 552 ticks after joining a fully lit place, since {@code vignetteBrightness} starts at 1 and decays 1 %/tick).
 *
 * <p><b>What is skipped, and what is not.</b> {@code extractVignette} mutates nothing of its own (the
 * {@code vignetteBrightness} update is in {@code Hud.tick}, unchanged; the world-border reads are getters), so its only
 * effects are those of {@code graphics.blit}: the lazy {@code TextureManager.getTexture} lookup, and adding a
 * {@link BlitRenderState} to the frame's {@link GuiRenderState}. The latter matters beyond the draw: the element takes
 * part in the GUI layering (its full-screen bounds decide, through {@code lastElementBounds} and the node intersection
 * search, which node later elements of the stratum land in, and {@code lastElementBounds} even carries into the next
 * stratum and the next frame), in the per-node sort and in draw batching. Dropping the element would change those.
 * {@link #blitWithoutDraw} therefore performs the same blit with the same inputs, in the same {@link GuiRenderState}, but
 * the element it adds ({@link NoDrawElement}) builds no vertices: same pipeline, texture setup, scissor and bounds, so
 * layering, sort, batching and the draw list of every other element are exactly vanilla's; its own draw has no vertices,
 * so {@code StagedVertexBuffer.getExecuteInfo} returns null and {@code GuiRenderer.executeDraw} issues nothing for it.
 *
 * <p><b>Guards</b> (each falls back to the vanilla draw): the world-border warning path never skips (conservative: its
 * colour can also quantize to 0, but W1 is defined on the plain path); the shader sources the VIGNETTE pipeline was
 * compiled from (the current pipeline cache, i.e. the last resource reload) must equal the built-in ones (a resource pack
 * may override {@code core/position_tex_color}, and then colour 0 need not be a no-op), checked once per pipeline cache;
 * there is no current cache only before the first reload, when no world (and no HUD) is rendered; and the private fields
 * read below must be reachable.
 */
public final class HudVignetteSkip {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** W1 toggle, {@code -Dmc.hud.vignetteSkipNoop} (default true). */
    public static volatile boolean enabled = flag("mc.hud.vignetteSkipNoop", true);

    // GuiGraphicsExtractor.guiRenderState, GuiGraphicsExtractor.scissorStack.peek(), RenderSystem.currentPipelineCache,
    // PipelineCache.shaderSource. Null when not reachable (then the skip never happens).
    private static final @Nullable MethodHandle GUI_RENDER_STATE;
    private static final @Nullable MethodHandle SCISSOR;
    private static final @Nullable MethodHandle CURRENT_PIPELINE_CACHE;
    private static final @Nullable MethodHandle CACHE_SHADER_SOURCE;

    static {
        MethodHandle state = null;
        MethodHandle scissor = null;
        MethodHandle current = null;
        MethodHandle source = null;
        try {
            MethodHandles.Lookup self = MethodHandles.lookup();
            MethodHandles.Lookup graphics = MethodHandles.privateLookupIn(GuiGraphicsExtractor.class, self);
            Class<?> stackClass = Class.forName(GuiGraphicsExtractor.class.getName() + "$ScissorStack", false, GuiGraphicsExtractor.class.getClassLoader());
            MethodHandle stack = graphics.findGetter(GuiGraphicsExtractor.class, "scissorStack", stackClass);
            MethodHandle peek = MethodHandles.privateLookupIn(stackClass, self).findVirtual(stackClass, "peek", MethodType.methodType(ScreenRectangle.class));
            MethodHandles.Lookup renderSystem = MethodHandles.privateLookupIn(RenderSystem.class, self);
            state = graphics.findGetter(GuiGraphicsExtractor.class, "guiRenderState", GuiRenderState.class);
            scissor = MethodHandles.filterReturnValue(stack, peek);
            current = renderSystem.findStaticGetter(RenderSystem.class, "currentPipelineCache", PipelineCache.class);
            source = MethodHandles.privateLookupIn(PipelineCache.class, self).findGetter(PipelineCache.class, "shaderSource", ShaderSource.class);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            LOGGER.warn("W1 vignette skip disabled: GUI/pipeline internals not reachable ({})", e.toString());
            state = scissor = current = source = null;
        }
        GUI_RENDER_STATE = state;
        SCISSOR = scissor;
        CURRENT_PIPELINE_CACHE = current;
        CACHE_SHADER_SOURCE = source;
    }

    // Render thread only (GUI extraction): the pipeline cache whose shader sources were compared last (weak: a reload
    // closes and drops it), and the result.
    private static WeakReference<PipelineCache> checkedCache = new WeakReference<>(null);
    private static @Nullable RenderPipeline checkedPipeline;
    private static boolean checkedVanilla;

    private HudVignetteSkip() {
    }

    private static boolean flag(final String name, final boolean defaultValue) {
        String value = System.getProperty(name);
        return value == null ? defaultValue : !value.equalsIgnoreCase("false") && !value.equals("0");
    }

    /**
     * True when the vanilla vignette blit with this packed ARGB colour changes no pixel and W1 may skip it: no
     * world-border warning (vanilla's own branch test, {@code borderWarningStrength > 0}, is false; after that branch the
     * strength is still {@code > 0}, so the value seen after the colour computation gives the same answer) and RGB 0.
     * Alpha is irrelevant: the blend takes dst alpha unchanged, and RGB uses only the source colour.
     */
    public static boolean isNoop(final float borderWarningStrength, final int color) {
        return !(borderWarningStrength > 0.0F) && (color & 0x00FFFFFF) == 0;
    }

    /**
     * {@code graphics.blit(pipeline, texture, x, y, u, v, width, height, textureWidth, textureHeight, color)} without the
     * draw: the same calls as {@code GuiGraphicsExtractor.blit} (11 args) -> {@code blit} (13 args, srcWidth = width,
     * srcHeight = height) -> {@code innerBlit(Identifier)} -> {@code innerBlit(GpuTextureView)}, with the element wrapped in
     * a {@link NoDrawElement}. Returns false, having changed nothing, when the skip is not exact or not possible (shader
     * sources overridden, internals unreachable); the caller then calls the vanilla blit.
     */
    public static boolean blitWithoutDraw(
        final GuiGraphicsExtractor graphics,
        final Minecraft minecraft,
        final RenderPipeline pipeline,
        final Identifier texture,
        final int x,
        final int y,
        final float u,
        final float v,
        final int width,
        final int height,
        final int textureWidth,
        final int textureHeight,
        final int color
    ) {
        if (GUI_RENDER_STATE == null || SCISSOR == null) {
            return false;
        }

        GuiRenderState guiRenderState;
        ScreenRectangle scissorArea;
        try {
            if (!vanillaShaders(pipeline)) {
                return false;
            }

            guiRenderState = (GuiRenderState)GUI_RENDER_STATE.invokeExact(graphics);
            scissorArea = (ScreenRectangle)SCISSOR.invokeExact(graphics);
        } catch (Throwable t) {
            return false;
        }

        // innerBlit(pipeline, Identifier, x0, x1, y0, y1, u0, u1, v0, v1, color)
        int x0 = x;
        int x1 = x + width;
        int y0 = y;
        int y1 = y + height;
        float u0 = (u + 0.0F) / textureWidth;
        float u1 = (u + width) / textureWidth;
        float v0 = (v + 0.0F) / textureHeight;
        float v1 = (v + height) / textureHeight;
        AbstractTexture abstractTexture = minecraft.getTextureManager().getTexture(texture);
        // innerBlit(pipeline, GpuTextureView, GpuSampler, x0, y0, x1, y1, u0, u1, v0, v1, color)
        guiRenderState.addGuiElement(
            new NoDrawElement(
                new BlitRenderState(
                    pipeline,
                    TextureSetup.singleTexture(abstractTexture.getTextureView(), abstractTexture.getSampler()),
                    new Matrix3x2f(graphics.pose()),
                    x0,
                    y0,
                    x1,
                    y1,
                    u0,
                    u1,
                    v0,
                    v1,
                    color,
                    scissorArea
                )
            )
        );
        return true;
    }

    /**
     * True when every shader stage of {@code pipeline} in the current pipeline cache (set by the last resource reload;
     * {@code RenderSystem.getCompiledPipelineNullable} draws from it, and a reload that fails to compile a required
     * pipeline such as VIGNETTE does not replace it) has exactly the built-in source of the client jar, i.e. no resource
     * pack overrides it. Compared once per cache (a map lookup in {@code ShaderManager.Configs}, no I/O). False before the
     * first reload (only the startup fallback cache exists then, and no world is rendered).
     */
    static boolean vanillaShaders(final RenderPipeline pipeline) throws Throwable {
        if (CURRENT_PIPELINE_CACHE == null || CACHE_SHADER_SOURCE == null) {
            return false;
        }

        PipelineCache cache = (PipelineCache)CURRENT_PIPELINE_CACHE.invokeExact();
        if (cache == null) {
            return false;
        }

        if (cache != checkedCache.get() || pipeline != checkedPipeline) {
            ShaderSource source = (ShaderSource)CACHE_SHADER_SOURCE.invokeExact(cache);
            boolean vanilla = !pipeline.getShaders().isEmpty();
            for (Map.Entry<ShaderType, Identifier> stage : pipeline.getShaders().entrySet()) {
                String builtIn = builtInShader(stage.getValue(), stage.getKey());
                vanilla &= builtIn != null && builtIn.equals(source.getShader(stage.getValue(), stage.getKey()));
            }

            if (!vanilla && enabled) {
                LOGGER.info("W1 vignette skip inactive: {} shaders differ from the built-in ones (resource pack)", pipeline.getLocation());
            }

            checkedCache = new WeakReference<>(cache);
            checkedPipeline = pipeline;
            checkedVanilla = vanilla;
        }

        return checkedVanilla;
    }

    /** The jar's built-in source of a shader stage, as the vanilla pack serves it (assets/ns/shaders/path.vsh|.fsh). */
    static @Nullable String builtInShader(final Identifier id, final ShaderType type) {
        String path = "assets/" + id.getNamespace() + "/shaders/" + id.getPath() + (type == ShaderType.VERTEX ? ".vsh" : ".fsh");
        try (InputStream in = HudVignetteSkip.class.getClassLoader().getResourceAsStream(path)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * The vanilla element without its vertices: GUI layering, sorting and batching see exactly the vanilla element
     * (same pipeline, texture setup, scissor and bounds); {@code GuiRenderer.addElementToMesh} still opens the same draw
     * for it, which stays empty and is never executed.
     */
    public record NoDrawElement(BlitRenderState vanilla) implements GuiElementRenderState {
        @Override
        public void buildVertices(final VertexConsumer vertexConsumer) {
        }

        @Override
        public RenderPipeline pipeline() {
            return this.vanilla.pipeline();
        }

        @Override
        public TextureSetup textureSetup() {
            return this.vanilla.textureSetup();
        }

        @Override
        public @Nullable ScreenRectangle scissorArea() {
            return this.vanilla.scissorArea();
        }

        @Override
        public @Nullable ScreenRectangle bounds() {
            return this.vanilla.bounds();
        }
    }
}
