#include <metal_stdlib>
using namespace metal;

// Luma from BGRA pixel (Rec.709 coefficients).
inline float luma(float4 c) {
    return dot(c.rgb, float3(0.2126f, 0.7152f, 0.0722f));
}

// ---------------------------------------------------------------------------
// Pass 1 — Coarse block motion estimation (16×16 macroblocks).
//
// Each thread handles one macroblock.  For the block in `currFrame` at grid
// position `gid`, search a window in `prevFrame` centred on the same position
// and find the offset that minimises the sum-of-absolute-luma-differences (SAD).
// The winning motion vector is written to `motionOut` (RG16Float, one texel
// per macroblock).
// ---------------------------------------------------------------------------
kernel void motionEstimationCoarse(
    texture2d<float, access::read>  prevFrame     [[texture(0)]],
    texture2d<float, access::read>  currFrame     [[texture(1)]],
    texture2d<float, access::write> motionOut     [[texture(2)]],
    constant uint                   &blockSize    [[buffer(0)]],
    constant uint                   &searchRadius [[buffer(1)]],
    uint2                           gid           [[thread_position_in_grid]])
{
    uint mvW = motionOut.get_width();
    uint mvH = motionOut.get_height();
    if (gid.x >= mvW || gid.y >= mvH) return;

    uint frameW = currFrame.get_width();
    uint frameH = currFrame.get_height();

    uint bx = gid.x * blockSize;
    uint by = gid.y * blockSize;

    float bestSAD = 1e30f;
    int2  bestOff = int2(0, 0);

    int sr = int(searchRadius);

    for (int dy = -sr; dy <= sr; dy += 2) {
        for (int dx = -sr; dx <= sr; dx += 2) {
            float sad = 0.0f;
            for (uint py = 0; py < blockSize; py += 2) {
                for (uint px = 0; px < blockSize; px += 2) {
                    uint cx = bx + px;
                    uint cy = by + py;
                    if (cx >= frameW || cy >= frameH) continue;

                    int px2 = int(cx) + dx;
                    int py2 = int(cy) + dy;
                    px2 = clamp(px2, 0, int(frameW) - 1);
                    py2 = clamp(py2, 0, int(frameH) - 1);

                    float lc = luma(currFrame.read(uint2(cx, cy)));
                    float lp = luma(prevFrame.read(uint2(px2, py2)));
                    sad += abs(lc - lp);
                }
            }
            if (sad < bestSAD) {
                bestSAD = sad;
                bestOff = int2(dx, dy);
            }
        }
    }

    // Sub-pixel refinement: search ±1 around best integer offset
    int2 center = bestOff;
    for (int dy = -1; dy <= 1; ++dy) {
        for (int dx = -1; dx <= 1; ++dx) {
            if (dx == 0 && dy == 0) continue;
            int ox = center.x + dx;
            int oy = center.y + dy;
            if (abs(ox) > sr || abs(oy) > sr) continue;

            float sad = 0.0f;
            for (uint py = 0; py < blockSize; py += 2) {
                for (uint px = 0; px < blockSize; px += 2) {
                    uint cx = bx + px;
                    uint cy = by + py;
                    if (cx >= frameW || cy >= frameH) continue;

                    int px2 = clamp(int(cx) + ox, 0, int(frameW) - 1);
                    int py2 = clamp(int(cy) + oy, 0, int(frameH) - 1);

                    float lc = luma(currFrame.read(uint2(cx, cy)));
                    float lp = luma(prevFrame.read(uint2(px2, py2)));
                    sad += abs(lc - lp);
                }
            }
            if (sad < bestSAD) {
                bestSAD = sad;
                bestOff = int2(ox, oy);
            }
        }
    }

    // Store motion vector (pixel displacement from curr → prev).
    // Normalise to [-1, 1] range relative to frame dimensions for portability.
    float2 mv = float2(float(bestOff.x) / float(frameW),
                        float(bestOff.y) / float(frameH));
    motionOut.write(float4(mv.x, mv.y, 0.0f, 0.0f), gid);
}

// ---------------------------------------------------------------------------
// Pass 2 — Refine motion vectors at 4×4 sub-block granularity.
//
// Each thread handles one 4×4 sub-block.  It reads the coarse vector for the
// enclosing macroblock and searches a ±4 pixel window around it to find a
// more precise match.
// ---------------------------------------------------------------------------
kernel void motionEstimationRefine(
    texture2d<float, access::read>  prevFrame     [[texture(0)]],
    texture2d<float, access::read>  currFrame     [[texture(1)]],
    texture2d<float, access::read>  coarseMV      [[texture(2)]],
    texture2d<float, access::write> refinedMV     [[texture(3)]],
    constant uint                   &blockSize    [[buffer(0)]],
    constant uint                   &coarseBlock  [[buffer(1)]],
    uint2                           gid           [[thread_position_in_grid]])
{
    uint mvW = refinedMV.get_width();
    uint mvH = refinedMV.get_height();
    if (gid.x >= mvW || gid.y >= mvH) return;

    uint frameW = currFrame.get_width();
    uint frameH = currFrame.get_height();

    uint bx = gid.x * blockSize;
    uint by = gid.y * blockSize;

    // Look up coarse vector for the enclosing macroblock.
    uint coarseX = (bx / coarseBlock);
    uint coarseY = (by / coarseBlock);
    coarseX = min(coarseX, coarseMV.get_width() - 1);
    coarseY = min(coarseY, coarseMV.get_height() - 1);
    float4 cmv = coarseMV.read(uint2(coarseX, coarseY));

    // Convert normalised MV back to pixel offsets.
    int baseOX = int(round(cmv.x * float(frameW)));
    int baseOY = int(round(cmv.y * float(frameH)));

    float bestSAD = 1e30f;
    int2  bestOff = int2(baseOX, baseOY);

    int sr = 4;
    for (int dy = -sr; dy <= sr; ++dy) {
        for (int dx = -sr; dx <= sr; ++dx) {
            int ox = baseOX + dx;
            int oy = baseOY + dy;

            float sad = 0.0f;
            for (uint py = 0; py < blockSize; ++py) {
                for (uint px = 0; px < blockSize; ++px) {
                    uint cx = bx + px;
                    uint cy = by + py;
                    if (cx >= frameW || cy >= frameH) continue;

                    int px2 = clamp(int(cx) + ox, 0, int(frameW) - 1);
                    int py2 = clamp(int(cy) + oy, 0, int(frameH) - 1);

                    float lc = luma(currFrame.read(uint2(cx, cy)));
                    float lp = luma(prevFrame.read(uint2(px2, py2)));
                    sad += abs(lc - lp);
                }
            }
            if (sad < bestSAD) {
                bestSAD = sad;
                bestOff = int2(ox, oy);
            }
        }
    }

    float2 mv = float2(float(bestOff.x) / float(frameW),
                        float(bestOff.y) / float(frameH));
    refinedMV.write(float4(mv.x, mv.y, 0.0f, 0.0f), gid);
}

