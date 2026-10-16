/*
 * mcmetal_resources.m -- buffers, textures, texture views, texel buffer views, samplers, query pools,
 * handle release.
 * OWNER: I1 (native-core). Contract: docs/metal/CONTRACT.md sections 3 and 6; mcmetal.h; spec 2.2, 2.13, 3.7.
 *
 * Every creation export validates its descriptor before touching Metal: under MTL_DEBUG_LAYER=1 an
 * invalid Metal call aborts the process instead of returning nil, so a bad value from Java must turn into
 * an error code here. Resources are created exactly as described (raw MTL enum values from
 * MetalFormats.java); nothing is zero-filled (textures: INIT_TEXTURE in the init stream, spec U15).
 *
 * Samplers are deduplicated per device (key = every descriptor field): identical descriptors return the
 * same MTLSamplerState with a new +1 reference, so Java's own refcounted cache and this one compose.
 */
#import "mcmetal_core.h"

#include <string.h>

/* ---------------------------------------------------------------------------------------------- */
/* Shared validation                                                                                */
/* ---------------------------------------------------------------------------------------------- */

/* MTLResourceOptions accepted by M1: cache mode Default/WriteCombined, storage Shared/Managed/Private
 * (Memoryless only for render-target textures), hazard tracking Default/Tracked. Untracked resources need
 * the M2 fence chain (MCMTL_DEVICE_FLAG_UNTRACKED), so they are rejected while that flag is unsupported. */
static int32_t mcmtl_check_resource_options(MCMTLDevice *dev, uint32_t options, BOOL texture, const char *what) {
    const uint32_t known = (uint32_t)(MTLResourceCPUCacheModeMask | MTLResourceStorageModeMask | MTLResourceHazardTrackingModeMask);
    if (options & ~known) {
        mcmtl_set_error("%s: unknown MTLResourceOptions bits 0x%x", what, options & ~known);
        return MCMTL_E_INVALID_ARGUMENT;
    }
    uint32_t cacheMode = (options & MTLResourceCPUCacheModeMask) >> MTLResourceCPUCacheModeShift;
    uint32_t storage = (options & MTLResourceStorageModeMask) >> MTLResourceStorageModeShift;
    uint32_t hazard = (options & MTLResourceHazardTrackingModeMask) >> MTLResourceHazardTrackingModeShift;
    if (cacheMode > MTLCPUCacheModeWriteCombined) {
        mcmtl_set_error("%s: invalid CPU cache mode %u", what, cacheMode);
        return MCMTL_E_INVALID_ARGUMENT;
    }
    if (storage > MTLStorageModeMemoryless) {
        mcmtl_set_error("%s: invalid storage mode %u", what, storage);
        return MCMTL_E_INVALID_ARGUMENT;
    }
    if (storage == MTLStorageModeMemoryless && !texture) {
        mcmtl_set_error("%s: buffers cannot use MTLStorageModeMemoryless", what);
        return MCMTL_E_UNSUPPORTED;
    }
    if (cacheMode == MTLCPUCacheModeWriteCombined && (storage == MTLStorageModePrivate || storage == MTLStorageModeMemoryless)) {
        mcmtl_set_error("%s: write-combined CPU cache mode needs CPU-visible storage", what);
        return MCMTL_E_INVALID_ARGUMENT;
    }
    if (hazard > MTLHazardTrackingModeTracked) {
        mcmtl_set_error("%s: invalid hazard tracking mode %u", what, hazard);
        return MCMTL_E_INVALID_ARGUMENT;
    }
    if (hazard == MTLHazardTrackingModeUntracked && !(dev->flags & MCMTL_DEVICE_FLAG_UNTRACKED)) {
        mcmtl_set_error("%s: untracked hazard mode needs MCMTL_DEVICE_FLAG_UNTRACKED (M2)", what);
        return MCMTL_E_UNSUPPORTED;
    }
    return MCMTL_OK;
}

static NSString *mcmtl_label(const char *label) {
    if (!label || !label[0]) return nil;
    NSString *s = [NSString stringWithUTF8String:label];
    return s;
}

static uint32_t mcmtl_max_mip_levels(uint32_t width, uint32_t height) {
    uint32_t size = width > height ? width : height;
    uint32_t levels = 1;
    while (size > 1) {
        size >>= 1;
        levels++;
    }
    return levels;
}

