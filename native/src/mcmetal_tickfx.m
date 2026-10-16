/*
 * mcmetal_tickfx.m -- round 7 (docs/metal/CONTRACT.md 16.R7-1): the per-tick lightmap update as a compute dispatch on a
 * side command buffer, so the first frame after a client tick keeps its overlap with the previous frame.
 *
 * NOT part of the frozen ABI (mcmetal.h is unchanged, still ABI 2): `mcmtl_ext_lightmap_compute` is an optional extension
 * symbol, resolved by name from Java (MetalTickFx); a missing symbol means the vanilla render pass is used.
 *
 * Why (results/ROUND7.md): vanilla samples the lightmap in the VERTEX stage (terrain / block / entity / item / particle /
 * text / leash shaders: vertexColor = Color * sample_lightmap(Sampler2, UV2)). The vanilla update is a render pass. On the
 * Apple GPU the fragment phases of render passes run in submission order, so that pass's fragment work queues behind the
 * previous frame's fragment work, and the next world pass's vertex work (which reads the lightmap) waits for it: the
 * frame after every client tick loses its vertex/fragment overlap with the previous frame (+0.4-0.6 ms GPU). A compute
 * dispatch is not ordered behind the previous frame's fragment phase (no dependency: with the ping-pong lightmap,
 * TexturePingPong / CONTRACT 16.R6-1, it writes the copy no frame in flight samples), so it completes at once and the
 * world pass's vertex work overlaps the previous frame again.
 *
 * Ordering: the side command buffer is committed on the device's main queue from the render thread while the frame is
 * being recorded, i.e. after the previous frame's command buffer and before this frame's: every later read of the
 * texture (this frame's draws, the F3 lightmap chart) is ordered after it by Metal's hazard tracking (tracked textures,
 * same queue), and nothing earlier in this frame's stream touches the copy (Lightmap uses this path only with the
 * ping-pong copy as target). Lifetime: the command buffer retains the texture, pipeline and itself until completed.
 *
 * Kernel: lightmap.fsh with the same expressions in the same order (texCoord = the texel centre the full-screen triangle
 * rasterizes, floor(texCoord * 16) is the texel index either way), compiled with the same options as every game shader
 * (fast math, MSL 3.2). Bit-exactness against the render pass is tested, not assumed: tools/lightmap-compute-test.sh.
 */
#import "mcmetal_internal.h"
#import <objc/runtime.h>
#include <stddef.h>

static char mcmtl_tickfx_lightmap_key;
static char mcmtl_tickfx_lightmap_sky_key;

