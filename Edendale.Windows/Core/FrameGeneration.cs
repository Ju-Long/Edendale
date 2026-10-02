using System.Globalization;

namespace Edendale.Windows.Core;

/// <summary>Where frame generation and upscaling run (ENHANCEMENT.md G.1).</summary>
public enum FrameGenerationBackend
{
    /// <summary>Not offered: no supported GPU, or the software adapter.</summary>
    None,

    /// <summary>NVIDIA: Edendale's CUDA kernels through the driver API (nvcuda.dll).</summary>
    Cuda,

    /// <summary>Intel: the same algorithm as Direct3D 11 compute shaders.</summary>
    Direct3D,
}

/// <summary>One motion block's vector (in source pixels, from the earlier frame to the later one) and its match cost.</summary>
public readonly record struct MotionVector(float X, float Y, float Cost);

/// <summary>When the generated frame and the real one go on screen.</summary>
public readonly record struct FrameGenerationPlan(bool Interpolate, double GeneratedAt, double RealAt);

/// <summary>A destination rectangle in output pixels.</summary>
public readonly record struct OutputRect(int X, int Y, int Width, int Height);

/// <summary>
/// The rules behind frame generation and upscaling (Option B, which Windows
/// adds on top of Option A). The GPU kernels in <c>FrameGeneration/</c>
/// implement exactly these constants and the reference algorithm in
/// <see cref="FrameInterpolation"/>, so the tests here pin what the GPU does.
/// </summary>
public static class FrameGenerationRules
{
    /// <summary>Player Adjustments → Enhancement → Frame Generation, device-local. Off by default.</summary>
    public const string EnabledKey = "video.frameGeneration";

    /// <summary>Motion blocks are this many source pixels square.</summary>
    public const int BlockSize = 16;

    /// <summary>The coarse search tries vectors up to this far in each axis, every <see cref="CoarseStep"/> pixels.</summary>
    public const int CoarseRadius = 32;
    public const int CoarseStep = 4;

    /// <summary>The coarse search compares every fourth pixel of a block (16 samples).</summary>
    public const int CoarseSampleStride = 4;

    /// <summary>The refinement tries ±4 pixels around the coarse winner in steps of 2, comparing every second pixel.</summary>
    public const int RefineRadius = 4;
    public const int RefineStep = 2;
    public const int RefineSampleStride = 2;

    /// <summary>A small cost per pixel of displacement, so flat areas keep a zero vector.</summary>
    public const float MotionPenalty = 0.0001f;

    /// <summary>Below this mean luma difference a block's match is trusted completely…</summary>
    public const float TrustedCost = 0.04f;

    /// <summary>…and above this one the plain blend of both frames replaces it.</summary>
    public const float UntrustedCost = 0.10f;

    /// <summary>A frame whose blocks match this badly on average is a scene cut: the earlier frame is held.</summary>
    public const float SceneCutCost = 0.18f;

    /// <summary>Doubling is for 30 fps and slower sources, like Motion Smoothing.</summary>
    public const double MaximumFrameRate = 30.5;

    public const int MaximumWidth = 3840;
    public const int MaximumHeight = 2160;

    /// <summary>Faster than this, frames arrive too quickly to double; real frames are shown as they come.</summary>
    public const double MaximumPlaybackRate = 1.5;

    /// <summary>NVIDIA uses CUDA, Intel uses Direct3D compute. AMD keeps LibVLC's own doubler (Motion Smoothing).</summary>
    public static FrameGenerationBackend BackendFor(GpuCapabilities capabilities) =>
        capabilities.IsSoftwareAdapter
            ? FrameGenerationBackend.None
            : capabilities.Vendor switch
            {
                GpuVendor.Nvidia => FrameGenerationBackend.Cuda,
                GpuVendor.Intel => FrameGenerationBackend.Direct3D,
                _ => FrameGenerationBackend.None,
            };

    /// <summary>A known frame rate of 30 fps or less, at 4K or smaller.</summary>
    public static bool IsEligible(VideoSourceInfo source) =>
        source.FrameRate is double rate && rate > 0 && rate <= MaximumFrameRate
        && source.Width > 0 && source.Height > 0
        && source.Width <= MaximumWidth && source.Height <= MaximumHeight;

    /// <summary>Whether frames are generated at <paramref name="playbackRate"/>.</summary>
    public static bool GeneratesAt(double playbackRate) =>
        double.IsFinite(playbackRate) && playbackRate > 0 && playbackRate <= MaximumPlaybackRate + 1e-6;

