using Edendale.Windows.Core;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Edendale.Windows.Tests;

/// <summary>X.4: picking a display refresh rate that is a whole multiple of the frame rate.</summary>
[TestClass]
public sealed class RefreshRateMatchingTests
{
    [TestMethod]
    public void FilmMovesToTheHighestWholeMultiple()
    {
        Assert.AreEqual(144, RefreshRateMatching.Choose(24, 60, [60, 120, 144]));
        Assert.AreEqual(120, RefreshRateMatching.Choose(24, 60, [60, 120, 165]));
        Assert.AreEqual(48, RefreshRateMatching.Choose(24, 60, [48, 50, 60]));
    }

    [TestMethod]
    public void NtscRatesPreferTheirExactModes()
    {
        // 23.976 fps: Windows lists 23.976 Hz as "23", which beats 24 Hz.
        Assert.AreEqual(23, RefreshRateMatching.Choose(24000.0 / 1001, 60, [23, 24, 60]));
        // Without an exact mode, 120 Hz is only 0.1 % off.
        Assert.AreEqual(120, RefreshRateMatching.Choose(24000.0 / 1001, 60, [60, 120]));
        Assert.AreEqual(59, RefreshRateMatching.Choose(30000.0 / 1001, 60, [50, 59, 60]));
    }

    [TestMethod]
    public void KeepsARateThatAlreadyFits()
    {
        Assert.IsNull(RefreshRateMatching.Choose(25, 50, [50, 60, 100]));
        Assert.IsNull(RefreshRateMatching.Choose(30, 60, [60, 120]));
        // Already at an exact multiple: no switch just for a higher one.
        Assert.IsNull(RefreshRateMatching.Choose(24, 120, [60, 120, 144]));
        // A near miss (24 Hz for 23.976 fps) still moves to an exact mode.
        Assert.AreEqual(23, RefreshRateMatching.Choose(24000.0 / 1001, 24, [23, 24]));
    }

    [TestMethod]
    public void LeavesTheDisplayAloneWhenNothingFits()
    {
        Assert.IsNull(RefreshRateMatching.Choose(25, 60, [60, 144]));
        Assert.AreEqual(75, RefreshRateMatching.Choose(25, 60, [60, 75]));
        Assert.IsNull(RefreshRateMatching.Choose(null, 60, [24, 48, 120]));
        Assert.IsNull(RefreshRateMatching.Choose(double.NaN, 60, [24]));
        Assert.IsNull(RefreshRateMatching.Choose(0, 60, [24]));
    }

    [TestMethod]
    public void ReadsWindowsRateLabels()
    {
        Assert.AreEqual(23.976, RefreshRateMatching.ActualRate(23), 0.001);
        Assert.AreEqual(59.94, RefreshRateMatching.ActualRate(59), 0.001);
        Assert.AreEqual(60, RefreshRateMatching.ActualRate(60));
        Assert.IsNotNull(RefreshRateMatching.MultipleError(24, 48));
        Assert.IsNull(RefreshRateMatching.MultipleError(24, 60));
    }
}
