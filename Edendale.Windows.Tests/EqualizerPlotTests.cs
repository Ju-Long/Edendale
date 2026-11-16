using System.Globalization;
using Edendale.Windows.Core;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Edendale.Windows.Tests;

/// <summary>
/// Settings → Audio's equalizer graph geometry and level text
/// (EqualizerGraph.swift's EqualizerPlot and DecibelLabel, apple-27.1).
/// The reference plot is 330 × 212 with ten bands: a 300 × 170 plot area
/// after the 30 px scale gutter, the 12 px insets, and the 18 px label row.
/// </summary>
[TestClass]
public sealed class EqualizerPlotTests
{
    private const char Minus = '−';
    private static readonly EqualizerPlot Plot = new(330, EqualizerPlot.GraphHeight, 10);

    [TestMethod]
    public void ThePlotAreaLeavesRoomForTheScaleAndLabels()
    {
        Assert.AreEqual(30, Plot.Left);
        Assert.AreEqual(12, Plot.Top);
        Assert.AreEqual(300, Plot.Width);
        Assert.AreEqual(170, Plot.Height);
        Assert.AreEqual(330, Plot.Right);
        Assert.AreEqual(182, Plot.Bottom);
        Assert.AreEqual(30, Plot.ColumnWidth);
        Assert.AreEqual(4.25, Plot.PointsPerDecibel);
        Assert.AreEqual(201, Plot.LabelY);
    }

    [TestMethod]
    public void BandsSitInTheMiddleOfTheirColumns()
    {
        Assert.AreEqual(45, Plot.X(0));
        Assert.AreEqual(315, Plot.X(9));
    }

    [TestMethod]
    public void LevelsMapTopToBottom()
    {
        Assert.AreEqual(12, Plot.Y(20));
        Assert.AreEqual(54.5, Plot.Y(10));
        Assert.AreEqual(97, Plot.Y(0));
        Assert.AreEqual(182, Plot.Y(-20));
    }

    [TestMethod]
    public void TheScaleRulesBothLimitsHalfwayAndZero()
    {
        CollectionAssert.AreEqual(new double[] { 20, 10, 0, -10, -20 }, Plot.GridLevels.ToArray());
        Assert.IsTrue(Plot.IsSolidGridLine(0));
        Assert.IsTrue(Plot.IsSolidGridLine(20));
        Assert.IsTrue(Plot.IsSolidGridLine(-20));
        Assert.IsFalse(Plot.IsSolidGridLine(10));
        Assert.IsFalse(Plot.IsSolidGridLine(-10));
    }

    [TestMethod]
    public void DegenerateSizesStillLayOut()
    {
        Assert.AreEqual(1, new EqualizerPlot(10, EqualizerPlot.GraphHeight, 10).Width);
        Assert.AreEqual(1, new EqualizerPlot(330, 10, 10).Height);
        var noBands = new EqualizerPlot(330, EqualizerPlot.GraphHeight, 0);
        Assert.AreEqual(noBands.Width, noBands.ColumnWidth);
    }

    [TestMethod]
    public void DraggingMovesWholeDecibelsRoundingHalfAwayFromZero()
    {
        Assert.AreEqual(3, Plot.DragLevel(0, -12.75));
        Assert.AreEqual(-1, Plot.DragLevel(0, 2.125));
        Assert.AreEqual(9, Plot.DragLevel(8, -2.125));
        Assert.AreEqual(-20, Plot.DragLevel(8, 119));
        Assert.AreEqual(20, Plot.DragLevel(18, -500));
        Assert.AreEqual(-20, Plot.DragLevel(-18, 500));
        Assert.AreEqual(5, Plot.DragLevel(5, 0));
    }

    [TestMethod]
    public void TouchTargetsAreCentredOnThePointAndNeverTooNarrow()
    {
        Assert.AreEqual(new PlotRect(30, 75, 30, 44), Plot.HitBox(0, 0));
        var tight = new EqualizerPlot(230, EqualizerPlot.GraphHeight, 10);
        Assert.AreEqual(20, tight.ColumnWidth);
        Assert.AreEqual(24, tight.HitBox(0, 0).Width);
        Assert.AreEqual(tight.X(0) - 12, tight.HitBox(0, 0).X);
    }

    [TestMethod]
    public void TheBadgeSitsAboveThePointInsideTheGraph()
    {
        Assert.AreEqual(new PlotPoint(56, 65), Plot.BadgeCenter(0, 0));
        Assert.AreEqual(304, Plot.BadgeCenter(9, 0).X);
        // Too near the top for the badge above: it goes below.
        Assert.AreEqual(44, Plot.BadgeCenter(3, 20).Y);
        Assert.AreEqual(14, Plot.BadgeCenter(3, 12).Y);
        // A plot too narrow for both insets keeps a badge rather than throwing.
        var narrow = new EqualizerPlot(40, EqualizerPlot.GraphHeight, 10);
        var center = narrow.BadgeCenter(0, 0);
        Assert.IsTrue(double.IsFinite(center.X) && double.IsFinite(center.Y));
    }

