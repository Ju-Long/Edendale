#include <metal_stdlib>
using namespace metal;

struct VideoAdjustmentUniforms {
    float brightness; // default 1.0, range 0..2
    float contrast;   // default 1.0, range 0..2
    float gamma;      // default 1.0, range 0.25..3
    float saturation; // default 1.0, range 0..3
    float hue;        // default 0.0, range 0..360 (degrees)
};

// Rotate RGB vector around the diagonal gray axis (1,1,1)/sqrt(3) by angle in degrees.
inline float3 applyHueRotation(float3 rgb, float hueDegrees) {
    if (abs(hueDegrees) < 1e-3f) {
        return rgb;
    }
    float angle = hueDegrees * (3.14159265358979323846f / 180.0f);
    float cosA = cos(angle);
    float sinA = sin(angle);
    float3 axis = float3(0.57735026919f); // (1, 1, 1) / sqrt(3)
    return rgb * cosA + cross(axis, rgb) * sinA + axis * dot(axis, rgb) * (1.0f - cosA);
}

kernel void applyColorAdjustments(
    texture2d<float, access::read>  input    [[texture(0)]],
    texture2d<float, access::write> output   [[texture(1)]],
    constant VideoAdjustmentUniforms &params [[buffer(0)]],
    uint2                           gid      [[thread_position_in_grid]])
{
    if (gid.x >= output.get_width() || gid.y >= output.get_height()) {
        return;
    }

    float4 pixel = input.read(gid);
    float3 rgb = pixel.rgb;

    // 1. Brightness
    rgb *= params.brightness;

    // 2. Contrast around mid-gray (0.5)
    rgb = (rgb - 0.5f) * params.contrast + 0.5f;

    // 3. Gamma correction
    if (params.gamma > 0.01f && abs(params.gamma - 1.0f) > 1e-3f) {
        rgb = pow(max(rgb, float3(0.0f)), float3(1.0f / params.gamma));
    }

    // 4. Saturation (Rec.709 luma coefficients)
    float luma = dot(rgb, float3(0.2126f, 0.7152f, 0.0722f));
    rgb = mix(float3(luma), rgb, params.saturation);

    // 5. Hue rotation
    rgb = applyHueRotation(rgb, params.hue);

    output.write(float4(clamp(rgb, 0.0f, 1.0f), pixel.a), gid);
}