/* ---------------------------------------------------------------------------------------------- */
/* Buffers                                                                                          */
/* ---------------------------------------------------------------------------------------------- */

MCMTL_API int32_t mcmtl_buffer_create(mcmtl_handle device, const mcmtl_buffer_desc *desc, mcmtl_buffer_result *out) {
    MCMTL_TRY_BEGIN
        mcmtl_clear_error();
        if (!out) {
            mcmtl_set_error("mcmtl_buffer_create: out is NULL");
            return MCMTL_E_INVALID_ARGUMENT;
        }
        if (out->structSize != sizeof(mcmtl_buffer_result)) {
            mcmtl_set_error("mcmtl_buffer_create: result structSize %u != %zu", out->structSize, sizeof(mcmtl_buffer_result));
            return MCMTL_E_ABI_MISMATCH;
        }
        memset(out, 0, sizeof *out);
        out->structSize = (uint32_t)sizeof(mcmtl_buffer_result);
        if (!device || !desc) {
            mcmtl_set_error("mcmtl_buffer_create: %s is NULL", device ? "desc" : "device");
            return MCMTL_E_INVALID_ARGUMENT;
        }
        if (desc->structSize != sizeof(mcmtl_buffer_desc)) {
            mcmtl_set_error("mcmtl_buffer_create: structSize %u != %zu", desc->structSize, sizeof(mcmtl_buffer_desc));
            return MCMTL_E_ABI_MISMATCH;
        }
        MCMTLDevice *dev = MCMTL_DEVICE(device);
        if (desc->size == 0) {
            mcmtl_set_error("mcmtl_buffer_create: size is 0");
            return MCMTL_E_INVALID_ARGUMENT;
        }
        int32_t rc = mcmtl_check_resource_options(dev, desc->resourceOptions, NO, "mcmtl_buffer_create");
        if (rc != MCMTL_OK) return rc;

        uint64_t length = desc->size;
        /* std140 blocks are sized by their last member (a lone vec3 block is 12 bytes) but the MSL
           struct SPIRV-Cross emits rounds up to its 16-byte alignment, and Metal validation rejects a
           binding whose buffer ends before the struct does. Keep 16 bytes of tail in every allocation;
           Java still reports the requested size. */
        if (length > UINT64_MAX - 15) {
            mcmtl_set_error("mcmtl_buffer_create: size %llu overflows", (unsigned long long)length);
            return MCMTL_E_INVALID_ARGUMENT;
        }
        length = mcmtl_core_align_up(length, 16);
        if (desc->usage & MCMTL_CORE_USAGE_UNIFORM_TEXEL_BUFFER) {
            /* Texel views use bytesPerRow = alignUp(length, alignment): keep that inside the allocation. */
            uint64_t alignment = mcmtl_core_state(dev)->maxTexelBufferAlignment;
            if (length > UINT64_MAX - alignment) {
                mcmtl_set_error("mcmtl_buffer_create: size %llu overflows", (unsigned long long)length);
                return MCMTL_E_OUT_OF_MEMORY;
            }
            length = mcmtl_core_align_up(length, alignment);
        }
        uint64_t maxLength = (uint64_t)dev->device.maxBufferLength;
        if (length > maxLength) {
            mcmtl_set_error("mcmtl_buffer_create: %llu bytes exceed maxBufferLength %llu", (unsigned long long)length,
                            (unsigned long long)maxLength);
            return MCMTL_E_OUT_OF_MEMORY;
        }
        id<MTLBuffer> buffer = [dev->device newBufferWithLength:(NSUInteger)length options:(MTLResourceOptions)desc->resourceOptions];
        if (!buffer) {
            mcmtl_set_error("newBufferWithLength:%llu options:0x%x returned nil", (unsigned long long)length, desc->resourceOptions);
            return MCMTL_E_OUT_OF_MEMORY;
        }
        NSString *label = mcmtl_label(desc->label);
        if (label) buffer.label = label;
        out->contents = buffer.storageMode == MTLStorageModePrivate ? 0 : (uint64_t)(uintptr_t)buffer.contents;
        out->gpuAddress = buffer.gpuAddress;
        out->length = (uint64_t)buffer.length;
        out->handle = mcmtl_handle_retain(buffer);
        return MCMTL_OK;
    MCMTL_TRY_END(MCMTL_E_OBJC_EXCEPTION)
}

