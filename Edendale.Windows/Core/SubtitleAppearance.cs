using System.Globalization;
using Edendale.Windows.Services;

namespace Edendale.Windows.Core;

/// <summary>
/// The subtitle typeface. Windows offers three of Apple's four families:
/// LibVLC 3 resolves fonts only among the families installed in Windows, and
/// Windows ships no rounded family, so Rounded is left out (decision D8).
/// </summary>
public enum SubtitleFontStyle
{
    System,
    Serif,
    Monospaced,
}

public enum SubtitleTextColor
{
    /// <summary>The archive's parchment (TextPrimary), the default look.</summary>
    Parchment,
    White,
    Yellow,
    Cyan,
    Green,
    Black,
}

public enum SubtitleBackgroundColor
{
    /// <summary>The archive's ink (Background), the default look.</summary>
    Ink,
    Black,
    Charcoal,
    Navy,
    White,
}

/// <summary>
/// Settings → Subtitles (DIFF.md §3.8): named presets rather than a color
/// picker. LibVLC 3 draws text subtitles itself, so the choices become
/// freetype arguments on the LibVLC instance (<see cref="LibVlcArguments"/>),
/// applied at the next playback or through a reopen at the same position.
/// Bitmap subtitles (PGS, VobSub) keep their authored pixels.
/// </summary>
public sealed class SubtitleAppearance
{
    public const string FontKey = "subtitles.font";
    public const string TextColorKey = "subtitles.textColor";
    public const string BackgroundColorKey = "subtitles.backgroundColor";
    public const string BackgroundOpacityKey = "subtitles.backgroundOpacity";

    public const SubtitleFontStyle DefaultFont = SubtitleFontStyle.System;
    public const SubtitleTextColor DefaultTextColor = SubtitleTextColor.Parchment;
    public const SubtitleBackgroundColor DefaultBackgroundColor = SubtitleBackgroundColor.Ink;
    public const double DefaultBackgroundOpacity = 1;

    /// <summary>
    /// LibVLC sizes text as picture height ÷ this value, so 18 is about 5.5 %
    /// of the picture, Apple's ratio. Apple's 16–48 pt clamp has no LibVLC
    /// equivalent.
    /// </summary>
    public const int RelativeFontSize = 18;

    private readonly PlayerSettingsStore _store;

    public SubtitleAppearance(PlayerSettingsStore store)
    {
        _store = store;
    }

    public static IReadOnlyList<SubtitleFontStyle> Fonts { get; } =
        [SubtitleFontStyle.System, SubtitleFontStyle.Serif, SubtitleFontStyle.Monospaced];

    public static IReadOnlyList<SubtitleTextColor> TextColors { get; } =
    [
        SubtitleTextColor.Parchment, SubtitleTextColor.White, SubtitleTextColor.Yellow,
        SubtitleTextColor.Cyan, SubtitleTextColor.Green, SubtitleTextColor.Black,
    ];

    public static IReadOnlyList<SubtitleBackgroundColor> BackgroundColors { get; } =
    [
        SubtitleBackgroundColor.Ink, SubtitleBackgroundColor.Black, SubtitleBackgroundColor.Charcoal,
        SubtitleBackgroundColor.Navy, SubtitleBackgroundColor.White,
    ];

    public SubtitleFontStyle Font
    {
        get => _store.GetString(FontKey) switch
        {
            "system" => SubtitleFontStyle.System,
            "serif" => SubtitleFontStyle.Serif,
            "monospaced" => SubtitleFontStyle.Monospaced,
            _ => DefaultFont,
        };
        set => _store.SetString(FontKey, RawValue(value));
    }

    public SubtitleTextColor TextColor
    {
        get => TextColors.Cast<SubtitleTextColor?>().FirstOrDefault(color => RawValue(color!.Value) == _store.GetString(TextColorKey))
            ?? DefaultTextColor;
        set => _store.SetString(TextColorKey, RawValue(value));
    }

    public SubtitleBackgroundColor BackgroundColor
    {
        get => BackgroundColors.Cast<SubtitleBackgroundColor?>().FirstOrDefault(color => RawValue(color!.Value) == _store.GetString(BackgroundColorKey))
            ?? DefaultBackgroundColor;
        set => _store.SetString(BackgroundColorKey, RawValue(value));
    }

    /// <summary>0 removes the box; the outline still keeps text legible.</summary>
    public double BackgroundOpacity
    {
        get => _store.GetDouble(BackgroundOpacityKey) is double stored ? NormalizedOpacity(stored) : DefaultBackgroundOpacity;
        set => _store.SetDouble(BackgroundOpacityKey, NormalizedOpacity(value));
    }

    public bool IsDefault =>
        Font == DefaultFont
        && TextColor == DefaultTextColor
        && BackgroundColor == DefaultBackgroundColor
        && BackgroundOpacity == DefaultBackgroundOpacity;

    public void Reset()
    {
        Font = DefaultFont;
        TextColor = DefaultTextColor;
        BackgroundColor = DefaultBackgroundColor;
        BackgroundOpacity = DefaultBackgroundOpacity;
    }

    /// <summary>Clamps to 0…1 and snaps to whole percent; non-finite becomes the default.</summary>
    public static double NormalizedOpacity(double opacity)
    {
        if (!double.IsFinite(opacity)) return DefaultBackgroundOpacity;
        return Math.Round(Math.Clamp(opacity, 0, 1) * 100, MidpointRounding.AwayFromZero) / 100;
    }

    public static bool Owns(string key) => key is FontKey or TextColorKey or BackgroundColorKey or BackgroundOpacityKey;

    // ------------------------------------------------------------------
    // Values
    // ------------------------------------------------------------------

