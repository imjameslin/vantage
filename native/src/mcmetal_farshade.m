#import "mcmetal_farshade.h"
#import "mcmetal_farshade_source.h"
#include <math.h>
#include <string.h>

typedef struct {
    uint32_t directoryCount, tailWordCount, sectionCount, arenaCount;
    uint32_t width, height, layer, flags;
} ShadeParams;
_Static_assert(sizeof(ShadeParams)==32, "Far shade MSL params");
_Static_assert(sizeof(MCMTLFarShadeUniforms)==240, "Exact original UBO byte layouts");
_Static_assert(MCMTLFarShadeDiagnosticBytes==MCMTLFarShadeDiagnosticSampleOffsetBytes &&
    MCMTLFarShadeDiagnosticSampleWords*4==MCMTLFarShadeDiagnosticSampleBytes &&
    MCMTLFarShadeDiagnosticSampleOffsetBytes+MCMTLFarShadeDiagnosticSampleBytes==MCMTLFarShadeDiagnosticFullBytes,
    "Far shade diagnostic sample ABI");

static BOOL fail(NSError **error, NSString *message) {
    if (error) *error=[NSError errorWithDomain:@"mcmetal.far.shade" code:1
        userInfo:@{NSLocalizedDescriptionKey:message ?: @"Far shade failure"}];
    return NO;
}

@interface MCMTLFarShadeGeometry () {
@public
    id<MTLBuffer> arguments, arenas;
    id<MTLDevice> device;
}
- (instancetype)initWithLookup:(MCMTLFarFaceLookup *)lookup;
@end
@implementation MCMTLFarShadeGeometry
- (instancetype)initWithLookup:(MCMTLFarFaceLookup *)lookup {
    if ((self=[super init])) _lookup=lookup;
    return self;
}
- (uint64_t)ownedBufferBytes { return arguments.length+arenas.length; }
@end

