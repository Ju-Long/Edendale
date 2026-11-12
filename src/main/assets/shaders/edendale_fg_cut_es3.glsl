#version 300 es
// Motion Smoothing (G.2.2): counts the coarse pass's unmatched blocks on the
// GPU, so the scene-cut decision needs no readback (OpenGL ES 3.0 has no
// atomics, so a two-step reduction stands in for Apple's atomic counter).
// uMode 0 renders one texel per row of blocks: that row's count. uMode 1
// renders one texel: x = 1 when the total reaches uCutCount (30 % of the
// blocks), y = the total.
precision highp float;
precision highp int;
precision highp isampler2D;
uniform isampler2D uCountSampler;
uniform int uMode;
uniform int uCutCount;
layout(location = 0) out ivec4 outCount;

void main() {
    ivec2 size = textureSize(uCountSampler, 0);
    int sum = 0;
    if (uMode == 0) {
        int y = int(gl_FragCoord.y);
        for (int x = 0; x < size.x; ++x) sum += texelFetch(uCountSampler, ivec2(x, y), 0).z;
        outCount = ivec4(sum, 0, 0, 0);
    } else {
        for (int y = 0; y < size.y; ++y) sum += texelFetch(uCountSampler, ivec2(0, y), 0).x;
        outCount = ivec4(sum >= uCutCount ? 1 : 0, sum, 0, 0);
    }
}