/* LightmapInfo in std140: six floats (0-23), then the vec3s at 32, 48, 64, 80 (16-byte aligned; packed_float3 + pad). */
static NSString *mcmtl_tickfx_lightmap_source(void) {
    return @"#include <metal_stdlib>\n"
        "using namespace metal;\n"
        "struct LightmapInfo {\n"
        "    float SkyFactor;\n"
        "    float BlockFactor;\n"
        "    float NightVisionFactor;\n"
        "    float DarknessScale;\n"
        "    float BossOverlayWorldDarkeningFactor;\n"
        "    float BrightnessFactor;\n"
        "    float2 _pad0;\n"
        "    packed_float3 BlockLightTint;\n"
        "    float _pad1;\n"
        "    packed_float3 SkyLightColor;\n"
        "    float _pad2;\n"
        "    packed_float3 AmbientColor;\n"
        "    float _pad3;\n"
        "    packed_float3 NightVisionColor;\n"
        "};\n"
        "static inline float get_brightness(float level) {\n"
        "    return level / (4.0 - (3.0 * level));\n"
        "}\n"
        "static inline float3 notGamma(float3 color) {\n"
        "    float maxComponent = fast::max(fast::max(color.x, color.y), color.z);\n"
        "    float maxInverted = 1.0 - maxComponent;\n"
        "    float maxScaled = 1.0 - (((maxInverted * maxInverted) * maxInverted) * maxInverted);\n"
        "    return color * (maxScaled / maxComponent);\n"
        "}\n"
        "static inline float parabolicMixFactor(float level) {\n"
        "    return ((2.0 * level) - 1.0) * ((2.0 * level) - 1.0);\n"
        "}\n"
        "kernel void mcmtl_lightmap(constant LightmapInfo& lightmapInfo [[buffer(0)]],\n"
        "                           texture2d<float, access::write> lightMap [[texture(0)]],\n"
        "                           uint2 gid [[thread_position_in_grid]]) {\n"
        "    if (gid.x >= 16u || gid.y >= 16u) return;\n"
        "    float2 texCoord = (float2(gid) + 0.5) / 16.0;\n"
        "    float block_level = floor(texCoord.x * 16.0) / 15.0;\n"
        "    float sky_level = floor(texCoord.y * 16.0) / 15.0;\n"
        "    float block_brightness = get_brightness(block_level) * lightmapInfo.BlockFactor;\n"
        "    float sky_brightness = get_brightness(sky_level) * lightmapInfo.SkyFactor;\n"
        "    float3 nightVisionColor = float3(lightmapInfo.NightVisionColor) * lightmapInfo.NightVisionFactor;\n"
        "    float3 color = fast::max(float3(lightmapInfo.AmbientColor), nightVisionColor);\n"
        "    color += (float3(lightmapInfo.SkyLightColor) * sky_brightness);\n"
        "    float3 BlockLightColor = mix(float3(lightmapInfo.BlockLightTint), float3(1.0), float3(0.89999997615814208984375 * parabolicMixFactor(block_level)));\n"
        "    color += (BlockLightColor * block_brightness);\n"
        "    color = mix(color, color * float3(0.699999988079071044921875, 0.60000002384185791015625, 0.60000002384185791015625), float3(lightmapInfo.BossOverlayWorldDarkeningFactor));\n"
        "    color -= float3(lightmapInfo.DarknessScale);\n"
        "    color = fast::clamp(color, float3(0.0), float3(1.0));\n"
        "    float3 notGamma_1 = notGamma(color);\n"
        "    color = mix(color, notGamma_1, float3(lightmapInfo.BrightnessFactor));\n"
        "    lightMap.write(float4(color, 1.0), gid);\n"
        "}\n";
}

/* Stage 4 (Tessera): the same kernel writing a second texture with alpha = 0.5 + 0.5 * sky level (the terrain lightmap; the
 * first texture's texels are the same expressions, unchanged). */
static NSString *mcmtl_tickfx_lightmap_sky_source(void) {
    NSString *base = mcmtl_tickfx_lightmap_source();
    NSRange k = [base rangeOfString:@"kernel void mcmtl_lightmap("];
    NSString *kernel = [base substringFromIndex:k.location];
    kernel = [kernel stringByReplacingOccurrencesOfString:@"kernel void mcmtl_lightmap(" withString:@"kernel void mcmtl_lightmap_sky("];
    kernel = [kernel stringByReplacingOccurrencesOfString:@"texture2d<float, access::write> lightMap [[texture(0)]],\n"
        withString:@"texture2d<float, access::write> lightMap [[texture(0)]],\n                           texture2d<float, access::write> skyMap [[texture(1)]],\n"];
    // The twin's light (terrain only): vanilla's expressions with a warm torch colour (orange, brighter
    // near the source) instead of vanilla's near-white block light, stored at half scale (light up to 2 without clipping).
    kernel = [kernel stringByReplacingOccurrencesOfString:@"    lightMap.write(float4(color, 1.0), gid);\n"
        withString:@"    lightMap.write(float4(color, 1.0), gid);\n"
        "    float3 tc = fast::max(float3(lightmapInfo.AmbientColor), nightVisionColor);\n"
        "    tc += (float3(lightmapInfo.SkyLightColor) * sky_brightness);\n"
        "    float3 torch = mix(float3(1.0, 0.60, 0.30), float3(1.0, 0.74, 0.46), block_level * block_level);\n"
        "    tc += torch * (block_brightness * (1.25 + 0.5 * block_level * block_level));\n"
        "    tc = mix(tc, tc * float3(0.699999988079071044921875, 0.60000002384185791015625, 0.60000002384185791015625), float3(lightmapInfo.BossOverlayWorldDarkeningFactor));\n"
        "    tc -= float3(lightmapInfo.DarknessScale);\n"
        "    tc = fast::clamp(tc, float3(0.0), float3(2.0));\n"
        "    float3 tcl = fast::min(tc, float3(1.0));\n"
        "    tc = mix(tcl, notGamma(tcl), float3(lightmapInfo.BrightnessFactor)) + (tc - tcl);\n"
        "    // half scale: headroom to 2 (terrain.fsh doubles it back, then rolls highlights off)\n"
        "    tc *= 0.5;\n"
        "    skyMap.write(float4(tc, 0.5 + 0.5 * sky_level), gid);\n"];
    return [base stringByAppendingString:kernel];
}

