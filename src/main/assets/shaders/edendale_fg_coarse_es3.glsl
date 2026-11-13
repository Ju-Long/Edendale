#version 300 es
// Motion Smoothing (G.2.1): the first half of Apple's motionEstimationCoarse,
// spread across the GPU. One fragment per 16×16 block and even vertical
// offset dy (−16…16, step 2): 17 fragments per block, each scoring all 17
// even horizontal offsets of its dy over every other pixel of the block.
// Each sampled row of the block is read once, with the 24 samples of frame
// N−1 that its 17 offsets reach (as six vec4s, compared with fixed
// swizzles: Adreno keeps arrays indexed in loops in slow memory), so the
// fragment fetches 256 texels where scoring each offset on its own fetches
// 2,176. The Pixel 3 needed 525 ms at 1080p for the search as one fragment
// per block looping over all 289 offsets (Findings — G).
//
// Within the row it keeps Apple's order and rule: the cost is the mean luma
// difference plus a charge per pixel of offset, and only a strictly lower
// cost wins, so the first of equal costs stays. Sums go out in luma steps
// (the luma is 8-bit, so sum × 255 is a whole number), and the pick pass
// recomputes the same costs from them. Output: x = the row's best sum,
// y = its offset index (0…16, −1 for none), z = the zero offset's sum (in
// the dy = 0 row, where zero motion is the starting point, not a candidate).
precision highp float;
precision highp int;
precision highp sampler2D;
uniform sampler2D uPrevSampler;
uniform sampler2D uCurrSampler;
layout(location = 0) out ivec4 outRow;

const int kBlock = 16;
const int kRadius = 16;
const int kSide = 17;
const float kMaxMotionCost = 0.016;

ivec2 last;

float prevAt(int x, int y) {
    return texelFetch(uPrevSampler, ivec2(clamp(x, 0, last.x), y), 0).r;
}

float currAt(int x, int y) {
    return texelFetch(uCurrSampler, ivec2(min(x, last.x), y), 0).r;
}

void consider(float sad, int i, int dy, float scale, float costPerPixel,
              inout float bestCost, inout int bestSum, inout int bestIndex, inout int zeroSum) {
    int sum = int(round(sad * 255.0));
    if (dy == 0 && i == kSide / 2) {
        zeroSum = sum;
        return;
    }
    float cost = float(sum) * scale + costPerPixel * float(abs(i * 2 - kRadius) + abs(dy));
    if (cost < bestCost) {
        bestCost = cost;
        bestSum = sum;
        bestIndex = i;
    }
}

