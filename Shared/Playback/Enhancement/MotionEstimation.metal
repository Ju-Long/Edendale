#include <metal_stdlib>
using namespace metal;

// Luma from BGRA pixel (Rec.709 coefficients).
inline float luma(float4 c) {
    return dot(c.rgb, float3(0.2126f, 0.7152f, 0.0722f));
}

// Motion search prefers the smallest vector that explains a block: a
// candidate at the edge of its search window must match this much better
// (mean luma difference) than the starting vector.  Without it, flat or noisy
// areas, where every offset matches about equally well, take whichever
// candidate the loop tried first.
constant float kMaxMotionCost = 0.016f;

// ---------------------------------------------------------------------------
// Pass 1 — Coarse block motion estimation (16×16 macroblocks).
//
// Each thread handles one macroblock.  For the block in `currFrame` at grid
// position `gid`, search a window in `prevFrame` centred on the same position
// for the offset with the lowest cost: mean absolute luma difference plus a
// charge per pixel of offset.  The winning motion vector is written to
// `motionOut` (RG16Float, one texel per macroblock).
//
// Blocks whose best match still differs by more than `unmatchedError` are
// counted in `unmatchedBlocks`; when most blocks are unmatched the frame pair
// is a scene cut (see holdPreviousOnSceneCut).
// ---------------------------------------------------------------------------
kernel void motionEstimationCoarse(
    texture2d<float, access::read>  prevFrame        [[texture(0)]],
    texture2d<float, access::read>  currFrame        [[texture(1)]],
    texture2d<float, access::write> motionOut        [[texture(2)]],
    constant uint                   &blockSize       [[buffer(0)]],
    constant uint                   &searchRadius    [[buffer(1)]],
    device atomic_uint              *unmatchedBlocks [[buffer(2)]],
    constant float                  &unmatchedError  [[buffer(3)]],
    uint2                           gid              [[thread_position_in_grid]])
{
    uint mvW = motionOut.get_width();
    uint mvH = motionOut.get_height();
    if (gid.x >= mvW || gid.y >= mvH) return;

    uint frameW = currFrame.get_width();
    uint frameH = currFrame.get_height();

    uint bx = gid.x * blockSize;
    uint by = gid.y * blockSize;

    int sr = int(searchRadius);
    float costPerPixel = kMaxMotionCost / float(2 * sr);

    // Mean absolute luma difference against `prevFrame` at offset `off`,
    // sampling every other pixel of the block.
    auto blockError = [&](int2 off) -> float {
        float sad = 0.0f;
        float n = 0.0f;
        for (uint py = 0; py < blockSize; py += 2) {
            for (uint px = 0; px < blockSize; px += 2) {
                uint cx = bx + px;
                uint cy = by + py;
                if (cx >= frameW || cy >= frameH) continue;

                int px2 = clamp(int(cx) + off.x, 0, int(frameW) - 1);
                int py2 = clamp(int(cy) + off.y, 0, int(frameH) - 1);

                float lc = luma(currFrame.read(uint2(cx, cy)));
                float lp = luma(prevFrame.read(uint2(px2, py2)));
                sad += abs(lc - lp);
                n += 1.0f;
            }
        }
        return n > 0.0f ? sad / n : 0.0f;
    };

    // Start from zero motion; other candidates must beat it including their cost.
    int2  bestOff = int2(0, 0);
    float bestError = blockError(bestOff);
    float bestCost = bestError;

    for (int dy = -sr; dy <= sr; dy += 2) {
        for (int dx = -sr; dx <= sr; dx += 2) {
            if (dx == 0 && dy == 0) continue;
            float error = blockError(int2(dx, dy));
            float cost = error + costPerPixel * float(abs(dx) + abs(dy));
            if (cost < bestCost) {
                bestCost = cost;
                bestError = error;
                bestOff = int2(dx, dy);
            }
        }
    }

    // Try the odd offsets the step-2 search skipped around the best one.
    int2 center = bestOff;
    for (int dy = -1; dy <= 1; ++dy) {
        for (int dx = -1; dx <= 1; ++dx) {
            if (dx == 0 && dy == 0) continue;
            int ox = center.x + dx;
            int oy = center.y + dy;
            if (abs(ox) > sr || abs(oy) > sr) continue;

            float error = blockError(int2(ox, oy));
            float cost = error + costPerPixel * float(abs(ox) + abs(oy));
            if (cost < bestCost) {
                bestCost = cost;
                bestError = error;
                bestOff = int2(ox, oy);
            }
        }
    }

    if (bestError > unmatchedError) {
        atomic_fetch_add_explicit(unmatchedBlocks, 1u, memory_order_relaxed);
    }

    // Store motion vector (pixel displacement from curr → prev), normalised to
    // [-1, 1] relative to frame dimensions for portability.  The best match's
    // error rides along in z for diagnostics; the RG16 texture drops it.
    float2 mv = float2(float(bestOff.x) / float(frameW),
                        float(bestOff.y) / float(frameH));
    motionOut.write(float4(mv.x, mv.y, bestError, 0.0f), gid);
}

