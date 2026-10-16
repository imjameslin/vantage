/* Offline replay of the captured Farsight draw workload, not a game FPS test.
 * Original arena addresses, visible lists, shaders, uniforms and atlas mips are
 * preserved. Near geometry, culling, animation and presentation are excluded.
 * No production path or installed client is changed by this executable.
 */
#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#import <CoreGraphics/CoreGraphics.h>
#import <ImageIO/ImageIO.h>
#include <zlib.h>
#include <unistd.h>
#include <algorithm>
#include <cmath>
#include <cfloat>
#include <cstring>
#include <map>
#include <random>
#include <vector>
#include "lod_scene_data.h"

static void check(bool ok, NSString *why) {
    if (!ok) { fprintf(stderr,"FAIL: %s\n",why.UTF8String); exit(1); }
}
static NSDictionary *json(NSString *path) {
    NSError *error=nil;
    NSData *data=[NSData dataWithContentsOfFile:path]; check(data!=nil,path);
    id value=[NSJSONSerialization JSONObjectWithData:data options:0 error:&error];
    check([value isKindOfClass:[NSDictionary class]],error.description ?: @"JSON object required");
    return value;
}
static void writeJson(id value, NSString *path) {
    NSError *error=nil;
    NSData *data=[NSJSONSerialization dataWithJSONObject:value options:NSJSONWritingPrettyPrinted|NSJSONWritingSortedKeys error:&error];
    check(data!=nil,error.description); check([data writeToFile:path atomically:YES],@"JSON write");
}
// Small textures/uniforms only. Geometry uses the independent bounded streamer.
static NSData *smallGzip(NSString *path, NSUInteger expected) {
    check(expected<=64ull<<20,@"Small-input cap");
    gzFile f=gzopen(path.fileSystemRepresentation,"rb"); check(f!=nullptr,path);
    NSMutableData *data=[NSMutableData dataWithLength:expected];
    NSUInteger at=0;
    while(at<expected) {
        int n=gzread(f,(uint8_t*)data.mutableBytes+at,(unsigned)std::min<NSUInteger>(expected-at,1<<20));
        check(n>0,@"Truncated gzip input"); at+=(NSUInteger)n;
    }
    uint8_t extra; check(gzread(f,&extra,1)==0 && gzeof(f),@"Trailing gzip input");
    check(gzclose(f)==Z_OK,@"Gzip close/CRC"); return data;
}
static id<MTLBuffer> smallBuffer(id<MTLDevice> dev, NSData *data, NSString *label) {
    id<MTLBuffer> b=[dev newBufferWithBytes:data.bytes length:data.length options:MTLResourceStorageModeShared];
    check(b!=nil,label); b.label=label; return b;
}
static id<MTLTexture> textureInput(id<MTLDevice> dev, NSString *dir, NSDictionary *desc) {
    check([desc[@"format"] isEqual:@"RGBA8_UNORM"],@"Expected captured RGBA8 texture");
    NSArray *mips=desc[@"mips"]; check(mips.count>0 && mips.count<=16,@"Mip count");
    NSDictionary *base=mips[0]; NSUInteger w=[base[@"width"] unsignedIntegerValue],h=[base[@"height"] unsignedIntegerValue];
    check(w>0 && h>0 && w<=4096 && h<=4096,@"Texture dimensions");
    MTLTextureDescriptor *td=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:w height:h mipmapped:mips.count>1];
    td.mipmapLevelCount=mips.count; td.storageMode=MTLStorageModeShared; td.usage=MTLTextureUsageShaderRead;
    id<MTLTexture> texture=[dev newTextureWithDescriptor:td]; check(texture!=nil,@"Texture allocation");
    for(NSUInteger i=0;i<mips.count;i++) {
        NSDictionary *m=mips[i]; NSUInteger mw=std::max<NSUInteger>(1,w>>i),mh=std::max<NSUInteger>(1,h>>i);
        check([m[@"level"] unsignedIntegerValue]==i && [m[@"width"] unsignedIntegerValue]==mw && [m[@"height"] unsignedIntegerValue]==mh,@"Mip shape");
        NSData *d=smallGzip([dir stringByAppendingPathComponent:m[@"file"]],mw*mh*4);
        [texture replaceRegion:MTLRegionMake2D(0,0,mw,mh) mipmapLevel:i withBytes:d.bytes bytesPerRow:mw*4];
    }
    return texture;
}
struct Target {
    id<MTLTexture> color,depth,identity;
    id<MTLBuffer> readback;
    NSUInteger width,height;
    Target(id<MTLDevice> dev,NSUInteger w,NSUInteger h,bool withIdentity=false):width(w),height(h) {
        MTLTextureDescriptor *td=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:w height:h mipmapped:NO];
        td.storageMode=MTLStorageModePrivate; td.usage=MTLTextureUsageRenderTarget|MTLTextureUsageShaderRead;
        color=[dev newTextureWithDescriptor:td];td.pixelFormat=MTLPixelFormatDepth32Float;
        depth=[dev newTextureWithDescriptor:td];
        if(withIdentity) {td.pixelFormat=MTLPixelFormatR32Uint;identity=[dev newTextureWithDescriptor:td];check(identity!=nil,@"Identity target");}
        // 3024*4 and 1512*4 are not guaranteed blit-aligned; pad each row to 256.
        readback=[dev newBufferWithLength:(withIdentity?3:2)*rowBytes()*h options:MTLResourceStorageModeShared];
        check(color&&depth&&readback,@"Output allocation");
    }
    NSUInteger rowBytes()const{return (width*4+255)&~255ull;}
    void copy(id<MTLCommandBuffer> cb) {
        id<MTLBlitCommandEncoder> b=[cb blitCommandEncoder];
        for(int i=0;i<(identity?3:2);i++)[b copyFromTexture:i==2?identity:(i?depth:color) sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0,0,0)
            sourceSize:MTLSizeMake(width,height,1) toBuffer:readback destinationOffset:i*rowBytes()*height
            destinationBytesPerRow:rowBytes() destinationBytesPerImage:rowBytes()*height];
        [b endEncoding];
    }
};
static double finish(id<MTLCommandBuffer> cb) {
    dispatch_semaphore_t done=dispatch_semaphore_create(0);
    [cb addCompletedHandler:^(id<MTLCommandBuffer> completed){(void)completed;dispatch_semaphore_signal(done);}];
    [cb commit];
    check(dispatch_semaphore_wait(done,dispatch_time(DISPATCH_TIME_NOW,30*NSEC_PER_SEC))==0,@"30-second GPU completion timeout");
    check(cb.status==MTLCommandBufferStatusCompleted,cb.error.description ?: @"Command buffer failed");
    double ms=(cb.GPUEndTime-cb.GPUStartTime)*1000.;
    check(std::isfinite(ms)&&ms>0,@"GPU timestamps unavailable");return ms;
}
static void png(Target &t,NSString *path) {
    std::vector<uint8_t> packed(t.width*t.height*4);
    // Production projection flips Y for the flipped presentation layer. Save an
    // upright human preview; raw depth/color hashes retain native row order.
    for(NSUInteger y=0;y<t.height;y++)memcpy(packed.data()+y*t.width*4,(uint8_t*)t.readback.contents+(t.height-1-y)*t.rowBytes(),t.width*4);
    CGColorSpaceRef cs=CGColorSpaceCreateWithName(kCGColorSpaceSRGB);
    CGDataProviderRef provider=CGDataProviderCreateWithData(nullptr,packed.data(),packed.size(),nullptr);
    CGImageRef image=CGImageCreate(t.width,t.height,8,32,t.width*4,cs,kCGBitmapByteOrderDefault|kCGImageAlphaLast,provider,nullptr,false,kCGRenderingIntentDefault);
    CGImageDestinationRef dest=CGImageDestinationCreateWithURL((__bridge CFURLRef)[NSURL fileURLWithPath:path],CFSTR("public.png"),1,nullptr);
    check(image&&dest,@"PNG setup");CGImageDestinationAddImage(dest,image,nullptr);check(CGImageDestinationFinalize(dest),@"PNG save");
    CFRelease(dest);CGImageRelease(image);CGDataProviderRelease(provider);CGColorSpaceRelease(cs);
}
static void rawOutput(Target &t,NSString *path) {
    gzFile f=gzopen(path.fileSystemRepresentation,"wb1");check(f!=nullptr,@"Raw output file");
    uint32_t header[4]={0x4c535250,(uint32_t)t.width,(uint32_t)t.height,2};
    check(gzwrite(f,header,sizeof(header))==sizeof(header),@"Raw output header");
    for(int plane=0;plane<2;plane++)for(NSUInteger y=0;y<t.height;y++) {
        void *p=(uint8_t*)t.readback.contents+(plane*t.height+y)*t.rowBytes();
        check(gzwrite(f,p,(unsigned)t.width*4)==(int)t.width*4,@"Raw output rows");
    }
    check(gzclose(f)==Z_OK,@"Raw output close");
}
struct Pipeline {
    id<MTLRenderPipelineState> state;
    id<MTLDepthStencilState> depth;
    MTLCullMode cull;
    MTLWinding front;
};
static NSUInteger number(NSDictionary *d, NSString *name) {
    check([d[name] isKindOfClass:[NSNumber class]],[NSString stringWithFormat:@"Missing numeric pipeline field %@",name]);
    return [d[name] unsignedIntegerValue];
}
static NSString *once(NSString *text,NSString *needle,NSString *replacement) {
    check([text componentsSeparatedByString:needle].count==2,[NSString stringWithFormat:@"Expected one shader match: %@",needle]);
    return [text stringByReplacingOccurrencesOfString:needle withString:replacement];
}
static NSString *identitySource(NSString *text,bool vertex) {
    check(![text containsString:@"locn6"] && ![text containsString:@"capturedFace"],@"Identity varying slot occupied");
    NSString *type=vertex?@"struct main0_out\n{":@"struct main0_in\n{";
    text=once(text,type,[type stringByAppendingString:@"\n    uint capturedFace [[user(locn6), flat]];"]);
    if(vertex) {
        NSRange sig=[text rangeOfString:@"vertex main0_out main0("];check(sig.location!=NSNotFound,@"Vertex signature");
        NSRange end=[text rangeOfString:@")\n{" options:0 range:NSMakeRange(sig.location,text.length-sig.location)];
        check(end.location!=NSNotFound,@"Vertex signature end");
        text=[text stringByReplacingCharactersInRange:NSMakeRange(end.location,0) withString:@", constant uint& capturedArena [[buffer(6)]]"];
        text=once(text,@"    uint2 mcmtl_item = mcmtl_items[mcmtl_e >> 6u];",
             @"    uint2 mcmtl_item = mcmtl_items[mcmtl_e >> 6u];\n    out.capturedFace = 1u + capturedArena + (mcmtl_item.x >> 2u) + (mcmtl_e & 63u);");
    } else {
        text=once(text,@"struct main0_out\n{",@"struct main0_out\n{\n    uint capturedFace [[color(1)]];");
        NSRange entry=[text rangeOfString:@"fragment main0_out main0("];
        NSRange ret=[text rangeOfString:@"    return out;" options:NSBackwardsSearch];
        check(entry.location!=NSNotFound && ret.location>entry.location && ret.location!=NSNotFound,@"Fragment return");
        text=[text stringByReplacingCharactersInRange:ret withString:@"    out.capturedFace = in.capturedFace;\n    return out;"];
    }
    return text;
}
static id<MTLLibrary> shader(id<MTLDevice> dev, NSString *dir, NSString *name,bool identity=false,NSString *dump=nil) {
    check(name.length>0 && !name.isAbsolutePath && ![name.pathComponents containsObject:@".."],@"Shader must remain inside export directory");
    NSError *error=nil;
    NSString *text=[NSString stringWithContentsOfFile:[dir stringByAppendingPathComponent:name] encoding:NSUTF8StringEncoding error:&error];
    check(text!=nil,error.description);
    if(identity)text=identitySource(text,[name hasSuffix:@".vert.metal"]);
    if(dump)check([text writeToFile:[dump stringByAppendingPathComponent:name.lastPathComponent] atomically:YES encoding:NSUTF8StringEncoding error:&error],@"Instrumented shader dump");
    MTLCompileOptions *opts=[MTLCompileOptions new];opts.languageVersion=MTLLanguageVersion3_2;opts.mathMode=MTLMathModeFast;
    id<MTLLibrary> lib=[dev newLibraryWithSource:text options:opts error:&error];
    check(lib!=nil,[NSString stringWithFormat:@"Shader %@: %@",name,error]);return lib;
}
static std::map<int,Pipeline> pipelines(id<MTLDevice> dev, NSString *dir,bool identity=false,NSString *dump=nil,bool translucentIdentity=false) {
    NSDictionary *manifest=json([dir stringByAppendingPathComponent:@"manifest.json"]);
    std::map<int,Pipeline> result;
    for(NSDictionary *d in manifest[@"pipelines"]) {
        int code=(int)number(d,@"layerCode"); check(result.find(code)==result.end(),@"Duplicate pipeline code");
        MTLRenderPipelineDescriptor *p=[MTLRenderPipelineDescriptor new];
        p.vertexFunction=[shader(dev,dir,d[@"vertexFile"],identity,dump) newFunctionWithName:d[@"vertexEntry"]];
        p.fragmentFunction=[shader(dev,dir,d[@"fragmentFile"],identity,dump) newFunctionWithName:d[@"fragmentEntry"]];
        check(p.vertexFunction&&p.fragmentFunction,@"Shader entrypoints");
        p.label=[NSString stringWithFormat:@"Captured LOD %d",code];
        // Production cluster shaders pull all attributes from explicit buffers.
        // No vertex descriptor, no source shader edits, sample count one.
        p.rasterSampleCount=1;p.depthAttachmentPixelFormat=MTLPixelFormatDepth32Float;
        p.inputPrimitiveTopology=(MTLPrimitiveTopologyClass)number(d,@"topologyClass");
        check(number(d,@"primitiveType")==MTLPrimitiveTypeTriangle && number(d,@"fillMode")==MTLTriangleFillModeFill &&
              number(d,@"emulation")==0 && ![d[@"vertexDescriptorRequired"] boolValue],@"Supported original pulling pipeline");
        for(NSString *name in @[@"depthBiasConstant",@"depthBiasSlopeScale",@"depthBiasClamp"])
            check([d[name] floatValue]==0,@"Unexpected depth bias");
        NSDictionary *c=d[@"colorTarget"]; auto a=p.colorAttachments[0];
        a.pixelFormat=(MTLPixelFormat)number(c,@"pixelFormat");check(a.pixelFormat==MTLPixelFormatRGBA8Unorm,@"Captured target RGBA8 expected");
        a.writeMask=(MTLColorWriteMask)number(c,@"writeMask");
        a.blendingEnabled=number(c,@"blendingEnabled")!=0;
        a.rgbBlendOperation=(MTLBlendOperation)number(c,@"rgbBlendOperation");
        a.alphaBlendOperation=(MTLBlendOperation)number(c,@"alphaBlendOperation");
        a.sourceRGBBlendFactor=(MTLBlendFactor)number(c,@"sourceRGBBlendFactor");
        a.destinationRGBBlendFactor=(MTLBlendFactor)number(c,@"destinationRGBBlendFactor");
        a.sourceAlphaBlendFactor=(MTLBlendFactor)number(c,@"sourceAlphaBlendFactor");
        a.destinationAlphaBlendFactor=(MTLBlendFactor)number(c,@"destinationAlphaBlendFactor");
        if(identity) {
            auto idTarget=p.colorAttachments[1];idTarget.pixelFormat=MTLPixelFormatR32Uint;
            idTarget.blendingEnabled=NO;
            // Keep the final opaque ID behind water. Its blend reads that opaque
            // color, so a face hidden only by translucent water must be retained.
            idTarget.writeMask=(code&7)==2&&!translucentIdentity?MTLColorWriteMaskNone:MTLColorWriteMaskAll;
        }
        NSError *error=nil; Pipeline q;
        q.state=[dev newRenderPipelineStateWithDescriptor:p error:&error];check(q.state!=nil,error.description);
        MTLDepthStencilDescriptor *ds=[MTLDepthStencilDescriptor new];
        ds.depthCompareFunction=(MTLCompareFunction)number(d,@"depthCompare");
        ds.depthWriteEnabled=number(d,@"depthWriteEnabled")!=0;
        check(ds.depthCompareFunction==MTLCompareFunctionGreaterEqual,@"Expected game's reverse-Z greater-equal depth state");
        q.depth=[dev newDepthStencilStateWithDescriptor:ds];check(q.depth!=nil,@"Depth state");
        q.cull=(MTLCullMode)number(d,@"cullMode");q.front=(MTLWinding)number(d,@"frontFacing");
        result.emplace(code,q);
    }
    check(!result.empty(),@"No exported pipelines");return result;
}
struct Materials {
    id<MTLTexture> atlas,lightmap;
    id<MTLSamplerState> atlasSampler,lightSampler;
    id<MTLBuffer> projection,globals,fog,terrain,halfGlobals;
    Materials(id<MTLDevice> dev,NSString *dir,NSDictionary *manifest) {
        atlas=textureInput(dev,dir,manifest[@"atlas"]);lightmap=textureInput(dev,dir,manifest[@"lightmap"]);
        NSDictionary *s=manifest[@"atlasSampler"];
        check([s[@"min"] isEqual:@"LINEAR"] && [s[@"mag"] isEqual:@"LINEAR"] &&
              [s[@"addressU"] isEqual:@"CLAMP_TO_EDGE"] && [s[@"addressV"] isEqual:@"CLAMP_TO_EDGE"] &&
              [s[@"anisotropy"] intValue]==1,@"Supported captured sampler state");
        MTLSamplerDescriptor *sd=[MTLSamplerDescriptor new];
        sd.minFilter=sd.magFilter=MTLSamplerMinMagFilterLinear;
        sd.sAddressMode=sd.tAddressMode=MTLSamplerAddressModeClampToEdge;
        sd.rAddressMode=MTLSamplerAddressModeRepeat;
        sd.mipFilter=MTLSamplerMipFilterLinear;sd.lodMaxClamp=FLT_MAX;sd.maxAnisotropy=1;
        // LevelRenderer creates terrain sampler with OptionalDouble.empty().
        // MetalFormats maps that to linear mip filtering and FLT_MAX LOD clamp.
        atlasSampler=[dev newSamplerStateWithDescriptor:sd];
        // SamplerCache clamp(LINEAR) disables mipmapping. Lightmap has one level.
        sd.mipFilter=MTLSamplerMipFilterNotMipmapped;sd.lodMaxClamp=0;
        lightSampler=[dev newSamplerStateWithDescriptor:sd];check(atlasSampler&&lightSampler,@"Samplers");
        projection=smallBuffer(dev,smallGzip([dir stringByAppendingPathComponent:@"projection.bin.gz"],64),@"Projection");
        globals=smallBuffer(dev,smallGzip([dir stringByAppendingPathComponent:@"globals.bin.gz"],48),@"Globals");
        fog=smallBuffer(dev,smallGzip([dir stringByAppendingPathComponent:@"fog.bin.gz"],48),@"Fog");
        terrain=smallBuffer(dev,smallGzip([dir stringByAppendingPathComponent:@"terrain.bin.gz"],80),@"TerrainUniform");
        uint8_t smaller[48];memcpy(smaller,globals.contents,48);
        float *screen=(float*)(smaller+32);
        check(screen[0]==3024 && screen[1]==1964,@"Captured screen dimensions");
        screen[0]*=.5f;screen[1]*=.5f;
        halfGlobals=smallBuffer(dev,[NSData dataWithBytes:smaller length:48],@"Half-resolution diagnostic Globals");
    }
    void bind(id<MTLRenderCommandEncoder> re,bool half)const {
        id<MTLBuffer> ubos[]={projection,half?halfGlobals:globals,fog,terrain};
        NSUInteger slots[]={0,1,2,5};
        for(int i=0;i<4;i++) {
            [re setVertexBuffer:ubos[i] offset:0 atIndex:slots[i]];
            [re setFragmentBuffer:ubos[i] offset:0 atIndex:slots[i]];
        }
        [re setVertexTexture:lightmap atIndex:2];[re setVertexSamplerState:lightSampler atIndex:2];
        [re setFragmentTexture:atlas atIndex:0];[re setFragmentSamplerState:atlasSampler atIndex:0];
    }
};
struct ReplayDraw {
    int code;
    uint32_t count;
    id<MTLBuffer> arena,quads,items,args;
    uint32_t identityBase=0;
};
struct Replay {
    id<MTLCommandQueue> queue;
    id<MTLBuffer> sections,indices;
    Materials &materials;
    std::map<int,Pipeline> &psos;
    std::vector<ReplayDraw> draws;
    Replay(id<MTLDevice> dev,id<MTLBuffer> s,Materials &m,std::map<int,Pipeline> &p,std::vector<ReplayDraw> d)
      :sections(s),materials(m),psos(p),draws(std::move(d)) {
        queue=[dev newCommandQueue];check(queue!=nil,@"Command queue");
        uint32_t maxQuads=0;
        for(const auto &draw:draws) {
            check(psos.find(draw.code)!=psos.end(),@"Captured draw has no pipeline");
            maxQuads=std::max(maxQuads,draw.count);
        }
        check(maxQuads>0 && maxQuads<=6000000,@"Index cap");
        indices=[dev newBufferWithLength:(NSUInteger)maxQuads*24 options:MTLResourceStorageModeShared];
        check(indices!=nil,@"Index allocation");
        uint32_t *dst=(uint32_t*)indices.contents;const uint32_t order[]={0,1,2,2,3,0};
        for(uint32_t i=0;i<maxQuads;i++)for(int k=0;k<6;k++)dst[i*6+k]=i*4+order[k];
    }
    void encode(id<MTLCommandBuffer> cb,Target &target,unsigned mask,bool indirect) {
        MTLRenderPassDescriptor *pass=[MTLRenderPassDescriptor renderPassDescriptor];
        auto color=pass.colorAttachments[0];color.texture=target.color;
        color.loadAction=MTLLoadActionClear;color.storeAction=MTLStoreActionStore;
        const float *fog=(const float*)materials.fog.contents;
        color.clearColor=MTLClearColorMake(fog[0],fog[1],fog[2],1);
        pass.depthAttachment.texture=target.depth;
        pass.depthAttachment.clearDepth=0;pass.depthAttachment.loadAction=MTLLoadActionClear;
        pass.depthAttachment.storeAction=MTLStoreActionStore;
        if(target.identity) {
            auto a=pass.colorAttachments[1];a.texture=target.identity;a.loadAction=MTLLoadActionClear;
            a.storeAction=MTLStoreActionStore;a.clearColor=MTLClearColorMake(0,0,0,0);
        }
        id<MTLRenderCommandEncoder> re=[cb renderCommandEncoderWithDescriptor:pass];check(re!=nil,@"Render encoder");
        re.label=@"Captured LOD only: original shaders and addresses";
        [re setViewport:(MTLViewport){0,0,(double)target.width,(double)target.height,0,1}];
        [re setScissorRect:(MTLScissorRect){0,0,target.width,target.height}];
        [re setDepthClipMode:MTLDepthClipModeClip];[re setDepthBias:0 slopeScale:0 clamp:0];
        materials.bind(re,target.width==1512);
        [re setVertexBuffer:sections offset:0 atIndex:29];
        // ChunkSectionsToRender iterates SOLID, CUTOUT, then TRANSLUCENT. Keep
        // original group order within each layer (especially for water blend).
        for(int layer=0;layer<3;layer++)if(mask&(1u<<layer)) {
            int bound=-1;
            for(const auto &draw:draws)if((draw.code&7)==layer && draw.count) {
                const Pipeline &p=psos.at(draw.code);
                if(bound!=draw.code) {
                    [re setRenderPipelineState:p.state];[re setDepthStencilState:p.depth];
                    [re setCullMode:p.cull];[re setFrontFacingWinding:p.front];bound=draw.code;
                }
                [re setVertexBuffer:draw.arena offset:0 atIndex:30];
                [re setVertexBuffer:draw.quads offset:0 atIndex:28];
                [re setVertexBuffer:draw.items offset:0 atIndex:27];
                if(target.identity)[re setVertexBytes:&draw.identityBase length:4 atIndex:6];
                if(indirect)[re drawIndexedPrimitives:MTLPrimitiveTypeTriangle indexType:MTLIndexTypeUInt32 indexBuffer:indices
                    indexBufferOffset:0 indirectBuffer:draw.args indirectBufferOffset:0];
                else [re drawIndexedPrimitives:MTLPrimitiveTypeTriangle indexCount:(NSUInteger)draw.count*6 indexType:MTLIndexTypeUInt32
                    indexBuffer:indices indexBufferOffset:0 instanceCount:1 baseVertex:0 baseInstance:0];
            }
        }
        [re endEncoding];
    }
    double render(Target &target,unsigned mask,bool indirect,int repeats,bool readback) {
        id<MTLCommandBuffer> cb=[queue commandBuffer];check(cb!=nil,@"Command buffer");
        for(int i=0;i<repeats;i++)encode(cb,target,mask,indirect);
        if(readback)target.copy(cb);
        return finish(cb)/repeats;
    }
};
static NSDictionary *compare(Target &a,Target &b) {
    check(a.width==b.width && a.height==b.height,@"Comparison dimensions");
    uint64_t colorMismatch=0,depthMismatch=0,covered=0,invalidDepth=0,colorAbsSum=0;
    unsigned maxColorDifference=0;NSMutableArray *examples=[NSMutableArray array];
    float maxDepth=0;
    for(NSUInteger y=0;y<a.height;y++) {
        const uint32_t *ac=(const uint32_t*)((uint8_t*)a.readback.contents+y*a.rowBytes());
        const uint32_t *bc=(const uint32_t*)((uint8_t*)b.readback.contents+y*b.rowBytes());
        const uint32_t *ad=(const uint32_t*)((uint8_t*)a.readback.contents+(a.height+y)*a.rowBytes());
        const uint32_t *bd=(const uint32_t*)((uint8_t*)b.readback.contents+(b.height+y)*b.rowBytes());
        for(NSUInteger x=0;x<a.width;x++) {
            colorMismatch+=ac[x]!=bc[x];depthMismatch+=ad[x]!=bd[x];
            if(ac[x]!=bc[x]) {
                unsigned largest=0;
                for(int c=0;c<4;c++) {
                    unsigned difference=(unsigned)std::abs(int((ac[x]>>(c*8))&255)-int((bc[x]>>(c*8))&255));
                    largest=std::max(largest,difference);colorAbsSum+=difference;
                }
                maxColorDifference=std::max(maxColorDifference,largest);
                if(examples.count<32)[examples addObject:@{@"x":@(x),@"nativeY":@(y),@"a":@(ac[x]),@"b":@(bc[x]),@"maxByteDifference":@(largest)}];
            }
            float z;memcpy(&z,&ad[x],4);covered+=z>0;
            invalidDepth+=!std::isfinite(z)||z<0||z>1;maxDepth=std::max(maxDepth,z);
        }
    }
    return @{@"colorMismatchPixels":@(colorMismatch),@"depthBitMismatchPixels":@(depthMismatch),
             @"coveredDepthPixels":@(covered),@"invalidDepthPixels":@(invalidDepth),@"maxReverseDepth":@(maxDepth),
             @"maxColorByteDifference":@(maxColorDifference),@"colorAbsoluteDifferenceSum":@(colorAbsSum),@"differenceExamples":examples};
}
int main(int argc,char **argv) {
    @autoreleasepool {
        check(argc==5,@"Usage: lod_scene_replay SNAPSHOT SHADERS OUTPUT quality|timing");
        alarm(175);
        NSString *dir=@(argv[1]),*shaders=@(argv[2]),*out=@(argv[3]),*mode=@(argv[4]);
        check([mode isEqual:@"quality"]||[mode isEqual:@"timing"],@"Mode");
        bool quality=[mode isEqual:@"quality"];
        check(getenv("MC_GPU_LOCK_HELD")&&strcmp(getenv("MC_GPU_LOCK_HELD"),"timing")==0,@"Timing lock required");
        id<MTLDevice> dev=MTLCreateSystemDefaultDevice();check(dev!=nil,@"Metal device");
        auto psos=pipelines(dev,shaders);
        lodscene::Scene scene;std::string error;
        fprintf(stdout,"Loading complete source snapshot into original arena addresses...\n");fflush(stdout);
        bool loaded=lodscene::load(dev,dir,scene,error);
        check(loaded,[NSString stringWithUTF8String:error.c_str()]);
        check(scene.stats.validated,@"Loader did not validate all geometry references");
        const auto &s=scene.stats;
        fprintf(stdout,"Loaded %llu source / %llu visible quads, %llu sections, %u arenas\n",
                (unsigned long long)s.payloadQuads,(unsigned long long)s.visibleQuads,
                (unsigned long long)s.selectedSections,s.allocatedArenas);fflush(stdout);
        Materials materials(dev,dir,scene.manifest);
        std::vector<ReplayDraw> draws;
        for(const auto &d:scene.draws) {
            const auto &g=scene.groups.at(d.groupOrdinal);
            draws.push_back({(int)d.layerCode,d.quadCount,scene.arenas.at(g.arena).buffer,
                             d.quadBuffer,d.itemBuffer,d.argsBuffer});
        }
        Replay replay(dev,scene.sectionInfos,materials,psos,std::move(draws));
        Target native(dev,3024,1964),other(dev,quality?3024:1512,quality?1964:982);
        check(dev.currentAllocatedSize<6ull<<30,@"Total replay Metal memory cap (6 GiB)");
        NSMutableDictionary *result=[@{@"schema":@"farsight-scene-replay-1",@"status":@"running",@"mode":mode,
            @"device":dev.name,@"snapshot":dir,@"shaders":shaders,
            @"sourceQuads":@(s.payloadQuads),@"visibleQuads":@(s.visibleQuads),@"groups":@(s.groups),
            @"sections":@(s.selectedSections),@"originalArenaCount":@(s.allocatedArenas),
            @"MetalAllocatedBytes":@(dev.currentAllocatedSize),
            @"visibleByLayer":@[@(s.visibleByLayer[0]),@(s.visibleByLayer[1]),@(s.visibleByLayer[2])],
            @"scope":@"Original LOD raster only: clear, original buffers/shaders, all retained visible groups, color/depth store. No culling, near terrain, entities, box queries, streaming, CPU frame, presentation or overlap with those passes.",
            @"qualification":@"Diagnostic baseline only. No full-game FPS, Voxy visual equivalence, motion or optimization claim.",
            @"depth":@"Captured reverse-Z projection, greater-equal, clear 0; post-GUI capture depth is NOT an occluder reference."} mutableCopy];
        if(quality) {
            replay.render(native,7,true,1,true);
            memcpy(other.readback.contents,native.readback.contents,native.readback.length);
            replay.render(native,7,true,2,false);
            replay.render(native,7,true,1,true);
            result[@"coldWarmControl"]=compare(other,native);
            result[@"qualityWarmupPasses"]=@3;
            png(native,[out stringByAppendingPathComponent:@"lod-only-upright.png"]);
            rawOutput(native,[out stringByAppendingPathComponent:@"lod-only-native-rows.bin.gz"]);
            // Preserve the reference bytes, then use the SAME attachments for
            // direct/indirect parity. Cold and separate-target controls are
            // retained independently; neither is silently treated as bit exact.
            memcpy(other.readback.contents,native.readback.contents,native.readback.length);
            replay.render(native,7,false,1,true);
            NSDictionary *parity=compare(other,native);result[@"directIndirectParity"]=parity;
            png(native,[out stringByAppendingPathComponent:@"direct-lod-only-upright.png"]);
            rawOutput(native,[out stringByAppendingPathComponent:@"direct-lod-only-native-rows.bin.gz"]);
            replay.render(native,7,true,1,true);
            NSDictionary *repeat=compare(other,native);result[@"indirectRepeatParity"]=repeat;
            bool ok=[parity[@"colorMismatchPixels"] unsignedLongLongValue]==0 &&
                    [parity[@"depthBitMismatchPixels"] unsignedLongLongValue]==0 &&
                    [parity[@"invalidDepthPixels"] unsignedLongLongValue]==0 &&
                    [parity[@"coveredDepthPixels"] unsignedLongLongValue]>10000;
            ok=ok && [repeat[@"colorMismatchPixels"] unsignedLongLongValue]==0 &&
                     [repeat[@"depthBitMismatchPixels"] unsignedLongLongValue]==0;
            replay.render(other,7,true,1,true);
            result[@"differentAttachmentControl"]=compare(native,other);
            // An empty-list control must remove all geometry depth. It catches
            // tests accidentally comparing two undrawn clears or stale images.
            replay.render(other,0,true,1,true);
            NSDictionary *empty=compare(other,other);
            result[@"emptyControlDepthPixels"]=empty[@"coveredDepthPixels"];
            ok=ok && [empty[@"coveredDepthPixels"] unsignedLongLongValue]==0;
            result[@"status"]=ok?@"passed":@"failed";
            writeJson(result,[out stringByAppendingPathComponent:@"result.json"]);
            check(ok,@"Direct/indirect or coverage/control validation failed");
            printf("QUALITY PASSED: direct vs indirect color/depth identical; geometry and empty-list controls valid\n");
        } else {
            struct Case { const char *name;unsigned mask;bool half; };
            const Case cases[]={{"all-native",7,false},{"all-half-diagnostic",7,true},
                {"solid-only-diagnostic",1,false},{"cutout-only-diagnostic",2,false},{"water-only-diagnostic",4,false}};
            for(int warm=0;warm<3;warm++)for(auto c:cases)replay.render(c.half?other:native,c.mask,true,1,false);
            std::array<std::vector<double>,5> samples;
            std::mt19937 rng(20261008);
            NSMutableArray *orders=[NSMutableArray array];
            for(int round=0;round<9;round++) {
                std::array<int,5> order={0,1,2,3,4};std::shuffle(order.begin(),order.end(),rng);
                NSMutableArray *record=[NSMutableArray array];
                for(int k:order) {
                    auto c=cases[k];double ms=replay.render(c.half?other:native,c.mask,true,4,false);
                    samples[k].push_back(ms);[record addObject:@(c.name)];
                }
                [orders addObject:record];
            }
            NSMutableArray *rows=[NSMutableArray array];
            for(int k=0;k<5;k++) {
                auto sorted=samples[k];std::sort(sorted.begin(),sorted.end());NSMutableArray *values=[NSMutableArray array];
                double sum=0;for(double v:samples[k]){[values addObject:@(v)];sum+=v;}
                auto c=cases[k];
                [rows addObject:@{@"name":@(c.name),@"width":@(c.half?1512:3024),@"height":@(c.half?982:1964),
                    @"layerMask":@(c.mask),@"medianGpuMs":@(sorted[4]),@"meanGpuMs":@(sum/9),
                    @"minGpuMs":@(sorted.front()),@"maxGpuMs":@(sorted.back()),@"gpuMsSamples":values}];
                printf("%s: median %.6f ms, range %.6f..%.6f ms\n",c.name,sorted[4],sorted.front(),sorted.back());
            }
            result[@"timings"]=rows;result[@"interleavedOrders"]=orders;
            result[@"warmupPassesPerCase"]=@3;result[@"measuredBlocksPerCase"]=@9;result[@"passesPerBlock"]=@4;
            result[@"timingNotes"]=@"GPUStartTime to GPUEndTime divided by four independently cleared/stored passes; no readback in timed commands. Serialized isolated workload. Half resolution and isolated layers are cost diagnostics, never quality candidates. Layer times are not additive because occlusion changes.";
            result[@"status"]=@"passed";
            writeJson(result,[out stringByAppendingPathComponent:@"result.json"]);
        }
    }
    return 0;
}
