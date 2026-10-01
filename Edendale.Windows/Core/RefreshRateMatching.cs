namespace Edendale.Windows.Core;

/// <summary>
/// X.4: in full screen, the display switches to a refresh rate that is a
/// whole multiple of the source's frame rate (24p at 48, 72, 120, or 144 Hz)
/// when the monitor offers one, which removes judder at no GPU cost and gives
/// Motion Smoothing an even cadence.
/// </summary>
public static class RefreshRateMatching
{
    /// <summary>How far from a whole multiple still counts (24 Hz for 23.976 fps is 0.1 % off).</summary>
    public const double Tolerance = 0.0015;

    /// <summary>
    /// The rate a mode reports, as a real rate: Windows lists the NTSC rates
    /// (23.976, 29.97, 59.94, 119.88 Hz) as 23, 29, 59, and 119.
    /// </summary>
    public static double ActualRate(int hz) =>
        hz is 23 or 29 or 47 or 59 or 71 or 119 or 143 ? (hz + 1) * 1000.0 / 1001 : hz;

    /// <summary>How far <paramref name="hz"/> is from a whole multiple of <paramref name="fps"/>, or null when it isn't one.</summary>
    public static double? MultipleError(double fps, int hz)
    {
        var ratio = ActualRate(hz) / fps;
        var nearest = Math.Round(ratio);
        if (nearest < 1) return null;
        var error = Math.Abs(ratio - nearest) / nearest;
        return error <= Tolerance ? error : null;
    }

    /// <summary>
    /// The refresh rate to switch to, or null to keep <paramref name="currentHz"/>:
    /// when the frame rate is unknown, when the current rate already fits as
    /// well as any other, or when nothing on offer fits. Among the fitting
    /// rates the closest wins, then the highest.
    /// </summary>
    public static int? Choose(double? sourceFps, int currentHz, IEnumerable<int> availableHz)
    {
        if (sourceFps is not double fps || double.IsNaN(fps) || fps < 10 || fps > 120) return null;
        var best = availableHz
            .Distinct()
            .Select(hz => (Hz: hz, Error: MultipleError(fps, hz)))
            .Where(candidate => candidate.Error is not null)
            .OrderBy(candidate => candidate.Error)
            .ThenByDescending(candidate => candidate.Hz)
            .Select(candidate => ((int Hz, double? Error)?)candidate)
            .FirstOrDefault();
        if (best is not { } chosen) return null;
        if (MultipleError(fps, currentHz) is double current && current <= chosen.Error!.Value + 1e-9) return null;
        return chosen.Hz == currentHz ? null : chosen.Hz;
    }
}
