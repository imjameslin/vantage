#import "mcmetal_farmaterial.h"
#include <math.h>
#include <stdio.h>
#include <string.h>

_Static_assert(sizeof(mcmtl_far_material_source) == 48, "FFM source layout");
_Static_assert(sizeof(mcmtl_far_material_desc) == 40, "FFM descriptor layout");
_Static_assert(sizeof(mcmtl_far_material_binding) == 64, "FFM binding layout");
_Static_assert(sizeof(mcmtl_far_material_copy_job) == 32, "FFM job layout");
_Static_assert(sizeof(mcmtl_far_material_copy_dependency) == 24, "FFM dependency layout");

static BOOL fail(NSError **error, int32_t code, NSString *message) {
    if (error) *error = [NSError errorWithDomain:@"MCMTLFarMaterial" code:code
        userInfo:@{NSLocalizedDescriptionKey: message}];
    return NO;
}
static mcmtl_handle borrowed(id object) { return (mcmtl_handle)(uintptr_t)(__bridge void *)object; }
static NSUInteger mipExtent(NSUInteger size, NSUInteger level) { return MAX((NSUInteger)1, size >> level); }
static BOOL identitySwizzle(id<MTLTexture> t) {
    MTLTextureSwizzleChannels s = t.swizzle;
    return s.red == MTLTextureSwizzleRed && s.green == MTLTextureSwizzleGreen &&
           s.blue == MTLTextureSwizzleBlue && s.alpha == MTLTextureSwizzleAlpha;
}
static BOOL supportedTexture(id<MTLTexture> t, id<MTLDevice> device) {
    return t && t.device == device && t.textureType == MTLTextureType2D &&
        t.pixelFormat == MTLPixelFormatRGBA8Unorm && t.sampleCount == 1 && t.depth == 1 && t.arrayLength == 1 &&
        t.width && t.width <= 16384 && t.height && t.height <= 16384 &&
        (t.storageMode == MTLStorageModePrivate || t.storageMode == MTLStorageModeShared ||
         t.storageMode == MTLStorageModeManaged) && t.hazardTrackingMode == MTLHazardTrackingModeTracked &&
        (t.usage & MTLTextureUsageShaderRead) && !t.heap && !t.isAliasable && !t.buffer &&
        !t.iosurface && !t.shareable && !t.framebufferOnly && !t.remoteStorageTexture &&
        [t respondsToSelector:@selector(isSparse)] && !t.isSparse && identitySwizzle(t);
}