static id<MTLComputePipelineState> mcmtl_tickfx_compile(MCMTLDevice *dev, void *key, NSString *source, NSString *name) {
    id<MTLComputePipelineState> pso;
    @synchronized (dev) {
        pso = objc_getAssociatedObject(dev, key);
        if (pso) return pso;
        MTLCompileOptions *options = [MTLCompileOptions new];
        options.languageVersion = (MTLLanguageVersion)(dev->mslLanguageVersion ? dev->mslLanguageVersion : MTLLanguageVersion3_2);
        options.mathMode = MTLMathModeFast;
        NSError *error = nil;
        id<MTLLibrary> lib = [dev->device newLibraryWithSource:source options:options error:&error];
        if (!lib) {
            mcmtl_set_error_nserror("mcmtl_ext_lightmap_compute: kernel compile failed", error);
            return nil;
        }
        id<MTLFunction> fn = [lib newFunctionWithName:name];
        pso = fn ? [dev->device newComputePipelineStateWithFunction:fn error:&error] : nil;
        if (!pso) {
            mcmtl_set_error_nserror("mcmtl_ext_lightmap_compute: pipeline creation failed", error);
            return nil;
        }
        objc_setAssociatedObject(dev, key, pso, OBJC_ASSOCIATION_RETAIN);
    }
    return pso;
}

static id<MTLComputePipelineState> mcmtl_tickfx_lightmap_pso(MCMTLDevice *dev) {
    id<MTLComputePipelineState> pso;
    @synchronized (dev) {
        pso = objc_getAssociatedObject(dev, &mcmtl_tickfx_lightmap_key);
        if (pso) return pso;
        MTLCompileOptions *options = [MTLCompileOptions new];
        options.languageVersion = (MTLLanguageVersion)(dev->mslLanguageVersion ? dev->mslLanguageVersion : MTLLanguageVersion3_2);
        options.mathMode = MTLMathModeFast;
        NSError *error = nil;
        id<MTLLibrary> lib = [dev->device newLibraryWithSource:mcmtl_tickfx_lightmap_source() options:options error:&error];
        if (!lib) {
            mcmtl_set_error_nserror("mcmtl_ext_lightmap_compute: kernel compile failed", error);
            return nil;
        }
        id<MTLFunction> fn = [lib newFunctionWithName:@"mcmtl_lightmap"];
        pso = fn ? [dev->device newComputePipelineStateWithFunction:fn error:&error] : nil;
        if (!pso) {
            mcmtl_set_error_nserror("mcmtl_ext_lightmap_compute: pipeline creation failed", error);
            return nil;
        }
        objc_setAssociatedObject(dev, &mcmtl_tickfx_lightmap_key, pso, OBJC_ASSOCIATION_RETAIN);
    }
    return pso;
}

/* Compiles the kernel (once per device); called at Lightmap creation so the first update does not compile. */
MCMTL_API int32_t mcmtl_ext_lightmap_prepare(mcmtl_handle device) {
    MCMTL_TRY_BEGIN
        mcmtl_clear_error();
        if (!device) {
            mcmtl_set_error("mcmtl_ext_lightmap_prepare: device is 0");
            return MCMTL_E_INVALID_ARGUMENT;
        }
        return mcmtl_tickfx_lightmap_pso(MCMTL_DEVICE(device)) ? MCMTL_OK : MCMTL_E_COMPILE_FAILED;
    MCMTL_TRY_END(MCMTL_E_OBJC_EXCEPTION)
}