void main() {
    ivec2 frameSize = textureSize(uCurrSampler, 0);
    last = frameSize - 1;
    ivec2 cell = ivec2(gl_FragCoord.xy);
    int blockY = cell.y / kSide;
    int j = cell.y - blockY * kSide;
    int dy = j * 2 - kRadius;
    ivec2 origin = ivec2(cell.x, blockY) * kBlock;
    int x0 = origin.x;
    // Which of the block's 8 sampled columns lie inside the frame.
    vec4 vA = vec4(
        x0 < frameSize.x ? 1.0 : 0.0, x0 + 2 < frameSize.x ? 1.0 : 0.0,
        x0 + 4 < frameSize.x ? 1.0 : 0.0, x0 + 6 < frameSize.x ? 1.0 : 0.0);
    vec4 vB = vec4(
        x0 + 8 < frameSize.x ? 1.0 : 0.0, x0 + 10 < frameSize.x ? 1.0 : 0.0,
        x0 + 12 < frameSize.x ? 1.0 : 0.0, x0 + 14 < frameSize.x ? 1.0 : 0.0);

    // Sums for offset index i = 0…16 (dx = 2i − 16): s0 holds 0…3, s1 4…7, s2 8…11, s3 12…15, s4 16.
    vec4 s0 = vec4(0.0);
    vec4 s1 = vec4(0.0);
    vec4 s2 = vec4(0.0);
    vec4 s3 = vec4(0.0);
    float s4 = 0.0;
    for (int py = 0; py < kBlock; py += 2) {
        int cy = origin.y + py;
        if (cy >= frameSize.y) break;
        int y = clamp(cy + dy, 0, last.y);
        vec4 cA = vec4(currAt(x0, cy), currAt(x0 + 2, cy), currAt(x0 + 4, cy), currAt(x0 + 6, cy));
        vec4 cB = vec4(currAt(x0 + 8, cy), currAt(x0 + 10, cy), currAt(x0 + 12, cy), currAt(x0 + 14, cy));
        // Frame N−1 at x = x0 − 16 + 2m, m = 0…23: offset index i reaches m = k + i.
        int px0 = x0 - kRadius;
        vec4 p0 = vec4(prevAt(px0, y), prevAt(px0 + 2, y), prevAt(px0 + 4, y), prevAt(px0 + 6, y));
        vec4 p1 = vec4(prevAt(px0 + 8, y), prevAt(px0 + 10, y), prevAt(px0 + 12, y), prevAt(px0 + 14, y));
        vec4 p2 = vec4(prevAt(px0 + 16, y), prevAt(px0 + 18, y), prevAt(px0 + 20, y), prevAt(px0 + 22, y));
        vec4 p3 = vec4(prevAt(px0 + 24, y), prevAt(px0 + 26, y), prevAt(px0 + 28, y), prevAt(px0 + 30, y));
        vec4 p4 = vec4(prevAt(px0 + 32, y), prevAt(px0 + 34, y), prevAt(px0 + 36, y), prevAt(px0 + 38, y));
        vec4 p5 = vec4(prevAt(px0 + 40, y), prevAt(px0 + 42, y), prevAt(px0 + 44, y), prevAt(px0 + 46, y));
        s0.x += dot(vA, abs(cA - p0)) + dot(vB, abs(cB - p1));
        s0.y += dot(vA, abs(cA - vec4(p0.yzw, p1.x))) + dot(vB, abs(cB - vec4(p1.yzw, p2.x)));
        s0.z += dot(vA, abs(cA - vec4(p0.zw, p1.xy))) + dot(vB, abs(cB - vec4(p1.zw, p2.xy)));
        s0.w += dot(vA, abs(cA - vec4(p0.w, p1.xyz))) + dot(vB, abs(cB - vec4(p1.w, p2.xyz)));
        s1.x += dot(vA, abs(cA - p1)) + dot(vB, abs(cB - p2));
        s1.y += dot(vA, abs(cA - vec4(p1.yzw, p2.x))) + dot(vB, abs(cB - vec4(p2.yzw, p3.x)));
        s1.z += dot(vA, abs(cA - vec4(p1.zw, p2.xy))) + dot(vB, abs(cB - vec4(p2.zw, p3.xy)));
        s1.w += dot(vA, abs(cA - vec4(p1.w, p2.xyz))) + dot(vB, abs(cB - vec4(p2.w, p3.xyz)));
        s2.x += dot(vA, abs(cA - p2)) + dot(vB, abs(cB - p3));
        s2.y += dot(vA, abs(cA - vec4(p2.yzw, p3.x))) + dot(vB, abs(cB - vec4(p3.yzw, p4.x)));
        s2.z += dot(vA, abs(cA - vec4(p2.zw, p3.xy))) + dot(vB, abs(cB - vec4(p3.zw, p4.xy)));
        s2.w += dot(vA, abs(cA - vec4(p2.w, p3.xyz))) + dot(vB, abs(cB - vec4(p3.w, p4.xyz)));
        s3.x += dot(vA, abs(cA - p3)) + dot(vB, abs(cB - p4));
        s3.y += dot(vA, abs(cA - vec4(p3.yzw, p4.x))) + dot(vB, abs(cB - vec4(p4.yzw, p5.x)));
        s3.z += dot(vA, abs(cA - vec4(p3.zw, p4.xy))) + dot(vB, abs(cB - vec4(p4.zw, p5.xy)));
        s3.w += dot(vA, abs(cA - vec4(p3.w, p4.xyz))) + dot(vB, abs(cB - vec4(p4.w, p5.xyz)));
        s4 += dot(vA, abs(cA - p4)) + dot(vB, abs(cB - p5));
    }

    // Samples inside the frame: every other pixel of the block.
    int nx = clamp((frameSize.x - origin.x + 1) / 2, 0, 8);
    int ny = clamp((frameSize.y - origin.y + 1) / 2, 0, 8);
    float scale = 1.0 / (255.0 * float(max(nx * ny, 1)));
    float costPerPixel = kMaxMotionCost / float(2 * kRadius);

    int bestSum = 0;
    int bestIndex = -1;
    float bestCost = 3.0e38;
    int zeroSum = 0;
    consider(s0.x, 0, dy, scale, costPerPixel, bestCost, bestSum, bestIndex, zeroSum);
    consider(s0.y, 1, dy, scale, costPerPixel, bestCost, bestSum, bestIndex, zeroSum);
    consider(s0.z, 2, dy, scale, costPerPixel, bestCost, bestSum, bestIndex, zeroSum);
    consider(s0.w, 3, dy, scale, costPerPixel, bestCost, bestSum, bestIndex, zeroSum);
    consider(s1.x, 4, dy, scale, costPerPixel, bestCost, bestSum, bestIndex, zeroSum);
    consider(s1.y, 5, dy, scale, costPerPixel, bestCost, bestSum, bestIndex, zeroSum);
    consider(s1.z, 6, dy, scale, costPerPixel, bestCost, bestSum, bestIndex, zeroSum);
    consider(s1.w, 7, dy, scale, costPerPixel, bestCost, bestSum, bestIndex, zeroSum);
    consider(s2.x, 8, dy, scale, costPerPixel, bestCost, bestSum, bestIndex, zeroSum);
    consider(s2.y, 9, dy, scale, costPerPixel, bestCost, bestSum, bestIndex, zeroSum);
    consider(s2.z, 10, dy, scale, costPerPixel, bestCost, bestSum, bestIndex, zeroSum);
    consider(s2.w, 11, dy, scale, costPerPixel, bestCost, bestSum, bestIndex, zeroSum);
    consider(s3.x, 12, dy, scale, costPerPixel, bestCost, bestSum, bestIndex, zeroSum);
    consider(s3.y, 13, dy, scale, costPerPixel, bestCost, bestSum, bestIndex, zeroSum);
    consider(s3.z, 14, dy, scale, costPerPixel, bestCost, bestSum, bestIndex, zeroSum);
    consider(s3.w, 15, dy, scale, costPerPixel, bestCost, bestSum, bestIndex, zeroSum);
    consider(s4, 16, dy, scale, costPerPixel, bestCost, bestSum, bestIndex, zeroSum);
    outRow = ivec4(bestSum, bestIndex, zeroSum, 0);
}
