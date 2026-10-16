import com.mojang.blaze3d.pipeline.PipelineCache;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.GpuFence;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.device.GpuSurface;
import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.BlendOp;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.metal.MetalBackend;
import com.mojang.renderpearl.backend.metal.MtlNative;
import com.mojang.renderpearl.frontend.FrontendGpuSurface;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.FieldModel;
import java.lang.classfile.Instruction;
import java.lang.classfile.Label;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.TypeKind;
import java.lang.classfile.instruction.BranchInstruction;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.ExceptionCatch;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LabelTarget;
import java.lang.classfile.instruction.LoadInstruction;
import java.lang.classfile.instruction.LocalVariable;
import java.lang.classfile.instruction.LookupSwitchInstruction;
import java.lang.classfile.instruction.ReturnInstruction;
import java.lang.classfile.instruction.StoreInstruction;
import java.lang.classfile.instruction.SwitchCase;
import java.lang.classfile.instruction.TableSwitchInstruction;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.gui.HudVignetteSkip;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.state.gui.BlitRenderState;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.resources.ClientPackSource;
import net.minecraft.client.resources.IndexedAssetSource;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.VanillaPackResources;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3x2fStack;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * Tests for W1 of docs/stage2/STAGE2-PLAN.md (HUD vignette no-op skip, {@code -Dmc.hud.vignetteSkipNoop},
 * {@code net.minecraft.client.gui.HudVignetteSkip} + the patched {@code Hud.extractVignette}). Run through
 * tools/hud-vignette-test.sh (needs build/client.jar; the GPU part needs the Metal device, run it under the GPU lock).
 *
 * <ol>
 *   <li>Toggle: default on, {@code false}/{@code 0} off.</li>
 *   <li>Quantization boundary: with vanilla's own {@code Mth.clamp} / {@code ARGB.colorFromFloat} /
 *       {@code ARGB.as8BitChannel}, the skip happens exactly when {@code as8BitChannel(clamp(vb)) == 0}, around the
 *       boundary ulp by ulp, for special values and for 4M random floats; the decay from vb = 1 reaches it after 552 ticks.</li>
 *   <li>World-border warning (strength > 0) never skips, also where its colour quantizes to RGB 0; NaN strength
 *       follows vanilla's branch.</li>
 *   <li>The VIGNETTE pipeline is what the no-op proof assumes (blend, write mask, format, no depth/stencil, shaders), and
 *       the shader-source guard turns the skip off for resource-pack overrides of either stage, a missing shader, and
 *       before the first resource reload (no current pipeline cache).</li>
 *   <li>Differential GUI test against the real GuiGraphicsExtractor / GuiRenderState / GuiRenderer comparator: random HUD
 *       frames (pose, scissor, strata, fills, textured blits, vignettes anywhere), (A) vanilla {@code graphics.blit} vs (B)
 *       {@code HudVignetteSkip.blitWithoutDraw}: the stand-in wraps a record equal to the vanilla element; node structure,
 *       lastElementBounds (also across frames), sorted draw order and draw batching are identical; the stand-in's draws
 *       have 0 vertices. (C) "return early" (no element) for contrast: reports how often the draw list differs.</li>
 *   <li>StagedVertexBuffer: the stand-in's draw is empty, so getExecuteInfo returns null (GuiRenderer issues nothing).</li>
 *   <li>Bytecode (build/client.jar vs the verbatim vanilla Hud.java compiled by the same javac): every Hud class and method
 *       is instruction-identical except extractVignette, which is vanilla plus one inserted block
 *       {@code if (enabled && isNoop(..) && blitWithoutDraw(..)) return;} right before the vanilla blit (no stores), with
 *       its exact instructions: isNoop gets the slot of vanilla's own border test and the slot the vanilla blit takes its
 *       colour from (also named borderWarningStrength / color in the LocalVariableTable), blitWithoutDraw gets the vanilla
 *       blit's argument instructions verbatim plus {@code this.minecraft}.</li>
 *   <li>extractVignette end to end: the patched Hud from the jar (Unsafe-built, real WorldBorder, real camera entity)
 *       with the toggle off, on, and on with resource-pack shaders, over vignette brightnesses around the quantization
 *       boundary and specials, no camera, cameras near/at/outside a static or moving border, NaN positions, random
 *       pose/scissor: toggle off adds exactly vanilla's element (colour = an independent oracle), toggle on wraps an
 *       equal element in NoDrawElement exactly when the oracle says no border warning and RGB 0, the GuiRenderState
 *       structure is identical and no Hud field changes.</li>
 *   <li>Shader guard through the game's own resource loading: ClientPackSource's vanilla pack (jar + the asset index the
 *       launcher uses) read by ShaderManager.loadConfigs, the source the current pipeline cache holds in game: the guard
 *       accepts it (the skip is active), rejects a resource pack overriding either stage, and accepts a pack shipping
 *       byte-identical copies.</li>
 *   <li>Metal (real backend, headless): the vanilla VIGNETTE pipeline with colour RGB 0 over random RGBA8 content (every
 *       byte value in every channel) and a cleared D32 depth, vanilla GUI projection/transforms, vanilla vignette.png and
 *       a random texture with alpha-0 texels, three samplers, two GUI scales: colour and depth bytes equal to no draw;
 *       RGB 1/255 and white must change pixels (positive controls). Then the same through the present-fusion BGRA8
 *       drawable (offscreen CAMetalLayer): next-frame readbacks equal the no-draw reference.</li>
 * </ol>
 *
 * <p>Usage: {@code java -Dw1.mcDir=<game dir> -cp <client.jar + libraries> tools/HudVignetteTest.java <client.jar>
 * <vanilla-hud-classes-dir> [--no-gpu]}.
 */
public final class HudVignetteTest {
    private static int checks;
    private static int failures;
    private static final List<String> FAILED = new ArrayList<>();

    public static void main(final String[] args) throws Throwable {
        Locale.setDefault(Locale.ROOT);
        List<String> rest = new ArrayList<>(Arrays.asList(args));
        boolean gpu = !rest.remove("--no-gpu");
        String jar = rest.size() > 0 ? rest.get(0) : null;
        String vanillaHud = rest.size() > 1 ? rest.get(1) : null;

        section("toggle -Dmc.hud.vignetteSkipNoop", HudVignetteTest::toggle);
        section("quantization boundary", HudVignetteTest::quantization);
        section("world-border warning never skips", HudVignetteTest::borderNeverSkips);
        section("VIGNETTE pipeline facts + shader-source guard", HudVignetteTest::pipelineAndShaderGuard);
        section("GUI differential: blitWithoutDraw vs vanilla blit (element, layering, sort, batching)", HudVignetteTest::guiDifferential);
        section("StagedVertexBuffer: stand-in draw empty, never executed", HudVignetteTest::stagedVertexBuffer);
        if (jar != null && vanillaHud != null) {
            section("bytecode: Hud == verbatim vanilla Hud except the inserted skip block", () -> bytecode(jar, vanillaHud));
        } else {
            System.out.println("SKIP bytecode (no client jar / vanilla Hud classes given)");
        }
        section("extractVignette end to end: patched Hud, toggle off / on / guard off, border and camera cases", HudVignetteTest::extractVignetteEndToEnd);
        section("shader guard through the game's resource loading (vanilla pack + asset index, resource packs)", HudVignetteTest::shaderGuardGameLoader);
        if (gpu) {
            section("Metal: VIGNETTE colour 0 == no draw (offscreen RGBA8 + present-fusion BGRA8)", HudVignetteTest::metal);
        } else {
            System.out.println("SKIP Metal (--no-gpu)");
        }

        System.out.printf("HudVignetteTest: %d checks, %d failures%n", checks, failures);
        for (String f : FAILED.subList(0, Math.min(20, FAILED.size()))) {
            System.out.println("  failed: " + f);
        }
        System.out.println(failures == 0 ? "HUD VIGNETTE TEST PASSED" : "HUD VIGNETTE TEST FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    interface Body {
        void run() throws Throwable;
    }

    private static void section(final String name, final Body body) {
        int before = failures;
        long t0 = System.nanoTime();
        try {
            body.run();
        } catch (Throwable t) {
            t.printStackTrace(System.out);
            check(false, name + ": threw " + t);
        }
        System.out.printf("%s %s (%.0f ms)%n", failures == before ? "ok  " : "FAIL", name, (System.nanoTime() - t0) / 1e6);
    }

    private static void check(final boolean ok, final String what) {
        checks++;
        if (!ok) {
            failures++;
            FAILED.add(what);
            System.out.println("  FAIL: " + what);
        }
    }

    // ================================================================================================================
    // 1. toggle

    static void toggle() throws Throwable {
        check(System.getProperty("mc.hud.vignetteSkipNoop") == null, "test JVM runs without -Dmc.hud.vignetteSkipNoop");
        check(HudVignetteSkip.enabled, "default: enabled");
        Method flag = HudVignetteSkip.class.getDeclaredMethod("flag", String.class, boolean.class);
        flag.setAccessible(true);
        String key = "w1.test.flag";
        Object[][] cases = {{null, true}, {"false", false}, {"FALSE", false}, {"0", false}, {"true", true}, {"1", true}, {"yes", true}};
        for (Object[] c : cases) {
            if (c[0] == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, (String)c[0]);
            }
            check((Boolean)flag.invoke(null, key, true) == (Boolean)c[1], "flag(" + c[0] + ") == " + c[1]);
        }
        System.clearProperty(key);
    }

    // ================================================================================================================
    // 2. quantization boundary (vanilla Mth / ARGB from the jar)

    /** Vanilla's plain-path colour (Hud.extractVignette, no border warning). */
    static int vanillaColor(final float vignetteBrightness) {
        float brightness = Mth.clamp(vignetteBrightness, 0.0F, 1.0F);
        return ARGB.colorFromFloat(1.0F, brightness, brightness, brightness);
    }

    static void expectSkipIffZero(final float vb, final String what) {
        boolean zero = ARGB.as8BitChannel(Mth.clamp(vb, 0.0F, 1.0F)) == 0;
        boolean skip = HudVignetteSkip.isNoop(0.0F, vanillaColor(vb));
        if (skip != zero) {
            check(false, what + ": vb " + vb + " (0x" + Integer.toHexString(Float.floatToRawIntBits(vb)) + ") skip " + skip + ", as8Bit==0 " + zero);
        }
    }

    static void quantization() {
        int before = failures;
        // Largest float b with floor(b * 255f) == 0 (float product, as ARGB.as8BitChannel computes it).
        float boundary = 1.0F / 255.0F;
        while (ARGB.as8BitChannel(boundary) != 0) {
            boundary = Math.nextDown(boundary);
        }
        while (ARGB.as8BitChannel(Math.nextUp(boundary)) == 0) {
            boundary = Math.nextUp(boundary);
        }
        System.out.printf("  boundary: largest vb with as8BitChannel == 0 is %s (0x%08x); 1/255f = %s%n", boundary,
            Float.floatToRawIntBits(boundary), 1.0F / 255.0F);
        check(HudVignetteSkip.isNoop(0.0F, vanillaColor(boundary)), "skips at the boundary value");
        check(!HudVignetteSkip.isNoop(0.0F, vanillaColor(Math.nextUp(boundary))), "draws one ulp above the boundary");
        check(vanillaColor(Math.nextUp(boundary)) == 0xFF010101, "one ulp above: colour 0xFF010101");
        // Every float within 2^16 ulps of the boundary.
        float f = boundary;
        for (int i = 0; i < 65536; i++) {
            f = Math.nextDown(f);
        }
        for (int i = 0; i < 131073; i++, f = Math.nextUp(f)) {
            expectSkipIffZero(f, "near boundary");
        }
        float[] specials = {0.0F, -0.0F, Float.MIN_VALUE, Float.MIN_NORMAL, 1e-30F, 1e-10F, 1e-3F, 1.0F / 512, 1.0F / 256, 1.0F / 255, 2.0F / 255, 0.5F,
            Math.nextDown(1.0F), 1.0F, Math.nextUp(1.0F), 1.5F, 1e30F, -Float.MIN_VALUE, -1e-9F, -1.0F, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
            Float.NaN, Float.intBitsToFloat(0x7FC12345), Float.intBitsToFloat(0xFFC00001)};
        for (float s : specials) {
            expectSkipIffZero(s, "special");
        }
        check(HudVignetteSkip.isNoop(0.0F, vanillaColor(Float.NaN)), "NaN vb: vanilla colour is 0xFF000000 (floor(NaN) -> 0), skip");
        check(HudVignetteSkip.isNoop(0.0F, vanillaColor(-5.0F)) && !HudVignetteSkip.isNoop(0.0F, vanillaColor(5.0F)), "clamp: negative skips, > 1 draws");
        Random rnd = new Random(0x5715);
        for (int i = 0; i < 2_000_000; i++) {
            expectSkipIffZero(Float.intBitsToFloat(rnd.nextInt()), "random bits");
            expectSkipIffZero(rnd.nextFloat() * 1.2F - 0.1F, "random [-0.1, 1.1)");
        }
        // Every 8-bit colour value: skip iff RGB 0 (alpha irrelevant).
        for (int a = 0; a < 256; a += 51) {
            for (int v = 0; v < 256; v++) {
                check(HudVignetteSkip.isNoop(0.0F, a << 24 | v << 16 | v << 8 | v) == (v == 0), "grey " + v + " alpha " + a);
            }
        }
        check(!HudVignetteSkip.isNoop(0.0F, 0xFF000001) && !HudVignetteSkip.isNoop(0.0F, 0xFF000100) && !HudVignetteSkip.isNoop(0.0F, 0xFF010000),
            "any non-zero RGB channel draws");

        // Hud.updateVignetteBrightness in a fully lit place (levelBrightness 1 -> target 0), from the initial 1.0, in floats.
        float vb = 1.0F;
        int ticks = 0;
        while (!HudVignetteSkip.isNoop(0.0F, vanillaColor(vb)) && ticks < 100_000) {
            float brightness = Mth.clamp(1.0F - 1.0F, 0.0F, 1.0F);
            vb = vb + (brightness - vb) * 0.01F;
            ticks++;
        }
        System.out.printf("  from vb = 1 in full light: first skipped frame after %d ticks (%.2f s), vb = %s%n", ticks, ticks / 20.0, vb);
        check(ticks == 552, "552 ticks to the first skipped frame (STAGE2-PLAN W1), got " + ticks);
        // A target at or above the boundary never skips: e.g. 1/255 exactly converges to 1/255 from above.
        float target = 1.0F / 255.0F;
        vb = 1.0F;
        boolean everSkipped = false;
        for (int t = 0; t < 20_000; t++) {
            vb = vb + (target - vb) * 0.01F;
            everSkipped |= HudVignetteSkip.isNoop(0.0F, vanillaColor(vb));
        }
        System.out.printf("  target 1/255: vb after 20000 ticks %s, skipped %s%n", vb, everSkipped);
        check(failures == before, "quantization: skip <=> as8BitChannel(clamp(vb)) == 0 everywhere");
    }

