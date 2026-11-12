#version 300 es
// Motion Smoothing (G.2.1, G.2.2): Apple's motionEstimationCoarse as a
// fragment pass (the G.1.3 probe, which matched Apple's results). One fragment
// per 16×16 block of frame N searches ±16 px of frame N−1 for the offset with
// the lowest cost: the mean absolute luma difference over every other pixel
// of the block, plus a charge per pixel of offset, so flat or noisy areas keep
// zero motion. Every even offset first, then the odd ones around the best.
//
// The output is an integer texel per block: xy = the offset from frame N to
// its match in N−1, in search pixels; z = 1 when even the best match differs
// by more than uUnmatchedError (counted for the scene cut, G.2.2).
precision highp float;
precision highp int;
precision highp sampler2D;
uniform sampler2D uPrevSampler;
uniform sampler2D uCurrSampler;
uniform int uBlockSize;
uniform int uSearchRadius;
uniform float uUnmatchedError;
layout(location = 0) out ivec4 outMotion;

// A candidate at the edge of the window must match this much better (mean
// luma difference) than zero motion.
const float kMaxMotionCost = 0.016;

ivec2 frameSize;
ivec2 blockOrigin;

float blockError(ivec2 off) {
    float sad = 0.0;
    float n = 0.0;
    for (int py = 0; py < uBlockSize; py += 2) {
        for (int px = 0; px < uBlockSize; px += 2) {
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
    int sr = uSearchRadius;
    float costPerPixel = kMaxMotionCost / float(2 * sr);

    // Start from zero motion; other candidates must beat it including their cost.
    ivec2 bestOff = ivec2(0);
    float bestError = blockError(bestOff);
    float bestCost = bestError;
    for (int dy = -sr; dy <= sr; dy += 2) {
        for (int dx = -sr; dx <= sr; dx += 2) {
            if (dx == 0 && dy == 0) continue;
            float error = blockError(ivec2(dx, dy));
            float cost = error + costPerPixel * float(abs(dx) + abs(dy));
            if (cost < bestCost) {
                bestCost = cost;
                bestError = error;
                bestOff = ivec2(dx, dy);
            }
        }
    }
    // The odd offsets the even search skipped, around the best one.
    ivec2 center = bestOff;
    for (int dy = -1; dy <= 1; ++dy) {
        for (int dx = -1; dx <= 1; ++dx) {
            if (dx == 0 && dy == 0) continue;
            ivec2 o = center + ivec2(dx, dy);
            if (abs(o.x) > sr || abs(o.y) > sr) continue;
            float error = blockError(o);
            float cost = error + costPerPixel * float(abs(o.x) + abs(o.y));
            if (cost < bestCost) {
                bestCost = cost;
                bestError = error;
                bestOff = o;
            }
        }
    }
    outMotion = ivec4(bestOff, bestError > uUnmatchedError ? 1 : 0, 0);
}
