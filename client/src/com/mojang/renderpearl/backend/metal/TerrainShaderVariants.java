package com.mojang.renderpearl.backend.metal;

import com.mojang.logging.LogUtils;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Terrain shader specialization for the Metal backend (CONTRACT.md 16.8).
 *
 * <p>{@code core/terrain.fsh} picks its texture sampling with a runtime branch on the {@code Globals.UseRgss} uniform:
 * {@code UseRgss == 1 ? sampleRGSS(...) : sampleNearest(...)}. SPIRV-Cross keeps that branch in the MSL (both paths
 * take their own {@code dfdx}/{@code dfdy} and samples inside it), and Metal compiles every terrain fragment (solid,
 * cutout, translucent, OIT, wireframe) with both paths. Measured in game, that shader is far slower than either path
 * alone, whatever the option's value (static scorecard 575-584 -> 807-819 FPS median; CONTRACT.md 16.8).
 *
 * <p>With {@link #specialize} on, every pipeline whose fragment MSL contains that branch gets two native pipelines:
 * one whose branch condition is the literal {@code false} (the nearest path only) and one with {@code true} (RGSS
 * only); Metal's compiler drops the dead path. The live path is the same source text as before, so the arithmetic is
 * unchanged. {@link MetalRenderPipeline#handle()} (recorded by {@code MetalRenderPass.setPipeline}) returns the
 * variant matching {@link #frameUsesRgss()}, which {@code GameRenderer.render} sets in the same place, with the same
 * value, as the {@code UseRgss} it writes into the Globals UBO (once per frame, before any pass), so the selected
 * variant always computes what the uniform branch would.
 *
 * <p>Scope: only pipelines whose fragment shader is {@value #TERRAIN_FRAGMENT} (solid, cutout, translucent, the three
 * OIT terrain passes, wireframe, and their multidraw forms), and only while the terrain shader sources are the
 * built-in vanilla ones ({@link #setVanillaSourceCheck}, installed by GameRenderer: core/terrain.fsh, core/terrain.vsh
 * and every include they pull in come from the "vanilla" pack). A resource pack that overrides any of them gets the
 * generic path (its MSL unchanged, one PSO), as do shaders whose MSL does not have the vanilla shape. Every rewrite
 * checks the exact vanilla text it replaces and otherwise leaves the shader alone.
 *
 * <p>Switches (system properties, read when a pipeline compiles):
 * <ul>
 *   <li>{@code -Dmcmetal.terrainSpecialize=true|false} (default true): the specialization above.</li>
 *   <li>{@code -Dmcmetal.terrainRgssEarlyOut=true|false} (default true): {@link #applyRgssEarlyOut} (exact).</li>
 *   <li>{@code -Dmcmetal.terrainRgssNoUnroll=true|false} (default false): the RGSS sample loop stays rolled (exact;
 *       experiment, no gain in game).</li>
 *   <li>{@code -Dmcmetal.terrainRgssLevelSampler=true|false} (default false): mip-nearest sampler for the integral-LOD
 *       RGSS samples (experiment; exact only for the vanilla terrain sampler in RGSS mode; gain within noise in game).</li>
 *   <li>{@code -Dmcmetal.diag.terrainShader=flat} (diagnostics only, changes the image): solid terrain fragments output
 *       the vertex colour without sampling, to bound what fragment-shader work could still save.</li>
 *   <li>{@code -Dmcmetal.terrainVariant=<modes>} (default {@value #DEFAULT_MODES}) / {@code -Dmcmetal.terrainVariant.use}
 *       (default true): round 11's perceptually identical (not bit-exact) terrain variant, a third and fourth PSO per
 *       non-OIT terrain pipeline (nearest and RGSS) picked by {@link MetalRenderPipeline#handle()}: fog value computed per
 *       vertex, one fog/visibility varying instead of three, half-precision colour varyings ({@link #pairedVariant};
 *       CONTRACT.md 16.R11). {@code use=false} gives the exact PSOs above.</li>
 * </ul>
 */
public final class TerrainShaderVariants {
    private static final Logger LOGGER = LogUtils.getLogger();
    /**
     * Specialize terrain fragment shaders on UseRgss (two PSOs per terrain pipeline, picked per frame). Read when a
     * pipeline compiles (tests flip it between devices).
     */
    public static volatile boolean specialize = flag("mcmetal.terrainSpecialize", true);
    /** Skip the RGSS samples where they cannot change the result ({@link #applyRgssEarlyOut}). Read when a pipeline compiles. */
    public static volatile boolean rgssEarlyOut = flag("mcmetal.terrainRgssEarlyOut", true);
    /**
     * Experiment: keep the 4-iteration RGSS sample loop rolled (fewer samples in flight, lower register pressure); the
     * accumulation order is unchanged. Read when a pipeline compiles.
     */
    public static volatile boolean rgssNoUnroll = flag("mcmetal.terrainRgssNoUnroll", false);
    /**
     * Experiment: the 8 RGSS {@code textureLod} samples (integral LODs) use an in-shader sampler identical to the
     * terrain atlas sampler in RGSS mode (clamp to edge, linear min/mag, anisotropy 1, LOD 0..FLT_MAX) except
     * {@code mip_filter::nearest}: at an integral LOD the trilinear blend weight is 0. Read when a pipeline compiles.
     */
    public static volatile boolean rgssLevelSampler = flag("mcmetal.terrainRgssLevelSampler", false);
    public static final AtomicInteger LEVEL_SAMPLER_APPLIED = new AtomicInteger();
    /** Fragment shaders rewritten so far, per rewrite (tests check that the rewrites matched the vanilla MSL). */
    public static final AtomicInteger SPECIALIZED = new AtomicInteger();
    /** Terrain fragment shaders left on the generic path because the sources are not vanilla's (resource pack). */
    public static final AtomicInteger OVERRIDDEN = new AtomicInteger();
    public static final AtomicInteger EARLY_OUT_APPLIED = new AtomicInteger();
    public static final AtomicInteger NO_UNROLL_APPLIED = new AtomicInteger();
    private static final String DIAG = System.getProperty("mcmetal.diag.terrainShader", "");
    static final boolean DIAG_FLAT = hasMode("flat");

    /** {@code if (_NNN.UseRgss == 1)}: the runtime filtering branch of core/terrain.fsh in SPIRV-Cross output. */
    private static final Pattern RGSS_BRANCH = Pattern.compile("if \\((_\\d+)\\.UseRgss == 1\\)");
    /** The entry point's Globals UBO parameter ({@code constant Globals& _NNN [[buffer(n)]]}). */
    private static final Pattern GLOBALS_PARAM = Pattern.compile("constant Globals& (_\\d+) \\[\\[buffer\\(\\d+\\)\\]\\]");
    /** CreateInfo.Shader name of the fragment shader of every vanilla terrain pipeline. */
    public static final String TERRAIN_FRAGMENT = "minecraft:core/terrain";
    /**
     * True iff the terrain shader sources are vanilla's; evaluated when a terrain pipeline compiles (inside the resource
     * reload that compiles it, so it sees that reload's packs). Default (headless tests): the jar's own sources.
     */
    private static volatile BooleanSupplier vanillaSources = () -> true;

    /** Bounds-based experimental LOD culling must not assume pack shaders preserve positions. */
    public static boolean hasBuiltinTerrainSources() {
        try {
            return vanillaSources.getAsBoolean();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Render thread only: the installed cache's reuse composite stages and all their includes must equal the bundled
     * sources. Cached per pipeline cache (resource reload); false for missing sources or an unavailable check.
     */
    public static boolean hasBuiltinReuseSources() {
        try {
            return BuiltinReuseSources.check();
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /**
     * The value GameRenderer last wrote into Globals.UseRgss (render thread). Volatile only so a test or debugger on
     * another thread sees it; the render thread is the only writer and reader in the game.
     */
    private static volatile boolean frameUsesRgss;
    private static volatile boolean frameAnisotropic;
    /** Render thread only: what {@link #setFrameUsesRgss} last logged. */
    private static boolean logged;
    private static boolean loggedRgss;

    private TerrainShaderVariants() {
    }

    /** Called by GameRenderer next to {@code GlobalSettingsUniform.update(..., useRgss)} with the same value. */
    public static void setFrameUsesRgss(final boolean useRgss) {
        // Log the variant in use once specialized pipelines exist, and whenever it changes (render thread only).
        int specialized = SPECIALIZED.get();
        if (specialized > 0 && (useRgss != loggedRgss || !logged)) {
            LOGGER.info("Terrain shaders: {} variants in use ({} fragment shaders specialized so far)", useRgss ? "RGSS" : "nearest", specialized);
            logged = true;
            loggedRgss = useRgss;
        }

        frameUsesRgss = useRgss;
    }

    /** GameRenderer, next to {@link #setFrameUsesRgss}: the terrain sampler of this frame is anisotropic (ANISOTROPIC option). */
    public static void setFrameAnisotropic(final boolean anisotropic) {
        frameAnisotropic = anisotropic;
    }

    /** The nearest-path variant may be used in this frame: switched on and the terrain sampler has anisotropy 1. */
    public static boolean frameUsesVariant() {
        return useVariant && !frameAnisotropic;
    }

    public static boolean frameUsesRgss() {
        return frameUsesRgss;
    }

    /** GameRenderer: how to tell whether the terrain shader sources are the built-in vanilla ones (see class doc). */
    public static void setVanillaSourceCheck(final BooleanSupplier check) {
        vanillaSources = check;
    }

    /**
     * The fragment MSL of a pipeline whose fragment shader is {@code fragmentShader}: {@code {msl}} unchanged (generic
     * path), {@code {rewritten}} (exact rewrites, no specialization) or {@code {nearestOnly, rgssOnly}} (one PSO each,
     * {@link MetalRenderPipeline#handle()} picks per frame).
     */
    public static String[] fragmentVariants(final String fragmentShader, final String msl) {
        if (!TERRAIN_FRAGMENT.equals(fragmentShader) || !specialize && !rgssEarlyOut && !rgssNoUnroll && !rgssLevelSampler) {
            return new String[]{msl};
        }

        boolean vanilla;
        try {
            vanilla = vanillaSources.getAsBoolean();
        } catch (RuntimeException e) {
            LOGGER.warn("Terrain shaders: couldn't check the shader sources, generic path", e);
            vanilla = false;
        }

        if (!vanilla) {
            if (OVERRIDDEN.getAndIncrement() == 0) {
                LOGGER.info("Terrain shaders: overridden by a resource pack, Metal uses them unchanged (no specialization)");
            }

            return new String[]{msl};
        }

        String rewritten = fragment(msl);
        String[] rgss = specialize ? specializeRgss(rewritten) : null;
        String[] out = rgss != null ? rgss : new String[]{rewritten};
        dump(msl, out);
        return out;
    }

    /**
     * Returns {@code {nearestOnly, rgssOnly}} MSL for a fragment shader with exactly one UseRgss branch, else null
     * (the shader is used unchanged).
     */
    static String @Nullable [] specializeRgss(final String msl) {
        Matcher m = RGSS_BRANCH.matcher(msl);
        if (!m.find()) {
            return null;
        }

        int start = m.start();
        int end = m.end();
        String ubo = m.group(1);
        if (m.find()) {
            return null; // not the vanilla shape: leave it alone
        }

        // The condition must read the Globals UBO (the value GameRenderer reports), not some other UseRgss field.
        Matcher g = GLOBALS_PARAM.matcher(msl);
        if (!g.find() || !g.group(1).equals(ubo) || g.find()) {
            return null;
        }

        String head = msl.substring(0, start);
        String tail = msl.substring(end);
        SPECIALIZED.incrementAndGet();
        return new String[]{head + "if (false)" + tail, head + "if (true)" + tail};
    }

    /** The terrain fragment rewrites that keep the output exact ({@link #rgssEarlyOut}), before specialization. */
    static String fragment(final String msl) {
        String out = msl;
        if (rgssEarlyOut) {
            String early = applyRgssEarlyOut(out);
            if (early != null) {
                out = early;
                EARLY_OUT_APPLIED.incrementAndGet();
            }
        }

        if (rgssNoUnroll) {
            String loop = "    for (int i = 0; i < 4; i++)\n    {\n        float2 sampleUV = uv + (";
            int at = out.indexOf(loop);
            if (at >= 0 && out.indexOf(loop, at + 1) < 0) {
                out = out.substring(0, at) + "    #pragma clang loop unroll(disable)\n" + out.substring(at);
                NO_UNROLL_APPLIED.incrementAndGet();
            }
        }

        if (rgssLevelSampler) {
            String low = "rgssColorLow += source.sample(sourceSmplr, sampleUV, level(mipLevelLow));";
            String high = "rgssColorHigh += source.sample(sourceSmplr, sampleUV, level(mipLevelHigh));";
            String fn = "static inline __attribute__((always_inline))\nfloat4 sampleRGSS(";
            int at = out.indexOf(fn);
            if (at >= 0 && out.indexOf(low) > at && out.indexOf(high) > at && out.indexOf(low, out.indexOf(low) + 1) < 0) {
                out = out.substring(0, at)
                    + "constexpr sampler mcmtlRgssLevelSmplr(coord::normalized, address::clamp_to_edge, filter::linear, mip_filter::nearest);\n\n"
                    + out.substring(at);
                out = out.replace(low, low.replace("sourceSmplr", "mcmtlRgssLevelSmplr")).replace(high, high.replace("sourceSmplr", "mcmtlRgssLevelSmplr"));
                LEVEL_SAMPLER_APPLIED.incrementAndGet();
            }
        }

        return out;
    }

    /**
     * RGSS early-out: in {@code sampleRGSS}, the result is {@code mix(nearestColor, rgssColor, blendFactor)}, and
     * {@code blendFactor = smoothstep(minPixelSize, 2 * minPixelSize, maxTexelSize)} is exactly 0 wherever a texel
     * covers at least one pixel (magnified terrain, i.e. most of the screen). There {@code mix(n, r, 0) == n} exactly
     * for any finite {@code r} (x + (y - x) * 0, in any fused or reassociated form), so the 8 {@code textureLod}
     * samples are dead: this rewrite returns {@code nearestColor} right after {@code blendFactor} is known. The nearest
     * sample takes the same arguments (derivatives are computed before the branch, in uniform control flow; the RGSS
     * samples use explicit LODs and need none). Likewise {@code mipBlend = fract(mipLevelExact)} is exactly 0 wherever
     * the RGSS mip level clamps to 0, and there the 4 samples of the next level are skipped. Returns null if
     * {@code msl} does not have the vanilla shape.
     */
    static @Nullable String applyRgssEarlyOut(final String msl) {
        int fn = msl.indexOf("float4 sampleRGSS(texture2d<float> source, sampler sourceSmplr, thread const float2& uv, thread const float2& pixelSize)");
        if (fn < 0) {
            return null;
        }

        String blend = "    float blendFactor = smoothstep(transitionStart, transitionEnd, maxTexelSize);\n";
        int at = msl.indexOf(blend, fn);
        int fnEnd = msl.indexOf("\n}\n", fn);
        if (at < 0 || fnEnd < 0 || at > fnEnd
            || msl.indexOf("float4 nearestColor = ", at) > fnEnd
            || !msl.substring(at, fnEnd).contains("sampleNearest(source, sourceSmplr, param, param_1, param_2, param_3, param_4);")
            || !msl.substring(at, fnEnd).contains("return mix(nearestColor, rgssColor, float4(blendFactor));")
            || !msl.substring(fn, at).contains("float2 texelScreenSize = sqrt((du * du) + (dv * dv));")) {
            return null;
        }

        String high = "        rgssColorHigh += source.sample(sourceSmplr, sampleUV, level(mipLevelHigh));\n";
        int highAt = msl.indexOf(high, at);
        if (highAt < 0 || highAt > fnEnd || !msl.substring(at, fnEnd).contains("float4 rgssColor = mix(rgssColorLow, rgssColorHigh, float4(mipBlend));")) {
            return null;
        }

        int insert = at + blend.length();
        String early = "    if (blendFactor == 0.0)\n"
            + "    {\n"
            + "        float2 mcmtl_uv = uv;\n"
            + "        float2 mcmtl_pixelSize = pixelSize;\n"
            + "        float2 mcmtl_du = du;\n"
            + "        float2 mcmtl_dv = dv;\n"
            + "        float2 mcmtl_texelScreenSize = texelScreenSize;\n"
            + "        return sampleNearest(source, sourceSmplr, mcmtl_uv, mcmtl_pixelSize, mcmtl_du, mcmtl_dv, mcmtl_texelScreenSize);\n"
            + "    }\n";
        // mipBlend = fract(mipLevelExact) is exactly 0 wherever the mip level clamps to 0 (and at integral levels): then
        // mix(rgssColorLow, rgssColorHigh, 0) == rgssColorLow for any finite rgssColorHigh, so the 4 samples of the
        // next level are dead too.
        String guardedHigh = "        if (mipBlend != 0.0)\n        {\n    " + high.substring(0, high.length() - 1) + "\n        }\n";
        return msl.substring(0, insert) + early + msl.substring(insert, highAt) + guardedHigh + msl.substring(highAt + high.length());
    }

    /**
     * Runtime-switchable nearest-path variant (CONTRACT.md 16.R8): {@code -Dmcmetal.terrainVariant=<modes>} builds a third
     * PSO per specialized terrain pipeline whose name contains one of {@code -Dmcmetal.terrainVariant.pipelines} (comma
     * list; empty = all); {@link #useVariant} (abab toggle) selects it instead of the nearest PSO. Modes (comma list):
     * diagnostics {@code level0} (texture sample at level 0 instead of the gradients), {@code nofog}, {@code nodiscard},
     * {@code flat} (no texture sample); candidates {@code earlydiscard}.
     *
     * <p>Round 11 (CONTRACT.md 16.R11): the shipped default is {@value #DEFAULT_MODES} ({@link #pairedVariant}: fog value
     * per vertex, one fog/visibility varying, half-precision colour varyings), perceptually identical to vanilla and used
     * by default ({@code mcmetal.terrainVariant.use} true). Off: {@code -Dmcmetal.terrainVariant.use=false} (the PSO is still
     * built; abab toggle {@code TerrainShaderVariants#useVariant}) or {@code -Dmcmetal.terrainVariant=} (not built). A
     * diagnostic mode list given with {@code -Dmcmetal.terrainVariant} replaces the default.
     */
    public static final String DEFAULT_MODES = "vfog,hvary";
    public static volatile boolean useVariant = flag("mcmetal.terrainVariant.use", true);
    public static volatile String variantModes = System.getProperty("mcmetal.terrainVariant", DEFAULT_MODES);
    public static volatile String variantPipelines = System.getProperty("mcmetal.terrainVariant.pipelines", "");
    public static final AtomicInteger VARIANTS_BUILT = new AtomicInteger();

    /** The variant MSL of {@code nearestMsl} for pipeline {@code name}, or null (no variant configured / applicable). */
    static @Nullable String nearestVariant(final String name, final String nearestMsl) {
        if (variantModes.isEmpty()) {
            return null;
        }
        if (!variantPipelines.isEmpty()) {
            boolean match = false;
            for (String p : variantPipelines.split(",")) {
                if (!p.isBlank() && name.contains(p.trim())) {
                    match = true;
                }
            }
            if (!match) {
                return null;
            }
        }
        String fragmentModes = fragmentModes(variantModes);
        if (fragmentModes.isEmpty()) {
            return null;
        }
        String out = applyVariant(fragmentModes, nearestMsl);
        if (out == null || out.equals(nearestMsl)) {
            LOGGER.warn("Terrain shader variant {} not applicable to {}", variantModes, name);
            return null;
        }
        VARIANTS_BUILT.incrementAndGet();
        LOGGER.info("Terrain shader variant {} built for {}", variantModes, name);
        return out;
    }

    /** Metal buffer index of the Fog UBO (CONTRACT.md 6.1). */
    public static final int FOG_SLOT = 2;
    public static final AtomicInteger VARIANT_FAILURES = new AtomicInteger();
    /** Modes that rewrite the vertex and the fragment stage together (their interface changes): round 11. */
    private static final java.util.Set<String> PAIRED = java.util.Set.of("vfog", "hvary");

    private static boolean hasVariantMode(final String mode) {
        for (String m : variantModes.split(",")) {
            if (m.trim().equals(mode)) {
                return true;
            }
        }
        return false;
    }

    /** The variant of pipeline {@code name} reads the Fog UBO in its vertex stage (prepare binds it there). */
    public static boolean vertexNeedsFog(final String name) {
        return !variantModes.isEmpty() && hasVariantMode("vfog") && pipelineMatches(name);
    }

    /**
     * The runtime-switchable variant PSO's {@code {vertexMsl, fragmentMsl}} for pipeline {@code name} (nearest path), or
     * null: fragment-only modes ({@link #nearestVariant}), vertex-only {@code vs} modes ({@link #vertexVariant}) and the
     * paired modes of round 11 ({@link #pairedVariant}); if any configured mode does not apply, no variant at all.
     */
    public static String @Nullable [] variant(final String name, final String vertexMsl, final String nearestMsl) {
        if (variantModes.isEmpty() || !pipelineMatches(name)) {
            return null;
        }
        String f = nearestVariant(name, nearestMsl);
        String v = vertexVariant(name, vertexMsl);
        boolean paired = false;
        for (String m : variantModes.split(",")) {
            paired |= PAIRED.contains(m.trim());
        }
        if (!paired) {
            return f == null && v == null ? null : new String[]{v != null ? v : vertexMsl, f != null ? f : nearestMsl};
        }
        boolean otherModes = !fragmentModes(variantModes).isEmpty();
        if (otherModes && f == null) {
            return null;
        }
        String[] p = pairedVariant(v != null ? v : vertexMsl, f != null ? f : nearestMsl);
        if (p == null) {
            LOGGER.warn("Terrain shader variant {} not applicable to {}", variantModes, name);
            return null;
        }
        VARIANTS_BUILT.incrementAndGet();
        LOGGER.info("Terrain shader variant {} built for {}", variantModes, name);
        return p;
    }

    /**
     * The RGSS-path counterpart of the variant, {@code {vertexMsl, fragmentMsl}}, when the configured modes are paired
     * modes only (they leave texture sampling alone, so the same rewrite is valid for the RGSS fragment); else null.
     */
    public static String @Nullable [] rgssVariant(final String vertexMsl, final String rgssMsl) {
        if (variantModes.isEmpty()) {
            return null;
        }
        for (String m : variantModes.split(",")) {
            if (!m.isBlank() && !PAIRED.contains(m.trim())) {
                return null;
            }
        }
        String[] p = pairedVariant(vertexMsl, rgssMsl);
        if (p != null) {
            RGSS_VARIANTS_BUILT.incrementAndGet();
        }
        return p;
    }

    public static final AtomicInteger RGSS_VARIANTS_BUILT = new AtomicInteger();

    private static final Pattern VS_SIG =Pattern.compile("(vertex main0_out main0\\([^\\n]*)\\)\\n\\{\\n");
    private static final Pattern VS_VIS = Pattern.compile("    out\\.chunkVisibility = (mix\\(1\\.0, [^;\\n]*\\));\\n");
    private static final Pattern FS_COLOR = Pattern.compile(
        "    float4 color = ([A-Za-z_][A-Za-z0-9_]*) \\* in\\.vertexColor;\\n    color = mix\\((_\\d+)\\.FogColor \\* float4\\(1\\.0, 1\\.0, 1\\.0, color\\.w\\), color, float4\\(in\\.chunkVisibility\\)\\);\\n");
    private static final Pattern FS_FINAL = Pattern.compile(
        "    float4 (param_\\d+) = color;\\n    out\\.fragColor = calculateFinalColor\\(\\1, (_\\d+), in\\.sphericalVertexDistance, in\\.cylindricalVertexDistance\\);\\n");
    private static final String IO_DIST = "    float sphericalVertexDistance [[user(locn0)]];\n    float cylindricalVertexDistance [[user(locn1)]];\n";
    private static final String IO_VIS = "    float chunkVisibility [[user(locn4)]];\n";
    private static final String IO_COLOR = "    float4 vertexColor [[user(locn2)]];\n";

    private static int count(final String s, final String part) {
        int n = 0;
        for (int at = s.indexOf(part); at >= 0; at = s.indexOf(part, at + 1)) {
            n++;
        }
        return n;
    }

    /**
     * Round 11 (CONTRACT.md 16.R11) paired modes on {@code {vertex, fragment}} MSL; null if the shaders do not have the
     * vanilla shape (OIT, other packs).
     * <ul>
     *   <li>{@code vfog}: the vertex stage computes vanilla's fog value F (both linear ramps and their max, same branches)
     *       from its own two distances, and the fragment receives one interpolated {@code float2}: {@code g = v (1 - F Fa)}
     *       and {@code h = mix(Fa, 1, v)} (v: chunk visibility, Fa: FogColor.a). Vanilla's chunk-fade mix followed by its
     *       fog mix is {@code rgb = mix(Fc, c, g)}, {@code a = c.a h} algebraically, so the fragment does one mix. The only
     *       differences: the fog ramp's kinks (start / end, and where the two ramps cross) are interpolated across a
     *       triangle instead of per pixel, and v (1 - F) is interpolated as a product (v is 1 outside fading sections).</li>
     *   <li>{@code hvary} (needs vfog): vertexColor and the fog/visibility pair as half-precision varyings.</li>
     * </ul>
     */
    static String @Nullable [] pairedVariant(final String vertexMsl, final String fragmentMsl) {
        String vs = vertexMsl;
        String fs = fragmentMsl;
        boolean vfog = hasVariantMode("vfog");
        boolean hvary = hasVariantMode("hvary");
        if (!vfog) {
            return null;   // hvary alone is not built
        }
        String sph = "    out.sphericalVertexDistance = fog_spherical_distance(param);\n";
        String cyl = "    out.cylindricalVertexDistance = fog_cylindrical_distance(param_1);\n";
        String flip = "    out.gl_Position.y = -(out.gl_Position.y);";
        Matcher sig = VS_SIG.matcher(vs);
        Matcher vis = VS_VIS.matcher(vs);
        Matcher color = FS_COLOR.matcher(fs);
        Matcher fin = FS_FINAL.matcher(fs);
        if (count(vs, IO_DIST) != 1 || count(vs, IO_VIS) != 1 || count(fs, IO_DIST) != 1 || count(fs, IO_VIS) != 1 || count(vs, sph) != 1 || count(vs, cyl) != 1
            || count(vs, flip) != 1 || !vs.contains("struct Fog\n{\n    float4 FogColor;\n    float FogEnvironmentalStart;\n    float FogEnvironmentalEnd;\n    float FogRenderDistanceStart;\n    float FogRenderDistanceEnd;\n")
            || !sig.find() || !vis.find() || !color.find() || !fin.find() || vs.contains("mcFog") || count(fs, "in.chunkVisibility") != 1
            || count(fs, "in.sphericalVertexDistance") != 1 || count(fs, "in.cylindricalVertexDistance") != 1) {
            return null;
        }
        String vty = hvary ? "half2" : "float2";
        String io = "    " + vty + " mcmtlFogVis [[user(locn0)]];\n";
        // vertex
        String helper = "static inline __attribute__((always_inline))\n"
            + "float mcmtl_linear_fog(float d, float s, float e)\n"
            + "{\n"
            + "    if (d <= s)\n    {\n        return 0.0;\n    }\n"
            + "    else\n    {\n        if (d >= e)\n        {\n            return 1.0;\n        }\n    }\n"
            + "    return (d - s) / (e - s);\n"
            + "}\n\n";
        vs = vs.substring(0, sig.start()) + helper + sig.group(1) + ", constant Fog& mcFog [[buffer(" + FOG_SLOT + ")]])\n{\n" + vs.substring(sig.end());
        // Stage 3 LOD fog (CONTRACT 16.S3-LOD-FOG, fog.glsl fog_lod_distance): only when the Fog block carries the LOD fields.
        String lodFog = vs.contains("    float FogCloudsEnd;\n    float FogLodStart;\n    float FogLodCompress;\n};")
            ? "    mcmtl_sph -= fast::max(mcmtl_sph - mcFog.FogLodStart, 0.0) * mcFog.FogLodCompress;\n" : "";
        vs = vs.replace(sph, "    float mcmtl_sph = fog_spherical_distance(param);\n" + lodFog).replace(cyl, "    float mcmtl_cyl = fog_cylindrical_distance(param_1);\n");
        vis = VS_VIS.matcher(vs);
        if (!vis.find()) {
            return null;
        }
        vs = vs.substring(0, vis.start()) + "    float mcmtl_vis = " + vis.group(1) + ";\n" + vs.substring(vis.end());
        vs = vs.replace(flip, "    float mcmtl_f = fast::max(mcmtl_linear_fog(mcmtl_sph, mcFog.FogEnvironmentalStart, mcFog.FogEnvironmentalEnd), "
            + "mcmtl_linear_fog(mcmtl_cyl, mcFog.FogRenderDistanceStart, mcFog.FogRenderDistanceEnd)) * mcFog.FogColor.w;\n"
            + "    out.mcmtlFogVis = " + vty + "(float2(mcmtl_vis * (1.0 - mcmtl_f), mix(mcFog.FogColor.w, 1.0, mcmtl_vis)));\n"
            + flip);
        vs = vs.replace(IO_DIST, io).replace(IO_VIS, "");
        // fragment
        String fv = hvary ? "float2(in.mcmtlFogVis)" : "in.mcmtlFogVis";
        String fc = hvary ? "float4(in.vertexColor)" : "in.vertexColor";
        fs = fs.substring(0, color.start()) + "    float4 color = " + color.group(1) + " * " + fc + ";\n    float2 mcmtl_fv = " + fv + ";\n    color.w *= mcmtl_fv.y;\n"
            + fs.substring(color.end());
        fin = FS_FINAL.matcher(fs);
        if (!fin.find()) {
            return null;
        }
        fs = fs.substring(0, fin.start()) + "    out.fragColor = float4(mix(" + fin.group(2) + ".FogColor.xyz, color.xyz, float3(mcmtl_fv.x)), color.w);\n" + fs.substring(fin.end());
        fs = fs.replace(IO_DIST, io).replace(IO_VIS, "");
        if (hvary) {
            String vc = "    out.vertexColor = ";
            int at = vs.indexOf(vc);
            int eol = at < 0 ? -1 : vs.indexOf(";\n", at);
            if (count(vs, IO_COLOR) != 1 || count(fs, IO_COLOR) != 1 || count(vs, vc) != 1 || eol < 0) {
                return null;
            }
            vs = vs.substring(0, at) + vc + "half4(" + vs.substring(at + vc.length(), eol) + ")" + vs.substring(eol);
            vs = vs.replace(IO_COLOR, "    half4 vertexColor [[user(locn2)]];\n");
            fs = fs.replace(IO_COLOR, "    half4 vertexColor [[user(locn2)]];\n");
        }
        return new String[]{vs, fs};
    }

    private static String fragmentModes(final String modes) {
        StringBuilder b = new StringBuilder();
        for (String m : modes.split(",")) {
            if (!m.isBlank() && !m.trim().startsWith("vs") && !PAIRED.contains(m.trim())) {
                b.append(b.length() == 0 ? "" : ",").append(m.trim());
            }
        }
        return b.toString();
    }

    private static boolean pipelineMatches(final String name) {
        if (name.contains("lod_occlusion")) {
            return false;   // stage 3 r3: the LOD occlusion box pipeline is hand-written in both stages (no terrain variants)
        }
        if (variantPipelines.isEmpty()) {
            return true;
        }
        for (String p : variantPipelines.split(",")) {
            if (!p.isBlank() && name.contains(p.trim())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Round 9: vertex-stage variant modes (prefix {@code vs}) of the same runtime-switchable variant PSO. Diagnostics
     * {@code vsnolight} (no lightmap sample: vertex colour = Color), {@code vsconstfog} (fog distances 0); returns null if
     * no vs mode is configured or one does not match.
     */
    static @Nullable String vertexVariant(final String name, final String vertexMsl) {
        if (variantModes.isEmpty() || !pipelineMatches(name)) {
            return null;
        }
        String out = vertexMsl;
        boolean any = false;
        for (String raw : variantModes.split(",")) {
            String mode = raw.trim();
            if (!mode.startsWith("vs")) {
                continue;
            }
            any = true;
            String before = out;
            switch (mode) {
                case "vsnolight" -> out = out.replace("out.vertexColor = in.Color * sample_lightmap(Sampler2, Sampler2Smplr, param_2);",
                    "out.vertexColor = in.Color;");
                case "vsconstfog" -> out = out.replace("out.sphericalVertexDistance = fog_spherical_distance(param);", "out.sphericalVertexDistance = 0.0;")
                    .replace("out.cylindricalVertexDistance = fog_cylindrical_distance(param_1);", "out.cylindricalVertexDistance = 0.0;");
                default -> {
                    return null;
                }
            }
            if (out.equals(before)) {
                LOGGER.warn("Terrain vertex variant {} not applicable to {}", mode, name);
                return null;
            }
        }
        if (any) {
            LOGGER.info("Terrain vertex variant {} built for {}", variantModes, name);
        }
        return any ? out : null;
    }

    /** Applies the comma-separated {@code modes} to a nearest-path terrain fragment; null if a mode does not match. */
    public static @Nullable String applyVariant(final String modes, final String msl) {
        String out = msl;
        for (String raw : modes.split(",")) {
            String mode = raw.trim();
            String before = out;
            switch (mode) {
                case "maglevel0" -> out = out.replace("    return source.sample(sourceSmplr, uv, gradient2d(du, dv));\n",
                    "    float2 mcmtl_a = du / pixelSize;\n"
                        + "    float2 mcmtl_b = dv / pixelSize;\n"
                        + "    if (fast::max(dot(mcmtl_a, mcmtl_a), dot(mcmtl_b, mcmtl_b)) <= 0.25)\n"
                        + "    {\n"
                        + "        return source.sample(sourceSmplr, uv, level(0.0));\n"
                        + "    }\n"
                        + "    return source.sample(sourceSmplr, uv, gradient2d(du, dv));\n");
                case "level0" -> out = out.replace("return source.sample(sourceSmplr, uv, gradient2d(du, dv));",
                    "return source.sample(sourceSmplr, uv, level(0.0));");
                case "nofog" -> out = out.replaceAll("out\\.fragColor = calculateFinalColor\\((\\w+), [^;]*\\);", "out.fragColor = $1;");
                case "nodiscard" -> out = out.replace("discard_fragment();", "");
                // round 9 budget (diagnostics): trivial fragment stage, no varying read, no sample, no discard
                case "const" -> out = !out.contains("float4 fragColor [[color(0)]];") ? out : out.replaceFirst("(fragment main0_out main0\\([^\\n]*\\n\\{\\n    main0_out out = \\{\\};\\n)",
                    "$1    out.fragColor = float4(0.5);\n    return out;\n");
                case "flat" -> out = out.replaceAll("(_\\d+) = sampleNearest\\(Sampler0, Sampler0Smplr, \\w+, \\w+\\);", "$1 = float4(0.5);");
                case "earlydiscard" -> {
                    Matcher t = Pattern.compile("    if \\(color\\.w < ([0-9.]+)\\)\n    \\{\n        discard_fragment\\(\\);\n    \\}\n").matcher(out);
                    String mul = "    float4 color = ";
                    int at = out.indexOf(mul);
                    int eol = at < 0 ? -1 : out.indexOf(";\n", at);
                    if (!t.find() || eol < 0 || !out.substring(at, eol).endsWith(" * in.vertexColor")) {
                        return null;
                    }
                    String test = t.group();
                    out = out.substring(0, t.start()) + out.substring(t.end());
                    out = out.substring(0, eol + 2) + test + out.substring(eol + 2);
                }
                default -> {
                    return null;
                }
            }
            if (out.equals(before)) {
                return null;
            }
        }
        return out;
    }

    /** Diagnostics: {@code -Dmcmetal.dumpTerrainMsl=<dir>} writes the translator's MSL and every variant (off by default). */
    private static void dump(final String msl, final String[] variants) {
        String dir = System.getProperty("mcmetal.dumpTerrainMsl");
        if (dir == null || dir.isEmpty()) {
            return;
        }
        try {
            java.nio.file.Path d = java.nio.file.Path.of(dir);
            java.nio.file.Files.createDirectories(d);
            String id = Integer.toHexString(msl.hashCode());
            java.nio.file.Files.writeString(d.resolve("terrain-" + id + "-in.metal"), msl);
            for (int i = 0; i < variants.length; i++) {
                java.nio.file.Files.writeString(d.resolve("terrain-" + id + "-v" + i + ".metal"), variants[i]);
            }
        } catch (java.io.IOException e) {
            LOGGER.warn("Terrain shaders: dump failed", e);
        }
    }

    /** Diagnostics: {@code -Dmcmetal.dumpTerrainMsl=<dir>} also writes each terrain pipeline's vertex MSL (and its variant). */
    static void dumpVertex(final String name, final String msl, final @Nullable String variant) {
        String dir = System.getProperty("mcmetal.dumpTerrainMsl");
        if (dir == null || dir.isEmpty()) {
            return;
        }
        try {
            java.nio.file.Path d = java.nio.file.Path.of(dir);
            java.nio.file.Files.createDirectories(d);
            String id = name.replaceAll("[^A-Za-z0-9_.-]", "_");
            java.nio.file.Files.writeString(d.resolve("vertex-" + id + ".metal"), msl);
            if (variant != null) {
                java.nio.file.Files.writeString(d.resolve("vertex-" + id + "-variant.metal"), variant);
            }
        } catch (java.io.IOException e) {
            LOGGER.warn("Terrain shaders: dump failed", e);
        }
    }

    /** Diagnostics: applies the -Dmcmetal.diag.terrainShader rewrites (identity when none applies). */
    static String diagFragment(final String pipelineName, final String msl) {
        if (DIAG_FLAT && pipelineName.contains("solid_terrain")) {
            Matcher m = RGSS_BRANCH.matcher(msl);
            int ifAt = m.find() ? m.start() : -1;
            int elseAt = ifAt < 0 ? -1 : msl.indexOf("\n    else\n    {\n", ifAt);
            int closeAt = elseAt < 0 ? -1 : msl.indexOf("\n    }\n", elseAt + 1);
            int declAt = ifAt < 0 ? -1 : msl.lastIndexOf("    float4 ", ifAt);
            if (closeAt > 0 && declAt > 0) {
                String decl = msl.substring(declAt + 4, msl.indexOf(" = ", declAt));
                String var = decl.substring("float4 ".length());
                return msl.substring(0, ifAt) + var + " = float4(1.0);" + msl.substring(closeAt + "\n    }".length());
            }
        }

        return msl;
    }

    private static boolean hasMode(final String mode) {
        for (String part : DIAG.split(",")) {
            if (part.trim().equals(mode)) {
                return true;
            }
        }

        return false;
    }

    private static boolean flag(final String name, final boolean defaultValue) {
        String value = System.getProperty(name);
        return value == null ? defaultValue : !value.equalsIgnoreCase("false") && !value.equals("0");
    }
}