    // ================================================================================================================
    // 3. world-border warning

    static void borderNeverSkips() {
        float[] strengths = {Float.MIN_VALUE, 1e-30F, 1e-6F, 1e-3F, Math.nextDown(1.0F / 255), 0.25F, 0.5F, 1.0F, 1.5F, 1e9F, Float.POSITIVE_INFINITY};
        float[] vbs = {0.0F, Float.MIN_VALUE, 1e-6F, 1.0F / 300, 1.0F / 255, 0.1F, 0.5F, 1.0F, Float.NaN};
        int zeroColour = 0;
        int cases = 0;
        for (float s0 : strengths) {
            for (float vb : vbs) {
                // Hud.extractVignette, border branch (taken because s0 > 0).
                float brightness = Mth.clamp(vb, 0.0F, 1.0F);
                float s = Mth.clamp(s0, 0.0F, 1.0F);
                float red = brightness * (1.0F - s);
                float greenBlue = brightness + (1.0F - brightness) * s;
                int color = ARGB.colorFromFloat(1.0F, red, greenBlue, greenBlue);
                cases++;
                zeroColour += (color & 0xFFFFFF) == 0 ? 1 : 0;
                check(!HudVignetteSkip.isNoop(s, color), "border strength " + s0 + " vb " + vb + " (colour " + Integer.toHexString(color) + ") must not skip");
            }
        }
        System.out.printf("  %d border cases never skip; in %d of them the colour is RGB 0 anyway (conservative by definition)%n", cases, zeroColour);
        check(zeroColour > 0, "the conservative case exists (tiny strength, dark vignette)");
        // NaN strength: vanilla's `borderWarningStrength > 0` is false, so it takes the plain branch; so does isNoop.
        check(HudVignetteSkip.isNoop(Float.NaN, vanillaColor(0.0F)) && !HudVignetteSkip.isNoop(Float.NaN, vanillaColor(1.0F)), "NaN strength = plain branch");
        check(HudVignetteSkip.isNoop(-0.0F, vanillaColor(0.0F)) && HudVignetteSkip.isNoop(-1.0F, vanillaColor(0.0F)), "strength <= 0 = plain branch");
    }

    // ================================================================================================================
    // 4. pipeline facts + shader guard

    static void pipelineAndShaderGuard() throws Throwable {
        RenderPipeline p = RenderPipelines.VIGNETTE;
        List<ColorTargetState> targets = p.getColorTargetStates().stream().filter(Objects::nonNull).toList();
        check(targets.size() == 1 && p.getColorTargetStates().get(0) != null, "one colour target (index 0)");
        ColorTargetState t = p.getColorTargetStates().get(0);
        check(t.format() == GpuFormat.RGBA8_UNORM && t.writeMask() == ColorTargetState.WRITE_ALL, "RGBA8_UNORM, write mask RGBA");
        BlendFunction b = t.blendFunction().orElse(null);
        check(b != null && b.color().sourceFactor() == BlendFactor.ZERO && b.color().destFactor() == BlendFactor.ONE_MINUS_SRC_COLOR
            && b.color().op() == BlendOp.ADD && b.alpha().sourceFactor() == BlendFactor.ZERO && b.alpha().destFactor() == BlendFactor.ONE
            && b.alpha().op() == BlendOp.ADD, "blend (ZERO, ONE_MINUS_SRC_COLOR, ADD; ZERO, ONE, ADD): " + b);
        check(p.getDepthStencilState() == null, "no depth/stencil state (no depth test, no depth or stencil writes)");
        check(p.getVertexFormatBinding(0) == DefaultVertexFormat.POSITION_TEX_COLOR && p.getPrimitiveTopology() == PrimitiveTopology.QUADS,
            "POSITION_TEX_COLOR quads");
        Identifier shader = Identifier.withDefaultNamespace("core/position_tex_color");
        check(shader.equals(p.getShaders().get(ShaderType.VERTEX)) && shader.equals(p.getShaders().get(ShaderType.FRAGMENT)) && p.getShaders().size() == 2,
            "shaders core/position_tex_color: " + p.getShaders());
        check(p.getShaderDefines().isEmpty(), "no shader defines: " + p.getShaderDefines());
        String fsh = resource("assets/minecraft/shaders/core/position_tex_color.fsh");
        String vsh = resource("assets/minecraft/shaders/core/position_tex_color.vsh");
        check(fsh != null && fsh.contains("vec4 color = texture(Sampler0, texCoord0) * vertexColor;") && fsh.contains("if (color.a == 0.0) {")
            && fsh.contains("fragColor = color * ColorModulator;") && !fsh.contains("#moj_import"), "fragment: tex * vertexColor (discard a == 0) * ColorModulator");
        check(vsh != null && vsh.contains("vertexColor = Color;") && !vsh.contains("#moj_import"), "vertex: vertexColor = Color");

        Method vanillaShaders = HudVignetteSkip.class.getDeclaredMethod("vanillaShaders", RenderPipeline.class);
        vanillaShaders.setAccessible(true);
        check(!(Boolean)vanillaShaders.invoke(null, p), "no current pipeline cache (before the first reload): no skip");
        RenderSystem.setCurrentPipelineCache(new PipelineCache(null, jarShaderSource()));
        check((Boolean)vanillaShaders.invoke(null, p), "current cache with the built-in sources: vanilla");
        RenderSystem.setCurrentPipelineCache(new PipelineCache(null, override(ShaderType.FRAGMENT, fsh + "\n// pack\n")));
        check(!(Boolean)vanillaShaders.invoke(null, p), "resource pack overrides the fragment shader: not vanilla");
        RenderSystem.setCurrentPipelineCache(new PipelineCache(null, override(ShaderType.VERTEX, vsh.replace("vertexColor = Color;", "vertexColor = vec4(1.0);"))));
        check(!(Boolean)vanillaShaders.invoke(null, p), "resource pack overrides the vertex shader: not vanilla");
        RenderSystem.setCurrentPipelineCache(new PipelineCache(null, override(ShaderType.FRAGMENT, null)));
        check(!(Boolean)vanillaShaders.invoke(null, p), "shader missing: not vanilla");
        RenderSystem.setCurrentPipelineCache(new PipelineCache(null, jarShaderSource()));
        check((Boolean)vanillaShaders.invoke(null, p), "reload back to the built-in sources: vanilla");
    }

    static ShaderSource override(final ShaderType type, final String text) {
        ShaderSource base = jarShaderSource();
        return new ShaderSource() {
            @Override
            public String getShader(final Identifier id, final ShaderType t) {
                return t == type && id.getPath().equals("core/position_tex_color") ? text : base.getShader(id, t);
            }

            @Override
            public CachedIncludeSource getInclude(final Identifier id) {
                return base.getInclude(id);
            }

            @Override
            public void close() {
            }
        };
    }

    // ================================================================================================================
    // 5. GUI differential test (real GuiGraphicsExtractor / GuiRenderState, no Minecraft instance)

    static final sun.misc.Unsafe UNSAFE;

