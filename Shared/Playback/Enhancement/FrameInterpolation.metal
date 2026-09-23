#include <metal_stdlib>
using namespace metal;

// ---------------------------------------------------------------------------
// Bidirectional frame interpolation with occlusion-aware blending.
//
// Given two consecutive frames and a per-pixel motion vector field (curr→prev
// direction, normalised to frame dimensions), synthesise a frame at time
// `blendTime` (0.0 = prevFrame, 1.0 = currFrame, 0.5 = midpoint).
//
// Algorithm:
//   1. Forward warp  — sample prevFrame at (pos + mv * blendTime)
//   2. Backward warp — sample currFrame at (pos - mv * (1 - blendTime))
//   3. Consistency check — if the two warp targets disagree by more than a
//      threshold, one side is occluded; favour the visible sample.
//   4. Blend non-occluded pixels with weights proportional to temporal distance.
//   5. Hole-fill — if both samples fall outside the frame, fall back to
//      currFrame at the pixel position.
// ---------------------------------------------------------------------------

inline float lumaBrightness(float3 c) {
    return dot(c, float3(0.2126f, 0.7152f, 0.0722f));
}

kernel void frameInterpolate(
    texture2d<float, access::read>  prevFrame   [[texture(0)]],
    texture2d<float, access::read>  currFrame   [[texture(1)]],
    texture2d<float, access::read>  motionVec   [[texture(2)]],
    texture2d<float, access::write> output      [[texture(3)]],
    constant float                  &blendTime  [[buffer(0)]],
    uint2                           gid         [[thread_position_in_grid]])
{
    uint w = output.get_width();
    uint h = output.get_height();
    if (gid.x >= w || gid.y >= h) return;

    float2 mv = motionVec.read(gid).xy;

    // Convert normalised MV to pixel displacement.
    float2 mvPixel = float2(mv.x * float(w), mv.y * float(h));

    // Warp coordinates.
    float2 pos = float2(gid) + 0.5f;
    float2 fwdPos = pos + mvPixel * blendTime;
    float2 bwdPos = pos - mvPixel * (1.0f - blendTime);

    // Bounds check helpers.
    bool fwdValid = (fwdPos.x >= 0.0f && fwdPos.x < float(w) &&
                     fwdPos.y >= 0.0f && fwdPos.y < float(h));
    bool bwdValid = (bwdPos.x >= 0.0f && bwdPos.x < float(w) &&
                     bwdPos.y >= 0.0f && bwdPos.y < float(h));

    // Bilinear sample helper (clamp to edge).
    auto bilinearSample = [&](texture2d<float, access::read> tex, float2 p) -> float4 {
        p = clamp(p, float2(0.5f), float2(float(w) - 0.5f, float(h) - 0.5f));
        int2 p0 = int2(floor(p - 0.5f));
        float2 f = p - 0.5f - float2(p0);

        int2 p00 = clamp(p0, int2(0), int2(w - 1, h - 1));
        int2 p10 = clamp(p0 + int2(1, 0), int2(0), int2(w - 1, h - 1));
        int2 p01 = clamp(p0 + int2(0, 1), int2(0), int2(w - 1, h - 1));
        int2 p11 = clamp(p0 + int2(1, 1), int2(0), int2(w - 1, h - 1));

        float4 s00 = tex.read(uint2(p00));
        float4 s10 = tex.read(uint2(p10));
        float4 s01 = tex.read(uint2(p01));
        float4 s11 = tex.read(uint2(p11));

        return mix(mix(s00, s10, f.x), mix(s01, s11, f.x), f.y);
    };

    float4 fwdSample = fwdValid ? bilinearSample(prevFrame, fwdPos) : float4(0.0f);
    float4 bwdSample = bwdValid ? bilinearSample(currFrame, bwdPos) : float4(0.0f);

    float4 result;

    if (fwdValid && bwdValid) {
        // Occlusion check: if forward and backward samples differ too much,
        // one direction is likely occluded.
        float diff = abs(lumaBrightness(fwdSample.rgb) - lumaBrightness(bwdSample.rgb));
        float occlusionThreshold = 0.12f;

        if (diff > occlusionThreshold) {
            // Favour the sample closer in time.
            float fwdDist = length(fwdPos - pos);
            float bwdDist = length(bwdPos - pos);
            result = (fwdDist < bwdDist) ? fwdSample : bwdSample;
        } else {
            // Temporal blend: weight inversely proportional to time distance.
            float wFwd = 1.0f - blendTime;
            float wBwd = blendTime;
            result = fwdSample * wFwd + bwdSample * wBwd;
        }
    } else if (fwdValid) {
        result = fwdSample;
    } else if (bwdValid) {
        result = bwdSample;
    } else {
        // Both out of bounds — fall back to current frame at this pixel.
        result = currFrame.read(gid);
    }

    output.write(float4(clamp(result.rgb, 0.0f, 1.0f), 1.0f), gid);
}

// ---------------------------------------------------------------------------
// Scene cut: when the coarse motion pass left at least `cutBlockCount` blocks
// without a match, the two frames show different shots.  Replace the blend
// with the previous frame so the cut lands exactly on the next real frame.
// Runs after either backend's warp; costs one buffer read when there is no cut.
// ---------------------------------------------------------------------------
kernel void holdPreviousOnSceneCut(
    texture2d<float, access::read>  prevFrame       [[texture(0)]],
    texture2d<float, access::write> output          [[texture(1)]],
    device const uint               *unmatchedBlocks [[buffer(0)]],
    constant uint                   &cutBlockCount  [[buffer(1)]],
    uint2                           gid             [[thread_position_in_grid]])
{
    if (*unmatchedBlocks < cutBlockCount) return;
    if (gid.x >= output.get_width() || gid.y >= output.get_height()) return;
    output.write(float4(prevFrame.read(gid).rgb, 1.0f), gid);
}
