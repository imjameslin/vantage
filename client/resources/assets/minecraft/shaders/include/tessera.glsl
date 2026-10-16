// Stage 4 (Tessera, net.minecraft.client.renderer.Tessera): forward sun/moon light for terrain. Only with the TESSERA
// define (-Dmc.shaders=true). TesseraShadow is the light's orthographic depth map (reversed: larger = nearer the light).
#ifdef TESSERA
uniform sampler2D TesseraShadow;
uniform sampler2D TesseraShafts;     // god rays (tessera_shafts.fsh, last frame, quarter resolution): r = shadowed share of the shafts' air
uniform sampler2D TesseraFarFine;    // fine far shadows (1 block per texel, +-1024 blocks: trees); wraps
uniform sampler2D TesseraFar;        // far shadows (LodShadow): the height below which the terrain up-light hides the sun / moon; wraps
uniform sampler2D TesseraMaterial;   // block atlas material mask (Tessera#updateMaterialMask): r * 3 = 0 other, 1 plants, 2 leaves

layout(std140) uniform TesseraLight {
    mat4 TesseraInvViewProj;   // GL NDC -> camera-relative world (main camera)
    mat4 TesseraShadowMat;     // camera-relative world -> shadow NDC (z in [0, 1], reversed)
    vec4 TesseraLightDir;      // xyz towards the light, w = strength (0 = off: no tint at all)
    vec4 TesseraSizes;         // screen width, height, shadow map size, world size of one shadow texel
    vec4 TesseraSunColor;      // rgb lit tint, w = NDC y sign of the screen (1 or -1)
    vec4 TesseraShadeColor;    // rgb shade tint, w = shadow fade start (fraction of the map's half extent)
    vec4 TesseraParams;        // x = debug view (1 = shadow term, 2 = normals), y = fade start, z = fade end (blocks), w = 1: no shadow lookups (probe)
    vec4 TesseraSkyColor;      // rgb zenith sky colour, w = 1 when Tessera is active (water look on)
    vec4 TesseraWaterStill;    // atlas UV bounds (u0, v0, u1, v1) of block/water_still
    vec4 TesseraWaterFlow;     // atlas UV bounds of block/water_flow
    vec4 TesseraCamera;        // xyz camera position modulo 4096 (stable wave phase), w = seconds
    vec4 TesseraGrade;         // x = terrain lightmap scale (2: the half-scale twin / copy is bound, 1: vanilla's), y = 1 sun / 0 moon, z = rain 0..1, w = held light level
    mat4 TesseraPrevViewProj;  // previous frame: its camera-relative world -> GL clip
    vec4 TesseraPrevCamera;    // xyz = this camera - the previous one, w = 1 when TesseraReflect holds that frame's image
    vec4 TesseraCloud;         // xy = camera in the cloud map (blocks, wind included), z = layer bottom - camera y, w = range
    vec4 TesseraCloudColor;    // rgb vanilla cloud colour, w = 1 while the raymarched clouds are on
    vec4 TesseraOptions;       // x = the Brightness option (0 Moody .. 1 Bright), y = 1: shadowed light shafts (god rays)
    vec4 TesseraProbe;         // x = 1: the clouds march without empty-space jumps; yzw = free A/B probes (Tessera.probeY..W)
    vec4 TesseraSceneLight;    // per-frame lighting terms (Tessera.sceneConstants): rgb sun / moon light, w shadow time
    vec4 TesseraSceneAmb;      // rgb sky ambient, w day visibility squared
    vec4 TesseraSceneK;        // x block light's daylight factor, y near-camera lift per block, z 1 - noon
    vec4 TesseraShaftCol;      // rgb light shafts' colour x time weight (0: none), w (1 - noon)^2
    vec4 TesseraSkyZenith;     // per-frame sky terms (Tessera.skyConstants): rgb zenith colour, w sunrise / sunset amount
    vec4 TesseraSkyNight;      // rgb night horizon colour, w day factor of the sky colours
    vec4 TesseraSkyGlow;       // rgb sun glow tint, w its strength (full by day, about a third under the moon)
    vec4 TesseraFarCam;        // far shadows: xz = camera modulo the height image's span, y = camera height, w = 1 / span (0: none)
    vec4 TesseraFineCam;       // fine far shadows: xz = camera modulo its span, w = 1 / span (0: none)
    vec4 TesseraSunDisc;       // xyz the sun (the moon once the sun is well down): the disc and sky glow's direction, w the disc's weight
    vec4 TesseraSunAux;        // x warm haze towards the sun, y the sun's elevation sine, z sky glare weight, w = 1 sun / 0 moon
    vec4 TesseraSunTrans;      // rgb the sun disc's colour relative to noon (display multipliers; Tessera.skyConstants), w free
};
#ifdef TESSERA_WATER
uniform sampler2D TesseraReflect;   // previous frame's world at half resolution: rgb, a = distance from its camera
uniform sampler2D TesseraCloudMap;  // the raymarched clouds' occupancy map (r = 1 in a cell), for their reflection

