using System.Globalization;
using System.IO;
using System.Runtime.InteropServices;
using Edendale.Windows.Core;
using Edendale.Windows.Services;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Edendale.Windows.Tests;

/// <summary>
/// Option A video enhancement (ENHANCEMENT.md E.1–E.2): the capability model
/// for each vendor and architecture, and the LibVLC argument builder with
/// every fallback. Windows-only tests with no Apple counterpart.
/// </summary>
[TestClass]
public sealed class GpuCapabilitiesTests
{
    private static GpuAdapterInfo Adapter(uint vendor, Architecture arch = Architecture.X64, bool amf = false,
        int[]? driver = null, bool software = false, uint device = 0x1234, (int, int, int)? amfVersion = null,
        bool hybrid = false) => new()
        {
            VendorId = vendor,
            DeviceId = device,
            ProcessArchitecture = arch,
            AmfAvailable = amf,
            DriverVersion = driver,
            IsSoftware = software,
            AmfVersion = amfVersion,
            DedicatedGpuNotDefault = hybrid,
        };

    [TestMethod]
    public void NvidiaSuperResolutionFollowsVlcsDriverCheck()
    {
        Assert.AreEqual(153118, 15 * 10_000 + 3118);
        Assert.IsTrue(GpuCapabilities.NvidiaDriverSupportsSuperResolution([31, 0, 15, 3118]));
        Assert.IsFalse(GpuCapabilities.NvidiaDriverSupportsSuperResolution([31, 0, 15, 3000]));
        Assert.IsFalse(GpuCapabilities.NvidiaDriverSupportsSuperResolution([30, 0, 14, 9999]));
        Assert.IsFalse(GpuCapabilities.NvidiaDriverSupportsSuperResolution(null));

        var recent = GpuCapabilities.Evaluate(Adapter(0x10DE, driver: [32, 0, 15, 6094]));
        Assert.AreEqual(GpuVendor.Nvidia, recent.Vendor);
        Assert.AreEqual(CapabilitySupport.Yes, recent.SuperResolution);
        Assert.IsTrue(recent.VideoProcessor);
        Assert.IsFalse(recent.MotionSmoothing);
        Assert.IsFalse(recent.GpuDenoise);

        var old = GpuCapabilities.Evaluate(Adapter(0x10DE, driver: [27, 21, 14, 5671]));
        Assert.AreEqual(CapabilitySupport.No, old.SuperResolution);
        Assert.IsTrue(old.VideoProcessor);
    }

    [TestMethod]
    public void IntelIsAlwaysMaybe()
    {
        var intel = GpuCapabilities.Evaluate(Adapter(0x8086, driver: [31, 0, 101, 4255]));
        Assert.AreEqual(GpuVendor.Intel, intel.Vendor);
        Assert.AreEqual(CapabilitySupport.Maybe, intel.SuperResolution);
        Assert.IsFalse(intel.MotionSmoothing);
    }

    [TestMethod]
    public void AmdFeaturesNeedX64AndTheAmfRuntime()
    {
        var x64 = GpuCapabilities.Evaluate(Adapter(0x1002, amf: true, amfVersion: (1, 4, 35)));
        Assert.AreEqual(CapabilitySupport.Yes, x64.SuperResolution);
        Assert.IsTrue(x64.MotionSmoothing);
        Assert.IsTrue(x64.GpuDenoise);

        var noRuntime = GpuCapabilities.Evaluate(Adapter(0x1002, amf: false));
        Assert.AreEqual(CapabilitySupport.No, noRuntime.SuperResolution);
        Assert.IsFalse(noRuntime.MotionSmoothing);
        Assert.IsTrue(noRuntime.VideoProcessor);

        foreach (var arch in new[] { Architecture.Arm64, Architecture.X86 })
        {
            var other = GpuCapabilities.Evaluate(Adapter(0x1002, arch, amf: true));
            Assert.AreEqual(CapabilitySupport.No, other.SuperResolution, arch.ToString());
            Assert.IsFalse(other.MotionSmoothing, arch.ToString());
            Assert.IsFalse(other.GpuDenoise, arch.ToString());
        }
    }

    [TestMethod]
    public void AnOldAmfRuntimeDropsOnlyTheDoubler()
    {
        var old = GpuCapabilities.Evaluate(Adapter(0x1002, amf: true, amfVersion: (1, 4, 33)));
        Assert.IsFalse(old.MotionSmoothing);
        Assert.IsTrue(old.GpuDenoise);
        Assert.AreEqual(CapabilitySupport.Yes, old.SuperResolution);
    }

