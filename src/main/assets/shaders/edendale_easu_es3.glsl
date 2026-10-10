#version 300 es
// AMD FidelityFX Super Resolution 1.0 — Edge Adaptive Spatial Upsampling
// (EASU), ported to GLSL ES 3.0 for Edendale's video upscaler (F.3.3).
//
// Ported from ffx_fsr1.h (FsrEasuCon, FsrEasuF, FsrEasuSetF, FsrEasuTapF) in
// https://github.com/GPUOpen-Effects/FidelityFX-FSR. ES 3.0 has no
// textureGather, so the twelve taps are fetched one by one, and the
// approximate reciprocal and reciprocal square root become exact ones.
//
// Copyright (c) 2021 Advanced Micro Devices, Inc. All rights reserved.
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in
// all copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
// THE SOFTWARE.
precision highp float;
uniform sampler2D uTexSampler;
uniform vec2 uInputSize;
uniform vec2 uOutputSize;
out vec4 outColor;

vec3 tapColor(ivec2 p) {
    return texelFetch(uTexSampler, clamp(p, ivec2(0), ivec2(uInputSize) - 1), 0).rgb;
}

// Luma times 2, in two multiply-adds (FsrEasuF).
float tapLuma(vec3 c) {
    return c.b * 0.5 + (c.r * 0.5 + c.g);
}

// FsrEasuTapF: one tap of the approximated, windowed Lanczos-2.
void easuTap(inout vec3 aC, inout float aW, vec2 off, vec2 dir, vec2 len, float lob, float clp, vec3 c) {
    vec2 v;
    v.x = (off.x * dir.x) + (off.y * dir.y);
    v.y = (off.x * (-dir.y)) + (off.y * dir.x);
    v *= len;
    float d2 = v.x * v.x + v.y * v.y;
    d2 = min(d2, clp);
    float wB = (2.0 / 5.0) * d2 - 1.0;
    float wA = lob * d2 - 1.0;
    wB *= wB;
    wA *= wA;
    wB = (25.0 / 16.0) * wB - (25.0 / 16.0 - 1.0);
    float w = wB * wA;
    aC += c * w;
    aW += w;
}

// FsrEasuSetF: accumulate direction and length, with the bilinear weight w.
void easuSet(inout vec2 dir, inout float len, float w, float lA, float lB, float lC, float lD, float lE) {
    float dc = lD - lC;
    float cb = lC - lB;
    float lenX = max(abs(dc), abs(cb));
    lenX = 1.0 / max(lenX, 1e-30);
    float dirX = lD - lB;
    dir.x += dirX * w;
    lenX = clamp(abs(dirX) * lenX, 0.0, 1.0);
    lenX *= lenX;
    len += lenX * w;

    float ec = lE - lC;
    float ca = lC - lA;
    float lenY = max(abs(ec), abs(ca));
    lenY = 1.0 / max(lenY, 1e-30);
    float dirY = lE - lA;
    dir.y += dirY * w;
    lenY = clamp(abs(dirY) * lenY, 0.0, 1.0);
    lenY *= lenY;
    len += lenY * w;
}