// Screen-space reflection against the previous frame's world image: march the reflected ray from p (camera-relative)
// with growing steps, project each point into the previous frame, hit where the ray passes behind its surface (within a
// thickness), then refine. Returns rgb and the hit weight (screen-edge / distance fades; 0 = no hit, use the sky).
vec4 tesseraTrace(vec3 p, vec3 r) {
    ivec2 size = textureSize(TesseraReflect, 0);
    float step = 0.35 + length(p) * 0.012;
    vec3 q = p;
    vec3 last = p;
    // the previous frame's clip position is linear along the ray: c(q) = c0 + cr * (distance travelled)
    vec4 c0 = TesseraPrevViewProj * vec4(p + TesseraPrevCamera.xyz, 1.0);
    vec4 cr = TesseraPrevViewProj * vec4(r, 0.0);
    float travelled = 0.0;
    // probe z = 9: 12 steps growing 1.40x (same reach as 20 steps of 1.22x)
    bool tesseraFull = TesseraProbe.z == 26.0;   // probe 26: the old full trace (20 steps everywhere)
    int steps = tesseraFull ? 20 : 12;
    float grow = tesseraFull ? 1.22 : 1.40;
    for (int i = 0; i < steps; i++) {
        last = q;
        q += r * step;
        travelled += step;
        vec3 qp = q + TesseraPrevCamera.xyz;
        vec4 c = c0 + cr * travelled;
        if (c.w <= 0.0) {
            break;
        }
        vec2 ndc = c.xy / c.w;
        if (abs(ndc.x) > 1.0 || abs(ndc.y) > 1.0) {
            break;
        }
        vec2 frag = vec2(ndc.x + 1.0, TesseraSunColor.w * ndc.y + 1.0) * 0.5 * TesseraSizes.xy;
        vec4 s = texelFetch(TesseraReflect, clamp(ivec2(frag * 0.5), ivec2(0), size - 1), 0);
        float qd = length(qp);
        if (s.a < qd && qd - s.a < step * 2.0 + 0.5) {
            // refine between last and q
            vec3 a = last;
            vec3 b = q;
            for (int k = 0; k < 4; k++) {
                vec3 m = (a + b) * 0.5;
                vec3 mp = m + TesseraPrevCamera.xyz;
                vec4 mc = TesseraPrevViewProj * vec4(mp, 1.0);
                vec2 mn = mc.xy / mc.w;
                vec2 mf = vec2(mn.x + 1.0, TesseraSunColor.w * mn.y + 1.0) * 0.5 * TesseraSizes.xy;
                vec4 ms = texelFetch(TesseraReflect, clamp(ivec2(mf * 0.5), ivec2(0), size - 1), 0);
                if (ms.a < length(mp)) {
                    b = m;
                    s = ms;
                    ndc = mn;
                } else {
                    a = m;
                }
            }
            vec2 edge = smoothstep(vec2(1.0), vec2(0.85), abs(ndc));
            return vec4(s.rgb, edge.x * edge.y);
        }
        step *= grow;
    }
    return vec4(0.0);
}
#endif

// Camera-relative position of this fragment (main camera; meaningless where TesseraLightDir.w == 0).
vec3 tesseraPosition() {
    vec2 ndc = vec2(2.0 * gl_FragCoord.x / TesseraSizes.x - 1.0, TesseraSunColor.w * (2.0 * gl_FragCoord.y / TesseraSizes.y - 1.0));
    vec4 wp = TesseraInvViewProj * vec4(ndc, gl_FragCoord.z, 1.0);
    return wp.xyz / wp.w;
}

// Far shadows: 1 = lit. The point's height against the swept terrain shadow height (a soft edge of a few blocks).
float tesseraFarShadow(vec3 p) {
    if (TesseraFarCam.w == 0.0 || TesseraProbe.z == 18.0) return 1.0;   // probe 18: none
    // coarser levels with distance (the view is grazing there: full detail would scatter the reads over the image)
    float d2 = dot(p.xz, p.xz);
    float y = p.y + TesseraFarCam.y;
    // fine image (trees) where the far terrain has block cells (out to ~300 blocks), blended into the coarse one (hills) by
    // 450: further out a tree tip's shadow covered whole 2-4 block cells and shaded the forest's crowns flat
    float t = TesseraFineCam.w == 0.0 ? 1.0 : smoothstep(300.0 * 300.0, 450.0 * 450.0, d2);
    float lit = 1.0;
    if (t < 1.0) {
        // full detail: at the 2 px cell rule a block is a few pixels wide out here; the max-filtered mips spread canopy
        // height over the ground between trees and shaded the whole forest evenly (flat look)
        float lodF = 0.0;
        float hf = textureLod(TesseraFarFine, (p.xz + TesseraFineCam.xz) * TesseraFineCam.w, lodF).r;
        lit = smoothstep(-1.0, 0.0, y - hf);
    }
    if (t > 0.0) {
        float lod = TesseraProbe.z == 19.0 ? 0.0 : clamp(log2(d2) * 0.5 - 8.5, 0.0, 4.0);   // probe 19: full detail
        float h = textureLod(TesseraFar, (p.xz + TesseraFarCam.xz) * TesseraFarCam.w, lod).r;
        lit = mix(lit, smoothstep(-2.5, 0.0, y - h), t);
    }
    return lit;
}

// 3x3 bilinear PCF over the 4x4 shadow texels around the sample, as four gathers: 1 = lit.
float tesseraShadow(vec3 sp, out float edge) {
    vec4 s = TesseraShadowMat * vec4(sp, 1.0);
    edge = max(abs(s.x), abs(s.y));
    vec2 st = (s.xy * 0.5 + 0.5) * TesseraSizes.z - 0.5;
    vec2 b = floor(st) - 1.0;
    vec2 f = fract(st);
    float z = s.z;
    float inv = 1.0 / TesseraSizes.z;
    vec4 g00 = step(textureGather(TesseraShadow, (b + vec2(1.0, 1.0)) * inv), vec4(z));
    vec4 g10 = step(textureGather(TesseraShadow, (b + vec2(3.0, 1.0)) * inv), vec4(z));
    vec4 g01 = step(textureGather(TesseraShadow, (b + vec2(1.0, 3.0)) * inv), vec4(z));
    vec4 g11 = step(textureGather(TesseraShadow, (b + vec2(3.0, 3.0)) * inv), vec4(z));
    // gather order: x = (0,1), y = (1,1), z = (1,0), w = (0,0); column weights 1-f.x, 1, 1, f.x (rows likewise)
    float wx0 = 1.0 - f.x;
    float wx3 = f.x;
    float wy0 = 1.0 - f.y;
    float wy3 = f.y;
    float sum = g00.w * wx0 * wy0 + g00.z * wy0 + g00.x * wx0 + g00.y
              + g10.w * wy0 + g10.z * wx3 * wy0 + g10.x + g10.y * wx3
              + g01.w * wx0 + g01.z + g01.x * wx0 * wy3 + g01.y * wy3
              + g11.w + g11.z * wx3 + g11.x * wy3 + g11.y * wx3 * wy3;
    return sum / 9.0;
}

bool tesseraInside(vec2 uv, vec4 b) {
    return uv.x >= b.x && uv.x <= b.z && uv.y >= b.y && uv.y <= b.w;
}

bool tesseraIsWater(vec2 uv) {
    return TesseraSkyColor.w != 0.0 && TesseraProbe.z != 14.0 && (tesseraInside(uv, TesseraWaterStill) || tesseraInside(uv, TesseraWaterFlow));
}

vec3 tesseraSky(vec3 d);
float tesseraLow();
float tesseraSunFactor();
vec3 tesseraNightMiddle();

