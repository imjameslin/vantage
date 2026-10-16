// Stage 4 (Tessera ambient occlusion, TesseraBloom.ambientOcclusion): half-resolution screen-space AO from the main
// depth (TESSERA_AO_DEPTH, InSampler by default) and the frame's TesseraInvViewProj. 8 spiral samples in a disk whose
// world radius grows with distance (contact shadows near, valley shading far on Farsight terrain). Used by tessera_ao.fsh
// and, written as a second target of the same pass, by tessera_reflect_source.fsh (TESSERA_REFLECT_AO).
#ifndef TESSERA_AO_DEPTH
uniform sampler2D InSampler;
#define TESSERA_AO_DEPTH InSampler
#endif

#ifdef TESSERA
#ifdef TESSERA_AO_DIST
// InSampler = the reflection source (half resolution, a = distance from the camera; sky 65000), whose texel i covers
// full-resolution pixels 2i..2i+1 and was measured at 2i + 0.5. A full-resolution position px reads the texel under it
// and rebuilds the point along its own ray: the ray direction is linear in px (the inverse view-projection's x, y, w
// rows; the camera sits at the origin), so no matrix per sample.
vec3 rayO;
vec3 rayX;
vec3 rayY;

void initRays() {
    mat4 m = TesseraInvViewProj;
    vec2 k = 2.0 / TesseraSizes.xy;
    // direction(px) ~ m * (px.x k.x - 1, sign (px.y k.y - 1), 0.5, 1).xyz (the point at a fixed NDC depth)
    rayX = m[0].xyz * k.x;
    rayY = m[1].xyz * (k.y * TesseraSunColor.w);
    rayO = m[0].xyz * -1.0 - m[1].xyz * TesseraSunColor.w + m[2].xyz * 0.5 + m[3].xyz;
}

float distAt(vec2 px) {
    ivec2 ip = ivec2(clamp(floor(px * 0.5), vec2(0.0), vec2(textureSize(TESSERA_AO_DEPTH, 0) - 1)));
    return texelFetch(TESSERA_AO_DEPTH, ip, 0).a;
}

vec3 pointAt(vec2 px, float dist) {
    return normalize(rayO + rayX * px.x + rayY * px.y) * dist;
}

vec3 posAt(vec2 px) {
    vec2 c = floor(px * 0.5) * 2.0 + 0.5;
    return pointAt(c, distAt(px));
}
#else
// the inverse view-projection is linear in (px, depth): its columns pre-scaled once (initUnproject), 4 multiply-adds a point
vec4 unX;
vec4 unY;
vec4 unZ;
vec4 unO;

void initUnproject() {
    mat4 m = TesseraInvViewProj;
    vec2 k = 2.0 / TesseraSizes.xy;
    unX = m[0] * k.x;
    unY = m[1] * (k.y * TesseraSunColor.w);
    unZ = m[2];
    unO = m[3] - m[0] - m[1] * TesseraSunColor.w;
}

vec3 unproject(vec2 px, float d) {
    vec4 wp = unX * px.x + unY * px.y + unZ * d + unO;
    return wp.xyz / wp.w;
}

float depthAt(vec2 px) {
    return texelFetch(TESSERA_AO_DEPTH, ivec2(clamp(px, vec2(0.0), TesseraSizes.xy - 1.0)), 0).r;
}

vec3 posAt(vec2 px) {
    return unproject(px, depthAt(px));
}
#endif

#endif

#ifdef TESSERA
// the AO visibility (1 = open) of the half-resolution pixel at gl_FragCoord
float tesseraAoVisibility() {
    if (TesseraOptions.z != 0.0) {
        return 1.0;
    }
    vec2 px = floor(gl_FragCoord.xy) * 2.0 + 0.5;
#ifdef TESSERA_AO_DIST
    initRays();
    float dist = distAt(px);
    if (dist >= 60000.0) {
        return 1.0;
    }
    vec3 p = pointAt(px, dist);
#else
    float d = depthAt(px);
    if (d <= 0.0) {
        return 1.0;
    }
    initUnproject();
    vec3 p = unproject(px, d);
    float dist = length(p);
#endif
    // faded out entirely past 320 blocks (below): no samples there
    if (dist >= 320.0) {
        return 1.0;
    }
    // the cloud layer (y 192..200, stage 4 doubles its height) writes depth too: no AO in its corners
    float worldY = p.y + TesseraCamera.y;
    if (worldY > 191.0 && worldY < 201.0) {
        return 1.0;
    }
    // normal from the nearer neighbour on each axis (no smearing across depth edges)
    vec3 xa = posAt(px + vec2(2.0, 0.0)) - p;
    vec3 xb = p - posAt(px - vec2(2.0, 0.0));
    vec3 ya = posAt(px + vec2(0.0, 2.0)) - p;
    vec3 yb = p - posAt(px - vec2(0.0, 2.0));
    vec3 n = normalize(cross(dot(xa, xa) < dot(xb, xb) ? xa : xb, dot(ya, ya) < dot(yb, yb) ? ya : yb));
    if (dot(n, p) > 0.0) {
        n = -n;
    }
    float radius = max(1.1, dist * 0.012);
#ifdef TESSERA_AO_DIST
    float pixelWorld = length(pointAt(px + vec2(1.0, 0.0), dist) - p);
#else
    float pixelWorld = length(unproject(px + vec2(1.0, 0.0), d) - p);
#endif
    float rpx = clamp(radius / max(pixelWorld, 1.0e-5), 4.0, 90.0);
    radius = rpx * pixelWorld;
    float noise = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
    float angle = noise * 6.2831853 + 2.3999632;
    // the spiral's unit step, turned by the golden angle each sample (a constant rotation, no sin / cos in the loop)
    vec2 dir = vec2(cos(angle), sin(angle));
    const mat2 turn = mat2(-0.7373688, 0.6754903, -0.6754903, -0.7373688);
    float occ = 0.0;
    for (int i = 0; i < 8; i++) {
        float t = (float(i) + 0.5 + 0.5 * noise) / 8.5;
        vec2 o = dir * (rpx * t);
        dir = turn * dir;
        vec3 v = posAt(px + o) - p;
        float len = length(v);
        float c = dot(v, n) / max(len, 1.0e-4);
        occ += max(c - 0.12, 0.0) * (1.0 - smoothstep(radius * 0.6, radius * 1.4, len));
    }
    float ao = clamp(1.0 - occ * (1.5 / 8.0), 0.0, 1.0);
    // AO is multiplied after the far haze: fade it out where the haze takes over (no dark blotches in the fog)
    ao = mix(ao, 1.0, smoothstep(48.0, 320.0, dist));
    return ao;
}
#endif
