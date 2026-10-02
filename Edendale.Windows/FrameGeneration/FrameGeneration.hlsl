// Edendale frame generation and upscaling for Intel GPUs (ENHANCEMENT.md G),
// as Direct3D 11 compute shaders (cs_5_0).
//
// The same algorithm as FrameGeneration.cu and Core/FrameGeneration.cs's
// FrameInterpolation reference: keep the constants in step with
// FrameGenerationRules. Services/FrameGeneration/ComputeShaderFrameGenerator.cs
// compiles each entry point at run time with d3dcompiler_47.dll, which ships
// with Windows, and binds the registers named below.

#define BLOCK_SIZE 16
#define COARSE_RADIUS 32
#define COARSE_STEP 4
#define COARSE_STRIDE 4
#define COARSE_AXIS 17
#define COARSE_CANDIDATES (COARSE_AXIS * COARSE_AXIS)
#define REFINE_RADIUS 4
#define REFINE_STEP 2
#define REFINE_STRIDE 2
#define REFINE_AXIS 5
#define REFINE_CANDIDATES (REFINE_AXIS * REFINE_AXIS)
#define MOTION_PENALTY 0.0001f
#define TRUSTED_COST 0.04f
#define UNTRUSTED_COST 0.10f
#define SCENE_CUT_COST 0.18f
#define COARSE_THREADS 128
#define REFINE_THREADS 32
#define STATS_THREADS 256
#define PI_F 3.14159265358979f

cbuffer Params : register(b0)
{
    int Width;
    int Height;
    int BlocksX;
    int BlocksY;
    int OutWidth;
    int OutHeight;
    int RectX;
    int RectY;
    int RectWidth;
    int RectHeight;
    int Matrix;
    int Count;
};

Texture2D<float> LumaA : register(t0);          // R8_UNORM: the earlier frame's Y (or the frame being converted)
Texture2D<float> LumaB : register(t1);          // R8_UNORM: the later frame's Y
StructuredBuffer<float4> FieldIn : register(t2);
Texture2D<float4> ColorA : register(t3);        // R8G8B8A8_UNORM
Texture2D<float4> ColorB : register(t4);
StructuredBuffer<float> MeanIn : register(t5);
Texture2D<float2> Chroma : register(t6);        // R8G8_UNORM: NV12's interleaved Cb Cr at half size

RWStructuredBuffer<float4> FieldOut : register(u0);
RWStructuredBuffer<float> MeanOut : register(u1);
RWTexture2D<unorm float4> ColorOut : register(u2);

float LoadLuma(Texture2D<float> plane, int x, int y)
{
    return plane.Load(int3(clamp(x, 0, Width - 1), clamp(y, 0, Height - 1), 0));
}

float4 LoadColor(Texture2D<float4> image, int x, int y)
{
    return float4(image.Load(int3(clamp(x, 0, Width - 1), clamp(y, 0, Height - 1), 0)).rgb, 1.0f);
}

float4 Bilinear(Texture2D<float4> image, float x, float y)
{
    int x0 = (int)floor(x);
    int y0 = (int)floor(y);
    float tx = x - x0;
    float ty = y - y0;
    float4 top = lerp(LoadColor(image, x0, y0), LoadColor(image, x0 + 1, y0), tx);
    float4 bottom = lerp(LoadColor(image, x0, y0 + 1), LoadColor(image, x0 + 1, y0 + 1), tx);
    return lerp(top, bottom, ty);
}

// ---------------------------------------------------------------------------
// NV12 (limited range) to RGBA. Matrix is 709 or 601.
// ---------------------------------------------------------------------------

[numthreads(8, 8, 1)]
void Convert(uint3 id : SV_DispatchThreadID)
{
    int x = (int)id.x;
    int y = (int)id.y;
    if (x >= Width || y >= Height) return;

    float yy = (LumaA.Load(int3(x, y, 0)) * 255.0f - 16.0f) * (255.0f / 219.0f);
    float2 c = Chroma.Load(int3(x / 2, y / 2, 0)) * 255.0f;
    float cb = (c.x - 128.0f) * (255.0f / 224.0f);
    float cr = (c.y - 128.0f) * (255.0f / 224.0f);

    float r, g, b;
    if (Matrix == 709)
    {
        r = yy + 1.5748f * cr;
        g = yy - 0.1873f * cb - 0.4681f * cr;
        b = yy + 1.8556f * cb;
    }
    else
    {
        r = yy + 1.402f * cr;
        g = yy - 0.344136f * cb - 0.714136f * cr;
        b = yy + 1.772f * cb;
    }
    ColorOut[uint2(x, y)] = float4(saturate(float3(r, g, b) / 255.0f), 1.0f);
}