    [TestMethod]
    public void SnapdragonGetsTheVideoProcessorOnly()
    {
        var arm = GpuCapabilities.Evaluate(Adapter(0x5143, Architecture.Arm64));
        Assert.AreEqual(GpuVendor.Qualcomm, arm.Vendor);
        Assert.AreEqual(CapabilitySupport.No, arm.SuperResolution);
        Assert.IsTrue(arm.VideoProcessor);
        Assert.IsFalse(arm.MotionSmoothing);
    }

    [TestMethod]
    public void TheSoftwareAdapterTurnsEverythingOff()
    {
        foreach (var adapter in new[]
        {
            Adapter(0x1414, device: 0x8C),
            Adapter(0x10DE, software: true, driver: [32, 0, 15, 6094]),
        })
        {
            var capabilities = GpuCapabilities.Evaluate(adapter);
            Assert.IsTrue(capabilities.IsSoftwareAdapter);
            Assert.AreEqual(CapabilitySupport.No, capabilities.SuperResolution);
            Assert.IsFalse(capabilities.VideoProcessor);
            Assert.IsFalse(capabilities.MotionSmoothing);
            Assert.IsFalse(capabilities.GpuDenoise);
        }
    }

    [TestMethod]
    public void HybridLaptopsAreFlagged()
    {
        Assert.IsTrue(GpuCapabilities.Evaluate(Adapter(0x8086, hybrid: true)).RunningOnIntegratedGpu);
        Assert.IsFalse(GpuCapabilities.Evaluate(Adapter(0x10DE)).RunningOnIntegratedGpu);
    }
}

[TestClass]
public sealed class VideoEnhancementOptionsTests
{
    private static readonly GpuCapabilities Nvidia = new()
    {
        Vendor = GpuVendor.Nvidia,
        SuperResolution = CapabilitySupport.Yes,
        VideoProcessor = true,
    };

    private static readonly GpuCapabilities NvidiaOldDriver = Nvidia with { SuperResolution = CapabilitySupport.No };

    private static readonly GpuCapabilities Intel = new()
    {
        Vendor = GpuVendor.Intel,
        SuperResolution = CapabilitySupport.Maybe,
        VideoProcessor = true,
    };

    private static readonly GpuCapabilities Amd = new()
    {
        Vendor = GpuVendor.Amd,
        SuperResolution = CapabilitySupport.Yes,
        VideoProcessor = true,
        MotionSmoothing = true,
        GpuDenoise = true,
    };

    private static readonly GpuCapabilities Software = new() { Vendor = GpuVendor.Software };

    private static readonly VideoSourceInfo Film720 = new(1280, 720, 23.976);

    private static string[] Args(VideoEnhancementPreset preset, GpuCapabilities capabilities, bool smoothing = false,
        VideoSourceInfo? source = null, bool indicator = false) =>
        VideoEnhancementOptions.Build(preset, smoothing, onBattery: false, capabilities, source ?? Film720,
            frameRateIndicator: indicator).Arguments.ToArray();

    [TestMethod]
    public void OffIsBilinearEverywhere()
    {
        foreach (var capabilities in new[] { Nvidia, Intel, Amd, Software })
        {
            CollectionAssert.AreEqual(new[] { "--d3d11-upscale-mode=linear" }, Args(VideoEnhancementPreset.Off, capabilities));
        }
    }

    [TestMethod]
    public void MotionSmoothingIsIndependentOfThePreset()
    {
        // As on Apple, the toggle sits beside the preset rather than inside it.
        CollectionAssert.AreEqual(
            new[] { "--d3d11-upscale-mode=linear", "--video-filter=amf_frc" },
            Args(VideoEnhancementPreset.Off, Amd, smoothing: true));
        CollectionAssert.AreEqual(new[] { "--d3d11-upscale-mode=linear" }, Args(VideoEnhancementPreset.Off, Software, smoothing: true));
    }

    [TestMethod]
    public void BalancedUsesSuperResolutionWhenSupportedOrMaybe()
    {
        CollectionAssert.AreEqual(new[] { "--d3d11-upscale-mode=super" }, Args(VideoEnhancementPreset.Balanced, Nvidia));
        CollectionAssert.AreEqual(new[] { "--d3d11-upscale-mode=super" }, Args(VideoEnhancementPreset.Balanced, Intel));
        CollectionAssert.AreEqual(new[] { "--d3d11-upscale-mode=super" }, Args(VideoEnhancementPreset.Balanced, Amd));
    }

