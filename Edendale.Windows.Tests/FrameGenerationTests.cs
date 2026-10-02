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

    /// <summary>
    /// A smooth, fairly low-contrast shot like real footage (and the test
    /// clip), in limited-range luma as the GPU reads it. Two seeds look alike
    /// but are different shots: the cut a mean-cost threshold alone missed.
    /// </summary>
    private static float[] Scene(int width, int height, int seed, int shiftX = 0, int shiftY = 0)
    {
        static float Lattice(int x, int y, int seed)
        {
            var hash = (uint)(x * 374761393 + y * 668265263 + seed * 1442695041);
            hash = (hash ^ (hash >> 13)) * 1274126177;
            return ((hash ^ (hash >> 16)) & 0xFFFF) / 65535f;
        }

        static float Noise(float x, float y, int seed)
        {
            var ix = (int)MathF.Floor(x);
            var iy = (int)MathF.Floor(y);
            var tx = x - ix;
            var ty = y - iy;
            tx = tx * tx * (3 - 2 * tx);
            ty = ty * ty * (3 - 2 * ty);
            var top = Lattice(ix, iy, seed) + (Lattice(ix + 1, iy, seed) - Lattice(ix, iy, seed)) * tx;
            var bottom = Lattice(ix, iy + 1, seed) + (Lattice(ix + 1, iy + 1, seed) - Lattice(ix, iy + 1, seed)) * tx;
            return top + (bottom - top) * ty;
        }

        var plane = new float[width * height];
        for (var y = 0; y < height; y++)
        {
            for (var x = 0; x < width; x++)
            {
                float u = x - shiftX, v = y - shiftY;
                var luma = 0.15f + 0.6f * Noise(u / 41, v / 41, seed) + 0.2f * Noise(u / 13, v / 13, seed + 1) + 0.1f * Noise(u / 4, v / 4, seed + 2);
                plane[y * width + x] = (16 + 219 * Math.Clamp(luma, 0, 1)) / 255;
            }
        }
        return plane;
    }

    /// <summary>The statistics the generators read back for a pair: motion search, median, then the field's costs.</summary>
    private static FieldStatistics Statistics(float[] a, float[] b, int width, int height) =>
        FrameInterpolation.Statistics(FrameInterpolation.Smooth(FrameInterpolation.EstimateMotion(a, b, width, height),
            FrameGenerationRules.Blocks(width), FrameGenerationRules.Blocks(height)));

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
        // DX10 and DXA0 are hardware-decoded 10-bit (D3D11 and DXVA2), how HDR10 usually arrives.
        foreach (var chroma in new[] { "P010", "I0AL", "I0AB", "P016", "v210", "I4AL\0", "DX10", "DXA0", "RGA4", "GBFL" })
        {
            Assert.IsTrue(FrameGenerationRules.IsHighBitDepth(chroma), chroma);
        }
        foreach (var chroma in new[] { "NV12", "I420", "YV12", "J420", "RV32", "DX11", "DXA9", "", null })
        {
            Assert.IsFalse(FrameGenerationRules.IsHighBitDepth(chroma), chroma ?? "null");
        }
    }

    [TestMethod]
    public void TheVisiblePictureIsAskedForWithoutCodecPadding()
    {
        // 1080p H.264 is coded 1088 lines high (1090 in software), and a DXVA HEVC surface aligns to 128.
        Assert.AreEqual((1920, 1080, false), FrameGenerationRules.PictureSize(1920, 1088, 1920, 1080));
        Assert.AreEqual((1920, 1080, false), FrameGenerationRules.PictureSize(1920, 1090, 1920, 1080));
        Assert.AreEqual((1920, 1080, false), FrameGenerationRules.PictureSize(1920, 1152, 1920, 1080));
        Assert.AreEqual((1280, 720, false), FrameGenerationRules.PictureSize(1280, 720, 1280, 720));
        // A phone video turned upright.
        Assert.AreEqual((1080, 1920, true), FrameGenerationRules.PictureSize(1088, 1920, 1920, 1080));
        // An unknown size, or one that doesn't fit the offer, keeps the offer.
        Assert.AreEqual((1920, 1088, false), FrameGenerationRules.PictureSize(1920, 1088, 0, 0));
        Assert.AreEqual((1920, 1088, false), FrameGenerationRules.PictureSize(1920, 1088, 1280, 720));
        Assert.AreEqual((1280, 720, false), FrameGenerationRules.PictureSize(1280, 720, 1920, 1080));
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
    public void ACutBetweenLookalikeShotsIsACut()
    {
        const int width = 320, height = 192;
        var panA = Statistics(Scene(width, height, 1), Scene(width, height, 1, -8, -2), width, height);
        var cut = Statistics(Scene(width, height, 1, -8, -2), Scene(width, height, 7), width, height);
        var panB = Statistics(Scene(width, height, 7), Scene(width, height, 7, 6, 0), width, height);

        // The block search still finds passable matches across the cut, so
        // the mean cost alone stays far below the kernels' backstop.
        Assert.IsTrue(cut.MeanCost < FrameGenerationRules.SceneCutCost, $"mean {cut.MeanCost}");

        var cuts = new SceneCutDetector();
        cuts.Advance();
        bool Next(FieldStatistics pair)
        {
            cuts.Advance();
            return cuts.IsCut(pair);
        }
        Assert.IsFalse(Next(panA), $"a steady pan: {panA}");
        Assert.IsTrue(Next(cut), $"the cut: {cut}");
        Assert.IsFalse(Next(panB), $"the new shot pans on: {panB}");
    }

    [TestMethod]
    public void ASteadilyGrainyShotIsNotACut()
    {
        const int width = 320, height = 192;
        var random = new Random(5);
        float[] Grainy(int shiftX)
        {
            var plane = Scene(width, height, 3, shiftX);
            for (var i = 0; i < plane.Length; i++) plane[i] = Math.Clamp(plane[i] + (float)(random.NextDouble() - 0.5) * 0.12f, 0, 1);
            return plane;
        }
        var frames = Enumerable.Range(0, 5).Select(index => Grainy(-4 * index)).ToArray();
        var pairs = Enumerable.Range(0, 4).Select(index => Statistics(frames[index], frames[index + 1], width, height)).ToArray();
        Assert.IsTrue(pairs.All(pair => pair.PoorShare > FrameGenerationRules.CutPoorShare), "grain makes most blocks match poorly");

        var cuts = new SceneCutDetector();
        cuts.Advance();
        cuts.Advance();
        cuts.IsCut(pairs[0]); // the first pair has nothing to compare with
        foreach (var pair in pairs.Skip(1))
        {
            cuts.Advance();
            Assert.IsFalse(cuts.IsCut(pair), $"{pair}");
        }
    }

    [TestMethod]
    public void MatchingThisBadlyIsACutWhateverCameBefore()
    {
        const int width = 128, height = 96;
        var a = Frame(width, height, 0, 0);
        var hopeless = Statistics(a, [.. a.Select(value => 1 - value)], width, height);
        Assert.IsTrue(hopeless.MeanCost > FrameGenerationRules.CertainCutCost, $"{hopeless}");
        Assert.IsTrue(FrameGenerationRules.IsSceneCut(hopeless, previousMeanCost: hopeless.MeanCost), "even after an equally hopeless pair");
    }

    [TestMethod]
    public void ACutNeedsMostBlocksPoorAndAJump()
    {
        var cut = new FieldStatistics(0.037f, 0.8f);
        Assert.IsTrue(FrameGenerationRules.IsSceneCut(cut, previousMeanCost: 0.002f));
        Assert.IsFalse(FrameGenerationRules.IsSceneCut(cut, previousMeanCost: 0.03f), "no jump: a steadily hard shot");
        Assert.IsFalse(FrameGenerationRules.IsSceneCut(new FieldStatistics(0.037f, 0.4f), previousMeanCost: 0.002f), "most blocks still match");
        Assert.IsTrue(FrameGenerationRules.IsSceneCut(cut, previousMeanCost: null), "nothing to compare with");
        Assert.IsTrue(FrameGenerationRules.IsSceneCut(new FieldStatistics(0.09f, 0.3f), previousMeanCost: 0.09f), "matching fails almost everywhere");
    }

    [TestMethod]
    public void AGapLeavesNothingToCompareWith()
    {
        var grainy = new FieldStatistics(0.035f, 0.9f);
        var cuts = new SceneCutDetector();
        cuts.Advance();
        cuts.Advance();
        cuts.IsCut(grainy);
        cuts.Advance();
        Assert.IsFalse(cuts.IsCut(grainy), "compared with the pair before");

        cuts.Advance(); // a frame whose pair isn't compared: a seek, or fast playback
        cuts.Advance();
        Assert.IsTrue(cuts.IsCut(grainy), "nothing to compare with, so the poor share decides");

        cuts.Reset();
        cuts.Advance();
        cuts.Advance();
        Assert.IsTrue(cuts.IsCut(grainy), "a new stream starts over");
    }

    [TestMethod]
    public void StatisticsCountPoorMatches()
    {
        var statistics = FrameInterpolation.Statistics([0.01f, 0.02f, 0.03f, 0.10f]);
        Assert.AreEqual(0.04f, statistics.MeanCost, 1e-6);
        Assert.AreEqual(0.5f, statistics.PoorShare, 1e-6);
        Assert.AreEqual(new FieldStatistics(0, 0), FrameInterpolation.Statistics(ReadOnlySpan<float>.Empty));
    }

    [TestMethod]
    public void NothingMatchingIsNeverBlended()
    {
        const int width = 64, height = 64;
        var a = new float[width * height];
        Array.Fill(a, 0.05f);
        var b = new float[width * height];
        Array.Fill(b, 0.95f);
        var field = FrameInterpolation.EstimateMotion(a, b, width, height);
        Assert.IsTrue(FrameInterpolation.MeanCost(field) > FrameGenerationRules.SceneCutCost);
        CollectionAssert.AreEqual(a, FrameInterpolation.Synthesize(a, b, width, height, field), "the kernels' backstop holds the earlier frame");
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
