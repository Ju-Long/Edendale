#version 300 es
// G.1.3: motionEstimationCoarse from Apple's MotionEstimation.metal as a
// fragment pass. One fragment per 16×16 block of frame N searches ±16 px of
// frame N−1 (every other offset, then the odd offsets around the best), with
// Apple's per-pixel charge for leaving zero motion. The test prepends
// "#define LUMA_INPUT" to read a one-channel luma texture instead of RGBA.
precision highp float;
precision highp int;
precision highp sampler2D;
uniform sampler2D uPrevSampler;
uniform sampler2D uCurrSampler;
uniform int uBlockSize;
uniform int uSearchRadius;
uniform float uUnmatchedError;
// xy: offset from frame N to its match in N−1, over the frame size; z: the
// match's mean luma difference; w: 1 when unmatched (the scene-cut input).
out vec4 outMotion;

const float kMaxMotionCost = 0.016;

ivec2 frameSize;
ivec2 blockOrigin;

float lumaAt(sampler2D frame, ivec2 p) {
#ifdef LUMA_INPUT
    return texelFetch(frame, p, 0).r;
#else
    return dot(texelFetch(frame, p, 0).rgb, vec3(0.2126, 0.7152, 0.0722));
#endif
}

// Mean absolute luma difference against frame N−1 at `off`, sampling every other pixel.
float blockError(ivec2 off) {
    float sad = 0.0;
    float n = 0.0;
    for (int py = 0; py < uBlockSize; py += 2) {
        for (int px = 0; px < uBlockSize; px += 2) {
            ivec2 c = blockOrigin + ivec2(px, py);
            if (c.x >= frameSize.x || c.y >= frameSize.y) continue;
            ivec2 p = clamp(c + off, ivec2(0), frameSize - 1);
            sad += abs(lumaAt(uCurrSampler, c) - lumaAt(uPrevSampler, p));
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
    // The odd offsets the step-2 search skipped around the best one.
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
    outMotion = vec4(vec2(bestOff) / vec2(frameSize), bestError, bestError > uUnmatchedError ? 1.0 : 0.0);
}
