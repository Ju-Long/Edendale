using System.Globalization;
using System.Text.Json.Serialization;
using Edendale.Windows.Services;

namespace Edendale.Windows.Core;

/// <summary>The five picture adjustments (DIFF.md §3.7), with VLC's own ranges.</summary>
public enum VideoAdjustment
{
    Brightness,
    Contrast,
    Gamma,
    Saturation,
    Hue,
}

public static class VideoAdjustmentRanges
{
    public static IReadOnlyList<VideoAdjustment> All { get; } =
    [
        VideoAdjustment.Brightness,
        VideoAdjustment.Contrast,
        VideoAdjustment.Gamma,
        VideoAdjustment.Saturation,
        VideoAdjustment.Hue,
    ];

    public static double Minimum(this VideoAdjustment adjustment) => adjustment switch
    {
        VideoAdjustment.Gamma => 0.25,
        _ => 0,
    };

    public static double Maximum(this VideoAdjustment adjustment) => adjustment switch
    {
        VideoAdjustment.Brightness or VideoAdjustment.Contrast => 2,
        VideoAdjustment.Gamma or VideoAdjustment.Saturation => 3,
        _ => 360,
    };

    public static double Neutral(this VideoAdjustment adjustment) => adjustment == VideoAdjustment.Hue ? 0 : 1;

    public static double Step(this VideoAdjustment adjustment) => adjustment == VideoAdjustment.Hue ? 5 : 0.05;

    /// <summary>Clamps to the range and snaps to the step; a non-finite value becomes neutral.</summary>
    public static double Normalized(this VideoAdjustment adjustment, double value)
    {
        if (!double.IsFinite(value)) return adjustment.Neutral();
        var bounded = Math.Clamp(value, adjustment.Minimum(), adjustment.Maximum());
        var snapped = Math.Round(Math.Round(bounded / adjustment.Step(), MidpointRounding.AwayFromZero) * adjustment.Step(), 4);
        return Math.Clamp(snapped, adjustment.Minimum(), adjustment.Maximum());
    }

    public static string Title(this VideoAdjustment adjustment) => AppText.Get(adjustment switch
    {
        VideoAdjustment.Brightness => "Picture_Brightness",
        VideoAdjustment.Contrast => "Picture_Contrast",
        VideoAdjustment.Gamma => "Picture_Gamma",
        VideoAdjustment.Saturation => "Picture_Saturation",
        _ => "Picture_Hue",
    });

    /// <summary>"1.05", or "45°" for hue, in the reader's number format.</summary>
    public static string Label(this VideoAdjustment adjustment, double value, CultureInfo? culture = null) =>
        adjustment == VideoAdjustment.Hue
            ? string.Format(culture ?? CultureInfo.CurrentCulture, "{0:0}°", Math.Round(value))
            : string.Format(culture ?? CultureInfo.CurrentCulture, "{0:0.00}", value);
}

/// <summary>Stored as one JSON value, <c>video.adjustments</c>, with Apple's field names.</summary>
public sealed record VideoAdjustmentValues
{
    [JsonPropertyName("brightness")] public double Brightness { get; init; } = 1;
    [JsonPropertyName("contrast")] public double Contrast { get; init; } = 1;
    [JsonPropertyName("gamma")] public double Gamma { get; init; } = 1;
    [JsonPropertyName("saturation")] public double Saturation { get; init; } = 1;
    [JsonPropertyName("hue")] public double Hue { get; init; }

    public static VideoAdjustmentValues Neutral { get; } = new();

    public double this[VideoAdjustment adjustment] => adjustment switch
    {
        VideoAdjustment.Brightness => Brightness,
        VideoAdjustment.Contrast => Contrast,
        VideoAdjustment.Gamma => Gamma,
        VideoAdjustment.Saturation => Saturation,
        _ => Hue,
    };

    /// <summary>A copy with one value replaced (normalized).</summary>
    public VideoAdjustmentValues With(VideoAdjustment adjustment, double value)
    {
        var normalized = adjustment.Normalized(value);
        return adjustment switch
        {
            VideoAdjustment.Brightness => this with { Brightness = normalized },
            VideoAdjustment.Contrast => this with { Contrast = normalized },
            VideoAdjustment.Gamma => this with { Gamma = normalized },
            VideoAdjustment.Saturation => this with { Saturation = normalized },
            _ => this with { Hue = normalized },
        };
    }

    /// <summary>Every value clamped and snapped, as on load.</summary>
    public VideoAdjustmentValues Normalized() => new()
    {
        Brightness = VideoAdjustment.Brightness.Normalized(Brightness),
        Contrast = VideoAdjustment.Contrast.Normalized(Contrast),
        Gamma = VideoAdjustment.Gamma.Normalized(Gamma),
        Saturation = VideoAdjustment.Saturation.Normalized(Saturation),
        Hue = VideoAdjustment.Hue.Normalized(Hue),
    };

    /// <summary>Neutral values switch LibVLC's adjust filter off entirely.</summary>
    [JsonIgnore]
    public bool IsNeutral => this == Neutral;

    /// <summary>
    /// LibVLC's hue runs −180…180, so 0…360 maps by sending h − 360 above 180
    /// (270° becomes −90°, the same rotation).
    /// </summary>
    public static double LibVlcHue(double hue) => hue > 180 ? hue - 360 : hue;
}

/// <summary>
/// Player Adjustments → Picture. Values persist device-locally; Show Original
/// applies neutral values for the moment without touching the stored ones.
/// </summary>
public sealed class VideoAdjustments
{
    public const string StorageKey = "video.adjustments";

    private readonly PlayerSettingsStore _store;

    public VideoAdjustments(PlayerSettingsStore store)
    {
        _store = store;
    }

    /// <summary>The stored values, normalized; neutral when missing or unreadable.</summary>
    public VideoAdjustmentValues Values =>
        (_store.GetObject<VideoAdjustmentValues>(StorageKey) ?? VideoAdjustmentValues.Neutral).Normalized();

    /// <summary>Temporarily shows the unadjusted picture; not persisted.</summary>
    public bool IsShowingOriginal { get; set; }

    /// <summary>What the engine should apply right now.</summary>
    public VideoAdjustmentValues EffectiveValues => IsShowingOriginal ? VideoAdjustmentValues.Neutral : Values;

    public void Set(VideoAdjustment adjustment, double value)
    {
        IsShowingOriginal = false;
        _store.SetObject(StorageKey, Values.With(adjustment, value));
    }

    /// <summary>Moves one value by whole steps (Ctrl+↑/↓ changes brightness this way).</summary>
    public double Step(VideoAdjustment adjustment, int steps)
    {
        var next = adjustment.Normalized(Values[adjustment] + steps * adjustment.Step());
        Set(adjustment, next);
        return next;
    }

    public void Reset()
    {
        IsShowingOriginal = false;
        _store.SetObject(StorageKey, VideoAdjustmentValues.Neutral);
    }
}
