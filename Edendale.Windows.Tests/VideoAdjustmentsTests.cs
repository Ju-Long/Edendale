using System.Globalization;
using System.IO;
using Edendale.Windows.Core;
using Edendale.Windows.Services;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Edendale.Windows.Tests;

/// <summary>Picture adjustment ranges, normalization, persistence, and LibVLC hue mapping (DIFF.md §3.7).</summary>
[TestClass]
public sealed class VideoAdjustmentsTests
{
    private string _directory = "";
    private string _path = "";

    [TestInitialize]
    public void Init()
    {
        _directory = Path.Combine(Path.GetTempPath(), $"eden-picture-{Guid.NewGuid():N}");
        Directory.CreateDirectory(_directory);
        _path = Path.Combine(_directory, "player-settings.json");
    }

    [TestCleanup]
    public void Cleanup()
    {
        try { Directory.Delete(_directory, recursive: true); } catch { /* best effort */ }
    }

    [TestMethod]
    public void RangesNeutralsAndStepsMatchTheTable()
    {
        Assert.AreEqual((0.0, 2.0, 1.0, 0.05), Row(VideoAdjustment.Brightness));
        Assert.AreEqual((0.0, 2.0, 1.0, 0.05), Row(VideoAdjustment.Contrast));
        Assert.AreEqual((0.25, 3.0, 1.0, 0.05), Row(VideoAdjustment.Gamma));
        Assert.AreEqual((0.0, 3.0, 1.0, 0.05), Row(VideoAdjustment.Saturation));
        Assert.AreEqual((0.0, 360.0, 0.0, 5.0), Row(VideoAdjustment.Hue));

        static (double, double, double, double) Row(VideoAdjustment adjustment) =>
            (adjustment.Minimum(), adjustment.Maximum(), adjustment.Neutral(), adjustment.Step());
    }

    [TestMethod]
    public void ValuesClampAndSnapToTheirStep()
    {
        Assert.AreEqual(2, VideoAdjustment.Brightness.Normalized(7));
        Assert.AreEqual(0, VideoAdjustment.Contrast.Normalized(-1));
        Assert.AreEqual(0.25, VideoAdjustment.Gamma.Normalized(0.1));
        Assert.AreEqual(1.05, VideoAdjustment.Saturation.Normalized(1.06));
        Assert.AreEqual(45, VideoAdjustment.Hue.Normalized(44));
        Assert.AreEqual(360, VideoAdjustment.Hue.Normalized(400));
        Assert.AreEqual(1, VideoAdjustment.Brightness.Normalized(double.NaN));
        Assert.AreEqual(0, VideoAdjustment.Hue.Normalized(double.PositiveInfinity));
    }

    [TestMethod]
    public void NeutralIsTheDefaultAndSkipsFiltering()
    {
        var adjustments = new VideoAdjustments(new PlayerSettingsStore(_path));
        Assert.IsTrue(adjustments.Values.IsNeutral);
        adjustments.Set(VideoAdjustment.Contrast, 1.2);
        Assert.IsFalse(adjustments.Values.IsNeutral);
        adjustments.Reset();
        Assert.IsTrue(adjustments.Values.IsNeutral);
    }

    [TestMethod]
    public void ValuesPersistAsOneJsonValueAndNormalizeOnLoad()
    {
        var adjustments = new VideoAdjustments(new PlayerSettingsStore(_path));
        adjustments.Set(VideoAdjustment.Brightness, 1.25);
        adjustments.Set(VideoAdjustment.Hue, 90);
        StringAssert.Contains(File.ReadAllText(_path), "\"video.adjustments\"");

        var restored = new VideoAdjustments(new PlayerSettingsStore(_path)).Values;
        Assert.AreEqual(1.25, restored.Brightness);
        Assert.AreEqual(90, restored.Hue);

        File.WriteAllText(_path, """{ "video.adjustments": { "brightness": 9, "gamma": 0.01, "hue": 33 } }""");
        var normalized = new VideoAdjustments(new PlayerSettingsStore(_path)).Values;
        Assert.AreEqual(2, normalized.Brightness);
        Assert.AreEqual(0.25, normalized.Gamma);
        Assert.AreEqual(35, normalized.Hue);
        Assert.AreEqual(1, normalized.Contrast, "missing fields keep their neutral value");
    }

    [TestMethod]
    public void UnreadableValueFallsBackToNeutral()
    {
        File.WriteAllText(_path, """{ "video.adjustments": "bright" }""");
        Assert.IsTrue(new VideoAdjustments(new PlayerSettingsStore(_path)).Values.IsNeutral);
    }

    [TestMethod]
    public void ShowOriginalAppliesNeutralWithoutChangingTheStoredValues()
    {
        var adjustments = new VideoAdjustments(new PlayerSettingsStore(_path));
        adjustments.Set(VideoAdjustment.Saturation, 1.5);
        adjustments.IsShowingOriginal = true;
        Assert.IsTrue(adjustments.EffectiveValues.IsNeutral);
        Assert.AreEqual(1.5, adjustments.Values.Saturation);

        adjustments.Set(VideoAdjustment.Saturation, 1.6);
        Assert.IsFalse(adjustments.IsShowingOriginal, "an edit ends the comparison");
    }

    [TestMethod]
    public void StepsMoveByOneIncrementAndStopAtTheEdges()
    {
        var adjustments = new VideoAdjustments(new PlayerSettingsStore(_path));
        Assert.AreEqual(1.05, adjustments.Step(VideoAdjustment.Brightness, 1));
        Assert.AreEqual(1.0, adjustments.Step(VideoAdjustment.Brightness, -1));
        for (var step = 0; step < 50; step++) adjustments.Step(VideoAdjustment.Brightness, 1);
        Assert.AreEqual(2, adjustments.Values.Brightness);
    }

    [TestMethod]
    public void HueMapsOntoLibVlcsSignedRange()
    {
        Assert.AreEqual(0, VideoAdjustmentValues.LibVlcHue(0));
        Assert.AreEqual(180, VideoAdjustmentValues.LibVlcHue(180));
        Assert.AreEqual(-175, VideoAdjustmentValues.LibVlcHue(185));
        Assert.AreEqual(-90, VideoAdjustmentValues.LibVlcHue(270));
        Assert.AreEqual(0, VideoAdjustmentValues.LibVlcHue(360));
    }

    [TestMethod]
    public void LabelsUseTheReadersNumberFormat()
    {
        Assert.AreEqual("1.05", VideoAdjustment.Brightness.Label(1.05, CultureInfo.InvariantCulture));
        Assert.AreEqual("1,05", VideoAdjustment.Brightness.Label(1.05, new CultureInfo("de-DE")));
        Assert.AreEqual("45°", VideoAdjustment.Hue.Label(45, CultureInfo.InvariantCulture));
    }
}