@implementation MCMTLFarMaterialRecipe {
    id<MTLTexture> _parents[MCMTLFarMaterialCount], _textures[MCMTLFarMaterialCount];
    id<MTLSamplerState> _samplers[MCMTLFarMaterialCount];
    mcmtl_far_material_desc _descs[MCMTLFarMaterialCount];
}
+ (BOOL)accessInstanceVariablesDirectly { return NO; }
- (instancetype)initWithDevice:(id<MTLDevice>)device sources:(const mcmtl_far_material_source *)sources
    budgetBytes:(uint64_t)budgetBytes error:(NSError **)error {
    if (error) *error = nil;
    if (!(self = [super init])) return nil;
    if (!device || !sources || !budgetBytes) {
        fail(error, MCMTL_E_INVALID_ARGUMENT, @"Missing material device, sources or byte budget"); return nil;
    }
    _device = device;
    for (NSUInteger i = 0; i < MCMTLFarMaterialCount; ++i) {
        mcmtl_far_material_source s = sources[i];
        id<MTLTexture> p = MCMTL_TEXTURE(s.parent), t = MCMTL_TEXTURE(s.texture);
        id<MTLSamplerState> sampler = MCMTL_SAMPLER(s.sampler);
        if (!supportedTexture(p, device) || p.parentTexture || !supportedTexture(t, device) ||
            !sampler || sampler.device != device) {
            fail(error, MCMTL_E_UNSUPPORTED, @"Material requires device-owned tracked RGBA8 2D parents/views and same-device samplers");
            return nil;
        }
        NSUInteger maxLevels = 1;
        for (NSUInteger n = MAX(p.width, p.height); n > 1; n >>= 1) ++maxLevels;
        NSUInteger levels = p.mipmapLevelCount;
        // Subtraction checks precede shifts/range construction; no invalid Metal descriptor is issued.
        if (!levels || levels > maxLevels || s.reserved || s.samplerLODClamp > 1 ||
            !s.levelCount || s.baseLevel >= levels || s.levelCount > levels - s.baseLevel ||
            (s.samplerLODClamp && (!isfinite(s.lodMin) || !isfinite(s.lodMax) ||
                                  s.lodMin < 0 || s.lodMax < s.lodMin))) {
            fail(error, MCMTL_E_INVALID_ARGUMENT, @"Invalid material mip range, LOD clamp or reserved fields"); return nil;
        }
        if (t.pixelFormat != p.pixelFormat || t.mipmapLevelCount != s.levelCount ||
            t.width != mipExtent(p.width, s.baseLevel) || t.height != mipExtent(p.height, s.baseLevel) ||
            (t == p ? (s.baseLevel != 0 || s.levelCount != levels) :
             (t.parentTexture != p || t.parentRelativeLevel != s.baseLevel || t.parentRelativeSlice != 0))) {
            fail(error, MCMTL_E_INVALID_ARGUMENT, @"Sampling handle does not match the exact direct parent view range"); return nil;
        }
        uint64_t bytes = 0;
        for (NSUInteger level = 0; level < levels; ++level)
            bytes += (uint64_t)mipExtent(p.width, level) * mipExtent(p.height, level) * 4;
        if (bytes > budgetBytes - _copyBytes) {
            fail(error, MCMTL_E_OUT_OF_MEMORY, @"Material mip copies exceed the logical byte budget"); return nil;
        }
        _copyBytes += bytes;
        _parents[i] = p; _textures[i] = t; _samplers[i] = sampler;
        _descs[i] = (mcmtl_far_material_desc){.width=(uint32_t)p.width, .height=(uint32_t)p.height,
            .pixelFormat=(uint32_t)p.pixelFormat, .mipLevels=(uint32_t)levels,
            .baseLevel=s.baseLevel, .levelCount=s.levelCount, .samplerLODClamp=s.samplerLODClamp,
            .lodMin=s.lodMin, .lodMax=s.lodMax};
    }
    return self;
}
- (mcmtl_far_material_desc)descriptorAtIndex:(NSUInteger)i {
    return i < MCMTLFarMaterialCount ? _descs[i] : (mcmtl_far_material_desc){0};
}
- (id<MTLTexture>)sourceParentAtIndex:(NSUInteger)i { return i < MCMTLFarMaterialCount ? _parents[i] : nil; }
- (id<MTLTexture>)sourceTextureAtIndex:(NSUInteger)i { return i < MCMTLFarMaterialCount ? _textures[i] : nil; }
- (id<MTLSamplerState>)samplerAtIndex:(NSUInteger)i { return i < MCMTLFarMaterialCount ? _samplers[i] : nil; }
@end

