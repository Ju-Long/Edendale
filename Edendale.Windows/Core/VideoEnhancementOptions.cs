using System.Globalization;
using Edendale.Windows.Services;

namespace Edendale.Windows.Core;

/// <summary>
/// Apple's preset names, limited to what LibVLC 3 can do (decision D1):
/// Sharpen Only and the Sharpness and Denoise sliders have no Option A
/// equivalent, so Windows offers Off, Balanced, and — where an AMD x64
/// denoiser exists — High Quality.
/// </summary>
public enum VideoEnhancementPreset
{
    Off,
    Balanced,
    HighQuality,
}

/// <summary>What the player knows about the source before (or after) opening it.</summary>
public readonly record struct VideoSourceInfo(int Width, int Height, double? FrameRate)
{
    public static VideoSourceInfo Unknown => new(0, 0, null);
}

/// <summary>LibVLC arguments plus the labels and controls Player Adjustments shows.</summary>
public sealed record VideoEnhancementResult
{
    /// <summary>Stable for equal inputs, so the player can compare lists to decide on a reopen.</summary>
    public required IReadOnlyList<string> Arguments { get; init; }

    /// <summary>The preset after fallbacks (High Quality without a denoiser runs as Balanced).</summary>
    public required VideoEnhancementPreset EffectivePreset { get; init; }

    public required string UpscaleMode { get; init; }
    public bool UsesSuperResolution => UpscaleMode == "super";
    public bool DenoiseApplied { get; init; }
    public bool MotionSmoothingApplied { get; init; }

    /// <summary>"1280×720 → 3840×2160" when super resolution enlarges the picture; else null.</summary>
    public string? ResolutionLabel { get; init; }

    /// <summary>"24 fps → 48 fps" while Motion Smoothing runs; else null.</summary>
    public string? FrameRateLabel { get; init; }

    public bool ShowHighQuality { get; init; }

    /// <summary>The toggle shows only where AMD's doubler exists and the source runs at 30 fps or less.</summary>
    public bool ShowMotionSmoothing { get; init; }
}

/// <summary>
/// ENHANCEMENT.md E.2: one pure function from settings, capabilities, and
/// source to LibVLC arguments, so every fallback is unit-tested.
/// </summary>
public static class VideoEnhancementOptions
{
    public const string PresetKey = "video.enhancementPreset";
    public const string MotionSmoothingKey = "video.motionSmoothing";
    public const VideoEnhancementPreset DefaultPreset = VideoEnhancementPreset.Balanced;

    /// <summary>Apple's limit: frame doubling is for 30 fps and slower sources.</summary>
    public const double MotionSmoothingMaximumFrameRate = 30.0;

    public static string RawValue(VideoEnhancementPreset preset) => preset switch
    {
        VideoEnhancementPreset.Off => "off",
        VideoEnhancementPreset.HighQuality => "highQuality",
        _ => "balanced",
    };

    public static VideoEnhancementPreset? FromRawValue(string? raw) => raw switch
    {
        "off" => VideoEnhancementPreset.Off,
        "balanced" => VideoEnhancementPreset.Balanced,
        "highQuality" => VideoEnhancementPreset.HighQuality,
        _ => null,
    };

    public static string DisplayName(VideoEnhancementPreset preset) => AppText.Get(preset switch
    {
        VideoEnhancementPreset.Off => "Enhancement_PresetOff",
        VideoEnhancementPreset.HighQuality => "Enhancement_PresetHighQuality",
        _ => "Enhancement_PresetBalanced",
    });

