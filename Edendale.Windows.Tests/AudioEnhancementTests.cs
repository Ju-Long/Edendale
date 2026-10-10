using System.IO;
using Edendale.Windows.Core;
using Edendale.Windows.Services;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Edendale.Windows.Tests;

/// <summary>EQ profiles, clamping, the booster, and reset on profile change. Ports AudioEnhancementTests.swift.</summary>
[TestClass]
public sealed class AudioEnhancementTests
{
    private string _directory = "";
    private string _path = "";

    [TestInitialize]
    public void Init()
    {
        _directory = Path.Combine(Path.GetTempPath(), $"eden-audio-{Guid.NewGuid():N}");
        Directory.CreateDirectory(_directory);
        _path = Path.Combine(_directory, "player-settings.json");
    }

    [TestCleanup]
    public void Cleanup()
    {
        try { Directory.Delete(_directory, recursive: true); } catch { /* best effort */ }
    }

    private AudioEnhancement NewController() => new(new PlayerSettingsStore(_path));

    [TestMethod]
    public void DefaultProfileIsMovies() => Assert.AreEqual(AudioEnhancementProfile.Movies, NewController().SelectedProfile);

    [TestMethod]
    public void ProfilesMatchTheTable()
    {
        foreach (var profile in AudioEnhancementProfiles.All)
        {
            Assert.AreEqual(AudioEnhancementProfiles.BandCount, profile.Bands().Count, profile.RawValue());
            Assert.IsTrue(profile.Preamp() is >= -20 and <= 20);
            Assert.IsTrue(profile.Bands().All(band => band is >= -20 and <= 20));
            // Each preset's preamp offsets its peak boost, which leaves headroom.
            Assert.IsTrue(profile.Preamp() + profile.PeakBandBoost() <= 0, profile.RawValue());
            Assert.IsFalse(string.IsNullOrEmpty(profile.DisplayName()));
        }
        Assert.AreEqual(0, AudioEnhancementProfile.Flat.Preamp());
        Assert.IsTrue(AudioEnhancementProfile.Flat.Bands().All(band => band == 0));
        Assert.AreEqual(-8, AudioEnhancementProfile.Movies.Preamp());
        CollectionAssert.AreEqual(new double[] { 8, 5, 3, 0, 0, 2, 3, 2, 1, 0 }, AudioEnhancementProfile.Movies.Bands().ToArray());
        CollectionAssert.AreEqual(new double[] { 4, 2, 0, -1, -1, 2, 3, 3, 2, 1 }, AudioEnhancementProfile.Music.Bands().ToArray());
        CollectionAssert.AreEqual(new double[] { -3, -1, 0, 5, 6, 5, 3, 1, 0, -1 }, AudioEnhancementProfile.Dialogue.Bands().ToArray());
        CollectionAssert.AreEqual(new double[] { -5, -2, 1, 4, 5, 5, 3, 1, 0, -1 }, AudioEnhancementProfile.NightMode.Bands().ToArray());
        Assert.AreEqual(AudioEnhancementProfiles.BandCount, AudioEnhancementProfiles.BandFrequencyLabels.Count);
    }

    [TestMethod]
    public void RawValuesAreUniqueAndApplesSpelling()
    {
        var raw = AudioEnhancementProfiles.All.Select(profile => profile.RawValue()).ToList();
        Assert.AreEqual(raw.Count, raw.Distinct().Count());
        Assert.AreEqual("nightMode", AudioEnhancementProfile.NightMode.RawValue());
    }

    [TestMethod]
    public void ClampingKeepsValuesInRange()
    {
        Assert.AreEqual(-20, AudioEnhancementProfiles.Clamp(-25));
        Assert.AreEqual(20, AudioEnhancementProfiles.Clamp(25));
        Assert.AreEqual(0, AudioEnhancementProfiles.Clamp(0));
        Assert.AreEqual(5, AudioEnhancementProfiles.Clamp(5));
        Assert.AreEqual(0, AudioEnhancementProfiles.Clamp(double.NaN));
    }

