using System.IO;
using Edendale.Windows.Core;
using Edendale.Windows.Services;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Edendale.Windows.Tests;

/// <summary>
/// Settings → App Controls: skip lengths and press-and-hold speeds, their
/// persistence and bounds. Ports PlayerControlPreferencesTests.swift.
/// </summary>
[TestClass]
public sealed class PlayerControlPreferencesTests
{
    private string _directory = "";
    private string _path = "";

    [TestInitialize]
    public void Init()
    {
        _directory = Path.Combine(Path.GetTempPath(), $"eden-controls-{Guid.NewGuid():N}");
        Directory.CreateDirectory(_directory);
        _path = Path.Combine(_directory, "player-settings.json");
    }

    [TestCleanup]
    public void Cleanup()
    {
        try { Directory.Delete(_directory, recursive: true); } catch { /* best effort */ }
    }

    private PlayerControlPreferences NewControls(out PlayerSettingsStore store)
    {
        store = new PlayerSettingsStore(_path);
        return new PlayerControlPreferences(store);
    }

    [TestMethod]
    public void DefaultsSkipTenSecondsAndHoldAtHalfAndDoubleSpeed()
    {
        var controls = NewControls(out _);

        Assert.AreEqual(SkipInterval.Ten, controls.SkipBackwardInterval);
        Assert.AreEqual(SkipInterval.Ten, controls.SkipForwardInterval);
        Assert.AreEqual(0.5, controls.HoldRate(HoldSide.Left));
        Assert.AreEqual(2.0, controls.HoldRate(HoldSide.Right));
    }

    [TestMethod]
    public void ChoicesPersistForTheNextLaunch()
    {
        var controls = NewControls(out _);
        controls.SkipBackwardInterval = SkipInterval.Thirty;
        controls.SkipForwardInterval = SkipInterval.Fifteen;
        controls.SetHoldRate(0.75, HoldSide.Left);
        controls.SetHoldRate(2.5, HoldSide.Right);

        var relaunched = new PlayerControlPreferences(new PlayerSettingsStore(_path));
        Assert.AreEqual(SkipInterval.Thirty, relaunched.SkipBackwardInterval);
        Assert.AreEqual(SkipInterval.Fifteen, relaunched.SkipForwardInterval);
        Assert.AreEqual(0.75, relaunched.HoldRate(HoldSide.Left));
        Assert.AreEqual(2.5, relaunched.HoldRate(HoldSide.Right));
    }

    [TestMethod]
    public void ChoicesUseApplesKeys()
    {
        var controls = NewControls(out var store);
        controls.SkipBackwardInterval = SkipInterval.Fifteen;
        controls.SetHoldRate(1.5, HoldSide.Right);

        Assert.AreEqual(15, store.GetInt("player.skipBackwardSeconds"));
        Assert.AreEqual(1.5, store.GetDouble("player.holdRightRate"));
    }

    [TestMethod]
    public void SkipOffsetsAreSignedByDirection()
    {
        var controls = NewControls(out _);
        controls.SkipBackwardInterval = SkipInterval.Fifteen;
        controls.SkipForwardInterval = SkipInterval.Thirty;

        Assert.AreEqual(-15, controls.SkipOffset(SkipDirection.Backward));
        Assert.AreEqual(30, controls.SkipOffset(SkipDirection.Forward));
    }

    [TestMethod]
    public void HoldRatesSnapToQuarterStepsWithinTheSpeedRange()
    {
        var controls = NewControls(out _);

        controls.SetHoldRate(0.6, HoldSide.Left);
        Assert.AreEqual(0.5, controls.HoldRate(HoldSide.Left));
        controls.SetHoldRate(1.4, HoldSide.Right);
        Assert.AreEqual(1.5, controls.HoldRate(HoldSide.Right));
        controls.SetHoldRate(9, HoldSide.Right);
        Assert.AreEqual(PlayerLogic.MaxRate, controls.HoldRate(HoldSide.Right));
        controls.SetHoldRate(0.05, HoldSide.Left);
        Assert.AreEqual(PlayerLogic.MinRate, controls.HoldRate(HoldSide.Left));
        controls.SetHoldRate(double.NaN, HoldSide.Left);
        Assert.AreEqual(PlayerLogic.MinRate, controls.HoldRate(HoldSide.Left));
    }

    [TestMethod]
    public void NonFiniteHoldRateNormalizesToTheMinimum()
    {
        Assert.AreEqual(PlayerLogic.MinRate, PlayerControlPreferences.NormalizedHoldRate(double.PositiveInfinity));
        Assert.AreEqual(PlayerLogic.MinRate, PlayerControlPreferences.NormalizedHoldRate(double.NaN));
    }

    [TestMethod]
    public void UnrecognizedStoredValuesFallBack()
    {
        File.WriteAllText(_path, """{ "player.skipForwardSeconds": 12, "player.holdRightRate": 7.3 }""");
        var controls = new PlayerControlPreferences(new PlayerSettingsStore(_path));

        Assert.AreEqual(PlayerControlPreferences.DefaultSkipInterval, controls.SkipForwardInterval);
        Assert.AreEqual(PlayerLogic.MaxRate, controls.HoldRate(HoldSide.Right));
    }

    [TestMethod]
    public void SkipLengthChangesReachTheSystemTransport()
    {
        var controls = NewControls(out _);
        var changes = 0;
        controls.SkipIntervalsChanged += (_, _) => changes++;

        controls.SkipBackwardInterval = SkipInterval.Fifteen;
        controls.SkipForwardInterval = SkipInterval.Thirty;
        controls.SetHoldRate(1.0, HoldSide.Left);

        Assert.AreEqual(2, changes);
    }

    [TestMethod]
    public void ChangesInTheStoreAreReadAtTheNextGesture()
    {
        var controls = NewControls(out var store);
        store.SetInt(PlayerControlPreferences.SkipForwardKey, 30);
        Assert.AreEqual(30, controls.SkipOffset(SkipDirection.Forward));
    }
}