/* ---------------------------------------------------------------------------------------------- */
/* Textures and views                                                                               */
/* ---------------------------------------------------------------------------------------------- */

/* Common prologue of the exports that fill an mcmtl_object_result: validate and zero the result first (so
 * every later failure leaves handle = 0), then the device and the descriptor (whose first field is its
 * structSize). */
static int32_t mcmtl_object_begin(mcmtl_handle device, const void *desc, size_t descSize, mcmtl_object_result *out, const char *what) {
    if (!out) {
        mcmtl_set_error("%s: out is NULL", what);
        return MCMTL_E_INVALID_ARGUMENT;
    }
    if (out->structSize != sizeof(mcmtl_object_result)) {
        mcmtl_set_error("%s: result structSize %u != %zu", what, out->structSize, sizeof(mcmtl_object_result));
        return MCMTL_E_ABI_MISMATCH;
    }
    memset(out, 0, sizeof *out);
    out->structSize = (uint32_t)sizeof(mcmtl_object_result);
    if (!device || !desc) {
        mcmtl_set_error("%s: %s is NULL", what, device ? "desc" : "device");
        return MCMTL_E_INVALID_ARGUMENT;
    }
    uint32_t structSize = *(const uint32_t *)desc;
    if (structSize != descSize) {
        mcmtl_set_error("%s: structSize %u != %zu", what, structSize, descSize);
        return MCMTL_E_ABI_MISMATCH;
    }
    return MCMTL_OK;
}

MCMTL_API int32_t mcmtl_texture_create(mcmtl_handle device, const mcmtl_texture_desc *desc, mcmtl_object_result *out) {
    MCMTL_TRY_BEGIN
        mcmtl_clear_error();
        int32_t rc = mcmtl_object_begin(device, desc, sizeof *desc, out, "mcmtl_texture_create");
        if (rc != MCMTL_OK) return rc;
        MCMTLDevice *dev = MCMTL_DEVICE(device);

        const uint32_t format = desc->pixelFormat;
        if (format == MTLPixelFormatInvalid) {
            mcmtl_set_error("mcmtl_texture_create: pixelFormat is Invalid");
            return MCMTL_E_INVALID_ARGUMENT;
        }
        if (format == MTLPixelFormatDepth24Unorm_Stencil8 && !dev->device.depth24Stencil8PixelFormatSupported) {
            mcmtl_set_error("mcmtl_texture_create: Depth24Unorm_Stencil8 is not supported on this GPU");
            return MCMTL_E_UNSUPPORTED;
        }
        const uint32_t type = desc->textureType;
        if (type != MTLTextureType2D && type != MTLTextureTypeCube) {
            mcmtl_set_error("mcmtl_texture_create: textureType %u unsupported (2D or Cube)", type);
            return MCMTL_E_UNSUPPORTED;
        }
        if (desc->width < 1 || desc->height < 1 || desc->width > 16384 || desc->height > 16384) {
            mcmtl_set_error("mcmtl_texture_create: size %ux%u outside 1..16384", desc->width, desc->height);
            return MCMTL_E_INVALID_ARGUMENT;
        }
        if (type == MTLTextureTypeCube && desc->width != desc->height) {
            mcmtl_set_error("mcmtl_texture_create: cube texture must be square (%ux%u)", desc->width, desc->height);
            return MCMTL_E_INVALID_ARGUMENT;
        }
        if (desc->depth > 1 || desc->arrayLength > 1) {
            mcmtl_set_error("mcmtl_texture_create: depth %u / arrayLength %u must be 1", desc->depth, desc->arrayLength);
            return MCMTL_E_INVALID_ARGUMENT;
        }
        uint32_t maxLevels = mcmtl_max_mip_levels(desc->width, desc->height);
        if (desc->mipLevels < 1 || desc->mipLevels > maxLevels) {
            mcmtl_set_error("mcmtl_texture_create: mipLevels %u outside 1..%u for %ux%u", desc->mipLevels, maxLevels,
                            desc->width, desc->height);
            return MCMTL_E_INVALID_ARGUMENT;
        }
        const uint32_t knownUsage = (uint32_t)(MTLTextureUsageShaderRead | MTLTextureUsageShaderWrite | MTLTextureUsageRenderTarget |
                                               MTLTextureUsagePixelFormatView | MTLTextureUsageShaderAtomic);
        if (desc->textureUsage & ~knownUsage) {
            mcmtl_set_error("mcmtl_texture_create: unknown MTLTextureUsage bits 0x%x", desc->textureUsage & ~knownUsage);
            return MCMTL_E_INVALID_ARGUMENT;
        }
        rc = mcmtl_check_resource_options(dev, desc->resourceOptions, YES, "mcmtl_texture_create");
        if (rc != MCMTL_OK) return rc;
        uint32_t storage = (desc->resourceOptions & MTLResourceStorageModeMask) >> MTLResourceStorageModeShift;
        if (storage == MTLStorageModeMemoryless && desc->textureUsage != MTLTextureUsageRenderTarget) {
            mcmtl_set_error("mcmtl_texture_create: memoryless textures must have usage RenderTarget only");
            return MCMTL_E_INVALID_ARGUMENT;
        }

        MTLTextureDescriptor *td = [MTLTextureDescriptor new];
        td.textureType = (MTLTextureType)type;
        td.pixelFormat = (MTLPixelFormat)format;
        td.width = desc->width;
        td.height = desc->height;
        td.depth = 1;
        td.mipmapLevelCount = desc->mipLevels;
        td.arrayLength = 1;
        td.sampleCount = 1;
        td.resourceOptions = (MTLResourceOptions)desc->resourceOptions;
        td.usage = (MTLTextureUsage)desc->textureUsage;
        id<MTLTexture> texture = [dev->device newTextureWithDescriptor:td];
        if (!texture) {
            mcmtl_set_error("newTextureWithDescriptor: returned nil (%ux%u format %u, %u mips, usage 0x%x)", desc->width,
                            desc->height, format, desc->mipLevels, desc->textureUsage);
            return MCMTL_E_OUT_OF_MEMORY;
        }
        NSString *label = mcmtl_label(desc->label);
        if (label) texture.label = label;
        out->resourceId = texture.gpuResourceID._impl;
        out->handle = mcmtl_handle_retain(texture);
        return MCMTL_OK;
    MCMTL_TRY_END(MCMTL_E_OBJC_EXCEPTION)
}

