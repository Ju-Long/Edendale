using System.Globalization;

namespace Edendale.Windows.Core;

/// <summary>The side of the picture a press-and-hold rests on.</summary>
public enum HoldSide
{
    Left,
    Right,
}

/// <summary>
/// Pure, WinUI-free playback rules: the speed grid, normalized volume and
/// brightness steps, press-and-hold sides, natural-end detection, and time
/// formatting. Ports PlayerLogic.swift's rules so the domain tests can cover
/// them without a player.
/// </summary>
public static class PlayerLogic
{
    // ------------------------------------------------------------------
    // Normalized levels (volume, brightness)
    // ------------------------------------------------------------------

    /// <summary>Keyboard and wheel volume steps, as a fraction of full scale.</summary>
    public const double LevelStep = 0.05;

    /// <summary>
    /// <paramref name="level"/> moved by <paramref name="delta"/>, snapped to
    /// hundredths so repeated steps never drift, and kept inside 0…1.
    /// </summary>
    public static double AdjustedLevel(double level, double delta)
    {
        var hundredths = Math.Round((level + delta) * 100, MidpointRounding.AwayFromZero);
        return Math.Clamp(hundredths / 100, 0, 1);
    }

    // ------------------------------------------------------------------
    // Playback speed
    // ------------------------------------------------------------------

    public const double RateStep = 0.05;
    public const double MinRate = 0.25;
    public const double MaxRate = 3.0;

    /// <summary>Rates closer than this are the same grid point (LibVLC reports floats).</summary>
    public const double RateTolerance = 0.001;

    /// <summary>
    /// Snaps a rate onto the 0.05 grid and clamps it to 0.25…3.00. Snapping
    /// runs in whole hundredths so stepping 1.05, 1.10, … stays exact.
    /// </summary>
    public static double NormalizedRate(double rate)
    {
        if (!double.IsFinite(rate)) return 1.0;
        var hundredths = Math.Round(rate * 100, MidpointRounding.AwayFromZero);
        var snapped = Math.Round(hundredths / 5, MidpointRounding.AwayFromZero) * 5 / 100;
        return Math.Clamp(snapped, MinRate, MaxRate);
    }

    public static double IncrementedRate(double rate) => NormalizedRate(rate + RateStep);

    public static double DecrementedRate(double rate) => NormalizedRate(rate - RateStep);

    /// <summary>Compares two rates on the grid rather than as exact floats.</summary>
    public static bool RatesEqual(double left, double right) => Math.Abs(left - right) < RateTolerance;

    /// <summary>Every grid point from 0.25× to 3.00×, for a speed picker.</summary>
    public static IReadOnlyList<double> RateGrid { get; } =
        [.. Enumerable.Range(5, 56).Select(step => step * 5 / 100.0)];

    /// <summary>"1.05×" in the reader's decimal convention.</summary>
    public static string RateLabel(double rate, CultureInfo? culture = null) =>
        string.Format(culture ?? CultureInfo.CurrentCulture, "{0:0.00}×", rate);

    /// <summary>"2×", "1.5×", "0.75×": the shortest form, for the HUD.</summary>
    public static string ShortRateLabel(double rate, CultureInfo? culture = null) =>
        string.Format(culture ?? CultureInfo.CurrentCulture, "{0:0.##}×", rate);

    // ------------------------------------------------------------------
    // Press-and-hold speed (touchpads and triggers)
    // ------------------------------------------------------------------

    /// <summary>Horizontal magnitude (0…1 from center) that arms a hold.</summary>
    public const double HoldArmMagnitude = 0.55;

    /// <summary>The lower magnitude at which an armed hold lets go (hysteresis).</summary>
    public const double HoldReleaseMagnitude = 0.30;

    /// <summary>
    /// The side a deflection at (<paramref name="x"/>, <paramref name="y"/>)
    /// holds, or null when it is too centered or mostly vertical. Once
    /// <paramref name="active"/>, the lower release threshold applies so
    /// small drift doesn't drop the hold. <paramref name="x"/> runs −1…+1.
    /// </summary>
    public static HoldSide? HoldSideFor(double x, double y, bool active)
    {
        var threshold = active ? HoldReleaseMagnitude : HoldArmMagnitude;
        if (Math.Abs(x) < threshold || Math.Abs(x) <= Math.Abs(y)) return null;
        return x >= 0 ? HoldSide.Right : HoldSide.Left;
    }

    /// <summary>
    /// The side of the video a pointer at <paramref name="x"/> within a surface
    /// <paramref name="width"/> wide rests on: the left or right half.
    /// </summary>
    public static HoldSide PointerHoldSide(double x, double width) =>
        width > 0 && x >= width / 2 ? HoldSide.Right : HoldSide.Left;

    /// <summary>Which third of the surface a double tap landed in.</summary>
    public enum TapZone
    {
        Left,
        Center,
        Right,
    }

    public static TapZone DoubleTapZone(double x, double width)
    {
        if (width <= 0) return TapZone.Center;
        if (x < width / 3) return TapZone.Left;
        if (x > width * 2 / 3) return TapZone.Right;
        return TapZone.Center;
    }

    /// <summary>How long a press must rest before it becomes a speed hold.</summary>
    public static readonly TimeSpan HoldDelay = TimeSpan.FromSeconds(0.4);

    /// <summary>Movement (in DIPs) beyond which a press is a drag, not a hold or a click.</summary>
    public const double DragThreshold = 8;

    // ------------------------------------------------------------------
    // End of media
    // ------------------------------------------------------------------

    /// <summary>
    /// Whether playback at <paramref name="time"/> of <paramref name="duration"/>
    /// counts as reaching the end: past 95 %, or within two seconds of the end
    /// for short clips.
    /// </summary>
    public static bool IsNaturalEnd(TimeSpan time, TimeSpan? duration)
    {
        if (duration is not { } length || length <= TimeSpan.Zero) return false;
        return time.TotalMilliseconds >= length.TotalMilliseconds * 0.95
            || time >= length - TimeSpan.FromSeconds(2);
    }

    // ------------------------------------------------------------------
    // Seeking
    // ------------------------------------------------------------------

    /// <summary>The target time of a relative skip, clamped to the timeline.</summary>
    public static long SkipTarget(long currentMilliseconds, double offsetSeconds, long durationMilliseconds)
    {
        var target = currentMilliseconds + (long)Math.Round(offsetSeconds * 1000);
        if (durationMilliseconds > 0) target = Math.Min(target, durationMilliseconds);
        return Math.Max(0, target);
    }

    // ------------------------------------------------------------------
    // Time formatting
    // ------------------------------------------------------------------

    /// <summary>"1:23:45" from an hour up, "23:45" below; invalid input reads "0:00".</summary>
    public static string Timestamp(double seconds)
    {
        if (!double.IsFinite(seconds) || seconds < 0) return "0:00";
        var total = (long)Math.Floor(seconds);
        var hours = total / 3600;
        var minutes = total % 3600 / 60;
        var secs = total % 60;
        return hours > 0
            ? string.Create(CultureInfo.InvariantCulture, $"{hours}:{minutes:00}:{secs:00}")
            : string.Create(CultureInfo.InvariantCulture, $"{minutes}:{secs:00}");
    }
}