typedef NS_ENUM(uint8_t, MaterialState) { MaterialPrepared, MaterialPublished, MaterialFailed };
@implementation MCMTLFarMaterialVersion {
    MCMTLFarMaterialRecipe *_recipe;
    id<MTLDevice> _device;
    id<MTLTexture> _parents[MCMTLFarMaterialCount], _textures[MCMTLFarMaterialCount];
    id<MTLSamplerState> _samplers[MCMTLFarMaterialCount];
    mcmtl_far_material_desc _descs[MCMTLFarMaterialCount];
    MaterialState _state;
}
+ (BOOL)accessInstanceVariablesDirectly { return NO; }
- (instancetype)initWithRecipe:(MCMTLFarMaterialRecipe *)recipe error:(NSError **)error {
    if (error) *error = nil;
    if (!(self = [super init])) return nil;
    if (!recipe) { fail(error, MCMTL_E_INVALID_ARGUMENT, @"Missing pinned material recipe"); return nil; }
    _recipe = recipe; _device = recipe.device;
    for (NSUInteger i = 0; i < MCMTLFarMaterialCount; ++i) {
        mcmtl_far_material_desc d = [recipe descriptorAtIndex:i];
        _descs[i] = d; _samplers[i] = [recipe samplerAtIndex:i];
        MTLTextureDescriptor *td = [MTLTextureDescriptor new];
        td.textureType = MTLTextureType2D; td.pixelFormat = (MTLPixelFormat)d.pixelFormat;
        td.width = d.width; td.height = d.height; td.depth = td.arrayLength = td.sampleCount = 1;
        td.mipmapLevelCount = d.mipLevels;
        td.storageMode = MTLStorageModePrivate; td.hazardTrackingMode = MTLHazardTrackingModeTracked;
        td.usage = MTLTextureUsageShaderRead;
        _parents[i] = [_device newTextureWithDescriptor:td];
        if (!_parents[i]) { fail(error, MCMTL_E_OUT_OF_MEMORY, @"Private material allocation failed"); return nil; }
        if (d.baseLevel == 0 && d.levelCount == d.mipLevels) _textures[i] = _parents[i];
        else _textures[i] = [_parents[i] newTextureViewWithPixelFormat:td.pixelFormat textureType:MTLTextureType2D
            levels:NSMakeRange(d.baseLevel, d.levelCount) slices:NSMakeRange(0, 1)];
        if (!_textures[i]) { fail(error, MCMTL_E_OUT_OF_MEMORY, @"Private material view allocation failed"); return nil; }
        _copyJobCount += d.mipLevels;
    }
    return self;
}
- (BOOL)isPublished { return _state == MaterialPublished; }
- (mcmtl_far_material_binding)bindingAtIndex:(NSUInteger)i {
    if (i >= MCMTLFarMaterialCount || _state == MaterialFailed) return (mcmtl_far_material_binding){0};
    return (mcmtl_far_material_binding){borrowed(_parents[i]), borrowed(_textures[i]), borrowed(_samplers[i]), _descs[i]};
}
- (BOOL)copyJobAtIndex:(NSUInteger)i into:(mcmtl_far_material_copy_job *)job {
    if (job) memset(job, 0, sizeof *job);
    if (!job || _state != MaterialPrepared || i >= _copyJobCount) return NO;
    NSUInteger material = i < _descs[0].mipLevels ? 0 : 1;
    NSUInteger level = material ? i - _descs[0].mipLevels : i;
    mcmtl_far_material_desc d = _descs[material];
    *job = (mcmtl_far_material_copy_job){borrowed([_recipe sourceParentAtIndex:material]), borrowed(_parents[material]),
        (uint32_t)material, (uint32_t)level, (uint32_t)mipExtent(d.width, level), (uint32_t)mipExtent(d.height, level)};
    return YES;
}
- (BOOL)publishCommittedCopies:(mcmtl_far_material_copy_dependency)dependency error:(NSError **)error {
    if (error) *error = nil;
    if (_state != MaterialPrepared)
        return fail(error, MCMTL_E_INVALID_ARGUMENT, @"Material publication was already attempted");
    // Consume the attempt before inspecting the dependency: invalid/exception paths stay closed.
    _state = MaterialFailed; _recipe = nil;
    id<MTLSharedEvent> event = (id<MTLSharedEvent>)MCMTL_OBJ(dependency.event);
    if (dependency.committed != 1 || dependency.copyCount != _copyJobCount || !dependency.value ||
        !event || (event.device && event.device != _device))
        return fail(error, MCMTL_E_INVALID_ARGUMENT, @"Missing committed-copy contract or mismatched event/value/job count");
    // MTLEvent.h: MTLSharedEvent.device is nil for events shared across devices.
    // The coordinator additionally matches this EXACT event to the captured main
    // geometry event and the committed Java ticket, rather than inferring provenance here.
    _readyEvent = event; _readyValue = dependency.value; _state = MaterialPublished;
    return YES;
}
- (BOOL)ownsImmutableFarTexture:(id<MTLTexture>)texture {
    if (!texture || _state != MaterialPublished) return NO;
    // Identity whitelist only: never follow arbitrary aliases or accept retained sources.
    for (NSUInteger i = 0; i < MCMTLFarMaterialCount; ++i)
        if (texture == _parents[i] || texture == _textures[i]) return YES;
    return NO;
}
@end