float tesseraHash(vec2 i) {
    vec3 q = fract(vec3(i.xyx) * vec3(0.1031, 0.1030, 0.0973));
    q += dot(q, q.yzx + 33.33);
    return fract((q.x + q.y) * q.z);
}

// value noise at x (quintic fade) with its gradient: (value, d/dx, d/dy)
vec3 tesseraNoiseGrad(vec2 x) {
    vec2 i = floor(x);
    vec2 f = x - i;
    vec2 u = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
    vec2 du = 30.0 * f * f * (f * (f - 2.0) + 1.0);
    float a = tesseraHash(i);
    float b = tesseraHash(i + vec2(1.0, 0.0));
    float c = tesseraHash(i + vec2(0.0, 1.0));
    float d = tesseraHash(i + vec2(1.0, 1.0));
    float k1 = b - a, k2 = c - a, k4 = a - b - c + d;
    return vec3(a + k1 * u.x + k2 * u.y + k4 * u.x * u.y, du * (vec2(k1, k2) + k4 * u.yx));
}

// the world-space gradient of one wave octave: noise of frequency freq, turned by angle, drifting at vel (blocks / s)
vec2 tesseraWaveSlope(vec2 xz, float freq, float angle, vec2 vel, float t) {
    float cs = cos(angle), sn = sin(angle);
    mat2 rot = mat2(cs, sn, -sn, cs);
    vec2 g = tesseraNoiseGrad(rot * (xz - vel * t) * freq).yz;
    return transpose(rot) * g;
}

// Water surface: small analytic waves, sky reflection with Schlick fresnel and a sun glint (lit = shadow term, 1 = sunlit).
vec3 tesseraSunTint();
float tesseraLow();

vec4 tesseraWater(vec4 color, vec3 p, vec3 n, float sky, float lit) {
    vec3 v = normalize(-p);
    if (dot(n, v) < 0.0) {
        n = -n;
    }
    vec3 nw = n;
    if (n.y > 0.9) {
        // the surface slope from four octaves of value noise, each turned and drifting its own way (no straight crests);
        // the long swells fade out by ~48 blocks, the fine ripples (glint sparkle) by ~20
        vec2 xz = p.xz + TesseraCamera.xz;
        float t = TesseraCamera.w;
        float dist = length(p);
        float far = 1.0 - smoothstep(12.0, 48.0, dist);
        float near = 1.0 - smoothstep(6.0, 20.0, dist);
        if (far > 0.0 && TesseraProbe.z != 10.0) {   // flat past 48 blocks: no noise there (probe 10: no waves)
            vec2 g = vec2(0.0);
            g += tesseraWaveSlope(xz, 0.35, 0.00, vec2(0.31, 0.22), t) * (0.10 * 0.35 * far);
            g += tesseraWaveSlope(xz, 0.90, 1.13, vec2(-0.42, 0.29), t) * (0.045 * 0.90 * far);
            g += tesseraWaveSlope(xz, 2.30, 2.31, vec2(0.48, -0.61), t) * (0.018 * 2.30 * far);
            if (near > 0.0) {
                g += tesseraWaveSlope(xz, 5.10, 0.62, vec2(-0.73, -0.52), t) * (0.008 * 5.10 * near);
            }
            nw = normalize(vec3(-g.x, 1.0, -g.y));
        }
    }
    float cosv = clamp(dot(nw, v), 0.0, 1.0);
    float fresnel = 0.08 + 0.92 * pow(1.0 - cosv, 3.0);
    vec3 r = reflect(-v, nw);
    // the same sky as core/sky.fsh (sunset colours, sun glow), so reflected sky and reflected clouds agree
    vec3 skyRefl = TesseraProbe.z == 11.0 ? vec3(0.5, 0.6, 0.9) : tesseraSky(vec3(r.x, max(r.y, 0.0), r.z));   // probe 11: no sky
#ifdef TESSERA_WATER
    // the clouds in the reflection, in 3D: six samples through the cloud layer along the reflected ray (the raymarched
    // clouds' rounded cells), shaded by the height of the first one inside (dark undersides, lit tops and sides); no
    // screen-space trace needed for them (probe 24: none)
    float cloudLo = TesseraCloud.z - p.y;
    if (TesseraCloudColor.w != 0.0 && r.y > 0.02 && cloudLo > 0.0 && TesseraProbe.z != 24.0) {
        float tLo = cloudLo / r.y;
        float tHi = (cloudLo + 8.0) / r.y;
        if (tLo < TesseraCloud.w) {
            vec2 size = vec2(textureSize(TesseraCloudMap, 0)) / 4.0;
            float cov = 0.0, sHit = 0.0, tHit = tLo;
            for (int i = 0; i < 6; i++) {
                float f = (float(i) + 0.5) / 6.0;
                float t = mix(tLo, tHi, f);
                float k = texture(TesseraCloudMap, (TesseraCloud.xy + p.xz + r.xz * t) / 12.0 / size).r;
                float v = f * 2.0 - 1.0;
                float vv = v * v;
                vv *= vv;
                float m = 0.5 + 0.5 * vv * vv - k;   // the clouds' margin: < 0 inside
                if (m < 0.1) {
                    cov = clamp(1.0 - m * 10.0, 0.0, 1.0);
                    sHit = f;
                    tHit = t;
                    break;
                }
            }
            if (cov > 0.0) {
                float far = tHit / TesseraCloud.w;
                float low = tesseraLow();
                vec3 c = mix(mix(vec3(0.58, 0.62, 0.81), vec3(0.74, 0.62, 0.66), low), mix(vec3(0.98, 0.97, 0.99), vec3(1.0, 0.88, 0.80), low), pow(sHit, 0.75));
                c *= clamp(dot(TesseraCloudColor.rgb, vec3(0.2126, 0.7152, 0.0722)) * 1.6, 0.0, 1.0);
                c = mix(tesseraNightMiddle() * mix(0.75, 1.35, sHit), c, tesseraSunFactor());
                c = mix(c, vec3(dot(c, vec3(0.2126, 0.7152, 0.0722))) * vec3(0.50, 0.52, 0.57) * mix(0.8, 1.1, sHit), TesseraGrade.z);
                c = mix(c, skyRefl, 0.08 + 0.6 * sqrt(min(far, 1.0)));
                skyRefl = mix(skyRefl, c, cov * (1.0 - smoothstep(0.55, 1.0, far)));
            }
        }
    }
    if (TesseraPrevCamera.w != 0.0 && r.y > 0.0 && TesseraProbe.w == 0.0 && !(TesseraProbe.z != 26.0 && dot(p, p) > 512.0 * 512.0)
        && !(TesseraProbe.z != 26.0 && fresnel < 0.03) && !(TesseraProbe.z != 26.0 && r.y > 0.7)) {   // terrain only (clouds above): none past 512 blocks, nor where the reflection is too faint to see (it fades in over fresnel 0.03 .. 0.05, no hard ring round the camera); probe w = 1: no trace, z = 26: the old full trace
        vec4 hit = tesseraTrace(p, r);
        skyRefl = mix(skyRefl, hit.rgb, hit.a * (TesseraProbe.z != 26.0 ? smoothstep(0.03, 0.05, fresnel) : 1.0));
    }
#endif
    float open = smoothstep(0.5, 1.0, sky);
    fresnel *= open;
    // the sun's glitter follows the sun disc down to the horizon (the shadow light fades out before it); the moon's as before
    bool sunGlint = TesseraSunAux.w != 0.0;
    float rl = max(dot(r, sunGlint ? TesseraSunDisc.xyz : TesseraLightDir.xyz), 0.0);
    float spec = (pow(rl, 1400.0) * 1.6 + pow(rl, 90.0) * 0.12) * (sunGlint ? TesseraSunDisc.w * mix(1.0, lit, min(TesseraLightDir.w * 4.0, 1.0)) : lit * TesseraLightDir.w) * open;
    // the water texture itself (its ripple pattern and biome tint), a little darker, over a see-through body: looking
    // down the bottom shows, towards the horizon the reflection takes over
    vec3 body = color.rgb * 0.40;
    float base = mix(0.55, 0.85, pow(1.0 - cosv, 1.5));
    vec3 rgb = mix(body, skyRefl, fresnel) + (sunGlint ? mix(vec3(1.08, 1.03, 0.93), tesseraSunTint() * 0.25, tesseraLow()) : TesseraSunColor.rgb) * spec;
    float a = clamp(base + (1.0 - base) * fresnel + spec, 0.0, 1.0);
    return vec4(rgb, a);
}

