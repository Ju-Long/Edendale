#include <metal_stdlib>
using namespace metal;

// AMD Contrast Adaptive Sharpening (CAS) adapted for Metal compute.
// Sharpness parameter: 0.0 (no sharpening) to 1.0 (maximum). Default 0.5.
kernel void contrastAdaptiveSharpening(
    texture2d<float, access::read>  input     [[texture(0)]],
    texture2d<float, access::write> output    [[texture(1)]],
    constant float                  &sharpness [[buffer(0)]],
    uint2                           gid       [[thread_position_in_grid]])
{
    if (gid.x >= output.get_width() || gid.y >= output.get_height()) {
        return;
    }

    uint width = input.get_width();
    uint height = input.get_height();

    // Fast-path passthrough when sharpness is zero
    if (sharpness <= 0.0f) {
        float4 center = input.read(gid);
        output.write(center, gid);
        return;
    }

    int x = int(gid.x);
    int y = int(gid.y);

    uint xm1 = max(x - 1, 0);
    uint xp1 = min(x + 1, int(width) - 1);
    uint ym1 = max(y - 1, 0);
    uint yp1 = min(y + 1, int(height) - 1);

    // 3x3 neighborhood:
    //   a b c
    //   d e f
    //   g h i
    float4 a = input.read(uint2(xm1, ym1));
    float4 b = input.read(uint2(x,   ym1));
    float4 c = input.read(uint2(xp1, ym1));
    float4 d = input.read(uint2(xm1, y));
    float4 e = input.read(uint2(x,   y));
    float4 f = input.read(uint2(xp1, y));
    float4 g = input.read(uint2(xm1, yp1));
    float4 h = input.read(uint2(x,   yp1));
    float4 i = input.read(uint2(xp1, yp1));

    // Min and max of cross neighborhood (b, d, e, f, h)
    float3 minRGB = min(min(min(d.rgb, e.rgb), f.rgb), min(b.rgb, h.rgb));
    float3 maxRGB = max(max(max(d.rgb, e.rgb), f.rgb), max(b.rgb, h.rgb));

    // Min and max including corner neighborhood (a, c, g, i)
    float3 minRGB2 = min(min(min(a.rgb, c.rgb), g.rgb), i.rgb);
    minRGB2 = min(minRGB, minRGB2);
    float3 maxRGB2 = max(max(max(a.rgb, c.rgb), g.rgb), i.rgb);
    maxRGB2 = max(maxRGB, maxRGB2);

    // Smooth min and max
    minRGB = minRGB + minRGB2;
    maxRGB = maxRGB + maxRGB2;

    // Sharpening amplification factor (prevents ringing on high-contrast edges)
    float3 ampRGB = clamp(min(minRGB, 2.0f - maxRGB) / max(maxRGB, float3(1e-5f)), 0.0f, 1.0f);
    float peak = -0.125f - (clamp(sharpness, 0.0f, 1.0f) * 0.125f);
    float3 wRGB = sqrt(ampRGB) * peak;

    // Filter sum and normalization:
    // (b + d + f + h) * wRGB + e / (1.0 + 4.0 * wRGB)
    float3 sum = (b.rgb + d.rgb + f.rgb + h.rgb) * wRGB + e.rgb;
    float3 weight = 1.0f + 4.0f * wRGB;
    float3 result = clamp(sum / weight, 0.0f, 1.0f);

    output.write(float4(result, e.a), gid);
}
