#version 300 es
// Contrast Adaptive Sharpening (F.4): a port of Apple's CASShader.metal (its
// variant, not AMD's reference), so Sharpness means the same on both
// platforms. Sharpness 0 is the identity.
//
// Apple's last step, (b + d + f + h)·w + e over 1 + 4w, is 0/0 at Sharpness 1
// wherever amp is 1 — every flat area at or below mid-gray — which turns it
// black. It's written here as e + 4w·(mean − e) / (1 + 4w), the same value
// wherever Apple's is defined, with the divisor kept off zero: a flat area
// (mean = e) stays itself at every Sharpness.
precision highp float;
uniform sampler2D uTexSampler;
uniform float uSharpness;
out vec4 outColor;

void main() {
    ivec2 size = textureSize(uTexSampler, 0);
    ivec2 p = ivec2(gl_FragCoord.xy);
    vec4 e = texelFetch(uTexSampler, p, 0);
    if (uSharpness <= 0.0) {
        outColor = e;
        return;
    }
    int xm1 = max(p.x - 1, 0);
    int xp1 = min(p.x + 1, size.x - 1);
    int ym1 = max(p.y - 1, 0);
    int yp1 = min(p.y + 1, size.y - 1);
    //   a b c
    //   d e f
    //   g h i
    vec3 a = texelFetch(uTexSampler, ivec2(xm1, ym1), 0).rgb;
    vec3 b = texelFetch(uTexSampler, ivec2(p.x, ym1), 0).rgb;
    vec3 c = texelFetch(uTexSampler, ivec2(xp1, ym1), 0).rgb;
    vec3 d = texelFetch(uTexSampler, ivec2(xm1, p.y), 0).rgb;
    vec3 f = texelFetch(uTexSampler, ivec2(xp1, p.y), 0).rgb;
    vec3 g = texelFetch(uTexSampler, ivec2(xm1, yp1), 0).rgb;
    vec3 h = texelFetch(uTexSampler, ivec2(p.x, yp1), 0).rgb;
    vec3 i = texelFetch(uTexSampler, ivec2(xp1, yp1), 0).rgb;

    vec3 minRGB = min(min(min(d, e.rgb), f), min(b, h));
    vec3 maxRGB = max(max(max(d, e.rgb), f), max(b, h));
    vec3 minRGB2 = min(min(min(a, c), g), i);
    minRGB2 = min(minRGB, minRGB2);
    vec3 maxRGB2 = max(max(max(a, c), g), i);
    maxRGB2 = max(maxRGB, maxRGB2);
    minRGB = minRGB + minRGB2;
    maxRGB = maxRGB + maxRGB2;

    vec3 ampRGB = clamp(min(minRGB, 2.0 - maxRGB) / max(maxRGB, vec3(1e-5)), 0.0, 1.0);
    float peak = -0.125 - (clamp(uSharpness, 0.0, 1.0) * 0.125);
    vec3 wRGB = sqrt(ampRGB) * peak;
    vec3 mean = (b + d + f + h) * 0.25;
    vec3 weight = max(1.0 + 4.0 * wRGB, vec3(1e-4));
    vec3 result = e.rgb + 4.0 * wRGB * (mean - e.rgb) / weight;
    outColor = vec4(clamp(result, 0.0, 1.0), e.a);
}
