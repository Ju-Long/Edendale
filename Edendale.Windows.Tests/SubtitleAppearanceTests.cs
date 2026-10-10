using System.IO;
using Edendale.Windows.Core;
using Edendale.Windows.Services;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Edendale.Windows.Tests;

/// <summary>
/// Settings → Subtitles: defaults, persistence with Apple's keys, opacity
/// snapping, and the mapping to LibVLC's freetype arguments. Ports
/// SubtitleAppearanceTests.swift; the renderer cases become argument cases
/// because LibVLC draws the text.
/// </summary>
[TestClass]
public sealed class SubtitleAppearanceTests
{
    private string _directory = "";
    private string _path = "";

    [TestInitialize]
    public void Init()
    {
        _directory = Path.Combine(Path.GetTempPath(), $"eden-subtitles-{Guid.NewGuid():N}");
        Directory.CreateDirectory(_directory);
        _path = Path.Combine(_directory, "player-settings.json");
    }

    [TestCleanup]
    public void Cleanup()
    {
        try { Directory.Delete(_directory, recursive: true); } catch { /* best effort */ }
    }

    private SubtitleAppearance NewAppearance() => new(new PlayerSettingsStore(_path));

    [TestMethod]
    public void DefaultsKeepTheArchiveLook()
    {
        var appearance = NewAppearance();
        Assert.AreEqual(SubtitleFontStyle.System, appearance.Font);
        Assert.AreEqual(SubtitleTextColor.Parchment, appearance.TextColor);
        Assert.AreEqual(SubtitleBackgroundColor.Ink, appearance.BackgroundColor);
        Assert.AreEqual(1, appearance.BackgroundOpacity);
        Assert.IsTrue(appearance.IsDefault);
    }

    [TestMethod]
    public void ChoicesPersistWithApplesKeys()
    {
        var appearance = NewAppearance();
        appearance.Font = SubtitleFontStyle.Serif;
        appearance.TextColor = SubtitleTextColor.Yellow;
        appearance.BackgroundColor = SubtitleBackgroundColor.Navy;
        appearance.BackgroundOpacity = 0.4;

        var store = new PlayerSettingsStore(_path);
        Assert.AreEqual("serif", store.GetString("subtitles.font"));
        Assert.AreEqual("yellow", store.GetString("subtitles.textColor"));
        Assert.AreEqual("navy", store.GetString("subtitles.backgroundColor"));
        Assert.AreEqual(0.4, store.GetDouble("subtitles.backgroundOpacity"));

        var relaunched = NewAppearance();
        Assert.AreEqual(SubtitleFontStyle.Serif, relaunched.Font);
        Assert.AreEqual(SubtitleTextColor.Yellow, relaunched.TextColor);
        Assert.AreEqual(SubtitleBackgroundColor.Navy, relaunched.BackgroundColor);
        Assert.AreEqual(0.4, relaunched.BackgroundOpacity);
        Assert.IsFalse(relaunched.IsDefault);
    }

    [TestMethod]
    public void OpacityClampsAndSnapsToWholePercent()
    {
        var appearance = NewAppearance();
        appearance.BackgroundOpacity = -0.5;
        Assert.AreEqual(0, appearance.BackgroundOpacity);
        appearance.BackgroundOpacity = 1.7;
        Assert.AreEqual(1, appearance.BackgroundOpacity);
        appearance.BackgroundOpacity = double.NaN;
        Assert.AreEqual(SubtitleAppearance.DefaultBackgroundOpacity, appearance.BackgroundOpacity);
        appearance.BackgroundOpacity = 0.1 + 0.2;
        Assert.AreEqual(0.3, appearance.BackgroundOpacity);
    }

    [TestMethod]
    public void UnrecognizedStoredValuesFallBackToDefaults()
    {
        var store = new PlayerSettingsStore(_path);
        store.SetString("subtitles.font", "comic-sans");
        store.SetString("subtitles.textColor", "plaid");
        store.SetString("subtitles.backgroundColor", "tartan");
        Assert.IsTrue(NewAppearance().IsDefault);
    }

    [TestMethod]
    public void RoundedFromAnotherPlatformFallsBackToSystem()
    {
        new PlayerSettingsStore(_path).SetString("subtitles.font", "rounded");
        Assert.AreEqual(SubtitleFontStyle.System, NewAppearance().Font);
    }

    [TestMethod]
    public void ResetRestoresEveryDefault()
    {
        var appearance = NewAppearance();
        appearance.Font = SubtitleFontStyle.Monospaced;
        appearance.TextColor = SubtitleTextColor.Black;
        appearance.BackgroundColor = SubtitleBackgroundColor.White;
        appearance.BackgroundOpacity = 0;
        appearance.Reset();
        Assert.IsTrue(appearance.IsDefault);
        Assert.IsTrue(NewAppearance().IsDefault);
    }

