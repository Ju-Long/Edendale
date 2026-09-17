#include <metal_stdlib>
using namespace metal;

// Temporal Noise Reduction compute kernel.
// Blends current frame with previous history frame.
// Pixels with difference exceeding motionThreshold detect motion and bypass blending.
kernel void temporalDenoise(
    texture2d<float, access::read>  currentFrame    [[texture(0)]],
    texture2d<float, access::read>  historyFrame    [[texture(1)]],
    texture2d<float, access::write> outputFrame     [[texture(2)]],
    constant float                  &strength       [[buffer(0)]],
    constant float                  &motionThreshold [[buffer(1)]],
    uint2                           gid             [[thread_position_in_grid]])
{
    if (gid.x >= outputFrame.get_width() || gid.y >= outputFrame.get_height()) {
        return;
    }

    float4 current = currentFrame.read(gid);
    float4 history = historyFrame.read(gid);

    // If strength is zero or motion threshold is zero, passthrough
    if (strength <= 0.0f || motionThreshold <= 0.0f) {
        outputFrame.write(current, gid);
        return;
    }

    // Calculate perceptual difference (luminance + max channel delta)
    float3 diff = abs(current.rgb - history.rgb);
    float lumaDiff = dot(diff, float3(0.299f, 0.587f, 0.114f));
    float maxDiff = max(max(diff.r, diff.g), diff.b);
    float metric = (lumaDiff + maxDiff) * 0.5f;

    // Smooth transition from full blend (static) to zero blend (motion)
    // Between motionThreshold * 0.5 and motionThreshold * 1.5
    float low = motionThreshold * 0.5f;
    float high = motionThreshold * 1.5f;
    float motion = smoothstep(low, high, metric);

    // Blend weight: maximum blend with history is capped (e.g. up to 85% history when strength = 1.0)
    float maxHistoryBlend = 0.85f * clamp(strength, 0.0f, 1.0f);
    float historyWeight = (1.0f - motion) * maxHistoryBlend;

    float3 blended = mix(current.rgb, history.rgb, historyWeight);
    outputFrame.write(float4(blended, current.a), gid);
}