// ---------------------------------------------------------------------------
// Motion search around the midpoint (FrameInterpolation.BlockCost).
// ---------------------------------------------------------------------------

float BlockCost(int blockX, int blockY, int vx, int vy, int stride)
{
    int half = stride / 2;
    float sum = 0.0f;
    int count = 0;
    for (int j = half; j < BLOCK_SIZE; j += stride)
    {
        for (int i = half; i < BLOCK_SIZE; i += stride)
        {
            int px = blockX * BLOCK_SIZE + i;
            int py = blockY * BLOCK_SIZE + j;
            sum += abs(LoadLuma(LumaA, px - vx / 2, py - vy / 2) - LoadLuma(LumaB, px + vx / 2, py + vy / 2));
            count++;
        }
    }
    return sum / count + MOTION_PENALTY * (abs(vx) + abs(vy));
}

groupshared float SharedCost[COARSE_THREADS];
groupshared int SharedIndex[COARSE_THREADS];

// Lowest cost wins; a tie keeps the lower candidate index, like the reference.
void ReduceBest(uint thread, uint threads)
{
    for (uint offset = threads / 2; offset > 0; offset /= 2)
    {
        if (thread < offset)
        {
            float other = SharedCost[thread + offset];
            int otherIndex = SharedIndex[thread + offset];
            if (other < SharedCost[thread] || (other == SharedCost[thread] && otherIndex < SharedIndex[thread]))
            {
                SharedCost[thread] = other;
                SharedIndex[thread] = otherIndex;
            }
        }
        GroupMemoryBarrierWithGroupSync();
    }
}

// One group per motion block; dispatch (BlocksX, BlocksY, 1).
[numthreads(COARSE_THREADS, 1, 1)]
void MotionCoarse(uint3 group : SV_GroupID, uint thread : SV_GroupIndex)
{
    float best = 3.0e38f;
    int bestIndex = 0x7fffffff;
    for (int c = (int)thread; c < COARSE_CANDIDATES; c += COARSE_THREADS)
    {
        int vx = -COARSE_RADIUS + (c % COARSE_AXIS) * COARSE_STEP;
        int vy = -COARSE_RADIUS + (c / COARSE_AXIS) * COARSE_STEP;
        float cost = BlockCost((int)group.x, (int)group.y, vx, vy, COARSE_STRIDE);
        if (cost < best)
        {
            best = cost;
            bestIndex = c;
        }
    }
    SharedCost[thread] = best;
    SharedIndex[thread] = bestIndex;
    GroupMemoryBarrierWithGroupSync();
    ReduceBest(thread, COARSE_THREADS);

    if (thread == 0)
    {
        int c = SharedIndex[0];
        FieldOut[group.y * BlocksX + group.x] = float4(
            -COARSE_RADIUS + (c % COARSE_AXIS) * COARSE_STEP,
            -COARSE_RADIUS + (c / COARSE_AXIS) * COARSE_STEP,
            SharedCost[0], 0.0f);
    }
}

