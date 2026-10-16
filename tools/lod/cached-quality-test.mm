// Tiny standalone runner; --cpu-only never creates a Metal device.
// GPU invocation (after parent grants a serialized slot): runner --gpu SHADER FIXTURE_DIR
#include "../../native/tests/lod_cached_quality.h"
#include <cstdio>

static void check(bool ok, const char* why) { lodcachedquality::detail::need(ok,why); }
static void cpuTests() {
    using lodcachedquality::detail::largestRegion;
    std::vector<uint32_t> stack;
    std::array<uint32_t,4> box{};
    auto run = [&](std::vector<uint8_t> f, unsigned w, unsigned h, unsigned expected) {
        check(largestRegion(f.data(),w,h,stack,box)==expected,"CPU region size");
        check(std::all_of(f.begin(),f.end(),[](uint8_t v){return v==0;}),"flags consumed");
    };
    run(std::vector<uint8_t>(9,0),3,3,0);
    run({1},1,1,1); run({1,1,0,1},1,4,2); run({1,1,0,1},4,1,2);
    run({1,0,0,0,1,0,0,0,1},3,3,3); // Diagonals ARE connected.
    check(box==std::array<uint32_t,4>{0,0,2,2},"diagonal bounds");
    run({0,0,0,1,1,0,0,0},4,2,1); // No row wrap.
    run(std::vector<uint8_t>(255,1),255,1,255);
    run(std::vector<uint8_t>(256,1),256,1,256);
    run(std::vector<uint8_t>(3024*1964,1),3024,1964,3024*1964);
    // Exercise all bounded percentile ranks, including where 999*N overflows uint.
    for (uint32_t n=1;n<=3024u*1964u;n++)
        check(n-n/1000u==uint32_t(std::ceil(0.999*double(n))),"percentile rank");
    std::puts("CPU region and percentile checks PASS (no Metal device created)");
}

static id<MTLTexture> upload(id<MTLDevice> device, NSString* path, uint32_t w, uint32_t h) {
    NSData* data=[NSData dataWithContentsOfFile:path];
    check(data && data.length==4ull*w*h,"fixture RGBA length");
    MTLTextureDescriptor* desc=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm
        width:w height:h mipmapped:NO];
    desc.storageMode=MTLStorageModeShared; desc.usage=MTLTextureUsageShaderRead;
    id<MTLTexture> t=[device newTextureWithDescriptor:desc]; check(t!=nil,"fixture texture");
    [t replaceRegion:MTLRegionMake2D(0,0,w,h) mipmapLevel:0 withBytes:data.bytes bytesPerRow:4*w];
    return t;
}

int main(int argc, const char** argv) {
    @autoreleasepool { try {
        if (argc==2 && std::strcmp(argv[1],"--cpu-only")==0) { cpuTests(); return 0; }
        check(argc==4 && std::strcmp(argv[1],"--gpu")==0,"usage: --cpu-only OR --gpu SHADER FIXTURE_DIR (parent authorization required)");
        NSString* dir=[NSString stringWithUTF8String:argv[3]];
        NSData* json=[NSData dataWithContentsOfFile:[dir stringByAppendingPathComponent:@"expected.json"]];
        check(json!=nil,"expected.json missing");
        NSError* error=nil;
        NSArray* cases=[NSJSONSerialization JSONObjectWithData:json options:0 error:&error];
        check([cases isKindOfClass:NSArray.class] && cases.count>0,"fixture JSON");
        id<MTLDevice> device=MTLCreateSystemDefaultDevice(); check(device!=nil,"no Metal device");
        id<MTLCommandQueue> queue=[device newCommandQueue]; check(queue!=nil,"no command queue");
        lodcachedquality::Evaluator evaluator(device,argv[2]);
        unsigned failed=0;
        for (NSDictionary* c in cases) { @autoreleasepool {
            uint32_t w=[c[@"width"] unsignedIntValue], h=[c[@"height"] unsignedIntValue];
            NSString* name=c[@"name"];
            id<MTLTexture> a=upload(device,[dir stringByAppendingPathComponent:[name stringByAppendingString:@"-ref.rgba"]],w,h);
            id<MTLTexture> b=upload(device,[dir stringByAppendingPathComponent:[name stringByAppendingString:@"-cand.rgba"]],w,h);
            NSDictionary* got=evaluator.evaluate(queue,b,a);
            double dm=std::abs([got[@"mean"] doubleValue]-[c[@"mean"] doubleValue]);
            double dp=std::abs([got[@"p999"] doubleValue]-[c[@"p999"] doubleValue]);
            double ds=std::abs([got[@"ssim"] doubleValue]-[c[@"ssim"] doubleValue]);
            // Allow float-vs-double arithmetic and one histogram boundary bin;
            // gate decisions and connected component sizes must still match.
            bool good=dm<=0.0005 && dp<=0.010001 && ds<=0.00002;
            for (NSString* key in @[@"region",@"meanOk",@"p999Ok",@"ssimOk",@"regionOk",@"ok"])
                good=good && [got[key] isEqual:c[key]];
            failed+=!good;
            std::printf("%s: %s | mean %.8g/%.8g p999 %.2f/%.2f SSIM %.9g/%.9g region %u/%u\n",name.UTF8String,good?"PASS":"FAIL",
                [got[@"mean"] doubleValue],[c[@"mean"] doubleValue],[got[@"p999"] doubleValue],[c[@"p999"] doubleValue],
                [got[@"ssim"] doubleValue],[c[@"ssim"] doubleValue],[got[@"region"] unsignedIntValue],[c[@"region"] unsignedIntValue]);
            // Same scratch, reset histogram/flags, ignore alpha, exact identity.
            NSDictionary* identical=evaluator.evaluate(queue,a,a);
            check([identical[@"ok"] boolValue] && [identical[@"mean"] doubleValue]==0 &&
                  [identical[@"p999"] doubleValue]==0 && [identical[@"ssim"] doubleValue]==1 &&
                  [identical[@"region"] unsignedIntValue]==0,"same-size identity/reset");
        }}
        // Invalid textures must throw before encoding.
        auto reject=[&](MTLPixelFormat format, uint32_t w, uint32_t h) {
            MTLTextureDescriptor* d=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:format width:w height:h mipmapped:NO];
            d.usage=MTLTextureUsageShaderRead;
            id<MTLTexture> t=[device newTextureWithDescriptor:d]; check(t!=nil,"invalid-input test texture");
            bool rejected=false;
            try { (void)evaluator.evaluate(queue,t,t); } catch (const std::runtime_error&) { rejected=true; }
            check(rejected,"invalid input was accepted");
        };
        reject(MTLPixelFormatRGBA8Unorm_sRGB,2,2); reject(MTLPixelFormatBGRA8Unorm,2,2);
        reject(MTLPixelFormatRGBA8Unorm,3025,1);
        std::printf("%lu Java/GPU synthetic comparisons, %u failures\n",(unsigned long)cases.count,failed);
        return failed ? 1 : 0;
    } catch (const std::exception& e) { std::fprintf(stderr,"%s\n",e.what()); return 1; } }
}