/* Writes the 16x16 RGBA8Unorm lightmap `texture` (created with ShaderWrite) from the std140 LightmapInfo bytes `info`
 * (`infoBytes` >= 92) with one compute dispatch on a side command buffer of the device's main queue, committed now. */
MCMTL_API int32_t mcmtl_ext_lightmap_compute(mcmtl_handle device, mcmtl_handle texture, const void *info, uint32_t infoBytes) {
    MCMTL_TRY_BEGIN
        mcmtl_clear_error();
        if (!device || !texture || !info || infoBytes < 92u || infoBytes > 4096u) {
            mcmtl_set_error("mcmtl_ext_lightmap_compute: invalid argument");
            return MCMTL_E_INVALID_ARGUMENT;
        }
        MCMTLDevice *dev = MCMTL_DEVICE(device);
        id<MTLTexture> target = MCMTL_TEXTURE(texture);
        if (target.width != 16 || target.height != 16 || target.pixelFormat != MTLPixelFormatRGBA8Unorm
            || !(target.usage & MTLTextureUsageShaderWrite)) {
            mcmtl_set_error("mcmtl_ext_lightmap_compute: target must be a 16x16 RGBA8Unorm ShaderWrite texture");
            return MCMTL_E_INVALID_ARGUMENT;
        }
        id<MTLComputePipelineState> pso = mcmtl_tickfx_lightmap_pso(dev);
        if (!pso) return MCMTL_E_COMPILE_FAILED;
        uint8_t bytes[96];
        memset(bytes, 0, sizeof bytes);
        memcpy(bytes, info, infoBytes < sizeof bytes ? infoBytes : sizeof bytes);
        id<MTLCommandBuffer> cb = [dev->queue commandBuffer];
        cb.label = @"mcmetal lightmap (R7-1)";
        id<MTLComputeCommandEncoder> enc = [cb computeCommandEncoder];
        [enc setComputePipelineState:pso];
        [enc setBytes:bytes length:sizeof bytes atIndex:0];
        [enc setTexture:target atIndex:0];
        [enc dispatchThreads:MTLSizeMake(16, 16, 1) threadsPerThreadgroup:MTLSizeMake(16, 16, 1)];
        [enc endEncoding];
        [cb commit];
        return MCMTL_OK;
    MCMTL_TRY_END(MCMTL_E_OBJC_EXCEPTION)
}

static int mcmtl_tickfx_is_lightmap_target(id<MTLTexture> t) {
    return t && t.width == 16 && t.height == 16 && t.pixelFormat == MTLPixelFormatRGBA8Unorm && (t.usage & MTLTextureUsageShaderWrite);
}

/* Stage 4 (Tessera): mcmtl_ext_lightmap_compute that also writes `sky` (alpha = 0.5 + 0.5 * sky level), one dispatch. */
MCMTL_API int32_t mcmtl_ext_lightmap_compute_sky(mcmtl_handle device, mcmtl_handle texture, mcmtl_handle sky, const void *info, uint32_t infoBytes) {
    MCMTL_TRY_BEGIN
        mcmtl_clear_error();
        if (!device || !texture || !sky || !info || infoBytes < 92u || infoBytes > 4096u) {
            mcmtl_set_error("mcmtl_ext_lightmap_compute_sky: invalid argument");
            return MCMTL_E_INVALID_ARGUMENT;
        }
        MCMTLDevice *dev = MCMTL_DEVICE(device);
        id<MTLTexture> target = MCMTL_TEXTURE(texture);
        id<MTLTexture> skyTarget = MCMTL_TEXTURE(sky);
        if (!mcmtl_tickfx_is_lightmap_target(target) || !mcmtl_tickfx_is_lightmap_target(skyTarget)) {
            mcmtl_set_error("mcmtl_ext_lightmap_compute_sky: targets must be 16x16 RGBA8Unorm ShaderWrite textures");
            return MCMTL_E_INVALID_ARGUMENT;
        }
        id<MTLComputePipelineState> pso = mcmtl_tickfx_compile(dev, &mcmtl_tickfx_lightmap_sky_key, mcmtl_tickfx_lightmap_sky_source(), @"mcmtl_lightmap_sky");
        if (!pso) return MCMTL_E_COMPILE_FAILED;
        uint8_t bytes[96];
        memset(bytes, 0, sizeof bytes);
        memcpy(bytes, info, infoBytes < sizeof bytes ? infoBytes : sizeof bytes);
        id<MTLCommandBuffer> cb = [dev->queue commandBuffer];
        cb.label = @"mcmetal lightmap + sky (Tessera)";
        id<MTLComputeCommandEncoder> enc = [cb computeCommandEncoder];
        [enc setComputePipelineState:pso];
        [enc setBytes:bytes length:sizeof bytes atIndex:0];
        [enc setTexture:target atIndex:0];
        [enc setTexture:skyTarget atIndex:1];
        [enc dispatchThreads:MTLSizeMake(16, 16, 1) threadsPerThreadgroup:MTLSizeMake(16, 16, 1)];
        [enc endEncoding];
        [cb commit];
        return MCMTL_OK;
    MCMTL_TRY_END(MCMTL_E_OBJC_EXCEPTION)
}