    /// <summary>
    /// LibVLC's four-character chromas with more than 8 bits per sample
    /// (10-bit, 12-bit, and 16-bit YUV, and packed 10-bit formats). Frame
    /// generation takes 8-bit NV12, and LibVLC converts higher depths to it
    /// without tone mapping, so HDR would look washed out: those sources play
    /// the normal way instead.
    /// </summary>
    private static readonly HashSet<string> HighBitDepthChromas = new(StringComparer.Ordinal)
    {
        "P010", "P016", "P210", "P216", "P410", "P416",
        "I0AL", "I0AB", "I2AL", "I2AB", "I4AL", "I4AB",
        "I09L", "I09B", "I29L", "I29B", "I49L", "I49B",
        "I0CL", "I0CB", "I2CL", "I2CB", "I4CL", "I4CB",
        "I0FL", "I0FB", "I2FL", "I2FB", "I4FL", "I4FB",
        "v210", "XV30", "Y210", "Y216", "Y410", "Y416", "RGBA64", "GBAL", "GBAB",
    };

    /// <summary>Whether LibVLC's source chroma carries more than 8 bits per sample.</summary>
    public static bool IsHighBitDepth(string? chroma) =>
        chroma is not null && HighBitDepthChromas.Contains(chroma.TrimEnd('\0', ' '));

    /// <summary>"24 fps → 48 fps · CUDA"; null when nothing is generated.</summary>
    public static string? Label(VideoSourceInfo source, FrameGenerationBackend backend, CultureInfo? culture = null)
    {
        if (backend == FrameGenerationBackend.None || !IsEligible(source)) return null;
        var rate = source.FrameRate!.Value;
        var engine = backend == FrameGenerationBackend.Cuda ? "CUDA" : "DirectX";
        return string.Format(culture ?? CultureInfo.CurrentCulture, "{0:0.###} fps → {1:0.###} fps · {2}", rate, rate * 2, engine);
    }

    /// <summary>Motion blocks across a dimension (the last one may be partial).</summary>
    public static int Blocks(int pixels) => (pixels + BlockSize - 1) / BlockSize;

    /// <summary>Candidates per axis in the coarse search (17).</summary>
    public static int CoarseCandidatesPerAxis => CoarseRadius * 2 / CoarseStep + 1;

    /// <summary>Candidates per axis in the refinement (5).</summary>
    public static int RefineCandidatesPerAxis => RefineRadius * 2 / RefineStep + 1;

    /// <summary>BT.709 for HD and larger, BT.601 below 720 lines (what decoders assume when a file doesn't say).</summary>
    public static int ColorMatrix(int height) => height >= 720 ? 709 : 601;

    /// <summary>
    /// Where the picture goes in an output of <paramref name="outputWidth"/> ×
    /// <paramref name="outputHeight"/> pixels: letterboxed to fit, or cropped
    /// to fill, honouring the source's pixel aspect ratio.
    /// </summary>
    public static OutputRect DestinationRect(
        int sourceWidth, int sourceHeight, uint sarNum, uint sarDen, int outputWidth, int outputHeight, bool fill)
    {
        if (sourceWidth <= 0 || sourceHeight <= 0 || outputWidth <= 0 || outputHeight <= 0) return new OutputRect(0, 0, 0, 0);
        var pixelAspect = sarNum > 0 && sarDen > 0 ? (double)sarNum / sarDen : 1.0;
        var pictureAspect = sourceWidth * pixelAspect / sourceHeight;
        var outputAspect = (double)outputWidth / outputHeight;
        var widthLimited = fill ? pictureAspect < outputAspect : pictureAspect > outputAspect;
        int width, height;
        if (widthLimited)
        {
            width = outputWidth;
            height = (int)Math.Round(outputWidth / pictureAspect);
        }
        else
        {
            height = outputHeight;
            width = (int)Math.Round(outputHeight * pictureAspect);
        }
        return new OutputRect((outputWidth - width) / 2, (outputHeight - height) / 2, width, height);
    }

    /// <summary>The Lanczos-3 kernel the upscaler samples with.</summary>
    public static double Lanczos3(double x)
    {
        x = Math.Abs(x);
        if (x < 1e-8) return 1;
        if (x >= 3) return 0;
        var pix = Math.PI * x;
        return 3 * Math.Sin(pix) * Math.Sin(pix / 3) / (pix * pix);
    }
}

/// <summary>
/// Schedules the doubled frames. LibVLC hands each real frame over when it
/// is due, so the generated frame between it and the previous one is shown
/// at once and the real frame half an interval later. Video therefore runs
/// half a frame behind LibVLC's clock, and the player delays the audio by
/// <see cref="Delay"/> to match. A gap (a pause, a seek, a stall) or a frame
/// that comes too soon shows the real frame without generating one.
/// </summary>
public sealed class FrameGenerationClock
{
    private double? _last;

