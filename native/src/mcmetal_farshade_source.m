#import "mcmetal_farshade_source.h"

static NSString *fail(NSError **error, NSString *reason) {
    if (error) *error = [NSError errorWithDomain:@"mcmetal.far.shade.source" code:1
        userInfo:@{NSLocalizedDescriptionKey:reason}];
    return nil;
}
static NSRegularExpression *rx(NSString *pattern) {
    return [NSRegularExpression regularExpressionWithPattern:pattern options:0 error:NULL];
}
static NSString *sub(NSString *s, NSString *pattern, NSString *replacement) {
    return [rx(pattern) stringByReplacingMatchesInString:s options:0 range:NSMakeRange(0,s.length)
        withTemplate:replacement];
}
static NSString *once(NSString *s, NSString *old, NSString *replacement) {
    if (!s || [s componentsSeparatedByString:old].count != 2) return nil;
    return [s stringByReplacingOccurrencesOfString:old withString:replacement];
}
static NSString *uniformName(NSString *source, NSString *type) {
    NSString *pattern = [NSString stringWithFormat:@"constant %@& ([A-Za-z_][A-Za-z_0-9]*) \\[\\[buffer",type];
    NSTextCheckingResult *match = [rx(pattern) firstMatchInString:source options:0 range:NSMakeRange(0,source.length)];
    return match ? [source substringWithRange:[match rangeAtIndex:1]] : nil;
}
static NSString *namespaceSource(NSString *source, NSString *name) {
    // Header/pragma placement changes only; shading bodies stay verbatim.
    source = sub(source,@"(?m)^#(?:include|pragma)[^\\n]*\\n",@"");
    source = sub(source,@" \\[\\[[^\\]]*\\]\\]",@"");
    return [NSString stringWithFormat:@"namespace %@ {\n%@\n}\n",name,source];
}
static NSString *vertexHelper(NSString *source, NSString *entry, BOOL compact, NSError **error) {
    NSString *terrain = uniformName(source,@"TerrainUniform");
    if (!terrain) return fail(error,@"Missing admitted terrain uniform");
    NSString *head = [NSString stringWithFormat:@"vertex main0_out %@(uint mcmtl_vid [[vertex_id]], device const uint* mcmtl_vb [[buffer(30)]], device const int4* mcmtl_sb [[buffer(29)]], device const uint* mcmtl_quads [[buffer(28)]], device const uint2* mcmtl_items [[buffer(27)]], ",entry];
    source = once(source,head,@"inline main0_out shade_vertex(device const uint* vb, device const int4* sb, uint arenaQuad, uint corner, uint section, ");
    NSString *fetch = compact ? [NSString stringWithFormat:@"mcmtl_fetch_compact((device const uint4*)mcmtl_vb, mcmtl_sb, (mcmtl_item.x >> 2u) + (mcmtl_e & 63u), mcmtl_vid & 3u, mcmtl_item.y, %@.TextureSize)",terrain]
        : @"mcmtl_fetch(mcmtl_vb, mcmtl_sb, mcmtl_item.x + 4u * (mcmtl_e & 63u) + (mcmtl_vid & 3u), mcmtl_item.y)";
    NSString *direct = compact ? [NSString stringWithFormat:@"mcmtl_fetch_compact((device const uint4*)vb, sb, arenaQuad, corner, section, %@.TextureSize)",terrain]
        : @"mcmtl_fetch(vb, sb, 4u * arenaQuad + corner, section)";
    source = once(source,@"    uint mcmtl_e = mcmtl_quads[mcmtl_vid >> 2u];\n    uint2 mcmtl_item = mcmtl_items[mcmtl_e >> 6u];\n",@"");
    source = once(source,fetch,direct);
    return source ? namespaceSource(source,compact ? @"farshade_compact" : @"farshade_block")
        : fail(error,@"Admitted vertex adaptation anchor changed");
}
static NSString *fragmentHelper(NSString *source, NSString *entry, MCMTLFarShaderLayer layer, NSError **error) {
    source = once(source,[NSString stringWithFormat:@"fragment main0_out %@(",entry],@"inline main0_out shade_fragment(");
    source = once(source,@"sampler Sampler0Smplr [[sampler(0)]])",
        @"sampler Sampler0Smplr [[sampler(0)]], float2 dUVdx, float2 dUVdy, thread bool& farshadeDiscarded)");
    source = once(source,@"dfdx(in.texCoord0)",@"dUVdx");
    source = once(source,@"dfdy(in.texCoord0)",@"dUVdy");
    if (layer == MCMTLFarShaderCutout)
        source = once(source,@"discard_fragment();",@"farshadeDiscarded = true; return main0_out{};");
    return source ? namespaceSource(source,@"farshade_fs") : fail(error,@"Admitted fragment adaptation anchor changed");
}

