#version 300 es
// Motion Smoothing (G.2): the Rec. 709 luma of frame N, at the motion
// search's resolution, so the search reads one byte per sample (Findings —
// G.1: a luma prepass saves about a quarter). With uScale 2 each output pixel
// averages a 2×2 block, as Apple's bilinearDownscale does for sources wider
// than 1920 px; the governor's half-resolution step uses it too.
precision highp float;
precision highp int;
precision highp sampler2D;
uniform sampler2D uTexSampler;
uniform int uScale;
out vec4 outLuma;

float luma(vec3 c) {
    return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy) * uScale;
    if (uScale == 1) {
        outLuma = vec4(luma(texelFetch(uTexSampler, p, 0).rgb), 0.0, 0.0, 1.0);
        return;
    }
    // A 2×2 block; past the last row or column the edge pixel stands in.
    ivec2 last = textureSize(uTexSampler, 0) - 1;
    vec3 a = texelFetch(uTexSampler, min(p, last), 0).rgb;
    vec3 b = texelFetch(uTexSampler, min(p + ivec2(1, 0), last), 0).rgb;
    vec3 c = texelFetch(uTexSampler, min(p + ivec2(0, 1), last), 0).rgb;
    vec3 d = texelFetch(uTexSampler, min(p + ivec2(1, 1), last), 0).rgb;
    outLuma = vec4(luma((a + b + c + d) * 0.25), 0.0, 0.0, 1.0);
}
