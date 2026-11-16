#version 300 es
// Motion Smoothing (G.2.1, G.2.2): the second half of Apple's
// motionEstimationCoarse. One fragment per 16×16 block starts from zero
// motion and takes the 17 row winners from edendale_fg_coarse_es3.glsl in
// Apple's order (dy, then dx) with Apple's cost, the mean luma difference
// plus a charge per pixel of offset; a strictly lower cost wins, so the same
// offset wins as in Apple's single loop over all 289. Then it tries the odd
// offsets around the best one: each sampled row of the block reads the 3 ×
// 17 pixels of frame N−1 that all 8 neighbours reach, as vec4s compared
// with fixed swizzles (no arrays, which Adreno keeps in slow memory).
//
// The output is an integer texel per block: xy = the offset from frame N to
// its match in N−1, in search pixels; z = 1 when even the best match differs
// by more than uUnmatchedError (counted for the scene cut, G.2.2).
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp isampler2D;
uniform sampler2D uPrevSampler;
uniform sampler2D uCurrSampler;
uniform isampler2D uRowSampler;
uniform float uUnmatchedError;
layout(location = 0) out ivec4 outMotion;

const int kBlock = 16;
const int kRadius = 16;
const int kSide = 17;
// A candidate at the edge of the window must match this much better (mean
// luma difference) than zero motion.
const float kMaxMotionCost = 0.016;

ivec2 last;

float prevAt(int x, int y) {
    return texelFetch(uPrevSampler, ivec2(clamp(x, 0, last.x), y), 0).r;
}

float currAt(int x, int y) {
    return texelFetch(uCurrSampler, ivec2(min(x, last.x), y), 0).r;
}

// One row of frame N−1 against the block's sampled row: the sums for dx = −1, 0, +1
// around the centre, where x0 is the centre's column minus one.
vec3 rowSums(int x0, int y, vec4 cA, vec4 cB, vec4 vA, vec4 vB) {
    vec4 q0 = vec4(prevAt(x0, y), prevAt(x0 + 1, y), prevAt(x0 + 2, y), prevAt(x0 + 3, y));
    vec4 q1 = vec4(prevAt(x0 + 4, y), prevAt(x0 + 5, y), prevAt(x0 + 6, y), prevAt(x0 + 7, y));
    vec4 q2 = vec4(prevAt(x0 + 8, y), prevAt(x0 + 9, y), prevAt(x0 + 10, y), prevAt(x0 + 11, y));
    vec4 q3 = vec4(prevAt(x0 + 12, y), prevAt(x0 + 13, y), prevAt(x0 + 14, y), prevAt(x0 + 15, y));
    float q4 = prevAt(x0 + 16, y);
    // Sample k of the row meets column 2k + 1 + dx.
    float left = dot(vA, abs(cA - vec4(q0.x, q0.z, q1.x, q1.z))) + dot(vB, abs(cB - vec4(q2.x, q2.z, q3.x, q3.z)));
    float middle = dot(vA, abs(cA - vec4(q0.y, q0.w, q1.y, q1.w))) + dot(vB, abs(cB - vec4(q2.y, q2.w, q3.y, q3.w)));
    float right = dot(vA, abs(cA - vec4(q0.z, q1.x, q1.z, q2.x))) + dot(vB, abs(cB - vec4(q2.z, q3.x, q3.z, q4)));
    return vec3(left, middle, right);
}

void consider(float sad, ivec2 o, float n, float costPerPixel,
              inout float bestCost, inout float bestError, inout ivec2 bestOff) {
    if (abs(o.x) > kRadius || abs(o.y) > kRadius) return;
    float error = sad / n;
    float cost = error + costPerPixel * float(abs(o.x) + abs(o.y));
    if (cost < bestCost) {
        bestCost = cost;
        bestError = error;
        bestOff = o;
    }
}

