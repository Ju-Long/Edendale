using System.Globalization;
using Edendale.Windows.Core;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Edendale.Windows.Tests;

/// <summary>
/// The player's pure rules: level steps, the rate grid, hold sides, natural
/// end, skips, and timestamps. Ports the matching PlayerLogicTests.swift cases.
/// </summary>
[TestClass]
public sealed class PlayerLogicTests
{
    private const double Epsilon = 1e-9;

    [TestMethod]
    public void NormalizedLevelsStepAndClamp()
    {
        Assert.AreEqual(0.55, PlayerLogic.AdjustedLevel(0.5, 0.05), Epsilon);
        Assert.AreEqual(0.45, PlayerLogic.AdjustedLevel(0.5, -0.05), Epsilon);
        Assert.AreEqual(1, PlayerLogic.AdjustedLevel(0.98, 0.05), Epsilon);
        Assert.AreEqual(0, PlayerLogic.AdjustedLevel(0.02, -0.05), Epsilon);
    }

    [TestMethod]
    public void RateStepsInFiveHundredthsIncrements()
    {
        Assert.AreEqual(1.05, PlayerLogic.IncrementedRate(1.0), Epsilon);
        Assert.AreEqual(0.95, PlayerLogic.DecrementedRate(1.0), Epsilon);
        var rate = 1.0;
        for (var step = 0; step < 7; step++) rate = PlayerLogic.IncrementedRate(rate);
        Assert.AreEqual(1.35, rate, 0.0001);
    }

    [TestMethod]
    public void RateClampsToSupportedRange()
    {
        Assert.AreEqual(PlayerLogic.MinRate, PlayerLogic.DecrementedRate(PlayerLogic.MinRate));
        Assert.AreEqual(PlayerLogic.MaxRate, PlayerLogic.IncrementedRate(PlayerLogic.MaxRate));
        Assert.AreEqual(PlayerLogic.MaxRate, PlayerLogic.NormalizedRate(17));
        Assert.AreEqual(PlayerLogic.MinRate, PlayerLogic.NormalizedRate(-2));
    }

    [TestMethod]
    public void RateSnapsOntoGrid()
    {
        Assert.AreEqual(1.0, PlayerLogic.NormalizedRate(1.02), Epsilon);
        Assert.AreEqual(1.15, PlayerLogic.NormalizedRate(1.13), Epsilon);
        Assert.AreEqual("1.50×", PlayerLogic.RateLabel(1.5, CultureInfo.InvariantCulture));
        Assert.AreEqual("1,50×", PlayerLogic.RateLabel(1.5, new CultureInfo("de-DE")));
        Assert.AreEqual("2×", PlayerLogic.ShortRateLabel(2.0, CultureInfo.InvariantCulture));
        Assert.AreEqual("0.75×", PlayerLogic.ShortRateLabel(0.75, CultureInfo.InvariantCulture));
    }

    [TestMethod]
    public void RateGridRunsFromQuarterToTripleSpeedInFiveHundredths()
    {
        var grid = PlayerLogic.RateGrid;
        Assert.AreEqual(56, grid.Count);
        Assert.AreEqual(0.25, grid[0], Epsilon);
        Assert.AreEqual(3.0, grid[^1], Epsilon);
        Assert.IsTrue(grid.Any(rate => PlayerLogic.RatesEqual(rate, 1.0)));
        Assert.IsTrue(grid.Zip(grid.Skip(1)).All(pair => Math.Abs(pair.Second - pair.First - 0.05) < Epsilon));
    }

    [TestMethod]
    public void RatesCompareWithATolerance()
    {
        // LibVLC hands back single-precision rates, so 1.25f must equal 1.25.
        Assert.IsTrue(PlayerLogic.RatesEqual((float)1.1, 1.1));
        Assert.IsFalse(PlayerLogic.RatesEqual(1.1, 1.15));
    }

    [TestMethod]
    public void HoldSideArmsFromTheRestingSide()
    {
        Assert.IsNull(PlayerLogic.HoldSideFor(0.4, 0, active: false));
        Assert.AreEqual(HoldSide.Right, PlayerLogic.HoldSideFor(0.6, 0, active: false));
        Assert.AreEqual(HoldSide.Left, PlayerLogic.HoldSideFor(-0.6, 0, active: false));
    }