void main() {
    // FsrEasuCon: output pixel position to input viewport position.
    vec2 scale = uInputSize / uOutputSize;
    vec2 ip = floor(gl_FragCoord.xy);
    vec2 pp = ip * scale + (0.5 * scale - 0.5);
    vec2 fp = floor(pp);
    pp -= fp;
    ivec2 f0 = ivec2(fp);

    // The 12-tap kernel around 'f':
    //    b c
    //  e f g h
    //  i j k l
    //    n o
    vec3 bC = tapColor(f0 + ivec2(0, -1));
    vec3 cC = tapColor(f0 + ivec2(1, -1));
    vec3 eC = tapColor(f0 + ivec2(-1, 0));
    vec3 fC = tapColor(f0 + ivec2(0, 0));
    vec3 gC = tapColor(f0 + ivec2(1, 0));
    vec3 hC = tapColor(f0 + ivec2(2, 0));
    vec3 iC = tapColor(f0 + ivec2(-1, 1));
    vec3 jC = tapColor(f0 + ivec2(0, 1));
    vec3 kC = tapColor(f0 + ivec2(1, 1));
    vec3 lC = tapColor(f0 + ivec2(2, 1));
    vec3 nC = tapColor(f0 + ivec2(0, 2));
    vec3 oC = tapColor(f0 + ivec2(1, 2));

    float bL = tapLuma(bC);
    float cL = tapLuma(cC);
    float eL = tapLuma(eC);
    float fL = tapLuma(fC);
    float gL = tapLuma(gC);
    float hL = tapLuma(hC);
    float iL = tapLuma(iC);
    float jL = tapLuma(jC);
    float kL = tapLuma(kC);
    float lL = tapLuma(lC);
    float nL = tapLuma(nC);
    float oL = tapLuma(oC);

    // Direction and length, bilinearly weighted over the four nearest.
    vec2 dir = vec2(0.0);
    float len = 0.0;
    easuSet(dir, len, (1.0 - pp.x) * (1.0 - pp.y), bL, eL, fL, gL, jL);
    easuSet(dir, len, pp.x * (1.0 - pp.y), cL, fL, gL, hL, kL);
    easuSet(dir, len, (1.0 - pp.x) * pp.y, fL, iL, jL, kL, nL);
    easuSet(dir, len, pp.x * pp.y, gL, jL, kL, lL, oL);

    // Normalize, with cleanup close to zero.
    vec2 dir2 = dir * dir;
    float dirR = dir2.x + dir2.y;
    bool zro = dirR < (1.0 / 32768.0);
    dirR = zro ? 1.0 : inversesqrt(dirR);
    dir.x = zro ? 1.0 : dir.x;
    dir *= vec2(dirR);
    // {0 to 2} to {0 to 1}, shaped with a square.
    len = len * 0.5;
    len *= len;
    // Stretch the kernel from 1.0 (vertical or horizontal) to sqrt(2) on diagonals.
    float stretch = (dir.x * dir.x + dir.y * dir.y) / max(abs(dir.x), abs(dir.y));
    vec2 len2 = vec2(1.0 + (stretch - 1.0) * len, 1.0 - 0.5 * len);
    // The window shifts from ±sqrt(2) to slightly beyond 2 with the amount of edge.
    float lob = 0.5 + ((1.0 / 4.0 - 0.04) - 0.5) * len;
    float clp = 1.0 / lob;

    // Deringing limits from the four nearest.
    vec3 min4 = min(min(fC, gC), min(jC, kC));
    vec3 max4 = max(max(fC, gC), max(jC, kC));

    vec3 aC = vec3(0.0);
    float aW = 0.0;
    easuTap(aC, aW, vec2(0.0, -1.0) - pp, dir, len2, lob, clp, bC);
    easuTap(aC, aW, vec2(1.0, -1.0) - pp, dir, len2, lob, clp, cC);
    easuTap(aC, aW, vec2(-1.0, 1.0) - pp, dir, len2, lob, clp, iC);
    easuTap(aC, aW, vec2(0.0, 1.0) - pp, dir, len2, lob, clp, jC);
    easuTap(aC, aW, vec2(0.0, 0.0) - pp, dir, len2, lob, clp, fC);
    easuTap(aC, aW, vec2(-1.0, 0.0) - pp, dir, len2, lob, clp, eC);
    easuTap(aC, aW, vec2(1.0, 1.0) - pp, dir, len2, lob, clp, kC);
    easuTap(aC, aW, vec2(2.0, 1.0) - pp, dir, len2, lob, clp, lC);
    easuTap(aC, aW, vec2(2.0, 0.0) - pp, dir, len2, lob, clp, hC);
    easuTap(aC, aW, vec2(1.0, 0.0) - pp, dir, len2, lob, clp, gC);
    easuTap(aC, aW, vec2(1.0, 2.0) - pp, dir, len2, lob, clp, oC);
    easuTap(aC, aW, vec2(0.0, 2.0) - pp, dir, len2, lob, clp, nC);

    vec3 pix = min(max4, max(min4, aC * (1.0 / aW)));
    outColor = vec4(pix, 1.0);
}
