#version 300 es
// Motion Smoothing (G.2.1): Apple's motionEstimationRefine. One fragment per
// 4×4 block searches ±4 px around its 16×16 block's coarse vector, every
// pixel of the block, and leaves the coarse vector only for a clearly better
// match. Output: xy = the refined offset from frame N to N−1, in search pixels.
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp isampler2D;
uniform sampler2D uPrevSampler;
uniform sampler2D uCurrSampler;
uniform isampler2D uCoarseSampler;
uniform int uBlockSize;
uniform int uCoarseBlockSize;
layout(location = 0) out ivec4 outMotion;

const float kMaxMotionCost = 0.016;
const int kSearchRadius = 4;

ivec2 frameSize;
ivec2 blockOrigin;

float blockError(ivec2 off) {
    float sad = 0.0;
    float n = 0.0;
    for (int py = 0; py < uBlockSize; ++py) {
        for (int px = 0; px < uBlockSize; ++px) {
            ivec2 c = blockOrigin + ivec2(px, py);
            if (c.x >= frameSize.x || c.y >= frameSize.y) continue;
            ivec2 p = clamp(c + off, ivec2(0), frameSize - 1);
            sad += abs(texelFetch(uCurrSampler, c, 0).r - texelFetch(uPrevSampler, p, 0).r);
            n += 1.0;
        }
    }
    return n > 0.0 ? sad / n : 0.0;
}

void main() {
    frameSize = textureSize(uCurrSampler, 0);
    blockOrigin = ivec2(gl_FragCoord.xy) * uBlockSize;
    ivec2 coarseCell = min(blockOrigin / uCoarseBlockSize, textureSize(uCoarseSampler, 0) - 1);
    ivec2 base = texelFetch(uCoarseSampler, coarseCell, 0).xy;

    float costPerPixel = kMaxMotionCost / float(2 * kSearchRadius);
    ivec2 bestOff = base;
    float bestCost = blockError(base);
    for (int dy = -kSearchRadius; dy <= kSearchRadius; ++dy) {
        for (int dx = -kSearchRadius; dx <= kSearchRadius; ++dx) {
            if (dx == 0 && dy == 0) continue;
            ivec2 off = base + ivec2(dx, dy);
            float cost = blockError(off) + costPerPixel * float(abs(dx) + abs(dy));
            if (cost < bestCost) {
                bestCost = cost;
                bestOff = off;
            }
        }
    }
    outMotion = ivec4(bestOff, 0, 0);
}