    public static string RawValue(SubtitleFontStyle font) => font switch
    {
        SubtitleFontStyle.Serif => "serif",
        SubtitleFontStyle.Monospaced => "monospaced",
        _ => "system",
    };

    public static string RawValue(SubtitleTextColor color) => color switch
    {
        SubtitleTextColor.White => "white",
        SubtitleTextColor.Yellow => "yellow",
        SubtitleTextColor.Cyan => "cyan",
        SubtitleTextColor.Green => "green",
        SubtitleTextColor.Black => "black",
        _ => "parchment",
    };

    public static string RawValue(SubtitleBackgroundColor color) => color switch
    {
        SubtitleBackgroundColor.Black => "black",
        SubtitleBackgroundColor.Charcoal => "charcoal",
        SubtitleBackgroundColor.Navy => "navy",
        SubtitleBackgroundColor.White => "white",
        _ => "ink",
    };

    /// <summary>The Windows family LibVLC draws with.</summary>
    public static string FontFamily(SubtitleFontStyle font) => font switch
    {
        SubtitleFontStyle.Serif => "Georgia",
        SubtitleFontStyle.Monospaced => "Consolas",
        _ => "Segoe UI",
    };

    /// <summary>0xRRGGBB.</summary>
    public static int Rgb(SubtitleTextColor color) => color switch
    {
        SubtitleTextColor.White => 0xFFFFFF,
        SubtitleTextColor.Yellow => 0xFFE033,
        SubtitleTextColor.Cyan => 0x59E6FF,
        SubtitleTextColor.Green => 0x73F273,
        SubtitleTextColor.Black => 0x000000,
        _ => 0xE4E1E9,
    };

    /// <summary>0xRRGGBB; Charcoal is 22 % white.</summary>
    public static int Rgb(SubtitleBackgroundColor color) => color switch
    {
        SubtitleBackgroundColor.Black => 0x000000,
        SubtitleBackgroundColor.Charcoal => 0x383838,
        SubtitleBackgroundColor.Navy => 0x0F1A3D,
        SubtitleBackgroundColor.White => 0xFFFFFF,
        _ => 0x0A0A0F,
    };

    /// <summary>Light around black text, the archive's ink around everything else.</summary>
    public static int OutlineRgb(SubtitleTextColor color) => color == SubtitleTextColor.Black ? 0xFFFFFF : 0x0A0A0F;

    public static string DisplayName(SubtitleFontStyle font) => AppText.Get(font switch
    {
        SubtitleFontStyle.Serif => "SubtitleFont_Serif",
        SubtitleFontStyle.Monospaced => "SubtitleFont_Monospaced",
        _ => "SubtitleFont_System",
    });

    public static string DisplayName(SubtitleTextColor color) => AppText.Get(color switch
    {
        SubtitleTextColor.White => "SubtitleColor_White",
        SubtitleTextColor.Yellow => "SubtitleColor_Yellow",
        SubtitleTextColor.Cyan => "SubtitleColor_Cyan",
        SubtitleTextColor.Green => "SubtitleColor_Green",
        SubtitleTextColor.Black => "SubtitleColor_Black",
        _ => "SubtitleColor_Parchment",
    });

    public static string DisplayName(SubtitleBackgroundColor color) => AppText.Get(color switch
    {
        SubtitleBackgroundColor.Black => "SubtitleColor_Black",
        SubtitleBackgroundColor.Charcoal => "SubtitleColor_Charcoal",
        SubtitleBackgroundColor.Navy => "SubtitleColor_Navy",
        SubtitleBackgroundColor.White => "SubtitleColor_White",
        _ => "SubtitleColor_Ink",
    });

    // ------------------------------------------------------------------
    // LibVLC
    // ------------------------------------------------------------------

    /// <summary>
    /// The freetype arguments for these choices. The order is fixed, so equal
    /// choices give equal lists and the player reuses its LibVLC instance.
    /// <paramref name="textScaleFactor"/> is Windows' text-size setting (1…2.25).
    /// </summary>
    public IReadOnlyList<string> LibVlcArguments(double textScaleFactor) =>
        LibVlcArguments(Font, TextColor, BackgroundColor, BackgroundOpacity, textScaleFactor);

    public static IReadOnlyList<string> LibVlcArguments(
        SubtitleFontStyle font,
        SubtitleTextColor textColor,
        SubtitleBackgroundColor backgroundColor,
        double backgroundOpacity,
        double textScaleFactor)
    {
        var opacity = NormalizedOpacity(backgroundOpacity);
        var alpha = (int)Math.Round(opacity * 255, MidpointRounding.AwayFromZero);
        // With no box the outline carries legibility, so it thickens.
        var outline = alpha == 0 ? 4 : 2;
        var scale = double.IsFinite(textScaleFactor) && textScaleFactor > 0 ? textScaleFactor : 1;
        var textScale = Math.Clamp((int)Math.Round(scale * 100, MidpointRounding.AwayFromZero), 10, 500);

        return
        [
            $"--freetype-font={FontFamily(font)}",
            Invariant($"--freetype-color={Rgb(textColor)}"),
            Invariant($"--freetype-background-color={Rgb(backgroundColor)}"),
            Invariant($"--freetype-background-opacity={alpha}"),
            Invariant($"--freetype-outline-color={OutlineRgb(textColor)}"),
            Invariant($"--freetype-outline-thickness={outline}"),
            Invariant($"--freetype-rel-fontsize={RelativeFontSize}"),
            Invariant($"--sub-text-scale={textScale}"),
        ];
    }

    private static string Invariant(FormattableString value) => value.ToString(CultureInfo.InvariantCulture);
}
