// Step 0 only: actual driver compilation, dispatch and readback. No FPS claim.
// Run through micro-raster-atomics.sh under the timing GPU lane. No window.
#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

static uint64_t mix(uint64_t x) {
    x += UINT64_C(0x9e3779b97f4a7c15);
    x = (x ^ (x >> 30)) * UINT64_C(0xbf58476d1ce4e5b9);
    x = (x ^ (x >> 27)) * UINT64_C(0x94d049bb133111eb);
    return x ^ (x >> 31);
}

static NSString *kernelSource(BOOL texture, BOOL wide, BOOL maximum) {
    NSString *type = wide ? @"ulong" : @"uint";
    NSString *op = maximum ? @"max" : @"min";
    NSString *output = texture
        ? [NSString stringWithFormat:@"texture2d<%@, access::read_write> dst [[texture(0)]]", type]
        : [NSString stringWithFormat:@"device atomic_%@ *dst [[buffer(1)]]", type];
    // ulong min/max are non-fetch operations; uint uses the fetch overloads.
    NSString *fetch = wide ? @"" : @"fetch_";
    NSString *operation = texture
        ? [NSString stringWithFormat:@"dst.atomic_%@%@(uint2(bin, 0), value);", fetch, op]
        : [NSString stringWithFormat:@"atomic_%@%@_explicit(dst + bin, value, memory_order_relaxed);", fetch, op];
    return [NSString stringWithFormat:
        @"#include <metal_stdlib>\nusing namespace metal;\n"
         "kernel void probe(device const %@ *src [[buffer(0)]], %@,"
         " constant uint2 &params [[buffer(2)]], uint tid [[thread_position_in_grid]]) {"
         " if(tid >= params.x) return; uint bin=tid%%params.y; %@ value=src[tid]; %@ }\n",
        type, output, type, operation];
}

static NSString *fragmentSource(BOOL texture, BOOL maximum) {
    NSString *output = texture ? @"texture2d<ulong, access::read_write> dst [[texture(0)]]"
        : @"device atomic_ulong *dst [[buffer(1)]]";
    NSString *op = maximum ? @"max" : @"min";
    NSString *write = texture ? [NSString stringWithFormat:@"dst.atomic_%@(uint2(bin,0), value);",op]
        : [NSString stringWithFormat:@"atomic_%@_explicit(dst+bin,value,memory_order_relaxed);",op];
    return [NSString stringWithFormat:
        @"#include <metal_stdlib>\nusing namespace metal;\n"
         "struct V { float4 pos [[position]]; uint instance [[flat]]; };"
         "vertex V v(uint i [[vertex_id]], uint n [[instance_id]]) {"
         "float2 xy[3]={float2(-1,-1),float2(3,-1),float2(-1,3)};return {float4(xy[i],.5,1),n}; }"
         "fragment float4 f(V in [[stage_in]], device const ulong *src [[buffer(0)]], %@,"
         "constant uint2 &params [[buffer(2)]]) {uint bin=uint(in.pos.x);uint i=in.instance*params.y+bin;"
         "if(i>=params.x || i<params.x/2) discard_fragment();ulong value=src[i];%@ return float4(0);}",output,write];
}