// The light tint for a fragment at camera-relative p with geometric normal n and sky light level sky (0..1);
// litOut is the weighted shadow term (1 = sunlit).
vec3 tesseraTintLit(vec3 p, vec3 n, float sky, out float litOut) {
    float dist = length(p);
    // The sun colour reaches the horizon (Farsight terrain too); only the shadows fade out with the map's range.
    float w = TesseraLightDir.w * smoothstep(0.5, 1.0, sky);
    float shadowFade = 1.0 - smoothstep(TesseraParams.y, TesseraParams.z, dist);
    litOut = 1.0;
    if (w <= 0.0) {
        return vec3(1.0);
    }
    if (dot(n, p) > 0.0) {
        n = -n;
    }
    float ndl = dot(n, TesseraLightDir.xyz);
    float facing = clamp(ndl * 2.5, 0.0, 1.0);
    float lit = TesseraParams.w == 0.0 ? 0.0 : 1.0;
    if (ndl > 0.0 && TesseraParams.w == 0.0) {
        lit = 1.0;
        if (shadowFade > 0.0) {
            float edge;
            lit = tesseraShadow(p + n * (TesseraSizes.w * 1.5) + TesseraLightDir.xyz * (TesseraSizes.w * 0.5), edge);
            lit = mix(1.0, mix(lit, 1.0, smoothstep(TesseraShadeColor.w, 1.0, edge)), shadowFade);
        }
    }
    litOut = mix(1.0, lit, w);
    if (TesseraParams.x == 1.0) {
        return vec3(lit, lit * facing, facing) / max(w, 1.0e-3);
    }
    // On top of vanilla's face shades: faces turned to the sun get warm light that grows with how squarely they face it,
    // faces turned away a cool sky tint, and faces that look at the sun but are shadowed a deeper cool shade. Farsight
    // terrain beyond the shadow map keeps the sun / shade sides.
    vec3 away = mix(TesseraShadeColor.rgb, vec3(1.0), 0.35);
    vec3 shade = mix(away, away * 0.78, facing);
    vec3 sunLit = TesseraSunColor.rgb * (0.95 + 0.25 * clamp(ndl, 0.0, 1.0));
    return mix(vec3(1.0), mix(shade, sunLit, lit * facing), w);
}

// The material of the block atlas texel at uv: 0 other, 1 plants (grass, flowers, crops), 2 leaves.
float tesseraMaterial(vec2 uv) {
    vec2 size = vec2(textureSize(TesseraMaterial, 0));
    return floor(texelFetch(TesseraMaterial, ivec2(min(uv * size, size - 1.0)), 0).r * 3.0 + 0.5);
}

// Vanilla's baked face shade (up 1, down 0.5, north/south 0.8, east/west 0.6), divided back out of the vertex colour.
float tesseraVanillaShade(vec3 n) {
    vec3 a = abs(n);
    return a.y > 0.5 ? (n.y > 0.0 ? 1.0 : 0.5) : (a.z > a.x ? 0.8 : 0.6);
}