MCMTL_API int32_t mcmtl_texture_view_create(mcmtl_handle device, const mcmtl_texture_view_desc *desc, mcmtl_object_result *out) {
    MCMTL_TRY_BEGIN
        mcmtl_clear_error();
        int32_t rc = mcmtl_object_begin(device, desc, sizeof *desc, out, "mcmtl_texture_view_create");
        if (rc != MCMTL_OK) return rc;
        if (!desc->texture) {
            mcmtl_set_error("mcmtl_texture_view_create: texture is 0");
            return MCMTL_E_INVALID_ARGUMENT;
        }
        id<MTLTexture> parent = MCMTL_TEXTURE(desc->texture);
        const MTLPixelFormat format = desc->pixelFormat ? (MTLPixelFormat)desc->pixelFormat : parent.pixelFormat;
        if (format != parent.pixelFormat && !(parent.usage & MTLTextureUsagePixelFormatView)) {
            mcmtl_set_error("mcmtl_texture_view_create: pixelFormat %u != parent %lu without PixelFormatView usage",
                            (uint32_t)format, (unsigned long)parent.pixelFormat);
            return MCMTL_E_INVALID_ARGUMENT;
        }
        const MTLTextureType parentType = parent.textureType;
        const MTLTextureType type = desc->textureType ? (MTLTextureType)desc->textureType : parentType;
        const BOOL cubeFace = parentType == MTLTextureTypeCube && type == MTLTextureType2D;
        if (type != parentType && !cubeFace) {
            mcmtl_set_error("mcmtl_texture_view_create: textureType %lu incompatible with parent type %lu", (unsigned long)type,
                            (unsigned long)parentType);
            return MCMTL_E_INVALID_ARGUMENT;
        }
        const uint32_t parentLevels = (uint32_t)parent.mipmapLevelCount;
        if (desc->levelCount < 1 || desc->baseLevel >= parentLevels || desc->levelCount > parentLevels - desc->baseLevel) {
            mcmtl_set_error("mcmtl_texture_view_create: levels [%u, +%u) outside the parent's %u", desc->baseLevel,
                            desc->levelCount, parentLevels);
            return MCMTL_E_INVALID_ARGUMENT;
        }
        const uint32_t parentSlices = parentType == MTLTextureTypeCube ? 6u * (uint32_t)parent.arrayLength : (uint32_t)parent.arrayLength;
        uint32_t sliceCount = desc->sliceCount;
        if (sliceCount == 0) sliceCount = type == MTLTextureTypeCube ? 6 : 1;
        if (desc->baseSlice >= parentSlices || sliceCount > parentSlices - desc->baseSlice ||
            (type == MTLTextureTypeCube && (sliceCount % 6 != 0 || desc->baseSlice % 6 != 0)) ||
            (type == MTLTextureType2D && sliceCount != 1)) {
            mcmtl_set_error("mcmtl_texture_view_create: slices [%u, +%u) invalid for parent with %u slices (view type %lu)",
                            desc->baseSlice, sliceCount, parentSlices, (unsigned long)type);
            return MCMTL_E_INVALID_ARGUMENT;
        }
        id<MTLTexture> view = [parent newTextureViewWithPixelFormat:format
                                                       textureType:type
                                                            levels:NSMakeRange(desc->baseLevel, desc->levelCount)
                                                            slices:NSMakeRange(desc->baseSlice, sliceCount)];
        if (!view) {
            mcmtl_set_error("newTextureViewWithPixelFormat: returned nil");
            return MCMTL_E_OUT_OF_MEMORY;
        }
        if (parent.label.length) view.label = parent.label;
        out->resourceId = view.gpuResourceID._impl;
        out->handle = mcmtl_handle_retain(view);
        return MCMTL_OK;
    MCMTL_TRY_END(MCMTL_E_OBJC_EXCEPTION)
}