// ---------------------------------------------------------------------------
// Pass 3 — Densify block-level motion vectors to per-pixel via bilinear
// interpolation, then apply a 3×3 median filter to suppress outliers.
// ---------------------------------------------------------------------------
inline float2 medianVec(float2 a, float2 b, float2 c) {
    // Component-wise median of three vectors.
    float mx = a.x + b.x + c.x - min(a.x, min(b.x, c.x)) - max(a.x, max(b.x, c.x));
    float my = a.y + b.y + c.y - min(a.y, min(b.y, c.y)) - max(a.y, max(b.y, c.y));
    return float2(mx, my);
}

kernel void motionVectorDensify(
    texture2d<float, access::read>  blockMV       [[texture(0)]],
    texture2d<float, access::write> pixelMV       [[texture(1)]],
    constant uint                   &blockSize    [[buffer(0)]],
    uint2                           gid           [[thread_position_in_grid]])
{
    uint outW = pixelMV.get_width();
    uint outH = pixelMV.get_height();
    if (gid.x >= outW || gid.y >= outH) return;

    uint mvW = blockMV.get_width();
    uint mvH = blockMV.get_height();

    // Map pixel position to floating-point block coordinate.
    float bxf = (float(gid.x) + 0.5f) / float(blockSize) - 0.5f;
    float byf = (float(gid.y) + 0.5f) / float(blockSize) - 0.5f;

    // Bilinear sample from block MV grid.
    int bx0 = int(floor(bxf));
    int by0 = int(floor(byf));
    int bx1 = bx0 + 1;
    int by1 = by0 + 1;

    float fx = bxf - float(bx0);
    float fy = byf - float(by0);

    bx0 = clamp(bx0, 0, int(mvW) - 1);
    bx1 = clamp(bx1, 0, int(mvW) - 1);
    by0 = clamp(by0, 0, int(mvH) - 1);
    by1 = clamp(by1, 0, int(mvH) - 1);

    float2 v00 = blockMV.read(uint2(bx0, by0)).xy;
    float2 v10 = blockMV.read(uint2(bx1, by0)).xy;
    float2 v01 = blockMV.read(uint2(bx0, by1)).xy;
    float2 v11 = blockMV.read(uint2(bx1, by1)).xy;

    float2 mv = mix(mix(v00, v10, fx), mix(v01, v11, fx), fy);

    // 3×3 cross median filter to suppress outlier vectors.
    float2 left  = (gid.x > 0) ? blockMV.read(uint2(clamp(int(bxf), 0, int(mvW)-1),
                                                       clamp(int(byf + 0.5f), 0, int(mvH)-1))).xy : mv;
    float2 right = blockMV.read(uint2(clamp(int(bxf) + 1, 0, int(mvW)-1),
                                       clamp(int(byf + 0.5f), 0, int(mvH)-1))).xy;
    mv = medianVec(left, mv, right);

    pixelMV.write(float4(mv.x, mv.y, 0.0f, 0.0f), gid);
}

// ---------------------------------------------------------------------------
// Scene-cut detection helper — computes average SAD across the entire frame
// at coarse granularity.  The CPU reads back a single value to decide whether
// to skip interpolation.
// ---------------------------------------------------------------------------
kernel void sceneCutScore(
    texture2d<float, access::read>  prevFrame   [[texture(0)]],
    texture2d<float, access::read>  currFrame   [[texture(1)]],
    device atomic_uint              *totalSAD   [[buffer(0)]],
    device atomic_uint              *pixelCount [[buffer(1)]],
    uint2                           gid         [[thread_position_in_grid]])
{
    uint w = currFrame.get_width();
    uint h = currFrame.get_height();

    // Sample every 4th pixel for speed.
    uint x = gid.x * 4;
    uint y = gid.y * 4;
    if (x >= w || y >= h) return;

    float lc = luma(currFrame.read(uint2(x, y)));
    float lp = luma(prevFrame.read(uint2(x, y)));
    uint diff = uint(abs(lc - lp) * 1000.0f);

    atomic_fetch_add_explicit(totalSAD, diff, memory_order_relaxed);
    atomic_fetch_add_explicit(pixelCount, 1u, memory_order_relaxed);
}
