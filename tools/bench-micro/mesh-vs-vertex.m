// Microbench: N quads (48-byte records) drawn by vertex pulling (indexed, 4 verts/quad) vs mesh shader (1 thread/quad).
#import <Metal/Metal.h>
#import <Foundation/Foundation.h>
#include <stdlib.h>
static NSString *src = @"#include <metal_stdlib>\nusing namespace metal;\n"
"struct V { float4 pos [[position]]; float4 col; float2 uv; float fog; };\n"
"struct U { float4x4 m; float3 cam; };\n"
"static V vtx(uint4 a, uint4 b, uint4 c, uint k, constant U& u, texture2d<float> lm) {\n"
"  float fa = (k==1u||k==2u)?1.0:0.0; float fb = (k>=2u)?1.0:0.0;\n"
"  float3 p = float3(float(a.x&1023u), float((a.x>>10u)&1023u), float((a.x>>20u)&1023u));\n"
"  float l1 = float(int(a.y<<18u)>>20); float l2 = float(int(a.y<<4u)>>20);\n"
"  uint x1 = a.y&3u, x2=(a.y>>14u)&3u;\n"
"  p += fa*l1*float3(x1==0u,x1==1u,x1==2u); p += fb*l2*float3(x2==0u,x2==1u,x2==2u);\n"
"  p = p*float(1u<<((a.y>>28u)&7u))*0.03125 - u.cam;\n"
"  uint col = k==0u?a.z:k==1u?a.w:k==2u?b.x:b.y;\n"
"  uint lw = (k<2u?b.z:b.w)>>((k&1u)*16u);\n"
"  V o; o.pos = u.m*float4(p,1.0);\n"
"  o.col = unpack_unorm4x8_to_float(col) * lm.read(uint2(lw&15u,(lw>>8u)&15u));\n"
"  o.uv = float2(float(c.x&1048575u), float(c.y&1048575u))/65536.0 + fa*float2(float(int(c.z<<16u)>>16),0)/65536.0;\n"
"  o.fog = length(p); return o; }\n"
"vertex V vmain(uint vid [[vertex_id]], device const uint4* rb [[buffer(0)]], constant U& u [[buffer(1)]], texture2d<float> lm [[texture(0)]]) {\n"
"  uint q = vid>>2u; return vtx(rb[3u*q], rb[3u*q+1u], rb[3u*q+2u], vid&3u, u, lm); }\n"
"using MeshT = metal::mesh<V, void, 128, 64, topology::triangle>;\n"
"[[mesh]] void mmain(MeshT m, uint tid [[thread_index_in_threadgroup]], uint gid [[threadgroup_position_in_grid]], device const uint4* rb [[buffer(0)]], constant U& u [[buffer(1)]], texture2d<float> lm [[texture(0)]], constant uint& n [[buffer(2)]]) {\n"
"  uint q = gid*32u + tid; uint cnt = min(32u, n - gid*32u);\n"
"  if (tid == 0u) m.set_primitive_count(2u*cnt);\n"
"  if (tid < cnt) { uint4 a=rb[3u*q], b=rb[3u*q+1u], c=rb[3u*q+2u];\n"
"    for (uint k=0u;k<4u;k++) m.set_vertex(4u*tid+k, vtx(a,b,c,k,u,lm));\n"
"    uint i=6u*tid, v=4u*tid; m.set_index(i,v); m.set_index(i+1u,v+1u); m.set_index(i+2u,v+2u); m.set_index(i+3u,v); m.set_index(i+4u,v+2u); m.set_index(i+5u,v+3u); }\n"
"}\n"
"fragment float4 fmain(V in [[stage_in]], texture2d<float> t [[texture(1)]]) { constexpr sampler s(filter::nearest); return t.sample(s, in.uv) * in.col * (1.0 - in.fog*0.0001); }\n";
int main(int argc, char** argv) { @autoreleasepool {
  uint32_t N = argc > 1 ? atoi(argv[1]) : 2000000; int W=3024, H=1964, FR=60;
  id<MTLDevice> d = MTLCreateSystemDefaultDevice(); NSError *e=nil;
  id<MTLLibrary> lib = [d newLibraryWithSource:src options:nil error:&e]; if(!lib){NSLog(@"%@",e);return 1;}
  // Records: quads on a heightfield 1000x1000 cells viewed from above at an angle, sizes 1-2 cells, level 0.
  uint32_t *r = malloc((size_t)N*48); srand(1);
  for (uint32_t q=0;q<N;q++){ uint32_t x=rand()%1000, z=rand()%1000, y=200+rand()%40; uint32_t*o=r+12*q;
    o[0]=(x)|(y<<10)|(z<<20); int l1=32+ (rand()%2)*32, l2=32;
    o[1]=0u|((uint32_t)(l1&4095)<<2)|(2u<<14)|((uint32_t)(l2&4095)<<16)|(0u<<28);
    o[2]=o[3]=o[4]=o[5]=0xFF80C060; o[6]=0x0F0F0F0F; o[7]=0x0F0F0F0F; o[8]=rand()&0xFFFF; o[9]=rand()&0xFFFF; o[10]=16|(0<<16); o[11]=(16<<16); }
  id<MTLBuffer> rb=[d newBufferWithBytes:r length:(size_t)N*48 options:MTLResourceStorageModeShared];
  uint32_t *idx=malloc((size_t)N*6*4); for(uint32_t q=0;q<N;q++){uint32_t v=4*q,*o=idx+6*q;o[0]=v;o[1]=v+1;o[2]=v+2;o[3]=v;o[4]=v+2;o[5]=v+3;}
  id<MTLBuffer> ib=[d newBufferWithBytes:idx length:(size_t)N*24 options:MTLResourceStorageModeShared];
  float m[16]={0}; float f=1.0/tanf(0.61f), asp=(float)W/H; // perspective looking down -z then tilted
  struct { float m[16]; float cam[4]; } u; memset(&u,0,sizeof u);
  // simple view: camera at (500, 300, 1100) looking towards -z with 30 deg pitch down
  float cp=cosf(0.5f), sp=sinf(0.5f); float V[16]={1,0,0,0, 0,cp,sp,0, 0,-sp,cp,0, 0,0,0,1};
  float n=0.05f; float P[16]={f/asp,0,0,0, 0,f,0,0, 0,0,0,-1, 0,0,n,0};
  for(int i=0;i<4;i++)for(int j=0;j<4;j++){float s=0;for(int k=0;k<4;k++)s+=P[k*4+j]*V[i*4+k];u.m[i*4+j]=s;}
  u.cam[0]=500;u.cam[1]=getenv("BEHIND")?-5000:300;u.cam[2]=1100; (void)m;
  MTLTextureDescriptor*td=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:16 height:16 mipmapped:NO];
  id<MTLTexture> lm=[d newTextureWithDescriptor:td]; uint8_t px[1024]; memset(px,200,1024); [lm replaceRegion:MTLRegionMake2D(0,0,16,16) mipmapLevel:0 withBytes:px bytesPerRow:64];
  MTLTextureDescriptor*ad=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:1024 height:1024 mipmapped:NO];
  id<MTLTexture> at=[d newTextureWithDescriptor:ad]; uint8_t*ap=malloc(1024*1024*4); for(int i=0;i<1024*1024*4;i++)ap[i]=rand(); [at replaceRegion:MTLRegionMake2D(0,0,1024,1024) mipmapLevel:0 withBytes:ap bytesPerRow:4096];
  MTLTextureDescriptor*cd=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:W height:H mipmapped:NO]; cd.usage=MTLTextureUsageRenderTarget; cd.storageMode=MTLStorageModePrivate;
  id<MTLTexture> ct=[d newTextureWithDescriptor:cd];
  MTLTextureDescriptor*dd=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatDepth32Float width:W height:H mipmapped:NO]; dd.usage=MTLTextureUsageRenderTarget; dd.storageMode=MTLStorageModePrivate;
  id<MTLTexture> dt=[d newTextureWithDescriptor:dd];
  MTLDepthStencilDescriptor*dsd=[MTLDepthStencilDescriptor new]; dsd.depthCompareFunction=MTLCompareFunctionGreaterEqual; dsd.depthWriteEnabled=YES;
  id<MTLDepthStencilState> ds=[d newDepthStencilStateWithDescriptor:dsd];
  MTLRenderPipelineDescriptor*vd=[MTLRenderPipelineDescriptor new]; vd.vertexFunction=[lib newFunctionWithName:@"vmain"]; vd.fragmentFunction=[lib newFunctionWithName:@"fmain"];
  vd.colorAttachments[0].pixelFormat=MTLPixelFormatRGBA8Unorm; vd.depthAttachmentPixelFormat=MTLPixelFormatDepth32Float;
  id<MTLRenderPipelineState> vp=[d newRenderPipelineStateWithDescriptor:vd error:&e]; if(!vp){NSLog(@"%@",e);return 1;}
  MTLMeshRenderPipelineDescriptor*md=[MTLMeshRenderPipelineDescriptor new]; md.meshFunction=[lib newFunctionWithName:@"mmain"]; md.fragmentFunction=vd.fragmentFunction;
  md.colorAttachments[0].pixelFormat=MTLPixelFormatRGBA8Unorm; md.depthAttachmentPixelFormat=MTLPixelFormatDepth32Float; md.maxTotalThreadsPerMeshThreadgroup=32;
  id<MTLRenderPipelineState> mp=[d newRenderPipelineStateWithMeshDescriptor:md options:0 reflection:nil error:&e]; if(!mp){NSLog(@"%@",e);return 1;}
  id<MTLCommandQueue> cq=[d newCommandQueue];
  for (int mode=0; mode<4; mode++) { int mesh = mode&1; double sum=0; int cnt=0;
    for (int fr=0; fr<FR; fr++) {
      id<MTLCommandBuffer> cb=[cq commandBuffer];
      MTLRenderPassDescriptor*rp=[MTLRenderPassDescriptor renderPassDescriptor]; rp.colorAttachments[0].texture=ct; rp.colorAttachments[0].loadAction=MTLLoadActionClear; rp.colorAttachments[0].storeAction=MTLStoreActionStore;
      rp.depthAttachment.texture=dt; rp.depthAttachment.loadAction=MTLLoadActionClear; rp.depthAttachment.clearDepth=0; rp.depthAttachment.storeAction=MTLStoreActionDontCare;
      id<MTLRenderCommandEncoder> en=[cb renderCommandEncoderWithDescriptor:rp]; [en setDepthStencilState:ds]; [en setCullMode:MTLCullModeNone];
      if(!mesh){ [en setRenderPipelineState:vp]; [en setVertexBuffer:rb offset:0 atIndex:0]; [en setVertexBytes:&u length:sizeof u atIndex:1]; [en setVertexTexture:lm atIndex:0];
        [en setFragmentTexture:at atIndex:1]; [en drawIndexedPrimitives:MTLPrimitiveTypeTriangle indexCount:(NSUInteger)N*6 indexType:MTLIndexTypeUInt32 indexBuffer:ib indexBufferOffset:0]; }
      else { [en setRenderPipelineState:mp]; [en setMeshBuffer:rb offset:0 atIndex:0]; [en setMeshBytes:&u length:sizeof u atIndex:1]; [en setMeshTexture:lm atIndex:0]; [en setMeshBytes:&N length:4 atIndex:2];
        [en setFragmentTexture:at atIndex:1]; [en drawMeshThreadgroups:MTLSizeMake((N+31)/32,1,1) threadsPerObjectThreadgroup:MTLSizeMake(1,1,1) threadsPerMeshThreadgroup:MTLSizeMake(32,1,1)]; }
      [en endEncoding]; [cb commit]; [cb waitUntilCompleted];
      if (fr >= 10) { sum += cb.GPUEndTime - cb.GPUStartTime; cnt++; } }
    printf("%s N=%u: %.3f ms/frame\n", mesh?"mesh  ":"vertex", N, 1000.0*sum/cnt); }
  return 0; } }