MCMTL_API int32_t mcmtl_texel_view_create(mcmtl_handle device, const mcmtl_texel_view_desc *desc, mcmtl_object_result *out) {
    MCMTL_TRY_BEGIN
        mcmtl_clear_error();
        int32_t rc = mcmtl_object_begin(device, desc, sizeof *desc, out, "mcmtl_texel_view_create");
        if (rc != MCMTL_OK) return rc;
        MCMTLDevice *dev = MCMTL_DEVICE(device);
        if (!desc->buffer) {
            mcmtl_set_error("mcmtl_texel_view_create: buffer is 0");
            return MCMTL_E_INVALID_ARGUMENT;
        }
        const uint32_t format = desc->pixelFormat;
        if (format == MTLPixelFormatInvalid || mcmtl_core_format_is_depth_stencil(format)) {
            mcmtl_set_error("mcmtl_texel_view_create: pixelFormat %u cannot back a texture buffer", format);
            return MCMTL_E_UNSUPPORTED;
        }
        if (desc->bytesPerTexel == 0 || desc->length < desc->bytesPerTexel) {
            mcmtl_set_error("mcmtl_texel_view_create: length %llu / bytesPerTexel %u gives no texels",
                            (unsigned long long)desc->length, desc->bytesPerTexel);
            return MCMTL_E_INVALID_ARGUMENT;
        }
        id<MTLBuffer> buffer = MCMTL_BUFFER(desc->buffer);
        const uint64_t bufferLength = (uint64_t)buffer.length;
        if (desc->offset > bufferLength || desc->length > bufferLength - desc->offset) {
            mcmtl_set_error("mcmtl_texel_view_create: range [%llu, +%llu) outside the %llu-byte buffer",
                            (unsigned long long)desc->offset, (unsigned long long)desc->length, (unsigned long long)bufferLength);
            return MCMTL_E_INVALID_ARGUMENT;
        }
        NSUInteger alignment = [dev->device minimumTextureBufferAlignmentForPixelFormat:(MTLPixelFormat)format];
        if (alignment == 0) alignment = 1;
        if (desc->offset % alignment != 0) {
            mcmtl_set_error("mcmtl_texel_view_create: offset %llu is not a multiple of %lu", (unsigned long long)desc->offset,
                            (unsigned long)alignment);
            return MCMTL_E_INVALID_ARGUMENT;
        }
        const uint64_t width = desc->length / desc->bytesPerTexel;
        /* bytesPerRow = alignUp(length, alignment) (spec 3.7): buffers created with USAGE_UNIFORM_TEXEL_BUFFER
         * are rounded up so it fits; for any other buffer fall back to the exact row size. */
        uint64_t bytesPerRow = mcmtl_core_align_up(desc->length, alignment);
        if (bytesPerRow > bufferLength - desc->offset) bytesPerRow = width * desc->bytesPerTexel;
        MTLTextureDescriptor *td = [MTLTextureDescriptor textureBufferDescriptorWithPixelFormat:(MTLPixelFormat)format
                                                                                         width:(NSUInteger)width
                                                                               resourceOptions:buffer.resourceOptions
                                                                                         usage:MTLTextureUsageShaderRead];
        id<MTLTexture> view = [buffer newTextureWithDescriptor:td offset:(NSUInteger)desc->offset bytesPerRow:(NSUInteger)bytesPerRow];
        if (!view) {
            mcmtl_set_error("newTextureWithDescriptor:offset:bytesPerRow: returned nil (format %u, width %llu)", format,
                            (unsigned long long)width);
            return MCMTL_E_OUT_OF_MEMORY;
        }
        if (buffer.label.length) view.label = buffer.label;
        out->resourceId = view.gpuResourceID._impl;
        out->handle = mcmtl_handle_retain(view);
        return MCMTL_OK;
    MCMTL_TRY_END(MCMTL_E_OBJC_EXCEPTION)
}