@implementation MCMTLFarShade {
    id<MTLRenderPipelineState> _pipeline, _diagnosticPipeline;
    id<MTLFunction> _fragment;
}
- (instancetype)initWithBlockProgram:(MCMTLFarShader *)block
                     compactProgram:(MCMTLFarShader *)compact error:(NSError **)error {
    if (error) *error=nil;
    if (!block || !compact || block.layout!=MCMTLFarShaderBlock || compact.layout!=MCMTLFarShaderCompact ||
        block.layer!=compact.layer || block.pipelineState.device!=compact.pipelineState.device ||
        ![block.originalFragment isEqualToString:compact.originalFragment] ||
        ![block.originalFragmentEntry isEqualToString:compact.originalFragmentEntry]) {
        fail(error,@"Far shade needs matching admitted BLOCK and compact programs"); return nil;
    }
    MCMTLFarShaderRaster br=block.raster, cr=compact.raster;
    if (br.depthBiasConstant!=0 || br.depthBiasSlopeScale!=0 || br.depthBiasClamp!=0 ||
        cr.depthBiasConstant!=0 || cr.depthBiasSlopeScale!=0 || cr.depthBiasClamp!=0) {
        fail(error,@"Far shade does not reconstruct biased hardware depth"); return nil;
    }
    MTLCompileOptions *a=[block copyOriginalCompileOptions],*b=[compact copyOriginalCompileOptions];
    if (!a || !b || a.languageVersion!=b.languageVersion || a.mathMode!=b.mathMode ||
        a.preserveInvariance!=b.preserveInvariance || a.preprocessorMacros.count || b.preprocessorMacros.count) {
        fail(error,@"Far shade producer compiler settings differ"); return nil;
    }
    return [self initWithDevice:block.pipelineState.device blockVertex:block.originalVertex
        blockEntry:block.originalVertexEntry compactVertex:compact.originalVertex compactEntry:compact.originalVertexEntry
        fragment:block.originalFragment fragmentEntry:block.originalFragmentEntry layer:block.layer options:a error:error];
}
- (instancetype)initWithDevice:(id<MTLDevice>)device
                    blockVertex:(NSString *)blockVertex blockEntry:(NSString *)blockEntry
                  compactVertex:(NSString *)compactVertex compactEntry:(NSString *)compactEntry
                       fragment:(NSString *)fragment fragmentEntry:(NSString *)fragmentEntry
                          layer:(MCMTLFarShaderLayer)layer options:(MTLCompileOptions *)options
                          error:(NSError **)error {
    if (error) *error=nil;
    if (!(self=[super init])) return nil;
    @try {
        if ([NSThread isMainThread] || !device || device.argumentBuffersSupport!=MTLArgumentBuffersTier2) {
            fail(error,@"Far shade construction requires worker and tier-2 argument buffers"); return nil;
        }
        if (!options || options.preprocessorMacros.count) {
            fail(error,@"Far shade requires original compile options without macro specialization"); return nil;
        }
        NSString *source=mcmtl_farshade_source(blockVertex,blockEntry,compactVertex,compactEntry,
            fragment,fragmentEntry,layer,error);
        if (!source) return nil;
        id<MTLLibrary> library=[device newLibraryWithSource:source options:[options copy] error:error];
        if (!library) return nil;
        id<MTLFunction> vertex=[library newFunctionWithName:@"farshade_vertex"];
        _fragment=[library newFunctionWithName:@"farshade_fragment"];
        if (!vertex || !_fragment) { fail(error,@"Missing far shade library entries"); return nil; }
        MTLRenderPipelineDescriptor *d=[MTLRenderPipelineDescriptor new];
        d.label=@"mcmetal current-material visibility shade";
        d.vertexFunction=vertex; d.fragmentFunction=_fragment;
        d.colorAttachments[0].pixelFormat=MTLPixelFormatRGBA8Unorm;
        d.colorAttachments[1].pixelFormat=MTLPixelFormatR32Float;
        d.colorAttachments[2].pixelFormat=MTLPixelFormatR8Uint;
        d.inputPrimitiveTopology=MTLPrimitiveTopologyClassTriangle;
        _pipeline=[device newRenderPipelineStateWithDescriptor:d error:error];
        if (!_pipeline) return nil;
        d.fragmentFunction=[library newFunctionWithName:@"farshade_fragment_diagnostics"];
        if (!d.fragmentFunction) { fail(error,@"Missing far shade diagnostic entry"); return nil; }
        d.label=@"mcmetal current-material shade diagnostics";
        _diagnosticPipeline=[device newRenderPipelineStateWithDescriptor:d error:error];
        if (!_diagnosticPipeline) return nil;
        _device=device; _layer=layer;
        return self;
    } @catch (NSException *ex) { fail(error,ex.reason); return nil; }
}

- (MCMTLFarShadeGeometry *)prepareLookup:(MCMTLFarFaceLookup *)lookup
                            budgetBytes:(uint64_t)budgetBytes error:(NSError **)error {
    if (error) *error=nil;
    @try {
        if ([NSThread isMainThread] || !lookup || lookup.shaderSections.device!=_device ||
            lookup.directoryBuffer.device!=_device || lookup.tailBuffer.device!=_device ||
            !lookup.arenaCount || lookup.arenaCount>64) {
            fail(error,@"Far shade lookup requires worker, same device and 1..64 arenas"); return nil;
        }
        id<MTLArgumentEncoder> encoder=[_fragment newArgumentEncoderWithBufferIndex:7];
        const NSUInteger metadataBytes=64*16;
        if (!encoder || !encoder.encodedLength || encoder.encodedLength>budgetBytes ||
            metadataBytes>budgetBytes-encoder.encodedLength) {
            fail(error,@"Far shade argument budget exhausted"); return nil;
        }
        MCMTLFarShadeGeometry *g=[[MCMTLFarShadeGeometry alloc] initWithLookup:lookup];
        g->device=_device;
        g->arguments=[_device newBufferWithLength:encoder.encodedLength options:MTLResourceStorageModeShared];
        g->arenas=[_device newBufferWithLength:metadataBytes options:MTLResourceStorageModeShared];
        if (!g->arguments.contents || !g->arenas.contents) {
            fail(error,@"Far shade argument allocation failed"); return nil;
        }
        memset(g->arguments.contents,0,g->arguments.length); memset(g->arenas.contents,0,metadataBytes);
        [encoder setArgumentBuffer:g->arguments offset:0];
        uint32_t *descriptors=g->arenas.contents;
        for (unsigned i=0;i<64;++i) {
            if (i>=lookup.arenaCount) { [encoder setBuffer:nil offset:0 atIndex:i]; continue; }
            MCMTLFarFaceArena a=[lookup arenaAtIndex:i];
            uint64_t stride=a.layout==MCMTLFarFaceLayoutBlock?112:48;
            if (!a.vertices || a.vertices.device!=_device || a.vertexOffset>a.vertices.length ||
                a.vertexBytes>a.vertices.length-a.vertexOffset || a.vertexBytes/stride>UINT32_MAX ||
                (a.layout!=MCMTLFarFaceLayoutBlock && a.layout!=MCMTLFarFaceLayoutCompact)) {
                fail(error,@"Invalid leased far vertex arena"); return nil;
            }
            [encoder setBuffer:a.vertices offset:a.vertexOffset atIndex:i];
            descriptors[4*i]=a.faceBase; descriptors[4*i+1]=a.layout;
            descriptors[4*i+2]=(uint32_t)(a.vertexBytes/stride);
        }
        return g;
    } @catch (NSException *ex) { fail(error,ex.reason); return nil; }
}