// Stage 4 lighting (our model): the light reaching a surface from the light levels lm (x = block, y = sky; 0..1), worked
// out in linear light and returned display-encoded (^(1/2.2)) for albedo x result, then tesseraTonemap(tesseraLinear(..)).
// Direct light (sun by day, moon by night; Tessera.sceneConstants) by a wrapped cosine through the shadow map, sky light
// over the hemisphere the face looks at, scaled by how open the sky is, warm block light with a hot core near the source,
// a cool floor in caves, and a per-face shade (vanilla's idea: tops brightest, then north/south, east/west, bottoms).
// lit = the shadow term. mat (tesseraMaterial): plants are lit like the ground they grow from (an up normal), leaves wrap
// light round to their far side, and both glow where the sun shines through them towards the camera.
vec3 tesseraTerrainLight(vec3 p, vec3 n, vec2 lm, float mat, out float litOut) {
    vec3 l = TesseraLightDir.xyz;
    vec3 direct = TesseraSceneLight.rgb;   // linear, per frame (time of day, rain and the Brightness option included)
    vec3 skyLight = TesseraSceneAmb.rgb;
    float directFade = TesseraSceneLight.w;
    float bright = TesseraOptions.x;

    float dist = length(p);
    if (dot(n, p) > 0.0) n = -n;
    if (mat == 1.0) n = vec3(0.0, 1.0, 0.0);
    float skyL = lm.y;
    // open sky beyond the shadow map's reach: only where the sky light is (almost) full
    float open = smoothstep(0.82, 1.0, skyL);
    float wrap = mat == 2.0 ? 0.65 : 0.22;
    float diffuse = clamp((dot(n, l) + wrap) / (1.0 + wrap), 0.0, 1.0);
    float lit = 1.0;
    litOut = 1.0;
    if (diffuse > 0.0 && TesseraLightDir.w > 0.0) {
        lit = open;
        float shadowFade = 1.0 - smoothstep(TesseraParams.y, TesseraParams.z, dist);
        if (shadowFade < 1.0) lit *= tesseraFarShadow(p + n * 0.5);
        if (shadowFade > 0.0 && TesseraParams.w == 0.0) {
            float edge;
            float s = tesseraShadow(p + n * (TesseraSizes.w * 1.5) + l * (TesseraSizes.w * 0.5), edge);
            s = mix(s, 1.0, smoothstep(TesseraShadeColor.w, 1.0, edge));
            lit = mix(lit, s, shadowFade);
        }
        litOut = lit;
    } else if (TesseraLightDir.w <= 0.0) {
        lit = open;
    } else {
        litOut = 0.0;
        lit = 0.0;
    }
    vec3 sun = direct * (diffuse * lit * directFade);
    // sky light: the face's view of the sky dome (tops see all of it, bottoms the ground's bounce), by the sky light level
    vec3 sky = skyLight * ((0.58 + 0.42 * n.y) * skyL * skyL * (0.35 + 0.65 * skyL));
    // block light (and the held light: a source at the player's chest, one level less per block like vanilla's)
    float bx = max(lm.x, clamp((TesseraGrade.w - length(p + vec3(0.0, 0.6, 0.0))) / 15.0, 0.0, 1.0));
    float bx2 = bx * bx;
    float blockI = bx2 * bx * (2.4 + 1.2 * bright) + exp2(14.0 * (bx - 1.0)) * 4.5;
    blockI *= 1.0 - TesseraSceneK.x * skyL * skyL;   // washed out under open daylight
    vec3 block = blockI * vec3(1.0, 0.57, 0.31);
    vec3 floorL = (0.006 + 0.018 * bright) * vec3(0.62, 0.68, 0.90) * (1.0 - skyL);
    // per-face shade
    float shade = (n.y >= 0.0 ? 0.76 + 0.24 * n.y : 0.76 + 0.30 * n.y) - 0.07 * n.x * n.x + 0.04 * n.z * n.z;
    vec3 total = (sun + sky + block + floorL) * shade;
    if (mat != 0.0) {
        // light scattered through the blade / leaf towards the camera
        float through = pow(max(dot(normalize(p), l), 0.0), 8.0) * (mat == 1.0 ? 0.7 : 0.5);
        total += direct * (litOut * directFade * through * 0.8);
    }
    return pow(max(total, vec3(0.0)), vec3(1.0 / 2.2));
}

// Light shafts, unshadowed (our model): sunlight scattered by the air between the camera and a surface at
// p, in linear HDR, added before the tonemap. Grows with distance (a curved ramp to full at TESSERA_SHAFT_RANGE), strongest
// looking towards the sun and near the horizon, and with a low sun (an eighth of it at noon); the colour is the sunlight's,
// paler (square-rooted) by day and fuller at sunrise / sunset. Off at night and while the sun crosses the horizon.
const float TESSERA_SHAFT_RANGE = 1024.0;

// the air's share of the shaft up to distance x: a curved ramp to 1 at TESSERA_SHAFT_RANGE
float tesseraShaftWeight(float x) {
    float w = pow(min(x / TESSERA_SHAFT_RANGE, 1.0), 0.7);
    return w * w * (3.0 - 2.0 * w);
}

// Shadowed shafts: the air weight along direction d up to dist (w = its full weight) that the sun reaches. Inside the
// shadow map, TESSERA_SHAFT_STEPS single-texel shadow taps spread towards the camera (dithered per pixel and frame; TAA
// averages the noise) weight their slices; past the map the air counts as lit.
#ifndef TESSERA_SHAFT_STEPS
#define TESSERA_SHAFT_STEPS 8
#endif
float tesseraShaftAir(vec3 d, float dist, float w) {
    if (TesseraOptions.y == 0.0 || TesseraParams.w != 0.0 || TesseraLightDir.w <= 0.0) return w;
    float reach = min(dist, TesseraParams.z * 0.95);
    float size = TesseraSizes.z;
    float jitter = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))) + fract(TesseraCamera.w * 7.31) * 0.618);
    float lastW = 0.0;
    float sum = 0.0;
    float s = 1.0;
    for (int i = 0; i < TESSERA_SHAFT_STEPS; i++) {
        float t = (float(i) + jitter) / float(TESSERA_SHAFT_STEPS);
        float x = t * t * reach;
        vec4 sp = TesseraShadowMat * vec4(d * x, 1.0);
        s = 1.0;
        if (max(abs(sp.x), abs(sp.y)) < 1.0) {
            s = texelFetch(TesseraShadow, ivec2((sp.xy * 0.5 + 0.5) * size), 0).r <= sp.z ? 1.0 : 0.0;
        }
        float wx = tesseraShaftWeight(x);
        sum += s * (wx - lastW);
        lastW = wx;
    }
    float wr = tesseraShaftWeight(reach);
    return sum + s * (wr - lastW) + (w - wr);
}