/* ---------------------------------------------------------------------------------------------- */
/* Samplers (deduplicated per device)                                                              */
/* ---------------------------------------------------------------------------------------------- */

MCMTL_API int32_t mcmtl_sampler_create(mcmtl_handle device, const mcmtl_sampler_desc *desc, mcmtl_object_result *out) {
    MCMTL_TRY_BEGIN
        mcmtl_clear_error();
        int32_t rc = mcmtl_object_begin(device, desc, sizeof *desc, out, "mcmtl_sampler_create");
        if (rc != MCMTL_OK) return rc;
        MCMTLDevice *dev = MCMTL_DEVICE(device);

        if (desc->sAddressMode > MTLSamplerAddressModeClampToBorderColor || desc->tAddressMode > MTLSamplerAddressModeClampToBorderColor ||
            desc->rAddressMode > MTLSamplerAddressModeClampToBorderColor) {
            mcmtl_set_error("mcmtl_sampler_create: invalid address mode (%u, %u, %u)", desc->sAddressMode, desc->tAddressMode,
                            desc->rAddressMode);
            return MCMTL_E_INVALID_ARGUMENT;
        }
        if (desc->minFilter > MTLSamplerMinMagFilterLinear || desc->magFilter > MTLSamplerMinMagFilterLinear ||
            desc->mipFilter > MTLSamplerMipFilterLinear) {
            mcmtl_set_error("mcmtl_sampler_create: invalid filter (min %u, mag %u, mip %u)", desc->minFilter, desc->magFilter,
                            desc->mipFilter);
            return MCMTL_E_INVALID_ARGUMENT;
        }
        if (desc->maxAnisotropy < 1 || desc->maxAnisotropy > 16) {
            mcmtl_set_error("mcmtl_sampler_create: maxAnisotropy %u outside 1..16", desc->maxAnisotropy);
            return MCMTL_E_INVALID_ARGUMENT;
        }
        if (!(desc->lodMinClamp >= 0.0f) || !(desc->lodMaxClamp >= desc->lodMinClamp)) {   /* also rejects NaN */
            mcmtl_set_error("mcmtl_sampler_create: invalid LOD clamp [%g, %g]", (double)desc->lodMinClamp, (double)desc->lodMaxClamp);
            return MCMTL_E_INVALID_ARGUMENT;
        }
        if (desc->compareFunction > MTLCompareFunctionAlways) {
            mcmtl_set_error("mcmtl_sampler_create: invalid compareFunction %u", desc->compareFunction);
            return MCMTL_E_INVALID_ARGUMENT;
        }
        if (desc->flags & ~MCMTL_SAMPLER_FLAG_SUPPORT_ARGUMENT_BUFFERS) {
            mcmtl_set_error("mcmtl_sampler_create: unknown flags 0x%x", desc->flags & ~MCMTL_SAMPLER_FLAG_SUPPORT_ARGUMENT_BUFFERS);
            return MCMTL_E_INVALID_ARGUMENT;
        }

        MCMTLCoreState *core = mcmtl_core_state(dev);
        /* Key: every field after structSize (the struct has no padding). */
        NSData *key = [NSData dataWithBytes:(const uint8_t *)desc + sizeof(uint32_t)
                                     length:sizeof(mcmtl_sampler_desc) - sizeof(uint32_t)];
        os_unfair_lock_lock(&core->lock);
        id<MTLSamplerState> sampler = core->samplers[key];
        os_unfair_lock_unlock(&core->lock);
        if (!sampler) {
            MTLSamplerDescriptor *sd = [MTLSamplerDescriptor new];
            sd.sAddressMode = (MTLSamplerAddressMode)desc->sAddressMode;
            sd.tAddressMode = (MTLSamplerAddressMode)desc->tAddressMode;
            sd.rAddressMode = (MTLSamplerAddressMode)desc->rAddressMode;
            sd.minFilter = (MTLSamplerMinMagFilter)desc->minFilter;
            sd.magFilter = (MTLSamplerMinMagFilter)desc->magFilter;
            sd.mipFilter = (MTLSamplerMipFilter)desc->mipFilter;
            sd.maxAnisotropy = desc->maxAnisotropy;
            sd.lodMinClamp = desc->lodMinClamp;
            sd.lodMaxClamp = desc->lodMaxClamp;
            sd.compareFunction = (MTLCompareFunction)desc->compareFunction;
            sd.normalizedCoordinates = YES;
            /* Terrain ICB (CONTRACT.md 16.5): ICB pipelines read samplers through an argument buffer. The cache key is the
             * descriptor, so with the toggle on every sampler is argument-buffer capable. */
            sd.supportArgumentBuffers = (desc->flags & MCMTL_SAMPLER_FLAG_SUPPORT_ARGUMENT_BUFFERS) != 0 || mcmtl_terrain_icb_env();
            id<MTLSamplerState> created = [dev->device newSamplerStateWithDescriptor:sd];
            if (!created) {
                mcmtl_set_error("newSamplerStateWithDescriptor: returned nil");
                return MCMTL_E_OUT_OF_MEMORY;
            }
            os_unfair_lock_lock(&core->lock);
            sampler = core->samplers[key];   /* another thread may have won the race */
            if (!sampler) {
                sampler = created;
                if (core->samplers.count < MCMTL_CORE_MAX_CACHED_SAMPLERS) core->samplers[key] = created;
            }
            os_unfair_lock_unlock(&core->lock);
        }
        /* gpuResourceID is only defined for argument-buffer capable samplers. */
        if (desc->flags & MCMTL_SAMPLER_FLAG_SUPPORT_ARGUMENT_BUFFERS) out->resourceId = sampler.gpuResourceID._impl;
        out->handle = mcmtl_handle_retain(sampler);
        return MCMTL_OK;
    MCMTL_TRY_END(MCMTL_E_OBJC_EXCEPTION)
}