static NSString *const wrapper = @
"struct Lookup { array<device const uint*,64> vertices [[id(0)]]; };\n"
"struct ShadeParams { uint4 counts; uint2 viewport; uint layer, flags; };\n"
"struct ShadeVertex { float4 position [[position]]; };\n"
"struct ShadeResult { float4 color [[color(0)]]; uint mask [[color(2)]]; float depth [[color(1)]]; };\n"
"inline ShadeResult shadeCount(ShadeResult result, device atomic_uint *counters, uint reason) {\n"
"  atomic_fetch_add_explicit(counters+reason,1u,memory_order_relaxed); return result;\n"
"}\n"
"struct Corner { float4 clip, color; float2 uv; float3 fog; int2 tile; };\n"
"void shadeSample(device atomic_uint *counters, uint face, uint2 xy, uint triangle,\n"
"  uint layout, uint section, uint arena, uint q, float area, float roundoff, float3 lengths,\n"
"  uint2 viewport, thread const float2 *screen, thread const Corner *corners,\n"
"  device const uint *vb, device const int4 *sections, float capturedDepth, uint flags) {\n"
"  uint expected=0u;\n"
"  // Retry only spurious CAS failure; never wait for the winning invocation.\n"
"  while (!atomic_compare_exchange_weak_explicit(counters+16,&expected,1u,memory_order_relaxed,memory_order_relaxed))\n"
"    if (expected!=0u) return;\n"
"  device uint *s=(device uint *)(counters+16);\n"
"  s[1]=9u; s[2]=face; s[3]=xy.x; s[4]=xy.y; s[5]=triangle;\n"
"  s[6]=layout; s[7]=section; s[8]=arena; s[9]=q;\n"
"  s[10]=as_type<uint>(area); s[11]=as_type<uint>(roundoff);\n"
"  for (uint k=0;k<3u;k++) s[12+k]=as_type<uint>(lengths[k]);\n"
"  s[15]=viewport.x; s[16]=viewport.y;\n"
"  for (uint k=0;k<4u;k++) {\n"
"    for (uint j=0;j<2u;j++) s[17+2*k+j]=as_type<uint>(screen[k][j]);\n"
"    for (uint j=0;j<4u;j++) s[25+4*k+j]=as_type<uint>(corners[k].clip[j]);\n"
"  }\n"
"  uint stride=layout==1u?28u:12u;\n"
"  // The admitted face bound includes this entire record. Widen before the\n"
"  // product: compact's original helper addresses uint4, this copy addresses uint.\n"
"  for (uint k=0;k<28u;k++) s[41+k]=k<stride?vb[ulong(q)*ulong(stride)+k]:0u;\n"
"  uint4 sectionBits=as_type<uint4>(sections[section]);\n"
"  for (uint k=0;k<4u;k++) s[69+k]=sectionBits[k];\n"
"  s[73]=as_type<uint>(capturedDepth); s[74]=flags;\n"
"  for (uint k=75u;k<80u;k++) s[k]=0u;\n"
"}\n"
"// Bit tests also survive fast-math compilation of the surrounding original code.\n"
"bool finite1(float x) { return (as_type<uint>(x)&0x7f800000u)!=0x7f800000u; }\n"
"bool finite2(float2 x) { return all((as_type<uint2>(x)&0x7f800000u)!=0x7f800000u); }\n"
"bool finite3(float3 x) { return all((as_type<uint3>(x)&0x7f800000u)!=0x7f800000u); }\n"
"bool finite4(float4 x) { return all((as_type<uint4>(x)&0x7f800000u)!=0x7f800000u); }\n"
"vertex ShadeVertex farshade_vertex(uint vertexID [[vertex_id]]) {\n"
"  float2 p=vertexID==0 ? float2(-1,-1) : vertexID==1 ? float2(3,-1) : float2(-1,3);\n"
"  return {float4(p,0,1)};\n"
"}\n"
"float edge(float2 a, float2 b, float2 p) { float2 e=b-a, q=p-a; return e.x*q.y-e.y*q.x; }\n"
"float3 barycentric(float2 p, float2 a, float2 b, float2 c, float area) {\n"
"  return float3(edge(b,c,p),edge(c,a,p),edge(a,b,p))/area;\n"
"}\n"
"bool perspective(float3 b, float3 inverseW, thread float3 &w) {\n"
"  float3 q=b*inverseW; float sum=q.x+q.y+q.z;\n"
"  if (!finite3(q) || !finite1(sum) || sum<=1e-6f*(abs(q.x)+abs(q.y)+abs(q.z))) return false;\n"
"  w=q/sum; return finite3(w);\n"
"}\n"
"bool helperUV(float2 p, float2 a, float2 b, float2 c, float area, float3 inverseW,\n"
"              float2 ua, float2 ub, float2 uc, thread float2 &uv) {\n"
"  float3 w; if (!perspective(barycentric(p,a,b,c,area),inverseW,w)) return false;\n"
"  uv=w.x*ua+w.y*ub+w.z*uc; return finite2(uv);\n"
"}\n"
"// Narrow exact-SOLID recovery for small axis-aligned BLOCK rectangles. The\n"
"// captured thin-face hardware oracle uses 8 fractional raster bits. Require\n"
"// stable rounding, one strict owner, conditioned local edges AND agreement\n"
"// with authoritative hardware depth; never relax the general area guard.\n"
"bool thinRasterSolve(device const uint *vb, uint q, uint2 xy, float capturedDepth,\n"
"  thread const Corner *corners, thread float2 *screen, thread uint3 &chosen,\n"
"  thread float3 &bary, thread float &selectedArea) {\n"
"  float3 pos[4];\n"
"  for (uint k=0;k<4u;k++) {device const uint *v=vb+28u*q+7u*k; pos[k]=float3(as_type<float>(v[0]),as_type<float>(v[1]),as_type<float>(v[2])); if(!finite3(pos[k])) return false;}\n"
"  float3 e=pos[1]-pos[0], f=pos[3]-pos[0];\n"
"  if (any(e!=pos[2]-pos[3]) || any(f!=pos[2]-pos[1]) ||\n"
"      uint(e.x!=0)+uint(e.y!=0)+uint(e.z!=0)!=1u ||\n"
"      uint(f.x!=0)+uint(f.y!=0)+uint(f.z!=0)!=1u || any((e!=0)&(f!=0))) return false;\n"
"  float2 raster[4], lo=screen[0], hi=screen[0];\n"
"  for (uint k=0;k<4u;k++) {\n"
"    float2 grid=rint(screen[k]*256.0f); raster[k]=grid*(1.0f/256.0f);\n"
"    float2 boundary=(0.5f-abs(screen[k]*256.0f-grid))*(1.0f/256.0f);\n"
"    if (any(boundary<=2.0f*1.1920928955078125e-7f*max(abs(screen[k]),float2(1)))) return false;\n"
"    lo=min(lo,raster[k]); hi=max(hi,raster[k]);\n"
"  }\n"
"  float2 center=float2(xy)+0.5f;\n"
"  // Explicitly bound BOTH edges and sample-relative coordinates, including\n"
"  // malformed IDs naming a far-away face. In 1/256-pixel units magnitudes\n"
"  // are <=2048: products <=2^22, differences <=2^23, exactly representable.\n"
"  if (any(hi-lo>8.0f) || any(center<lo) || any(center>hi)) return false;\n"
"  uint3 tris[2]={uint3(0,1,2),uint3(2,3,0)}; uint hits=0u;\n"
"  for(uint t=0;t<2u;t++) {\n"
"    uint3 ix=tris[t]; float2 a=raster[ix.x]-center,b=raster[ix.y]-center,c=raster[ix.z]-center;\n"
"    float area=edge(a,b,c); float scale=max(dot(b-a,b-a),max(dot(c-a,c-a),dot(c-b,c-b)));\n"
"    if (!finite1(area) || !finite1(scale) || scale<=0 || abs(area)<=64.0f*1.1920928955078125e-7f*scale) return false;\n"
"    float3 bc=barycentric(float2(0),a,b,c,area);\n"
"    if (!finite3(bc)) return false;\n"
"    if (all(bc>0)) {chosen=ix; bary=bc; selectedArea=area; ++hits;}\n"
"  }\n"
"  if (hits!=1u) return false;\n"
"  float3 z=float3(corners[chosen.x].clip.z/corners[chosen.x].clip.w,\n"
"    corners[chosen.y].clip.z/corners[chosen.y].clip.w,corners[chosen.z].clip.z/corners[chosen.z].clip.w);\n"
"  float predicted=dot(bary,z); if(!finite1(predicted) || predicted<=0 || predicted>1) return false;\n"
"  uint a=as_type<uint>(predicted), b=as_type<uint>(capturedDepth);\n"
"  if (max(a,b)-min(a,b)>2u) return false;\n"
"  for(uint k=0;k<4u;k++) screen[k]=raster[k];\n"
"  return true;\n"
"}\n";