    /// <summary>
    /// Builds the arguments. <paramref name="onBattery"/> is accepted but does
    /// not change the result: enhancement applies on battery too, as on Apple
    /// (decision D4). <paramref name="displayWidth"/> and
    /// <paramref name="displayHeight"/> are the video area in physical pixels,
    /// used only for the resolution label.
    /// </summary>
    public static VideoEnhancementResult Build(
        VideoEnhancementPreset preset,
        bool motionSmoothing,
        bool onBattery,
        GpuCapabilities capabilities,
        VideoSourceInfo source,
        int displayWidth = 0,
        int displayHeight = 0,
        bool frameRateIndicator = false,
        CultureInfo? culture = null)
    {
        _ = onBattery;
        var effective = preset == VideoEnhancementPreset.HighQuality && !capabilities.GpuDenoise
            ? VideoEnhancementPreset.Balanced
            : preset;

        var upscale = effective == VideoEnhancementPreset.Off || capabilities.IsSoftwareAdapter
            ? "linear"
            : capabilities.SuperResolution != CapabilitySupport.No
                ? "super"
                : capabilities.VideoProcessor ? "processor" : "linear";

        var denoise = effective == VideoEnhancementPreset.HighQuality && capabilities.GpuDenoise;
        var smoothingAvailable = capabilities.MotionSmoothing
            && source.FrameRate is double rate && rate > 0 && rate <= MotionSmoothingMaximumFrameRate + 0.001;
        var smoothing = motionSmoothing && smoothingAvailable && !capabilities.IsSoftwareAdapter;

        var arguments = new List<string> { $"--d3d11-upscale-mode={upscale}" };
        var filters = new List<string>();
        if (denoise) filters.Add("amf_vqenhancer");
        if (smoothing) filters.Add("amf_frc");
        if (filters.Count > 0) arguments.Add($"--video-filter={string.Join(':', filters)}");
        if (smoothing && frameRateIndicator) arguments.Add("--frc-indicator");

        return new VideoEnhancementResult
        {
            Arguments = arguments,
            EffectivePreset = effective,
            UpscaleMode = upscale,
            DenoiseApplied = denoise,
            MotionSmoothingApplied = smoothing,
            ResolutionLabel = upscale == "super" ? ResolutionLabel(source, displayWidth, displayHeight, culture) : null,
            FrameRateLabel = smoothing ? FrameRateLabel(source.FrameRate!.Value, culture) : null,
            ShowHighQuality = capabilities.GpuDenoise,
            ShowMotionSmoothing = smoothingAvailable,
        };
    }

    /// <summary>The source fitted into the display area, uniformly scaled.</summary>
    public static (int Width, int Height) FittedSize(int sourceWidth, int sourceHeight, int displayWidth, int displayHeight)
    {
        if (sourceWidth <= 0 || sourceHeight <= 0 || displayWidth <= 0 || displayHeight <= 0) return (0, 0);
        var scale = Math.Min((double)displayWidth / sourceWidth, (double)displayHeight / sourceHeight);
        return ((int)Math.Round(sourceWidth * scale), (int)Math.Round(sourceHeight * scale));
    }

    /// <summary>Only when the source is smaller than the fitted area does super resolution do anything.</summary>
    public static string? ResolutionLabel(VideoSourceInfo source, int displayWidth, int displayHeight, CultureInfo? culture = null)
    {
        var (width, height) = FittedSize(source.Width, source.Height, displayWidth, displayHeight);
        if (width <= source.Width || height <= source.Height) return null;
        var format = culture ?? CultureInfo.CurrentCulture;
        return string.Create(format, $"{source.Width}×{source.Height} → {width}×{height}");
    }

    public static string FrameRateLabel(double frameRate, CultureInfo? culture = null)
    {
        var format = culture ?? CultureInfo.CurrentCulture;
        return string.Format(format, "{0:0.###} fps → {1:0.###} fps", frameRate, frameRate * 2);
    }
}

/// <summary>Player Adjustments → Enhancement choices, persisted device-locally (decision D5).</summary>
public sealed class VideoEnhancementSettings
{
    private readonly PlayerSettingsStore _store;

    public VideoEnhancementSettings(PlayerSettingsStore store)
    {
        _store = store;
    }

    public VideoEnhancementPreset Preset
    {
        get => VideoEnhancementOptions.FromRawValue(_store.GetString(VideoEnhancementOptions.PresetKey)) ?? VideoEnhancementOptions.DefaultPreset;
        set => _store.SetString(VideoEnhancementOptions.PresetKey, VideoEnhancementOptions.RawValue(value));
    }

    public bool MotionSmoothing
    {
        get => _store.GetBool(VideoEnhancementOptions.MotionSmoothingKey, fallback: false);
        set => _store.SetBool(VideoEnhancementOptions.MotionSmoothingKey, value);
    }

    /// <summary>Show Original (decision D2): plays unenhanced for the moment, not stored.</summary>
    public bool IsShowingOriginal { get; set; }

    /// <summary>The preset the engine should run with right now.</summary>
    public VideoEnhancementPreset EffectivePreset => IsShowingOriginal ? VideoEnhancementPreset.Off : Preset;
}