    /// <param name="nominalInterval">Seconds per source frame at the current speed.</param>
    public FrameGenerationClock(double nominalInterval)
    {
        Interval = nominalInterval > 0 && double.IsFinite(nominalInterval) ? nominalInterval : 1 / 24.0;
    }

    /// <summary>The current estimate of seconds between real frames.</summary>
    public double Interval { get; private set; }

    /// <summary>How far video runs behind LibVLC: half an interval.</summary>
    public double Delay => Interval / 2;

    /// <summary>A real frame arrived at <paramref name="now"/> seconds.</summary>
    public FrameGenerationPlan Arrive(double now)
    {
        var previous = _last;
        _last = now;
        if (previous is not double last) return new FrameGenerationPlan(false, now, now + Delay);

        var delta = now - last;
        var regular = delta >= Interval * 0.5 && delta <= Interval * 1.75;
        if (regular)
        {
            // Follows a speed change within a few frames without chasing jitter.
            Interval += (delta - Interval) * 0.125;
        }
        return new FrameGenerationPlan(regular, now, now + Delay);
    }

    /// <summary>A new nominal interval (the speed changed) or a seek: start over.</summary>
    public void Reset(double? nominalInterval = null)
    {
        _last = null;
        if (nominalInterval is double interval && interval > 0 && double.IsFinite(interval)) Interval = interval;
    }
}

/// <summary>
/// The CPU reference for the GPU kernels: symmetric block matching around
/// the midpoint, a 3×3 median over the vectors, motion-compensated blending
/// that falls back to a plain blend where the match is poor, a held frame on
/// a scene cut, and a Lanczos-3 upscaler with anti-ringing. Planes are
/// row-major floats from 0 to 1.
/// </summary>
public static class FrameInterpolation
{
    /// <summary>Clamped nearest-pixel read.</summary>
    public static float Pixel(ReadOnlySpan<float> plane, int width, int height, int x, int y) =>
        plane[Math.Clamp(y, 0, height - 1) * width + Math.Clamp(x, 0, width - 1)];

    /// <summary>Clamped bilinear read at a pixel-centre coordinate.</summary>
    public static float Bilinear(ReadOnlySpan<float> plane, int width, int height, float x, float y)
    {
        var x0 = (int)MathF.Floor(x);
        var y0 = (int)MathF.Floor(y);
        var tx = x - x0;
        var ty = y - y0;
        var top = Pixel(plane, width, height, x0, y0) * (1 - tx) + Pixel(plane, width, height, x0 + 1, y0) * tx;
        var bottom = Pixel(plane, width, height, x0, y0 + 1) * (1 - tx) + Pixel(plane, width, height, x0 + 1, y0 + 1) * tx;
        return top * (1 - ty) + bottom * ty;
    }

    /// <summary>
    /// The cost of vector (<paramref name="vx"/>, <paramref name="vy"/>) for a
    /// block: the earlier frame half a vector back against the later frame
    /// half a vector on, at every <paramref name="stride"/>-th pixel, plus the
    /// motion penalty. Vectors are even, so both halves land on pixels.
    /// </summary>
    public static float BlockCost(
        ReadOnlySpan<float> a, ReadOnlySpan<float> b, int width, int height,
        int blockX, int blockY, int vx, int vy, int stride)
    {
        var size = FrameGenerationRules.BlockSize;
        var half = stride / 2;
        float sum = 0;
        var count = 0;
        for (var j = half; j < size; j += stride)
        {
            for (var i = half; i < size; i += stride)
            {
                var px = blockX * size + i;
                var py = blockY * size + j;
                sum += MathF.Abs(Pixel(a, width, height, px - vx / 2, py - vy / 2) - Pixel(b, width, height, px + vx / 2, py + vy / 2));
                count++;
            }
        }
        return sum / count + FrameGenerationRules.MotionPenalty * (Math.Abs(vx) + Math.Abs(vy));
    }

    /// <summary>The coarse search and then the refinement, for every block.</summary>
    public static MotionVector[] EstimateMotion(ReadOnlySpan<float> a, ReadOnlySpan<float> b, int width, int height)
    {
        var blocksX = FrameGenerationRules.Blocks(width);
        var blocksY = FrameGenerationRules.Blocks(height);
        var field = new MotionVector[blocksX * blocksY];
        for (var by = 0; by < blocksY; by++)
        {
            for (var bx = 0; bx < blocksX; bx++)
            {
                var (cx, cy, _) = Search(a, b, width, height, bx, by, 0, 0,
                    FrameGenerationRules.CoarseRadius, FrameGenerationRules.CoarseStep, FrameGenerationRules.CoarseSampleStride);
                var (fx, fy, cost) = Search(a, b, width, height, bx, by, cx, cy,
                    FrameGenerationRules.RefineRadius, FrameGenerationRules.RefineStep, FrameGenerationRules.RefineSampleStride);
                field[by * blocksX + bx] = new MotionVector(fx, fy, cost);
            }
        }
        return field;
    }

