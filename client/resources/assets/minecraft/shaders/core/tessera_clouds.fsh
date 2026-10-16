#version 330
#extension GL_ARB_separate_shader_objects : require
#extension GL_ARB_texture_gather : require

// Stage 4 (Tessera clouds): soft volumetric clouds raymarched over vanilla's cloud map (TesseraCloudMap: r = 1 where the
// map has a cell; one cell = 12 x 12 blocks). The 2D occupancy is blended between neighbouring cells through a narrow
// smoothstep, so cell edges are soft and outer corners round off; the layer is thicker than vanilla's and its top and
// bottom pull in towards the cell edges (rounded sides). Shaded by height in the layer, with self-shadow towards the sun,
// then faded into the sky colour with distance. One full-screen triangle where vanilla draws its clouds: premultiplied
// over the frame, depth tested and written at the cloud surface.
#include <minecraft:fog.glsl>
#include <minecraft:tessera.glsl>

uniform sampler2D TesseraCloudMap;

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

const float CELL = 12.0;
const float THICK = 8.0;
const float ROUND = 0.12;   // half-width of the soft band across a cell edge (in cells; baked, Tessera.CLOUD_ROUND)

// soft 2D cloud occupancy at q (cloud map cells): baked by Tessera.uploadCloudMap at size / map size texels a cell
float density(vec2 q, vec2 size) {
    return texture(TesseraCloudMap, q / size).r;
}

// horizontal distance (blocks) from q (cells) to the nearest cloud texel, made safe for the bilinear map (minus 1.5 texels)
float emptyRun(vec2 q, vec2 size) {
    float texels = texture(TesseraCloudMap, q / size).g * (255.0 / 4.0);
    return max(texels - 1.5, 0.0) * (CELL / 4.0);
}

// inside the cloud at height v (-1 bottom .. 1 top of the layer) with occupancy k: the layer thins near cell edges
float margin(float k, float v) {
    float vv = v * v;
    vv *= vv;
    return 0.5 + 0.5 * vv * vv - k;
}

bool inside(float k, float v) {
    return margin(k, v) < 0.0;
}

// a ray that misses: discarded over the frame; at half resolution every texel is written (no cloud = 0, far depth)
#ifdef TESSERA_CLOUDS_LOW
#define CLOUD_MISS { fragColor = vec4(0.0); gl_FragDepth = 0.0; return; }
#else
#define CLOUD_MISS discard;
#endif