    [TestMethod]
    public void BalancedFallsBackToTheVideoProcessorThenBilinear()
    {
        CollectionAssert.AreEqual(new[] { "--d3d11-upscale-mode=processor" }, Args(VideoEnhancementPreset.Balanced, NvidiaOldDriver));
        CollectionAssert.AreEqual(new[] { "--d3d11-upscale-mode=linear" }, Args(VideoEnhancementPreset.Balanced, Software));
        CollectionAssert.AreEqual(new[] { "--d3d11-upscale-mode=linear" }, Args(VideoEnhancementPreset.Balanced, GpuCapabilities.None));
    }

    [TestMethod]
    public void HighQualityAddsTheAmdDenoiserOrRunsAsBalanced()
    {
        CollectionAssert.AreEqual(
            new[] { "--d3d11-upscale-mode=super", "--video-filter=amf_vqenhancer" },
            Args(VideoEnhancementPreset.HighQuality, Amd));

        var nvidia = VideoEnhancementOptions.Build(VideoEnhancementPreset.HighQuality, false, false, Nvidia, Film720);
        Assert.AreEqual(VideoEnhancementPreset.Balanced, nvidia.EffectivePreset);
        CollectionAssert.AreEqual(new[] { "--d3d11-upscale-mode=super" }, nvidia.Arguments.ToArray());
        Assert.IsFalse(nvidia.ShowHighQuality);
        Assert.IsTrue(VideoEnhancementOptions.Build(VideoEnhancementPreset.Balanced, false, false, Amd, Film720).ShowHighQuality);
    }

    [TestMethod]
    public void MotionSmoothingAddsTheDoublerInFilterOrder()
    {
        CollectionAssert.AreEqual(
            new[] { "--d3d11-upscale-mode=super", "--video-filter=amf_vqenhancer:amf_frc" },
            Args(VideoEnhancementPreset.HighQuality, Amd, smoothing: true));
        CollectionAssert.AreEqual(
            new[] { "--d3d11-upscale-mode=super", "--video-filter=amf_frc" },
            Args(VideoEnhancementPreset.Balanced, Amd, smoothing: true));
    }

    [TestMethod]
    public void MotionSmoothingStopsAboveThirtyFramesAndForUnknownRates()
    {
        Assert.IsTrue(Args(VideoEnhancementPreset.Balanced, Amd, true, new VideoSourceInfo(1920, 1080, 30)).Contains("--video-filter=amf_frc"));
        Assert.IsTrue(Args(VideoEnhancementPreset.Balanced, Amd, true, new VideoSourceInfo(1920, 1080, 29.97)).Contains("--video-filter=amf_frc"));
        Assert.IsFalse(Args(VideoEnhancementPreset.Balanced, Amd, true, new VideoSourceInfo(1920, 1080, 59.94)).Any(a => a.Contains("amf_frc")));
        Assert.IsFalse(Args(VideoEnhancementPreset.Balanced, Amd, true, new VideoSourceInfo(1920, 1080, null)).Any(a => a.Contains("amf_frc")));

        Assert.IsFalse(VideoEnhancementOptions.Build(VideoEnhancementPreset.Balanced, false, false, Amd, new VideoSourceInfo(1920, 1080, 60)).ShowMotionSmoothing);
        Assert.IsTrue(VideoEnhancementOptions.Build(VideoEnhancementPreset.Balanced, false, false, Amd, Film720).ShowMotionSmoothing);
        Assert.IsFalse(VideoEnhancementOptions.Build(VideoEnhancementPreset.Balanced, false, false, Nvidia, Film720).ShowMotionSmoothing);
    }

    [TestMethod]
    public void MotionSmoothingNeedsAmd()
    {
        Assert.IsFalse(Args(VideoEnhancementPreset.Balanced, Nvidia, smoothing: true).Any(a => a.Contains("amf_frc")));
        Assert.IsFalse(Args(VideoEnhancementPreset.Balanced, Intel, smoothing: true).Any(a => a.Contains("amf_frc")));
    }

    [TestMethod]
    public void DebugBuildsAddTheDoublerIndicatorOnlyWhileItRuns()
    {
        CollectionAssert.Contains(Args(VideoEnhancementPreset.Balanced, Amd, smoothing: true, indicator: true), "--frc-indicator");
        CollectionAssert.DoesNotContain(Args(VideoEnhancementPreset.Balanced, Amd, smoothing: false, indicator: true), "--frc-indicator");
    }

