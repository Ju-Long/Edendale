// Edendale frame generation and upscaling for NVIDIA GPUs (ENHANCEMENT.md G).
//
// These kernels implement Core/FrameGeneration.cs's FrameInterpolation
// reference exactly: the same block size, search pattern, tie-breaking,
// median, fallback weights, scene-cut threshold, and Lanczos-3 scaler. Keep
// the constants below in step with FrameGenerationRules; the unit tests pin
// the C# side.
//
// Compiled to PTX by tools/build-frame-generation-ptx.py (NVRTC) and loaded
// by Services/FrameGeneration/CudaFrameGenerator.cs through the CUDA driver
// API, so no CUDA runtime ships with the app. Rebuild the PTX after any
// change here.

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

__device__ __forceinline__ int clampi(int value, int low, int high)
{
    return value < low ? low : (value > high ? high : value);
}

__device__ __forceinline__ float clampf(float value, float low, float high)
{
    return fminf(fmaxf(value, low), high);
}

__device__ __forceinline__ float luma(const unsigned char* plane, int pitch, int width, int height, int x, int y)
{
    return plane[clampi(y, 0, height - 1) * pitch + clampi(x, 0, width - 1)] * (1.0f / 255.0f);
}

__device__ __forceinline__ float4 load(const uchar4* image, int width, int height, int x, int y)
{
    uchar4 p = image[clampi(y, 0, height - 1) * width + clampi(x, 0, width - 1)];
    return make_float4(p.x * (1.0f / 255.0f), p.y * (1.0f / 255.0f), p.z * (1.0f / 255.0f), 1.0f);
}

__device__ __forceinline__ float4 mix(float4 p, float4 q, float t)
{
    return make_float4(p.x + (q.x - p.x) * t, p.y + (q.y - p.y) * t, p.z + (q.z - p.z) * t, 1.0f);
}

__device__ float4 bilinear(const uchar4* image, int width, int height, float x, float y)
{
    int x0 = (int)floorf(x);
    int y0 = (int)floorf(y);
    float tx = x - x0;
    float ty = y - y0;
    float4 top = mix(load(image, width, height, x0, y0), load(image, width, height, x0 + 1, y0), tx);
    float4 bottom = mix(load(image, width, height, x0, y0 + 1), load(image, width, height, x0 + 1, y0 + 1), tx);
    return mix(top, bottom, ty);
}

__device__ __forceinline__ uchar4 store(float4 c)
{
    return make_uchar4(
        (unsigned char)(clampf(c.x, 0.0f, 1.0f) * 255.0f + 0.5f),
        (unsigned char)(clampf(c.y, 0.0f, 1.0f) * 255.0f + 0.5f),
        (unsigned char)(clampf(c.z, 0.0f, 1.0f) * 255.0f + 0.5f),
        255);
}

// ---------------------------------------------------------------------------
// NV12 (limited range) to RGBA. matrix is 709 or 601.
// ---------------------------------------------------------------------------

extern "C" __global__ void nv12_to_rgba(
    const unsigned char* y_plane, const unsigned char* uv_plane, int pitch,
    int width, int height, int matrix, uchar4* out)
{
    int x = blockIdx.x * blockDim.x + threadIdx.x;
    int y = blockIdx.y * blockDim.y + threadIdx.y;
    if (x >= width || y >= height) return;

    float yy = (y_plane[y * pitch + x] - 16.0f) * (255.0f / 219.0f);
    const unsigned char* uv = uv_plane + (y / 2) * pitch + (x / 2) * 2;
    float cb = (uv[0] - 128.0f) * (255.0f / 224.0f);
    float cr = (uv[1] - 128.0f) * (255.0f / 224.0f);

    float r, g, b;
    if (matrix == 709)
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
    out[y * width + x] = store(make_float4(r / 255.0f, g / 255.0f, b / 255.0f, 1.0f));
}

// ---------------------------------------------------------------------------
// Motion search: the earlier frame half a vector back against the later one
// half a vector on, around the midpoint (FrameInterpolation.BlockCost).
// ---------------------------------------------------------------------------

__device__ float block_cost(
    const unsigned char* a, const unsigned char* b, int pitch, int width, int height,
    int block_x, int block_y, int vx, int vy, int stride)
{
    int half = stride / 2;
    float sum = 0.0f;
    int count = 0;
    for (int j = half; j < BLOCK_SIZE; j += stride)
    {
        for (int i = half; i < BLOCK_SIZE; i += stride)
        {
            int px = block_x * BLOCK_SIZE + i;
            int py = block_y * BLOCK_SIZE + j;
            sum += fabsf(luma(a, pitch, width, height, px - vx / 2, py - vy / 2)
                - luma(b, pitch, width, height, px + vx / 2, py + vy / 2));
            count++;
        }
    }
    return sum / count + MOTION_PENALTY * (abs(vx) + abs(vy));
}

