using System.Globalization;
using Edendale.Windows.Core;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Edendale.Windows.Tests;

/// <summary>
/// Frame generation and upscaling (ENHANCEMENT.md G): backend choice, the
/// presentation clock, output geometry, and the CPU reference the CUDA and
/// Direct3D kernels implement.
/// </summary>
[TestClass]
public sealed class FrameGenerationTests
{
    private static GpuCapabilities Gpu(GpuVendor vendor) => new() { Vendor = vendor };

    /// <summary>Smooth, non-repeating value noise, defined everywhere so a frame can be shifted exactly.</summary>
    private static float Texture(int x, int y)
    {
        static float Lattice(int x, int y)
        {
            var hash = (uint)(x * 374761393 + y * 668265263);
            hash = (hash ^ (hash >> 13)) * 1274126177;
            return (hash ^ (hash >> 16)) % 1000 / 1000f;
        }
        var gx = Math.Floor(x / 4.0);
        var gy = Math.Floor(y / 4.0);
        var tx = (float)(x / 4.0 - gx);
        var ty = (float)(y / 4.0 - gy);
        var ix = (int)gx;
        var iy = (int)gy;
        var top = Lattice(ix, iy) * (1 - tx) + Lattice(ix + 1, iy) * tx;
        var bottom = Lattice(ix, iy + 1) * (1 - tx) + Lattice(ix + 1, iy + 1) * tx;
        return top * (1 - ty) + bottom * ty;
    }

    private static float[] Frame(int width, int height, int shiftX, int shiftY)
    {
        var plane = new float[width * height];
        for (var y = 0; y < height; y++)
        {
            for (var x = 0; x < width; x++)
            {
                plane[y * width + x] = Texture(x - shiftX, y - shiftY);
            }
        }
        return plane;
    }

    // ------------------------------------------------------------------
    // Rules
    // ------------------------------------------------------------------

    [TestMethod]
    public void NvidiaUsesCudaIntelUsesDirect3DAndOthersNothing()
    {
        Assert.AreEqual(FrameGenerationBackend.Cuda, FrameGenerationRules.BackendFor(Gpu(GpuVendor.Nvidia)));
        Assert.AreEqual(FrameGenerationBackend.Direct3D, FrameGenerationRules.BackendFor(Gpu(GpuVendor.Intel)));
        Assert.AreEqual(FrameGenerationBackend.None, FrameGenerationRules.BackendFor(Gpu(GpuVendor.Amd)));
        Assert.AreEqual(FrameGenerationBackend.None, FrameGenerationRules.BackendFor(Gpu(GpuVendor.Qualcomm)));
        Assert.AreEqual(FrameGenerationBackend.None, FrameGenerationRules.BackendFor(Gpu(GpuVendor.Software)));
        Assert.AreEqual(FrameGenerationBackend.None, FrameGenerationRules.BackendFor(GpuCapabilities.None));
    }

    [TestMethod]
    public void OnlyKnownRatesUpTo30FpsAndUpTo4KAreDoubled()
    {
        Assert.IsTrue(FrameGenerationRules.IsEligible(new VideoSourceInfo(1920, 1080, 23.976)));
        Assert.IsTrue(FrameGenerationRules.IsEligible(new VideoSourceInfo(3840, 2160, 30)));
        Assert.IsTrue(FrameGenerationRules.IsEligible(new VideoSourceInfo(1920, 1080, 29.97)));
        Assert.IsFalse(FrameGenerationRules.IsEligible(new VideoSourceInfo(1920, 1080, 50)));
        Assert.IsFalse(FrameGenerationRules.IsEligible(new VideoSourceInfo(1920, 1080, null)));
        Assert.IsFalse(FrameGenerationRules.IsEligible(new VideoSourceInfo(7680, 4320, 24)));
        Assert.IsFalse(FrameGenerationRules.IsEligible(VideoSourceInfo.Unknown));
    }