void main() {
#ifndef TESSERA
    discard;
#else
#ifdef TESSERA_CLOUDS_LOW
    // half resolution (Tessera.cloudHalf): this texel covers 2 x 2 frame pixels; its ray goes through their centre
    const float PIX = 2.0;
    vec2 fc = floor(gl_FragCoord.xy) * 2.0 + 1.0;
#else
    const float PIX = 1.0;
    vec2 fc = gl_FragCoord.xy;
#endif
    vec2 ndc = vec2(2.0 * fc.x / TesseraSizes.x - 1.0, TesseraSunColor.w * (2.0 * fc.y / TesseraSizes.y - 1.0));
    vec4 w1 = TesseraInvViewProj * vec4(ndc, 0.5, 1.0);
    vec4 w2 = TesseraInvViewProj * vec4(ndc, 0.25, 1.0);
    vec3 p1 = w1.xyz / w1.w;
    vec3 p2 = w2.xyz / w2.w;
    // the eye is not the origin while the view bobs (the bob sits in the projection): take it out of both points
    vec3 eye = TesseraInvViewProj[2].xyz / TesseraInvViewProj[2].w;   // the unprojection's limit for NDC depth -> infinity
    p1 -= eye;
    p2 -= eye;
    vec3 d = normalize(p1);

    float y0 = TesseraCloud.z;
    float y1 = y0 + THICK;
    float range = TesseraCloud.w;
    // ray span inside the layer
    float tA, tB;
    if (abs(d.y) < 1.0e-4) {
        if (y0 > 0.0 || y1 < 0.0) CLOUD_MISS
        tA = 0.0;
        tB = range;
    } else {
        float ta = y0 / d.y, tb = y1 / d.y;
        tA = max(min(ta, tb), 0.0);
        tB = min(max(ta, tb), range * 1.2);
    }
    if (tB <= tA) CLOUD_MISS
    if (TesseraOptions.w == 1.0) {
        fragColor = vec4(0.0, 0.0, 0.0, 0.004);
        gl_FragDepth = gl_FragCoord.z;
        return;
    }

    vec2 size = vec2(textureSize(TesseraCloudMap, 0)) / 4.0;   // in cells (4 texels a cell)
    vec2 cam = TesseraCloud.xy;
    // march: steps grow with distance (a cell is 12 blocks; far clouds are faded anyway)
    float t = tA;
    float prev = tA;
    bool hit = false;
    // nearest miss (smallest margin outside the cloud) and where: a ray grazing a silhouette gets partial coverage there,
    // which anti-aliases the cloud edges
    float near = 1.0, tNear = tA;
    float hInv = 1.0 / max(length(d.xz), 1.0e-3);
    for (int i = 0; i < 96; i++) {
        vec3 p = d * t;
        float v = (p.y - y0) / THICK * 2.0 - 1.0;
        float mg = margin(density((cam + p.xz) / CELL, size), v);
        if (mg < 0.0) {
            hit = true;
            break;
        }
        if (mg < near) {
            near = mg;
            tNear = t;
        }
        prev = t;
        if (t >= tB) break;
        // shorter steps close to a cloud (small margin), so grazing rays do not skip its rounded edges; far from any
        // cloud, a jump across the empty sky (the baked distance over the ray's horizontal speed)
        float stepT = clamp(t * 0.012, 0.5, 5.0) * clamp(mg * 2.5, 0.15, 1.0);
        if (mg >= 0.5 && TesseraProbe.x == 0.0) stepT = max(stepT, emptyRun((cam + p.xz) / CELL, size) * hInv);
        t = min(t + stepT, tB);
    }
    float cover = 1.0;
    if (!hit) {
        // margin change across one pixel at tNear (the occupancy ramps 0..1 over 2 ROUND cells)
        float px = PIX * tNear * 2.0 / TesseraSizes.y / (2.0 * ROUND * CELL);
        cover = 1.0 - near / clamp(px * 1.5, 0.004, 0.25);
        if (cover <= 0.0) CLOUD_MISS
        t = tNear;
        prev = tNear;
    }
    // refine the surface
    float lo = prev, hi = t;
    for (int i = 0; i < 5; i++) {
        float m = 0.5 * (lo + hi);
        vec3 p = d * m;
        float v = (p.y - y0) / THICK * 2.0 - 1.0;
        if (inside(density((cam + p.xz) / CELL, size), v)) hi = m; else lo = m;
    }
    t = hi;
    vec3 p = d * t;
    vec2 q = (cam + p.xz) / CELL;
    float s = clamp((p.y - y0) / THICK, 0.0, 1.0);   // 0 bottom .. 1 top

    if (TesseraOptions.w == 2.0) {
        fragColor = vec4(0.5 * cover, 0.5 * cover, 0.5 * cover, cover);
        gl_FragDepth = gl_FragCoord.z;
        return;
    }
    // light: height in the layer (sunlit tops, darker undersides), self-shadow from the cloud between here and the sun
    vec3 l = TesseraLightDir.xyz;
    vec2 toSun = l.xz / max(length(l.xz), 1.0e-3) * (1.0 - s) * 0.5 / max(l.y, 0.25);
    float occ = 0.5 * (density(q + toSun * 0.35, size) + density(q + toSun, size));
    float lit = pow(s, 0.75) * (1.0 - 0.55 * occ * (1.0 - s));
    float low = tesseraLow();
    vec3 under = mix(vec3(0.58, 0.62, 0.81), vec3(0.74, 0.62, 0.66), low);   // lavender; dusty pink at sunset
    vec3 top = mix(vec3(0.98, 0.97, 0.99), vec3(1.0, 0.88, 0.80), low);
    vec3 c = mix(under, top, lit);
    // warm sun tint on the lit part, forward scattering near the sun
    c += tesseraGlowTint() * tesseraGlow(d, 0.25, 0.3) * (0.4 + 0.6 * lit);
    // vanilla's cloud colour only for the day's brightness (it greys out at sunset, which ours should not); at
    // night the clouds are the night sky's blue-grey, moonlit tops a little brighter
    c *= clamp(dot(TesseraCloudColor.rgb, vec3(0.2126, 0.7152, 0.0722)) * 1.6, 0.0, 1.0);
    vec3 night = tesseraNightMiddle() * mix(0.75, 1.35, lit);
    c = mix(night, c, tesseraSunFactor());
    // rain: heavy grey cloud, darker underneath, only a little lighter on top
    c = mix(c, vec3(dot(c, vec3(0.2126, 0.7152, 0.0722))) * vec3(0.50, 0.52, 0.57) * mix(0.8, 1.1, lit), TesseraGrade.z);
    // distance: into the sky colour of its direction, then fade out towards the cloud range
    float far = clamp(t / range, 0.0, 1.0);
    vec3 sky = tesseraShoulder(tesseraSky(d));
    c = mix(c, sky, 0.08 + 0.6 * sqrt(far));
    c = tesseraSkyGlareOver(c, d, 0.5);   // clouds are part sky colour: half the sky's glare
    float alpha = (1.0 - smoothstep(0.55, 1.0, far)) * cover;
    if (alpha < 0.01) CLOUD_MISS
    fragColor = vec4(c * alpha, alpha);
    // depth at the surface: along this pixel's ray, NDC z = a + b / distance
    float r1 = length(p1), r2 = length(p2);
    float b = (0.5 - 0.25) / (1.0 / r1 - 1.0 / r2);
    float a = 0.5 - b / r1;
    gl_FragDepth = a + b / t;
#endif
}