// Lowest cost wins; a tie keeps the lower candidate index, like the reference.
__device__ void reduce_best(float* costs, int* indices, int threads)
{
    for (int offset = threads / 2; offset > 0; offset /= 2)
    {
        if ((int)threadIdx.x < offset)
        {
            float other = costs[threadIdx.x + offset];
            int other_index = indices[threadIdx.x + offset];
            if (other < costs[threadIdx.x] || (other == costs[threadIdx.x] && other_index < indices[threadIdx.x]))
            {
                costs[threadIdx.x] = other;
                indices[threadIdx.x] = other_index;
            }
        }
        __syncthreads();
    }
}

// One CUDA block per motion block; COARSE_THREADS threads share the 289 candidates.
extern "C" __global__ void motion_coarse(
    const unsigned char* a, const unsigned char* b, int pitch, int width, int height, int blocks_x, float4* field)
{
    __shared__ float costs[COARSE_THREADS];
    __shared__ int indices[COARSE_THREADS];

    float best = 3.0e38f;
    int best_index = 0x7fffffff;
    for (int c = threadIdx.x; c < COARSE_CANDIDATES; c += COARSE_THREADS)
    {
        int vx = -COARSE_RADIUS + (c % COARSE_AXIS) * COARSE_STEP;
        int vy = -COARSE_RADIUS + (c / COARSE_AXIS) * COARSE_STEP;
        float cost = block_cost(a, b, pitch, width, height, blockIdx.x, blockIdx.y, vx, vy, COARSE_STRIDE);
        if (cost < best)
        {
            best = cost;
            best_index = c;
        }
    }
    costs[threadIdx.x] = best;
    indices[threadIdx.x] = best_index;
    __syncthreads();
    reduce_best(costs, indices, COARSE_THREADS);

    if (threadIdx.x == 0)
    {
        int c = indices[0];
        field[blockIdx.y * blocks_x + blockIdx.x] = make_float4(
            (float)(-COARSE_RADIUS + (c % COARSE_AXIS) * COARSE_STEP),
            (float)(-COARSE_RADIUS + (c / COARSE_AXIS) * COARSE_STEP),
            costs[0], 0.0f);
    }
}

// ±4 pixels around the coarse winner, every second pixel compared.
extern "C" __global__ void motion_refine(
    const unsigned char* a, const unsigned char* b, int pitch, int width, int height, int blocks_x,
    const float4* coarse, float4* field)
{
    __shared__ float costs[REFINE_THREADS];
    __shared__ int indices[REFINE_THREADS];

    float4 center = coarse[blockIdx.y * blocks_x + blockIdx.x];
    int cx = (int)center.x;
    int cy = (int)center.y;

    float best = 3.0e38f;
    int best_index = 0x7fffffff;
    for (int c = threadIdx.x; c < REFINE_CANDIDATES; c += REFINE_THREADS)
    {
        int vx = cx - REFINE_RADIUS + (c % REFINE_AXIS) * REFINE_STEP;
        int vy = cy - REFINE_RADIUS + (c / REFINE_AXIS) * REFINE_STEP;
        float cost = block_cost(a, b, pitch, width, height, blockIdx.x, blockIdx.y, vx, vy, REFINE_STRIDE);
        if (cost < best)
        {
            best = cost;
            best_index = c;
        }
    }
    costs[threadIdx.x] = best;
    indices[threadIdx.x] = best_index;
    __syncthreads();
    reduce_best(costs, indices, REFINE_THREADS);

    if (threadIdx.x == 0)
    {
        int c = indices[0];
        field[blockIdx.y * blocks_x + blockIdx.x] = make_float4(
            (float)(cx - REFINE_RADIUS + (c % REFINE_AXIS) * REFINE_STEP),
            (float)(cy - REFINE_RADIUS + (c / REFINE_AXIS) * REFINE_STEP),
            costs[0], 0.0f);
    }
}

// ---------------------------------------------------------------------------
// 3×3 median of each component, clamped at the edges.
// ---------------------------------------------------------------------------

__device__ float median9(float* v)
{
    for (int i = 1; i < 9; i++)
    {
        float key = v[i];
        int j = i - 1;
        while (j >= 0 && v[j] > key)
        {
            v[j + 1] = v[j];
            j--;
        }
        v[j + 1] = key;
    }
    return v[4];
}

