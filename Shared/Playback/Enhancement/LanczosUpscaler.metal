#include <metal_stdlib>
using namespace metal;

constant float PI = 3.14159265358979323846f;

inline float lanczosWeight(float x, float a) {
    float ax = abs(x);
    if (ax < 1e-5f) return 1.0f;
    if (ax >= a) return 0.0f;
    float pi_x = PI * x;
    return (sin(pi_x) * sin(pi_x / a)) / ((pi_x * pi_x) / a);
}

kernel void lanczosUpscale(
    texture2d<float, access::read>  input   [[texture(0)]],
    texture2d<float, access::write> output  [[texture(1)]],
    uint2                           gid     [[thread_position_in_grid]])
{
    uint outW = output.get_width();
    uint outH = output.get_height();
    if (gid.x >= outW || gid.y >= outH) return;

    uint inW = input.get_width();
    uint inH = input.get_height();

    // Map output coordinate to input space (pixel center alignment)
    float u = ((float(gid.x) + 0.5f) * float(inW) / float(outW)) - 0.5f;
    float v = ((float(gid.y) + 0.5f) * float(inH) / float(outH)) - 0.5f;

    int centerU = int(floor(u));
    int centerV = int(floor(v));

    float4 colorSum = float4(0.0f);
    float totalWeight = 0.0f;

    // 4-tap Lanczos-2 (radius a = 2, kernel size 4x4)
    for (int dy = -1; dy <= 2; ++dy) {
        int sampleY = clamp(centerV + dy, 0, int(inH) - 1);
        float wy = lanczosWeight(v - float(centerV + dy), 2.0f);

        for (int dx = -1; dx <= 2; ++dx) {
            int sampleX = clamp(centerU + dx, 0, int(inW) - 1);
            float wx = lanczosWeight(u - float(centerU + dx), 2.0f);
            float w = wx * wy;

            float4 c = input.read(uint2(sampleX, sampleY));
            colorSum += c * w;
            totalWeight += w;
        }
    }

    float4 result = (totalWeight > 1e-5f)
        ? (colorSum / totalWeight)
        : input.read(uint2(clamp(int(round(u)), 0, int(inW) - 1), clamp(int(round(v)), 0, int(inH) - 1)));

    output.write(clamp(result, 0.0f, 1.0f), gid);
}
