// tools/lodray_bench.m, variant "wave": Voxlap-style column marching ("wave surfing"). Appended to the bench's kernel source.
// One SIMD group per screen line through the vertical vanishing point (every such line is the image of one vertical world
// plane through the eye), 32 lanes = 32 consecutive distance segments of that plane's horizontal ray. Pass 1: each lane walks
// its segment's columns and keeps the highest projected column top; an exclusive prefix max over the lanes gives the coverage
// that the nearer segments leave (wave-surfing occlusion); pass 2 (only where something can be visible): walk again front to
// back and write the newly covered rows (side face below the front top, top face between front and back top), each pixel
// resolved by its own ray against the face plane (exact block edges, texture uv). Columns are solid from their top down
// (gaps under canopies are filled: a known approximation of this prototype).
struct WaveP { float4 vp; float4 seg[9]; };   // vp: u_vp, v_vp (px from the centre, v up), line count, u offset
static inline float projV(float r, float y, float3 o, float hu, float hf, float Yu, float Yf, float K, thread bool& ok) {
  float dy = y - o.y;
  float zc = r * hf + dy * Yf;
  ok = zc > 0.05;
  return K * (r * hu + dy * Yu) / max(zc, 0.05);
}
struct Cell { float rin; float rout; float top; uint ci; uint axis; float bound; int s; };
static inline bool cellAt(constant P& p, device const uint* map, float3 o, float2 h, float2 invh, float r, thread Cell& c, device const ushort* tops,
    thread int& lastCh, thread uint& lastE) {
  int R = p.grid.x;
  float wmin = -16.0 * R;
  float2 q = o.xz + h * r + select(float2(-2e-3), float2(2e-3), h > 0.0);
  int2 ch = int2(floor((q - wmin) * (1.0 / 16.0)));
  int dim = 2 * R;
  if (any(ch < 0) || any(ch >= dim)) return false;
  int chi = ch.y * dim + ch.x;
  if (chi != lastCh) { lastCh = chi; lastE = map[uint(chi)]; }
  uint e = lastE;
  float2 lo; float sz;
  if (e == EMPTY) { lo = float2(ch) * 16.0 + wmin; sz = 16.0; c.top = -1e9; c.ci = 0xFFFFFFFFu; c.s = 16; }
  else {
    uint k = e >> 28u; int s = 1 << k; sz = float(s);
    int2 nodeC = (ch >> int(k)) << int(k);
    float2 nOrg = float2(nodeC) * 16.0 + wmin;
    int2 cell = clamp(int2(floor((q - nOrg) / sz)), 0, 15);
    lo = nOrg + float2(cell) * sz;
    c.ci = (e & 0x0FFFFFFFu) * 256u + uint(cell.y * 16 + cell.x);
    uint tp = tops[c.ci];
    c.top = tp == 0u ? -1e9 : float(int(tp) - 64);
    c.s = s;
  }
  float2 tb = (select(lo, lo + sz, h > 0.0) - o.xz) * invh;
  bool xe = tb.x < tb.y;
  c.rin = r;
  c.rout = xe ? tb.x : tb.y;
  c.axis = xe ? 0u : 1u;
  c.bound = xe ? select(lo.x, lo.x + sz, h.x > 0.0) : select(lo.y, lo.y + sz, h.y > 0.0);
  return true;
}
struct Walk { float2 o; float2 h; float2 invh; float wmin; int dim; bool empty; uint base; float fs; float2 nOrg; int2 cell; int lim;
  float2 tMax; float2 tDel; int2 st; int lastCh; uint lastE; float r; uint axisIn; };