static BOOL identitySwizzle(id<MTLTexture> t) {
    MTLTextureSwizzleChannels s=t.swizzle;
    return s.red==MTLTextureSwizzleRed && s.green==MTLTextureSwizzleGreen &&
        s.blue==MTLTextureSwizzleBlue && s.alpha==MTLTextureSwizzleAlpha;
}
static BOOL plane(id<MTLTexture> t,id<MTLDevice> device,MTLPixelFormat format,
                  NSUInteger width,NSUInteger height,MTLTextureUsage usage) {
    return t && t.device==device && t.pixelFormat==format && t.width==width && t.height==height &&
        width && height && width<=16384 && height<=16384 && t.textureType==MTLTextureType2D &&
        t.depth==1 && t.arrayLength==1 && t.mipmapLevelCount==1 && t.sampleCount==1 &&
        (t.usage&usage)==usage && t.hazardTrackingMode==MTLHazardTrackingModeTracked &&
        (t.storageMode==MTLStorageModePrivate || t.storageMode==MTLStorageModeShared || t.storageMode==MTLStorageModeManaged) &&
        !t.heap && !t.buffer && !t.parentTexture && !t.iosurface && !t.isAliasable && !t.framebufferOnly &&
        !t.shareable && !t.remoteStorageTexture && !t.isSparse && identitySwizzle(t);
}
static BOOL material(id<MTLTexture> t,id<MTLDevice> d,id<MTLSamplerState> s,BOOL clamp,float low,float high) {
    return t && s && s.device==d && t.device==d && t.pixelFormat==MTLPixelFormatRGBA8Unorm &&
        t.textureType==MTLTextureType2D && t.sampleCount==1 && t.arrayLength==1 && t.depth==1 &&
        t.width && t.height && (t.usage&MTLTextureUsageShaderRead) &&
        t.hazardTrackingMode==MTLHazardTrackingModeTracked && identitySwizzle(t) &&
        (!clamp || (isfinite(low) && isfinite(high) && low>=0 && high>=low));
}
static BOOL overlaps(id<MTLTexture> a,id<MTLTexture> b) {
    // Targets/ID planes are standalone, so only material views can name a parent.
    for (unsigned i=0;a && i<4;++i,a=a.parentTexture) if (a==b) return YES;
    return NO;
}
static BOOL finiteFloats(const uint8_t *bytes,unsigned count) {
    // Inspect bits; this admission must not depend on compiler fast-math flags.
    for (unsigned i=0;i<count;++i) {
        uint32_t bits; memcpy(&bits,bytes+4*i,4);
        if ((bits&0x7f800000u)==0x7f800000u) return NO;
    }
    return YES;
}
static BOOL finiteUniforms(const MCMTLFarShadeUniforms *u) {
    // Integer fields and unspecified padding are not floating-point values.
    // Globals: CameraBlockPos[3], GlintAlpha, CameraOffset[3], GameTime,
    // ScreenSize[2], MenuBlurRadius, UseRgss. Terrain ends with int TextureSize[2].
    int32_t textureSize[2]; memcpy(textureSize,u->terrain+64,sizeof textureSize);
    return finiteFloats(u->projection,16) && finiteFloats(u->globals+12,7) &&
        finiteFloats(u->fog,12) && finiteFloats(u->terrain,16) &&
        textureSize[0]>0 && textureSize[1]>0;
}
- (BOOL)encodeToCommandBuffer:(id<MTLCommandBuffer>)buffer
                      geometry:(MCMTLFarShadeGeometry *)g source:(MCMTLFarShadeSource)source
                       targets:(MCMTLFarShadeTargets)targets uniforms:(const MCMTLFarShadeUniforms *)uniforms
                     materials:(MCMTLFarShadeMaterials)m error:(NSError **)error {
    return [self encodeToCommandBuffer:buffer geometry:g source:source targets:targets
        uniforms:uniforms materials:m diagnostics:nil error:error];
}
- (BOOL)encodeToCommandBuffer:(id<MTLCommandBuffer>)buffer
                      geometry:(MCMTLFarShadeGeometry *)g source:(MCMTLFarShadeSource)source
                       targets:(MCMTLFarShadeTargets)targets uniforms:(const MCMTLFarShadeUniforms *)uniforms
                     materials:(MCMTLFarShadeMaterials)m diagnostics:(id<MTLBuffer>)diagnostics error:(NSError **)error {
    if (error) *error=nil;
    id<MTLRenderCommandEncoder> encoder=nil;
    @try {
        if (!buffer || buffer.device!=_device || buffer.status!=MTLCommandBufferStatusNotEnqueued ||
            !buffer.retainedReferences || !g || g->device!=_device || !uniforms)
            return fail(error,@"Far shade requires same-device unqueued buffer and prepared leased geometry");
        if (!finiteUniforms(uniforms)) return fail(error,@"Invalid far shade uniforms");
        NSUInteger width=source.ids.width,height=source.ids.height;
        __unsafe_unretained id<MTLTexture> textures[6]={source.ids,source.validity,source.depth,targets.color,targets.depth,targets.validity};
        const MTLPixelFormat formats[6]={MTLPixelFormatR32Uint,MTLPixelFormatR8Uint,MTLPixelFormatR32Float,MTLPixelFormatRGBA8Unorm,MTLPixelFormatR32Float,MTLPixelFormatR8Uint};
        for (unsigned i=0;i<6;++i) {
            if (!plane(textures[i],_device,formats[i],width,height,i<3?MTLTextureUsageShaderRead:MTLTextureUsageRenderTarget|MTLTextureUsageShaderRead))
                return fail(error,@"Unsupported far shade input/output plane");
            for (unsigned j=0;j<i;++j) if (textures[i]==textures[j]) return fail(error,@"Aliased far shade planes");
            if (overlaps(m.atlas,textures[i]) || overlaps(m.lightmap,textures[i]))
                return fail(error,@"Material aliases a far shade target or ID plane");
        }
        if (!material(m.atlas,_device,m.atlasSampler,m.atlasLODClamp,m.atlasLODMin,m.atlasLODMax) ||
            !material(m.lightmap,_device,m.lightSampler,m.lightLODClamp,m.lightLODMin,m.lightLODMax))
            return fail(error,@"Invalid current material texture/sampler");
        MCMTLFarFaceLookup *lookup=g.lookup;
        if (diagnostics) {
            if (diagnostics.device!=_device || diagnostics.length<MCMTLFarShadeDiagnosticBytes ||
                diagnostics.hazardTrackingMode!=MTLHazardTrackingModeTracked || diagnostics.heap || diagnostics.isAliasable ||
                (diagnostics.storageMode!=MTLStorageModeShared && diagnostics.storageMode!=MTLStorageModePrivate &&
                 diagnostics.storageMode!=MTLStorageModeManaged) ||
                diagnostics==g->arguments || diagnostics==g->arenas || diagnostics==lookup.directoryBuffer ||
                diagnostics==lookup.tailBuffer || diagnostics==lookup.shaderSections ||
                diagnostics==m.atlas.buffer || diagnostics==m.lightmap.buffer)
                return fail(error,@"Invalid or aliased far shade diagnostics buffer");
            for (unsigned i=0;i<lookup.arenaCount;++i)
                if (diagnostics==[lookup arenaAtIndex:i].vertices)
                    return fail(error,@"Diagnostics alias leased far geometry");
        }
        ShadeParams p={lookup.directoryCount,lookup.tailWordCount,lookup.sectionCount,lookup.arenaCount,
            (uint32_t)width,(uint32_t)height,_layer,source.exactCoverage?1u:0u};
        if (diagnostics.length>=MCMTLFarShadeDiagnosticFullBytes) p.flags|=MCMTLFarShadeDiagnosticSampleFlag;
        MTLRenderPassDescriptor *pass=[MTLRenderPassDescriptor renderPassDescriptor];
        for (unsigned i=0;i<3;++i) {
            pass.colorAttachments[i].texture=textures[i+3];
            pass.colorAttachments[i].loadAction=MTLLoadActionDontCare;
            pass.colorAttachments[i].storeAction=MTLStoreActionStore;
        }
        // A completion block also belongs to an uncommitted CB until it is dropped.
        // Capture the geometry owner, not just buffers (suballocation-range lease).
        [buffer addCompletedHandler:^(id<MTLCommandBuffer> done) { (void)done; (void)g.lookup; }];
        encoder=[buffer renderCommandEncoderWithDescriptor:pass];
        if (!encoder) return fail(error,@"Cannot create far shade encoder");
        encoder.label=@"mcmetal far current-material pixel shade";
        [encoder setRenderPipelineState:diagnostics?_diagnosticPipeline:_pipeline]; [encoder setCullMode:MTLCullModeNone];
        [encoder setViewport:(MTLViewport){0,0,width,height,0,1}];
        [encoder setScissorRect:(MTLScissorRect){0,0,width,height}];
        [encoder setFragmentBytes:uniforms->projection length:64 atIndex:0];
        [encoder setFragmentBytes:uniforms->globals length:48 atIndex:1];
        [encoder setFragmentBytes:uniforms->fog length:48 atIndex:2];
        [encoder setFragmentBytes:uniforms->terrain length:80 atIndex:5];
        [encoder setFragmentBuffer:g->arguments offset:0 atIndex:7];
        [encoder setFragmentBuffer:g->arenas offset:0 atIndex:8];
        [encoder setFragmentBuffer:lookup.directoryBuffer offset:0 atIndex:9];
        [encoder setFragmentBuffer:lookup.tailBuffer offset:0 atIndex:10];
        [encoder setFragmentBuffer:lookup.shaderSections offset:0 atIndex:11];
        [encoder setFragmentBytes:&p length:sizeof p atIndex:12];
        if (diagnostics) [encoder setFragmentBuffer:diagnostics offset:0 atIndex:13];
        for (unsigned i=0;i<lookup.arenaCount;++i)
            [encoder useResource:[lookup arenaAtIndex:i].vertices usage:MTLResourceUsageRead stages:MTLRenderStageFragment];
        [encoder setFragmentTexture:m.atlas atIndex:0]; [encoder setFragmentTexture:m.lightmap atIndex:2];
        [encoder setFragmentTexture:source.ids atIndex:3]; [encoder setFragmentTexture:source.validity atIndex:4];
        [encoder setFragmentTexture:source.depth atIndex:5];
        if (m.atlasLODClamp) [encoder setFragmentSamplerState:m.atlasSampler lodMinClamp:m.atlasLODMin lodMaxClamp:m.atlasLODMax atIndex:0];
        else [encoder setFragmentSamplerState:m.atlasSampler atIndex:0];
        if (m.lightLODClamp) [encoder setFragmentSamplerState:m.lightSampler lodMinClamp:m.lightLODMin lodMaxClamp:m.lightLODMax atIndex:2];
        else [encoder setFragmentSamplerState:m.lightSampler atIndex:2];
        [encoder drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
        [encoder endEncoding]; encoder=nil;
        return YES;
    } @catch (NSException *ex) {
        if (encoder) { @try { [encoder endEncoding]; } @catch (NSException *ignored) { (void)ignored; } }
        return fail(error,ex.reason);
    }
}
@end
