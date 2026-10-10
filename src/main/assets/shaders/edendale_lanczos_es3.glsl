#version 300 es
// Lanczos-2 upscale (F.3.2): a port of Apple's LanczosUpscaler.metal, the
// fallback where EASU isn't used.
precision highp float;
uniform sampler2D uTexSampler;
uniform vec2 uOutputSize;
out vec4 outColor;

const float PI = 3.14159265358979323846;

float lanczosWeight(float x, float a) {
    float ax = abs(x);
    if (ax < 1e-5) return 1.0;
    if (ax >= a) return 0.0;
    float piX = PI * x;
    return (sin(piX) * sin(piX / a)) / ((piX * piX) / a);
}

void main() {
    ivec2 inSize = textureSize(uTexSampler, 0);
    vec2 outPos = floor(gl_FragCoord.xy);
    float u = ((outPos.x + 0.5) * float(inSize.x) / uOutputSize.x) - 0.5;
    float v = ((outPos.y + 0.5) * float(inSize.y) / uOutputSize.y) - 0.5;
    int centerU = int(floor(u));
    int centerV = int(floor(v));
    vec4 colorSum = vec4(0.0);
    float totalWeight = 0.0;
    for (int dy = -1; dy <= 2; ++dy) {
        int sampleY = clamp(centerV + dy, 0, inSize.y - 1);
        float wy = lanczosWeight(v - float(centerV + dy), 2.0);
        for (int dx = -1; dx <= 2; ++dx) {
            int sampleX = clamp(centerU + dx, 0, inSize.x - 1);
            float wx = lanczosWeight(u - float(centerU + dx), 2.0);
            float w = wx * wy;
            colorSum += texelFetch(uTexSampler, ivec2(sampleX, sampleY), 0) * w;
            totalWeight += w;
        }
    }
    vec4 result = totalWeight > 1e-5
        ? colorSum / totalWeight
        : texelFetch(uTexSampler, clamp(ivec2(round(u), round(v)), ivec2(0), inSize - 1), 0);
    outColor = clamp(result, 0.0, 1.0);
}