static int32_t report(char *out, uint32_t cap, NSError *error) {
    if (out && cap) snprintf(out, cap, "%s", error.localizedDescription.UTF8String ?: "Material operation failed");
    return error ? (int32_t)error.code : MCMTL_E_UNSUPPORTED;
}
static int32_t exceptionError(char *out, uint32_t cap, NSException *exception) {
    if (out && cap) snprintf(out, cap, "%s", exception.reason.UTF8String ?: "Material exception");
    return MCMTL_E_OBJC_EXCEPTION;
}
MCMTL_API int32_t mcmtl_ext_far_material_pin(mcmtl_handle device, const mcmtl_far_material_source *sources,
    uint64_t budget, mcmtl_handle *out, char *message, uint32_t capacity) {
    if (out) *out = 0;
    if (message && capacity) message[0] = 0;
    if (!device || !sources || !out || (capacity && !message)) return MCMTL_E_INVALID_ARGUMENT;
    @autoreleasepool { @try {
        NSError *error = nil;
        MCMTLFarMaterialRecipe *recipe = [[MCMTLFarMaterialRecipe alloc] initWithDevice:MCMTL_DEVICE(device)->device
            sources:sources budgetBytes:budget error:&error];
        if (!recipe) return report(message, capacity, error);
        *out = mcmtl_handle_retain(recipe); return MCMTL_OK;
    } @catch (NSException *e) { return exceptionError(message, capacity, e); } }
}
MCMTL_API int32_t mcmtl_ext_far_material_prepare(mcmtl_handle handle, mcmtl_handle *out,
    char *message, uint32_t capacity) {
    if (out) *out = 0;
    if (message && capacity) message[0] = 0;
    if (!handle || !out || (capacity && !message)) return MCMTL_E_INVALID_ARGUMENT;
    @autoreleasepool { @try {
        NSError *error = nil;
        MCMTLFarMaterialVersion *version = [[MCMTLFarMaterialVersion alloc]
            initWithRecipe:(MCMTLFarMaterialRecipe *)MCMTL_OBJ(handle) error:&error];
        if (!version) return report(message, capacity, error);
        *out = mcmtl_handle_retain(version); return MCMTL_OK;
    } @catch (NSException *e) { return exceptionError(message, capacity, e); } }
}
MCMTL_API int32_t mcmtl_ext_far_material_binding(mcmtl_handle handle, uint32_t index, mcmtl_far_material_binding *out) {
    if (out) memset(out, 0, sizeof *out);
    if (!handle || !out || index >= MCMTLFarMaterialCount) return MCMTL_E_INVALID_ARGUMENT;
    *out = [(MCMTLFarMaterialVersion *)MCMTL_OBJ(handle) bindingAtIndex:index];
    return out->parent ? MCMTL_OK : MCMTL_E_INVALID_ARGUMENT;
}
MCMTL_API uint32_t mcmtl_ext_far_material_copy_count(mcmtl_handle handle) {
    return handle ? (uint32_t)[(MCMTLFarMaterialVersion *)MCMTL_OBJ(handle) copyJobCount] : 0;
}
MCMTL_API int32_t mcmtl_ext_far_material_copy_job(mcmtl_handle handle, uint32_t index, mcmtl_far_material_copy_job *out) {
    if (out) memset(out, 0, sizeof *out);
    return handle && [(MCMTLFarMaterialVersion *)MCMTL_OBJ(handle) copyJobAtIndex:index into:out]
        ? MCMTL_OK : MCMTL_E_INVALID_ARGUMENT;
}
MCMTL_API int32_t mcmtl_ext_far_material_retain_parent(mcmtl_handle handle, uint32_t index, mcmtl_handle *out) {
    if (out) *out = 0;
    mcmtl_far_material_binding b = {0};
    if (!out || mcmtl_ext_far_material_binding(handle, index, &b) != MCMTL_OK) return MCMTL_E_INVALID_ARGUMENT;
    *out = mcmtl_handle_retain(MCMTL_OBJ(b.parent)); return MCMTL_OK;
}
MCMTL_API int32_t mcmtl_ext_far_material_publish(mcmtl_handle handle,
    const mcmtl_far_material_copy_dependency *dependency, char *message, uint32_t capacity) {
    if (message && capacity) message[0] = 0;
    if (!handle || (capacity && !message)) return MCMTL_E_INVALID_ARGUMENT;
    @autoreleasepool { @try {
        NSError *error = nil;
        BOOL ok = [(MCMTLFarMaterialVersion *)MCMTL_OBJ(handle)
            publishCommittedCopies:dependency ? *dependency : (mcmtl_far_material_copy_dependency){0} error:&error];
        return ok ? MCMTL_OK : report(message, capacity, error);
    } @catch (NSException *e) { return exceptionError(message, capacity, e); } }
}
MCMTL_API void mcmtl_ext_far_material_drop(mcmtl_handle object) { mcmtl_handle_release(object); }