static NSString *cornerCall(NSString *space, unsigned vary) {
    return [NSString stringWithFormat:
        @"      auto v=%@::shade_vertex(vb,sections,q,k,section,\n"
         "        *((constant %@::Projection*)projection), *((constant %@::Globals*)globals),\n"
         "        *((constant %@::TerrainUniform*)terrain),lightmap,lightSampler%@);\n"
         "      corners[k]={v.gl_Position,float4(v.vertexColor),v.texCoord0,%@,v.greedyTile};\n",
        space,space,space,space,
        vary ? [NSString stringWithFormat:@", *((constant %@::Fog*)fog)",space] : @"",
        vary ? @"float3(float2(v.mcmtlFogVis),0)" : @"float3(v.sphericalVertexDistance,v.cylindricalVertexDistance,v.chunkVisibility)"];
}

static NSString *const fragmentStart = @
"fragment ShadeResult farshade_fragment(ShadeVertex pixel [[stage_in]],\n"
"  constant uint *projection [[buffer(0)]], constant uint *globals [[buffer(1)]],\n"
"  constant uint *fog [[buffer(2)]], constant uint *terrain [[buffer(5)]],\n"
"  constant Lookup &lookup [[buffer(7)]], constant uint4 *arenas [[buffer(8)]],\n"
"  device const uint *directory [[buffer(9)]], device const uint *tails [[buffer(10)]],\n"
"  device const int4 *sections [[buffer(11)]], constant ShadeParams &p [[buffer(12)]],\n"
"  texture2d<float> atlas [[texture(0)]], sampler atlasSampler [[sampler(0)]],\n"
"  texture2d<float> lightmap [[texture(2)]], sampler lightSampler [[sampler(2)]],\n"
"  texture2d<uint,access::read> ids [[texture(3)]], texture2d<uint,access::read> validity [[texture(4)]],\n"
"  texture2d<float,access::read> sourceDepth [[texture(5)]]) {\n"
"  ShadeResult bad={float4(0),1u,0};\n"
"  if ((p.flags&~3u) || any(p.viewport==0u) || any(p.viewport>16384u) || p.counts.w>64u ||\n"
"      ids.get_width()!=p.viewport.x || ids.get_height()!=p.viewport.y ||\n"
"      validity.get_width()!=p.viewport.x || validity.get_height()!=p.viewport.y ||\n"
"      sourceDepth.get_width()!=p.viewport.x || sourceDepth.get_height()!=p.viewport.y ||\n"
"      !finite2(pixel.position.xy) || any(pixel.position.xy<0.0f) || any(pixel.position.xy>=float2(p.viewport))) return FARSHADE_RESULT(bad,4u);\n"
"  uint2 xy=uint2(pixel.position.xy); uint prior=validity.read(xy).x;\n"
"  if (prior<2u) { bad.mask=prior; return FARSHADE_RESULT(bad,2u+prior); }\n"
"  uint face=ids.read(xy).x; float capturedDepth=sourceDepth.read(xy).x;\n"
"  if (!finite1(capturedDepth)) return FARSHADE_RESULT(bad,4u);\n"
"  if (!face) { if (prior!=3u || capturedDepth!=0) return FARSHADE_RESULT(bad,4u);\n"
"    ShadeResult sky={float4(0),3u,0}; return FARSHADE_RESULT(sky,1u); }\n"
"  if (prior!=2u || capturedDepth<=0 || capturedDepth>1) return FARSHADE_RESULT(bad,4u);\n"
"  if ((face>>6)>=p.counts.x) return FARSHADE_RESULT(bad,5u);\n"
"  bool exact=(p.flags&1u)!=0u;\n"
"  uint owner=directory[face>>6];\n"
"  if (owner&0x80000000u) {\n"
"    uint offset=owner&0x7fffffffu;\n"
"    if ((offset&63u) || p.counts.y<64u || offset>p.counts.y-64u) return FARSHADE_RESULT(bad,5u);\n"
"    owner=tails[offset+(face&63u)];\n"
"  }\n"
"  uint sectionPlus=owner&0x001fffffu, arena=(owner>>21)&63u;\n"
"  if ((owner&0xf0000000u) || !sectionPlus || sectionPlus>p.counts.z || arena>=p.counts.w || ((owner>>27)&1u)!=p.layer) return FARSHADE_RESULT(bad,5u);\n"
"  uint section=sectionPlus-1u; uint4 desc=arenas[arena];\n"
"  if (desc.w || face<=desc.x || (desc.y!=1u && desc.y!=2u)) return FARSHADE_RESULT(bad,5u);\n"
"  uint q=face-desc.x-1u;\n"
"  // Original helpers use uint address arithmetic; bound every product too.\n"
"  if (q>=desc.z || q>0x3fffffffu || (desc.y==1u && q>(0xffffffffu-27u)/28u)) return FARSHADE_RESULT(bad,5u);\n"
"  device const uint *vb=lookup.vertices[arena]; if (vb==nullptr) return FARSHADE_RESULT(bad,5u);\n"
"  Corner corners[4];\n"
"  for (uint k=0;k<4u;k++) {\n"
"    if (desc.y==1u) {\n";