    [TestMethod]
    public void GenerationPausesAboveOneAndAHalfTimesSpeed()
    {
        Assert.IsTrue(FrameGenerationRules.GeneratesAt(1.0));
        Assert.IsTrue(FrameGenerationRules.GeneratesAt(0.5));
        Assert.IsTrue(FrameGenerationRules.GeneratesAt(1.5));
        Assert.IsFalse(FrameGenerationRules.GeneratesAt(2.0));
        Assert.IsFalse(FrameGenerationRules.GeneratesAt(double.NaN));
    }

    [TestMethod]
    public void HigherBitDepthsPlayTheNormalWay()
    {
        foreach (var chroma in new[] { "P010", "I0AL", "I0AB", "P016", "v210", "I4AL\0" })
        {
            Assert.IsTrue(FrameGenerationRules.IsHighBitDepth(chroma), chroma);
        }
        foreach (var chroma in new[] { "NV12", "I420", "YV12", "J420", "RV32", "", null })
        {
            Assert.IsFalse(FrameGenerationRules.IsHighBitDepth(chroma), chroma ?? "null");
        }
    }

    [TestMethod]
    public void TheLabelNamesTheRatesAndTheEngine()
    {
        var culture = CultureInfo.InvariantCulture;
        Assert.AreEqual("24 fps → 48 fps · CUDA",
            FrameGenerationRules.Label(new VideoSourceInfo(1920, 1080, 24), FrameGenerationBackend.Cuda, culture));
        Assert.AreEqual("23.976 fps → 47.952 fps · DirectX",
            FrameGenerationRules.Label(new VideoSourceInfo(1920, 1080, 23.976), FrameGenerationBackend.Direct3D, culture));
        Assert.IsNull(FrameGenerationRules.Label(new VideoSourceInfo(1920, 1080, 60), FrameGenerationBackend.Cuda, culture));
        Assert.IsNull(FrameGenerationRules.Label(new VideoSourceInfo(1920, 1080, 24), FrameGenerationBackend.None, culture));
    }

    [TestMethod]
    public void SearchSizesMatchTheKernels()
    {
        Assert.AreEqual(17, FrameGenerationRules.CoarseCandidatesPerAxis);
        Assert.AreEqual(5, FrameGenerationRules.RefineCandidatesPerAxis);
        Assert.AreEqual(120, FrameGenerationRules.Blocks(1920));
        Assert.AreEqual(68, FrameGenerationRules.Blocks(1080));
        Assert.AreEqual(709, FrameGenerationRules.ColorMatrix(1080));
        Assert.AreEqual(601, FrameGenerationRules.ColorMatrix(576));
    }

    [TestMethod]
    public void PicturesFitOrFillTheOutput()
    {
        Assert.AreEqual(new OutputRect(0, 0, 3840, 2160), FrameGenerationRules.DestinationRect(1920, 1080, 1, 1, 3840, 2160, fill: false));
        // 2.39:1 letterboxed into 16:9, then cropped to fill it.
        Assert.AreEqual(new OutputRect(0, 138, 1920, 803), FrameGenerationRules.DestinationRect(1920, 803, 1, 1, 1920, 1080, fill: false));
        Assert.AreEqual(new OutputRect(-331, 0, 2582, 1080), FrameGenerationRules.DestinationRect(1920, 803, 1, 1, 1920, 1080, fill: true));
        // Anamorphic DVD: 720×480 with 32:27 pixels shows as 16:9.
        Assert.AreEqual(new OutputRect(0, 0, 1920, 1080), FrameGenerationRules.DestinationRect(720, 480, 32, 27, 1920, 1080, fill: false));
        Assert.AreEqual(new OutputRect(0, 0, 0, 0), FrameGenerationRules.DestinationRect(0, 0, 1, 1, 1920, 1080, fill: false));
    }

