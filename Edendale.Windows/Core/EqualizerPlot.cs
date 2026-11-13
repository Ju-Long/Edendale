// The rules behind Settings → Audio's equalizer graph and preamp slider
// (EqualizerGraph.swift and AudioEnhancementSection.swift, apple-27.1):
// how a level is written, where the graph puts each band and level, and the
// curve through the points. WinUI-free, so Edendale.Windows.Tests links it;
// Controls/EqualizerGraph.cs and Controls/DecibelSlider.cs draw from it.

using System.Globalization;

namespace Edendale.Windows.Core;

/// <summary>
/// "+5 dB", "0 dB", "−3 dB": how Settings writes an equalizer level
/// (Apple's <c>DecibelLabel</c>). A level rounds half away from zero to a
/// whole decibel, as Swift's <c>rounded()</c> does. Windows writes a true
/// minus sign (U+2212), the width of the plus, as its Settings always has;
/// Apple's label prints a hyphen here but uses the true minus on the graph's
/// scale. The unit is not localized, on either platform.
/// </summary>
public static class DecibelLabel
{
    public const string Unit = "dB";

    /// <summary>The true minus sign, the width of the plus.</summary>
    public const char Minus = '−';

    public static string Text(double value)
    {
        var rounded = Whole(value);
        return rounded switch
        {
            > 0 => "+" + Magnitude(rounded) + " " + Unit,
            < 0 => Minus + Magnitude(rounded) + " " + Unit,
            _ => "0 " + Unit,
        };
    }

    /// <summary>
    /// The graph's scale: "+20", "+10", "0", "−10", "−20". Like Apple's
    /// <c>axisLabel</c>, a fraction is cut off rather than rounded.
    /// </summary>
    public static string Axis(double level)
    {
        var value = double.IsFinite(level) ? Math.Truncate(level) : 0;
        return value switch
        {
            > 0 => "+" + Magnitude(value),
            < 0 => Minus + Magnitude(value),
            _ => "0",
        };
    }

    /// <summary>
    /// Reads a level a screen reader or UI Automation client typed:
    /// "+5 dB", "−3 dB", "-3", or "5". The unit is optional and either minus
    /// sign works. False for empty, unreadable, or non-finite text.
    /// </summary>
    public static bool TryParse(string? text, out double level)
    {
        level = 0;
        if (string.IsNullOrWhiteSpace(text)) return false;
        var number = text.Trim();
        if (number.EndsWith(Unit, StringComparison.OrdinalIgnoreCase))
        {
            number = number[..^Unit.Length].TrimEnd();
        }
        number = number.Replace(Minus, '-');
        if (!double.TryParse(number, NumberStyles.Float, CultureInfo.InvariantCulture, out var parsed)
            && !double.TryParse(number, NumberStyles.Float, CultureInfo.CurrentCulture, out parsed))
        {
            return false;
        }
        if (!double.IsFinite(parsed)) return false;
        level = parsed;
        return true;
    }

    private static double Whole(double value) =>
        double.IsFinite(value) ? Math.Round(value, MidpointRounding.AwayFromZero) : 0;

    // The sign is written here, never by the culture: a culture whose own
    // negative sign is already U+2212 (sv-SE on ICU) would otherwise print two.
    private static string Magnitude(double whole) =>
        Math.Abs(whole).ToString("0", CultureInfo.CurrentCulture);
}

/// <summary>A point in the graph's coordinate space, in device-independent pixels.</summary>
public readonly record struct PlotPoint(double X, double Y);

/// <summary>A rectangle in the graph's coordinate space.</summary>
public readonly record struct PlotRect(double X, double Y, double Width, double Height);

/// <summary>One cubic Bézier segment of the curve, ending at a band's point.</summary>
public readonly record struct CurveSegment(PlotPoint Control1, PlotPoint Control2, PlotPoint End);

