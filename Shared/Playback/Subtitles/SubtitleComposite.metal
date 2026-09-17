//
//  SubtitleComposite.metal
//  Edendale
//
//  Alpha-blending compute kernel for compositing subtitles onto video frames.
//

#include <metal_stdlib>
using namespace metal;

kernel void compositeSubtitles(
    texture2d<float, access::read>  video     [[texture(0)]],
    texture2d<float, access::read>  subtitle  [[texture(1)]],
    texture2d<float, access::write> output    [[texture(2)]],
    uint2 gid [[thread_position_in_grid]])
{
    if (gid.x >= output.get_width() || gid.y >= output.get_height()) {
        return;
    }

    float4 v = video.read(gid);
    float4 s = subtitle.read(gid);

    // Alpha blend subtitle over video: out = video * (1 - alpha) + subtitle * alpha
    output.write(float4(mix(v.rgb, s.rgb, s.a), 1.0), gid);
}