// ±4 pixels around the coarse winner (FieldIn), every second pixel compared.
[numthreads(REFINE_THREADS, 1, 1)]
void MotionRefine(uint3 group : SV_GroupID, uint thread : SV_GroupIndex)
{
    float4 center = FieldIn[group.y * BlocksX + group.x];
    int cx = (int)center.x;
    int cy = (int)center.y;

    float best = 3.0e38f;
    int bestIndex = 0x7fffffff;
    for (int c = (int)thread; c < REFINE_CANDIDATES; c += REFINE_THREADS)
    {
        int vx = cx - REFINE_RADIUS + (c % REFINE_AXIS) * REFINE_STEP;
        int vy = cy - REFINE_RADIUS + (c / REFINE_AXIS) * REFINE_STEP;
        float cost = BlockCost((int)group.x, (int)group.y, vx, vy, REFINE_STRIDE);
        if (cost < best)
        {
            best = cost;
            bestIndex = c;
        }
    }
    SharedCost[thread] = best;
    SharedIndex[thread] = bestIndex;
    GroupMemoryBarrierWithGroupSync();
    ReduceBest(thread, REFINE_THREADS);

    if (thread == 0)
    {
        int c = SharedIndex[0];
        FieldOut[group.y * BlocksX + group.x] = float4(
            cx - REFINE_RADIUS + (c % REFINE_AXIS) * REFINE_STEP,
            cy - REFINE_RADIUS + (c / REFINE_AXIS) * REFINE_STEP,
            SharedCost[0], 0.0f);
    }
}

// ---------------------------------------------------------------------------
// 3×3 median of each component, clamped at the edges.
// ---------------------------------------------------------------------------

float Median9(float v[9])
{
    for (int i = 1; i < 9; i++)
    {
        for (int j = i; j > 0 && v[j - 1] > v[j]; j--)
        {
            float t = v[j];
            v[j] = v[j - 1];
            v[j - 1] = t;
        }
    }
    return v[4];
}

[numthreads(8, 8, 1)]
void SmoothField(uint3 id : SV_DispatchThreadID)
{
    int bx = (int)id.x;
    int by = (int)id.y;
    if (bx >= BlocksX || by >= BlocksY) return;

    float xs[9];
    float ys[9];
    float cs[9];
    int n = 0;
    for (int j = -1; j <= 1; j++)
    {
        for (int i = -1; i <= 1; i++)
        {
            float4 v = FieldIn[clamp(by + j, 0, BlocksY - 1) * BlocksX + clamp(bx + i, 0, BlocksX - 1)];
            xs[n] = v.x;
            ys[n] = v.y;
            cs[n] = v.z;
            n++;
        }
    }
    FieldOut[by * BlocksX + bx] = float4(Median9(xs), Median9(ys), Median9(cs), 0.0f);
}

// The mean match cost, for the scene-cut test. Dispatch (1, 1, 1).
groupshared float SharedSum[STATS_THREADS];

[numthreads(STATS_THREADS, 1, 1)]
void FieldStats(uint thread : SV_GroupIndex)
{
    float sum = 0.0f;
    for (int i = (int)thread; i < Count; i += STATS_THREADS) sum += FieldIn[i].z;
    SharedSum[thread] = sum;
    GroupMemoryBarrierWithGroupSync();
    for (uint offset = STATS_THREADS / 2; offset > 0; offset /= 2)
    {
        if (thread < offset) SharedSum[thread] += SharedSum[thread + offset];
        GroupMemoryBarrierWithGroupSync();
    }
    if (thread == 0) MeanOut[0] = Count > 0 ? SharedSum[0] / Count : 0.0f;
}

// ---------------------------------------------------------------------------
// The frame halfway between ColorA and ColorB (FrameInterpolation.Synthesize).
// ---------------------------------------------------------------------------

[numthreads(8, 8, 1)]
void Synthesize(uint3 id : SV_DispatchThreadID)
{
    int x = (int)id.x;
    int y = (int)id.y;
    if (x >= Width || y >= Height) return;

    if (MeanIn[0] > SCENE_CUT_COST)
    {
        ColorOut[uint2(x, y)] = LoadColor(ColorA, x, y);
        return;
    }

    float fx = (x + 0.5f) / BLOCK_SIZE - 0.5f;
    float fy = (y + 0.5f) / BLOCK_SIZE - 0.5f;
    int x0 = clamp((int)floor(fx), 0, BlocksX - 1);
    int y0 = clamp((int)floor(fy), 0, BlocksY - 1);
    int x1 = min(x0 + 1, BlocksX - 1);
    int y1 = min(y0 + 1, BlocksY - 1);
    float tx = saturate(fx - x0);
    float ty = saturate(fy - y0);

    float3 top = lerp(FieldIn[y0 * BlocksX + x0].xyz, FieldIn[y0 * BlocksX + x1].xyz, tx);
    float3 bottom = lerp(FieldIn[y1 * BlocksX + x0].xyz, FieldIn[y1 * BlocksX + x1].xyz, tx);
    float3 v = lerp(top, bottom, ty);

    float4 compensated = lerp(Bilinear(ColorA, x - v.x * 0.5f, y - v.y * 0.5f), Bilinear(ColorB, x + v.x * 0.5f, y + v.y * 0.5f), 0.5f);
    float4 plain = lerp(LoadColor(ColorA, x, y), LoadColor(ColorB, x, y), 0.5f);
    float weight = saturate((v.z - TRUSTED_COST) / (UNTRUSTED_COST - TRUSTED_COST));
    ColorOut[uint2(x, y)] = float4(saturate(lerp(compensated, plain, weight).rgb), 1.0f);
}