void main() {
    ivec2 frameSize = textureSize(uCurrSampler, 0);
    last = frameSize - 1;
    ivec2 block = ivec2(gl_FragCoord.xy);
    ivec2 origin = block * kBlock;
    // Samples inside the frame: every other pixel of the block.
    int nx = clamp((frameSize.x - origin.x + 1) / 2, 0, kBlock / 2);
    int ny = clamp((frameSize.y - origin.y + 1) / 2, 0, kBlock / 2);
    float n = float(max(nx * ny, 1));
    float scale = 1.0 / (255.0 * n);
    float costPerPixel = kMaxMotionCost / float(2 * kRadius);

    // Start from zero motion; other candidates must beat it including their cost.
    ivec2 bestOff = ivec2(0);
    float bestError = float(texelFetch(uRowSampler, ivec2(block.x, block.y * kSide + kSide / 2), 0).z) * scale;
    float bestCost = bestError;
    for (int j = 0; j < kSide; ++j) {
        ivec4 row = texelFetch(uRowSampler, ivec2(block.x, block.y * kSide + j), 0);
        if (row.y < 0) continue;
        ivec2 off = ivec2(row.y, j) * 2 - kRadius;
        float error = float(row.x) * scale;
        float cost = error + costPerPixel * float(abs(off.x) + abs(off.y));
        if (cost < bestCost) {
            bestCost = cost;
            bestError = error;
            bestOff = off;
        }
    }

    // The odd offsets the even search skipped, around the best one.
    ivec2 center = bestOff;
    int x0 = origin.x;
    vec4 vA = vec4(
        x0 < frameSize.x ? 1.0 : 0.0, x0 + 2 < frameSize.x ? 1.0 : 0.0,
        x0 + 4 < frameSize.x ? 1.0 : 0.0, x0 + 6 < frameSize.x ? 1.0 : 0.0);
    vec4 vB = vec4(
        x0 + 8 < frameSize.x ? 1.0 : 0.0, x0 + 10 < frameSize.x ? 1.0 : 0.0,
        x0 + 12 < frameSize.x ? 1.0 : 0.0, x0 + 14 < frameSize.x ? 1.0 : 0.0);
    vec3 up = vec3(0.0);
    vec3 level = vec3(0.0);
    vec3 down = vec3(0.0);
    int left = x0 + center.x - 1;
    for (int py = 0; py < kBlock; py += 2) {
        int cy = origin.y + py;
        if (cy >= frameSize.y) break;
        vec4 cA = vec4(currAt(x0, cy), currAt(x0 + 2, cy), currAt(x0 + 4, cy), currAt(x0 + 6, cy));
        vec4 cB = vec4(currAt(x0 + 8, cy), currAt(x0 + 10, cy), currAt(x0 + 12, cy), currAt(x0 + 14, cy));
        int y = cy + center.y;
        up += rowSums(left, clamp(y - 1, 0, last.y), cA, cB, vA, vB);
        level += rowSums(left, clamp(y, 0, last.y), cA, cB, vA, vB);
        down += rowSums(left, clamp(y + 1, 0, last.y), cA, cB, vA, vB);
    }
    consider(up.x, center + ivec2(-1, -1), n, costPerPixel, bestCost, bestError, bestOff);
    consider(up.y, center + ivec2(0, -1), n, costPerPixel, bestCost, bestError, bestOff);
    consider(up.z, center + ivec2(1, -1), n, costPerPixel, bestCost, bestError, bestOff);
    consider(level.x, center + ivec2(-1, 0), n, costPerPixel, bestCost, bestError, bestOff);
    consider(level.z, center + ivec2(1, 0), n, costPerPixel, bestCost, bestError, bestOff);
    consider(down.x, center + ivec2(-1, 1), n, costPerPixel, bestCost, bestError, bestOff);
    consider(down.y, center + ivec2(0, 1), n, costPerPixel, bestCost, bestError, bestOff);
    consider(down.z, center + ivec2(1, 1), n, costPerPixel, bestCost, bestError, bestOff);
    outMotion = ivec4(bestOff, bestError > uUnmatchedError ? 1 : 0, 0);
}