// sky: true for a sky pixel along p (the full air weight; returns the shafts' shadowed part, which the sky's tuned colour
// already includes as unshadowed haze), false for a surface at p (returns the lit shafts to add)
vec3 tesseraShafts(vec3 p, bool sky) {
    vec3 col = TesseraShaftCol.rgb;   // colour x time weight (Tessera.sceneConstants); 0 with no shafts
    if (col.r + col.g + col.b <= 0.0) return vec3(0.0);
    vec3 l = TesseraLightDir.xyz;
    float lowSun2 = TesseraShaftCol.w;
    float dist = length(p);
    vec3 d = p / max(dist, 1.0e-3);
    float vdl = max((dot(d, l) + 1.0) * 0.5, 0.0);
    float vdu = 1.0 - max(d.y, 0.0);
    float vduM = vdu * vdu;
    vduM = vduM * vduM * (3.0 - 2.0 * vduM);
    vduM = pow(vduM, min(dist / TESSERA_SHAFT_RANGE, 1.0) * 3.0);
    float mult = vduM * vdl;   // (the time weight is in col)
    float w = tesseraShaftWeight(dist);
    if (mult * w < 1.0e-4) return vec3(0.0);
    float shade = texture(TesseraShafts, gl_FragCoord.xy / TesseraSizes.xy).r;
    // the sky shows the shadowed beams towards the sun only (away from it they read as stripes, not rays)
    // and with a low sun only (a high sun's shafts are a faint haze; their shadows in the sky read as stripes)
    if (sky) shade *= smoothstep(-0.1, 0.5, dot(d, l)) * lowSun2;
    return col * (mult * w * (sky ? shade : 1.0 - shade));
}

vec3 tesseraLightShaft(vec3 p) {
    return tesseraShafts(p, false);
}

// The final grade of every lit surface: a saturation lift (vivid colours; less on bright values so snow
// and clouds stay white), then a soft highlight rolloff instead of a hard clip at 1: identity below 0.85.
vec3 tesseraShoulder(vec3 c) {
    float luma = dot(c, vec3(0.2126, 0.7152, 0.0722));
    c = max(mix(vec3(luma), c, 1.22 - 0.12 * clamp(luma, 0.0, 1.0)), 0.0);
    vec3 over = max(c - 0.85, 0.0);
    return min(c, 0.85) + 0.15 * (1.0 - exp(-over / 0.15));
}

// Sky colour towards direction d (camera-relative, normalized): horizon haze (the fog colour) to a deeper zenith, plus a
// warm glow around the sun that widens as the sun gets low. Also the colour of the terrain haze.
float tesseraGlow(vec3 d, float wide, float tight) {
    vec3 l = TesseraSunDisc.xyz;
    float mu = max(dot(d, l), 0.0);
    float low = 1.0 - smoothstep(0.0, 0.55, l.y);
    // integer powers of mu by multiplication (no exp / log)
    float m2 = mu * mu, m4 = m2 * m2, m8 = m4 * m4, m16 = m8 * m8, m32 = m16 * m16;
    float glow = (m8 * m4) * wide * (0.1 + 0.3 * low) + (m32 * m32) * tight * (0.15 + 0.25 * low);   // the sun itself and its glare: tessera_sun_glare.fsh
    glow += (m4 * mu) * wide * 0.6 * (1.0 - low);   // by day a broad pale-white brightening round the sun
    // full by day, about a third under the moon (strength <= 0.45)
    return glow * TesseraSkyGlow.w;
}

// how much of the sunrise / sunset look applies (0 by day and night)
float tesseraLow() {
    return TesseraSkyZenith.w;   // from the sun's elevation, on the CPU (Tessera.skyConstants)
}

// The sun's elevation (sine), whichever body TesseraLightDir is
float tesseraSunUp() {
    return TesseraGrade.y != 0.0 ? TesseraLightDir.y : -TesseraLightDir.y;
}

// day (1) to night (0) for the sky colours: a slow fade after sunset, a quick one before sunrise
float tesseraSunFactor() {
    return TesseraSkyNight.w;   // on the CPU (Tessera.skyConstants)
}

vec3 tesseraNightMiddle() {
    return TesseraSkyNight.rgb;   // a blue-grey night horizon, darkest late in the night (Tessera.skyConstants)
}

// A deep saturated blue zenith rather than vanilla's pale cyan (reference shot ~(95, 121, 197)); at
// sunrise / sunset a dusty mauve
vec3 tesseraZenith() {
    // our blue x (0.50, 0.64, 1.15), to (0.60, 0.52, 0.58) at sunrise / sunset, to a dark night blue; rain greys it
    // (a rainy night a dark blue-grey, not black); on the CPU (Tessera.skyConstants)
    return TesseraSkyZenith.rgb;
}

// How much of the sky shows through at a reflection-source texel looking along d with surface distance dist: 1 for
// sky; for a surface inside the cloud layer the clouds' own distance fade (tessera_clouds.fsh), so the sun sets behind
// the faint far clouds on the horizon instead of vanishing behind them; 0 for anything else.
float tesseraHazeAmount(vec3 d, float dist);
float tesseraSkyOpen(vec3 d, float dist) {
    if (dist >= 60000.0) return 1.0;
    float y = d.y * dist;
    if (TesseraCloudColor.w != 0.0 && y >= TesseraCloud.z - 1.0 && y <= TesseraCloud.z + 9.0) {
        return smoothstep(0.55, 1.0, dist / TesseraCloud.w);
    }
    // land deep in the haze (terrain.fsh's aerial perspective, same curve): the sun sinks into the haze over it, dimming
    // as the land it passes behind gets nearer and clearer, instead of cutting off on an edge that cannot be seen;
    // plainly visible hills still hide it
    return smoothstep(0.60, 0.93, tesseraHazeAmount(d, dist));
}

// Aerial perspective amount at distance dist along d (terrain.fsh): denser at sunrise / sunset, and towards a low sun
// thicker still (light scattered forwards in the haze), which softens the far land's silhouettes against the glow
float tesseraHazeAmount(vec3 d, float dist) {
    float low = tesseraLow();
    float haze = pow(smoothstep(48.0, 1600.0 - 700.0 * low, dist), 0.75) * (0.85 + 0.08 * low);
    if (TesseraSunAux.w != 0.0 && low > 0.0) {
        float mu = max(dot(d, TesseraSunDisc.xyz), 0.0);
        float m2 = mu * mu, m4 = m2 * m2;
        haze += (0.97 - haze) * haze * (m4 * m4) * 0.6 * low;
    }
    return haze;
}

// The sun's tangent plane coordinates of direction d (axes like vanilla's sun quad: world Z and the sun's path); 1e3 when
// d faces away from the sun.
vec2 tesseraSunPlane(vec3 d) {
    vec3 l = TesseraSunDisc.xyz;
    float k = dot(d, l);
    if (k <= 0.0) return vec2(1.0e3);
    vec3 e2 = normalize(cross(l, vec3(0.0, 0.0, 1.0)));
    return vec2(d.z, dot(d, e2)) / k;
}