extern "C" __global__ void smooth_field(const float4* in, float4* out, int blocks_x, int blocks_y)
{
    int bx = blockIdx.x * blockDim.x + threadIdx.x;
    int by = blockIdx.y * blockDim.y + threadIdx.y;
    if (bx >= blocks_x || by >= blocks_y) return;

    float xs[9], ys[9], cs[9];
    int n = 0;
    for (int j = -1; j <= 1; j++)
    {
        for (int i = -1; i <= 1; i++)
        {
            float4 v = in[clampi(by + j, 0, blocks_y - 1) * blocks_x + clampi(bx + i, 0, blocks_x - 1)];
            xs[n] = v.x;
            ys[n] = v.y;
            cs[n] = v.z;
            n++;
        }
    }
    out[by * blocks_x + bx] = make_float4(median9(xs), median9(ys), median9(cs), 0.0f);
}

// The mean match cost, for the scene-cut test. One block of STATS_THREADS.
extern "C" __global__ void field_stats(const float4* field, int count, float* mean_cost)
{
    __shared__ float sums[STATS_THREADS];
    float sum = 0.0f;
    for (int i = threadIdx.x; i < count; i += STATS_THREADS) sum += field[i].z;
    sums[threadIdx.x] = sum;
    __syncthreads();
    for (int offset = STATS_THREADS / 2; offset > 0; offset /= 2)
    {
        if ((int)threadIdx.x < offset) sums[threadIdx.x] += sums[threadIdx.x + offset];
        __syncthreads();
    }
    if (threadIdx.x == 0) mean_cost[0] = count > 0 ? sums[0] / count : 0.0f;
}

// ---------------------------------------------------------------------------
// The frame halfway between a and b (FrameInterpolation.Synthesize).
// ---------------------------------------------------------------------------

extern "C" __global__ void synthesize(
    const uchar4* a, const uchar4* b, int width, int height,
    const float4* field, int blocks_x, int blocks_y, const float* mean_cost, uchar4* out)
{
    int x = blockIdx.x * blockDim.x + threadIdx.x;
    int y = blockIdx.y * blockDim.y + threadIdx.y;
    if (x >= width || y >= height) return;

    if (mean_cost[0] > SCENE_CUT_COST)
    {
        out[y * width + x] = a[y * width + x];
        return;
    }

    float fx = (x + 0.5f) / BLOCK_SIZE - 0.5f;
    float fy = (y + 0.5f) / BLOCK_SIZE - 0.5f;
    int x0 = clampi((int)floorf(fx), 0, blocks_x - 1);
    int y0 = clampi((int)floorf(fy), 0, blocks_y - 1);
    int x1 = min(x0 + 1, blocks_x - 1);
    int y1 = min(y0 + 1, blocks_y - 1);
    float tx = clampf(fx - x0, 0.0f, 1.0f);
    float ty = clampf(fy - y0, 0.0f, 1.0f);

    float4 v00 = field[y0 * blocks_x + x0];
    float4 v10 = field[y0 * blocks_x + x1];
    float4 v01 = field[y1 * blocks_x + x0];
    float4 v11 = field[y1 * blocks_x + x1];
    float4 top = make_float4(v00.x + (v10.x - v00.x) * tx, v00.y + (v10.y - v00.y) * tx, v00.z + (v10.z - v00.z) * tx, 0.0f);
    float4 bottom = make_float4(v01.x + (v11.x - v01.x) * tx, v01.y + (v11.y - v01.y) * tx, v01.z + (v11.z - v01.z) * tx, 0.0f);
    float vx = top.x + (bottom.x - top.x) * ty;
    float vy = top.y + (bottom.y - top.y) * ty;
    float cost = top.z + (bottom.z - top.z) * ty;

    float4 from_a = bilinear(a, width, height, x - vx * 0.5f, y - vy * 0.5f);
    float4 from_b = bilinear(b, width, height, x + vx * 0.5f, y + vy * 0.5f);
    float4 compensated = mix(from_a, from_b, 0.5f);
    float4 plain = mix(load(a, width, height, x, y), load(b, width, height, x, y), 0.5f);
    float weight = clampf((cost - TRUSTED_COST) / (UNTRUSTED_COST - TRUSTED_COST), 0.0f, 1.0f);
    out[y * width + x] = store(mix(compensated, plain, weight));
}