/* ---------------------------------------------------------------------------------------------- */
/* R7-2: animated atlas sprites (docs/metal/CONTRACT.md 16.R7-2)                                    */
/* ---------------------------------------------------------------------------------------------- */

/* Vanilla draws each changed animated sprite with one quad per mip level into the atlas (animate_sprite.vsh +
 * animate_sprite_blit.fsh / animate_sprite_interpolate.fsh): a render pass per mip level, so every tick frame paid the load
 * and store of every level of the 2048x1024x5 block atlas (and of the other animated atlases) on the GPU's in-order
 * fragment phase (results/ROUND7.md). The kernel writes only the sprites' texels, in ONE dispatch on a side command buffer
 * (like the lightmap); a dispatch per sprite and level cost ~0.4 ms of dispatch overhead per tick.
 *
 * Sources: every animation frame texture of the atlas is copied once (at upload, by Java) into one "frames" texture with
 * the same mip count, each frame at (fx, fy) with fx, fy multiples of 2^maxLevel, so level L of a frame is the rectangle
 * (fx >> L, fy >> L, w >> L, h >> L) of the frames texture's level L: a texel read there is the frame texture's texel.
 *
 * Per op (one sprite at one mip level L): the quad covers the padded slot (dstX, dstY, w, h) = (x >> L, y >> L,
 * (width + 2 padding) >> L, (height + 2 padding) >> L); its texCoord runs from -padding/width to 1 + padding/width, sampled
 * NEAREST with clamp-to-edge at level L of the frame texture (srcW x srcH = (width >> L) x (height >> L)). When width,
 * height and padding are multiples of 2^L (Java checks it per atlas, else the render passes are used), slot pixel centre j
 * maps to texel centre j - (padding >> L) exactly, so the sampled texel is clamp(j - padL, 0, srcW - 1); the interpolating
 * shader mixes the current and next frame with progress = float(vertexIndex >> 3) / 1000 (Java passes vertexIndex >> 3).
 * Bit-exactness against the render passes: tools/atlas-anim-test.sh (every ping-pong config runs the kernel). */
typedef struct {
    uint32_t level, dstX, dstY, w, h, pad, srcW, srcH;
    uint32_t curX, curY, nextX, nextY;    /* frame origins in the frames texture at this level */
    int32_t  progress;                    /* (int)(frameProgress * 1000), the draw's first vertex >> 3 */
    uint32_t interp, _pad0, _pad1;
} mcmtl_anim_op;                          /* 64 bytes; Java: MetalTickFx.ANIM_OP_SIZE */

_Static_assert(sizeof(mcmtl_anim_op) == 64, "mcmtl_anim_op layout");

static char mcmtl_tickfx_anim_key;