    [TestMethod]
    public void TheLanczosKernelIsOneAtZeroAndZeroAtOtherIntegers()
    {
        Assert.AreEqual(1, FrameGenerationRules.Lanczos3(0), 1e-9);
        for (var k = 1; k <= 3; k++) Assert.AreEqual(0, FrameGenerationRules.Lanczos3(k), 1e-9);
        Assert.AreEqual(0, FrameGenerationRules.Lanczos3(3.5), 1e-9);
        Assert.IsTrue(FrameGenerationRules.Lanczos3(1.5) < 0, "a negative lobe sharpens");
        Assert.AreEqual(FrameGenerationRules.Lanczos3(0.4), FrameGenerationRules.Lanczos3(-0.4), 1e-12);
    }

    // ------------------------------------------------------------------
    // Clock
    // ------------------------------------------------------------------

    [TestMethod]
    public void SteadyFramesAreDoubledHalfAFrameBehind()
    {
        var clock = new FrameGenerationClock(1 / 24.0);
        var first = clock.Arrive(10.0);
        Assert.IsFalse(first.Interpolate, "nothing to blend with yet");
        Assert.AreEqual(10.0 + 1 / 48.0, first.RealAt, 1e-9);

        var second = clock.Arrive(10.0 + 1 / 24.0);
        Assert.IsTrue(second.Interpolate);
        Assert.AreEqual(10.0 + 1 / 24.0, second.GeneratedAt, 1e-9);
        Assert.AreEqual(10.0 + 1 / 24.0 + 1 / 48.0, second.RealAt, 1e-9);
        Assert.AreEqual(1 / 48.0, clock.Delay, 1e-9);
    }

    [TestMethod]
    public void GapsAndEarlyFramesAreNotBlended()
    {
        var clock = new FrameGenerationClock(1 / 24.0);
        clock.Arrive(0);
        Assert.IsFalse(clock.Arrive(1.0).Interpolate, "a pause or a seek");
        Assert.IsTrue(clock.Arrive(1.0 + 1 / 24.0).Interpolate);
        Assert.IsFalse(clock.Arrive(1.0 + 1 / 24.0 + 0.005).Interpolate, "a burst after a stall");
    }

    [TestMethod]
    public void TheIntervalFollowsASpeedChange()
    {
        var clock = new FrameGenerationClock(1 / 24.0);
        var now = 0.0;
        clock.Arrive(now);
        for (var frame = 0; frame < 60; frame++)
        {
            now += 1 / 30.0; // 1.25× speed
            clock.Arrive(now);
        }
        Assert.AreEqual(1 / 30.0, clock.Interval, 1e-4);

        clock.Reset(1 / 24.0);
        Assert.AreEqual(1 / 24.0, clock.Interval, 1e-12);
        Assert.IsFalse(clock.Arrive(5).Interpolate);
    }

    // ------------------------------------------------------------------
    // Reference algorithm
    // ------------------------------------------------------------------

    [TestMethod]
    public void MotionSearchFindsATranslation()
    {
        const int width = 128, height = 96;
        var a = Frame(width, height, 0, 0);
        var b = Frame(width, height, 8, -4);
        var field = FrameInterpolation.EstimateMotion(a, b, width, height);
        var blocksX = FrameGenerationRules.Blocks(width);

        // Interior blocks, away from the clamped edges.
        for (var by = 1; by < FrameGenerationRules.Blocks(height) - 1; by++)
        {
            for (var bx = 1; bx < blocksX - 1; bx++)
            {
                var vector = field[by * blocksX + bx];
                Assert.AreEqual(8, vector.X, $"block {bx},{by}");
                Assert.AreEqual(-4, vector.Y, $"block {bx},{by}");
                Assert.IsTrue(vector.Cost < FrameGenerationRules.TrustedCost);
            }
        }
    }

    [TestMethod]
    public void StillPicturesKeepZeroVectors()
    {
        const int width = 64, height = 64;
        var a = Frame(width, height, 0, 0);
        var field = FrameInterpolation.EstimateMotion(a, a, width, height);
        Assert.IsTrue(field.All(vector => vector.X == 0 && vector.Y == 0));
    }

