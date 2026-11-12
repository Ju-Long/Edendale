#version 300 es
// Motion Smoothing (G.3.1): Apple's frameInterpolate, with its
// motionVectorDensify, motionVectorUpscale, and holdPreviousOnSceneCut folded
// in, so no full-size vector texture is written.
//
// 1. This pixel's vector: a bilinear blend of the four nearest 4×4 blocks'
//    refined vectors, then Apple's cross median against the blocks left and
//    right of it, scaled from search pixels to output pixels.
// 2. Two-way warp at uBlendTime (0.5, the midpoint): frame N−1 sampled at
//    pos + mv·t, frame N at pos − mv·(1 − t). The vector points from N to N−1.
// 3. Occlusion: when the two samples' luma differs by more than 0.12, one side
//    is covered; take the sample nearer in time (at the midpoint, Apple's
//    rule picks frame N). Otherwise blend by temporal distance.
// 4. Holes: a sample outside the frame drops out; with both out, frame N.
// 5. Scene cut: when the reduction flagged one, frame N−1 itself.
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp isampler2D;
uniform sampler2D uPrevSampler;
uniform sampler2D uCurrSampler;
uniform isampler2D uMotionSampler;
uniform isampler2D uCutSampler;
uniform int uBlockSize;
// Output pixels per search pixel: 1, or 2 when the search ran at half resolution.
uniform float uMotionScale;
uniform float uBlendTime;
out vec4 outColor;

const float kOcclusionThreshold = 0.12;

float luma(vec3 c) {
    return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

vec2 blockVector(ivec2 cell, ivec2 grid) {
    return vec2(texelFetch(uMotionSampler, clamp(cell, ivec2(0), grid - 1), 0).xy);
}

vec2 median3(vec2 a, vec2 b, vec2 c) {
    return a + b + c - min(a, min(b, c)) - max(a, max(b, c));
}

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    if (texelFetch(uCutSampler, ivec2(0), 0).x != 0) {
        outColor = vec4(texelFetch(uPrevSampler, pixel, 0).rgb, 1.0);
        return;
    }

    vec2 size = vec2(textureSize(uCurrSampler, 0));
    ivec2 grid = textureSize(uMotionSampler, 0);
    vec2 pos = gl_FragCoord.xy;

    // Where this pixel's centre falls on the block grid.
    vec2 block = (pos / uMotionScale) / float(uBlockSize) - 0.5;
    ivec2 b0 = ivec2(floor(block));
    vec2 f = block - vec2(b0);
    vec2 mv = mix(
        mix(blockVector(b0, grid), blockVector(b0 + ivec2(1, 0), grid), f.x),
        mix(blockVector(b0 + ivec2(0, 1), grid), blockVector(b0 + ivec2(1, 1), grid), f.x),
        f.y);
    ivec2 row = ivec2(int(block.x), int(block.y + 0.5));
    vec2 left = pixel.x > 0 ? blockVector(row, grid) : mv;
    vec2 right = blockVector(row + ivec2(1, 0), grid);
    mv = median3(left, mv, right) * uMotionScale;

    vec2 fwdPos = pos + mv * uBlendTime;
    vec2 bwdPos = pos - mv * (1.0 - uBlendTime);
    bool fwdValid = all(greaterThanEqual(fwdPos, vec2(0.0))) && all(lessThan(fwdPos, size));
    bool bwdValid = all(greaterThanEqual(bwdPos, vec2(0.0))) && all(lessThan(bwdPos, size));
    // Linear filtering with clamp to edge: Apple's clamped bilinear sample.
    vec4 fwd = fwdValid ? texture(uPrevSampler, fwdPos / size) : vec4(0.0);
    vec4 bwd = bwdValid ? texture(uCurrSampler, bwdPos / size) : vec4(0.0);

    vec4 result;
    if (fwdValid && bwdValid) {
        if (abs(luma(fwd.rgb) - luma(bwd.rgb)) > kOcclusionThreshold) {
            result = length(fwdPos - pos) < length(bwdPos - pos) ? fwd : bwd;
        } else {
            result = fwd * (1.0 - uBlendTime) + bwd * uBlendTime;
        }
    } else if (fwdValid) {
        result = fwd;
    } else if (bwdValid) {
        result = bwd;
    } else {
        result = texelFetch(uCurrSampler, pixel, 0);
    }
    outColor = vec4(clamp(result.rgb, 0.0, 1.0), 1.0);
}