// ---------------------------------------------------------------------------
// Pass 2 — Refine motion vectors at 4×4 sub-block granularity.
//
// Each thread handles one 4×4 sub-block.  It reads the coarse vector for the
// enclosing macroblock and searches a ±4 pixel window around it, moving off
// the coarse vector only for a clearly better match.
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
    int2 base = int2(int(round(cmv.x * float(frameW))),
                     int(round(cmv.y * float(frameH))));

    auto blockError = [&](int2 off) -> float {
        float sad = 0.0f;
        float n = 0.0f;
        for (uint py = 0; py < blockSize; ++py) {
            for (uint px = 0; px < blockSize; ++px) {
                uint cx = bx + px;
                uint cy = by + py;
                if (cx >= frameW || cy >= frameH) continue;

                int px2 = clamp(int(cx) + off.x, 0, int(frameW) - 1);
                int py2 = clamp(int(cy) + off.y, 0, int(frameH) - 1);

                float lc = luma(currFrame.read(uint2(cx, cy)));
                float lp = luma(prevFrame.read(uint2(px2, py2)));
                sad += abs(lc - lp);
                n += 1.0f;
            }
        }
        return n > 0.0f ? sad / n : 0.0f;
    };

    int sr = 4;
    float costPerPixel = kMaxMotionCost / float(2 * sr);
    int2  bestOff = base;
    float bestCost = blockError(base);

    for (int dy = -sr; dy <= sr; ++dy) {
        for (int dx = -sr; dx <= sr; ++dx) {
            if (dx == 0 && dy == 0) continue;
            int2 off = base + int2(dx, dy);
            float cost = blockError(off) + costPerPixel * float(abs(dx) + abs(dy));
            if (cost < bestCost) {
                bestCost = cost;
                bestOff = off;
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
// Pass 3b — Bilinear 2:1 downscale (for half-res ME on 4K sources).
// Each output pixel averages a 2×2 block from the source.
// ---------------------------------------------------------------------------
kernel void bilinearDownscale(
    texture2d<float, access::read>  src   [[texture(0)]],
    texture2d<float, access::write> dst   [[texture(1)]],
    uint2                           gid   [[thread_position_in_grid]])
{
    uint outW = dst.get_width();
    uint outH = dst.get_height();
    if (gid.x >= outW || gid.y >= outH) return;

    uint sx = gid.x * 2;
    uint sy = gid.y * 2;
    uint srcW = src.get_width();
    uint srcH = src.get_height();

    float4 a = src.read(uint2(sx, sy));
    float4 b = (sx + 1 < srcW) ? src.read(uint2(sx + 1, sy)) : a;
    float4 c = (sy + 1 < srcH) ? src.read(uint2(sx, sy + 1)) : a;
    float4 d = (sx + 1 < srcW && sy + 1 < srcH) ? src.read(uint2(sx + 1, sy + 1)) : a;

    dst.write((a + b + c + d) * 0.25f, gid);
}

// ---------------------------------------------------------------------------
// Pass 3c — Upscale motion vector texture with magnitude scaling.
// Bilinear upscale of half-res per-pixel MVs to full resolution.
// MV values are in normalised [-1,1] space so no magnitude adjustment needed.
// ---------------------------------------------------------------------------
kernel void motionVectorUpscale(
    texture2d<float, access::read>  halfMV  [[texture(0)]],
    texture2d<float, access::write> fullMV  [[texture(1)]],
    uint2                           gid     [[thread_position_in_grid]])
{
    uint outW = fullMV.get_width();
    uint outH = fullMV.get_height();
    if (gid.x >= outW || gid.y >= outH) return;

    uint halfW = halfMV.get_width();
    uint halfH = halfMV.get_height();

    float hx = (float(gid.x) + 0.5f) * float(halfW) / float(outW) - 0.5f;
    float hy = (float(gid.y) + 0.5f) * float(halfH) / float(outH) - 0.5f;

    int x0 = int(floor(hx));
    int y0 = int(floor(hy));
    int x1 = x0 + 1;
    int y1 = y0 + 1;
    float fx = hx - float(x0);
    float fy = hy - float(y0);

    x0 = clamp(x0, 0, int(halfW) - 1);
    x1 = clamp(x1, 0, int(halfW) - 1);
    y0 = clamp(y0, 0, int(halfH) - 1);
    y1 = clamp(y1, 0, int(halfH) - 1);

    float2 v00 = halfMV.read(uint2(x0, y0)).xy;
    float2 v10 = halfMV.read(uint2(x1, y0)).xy;
    float2 v01 = halfMV.read(uint2(x0, y1)).xy;
    float2 v11 = halfMV.read(uint2(x1, y1)).xy;

    float2 mv = mix(mix(v00, v10, fx), mix(v01, v11, fx), fy);
    fullMV.write(float4(mv.x, mv.y, 0.0f, 0.0f), gid);
}