// Virtual-HDR sun, built on vanilla's sun texture: a 32-texel quad 0.6 wide (tangent units) whose core is three nested
// squares (4, 6 and 8 texels: the white centre, the pale yellow ring, the gold rim). Each is curved up steeply (only the
// core survives) and scaled far above white with an orange tint; the tonemapper turns that white with a warm rim.
const vec3 TESSERA_SUN_RINGS = vec3(0.0375, 0.05625, 0.075);   // half-widths of the nested squares
const vec3 TESSERA_SUN_LEVEL = vec3(3.30, 1.77, 0.26);         // their curved brightness

vec3 tesseraSunTint() {
    // warm white at noon; yellow, orange, then a deep orange-red as it sets (Tessera.skyConstants)
    return vec3(1.0, 0.85, 0.52) * 4.4 * TesseraSunTrans.rgb;
}

float tesseraBlurSquare(vec2 q, float a, float s);

// the nested squares, their edges softened by about a texel (no hard box edge where the dim outer ring meets the sky)
vec3 tesseraSunCore(vec2 q) {
    float m = max(abs(q.x), abs(q.y));
    if (m > TESSERA_SUN_RINGS.z + 0.04) return vec3(0.0);
    const float s = 0.012;
    vec3 r = TESSERA_SUN_RINGS, v = TESSERA_SUN_LEVEL;
    float k = (v.x - v.y) * tesseraBlurSquare(q, r.x, s) + (v.y - v.z) * tesseraBlurSquare(q, r.y, s) + v.z * tesseraBlurSquare(q, r.z, s);
    return tesseraSunTint() * k;
}

float tesseraErf(float x) {
    float xx = x * x;
    return sign(x) * sqrt(1.0 - exp(-xx * (1.2732395 + 0.147 * xx) / (1.0 + 0.147 * xx)));
}

// a centred square of half-width a blurred by a gaussian of width s, at q
float tesseraBlurSquare(vec2 q, float a, float s) {
    float k = 0.70710678 / s;
    return 0.25 * (tesseraErf((q.x + a) * k) - tesseraErf((q.x - a) * k)) * (tesseraErf((q.y + a) * k) - tesseraErf((q.y - a) * k));
}

// The sun's bloom: the core blurred at seven widths (doubling, tight to very wide), weighted towards the tight ones and
// mixed in at a small fraction. Blurred in linear light, where the core is enormous (its display value raised to 2.2), and
// each level clipped like an 8-bit bloom buffer (at 128): the tight levels saturate around the core, which melts the
// rings into one soft glowing square. The tight widths blur the squares exactly; the wide ones treat the core as a point.
vec3 tesseraSunBloom(vec2 q) {
    const float W[7] = float[7](1.0, 1.0, 1.0, 0.8, 0.8, 0.6, 0.4);
    vec3 r = TESSERA_SUN_RINGS;
    vec3 t = tesseraSunTint();
    // by day the glow is a near-neutral white (an orange halo over a blue sky reads pink); warm only as the sun gets low
    t = mix(vec3(dot(t, vec3(0.2126, 0.7152, 0.0722))) * vec3(1.0, 0.97, 0.9), t, 0.25 + 0.75 * tesseraLow());
    // linear ring intensities per channel (display value ^ 2.2), as differences of nested squares
    vec3 c1 = pow(t * TESSERA_SUN_LEVEL.x, vec3(2.2));
    vec3 c2 = pow(t * TESSERA_SUN_LEVEL.y, vec3(2.2));
    vec3 c3 = pow(t * TESSERA_SUN_LEVEL.z, vec3(2.2));
    vec3 v1 = c1 - c2, v2 = c2 - c3, v3 = c3;
    vec3 energy = 4.0 * (v1 * r.x * r.x + v2 * r.y * r.y + v3 * r.z * r.z);
    float rr = dot(q, q);
    float m = max(abs(q.x), abs(q.y));
    vec3 sum = vec3(0.0);
    float s = 0.00674;
    for (int k = 0; k < 7; k++) {
        vec3 level;
        if (k < 4) {
            level = m < r.z + 5.0 * s ? v1 * tesseraBlurSquare(q, r.x, s) + v2 * tesseraBlurSquare(q, r.y, s) + v3 * tesseraBlurSquare(q, r.z, s) : vec3(0.0);
        } else {
            level = energy / (6.2831853 * s * s) * exp(-rr / (2.0 * s * s));
        }
        sum += W[k] * min(level, vec3(128.0));
        s *= 2.0;
    }
    return sum * (0.14 * 0.12);
}

// The sky's own glare round the sun (linear HDR, added to open sky): a forward-scattering lobe (Henyey-Greenstein, g 0.82,
// scaled to 1 at the sun) plus a wide faint one, warm and wide by day, orange and tighter at sunrise / sunset, cool and
// faint under the moon; it fades while the body crosses the horizon.
float tesseraPhaseHG(float mu, float g) {
    float t = (1.0 - g) * inversesqrt(1.0 + g * g - 2.0 * g * mu);   // HG(mu) / HG(1) = ((1 - g) / sqrt(1 + g^2 - 2 g mu))^3
    return t * t * t;
}

vec3 tesseraSunSkyGlare(vec3 d) {
    float up = TesseraSunAux.y;
    float mu = dot(d, TesseraSunDisc.xyz);
    if (mu <= 0.0) return vec3(0.0);
    float g = 0.07 * tesseraPhaseHG(mu, 0.82) - 0.07 * tesseraPhaseHG(0.0, 0.82) + 0.05 * mu * mu * mu;   // 0 at mu = 0
    float low = tesseraLow();
    vec3 warm = mix(vec3(1.28, 1.22, 1.12), vec3(1.55, 0.78 + 0.4 * sqrt(max(up, 0.0)), 0.30), low);
    vec3 col = TesseraSunAux.w != 0.0 ? warm : vec3(0.40, 0.44, 0.56) * 0.3;
    return col * max(g, 0.0) * mix(1.0, 0.5, low);
}

vec3 tesseraLinear(vec3 c) {
    return pow(max(c, 0.0), vec3(2.2));
}