/// <summary>
/// The curve through every band's point, run flat out to both edges: a line
/// from <see cref="Start"/> to <see cref="First"/>, one cubic segment to each
/// following point, then a line to <see cref="Finish"/>.
/// </summary>
public sealed record EqualizerCurve(PlotPoint Start, PlotPoint First, IReadOnlyList<CurveSegment> Segments, PlotPoint Finish);

/// <summary>
/// Where the graph puts each band and level (Apple's <c>EqualizerPlot</c>):
/// a plot rectangle with a gutter on the leading edge for the dB scale, room
/// above the top level and below the bottom one for a whole point, and a row
/// for the frequency labels underneath. The points, the curve, the touch
/// targets, and the held point's badge all come from here, so the points sit
/// exactly on the line.
/// </summary>
public readonly struct EqualizerPlot
{
    /// <summary>The graph's height, without its 4 px of vertical padding.</summary>
    public const double GraphHeight = 212;

    /// <summary>Room on the leading edge for the dB scale.</summary>
    public const double Gutter = 30;

    /// <summary>Room above +20 dB and below −20 dB for a whole point.</summary>
    public const double VerticalInset = 12;

    /// <summary>The row for the frequency labels under the plot.</summary>
    public const double LabelHeight = 18;

    /// <summary>
    /// A point's touch target is as tall as a fingertip and as wide as its
    /// band's column, but never narrower than this, so neighbouring points
    /// never steal each other's drags.
    /// </summary>
    public const double HitWidthMinimum = 24;

    /// <summary>A point's touch target is as tall as a fingertip; see <see cref="HitWidthMinimum"/>.</summary>
    public const double HitHeight = 44;

    /// <summary>The held point's level badge sits this far above the point.</summary>
    public const double BadgeOffset = 32;

    /// <summary>A badge that would sit above this goes below its point instead.</summary>
    public const double BadgeTopLimit = 12;

    /// <summary>Keeps the badge's centre this far inside the plot, so it stays inside the graph at the edges.</summary>
    public const double BadgeEdgeInset = 26;

    /// <summary>The dB scale and the "Hz" unit are centred on this x.</summary>
    public const double AxisLabelX = Gutter / 2 - 2;

    /// <param name="width">The graph's width.</param>
    /// <param name="height">The graph's height, normally <see cref="GraphHeight"/>.</param>
    /// <param name="count">The number of bands; at least one column is laid out.</param>
    /// <param name="minimum">The bottom level, in dB.</param>
    /// <param name="maximum">The top level, in dB; greater than <paramref name="minimum"/>.</param>
    public EqualizerPlot(
        double width,
        double height,
        int count,
        double minimum = AudioEnhancementProfiles.MinimumAmplification,
        double maximum = AudioEnhancementProfiles.MaximumAmplification)
    {
        Width = Math.Max(width - Gutter, 1);
        Height = Math.Max(height - LabelHeight - VerticalInset * 2, 1);
        Count = Math.Max(count, 1);
        Minimum = minimum;
        Maximum = maximum;
    }

    public double Left => Gutter;
    public double Top => VerticalInset;
    public double Width { get; }
    public double Height { get; }
    public double Right => Left + Width;
    public double Bottom => Top + Height;
    public int Count { get; }
    public double Minimum { get; }
    public double Maximum { get; }

    public double ColumnWidth => Width / Count;

    /// <summary>How far a point moves for 1 dB.</summary>
    public double PointsPerDecibel => Height / (Maximum - Minimum);

    /// <summary>The frequency labels' centre line.</summary>
    public double LabelY => Bottom + VerticalInset + LabelHeight / 2 - 2;

    /// <summary>The scale's ruled levels: both limits, halfway to each, and 0 dB.</summary>
    public IReadOnlyList<double> GridLevels => [Maximum, Maximum / 2, 0, Minimum / 2, Minimum];

    /// <summary>0 dB and both limits are solid rules; the halfway rules are dashed.</summary>
    public bool IsSolidGridLine(double level) => level == 0 || Math.Abs(level) == Maximum;

    /// <summary>The centre of a band's column.</summary>
    public double X(int band) => Left + (band + 0.5) * ColumnWidth;

    public double Y(double level) => Bottom - (level - Minimum) / (Maximum - Minimum) * Height;

    public PlotPoint Point(int band, double level) => new(X(band), Y(level));

    /// <summary>Clamps a level into the range; NaN counts as 0 dB.</summary>
    public double Clamp(double level) =>
        Math.Min(Math.Max(double.IsNaN(level) ? 0 : level, Minimum), Maximum);

    /// <summary>
    /// The level a drag sets: the level the point had when it was grabbed,
    /// moved by the pointer's vertical travel since then (up is louder),
    /// rounded half away from zero to a whole decibel and clamped. Swift's
    /// <c>rounded()</c> rounds half away from zero; .NET's default rounds
    /// half to even, which would make a half-step drag stick.
    /// </summary>
    /// <param name="startLevel">The point's level when it was grabbed.</param>
    /// <param name="deltaY">The pointer's travel since then, downward positive.</param>
    public double DragLevel(double startLevel, double deltaY)
    {
        var level = Math.Round(startLevel - deltaY / PointsPerDecibel, MidpointRounding.AwayFromZero);
        return Clamp(double.IsFinite(level) ? level : startLevel);
    }

    /// <summary>A point's touch target, centred on the point.</summary>
    public PlotRect HitBox(int band, double level)
    {
        var width = Math.Max(ColumnWidth, HitWidthMinimum);
        return new PlotRect(X(band) - width / 2, Y(level) - HitHeight / 2, width, HitHeight);
    }

    /// <summary>
    /// The centre of the held point's level badge: above the point, or below
    /// it when the point is near the top, and kept inside the graph at the
    /// edges. Apple's min-of-max order is kept on purpose: a plot too narrow
    /// for both insets must not throw, as <see cref="Math.Clamp(double, double, double)"/> would.
    /// </summary>
    public PlotPoint BadgeCenter(int band, double level)
    {
        var pointY = Y(level);
        var above = pointY - BadgeOffset;
        var y = above < BadgeTopLimit ? pointY + BadgeOffset : above;
        var x = Math.Min(Math.Max(X(band), Left + BadgeEdgeInset), Right - BadgeEdgeInset);
        return new PlotPoint(x, y);
    }

    /// <summary>
    /// The curve through one point per level (band 0 first), or null when
    /// there are no levels. Catmull–Rom through the points, with the handles
    /// kept inside the plot so the line never overshoots past the range.
    /// </summary>
    public EqualizerCurve? Curve(IReadOnlyList<double> levels)
    {
        if (levels.Count == 0) return null;
        var points = new PlotPoint[levels.Count];
        for (var band = 0; band < points.Length; band++)
        {
            points[band] = Point(band, levels[band]);
        }

        var segments = new CurveSegment[points.Length - 1];
        for (var index = 0; index < segments.Length; index++)
        {
            var p0 = points[Math.Max(index - 1, 0)];
            var p1 = points[index];
            var p2 = points[index + 1];
            var p3 = points[Math.Min(index + 2, points.Length - 1)];
            segments[index] = new CurveSegment(
                new PlotPoint(p1.X + (p2.X - p0.X) / 6, ClampY(p1.Y + (p2.Y - p0.Y) / 6)),
                new PlotPoint(p2.X - (p3.X - p1.X) / 6, ClampY(p2.Y - (p3.Y - p1.Y) / 6)),
                p2);
        }

        return new EqualizerCurve(
            new PlotPoint(Left, points[0].Y),
            points[0],
            segments,
            new PlotPoint(Right, points[^1].Y));
    }

    private double ClampY(double y) => Math.Min(Math.Max(y, Top), Bottom);
}