static NSString *mcmtl_tickfx_anim_source(void) {
    return @"#include <metal_stdlib>\n"
        "using namespace metal;\n"
        "struct AnimOp { uint level; uint dstX; uint dstY; uint w; uint h; uint pad; uint srcW; uint srcH;\n"
        "                uint curX; uint curY; uint nextX; uint nextY; int progress; uint interp; uint _pad0; uint _pad1; };\n"
        "kernel void mcmtl_anim(const device AnimOp* ops [[buffer(0)]],\n"
        "                       texture2d<float, access::read> frames [[texture(0)]],\n"
        "                       texture2d<float, access::write> atlas [[texture(1)]],\n"
        "                       uint3 gid [[thread_position_in_grid]]) {\n"
        "    const device AnimOp& op = ops[gid.z];\n"
        "    if (gid.x >= op.w || gid.y >= op.h) return;\n"
        "    uint tx = (uint)clamp((int)gid.x - (int)op.pad, 0, (int)op.srcW - 1);\n"
        "    uint ty = (uint)clamp((int)gid.y - (int)op.pad, 0, (int)op.srcH - 1);\n"
        "    float4 currentColor = frames.read(uint2(op.curX + tx, op.curY + ty), op.level);\n"
        "    float4 fragColor = currentColor;\n"
        "    if (op.interp != 0u) {\n"
        "        float fAnimationProgress = float(op.progress) / 1000.0;\n"
        "        float4 nextColor = frames.read(uint2(op.nextX + tx, op.nextY + ty), op.level);\n"
        "        fragColor = mix(currentColor, nextColor, float4(fAnimationProgress));\n"
        "    }\n"
        "    atlas.write(fragColor, uint2(op.dstX + gid.x, op.dstY + gid.y), op.level);\n"
        "}\n";
}

static id<MTLComputePipelineState> mcmtl_tickfx_anim_pso(MCMTLDevice *dev) {
    id<MTLComputePipelineState> pso;
    @synchronized (dev) {
        pso = objc_getAssociatedObject(dev, &mcmtl_tickfx_anim_key);
        if (pso) return pso;
        MTLCompileOptions *options = [MTLCompileOptions new];
        options.languageVersion = (MTLLanguageVersion)(dev->mslLanguageVersion ? dev->mslLanguageVersion : MTLLanguageVersion3_2);
        options.mathMode = MTLMathModeFast;
        NSError *error = nil;
        id<MTLLibrary> lib = [dev->device newLibraryWithSource:mcmtl_tickfx_anim_source() options:options error:&error];
        if (!lib) {
            mcmtl_set_error_nserror("mcmtl_ext_atlas_animate: kernel compile failed", error);
            return nil;
        }
        id<MTLFunction> fn = [lib newFunctionWithName:@"mcmtl_anim"];
        pso = fn ? [dev->device newComputePipelineStateWithFunction:fn error:&error] : nil;
        if (!pso) {
            mcmtl_set_error_nserror("mcmtl_ext_atlas_animate: pipeline creation failed", error);
            return nil;
        }
        objc_setAssociatedObject(dev, &mcmtl_tickfx_anim_key, pso, OBJC_ASSOCIATION_RETAIN);
    }
    return pso;
}

MCMTL_API int32_t mcmtl_ext_atlas_prepare(mcmtl_handle device) {
    MCMTL_TRY_BEGIN
        mcmtl_clear_error();
        if (!device) {
            mcmtl_set_error("mcmtl_ext_atlas_prepare: device is 0");
            return MCMTL_E_INVALID_ARGUMENT;
        }
        return mcmtl_tickfx_anim_pso(MCMTL_DEVICE(device)) ? MCMTL_OK : MCMTL_E_COMPILE_FAILED;
    MCMTL_TRY_END(MCMTL_E_OBJC_EXCEPTION)
}

/* Writes `count` ops (mcmtl_anim_op; validated first: rects inside the atlas level, sources inside the frames texture
 * level) into the RGBA8Unorm ShaderWrite `atlas`, reading the frames texture `frames`, with one dispatch on a side command
 * buffer of the device's main queue, committed now. All-or-nothing: an invalid op fails the call before anything is
 * encoded. */