    [TestMethod]
    public void SelectedProfilePersistsAndRestores()
    {
        NewController().SelectProfile(AudioEnhancementProfile.Dialogue);
        var store = new PlayerSettingsStore(_path);
        Assert.AreEqual("dialogue", store.GetString("audio.enhancementProfile"));

        store.SetString("audio.enhancementProfile", "nightMode");
        Assert.AreEqual(AudioEnhancementProfile.NightMode, NewController().SelectedProfile);
    }

    [TestMethod]
    public void InvalidPersistedProfileFallsBackToMovies()
    {
        new PlayerSettingsStore(_path).SetString("audio.enhancementProfile", "nonexistent");
        Assert.AreEqual(AudioEnhancementProfile.Movies, NewController().SelectedProfile);
    }

    [TestMethod]
    public void UserAdjustmentsPersistAndRestore()
    {
        var controller = NewController();
        controller.SetUserPreampAdjustment(5);
        controller.SetUserBandAdjustment(3, 0);
        controller.SetUserBandAdjustment(-2, 9);

        var restored = NewController();
        Assert.AreEqual(5, restored.UserPreampAdjustment);
        Assert.AreEqual(3, restored.UserBandAdjustments[0]);
        Assert.AreEqual(-2, restored.UserBandAdjustments[9]);
    }

    [TestMethod]
    public void ChangingProfileResetsUserAdjustmentsButTheSameProfileKeepsThem()
    {
        var controller = NewController();
        controller.SetUserPreampAdjustment(2);
        controller.SelectProfile(AudioEnhancementProfile.Movies);
        Assert.AreEqual(2, controller.UserPreampAdjustment);

        controller.SetUserBandAdjustment(3, 0);
        controller.SelectProfile(AudioEnhancementProfile.Music);
        Assert.AreEqual(0, controller.UserPreampAdjustment);
        Assert.IsTrue(controller.UserBandAdjustments.All(value => value == 0));
    }

    [TestMethod]
    public void EffectiveValuesAddProfileAndUserAdjustment()
    {
        var controller = NewController();
        controller.SelectProfile(AudioEnhancementProfile.Movies);
        controller.SetUserPreampAdjustment(2);
        Assert.AreEqual(-8 + 2, controller.EffectivePreamp);
        controller.SetUserBandAdjustment(5, 0);
        Assert.AreEqual(13, controller.EffectiveBands[0]);
    }

    [TestMethod]
    public void EffectiveValuesClampAtBoundaries()
    {
        var controller = NewController();
        controller.SetUserPreampAdjustment(20);
        Assert.AreEqual(-8 + 20, controller.EffectivePreamp);
        controller.SetUserBandAdjustment(20, 0);
        Assert.AreEqual(20, controller.EffectiveBands[0]);
    }

    [TestMethod]
    public void FlatnessAndUserAdjustmentTracking()
    {
        var controller = NewController();
        Assert.IsFalse(controller.HasUserAdjustments);
        controller.SelectProfile(AudioEnhancementProfile.Flat);
        Assert.IsTrue(controller.IsEffectivelyFlat);
        controller.SetUserBandAdjustment(1, 0);
        Assert.IsFalse(controller.IsEffectivelyFlat);
        Assert.IsTrue(controller.HasUserAdjustments);
        controller.ResetUserAdjustments();
        Assert.IsTrue(controller.IsEffectivelyFlat);
        Assert.IsFalse(controller.HasUserAdjustments);
        controller.SetUserPreampAdjustment(1);
        Assert.IsTrue(controller.HasUserAdjustments);
    }

    [TestMethod]
    public void SettingABandOutsideTheRangeIsIgnored()
    {
        var controller = NewController();
        var before = controller.UserBandAdjustments.ToArray();
        controller.SetUserBandAdjustment(5, -1);
        controller.SetUserBandAdjustment(5, AudioEnhancementProfiles.BandCount);
        CollectionAssert.AreEqual(before, controller.UserBandAdjustments.ToArray());
    }