    [TestMethod]
    public void AFlatCurveIsAStraightLineAtZero()
    {
        var curve = Plot.Curve(new double[10]);
        Assert.IsNotNull(curve);
        Assert.AreEqual(new PlotPoint(30, 97), curve.Start);
        Assert.AreEqual(new PlotPoint(45, 97), curve.First);
        Assert.AreEqual(new PlotPoint(330, 97), curve.Finish);
        Assert.AreEqual(9, curve.Segments.Count);
        Assert.AreEqual(new PlotPoint(50, 97), curve.Segments[0].Control1);
        Assert.AreEqual(new PlotPoint(65, 97), curve.Segments[0].Control2);
        Assert.AreEqual(new PlotPoint(75, 97), curve.Segments[0].End);
        foreach (var segment in curve.Segments)
        {
            Assert.AreEqual(97, segment.Control1.Y);
            Assert.AreEqual(97, segment.Control2.Y);
        }
    }

    [TestMethod]
    public void TheCurveNeverOvershootsThePlot()
    {
        var levels = Enumerable.Range(0, 10).Select(band => band % 2 == 0 ? 20.0 : -20.0).ToArray();
        var curve = Plot.Curve(levels)!;
        foreach (var segment in curve.Segments)
        {
            foreach (var y in new[] { segment.Control1.Y, segment.Control2.Y, segment.End.Y })
            {
                Assert.IsTrue(y >= Plot.Top && y <= Plot.Bottom, $"{y}");
            }
        }
        Assert.IsNull(Plot.Curve([]));
    }

    [TestMethod]
    public void TheScaleUsesATrueMinus()
    {
        Assert.AreEqual("+20", DecibelLabel.Axis(20));
        Assert.AreEqual("+10", DecibelLabel.Axis(10));
        Assert.AreEqual("0", DecibelLabel.Axis(0));
        Assert.AreEqual(Minus + "10", DecibelLabel.Axis(-10));
        Assert.AreEqual(Minus + "20", DecibelLabel.Axis(-20));
    }

    [TestMethod]
    public void LevelsReadInWholeSignedDecibels()
    {
        Assert.AreEqual("+5 dB", DecibelLabel.Text(5));
        Assert.AreEqual("0 dB", DecibelLabel.Text(0));
        Assert.AreEqual(Minus + "3 dB", DecibelLabel.Text(-3));
        Assert.AreEqual("0 dB", DecibelLabel.Text(-0.4));
        Assert.AreEqual("+3 dB", DecibelLabel.Text(2.5));
        Assert.AreEqual(Minus + "3 dB", DecibelLabel.Text(-2.5));
        Assert.AreEqual("0 dB", DecibelLabel.Text(double.NaN));
    }

    [TestMethod]
    public void ACultureWithATrueMinusOfItsOwnStillGetsOne()
    {
        var saved = CultureInfo.CurrentCulture;
        try
        {
            CultureInfo.CurrentCulture = new CultureInfo("sv-SE");
            var text = DecibelLabel.Text(-3);
            Assert.AreEqual(Minus + "3 dB", text);
            Assert.AreEqual(1, text.Count(c => c is '−' or '-'));
            Assert.AreEqual(Minus + "20", DecibelLabel.Axis(-20));
        }
        finally
        {
            CultureInfo.CurrentCulture = saved;
        }
    }

    [TestMethod]
    public void TypedLevelsParse()
    {
        for (var level = -20; level <= 20; level++)
        {
            Assert.IsTrue(DecibelLabel.TryParse(DecibelLabel.Text(level), out var parsed), level.ToString());
            Assert.AreEqual(level, parsed);
        }
        Assert.IsTrue(DecibelLabel.TryParse("-3", out var hyphen));
        Assert.AreEqual(-3, hyphen);
        Assert.IsTrue(DecibelLabel.TryParse("+5", out var plus));
        Assert.AreEqual(5, plus);
        Assert.IsTrue(DecibelLabel.TryParse("5 dB", out var unit));
        Assert.AreEqual(5, unit);
        Assert.IsFalse(DecibelLabel.TryParse("", out _));
        Assert.IsFalse(DecibelLabel.TryParse(null, out _));
        Assert.IsFalse(DecibelLabel.TryParse("abc", out _));
        Assert.IsFalse(DecibelLabel.TryParse("Infinity", out _));
    }
}