// Display tonemap of the virtual HDR (our chain): exposure, an extended Reinhard curve (white at TESSERA_WHITE) taken
// partly per channel and partly on luminance (bright colours keep their hue, very bright light still goes white), a
// slight S-shaped contrast in display space, then a small vibrance lift that spares near-greys and near-whites.
const float TESSERA_EXPOSURE = 1.0;
const float TESSERA_WHITE = 9.0;
const float TESSERA_CONTRAST = 0.05;

vec3 tesseraReinhard(vec3 x) {
    return x * (1.0 + x * (1.0 / (TESSERA_WHITE * TESSERA_WHITE))) / (1.0 + x);
}

vec3 tesseraTonemap(vec3 x) {
    x = max(x, 0.0) * TESSERA_EXPOSURE;
    float lum = dot(x, vec3(0.2126, 0.7152, 0.0722));
    float lumT = lum * (1.0 + lum * (1.0 / (TESSERA_WHITE * TESSERA_WHITE))) / (1.0 + lum);
    vec3 c = mix(tesseraReinhard(x), x * (lumT / max(lum, 1.0e-6)), 0.45);
    c = clamp(c, 0.0, 1.0);
    c = mix(1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055, 12.92 * c, lessThan(c, vec3(0.0031308)));
    c += TESSERA_CONTRAST * c * (1.0 - c) * (2.0 * c - 1.0);   // gentle S around mid grey
    float g = dot(c, vec3(0.2126, 0.7152, 0.0722));
    float vib = 0.10 * smoothstep(0.02, 0.2, g) * (1.0 - smoothstep(0.75, 1.0, g));
    return clamp(mix(vec3(g), c, 1.0 + vib), 0.0, 1.0);
}

// tesseraTonemap(tesseraLinear(a))
vec3 tesseraTonemapDisplay(vec3 a) {
    return tesseraTonemap(pow(max(a, 0.0), vec3(2.2)));
}

// The sky's own glare (tesseraSunSkyGlare, linear HDR, times k) over a display colour: both through the tonemap, the
// difference added, so a colour without glare stays exactly as it was. For the sky (k = 1) and the clouds (k = 0.5).
vec3 tesseraSkyGlareOver(vec3 display, vec3 d, float k) {
    if (TesseraSunAux.z <= 0.0 || TesseraProbe.z == 5.0) return display;   // probe 5: no glare
    vec3 g = tesseraSunSkyGlare(d) * k;
    if (max(g.r, max(g.g, g.b)) < 2.0e-4) return display;
    float strength = TesseraSunAux.z;
    vec3 base = tesseraLinear(display);
    return display + max(tesseraTonemap(base + g) - tesseraTonemap(base), 0.0) * strength;
}

// pale lavender-white horizon (vanilla's fog colour, less cyan); pale peach at sunrise / sunset
vec3 tesseraHorizon() {
    vec3 h = mix(FogColor.rgb * vec3(0.86, 0.92, 1.0), vec3(0.93, 0.80, 0.68), tesseraLow() * 0.9);
    h = mix(tesseraNightMiddle(), h, tesseraSunFactor());
    h = mix(h, vec3(dot(h, vec3(0.2126, 0.7152, 0.0722))) * vec3(0.96, 0.98, 1.03), TesseraGrade.z * 0.8);
    return max(h, vec3(0.050, 0.055, 0.068) * TesseraGrade.z);
}

// The horizon colour looking along d: at sunrise / sunset deeper gold-orange in the sun's direction (by azimuth, so the
// band runs along the horizon), the rest pale peach. The terrain's far fog uses the same colour (terrain.fsh, sky.fsh),
// so distant land still meets the sky without an edge. The saturated band lets the bright low sun stand out.
vec3 tesseraHorizonToward(vec3 d) {
    vec3 h = tesseraHorizon();
    vec2 s = TesseraSunDisc.xz;
    float sl = dot(s, s), dl = dot(d.xz, d.xz);
    if (TesseraSunAux.w == 0.0 || sl < 1.0e-6 || dl < 1.0e-6) return h;
    float mu = max(dot(d.xz, s) * inversesqrt(sl * dl), 0.0);
    float m2 = mu * mu;
    float w = m2 * m2 * tesseraLow() * smoothstep(0.0, 0.5, TesseraSunAux.x);
    return h * mix(vec3(1.0), vec3(1.05, 0.80, 0.56), w);
}

// the sky's sun glow: the sun colour, paler (a peachy white at sunset, not orange)
vec3 tesseraGlowTint() {
    return TesseraSkyGlow.rgb;   // (1, 0.93, 0.84) to mix(sun colour, (1, 0.86, 0.68), 0.45) at sunset, on the CPU
}

vec3 tesseraSky(vec3 d) {
    float up = clamp(d.y, 0.0, 1.0);
    vec3 zenith = tesseraZenith();
    vec3 c = mix(tesseraHorizonToward(d), zenith, pow(up, 0.7));
    // by day the blue pales towards the sun (a wide, near-white region round it, not saturated blue up to the disc)
    float mu = max(dot(d, TesseraSunDisc.xyz), 0.0);
    float day = TesseraSunAux.x * (1.0 - tesseraLow());
    c = mix(c, vec3(dot(c, vec3(0.3, 0.55, 0.15))) * vec3(1.02, 1.0, 0.98), mu * mu * mu * 0.65 * day);
    return c + tesseraGlowTint() * tesseraGlow(d, 0.36, 0.42);
}

// Colour distant terrain fades into: the horizon sky (its own height, not the zenith) with a much weaker sun glow.
vec3 tesseraHaze(vec3 d) {
    float up = clamp(d.y, 0.0, 1.0);
    vec3 zenith = tesseraZenith();
    vec3 c = mix(tesseraHorizonToward(d), zenith, pow(up, 0.7) * 0.5);
    // sunlight scattered in the air towards the sun: distant land facing it fades into a warm haze by day
    float mu = max(dot(d, TesseraSunDisc.xyz), 0.0);
    float day = TesseraSunAux.x * (1.0 - 0.6 * tesseraLow());
    c += vec3(1.0, 0.80, 0.62) * (mu * mu * mu * 0.38 * day);
    return c + tesseraGlowTint() * tesseraGlow(d, 0.18, 0.12);
}

vec3 tesseraTint(vec3 p, vec3 n, float sky) {
    float lit;
    return tesseraTintLit(p, n, sky, lit);
}
#endif
