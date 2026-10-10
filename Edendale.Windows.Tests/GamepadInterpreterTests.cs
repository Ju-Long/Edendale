using Edendale.Windows.Core;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Edendale.Windows.Tests;

/// <summary>X.1: controller readings to player actions.</summary>
[TestClass]
public sealed class GamepadInterpreterTests
{
    private static TimeSpan At(double seconds) => TimeSpan.FromSeconds(seconds);

    [TestMethod]
    public void ButtonsActOncePerPress()
    {
        var pad = new GamepadInterpreter();
        CollectionAssert.AreEqual(new[] { PadAction.PlayPause }, pad.Update(PadButtons.A, 0, 0, At(0)).ToArray());
        Assert.AreEqual(0, pad.Update(PadButtons.A, 0, 0, At(0.5)).Count);
        Assert.AreEqual(0, pad.Update(PadButtons.None, 0, 0, At(0.6)).Count);
        CollectionAssert.AreEqual(new[] { PadAction.PlayPause }, pad.Update(PadButtons.A, 0, 0, At(0.7)).ToArray());

        CollectionAssert.AreEquivalent(
            new[] { PadAction.Back, PadAction.SkipBackward, PadAction.SkipForward, PadAction.ToggleFullScreen, PadAction.Adjustments },
            pad.Update(PadButtons.B | PadButtons.LeftBumper | PadButtons.RightBumper | PadButtons.View | PadButtons.Menu, 0, 0, At(1)).ToArray());
    }

    [TestMethod]
    public void TheDPadRepeatsWhileHeld()
    {
        var pad = new GamepadInterpreter();
        CollectionAssert.AreEqual(new[] { PadAction.SeekForward }, pad.Update(PadButtons.DPadRight, 0, 0, At(0)).ToArray());
        Assert.AreEqual(0, pad.Update(PadButtons.DPadRight, 0, 0, At(0.3)).Count);
        CollectionAssert.AreEqual(new[] { PadAction.SeekForward }, pad.Update(PadButtons.DPadRight, 0, 0, At(0.41)).ToArray());
        Assert.AreEqual(0, pad.Update(PadButtons.DPadRight, 0, 0, At(0.5)).Count);
        CollectionAssert.AreEqual(new[] { PadAction.SeekForward }, pad.Update(PadButtons.DPadRight, 0, 0, At(0.57)).ToArray());
        Assert.AreEqual(0, pad.Update(PadButtons.None, 0, 0, At(0.6)).Count);
        CollectionAssert.AreEqual(new[] { PadAction.VolumeUp }, pad.Update(PadButtons.DPadUp, 0, 0, At(0.7)).ToArray());
        CollectionAssert.AreEqual(new[] { PadAction.VolumeDown }, pad.Update(PadButtons.DPadDown, 0, 0, At(0.8)).ToArray());
    }

    [TestMethod]
    public void TriggersHoldWithTheSiriRemoteHysteresis()
    {
        var pad = new GamepadInterpreter();
        Assert.AreEqual(0, pad.Update(PadButtons.None, 0, 0.5, At(0)).Count);
        CollectionAssert.AreEqual(new[] { PadAction.HoldRightStart }, pad.Update(PadButtons.None, 0, 0.56, At(0.1)).ToArray());
        Assert.AreEqual(HoldSide.Right, pad.Hold);
        // Easing to 0.35 keeps the hold; below 0.30 releases it.
        Assert.AreEqual(0, pad.Update(PadButtons.None, 0, 0.35, At(0.2)).Count);
        CollectionAssert.AreEqual(new[] { PadAction.HoldEnd }, pad.Update(PadButtons.None, 0, 0.29, At(0.3)).ToArray());
        Assert.IsNull(pad.Hold);

        CollectionAssert.AreEqual(new[] { PadAction.HoldLeftStart }, pad.Update(PadButtons.None, 0.9, 0, At(0.4)).ToArray());
        // Switching triggers moves the hold without a gap.
        CollectionAssert.AreEqual(new[] { PadAction.HoldRightStart }, pad.Update(PadButtons.None, 0.1, 0.9, At(0.5)).ToArray());
        CollectionAssert.AreEqual(new[] { PadAction.HoldEnd }, pad.Reset().ToArray());
        Assert.AreEqual(0, pad.Reset().Count);
    }
}