    [TestMethod]
    public void TheSameInputsAlwaysGiveTheSameList()
    {
        var first = Args(VideoEnhancementPreset.HighQuality, Amd, smoothing: true);
        var second = Args(VideoEnhancementPreset.HighQuality, Amd, smoothing: true);
        CollectionAssert.AreEqual(first, second);
    }

    [TestMethod]
    public void BatteryDoesNotChangeTheResult()
    {
        var plugged = VideoEnhancementOptions.Build(VideoEnhancementPreset.HighQuality, true, onBattery: false, Amd, Film720);
        var battery = VideoEnhancementOptions.Build(VideoEnhancementPreset.HighQuality, true, onBattery: true, Amd, Film720);
        CollectionAssert.AreEqual(plugged.Arguments.ToArray(), battery.Arguments.ToArray());
    }

    [TestMethod]
    public void LabelsDescribeWhatRuns()
    {
        var culture = CultureInfo.InvariantCulture;
        var result = VideoEnhancementOptions.Build(VideoEnhancementPreset.Balanced, true, false, Amd,
            new VideoSourceInfo(1280, 720, 24), displayWidth: 3840, displayHeight: 2160, culture: culture);
        Assert.AreEqual("1280×720 → 3840×2160", result.ResolutionLabel);
        Assert.AreEqual("24 fps → 48 fps", result.FrameRateLabel);

        var film = VideoEnhancementOptions.Build(VideoEnhancementPreset.Balanced, true, false, Amd,
            Film720, displayWidth: 1920, displayHeight: 1080, culture: culture);
        Assert.AreEqual("23.976 fps → 47.952 fps", film.FrameRateLabel);
    }

    [TestMethod]
    public void NoResolutionLabelWhenTheSourceAlreadyFillsTheArea()
    {
        var culture = CultureInfo.InvariantCulture;
        Assert.IsNull(VideoEnhancementOptions.Build(VideoEnhancementPreset.Balanced, false, false, Nvidia,
            new VideoSourceInfo(3840, 2160, 24), 1920, 1080, culture: culture).ResolutionLabel);
        Assert.IsNull(VideoEnhancementOptions.Build(VideoEnhancementPreset.Balanced, false, false, NvidiaOldDriver,
            Film720, 3840, 2160, culture: culture).ResolutionLabel, "the video processor isn't super resolution");
        Assert.IsNull(VideoEnhancementOptions.Build(VideoEnhancementPreset.Off, false, false, Nvidia,
            Film720, 3840, 2160, culture: culture).ResolutionLabel);
    }

    [TestMethod]
    public void FittedSizeKeepsTheAspectRatio()
    {
        Assert.AreEqual((2560, 1071), VideoEnhancementOptions.FittedSize(1920, 803, 2560, 1440));
        Assert.AreEqual((1440, 1080), VideoEnhancementOptions.FittedSize(640, 480, 1920, 1080));
        Assert.AreEqual((0, 0), VideoEnhancementOptions.FittedSize(0, 0, 1920, 1080));
    }

    [TestMethod]
    public void SettingsPersistAndShowOriginalIsTemporary()
    {
        var directory = Path.Combine(Path.GetTempPath(), $"eden-enhancement-{Guid.NewGuid():N}");
        Directory.CreateDirectory(directory);
        try
        {
            var path = Path.Combine(directory, "player-settings.json");
            var settings = new VideoEnhancementSettings(new PlayerSettingsStore(path));
            Assert.AreEqual(VideoEnhancementPreset.Balanced, settings.Preset);
            Assert.IsFalse(settings.MotionSmoothing);

            settings.Preset = VideoEnhancementPreset.HighQuality;
            settings.MotionSmoothing = true;
            settings.IsShowingOriginal = true;
            Assert.AreEqual(VideoEnhancementPreset.Off, settings.EffectivePreset);

            var restored = new VideoEnhancementSettings(new PlayerSettingsStore(path));
            Assert.AreEqual(VideoEnhancementPreset.HighQuality, restored.Preset);
            Assert.IsTrue(restored.MotionSmoothing);
            Assert.IsFalse(restored.IsShowingOriginal);
            Assert.AreEqual("highQuality", new PlayerSettingsStore(path).GetString("video.enhancementPreset"));

            new PlayerSettingsStore(path).SetString("video.enhancementPreset", "sharpenOnly");
            Assert.AreEqual(VideoEnhancementPreset.Balanced, new VideoEnhancementSettings(new PlayerSettingsStore(path)).Preset);
        }
        finally
        {
            Directory.Delete(directory, recursive: true);
        }
    }
}