static NSString *const reconstruct = @
"    }\n"
"  }\n"
"  float2 screen[4];\n"
"  for (uint k=0;k<4u;k++) {\n"
"    Corner v=corners[k];\n"
"    if (!finite4(v.clip) || !finite4(v.color) || !finite2(v.uv) || !finite3(v.fog)) return FARSHADE_RESULT(bad,6u);\n"
"    if (v.clip.w<=0 || v.clip.z<=0 || v.clip.z>v.clip.w || (!exact && any(abs(v.clip.xy)>v.clip.w))) return FARSHADE_RESULT(bad,7u);\n"
"    if (any(v.tile!=corners[0].tile)) return FARSHADE_RESULT(bad,8u);\n"
"    screen[k]=(v.clip.xy/v.clip.w*float2(0.5f,-0.5f)+0.5f)*float2(p.viewport);\n"
"    if (!finite2(screen[k])) return FARSHADE_RESULT(bad,6u);\n"
"  }\n"
"  float2 center=float2(xy)+0.5f;\n"
"  uint3 triangles[2]={uint3(0,1,2),uint3(2,3,0)};\n"
"  uint3 chosen=uint3(0); float3 bary=0; float selectedArea=0; uint hits=0;\n"
"  float bestDistance=-INFINITY;\n"
"  for (uint t=0;t<2u;t++) {\n"
"    uint3 ix=triangles[t]; float2 a=screen[ix.x], b=screen[ix.y], c=screen[ix.z];\n"
"    float area=edge(a,b,c); float3 lengths=float3(length(c-b),length(a-c),length(b-a));\n"
"    // Covers raster subpixel rounding plus float operations at this viewport scale.\n"
"    float roundoff=16.0f*1.1920928955078125e-7f*max(float(max(p.viewport.x,p.viewport.y)),\n"
"      max(max(abs(a.x),abs(a.y)),max(max(abs(b.x),abs(b.y)),max(abs(c.x),abs(c.y)))));\n"
"    float margin=1.0f/256.0f+roundoff;\n"
"    if (!finite1(area) || !finite3(lengths) || any(lengths<=0) ||\n"
"        abs(area)<=(exact?roundoff:margin)*max(lengths.x,max(lengths.y,lengths.z))) {\n"
"      if (exact && p.layer==0u && desc.y==1u && thinRasterSolve(vb,q,xy,capturedDepth,corners,screen,chosen,bary,selectedArea)) {hits=1u; break;}\n"
"      FARSHADE_SAMPLE(); return FARSHADE_RESULT(bad,9u);\n"
"    }\n"
"    float3 bcoords=barycentric(center,a,b,c,area);\n"
"    if (!finite3(bcoords)) return FARSHADE_RESULT(bad,10u);\n"
"    float3 distance=bcoords*abs(area)/lengths;\n"
"    if (!finite3(distance)) return FARSHADE_RESULT(bad,10u);\n"
"    if (exact) {\n"
"      // Hardware IDs establish coverage. Choose the nearest inside primitive;\n"
"      // retain extrapolated weights at rounded raster edges (never clamp UVs).\n"
"      float nearest=min(distance.x,min(distance.y,distance.z));\n"
"      float2 diagonal=area>0 ? screen[0]-screen[2] : screen[2]-screen[0];\n"
"      bool firstOwnsTie=diagonal.y<0 || (diagonal.y==0 && diagonal.x>0);\n"
"      if (nearest>=-margin && (!hits || nearest>bestDistance || (nearest==bestDistance && !firstOwnsTie))) {\n"
"        chosen=ix; bary=bcoords; selectedArea=area; bestDistance=nearest; hits=1u;\n"
"      }\n"
"    } else if (all(distance>margin)) { chosen=ix; bary=bcoords; selectedArea=area; ++hits; }\n"
"    else if (all(distance>=-margin)) return FARSHADE_RESULT(bad,10u); // Includes ambiguous shared-diagonal pixels.\n"
"  }\n"
"  if (hits!=1u) return FARSHADE_RESULT(bad,10u);\n"
"  Corner a=corners[chosen.x], b=corners[chosen.y], c=corners[chosen.z];\n"
"  float3 inverseW=1.0f/float3(a.clip.w,b.clip.w,c.clip.w), weights;\n"
"  if (!perspective(bary,inverseW,weights)) return FARSHADE_RESULT(bad,11u);\n"
"  float depth=exact ? capturedDepth : dot(bary,float3(a.clip.z,b.clip.z,c.clip.z)*inverseW);\n"
"  if (!finite1(depth) || depth<=0 || depth>1) return FARSHADE_RESULT(bad,11u);\n"
"  float2 sa=screen[chosen.x], sb=screen[chosen.y], sc=screen[chosen.z];\n"
"  float2 base=float2(xy&uint2(~1u))+0.5f, uv[4];\n"
"  for (uint lane=0;lane<4u;lane++)\n"
"    if (!helperUV(base+float2(lane&1u,lane>>1u),sa,sb,sc,selectedArea,inverseW,a.uv,b.uv,c.uv,uv[lane])) return FARSHADE_RESULT(bad,12u);\n"
"  float2 dUVdx=(xy.y&1u) ? uv[3]-uv[2] : uv[1]-uv[0];\n"
"  float2 dUVdy=(xy.x&1u) ? uv[3]-uv[1] : uv[2]-uv[0];\n"
"  if (!finite2(dUVdx) || !finite2(dUVdy)) return FARSHADE_RESULT(bad,12u);\n"
"  farshade_fs::main0_in in={};\n"
"  in.texCoord0=uv[(xy.y&1u)*2u+(xy.x&1u)]; in.greedyTile=a.tile;\n"
"  float4 color=weights.x*a.color+weights.y*b.color+weights.z*c.color;\n"
"  float3 fogValue=weights.x*a.fog+weights.y*b.fog+weights.z*c.fog;\n"
"  if (!finite4(color) || !finite3(fogValue)) return FARSHADE_RESULT(bad,13u);\n";

