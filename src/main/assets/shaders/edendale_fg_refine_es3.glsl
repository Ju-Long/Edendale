#version 300 es
// Motion Smoothing (G.2.1): Apple's motionEstimationRefine. One fragment per
// 4×4 block searches ±4 px around its 16×16 block's coarse vector, every
// pixel of the block, and leaves the coarse vector only for a clearly better
// match (Apple's order, dy then dx, and a strictly lower cost).
//
// For each dy, each of the block's 4 rows reads the 12 pixels of frame N−1
// that its 9 horizontal offsets reach, as three vec4s, and scores all 9 with
// fixed swizzles: 464 fetches where scoring each offset on its own takes
// 2,592 (74 ms at 1080p on the Pixel 3, Findings — G). There are no local
// arrays: Adreno keeps arrays indexed in loops in slow memory, which made an
// array version of this pass twice as slow. Output: xy = the refined offset
// from frame N to N−1, in search pixels.
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp isampler2D;
uniform sampler2D uPrevSampler;
uniform sampler2D uCurrSampler;
uniform isampler2D uCoarseSampler;
uniform int uCoarseBlockSize;
layout(location = 0) out ivec4 outMotion;

const float kMaxMotionCost = 0.016;
const float kCostPerPixel = kMaxMotionCost / 8.0;

ivec2 last;
ivec2 origin;
ivec2 base;

float prevAt(int x, int y) {
    return texelFetch(uPrevSampler, clamp(ivec2(x, y), ivec2(0), last), 0).r;
}

float currAt(int x, int y) {
    return texelFetch(uCurrSampler, min(ivec2(x, y), last), 0).r;
}

// One row r of the block against frame N−1 at dy: adds its share to each dx's sum (−4…−1, 0…3, 4).
void addRow(int r, int dy, vec4 cur, vec4 valid, inout vec4 low, inout vec4 high, inout float edge) {
    int y = origin.y + r + base.y + dy;
    int x = origin.x + base.x - 4;
    vec4 a = vec4(prevAt(x, y), prevAt(x + 1, y), prevAt(x + 2, y), prevAt(x + 3, y));
    vec4 b = vec4(prevAt(x + 4, y), prevAt(x + 5, y), prevAt(x + 6, y), prevAt(x + 7, y));
    vec4 c = vec4(prevAt(x + 8, y), prevAt(x + 9, y), prevAt(x + 10, y), prevAt(x + 11, y));
    low.x += dot(valid, abs(cur - a));
    low.y += dot(valid, abs(cur - vec4(a.yzw, b.x)));
    low.z += dot(valid, abs(cur - vec4(a.zw, b.xy)));
    low.w += dot(valid, abs(cur - vec4(a.w, b.xyz)));
    high.x += dot(valid, abs(cur - b));
    high.y += dot(valid, abs(cur - vec4(b.yzw, c.x)));
    high.z += dot(valid, abs(cur - vec4(b.zw, c.xy)));
    high.w += dot(valid, abs(cur - vec4(b.w, c.xyz)));
    edge += dot(valid, abs(cur - c));
}

void consider(float sad, int dx, int dy, float n, inout float bestCost, inout ivec2 bestOff) {
    if (dx == 0 && dy == 0) return;
    float cost = sad / n + kCostPerPixel * float(abs(dx) + abs(dy));
    if (cost < bestCost) {
        bestCost = cost;
        bestOff = base + ivec2(dx, dy);
    }
}

vec4 rowValid(int r, ivec2 frameSize) {
    if (origin.y + r >= frameSize.y) return vec4(0.0);
    return vec4(
        origin.x < frameSize.x ? 1.0 : 0.0,
        origin.x + 1 < frameSize.x ? 1.0 : 0.0,
        origin.x + 2 < frameSize.x ? 1.0 : 0.0,
        origin.x + 3 < frameSize.x ? 1.0 : 0.0);
}

vec4 rowCurr(int r) {
    int y = origin.y + r;
    return vec4(currAt(origin.x, y), currAt(origin.x + 1, y), currAt(origin.x + 2, y), currAt(origin.x + 3, y));
}

float baseRow(int r, vec4 cur, vec4 valid) {
    int y = origin.y + r + base.y;
    int x = origin.x + base.x;
    return dot(valid, abs(cur - vec4(prevAt(x, y), prevAt(x + 1, y), prevAt(x + 2, y), prevAt(x + 3, y))));
}

void main() {
    ivec2 frameSize = textureSize(uCurrSampler, 0);
    last = frameSize - 1;
    origin = ivec2(gl_FragCoord.xy) * 4;
    ivec2 coarseCell = min(origin / uCoarseBlockSize, textureSize(uCoarseSampler, 0) - 1);
    base = texelFetch(uCoarseSampler, coarseCell, 0).xy;

    vec4 c0 = rowCurr(0);
    vec4 c1 = rowCurr(1);
    vec4 c2 = rowCurr(2);
    vec4 c3 = rowCurr(3);
    vec4 v0 = rowValid(0, frameSize);
    vec4 v1 = rowValid(1, frameSize);
    vec4 v2 = rowValid(2, frameSize);
    vec4 v3 = rowValid(3, frameSize);
    float n = max(dot(v0 + v1 + v2 + v3, vec4(1.0)), 1.0);

    // The coarse vector itself, uncharged, is the one to beat.
    float bestCost = (baseRow(0, c0, v0) + baseRow(1, c1, v1) + baseRow(2, c2, v2) + baseRow(3, c3, v3)) / n;
    ivec2 bestOff = base;

    for (int dy = -4; dy <= 4; ++dy) {
        vec4 low = vec4(0.0);
        vec4 high = vec4(0.0);
        float edge = 0.0;
        addRow(0, dy, c0, v0, low, high, edge);
        addRow(1, dy, c1, v1, low, high, edge);
        addRow(2, dy, c2, v2, low, high, edge);
        addRow(3, dy, c3, v3, low, high, edge);
        consider(low.x, -4, dy, n, bestCost, bestOff);
        consider(low.y, -3, dy, n, bestCost, bestOff);
        consider(low.z, -2, dy, n, bestCost, bestOff);
        consider(low.w, -1, dy, n, bestCost, bestOff);
        consider(high.x, 0, dy, n, bestCost, bestOff);
        consider(high.y, 1, dy, n, bestCost, bestOff);
        consider(high.z, 2, dy, n, bestCost, bestOff);
        consider(high.w, 3, dy, n, bestCost, bestOff);
        consider(edge, 4, dy, n, bestCost, bestOff);
    }
    outMotion = ivec4(bestOff, 0, 0);
}