    /// <summary>
    /// The lowest-cost candidate around (<paramref name="centerX"/>,
    /// <paramref name="centerY"/>). Candidates run row by row from the top
    /// left, and a tie keeps the earlier one: the GPU reduction does the same.
    /// </summary>
    public static (int X, int Y, float Cost) Search(
        ReadOnlySpan<float> a, ReadOnlySpan<float> b, int width, int height,
        int blockX, int blockY, int centerX, int centerY, int radius, int step, int stride)
    {
        var bestX = centerX;
        var bestY = centerY;
        var best = float.MaxValue;
        for (var dy = -radius; dy <= radius; dy += step)
        {
            for (var dx = -radius; dx <= radius; dx += step)
            {
                var cost = BlockCost(a, b, width, height, blockX, blockY, centerX + dx, centerY + dy, stride);
                if (cost < best)
                {
                    best = cost;
                    bestX = centerX + dx;
                    bestY = centerY + dy;
                }
            }
        }
        return (bestX, bestY, best);
    }

    /// <summary>A 3×3 median of each component (clamped at the edges), which removes stray vectors.</summary>
    public static MotionVector[] Smooth(IReadOnlyList<MotionVector> field, int blocksX, int blocksY)
    {
        var result = new MotionVector[field.Count];
        Span<float> xs = stackalloc float[9];
        Span<float> ys = stackalloc float[9];
        Span<float> costs = stackalloc float[9];
        for (var by = 0; by < blocksY; by++)
        {
            for (var bx = 0; bx < blocksX; bx++)
            {
                var n = 0;
                for (var j = -1; j <= 1; j++)
                {
                    for (var i = -1; i <= 1; i++)
                    {
                        var vector = field[Math.Clamp(by + j, 0, blocksY - 1) * blocksX + Math.Clamp(bx + i, 0, blocksX - 1)];
                        xs[n] = vector.X;
                        ys[n] = vector.Y;
                        costs[n] = vector.Cost;
                        n++;
                    }
                }
                xs.Sort();
                ys.Sort();
                costs.Sort();
                result[by * blocksX + bx] = new MotionVector(xs[4], ys[4], costs[4]);
            }
        }
        return result;
    }

    public static float MeanCost(IReadOnlyList<MotionVector> field) =>
        field.Count == 0 ? 0 : field.Average(vector => vector.Cost);

    public static bool IsSceneCut(IReadOnlyList<MotionVector> field) =>
        MeanCost(field) > FrameGenerationRules.SceneCutCost;

    /// <summary>The vector field at a pixel, interpolated between the four nearest block centres.</summary>
    public static MotionVector VectorAt(IReadOnlyList<MotionVector> field, int blocksX, int blocksY, int x, int y)
    {
        var size = FrameGenerationRules.BlockSize;
        var fx = (x + 0.5f) / size - 0.5f;
        var fy = (y + 0.5f) / size - 0.5f;
        var x0 = Math.Clamp((int)MathF.Floor(fx), 0, blocksX - 1);
        var y0 = Math.Clamp((int)MathF.Floor(fy), 0, blocksY - 1);
        var x1 = Math.Min(x0 + 1, blocksX - 1);
        var y1 = Math.Min(y0 + 1, blocksY - 1);
        var tx = Math.Clamp(fx - x0, 0, 1);
        var ty = Math.Clamp(fy - y0, 0, 1);
        MotionVector Lerp(MotionVector p, MotionVector q, float t) =>
            new(p.X + (q.X - p.X) * t, p.Y + (q.Y - p.Y) * t, p.Cost + (q.Cost - p.Cost) * t);
        var top = Lerp(field[y0 * blocksX + x0], field[y0 * blocksX + x1], tx);
        var bottom = Lerp(field[y1 * blocksX + x0], field[y1 * blocksX + x1], tx);
        return Lerp(top, bottom, ty);
    }

    /// <summary>How much of the plain blend replaces the motion-compensated one, from 0 to 1.</summary>
    public static float FallbackWeight(float cost) =>
        Math.Clamp((cost - FrameGenerationRules.TrustedCost) / (FrameGenerationRules.UntrustedCost - FrameGenerationRules.TrustedCost), 0, 1);

