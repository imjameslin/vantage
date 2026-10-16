/* Prompt 5 Phase 1 precursor. Internal, opt-in building block; no engine hooks. */
#ifndef MCMETAL_FARWARP_H
#define MCMETAL_FARWARP_H

#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#include <stdint.h>

typedef NS_ENUM(uint32_t, MCMTLFarWarpMotion) {
    MCMTLFarWarpMotionUnspecified = 0,
    MCMTLFarWarpMotionPureRotation = 1,
    MCMTLFarWarpMotionTranslation = 2 /* Explicitly unsupported, including zero displacement. */
};

typedef NS_ENUM(uint8_t, MCMTLFarWarpValidity) {
    MCMTLFarWarpMissing = 0,       /* Ray/positive-weight footprint outside source guard/clip. */
    MCMTLFarWarpUncertain = 1,     /* Invalid/nonfinite data, unknown or mixed face IDs. */
    MCMTLFarWarpGeometry = 2,      /* Every required sample has the SAME nonzero face ID. */
    MCMTLFarWarpSky = 3            /* Every required sample has depth == 0 AND ID == 0. */
};

typedef struct {
    __unsafe_unretained id<MTLTexture> color;        /* RGBA8Unorm, original source bytes. */
    __unsafe_unretained id<MTLTexture> depth;        /* Depth32Float, reversed Z [0,1]. */
    __unsafe_unretained id<MTLTexture> visibilityID; /* R32Uint; zero means no known face. */
} MCMTLFarWarpSource;

typedef struct {
    __unsafe_unretained id<MTLTexture> color;        /* RGBA8Unorm */
    __unsafe_unretained id<MTLTexture> depth;        /* R32Float (compute-writable) */
    __unsafe_unretained id<MTLTexture> visibilityID; /* R32Uint */
    __unsafe_unretained id<MTLTexture> validity;     /* R8Uint, MCMTLFarWarpValidity */
} MCMTLFarWarpTargets;

typedef struct {
    /* Mutually inverse COLUMN-MAJOR homogeneous clip-to-clip transforms, NOT
     * inverse view/projection matrices. Already include Metal Y flip. Physical
     * top-left texture coordinates use clip xy = (2u-1, 1-2v); no extra Y flip.
     * Current and source must share the optical centre. Both transforms must
     * preserve that centre: column 2 has exact zeros in rows 0,1,3, and positive
     * row 2. This rules out depth-dependent ray mapping, including translation.
     * A caller computing in double may canonicalize these structural zeros on
     * the CPU ONLY after independently proving zero translation. Native code
     * stays fail-closed and never silently removes a small translation term.
     * Caller must still declare the actual camera motion truthfully: these two
     * matrices alone cannot establish scene stationarity or camera provenance.
     */
    float currentToSourceClip[16];
    float sourceToCurrentClip[16];
    MCMTLFarWarpMotion motion;     /* Must explicitly be PureRotation. */
    float translation[3];         /* Actual camera displacement; must be finite, exactly zero. */
} MCMTLFarWarpTransform;

@interface MCMTLFarWarp : NSObject
- (instancetype)init NS_UNAVAILABLE;
+ (instancetype)new NS_UNAVAILABLE;
/* WORKER ONLY: compiles one immutable compute pipeline. No queue creation.
 * Targets must also be allocated by the caller on a worker, outside encoding.
 */
- (instancetype)initWithDevice:(id<MTLDevice>)device error:(NSError **)error;

/* One bounded pass, <= 4 source texels per plane per output pixel. No queue, commit, wait,
 * compilation, texture/buffer allocation, decoder access, or source mutation.
 * Caller exclusively owns an unqueued, retained-reference command buffer with
 * no open encoder. All validation precedes encoder creation. Any NO after
 * encoder creation requires discarding that buffer; NO never refreshes targets.
 *
 * Source planes share dimensions; target planes share current viewport size.
 * Dimensions are 1..16384. Textures must be distinct, tracked, standalone,
 * single-sample, single-mip 2D with identity swizzles and shader-read (source)
 * / shader-write (target) usage. Heap/buffer/IOSurface-backed textures, views,
 * sparse/external/shareable storage are rejected conservatively to exclude
 * aliasing. Ordinary private/shared/managed storage is accepted.
 *
 * Attach the source MCMTLFarImageLease to this main command buffer BEFORE this
 * call, and keep its pin through actual main completion. Retaining a texture
 * does NOT pin an image slot or its face-ID inputs. Caller orders other source
 * uploads, preserves immutable image/ID inputs, and orders all target readers.
 * The worker neither acquires nor releases leases and never reads a prior warp.
 *
 * Exact identity and integer crops bypass resampling and preserve all color
 * bytes, float depth bits and IDs for valid pixels. Canonical crop transforms
 * (source W,H; destination w,h; integer top-left x,y) are column-major diagonal
 * (w/W,h/H,1,1), translation ((2x+w-W)/W,(H-2y-h)/H,0); inverse diagonal
 * (W/w,H/h,1,1), translation ((W-w-2x)/w,(2y+h-H)/h,0).
 * Compute each ratio in double then cast once to float. Recognition is exact,
 * with NO epsilon snapping of fractional footprints or positive weights.
 *
 * Other footprints bilinearly interpolate RGBA and SOURCE clip depth only
 * when all positive-weight neighbours have the same nonzero ID and valid
 * positive depth. Current depth is then reprojected and must remain >0 and
 * <=1; the zero-depth clipping boundary is Missing for geometry. Zero-weight
 * neighbours are ignored, including off-guard/other-ID neighbours. Sky requires all
 * required depths/IDs zero. Missing/uncertain pixels get zero RGBA/depth/ID;
 * every successful pass writes every target pixel, including validity.
 *
 * Scope: one original opaque/cutout layer, pure rotation only. No translation,
 * disocclusion repair, multi-image merge, near composition, translucency,
 * MSAA, temporal scene validation or integration. Masks describe this bounded
 * sampling contract, NOT a visibility proof for translation or changed scenes.
 * This precursor alone does NOT qualify Prompt 5 Phase 1.
 */
- (BOOL)encodeToCommandBuffer:(id<MTLCommandBuffer>)buffer
                       source:(MCMTLFarWarpSource)source
                      targets:(MCMTLFarWarpTargets)targets
                    transform:(const MCMTLFarWarpTransform *)transform
                        error:(NSError **)error;
@end

#endif