static inline bool walkInit(thread Walk& W, device const uint* map, float r) {
  float2 q = W.o + W.h * r + select(float2(-2e-3), float2(2e-3), W.h > 0.0);
  int2 ch = int2(floor((q - W.wmin) * 0.0625));
  if (any(ch < 0) || any(ch >= W.dim)) return false;
  int chi = ch.y * W.dim + ch.x;
  if (chi != W.lastCh) { W.lastCh = chi; W.lastE = map[uint(chi)]; }
  uint e = W.lastE;
  if (e == EMPTY) { W.empty = true; W.fs = 16.0; W.nOrg = float2(ch) * 16.0 + W.wmin; W.cell = int2(0); W.lim = 0; }
  else {
    W.empty = false; uint k = e >> 28u; W.fs = float(1 << k);
    int2 nodeC = (ch >> int(k)) << int(k);
    W.nOrg = float2(nodeC) * 16.0 + W.wmin;
    W.cell = clamp(int2(floor((q - W.nOrg) / W.fs)), 0, 15); W.lim = 15;
    W.base = (e & 0x0FFFFFFFu) * 256u;
  }
  float2 lo = W.nOrg + float2(W.cell) * W.fs;
  W.tMax = (select(lo, lo + W.fs, W.h > 0.0) - W.o) * W.invh;
  W.tDel = W.fs * abs(W.invh);
  W.r = r;
  return true;
}
// current cell's top (block y, -1e9 = none) and exit distance; then advance (re-init at node / chunk borders)
static inline float walkTop(thread Walk& W, device const ushort* tops, thread uint& ci) {
  if (W.empty) return -1e9;
  ci = W.base + uint(W.cell.y * 16 + W.cell.x);
  uint tp = tops[ci];
  return tp == 0u ? -1e9 : float(int(tp) - 64);
}
static inline bool walkNext(thread Walk& W, device const uint* map) {
  if (W.tMax.x < W.tMax.y) { W.r = W.tMax.x; W.tMax.x += W.tDel.x; W.cell.x += W.st.x; W.axisIn = 0u; }
  else { W.r = W.tMax.y; W.tMax.y += W.tDel.y; W.cell.y += W.st.y; W.axisIn = 1u; }
  if (any(W.cell < 0) || any(W.cell > W.lim)) { uint a = W.axisIn; bool ok = walkInit(W, map, W.r); W.axisIn = a; return ok; }
  return true;
}
kernel void waveKernel(constant P& p [[buffer(0)]], device const uint* map [[buffer(1)]], device const uint2* cols [[buffer(4)]],
    device const uint* runs [[buffer(5)]], device atomic_uint* stats [[buffer(7)]], constant WaveP& w [[buffer(10)]], device uint* ids [[buffer(11)]], device float* lineTop [[buffer(14)]], device const ushort* tops [[buffer(15)]],
    texture2d<float, access::write> out [[texture(0)]], texture2d<float> atlas [[texture(1)]],
    uint tid [[thread_position_in_grid]], uint lane [[thread_index_in_simdgroup]]) {
  constexpr sampler smp(filter::linear, mip_filter::linear, address::repeat);
  uint line = tid >> 5u;
  if (float(line) >= w.vp.z) return;
  float Wd = float(out.get_width()), Hd = float(out.get_height());
  float K = Hd * 0.5 / p.misc.y;
  float U = float(line) - w.vp.w + 0.5;
  float Vt = Hd * 0.5;
  float3 o = p.cam.xyz;
  float3 wd = p.fwd.xyz + (U / K) * p.right.xyz + (Vt / K) * p.up.xyz;
  float2 h = wd.xz; float hl = length(h);
  if (hl < 1e-5) return;
  h /= hl;
  float2 invh = 1.0 / h;
  float3 h3 = float3(h.x, 0.0, h.y);
  float hu = dot(h3, p.up.xyz), hf = dot(h3, p.fwd.xyz), Yu = p.up.y, Yf = p.fwd.y;
  float uvp = w.vp.x, vvp = w.vp.y;
  float rA = ((constant float*)w.seg)[lane], rB = ((constant float*)w.seg)[lane + 1u];
  uint iters = 0u;
  // Occlusion in elevation-slope space: sigma = (y - eye y) / r; screen v is a monotonic function of sigma on a fixed line
  // (v = K (hu + sigma Yu) / (hf + sigma Yf)), so wave surfing compares slopes (one reciprocal per cell, no division per corner).
  float maxs = -1e9;
  if ((p.grid.w & 256) != 0) { lineTop[line] = 0; return; }
  Walk W; W.o = o.xz; W.h = h; W.invh = invh; W.wmin = -16.0 * p.grid.x; W.dim = 2 * p.grid.x; W.lastCh = -1; W.lastE = 0u;
  W.st = int2(h.x > 0.0 ? 1 : -1, h.y > 0.0 ? 1 : -1); W.axisIn = 0u;
  bool alive = walkInit(W, map, rA);
  bool first = lane != 0u;
  uint ci = 0u;
  float invIn = 1.0 / max(W.r, 1e-3);
  for (uint i = 0u; i < 4096u && alive && W.r < rB; i++) {
    iters++;
    float top = walkTop(W, tops, ci);
    float invOut = 1.0 / min(W.tMax.x, W.tMax.y);
    float dy = top - o.y;
    if (!first) maxs = max(maxs, dy * (dy > 0.0 ? invOut : invIn));
    first = false;
    invIn = invOut;
    alive = walkNext(W, map);
  }
  float inc = maxs;
  for (ushort d = 1; d < 32; d <<= 1) { float o2 = simd_shuffle_up(inc, d); if (lane >= d) inc = max(inc, o2); }
  float sin_ = simd_shuffle_up(inc, 1);
  if (lane == 0u) sin_ = -1e9;
  float sall = simd_broadcast(inc, 31);
  if (lane == 0u) lineTop[line] = K * (hu + sall * Yu) / max(hf + sall * Yf, 1e-6);
  if ((p.grid.w & 128) != 0) { lineTop[line] = maxs; return; }
  if (maxs <= sin_) { if ((p.grid.w & 1) != 0) atomic_fetch_add_explicit(&stats[0], iters, memory_order_relaxed); return; }
  float sbuf = sin_;
  W.lastCh = -1; W.axisIn = 0u;
  alive = walkInit(W, map, rA);
  first = lane != 0u;
  invIn = 1.0 / max(W.r, 1e-3);
  device uint* lp = ids + line * uint(Hd);
  float sTop = (Hd * 0.5 * hf / K - hu) / (Yu - Hd * 0.5 * Yf / K);   // slope of the screen's top row on this line
  float vbuf = sbuf < -1e8 ? -Hd * 0.5 - 1.0 : K * (hu + sbuf * Yu) / (hf + sbuf * Yf);
  for (uint i = 0u; i < 4096u && alive && W.r < rB && sbuf < sTop; i++) {
    iters++;
    float top = walkTop(W, tops, ci);
    float invOut = 1.0 / min(W.tMax.x, W.tMax.y);
    float dy = top - o.y;
    float s1 = dy * invIn, s2 = dy * invOut;   // front / back corner slopes
    float sEnd = max(s1, s2);
    if (!first && sEnd > sbuf) {
      float v1 = K * (hu + s1 * Yu) / (hf + s1 * Yf), vEnd = K * (hu + sEnd * Yu) / (hf + sEnd * Yf);
      int rowLo = max(int(ceil(Hd * 0.5 - vEnd - 0.5)), 0), rowHi = min(int(floor(Hd * 0.5 - vbuf - 0.5)), int(Hd) - 1);
      if ((p.grid.w & 16) != 0) rowLo = rowHi + 1;
      uint idTop = ci, idSide = ci | ((W.axisIn + 1u) << 26);
      int rowSplit = int(floor(Hd * 0.5 - v1 - 0.5));
      for (int row = rowHi; row >= rowLo; row--) lp[row] = row <= rowSplit ? idTop : idSide;
      sbuf = sEnd; vbuf = vEnd;
    }
    first = false;
    invIn = invOut;
    alive = walkNext(W, map);
  }
  if ((p.grid.w & 1) != 0) atomic_fetch_add_explicit(&stats[0], iters, memory_order_relaxed);
}
kernel void clearKernel(device uint* ids [[buffer(11)]], constant uint2& size [[buffer(12)]], uint2 gid [[thread_position_in_grid]]) {
  if (gid.x < size.x && gid.y < size.y) ids[gid.y * size.x + gid.x] = 0xFFFFFFFFu;
}
// per-pixel resolve: the pixel's own ray against the face its wave line found (exact block edges), material, shading
kernel void resolveKernel(constant P& p [[buffer(0)]], device const uint2* cols [[buffer(4)]], device const uint* runs [[buffer(5)]],
    device const uint* ids [[buffer(11)]], device const int4* nodes [[buffer(13)]], device const float* lineTop [[buffer(14)]],
    constant WaveP& w [[buffer(10)]], texture2d<float, access::write> out [[texture(0)]],
    texture2d<float> atlas [[texture(1)]], uint2 gid [[thread_position_in_grid]]) {
  if (gid.x >= out.get_width() || gid.y >= out.get_height()) return;
  constexpr sampler smp(filter::linear, mip_filter::linear, address::repeat);
  float Hd = float(out.get_height());
  float uu = float(gid.x) + 0.5 - float(out.get_width()) * 0.5, vv = Hd * 0.5 - (float(gid.y) + 0.5);
  float Ut = w.vp.x + (uu - w.vp.x) * (Hd * 0.5 - w.vp.y) / (vv - w.vp.y);
  int line = int(floor(Ut + w.vp.w));
  uint id = 0xFFFFFFFFu;
  if (line >= 0 && float(line) < w.vp.z && vv <= lineTop[line]) id = ids[uint(line) * uint(Hd) + gid.y];
  if (id == 0xFFFFFFFFu) { out.write(float4(0.55, 0.7, 1.0, 1.0), gid); return; }
  float2 size = float2(out.get_width(), out.get_height());
  float3 o = p.cam.xyz; float3 d = rayDir(p, float2(gid) + 0.5, size);
  uint ci = id & 0x03FFFFFFu, face = id >> 26;
  int4 nd = nodes[ci >> 8u];
  int s = 1 << nd.z;
  float2 cmin = float2(nd.xy) + float2(float(ci & 15u), float((ci >> 4u) & 15u)) * float(s);
  uint2 hd = cols[ci];
  float top = float(int(hd.y >> 8u) * s - 64);
  Hit hh;
  if (face == 0u) { hh.t = (top - o.y) / d.y; hh.face = 0u; }
  else if (face == 1u) { hh.t = (cmin.x + (d.x < 0.0 ? float(s) : 0.0) - o.x) / d.x; hh.face = 2u; }
  else { hh.t = (cmin.y + (d.z < 0.0 ? float(s) : 0.0) - o.z) / d.z; hh.face = 3u; }
  float hy = o.y + d.y * hh.t;
  uint mat = 3u, cnt = hd.y & 0xFFu;
  for (uint k2 = 0u; k2 < cnt; k2++) { uint rr = runs[hd.x + k2]; mat = rr >> 18u; if (hy >= float(int(rr & 511u) * s - 64) - 1e-3) break; }
  hh.mat = mat;
  out.write(shade(p, hh, o, d, atlas, smp), gid);
}