static NSMutableDictionary *probe(id<MTLDevice> device, id<MTLCommandQueue> queue,
                                  MTLLanguageVersion version, NSString *label,
                                  BOOL texture, BOOL wide, BOOL maximum) {
    NSMutableDictionary *row = [@{@"language":label, @"storage":texture ? @"texture" : @"buffer",
        @"bits":wide ? @64 : @32, @"operation":maximum ? @"max" : @"min",
        @"shader":kernelSource(texture, wide, maximum), @"status":@"not-run"} mutableCopy];
    MTLCompileOptions *options = [MTLCompileOptions new];
    options.languageVersion = version;
    options.mathMode = MTLMathModeSafe;
    NSError *error = nil;
    id<MTLLibrary> library = [device newLibraryWithSource:row[@"shader"] options:options error:&error];
    if (!library) { row[@"status"]=@"compile-rejected"; row[@"error"]=error.description ?: @"no library"; return row; }
    id<MTLComputePipelineState> pipeline = [device newComputePipelineStateWithFunction:[library newFunctionWithName:@"probe"] error:&error];
    if (!pipeline) { row[@"status"]=@"pipeline-rejected"; row[@"error"]=error.description ?: @"no pipeline"; return row; }
    id<MTLRenderPipelineState> renderPipeline=nil;
    if(wide) {
        row[@"fragmentShader"]=fragmentSource(texture,maximum);
        id<MTLLibrary> renderLibrary=[device newLibraryWithSource:row[@"fragmentShader"] options:options error:&error];
        if(!renderLibrary) {row[@"status"]=@"fragment-compile-rejected";row[@"error"]=error.description ?: @"no library";return row;}
        MTLRenderPipelineDescriptor *pd=[MTLRenderPipelineDescriptor new];
        pd.vertexFunction=[renderLibrary newFunctionWithName:@"v"];
        pd.fragmentFunction=[renderLibrary newFunctionWithName:@"f"];
        pd.colorAttachments[0].pixelFormat=MTLPixelFormatRGBA8Unorm;
        renderPipeline=[device newRenderPipelineStateWithDescriptor:pd error:&error];
        if(!renderPipeline) {row[@"status"]=@"fragment-pipeline-rejected";row[@"error"]=error.description ?: @"no pipeline";return row;}
    }
    const uint32_t count = 65536;
    const NSUInteger stride = wide ? 8 : 4;
    id<MTLBuffer> input = [device newBufferWithLength:count*stride options:MTLResourceStorageModeShared];
    if (!input) { row[@"status"]=@"allocation-failed"; return row; }
    unsigned long long checks=0, mismatches=0;
    const uint32_t binCounts[] = {1, 257, 4096};
    for (NSUInteger layout=0; layout<3; layout++) @autoreleasepool {
        uint32_t bins=binCounts[layout];
        NSMutableData *reference=[NSMutableData dataWithLength:bins*stride];
        NSMutableData *readback=[NSMutableData dataWithLength:bins*stride];
        id<MTLBuffer> buffer=nil;
        id<MTLTexture> target=nil;
        if (texture) {
            MTLTextureDescriptor *desc=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:
                wide ? MTLPixelFormatRG32Uint : MTLPixelFormatR32Uint width:bins height:1 mipmapped:NO];
            desc.storageMode=MTLStorageModeShared;
            desc.usage=MTLTextureUsageShaderRead | MTLTextureUsageShaderWrite | MTLTextureUsageShaderAtomic;
            target=[device newTextureWithDescriptor:desc];
        } else buffer=[device newBufferWithLength:bins*stride options:MTLResourceStorageModeShared];
        if (texture ? !target : !buffer) { row[@"status"]=@"allocation-failed"; return row; }
        MTLTextureDescriptor *colorDesc=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm
            width:bins height:1 mipmapped:NO];
        colorDesc.storageMode=MTLStorageModePrivate;colorDesc.usage=MTLTextureUsageRenderTarget;
        id<MTLTexture> color=wide ? [device newTextureWithDescriptor:colorDesc] : nil;
        if(wide && !color) {row[@"status"]=@"allocation-failed";return row;}
        for (uint32_t round=0; round<12; round++) {
            memset(reference.mutableBytes, maximum ? 0 : 255, reference.length);
            if (texture) [target replaceRegion:MTLRegionMake2D(0,0,bins,1) mipmapLevel:0
                withBytes:reference.bytes bytesPerRow:bins*stride];
            else memcpy(buffer.contents,reference.bytes,reference.length);
            for (uint32_t i=0; i<count; i++) {
                uint64_t value=mix(((uint64_t)round<<32) | i);
                // Equal high-word depths test low-word winner selection; alternating
                // positive/full-sign-range bit patterns catch accidental signed lowering.
                if (round==1) value=UINT64_C(0x3f40000000000000) | (uint32_t)value;
                if (round==2) value=((uint64_t)(0x3f000000u + (i&31))<<32) | (uint32_t)~i;
                if (round==3 && i<bins) value=maximum ? UINT64_MAX : 0;
                // In mixed rounds force a fragment-only winner for every bin.
                if (round==7 && i>=count-bins) value=maximum ? UINT64_MAX : 0;
                uint32_t bin=i%bins;
                if (wide) {
                    ((uint64_t *)input.contents)[i]=value;
                    uint64_t *ref=reference.mutableBytes;
                    if (maximum ? value>ref[bin] : value<ref[bin]) ref[bin]=value;
                } else {
                    uint32_t v=(uint32_t)value;
                    ((uint32_t *)input.contents)[i]=v;
                    uint32_t *ref=reference.mutableBytes;
                    if (maximum ? v>ref[bin] : v<ref[bin]) ref[bin]=v;
                }
            }
            id<MTLCommandBuffer> cb=[queue commandBuffer];
            id<MTLComputeCommandEncoder> enc=[cb computeCommandEncoder];
            [enc setComputePipelineState:pipeline];
            [enc setBuffer:input offset:0 atIndex:0];
            if(texture) [enc setTexture:target atIndex:0]; else [enc setBuffer:buffer offset:0 atIndex:1];
            BOOL mixed=wide && round>=6;
            uint32_t params[]={mixed ? count/2 : count,bins};[enc setBytes:params length:sizeof params atIndex:2];
            NSUInteger threads=MIN((NSUInteger)256,pipeline.maxTotalThreadsPerThreadgroup);
            [enc dispatchThreads:MTLSizeMake(params[0],1,1) threadsPerThreadgroup:MTLSizeMake(threads,1,1)];
            [enc endEncoding];
            if(mixed) {
                MTLRenderPassDescriptor *rp=[MTLRenderPassDescriptor renderPassDescriptor];
                rp.colorAttachments[0].texture=color;rp.colorAttachments[0].loadAction=MTLLoadActionDontCare;
                rp.colorAttachments[0].storeAction=MTLStoreActionDontCare;
                id<MTLRenderCommandEncoder> re=[cb renderCommandEncoderWithDescriptor:rp];
                [re setRenderPipelineState:renderPipeline];[re setCullMode:MTLCullModeNone];
                [re setFragmentBuffer:input offset:0 atIndex:0];
                if(texture) [re setFragmentTexture:target atIndex:0];else [re setFragmentBuffer:buffer offset:0 atIndex:1];
                params[0]=count;[re setFragmentBytes:params length:sizeof params atIndex:2];
                [re drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3 instanceCount:(count+bins-1)/bins];
                [re endEncoding];
            }
            [cb commit];[cb waitUntilCompleted];
            if(cb.status!=MTLCommandBufferStatusCompleted) {
                row[@"status"]=@"gpu-failed";row[@"error"]=cb.error.description ?: @"command failed";return row;
            }
            if(texture) [target getBytes:readback.mutableBytes bytesPerRow:bins*stride
                fromRegion:MTLRegionMake2D(0,0,bins,1) mipmapLevel:0];
            else memcpy(readback.mutableBytes,buffer.contents,readback.length);
            for(uint32_t bin=0;bin<bins;bin++) {
                checks++;
                if(memcmp((char *)reference.bytes+bin*stride,(char *)readback.bytes+bin*stride,stride)) mismatches++;
            }
        }
    }
    row[@"checks"]=@(checks);row[@"mismatches"]=@(mismatches);
    row[@"mixedComputeFragmentChecks"]=wide ? @(checks/2) : @0;
    row[@"status"]=mismatches ? @"readback-failed" : @"passed";
    return row;
}