    static {
        try {
            Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            UNSAFE = (sun.misc.Unsafe)f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    static void set(final Object target, final Class<?> owner, final String name, final Object value) throws ReflectiveOperationException {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    static Object get(final Object target, final Class<?> owner, final String name) throws ReflectiveOperationException {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    @SuppressWarnings("unchecked")
    static <T> T fake(final Class<T> type, final String name) {
        return (T)Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, m, a) -> switch (m.getName()) {
            case "equals" -> proxy == a[0];
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> name;
            default -> throw new UnsupportedOperationException(name + "." + m.getName());
        });
    }

    static final class FakeTexture extends AbstractTexture {
        FakeTexture(final String name) {
            this.textureView = fake(GpuTextureView.class, name + " view");
            this.sampler = fake(GpuSampler.class, name + " sampler");
        }
    }

    static final Identifier VIGNETTE_LOCATION = Identifier.withDefaultNamespace("textures/misc/vignette.png");
    static final Identifier[] TEXTURES = {
        Identifier.withDefaultNamespace("textures/misc/spyglass_scope.png"), Identifier.withDefaultNamespace("textures/misc/powder_snow_outline.png"),
        Identifier.withDefaultNamespace("textures/misc/nausea.png"), Identifier.withDefaultNamespace("textures/gui/sprites.png")};
    static final RenderPipeline[] BLIT_PIPELINES = {RenderPipelines.GUI_TEXTURED, RenderPipelines.CROSSHAIR, RenderPipelines.GUI_NAUSEA_OVERLAY,
        RenderPipelines.GUI_TEXTURED_PREMULTIPLIED_ALPHA, RenderPipelines.GUI_OPAQUE_TEXTURED_BACKGROUND};
    static final RenderPipeline[] FILL_PIPELINES = {RenderPipelines.GUI, RenderPipelines.GUI_INVERT, RenderPipelines.GUI_TEXT_HIGHLIGHT};

    /** GuiGraphicsExtractor fields set the way its constructor does (pose, scissor stack, state), without a game. */
    static final class Gui {
        final Minecraft minecraft;
        final Constructor<?> scissorStack;

        Gui() throws Throwable {
            TextureManager textures = (TextureManager)UNSAFE.allocateInstance(TextureManager.class);
            Map<Identifier, AbstractTexture> byPath = new HashMap<>();
            byPath.put(VIGNETTE_LOCATION, new FakeTexture("vignette"));
            for (Identifier id : TEXTURES) {
                byPath.put(id, new FakeTexture(id.getPath()));
            }
            set(textures, TextureManager.class, "byPath", byPath);
            this.minecraft = (Minecraft)UNSAFE.allocateInstance(Minecraft.class);
            set(this.minecraft, Minecraft.class, "textureManager", textures);
            Class<?> stack = Class.forName(GuiGraphicsExtractor.class.getName() + "$ScissorStack");
            this.scissorStack = stack.getDeclaredConstructor(ScreenRectangle.class);
            this.scissorStack.setAccessible(true);
        }

        /** As {@code new GuiGraphicsExtractor(minecraft, state, mouseX, mouseY)} with a guiWidth x guiHeight window. */
        GuiGraphicsExtractor graphics(final GuiRenderState state, final int guiWidth, final int guiHeight) throws Throwable {
            GuiGraphicsExtractor g = (GuiGraphicsExtractor)UNSAFE.allocateInstance(GuiGraphicsExtractor.class);
            set(g, GuiGraphicsExtractor.class, "minecraft", this.minecraft);
            set(g, GuiGraphicsExtractor.class, "pose", new Matrix3x2fStack(16));
            set(g, GuiGraphicsExtractor.class, "guiRenderState", state);
            set(g, GuiGraphicsExtractor.class, "scissorStack", this.scissorStack.newInstance(new ScreenRectangle(0, 0, guiWidth, guiHeight)));
            return g;
        }
    }

    /** One extraction step, applied identically to the three states (the vignette op differs by mode). */
    sealed interface Op permits Vig, Blit, Fill, Push, Pop, Translate, Scale, ScissorOn, ScissorOff, Stratum {
    }

    record Vig(int w, int h, int color) implements Op {
    }

    record Blit(RenderPipeline pipeline, Identifier texture, int x, int y, float u, float v, int w, int h, int tw, int th, int color) implements Op {
    }

    record Fill(RenderPipeline pipeline, int x0, int y0, int x1, int y1, int color) implements Op {
    }

    record Push() implements Op {
    }

    record Pop() implements Op {
    }

    record Translate(float x, float y) implements Op {
    }

    record Scale(float x, float y) implements Op {
    }

    record ScissorOn(int x0, int y0, int x1, int y1) implements Op {
    }

    record ScissorOff() implements Op {
    }

    record Stratum() implements Op {
    }

    enum Mode {
        VANILLA,
        STAND_IN,
        RETURN_EARLY
    }

    /** A HUD-like frame: vignette first (identity pose, no scissor), camera overlays, crosshair, strata, random rest. */
    static List<Op> frame(final Random r, final int w, final int h) {
        List<Op> ops = new ArrayList<>();
        int vigColor = r.nextInt(4) == 0 ? 0xFF000000 | r.nextInt(3) * 0x010101 : 0xFF000000;
        if (r.nextInt(8) != 0) {
            ops.add(new Vig(w, h, vigColor));
        }
        int overlays = r.nextInt(3);
        for (int i = 0; i < overlays; i++) {
            if (r.nextBoolean()) {
                // spyglass-like: scaled square, can exceed the screen, plus black fills around it
                int size = (int)(Math.min(w, h) * (0.5F + r.nextFloat()));
                int left = (w - size) / 2;
                int top = (h - size) / 2;
                ops.add(new Blit(RenderPipelines.GUI_TEXTURED, TEXTURES[0], left, top, 0, 0, size, size, size, size, -1));
                ops.add(new Fill(RenderPipelines.GUI, 0, top + size, w, h, 0xFF000000));
                ops.add(new Fill(RenderPipelines.GUI, 0, 0, w, top, 0xFF000000));
            } else {
                ops.add(new Blit(RenderPipelines.GUI_TEXTURED, TEXTURES[1 + r.nextInt(2)], 0, 0, 0, 0, w, h, w, h, r.nextInt()));
            }
        }
        if (r.nextInt(4) != 0) {
            ops.add(new Blit(RenderPipelines.CROSSHAIR, TEXTURES[3], (w - 15) / 2, (h - 15) / 2, 0, 0, 15, 15, 256, 256, -1));
            if (r.nextBoolean()) {
                ops.add(new Blit(RenderPipelines.GUI_TEXTURED, TEXTURES[3], w / 2 - 8, h / 2 - 7 + 16, 0, 0, 16, 4, 256, 256, -1));
            }
        }
        ops.add(new Stratum());
        int depthPose = 0;
        int depthScissor = 0;
        int n = 5 + r.nextInt(40);
        for (int i = 0; i < n; i++) {
            int k = r.nextInt(20);
            if (k < 7) {
                int x = r.nextInt(w + 20) - 10;
                int y = r.nextInt(h + 20) - 10;
                int bw = r.nextInt(4) == 0 ? w : 1 + r.nextInt(Math.max(1, w / 3));
                int bh = r.nextInt(4) == 0 ? h : 1 + r.nextInt(Math.max(1, h / 3));
                ops.add(new Blit(BLIT_PIPELINES[r.nextInt(BLIT_PIPELINES.length)], TEXTURES[r.nextInt(TEXTURES.length)], x, y, r.nextInt(16), r.nextInt(16),
                    bw, bh, 16 + r.nextInt(240), 16 + r.nextInt(240), r.nextInt()));
            } else if (k < 12) {
                int x0 = r.nextInt(w + 20) - 10;
                int y0 = r.nextInt(h + 20) - 10;
                ops.add(new Fill(FILL_PIPELINES[r.nextInt(FILL_PIPELINES.length)], x0, y0, x0 + r.nextInt(w / 2 + 1) - 3, y0 + r.nextInt(h / 2 + 1) - 3,
                    r.nextInt()));
            } else if (k == 12) {
                ops.add(new Push());
                depthPose++;
                ops.add(r.nextBoolean() ? new Translate(r.nextInt(41) - 20 + (r.nextBoolean() ? 0.5F : 0.0F), r.nextInt(41) - 20)
                    : new Scale(0.5F + r.nextInt(4) * 0.5F, 0.5F + r.nextInt(4) * 0.5F));
            } else if (k == 13 && depthPose > 0) {
                ops.add(new Pop());
                depthPose--;
            } else if (k == 14 && depthScissor < 2) {
                int x0 = r.nextInt(w);
                int y0 = r.nextInt(h);
                ops.add(new ScissorOn(x0, y0, x0 + r.nextInt(w), y0 + r.nextInt(h)));
                depthScissor++;
            } else if (k == 15 && depthScissor > 0) {
                ops.add(new ScissorOff());
                depthScissor--;
            } else if (k == 16) {
                ops.add(new Stratum());
            } else if (k == 17) {
                // a vignette blit anywhere (any pose / scissor): blitWithoutDraw must reproduce graphics.blit in general
                ops.add(new Vig(r.nextInt(4) == 0 ? 0 : w, r.nextInt(4) == 0 ? 0 : h, 0xFF000000));
            }
        }
        for (; depthScissor > 0; depthScissor--) {
            ops.add(new ScissorOff());
        }
        for (; depthPose > 0; depthPose--) {
            ops.add(new Pop());
        }
        return ops;
    }

    /** Applies one op; returns the elements it added to the state (by identity), in order. */
    static void apply(final GuiGraphicsExtractor g, final Op op, final Mode mode, final Minecraft mc) {
        switch (op) {
            case Vig v -> {
                if (mode == Mode.VANILLA) {
                    g.blit(RenderPipelines.VIGNETTE, VIGNETTE_LOCATION, 0, 0, 0.0F, 0.0F, v.w(), v.h(), v.w(), v.h(), v.color());
                } else if (mode == Mode.STAND_IN) {
                    boolean done = HudVignetteSkip.blitWithoutDraw(g, mc, RenderPipelines.VIGNETTE, VIGNETTE_LOCATION, 0, 0, 0.0F, 0.0F, v.w(), v.h(), v.w(),
                        v.h(), v.color());
                    check(done, "blitWithoutDraw returned true");
                }
            }
            case Blit b -> g.blit(b.pipeline(), b.texture(), b.x(), b.y(), b.u(), b.v(), b.w(), b.h(), b.tw(), b.th(), b.color());
            case Fill f -> g.fill(f.pipeline(), f.x0(), f.y0(), f.x1(), f.y1(), f.color());
            case Push p -> g.pose().pushMatrix();
            case Pop p -> g.pose().popMatrix();
            case Translate t -> g.pose().translate(t.x(), t.y());
            case Scale s -> g.pose().scale(s.x(), s.y());
            case ScissorOn s -> g.enableScissor(s.x0(), s.y0(), s.x1(), s.y1());
            case ScissorOff s -> g.disableScissor();
            case Stratum s -> g.nextStratum();
        }
    }

    /** strata -> node chain -> elementStates, NoDrawElement unwrapped; plus lastElementBounds and the current node. */
    static List<Object> structure(final GuiRenderState state) throws Throwable {
        List<?> strata = (List<?>)get(state, GuiRenderState.class, "strata");
        Object current = get(state, GuiRenderState.class, "current");
        Class<?> nodeClass = Class.forName(GuiRenderState.class.getName() + "$Node");
        List<Object> out = new ArrayList<>();
        String currentAt = "none";
        for (int i = 0; i < strata.size(); i++) {
            List<Object> chain = new ArrayList<>();
            int level = 0;
            for (Object node = strata.get(i); node != null; node = get(node, nodeClass, "up"), level++) {
                if (node == current) {
                    currentAt = i + "/" + level;
                }
                List<?> elements = (List<?>)get(node, nodeClass, "elementStates");
                List<Object> unwrapped = new ArrayList<>();
                if (elements != null) {
                    for (Object e : elements) {
                        unwrapped.add(e instanceof HudVignetteSkip.NoDrawElement nd ? nd.vanilla() : e);
                    }
                }
                chain.add(elements == null ? "null" : unwrapped);
                for (String other : new String[]{"glyphStates", "itemStates", "textStates", "picturesInPictureStates"}) {
                    check(get(node, nodeClass, other) == null, "only GUI elements in this test");
                }
            }
            out.add(chain);
        }
        out.add("current " + currentAt);
        out.add("lastElementBounds " + get(state, GuiRenderState.class, "lastElementBounds"));
        return out;
    }

    static List<GuiElementRenderState> drawOrder(final GuiRenderState state, final Comparator<GuiElementRenderState> sort) {
        state.sortElements(sort);
        List<GuiElementRenderState> order = new ArrayList<>();
        state.forEachElement(order::add, GuiRenderState.TraverseRange.ALL);
        return order;
    }

    static GuiElementRenderState unwrap(final GuiElementRenderState e) {
        return e instanceof HudVignetteSkip.NoDrawElement nd ? nd.vanilla() : e;
    }

    static int vertexCount(final GuiElementRenderState e) {
        int[] n = new int[1];
        VertexConsumer[] self = new VertexConsumer[1];
        self[0] = (VertexConsumer)Proxy.newProxyInstance(VertexConsumer.class.getClassLoader(), new Class<?>[]{VertexConsumer.class}, (proxy, m, a) -> {
            if (m.getName().startsWith("addVertex")) {
                n[0]++;
            }
            return m.getReturnType() == VertexConsumer.class ? self[0] : null;
        });
        e.buildVertices(self[0]);
        return n[0];
    }

    /** GuiRenderer.addElementsToMeshes: a new draw whenever pipeline, scissor or texture setup changes. */
    record Draw(RenderPipeline pipeline, TextureSetup textureSetup, ScreenRectangle scissor, int vertices, int vignettes) {
    }

    static List<Draw> batch(final List<GuiElementRenderState> order) {
        List<Draw> draws = new ArrayList<>();
        RenderPipeline pipeline = null;
        TextureSetup texture = null;
        ScreenRectangle scissor = null;
        int vertices = 0;
        int vignettes = 0;
        boolean open = false;
        for (GuiElementRenderState e : order) {
            boolean scissorChanged = e.scissorArea() != scissor && (e.scissorArea() == null || !e.scissorArea().equals(scissor));
            if (!open || e.pipeline() != pipeline || scissorChanged || !e.textureSetup().equals(texture)) {
                if (open) {
                    draws.add(new Draw(pipeline, texture, scissor, vertices, vignettes));
                }
                pipeline = e.pipeline();
                texture = e.textureSetup();
                scissor = e.scissorArea();
                vertices = 0;
                vignettes = 0;
                open = true;
            }
            vertices += vertexCount(e);
            vignettes += e.pipeline() == RenderPipelines.VIGNETTE ? 1 : 0;
        }
        if (open) {
            draws.add(new Draw(pipeline, texture, scissor, vertices, vignettes));
        }
        return draws;
    }

    @SuppressWarnings("unchecked")
    static void guiDifferential() throws Throwable {
        Field sortField = GuiRenderer.class.getDeclaredField("ELEMENT_SORT_COMPARATOR");
        sortField.setAccessible(true);
        Comparator<GuiElementRenderState> sort = (Comparator<GuiElementRenderState>)sortField.get(null);
        Gui gui = new Gui();
        Random r = new Random(0x0571);
        int frames = 0;
        int vignettes = 0;
        int skippedDraws = 0;
        int earlyDiffers = 0;
        int earlyFrames = 0;
        int sequences = 300;
        for (int seq = 0; seq < sequences; seq++) {
            // One GuiRenderState per mode for a run of frames (lastElementBounds carries over frames, as in the game).
            GuiRenderState[] states = {new GuiRenderState(), new GuiRenderState(), new GuiRenderState()};
            int framesInRun = 1 + r.nextInt(4);
            for (int f = 0; f < framesInRun; f++, frames++) {
                int w = r.nextInt(10) == 0 ? 1 + r.nextInt(8) : 200 + r.nextInt(500);
                int h = r.nextInt(10) == 0 ? 1 + r.nextInt(8) : 120 + r.nextInt(300);
                List<Op> ops = frame(r, w, h);
                List<List<GuiElementRenderState>> added = new ArrayList<>();
                List<List<Integer>> strataOf = new ArrayList<>();
                for (Mode mode : Mode.values()) {
                    GuiRenderState state = states[mode.ordinal()];
                    GuiGraphicsExtractor g = gui.graphics(state, w, h);
                    List<GuiElementRenderState> submitted = new ArrayList<>();
                    List<Integer> stratumOf = new ArrayList<>();
                    int stratum = 0;
                    for (Op op : ops) {
                        Set<GuiElementRenderState> before = elementSet(state);
                        apply(g, op, mode, gui.minecraft);
                        stratum += op instanceof Stratum ? 1 : 0;
                        for (GuiElementRenderState e : elementList(state)) {
                            if (!before.contains(e)) {
                                submitted.add(e);
                                stratumOf.add(stratum);
                            }
                        }
                    }
                    added.add(submitted);
                    strataOf.add(stratumOf);
                }
                List<GuiElementRenderState> va = added.get(0);
                List<GuiElementRenderState> sb = added.get(1);
                // (B) element by element: the stand-in wraps a record equal to the vanilla element.
                check(va.size() == sb.size(), "same number of elements A/B");
                for (int i = 0; i < Math.min(va.size(), sb.size()); i++) {
                    GuiElementRenderState a = va.get(i);
                    GuiElementRenderState b = sb.get(i);
                    if (a.pipeline() == RenderPipelines.VIGNETTE) {
                        vignettes++;
                        check(b instanceof HudVignetteSkip.NoDrawElement nd && nd.vanilla().equals(a) && b.pipeline() == a.pipeline()
                            && b.textureSetup().equals(a.textureSetup()) && Objects.equals(b.scissorArea(), a.scissorArea())
                            && Objects.equals(b.bounds(), a.bounds())
                            && vertexCount(b) == 0 && vertexCount(a) == 4, "stand-in == vanilla element minus vertices: " + a + " / " + b);
                    } else {
                        check(a.equals(b), "other elements equal");
                    }
                }
                List<Object> sa = structure(states[0]);
                List<Object> sbs = structure(states[1]);
                check(sa.equals(sbs), "GuiRenderState structure A == B (frame " + frames + ")\n    A " + sa + "\n    B " + sbs);
                List<GuiElementRenderState> oa = drawOrder(states[0], sort);
                List<GuiElementRenderState> ob = drawOrder(states[1], sort);
                List<GuiElementRenderState> oc = drawOrder(states[2], sort);
                check(oa.equals(ob.stream().map(HudVignetteTest::unwrap).toList()), "sorted draw order A == B");
                List<Draw> da = batch(oa);
                List<Draw> db = batch(ob);
                check(da.size() == db.size(), "same draw count A/B");
                for (int i = 0; i < Math.min(da.size(), db.size()); i++) {
                    Draw x = da.get(i);
                    Draw y = db.get(i);
                    boolean same = x.pipeline() == y.pipeline() && x.textureSetup().equals(y.textureSetup()) && Objects.equals(x.scissor(), y.scissor())
                        && x.vignettes() == y.vignettes() && y.vertices() == x.vertices() - 4 * x.vignettes();
                    check(same, "draw " + i + " A/B: " + x + " / " + y);
                    skippedDraws += x.vignettes() > 0 && y.vertices() == 0 ? 1 : 0;
                }
                // (C) return early, for contrast; and vanilla's invariant (intersecting elements keep submission order).
                List<GuiElementRenderState> aNoVig = oa.stream().filter(e -> e.pipeline() != RenderPipelines.VIGNETTE).toList();
                if (aNoVig.size() < oa.size()) {
                    earlyFrames++;
                    earlyDiffers += aNoVig.equals(oc) ? 0 : 1;
                }
                checkIntersectingOrder(va, strataOf.get(0), oa, "A (vanilla)");
                checkIntersectingOrder(added.get(2), strataOf.get(2), oc, "C (return early)");
                for (GuiRenderState s : states) {
                    s.reset();
                }
            }
        }
        System.out.printf("  %d frames, %d vignette blits: elements, structure, lastElementBounds, sorted order and batching A == B; %d vignette"
            + " draws with 0 vertices in B%n", frames, vignettes, skippedDraws);
        System.out.printf("  return-early (no element) changes the sorted draw list of the other elements in %d of %d frames with a vignette%n",
            earlyDiffers, earlyFrames);
        check(vignettes > 300 && skippedDraws > 0, "coverage: vignettes " + vignettes + ", empty draws " + skippedDraws);

        // Guard off (resource pack): blitWithoutDraw returns false and changes nothing.
        RenderSystem.setCurrentPipelineCache(new PipelineCache(null, override(ShaderType.FRAGMENT, "// pack")));
        GuiRenderState state = new GuiRenderState();
        GuiGraphicsExtractor g = gui.graphics(state, 320, 240);
        g.fill(10, 10, 20, 20, -1);
        List<Object> before = structure(state);
        boolean done = HudVignetteSkip.blitWithoutDraw(g, gui.minecraft, RenderPipelines.VIGNETTE, VIGNETTE_LOCATION, 0, 0, 0, 0, 320, 240, 320, 240,
            0xFF000000);
        check(!done && structure(state).equals(before), "overridden shaders: blitWithoutDraw returns false and adds nothing");
        RenderSystem.setCurrentPipelineCache(new PipelineCache(null, jarShaderSource()));
    }

    static List<GuiElementRenderState> elementList(final GuiRenderState state) {
        List<GuiElementRenderState> out = new ArrayList<>();
        state.forEachElement(out::add, GuiRenderState.TraverseRange.ALL);
        return out;
    }

    static Set<GuiElementRenderState> elementSet(final GuiRenderState state) {
        Set<GuiElementRenderState> set = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        set.addAll(elementList(state));
        return set;
    }

    static void checkIntersectingOrder(final List<GuiElementRenderState> submitted, final List<Integer> stratum, final List<GuiElementRenderState> order,
        final String what) {
        Map<GuiElementRenderState, Integer> position = new IdentityHashMap<>();
        for (int i = 0; i < order.size(); i++) {
            position.put(order.get(i), i);
        }
        for (int i = 0; i < submitted.size(); i++) {
            for (int j = i + 1; j < submitted.size(); j++) {
                ScreenRectangle bi = submitted.get(i).bounds();
                ScreenRectangle bj = submitted.get(j).bounds();
                if (stratum.get(i).equals(stratum.get(j)) && bi != null && bj != null && bi.intersects(bj)) {
                    if (!(position.get(submitted.get(i)) < position.get(submitted.get(j)))) {
                        check(false, what + ": intersecting elements drawn out of submission order");
                    }
                }
            }
        }
    }

    // ================================================================================================================
    // 6. StagedVertexBuffer

    static void stagedVertexBuffer() {
        RenderSystem.initRenderThread();   // getSequentialBuffer asserts the render thread (this one, as in the game)
        BlitRenderState vanilla = new BlitRenderState(RenderPipelines.VIGNETTE, TextureSetup.noTexture(), new org.joml.Matrix3x2f(), 0, 0, 427, 240, 0.0F,
            1.0F, 0.0F, 1.0F, 0xFF000000, null);
        HudVignetteSkip.NoDrawElement standIn = new HudVignetteSkip.NoDrawElement(vanilla);
        StagedVertexBuffer buffer = new StagedVertexBuffer(() -> "W1 test", 4096);
        // GuiRenderer.addElementToMesh: appendDraw for the pipeline, then buildVertices into its vertex builder.
        StagedVertexBuffer.Draw draw = buffer.appendDraw(standIn.pipeline().getVertexFormatBinding(0), standIn.pipeline().getPrimitiveTopology());
        standIn.buildVertices(buffer.getVertexBuilder(draw));
        check(draw.isEmpty(), "stand-in draw has no vertices");
        buffer.requestIndexCount(draw);
        buffer.upload();   // nothing to upload: no GPU buffer is acquired
        check(buffer.getExecuteInfo(draw) == null, "getExecuteInfo(stand-in draw) == null: GuiRenderer.executeDraw issues nothing");
        buffer.endDraw();
        buffer.close();
    }

    // ================================================================================================================
    // 7. bytecode

    static final String SKIP = "net/minecraft/client/gui/HudVignetteSkip";

    static void bytecode(final String jarPath, final String vanillaDir) throws Throwable {
        Map<String, byte[]> patched = new TreeMap<>();
        try (JarFile jar = new JarFile(jarPath)) {
            jar.stream().filter(e -> e.getName().matches("net/minecraft/client/gui/Hud(\\$.*)?\\.class")).forEach(e -> {
                try (InputStream in = jar.getInputStream(e)) {
                    patched.put(e.getName(), in.readAllBytes());
                } catch (IOException x) {
                    throw new IllegalStateException(x);
                }
            });
        }
        Map<String, byte[]> vanilla = new TreeMap<>();
        Path root = Path.of(vanillaDir);
        try (Stream<Path> files = Files.walk(root)) {
            for (Path p : files.toList()) {
                String name = root.relativize(p).toString();
                if (name.matches("net/minecraft/client/gui/Hud(\\$.*)?\\.class")) {
                    vanilla.put(name, Files.readAllBytes(p));
                }
            }
        }
        check(!vanilla.isEmpty() && patched.keySet().equals(vanilla.keySet()), "same Hud classes: " + patched.keySet() + " / " + vanilla.keySet());
        ClassFile cf = ClassFile.of(ClassFile.DebugElementsOption.DROP_DEBUG, ClassFile.LineNumbersOption.DROP_LINE_NUMBERS);
        int methods = 0;
        int instructions = 0;
        for (String name : vanilla.keySet()) {
            if (!patched.containsKey(name)) {
                continue;
            }
            ClassModel a = cf.parse(vanilla.get(name));
            ClassModel b = cf.parse(patched.get(name));
            check(a.flags().flagsMask() == b.flags().flagsMask()
                && a.superclass().map(e -> e.asInternalName()).equals(b.superclass().map(e -> e.asInternalName()))
                && a.interfaces().stream().map(e -> e.asInternalName()).toList().equals(b.interfaces().stream().map(e -> e.asInternalName()).toList()),
                name + ": class header");
            check(fields(a).equals(fields(b)), name + ": fields " + fields(a) + " / " + fields(b));
            Map<String, MethodModel> ma = methods(a);
            Map<String, MethodModel> mb = methods(b);
            check(ma.keySet().equals(mb.keySet()), name + ": methods " + ma.keySet() + " / " + mb.keySet());
            for (String m : ma.keySet()) {
                if (!mb.containsKey(m)) {
                    continue;
                }
                methods++;
                check(ma.get(m).flags().flagsMask() == mb.get(m).flags().flagsMask(), name + "." + m + ": flags");
                List<CodeElement> ca = code(ma.get(m));
                List<CodeElement> cb = code(mb.get(m));
                if (name.equals("net/minecraft/client/gui/Hud.class") && m.startsWith("extractVignette(")) {
                    cb = withoutSkipBlock(cb);
                }
                List<String> na = normalize(ca);
                List<String> nb = normalize(cb);
                instructions += na.size();
                if (!na.equals(nb)) {
                    check(false, name + "." + m + ": code differs from vanilla");
                    for (int i = 0; i < Math.max(na.size(), nb.size()); i++) {
                        String x = i < na.size() ? na.get(i) : "-";
                        String y = i < nb.size() ? nb.get(i) : "-";
                        if (!x.equals(y)) {
                            System.out.println("    first difference at " + i + ":\n      vanilla " + x + "\n      patched " + y);
                            break;
                        }
                    }
                }
            }
        }
        System.out.printf("  %d classes, %d methods, %d normalized instructions compared%n", vanilla.size(), methods, instructions);
        check(instructions > 4000, "the comparison covers the whole class (" + instructions + " instructions)");
        byte[] hud = patched.get("net/minecraft/client/gui/Hud.class");
        check(hud != null, "patched Hud.class in the jar");
        if (hud != null) {
            skipBlockLocals(hud);
        }
    }

    static List<String> fields(final ClassModel c) {
        return c.fields().stream().map(f -> f.flags().flagsMask() + " " + f.fieldName().stringValue() + " " + f.fieldType().stringValue()).toList();
    }

    static Map<String, MethodModel> methods(final ClassModel c) {
        Map<String, MethodModel> out = new LinkedHashMap<>();
        for (MethodModel m : c.methods()) {
            out.put(m.methodName().stringValue() + m.methodType().stringValue(), m);
        }
        return out;
    }

    static List<CodeElement> code(final MethodModel m) {
        return m.code().map(c -> c.elementList()).orElse(List.of());
    }

    /**
     * extractVignette: removes the W1 block [getstatic HudVignetteSkip.enabled .. return, label] and checks its shape: it
     * calls isNoop and blitWithoutDraw, every branch in it targets the label right after its return (the vanilla code),
     * and it stores nothing (no fields, no locals).
     */
    static List<CodeElement> withoutSkipBlock(final List<CodeElement> code) {
        int start = -1;
        for (int i = 0; i < code.size() && start < 0; i++) {
            if (code.get(i) instanceof FieldInstruction f && f.opcode() == Opcode.GETSTATIC && f.owner().asInternalName().equals(SKIP)
                && f.name().stringValue().equals("enabled")) {
                start = i;
            }
        }
        int blit = -1;
        for (int i = Math.max(0, start); i < code.size() && blit < 0 && start >= 0; i++) {
            if (code.get(i) instanceof InvokeInstruction inv && inv.owner().asInternalName().equals(SKIP) && inv.name().stringValue().equals("blitWithoutDraw")) {
                blit = i;
            }
        }
        int ret = -1;
        for (int i = Math.max(0, blit); i < code.size() && ret < 0 && blit >= 0; i++) {
            if (code.get(i) instanceof ReturnInstruction) {
                ret = i;
            }
        }
        check(start >= 0 && blit > start && ret > blit && ret + 1 < code.size() && code.get(ret + 1) instanceof LabelTarget,
            "extractVignette: skip block found (" + start + ", " + blit + ", " + ret + ")");
        if (!(start >= 0 && blit > start && ret > blit && ret + 1 < code.size() && code.get(ret + 1) instanceof LabelTarget)) {
            return code;
        }
        Label after = ((LabelTarget)code.get(ret + 1)).label();
        boolean isNoop = false;
        int branches = 0;
        for (int i = start; i <= ret; i++) {
            CodeElement e = code.get(i);
            if (e instanceof InvokeInstruction inv && inv.owner().asInternalName().equals(SKIP) && inv.name().stringValue().equals("isNoop")) {
                isNoop = true;
            }
            if (e instanceof BranchInstruction b) {
                branches++;
                check(b.target() == after, "skip block: every branch goes to the vanilla code after it");
            }
            check(!(e instanceof StoreInstruction) && !(e instanceof FieldInstruction f && (f.opcode() == Opcode.PUTFIELD || f.opcode() == Opcode.PUTSTATIC))
                && !(e instanceof TableSwitchInstruction) && !(e instanceof LookupSwitchInstruction), "skip block stores nothing: " + e);
        }
        check(isNoop && branches == 3, "skip block = if (enabled && isNoop(..) && blitWithoutDraw(..)) return; (branches " + branches + ")");
        // Every vanilla call of the method is still there after the block (the blit included).
        boolean vanillaBlit = false;
        for (int i = ret + 1; i < code.size(); i++) {
            if (code.get(i) instanceof InvokeInstruction inv && inv.owner().asInternalName().equals("net/minecraft/client/gui/GuiGraphicsExtractor")
                && inv.name().stringValue().equals("blit")) {
                vanillaBlit = true;
            }
        }
        check(vanillaBlit, "the vanilla graphics.blit follows the block");
        exactSkipBlock(code, start, ret, after);
        List<CodeElement> out = new ArrayList<>(code.subList(0, start));
        out.addAll(code.subList(ret + 2, code.size()));
        return out;
    }

    static final String GRAPHICS = "net/minecraft/client/gui/GuiGraphicsExtractor";

    static List<Instruction> instructions(final List<CodeElement> code) {
        List<Instruction> out = new ArrayList<>();
        for (CodeElement e : code) {
            if (e instanceof Instruction i) {
                out.add(i);
            }
        }
        return out;
    }

    /** An instruction with its operands, label-independent except that a branch to {@code after} reads "-> A". */
    static String describe(final Instruction i, final Label after) {
        return switch (i) {
            case LoadInstruction l -> "load " + l.typeKind() + " " + l.slot();
            case StoreInstruction s -> "store " + s.typeKind() + " " + s.slot();
            case FieldInstruction f -> f.opcode() + " " + f.owner().asInternalName() + "." + f.name().stringValue() + ":" + f.type().stringValue();
            case InvokeInstruction v -> v.opcode() + " " + v.owner().asInternalName() + "." + v.name().stringValue() + v.type().stringValue();
            case BranchInstruction b -> b.opcode() + (b.target() == after ? " -> A" : " -> elsewhere");
            case ReturnInstruction r -> r.opcode().toString();
            default -> i.toString();
        };
    }

    /**
     * The skip block's exact instructions, so that no wrong argument can hide in it (isNoop(strength, color & 0xFF000000)
     * skips every visible vignette yet passes the quantization, differential and Metal tests, which call HudVignetteSkip
     * directly):
     * <pre>
     *   getstatic HudVignetteSkip.enabled; ifeq A
     *   fload S; iload C; invokestatic HudVignetteSkip.isNoop(FI)Z; ifeq A
     *   [vanilla blit receiver]; aload_0; getfield Hud.minecraft; [vanilla blit's other argument instructions, verbatim]
     *   invokestatic HudVignetteSkip.blitWithoutDraw(GuiGraphicsExtractor, Minecraft, [blit's parameters])Z; ifeq A
     *   return
     * A: [vanilla blit arguments]; invokevirtual GuiGraphicsExtractor.blit; return
     * </pre>
     * C is the slot the vanilla blit loads its colour from (its last argument) and S the slot of vanilla's own border test
     * {@code if (borderWarningStrength > 0.0F)} (fload S; fconst_0; fcmpl; ifle), the only such test before the block.
     */
    static void exactSkipBlock(final List<CodeElement> code, final int start, final int ret, final Label after) {
        int blitAt = -1;
        for (int i = ret + 1; i < code.size() && blitAt < 0; i++) {
            if (code.get(i) instanceof InvokeInstruction inv && inv.owner().asInternalName().equals(GRAPHICS) && inv.name().stringValue().equals("blit")) {
                blitAt = i;
            }
        }
        if (blitAt < 0) {
            check(false, "skip block: no vanilla blit after it");
            return;
        }
        InvokeInstruction blit = (InvokeInstruction)code.get(blitAt);
        List<Instruction> args = instructions(code.subList(ret + 1, blitAt));
        List<Instruction> tail = instructions(code.subList(blitAt + 1, code.size()));
        check(tail.size() == 1 && tail.get(0) instanceof ReturnInstruction r && r.opcode() == Opcode.RETURN, "the vanilla blit is the method's last statement: " + tail);
        check(args.size() >= 2 && args.stream().allMatch(i -> i instanceof LoadInstruction || i instanceof ConstantInstruction
                || i instanceof FieldInstruction f && f.opcode() == Opcode.GETSTATIC
                || i instanceof InvokeInstruction v && v.owner().asInternalName().equals(GRAPHICS) && v.typeSymbol().parameterCount() == 0),
            "the vanilla blit's arguments are loads, constants, static fields and GuiGraphicsExtractor getters: " + args);
        int colourSlot = !args.isEmpty() && args.getLast() instanceof LoadInstruction l && l.typeKind() == TypeKind.INT ? l.slot() : -1;
        check(colourSlot >= 0, "the vanilla blit's colour (last argument) is an int local: " + (args.isEmpty() ? "-" : args.getLast()));

        List<Instruction> before = instructions(code.subList(0, start));
        List<Integer> strengthSlots = new ArrayList<>();
        for (int i = 0; i + 3 < before.size(); i++) {
            if (before.get(i) instanceof LoadInstruction l && l.typeKind() == TypeKind.FLOAT && before.get(i + 1).opcode() == Opcode.FCONST_0
                && before.get(i + 2).opcode() == Opcode.FCMPL && before.get(i + 3).opcode() == Opcode.IFLE) {
                strengthSlots.add(l.slot());
            }
        }
        check(strengthSlots.size() == 1, "one `if (borderWarningStrength > 0.0F)` test before the block, slots " + strengthSlots);
        int strengthSlot = strengthSlots.size() == 1 ? strengthSlots.getFirst() : -1;

        String blitType = blit.type().stringValue();
        List<String> expected = new ArrayList<>();
        expected.add("GETSTATIC " + SKIP + ".enabled:Z");
        expected.add("IFEQ -> A");
        expected.add("load FLOAT " + strengthSlot);
        expected.add("load INT " + colourSlot);
        expected.add("INVOKESTATIC " + SKIP + ".isNoop(FI)Z");
        expected.add("IFEQ -> A");
        if (!args.isEmpty()) {
            expected.add(describe(args.getFirst(), after));
        }
        expected.add("load REFERENCE 0");
        expected.add("GETFIELD net/minecraft/client/gui/Hud.minecraft:Lnet/minecraft/client/Minecraft;");
        for (Instruction i : args.subList(Math.min(1, args.size()), args.size())) {
            expected.add(describe(i, after));
        }
        expected.add("INVOKESTATIC " + SKIP + ".blitWithoutDraw(L" + GRAPHICS + ";Lnet/minecraft/client/Minecraft;"
            + blitType.substring(1, blitType.indexOf(')')) + ")Z");
        expected.add("IFEQ -> A");
        expected.add("RETURN");
        List<String> actual = instructions(code.subList(start, ret + 1)).stream().map(i -> describe(i, after)).toList();
        check(actual.equals(expected), "skip block instructions exact (isNoop(borderWarningStrength, color), blitWithoutDraw(graphics, this.minecraft,"
            + " <the vanilla blit's arguments>)):\n    expected " + expected + "\n    actual   " + actual);
        System.out.printf("  skip block: %d instructions exact; isNoop(fload %d, iload %d) = vanilla's border-test slot and the blit's colour slot;"
            + " blitWithoutDraw gets the blit's %d argument instructions + this.minecraft%n", actual.size(), strengthSlot, colourSlot, args.size());
    }

    /**
     * With debug info (javac -g, as tools/build-client.sh compiles): the two locals passed to isNoop are the variables
     * named {@code borderWarningStrength} and {@code color} whose scopes cover the call.
     */
    static void skipBlockLocals(final byte[] hudClass) {
        ClassModel c = ClassFile.of().parse(hudClass);
        MethodModel m = c.methods().stream().filter(x -> x.methodName().stringValue().equals("extractVignette")).findFirst().orElse(null);
        check(m != null, "Hud.extractVignette present");
        if (m == null) {
            return;
        }
        List<CodeElement> code = code(m);
        Map<Label, Integer> at = new IdentityHashMap<>();
        List<LocalVariable> locals = new ArrayList<>();
        int call = -1;
        for (int i = 0; i < code.size(); i++) {
            CodeElement e = code.get(i);
            if (e instanceof LabelTarget t) {
                at.put(t.label(), i);
            } else if (e instanceof LocalVariable v) {
                locals.add(v);
            } else if (call < 0 && e instanceof InvokeInstruction v && v.owner().asInternalName().equals(SKIP) && v.name().stringValue().equals("isNoop")) {
                call = i;
            }
        }
        List<Instruction> before = call < 0 ? List.of() : instructions(code.subList(0, call));
        check(locals.size() > 5 && before.size() >= 2, "LocalVariableTable present and isNoop call found (" + locals.size() + " locals)");
        if (before.size() < 2) {
            return;
        }
        String[] names = {"borderWarningStrength", "color"};
        String[] types = {"F", "I"};
        for (int k = 0; k < 2; k++) {
            Instruction arg = before.get(before.size() - 2 + k);
            int slot = arg instanceof LoadInstruction l ? l.slot() : -1;
            int position = call;
            List<String> named = locals.stream()
                .filter(v -> v.slot() == slot && at.getOrDefault(v.startScope(), -1) <= position && position < at.getOrDefault(v.endScope(), code.size()))
                .map(v -> v.name().stringValue() + ":" + v.type().stringValue()).toList();
            check(named.equals(List.of(names[k] + ":" + types[k])), "isNoop argument " + k + " (" + arg + ") is local " + names[k] + ": " + named);
        }
    }

    static List<String> normalize(final List<CodeElement> code) {
        Set<Label> referenced = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (CodeElement e : code) {
            switch (e) {
                case BranchInstruction b -> referenced.add(b.target());
                case TableSwitchInstruction t -> {
                    referenced.add(t.defaultTarget());
                    t.cases().forEach(c -> referenced.add(c.target()));
                }
                case LookupSwitchInstruction l -> {
                    referenced.add(l.defaultTarget());
                    l.cases().forEach(c -> referenced.add(c.target()));
                }
                case ExceptionCatch c -> {
                    referenced.add(c.tryStart());
                    referenced.add(c.tryEnd());
                    referenced.add(c.handler());
                }
                default -> {
                }
            }
        }
        Map<Label, Integer> ids = new IdentityHashMap<>();
        java.util.function.Function<Label, String> id = l -> "L" + ids.computeIfAbsent(l, k -> ids.size());
        List<String> out = new ArrayList<>();
        for (CodeElement e : code) {
            switch (e) {
                case LabelTarget t -> {
                    if (referenced.contains(t.label())) {
                        out.add(id.apply(t.label()) + ":");
                    }
                }
                case BranchInstruction b -> out.add(b.opcode() + " " + id.apply(b.target()));
                case TableSwitchInstruction t -> {
                    StringBuilder s = new StringBuilder("TABLESWITCH " + t.lowValue() + ".." + t.highValue() + " default " + id.apply(t.defaultTarget()));
                    for (SwitchCase c : t.cases()) {
                        s.append(' ').append(c.caseValue()).append("->").append(id.apply(c.target()));
                    }
                    out.add(s.toString());
                }
                case LookupSwitchInstruction l -> {
                    StringBuilder s = new StringBuilder("LOOKUPSWITCH default " + id.apply(l.defaultTarget()));
                    for (SwitchCase c : l.cases()) {
                        s.append(' ').append(c.caseValue()).append("->").append(id.apply(c.target()));
                    }
                    out.add(s.toString());
                }
                case ExceptionCatch c -> out.add("CATCH " + id.apply(c.tryStart()) + " " + id.apply(c.tryEnd()) + " " + id.apply(c.handler()) + " "
                    + c.catchType().map(x -> x.asInternalName()).orElse("any"));
                case InvokeDynamicInstruction d -> out.add("INVOKEDYNAMIC " + d.name().stringValue() + d.type().stringValue() + " bsm "
                    + d.bootstrapMethod() + " args " + d.bootstrapArgs());
                case Instruction i -> out.add(i.toString());
                default -> {
                }
            }
        }
        return out;
    }

    // ================================================================================================================
    // 8. extractVignette end to end (the patched Hud from the jar, called as Hud.extractHud calls it)

    private static boolean bootstrapped;

    static void bootstrap() {
        if (!bootstrapped) {
            // Bootstrap routes System.out/err through the logger; keep this test's plain output (the script greps it).
            java.io.PrintStream out = System.out;
            java.io.PrintStream err = System.err;
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            System.setOut(out);
            System.setErr(err);
            bootstrapped = true;
        }
    }

    /**
     * A camera / world-border setup: a border of size 100 centred on 0 (static, or shrinking 100 -> 20 over 40 ticks, which
     * makes vanilla's moving-border threshold 2 blocks/tick x 15 ticks = 30 blocks), the camera {@code distance} blocks
     * inside its east edge (negative = outside, NaN = NaN position), or no camera entity at all.
     */
    record CameraCase(String name, boolean present, double distance, int warningBlocks, boolean moving) {
    }

    static final CameraCase[] CAMERAS = {
        new CameraCase("no camera entity", false, 0.0, 5, false),
        new CameraCase("far inside", true, 40.0, 5, false),
        new CameraCase("exactly at the warning distance", true, 5.0, 5, false),
        new CameraCase("tiny warning (4.99 blocks)", true, 4.99, 5, false),
        new CameraCase("tiny warning (4.9999 blocks)", true, 4.9999, 5, false),
        new CameraCase("half warning", true, 2.5, 5, false),
        new CameraCase("on the border", true, 0.0, 5, false),
        new CameraCase("outside the border", true, -3.0, 5, false),
        new CameraCase("NaN position", true, Double.NaN, 5, false),
        new CameraCase("warningBlocks 0, on the border", true, 0.0, 0, false),
        new CameraCase("warningBlocks 0, outside", true, -1.0, 0, false),
        new CameraCase("moving border, 20 blocks (threshold 30)", true, 20.0, 5, true),
        new CameraCase("moving border, 29.99 blocks", true, 29.99, 5, true),
        new CameraCase("moving border, 31 blocks", true, 31.0, 5, true),
    };

    /** The patched Hud and the game objects extractVignette reads, built without a game (Unsafe, like Gui above). */
    static final class HudRig {
        final Gui gui;
        final Window window;
        final Field guiScaledWidth;
        final Field guiScaledHeight;
        final WorldBorder border = new WorldBorder();
        final Hud hud;
        final Method extract;
        final Entity marker;
        final List<Field> hudFields = new ArrayList<>();
        final PipelineCache vanillaCache = new PipelineCache(null, jarShaderSource());
        final PipelineCache packCache = new PipelineCache(null, override(ShaderType.FRAGMENT, "// pack"));

        HudRig() throws Throwable {
            this.gui = new Gui();
            this.window = (Window)UNSAFE.allocateInstance(Window.class);
            set(this.gui.minecraft, Minecraft.class, "window", this.window);
            this.guiScaledWidth = Window.class.getDeclaredField("guiScaledWidth");
            this.guiScaledHeight = Window.class.getDeclaredField("guiScaledHeight");
            this.guiScaledWidth.setAccessible(true);
            this.guiScaledHeight.setAccessible(true);
            ClientLevel level = (ClientLevel)UNSAFE.allocateInstance(ClientLevel.class);
            set(level, ClientLevel.class, "worldBorder", this.border);
            this.gui.minecraft.level = level;
            this.hud = (Hud)UNSAFE.allocateInstance(Hud.class);
            set(this.hud, Hud.class, "minecraft", this.gui.minecraft);
            this.extract = Hud.class.getDeclaredMethod("extractVignette", GuiGraphicsExtractor.class, Entity.class);
            this.extract.setAccessible(true);
            this.marker = (Entity)UNSAFE.allocateInstance(Marker.class);
            for (Field f : Hud.class.getDeclaredFields()) {
                if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    f.setAccessible(true);
                    this.hudFields.add(f);
                }
            }
        }

        Entity camera(final CameraCase c) throws ReflectiveOperationException {
            this.border.setCenter(0.0, 0.0);
            if (c.moving()) {
                this.border.lerpSizeBetween(100.0, 20.0, 40L, 0L);
            } else {
                this.border.setSize(100.0);
            }
            this.border.setWarningBlocks(c.warningBlocks());
            if (!c.present()) {
                return null;
            }
            set(this.marker, Entity.class, "position", new Vec3(this.border.getMaxX() - c.distance(), 64.0, 0.0));
            return this.marker;
        }

        List<Object> fieldValues() throws IllegalAccessException {
            List<Object> out = new ArrayList<>();
            for (Field f : this.hudFields) {
                out.add(f.get(this.hud));
            }
            return out;
        }

        /** Primitives by value (boxed equals: NaN == NaN), references by identity. */
        boolean sameFieldValues(final List<Object> a, final List<Object> b) {
            for (int i = 0; i < this.hudFields.size(); i++) {
                boolean same = this.hudFields.get(i).getType().isPrimitive() ? a.get(i).equals(b.get(i)) : a.get(i) == b.get(i);
                if (!same) {
                    return false;
                }
            }
            return true;
        }
    }

    /** Vanilla's border-warning strength (Hud.extractVignette), recomputed here from the same WorldBorder getters. */
    static float oracleStrength(final WorldBorder border, final Entity camera) {
        if (camera == null) {
            return 0.0F;
        }
        float distToBorder = (float)border.getDistanceToBorder(camera);
        double movingBlocksThreshold = Math.min(border.getLerpSpeed() * border.getWarningTime(), Math.abs(border.getLerpTarget() - border.getSize()));
        double warningDistance = Math.max(border.getWarningBlocks(), movingBlocksThreshold);
        return distToBorder < warningDistance ? 1.0F - (float)(distToBorder / warningDistance) : 0.0F;
    }

    /** Vanilla's vignette colour for this brightness and strength (both branches of Hud.extractVignette). */
    static int oracleColor(final float vignetteBrightness, final float strength) {
        float brightness = Mth.clamp(vignetteBrightness, 0.0F, 1.0F);
        if (strength > 0.0F) {
            float s = Mth.clamp(strength, 0.0F, 1.0F);
            float red = brightness * (1.0F - s);
            float greenBlue = brightness + (1.0F - brightness) * s;
            return ARGB.colorFromFloat(1.0F, red, greenBlue, greenBlue);
        }
        return ARGB.colorFromFloat(1.0F, brightness, brightness, brightness);
    }

    /**
     * Runs the patched {@code Hud.extractVignette} with the toggle off (= vanilla: the bytecode section proves the rest
     * of the method is vanilla's), on, and (guardCase) on with resource-pack shaders, each into a fresh GuiRenderState
     * prepared identically, and checks the result against the independent oracle. Returns 1 if it expected a skip.
     */
    static int e2eCase(final HudRig t, final CameraCase c, final float vb, final boolean gameLike, final boolean guardCase, final Random r, final int[] counts)
        throws Throwable {
        Entity camera = t.camera(c);
        float strength = oracleStrength(t.border, camera);
        int colour = oracleColor(vb, strength);
        boolean expectSkip = !(strength > 0.0F) && (colour & 0x00FFFFFF) == 0;
        int w = gameLike ? 427 : 1 + r.nextInt(900);
        int h = gameLike ? 240 : 1 + r.nextInt(500);
        long seed = r.nextLong();
        t.guiScaledWidth.setInt(t.window, w);
        t.guiScaledHeight.setInt(t.window, h);
        t.hud.vignetteBrightness = vb;
        int modes = guardCase ? 3 : 2;
        GuiElementRenderState[] added = new GuiElementRenderState[modes];
        List<List<Object>> structures = new ArrayList<>();
        for (int mode = 0; mode < modes; mode++) {
            HudVignetteSkip.enabled = mode != 0;
            RenderSystem.setCurrentPipelineCache(mode == 2 ? t.packCache : t.vanillaCache);
            GuiRenderState state = new GuiRenderState();
            GuiGraphicsExtractor g = t.gui.graphics(state, w, h);
            if (!gameLike) {
                // Elements before it, a pose and a scissor (the HUD calls it first, at identity, but the skip must hold anyway).
                Random p = new Random(seed);
                if (p.nextBoolean()) {
                    g.fill(RenderPipelines.GUI, p.nextInt(w + 1), p.nextInt(h + 1), p.nextInt(w + 1), p.nextInt(h + 1), p.nextInt());
                }
                if (p.nextBoolean()) {
                    g.pose().pushMatrix();
                    g.pose().translate(p.nextInt(41) - 20 + (p.nextBoolean() ? 0.5F : 0.0F), p.nextInt(41) - 20);
                    g.pose().scale(0.5F + p.nextInt(4) * 0.5F, 0.5F + p.nextInt(4) * 0.5F);
                }
                if (p.nextBoolean()) {
                    int x0 = p.nextInt(w);
                    int y0 = p.nextInt(h);
                    g.enableScissor(x0, y0, x0 + p.nextInt(w), y0 + p.nextInt(h));
                }
                if (p.nextInt(4) == 0) {
                    g.nextStratum();
                }
            }
            Set<GuiElementRenderState> before = elementSet(state);
            List<Object> fieldsBefore = t.fieldValues();
            t.extract.invoke(t.hud, g, camera);
            check(t.sameFieldValues(fieldsBefore, t.fieldValues()), "extractVignette changes no Hud field (mode " + mode + ")");
            List<GuiElementRenderState> fresh = elementList(state).stream().filter(e -> !before.contains(e)).toList();
            check(fresh.size() <= 1, "extractVignette adds at most one element (mode " + mode + "): " + fresh);
            added[mode] = fresh.isEmpty() ? null : fresh.getFirst();
            structures.add(structure(state));
        }
        RenderSystem.setCurrentPipelineCache(t.vanillaCache);
        HudVignetteSkip.enabled = true;

        String what = c.name() + ", vb " + vb + " (0x" + Integer.toHexString(Float.floatToRawIntBits(vb)) + "), oracle strength " + strength + " colour "
            + Integer.toHexString(colour) + (gameLike ? "" : ", random pose/scissor/elements");
        counts[0]++;
        if (added[0] == null) {
            // GuiRenderState.addGuiElement drops an element whose bounds are empty (a scissor outside the screen area): vanilla
            // adds nothing, and neither may the skip path (blitWithoutDraw goes through the same addGuiElement).
            check(!gameLike && Arrays.stream(added).allMatch(Objects::isNull), "an element is dropped only under a random scissor, and in every mode (" + what + ")");
            for (int mode = 1; mode < modes; mode++) {
                check(structures.get(mode).equals(structures.get(0)), "dropped element: GuiRenderState structure equal in mode " + mode + " (" + what + ")");
            }
            counts[5]++;
            return expectSkip ? 1 : 0;
        }
        GuiElementRenderState vanilla = added[0];
        check(vanilla instanceof BlitRenderState b && b.pipeline() == RenderPipelines.VIGNETTE && b.color() == colour && vertexCount(b) == 4,
            "toggle off: the vanilla VIGNETTE blit with the oracle's colour: " + vanilla + " (" + what + ")");
        GuiElementRenderState on = added[1];
        if (expectSkip) {
            check(on instanceof HudVignetteSkip.NoDrawElement nd && nd.vanilla().equals(vanilla) && vertexCount(on) == 0,
                "toggle on, no border warning, RGB 0: the stand-in of the vanilla element: " + on + " (" + what + ")");
        } else {
            check(!(on instanceof HudVignetteSkip.NoDrawElement) && Objects.equals(on, vanilla), "toggle on, not a no-op: the vanilla element: " + on + " (" + what + ")");
        }
        check(structures.get(0).equals(structures.get(1)), "GuiRenderState structure, toggle off == on (" + what + ")");
        if (guardCase) {
            check(!(added[2] instanceof HudVignetteSkip.NoDrawElement) && Objects.equals(added[2], vanilla) && structures.get(2).equals(structures.get(0)),
                "toggle on, resource-pack shaders: the vanilla element: " + added[2] + " (" + what + ")");
            counts[4]++;
        }
        counts[expectSkip ? 1 : 2]++;
        counts[3] += strength > 0.0F && (colour & 0x00FFFFFF) == 0 ? 1 : 0;
        return expectSkip ? 1 : 0;
    }

    static void extractVignetteEndToEnd() throws Throwable {
        bootstrap();
        HudRig t = new HudRig();
        float boundary = 1.0F / 255.0F;
        while (ARGB.as8BitChannel(boundary) != 0) {
            boundary = Math.nextDown(boundary);
        }
        while (ARGB.as8BitChannel(Math.nextUp(boundary)) == 0) {
            boundary = Math.nextUp(boundary);
        }
        float[] vbs = {0.0F, -0.0F, Float.MIN_VALUE, 1e-6F, Math.nextDown(boundary), boundary, Math.nextUp(boundary), 1.0F / 255.0F, 0.002F, 0.1F, 0.5F,
            1.0F, Math.nextUp(1.0F), 2.0F, -1.0F, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY};
        int[] counts = new int[6];   // calls, expected skips, expected draws, border warnings with RGB 0, guard-off runs, dropped (empty bounds)
        int[] perCamera = new int[CAMERAS.length];
        Random r = new Random(0x0E2E);
        for (int ci = 0; ci < CAMERAS.length; ci++) {
            for (float vb : vbs) {
                for (int variant = 0; variant < 2; variant++) {
                    perCamera[ci] += e2eCase(t, CAMERAS[ci], vb, variant == 0, variant == 1 || r.nextInt(4) == 0, r, counts);
                }
            }
        }
        for (int i = 0; i < 3000; i++) {
            boolean moving = r.nextInt(3) == 0;
            CameraCase c = new CameraCase("random camera", r.nextInt(8) != 0, moving ? r.nextDouble() * 40.0 - 2.0 : r.nextDouble() * 12.0 - 2.0,
                r.nextInt(3) == 0 ? r.nextInt(10) : 5, moving);
            float vb = switch (r.nextInt(4)) {
                case 0 -> Float.intBitsToFloat(Float.floatToRawIntBits(boundary) + r.nextInt(2001) - 1000);
                case 1 -> r.nextFloat() * 0.01F;
                case 2 -> r.nextFloat() * 1.2F - 0.1F;
                default -> Float.intBitsToFloat(r.nextInt());
            };
            e2eCase(t, c, vb, r.nextInt(3) == 0, r.nextInt(4) == 0, r, counts);
        }
        StringBuilder skipsByCamera = new StringBuilder();
        for (int ci = 0; ci < CAMERAS.length; ci++) {
            skipsByCamera.append(ci == 0 ? "" : ", ").append(CAMERAS[ci].name()).append(' ').append(perCamera[ci]);
        }
        System.out.printf("  %d extractVignette calls x (off, on[, guard off]): %d skipped, %d drawn (%d of them border warnings with RGB 0), %d guard-off runs,"
            + " %d dropped by vanilla (empty scissor) in every mode%n", counts[0], counts[1], counts[2], counts[3], counts[4], counts[5]);
        System.out.println("  skips per fixed camera case (of " + 2 * vbs.length + "; each call checked: skipped exactly when expected): " + skipsByCamera);
        check(counts[1] > 300 && counts[2] > 500 && counts[3] > 20 && counts[4] > 500, "coverage: " + Arrays.toString(counts));
        // The camera cases do what their names say: a warning (no skip) at 4.99 / 4.9999 / 2.5 / 0 / -3 blocks, with
        // warningBlocks 0 outside the border and within the moving border's 30 blocks; none (same skips as without a
        // camera) far inside, exactly at the warning distance, at a NaN position, on the border with warningBlocks 0 and
        // 31 blocks inside the moving border.
        check(perCamera[3] == 0 && perCamera[4] == 0 && perCamera[5] == 0 && perCamera[6] == 0 && perCamera[7] == 0 && perCamera[10] == 0
            && perCamera[11] == 0 && perCamera[12] == 0, "no skip with a border warning: " + skipsByCamera);
        check(perCamera[0] > 0 && perCamera[0] == perCamera[1] && perCamera[1] == perCamera[2] && perCamera[2] == perCamera[8]
            && perCamera[8] == perCamera[9] && perCamera[9] == perCamera[13], "same skips for every camera without a warning: " + skipsByCamera);
        HudVignetteSkip.enabled = true;
        RenderSystem.setCurrentPipelineCache(new PipelineCache(null, jarShaderSource()));
    }

    // ================================================================================================================
    // 9. shader guard through the game's own resource loading

    /**
     * The shader sources a resource reload produces with the vanilla pack (its resource layers, as {@code Pack.open} ->
     * {@code PackRepository.openAllSelected} hands them to the reload's MultiPackResourceManager) plus {@code packs} on top:
     * ShaderManager.prepare -> loadConfigs(resourceManager), the Configs that ShaderManager.apply puts into the new current
     * PipelineCache (its shaderSource, which HudVignetteSkip.vanillaShaders compares).
     */
    static ShaderSource gameShaders(final Method createVanillaPack, final Path externalAssets, final Method loadConfigs, final List<PackResources> packs)
        throws Throwable {
        VanillaPackResources vanilla = (VanillaPackResources)createVanillaPack.invoke(null, externalAssets);
        List<PackResources> all = new ArrayList<>(vanilla.asResourcesSupplier().openResources(null, null).toList());   // both arguments unused
        check(all.size() >= 2, "vanilla pack: jar layer + asset index layer (" + all.size() + " layers)");
        all.addAll(packs);
        return (ShaderSource)loadConfigs.invoke(null, new MultiPackResourceManager(PackType.CLIENT_RESOURCES, all));
    }

    static void shaderGuardGameLoader() throws Throwable {
        bootstrap();
        String mcDir = System.getProperty("w1.mcDir");
        check(mcDir != null, "-Dw1.mcDir set (tools/hud-vignette-test.sh passes the game directory)");
        if (mcDir == null) {
            return;
        }
        Path mc = Path.of(mcDir);
        Matcher index = Pattern.compile("\"assets\"\\s*:\\s*\"([^\"]+)\"").matcher(Files.readString(mc.resolve("versions/26.3/26.3.json")));
        check(index.find(), "asset index id in versions/26.3/26.3.json");
        // GameConfig.LocationInfo.getExternalAssetSource() with the launcher's --assetsDir / --assetIndex.
        Path externalAssets = IndexedAssetSource.createIndexFs(mc.resolve("assets"), index.group(1));
        Method vanillaPack = ClientPackSource.class.getDeclaredMethod("createVanillaPackSource", Path.class);
        vanillaPack.setAccessible(true);
        Method loadConfigs = ShaderManager.class.getDeclaredMethod("loadConfigs", ResourceManager.class);
        loadConfigs.setAccessible(true);
        Method vanillaShaders = HudVignetteSkip.class.getDeclaredMethod("vanillaShaders", RenderPipeline.class);
        vanillaShaders.setAccessible(true);
        Method builtIn = HudVignetteSkip.class.getDeclaredMethod("builtInShader", Identifier.class, ShaderType.class);
        builtIn.setAccessible(true);
        RenderPipeline p = RenderPipelines.VIGNETTE;
        Identifier id = Identifier.withDefaultNamespace("core/position_tex_color");

        // No resource pack: the vanilla pack as the game builds it (jar resources + the asset index layer).
        ShaderSource game = gameShaders(vanillaPack, externalAssets, loadConfigs, List.of());
        for (ShaderType type : new ShaderType[]{ShaderType.VERTEX, ShaderType.FRAGMENT}) {
            String loaded = game.getShader(id, type);
            String guard = (String)builtIn.invoke(null, id, type);
            check(loaded != null && loaded.equals(guard), type + ": the game's loader and the guard read the same source ("
                + (loaded == null ? "null" : loaded.length() + " chars") + " / " + (guard == null ? "null" : guard.length() + " chars") + ")");
        }
        RenderSystem.setCurrentPipelineCache(new PipelineCache(null, game));
        check((Boolean)vanillaShaders.invoke(null, p), "vanilla resources as the game loads them: the guard accepts them (the skip is active)");

        // Resource packs on top (PathPackResources, as for a folder pack in resourcepacks/), highest priority last.
        String vsh = (String)builtIn.invoke(null, id, ShaderType.VERTEX);
        String fsh = (String)builtIn.invoke(null, id, ShaderType.FRAGMENT);
        String vshPath = "assets/minecraft/shaders/core/position_tex_color.vsh";
        String fshPath = "assets/minecraft/shaders/core/position_tex_color.fsh";
        Object[][] packCases = {
            {"fragment stage overridden", Map.of(fshPath, (fsh + "\n// pack\n").getBytes(StandardCharsets.UTF_8)), false},
            {"vertex stage overridden", Map.of(vshPath, vsh.replace("vertexColor = Color;", "vertexColor = vec4(1.0);").getBytes(StandardCharsets.UTF_8)), false},
            {"fragment stage with CRLF line ends", Map.of(fshPath, fsh.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8)), false},
            {"byte-identical copies of both stages", Map.of(vshPath, jarBytes(vshPath), fshPath, jarBytes(fshPath)), true},
            {"an unrelated shader only", Map.of("assets/minecraft/shaders/core/rendertype_lines.fsh", "// pack\n".getBytes(StandardCharsets.UTF_8)), true},
        };
        Path packs = Files.createTempDirectory("w1-packs");
        try {
            for (int n = 0; n < packCases.length; n++) {
                Path root = packs.resolve("pack" + n);
                @SuppressWarnings("unchecked")
                Map<String, byte[]> files = (Map<String, byte[]>)packCases[n][1];
                for (Map.Entry<String, byte[]> f : files.entrySet()) {
                    Path file = root.resolve(f.getKey());
                    Files.createDirectories(file.getParent());
                    Files.write(file, f.getValue());
                }
                PackResources pack = new PathPackResources(
                    new PackLocationInfo("file/w1-pack" + n, Component.literal("W1 test pack " + n), PackSource.DEFAULT, Optional.empty()), root);
                ShaderSource withPack = gameShaders(vanillaPack, externalAssets, loadConfigs, List.of(pack));
                RenderSystem.setCurrentPipelineCache(new PipelineCache(null, withPack));
                boolean expected = (Boolean)packCases[n][2];
                check((Boolean)vanillaShaders.invoke(null, p) == expected, "resource pack, " + packCases[n][0] + ": guard " + (expected ? "accepts" : "rejects"));
            }
        } finally {
            try (Stream<Path> walk = Files.walk(packs)) {
                for (Path f : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(f);
                }
            }
        }
        RenderSystem.setCurrentPipelineCache(new PipelineCache(null, jarShaderSource()));
    }

