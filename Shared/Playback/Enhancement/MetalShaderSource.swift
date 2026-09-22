import Foundation
import Metal

/// Helper to reliably resolve MTLLibrary for enhancement compute shaders across
/// app bundles, test bundles, and previews.
enum MetalShaderSource {
    private static let lock = NSLock()
    private static var cachedLibraries: [ObjectIdentifier: MTLLibrary] = [:]

    static func library(for device: MTLDevice) -> MTLLibrary? {
        lock.lock()
        defer { lock.unlock() }

        let id = ObjectIdentifier(device)
        if let existing = cachedLibraries[id] {
            return existing
        }

        // 1. Try default library
        if let defaultLib = device.makeDefaultLibrary() {
            if defaultLib.functionNames.contains("contrastAdaptiveSharpening") {
                cachedLibraries[id] = defaultLib
                return defaultLib
            }
        }

        // 2. Try bundle libraries
        let candidateBundles = [
            Bundle.main,
            Bundle(for: EnhancementPipelineBundleToken.self)
        ]
        for bundle in candidateBundles {
            if let lib = try? device.makeDefaultLibrary(bundle: bundle) {
                if lib.functionNames.contains("contrastAdaptiveSharpening") {
                    cachedLibraries[id] = lib
                    return lib
                }
            }
        }

        // 3. Fallback: compile embedded source at runtime
        if let compiledLib = try? device.makeLibrary(source: embeddedShaderSource, options: nil) {
            cachedLibraries[id] = compiledLib
            return compiledLib
        }

        return nil
    }