    [TestMethod]
    public void ResetClearsAndPersists()
    {
        var controller = NewController();
        controller.SetUserPreampAdjustment(10);
        controller.SetUserBandAdjustment(7, 3);
        controller.ResetUserAdjustments();
        var restored = NewController();
        Assert.AreEqual(0, restored.UserPreampAdjustment);
        Assert.IsTrue(restored.UserBandAdjustments.All(value => value == 0));
    }

    [TestMethod]
    public void StoredBandsWithTheWrongCountAreIgnored()
    {
        new PlayerSettingsStore(_path).SetDoubleArray("audio.enhancementBands", [5, 5, 5, 5, 5]);
        var controller = NewController();
        Assert.AreEqual(AudioEnhancementProfiles.BandCount, controller.UserBandAdjustments.Count);
        Assert.IsTrue(controller.UserBandAdjustments.All(value => value == 0));
    }

    [TestMethod]
    public void BoosterAddsTenDecibelsOfPreampAndRestores()
    {
        var controller = NewController();
        Assert.IsFalse(controller.BoosterEnabled);
        controller.SetUserPreampAdjustment(3);
        controller.SetUserBandAdjustment(-2, 2);
        var unboosted = controller.EffectivePreamp;
        var bands = controller.EffectiveBands.ToArray();

        controller.SetBooster(true);
        Assert.AreEqual(unboosted + AudioEnhancement.BoosterGain, controller.EffectivePreamp);
        controller.SetBooster(true);
        Assert.AreEqual(unboosted + AudioEnhancement.BoosterGain, controller.EffectivePreamp);
        CollectionAssert.AreEqual(bands, controller.EffectiveBands.ToArray());

        var restored = NewController();
        Assert.IsTrue(restored.BoosterEnabled);
        restored.SetBooster(false);
        Assert.AreEqual(unboosted, restored.EffectivePreamp);
        CollectionAssert.AreEqual(bands, restored.EffectiveBands.ToArray());
    }

    [TestMethod]
    public void TogglingBoosterRestoresPreampAfterClamping()
    {
        var controller = NewController();
        controller.SelectProfile(AudioEnhancementProfile.Flat);
        controller.SetUserPreampAdjustment(17);
        controller.SetBooster(true);
        Assert.AreEqual(20, controller.EffectivePreamp);
        controller.SetBooster(false);
        Assert.AreEqual(17, controller.EffectivePreamp);
        Assert.AreEqual(17, controller.UserPreampAdjustment);
    }

    [TestMethod]
    public void CorruptNonFiniteAdjustmentsAreSanitized()
    {
        File.WriteAllText(_path, """{ "audio.enhancementPreamp": "NaN", "audio.enhancementBands": [1e400, 0, 0, 0, 0, 0, 0, 0, 0, 0] }""");
        var controller = NewController();
        Assert.AreEqual(0, controller.UserPreampAdjustment);
        Assert.IsTrue(controller.EffectiveBands.All(double.IsFinite));

        controller.SetUserPreampAdjustment(double.NegativeInfinity);
        controller.SetUserBandAdjustment(double.NaN, 0);
        Assert.IsTrue(double.IsFinite(controller.EffectivePreamp));
        Assert.IsTrue(controller.EffectiveBands.All(double.IsFinite));
    }

    [TestMethod]
    public void TheEqualizerIsRemovedOnlyWhenEverythingIsZero()
    {
        var controller = NewController();
        controller.SelectProfile(AudioEnhancementProfile.Movies);
        Assert.IsFalse(controller.IsEffectivelyFlat);
        controller.SelectProfile(AudioEnhancementProfile.Flat);
        Assert.IsTrue(controller.IsEffectivelyFlat);
        controller.SetBooster(true);
        Assert.IsFalse(controller.IsEffectivelyFlat);
        controller.SetBooster(false);
        Assert.IsTrue(controller.IsEffectivelyFlat);
        controller.SetUserBandAdjustment(4, 5);
        Assert.IsFalse(controller.IsEffectivelyFlat);
    }
}
