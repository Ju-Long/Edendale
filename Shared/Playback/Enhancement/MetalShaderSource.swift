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
    """
}

final class EnhancementPipelineBundleToken {}