int main(void) { @autoreleasepool {
    id<MTLDevice> device=MTLCreateSystemDefaultDevice();
    if(!device) { fprintf(stderr,"No Metal device\n");return 2; }
    id<MTLCommandQueue> queue=[device newCommandQueue];
    NSMutableArray *rows=[NSMutableArray new];
    const MTLLanguageVersion versions[]={MTLLanguageVersion3_2,MTLLanguageVersion4_0};
    NSArray *labels=@[@"3.2",@"4.0"];
    for(NSUInteger lang=0;lang<2;lang++) for(int wide=0;wide<2;wide++)
        for(int texture=0;texture<2;texture++) for(int maximum=0;maximum<2;maximum++) {
            @autoreleasepool {
                NSMutableDictionary *row=probe(device,queue,versions[lang],labels[lang],texture,wide,maximum);
                [rows addObject:row];
                fprintf(stderr,"MSL %s %s %d-bit %s: %s\n",[labels[lang] UTF8String],
                    texture?"texture":"buffer",wide?64:32,maximum?"max":"min",[row[@"status"] UTF8String]);
            }
        }
    NSDictionary *report=@{@"stage":@"feasibility-only",@"fpsMeasured":@NO,
        @"device":device.name,@"registryID":@(device.registryID),
        @"os":[NSProcessInfo processInfo].operatingSystemVersionString,
        @"apple8":@([device supportsFamily:MTLGPUFamilyApple8]),
        @"apple9":@([device supportsFamily:MTLGPUFamilyApple9]),
        @"validation":@(getenv("MTL_DEBUG_LAYER") ? atoi(getenv("MTL_DEBUG_LAYER")) : -1),
        @"results":rows};
    NSData *json=[NSJSONSerialization dataWithJSONObject:report options:NSJSONWritingPrettyPrinted|NSJSONWritingSortedKeys error:nil];
    fwrite(json.bytes,1,json.length,stdout);puts("");
    // Unsupported 64-bit ops are a valid feasibility result. A broken control/readback isn't.
    for(NSDictionary *row in rows) if([row[@"status"] isEqual:@"gpu-failed"]
        || [row[@"status"] isEqual:@"allocation-failed"] || [row[@"status"] isEqual:@"readback-failed"]
        || ([row[@"bits"] intValue]==32 && ![row[@"status"] isEqual:@"passed"])) return 1;
    return 0;
} }