    private static let embeddedShaderSource = """
    #include <metal_stdlib>
    using namespace metal;

    constant float PI = 3.14159265358979323846f;

    // AMD Contrast Adaptive Sharpening (CAS)
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

        float4 a = input.read(uint2(xm1, ym1));
        float4 b = input.read(uint2(x,   ym1));
        float4 c = input.read(uint2(xp1, ym1));
        float4 d = input.read(uint2(xm1, y));
        float4 e = input.read(uint2(x,   y));
        float4 f = input.read(uint2(xp1, y));
        float4 g = input.read(uint2(xm1, yp1));
        float4 h = input.read(uint2(x,   yp1));
        float4 i = input.read(uint2(xp1, yp1));

        float3 minRGB = min(min(min(d.rgb, e.rgb), f.rgb), min(b.rgb, h.rgb));
        float3 maxRGB = max(max(max(d.rgb, e.rgb), f.rgb), max(b.rgb, h.rgb));

        float3 minRGB2 = min(min(min(a.rgb, c.rgb), g.rgb), i.rgb);
        minRGB2 = min(minRGB, minRGB2);
        float3 maxRGB2 = max(max(max(a.rgb, c.rgb), g.rgb), i.rgb);
        maxRGB2 = max(maxRGB, maxRGB2);

        minRGB = minRGB + minRGB2;
        maxRGB = maxRGB + maxRGB2;

        float3 ampRGB = clamp(min(minRGB, 2.0f - maxRGB) / max(maxRGB, float3(1e-5f)), 0.0f, 1.0f);
        float peak = -0.125f - (clamp(sharpness, 0.0f, 1.0f) * 0.125f);
        float3 wRGB = sqrt(ampRGB) * peak;

        float3 sum = (b.rgb + d.rgb + f.rgb + h.rgb) * wRGB + e.rgb;
        float3 weight = 1.0f + 4.0f * wRGB;
        float3 result = clamp(sum / weight, 0.0f, 1.0f);

        output.write(float4(result, e.a), gid);
    }

    // Temporal Noise Reduction
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

        if (strength <= 0.0f || motionThreshold <= 0.0f) {
            outputFrame.write(current, gid);
            return;
        }

        float3 diff = abs(current.rgb - history.rgb);
        float lumaDiff = dot(diff, float3(0.299f, 0.587f, 0.114f));
        float maxDiff = max(max(diff.r, diff.g), diff.b);
        float metric = (lumaDiff + maxDiff) * 0.5f;

        float low = motionThreshold * 0.5f;
        float high = motionThreshold * 1.5f;
        float motion = smoothstep(low, high, metric);

        float maxHistoryBlend = 0.85f * clamp(strength, 0.0f, 1.0f);
        float historyWeight = (1.0f - motion) * maxHistoryBlend;

        float3 blended = mix(current.rgb, history.rgb, historyWeight);
        outputFrame.write(float4(blended, current.a), gid);
    }

    // Lanczos-2 Upscale Fallback
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

        float u = ((float(gid.x) + 0.5f) * float(inW) / float(outW)) - 0.5f;
        float v = ((float(gid.y) + 0.5f) * float(inH) / float(outH)) - 0.5f;

        int centerU = int(floor(u));
        int centerV = int(floor(v));

        float4 colorSum = float4(0.0f);
        float totalWeight = 0.0f;

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

    // Color Adjustments
    struct VideoAdjustmentUniforms {
        float brightness;
        float contrast;
        float gamma;
        float saturation;
        float hue;
    };

    inline float3 applyHueRotation(float3 rgb, float hueDegrees) {
        if (abs(hueDegrees) < 1e-3f) {
            return rgb;
        }
        float angle = hueDegrees * (3.14159265358979323846f / 180.0f);
        float cosA = cos(angle);
        float sinA = sin(angle);
        float3 axis = float3(0.57735026919f);
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

        rgb *= params.brightness;
        rgb = (rgb - 0.5f) * params.contrast + 0.5f;

        if (params.gamma > 0.01f && abs(params.gamma - 1.0f) > 1e-3f) {
            rgb = pow(max(rgb, float3(0.0f)), float3(1.0f / params.gamma));
        }

        float luma = dot(rgb, float3(0.2126f, 0.7152f, 0.0722f));
        rgb = mix(float3(luma), rgb, params.saturation);

        rgb = applyHueRotation(rgb, params.hue);

        output.write(float4(clamp(rgb, 0.0f, 1.0f), pixel.a), gid);
    }

    // -----------------------------------------------------------------------
    // Motion Estimation — coarse (16×16), refined (4×4), densify, scene-cut
    // -----------------------------------------------------------------------

    inline float meL(float4 c) {
        return dot(c.rgb, float3(0.2126f, 0.7152f, 0.0722f));
    }

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
        int2 bestOff = int2(0, 0);
        int sr = int(searchRadius);
        for (int dy = -sr; dy <= sr; dy += 2) {
            for (int dx = -sr; dx <= sr; dx += 2) {
                float sad = 0.0f;
                for (uint py = 0; py < blockSize; py += 2) {
                    for (uint px = 0; px < blockSize; px += 2) {
                        uint cx = bx + px; uint cy = by + py;
                        if (cx >= frameW || cy >= frameH) continue;
                        int px2 = clamp(int(cx)+dx, 0, int(frameW)-1);
                        int py2 = clamp(int(cy)+dy, 0, int(frameH)-1);
                        sad += abs(meL(currFrame.read(uint2(cx,cy))) - meL(prevFrame.read(uint2(px2,py2))));
                    }
                }
                if (sad < bestSAD) { bestSAD = sad; bestOff = int2(dx, dy); }
            }
        }
        int2 center = bestOff;
        for (int dy = -1; dy <= 1; ++dy) {
            for (int dx = -1; dx <= 1; ++dx) {
                if (dx == 0 && dy == 0) continue;
                int ox = center.x+dx; int oy = center.y+dy;
                if (abs(ox) > sr || abs(oy) > sr) continue;
                float sad = 0.0f;
                for (uint py = 0; py < blockSize; py += 2) {
                    for (uint px = 0; px < blockSize; px += 2) {
                        uint cx = bx+px; uint cy = by+py;
                        if (cx >= frameW || cy >= frameH) continue;
                        int px2 = clamp(int(cx)+ox, 0, int(frameW)-1);
                        int py2 = clamp(int(cy)+oy, 0, int(frameH)-1);
                        sad += abs(meL(currFrame.read(uint2(cx,cy))) - meL(prevFrame.read(uint2(px2,py2))));
                    }
                }
                if (sad < bestSAD) { bestSAD = sad; bestOff = int2(ox, oy); }
            }
        }
        float2 mv = float2(float(bestOff.x)/float(frameW), float(bestOff.y)/float(frameH));
        motionOut.write(float4(mv.x, mv.y, 0.0f, 0.0f), gid);
    }

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
        uint coarseX = min(bx / coarseBlock, coarseMV.get_width() - 1);
        uint coarseY = min(by / coarseBlock, coarseMV.get_height() - 1);
        float4 cmv = coarseMV.read(uint2(coarseX, coarseY));
        int baseOX = int(round(cmv.x * float(frameW)));
        int baseOY = int(round(cmv.y * float(frameH)));
        float bestSAD = 1e30f;
        int2 bestOff = int2(baseOX, baseOY);
        for (int dy = -4; dy <= 4; ++dy) {
            for (int dx = -4; dx <= 4; ++dx) {
                int ox = baseOX+dx; int oy = baseOY+dy;
                float sad = 0.0f;
                for (uint py = 0; py < blockSize; ++py) {
                    for (uint px = 0; px < blockSize; ++px) {
                        uint cx = bx+px; uint cy = by+py;
                        if (cx >= frameW || cy >= frameH) continue;
                        int px2 = clamp(int(cx)+ox, 0, int(frameW)-1);
                        int py2 = clamp(int(cy)+oy, 0, int(frameH)-1);
                        sad += abs(meL(currFrame.read(uint2(cx,cy))) - meL(prevFrame.read(uint2(px2,py2))));
                    }
                }
                if (sad < bestSAD) { bestSAD = sad; bestOff = int2(ox, oy); }
            }
        }
        float2 mv = float2(float(bestOff.x)/float(frameW), float(bestOff.y)/float(frameH));
        refinedMV.write(float4(mv.x, mv.y, 0.0f, 0.0f), gid);
    }

    inline float2 medVec(float2 a, float2 b, float2 c) {
        float mx = a.x+b.x+c.x - min(a.x,min(b.x,c.x)) - max(a.x,max(b.x,c.x));
        float my = a.y+b.y+c.y - min(a.y,min(b.y,c.y)) - max(a.y,max(b.y,c.y));
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
        float bxf = (float(gid.x)+0.5f)/float(blockSize) - 0.5f;
        float byf = (float(gid.y)+0.5f)/float(blockSize) - 0.5f;
        int bx0 = clamp(int(floor(bxf)), 0, int(mvW)-1);
        int by0 = clamp(int(floor(byf)), 0, int(mvH)-1);
        int bx1 = clamp(bx0+1, 0, int(mvW)-1);
        int by1 = clamp(by0+1, 0, int(mvH)-1);
        float fx = bxf - float(bx0);
        float fy = byf - float(by0);
        float2 v00 = blockMV.read(uint2(bx0,by0)).xy;
        float2 v10 = blockMV.read(uint2(bx1,by0)).xy;
        float2 v01 = blockMV.read(uint2(bx0,by1)).xy;
        float2 v11 = blockMV.read(uint2(bx1,by1)).xy;
        float2 mv = mix(mix(v00,v10,fx), mix(v01,v11,fx), fy);
        float2 left  = blockMV.read(uint2(clamp(int(bxf),0,int(mvW)-1), clamp(int(byf+0.5f),0,int(mvH)-1))).xy;
        float2 right = blockMV.read(uint2(clamp(int(bxf)+1,0,int(mvW)-1), clamp(int(byf+0.5f),0,int(mvH)-1))).xy;
        mv = medVec(left, mv, right);
        pixelMV.write(float4(mv.x, mv.y, 0.0f, 0.0f), gid);
    }

    kernel void sceneCutScore(
        texture2d<float, access::read>  prevFrame   [[texture(0)]],
        texture2d<float, access::read>  currFrame   [[texture(1)]],
        device atomic_uint              *totalSAD   [[buffer(0)]],
        device atomic_uint              *pixelCount [[buffer(1)]],
        uint2                           gid         [[thread_position_in_grid]])
    {
        uint w = currFrame.get_width();
        uint h = currFrame.get_height();
        uint x = gid.x * 4; uint y = gid.y * 4;
        if (x >= w || y >= h) return;
        float lc = meL(currFrame.read(uint2(x,y)));
        float lp = meL(prevFrame.read(uint2(x,y)));
        uint diff = uint(abs(lc - lp) * 1000.0f);
        atomic_fetch_add_explicit(totalSAD, diff, memory_order_relaxed);
        atomic_fetch_add_explicit(pixelCount, 1u, memory_order_relaxed);
    }

    // Frame Interpolation — bidirectional warp + blend
    inline float interpLuma(float3 c) {
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
        float2 mvPx = float2(mv.x*float(w), mv.y*float(h));
        float2 pos = float2(gid) + 0.5f;
        float2 fwdPos = pos + mvPx * blendTime;
        float2 bwdPos = pos - mvPx * (1.0f - blendTime);
        bool fwdOk = (fwdPos.x >= 0 && fwdPos.x < float(w) && fwdPos.y >= 0 && fwdPos.y < float(h));
        bool bwdOk = (bwdPos.x >= 0 && bwdPos.x < float(w) && bwdPos.y >= 0 && bwdPos.y < float(h));
        // Bilinear helper (clamped).
        auto bsamp = [&](texture2d<float, access::read> tex, float2 p) -> float4 {
            p = clamp(p, float2(0.5f), float2(float(w)-0.5f, float(h)-0.5f));
            int2 p0 = int2(floor(p-0.5f));
            float2 f = p - 0.5f - float2(p0);
            int2 c00 = clamp(p0, int2(0), int2(w-1,h-1));
            int2 c10 = clamp(p0+int2(1,0), int2(0), int2(w-1,h-1));
            int2 c01 = clamp(p0+int2(0,1), int2(0), int2(w-1,h-1));
            int2 c11 = clamp(p0+int2(1,1), int2(0), int2(w-1,h-1));
            return mix(mix(tex.read(uint2(c00)),tex.read(uint2(c10)),f.x),
                       mix(tex.read(uint2(c01)),tex.read(uint2(c11)),f.x), f.y);
        };
        float4 fS = fwdOk ? bsamp(prevFrame, fwdPos) : float4(0);
        float4 bS = bwdOk ? bsamp(currFrame, bwdPos) : float4(0);
        float4 result;
        if (fwdOk && bwdOk) {
            float d = abs(interpLuma(fS.rgb) - interpLuma(bS.rgb));
            if (d > 0.12f) {
                result = (length(fwdPos-pos) < length(bwdPos-pos)) ? fS : bS;
            } else {
                result = fS*(1.0f-blendTime) + bS*blendTime;
            }
        } else if (fwdOk) { result = fS; }
          else if (bwdOk) { result = bS; }
          else { result = currFrame.read(gid); }
        output.write(float4(clamp(result.rgb, 0.0f, 1.0f), 1.0f), gid);
    }
    """
}

final class EnhancementPipelineBundleToken {}