/* ---------------------------------------------------------------------------------------------- */
/* Timestamp query pools                                                                            */
/* ---------------------------------------------------------------------------------------------- */

MCMTL_API int32_t mcmtl_query_pool_create(mcmtl_handle device, uint32_t count, mcmtl_handle *outPool) {
    MCMTL_TRY_BEGIN
        mcmtl_clear_error();
        if (outPool) *outPool = 0;
        if (!device || !outPool) {
            mcmtl_set_error("mcmtl_query_pool_create: NULL argument");
            return MCMTL_E_INVALID_ARGUMENT;
        }
        if (count == 0) {
            mcmtl_set_error("mcmtl_query_pool_create: count is 0");
            return MCMTL_E_INVALID_ARGUMENT;
        }
        MCMTLDevice *dev = MCMTL_DEVICE(device);
        MCMTLQueryPool *pool = [MCMTLQueryPool new];
        pool->owner = dev;
        pool->count = count;
        if (dev->timestampCounterSet) {
            if (count > MCMTL_CORE_MAX_TIMESTAMP_SAMPLES) {
                mcmtl_device_message(dev, "timestamp query pool of %u samples exceeds the %u-sample counter buffer limit; "
                                     "its samples will read as unavailable", count, MCMTL_CORE_MAX_TIMESTAMP_SAMPLES);
            } else {
                MTLCounterSampleBufferDescriptor *cd = [MTLCounterSampleBufferDescriptor new];
                cd.counterSet = dev->timestampCounterSet;
                cd.storageMode = MTLStorageModeShared;
                cd.sampleCount = count;
                cd.label = @"mcmetal timestamps";
                NSError *error = nil;
                pool->sampleBuffer = [dev->device newCounterSampleBufferWithDescriptor:cd error:&error];
                if (!pool->sampleBuffer) {
                    mcmtl_device_message(dev, "newCounterSampleBufferWithDescriptor (%u samples) failed: %s; timestamps unavailable",
                                         count, error ? error.localizedDescription.UTF8String : "?");
                }
            }
        }
        *outPool = mcmtl_handle_retain(pool);
        return MCMTL_OK;
    MCMTL_TRY_END(MCMTL_E_OBJC_EXCEPTION)
}