NSString *mcmtl_farshade_source(NSString *blockVS, NSString *blockEntry,
    NSString *compactVS, NSString *compactEntry, NSString *fragment,
    NSString *fragmentEntry, MCMTLFarShaderLayer layer, NSError **error) {
    if (error) *error=nil;
    // Admission ONLY: do not read the ID-injected vertex/fragment results.
    if (!mcmtl_farshader_rewrite(blockVS,fragment,blockEntry,fragmentEntry,MCMTLFarShaderBlock,layer,error) ||
        !mcmtl_farshader_rewrite(compactVS,fragment,compactEntry,fragmentEntry,MCMTLFarShaderCompact,layer,error)) return nil;
    unsigned vary=[fragment containsString:@"half2 mcmtlFogVis"] ? 2 : [fragment containsString:@"mcmtlFogVis"] ? 1 : 0;
    NSString *block=vertexHelper(blockVS,blockEntry,NO,error);
    if (!block) return nil;
    NSString *compact=vertexHelper(compactVS,compactEntry,YES,error);
    if (!compact) return nil;
    NSString *fs=fragmentHelper(fragment,fragmentEntry,layer,error);
    if (!fs) return nil;
    NSMutableString *source=[NSMutableString stringWithString:@"#include <metal_stdlib>\n#include <simd/simd.h>\nusing namespace metal;\n#pragma clang fp reassociate(off)\n#pragma clang diagnostic ignored \"-Wmissing-prototypes\"\n#pragma clang diagnostic ignored \"-Wmissing-braces\"\n"];
    [source appendFormat:@"%@%@%@%@",block,compact,fs,wrapper];
    NSMutableString *entry=[NSMutableString stringWithString:fragmentStart];
    [entry appendString:cornerCall(@"farshade_block",vary)];
    [entry appendString:@"    } else {\n"];
    [entry appendString:cornerCall(@"farshade_compact",vary)];
    [entry appendString:reconstruct];
    [entry appendFormat:@"  in.vertexColor=%@(color);\n",vary==2 ? @"half4" : @"float4"];
    if (vary) [entry appendFormat:@"  in.mcmtlFogVis=%@(fogValue.xy);\n",vary==2 ? @"half2" : @"float2"];
    else [entry appendString:@"  in.sphericalVertexDistance=fogValue.x; in.cylindricalVertexDistance=fogValue.y; in.chunkVisibility=fogValue.z;\n"];
    [entry appendFormat:@"  if (p.layer!=%uu) return FARSHADE_RESULT(bad,4u);\n",(unsigned)layer];
    [entry appendString:@"  bool discarded=false;\n"
        "  auto shaded=farshade_fs::shade_fragment(in,*((constant farshade_fs::Globals*)globals),\n"
        "    *((constant farshade_fs::Fog*)fog),*((constant farshade_fs::TerrainUniform*)terrain),\n"
        "    atlas,atlasSampler,dUVdx,dUVdy,discarded);\n"
        "  if (discarded) return FARSHADE_RESULT(bad,14u);\n"
        "  if (!finite4(shaded.fragColor)) return FARSHADE_RESULT(bad,15u);\n"
        "  ShadeResult result={shaded.fragColor,2u,depth}; return FARSHADE_RESULT(result,0u);\n}\n"];
    // Preprocessing erases counters AND reason expressions from the normal
    // entry; no runtime branch, buffer binding, or atomic exists on that path.
    [source appendFormat:@"#define FARSHADE_RESULT(result,reason) (result)\n#define FARSHADE_SAMPLE() ((void)0)\n%@#undef FARSHADE_RESULT\n#undef FARSHADE_SAMPLE\n",entry];
    NSString *diagnostic=once(entry,@"farshade_fragment(",@"farshade_fragment_diagnostics(");
    diagnostic=once(diagnostic,@"sourceDepth [[texture(5)]])",
        @"sourceDepth [[texture(5)]], device atomic_uint *diagnostics [[buffer(13)]])");
    if (!diagnostic) return fail(error,@"Diagnostic fragment adaptation anchor changed");
    [source appendFormat:@"#define FARSHADE_RESULT(result,reason) shadeCount(result,diagnostics,reason)\n"
        "#define FARSHADE_SAMPLE() do { if (p.flags&2u) shadeSample(diagnostics,face,xy,t,desc.y,section,arena,q,area,roundoff,lengths,p.viewport,screen,corners,vb,sections,capturedDepth,p.flags); } while(false)\n"
        "%@#undef FARSHADE_RESULT\n#undef FARSHADE_SAMPLE\n",diagnostic];
    return [source copy];
}