    static byte[] jarBytes(final String path) throws IOException {
        try (InputStream in = HudVignetteTest.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("not in the jar: " + path);
            }
            return in.readAllBytes();
        }
    }

    // ================================================================================================================
    // 10. Metal

    static final int RT_USAGE = GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING;

    static void metal() throws Throwable {
        MetalBackend backend = new MetalBackend();
        backend.loadLibrary();
        GpuDevice device = backend.createDevice(new GpuDebugOptions(0, false, false, true));
        Field field = RenderSystem.class.getDeclaredField("DEVICE");
        field.setAccessible(true);
        field.set(null, device);
        try (Rig rig = new Rig(device)) {
            rig.offscreen();
            rig.fused();
            List<String> messages = device.getLastDebugMessages();
            for (String m : messages) {
                System.out.println("  device message: " + m);
            }
            check(messages.isEmpty(), "no device messages");
        } finally {
            device.close();
        }
    }

    static final class Rig implements AutoCloseable {
        final GpuDevice device;
        final CommandEncoder enc;
        final ExecutorService workers = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "w1 compile");
            t.setDaemon(true);
            return t;
        });
        final List<AutoCloseable> owned = new ArrayList<>();
        final CompiledRenderPipeline vignette;
        final CompiledRenderPipeline opaque;
        final GpuBuffer quadIndices;
        final GpuTextureView vignetteTexture;
        final GpuTextureView randomTexture;
        final GpuSampler[] samplers;
        final String[] samplerNames = {"game default (repeat, nearest min, linear mag)", "nearest clamp", "linear clamp"};
        int vignetteTexels;

        Rig(final GpuDevice device) throws Throwable {
            this.device = device;
            this.enc = device.createCommandEncoder();
            ShaderSource shaders = jarShaderSource();
            this.vignette = this.own(device.compilePipeline(RenderPipelines.VIGNETTE, shaders, this.workers).join().finishCompile());
            this.opaque = this.own(device.compilePipeline(RenderPipelines.GUI_OPAQUE_TEXTURED_BACKGROUND, shaders, this.workers).join().finishCompile());
            check(this.vignette != null && this.opaque != null, "pipelines compile");
            ByteBuffer idx = direct(6 * 2);
            for (int i : new int[]{0, 1, 2, 2, 3, 0}) {
                idx.putShort((short)i);
            }
            this.quadIndices = this.own(device.createBuffer(() -> "w1 quad indices", GpuBuffer.USAGE_INDEX, idx.flip()));
            // vanilla vignette.png as the game loads it (RGBA8), and a random texture with ~30% alpha-0 texels
            BufferedImage img;
            try (InputStream in = HudVignetteTest.class.getClassLoader().getResourceAsStream("assets/minecraft/textures/misc/vignette.png")) {
                img = ImageIO.read(Objects.requireNonNull(in, "vignette.png"));
            }
            int vw = img.getWidth();
            int vh = img.getHeight();
            ByteBuffer texels = direct(vw * vh * 4);
            for (int y = 0; y < vh; y++) {
                for (int x = 0; x < vw; x++) {
                    int argb = img.getRGB(x, y);
                    texels.put((byte)(argb >> 16)).put((byte)(argb >> 8)).put((byte)argb).put((byte)(argb >>> 24));
                    this.vignetteTexels += (argb & 0xFFFFFF) != 0 && (argb >>> 24) != 0 ? 1 : 0;
                }
            }
            System.out.printf("  vignette.png %dx%d, %d texels with RGB != 0 and alpha != 0%n", vw, vh, this.vignetteTexels);
            GpuTexture vt = this.own(device.createTexture((String)"w1 vignette.png", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                GpuFormat.RGBA8_UNORM, vw, vh, 1, 1));
            this.enc.writeToTexture(vt, texels.flip(), 0, 0, 0, 0, vw, vh);
            this.vignetteTexture = this.own(device.createTextureView(vt));
            Random rnd = new Random(77);
            ByteBuffer random = direct(64 * 64 * 4);
            for (int i = 0; i < 64 * 64; i++) {
                random.put((byte)rnd.nextInt()).put((byte)rnd.nextInt()).put((byte)rnd.nextInt()).put((byte)(rnd.nextInt(10) < 3 ? 0 : rnd.nextInt()));
            }
            GpuTexture rt = this.own(device.createTexture((String)"w1 random texture", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                GpuFormat.RGBA8_UNORM, 64, 64, 1, 1));
            this.enc.writeToTexture(rt, random.flip(), 0, 0, 0, 0, 64, 64);
            this.randomTexture = this.own(device.createTextureView(rt));
            this.samplers = new GpuSampler[]{
                this.own(device.createSampler(AddressMode.REPEAT, AddressMode.REPEAT, FilterMode.NEAREST, FilterMode.LINEAR, 1, OptionalDouble.empty())),
                this.own(device.createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, FilterMode.NEAREST, FilterMode.NEAREST, 1,
                    OptionalDouble.empty())),
                this.own(device.createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, FilterMode.LINEAR, FilterMode.LINEAR, 1,
                    OptionalDouble.empty()))};
        }

        <T> T own(final T resource) {
            if (resource instanceof AutoCloseable c) {
                this.owned.add(c);
            }
            return resource;
        }

        @Override
        public void close() {
            for (int i = this.owned.size() - 1; i >= 0; i--) {
                try {
                    this.owned.get(i).close();
                } catch (Exception ignored) {
                }
            }
            this.workers.shutdownNow();
        }

        GpuBufferSlice ubo(final ByteBuffer data) {
            return this.enc.transientMemory().uploadGpu(data, this.device.getDeviceInfo().limits().minUniformOffsetAlignment(), GpuBuffer.USAGE_UNIFORM);
        }

        byte[] readback(final GpuTexture texture) {
            int bytes = texture.getWidth(0) * texture.getHeight(0) * 4;
            GpuBuffer buffer = this.device.createBuffer(() -> "w1 readback", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, bytes);
            this.enc.copyTextureToBuffer(texture, buffer, 0, () -> {}, 0);
            GpuFence fence = this.enc.createFence();
            this.enc.submit();
            check(fence.awaitCompletion(5_000_000_000L), "readback fence");
            fence.close();
            byte[] out = new byte[bytes];
            try (GpuBufferSlice.MappedView view = buffer.slice(0, bytes).map(true, false)) {
                view.data().get(out);
            }
            buffer.close();
            return out;
        }

        /** Random RGBA8 content in which every byte value occurs in every channel (the first 256 pixels are a ramp). */
        static ByteBuffer randomContent(final Random rnd, final int w, final int h) {
            ByteBuffer b = direct(w * h * 4);
            for (int i = 0; i < w * h; i++) {
                if (i < 256) {
                    b.put((byte)i).put((byte)(255 - i)).put((byte)(i * 7)).put((byte)(i * 13));
                } else {
                    b.putInt(rnd.nextInt());
                }
            }
            return b.flip();
        }

        /**
         * The GUI pass as GuiRenderer.executeDrawRange + executeDraw issue it for the vignette: main colour + depth, no
         * clears, the GUI projection (ortho over window/guiScale, reverse-Z near 11000 / far 1000) and DynamicTransforms
         * (translation z -11000, ColorModulator 1), the blit's quad (x 0..guiWidth, y 0..guiHeight, uv 0..1) with the packed
         * colour as UNORM8 RGBA, Sampler0. {@code draw == false}: the same pass with no draw (the stand-in's frame).
         */
        void guiPass(final GpuTexture color, final GpuTexture depth, final CompiledRenderPipeline pipeline, final GpuTextureView texture,
            final GpuSampler sampler, final int argb, final int guiWidth, final int guiHeight, final float projWidth, final float projHeight,
            final boolean draw) {
            ByteBuffer proj = direct(64);
            new Matrix4f().setOrtho(0.0F, projWidth, projHeight, 0.0F, 11000.0F, 1000.0F, this.device.getDeviceInfo().isZZeroToOne()).get(0, proj);
            ByteBuffer transforms = direct(160);
            new Matrix4f().setTranslation(0.0F, 0.0F, -11000.0F).get(0, transforms);
            new Matrix4f().get(64, transforms);
            transforms.putFloat(128, 1).putFloat(132, 1).putFloat(136, 1).putFloat(140, 1);
            ByteBuffer vb = direct(4 * 24);
            float[][] corners = {{0, 0, 0, 0}, {0, guiHeight, 0, 1}, {guiWidth, guiHeight, 1, 1}, {guiWidth, 0, 1, 0}};
            for (float[] c : corners) {
                vb.putFloat(c[0]).putFloat(c[1]).putFloat(0.0F).putFloat(c[2]).putFloat(c[3]);
                vb.put((byte)(argb >> 16)).put((byte)(argb >> 8)).put((byte)argb).put((byte)(argb >>> 24));
            }
            GpuBuffer vertices = this.own(this.device.createBuffer(() -> "w1 vb", GpuBuffer.USAGE_VERTEX, vb.flip()));
            try (RenderPass p = this.enc.createRenderPass(() -> "GUI before blur", this.own(this.device.createTextureView(color)), Optional.empty(),
                depth == null ? null : this.own(this.device.createTextureView(depth)), OptionalDouble.empty())) {
                if (draw) {
                    p.setPipeline(pipeline);
                    p.setUniform("Projection", this.ubo(proj));
                    p.setUniform("DynamicTransforms", this.ubo(transforms));
                    p.setVertexBuffer(0, vertices.slice());
                    p.disableScissor();
                    p.setUniform("Sampler0", texture, sampler);
                    p.setIndexBuffer(this.quadIndices, IndexType.SHORT);
                    p.drawIndexed(6, 1, 0, 0, 0);
                }
            }
        }

        static int diff(final byte[] a, final byte[] b) {
            int n = 0;
            for (int i = 0; i < Math.min(a.length, b.length); i++) {
                n += a[i] != b[i] ? 1 : 0;
            }
            return n + Math.abs(a.length - b.length);
        }

        void offscreen() {
            int[][] sizes = {{320, 200, 1}, {257, 131, 3}};
            Random rnd = new Random(0x0A11);
            int comparisons = 0;
            for (int[] size : sizes) {
                int w = size[0];
                int h = size[1];
                int scale = size[2];
                int guiW = (int)Math.ceil(w / (double)scale);   // Window.getGuiScaledWidth (rounded up)
                int guiH = (int)Math.ceil(h / (double)scale);
                for (int tex = 0; tex < 2; tex++) {
                    GpuTextureView texture = tex == 0 ? this.vignetteTexture : this.randomTexture;
                    for (int s = 0; s < this.samplers.length; s++) {
                        int[] colours = {0xFF000000, 0x00000000, 0x7F000000, 0xFF010101, 0xFFFFFFFF};
                        for (int argb : colours) {
                            ByteBuffer content = randomContent(rnd, w, h);
                            byte[] contentBytes = toArray(content);
                            double depthValue = rnd.nextDouble();
                            GpuTexture color = this.own(this.device.createTexture((String)"w1 main", RT_USAGE, GpuFormat.RGBA8_UNORM, w, h, 1, 1));
                            GpuTexture depth = this.own(this.device.createTexture((String)"w1 depth", RT_USAGE, GpuFormat.D32_FLOAT, w, h, 1, 1));
                            this.enc.writeToTexture(color, content, 0, 0, 0, 0, w, h);
                            this.enc.clearDepthTexture(depth, depthValue);
                            byte[] before = this.readback(color);
                            byte[] depthBefore = this.readback(depth);
                            check(diff(before, contentBytes) == 0, "upload round-trips");
                            this.guiPass(color, depth, this.vignette, texture, this.samplers[s], argb, guiW, guiH, (float)w / scale, (float)h / scale, false);
                            byte[] noDraw = this.readback(color);
                            check(diff(noDraw, before) == 0, "empty GUI pass changes nothing");
                            this.guiPass(color, depth, this.vignette, texture, this.samplers[s], argb, guiW, guiH, (float)w / scale, (float)h / scale, true);
                            byte[] after = this.readback(color);
                            byte[] depthAfter = this.readback(depth);
                            int d = diff(after, before);
                            int dd = diff(depthAfter, depthBefore);
                            String what = String.format("%dx%d scale %d, %s, sampler %s, colour %08x", w, h, scale, tex == 0 ? "vignette.png" : "random texture",
                                this.samplerNames[s], argb);
                            comparisons++;
                            check(dd == 0, what + ": depth unchanged (" + dd + " bytes differ)");
                            if ((argb & 0xFFFFFF) == 0) {
                                check(d == 0, what + ": colour bytes identical to no draw (" + d + " differ)");
                            } else {
                                check(d > (argb == 0xFFFFFFFF ? w * h / 4 : 0), what + ": positive control changes pixels (" + d + " bytes)");
                                if (argb == 0xFFFFFFFF) {
                                    System.out.printf("  control %s: %d bytes changed%n", what, d);
                                }
                            }
                        }
                    }
                }
            }
            System.out.printf("  offscreen: %d draws compared against no draw (RGB 0 identical; RGB 1/255 and white change pixels)%n", comparisons);
        }

        /**
         * Present fusion (CONTRACT.md 16.3, default on in game): the frame's GUI passes render straight into the BGRA8
         * drawable. Frame: clear main, an opaque full-screen blit of random content (GUI_OPAQUE_TEXTURED_BACKGROUND, nearest),
         * the GUI pass with or without the vignette, PRESENT; the next-frame readback restores main from the drawable.
         */
        void fused() throws Throwable {
            int w = 96;
            int h = 64;
            Field backendField = Class.forName("com.mojang.renderpearl.frontend.FrontendGpuDevice").getDeclaredField("backend");
            backendField.setAccessible(true);
            Object metalDevice = backendField.get(this.device);
            Class<?> metalDeviceClass = Class.forName("com.mojang.renderpearl.backend.metal.MetalDevice");
            Class<?> surfaceClass = Class.forName("com.mojang.renderpearl.backend.metal.MetalSurface");
            Class<?> nativesClass = Class.forName("com.mojang.renderpearl.backend.metal.MetalSurface$Natives");
            Object metalEncoder = metalDeviceClass.getMethod("encoder").invoke(metalDevice);
            Method lastResultFlags = metalEncoder.getClass().getDeclaredMethod("lastResultFlags");
            lastResultFlags.setAccessible(true);
            long layer = ObjC.newMetalLayer();
            Object natives = Proxy.newProxyInstance(nativesClass.getClassLoader(), new Class<?>[]{nativesClass}, (proxy, m, a) -> switch (m.getName()) {
                case "createView" -> 0x5EED0000L;
                case "getLayer" -> layer;
                case "destroyView" -> null;
                case "lastSdlError" -> "";
                case "windowFlags" -> 0L;
                case "surfaceCreate" -> MtlNative.surfaceCreate((Long)a[0], (Long)a[1], (Long)a[2]);
                case "surfaceConfigure" -> MtlNative.surfaceConfigure((Long)a[0], (Long)a[1]);
                case "surfacePresent" -> MtlNative.surfacePresent((Long)a[0]);
                case "surfaceDestroy" -> MtlNative.surfaceDestroy((Long)a[0]);
                case "toString" -> "w1 surface natives";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == a[0];
                default -> throw new UnsupportedOperationException(m.getName());
            });
            Method forTesting = surfaceClass.getDeclaredMethod("forTesting", metalDeviceClass, long.class, BooleanSupplier.class, nativesClass);
            forTesting.setAccessible(true);
            Object backendSurface = forTesting.invoke(null, metalDevice, 1L, (BooleanSupplier)() -> false, natives);
            GpuSurface surface = new FrontendGpuSurface((com.mojang.renderpearl.backend.api.GpuSurfaceBackend)backendSurface);
            surface.configure(new GpuSurface.Configuration(w, h, GpuSurface.PresentMode.IMMEDIATE));

            Random rnd = new Random(0xF05E);
            GpuTexture content = this.own(this.device.createTexture((String)"w1 fused content", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                GpuFormat.RGBA8_UNORM, w, h, 1, 1));
            ByteBuffer contentBytes = randomContent(rnd, w, h);
            // alpha 0 texels are discarded by position_tex_color: the clear colour (alpha 0) shows through there
            this.enc.writeToTexture(content, contentBytes, 0, 0, 0, 0, w, h);
            GpuTextureView contentView = this.own(this.device.createTextureView(content));
            Vector4f clear = new Vector4f(0.2F, 0.4F, 0.6F, 0.0F);
            GpuTexture main = this.own(this.device.createTexture((String)"w1 fused main", RT_USAGE, GpuFormat.RGBA8_UNORM, w, h, 1, 1));
            GpuTexture depth = this.own(this.device.createTexture((String)"w1 fused depth", RT_USAGE, GpuFormat.D32_FLOAT, w, h, 1, 1));
            GpuTextureView mainView = this.own(this.device.createTextureView(main));

            // reference: the same frame offscreen, no vignette draw, no surface
            GpuTexture refMain = this.own(this.device.createTexture((String)"w1 ref main", RT_USAGE, GpuFormat.RGBA8_UNORM, w, h, 1, 1));
            this.enc.clearColorAndDepthTextures(refMain, clear, depth, 0.0);
            this.guiPass(refMain, depth, this.opaque, contentView, this.samplers[1], 0xFFFFFFFF, w, h, w, h, true);
            this.guiPass(refMain, depth, this.vignette, this.vignetteTexture, this.samplers[0], 0xFF000000, w, h, w, h, false);
            byte[] reference = this.readback(refMain);

            int frames = 36;
            int presented = 0;
            int[] mismatches = new int[3];
            int[] controlsChanged = new int[1];
            for (int f = 0; f < frames; f++) {
                int variant = f % 3;   // 0: no vignette draw (stand-in), 1: vignette colour 0, 2: control colour 1/255
                surface.acquireNextTexture();
                this.enc.clearColorAndDepthTextures(main, clear, depth, 0.0);   // frame-start clear (GameRenderer)
                this.guiPass(main, depth, this.opaque, contentView, this.samplers[1], 0xFFFFFFFF, w, h, w, h, true);
                this.guiPass(main, depth, this.vignette, this.vignetteTexture, this.samplers[0], variant == 2 ? 0xFF010101 : 0xFF000000, w, h, w, h,
                    variant != 0);
                surface.blitFromTexture(this.enc, mainView);
                this.enc.submit();
                int flags = (Integer)lastResultFlags.invoke(metalEncoder);
                check((flags & MtlNative.SUBMIT_RESULT_FLAG_DRAWABLE_NIL) == 0, "fused frame " + f + ": no nil drawable");
                presented += (flags & MtlNative.SUBMIT_RESULT_FLAG_PRESENT_ENCODED) != 0 ? 1 : 0;
                surface.present();
                byte[] img = this.readback(main);
                int d = diff(img, reference);
                if (variant == 2) {
                    controlsChanged[0] += d > 0 ? 1 : 0;
                } else if (d != 0) {
                    mismatches[variant]++;
                    check(false, "fused frame " + f + " (" + (variant == 0 ? "no draw" : "vignette colour 0") + "): " + d + " bytes differ from the reference");
                }
                Thread.sleep(10);   // lets the asynchronous BGRA8 variants compile, and the acquirer park a drawable
            }
            System.out.printf("  W1 FUSED SCENE: %d frames, %d presented; no-draw / colour-0 frames equal to the reference: %d / %d of %d each;"
                + " control changed %d of %d%n", frames, presented, frames / 3 - mismatches[0], frames / 3 - mismatches[1], frames / 3, controlsChanged[0],
                frames / 3);
            check(presented >= 1, "fused scene: at least one frame presented");
            check(controlsChanged[0] == frames / 3, "fused scene: every control frame differs from the reference");
            surface.close();
        }
    }

    static byte[] toArray(final ByteBuffer b) {
        byte[] out = new byte[b.remaining()];
        b.duplicate().get(out);
        return out;
    }

    static ByteBuffer direct(final int size) {
        return ByteBuffer.allocateDirect(size).order(ByteOrder.LITTLE_ENDIAN);
    }

    /** Minimal Objective-C runtime access through FFM (QuartzCore is already loaded by libmcmetal); see MetalE2ETest. */
    static final class ObjC {
        private static final Linker LINKER = Linker.nativeLinker();
        private static final SymbolLookup LIBOBJC = SymbolLookup.libraryLookup("/usr/lib/libobjc.A.dylib", Arena.global());
        private static final MethodHandle GET_CLASS = LINKER.downcallHandle(LIBOBJC.find("objc_getClass").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        private static final MethodHandle SEL = LINKER.downcallHandle(LIBOBJC.find("sel_registerName").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        private static final MethodHandle SEND = LINKER.downcallHandle(LIBOBJC.find("objc_msgSend").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

        static long newMetalLayer() throws Throwable {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment cls = (MemorySegment)GET_CLASS.invokeExact(arena.allocateFrom("CAMetalLayer"));
                MemorySegment sel = (MemorySegment)SEL.invokeExact(arena.allocateFrom("new"));
                if (cls.address() == 0L) {
                    throw new IllegalStateException("CAMetalLayer class not found");
                }
                return ((MemorySegment)SEND.invokeExact(cls, sel)).address();
            }
        }
    }

    static ShaderSource jarShaderSource() {
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

    static String resource(final String path) {
        try (InputStream in = HudVignetteTest.class.getClassLoader().getResourceAsStream(path)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(path, e);
        }
    }
}