MCMTL_API int32_t mcmtl_query_pool_resolve(mcmtl_handle device, mcmtl_handle pool, uint32_t first, uint32_t count, uint64_t *outValuesNs) {
    MCMTL_TRY_BEGIN
        mcmtl_clear_error();
        if (!device || !pool || (count > 0 && !outValuesNs)) {
            mcmtl_set_error("mcmtl_query_pool_resolve: NULL argument");
            return MCMTL_E_INVALID_ARGUMENT;
        }
        MCMTLQueryPool *p = MCMTL_QUERY_POOL(pool);
        if (first > p->count || count > p->count - first) {
            mcmtl_set_error("mcmtl_query_pool_resolve: range [%u, +%u) outside the pool's %u samples", first, count, p->count);
            return MCMTL_E_INVALID_ARGUMENT;
        }
        for (uint32_t i = 0; i < count; i++) outValuesNs[i] = MCMTL_TIMESTAMP_UNAVAILABLE;
        if (count == 0 || !p->sampleBuffer) return MCMTL_OK;
        NSData *data = [p->sampleBuffer resolveCounterRange:NSMakeRange(first, count)];
        if (!data) return MCMTL_OK;
        const MTLCounterResultTimestamp *samples = (const MTLCounterResultTimestamp *)data.bytes;
        NSUInteger available = data.length / sizeof(MTLCounterResultTimestamp);
        MCMTLCoreState *core = mcmtl_core_state(MCMTL_DEVICE(device));
        for (uint32_t i = 0; i < count && i < available; i++) {
            uint64_t value = samples[i].timestamp;
            /* Never-written samples resolve to 0 on Apple GPUs; MTLCounterErrorValue marks failed samples. */
            if (value == 0 || value == MTLCounterErrorValue) continue;
            outValuesNs[i] = mcmtl_core_timestamp_ns(core, value);
        }
        return MCMTL_OK;
    MCMTL_TRY_END(MCMTL_E_OBJC_EXCEPTION)
}

/* ---------------------------------------------------------------------------------------------- */
/* Release (M1: plain CFRelease; M3 adds residency-set removal)                                     */
/* ---------------------------------------------------------------------------------------------- */

MCMTL_API int32_t mcmtl_release(mcmtl_handle device, mcmtl_handle object) {
    MCMTL_TRY_BEGIN
        (void)device;
        mcmtl_handle_release(object);
        return MCMTL_OK;
    MCMTL_TRY_END(MCMTL_E_OBJC_EXCEPTION)
}

MCMTL_API int32_t mcmtl_release_batch(mcmtl_handle device, const mcmtl_handle *objects, uint32_t count) {
    MCMTL_TRY_BEGIN
        (void)device;
        if (!objects && count > 0) {
            mcmtl_set_error("mcmtl_release_batch: objects is NULL");
            return MCMTL_E_INVALID_ARGUMENT;
        }
        for (uint32_t i = 0; i < count; i++) {
            mcmtl_handle_release(objects[i]);
        }
        return MCMTL_OK;
    MCMTL_TRY_END(MCMTL_E_OBJC_EXCEPTION)
}