    [TestMethod]
    public void DefaultLookMapsToParchmentOnInk()
    {
        var arguments = NewAppearance().LibVlcArguments(1.0);
        CollectionAssert.AreEqual(new[]
        {
            "--freetype-font=Segoe UI",
            "--freetype-color=15000041",
            "--freetype-background-color=657935",
            "--freetype-background-opacity=255",
            "--freetype-outline-color=657935",
            "--freetype-outline-thickness=2",
            "--freetype-rel-fontsize=18",
            "--sub-text-scale=100",
        }, arguments.ToArray());
    }

    [TestMethod]
    public void EachFontMapsToAWindowsFamily()
    {
        Assert.AreEqual("Segoe UI", SubtitleAppearance.FontFamily(SubtitleFontStyle.System));
        Assert.AreEqual("Georgia", SubtitleAppearance.FontFamily(SubtitleFontStyle.Serif));
        Assert.AreEqual("Consolas", SubtitleAppearance.FontFamily(SubtitleFontStyle.Monospaced));
    }

    [TestMethod]
    public void ColorsMatchThePalette()
    {
        Assert.AreEqual(0xE4E1E9, SubtitleAppearance.Rgb(SubtitleTextColor.Parchment));
        Assert.AreEqual(0xFFE033, SubtitleAppearance.Rgb(SubtitleTextColor.Yellow));
        Assert.AreEqual(0x59E6FF, SubtitleAppearance.Rgb(SubtitleTextColor.Cyan));
        Assert.AreEqual(0x73F273, SubtitleAppearance.Rgb(SubtitleTextColor.Green));
        Assert.AreEqual(0x0A0A0F, SubtitleAppearance.Rgb(SubtitleBackgroundColor.Ink));
        Assert.AreEqual(0x383838, SubtitleAppearance.Rgb(SubtitleBackgroundColor.Charcoal));
        Assert.AreEqual(0x0F1A3D, SubtitleAppearance.Rgb(SubtitleBackgroundColor.Navy));
    }

    [TestMethod]
    public void OutlineIsLightAroundBlackTextAndInkElsewhere()
    {
        Assert.AreEqual(0xFFFFFF, SubtitleAppearance.OutlineRgb(SubtitleTextColor.Black));
        Assert.AreEqual(0x0A0A0F, SubtitleAppearance.OutlineRgb(SubtitleTextColor.White));
        Assert.AreNotEqual(SubtitleAppearance.OutlineRgb(SubtitleTextColor.Black), SubtitleAppearance.OutlineRgb(SubtitleTextColor.White));
    }

    [TestMethod]
    public void NoBoxRemovesTheBackgroundAndThickensTheOutline()
    {
        var arguments = SubtitleAppearance.LibVlcArguments(
            SubtitleFontStyle.Serif, SubtitleTextColor.Black, SubtitleBackgroundColor.White, 0, 1);
        CollectionAssert.Contains(arguments.ToArray(), "--freetype-background-opacity=0");
        CollectionAssert.Contains(arguments.ToArray(), "--freetype-outline-thickness=4");
        CollectionAssert.Contains(arguments.ToArray(), "--freetype-outline-color=16777215");
        CollectionAssert.Contains(arguments.ToArray(), "--freetype-font=Georgia");
    }

    [TestMethod]
    public void HalfOpacityMapsToHalfAlpha()
    {
        var arguments = SubtitleAppearance.LibVlcArguments(
            SubtitleFontStyle.System, SubtitleTextColor.White, SubtitleBackgroundColor.Black, 0.5, 1);
        CollectionAssert.Contains(arguments.ToArray(), "--freetype-background-opacity=128");
    }

    [TestMethod]
    public void WindowsTextSizeScalesSubtitles()
    {
        CollectionAssert.Contains(NewAppearance().LibVlcArguments(1.5).ToArray(), "--sub-text-scale=150");
        CollectionAssert.Contains(NewAppearance().LibVlcArguments(double.NaN).ToArray(), "--sub-text-scale=100");
        CollectionAssert.Contains(NewAppearance().LibVlcArguments(9).ToArray(), "--sub-text-scale=500");
    }

    [TestMethod]
    public void EqualChoicesGiveEqualArgumentLists()
    {
        var first = NewAppearance().LibVlcArguments(1.25);
        var second = NewAppearance().LibVlcArguments(1.25);
        CollectionAssert.AreEqual(first.ToArray(), second.ToArray());
    }
}