MCMTL_API int32_t mcmtl_ext_atlas_animate(mcmtl_handle device, mcmtl_handle atlasTexture, mcmtl_handle framesTexture, const void *opsPtr,
                                          uint32_t count) {
    MCMTL_TRY_BEGIN
        mcmtl_clear_error();
        if (!device || !atlasTexture || !framesTexture || (!opsPtr && count) || count > 65535u) {
            mcmtl_set_error("mcmtl_ext_atlas_animate: invalid argument");
            return MCMTL_E_INVALID_ARGUMENT;
        }
        if (count == 0) return MCMTL_OK;
        MCMTLDevice *dev = MCMTL_DEVICE(device);
        id<MTLTexture> atlas = MCMTL_TEXTURE(atlasTexture);
        id<MTLTexture> frames = MCMTL_TEXTURE(framesTexture);
        if (atlas.pixelFormat != MTLPixelFormatRGBA8Unorm || !(atlas.usage & MTLTextureUsageShaderWrite)
            || frames.pixelFormat != MTLPixelFormatRGBA8Unorm || !(frames.usage & MTLTextureUsageShaderRead)) {
            mcmtl_set_error("mcmtl_ext_atlas_animate: atlas must be RGBA8Unorm ShaderWrite, frames RGBA8Unorm ShaderRead");
            return MCMTL_E_INVALID_ARGUMENT;
        }
        const mcmtl_anim_op *ops = (const mcmtl_anim_op *)opsPtr;
        uint32_t maxW = 1, maxH = 1;
        for (uint32_t i = 0; i < count; i++) {
            const mcmtl_anim_op *op = &ops[i];
            if (op->level >= atlas.mipmapLevelCount || op->level >= frames.mipmapLevelCount || op->w == 0 || op->h == 0
                || op->srcW == 0 || op->srcH == 0) {
                mcmtl_set_error("mcmtl_ext_atlas_animate: op %u invalid", i);
                return MCMTL_E_INVALID_ARGUMENT;
            }
            uint64_t aw = MAX(1u, atlas.width >> op->level), ah = MAX(1u, atlas.height >> op->level);
            uint64_t fw = MAX(1u, frames.width >> op->level), fh = MAX(1u, frames.height >> op->level);
            if ((uint64_t)op->dstX + op->w > aw || (uint64_t)op->dstY + op->h > ah
                || (uint64_t)op->curX + op->srcW > fw || (uint64_t)op->curY + op->srcH > fh
                || (op->interp && ((uint64_t)op->nextX + op->srcW > fw || (uint64_t)op->nextY + op->srcH > fh))) {
                mcmtl_set_error("mcmtl_ext_atlas_animate: op %u outside a texture level", i);
                return MCMTL_E_INVALID_ARGUMENT;
            }
            maxW = MAX(maxW, op->w);
            maxH = MAX(maxH, op->h);
        }
        id<MTLComputePipelineState> pso = mcmtl_tickfx_anim_pso(dev);
        if (!pso) return MCMTL_E_COMPILE_FAILED;
        id<MTLCommandBuffer> cb = [dev->queue commandBuffer];
        cb.label = @"mcmetal atlas animation (R7-2)";
        id<MTLComputeCommandEncoder> enc = [cb computeCommandEncoder];
        [enc setComputePipelineState:pso];
        size_t bytes = (size_t)count * sizeof(mcmtl_anim_op);
        if (bytes <= 4096) {
            [enc setBytes:ops length:bytes atIndex:0];
        } else {
            id<MTLBuffer> buffer = [dev->device newBufferWithBytes:ops length:bytes options:MTLResourceStorageModeShared];
            if (!buffer) {
                [enc endEncoding];
                mcmtl_set_error("mcmtl_ext_atlas_animate: op buffer allocation failed");
                return MCMTL_E_OUT_OF_MEMORY;
            }
            [enc setBuffer:buffer offset:0 atIndex:0];
        }
        [enc setTexture:frames atIndex:0];
        [enc setTexture:atlas atIndex:1];
        NSUInteger gx = MIN((NSUInteger)maxW, (NSUInteger)16), gy = MIN((NSUInteger)maxH, (NSUInteger)16);
        [enc dispatchThreads:MTLSizeMake(maxW, maxH, count) threadsPerThreadgroup:MTLSizeMake(gx, gy, 1)];
        [enc endEncoding];
        [cb commit];
        return MCMTL_OK;
    MCMTL_TRY_END(MCMTL_E_OBJC_EXCEPTION)
}
