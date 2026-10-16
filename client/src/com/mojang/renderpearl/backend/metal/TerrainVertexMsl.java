package com.mojang.renderpearl.backend.metal;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Round 12 (c) (CONTRACT.md 16.R12-C): hand-written Metal vertex functions for the multidraw terrain pipelines, replacing the
 * SPIRV-Cross translation of {@code core/terrain.vsh} ({@code MULTIDRAW_TERRAIN}, 28-byte BLOCK vertex) with byte-identical
 * output, and the vertex-pulling form the cluster path (round 12 (a), {@code TerrainClusters}) draws with.
 *
 * <p><b>Pull form ({@link #pullVertex}).</b> No {@code [[stage_in]]}: the function reads the vertex itself from the vertex
 * buffer of slot 0 ({@code [[buffer(30)]]}, CONTRACT 6.1) at {@code 28 * vertex_id} (Metal's {@code vertex_id} includes the
 * draw's base vertex, as the fixed-function fetch's index does) and the section info from slot 1 ({@code [[buffer(29)]]}) at
 * {@code 16 * instance_id} (includes the base instance): Position as three raw floats, Color with
 * {@code unpack_unorm4x8_to_float} (Metal's normalized-integer conversion, the same rule the vertex fetch applies to
 * {@code UChar4Normalized}), UV0 as two raw floats, UV2 as two sign-extended shorts, ChunkPosition as three ints and
 * ChunkVisibility as a raw float. After the fetch the body is the translator's text line for line except the clip position
 * (see {@link #body(String, String, String, boolean)}), so the outputs are the same floats; {@code tools/TerrainShaderTest.java}
 * checks colour and depth byte for byte on the multidraw pipelines (base vertex / base instance offsets, every colour byte
 * value, both quad index patterns). Because the body lines are the translator's, the round-11 paired rewrite
 * ({@link TerrainShaderVariants#pairedVariant}) applies to it unchanged, so the shipped vfog,hvary PSOs get a hand-written twin
 * (also byte-identical to the translated variant).
 *
 * <p><b>Cluster form ({@link #clusterVertex}).</b> The same body; the fetch first maps the draw's vertex id to a quad of the
 * per-frame compacted cluster list (round 12 (a)): {@code o = vid >> 2}, {@code e = quads[o]} = cluster slot {@code << 6 |}
 * local quad, {@code item = items[slot]} = (first vertex of the cluster in the heap, section info index), vertex
 * {@code item.x + 4 * local + (vid & 3)}, section {@code item.y}. Drawn with a 32-bit quad index buffer, so the triangles of
 * output quad o are vanilla's {@code 4o + {0 1 2 2 3 0}} with the same vertex order.
 *
 * <p>Built only for pipelines whose translated vertex MSL has exactly the vanilla multidraw shape ({@link #matchesVanilla}),
 * never under a resource pack that overrides the terrain shaders (the caller only asks for specialized pipelines).
 */
public final class TerrainVertexMsl {
    /** {@code -Dmcmetal.terrainHandVertex=true}: build the hand-written PSOs (at pipeline compile; off = not built). */
    public static volatile boolean build = flag("mcmetal.terrainHandVertex", false);
    /** abab-switchable selection of the hand-written PSOs (read per SET_PIPELINE through {@link MetalRenderPipeline#handle()}). */
    public static volatile boolean useHand = flag("mcmetal.terrainHandVertex.use", true);
    public static final AtomicInteger BUILT = new AtomicInteger();
    /** Round 12 (a): RenderPearl vertex buffer slots of the cluster draw's quad list and cluster items (Metal buffers 28, 27). */
    public static final int CLUSTER_QUADS_SLOT = 2;
    public static final int CLUSTER_ITEMS_SLOT = 3;
    public static final AtomicInteger FAILURES = new AtomicInteger();

    /** Signature of the translated multidraw terrain vertex function (binding names are SPIRV-Cross ids). */
    private static final Pattern SIG = Pattern.compile(
        "vertex main0_out main0\\(main0_in in \\[\\[stage_in\\]\\], constant Projection& (_\\d+) \\[\\[buffer\\(0\\)\\]\\], constant Globals& (_\\d+) \\[\\[buffer\\(1\\)\\]\\], "
            + "constant TerrainUniform& (_\\d+) \\[\\[buffer\\((\\d+)\\)\\]\\], texture2d<float> Sampler2 \\[\\[texture\\(2\\)\\]\\]"
            // Stage 4 (Tessera waving): the material mask the vertex stage reads (group 5) and the vertex id it uses
            + "(?:, texture2d<float> TesseraMaterial \\[\\[texture\\((\\d+)\\)\\]\\])?, sampler Sampler2Smplr \\[\\[sampler\\(2\\)\\]\\]"
            + "(?:, sampler TesseraMaterialSmplr \\[\\[sampler\\(\\d+\\)\\]\\])?(?:, uint gl_VertexIndex \\[\\[vertex_id\\]\\])?\\)\n");

    private TerrainVertexMsl() {
    }

    /** File-scope: no reassociation anywhere in the hand-written file (fma contraction stays as the compiler decides). */
    static final String PRAGMA = "#pragma clang fp reassociate(off)\n";

    static final String HEAD = "#pragma clang diagnostic ignored \"-Wmissing-prototypes\"\n"
        + "\n"
        + "#include <metal_stdlib>\n"
        + "#include <simd/simd.h>\n"
        + "\n"
        + "using namespace metal;\n"
        + "\n"
        + "struct Globals\n{\n    packed_int3 CameraBlockPos;\n    float GlintAlpha;\n    packed_float3 CameraOffset;\n    float GameTime;\n"
        + "    float2 ScreenSize;\n    int MenuBlurRadius;\n    int UseRgss;\n};\n"
        + "\n"
        + "struct Projection\n{\n    float4x4 ProjMat;\n};\n"
        + "\n"
        + "struct TerrainUniform\n{\n    float4x4 ModelViewMat;\n    int2 TextureSize;\n};\n"
        + "\n"
        + "struct Fog\n{\n    float4 FogColor;\n    float FogEnvironmentalStart;\n    float FogEnvironmentalEnd;\n    float FogRenderDistanceStart;\n"
        + "    float FogRenderDistanceEnd;\n    float FogSkyEnd;\n    float FogCloudsEnd;\n    float FogLodStart;\n    float FogLodCompress;\n};\n"
        + "\n"
        + "struct main0_out\n{\n    float sphericalVertexDistance [[user(locn0)]];\n    float cylindricalVertexDistance [[user(locn1)]];\n"
        + "    float4 vertexColor [[user(locn2)]];\n    float2 texCoord0 [[user(locn3)]];\n    float chunkVisibility [[user(locn4)]];\n"
        + "    float4 gl_Position [[position]];\n};\n"
        + "\n"
        // The pulled attributes, as the fixed-function fetch would deliver them (no [[attribute]]: a plain local).
        + "struct main0_in\n{\n    float3 Position;\n    float4 Color;\n    float2 UV0;\n    int2 UV2;\n    int3 ChunkPosition;\n    float ChunkVisibility;\n};\n"
        + "\n"
        + "static inline __attribute__((always_inline))\nfloat fog_spherical_distance(thread const float3& pos)\n{\n    return length(pos);\n}\n"
        + "\n"
        + "static inline __attribute__((always_inline))\nfloat fog_cylindrical_distance(thread const float3& pos)\n{\n    float distXZ = length(pos.xz);\n"
        + "    float distY = abs(pos.y);\n    return fast::max(distXZ, distY);\n}\n"
        + "\n"
        + "static inline __attribute__((always_inline))\nfloat4 sample_lightmap(texture2d<float> lightMap, sampler lightMapSmplr, thread const int2& uv)\n{\n"
        + "    return lightMap.sample(lightMapSmplr, fast::clamp((float2(uv) / float2(256.0)) + float2(0.03125), float2(0.03125), float2(0.96875)), level(0.0));\n}\n"
        + "\n"
        // One BLOCK vertex (Position f32x3, Color u8x4 normalized, UV0 f32x2, UV2 s16x2 = 7 words) and one section info
        // (CHUNK_DATA_INSTANCED: ivec3 ChunkPosition, float ChunkVisibility = 4 words).
        + "static inline __attribute__((always_inline))\n"
        + "main0_in mcmtl_fetch(device const uint* vb, device const int4* sb, uint v, uint s)\n{\n"
        + "    device const uint* w = vb + 7u * v;\n"
        + "    main0_in in;\n"
        + "    in.Position = float3(as_type<float>(w[0]), as_type<float>(w[1]), as_type<float>(w[2]));\n"
        + "    in.Color = unpack_unorm4x8_to_float(w[3]);\n"
        + "    in.UV0 = float2(as_type<float>(w[4]), as_type<float>(w[5]));\n"
        + "    in.UV2 = int2(as_type<short2>(w[6]));\n"
        + "    int4 c = sb[s];\n"
        + "    in.ChunkPosition = c.xyz;\n"
        + "    in.ChunkVisibility = as_type<float>(c.w);\n"
        + "    return in;\n}\n"
        + "\n";

    /** The translator's body (after the fetch), with the binding names of {@link #SIG}: P, G, T. */
    static String body(final String p, final String g, final String t) {
        return body(p, g, t, false);
    }

    /**
     * {@code hand}: the hand-written form of the clip position. The translated function is compiled with fast math, so the
     * optimizer reassociates the camera-relative position and the matrix product as it likes, and which association it picks
     * depends on how the inputs are produced (attribute fetch vs device loads; measured: a pulled vertex with the translator's
     * text differs from the translated function in depth on ~50 % of the test pixels, and in 0.3 % of the colour pixels). The
     * hand-written file turns reassociation off ({@link #PRAGMA}) and spells out the association the translated function
     * compiles to: {@code pos = (Position + float3(ChunkPosition - CameraBlockPos)) + CameraOffset}, {@code m = ProjMat *
     * ModelViewMat}, {@code clip = fma(m[2], z, fma(m[1], y, fma(m[0], x, m[3])))}. Found by testing candidate forms against the
     * translated function in {@code tools/TerrainShaderTest.java}: this one is byte-identical in colour and depth on every
     * image; the others (source order, {@code P * (MV * v)}, other fma orders, with and without the pragma) differ in 3-60 %
     * of the depth pixels.
     */
    static String body(final String p, final String g, final String t, final boolean hand) {
        String clip = hand
            ? "    float4x4 mcmtl_m = " + p + ".ProjMat * " + t + ".ModelViewMat;\n"
                + "    out.gl_Position = fma(mcmtl_m[2], float4(pos.z), fma(mcmtl_m[1], float4(pos.y), fma(mcmtl_m[0], float4(pos.x), mcmtl_m[3])));\n"
            : "    out.gl_Position = (" + p + ".ProjMat * " + t + ".ModelViewMat) * float4(pos, 1.0);\n";
        return "    float3 pos = (in.Position + float3(in.ChunkPosition - int3(" + g + ".CameraBlockPos))) + float3(" + g + ".CameraOffset);\n"
            + clip
            + "    float3 param = pos;\n"
            + "    out.sphericalVertexDistance = fog_spherical_distance(param);\n"
            + "    float3 param_1 = pos;\n"
            + "    out.cylindricalVertexDistance = fog_cylindrical_distance(param_1);\n"
            + "    int2 param_2 = in.UV2;\n"
            + "    out.vertexColor = in.Color * sample_lightmap(Sampler2, Sampler2Smplr, param_2);\n"
            + "    out.texCoord0 = in.UV0;\n"
            + "    float dist = length(pos);\n"
            + "    out.chunkVisibility = mix(1.0, in.ChunkVisibility, fast::clamp((dist - 16.0) / 16.0, 0.0, 1.0));\n"
            + "    out.gl_Position.y = -(out.gl_Position.y);    // Invert Y-axis for Metal\n"
            + "    return out;\n"
            + "}\n";
    }

    /**
     * True iff {@code msl} is exactly the translator's multidraw terrain vertex function this class replaces (same structs,
     * helpers and body, binding names aside): the hand-written form is then equivalent.
     */
    static @Nullable Matcher matchesVanilla(final String msl) {
        Matcher m = SIG.matcher(msl);
        if (!m.find()) {
            return null;
        }
        String expectedBody = "{\n    main0_out out = {};\n" + body(m.group(1), m.group(2), m.group(3));
        String rest = stripTessera(msl.substring(m.end()));
        if (!rest.strip().equals(expectedBody.strip()) || !msl.contains("struct main0_in\n{\n    float3 Position [[attribute(0)]];\n    float4 Color [[attribute(1)]];\n"
            + "    float2 UV0 [[attribute(2)]];\n    int2 UV2 [[attribute(3)]];\n    int3 ChunkPosition [[attribute(4)]];\n    float ChunkVisibility [[attribute(5)]];\n};\n")) {
            return null;
        }
        return m;
    }

    /** Stage 4 (Tessera lighting): the translated terrain vertex function also passes the unlit colour and light levels. */
    static boolean tessera(final String translatedMsl) {
        return translatedMsl.contains("tesseraLm");
    }

    /** The translated body without its Tessera lighting lines (they only add outputs; compared against vanilla's body). */
    static String stripTessera(final String body) {
        StringBuilder b = new StringBuilder();
        for (String line : body.split("\n", -1)) {
            if (!line.contains("tessera")) {
                b.append(line).append('\n');
            }
        }
        return b.substring(0, b.length() - 1);
    }

    /** Tessera: the extra varyings (core/terrain.vsh locations 6 and 7). */
    static String tesseraHead(final String head) {
        return head.replace("    float4 gl_Position [[position]];\n};\n",
            "    float4 tesseraBase [[user(locn6)]];\n    float2 tesseraLm [[user(locn7)]];\n    float4 gl_Position [[position]];\n};\n");
    }

    /** Tessera: the extra outputs, after the light coordinates (param_2) are known. */
    static String tesseraBody(final String body) {
        return body.replace("    out.texCoord0 = in.UV0;\n", "    out.tesseraBase = in.Color;\n"
            + "    out.tesseraLm = fast::clamp(float2(param_2 & int2(255)) / float2(240.0), float2(0.0), float2(1.0));\n    out.texCoord0 = in.UV0;\n");
    }

    /** Tessera waving: the material mask parameter of the hand-written signature ("" when the translated function has none). */
    static String waveParam(final Matcher m) {
        return m.group(5) == null ? "" : ", texture2d<float> TesseraMaterial [[texture(" + m.group(5) + ")]]";
    }

    /**
     * Tessera waving (core/terrain.vsh, the same lines in MSL): after the camera-relative position, leaves sway at every corner
     * and plants at their top corners, by world position and Globals.GameTime; the material is the atlas mask's at the vertex
     * UV moved half a cell into its sprite (corner {@code mcmtl_vid & 3}). Unchanged when the translated function has no mask.
     */
    static String wave(final String body, final Matcher m) {
        if (m.group(5) == null) {
            return body;
        }
        String g = m.group(2);
        int at = body.indexOf(";\n") + 2;
        return body.substring(0, at)
            + "    uint tesseraCorner = mcmtl_vid & 3u;\n"
            + "    bool tesseraTop = (tesseraCorner == 0u) || (tesseraCorner == 3u);\n"
            + "    float2 tesseraCells = float2(float(TesseraMaterial.get_width()), float(TesseraMaterial.get_height()));\n"
            + "    float2 tesseraNudge = float2((tesseraCorner < 2u) ? 0.5 : (-0.5), tesseraTop ? 0.5 : (-0.5));\n"
            + "    uint2 tesseraCell = uint2(fast::clamp(floor((in.UV0 * tesseraCells) + tesseraNudge), float2(0.0), tesseraCells - float2(1.0)));\n"
            + "    float tesseraMat = floor((TesseraMaterial.read(tesseraCell, 0).x * 3.0) + 0.5);\n"
            + "    float3 tesseraWorld = float3(in.ChunkPosition & int3(4095)) + in.Position;\n"
            + "    float tesseraAmp = (0.035 * step(1.5, tesseraMat)) + (((0.075 * step(0.5, tesseraMat)) * step(tesseraMat, 1.5)) * (tesseraTop ? 1.0 : 0.0));\n"
            + "    float tesseraPhase = (dot(tesseraWorld.xz, float2(0.37, 0.29)) + (tesseraWorld.y * 0.21)) + (" + g + ".GameTime * 2040.0);\n"
            + "    float3 tesseraSway = float3(sin(tesseraPhase) + (0.45 * sin((tesseraPhase * 2.3) + (tesseraWorld.z * 0.7))), 0.35 * sin((tesseraPhase * 1.3) + tesseraWorld.x), "
            + "(0.8 * cos((tesseraPhase * 0.83) + (tesseraWorld.x * 0.5))) + (0.3 * sin(tesseraPhase * 1.9)));\n"
            + "    pos += (tesseraSway * tesseraAmp);\n"
            + body.substring(at);
    }

    /** The hand-written pull form of a translated multidraw terrain vertex function, or null if it is not the vanilla shape. */
    public static @Nullable String pullVertex(final String translatedMsl) {
        Matcher m = matchesVanilla(translatedMsl);
        if (m == null) {
            return null;
        }
        boolean ts = tessera(translatedMsl);
        return PRAGMA + (ts ? tesseraHead(HEAD) : HEAD)
            + "vertex main0_out main0(uint mcmtl_vid [[vertex_id]], uint mcmtl_iid [[instance_id]], device const uint* mcmtl_vb [[buffer(30)]], "
            + "device const int4* mcmtl_sb [[buffer(29)]], constant Projection& " + m.group(1) + " [[buffer(0)]], constant Globals& " + m.group(2)
            + " [[buffer(1)]], constant TerrainUniform& " + m.group(3) + " [[buffer(" + m.group(4) + ")]], texture2d<float> Sampler2 [[texture(2)]]" + waveParam(m) + ", "
            + "sampler Sampler2Smplr [[sampler(2)]])\n"
            + "{\n    main0_out out = {};\n"
            + "    main0_in in = mcmtl_fetch(mcmtl_vb, mcmtl_sb, mcmtl_vid, mcmtl_iid);\n"
            + (ts ? tesseraBody(wave(body(m.group(1), m.group(2), m.group(3), true), m)) : body(m.group(1), m.group(2), m.group(3), true));
    }

    /**
     * The cluster-path vertex function (round 12 (a)): {@code quads} at {@code [[buffer(quadsSlot)]]} (one uint per output quad:
     * cluster slot << 6 | local quad), {@code items} at {@code [[buffer(itemsSlot)]]} (uint2 per cluster slot: first vertex,
     * section info index). Null if the translated function is not the vanilla shape.
     */
    public static @Nullable String clusterVertex(final String translatedMsl, final int quadsSlot, final int itemsSlot) {
        Matcher m = matchesVanilla(translatedMsl);
        if (m == null) {
            return null;
        }
        boolean ts = tessera(translatedMsl);
        return PRAGMA + (ts ? tesseraHead(HEAD) : HEAD)
            + "vertex main0_out main0(uint mcmtl_vid [[vertex_id]], device const uint* mcmtl_vb [[buffer(30)]], device const int4* mcmtl_sb [[buffer(29)]], "
            + "device const uint* mcmtl_quads [[buffer(" + quadsSlot + ")]], device const uint2* mcmtl_items [[buffer(" + itemsSlot + ")]], "
            + "constant Projection& " + m.group(1) + " [[buffer(0)]], constant Globals& " + m.group(2)
            + " [[buffer(1)]], constant TerrainUniform& " + m.group(3) + " [[buffer(" + m.group(4) + ")]], texture2d<float> Sampler2 [[texture(2)]]" + waveParam(m) + ", "
            + "sampler Sampler2Smplr [[sampler(2)]])\n"
            + "{\n    main0_out out = {};\n"
            + "    uint mcmtl_e = mcmtl_quads[mcmtl_vid >> 2u];\n"
            + "    uint2 mcmtl_item = mcmtl_items[mcmtl_e >> 6u];\n"
            + "    main0_in in = mcmtl_fetch(mcmtl_vb, mcmtl_sb, mcmtl_item.x + 4u * (mcmtl_e & 63u) + (mcmtl_vid & 3u), mcmtl_item.y);\n"
            + (ts ? tesseraBody(wave(body(m.group(1), m.group(2), m.group(3), true), m)) : body(m.group(1), m.group(2), m.group(3), true));
    }

    /**
     * Stage 3 round 1b: the cluster-path vertex function of the LOD greedy pipelines ({@code TERRAIN_CLUSTER} +
     * {@code TERRAIN_GREEDY}, chunk/LodGreedy): {@link #clusterVertex}'s fetch and body, plus the greedy contract of
     * {@code core/terrain.vsh}: the lightmap is sampled with UV2's low bytes and {@code greedyTile} (locn5, flat in the
     * fragment) = UV2's high bytes (atlas tile + 1 of a merged face, 0 otherwise). Not byte-compared with the translated
     * function (LOD only; the LOD look is checked by screenshots); needs only the translated signature's binding names.
     */
    public static @Nullable String clusterVertexGreedy(final String translatedMsl, final int quadsSlot, final int itemsSlot) {
        return clusterVertexLod(translatedMsl, quadsSlot, itemsSlot, false);
    }

    /**
     * Stage 3 round 2: the vertex function of the compact LOD pipelines ({@code *_lod_compact_terrain_cluster}): the fetch decodes
     * the quad's 48-byte record (chunk/LodCompact; three uint4 at {@code [[buffer(30)]]}, record = item.x / 4 + local quad) into
     * the BLOCK attributes of corner {@code vid & 3} (position in cells times 2^level), then {@link #clusterVertexGreedy}'s body.
     */
    public static @Nullable String clusterVertexCompact(final String translatedMsl, final int quadsSlot, final int itemsSlot) {
        return clusterVertexLod(translatedMsl, quadsSlot, itemsSlot, true);
    }

    /** Stage 3 r6 ({@code mc.lod.lean}, default false): the leaner LOD vertex body (see clusterVertexLod). */
    static final boolean LOD_LEAN = Boolean.getBoolean("mc.lod.lean");

    static final String COMPACT_FETCH = "static inline __attribute__((always_inline))\n"
        + "main0_in mcmtl_fetch_compact(device const uint4* rb, device const int4* sb, uint q, uint k, uint s, int2 texSize)\n{\n"
        + "    uint4 a = rb[3u * q];\n"
        + "    uint4 b = rb[3u * q + 1u];\n"
        + "    uint4 c = rb[3u * q + 2u];\n"
        + "    float fa = (k == 1u || k == 2u) ? 1.0 : 0.0;\n"
        + "    float fb = (k >= 2u) ? 1.0 : 0.0;\n"
        + "    float3 p = float3(float(a.x & 1023u), float((a.x >> 10u) & 1023u), float((a.x >> 20u) & 1023u));\n"
        + "    float l1 = float(int(a.y << 18u) >> 20);\n"
        + "    float l2 = float(int(a.y << 4u) >> 20);\n"
        + "    uint x1 = a.y & 3u;\n"
        + "    uint x2 = (a.y >> 14u) & 3u;\n"
        + "    p += fa * l1 * float3(x1 == 0u ? 1.0 : 0.0, x1 == 1u ? 1.0 : 0.0, x1 == 2u ? 1.0 : 0.0);\n"
        + "    p += fb * l2 * float3(x2 == 0u ? 1.0 : 0.0, x2 == 1u ? 1.0 : 0.0, x2 == 2u ? 1.0 : 0.0);\n"
        + "    main0_in in;\n"
        + "    float unit = float(1u << ((a.y >> 28u) & 7u)) * 0.03125;\n"
        + "    in.Position = p * unit;\n"
        // Round 4 (LodTrees): edge-1 axis code 3 = camera-facing billboard, l1 = half width; corners 0 / 3 left, 1 / 2 right.
        + "    in.bbHalf = x1 == 3u ? l1 * unit : 0.0;\n"
        + "    in.bbSide = fa * 2.0 - 1.0;\n"
        + "    uint col = k == 0u ? a.z : k == 1u ? a.w : k == 2u ? b.x : b.y;\n"
        + "    in.Color = unpack_unorm4x8_to_float(col);\n"
        + "    uint lw = (k < 2u ? b.z : b.w) >> ((k & 1u) * 16u);\n"
        + "    in.UV2 = int2(int((lw & 255u) | ((c.x >> 20u) & 255u) << 8u), int(((lw >> 8u) & 255u) | ((c.y >> 20u) & 255u) << 8u));\n"
        + "    float2 d1 = float2(float(int(c.z << 16u) >> 16), float(int(c.z) >> 16));\n"
        + "    float2 d2 = float2(float(int(c.w << 16u) >> 16), float(int(c.w) >> 16));\n"
        + "    float2 uv = float2(float(c.x & 1048575u), float(c.y & 1048575u)) + fa * d1 + fb * d2;\n"
        + "    in.UV0 = uv * (float2(1.0) / (16.0 * float2(texSize)));\n"
        + "    int4 sec = sb[s];\n"
        + "    in.ChunkPosition = sec.xyz;\n"
        + "    in.ChunkVisibility = as_type<float>(sec.w);\n"
        + "    return in;\n}\n\n";

    /** Lit merge ({@code mc.lod.litMerge}, chunk/Lod.LIT_MERGE): compact LOD pipelines pass lit records' cell tables to the fragment. */
    public static final boolean LIT = !"false".equalsIgnoreCase(System.getProperty("mc.lod.litMerge")) && !"0".equals(System.getProperty("mc.lod.litMerge"));

    /**
     * Lit decode, inserted into {@link #COMPACT_FETCH} before the section read: a lit record (word 6 = 0xFFFFFFFF, chunk/LodCompact)
     * passes its cell table (absolute uint4 index, w | h << 8) and the corner's cell coordinates (corner k's (i, j) bits from word 3
     * times w / h) to the fragment; its vertex colour is unused there.
     */
    static final String LIT_FETCH = "    bool lit = b.z == 0xFFFFFFFFu;\n"
        + "    uint cb = (a.w >> (16u + 2u * k)) & 3u;\n"
        + "    in.litRef = lit ? uint2(3u * q + a.z, a.w & 0xFFFFu) : uint2(0u);\n"
        + "    in.litUV = lit ? float2(float(cb & 1u) * float(a.w & 255u), float(cb >> 1u) * float((a.w >> 8u) & 255u)) : float2(0.0);\n"
        + "    if (lit) { in.Color = float4(1.0); }\n";

    /**
     * Lit merge: the compact LOD fragment function rebuilding a lit cell's vertex colour (chunk/LodGreedy#LIT_TABLES): the cell
     * of the interpolated cell coordinates, its four canonical corners' colour * lightmap(light) - exactly the per-vertex value
     * vanilla interpolates - blended with the barycentric weights of the cell's own triangle (split along its diagonal), i.e.
     * the value the unmerged quad's interpolation gives at that point. Reads the vertex arena at fragment buffer 30 (the decoder
     * binds the pipeline's vertex buffer 30 there) and the lightmap at texture / sampler 2. Null if the shape is unexpected.
     */
    public static @Nullable String litFragment(final String msl) {
        String inField = "    int2 greedyTile [[user(locn5)]];\n";
        String use = " * in.vertexColor;\n";
        int sig = msl.indexOf("fragment main0_out main0(main0_in in [[stage_in]]");
        int sigEnd = sig < 0 ? -1 : msl.indexOf(")\n{\n", sig);
        if (msl.indexOf(inField) < 0 || msl.indexOf(inField) != msl.lastIndexOf(inField) || msl.indexOf(use) < 0 || msl.indexOf(use) != msl.lastIndexOf(use)
            || sigEnd < 0 || msl.contains("[[texture(2)]]") || msl.contains("[[sampler(2)]]") || msl.contains("[[buffer(30)]]")) {
            return null;
        }
        String helpers = "static inline __attribute__((always_inline))\n"
            + "float4 mcmtl_lm(texture2d<float> t, sampler sm, uint l)\n{\n"
            + "    int2 uv = int2(int(l & 255u), int((l >> 8u) & 255u));\n"
            + "    return t.sample(sm, fast::clamp((float2(uv) / float2(256.0)) + float2(0.03125), float2(0.03125), float2(0.96875)), level(0.0));\n}\n\n"
            + "static inline __attribute__((always_inline))\n"
            + "float4 mcmtl_lit_color(float4 vc, uint2 ref, float2 luv, device const uint4* tb, texture2d<float> t, sampler sm)\n{\n"
            + "    if (ref.x == 0u) return vc;\n"
            + "    uint w = ref.y & 255u;\n"
            + "    uint h = ref.y >> 8u;\n"
            + "    float2 c = fast::clamp(floor(luv), float2(0.0), float2(float(w) - 1.0, float(h) - 1.0));\n"
            + "    float2 f = fast::clamp(luv - c, float2(0.0), float2(1.0));\n"
            + "    uint cell = ref.x + 2u * (uint(c.y) * w + uint(c.x));\n"
            + "    uint4 col = tb[cell];\n"
            + "    uint4 lt = tb[cell + 1u];\n"
            // corners: 0 = 00, 1 = 10, 2 = 11, 3 = 01; the triangle containing f and its weights
            + "    uint ka; uint kb; uint kc; float wa; float wb; float wc;\n"
            + "    if (lt.z == 0u) {\n"
            + "        if (f.x >= f.y) { ka = 0u; kb = 1u; kc = 2u; wa = 1.0 - f.x; wb = f.x - f.y; wc = f.y; }\n"
            + "        else { ka = 0u; kb = 2u; kc = 3u; wa = 1.0 - f.y; wb = f.x; wc = f.y - f.x; }\n"
            + "    } else {\n"
            + "        if (f.x + f.y <= 1.0) { ka = 0u; kb = 1u; kc = 3u; wa = 1.0 - f.x - f.y; wb = f.x; wc = f.y; }\n"
            + "        else { ka = 1u; kb = 2u; kc = 3u; wa = 1.0 - f.y; wb = f.x + f.y - 1.0; wc = 1.0 - f.x; }\n"
            + "    }\n"
            + "    uint4 lw = uint4(lt.x & 0xFFFFu, lt.x >> 16u, lt.y & 0xFFFFu, lt.y >> 16u);\n"
            + "    float4 ca = unpack_unorm4x8_to_float(col[ka]);\n"
            + "    float4 cb = unpack_unorm4x8_to_float(col[kb]);\n"
            + "    float4 cc = unpack_unorm4x8_to_float(col[kc]);\n"
            + "    if (lw[ka] == lw[kb] && lw[ka] == lw[kc]) {\n"
            + "        float4 l = mcmtl_lm(t, sm, lw[ka]);\n"
            + "        return (ca * l) * wa + (cb * l) * wb + (cc * l) * wc;\n"
            + "    }\n"
            + "    return (ca * mcmtl_lm(t, sm, lw[ka])) * wa + (cb * mcmtl_lm(t, sm, lw[kb])) * wb + (cc * mcmtl_lm(t, sm, lw[kc])) * wc;\n}\n\n"
            // Stage 4 (Tessera lighting): the same interpolation of the unlit colour and the light levels (0..1)
            + "static inline __attribute__((always_inline))\n"
            + "float4 mcmtl_lit_parts(uint2 ref, float2 luv, device const uint4* tb, thread float2& lmOut)\n{\n"
            + "    uint w = ref.y & 255u;\n"
            + "    uint h = ref.y >> 8u;\n"
            + "    float2 c = fast::clamp(floor(luv), float2(0.0), float2(float(w) - 1.0, float(h) - 1.0));\n"
            + "    float2 f = fast::clamp(luv - c, float2(0.0), float2(1.0));\n"
            + "    uint cell = ref.x + 2u * (uint(c.y) * w + uint(c.x));\n"
            + "    uint4 col = tb[cell];\n"
            + "    uint4 lt = tb[cell + 1u];\n"
            + "    uint ka; uint kb; uint kc; float wa; float wb; float wc;\n"
            + "    if (lt.z == 0u) {\n"
            + "        if (f.x >= f.y) { ka = 0u; kb = 1u; kc = 2u; wa = 1.0 - f.x; wb = f.x - f.y; wc = f.y; }\n"
            + "        else { ka = 0u; kb = 2u; kc = 3u; wa = 1.0 - f.y; wb = f.x; wc = f.y - f.x; }\n"
            + "    } else {\n"
            + "        if (f.x + f.y <= 1.0) { ka = 0u; kb = 1u; kc = 3u; wa = 1.0 - f.x - f.y; wb = f.x; wc = f.y; }\n"
            + "        else { ka = 1u; kb = 2u; kc = 3u; wa = 1.0 - f.y; wb = f.x + f.y - 1.0; wc = 1.0 - f.x; }\n"
            + "    }\n"
            + "    uint4 lw = uint4(lt.x & 0xFFFFu, lt.x >> 16u, lt.y & 0xFFFFu, lt.y >> 16u);\n"
            + "    float2 la = float2(float(lw[ka] & 255u), float((lw[ka] >> 8u) & 255u));\n"
            + "    float2 lb = float2(float(lw[kb] & 255u), float((lw[kb] >> 8u) & 255u));\n"
            + "    float2 lc = float2(float(lw[kc] & 255u), float((lw[kc] >> 8u) & 255u));\n"
            + "    lmOut = fast::clamp((la * wa + lb * wb + lc * wc) / float2(240.0), float2(0.0), float2(1.0));\n"
            + "    return unpack_unorm4x8_to_float(col[ka]) * wa + unpack_unorm4x8_to_float(col[kb]) * wb + unpack_unorm4x8_to_float(col[kc]) * wc;\n}\n\n";
        String out = msl.substring(0, sig) + helpers + msl.substring(sig, sigEnd)
            + ", device const uint4* mcmtl_lit [[buffer(30)]], texture2d<float> mcmtl_lightmap [[texture(2)]], sampler mcmtl_lightmapSmplr [[sampler(2)]]"
            + msl.substring(sigEnd);
        // The colour line stays vanilla's text (TerrainShaderVariants' vfog / hvary rewrite matches it): the lit colour replaces
        // in.vertexColor at the top of main (cast back to the varying's type, half4 under hvary).
        String bodyStart = "{\n    main0_out out = {};\n";
        int at = out.indexOf(bodyStart, out.indexOf("fragment main0_out main0("));
        if (at < 0) {
            return null;
        }
        String set = System.getProperty("mc.lod.diag", "").contains("litnofrag")
            ? "    { uint2 gt = as_type<uint2>(in.greedyTile); if (((gt.x >> 8u) & 4095u) != 0u) { in.vertexColor = static_cast<decltype(in.vertexColor)>(float4(1.0)); } in.greedyTile = as_type<int2>(gt & 255u); }\n"
            : "    { uint2 gt = as_type<uint2>(in.greedyTile);\n"
              + "      uint wh = (gt.x >> 8u) & 4095u;\n"
              + "      if (wh != 0u) {\n"
              + "        uint2 mcmtl_ref = uint2((gt.y >> 8u) | (gt.x >> 20u) << 24u, (wh & 63u) | (wh >> 6u) << 8u);\n"
              + "        float2 mcmtl_luv = float2(float4(in.vertexColor).xy);\n"
              + (msl.contains("tesseraLm")
                  ? "        { float2 mcmtl_tl; in.tesseraBase = static_cast<decltype(in.tesseraBase)>(mcmtl_lit_parts(mcmtl_ref, mcmtl_luv, mcmtl_lit, mcmtl_tl)); "
                      + "in.tesseraLm = static_cast<decltype(in.tesseraLm)>(mcmtl_tl); }\n"
                  : "")
              + "        in.vertexColor = static_cast<decltype(in.vertexColor)>(mcmtl_lit_color(float4(1.0), mcmtl_ref, mcmtl_luv, mcmtl_lit, mcmtl_lightmap, mcmtl_lightmapSmplr)); }\n"
              + "      in.greedyTile = as_type<int2>(gt & 255u); }\n";
        return out.substring(0, at + bodyStart.length()) + set + out.substring(at + bodyStart.length());
    }

    private static @Nullable String clusterVertexLod(final String translatedMsl, final int quadsSlot, final int itemsSlot, final boolean compact) {
        Matcher m = SIG.matcher(translatedMsl);
        if (!m.find() || !translatedMsl.contains("greedyTile")) {
            return null;
        }
        String head = HEAD.replace("    float chunkVisibility [[user(locn4)]];\n", "    float chunkVisibility [[user(locn4)]];\n    int2 greedyTile [[user(locn5)]];\n");
        if (compact) {
            head = head.replace("    float ChunkVisibility;\n};\n", "    float ChunkVisibility;\n    float bbHalf;\n    float bbSide;\n};\n");
        }
        boolean lit = compact && LIT;
        String fetch = COMPACT_FETCH;
        if (lit) {
            head = head
                .replace("    float bbSide;\n};\n", "    float bbSide;\n    uint2 litRef;\n    float2 litUV;\n};\n");
            fetch = fetch.replace("    int4 sec = sb[s];\n", LIT_FETCH + "    int4 sec = sb[s];\n");
        }
        String body = body(m.group(1), m.group(2), m.group(3), true);
        if (lit) {
            // No extra varyings: a lit vertex sends its cell coordinates in vertexColor.xy (unused for lit cells) and its table
            // (index low 24 bits << 8 | tile y, index >> 24 << 20 | (w | h << 6) << 8 | tile x; the LOD buffer exceeds 2^24 uint4) in greedyTile's spare high bits.
            String texLine = "    out.texCoord0 = in.UV0;\n";
            if (!body.contains(texLine) || !body.contains("    out.gl_Position.y = -")) {
                return null;
            }
            body = body.replace(texLine, "    if (in.litRef.y != 0u) { out.vertexColor = static_cast<decltype(out.vertexColor)>(float4(in.litUV, 0.0, 0.0)); }\n" + texLine);
            body = body.replace("    out.gl_Position.y = -", "    if (in.litRef.y != 0u) { out.greedyTile = as_type<int2>(as_type<uint2>(out.greedyTile) "
                + "| uint2(((in.litRef.y & 255u) | (in.litRef.y >> 8u) << 6u) << 8u | (in.litRef.x >> 24u) << 20u, in.litRef.x << 8u)); }\n    out.gl_Position.y = -");
        }
        if (LOD_LEAN) {
            // Stage 3 r6 (mc.lod.lean): LOD vertices need not match vanilla's association bit for bit: two matrix-vector products
            // instead of a 4x4 matrix product per vertex, and the section visibility as is (LOD sections are >= 32 blocks away, where
            // vanilla's near-camera fade term is 1).
            String clipHand = "    float4x4 mcmtl_m = " + m.group(1) + ".ProjMat * " + m.group(3) + ".ModelViewMat;\n"
                + "    out.gl_Position = fma(mcmtl_m[2], float4(pos.z), fma(mcmtl_m[1], float4(pos.y), fma(mcmtl_m[0], float4(pos.x), mcmtl_m[3])));\n";
            String clipLean = "    out.gl_Position = " + m.group(1) + ".ProjMat * (" + m.group(3) + ".ModelViewMat * float4(pos, 1.0));\n";
            String vis = "    float dist = length(pos);\n    out.chunkVisibility = mix(1.0, in.ChunkVisibility, fast::clamp((dist - 16.0) / 16.0, 0.0, 1.0));\n";
            if (!body.contains(clipHand) || !body.contains(vis)) {
                return null;
            }
            body = body.replace(clipHand, clipLean).replace(vis, "    out.chunkVisibility = in.ChunkVisibility;\n");
        }
        String light = "    int2 param_2 = in.UV2;\n";
        if (head.equals(HEAD) || !body.contains(light)) {
            return null;
        }
        body = body.replace(light, "    int2 param_2 = in.UV2 & int2(255);\n    out.greedyTile = (in.UV2 >> int2(8)) & int2(255);\n");
        if (tessera(translatedMsl)) {
            head = tesseraHead(head);
            body = tesseraBody(body);
        }
        if (System.getProperty("mc.lod.diag", "").contains("novs")) {
            // Diagnostics (stage 3 r2 cost attribution): every LOD vertex shaded, every primitive outside the clip volume.
            body = body.replace("    return out;\n}\n", "    out.gl_Position = float4(2.0, 2.0, 2.0, 1.0);\n    return out;\n}\n");
        }
        if (System.getProperty("mc.lod.diag", "").contains("minvary")) {
            // Diagnostics: only clip position and colour leave the vertex stage (with flatfs: varying-traffic attribution).
            StringBuilder hb = new StringBuilder();
            for (String line : head.split("\n", -1)) {
                if (!line.contains("[[user(locn") || line.contains("vertexColor")) {
                    hb.append(line).append('\n');
                }
            }
            head = hb.substring(0, hb.length() - 1);
            StringBuilder bb = new StringBuilder();
            for (String line : body.split("\n", -1)) {
                if (!line.startsWith("    out.") || line.startsWith("    out.vertexColor") || line.startsWith("    out.gl_Position")) {
                    bb.append(line).append('\n');
                }
            }
            body = bb.substring(0, bb.length() - 1);
        }
        return PRAGMA + head + (compact ? fetch : "")
            + "vertex main0_out main0(uint mcmtl_vid [[vertex_id]], device const uint* mcmtl_vb [[buffer(30)]], device const int4* mcmtl_sb [[buffer(29)]], "
            + "device const uint* mcmtl_quads [[buffer(" + quadsSlot + ")]], device const uint2* mcmtl_items [[buffer(" + itemsSlot + ")]], "
            + "constant Projection& " + m.group(1) + " [[buffer(0)]], constant Globals& " + m.group(2)
            + " [[buffer(1)]], constant TerrainUniform& " + m.group(3) + " [[buffer(" + m.group(4) + ")]], texture2d<float> Sampler2 [[texture(2)]], "
            + "sampler Sampler2Smplr [[sampler(2)]])\n"
            + "{\n    main0_out out = {};\n"
            + (System.getProperty("mc.lod.diag", "").contains("nofetch")
                ? "    main0_in in = {};\n    in.Position = float3(float(mcmtl_vid & 3u), 0.0, 0.0);\n"
                : compact && System.getProperty("mc.lod.diag", "").contains("direct")
                ? "    main0_in in = mcmtl_fetch_compact((device const uint4*)mcmtl_vb, mcmtl_sb, mcmtl_vid >> 2u, mcmtl_vid & 3u, 0u, " + m.group(3) + ".TextureSize);\n"
                : "    uint mcmtl_e = mcmtl_quads[mcmtl_vid >> 2u];\n    if (mcmtl_e == 0xFFFFFFFFu) { return out; }\n    uint2 mcmtl_item = mcmtl_items[mcmtl_e >> 6u];\n"
                    + (compact
                        ? "    main0_in in = mcmtl_fetch_compact((device const uint4*)mcmtl_vb, mcmtl_sb, (mcmtl_item.x >> 2u) + (mcmtl_e & 63u), mcmtl_vid & 3u, mcmtl_item.y, "
                            + m.group(3) + ".TextureSize);\n"
                        : "    main0_in in = mcmtl_fetch(mcmtl_vb, mcmtl_sb, mcmtl_item.x + 4u * (mcmtl_e & 63u) + (mcmtl_vid & 3u), mcmtl_item.y);\n"))
            + (compact
                ? "    if (in.bbHalf != 0.0) {\n"
                    + "        float3 bbRel = (in.Position + float3(in.ChunkPosition - int3(" + m.group(2) + ".CameraBlockPos))) + float3(" + m.group(2) + ".CameraOffset);\n"
                    + "        float bbLen = length(bbRel.xz);\n"
                    + "        float2 bbDir = bbLen > 1e-4 ? bbRel.xz / bbLen : float2(0.0, 1.0);\n"
                    + "        in.Position.xz += (in.bbSide * in.bbHalf) * float2(-bbDir.y, bbDir.x);\n"
                    + "    }\n"
                : "")
            + body;
    }

    /**
     * Stage 3 r3 (LOD occlusion, CONTRACT 16.S3-LOD-R3): the box-query pipeline {@code pipeline/lod_occlusion_terrain_cluster}.
     * Vertex: one draw of 3 quads per box ({@code vid / 12} = box, {@code (vid >> 2) % 3} = axis, {@code vid & 3} = corner) from the
     * box list at {@code [[buffer(30)]]} (uint2: absolute cluster item, visibility entry), the frame's cluster items at 29, the
     * persistent LOD cull table at 28 ((origin - C0, unit), (C0 - origin)) and the box parameters at 27 (float4 2 = camera - C0,
     * margin; word 12 = this frame's "visible" value): the cluster's widened bounds box, and per axis its face toward the camera
     * (the three faces that cover its silhouette). Fragment ({@code [[early_fragment_tests]]}, no colour or depth writes, depth test
     * GEQUAL against the opaque terrain + LOD of this frame): marks the box's entry visible in the LodOcclusion buffer
     * ({@code [[buffer(occlSlot)]]}). Returns {vertex MSL, fragment MSL} or null.
     */
    public static String @Nullable [] lodOcclusion(final String translatedMsl, final int occlSlot) {
        Matcher m = SIG.matcher(translatedMsl);
        if (!m.find()) {
            return null;
        }
        String head = "#include <metal_stdlib>\nusing namespace metal;\n"
            + "struct Projection\n{\n    float4x4 ProjMat;\n};\n"
            + "struct TerrainUniform\n{\n    float4x4 ModelViewMat;\n    int2 TextureSize;\n};\n"
            + "struct BoxOut\n{\n    float4 pos [[position]];\n    uint vi [[user(locn0), flat]];\n    uint stamp [[user(locn1), flat]];\n};\n";
        String vertex = head
            + "vertex BoxOut main0(uint vid [[vertex_id]], device const uint2* boxes [[buffer(30)]], device const uint4* items [[buffer(29)]], "
            + "device const float4* table [[buffer(28)]], device const float4* params [[buffer(27)]], constant Projection& P [[buffer(0)]], "
            + "constant TerrainUniform& T [[buffer(" + m.group(4) + ")]])\n{\n"
            + "    BoxOut out = {};\n"
            + "    uint b = vid / 12u;\n"
            + "    uint f = (vid >> 2u) % 3u;\n"
            + "    uint k = vid & 3u;\n"
            + "    uint2 bx = boxes[b];\n"
            + "    uint4 it = items[bx.x];\n"
            + "    float4 o4 = table[2u * (it.y & 0xFFFFFu)];\n"
            + "    float4 pr = params[2];\n"
            + "    float3 o = o4.xyz - pr.xyz;\n"
            + "    float3 mn = o + float3(int3(int(it.z & 1023u), int((it.z >> 10) & 1023u), int((it.z >> 20) & 1023u)) - 512) * o4.w - pr.w;\n"
            + "    float3 mx = o + float3(int3(int(it.w & 1023u), int((it.w >> 10) & 1023u), int((it.w >> 20) & 1023u)) - 512) * o4.w + pr.w;\n"
            + "    float a = (k == 1u || k == 2u) ? 1.0 : 0.0;\n"
            + "    float c = (k >= 2u) ? 1.0 : 0.0;\n"
            + "    float3 p;\n"
            + "    if (f == 0u) { p = float3(mn.x > 0.0 ? mn.x : mx.x, mix(mn.y, mx.y, a), mix(mn.z, mx.z, c)); }\n"
            + "    else if (f == 1u) { p = float3(mix(mn.x, mx.x, c), mn.y > 0.0 ? mn.y : mx.y, mix(mn.z, mx.z, a)); }\n"
            + "    else { p = float3(mix(mn.x, mx.x, a), mix(mn.y, mx.y, c), mn.z > 0.0 ? mn.z : mx.z); }\n"
            + "    out.pos = P.ProjMat * (T.ModelViewMat * float4(p, 1.0));\n"
            + "    out.pos.y = -out.pos.y;\n"
            + "    out.vi = bx.y;\n"
            + "    out.stamp = as_type<uint>(params[3].x);\n"
            + "    return out;\n}\n";
        String fragment = head
            + "[[early_fragment_tests]] fragment void main0(BoxOut in [[stage_in]], device uint* occl [[buffer(" + occlSlot + ")]])\n{\n"
            + "    if (occl[in.vi] != in.stamp) { occl[in.vi] = in.stamp; }\n}\n";
        return new String[]{vertex, fragment};
    }

    private static final java.util.Map<String, Boolean> CLUSTER_READY = new java.util.concurrent.ConcurrentHashMap<>();

    /** Round 12 (a): whether the cluster pipeline {@code name} got its pulling vertex function at its last compile. */
    static void setClusterReady(final String name, final boolean ready) {
        CLUSTER_READY.put(name, ready);
    }

    /** Stage 3: whether the cluster pipeline {@code name} (e.g. {@code minecraft:pipeline/translucent_terrain_cluster}) is ready. */
    public static boolean clusterPipelineReady(final String name) {
        return Boolean.TRUE.equals(CLUSTER_READY.get(name));
    }

    /** True if both cluster pipelines compiled with the cluster vertex function at their last compile. */
    public static boolean clusterPipelinesReady() {
        return Boolean.TRUE.equals(CLUSTER_READY.get("minecraft:pipeline/solid_terrain_cluster"))
            && Boolean.TRUE.equals(CLUSTER_READY.get("minecraft:pipeline/cutout_terrain_cluster"));
    }

    private static boolean flag(final String name, final boolean defaultValue) {
        String value = System.getProperty(name);
        return value == null ? defaultValue : !value.equalsIgnoreCase("false") && !value.equals("0");
    }
}