    /// <summary>The frame halfway between <paramref name="a"/> and <paramref name="b"/>.</summary>
    public static float[] Synthesize(ReadOnlySpan<float> a, ReadOnlySpan<float> b, int width, int height, IReadOnlyList<MotionVector> field)
    {
        var output = new float[width * height];
        if (IsSceneCut(field))
        {
            a.CopyTo(output);
            return output;
        }

        var blocksX = FrameGenerationRules.Blocks(width);
        var blocksY = FrameGenerationRules.Blocks(height);
        for (var y = 0; y < height; y++)
        {
            for (var x = 0; x < width; x++)
            {
                var vector = VectorAt(field, blocksX, blocksY, x, y);
                var compensated = 0.5f * (Bilinear(a, width, height, x - vector.X / 2, y - vector.Y / 2)
                    + Bilinear(b, width, height, x + vector.X / 2, y + vector.Y / 2));
                var plain = 0.5f * (a[y * width + x] + b[y * width + x]);
                output[y * width + x] = compensated + (plain - compensated) * FallbackWeight(vector.Cost);
            }
        }
        return output;
    }

    /// <summary>
    /// Scales <paramref name="source"/> into <paramref name="rect"/> of an
    /// output, black outside it. Enlarging uses Lanczos-3 clamped to the four
    /// nearest source pixels (no ringing halos); shrinking averages four
    /// bilinear samples per output pixel.
    /// </summary>
    public static float[] Scale(ReadOnlySpan<float> source, int width, int height, int outputWidth, int outputHeight, OutputRect rect)
    {
        var output = new float[outputWidth * outputHeight];
        if (rect.Width <= 0 || rect.Height <= 0) return output;
        var scaleX = (float)width / rect.Width;
        var scaleY = (float)height / rect.Height;
        var enlarging = scaleX <= 1 && scaleY <= 1;
        for (var oy = 0; oy < outputHeight; oy++)
        {
            for (var ox = 0; ox < outputWidth; ox++)
            {
                if (ox < rect.X || oy < rect.Y || ox >= rect.X + rect.Width || oy >= rect.Y + rect.Height) continue;
                var sx = (ox - rect.X + 0.5f) * scaleX - 0.5f;
                var sy = (oy - rect.Y + 0.5f) * scaleY - 0.5f;
                output[oy * outputWidth + ox] = enlarging
                    ? Lanczos(source, width, height, sx, sy)
                    : 0.25f * (Bilinear(source, width, height, sx - 0.25f * scaleX, sy - 0.25f * scaleY)
                        + Bilinear(source, width, height, sx + 0.25f * scaleX, sy - 0.25f * scaleY)
                        + Bilinear(source, width, height, sx - 0.25f * scaleX, sy + 0.25f * scaleY)
                        + Bilinear(source, width, height, sx + 0.25f * scaleX, sy + 0.25f * scaleY));
            }
        }
        return output;
    }

    /// <summary>One Lanczos-3 sample (6 × 6 taps), clamped to the nearest 2 × 2 pixels.</summary>
    public static float Lanczos(ReadOnlySpan<float> source, int width, int height, float sx, float sy)
    {
        var x0 = (int)MathF.Floor(sx);
        var y0 = (int)MathF.Floor(sy);
        Span<float> wx = stackalloc float[6];
        Span<float> wy = stackalloc float[6];
        float sumX = 0, sumY = 0;
        for (var k = 0; k < 6; k++)
        {
            wx[k] = (float)FrameGenerationRules.Lanczos3(sx - (x0 - 2 + k));
            wy[k] = (float)FrameGenerationRules.Lanczos3(sy - (y0 - 2 + k));
            sumX += wx[k];
            sumY += wy[k];
        }

        float value = 0;
        for (var j = 0; j < 6; j++)
        {
            float row = 0;
            for (var i = 0; i < 6; i++)
            {
                row += wx[i] * Pixel(source, width, height, x0 - 2 + i, y0 - 2 + j);
            }
            value += wy[j] * row;
        }
        value /= sumX * sumY;

        var p00 = Pixel(source, width, height, x0, y0);
        var p10 = Pixel(source, width, height, x0 + 1, y0);
        var p01 = Pixel(source, width, height, x0, y0 + 1);
        var p11 = Pixel(source, width, height, x0 + 1, y0 + 1);
        var low = MathF.Min(MathF.Min(p00, p10), MathF.Min(p01, p11));
        var high = MathF.Max(MathF.Max(p00, p10), MathF.Max(p01, p11));
        return Math.Clamp(value, low, high);
    }
}