    [TestMethod]
    public void HoldSideHoldsThroughDriftOnceActive()
    {
        Assert.AreEqual(HoldSide.Right, PlayerLogic.HoldSideFor(0.4, 0, active: true));
        Assert.AreEqual(HoldSide.Left, PlayerLogic.HoldSideFor(-0.4, 0, active: true));
        Assert.IsNull(PlayerLogic.HoldSideFor(0.2, 0, active: true));
    }

    [TestMethod]
    public void HoldSideIgnoresVerticalDominantInput()
    {
        Assert.IsNull(PlayerLogic.HoldSideFor(0.6, 0.7, active: false));
        Assert.IsNull(PlayerLogic.HoldSideFor(0.6, 0.7, active: true));
    }

    [TestMethod]
    public void PointerHoldSideSplitsTheVideoInHalves()
    {
        Assert.AreEqual(HoldSide.Left, PlayerLogic.PointerHoldSide(10, 1000));
        Assert.AreEqual(HoldSide.Left, PlayerLogic.PointerHoldSide(499, 1000));
        Assert.AreEqual(HoldSide.Right, PlayerLogic.PointerHoldSide(500, 1000));
        Assert.AreEqual(HoldSide.Left, PlayerLogic.PointerHoldSide(500, 0));
    }

    [TestMethod]
    public void DoubleTapZonesAreThirds()
    {
        Assert.AreEqual(PlayerLogic.TapZone.Left, PlayerLogic.DoubleTapZone(100, 900));
        Assert.AreEqual(PlayerLogic.TapZone.Center, PlayerLogic.DoubleTapZone(450, 900));
        Assert.AreEqual(PlayerLogic.TapZone.Right, PlayerLogic.DoubleTapZone(800, 900));
        Assert.AreEqual(PlayerLogic.TapZone.Center, PlayerLogic.DoubleTapZone(10, 0));
    }

    [TestMethod]
    public void TimestampsFormatAcrossHourBoundary()
    {
        Assert.AreEqual("0:00", PlayerLogic.Timestamp(0));
        Assert.AreEqual("0:59", PlayerLogic.Timestamp(59.9));
        Assert.AreEqual("1:05", PlayerLogic.Timestamp(65));
        Assert.AreEqual("59:59", PlayerLogic.Timestamp(3599));
        Assert.AreEqual("1:00:00", PlayerLogic.Timestamp(3600));
        Assert.AreEqual("1:23:45", PlayerLogic.Timestamp(5025));
        Assert.AreEqual("0:00", PlayerLogic.Timestamp(-4));
        Assert.AreEqual("0:00", PlayerLogic.Timestamp(double.NaN));
    }

    [TestMethod]
    public void NaturalEndFiresNearNinetyFivePercent()
    {
        var duration = TimeSpan.FromSeconds(1200);
        Assert.IsFalse(PlayerLogic.IsNaturalEnd(TimeSpan.FromSeconds(600), duration));
        Assert.IsTrue(PlayerLogic.IsNaturalEnd(TimeSpan.FromSeconds(1152), duration));
        Assert.IsFalse(PlayerLogic.IsNaturalEnd(TimeSpan.Zero, duration));
        Assert.IsFalse(PlayerLogic.IsNaturalEnd(TimeSpan.FromSeconds(1152), null));
    }

    [TestMethod]
    public void NaturalEndKeepsTwoSecondArmForShortClips()
    {
        var clip = TimeSpan.FromSeconds(30);
        Assert.IsTrue(PlayerLogic.IsNaturalEnd(TimeSpan.FromSeconds(28.2), clip));
        Assert.IsFalse(PlayerLogic.IsNaturalEnd(TimeSpan.FromSeconds(27), clip));
    }

    [TestMethod]
    public void SkipTargetsClampToTheTimeline()
    {
        Assert.AreEqual(40_000, PlayerLogic.SkipTarget(30_000, 10, 100_000));
        Assert.AreEqual(0, PlayerLogic.SkipTarget(5_000, -10, 100_000));
        Assert.AreEqual(100_000, PlayerLogic.SkipTarget(95_000, 30, 100_000));
        // Unknown duration: only the lower bound applies.
        Assert.AreEqual(45_000, PlayerLogic.SkipTarget(30_000, 15, 0));
    }
}