// ---------------------------------------------------------------------------
// Scaling ColorA into the destination rectangle (FrameInterpolation.Scale).
// ---------------------------------------------------------------------------

float Lanczos3(float x)
{
    x = abs(x);
    if (x < 1e-8f) return 1.0f;
    if (x >= 3.0f) return 0.0f;
    float pix = PI_F * x;
    return 3.0f * sin(pix) * sin(pix / 3.0f) / (pix * pix);
}

float4 Lanczos(float sx, float sy)
{
    int x0 = (int)floor(sx);
    int y0 = (int)floor(sy);
    float wx[6];
    float wy[6];
    float sumX = 0.0f;
    float sumY = 0.0f;
    for (int k = 0; k < 6; k++)
    {
        wx[k] = Lanczos3(sx - (x0 - 2 + k));
        wy[k] = Lanczos3(sy - (y0 - 2 + k));
        sumX += wx[k];
        sumY += wy[k];
    }

    float3 value = 0.0f;
    for (int j = 0; j < 6; j++)
    {
        float3 row = 0.0f;
        for (int i = 0; i < 6; i++)
        {
            row += wx[i] * LoadColor(ColorA, x0 - 2 + i, y0 - 2 + j).rgb;
        }
        value += wy[j] * row;
    }
    value /= sumX * sumY;

    float3 p00 = LoadColor(ColorA, x0, y0).rgb;
    float3 p10 = LoadColor(ColorA, x0 + 1, y0).rgb;
    float3 p01 = LoadColor(ColorA, x0, y0 + 1).rgb;
    float3 p11 = LoadColor(ColorA, x0 + 1, y0 + 1).rgb;
    return float4(clamp(value, min(min(p00, p10), min(p01, p11)), max(max(p00, p10), max(p01, p11))), 1.0f);
}

[numthreads(8, 8, 1)]
void Scale(uint3 id : SV_DispatchThreadID)
{
    int ox = (int)id.x;
    int oy = (int)id.y;
    if (ox >= OutWidth || oy >= OutHeight) return;

    if (RectWidth <= 0 || RectHeight <= 0
        || ox < RectX || oy < RectY || ox >= RectX + RectWidth || oy >= RectY + RectHeight)
    {
        ColorOut[uint2(ox, oy)] = float4(0.0f, 0.0f, 0.0f, 1.0f);
        return;
    }

    float scaleX = (float)Width / RectWidth;
    float scaleY = (float)Height / RectHeight;
    float sx = (ox - RectX + 0.5f) * scaleX - 0.5f;
    float sy = (oy - RectY + 0.5f) * scaleY - 0.5f;

    float4 value;
    if (scaleX <= 1.0f && scaleY <= 1.0f)
    {
        value = Lanczos(sx, sy);
    }
    else
    {
        float dx = 0.25f * scaleX;
        float dy = 0.25f * scaleY;
        value = 0.25f * (Bilinear(ColorA, sx - dx, sy - dy) + Bilinear(ColorA, sx + dx, sy - dy)
            + Bilinear(ColorA, sx - dx, sy + dy) + Bilinear(ColorA, sx + dx, sy + dy));
    }
    ColorOut[uint2(ox, oy)] = float4(saturate(value.rgb), 1.0f);
}