    [TestMethod]
    public void TheGeneratedFrameSitsHalfway()
    {
        const int width = 128, height = 96;
        var a = Frame(width, height, 0, 0);
        var b = Frame(width, height, 8, -4);
        var expected = Frame(width, height, 4, -2);
        var field = FrameInterpolation.Smooth(FrameInterpolation.EstimateMotion(a, b, width, height),
            FrameGenerationRules.Blocks(width), FrameGenerationRules.Blocks(height));
        var middle = FrameInterpolation.Synthesize(a, b, width, height, field);

        double compensatedError = 0, blendError = 0;
        var count = 0;
        for (var y = 24; y < height - 24; y++)
        {
            for (var x = 24; x < width - 24; x++)
            {
                var index = y * width + x;
                compensatedError += Math.Abs(middle[index] - expected[index]);
                blendError += Math.Abs(0.5f * (a[index] + b[index]) - expected[index]);
                count++;
            }
        }
        Assert.IsTrue(compensatedError / count < 0.005, $"error {compensatedError / count}");
        Assert.IsTrue(compensatedError * 10 < blendError, "far closer than a plain blend");
    }

    [TestMethod]
    public void ASceneCutHoldsTheEarlierFrame()
    {
        const int width = 64, height = 64;
        var a = new float[width * height];
        Array.Fill(a, 0.05f);
        var b = new float[width * height];
        Array.Fill(b, 0.95f);
        var field = FrameInterpolation.EstimateMotion(a, b, width, height);
        Assert.IsTrue(FrameInterpolation.IsSceneCut(field));
        CollectionAssert.AreEqual(a, FrameInterpolation.Synthesize(a, b, width, height, field));
    }

    [TestMethod]
    public void PoorMatchesFallBackToABlend()
    {
        Assert.AreEqual(0, FrameInterpolation.FallbackWeight(0.01f));
        Assert.AreEqual(0.5f, FrameInterpolation.FallbackWeight(0.07f), 1e-6);
        Assert.AreEqual(1, FrameInterpolation.FallbackWeight(0.5f));
    }

    [TestMethod]
    public void TheMedianRemovesAStrayVector()
    {
        var field = Enumerable.Repeat(new MotionVector(4, 2, 0.01f), 9).ToArray();
        field[4] = new MotionVector(-30, 28, 0.2f);
        var smoothed = FrameInterpolation.Smooth(field, 3, 3);
        Assert.AreEqual(new MotionVector(4, 2, 0.01f), smoothed[4]);
    }

    [TestMethod]
    public void UpscalingKeepsFlatAreasFlatAndAddsNoHalos()
    {
        const int width = 16, height = 12;
        var flat = new float[width * height];
        Array.Fill(flat, 0.4f);
        var rect = new OutputRect(0, 0, 40, 30);
        var scaled = FrameInterpolation.Scale(flat, width, height, 40, 30, rect);
        Assert.IsTrue(scaled.All(value => Math.Abs(value - 0.4f) < 1e-5));

        // A hard edge: Lanczos would ring; the clamp keeps every value inside the source range.
        var edge = new float[width * height];
        for (var y = 0; y < height; y++) for (var x = width / 2; x < width; x++) edge[y * width + x] = 1;
        var sharp = FrameInterpolation.Scale(edge, width, height, 64, 48, new OutputRect(0, 0, 64, 48));
        Assert.IsTrue(sharp.All(value => value >= 0 && value <= 1));
        Assert.IsTrue(sharp.Any(value => value > 0.05 && value < 0.95), "the edge is resampled, not copied");
    }

    [TestMethod]
    public void LetterboxBarsAreBlackAndShrinkingAverages()
    {
        const int width = 8, height = 8;
        var source = new float[width * height];
        Array.Fill(source, 1f);
        var scaled = FrameInterpolation.Scale(source, width, height, 8, 8, new OutputRect(0, 2, 8, 4));
        Assert.AreEqual(0, scaled[0]);
        Assert.AreEqual(1, scaled[3 * 8 + 3], 1e-5);
        Assert.AreEqual(0, scaled[7 * 8 + 7]);
    }
}