// ---------------------------------------------------------------------------
// Scaling into the destination rectangle (FrameInterpolation.Scale):
// Lanczos-3 with an anti-ringing clamp when enlarging, four bilinear samples
// when shrinking, black outside the rectangle.
// ---------------------------------------------------------------------------

__device__ float lanczos3(float x)
{
    x = fabsf(x);
    if (x < 1e-8f) return 1.0f;
    if (x >= 3.0f) return 0.0f;
    float pix = PI_F * x;
    return 3.0f * sinf(pix) * sinf(pix / 3.0f) / (pix * pix);
}

__device__ float4 lanczos(const uchar4* src, int width, int height, float sx, float sy)
{
    int x0 = (int)floorf(sx);
    int y0 = (int)floorf(sy);
    float wx[6], wy[6];
    float sum_x = 0.0f, sum_y = 0.0f;
    for (int k = 0; k < 6; k++)
    {
        wx[k] = lanczos3(sx - (x0 - 2 + k));
        wy[k] = lanczos3(sy - (y0 - 2 + k));
        sum_x += wx[k];
        sum_y += wy[k];
    }

    float4 value = make_float4(0.0f, 0.0f, 0.0f, 1.0f);
    for (int j = 0; j < 6; j++)
    {
        float4 row = make_float4(0.0f, 0.0f, 0.0f, 0.0f);
        for (int i = 0; i < 6; i++)
        {
            float4 p = load(src, width, height, x0 - 2 + i, y0 - 2 + j);
            row.x += wx[i] * p.x;
            row.y += wx[i] * p.y;
            row.z += wx[i] * p.z;
        }
        value.x += wy[j] * row.x;
        value.y += wy[j] * row.y;
        value.z += wy[j] * row.z;
    }
    float norm = 1.0f / (sum_x * sum_y);
    value.x *= norm;
    value.y *= norm;
    value.z *= norm;

    float4 p00 = load(src, width, height, x0, y0);
    float4 p10 = load(src, width, height, x0 + 1, y0);
    float4 p01 = load(src, width, height, x0, y0 + 1);
    float4 p11 = load(src, width, height, x0 + 1, y0 + 1);
    value.x = clampf(value.x, fminf(fminf(p00.x, p10.x), fminf(p01.x, p11.x)), fmaxf(fmaxf(p00.x, p10.x), fmaxf(p01.x, p11.x)));
    value.y = clampf(value.y, fminf(fminf(p00.y, p10.y), fminf(p01.y, p11.y)), fmaxf(fmaxf(p00.y, p10.y), fmaxf(p01.y, p11.y)));
    value.z = clampf(value.z, fminf(fminf(p00.z, p10.z), fminf(p01.z, p11.z)), fmaxf(fmaxf(p00.z, p10.z), fmaxf(p01.z, p11.z)));
    return value;
}

extern "C" __global__ void scale(
    const uchar4* src, int width, int height, uchar4* dst, int out_width, int out_height,
    int rect_x, int rect_y, int rect_width, int rect_height)
{
    int ox = blockIdx.x * blockDim.x + threadIdx.x;
    int oy = blockIdx.y * blockDim.y + threadIdx.y;
    if (ox >= out_width || oy >= out_height) return;

    if (rect_width <= 0 || rect_height <= 0
        || ox < rect_x || oy < rect_y || ox >= rect_x + rect_width || oy >= rect_y + rect_height)
    {
        dst[oy * out_width + ox] = make_uchar4(0, 0, 0, 255);
        return;
    }

    float scale_x = (float)width / rect_width;
    float scale_y = (float)height / rect_height;
    float sx = (ox - rect_x + 0.5f) * scale_x - 0.5f;
    float sy = (oy - rect_y + 0.5f) * scale_y - 0.5f;

    float4 value;
    if (scale_x <= 1.0f && scale_y <= 1.0f)
    {
        value = lanczos(src, width, height, sx, sy);
    }
    else
    {
        float dx = 0.25f * scale_x;
        float dy = 0.25f * scale_y;
        float4 s0 = bilinear(src, width, height, sx - dx, sy - dy);
        float4 s1 = bilinear(src, width, height, sx + dx, sy - dy);
        float4 s2 = bilinear(src, width, height, sx - dx, sy + dy);
        float4 s3 = bilinear(src, width, height, sx + dx, sy + dy);
        value = make_float4(
            0.25f * (s0.x + s1.x + s2.x + s3.x),
            0.25f * (s0.y + s1.y + s2.y + s3.y),
            0.25f * (s0.z + s1.z + s2.z + s3.z),
            1.0f);
    }
    dst[oy * out_width + ox] = store(value);
}
